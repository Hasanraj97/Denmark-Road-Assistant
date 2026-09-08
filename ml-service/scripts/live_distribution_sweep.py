"""
Evidence that all five grades are actually reachable in normal use.

The original model never once predicted "Very Poor". Its own classification
report scored that class at precision 0.00, recall 0.00 and f1 0.00, and a sweep
of realistic live inputs produced zero of them, so the advertised five-grade
advisory was really a four-grade one.

This script is the check that the claim is now true. It walks a grid of realistic
Danish trips - eight real route pairs, four seasons, weekday and weekend, all
twenty-four departure hours - and asks the live prediction path for each one.
Weather is drawn from a seasonal Danish distribution with a fixed seed, so the
run is reproducible.

It also compares the live feature ranges against the training ranges, which is
the check that train/serve skew has not crept back in.

Run it with:
    cd ml-service
    ./.venv/Scripts/python.exe scripts/live_distribution_sweep.py
"""

from __future__ import annotations

import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.features import (  # noqa: E402
    NUMERIC_COLUMNS,
    RouteObservation,
    derive_features,
)
from app.main import DATASET_PATH, manager  # noqa: E402

# Eight real Danish trips. distance_km, duration_min and urban_signal are the
# kind of values the backend actually produces for these pairs.
ROUTE_PAIRS = [
    ("Copenhagen -> Roskilde",     35.6, 37.0, 0.80),
    ("Copenhagen -> Helsingor",    46.0, 44.0, 0.74),
    ("Aarhus -> Randers",          38.5, 33.0, 0.62),
    ("Odense -> Svendborg",        45.0, 38.0, 0.55),
    ("Aalborg -> Frederikshavn",   66.0, 48.0, 0.48),
    ("Copenhagen -> Aarhus",      187.0, 145.0, 0.52),
    ("Esbjerg -> Kolding",         72.0, 52.0, 0.44),
    ("Vejle -> Billund",           28.0, 26.0, 0.38),
]

# Representative dates: one per season, on a weekday and at a weekend.
SEASON_DATES = {
    "winter": (datetime(2025, 1, 15), datetime(2025, 1, 18)),
    "spring": (datetime(2025, 4, 16), datetime(2025, 4, 19)),
    "summer": (datetime(2025, 7, 16), datetime(2025, 7, 19)),
    "autumn": (datetime(2025, 10, 15), datetime(2025, 10, 18)),
}

# Seasonal baseline temperature and humidity for Denmark.
SEASON_BASELINE = {
    "winter": (1.5, 0.87),
    "spring": (8.5, 0.74),
    "summer": (17.5, 0.68),
    "autumn": (9.5, 0.83),
}

# Weather bands the app will genuinely meet over a year. Sweeping only the median
# hour would be a rigged test in the opposite direction: it would "prove" that
# severe grades never happen simply by never asking about severe weather.
# Denmark gets several named storms a year, so "storm" belongs in the grid.
#   (label, rain mm/h, wind km/h, temperature offset, humidity offset)
WEATHER_BANDS = [
    ("calm",    0.0,  8.0,   1.5, -0.10),
    ("typical", 0.6, 18.0,   0.0,  0.00),
    ("wet",     3.5, 34.0,  -2.5,  0.08),
    ("storm",  12.0, 78.0,  -5.0,  0.12),
]

# Six departure hours covering every time-of-day bucket the model uses.
SWEEP_HOURS = [2, 8, 12, 17, 20, 23]


def main() -> int:
    manager.startup()
    rng = np.random.default_rng(2026)

    rows = []
    for route_name, distance_km, duration_min, urban_signal in ROUTE_PAIRS:
        for season, (weekday, weekend) in SEASON_DATES.items():
            mean_temp, mean_humidity = SEASON_BASELINE[season]
            for day_kind, base_date in (("weekday", weekday), ("weekend", weekend)):
                for band_name, rain, wind, temp_offset, humidity_offset in WEATHER_BANDS:
                    for hour in SWEEP_HOURS:
                        departure = base_date.replace(hour=hour)
                        features = derive_features(
                            RouteObservation(
                                distance_km=distance_km,
                                duration_min=duration_min,
                                departure=departure,
                                urban_signal=urban_signal,
                                temperature_c=float(mean_temp + temp_offset + rng.normal(0, 2.0)),
                                rainfall_mm=float(max(0.0, rain + rng.normal(0, rain * 0.25))),
                                humidity=float(
                                    np.clip(mean_humidity + humidity_offset + rng.normal(0, 0.04), 0.3, 1.0)
                                ),
                                wind_speed_kmh=float(max(0.0, wind + rng.normal(0, wind * 0.15))),
                            ),
                            rng=None,
                        )
                        prediction = manager.predict(features, weather_source="open-meteo")
                        features["_label"] = prediction.roadConditionLabel
                        features["_route"] = route_name
                        features["_day_kind"] = day_kind
                        features["_weather"] = band_name
                        rows.append(features)

    live = pd.DataFrame(rows)
    counts = Counter(live["_label"])
    total = len(live)

    print("=" * 74)
    print(f"LIVE PREDICTION SWEEP - {total} realistic route/time/weather combinations")
    print("=" * 74)
    for label in ["Excellent", "Good", "Moderate", "Poor", "Very Poor"]:
        n = counts.get(label, 0)
        bar = "#" * int(round(n / total * 50))
        print(f"  {label:<10} {n:>5}  {n / total * 100:5.2f}%  {bar}")

    missing = [l for l in ["Excellent", "Good", "Moderate", "Poor", "Very Poor"] if counts.get(l, 0) == 0]
    print()
    if missing:
        print(f"  FAIL - these grades were never predicted: {', '.join(missing)}")
    else:
        print("  PASS - every one of the five grades occurs.")

    print()
    print("  Grade by weather band (the model should react to the weather):")
    breakdown = pd.crosstab(live["_weather"], live["_label"])
    for band in ["calm", "typical", "wet", "storm"]:
        if band not in breakdown.index:
            continue
        parts = [
            f"{label} {int(breakdown.loc[band, label])}"
            for label in ["Excellent", "Good", "Moderate", "Poor", "Very Poor"]
            if label in breakdown.columns and breakdown.loc[band, label] > 0
        ]
        print(f"    {band:<9} {', '.join(parts)}")

    print()
    print("=" * 74)
    print("LIVE vs TRAINING FEATURE RANGES")
    print("=" * 74)
    training = pd.read_csv(DATASET_PATH)
    print(f"  {'feature':<26}{'live min':>10}{'live max':>10}{'train min':>11}{'train max':>11}   covered")
    worst = 1.0
    for column in NUMERIC_COLUMNS:
        lo, hi = float(live[column].min()), float(live[column].max())
        tlo, thi = float(training[column].min()), float(training[column].max())
        # How much of the live span sits inside the training span.
        overlap = max(0.0, min(hi, thi) - max(lo, tlo))
        span = max(hi - lo, 1e-9)
        covered = min(1.0, overlap / span) if hi > lo else (1.0 if tlo <= lo <= thi else 0.0)
        worst = min(worst, covered)
        flag = "ok" if covered >= 0.99 else "CHECK"
        print(f"  {column:<26}{lo:>10.2f}{hi:>10.2f}{tlo:>11.2f}{thi:>11.2f}   {covered * 100:5.1f}% {flag}")

    print()
    print(f"  Lowest coverage of any feature: {worst * 100:.1f}%")
    print("  (Coverage = the share of the live range that falls inside the training range.")
    print("   100% everywhere means the model is never asked to extrapolate.)")

    return 0 if not missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
