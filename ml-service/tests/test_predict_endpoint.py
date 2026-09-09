"""
Contract tests for ``POST /predict-road-condition``.

Covers the three things the rest of the system depends on:

1. the preferred *observation* path returns a complete, camelCase body;
2. the legacy *features* path still works in both camelCase and snake_case,
   because ``populate_by_name=True`` is set on the pydantic model;
3. bad input is rejected with 422, never with a 500.

The camelCase assertion is not cosmetic. ``RouteFeaturesDto`` in the Java backend
is a record with exactly these nineteen component names, and Jackson maps them by
name, so a single snake_case key here would deserialise to 0.0 on the Java side
without any error being raised.
"""

from __future__ import annotations

import pytest

from helpers import observation_payload

# The nineteen keys of backend/src/main/java/com/example/backend/dto/RouteFeaturesDto.java
EXPECTED_FEATURE_KEYS = {
    "distanceKm",
    "estimatedDurationMin",
    "avgSpeedKmh",
    "roadTypeScore",
    "trafficLevel",
    "weatherRisk",
    "rainfallMm",
    "timeOfDay",
    "dayOfWeek",
    "urbanDensity",
    "accidentRiskScore",
    "constructionRiskScore",
    "elevationVariation",
    "temperatureC",
    "humidity",
    "windSpeedKmh",
    "season",
    "regionType",
    "vehicleLoadFactor",
}

LABELS = ["Excellent", "Good", "Moderate", "Poor", "Very Poor"]

# A perfectly ordinary Danish trip: Copenhagen to Roskilde on a spring Wednesday
# morning, light rain, breezy.
ORDINARY_TRIP = observation_payload(
    distance_km=35.6,
    duration_min=37.0,
    departure_iso="2025-04-16T08:00",
    urban_signal=0.78,
    temperature_c=9.0,
    rainfall_mm=0.6,
    humidity=0.74,
    wind_speed_kmh=18.0,
)


# ---------------------------------------------------------------------------
# 1. The observation path
# ---------------------------------------------------------------------------

def test_observation_payload_returns_complete_response(client):
    response = client.post("/predict-road-condition", json=ORDINARY_TRIP)
    assert response.status_code == 200, response.text
    body = response.json()

    for key in (
        "roadConditionLabel",
        "confidence",
        "leaveEarlyMinutes",
        "advisory",
        "influentialFeatures",
        "classProbabilities",
        "features",
    ):
        assert key in body, f"response is missing '{key}': {sorted(body)}"

    assert body["roadConditionLabel"] in LABELS
    assert 0.0 <= body["confidence"] <= 1.0
    assert isinstance(body["leaveEarlyMinutes"], int)
    assert body["leaveEarlyMinutes"] >= 0
    assert isinstance(body["advisory"], str) and body["advisory"].strip()

    assert isinstance(body["influentialFeatures"], list)
    assert body["influentialFeatures"], "at least one influential feature is expected"
    for influence in body["influentialFeatures"]:
        assert set(influence) == {"feature", "value", "impact"}
        assert influence["feature"] and influence["impact"]

    probabilities = body["classProbabilities"]
    assert set(probabilities) == set(LABELS)
    assert probabilities[body["roadConditionLabel"]] == pytest.approx(body["confidence"])
    assert sum(probabilities.values()) == pytest.approx(1.0, abs=1e-3)


def test_observation_response_features_are_camel_case(client):
    """The Java DTO binds by name, so these keys are a hard contract."""
    response = client.post("/predict-road-condition", json=ORDINARY_TRIP)
    assert response.status_code == 200, response.text
    features = response.json()["features"]

    assert set(features) == EXPECTED_FEATURE_KEYS, (
        "returned feature keys do not match RouteFeaturesDto. "
        f"missing={EXPECTED_FEATURE_KEYS - set(features)} "
        f"unexpected={set(features) - EXPECTED_FEATURE_KEYS}"
    )
    # Spot-check the two named in the brief, and prove no snake_case leaked out.
    assert "distanceKm" in features and "distance_km" not in features
    assert "weatherRisk" in features and "weather_risk" not in features
    assert not any("_" in key for key in features)

    assert features["distanceKm"] == pytest.approx(35.6)
    assert features["dayOfWeek"] == "wednesday"
    assert features["season"] == "spring"
    assert features["timeOfDay"] == "morning_peak"


