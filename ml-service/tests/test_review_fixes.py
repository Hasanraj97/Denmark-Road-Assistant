"""
Regression tests for the fixes made after the November 2026 review.

* The advisory must name the real source of the weather. It used to call a
  seasonal fallback a "live forecast".
* The response lists factors that pull AWAY from the predicted grade, not only
  the ones that support it.
* The leave-early and confidence sentences say what they really are.
* The service refuses to serve a model whose checksum does not match, and it
  never trains silently when the model files are missing.
"""

from __future__ import annotations

import json
import shutil

import pytest

from helpers import observation_payload

STORMY = dict(
    distance_km=187.0,
    duration_min=142.0,
    departure_iso="2026-01-14T08:00",
    urban_signal=0.55,
    temperature_c=-1.0,
    rainfall_mm=6.0,
    humidity=0.97,
    wind_speed_kmh=55.0,
)


def _with_source(source):
    body = observation_payload(**STORMY)
    if source is not None:
        body["observation"]["weather"]["source"] = source
    return body


def test_fallback_weather_is_never_called_live(client):
    advisory = client.post("/predict-road-condition", json=_with_source("seasonal-estimate")).json()["advisory"]
    assert "seasonal average" in advisory
    assert "live Open-Meteo" not in advisory


def test_live_weather_is_named_as_live(client):
    advisory = client.post("/predict-road-condition", json=_with_source("open-meteo")).json()["advisory"]
    assert "live Open-Meteo forecast" in advisory


def test_missing_source_is_reported_as_unknown(client):
    advisory = client.post("/predict-road-condition", json=_with_source(None)).json()["advisory"]
    assert "unknown" in advisory
    assert "live Open-Meteo" not in advisory


def test_counter_factors_are_returned_and_explained(client):
    body = client.post("/predict-road-condition", json=_with_source("open-meteo")).json()
    assert isinstance(body["counterFeatures"], list)
    assert len(body["counterFeatures"]) <= 2
    for item in body["counterFeatures"]:
        assert "less likely" in item["impact"]


def test_leave_early_and_confidence_wording_is_honest(client):
    body = client.post("/predict-road-condition", json=_with_source("open-meteo")).json()
    advisory = body["advisory"]
    if body["leaveEarlyMinutes"] > 0:
        assert "rough rule of thumb" in advisory
    assert "fairly confident" not in advisory


def test_model_with_wrong_checksum_is_refused(tmp_path, monkeypatch):
    import app.main as main

    model = tmp_path / "model.joblib"
    meta = tmp_path / "meta.json"
    shutil.copy2(main.MODEL_PATH, model)
    metadata = json.loads(main.METADATA_PATH.read_text(encoding="utf-8"))
    metadata["model_sha256"] = "0" * 64
    meta.write_text(json.dumps(metadata), encoding="utf-8")
    monkeypatch.setattr(main, "MODEL_PATH", model)
    monkeypatch.setattr(main, "METADATA_PATH", meta)

    with pytest.raises(RuntimeError, match="checksum"):
        main.ModelManager().startup()


def test_missing_model_is_not_trained_silently(tmp_path, monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "MODEL_PATH", tmp_path / "absent.joblib")
    monkeypatch.setattr(main, "METADATA_PATH", tmp_path / "absent.json")
    monkeypatch.delenv("ML_BOOTSTRAP_TRAINING", raising=False)

    with pytest.raises(RuntimeError, match="deliberate"):
        main.ModelManager().startup()
