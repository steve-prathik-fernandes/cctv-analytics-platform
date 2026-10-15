"""Runs the detection pipeline on a local video file, without the API.

Used for the baseline detection test (plan Week 4) and for trying zones and rules on sample
footage. Example:

    python -m app.cli ../data/samples/clip.mp4 --stride 3 \\
        --zone "0.1,0.5 0.6,0.5 0.6,0.95 0.1,0.95" --loiter 10 --out events.json
"""
import argparse
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from .detector import DependencyMissing, track_video
from .events import MIN_TRACK_OBSERVATIONS, build_events
from .models import JobRequest


def _zone(text: str) -> list[list[float]]:
    try:
        return [[float(v) for v in point.split(",")] for point in text.split()]
    except ValueError as exc:
        raise argparse.ArgumentTypeError("zone must look like 'x,y x,y x,y' with values 0..1") from exc


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m app.cli", description=__doc__.split("\n\n")[0])
    p.add_argument("video", type=Path)
    p.add_argument("--stride", type=int, default=3, help="process every Nth frame (default 3)")
    p.add_argument("--conf", type=float, default=0.4, help="minimum confidence (default 0.4)")
    p.add_argument("--zone", type=_zone, help="restricted zone polygon, normalized points")
    p.add_argument("--loiter", type=float, metavar="SECONDS", help="add a loitering rule for the zone")
    p.add_argument("--after-hours", metavar="HH:MM-HH:MM", help="add an after-hours vehicle rule")
    p.add_argument("--recorded-at", default=None, help="ISO time the footage starts (default: now, UTC)")
    p.add_argument("--timezone", default="UTC", help="timezone for --after-hours (default UTC)")
    p.add_argument("--out", type=Path, help="write the events as JSON to this file")
    args = p.parse_args(argv)

    if not args.video.is_file():
        p.error(f"{args.video} not found")

    zones, rules = [], []
    if args.zone:
        zones.append({"id": 1, "name": "Zone", "polygon": args.zone})
        rules.append({"id": 1, "type": "ZONE_ENTRY", "zoneId": 1})
        if args.loiter:
            rules.append({"id": 2, "type": "LOITERING", "zoneId": 1, "params": {"minSeconds": args.loiter}})
    elif args.loiter:
        p.error("--loiter needs --zone")
    if args.after_hours:
        start, _, end = args.after_hours.partition("-")
        rules.append({"id": 3, "type": "AFTER_HOURS_VEHICLE",
                      "params": {"start": start, "end": end, "timezone": args.timezone}})

    job = JobRequest(jobId=0, cameraId=0, videoKey=args.video.name,
                     recordedStartAt=args.recorded_at or datetime.now(timezone.utc).isoformat(),
                     frameStride=args.stride, minConfidence=args.conf, zones=zones, rules=rules)

    try:
        result = track_video(args.video, job.frameStride, job.minConfidence)
    except DependencyMissing as exc:
        print(exc, file=sys.stderr)
        return 2
    events = build_events(result.observations, job)

    per_frame = result.elapsed_s / max(result.frames_processed, 1)
    print(f"Video:      {args.video.name}, {result.width}x{result.height}, {result.fps:.1f} fps, "
          f"{result.duration_s:.1f} s")
    print(f"Processed:  {result.frames_processed} of {result.frames_read} frames (stride {job.frameStride}) "
          f"in {result.elapsed_s:.1f} s, {per_frame * 1000:.0f} ms per frame, "
          f"{result.duration_s / max(result.elapsed_s, 1e-9):.2f}x real time")
    print(f"Detections: {len(result.observations)} tracked boxes, "
          f"{len({o.track_id for o in result.observations})} track ids "
          f"(tracks shorter than {MIN_TRACK_OBSERVATIONS} frames are dropped)")
    for (event_type, label), n in sorted(Counter((e.eventType, e.objectLabel) for e in events).items()):
        print(f"  {event_type:<20} {label:<11} {n}")

    if args.out:
        args.out.write_text(json.dumps([e.model_dump(mode="json") for e in events], indent=2))
        print(f"Wrote {len(events)} events to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
