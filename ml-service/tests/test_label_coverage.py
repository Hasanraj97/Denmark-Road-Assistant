"""
Defect M1 - all five grades must actually be reachable.

The original model advertised a five-grade advisory but never once predicted
"Very Poor": its own classification report scored that class at precision 0.00,
recall 0.00 and f1 0.00, so the fifth grade existed only in the UI legend.

This module drives the *live* path - the ``observation`` payload, so the real
``derive_features`` derivation is exercised, not a hand-written feature vector -
with realistic Danish trips and realistic Danish weather, and requires that every
one of the five grades comes back at least once.

The scenarios are ordinary, not contrived. Each is a real route pair with the
sort of distance and duration OSRM returns for it, a plausible departure moment,
and weather Denmark genuinely sees: a calm spring Saturday at one end, a
sub-zero winter storm at the other.
"""

from __future__ import annotations

import pytest

from helpers import observation_payload

LABELS = ["Excellent", "Good", "Moderate", "Poor", "Very Poor"]
LABEL_ORDER = {label: index for index, label in enumerate(LABELS)}

# (expected grade, description, payload)
SCENARIOS = [
    (
        "Excellent",
        "Copenhagen to Odense on the motorway, calm dry spring Saturday midday",
        observation_payload(
            distance_km=165.0,
            duration_min=105.0,
            departure_iso="2025-04-19T12:00",
            urban_signal=0.35,
            temperature_c=9.0,
            rainfall_mm=0.0,
            humidity=0.55,
            wind_speed_kmh=5.0,
        ),
    ),
    (
        "Good",
        "Odense to Svendborg, spring Wednesday morning peak, a spot of drizzle",
        observation_payload(
            distance_km=45.0,
            duration_min=38.0,
            departure_iso="2025-04-16T08:00",
            urban_signal=0.55,
            temperature_c=9.0,
            rainfall_mm=0.5,
            humidity=0.70,
            wind_speed_kmh=15.0,
        ),
    ),
    (
        "Moderate",
        "Copenhagen to Roskilde, built-up, spring Wednesday rush hour, light rain and breeze",
        observation_payload(
            distance_km=35.6,
            duration_min=40.0,
            departure_iso="2025-04-16T08:00",
            urban_signal=0.70,
            temperature_c=9.0,
            rainfall_mm=1.0,
            humidity=0.75,
            wind_speed_kmh=22.0,
        ),
    ),
    (
        "Poor",
        "Odense to Svendborg before dawn in a freezing January storm",
        observation_payload(
            distance_km=45.0,
            duration_min=38.0,
            departure_iso="2025-01-15T04:00",
            urban_signal=0.55,
            temperature_c=-4.5,
            rainfall_mm=12.0,
            humidity=0.97,
            wind_speed_kmh=80.0,
        ),
    ),
    (
        "Very Poor",
        "Copenhagen to Roskilde at afternoon peak in the same freezing January storm",
        observation_payload(
            distance_km=35.6,
            duration_min=37.0,
            departure_iso="2025-01-15T16:00",
            urban_signal=0.80,
            temperature_c=-4.5,
            rainfall_mm=12.0,
            humidity=0.97,
            wind_speed_kmh=80.0,
        ),
    ),
]


@pytest.fixture(scope="module")
def predictions(client):
    """Run every scenario once; the assertions below share the results."""
    results = {}
    for expected, description, payload in SCENARIOS:
        response = client.post("/predict-road-condition", json=payload)
        assert response.status_code == 200, f"{description}: {response.text}"
        results[expected] = (description, response.json())
    return results


def test_every_one_of_the_five_labels_is_reachable(predictions):
    observed = {body["roadConditionLabel"] for _description, body in predictions.values()}
    missing = [label for label in LABELS if label not in observed]
    assert not missing, (
        "these grades were never predicted from realistic live input: "
        f"{', '.join(missing)}. Observed: {sorted(observed)}"
    )
    assert observed == set(LABELS)


@pytest.mark.parametrize("expected", LABELS)
def test_scenario_produces_its_intended_grade(predictions, expected):
    description, body = predictions[expected]
    assert body["roadConditionLabel"] == expected, (
        f"{description} was graded {body['roadConditionLabel']}, expected {expected}. "
        f"probabilities={body['classProbabilities']}"
    )


def test_very_poor_is_not_a_fluke_of_low_confidence(predictions):
    """A "Very Poor" that only wins by 0.21 to 0.20 would not really count."""
    _description, body = predictions["Very Poor"]
    assert body["confidence"] >= 0.5, body["classProbabilities"]


def test_severe_weather_grades_worse_than_calm_weather(predictions):
    calm = predictions["Excellent"][1]["roadConditionLabel"]
    storm = predictions["Very Poor"][1]["roadConditionLabel"]
    assert LABEL_ORDER[storm] > LABEL_ORDER[calm]


def test_leave_early_buffer_grows_with_severity(predictions):
    """The advice a driver acts on has to move with the grade."""
    buffers = {
        label: predictions[label][1]["leaveEarlyMinutes"] for label in LABELS
    }
    assert buffers["Excellent"] == 0
    ordered = [buffers[label] for label in LABELS]
    assert ordered == sorted(ordered), buffers
    assert buffers["Very Poor"] > buffers["Moderate"] > buffers["Good"]


def test_worst_grade_advisory_tells_the_driver_to_allow_time(predictions):
    _description, body = predictions["Very Poor"]
    assert "very poor" in body["advisory"].lower()
    assert body["influentialFeatures"], "a severe grade should have an explanation"
