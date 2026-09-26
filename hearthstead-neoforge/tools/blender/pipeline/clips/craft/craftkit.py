"""Craft / workshop clip kit (craft animator). Builds on hsrig / mcrig /
export_mc_clip and never edits them.

Conventions (same as mcrig): Minecraft model space, pixels, +Y DOWN, the
settler faces -Z, +X is the settler's LEFT, ground at y = 24. Hip pivot
y = 12, shoulders y = 2, head base y = 0. Positive torso x = lean forward,
positive head x = look down, positive yaw = turn toward the settler's
RIGHT (right shoulder goes back). Elbow flex is negative x, knee flex
positive x.

A clip script:
    clip = Clip("HAMMER_ANVIL", 1.0, True, "settler_smith.png", feet=...)
    clip.keys({...body curves...})            # root_x/y/z, root_yaw, torso_x/y/z,
                                              # head_nod, head_yaw, look_x/y/z
    clip.arm_goals("right", [(t, hand_xyz, ease), ...], pole)   # FK arcs through IK key poses
    clip.arm_live("left", target_fn, pole)                      # per-frame IK (holds, slides)
    clip.run(contacts=..., keep=...)          # bake, checks, export, preview

Arms in FK mode move on joint arcs between IK-solved key poses (the lead's
chop approach): heavy swings arc instead of travelling in straight lines.
Arms in live mode stay exactly on a moving/fixed target (tongs on the work,
a plane sliding along a board). Legs are always IK to planted soles.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PIPE = os.path.abspath(os.path.join(HERE, "..", ".."))
if PIPE not in sys.path:
    sys.path.insert(0, PIPE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
VIDEOS = os.environ.get("HS_CRAFT_VIDEOS", r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\craft")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")
TEX_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                       "entity", "settler")

GROUND = 24.0
SHOULDER = {"right": np.array([-6.0, -10.0, 0.0]), "left": np.array([6.0, -10.0, 0.0])}
HIP = {"right": np.array([-2.6, -12.0, 0.0]), "left": np.array([2.6, -12.0, 0.0])}
REST_FEET = (np.array([-2.6, GROUND, 0.0]), np.array([2.6, GROUND, 0.0]))

# Blender key presets: (interp, easing) for the segment LEAVING a key.
EASE = {
    "accel": ("CUBIC", "EASE_IN"),      # strike: accelerate, fastest on arrival
    "whip": ("QUART", "EASE_IN"),       # later, harder release (elbow lag)
    "in": ("QUAD", "EASE_IN"),
    "decel": ("SINE", "EASE_OUT"),      # recoil / settle
    "out": ("QUAD", "EASE_OUT"),
    "out3": ("CUBIC", "EASE_OUT"),
    "inout": ("SINE", "EASE_IN_OUT"),
    "linear": ("LINEAR", "AUTO"),
    "hold": ("CONSTANT", "AUTO"),
    "smooth": ("BEZIER", "AUTO"),
}
def variant():
    """"" = base clip; "v2" -> <clip>__v2 (set by the author_<clip>__v2.py wrappers)."""
    return os.environ.get("HS_VARIANT", "")


def c(prop, t):
    """Control value: the (cycle-repeated) base curve plus an optional variant
    delta curve `<prop>__v` authored over the whole variant length."""
    return hsrig.ctrl(prop, t) + hsrig.ctrl(prop + "__v", t)



def smoothstep(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3.0 - 2.0 * x)


# --------------------------------------------------------------------------- IK
def two_bone(root_pt, target, l1, l2, pole, bend_sign):
    """mcrig.two_bone with a frame that stays defined when the limb is straight.

    The bend plane comes from the pole alone, so a fully extended limb (the
    exact rest pose of a one-shot) solves to exactly zero instead of a
    random twist. bend_sign < 0: elbow (joint pushes toward the pole, lower
    bone folds to local -Z); > 0: knee (lower bone folds to local +Z)."""
    root_pt = np.asarray(root_pt, float)
    d = np.asarray(target, float) - root_pt
    n = float(np.linalg.norm(d))
    dn = d / max(n, 1e-9)
    dist = min(max(n, abs(l1 - l2) + 1e-4), l1 + l2)
    cos_a = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)
    a = math.acos(max(-1.0, min(1.0, cos_a)))
    cos_k = (l1 * l1 + l2 * l2 - dist * dist) / (2 * l1 * l2)
    interior = math.pi - math.acos(max(-1.0, min(1.0, cos_k)))
    p = np.asarray(pole, float)
    p = p - dn * float(p @ dn)
    if np.linalg.norm(p) < 1e-6:
        p = np.array([0.0, 0.0, 1.0 if bend_sign < 0 else -1.0])
        p = p - dn * float(p @ dn)
    p /= np.linalg.norm(p)
    y = dn * math.cos(a) + p * math.sin(a)
    pz = p - y * float(p @ y)
    pz /= max(np.linalg.norm(pz), 1e-9)
    z = pz if bend_sign < 0 else -pz
    x = np.cross(y, z)
    return np.column_stack([x, y, z]), bend_sign * interior


def euler_near(m3, prev):
    return tuple(hsrig.euler_deg_continuous(m3, None if prev is None else list(prev)))


def solve_arm(ch, side, target, pole, prev=None, lower=6.0):
    """(arm rot deg, elbow deg) putting arm-local (0, 4+lower, 0) on a model-space target."""
    world = mcrig.pose_matrices(ch)
    tl = mcrig.xform(np.linalg.inv(world["torso"]), target)
    r, flex = two_bone(SHOULDER[side], tl, mcrig.UPPER_ARM, lower, pole, -1)
    return euler_near(r, prev), math.degrees(flex)


def choke_pos(side, wrist_rot, choke, knob=(2.5, 1.5, 8.0), neck=(9.5, 8.5, 8.0)):
    """posVec for <side>_item that slides the held tool `choke` px along its own haft
    toward the knob, i.e. the fist chokes up toward the head (runtime: the wrist bone's
    position slides the grip).  0 -> (0, 0, 0)."""
    if not choke:
        return (0.0, 0.0, 0.0)
    m = mcrig.item_in_hand_matrix(np.eye(4), side == "right")        # forearm-local item frame
    h0 = mcrig.xform(m, neck) - mcrig.xform(m, knob)
    h0 /= np.linalg.norm(h0)
    r = mcrig.rot_zyx(*[math.radians(v) for v in wrist_rot])
    d = -choke * (r @ h0)                                              # forearm frame, y down
    return (float(d[0]), float(-d[1]), float(d[2]))                   # -> posVec (y up)


def palm(world, side, lower=6.0):
    return mcrig.xform(world[side + "_forearm"], (0, lower, 0))


# --------------------------------------------------------------------------- clip
class Clip:
    def __init__(self, const, length, loop, tex, feet, slug=None, knee_out=0.15, tool_png=None,
                 cycles=1):
        """cycles > 1 (variants of work loops): the base keys repeat every
        `length`; the clip is cycles * length long so every contact tick recurs."""
        self.cycles = cycles
        self.base_length = length
        length = length * cycles
        self.const = const
        self.name = "animation.settler." + const.lower()
        self.slug = slug or const.lower()
        self.length = length
        self.loop = loop
        self.feet = [np.asarray(f, float) for f in feet]
        self.knee_out = knee_out
        hsrig.reset()
        self.objs = hsrig.build_scene(os.path.join(TEX_DIR, tex), tool_png)
        hsrig.prop_box("ground", (-60, GROUND, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
        self.arms = {}
        self.head_w = (0.5, 0.6)
        self.head_limit = (40.0, 50.0)
        self.hand_props = None     # runtime display props, e.g. [{"hand": "mainhand", "item": ...}]
        self.head_env = False      # one-shots: key "head_env" 0 -> 1 -> 0 so the head starts/ends at rest
        self.extra = None          # optional fn(ch, t, world) for clip-specific tweaks
        self.cam_side = ((-3.7, -1.35, 1.25), (0.0, -0.45, 0.85), 38)   # Blender blocks
        self.prev = {}

    # ------------------------------------------------------------- props/keys
    def prop(self, name, frm, size, colour):
        return hsrig.prop_box(name, frm, size, colour)

    def keys(self, K):
        """{prop: [(t, v[, ease-name])]}: ease names from EASE (segment leaving the key)."""
        for prop, ks in K.items():
            out = []
            for k in ks:
                e = EASE[k[2]] if len(k) > 2 and k[2] else None
                out.append((k[0], float(k[1]), *e) if e else (k[0], float(k[1])))
            L = self.base_length
            if self.loop and out[-1][0] < L - 1e-6:
                out.append((L, out[0][1]))
            if self.cycles > 1:
                one = out[:-1]
                out = [(k[0] + i * L, *k[1:]) for i in range(self.cycles) for k in one]
                out.append((self.length, one[0][1]))
            hsrig.key_curve(prop, out, cyclic=self.loop)

    def vary(self, K):
        """Variant delta curves over the WHOLE clip (not repeated): {prop: [(t, dv[, ease])]}.
        They add onto the base control `prop`; start and end them at 0."""
        for prop, ks in K.items():
            out = []
            for k in ks:
                e = EASE[k[2]] if len(k) > 2 and k[2] else None
                out.append((k[0], float(k[1]), *e) if e else (k[0], float(k[1])))
            if out[-1][0] < self.length - 1e-6:
                out.append((self.length, 0.0))
            hsrig.key_curve(prop + "__v", out, cyclic=self.loop)

    def key_vec(self, name, ks):
        """ks: [(t, (x, y, z)[, ease])] -> name_x/_y/_z."""
        for i, a in enumerate("xyz"):
            self.keys({f"{name}_{a}": [(k[0], k[1][i], *k[2:]) for k in ks]})

    def cv(self, name, t):
        return np.array([c(name + "_x", t), c(name + "_y", t), c(name + "_z", t)])

    def wrap(self, t):
        if self.loop:
            return t % self.length
        return min(max(t, 0.0), self.length)

    # ------------------------------------------------------------- body
    def body(self, t):
        return {
            "root": {"rot": (0.0, c("root_yaw", t), 0.0),
                     "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
            "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t))},
        }

    # ------------------------------------------------------------- arms
    def arm_goals(self, side, goals, pole, lower=6.0):
        """FK arm curves through IK-solved key poses.

        goals: [(t, hand_xyz | "rest", ease-name | None[, pole])]. The ease
        shapes the segment LEAVING that key. "rest" keys exact zeros (one-shot
        ends)."""
        names = [f"{side}_ax", f"{side}_ay", f"{side}_az", f"{side}_el"]
        keys = {n: [] for n in names}
        prev = None
        log = []
        for g in goals:
            t, hand = g[0], g[1]
            e = g[2] if len(g) > 2 else None
            p = g[3] if len(g) > 3 else pole
            if isinstance(hand, str):
                rot, flex = (0.0, 0.0, 0.0), 0.0
            else:
                rot, flex = solve_arm(self.body(t), side, np.asarray(hand, float), p, prev, lower)
                prev = rot
            log.append({"t": t, "rot": [round(v, 2) for v in rot], "elbow": round(flex, 2)})
            for n, v in zip(names, (*rot, flex)):
                keys[n].append((t, v, e) if e else (t, v))
        self.keys(keys)
        self.arms[side] = {"mode": "fk", "lower": lower}
        return log

    def arm_goals_tool(self, side, goals, tip_local, seed, w_tip=6.0):
        """FK arm curves (with forearm twist) from held-tool goals, via mcrig.solve_arm_goal.

        goals: [(t, hand_xyz | "rest", tip_xyz | None, handle_dir | None, ease)]: the
        tool's working point (item-sprite px `tip_local`, e.g. a hammer face) lands
        on tip_xyz, the palm near hand_xyz."""
        names = [f"{side}_ax", f"{side}_ay", f"{side}_az", f"{side}_el", f"{side}_tw"]
        keys = {n: [] for n in names}
        saved = mcrig.AXE_TIP
        mcrig.AXE_TIP = tuple(tip_local)
        log = []
        try:
            x0 = list(seed)
            for t, hand, tip, hdir, e in goals:
                if isinstance(hand, str):
                    sol = [0.0] * 5
                else:
                    sol, err = mcrig.solve_arm_goal(
                        self.body(t), side, np.asarray(hand, float),
                        None if hdir is None else np.asarray(hdir, float), x0,
                        w_dir=4.0 if hdir is not None else 0.0,
                        tip_goal=None if tip is None else np.asarray(tip, float), w_tip=w_tip)
                    x0 = sol
                    log.append({"t": t, "sol": [round(v, 1) for v in sol], "cost": round(err, 3)})
                for n, v in zip(names, sol):
                    keys[n].append((t, v, e) if e else (t, v))
        finally:
            mcrig.AXE_TIP = saved
        self.keys(keys)
        self.arms[side] = {"mode": "fk", "lower": 6.0, "tool": tuple(tip_local)}
        return log

    def arm_tool_poses(self, side, goals, tip_local, knob=(2.5, 1.5, 8.0), neck=(9.5, 8.5, 8.0),
                       seed=(-60.0, 10.0, 10.0, -45.0, 0.0), w_dir=1.0, wrist=False,
                       wrist_lim=(60.0, 30.0, 35.0), avoid_head=0.0, choke=0.0):
        """FK arm curves (with forearm twist) from tool-only goals: no palm target.

        goals: [(t, tip_xyz | "rest", haft_dir | None, ease)]. The held item's
        `tip_local` point lands on tip_xyz and its knob->neck axis follows
        haft_dir; among the solutions the one nearest the previous key wins
        (continuity), with a few canonical restarts against local minima."""
        # QA 2026-09-26: wrist=True also solves the held-item wrist bone (<side>_item,
        # pivot at the palm) so the tool can tip in the fist instead of sitting square
        # to the forearm; avoid_head > 0 keeps the forearm and fist clear of the face.
        names = [f"{side}_ax", f"{side}_ay", f"{side}_az", f"{side}_el", f"{side}_tw"]             + ([f"{side}_wx", f"{side}_wy", f"{side}_wz"] if wrist else [])
        keys = {n: [] for n in names}
        log = []
        seed = list(seed) + ([0.0, 0.0, 0.0] if wrist and len(seed) < 8 else [])
        prev = np.array(seed, float)
        restarts = [(-60, 10, 10, -45, 0), (-150, 10, 15, -60, 0), (-100, -20, 25, -90, 0),
                    (-30, 20, 5, -80, 0), (-170, 30, 30, -30, 0)]
        if wrist:
            restarts = [tuple(r) + (0.0, 0.0, 0.0) for r in restarts]
        for g in goals:
            t, tip, hdir, e = g[:4]
            palm_goal = None if len(g) < 5 or g[4] is None else np.asarray(g[4], float)
            if isinstance(tip, str):
                sol = np.zeros(len(names))
            else:
                body = self.body(t)
                tip = np.asarray(tip, float)
                d = None if hdir is None else np.asarray(hdir, float) / np.linalg.norm(hdir)
                ref = prev.copy()

                def cost(p):
                    ch = dict(body)
                    ch[side + "_arm"] = {"rot": (p[0], p[1], p[2])}
                    ch[side + "_forearm"] = {"rot": (p[3], p[4], 0.0)}
                    if wrist:
                        ch[side + "_item"] = {"rot": (p[5], p[6], p[7]),
                                              "pos": choke_pos(side, (p[5], p[6], p[7]), choke, knob, neck)}
                    w = mcrig.pose_matrices(ch)
                    it = mcrig.item_in_hand_matrix(w[side + "_forearm"], side == "right",
                                                   w.get(side + "_item") if wrist else None)
                    cst = 10.0 * float(np.sum((mcrig.xform(it, tip_local) - tip) ** 2))
                    if d is not None:
                        a = mcrig.xform(it, neck) - mcrig.xform(it, knob)
                        a /= np.linalg.norm(a)
                        cst += 25.0 * w_dir * float(np.sum((a - d) ** 2))
                    cst += 0.0004 * float(np.sum((p - ref) ** 2)) + 0.002 * p[4] ** 2
                    if palm_goal is not None:      # optional 5th goal element: where the fist is
                        cst += 1.5 * float(np.sum((mcrig.xform(w[side + "_forearm"], (0, 6, 0)) - palm_goal) ** 2))
                    if wrist:
                        for v, lim in zip(p[5:8], wrist_lim):
                            cst += 0.0008 * v * v + (0.5 * (abs(v) - lim) ** 2 if abs(v) > lim else 0.0)
                    if avoid_head > 0.0:
                        hc = mcrig.xform(w["head"], (0.0, -4.0, 0.0))
                        for q in ((0, 0, 0), (0, 3, 0), (0, 6, 0)):
                            dq = float(np.linalg.norm(mcrig.xform(w[side + "_forearm"], q) - hc))
                            if dq < 6.2:
                                cst += avoid_head * (6.2 - dq) ** 2
                        # forearm (4 px thick) kept out of the chest box as well
                        inv_t = np.linalg.inv(w["torso"])
                        for q in ((0, 1, 0), (0, 3, 0), (0, 5, 0)):
                            lq = mcrig.xform(inv_t, mcrig.xform(w[side + "_forearm"], q))
                            dx = 7.0 - abs(lq[0])
                            dy = min(lq[1] + 12.0, 0.0 - lq[1]) + 2.0
                            dz = 4.5 - abs(lq[2])
                            pen = min(dx, dy, dz)
                            if pen > 0.0:
                                cst += avoid_head * pen * pen
                        # and no corkscrew shoulders / jammed elbows to get there
                        if abs(p[1]) > 75.0:
                            cst += 0.2 * (abs(p[1]) - 75.0) ** 2
                        if abs(p[2]) > 55.0:                 # no arm-over-the-head wings
                            cst += 0.2 * (abs(p[2]) - 55.0) ** 2
                        if p[0] > 25.0:                      # upper arm not swung behind the back
                            cst += 0.2 * (p[0] - 25.0) ** 2
                        if p[3] < -115.0:
                            cst += 0.2 * (p[3] + 115.0) ** 2
                    if p[3] > 0:
                        cst += p[3] ** 2
                    if p[3] < -135:
                        cst += (p[3] + 135) ** 2
                    return cst
                best = None
                st1 = [15, 15, 15, 15, 10] + ([10, 10, 10] if wrist else [])
                st2 = [4, 4, 4, 4, 3] + ([3, 3, 3] if wrist else [])
                for x0 in [prev] + [np.array(r, float) for r in restarts]:
                    sol, val = mcrig.nelder_mead(cost, x0, st1, iters=600 if not wrist else 1500)
                    sol, val = mcrig.nelder_mead(cost, sol, st2, iters=600 if not wrist else 1500)
                    if best is None or val < best[1]:
                        best = (sol, val)
                sol = best[0]
                # keep Euler continuity with the previous key
                sol[:3] = [v + 360.0 * round((q - v) / 360.0) for v, q in zip(sol[:3], prev[:3])]
                prev = sol.copy()
                log.append({"t": t, "sol": [round(float(v), 1) for v in sol], "cost": round(best[1], 3)})
            for n, v in zip(names, sol):
                keys[n].append((t, float(v), e) if e else (t, float(v)))
        self.keys(keys)
        self.arms[side] = {"mode": "fk", "lower": 6.0, "tool": tuple(tip_local), "wrist": wrist,
                           "choke": (choke, knob, neck)}
        return log

    def tool_tip(self, world, side="right"):
        tip = self.arms[side]["tool"]
        return mcrig.xform(mcrig.item_in_hand_matrix(world[side + "_forearm"], side == "right",
                                                     world.get(side + "_item")), tip)

    def arm_live(self, side, target_fn, pole, lower=6.0, weight=None):
        """Per-frame IK onto target_fn(t, world) (model px). weight: curve name
        blending FK (0, from arm_goals) and IK (1); None = always IK."""
        prev_fk = self.arms.get(side)
        self.arms[side] = {"mode": "live", "fn": target_fn, "pole": pole, "lower": lower,
                           "weight": weight, "has_fk": prev_fk is not None}

    def _arm(self, ch, side, t, world):
        a = self.arms.get(side)
        if a is None:
            return
        fk_rot = (c(f"{side}_ax", t), c(f"{side}_ay", t), c(f"{side}_az", t))
        fk_el = c(f"{side}_el", t)
        if a["mode"] == "fk":
            ch[side + "_arm"] = {"rot": fk_rot}
            ch[side + "_forearm"] = {"rot": (fk_el, c(f"{side}_tw", t), 0.0)}
            if a.get("wrist"):
                wr = (c(f"{side}_wx", t), c(f"{side}_wy", t), c(f"{side}_wz", t))
                ck_, kn_, ne_ = a.get("choke", (0.0, None, None))
                ch[side + "_item"] = {"rot": wr, "pos": choke_pos(side, wr, ck_, kn_, ne_) if ck_ else (0.0, 0.0, 0.0)}
            return
        pole = a["pole"](t) if callable(a["pole"]) else a["pole"]
        tgt = a["fn"](t, world)
        rot, flex = solve_arm(ch, side, tgt, pole, self.prev.get(side + "_ik"), a["lower"])
        self.prev[side + "_ik"] = rot
        w = 1.0 if a["weight"] is None else max(0.0, min(1.0, c(a["weight"], t)))
        if w < 1.0 and a["has_fk"]:
            fk = [f + 360.0 * round((i - f) / 360.0) for f, i in zip(fk_rot, rot)]
            rot = tuple(f + (i - f) * w for f, i in zip(fk, rot))
            flex = fk_el + (flex - fk_el) * w
        ch[side + "_arm"] = {"rot": tuple(rot)}
        ch[side + "_forearm"] = {"rot": (flex, 0.0, 0.0)}

    # ------------------------------------------------------------- head/legs/cloak
    def _head(self, ch, t, world):
        torso = world["torso"]
        tgt = self.cv("look", t)
        eye = mcrig.xform(torso, (0, -16, -2))
        d = np.linalg.inv(torso[:3, :3]) @ (tgt - eye)
        yaw = math.degrees(math.atan2(-d[0], -d[2]))
        pitch = math.degrees(math.atan2(d[1], math.hypot(d[0], d[2])))
        wp, wy = self.head_w
        pitch = max(-self.head_limit[0], min(self.head_limit[0] + 15, wp * pitch + c("head_nod", t)))
        yaw = max(-self.head_limit[1], min(self.head_limit[1], wy * yaw + c("head_yaw", t)))
        env = max(0.0, min(1.0, c("head_env", t))) if self.head_env else 1.0
        ch["head"] = {"rot": (pitch * env, yaw * env, c("head_roll", t))}

    def _legs(self, ch, world):
        inv_root = np.linalg.inv(world["root"])
        for side, foot in (("right", self.feet[0]), ("left", self.feet[1])):
            hip = HIP[side]
            fl = mcrig.xform(inv_root, foot)
            pole = inv_root[:3, :3] @ np.array([self.knee_out * np.sign(hip[0]), 0.0, -1.0])
            r, flex = two_bone(hip, fl, mcrig.THIGH, mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
            rot = euler_near(r, self.prev.get(side + "_leg"))
            self.prev[side + "_leg"] = rot
            ch[side + "_leg"] = {"rot": rot}
            ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}

    def _cloak(self, t):
        dt = 1.0 / 60.0

        def pitch(u):
            return c("torso_x", self.wrap(u))

        def yaw(u):
            return c("torso_y", self.wrap(u)) + c("root_yaw", self.wrap(u))
        lag = 0.05
        u = t - lag
        if not self.loop:
            u = max(u, dt)
        pv = (pitch(u) - pitch(u - dt)) / dt
        yv = (yaw(u) - yaw(u - dt)) / dt
        x = 0.35 * pitch(t) - 0.05 * pv + c("cloak_add", t)
        z = -0.02 * yv
        env = max(0.0, min(1.0, c("head_env", t))) if self.head_env else 1.0
        return (max(-10.0, min(22.0, x)) * env, 0.0, max(-10.0, min(10.0, z)) * env)

    # ------------------------------------------------------------- solve
    def solve(self, t):
        t = self.wrap(t)
        ch = self.body(t)
        ch["cloak"] = {"rot": self._cloak(t)}
        world = mcrig.pose_matrices(ch)
        for side in ("right", "left"):
            self._arm(ch, side, t, world)
            # QA 2026-09-26: a clip may key the held tool's wrist directly
            # (<side>_wrist_x/y/z), e.g. to tip the lumberer's axe down and away
            # while the hands do table work, so it never sweeps through the face.
            ob = hsrig.controls()
            if (side + "_item") not in ch and ob.animation_data.action.fcurves.find(f'["{side}_wrist_x"]') is not None:
                ch[side + "_item"] = {"rot": (c(f"{side}_wrist_x", t), c(f"{side}_wrist_y", t),
                                              c(f"{side}_wrist_z", t))}
        self._head(ch, t, world)
        self._legs(ch, world)
        if self.extra:
            self.extra(ch, t, world)
        return ch

    # ------------------------------------------------------------- run
    def run(self, contacts=(), keep=(), meta=None, surfaces=(), strike=None, args=None):
        """Bake, check, export, preview.

        contacts: [(label, t, side, target_xyz or fn(world))] palm-on-target checks.
        keep: extra key times preserved exactly in the JSON (holds, beats).
        surfaces: [(label, xmin, xmax, zmin, zmax, ytop)] palm must stay above ytop - 0.5.
        strike: (side, t_contact) -> reports where the palm speed peaks."""
        a = hsrig.parse_args(args)
        self.prev = {}
        if self.cycles > 1:
            L = self.base_length
            contacts = [(f"{lab}_c{i + 1}", t + i * L, side, tgt) for i in range(self.cycles)
                        for lab, t, side, tgt in contacts]
            keep = [k + i * L for i in range(self.cycles) for k in keep]
            if strike:
                tcs = strike[1] if isinstance(strike[1], (list, tuple)) else [strike[1]]
                strike = (strike[0], [t + i * L for i in range(self.cycles) for t in tcs])
        times, samples = hsrig.bake(self.solve, self.length, self.objs)
        checks = {}
        foot = 0.0
        pen = {s[0]: 0.0 for s in surfaces}
        pen_at = {s[0]: None for s in surfaces}
        palms = {"right": [], "left": []}
        for fi, s in enumerate(samples):
            w = mcrig.pose_matrices(s)
            for side, f in (("right", self.feet[0]), ("left", self.feet[1])):
                foot = max(foot, float(np.linalg.norm(mcrig.xform(w[side + "_shin"], (0, 6, 0)) - f)))
            for side in ("right", "left"):
                p = palm(w, side, self.arms.get(side, {}).get("lower", 6.0))
                palms[side].append(p)
                for lab, x0, x1, z0, z1, top in surfaces:
                    if x0 <= p[0] <= x1 and z0 <= p[2] <= z1:
                        if float(p[1] - top) > pen[lab]:
                            pen[lab] = float(p[1] - top)
                            pen_at[lab] = (side, round(fi / hsrig.FPS, 3))
        checks["foot_slide_px_max"] = round(foot, 3)
        for lab, v in pen.items():
            checks[f"palm_below_{lab}_top_px_max"] = [round(v, 2), pen_at[lab]]
        for lab, t, side, tgt in contacts:
            f = int(round(t * hsrig.FPS))
            w = mcrig.pose_matrices(samples[f])
            goal = tgt(w) if callable(tgt) else np.asarray(tgt, float)
            if side.endswith("_tool"):
                got = self.tool_tip(w, side[:-5])
            else:
                got = palms[side][f]
            checks[f"contact_{lab}"] = {"t": t, "tick": round(t * 20, 2), "frame": f,
                                        "err_px": round(float(np.linalg.norm(got - goal)), 3)}
        if strike:
            side, tc = strike[0], strike[1]
            if side.endswith("_tool"):
                P = np.array([self.tool_tip(mcrig.pose_matrices(s), side[:-5]) for s in samples])
            else:
                P = np.array(palms[side])
            sp = np.linalg.norm(np.diff(P, axis=0), axis=1) * hsrig.FPS
            tcs = tc if isinstance(tc, (list, tuple)) else [tc]
            out_s = []
            for tcc in tcs:
                fc = int(round(tcc * hsrig.FPS))
                lo = max(0, fc - 12)
                i = lo + int(np.argmax(sp[lo:fc + 1]))
                out_s.append({"contact_t": tcc, "window_peak_t": round((i + 0.5) / hsrig.FPS, 3),
                              "window_peak_px_s": round(float(sp[i]), 1),
                              "speed_into_contact_px_s": round(float(sp[fc - 1]), 1),
                              "speed_out_of_contact_px_s": round(float(sp[fc]), 1)})
            checks["strike"] = out_s
        chan = hsrig.to_export_channels(times, samples)
        seam = 0.0
        ends = 0.0
        for b, kinds in chan.items():
            for kind, vecs in kinds.items():
                seam = max(seam, max(abs(x - y) for x, y in zip(vecs[0], vecs[-1])))
                ends = max(ends, max(abs(v) for v in vecs[0] + vecs[-1]))
        if self.loop:
            checks["loop_seam_max_delta"] = round(seam, 4)
        else:
            checks["rest_at_ends_max_abs"] = round(ends, 4)
        print("CHECKS", self.slug, json.dumps(checks))

        keep_times = sorted(set([0.0, self.length] + [k[1] for k in contacts] + list(keep)))
        m = {"source": f"tools/blender/pipeline/clips/craft/author_{self.slug}.py (Blender "
                       + bpy.app.version_string + ")"}
        m.update(meta or {})
        m["checks"] = checks
        doc, report = ex.build_bedrock(self.name, self.length, self.loop, times, chan,
                                       rot_tol=0.25, pos_tol=0.02, keep_times=keep_times, meta=m)
        if self.hand_props:
            doc["animations"][self.name]["hearthstead_props"] = self.hand_props
        worst = 0.0
        bones_doc = doc["animations"][self.name]["bones"]
        for b, kinds in chan.items():
            for kind, vecs in kinds.items():
                if b not in bones_doc or kind not in bones_doc[b]:
                    continue
                for t, v in zip(times, vecs):
                    got = ex.sample(doc, self.name, b, kind, t)
                    worst = max(worst, max(abs(x - q) for x, q in zip(got, v)))
        out = os.path.join(WORK, "out", "craft", self.slug)
        os.makedirs(out, exist_ok=True)
        if a["export"]:
            path = os.path.join(ANIM_DIR, self.const.lower() + ".animation.json")
            ex.write(doc, path)
            print("EXPORTED", path, sum(v["keys"] for v in report.values()), "keys, roundtrip",
                  round(worst, 4))
        ex.write(doc, os.path.join(out, self.const.lower() + ".animation.json"))
        with open(os.path.join(out, "export_report.json"), "w") as fh:
            json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
        bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out, self.slug + ".blend"))
        if a["fast"] or a["full"]:
            hsrig.preview(out, self.length, fast=not a["full"], full=a["full"], cams=self.cameras(),
                          loops=2 if self.loop else 1)
            os.makedirs(VIDEOS, exist_ok=True)
            pairs = [("side.mp4", "_side.mp4"), ("sheet_side.png", "_sheet.png")]
            if a["full"]:
                pairs += [("front34.mp4", "_front34.mp4"), ("side_half.mp4", "_side_half_speed.mp4"),
                          ("front34_half.mp4", "_front34_half_speed.mp4"),
                          ("sheet_front34.png", "_front34_sheet.png")]
            for src, suffix in pairs:
                sp = os.path.join(out, src)
                if os.path.exists(sp):
                    shutil.copyfile(sp, os.path.join(VIDEOS, self.slug + suffix))
            print("PREVIEW_COPIED", os.path.join(VIDEOS, self.slug + "_side.mp4"))
        return checks

    def cameras(self):
        # Blender space: x = settler's left, -y = forward, z up (blocks). The
        # "side" view sits off the settler's right shoulder, a little in front,
        # so the work, both hands and the knees all read.
        return {
            "side": hsrig.camera("cam_side", self.cam_side[0], self.cam_side[1], lens=self.cam_side[2]),
            "front34": hsrig.camera("cam_front34", (-2.2, -3.2, 1.55), (0.0, -0.4, 0.9), lens=40),
        }
