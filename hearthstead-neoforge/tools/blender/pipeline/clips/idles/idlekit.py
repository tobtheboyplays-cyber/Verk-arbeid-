"""Parametric idle generator for every settler idle clip (base + per-job variants).

One generator, many small overlays. Every idle is

    stance (planted feet, IK legs, auto hip height)   <- spec: feet, knee, lean
  + breathing (asymmetric inhale/exhale, chest scale)  <- spec: breath
  + weight shift (hips travel + roll, shoulders counter-roll late)
  + gaze (world yaw/pitch; head leads, torso and hips follow late)
  + arms: rest pose + hanging-arm pendulum lag + fidget + authored gesture
          offsets, optionally blended to two-bone IK reaches (hand to brow,
          hand to mug, whetstone along the axe edge ...)
  + cloak drag from torso velocity

A job spec (idle_specs.py) is a small dict: posture numbers plus a few keyed
gesture curves. Every keyed curve is a cyclic Blender F-curve on the CTRL
object (Bezier, per-key easing), so the saved .blend is editable. All
procedural terms use whole cycles per loop and every lag is evaluated
cyclically, so each clip loops exactly (pose AND velocity).

Model space (mcrig): px, +Y down, settler faces -Z, +X = settler's left.
Channel convention: rotation degrees, position posVec px with Y up.
Elbow flex NEGATIVE x, knee flex POSITIVE x (bend bones are rotation-only).
"""

from __future__ import annotations

import json
import math
import os
import shutil
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

REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
TEXDIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                      "entity", "settler")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations",
                        "settler")
ITEMS = os.path.join(WORK, "ref", "idle_items")
VIDEOS = r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\idles"
FPS = hsrig.FPS
D = math.radians

# Blender key easing presets (segment LEAVING the key)
EASE = {"s": ("BEZIER", "AUTO"), "a": ("CUBIC", "EASE_IN"), "d": ("SINE", "EASE_OUT"),
        "D": ("CUBIC", "EASE_OUT"), "l": ("LINEAR", "AUTO"), "w": ("QUART", "EASE_IN"),
        "h": ("CONSTANT", "AUTO"), "io": ("SINE", "EASE_IN_OUT")}

# vanilla thirdperson display transforms: (translation, rotation XYZ, scale)
DISPLAY = {
    "handheld": ((0, 4, 0.5), (0, -90, 55), 0.85),
    "bow": ((-1, -2, 2.5), (-80, 260, -40), 0.9),
    "rod": ((0, 2, 0), (0, 90, 45), 0.75),          # hearthstead:fishers_rod model
    "generated": ((0, 3, 1), (0, 0, 0), 0.55),
}
ITEM_KIND = {"hoe": ("iron_hoe", "handheld"), "axe": ("iron_axe", "handheld"),
             "sword": ("iron_sword", "handheld"), "pickaxe": ("iron_pickaxe", "handheld"),
             "bow": ("bow", "bow"), "rod": (None, "rod"), "flint": ("flint", "generated"),
             "bread": ("bread", "generated"), "book": ("book", "generated"),
             "potion": ("potion", "generated"), "paper": ("paper", "generated")}


# --------------------------------------------------------------------------- maths
def item_matrix(fore_world, display, right=True):
    """World frame of an item model held in a hand (vanilla ItemInHandLayer + display)."""
    (tx, ty, tz), (rx_, ry_, rz_), s = DISPLAY[display]
    side = 1 if right else -1
    m = fore_world @ mcrig.T(0, -4, 0)
    m = m @ mcrig.mat4(mcrig.rx(D(-90))) @ mcrig.mat4(mcrig.ry(D(180))) @ mcrig.T(side, 2, -10)
    r = mcrig.rx(D(rx_)) @ mcrig.ry(D(ry_ * side)) @ mcrig.rz(D(rz_ * side))
    return m @ mcrig.T(tx * side, ty, tz) @ mcrig.mat4(r, s=s) @ mcrig.T(-8, -8, -8)


