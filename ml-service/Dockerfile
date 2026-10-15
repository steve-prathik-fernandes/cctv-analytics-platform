FROM python:3.12-slim
WORKDIR /srv

# OpenCV (pulled in by Ultralytics) needs these system libraries.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# CPU-only PyTorch: the default Linux wheels bundle CUDA libraries a CPU server cannot use.
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch torchvision

COPY requirements-ml.txt requirements.txt ./
RUN pip install --no-cache-dir -r requirements-ml.txt

# Never install packages at runtime, and bake the model weights into the image.
ENV YOLO_AUTOINSTALL=false \
    YOLO_CONFIG_DIR=/tmp/Ultralytics \
    YOLO_MODEL=/srv/models/yolov8n.pt
RUN mkdir -p /srv/models \
    && python -c "from ultralytics import YOLO; YOLO('/srv/models/yolov8n.pt')"

COPY app ./app
RUN useradd --system --uid 10001 detector && chown -R detector /srv
USER detector
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
