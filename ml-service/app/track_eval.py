"""Tracking quality on MOTChallenge sequences: MOTA, IDF1, identity switches.

Uses the standard ``motmetrics`` library for the scores. Ground truth follows the same rules as
``evaluation.py``: visible pedestrians are targets; detections that only cover ignore regions
(low-visibility people, static people, reflections and so on) are removed before scoring.

    python -m app.track_eval ../data/datasets/MOT17/train/MOT17-{02,04,09}-FRCNN --stride 3

``--stride`` matches the service's frame sampling, so the numbers describe tracking as it runs
in production. Needs requirements-ml.txt plus ``motmetrics``.
"""
import argparse
import time
from collections import defaultdict
from pathlib import Path

from .evaluation import IGNORE_CLASSES, PEDESTRIAN, iou, read_seqinfo

METRICS = ["num_frames", "num_unique_objects", "mota", "idf1", "num_switches", "mostly_tracked",
           "mostly_lost", "precision", "recall"]


def load_tracks(gt_file: Path, min_visibility: float = 0.25):
    """Per frame: target (id, box) pairs and ignore boxes. Boxes are x1, y1, x2, y2 in pixels."""
    targets = defaultdict(list)
    ignore = defaultdict(list)
    for line in gt_file.read_text().splitlines():
        if not line.strip():
            continue
        f, tid, x, y, w, h, consider, cls, vis = (float(v) for v in line.split(",")[:9])
        box = (x, y, x + w, y + h)
        if int(cls) == PEDESTRIAN and consider and vis >= min_visibility:
            targets[int(f)].append((int(tid), box))
        elif int(cls) == PEDESTRIAN or int(cls) in IGNORE_CLASSES:
            ignore[int(f)].append(box)
    return targets, ignore


def distance_matrix(gt_boxes, hyp_boxes, threshold: float = 0.5):
    """1 - IoU where IoU is at least the threshold, NaN (no match allowed) elsewhere."""
    import numpy as np

    d = np.full((len(gt_boxes), len(hyp_boxes)), np.nan)
    for i, g in enumerate(gt_boxes):
        for j, h in enumerate(hyp_boxes):
            score = iou(g, h)
            if score >= threshold:
                d[i, j] = 1 - score
    return d


def drop_ignored(hyps, targets, ignore, threshold: float = 0.5):
    """Removes hypotheses that match an ignore region and no target."""
    kept = []
    for hid, box in hyps:
        on_target = any(iou(box, t) >= threshold for _, t in targets)
        on_ignore = any(iou(box, g) >= threshold for g in ignore)
        if on_target or not on_ignore:
            kept.append((hid, box))
    return kept


def evaluate_sequence(seq_dir: Path, stride: int = 3, conf: float = 0.4, imgsz: int = 640):
    import motmetrics as mm
    from .detector import TRACKER, load_model

    info = read_seqinfo(seq_dir)
    targets, ignore = load_tracks(seq_dir / "gt" / "gt.txt")
    images = sorted((seq_dir / info.get("imdir", "img1")).glob("*" + info.get("imext", ".jpg")))
    model = load_model()  # fresh model: tracker state must not carry over between sequences
    acc = mm.MOTAccumulator(auto_id=True)
    for index in range(0, len(images), stride):
        frame = index + 1  # MOT frames are 1-based
        result = model.track(str(images[index]), persist=True, tracker=TRACKER, conf=conf, classes=[0],
                             imgsz=imgsz, verbose=False)[0]
        hyps = []
        if result.boxes is not None and result.boxes.id is not None:
            hyps = [(int(i), tuple(map(float, b))) for i, b in zip(result.boxes.id.tolist(), result.boxes.xyxy.tolist())]
        gt = targets.get(frame, [])
        hyps = drop_ignored(hyps, gt, ignore.get(frame, []))
        acc.update([t for t, _ in gt], [h for h, _ in hyps], distance_matrix([b for _, b in gt], [b for _, b in hyps]))
    return acc


def main(argv: list[str] | None = None) -> int:
    import motmetrics as mm

    p = argparse.ArgumentParser(prog="python -m app.track_eval", description="Tracking quality on MOT17 sequences.")
    p.add_argument("sequences", nargs="+", type=Path)
    p.add_argument("--stride", type=int, default=3, help="track every Nth frame, as the service does (default 3)")
    p.add_argument("--conf", type=float, default=0.4)
    p.add_argument("--imgsz", type=int, default=640)
    args = p.parse_args(argv)

    started = time.perf_counter()
    accs = [evaluate_sequence(s, args.stride, args.conf, args.imgsz) for s in args.sequences]
    summary = mm.metrics.create().compute_many(accs, metrics=METRICS, names=[s.name for s in args.sequences],
                                               generate_overall=len(accs) > 1)
    print(mm.io.render_summary(summary, formatters=mm.metrics.create().formatters,
                               namemap=mm.io.motchallenge_metric_names))
    print(f"stride {args.stride}, input {args.imgsz}px, confidence {args.conf}, {time.perf_counter() - started:.0f} s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
