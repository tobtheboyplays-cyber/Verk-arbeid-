"""Group previews for the tavern lane (NO export): several rigs in one scene, driven the way the
RUNTIME drives them - the shipped JSON clips (runtime Catmull-Rom) plus a Python port of
TavernTableMath / SocialPair / TavernPatronMotion - so the owner judges the real interaction.

    blender -b --factory-startup --python author_group_previews.py -- [table4|table2|pair]

  table4  a 2-block table, one seat per block side (4 patrons). The cheer: one initiator
          raises, the others join 0.2-0.8 s later on their own speed; ~70% join (the rest keep
          sipping); mugs clink PAIRWISE across the table in staggered clinks at nearby points;
          three drink styles after. Then a story: one teller, the listeners react with their own
          lag and style. Relaxed seated angles, some lean back.
  table2  a 1-block table with 2 patrons, same rules.
  pair    two villagers 1.35 blocks apart, each turned 10-20 deg off the partner; turns of
          1.5-6 s with the odd cut-in; speak/listen cross-faded; own clip phase and speed;
          glances away, back-channel nods and head tilts, weight shifts.
Videos: videos/blender/clips/tavern/group_<name>_front34.mp4 (+ _side for the tables).
"""

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tavernkit as tk  # noqa: E402
import numpy as np  # noqa: E402
import bpy  # noqa: E402
from mathutils import Matrix  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import lifekit as lk  # noqa: E402
import idlekit  # noqa: E402

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
WHICH = [a for a in argv if a in ("table4", "table2", "pair")] or ["table4", "table2", "pair"]
FPS = hsrig.FPS
M64 = (1 << 64) - 1


# --------------------------------------------------------------------------- runtime clips
class Clip:
    """A shipped clip, densely pre-evaluated with the runtime Catmull-Rom."""
    cache = {}

    def __init__(self, name):
        path = os.path.join(lk.ANIM_DIR, name + ".animation.json")
        doc = json.load(open(path, encoding="utf-8"))
        anim = list(doc["animations"].values())[0]
        self.L = float(anim["animation_length"])
        self.loop = bool(anim.get("loop", False))
        n = int(round(self.L * 120)) + 1
        self.ts = np.linspace(0.0, self.L, n)
        self.ch = {}
        for bone, kinds in anim["bones"].items():
            for kind, keys in kinds.items():
                kt = np.array([float(k) for k in keys])
                kv = np.array([v["post"] for v in keys.values()], float)
                o = np.argsort(kt)
                self.ch[(bone, kind)] = idlekit._eval_many(kt[o], kv[o], self.ts)

    @classmethod
    def get(cls, name):
        if name not in cls.cache:
            cls.cache[name] = Clip(name)
        return cls.cache[name]

    def at(self, t, weight=1.0, out=None):
        t = t % self.L if self.loop else min(max(t, 0.0), self.L)
        f = t * 120.0
        i = min(int(f), len(self.ts) - 2)
        a = f - i
        out = {} if out is None else out
        for (bone, kind), arr in self.ch.items():
            v = arr[i] * (1 - a) + arr[i + 1] * a
            k = {"rotation": "rot", "position": "pos", "scale": "scale"}[kind]
            d = out.setdefault(bone, {})
            if k == "scale":
                d[k] = tuple(1 + (x - 1) * weight + (y - 1) for x, y in zip(v, d.get(k, (1, 1, 1))))
            else:
                d[k] = tuple(x * weight + y for x, y in zip(v, d.get(k, (0, 0, 0))))
        return out


# --------------------------------------------------------------------------- port of TavernTableMath
def hash01(seed, a, b):
    z = (seed + a * 0x9E3779B97F4A7C15 + b * 0xC2B2AE3D27D4EB4F) & M64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    z ^= z >> 31
    return (z >> 11) * 2.0 ** -53


