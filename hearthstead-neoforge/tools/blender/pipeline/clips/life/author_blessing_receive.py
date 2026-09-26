"""BLESSING_RECEIVE (SettlerAnimations.BLESSING_RECEIVE), authored in Blender. Run headless:

    blender -b --factory-startup --python author_blessing_receive.py -- [--fast|--full] [--no-export]

Contract kept (SettlerModel absolute one-shot priority, SettlerEntity EV_BLESSING_RECEIVE):
  length 1.60 s, one-shot; SettlerModel resets every part it owns, then
  applies this clip, so it starts and ends on the exact rest pose. Beat
  timing as before: 0.15 s anticipation crouch, hands on the heart by 0.50 s
  and barely drifting to 0.75 s, the acceptance lift at 0.85-0.95 s,
  recovery dropping past neutral at 1.15 s, a small counter-settle at 1.40 s.

The hands are IK'd onto the heart (the settler's left chest, palms
overlapped, riding the chest as it breathes and lifts); the acceptance lift
is carried by the spine and chest (torso lifts, head rises), not by a root
hop, so the feet stay planted and the knees take the crouch and the settle.
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
CONST, SLUG = "BLESSING_RECEIVE", "blessing_receive"
L = 1.60
objs = lk.scene("settler_mayor.png")
FEET = lk.FEET0
ACC, ACC2, DEC, DEC3, INOUT, LIN = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT, lk.LIN
K = {
    "root_y": [(0.0, 0.0), (0.15, -0.85, *ACC2), (0.35, -0.35), (0.50, -0.12), (0.75, -0.15),
               (0.85, 0.0), (0.95, 0.0), (1.15, -0.65), (1.40, -0.05), (1.6, 0.0)],
    "chest": [(0.0, 0.0), (0.15, -0.2), (0.50, 0.15), (0.75, 0.2), (0.85, 0.6, *DEC), (0.95, 0.55),
              (1.15, -0.25), (1.40, 0.05), (1.6, 0.0)],
    "torso_x": [(0.0, 0.0), (0.15, 8.0, *ACC2), (0.35, 3.0), (0.50, -3.0), (0.75, -3.5), (0.85, -9.0, *DEC),
                (0.95, -8.0), (1.15, 4.0), (1.40, -1.0), (1.6, 0.0)],
    "torso_y": [(0.0, 0.0), (0.35, -3.0), (0.50, -5.0), (0.75, -5.0), (0.95, -2.0), (1.15, 1.5), (1.6, 0.0)],
    "head_x": [(0.0, 0.0), (0.15, 6.0), (0.35, 12.0), (0.50, 10.0), (0.75, 9.0), (0.85, -13.0, *DEC),
               (0.95, -12.0), (1.15, 5.0), (1.40, -1.0), (1.6, 0.0)],
    "head_y": [(0.0, 0.0), (0.35, -5.0), (0.75, -4.0), (0.90, 0.0), (1.15, 2.0), (1.6, 0.0)],
    # hands: 0 = FK (rest / anticipation / follow-through), 1 = on the heart
    "ik": [(0.0, 0.0), (0.14, 0.0, *ACC2), (0.50, 1.0), (0.95, 1.0, *INOUT), (1.28, 0.0), (1.6, 0.0)],
    "ra_x": [(0.0, 0.0), (0.15, 6.0), (0.40, -30.0), (1.00, -40.0), (1.20, -8.0), (1.40, 5.0), (1.6, 0.0)],
    "ra_z": [(0.0, 0.0), (0.15, -5.0), (0.40, 6.0), (1.20, 2.0), (1.40, -2.0), (1.6, 0.0)],
    "la_x": [(0.0, 0.0), (0.15, 5.0), (0.40, -22.0), (1.00, -30.0), (1.20, -6.0), (1.40, 4.0), (1.6, 0.0)],
    "la_z": [(0.0, 0.0), (0.15, 5.0), (0.40, -4.0), (1.20, -2.0), (1.40, 2.0), (1.6, 0.0)],
    "press": [(0.0, 0.0), (0.50, 0.0), (0.75, 0.15), (0.88, 0.5), (0.95, 0.45), (1.1, 0.0), (1.6, 0.0)],
}
lk.key(K, cyclic=False)
c = lk.c
HEART_R = np.array([1.4, -7.4, -3.5])     # right palm over the heart (torso space)
HEART_L = np.array([2.9, -6.3, -3.2])     # left palm just below it


def solve(t):
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, c("root_y", t), 0.0)},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), 0.0), "pos": (0.0, c("chest", t), 0.0)},
          "head": {"rot": (c("head_x", t), c("head_y", t), 0.0)}}
    w = max(0.0, min(1.0, c("ik", t)))
    p = c("press", t)
    ik = {}
    lk.arm_ik_local(ik, "right", HEART_R + np.array([0, 0, 0.4 * p]), (-0.9, 0.7, 0.3), solve.prev)
    lk.arm_ik_local(ik, "left", HEART_L + np.array([0, 0, 0.3 * p]), (0.9, 0.7, 0.3), solve.prev)
    fk = {"right_arm": (c("ra_x", t), 0.0, c("ra_z", t)), "left_arm": (c("la_x", t), 0.0, c("la_z", t)),
          "right_forearm": (0.0, 0.0, 0.0), "left_forearm": (0.0, 0.0, 0.0)}
    for b, f in fk.items():
        ch[b] = {"rot": tuple(a + (q - a) * w for a, q in zip(f, ik[b]["rot"]))}
    lk.leg_ik(ch, FEET, solve.prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u), t)
    vy = lk.lagged_rate(lambda u: c("root_y", u), t, lag=0.03)
    ch["cloak"] = {"rot": lk.cloak(0.0, ch["torso"]["rot"][0], rate, root_vy=vy, k_pitch=0.4)}
    solve.heart = (w, ik)
    return lk.edge_rest(ch, t, L)


solve.prev = {}
times, samples = hsrig.bake(solve, L, objs)

err = 0.0
for t, s in zip(times, samples):
    if 0.5 <= t <= 0.95:
        w = mcrig.pose_matrices(s)
        tr = np.linalg.inv(w["torso"])
        err = max(err, float(np.linalg.norm(mcrig.xform(tr, lk.palm(w, "right")) - HEART_R)) - 0.4)
checks = {
    "length": L, "loop": False,
    "right_palm_to_heart_px_max_0.50_0.95": round(max(err, 0.0), 3),
    "start_end_rest_max": lk.ends_at_rest(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_blessing_receive.py (Blender " + bpy.app.version_string + ")",
        "contract": "BLESSING_RECEIVE 1.60 s one-shot, full priority (SettlerModel resets first); "
                    "hands on the heart 0.50-0.75 s, acceptance lift 0.85-0.95 s; starts/ends at rest",
        "checks": checks}
path, doc, report, worst = lk.export(CONST, L, False, times, samples, keep_times=(0.0, 0.15, 0.5, 0.75, 0.85, L),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, L, A)
