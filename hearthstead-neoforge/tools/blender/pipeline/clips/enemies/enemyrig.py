"""Raider + goblin rigs for the Blender clip pipeline (headless-safe).

Transcribed exactly from RaiderModel.createBodyLayer() / GoblinThiefModel.createBodyLayer():
same part names, pivots, cubes, texOffs and atlases. Minecraft model space: pixels,
+Y DOWN, the body faces -Z, +X is the creature's LEFT. A part's frame is
    parent @ T(pivot + offset) @ Rz Ry Rx @ S(scale)       (ModelPart.translateAndRotate)
Channel values are degreeVec degrees / posVec pixels (Y UP), the numbers a Java
AnimationDefinition (and the motion engine's bedrock JSON) use.

Bend bones (motion engine, a22dc62297e3f687e, 2026-09-25):
  raider  right/left_forearm  child of *_arm at arm-local (0, 4.5, 0)  (arm cube -1.5..10.5)
          right/left_shin     child of *_leg at leg-local (0, 6, 0)    (leg cube 0..12)
  goblin  (proposed, not in the model yet) forearm at (0, 4.5, 0) of the 0..9 arm,
          shin at (0, 5, 0) of the 0..10 leg.
  Elbow flex = NEGATIVE x, knee flex = POSITIVE x. Weapons/bracers/shield ride the forearm,
  boots ride the shin. For the preview each limb cube is split at the bend pivot.

The shared settler modules are used unchanged: mcrig.PARTS / EXPORT_BONES / ROTATION_ONLY are
swapped for the enemy tables at runtime (the lead pipeline agent's documented extension
point) so hsrig.bake / hsrig.preview / export_mc_clip work as-is.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
if PIPE not in sys.path:
    sys.path.insert(0, PIPE)
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
ASSETS = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead")
TEX_DIR = os.path.join(ASSETS, "textures", "entity", "raider")
ANIM_DIR = os.path.join(ASSETS, "animations")
STAGED = os.path.join(HERE, "staged")          # clips with no Java constant yet
VIDEOS = r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\enemies"
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
DEG = math.pi / 180.0
FPS = 60

# ============================================================================ raider
def _limb(u, v, x0, y0, z0, w, h, d, split, mirror=False):
    """Split one MC limb cube at `split` (part-local y) into upper + lower cubes."""
    top_h = split - y0
    upper = [((x0, y0, z0), (w, top_h, d), (u, v), mirror, 0.0)]
    lower = [((x0, 0.0, z0), (w, h - top_h, d), (u, v + top_h), mirror, 0.0)]
    return upper, lower


_RA_U, _RA_L = _limb(32, 16, -1.5, -1.5, -1.5, 3, 12, 3, 4.5)
_LA_U, _LA_L = _limb(48, 16, -1.5, -1.5, -1.5, 3, 12, 3, 4.5, True)
_RL_U, _RL_L = _limb(0, 32, -2, 0, -2, 4, 12, 4, 6.0)
_LL_U, _LL_L = _limb(16, 32, -2, 0, -2, 4, 12, 4, 6.0, True)

RAIDER_PARTS = {
    "root": (None, (0, 24, 0), []),
    "torso": ("root", (0, -12, 0), [((-4, -12, -2), (8, 12, 4), (0, 16), False, 0.0)]),
    "head": ("torso", (0, -12, 0), [((-4, -8, -4), (8, 8, 8), (0, 0), False, 0.0)]),
    "right_arm": ("torso", (-5, -10, 0), _RA_U),
    "right_forearm": ("right_arm", (0, 4.5, 0), _RA_L),
    "left_arm": ("torso", (5, -10, 0), _LA_U),
    "left_forearm": ("left_arm", (0, 4.5, 0), _LA_L),
    "right_leg": ("root", (-2.2, -12, 0), _RL_U),
    "right_shin": ("right_leg", (0, 6, 0), _RL_L),
    "left_leg": ("root", (2.2, -12, 0), _LL_U),
    "left_shin": ("left_leg", (0, 6, 0), _LL_L),
}

SKIRM, BRUTE, CAPTAIN, BRUTE_CAPTAIN = "skirmisher", "brute", "captain", "brute_captain"


def _is_brute(v):
    return v in (BRUTE, BRUTE_CAPTAIN)


def _is_cap(v):
    return v in (CAPTAIN, BRUTE_CAPTAIN)


_SHIELD = []
for _p in range(7):
    _SHIELD.append(((-3.5 + _p, -5, -0.5), (1, 10, 1), (36, 48), False, 0.0))
for _r in range(3):
    _SHIELD.append(((-4.5 + _r * 3, -6, -0.75), (3, 1, 1), (40, 48), False, 0.0))
    _SHIELD.append(((-4.5 + _r * 3, 5, -0.75), (3, 1, 1), (40, 48), False, 0.0))
for _s in (-4.5, 3.5):
    _SHIELD.append(((_s, -5, -0.75), (1, 6, 1), (56, 48), False, 0.0))
    _SHIELD.append(((_s, 1, -0.75), (1, 4, 1), (56, 48), False, 0.0))
_SHIELD.append(((-1.5, -1.5, -1.5), (3, 3, 1), (40, 48), False, 0.0))

HALF_PI = 90.0
# name: (parent, offset px, rest rot deg (x,y,z), cubes, visible(variant) -> bool, scale)
# Offsets of parts that ride a bend bone are re-expressed in the lower segment's frame
# (arm-local y - 4.5, leg-local y - 6), exactly what the engine's carried-children do.
RAIDER_PROPS = {
    "hood": ("head", (0, 0, 0), (0, 0, 0),
             [((-4, -8, -4), (8, 8, 8), (32, 0), False, 0.45)],
             lambda v: v == SKIRM, None),
    "brow_rim": ("head", (0, 0, 0), (0, 0, 0), [
        ((-3, -6.2, -5.4), (3, 1, 2), (46, 40), False, 0.0),
        ((0, -6.2, -5.4), (3, 1, 2), (46, 40), False, 0.0),
        ((-4.4, -6, -5.1), (1, 3, 2), (46, 40), False, 0.0),
        ((3.4, -6, -5.1), (1, 3, 2), (46, 40), False, 0.0),
        ((-2, -3.5, -5), (4, 3, 2), (46, 40), False, 0.0)], lambda v: v == SKIRM, None),
    "brute_beard": ("head", (0, 0, 0), (0, 0, 0), [
        ((-2, -2, -5), (4, 2, 2), (0, 60), False, 0.0),
        ((-1, 0, -4.75), (2, 2, 1), (0, 60), False, 0.0)], lambda v: v == BRUTE, None),
    "helm": ("head", (0, 0, 0), (0, 0, 0), [((-4.5, -9, -4.5), (9, 3, 9), (0, 48), False, 0.0)],
             _is_cap, None),
    "captain_face_guard": ("head", (0, 0, 0), (0, 0, 0), [
        ((-0.5, -6, -4.75), (1, 4, 1), (40, 48), False, 0.0),
        ((-4.5, -3, -4.5), (1, 3, 1), (40, 48), False, 0.0),
        ((3.5, -3, -4.5), (1, 3, 1), (40, 48), False, 0.0),
        ((-2, -1, -4.5), (4, 2, 1), (40, 48), False, 0.0)], _is_cap, None),
    "hood_tail": ("head", (0, 0, 0), (0, 0, 0), [((-2, -2, 3), (4, 3, 2), (46, 40), False, 0.0)],
                  lambda v: v == SKIRM, None),
    "pauldron": ("right_arm", (0, 0, 0), (0, 0, 0), [
        ((-5, -2.5, -2.5), (10, 3, 5), (32, 32), False, 0.0),
        ((-4, 0.3, -2.1), (4, 2, 4), (40, 48), False, 0.0)], _is_cap, (0.5, 1, 1)),
    "right_brute_shoulder": ("right_arm", (0, 0, 0), (0, 0, -0.12 / DEG), [
        ((-2.5, -2.75, -2), (4, 2, 4), (40, 48), False, 0.0),
        ((-3, -0.9, -1.6), (3, 2, 3), (40, 48), False, 0.0)], lambda v: v == BRUTE, None),
    "left_brute_shoulder": ("left_arm", (0, 0, 0), (0, 0, 0.12 / DEG), [
        ((-1.5, -2.75, -2), (4, 2, 4), (40, 48), True, 0.0),
        ((0, -0.9, -1.6), (3, 2, 3), (40, 48), False, 0.0)], lambda v: v == BRUTE, None),
    "right_bracer": ("right_forearm", (0, -4.5, 0), (0, 0, 0), [
        ((-2, 5.5, -2), (4, 3, 4), (40, 48), False, 0.0),
        ((-1.5, 6, -2.4), (3, 2, 1), (40, 48), False, 0.0)], lambda v: v != SKIRM, None),
    "left_bracer": ("left_forearm", (0, -4.5, 0), (0, 0, 0), [
        ((-2, 5.5, -2), (4, 3, 4), (40, 48), False, 0.0),
        ((-1.5, 6, -2.4), (3, 2, 1), (40, 48), False, 0.0)], lambda v: v != SKIRM, None),
    "fur_mantle": ("torso", (0, 0, 0), (0, 0, 0), [
        ((-5, -13, -3), (4, 3, 3), (32, 40), False, 0.0),
        ((1, -13, -3), (4, 3, 3), (32, 40), False, 0.0),
        ((-4, -12, 1), (4, 3, 3), (32, 40), False, 0.0),
        ((0, -12, 1), (4, 3, 3), (32, 40), False, 0.0),
        ((-2, -9, 1.25), (4, 3, 3), (32, 40), False, 0.0)], _is_brute, None),
    "right_fur_lapel": ("torso", (-2.8, -10.1, -2.8), (-0.14 / DEG, 0, -0.18 / DEG), [
        ((-1.5, 0, -1.5), (3, 3, 3), (32, 40), False, 0.0),
        ((-1, 2.75, -1.25), (2, 2, 2), (32, 40), False, 0.0)], _is_brute, None),
    "left_fur_lapel": ("torso", (2.8, -10.1, -2.8), (-0.14 / DEG, 0, 0.18 / DEG), [
        ((-1.5, 0, -1.5), (3, 3, 3), (32, 40), False, 0.0),
        ((-1, 2.75, -1.25), (2, 2, 2), (32, 40), False, 0.0)], _is_brute, None),
    "scarf": ("torso", (0, 0, 0), (0, 0, 0), [
        ((-4, -12, -2.5), (4, 3, 2), (46, 40), False, 0.0),
        ((0, -12, -2.5), (4, 3, 2), (46, 40), False, 0.0)], lambda v: not _is_brute(v), None),
    "club": ("right_forearm", (0, 3.5, 0), (HALF_PI, 0, 0), [
        ((-0.5, -7, -0.5), (1, 8, 1), (36, 48), False, 0.0),
        ((-1.5, -10, -1.5), (3, 4, 3), (40, 48), False, 0.0),
        ((-2.5, -9.5, -1.25), (1, 3, 3), (40, 48), False, 0.0),
        ((1.5, -9.5, -1.25), (1, 3, 3), (40, 48), False, 0.0),
        ((-1, -11, -1), (2, 1, 2), (40, 48), False, 0.0)], _is_brute, None),
    "dagger": ("right_forearm", (0, 3.5, 0), (HALF_PI, 0, 0), [
        ((-0.5, -1, -0.5), (1, 3, 1), (36, 48), False, 0.0),
        ((-1.5, -2, -0.5), (3, 1, 1), (56, 55), False, 0.0),
        ((-0.5, -8, -0.5), (1, 6, 1), (56, 48), False, 0.0)], lambda v: v == SKIRM, None),
    "captain_axe": ("right_forearm", (0, 3.5, 0), (HALF_PI, 0, 0), [
        ((-0.5, -7, -0.5), (1, 8, 1), (36, 48), False, 0.0),
        ((-1, -8, -1), (3, 2, 2), (40, 48), False, 0.0),
        ((2, -8.5, -0.75), (1, 4, 2), (40, 48), False, 0.0),
        ((3, -8, -0.5), (1, 3, 1), (40, 48), False, 0.0),
        ((1, -5, -0.75), (1, 1, 2), (40, 48), False, 0.0)], lambda v: v == CAPTAIN, None),
    "captain_shield": ("left_forearm", (0, 2.5, -2), (0, 0, 0), _SHIELD, _is_cap, None),
    "right_boot_toe": ("right_shin", (0, -6, 0), (0, 0, 0),
                       [((-2, 10, -3.25), (4, 2, 3), (40, 57), False, 0.0)], lambda v: True, None),
    "left_boot_toe": ("left_shin", (0, -6, 0), (0, 0, 0),
                      [((-2, 10, -3.25), (4, 2, 3), (40, 57), False, 0.0)], lambda v: True, None),
}

# RaiderModel.setupAnim BRUTE SCALE (applied every frame, never keyed by clips)
BRUTE_SCALE = {
    "torso": (1.18, 1.05, 1.12),
    "right_arm": (1.10, 1.26, 1.10), "left_arm": (1.10, 1.26, 1.10),
    "head": (1.06, 1.02, 1.06),
    "right_leg": (1.10, 1.0, 1.10), "left_leg": (1.10, 1.0, 1.10),
}
RAIDER_TEX = {SKIRM: "raider.png", BRUTE: "raider_brute.png", CAPTAIN: "raider_captain.png",
              BRUTE_CAPTAIN: "raider_brute_captain.png"}

# ============================================================================ goblin
def _gb(u, v, x, y, z, w, h, d, mirror=False, infl=0.0):
    return ((x, y, z), (w, h, d), (u * 2, v * 2), mirror, infl)


_GA_U, _GA_L = _limb(0, 64, -1.5, 0, -1.5, 3, 9, 3, 4.5)
_GL_U, _GL_L = _limb(0, 64, -1.5, 0, -1.5, 3, 10, 3, 5.0)
GOBLIN_PARTS = {
    "root": (None, (0, 24, 0), []),
    "torso": ("root", (0, -10, 0), [_gb(32, 0, -3.5, -9, -2, 7, 9, 4)]),
    "head": ("torso", (0, -9, 0), [_gb(0, 0, -4, -8, -4, 8, 8, 8)]),
    "right_arm": ("torso", (-5, -8, 0), _GA_U),
    "right_forearm": ("right_arm", (0, 4.5, 0), _GA_L),
    "left_arm": ("torso", (5, -8, 0), _GA_U),
    "left_forearm": ("left_arm", (0, 4.5, 0), _GA_L),
    "right_leg": ("root", (-2.1, -10, 0), _GL_U),
    "right_shin": ("right_leg", (0, 5, 0), _GL_L),
    "left_leg": ("root", (2.1, -10, 0), _GL_U),
    "left_shin": ("left_leg", (0, 5, 0), _GL_L),
}
_ALL = lambda v: True  # noqa: E731
GOBLIN_PROPS = {
    "belt": ("torso", (0, 0, 0), (0, 0, 0), [_gb(64, 0, -3.65, -2, -2.15, 7.3, 1.3, 4.3)], _ALL, None),
    "hood_top": ("head", (0, 0, 0), (0, 0, 0), [_gb(32, 0, -4.6, -9, -4.5, 9.2, 2, 9)], _ALL, None),
    "hood_back": ("head", (0, 0, 0), (0, 0, 0), [_gb(32, 0, -4.6, -7, 3.5, 9.2, 7.5, 1)], _ALL, None),
    "hood_left": ("head", (0, 0, 0), (0, 0, 0), [_gb(32, 0, 3.8, -7, -4.5, 1, 7.5, 8)], _ALL, None),
    "hood_right": ("head", (0, 0, 0), (0, 0, 0), [_gb(32, 0, -4.8, -7, -4.5, 1, 7.5, 8)], _ALL, None),
    "left_ear": ("head", (4, -4, 0), (0, 0, -0.18 / DEG), [_gb(0, 32, 0, -1.3, -1, 4, 2.6, 2)], _ALL, None),
    "right_ear": ("head", (-4, -4, 0), (0, 0, 0.18 / DEG), [_gb(0, 32, -4, -1.3, -1, 4, 2.6, 2)], _ALL, None),
    "nose": ("head", (0, 0, 0), (0, 0, 0), [_gb(0, 32, -1.75, -3.85, -6.05, 3.5, 2.15, 2.2)], _ALL, None),
    "eyes": ("head", (0, 0, 0), (0, 0, 0), [((-2.8, -4.5, -4.1), (1.6, 1.3, 0.2), (192, 0), False, 0.0),
                                            ((1.2, -4.5, -4.1), (1.6, 1.3, 0.2), (192, 0), False, 0.0)], _ALL, None),
    "left_brow": ("head", (1.9, -4.95, -4.2), (0, 0, -0.22 / DEG), [_gb(32, 32, -1.1, -.35, -.15, 2.2, .7, .3)], _ALL, None),
    "right_brow": ("head", (-1.9, -5.45, -4.2), (0, 0, 0.06 / DEG), [_gb(32, 32, -1.1, -.35, -.15, 2.2, .7, .3)], _ALL, None),
    "left_pupil": ("head", (1.75, -4.4, -4.32), (0, 0, 0), [_gb(32, 32, 0, 0, 0, .55, 1.1, .2)], _ALL, None),
    "right_pupil": ("head", (-2.10, -4.4, -4.32), (0, 0, 0), [_gb(32, 32, 0, 0, 0, .55, 1.1, .2)], _ALL, None),
    "smirk": ("head", (.65, -1.5, -4.15), (0, 0, -0.18 / DEG), [_gb(32, 32, -1.4, -.15, 0, 2.8, .3, .2)], _ALL, None),
    "pouch": ("torso", (3, -2, -3), (0, 0, 0), [
        _gb(64, 32, -1.6, 1, -1.2, 3.2, 2.8, 2.4), _gb(64, 32, -.8, 0, -.7, 1.6, 1.3, 1.4),
        _gb(64, 32, -1.15, 3.8, -.9, 2.3, .6, 1.8), _gb(64, 0, -1.15, .25, -.95, 2.3, .45, 1.9)], _ALL, None),
    "strap": ("torso", (0, -5, -2.18), (0, 0, 0.57 / DEG), [_gb(64, 0, -.45, -4.3, -.15, .9, 8.6, .3)], _ALL, None),
    "right_boot": ("right_shin", (0, -5, 0), (0, 0, 0), [_gb(32, 32, -1.7, 6, -2.3, 3.4, 4, 4)], _ALL, None),
    "left_boot": ("left_shin", (0, -5, 0), (0, 0, 0), [_gb(32, 32, -1.7, 6, -2.3, 3.4, 4, 4)], _ALL, None),
}

BONES = ["root", "torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm",
         "right_leg", "left_leg", "right_shin", "left_shin"]
ROT_ONLY = {"right_forearm", "left_forearm", "right_shin", "left_shin"}

RIGS = {
    "raider": dict(parts=RAIDER_PARTS, props=RAIDER_PROPS, tex=(64, 64),
                   hip=2.2, hip_y=-12.0, thigh=6.0, shin=6.0,
                   shoulder=(5.0, -10.0), upper=4.5, lower=6.0, grip=3.5),
    "goblin": dict(parts=GOBLIN_PARTS, props=GOBLIN_PROPS, tex=(256, 128),
                   hip=2.1, hip_y=-10.0, thigh=5.0, shin=5.0,
                   shoulder=(5.0, -8.0), upper=4.5, lower=4.5, grip=3.5),
}
CUR = {"rig": "raider", "variant": SKIRM, "scale": {}}


def use(rig, variant=SKIRM):
    """Swap the shared rig tables to this enemy rig (runtime only; files untouched)."""
    spec = RIGS[rig]
    mcrig.PARTS = spec["parts"]
    mcrig.EXPORT_BONES = list(BONES)
    mcrig.ROTATION_ONLY = set(ROT_ONLY)
    CUR["rig"], CUR["variant"] = rig, variant
    CUR["scale"] = BRUTE_SCALE if (rig == "raider" and _is_brute(variant)) else {}
    return spec


# ============================================================================ maths
def world(ch, scale=None):
    """Like mcrig.pose_matrices but with ModelPart SCALE (T @ R @ S)."""
    scale = CUR["scale"] if scale is None else scale
    out = {}
    for name in mcrig.order():
        parent, pivot, _ = mcrig.PARTS[name]
        c = ch.get(name, {})
        rot = c.get("rot", (0, 0, 0))
        pos = c.get("pos", (0, 0, 0))
        t = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
        local = mcrig.mat4(mcrig.rot_zyx(rot[0] * DEG, rot[1] * DEG, rot[2] * DEG), t,
                           np.array(scale.get(name, (1, 1, 1)), float))
        out[name] = (out[parent] if parent else np.eye(4)) @ local
    return out


def _euler_near(m3, prev):
    x, y, z = mcrig.euler_from_matrix(m3)
    a = [math.degrees(x), math.degrees(y), math.degrees(z)]
    b = [a[0] + 180.0, 180.0 - a[1], a[2] + 180.0]
    if prev is None:
        return tuple(a)

    def near(v):
        return [c + 360.0 * round((p - c) / 360.0) for p, c in zip(prev, v)]
    a, b = near(a), near(b)
    da = sum((p - c) ** 2 for p, c in zip(prev, a))
    db = sum((p - c) ** 2 for p, c in zip(prev, b))
    return tuple(a if da <= db else b)


class LegIK:
    """Planted-foot two-bone legs. feet: {'right': xyz, 'left': xyz} sole centres, model px."""

    def __init__(self):
        self.prev = {}

    def __call__(self, ch, feet, knee_dir=(0.0, 0.0, -1.0), splay=0.12):
        spec = RIGS[CUR["rig"]]
        w = world(ch)
        inv_root = np.linalg.inv(w["root"])
        for side, sign in (("right", -1), ("left", 1)):
            fl = mcrig.xform(inv_root, feet[side])
            pole = inv_root[:3, :3] @ (np.array(knee_dir, float) + np.array([splay * sign, 0, 0]))
            r, flex, _ = mcrig.two_bone(np.array([sign * spec["hip"], spec["hip_y"], 0.0]), fl,
                                        spec["thigh"], spec["shin"], pole, +1)
            rot = _euler_near(r, self.prev.get(side))
            self.prev[side] = rot
            ch[side + "_leg"] = {"rot": rot}
            ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        return ch


def sole(ch, side):
    spec = RIGS[CUR["rig"]]
    return mcrig.xform(world(ch)[side + "_shin"], (0, spec["shin"], 0))


def grip_point(ch, side="right", along=0.0):
    """Model-space point on the held weapon's haft: `along` px from the hand toward the head."""
    w = world(ch)
    return mcrig.xform(w[side + "_forearm"], (0, RIGS[CUR["rig"]]["grip"], -along))


