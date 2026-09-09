"""
Unit tests for the two derived quantities a reader is most likely to challenge:
the weather risk score and the rush-hour pressure curve.

Both replaced lookups that ignored the thing they claimed to measure. The old
weather risk was one of three constants chosen by the season, so the app showed a
live Open-Meteo forecast next to a number that had never seen it. The old rush
hour value disagreed between the Java service and the training generator, and
because traffic is one of the strongest features that single mismatch made every
night-time trip look better than anything the model had been taught.

These tests check the shape of the curves rather than exact constants wherever
possible, so a future retuning of the weights does not fail the suite for no
reason - but the documented ceilings (10 mm/h rain, 0 C ice, 60 km/h wind) are
pinned, because they are what the docstring promises a reader.
"""

from __future__ import annotations

import pytest

from helpers import ML_SERVICE_ROOT  # noqa: F401  (ensures the app package is importable)

from app.features import (
    DAY_NAMES,
    LABEL_THRESHOLDS,
    LABELS,
    clamp,
    label_for_score,
    rush_hour_pressure,
    season_of,
    time_of_day_of,
    weather_risk_from_conditions,
)

# A calm, dry, mild hour: nothing for a driver to think about.
CALM = dict(temperature_c=15.0, rainfall_mm=0.0, humidity=0.55, wind_speed_kmh=8.0)


def risk(**overrides) -> float:
    return weather_risk_from_conditions(**{**CALM, **overrides})


# ---------------------------------------------------------------------------
# weather_risk_from_conditions
# ---------------------------------------------------------------------------

def test_calm_dry_mild_weather_is_near_zero():
    assert risk() == pytest.approx(0.0, abs=1e-9)


def test_output_is_always_a_probability_like_number():
    for temperature in (-20.0, -0.1, 0.0, 2.9, 3.0, 12.0, 30.0):
        for rain in (0.0, 0.5, 9.9, 10.0, 40.0):
            for wind in (0.0, 15.0, 59.9, 60.0, 150.0):
                for humidity in (0.0, 0.7, 0.85, 1.0):
                    value = weather_risk_from_conditions(temperature, rain, humidity, wind)
                    assert 0.0 <= value <= 1.0


def test_heavy_rain_raises_the_risk():
    light = risk(rainfall_mm=1.0)
    moderate = risk(rainfall_mm=4.0)
    heavy = risk(rainfall_mm=10.0)
    assert risk() < light < moderate < heavy
    # 10 mm/h is documented as the ceiling for the rain component.
    assert heavy == pytest.approx(risk(rainfall_mm=25.0))


def test_rain_is_the_largest_single_contributor():
    """The docstring says so, so the weights had better agree."""
    rain_only = risk(rainfall_mm=10.0)
    ice_only = risk(temperature_c=-5.0)
    wind_only = risk(wind_speed_kmh=60.0)
    fog_only = risk(humidity=1.0)
    assert rain_only > ice_only > wind_only > fog_only


def test_sub_zero_temperature_raises_the_risk():
    mild = risk(temperature_c=12.0)
    chilly = risk(temperature_c=2.0)     # 0-3 C: possible ice in shaded spots
    freezing = risk(temperature_c=0.0)   # at or below freezing
    hard_frost = risk(temperature_c=-8.0)
    assert mild < chilly < freezing
    assert freezing == pytest.approx(hard_frost)
    assert freezing > 0.0


def test_the_ice_step_happens_exactly_at_the_documented_boundaries():
    assert risk(temperature_c=3.1) == pytest.approx(risk(temperature_c=20.0))
    assert risk(temperature_c=3.0) > risk(temperature_c=3.1)
    assert risk(temperature_c=0.0) > risk(temperature_c=0.1)


def test_high_wind_raises_the_risk():
    still = risk(wind_speed_kmh=5.0)
    breezy = risk(wind_speed_kmh=15.0)
    windy = risk(wind_speed_kmh=35.0)
    gale = risk(wind_speed_kmh=60.0)
    assert still == pytest.approx(breezy)  # nothing below the 15 km/h threshold
    assert breezy < windy < gale
    # 60 km/h is documented as severe, and is where the component saturates.
    assert gale == pytest.approx(risk(wind_speed_kmh=130.0))


def test_humidity_only_counts_above_seventy_percent():
    assert risk(humidity=0.40) == pytest.approx(risk(humidity=0.70))
    assert risk(humidity=0.85) > risk(humidity=0.70)
    assert risk(humidity=1.00) > risk(humidity=0.85)


def test_an_extreme_combination_clips_at_one():
    """Snow, gales and freezing at once is simply maximum risk."""
    worst = weather_risk_from_conditions(
        temperature_c=-12.0, rainfall_mm=26.0, humidity=1.0, wind_speed_kmh=135.0
    )
    assert worst == 1.0
    # The unclipped weighted sum genuinely exceeds 1, so this is a real clip and
    # not an accident of the weights adding to exactly 1.0.
    assert 0.45 + 0.30 + 0.25 + 0.10 > 1.0

    even_worse = weather_risk_from_conditions(
        temperature_c=-40.0, rainfall_mm=200.0, humidity=1.0, wind_speed_kmh=400.0
    )
    assert even_worse == 1.0


