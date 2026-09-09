"""
Shared fixtures for the ML service test suite.

Two things matter for speed and safety here:

* **The model is loaded exactly once.** ``app.main`` does not train on import - the
  loading (or, if there were no artefact, the training) happens in the FastAPI
  ``lifespan`` hook, which only runs when ``TestClient`` is entered as a context
  manager. The client fixture is therefore session-scoped, so the joblib artefact
  and the 12,000-row baseline CSV are read once for the whole run.

* **Nothing here touches the committed artefacts.** No test calls ``/retrain``,
  and no test writes to ``ml-service/data`` or ``ml-service/models``. The suite
  reads the committed dataset to check feature ranges, and that is all.

There is also no network access anywhere in this suite: ``TestClient`` speaks to
the ASGI app in-process, and the service itself makes no outbound calls.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

# The service package lives one level up from ``tests/``. Adding it explicitly
# means the suite runs the same way whatever directory pytest is invoked from.
ML_SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(ML_SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(ML_SERVICE_ROOT))


@pytest.fixture(scope="session")
def client():
    """A TestClient with the lifespan run, so the model is actually loaded."""
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture(scope="session")
def training_frame():
    """The committed training set, read once. Read-only."""
    import pandas as pd

    from app.main import DATASET_PATH

    assert DATASET_PATH.exists(), (
        f"The committed training set is missing at {DATASET_PATH}. "
        "The suite reads it to check train/serve consistency; it never creates it."
    )
    return pd.read_csv(DATASET_PATH)