class ArmIK:
    """Numeric arm solve (handles BRUTE non-uniform SCALE): puts the hand grip on a point."""

    def __init__(self, side, local=None, w_head=25.0):
        self.side = side
        self.prev = None
        self.local = local            # forearm-local point to place (default: the hand grip)
        self.w_head_default = w_head

    # sample points along the limb (part-local y) used to keep the arm out of the head
    SAMPLES = {"arm": (0.0, 2.0, 3.0, 4.5), "fore": (0.5, 2.0, 3.5, 5.0)}
    RADIUS = 1.6

    # (part, local box min, local box max) the limb must stay out of; raider/goblin heads and
    # torsos share these proportions closely enough for avoidance
    BODY = {"raider": (("head", (-4, -8, -4), (4, 0, 4)), ("torso", (-4, -12, -2), (4, 0, 2))),
            "goblin": (("head", (-4, -8, -4), (4, 0, 4)), ("torso", (-3.5, -9, -2), (3.5, 0, 2)))}

    def _head_pen(self, w):
        """Summed squared depth of the limb's sample points inside the (inflated) head/torso boxes."""
        r = self.RADIUS
        pen = 0.0
        pts = [mcrig.xform(w[self.side + "_arm"], (0, y, 0)) for y in self.SAMPLES["arm"] if y >= 2.0] +               [mcrig.xform(w[self.side + "_forearm"], (0, y, 0)) for y in self.SAMPLES["fore"]]
        for part, lo, hi in self.BODY[CUR["rig"]]:
            inv = np.linalg.inv(w[part])
            for p in pts:
                q = mcrig.xform(inv, p)
                d = min(q[0] - lo[0] + r, hi[0] + r - q[0], q[1] - lo[1] + r, hi[1] + r - q[1],
                        q[2] - lo[2] + r, hi[2] + r - q[2])
                if d > 0:
                    pen += d * d
        return pen

    def __call__(self, ch, target, guess, w_guess=0.002, w_head=None):
        w_head = self.w_head_default if w_head is None else w_head
        local = self.local if self.local is not None else (0, RIGS[CUR["rig"]]["grip"], 0)
        arm, fore = self.side + "_arm", self.side + "_forearm"
        x0 = list(self.prev) if self.prev is not None else list(guess)
        g = np.array(guess, float)
        target = np.asarray(target, float)

        def cost(p):
            c2 = dict(ch)
            c2[arm] = {"rot": (p[0], p[1], p[2])}
            c2[fore] = {"rot": (p[3], 0.0, 0.0)}
            w = world(c2)
            hand = mcrig.xform(w[fore], local)
            c = float(np.sum((hand - target) ** 2))
            if w_head:
                c += w_head * self._head_pen(w)
            c += w_guess * float(np.sum((np.array(p) - g) ** 2))
            if self.prev is not None:          # frame-to-frame continuity: no solution flips
                c += 0.02 * float(np.sum((np.array(p) - np.array(self.prev)) ** 2))
            if p[3] > 0:
                c += p[3] ** 2
            if p[3] < -150:
                c += (p[3] + 150) ** 2
            return c

        best, val = mcrig.nelder_mead(cost, x0, [8, 8, 8, 8], iters=500)
        best, val = mcrig.nelder_mead(cost, best, [2, 2, 2, 2], iters=300)
        self.prev = [float(v) for v in best]
        ch[arm] = {"rot": tuple(self.prev[:3])}
        ch[fore] = {"rot": (self.prev[3], 0.0, 0.0)}
        return math.sqrt(max(val, 0.0))


