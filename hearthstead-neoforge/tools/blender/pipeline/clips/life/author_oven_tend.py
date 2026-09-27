"""OVEN_TEND (SettlerAnimations.OVEN_TEND), authored in Blender. Run headless:

    blender -b --factory-startup --python author_oven_tend.py -- [--fast|--full] [--no-export]

Contract kept (Employment.soundContactOf WORK_OVEN, BAKER trade):
  length 1.60 s, looping; oven_slide onset at t = 0.25 s = tick 5 of 32, the
  start of the 0.25-0.50 s peel push into the oven mouth.

Both hands on a long peel handle (mimed, no prop). The handle is a straight
line: the right hand sits on it at the back, the left hand 5 px further along
toward the oven mouth, so the two hands always agree about where the peel is.
  0.00-0.25  ready -> small draw back, weight onto the back foot (anticipation);
  0.25-0.50  drive: the hips go forward onto the front knee, the chest leans
             in, both hands shoot the peel into the mouth, easing into the stop;
  0.50-0.70  hold in the mouth while the loaf settles on the hearth stone;
  0.70-0.78  the snap: the peel is yanked back out from under the loaf with a
             quick roll of the forearms (the baker's signature);
  0.78-1.10  withdraw; the body leans away from the heat, the head turns off
             the glow;
  1.10-1.60  settle back to ready.
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
VAR = "v2" in A["rest"]     # OVEN_TEND__v2: two cycles; after the second load the baker wipes the heat off his brow
CONST, SLUG = ("OVEN_TEND__V2", "oven_tend__v2") if VAR else ("OVEN_TEND", "oven_tend")
L = 1.60
LT = 2 * L if VAR else L
ONSET = 0.25
IN = 0.50
objs = lk.scene("settler_baker.png")
FEET = (np.array([-3.0, 24.0, 2.0]), np.array([2.9, 24.0, -2.2]))
MOUTH = np.array([-0.5, 9.0, -22.0])
lk.box("oven", (-8, 4, -30), (16, 20, 10), (0.5, 0.42, 0.38, 1))
lk.box("oven_mouth", (-3.5, 7, -20.2), (7, 4, 0.4), (0.12, 0.07, 0.05, 1))

ACC, ACC2, DEC, DEC3, INOUT, LIN = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT, lk.LIN
K = {
    "torso_x": [(0.0, 10.0), (0.20, 7.0, *ACC2), (0.25, 7.5), (0.50, 27.0), (0.68, 28.0, *ACC),
                (0.78, 19.0, *DEC), (1.05, -5.0), (1.30, 6.0), (1.6, 10.0)],
    "torso_y": [(0.0, 0.0), (0.20, 4.0), (0.50, -3.0), (0.70, -3.0), (0.78, 0.0), (1.05, 6.0), (1.6, 0.0)],
    "root_z": [(0.0, 0.0), (0.20, 0.55, *ACC2), (0.25, 0.5), (0.50, -1.8), (0.68, -1.85, *ACC),
               (0.78, -0.9, *DEC), (1.05, 0.65), (1.35, 0.1), (1.6, 0.0)],
    "root_y": [(0.0, -0.4), (0.20, -0.55), (0.50, -1.0), (0.68, -1.05), (1.05, -0.3), (1.6, -0.4)],
    "root_x": [(0.0, 0.0), (0.20, -0.35), (0.50, 0.35), (1.05, -0.2), (1.6, 0.0)],
    "twist": [(0.0, 0.0), (0.68, 0.0, *ACC), (0.74, 22.0, *DEC), (0.90, 4.0), (1.2, 0.0), (1.6, 0.0)],
    "look_away": [(0.0, 0.0), (0.72, 0.0), (0.98, 1.0), (1.20, 0.8), (1.5, 0.0), (1.6, 0.0)],
}
lk.key(K, cyclic=True)
# right (rear) hand on the handle, world px
lk.key_vec("rh", [(0.0, (-2.2, 9.4, -2.8)), (0.20, (-2.5, 9.3, -1.5), *ACC2),
                  (0.25, (-2.45, 9.3, -1.6)), (0.50, (-1.5, 8.0, -7.9)),
                  (0.68, (-1.4, 8.1, -8.1), *ACC), (0.78, (-1.7, 8.6, -5.2), *DEC),
                  (1.05, (-2.5, 9.6, -2.2)), (1.30, (-2.3, 9.4, -3.1)), (1.6, (-2.2, 9.4, -2.8))],
           cyclic=True)
c = lk.c
GRIP_SPAN = 4.2


if VAR:
    # second cycle, as the peel comes out (base 0.85-1.05 s): the right hand
    # leaves the handle and drags the back of the wrist across the forehead,
    # the head dips into it, a relieved breath out, and the hand is back on
    # the handle for the frame-0 ready pose at 3.20 s
    lk.key({"v": [(0.0, 0.0), (2.40, 0.0, *lk.INOUT), (2.62, 1.0), (2.98, 1.0, *lk.INOUT), (3.18, 0.0),
                  (LT, 0.0)],
            "sweep": [(0.0, 0.0), (2.60, 0.0, *lk.INOUT), (2.92, 1.0), (LT, 1.0)]}, cyclic=False)
BROW_A = np.array([-4.2, -5.6, -4.6])    # head space: back of the right wrist at the right temple
BROW_B = np.array([1.8, -5.9, -4.6])     # ... dragged across to the left


def solve(t):
    tv = t % LT
    t %= L
    vw = lk.smoothstep(max(0.0, min(1.0, c("v", tv)))) if VAR else 0.0
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), 0.0),
                    "scale": lk.breath_scale(lk.breath(t, L, phase=0.4), 0.012)}}
    w = mcrig.pose_matrices(ch)
    pitch, yaw = lk.head_aim(w, MOUTH)
    away = c("look_away", t)
    ch["head"] = {"rot": ((0.6 * pitch) * (1 - away) - 6.0 * away + 9.0 * vw,
                          (0.6 * yaw * (1 - away) + 16.0 * away) * (1 - 0.6 * vw), -3.0 * away * (1 - vw))}
    if vw > 0:
        tr = ch["torso"]["rot"]
        ch["torso"]["rot"] = (tr[0] + (2.0 - tr[0]) * vw * 0.7, tr[1], tr[2])
        w = mcrig.pose_matrices(ch)
    prev = solve.prev
    r = lk.cv("rh", t)
    d = MOUTH - r
    d /= np.linalg.norm(d)
    lh = r + d * GRIP_SPAN + np.array([1.9, -0.4, 0.0])
    tw = c("twist", t)
    rr = r
    if vw > 0:
        sw = c("sweep", tv)
        rr = r * (1 - vw) + mcrig.xform(w["head"], BROW_A * (1 - sw) + BROW_B * sw) * vw
    solve.err = max(lk.arm_ik(ch, w, "right", rr, (-0.8 + 0.3 * vw, 0.7 + 0.3 * vw, 0.3), prev,
                              twist=-tw * (1 - vw)),
                    lk.arm_ik(ch, w, "left", lh, (0.7, 0.8, 0.3), prev, twist=tw))
    lk.leg_ik(ch, FEET, prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
    yrate = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
    vz = lk.lagged_rate(lambda u: c("root_z", u % L), t, lag=0.03)
    ch["cloak"] = {"rot": lk.cloak(4.0 - 0.8 * vz, ch["torso"]["rot"][0], rate, yrate, k_pitch=0.4)}
    solve.handle = (r, lh)
    return ch


solve.prev = {}
reach = 0.0
for f in range(int(LT * lk.FPS) + 1):
    solve(f / lk.FPS)
    reach = max(reach, solve.err)
times, samples = hsrig.bake(solve, LT, objs)

rz = [float(lk.cv("rh", t)[2]) for t in times]
spd = [abs(b - a) * 60 for a, b in zip(rz, rz[1:])] + [0.0]
checks = {
    "length": LT, "loop": True, "oven_slide_onset_ticks": [5, 37] if VAR else [5],
    "push_starts_after_t": lk.extreme_time(times[:40], rz[:40], "max"),
    "hands_deepest": lk.extreme_time(times[:97], rz[:97], "min"),
    "push_speed_peak_t": lk.extreme_time(times[:40], spd[:40], "max"),
    "hand_ik_unreached_px_max": round(reach, 3),
    "loop_seam": lk.loop_seam(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_oven_tend.py (Blender " + bpy.app.version_string + ")",
        "contract": "OVEN_TEND 1.60 s loop; oven_slide onset t=0.25 s = tick 5 of 32, push "
                    "0.25-0.50 s into the oven mouth (Employment.soundContactOf WORK_OVEN)",
        "checks": checks}
if VAR:
    meta["variant_of"] = "animation.settler.oven_tend"
    meta["contract"] += "; variant = 2 base cycles (onsets 0.25/1.85 s), same frame-0 pose"
keep = (ONSET, IN, L, ONSET + L, IN + L) if VAR else (ONSET, IN)
path, doc, report, worst = lk.export(CONST, LT, True, times, samples, keep_times=(0.0,) + keep + (LT,),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, LT, A)
