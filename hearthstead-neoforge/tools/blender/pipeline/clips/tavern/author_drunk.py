"""Drunk clips (tavern lane, owner requests 26 Sep). One Blender process, run headless:

    blender -b --factory-startup --python author_drunk.py -- [--fast|--stills] [--no-export] [clip ...]

Walks (the WALK slot: distance-clocked, blocks_per_cycle in the meta, two gait cycles each):
  TIPSY_WALK       2.0 s  1 ale: an ordinary gait with a slight loose sway and a soft head bob.
  DRUNK_WALK       2.4 s  2 ales: uneven steps (short-long), wandering plant widths, a rolling
                          torso, loose arms swinging wide, the head lolling.
  VERY_DRUNK_WALK  2.8 s  3+: a lurching stagger - a quick half-step catch, big roll and yaw,
                          the head hanging, arms out for balance.
One-shots (server-timed cues). Ragdoll feel = limp limbs driven by a floppy spring-damper
(low damping) from the body's own acceleration: they lag, overshoot and flop.
  STUMBLE (+__V2)  1.6 s  ADDITIVE over the drunk walk (zero at both ends): the right foot
                          catches (0.12 s), the torso pitches, arms flail and windmill, a catch
                          step, regained. V2: a sideways lurch to the right with a hop.
  FALL_FORWARD     5.0 s  knees buckle, a gravity tip onto the front, limp arms, the head
                          lagging then bouncing; IMPACT 0.95 s (thud); lie still; groan ~1.95;
                          push up with the hands, kneel, hands on knees, up, wobble. Rest at 5.0.
  FALL_SIDE        5.0 s  knees buckle, a twisting spin onto the right hip; IMPACT 0.95 s; lie
                          on the side; groan ~2.05; roll to the knees, up, wobble.
  DRUNK_LEAN       4.0 s  loop: the right shoulder against a wall (the server turns the settler
                          so the wall is on its right), head drooping, slow sway.
  DRUNK_SIT        6.0 s  plops onto the floor (contact 0.9 s), sits swaying, gets up.
Contact is solved, not guessed: every frame of the falls / sit is lowered or raised so the
lowest cube corner of the whole rig touches the ground exactly; the lean is shifted so the
body touches the wall plane (x = -8 px) exactly.
"""

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tavernkit as tk  # noqa: E402
import numpy as np  # noqa: E402
import mcrig  # noqa: E402
import lifekit as lk  # noqa: E402

A = tk.args()
ONLY = list(A["rest"])
c = tk.c
ACC, ACC2, DEC, DEC3, INOUT = tk.ACC, tk.ACC2, tk.DEC, tk.DEC3, tk.INOUT
G = 24.0
WALL_X = -8.0
IMPACT = 0.95

# --------------------------------------------------------------------------- contact helpers
_CUBES = []
for _name, (_parent, _pivot, _cubes) in mcrig.PARTS.items():
    for cube in _cubes:
        fr, sz = np.array(cube[0], float), np.array(cube[1], float)
        pts = np.array([[fr[0] + sz[0] * i, fr[1] + sz[1] * j, fr[2] + sz[2] * k, 1.0]
                        for i in (0, 1) for j in (0, 1) for k in (0, 1)])
        _CUBES.append((_name, pts.T))


def extents(ch):
    w = mcrig.pose_matrices(ch)
    return np.concatenate([(w[n] @ p)[:3] for n, p in _CUBES], axis=1)


def ground(ch, hop=0.0):
    """Shift the root so the lowest corner sits exactly on the ground (+hop px in the air)."""
    lowest = float(np.max(extents(ch)[1]))
    pos = list(ch["root"].get("pos", (0.0, 0.0, 0.0)))
    pos[1] += lowest - G + hop
    ch["root"]["pos"] = tuple(pos)
    return ch


def wall_touch(ch):
    """Shift the root sideways so the right-most corner touches the wall plane exactly."""
    right = float(np.min(extents(ch)[0]))
    pos = list(ch["root"].get("pos", (0.0, 0.0, 0.0)))
    pos[0] += WALL_X - right
    ch["root"]["pos"] = tuple(pos)
    return ch


