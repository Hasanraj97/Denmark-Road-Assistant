from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder


BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
MODEL_DIR = BASE_DIR / "models"
DATASET_PATH = DATA_DIR / "synthetic_denmark_routes.csv"
MODEL_PATH = MODEL_DIR / "road_condition_model.joblib"
METADATA_PATH = MODEL_DIR / "road_condition_model_metadata.json"

LABELS = ["Excellent", "Good", "Moderate", "Poor", "Very Poor"]
NUMERIC_COLUMNS = [
    "distance_km",
    "estimated_duration_min",
    "avg_speed_kmh",
    "road_type_score",
    "traffic_level",
    "weather_risk",
    "rainfall_mm",
    "urban_density",
    "accident_risk_score",
    "construction_risk_score",
    "elevation_variation",
    "temperature_c",
    "humidity",
    "wind_speed_kmh",
    "vehicle_load_factor",
]
CATEGORICAL_COLUMNS = ["time_of_day", "day_of_week", "season", "region_type"]
FEATURE_COLUMNS = NUMERIC_COLUMNS + CATEGORICAL_COLUMNS


class FeatureInfluence(BaseModel):
    feature: str
    value: str
    impact: str


class RouteFeatures(BaseModel):
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
    features: RouteFeatures


class PredictionResponse(BaseModel):
    roadConditionLabel: str
    confidence: float
    leaveEarlyMinutes: int
    advisory: str
    influentialFeatures: list[FeatureInfluence]
    classProbabilities: dict[str, float]
    syntheticPrediction: bool = True


class ModelInfoResponse(BaseModel):
    modelName: str
    datasetPath: str
    modelPath: str
    sampleCount: int
    accuracy: float
    labels: list[str]
    featureImportances: dict[str, float]
    syntheticDisclaimer: str


