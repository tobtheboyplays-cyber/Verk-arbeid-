#!/usr/bin/env python3
"""Edge-first wrist pass for axe clips: adds a right_item roll so the cutting
edge leads the swing.

For each dense sample of an authored clip the held-axe frame is rebuilt with
tools/blender/pipeline/mcrig.py (exact in-game held-item chain). The wanted
edge direction is the velocity of the edge point with its component along the
haft removed; the roll about the haft that turns the vanilla edge onto it is
solved, weighted toward zero outside the swing (so the hold/reset poses are
untouched), limited to +-60 deg, smoothed, and written as a right_item rotation
channel (ZYX degrees in arm-bent space, pivot at the palm) -- identical to
the runtime's T(palm) R(item) T(-palm).

usage: python tools/motion/wrist_edge_first.py chop chop__v2 chop__v3 [--from 0.30 --to 0.62] [--dry]
"""
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "blender", "pipeline"))
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

ASSETS = os.path.join(os.path.dirname(os.path.dirname(HERE)),
                      "src/main/resources/assets/hearthstead/animations/settler")
BONES = ["root", "torso", "right_arm", "right_forearm", "left_arm", "left_forearm"]


def channels_at(doc, name, t):
    bones = doc["animations"][name]["bones"]
    ch = {}
    for b in BONES:
        if b not in bones:
            continue
        rot = ex.sample(doc, name, b, "rotation", t) if "rotation" in bones[b] else [0, 0, 0]
        pos = ex.sample(doc, name, b, "position", t) if "position" in bones[b] else [0, 0, 0]
        ch[b] = {"rot": tuple(rot), "pos": tuple(pos)}
    return ch


def arm_bent_frame(world):
    return world["right_forearm"] @ mcrig.T(0, -4, 0)


def euler_zyx_deg(r):
    """ZYX (rotationZYX(z,y,x)) Euler angles in degrees for a rotation matrix."""
    sy = -r[2, 0]
    y = math.asin(max(-1.0, min(1.0, sy)))
    if abs(math.cos(y)) > 1e-6:
        x = math.atan2(r[2, 1], r[2, 2])
        z = math.atan2(r[1, 0], r[0, 0])
    else:
        x = math.atan2(-r[1, 2], r[1, 1])
        z = 0.0
    return [math.degrees(x), math.degrees(y), math.degrees(z)]


def axis_rot(axis, a):
    x, y, z = axis
    c, s, t = math.cos(a), math.sin(a), 1 - math.cos(a)
    return np.array([[t*x*x+c, t*x*y-s*z, t*x*z+s*y], [t*x*y+s*z, t*y*y+c, t*y*z-s*x],
                     [t*x*z-s*y, t*y*z+s*x, t*z*z+c]])


def solve(doc, name, t0, t1, n=121):
    length = doc["animations"][name]["animation_length"]
    times = [length * i / (n - 1) for i in range(n)]
    rolls = []
    eps = 0.004
    for t in times:
        w = mcrig.pose_matrices(channels_at(doc, name, t))
        item = mcrig.item_in_hand_matrix(w["right_forearm"], True)
        h, e, _ = mcrig.axe_frame(item)
        p0 = mcrig.xform(mcrig.item_in_hand_matrix(
            mcrig.pose_matrices(channels_at(doc, name, max(0, t - eps)))["right_forearm"], True), mcrig.AXE_EDGE)
        p1 = mcrig.xform(mcrig.item_in_hand_matrix(
            mcrig.pose_matrices(channels_at(doc, name, min(length, t + eps)))["right_forearm"], True), mcrig.AXE_EDGE)
        v = (np.asarray(p1) - np.asarray(p0)) / (2 * eps)
        v = v - h * float(v @ h)
        speed = float(np.linalg.norm(v))
        if speed < 20.0 or not (t0 <= t <= t1):
            rolls.append(0.0)
            continue
        want = v / speed
        # signed angle about the haft from e to want
        ang = math.atan2(float(np.cross(e, want) @ h), float(e @ want))
        rolls.append(max(-math.radians(60), min(math.radians(60), ang)))
    # smooth + fade in/out at the window edges
    r = np.array(rolls)
    k = np.array([1, 4, 6, 4, 1], float) / 16
    r = np.convolve(np.pad(r, 2, mode="edge"), k, mode="valid")
    keys = []
    for i, t in enumerate(times):
        if i % 4 and i != len(times) - 1:
            continue
        w = mcrig.pose_matrices(channels_at(doc, name, t))
        bent = arm_bent_frame(w)[:3, :3]
        item = mcrig.item_in_hand_matrix(w["right_forearm"], True)
        h, _, _ = mcrig.axe_frame(item)
        h_local = bent.T @ h                       # haft axis in arm-bent space
        rot = axis_rot(h_local / np.linalg.norm(h_local), float(r[i]))
        keys.append((round(t, 4), [round(a, 3) for a in euler_zyx_deg(rot)]))
    return keys, float(np.max(np.abs(np.degrees(r))))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--") and not a.replace(".", "").isdigit()]
    t0 = float(sys.argv[sys.argv.index("--from") + 1]) if "--from" in sys.argv else 0.30
    t1 = float(sys.argv[sys.argv.index("--to") + 1]) if "--to" in sys.argv else 0.62
    dry = "--dry" in sys.argv
    for clip in args:
        path = os.path.join(ASSETS, clip + ".animation.json")
        doc = json.load(open(path, encoding="utf-8"))
        name = next(iter(doc["animations"]))
        # drop numeric filters so the arguments above are not treated as clip names
        keys, peak = solve(doc, name, t0, t1)
        print(f"{clip}: peak wrist roll {peak:.1f} deg over {t0}-{t1}s, {len(keys)} keys")
        if dry:
            continue
        doc["animations"][name]["bones"]["right_item"] = {"rotation": {
            (f"{t:.4f}".rstrip("0").rstrip(".") if t else "0.0"): {"post": v, "lerp_mode": "catmullrom"}
            for t, v in keys}}
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, indent=1)
            f.write("\n")


if __name__ == "__main__":
    main()
