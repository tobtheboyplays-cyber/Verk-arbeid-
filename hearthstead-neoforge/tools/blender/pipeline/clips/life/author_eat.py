"""EAT (SettlerAnimations.EAT), authored in Blender. SUPERSEDED (26 Sep): the shipped clip is
now authored by clips/tavern/author_remakes.py; running this overwrites it. Run headless:

    blender -b --factory-startup --python author_eat.py -- [--fast|--full] [--no-export]

Contract kept (EatFromHearthGoal.tick / SettlerModel):
  length 1.2 s, looping; bites at t = 0.25 s and 0.70 s = ticks 5 and 14 of 24
  (settler_eat). The meal is drawn by ResidentMealLayer at the RIGHT palm
  (arm-local y = 10), level in the world, so the palm itself travels to the
  mouth. IDLE runs underneath through SettlerModel.IDLE_UNDER_ACTION (head,
  arms, forearms 0; torso 0.6; legs/root 0.5), so this clip keeps the hips
  and legs quiet (a small sag only) and owns head + both arms.

Beats per bite: a small dip of the food (anticipation) -> the hand rises and
decelerates into the mouth while the head tips down to meet it and the chest
leans in -> a short hold on the bite -> the hand drops back to the chest
while the head gives two chewing bobs. The second bite is mirrored in the
torso twist and head tilt so the loop does not read as a copy-paste.
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
VAR = "v2" in A["rest"]          # EAT__v2: two base cycles, a back-of-hand mouth wipe between them
CONST, SLUG = ("EAT__V2", "eat__v2") if VAR else ("EAT", "eat")
L = 1.2
LT = 2 * L if VAR else L
BITES = (0.25, 0.70)
objs = lk.scene("settler_farmer.png")
FEET = lk.FEET

ACC, DEC, INOUT = lk.ACC, lk.DEC, lk.INOUT
K = {
    # hips sag onto soft knees; a touch lower into each bite (legs share idle 0.5)
    "root_y": [(0.0, -0.45), (0.10, -0.40), (0.25, -0.70, *DEC), (0.45, -0.50), (0.55, -0.45),
               (0.70, -0.72, *DEC), (0.92, -0.50), (1.2, -0.45)],
    # chest leans into each bite, eases back while chewing
    "torso_x": [(0.0, 4.0), (0.10, 3.2), (0.25, 8.0, *DEC),
                (0.45, 5.0), (0.55, 3.5), (0.70, 8.0, *DEC), (0.92, 5.0), (1.2, 4.0)],
    "torso_y": [(0.0, 0.0), (0.25, -2.5), (0.48, -0.5), (0.70, 2.0), (0.95, 0.8), (1.2, 0.0)],
    "torso_z": [(0.0, 0.0), (0.25, 1.0), (0.70, -1.0), (1.2, 0.0)],
    # 0 = holding the food at the chest, 1 = food at the mouth
    "bite": [(0.0, 0.0), (0.10, 0.0, *ACC), (0.25, 1.0), (0.31, 0.97, *INOUT), (0.46, 0.0),
             (0.55, 0.0, *ACC), (0.70, 1.0), (0.76, 0.97, *INOUT), (0.93, 0.0), (1.2, 0.0)],
    # anticipation: the food sinks a little before each lift
    "dip": [(0.0, 0.0), (0.10, 1.0), (0.21, 0.0), (0.46, 0.0), (0.55, 1.0), (0.66, 0.0), (1.2, 0.0)],
    # head tips down to meet the food; chewing bobs between bites
    "head_x": [(0.0, 7.0), (0.10, 4.0), (0.25, 17.0, *DEC), (0.32, 15.0), (0.38, 10.5), (0.43, 13.0),
               (0.49, 9.5), (0.55, 5.0), (0.70, 17.0, *DEC), (0.77, 15.0), (0.84, 10.5), (0.90, 13.0),
               (0.97, 10.0), (1.04, 12.0), (1.12, 8.5), (1.2, 7.0)],
    "head_y": [(0.0, -5.0), (0.25, -8.0), (0.50, -5.5), (0.70, -7.0), (1.0, -4.5), (1.2, -5.0)],
    "head_z": [(0.0, 0.0), (0.25, -3.0), (0.48, 0.0), (0.70, 3.0), (0.95, 0.5), (1.2, 0.0)],
    # the free hand floats at the belly and answers each bite a little
    "lh_y": [(0.0, -1.2), (0.25, -1.9), (0.50, -1.3), (0.70, -1.9), (0.95, -1.3), (1.2, -1.2)],
}
lk.key(K, cyclic=True)
c = lk.c
if VAR:
    # after the second bite has been chewed, the free hand wipes the mouth
    # (back of the hand, left to right) and drops again before the next bite
    lk.key({"wipe": [(0.0, 0.0), (0.90, 0.0, *lk.ACC2), (1.06, 1.0), (1.22, 1.0, *lk.INOUT), (1.42, 0.0),
                     (LT, 0.0)],
            "wipe_s": [(0.0, 0.0), (1.04, 0.0, *lk.INOUT), (1.24, 1.0), (LT, 1.0)]}, cyclic=False)
WIPE_A = np.array([3.4, -0.6, -4.9])     # head space: back of the left hand at the left mouth corner
WIPE_B = np.array([-1.2, -0.9, -4.9])    # ... dragged across to the right corner

# food held in front of the chest (torso space: +x left, +y down, +z back)
HOLD = np.array([-2.0, -4.0, -5.8])
DIP = np.array([0.0, 0.9, 0.2])          # the anticipation dip, per unit of negative "bite"
BITE_HEAD = np.array([-1.6, 0.6, -5.6])   # palm just under the chin (head space): the level food's edge at the lips


def body(t):
    return {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, c("root_y", t), 0.0)},
            "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t)),
                      "scale": lk.breath_scale(lk.breath(t, L / 2.0, phase=0.15), 0.010)}}


def solve(t):
    tv = t % LT
    t %= L
    ww = c("wipe", tv) if VAR else 0.0
    ch = body(t)
    ch["head"] = {"rot": (c("head_x", t) - 4.0 * ww, c("head_y", t) + 6.0 * ww, c("head_z", t) - 3.0 * ww)}
    prev = solve.prev
    w = mcrig.pose_matrices(ch)
    b = c("bite", t)
    hold = mcrig.xform(w["torso"], HOLD + DIP * c("dip", t))
    mouth = mcrig.xform(w["head"], BITE_HEAD)
    s = max(0.0, min(1.0, b))
    target = hold * (1 - s) + mouth * s
    lk.arm_ik(ch, w, "right", target, (-0.4, 1.0, 0.15), prev)
    left = mcrig.xform(w["torso"], (3.0, c("lh_y", t), -4.4))
    if ww > 0:
        ws = c("wipe_s", tv)
        left = left * (1 - ww) + mcrig.xform(w["head"], WIPE_A * (1 - ws) + WIPE_B * ws) * ww
    lk.arm_ik(ch, w, "left", left, (0.6, 1.0, 0.3), prev)
    lk.leg_ik(ch, FEET, prev)
    rate = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
    vy = lk.lagged_rate(lambda u: c("root_y", u % L), t, lag=0.03)
    ch["cloak"] = {"rot": lk.cloak(2.0, c("torso_x", t), rate, root_vy=vy, k_pitch=0.3)}
    solve.last_target = target
    return ch


solve.prev = {}
for _ in range(2):              # warm the Euler continuity so frame 0 matches frame L
    for f in range(int(LT * lk.FPS) + 1):
        solve(f / lk.FPS)
times, samples = hsrig.bake(solve, LT, objs)

# --------------------------------------------------------------------------- checks
mouth_err = []
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    mouth_err.append(float(np.linalg.norm(lk.palm(w, "right") - mcrig.xform(w["head"], BITE_HEAD))))
checks = {
    "length": LT, "loop": True, "bite_ticks": [5, 14] if not VAR else [5, 14, 29, 38],
    "palm_to_mouth_px_at_bites": [round(mouth_err[int(round(b * 60))], 3) for b in BITES],
    "closest_mouth_t": [lk.extreme_time(times[:40], mouth_err[:40], "min"),
                        lk.extreme_time(times[40:], mouth_err[40:], "min")],
    "right_elbow_at_bites": [lk.value_at(times, samples, b, "right_forearm")[0] for b in BITES],
    "loop_seam_pos_vel": lk.loop_seam(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET), "curve_seams": lk.curve_seams(L),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_eat.py (Blender " + bpy.app.version_string + ")",
        "contract": "EAT 1.2 s loop; bites t=0.25/0.70 s = ticks 5/14 of 24 (EatFromHearthGoal, "
                    "settler_eat); meal drawn at the right palm; IDLE layered under via IDLE_UNDER_ACTION",
        "checks": checks}
if VAR:
    meta["variant_of"] = "animation.settler.eat"
    meta["contract"] += "; variant = 2 base cycles (bites 0.25/0.70/1.45/1.90), same frame-0 pose"
keep = BITES + (L,) + tuple(b + L for b in BITES) if VAR else BITES
path, doc, report, worst = lk.export(CONST, LT, True, times, samples,
                                     keep_times=(0.0,) + keep + (LT,), meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, LT, A)
