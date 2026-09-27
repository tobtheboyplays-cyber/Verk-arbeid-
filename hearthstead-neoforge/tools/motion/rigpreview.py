#!/usr/bin/env python3
"""Offline preview of settler motion clips, bend joints included.

Evaluates bedrock animation JSON (assets/hearthstead/animations/settler) or a
legacy Java clip (via tools/anim_check.py's parser) with the SAME rules as
com.hearthstead.client.motion.MotionClip (end keyframe owns the segment,
clamped catmull-rom, GeckoLib easings) and draws the settler rig -- with the
bendable elbows/knees rendered as three-ring meshes like BendableLimb -- as
flat-shaded orthographic contact sheets. It is an authoring aid, not
evidence: in-game footage is the proof.

usage:
  python tools/motion/rigpreview.py OUT.png CLIP [t0 t1 ...] [--legacy] [--views front,side,34]
  python tools/motion/rigpreview.py OUT.png mine_pick 0 0.3 0.45 0.6 --compare
     (--compare draws the legacy Java clip row above the authored JSON row)
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
NEOFORGE = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(NEOFORGE, "src/main/resources/assets/hearthstead/animations/settler")

# ------------------------------------------------------------------ math ---

def mat_mul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def mat_vec(m, v):
    return [m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]]


def rot_zyx(x, y, z):
    cx, sx = math.cos(x), math.sin(x)
    cy, sy = math.cos(y), math.sin(y)
    cz, sz = math.cos(z), math.sin(z)
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return mat_mul(rz, mat_mul(ry, rx))


def axis_angle(m):
    angle = math.acos(max(-1.0, min(1.0, (m[0][0] + m[1][1] + m[2][2] - 1) / 2)))
    if angle < 1e-6:
        return [1, 0, 0], 0.0
    s = 2 * math.sin(angle)
    axis = [(m[2][1] - m[1][2]) / s, (m[0][2] - m[2][0]) / s, (m[1][0] - m[0][1]) / s]
    n = math.sqrt(sum(a * a for a in axis)) or 1
    return [a / n for a in axis], angle


def rot_axis(axis, angle):
    x, y, z = axis
    c, s, t = math.cos(angle), math.sin(angle), 1 - math.cos(angle)
    return [[t * x * x + c, t * x * y - s * z, t * x * z + s * y],
            [t * x * y + s * z, t * y * y + c, t * y * z - s * x],
            [t * x * z - s * y, t * y * z + s * x, t * z * z + c]]


class Xf:
    """Affine transform: m (3x3 incl. scale) and translation t."""
    def __init__(self, m=None, t=None):
        self.m = m or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        self.t = t or [0, 0, 0]

    def then(self, other):  # self * other
        return Xf(mat_mul(self.m, other.m),
                  [a + b for a, b in zip(mat_vec(self.m, other.t), self.t)])

    def apply(self, p):
        v = mat_vec(self.m, p)
        return [v[0] + self.t[0], v[1] + self.t[1], v[2] + self.t[2]]


def part_xf(pose):
    x, y, z, rx, ry, rz, sx, sy, sz = pose
    r = rot_zyx(rx, ry, rz)
    m = [[r[i][j] * (sx, sy, sz)[j] for j in range(3)] for i in range(3)]
    return Xf(m, [x, y, z])

# ------------------------------------------------------------ easing ---

def ease(name, t, arg=None):
    t = max(0.0, min(1.0, t))
    n = (name or "linear").lower()
    if n == "linear": return t
    if n == "easeinsine": return 1 - math.cos(t * math.pi / 2)
    if n == "easeoutsine": return math.sin(t * math.pi / 2)
    if n == "easeinoutsine": return -(math.cos(math.pi * t) - 1) / 2
    if n == "easeinquad": return t * t
    if n == "easeoutquad": return 1 - (1 - t) ** 2
    if n == "easeinoutquad": return 2 * t * t if t < 0.5 else 1 - (-2 * t + 2) ** 2 / 2
    if n == "easeincubic": return t ** 3
    if n == "easeoutcubic": return 1 - (1 - t) ** 3
    if n == "easeinoutcubic": return 4 * t ** 3 if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2
    if n == "easeinquart": return t ** 4
    if n == "easeoutquart": return 1 - (1 - t) ** 4
    if n == "easeinoutquart": return 8 * t ** 4 if t < 0.5 else 1 - (-2 * t + 2) ** 4 / 2
    if n == "easeinexpo": return 0 if t == 0 else 2 ** (10 * t - 10)
    if n == "easeoutexpo": return 1 if t == 1 else 1 - 2 ** (-10 * t)
    if n == "easeinback":
        c1 = 1.70158 if arg is None else arg
        return (c1 + 1) * t ** 3 - c1 * t * t
    if n == "easeoutback":
        c1 = 1.70158 if arg is None else arg
        u = t - 1
        return 1 + (c1 + 1) * u ** 3 + c1 * u * u
    if n == "easeinoutback":
        c2 = (1.70158 if arg is None else arg) * 1.525
        return ((2 * t) ** 2 * ((c2 + 1) * 2 * t - c2)) / 2 if t < 0.5 else \
            ((2 * t - 2) ** 2 * ((c2 + 1) * (t * 2 - 2) + c2) + 2) / 2
    if n == "easeincirc": return 1 - math.sqrt(1 - t * t)
    if n == "easeoutcirc": return math.sqrt(1 - (t - 1) ** 2)
    return t


def catmull(d, p0, p1, p2, p3):
    return 0.5 * (2 * p1 + (p2 - p0) * d + (2 * p0 - 5 * p1 + 4 * p2 - p3) * d * d
                  + (3 * p1 - p0 - 3 * p2 + p3) * d ** 3)

# ------------------------------------------------------------- clips ---

class Track:
    def __init__(self, bone, target, frames):
        # frames: list of (t, pre[3], post[3], mode, easing, arg) in Java additive space
        self.bone, self.target, self.frames = bone, target, frames

    def sample(self, t):
        f = self.frames
        n = len(f)
        lo = 0
        while lo < n and not (t <= f[lo][0]):
            lo += 1
        i = max(0, lo - 1)
        j = min(n - 1, i + 1)
        d = 0.0 if i == j else max(0.0, min(1.0, (t - f[i][0]) / (f[j][0] - f[i][0])))
        mode = f[j][3]
        if mode == "catmullrom":
            p = max(0, i - 1)
            q = min(n - 1, j + 1)
            return [catmull(d, f[p][2][k], f[i][2][k], f[j][1][k], f[q][1][k]) for k in range(3)]
        if mode == "step":
            return list(f[i][2])
        e = ease(f[j][4], d, f[j][5]) if mode == "eased" else d
        return [f[i][2][k] + (f[j][1][k] - f[i][2][k]) * e for k in range(3)]


class Clip:
    def __init__(self, name, length, loop, tracks):
        self.name, self.length, self.loop, self.tracks = name, length, loop, tracks

    def wrap(self, t):
        return t % self.length if self.loop and self.length > 0 else t


def _vec(v, target):
    if isinstance(v, (int, float)):
        v = [v, v, v]
    v = [float(a) for a in v]
    if target == "rotation":
        return [math.radians(a) for a in v]
    if target == "position":
        return [v[0], -v[1], v[2]]
    return [v[0] - 1, v[1] - 1, v[2] - 1]


def load_json_clip(key):
    for fn in os.listdir(ASSETS):
        if not fn.endswith(".json"):
            continue
        data = json.load(open(os.path.join(ASSETS, fn), encoding="utf-8"))
        for name, anim in data.get("animations", {}).items():
            if name.lower().split(".")[-1] == key.lower():
                return parse_anim(name, anim)
    raise SystemExit(f"no authored clip {key}")


def parse_anim(name, anim):
    tracks = []
    last = 0.0
    for bone, chans in anim.get("bones", {}).items():
        for target in ("rotation", "position", "scale"):
            if target not in chans:
                continue
            raw = chans[target]
            if not isinstance(raw, dict) or any(k in raw for k in ("pre", "post", "vector")):
                raw = {"0": raw}
            frames = []
            for ts in sorted(raw, key=float):
                v = raw[ts]
                mode, easing, arg = "linear", None, None
                if isinstance(v, dict):
                    post = v.get("post", v.get("vector", v.get("pre")))
                    pre = v.get("pre", post)
                    lm = v.get("lerp_mode", "linear")
                    mode = "catmullrom" if lm == "catmullrom" else "step" if lm == "step" else "linear"
                    if "easing" in v:
                        if v["easing"] == "catmullrom":
                            mode = "catmullrom"
                        else:
                            mode, easing = "eased", v["easing"]
                            arg = (v.get("easingArgs") or [None])[0]
                else:
                    pre = post = v
                frames.append((float(ts), _vec(pre, target), _vec(post, target), mode, easing, arg))
                last = max(last, float(ts))
            tracks.append(Track(bone, target, frames))
    length = float(anim.get("animation_length", last or 0.05))
    loop = anim.get("loop") in (True, "true", "loop")
    return Clip(name, length, loop, tracks)


def load_legacy_clip(const):
    sys.path.insert(0, os.path.join(NEOFORGE, "tools"))
    import importlib.util
    spec = importlib.util.spec_from_file_location("anim_check", os.path.join(NEOFORGE, "tools", "anim_check.py"))
    ac = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ac)
    src = next(s for s in ac.ANIMATION_SOURCES if s["label"] == "settler")
    d = ac.parse_definitions(src["path"])[const.upper()]
    tracks = []
    for bone, target, frames in d["channels"]:
        tname = {"ROTATION": "rotation", "POSITION": "position", "SCALE": "scale"}[target]
        fr = []
        for (t, _k, (x, y, z), interp) in frames:
            v = _vec([x, y, z], tname)
            fr.append((t, v, v, "catmullrom" if interp == "CATMULLROM" else "linear", None, None))
        tracks.append(Track(bone, tname, fr))
    return Clip(const, d["length"], d["looping"], tracks)

# --------------------------------------------------------------- rig ---

REST = {  # name: (parent, offset)
    "root": (None, (0, 24, 0)),
    "torso": ("root", (0, -12, 0)),
    "head": ("torso", (0, -12, 0)),
    "cloak": ("torso", (0, -12, 0)),
    "right_arm": ("torso", (-6, -10, 0)),
    "left_arm": ("torso", (6, -10, 0)),
    "right_forearm": ("right_arm", (0, 4, 0)),
    "left_forearm": ("left_arm", (0, 4, 0)),
    "right_leg": ("root", (-2.6, -12, 0)),
    "left_leg": ("root", (2.6, -12, 0)),
    "right_shin": ("right_leg", (0, 6, 0)),
    "left_shin": ("left_leg", (0, 6, 0)),
}
COLORS = {"torso": (122, 92, 64), "head": (214, 176, 140), "cloak": (88, 66, 48),
          "right_arm": (160, 120, 84), "left_arm": (140, 104, 72),
          "right_leg": (78, 70, 90), "left_leg": (66, 60, 78), "tool": (190, 190, 200),
          "handle": (120, 86, 50), "nose": (200, 150, 120), "ground": (70, 110, 60)}
TOOLS = {
    # Tool frame: palm at arm-local (0, 9.5, -0.5), handle along local -Z, tipped 40 deg up.
    "axe": [((-0.5, -0.5, -13), (1, 1, 15), "handle"), ((-0.6, -1.5, -13.5), (1.2, 6, 3.5), "tool")],
    "pick": [((-0.5, -0.5, -13), (1, 1, 15), "handle"), ((-0.6, -5.5, -14), (1.2, 11, 1.6), "tool")],
    "hammer": [((-0.5, -0.5, -10), (1, 1, 12), "handle"), ((-1.6, -2.5, -12.5), (3.2, 5, 3.2), "tool")],
    "hoe": [((-0.5, -0.5, -15), (1, 1, 17), "handle"), ((-0.6, -0.5, -16), (1.2, 5, 1.8), "tool")],
    "sword": [((-0.5, -0.5, -1), (1, 1, 3), "handle"), ((-1.5, -0.7, -2), (3, 1.4, 1), "handle"),
              ((-0.6, -0.9, -15), (1.2, 1.8, 13), "tool")],
    "none": [],
}


def pose_from_clip(clips, t_by_clip):
    """Additive evaluation of one or more clips over the rest pose."""
    poses = {n: [off[0], off[1], off[2], 0, 0, 0, 1, 1, 1] for n, (_p, off) in REST.items()}
    for clip, weight in clips:
        t = clip.wrap(t_by_clip[clip.name])
        for tr in clip.tracks:
            if tr.bone not in poses:
                continue
            v = tr.sample(t)
            p = poses[tr.bone]
            base = {"position": 0, "rotation": 3, "scale": 6}[tr.target]
            for k in range(3):
                p[base + k] += v[k] * weight
    return poses


def world_xf(poses, name):
    chain = []
    n = name
    while n:
        chain.append(n)
        n = REST[n][0]
    xf = Xf()
    for n in reversed(chain):
        xf = xf.then(part_xf(poses[n]))
    return xf


def box_quads(x0, y0, z0, w, h, d):
    x1, y1, z1 = x0 + w, y0 + h, z0 + d
    c = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
         (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    faces = [(0, 1, 2, 3), (5, 4, 7, 6), (4, 0, 3, 7), (1, 5, 6, 2), (4, 5, 1, 0), (3, 2, 6, 7)]
    return [[c[i] for i in f] for f in faces]


def bent_limb_quads(y0, h, pivot, bend_pose):
    """Three-ring bent limb in limb-local space, like BendableLimb."""
    rx, ry, rz = bend_pose[3], bend_pose[4], bend_pose[5]
    full = rot_zyx(rx, ry, rz)
    axis, angle = axis_angle(full)
    half = rot_axis(axis, angle / 2)
    stretch, sdir = 1.0, None
    if angle > 1e-3:
        dx, dz = -axis[2], axis[0]
        ln = math.hypot(dx, dz)
        if ln > 0.1:
            sdir = (dx / ln, dz / ln)
            stretch = min(1.9, 1 / max(0.05, math.cos(angle / 2)))
            stretch = 1 + (stretch - 1) * min(1.0, ln)

    def ring(y, kind):
        pts = [(-2, y, -2), (2, y, -2), (2, y, 2), (-2, y, 2)]
        out = []
        for (x, yy, z) in pts:
            if kind == 0:
                out.append((x, yy, z))
                continue
            p = [x, yy - pivot, z]
            if kind == 1:
                if sdir:
                    along = p[0] * sdir[0] + p[2] * sdir[1]
                    p[0] += sdir[0] * along * (stretch - 1)
                    p[2] += sdir[1] * along * (stretch - 1)
                p = mat_vec(half, p)
            else:
                p = mat_vec(full, p)
            out.append((p[0], p[1] + pivot, p[2]))
        return out
    top, mid, bot = ring(y0, 0), ring(pivot, 1), ring(y0 + h, 2)
    quads = [top[::-1], bot]
    for a, b in ((top, mid), (mid, bot)):
        for i in range(4):
            j = (i + 1) % 4
            quads.append([a[i], a[j], b[j], b[i]])
    return quads


def build_scene(poses, tool="none"):
    tris = []  # (points_model_space[4], color_name)

    def add_box(part, box, color, local_xf=None):
        xf = world_xf(poses, part)
        if local_xf:
            xf = xf.then(local_xf)
        for q in box_quads(*box):
            tris.append(([xf.apply(p) for p in q], color))

    add_box("torso", (-5, -12, -2.5, 10, 12, 5), "torso")
    add_box("head", (-4, -8, -4, 8, 8, 8), "head")
    add_box("head", (-0.75, -2.5, -5.5, 1.5, 3, 1.5), "nose")
    add_box("cloak", (-5.7, -0.2, -3.2, 11.4, 4.4, 6.4), "cloak")
    for limb, bend, y0, pivot, scale in (("right_arm", "right_forearm", -2, 4, (1, 1, 1)),
                                         ("left_arm", "left_forearm", -2, 4, (1, 1, 1)),
                                         ("right_leg", "right_shin", 0, 6, (1.2, 1, 1.15)),
                                         ("left_leg", "left_shin", 0, 6, (1.2, 1, 1.15))):
        p = list(poses[limb])
        p[6] *= scale[0]; p[8] *= scale[2]
        saved = poses[limb]
        poses[limb] = p
        xf = world_xf(poses, limb)
        poses[limb] = saved
        for q in bent_limb_quads(y0, 12, pivot, poses[bend]):
            tris.append(([xf.apply(pt) for pt in q], limb))
        if limb == "right_arm" and tool != "none":
            full = rot_zyx(*poses[bend][3:6])
            lower = Xf(t=[0, pivot, 0]).then(Xf(full)).then(Xf(t=[0, -pivot, 0]))
            hand = xf.then(lower).then(Xf(t=[0, 9.5, -0.5])).then(Xf(rot_zyx(math.radians(-40), 0, 0)))
            for (o, s, c) in TOOLS[tool]:
                for q in box_quads(o[0], o[1], o[2], s[0], s[1], s[2]):
                    tris.append(([hand.apply(pt) for pt in q], c))
    return tris


VIEWS = {  # yaw of the camera around the settler (degrees); 0 = in front
    "front": 0, "34": 40, "side": 90, "back34": 150, "left": -90,
}


def render_frame(poses, view, size=220, tool="none", label=""):
    img = Image.new("RGB", (size, size), (34, 36, 42))
    dr = ImageDraw.Draw(img)
    yaw = math.radians(VIEWS[view])
    # model space -> entity local (renderer flip): X' = -x, Y' = -y + 24, Z' = z; forward is -Z'.
    light = (0.35, 0.8, -0.5)
    polys = []
    for pts, color in build_scene(poses, tool):
        loc = [(-p[0], 24 - p[1], p[2]) for p in pts]
        # camera in front (at -Z') rotated by yaw around Y
        cam = []
        for (x, y, z) in loc:
            cx = x * math.cos(yaw) + z * math.sin(yaw)
            cz = -x * math.sin(yaw) + z * math.cos(yaw)
            cam.append((cx, y, cz))
        # normal for shading
        a, b, c = cam[0], cam[1], cam[2]
        u = [b[i] - a[i] for i in range(3)]
        v = [c[i] - a[i] for i in range(3)]
        n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]
        ln = math.sqrt(sum(k * k for k in n)) or 1
        n = [k / ln for k in n]
        shade = 0.55 + 0.45 * abs(n[0] * light[0] + n[1] * light[1] + n[2] * light[2])
        depth = sum(p[2] for p in cam) / 4
        polys.append((depth, cam, color, shade))
    scale = size / 44.0
    ox, oy = size / 2, size - 8
    # ground line
    dr.line([(0, oy), (size, oy)], fill=COLORS["ground"], width=2)
    for depth, cam, color, shade in sorted(polys, key=lambda p: -p[0]):
        base = COLORS.get(color, (180, 180, 180))
        fill = tuple(int(ch * shade) for ch in base)
        dr.polygon([(ox - p[0] * scale, oy - p[1] * scale) for p in cam], fill=fill, outline=(20, 20, 20))
    if label:
        dr.text((4, 4), label, fill=(235, 235, 235))
    return img


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    out, key = args[0], args[1]
    times = [float(t) for t in args[2:]] or None
    views = ["front", "34", "side"]
    tool = "none"
    for f in flags:
        if f.startswith("--views="):
            views = f.split("=", 1)[1].split(",")
        if f.startswith("--tool="):
            tool = f.split("=", 1)[1]
    rows = []
    if "--legacy" in flags or "--compare" in flags:
        rows.append(("java", load_legacy_clip(key)))
    if "--legacy" not in flags:
        rows.append(("json", load_json_clip(key)))
    if times is None:
        length = rows[-1][1].length
        times = [round(length * i / 6, 3) for i in range(6)]
    size = 200
    sheet = Image.new("RGB", (size * len(times), size * len(views) * len(rows)), (20, 20, 20))
    for r, (label, clip) in enumerate(rows):
        for vi, view in enumerate(views):
            for ti, t in enumerate(times):
                poses = pose_from_clip([(clip, 1.0)], {clip.name: t})
                img = render_frame(poses, view, size, tool, f"{label} {view} t={t:g}")
                sheet.paste(img, (ti * size, (r * len(views) + vi) * size))
    sheet.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
