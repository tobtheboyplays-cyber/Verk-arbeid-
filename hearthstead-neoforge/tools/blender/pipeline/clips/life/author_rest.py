"""REST (SettlerAnimations.REST), authored in Blender. Run headless:

    blender -b --factory-startup --python author_rest.py -- [--fast|--full] [--no-export]

Contract kept (SettlerEntity restState: RESTING and not sleeping, by the fire):
  length 6.0 s, looping. The SEATED base contract is kept: root posVec
  (0, -8, 0) (hips 4 px above the ground, sitting on it). Head tracking is
  damped to 0.25 for RESTING in SettlerModel, so the head keys stay small.

What changed: the old clip had straight legs stuck out at -86 deg with the
arms at -58 deg ("arms on knees" that never reached a knee). With knees the
settler now sits properly: feet planted in front, knees up, forearms resting
over the knees with the hands hanging loose past them, the back rounded
(the 4 px hip height reads as a low log or stone seat by the fire).
Life: two slow breaths per loop (chest scale + a little rise of the
shoulders), the knees drift apart and back, the gaze wanders over the fire,
and once per loop a drowsy nod that is caught and eased back up.
"""

import json
import math
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
CONST, SLUG = "REST", "rest"
L = 6.0
objs = lk.scene("settler_courier.png")
FEET = (np.array([-3.8, 24.0, -6.4]), np.array([3.8, 24.0, -6.8]))
lk.box("log_seat", (-6, 20, -3), (12, 4, 7), (0.36, 0.26, 0.16, 1))   # preview scenery: the 4 px seat
ACC, ACC2, DEC, DEC3, INOUT = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT
K = {
    "torso_x": [(0.0, 22.0), (1.5, 21.0), (2.8, 23.5), (3.5, 25.5), (3.75, 22.0, *DEC), (4.8, 21.5), (6.0, 22.0)],
    "torso_y": [(0.0, 0.0), (1.4, -3.0), (2.6, -2.0), (4.6, 3.0), (6.0, 0.0)],
    "torso_z": [(0.0, 0.0), (1.8, 1.2), (4.2, -1.0), (6.0, 0.0)],
    "head_x": [(0.0, -6.0), (1.2, -5.0), (2.5, -4.0), (3.2, 6.0), (3.45, 9.0), (3.62, -2.0, *DEC),
               (4.1, -5.5), (5.2, -7.0), (6.0, -6.0)],
    "head_y": [(0.0, 0.0), (0.8, -3.0), (1.9, -9.0), (2.5, -7.0), (3.4, -2.0), (4.4, 6.0), (5.3, 7.0),
               (6.0, 0.0)],
    "head_z": [(0.0, 0.0), (1.9, -2.0), (3.3, 3.0), (3.8, 0.5), (5.0, 1.5), (6.0, 0.0)],
    "knee_out": [(0.0, 0.18), (2.2, 0.45), (4.2, 0.10), (6.0, 0.18)],
    "hand_drift": [(0.0, 0.0), (2.0, 0.6), (3.5, 0.9), (3.8, 0.2), (5.0, -0.3), (6.0, 0.0)],
}
lk.key(K, cyclic=True)
c = lk.c


def solve(t):
    t %= L
    b = lk.breath(t, L / 2.0, phase=0.05)
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, -8.0, 0.0)},
          "torso": {"rot": (c("torso_x", t) - 1.2 * b, c("torso_y", t), c("torso_z", t)),
                    "pos": (0.0, 0.12 * b, 0.0), "scale": lk.breath_scale(b, 0.02)},
          "head": {"rot": (c("head_x", t) + 0.6 * b, c("head_y", t), c("head_z", t))}}
    prev = solve.prev
    lk.leg_ik(ch, FEET, prev, knee=(0.0, -1.0, -0.45), knee_out=c("knee_out", t))
    w = mcrig.pose_matrices(ch)
    dr = c("hand_drift", t)
    for side, sx in (("right", 1.0), ("left", -1.0)):
        knee = mcrig.xform(w[side + "_leg"], (0, 6, -2.0))       # top-front of the knee
        target = knee + np.array([sx * 1.4, 0.9 + 0.3 * b, -3.6 - 0.3 * dr])
        lk.arm_ik(ch, w, side, target, (-sx * 0.9, 0.8, 0.5), prev)
    ch["cloak"] = {"rot": (-5.0 + 0.35 * (ch["torso"]["rot"][0] - 22.0), 0.0, -0.3 * c("torso_z", t))}
    return ch


solve.prev = {}
for f in range(int(L * lk.FPS) + 1):
    solve(f / lk.FPS)
times, samples = hsrig.bake(solve, L, objs)
checks = {
    "length": L, "loop": True, "root_pos": [0.0, -8.0, 0.0],
    "loop_seam": lk.loop_seam(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
    "knee_flex_deg": [lk.value_at(times, samples, 0.0, "right_shin")[0],
                      lk.value_at(times, samples, 0.0, "left_shin")[0]],
    "elbow_deg": lk.value_at(times, samples, 0.0, "right_forearm")[0],
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_rest.py (Blender " + bpy.app.version_string + ")",
        "contract": "REST 6.0 s loop; SEATED base kept: root posVec (0,-8,0); knees up, forearms on the knees",
        "checks": checks}
path, doc, report, worst = lk.export(CONST, L, True, times, samples, keep_times=(0.0, L),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, L, A, loops=1, cams=lk.cameras(side=((-3.6, -0.6, 0.8), (0.0, -0.3, 0.55)),
                                        front34=((-2.3, -2.8, 1.1), (0.0, -0.3, 0.55))))
