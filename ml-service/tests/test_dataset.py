"""
Reproducibility of the training set.

The dataset that used to ship with this project could not be regenerated from the
committed code at all: it contained thousands of rows the committed generator was
arithmetically incapable of producing, so nobody could check what the model had
actually been trained on.

``generate_dataset`` is now a pure function of ``(sample_count, seed)`` - it uses
a seeded ``numpy.random.Generator`` and a fixed EPOCH date rather than
``datetime.now()`` - so anyone can rebuild the exact file and audit it. These
tests are what makes that claim checkable.

They use small sample counts and never write to disk, so they do not touch the
committed ``data/synthetic_denmark_routes.csv`` or the trained model.
"""

from __future__ import annotations

import pandas as pd
import pytest

from helpers import ML_SERVICE_ROOT  # noqa: F401  (ensures the app package is importable)

from app.dataset import EPOCH, generate_dataset
from app.features import CATEGORICAL_COLUMNS, FEATURE_COLUMNS, LABELS, NUMERIC_COLUMNS


@pytest.fixture(scope="module")
def frame_a() -> pd.DataFrame:
    return generate_dataset(200, seed=7)


@pytest.fixture(scope="module")
def frame_b() -> pd.DataFrame:
    return generate_dataset(200, seed=7)


def test_same_seed_gives_an_identical_frame(frame_a, frame_b):
    pd.testing.assert_frame_equal(frame_a, frame_b)


def test_same_seed_gives_identical_bytes_when_serialised(frame_a, frame_b):
    """The stronger claim: regenerating the CSV produces the same file."""
    assert frame_a.to_csv(index=False) == frame_b.to_csv(index=False)


def test_a_different_seed_gives_a_different_frame(frame_a):
    """Determinism must come from the seed, not from the generator being constant."""
    other = generate_dataset(200, seed=8)
    assert other.shape == frame_a.shape
    assert not other.equals(frame_a)


def test_row_count_and_column_contract(frame_a):
    assert len(frame_a) == 200
    assert list(frame_a.columns) == FEATURE_COLUMNS + ["road_condition_label"]


def test_labels_are_drawn_only_from_the_five_grades(frame_a):
    assert set(frame_a["road_condition_label"]) <= set(LABELS)


def test_a_larger_sample_still_reproduces_and_covers_all_five_grades():
    """
    Also the check that the generator can produce every grade at all - a training
    set missing a class is how the old model ended up never predicting one.
    """
    first = generate_dataset(600, seed=11)
    second = generate_dataset(600, seed=11)
    pd.testing.assert_frame_equal(first, second)
    assert set(first["road_condition_label"]) == set(LABELS)


def test_no_missing_values(frame_a):
    assert not frame_a.isna().any().any()


def test_numeric_columns_are_numeric_and_categoricals_are_strings(frame_a):
    for column in NUMERIC_COLUMNS:
        assert pd.api.types.is_numeric_dtype(frame_a[column]), column
    for column in CATEGORICAL_COLUMNS:
        assert frame_a[column].map(type).eq(str).all(), column


def test_generation_does_not_depend_on_the_wall_clock():
    """
    A fixed EPOCH is what removes ``datetime.now()`` from the generator. If it
    ever crept back, two runs on either side of midnight would diverge - which is
    exactly the failure that made the old dataset unreproducible.
    """
    assert EPOCH.year == 2025 and (EPOCH.month, EPOCH.day) == (1, 1)
    assert generate_dataset(50, seed=3).equals(generate_dataset(50, seed=3))


def test_prefix_property_holds_for_a_growing_sample():
    """
    Rows are drawn one after another from a single seeded stream, so the first 50
    rows of a 120-row run must equal a 50-row run with the same seed. That is a
    much tighter statement than "the two runs look similar".
    """
    small = generate_dataset(50, seed=5).reset_index(drop=True)
    large = generate_dataset(120, seed=5).head(50).reset_index(drop=True)
    pd.testing.assert_frame_equal(small, large)