def breath_wave(p, inhale=0.40, rest=0.10):
    """0..1 chest fill for cycle phase p in [0,1): eased inhale, longer exhale, short pause."""
    p %= 1.0
    ex_end = 1.0 - rest
    if p < inhale:
        return 0.5 - 0.5 * math.cos(math.pi * p / inhale)
    if p < ex_end:
        return 0.5 + 0.5 * math.cos(math.pi * (p - inhale) / (ex_end - inhale))
    return 0.0


def unwrap_to(ref, v):
    return [a + 360.0 * round((r - a) / 360.0) for r, a in zip(ref, v)]


def two_bone_arm(torso_world, side, target, pole, prev, grip=6.0):
    """Arm IK in torso space. Returns ((ax, ay, az), elbow_deg)."""
    sign = -1 if side == "r" else 1
    tl = mcrig.xform(np.linalg.inv(torso_world), target)
    p = np.array(pole, float) * np.array([sign, 1, 1])
    r, flex, _ = mcrig.two_bone(np.array([6.0 * sign, -10.0, 0.0]), tl, mcrig.UPPER_ARM,
                                grip, p, -1)
    rot = hsrig.euler_deg_continuous(r, prev)
    return rot, math.degrees(flex)


# --------------------------------------------------------------------------- keys
def key(name, keys, L):
    """keys [(t, v[, ease])] -> cyclic CTRL F-curve with period exactly L."""
    ks = sorted(keys, key=lambda k: k[0])
    if ks[0][0] > 1e-6:
        ks = [(0.0, ks[-1][1] if abs(ks[-1][0] - L) < 1e-6 else 0.0)] + ks
    if abs(ks[-1][0] - L) > 1e-6:
        ks = ks + [(L, ks[0][1])]
    out = []
    for k in ks:
        interp, easing = EASE[k[2]] if len(k) > 2 else EASE["s"]
        out.append((k[0], float(k[1]), interp, easing))
    hsrig.key_curve(name, out, cyclic=True, length=L)


def key_vec(prefix, keys, L):
    """keys [(t, (x,y,z)[, ease])] -> three curves prefix_x/_y/_z."""
    for i, ax in enumerate("xyz"):
        key(f"{prefix}_{ax}", [(k[0], k[1][i], *k[2:]) for k in keys], L)


class Spec(dict):
    def __getattr__(self, k):
        return self.get(k)


DEFAULTS = dict(
    length=4.0, texture="settler_none.png", item=None, offhand=None,
    feet=((-2.9, 0.4), (2.9, -0.4)), knee=5.0, lean=0.0, hip_back=0.045,
    breath=dict(amp=1.0, cycles=1, phase=0.0, inhale=0.40),
    weight_px=0.55, roll=1.4, weight=None,
    gaze_yaw=None, gaze_pitch=None, follow=0.22,
    arm_r=(0.0, 0.0, 2.5, -9.0), arm_l=(0.0, 0.0, -2.5, -9.0), hang_r=1.0, hang_l=1.0,
    head_z=0.0, torso_yaw=0.0, fidget=1.0, seed=1,
    pole_r=(0.8, 0.55, 0.45), pole_l=(0.8, 0.55, 0.45),
    g={}, reach={}, legs_fk={}, cloak=2.0,
)