class Floppy:
    """Deterministic spring-damper followers pre-simulated on a fine grid.

    targets(t) -> {k: target}; inertia(t) -> {k: body acceleration felt by k} (deg/s^2; the
    follower is pushed the opposite way). Low zeta = floppy overshoot. loop=True runs extra
    passes so the loop seam is continuous. clamp = a limb stopped by the ground / the body."""

    def __init__(self, length, targets, inertia, omega=8.0, zeta=0.3, dt=1.0 / 240.0, loop=False,
                 clamp=None):
        n = int(round(length / dt)) + 1
        self.ts = np.arange(n) * dt
        keys = list(targets(0.0).keys())
        self.vals = {k: np.zeros(n) for k in keys}
        state = {k: [targets(0.0)[k], 0.0] for k in keys}
        clamp = clamp or {}
        passes = 3 if loop else 1
        for p in range(passes):
            for i, t in enumerate(self.ts):
                tg, acc = targets(t), inertia(t)
                for k in keys:
                    x, v = state[k]
                    a = omega * omega * (tg[k] - x) - 2 * zeta * omega * v - acc.get(k, 0.0)
                    v += a * dt
                    x += v * dt
                    if k in clamp:
                        lo, hi = clamp[k]
                        if x < lo or x > hi:
                            x = min(hi, max(lo, x))
                            v = -0.25 * v
                    state[k] = [x, v]
                    if p == passes - 1:
                        self.vals[k][i] = x

    def __call__(self, k, t):
        return float(np.interp(t, self.ts, self.vals[k]))


def d2(f, t, h=1.0 / 120.0):
    return (f(t + h) - 2 * f(t) + f(t - h)) / (h * h)


def mix(a, b, w):
    return tuple(x + (y - x) * w for x, y in zip(a, b))


# =========================================================================== the drunk walks
WALKS = {
    #  steps: {foot: [(contact time, plant x)]}; v = ground speed px/s; duty = stance share
    "tipsy_walk": dict(L=2.0, v=19.2, duty=0.6, lift=2.2,
                       steps={"right": [(0.0, -2.7), (1.0, -2.5)], "left": [(0.5, 2.6), (1.5, 2.8)]},
                       sway=0.35, head=0.4, arms=0.35, yaw=2.5),
    "drunk_walk": dict(L=2.4, v=15.5, duty=0.64, lift=1.8,
                       steps={"right": [(0.0, -2.3), (1.3, -3.9)], "left": [(0.55, 2.2), (1.85, 3.7)]},
                       sway=1.0, head=1.0, arms=1.0, yaw=7.0),
    "very_drunk_walk": dict(L=2.8, v=12.5, duty=0.7, lift=1.3,
                            steps={"right": [(0.0, -2.2), (1.55, -4.4)],
                                   "left": [(0.7, 2.2), (1.05, 3.8), (2.3, 2.6)]},
                            sway=1.7, head=1.8, arms=1.6, yaw=11.0),
}


def walk_foot(spec, t, foot):
    """Treadmill foot: stance slides back at ground speed v, swing arcs to the next plant.
    Returns (position, swing height 0..1)."""
    L, v, duty, lift = spec["L"], spec["v"], spec["duty"], spec["lift"]
    mine = spec["steps"][foot]
    n = len(mine)
    t %= L
    for i, (t0, x0) in enumerate(mine):
        t1, x1 = mine[(i + 1) % n]
        if t1 <= t0:
            t1 += L
        tt = t if t >= t0 else t + L
        if not (t0 <= tt < t1):
            continue
        span = t1 - t0
        stance = duty * span
        e0 = v * stance
        n1, n2 = mine[(i + 1) % n][0], mine[(i + 2) % n][0]
        span_n = (n2 - n1) % L or L
        e1 = v * duty * span_n
        if tt - t0 < stance:
            return np.array([x0, G, -e0 / 2 + v * (tt - t0)]), 0.0
        s = (tt - t0 - stance) / (span - stance)
        sm = s * s * (3 - 2 * s)
        z = e0 / 2 - (e0 / 2 + e1 / 2) * sm
        h = math.sin(math.pi * min(1.0, s ** 0.8))
        return np.array([x0 + (x1 - x0) * sm, G - lift * h, z]), h
    return np.array([mine[0][1], G, 0.0]), 0.0


