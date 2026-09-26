"""INN_WELCOME (SettlerAnimations.INN_WELCOME), authored in Blender. SUPERSEDED (26 Sep): the shipped clip is
now authored by clips/tavern/author_remakes.py; running this overwrites it. Run headless:

    blender -b --factory-startup --python author_inn_welcome.py -- [--fast|--full] [--no-export]

Contract kept (SettlerModel innkeeper WELCOME + village-social WELCOME):
  length 1.8 s, one-shot, sampled from the server social clock. The innkeeper
  path resets arms/torso/root/legs/head, sets the head to the tracked guest
  and lerps in/out over a 36-tick envelope; the village path (WELCOME_TICKS =
  32 = 1.6 s) has NO envelope, so the clip starts on rest and is essentially
  back on rest by 1.6 s (last 0.2 s is only a tiny settle). Head keys stay
  small because the innkeeper head is already aimed at the guest.

Beats: a small knee dip and the face lifting toward the guest (anticipation),
the right hand rises beside the head (elbow bent, palm out), two unhurried
waves pivoting at the elbow (the chest opens and tilts into them), the hand
comes down while the head gives a welcoming nod, settle. The free hand drifts
out a little with overlapping lag.
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lifekit as lk  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import numpy as np  # noqa: E402
import bpy  # noqa: E402

A = lk.args()
CONST, SLUG = "INN_WELCOME", "inn_welcome"
L = 1.8
objs = lk.scene("settler_innkeeper.png")
FEET = lk.FEET0
ACC, ACC2, DEC, DEC3, INOUT = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT
K = {
    "root_y": [(0.0, 0.0), (0.15, -0.45), (0.45, 0.0), (1.20, -0.1), (1.48, -0.35), (1.62, -0.08), (1.8, 0.0)],
    "torso_x": [(0.0, 0.0), (0.15, 2.5), (0.45, -2.5), (1.20, -1.5), (1.48, 3.0), (1.64, 0.3), (1.8, 0.0)],
    "torso_y": [(0.0, 0.0), (0.40, -4.0), (1.25, -3.0), (1.62, -0.3), (1.8, 0.0)],
    "torso_z": [(0.0, 0.0), (0.55, 1.5), (0.85, -0.5), (1.20, 1.0), (1.60, 0.0), (1.8, 0.0)],
    "head_x": [(0.0, 0.0), (0.18, -5.0), (0.55, -3.0), (1.25, -2.5), (1.46, 5.0, *DEC), (1.64, 0.5), (1.8, 0.0)],
    "head_y": [(0.0, 0.0), (0.25, -2.0), (1.30, -3.0), (1.64, -0.3), (1.8, 0.0)],
    "head_z": [(0.0, 0.0), (0.60, 3.0), (0.95, 1.0), (1.25, 3.0), (1.62, 0.0), (1.8, 0.0)],
    # 0 = arms on the rest pose (FK zero), 1 = the right hand on its IK wave path
    "ik": [(0.0, 0.0), (0.08, 0.0, *ACC2), (0.30, 1.0), (1.30, 1.0, *INOUT), (1.60, 0.0), (1.8, 0.0)],
    "la_x": [(0.0, 0.0), (0.45, -6.0), (1.00, -4.0), (1.40, -5.0), (1.70, 0.0), (1.8, 0.0)],
    "la_z": [(0.0, 0.0), (0.50, 5.0), (1.00, 3.0), (1.45, 3.5), (1.72, 0.0), (1.8, 0.0)],
    "le": [(0.0, 0.0), (0.55, -14.0), (1.10, -10.0), (1.72, 0.0), (1.8, 0.0)],
}
lk.key(K, cyclic=False)
# the waving palm (torso space); swings pivot at the elbow
lk.key_vec("rh", [(0.0, (-6.3, -3.0, -3.0)), (0.35, (-10.8, -14.2, -2.4), *DEC), (0.52, (-11.6, -16.4, -1.8)),
                  (0.70, (-9.9, -17.2, -2.0)), (0.88, (-13.0, -15.6, -1.4)), (1.06, (-10.0, -17.2, -2.0)),
                  (1.24, (-12.7, -15.9, -1.5)), (1.45, (-8.6, -8.0, -3.4)), (1.8, (-6.3, -2.0, -2.0))],
           cyclic=False)
c = lk.c


def solve(t):
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, c("root_y", t), 0.0)},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t))},
          "head": {"rot": (c("head_x", t), c("head_y", t), c("head_z", t))},
          "left_arm": {"rot": (c("la_x", t), 0.0, c("la_z", t))},
          "left_forearm": {"rot": (c("le", t), 0.0, 0.0)}}
    w = max(0.0, min(1.0, c("ik", t)))
    lk.arm_ik_local(ch, "right", lk.cv("rh", t), (-1.0, 0.6, 0.35), solve.prev)
    for b in ("right_arm", "right_forearm"):
        ch[b]["rot"] = tuple(v * w for v in ch[b]["rot"])
    lk.leg_ik(ch, FEET, solve.prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u), t)
    yrate = lk.lagged_rate(lambda u: c("torso_y", u), t)
    ch["cloak"] = {"rot": lk.cloak(0.0, 0.0, rate, yrate, k_yaw=0.08)}
    return lk.edge_rest(ch, t, L)


solve.prev = {}
times, samples = hsrig.bake(solve, L, objs)

palm_y = [float(lk.palm(mcrig.pose_matrices(s), "right")[1]) for s in samples]
checks = {
    "length": L, "loop": False,
    "hand_highest": lk.extreme_time(times, palm_y, "min"),
    "start_end_rest_max": lk.ends_at_rest(samples),
    "pose_at_1_6s_right_arm": lk.value_at(times, samples, 1.6, "right_arm"),
    "root_at_1_6s": lk.value_at(times, samples, 1.6, "root", "pos"),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_inn_welcome.py (Blender " + bpy.app.version_string + ")",
        "contract": "INN_WELCOME 1.8 s one-shot (innkeeper: 36-tick envelope; village WELCOME: 32 ticks, "
                    "no envelope -> back on rest by 1.6 s); starts and ends at rest",
        "checks": checks}
path, doc, report, worst = lk.export(CONST, L, False, times, samples, keep_times=(0.0, 1.6, L),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, L, A)