# ============================================================================ curves
def _ease_seg(mode, u):
    if mode == "lin":
        return u
    if mode == "smooth":
        return u * u * (3 - 2 * u)
    if mode == "in":
        return u ** 3
    if mode == "in2":
        return u * u
    if mode == "out":
        return 1 - (1 - u) ** 3
    if mode == "out2":
        return 1 - (1 - u) ** 2
    if mode == "snap":
        return 1 - (1 - u) ** 5
    if mode == "hold":
        return 0.0
    raise ValueError(mode)


class Curve:
    """Pose track of 3-vectors. keys: [(t, (x,y,z), mode)] where mode shapes the segment
    ARRIVING at that key: 'auto' = C1 cubic Hermite with Catmull-Rom tangents (flowing
    motion, velocity carried through keys), or an ease: smooth / in (accelerate, fastest
    at the key: strikes) / in2 / out (decelerate: settles, recoils) / out2 / snap / lin / hold.
    `period` makes it cyclic (keys must span [0, period] with equal ends)."""

    def __init__(self, keys, period=None):
        self.k = [(float(t), np.array(v, float), (m[0] if m else "auto")) for t, v, *m in keys]
        self.period = period
        n = len(self.k)
        self.tan = []
        for i in range(n):
            if period is not None:
                ip = (i - 1) % (n - 1) if i == 0 else i - 1
                inx = 1 if i == n - 1 else i + 1
                t_prev = self.k[ip][0] - (period if i == 0 else 0.0)
                t_next = self.k[inx][0] + (period if i == n - 1 else 0.0)
                p_prev, p_next = self.k[ip][1], self.k[inx][1]
            else:
                ip, inx = max(0, i - 1), min(n - 1, i + 1)
                t_prev, t_next = self.k[ip][0], self.k[inx][0]
                p_prev, p_next = self.k[ip][1], self.k[inx][1]
            dt = max(t_next - t_prev, 1e-9)
            self.tan.append((p_next - p_prev) / dt)

    def __call__(self, t):
        if self.period is not None:
            t %= self.period
        k = self.k
        if t <= k[0][0]:
            return tuple(k[0][1])
        for i in range(1, len(k)):
            t0, p0, _ = k[i - 1]
            t1, p1, mode = k[i]
            if t <= t1:
                dt = max(t1 - t0, 1e-9)
                u = (t - t0) / dt
                if mode == "auto":
                    # tangents: zero where the neighbouring segment is an ease (a stop)
                    n = len(k)
                    periodic = self.period is not None
                    if i - 1 == 0:
                        m0 = self.tan[0] if (periodic and k[-1][2] == "auto") else np.zeros(3)
                    else:
                        m0 = self.tan[i - 1] if k[i - 1][2] == "auto" else np.zeros(3)
                    if i == n - 1:
                        m1 = self.tan[i] if (periodic and k[1][2] == "auto") else np.zeros(3)
                    else:
                        m1 = self.tan[i] if k[i + 1][2] == "auto" else np.zeros(3)
                    u2, u3 = u * u, u * u * u
                    h00, h10 = 2 * u3 - 3 * u2 + 1, u3 - 2 * u2 + u
                    h01, h11 = -2 * u3 + 3 * u2, u3 - u2
                    return tuple(h00 * p0 + h10 * dt * m0 + h01 * p1 + h11 * dt * m1)
                return tuple(p0 + (p1 - p0) * _ease_seg(mode, u))
        return tuple(k[-1][1])


