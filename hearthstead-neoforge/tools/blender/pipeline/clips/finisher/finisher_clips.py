"""Finisher choreography: executor (vanilla player) + victim (raider / goblin), one scene.

Timing contract = com.hearthstead.finisher.FinisherVariant (move ticks, impact tick, actor
distance). The exported clips are move + 3 hit-stop ticks long (actor) and + a tail for the
victim's fall (the server removes the body 20 ticks after the kill).

Sign conventions (mcrig / enemyrig): model px, +Y down, a body faces -Z, +X is its LEFT.
Torso/root X positive = pitch FORWARD. Root/torso Y positive turns the front toward the
body's RIGHT (-X) (the left shoulder comes forward). Arm X negative swings forward.
Elbow flex negative, knee flex positive. posVec y is UP; posVec z positive = BACKWARD.

Scene: the executor stands at the origin facing -Z; a solo victim stands d blocks ahead
facing back (+Z). Actor hand/foot targets are SCENE px (use actor_scene() for points in the
actor's own model space); every other channel is model space.
Feel rules (owner): strong anticipation, a snappy strike, a hard stop held for the
hit-stop, a satisfying reaction and fall. No gore anywhere.
"""

from __future__ import annotations

import math

import numpy as np

import finkit as fk
from finkit import keyposes, ticks, blocks
import enemyrig as er
import mcrig

S = fk.PLAYER_SCALE
tk = ticks


# =========================================================================== helpers
def scene_from_actor(p_model, AP=None):
    AP = fk.place(0, 0, 0, S) if AP is None else AP
    return tuple(mcrig.xform(AP, p_model))


def actor_scene(model_pt, AP=None):
    return scene_from_actor(model_pt, AP)


def victim_probe(rig, variant, kp, place_m, scale):
    def at(t):
        er.use(rig, variant)
        ch = fk.ground_clamp(fk.VictimSolver(rig)(kp(t)), place_m, scale)
        return fk.victim_points(ch, place_m)
    return at


def vscale_of(rig, variant):
    return fk.SKIRMISHER_SCALE if variant == er.SKIRM else 1.0


def victim_place(d, variant, rig="raider", x=0.0, yaw=180.0):
    return fk.place(blocks(x), blocks(-d), yaw, vscale_of(rig, variant))


def solve_strike(p, target_scene, weapon, AP=None, roll_pref=0.0, dir_pref=None, w_dir=0.0,
                 reach=9.0):
    """Right-hand target (scene px) + roll putting the weapon's business end on target."""
    AP = fk.place(0, 0, 0, S) if AP is None else AP
    inv = np.linalg.inv(AP)
    target = mcrig.xform(inv, np.asarray(target_scene, float))
    q = dict(p)
    for k in ("rh", "lh", "rf", "lf"):
        q[k] = tuple(mcrig.xform(inv, np.asarray(q[k], float)))
    def direction(az, el):
        return np.array([math.cos(el) * math.sin(az), math.sin(el), -math.cos(el) * math.cos(az)])

    def pose(x):
        dvec = direction(x[0], x[1])
        q2 = dict(q)
        dz = max(-reach, min(reach, x[3]))
        rp = q["root.pos"]
        q2["root.pos"] = (rp[0], rp[1], rp[2] + dz)
        # the lead foot travels with the lunge so the stance stays under the body
        q2["lf"] = (q["lf"][0], q["lf"][1], q["lf"][2] + dz)
        q2["rf"] = (q["rf"][0], q["rf"][1], q["rf"][2] + dz)
        world = fk.PlayerSolver()(q2)[1]
        shoulder = mcrig.xform(world["torso"], fk.SHOULDER["right"])
        q2["rh"] = tuple(shoulder + fk.ARM_LEN * dvec)
        q2["rroll"] = (x[2], 0, 0)
        return q2, dvec

    def tip_of(q2):
        return fk.weapon_point(fk.PlayerSolver()(q2)[1]["right_arm"], weapon)

    def cost(x):
        q2, dvec = pose(x)
        c = float(np.sum((tip_of(q2) - target) ** 2)) + 0.0004 * (x[2] - roll_pref) ** 2             + 0.05 * x[3] ** 2 + (10.0 * (abs(x[3]) - reach) ** 2 if abs(x[3]) > reach else 0.0)
        if dir_pref is not None:
            dp = np.asarray(dir_pref, float)
            c += w_dir * float(np.sum((dvec - dp / np.linalg.norm(dp)) ** 2))
        return c

    best, val = None, 1e18
    for az0 in (-0.6, 0.0, 0.6):
        for el0 in (-0.9, 0.0, 0.9):
            for r0 in (-70.0, 0.0, 70.0):
                b, v = mcrig.nelder_mead(cost, [az0, el0, r0, 0.0], [0.3, 0.3, 30, 2.0], iters=300)
                if v < val:
                    best, val = b, v
    best, val = mcrig.nelder_mead(cost, best, [0.05, 0.05, 5, 0.5], iters=500)
    q2, _ = pose(best)
    err = float(np.linalg.norm(tip_of(q2) - target))
    return (tuple(mcrig.xform(AP, np.array(q2["rh"]))), float(best[2]), err, q2["root.pos"],
            tuple(mcrig.xform(AP, np.array(q2["lf"]))))


def off(p, dx=0.0, dy=0.0, dz=0.0):
    return (p[0] + dx, p[1] + dy, p[2] + dz)


def ready(AP=None, **over):
    """Full ready-stance key (targets converted to scene for this actor)."""
    d = dict(fk.PLAYER_READY)
    for k in ("rh", "lh", "rf", "lf"):
        d[k] = actor_scene(fk.PLAYER_READY[k], AP)
    d.update(over)
    return d


def strike_into(keys, t_hit, probe, target_name, target_offset, weapon, AP=None, **kw):
    """Replace rh/rroll of the key at t_hit so the weapon lands on the victim point."""
    p = keyposes(keys, fk.PLAYER_BASE)(t_hit)
    V = probe(t_hit)
    tgt = off(V[target_name], *target_offset)
    rh, roll, err, root_pos, lf = solve_strike(p, tgt, weapon, AP=AP, **kw)
    dz = root_pos[2] - p["root.pos"][2]
    earlier = [k[0] for k in keys if k[0] < t_hit - 1e-9]
    t_from = max(earlier) if earlier else t_hit
    out = []
    for k in keys:
        t, d, *m = k
        d = dict(d)
        if abs(t - t_hit) < 1e-9:
            d["rh"] = rh
            d["rroll"] = (roll, 0, 0)
        if t_from - 1e-9 <= t <= t_hit + 0.12:
            # the depth correction spans the wind-up through the hold (no pop at contact)
            cur = keyposes(keys, fk.PLAYER_BASE)(t)
            rp = cur["root.pos"]
            d["root.pos"] = (rp[0], rp[1], rp[2] + dz)
            for foot in ("lf", "rf"):
                f0 = cur[foot]
                d[foot] = (f0[0], f0[1], f0[2] + dz * S)
        out.append((t, d, *m))
    print("STRIKE", target_name, "err_px", round(err, 2), "roll", round(roll, 1), "lunge_dz", round(dz, 2))
    return out


