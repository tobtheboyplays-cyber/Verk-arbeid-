"""MINE_PICK (Miner), authored in Blender. Run headless:

    blender -b --factory-startup --python author_mine_pick.py -- [--fast|--full] [v2]

Contract kept from SettlerAnimations.MINE_PICK / MinerWorkGoal (do not change):
  length 0.95 s looping (19 ticks); the pick point bites the rock at
  t = 0.45 s = tick 9 (MinerWorkGoal: swingTicks % 19 == 9 -> PICK_STRIKE).
  Full-body stationary work clip (SettlerModel: animate(mineState, MINE_PICK)).

Beats: 0.00 ready, pick low across the body -> 0.06 small settle into the knees
(anticipation) -> 0.30 pick cocked overhead behind the head, spine arched back,
weight on the back (right) foot -> 0.34 short hang -> 0.45 accelerating
downswing, hips drop and the spine drives forward, fastest at the contact ->
0.45-0.50 hard stop: the point lodges, the arms jolt back a touch, head and
cloak snap forward -> 0.50-0.64 lodged, a small lever -> 0.64-0.74 rock back to
wrench the point free -> 0.74-0.95 settle to ready.

Rig: pelvis/spine FK controls; the right arm is FK solved at key poses from
world goals (hand, haft direction, pick point on the rock face) and
interpolated as joint curves so the pick travels on an arc; the LEFT hand is
IK to the haft just below the right hand; both legs are IK to planted feet.

Variant v2 ("listens to the rock"): same length and tick-9 strike; after the
strike, instead of wrenching straight out, the miner holds the lodged pick a
beat longer with his head cocked to the face (listening for the crack), then
pulls free faster. Contact frame identical.
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
VARIANT = "v2" if "v2" in A["rest"] else None
CONST = "MINE_PICK" + ("__V2" if VARIANT else "")
SLUG = CONST.lower()
LENGTH = 0.95
CONTACT = 0.45
TOP = 0.30

FOOT_R = np.array([-3.3, 24.0, 2.0])     # back foot
FOOT_L = np.array([3.0, 24.0, -2.4])     # front foot

objs = lkit.scene("settler_miner.png", os.path.join(lkit.REF, "iron_pickaxe.png"))

IN = ("CUBIC", "EASE_IN")
IN4 = ("QUART", "EASE_IN")
OUTS = ("SINE", "EASE_OUT")
INQ = ("QUAD", "EASE_IN")
LIN = ("LINEAR", "AUTO")

v2 = VARIANT == "v2"
K = {
    # pelvis (root): posVec y (up +), x (+ = settler's left), z (- = forward); yaw (+ = face right)
    "pelvis_y":   [(0.0, -0.7), (0.06, -1.0), (0.28, -0.15), (0.34, -0.25, *IN), (0.45, -2.1, *OUTS),
                   (0.50, -2.4), (0.62, -2.0), (0.72, -1.2), (0.95, -0.7)],
    "pelvis_x":   [(0.0, 0.05), (0.26, -0.55), (0.34, -0.5, *IN), (0.45, 0.55, *OUTS), (0.62, 0.45),
                   (0.95, 0.05)],
    "pelvis_z":   [(0.0, 0.0), (0.28, 0.35), (0.34, 0.3, *IN), (0.45, -0.55, *OUTS), (0.64, -0.45),
                   (0.76, 0.1), (0.95, 0.0)],
    "pelvis_yaw": [(0.0, 3.0), (0.28, 9.0), (0.34, 8.0, *IN), (0.45, -4.0, *OUTS), (0.62, -3.0),
                   (0.95, 3.0)],
    # spine: + = lean forward
    "spine_x":    [(0.0, 13.0), (0.06, 16.0), (0.28, -11.0), (0.34, -10.0, *IN), (0.45, 35.0, *OUTS),
                   (0.49, 31.5), (0.62, 33.0), (0.72, 20.0), (0.95, 13.0)],
    "spine_yaw":  [(0.0, 0.0), (0.28, 8.0), (0.34, 7.0, *IN), (0.45, -5.0, *OUTS), (0.62, -3.0),
                   (0.95, 0.0)],
    "spine_z":    [(0.0, 0.0), (0.28, 3.0), (0.34, 3.0, *IN), (0.45, -2.5, *OUTS), (0.95, 0.0)],
    # QA 2026-09-26: eyes follow the pick up (the face tips back out of the fists'
    # path) and the head lags the strike by a frame or two (overlap).
    "head_nod":   [(0.0, 4.0), (0.18, -22.0), (0.33, -44.0), (0.37, -42.0, *IN), (0.47, 8.0, *OUTS),
                   (0.52, 4.0), (0.62, 5.0), (0.95, 4.0)],
    "head_tilt":  [(0.0, 0.0), (0.95, 0.0)],
    # QA 2026-09-26: left hand at the END of the handle (toward the knob), right hand
    # nearer the head -- a right-handed pick grip. With +1.5 (toward the head) the
    # left fist rode up into the face whenever the pick was cocked back.
    "grip_s":     [(0.0, -2.4), (0.95, -2.4)],
}
if v2:
    # hold the lodged pick longer, head cocked toward the face (listening), quicker wrench
    K["spine_x"] = [(0.0, 13.0), (0.06, 16.0), (0.28, -11.0), (0.34, -10.0, *IN), (0.45, 35.0, *OUTS),
                    (0.49, 31.5), (0.56, 33.5), (0.70, 34.0, *INQ), (0.80, 15.0), (0.95, 13.0)]
    K["pelvis_y"] = [(0.0, -0.7), (0.06, -1.0), (0.28, -0.15), (0.34, -0.25, *IN), (0.45, -2.1, *OUTS),
                     (0.50, -2.4), (0.70, -2.25, *INQ), (0.80, -0.95), (0.95, -0.7)]
    K["head_tilt"] = [(0.0, 0.0), (0.49, 0.0), (0.58, -14.0), (0.70, -13.0), (0.80, 0.0), (0.95, 0.0)]
    K["head_nod"] = [(0.0, 4.0), (0.18, -22.0), (0.33, -44.0), (0.37, -42.0, *IN), (0.47, 8.0, *OUTS),
                     (0.52, 4.0), (0.58, 10.0), (0.70, 10.0), (0.80, 4.0), (0.95, 4.0)]
for prop, keys in K.items():
    hsrig.key_curve(prop, keys, cyclic=True, length=LENGTH)
c = hsrig.ctrl


def wrap(t):
    return t % LENGTH


def body(t):
    return {"root": {"rot": (0.0, c("pelvis_yaw", t), 0.0),
                     "pos": (c("pelvis_x", t), c("pelvis_y", t), c("pelvis_z", t))},
            "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), c("spine_z", t))}}


def pick_points(fore_world):
    item = mcrig.item_in_hand_matrix(fore_world, True)
    return {k: mcrig.xform(item, p) for k, p in
            (("knob", lkit.PICK_KNOB), ("neck", lkit.PICK_NECK), ("tip", lkit.PICK_POINT),
             ("rear", lkit.PICK_REAR))}


TORSO0 = np.array([0.0, 12.0, 0.0])     # torso origin (hip pivot) with root/torso at rest
SEEDS = [[-60, -30, 0, -60, 0], [-120, -20, 10, -60, 0], [-30, -40, -10, -80, 0],
         [-160, -10, 0, -40, 0], [0, -20, 0, -90, 0], [-90, -60, 20, -30, 0]]


def solve_right(hand_local, dir_local, seed):
    """FK right arm (x, y, z, elbow, twist) putting the fist on a TORSO-local point with the
    haft (knob -> head) along a torso-local direction. Arms are torso children, so the
    solution is independent of the pelvis/spine pose."""
    hand_t = np.asarray(hand_local, float) + TORSO0
    hd = np.asarray(dir_local, float) / np.linalg.norm(dir_local)

    def cost(p):
        w = mcrig.pose_matrices({"right_arm": {"rot": (p[0], p[1], p[2])},
                                 "right_forearm": {"rot": (p[3], p[4], 0.0)}})
        hp = mcrig.xform(w["right_forearm"], (0, 5.5, 0))
        pp = pick_points(w["right_forearm"])
        a = pp["neck"] - pp["knob"]
        a /= np.linalg.norm(a)
        cst = float(np.sum((hp - hand_t) ** 2)) + 60.0 * float(np.sum((a - hd) ** 2))
        cst += 0.0005 * p[4] ** 2
        if p[3] > 0:
            cst += p[3] ** 2
        if p[3] < -135:
            cst += (p[3] + 135) ** 2
        return cst

    best = None
    for s0 in [seed] + SEEDS:
        b, v = mcrig.nelder_mead(cost, s0, [20, 20, 20, 20, 15], iters=1500)
        b, v = mcrig.nelder_mead(cost, b, [4, 4, 4, 4, 4], iters=1500)
        # prefer continuity with the previous key when costs tie
        v2 = v + 0.0004 * float(np.sum((np.asarray(b) - np.asarray(seed)) ** 2))
        if best is None or v2 < best[2]:
            best = (b, v, v2)
    return [float(x) for x in best[0]], float(best[1])


GOALS = [
    # t,    fist (torso-local px),  haft dir (torso-local), ease leaving
    (0.00, (0.3, -5.5, -5.3), (0.05, -0.25, -0.97), None),   # ready: pick head forward, hands at the belt
    (0.06, (0.3, -4.8, -5.2), (0.05, -0.15, -0.99), None),   # settle (anticipation)
    # QA 2026-09-26: the fists used to rise 0.6 px in front of the face (both
    # forearms 4.7-6.5 px inside the head at 0.28-0.30 s); they now travel a
    # hand's width further forward and the pick head is cocked steeper up.
    (0.18, (-0.8, -11.0, -6.6), (0.0, -0.80, -0.60), None),  # rising
    (TOP, (-1.2, -14.3, -6.7), (0.0, -0.72, 0.69), INQ),     # cocked: fists above the brow, head behind
    (0.34, (-1.2, -14.0, -6.8), (0.0, -0.78, 0.62), INQ),    # hang, then accelerate
    (0.40, (-0.6, -11.5, -6.9), (0.0, -0.90, -0.30), LIN),   # fists lead, the head lags over the top
    (CONTACT, (0.8, -5.8, -5.4), (0.0, -0.80, -0.60), OUTS),  # point in the rock
    (0.49, (0.8, -6.2, -5.1), (0.0, -0.84, -0.54), None),    # jolt back off the stop
    (0.62, (0.8, -5.6, -5.4), (0.0, -0.78, -0.62), None),    # lodged, levering
    (0.72, (0.8, -8.5, -4.0), (0.0, -0.95, -0.30), None),    # wrench free
    (0.84, (0.5, -6.5, -5.2), (0.03, -0.45, -0.89), None),   # settle
]
if v2:
    GOALS = [g for g in GOALS if g[0] < 0.60] + [
        (0.62, (0.8, -5.7, -5.4), (0.0, -0.79, -0.61), None),
        (0.70, (0.8, -5.6, -5.4), (0.0, -0.78, -0.62), INQ),  # still lodged: listening
        (0.79, (0.8, -8.5, -4.0), (0.0, -0.95, -0.30), None),  # quick wrench
        (0.88, (0.5, -6.2, -5.2), (0.03, -0.40, -0.91), None),
    ]
arm_keys = {k: [] for k in ("arm_r_x", "arm_r_y", "arm_r_z", "elbow_r", "fore_r_twist")}
seed = [-30.0, -30.0, -30.0, -20.0, 0.0]
LOG = []
for t, hand, hdir, ease in GOALS:
    sol, err = solve_right(hand, hdir, seed)
    seed = sol
    LOG.append({"t": t, "sol": [round(v, 1) for v in sol], "cost": round(err, 3)})
    for name, v in zip(arm_keys, sol):
        e = ease
        if name == "elbow_r" and ease == INQ:
            e = IN4
        arm_keys[name].append((t, v, *e) if e else (t, v))
for name, keys in arm_keys.items():
    keys.append((LENGTH, keys[0][1]))
    hsrig.key_curve(name, keys, cyclic=True, length=LENGTH)
print("GOALS", json.dumps(LOG))


def arms_at(t):
    ch = body(t)
    ch["right_arm"] = {"rot": (c("arm_r_x", t), c("arm_r_y", t), c("arm_r_z", t))}
    ch["right_forearm"] = {"rot": (c("elbow_r", t), c("fore_r_twist", t), 0.0)}
    return ch


# The rock face is placed where the solved swing puts the point (it bites 0.6 px in).
STRIKE = pick_points(mcrig.pose_matrices(arms_at(CONTACT))["right_forearm"])["tip"]
FACE_Z = float(STRIKE[2]) + 0.6
hsrig.prop_box("rock", (-16, -8, FACE_Z - 16), (32, 32, 16), (0.47, 0.47, 0.48, 1))
print("STRIKE", STRIKE.round(2).tolist(), "FACE_Z", round(FACE_Z, 2))

LEFT_GRIP_LEN = 5.5
prev = {}


def haft_point(world, s):
    pp = pick_points(world["right_forearm"])
    axis = (pp["neck"] - pp["knob"]) / np.linalg.norm(pp["neck"] - pp["knob"])
    hand = mcrig.xform(world["right_forearm"], (0, 5.5, 0))
    base = pp["knob"] + axis * float((hand - pp["knob"]) @ axis)
    return base + axis * s


def solve(t):
    t = wrap(t)
    ch = body(t)
    ch["right_arm"] = {"rot": (c("arm_r_x", t), c("arm_r_y", t), c("arm_r_z", t))}
    ch["right_forearm"] = {"rot": (c("elbow_r", t), c("fore_r_twist", t), 0.0)}
    pv = lkit.vel(lambda u: c("spine_x", u), t - 0.05, LENGTH)
    ch["cloak"] = {"rot": (max(-16.0, min(18.0, 3.0 + 0.35 * c("spine_x", t) - 0.05 * pv)), 0.0, 0.0)}
    world = mcrig.pose_matrices(ch)
    # head: eyes on the strike point + authored nod (v2: cocks toward the face)
    lkit.head_aim(ch, STRIKE + np.array([0, -1.0, 0]), w_pitch=0.55, w_yaw=0.8, nod=c("head_nod", t))
    hx, hy, hz = ch["head"]["rot"]
    ch["head"]["rot"] = (hx, hy, hz + c("head_tilt", t))
    # left hand on the haft below the right hand
    target = haft_point(world, c("grip_s", t))
    tl = mcrig.xform(np.linalg.inv(world["torso"]), target)
    solve.err = lkit.arm_ik_local(ch, "left", tl, (0.8, 0.5, 0.35), prev, lower=LEFT_GRIP_LEN)
    lkit.legs_ik(ch, (FOOT_R, FOOT_L), prev, knee=(0.0, 0.0, -1.0))
    return ch


times, samples = hsrig.bake(solve, LENGTH, objs)

# --------------------------------------------------------------------------- checks
tips, grip, foot = [], 0.0, 0.0
for t, s in zip(times, samples):
    w = mcrig.pose_matrices(s)
    tips.append(pick_points(w["right_forearm"])["tip"])
    lp = mcrig.xform(w["left_forearm"], (0, LEFT_GRIP_LEN, 0))
    grip = max(grip, float(np.linalg.norm(lp - haft_point(w, c("grip_s", t % LENGTH)))))
    for side, f in (("right", FOOT_R), ("left", FOOT_L)):
        foot = max(foot, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - f)))
tips = np.array(tips)
speed = np.linalg.norm(np.diff(tips, axis=0), axis=1) * hsrig.FPS
cf = int(round(CONTACT * hsrig.FPS))
checks = {
    "length_s": LENGTH, "loop": True, "contact_t": CONTACT, "contact_tick": 9,
    "tip_speed_peak_t": round(int(np.argmax(speed)) / hsrig.FPS, 3),
    "tip_speed_px_s_into_contact": round(float(speed[cf - 1]), 1),
    "tip_speed_px_s_after_contact": round(float(speed[cf + 1]), 1),
    "tip_at_contact": [round(float(v), 2) for v in tips[cf]],
    "tip_depth_past_face_px": round(FACE_Z - float(tips[cf][2]), 2),
    "tip_min_z_px": round(float(tips[:, 2].min()), 2),
    "left_hand_haft_err_px_max": round(grip, 3),
    "foot_slide_px_max": round(foot, 3),
    "seam_max": lkit.seam(samples),
    "goal_solves": LOG,
}
print("CHECKS", json.dumps(checks))

meta = {"source": "tools/blender/pipeline/clips/logistics/author_mine_pick.py (Blender "
                  + bpy.app.version_string + ")" + (" variant " + VARIANT if VARIANT else ""),
        "contract": "length 0.95 s loop; pick contact t=0.45 s = tick 9 of 19 (MinerWorkGoal PICK_STRIKE)",
        "checks": checks}
if A["export"]:
    lkit.export(CONST, LENGTH, True, times, samples, keep_times=(TOP, CONTACT), meta=meta)
if A["fast"] or A["full"]:
    cams = {"side": hsrig.camera("cam_side", (-3.4, -0.5, 1.0), (0.0, -0.45, 0.8), lens=42),
            "front34": hsrig.camera("cam_front34", (-2.8, -2.4, 1.5), (0.0, -0.2, 0.85), lens=38)}
    lkit.preview(SLUG, LENGTH, cams=cams, full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, LENGTH, (-1.6, -2.8, 1.25), (0.1, -0.3, 0.75), hide=("rock",))