def test_negative_or_absurd_inputs_do_not_escape_the_range():
    assert weather_risk_from_conditions(20.0, -5.0, -1.0, -30.0) == 0.0


# ---------------------------------------------------------------------------
# rush_hour_pressure
# ---------------------------------------------------------------------------

MORNING_PEAK = [7, 8, 9]
AFTERNOON_PEAK = [15, 16, 17, 18]
MIDDAY = [10, 11, 12, 13, 14]
EVENING = [19, 20, 21]
NIGHT = [22, 23, 0, 1, 2, 3, 4, 5, 6]


def test_peak_hours_score_higher_than_midday():
    midday = [rush_hour_pressure(hour, "wednesday") for hour in MIDDAY]
    for hour in MORNING_PEAK + AFTERNOON_PEAK:
        peak = rush_hour_pressure(hour, "wednesday")
        assert peak > max(midday), f"hour {hour} scored {peak}, not above midday {max(midday)}"


def test_midday_scores_higher_than_night():
    night = [rush_hour_pressure(hour, "wednesday") for hour in NIGHT]
    for hour in MIDDAY:
        assert rush_hour_pressure(hour, "wednesday") > max(night)


def test_the_full_ordering_is_peak_then_midday_then_evening_then_night():
    peak = rush_hour_pressure(8, "wednesday")
    midday = rush_hour_pressure(12, "wednesday")
    evening = rush_hour_pressure(20, "wednesday")
    night = rush_hour_pressure(3, "wednesday")
    assert peak > midday > evening > night


def test_nights_are_quiet_but_not_empty():
    """The old Java service dropped nights to 0.05 while the training data used
    0.25 for the same hours; a floor well above zero is the point here."""
    for hour in NIGHT:
        assert rush_hour_pressure(hour, "wednesday") >= 0.15


@pytest.mark.parametrize("hour", list(range(24)))
def test_weekend_is_lower_than_the_same_weekday_hour(hour):
    weekday = rush_hour_pressure(hour, "wednesday")
    saturday = rush_hour_pressure(hour, "saturday")
    sunday = rush_hour_pressure(hour, "sunday")
    assert saturday < weekday, f"hour {hour}: saturday {saturday} not below wednesday {weekday}"
    assert sunday == pytest.approx(saturday)


def test_all_weekdays_agree_with_each_other():
    for hour in (3, 8, 12, 17, 20):
        values = {
            day: rush_hour_pressure(hour, day)
            for day in DAY_NAMES
            if day not in ("saturday", "sunday")
        }
        assert len(set(values.values())) == 1, values


@pytest.mark.parametrize("hour", list(range(24)))
@pytest.mark.parametrize("day", DAY_NAMES)
def test_pressure_stays_within_zero_and_one(hour, day):
    assert 0.0 <= rush_hour_pressure(hour, day) <= 1.0


# ---------------------------------------------------------------------------
# The small helpers the two above lean on
# ---------------------------------------------------------------------------

def test_clamp_bounds_both_ends():
    assert clamp(-4.0) == 0.0
    assert clamp(0.37) == pytest.approx(0.37)
    assert clamp(9.0) == 1.0
    assert clamp(9.0, 0.0, 5.0) == 5.0


@pytest.mark.parametrize(
    "month, expected",
    [
        (12, "winter"), (1, "winter"), (2, "winter"),
        (3, "spring"), (4, "spring"), (5, "spring"),
        (6, "summer"), (7, "summer"), (8, "summer"),
        (9, "autumn"), (10, "autumn"), (11, "autumn"),
    ],
)
def test_season_of_covers_every_month(month, expected):
    assert season_of(month) == expected


@pytest.mark.parametrize(
    "hour, expected",
    [
        (0, "early_morning"), (5, "early_morning"),
        (6, "morning_peak"), (9, "morning_peak"),
        (10, "midday"), (14, "midday"),
        (15, "afternoon_peak"), (18, "afternoon_peak"),
        (19, "evening"), (22, "evening"),
        (23, "night"),
    ],
)
def test_time_of_day_buckets(hour, expected):
    assert time_of_day_of(hour) == expected


def test_every_hour_maps_to_a_known_bucket():
    buckets = {time_of_day_of(hour) for hour in range(24)}
    assert buckets == {
        "early_morning", "morning_peak", "midday", "afternoon_peak", "evening", "night"
    }


@pytest.mark.parametrize(
    "score, expected",
    [
        (0.00, "Excellent"), (0.21, "Excellent"),
        (0.22, "Good"), (0.33, "Good"),
        (0.34, "Moderate"), (0.47, "Moderate"),
        (0.48, "Poor"), (0.61, "Poor"),
        (0.62, "Very Poor"), (1.00, "Very Poor"),
    ],
)
def test_label_for_score_matches_the_documented_thresholds(score, expected):
    assert label_for_score(score) == expected


def test_thresholds_are_ordered_and_name_the_first_four_labels():
    thresholds = [value for value, _label in LABEL_THRESHOLDS]
    assert thresholds == sorted(thresholds)
    assert [label for _value, label in LABEL_THRESHOLDS] == LABELS[:4]