def test_observation_path_is_deterministic(client):
    """``derive_features`` is called with rng=None when serving, so the same
    request must always give the same answer."""
    first = client.post("/predict-road-condition", json=ORDINARY_TRIP).json()
    second = client.post("/predict-road-condition", json=ORDINARY_TRIP).json()
    assert first == second


# ---------------------------------------------------------------------------
# 2. The legacy features path, in both cases
# ---------------------------------------------------------------------------

SNAKE_CASE_FEATURES = {
    "distance_km": 35.6,
    "estimated_duration_min": 37.0,
    "avg_speed_kmh": 57.73,
    "road_type_score": 0.6124,
    "traffic_level": 0.7539,
    "weather_risk": 0.0714,
    "rainfall_mm": 0.6,
    "time_of_day": "morning_peak",
    "day_of_week": "wednesday",
    "urban_density": 0.93,
    "accident_risk_score": 0.3452,
    "construction_risk_score": 0.5190,
    "elevation_variation": 6.14,
    "temperature_c": 9.0,
    "humidity": 0.74,
    "wind_speed_kmh": 18.0,
    "season": "spring",
    "region_type": "urban",
    "vehicle_load_factor": 0.6228,
}

CAMEL_CASE_FEATURES = {
    "distanceKm": 35.6,
    "estimatedDurationMin": 37.0,
    "avgSpeedKmh": 57.73,
    "roadTypeScore": 0.6124,
    "trafficLevel": 0.7539,
    "weatherRisk": 0.0714,
    "rainfallMm": 0.6,
    "timeOfDay": "morning_peak",
    "dayOfWeek": "wednesday",
    "urbanDensity": 0.93,
    "accidentRiskScore": 0.3452,
    "constructionRiskScore": 0.5190,
    "elevationVariation": 6.14,
    "temperatureC": 9.0,
    "humidity": 0.74,
    "windSpeedKmh": 18.0,
    "season": "spring",
    "regionType": "urban",
    "vehicleLoadFactor": 0.6228,
}


@pytest.mark.parametrize(
    "payload_name, payload",
    [("camelCase", CAMEL_CASE_FEATURES), ("snake_case", SNAKE_CASE_FEATURES)],
)
def test_legacy_features_payload_accepted(client, payload_name, payload):
    response = client.post("/predict-road-condition", json={"features": payload})
    assert response.status_code == 200, f"{payload_name} rejected: {response.text}"
    body = response.json()
    assert body["roadConditionLabel"] in LABELS
    assert set(body["features"]) == EXPECTED_FEATURE_KEYS


def test_legacy_camel_and_snake_agree(client):
    """populate_by_name=True means the two spellings are the same request."""
    camel = client.post("/predict-road-condition", json={"features": CAMEL_CASE_FEATURES}).json()
    snake = client.post("/predict-road-condition", json={"features": SNAKE_CASE_FEATURES}).json()
    assert camel["roadConditionLabel"] == snake["roadConditionLabel"]
    assert camel["classProbabilities"] == snake["classProbabilities"]
    assert camel["features"] == snake["features"]


# ---------------------------------------------------------------------------
# 3. Malformed input is a 422, never a 500
# ---------------------------------------------------------------------------

def _observation_missing(field: str) -> dict:
    payload = observation_payload(
        distance_km=35.6,
        duration_min=37.0,
        departure_iso="2025-04-16T08:00",
        urban_signal=0.78,
        temperature_c=9.0,
        rainfall_mm=0.6,
        humidity=0.74,
        wind_speed_kmh=18.0,
    )
    payload["observation"].pop(field)
    return payload


def _observation_with(**overrides) -> dict:
    payload = observation_payload(
        distance_km=35.6,
        duration_min=37.0,
        departure_iso="2025-04-16T08:00",
        urban_signal=0.78,
        temperature_c=9.0,
        rainfall_mm=0.6,
        humidity=0.74,
        wind_speed_kmh=18.0,
    )
    payload["observation"].update(overrides)
    return payload


