# Smart CCTV Analytics Platform

Capstone project. Detects and tracks people and vehicles in camera footage, turns detections into events, and gives operators a searchable event history.

| Part | Tech | Port |
|---|---|---|
| `backend/` | Spring Boot 3.3, Java 21, JPA, PostgreSQL | 8080 |
| `ml-service/` | FastAPI, YOLOv8 (ultralytics) | 8000 |
| `frontend/` | React, TypeScript, Vite | 5173 |
| `docker-compose.yml` | PostgreSQL 16, backend, ml-service | 5432 |

Project charter and plan are in `docs/`.

## Run locally (no Docker)

Prerequisites: JDK 21, Maven 3.9+, Node 22+, Python 3.12+.

**Backend** (in-memory H2 database, no Postgres needed):

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# http://localhost:8080/actuator/health
# http://localhost:8080/api/cameras
```

**Frontend:**

```bash
cd frontend
npm install
npm run dev
# http://localhost:5173  (proxies /api to the backend)
```

**ML service:**

```bash
cd ml-service
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt  # lightweight; enough for /health
uvicorn app.main:app --reload --port 8000
# http://localhost:8000/health
```

For real detection, install the heavy dependencies instead: `pip install -r requirements-ml.txt` (pulls in PyTorch, several GB). The first `/detect` call downloads `yolov8n.pt`.

## Run with Docker

```bash
docker compose up --build
```

## Project layout

```
backend/        Spring Boot API (cameras now; events, auth, RBAC, audit log next)
ml-service/     Detection service (YOLOv8; tracking and event rules next)
frontend/       Operator dashboard
docs/           Charter and project plan
.github/        CI: builds backend, frontend, and imports the ML service on every push
```

## Status

Week 1 scaffold: camera list/create endpoint, health checks, CI, container setup.
Next: requirements doc, ER diagram, API contract (see the Trello board).
