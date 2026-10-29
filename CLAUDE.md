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
pip install -r requirements-dev.txt && python -m pytest -q   # tests stub the model; no PyTorch needed
python -m app.cli ../data/samples/clip.mp4 --zone "0.1,0.5 0.6,0.5 0.6,0.95 0.1,0.95" --loiter 10
python -m app.evaluation ../data/datasets/MOT17/train/MOT17-09-FRCNN   # person precision/recall
pip install -r requirements-eval.txt && python -m app.track_eval ../data/datasets/MOT17/train/MOT17-{02,04,09}-FRCNN  # MOTA/IDF1
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

## Current state (as of 2026-10-29)

Done: charter, plan, requirements, dataset selection, architecture, database design, OpenAPI contract, technology decisions, project scaffold, CI workflow, Flyway baseline schema, authentication and access control.

Backend (packages under `com.cctv.platform`):
- Schema: Flyway `V1__baseline_schema.sql` creates all 9 tables; Hibernate runs with `ddl-auto: validate`. Every table has an entity.
- `auth`: login and `/api/auth/me`, HS256 JWT via Spring Security resource server (60 min), BCrypt, failed-login limit (5 per email per 15 min, in memory), bootstrap admin from `ADMIN_EMAIL`/`ADMIN_PASSWORD`. Controllers receive the caller as a `CurrentUser` parameter. Role checks use `@PreAuthorize`; camera access is checked in `CameraService` (unassigned cameras return 404). `/internal/**` has its own filter chain (`ServiceTokenFilter`, constant-time compare of `X-Service-Token`); user JWTs are not accepted there.
- `user`: admin endpoints list, create, patch (last active admin rule), get and replace camera access.
- `camera`: list (filtered by access), get, create, update, delete. Delete with footage needs `deleteFootage=true` and goes through `video/CameraDeletionService` (avoids a CameraService/VideoService cycle). `CameraService.getVisible` is the access check reused by videos, jobs, events, zones and rules.
- `zone`: zones and event rules, admin create/delete (rules also update), operators read. Validation mirrors `ml-service/app/models.py`.
- `storage`: `VideoStorage` interface (store, exists, delete, `signedUrl`), `LocalVideoStorage` (`app.storage.local-dir`, env `STORAGE_DIR`, default `../data/storage`, shared with the detection service), `LinkSigner` (HMAC-SHA256, `STORAGE_SIGNING_SECRET`), `MediaController` (`GET /api/media?key&expires&sig`, permitAll, signature is the auth, 403 for every failure, range requests), `StorageKeys.isSafe`. Link TTL `app.storage.link-ttl` 5m. No S3 yet.
- `video`: upload (`.mp4` name, `ftyp` content check, `app.upload.max-bytes` 500 MB, generated keys `videos/{camera}/{yyyy}/{MM}/{uuid}.mp4`), get, admin delete (removes jobs, events and files).
- `job`: `ProcessingJob`, `DetectionClient` (POST /jobs, `app.detection.*`, env `DETECTION_URL`, `SERVICE_TOKEN`), `JobService` (dispatch after commit, FAILED if unreachable; callbacks validate events, replace on resend, DONE). `InternalJobController` serves `/internal/jobs/{id}/status|events`.
- `event`: search with filters and sort whitelist, limited to visible cameras; detail; `/clip` and `/thumbnail` redirect (302) to signed links. Search, detail and clip views are audited.
- `audit`: `AuditService.record(...)` for every security-relevant action; admin search at `/api/audit-logs`. Add new actions to `AuditAction` and to the description in `openapi.yaml`.
- `common`: `ApiException` and RFC 7807 problem responses.
- Tests: `SchemaMigrationTests`, `AuthAndAccessTests`, `ProcessingFlowTests` (upload, callbacks, search, media links, camera delete), `AuthPropertiesTests`. 59 total.

