"""Minecraft-exact settler rig maths (numpy only; runs in Blender and plain Python).

Everything here is in Minecraft *model space*: units are pixels, +Y is DOWN,
the settler faces -Z, +X is the settler's LEFT (left_arm pivot x=+6).
A part's frame is  parent @ T(pivot + offset) @ Rz(z) @ Ry(y) @ Rx(x),
exactly ModelPart.translateAndRotate (Quaternionf.rotationZYX(z, y, x)).

Channel values use the SettlerAnimations / bedrock-JSON convention:
rotation = degrees added to the part's rest rotation (all rests are zero for
the parts this rig drives); position = posVec pixels with Y UP (negated here).

Bend bones (motion engine, agreed with the engine owner 2026-09-25):
  right_forearm / left_forearm : child of *_arm, pivot arm-local (0,4,0),
                                 hand at arm-local y=10; flex = NEGATIVE x.
  right_shin / left_shin       : child of *_leg, pivot leg-local (0,6,0),
                                 sole at leg-local y=12; flex = POSITIVE x.
Held-item wrist bones (2026-09-26):
  right_item / left_item       : child of *_forearm at forearm-local (0,6,0) = the
                                 palm (arm-local y=10). Rotation turns the tool in
                                 the fist about the palm; position slides the grip.
                                 Runtime: arm_bent @ T(palm + pos) R(item) T(-palm),
                                 then vanilla ItemInHandLayer. Never keyed = identity.
"""

from __future__ import annotations

import math

import numpy as np

DEG = math.pi / 180.0


# --------------------------------------------------------------------------- matrices
def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], float)


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], float)


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], float)


def rot_zyx(x, y, z):
    """ModelPart rotation for channel angles in radians."""
    return rz(z) @ ry(y) @ rx(x)


def euler_from_matrix(m):
    """Inverse of rot_zyx: returns (x, y, z) radians with y in [-90, 90]."""
    sy = -m[2, 0]
    sy = max(-1.0, min(1.0, sy))
    y = math.asin(sy)
    if abs(sy) < 0.999999:
        x = math.atan2(m[2, 1], m[2, 2])
        z = math.atan2(m[1, 0], m[0, 0])
    else:  # gimbal: put everything in x
        z = 0.0
        x = math.atan2(-m[1, 2], m[1, 1])
    return x, y, z


def mat4(r=None, t=(0, 0, 0), s=1.0):
    m = np.eye(4)
    if r is not None:
        m[:3, :3] = r
    if not np.isscalar(s):
        m[:3, :3] = m[:3, :3] @ np.diag(s)
    elif s != 1.0:
        m[:3, :3] *= s
    m[:3, 3] = t
    return m


def T(x, y, z):
    return mat4(t=(x, y, z))


def xform(m, p):
    return (m @ np.array([p[0], p[1], p[2], 1.0]))[:3]


