#!/usr/bin/env python3
"""Strict deterministic gate for the Hearth premium approval board."""
from pathlib import Path
import json
from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build" / "ui_art_direction" / "hearth_golden_slice"
SRC = ROOT / "tools" / "ui" / "art_direction" / "sources"


def fail(message):
    raise SystemExit("HEARTH GOLDEN SLICE REJECT: " + message)


manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
if "NOT IN-GAME VERIFIED" not in manifest["status"]:
    fail("approval artifact overclaims runtime verification")
if len(manifest["criteria"]) != 5:
    fail("approval board must contain exactly five criteria")
if any(item["result"] != "PENDING_TOBIAS" for item in manifest["criteria"]):
    fail("generator must not self-award Tobias's visual approval")

states = ("a_normal", "b_hover", "c_blocked")
for state in states:
    image_path = OUT / f"hearth_{state}_427x240.png"
    with Image.open(image_path) as image:
        if image.size != (427, 240) or image.mode != "RGBA":
            fail(f"wrong main preview geometry: {image_path}")
        # Main physical object must survive a thumbnail/value read.
        focus = image.crop((53, 20, 373, 240)).convert("L").resize((80, 55))
        # 17 rejects a recoloured flat panel while allowing the deliberately
        # quiet soot/iron normal state; the paper warning state is far higher.
        if ImageStat.Stat(focus).stddev[0] < 17:
            fail(f"flat value grouping in {state}")
    source = SRC / f"hearth_{state}_427x240.aseprite"
    if not source.exists() or source.stat().st_size < 4096:
        fail(f"missing editable six-layer Aseprite source: {source}")

with Image.open(OUT / "hearth_survivability_320x240.png") as image:
    if image.size != (320, 240):
        fail("320x240 survivability viewport is not exact")
    # Corners of the Hearth silhouette must remain in-frame.
    if image.getbbox() != (0, 0, 320, 240):
        fail("survivability preview has accidental transparency/cropping")

for review in ("hearth_golden_slice_approval_board.png",
               "hearth_golden_slice_depth_200pct.png",
               "hearth_golden_slice_value_check.png"):
    if not (OUT / review).exists() or (OUT / review).stat().st_size < 1024:
        fail(f"missing visual review artifact: {review}")

print("HEARTH GOLDEN SLICE ASSET GATE PASS: A/B/C, 320x240, 200% depth, value grouping, five pending visual criteria")