def make_walk(slug):
    spec = WALKS[slug]
    L, ks, kh, ka, kyaw = spec["L"], spec["sway"], spec["head"], spec["arms"], spec["yaw"]
    ph = np.random.default_rng(sum(map(ord, slug))).uniform(0, 2 * math.pi, 8)

    def per(n, t):
        return math.sin(2 * math.pi * n * t / L + ph[n % 8])

    def feet(t):
        return walk_foot(spec, t, "right"), walk_foot(spec, t, "left")

    def root_x(t):
        (fr, hr), (fl, hl) = feet(t)
        wr, wl = 1.0 - hr, 1.0 - hl
        return 0.32 * ks * (wr * fr[0] + wl * fl[0]) / max(1e-3, wr + wl) + 0.6 * ks * per(1, t)

    def root_y(t):
        (_, hr), (_, hl) = feet(t)
        return -1.0 - 0.25 * ks + 0.9 * max(hr, hl)

    def yaw(t):
        return kyaw * per(1, t + 0.2) + 0.4 * kyaw * per(3, t)

    def pitch(t):
        return 4.0 + 3.0 * ks + 2.0 * ks * per(2, t)

    flop = Floppy(L, lambda t: {"hx": 4.0 * kh + 3.0 * kh * (0.5 + 0.5 * per(2, t)),
                                "hz": 7.0 * kh * per(1, t + 0.35), "hy": 6.0 * kh * per(1, t + 0.6),
                                "ar": 0.0, "al": 0.0},
                  lambda t: {"hz": 6.0 * d2(root_x, t), "ar": -5.0 * d2(root_x, t), "al": -5.0 * d2(root_x, t),
                             "hx": 4.0 * d2(root_y, t)},
                  omega=7.0, zeta=0.3, loop=True,
                  clamp={"hz": (-24, 24), "ar": (-10, 30), "al": (-30, 10), "hx": (-10, 30)})

    def solve(t):
        t %= L
        (fr, _), (fl, _) = feet(t)
        rx = root_x(t)
        roll = -1.2 * rx
        ch = {"root": {"rot": (0.0, yaw(t), roll), "pos": (rx, root_y(t), 0.4 * ks * per(2, t + 0.1))},
              "torso": {"rot": (pitch(t), 0.5 * yaw(t) + 3.0 * ks * per(1, t + 0.5),
                                0.7 * roll + 3.0 * ks * per(2, t + 0.3)),
                        "scale": lk.breath_scale(lk.breath(t, L / 2, phase=0.1), 0.02)},
              "head": {"rot": (flop("hx", t) - 0.5 * pitch(t), flop("hy", t) - 0.4 * yaw(t),
                               flop("hz", t) - 0.6 * roll)}}
        # loose counter-swing: each arm follows the opposite foot, plus the floppy follower
        ch["right_arm"] = {"rot": (0.9 * fl[2] * (0.7 + 0.5 * ka), 0.0, 5.0 + 9.0 * ka + flop("ar", t))}
        ch["left_arm"] = {"rot": (0.9 * fr[2] * (0.7 + 0.5 * ka), 0.0, -5.0 - 9.0 * ka + flop("al", t))}
        ch["right_forearm"] = {"rot": (-12.0 - 8.0 * ka * (0.5 + 0.5 * per(2, t)), 0.0, 0.0)}
        ch["left_forearm"] = {"rot": (-12.0 - 8.0 * ka * (0.5 + 0.5 * per(2, t + 0.4)), 0.0, 0.0)}
        lk.leg_ik(ch, (fr, fl), solve.prev, knee_out=0.18)
        pr = lk.lagged_rate(lambda u: pitch(u % L), t)
        yr = lk.lagged_rate(lambda u: yaw(u % L), t)
        vy = lk.lagged_rate(lambda u: root_y(u % L), t, lag=0.03)
        ch["cloak"] = {"rot": lk.cloak(3.0, pitch(t), pr, yr, root_vy=vy, k_pitch=0.4, k_yaw=0.08, k_vy=0.9)}
        return ch
    solve.prev = {}
    return solve


def walk_bpc(slug):
    s = WALKS[slug]
    return s["v"] * s["L"] / 16.0