# --------------------------------------------------------------------------- rig spec
# name: (parent, pivot px, [cubes: (from xyz, size xyz, uv (u, v), mirror, inflate)])
# Transcribed from SettlerModel.createBodyLayer (the parts a Lumberer shows while
# chopping). Hood/brims/fisher/courier props are hidden for LUMBERER.
PARTS = {
    "root": (None, (0, 24, 0), []),
    "torso": ("root", (0, -12, 0), [((-5, -12, -2.5), (10, 12, 5), (64, 0), False, 0.0)]),
    "head": ("torso", (0, -12, 0), [((-4, -8, -4), (8, 8, 8), (0, 0), False, 0.0)]),
    "nose": ("head", (0, 0, 0), [((-0.75, -2.5, -5.5), (1.5, 3, 1.5), (120, 32), False, 0.0)]),
    "cloak": ("torso", (0, -12, 0), [((-5.5, 0, -3), (11, 4, 6), (64, 32), False, 0.2)]),
    "belt": ("torso", (0, 0, 0), [((-5, -5, -2.5), (10, 2, 5), (96, 20), False, 0.3)]),
    # Arms: the runtime draws ONE continuous bent limb (arm cube -2..10); the
    # preview splits it at the elbow pivot so the bend is visible.
    "right_arm": ("torso", (-6, -10, 0), [((-2, -2, -2), (4, 6, 4), (0, 32), False, 0.0)]),
    "right_forearm": ("right_arm", (0, 4, 0), [((-2, 0, -2), (4, 6, 4), (0, 38), False, 0.0)]),
    "left_arm": ("torso", (6, -10, 0), [((-2, -2, -2), (4, 6, 4), (16, 32), True, 0.0)]),
    "left_forearm": ("left_arm", (0, 4, 0), [((-2, 0, -2), (4, 6, 4), (16, 38), True, 0.0)]),
    "right_leg": ("root", (-2.6, -12, 0), [((-2, 0, -2), (4, 6, 4), (32, 32), False, 0.0)]),
    "right_shin": ("right_leg", (0, 6, 0), [((-2, 0, -2), (4, 6, 4), (32, 38), False, 0.0)]),
    "left_leg": ("root", (2.6, -12, 0), [((-2, 0, -2), (4, 6, 4), (48, 32), True, 0.0)]),
    "left_shin": ("left_leg", (0, 6, 0), [((-2, 0, -2), (4, 6, 4), (48, 38), True, 0.0)]),
    # Held-item wrist bones (no geometry): pivot at the palm.
    "right_item": ("right_forearm", (0, 6, 0), []),
    "left_item": ("left_forearm", (0, 6, 0), []),
}
# Channels the exporter writes (bend bones are rotation-only).
EXPORT_BONES = ["root", "torso", "head", "right_arm", "left_arm", "right_forearm",
                "left_forearm", "right_leg", "left_leg", "right_shin", "left_shin", "cloak",
                "right_item", "left_item"]
ROTATION_ONLY = {"right_forearm", "left_forearm", "right_shin", "left_shin"}
LEG_GIRTH = (1.2, 1.0, 1.15)   # SettlerModel.applyLegProportions, render-time only

UPPER_ARM = 4.0     # shoulder pivot -> elbow pivot
HAND_Y = 10.0       # arm-local y of the hand (end of the limb)
THIGH = 6.0         # hip pivot -> knee pivot
SOLE_Y = 12.0       # leg-local y of the sole


def order():
    out = []
    def visit(n):
        if n in out:
            return
        p = PARTS[n][0]
        if p:
            visit(p)
        out.append(n)
    for n in PARTS:
        visit(n)
    return out


def pose_matrices(channels):
    """channels: {part: {'rot': (x,y,z) deg, 'pos': (x,y,z) posVec px}} -> {part: 4x4 world}"""
    world = {}
    for name in order():
        parent, pivot, _ = PARTS[name]
        ch = channels.get(name, {})
        rot = ch.get("rot", (0, 0, 0))
        pos = ch.get("pos", (0, 0, 0))
        t = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
        local = mat4(rot_zyx(rot[0] * DEG, rot[1] * DEG, rot[2] * DEG), t)
        world[name] = (world[parent] if parent else np.eye(4)) @ local
    return world


# --------------------------------------------------------------------------- held item
def item_in_hand_matrix(forearm_world, right=True, item_world=None):
    """Frame of the vanilla generated-item model (0..16 px cube) held in the hand.

    arm * T(0,4,0) R(fore) T(0,-4,0)  [== forearm_world @ T(0,-4,0)]
      * Rx(-90) Ry(180) T(+-1, 2, -10)                 (ItemInHandLayer)
      * T(0, 4, 0.5) Rxyz(0, -+90, +-55) S(0.85)       (item/handheld thirdperson)
      * T(-8, -8, -8)                                  (ItemRenderer centring)
    """
    side = 1 if right else -1
    # With a wrist bone (right_item/left_item, pivot at the palm = forearm-local
    # (0,6,0)) the held-item frame is item_world @ T(0,-10,0); an unkeyed item
    # bone equals forearm_world @ T(0,-4,0), so both paths agree at identity.
    m = item_world @ T(0, -10, 0) if item_world is not None else forearm_world @ T(0, -4, 0)
    m = m @ mat4(rx(-90 * DEG)) @ mat4(ry(180 * DEG)) @ T(side * 1, 2, -10)
    r = rx(0) @ ry(-90 * side * DEG) @ rz(55 * side * DEG)   # JOML rotationXYZ = Rx Ry Rz
    m = m @ T(0, 4, 0.5) @ mat4(r, s=0.85) @ T(-8, -8, -8)
    return m