def sin01(t, period, phase=0.0):
    return math.sin(2 * math.pi * (t / period + phase))


def bump(t, center, width, period=None):
    """Smooth 0..1..0 bump (raised cosine) of total `width`, optionally periodic."""
    d = t - center
    if period:
        d = (d + period / 2) % period - period / 2
    if abs(d) >= width / 2:
        return 0.0
    return 0.5 + 0.5 * math.cos(2 * math.pi * d / width)


def add(ch, bone, rot=None, pos=None):
    c = ch.setdefault(bone, {})
    if rot is not None:
        c["rot"] = tuple(a + b for a, b in zip(c.get("rot", (0, 0, 0)), rot))
    if pos is not None:
        c["pos"] = tuple(a + b for a, b in zip(c.get("pos", (0, 0, 0)), pos))
    return ch


# ============================================================================ gait
def gait_foot(u, excursion, duty, lift, lift_shape=0.8):
    """Treadmill sole track for a foot at phase u (0 = its heel strike, front). Returns (z, y)."""
    u %= 1.0
    if u < duty:
        s = u / duty
        return -excursion / 2 + excursion * s, 24.0
    s = (u - duty) / (1 - duty)
    # cubic Hermite front <- back whose end velocities equal the stance (ground) velocity, so the
    # foot plants and lifts without a velocity kink (no seam/contact pop)
    m = excursion / duty * (1 - duty)
    h00, h10, h01, h11 = 2 * s ** 3 - 3 * s ** 2 + 1, s ** 3 - 2 * s ** 2 + s, -2 * s ** 3 + 3 * s ** 2, s ** 3 - s ** 2
    z = h00 * (excursion / 2) + h10 * m + h01 * (-excursion / 2) + h11 * m
    y = 24.0 - lift * math.sin(math.pi * min(1.0, s ** lift_shape))
    return z, y