# --------------------------------------------------------------------------- the clip
class IdleClip:
    def __init__(self, spec):
        s = Spec(DEFAULTS)
        s.update(spec)
        s["breath"] = {**DEFAULTS["breath"], **spec.get("breath", {})}
        self.s = s
        self.L = s.length
        self.prev = {}

    # ---- scene + curves
    def build(self):
        s, L = self.s, self.L
        hsrig.reset()
        self.objs = hsrig.build_scene(os.path.join(TEXDIR, s.texture))
        hsrig.prop_box("ground", (-40, 24, -40), (80, 1, 80), (0.30, 0.45, 0.22, 1))
        for p in s.get("props", []):
            hsrig.prop_box(*p)
        key("weight", s.weight or [(0, -0.55), (0.45 * L, 0.6), (L, -0.55)], L)
        key("gaze_yaw", s.gaze_yaw or [(0, 0.0), (L, 0.0)], L)
        key("gaze_pitch", s.gaze_pitch or [(0, 2.0), (L, 2.0)], L)
        for name, ks in s.g.items():
            if isinstance(ks[0][1], (tuple, list)):
                key_vec(name, ks, L)
            else:
                key(name, ks, L)
        for side, r in s.reach.items():
            key(f"reach_{side}_w", r["w"], L)
            key_vec(f"reach_{side}_p", r["p"], L)
        self._items()

    def _items(self):
        s = self.s
        self.item_disp = None
        for hand, it in (("r", s.item), ("l", s.offhand)):
            if not it:
                continue
            png, disp = ITEM_KIND[it]
            if hand == "r":
                self.item_disp = disp
            parent = self.objs["right_forearm" if hand == "r" else "left_forearm"]
            e = bpy.data.objects.new(f"item_{hand}", None)
            bpy.context.scene.collection.objects.link(e)
            e.parent = parent
            # forearm-local frame: world == forearm_world, so pass identity
            rel = item_matrix(np.eye(4), disp, right=(hand == "r"))
            e.matrix_basis = Matrix(rel.tolist())
            if png and os.path.exists(os.path.join(ITEMS, png + ".png")):
                hsrig.sprite_mesh(f"mesh:item_{hand}", os.path.join(ITEMS, png + ".png"), e)
            elif it == "rod":
                self._rod_mesh(e)

    def _rod_mesh(self, parent):
        boxes = [((7.4, 0, 7.4), (8.6, 19, 8.6), (0.55, 0.42, 0.28, 1)),
                 ((7.1, 0, 7.1), (8.9, 5, 8.9), (0.35, 0.22, 0.15, 1)),
                 ((6.5, 3, 8.6), (9.5, 6, 10.4), (0.75, 0.45, 0.25, 1)),
                 ((9.75, 6, 7.85), (10.0, 18.2, 8.1), (0.95, 0.95, 0.95, 1))]
        for i, (a, b, col) in enumerate(boxes):
            me = bpy.data.meshes.new(f"rod{i}")
            (x0, y0, z0), (x1, y1, z1) = a, b
            v = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
                 (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
            f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 4, 7, 3)]
            me.from_pydata(v, [], f)
            mat = bpy.data.materials.new(f"rodmat{i}")
            mat.diffuse_color = col
            mat.use_nodes = True
            mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = col
            me.materials.append(mat)
            ob = bpy.data.objects.new(f"rod{i}", me)
            bpy.context.scene.collection.objects.link(ob)
            ob.parent = parent

    # ---- evaluation helpers
    def c(self, name, t):
        return hsrig.ctrl(name, t % self.L)

    def cv(self, prefix, t):
        return np.array([self.c(f"{prefix}_{a}", t) for a in "xyz"])

    def breath(self, t):
        b = self.s.breath
        p = b["cycles"] * t / self.L + b["phase"]
        return b["amp"] * (breath_wave(p, b["inhale"]) + self.c("sigh", t))

    def fid(self, t, k):
        """Loop-exact low-frequency fidget, channel k."""
        rng = np.random.default_rng(self.s.seed * 101 + k)
        a1, a2 = rng.uniform(0.5, 1.0), rng.uniform(0.2, 0.45)
        n1, n2 = rng.choice([1, 2]), rng.choice([2, 3])
        f1, f2 = rng.uniform(0, 2 * math.pi, 2)
        x = t / self.L * 2 * math.pi
        return self.s.fidget * (a1 * math.sin(n1 * x + f1) + a2 * math.sin(n2 * x + f2))

    def root_rot(self, t):
        s = self.s
        w = self.c("weight", t)
        return (self.c("root_x", t),
                s.get("root_yaw", 0.0) + 1.2 * w + self.c("root_yaw", t)
                + 0.08 * self.c("gaze_yaw", t - 0.3),
                -w * s.roll + self.c("root_roll", t))

    def torso_rot(self, t):
        s = self.s
        w_lag = self.c("weight", t - 0.12)
        return (s.lean + self.c("torso_x", t) - 1.1 * self.breath(t),
                s.torso_yaw + self.c("torso_y", t) + s.follow * self.c("gaze_yaw", t - 0.2),
                0.85 * s.roll * w_lag + self.c("torso_z", t))

    def body_angles(self, t):
        """(pitch, yaw, roll) of the torso in the world (additive approximation)."""
        r, q = self.root_rot(t), self.torso_rot(t)
        return (r[0] + q[0], r[1] + q[1], r[2] + q[2])

    def feet(self, t):
        s = self.s
        out = []
        for i, side in enumerate("rl"):
            x, z = s.feet[i]
            off = self.cv(f"foot_{side}", t) if f"foot_{side}" in s.g else np.zeros(3)
            out.append(np.array([x, 24.0, z]) + np.array([off[0], -off[1], off[2]]))
        return out

    # ---- the pose
    def solve(self, t):
        s, L = self.s, self.L
        t %= L
        b = self.breath(t)
        w = self.c("weight", t)
        feet = self.feet(t)
        rr = self.root_rot(t)
        tq = self.torso_rot(t)
        lean_total = s.lean + self.c("torso_x", t)
        rx_ = sum(f[0] for f in feet) / 2 + w * s.weight_px + self.c("root_px", t)
        rz_ = sum(f[2] for f in feet) / 2 * 0.35 + s.hip_back * lean_total + self.c("root_pz", t)
        # hip height: the longer leg keeps `knee` degrees of soft flex, then authored dip
        R = mcrig.rot_zyx(D(rr[0]), D(rr[1]), D(rr[2]))
        m0 = mcrig.mat4(R, (rx_, 24.0, rz_))
        def allowance(knee_deg):
            lmax = 12.0 * math.cos(D(knee_deg) / 2)
            ys = []
            for f, hx in zip(feet, (-2.6, 2.6)):
                hip = mcrig.xform(m0, (hx, -12.0, 0.0))
                dxz = math.hypot(hip[0] - f[0], hip[2] - f[2])
                dv = math.sqrt(max(lmax * lmax - dxz * dxz, 0.0))
                ys.append(dv - f[1] + hip[1])
            return min(ys)
        # soft-knee height minus the authored dip; a negative dip (rise) stops at 2 deg knees
        ry_ = min(allowance(s.knee) - self.c("dip", t), allowance(2.0))
        ch = {"root": {"rot": rr, "pos": (rx_, ry_, rz_)},
              "torso": {"rot": tq,
                        "scale": (1 + 0.010 * b, 1 + 0.012 * b, 1 + 0.024 * b)}}
        # head: world gaze minus body, keeps the horizon against the roll
        bp, by, bz = self.body_angles(t)
        gy, gp = self.c("gaze_yaw", t), self.c("gaze_pitch", t)
        ch["head"] = {"rot": (gp - bp + 0.7 * b + self.c("head_x", t),
                              max(-70.0, min(70.0, gy - by + self.c("head_y", t))),
                              -0.6 * bz + s.head_z + self.c("head_z", t))}
        # cloak: rest + breath + drag against torso pitch / yaw velocity
        dt = 1.0 / 30.0
        pv = (self.body_angles(t - 0.05)[0] - self.body_angles(t - 0.05 - dt)[0]) / dt
        yv = (self.body_angles(t - 0.05)[1] - self.body_angles(t - 0.05 - dt)[1]) / dt
        ch["cloak"] = {"rot": (max(-12.0, min(14.0, s.cloak + 0.8 * b - 0.10 * pv
                                              + self.c("cloak_x", t))),
                               0.0, max(-8.0, min(8.0, -0.05 * yv)))}
        # arms: FK (rest + pendulum lag + fidget + gesture), then IK reach blend
        lag = self.body_angles(t - 0.14)
        world = mcrig.pose_matrices(ch)
        for side, k0 in (("r", 0), ("l", 10)):
            name = "right" if side == "r" else "left"
            sg = -1 if side == "r" else 1
            ax, ay, az, el = s["arm_" + side]
            hang = s["hang_" + side]
            fk = [ax + self.c(f"arm_{side}_x", t) - hang * lag[0] + 0.8 * self.fid(t, k0),
                  ay + self.c(f"arm_{side}_y", t),
                  az + self.c(f"arm_{side}_z", t) - hang * lag[2] - sg * 1.3 * b
                  + 0.5 * self.fid(t, k0 + 1)]
            elbow = el + self.c(f"elbow_{side}", t) + 1.2 * self.fid(t, k0 + 2)
            for slot in sorted(k for k in s.reach if k.rstrip("+") == side):
                rw = max(0.0, min(1.0, self.c(f"reach_{slot}_w", t)))
                if rw <= 1e-4:
                    continue
                r = s.reach[slot]
                tgt = self.space_point(r["space"], self.cv(f"reach_{slot}_p", t), world, ch)
                ik, flex = two_bone_arm(world["torso"], side, tgt, r.get("pole", s["pole_" + side]),
                                        self.prev.get(slot), r.get("grip", 6.0))
                self.prev[slot] = ik
                ik = unwrap_to(fk, ik)
                fk = [f + (i - f) * rw for f, i in zip(fk, ik)]
                elbow = elbow + (flex - elbow) * rw
            ch[name + "_arm"] = {"rot": tuple(fk)}
            ch[name + "_forearm"] = {"rot": (min(-2.0, elbow), 0.0, 0.0)}
            world = mcrig.pose_matrices(ch)
        # legs: planted feet, knees forward
        inv_root = np.linalg.inv(world["root"])
        for (side, hx), f in zip((("right", -2.6), ("left", 2.6)), feet):
            fl = mcrig.xform(inv_root, f)
            pole = inv_root[:3, :3] @ np.array([0.15 * np.sign(hx), 0.0, -1.0])
            r, flex, _ = mcrig.two_bone(np.array([hx, -12.0, 0.0]), fl, mcrig.THIGH,
                                        mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
            ch[side + "_leg"] = {"rot": tuple(hsrig.euler_deg_continuous(r, self.prev.get(side)))}
            self.prev[side] = ch[side + "_leg"]["rot"]
            ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        return ch

    def space_point(self, space, p, world, ch):
        if space == "model":
            return p
        if space == "item_r":
            return mcrig.xform(item_matrix(world["right_forearm"], self.item_disp or "handheld"), p)
        if space == "hand_r":            # right forearm local (hand end at y=6)
            return mcrig.xform(world["right_forearm"], p)
        return mcrig.xform(world[space], p)   # torso / head / root local px

    # ---- bake, check, export, preview
    def bake(self):
        L = self.L
        sc = bpy.context.scene
        n = int(round(L * FPS))
        sc.frame_start, sc.frame_end = 0, n
        self.prev = {}
        self.solve(L - 2.0 / FPS)          # warm the IK continuity seeds
        self.solve(L - 1.0 / FPS)
        times, samples = [], []
        for f in range(n + 1):
            t = f / FPS
            ch = self.solve(t)
            times.append(t)
            samples.append(ch)
            for name, e in self.objs.items():
                if name not in mcrig.PARTS:
                    continue
                pivot = mcrig.PARTS[name][1]
                cc = ch.get(name, {})
                rot, pos = cc.get("rot", (0, 0, 0)), cc.get("pos", (0, 0, 0))
                e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
                e.rotation_euler = tuple(math.radians(r) for r in rot)
                e.keyframe_insert("location", frame=f)
                e.keyframe_insert("rotation_euler", frame=f)
                if "scale" in cc:
                    e.scale = cc["scale"]
                    e.keyframe_insert("scale", frame=f)
        for e in self.objs.values():
            ad = e.animation_data
            if ad and ad.action:
                for fc in ad.action.fcurves:
                    for kp in fc.keyframe_points:
                        kp.interpolation = "LINEAR"
        # exact loop: last sample is the first
        samples[-1] = samples[0]
        self.times, self.samples = times, samples
        return times, samples

    def checks(self):
        feet_err, knee_min, knee_max, elbow_max = 0.0, 1e9, -1e9, -1e9
        head_yaw = 0.0
        for t, s in zip(self.times, self.samples):
            w = mcrig.pose_matrices(s)
            for side, f in zip(("right", "left"), self.feet(t)):
                sole = mcrig.xform(w[side + "_shin"], (0, 6, 0))
                feet_err = max(feet_err, float(np.linalg.norm(sole - f)))
                k = s[side + "_shin"]["rot"][0]
                knee_min, knee_max = min(knee_min, k), max(knee_max, k)
                elbow_max = max(elbow_max, s[side + "_forearm"]["rot"][0])
            head_yaw = max(head_yaw, abs(s["head"]["rot"][1]))
        a, z = self.samples[0], self.samples[-1]
        return {"foot_slide_px_max": round(feet_err, 3), "knee_deg_range": [round(knee_min, 1),
                round(knee_max, 1)], "elbow_deg_max": round(elbow_max, 1),
                "head_yaw_local_max": round(head_yaw, 1)}


# --------------------------------------------------------------------------- export
EXPORT_BONES = mcrig.EXPORT_BONES


def _eval_many(kt, kv, ts):
    """Runtime catmull-rom (index-uniform, clamped neighbours), vectorised over ts."""
    n = len(kt)
    k = np.searchsorted(kt, ts, side="left")
    i = np.clip(k - 1, 0, n - 1)
    j = np.minimum(i + 1, n - 1)
    span = np.where(j > i, kt[j] - kt[i], 1.0)
    a = np.clip(np.where(j > i, (ts - kt[i]) / span, 0.0), 0.0, 1.0)[:, None]
    p0, p1, p2, p3 = kv[np.maximum(i - 1, 0)], kv[i], kv[j], kv[np.minimum(j + 1, n - 1)]
    return 0.5 * (2 * p1 + (p2 - p0) * a + (2 * p0 - 5 * p1 + 4 * p2 - p3) * a * a
                  + (3 * p1 - p0 - 3 * p2 + p3) * a * a * a)


def reduce_keys(times, vecs, tol):
    """Greedy worst-error key insertion, then a pruning pass. Returns (indices, max_err)."""
    ts = np.asarray(times)
    vs = np.asarray(vecs, float)
    idx = [0, len(ts) - 1]

    def err(ix):
        ix = sorted(ix)
        e = np.abs(_eval_many(ts[ix], vs[ix], ts) - vs).max(axis=1)
        return e
    while True:
        e = err(idx)
        m = int(np.argmax(e))
        if e[m] <= tol:
            break
        idx.append(m)
    idx = sorted(set(idx))
    changed = True
    while changed:
        changed = False
        for p in range(1, len(idx) - 1):
            trial = idx[:p] + idx[p + 1:]
            if err(trial).max() <= tol:
                idx = trial
                changed = True
                break
    return idx, float(err(idx).max())


def tkey(t):
    s = f"{t:.4f}".rstrip("0").rstrip(".")
    return s if "." in s else s + ".0"


def export(clip, name, meta, out_path):
    times, samples = clip.times, clip.samples
    bones, report = {}, {}
    for b in EXPORT_BONES:
        out = {}
        kinds = [("rotation", "rot", 0.2, (0, 0, 0)), ("position", "pos", 0.015, (0, 0, 0)),
                 ("scale", "scale", 0.0015, (1, 1, 1))]
        for kind, k, tol, rest in kinds:
            if kind == "position" and b in mcrig.ROTATION_ONLY:
                continue
            vecs = [list(s.get(b, {}).get(k, rest)) for s in samples]
            if all(abs(v - r) < tol * 0.5 for vec in vecs for v, r in zip(vec, rest)):
                continue
            idx, e = reduce_keys(times, vecs, tol)
            out[kind] = {tkey(times[i]): {"post": [round(v, 3 if kind != "scale" else 4)
                                                    for v in vecs[i]],
                                           "lerp_mode": "catmullrom"} for i in idx}
            report[f"{b}.{kind}"] = {"keys": len(idx), "max_err": round(e, 4)}
        if out:
            bones[b] = out
    anim = {"loop": True, "animation_length": clip.L, "bones": bones}
    if clip.s.get("hs_props"):
        # runtime display props (engine: hand mainhand|offhand, clip-local seconds)
        anim["hearthstead_props"] = [dict(p) for p in clip.s["hs_props"]]
    if clip.s.get("hs_sounds"):
        # client-side timeline cues, clip-local seconds (engine: MotionClip sound hook)
        anim["hearthstead_sounds"] = [dict(x) for x in clip.s["hs_sounds"]]
    doc = {"format_version": "1.8.0", "animations": {name: anim}, "hearthstead_meta": meta}
    # round trip: evaluate the written keys like the runtime and compare to the bake
    worst = 0.0
    ts = np.asarray(times)
    for b, chs in bones.items():
        for kind, keys in chs.items():
            kt = np.array([float(x) for x in keys])
            kv = np.array([v["post"] for v in keys.values()])
            o = np.argsort(kt)
            got = _eval_many(kt[o], kv[o], ts)
            k = {"rotation": "rot", "position": "pos", "scale": "scale"}[kind]
            rest = (1, 1, 1) if kind == "scale" else (0, 0, 0)
            ref = np.array([s.get(b, {}).get(k, rest) for s in samples])
            e = float(np.abs(got - ref).max())
            worst = max(worst, e * 100 if kind == "scale" else e)   # scale err in %
    doc["hearthstead_meta"]["roundtrip_max_err"] = round(worst, 4)
    doc["hearthstead_meta"]["keys"] = sum(r["keys"] for r in report.values())
    # QA 2026-09-26: head trails the chest (overlap), same pass as export_mc_clip.write
    import export_mc_clip
    export_mc_clip._overlap(doc, out_path)
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, indent=1)
        fh.write("\n")
    return doc, report, worst