CLINK_LEFT, CLINK_UP, CLINK_FORWARD = -0.6, 16.2, 12.0
CLINK, HOLD, CHEER_CLIP = 1.0, 0.8, 3.0
JOIN_SLOTS, LAG_SLOTS = [0.2, 0.4, 0.6, 0.8], [0.1, 0.25, 0.4, 0.55]
MUG_TOUCH = 1.3 / 16.0


def fwd(yaw):
    a = math.radians(yaw)
    return np.array([-math.sin(a), 0.0, math.cos(a)])


def left(yaw):
    a = math.radians(yaw)
    return np.array([math.cos(a), 0.0, math.sin(a)])


def slot(slots, seed, salt, index, count):
    n = min(len(slots), max(1, count))
    order = list(range(n))
    for i in range(n - 1, 0, -1):
        j = int(hash01(seed, salt, i) * (i + 1))
        order[i], order[j] = order[j], order[i]
    return slots[order[index % n]] + (hash01(seed, salt + 7, index) - 0.5) * 0.04


def plan_cheer(seed, cycle, count, yaws):
    init = cycle % count
    joined = [False] * count
    start, speed, contact = [0.0] * count, [1.0] * count, [0.0] * count
    group, late, style = [-1] * count, [False] * count, [0] * count
    joined[init] = True
    for i in range(count):
        speed[i] = 0.9 + 0.2 * hash01(seed, cycle * 37 + 1, i)
        style[i] = int(hash01(seed, cycle * 37 + 2, i) * 3)
        if i != init:
            joined[i] = hash01(seed, cycle * 37 + 3, i) < 0.7
    if sum(joined) == 1:
        joined[(init + 1) % count] = True
    order = []
    for i in range(count):
        if joined[i]:
            start[i] = 0.0 if i == init else slot(JOIN_SLOTS, seed, cycle * 37 + 4, i, count)
            order.append(i)
    order.sort(key=lambda i: start[i])
    natural = [start[i] + CLINK / speed[i] for i in range(count)]
    if len(order) > 2:
        best = max(range(1, len(order)), key=lambda k: abs(((yaws[order[k]] - yaws[order[0]] + 180) % 360) - 180))
        order.insert(1, order.pop(best))
        if len(order) == 4:
            rest = sorted(order[2:], key=lambda i: start[i])
            order[2:] = rest
    m0 = order[0]
    m1 = order[1] if len(order) > 1 else -1
    t0 = max(natural[m0], natural[m1]) if m1 >= 0 else natural[m0]
    group[m0], contact[m0] = 0, t0
    if m1 >= 0:
        group[m1], contact[m1] = 0, t0
    if len(order) == 3:
        m2 = order[2]
        group[m2], late[m2] = 0, True
        contact[m2] = max(natural[m2], t0 + 0.35 + 0.25 * hash01(seed, cycle * 37 + 5, m2))
    elif len(order) >= 4:
        m2, m3 = order[2], order[3]
        t1 = max(natural[m2], natural[m3], t0 + 0.3 + 0.4 * hash01(seed, cycle * 37 + 6, 0))
        group[m2] = group[m3] = 1
        contact[m2] = contact[m3] = t1
    return dict(init=init, joined=joined, start=start, speed=speed, group=group, late=late, contact=contact,
                style=style)


def cheer_local(p, i, t):
    s = p["speed"][i]
    u = (t - p["start"][i]) * s
    if u <= HOLD:
        return max(0.0, u)
    approach = p["contact"][i] - (CLINK - HOLD) / s
    if t < approach:
        return HOLD
    return min(CHEER_CLIP, HOLD + (t - approach) * s)


def mug_world(hip, yaw, cyaw=0.0, cpitch=0.0, lift=0.0):
    x, y, z = CLINK_LEFT, -CLINK_UP, -CLINK_FORWARD
    cp, sp = math.cos(cpitch), math.sin(cpitch)
    y1, z1 = cp * y - sp * z, sp * y + cp * z
    cy, sy = math.cos(cyaw), math.sin(cyaw)
    x2, z2 = cy * x + sy * z1, -sy * x + cy * z1
    return hip + left(yaw) * (x2 / 16) + np.array([0.0, (lift - y1) / 16, 0.0]) + fwd(yaw) * (-z2 / 16)


