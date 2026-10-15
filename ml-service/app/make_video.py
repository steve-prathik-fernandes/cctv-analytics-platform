"""Builds an .mp4 from a MOTChallenge image sequence, for use as sample footage.

    python -m app.make_video ../data/datasets/MOT17/train/MOT17-09-FRCNN ../data/samples/MOT17-09.mp4

The output uses OpenCV's mp4v codec. It is fine for the pipeline; browsers may not play it.
"""
import argparse
from pathlib import Path

from .evaluation import read_seqinfo


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="python -m app.make_video", description=__doc__.split("\n\n")[0])
    p.add_argument("sequence", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--seconds", type=float, default=None, help="only the first N seconds")
    args = p.parse_args(argv)

    import cv2

    info = read_seqinfo(args.sequence)
    fps = float(info["framerate"])
    images = sorted((args.sequence / info.get("imdir", "img1")).glob("*" + info.get("imext", ".jpg")))
    if args.seconds:
        images = images[: int(args.seconds * fps)]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    size = (int(info["imwidth"]), int(info["imheight"]))
    writer = cv2.VideoWriter(str(args.output), cv2.VideoWriter_fourcc(*"mp4v"), fps, size)
    try:
        for image in images:
            writer.write(cv2.imread(str(image)))
    finally:
        writer.release()
    print(f"Wrote {len(images)} frames ({len(images) / fps:.1f} s at {fps:g} fps) to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