# =========================================================================== STUMBLE (additive)
def make_stumble(variant):
    L = 1.6
    if variant == 1:
        lk.key({"tx": [(0.0, 0.0), (0.12, 3.0, *ACC), (0.3, 24.0, *DEC), (0.58, 12.0), (0.95, -6.0, *INOUT),
                       (1.25, 2.0), (L, 0.0)],
                "tz": [(0.0, 0.0), (0.3, -5.0), (0.7, 7.0), (1.1, -2.0), (L, 0.0)],
                "rz": [(0.0, 0.0), (0.3, -2.4), (0.62, -3.4), (1.0, -0.8), (L, 0.0)],     # root z (forward)
                "ry": [(0.0, 0.0), (0.3, -1.4, *DEC), (0.62, -0.6), (1.0, 0.2), (L, 0.0)],
                "rxp": [(0.0, 0.0), (L, 0.0)],                                             # root x
                "caught": [(0.0, 0.0), (0.12, 16.0, *ACC), (0.28, 6.0), (0.5, 0.0), (L, 0.0)],
                "catch": [(0.0, 0.0), (0.3, 0.0), (0.5, -34.0, *DEC), (0.78, -8.0), (1.1, 0.0), (L, 0.0)],
                "wm": [(0.0, 0.0), (0.26, 0.0, *ACC2), (1.05, 1.0, *DEC), (L, 1.0)]}, cyclic=False)
    else:
        lk.key({"tx": [(0.0, 0.0), (0.2, 6.0), (0.55, 3.0), (1.0, -3.0), (L, 0.0)],
                "tz": [(0.0, 0.0), (0.14, 4.0), (0.36, -20.0, *DEC), (0.75, 12.0, *INOUT), (1.15, -4.0), (L, 0.0)],
                "rz": [(0.0, 0.0), (0.3, -0.8), (L, 0.0)],
                "ry": [(0.0, 0.0), (0.2, -0.8), (0.36, 1.4, *DEC), (0.5, 0.0, *ACC2), (0.7, -0.8), (1.0, 0.0), (L, 0.0)],
                "rxp": [(0.0, 0.0), (0.2, 0.0), (0.4, -2.6, *DEC), (0.62, 1.6), (1.0, -0.4), (L, 0.0)],
                "caught": [(0.0, 0.0), (0.14, 10.0), (0.3, 0.0), (L, 0.0)],
                "catch": [(0.0, 0.0), (0.36, -18.0, *DEC), (0.6, 4.0), (0.9, 0.0), (L, 0.0)],
                "wm": [(0.0, 0.0), (0.2, 0.0, *ACC2), (0.95, 0.7, *DEC), (L, 0.7)]}, cyclic=False)

    def tx(u):
        return c("tx", u)

    def tz(u):
        return c("tz", u)

    flop = Floppy(L, lambda t: {"ax": 0.0, "az": 0.0, "hx": 0.0, "hz": 0.0},
                  lambda t: {"ax": 0.55 * d2(tx, t), "az": 0.55 * d2(tz, t),
                             "hx": 0.7 * d2(tx, t), "hz": 0.7 * d2(tz, t)},
                  omega=8.0, zeta=0.25,
                  clamp={"ax": (-50, 50), "az": (-40, 40), "hx": (-35, 30), "hz": (-30, 30)})

    def env(t):
        return lk.smoothstep(t / 0.08) * (1.0 - lk.smoothstep((t - (L - 0.2)) / 0.2))

    def solve(t):
        wm = c("wm", t)
        amp = 2.0 * min(1.0, 4.0 * wm) * (1.0 - wm)             # windmill strength, dies out
        wr = 2.0 * math.pi * wm
        out = math.sin(math.pi * min(1.0, wm * 1.3))
        ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("rxp", t), c("ry", t), c("rz", t))},
              "torso": {"rot": (tx(t), 0.0, tz(t))},
              "head": {"rot": (-0.5 * tx(t) + flop("hx", t), 0.0, -0.4 * tz(t) + flop("hz", t))},
              "right_arm": {"rot": (flop("ax", t) - 60.0 * math.sin(wr) * amp, 0.0, flop("az", t) + 40.0 * out)},
              "left_arm": {"rot": (0.8 * flop("ax", t) - 45.0 * math.sin(wr + 1.4) * amp, 0.0,
                                   flop("az", t) - 45.0 * out)},
              "right_forearm": {"rot": (-20.0 * math.sin(math.pi * wm), 0.0, 0.0)},
              "left_forearm": {"rot": (-16.0 * math.sin(math.pi * wm), 0.0, 0.0)},
              "right_leg": {"rot": (c("caught", t), 0.0, 0.0)},
              "left_leg": {"rot": (c("catch", t), 0.0, 0.0)},
              "left_shin": {"rot": (max(0.0, -0.7 * c("catch", t)), 0.0, 0.0)},
              "cloak": {"rot": (max(-16.0, min(20.0, 0.5 * tx(t) - 0.02 * lk.lagged_rate(tx, t))), 0.0,
                                -0.3 * tz(t))}}
        e = env(t)
        for kinds in ch.values():
            for k, v in list(kinds.items()):
                kinds[k] = tuple(x * e for x in v)
        return ch
    solve.prev = {}
    return solve


