"""Combat clip kit: guard / archer rig, weapons, planted-foot solve, additive export.

Shared by every script in tools/blender/pipeline/clips/combat/ (the combat,
defense and guard-moveset clips). Builds on the lead's hsrig / mcrig /
motionkit / export_mc_clip WITHOUT modifying them.

Conventions (mcrig, verified against the lead's chop/walk renders):
  model px, +Y DOWN, the settler faces -Z, +X = settler's LEFT, ground y = 24.
  arm/leg X: NEGATIVE swings the limb FORWARD.  torso X: POSITIVE leans forward.
  head X: positive looks down.  yaw (Y) POSITIVE turns the front toward the
  settler's RIGHT (-X).  elbow flex = NEGATIVE forearm X, knee flex = POSITIVE shin X.

A clip is authored as control F-curves (Blender Bezier keys with per-key
easing) on the CTRL object:

  hip_x hip_y hip_z        root posVec px (y up)          (pelvis shift / dip)
  hip_p hip_yaw hip_r      root rotation deg
  sp_x sp_y sp_z sp_lift   torso rotation deg, torso posVec y
  hd_x hd_y hd_z           head rotation deg
  ar_x ar_y ar_z el_r tw_r right arm FK (arm rot, elbow flex, forearm twist)
  al_x al_y al_z el_l tw_l left arm FK
  gl_w                     0..1 left hand IK onto the sword grip (two-handed)
  fr_x fr_y fr_z fl_x ...  foot offsets from the planted stance feet (steps)
  ck_x ck_z                cloak base rotation (drag is added procedurally)

Arm FK keys for weapon beats are SOLVED from world goals (hand point + blade
direction [+ tip point]) with mcrig.nelder_mead, so in-betweens travel on
joint arcs, and the contact pose is exact.

Additive clips (MELEE family) are baked as ABSOLUTE poses over the guard's
stance base, then exported as per-channel offsets from STANCE_BASE -- exactly
the runtime's additive grammar (channel sums), so stance + clip reproduces
the authored pose.
"""

from __future__ import annotations

import json
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Matrix

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
if PIPE not in sys.path:
    sys.path.insert(0, PIPE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
REF = os.path.join(WORK, "ref_combat", "assets", "minecraft", "textures")
SETTLER_TEX = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                           "entity", "settler")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations",
                        "settler")
