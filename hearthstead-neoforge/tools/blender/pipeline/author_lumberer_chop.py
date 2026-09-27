"""Lumberer CHOP, authored in Blender. Run headless:

    blender -b --factory-startup --python author_lumberer_chop.py -- [--render] [--out DIR]

Contract kept from SettlerAnimations.CHOP / LumbererWorkGoal (do not change):
  length 1.0 s looping, axe contact at t = 0.55 s = tick 11 of 20 (CHOP_CONTACT_TICK),
  which WorkSoundSync plays the chop sound on.

Timing reference: CMU mocap 79_01 "chopping wood" (tools/mocap, analysed by
analyze_mocap.py): wind-up 0.34 s, top -> impact 0.18 s, peak hand speed at the
impact, hips reverse before the hands reach the top, pelvis dips ~6% of hip
height while loading. Those ratios place the keys below around the fixed
0.55 s contact:  wind-up 0.03 -> 0.37, downswing 0.37 -> 0.55 (ease-in:
accelerating, fastest at contact), stop + small recoil 0.55 -> 0.60,
wrench-out 0.62 -> 0.72, settle to ready 0.72 -> 1.00.

Rig use: pelvis (root) yaw/shift/dip and spine are FK controls; the right arm is
FK (shoulder + elbow) so the axe arc is authored directly; the LEFT hand is IK
to a point on the real held-axe haft; both legs are IK to feet planted on the
ground, so pelvis motion bends the knees instead of sliding the feet; the head
aims at the trunk; the cloak is simulated drag from the spine's yaw velocity.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
# --variant v2|v3 authors a chop__v2 / chop__v3 variant (same length + contact tick):
#   v2  the bit sticks in the cut: hold, lean back, two-stage yank free
#   v3  a glance up at the canopy while resetting (is it about to go?)
VARIANT = argv[argv.index("--variant") + 1] if "--variant" in argv else ""
CLIP = "chop" + ("__" + VARIANT if VARIANT else "")
OUT = argv[argv.index("--out") + 1] if "--out" in argv else os.path.join(WORK, "out", CLIP)
TEX = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                   "entity", "settler", "settler_lumberer.png")
AXE = os.path.join(WORK, "ref", "assets", "minecraft", "textures", "item", "iron_axe.png")
CLIP_JSON = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations",
                         "settler", CLIP + ".animation.json")

LENGTH = 1.0
CONTACT = 0.55
TOP = 0.37          # mocap: top -> impact 0.18 s
WIND0 = 0.03        # mocap: wind-up 0.34 s

# Planted feet (model px, ground = y 24). Left foot forward, right foot back,
# a little wider than the hips: the chopping stance.
FOOT_R = np.array([-3.4, 24.0, 1.6])
FOOT_L = np.array([3.2, 24.0, -1.8])
# Trunk the swing is aimed at: the tree in the next block, face 8 px ahead.
TRUNK_FACE_Z = -8.0

os.makedirs(OUT, exist_ok=True)
sc = hsrig.reset()
objs = hsrig.build_scene(TEX, AXE)
hsrig.prop_box("ground", (-40, 24, -40), (80, 1, 80), (0.30, 0.45, 0.22, 1))
hsrig.prop_box("trunk", (-4, -40, -16), (8, 64, 8), (0.38, 0.27, 0.16, 1))  # slim marker; face at z=-8

# --------------------------------------------------------------------------- keys
# (t, value[, interp, easing]).  Interp/easing apply to the segment LEAVING the key.
IN = ("CUBIC", "EASE_IN")          # accelerate (downswing): fastest at the contact
IN4 = ("QUART", "EASE_IN")         # elbow releases later than the shoulder (whip/lag)
OUTS = ("SINE", "EASE_OUT")        # decelerate (recoil, settle)
INQ = ("QUAD", "EASE_IN")          # arm: accelerate out of the top ...
LIN = ("LINEAR", "AUTO")           # ... and arrive at full speed at the contact
K = {
    # pelvis = root: sideways weight shift (+x = settler's left), dip (posVec y), yaw.
    # Weight goes onto the back (right) foot in the wind-up, the hips reverse
    # before the hands reach the top (mocap), and drive onto the front foot.
    "pelvis_x":   [(0.0, 0.1), (0.14, -0.45), (0.30, -0.9), (0.40, -0.7, *IN), (0.55, 0.8, *OUTS),
                   (0.66, 0.7), (0.82, 0.35), (1.0, 0.1)],
    "pelvis_y":   [(0.0, -0.35), (0.14, -0.45), (0.30, -1.0), (0.40, -0.6, *IN), (0.55, -0.5, *OUTS),
                   (0.62, -0.85), (0.76, -0.55), (1.0, -0.35)],
    "pelvis_yaw": [(0.0, 1.0), (0.14, 5.0), (0.27, 9.0), (0.37, 6.0, *IN), (0.55, -7.0, *OUTS),
                   (0.64, -8.0), (0.80, -2.5), (1.0, 1.0)],
    # spine = torso relative to pelvis: pitch (POSITIVE = lean forward), twist, side bend
    "spine_x":    [(0.0, 5.0), (0.20, 2.0), (0.34, -3.0), (0.37, -2.5, *IN), (0.55, 10.0, *OUTS),
                   (0.62, 11.5), (0.76, 7.5), (1.0, 5.0)],
    "spine_yaw":  [(0.0, 0.0), (0.16, 7.0), (0.33, 13.0), (0.37, 12.5, *IN), (0.55, -3.0, *OUTS),
                   (0.63, -4.5), (0.78, -1.5), (1.0, 0.0)],
    "spine_z":    [(0.0, 0.0), (0.30, 2.0), (0.37, 2.0, *IN), (0.55, -2.0, *OUTS), (0.65, -1.5),
                   (1.0, 0.0)],
    # QA 2026-09-26: ONE-handed chop with a counter-swinging left arm. On this rig
    # the left palm cannot reach the haft anywhere in the downswing (the grip point
    # is 11-14 px from the left shoulder, the arm reaches 8.6), so the old re-catch
    # left the hand hovering 3-6 px off the handle and dragged the forearm through
    # the face at 0.49-0.51 s. Now the left arm guides across the chest in the
    # wind-up and swings back past the left hip through the blow, arriving a few
    # frames after the axe (follow-through), then settles to the ready pose.
    "grip_w":     [(0.0, 0.0), (1.0, 0.0)],
    "grip_s":     [(0.0, 1.0), (0.55, 1.0), (1.0, 1.0)],
    "arm_l_x":    [(0.0, -18.0), (0.14, -30.0), (0.30, -45.0), (0.40, -47.0, *IN), (0.58, 12.0, *OUTS),
                   (0.66, 14.0), (0.84, -6.0), (1.0, -18.0)],
    "arm_l_y":    [(0.0, 6.0), (0.14, 14.0), (0.30, 26.0), (0.40, 26.0, *IN), (0.58, -6.0, *OUTS),
                   (0.66, -8.0), (0.84, 2.0), (1.0, 6.0)],
    "arm_l_z":    [(0.0, -4.0), (0.37, -2.0, *IN), (0.58, -10.0, *OUTS), (0.70, -8.0), (1.0, -4.0)],
    "elbow_l":    [(0.0, -22.0), (0.14, -35.0), (0.30, -60.0), (0.40, -62.0, *IN), (0.58, -18.0, *OUTS),
                   (0.66, -20.0), (0.84, -25.0), (1.0, -22.0)],
    # head: extra nod on top of the aim-at-trunk constraint
    "head_nod":   [(0.0, 4.0), (0.37, 2.0, *IN), (0.55, 8.0, *OUTS), (0.64, 9.0), (1.0, 4.0)],
}
VARIANT_K = {
    "v2": {  # stuck bit: body holds, sits back against the haft, then yanks
        "pelvis_x": [(0.0, 0.1), (0.14, -0.45), (0.30, -0.9), (0.40, -0.7, *IN), (0.55, 0.8, *OUTS),
                     (0.62, 0.8), (0.68, 0.1), (0.73, -0.35), (0.86, 0.1), (1.0, 0.1)],
        "pelvis_y": [(0.0, -0.35), (0.14, -0.45), (0.30, -1.0), (0.40, -0.6, *IN), (0.55, -0.5, *OUTS),
                     (0.62, -0.9), (0.68, -1.1), (0.73, -0.5), (0.86, -0.4), (1.0, -0.35)],
        "spine_x":  [(0.0, 5.0), (0.20, 2.0), (0.34, -3.0), (0.37, -2.5, *IN), (0.55, 10.0, *OUTS),
                     (0.62, 10.5), (0.68, 3.0), (0.73, -4.0), (0.86, 4.0), (1.0, 5.0)],
        "head_nod": [(0.0, 4.0), (0.37, 2.0, *IN), (0.55, 8.0, *OUTS), (0.64, 10.0), (0.70, 3.0),
                     (0.76, 6.0), (1.0, 4.0)],
    },
    "v3": {  # canopy glance during the reset
        "head_nod": [(0.0, 4.0), (0.37, 2.0, *IN), (0.55, 8.0, *OUTS), (0.64, 8.0), (0.74, -30.0),
                     (0.88, -27.0), (1.0, 4.0)],
        "spine_x":  [(0.0, 5.0), (0.20, 2.0), (0.34, -3.0), (0.37, -2.5, *IN), (0.55, 10.0, *OUTS),
                     (0.62, 11.5), (0.76, 1.5), (0.88, 2.0), (1.0, 5.0)],
        "pelvis_y": [(0.0, -0.35), (0.14, -0.45), (0.30, -1.0), (0.40, -0.6, *IN), (0.55, -0.5, *OUTS),
                     (0.62, -0.85), (0.76, -0.3), (0.88, -0.3), (1.0, -0.35)],
    },
}
K.update(VARIANT_K.get(VARIANT, {}))
for prop, keys in K.items():
    hsrig.key_curve(prop, keys, cyclic=True, length=LENGTH)

c = hsrig.ctrl


def wrap(t):
    return t % LENGTH


def body(t):
    """Pelvis + spine channels at time t (everything the arm goals depend on)."""
    return {
        "root": {"rot": (0.0, c("pelvis_yaw", t), 0.0),
                 "pos": (c("pelvis_x", t), c("pelvis_y", t), 0.0)},
        "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), c("spine_z", t))},
    }


# Right arm: ONE-PLANE FK swing (2026-09-25 rework; owner: "the axe swung a bit weird").
# The held axe is rigid in the fist: the haft sits across the forearm and the cutting
# edge points along the forearm (vanilla ItemInHandLayer + item/handheld). The old
# goal solver matched hand position + haft direction per key and was free to pick
# any roll, so the blade twisted about its handle between keys and the wrist
# flipped between the top and the downswing.
# Now the swing is authored the way a real overhand chop works on this rig:
#   * arm_r_y / arm_r_z are HELD CONSTANT from wind-up through contact, so they only
#     orient the swing plane (a diagonal, high-right -> low-left chop);
#   * the swing itself is shoulder flexion (arm_r_x) + elbow extension, both rotations
#     about the same local axis, so haft, forearm and edge stay in that plane:
#     zero roll about the handle, edge leading, no wrist twist (fore_r_twist = 0);
#   * wind-up: axe lifted over the right shoulder, head back (x -168, elbow -105);
#   * downswing: shoulder on a quadratic ease-in, elbow released later on a quartic
#     ease-in (the whip), both arriving at full speed on the 0.55 s contact;
#   * the only roll is a small wrench-out (twist -10) after impact.
IN2 = ("QUAD", "EASE_IN")
# QA 2026-09-26: the old plane (y -28, z +8) tilted the swing INWARD, so both
# hands and the lower haft passed through the face at 0.47-0.53 s (forearm 4.7 px
# inside the head) and the left arm flipped as it re-caught the haft there. The
# plane now stands upright beside the head (y 0, z -16: the arm swings slightly
# out, past the right ear); the contact pose is re-aimed so the edge still bites
# ~5 px into the trunk face at waist height (x -2, y 12, z -13), same tick.
PLANE_Y, PLANE_Z = 0.0, -16.0
ARM = {
    "base": {
        "arm_r_x": [(0.0, -16.0), (0.14, -85.0), (0.28, -148.0), (0.37, -168.0, *IN2),
                    (0.55, -8.0, *OUTS), (0.60, -13.0), (0.72, -25.0), (0.86, -14.0), (1.0, -16.0)],
        "elbow_r": [(0.0, -20.0), (0.14, -62.0), (0.28, -95.0), (0.37, -105.0, *IN4),
                    (0.55, -25.0, *OUTS), (0.60, -32.0), (0.72, -46.0), (0.86, -26.0), (1.0, -20.0)],
        "arm_r_y": [(0.0, PLANE_Y), (0.55, PLANE_Y, *OUTS), (0.72, PLANE_Y + 8.0), (1.0, PLANE_Y)],
        "arm_r_z": [(0.0, PLANE_Z), (0.55, PLANE_Z, *OUTS), (0.72, PLANE_Z + 2.0), (1.0, PLANE_Z)],
        "fore_r_twist": [(0.0, 0.0), (0.55, 0.0, *OUTS), (0.72, -10.0), (0.86, -3.0), (1.0, 0.0)],
    },
}
ARM["v2"] = dict(ARM["base"], **{   # bit sticks: hold in the cut, heave, two-stage yank
    "arm_r_x": ARM["base"]["arm_r_x"][:5] + [(0.62, -8.0), (0.68, -5.0), (0.73, -33.0),
                                             (0.86, -14.0), (1.0, -16.0)],
    "elbow_r": ARM["base"]["elbow_r"][:5] + [(0.62, -25.0), (0.68, -20.0), (0.73, -58.0),
                                             (0.86, -26.0), (1.0, -20.0)],
    "fore_r_twist": [(0.0, 0.0), (0.55, 0.0, *OUTS), (0.68, 3.0), (0.73, -12.0), (0.86, -3.0),
                     (1.0, 0.0)],
})
ARM["v3"] = dict(ARM["base"], **{   # glance at the canopy: axe lowered and held still
    "arm_r_x": ARM["base"]["arm_r_x"][:6] + [(0.74, -12.0), (0.88, -15.0), (1.0, -16.0)],
    "elbow_r": ARM["base"]["elbow_r"][:6] + [(0.74, -26.0), (0.88, -22.0), (1.0, -20.0)],
})
for name, keys in ARM.get(VARIANT or "base", ARM["base"]).items():
    hsrig.key_curve(name, keys, cyclic=True, length=LENGTH)
GOAL_LOG = [{"plane_y": PLANE_Y, "plane_z": PLANE_Z}]
TRUNK_Z = TRUNK_FACE_Z
PLANE_N = None   # measured from the bake (best-fit plane of the downswing haft directions)


# --------------------------------------------------------------------------- solve
def handle_point(world, s):
    """World point on the held axe's haft, s px from the right hand toward the head."""
    item = mcrig.item_in_hand_matrix(world["right_forearm"])
    knob = mcrig.xform(item, mcrig.AXE_KNOB)
    neck = mcrig.xform(item, mcrig.AXE_NECK)
    axis = (neck - knob) / np.linalg.norm(neck - knob)
    hand = mcrig.xform(world["right_forearm"], (0, 6, 0))   # arm-local y=10
    base = knob + axis * float((hand - knob) @ axis)         # the right hand on the haft
    return base + axis * s


