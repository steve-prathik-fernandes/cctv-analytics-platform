"""Person detection precision and recall against MOTChallenge ground truth.

Measures the charter objective (at least 80% precision for people, docs/01-requirements.md).

Matching rules, following the MOTChallenge conventions:
- Ground truth rows of class 1 (pedestrian) with the "consider" flag set are the targets.
- Pedestrians below ``min_visibility``, and classes 2 (person on vehicle), 7 (static person),
  8 (distractor) and 12 (reflection), are *ignore* regions: a detection on them counts as
  neither a true nor a false positive.
- A detection is a true positive if its IoU with an unmatched target is at least 0.5.
  Detections are matched in order of confidence, highest first.

The matching here is pure Python; ``evaluate_sequence`` runs the model.
"""
import argparse
import configparser
import time
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

Box = tuple[float, float, float, float]  # x1, y1, x2, y2 in pixels

PEDESTRIAN = 1
IGNORE_CLASSES = {2, 7, 8, 12}


@dataclass
class GroundTruth:
    targets: list[Box] = field(default_factory=list)
    ignore: list[Box] = field(default_factory=list)


@dataclass
class Counts:
    tp: int = 0
    fp: int = 0
    fn: int = 0
    ignored: int = 0

    def add(self, other: "Counts") -> None:
        self.tp += other.tp
        self.fp += other.fp
        self.fn += other.fn
        self.ignored += other.ignored

    @property
    def precision(self) -> float:
        return self.tp / (self.tp + self.fp) if self.tp + self.fp else 0.0

    @property
    def recall(self) -> float:
        return self.tp / (self.tp + self.fn) if self.tp + self.fn else 0.0


def iou(a: Box, b: Box) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def load_ground_truth(gt_file: Path, min_visibility: float = 0.25) -> dict[int, GroundTruth]:
    """Reads a MOTChallenge gt.txt: frame, id, x, y, w, h, consider, class, visibility."""
    frames: dict[int, GroundTruth] = defaultdict(GroundTruth)
    for line in gt_file.read_text().splitlines():
        if not line.strip():
            continue
        f, _, x, y, w, h, consider, cls, vis = (float(v) for v in line.split(",")[:9])
        box = (x, y, x + w, y + h)
        cls = int(cls)
        if cls == PEDESTRIAN and consider and vis >= min_visibility:
            frames[int(f)].targets.append(box)
        elif cls == PEDESTRIAN or cls in IGNORE_CLASSES:
            frames[int(f)].ignore.append(box)
    return frames


def match_frame(detections: list[tuple[Box, float]], gt: GroundTruth, threshold: float = 0.5) -> Counts:
    counts = Counts()
    matched = [False] * len(gt.targets)
    for box, _conf in sorted(detections, key=lambda d: -d[1]):
        best, best_iou = -1, threshold
        for i, target in enumerate(gt.targets):
            if not matched[i] and (score := iou(box, target)) >= best_iou:
                best, best_iou = i, score
        if best >= 0:
            matched[best] = True
            counts.tp += 1
        elif any(iou(box, ign) >= threshold for ign in gt.ignore):
            counts.ignored += 1
        else:
            counts.fp += 1
    counts.fn = matched.count(False)
    return counts


def read_seqinfo(seq_dir: Path) -> dict[str, str]:
    parser = configparser.ConfigParser()
    parser.read(seq_dir / "seqinfo.ini")
    return dict(parser["Sequence"])


def evaluate_sequence(seq_dir: Path, conf: float = 0.4, stride: int = 1, min_visibility: float = 0.25,
                      model_name: str | None = None, imgsz: int = 640) -> Counts:
    """Runs YOLO on every ``stride``-th frame of a MOT sequence and scores person detections."""
    from .detector import MODEL_NAME, load_model

    if model_name and model_name != MODEL_NAME:
        from ultralytics import YOLO
        model = YOLO(model_name)
    else:
        model = load_model()

    info = read_seqinfo(seq_dir)
    gt = load_ground_truth(seq_dir / "gt" / "gt.txt", min_visibility)
    images = sorted((seq_dir / info.get("imdir", "img1")).glob("*" + info.get("imext", ".jpg")))

    total = Counts()
    for index, image in enumerate(images[::stride]):
        frame = index * stride + 1  # MOT frames are 1-based
        result = model.predict(str(image), conf=conf, classes=[0], imgsz=imgsz, verbose=False)[0]
        detections = [((*map(float, xyxy),), float(c))
                      for xyxy, c in zip(result.boxes.xyxy.tolist(), result.boxes.conf.tolist())]
        total.add(match_frame(detections, gt.get(frame, GroundTruth())))
    return total


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m app.evaluation",
                                description="Person detection precision and recall on MOT17 sequences.")
    p.add_argument("sequences", nargs="+", type=Path, help="MOT sequence folders (with gt/ and img1/)")
    p.add_argument("--conf", type=float, default=0.4)
    p.add_argument("--stride", type=int, default=5, help="evaluate every Nth frame (default 5)")
    p.add_argument("--min-visibility", type=float, default=0.25)
    p.add_argument("--model", default=None, help="weights file, default from YOLO_MODEL")
    p.add_argument("--imgsz", type=int, default=640, help="model input size in pixels (default 640)")
    args = p.parse_args(argv)

    overall = Counts()
    started = time.perf_counter()
    print(f"{'sequence':<16}{'TP':>7}{'FP':>7}{'FN':>7}{'ignored':>9}{'precision':>11}{'recall':>8}")
    for seq in args.sequences:
        c = evaluate_sequence(seq, args.conf, args.stride, args.min_visibility, args.model, args.imgsz)
        overall.add(c)
        print(f"{seq.name:<16}{c.tp:>7}{c.fp:>7}{c.fn:>7}{c.ignored:>9}{c.precision:>11.3f}{c.recall:>8.3f}")
    if len(args.sequences) > 1:
        c = overall
        print(f"{'all':<16}{c.tp:>7}{c.fp:>7}{c.fn:>7}{c.ignored:>9}{c.precision:>11.3f}{c.recall:>8.3f}")
    print(f"Model input {args.imgsz}px, confidence {args.conf}, every {args.stride}th frame, "
          f"{time.perf_counter() - started:.0f} s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