def heading(yaw, v):
    return math.atan2(-v.dot(left(yaw)), v.dot(fwd(yaw)))


def correction(hip, yaw, target):
    cyaw = cpitch = 0.0
    for _ in range(8):
        m = mug_world(hip, yaw, cyaw, cpitch) - hip
        d = target - hip
        cyaw += (heading(yaw, d) - heading(yaw, m) + math.pi) % (2 * math.pi) - math.pi
        cpitch += (math.hypot(d[0], d[2]) - math.hypot(m[0], m[2])) / (CLINK_UP / 16)
        cyaw = max(-0.9, min(0.9, cyaw))
        cpitch = max(-0.35, min(0.6, cpitch))
    lift = max(-3.0, min(3.0, (target[1] - mug_world(hip, yaw, cyaw, cpitch)[1]) * 16))
    return cyaw, cpitch, lift


def pair_point(seats, p, g, seed, cycle):
    pts = [mug_world(s["hip"], s["yaw"]) for i, s in enumerate(seats) if p["group"][i] == g and not p["late"][i]]
    if not pts:
        return None
    c = np.mean(pts, axis=0)
    r = (0.5 + 1.5 * hash01(seed, cycle * 41 + g, 2)) / 16 * (-1 if hash01(seed, cycle * 41 + g, 1) < 0.5 else 1)
    dy = (hash01(seed, cycle * 41 + g, 3) - 0.5) * 2 / 16
    px, pz = c[0], c[2]
    mem = [s for i, s in enumerate(seats) if p["group"][i] == g and not p["late"][i]]
    if len(mem) == 2:
        m = (mem[0]["hip"] + mem[1]["hip"]) / 2
        e = mem[1]["hip"] - mem[0]["hip"]
        el = math.hypot(e[0], e[2])
        if el > 1e-6:
            ax, az = -e[2] / el, e[0] / el
            along = (px - m[0]) * ax + (pz - m[2]) * az + r
            px, pz = m[0] + ax * along, m[2] + az * along
    hy = []
    for i, s in enumerate(seats):
        if p["group"][i] != g or p["late"][i]:
            continue
        d = s["hip"] - np.array([px, s["hip"][1], pz])
        d[1] = 0
        n = np.linalg.norm(d)
        d = d / n if n > 1e-6 else -fwd(s["yaw"])
        tgt = np.array([px, mug_world(s["hip"], s["yaw"])[1], pz]) + d * MUG_TOUCH
        cy_, cp_, _ = correction(s["hip"], s["yaw"], tgt)
        hy.append(mug_world(s["hip"], s["yaw"], cy_, cp_)[1])
    return np.array([px, float(np.mean(hy)) + dy, pz])


def mug_target(seats, p, i, seed, cycle):
    g = p["group"][i]
    if g < 0:
        return None
    pp = pair_point(seats, p, g, seed, cycle)
    if p["late"][i]:
        mine = mug_world(seats[i]["hip"], seats[i]["yaw"])
        d = pp - mine
        d[1] = 0
        n = np.linalg.norm(d)
        if n < 1e-6:
            return mine
        reach = mine + d * (min(n, 4 / 16) / n)
        cy_, cp_, _ = correction(seats[i]["hip"], seats[i]["yaw"], reach)
        reach[1] = mug_world(seats[i]["hip"], seats[i]["yaw"], cy_, cp_)[1]
        return reach
    d = seats[i]["hip"] - pp
    d[1] = 0
    n = np.linalg.norm(d)
    d = d / n if n > 1e-6 else -fwd(seats[i]["yaw"])
    return pp + d * MUG_TOUCH