Detection service (`ml-service/app`):
- `models.py`: job request and event shapes from the contract, with validation (zones 0..1, rule params, storage keys that cannot escape `STORAGE_DIR`).
- `events.py`: pure-Python event building and the three rules. Design details are in `docs/03-architecture.md` section 5.
- `detector.py`: YOLOv8 plus ByteTrack over a video with OpenCV, every Nth frame; lazy imports.
- `jobs.py` and `main.py`: `POST /jobs` (requires `X-Service-Token`, fails closed if `SERVICE_TOKEN` is unset), one background worker, callbacks to the API's `/internal/jobs/...`. Env: `SERVICE_TOKEN`, `API_BASE_URL`, `STORAGE_DIR`.
- `media.py`: one extra pass writes a thumbnail (best frame) and a clip (event ±2 s, max 30 s, 640 px, ~15 fps, box drawn) per event; H.264 MP4 if OpenCV can encode it, else VP8 WebM. Failure is logged and events are sent without media. Env `MEDIA=off`, `CLIP_WIDTH`, `CLIP_MAX_SECONDS`.
- `cli.py` (run the pipeline on a file), `evaluation.py` (MOT17 person precision/recall), `track_eval.py` (MOTA/IDF1 via motmetrics, `requirements-eval.txt`), `make_video.py` (MOT image sequence to .mp4).
- Not done: S3.

Frontend: login screen, token in `sessionStorage`. Admins get a nav: Cameras, Users (`UsersPage.tsx`: create, role, enable/disable, camera access), Audit log (`AuditPage.tsx`). Clicking a camera opens `CameraDetail.tsx`: upload form, job status polling, paged event table with a type filter and a Play button opening `EventPanel.tsx` (clip and thumbnail). Admins also see `CameraAdmin.tsx`: edit/delete camera, zone editor (click points on a recent thumbnail), rules. `src/api.ts` wraps fetch; `mediaUrl()` follows the clip/thumbnail redirect to get the signed link.

Dev logins: `admin@cctv.local` / `admin-dev-password` (dev profile and docker-compose only).

Verified working: backend build and 51 tests (JDK 21) on H2 and on a real PostgreSQL; full upload-to-event flow with the real backend and real detection service on MOT17-09 (upload, dispatch, 55 events stored with absolute times, search, audit), done 2026-10-15; frontend build. The user confirmed the camera page displays correctly in a browser (before login was added).
ML service: 45 pytest tests pass (media and track-eval tests skip without OpenCV/NumPy, so CI runs 37). Heavy deps installed locally (`ml-service/.venv`, Python 3.13). Real detection verified on MOT17: person precision 0.934, recall 0.427 (`docs/06-detection-baseline.md`); `/jobs` verified end to end against a stand-in API. The Dockerfile (CPU-only torch, baked weights, non-root) has not been built.
Sprint 2 (2026-10-29): full flow including clips verified through the Vite proxy (38 events, all with clip and thumbnail, signed links, range requests, forged link refused). Tracking quality measured (docs/06 section 4).
**Not yet verified:** `docker compose` (Docker Desktop was not running), the Sprint 2 screens (users, audit log, zone editor, rules, clip playback) in a browser.

Local machine note: `~/.zshrc` pins `JAVA_HOME` to JDK 17. Build the backend with `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -B verify` unless the user has changed that.

Data: `data/` is git-ignored. MOT17 is downloaded: `data/datasets/MOT17.zip` (5.9 GB) with the train FRCNN sequences extracted to `data/datasets/MOT17/train/`. Static-camera sequences: 02, 04, 09. Sample video: `data/samples/MOT17-09.mp4`. MOT17 is CC BY-NC-SA 3.0.
Week numbering: Week 1 ended 4 Oct, so Week 3 = 12–18 Oct, Week 5 = 26 Oct–1 Nov. Sprint 1 = Weeks 1–3. No commits were made in Week 4 (19–25 Oct).

## Next steps (plan: Week 4 detection, Week 5 tracking and rules, Weeks 6 and 7 backend and integration)

1. Still open from Week 3: AWS account and "hello world" deployment (needs the user's account).
2. S3 storage with encryption and presigned URLs (implement `VideoStorage` for S3).
3. Run `docker compose up --build` once Docker Desktop is running.
4. Record demo clips with consenting people; build the vehicle evaluation set.

## Conventions

- Never commit footage or datasets. `data/`, `*.mp4`, `*.avi` and `*.pt` are git-ignored.
- Never commit secrets. Use environment variables.
- Every new endpoint needs a server-side authorization check and an entry in `docs/api/openapi.yaml`.
- Security-relevant actions write an audit log entry.
- Add tests with new backend features; CI runs `mvn -B verify`.
- Weekly report drafts live in `docs/weekly-reports/`.
- Docs are written for a professor to read: plain, professional language, no marketing tone.