# ============================================================================ scene + run
def build_scene(texture):
    import bpy  # noqa: F401
    import hsrig
    spec = RIGS[CUR["rig"]]
    tw, th = spec["tex"]
    sc = bpy.context.scene
    space = hsrig._empty("MC_SPACE")
    space.matrix_basis = hsrig.MC_TO_BLENDER
    mat = hsrig._material("skin", texture)
    try:
        mat.blend_method = "CLIP"
        mat.alpha_threshold = 0.5
    except Exception:
        pass
    alpha = _alpha_grid(texture)
    objs = {}
    for name in mcrig.order():
        parent, pivot, cubes = mcrig.PARTS[name]
        e = hsrig._empty("part:" + name, objs[parent] if parent else space)
        e.location = pivot
        e.scale = CUR["scale"].get(name, (1, 1, 1))
        objs[name] = e
        if cubes:
            me = hsrig.cuboid_mesh("mesh:" + name, cubes, tw, th)
            _cull_transparent(me, alpha)
            me.materials.append(mat)
            mo = bpy.data.objects.new("mesh:" + name, me)
            sc.collection.objects.link(mo)
            mo.parent = e
    for name, (parent, off, rest, cubes, vis, scl) in spec["props"].items():
        if not vis(CUR["variant"]):
            continue
        e = hsrig._empty("prop:" + name, objs[parent])
        e.location = off
        e.rotation_euler = tuple(r * DEG for r in rest)
        if scl:
            e.scale = scl
        me = hsrig.cuboid_mesh("mesh:" + name, cubes, tw, th)
        _cull_transparent(me, alpha)
        me.materials.append(mat)
        mo = bpy.data.objects.new("mesh:" + name, me)
        sc.collection.objects.link(mo)
        mo.parent = e
    return objs


