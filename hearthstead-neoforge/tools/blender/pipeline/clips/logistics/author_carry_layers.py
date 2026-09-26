"""Arm-only carry LAYERS, authored in Blender. Run headless:

    blender -b --factory-startup --python author_carry_layers.py -- <courier|haul|heavy> [v2] [--fast|--full]

These are NOT full-body clips. SettlerModel resets both arms (resetLimb also
clears the elbow) and then plays the layer over the distance-sampled
WALK_LADEN gait, which (with SettlerModel.applySack's load lean) owns the
feet, hips, spine, head and cloak. So each layer writes ONLY
right_arm / left_arm / right_forearm / left_forearm (exact_overlay_bones in
tools/anim_check.py), and the hand goals are authored in TORSO space: the
fists stay on the straps whatever the gait and the load lean do.

Contracts kept:
  COURIER_CARRY_GRIP  2.0 s loop, no sound tick; the carry clamp (<= 6 deg arm
                      travel, anim_check carry_layer_clips).
  HAUL_LOG            2.4 s loop; strain accent at t = 1.20 s = tick 24 of 48
                      (LumbererWorkGoal: haulTicks % 48 == 24 -> SETTLER_HM).
  HAUL_LOG_HEAVY      same 2.4 s cadence and 1.20 s accent (the runtime
                      cross-blends light/heavy by heavyHaulPoseBlend, so both
                      share phase).

Weight: the load hangs off the shoulder straps, so the fists grip the straps
on the chest and pull DOWN on them; the elbows sit low and back. Over a cycle
the load slowly drags the fists down (sag), then the carrier hitches it: a small
lift (anticipation) and a firm pull down/back on the accent, easing out.
Heavy: fists lower and tighter to the ribs, elbows further back, a bigger hitch.

Variants (same length, same accent):
  courier v2  "hitches the sack higher": a two-hand heave on the straps.
  heavy   v2  "readjusts the grip": after the 1.2 s strain the right hand lets go,
              flexes and re-grips lower on the strap.
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
KIND = next((k for k in ("courier", "haul", "heavy") if k in A["rest"]), "courier")
VARIANT = "v2" if "v2" in A["rest"] else None
BASE = {"courier": "COURIER_CARRY_GRIP", "haul": "HAUL_LOG", "heavy": "HAUL_LOG_HEAVY"}[KIND]
CONST = BASE + ("__V2" if VARIANT else "")
SLUG = CONST.lower()
LENGTH = 2.0 if KIND == "courier" else 2.4
ACCENT = None if KIND == "courier" else 1.20

# Fist goals (torso-local px; the torso pivot is the hip, shoulders at y -10,
# the chest front at z -2.5, straps at x +-3.4). Palm = arm-local y 9 (fist centre).
P = {
    "courier": dict(fist=(3.4, -8.8, -4.3), pole=(0.75, 1.0, 0.35), sag=0.22, hitch=0.32, lift=0.14),
    "haul":    dict(fist=(3.6, -7.2, -4.3), pole=(0.8, 1.0, 0.4), sag=0.4, hitch=0.6, lift=0.25),
    "heavy":   dict(fist=(3.5, -6.2, -4.4), pole=(0.6, 1.0, 0.5), sag=0.55, hitch=0.8, lift=0.35),
}[KIND]
LOWER = 5.0
OUTS = ("SINE", "EASE_OUT")
INQ = ("QUAD", "EASE_IN")

tex = "settler_courier.png" if KIND == "courier" else "settler_lumberer.png"
objs = lkit.scene(tex)
if KIND == "courier":
    lkit.attach_sack(objs, scale=1.0)
else:
    lkit.attach_frame(objs, logs=3 if KIND == "heavy" else 1)

L = LENGTH
if KIND == "courier":
    # slow settle-and-tug, one per 2 s cycle (no audio tick)
    dy = [(0.0, 0.0), (0.55, P["sag"] * 0.6), (0.95, P["sag"]), (1.10, P["sag"] - P["lift"], *INQ),
          (1.30, P["sag"] + P["hitch"] * 0.6, *OUTS), (1.70, 0.1), (L, 0.0)]
    dz = [(0.0, 0.0), (1.10, -0.1, *INQ), (1.30, 0.25, *OUTS), (1.80, 0.05), (L, 0.0)]
    dx = [(0.0, 0.0), (1.10, 0.0, *INQ), (1.30, -0.12, *OUTS), (L, 0.0)]
else:
    # sag under the load, anticipate (fists lift), pull hard ON the accent, ease out
    dy = [(0.0, 0.0), (0.60, P["sag"] * 0.6), (0.98, P["sag"]), (1.08, P["sag"] - P["lift"], *INQ),
          (ACCENT, P["sag"] + P["hitch"], *OUTS), (1.45, P["sag"] + P["hitch"] * 0.8), (2.0, 0.15), (L, 0.0)]
    dz = [(0.0, 0.0), (1.08, -0.1, *INQ), (ACCENT, 0.35, *OUTS), (1.6, 0.2), (L, 0.0)]
    dx = [(0.0, 0.0), (1.08, 0.0, *INQ), (ACCENT, -0.15, *OUTS), (1.8, -0.05), (L, 0.0)]
hsrig.key_curve("dy", dy, cyclic=True, length=L)
hsrig.key_curve("dz", dz, cyclic=True, length=L)
hsrig.key_curve("dx", dx, cyclic=True, length=L)
# per-side detune so the two fists never move as a mirrored pair
hsrig.key_curve("asym", [(0.0, 0.0), (0.5, 0.12), (1.3, -0.08), (L, 0.0)], cyclic=True, length=L)

# variant beats (0 when unused)
hsrig.key_curve("heave", [(0.0, 0.0), (L, 0.0)], cyclic=True, length=L)
hsrig.key_curve("regrip", [(0.0, 0.0), (L, 0.0)], cyclic=True, length=L)
if VARIANT and KIND == "courier":
    # two-hand heave: drop the fists down the straps, then yank down/in hard (the sack rides up)
    hsrig.key_curve("heave", [(0.0, 0.0), (0.15, 0.0), (0.45, -0.35, *INQ), (0.62, 1.0, *OUTS),
                              (0.80, 0.85), (1.25, 0.0), (L, 0.0)], cyclic=True, length=L)
if VARIANT and KIND == "heavy":
    # right hand: release after the strain, shake out, re-grip lower
    hsrig.key_curve("regrip", [(0.0, 0.0), (1.45, 0.0), (1.62, 1.0, *OUTS), (1.85, 1.0), (2.05, 0.35, *INQ),
                               (2.2, 0.0), (L, 0.0)], cyclic=True, length=L)
c = hsrig.ctrl
prev = {}
AMP = [1.0]
VARIANT_ON = [True]   # off while measuring the base clamp, so variants share the base scale
# breathing-room scale on the sag/hitch so the upper arm stays inside the carry clamp


def fist_goal(side, t):
    s = 1 if side == "left" else -1
    fx, fy, fz = P["fist"]
    k = AMP[0]
    a = c("asym", t) * s * k
    h = c("heave", t) if VARIANT_ON[0] else 0.0
    x = s * (fx - k * c("dx", t) - 0.35 * h)
    y = fy + k * c("dy", t) + a + 1.6 * h
    z = fz + k * c("dz", t) + 0.5 * h
    g = np.array([x, y, z])
    if side == "right":
        r = c("regrip", t) if VARIANT_ON[0] else 0.0
        # hand leaves the strap forward/out and down, fingers open (less flex), back on lower
        g = g + r * np.array([-1.6, 1.4, -2.2])
    return g


def solve(t):
    t %= L
    ch = {}
    for side in ("right", "left"):
        s = 1 if side == "left" else -1
        pole = np.array([s * P["pole"][0], P["pole"][1], P["pole"][2]])
        lkit.arm_ik_local(ch, side, fist_goal(side, t), pole, prev, lower=LOWER)
    return ch


# the layer itself (torso at rest) -> export
CLAMP = 5.5     # deg; anim_check carry_layer_clips allows 6


def sample_all():
    ts, ss = [], []
    for f in range(int(round(L * hsrig.FPS)) + 1):
        ts.append(f / hsrig.FPS)
        ss.append(solve(f / hsrig.FPS))
    return ts, ss


VARIANT_ON[0] = False
times, samples = sample_all()
if True:
    for _ in range(4):
        tv = lkit.travel(samples, ["right_arm", "left_arm"])
        worst = max(max(v) for v in tv.values())
        if worst <= CLAMP:
            break
        AMP[0] *= CLAMP / worst * 0.98
        times, samples = sample_all()
VARIANT_ON[0] = True
times, samples = sample_all()

tr = lkit.travel(samples, lkit.ARM_BONES)
checks = {"length_s": L, "loop": True, "accent_t": ACCENT, "motion_scale": round(AMP[0], 3),
          "bones": lkit.ARM_BONES, "arm_travel_deg": tr,
          "arm_travel_max_deg": max(max(v) for k, v in tr.items() if k.endswith("_arm")),
          "ranges": lkit.ranges(samples, lkit.ARM_BONES), "seam_max": lkit.seam(samples)}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_carry_layers.py " + KIND
                  + (" " + VARIANT if VARIANT else "") + " (Blender " + bpy.app.version_string + ")",
        "contract": (f"{BASE} arm-only layer over WALK_LADEN; {L} s loop"
                     + (f"; strain accent t={ACCENT} s = tick 24 of 48 (SETTLER_HM)" if ACCENT else "")),
        "layer_bones": lkit.ARM_BONES, "checks": checks}
if A["export"]:
    lkit.export(CONST, L, True, times, samples, bones=lkit.ARM_BONES,
                keep_times=(ACCENT,) if ACCENT else (), meta=meta, rot_tol=0.15)

# preview: the layer over the lead's WALK_LADEN (in place) + applySack's load lean
if A["fast"] or A["full"]:
    lean = {"courier": 4.6, "haul": 6.0, "heavy": 12.6}[KIND]
    period = 1.0 if KIND == "courier" else 1.2   # courier: 2 steps per 2.0 s preview loop
    walk = lkit.walk_underlay("walk_laden", period=period, lean_deg=lean)

    def full(t):
        ch = walk(t)
        ch.update(solve(t))
        return ch
    hsrig.bake(full, L, objs)
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.6, -0.3, 1.1), (0.0, 0.0, 0.92), lens=50),
                                "front34": hsrig.camera("cam_front34", (-2.2, -2.4, 1.5), (0.0, 0.0, 1.0), lens=45)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (0.0, -3.2, 1.25), (0.0, 0.0, 1.0), lens=50, n=6)
