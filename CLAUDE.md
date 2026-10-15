# Smart CCTV Analytics Platform

Solo university capstone project. Operators upload recorded camera footage; the system detects and tracks people and vehicles, creates events (including rule-based ones), and provides a searchable event history with clip playback.

Course requirements that shape the work: GitHub for version control, Trello board with weekly progress reports, full SDLC artifacts (analysis, design, implementation, testing), demonstrated security measures, final version hosted on AWS or GCP, final report and presentation. The Trello board is https://trello.com/b/Lt2EjwUr/cctv-analytics-platform.

## Where things are

| Path | What |
|---|---|
| `backend/` | Spring Boot 3.3, Java 21, Maven. Package `com.cctv.platform`. |
| `ml-service/` | FastAPI detection service (YOLOv8 via Ultralytics). Python 3.12. |
| `frontend/` | React, TypeScript, Vite. `/api` is proxied to `localhost:8080` in dev. |
| `docs/` | Source of truth for design. Start with `docs/README.md`. |
| `docs/api/openapi.yaml` | API contract. Keep it in sync with the controllers. |
| `docs/diagrams/` | Rendered PNGs of the Mermaid diagrams in the docs. |
| `.github/workflows/ci.yml` | CI: builds backend and frontend, imports the ML service. |

Read `docs/01-requirements.md`, `docs/03-architecture.md`, `docs/04-database-design.md` and `docs/05-tech-stack-decisions.md` before changing design-level things. If code and docs disagree, tell the user and fix whichever is wrong; do not let them drift.

## Commands

```bash
# Backend (in-memory H2, no Postgres needed)
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd backend && mvn -B verify                    # build and tests

# Frontend
cd frontend && npm install && npm run dev      # http://localhost:5173
cd frontend && npm run build

# Detection service
cd ml-service && python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt                # light; enough for /health
pip install -r requirements-ml.txt             # adds Ultralytics and PyTorch (several GB)
uvicorn app.main:app --reload --port 8000

# Everything with Postgres
docker compose up --build
```

## Key design decisions

- Input is **recorded uploads**, not live streams (live cameras are out of scope).
- Backend submits a job to the detection service over HTTP; the detection service calls back with status and events. No message broker.
- Event rules (loitering, zone entry, after-hours vehicle) run in the detection service, using zones and rules sent with each job.
- Auth is stateless JWT with Spring Security and BCrypt. Roles: `ADMIN`, `OPERATOR`. Operators only see cameras assigned to them.
- Storage is S3 behind a `VideoStorage` interface, with a local-folder implementation for development. Clips are served through short-lived signed links.
- Detection uses pretrained YOLOv8n; tracking uses Ultralytics' built-in ByteTrack (BoT-SORT as fallback). This replaces DeepSORT from the charter.
- No facial recognition, ever. Only public, licensed or consenting footage.

## Current state (as of 2026-10-08)

Done: charter, plan, requirements, dataset selection, architecture, database design, OpenAPI contract, technology decisions, project scaffold, CI workflow, Flyway baseline schema, authentication and access control.

Backend (packages under `com.cctv.platform`):
- Schema: Flyway `V1__baseline_schema.sql` creates all 9 tables; Hibernate runs with `ddl-auto: validate`. Entities exist for `User`, `Camera`, `AuditLog`.
- `auth`: login and `/api/auth/me`, HS256 JWT via Spring Security resource server (60 min), BCrypt, failed-login limit (5 per email per 15 min, in memory), bootstrap admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD`. Controllers receive the caller as a `CurrentUser` parameter. Role checks use `@PreAuthorize`; camera access is checked in `CameraService` (unassigned cameras return 404). `/internal/**` is `denyAll` until the service token exists.
- `user`: admin endpoints list, create, patch (last active admin rule), replace camera access.
- `camera`: list (filtered by access), get, create. PUT and DELETE from the contract are **not implemented**.
- `audit`: `AuditService.record(...)` for every security-relevant action; admin search at `/api/audit-logs`. Add new actions to `AuditAction` and to the description in `openapi.yaml`.
- `common`: `ApiException` and RFC 7807 problem responses.
- Tests: `SchemaMigrationTests`, `AuthAndAccessTests` (MockMvc), `AuthPropertiesTests`.

Detection service: `/health` and a single-image `/detect` stub that returns 503 until Ultralytics is installed. The `/jobs` flow in the architecture doc is **not implemented**.

Frontend: login screen, token in `sessionStorage`, camera list; add-camera form only for admins. `src/api.ts` wraps fetch with the token and problem-error handling. No user management screens yet.

Dev logins: `admin@cctv.local` / `admin-dev-password` (dev profile and docker-compose only).

Verified working: backend build and 30 tests (JDK 21), migration and auth flow against a real PostgreSQL, login and access flow end to end with curl, ML `/health`, frontend build. The user confirmed the camera page displays correctly in a browser (before login was added).
**Not yet verified:** `docker compose`, the real YOLO detection path, the login screen in a browser, the CI run on GitHub.

Local machine note: `~/.zshrc` pins `JAVA_HOME` to JDK 17. Build the backend with `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B verify` unless the user has changed that.

## Next steps (Week 3 of the plan)

1. Set up the AWS account and deploy a "hello world" API and dashboard (needs the user's account).
2. Download MOT17, record demo clips (see `docs/02-dataset-selection.md`).
3. Camera PUT/DELETE and admin screens for users and camera access in the dashboard.

## Conventions

- Never commit footage or datasets. `data/`, `*.mp4`, `*.avi` and `*.pt` are git-ignored.
- Never commit secrets. Use environment variables.
- Every new endpoint needs a server-side authorization check and an entry in `docs/api/openapi.yaml`.
- Security-relevant actions write an audit log entry.
- Add tests with new backend features; CI runs `mvn -B verify`.
- Weekly report drafts live in `docs/weekly-reports/`.
- Docs are written for a professor to read: plain, professional language, no marketing tone.