class ModelManager:
    def __init__(self) -> None:
        self.pipeline: Pipeline | None = None
        self.metadata: dict[str, Any] = {}
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        MODEL_DIR.mkdir(parents=True, exist_ok=True)

    def startup(self) -> None:
        if not DATASET_PATH.exists():
            dataset = self._generate_dataset()
            dataset.to_csv(DATASET_PATH, index=False)
            print(f"Generated synthetic dataset at {DATASET_PATH}")

        if MODEL_PATH.exists() and METADATA_PATH.exists():
            self.pipeline = joblib.load(MODEL_PATH)
            self.metadata = json.loads(METADATA_PATH.read_text())
            self._print_saved_metrics()
        else:
            self.retrain()

    def retrain(self) -> ModelInfoResponse:
        dataset = self._generate_dataset()
        dataset.to_csv(DATASET_PATH, index=False)
        x_train, x_test, y_train, y_test = train_test_split(
            dataset[FEATURE_COLUMNS],
            dataset["road_condition_label"],
            test_size=0.2,
            random_state=42,
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

        classifier = RandomForestClassifier(
            n_estimators=280,
            min_samples_leaf=2,
            random_state=42,
            class_weight="balanced_subsample",
        )

        self.pipeline = Pipeline(
            steps=[
                ("preprocessor", preprocessor),
                ("classifier", classifier),
            ]
        )
        self.pipeline.fit(x_train, y_train)

        predictions = self.pipeline.predict(x_test)
        accuracy = accuracy_score(y_test, predictions)
        report_text = classification_report(y_test, predictions, labels=LABELS, zero_division=0)
        confusion = confusion_matrix(y_test, predictions, labels=LABELS).tolist()
        importances = self._aggregate_feature_importances()

        self.metadata = {
            "model_name": "RandomForestClassifier",
            "dataset_path": str(DATASET_PATH),
            "model_path": str(MODEL_PATH),
            "sample_count": int(len(dataset)),
            "accuracy": round(float(accuracy), 4),
            "labels": LABELS,
            "feature_importances": importances,
            "classification_report": report_text,
            "confusion_matrix": confusion,
            "synthetic_disclaimer": (
                "Synthetic Denmark demo prediction. The model trains on generated data and does not "
                "represent real road truth."
            ),
        }

        joblib.dump(self.pipeline, MODEL_PATH)
        METADATA_PATH.write_text(json.dumps(self.metadata, indent=2))
        self._print_training_metrics()
        return self.get_model_info()

    def predict(self, request: PredictionRequest) -> PredictionResponse:
        if self.pipeline is None:
            raise RuntimeError("Model pipeline is not loaded.")

        feature_dict = request.features.model_dump(by_alias=False)
        frame = pd.DataFrame([feature_dict])
        probabilities = self.pipeline.predict_proba(frame)[0]
        classes = self.pipeline.named_steps["classifier"].classes_
        probability_map = {
            str(label): round(float(probabilities[idx]), 4)
            for idx, label in enumerate(classes)
        }
        predicted_label = str(classes[int(np.argmax(probabilities))])
        confidence = round(float(np.max(probabilities)), 4)
        leave_early = self._leave_early_minutes(predicted_label, feature_dict)
        influences = self._influential_features(feature_dict, predicted_label)
        advisory = self._advisory_text(
            predicted_label,
            confidence,
            leave_early,
            feature_dict,
            influences,
        )

        ordered_probabilities = {
            label: probability_map.get(label, 0.0) for label in LABELS
        }
        return PredictionResponse(
            roadConditionLabel=predicted_label,
            confidence=confidence,
            leaveEarlyMinutes=leave_early,
            advisory=advisory,
            influentialFeatures=influences,
            classProbabilities=ordered_probabilities,
            syntheticPrediction=True,
        )

    def get_model_info(self) -> ModelInfoResponse:
        return ModelInfoResponse(
            modelName=self.metadata["model_name"],
            datasetPath=self.metadata["dataset_path"],
            modelPath=self.metadata["model_path"],
            sampleCount=self.metadata["sample_count"],
            accuracy=self.metadata["accuracy"],
            labels=self.metadata["labels"],
            featureImportances=self.metadata["feature_importances"],
            syntheticDisclaimer=self.metadata["synthetic_disclaimer"],
        )

    def _generate_dataset(self, sample_count: int = 12000) -> pd.DataFrame:
        rng = np.random.default_rng(42)
        rows: list[dict[str, Any]] = []

        region_choices = ["urban", "suburban", "regional"]
        region_probs = [0.34, 0.28, 0.38]
        season_choices = ["winter", "spring", "summer", "autumn"]
        season_probs = [0.24, 0.24, 0.27, 0.25]
        day_choices = [
            "monday",
            "tuesday",
            "wednesday",
            "thursday",
            "friday",
            "saturday",
            "sunday",
        ]
        day_probs = [0.16, 0.16, 0.16, 0.16, 0.16, 0.1, 0.1]
        time_choices = ["early_morning", "morning_peak", "midday", "afternoon_peak", "evening", "night"]
        time_probs = [0.12, 0.2, 0.22, 0.2, 0.16, 0.1]

        def apply_noise(value: float) -> float:
            return value * (1.0 + rng.uniform(-0.03, 0.03))

        def derive_hour(bucket: str) -> int:
            hour_map = {
                "early_morning": (0, 5),
                "morning_peak": (6, 9),
                "midday": (10, 14),
                "afternoon_peak": (15, 18),
                "evening": (19, 22),
                "night": (23, 23),
            }
            start, end = hour_map[bucket]
            if start == end:
                return start
            return int(rng.integers(start, end + 1))

        def time_score(hour: int, day_of_week: str) -> float:
            if (7 <= hour <= 9) or (15 <= hour <= 18):
                score = 0.75
            elif 10 <= hour <= 14:
                score = 0.50
            else:
                score = 0.25

            if day_of_week in {"saturday", "sunday"}:
                score -= 0.10

            return float(np.clip(score, 0.0, 1.0))

        def weather_score(season: str) -> float:
            return {
                "winter": 0.75,
                "spring": 0.50,
                "autumn": 0.50,
                "summer": 0.25,
            }[season]

        def derive_region_type(urban_density: float, distance_km: float) -> str:
            if urban_density > 0.68 or distance_km < 22:
                return "urban"
            if urban_density > 0.44 or distance_km < 80:
                return "suburban"
            return "regional"

        for _ in range(sample_count):
            region_seed = rng.choice(region_choices, p=region_probs)
            season = rng.choice(season_choices, p=season_probs)
            day_of_week = rng.choice(day_choices, p=day_probs)
            time_of_day = rng.choice(time_choices, p=time_probs)
            departure_hour = derive_hour(time_of_day)

            if region_seed == "urban":
                distance_km = float(rng.uniform(4, 32))
                urban_density = float(rng.uniform(0.64, 0.82))
                avg_speed_kmh = float(rng.uniform(24, 46))
            elif region_seed == "suburban":
                distance_km = float(rng.uniform(12, 85))
                urban_density = float(rng.uniform(0.44, 0.66))
                avg_speed_kmh = float(rng.uniform(42, 78))
            else:
                distance_km = float(rng.uniform(45, 310))
                urban_density = float(rng.uniform(0.19, 0.40))
                avg_speed_kmh = float(rng.uniform(68, 108))

            if distance_km < 20:
                urban_density += 0.15
            if distance_km > 100:
                urban_density -= 0.15
            urban_density = float(np.clip(apply_noise(urban_density), 0.0, 1.0))

            region_type = derive_region_type(urban_density, distance_km)
            road_type_score = float(np.clip(apply_noise(0.20 + avg_speed_kmh / 140.0), 0.0, 1.0))

            traffic_level = float(
                np.clip(
                    apply_noise(0.20 + urban_density * 0.40 + time_score(departure_hour, day_of_week) * 0.40),
                    0.0,
                    1.0,
                )
            )

            weather_risk = float(np.clip(apply_noise(0.20 + weather_score(season) * 0.60), 0.0, 1.0))
            rainfall_mm = float(max(0.0, round(apply_noise(weather_risk * 8.0), 2)))
            humidity = float(np.clip(apply_noise(0.40 + weather_risk * 0.40), 0.0, 1.0))
            wind_speed_kmh = float(max(5.0, round(apply_noise(10.0 + weather_risk * 18.0), 2)))
            temperature_c = float(
                round(
                    apply_noise(
                        {
                            "winter": 1.5,
                            "spring": 9.0,
                            "summer": 18.0,
                            "autumn": 10.0,
                        }[season]
                    ),
                    2,
                )
            )
            construction_risk_score = float(np.clip(apply_noise(0.20 + urban_density * 0.40), 0.0, 1.0))
            elevation_variation = float(max(2.0, round(apply_noise(5.0 + distance_km * 0.05), 2)))
            accident_risk_score = float(
                np.clip(apply_noise(0.20 + traffic_level * 0.35 + weather_risk * 0.30), 0.0, 1.0)
            )
            vehicle_load_factor = float(np.clip(apply_noise(0.20 + traffic_level * 0.60), 0.0, 1.0))

            estimated_duration_min = float((distance_km / max(avg_speed_kmh, 1.0)) * 60)

            condition_score = (
                traffic_level * 0.32
                + weather_risk * 0.2
                + min(rainfall_mm / 12.0, 1.0) * 0.08
                + urban_density * 0.1
                + accident_risk_score * 0.12
                + construction_risk_score * 0.08
                + vehicle_load_factor * 0.05
                + min(wind_speed_kmh / 45.0, 1.0) * 0.05
                - road_type_score * 0.13
                - min(avg_speed_kmh / 110.0, 1.0) * 0.08
                + rng.normal(0, 0.04)
            )

            if condition_score < 0.18:
                label = "Excellent"
            elif condition_score < 0.33:
                label = "Good"
            elif condition_score < 0.51:
                label = "Moderate"
            elif condition_score < 0.68:
                label = "Poor"
            else:
                label = "Very Poor"

            if rng.random() < 0.03:
                label = rng.choice(LABELS)

            rows.append(
                {
                    "distance_km": round(distance_km, 2),
                    "estimated_duration_min": round(estimated_duration_min, 2),
                    "avg_speed_kmh": round(avg_speed_kmh, 2),
                    "road_type_score": round(road_type_score, 2),
                    "traffic_level": round(traffic_level, 2),
                    "weather_risk": round(weather_risk, 2),
                    "rainfall_mm": round(rainfall_mm, 2),
                    "time_of_day": time_of_day,
                    "day_of_week": day_of_week,
                    "urban_density": round(urban_density, 2),
                    "accident_risk_score": round(accident_risk_score, 2),
                    "construction_risk_score": round(construction_risk_score, 2),
                    "elevation_variation": round(elevation_variation, 2),
                    "temperature_c": round(temperature_c, 2),
                    "humidity": round(humidity, 2),
                    "wind_speed_kmh": round(wind_speed_kmh, 2),
                    "season": season,
                    "region_type": region_type,
                    "vehicle_load_factor": round(vehicle_load_factor, 2),
                    "road_condition_label": label,
                }
            )

        return pd.DataFrame(rows)

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

        sorted_features = sorted(aggregated.items(), key=lambda item: item[1], reverse=True)
        return {key: round(value, 4) for key, value in sorted_features[:10]}

    def _leave_early_minutes(self, label: str, features: dict[str, Any]) -> int:
        traffic = float(features["traffic_level"])
        weather = float(features["weather_risk"])
        distance = float(features["distance_km"])

        if label == "Excellent":
            return 0 if traffic < 0.45 else 5
        if label == "Good":
            return 0 if traffic < 0.55 and weather < 0.4 else 5
        if label == "Moderate":
            return 10 if distance < 90 else 15
        if label == "Poor":
            return 20 if distance < 120 else 25
        return 30 if distance < 150 else 35

    def _influential_features(self, features: dict[str, Any], label: str) -> list[FeatureInfluence]:
        influences: list[FeatureInfluence] = []

        if features["traffic_level"] >= 0.65:
            influences.append(
                FeatureInfluence(
                    feature="Traffic level",
                    value=f"{features['traffic_level']:.2f}",
                    impact="Heavy traffic pressure is likely to slow the route, especially near busier junctions.",
                )
            )
        if features["weather_risk"] >= 0.55 or features["rainfall_mm"] >= 4:
            influences.append(
                FeatureInfluence(
                    feature="Weather risk",
                    value=f"{features['weather_risk']:.2f} / {features['rainfall_mm']:.1f} mm rain",
                    impact="Wet or windy conditions may reduce comfort and increase caution on the road.",
                )
            )
        if features["urban_density"] >= 0.65:
            influences.append(
                FeatureInfluence(
                    feature="Urban density",
                    value=f"{features['urban_density']:.2f}",
                    impact="Urban sections usually mean more signals, merges, and short-stop delays.",
                )
            )
        if features["construction_risk_score"] >= 0.45:
            influences.append(
                FeatureInfluence(
                    feature="Construction-style risk",
                    value=f"{features['construction_risk_score']:.2f}",
                    impact="The synthetic model sees enough disruption risk to allow for lane or speed reductions.",
                )
            )
        if not influences and label in {"Excellent", "Good"}:
            influences.append(
                FeatureInfluence(
                    feature="Road profile",
                    value=f"road_type_score {features['road_type_score']:.2f}",
                    impact="The route profile looks relatively smooth, which supports steadier travel conditions.",
                )
            )
        if len(influences) < 3:
            influences.append(
                FeatureInfluence(
                    feature="Average speed",
                    value=f"{features['avg_speed_kmh']:.1f} km/h",
                    impact="The expected average speed helps the model judge whether the route should feel smooth or delayed.",
                )
            )

        return influences[:4]

    def _advisory_text(
        self,
        label: str,
        confidence: float,
        leave_early: int,
        features: dict[str, Any],
        influences: list[FeatureInfluence],
    ) -> str:
        traffic = float(features["traffic_level"])
        weather = float(features["weather_risk"])
        region = str(features["region_type"])

        condition_sentence = {
            "Excellent": "Conditions look excellent for this Denmark route, so the trip should feel fairly smooth.",
            "Good": "Conditions look mostly good on this route, with only light disruption likely.",
            "Moderate": "Road conditions may be moderate on this route, especially where traffic and local slowdowns build up.",
            "Poor": "This route may face poor conditions because several delay signals line up at the same time.",
            "Very Poor": "This route may face very poor conditions, so extra caution and buffer time are recommended.",
        }[label]

        leave_sentence = (
            "You likely do not need to leave early unless conditions change unexpectedly."
            if leave_early == 0
            else f"Leaving about {leave_early} minutes early would be a sensible buffer."
        )

        reasons: list[str] = []
        if traffic >= 0.65:
            reasons.append("heavier traffic pressure")
        elif traffic >= 0.45:
            reasons.append("moderate traffic build-up")
        if weather >= 0.55:
            reasons.append("weather-related risk")
        if float(features["construction_risk_score"]) >= 0.45:
            reasons.append("construction-like disruption")
        if region == "urban":
            reasons.append("urban stop-and-go sections")
        elif region == "regional" and float(features["road_type_score"]) >= 0.7:
            reasons.append("longer smoother regional road sections")

        reason_text = (
            "The model is mainly reacting to " + ", ".join(reasons[:3]) + "."
            if reasons
            else influences[0].impact
        )

        confidence_text = (
            "Confidence is solid for this synthetic estimate."
            if confidence >= 0.7
            else "Confidence is moderate, so treat this as a helpful synthetic guide rather than a certainty."
        )

        return " ".join([condition_sentence, leave_sentence, reason_text, confidence_text])

    def _print_saved_metrics(self) -> None:
        print("Loaded saved road-condition model.")
        print(f"Accuracy: {self.metadata['accuracy']}")
        print("Classification report:")
        print(self.metadata["classification_report"])
        print("Confusion matrix:")
        print(np.array(self.metadata["confusion_matrix"]))

    def _print_training_metrics(self) -> None:
        print("Trained synthetic road-condition model.")
        print(f"Accuracy: {self.metadata['accuracy']}")
        print("Classification report:")
        print(self.metadata["classification_report"])
        print("Confusion matrix:")
        print(np.array(self.metadata["confusion_matrix"]))


app = FastAPI(title="Denmark Road Condition ML Service")
manager = ModelManager()


def _normalize_prediction_payload(payload: dict[str, Any]) -> PredictionRequest:
    raw_features = payload.get("features", payload)
    if not isinstance(raw_features, dict):
        raise HTTPException(status_code=422, detail="Request must contain a features object.")

    alias_map = {
        "distance_km": "distanceKm",
        "estimated_duration_min": "estimatedDurationMin",
        "avg_speed_kmh": "avgSpeedKmh",
        "road_type_score": "roadTypeScore",
        "traffic_level": "trafficLevel",
        "weather_risk": "weatherRisk",
        "rainfall_mm": "rainfallMm",
        "time_of_day": "timeOfDay",
        "day_of_week": "dayOfWeek",
        "urban_density": "urbanDensity",
        "accident_risk_score": "accidentRiskScore",
        "construction_risk_score": "constructionRiskScore",
        "elevation_variation": "elevationVariation",
        "temperature_c": "temperatureC",
        "wind_speed_kmh": "windSpeedKmh",
        "region_type": "regionType",
        "vehicle_load_factor": "vehicleLoadFactor",
    }

    normalized = dict(raw_features)
    for snake_key, camel_key in alias_map.items():
        if snake_key in normalized and camel_key not in normalized:
            normalized[camel_key] = normalized[snake_key]

    try:
        return PredictionRequest.model_validate({"features": normalized})
    except Exception as ex:  # pragma: no cover - defensive conversion path
        raise HTTPException(status_code=422, detail=f"Invalid feature payload: {ex}") from ex


@app.on_event("startup")
def on_startup() -> None:
    manager.startup()


@app.post("/predict-road-condition", response_model=PredictionResponse)
async def predict_road_condition(request: Request) -> PredictionResponse:
    try:
        raw_body = await request.body()
        print("Raw predict payload:", raw_body.decode("utf-8", errors="replace"))
        payload = json.loads(raw_body.decode("utf-8"))
    except Exception as ex:  # pragma: no cover - defensive parsing path
        raise HTTPException(status_code=422, detail=f"Could not parse JSON body: {ex}") from ex

    if not isinstance(payload, dict):
        raise HTTPException(status_code=422, detail="JSON body must be an object.")

    normalized_request = _normalize_prediction_payload(payload)
    return manager.predict(normalized_request)


@app.post("/retrain", response_model=ModelInfoResponse)
def retrain() -> ModelInfoResponse:
    return manager.retrain()


@app.get("/model-info", response_model=ModelInfoResponse)
def model_info() -> ModelInfoResponse:
    return manager.get_model_info()
