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
```

The dev profile creates an admin account on startup: `admin@cctv.local` / `admin-dev-password`. These credentials exist only in the dev profile and in `docker-compose.yml`.

Outside the dev profile the backend needs these environment variables:

| Variable | Purpose |
|---|---|
| `JWT_SECRET` | Signing key for login tokens, at least 32 characters. The API will not start without it. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Creates the first admin when the database has no users. Ignored afterwards. |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection. |
| `SERVICE_TOKEN` | Shared secret between the backend and the detection service, at least 32 characters. Set the same value on both. |
| `DETECTION_URL` | Detection service address (default `http://localhost:8000`). |
| `STORAGE_DIR` | Folder for uploaded footage (default `../data/storage`). The detection service must see the same folder. |

**Processing footage locally** needs the detection service running with the same token and folder as the dev backend:

```bash
cd ml-service && source .venv/bin/activate
SERVICE_TOKEN=dev-only-service-token-change-me-0123456789 uvicorn app.main:app --port 8000
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

Weeks 1 to 3: design documents, Flyway database schema, login with JWT and BCrypt, admin and operator roles, per-camera access for operators, user management API, audit log. The dashboard has a login screen and the camera list.
Next: AWS deployment, footage upload and the detection job flow (see the Trello board).