# --------------------------------------------------------------------------- preview
def preview(clip, stem, review=False):
    """Fast Workbench preview into VIDEOS: side contact sheet (12 frames over one loop) and a
    short side MP4 (one loop, 30 fps, real time). ~0.2 s/frame; hsrig.preview's 2-loop 60 fps
    MP4 was ~5 min per clip here."""
    sc = bpy.context.scene
    tmp = os.path.join(WORK, "out", "idles", stem)
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    hsrig.setup_render(res=(400, 300))
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light, sh.color_type, sh.show_shadows = "STUDIO", "TEXTURE", True
    sc.display.render_aa = "FXAA"
    cams = hsrig.default_cameras()
    if clip.s.get("low_cam"):
        for cam in cams.values():
            cam.location.z -= 0.25
    n = int(round(clip.L * FPS))
    idx = [int(round(i * n / 12)) for i in range(12)]
    views = [("side", "_sheet.png")] + ([("front34", "_front34_sheet.png")] if review else [])
    for cam_name, suffix in views:
        d = os.path.join(tmp, "_" + cam_name)
        hsrig.render_frames(cams[cam_name], d, idx)
        hsrig._sheet([os.path.join(d, f"f{f:04d}.png") for f in idx],
                     os.path.join(VIDEOS, stem + suffix))
    sc.camera = cams["side"]
    sc.frame_start, sc.frame_end, sc.frame_step = 0, n - 1, 2
    sc.render.fps = FPS // 2
    sc.render.image_settings.file_format = "FFMPEG"
    sc.render.ffmpeg.format = "MPEG4"
    sc.render.ffmpeg.codec = "H264"
    sc.render.ffmpeg.constant_rate_factor = "HIGH"
    sc.render.filepath = os.path.join(VIDEOS, stem + "_side.mp4")
    bpy.ops.render.render(animation=True)
    sc.frame_step, sc.render.fps = 1, FPS
    sc.render.image_settings.file_format = "PNG"