def _alpha_grid(path):
    import bpy
    img = bpy.data.images.load(path, check_existing=True)
    w, h = img.size
    return np.array(img.pixels[:], dtype=np.float32).reshape(h, w, 4)[::-1, :, 3]   # row 0 = top


def _cull_transparent(me, alpha):
    """Drop faces whose whole UV rectangle is transparent (Minecraft cutout overlays)."""
    import bmesh
    h, w = alpha.shape
    uv = me.uv_layers[0].data
    dead = []
    for poly in me.polygons:
        us = [uv[li].uv[0] * w for li in poly.loop_indices]
        vs = [(1.0 - uv[li].uv[1]) * h for li in poly.loop_indices]
        u0, u1 = int(math.floor(min(us) + 1e-4)), int(math.ceil(max(us) - 1e-4))
        v0, v1 = int(math.floor(min(vs) + 1e-4)), int(math.ceil(max(vs) - 1e-4))
        u0, v0 = max(0, u0), max(0, v0)
        u1, v1 = min(w, max(u1, u0 + 1)), min(h, max(v1, v0 + 1))
        if alpha[v0:v1, u0:u1].max() < 0.5:
            dead.append(poly.index)
    if dead:
        bm = bmesh.new()
        bm.from_mesh(me)
        bm.faces.ensure_lookup_table()
        bmesh.ops.delete(bm, geom=[bm.faces[i] for i in dead], context="FACES")
        bm.to_mesh(me)
        bm.free()


