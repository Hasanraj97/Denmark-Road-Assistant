"""
Defect M2 - train/serve skew.

The project used to derive the nineteen features twice: once in Java at
prediction time and once in Python when generating training data. The two sets of
formulas had drifted, so the live vectors sat almost entirely outside the
distribution the model was fitted on.

There is now one derivation, ``features.derive_features``, called by both
``dataset.generate_dataset`` and the ``/predict-road-condition`` handler. This
module checks the two properties that follow from that:

1. **Serve-time determinism.** ``derive_features(observation, rng=None)`` must
   return the identical vector every time it is called. The jitter used when
   building training data is opt-in via ``rng``; passing ``None`` must switch it
   off completely, so a given route at a given time always yields one answer.

2. **Distribution coverage.** A grid of live-style observations - real Danish
   route pairs, every hour of the day, all four seasons, weekday and weekend, and
   weather from calm to a named winter storm - must produce feature values that
   all fall inside the min/max of the committed training CSV. If they do not, the
   model is extrapolating on real traffic.

TOLERANCE
---------
The range check allows each column to fall outside the committed training
min/max by up to ``RANGE_TOLERANCE_FRACTION`` (1%) of that column's training
span, i.e. ``0.01 * (train_max - train_min)``. That is a per-column relative
tolerance, so it is 3.17 km on ``distance_km`` (span 3.00-319.93) and 0.01 on
``weather_risk`` (span 0-1), rather than one absolute number that would be
meaningless across columns with different units.

The tolerance is slack, not a fudge: ``test_live_features_are_strictly_inside_the_training_range``
below asserts the excursion is exactly zero, so the tolerance is currently
unused. It exists so a future dataset regenerated with a different seed, which
would move the extremes slightly, does not fail the suite for a rounding-sized
difference.
"""

from __future__ import annotations

from datetime import datetime

import pandas as pd
import pytest

from helpers import ML_SERVICE_ROOT  # noqa: F401  (ensures the app package is importable)

from app.features import (
    CATEGORICAL_COLUMNS,
    FEATURE_COLUMNS,
    NUMERIC_COLUMNS,
    RouteObservation,
    derive_features,
)

RANGE_TOLERANCE_FRACTION = 0.01

# Real Danish trips: (label, distance km, duration min, urban signal). The
# distances and durations are the sort of numbers OSRM returns for these pairs,
# and the urban signal is the average of the two endpoints' urban scores.
LIVE_ROUTES = [
    ("Copenhagen -> Roskilde", 35.6, 37.0, 0.80),
    ("Copenhagen -> Helsingor", 46.0, 44.0, 0.74),
    ("Aarhus -> Randers", 38.5, 33.0, 0.62),
    ("Odense -> Svendborg", 45.0, 38.0, 0.55),
    ("Aalborg -> Frederikshavn", 66.0, 48.0, 0.48),
    ("Copenhagen -> Aarhus", 187.0, 145.0, 0.52),
    ("Esbjerg -> Kolding", 72.0, 52.0, 0.44),
    ("Vejle -> Billund", 28.0, 26.0, 0.38),
    ("Norrebro -> Amager (short city hop)", 3.5, 9.0, 0.92),
    ("Skagen -> Gedser (end to end)", 312.0, 205.0, 0.20),
]

# One weekday and one weekend day in each season.
LIVE_DATES = [
    datetime(2025, 1, 15),   # Wednesday, winter
    datetime(2025, 1, 18),   # Saturday, winter
    datetime(2025, 4, 16),   # Wednesday, spring
    datetime(2025, 4, 19),   # Saturday, spring
    datetime(2025, 7, 16),   # Wednesday, summer
    datetime(2025, 7, 19),   # Saturday, summer
    datetime(2025, 10, 15),  # Wednesday, autumn
    datetime(2025, 10, 18),  # Saturday, autumn
]

# Weather the app will genuinely meet over a year, from a still summer morning to
# a named winter storm: (rain mm/h, wind km/h, temperature C, humidity).
LIVE_WEATHER = [
    (0.0, 3.0, 20.0, 0.50),
    (0.6, 18.0, 8.0, 0.72),
    (3.5, 34.0, 3.0, 0.88),
    (12.0, 78.0, -2.0, 0.96),
    (24.0, 120.0, -12.0, 0.99),
]

LIVE_HOURS = [0, 2, 5, 8, 11, 14, 17, 20, 22, 23]


def _live_observations():
    for _name, distance_km, duration_min, urban_signal in LIVE_ROUTES:
        for date in LIVE_DATES:
            for rainfall_mm, wind_speed_kmh, temperature_c, humidity in LIVE_WEATHER:
                for hour in LIVE_HOURS:
                    yield RouteObservation(
                        distance_km=distance_km,
                        duration_min=duration_min,
                        departure=date.replace(hour=hour),
                        urban_signal=urban_signal,
                        temperature_c=temperature_c,
                        rainfall_mm=rainfall_mm,
                        humidity=humidity,
                        wind_speed_kmh=wind_speed_kmh,
                    )


@pytest.fixture(scope="module")
def live_frame() -> pd.DataFrame:
    """Every live-style observation, run through the one real derivation."""
    rows = [derive_features(observation, rng=None) for observation in _live_observations()]
    assert len(rows) == len(LIVE_ROUTES) * len(LIVE_DATES) * len(LIVE_WEATHER) * len(LIVE_HOURS)
    return pd.DataFrame(rows)[FEATURE_COLUMNS]


# ---------------------------------------------------------------------------
# 1. Serve-time determinism
# ---------------------------------------------------------------------------