VIDEO_ROOT = os.environ.get("HS_COMBAT_VIDEOS",
                            r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\combat")
SWORD_PNG = os.path.join(REF, "item", "iron_sword.png")
BOW_PNG = os.path.join(REF, "item", "bow.png")
SHIELD_PNG = os.path.join(REF, "entity", "shield_base_nopattern.png")
FPS = hsrig.FPS
c = hsrig.ctrl

# --------------------------------------------------------------------------- items
# Vanilla display transforms (thirdperson_righthand; the left hand mirrors x
# translation and y/z rotation exactly like ItemTransform.apply(leftHand)).
HANDHELD = ((0.0, -90.0, 55.0), (0.0, 4.0, 0.5), 0.85)        # item/handheld (sword, axe)
BOW_DISPLAY = ((-80.0, 260.0, -40.0), (-1.0, -2.0, 2.5), 0.9)  # item/bow
SHIELD_DISPLAY = ((0.0, 90.0, 0.0), (10.0, 6.0, 12.0), 1.0)    # item/shield thirdperson_lefthand
# Iron sword sprite landmarks, item px (x right, y UP), pixel centres.
SWORD_POMMEL = (1.5, 1.5, 8.0)
SWORD_GUARD = (5.5, 5.5, 8.0)
SWORD_TIP = (15.0, 15.0, 8.0)


def display_matrix(forearm_world, display, right=True):
    """Held-item frame (item px, 0..16 cube) for a forearm world matrix."""
    (rx, ry, rz), (tx, ty, tz), s = display
    side = 1 if right else -1
    m = forearm_world @ mcrig.T(0, -4, 0)
    m = m @ mcrig.mat4(mcrig.rx(-math.pi / 2)) @ mcrig.mat4(mcrig.ry(math.pi)) @ mcrig.T(side * 1, 2, -10)
    r = mcrig.rx(math.radians(rx)) @ mcrig.ry(math.radians(side * ry)) @ mcrig.rz(math.radians(side * rz))
    m = m @ mcrig.T(side * tx, ty, tz) @ mcrig.mat4(r, s=s) @ mcrig.T(-8, -8, -8)
    return m


def item_base(world, side):
    """Forearm-equivalent frame for the held item: the wrist bone (right_item /
    left_item, pivot at the palm = forearm-local (0,6,0)) @ T(0,-6,0). With an
    identity wrist this is exactly the forearm frame (vanilla hold)."""
    w = world.get(side + "_item")
    return w @ mcrig.T(0, -6, 0) if w is not None else world[side + "_forearm"]


def sword_frame(world):
    return display_matrix(item_base(world, "right"), HANDHELD, True)


SWORD_EDGE_ITEM = np.array([-1.0, 1.0, 0.0]) / math.sqrt(2.0)


def sword_points(world):
    """World (model px) pommel, guard, tip of the MAINHAND sword."""
    it = sword_frame(world)
    return (mcrig.xform(it, SWORD_POMMEL), mcrig.xform(it, SWORD_GUARD), mcrig.xform(it, SWORD_TIP))


def shield_centre(world):
    it = display_matrix(item_base(world, "left"), SHIELD_DISPLAY, False) @ mcrig.mat4(s=(1, -1, -1))
    return mcrig.xform(it, (0.0, 0.0, -1.5)), it


def _item_empty(name, parent, rel):
    e = bpy.data.objects.new(name, None)
    bpy.context.scene.collection.objects.link(e)
    e.parent = parent
    e.matrix_basis = Matrix(rel.tolist())
    return e


def build(tex="settler_guard.png", right=None, left=None, ground=True):
    """New scene: settler rig + held items. right: 'sword'|'bow'|None, left: 'shield'|None."""
    hsrig.reset()
    objs = hsrig.build_scene(os.path.join(SETTLER_TEX, tex), None)
    if ground:
        hsrig.prop_box("ground", (-48, 24, -48), (96, 1, 96), (0.30, 0.45, 0.22, 1))
    # held items hang off the WRIST bones (engine 2026-09-26): pivot at the palm
    eye = mcrig.T(0, -6, 0)
    rpar = objs.get("right_item", objs["right_forearm"])
    lpar = objs.get("left_item", objs["left_forearm"])
    if "right_item" not in objs:
        eye = np.eye(4)
    if right == "sword":
        e = _item_empty("item:sword", rpar, display_matrix(eye, HANDHELD, True))
        hsrig.sprite_mesh("mesh:sword", SWORD_PNG, e)
    elif right == "bow":
        e = _item_empty("item:bow", rpar, display_matrix(eye, BOW_DISPLAY, True))
        hsrig.sprite_mesh("mesh:bow", BOW_PNG, e)
    if left == "shield":
        rel = display_matrix(eye, SHIELD_DISPLAY, False) @ mcrig.mat4(s=(1, -1, -1))
        e = _item_empty("item:shield", lpar, rel)
        me = hsrig.cuboid_mesh("mesh:shield", [((-6, -11, -2), (12, 22, 1), (0, 0), False, 0.0),
                                               ((-1, -3, -1), (2, 6, 6), (26, 0), False, 0.0)],
                               tex_w=64, tex_h=64)
        me.materials.append(hsrig._material("shield", SHIELD_PNG))
        mo = bpy.data.objects.new("mesh:shield", me)
        bpy.context.scene.collection.objects.link(mo)
        mo.parent = e
    return objs


# --------------------------------------------------------------------------- stance
# Guard ready stance v2 (owner: "heavy and professional"): a trained soldier --
# knees clearly bent (hips 1 px down), weight centred between a long lead-left
# stance, chest over the lead knee, chin down / eyes up, GUARD UP: sword hand at
# chest height with the point angled at the opponent's face, free fist up
# covering the chest.
FOOT_R = np.array([-3.3, 24.0, 2.0])
FOOT_L = np.array([3.1, 24.0, -2.4])
HIP_X = 2.6
STANCE_CTRL = {
    "hip_x": 0.0, "hip_y": -0.6, "hip_z": 0.3, "hip_p": 0.0, "hip_yaw": 9.0, "hip_r": 0.0,
    "sp_x": 8.0, "sp_y": 3.0, "sp_z": 0.0, "sp_lift": 0.0,
    "hd_x": -6.0, "hd_y": -10.0, "hd_z": 0.0,
    "tw_r": 0.0, "tw_l": 0.0, "gl_w": 0.0,
    "ck_x": 5.0, "ck_z": 0.0,
}
# Sword hand + blade direction (model space) at the stance.
STANCE_SWORD_HAND = np.array([-3.0, 7.4, -6.4])
STANCE_SWORD_DIR = np.array([0.20, -0.60, -0.77])
# Free (left) hand: fist up in front of the chest, elbow bent and slightly out.
STANCE_LEFT_HAND = np.array([2.2, 7.2, -5.6])
FEET_PROPS = ("fr_x", "fr_y", "fr_z", "fl_x", "fl_y", "fl_z")
ARM_PROPS = {"right": ("ar_x", "ar_y", "ar_z", "el_r", "tw_r"),
             "left": ("al_x", "al_y", "al_z", "el_l", "tw_l")}


def body_channels(g, t=None):
    """Root/torso/head channels from a getter g(prop)."""
    return {
        "root": {"rot": (g("hip_p"), g("hip_yaw"), g("hip_r")),
                 "pos": (g("hip_x"), g("hip_y"), g("hip_z"))},
        "torso": {"rot": (g("sp_x"), g("sp_y"), g("sp_z")), "pos": (0.0, g("sp_lift"), 0.0)},
        "head": {"rot": (g("hd_x"), g("hd_y"), g("hd_z"))},
        # authored wrist (held-item) rotation; the procedural edge roll is added in make_solve
        "right_item": {"rot": (g("wr_x"), g("wr_y"), g("wr_z"))},
        "left_item": {"rot": (g("wl_x"), g("wl_y"), g("wl_z"))},
    }


def static_getter(d):
    return lambda p: float(d.get(p, 0.0))


def arm_cost_factory(channels, side, hand_goal, dir_goal, tip_goal=None, w_dir=10.0, w_tip=1.0,
                     display=HANDHELD, pole_out=0.0, twist_w=0.02, elbow_pref=None, seed_ref=None,
                     w_seed=0.0):
    arm, fore = side + "_arm", side + "_forearm"
    right = side == "right"
    hd = None if dir_goal is None else np.asarray(dir_goal, float) / np.linalg.norm(dir_goal)

    def pose(p):
        ch = dict(channels)
        ch[arm] = {"rot": (p[0], p[1], p[2])}
        ch[fore] = {"rot": (p[3], p[4], 0.0)}
        return mcrig.pose_matrices(ch)

    def cost(p):
        w = pose(p)
        hand = mcrig.xform(w[fore], (0, 6, 0))
        cst = float(np.sum((hand - hand_goal) ** 2))
        if hd is not None:
            it = display_matrix(item_base(w, side), display, right)
            a = mcrig.xform(it, SWORD_TIP) - mcrig.xform(it, SWORD_POMMEL)
            a /= np.linalg.norm(a)
            cst += w_dir * float(np.sum((a - hd) ** 2)) * 25.0
            if tip_goal is not None:
                cst += w_tip * float(np.sum((mcrig.xform(it, SWORD_TIP) - tip_goal) ** 2))
        cst += twist_w * p[4] ** 2 * 0.01
        if elbow_pref is not None:
            cst += 0.02 * (p[3] - elbow_pref) ** 2
        if w_seed and seed_ref is not None:
            # continuity: stay on the previous key's shoulder branch (no Euler/branch switch)
            cst += w_seed * sum((a - b) ** 2 for a, b in zip(p, seed_ref))
        if p[3] > 0:
            cst += p[3] ** 2
        if p[3] < -140:
            cst += (p[3] + 140) ** 2
        if pole_out:
            # keep the elbow out to the arm's own side (no chicken-wing inward)
            sgn = -1 if right else 1
            elbow = mcrig.xform(w[arm], (0, 4, 0))
            sh = mcrig.xform(w[arm], (0, 0, 0))
            cst += pole_out * max(0.0, -(elbow[0] - sh[0]) * sgn) ** 2
        return cst
    return cost


def solve_arm(channels, side, hand_goal, dir_goal=None, seed=(-40, 0, 0, -40, 0), tip_goal=None,
              w_dir=10.0, pole_out=0.0, elbow_pref=None, w_seed=0.0):
    hand_goal = np.asarray(hand_goal, float)
    # reach clamp: the limb is 10 px (shoulder pivot -> hand); keep goals just inside it
    wt = mcrig.pose_matrices(channels)["torso"]
    sh = mcrig.xform(wt, (-6.0 if side == "right" else 6.0, -10.0, 0.0))
    v = hand_goal - sh
    dist = float(np.linalg.norm(v))
    if dist > 9.6:
        hand_goal = sh + v * (9.6 / dist)
    cost = arm_cost_factory(channels, side, hand_goal, dir_goal, tip_goal,
                            w_dir=w_dir, pole_out=pole_out, elbow_pref=elbow_pref,
                            seed_ref=[float(v) for v in seed], w_seed=w_seed)
    seed = [float(v) for v in seed]

    def run(x0):
        b, v = mcrig.nelder_mead(cost, list(x0), [15, 15, 15, 15, 10], iters=900)
        b, v = mcrig.nelder_mead(cost, b, [4, 4, 4, 4, 3], iters=900)
        return b, v

    best, val = run(seed)
    if val > 30.0:
        # multi-start: the blade direction constraint has several local minima
        # (twist / shoulder-roll branches). Prefer the solution nearest the seed
        # among the near-equal best ones so the key-to-key path stays continuous.
        rng = np.random.default_rng(1234)
        cands = [(val, best)]
        for k in range(8):
            x0 = np.array(seed) + rng.normal(0, [40, 40, 30, 35, 60])
            x0[3] = min(-5.0, x0[3])
            b, v = run(x0)
            cands.append((v, b))
        vmin = min(v for v, _ in cands)
        ok = [(v, b) for v, b in cands if v <= vmin + max(1.0, 0.15 * vmin)]
        val, best = min(ok, key=lambda vb: float(np.sum((np.array(vb[1]) - np.array(seed)) ** 2)))
    best, val = mcrig.nelder_mead(cost, best, [1, 1, 1, 1, 1], iters=600)
    return [float(v) for v in best], float(val)


def stance_base():
    """Absolute channel dict of the guard's rest stance (legs by IK)."""
    g = static_getter(STANCE_CTRL)
    ch = body_channels(g)
    sol_r, _ = solve_arm(ch, "right", STANCE_SWORD_HAND, STANCE_SWORD_DIR, seed=(-35, -10, 5, -40, 0),
                         elbow_pref=-55.0)
    ch["right_arm"] = {"rot": tuple(sol_r[:3])}
    ch["right_forearm"] = {"rot": (sol_r[3], sol_r[4], 0.0)}
    sol_l, _ = solve_arm(ch, "left", STANCE_LEFT_HAND, None, seed=(-30, 10, -5, -50, 0), pole_out=4.0)
    ch["left_arm"] = {"rot": tuple(sol_l[:3])}
    ch["left_forearm"] = {"rot": (sol_l[3], 0.0, 0.0)}
    legs_ik(ch, FOOT_R, FOOT_L)
    ch["cloak"] = {"rot": (STANCE_CTRL["ck_x"], 0.0, 0.0)}
    ctrl = dict(STANCE_CTRL)
    for side, sol in (("right", sol_r), ("left", sol_l)):
        for name, v in zip(ARM_PROPS[side], sol):
            ctrl[name] = v
    ctrl["tw_l"] = 0.0
    return ch, ctrl


_leg_prev = {}


# sole corners of the rendered shin (girth-scaled like SettlerModel.applyLegProportions)
SOLE_CORNERS = [(sx * 2.0 * mcrig.LEG_GIRTH[0], 6.0, sz * 2.0 * mcrig.LEG_GIRTH[2])
                for sx in (-1, 1) for sz in (-1, 1)]


def lowest_sole(world, side):
    """Largest model y (= lowest point, y is down) of the shin's sole corners."""
    return max(mcrig.xform(world[side + "_shin"], p)[1] for p in SOLE_CORNERS)


def legs_ik(ch, foot_r, foot_l, knee_out=0.14, key="default", contact=True):
    """Two-bone leg IK. foot_* = sole-centre targets; with contact=True the target
    is lifted by half of the tilted sole's drop: the lowest corner is always at or
    just below the ground (never floating) and the sink is halved."""
    world = mcrig.pose_matrices(ch)
    inv_root = np.linalg.inv(world["root"])
    for side, foot, hip_x in (("right", foot_r, -HIP_X), ("left", foot_l, HIP_X)):
        target = np.array(foot, float)
        rot = None
        for it in range(2 if contact else 1):
            fl = mcrig.xform(inv_root, target)
            # knees point along the foot's forward, a touch outward
            pole = inv_root[:3, :3] @ np.array([knee_out * np.sign(hip_x), 0.0, -1.0])
            r, flex, _ = mcrig.two_bone(np.array([hip_x, -12.0, 0.0]), fl, mcrig.THIGH,
                                        mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
            rot = tuple(hsrig.euler_deg_continuous(r, _leg_prev.get((key, side))))
            ch[side + "_leg"] = {"rot": rot}
            ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
            if not contact:
                break
            w2 = mcrig.pose_matrices({k: v for k, v in ch.items()
                                      if k in ("root", side + "_leg", side + "_shin")})
            # one damped pass: lift the sole centre by HALF the tilt drop, so the tilted
            # block foot never floats and sinks at most half as far (a full correction
            # bends the knee further, tilts the shin more and runs away)
            target[1] += 0.5 * (float(foot[1]) - lowest_sole(w2, side))
        _leg_prev[(key, side)] = rot
    return ch


def ground_report(samples):
    """Max float / sink of the lowest sole corner over a clip (planted feet)."""
    fl, sk = 0.0, 0.0
    for s in samples:
        w = mcrig.pose_matrices(s)
        for side in ("right", "left"):
            low = lowest_sole(w, side)
            fl = max(fl, 24.0 - low)
            sk = max(sk, low - 24.0)
    return {"max_float_px": round(fl, 3), "max_sink_px": round(sk, 3)}


def sole_error(ch, foot_r, foot_l):
    """Lowest-sole-corner height error vs the target (px)."""
    w = mcrig.pose_matrices(ch)
    e = 0.0
    for side, foot in (("right", foot_r), ("left", foot_l)):
        e = max(e, abs(lowest_sole(w, side) - float(foot[1])))
    return e


# --------------------------------------------------------------------------- curves
def key_all(K, length, loop):
    for prop, keys in K.items():
        hsrig.key_curve(prop, keys, cyclic=loop, length=length)


def goal_keys(goals, side, body_at, seed, length, loop, extra_close=True, w_dir=4.0):
    """goals: [(t, hand, dir|None, tip|None, ease|None)] -> keyed arm FK curves.

    ease is the (interp, easing) of the segment LEAVING the key."""
    props = ARM_PROPS[side]
    keys = {p: [] for p in props}
    log = []
    rest = list(seed)
    for t, hand, d, tip, e in goals:
        if hand is None:          # REST: exactly the stance solution (no re-solve drift)
            sol, err = list(rest), 0.0
        else:
            sol, err = solve_arm(body_at(t), side, hand, d, seed=seed, tip_goal=tip, w_dir=w_dir,
                                 pole_out=2.0)
        seed = sol
        log.append({"t": t, "sol": [round(v, 2) for v in sol], "cost": round(err, 3)})
        for name, v in zip(props, sol):
            keys[name].append((t, v, *e) if e else (t, v))
    for name, ks in keys.items():
        if loop and extra_close and abs(ks[-1][0] - length) > 1e-6:
            ks.append((length, ks[0][1]))
        hsrig.key_curve(name, ks, cyclic=loop, length=length)
    return log


# easing presets (segment LEAVING a key)
ACC = ("CUBIC", "EASE_IN")      # accelerate into the next key (strike)
ACC4 = ("QUART", "EASE_IN")
DEC = ("SINE", "EASE_OUT")      # decelerate (settle)
DEC3 = ("CUBIC", "EASE_OUT")
LIN = ("LINEAR", "AUTO")
SMO = ("SINE", "EASE_IN_OUT")
BEZ = None                      # auto-clamped Bezier


# --------------------------------------------------------------------------- solve
def make_solve(length, loop, *, feet=(FOOT_R, FOOT_L), grip_s=-2.2, cloak_drag=True,
               head_aim=None, left_ik=None, extra=None):
    """Generic solve(t) from the CTRL curves.

    left_ik(t, world) -> (target_point, weight) optional extra left-hand IK target;
    extra(t, ch) optional in-place post edit (before legs)."""
    prev = {}

    def w(t):
        return t % length if loop else min(max(t, 0.0), length)

    def solve(t):
        t = w(t)
        g = lambda p: c(p, t)  # noqa: E731
        ch = body_channels(g)
        for side, (ax, ay, az, el, tw) in ARM_PROPS.items():
            ch[side + "_arm"] = {"rot": (g(ax), g(ay), g(az))}
            ch[side + "_forearm"] = {"rot": (g(el), g(tw), 0.0)}
        if cloak_drag:
            dt = 1.0 / 60.0

            def twist(u):
                u = w(u)
                return c("sp_y", u) + c("hip_yaw", u)

            def pitch(u):
                u = w(u)
                return c("sp_x", u) + c("hip_p", u)
            vyaw = (twist(t - 0.04) - twist(t - 0.04 - dt)) / dt
            vp = (pitch(t - 0.05) - pitch(t - 0.05 - dt)) / dt
            vy = (c("hip_y", w(t - 0.05)) - c("hip_y", w(t - 0.05 - dt))) / dt
            k = 1.0
            if not loop:   # one-shots hand back to the stance: the drag settles to zero at the end
                u = min(1.0, max(0.0, (length - t) / 0.14))
                k = u * u * (3 - 2 * u)
            ch["cloak"] = {"rot": (g("ck_x") + k * max(-16.0, min(16.0, -0.07 * vp + 1.2 * vy)), 0.0,
                                   g("ck_z") + k * max(-12.0, min(12.0, -0.02 * vyaw)))}
        else:
            ch["cloak"] = {"rot": (g("ck_x"), 0.0, g("ck_z"))}
        # two-handed grip: left hand IK onto the sword grip below the right hand
        wg = max(0.0, min(1.0, g("gl_w")))
        target = None
        if wg > 1e-3 or left_ik is not None:
            world = mcrig.pose_matrices(ch)
            if left_ik is not None:
                target, wg = left_ik(t, world)
            else:
                pom, grd, _ = sword_points(world)
                axis = (grd - pom) / np.linalg.norm(grd - pom)
                hand = mcrig.xform(world["right_forearm"], (0, 6, 0))
                base = pom + axis * float((hand - pom) @ axis)
                target = base + axis * grip_s
            if wg > 1e-3:
                tl = mcrig.xform(np.linalg.inv(world["torso"]), target)
                r, flex, _ = mcrig.two_bone(np.array([6.0, -10.0, 0.0]), tl, mcrig.UPPER_ARM, 6.0,
                                            np.array([0.8, 0.5, 0.4]), -1)
                ik = hsrig.euler_deg_continuous(r, prev.get("l_ik"))
                prev["l_ik"] = ik
                fk = list(ch["left_arm"]["rot"])
                # wrap the IK solution next to the FK angles (not the other way round): the
                # blend then ends EXACTLY on the FK keys when the grip releases (the old
                # direction left the fk curve 360 deg off -> an euler spin on release)
                ik = [i + 360.0 * round((f - i) / 360.0) for f, i in zip(fk, ik)]
                ch["left_arm"] = {"rot": tuple(f + (i - f) * wg for f, i in zip(fk, ik))}
                fe = ch["left_forearm"]["rot"]
                ch["left_forearm"] = {"rot": (fe[0] + (math.degrees(flex) - fe[0]) * wg,
                                              fe[1] * (1 - wg), 0.0)}
        roll = g("wr_roll")
        if abs(roll) > 1e-4:
            ch["right_item"] = {"rot": tuple(apply_roll(ch, roll, prev))}
        if head_aim is not None:
            head_aim(t, ch)
        if extra is not None:
            extra(t, ch)
        fr = feet[0] + np.array([g("fr_x"), -g("fr_y"), g("fr_z")])
        fl = feet[1] + np.array([g("fl_x"), -g("fl_y"), g("fl_z")])
        legs_ik(ch, fr, fl, key=id(solve))
        solve.last_feet = (fr, fl)
        solve.last_grip = target
        return ch
    return solve


# --------------------------------------------------------------------------- wrist / edge roll
def _axis_angle(a, th):
    a = a / np.linalg.norm(a)
    k = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) + math.sin(th) * k + (1 - math.cos(th)) * (k @ k)


def apply_roll(ch, roll_deg, prev):
    """right_item rotation = authored wrist @ roll about the blade axis (pivot: palm)."""
    world = mcrig.pose_matrices(ch)
    pom, _, tip = sword_points(world)
    b = (tip - pom) / np.linalg.norm(tip - pom)
    f = world["right_item"][:3, :3]
    a_local = f.T @ b
    ra = mcrig.rot_zyx(*[math.radians(v) for v in ch["right_item"]["rot"]])
    m = ra @ _axis_angle(a_local, math.radians(roll_deg))
    out = hsrig.euler_deg_continuous(m, prev.get("wrist"))
    prev["wrist"] = out
    return out


def arm_world(t):
    """Body + arms + authored wrist (no roll, no legs) at time t, from CTRL."""
    g = lambda p: c(p, t)  # noqa: E731
    ch = body_channels(g)
    for side, (ax, ay, az, el, tw) in ARM_PROPS.items():
        ch[side + "_arm"] = {"rot": (g(ax), g(ay), g(az))}
        ch[side + "_forearm"] = {"rot": (g(el), g(tw), 0.0)}
    return mcrig.pose_matrices(ch)


def key_edge_roll(length, start=0.0, end=0.0, v_lo=140.0, v_hi=420.0, tail=0.16, sigma=2.0,
                  limit=80.0):
    """Edge alignment: roll the sword about its own axis so the cutting edge leads
    the swing (edge parallel to the tip velocity across the blade). The roll is
    held through slow phases (the hit-stop keeps the contact roll) and eased back
    to `end` over the last `tail` seconds. Keys CTRL "wr_roll" (degrees)."""
    n = int(round(length * FPS))
    ts = [i / FPS for i in range(n + 1)]
    data = []
    for t in ts:
        w = arm_world(t)
        pom, _, tip = sword_points(w)
        b = (tip - pom) / np.linalg.norm(tip - pom)
        e0 = sword_frame(w)[:3, :3] @ SWORD_EDGE_ITEM
        e0 = e0 - b * float(e0 @ b)
        e0 /= np.linalg.norm(e0)
        data.append((tip, b, e0))
    r = [start]
    for i in range(1, n + 1):
        tip, b, e0 = data[i]
        j = min(n, i + 1)
        v = (data[j][0] - data[i - 1][0]) * FPS / (j - (i - 1))
        vp = v - b * float(v @ b)
        sp = float(np.linalg.norm(vp))
        prevr = r[-1]
        if sp < 1e-6:
            r.append(prevr)
            continue
        d = vp / sp
        th = math.degrees(math.atan2(float(np.cross(b, e0) @ d), float(e0 @ d)))
        th += 180.0 * round((prevr - th) / 180.0)     # either edge may lead: nearest
        th = max(-limit, min(limit, th))
        wgt = min(1.0, max(0.0, (sp - v_lo) / (v_hi - v_lo)))
        wgt = wgt * wgt * (3 - 2 * wgt)
        r.append(prevr + (th - prevr) * wgt)
    r = np.array(r)
    for i, t in enumerate(ts):
        if t > length - tail:
            u = (length - t) / tail
            u = u * u * (3 - 2 * u)
            r[i] = end + (r[i] - end) * u
    k = np.arange(-6, 7)
    ker = np.exp(-0.5 * (k / sigma) ** 2)
    ker /= ker.sum()
    rp = np.concatenate([np.full(6, r[0]), r, np.full(6, r[-1])])
    rs = np.convolve(rp, ker, mode="valid")
    rs[0], rs[-1] = start, end
    keys = [(t, float(v)) for t, v in zip(ts[::2], rs[::2])]
    if abs(keys[-1][0] - length) > 1e-9:
        keys.append((length, end))
    hsrig.key_curve("wr_roll", keys, cyclic=False, length=length)
    return [round(float(v), 1) for v in rs[::6]]


# --------------------------------------------------------------------------- export
def subtract(samples, base):
    out = []
    for s in samples:
        d = {}
        for bone in set(s) | set(base):
            sb, bb = s.get(bone, {}), base.get(bone, {})
            d[bone] = {}
            for kind in ("rot", "pos"):
                a = sb.get(kind, (0, 0, 0))
                b = bb.get(kind, (0, 0, 0))
                d[bone][kind] = tuple(x - y for x, y in zip(a, b))
        out.append(d)
    return out


def export(name_const, length, loop, times, samples, *, bones=None, base=None, keep_times=(),
           meta=None, write=True, out_dir=None, rot_tol=0.25, pos_tol=0.02):
    """Write animations/settler/<const>.animation.json (absolute, or additive vs `base`)."""
    key = name_const.lower()
    anim = "animation.settler." + key
    use = subtract(samples, base) if base is not None else samples
    chan = hsrig.to_export_channels(times, use, bones=bones)
    kt = tuple(sorted(set([0.0, length] + [k for k in keep_times if 0 <= k <= length])))
    doc, report = ex.build_bedrock(anim, length, loop, times, chan, rot_tol=rot_tol, pos_tol=pos_tol,
                                   keep_times=kt, meta=meta)
    # verify: runtime interpolation of the written keys matches the bake
    worst = 0.0
    bones_doc = doc["animations"][anim]["bones"]
    for b, kinds in chan.items():
        for kind, vecs in kinds.items():
            if b not in bones_doc or kind not in bones_doc[b]:
                worst = max(worst, max(abs(v) for vec in vecs for v in vec))
                continue
            for t, v in zip(times, vecs):
                got = ex.sample(doc, anim, b, kind, t)
                worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
    doc.setdefault("hearthstead_meta", {})["roundtrip_max_err"] = round(worst, 4)
    path = os.path.join(ANIM_DIR, key + ".animation.json")
    if write:
        ex.write(doc, path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        ex.write(doc, os.path.join(out_dir, key + ".animation.json"))
    nkeys = sum(v["keys"] for v in report.values())
    print("EXPORTED", key, nkeys, "keys, roundtrip", round(worst, 4), "->", path if write else "(no write)")
    return doc, worst


def out_dir(clip):
    d = os.path.join(VIDEO_ROOT, clip)
    os.makedirs(d, exist_ok=True)
    return d


def cameras(kind="guard"):
    """Side (settler's right) and front three-quarter cameras (blocks)."""
    return {
        "side": hsrig.camera("cam_side", (-3.9, -0.35, 1.05), (0.0, -0.35, 0.95), lens=38),
        "front34": hsrig.camera("cam_front34", (-2.2, -2.9, 1.5), (0.0, -0.3, 0.95), lens=38),
    }


FFMPEG = os.environ.get("HS_FFMPEG", os.path.join(
    os.path.expanduser("~"), "AppData", "Local", "Microsoft", "WinGet", "Packages",
    "Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe", "ffmpeg-8.1-full_build", "bin", "ffmpeg.exe"))


def _setup_workbench(res):
    sc = bpy.context.scene
    hsrig.setup_render(res=res)
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light = "STUDIO"
    sh.color_type = "TEXTURE"
    sh.show_shadows = True
    sh.shadow_intensity = 0.35
    sc.display.render_aa = "8"
    sc.view_settings.exposure = 0.9
    sh.background_type = "VIEWPORT"
    sh.background_color = (0.62, 0.72, 0.84)


def _encode(frames_dir, n, fps, path, pad=0.0):
    import subprocess
    vf = "format=yuv420p"
    if pad > 0:
        vf = f"tpad=start_mode=clone:start_duration={pad}:stop_mode=clone:stop_duration={pad}," + vf
    cmd = [FFMPEG, "-y", "-loglevel", "error", "-framerate", str(fps), "-start_number", "0",
           "-i", os.path.join(frames_dir, "f%04d.png"), "-frames:v", str(n), "-vf", vf,
           "-c:v", "libx264", "-crf", "20", path]
    subprocess.run(cmd, check=False)


def preview(clip, length, args, loop=True, step=None):
    """Render PNG frames once per camera, then ffmpeg: normal + half-speed MP4s + sheets.

    --fast: side camera only, 480x360.  --full: side + front34, 720x540.
    One-shots are padded 0.3 s with their first/last frame (the stance hold)."""
    if not (args["fast"] or args["full"]):
        return
    d = out_dir(clip)
    _setup_workbench((720, 540) if args["full"] else (480, 360))
    cams = cameras()
    n = int(round(length * FPS))
    step = step or (2 if (loop and length >= 2.0) else 1)
    frames = list(range(0, n + (0 if loop else 1), step))
    fps = FPS // step
    use = ["side", "front34"] if args["full"] else ["side"]
    sc = bpy.context.scene
    for name in use:
        fd = os.path.join(d, "_frames_" + name)
        os.makedirs(fd, exist_ok=True)
        for f in os.listdir(fd):
            os.remove(os.path.join(fd, f))
        sc.camera = cams[name]
        for i, f in enumerate(frames):
            sc.frame_set(f)
            sc.render.filepath = os.path.join(fd, f"f{i:04d}.png")
            bpy.ops.render.render(write_still=True)
        pad = 0.0 if loop else 0.3
        _encode(fd, len(frames), fps, os.path.join(d, name + ".mp4"), pad)
        _encode(fd, len(frames), max(1, fps // 2), os.path.join(d, name + "_half.mp4"), pad)
        k = min(12, len(frames))
        idx = [int(round(i * (len(frames) - 1) / (k - 1))) for i in range(k)]
        hsrig._sheet([os.path.join(fd, f"f{i:04d}.png") for i in idx], os.path.join(d, f"sheet_{name}.png"))
    print("PREVIEW", d)


def world_speed(points, fps=FPS):
    p = np.asarray(points)
    return np.linalg.norm(np.diff(p, axis=0), axis=1) * fps


def write_report(clip, data):
    d = out_dir(clip)
    with open(os.path.join(d, "checks.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=1)
