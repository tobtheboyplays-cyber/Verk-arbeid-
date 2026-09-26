#!/usr/bin/env python3
"""Write tools/motion/settler.geo.json: the settler rig as a Bedrock /
GeckoLib geometry for editing motion clips in Blockbench.

Open it in Blockbench (File > Open Model), pick the settler texture, then in
the Animate tab use "Import Animations" on any
src/main/resources/assets/hearthstead/animations/settler/*.animation.json,
edit, and "Export Animations" back over the same file. F3+T (or
/hsmotion reload in a dev client) plays the change in game.

Coordinates are the runtime's: bedrock x = Java x, bedrock y = 24 - Java y,
bedrock z = Java z; bone pivots are absolute. Arms and legs are split at the
joint into two cubes, the lower one parented to the bend bone
(right_forearm / left_forearm at the elbow, right_shin / left_shin at the
knee), with per-face UVs cut from the same 128x64 box-UV islands the game
uses, so rotating a bend bone in Blockbench folds the limb like the in-game
bendable mesh does (the game fills the joint wedge; Blockbench shows a hinge).
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "settler.geo.json")

# name, parent, java offset (relative), cubes [(u, v, bx, by, bz, w, h, d, inflate, mirror)]
BONES = [
    ("root", None, (0, 24, 0), []),
    ("torso", "root", (0, -12, 0), [(64, 0, -5, -12, -2.5, 10, 12, 5, 0.0, False)]),
    ("belt", "torso", (0, 0, 0), [(96, 20, -5, -5, -2.5, 10, 2, 5, 0.3, False)]),
    ("cloak", "torso", (0, -12, 0), [(64, 32, -5.5, 0, -3, 11, 4, 6, 0.2, False)]),
    ("backpack", "torso", (0, 0, 0), [(96, 0, -3, -9, 2.5, 6, 7, 3, 0.0, False)]),
    ("sack", "torso", (0, -10.5, 2.5), [(28, 17, -2.5, 0, 1, 5, 3, 4, 0.0, False),
                                          (0, 17, -3.5, 2, 0, 7, 6, 6, 0.0, False)]),
    ("head", "torso", (0, -12, 0), [(0, 0, -4, -8, -4, 8, 8, 8, 0.0, False),
                                     (120, 32, -0.75, -2.5, -5.5, 1.5, 3, 1.5, 0.0, False)]),
    ("hood", "head", (0, 0, 0), [(32, 0, -4, -8, -4, 8, 8, 8, 0.6, False)]),
    ("right_arm", "torso", (-6, -10, 0), "arm:0:32:0"),
    ("right_forearm", "right_arm", (0, 4, 0), "arm:0:32:1"),
    ("left_arm", "torso", (6, -10, 0), "arm:16:32:0:m"),
    ("left_forearm", "left_arm", (0, 4, 0), "arm:16:32:1:m"),
    # Held-item wrist bones at the palm: rotate/slide the tool in the fist.
    ("right_item", "right_forearm", (0, 6, 0), []),
    ("left_item", "left_forearm", (0, 6, 0), []),
    ("right_leg", "root", (-2.6, -12, 0), "leg:32:32:0"),
    ("right_shin", "right_leg", (0, 6, 0), "leg:32:32:1"),
    ("left_leg", "root", (2.6, -12, 0), "leg:48:32:0:m"),
    ("left_shin", "left_leg", (0, 6, 0), "leg:48:32:1:m"),
]


def split_limb(spec, abs_pivot_of_limb):
    """Upper or lower half of a 4x12x4 limb, per-face UV from its box island."""
    parts = spec.split(":")
    kind, u, v, half = parts[0], int(parts[1]), int(parts[2]), int(parts[3])
    mirror = len(parts) > 4
    w, h, d = 4, 12, 4
    y0 = -2 if kind == "arm" else 0          # limb-local top
    joint = 4 if kind == "arm" else 6         # limb-local joint
    top, bottom = (y0, joint) if half == 0 else (joint, y0 + h)
    # side strip V range for this half
    sv0 = v + d + (top - y0)
    sv1 = v + d + (bottom - y0)
    west = [u, sv0, d, sv1 - sv0]
    north = [u + d, sv0, w, sv1 - sv0]
    east = [u + d + w, sv0, d, sv1 - sv0]
    south = [u + d + w + d, sv0, w, sv1 - sv0]
    if mirror:
        west, east = [east[0] + east[2], east[1], -east[2], east[3]], [west[0] + west[2], west[1], -west[2], west[3]]
        north = [north[0] + north[2], north[1], -north[2], north[3]]
        south = [south[0] + south[2], south[1], -south[2], south[3]]
    faces = {
        "north": {"uv": north[:2], "uv_size": north[2:]},
        "east": {"uv": east[:2], "uv_size": east[2:]},
        "south": {"uv": south[:2], "uv_size": south[2:]},
        "west": {"uv": west[:2], "uv_size": west[2:]},
    }
    if half == 0:
        faces["up"] = {"uv": [u + d, v], "uv_size": [w, d]}
    else:
        faces["down"] = {"uv": [u + d + w, v], "uv_size": [w, d]}
    px, py, pz = abs_pivot_of_limb
    ax0, ay0, az0 = px - 2, py + top, pz - 2          # java abs min corner (y down)
    origin = [ax0, 24 - (ay0 + (bottom - top)), az0]
    return {"origin": [round(c, 4) for c in origin], "size": [w, bottom - top, d], "uv": faces}


def build():
    absolute = {}
    for name, parent, off, _ in BONES:
        base = absolute.get(parent, (0, 0, 0))
        absolute[name] = (base[0] + off[0], base[1] + off[1], base[2] + off[2])
    bones = []
    for name, parent, off, cubes in BONES:
        ax, ay, az = absolute[name]
        bone = {"name": name, "pivot": [ax, 24 - ay, az]}
        if parent:
            bone["parent"] = parent
        out = []
        if isinstance(cubes, str):
            limb = name if "forearm" not in name and "shin" not in name else parent
            out.append(split_limb(cubes, absolute[limb]))
        else:
            for (u, v, bx, by, bz, w, h, d, inflate, mirror) in cubes:
                cube = {"origin": [ax + bx, 24 - (ay + by + h), az + bz], "size": [w, h, d], "uv": [u, v]}
                if inflate:
                    cube["inflate"] = inflate
                if mirror:
                    cube["mirror"] = True
                out.append(cube)
        if out:
            bone["cubes"] = out
        bones.append(bone)
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {"identifier": "geometry.hearthstead.settler", "texture_width": 128,
                            "texture_height": 64, "visible_bounds_width": 3, "visible_bounds_height": 3,
                            "visible_bounds_offset": [0, 1, 0]},
            "bones": bones,
        }],
    }


if __name__ == "__main__":
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(build(), f, indent=2)
        f.write("\n")
    print("wrote", OUT)
