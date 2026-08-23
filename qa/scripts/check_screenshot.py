#!/usr/bin/env python3
"""AC-3 screenshot validator: reject the failure modes actually observed
(22-colour and 2-colour artefacts from earlier runs — a blank/mostly-empty
capture, not real game content).

Checks: exact size (default 1280x720), mean luminance > 8/255, and more than
500 distinct colours.

Usage: check_screenshot.py <path> [--width W] [--height H]
Prints one JSON object to stdout: {"pass": bool, "width":.., "height":..,
"mean_luminance":.., "distinct_colors":.., "reasons": [...]}
Exit code 0 if pass, 1 if fail, 2 on error opening the image.
"""
import argparse
import json
import sys
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning)

try:
    from PIL import Image
except ImportError:
    print(json.dumps({"pass": False, "reasons": ["PIL not available"]}))
    sys.exit(2)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--min-luminance", type=float, default=8.0)
    ap.add_argument("--min-colors", type=int, default=500)
    args = ap.parse_args()

    try:
        img = Image.open(args.path).convert("RGB")
    except Exception as e:
        print(json.dumps({"pass": False, "reasons": [f"could not open image: {e}"]}))
        return 2

    w, h = img.size
    reasons = []
    if (w, h) != (args.width, args.height):
        reasons.append(f"size {w}x{h} != expected {args.width}x{args.height}")

    pixels = list(img.getdata())
    n = len(pixels)
    mean_luma = sum(0.299 * r + 0.587 * g + 0.114 * b for r, g, b in pixels) / n
    distinct = len(set(pixels))

    if mean_luma <= args.min_luminance:
        reasons.append(f"mean luminance {mean_luma:.2f} <= {args.min_luminance}")
    if distinct <= args.min_colors:
        reasons.append(f"distinct colors {distinct} <= {args.min_colors}")

    result = {
        "pass": not reasons,
        "width": w,
        "height": h,
        "mean_luminance": round(mean_luma, 2),
        "distinct_colors": distinct,
        "reasons": reasons,
    }
    print(json.dumps(result))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