def clip(cid, Lm, I, weapon, rig, var, d, vkeys, akeys_fn, base=None, contact="chest",
         tail=12, keep=(), **extra):
    """Assemble a solo clip. akeys_fn(probe, V0) -> actor keys (strike already solved)."""
    base = base or (fk.GOBLIN_READY if rig == "goblin" else fk.RAIDER_READY)
    scale = extra.pop("victim_scale", vscale_of(rig, var))
    VP = victim_place(d, var, rig)
    vkp = keyposes(vkeys, base)
    probe = victim_probe(rig, var, vkp, VP, scale)
    akp = keyposes(akeys_fn(probe, probe(0.0)), fk.PLAYER_BASE)
    return dict(id=cid, length_ticks=Lm, impact_tick=I, weapon=weapon, victim_rig=rig,
                victim_variant=var, victim_scale=scale, d=d, contact=contact, victim=vkp,
                actors=[dict(role="solo", solve=lambda t, V: akp(t))],
                keep_ticks=keep, victim_tail_ticks=tail,
                contract=f"{cid.upper()}: {Lm} move ticks, impact {I} (+3 hit-stop)", **extra)


# =========================================================================== victim poses
def KNEEL_ONE(z=0.0, extra=None):
    """Right knee down, left foot planted forward (a buckled, one-knee kneel)."""
    d = {"legik": 0.0, "root.pos": (0, -7.0, z), "right_leg": (-8, 0, 6), "right_shin": 88,
         "left_leg": (-62, 6, -6), "left_shin": 66}
    d.update(extra or {})
    return d


def KNEEL_BOTH(z=0.0, extra=None):
    d = {"legik": 0.0, "root.pos": (0, -6.5, z), "right_leg": (-6, 0, 5), "right_shin": 92,
         "left_leg": (-6, 0, -5), "left_shin": 92}
    d.update(extra or {})
    return d


def LIE_SIDE(side, z=0.0, x=0.0):
    """side +1 rolls onto its LEFT side, -1 onto its right. Knees drawn, arms loose."""
    return {"legik": 0.0, "root.pos": (x, 0.0, z), "root": (-12, 26 * side, 86 * side),
            "torso": (14, 4 * side, 0), "head": (6, 10 * side, -16 * side),
            "right_leg": (-34, 0, 4), "right_shin": 52, "left_leg": (-22, 0, -4), "left_shin": 36,
            "right_arm": (-50, 10, 22), "right_forearm": -26, "left_arm": (-70, -10, -16),
            "left_forearm": -34}


def LIE_BACK(z=0.0, x=0.0, yaw=10.0):
    return {"legik": 0.0, "root.pos": (x, 0.0, z), "root": (-86, yaw, 6),
            "torso": (-6, 0, 0), "head": (-14, 18, 0),
            "right_leg": (-16, 0, 8), "right_shin": 34, "left_leg": (-38, 0, -8), "left_shin": 56,
            "right_arm": (-165, 8, 28), "right_forearm": -18, "left_arm": (-24, 0, -46),
            "left_forearm": -20}


def LIE_FRONT(side=1, z=0.0, x=0.0):
    return {"legik": 0.0, "root.pos": (x, 0.0, z), "root": (86, 18 * side, 12 * side),
            "torso": (4, 0, 0), "head": (-38, 30 * side, 0),
            "right_leg": (4, 0, 6), "right_shin": 14, "left_leg": (8, 0, -4), "left_shin": 26,
            "right_arm": (-150, 0, 40), "right_forearm": -30, "left_arm": (-20, 0, -30),
            "left_forearm": -44}


def settle(pose, amount=0.35):
    """A small bounce/settle key derived from a lying pose (the body lands, rebounds, rests)."""
    d = dict(pose)
    r = d.get("root", (0, 0, 0))
    d["root"] = (r[0] * (1 - 0.03 * amount), r[1], r[2] * (1 - 0.04 * amount))
    return d


# =========================================================================== 1 SWORD_PARRY_THRUST
def clip_sword_parry_thrust():
    """Beat the blade aside, shoulder-shove, a coiled pause, then a driving thrust under the
    ribs. The victim folds around it, sinks to its knees and rolls onto its side."""
    Lm, I = 24, 14
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (0.12, {"right_arm": (-30, 30, 55), "right_forearm": -20, "torso": (4, -18, 6),
                "head": (-4, -12, 0), "left_arm": (-10, 6, -18)}, "snap"),
        (0.30, {"root.pos": (0, -1.0, 4.5), "root": (-6, -8, 0), "torso": (-14, -10, 4),
                "head": (-18, -6, 0), "right_arm": (-60, 20, 60), "right_forearm": -30,
                "left_arm": (-40, -10, -40), "left_forearm": -40,
                "rf": (-3.2, 24, 5.6), "lf": (3.4, 24, 2.4)}, "snap"),
        (0.56, {"root.pos": (0, -1.6, 3.6), "root": (4, -4, 0), "torso": (10, 8, 0),
                "head": (-10, 4, 0), "right_arm": (-110, -10, 30), "right_forearm": -60,
                "left_arm": (-20, 10, -20), "left_forearm": -30}, "out"),
        (ti, {"root.pos": (0, -2.4, 6.0), "root": (2, 0, 0), "torso": (30, 2, 0), "head": (24, 0, 0),
              "right_arm": (-30, 10, 20), "right_forearm": -70, "left_arm": (-48, -20, -8),
              "left_forearm": -95}, "snap"),
        (ti + 0.10, {"root.pos": (0, -2.6, 6.4), "torso": (36, 4, 2), "head": (30, 0, 0)}, "out"),
        (ti + 0.34, KNEEL_BOTH(6.5, {"root": (4, 8, 2), "torso": (30, 6, 6), "head": (34, 8, 0),
                                     "right_arm": (-10, 0, 18), "right_forearm": -30,
                                     "left_arm": (-20, 0, -10), "left_forearm": -40}), "in2"),
        (ti + 0.62, LIE_SIDE(+1, z=8.0), "in"),
        (ti + 0.72, settle(LIE_SIDE(+1, z=8.0)), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            (0.12, {"root.pos": (0, 0, -2.5), "root": (0, -6, 0), "torso": (8, -14, 4), "head": (-4, 8, 0),
                    "lh": off(V0["rhand"], 3.0, 1.0, 4.0), "rh": actor_scene((-6, 10, 3)),
                    "lf": actor_scene((3.0, 24, -8.0))}, "snap"),
            (0.30, {"root.pos": (0, 0, -6.5), "root": (0, -16, 0), "torso": (14, -20, 6), "head": (-6, 16, 0),
                    "lh": actor_scene((8, 3, -12)), "rh": actor_scene((-7, 11, 4)),
                    "rf": actor_scene((-3.0, 24, -2.0))}, "snap"),
            (0.58, {"root.pos": (0, 0, -5.5), "root": (0, 12, 0), "torso": (4, 22, -4), "head": (-6, -24, 0),
                    "rh": actor_scene((-8, 12, 7)), "lh": actor_scene((4, 5, -10)), "rroll": (40, 0, 0)}, "out"),
            (ti, {"root.pos": (0, 0, -8.0), "root": (0, -10, 0), "torso": (10, -14, 2), "head": (-4, 10, 0),
                  "lh": actor_scene((6, 9, -6)), "rf": actor_scene((-3.4, 24, -1.5)),
                  "lf": actor_scene((3.0, 24, -14.5))}, "in"),
            (ti + 0.08, {"root.pos": (0, 0, -8.3), "torso": (11, -15, 2)}, "out"),
            (ti + 0.34, {"root.pos": (0, 0, -4.0), "root": (0, 10, 0), "torso": (4, 8, 0), "head": (8, -6, 0),
                         "rh": actor_scene((-9, 10, -2)), "lh": actor_scene((5, 11, -2)), "rroll": (0, 0, 0),
                         "lf": actor_scene((3.0, 24, -6.0)), "rf": actor_scene((-3.2, 24, 2.0))}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -2.0)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "belly", (0.0, 0.0, 1.5), "sword", roll_pref=30.0)
    return clip("sword_parry_thrust", Lm, I, "sword", "raider", er.SKIRM, 1.60, vkeys, akeys,
                contact="belly", keep=(3, 6, 12))


