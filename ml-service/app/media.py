"""Thumbnails and short clips for events (docs/03-architecture.md, section 5, step 6).

One extra pass over the video writes, for every event, a JPEG of its highest-confidence frame
and a short clip around it, both with the tracked object's box drawn. Clips are H.264 MP4 where
the platform's OpenCV can encode it, otherwise VP8 WebM; browsers play both.

Needs OpenCV (requirements-ml.txt); imported lazily like the detector.
"""
import bisect
import logging
import os
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from .events import Observation
from .models import EventSubmission

log = logging.getLogger(__name__)

BOX_COLOR = (61, 163, 232)  # BGR amber, readable on most footage


@dataclass(frozen=True)
class MediaSettings:
    padding_ms: int = 2000      # footage kept before and after each event
    max_clip_ms: int = 30_000   # long events (loitering) are cut to this length from their start
    width: int = 640            # output width; height keeps the aspect ratio
    target_fps: float = 15.0    # clips keep about this many frames per second
    jpeg_quality: int = 85

    @classmethod
    def from_env(cls) -> "MediaSettings":
        return cls(width=int(os.environ.get("CLIP_WIDTH", "640")),
                   max_clip_ms=int(float(os.environ.get("CLIP_MAX_SECONDS", "30")) * 1000))


@dataclass
class _Plan:
    index: int
    event: EventSubmission
    start_ms: int
    end_ms: int
    thumb_ms: int
    clip_path: Path
    thumb_path: Path
    writer: object = None
    thumb_done: bool = False


def render(video: Path, events: list[EventSubmission], observations: list[Observation],
           storage_root: Path, key_prefix: tuple[int, int],
           settings: MediaSettings = MediaSettings()) -> list[EventSubmission]:
    """Writes media under ``storage_root`` and returns the events with their keys set.

    ``key_prefix`` is (cameraId, jobId); keys look like ``clips/7/42/0003.mp4``. An event whose
    media could not be written keeps no key, so the API simply shows no clip for it.
    """
    import cv2

    if not events:
        return events
    capture = cv2.VideoCapture(str(video))
    if not capture.isOpened():
        raise ValueError(f"could not open video {video.name}")
    fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
    src_w = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    src_h = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
    if fps <= 0 or src_w <= 0 or src_h <= 0:
        capture.release()
        raise ValueError(f"{video.name} has no readable video stream")

    out_w = min(settings.width, src_w)
    out_h = max(2, round(src_h * out_w / src_w / 2) * 2)  # even sizes keep encoders happy
    step = max(1, round(fps / settings.target_fps))
    clip_fps = fps / step
    fourcc, ext = _pick_codec(cv2, storage_root, clip_fps, (out_w, out_h))

    camera_id, job_id = key_prefix
    folder = f"{camera_id}/{job_id}"
    plans = []
    for i, e in enumerate(events):
        start = max(0, e.videoOffsetStartMs - settings.padding_ms)
        end = min(e.videoOffsetEndMs + settings.padding_ms, start + settings.max_clip_ms)
        plans.append(_Plan(i, e, start, end, e.bestOffsetMs if e.bestOffsetMs is not None else e.videoOffsetStartMs,
                           storage_root / "clips" / folder / f"{i:04d}{ext}",
                           storage_root / "thumbnails" / folder / f"{i:04d}.jpg"))
    for p in plans:
        p.clip_path.parent.mkdir(parents=True, exist_ok=True)
        p.thumb_path.parent.mkdir(parents=True, exist_ok=True)

    boxes = _BoxLookup(observations, max_gap_ms=round(1000 / fps * 4) + 50)
    frame_index = 0
    try:
        while True:
            ok = capture.grab()
            if not ok:
                break
            t = round(frame_index * 1000 / fps)
            wanted = [p for p in plans if (p.start_ms <= t <= p.end_ms and frame_index % step == 0)
                      or (not p.thumb_done and t >= p.thumb_ms)]
            if wanted:
                ok, frame = capture.retrieve()
                if not ok:
                    break
                small = cv2.resize(frame, (out_w, out_h), interpolation=cv2.INTER_AREA)
                for p in wanted:
                    box = boxes.at(p.event.trackId, t)
                    if not p.thumb_done and t >= p.thumb_ms:
                        _write_jpeg(cv2, p.thumb_path, _draw(cv2, small.copy(), box), settings.jpeg_quality)
                        p.thumb_done = True
                    if p.start_ms <= t <= p.end_ms and frame_index % step == 0:
                        if p.writer is None:
                            p.writer = cv2.VideoWriter(str(_partial(p.clip_path)), cv2.VideoWriter_fourcc(*fourcc),
                                                       clip_fps, (out_w, out_h))
                        p.writer.write(_draw(cv2, small.copy(), box))
            for p in plans:  # close clips as soon as they are complete
                if p.writer is not None and t > p.end_ms:
                    _finish(p)
            frame_index += 1
    finally:
        capture.release()
        for p in plans:
            if p.writer is not None:
                _finish(p)

    keyed = []
    for p in plans:
        update = {}
        if p.clip_path.is_file():
            update["clipKey"] = p.clip_path.relative_to(storage_root).as_posix()
        if p.thumb_path.is_file():
            update["thumbnailKey"] = p.thumb_path.relative_to(storage_root).as_posix()
        keyed.append(p.event.model_copy(update=update))
    log.info("Wrote media for %d events (%s clips, %dx%d at %.1f fps)", len(plans), ext, out_w, out_h, clip_fps)
    return keyed