def posture(eseed):
    a, b, c = hash01(eseed, 71, 1), hash01(eseed, 71, 2), hash01(eseed, 71, 3)
    yaw = math.radians((4 + 10 * a) * (-1 if b < 0.5 else 1))
    lean = math.radians(-(2 + 6 * hash01(eseed, 71, 4)) if c < 0.45 else 1.5 * c)
    return yaw, lean


def speed_of(eseed):
    return 0.9 + 0.2 * hash01(eseed, 73, 1)


def phase_of(eseed, L):
    return hash01(eseed, 73, 2) * L


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


EVENT_WINDOW, EVENT_CHANCE = 70.0, 0.85
EVENT_LEN = {"CHEER": 5.5, "STORY": 8.0, "TOAST": 8.0}
BEAT_WINDOW, SIP_CHANCE, FIDGET_CHANCE = 60.0, 0.33, 0.30
BEAT_LEN = {"SIP": 3.0, "FIDGET_CHIN": 3.0, "FIDGET_STRETCH": 3.4, "FIDGET_SHIFT": 2.4}
BEATS = ["CALM", "SIP", "FIDGET_CHIN", "FIDGET_STRETCH", "FIDGET_SHIFT"]


def table_event(seed, sec):
    off = hash01(seed, 5, 5) * EVENT_WINDOW
    k = math.floor((sec + off) / EVENT_WINDOW)
    if hash01(seed, k, 21) >= EVENT_CHANCE:
        return None
    roll = hash01(seed, k, 22)
    e = "CHEER" if roll < 0.35 else "STORY" if roll < 0.8 else "TOAST"
    start = k * EVENT_WINDOW - off + 10 + 20 * hash01(seed, k, 23)
    if sec < start or sec >= start + EVENT_LEN[e]:
        return None
    return e, start, EVENT_LEN[e], k


def personal(eseed, sec):
    off = hash01(eseed, 7, 7) * BEAT_WINDOW
    s_ = sec + off
    w = math.floor(s_ / BEAT_WINDOW)
    t = w * BEAT_WINDOW
    end = t + BEAT_WINDOW
    n = 0
    while t < end:
        calm = 3 + 7 * hash01(eseed, w * 64 + n, 31)
        if s_ < t + calm:
            return "CALM", t - off, calm
        t += calm
        r = hash01(eseed, w * 64 + n, 32)
        b = "SIP" if r < SIP_CHANCE else (BEATS[2 + int(hash01(eseed, w * 64 + n, 33) * 3)]
                                          if r < SIP_CHANCE + FIDGET_CHANCE else "CALM")
        if b != "CALM" and t + BEAT_LEN[b] <= end - 0.5:
            if s_ < t + BEAT_LEN[b]:
                return b, t - off, BEAT_LEN[b]
            t += BEAT_LEN[b]
        n += 1
    return "CALM", t - off, BEAT_WINDOW


def teller_of(k, count):
    return k % count


def toast_answer(seed, k, i, count):
    if hash01(seed, k * 977 + 5, i) >= 0.7:
        return -1.0
    return slot(JOIN_SLOTS, seed, k * 977 + 11, i, count)


def act_weight(local, length):
    return smooth(local / 0.45) * smooth((length - local) / 0.45)


# --------------------------------------------------------------------------- scene helpers
def to_model(world_xz):
    """World blocks -> the preview's model px (a 180-degree turn so MC yaw 0 faces model -z)."""
    return (-world_xz[0] * 16.0, 0.0, -world_xz[1] * 16.0)


def rig(texture, pos_px, yaw_deg):
    objs = hsrig.build_scene(os.path.join(lk.TEX_DIR, texture), None)
    space = objs["root"].parent
    r = mcrig.mat4(mcrig.ry(math.radians(-yaw_deg)), pos_px)   # rig yaw = -MC yaw
    space.matrix_basis = hsrig.MC_TO_BLENDER @ Matrix(r.tolist())
    return objs


