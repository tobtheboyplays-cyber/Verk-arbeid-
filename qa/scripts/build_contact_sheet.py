#!/usr/bin/env python3
"""AC-5: build a labelled contact sheet from a recorded clip, and judge
whether it shows real motion (median inter-frame mean-absolute-difference)
rather than a frozen or black recording.

Usage: build_contact_sheet.py <clip.mp4> <out_sheet.png> [--frames N]
Prints JSON: {"motion_ok":.., "median_mad":.., "frame_count":.., "fps":..,
"duration":..} to stdout. Exit 0 if motion_ok and clip meets AC-5's
duration/fps floor, else 1.
"""
import argparse
import json
import subprocess
import sys
import tempfile
import warnings
from pathlib import Path

warnings.filterwarnings("ignore", category=DeprecationWarning)

try:
    from PIL import Image, ImageDraw
except ImportError:
    print(json.dumps({"motion_ok": False, "reasons": ["PIL not available"]}))
    sys.exit(2)

TILE_W, TILE_H = 426, 240
MOTION_THRESHOLD = 2.0  # median MAD (0-255 grayscale) above which we call it real motion


def ffprobe_info(clip: str):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=r_frame_rate,duration",
        "-of", "json", clip,
    ], text=True)
    data = json.loads(out)["streams"][0]
    num, den = data["r_frame_rate"].split("/")
    fps = float(num) / float(den)
    duration = float(data.get("duration", 0) or 0)
    return fps, duration


def extract_frames(clip: str, n: int, duration: float, tmpdir: str):
    paths = []
    for i in range(n):
        t = duration * i / max(n - 1, 1)
        t = min(t, max(duration - 0.05, 0))
        out = str(Path(tmpdir) / f"f{i:03d}.png")
        subprocess.run([
            "ffmpeg", "-loglevel", "error", "-ss", f"{t:.3f}", "-i", clip,
            "-frames:v", "1", "-y", out,
        ], check=True)
        paths.append((out, t))
    return paths


def mean_abs_diff(a: Image.Image, b: Image.Image) -> float:
    ga = a.convert("L")
    gb = b.convert("L")
    pa = list(ga.getdata())
    pb = list(gb.getdata())
    return sum(abs(x - y) for x, y in zip(pa, pb)) / len(pa)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("clip")
    ap.add_argument("out_sheet")
    ap.add_argument("--frames", type=int, default=12)
    args = ap.parse_args()

    try:
        fps, duration = ffprobe_info(args.clip)
    except Exception as e:
        print(json.dumps({"motion_ok": False, "reasons": [f"ffprobe failed: {e}"]}))
        return 2

    with tempfile.TemporaryDirectory() as tmp:
        frame_paths = extract_frames(args.clip, args.frames, duration, tmp)
        imgs = [(Image.open(p).convert("RGB"), t) for p, t in frame_paths]

        mads = [mean_abs_diff(imgs[i][0], imgs[i + 1][0]) for i in range(len(imgs) - 1)]
        mads_sorted = sorted(mads)
        median_mad = mads_sorted[len(mads_sorted) // 2] if mads_sorted else 0.0

        cols = 4
        rows = (len(imgs) + cols - 1) // cols
        sheet = Image.new("RGB", (TILE_W * cols, TILE_H * rows), (16, 16, 16))
        draw = ImageDraw.Draw(sheet)
        for idx, (img, t) in enumerate(imgs):
            tile = img.resize((TILE_W, TILE_H))
            x = (idx % cols) * TILE_W
            y = (idx // cols) * TILE_H
            sheet.paste(tile, (x, y))
            label = f"f{idx} t={int(t * 1000)}ms"
            draw.rectangle([x, y + TILE_H - 18, x + TILE_W, y + TILE_H], fill=(0, 0, 0))
            draw.text((x + 4, y + TILE_H - 16), label, fill=(255, 255, 0))
        sheet.save(args.out_sheet)

    motion_ok = median_mad > MOTION_THRESHOLD
    result = {
        "motion_ok": motion_ok,
        "median_mad": round(median_mad, 3),
        "frame_count": len(imgs),
        "fps": round(fps, 2),
        "duration": round(duration, 2),
    }
    print(json.dumps(result))
    return 0 if motion_ok else 1


if __name__ == "__main__":
    sys.exit(main())
