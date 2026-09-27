#!/usr/bin/env python3
"""Deterministic offline gate for the Hearthstead identity art package."""
from pathlib import Path
import json
from PIL import Image, ImageChops, ImageStat

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/"build"/"ui_art_direction"
SRC=ROOT/"tools"/"ui"/"art_direction"/"sources"
RUNTIME=ROOT/"src"/"main"/"resources"/"assets"/"hearthstead"/"textures"/"gui"/"identity"
SURFACES=("hearth_ledger","development_survey","courier_dispatch","plaque_workbench")
VIEWPORTS=((320,240),(427,240),(512,274))


def fail(message):
    raise SystemExit("IDENTITY UI FAIL: "+message)


manifest=json.loads((OUT/"identity_surface_manifest.json").read_text(encoding="utf-8"))
if manifest.get("integration_status","").startswith("ART CANDIDATE") is False:
    fail("manifest must not imply in-game approval")

for surface in SURFACES:
    source=SRC/f"{surface}_427x240.aseprite"
    if not source.exists() or source.stat().st_size < 2048:
        fail(f"missing editable Aseprite source: {source}")
    for size in VIEWPORTS:
        path=OUT/f"{surface}_{size[0]}x{size[1]}.png"
        with Image.open(path) as image:
            if image.size != size: fail(f"wrong viewport: {path} = {image.size}")
            if image.mode != "RGBA": fail(f"not RGBA: {path}")
            # Green belongs to state feedback, never the material field.
            pixels=list(image.get_flattened_data())
            green=sum(1 for r,g,b,a in pixels if a and g > r*1.18 and g > b*1.12)
            if green / len(pixels) > 0.025:
                fail(f"green dominance regression: {path} ({green/len(pixels):.2%})")

# Canonical surfaces must not collapse back into one reskinned template.
thumbs=[]
for surface in SURFACES:
    with Image.open(OUT/f"{surface}_427x240.png") as image:
        thumbs.append(image.convert("RGB").resize((64,36)))
for i in range(len(thumbs)):
    for j in range(i+1,len(thumbs)):
        difference=ImageStat.Stat(ImageChops.difference(thumbs[i],thumbs[j])).mean
        if sum(difference)/3 < 14:
            fail(f"surfaces {SURFACES[i]} and {SURFACES[j]} are visually too similar")

# Depth/value gate on each main interactive surface at thumbnail scale. A
# featureless dark rectangle has very low luma variance and is rejected.
focus_crops={"hearth_ledger":(108,46,319,148),
             "development_survey":(20,18,408,224),
             "courier_dispatch":(22,45,404,214),
             "plaque_workbench":(18,18,410,224)}
for surface,box in focus_crops.items():
    with Image.open(OUT/f"{surface}_427x240.png") as image:
        value=image.crop(box).convert("L").resize((96,48))
        spread=ImageStat.Stat(value).stddev[0]
        # 15 is intentionally above a flat/recoloured panel (near zero) while
        # still allowing the Hearth's inventory well to remain the darkest,
        # quietest surface in the set.
        if spread < 15: fail(f"flatness/value grouping regression: {surface} ({spread:.1f})")

for review in ("identity_surfaces_value_check.png","identity_surfaces_depth_detail_200pct.png"):
    if not (OUT/review).exists(): fail(f"missing review artifact: {review}")

for asset in manifest["runtime_assets"]:
    path=RUNTIME/asset
    if not path.exists() or path.stat().st_size < 128: fail(f"missing runtime fragment: {path}")

print("IDENTITY UI PASS: 4 distinct surfaces, 12 viewport renders, editable sources and runtime fragments verified")