def blade_tip(world):
    """Middle of the cutting edge (was the poll before the 2026-09-25 rework)."""
    item = mcrig.item_in_hand_matrix(world["right_forearm"])
    return mcrig.xform(item, mcrig.AXE_EDGE)


def blade_frame(world):
    return mcrig.axe_frame(mcrig.item_in_hand_matrix(world["right_forearm"]))


LEFT_GRIP_LEN = 4.6     # elbow pivot -> left palm centre (arm-local y 8.6)


def solve(t):
    t = wrap(t)
    ch = body(t)
    # body turn (hips lead, shoulders follow) is compensated at the shoulder so the
    # axe's swing plane stays fixed in the WORLD: the turn is readable in the body,
    # the blade never rolls about its handle because of it.
    yaw_c = c("pelvis_yaw", CONTACT) + c("spine_yaw", CONTACT)
    comp = (c("pelvis_yaw", t) + c("spine_yaw", t)) - yaw_c
    ch["right_arm"] = {"rot": (c("arm_r_x", t), c("arm_r_y", t) - comp, c("arm_r_z", t))}
    ch["right_forearm"] = {"rot": (c("elbow_r", t), c("fore_r_twist", t), 0.0)}
    # cloak drag: lags the body's twist velocity and the spine pitch
    dt = 1.0 / 60.0

    def twist(u):
        return c("spine_yaw", wrap(u)) + c("pelvis_yaw", wrap(u))
    vel = (twist(t - 0.04) - twist(t - 0.04 - dt)) / dt
    pitch_v = (c("spine_x", wrap(t - 0.05)) - c("spine_x", wrap(t - 0.05 - dt))) / dt
    ch["cloak"] = {"rot": (max(-14.0, min(14.0, 2.0 - 0.06 * pitch_v)), 0.0,
                           max(-10.0, min(10.0, -0.018 * vel)))}
    world = mcrig.pose_matrices(ch)
    prev = getattr(solve, "_prev", {})

    # head aims at the cut on the trunk (eyes on target), plus the authored nod
    tgt = np.array([0.0, 24.0 - 17.0, TRUNK_FACE_Z])
    d_local = np.linalg.inv(world["torso"][:3, :3]) @ (tgt - mcrig.xform(world["torso"], (0, -12, 0)))
    yaw_h = math.degrees(math.atan2(-d_local[0], -d_local[2]))
    pitch_h = math.degrees(math.atan2(d_local[1], math.hypot(d_local[0], d_local[2])))
    ch["head"] = {"rot": (0.55 * pitch_h + c("head_nod", t), 0.8 * yaw_h, 0.0)}

    # left arm: two-bone IK to the haft, blended with the free FK arm by grip_w
    w_grip = max(0.0, min(1.0, c("grip_w", t)))
    target = handle_point(world, c("grip_s", t))
    tl = mcrig.xform(np.linalg.inv(world["torso"]), target)
    r, flex, _ = mcrig.two_bone(np.array([6.0, -10.0, 0.0]), tl, mcrig.UPPER_ARM, LEFT_GRIP_LEN,
                                np.array([0.8, 0.5, 0.4]), -1)
    ik = hsrig.euler_deg_continuous(r, prev.get("left_arm_ik"))
    fk = [c("arm_l_x", t), c("arm_l_y", t), c("arm_l_z", t)]
    fk = [f + 360.0 * round((i - f) / 360.0) for f, i in zip(fk, ik)]
    ch["left_arm"] = {"rot": tuple(f + (i - f) * w_grip for f, i in zip(fk, ik))}
    ch["left_forearm"] = {"rot": (c("elbow_l", t) + (math.degrees(flex) - c("elbow_l", t)) * w_grip,
                                  0.0, 0.0)}

    # legs: two-bone IK to planted feet, knees toward the feet's own forward
    inv_root = np.linalg.inv(world["root"])
    for side, foot, hip_x in (("right", FOOT_R, -2.6), ("left", FOOT_L, 2.6)):
        fl = mcrig.xform(inv_root, foot)
        pole = inv_root[:3, :3] @ np.array([0.12 * np.sign(hip_x), 0.0, -1.0])
        r, flex, _ = mcrig.two_bone(np.array([hip_x, -12.0, 0.0]), fl, mcrig.THIGH,
                                    mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
        ch[side + "_leg"] = {"rot": tuple(hsrig.euler_deg_continuous(r, prev.get(side + "_leg")))}
        ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
    solve._prev = {"left_arm_ik": ik, "right_leg": ch["right_leg"]["rot"],
                   "left_leg": ch["left_leg"]["rot"]}
    lf = mcrig.pose_matrices(ch)["left_forearm"]
    solve._grip = (w_grip, float(np.linalg.norm(mcrig.xform(lf, (0, 4.6, 0)) - target)))
    return ch


times, samples = hsrig.bake(solve, LENGTH, objs)

# --------------------------------------------------------------------------- checks
def world_of(s):
    return mcrig.pose_matrices(s)


foot_err = 0.0
tip_path = []
for t, s in zip(times, samples):
    w = world_of(s)
    for side, foot in (("right", FOOT_R), ("left", FOOT_L)):
        sole = mcrig.xform(w[side + "_shin"], (0, 6, 0))
        foot_err = max(foot_err, float(np.linalg.norm(sole - foot)))
    tip_path.append(blade_tip(w))
tip_path = np.array(tip_path)
# Blade orientation metrics (owner: "the axe swung a bit weird")
frames_bl = [blade_frame(world_of(s_)) for s_ in samples]
_hd = np.array([f[0] for f, t in zip(frames_bl, times) if TOP <= t <= CONTACT])
PLANE_N = np.linalg.svd(_hd - 0.0)[2][-1]      # normal of the best-fit plane through the origin
plane_dev, edge_lead, roll_rate = [], [], []
for i, (h, e, nb) in enumerate(frames_bl):
    plane_dev.append(math.degrees(math.acos(min(1.0, abs(float(nb @ PLANE_N))))))
    if i:
        h0, e0, _ = frames_bl[i - 1]
        # twist about the handle between frames (parallel-transport e0 onto h)
        e0p = e0 - h * float(e0 @ h)
        e0p /= np.linalg.norm(e0p)
        roll_rate.append(math.degrees(math.atan2(float(np.cross(e0p, e) @ h), float(e0p @ e))))
        v = tip_path[i] - tip_path[i - 1]
        if TOP <= times[i] <= CONTACT and np.linalg.norm(v) > 1e-6:
            edge_lead.append(math.degrees(math.acos(max(-1.0, min(1.0, float(e @ (v / np.linalg.norm(v))))))))
cum = np.cumsum([0.0] + roll_rate)
def _span(a, b):
    idx = [i for i, t in enumerate(times) if a <= t <= b]
    return float(max(cum[idx]) - min(cum[idx])) if idx else 0.0
speed = np.linalg.norm(np.diff(tip_path, axis=0), axis=1) * hsrig.FPS  # px/s
peak_frame = int(np.argmax(speed))
contact_frame = int(round(CONTACT * hsrig.FPS))
tip_at_contact = tip_path[contact_frame]
grip_err = 0.0
for t in times:
    solve(t)
    if solve._grip[0] > 0.99:
        grip_err = max(grip_err, solve._grip[1])
        print("GRIP", round(t, 3), round(solve._grip[1], 2))
checks = {
    "foot_slide_px_max": round(foot_err, 3),
    "blade_speed_peak_frame": peak_frame, "blade_speed_peak_t": round(peak_frame / hsrig.FPS, 3),
    "contact_frame": contact_frame,
    "blade_speed_px_s_before_contact": round(float(speed[contact_frame - 1]), 1),
    "blade_speed_px_s_after_contact": round(float(speed[contact_frame + 1]), 1),
    "blade_tip_at_contact_model_px": [round(float(v), 2) for v in tip_at_contact],
    "blade_height_above_ground_px": round(24.0 - float(tip_at_contact[1]), 2),
    "blade_depth_past_trunk_face_px": round(TRUNK_FACE_Z - float(tip_at_contact[2]), 2),
    "left_hand_to_haft_px_max_while_gripping": round(grip_err, 3),
    "blade_plane_vs_swing_plane_deg_max_windup_to_contact": round(max(
        d for d, t in zip(plane_dev, times) if 0.2 <= t <= CONTACT), 2),
    "handle_roll_span_deg_windup_to_contact": round(_span(0.0, CONTACT), 2),
    "handle_roll_span_deg_after_contact": round(_span(CONTACT, 1.0), 2),
    "edge_vs_travel_deg_max_downswing": round(max(edge_lead) if edge_lead else -1, 2),
    "goal_solves": GOAL_LOG,
}
print("CHECKS", json.dumps(checks))

# --------------------------------------------------------------------------- export
chan = hsrig.to_export_channels(times, samples)
meta = {"source": "tools/blender/pipeline/author_lumberer_chop.py (Blender "
                  + bpy.app.version_string + ")",
        "contract": "length 1.0 s loop; axe contact t=0.55 s = tick 11 of 20 (LumbererWorkGoal)",
        "mocap_reference": "CMU 79_01 chopping wood (mocap.cs.cmu.edu)",
        "checks": checks}
doc, report = ex.build_bedrock("animation.settler." + CLIP, LENGTH, True, times, chan,
                               rot_tol=0.25, pos_tol=0.02,
                               keep_times=(0.0, TOP, CONTACT, 1.0), meta=meta)
os.makedirs(os.path.dirname(CLIP_JSON), exist_ok=True)
if hsrig.parse_args()["export"]:
    ex.write(doc, CLIP_JSON)
    # Engine agent's edge-first wrist pass (right_item roll over the downswing, zero
    # at contact). Re-applied on every export so it is never silently dropped.
    import subprocess
    _wp = os.path.join(REPO, "tools", "motion", "wrist_edge_first.py")
    if os.path.exists(_wp):
        r_ = subprocess.run([sys.executable, _wp, CLIP, "--from", "0.40", "--to", "0.55"],
                            capture_output=True, text=True)
        print("WRIST_PASS", r_.returncode, (r_.stdout + r_.stderr).strip()[-300:])
else:
    print("NO-EXPORT: left", CLIP_JSON, "untouched")
ex.write(doc, os.path.join(OUT, CLIP + ".animation.json"))
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report}, fh, indent=1)
print("EXPORTED", CLIP_JSON, sum(v["keys"] for v in report.values()), "keys")

