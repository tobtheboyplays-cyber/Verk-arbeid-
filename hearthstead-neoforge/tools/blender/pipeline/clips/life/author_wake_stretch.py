"""WAKE_STRETCH (SettlerAnimations.WAKE_STRETCH), authored in Blender. Run headless:

    blender -b --factory-startup --python author_wake_stretch.py -- [--fast|--full] [--no-export]

Contract kept (SettlerEntity WAKE_YAWN_TICK = 24, EV_WAKE from RestAtNightGoal):
  length 2.6 s, one-shot; the yawn sound plays at t = 1.20 s = tick 24 of 52,
  which is where the head is thrown furthest back (mouth open) at the top
  of the stretch. The clip is additive over the standing idle, so it now
  starts and ends on exact rest (the old clip started at an 8 deg arm offset).

Beats: groggy slump (head drops, shoulders round, knees soften) -> the arms
come up with bent elbows, fists passing the shoulders -> they press up into a
wide V as the chest arches back and lifts, the head tips back into the yawn
peak at 1.20 s -> a trembling hold with a slow side bend one way then the
other -> release: the arms fall with bent elbows, the body slumps forward
past neutral and settles, awake.
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
CONST, SLUG = "WAKE_STRETCH", "wake_stretch"
L = 2.6
YAWN = 1.20
objs = lk.scene("settler_lumberer.png")
FEET = lk.FEET0
ACC, ACC2, DEC, DEC3, INOUT = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT
K = {
    "root_y": [(0.0, 0.0), (0.35, -0.55), (0.80, -0.2), (1.20, 0.0), (1.80, 0.0), (2.25, -0.75, *DEC),
               (2.45, -0.1), (2.6, 0.0)],
    "chest": [(0.0, 0.0), (0.35, -0.3), (1.20, 0.55, *DEC), (1.80, 0.45), (2.25, -0.25), (2.6, 0.0)],
    "torso_x": [(0.0, 0.0), (0.35, 7.0), (0.80, -5.0), (1.20, -14.0, *DEC), (1.50, -12.5), (1.80, -13.0),
                (2.25, 7.0, *DEC), (2.45, 1.0), (2.6, 0.0)],
    "torso_z": [(0.0, 0.0), (1.20, 0.0), (1.48, -4.5), (1.72, 3.0), (1.95, 0.0), (2.6, 0.0)],
    "head_x": [(0.0, 0.0), (0.35, 13.0), (0.85, -6.0), (1.20, -24.0, *DEC), (1.45, -21.0), (1.75, -18.0),
               (2.25, 11.0), (2.45, 2.0), (2.6, 0.0)],
    "head_z": [(0.0, 0.0), (0.35, -3.0), (1.20, 5.0), (1.50, -3.0), (1.75, 2.0), (2.25, 0.0), (2.6, 0.0)],
    "ra_x": [(0.0, 0.0), (0.35, 4.0), (0.75, -80.0), (1.20, -168.0, *DEC), (1.50, -165.0), (1.80, -160.0, *ACC2),
             (2.15, -42.0), (2.40, 5.0), (2.6, 0.0)],
    "ra_z": [(0.0, 0.0), (0.75, -10.0), (1.20, -24.0), (1.80, -26.0), (2.15, -8.0), (2.6, 0.0)],
    "re": [(0.0, 0.0), (0.35, -10.0), (0.75, -100.0), (1.20, -8.0, *DEC), (1.50, -4.0), (1.80, -10.0),
           (2.05, -62.0), (2.35, -14.0), (2.6, 0.0)],
    "la_x": [(0.0, 0.0), (0.40, 4.0), (0.83, -78.0), (1.28, -164.0, *DEC), (1.55, -162.0), (1.85, -158.0, *ACC2),
             (2.20, -36.0), (2.45, 4.0), (2.6, 0.0)],
    "la_z": [(0.0, 0.0), (0.83, 10.0), (1.28, 26.0), (1.85, 24.0), (2.20, 6.0), (2.6, 0.0)],
    "le": [(0.0, 0.0), (0.40, -10.0), (0.83, -96.0), (1.28, -6.0, *DEC), (1.55, -4.0), (1.85, -12.0),
           (2.10, -58.0), (2.40, -12.0), (2.6, 0.0)],
}
lk.key(K, cyclic=False)
c = lk.c


def tremble(t):
    """Muscle shake while the stretch is held (8 Hz, enveloped)."""
    env = lk.smoothstep((t - 1.22) / 0.12) * (1 - lk.smoothstep((t - 1.62) / 0.18))
    return env * math.sin(2 * math.pi * 8.0 * t)


def solve(t):
    tr = tremble(t)
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, c("root_y", t), 0.0)},
          "torso": {"rot": (c("torso_x", t), 0.0, c("torso_z", t)), "pos": (0.0, c("chest", t), 0.0)},
          "head": {"rot": (c("head_x", t), 0.0, c("head_z", t))},
          "right_arm": {"rot": (c("ra_x", t) + 1.4 * tr, 0.0, c("ra_z", t) - 0.8 * tr)},
          "right_forearm": {"rot": (c("re", t), 0.0, 0.0)},
          "left_arm": {"rot": (c("la_x", t) - 1.2 * tr, 0.0, c("la_z", t) + 0.8 * tr)},
          "left_forearm": {"rot": (c("le", t), 0.0, 0.0)}}
    lk.leg_ik(ch, FEET, solve.prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u), t)
    vy = lk.lagged_rate(lambda u: c("root_y", u), t, lag=0.03)
    ch["cloak"] = {"rot": lk.cloak(0.0, ch["torso"]["rot"][0], rate, root_vy=vy, k_pitch=0.4)}
    return lk.edge_rest(ch, t, L)


solve.prev = {}
times, samples = hsrig.bake(solve, L, objs)
hx = [s["head"]["rot"][0] for s in samples]
checks = {
    "length": L, "loop": False, "yawn_tick": 24,
    "head_furthest_back": lk.extreme_time(times, hx, "min"),
    "start_end_rest_max": lk.ends_at_rest(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_wake_stretch.py (Blender " + bpy.app.version_string + ")",
        "contract": "WAKE_STRETCH 2.6 s one-shot; yawn t=1.20 s = tick 24 of 52 (SettlerEntity.WAKE_YAWN_TICK); "
                    "starts and ends at rest",
        "checks": checks}
path, doc, report, worst = lk.export(CONST, L, False, times, samples, keep_times=(0.0, YAWN, L),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, L, A)
