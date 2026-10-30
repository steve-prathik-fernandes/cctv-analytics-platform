"""Detection service. Wraps YOLOv8 and ByteTrack so the Java backend can process footage over HTTP.

The ultralytics import is lazy so the service starts (and /health works) even before
the heavy ML dependencies are installed. Install them with requirements-ml.txt.

Endpoints:
- GET /health: liveness check.
- POST /jobs: process a stored video in the background (called by the API, needs the service token).
- POST /detect: run detection on a single image (development aid).
"""
import hmac
import io
import logging

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile, status

from . import jobs, storage
from .detector import TARGET_CLASSES, DependencyMissing, load_model
from .models import JobRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="CCTV detection service", version="0.2.0")

_model = None


def get_model():
    global _model
    if _model is None:
        try:
            _model = load_model()
        except DependencyMissing as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc
    return _model


def require_service_token(x_service_token: str | None = Header(default=None)) -> None:
    expected = jobs.settings.service_token
    if expected is None:
        # Fail closed: without a configured token nobody may submit jobs.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "SERVICE_TOKEN is not configured")
    if x_service_token is None or not hmac.compare_digest(x_service_token, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "invalid service token")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/jobs", status_code=status.HTTP_202_ACCEPTED, dependencies=[Depends(require_service_token)])
def submit_job(job: JobRequest):
    """Accepts a job and processes it in the background. Results go to the API's /internal endpoints."""
    try:
        storage.check_key(job.videoKey)
    except ValueError as exc:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(exc)) from exc
    jobs.submit(job)
    return {"jobId": job.jobId, "status": "ACCEPTED"}


@app.post("/detect")
async def detect(file: UploadFile = File(...), conf: float = 0.4):
    """Run detection on a single image and return people/vehicle boxes."""
    from PIL import Image

    try:
        image = Image.open(io.BytesIO(await file.read())).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Could not read the uploaded image.") from exc

    result = get_model().predict(image, conf=conf, verbose=False)[0]
    detections = []
    for box in result.boxes:
        class_id = int(box.cls)
        if class_id not in TARGET_CLASSES:
            continue
        x1, y1, x2, y2 = (float(v) for v in box.xyxy[0])
        detections.append(
            {
                "label": TARGET_CLASSES[class_id],
                "confidence": round(float(box.conf), 3),
                "box": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
            }
        )
    return {"count": len(detections), "detections": detections}