def run(specs, args):
    """Author/export/preview every spec in this Blender process."""
    results = []
    only = [a for a in args.get("rest", []) if not a.endswith(".py")]
    for stem, spec in specs.items():
        if only and not any(stem == o or stem.startswith(o + "__") for o in only):
            continue
        clip = IdleClip(spec)
        clip.build()
        clip.bake()
        chk = clip.checks()
        name = "animation.settler." + stem
        meta = {"source": "tools/blender/pipeline/clips/idles/author_idles.py (Blender "
                          + bpy.app.version_string + ")",
                "note": spec.get("note", ""), "checks": chk}
        out = os.path.join(ANIM_DIR, stem + ".animation.json")
        if not args.get("export", True):
            out = os.path.join(WORK, "out", "idles", stem + ".animation.json")
            os.makedirs(os.path.dirname(out), exist_ok=True)
        doc, rep, worst = export(clip, name, meta, out)
        os.makedirs(os.path.join(WORK, "out", "idles"), exist_ok=True)
        bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, "out", "idles", stem + ".blend"))
        if args.get("fast") or args.get("review"):
            preview(clip, stem, review=args.get("review"))
        print("CLIP", stem, clip.L, json.dumps(chk), "keys", doc["hearthstead_meta"]["keys"],
              "roundtrip", round(worst, 4))
        results.append((stem, chk, worst))
    return results
