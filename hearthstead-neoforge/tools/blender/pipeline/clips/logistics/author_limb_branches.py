"""LIMB_BRANCHES (Lumberer), authored in Blender. Run headless:

    blender -b --factory-startup --python author_limb_branches.py -- [--fast|--full]

Contract kept from SettlerAnimations.LIMB_BRANCHES / LumbererWorkGoal:
  length 1.3 s looping (LIMB_DURATION 26 ticks); two axe contacts per loop at
  t = 0.30 s (tick 6) and t = 0.95 s (tick 19): WorkSoundSync plays CHOP (pitched
  up) on exactly those ticks. Stationary full-body work loop.

Motion: stooped over the felled trunk, feet wide and planted, knees bent, the
left hand braced on the left thigh, the axe in the right hand. Each strike is a
short, fast diagonal flick across the body at shin height onto a branch stub:
a quick cock up beside the head (anticipation), an accelerating chop that is
fastest at the contact, a hard stop with a small recoil, then the axe lifts
clear. The body shifts a little to the left between the two cuts (the second
stub sits further along the trunk). Right arm goals are solved to FK at key
poses in TORSO space (so the stoop tilts the swing plane down to the trunk)
and interpolated as joint curves.
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
CONST = "LIMB_BRANCHES"
SLUG = CONST.lower()
L = 1.3
C1, C2 = 0.30, 0.95

FOOT_R = np.array([-4.0, 24.0, 1.6])
FOOT_L = np.array([3.8, 24.0, -0.6])

objs = lkit.scene("settler_lumberer.png", os.path.join(lkit.REF, "iron_axe.png"))

IN = ("CUBIC", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
INQ = ("QUAD", "EASE_IN")
LIN = ("LINEAR", "AUTO")
K = {
    "pelvis_y":   [(0.0, -2.4), (0.18, -2.1), (0.22, -2.2, *IN), (C1, -3.0, *OUTS), (0.40, -2.8),
                   (0.62, -2.3), (0.83, -2.1), (0.87, -2.2, *IN), (C2, -3.0, *OUTS), (1.05, -2.8),
                   (L, -2.4)],
    "pelvis_x":   [(0.0, -0.3), (C1, -0.5), (0.62, 0.4), (C2, 0.6), (1.15, 0.0), (L, -0.3)],
    "pelvis_yaw": [(0.0, 4.0), (0.20, 7.0), (0.22, 7.0, *IN), (C1, -2.0, *OUTS), (0.55, 1.0),
                   (0.85, 3.0), (0.87, 3.0, *IN), (C2, -7.0, *OUTS), (1.15, 0.0), (L, 4.0)],
    "spine_x":    [(0.0, 42.0), (0.20, 36.0), (0.22, 36.5, *IN), (C1, 47.0, *OUTS), (0.36, 45.0),
                   (0.60, 40.0), (0.85, 37.0), (0.87, 37.5, *IN), (C2, 48.0, *OUTS), (1.01, 46.0),
                   (L, 42.0)],
    "spine_yaw":  [(0.0, 6.0), (0.20, 12.0), (0.22, 12.0, *IN), (C1, -2.0, *OUTS), (0.55, 2.0),
                   (0.85, 6.0), (0.87, 6.0, *IN), (C2, -9.0, *OUTS), (1.15, 1.0), (L, 6.0)],
    "head_nod":   [(0.0, 6.0), (C1, 10.0, *OUTS), (0.45, 6.0), (C2, 10.0, *OUTS), (1.1, 6.0), (L, 6.0)],
}
# QA 2026-09-26 (owner: stooped poses read as "head by the knees"): the spine
# folds 17 deg less and the knees take 1.7 px more of the drop. The original fold
# is kept as old_* curves only to re-express the torso-space arm goals, so the
# blade still lands on the SAME world points at the same ticks.
K["old_spine_x"] = K["spine_x"]
K["old_pelvis_y"] = K["pelvis_y"]
K["spine_x"] = [(k[0], k[1] - 17.0, *k[2:]) for k in K["spine_x"]]
K["pelvis_y"] = [(k[0], k[1] - 1.7, *k[2:]) for k in K["pelvis_y"]]
for prop, keys in K.items():
    hsrig.key_curve(prop, keys, cyclic=True, length=L)
c = hsrig.ctrl
TORSO0 = np.array([0.0, 12.0, 0.0])


def _torso_world(t, old):
    sp, py = ("old_spine_x", "old_pelvis_y") if old else ("spine_x", "pelvis_y")
    ch = {"root": {"rot": (0.0, c("pelvis_yaw", t), 0.0), "pos": (c("pelvis_x", t), c(py, t), 0.0)},
          "torso": {"rot": (c(sp, t), c("spine_yaw", t), 0.0)}}
    return mcrig.pose_matrices(ch)["torso"]


def _reexpress(t, goal):
    """Torso-local goal authored against the old fold -> same world goal, new fold."""
    hand, hdir, tip = goal
    mo, mn = _torso_world(t, True), _torso_world(t, False)
    inv = np.linalg.inv(mn)
    h = mcrig.xform(inv, mcrig.xform(mo, hand))
    d = mn[:3, :3].T @ (mo[:3, :3] @ np.asarray(hdir, float))
    tp = None if tip is None else tuple(mcrig.xform(inv, mcrig.xform(mo, tip)))
    return tuple(h), tuple(d), tp


def body(t):
    return {"root": {"rot": (0.0, c("pelvis_yaw", t), 0.0), "pos": (c("pelvis_x", t), c("pelvis_y", t), 0.0)},
            "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), 0.0)}}


# right hand goals in TORSO space: fist (arm-local y 10), haft dir (knob->head), blade tip
COCK = ((-7.5, -12.0, -2.5), (0.15, -0.75, 0.64), None)
DOWN = ((-5.0, -8.0, -6.5), (-0.10, -0.40, -0.91), None)
HIT1 = ((-2.0, -4.2, -6.2), (-0.55, 0.20, -0.81), (-4.5, -2.6, -12.5))
HIT2 = ((-1.4, -4.0, -6.4), (-0.60, 0.22, -0.77), (-3.5, -2.4, -12.8))
REC = ((-2.6, -4.8, -5.8), (-0.45, 0.10, -0.89), None)
LIFT = ((-5.0, -7.0, -6.0), (-0.10, -0.20, -0.97), None)
GOALS = [(0.0, LIFT, None), (0.18, COCK, INQ), (0.24, DOWN, LIN), (C1, HIT1, OUTS), (0.36, REC, None),
         (0.55, LIFT, None), (0.83, COCK, INQ), (0.89, DOWN, LIN), (C2, HIT2, OUTS), (1.01, REC, None),
         (1.18, LIFT, None)]
arm_keys = {k: [] for k in ("arm_r_x", "arm_r_y", "arm_r_z", "elbow_r", "fore_r_twist")}
seed = [-60.0, 10.0, 5.0, -50.0, 0.0]
LOG = []
GOALS = [(t, _reexpress(t, g), e) for t, g, e in GOALS]
for t, (hand, hdir, tip), ease in GOALS:
    best = None
    for s0 in (seed, [-60, 10, 5, -50, 0], [-120, -10, 10, -60, 0], [-40, 20, -20, -30, 0]):
        sol, err = mcrig.solve_arm_goal({}, "right", np.array(hand) + TORSO0, np.array(hdir), s0,
                                        tip_goal=None if tip is None else np.array(tip) + TORSO0)
        err2 = err + 0.0005 * float(np.sum((np.array(sol) - np.array(seed)) ** 2))
        if best is None or err2 < best[2]:
            best = (sol, err, err2)
    sol, err = best[0], best[1]
    seed = sol
    LOG.append({"t": t, "sol": [round(v, 1) for v in sol], "cost": round(err, 3)})
    for name, v in zip(arm_keys, sol):
        e = ease
        if name == "elbow_r" and ease == INQ:
            e = ("QUART", "EASE_IN")
        arm_keys[name].append((t, v, *e) if e else (t, v))
for name, keys in arm_keys.items():
    keys.append((L, keys[0][1]))
    hsrig.key_curve(name, keys, cyclic=True, length=L)
print("GOALS", json.dumps(LOG))


def arms(t):
    ch = body(t)
    ch["right_arm"] = {"rot": (c("arm_r_x", t), c("arm_r_y", t), c("arm_r_z", t))}
    ch["right_forearm"] = {"rot": (c("elbow_r", t), c("fore_r_twist", t), 0.0)}
    return ch


def blade(w):
    return mcrig.xform(mcrig.item_in_hand_matrix(w["right_forearm"]), mcrig.AXE_TIP)


STUB1 = blade(mcrig.pose_matrices(arms(C1)))
STUB2 = blade(mcrig.pose_matrices(arms(C2)))
TRUNK_Y = max(STUB1[1], STUB2[1]) + 1.2          # stub roots sit just below the cut
lkit.box_object("prop:trunk", [((-24, TRUNK_Y, min(STUB1[2], STUB2[2]) - 4.0), (48, 24 - TRUNK_Y, 5.5))],
                lkit.BARK, None)
for i, s in enumerate((STUB1, STUB2)):
    lkit.box_object(f"prop:stub{i}", [((s[0] - 0.6, s[1] - 0.2, s[2] - 0.6), (1.2, TRUNK_Y - s[1] + 0.4, 1.2))],
                    lkit.OAK, None)
print("STUBS", STUB1.round(2).tolist(), STUB2.round(2).tolist())
prev = {}


def solve(t):
    t %= L
    ch = arms(t)
    pv = lkit.vel(lambda u: c("spine_x", u), t - 0.05, L)
    ch["cloak"] = {"rot": (max(-10.0, min(30.0, 4.0 + 0.4 * c("spine_x", t) - 0.05 * pv)), 0.0, 0.0)}
    target = lkit.eased_target([(0.0, STUB1), (0.55, STUB2), (1.12, STUB1)], t, 0.15)
    lkit.head_aim(ch, target, w_pitch=0.5, w_yaw=0.6, nod=c("head_nod", t))
    lkit.legs_ik(ch, (FOOT_R, FOOT_L), prev, knee=(0.0, 0.0, -1.0), knee_out=0.3)
    # left palm braced on the front of the left thigh, just above the knee
    w = mcrig.pose_matrices(ch)
    brace = mcrig.xform(w["left_leg"], (0.3, 4.2, -2.6))
    solve.brace_err = lkit.arm_ik_world(ch, "left", brace, (0.9, 0.3, 0.5), prev, lower=5.5)
    return ch


times, samples = hsrig.bake(solve, L, objs)
tips, foot, brace = [], 0.0, 0.0
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    tips.append(blade(w))
    for side, f in (("right", FOOT_R), ("left", FOOT_L)):
        foot = max(foot, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - f)))
    brace = max(brace, float(np.linalg.norm(lkit.palm(w, "left", 5.5) - mcrig.xform(w["left_leg"], (0.3, 4.2, -2.6)))))
tips = np.array(tips)
sp = np.linalg.norm(np.diff(tips, axis=0), axis=1) * hsrig.FPS
f1, f2 = int(round(C1 * 60)), int(round(C2 * 60))
checks = {"length_s": L, "loop": True, "contacts_t": [C1, C2], "contact_ticks": [6, 19],
          "blade_speed_into_c1": round(float(sp[f1 - 1]), 1), "blade_speed_after_c1": round(float(sp[f1 + 1]), 1),
          "blade_speed_into_c2": round(float(sp[f2 - 1]), 1), "blade_speed_after_c2": round(float(sp[f2 + 1]), 1),
          "stubs": [STUB1.round(2).tolist(), STUB2.round(2).tolist()],
          "left_brace_err_px_max": round(brace, 3), "foot_slide_px_max": round(foot, 3),
          "seam_max": lkit.seam(samples), "goal_solves": LOG}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_limb_branches.py (Blender "
                  + bpy.app.version_string + ")",
        "contract": "LIMB_BRANCHES 1.3 s loop; axe contacts t=0.30 s (tick 6) and 0.95 s (tick 19) of 26 (CHOP)",
        "checks": checks}
if A["export"]:
    lkit.export(CONST, L, True, times, samples, keep_times=(C1, C2), meta=meta)
if A["fast"] or A["full"]:
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.6, -0.5, 1.0), (0.0, -0.4, 0.7), lens=40),
                                "front34": hsrig.camera("cam_front34", (-2.4, -2.8, 1.5), (0.0, -0.3, 0.7), lens=40)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (-1.0, -3.2, 1.6), (0.0, -0.4, 0.6), lens=40, n=6)