class _BoxLookup:
    """The tracked box nearest before a time, for drawing on frames between sampled ones."""

    def __init__(self, observations: list[Observation], max_gap_ms: int):
        self.max_gap_ms = max_gap_ms
        self.by_track: dict[int, list[Observation]] = defaultdict(list)
        for o in observations:
            self.by_track[o.track_id].append(o)
        self.times: dict[int, list[int]] = {}
        for track_id, obs in self.by_track.items():
            obs.sort(key=lambda o: o.offset_ms)
            self.times[track_id] = [o.offset_ms for o in obs]

    def at(self, track_id: int, t: int) -> Observation | None:
        times = self.times.get(track_id)
        if not times:
            return None
        i = bisect.bisect_right(times, t) - 1
        if i < 0:
            return None
        obs = self.by_track[track_id][i]
        return obs if t - obs.offset_ms <= self.max_gap_ms else None


def _draw(cv2, frame, box: Observation | None):
    if box is not None:
        h, w = frame.shape[:2]
        p1 = (round(box.x1 * w), round(box.y1 * h))
        p2 = (round(box.x2 * w), round(box.y2 * h))
        cv2.rectangle(frame, p1, p2, BOX_COLOR, 2)
    return frame


def _pick_codec(cv2, root: Path, fps: float, size: tuple[int, int]) -> tuple[str, str]:
    """H.264 MP4 if this OpenCV build can write it, else VP8 WebM. Probed with a real file."""
    import numpy as np

    root.mkdir(parents=True, exist_ok=True)
    for fourcc, ext in (("avc1", ".mp4"), ("VP80", ".webm")):
        probe = root / f".codec-probe{ext}"
        try:
            writer = cv2.VideoWriter(str(probe), cv2.VideoWriter_fourcc(*fourcc), fps, size)
            if writer.isOpened():
                for _ in range(3):
                    writer.write(np.zeros((size[1], size[0], 3), np.uint8))
                writer.release()
                if probe.is_file() and probe.stat().st_size > 0:
                    return fourcc, ext
            writer.release()
        finally:
            probe.unlink(missing_ok=True)
    raise RuntimeError("OpenCV cannot encode H.264 or VP8 video on this machine")


def _partial(path: Path) -> Path:
    # Keeps the real extension last so OpenCV picks the right container.
    return path.with_name(".part-" + path.name)


def _finish(p: _Plan) -> None:
    p.writer.release()
    p.writer = None
    partial = _partial(p.clip_path)
    if partial.is_file() and partial.stat().st_size > 0:
        os.replace(partial, p.clip_path)
    else:
        partial.unlink(missing_ok=True)


def _write_jpeg(cv2, path: Path, frame, quality: int) -> None:
    partial = path.with_name(".part-" + path.name)
    if cv2.imwrite(str(partial), frame, [cv2.IMWRITE_JPEG_QUALITY, quality]):
        os.replace(partial, path)
