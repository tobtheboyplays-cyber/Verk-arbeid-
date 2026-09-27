"""FARM_TILL, authored in Blender. Run headless:

    blender -b --factory-startup --python author_farm_till.py -- [--fast|--full]

Contract kept (SettlerAnimations.FARM_TILL / FarmerWorkGoal / anim_check):
  length 1.50 s looping (TILL_DURATION 30 ticks); the hoe blade bites the soil
  at t = 0.60 s = tick 12 of 30 (FARMER_WORK sound contract). The real MAINHAND
  iron hoe is rendered by ItemInHandLayer on the right hand (vanilla handheld
  transform). SettlerModel resets the whole body before this clip, so it owns
  every bone.

Beats (a chopping hoe stroke, weight first):
  0.00-0.08  ready: blade hovering over the soil, knees soft, back hinged.
  0.08-0.42  lift: hips rise and ease back, spine straightens, both hands draw
             the hoe up in front of the face, weight rocks onto the back foot.
  0.42-0.47  suspension at the top (blade high, a breath of stillness).
  0.47-0.60  strike: hips drop first, spine hinges, arms follow and accelerate
             (cubic ease-in) -- fastest at the bite.
  0.60-0.66  contact hold, blade in the soil; tiny recoil through the knees.
  0.66-0.92  drag: the hoe is pulled back toward the feet a few px, turning the
             clod; spine rises a little against the pull.
  0.92-1.50  slow recovery to ready (the long "up", the short "down").
Left hand is IK on the haft above the right hand the whole loop.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import farmkit as fk  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

V = fk.variant_flag()                  # None = base clip, 2 = __v2 (brow wipe)
CONST, SLUG = ("FARM_TILL", "farm_till") if V is None else (f"FARM_TILL__v{V}", f"farm_till__v{V}")
LENGTH, CONTACT, TOP = 1.5, 0.60, 0.42
NCYC = 1 if V is None else 2
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)

sc, objs = fk.build(fk.tex("farmer"), item="hoe")
fk.box("tilled", (-8, 24, -24), (16, 1.2, 16), (0.33, 0.22, 0.13, 1))   # the tended block
FEET = (np.array([-3.4, 24.0, 2.4]), np.array([3.2, 24.0, -2.6]))       # right back, left forward
# QA 2026-09-26 rework (owner: "far too hunched", "head near the knees"): the
# body now works like a real hoe stroke -- knees and hips take the drop, the back
# stays long (spine 10-30 deg, was 38-56), both hands stay on the haft, and the
# wrist bone tips the hoe so the blade reaches the soil a full stride ahead.
SOIL = np.array([-1.6, 24.4, -10.6])                                       # where the blade bites

IN = ("CUBIC", "EASE_IN")
OUT_ = ("SINE", "EASE_OUT")
INQ = ("QUAD", "EASE_IN")
L = LENGTH
# overlap chain: pelvis leads the strike (0.52-0.57), spine lands with the blade
# (0.60), head and cloak settle after it (0.66-0.72).
K = {
    "pelvis_y":   [(0.0, -1.3), (0.12, -1.2), (0.40, -0.4), (0.46, -0.45, *IN), (0.57, -3.2, *OUT_),
                   (0.66, -3.4), (0.92, -3.0), (1.22, -1.9), (L, -1.3)],
    "pelvis_z":   [(0.0, 0.8), (0.40, 0.2), (0.46, 0.25, *IN), (0.57, 1.5, *OUT_), (0.92, 1.4),
                   (L, 0.8)],
    "pelvis_x":   [(0.0, 0.05), (0.40, -0.55), (0.46, -0.5, *IN), (0.57, 0.35, *OUT_), (0.92, 0.25),
                   (L, 0.05)],
    "pelvis_yaw": [(0.0, 2.0), (0.38, -2.0), (0.46, -1.5, *IN), (0.57, 3.5, *OUT_), (0.92, 2.5),
                   (L, 2.0)],
    "spine_x":    [(0.0, 18.0), (0.14, 17.0), (0.42, 8.0), (0.48, 8.5, *IN), (0.60, 28.0, *OUT_),
                   (0.68, 30.0), (0.92, 27.0), (1.24, 20.0), (L, 18.0)],
    "spine_yaw":  [(0.0, 5.0), (0.42, -2.0), (0.48, -1.5, *IN), (0.60, 7.0, *OUT_), (0.70, 8.0),
                   (0.92, 6.5), (L, 5.0)],
    "spine_z":    [(0.0, 2.0), (0.42, 1.0), (0.48, 1.0, *IN), (0.60, 3.0, *OUT_), (L, 2.0)],
    "head_nod":   [(0.0, 0.0), (0.44, -8.0), (0.50, -7.0, *IN), (0.64, 3.0, *OUT_), (0.72, 5.0),
                   (1.0, 2.0), (L, 0.0)],
    "grip_s":     [(0.0, -2.0), (0.40, -1.5), (0.60, -2.0), (L, -2.0)],
    # two-handed the whole stroke (the old left hand dropped onto the knee, which
    # is what forced the deep fold)
    "grip_w":     [(0.0, 1.0), (L, 1.0)],
}
BASE_L = L
if V == 2:
    # __v2, two cycles (3.00 s): the second bite still lands at 2.10 s = tick 42 =
    # tick 12 of cycle 2. Between them the farmer straightens, blows, and drags
    # the back of his left wrist across his brow (1.05-1.50), then re-grips and
    # lifts straight into the second stroke from the upright stance.
    K = {k: fk.tile(v, L, NCYC) for k, v in K.items()}
    W0, W1 = 0.92, L + 0.28
    K["spine_x"] = fk.splice(K["spine_x"], W0, W1, [(1.08, 22.0), (1.28, 7.0), (1.46, 6.0), (1.64, 9.0)])
    K["pelvis_y"] = fk.splice(K["pelvis_y"], W0, W1, [(1.10, -0.9), (1.32, -0.15), (1.55, -0.2)])
    K["pelvis_z"] = fk.splice(K["pelvis_z"], W0, W1, [(1.15, 0.4), (1.50, 0.1)])
    K["spine_yaw"] = fk.splice(K["spine_yaw"], W0, W1, [(1.20, 1.0), (1.42, -3.0), (1.62, 0.0)])
    K["spine_z"] = fk.splice(K["spine_z"], W0, W1, [(1.25, -1.5), (1.45, -2.5)])
    K["head_nod"] = fk.splice(K["head_nod"], W0, W1, [(1.15, -8.0), (1.32, -16.0), (1.46, -18.0),
                                                      (1.62, -9.0)])
    K["grip_w"] = fk.splice(K["grip_w"], W0, W1, [])
    K["wipe_w"] = [(0.0, 0.0), (0.96, 0.0), (1.16, 1.0), (1.44, 1.0), (1.64, 0.0), (2 * L, 0.0)]
    K["wipe_s"] = [(0.0, 0.0), (1.20, 0.0), (1.42, 1.0, "SINE", "EASE_OUT"), (2 * L, 1.0)]
    L = L * NCYC
fk.key(K, cyclic=True, length=L)


def body(t):
    return fk.body(t % L)


HAND = fk.HOE_KNOB, fk.HOE_NECK


def left_reach(w, s=2.0, reach=9.6):
    """Penalty when the left palm could not reach its grip on the haft."""
    im = fk.item_matrix(fk.hand_frame(w, "right"), "handheld", True)
    knob, neck = mcrig.xform(im, fk.HOE_KNOB), mcrig.xform(im, fk.HOE_NECK)
    axis = (neck - knob) / np.linalg.norm(neck - knob)
    grip = knob + axis * (float((fk.palm(w, "right") - knob) @ axis) + s)
    d = float(np.linalg.norm(grip - mcrig.xform(w["left_arm"], (0, 0, 0))))
    return 4.0 * max(0.0, d - reach) ** 2


def edge_down(w, wt):
    """The hoe head (neck -> blade corner) hangs edge-down into the soil."""
    im = fk.item_matrix(fk.hand_frame(w, "right"), "handheld", True)
    v = mcrig.xform(im, fk.HOE_BLADE) - mcrig.xform(im, fk.HOE_NECK)
    v /= np.linalg.norm(v)
    return wt * float(np.sum((v - np.array([0.0, 0.94, 0.34])) ** 2))


def g(hand, direction, tip=None, w_tip=0.6, w_hand=0.5, w_edge=0.0):
    return fk.goal_terms("right", hand=hand, w_hand=w_hand, dir_pts=HAND, direction=direction,
                         tip_pt=fk.HOE_BLADE, tip=tip, w_tip=w_tip, w_dir=120.0,
                         extra=(lambda w: edge_down(w, w_edge)) if w_edge else None)


# The wrist bone (right_item) tips the hoe: no more forearm-square haft, so the
# hands stay at the belly/chest while the blade still reaches the soil ahead.
GOALS = [
    # t, (right palm, haft dir knob->neck, blade corner, blade weight), ease leaving the key
    (0.00, g((-1.6, 12.6, -7.2), (0.02, 0.55, -0.83), (-1.6, 21.5, -13.5), 1.0), None),
    (0.14, g((-1.4, 11.6, -7.6), (0.02, 0.35, -0.94)), None),
    (0.30, g((-1.8, 4.6, -9.6), (0.03, -0.55, -0.83)), None),
    (0.42, g((-2.6, 0.8, -10.0), (0.03, -0.93, 0.36)), INQ),         # top: hoe up, blade cocked back
    (0.48, g((-2.6, 1.1, -10.1), (0.03, -0.90, 0.43)), IN),          # suspension, then accelerate
    (0.60, g((-1.6, 14.4, -6.6), (0.02, 0.86, -0.51), tuple(SOIL), 14.0, 0.1, 60.0), OUT_),            # bite
    (0.66, g((-1.6, 14.6, -6.4), (0.02, 0.87, -0.49), tuple(SOIL + (0, 0.2, 0.4)), 14.0, 0.1, 60.0), None),
    (0.92, g((-1.6, 14.2, -5.0), (0.02, 0.87, -0.49), tuple(SOIL + (0, -0.2, 2.2)), 10.0, 0.1, 40.0), None),  # drag
    (1.22, g((-1.6, 12.4, -7.0), (0.02, 0.50, -0.87), (-1.6, 21.5, -13.0), 0.7), None),
]
if V == 2:
    GOALS = fk.tile_goals(GOALS, BASE_L, NCYC)
    GOALS = [x for x in GOALS if not (0.92 < x[0] < BASE_L + 0.28)]
    GOALS += [(1.22, g((-5.2, 12.8, -3.0), (0.02, 0.30, -0.95)), None),       # hoe hangs while he wipes
              (1.52, g((-4.5, 12.5, -3.6), (0.02, 0.20, -0.98)), None)]
    GOALS.sort(key=lambda x: x[0])
LOG = fk.key_fk_goals("arm_r", "right", GOALS, body, [-45.0, 5.0, 5.0, -35.0, 0.0], L, wrist=True,
                      wrist_lim=(75.0, 30.0, 35.0),
                      probe=lambda w: {"blade": fk.item_point(w, fk.HOE_BLADE)})
print("GOALS", json.dumps(LOG))


def haft_point(world, s):
    im = fk.item_matrix(fk.hand_frame(world, "right"), "handheld", True)
    knob = mcrig.xform(im, fk.HOE_KNOB)
    neck = mcrig.xform(im, fk.HOE_NECK)
    axis = (neck - knob) / np.linalg.norm(neck - knob)
    hand = fk.palm(world, "right")
    base = knob + axis * float((hand - knob) @ axis)
    return base + axis * s


def solve(t):
    t = t % L
    ch = body(t)
    fk.fk_arm(ch, "arm_r", "right", t)
    ch["cloak"] = {"rot": fk.cloak(t, L)}
    prev = getattr(solve, "_prev", {})
    world = mcrig.pose_matrices(ch)
    grip = haft_point(world, c("grip_s", t))
    knee = mcrig.xform(world["left_leg"], (0.3, 4.6, -2.2)) if "left_leg" in ch else None
    if knee is None:     # legs are solved below; use this frame's leg IK for the brace point
        fk.legs(ch, FEET, prev)
        knee = mcrig.xform(mcrig.pose_matrices(ch)["left_leg"], (0.3, 4.6, -2.2))
    w = min(1.0, max(0.0, c("grip_w", t)))
    w = w * w * (3 - 2 * w)
    target = (1 - w) * knee + w * grip
    ww = min(1.0, max(0.0, c("wipe_w", t)))
    ww = ww * ww * (3 - 2 * ww)
    fk.look(ch, SOIL + np.array([0.0, 0.0, 1.5]), w_pitch=0.30, w_yaw=0.5, nod=c("head_nod", t))
    if ww > 0.0:
        # back of the left wrist drawn across the forehead, right temple -> left temple
        hw = mcrig.pose_matrices(ch)["head"]
        brow = (1 - c("wipe_s", t)) * np.array([-3.5, -6.2, -7.8]) + c("wipe_s", t) * np.array([3.8, -6.0, -7.6])
        target = (1 - ww) * target + ww * mcrig.xform(hw, brow)
    # QA 2026-09-26: the pole used to SWITCH at ww = 0.5 (a 154 deg left-arm flip,
    # 7.9 px hand pop at 1.54 s); blend it with the wipe weight instead.
    pole = (1 - ww) * np.array([0.9, 0.6, 0.5]) + ww * np.array([0.9, -0.2, 0.6])         + 4.0 * ww * (1 - ww) * np.array([0.0, 1.0, -0.8])     # elbow drops while the hand crosses
    # reach clamp: a locked-straight arm has no defined roll and flipped 110-150 deg
    # in the wipe transitions; keep the goal ~0.6 px inside the 9.5 px reach.
    sh = mcrig.xform(mcrig.pose_matrices(ch)["left_arm"], (0, 0, 0))
    d = np.asarray(target, float) - sh
    n = float(np.linalg.norm(d))
    if n > 8.9:
        target = sh + d * (8.9 / n)
    fk.arm_ik(ch, "left", target, tuple(pole), prev, lower=5.5)
    solve._w = w
    fk.legs(ch, FEET, prev)
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_leg", "left_leg")}
    solve._grip = grip
    return ch


c = hsrig.ctrl
times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)

# --------------------------------------------------------------------------- checks
blade = []
grip_err = 0.0
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    blade.append(fk.item_point(w, fk.HOE_BLADE))
    solve(t)
    lp = mcrig.xform(w["left_forearm"], (0, 5.5, 0))
    if solve._w > 0.99:
        grip_err = max(grip_err, float(np.linalg.norm(lp - solve._grip)))
pk_t, pk_v, sp = fk.peak_speed_t(times, blade)
cf = int(round(CONTACT * fk.FPS))
checks = {
    "contact_t": CONTACT, "contact_tick": 12,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "blade_at_contact_px": [round(float(v), 2) for v in blade[cf]],
    "blade_height_above_ground_px_at_contact": round(24.0 - float(blade[cf][1]), 2),
    "blade_y_at_every_contact": [round(float(blade[int(round((CONTACT + i * BASE_L) * fk.FPS))][1]), 2)
                                 for i in range(NCYC)],
    "variant_cycles": NCYC,
    "blade_peak_speed_t": pk_t, "blade_peak_speed_px_s": pk_v,
    "blade_speed_px_s_frame_before_contact": round(float(sp[cf - 1]), 1),
    "blade_speed_px_s_frame_after_contact": round(float(sp[cf]), 1),
    "left_hand_to_haft_px_max_while_gripping": round(grip_err, 3),
    "knee_flex_max_deg": round(max(max(s["right_shin"]["rot"][0], s["left_shin"]["rot"][0]) for s in samples), 1),
    "goal_solves": LOG,
}
print("CHECKS", json.dumps(checks))

meta = {"source": "tools/blender/pipeline/clips/farm/author_farm_till.py (Blender " + bpy.app.version_string + ")"
                  + ("" if V is None else f" --v{V}"),
        "contract": "FARM_TILL 1.50 s loop; blade bites soil t=0.60 s = tick 12 of 30 (FarmerWorkGoal, FARMER_WORK); right_item wrist keyed (hoe edge-down)"
                    + ("" if V is None else f"; variant = {NCYC} base cycles, bites at 0.60 s and 2.10 s, "
                       "left-wrist brow wipe 1.05-1.50 s"),
        "checks": {k: v for k, v in checks.items() if k != "goal_solves"}}
path, doc, report, worst = fk.export(CONST, L, True, times, samples,
                                     keep_times=tuple(sorted({x + i * BASE_L for i in range(NCYC) for x in (0.0, TOP, 0.47, CONTACT, 0.66)} | {L})), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