# =========================================================================== 2 SWORD_RISING_CUT
def clip_sword_rising_cut():
    """Collar grab and a knee to the belly fold the victim; the blade comes up from the hip
    in one rising diagonal cut that throws it upright and spins it down."""
    Lm, I = 22, 13
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(3), {"root.pos": (0, -1.2, -1.0), "torso": (22, 4, 0), "head": (-6, 4, 0),
                 "right_arm": (-40, 0, 20), "left_arm": (-30, 0, -20)}, "snap"),
        (tk(6), {"root.pos": (0, -2.4, 0.6), "root": (6, 0, 0), "torso": (44, 0, 0), "head": (26, 0, 0),
                 "right_arm": (-50, -20, 10), "right_forearm": -90, "left_arm": (-50, 20, -10),
                 "left_forearm": -90}, "snap"),
        (tk(10), {"root.pos": (0, -2.0, 1.2), "torso": (34, -4, 0), "head": (12, 0, 0)}, "out"),
        (ti, {"root.pos": (0, -0.8, 3.6), "root": (-8, -26, -6), "torso": (-24, -18, -10),
              "head": (-38, -10, 0), "right_arm": (-120, 20, 70), "right_forearm": -20,
              "left_arm": (-90, -20, -60), "left_forearm": -20}, "snap"),
        (ti + 0.12, {"root": (-10, -42, -8), "torso": (-26, -24, -12), "head": (-40, -16, 0)}, "out"),
        (ti + 0.40, KNEEL_ONE(4.0, {"root": (6, -70, -8), "torso": (20, -10, -6), "head": (20, -10, 0),
                                   "right_arm": (-30, 0, 30), "left_arm": (-30, 0, -30)}), "in2"),
        (ti + 0.66, dict(LIE_SIDE(-1, z=5.0), root=(-12, -96, -86)), "in"),
        (ti + 0.76, settle(dict(LIE_SIDE(-1, z=5.0), root=(-12, -96, -86))), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            (tk(3), {"root.pos": (0, 0, -4.0), "torso": (10, -8, 0), "head": (4, 6, 0),
                     "lh": off(V0["neck"], 2.0, 2.0, 2.0), "rh": actor_scene((-7, 11, 4)),
                     "lf": actor_scene((3.0, 24, -9.0))}, "snap"),
            # knee: the right leg drives forward-up into the belly
            (tk(6), {"root.pos": (0, 0, -5.0), "root": (-6, 0, 0), "torso": (-2, -6, 0),
                     "rf": off(probe(tk(6))["belly"], -2.0, 2.0, 3.0), "lh": off(V0["neck"], 2, 4, 0)}, "snap"),
            # coil: foot down, blade trails low behind the right hip
            (tk(10), {"root.pos": (0, 0, -3.5), "root": (0, 18, 0), "torso": (12, 24, -4),
                      "head": (4, -22, 0), "rf": actor_scene((-3.4, 24, 1.8)),
                      "rh": actor_scene((-7, 13, 9)), "lh": actor_scene((5, 6, -9)), "rroll": (-20, 0, 0)}, "out"),
            (ti, {"root.pos": (0, 0, -6.0), "root": (0, -14, 0), "torso": (-6, -26, 4), "head": (-10, 16, 0),
                  "lh": actor_scene((7, 12, 2))}, "in"),
            # follow-through: the blade keeps rising high across the body
            (ti + 0.20, {"root": (0, -22, 0), "torso": (-12, -32, 6), "head": (-14, 22, 0),
                         "rh": actor_scene((2, -8, -6)), "rroll": (0, 0, 0)}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -3.0)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "chest", (0.0, -1.0, 1.0), "sword", roll_pref=-40.0)
    return clip("sword_rising_cut", Lm, I, "sword", "raider", er.SKIRM, 1.40, vkeys, akeys,
                keep=(3, 6, 10))


# =========================================================================== 3 AXE_HOOK_CHOP
def clip_axe_hook_chop():
    """The axe's beard hooks the lead leg out from under the victim, it drops to a knee,
    the axe goes up in both hands and comes down in one overhead chop."""
    Lm, I = 26, 17
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(5), {"torso": (8, 10, 0), "head": (-2, 6, 0), "right_arm": (-70, 0, 20), "right_forearm": -60}, "auto"),
        # leg yanked forward, arms thrown back for balance
        (tk(8), {"legik": 0.0, "root.pos": (0, -3.0, 2.0), "root": (-14, 6, 0), "torso": (-10, 6, 0),
                 "head": (-16, 0, 0), "left_leg": (-60, 0, -10), "left_shin": 10, "right_leg": (16, 0, 4),
                 "right_shin": 30, "right_arm": (-10, 20, 70), "left_arm": (-20, -20, -70)}, "snap"),
        (tk(11), KNEEL_ONE(2.0, {"root": (4, 4, 0), "torso": (14, 2, 0), "head": (-22, 0, 0),
                                "right_arm": (-40, 10, 30), "right_forearm": -40,
                                "left_arm": (-60, 0, -10), "left_forearm": -60}), "out2"),
        (tk(15), KNEEL_ONE(2.0, {"torso": (10, 0, 0), "head": (-28, 0, 0),
                                "right_arm": (-100, 20, 20), "right_forearm": -80}), "auto"),
        (ti, KNEEL_ONE(2.6, {"root": (14, 0, 0), "torso": (42, 0, 0), "head": (30, 0, 0),
                            "right_arm": (-10, 0, 40), "right_forearm": -20,
                            "left_arm": (-10, 0, -40), "left_forearm": -20}), "snap"),
        (ti + 0.10, KNEEL_ONE(2.8, {"root": (16, 0, 0), "torso": (46, 0, 2), "head": (34, 0, 0)}), "out"),
        (ti + 0.42, LIE_FRONT(-1, z=6.0, x=-2.0), "in"),
        (ti + 0.52, settle(LIE_FRONT(-1, z=6.0, x=-2.0)), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            # reach low: the axe head goes behind the victim's lead knee
            (tk(5), {"root.pos": (0, 0, -5.0), "root": (0, -6, 0), "torso": (30, -8, 0), "head": (-18, 4, 0),
                     "rh": off(probe(tk(5))["lknee"], 0.0, -1.0, -6.0), "lh": actor_scene((5, 8, -8)),
                     "lf": actor_scene((3.0, 24, -9.0)), "rroll": (70, 0, 0)}, "out"),
            # yank: hips drop back, the hook pulls the leg through
            (tk(8), {"root.pos": (0, 0, -1.0), "root": (0, 10, 0), "torso": (6, 12, 0), "head": (-8, -8, 0),
                     "rh": actor_scene((-6, 12, 5)), "rf": actor_scene((-3.4, 24, 3.0))}, "snap"),
            # both hands take the haft high behind the head, back arches (the read)
            (tk(13), {"root.pos": (0, 0, -3.0), "root": (0, 4, 0), "torso": (-16, 4, 0), "head": (-18, 0, 0),
                      "rh": actor_scene((-2, -10, 4)), "lh": actor_scene((1, -9, 3)), "rroll": (0, 0, 0),
                      "lf": actor_scene((3.0, 24, -7.0))}, "out2"),
            (tk(15), {"torso": (-20, 4, 0), "rh": actor_scene((-2, -11, 5)), "lh": actor_scene((1, -10, 4))}, "auto"),
            (ti, {"root.pos": (0, 0, -5.5), "root": (0, -4, 0), "torso": (30, -4, 0), "head": (14, 0, 0),
                  "lh": actor_scene((-1, 9, -8)), "lf": actor_scene((3.0, 24, -10.0))}, "in"),
            (ti + 0.10, {"torso": (33, -4, 0)}, "out"),
            (ti + 0.40, {"root.pos": (0, 0, -2.0), "torso": (8, 6, 0), "head": (6, 0, 0),
                         "rh": actor_scene((-6, 10, -4)), "lh": actor_scene((5, 11, -3))}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -1.5)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "neck", (0.0, -1.0, 0.0), "axe", roll_pref=0.0)
    return clip("axe_hook_chop", Lm, I, "axe", "raider", er.SKIRM, 1.65, vkeys, akeys,
                contact="neck", keep=(5, 8, 13))