# =========================================================================== falls
def make_fall(kind):
    L = 5.0
    fwd = kind == "forward"
    GROAN = 1.95 if fwd else 2.05
    UP0 = 2.45 if fwd else 2.55
    if fwd:
        lk.key({
            # whole-body tip about the feet: slow start, gravity ease-in, a bounce, then get up
            "rx": [(0.0, 0.0), (0.3, 7.0), (IMPACT, 84.0, *ACC), (1.06, 80.0, *DEC), (1.2, 84.0), (1.34, 83.0),
                   (UP0, 83.0, *INOUT), (3.2, 34.0), (4.0, 6.0), (4.55, 0.0), (L, 0.0)],
            "rz": [(0.0, 0.0), (0.3, 3.0), (IMPACT, 6.0), (UP0, 6.0), (3.3, 2.0), (4.5, -2.0), (L, 0.0)],
            "ry": [(0.0, 0.0), (L, 0.0)],
            "hop": [(0.0, 0.0), (IMPACT, 0.0, *DEC), (1.04, 1.2), (1.16, 0.0, *ACC2), (1.26, 0.35), (1.34, 0.0),
                    (L, 0.0)],
            "kn": [(0.0, 0.0), (0.3, 34.0, *DEC), (0.7, 14.0), (IMPACT, 2.0), (UP0, 4.0), (3.2, 92.0),
                   (4.0, 58.0), (4.55, 6.0), (L, 0.0)],
            "tl": [(0.0, 0.0), (0.3, 14.0), (IMPACT, 2.0), (UP0, 4.0), (3.2, 40.0), (4.0, 38.0), (4.55, 4.0),
                   (L, 0.0)],
            "cheek": [(0.0, 0.0), (1.1, 0.0, *INOUT), (1.5, 1.0), (UP0 - 0.1, 1.0, *INOUT), (UP0 + 0.3, 0.0),
                      (L, 0.0)],
        }, cyclic=False)
    else:
        lk.key({
            "rx": [(0.0, 0.0), (0.3, 5.0), (IMPACT, 8.0), (UP0, 8.0), (3.3, 30.0), (4.05, 6.0), (4.55, 0.0), (L, 0.0)],
            "rz": [(0.0, 0.0), (0.3, 5.0), (IMPACT, -80.0, *ACC), (1.06, -74.0, *DEC), (1.2, -79.0), (1.34, -78.0),
                   (UP0, -78.0, *INOUT), (3.3, -6.0), (4.05, 3.0), (4.55, -2.0), (L, 0.0)],
            "ry": [(0.0, 0.0), (0.3, 10.0), (IMPACT, 58.0, *DEC), (UP0, 58.0, *INOUT), (3.3, 22.0), (4.55, 0.0),
                   (L, 0.0)],
            "hop": [(0.0, 0.0), (IMPACT, 0.0, *DEC), (1.04, 1.0), (1.16, 0.0, *ACC2), (1.26, 0.3), (1.34, 0.0),
                    (L, 0.0)],
            "kn": [(0.0, 0.0), (0.3, 40.0, *DEC), (IMPACT, 22.0), (UP0, 26.0), (3.3, 92.0), (4.05, 58.0),
                   (4.55, 6.0), (L, 0.0)],
            "tl": [(0.0, 0.0), (0.3, 14.0), (IMPACT, 10.0), (UP0, 10.0), (3.3, 40.0), (4.05, 38.0), (4.55, 4.0),
                   (L, 0.0)],
            "cheek": [(0.0, 0.0), (L, 0.0)],
        }, cyclic=False)
    lk.key({"reach": [(0.0, 0.0), (UP0 - 0.15, 0.0, *INOUT), (UP0 + 0.35, 1.0), (UP0 + 0.85, 1.0, *INOUT),
                      (UP0 + 1.25, 0.0), (L, 0.0)],
            "kneehand": [(0.0, 0.0), (UP0 + 0.95, 0.0, *INOUT), (UP0 + 1.35, 1.0), (UP0 + 1.85, 1.0, *INOUT),
                         (min(L - 0.3, UP0 + 2.2), 0.0), (L, 0.0)],
            "groan": [(0.0, 0.0), (GROAN - 0.3, 0.0, *INOUT), (GROAN, 1.0), (GROAN + 0.45, 0.0), (L, 0.0)],
            "wob": [(0.0, 0.0), (4.25, 0.0), (4.5, 1.0), (4.95, 0.0), (L, 0.0)],
            "down": [(0.0, 0.0), (0.7, 0.0, *INOUT), (1.2, 1.0), (UP0 - 0.2, 1.0, *INOUT), (UP0 + 0.2, 0.0),
                     (L, 0.0)]},
           cyclic=False)

    def rx(u):
        return c("rx", u)

    def rz(u):
        return c("rz", u)

    # limp arms + head, flung by the body's angular acceleration (the hard stop at impact flops
    # them); clamps stand in for the ground / chest stopping a limb.
    flop = Floppy(L, lambda t: {"ra": 0.0, "la": 0.0, "raz": 0.0, "laz": 0.0, "hx": 0.0, "hz": 0.0},
                  lambda t: {"ra": 0.9 * d2(rx, t), "la": 0.8 * d2(rx, t),
                             "raz": -0.8 * d2(rz, t), "laz": -0.7 * d2(rz, t),
                             "hx": 0.7 * d2(rx, t), "hz": 0.7 * d2(rz, t)},
                  omega=7.0, zeta=0.22,
                  clamp={"ra": (-60, 75), "la": (-60, 75), "raz": (-35, 60), "laz": (-60, 35),
                         "hx": (-45, 6), "hz": (-35, 35)})

    def solve(t):
        kn = c("kn", t)
        reach = max(0.0, min(1.0, c("reach", t)))
        kh = max(0.0, min(1.0, c("kneehand", t)))
        down = max(0.0, min(1.0, c("down", t)))
        wob = c("wob", t) * math.sin(2 * math.pi * 3.0 * t)
        groan = c("groan", t)
        ch = {"root": {"rot": (rx(t), c("ry", t), rz(t) + 4.0 * wob), "pos": (0.0, 0.0, 0.0)},
              "torso": {"rot": (c("tl", t) + 6.0 * groan * (1 - down), 0.0, 3.0 * wob),
                        "scale": lk.breath_scale(lk.breath(t, 1.3), 0.03)},
              "head": {"rot": (-0.5 * c("tl", t) + flop("hx", t) - 16.0 * groan,
                               -70.0 * c("cheek", t), flop("hz", t) + 5.0 * wob)},
              "right_leg": {"rot": (-0.55 * kn, 0.0, 2.0 * down)},
              "left_leg": {"rot": (-0.45 * kn + 6.0 * down, 0.0, -3.0 * down)},
              "right_shin": {"rot": (kn, 0.0, 0.0)},
              "left_shin": {"rot": (0.85 * kn + 10.0 * down, 0.0, 0.0)}}
        # limp arms (lying: splayed flat in the ground plane), IK hands for the get-up
        ch["right_arm"] = {"rot": (flop("ra", t), 0.0, 8.0 + flop("raz", t) + 26.0 * down)}
        ch["left_arm"] = {"rot": (flop("la", t), 0.0, -8.0 + flop("laz", t) - 22.0 * down)}
        ch["right_forearm"] = {"rot": (-8.0 - 10.0 * down, 0.0, 0.0)}
        ch["left_forearm"] = {"rot": (-12.0 - 8.0 * down, 0.0, 0.0)}
        if reach > 0 or kh > 0:
            ground(ch)
            w = mcrig.pose_matrices(ch)
            for side, sx, pole in (("right", -1.0, (-1.0, -0.2, 0.3)), ("left", 1.0, (1.0, -0.2, 0.3))):
                # blend in TARGET space (from where the limp palm is) - Euler mixing flips
                sh = mcrig.xform(w["torso"], lk.SHOULDER[side])
                g_ground = np.array([sh[0] + 1.0 * sx, G - 0.4, sh[2] - 2.0])      # palms on the ground
                g_knee = mcrig.xform(w[side + "_leg"], (0.0, 6.0, -2.4)) + np.array([0.0, -0.6, -0.4])
                goal = (reach * g_ground + kh * g_knee) / (reach + kh)
                limp = lk.palm(w, side)
                tgt = limp + (goal - limp) * max(reach, kh)
                reach_px = np.linalg.norm(tgt - sh)
                if reach_px > 9.6:      # out of reach (standing up): the hand slides up the thigh
                    tgt = sh + (tgt - sh) * (9.6 / reach_px)
                lk.arm_ik(ch, w, side, tgt, pole, solve.prev)
            ch["root"]["pos"] = (0.0, 0.0, 0.0)
        ch["cloak"] = {"rot": (max(-12.0, min(24.0, 4.0 + 0.3 * c("tl", t) + 14.0 * down)), 0.0, 0.0)}
        ground(ch, hop=c("hop", t))
        return lk.edge_rest(ch, t, L, ramp=0.08)
    solve.prev = {}
    return solve


