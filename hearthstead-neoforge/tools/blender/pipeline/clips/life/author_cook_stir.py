"""COOK_STIR (SettlerAnimations.COOK_STIR), authored in Blender. Run headless:

    blender -b --factory-startup --python author_cook_stir.py -- [--fast|--full] [--no-export]

Contract kept (Employment.soundContactOf WORK_STIR / CrafterWorkGoal):
  length 1.50 s, looping; pot_stir accent at t = 1.20 s = tick 24 of 30.

The pot is cradled at the belly in the LEFT hand (mimed, no prop), the right
hand drives a ladle round an ellipse inside it. The stir is not a uniform
circle: it drags slowly through the far side and accelerates through the thick
at the NEAR side, fastest (and lowest, scraping the bottom) exactly at the
1.20 s accent. The hips sway a little with the ladle, the spine turns with
it about 0.1 s late, the pot hand gives a small counter-motion, the head is
down in the steam and follows the ladle with a lag. Every layer is periodic
in 1.5 s, so the loop is seamless by construction.
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
VAR = "v2" in A["rest"]     # COOK_STIR__v2: two cycles; between the accents the cook tastes from the ladle
CONST, SLUG = ("COOK_STIR__V2", "cook_stir__v2") if VAR else ("COOK_STIR", "cook_stir")
L = 1.50
LT = 2 * L if VAR else L
ACCENT = 1.20
objs = lk.scene("settler_cook.png")
FEET = (np.array([-3.0, 24.0, 0.9]), np.array([3.0, 24.0, -0.9]))

POT = np.array([0.4, -3.4, -6.6])      # pot centre, torso space (belly height, in front)
RX, RZ, DIP = 2.5, 1.9, 0.8             # ladle ellipse radii (px) and near-side dip
SPEED_VAR = 0.45                        # 1.45x at the near side, 0.55x at the far side


def phase(t):
    """Ladle angle: pi/2 (= nearest the belly) exactly at the accent, fastest there."""
    u = 2 * math.pi * (t - ACCENT) / L
    return math.pi / 2 + u + SPEED_VAR * math.sin(u)


def ladle(t):
    th = phase(t)
    near = math.sin(th)                               # +1 near side, -1 far side
    return np.array([RX * math.cos(th), 0.5 * DIP * (near + 1) ** 2 / 2 - 0.6 * DIP,
                     RZ * near]), th


def body(t):
    lag = ladle(t - 0.10)[0]
    lag2 = ladle(t - 0.16)[0]
    s = lk.breath(t, L)
    return {"root": {"rot": (0.0, 0.0, 0.0),
                     "pos": (0.28 * lag2[0] / RX, -0.35 - 0.12 * (lag2[2] / RZ), 0.0)},
            "torso": {"rot": (13.0 - 1.4 * lag[2] / RZ, 3.2 * lag[0] / RX, -1.2 * lag[0] / RX),
                      "scale": lk.breath_scale(s, 0.012)}}


if VAR:
    # right after the 1.20 s accent the ladle comes up to the lips, a sip, a
    # judging pause, one satisfied nod, and back into the pot in time for the
    # 2.70 s accent (the stir phase itself never stops, so it rejoins seamlessly)
    lk.key({"taste": [(0.0, 0.0), (1.26, 0.0, *lk.ACC2), (1.66, 1.0), (1.98, 1.0, *lk.INOUT), (2.40, 0.0),
                      (LT, 0.0)],
            "nod": [(0.0, 0.0), (1.70, 0.0), (1.82, -2.0), (1.96, 0.0), (2.10, 8.0, *lk.DEC), (2.28, 0.0),
                    (LT, 0.0)]}, cyclic=False)
TASTE_HEAD = np.array([-1.3, 0.7, -5.3])     # ladle bowl at the lips (head space), like EAT's bite point


def solve(t):
    tv = t % LT
    t %= L
    tw = lk.smoothstep(c_taste(tv))
    ch = body(t)
    tr = ch["torso"]["rot"]
    ch["torso"]["rot"] = (tr[0] + (5.0 - tr[0]) * tw, tr[1] * (1 - tw), tr[2] * (1 - tw))
    prev = solve.prev
    lad, th = ladle(t)
    look = ladle(t - 0.12)[0]
    hx = 20.0 + 1.5 * look[2] / RZ
    ch["head"] = {"rot": (hx + (4.0 - hx) * tw + (lk.c("nod", tv) if VAR else 0.0),
                          6.0 * look[0] / RX * (1 - tw), -1.5 * look[0] / RX * (1 - tw))}
    w = mcrig.pose_matrices(ch)
    # ladle hand: above the rim, handle down into the pot
    grip = POT + np.array([-0.4, -2.3, 0.2]) + lad
    if tw > 0:
        tl = mcrig.xform(np.linalg.inv(w["torso"]), mcrig.xform(w["head"], TASTE_HEAD))
        grip = grip * (1 - tw) + tl * tw
    lk.arm_ik_local(ch, "right", grip, (-0.85 + 0.45 * tw, 0.6 + 0.4 * tw, 0.45 - 0.3 * tw), prev,
                    twist=-6.0 * math.cos(th) * (1 - tw))
    # pot hand: under the left of the pot, a small counter-motion against the ladle
    counter = -0.18 * lad
    lk.arm_ik_local(ch, "left", POT + np.array([2.4, 1.3, 0.8]) + counter, (0.85, 0.6, 0.35), prev)
    lk.leg_ik(ch, FEET, prev)
    yaw_rate = lk.lagged_rate(lambda u: body(u)["torso"]["rot"][1], t)
    pitch_rate = lk.lagged_rate(lambda u: body(u)["torso"]["rot"][0], t)
    ch["cloak"] = {"rot": lk.cloak(4.0, ch["torso"]["rot"][0], pitch_rate, yaw_rate, k_pitch=0.3,
                                   k_yaw=0.06)}
    return ch


def c_taste(tv):
    return max(0.0, min(1.0, lk.c("taste", tv))) if VAR else 0.0


solve.prev = {}
for f in range(int(LT * lk.FPS) + 1):
    solve(f / lk.FPS)
times, samples = hsrig.bake(solve, LT, objs)

# --------------------------------------------------------------------------- checks
speed = []
low = []
for i in range(len(times)):
    a = ladle(times[i])[0]
    b = ladle(times[i] + 1 / 60)[0]
    speed.append(float(np.linalg.norm(b - a) * 60))
    low.append(float(a[1]))
checks = {
    "length": LT, "loop": True, "accent_ticks": [24, 54] if VAR else [24],
    "ladle_speed_peak": lk.extreme_time(times, speed, "max"),
    "ladle_lowest_point": lk.extreme_time(times, low, "max"),
    "ladle_speed_at_accent_vs_mean": round(speed[int(ACCENT * 60)] / (sum(speed) / len(speed)), 2),
    "ladle_hand_at_accents_vs_pot_path_px": [],
    "loop_seam": lk.loop_seam(samples),
    "foot_slide_px_max": lk.foot_slide(samples, FEET),
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_cook_stir.py (Blender " + bpy.app.version_string + ")",
        "contract": "COOK_STIR 1.50 s loop; pot_stir accent t=1.20 s = tick 24 of 30 "
                    "(Employment.soundContactOf WORK_STIR)",
        "checks": checks}
for acc in ((ACCENT, ACCENT + L) if VAR else (ACCENT,)):
    s_ = samples[int(round(acc * 60))]
    w_ = mcrig.pose_matrices(s_)
    want = POT + np.array([-0.4, -2.3, 0.2]) + ladle(acc)[0]
    got = mcrig.xform(np.linalg.inv(w_["torso"]), lk.palm(w_, "right"))
    checks["ladle_hand_at_accents_vs_pot_path_px"].append(round(float(np.linalg.norm(got - want)), 3))
print("CHECKS2", json.dumps(checks["ladle_hand_at_accents_vs_pot_path_px"]))
if VAR:
    meta["variant_of"] = "animation.settler.cook_stir"
    meta["contract"] += "; variant = 2 base cycles (accents 1.20/2.70 s), same frame-0 pose"
keep = (ACCENT, L, ACCENT + L) if VAR else (ACCENT,)
path, doc, report, worst = lk.export(CONST, LT, True, times, samples, keep_times=(0.0,) + keep + (LT,),
                                     meta=meta, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, LT, A)