def preview(out_dir, length, cam_cfg, loops=2, sheet_frames=12):
    """Fast Workbench preview: contact sheet (side) + side MP4 at 60 fps, 480x360, FXAA."""
    import bpy
    import hsrig
    sc = bpy.context.scene
    os.makedirs(out_dir, exist_ok=True)
    sc.render.engine = "BLENDER_WORKBENCH"
    sc.render.resolution_x, sc.render.resolution_y = 480, 360
    sh = sc.display.shading
    sh.light = "STUDIO"
    sh.color_type = "TEXTURE"
    sh.show_shadows = False
    sh.show_cavity = False
    sc.display.render_aa = "FXAA"
    sc.view_settings.view_transform = "Standard"
    world = bpy.data.worlds.new("bg")
    world.color = (0.52, 0.62, 0.74)
    sc.world = world
    dist, tz, ty, lens = cam_cfg.get("dist", 4.3), cam_cfg.get("z", 1.0), cam_cfg.get("y", -0.3),         cam_cfg.get("lens", 40)
    cam = hsrig.camera("cam_side", (-dist, ty, tz + 0.1), (0.0, ty, tz), lens=lens)
    sc.camera = cam
    n = int(round(length * FPS))
    idx = [int(round(i * n / sheet_frames)) for i in range(sheet_frames)]
    tmp = os.path.join(out_dir, "_sheet_frames")
    hsrig.render_frames(cam, tmp, idx)
    hsrig._sheet([os.path.join(tmp, f"f{f:04d}.png") for f in idx], os.path.join(out_dir, "sheet_side.png"))
    for ob in bpy.data.objects:
        ad = ob.animation_data
        if ob.name.startswith("part:") and ad and ad.action:
            for fc in ad.action.fcurves:
                if not any(m.type == "CYCLES" for m in fc.modifiers):
                    fc.modifiers.new("CYCLES")
    hsrig._mp4(cam, os.path.join(out_dir, "side.mp4"), list(range(0, n * loops)), FPS)