# =========================================================================== 4 AXE_HAFT_CLEAVE
def clip_axe_haft_cleave():
    """Haft butt into the face snaps the head back; the axe winds high over the right
    shoulder and one long diagonal cleave drops the reeling victim on its back."""
    Lm, I = 24, 15
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(3), {"root.pos": (0, -1.2, 1.2), "root": (-8, 0, 0), "torso": (-16, 0, 0), "head": (-40, 8, 0),
                 "right_arm": (-40, 20, 50), "right_forearm": -20, "left_arm": (-30, -20, -50)}, "snap"),
        (tk(7), {"root.pos": (0, -1.4, 3.4), "root": (-4, 8, 0), "torso": (-8, 8, 4), "head": (-24, 10, 0),
                 "rf": (-3.4, 24, 5.0), "lf": (3.2, 24, 3.0),
                 "right_arm": (-100, 10, 10), "right_forearm": -100, "left_arm": (-110, -10, -10),
                 "left_forearm": -100}, "out"),
        (tk(12), {"root.pos": (0, -1.8, 3.0), "torso": (4, 4, 0), "head": (-10, 4, 0)}, "auto"),
        (ti, {"root.pos": (0, -2.2, 4.4), "root": (-6, -24, -8), "torso": (-18, -26, -14), "head": (-20, -20, 0),
              "right_arm": (-60, 20, 70), "right_forearm": -30, "left_arm": (-140, -10, -30),
              "left_forearm": -20}, "snap"),
        (ti + 0.10, {"root": (-8, -28, -10), "torso": (-20, -28, -14)}, "out"),
        (ti + 0.30, {"legik": 0.0, "root.pos": (0, -4.0, 6.0), "root": (-26, -30, -10),
                     "right_leg": (-30, 0, 6), "right_shin": 40, "left_leg": (-10, 0, -6), "left_shin": 20}, "in2"),
        (ti + 0.58, LIE_BACK(z=12.0, yaw=-20), "in"),
        (ti + 0.68, settle(LIE_BACK(z=12.0, yaw=-20)), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            (tk(3), {"root.pos": (0, 0, -3.5), "root": (0, -10, 0), "torso": (10, -12, 0), "head": (-2, 8, 0),
                     "rh": off(V0["head"], -3.0, 3.0, 8.0), "lh": off(V0["head"], 2.0, 4.0, 9.0),
                     "lf": actor_scene((3.0, 24, -8.0))}, "snap"),
            (tk(6), {"root.pos": (0, 0, -2.5), "torso": (4, -6, 0), "rh": actor_scene((-6, 6, -6)),
                     "lh": actor_scene((4, 6, -6))}, "out"),
            (tk(11), {"root.pos": (0, 0, -3.0), "root": (0, 20, 0), "torso": (-10, 26, -8), "head": (-6, -24, 0),
                      "rh": actor_scene((-8, -9, 6)), "lh": actor_scene((-3, -7, 3)), "rroll": (-30, 0, 0)}, "out2"),
            (ti, {"root.pos": (0, 0, -6.5), "root": (0, -14, 0), "torso": (24, -24, 8), "head": (10, 14, 0),
                  "lh": actor_scene((0, 10, -7)), "lf": actor_scene((3.0, 24, -11.0))}, "in"),
            (ti + 0.10, {"torso": (27, -26, 9)}, "out"),
            (ti + 0.36, {"root.pos": (0, 0, -3.0), "root": (0, -16, 0), "torso": (12, -18, 4),
                         "rh": actor_scene((6, 13, -4)), "lh": actor_scene((6, 12, -2)), "rroll": (0, 0, 0)}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -2.0)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "chest", (0.0, 0.0, 1.5), "axe", roll_pref=-20.0)
    return clip("axe_haft_cleave", Lm, I, "axe", "raider", er.SKIRM, 1.40, vkeys, akeys,
                keep=(3, 7, 11))


# =========================================================================== 5 MACE_GUT_SLAM
def clip_mace_gut_slam():
    """Haft jab to the gut folds the victim double; the mace goes up and comes down across
    its back, driving it flat."""
    Lm, I = 24, 16
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(4), {"root.pos": (0, -2.4, 1.6), "root": (8, 0, 0), "torso": (52, 0, 0), "head": (20, 0, 0),
                 "right_arm": (-60, -30, 10), "right_forearm": -100, "left_arm": (-60, 30, -10),
                 "left_forearm": -100}, "snap"),
        (tk(9), {"root.pos": (0, -2.8, 2.4), "root": (10, 4, 0), "torso": (56, 4, 2), "head": (28, 0, 0)}, "out"),
        (tk(14), {"root.pos": (0, -3.0, 2.2), "torso": (50, 0, 0), "head": (16, 0, 0)}, "auto"),
        (ti, KNEEL_BOTH(2.4, {"root": (18, 0, 0), "torso": (60, 0, 0), "head": (34, 0, 0),
                              "right_arm": (-20, 0, 30), "left_arm": (-20, 0, -30)}), "snap"),
        (ti + 0.10, KNEEL_BOTH(2.6, {"root": (22, 0, 0), "torso": (62, 0, 0)}), "out"),
        (ti + 0.40, LIE_FRONT(1, z=8.0, x=2.0), "in"),
        (ti + 0.50, settle(LIE_FRONT(1, z=8.0, x=2.0)), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            (tk(4), {"root.pos": (0, 0, -5.0), "root": (0, -8, 0), "torso": (18, -10, 0), "head": (2, 6, 0),
                     "rh": off(probe(tk(4))["belly"], 0, 0, 8.0), "lh": off(probe(tk(4))["belly"], 3, 1, 9.0),
                     "lf": actor_scene((3.0, 24, -9.0))}, "snap"),
            (tk(7), {"root.pos": (0, 0, -3.5), "torso": (8, -4, 0), "rh": actor_scene((-6, 6, -6)),
                     "lh": actor_scene((4, 6, -6))}, "out"),
            (tk(12), {"root.pos": (0, 0, -3.0), "root": (0, 6, 0), "torso": (-18, 6, 0), "head": (-16, 0, 0),
                      "rh": actor_scene((-2, -11, 5)), "lh": actor_scene((1, -10, 4))}, "out2"),
            (tk(14), {"torso": (-22, 6, 0), "rh": actor_scene((-2, -12, 6)), "lh": actor_scene((1, -11, 5))}, "auto"),
            (ti, {"root.pos": (0, 0, -5.5), "root": (0, -4, 0), "torso": (34, -4, 0), "head": (16, 0, 0),
                  "lh": actor_scene((-1, 10, -8)), "lf": actor_scene((3.0, 24, -10.0))}, "in"),
            (ti + 0.10, {"torso": (37, -4, 0)}, "out"),
            (ti + 0.36, {"root.pos": (0, 0, -1.5), "torso": (6, 4, 0), "head": (8, 0, 0),
                         "rh": actor_scene((-6, 11, -3)), "lh": actor_scene((5, 11, -3))}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -1.0)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "neck", (0.0, -1.5, 0.0), "mace", roll_pref=0.0)
    return clip("mace_gut_slam", Lm, I, "mace", "raider", er.SKIRM, 1.60, vkeys, akeys,
                contact="neck", keep=(4, 9, 12))