# =========================================================================== lean + sit
def make_lean():
    L = 4.0
    lk.key({"sw": [(0.0, 0.0), (1.0, 1.0), (2.0, 0.0), (3.0, -1.0), (L, 0.0)],
            "hd": [(0.0, 0.3), (1.3, 1.0), (2.2, 0.4), (2.6, 0.2), (3.3, 0.9), (L, 0.3)],
            "sl": [(0.0, 0.0), (1.6, 1.0), (2.4, 1.0), (L, 0.0)]}, cyclic=True)

    def solve(t):
        t %= L
        sw, hd, sl = c("sw", t), c("hd", t), c("sl", t)
        ch = {"root": {"rot": (0.0, 4.0, -7.0 - 1.5 * sw), "pos": (0.0, -0.6 - 0.8 * sl, 0.0)},
              "torso": {"rot": (6.0 + 3.0 * hd, -6.0 + 2.0 * sw, -4.0 - 1.0 * sw),
                        "scale": lk.breath_scale(lk.breath(t, 2.0), 0.03)},
              "head": {"rot": (16.0 + 14.0 * hd, 5.0 * sw, -12.0 - 4.0 * hd)},    # resting against the wall
              "right_arm": {"rot": (-4.0, 0.0, 2.0)},
              "right_forearm": {"rot": (-10.0, 0.0, 0.0)},
              "left_arm": {"rot": (-24.0 + 4.0 * sw, 0.0, 10.0)},                  # hand on the belly
              "left_forearm": {"rot": (-58.0, 0.0, 0.0)}}
        wall_touch(ch)
        rp = ch["root"]["pos"]
        feet = (np.array([rp[0] - 2.8, G, 1.0]), np.array([rp[0] + 3.6, G, -1.4]))   # feet out from the wall
        lk.leg_ik(ch, feet, solve.prev, knee_out=0.2)
        ch["cloak"] = {"rot": (4.0 + 2.0 * hd, 0.0, 2.0 * sw)}
        return ch
    solve.prev = {}
    return solve


