"""
Regression test for the intermittent 500 found in system testing (9 Nov 2026).

With ``n_jobs=-1`` each prediction started and stopped a joblib thread pool, and
under Python 3.14 on Windows that pool could crash while shutting down. Serving
now predicts on a single thread. These tests make sure it stays that way and
that many predictions in a row all succeed.
"""

from __future__ import annotations

from helpers import observation_payload


def test_loaded_model_predicts_on_one_thread(client):
    from app.main import manager

    classifier = manager.pipeline.named_steps["classifier"]
    assert classifier.n_jobs == 1


def test_many_predictions_in_a_row_all_succeed(client):
    # Aalborg to Aarhus at 17:00 was the route whose prediction crashed.
    body = observation_payload(
        distance_km=118.0,
        duration_min=85.0,
        departure_iso="2026-11-10T17:00",
        urban_signal=0.45,
        temperature_c=6.0,
        rainfall_mm=0.5,
        humidity=0.9,
        wind_speed_kmh=25.0,
    )
    for _ in range(40):
        response = client.post("/predict-road-condition", json=body)
        assert response.status_code == 200
