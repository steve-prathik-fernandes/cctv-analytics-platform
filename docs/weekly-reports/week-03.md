# Weekly Report: Week 3

Project: Smart CCTV Analytics Platform (solo)
Student: [your name]
Week ending: [date]

**Done**
- Detection pipeline built: YOLOv8n with ByteTrack processes recorded video and turns tracks into events. Loitering, zone-entry and after-hours vehicle rules implemented ahead of the Week 5 plan, with 37 automated tests that now run in CI.
- MOT17 downloaded, license confirmed (CC BY-NC-SA 3.0), and sequences 02, 04 and 09 confirmed as static-camera footage.
- Baseline measured: person precision 93.4% on MOT17 (target 80%), recall 42.7%. Raising the input size to 1280 px lifts recall to 60.4% at 2.7 times the processing time. Details in `docs/06-detection-baseline.md`.
- Detection service job endpoint secured with a service token and checked end to end on sample footage.

**Blockers**
- [AWS account and "hello world" deployment from Week 3, if still outstanding.]

**Next week**
- Backend side of the processing flow: video upload with validation, job records, and the internal endpoints that receive events.
- Tracking quality checks on MOT17, and thumbnails for events.
- Record demo clips with consenting people.
