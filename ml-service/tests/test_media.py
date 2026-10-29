"""Thumbnails and clips, on a small synthetic video. Needs OpenCV, so it is skipped without it."""
import pytest

cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

from app.events import Observation  # noqa: E402
from app.media import MediaSettings, render  # noqa: E402
from app.models import BoundingBox, EventSubmission  # noqa: E402

FPS = 30
SECONDS = 6


@pytest.fixture
def video(tmp_path):
    """A white square moving left to right over a grey background, 6 seconds at 30 fps."""
    path = tmp_path / "clip.mp4"
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), FPS, (320, 240))
    for i in range(FPS * SECONDS):
        frame = np.full((240, 320, 3), 90, np.uint8)
        x = 20 + i
        frame[100:160, x:x + 40] = 255
        writer.write(frame)
    writer.release()
    return path


def event(start_ms, end_ms, best_ms, track_id=1):
    return EventSubmission(eventType="OBJECT_DETECTED", objectLabel="person", trackId=track_id, confidence=0.9,
                           videoOffsetStartMs=start_ms, videoOffsetEndMs=end_ms,
                           bbox=BoundingBox(x=0.1, y=0.4, width=0.1, height=0.25), bestOffsetMs=best_ms)


def observations():
    return [Observation(1, "person", 0.9, i * 100, (20 + i * 3) / 320, 100 / 240, (60 + i * 3) / 320, 160 / 240)
            for i in range(SECONDS * 10)]


def frame_count(path):
    capture = cv2.VideoCapture(str(path))
    n = 0
    while capture.read()[0]:
        n += 1
    capture.release()
    return n


def test_writes_thumbnail_and_padded_clip(video, tmp_path):
    storage = tmp_path / "storage"
    [out] = render(video, [event(2000, 3000, 2500)], observations(), storage, (7, 42),
                   MediaSettings(padding_ms=1000, width=320, target_fps=15))

    assert out.thumbnailKey == "thumbnails/7/42/0000.jpg"
    assert out.clipKey.startswith("clips/7/42/0000.")
    thumb = cv2.imread(str(storage / out.thumbnailKey))
    assert thumb.shape[:2] == (240, 320)
    # 1 s padding each side: 1.0 s to 4.0 s at about 15 fps.
    assert 40 <= frame_count(storage / out.clipKey) <= 50


def test_long_events_are_capped(video, tmp_path):
    storage = tmp_path / "storage"
    [out] = render(video, [event(0, 6000, 100)], observations(), storage, (1, 1),
                   MediaSettings(padding_ms=0, max_clip_ms=2000, width=320, target_fps=15))
    assert frame_count(storage / out.clipKey) <= 32


def test_box_is_drawn_on_thumbnail(video, tmp_path):
    storage = tmp_path / "storage"
    [out] = render(video, [event(1000, 2000, 1500)], observations(), storage, (1, 1),
                   MediaSettings(width=320))
    thumb = cv2.imread(str(storage / out.thumbnailKey))
    # The box colour is amber; the synthetic video has only grey and white.
    b, g, r = thumb[..., 0].astype(int), thumb[..., 1].astype(int), thumb[..., 2].astype(int)
    assert ((r - b) > 100).sum() > 50


def test_no_partial_files_left(video, tmp_path):
    storage = tmp_path / "storage"
    render(video, [event(0, 1000, 500), event(3000, 4000, 3500, track_id=2)], observations(), storage, (1, 1))
    leftovers = [p for p in storage.rglob("*") if p.name.startswith(".")]
    assert leftovers == []


def test_bad_video_raises(tmp_path):
    bad = tmp_path / "bad.mp4"
    bad.write_bytes(b"nope")
    with pytest.raises(ValueError):
        render(bad, [event(0, 1000, 500)], [], tmp_path / "s", (1, 1))
