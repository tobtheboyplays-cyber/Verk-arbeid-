#!/usr/bin/env python3
from pathlib import Path
import json
from PIL import Image, ImageStat
ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/"build/ui_art_direction/hearth_clean_composite"
SRC=ROOT/"tools/ui/art_direction/sources"
def fail(msg): raise SystemExit("CLEAN COMPOSITE REJECT: "+msg)
m=json.loads((OUT/"manifest.json").read_text(encoding="utf-8"))
if m["status"]!="COMPOSITE PROOF - ROOT REVIEW REQUIRED - NO JAVA": fail("status overclaim")
bounds=json.loads((OUT/"pixel_bounds_audit.json").read_text(encoding="utf-8"))
if bounds["wide_intersections"] or bounds["compact_intersections"]: fail("literal content overlap")
for state in ("a_normal","b_hover","c_blocked"):
    path=OUT/f"hearth_clean_{state}_427x240.png"
    with Image.open(path) as im:
        if im.size!=(427,240): fail(f"wrong wide size {state}")
        if ImageStat.Stat(im.crop((11,6,416,234)).convert("L")).stddev[0]<28: fail(f"flat source loss {state}")
    source=SRC/f"hearth_clean_{state}_427x240.aseprite"
    if not source.exists() or source.stat().st_size<8000: fail(f"missing editable source {state}")
with Image.open(OUT/"hearth_clean_compact_320x240.png") as im:
    if im.size!=(320,240): fail("compact mismatch")
    # The bottom-right pixel band must remain inside the viewport and authored,
    # never a clipped shadow from a 240+ coordinate.
    if ImageStat.Stat(im.crop((72,233,249,240)).convert("L")).mean[0] < 8:
        fail("compact inventory bottom appears clipped")
if not (SRC/"hearth_clean_compact_320x240.aseprite").exists(): fail("compact source missing")
for name in ("hearth_clean_composite_board.png","hearth_clean_value_check.png","hearth_clean_depth_200pct.png","hearth_clean_bounds_audit.png"):
    if not (OUT/name).exists(): fail(f"missing evidence {name}")
print("CLEAN CARPENTER COMPOSITE ASSET GATE PASS: source-led A/B/C + separately composed 320 proof")
