"""KNEAD (SettlerAnimations.KNEAD), authored in Blender. Run headless:

    blender -b --factory-startup --python author_knead.py -- [--fast|--full] [--no-export]

Contract kept (Employment.soundContactOf WORK_KNEAD, MILLER trade):
  length 1.20 s, looping; knead_press when the RIGHT palm bottoms out at
  t = 0.45 s = tick 9 of 24.

Dough on a bench top in front (world space, it does not move with the body).
Push-fold-turn, the way dough is really worked:
  0.00-0.20  heels of both hands set on the near edge; the body rocks back a
             hair (anticipation), hips rise;
  0.20-0.45  the weight goes forward over straight-ish arms: hips drop into
             the knees, the chest drives down, the heels of the hands push the
             dough down and away, decelerating as the dough resists; right
             palm lowest exactly at 0.45 (left a tick later);
  0.45-0.62  continuous pressure: the hands creep, the torso keeps leaning in;
  0.62-0.95  release, rock back, the hands lift and reach for the far edge;
             the left hand gives the dough a quarter turn;
  0.95-1.20  fold the far edge back over toward the belly.
Feet planted in a staggered stance; knees absorb the hip dip.
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
VAR = "v2" in A["rest"]     # KNEAD__v2: two cycles; after the second press the miller dusts flour and pats the dough
CONST, SLUG = ("KNEAD__V2", "knead__v2") if VAR else ("KNEAD", "knead")
L = 1.20
LT = 2 * L if VAR else L
PRESS = 0.45
objs = lk.scene("settler_miller.png")
FEET = (np.array([-3.0, 24.0, 1.8]), np.array([2.9, 24.0, -1.6]))
BENCH_Y = 11.3                    # bench top (model px); the dough sits on it
DOUGH = np.array([0.0, 10.6, -10.0])
lk.box("bench", (-7, BENCH_Y, -14), (14, 24 - BENCH_Y, 7), (0.45, 0.33, 0.2, 1))

ACC, DEC, INOUT = lk.ACC, lk.DEC, lk.INOUT
K = {
    "torso_x": [(0.0, 16.0), (0.20, 13.5), (0.45, 25.0), (0.62, 27.5, *INOUT),
                (0.80, 17.0), (0.95, 19.0), (1.2, 16.0)],
    "torso_y": [(0.0, 1.0), (0.20, 2.0), (0.45, -3.0), (0.62, -3.5), (0.95, 4.0), (1.2, 1.0)],
    "torso_z": [(0.0, 0.0), (0.45, -1.5), (0.95, 1.5), (1.2, 0.0)],
    "root_y": [(0.0, -0.45), (0.20, -0.2), (0.45, -1.15), (0.62, -1.25, *INOUT), (0.82, -0.5), (1.2, -0.45)],
    "root_z": [(0.0, 0.0), (0.20, 0.25), (0.45, -0.8), (0.62, -0.95, *INOUT), (0.90, 0.1), (1.2, 0.0)],
    "root_x": [(0.0, 0.0), (0.45, -0.3), (0.95, 0.35), (1.2, 0.0)],
    "head_nod": [(0.0, 2.0), (0.20, 0.0), (0.45, 5.0), (0.62, 5.5), (0.85, 0.5), (1.2, 2.0)],
}
lk.key(K, cyclic=True)
# heels of the hands (world, model px); DEC into the press = the dough resists
lk.key_vec("rh", [(0.0, (-2.1, 10.0, -8.3)), (0.20, (-2.1, 9.5, -8.5), *lk.ACC2),
                  (0.45, (-2.1, 10.85, -11.0), *INOUT), (0.62, (-2.0, 10.65, -11.2)),
                  (0.80, (-2.4, 8.8, -10.2)), (0.95, (-2.4, 9.9, -11.5), *lk.ACC2),
                  (1.08, (-2.2, 9.4, -10.4)), (1.2, (-2.1, 10.0, -8.3))], cyclic=True)
lk.key_vec("lh", [(0.0, (2.1, 10.0, -8.4)), (0.24, (2.1, 9.5, -8.6), *lk.ACC2),
                  (0.49, (2.0, 10.8, -11.0), *INOUT), (0.64, (2.0, 10.65, -11.3)),
                  (0.80, (2.8, 9.0, -10.6)), (0.92, (1.2, 10.0, -11.3)), (1.0, (0.6, 9.9, -11.0)),
                  (1.1, (1.8, 9.4, -9.8)), (1.2, (2.1, 10.0, -8.4))], cyclic=True)
c = lk.c
if VAR:
    # second cycle, after the 1.65 s press has been held: instead of the fold,
    # the left hand lifts and sprinkles flour over the dough (a quick shake of
    # the fingers), the right hand gives it two light pats, and both hands are
    # back on the base path (frame-0 pose) by 2.40 s
    lk.key({"v": [(0.0, 0.0), (1.80, 0.0, *lk.INOUT), (1.95, 1.0), (2.22, 1.0, *lk.INOUT), (2.40, 0.0),
                  (LT, 0.0)],
            "pat": [(0.0, 0.0), (1.95, 0.0, *lk.ACC2), (2.02, 1.0, *lk.DEC), (2.09, 0.0, *lk.ACC2),
                    (2.16, 1.0, *lk.DEC), (2.24, 0.0), (LT, 0.0)],
            "shake": [(0.0, 0.0), (1.90, 0.0), (1.98, 1.0), (2.16, 1.0), (2.24, 0.0), (LT, 0.0)]}, cyclic=False)
PAT_TOP, PAT_HIT = np.array([-1.6, 9.2, -10.2]), np.array([-1.6, 10.6, -10.3])
SPRINKLE = np.array([1.2, 7.4, -10.4])


def solve(t):
    tv = t % LT
    t %= L
    vw = lk.smoothstep(max(0.0, min(1.0, c("v", tv)))) if VAR else 0.0
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t)),
                    "scale": lk.breath_scale(lk.breath(t, L, phase=0.6), 0.012)}}
    w = mcrig.pose_matrices(ch)
    pitch, yaw = lk.head_aim(w, DOUGH)
    ch["head"] = {"rot": (0.55 * pitch + c("head_nod", t), 0.5 * yaw, 0.0)}
    prev = solve.prev
    rh, lh = lk.cv("rh", t), lk.cv("lh", t)
    if vw > 0:
        import math
        rh = rh * (1 - vw) + (PAT_TOP + (PAT_HIT - PAT_TOP) * c("pat", tv)) * vw
        sh = c("shake", tv) * 0.45 * math.sin(2 * math.pi * 11.0 * tv)
        lh = lh * (1 - vw) + (SPRINKLE + np.array([sh, -abs(sh) * 0.5, 0.0])) * vw
        ch["head"]["rot"] = (ch["head"]["rot"][0] - 3.0 * vw, ch["head"]["rot"][1], 2.0 * vw)
    solve.err = max(lk.arm_ik(ch, w, "right", rh, (-0.8, 0.3, 0.6), prev),
                    lk.arm_ik(ch, w, "left", lh, (0.8, 0.3, 0.6), prev))
    lk.leg_ik(ch, FEET, prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
    vy = lk.lagged_rate(lambda u: c("root_y", u % L), t, lag=0.03)
    ch["cloak"] = {"rot": lk.cloak(4.0, ch["torso"]["rot"][0], rate, root_vy=vy, k_pitch=0.5)}
    return ch


solve.prev = {}
reach = 0.0
for f in range(int(LT * lk.FPS) + 1):
    solve(f / lk.FPS)
    reach = max(reach, solve.err)
times, samples = hsrig.bake(solve, LT, objs)

palm_y = [float(lk.palm(mcrig.pose_matrices(s), "right")[1]) for s in samples]
checks = {
    "length": LT, "loop": True, "press_ticks": [9, 33] if VAR else [9],
    "right_palm_lowest_per_cycle": [lk.extreme_time(times[:72], palm_y[:72], "max")]
    + ([lk.extreme_time(times[72:], palm_y[72:], "max")] if VAR else []),
    "hand_ik_unreached_px_max": round(reach, 3),
    "elbow_at_press": lk.value_at(times, samples, PRESS, "right_forearm")[0],
    "loop_seam": lk.loop_seam(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_knead.py (Blender " + bpy.app.version_string + ")",
        "contract": "KNEAD 1.20 s loop; knead_press when the right palm bottoms out at t=0.45 s = "
                    "tick 9 of 24 (Employment.soundContactOf WORK_KNEAD)",
        "checks": checks}
if VAR:
    meta["variant_of"] = "animation.settler.knead"
    meta["contract"] += "; variant = 2 base cycles (presses 0.45/1.65 s), same frame-0 pose"
keep = (PRESS, L, PRESS + L) if VAR else (PRESS,)
path, doc, report, worst = lk.export(CONST, LT, True, times, samples, keep_times=(0.0,) + keep + (LT,),
                                     meta=meta, write=A["export"],
                                     props=[{"hand": "offhand", "item": "minecraft:sugar", "from": 1.86,
                                             "to": 2.26}] if VAR else None)
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, LT, A)
