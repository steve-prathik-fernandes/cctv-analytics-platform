"""POST /jobs, request validation and the background job runner."""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app import detector, jobs
from app.events import Observation
from app.main import app

TOKEN = "test-service-token"


@pytest.fixture(autouse=True)
def env(monkeypatch, tmp_path):
    monkeypatch.setenv("SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("STORAGE_DIR", str(tmp_path))
    monkeypatch.setenv("API_BASE_URL", "http://api.test")
    return tmp_path


@pytest.fixture
def submitted(monkeypatch):
    calls = []
    monkeypatch.setattr(jobs, "submit", calls.append)
    return calls


def body(**overrides):
    base = {
        "jobId": 42, "cameraId": 7, "videoKey": "videos/7/a.mp4",
        "recordedStartAt": "2026-10-04T21:30:00Z", "frameStride": 3, "minConfidence": 0.4,
        "zones": [{"id": 3, "name": "Dock", "polygon": [[0.1, 0.5], [0.6, 0.5], [0.6, 0.95]]}],
        "rules": [{"id": 11, "type": "LOITERING", "zoneId": 3, "params": {"minSeconds": 30}}],
    }
    return base | overrides


client = TestClient(app)


def post(payload, token=TOKEN):
    headers = {"X-Service-Token": token} if token else {}
    return client.post("/jobs", json=payload, headers=headers)


# --- Endpoint ---

def test_accepts_contract_example(submitted):
    res = post(body())
    assert res.status_code == 202
    assert submitted[0].jobId == 42


def test_requires_service_token(submitted):
    assert post(body(), token=None).status_code == 401
    assert post(body(), token="wrong").status_code == 401
    assert submitted == []


def test_refuses_jobs_when_no_token_configured(monkeypatch, submitted):
    monkeypatch.delenv("SERVICE_TOKEN")
    assert post(body(), token="anything").status_code == 503


@pytest.mark.parametrize("key", ["../secret.mp4", "/etc/passwd", "videos/../../x.mp4", "a\\b.mp4"])
def test_rejects_keys_outside_storage(key, submitted):
    assert post(body(videoKey=key)).status_code in (400, 422)
    assert submitted == []


@pytest.mark.parametrize("change", [
    {"recordedStartAt": "2026-10-04T21:30:00"},                                  # no timezone
    {"frameStride": 0},
    {"zones": [{"id": 3, "name": "Bad", "polygon": [[0.1, 0.5], [1.5, 0.5], [0.6, 0.95]]}]},  # > 1
    {"zones": [{"id": 3, "name": "Line", "polygon": [[0.1, 0.5], [0.6, 0.5]]}]},  # < 3 points
    {"rules": [{"id": 1, "type": "LOITERING", "zoneId": 3, "params": {}}]},
    {"rules": [{"id": 1, "type": "LOITERING", "zoneId": 99, "params": {"minSeconds": 5}}]},
    {"rules": [{"id": 1, "type": "ZONE_ENTRY"}]},
    {"rules": [{"id": 1, "type": "AFTER_HOURS_VEHICLE", "params": {"start": "25:00", "end": "06:00"}}]},
    {"rules": [{"id": 1, "type": "AFTER_HOURS_VEHICLE",
                "params": {"start": "22:00", "end": "06:00", "timezone": "Mars/Base"}}]},
])
def test_rejects_invalid_jobs(change, submitted):
    assert post(body(**change)).status_code == 422
    assert submitted == []


# --- Runner ---

class FakeApi:
    """Records calls to the API's /internal endpoints."""

    def __init__(self, fail_events=False):
        self.calls = []
        self.fail_events = fail_events

    def handler(self, request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        self.calls.append((request.url.path, request.headers.get("X-Service-Token"), payload))
        if self.fail_events and request.url.path.endswith("/events"):
            return httpx.Response(500)
        return httpx.Response(204)


@pytest.fixture
def fake_api(monkeypatch):
    api = FakeApi()
    real = jobs.ApiClient.__init__
    monkeypatch.setattr(jobs.ApiClient, "__init__",
                        lambda self, transport=None: real(self, httpx.MockTransport(api.handler)))
    return api


def video(env):
    path = env / "videos" / "7" / "a.mp4"
    path.parent.mkdir(parents=True)
    path.write_bytes(b"not really a video")
    return path


def test_run_reports_running_then_events(env, fake_api, monkeypatch):
    video(env)
    # Bottom-center (0.5, 0.8) is inside the triangular zone from body().
    obs = [Observation(1, "person", 0.9, i * 1000, 0.45, 0.6, 0.55, 0.8) for i in range(31)]
    monkeypatch.setattr(detector, "track_video",
                        lambda path, stride, conf: detector.TrackingResult(obs, 93, 31, 30, 640, 480, 3.1, 1))

    jobs.run(jobs.JobRequest(**body()))

    (p1, t1, s1), (p2, t2, s2) = fake_api.calls
    assert (p1, s1) == ("/internal/jobs/42/status", {"status": "RUNNING"})
    assert p2 == "/internal/jobs/42/events"
    assert t1 == t2 == TOKEN
    assert sorted(e["eventType"] for e in s2["events"]) == ["LOITERING", "OBJECT_DETECTED"]


def test_run_reports_missing_video_as_failed(fake_api):
    jobs.run(jobs.JobRequest(**body()))
    assert fake_api.calls[-1][2] == {"status": "FAILED", "error": "video not found in storage"}


def test_run_hides_internal_error_details(env, fake_api, monkeypatch):
    video(env)

    def boom(*args):
        raise RuntimeError("/Users/someone/secret/path exploded")

    monkeypatch.setattr(detector, "track_video", boom)
    jobs.run(jobs.JobRequest(**body()))
    assert fake_api.calls[-1][2] == {"status": "FAILED", "error": "processing error (RuntimeError)"}