def key_rig(objs, samples):
    for f, ch in enumerate(samples):
        for name, e in objs.items():
            if name not in mcrig.PARTS:
                continue
            pivot = mcrig.PARTS[name][1]
            cc = ch.get(name, {})
            rot, pos = cc.get("rot", (0, 0, 0)), cc.get("pos", (0, 0, 0))
            e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
            e.rotation_euler = tuple(math.radians(v) for v in rot)
            e.keyframe_insert("location", frame=f)
            e.keyframe_insert("rotation_euler", frame=f)
            if "scale" in cc:
                e.scale = cc["scale"]
                e.keyframe_insert("scale", frame=f)


def clear_default_rig():
    for ob in list(bpy.data.objects):
        if ob.name.startswith(("part:", "mesh:", "item:")):
            bpy.data.objects.remove(ob, do_unlink=True)


def add(ch, bone, kind, v):
    d = ch.setdefault(bone, {})
    d[kind] = tuple(a + b for a, b in zip(d.get(kind, (0, 0, 0)), v))


def render(name, frames, cams):
    sc = bpy.context.scene
    sc.frame_start, sc.frame_end = 0, frames - 1
    hsrig.setup_render(res=(800, 600))
    sc.render.engine = "BLENDER_WORKBENCH"
    sh = sc.display.shading
    sh.light, sh.color_type, sh.show_shadows = "STUDIO", "TEXTURE", True
    sc.display.render_aa = "8"
    for ob in bpy.data.objects:
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    out = []
    for view, cam in cams.items():
        path = os.path.join(tk.VIDEOS, f"group_{name}_{view}.mp4")
        hsrig._mp4(cam, path, list(range(0, frames)), FPS)
        out.append(path)
    print("GROUP_PREVIEW", out, flush=True)


# --------------------------------------------------------------------------- the table
CHEER_STYLES = ["table_cheer", "table_cheer_wipe", "table_cheer_quick"]
LISTEN_STYLES = ["seated_listen", "seated_listen_smile", "seated_listen_chuckle"]
TEXTURES = ["settler_farmer.png", "settler_courier.png", "settler_lumberer.png", "settler_innkeeper.png"]