def make_sit():
    L = 6.0
    lk.key({"dn": [(0.0, 0.0), (0.25, 0.12), (0.9, 1.0, *ACC), (1.02, 0.94, *DEC), (1.14, 1.0), (4.5, 1.0, *INOUT),
                   (5.35, 0.25), (6.0, 0.0)],
            "hop": [(0.0, 0.0), (0.9, 0.0, *DEC), (1.0, 0.8), (1.12, 0.0), (L, 0.0)],
            "sw": [(0.0, 0.0), (1.4, 0.0), (2.2, 1.0), (3.0, -0.8), (3.8, 0.6), (4.5, 0.0), (L, 0.0)],
            "hd": [(0.0, 0.0), (1.2, 0.4), (2.4, 1.0), (3.2, 0.3), (3.6, 1.0), (4.4, 0.2), (L, 0.0)],
            "push": [(0.0, 0.0), (4.35, 0.0, *INOUT), (4.75, 1.0), (5.25, 1.0, *INOUT), (5.7, 0.0), (L, 0.0)]},
           cyclic=False)

    def solve(t):
        dn = max(0.0, min(1.0, c("dn", t)))
        sw, hd = c("sw", t), c("hd", t)
        push = max(0.0, min(1.0, c("push", t)))
        ch = {"root": {"rot": (0.0, 0.0, 3.0 * sw * dn), "pos": (0.0, 0.0, 2.0 * dn)},
              "torso": {"rot": (-10.0 * dn + 36.0 * push + 7.0 * hd * dn, 3.0 * sw, 5.0 * sw * dn),
                        "scale": lk.breath_scale(lk.breath(t, 2.2), 0.03)},
              "head": {"rot": (16.0 * hd * dn, -6.0 * sw, 7.0 * sw)},
              "right_leg": {"rot": (-86.0 * dn + 40.0 * push, 0.0, 9.0 * dn)},
              "left_leg": {"rot": (-80.0 * dn + 40.0 * push, 0.0, -7.0 * dn)},
              "right_shin": {"rot": (4.0 * dn + 40.0 * push, 0.0, 0.0)},
              "left_shin": {"rot": (14.0 * dn + 40.0 * push, 0.0, 0.0)},
              "right_arm": {"rot": (14.0 * dn - 50.0 * push, 0.0, 22.0 * dn)},      # propped on the floor
              "left_arm": {"rot": (-38.0 * dn - 30.0 * push + 6.0 * sw, 0.0, -8.0 * dn)},
              "right_forearm": {"rot": (-6.0, 0.0, 0.0)},
              "left_forearm": {"rot": (-40.0 * dn, 0.0, 0.0)},
              "cloak": {"rot": (8.0 * dn, 0.0, 0.0)}}
        ground(ch, hop=c("hop", t))
        return lk.edge_rest(ch, t, L, ramp=0.08)
    solve.prev = {}
    return solve


