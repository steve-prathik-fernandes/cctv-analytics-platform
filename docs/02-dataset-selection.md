# Dataset Selection

Status: Week 1 deliverable. Decision recorded 2026-10-06.

## What the project needs from data

The detector is a pretrained YOLOv8 model (COCO classes), so **no training data is required**. Data is needed for two other jobs:

1. **Demo footage** that looks like a fixed security camera, to show the full pipeline working.
2. **Ground-truth annotations** to measure detection precision against the charter objective (at least 80% precision for person/vehicle on a held-out set, Section 2.1 of the charter).

These are different needs, so the plan uses different sources for each.

## Options considered

| Option | Contents | Annotations | Access and terms | Fit |
|---|---|---|---|---|
| **MOT17** (MOTChallenge) | 14 sequences, 33,705 frames, mixed static and moving cameras | Pedestrians only, with tracks | Direct download, 5.5 GB with images (9.7 MB metadata only). The download page states no license; confirm terms before relying on it. | Good for **person** precision and tracking checks |
| **VIRAT** (ground camera) | About 8.5 hours of HD fixed-camera video, 11 outdoor scenes | Events and object tracks (people, vehicles) | Ground data requires signing the VIRAT Video Dataset Protection Agreement. Download size not stated on the main page. | Closest to real CCTV, but adds an approval step and delay |
| **UA-DETRAC** | Traffic-camera vehicle videos | Vehicle boxes and tracks | The original host (detrac-db.rit.albany.edu) now redirects to a lab page. Copies exist on Kaggle, Hugging Face and Roboflow, but I have not verified their completeness or licensing. | Good for **vehicles** if a trustworthy copy is found |
| **Self-recorded clips** | Your own footage | Hand-labeled in CVAT | You control the terms. Must use consenting people only (charter Section 3.4). | Best for the **live demo**; also a small vehicle evaluation set |
| Stock footage (for example Pexels) | Free-to-use video | None | Check each clip's license. | Backup demo material |

## Decision

| Purpose | Choice |
|---|---|
| Person detection and tracking evaluation | **MOT17**, using the fixed-camera sequences. Verify which sequences are static before using them (02, 04 and 09 are believed to be). |
| Vehicle evaluation | **Hand-label 100 to 200 frames** from self-recorded or licensed stock clips in CVAT. Switch to a verified UA-DETRAC copy if one turns up. |
| Demo footage | **Self-recorded clips** with consenting people, plus one or two licensed stock clips as backup. |
| VIRAT | **Optional stretch.** Request the agreement only if time allows, since it is the best match for the project's story. |

### Why not VIRAT first

It is the closest match to the use case, but the access agreement puts an external dependency on a 15-week schedule. MOT17 can be downloaded today.

## Handling rules

- Footage and datasets never go into the Git repository. The `.gitignore` already excludes `data/`, `*.mp4` and `*.avi`.
- Keep the dataset license and citation in the final report.
- Do not record bystanders. This follows the privacy position in the charter.

## Action items

- [ ] Download MOT17 and confirm which sequences use a static camera.
- [ ] Record 3 to 5 short demo clips (30 to 120 seconds) with consent.
- [ ] Set up a CVAT project for the vehicle evaluation frames.
- [ ] Decide in Week 4 whether to request VIRAT access.
