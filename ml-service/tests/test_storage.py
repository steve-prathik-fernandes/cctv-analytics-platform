from pathlib import Path

import pytest

from app import storage

boto3 = pytest.importorskip("boto3")
moto = pytest.importorskip("moto")

BUCKET = "test-footage"


@pytest.fixture
def s3(monkeypatch):
    for name, value in {"AWS_ACCESS_KEY_ID": "test", "AWS_SECRET_ACCESS_KEY": "test",
                        "AWS_DEFAULT_REGION": "us-east-1"}.items():
        monkeypatch.setenv(name, value)
    with moto.mock_aws():
        client = boto3.client("s3", region_name="us-east-1")
        client.create_bucket(Bucket=BUCKET)
        yield client


# --- Local folder ---

def test_local_reads_video_in_place(tmp_path):
    video = tmp_path / "videos" / "1" / "a.mp4"
    video.parent.mkdir(parents=True)
    video.write_bytes(b"x")
    store = storage.LocalStorage(tmp_path)
    with store.video("videos/1/a.mp4") as path:
        assert path == video.resolve()
    with store.media_dir() as root:
        assert root == tmp_path.resolve()


@pytest.mark.parametrize("key", ["../outside.mp4", "videos/../../outside.mp4", "/etc/passwd"])
def test_local_refuses_keys_outside_the_folder(tmp_path, key):
    with pytest.raises(ValueError):
        storage.LocalStorage(tmp_path).check_key(key)


def test_local_missing_video(tmp_path):
    with pytest.raises(FileNotFoundError):
        with storage.LocalStorage(tmp_path).video("videos/1/missing.mp4"):
            pass


# --- S3 ---

def test_s3_downloads_video_and_removes_the_copy(s3):
    s3.put_object(Bucket=BUCKET, Key="videos/1/2026/10/a.mp4", Body=b"video-bytes")
    store = storage.S3Storage(BUCKET, client=s3)
    with store.video("videos/1/2026/10/a.mp4") as path:
        assert path.read_bytes() == b"video-bytes"
        copy = path
    assert not copy.exists()


def test_s3_missing_video(s3):
    with pytest.raises(FileNotFoundError):
        with storage.S3Storage(BUCKET, client=s3).video("videos/1/missing.mp4"):
            pass


def test_s3_publish_uploads_encrypted_with_content_type(s3):
    store = storage.S3Storage(BUCKET, client=s3)
    with store.media_dir() as root:
        for key in ("clips/7/42/0000.mp4", "thumbnails/7/42/0000.jpg"):
            (root / key).parent.mkdir(parents=True, exist_ok=True)
            (root / key).write_bytes(b"media")
        store.publish(root, ["clips/7/42/0000.mp4", "thumbnails/7/42/0000.jpg"])
        rendered = root
    assert not rendered.exists()

    clip = s3.head_object(Bucket=BUCKET, Key="clips/7/42/0000.mp4")
    assert clip["ServerSideEncryption"] == "AES256"
    assert clip["ContentType"] == "video/mp4"
    assert s3.head_object(Bucket=BUCKET, Key="thumbnails/7/42/0000.jpg")["ContentType"] == "image/jpeg"


def test_s3_publish_uses_kms_key_when_set(s3):
    store = storage.S3Storage(BUCKET, kms_key_id="alias/cctv-media", client=s3)
    with store.media_dir() as root:
        (root / "clips").mkdir()
        (root / "clips" / "0000.mp4").write_bytes(b"media")
        store.publish(root, ["clips/0000.mp4"])
    head = s3.head_object(Bucket=BUCKET, Key="clips/0000.mp4")
    assert head["ServerSideEncryption"] == "aws:kms"


def test_s3_refuses_unsafe_keys(s3):
    store = storage.S3Storage(BUCKET, client=s3)
    with pytest.raises(ValueError):
        store.check_key("../videos/a.mp4")
    with pytest.raises(ValueError):
        store.publish(Path("/tmp"), ["/etc/passwd"])


def test_from_env_selects_backend(monkeypatch, tmp_path, s3):
    monkeypatch.setenv("STORAGE_DIR", str(tmp_path))
    monkeypatch.delenv("STORAGE_TYPE", raising=False)
    assert isinstance(storage.from_env(), storage.LocalStorage)

    monkeypatch.setenv("STORAGE_TYPE", "s3")
    monkeypatch.setenv("S3_BUCKET", BUCKET)
    assert isinstance(storage.from_env(), storage.S3Storage)

    monkeypatch.delenv("S3_BUCKET")
    with pytest.raises(RuntimeError):
        storage.from_env()


def test_s3_job_end_to_end(monkeypatch, s3):
    """A job reads the video from S3, its clips end up in S3, and their keys go to the API."""
    from types import SimpleNamespace

    from app import jobs, media
    from app.models import EventSubmission, JobRequest

    s3.put_object(Bucket=BUCKET, Key="videos/7/a.mp4", Body=b"video")
    monkeypatch.setattr(storage, "from_env", lambda: storage.S3Storage(BUCKET, client=s3))

    seen = {}

    def fake_track(path, *args):
        seen["video"] = path.read_bytes()
        return SimpleNamespace(observations=[])

    def fake_render(video, events, observations, root, prefix, settings):
        clip = root / "clips" / "7" / "42" / "0000.mp4"
        clip.parent.mkdir(parents=True)
        clip.write_bytes(b"clip")
        return [e.model_copy(update={"clipKey": "clips/7/42/0000.mp4"}) for e in events]

    monkeypatch.setattr(jobs.detector, "track_video", fake_track)
    monkeypatch.setattr(media, "render", fake_render)
    monkeypatch.setattr(jobs, "build_events", lambda obs, job: [EventSubmission(
        eventType="OBJECT_DETECTED", objectLabel="person", trackId=1, confidence=0.9,
        videoOffsetStartMs=0, videoOffsetEndMs=1000, bbox={"x": 0.1, "y": 0.1, "width": 0.2, "height": 0.4})])

    sent = {"status": []}

    class FakeApi:
        def status(self, job_id, status, error=None):
            sent["status"].append(status)

        def events(self, job_id, events):
            sent["events"] = events

    monkeypatch.setattr(jobs, "ApiClient", FakeApi)

    jobs.run(JobRequest(jobId=42, cameraId=7, videoKey="videos/7/a.mp4",
                        recordedStartAt="2026-10-04T21:30:00Z", zones=[], rules=[]))

    assert sent["status"] == ["RUNNING"]
    assert seen["video"] == b"video"
    assert sent["events"][0]["clipKey"] == "clips/7/42/0000.mp4"
    assert s3.get_object(Bucket=BUCKET, Key="clips/7/42/0000.mp4")["Body"].read() == b"clip"