def table(name, cells, seats_spec, cycle, wood_cells):
    """cells: table block (x, z) list; seats_spec: [(table cell index, 'N'|'S'|'E'|'W')]."""
    hsrig.reset()
    lk.scene("settler_farmer.png")
    clear_default_rig()
    wood, dark = (0.55, 0.38, 0.22, 1), (0.36, 0.24, 0.14, 1)
    for cx, cz in cells:
        mx, _, mz = to_model((cx + 0.5, cz + 0.5))
        lk.box(f"fence{cx}{cz}", (mx - 2, 8, mz - 2), (4, 16, 4), dark)
        lk.box(f"plate{cx}{cz}", (mx - 7, 7, mz - 7), (14, 1, 14), wood)
    table0 = min(cells)
    seed = (hash((table0[0], table0[1])) & 0xFFFFFFFF) * 0x9E3779B97F4A7C15 + 0x5DEECE66D
    seed &= M64
    face = {"N": (0, 1, 0.0), "S": (0, -1, 180.0), "E": (-1, 0, 90.0), "W": (1, 0, 270.0)}
    seats = []
    for k, (ci, side) in enumerate(seats_spec):
        cx, cz = cells[ci]
        dx, dz, yaw = {"N": (0, -1, 0.0), "S": (0, 1, 180.0), "W": (-1, 0, 270.0), "E": (1, 0, 90.0)}[side]
        chair = (cx + dx, cz + dz)
        f = fwd(yaw)
        hip = np.array([chair[0] + 0.5 + f[0] * 0.25, 0.5, chair[1] + 0.5 + f[2] * 0.25])
        seats.append({"hip": hip, "yaw": yaw, "cell": (cx, cz), "eseed": (k + 1) * 0x9E3779B97F4A7C15 & M64})
        # the stair chair (low step + back), drawn in world then mapped
        mx, _, mz = to_model((chair[0] + 0.5, chair[1] + 0.5))
        back = to_model((chair[0] + 0.5 - f[0] * 0.25, chair[1] + 0.5 - f[2] * 0.25))
        lk.box(f"step{k}", (mx - 8, 16, mz - 8), (16, 8, 16), wood)
        bw = (16, 8, 8) if abs(f[2]) > 0.5 else (8, 8, 16)
        lk.box(f"back{k}", (back[0] - bw[0] / 2, 8, back[2] - bw[2] / 2), bw, wood)
    count = len(seats)
    yaws = [s["yaw"] for s in seats]
    SPAN = 60.0
    # a 60 s stretch of an ordinary evening; start where one quiet window, a cheer and a story fall in it
    t0 = None
    for want in ({"CHEER", "STORY"}, {"CHEER"}):
        if t0 is not None:
            break
        for cand in range(0, 6000, 5):
            kinds = set()
            for tt in np.arange(cand, cand + SPAN, 0.5):
                ev = table_event(seed, tt)
                if ev and ev[1] >= cand + 6 and ev[1] + ev[2] <= cand + SPAN - 4:
                    kinds.add(ev[0])
            if want <= kinds:
                t0 = float(cand)
                break
    t0 = t0 or 0.0
    for cand in []:
        kinds = set()
        for tt in np.arange(cand, cand + SPAN, 0.5):
            ev = table_event(seed, tt)
            if ev and ev[1] >= cand + 6 and ev[1] + ev[2] <= cand + SPAN:
                kinds.add(ev[0])
        if {"CHEER", "STORY"} <= kinds:
            t0 = float(cand)
            break
    print("SPAN_START", t0, flush=True)
    total = int(round(SPAN * FPS))
    SEATED = {"SIP": "seated_sip", "FIDGET_CHIN": "seated_fidget_chin", "FIDGET_STRETCH": "seated_fidget_stretch",
              "FIDGET_SHIFT": "seated_fidget_shift"}
    log = {}
    for k, s in enumerate(seats):
        objs = rig(TEXTURES[k % 4], to_model((s["hip"][0], s["hip"][2])), s["yaw"])
        tk.attach_mug(objs, "left" if hash01(s["eseed"], 79, 1) < 0.3 else "right")
        lefty = hash01(s["eseed"], 79, 1) < 0.3
        pyaw, plean = posture(s["eseed"])
        spd, ph = speed_of(s["eseed"]), phase_of(s["eseed"], 12.0)
        others = np.mean([o["hip"] for j, o in enumerate(seats) if j != k], axis=0)
        samples = []
        acts = []
        for f in range(total):
            sec = t0 + f / FPS
            ch = {"root": {"pos": tk.SEAT_ROOT}}
            calm_t = sec * spd + ph
            ty, tp, tl, hy = pyaw, plean, 0.0, 0.0
            ev = table_event(seed, sec)
            joined = False
            if ev:
                e, st, ln, kk = ev
                if e == "STORY":
                    joined = True
                elif e == "CHEER":
                    p = plan_cheer(seed, kk, count, yaws)
                    joined = p["joined"][k]
                else:
                    joined = k == teller_of(kk, count) or toast_answer(seed, kk, k, count) >= 0
            if joined:
                e, st, ln, kk = ev
                el = sec - st
                w = act_weight(el, ln)
                Clip.get("seated_idle").at(calm_t, weight=1 - w, out=ch)
                if e == "CHEER":
                    local = cheer_local(p, k, el)
                    Clip.get(CHEER_STYLES[p["style"][k]]).at(local, weight=w, out=ch)
                    tgt = mug_target(seats, p, k, seed, kk)
                    cy_, cp_, l_ = correction(s["hip"], s["yaw"], tgt) if tgt is not None else (0, 0, 0)
                    r = smooth((local - 0.3) / 0.45) * (1 - smooth((local - 1.25) / 0.4))
                    ty, tp, tl = cy_ * r + pyaw * (1 - r), cp_ * r + plean * (1 - r), l_ * r
                    acts.append(("cheer", round(st - t0, 1)))
                elif e == "STORY":
                    tel = teller_of(kk, count)
                    if k == tel:
                        Clip.get("seated_story").at(el, weight=w, out=ch)
                        hy = 0.9 * max(-1.05, min(1.05, heading(s["yaw"], others - s["hip"]))) * w
                        ty = pyaw * 0.5
                    else:
                        lag = slot(LAG_SLOTS, seed, kk * 31 + 1, k, count)
                        st_ = int(hash01(seed, kk * 131, k * 17 + 3) * 3)
                        Clip.get(LISTEN_STYLES[st_]).at(max(0.0, el - lag), weight=w, out=ch)
                        hy = (0.7 if st_ == 2 else 1.0) * max(-1.05, min(1.05, heading(s["yaw"], seats[tel]["hip"] - s["hip"]))) * w
                    acts.append(("story", round(st - t0, 1)))
                else:
                    tel = teller_of(kk, count)
                    lag = 0.0 if k == tel else toast_answer(seed, kk, k, count)
                    Clip.get("seated_toast").at(max(0.0, el - lag), weight=w, out=ch)
                    acts.append(("toast", round(st - t0, 1)))
            else:
                b, st, ln = personal(s["eseed"], sec)
                if b == "CALM":
                    Clip.get("seated_idle").at(calm_t, out=ch)
                else:
                    el = sec - st
                    w = act_weight(el, ln)
                    Clip.get("seated_idle").at(calm_t, weight=1 - w, out=ch)
                    name_ = SEATED[b]
                    if b == "SIP" and lefty:
                        name_ = "seated_sip_left"
                    Clip.get(name_).at(el, weight=w, out=ch)
                    acts.append((b.lower(), round(st - t0, 1)))
            add(ch, "torso", "rot", (math.degrees(tp), math.degrees(ty), 0.0))
            add(ch, "torso", "pos", (0.0, tl, 0.0))
            add(ch, "head", "rot", (0.0, math.degrees(hy), 0.0))
            samples.append(tk.seated_legs(ch))
        key_rig(objs, samples)
        seen = []
        for a in acts:
            if a not in seen:
                seen.append(a)
        log[k] = seen
    print("TIMELINE", json.dumps(log), flush=True)
    c = to_model((np.mean([x for x, _ in cells]) + 0.5, np.mean([z for _, z in cells]) + 0.5))
    cb = (c[0] / 16, c[2] / 16)
    cams = {"front34": hsrig.camera(f"cam_{name}34", (cb[0] - 3.0, cb[1] - 3.2, 4.6), (cb[0], cb[1], 0.8), lens=40),
            "side": hsrig.camera(f"cam_{name}side", (cb[0] - 4.4, cb[1] - 0.8, 1.7), (cb[0], cb[1], 0.9), lens=34)}
    render(name, total, cams)


