# Weekly Report: Week 5

Project: Smart CCTV Analytics Platform (solo)
Student: [your name]
Week ending: 1 November 2026

**Done**
- Event clips and thumbnails: each event now has a short clip (2 seconds either side, box drawn) and a thumbnail, played in the dashboard.
- Clips and thumbnails are served through signed links that expire after 5 minutes; a forged or expired link is refused.
- Admin screens: user management, camera access, audit log, camera edit and delete, zone drawing on a camera frame, and rules.
- Tracking quality measured on MOT17: IDF1 50.1% overall and 67.2% on the sequence with larger people. Results in `docs/06-detection-baseline.md`.
- 104 automated tests (59 backend, 45 detection service).

**Blockers**
- AWS account still not set up, so the cloud deployment carries over again.
- Docker Desktop was not running, so the Docker Compose run is not yet checked.

**Next week**
- Set up AWS and deploy; add S3 storage with encryption.
- Run the full system with Docker Compose.
- Record demo clips and build the vehicle evaluation set.
