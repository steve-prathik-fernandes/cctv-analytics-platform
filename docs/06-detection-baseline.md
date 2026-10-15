# Detection Baseline

Status: Week 4 deliverable. Measured 2026-10-15.
Related: [requirements](01-requirements.md) (charter objective: at least 80% person precision), [dataset selection](02-dataset-selection.md), [architecture](03-architecture.md) section 5.

## 1. Purpose

Establish how well the pretrained detector finds people before any tuning, and check that the full detection pipeline runs on real footage.

## 2. Setup

| Item | Value |
|---|---|
| Model | YOLOv8n, pretrained on COCO, no fine-tuning (Ultralytics 8.4.118, PyTorch 2.14.1) |
| Tracker | ByteTrack (Ultralytics built-in) |
| Hardware | Apple silicon laptop, CPU inference |
| Data | MOT17 training sequences 02, 04 and 09, FRCNN copies (CC BY-NC-SA 3.0) |
| Classes scored | Person only (MOT17 has no vehicle ground truth) |
| Confidence threshold | 0.4, the service default |

### Static-camera check

The dataset decision asked which MOT17 sequences use a fixed camera, since only those resemble CCTV. Each sequence's first frame was compared with its middle and last frames. With a fixed camera, most of the background stays the same, so the median pixel change is small.

| Sequence | Median change, middle frame | Median change, last frame | Camera |
|---|---|---|---|
| MOT17-02 | 5 | 2 | Static |
| MOT17-04 | 3 | 3 | Static |
| MOT17-09 | 9 | 9 | Static |
| MOT17-05 | 27 | 75 | Moving |
| MOT17-10 | 50 | 28 | Moving |
| MOT17-11 | 44 | 43 | Moving |
| MOT17-13 | 28 | 53 | Moving |

Sequences 02, 04 and 09 are static, as expected. They are used for all measurements below.

### Scoring rules

Implemented in `ml-service/app/evaluation.py`, with unit tests in `ml-service/tests/test_evaluation.py`.

- Targets are ground-truth pedestrians (class 1) marked for evaluation with at least 25% visibility.
- Detections on less visible pedestrians, people on vehicles, static people, distractors and reflections are ignored: they count as neither correct nor wrong.
- A detection is correct (true positive) when its overlap (IoU) with an unmatched target is at least 0.5. Detections are matched from highest confidence down; a second detection on the same person is a false positive.

## 3. Results

### Precision and recall, default input size (640 px), every 5th frame

| Sequence | TP | FP | FN | Ignored | Precision | Recall |
|---|---|---|---|---|---|---|
| MOT17-02 | 702 | 51 | 1174 | 70 | 0.932 | 0.374 |
| MOT17-04 | 3168 | 211 | 4787 | 8 | 0.938 | 0.398 |
| MOT17-09 | 651 | 56 | 99 | 116 | 0.921 | 0.868 |
| **All** | **4521** | **318** | **6060** | **194** | **0.934** | **0.427** |

**Precision is 93.4%, above the 80% objective.** Each sequence is above 92%.

Recall is low on 02 and 04. Both are wide 1920×1080 scenes with many small, distant people. The model shrinks each frame to 640 pixels wide, and most missed people are only a few pixels tall at that size. Sequence 09 has larger people and reaches 87% recall.

### Effect of input size, every 10th frame

| Input size | Precision | Recall | Evaluation time |
|---|---|---|---|
| 640 px | 0.934 | 0.426 | 6 s |
| 1280 px | 0.923 | 0.604 | 16 s |

Sampling every 10th frame gives the same figures as every 5th frame at 640 px, so the sampling does not distort the results.

At 1280 px recall rises by 18 points for a 1-point drop in precision, but processing takes about 2.7 times as long. The input size is a setting (`YOLO_IMGSZ`, default 640). The choice is deferred to the cloud deployment, when the CPU budget is known.

### Pipeline run on footage

MOT17-09 converted to a 17.5-second, 1920×1080, 30 fps video and processed by the full pipeline (`python -m app.cli`), with a test zone over the lower-left quarter of the frame and a 5-second loitering rule:

| Measure | Value |
|---|---|
| Frames processed | 175 of 525 (every 3rd frame) |
| Speed | 38 ms per processed frame, 2.7 times faster than real time |
| Tracks | 45 track ids; 34 kept after dropping tracks shorter than 3 frames (ground truth has 26 pedestrians) |
| Events | 34 object detected, 17 zone entry, 4 loitering |

There are more tracks than people because a track can break when someone is hidden and get a new id when they reappear. Tracking quality is measured in Week 5.

The same run through the service's `POST /jobs` endpoint, with a stand-in for the API, produced the same 55 events and the expected status callbacks. A job for a missing file was reported as failed.

## 4. Limitations

- Vehicles are not measured. MOT17 annotates only pedestrians. The vehicle set (hand-labeled frames) is still to be built.
- MOT17 is street footage at roughly head height. Real CCTV is often higher and wider-angle, which makes people smaller, so recall on real footage may be lower still.
- Times are from a laptop CPU. The cloud instance will be slower or faster depending on its type.
- Tracking metrics (identity switches, fragmentation) are not measured yet.

## 5. How to reproduce

```bash
cd ml-service && source .venv/bin/activate
pip install -r requirements-ml.txt
# MOT17 extracted under data/datasets/MOT17 (git-ignored)
python -m app.evaluation ../data/datasets/MOT17/train/MOT17-{02,04,09}-FRCNN --stride 5
python -m app.evaluation ../data/datasets/MOT17/train/MOT17-{02,04,09}-FRCNN --stride 10 --imgsz 1280
python -m app.make_video ../data/datasets/MOT17/train/MOT17-09-FRCNN ../data/samples/MOT17-09.mp4
python -m app.cli ../data/samples/MOT17-09.mp4 --zone "0.0,0.6 0.5,0.6 0.5,1.0 0.0,1.0" --loiter 5
```
