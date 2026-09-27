"""GUARD DRILL v2 clips (owner, 26 Sep: "smoother, more movement, more fighting").

Footwork that really moves the guard (the entity travels with GuardDrillScript.moveEase; the feet
are keyed in model space as world minus that travel, so planted feet stay put), the feint, the
stumble that gets laughed off, the nod, and the shorter breather. Used by guard_drill.py.
"""
import math

import combatkit as ck
import moves

ACC, ACC4, DEC, DEC3, LIN, SMO = ck.ACC, ck.ACC4, ck.DEC, ck.DEC3, ck.LIN, ck.SMO


def _common_tail(B, D, L):
    D["hd_y"] = [(t, v, *SMO) for t, v in moves.head_follow(B, D, gain=0.8)]
    D["hd_y"][0] = (0.0, 0.0)
    D["hd_y"][-1] = (L, 0.0)


def _smooth(u):
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


def setup_breather(B):
    """Breather (v2: short, 2.2 s): step back, sword point to the ground, wipe the brow with the
    back of the free hand, a quick exhale, step back in. Authored on the v1 3.0 s timeline and
    re-timed."""
    L, K = 2.2, 2.2 / 3.0

    def sc(keys):
        return [(k[0] * K,) + tuple(k[1:]) for k in keys]
    D = {
        "hip_z": [(0.0, 0.0), (0.45, 3.2, *DEC), (2.35, 3.2, *SMO), (2.85, 0.0, *DEC), (3.0, 0.0)],
        "hip_y": [(0.0, 0.0), (0.45, 0.35, *SMO), (1.6, 0.45, *SMO), (1.9, 0.1, *SMO), (2.35, 0.35, *SMO),
                  (3.0, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.45, -4.0, *SMO), (2.3, -4.0, *SMO), (3.0, 0.0)],
        "hip_r": [(0.0, 0.0), (0.5, 1.5, *SMO), (1.6, 1.5, *SMO), (2.1, -1.0, *SMO), (3.0, 0.0)],
        "sp_x": [(0.0, 0.0), (0.45, -3.0, *SMO), (0.9, -5.0, *SMO), (1.4, -2.0, *SMO), (1.75, 2.5, *SMO),
                 (2.2, -2.0, *SMO), (2.7, 0.0, *SMO), (3.0, 0.0)],
        "sp_z": [(0.0, 0.0), (1.6, 0.0, *SMO), (1.85, 4.0, *SMO), (2.1, -2.0, *SMO), (2.4, 0.0, *SMO), (3.0, 0.0)],
        "sp_lift": [(0.0, 0.0), (0.9, 0.35, *SMO), (1.75, -0.35, *SMO), (2.4, 0.1, *SMO), (3.0, 0.0)],
        "hd_x": [(0.0, 0.0), (0.7, 6.0, *SMO), (0.95, 10.0, *SMO), (1.35, 4.0, *SMO), (1.75, -6.0, *SMO),
                 (2.2, 2.0, *SMO), (3.0, 0.0)],
        "hd_z": [(0.0, 0.0), (1.6, 0.0, *SMO), (1.9, 6.0, *SMO), (2.2, -2.0, *SMO), (2.6, 0.0, *SMO), (3.0, 0.0)],
        "hd_y": [(0.0, 0.0), (0.7, 6.0, *SMO), (1.3, 10.0, *SMO), (2.0, 4.0, *SMO), (2.6, 0.0, *SMO), (3.0, 0.0)],
        "ck_x": [(0.0, 0.0), (0.5, 2.0, *SMO), (2.4, 2.0, *SMO), (3.0, 0.0)],
        "fr_z": [(0.0, 0.0), (0.05, 0.0), (0.28, 3.4, *DEC), (2.4, 3.4, *SMO), (2.72, 0.0, *DEC), (3.0, 0.0)],
        "fr_y": [(0.0, 0.0), (0.05, 0.0), (0.16, 1.1), (0.28, 0.0), (2.4, 0.0), (2.56, 0.9), (2.72, 0.0), (3.0, 0.0)],
        "fl_z": [(0.0, 0.0), (0.2, 0.0), (0.46, 3.0, *DEC), (2.56, 3.0, *SMO), (2.86, 0.0, *DEC), (3.0, 0.0)],
        "fl_y": [(0.0, 0.0), (0.2, 0.0), (0.33, 1.0), (0.46, 0.0), (2.56, 0.0), (2.71, 0.8), (2.86, 0.0), (3.0, 0.0)],
    }
    D = {k: sc(v) for k, v in D.items()}
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.50, (-6.6, 11.4, -2.0), (0.05, 0.75, -0.66), None, SMO),
        (2.30, (-6.6, 11.6, -1.6), (0.04, 0.78, -0.62), None, SMO),
        (2.75, (-3.6, 9.4, -5.0), (0.16, -0.20, -0.96), None, SMO),
        (3.00, None, None, None, None),
    ]
    goals = [(g[0] * K,) + tuple(g[1:]) for g in goals]
    log = moves.solve_goals(B, goals, body_at)
    lgoals = [
        (0.00, None, None, None, None),
        (0.55, (3.2, 4.6, -4.2), None, None, SMO),
        (0.95, (2.6, -4.8, -4.6), None, None, SMO),
        (1.35, (-1.8, -5.4, -4.4), None, None, SMO),
        (1.75, (3.4, 8.8, -1.6), None, None, SMO),
        (2.60, (3.2, 9.4, -2.2), None, None, SMO),
        (3.00, None, None, None, None),
    ]
    lgoals = [(g[0] * K,) + tuple(g[1:]) for g in lgoals]
    log += moves.solve_goals(B, lgoals, body_at, side="left", w_dir=0.0)
    return L, False, (0.7, 1.0), "2.20 s one-shot; step back, wipe the brow 0.70-1.00 s, step back in", None, log