# --------------------------------------------------------------------------- the talking pair
TURN_MIN, TURN_MAX, OVERLAP = 30, 120, 5


def turn_length(seed, k):
    return TURN_MIN + int(hash01(seed, 11, k) * (TURN_MAX - TURN_MIN + 1))


def turn_start(seed, k):
    return sum(turn_length(seed, i) for i in range(k))


def role(slot_, el, seed):
    k, t = 0, 0
    while el >= t + turn_length(seed, k) and k < 64:
        t += turn_length(seed, k)
        k += 1
    mine = ((k + slot_) & 1) == 0
    if not mine and hash01(seed, 13, k) < 0.35 and turn_start(seed, k + 1) - el <= OVERLAP:
        return True
    return mine


def speak_weight(slot_, el, seed):
    return sum(1.0 for i in range(-3, 4) if role(slot_, max(0, math.floor(el + i * 2)), seed)) / 7.0


def pair_head(eseed, t, speaking):
    yaw = pitch = roll = 0.0
    s0 = int(math.floor(t / 1.6))
    for k in (s0 - 1, s0):
        if k < 0 or hash01(eseed, 91, k) >= (0.4 if speaking else 0.22):
            continue
        st = k * 1.6 + hash01(eseed, 92, k) * 0.8
        ln = 0.5 + 0.3 * hash01(eseed, 93, k)
        amp = math.radians(15 + 10 * hash01(eseed, 94, k)) * (-1 if hash01(eseed, 95, k) < 0.5 else 1)
        u = (t - st) / ln
        w = smooth(u * 4) * (1 - smooth((u - 0.75) * 4))
        yaw += amp * w
        pitch += math.radians(4) * w * (hash01(eseed, 96, k) - 0.3)
    if not speaking:
        b0 = int(math.floor(t / 1.2))
        for k in (b0 - 1, b0):
            if k < 0:
                continue
            kind = hash01(eseed, 97, k)
            st = k * 1.2 + hash01(eseed, 98, k) * 0.7
            if kind < 0.35:
                u = (t - st) / 0.35
                pitch += math.radians(7) * (math.sin(math.pi * u) if 0 < u < 1 else 0)
            elif kind < 0.55:
                u = (t - st) / 0.9
                roll += math.radians(6) * (math.sin(math.pi * u) if 0 < u < 1 else 0) * (-1 if hash01(eseed, 99, k) < 0.5 else 1)
    period = 3 + 2 * hash01(eseed, 100, 1)
    troll = math.radians(1.6) * math.sin(2 * math.pi * (t / period + hash01(eseed, 100, 2)))
    return yaw, pitch, roll, troll


