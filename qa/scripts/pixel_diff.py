#!/usr/bin/env python3
"""Percentage of differing pixels between two same-size screenshots, in a
named region. Used by the `key` input-class proof (AC-2): a key press must
visibly change the lower third of the screen (e.g. hotbar selection).

Usage: pixel_diff.py <before.png> <after.png> [--region lower-third|full]
Prints JSON {"pass":.., "diff_percent":.., "region":..} to stdout.
--min-percent sets the pass threshold (default 2.0).
Exit 0 pass, 1 fail, 2 error.
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
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--region", choices=["lower-third", "full"], default="lower-third")
    ap.add_argument("--min-percent", type=float, default=2.0)
    args = ap.parse_args()

    try:
        a = Image.open(args.before).convert("RGB")
        b = Image.open(args.after).convert("RGB")
    except Exception as e:
        print(json.dumps({"pass": False, "reasons": [f"could not open image(s): {e}"]}))
        return 2

    if a.size != b.size:
        print(json.dumps({"pass": False, "reasons": [f"size mismatch {a.size} vs {b.size}"]}))
        return 2

    w, h = a.size
    if args.region == "lower-third":
        box = (0, h * 2 // 3, w, h)
    else:
        box = (0, 0, w, h)
    a = a.crop(box)
    b = b.crop(box)

    pa = list(a.getdata())
    pb = list(b.getdata())
    n = len(pa)
    diff = sum(1 for x, y in zip(pa, pb) if x != y)
    pct = 100.0 * diff / n

    result = {
        "pass": pct > args.min_percent,
        "region": args.region,
        "diff_percent": round(pct, 3),
        "min_percent": args.min_percent,
    }
    print(json.dumps(result))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