def footwork(B, L, d, lead, extra=None, lift=1.1, body_lead=0.05):
    """Feet for a clip that MOVES the entity by d=(dx, dz) px (model space; +x = left, -z = forward)
    with GuardDrillScript.moveEase (smoothstep over 0.1-0.8 of the clip). In world space the lead
    foot steps over 0.10-0.45, the trail foot over 0.42-0.80; keyed in model space (world minus
    the entity's own eased travel), so planted feet stay put on the ground."""
    dx, dz = d
    D = dict(extra or {})

    def e(t):
        return _smooth((t / L - 0.1) / 0.7)

    def lead_w(t):
        return _smooth((t / L - 0.10) / 0.35)

    def trail_w(t):
        return _smooth((t / L - 0.42) / 0.38)

    def lift_of(w):
        return lift * math.sin(math.pi * w) if 0.0 < w < 1.0 else 0.0
    ts = [round(i * L / 16.0, 4) for i in range(17)]
    lp, tp = ("fl", "fr") if lead == "left" else ("fr", "fl")
    for pfx, w in ((lp, lead_w), (tp, trail_w)):
        D[pfx + "_x"] = [(t, w(t) * dx - e(t) * dx) for t in ts]
        D[pfx + "_z"] = [(t, w(t) * dz - e(t) * dz) for t in ts]
        D[pfx + "_y"] = [(t, lift_of(w(t))) for t in ts]
    # the body leads the feet a touch and dips on each transfer
    D.setdefault("hip_x", [(t, (e(min(L, t + body_lead)) - e(t)) * dx) for t in ts])
    D.setdefault("hip_z", [(t, (e(min(L, t + body_lead)) - e(t)) * dz) for t in ts])
    D.setdefault("hip_y", [(t, -0.45 * math.sin(math.pi * _smooth(t / L)) ** 2) for t in ts])
    return D


def _hold_arms(B, L, bob=1.2):
    """Sword stays on line while the feet work: a small rise and fall with the steps."""
    moves.key_arm_fk(B, "right", {"ar_x": [(0.0, 0.0), (L * 0.35, -bob, *SMO), (L * 0.7, 0.4, *SMO), (L, 0.0)],
                                  "el_r": [(0.0, 0.0), (L * 0.35, bob * 0.6, *SMO), (L, 0.0)]}, L)
    moves.key_arm_fk(B, "left", {"al_x": [(0.0, 0.0), (L * 0.4, -bob, *SMO), (L, 0.0)],
                                 "al_z": [(0.0, 0.0), (L * 0.4, -1.0, *SMO), (L, 0.0)]}, L)