def sprite_voxels(png_rgba):
    """Opaque pixels of a 16x16 item sprite -> list of (centre xyz in item px, rgba)."""
    out = []
    h, w = png_rgba.shape[:2]
    for v in range(h):
        for u in range(w):
            a = png_rgba[v, u, 3]
            if a > 0:
                out.append(((u + 0.5, 16 - (v + 0.5), 8.0), tuple(png_rgba[v, u])))
    return out


# --------------------------------------------------------------------------- IK
def two_bone(root_pt, target, l1, l2, pole, bend_sign):
    """Planar two-bone IK in a parent frame.

    Returns (upper_rot_matrix, flex_radians, reached_point).  The upper bone's
    local +Y points root->joint; the lower bone bends about local X by
    flex = bend_sign * interior angle, toward local -Z when bend_sign < 0
    (elbow) and toward +Z when bend_sign > 0 (knee). `pole` is the direction
    the JOINT should push out to (elbow back, knee forward).
    """
    d = np.asarray(target, float) - np.asarray(root_pt, float)
    dist = float(np.linalg.norm(d))
    dist = min(max(dist, abs(l1 - l2) + 1e-4), l1 + l2 - 1e-4)
    dn = d / max(np.linalg.norm(d), 1e-9)
    # law of cosines: angle at root between d and upper bone
    cos_a = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)
    a = math.acos(max(-1.0, min(1.0, cos_a)))
    cos_k = (l1 * l1 + l2 * l2 - dist * dist) / (2 * l1 * l2)
    interior = math.pi - math.acos(max(-1.0, min(1.0, cos_k)))   # 0 = straight
    p = np.asarray(pole, float)
    p = p - dn * float(p @ dn)
    if np.linalg.norm(p) < 1e-6:          # pole parallel to the limb: any stable perpendicular
        for alt in ((0.0, 0.0, -1.0), (1.0, 0.0, 0.0), (0.0, 1.0, 0.0)):
            p = np.array(alt) - dn * float(np.array(alt) @ dn)
            if np.linalg.norm(p) > 1e-3:
                break
    p /= np.linalg.norm(p)
    upper_dir = dn * math.cos(a) + p * math.sin(a)          # toward the joint
    y_axis = upper_dir
    # Bend direction, built from the pole (NOT from dn - y, which collapses to zero
    # when the limb is straight and flips the frame: fixed 2026-09-26 after the bag
    # animator's report). In the (dn, pole) plane the target lies on the side away
    # from the pole: this is the exact normalised form of dn - y(dn.y) whenever a > 0.
    toward_target = dn * math.sin(a) - p * math.cos(a)
    toward_target /= max(np.linalg.norm(toward_target), 1e-9)
    z_axis = -toward_target if bend_sign < 0 else toward_target
    x_axis = np.cross(y_axis, z_axis)
    r = np.column_stack([x_axis, y_axis, z_axis])
    reached = np.asarray(root_pt) + dn * dist
    return r, bend_sign * interior, reached


# --------------------------------------------------------------------------- goal solve
def nelder_mead(f, x0, step, iters=600, tol=1e-7):
    """Small dependency-free Nelder-Mead minimiser."""
    n = len(x0)
    pts = [np.array(x0, float)]
    for i in range(n):
        p = np.array(x0, float)
        p[i] += step[i] if hasattr(step, "__len__") else step
        pts.append(p)
    vals = [f(p) for p in pts]
    for _ in range(iters):
        order_ = np.argsort(vals)
        pts = [pts[i] for i in order_]
        vals = [vals[i] for i in order_]
        if abs(vals[-1] - vals[0]) < tol:
            break
        c = sum(pts[:-1]) / n
        xr = c + (c - pts[-1]); fr = f(xr)
        if fr < vals[0]:
            xe = c + 2 * (c - pts[-1]); fe = f(xe)
            pts[-1], vals[-1] = (xe, fe) if fe < fr else (xr, fr)
        elif fr < vals[-2]:
            pts[-1], vals[-1] = xr, fr
        else:
            xc = c + 0.5 * (pts[-1] - c); fc = f(xc)
            if fc < vals[-1]:
                pts[-1], vals[-1] = xc, fc
            else:
                pts = [pts[0] + 0.5 * (p - pts[0]) for p in pts]
                vals = [f(p) for p in pts]
    i = int(np.argmin(vals))
    return pts[i], vals[i]