# verify: the exported JSON, evaluated with the runtime interpolation, matches the bake
worst = 0.0
_lagged = {"head"} if "overlap_pass" in doc.get("hearthstead_meta", {}) else set()
for b, kinds in chan.items():
    if b in _lagged:        # QA overlap pass deliberately adds head lag on write
        continue
    for kind, vecs in kinds.items():
        if b not in doc["animations"]["animation.settler." + CLIP]["bones"] \
                or kind not in doc["animations"]["animation.settler." + CLIP]["bones"][b]:
            continue
        for t, v in zip(times, vecs):
            got = ex.sample(doc, "animation.settler." + CLIP, b, kind, t)
            worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
print("ROUNDTRIP_MAX_ERR", round(worst, 4))

bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, "lumberer_" + CLIP + ".blend"))

# --------------------------------------------------------------------------- preview
ARGS = hsrig.parse_args()
if ARGS["fast"] or ARGS["full"]:
    hsrig.preview(OUT, LENGTH, fast=not ARGS["full"], full=ARGS["full"])
    # top view: the arc plane of the swing seen from above
    top = hsrig.camera("cam_top", (-0.3, -0.3, 4.2), (-0.05, -0.3, 0.9), lens=40)
    top.constraints[0].up_axis = "UP_Y"
    idx = [int(round(i * 60 / 12)) for i in range(12)]
    hsrig.render_frames(top, os.path.join(OUT, "_top_frames"), idx)
    hsrig._sheet([os.path.join(OUT, "_top_frames", f"f{f:04d}.png") for f in idx],
                 os.path.join(OUT, "sheet_top.png"))
