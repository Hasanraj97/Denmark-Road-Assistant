"""
Denmark Road Condition ML service.

Serves a RandomForest that grades a route Excellent .. Very Poor, and explains
which features drove that grade.

Two things changed structurally from the original version:

1. **Feature derivation moved here.** The backend used to compute the 19 model
   features in Java using formulas that had drifted away from the ones used to
   build the training data. It now sends raw observations - distance, duration,
   departure time, an urban signal and the real Open-Meteo weather - and
   ``features.derive_features`` produces the vector. Since the training set is
   built by calling that same function, the two cannot disagree.

2. **Explanations are measured, not written by hand.** The old influential-feature
   list was a chain of if-statements with fixed thresholds that had no connection
   to what the model actually did. Each explanation is now produced by perturbing
   one feature at a time to its dataset-typical value and measuring how far the
   predicted probability moves.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
from contextlib import asynccontextmanager
from datetime import datetime
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder

from .dataset import generate_dataset
from .features import (
    CATEGORICAL_COLUMNS,
    FEATURE_COLUMNS,
    LABELS,
    NUMERIC_COLUMNS,
    RouteObservation,
    derive_features,
)

logger = logging.getLogger("ml-service")

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
MODEL_DIR = BASE_DIR / "models"
DATASET_PATH = DATA_DIR / "synthetic_denmark_routes.csv"
MODEL_PATH = MODEL_DIR / "road_condition_model.joblib"
METADATA_PATH = MODEL_DIR / "road_condition_model_metadata.json"
# The model that was live before the latest retrain, kept so it can be restored.
PREVIOUS_MODEL_PATH = MODEL_DIR / "road_condition_model.previous.joblib"
PREVIOUS_METADATA_PATH = MODEL_DIR / "road_condition_model_metadata.previous.json"

SAMPLE_COUNT = 12000
RANDOM_SEED = 42

DISCLAIMER = (
    "The weather in this advisory is a real Open-Meteo forecast. The road-condition "
    "grade comes from a model trained on generated data, so treat it as an "
    "illustration of the method rather than a measurement of Danish roads."
)


# ---------------------------------------------------------------------------
# Request and response shapes
# ---------------------------------------------------------------------------

class WeatherInput(BaseModel):
    temperature_c: float = Field(alias="temperatureC")
    rainfall_mm: float = Field(alias="rainfallMm")
    humidity: float
    wind_speed_kmh: float = Field(alias="windSpeedKmh")
    # Where the weather came from: "open-meteo" for a live forecast, or
    # "seasonal-estimate" when the backend had to fall back. The advisory must
    # never call a fallback "live", so a missing value is treated as unknown.
    source: str | None = None

    model_config = {"populate_by_name": True}


class ObservationInput(BaseModel):
    """The raw facts the backend knows about one trip."""

    distance_km: float = Field(alias="distanceKm")
    duration_min: float = Field(alias="durationMin")
    # ISO-8601 instant, already resolved by the backend. One timestamp supplies
    # the hour, the weekday AND the season, so they can never disagree.
    departure_iso: str = Field(alias="departureIso")
    urban_signal: float = Field(alias="urbanSignal")
    weather: WeatherInput

    model_config = {"populate_by_name": True}


class RouteFeatures(BaseModel):
    """The legacy shape: a fully derived feature vector supplied by the caller.

    Kept so that ``POST /api/predict`` and the tests can drive a specific vector
    directly. The preferred path is ``observation``.
    """

    distance_km: float = Field(alias="distanceKm")
    estimated_duration_min: float = Field(alias="estimatedDurationMin")
    avg_speed_kmh: float = Field(alias="avgSpeedKmh")
    road_type_score: float = Field(alias="roadTypeScore")
    traffic_level: float = Field(alias="trafficLevel")
    weather_risk: float = Field(alias="weatherRisk")
    rainfall_mm: float = Field(alias="rainfallMm")
    time_of_day: str = Field(alias="timeOfDay")
    day_of_week: str = Field(alias="dayOfWeek")
    urban_density: float = Field(alias="urbanDensity")
    accident_risk_score: float = Field(alias="accidentRiskScore")
    construction_risk_score: float = Field(alias="constructionRiskScore")
    elevation_variation: float = Field(alias="elevationVariation")
    temperature_c: float = Field(alias="temperatureC")
    humidity: float
    wind_speed_kmh: float = Field(alias="windSpeedKmh")
    season: str
    region_type: str = Field(alias="regionType")
    vehicle_load_factor: float = Field(alias="vehicleLoadFactor")

    model_config = {"populate_by_name": True}


class PredictionRequest(BaseModel):
    """Exactly one of ``observation`` or ``features`` must be supplied."""

    observation: ObservationInput | None = None
    features: RouteFeatures | None = None


class FeatureInfluence(BaseModel):
    feature: str
    value: str
    impact: str


class PredictionResponse(BaseModel):
    roadConditionLabel: str
    confidence: float
    leaveEarlyMinutes: int
    advisory: str
    influentialFeatures: list[FeatureInfluence]
    # Features that make the predicted grade LESS likely (largest first).
    counterFeatures: list[FeatureInfluence] = []
    classProbabilities: dict[str, float]
    features: dict[str, Any]
    syntheticPrediction: bool = True
    disclaimer: str = DISCLAIMER


class ModelInfoResponse(BaseModel):
    modelName: str
    sampleCount: int
    accuracy: float
    labels: list[str]
    featureImportances: dict[str, float]
    syntheticDisclaimer: str


# ---------------------------------------------------------------------------
# Human-readable names, used in explanations
# ---------------------------------------------------------------------------

FEATURE_LABELS = {
    "traffic_level": "Traffic level",
    "weather_risk": "Weather risk",
    "rainfall_mm": "Rainfall",
    "wind_speed_kmh": "Wind speed",
    "temperature_c": "Temperature",
    "humidity": "Humidity",
    "urban_density": "Built-up surroundings",
    "accident_risk_score": "Accident exposure",
    "construction_risk_score": "Roadworks risk",
    "vehicle_load_factor": "How loaded the road is",
    "road_type_score": "Road grade",
    "avg_speed_kmh": "Average speed",
    "distance_km": "Distance",
    "estimated_duration_min": "Journey time",
    "elevation_variation": "Terrain variation",
    "time_of_day": "Time of day",
    "day_of_week": "Day of week",
    "season": "Season",
    "region_type": "Region type",
}

FEATURE_UNITS = {
    "rainfall_mm": " mm/h",
    "wind_speed_kmh": " km/h",
    "temperature_c": " °C",
    "avg_speed_kmh": " km/h",
    "distance_km": " km",
    "estimated_duration_min": " min",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _to_camel_case(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(part.title() for part in tail)


def _features_for_api(features: dict[str, Any]) -> dict[str, Any]:
    """
    Present the feature vector in camelCase.

    Internally every feature name is snake_case, which is the Python convention
    and matches the CSV column headers. The JSON API and the Angular frontend
    both speak camelCase, so the conversion happens here, at the boundary,
    rather than being smeared through the model code.
    """
    return {_to_camel_case(key): value for key, value in features.items()}


def _format_value(name: str, value: Any) -> str:
    if isinstance(value, str):
        return value.replace("_", " ")
    unit = FEATURE_UNITS.get(name, "")
    return f"{float(value):.2f}".rstrip("0").rstrip(".") + unit


class ModelManager:
    def __init__(self) -> None:
        self.pipeline: Pipeline | None = None
        self.metadata: dict[str, Any] = {}
        # Typical values, used as the neutral baseline when measuring how much
        # each feature moved a particular prediction.
        self.baseline: dict[str, Any] = {}
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        MODEL_DIR.mkdir(parents=True, exist_ok=True)

    # -- lifecycle ----------------------------------------------------------

    def startup(self) -> None:
        """Load the saved model, and refuse to serve one that does not check out.

        Training is a deliberate step. If no model exists the service only trains
        one when ML_BOOTSTRAP_TRAINING=1 is set (the start scripts set it for the
        very first run); otherwise it stops with a clear message instead of quietly
        producing a new model that could change every advisory.
        """
        if MODEL_PATH.exists() and METADATA_PATH.exists() and DATASET_PATH.exists():
            self.metadata = json.loads(METADATA_PATH.read_text(encoding="utf-8"))
            actual = _sha256(MODEL_PATH)
            expected = self.metadata.get("model_sha256")
            if expected is None:
                # Model saved before checksums existed: record it once, loudly.
                logger.warning("Model has no recorded checksum; recording %s now.", actual)
                self.metadata["model_sha256"] = actual
                METADATA_PATH.write_text(json.dumps(self.metadata, indent=2), encoding="utf-8")
            elif expected != actual:
                raise RuntimeError(
                    "The saved model does not match its recorded checksum, so it will not be served. "
                    "Restore the previous model or retrain deliberately."
                )
            self.pipeline = joblib.load(MODEL_PATH)
            self._serve_single_threaded()
            self._load_baseline()
            logger.info(
                "Loaded model version %s. Accuracy %s on %s samples.",
                self.metadata.get("model_version", "unversioned"),
                self.metadata.get("accuracy"),
                self.metadata.get("sample_count"),
            )
        elif os.environ.get("ML_BOOTSTRAP_TRAINING") == "1":
            logger.info("No saved model found and ML_BOOTSTRAP_TRAINING=1: training the first model.")
            self.retrain()
        else:
            raise RuntimeError(
                "No trained model found. Training is a deliberate step: start the service once "
                "with ML_BOOTSTRAP_TRAINING=1 (the start-all scripts do this on first run)."
            )

    def _serve_single_threaded(self) -> None:
        """Predict on one thread; training still uses every core.

        With n_jobs=-1 every predict_proba call starts and stops a joblib thread
        pool. Under Python 3.14 on Windows that pool can crash when it shuts down
        ("concurrent send_bytes() calls are not supported"), and the request then
        fails with a 500. The system test on 9 November 2026 hit this once in
        about thirty predictions. A request only scores one route, plus a few
        rows for the explanation, so one thread is just as fast, and the pool and
        the crash are avoided entirely. Predictions are unchanged.
        """
        if self.pipeline is not None:
            self.pipeline.named_steps["classifier"].set_params(n_jobs=1)

    def _load_baseline(self) -> None:
        frame = pd.read_csv(DATASET_PATH)
        self.baseline = self._baseline_from(frame)

    @staticmethod
    def _baseline_from(frame: pd.DataFrame) -> dict[str, Any]:
        baseline: dict[str, Any] = {}
        for column in NUMERIC_COLUMNS:
            baseline[column] = float(frame[column].median())
        for column in CATEGORICAL_COLUMNS:
            baseline[column] = str(frame[column].mode().iloc[0])
        return baseline

    # -- training -----------------------------------------------------------

    def retrain(self) -> ModelInfoResponse:
        dataset = generate_dataset(SAMPLE_COUNT, seed=RANDOM_SEED)
        dataset.to_csv(DATASET_PATH, index=False)
        self.baseline = self._baseline_from(dataset)

        x_train, x_test, y_train, y_test = train_test_split(
            dataset[FEATURE_COLUMNS],
            dataset["road_condition_label"],
            test_size=0.2,
            random_state=RANDOM_SEED,
            stratify=dataset["road_condition_label"],
        )

        preprocessor = ColumnTransformer(
            transformers=[
                ("numeric", "passthrough", NUMERIC_COLUMNS),
                (
                    "categorical",
                    OneHotEncoder(handle_unknown="ignore", sparse_output=False),
                    CATEGORICAL_COLUMNS,
                ),
            ]
        )

        # Fewer, shallower trees than the original 280 x unlimited depth. That
        # model serialised to 82 MB, which is far too large to keep alongside a
        # student project and had to be git-ignored. min_samples_leaf=4 and a
        # depth cap cost very little accuracy and shrink the artefact by orders
        # of magnitude. class_weight keeps the rarer grades learnable.
        classifier = RandomForestClassifier(
            n_estimators=200,
            max_depth=18,
            min_samples_leaf=4,
            random_state=RANDOM_SEED,
            class_weight="balanced_subsample",
            n_jobs=-1,
        )

        self.pipeline = Pipeline(
            steps=[("preprocessor", preprocessor), ("classifier", classifier)]
        )
        self.pipeline.fit(x_train, y_train)

        predictions = self.pipeline.predict(x_test)
        accuracy = accuracy_score(y_test, predictions)
        report_text = classification_report(y_test, predictions, labels=LABELS, zero_division=0)
        report_dict = classification_report(
            y_test, predictions, labels=LABELS, zero_division=0, output_dict=True
        )
        confusion = confusion_matrix(y_test, predictions, labels=LABELS).tolist()

        class_balance = {
            label: int((dataset["road_condition_label"] == label).sum()) for label in LABELS
        }

        self.metadata = {
            "model_name": "RandomForestClassifier",
            "sample_count": int(len(dataset)),
            "accuracy": round(float(accuracy), 4),
            "labels": LABELS,
            "feature_importances": self._aggregate_feature_importances(),
            "classification_report": report_text,
            "per_class": {
                label: {
                    "precision": round(float(report_dict[label]["precision"]), 4),
                    "recall": round(float(report_dict[label]["recall"]), 4),
                    "f1": round(float(report_dict[label]["f1-score"]), 4),
                    "support": int(report_dict[label]["support"]),
                }
                for label in LABELS
            },
            "confusion_matrix": confusion,
            "class_balance": class_balance,
            "hyperparameters": {
                "n_estimators": 200,
                "max_depth": 18,
                "min_samples_leaf": 4,
                "class_weight": "balanced_subsample",
                "random_state": RANDOM_SEED,
            },
            "training_seed": RANDOM_SEED,
            "synthetic_disclaimer": DISCLAIMER,
        }

        self._serve_single_threaded()

        # Keep the model that was live until now, so a bad retrain can be rolled back.
        if MODEL_PATH.exists() and METADATA_PATH.exists():
            shutil.copy2(MODEL_PATH, PREVIOUS_MODEL_PATH)
            shutil.copy2(METADATA_PATH, PREVIOUS_METADATA_PATH)

        # compress=3 trades a little load time for a much smaller file.
        joblib.dump(self.pipeline, MODEL_PATH, compress=3)
        self.metadata["model_version"] = datetime.now().strftime("%Y%m%d-%H%M%S") + f"-seed{RANDOM_SEED}"
        self.metadata["model_sha256"] = _sha256(MODEL_PATH)
        METADATA_PATH.write_text(json.dumps(self.metadata, indent=2), encoding="utf-8")

        logger.info("Trained model. Accuracy %.4f", accuracy)
        logger.info("\n%s", report_text)
        return self.get_model_info()

    def _aggregate_feature_importances(self) -> dict[str, float]:
        assert self.pipeline is not None
        preprocessor: ColumnTransformer = self.pipeline.named_steps["preprocessor"]
        classifier: RandomForestClassifier = self.pipeline.named_steps["classifier"]
        transformed_names = preprocessor.get_feature_names_out()
        importances = classifier.feature_importances_

        aggregated: dict[str, float] = {feature: 0.0 for feature in FEATURE_COLUMNS}
        for name, importance in zip(transformed_names, importances, strict=True):
            raw_name = name.split("__", maxsplit=1)[1]
            matched = False
            for feature in CATEGORICAL_COLUMNS:
                if raw_name == feature or raw_name.startswith(f"{feature}_"):
                    aggregated[feature] += float(importance)
                    matched = True
                    break
            if matched:
                continue
            for feature in NUMERIC_COLUMNS:
                if raw_name == feature:
                    aggregated[feature] += float(importance)
                    break

        ordered = sorted(aggregated.items(), key=lambda item: item[1], reverse=True)
        return {key: round(value, 4) for key, value in ordered}

    # -- prediction ---------------------------------------------------------

    def predict(self, features: dict[str, Any], weather_source: str = "unknown") -> PredictionResponse:
        if self.pipeline is None:
            raise RuntimeError("Model pipeline is not loaded.")

        frame = pd.DataFrame([{column: features[column] for column in FEATURE_COLUMNS}])
        probabilities = self.pipeline.predict_proba(frame)[0]
        classes = list(self.pipeline.named_steps["classifier"].classes_)

        predicted_index = int(np.argmax(probabilities))
        predicted_label = str(classes[predicted_index])
        confidence = round(float(probabilities[predicted_index]), 4)

        probability_map = {
            str(label): round(float(probabilities[index]), 4)
            for index, label in enumerate(classes)
        }
        ordered_probabilities = {label: probability_map.get(label, 0.0) for label in LABELS}

        influences, counter = self._explain(features, predicted_label, predicted_index)
        leave_early = self._leave_early_minutes(predicted_label, features)
        advisory = self._advisory_text(
            predicted_label, confidence, leave_early, features, influences, weather_source
        )

        return PredictionResponse(
            roadConditionLabel=predicted_label,
            confidence=confidence,
            leaveEarlyMinutes=leave_early,
            advisory=advisory,
            influentialFeatures=influences,
            counterFeatures=counter,
            classProbabilities=ordered_probabilities,
            features=_features_for_api(features),
        )

    def _explain(
        self, features: dict[str, Any], predicted_label: str, predicted_index: int
    ) -> tuple[list[FeatureInfluence], list[FeatureInfluence]]:
        """
        Measure what actually drove this prediction.

        For every feature we build a copy of this route with that one feature
        reset to its dataset-typical value, ask the model again, and record how
        far the probability of the predicted grade falls. A large fall means that
        feature is genuinely holding the prediction up.

        All 19 variants go through the model in a single batched call, so this
        costs about as much as one extra prediction.
        """
        assert self.pipeline is not None

        base_row = {column: features[column] for column in FEATURE_COLUMNS}
        variants = []
        for column in FEATURE_COLUMNS:
            variant = dict(base_row)
            variant[column] = self.baseline.get(column, base_row[column])
            variants.append(variant)

        frames = pd.DataFrame([base_row] + variants)
        probabilities = self.pipeline.predict_proba(frames)[:, predicted_index]
        base_probability = float(probabilities[0])

        contributions = []
        for offset, column in enumerate(FEATURE_COLUMNS, start=1):
            # Positive contribution = this feature pushes TOWARDS the predicted grade.
            contributions.append((column, base_probability - float(probabilities[offset])))

        contributions.sort(key=lambda item: item[1], reverse=True)

        influences: list[FeatureInfluence] = []
        for column, contribution in contributions[:4]:
            if contribution <= 0.005:
                continue
            influences.append(
                FeatureInfluence(
                    feature=FEATURE_LABELS.get(column, column),
                    value=_format_value(column, features[column]),
                    impact=self._impact_sentence(column, features[column], contribution, predicted_label),
                )
            )

        if not influences:
            # Everything about this route is close to typical, which is itself
            # the explanation.
            top_column = contributions[0][0]
            influences.append(
                FeatureInfluence(
                    feature=FEATURE_LABELS.get(top_column, top_column),
                    value=_format_value(top_column, features[top_column]),
                    impact="Nothing about this route stands out from a typical Danish trip, "
                           "so the grade rests on the overall picture rather than any one factor.",
                )
            )

        # The other side of the story: features whose typical value would make
        # this grade MORE likely, i.e. the actual value pulls away from it.
        counter: list[FeatureInfluence] = []
        for column, contribution in sorted(contributions, key=lambda item: item[1])[:2]:
            if contribution >= -0.005:
                continue
            counter.append(
                FeatureInfluence(
                    feature=FEATURE_LABELS.get(column, column),
                    value=_format_value(column, features[column]),
                    impact=f"This made \"{predicted_label}\" less likely, so it pulls towards a different grade.",
                )
            )
        return influences, counter

    @staticmethod
    def _impact_sentence(column: str, value: Any, contribution: float, label: str) -> str:
        strength = "strongly" if contribution >= 0.10 else "moderately" if contribution >= 0.04 else "slightly"
        explanations = {
            "traffic_level": "how busy the road is expected to be",
            "weather_risk": "the combined weather hazard from rain, ice, wind and mist",
            "rainfall_mm": "the amount of rain forecast halfway along the route, at the hour you pass it",
            "wind_speed_kmh": "the forecast wind, which matters on exposed stretches and bridges",
            "temperature_c": "the temperature, which decides whether ice is a possibility",
            "humidity": "the humidity, used here as a stand-in for mist and fog",
            "urban_density": "how built-up the route is, and so how many junctions and stops it has",
            "accident_risk_score": "the combination of traffic and weather that raises accident exposure",
            "construction_risk_score": "the likelihood of roadworks on a route like this at this time of year",
            "vehicle_load_factor": "how much of the road's capacity is expected to be in use",
            "road_type_score": "the grade of road implied by the sustained speed",
            "avg_speed_kmh": "the average speed the routing engine expects",
            "distance_km": "the length of the trip",
            "estimated_duration_min": "how long the trip is expected to take",
            "elevation_variation": "how much the terrain varies along the way",
            "time_of_day": "the departure time",
            "day_of_week": "the day of the week",
            "season": "the time of year",
            "region_type": "the kind of area the route runs through",
        }
        reason = explanations.get(column, "this input")
        return f"The model leaned {strength} on {reason} when settling on \"{label}\"."

    @staticmethod
    def _leave_early_minutes(label: str, features: dict[str, Any]) -> int:
        """A buffer that scales with both the grade and the length of the trip."""
        base = {"Excellent": 0, "Good": 5, "Moderate": 10, "Poor": 20, "Very Poor": 30}[label]
        if base == 0:
            return 0
        distance = float(features["distance_km"])
        if distance > 150:
            return base + 10
        if distance > 80:
            return base + 5
        return base

    @staticmethod
    def _advisory_text(
        label: str,
        confidence: float,
        leave_early: int,
        features: dict[str, Any],
        influences: list[FeatureInfluence],
        weather_source: str,
    ) -> str:
        condition_sentence = {
            "Excellent": "Conditions look excellent for this Denmark route, so the trip should feel smooth.",
            "Good": "Conditions look mostly good on this route, with only light disruption likely.",
            "Moderate": "Conditions look moderate, especially where traffic and local slowdowns build up.",
            "Poor": "Conditions look poor, because several delay signals line up at the same time.",
            "Very Poor": "Conditions look very poor. Allow extra time, and consider whether the trip can wait.",
        }[label]

        leave_sentence = (
            "You should not need to leave early unless conditions change."
            if leave_early == 0
            else f"As a rough rule of thumb, not a measured delay, leaving about {leave_early} minutes early gives you a buffer."
        )

        reason_sentence = (
            "The strongest single factor was " + influences[0].feature.lower() + "."
            if influences
            else "No single factor dominated this estimate."
        )

        if weather_source == "open-meteo":
            weather_sentence = "The weather used here is a live Open-Meteo forecast for the halfway point of your route."
        elif weather_source == "seasonal-estimate":
            weather_sentence = (
                "Live weather was not available, so a seasonal average was used instead, "
                "which makes this grade less reliable."
            )
        else:
            weather_sentence = "The source of the weather data is unknown, so treat this grade with care."

        # The percentage is the model's own vote share, not a calibrated chance
        # of real conditions, so the wording avoids the word "confident".
        confidence_sentence = (
            "The model clearly favours this grade over the others, but this is the model's own score, "
            "not a measured probability."
            if confidence >= 0.60
            else "The model is split between neighbouring grades, so treat this as a rough guide."
        )

        return " ".join(
            [condition_sentence, leave_sentence, reason_sentence, weather_sentence, confidence_sentence]
        )

    def get_model_info(self) -> ModelInfoResponse:
        return ModelInfoResponse(
            modelName=self.metadata["model_name"],
            sampleCount=self.metadata["sample_count"],
            accuracy=self.metadata["accuracy"],
            labels=self.metadata["labels"],
            # Only the ten strongest are worth showing.
            featureImportances=dict(list(self.metadata["feature_importances"].items())[:10]),
            syntheticDisclaimer=self.metadata["synthetic_disclaimer"],
        )


manager = ModelManager()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Replaces the deprecated @app.on_event("startup") hook.
    manager.startup()
    yield


app = FastAPI(title="Denmark Road Condition ML Service", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "ml-service", "modelLoaded": str(manager.pipeline is not None)}


@app.post("/predict-road-condition", response_model=PredictionResponse)
def predict_road_condition(request: PredictionRequest) -> PredictionResponse:
    # The old handler read the raw body and printed every payload to stdout,
    # which put user route data into the server log on every single request.
    if request.observation is not None:
        observation = request.observation
        try:
            departure = datetime.fromisoformat(observation.departure_iso)
        except ValueError as exc:
            raise HTTPException(
                status_code=422,
                detail=f"departureIso is not a valid ISO-8601 timestamp: {observation.departure_iso}",
            ) from exc

        features = derive_features(
            RouteObservation(
                distance_km=observation.distance_km,
                duration_min=observation.duration_min,
                departure=departure,
                urban_signal=observation.urban_signal,
                temperature_c=observation.weather.temperature_c,
                rainfall_mm=observation.weather.rainfall_mm,
                humidity=observation.weather.humidity,
                wind_speed_kmh=observation.weather.wind_speed_kmh,
            ),
            rng=None,
        )
        return manager.predict(features, weather_source=observation.weather.source or "unknown")

    if request.features is not None:
        return manager.predict(request.features.model_dump(by_alias=False), weather_source="unknown")

    raise HTTPException(
        status_code=422,
        detail="Send either an 'observation' object (preferred) or a fully derived 'features' object.",
    )


@app.post("/retrain", response_model=ModelInfoResponse)
def retrain() -> ModelInfoResponse:
    return manager.retrain()


@app.get("/model-info", response_model=ModelInfoResponse)
def model_info() -> ModelInfoResponse:
    return manager.get_model_info()