def _setup_move(B, L, d, lead, turn=0.0):
    lean = 0.0 if d[0] == 0 else (-2.0 if d[0] > 0 else 2.0)
    extra = {
        "sp_z": [(0.0, 0.0), (L * 0.35, lean, *SMO), (L, 0.0)],
        "hip_yaw": [(0.0, 0.0), (L * 0.4, turn, *SMO), (L, 0.0)],
        "sp_x": [(0.0, 0.0), (L * 0.4, 1.5 if d[1] < 0 else -1.0, *SMO), (L, 0.0)],
        "hd_y": [(0.0, 0.0), (L * 0.4, -0.6 * turn, *SMO), (L, 0.0)],
    }
    D = footwork(B, L, d, lead, extra)
    moves.key_body(B, D, L)
    _hold_arms(B, L)
    return L, False, (), "%.2f s footwork; the entity travels (%.1f, %.1f) px with moveEase" % (L, d[0], d[1]), None, []


def setup_step_left(B):
    return _setup_move(B, 0.8, (8.0, 0.0), "left", turn=-5.0)


def setup_step_right(B):
    return _setup_move(B, 0.8, (-8.0, 0.0), "right", turn=5.0)


def setup_advance(B):
    return _setup_move(B, 0.7, (0.0, -6.0), "left")


def setup_retreat(B):
    return _setup_move(B, 0.7, (0.0, 6.0), "right")


def setup_feint(B):
    """Feint: a high cut starts (coil, weight in, half a step), is checked at 0.32 s before it
    commits, and the guard snaps back behind the blade with a small bounce."""
    L, hit = 0.8, 0.32
    D = {
        "hip_yaw": [(0.0, 0.0), (0.2, 12.0, *ACC), (0.32, 3.0, *DEC), (0.55, -1.0, *SMO), (0.8, 0.0)],
        "sp_y": [(0.0, 0.0), (0.2, 16.0, *ACC), (0.32, 4.0, *DEC), (0.55, -1.5, *SMO), (0.8, 0.0)],
        "sp_x": [(0.0, 0.0), (0.2, -3.0), (0.32, 6.0, *DEC), (0.55, 0.5, *SMO), (0.8, 0.0)],
        "hip_z": [(0.0, 0.0), (0.2, 0.4), (0.32, -1.6, *DEC), (0.55, 0.3, *SMO), (0.8, 0.0)],
        "hip_y": [(0.0, 0.0), (0.32, -0.8, *DEC), (0.5, -0.1, *SMO), (0.62, -0.5, *SMO), (0.8, 0.0)],
        "fl_z": [(0.0, 0.0), (0.12, 0.0, *SMO), (0.28, -2.0, *DEC), (0.5, -2.0, *SMO), (0.68, 0.0, *DEC), (0.8, 0.0)],
        "fl_y": [(0.0, 0.0), (0.12, 0.0), (0.2, 0.8), (0.28, 0.0), (0.5, 0.0), (0.59, 0.6), (0.68, 0.0), (0.8, 0.0)],
    }
    _common_tail(B, D, L)
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.20, (-6.4, 1.6, -0.6), (-0.10, -0.60, 0.79), None, ACC),     # cocked, as for the high cut
        (0.32, (-3.6, 3.8, -6.8), (0.05, -0.80, -0.60), None, DEC3),    # checked: the blade stops high
        (0.55, (-3.0, 8.2, -6.0), (0.18, -0.40, -0.90), None, SMO),     # back behind the point
        (0.80, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at, w_dir=8.0)
    moves.key_arm_fk(B, "left", {"al_x": [(0.0, 0.0), (0.2, -12.0, *ACC), (0.32, 4.0), (0.8, 0.0, *SMO)],
                                 "el_l": [(0.0, 0.0), (0.2, 8.0), (0.32, -6.0), (0.8, 0.0, *SMO)]}, L)
    return L, False, (0.2, hit), "0.80 s one-shot; the fake beat at 0.32 s, chains on at 0.52 s", hit, log


