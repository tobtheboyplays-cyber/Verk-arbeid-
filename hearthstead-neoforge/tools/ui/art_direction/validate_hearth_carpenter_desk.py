#!/usr/bin/env python3
from pathlib import Path
import json
from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build" / "ui_art_direction" / "hearth_carpenter_desk"
SRC = ROOT / "tools" / "ui" / "art_direction" / "sources"
RUNTIME = ROOT / "tools" / "ui" / "art_direction" / "runtime_candidates" / "hearth_carpenter_desk"

def fail(msg): raise SystemExit("CARPENTER DESK REJECT: " + msg)
manifest = json.loads((OUT / "manifest.json").read_text(encoding="utf-8"))
if len(manifest["criteria"]) != 5 or any(x["result"] != "PENDING_TOBIAS" for x in manifest["criteria"]):
    fail("visual approval must remain Tobias-owned and exactly five criteria")
for state in ("a_normal", "b_hover", "c_blocked"):
    path = OUT / f"hearth_carpenter_{state}_427x240.png"
    with Image.open(path) as image:
        if image.size != (427, 240) or image.mode != "RGBA": fail(f"wrong preview: {path}")
        if ImageStat.Stat(image.crop((11, 11, 416, 239)).convert("L")).stddev[0] < 22:
            fail(f"flat value grouping: {state}")
        # Both outer desk leaves must contain authored material. This catches
        # regression to a centered 320px composition inside the wide preview.
        if ImageStat.Stat(image.crop((11, 80, 45, 225)).convert("L")).mean[0] < 20:
            fail(f"left fold-out leaf missing: {state}")
        if ImageStat.Stat(image.crop((382, 80, 416, 225)).convert("L")).mean[0] < 20:
            fail(f"right fold-out leaf missing: {state}")
    source = SRC / f"hearth_carpenter_{state}_427x240.aseprite"
    if not source.exists() or source.stat().st_size < 4096: fail(f"missing editable source: {source}")
with Image.open(OUT / "hearth_carpenter_survivability_320x240.png") as image:
    if image.size != (320, 240): fail("minimum viewport mismatch")
for name in ("desk_frame_320x220.png", "desk_insets_320x220.png", "desk_hardware_320x220.png", "nine_slice_parts.png"):
    if not (RUNTIME / name).exists(): fail(f"missing runtime fragment: {name}")
for name in ("hearth_carpenter_approval_board.png", "hearth_carpenter_depth_200pct.png", "hearth_carpenter_value_check.png"):
    if not (OUT / name).exists(): fail(f"missing review artifact: {name}")
print("CARPENTER DESK ASSET GATE PASS: selected #2 A/B/C, minimum viewport, editable sources, runtime fragments")
