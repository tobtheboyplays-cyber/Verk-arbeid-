#!/usr/bin/env python3
"""AC-5: build a labelled contact sheet from a recorded clip, and judge
whether it shows real motion (median inter-frame mean-absolute-difference)
rather than a frozen or black recording.

Usage: build_contact_sheet.py <clip.mp4> <out_sheet.png> [--frames N]
Prints JSON: {"motion_ok":.., "duration_ok":.., "fps_ok":.., "ac5_ok":..,
"subject_mad":.., "median_mad":.., "frame_count":.., "fps":.., "duration":..}
to stdout. `subject_mad` (loudest tile) is the pass decision; `median_mad`
(whole frame) is reported alongside it so a camera pan, which makes the two
converge, stays distinguishable from subject motion, which does not.
Exit 0 only if motion_ok AND the clip actually meets AC-5's >=3s/>=20fps
floor (duration_ok and fps_ok), else 1 — motion_ok alone used to be both
the field name and the entire exit-code decision, so a clip that failed the
duration/fps floor could still report success.
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
    from PIL import Image, ImageChops, ImageDraw, ImageStat
except ImportError:
    print(json.dumps({"motion_ok": False, "reasons": ["PIL not available"]}))
    sys.exit(2)

TILE_W, TILE_H = 426, 240

# Motion is judged on the LOUDEST REGION of the frame, not on the frame
# average. A whole-frame mean-absolute-difference is dominated by the pixels
# that never change: a settler walking three blocks from the camera occupies
# well under a tenth of a 1280x720 frame, so even an unmistakable walk cycle
# averages out to a fraction of a grey level. Measured live: a clip in which
# three settlers plainly walk around a pen (contact sheet f0-f11, every frame
# visibly different) scored a whole-frame median MAD of 0.34 — against a
# threshold of 2.0 that had been calibrated on PANNING footage, where every
# pixel changes at once. That combination made `motion_ok` unreachable for
# exactly the thing it exists to judge, once the forced camera pan became
# opt-in: the check could report "no motion" honestly, but could never report
# motion, whatever the settler did.
#
# So the frame difference is split into a grid and the LOUDEST tile is taken.
# A moving subject lights up the tiles it occupies (tens of grey levels) while
# untouched background tiles stay near zero, so the measure tracks the subject
# instead of the subject's share of the screen. A camera pan still passes —
# every tile is loud — so this strictly widens what can be detected rather
# than loosening what counts as motion. Both directions are proven in
# qa/reports/artifacts/live/*/film/: a walking settler passes, and the same
# framing with the server tick-frozen fails.
MOTION_GRID_COLS, MOTION_GRID_ROWS = 16, 9
MOTION_THRESHOLD = 2.0  # median loudest-tile MAD (0-255 grey) that counts as motion


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


def _grey_diff(a: Image.Image, b: Image.Image) -> Image.Image:
    return ImageChops.difference(a.convert("L"), b.convert("L"))


def mean_abs_diff(a: Image.Image, b: Image.Image) -> float:
    """Whole-frame mean absolute difference. Reported for continuity and for
    telling a pan apart from subject motion — no longer the pass decision."""
    return ImageStat.Stat(_grey_diff(a, b)).mean[0]


def loudest_tile_mad(a: Image.Image, b: Image.Image) -> float:
    """Mean absolute difference of the single most-changed tile of the frame.

    This is what actually answers "did the thing I am looking at move": the
    background tiles contribute nothing to it, so a small subject is not
    averaged into insignificance."""
    diff = _grey_diff(a, b)
    w, h = diff.size
    best = 0.0
    for row in range(MOTION_GRID_ROWS):
        for col in range(MOTION_GRID_COLS):
            box = (
                w * col // MOTION_GRID_COLS, h * row // MOTION_GRID_ROWS,
                w * (col + 1) // MOTION_GRID_COLS, h * (row + 1) // MOTION_GRID_ROWS,
            )
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            best = max(best, ImageStat.Stat(diff.crop(box)).mean[0])
    return best


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

        pairs = [(imgs[i][0], imgs[i + 1][0]) for i in range(len(imgs) - 1)]
        mads = sorted(mean_abs_diff(a, b) for a, b in pairs)
        median_mad = mads[len(mads) // 2] if mads else 0.0
        tile_mads = sorted(loudest_tile_mad(a, b) for a, b in pairs)
        subject_mad = tile_mads[len(tile_mads) // 2] if tile_mads else 0.0

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

    motion_ok = subject_mad > MOTION_THRESHOLD
    # AC-5's own floor: clip.mp4 >= 3s at >= 20fps. Small float slop (1e-6)
    # so an exactly-3.000s/exactly-20.0fps clip isn't rejected by rounding.
    duration_ok = duration >= 3.0 - 1e-6
    fps_ok = fps >= 20.0 - 1e-6
    ac5_ok = motion_ok and duration_ok and fps_ok
    result = {
        "motion_ok": motion_ok,
        "duration_ok": duration_ok,
        "fps_ok": fps_ok,
        "ac5_ok": ac5_ok,
        "subject_mad": round(subject_mad, 3),
        "median_mad": round(median_mad, 3),
        "frame_count": len(imgs),
        "fps": round(fps, 2),
        "duration": round(duration, 2),
    }
    print(json.dumps(result))
    return 0 if ac5_ok else 1


if __name__ == "__main__":
    sys.exit(main())
