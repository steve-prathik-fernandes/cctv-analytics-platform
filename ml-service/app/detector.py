"""Runs YOLOv8 detection with ByteTrack tracking over a video file.

Needs the heavy dependencies in requirements-ml.txt (Ultralytics, PyTorch, OpenCV). They are
imported lazily so the rest of the service, and its tests, work without them.
"""
import logging
import os
import time
from dataclasses import dataclass
from pathlib import Path

from .events import Observation

log = logging.getLogger(__name__)

# COCO class ids we care about for this project.
TARGET_CLASSES = {0: "person", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}

MODEL_NAME = os.environ.get("YOLO_MODEL", "yolov8n.pt")
TRACKER = os.environ.get("TRACKER", "bytetrack.yaml")  # botsort.yaml is the fallback (decision D2)
# Model input size. 1280 finds more small, distant people (MOT17 recall 0.43 -> 0.60) at about
# 2.7x the CPU time; see docs/06-detection-baseline.md.
IMG_SIZE = int(os.environ.get("YOLO_IMGSZ", "640"))


class DependencyMissing(RuntimeError):
    pass


@dataclass
class TrackingResult:
    observations: list[Observation]
    frames_read: int
    frames_processed: int
    fps: float
    width: int
    height: int
    duration_s: float
    elapsed_s: float


def load_model():
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise DependencyMissing(
            "ultralytics is not installed. Run: pip install -r requirements-ml.txt") from exc
    return YOLO(MODEL_NAME)  # downloaded on first use


def track_video(path: Path, frame_stride: int = 3, min_confidence: float = 0.4) -> TrackingResult:
    """Detects and tracks people and vehicles in every ``frame_stride``-th frame."""
    try:
        import cv2
    except ImportError as exc:
        raise DependencyMissing("opencv is not installed. Run: pip install -r requirements-ml.txt") from exc

    # A fresh model per video: the tracker keeps state between calls, and track ids must
    # not carry over from one video to the next.
    model = load_model()

    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise ValueError(f"could not open video {path.name}")

    fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
    width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
    if fps <= 0 or width <= 0 or height <= 0:
        capture.release()
        raise ValueError(f"{path.name} has no readable video stream")

    observations: list[Observation] = []
    frame_index = processed = 0
    started = time.perf_counter()
    try:
        while True:
            ok = capture.grab()
            if not ok:
                break
            if frame_index % frame_stride == 0:
                ok, frame = capture.retrieve()
                if not ok:
                    break
                offset_ms = round(frame_index * 1000 / fps)
                result = model.track(frame, persist=True, tracker=TRACKER, conf=min_confidence,
                                     classes=list(TARGET_CLASSES), imgsz=IMG_SIZE,
                                     verbose=False)[0]
                observations.extend(_observations(result, offset_ms))
                processed += 1
            frame_index += 1
    finally:
        capture.release()

    elapsed = time.perf_counter() - started
    log.info("Tracked %s: %d of %d frames in %.1fs", path.name, processed, frame_index, elapsed)
    return TrackingResult(observations, frame_index, processed, fps, width, height,
                          frame_index / fps, elapsed)


def _observations(result, offset_ms: int) -> list[Observation]:
    boxes = result.boxes
    if boxes is None or boxes.id is None:  # nothing tracked in this frame
        return []
    out = []
    for track_id, cls, conf, xyxyn in zip(boxes.id.tolist(), boxes.cls.tolist(), boxes.conf.tolist(),
                                          boxes.xyxyn.tolist()):
        label = TARGET_CLASSES.get(int(cls))
        if label is None:
            continue
        x1, y1, x2, y2 = (min(max(v, 0.0), 1.0) for v in xyxyn)
        out.append(Observation(int(track_id), label, float(conf), offset_ms, x1, y1, x2, y2))
    return out
