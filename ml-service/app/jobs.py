"""Background processing of jobs submitted by the API (POST /jobs).

Flow (docs/03-architecture.md, section 3): report RUNNING, read the video from storage, detect
and track, build events, write clips and thumbnails to storage, then post the events back to the
API, which marks the job DONE. Any failure is
reported as FAILED with a short reason.
"""
import logging
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

from . import detector, media, storage
from .events import build_events
from .models import JobRequest

log = logging.getLogger(__name__)

# One job at a time: detection is CPU-bound and the service runs on a small instance.
_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="job")


class Settings:
    """Read from the environment each time, so tests can change them."""

    @property
    def service_token(self) -> str | None:
        return os.environ.get("SERVICE_TOKEN") or None

    @property
    def api_base_url(self) -> str:
        return os.environ.get("API_BASE_URL", "http://localhost:8080").rstrip("/")


settings = Settings()


def submit(job: JobRequest) -> None:
    _executor.submit(run, job)


def run(job: JobRequest) -> None:
    client = ApiClient()
    try:
        client.status(job.jobId, "RUNNING")
        store = storage.from_env()
        with store.video(job.videoKey) as path:
            result = detector.track_video(path, job.frameStride, job.minConfidence)
            events = build_events(result.observations, job)
            events = _with_media(job, store, path, events, result.observations)
        client.events(job.jobId, [e.model_dump(mode="json") for e in events])
        log.info("Job %d done: %d events", job.jobId, len(events))
    except Exception as exc:  # report every failure back, never leave a job RUNNING
        log.exception("Job %d failed", job.jobId)
        try:
            client.status(job.jobId, "FAILED", _reason(exc))
        except Exception:
            log.exception("Could not report failure of job %d", job.jobId)


def _with_media(job: JobRequest, store, path: Path, events, observations):
    """Adds thumbnails and clips. A media failure is logged, never fails the job: the events
    are still worth having without their clips."""
    if os.environ.get("MEDIA", "on").lower() == "off":
        return events
    try:
        with store.media_dir() as root:
            with_media = media.render(path, events, observations, root, (job.cameraId, job.jobId),
                                      media.MediaSettings.from_env())
            store.publish(root, [k for e in with_media for k in (e.clipKey, e.thumbnailKey) if k])
        return with_media
    except Exception:
        log.exception("Media for job %d failed; sending events without clips", job.jobId)
        return events


def _reason(exc: Exception) -> str:
    # Short, user-facing reason. No stack traces or file system paths.
    if isinstance(exc, (FileNotFoundError, ValueError, detector.DependencyMissing)):
        return str(exc)[:500]
    return f"processing error ({type(exc).__name__})"


class ApiClient:
    """Calls the API's /internal endpoints with the shared service token."""

    def __init__(self, transport: httpx.BaseTransport | None = None):
        token = settings.service_token
        if not token:
            raise RuntimeError("SERVICE_TOKEN is not set")
        self._http = httpx.Client(base_url=settings.api_base_url, timeout=30,
                                  headers={"X-Service-Token": token}, transport=transport)

    def status(self, job_id: int, status: str, error: str | None = None) -> None:
        body = {"status": status} | ({"error": error} if error else {})
        self._http.post(f"/internal/jobs/{job_id}/status", json=body).raise_for_status()

    def events(self, job_id: int, events: list[dict]) -> None:
        self._http.post(f"/internal/jobs/{job_id}/events", json={"events": events}).raise_for_status()