# =========================================================================== 6 BARE_COLLAR_THROW
def clip_bare_collar_throw():
    """Both fists take the collar, a knee folds the victim, the right fist rises and a
    hammer-blow to the back of the neck drives it face-down into the dirt."""
    Lm, I = 24, 15
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(3), {"root.pos": (0, -1.2, -1.6), "root": (6, 0, 0), "torso": (10, 0, 0), "head": (-12, 0, 0),
                 "right_arm": (-60, 20, 20), "right_forearm": -70, "left_arm": (-60, -20, -20),
                 "left_forearm": -70}, "snap"),
        (tk(7), {"root.pos": (0, -2.6, -0.4), "root": (8, 0, 0), "torso": (48, 0, 0), "head": (22, 0, 0),
                 "right_arm": (-40, -30, 10), "right_forearm": -100, "left_arm": (-40, 30, -10),
                 "left_forearm": -100}, "snap"),
        (tk(12), {"root.pos": (0, -2.4, 0.4), "torso": (44, 4, 0), "head": (18, 0, 0)}, "auto"),
        (ti, KNEEL_BOTH(1.4, {"root": (22, 0, 0), "torso": (56, 0, 0), "head": (36, 0, 0),
                              "right_arm": (-30, 0, 30), "left_arm": (-30, 0, -30)}), "snap"),
        (ti + 0.10, KNEEL_BOTH(1.6, {"root": (26, 0, 0), "torso": (58, 0, 0)}), "out"),
        (ti + 0.38, LIE_FRONT(-1, z=10.0, x=-3.0), "in"),
        (ti + 0.48, settle(LIE_FRONT(-1, z=10.0, x=-3.0)), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            (tk(3), {"root.pos": (0, 0, -2.5), "torso": (6, 0, 0), "head": (2, 0, 0),
                     "rh": off(V0["neck"], -2.5, 2.0, 1.0), "lh": off(V0["neck"], 2.5, 2.0, 1.0),
                     "lf": actor_scene((3.0, 24, -6.0))}, "snap"),
            (tk(7), {"root.pos": (0, 0, -3.0), "root": (-8, 0, 0), "torso": (-4, 0, 0),
                     "rh": off(probe(tk(7))["neck"], -2.5, 2.0, 1.0), "lh": off(probe(tk(7))["neck"], 2.5, 2.0, 1.0),
                     "rf": off(probe(tk(7))["belly"], -1.0, 3.0, 3.0)}, "snap"),
            (tk(11), {"root.pos": (0, 0, -2.5), "root": (0, 10, 0), "torso": (-10, 12, 0), "head": (-10, -8, 0),
                      "rf": actor_scene((-3.2, 24, 1.8)), "rh": actor_scene((-4, -10, 3)),
                      "lh": off(probe(tk(11))["neck"], 2.5, 2.0, 1.0)}, "out2"),
            (ti, {"root.pos": (0, 0, -4.5), "root": (0, -6, 0), "torso": (36, -6, 0), "head": (18, 0, 0),
                  "lh": actor_scene((6, 12, -4))}, "in"),
            (ti + 0.10, {"torso": (38, -6, 0)}, "out"),
            (ti + 0.36, {"root.pos": (0, 0, 0.0), "torso": (6, 0, 0), "head": (10, 0, 0),
                         "rh": actor_scene((-6, 11, -2)), "lh": actor_scene((5, 11, -2))}, "out"),
            (tk(Lm), ready(), "smooth"),
        ]
        return strike_into(k, ti, probe, "neck", (0.0, -1.0, -1.0), "bare")
    return clip("bare_collar_throw", Lm, I, "bare", "raider", er.SKIRM, 1.00, vkeys, akeys,
                contact="neck", keep=(3, 7, 11))


# =========================================================================== 7 BRUTE_KNEE_BUCKLE
def clip_brute_knee_buckle():
    """A low cut behind the Brute's knee drops it to one knee; it looks up; the weapon comes
    over in both hands and down onto the collar. The Brute topples like a tree."""
    Lm, I = 30, 20
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(4), {"torso": (18, 6, 0), "head": (-6, 4, 0), "right_arm": (-90, 0, 20), "right_forearm": -70}, "auto"),
        (tk(6), {"legik": 0.0, "root.pos": (0, -2.4, 0.0), "root": (4, -10, 0), "torso": (24, -8, 0),
                 "head": (-4, -6, 0), "right_leg": (14, 0, 6), "right_shin": 50,
                 "left_leg": (-8, 0, -4), "left_shin": 10}, "snap"),
        (tk(10), KNEEL_ONE(0.5, {"root": (8, -6, 0), "torso": (26, -4, 0), "head": (6, 0, 0),
                                "right_arm": (-20, 0, 40), "right_forearm": -30,
                                "left_arm": (-70, 0, -20), "left_forearm": -60}), "out2"),
        (tk(16), KNEEL_ONE(0.5, {"root": (2, 0, 0), "torso": (8, 0, 0), "head": (-30, 0, 0),
                                "right_arm": (-60, 0, 30), "right_forearm": -60}), "auto"),
        (ti, KNEEL_ONE(1.0, {"root": (12, 0, 0), "torso": (40, 0, 0), "head": (26, 0, 0),
                            "right_arm": (-10, 0, 40), "right_forearm": -20,
                            "left_arm": (-10, 0, -40), "left_forearm": -20}), "snap"),
        (ti + 0.10, KNEEL_ONE(1.2, {"root": (14, 0, 0), "torso": (44, 0, 0), "head": (30, 0, 0)}), "out"),
        (ti + 0.45, LIE_SIDE(-1, z=4.0), "in"),
        (ti + 0.55, settle(LIE_SIDE(-1, z=4.0), 0.6), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(), "auto"),
            # a quick low sidestep cut behind the right knee
            (tk(3), {"root.pos": (0, 0, -3.0), "root": (0, 22, 0), "torso": (10, 24, 0), "head": (-4, -18, 0),
                     "rh": actor_scene((-9, 10, 6)), "lf": actor_scene((3.0, 24, -8.0))}, "out"),
            (tk(6), {"root.pos": (0, 0, -6.0), "root": (0, -18, 0), "torso": (30, -22, 0), "head": (-14, 14, 0),
                     "rh": off(probe(tk(6))["rknee"], 0.0, 0.0, 2.0), "rroll": (-80, 0, 0),
                     "lf": actor_scene((3.4, 24, -12.0))}, "snap"),
            (tk(10), {"root.pos": (0, 0, -4.0), "root": (0, -6, 0), "torso": (8, -6, 0), "head": (-10, 0, 0),
                      "rh": actor_scene((-6, 8, -6)), "rroll": (0, 0, 0), "lf": actor_scene((3.0, 24, -8.0))}, "out"),
            # wind-up: high over the head in both hands, back arched (long, readable)
            (tk(16), {"root.pos": (0, 0, -3.0), "root": (0, 4, 0), "torso": (-20, 4, 0), "head": (-22, 0, 0),
                      "rh": actor_scene((-2, -11, 5)), "lh": actor_scene((1, -10, 4))}, "out2"),
            (tk(18), {"torso": (-24, 4, 0), "rh": actor_scene((-2, -12, 6)), "lh": actor_scene((1, -11, 5))}, "auto"),
            (ti, {"root.pos": (0, 0, -7.0), "root": (0, -4, 0), "torso": (30, -4, 0), "head": (12, 0, 0),
                  "lh": actor_scene((-1, 8, -9)), "lf": actor_scene((3.0, 24, -13.0))}, "in"),
            (ti + 0.10, {"torso": (33, -4, 0)}, "out"),
            (ti + 0.40, {"root.pos": (0, 0, -3.0), "torso": (6, 4, 0), "head": (6, 0, 0),
                         "rh": actor_scene((-6, 10, -4)), "lh": actor_scene((5, 11, -3)),
                         "lf": actor_scene((3.0, 24, -8.0))}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -2.0)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "neck", (-1.5, -1.0, 1.0), "sword", roll_pref=0.0)
    return clip("brute_knee_buckle", Lm, I, "sword", "raider", er.BRUTE, 2.05, vkeys, akeys,
                contact="neck", keep=(6, 10, 16), tail=10)


