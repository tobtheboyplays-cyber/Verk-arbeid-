#!/usr/bin/env python3
"""Export the real RaiderModel geometry and RaiderAnimations into Blockbench.

This deliberately reuses the deterministic settler bridge's coordinate and
keyframe conversion, then swaps only the rig/source/64px texture facts.  It is
an offline Candidate inspection surface; native Minecraft remains final.
"""

from __future__ import annotations

import base64
import json
import os
import sys

import export_bbmodel as bridge


HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
NEOFORGE = os.path.dirname(TOOLS)
OUT = os.path.join(HERE, "raider.bbmodel")
TEXTURE = os.path.join(
    NEOFORGE,
    "src/main/resources/assets/hearthstead/textures/entity/raider/raider.png",
)

# Exact transcription of RaiderModel.createBodyLayer().
BONES = [
    ("root", None, (0, 24, 0), []),
    ("torso", "root", (0, -12, 0), [
        (0, 16, -4, -12, -2, 8, 12, 4, 0.0, False),
    ]),
    ("head", "torso", (0, -12, 0), [
        (0, 0, -4, -8, -4, 8, 8, 8, 0.0, False),
    ]),
    ("hood", "head", (0, 0, 0), [
        (32, 0, -4, -8, -4, 8, 8, 8, 0.45, False),
    ]),
    ("helm", "head", (0, 0, 0), [
        (0, 48, -4.5, -10.5, -4.5, 9, 3, 9, 0.0, False),
    ]),
    ("pauldron", "torso", (0, 0, 0), [
        (32, 32, -5, -12.5, -2.5, 10, 3, 5, 0.0, False),
    ]),
    ("right_arm", "torso", (-5, -10, 0), [
        (32, 16, -1.5, -1.5, -1.5, 3, 12, 3, 0.0, False),
    ]),
    ("left_arm", "torso", (5, -10, 0), [
        (48, 16, -1.5, -1.5, -1.5, 3, 12, 3, 0.0, True),
    ]),
    ("right_leg", "root", (-2.2, -12, 0), [
        (0, 32, -2, 0, -2, 4, 12, 4, 0.0, False),
    ]),
    ("left_leg", "root", (2.2, -12, 0), [
        (16, 32, -2, 0, -2, 4, 12, 4, 0.0, True),
    ]),
]


def build():
    bridge.BONES = BONES
    bridge.BASE_ROTATIONS_DEG = {}
    bridge.TEXTURE = TEXTURE
    bridge.TEXTURE_MODEL_PATH = os.path.relpath(TEXTURE, HERE).replace(os.sep, "/")
    raider_source = next(
        source for source in bridge.anim_check.ANIMATION_SOURCES
        if source["label"] == "raider"
    )
    original_sources = bridge.anim_check.ANIMATION_SOURCES
    bridge.anim_check.ANIMATION_SOURCES = [
        {"label": "settler", "path": raider_source["path"]}
    ]
    try:
        model = bridge.build()
    finally:
        bridge.anim_check.ANIMATION_SOURCES = original_sources

    model["name"] = "raider"
    model["model_identifier"] = "raider"
    model["resolution"] = {"width": 64, "height": 64}
    for animation in model["animations"]:
        animation["name"] = animation["name"].replace(
            "animation.settler.", "animation.raider."
        )
    with open(TEXTURE, "rb") as texture_file:
        source = base64.b64encode(texture_file.read()).decode("ascii")
    texture = model["textures"][0]
    texture.update({
        "path": bridge.TEXTURE_MODEL_PATH,
        "name": "raider.png",
        "folder": "raider",
        "width": 64,
        "height": 64,
        "uv_width": 64,
        "uv_height": 64,
        "uuid": bridge.stable_uuid("tex", "raider"),
        "source": "data:image/png;base64," + source,
    })
    return model


def main() -> int:
    args = sys.argv[1:]
    check = args == ["--check"]
    output = OUT
    if len(args) == 2 and args[0] == "--output":
        output = os.path.abspath(args[1])
    elif args not in ([], ["--check"]):
        print("usage: export_raider_bbmodel.py [--check | --output PATH]", file=sys.stderr)
        return 2
    payload = json.dumps(build())
    if check:
        try:
            with open(OUT, encoding="utf-8") as current_file:
                current = current_file.read()
        except OSError as exc:
            print(f"raider bbmodel check FAIL: {exc}", file=sys.stderr)
            return 1
        if current != payload:
            print(f"raider bbmodel check FAIL: regenerate {OUT}", file=sys.stderr)
            return 1
        print("raider bbmodel check PASS")
        return 0
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as out_file:
        out_file.write(payload)
    model = json.loads(payload)
    print(
        f"wrote {output}: {len(model['elements'])} cubes, "
        f"{len(model['animations'])} animations"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
