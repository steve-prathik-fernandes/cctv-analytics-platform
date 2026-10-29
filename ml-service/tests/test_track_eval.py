"""Helpers for the tracking evaluation. Needs NumPy, so it is skipped without the ML dependencies."""
import math

import pytest

pytest.importorskip("numpy")

from app.track_eval import distance_matrix, drop_ignored, load_tracks  # noqa: E402

A = (0, 0, 10, 10)
B = (100, 100, 110, 110)


def test_distance_matrix_allows_only_overlapping_pairs():
    d = distance_matrix([A, B], [A, (1, 0, 11, 10)])
    assert d[0, 0] == 0
    assert math.isnan(d[1, 0]) and math.isnan(d[1, 1])
    assert 0 < d[0, 1] < 0.5


def test_hypothesis_on_ignore_region_is_dropped_unless_it_matches_a_target():
    hyps = [(1, A), (2, B)]
    assert drop_ignored(hyps, targets=[], ignore=[A]) == [(2, B)]
    assert drop_ignored(hyps, targets=[(7, A)], ignore=[A]) == hyps


def test_load_tracks_keeps_ids(tmp_path):
    gt = tmp_path / "gt.txt"
    gt.write_text("1,5,0,0,10,10,1,1,0.9\n1,6,50,50,10,10,1,7,1.0\n2,5,1,0,10,10,1,1,0.9\n")
    targets, ignore = load_tracks(gt)
    assert targets[1] == [(5, (0.0, 0.0, 10.0, 10.0))]
    assert targets[2][0][0] == 5
    assert ignore[1] == [(50.0, 50.0, 60.0, 60.0)]
