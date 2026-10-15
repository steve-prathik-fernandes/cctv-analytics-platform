"""Matching rules for the person precision measurement."""
from app.evaluation import GroundTruth, iou, load_ground_truth, match_frame

PERSON = (100, 100, 150, 250)


def test_iou():
    assert iou(PERSON, PERSON) == 1.0
    assert iou(PERSON, (200, 200, 250, 350)) == 0.0
    assert round(iou((0, 0, 10, 10), (5, 0, 15, 10)), 3) == 0.333


def test_true_and_false_positives_and_misses():
    gt = GroundTruth(targets=[PERSON, (300, 100, 350, 250)])
    counts = match_frame([(PERSON, 0.9), ((600, 100, 650, 250), 0.8)], gt)
    assert (counts.tp, counts.fp, counts.fn) == (1, 1, 1)


def test_duplicate_detection_is_a_false_positive():
    gt = GroundTruth(targets=[PERSON])
    counts = match_frame([(PERSON, 0.9), ((101, 101, 151, 251), 0.7)], gt)
    assert (counts.tp, counts.fp) == (1, 1)


def test_detection_on_ignore_region_is_not_counted():
    gt = GroundTruth(ignore=[PERSON])
    counts = match_frame([(PERSON, 0.9)], gt)
    assert (counts.tp, counts.fp, counts.ignored) == (0, 0, 1)


def test_higher_confidence_detection_gets_the_match():
    gt = GroundTruth(targets=[PERSON])
    loose = (105, 110, 155, 260)
    counts = match_frame([(loose, 0.5), (PERSON, 0.9)], gt)
    assert (counts.tp, counts.fp) == (1, 1)


def test_load_ground_truth_splits_targets_and_ignore(tmp_path):
    gt_file = tmp_path / "gt.txt"
    gt_file.write_text("\n".join([
        "1,1,100,100,50,150,1,1,0.9",   # visible pedestrian: target
        "1,2,300,100,50,150,1,1,0.1",   # barely visible pedestrian: ignore
        "1,3,500,100,50,150,0,1,1.0",   # not considered: ignore
        "1,4,700,100,50,150,1,7,1.0",   # static person: ignore
        "1,5,900,100,80,40,1,3,1.0",    # car: not part of person evaluation
    ]))
    frame = load_ground_truth(gt_file)[1]
    assert frame.targets == [(100, 100, 150, 250)]
    assert len(frame.ignore) == 3