ONE_TRIP = RouteObservation(
    distance_km=35.6,
    duration_min=37.0,
    departure=datetime(2025, 4, 16, 8, 0),
    urban_signal=0.78,
    temperature_c=9.0,
    rainfall_mm=0.6,
    humidity=0.74,
    wind_speed_kmh=18.0,
)


def test_derive_features_with_no_rng_is_deterministic():
    first = derive_features(ONE_TRIP, rng=None)
    for attempt in range(50):
        again = derive_features(ONE_TRIP, rng=None)
        assert again == first, f"attempt {attempt} differed: {again} != {first}"


def test_derive_features_is_deterministic_across_equal_observations():
    """Two separately built but equal observations must agree, not just the same
    object handed back repeatedly."""
    twin = RouteObservation(
        distance_km=35.6,
        duration_min=37.0,
        departure=datetime(2025, 4, 16, 8, 0),
        urban_signal=0.78,
        temperature_c=9.0,
        rainfall_mm=0.6,
        humidity=0.74,
        wind_speed_kmh=18.0,
    )
    assert derive_features(twin, rng=None) == derive_features(ONE_TRIP, rng=None)


def test_default_rng_argument_is_also_deterministic():
    """The endpoint passes rng=None explicitly, but the default must match."""
    assert derive_features(ONE_TRIP) == derive_features(ONE_TRIP, rng=None)


def test_every_live_observation_is_individually_deterministic(live_frame):
    """Determinism across the whole grid, not just one favourable trip."""
    repeat = pd.DataFrame(
        [derive_features(observation, rng=None) for observation in _live_observations()]
    )[FEATURE_COLUMNS]
    pd.testing.assert_frame_equal(live_frame, repeat)


def test_derive_features_returns_exactly_the_contracted_columns():
    vector = derive_features(ONE_TRIP, rng=None)
    assert set(vector) == set(FEATURE_COLUMNS)
    assert len(FEATURE_COLUMNS) == 19


# ---------------------------------------------------------------------------
# 2. Live features sit inside the training distribution
# ---------------------------------------------------------------------------

def test_live_numeric_features_are_inside_the_training_range(live_frame, training_frame):
    problems = []
    for column in NUMERIC_COLUMNS:
        train_low = float(training_frame[column].min())
        train_high = float(training_frame[column].max())
        tolerance = RANGE_TOLERANCE_FRACTION * (train_high - train_low)

        live_low = float(live_frame[column].min())
        live_high = float(live_frame[column].max())

        if live_low < train_low - tolerance:
            problems.append(
                f"{column}: live min {live_low:.4f} is below training min "
                f"{train_low:.4f} by more than the {tolerance:.4f} tolerance"
            )
        if live_high > train_high + tolerance:
            problems.append(
                f"{column}: live max {live_high:.4f} is above training max "
                f"{train_high:.4f} by more than the {tolerance:.4f} tolerance"
            )

    assert not problems, "train/serve skew has returned:\n  " + "\n  ".join(problems)


def test_live_features_are_strictly_inside_the_training_range(live_frame, training_frame):
    """
    Stronger than the tolerance check above: with the committed dataset the
    excursion is currently zero on every column, and this pins that down.
    """
    excursions = {}
    for column in NUMERIC_COLUMNS:
        train_low = float(training_frame[column].min())
        train_high = float(training_frame[column].max())
        below = max(0.0, train_low - float(live_frame[column].min()))
        above = max(0.0, float(live_frame[column].max()) - train_high)
        if below or above:
            excursions[column] = (round(below, 6), round(above, 6))

    assert not excursions, f"columns leaving the training range: {excursions}"


def test_no_live_row_falls_outside_the_training_range(live_frame, training_frame):
    """Range endpoints are not enough - check every individual row."""
    outside = {}
    for column in NUMERIC_COLUMNS:
        train_low = float(training_frame[column].min())
        train_high = float(training_frame[column].max())
        tolerance = RANGE_TOLERANCE_FRACTION * (train_high - train_low)
        values = live_frame[column]
        offenders = int(
            ((values < train_low - tolerance) | (values > train_high + tolerance)).sum()
        )
        if offenders:
            outside[column] = offenders
    assert not outside, f"rows outside the training range, by column: {outside}"


def test_live_categorical_values_all_occur_in_training(live_frame, training_frame):
    for column in CATEGORICAL_COLUMNS:
        live_values = set(live_frame[column].unique())
        training_values = set(training_frame[column].unique())
        unseen = live_values - training_values
        assert not unseen, (
            f"{column}: live values the model has never been trained on: {sorted(unseen)}"
        )


def test_the_live_grid_actually_stresses_the_feature_space(live_frame):
    """
    Guards against the range test passing for the wrong reason. A grid that only
    ever asked about one mild Tuesday would trivially sit inside the training
    range while proving nothing.
    """
    assert set(live_frame["season"]) == {"winter", "spring", "summer", "autumn"}
    assert len(set(live_frame["time_of_day"])) == 6
    assert len(set(live_frame["region_type"])) == 3
    assert float(live_frame["weather_risk"].min()) == pytest.approx(0.0, abs=0.02)
    assert float(live_frame["weather_risk"].max()) >= 0.95
    assert float(live_frame["traffic_level"].max()) - float(live_frame["traffic_level"].min()) > 0.5


def test_training_csv_has_the_contracted_columns(training_frame):
    """If the CSV ever drifts from FEATURE_COLUMNS the checks above are hollow."""
    assert list(training_frame.columns) == FEATURE_COLUMNS + ["road_condition_label"]