def setup_stumble(B):
    """Stumble: the blow lands on a late guard, the body is knocked back (the entity travels 6 px
    back), arms fly out for balance, a hop on the rear foot, then the guard finds the stance again
    and laughs it off (a NOD follows in the plan)."""
    L, hit = 1.4, 0.35
    extra = {
        "sp_x": [(0.0, 0.0), (0.2, -10.0, *DEC), (0.4, -16.0, *DEC), (0.7, -6.0, *SMO), (0.95, 3.0, *SMO),
                 (1.2, -1.0, *SMO), (1.4, 0.0)],
        "sp_y": [(0.0, 0.0), (0.3, -10.0, *DEC), (0.7, 4.0, *SMO), (1.1, -1.0, *SMO), (1.4, 0.0)],
        "sp_z": [(0.0, 0.0), (0.3, 7.0, *DEC), (0.6, -4.0, *SMO), (0.9, 2.0, *SMO), (1.4, 0.0)],
        "hd_x": [(0.0, 0.0), (0.2, -12.0, *DEC), (0.5, -4.0, *SMO), (0.8, 5.0, *SMO), (1.4, 0.0)],
        "hd_z": [(0.0, 0.0), (0.3, -6.0), (0.7, 4.0, *SMO), (1.4, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.3, -8.0, *DEC), (0.8, 3.0, *SMO), (1.4, 0.0)],
    }
    D = footwork(B, 1.4, (0.0, 6.0), "right", extra, lift=1.6, body_lead=0.12)
    D["hip_y"] = [(0.0, 0.0), (0.35, -1.4, *DEC), (0.6, -0.3, *SMO), (0.8, -0.9, *SMO), (1.1, -0.2, *SMO), (1.4, 0.0)]
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.30, (-8.4, 3.6, 1.6), (-0.60, -0.55, 0.58), None, DEC3),     # sword arm flung wide and up
        (0.70, (-6.0, 6.4, -2.4), (0.10, -0.70, -0.70), None, SMO),
        (1.05, (-3.6, 8.6, -5.2), (0.16, -0.38, -0.91), None, SMO),
        (1.40, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at, w_dir=3.0)
    moves.key_arm_fk(B, "left", {"al_x": [(0.0, 0.0), (0.3, -40.0, *DEC), (0.7, -12.0, *SMO), (1.4, 0.0)],
                                 "al_z": [(0.0, 0.0), (0.3, -38.0, *DEC), (0.7, -10.0, *SMO), (1.4, 0.0)],
                                 "el_l": [(0.0, 0.0), (0.3, 20.0), (0.8, 6.0, *SMO), (1.4, 0.0)]}, L)
    return L, False, (hit,), "1.40 s one-shot; knocked back 6 px (moveEase), furthest off balance 0.35 s", hit, log


def setup_nod(B):
    """Nod: the sword point drops, a nod and a chuckle (the shoulders bounce), a small open-hand
    gesture, then the guard comes back up."""
    L = 1.3
    D = {
        "hd_x": [(0.0, 0.0), (0.28, 12.0, *SMO), (0.45, 2.0, *SMO), (0.62, 10.0, *SMO), (0.85, 0.0, *SMO), (1.3, 0.0)],
        "hd_z": [(0.0, 0.0), (0.4, 4.0, *SMO), (0.9, -2.0, *SMO), (1.3, 0.0)],
        "sp_lift": [(0.0, 0.0), (0.35, 0.35), (0.45, 0.0), (0.55, 0.35), (0.65, 0.0), (0.75, 0.3), (0.9, 0.0),
                    (1.3, 0.0)],
        "sp_x": [(0.0, 0.0), (0.3, 3.0, *SMO), (0.9, 2.0, *SMO), (1.3, 0.0)],
        "hip_y": [(0.0, 0.0), (0.3, 0.3, *SMO), (1.0, 0.3, *SMO), (1.3, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.4, -3.0, *SMO), (1.3, 0.0)],
    }
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.30, (-6.2, 11.0, -2.6), (0.06, 0.74, -0.67), None, SMO),
        (0.95, (-6.4, 11.2, -2.2), (0.05, 0.76, -0.65), None, SMO),
        (1.30, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at)
    lgoals = [
        (0.00, None, None, None, None),
        (0.40, (3.4, 5.2, -6.4), None, None, SMO),     # open hand: "fair enough"
        (0.80, (3.2, 6.0, -5.8), None, None, SMO),
        (1.30, None, None, None, None),
    ]
    log += moves.solve_goals(B, lgoals, body_at, side="left", w_dir=0.0)
    return L, False, (0.3, 0.62), "1.30 s one-shot; nod and chuckle", None, log


CLIPS_V2 = {
    "GUARD_DRILL_BREATHER": setup_breather,
    "GUARD_DRILL_STEP_LEFT": setup_step_left,
    "GUARD_DRILL_STEP_RIGHT": setup_step_right,
    "GUARD_DRILL_ADVANCE": setup_advance,
    "GUARD_DRILL_RETREAT": setup_retreat,
    "GUARD_DRILL_FEINT": setup_feint,
    "GUARD_DRILL_STUMBLE": setup_stumble,
    "GUARD_DRILL_NOD": setup_nod,
}