# =========================================================================== 8 BRUTE_CLIMB_STRIKE
def clip_brute_climb_strike():
    """Cut the Brute down to a knee, step up onto its bent thigh, rise above it and drive the
    blade down at the collar; spring back down as it falls backwards."""
    Lm, I = 30, 20
    ti = tk(I)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(3), {"legik": 0.0, "root.pos": (0, -2.4, 0.0), "root": (4, 10, 0), "torso": (24, 8, 0),
                 "head": (-4, 6, 0), "left_leg": (14, 0, -6), "left_shin": 50,
                 "right_leg": (-8, 0, 4), "right_shin": 10}, "snap"),
        (tk(7), {"legik": 0.0, "root.pos": (0, -7.0, 1.0), "root": (4, 0, 0), "torso": (20, 0, 0),
                 "right_leg": (-62, -6, 6), "right_shin": 66, "left_leg": (-8, 0, -6), "left_shin": 88,
                 "right_arm": (-30, 0, 40), "left_arm": (-30, 0, -40)}, "out2"),
        (tk(14), {"torso": (-8, 0, 0), "head": (-40, 0, 0), "right_arm": (-70, 10, 30), "right_forearm": -70}, "auto"),
        (ti, {"root": (-6, 0, 0), "torso": (-20, 0, 0), "head": (-20, 0, 0), "right_arm": (-100, 20, 60),
              "right_forearm": -10, "left_arm": (-90, -20, -60), "left_forearm": -10}, "snap"),
        (ti + 0.10, {"torso": (-24, 0, 0), "head": (-26, 0, 0)}, "out"),
        (ti + 0.50, LIE_BACK(z=10.0, yaw=8), "in"),
        (ti + 0.60, settle(LIE_BACK(z=10.0, yaw=8), 0.6), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        thigh = probe(tk(10))["rknee"]
        k = [
            (0.00, ready(), "auto"),
            (tk(3), {"root.pos": (0, 0, -4.0), "root": (0, -20, 0), "torso": (26, -20, 0),
                     "rh": off(probe(tk(3))["lknee"], 0, 0, 2.0), "rroll": (-80, 0, 0),
                     "lf": actor_scene((3.0, 24, -9.0))}, "snap"),
            (tk(7), {"root.pos": (0, 0, -3.0), "root": (0, 0, 0), "torso": (6, 0, 0),
                     "rh": actor_scene((-6, 8, -2)), "rroll": (0, 0, 0)}, "out"),
            # step up: right foot onto the bent thigh, body rises
            (tk(11), {"root.pos": (0, 4.0, -9.0), "torso": (14, 0, 0), "head": (6, 0, 0),
                      "rf": off(thigh, 0.0, -1.0, 2.0), "lf": actor_scene((3.0, 24, -8.0)),
                      "lh": off(probe(tk(11))["neck"], 3, -2, 3)}, "out"),
            (tk(16), {"root.pos": (0, 9.0, -11.0), "torso": (-12, 0, 0), "head": (-10, 0, 0),
                      "lf": actor_scene((3.0, 14, -10.0)), "rh": actor_scene((-3, -12, 3)),
                      "lh": actor_scene((2, -10, 2))}, "out2"),
            (tk(18), {"root.pos": (0, 10.0, -11.5), "torso": (-16, 0, 0)}, "auto"),
            (ti, {"root.pos": (0, 7.0, -12.5), "torso": (34, 0, 0), "head": (16, 0, 0),
                  "lh": actor_scene((2, 8, -8))}, "in"),
            (ti + 0.10, {"torso": (37, 0, 0)}, "out"),
            (ti + 0.34, {"root.pos": (0, 0, -6.0), "torso": (10, 0, 0), "head": (6, 0, 0),
                         "rf": actor_scene((-3.2, 24, -3.0)), "lf": actor_scene((3.0, 24, -8.0)),
                         "rh": actor_scene((-6, 10, -4)), "lh": actor_scene((5, 11, -3))}, "in2"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -3.0)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "neck", (0.0, 0.0, 1.0), "sword", roll_pref=180.0)
    return clip("brute_climb_strike", Lm, I, "sword", "raider", er.BRUTE, 1.60, vkeys, akeys,
                contact="neck", keep=(3, 7, 11, 16), tail=10)


# =========================================================================== 9 CAPTAIN_DISARM_DRIVE
def clip_captain_disarm_drive():
    """The captain's overhead blow is met blade-to-haft, wrenched outward and down (sparks on
    the server), and while it reels empty-guarded the blade drives through; it falls back."""
    Lm, I = 28, 19
    ti = tk(I)
    vkeys = [
        (0.00, {"right_arm": (-150, 0, 20), "right_forearm": -40, "torso": (-8, 0, 0), "head": (-12, 0, 0)}, "auto"),
        (tk(5), {"root.pos": (0, -1.6, -1.0), "torso": (22, 0, 0), "head": (0, 0, 0),
                 "right_arm": (-90, 0, 10), "right_forearm": -20}, "in"),
        (tk(9), {"root.pos": (0, -1.4, 1.0), "root": (0, -24, 0), "torso": (6, -30, -8), "head": (-10, -20, 0),
                 "right_arm": (-20, 30, 80), "right_forearm": -10, "left_arm": (-40, -10, -30)}, "snap"),
        (tk(14), {"root.pos": (0, -1.8, 1.6), "root": (0, -8, 0), "torso": (-4, -6, 0), "head": (-16, 0, 0),
                  "right_arm": (-40, 10, 40), "right_forearm": -40, "left_arm": (-60, 0, -20),
                  "left_forearm": -60}, "out"),
        (ti, {"root.pos": (0, -2.0, 3.4), "root": (-4, 0, 0), "torso": (26, 0, 0), "head": (-30, 0, 0),
              "right_arm": (-70, -10, 30), "right_forearm": -80, "left_arm": (-70, 10, -30),
              "left_forearm": -80}, "snap"),
        (ti + 0.10, {"torso": (30, 0, 0), "head": (-34, 0, 0)}, "out"),
        (ti + 0.30, {"legik": 0.0, "root.pos": (0, -4.0, 5.0), "root": (-24, 0, 0),
                     "right_leg": (-24, 0, 4), "right_shin": 36, "left_leg": (-14, 0, -4), "left_shin": 24,
                     "torso": (10, 0, 0), "head": (-10, 0, 0)}, "in2"),
        (ti + 0.60, LIE_BACK(z=12.0, yaw=14), "in"),
        (ti + 0.70, settle(LIE_BACK(z=12.0, yaw=14), 0.5), "out"),
        (ti + 1.00, {}, "auto"),
    ]

    def akeys(probe, V0):
        k = [
            (0.00, ready(rh=actor_scene((-5, -2, -8))), "auto"),
            # bind: blade rises to meet the falling haft
            (tk(5), {"root.pos": (0, 0, -2.5), "torso": (-4, -4, 0), "head": (-12, 0, 0),
                     "rh": off(probe(tk(5))["rhand"], 0.0, 3.0, 8.0), "lh": actor_scene((4, 2, -8)),
                     "lf": actor_scene((3.0, 24, -6.0))}, "in"),
            # wrench: blade sweeps out and down to the right, left hand clamps the wrist
            (tk(9), {"root.pos": (0, 0, -3.5), "root": (0, 22, 0), "torso": (14, 26, 0), "head": (-4, -18, 0),
                     "rh": actor_scene((-12, 12, -2)), "rroll": (60, 0, 0),
                     "lh": off(probe(tk(9))["rhand"], 0, 0, 3)}, "snap"),
            # chamber at the hip, weight back (the read)
            (tk(15), {"root.pos": (0, 0, -3.0), "root": (0, 16, 0), "torso": (4, 22, -4), "head": (-6, -20, 0),
                      "rh": actor_scene((-8, 12, 7)), "lh": actor_scene((5, 5, -10)), "rroll": (40, 0, 0)}, "out"),
            (ti, {"root.pos": (0, 0, -8.0), "root": (0, -10, 0), "torso": (12, -14, 2), "head": (-4, 10, 0),
                  "lh": actor_scene((6, 9, -6)), "rf": actor_scene((-3.4, 24, -1.5)),
                  "lf": actor_scene((3.0, 24, -14.5))}, "in"),
            (ti + 0.10, {"root.pos": (0, 0, -8.4), "torso": (13, -15, 2)}, "out"),
            # shove off the blade with the left hand
            (ti + 0.30, {"root.pos": (0, 0, -5.0), "lh": actor_scene((5, 4, -12)), "torso": (4, 4, 0),
                         "rh": actor_scene((-8, 10, 2)), "rroll": (0, 0, 0)}, "out"),
            (tk(Lm), ready(**{"root.pos": (0, 0, -2.5)}), "smooth"),
        ]
        return strike_into(k, ti, probe, "chest", (0.0, 0.0, 1.5), "sword", roll_pref=30.0)
    return clip("captain_disarm_drive", Lm, I, "sword", "raider", er.CAPTAIN, 1.50, vkeys, akeys,
                keep=(5, 9, 15))


# =========================================================================== 10 GOBLIN_SCRUFF_SLAM
def clip_goblin_scruff_slam():
    """The goblin is caught by the scruff, hoisted kicking off its feet and slammed flat."""
    Lm, I = 20, 12
    ti = tk(I)
    lift = {"legik": 0.0, "right_leg": (-30, 0, 10), "right_shin": 40, "left_leg": (10, 0, -10), "left_shin": 60}
    vkeys = [
        (0.00, {}, "auto"),
        (tk(3), {"root.pos": (0, 0.5, -1.0), "torso": (4, 0, 0), "head": (-24, 0, 0),
                 "right_arm": (-120, 20, 40), "left_arm": (-120, -20, -40)}, "snap"),
        (tk(7), dict(lift, **{"root.pos": (0, 11.0, -2.0), "root": (-8, 0, 0), "torso": (-6, 0, 0),
                              "head": (-30, 0, 0), "right_arm": (-160, 20, 60), "right_forearm": -40,
                              "left_arm": (-150, -20, -60), "left_forearm": -40}), "out2"),
        (tk(9), dict(lift, **{"root.pos": (0, 12.0, -2.0), "right_leg": (20, 0, 10), "right_shin": 70,
                              "left_leg": (-40, 0, -10), "left_shin": 20}), "auto"),
        (ti, {"legik": 0.0, "root.pos": (0, 0.0, 2.0), "root": (-88, 0, 0), "torso": (-10, 0, 0),
              "head": (-20, 0, 0), "right_leg": (-20, 0, 10), "right_shin": 30, "left_leg": (-10, 0, -10),
              "left_shin": 20, "right_arm": (-170, 10, 30), "left_arm": (-170, -10, -30)}, "in"),
        (ti + 0.08, {"root.pos": (0, 1.5, 2.0), "root": (-84, 0, 4), "torso": (-4, 0, 0), "head": (-8, 0, 0)}, "out"),
        (ti + 0.30, {"root.pos": (0, 0.0, 2.0), "root": (-88, 6, 2), "head": (-14, 30, 0),
                     "right_arm": (-150, 0, 40), "left_arm": (-40, 0, -50)}, "in2"),
        (ti + 0.90, {}, "auto"),
    ]

    def akeys(probe, V0):
        return [
            (0.00, ready(), "auto"),
            (tk(3), {"root.pos": (0, 0, -3.0), "torso": (26, 0, 0), "head": (6, 0, 0),
                     "lh": off(V0["neck"], 0.0, -1.0, 1.0), "lf": actor_scene((3.0, 24, -7.0))}, "snap"),
            (tk(7), {"root.pos": (0, 0, -2.0), "root": (0, 0, 0), "torso": (-14, 0, 0), "head": (-18, 0, 0),
                     "lh": actor_scene((3, -10, -6)), "rh": actor_scene((-7, 9, 4))}, "out2"),
            (tk(9), {"torso": (-18, 0, 0), "lh": actor_scene((3, -11, -5))}, "auto"),
            (ti, {"root.pos": (0, 0, -7.0), "torso": (52, 0, 0), "head": (24, 0, 0),
                  "lh": off(probe(ti)["chest"], 0.0, -1.0, 1.0), "lf": actor_scene((3.0, 24, -12.0)),
                  "rf": actor_scene((-3.2, 24, -1.0))}, "in"),
            (ti + 0.10, {"torso": (54, 0, 0)}, "out"),
            (ti + 0.30, {"root.pos": (0, 0, -2.0), "torso": (10, 0, 0), "head": (10, 0, 0),
                         "lh": actor_scene((5, 11, -3))}, "out"),
            (tk(Lm), ready(), "smooth"),
        ]
    return clip("goblin_scruff_slam", Lm, I, "sword", "goblin", er.SKIRM, 0.85, vkeys, akeys,
                contact="chest", contact_hand="left", keep=(3, 7), tail=10)


# =========================================================================== 11 DOUBLE_PIN
def clip_double_pin():
    """Co-op: the lead (at the victim's left) hooks a knee and forces the Brute down, then pins
    its shoulder; the partner (in front) winds a huge overhead and brings it down. The Brute
    rolls away from the lead."""
    Lm, I = 32, 22
    ti = tk(I)
    dp, dl = 1.95, 1.30            # FinisherVariant.DOUBLE_PARTNER_DISTANCE / actor distance
    var, rig = er.BRUTE, "raider"
    VP = victim_place(dp, var, rig)
    LEAD_AP = fk.place(blocks(-dl), blocks(-dp), -90.0, S)

    def L(p):
        return actor_scene(p, LEAD_AP)
    vkeys = [
        (0.00, {}, "auto"),
        (tk(4), {"torso": (10, 10, 6), "head": (-4, 10, 0), "right_arm": (-80, 0, 20), "right_forearm": -70}, "auto"),
        (tk(7), {"legik": 0.0, "root.pos": (0, -2.6, 0.0), "root": (6, 0, -10), "torso": (20, 0, -8),
                 "head": (0, 14, 0), "left_leg": (16, 0, -6), "left_shin": 56, "right_leg": (-6, 0, 4),
                 "right_shin": 12}, "snap"),
        (tk(11), KNEEL_BOTH(0.5, {"root": (10, 0, -6), "torso": (30, 0, -6), "head": (-6, 0, 0),
                                  "right_arm": (-40, 0, 40), "right_forearm": -40,
                                  "left_arm": (-20, 0, -30), "left_forearm": -20}), "out2"),
        (tk(18), KNEEL_BOTH(0.5, {"root": (6, 0, -4), "torso": (24, 0, -4), "head": (-26, 0, 0)}), "auto"),
        (ti, KNEEL_BOTH(1.0, {"root": (16, 0, -4), "torso": (46, 0, 0), "head": (30, 0, 0),
                              "right_arm": (-10, 0, 40), "left_arm": (-10, 0, -40)}), "snap"),
        (ti + 0.10, KNEEL_BOTH(1.2, {"root": (18, 0, -4), "torso": (50, 0, 0)}), "out"),
        (ti + 0.46, LIE_SIDE(-1, z=4.0, x=-2.0), "in"),
        (ti + 0.56, settle(LIE_SIDE(-1, z=4.0, x=-2.0), 0.6), "out"),
        (ti + 1.00, {}, "auto"),
    ]
    vkp = keyposes(vkeys, fk.RAIDER_READY)
    probe = victim_probe(rig, var, vkp, VP, 1.0)
    # ---- lead (pinning)
    lead = [
        (0.00, ready(LEAD_AP), "auto"),
        (tk(4), {"root.pos": (0, 0, -3.0), "torso": (26, 0, 0), "head": (-10, 0, 0),
                 "rh": off(probe(tk(4))["lknee"], 0, 0, 0), "rroll": (-80, 0, 0),
                 "lh": off(probe(tk(4))["neck"], 0, 2, 0), "lf": L((3.0, 24, -8.0))}, "out"),
        (tk(8), {"root.pos": (0, 0, -1.0), "root": (0, 10, 0), "torso": (10, 8, 0), "head": (-6, 0, 0),
                 "rh": L((-6, 11, 4)), "rroll": (0, 0, 0), "lh": off(probe(tk(8))["neck"], 0, 1, 0)}, "snap"),
        (tk(12), {"root.pos": (0, 0, -3.0), "root": (0, 0, 0), "torso": (30, 0, 0), "head": (-14, 0, 0),
                  "lh": off(probe(tk(12))["neck"], 0, 0, 0), "rh": L((-7, 4, -4))}, "out"),
        (ti, {"torso": (36, 0, 0), "lh": off(probe(ti)["neck"], 0, 0, 0), "head": (4, 0, 0)}, "in"),
        (ti + 0.10, {"torso": (38, 0, 0)}, "out"),
        (ti + 0.40, {"root.pos": (0, 0, 1.0), "torso": (6, -8, 0), "head": (6, 10, 0),
                     "lh": L((5, 10, -3)), "rh": L((-6, 10, -2))}, "out"),
        (tk(Lm), ready(LEAD_AP, **{"root.pos": (0, 0, 1.0)}), "smooth"),
    ]
    # ---- partner (the overhead)
    partner = [
        (0.00, ready(), "auto"),
        (tk(8), {"root.pos": (0, 0, -1.0), "torso": (4, 0, 0), "rh": actor_scene((-6, 4, -6))}, "auto"),
        (tk(16), {"root.pos": (0, 0, -2.0), "root": (0, 4, 0), "torso": (-24, 4, 0), "head": (-24, 0, 0),
                  "rh": actor_scene((-2, -12, 6)), "lh": actor_scene((1, -11, 5)),
                  "lf": actor_scene((3.0, 24, -6.0))}, "out2"),
        (tk(19), {"torso": (-28, 4, 0), "rh": actor_scene((-2, -12, 7)), "lh": actor_scene((1, -11, 6))}, "auto"),
        (ti, {"root.pos": (0, 0, -6.0), "root": (0, -4, 0), "torso": (32, -4, 0), "head": (14, 0, 0),
              "lh": actor_scene((-1, 8, -9)), "lf": actor_scene((3.0, 24, -12.0))}, "in"),
        (ti + 0.10, {"torso": (35, -4, 0)}, "out"),
        (ti + 0.40, {"root.pos": (0, 0, -2.5), "torso": (6, 4, 0), "head": (6, 0, 0),
                     "rh": actor_scene((-6, 10, -4)), "lh": actor_scene((5, 11, -3))}, "out"),
        (tk(Lm), ready(**{"root.pos": (0, 0, -2.0)}), "smooth"),
    ]
    partner = strike_into(partner, ti, probe, "neck", (0.0, -1.0, 1.0), "sword")
    lkp = keyposes(lead, fk.PLAYER_BASE)
    pkp = keyposes(partner, fk.PLAYER_BASE)
    return dict(id="double_pin", length_ticks=Lm, impact_tick=I, weapon="sword", victim_rig=rig,
                victim_variant=var, victim_scale=1.0, d=dp, contact="neck", victim=vkp,
                victim_place=(0.0, -dp, 180.0), cam_mid=-dp / 2.0,
                actors=[dict(role="lead", place=(-dl, -dp, -90.0), strikes=False, solve=lambda t, V: lkp(t)),
                        dict(role="partner", place=(0.0, 0.0, 0.0), solve=lambda t, V: pkp(t))],
                keep_ticks=(4, 8, 12, 16), victim_tail_ticks=10,
                contract="DOUBLE_PIN_EXECUTION: 32 move ticks, impact 22 (+3 hit-stop); lead at the "
                         "victim's left (1.3 blocks), partner in front (1.6 blocks)")


CLIPS = {
    "sword_parry_thrust": clip_sword_parry_thrust,
    "sword_rising_cut": clip_sword_rising_cut,
    "axe_hook_chop": clip_axe_hook_chop,
    "axe_haft_cleave": clip_axe_haft_cleave,
    "mace_gut_slam": clip_mace_gut_slam,
    "bare_collar_throw": clip_bare_collar_throw,
    "brute_knee_buckle": clip_brute_knee_buckle,
    "brute_climb_strike": clip_brute_climb_strike,
    "captain_disarm_drive": clip_captain_disarm_drive,
    "goblin_scruff_slam": clip_goblin_scruff_slam,
    "double_pin": clip_double_pin,
}


def ready_hold():
    """player/finisher_ready: the lead steadies over the victim waiting for a partner (loops)."""
    k = [
        (0.00, dict(fk.PLAYER_READY), "auto"),
        (0.50, {"root.pos": (0, -0.4, 0), "torso": (8, 4, 0), "head": (-6, -8, 0),
                "rh": (-5.0, 7.0, -7.8), "lh": (4.6, 9.0, -5.2)}, "auto"),
        (1.00, dict(fk.PLAYER_READY), "auto"),
    ]
    return keyposes(k, fk.PLAYER_BASE)
