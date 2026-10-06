# Smart CCTV Analytics Platform — Capstone Project Plan

**Team members:** _(fill in)_
**Semester length assumed:** 15 weeks — adjust dates to your actual calendar
**Tools:** GitHub (version control) · Jira/Trello (project management, weekly progress reports) · AWS or GCP (final cloud hosting)

---

## 1. Project Overview

A video analytics platform that ingests footage from security cameras, automatically detects and tracks people and vehicles, flags notable events (loitering in a restricted zone, after-hours vehicle activity, etc.), and gives operators a searchable event history instead of raw footage they'd have to scrub through manually.

**Core value proposition:** turn passive video storage into an actionable, searchable record.

---

## 2. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Backend API | Spring Boot (Java) | Auth, event storage, search API, RBAC |
| Database | PostgreSQL | Cameras, events, users, audit logs |
| Detection model | YOLOv8 (Ultralytics) | Person/vehicle detection per frame |
| Tracking | ByteTrack or DeepSORT | De-duplicates detections across frames into one tracked object |
| ML service | Python (FastAPI) | Wraps detection+tracking, separate from Java backend |
| Frontend | React | Operator dashboard: search, filter, clip playback |
| Storage | AWS S3 / GCS (encrypted) | Video clips, thumbnails |
| Auth | Spring Security + JWT | Role-based: operator vs. admin |
| Deployment | Docker + AWS ECS/EC2 or GCP Cloud Run | Containerized services |
| CI/CD | GitHub Actions | Build/test on push, optional auto-deploy |

**Dataset for development/demo:** since you likely won't have live cameras, use a public surveillance dataset (e.g., VIRAT) or record your own short test clips to develop and demo against.

---

## 3. Week-by-Week Plan

| Week | Focus | Key Deliverables |
|---|---|---|
| 1 | Kickoff — roles, GitHub repo, Jira/Trello board, requirements gathering | Analysis doc (draft), Weekly Report #1 |
| 2 | System design — use case diagrams, ER diagram (cameras/events/users), API contract, finalize architecture | Design doc v1 |
| 3 | Environment setup — cloud account, repo scaffolding (Spring Boot + React), CI skeleton | "Hello world" deployed to cloud |
| 4 | Detection pipeline — prep dataset, integrate YOLOv8, baseline detection test | Working detection on sample footage |
| 5 | Tracking + event logic — integrate ByteTrack/DeepSORT, define event rules (loitering, after-hours, restricted zone) | Tracked objects → structured events |
| 6 | Backend core — camera management API, event schema in Postgres, JWT auth, RBAC skeleton | Core API endpoints functional |
| 7 | Integration — connect detection service to backend ingestion endpoint, encrypted clip storage to S3 | End-to-end pipeline: video → detection → stored event |
| 8 | **Midpoint demo** — internal review, catch-up buffer | Progress report to instructor, working E2E demo |
| 9 | Frontend build — dashboard shell, camera list, recent events feed | Basic UI functional |
| 10 | Search & filtering — query API (time range, camera, event type), pagination, clip playback | Full search/filter UX |
| 11 | Security hardening — audit logging, RBAC enforcement testing, HTTPS, secrets management | Security checklist (below) complete |
| 12 | Testing — backend unit tests, detection model metrics (precision/recall), integration testing, bug fixes | Test report + model evaluation numbers |
| 13 | Cloud deployment — containerize, deploy full stack, smoke test in production environment | Live hosted URL |
| 14 | Documentation — compile Analysis/Design/Implementation/Testing artifacts into final report, user guide | Final report draft |
| 15 | Polish + rehearse | Final report submitted, presentation ready |

---

## 4. Weekly Progress Report Template

Keep it to a few lines, but submit every week without fail:

```
Week N — [Date]
Done: <2-3 bullets>
Blockers: <anything stuck, or "none">
Next week: <2-3 bullets>
```

---

## 5. Security Checklist (maps to capstone requirement #5)

- [ ] All video/clip storage encrypted at rest (S3 SSE or equivalent)
- [ ] HTTPS/TLS enforced on all endpoints
- [ ] Role-based access control (operator vs. admin) enforced server-side, not just hidden in UI
- [ ] Audit log: every event view/search recorded with user + timestamp
- [ ] Secrets (API keys, DB credentials) in environment variables/secret manager — never committed to GitHub
- [ ] Dependency vulnerability scanning enabled (GitHub Dependabot)
- [ ] Input validation on all API endpoints (especially file/video uploads)

---

## 6. SDLC Artifact Checklist (maps to capstone requirement #4)

| Phase | Artifacts |
|---|---|
| Analysis | Requirements doc, use case diagrams |
| Design | ER diagram, system architecture diagram, API spec |
| Implementation | Source code (GitHub), commit history showing incremental work |
| Testing | Test cases, unit test results, model evaluation metrics (precision/recall/mAP) |

---

## 7. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| No real camera access | Use public dataset or self-recorded test footage |
| Detection model accuracy too low | Start with a pretrained YOLOv8 model (no training needed) before attempting fine-tuning |
| Cloud costs | Use free-tier/student credits (AWS Educate, GCP for Students); keep inference on-demand rather than always-on GPU |
| Falling behind mid-semester | Week 8 buffer built in; treat weeks 4-7 as the highest-risk stretch and start early |

---

## 8. Final Submission Checklist

- [ ] Source code in GitHub with clear commit history
- [ ] Jira/Trello board shared with instructor/TA
- [ ] Weekly reports submitted every week
- [ ] Final version hosted live on AWS/GCP
- [ ] All SDLC artifacts included in final report
- [ ] Security checklist completed and documented
- [ ] Final report submitted
- [ ] Presentation prepared
