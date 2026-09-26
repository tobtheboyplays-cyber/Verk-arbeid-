"""Finisher clip kit: two-actor executions authored in ONE shared scene.

Owner brief (2026-09-26): "Finish him" executions that feel heavy and satisfying, real
time for every viewer, with a 3-tick hit-stop hold everyone sees. Runtime contracts:

  * PLAYER clips (executor, double partner, ready hold) play through the motion lane's
    PlayerClips hook on the VANILLA player model: six flat bones (head, body, right_arm,
    left_arm, right_leg, left_leg), ABSOLUTE from rest (absolute=true resets them), so every
    frame carries rotation + position (pivot delta, posVec y-up). Vanilla limbs are straight:
    an arm is AIMED from its shoulder at the authored hand target, a leg from its hip at the
    authored sole target; the body pivots at the neck but is placed so the torso bends at
    the hip. => animations/player/finisher_<id>.animation.json ("animation.player.finisher_<id>")
  * VICTIM clips play through MotionOverrides on RaiderModel / GoblinThiefModel: absolute
    full-body channels on the enemy rig (bend bones for elbows and knees).
    => animations/<rig>/finisher_victim_<id>.animation.json
  * HIT-STOP is baked: a move is authored in continuous MOVE time; the export samples it at
    move_time(real) which holds the impact frame for HIT_STOP_TICKS (FinisherTimeline).

Scene space = WORLD pixels, +Y down, ground y = 24, the executor stands at the origin facing
-Z. Each character's model space maps into the scene by `place()` (its renderer scale about
the ground, a yaw, a position). The victim of a solo move stands `d` blocks ahead facing the
executor. All timings are in seconds; ticks are 1/20 s.
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
ENEMIES = os.path.abspath(os.path.join(HERE, "..", "enemies"))
for _p in (PIPE, ENEMIES):
    if _p not in sys.path:
        sys.path.insert(0, _p)
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402
import enemyrig as er  # noqa: E402
from enemyrig import Curve  # noqa: E402,F401

DEG = math.pi / 180.0
FPS = 60
TICK = 1.0 / 20.0
HIT_STOP_TICKS = 3
PLAYER_SCALE = 0.9375
SKIRMISHER_SCALE = 0.82
REPO = os.path.abspath(os.path.join(PIPE, "..", "..", ".."))
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations")
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
REF = os.path.join(WORK, "ref_finisher", "assets", "minecraft", "textures")
VIDEOS = r"C:\Users\tobia\Hearthstead-Claude\videos\blender\clips\finisher"
RAIDER_TEX_DIR = er.TEX_DIR


def ticks(n):
    return n * TICK


def move_time(real_t, impact_t):
    """FinisherTimeline.clipTicks in seconds: hold the impact frame for the hit-stop."""
    hs = HIT_STOP_TICKS * TICK
    if real_t < impact_t:
        return real_t
    if real_t < impact_t + hs:
        return impact_t
    return real_t - hs


# =========================================================================== placement
def _ry(deg):
    return mcrig.mat4(mcrig.ry(deg * DEG))


def place(x=0.0, z=0.0, yaw=0.0, scale=1.0):
    """Model space -> scene: scale about the ground point, then yaw, then translate (px)."""
    about = mcrig.T(0, 24, 0) @ mcrig.mat4(s=scale) @ mcrig.T(0, -24, 0)
    return mcrig.T(x, 0, z) @ _ry(yaw) @ about


def blocks(b):
    return b * 16.0


# =========================================================================== player (flat)
PLAYER_REST = {"head": (0, 0, 0), "body": (0, 0, 0), "right_arm": (-5, 2, 0),
               "left_arm": (5, 2, 0), "right_leg": (-1.9, 12, 0), "left_leg": (1.9, 12, 0)}
PLAYER_BONES = ["head", "body", "right_arm", "left_arm", "right_leg", "left_leg"]
SHOULDER = {"right": (-5.0, -10.0, 0.0), "left": (5.0, -10.0, 0.0)}   # torso-local
HIP = {"right": (-1.9, -12.0, 0.0), "left": (1.9, -12.0, 0.0)}        # root-local
ARM_LEN = 10.0
LEG_LEN = 12.0
# Vanilla-ish rest: standing tall, arms down, feet under the hips.
PLAYER_BASE = {
    "root.pos": (0, 0, 0), "root": (0, 0, 0), "torso": (0, 0, 0), "head": (0, 0, 0),
    "rh": (-5.4, 12.0, -1.0), "lh": (5.4, 12.0, -1.0),
    "rf": (-2.2, 24.0, 0.6), "lf": (2.2, 24.0, -0.6),
    "rroll": (0, 0, 0), "lroll": (0, 0, 0),
}
# Combat ready: weight low, left foot leads, weapon hand up and forward.
PLAYER_READY = dict(PLAYER_BASE)
PLAYER_READY.update({
    "root": (0, 8, 0), "torso": (6, 4, 0), "head": (-4, -8, 0),
    "rh": (-5.0, 7.5, -7.5), "lh": (4.5, 9.5, -5.0),
    "rf": (-3.2, 24.0, 2.4), "lf": (2.8, 24.0, -3.2),
})


def _rot_between(a, b):
    """Rotation matrix taking unit a onto unit b (shortest arc)."""
    a = a / np.linalg.norm(a)
    b = b / np.linalg.norm(b)
    v = np.cross(a, b)
    c = float(a @ b)
    if c < -0.999999:
        axis = np.cross(a, [1.0, 0.0, 0.0])
        if np.linalg.norm(axis) < 1e-6:
            axis = np.cross(a, [0.0, 0.0, 1.0])
        axis /= np.linalg.norm(axis)
        return _axis_angle(axis, math.pi)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1.0 / (1.0 + c))


def _axis_angle(axis, ang):
    axis = np.asarray(axis, float) / np.linalg.norm(axis)
    x, y, z = axis
    c, s = math.cos(ang), math.sin(ang)
    C = 1 - c
    return np.array([[c + x * x * C, x * y * C - z * s, x * z * C + y * s],
                     [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
                     [z * x * C - y * s, z * y * C + x * s, c + z * z * C]])


def _R(rot_deg):
    return mcrig.rot_zyx(rot_deg[0] * DEG, rot_deg[1] * DEG, rot_deg[2] * DEG)


class PlayerSolver:
    """Channel dict (keyposes of PLAYER_* channels, targets in the ACTOR's model px) ->
    flat vanilla bones + world matrices (model px) for preview and checks."""

    def __init__(self, auto_height=True):
        self.prev = {}
        self.auto_height = auto_height

    def root_frame(self, p):
        pos = p["root.pos"]
        return mcrig.mat4(_R(p["root"]), (pos[0], 24.0 - pos[1], pos[2]))

    def __call__(self, p):
        root = self.root_frame(p)
        if self.auto_height:
            # the body sits on its straight legs: lift/lower the root so the mean leg is 12 px
            hips = [mcrig.xform(root, HIP[s]) for s in ("right", "left")]
            feet = [np.array(p["rf"], float), np.array(p["lf"], float)]
            need = []
            for hip, foot in zip(hips, feet):
                dxz = math.hypot(hip[0] - foot[0], hip[2] - foot[2])
                dxz = min(dxz, LEG_LEN - 0.05)
                need.append(foot[1] - math.sqrt(LEG_LEN ** 2 - dxz ** 2))
            dy = float(np.mean(need)) - float(np.mean([h[1] for h in hips]))
            root = mcrig.T(0, dy, 0) @ root
        torso = root @ mcrig.T(0, -12, 0) @ mcrig.mat4(_R(p["torso"]))
        head = torso @ mcrig.T(0, -12, 0) @ mcrig.mat4(_R(p["head"]))
        body = torso @ mcrig.T(0, -12, 0)
        world = {"root": root, "torso": torso, "head": head, "body": body}
        for side in ("right", "left"):
            sh = mcrig.xform(torso, SHOULDER[side])
            target = np.array(p["rh" if side == "right" else "lh"], float)
            world[side + "_arm"] = self._aim(torso[:3, :3], sh, target,
                                             p["rroll" if side == "right" else "lroll"][0])
            hip = mcrig.xform(root, HIP[side])
            foot = np.array(p["rf" if side == "right" else "lf"], float)
            world[side + "_leg"] = self._aim(root[:3, :3], hip, foot, 0.0)
        out = {}
        for bone in PLAYER_BONES:
            m = world[bone]
            rest = PLAYER_REST[bone]
            x, y, z = mcrig.euler_from_matrix(m[:3, :3])
            e = self._continuous(bone, [math.degrees(x), math.degrees(y), math.degrees(z)])
            piv = m[:3, 3]
            out[bone] = {"rot": tuple(e), "pos": (piv[0] - rest[0], -(piv[1] - rest[1]), piv[2] - rest[2])}
        return out, world

    @staticmethod
    def _aim(base, joint, target, roll_deg):
        d = np.asarray(target, float) - joint
        r = np.array(base, float)
        if np.linalg.norm(d) > 1e-4:
            axis = r @ np.array([0.0, 1.0, 0.0])
            r = _rot_between(axis, d) @ r
            if abs(roll_deg) > 1e-6:
                r = _axis_angle(d, roll_deg * DEG) @ r
        return mcrig.mat4(r, joint)

    def _continuous(self, bone, e):
        prev = self.prev.get(bone)
        a = list(e)
        b = [a[0] + 180.0, 180.0 - a[1], a[2] + 180.0]
        if prev is not None:
            def near(v):
                return [c + 360.0 * round((q - c) / 360.0) for q, c in zip(prev, v)]
            a, b = near(a), near(b)
            if sum((q - c) ** 2 for q, c in zip(prev, b)) < sum((q - c) ** 2 for q, c in zip(prev, a)):
                a = b
        self.prev[bone] = a
        return a


# held item (vanilla ItemInHandLayer + item/handheld thirdperson), arm frame = the flat arm
HANDHELD = ((0.0, -90.0, 55.0), (0.0, 4.0, 0.5), 0.85)
SWORD_TIP = (15.0, 15.0, 8.0)
SWORD_POMMEL = (1.5, 1.5, 8.0)
AXE_HEAD = (11.0, 13.0, 8.0)


def item_matrix(arm_world, right=True, display=HANDHELD):
    (rx_, ry_, rz_), (tx, ty, tz), s = display
    side = 1 if right else -1
    m = arm_world @ mcrig.mat4(mcrig.rx(-90 * DEG)) @ mcrig.mat4(mcrig.ry(180 * DEG)) @ mcrig.T(side * 1, 2, -10)
    r = mcrig.rx(rx_ * DEG) @ mcrig.ry(side * ry_ * DEG) @ mcrig.rz(side * rz_ * DEG)
    return m @ mcrig.T(side * tx, ty, tz) @ mcrig.mat4(r, s=s) @ mcrig.T(-8, -8, -8)


def weapon_point(arm_world, weapon):
    """Business end of the held weapon (model px); the fist for bare hands."""
    if weapon == "bare":
        return mcrig.xform(arm_world, (0, 10, 0))
    it = item_matrix(arm_world)
    return mcrig.xform(it, AXE_HEAD if weapon in ("axe", "mace") else SWORD_TIP)


# =========================================================================== victims
RAIDER_READY = {
    "root.pos": (0, -1.2, 0), "root": (0, 0, 0),
    "torso": (14, 0, 0), "torso.pos": (0, 0, 0),
    "head": (-8, 0, 0),
    "right_arm": (-14, -4, 8), "right_forearm": (-38, 0, 0),
    "left_arm": (-6, 4, -10), "left_forearm": (-28, 0, 0),
    "right_leg": (0, 0, 0), "left_leg": (0, 0, 0), "right_shin": (0, 0, 0), "left_shin": (0, 0, 0),
    "rf": (-3.2, 24.0, 1.4), "lf": (3.2, 24.0, -1.8), "legik": (1, 0, 0),
}
GOBLIN_READY = {
    "root.pos": (0, -1.6, 0), "root": (0, 0, 0),
    "torso": (20, 0, 0), "torso.pos": (0, 0, 0),
    "head": (-14, 0, 0),
    "right_arm": (-32, -6, 6), "right_forearm": (-58, 0, 0),
    "left_arm": (-28, 6, -6), "left_forearm": (-56, 0, 0),
    "right_leg": (0, 0, 0), "left_leg": (0, 0, 0), "right_shin": (0, 0, 0), "left_shin": (0, 0, 0),
    "rf": (-2.5, 24.0, 1.0), "lf": (2.5, 24.0, -1.3), "legik": (1, 0, 0),
}
VICTIM_BONES = ["root", "torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm",
                "right_leg", "left_leg", "right_shin", "left_shin"]


class VictimSolver:
    """keyposes of RAIDER_READY-style channels -> enemy-rig channels. `legik` (x of the vec)
    blends planted-foot IK legs (1) with the keyed FK legs (0), for kneels and falls."""

    def __init__(self, rig):
        self.rig = rig
        self.legs = er.LegIK()

    def __call__(self, p):
        ch = {}
        for c, v in p.items():
            if c in ("rf", "lf", "legik"):
                continue
            if c.endswith(".pos"):
                er.add(ch, c[:-4], pos=v)
            else:
                er.add(ch, c, rot=v)
        w = max(0.0, min(1.0, p.get("legik", (1, 0, 0))[0]))
        if w > 1e-3:
            fk = {b: ch.get(b, {}).get("rot", (0, 0, 0))
                  for b in ("right_leg", "left_leg", "right_shin", "left_shin")}
            ik = dict(ch)
            self.legs(ik, {"right": np.array(p["rf"], float), "left": np.array(p["lf"], float)})
            for b in fk:
                a = fk[b]
                bb = ik[b]["rot"]
                bb = [q + 360.0 * round((x - q) / 360.0) for x, q in zip(a, bb)]
                ch[b] = {"rot": tuple(x + (y - x) * w for x, y in zip(a, bb))}
        else:
            self.legs.prev = {}
        return ch


def victim_points(ch, place_m):
    """Useful victim body points in SCENE px: chest, belly, neck, head, pelvis, knees."""
    w = er.world(ch)
    tor = w["torso"]
    pts = {
        "chest": mcrig.xform(tor, (0, -8, -2)),
        "belly": mcrig.xform(tor, (0, -3, -2)),
        "neck": mcrig.xform(tor, (0, -12, 0)),
        "head": mcrig.xform(w["head"], (0, -4, -4)),
        "pelvis": mcrig.xform(w["root"], (0, -12, 0)),
        "rknee": mcrig.xform(w["right_leg"], (0, 6, 0)),
        "lknee": mcrig.xform(w["left_leg"], (0, 6, 0)),
        "rhand": mcrig.xform(w["right_forearm"], (0, 6, 0)),
    }
    return {k: mcrig.xform(place_m, v) for k, v in pts.items()}


ALL_BODY = ("torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm",
            "right_leg", "left_leg", "right_shin", "left_shin")


def ground_clamp(ch, place_m, scale, tolerance=0.2):
    """Lift the root so no body box sinks below the ground (a lying body RESTS on it)."""
    low = lowest_point(ch, place_m, ALL_BODY)
    if low > 24.0 + tolerance:
        er.add(ch, "root", pos=(0.0, (low - 24.0) / scale, 0.0))
    return ch


def lowest_point(ch, place_m, bones=("torso", "head", "right_arm", "left_arm", "right_leg", "left_leg")):
    """Largest scene y (deepest) over a few body box corners: ground penetration check."""
    w = er.world(ch)
    deepest = -1e9
    for b in bones:
        cubes = mcrig.PARTS[b][2]
        for (fx, fy, fz), (sx, sy, sz), *_ in cubes:
            for cx in (fx, fx + sx):
                for cy in (fy, fy + sy):
                    for cz in (fz, fz + sz):
                        deepest = max(deepest, mcrig.xform(place_m @ w[b], (cx, cy, cz))[1])
    return deepest


# =========================================================================== keyposes
def _vec(v):
    return (float(v), 0.0, 0.0) if np.isscalar(v) else tuple(float(x) for x in v)


def keyposes(keys, base):
    """keys: [(t, {channel: value}, mode)]; unkeyed channels hold their last value.
    mode shapes the segment ARRIVING at the key (enemyrig.Curve)."""
    chans = set(base)
    for _, d, *_ in keys:
        chans |= set(d)
    cur = {c: _vec(base.get(c, (0, 0, 0))) for c in chans}
    rows = {c: [] for c in chans}
    for t, d, *m in keys:
        for c, v in d.items():
            cur[c] = _vec(v)
        for c in chans:
            rows[c].append((t, cur[c], m[0] if m else "auto"))
    curves = {c: Curve(r) for c, r in rows.items()}
    return lambda t: {c: cv(t) for c, cv in curves.items()}


def shake(t, t0, amp, dur=0.18, freq=38.0):
    """Decaying impact tremor (0 before t0)."""
    if t < t0:
        return 0.0
    u = (t - t0) / dur
    if u > 1.0:
        return 0.0
    return amp * (1 - u) ** 2 * math.sin(freq * (t - t0))


# =========================================================================== export
def _channels(times, samples, bones, rot_only=()):
    out = {}
    for b in bones:
        out[b] = {"rotation": [list(s.get(b, {}).get("rot", (0, 0, 0))) for s in samples]}
        if b not in rot_only:
            out[b]["position"] = [list(s.get(b, {}).get("pos", (0, 0, 0))) for s in samples]
    return out


def export(rig, key, length, times, samples, bones, rot_only, keep, meta, write=True):
    anim = f"animation.{rig}.{key}"
    chan = _channels(times, samples, bones, rot_only)
    doc, report = ex.build_bedrock(anim, length, False, times, chan, rot_tol=0.2, pos_tol=0.02,
                                   keep_times=tuple(sorted(set(keep) | {0.0, length})), meta=meta)
    worst = 0.0
    bd = doc["animations"][anim]["bones"]
    for b, kinds in chan.items():
        for kind, vecs in kinds.items():
            if b not in bd or kind not in bd[b]:
                worst = max(worst, max(abs(v) for vec in vecs for v in vec))
                continue
            for t, v in zip(times, vecs):
                got = ex.sample(doc, anim, b, kind, t)
                worst = max(worst, max(abs(a - q) for a, q in zip(got, v)))
    doc.setdefault("hearthstead_meta", {})["roundtrip_max_err"] = round(worst, 4)
    path = os.path.join(ANIM_DIR, rig, key + ".animation.json")
    if write:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        ex.write(doc, path)
    out = os.path.join(WORK, "out", "finisher")
    os.makedirs(out, exist_ok=True)
    ex.write(doc, os.path.join(out, f"{rig}__{key}.animation.json"))
    nkeys = sum(v["keys"] for v in report.values())
    print("EXPORTED", f"{rig}/{key}", nkeys, "keys, roundtrip", round(worst, 4), "->",
          path if write else "(no write)")
    return worst, nkeys


# =========================================================================== run
def run(clip, fast=True, write=True):
    """clip: dict(
         id, length_ticks, impact_tick, weapon, victim_rig ('raider'|'goblin'), victim_variant,
         d (blocks, solo actor distance),
         actors: [dict(role='lead'|'partner'|'solo', place=(x, z, yaw) blocks/deg, solve=fn(t, V))],
         victim: fn(t) -> channel dict (victim keyposes),
         victim_place: optional (x, z, yaw) blocks/deg (default: d blocks ahead facing the actor),
         keep_ticks: extra key ticks, contract: str)
       Actor solve(t, V) gets V = victim scene points at move time t, returns PLAYER_* keyposes
       with hand/foot targets in SCENE px; they are converted to the actor's model space here."""
    L_move = ticks(clip["length_ticks"])
    impact = ticks(clip["impact_tick"])
    L = L_move + ticks(HIT_STOP_TICKS)
    variant = clip.get("victim_variant", er.SKIRM)
    er.use(clip["victim_rig"], variant)
    vscale = 1.0
    if variant == er.SKIRM:
        vscale = SKIRMISHER_SCALE
    vscale = clip.get("victim_scale", vscale)
    vx, vz, vyaw = clip.get("victim_place", (0.0, -clip["d"], 180.0))
    VP = place(blocks(vx), blocks(vz), vyaw, vscale)
    vsolve = VictimSolver(clip["victim_rig"])
    actors = []
    for a in clip["actors"]:
        ax, az, ayaw = a.get("place", (0.0, 0.0, 0.0))
        AP = place(blocks(ax), blocks(az), ayaw, PLAYER_SCALE)
        actors.append(dict(a, AP=AP, AP_inv=np.linalg.inv(AP), solver=PlayerSolver(), samples=[],
                           worlds=[]))
    n = int(round(L * FPS))
    L_v = L + ticks(clip.get("victim_tail_ticks", 10))
    n_v = int(round(L_v * FPS))
    times, vtimes, vsamples = [], [], []
    checks = {"victim_scale": vscale}
    for f in range(n_v + 1):
        real = f / FPS
        t = move_time(real, impact)
        vch = ground_clamp(vsolve(clip["victim"](t)), VP, vscale)
        V = victim_points(vch, VP)
        vtimes.append(real)
        vsamples.append(vch)
        if f > n:
            continue
        times.append(real)
        for a in actors:
            p = a["solve"](t, V)
            p = dict(p)
            for key in ("rh", "lh", "rf", "lf"):
                if key in p:
                    p[key] = tuple(mcrig.xform(a["AP_inv"], np.array(p[key], float)))
            flat, world = a["solver"](p)
            drop = float(world["root"][1, 3] - 24.0)
            key = a.get("role", "solo") + "_root_drop_max_px"
            checks[key] = round(max(checks.get(key, -99.0), drop), 2)
            a["samples"].append(flat)
            a["worlds"].append(world)
            if abs(real - impact) < 0.5 / FPS and a.get("strikes", True):
                if clip.get("contact_hand") == "left":
                    hit = mcrig.xform(world["left_arm"], (0, 10, 0))
                else:
                    hit = weapon_point(world["right_arm"], a.get("weapon", clip.get("weapon", "sword")))
                hit_scene = mcrig.xform(a["AP"], hit)
                target = V[clip.get("contact", "chest")]
                checks[a.get("role", "solo") + "_contact_px"] = round(float(np.linalg.norm(hit_scene - target)), 2)
    # ground penetration of the lying victim, last frame
    checks["victim_lowest_y_last"] = round(float(lowest_point(vsamples[-1], VP, ALL_BODY)), 2)
    checks["victim_lowest_y_max"] = round(float(max(lowest_point(s, VP, ALL_BODY) for s in vsamples[::6])), 2)
    checks["victim_root_lift_last_px"] = round(float(vsamples[-1]["root"]["pos"][1]), 2)
    keep = sorted({impact, impact + ticks(HIT_STOP_TICKS)} |
                  {ticks(k) for k in clip.get("keep_ticks", ())})
    meta = {"source": "tools/blender/pipeline/clips/finisher (finisher lane, 2026-09-26)",
            "contract": clip.get("contract", ""), "impact_tick": clip["impact_tick"],
            "hit_stop_ticks": HIT_STOP_TICKS, "move_ticks": clip["length_ticks"], "checks": checks}
    results = {"id": clip["id"], "checks": checks}
    vkey = "finisher_victim_" + clip["id"]
    err, k = export(clip["victim_rig"], vkey, L_v, vtimes, vsamples, VICTIM_BONES,
                    er.ROT_ONLY, keep, meta, write)
    results["victim"] = {"key": f"{clip['victim_rig']}/{vkey}", "roundtrip": round(err, 4), "keys": k}
    for a in actors:
        key = a.get("key") or ("finisher_" + clip["id"] + ("" if a.get("role", "solo") == "solo"
                                                         else "_" + a["role"]))
        err, k = export("player", key, L, times, a["samples"], PLAYER_BONES, (), keep, meta, write)
        results[a.get("role", "solo")] = {"key": "player/" + key, "roundtrip": round(err, 4), "keys": k}
    print("CHECKS", json.dumps(results))
    if fast:
        preview(clip, L_v, vtimes, VP, vsamples, actors)
    return results


# =========================================================================== preview (bpy)
PLAYER_CUBES = {
    "head": [((-4, -8, -4), (8, 8, 8), (0, 0), False, 0.0)],
    "body": [((-4, 0, -2), (8, 12, 4), (16, 16), False, 0.0)],
    "right_arm": [((-3, -2, -2), (4, 12, 4), (40, 16), False, 0.0)],
    "left_arm": [((-1, -2, -2), (4, 12, 4), (32, 48), False, 0.0)],
    "right_leg": [((-2, 0, -2), (4, 12, 4), (0, 16), False, 0.0)],
    "left_leg": [((-2, 0, -2), (4, 12, 4), (16, 48), False, 0.0)],
}


def _space(name, scene_from_model):
    import hsrig
    from mathutils import Matrix
    e = hsrig._empty(name)
    e.matrix_basis = hsrig.MC_TO_BLENDER @ Matrix(scene_from_model.tolist())
    return e


def _key_matrix(obj, m, f):
    from mathutils import Matrix
    obj.matrix_basis = Matrix(m.tolist())
    obj.keyframe_insert("location", frame=f)
    obj.keyframe_insert("rotation_euler", frame=f)
    obj.keyframe_insert("scale", frame=f)


def preview(clip, L, times, VP, vsamples, actors):
    import bpy
    import hsrig
    hsrig.reset()
    sc = bpy.context.scene
    # victim (enemy rig) under its own placed space
    variant = clip.get("victim_variant", er.SKIRM)
    tex = os.path.join(RAIDER_TEX_DIR, er.RAIDER_TEX[variant] if clip["victim_rig"] == "raider"
                       else "goblin_thief.png")
    vobjs = er.build_scene(tex)
    vspace = [o for o in bpy.data.objects if o.name.startswith("MC_SPACE")][-1]
    from mathutils import Matrix
    vspace.matrix_basis = hsrig.MC_TO_BLENDER @ Matrix(VP.tolist())
    for o in vobjs.values():
        o.rotation_mode = "XYZ"
    ground = hsrig._empty("GROUND_SPACE")
    ground.matrix_basis = hsrig.MC_TO_BLENDER
    hsrig.prop_box("ground", (-70, 24, -90), (140, 1, 150), (0.30, 0.42, 0.22, 1), parent_name="GROUND_SPACE")
    # players
    skin = os.path.join(REF, "entity", "player", "wide", "steve.png")
    weapon_png = {"sword": "iron_sword.png", "axe": "iron_axe.png", "mace": "mace.png"}.get(
        clip.get("weapon", "sword"))
    for i, a in enumerate(actors):
        space = _space(f"PLAYER_SPACE_{i}", a["AP"])
        mat = hsrig._material(f"steve{i}", skin)
        parts = {}
        for bone, cubes in PLAYER_CUBES.items():
            e = hsrig._empty(f"pl{i}:{bone}", space)
            e.rotation_mode = "XYZ"
            me = hsrig.cuboid_mesh(f"plmesh{i}:{bone}", cubes, 64, 64)
            me.materials.append(mat)
            mo = bpy.data.objects.new(f"plmesh{i}:{bone}", me)
            sc.collection.objects.link(mo)
            mo.parent = e
            parts[bone] = e
        item = None
        w = a.get("weapon", clip.get("weapon", "sword"))
        if w != "bare" and weapon_png:
            item = hsrig._empty(f"pl{i}:item", space)
            item.rotation_mode = "XYZ"
            hsrig.sprite_mesh(f"plitem{i}", os.path.join(REF, "item", weapon_png), item)
        a["parts"], a["item"] = parts, item
    sc.frame_start, sc.frame_end = 0, len(times) - 1
    for f in range(len(times)):
        ch = vsamples[f]
        for name, e in vobjs.items():
            if name not in mcrig.PARTS:
                continue
            pivot = mcrig.PARTS[name][1]
            c = ch.get(name, {})
            rot = c.get("rot", (0, 0, 0))
            pos = c.get("pos", (0, 0, 0))
            e.location = (pivot[0] + pos[0], pivot[1] - pos[1], pivot[2] + pos[2])
            e.rotation_euler = tuple(math.radians(r) for r in rot)
            e.keyframe_insert("location", frame=f)
            e.keyframe_insert("rotation_euler", frame=f)
        for a in actors:
            world = a["worlds"][min(f, len(a["worlds"]) - 1)]
            for bone, e in a["parts"].items():
                _key_matrix(e, world[bone], f)
            if a["item"] is not None:
                _key_matrix(a["item"], item_matrix(world["right_arm"]), f)
    for ob in bpy.data.objects:
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    out = os.path.join(WORK, "out", "finisher", clip["id"])
    os.makedirs(out, exist_ok=True)
    sc.render.engine = "BLENDER_WORKBENCH"
    sc.render.resolution_x, sc.render.resolution_y = 480, 360
    sh = sc.display.shading
    sh.light = "STUDIO"
    sh.color_type = "TEXTURE"
    sh.show_shadows = False
    sc.display.render_aa = "FXAA"
    sc.view_settings.view_transform = "Standard"
    world_bg = bpy.data.worlds.new("bg")
    world_bg.color = (0.52, 0.62, 0.74)
    sc.world = world_bg
    mid = clip.get("cam_mid", -clip.get("d", 1.4) / 2.0)
    cams = {
        "side": hsrig.camera("cam_side", (-4.6, mid, 1.0), (0.0, mid, 0.75), lens=38),
        "front34": hsrig.camera("cam_f34", (-3.2, mid - 3.4, 1.6), (0.0, mid, 0.7), lens=38),
    }
    n = len(times) - 1
    impact_f = int(round(ticks(clip["impact_tick"]) * FPS))
    idx = sorted(set([int(round(i * n / 11)) for i in range(12)] + [impact_f]))[:12]
    for cname, cam in cams.items():
        tmp = os.path.join(out, "_frames_" + cname)
        if os.path.isdir(tmp):
            shutil.rmtree(tmp, ignore_errors=True)
        hsrig.render_frames(cam, tmp, idx)
        hsrig._sheet([os.path.join(tmp, f"f{f:04d}.png") for f in idx],
                     os.path.join(out, f"sheet_{cname}.png"), cols=6)
    hsrig._mp4(cams["side"], os.path.join(out, "side.mp4"), list(range(0, n + 1)), FPS)
    os.makedirs(VIDEOS, exist_ok=True)
    for name in ("sheet_side.png", "sheet_front34.png", "side.mp4"):
        src = os.path.join(out, name)
        if os.path.exists(src):
            shutil.copyfile(src, os.path.join(VIDEOS, f"{clip['id']}_{name}"))
    print("PREVIEW", out)


def export_ready(key, kp, length, write=True):
    """A looping player hold (the co-op READY while waiting for a partner)."""
    solver = PlayerSolver()
    n = int(round(length * FPS))
    times, samples = [], []
    for f in range(n + 1):
        t = f / FPS
        times.append(t)
        samples.append(solver(kp(t))[0])
    anim = f"animation.player.{key}"
    chan = _channels(times, samples, PLAYER_BONES)
    doc, report = ex.build_bedrock(anim, length, True, times, chan, rot_tol=0.2, pos_tol=0.02,
                                   keep_times=(0.0, length), meta={"source": "finisher lane",
                                   "contract": "co-op READY hold, loops"})
    path = os.path.join(ANIM_DIR, "player", key + ".animation.json")
    if write:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        ex.write(doc, path)
    print("EXPORTED", "player/" + key, sum(v["keys"] for v in report.values()), "keys")
    return {"id": key}