def pair():
    hsrig.reset()
    lk.scene("settler_farmer.png")
    clear_default_rig()
    seed = 1234567 * 7919 + 99
    seed &= 0xFFFFFFFF
    dist = 1.35
    a_pos, b_pos = np.array([0.0, 0.0, -dist / 2]), np.array([0.0, 0.0, dist / 2])
    offs = [10 + 10 * hash01(seed, 17, 0), -(10 + 10 * hash01(seed, 17, 1))]
    people = [(a_pos, b_pos, 0, "settler_farmer.png", 0x1234567), (b_pos, a_pos, 1, "settler_courier.png", 0x7654321)]
    total = 12 * FPS
    for pos, other, slot_, tex, eseed in people:
        d = other - pos
        face = math.degrees(math.atan2(d[2], d[0])) - 90.0
        body = face + offs[slot_]
        objs = rig(tex, to_model((pos[0], pos[2])), body)
        spd, ph = speed_of(eseed), phase_of(eseed, 2.4)
        samples = []
        for f in range(total):
            t = f / FPS
            el = t * 20.0
            w = speak_weight(slot_, el, seed)
            ch = {}
            lt = t * spd + ph
            if w > 0.01:
                Clip.get("village_chat").at(lt, weight=w, out=ch)
            if w < 0.99:
                Clip.get("village_listen").at(lt, weight=1 - w, out=ch)
            # eyes on the partner (the body is turned off by `offs`), plus own glances and nods
            gy, gp, gr, troll = pair_head(eseed, t, role(slot_, el, seed))
            add(ch, "head", "rot", (math.degrees(gp), -offs[slot_] * 0.85 + math.degrees(gy), math.degrees(gr)))
            add(ch, "torso", "rot", (0.0, 0.0, math.degrees(troll)))
            samples.append(ch)
        key_rig(objs, samples)
    cams = {"front34": hsrig.camera("cam_p34", (-3.4, -0.4, 1.35), (0.0, 0.0, 0.95), lens=38)}
    render("pair", total, cams)


for w in WHICH:
    if w == "table4":
        table("table4", [(0, 0), (1, 0)], [(0, "N"), (1, "N"), (0, "S"), (1, "S")], cycle=0, wood_cells=None)
    elif w == "table2":
        table("table2", [(0, 0)], [(0, "N"), (0, "S")], cycle=6, wood_cells=None)
    else:
        pair()