# Held-axe landmarks in item-sprite pixel centres (vanilla iron_axe.png).
AXE_KNOB = (2.5, 16 - 14.5, 8.0)
AXE_NECK = (9.5, 16 - 7.5, 8.0)
AXE_TIP = (13.5, 16 - 6.5, 8.0)       # NOTE: the poll (back of the head), kept for old scripts
AXE_EDGE = (7.5, 16 - 3.5, 8.0)       # middle of the cutting edge (upper-left of the head)
AXE_HAFT_ITEM = np.array([1.0, 1.0, 0.0]) / math.sqrt(2.0)    # knob -> head, item space
AXE_EDGE_ITEM = np.array([-1.0, 1.0, 0.0]) / math.sqrt(2.0)   # haft -> cutting edge, item space


def axe_frame(item):
    """(haft_dir, edge_dir, blade_normal) unit vectors in model space for an item matrix."""
    r = item[:3, :3]
    h = r @ AXE_HAFT_ITEM
    e = r @ AXE_EDGE_ITEM
    h /= np.linalg.norm(h)
    e -= h * float(e @ h)
    e /= np.linalg.norm(e)
    return h, e, np.cross(h, e)


def solve_arm_goal(channels, side, hand_goal, handle_dir_goal, x0, w_dir=10.0,
                   w_twist=0.02, tip_goal=None, w_tip=1.0, edge_dir_goal=None, w_edge=10.0,
                   w_reg=0.0):
    """FK arm params (ax, ay, az, elbow, twist) in degrees meeting world goals.

    hand_goal: model-space point for the hand (arm-local y=10).
    handle_dir_goal: model-space direction knob -> head of the held item (or None).
    tip_goal: optional model-space point for the blade tip.
    """
    arm, fore = side + "_arm", side + "_forearm"

    def pose(p):
        ch = dict(channels)
        ch[arm] = {"rot": (p[0], p[1], p[2])}
        ch[fore] = {"rot": (p[3], p[4], 0.0)}
        return pose_matrices(ch)

    hd = None if handle_dir_goal is None else np.asarray(handle_dir_goal, float) / np.linalg.norm(handle_dir_goal)
    ed = None if edge_dir_goal is None else np.asarray(edge_dir_goal, float) / np.linalg.norm(edge_dir_goal)
    x0a = np.asarray(x0, float)

    def cost(p):
        w = pose(p)
        hand = xform(w[fore], (0, 6, 0))
        c = float(np.sum((hand - hand_goal) ** 2))
        item = item_in_hand_matrix(w[fore], side == "right")
        if hd is not None:
            a = xform(item, AXE_NECK) - xform(item, AXE_KNOB)
            a /= np.linalg.norm(a)
            c += w_dir * float(np.sum((a - hd) ** 2)) * 25.0
        if tip_goal is not None:
            c += w_tip * float(np.sum((xform(item, AXE_TIP) - tip_goal) ** 2))
        if ed is not None:
            _, e, _ = axe_frame(item)
            c += w_edge * float(np.sum((e - ed) ** 2)) * 25.0
        if w_reg:
            c += w_reg * float(np.sum((np.asarray(p) - x0a) ** 2)) * 0.001
        c += w_twist * p[4] ** 2 * 0.01
        # keep the elbow inside its range (0 .. -140)
        if p[3] > 0:
            c += p[3] ** 2
        if p[3] < -140:
            c += (p[3] + 140) ** 2
        return c

    best, val = nelder_mead(cost, x0, [15, 15, 15, 15, 10])
    best, val = nelder_mead(cost, best, [4, 4, 4, 4, 3])
    return [float(v) for v in best], float(val)
