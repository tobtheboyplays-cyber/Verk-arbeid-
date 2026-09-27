"""Carry-pack clearance audit (pure numpy; no Blender, no game).

For every exported settler clip (assets/hearthstead/animations/settler/*.json) and
every job pack shape (CarryPackRules / CarryPackLayer geometry, transcribed below),
samples the clip at 30 Hz, poses the rig exactly like the runtime (mcrig), puts the
pack in the carried-sack frame (torso child, pivot (0,-10.5,2.5), fill/tier scale,
lateral cap, load lean) and reports every frame where a pack box penetrates an arm,
the head, the hanging hair volume, a leg, or the ground plane.

    python tools/blender/pipeline/pack_audit.py [--scale 1.05] [--json out.json]
        [--clips chop,mine_pick] [--shapes SACK,BASKET] [--min 0.35]

Penetration depth is the SAT minimum overlap in model px. Anything under --min
(default 0.35 px, i.e. hidden by the texel grid) is ignored.
Keep PACK_BOXES / PACK_PIVOT / LATERAL_HALF in sync with CarryPackRules + CarryPackLayer.
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
ANIM_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")

# Pack-frame boxes (x0,y0,z0,x1,y1,z1) in px at scale 1 -- CarryPackLayer.render.
PACK_BOXES = {
    "SATCHEL": [(-2.5, 2.5, 0.0, 2.5, 7.0, 1.5), (-2.6, 2.3, 1.3, 2.6, 4.6, 1.9)],
    "SACK": [(-2.5, 0.0, 1.0, 2.5, 3.0, 5.0), (-3.5, 2.0, 0.0, 3.5, 8.0, 6.0)],
    "BASKET": [(-3.0, 1.0, 0.0, 3.0, 8.0, 6.0)],
    "CRATE": [(-3.0, 1.0, 0.0, 3.0, 8.0, 6.0)],
    "BUNDLE": [(-3.5, -1.0, 0.0, 3.5, 7.8, 3.2)],
    "FRAME": [(-3.5, -1.0, 0.0, 3.5, 9.0, 4.6)],
}
# Content items (approximate AABBs around the item slots, tier 3).
CONTENT_BOXES = {
    "SACK": [(-3.2, -3.1, 1.0, 3.2, 1.3, 5.2)],
    "BASKET": [(-3.0, -2.0, 0.6, 3.0, 1.0, 5.4)],
    "CRATE": [(-3.0, -2.0, 0.6, 3.0, 1.0, 5.4)],
    "FRAME": [(-4.4, -1.6, 0.0, 4.4, 8.4, 5.4)],
}
# --geometry fixed: the carry pack lane's 26 Sep geometry (CarryPackLayer after the fix).
FIXED_BOXES = {
    "SATCHEL": PACK_BOXES["SATCHEL"],
    "SACK": PACK_BOXES["SACK"],
    "BASKET": [(-3.0, 1.0, 0.0, 3.0, 8.0, 6.0)],
    "CRATE": [(-3.0, 1.0, 0.0, 3.0, 8.0, 6.0)],
    "BUNDLE": [(-3.5, 0.3, 0.0, 3.5, 7.9, 3.4)],
    "FRAME": [(-3.5, 0.6, 0.0, 3.5, 9.0, 4.6)],
}
# CarryPackLayer SACK_SLOTS / OPEN_SLOTS / FRAME_SLOTS: x, y, z, yaw, tilt (deg).
FIXED_SLOTS = {
    "SACK": [(-0.9, 0.8, 4.0, 15, 35), (0.9, 0.6, 4.4, -20, 40), (0.0, 0.4, 3.6, 5, 30)],
    "BASKET": [(-0.9, 1.8, 3.6, 15, 30), (0.9, 1.6, 4.2, -20, 35), (0.0, 1.4, 3.0, 5, 25)],
    "CRATE": [(-0.9, 1.8, 3.6, 15, 30), (0.9, 1.6, 4.2, -20, 35), (0.0, 1.4, 3.0, 5, 25)],
    "FRAME": [(0.0, 6.8, 2.9, 0, 0), (0.0, 4.7, 2.9, 4, 0), (0.0, 2.6, 2.9, -4, 0)],
}
SPRITE_PX = 16 * 0.30     # SPRITE_SCALE
GEOMETRY = ["legacy"]


def content_obbs(pm, shape):
    """Exact item boxes of the fixed layer, in world (model) space."""
    out = []
    for i, (x, y, z, yaw, tilt) in enumerate(FIXED_SLOTS.get(shape, [])):
        m = pm @ mcrig.T(x, y, z) @ mcrig.mat4(mcrig.ry(yaw * mcrig.DEG)) \
            @ mcrig.mat4(mcrig.rz(math.pi)) @ mcrig.mat4(mcrig.rx(tilt * mcrig.DEG))
        if shape == "FRAME":
            b = (-3.2, -2.0, -2.6, 3.2, 2.0, 2.6)      # 8 px block * (0.8, 0.5, 0.65)
        else:
            m = m @ mcrig.mat4(mcrig.rz((10 if i == 0 else -8) * mcrig.DEG))
            h = SPRITE_PX / 2
            b = (-h, -h, -0.25, h, h, 0.25)
        out.append((m, b))
    return out


# Authored parts (SettlerModel): the courier's canvas sack uses the SACK mesh at the sack
# pivot with the courier scale; the lumberer's frame is a torso child at (0,-10.5,4).
AUTHORED = {
    "COURIER": ((0.0, -10.5, 2.5), PACK_BOXES["SACK"], 0.08),
    "LUMBER": ((0.0, -10.5, 4.0), [(-4.5, 0.0, 0.0, 4.5, 10.0, 1.0), (-4.0, 9.0, 0.0, 4.0, 10.0, 4.0),
                                    (-0.5, 2.0, 6.0, 0.5, 9.0, 7.0), (-2.9, 2.5, 1.0, 2.9, 8.9, 6.8)], 0.22),
}

HALF_WIDTH = {"COURIER": 3.5, "LUMBER": 4.5, "SATCHEL": 2.5, "SACK": 3.5, "BASKET": 3.0, "CRATE": 3.0, "BUNDLE": 3.5, "FRAME": 3.5}
LEAN = {"COURIER": 0.08, "LUMBER": 0.22, "SATCHEL": 0.0, "SACK": 0.08, "BASKET": 0.14, "CRATE": 0.14, "BUNDLE": 0.14, "FRAME": 0.14}
PACK_PIVOT = [0.0, -10.5, 2.5]
LATERAL_HALF = [4.6]      # TORSO_HALF_WIDTH - ARM_CLEARANCE (max pack half-width, px)
VERTICAL_CAP = [99.0]     # CarryPackRules.verticalScale cap (height never grows past this)

# Body boxes that can meet the pack: (part, x0,y0,z0,x1,y1,z1) part-local px.
BODY = [
    ("right_arm", -2, -2, -2, 2, 4, 2), ("right_forearm", -2, 0, -2, 2, 6, 2),
    ("left_arm", -2, -2, -2, 2, 4, 2), ("left_forearm", -2, 0, -2, 2, 6, 2),
    ("head", -4, -8, -4, 4, 0, 4),
    ("hair", -4.6, -8.4, 3.4, 4.4, 0.6, 5.4),     # SettlerAccessoryLayer "volume" (head child)
    ("right_leg", -2.4, 0, -2.3, 2.4, 6, 2.3), ("right_shin", -2.4, 0, -2.3, 2.4, 6, 2.3),
    ("left_leg", -2.4, 0, -2.3, 2.4, 6, 2.3), ("left_shin", -2.4, 0, -2.3, 2.4, 6, 2.3),
]


def load_clips(only=None):
    out = {}
    for path in sorted(glob.glob(os.path.join(ANIM_DIR, "*.animation.json"))):
        stem = os.path.basename(path).replace(".animation.json", "")
        if only and stem not in only:
            continue
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        for name, anim in doc["animations"].items():
            out[stem] = (doc, name, anim)
    return out


_KEYS = {}


def channels_at(doc, name, anim, t):
    ch = {}
    for bone, kinds in anim.get("bones", {}).items():
        if bone not in mcrig.PARTS:
            continue
        ch[bone] = {}
        for kind in kinds:
            if kind not in ("rotation", "position"):
                continue
            ck = (name, bone, kind)
            comps = _KEYS.get(ck)
            if comps is None:
                keys = ex.load_keys(doc, name, bone, kind)
                comps = [[(k[0], k[1][c]) for k in keys] for c in range(3)]
                _KEYS[ck] = comps
            v = [ex.evaluate(c, t) for c in comps]
            ch[bone]["rot" if kind == "rotation" else "pos"] = tuple(v)
    return ch


def box_obb(m4, b):
    x0, y0, z0, x1, y1, z1 = b
    c = np.array([(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2, 1.0])
    h = np.array([(x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2])
    R = m4[:3, :3]
    scales = np.linalg.norm(R, axis=0)
    axes = R / np.where(scales == 0, 1, scales)
    return (m4 @ c)[:3], axes, h * scales


def sat_depth(a, b):
    """Minimum overlap along the 15 SAT axes (0 means separated)."""
    ca, Aa, ha = a
    cb, Ab, hb = b
    d = cb - ca
    if float(d @ d) > float(ha @ ha) + float(hb @ hb) + 2.0 * math.sqrt(float(ha @ ha) * float(hb @ hb)):
        return 0.0
    best = 1e9
    axes = [Aa[:, i] for i in range(3)] + [Ab[:, i] for i in range(3)]
    for i in range(3):
        for j in range(3):
            v = np.cross(Aa[:, i], Ab[:, j])
            n = np.linalg.norm(v)
            if n > 1e-6:
                axes.append(v / n)
    for L in axes:
        ra = sum(ha[k] * abs(Aa[:, k] @ L) for k in range(3))
        rb = sum(hb[k] * abs(Ab[:, k] @ L) for k in range(3))
        ov = ra + rb - abs(d @ L)
        if ov <= 0:
            return 0.0
        best = min(best, ov)
    return best


def pack_matrix(world, shape, scale, pitch=0.0):
    if shape == "LUMBER":
        return world["torso"] @ mcrig.T(*AUTHORED["LUMBER"][0])
    if shape == "COURIER" and GEOMETRY[0] != "fixed":
        return world["torso"] @ mcrig.T(*PACK_PIVOT) @ np.diag([scale, scale, scale, 1.0])
    sx = min(scale, LATERAL_HALF[0] / HALF_WIDTH[shape])
    frame = world["torso"] @ mcrig.T(*PACK_PIVOT) @ mcrig.mat4(mcrig.rx(pitch))
    return frame @ np.diag([sx, min(scale, VERTICAL_CAP[0]), scale, 1.0])


def pose_world(ch, lean_rad):
    if lean_rad:
        tr = ch.setdefault("torso", {}).get("rot", (0, 0, 0))
        ch["torso"]["rot"] = (tr[0] + math.degrees(lean_rad), tr[1], tr[2])
        hd = ch.setdefault("head", {}).get("rot", (0, 0, 0))
        ch["head"]["rot"] = (hd[0] - 0.6 * math.degrees(lean_rad), hd[1], hd[2])
    return mcrig.pose_matrices(ch)


def geometry_of(shape):
    if shape in AUTHORED:
        return list(AUTHORED[shape][1]), False
    if GEOMETRY[0] == "fixed":
        return list(FIXED_BOXES[shape]), True
    return list(PACK_BOXES[shape]) + CONTENT_BOXES.get(shape, []), False


def audit_clip(doc, name, anim, shape, scale, contents=True, step=1 / 30.0, min_depth=0.35,
               loaded=True):
    L = float(anim.get("animation_length", 0.0)) or step
    n = max(1, int(round(L / step)))
    hits = {}
    lean = LEAN[shape] * min(1.0, scale / 1.05) if loaded else 0.0
    if shape == "LUMBER":
        scale = 1.0
    for i in range(n + 1):
        t = min(L, i * step)
        world = pose_world(channels_at(doc, name, anim, t), lean)
        pm = pack_matrix(world, shape, scale)
        if shape in AUTHORED:
            boxes = list(AUTHORED[shape][1])
            items = []
        elif GEOMETRY[0] == "fixed":
            boxes = list(FIXED_BOXES[shape])
            items = content_obbs(pm, shape) if contents else []
        else:
            boxes = list(PACK_BOXES[shape]) + (CONTENT_BOXES.get(shape, []) if contents else [])
            items = []
        pobbs = [box_obb(pm, b) for b in boxes] + [box_obb(m, b) for m, b in items]
        corners = [(pm, b) for b in boxes] + items
        for part, *b in BODY:
            m = world["head"] if part == "hair" else world[part]
            bo = box_obb(m, b)
            d = max(sat_depth(p, bo) for p in pobbs)
            if d > min_depth:
                rec = hits.setdefault(part, [0.0, None, 0])
                rec[2] += 1
                if d > rec[0]:
                    rec[0], rec[1] = d, round(t, 3)
        low = -1e9
        for mm, b in corners:
            x0, y0, z0, x1, y1, z1 = b
            for cx in (x0, x1):
                for cy in (y0, y1):
                    for cz in (z0, z1):
                        low = max(low, (mm @ np.array([cx, cy, cz, 1.0]))[1])
        if low - 24.0 > min_depth:
            rec = hits.setdefault("ground", [0.0, None, 0])
            rec[2] += 1
            if low - 24.0 > rec[0]:
                rec[0], rec[1] = low - 24.0, round(t, 3)
    return {k: {"depth_px": round(v[0], 2), "worst_t": v[1], "frames": v[2], "of": n + 1}
            for k, v in hits.items()}


def _work(job):
    stem, shapes, scale, contents, min_depth, geometry, lateral, vcap = job
    GEOMETRY[0] = geometry
    LATERAL_HALF[0] = lateral
    VERTICAL_CAP[0] = vcap
    doc, name, anim = load_clips({stem})[stem]
    per = {}
    for shape in shapes:
        sc = 1.0 if shape == "SATCHEL" else scale
        r = audit_clip(doc, name, anim, shape, sc, contents, min_depth=min_depth, loaded=shape != "SATCHEL")
        if r:
            per[shape] = r
    return stem, per


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scale", type=float, default=1.05)
    ap.add_argument("--clips", default="")
    ap.add_argument("--shapes", default="")
    ap.add_argument("--min", type=float, default=0.35)
    ap.add_argument("--json", default="")
    ap.add_argument("--no-contents", action="store_true")
    ap.add_argument("--pivot", default="")
    ap.add_argument("--lateral", type=float, default=0.0)
    ap.add_argument("--vcap", type=float, default=0.0)
    ap.add_argument("--geometry", default="legacy", choices=["legacy", "fixed"])
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    if a.pivot:
        PACK_PIVOT[:] = [float(v) for v in a.pivot.split(",")]
    GEOMETRY[0] = a.geometry
    if a.geometry == "fixed":
        VERTICAL_CAP[0] = 1.10
        LATERAL_HALF[0] = 4.2
    if a.vcap:
        VERTICAL_CAP[0] = a.vcap
    if a.lateral:
        LATERAL_HALF[0] = a.lateral
    stems = sorted(load_clips(set(a.clips.split(",")) if a.clips else None))
    shapes = a.shapes.split(",") if a.shapes else list(PACK_BOXES) + list(AUTHORED)
    jobs = [(stem, shapes, a.scale, not a.no_contents, a.min, GEOMETRY[0], LATERAL_HALF[0],
             VERTICAL_CAP[0]) for stem in stems]
    report = {}
    import multiprocessing as mp
    with mp.Pool(max(1, min(a.workers, len(jobs)))) as pool:
        for stem, per in pool.imap_unordered(_work, jobs):
            if per:
                report[stem] = per
    for stem, per in sorted(report.items()):
        for shape, r in per.items():
            parts = ", ".join(f"{k} {v['depth_px']}px@{v['worst_t']}s ({v['frames']}/{v['of']})"
                              for k, v in sorted(r.items(), key=lambda kv: -kv[1]["depth_px"]))
            print(f"{stem:32s} {shape:8s} {parts}")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=1)
    print(f"clips={len(stems)} flagged={len(report)}")


if __name__ == "__main__":
    main()