def to_channels(times, samples, bones, root_base=(0, 0, 0), additive=None):
    """additive: a channel dict (the pose the clip is layered over); exported values become
    sample - additive, so an additive clip starts and ends at exact zero."""
    if additive:
        samples = [{bn: {k: tuple(v - additive.get(bn, {}).get(k, (0, 0, 0))[i] for i, v in enumerate(vec))
                         for k, vec in c.items()} for bn, c in s.items()} for s in samples]
    out = {}
    for b in bones:
        rot = [list(s.get(b, {}).get("rot", (0, 0, 0))) for s in samples]
        out[b] = {"rotation": rot}
        if b not in ROT_ONLY:
            pos = [list(s.get(b, {}).get("pos", (0, 0, 0))) for s in samples]
            if b == "root":
                pos = [[p[i] - root_base[i] for i in range(3)] for p in pos]
            out[b]["position"] = pos
    return out


def run(clip, fast=True, export=True):
    """clip: dict(rig, variant, const, length, loop, solve, bones, keep, root_base, meta,
    staged, props(list of prop_box args), cam)."""
    import bpy
    import hsrig
    use(clip["rig"], clip.get("variant", SKIRM))
    hsrig.reset()
    tex = os.path.join(TEX_DIR, clip.get("texture") or
                       (RAIDER_TEX[CUR["variant"]] if clip["rig"] == "raider" else "goblin_thief.png"))
    objs = build_scene(tex)
    hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.42, 0.22, 1))
    for args in clip.get("scenery", []):
        hsrig.prop_box(*args)
    L, loop = clip["length"], clip["loop"]
    times, samples = hsrig.bake(clip["solve"], L, objs)

    const = clip["const"]
    lname = const.lower()
    anim_name = f"animation.{clip['rig']}.{lname}"
    bones = clip.get("bones", BONES)
    chan = to_channels(times, samples, bones, clip.get("root_base", (0, 0, 0)),
                       clip["additive"]() if clip.get("additive") else None)
    checks = clip.get("checks", lambda ts, ss: {})(times, samples)
    meta = {"source": "tools/blender/pipeline/clips/enemies (Blender " + bpy.app.version_string + ")",
            "contract": clip.get("contract", ""), "checks": checks}
    if clip.get("blocks_per_cycle"):
        meta["blocks_per_cycle"] = round(clip["blocks_per_cycle"], 4)
    doc, report = ex.build_bedrock(anim_name, L, loop, times, chan, rot_tol=0.25, pos_tol=0.02,
                                   keep_times=tuple(clip.get("keep", ())) + (0.0, L), meta=meta)
    # round-trip against the runtime interpolation
    worst = 0.0
    bd = doc["animations"][anim_name]["bones"]
    for b, kinds in chan.items():
        for kind, vecs in kinds.items():
            if b not in bd or kind not in bd[b]:
                continue
            for t, v in zip(times, vecs):
                got = ex.sample(doc, anim_name, b, kind, t)
                worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
    rig_dir = clip["rig"]
    dest_root = STAGED if clip.get("staged") else ANIM_DIR
    dest = os.path.join(dest_root, rig_dir, lname + ".animation.json")
    if export:
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        ex.write(doc, dest)
    nkeys = sum(v["keys"] for v in report.values())
    print("EXPORTED", const, dest, nkeys, "keys", "roundtrip", round(worst, 4), json.dumps(checks))

    out = os.path.join(WORK, "out", "enemies", lname)
    os.makedirs(out, exist_ok=True)
    ex.write(doc, os.path.join(out, lname + ".animation.json"))
    if fast:
        t0 = __import__("time").time()
        preview(out, L, clip.get("cam", {}), loops=clip.get("loops", 2))
        print("PREVIEW_SECONDS", round(__import__("time").time() - t0, 1))
        os.makedirs(VIDEOS, exist_ok=True)
        pre = f"{clip['rig']}_{lname}"
        shutil.copyfile(os.path.join(out, "sheet_side.png"), os.path.join(VIDEOS, pre + "_sheet.png"))
        shutil.copyfile(os.path.join(out, "side.mp4"), os.path.join(VIDEOS, pre + "_side.mp4"))
    return {"const": const, "file": dest, "keys": nkeys, "roundtrip": round(worst, 4), "checks": checks}
