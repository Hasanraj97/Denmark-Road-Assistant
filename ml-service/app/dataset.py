"""
Builds the synthetic training set.

The important property of this module is that it does NOT know how to compute a
single feature. It only invents plausible *observations* - a distance, a
departure time, how built-up the endpoints are, and some weather - and then hands
each one to ``features.derive_features``, which is the exact function the live
prediction endpoint uses. That is what keeps the training distribution and the
serving distribution the same shape.

HONESTY NOTE (repeated in MODEL_EVALUATION.md)
----------------------------------------------
The weather here is sampled from *stratified* severity bands, not from Denmark's
real weather frequencies. Calm days are deliberately under-represented and storms
deliberately over-represented, because a model that has only ever seen four
severe hours cannot learn what a severe hour looks like. The consequence is that
the class balance of this dataset is NOT an estimate of how often Danish roads
are actually in each condition, and no claim of that kind is made anywhere.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

import numpy as np
import pandas as pd

from .features import (
    FEATURE_COLUMNS,
    RouteObservation,
    condition_score,
    derive_features,
    label_for_score,
    season_of,
)

# A fixed start date so that a given seed reproduces a byte-identical dataset.
# The old dataset could not be reproduced from the code at all - it contained
# 5,837 rows that the committed generator was arithmetically incapable of
# producing - so "regenerate and get the same thing" is a deliberate goal here.
EPOCH = datetime(2025, 1, 1, 0, 0, 0)

# Route archetypes: (name, distance range km, typical speed range km/h, urban signal range)
ROUTE_ARCHETYPES = [
    ("city",     (3.0, 25.0),   (22.0, 48.0),  (0.62, 0.92)),
    ("suburban", (18.0, 90.0),  (40.0, 80.0),  (0.38, 0.70)),
    ("regional", (70.0, 320.0), (65.0, 112.0), (0.12, 0.42)),
]
ARCHETYPE_PROBS = [0.36, 0.34, 0.30]

# Weather severity bands. Each is (rain mm/h, wind km/h, humidity, temperature
# offset from the seasonal mean). Sampling is uniform inside a band.
SEVERITY_BANDS = {
    "calm":    {"rain": (0.0, 0.0),   "wind": (2.0, 14.0),  "humidity": (0.45, 0.70), "temp_offset": (0.0, 6.0)},
    "mild":    {"rain": (0.0, 1.2),   "wind": (8.0, 26.0),  "humidity": (0.55, 0.82), "temp_offset": (-2.0, 4.0)},
    "wet":     {"rain": (1.0, 5.0),   "wind": (14.0, 40.0), "humidity": (0.70, 0.95), "temp_offset": (-4.0, 2.0)},
    "severe":  {"rain": (4.0, 11.0),  "wind": (30.0, 62.0),  "humidity": (0.80, 1.00), "temp_offset": (-7.0, 1.0)},
    # The upper wind bound is deliberately hurricane-force. Denmark gets named
    # storms most winters and gusts above 120 km/h are on record, so the model
    # must have seen them; otherwise a genuine storm forces it to extrapolate.
    "extreme": {"rain": (9.0, 26.0),  "wind": (55.0, 135.0), "humidity": (0.85, 1.00), "temp_offset": (-12.0, 0.0)},
}
SEVERITY_NAMES = list(SEVERITY_BANDS)
SEVERITY_PROBS = [0.26, 0.24, 0.22, 0.17, 0.11]

SEASONAL_MEAN_TEMPERATURE = {"winter": 1.5, "spring": 8.5, "summer": 17.5, "autumn": 9.5}


def _sample_observation(rng: np.random.Generator) -> RouteObservation:
    """Invent one plausible Danish trip, weather included."""
    archetype_index = rng.choice(len(ROUTE_ARCHETYPES), p=ARCHETYPE_PROBS)
    _name, distance_range, speed_range, urban_range = ROUTE_ARCHETYPES[archetype_index]

    distance_km = float(rng.uniform(*distance_range))
    free_flow_speed = float(rng.uniform(*speed_range))
    urban_signal = float(rng.uniform(*urban_range))

    # Departure: any hour of any day across a full year, so every season,
    # weekday and hour bucket is represented.
    departure = EPOCH + timedelta(
        days=int(rng.integers(0, 365)),
        hours=int(rng.integers(0, 24)),
        minutes=int(rng.integers(0, 60)),
    )
    season = season_of(departure.month)

    # Weather, drawn from a severity band (see the honesty note above).
    band = SEVERITY_BANDS[SEVERITY_NAMES[rng.choice(len(SEVERITY_NAMES), p=SEVERITY_PROBS)]]
    rainfall_mm = float(rng.uniform(*band["rain"]))
    wind_speed_kmh = float(rng.uniform(*band["wind"]))
    humidity = float(rng.uniform(*band["humidity"]))
    temperature_c = float(
        SEASONAL_MEAN_TEMPERATURE[season]
        + rng.uniform(*band["temp_offset"])
        + rng.normal(0.0, 2.5)
    )

    # The realised journey time. Congestion and bad weather make the trip slower
    # than free flow; this is an observation, not a derived feature - it is
    # exactly the kind of number OSRM gives the backend for a real route.
    congestion_penalty = 1.0 + float(rng.uniform(0.0, 0.45))
    realised_speed = max(8.0, free_flow_speed / congestion_penalty)
    duration_min = (distance_km / realised_speed) * 60.0

    return RouteObservation(
        distance_km=distance_km,
        duration_min=duration_min,
        departure=departure,
        urban_signal=urban_signal,
        temperature_c=temperature_c,
        rainfall_mm=rainfall_mm,
        humidity=humidity,
        wind_speed_kmh=wind_speed_kmh,
    )


def generate_dataset(sample_count: int = 12000, seed: int = 42) -> pd.DataFrame:
    """
    Produce the labelled training set.

    Deterministic for a given ``seed``: regenerating gives an identical file.
    """
    rng = np.random.default_rng(seed)
    rows: list[dict[str, Any]] = []

    for _ in range(sample_count):
        observation = _sample_observation(rng)
        features = derive_features(observation, rng=rng)

        score = condition_score(features)
        # A small amount of label noise, so the model cannot achieve a perfect
        # score by memorising a clean threshold. This is honest about the fact
        # that two identical-looking trips do not always go the same way.
        score += float(rng.normal(0.0, 0.025))
        features["road_condition_label"] = label_for_score(score)

        rows.append(features)

    frame = pd.DataFrame(rows)
    # Guarantee the column order the model expects.
    return frame[FEATURE_COLUMNS + ["road_condition_label"]]
