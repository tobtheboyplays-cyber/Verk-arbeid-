"""WALK_CARRY_ITEM, authored in Blender (locomotion clip). Run headless:

    blender -b --factory-startup --python author_walk_carry_item.py -- [--fast|--full]

Contract kept from SettlerAnimations.WALK_CARRY_ITEM / SettlerModel:
  length 1.0 s loop; played by animateWalk() on the per-entity DISTANCE clock
  (COLLECTING_ITEMS with a real OFFHAND item), so the clip carries
  hearthstead_meta.blocks_per_cycle for stride matching. No sound tick.
  SettlerModel afterwards scales rightArm.xRot by 0.75, sinks the torso 0.3 px
  with gait drive, and forces leftArm.xRot = -|xRot| (front carry). The left
  arm here is therefore authored with NEGATIVE x throughout (never crosses 0),
  so that override is a no-op.

Motion: a short, careful walk with ONE log (the real OFFHAND item) cradled
against the ribs in the left arm, elbow bent, forearm under it; the free
right hand carries the tool low with a shortened swing. The load is modest,
so: a slightly shortened stride, a little extra knee, the torso wedged ~7 deg
forward and counter-leaning a touch AWAY from the load side (right), the
cradled log bobbing a fraction behind the pelvis. Planted-foot IK (treadmill,
same model as the lead's author_walk.py).
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lkit  # noqa: E402
from lkit import hsrig, mcrig  # noqa: E402

A = lkit.args()
CONST = "WALK_CARRY_ITEM"
SLUG = CONST.lower()
L = 1.0
ST = dict(excursion=11.0, duty=0.60, lift=2.0, drop=1.0, base=-0.45, sway=0.45, pelvis_yaw=3.0,
          spine_yaw=4.0, lean=7.0, lean_dip=1.6, arm=13.0, elbow=(-12.0, -26.0), cloak=4.0,
          side_lean=-2.0)
HIP_X, FOOT_X = 2.6, 2.9

objs = lkit.scene("settler_lumberer.png", lkit.os.path.join(lkit.REF, "iron_axe.png"))
log = lkit.attach_block_in_hand(objs, "left", lkit.BARK, "prop:log")


def smooth(x):
    return x * x * (3 - 2 * x)


def foot_target(u):
    e, d = ST["excursion"], ST["duty"]
    u %= 1.0
    if u < d:
        return -e / 2 + e * u / d, 24.0
    s = (u - d) / (1 - d)
    return e / 2 - e * smooth(s), 24.0 - ST["lift"] * math.sin(math.pi * min(1.0, s ** 0.8))


def cyc(prop, pts):
    hsrig.key_curve(prop, [(t * L, v) for t, v in pts], cyclic=True, length=L)


lo, hi = ST["base"] - ST["drop"], ST["base"]
cyc("pelvis_y", [(0.0, lo + 0.25 * ST["drop"]), (0.07, lo), (0.30, hi), (0.5, lo + 0.25 * ST["drop"]),
                 (0.57, lo), (0.80, hi), (1.0, lo + 0.25 * ST["drop"])])
cyc("pelvis_x", [(0.0, 0.0), (0.25, -ST["sway"]), (0.5, 0.0), (0.75, ST["sway"]), (1.0, 0.0)])
cyc("pelvis_yaw", [(0.0, -ST["pelvis_yaw"]), (0.25, 0.0), (0.5, ST["pelvis_yaw"]), (0.75, 0.0),
                   (1.0, -ST["pelvis_yaw"])])
cyc("spine_yaw", [(0.0, ST["spine_yaw"]), (0.25, 0.0), (0.5, -ST["spine_yaw"]), (0.75, 0.0),
                  (1.0, ST["spine_yaw"])])
ld = ST["lean_dip"]
cyc("spine_x", [(0.0, ST["lean"]), (0.1, ST["lean"] + ld), (0.3, ST["lean"] - 0.2 * ld), (0.5, ST["lean"]),
                (0.6, ST["lean"] + ld), (0.8, ST["lean"] - 0.2 * ld), (1.0, ST["lean"])])
a = ST["arm"]
cyc("arm_r_x", [(0.0, a * 0.8), (0.05, a), (0.3, 0.0), (0.55, -a), (0.8, 0.0), (1.0, a * 0.8)])
cyc("elbow_r", [(0.0, ST["elbow"][0]), (0.55, ST["elbow"][1]), (1.0, ST["elbow"][0])])
c = hsrig.ctrl
prev = {}
CRADLE = np.array([2.4, -3.9, -4.3])    # left fist (torso-local) under the log, against the ribs


def solve(t):
    t %= L
    u = t / L
    ch = {"root": {"rot": (0.0, c("pelvis_yaw", t), 0.0),
                   "pos": (c("pelvis_x", t), c("pelvis_y", t), 0.0)},
          "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), ST["side_lean"])}}
    ch["right_arm"] = {"rot": (c("arm_r_x", t), 0.0, 4.0)}
    ch["right_forearm"] = {"rot": (c("elbow_r", t), 0.0, 0.0)}
    yaw_net = c("pelvis_yaw", t) + c("spine_yaw", t)
    ch["head"] = {"rot": (-0.7 * c("spine_x", t) + 1.5, -0.85 * yaw_net, 0.0)}
    vy = lkit.vel(lambda q: c("pelvis_y", q), t - 0.05, L)
    ch["cloak"] = {"rot": (ST["cloak"] + max(-8, min(8, 1.6 * vy)), 0.0, 0.0)}
    # cradled log: rides the body, lagging the vertical bob by a fraction of a pixel
    lag = 0.18 * lkit.vel(lambda q: c("pelvis_y", q), t - 0.04, L) / 20.0
    lkit.arm_ik_local(ch, "left", CRADLE + np.array([0.0, lag, 0.0]), (1.0, 0.55, 0.25), prev, lower=5.0)
    world = mcrig.pose_matrices(ch)
    inv_root = np.linalg.inv(world["root"])
    for side, sign, phase in (("right", -1, 0.0), ("left", 1, 0.5)):
        z, y = foot_target(u - phase)
        fl = mcrig.xform(inv_root, np.array([sign * FOOT_X, y, z]))
        pole = inv_root[:3, :3] @ np.array([0.1 * sign, 0.0, -1.0])
        r, flex, _ = mcrig.two_bone(np.array([sign * HIP_X, -12.0, 0.0]), fl, mcrig.THIGH,
                                    mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
        ch[side + "_leg"] = {"rot": tuple(hsrig.euler_deg_continuous(r, prev.get(side)))}
        prev[side] = ch[side + "_leg"]["rot"]
        ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
    return ch


times, samples = hsrig.bake(solve, L, objs)
slip = 0.0
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    for side, sign, phase in (("right", -1, 0.0), ("left", 1, 0.5)):
        z, y = foot_target(t / L - phase)
        slip = max(slip, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - np.array([sign * FOOT_X, y, z]))))
bpc = ST["excursion"] / ST["duty"] / 16.0
rg = lkit.ranges(samples, lkit.ARM_BONES)
checks = {"length_s": L, "loop": True, "sole_max_error_px": round(slip, 3), "blocks_per_cycle": round(bpc, 4),
          "left_arm_x_max_deg (must stay < 0 for SettlerModel's -|x| front-carry override)": rg["left_arm"]["max"][0],
          "arm_ranges": rg, "seam_max": lkit.seam(samples),
          "frame0": {b: [round(v, 2) for v in samples[0][b]["rot"]] for b in lkit.ARM_BONES}}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_walk_carry_item.py (Blender "
                  + bpy.app.version_string + ")",
        "contract": "WALK_CARRY_ITEM 1.0 s loop, distance-clocked locomotion (animateWalk)",
        "blocks_per_cycle": round(bpc, 4), "checks": checks}
if A["export"]:
    lkit.export(CONST, L, True, times, samples, keep_times=(0.5,), meta=meta)
if A["fast"] or A["full"]:
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.6, -0.3, 1.1), (0.0, 0.0, 0.92), lens=50),
                                "front34": hsrig.camera("cam_front34", (-2.2, -2.4, 1.5), (0.0, 0.0, 1.0), lens=45)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (-1.2, -3.0, 1.3), (0.0, 0.0, 1.0), lens=50, n=6)
