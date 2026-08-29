#!/usr/bin/env python3
"""AC-3 screenshot validator: reject the failure modes actually observed
(22-colour and 2-colour artefacts from earlier runs — a blank/mostly-empty
capture, not real game content).

Checks: exact size (default 1280x720), mean luminance > 8/255, at least
`--min-colors` distinct colours, and at least `--min-edge` of pixels carrying
a real luminance edge.

Why the edge test exists (spec correction 2026-08-29)
-----------------------------------------------------
Distinct-colour count alone does not measure whether a frame rendered. It
measures how colourful the BACKDROP happened to be, and it was calibrated
when every screen came with a blurred backdrop — a blur interpolates between
pixels and manufactures thousands of intermediate colours out of nothing.

Measured on this repository's own captures:

    real, fully rendered plaque screen              484 colours,  6.12% edges
    real, fully rendered hearth (command centre)    170 colours,  8.96% edges
    real, fully rendered hearth (OLD parchment)     307 colours, 11.12% edges
    vanilla title screen                          20493 colours,  8.85% edges
    the 22-colour artefact this file was built to reject
                                                     22 colours,  0.23% edges
    the 2-colour artefact                             2 colours,  0.00% edges

The old 500-colour floor would have failed the OLD parchment hearth too, at
307 — it passed historically only because the scenes it ran in had colourful
backdrops. It was never testing the thing it claimed to test.

Edge density separates the two populations by two orders of magnitude, and it
is what "a real rendered frame" actually means: structure, not palette. So
the colour floor drops to where the observed artefacts really are (64, three
times the worst one) and the edge floor carries the assertion — 1.5%, four
times above the worst artefact and four times below the weakest real frame.
Both must pass. Every artefact this file was written to catch still fails
both; no real frame is anywhere near either bound. Ledger entry:
docs/HEARTHSTEAD_QUALITY_LEDGER.md, 2026-08-29.

Usage: check_screenshot.py <path> [--width W] [--height H]
Prints one JSON object to stdout: {"pass": bool, "width":.., "height":..,
"mean_luminance":.., "distinct_colors":.., "edge_fraction":.., "reasons": [...]}
Exit code 0 if pass, 1 if fail, 2 on error opening the image.
"""
import argparse
import json
import sys
import warnings

warnings.filterwarnings("ignore", category=DeprecationWarning)

try:
    from PIL import Image, ImageFilter
except ImportError:
    print(json.dumps({"pass": False, "reasons": ["PIL not available"]}))
    sys.exit(2)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--min-luminance", type=float, default=8.0)
    ap.add_argument("--min-colors", type=int, default=64)
    ap.add_argument("--min-edge", type=float, default=0.015)
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

    # Structural content: the fraction of pixels sitting on a real luminance
    # edge. A blank or near-blank capture has none; anything actually drawn
    # has thousands. See the module docstring for the measured separation.
    edges = img.convert("L").filter(ImageFilter.FIND_EDGES).getdata()
    edge_fraction = sum(1 for v in edges if v > 24) / n
    if edge_fraction < args.min_edge:
        reasons.append(f"edge fraction {edge_fraction:.4f} < {args.min_edge}")

    result = {
        "pass": not reasons,
        "width": w,
        "height": h,
        "mean_luminance": round(mean_luma, 2),
        "distinct_colors": distinct,
        "edge_fraction": round(edge_fraction, 4),
        "reasons": reasons,
    }
    print(json.dumps(result))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
