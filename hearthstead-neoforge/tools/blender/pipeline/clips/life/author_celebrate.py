"""CELEBRATE (SettlerAnimations.CELEBRATE), authored in Blender. Run headless:

    blender -b --factory-startup --python author_celebrate.py -- [--fast|--full] [--no-export]

Contract kept (SettlerEntity.tickAccents, CHEER_TICK_A/B):
  length 2.0 s, one-shot; cheers at t = 0.45 s and 1.10 s = ticks 9 and 22
  after the trigger; SettlerModel resets every part before applying it, so
  the clip starts and ends on the exact rest pose (all channels zero).

Grounded joy, not a cartoon bounce: a real crouch loads the knees (arms swing
back, chest coils, head drops), then a short hop (2 px) with both fists
thrown overhead peaks exactly on each cheer; take-off decelerates into the
apex, the fall accelerates into touchdown, and the knees absorb every
landing. Between the hops the fists pump down to the shoulders, the torso
turns to share it with the people on either side, and the finish is a small
fist shake that settles back to rest. The feet are IK-planted on the ground
except while airborne, where they tuck.
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
CONST, SLUG = "CELEBRATE", "celebrate"
L = 2.0
CHEERS = (0.45, 1.10)
objs = lk.scene("settler_farmer.png")
FEET = lk.FEET0

ACC, ACC2, DEC, DEC3, INOUT = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT
K = {
    "root_y": [(0.0, 0.0), (0.12, -0.3), (0.30, -2.0, *ACC2), (0.36, -1.1), (0.45, 2.0, *ACC2),
               (0.55, 0.0, *DEC), (0.63, -1.6), (0.85, -0.6), (0.97, -1.8, *ACC2), (1.02, -1.0),
               (1.10, 1.8, *ACC2), (1.20, 0.0, *DEC), (1.28, -1.4), (1.50, -0.35), (1.75, -0.12),
               (2.0, 0.0)],
    "tuck": [(0.0, 0.0), (0.37, 0.0), (0.45, 1.6), (0.53, 0.0), (1.03, 0.0), (1.10, 1.4), (1.18, 0.0),
             (2.0, 0.0)],
    "torso_x": [(0.0, 0.0), (0.12, 2.0), (0.30, 13.0), (0.45, -9.0), (0.63, 5.0), (0.85, 1.5),
                (0.97, 9.0), (1.10, -9.0), (1.28, 4.0), (1.60, -2.0), (1.85, 0.5), (2.0, 0.0)],
    "torso_y": [(0.0, 0.0), (0.30, -2.0), (0.70, 8.0), (1.00, 2.0), (1.40, -8.0), (1.80, -1.0), (2.0, 0.0)],
    "head_x": [(0.0, 0.0), (0.30, 9.0), (0.45, -17.0, *DEC), (0.75, -7.0), (0.97, 3.0), (1.10, -16.0, *DEC),
               (1.50, -5.0), (1.85, 1.0), (2.0, 0.0)],
    "head_y": [(0.0, 0.0), (0.45, 0.0), (0.72, 12.0), (1.10, 2.0), (1.42, -12.0), (1.80, -2.0), (2.0, 0.0)],
    # right arm FK (deg): shoulder pitch / spread, elbow flex
    "ra_x": [(0.0, 0.0), (0.12, 8.0), (0.30, 32.0, *ACC2), (0.45, -164.0, *DEC), (0.60, -158.0),
             (0.85, -98.0), (0.97, -104.0, *ACC2), (1.10, -168.0, *DEC), (1.30, -150.0), (1.45, -78.0),
             (1.70, -38.0), (1.88, -7.0), (2.0, 0.0)],
    "ra_z": [(0.0, 0.0), (0.30, 4.0), (0.45, -28.0), (0.85, -30.0), (1.10, -26.0), (1.45, -10.0),
             (1.80, -3.0), (2.0, 0.0)],
    "re": [(0.0, 0.0), (0.30, -12.0), (0.45, -6.0), (0.63, -20.0), (0.85, -105.0), (0.97, -95.0),
           (1.10, -6.0), (1.30, -16.0), (1.45, -104.0), (1.53, -80.0), (1.61, -108.0), (1.76, -50.0),
           (1.90, -6.0), (2.0, 0.0)],
    "la_x": [(0.0, 0.0), (0.15, 8.0), (0.33, 30.0, *ACC2), (0.48, -158.0, *DEC), (0.63, -152.0),
             (0.88, -94.0), (1.00, -100.0, *ACC2), (1.13, -162.0, *DEC), (1.33, -146.0), (1.52, -62.0),
             (1.72, -30.0), (1.90, -5.0), (2.0, 0.0)],
    "la_z": [(0.0, 0.0), (0.33, -4.0), (0.48, 28.0), (0.88, 28.0), (1.13, 26.0), (1.52, 9.0),
             (1.82, 3.0), (2.0, 0.0)],
    "le": [(0.0, 0.0), (0.33, -10.0), (0.48, -6.0), (0.66, -22.0), (0.88, -100.0), (1.00, -92.0),
           (1.13, -6.0), (1.33, -18.0), (1.55, -96.0), (1.75, -38.0), (1.92, -4.0), (2.0, 0.0)],
}
lk.key(K, cyclic=False)
c = lk.c


def solve(t):
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, c("root_y", t), 0.0)},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), 0.0)},
          "head": {"rot": (c("head_x", t), c("head_y", t), 0.0)},
          "right_arm": {"rot": (c("ra_x", t), 0.0, c("ra_z", t))},
          "right_forearm": {"rot": (c("re", t), 0.0, 0.0)},
          "left_arm": {"rot": (c("la_x", t), 0.0, c("la_z", t))},
          "left_forearm": {"rot": (c("le", t), 0.0, 0.0)}}
    lift = max(0.0, c("root_y", t)) + c("tuck", t)
    feet = tuple(f + np.array([0.0, -lift, 0.4 * c("tuck", t)]) for f in FEET)
    lk.leg_ik(ch, feet, solve.prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u), t)
    vy = lk.lagged_rate(lambda u: c("root_y", u), t, lag=0.04)
    ch["cloak"] = {"rot": lk.cloak(0.0, ch["torso"]["rot"][0], rate, root_vy=vy, k_pitch=0.4, k_vy=1.4)}
    return lk.edge_rest(ch, t, L)


solve.prev = {}
times, samples = hsrig.bake(solve, L, objs)

ry = [s["root"]["pos"][1] for s in samples]
checks = {
    "length": L, "loop": False, "cheer_ticks": [9, 22],
    "hop_apex": [lk.extreme_time(times[:40], ry[:40], "max"), lk.extreme_time(times[40:80], ry[40:80], "max")],
    "arms_at_cheers_deg": [lk.value_at(times, samples, t, "right_arm")[0] for t in CHEERS],
    "start_end_rest_max": lk.ends_at_rest(samples),
    "knee_max_deg": round(max(s["right_shin"]["rot"][0] for s in samples), 1),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_celebrate.py (Blender " + bpy.app.version_string + ")",
        "contract": "CELEBRATE 2.0 s one-shot; cheers t=0.45/1.10 s = ticks 9/22 (SettlerEntity CHEER_TICK_A/B); "
                    "starts and ends at rest (SettlerModel resets every part first)",
        "checks": checks}
path, doc, report, worst = lk.export(CONST, L, False, times, samples, keep_times=(0.0,) + CHEERS + (L,),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, L, A)