# =========================================================================== registry
CLIPS = {
    "tipsy_walk": dict(const="TIPSY_WALK", L=2.0, loop=True, make=lambda: make_walk("tipsy_walk"),
                       note="TIPSY_WALK 2.0 s loop (1 ale, WALK slot, distance clock): an ordinary gait with a "
                            "slight loose sway and head bob - no weave, no slowdown."),
    "drunk_walk": dict(const="DRUNK_WALK", L=2.4, loop=True, make=lambda: make_walk("drunk_walk"),
                       note="DRUNK_WALK 2.4 s loop (2 ales, WALK slot): short-long steps, wandering plant widths, "
                            "rolling torso, loose wide arms, head lolling (floppy follower)."),
    "very_drunk_walk": dict(const="VERY_DRUNK_WALK", L=2.8, loop=True, make=lambda: make_walk("very_drunk_walk"),
                            note="VERY_DRUNK_WALK 2.8 s loop (3+ ales, WALK slot): a lurching stagger with a quick "
                                 "half-step catch (1.05), big roll and yaw, head hanging, arms out."),
    "stumble": dict(const="STUMBLE", L=1.6, loop=False, make=lambda: make_stumble(1), keep=(0.0, 0.12, 1.6),
                    note="STUMBLE 1.6 s one-shot, ADDITIVE over the drunk walk (zero at both ends): the right foot "
                         "catches at 0.12 s (scuff), torso pitches, arms flail + windmill, a catch step at 0.5."),
    "stumble__v2": dict(const="STUMBLE__V2", L=1.6, loop=False, make=lambda: make_stumble(2),
                        variant_of="animation.settler.stumble", keep=(0.0, 0.14, 1.6),
                        note="STUMBLE variant (additive): a sideways lurch to the right, a hop to catch it."),
    "fall_forward": dict(const="FALL_FORWARD", L=5.0, loop=False, make=lambda: make_fall("forward"),
                         keep=(0.0, IMPACT, 1.95, 5.0),
                         note="FALL_FORWARD 5.0 s one-shot: knees buckle, a gravity tip onto the front, limp arms "
                              "and a lagging head (spring-damper), IMPACT 0.95 s (thud), groan 1.95, push up, "
                              "kneel, hands on knees, up, wobble. Ground contact solved per frame."),
    "fall_side": dict(const="FALL_SIDE", L=5.0, loop=False, make=lambda: make_fall("side"),
                      keep=(0.0, IMPACT, 2.05, 5.0),
                      note="FALL_SIDE 5.0 s one-shot: knees buckle, a twisting spin onto the right hip, IMPACT "
                           "0.95 s, groan 2.05, roll to the knees, up, wobble. Ground contact solved per frame."),
    "drunk_lean": dict(const="DRUNK_LEAN", L=4.0, loop=True, make=make_lean,
                       note="DRUNK_LEAN 4.0 s loop: the right shoulder and head against a wall (x = -8 px, contact "
                            "solved), a hand on the belly, slow sway and slump. Server faces the wall to the right."),
    "drunk_sit": dict(const="DRUNK_SIT", L=6.0, loop=False, make=make_sit, keep=(0.0, 0.9, 6.0),
                      note="DRUNK_SIT 6.0 s one-shot: plops onto the floor (contact 0.9 s), sits swaying with the "
                           "head nodding, pushes up and stands. Ground contact solved per frame."),
}


class LazySolve:
    """The solve is built inside scene() (after hsrig.reset) because it keys its own curves and
    pre-simulates its followers from them."""

    def __init__(self, holder):
        self.holder = holder
        self.prev = {}

    def __call__(self, t):
        f = self.holder["solve"]
        f.prev = self.prev
        return f(t)


def sounds_for(slug):
    import tavern_sounds
    return tavern_sounds.CUES.get(slug)


def main():
    for slug, spec in CLIPS.items():
        if ONLY and slug not in ONLY:
            continue
        holder = {}

        def scene(maker=spec["make"], holder=holder, wall=(slug == "drunk_lean")):
            objs = lk.scene("settler_farmer.png")
            if wall:   # the wall the server puts on the settler's right (its face at x = -8 px)
                lk.box("wall", (-24, -24, -20), (16, 48, 40), (0.52, 0.52, 0.5, 1))
            holder["solve"] = maker()
            return objs
        cams = None
        if slug == "drunk_lean":   # look from the open (left) side, the wall behind the settler
            cams = lambda: lk.cameras(side=((3.9, -0.3, 1.1), (0.0, -0.3, 0.98)),
                                      front34=((2.7, -2.9, 1.45), (0.0, -0.15, 1.0)))
        extra = {"blocks_per_cycle": round(walk_bpc(slug), 4)} if slug in WALKS else {}
        if slug.startswith("stumble"):
            extra["additive"] = True
        s = dict(const=spec["const"], slug=slug, L=spec["L"], loop=spec["loop"], script="author_drunk.py",
                 scene=scene, keys=lambda: None, solve=LazySolve(holder), bones=mcrig.EXPORT_BONES,
                 keep=spec.get("keep", (0.0, spec["L"])), mugs=[], note=spec["note"], extra_meta=extra,
                 sounds=sounds_for(slug), cams=cams)
        if spec.get("variant_of"):
            s["variant_of"] = spec["variant_of"]
        tk.run_clip(s, A)


if __name__ == "__main__":
    main()
