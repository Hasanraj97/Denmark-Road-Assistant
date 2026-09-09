"""
Small builders shared by several test modules.

Kept out of ``conftest.py`` so the test files import a normal module rather than
relying on pytest's conftest being importable by name.
"""

from __future__ import annotations

import sys
from pathlib import Path

ML_SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(ML_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(ML_SERVICE_ROOT))


def observation_payload(
    *,
    distance_km: float,
    duration_min: float,
    departure_iso: str,
    urban_signal: float,
    temperature_c: float,
    rainfall_mm: float,
    humidity: float,
    wind_speed_kmh: float,
) -> dict:
    """
    Build the camelCase JSON body the Java backend actually sends.

    ``departureIso`` is formatted the way ``RouteObservationService`` formats it:
    ``LocalDateTime.toString()`` on a value whose minutes and seconds are zeroed,
    which gives e.g. ``2025-04-16T08:00``.
    """
    return {
        "observation": {
            "distanceKm": distance_km,
            "durationMin": duration_min,
            "departureIso": departure_iso,
            "urbanSignal": urban_signal,
            "weather": {
                "temperatureC": temperature_c,
                "rainfallMm": rainfall_mm,
                "humidity": humidity,
                "windSpeedKmh": wind_speed_kmh,
            },
        }
    }
