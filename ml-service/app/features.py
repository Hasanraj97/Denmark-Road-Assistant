"""
The single definition of how a route becomes a feature vector.

WHY THIS FILE EXISTS
--------------------
The project used to derive features twice, in two languages. The Java backend
computed them one way at prediction time, and this Python service computed them a
different way when generating training data. The formulas had drifted apart, so
the model was tested on one distribution and used on another. Measured on the
shipped code, the live vectors occupied roughly 8% of the training distribution:
the model was extrapolating on nearly every real request.

There is now exactly ONE derivation, and it lives here. Both callers use it:

  * ``generate_dataset`` samples random *observations* and runs them through
    ``derive_features`` to build the training set;
  * the ``/predict-road-condition`` endpoint takes a *real* observation from the
    backend and runs it through the same ``derive_features``.

Train/serve skew is therefore not "fixed", it is structurally impossible: there
is no second copy of the formulas that could drift.

WHAT THE BACKEND SENDS
----------------------
Only facts it genuinely knows - distance, duration, when the driver is leaving,
how built-up the endpoints are, and the real weather from Open-Meteo. It no
longer invents traffic, accident or construction numbers. Everything derived is
derived here.

A NOTE ON RANDOMNESS
--------------------
``derive_features`` takes an optional ``rng``. When generating training data we
pass one, so the data has natural spread. When serving a prediction we pass
``None``, so the same route at the same time always gives the same answer.

The old code had a function called ``noise()`` that was not random at all: it
derived a multiplier from the *number of characters* in the two place names. That
meant the jitter was identical for every feature (perfectly correlated, where
training drew each independently), it compounded through chained features to
about 9%, and it silently changed whenever OpenStreetMap edited a place label.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any

import numpy as np

# --------------------------------------------------------------------------
# The feature contract. Kept in one place so the model, the API and the tests
# cannot disagree about column names or order.
# --------------------------------------------------------------------------

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

DAY_NAMES = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

WEEKEND_DAYS = {"saturday", "sunday"}


@dataclass(frozen=True)
class RouteObservation:
    """
    The raw facts about one trip. Nothing here is derived or guessed.

    ``departure`` is a single resolved moment. The old Java code took the hour
    from the user's chosen departure time but the day and the season from
    ``now()``, so a trip planned for Saturday morning could be labelled with
    Tuesday's traffic pattern. One timestamp, used for all three, removes that.
    """

    distance_km: float
    duration_min: float
    departure: datetime
    # How built-up the endpoints are, 0 (open country) to 1 (city centre).
    urban_signal: float
    # Real weather at the route midpoint at the departure hour.
    temperature_c: float
    rainfall_mm: float
    humidity: float
    wind_speed_kmh: float


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return float(min(max(value, low), high))


def season_of(month: int) -> str:
    if month in (12, 1, 2):
        return "winter"
    if month in (3, 4, 5):
        return "spring"
    if month in (6, 7, 8):
        return "summer"
    return "autumn"


def time_of_day_of(hour: int) -> str:
    """Buckets match the ones the app displays, so the label a user sees and the
    feature the model reads are always the same bucket."""
    if hour < 6:
        return "early_morning"
    if hour < 10:
        return "morning_peak"
    if hour < 15:
        return "midday"
    if hour < 19:
        return "afternoon_peak"
    if hour < 23:
        return "evening"
    return "night"


def rush_hour_pressure(hour: int, day_of_week: str) -> float:
    """
    How much the clock alone pushes traffic up, 0 to 1.

    Danish commuting peaks are roughly 07:00-09:00 and 15:00-18:00. Nights are
    quiet but not empty - the old Java code dropped them to 0.05 while the
    training data used 0.25 for the same hours, and because traffic_level is the
    single most important feature, that one mismatch made every night-time trip
    look far better than anything the model had been taught.
    """
    if 7 <= hour <= 9 or 15 <= hour <= 18:
        pressure = 0.85
    elif 10 <= hour <= 14:
        pressure = 0.55
    elif 19 <= hour <= 21:
        pressure = 0.35
    else:
        pressure = 0.20

    if day_of_week in WEEKEND_DAYS:
        pressure -= 0.15

    return clamp(pressure)


def weather_risk_from_conditions(
    temperature_c: float,
    rainfall_mm: float,
    humidity: float,
    wind_speed_kmh: float,
) -> float:
    """
    Turn four real measurements into a single 0-1 driving-risk number.

    This replaces a lookup that returned one of three values based only on the
    season, which meant the app displayed a real Open-Meteo forecast next to a
    prediction that had never seen it.

    Each part is chosen so it can be explained out loud:

    * **Rain** is the largest single contributor. 10 mm in an hour is heavy rain
      in Denmark, so that is treated as the ceiling.
    * **Ice** is the sharpest one. At or below freezing the road may be icy;
      between 0 and 3 degrees it still might be in shaded or exposed spots.
    * **Wind** starts to matter for high-sided vehicles and exposed bridges above
      about 15 km/h and is severe by 60 km/h.
    * **Humidity** is a small proxy for mist and fog, and only counts above 70%.

    The weighted sum can exceed 1 when several hazards coincide, and is clipped -
    which is the intended behaviour, since "snow, gales and freezing at once" is
    simply maximum risk.
    """
    rain_component = clamp(rainfall_mm / 10.0)

    if temperature_c <= 0.0:
        ice_component = 1.0
    elif temperature_c <= 3.0:
        ice_component = 0.5
    else:
        ice_component = 0.0

    wind_component = clamp((wind_speed_kmh - 15.0) / 45.0)
    fog_component = clamp((humidity - 0.70) / 0.30)

    return clamp(
        0.45 * rain_component
        + 0.30 * ice_component
        + 0.25 * wind_component
        + 0.10 * fog_component
    )


def region_type_of(urban_density: float, distance_km: float) -> str:
    if urban_density > 0.68 or distance_km < 22:
        return "urban"
    if urban_density > 0.44 or distance_km < 80:
        return "suburban"
    return "regional"


def _jitter(rng: Any, spread: float) -> float:
    """A multiplier near 1.0, or exactly 1.0 when no generator is supplied."""
    if rng is None:
        return 1.0
    return 1.0 + float(rng.uniform(-spread, spread))


def derive_features(observation: RouteObservation, rng: Any = None) -> dict[str, Any]:
    """
    The one and only feature derivation.

    Pass ``rng`` when building training data. Leave it ``None`` when serving, so
    a given route at a given time always produces the same prediction.
    """
    distance_km = float(observation.distance_km)
    duration_min = float(observation.duration_min)
    avg_speed_kmh = distance_km / (duration_min / 60.0) if duration_min > 0 else 0.0

    departure = observation.departure
    hour = departure.hour
    day_of_week = DAY_NAMES[departure.weekday()]
    season = season_of(departure.month)
    time_of_day = time_of_day_of(hour)

    # --- how built-up the route is -----------------------------------------
    # Short trips are dominated by their urban endpoints; long ones spend most of
    # their distance on open road, so the endpoints matter less.
    urban_density = float(observation.urban_signal)
    if distance_km < 20:
        urban_density += 0.15
    elif distance_km > 100:
        urban_density -= 0.15
    urban_density = clamp(urban_density * _jitter(rng, 0.05))

    region_type = region_type_of(urban_density, distance_km)

    # --- road quality proxy -------------------------------------------------
    # A high sustained average speed implies motorway-grade road.
    road_type_score = clamp((0.20 + avg_speed_kmh / 140.0) * _jitter(rng, 0.05))

    # --- real weather -------------------------------------------------------
    temperature_c = float(observation.temperature_c)
    rainfall_mm = max(0.0, float(observation.rainfall_mm))
    humidity = clamp(float(observation.humidity))
    wind_speed_kmh = max(0.0, float(observation.wind_speed_kmh))
    weather_risk = weather_risk_from_conditions(
        temperature_c, rainfall_mm, humidity, wind_speed_kmh
    )

    # --- traffic ------------------------------------------------------------
    # Built-up areas and rush hour both push it up; bad weather adds a little
    # because people slow down and queues form.
    pressure = rush_hour_pressure(hour, day_of_week)
    traffic_level = clamp(
        (0.10 + urban_density * 0.42 + pressure * 0.42 + weather_risk * 0.10)
        * _jitter(rng, 0.06)
    )

    # --- roadworks ----------------------------------------------------------
    # Denmark does most of its resurfacing in the warmer months, and there is
    # simply more of it in built-up areas.
    seasonal_roadworks = {"spring": 0.18, "summer": 0.22, "autumn": 0.12, "winter": 0.05}[season]
    construction_risk_score = clamp(
        (0.06 + urban_density * 0.30 + seasonal_roadworks) * _jitter(rng, 0.18)
    )

    # --- accident exposure --------------------------------------------------
    # Busy plus bad weather is the dangerous combination. A better road grade
    # takes a little back off.
    accident_risk_score = clamp(
        (0.08 + traffic_level * 0.38 + weather_risk * 0.42 - road_type_score * 0.10)
        * _jitter(rng, 0.08)
    )

    # --- how loaded the road is --------------------------------------------
    vehicle_load_factor = clamp(
        (0.08 + traffic_level * 0.72) * _jitter(rng, 0.08)
    )

    # --- terrain ------------------------------------------------------------
    # Denmark is flat, so this grows slowly with distance and stays small.
    elevation_variation = max(2.0, (4.0 + distance_km * 0.06) * _jitter(rng, 0.15))

    return {
        "distance_km": round(distance_km, 2),
        "estimated_duration_min": round(duration_min, 2),
        "avg_speed_kmh": round(avg_speed_kmh, 2),
        "road_type_score": round(road_type_score, 4),
        "traffic_level": round(traffic_level, 4),
        "weather_risk": round(weather_risk, 4),
        "rainfall_mm": round(rainfall_mm, 2),
        "time_of_day": time_of_day,
        "day_of_week": day_of_week,
        "urban_density": round(urban_density, 4),
        "accident_risk_score": round(accident_risk_score, 4),
        "construction_risk_score": round(construction_risk_score, 4),
        "elevation_variation": round(elevation_variation, 2),
        "temperature_c": round(temperature_c, 2),
        "humidity": round(humidity, 4),
        "wind_speed_kmh": round(wind_speed_kmh, 2),
        "season": season,
        "region_type": region_type,
        "vehicle_load_factor": round(vehicle_load_factor, 4),
    }


# --------------------------------------------------------------------------
# Labelling
# --------------------------------------------------------------------------

def condition_score(features: dict[str, Any]) -> float:
    """
    How difficult this trip looks, 0 (ideal) to 1 (severe).

    This is the rule that produces the training labels. It is a stated
    assumption, not a measurement, and MODEL_EVALUATION.md says so plainly:
    the model learns to reproduce THIS rule, so accuracy against it is partly
    circular. It is written down here so a reader can judge the rule itself.

    Weights are deliberately dominated by weather and traffic, because those are
    the two things a driver can actually act on by leaving earlier.
    """
    return clamp(
        features["traffic_level"] * 0.26
        + features["weather_risk"] * 0.30
        + features["accident_risk_score"] * 0.14
        + features["urban_density"] * 0.08
        + features["construction_risk_score"] * 0.08
        + features["vehicle_load_factor"] * 0.06
        - features["road_type_score"] * 0.10
        - clamp(features["avg_speed_kmh"] / 110.0) * 0.06
        + 0.06
    )


# Boundaries on the 0-1 difficulty scale above. Chosen to be describable in
# words rather than tuned to hit a target class balance.
LABEL_THRESHOLDS = [
    (0.22, "Excellent"),   # nothing of note
    (0.34, "Good"),        # light friction
    (0.48, "Moderate"),    # noticeable delay likely
    (0.62, "Poor"),        # several problems at once
]


def label_for_score(score: float) -> str:
    for threshold, label in LABEL_THRESHOLDS:
        if score < threshold:
            return label
    return "Very Poor"
