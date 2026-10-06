"""Detection service. Wraps YOLOv8 so the Java backend can request detections over HTTP.

The ultralytics import is lazy so the service starts (and /health works) even before
the heavy ML dependencies are installed. Install them with requirements-ml.txt.
"""
import io

from fastapi import FastAPI, File, HTTPException, UploadFile

app = FastAPI(title="CCTV detection service", version="0.1.0")

# COCO class ids we care about for this project.
TARGET_CLASSES = {0: "person", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}

_model = None


def get_model():
    global _model
    if _model is None:
        try:
            from ultralytics import YOLO
        except ImportError as exc:
            raise HTTPException(
                status_code=503,
                detail="ultralytics is not installed. Run: pip install -r requirements-ml.txt",
            ) from exc
        _model = YOLO("yolov8n.pt")  # downloaded on first use
    return _model


@app.get("/health")
def health():
    return {"status": "ok"}


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