MALFORMED_PAYLOADS = [
    # -- neither branch supplied ------------------------------------------
    ("empty object", {}),
    ("both branches null", {"observation": None, "features": None}),
    # -- missing body fields ----------------------------------------------
    ("observation missing distanceKm", _observation_missing("distanceKm")),
    ("observation missing durationMin", _observation_missing("durationMin")),
    ("observation missing departureIso", _observation_missing("departureIso")),
    ("observation missing urbanSignal", _observation_missing("urbanSignal")),
    ("observation missing weather", _observation_missing("weather")),
    ("observation is empty", {"observation": {}}),
    (
        "weather missing windSpeedKmh",
        {
            "observation": {
                "distanceKm": 35.6,
                "durationMin": 37.0,
                "departureIso": "2025-04-16T08:00",
                "urbanSignal": 0.78,
                "weather": {"temperatureC": 9.0, "rainfallMm": 0.6, "humidity": 0.74},
            }
        },
    ),
    ("features payload missing most fields", {"features": {"distanceKm": 35.6}}),
    # -- non-numeric distance ---------------------------------------------
    ("distance is a word", _observation_with(distanceKm="thirty-five")),
    ("distance is null", _observation_with(distanceKm=None)),
    ("distance is a list", _observation_with(distanceKm=[35.6])),
    ("duration is a word", _observation_with(durationMin="quite a while")),
    (
        "temperature is a word",
        {
            "observation": {
                "distanceKm": 35.6,
                "durationMin": 37.0,
                "departureIso": "2025-04-16T08:00",
                "urbanSignal": 0.78,
                "weather": {
                    "temperatureC": "chilly",
                    "rainfallMm": 0.6,
                    "humidity": 0.74,
                    "windSpeedKmh": 18.0,
                },
            }
        },
    ),
    # -- departureIso that is not a valid timestamp ------------------------
    ("departure is prose", _observation_with(departureIso="next tuesday-ish")),
    ("departure is empty", _observation_with(departureIso="")),
    ("departure is only a time", _observation_with(departureIso="08:00")),
    ("departure has month 13", _observation_with(departureIso="2025-13-16T08:00")),
    ("departure has day 45", _observation_with(departureIso="2025-04-45T08:00")),
    ("departure has hour 99", _observation_with(departureIso="2025-04-16T99:00")),
    ("departure is a number", _observation_with(departureIso=1744790400)),
]


@pytest.mark.parametrize(
    "case, payload", MALFORMED_PAYLOADS, ids=[case for case, _ in MALFORMED_PAYLOADS]
)
def test_malformed_payload_is_422_not_500(client, case, payload):
    response = client.post("/predict-road-condition", json=payload)
    assert response.status_code == 422, (
        f"{case}: expected 422, got {response.status_code}: {response.text[:400]}"
    )
    assert response.status_code < 500


def test_missing_body_entirely_is_422(client):
    response = client.post("/predict-road-condition")
    assert response.status_code == 422, response.text


def test_non_json_body_is_not_a_server_error(client):
    response = client.post(
        "/predict-road-condition",
        content=b"this is not json",
        headers={"Content-Type": "application/json"},
    )
    assert response.status_code == 422, response.text


def test_invalid_departure_message_names_the_field(client):
    response = client.post(
        "/predict-road-condition", json=_observation_with(departureIso="not-a-timestamp")
    )
    assert response.status_code == 422
    assert "departureIso" in response.text


# ---------------------------------------------------------------------------
# Sanity: the service is actually up and holding a model
# ---------------------------------------------------------------------------

def test_health_reports_a_loaded_model(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["modelLoaded"] == "True"


def test_model_info_lists_all_five_labels(client):
    body = client.get("/model-info").json()
    assert body["labels"] == LABELS
    assert 0.0 < body["accuracy"] <= 1.0
    assert body["sampleCount"] > 0
    assert body["featureImportances"]
