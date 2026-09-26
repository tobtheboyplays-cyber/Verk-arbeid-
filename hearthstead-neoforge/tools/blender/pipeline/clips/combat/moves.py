"""Guard moveset key definitions, v2 "overkill but grounded" (owner 2026-09-26).

Shared so combo links start on the exact pose the previous link hands over.
Each setup_*() keys the CTRL curves for one clip and returns
(length, loop, keep_times, contract, contact_t, goal_log).

Timing contract (combat-gameplay agent, GuardMove.java; 20 Hz ticks) -- UNCHANGED:
  MELEE (light A)        0.50 s, hit 0.20 (tick 4), hit-stop 0.20-0.25; B chains at 0.30
  GUARD_LIGHT_SLASH_B    0.50 s, hit 0.20, hit-stop 0.20-0.25; starts on MELEE@0.30,
                         back on the stance by 0.41 (finisher chains at B tick 8)
  GUARD_FINISHER_DRIVE   0.70 s, hit 0.20, hit-stop 0.20-0.30; starts from stance
  GUARD_HEAVY_OVERHEAD   1.10 s, hit 0.55 (tick 11), hang 0.40-0.55, hit-stop 0.55-0.65
  GUARD_SHIELD_BASH      0.50 s, hit 0.15 (tick 3), hit-stop 0.15-0.20
  GUARD_STAGGER          0.60 s, no hit, full body
  GUARD_HIT_REACT        0.30 s, full body

v2 grammar: hips lead the spine by ~2 ticks with real torque (up to ~35 deg of
separation), the weight visibly transfers (pelvis shift + drop, knees load),
every committed swing takes a short STEP with the lead foot and a recovery step
back, the right wrist bone rolls the blade edge-first through each cut
(combatkit.key_edge_roll), the heavy snaps the wrist at impact, the bash leads
with the shield rim behind a shoulder charge. Strikes are additive over
GUARD_STANCE (start/end = stance); STAGGER / HIT_REACT are absolute holds.
Foot props (fl_*/fr_*) are sole-centre offsets in px (z < 0 = forward, y = lift).
"""
import numpy as np

import combatkit as ck
import hsrig

c = hsrig.ctrl
ACC, ACC4, DEC, DEC3, LIN, SMO = ck.ACC, ck.ACC4, ck.DEC, ck.DEC3, ck.LIN, ck.SMO
BODY = ("hip_x", "hip_y", "hip_z", "hip_p", "hip_yaw", "hip_r", "sp_x", "sp_y", "sp_z", "sp_lift",
        "hd_x", "hd_y", "hd_z", "ck_x", "ck_z", "gl_w",
        "wr_x", "wr_y", "wr_z", "wl_x", "wl_y", "wl_z")
ALL_PROPS = BODY + ck.ARM_PROPS["right"] + ck.ARM_PROPS["left"] + ck.FEET_PROPS + ("wr_roll",)


def rel(B, prop, pts):
    """[(t, delta[, ease])] relative to the stance value -> absolute keys."""
    return [(p[0], B.get(prop, 0.0) + p[1], *p[2:]) for p in pts]


def body_at_factory():
    def body_at(t):
        return ck.body_channels(lambda p: c(p, t))
    return body_at


def key_body(B, D, length):
    """D: {prop: [(t, delta, *ease)]} relative to stance. Missing props hold the stance value."""
    K = {}
    for p in BODY:
        if p in D:
            K[p] = rel(B, p, D[p])
        else:
            K[p] = [(0.0, B.get(p, 0.0)), (length, B.get(p, 0.0))]
    for p in ck.FEET_PROPS:
        K[p] = D.get(p, [(0.0, 0.0), (length, 0.0)])
    ck.key_all(K, length, False)


def key_arm_fk(B, side, D, length):
    for p in ck.ARM_PROPS[side]:
        ck.key_all({p: rel(B, p, D.get(p, [(0.0, 0.0), (length, 0.0)]))}, length, False)


def snapshot(t):
    return {p: c(p, t) for p in ALL_PROPS}


def head_follow(B, D, gain=0.85):
    """Head yaw keeps the eyes on the target while hips/spine twist: derived keys."""
    ts = sorted(set(k[0] for k in D.get("hip_yaw", []) + D.get("sp_y", [])))
    out = []
    for t in ts:
        def val(prop):
            ks = D.get(prop, [(0.0, 0.0)])
            if t <= ks[0][0]:
                return ks[0][1]
            for a, b in zip(ks, ks[1:]):
                if a[0] <= t <= b[0]:
                    u = (t - a[0]) / max(b[0] - a[0], 1e-9)
                    return a[1] + (b[1] - a[1]) * u
            return ks[-1][1]
        out.append((t, -gain * (val("hip_yaw") + val("sp_y"))))
    return out


def solve_goals(B, goals, body_at, start=None, side="right", w_dir=4.0, w_seed=0.0):
    """Arm FK keys from world goals; hand None = the exact stance solution.
    start: optional absolute first key (combo hand-over), eased out of."""
    props = ck.ARM_PROPS[side]
    rest = [B[p] for p in props]
    seed = list(rest) if start is None else [start[p] for p in props]
    keys = {p: [] if start is None else [(0.0, start[p], *DEC)] for p in props}
    log = []
    for t, hand, d, tip, e in goals:
        if hand is None:
            sol, err = list(rest), 0.0
        else:
            sol, err = ck.solve_arm(body_at(t), side, hand, d, seed=seed, tip_goal=tip, w_dir=w_dir,
                                    pole_out=2.0, w_seed=w_seed)
        seed = sol
        log.append({"t": t, "sol": [round(v, 2) for v in sol], "cost": round(err, 3)})
        for p, v in zip(props, sol):
            keys[p].append((t, v, *e) if e else (t, v))
    ck.key_all(keys, goals[-1][0], False)
    return log


# =========================================================================== MELEE / light A
def setup_melee(B):
    """Forehand diagonal, high right -> low left. Hips uncoil two ticks ahead of the
    shoulders, the lead foot steps in under the cut, blade edge-first."""
    L = 0.5
    D = {
        "hip_x": [(0.0, 0.0), (0.09, -0.7, *ACC), (0.20, 0.8, *DEC), (0.26, 0.85), (0.34, 0.6, *SMO),
                  (0.44, -0.05, *SMO), (0.5, 0.0)],
        "hip_y": [(0.0, 0.0), (0.09, 0.15, *ACC), (0.20, -1.1, *DEC), (0.26, -1.15), (0.34, -0.9, *SMO),
                  (0.44, 0.05, *SMO), (0.5, 0.0)],
        "hip_z": [(0.0, 0.0), (0.09, 0.5, *ACC), (0.20, -1.0, *DEC), (0.30, -0.95, *SMO), (0.5, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.08, 15.0, *ACC), (0.18, -13.0, *DEC), (0.26, -14.0), (0.34, -10.0, *SMO),
                    (0.45, 0.8, *SMO), (0.5, 0.0)],
        "sp_y": [(0.0, 0.0), (0.10, 22.0, *ACC), (0.20, -18.0, *DEC), (0.25, -19.0), (0.30, -18.0, *SMO),
                 (0.44, 1.2, *SMO), (0.5, 0.0)],
        "sp_x": [(0.0, 0.0), (0.10, -4.0, *ACC), (0.20, 9.0, *DEC), (0.25, 9.5), (0.30, 9.0, *SMO),
                 (0.44, -0.5, *SMO), (0.5, 0.0)],
        "sp_z": [(0.0, 0.0), (0.10, 5.0, *ACC), (0.20, -6.0, *DEC), (0.30, -5.0, *SMO), (0.5, 0.0)],
        "hd_x": [(0.0, 0.0), (0.10, -1.5), (0.20, 4.0), (0.3, 3.5, *SMO), (0.5, 0.0)],
        "hd_z": [(0.0, 0.0), (0.10, -3.0), (0.20, 3.5), (0.3, 3.0, *SMO), (0.5, 0.0)],
        # lead (left) foot steps in under the cut, then a recovery step back
        "fl_z": [(0.0, 0.0), (0.06, 0.0, *SMO), (0.17, -2.0, *DEC), (0.36, -2.0, *SMO), (0.46, 0.0, *SMO), (0.5, 0.0)],
        "fl_y": [(0.0, 0.0), (0.06, 0.0), (0.115, 0.9), (0.17, 0.0), (0.36, 0.0), (0.41, 0.6), (0.46, 0.0), (0.5, 0.0)],
        "fr_z": [(0.0, 0.0), (0.12, 0.0, *SMO), (0.24, -0.5, *SMO), (0.38, -0.5, *SMO), (0.48, 0.0), (0.5, 0.0)],
    }
    D["hd_y"] = [(t, v, *SMO) for t, v in head_follow(B, D)]
    D["hd_y"][-1] = (0.5, 0.0)
    key_body(B, D, L)
    body_at = body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        # cock: hand behind the right ear, blade laid back over the shoulder
        (0.10, (-7.4, 0.6, 1.2), (-0.12, -0.45, 0.88), None, ACC),
        # contact: diagonal cut through the target 12-15 px ahead at chest height
        (0.20, (1.4, 7.4, -9.8), (0.62, 0.30, -0.72), None, None),
        (0.25, (2.0, 8.0, -9.3), (0.66, 0.36, -0.66), None, SMO),      # hit-stop (3 frames), eased release
        # follow-through low left (MELEE@0.30 = the pose LIGHT_SLASH_B starts on), clear of the belly
        (0.30, (2.8, 11.4, -8.2), (0.70, 0.66, -0.26), None, SMO),
        (0.42, (-2.6, 7.0, -6.6), (0.25, -0.64, -0.72), None, SMO),    # small overshoot past rest
        (0.50, None, None, None, None),
    ]
    log = solve_goals(B, goals, body_at)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.10, -22.0, *ACC), (0.20, 16.0, *DEC), (0.28, 17.0), (0.44, -1.0, *SMO), (0.5, 0.0)],
        "al_y": [(0.0, 0.0), (0.10, 8.0), (0.20, -8.0), (0.3, -6.0, *SMO), (0.5, 0.0)],
        "al_z": [(0.0, 0.0), (0.10, -5.0), (0.20, 6.0), (0.3, 4.0, *SMO), (0.5, 0.0)],
        "el_l": [(0.0, 0.0), (0.10, 12.0, *ACC), (0.20, -20.0, *DEC), (0.3, -17.0, *SMO), (0.5, 0.0)],
    }, L)
    roll = ck.key_edge_roll(L, 0.0, 0.0, tail=0.16)
    log.append({"edge_roll_every_0.1s": roll})
    return L, False, (0.10, 0.20, 0.25, 0.30), "0.50 s one-shot; blade contact t=0.20 s = tick 4 (GuardMeleeGoal.MELEE_CONTACT_TICK); hit-stop 0.20-0.25; LIGHT_SLASH_B chains from t=0.30", 0.20, log


# =========================================================================== LIGHT B
def setup_light_b(B, start):
    """Backhand rising slash, low left -> high right; torso counter-rotates vs A.
    Frame 0 = MELEE@0.30 (snapshot) exactly; the lead foot steps back home on the
    recovery so the finisher (B tick 8) starts from a clean stance."""
    L = 0.5

    def fromstart(prop, pts, absolute=False):
        v0 = start[prop] if absolute else start[prop] - B.get(prop, 0.0)
        return [(0.0, v0)] + pts

    D = {
        "hip_x": fromstart("hip_x", [(0.10, 0.9, *ACC), (0.20, -0.6, *DEC), (0.25, -0.65), (0.38, -0.1, *SMO), (0.5, 0.0)]),
        "hip_y": fromstart("hip_y", [(0.10, -1.2, *ACC), (0.20, -0.9, *DEC), (0.25, -0.9), (0.40, -0.05, *SMO), (0.5, 0.0)]),
        "hip_z": fromstart("hip_z", [(0.10, -1.0, *ACC), (0.20, 0.2, *DEC), (0.38, 0.0, *SMO), (0.5, 0.0)]),
        "hip_yaw": fromstart("hip_yaw", [(0.08, -17.0, *ACC), (0.18, 11.0, *DEC), (0.25, 11.5), (0.38, 1.5, *SMO), (0.5, 0.0)]),
        "sp_y": fromstart("sp_y", [(0.10, -24.0, *ACC), (0.20, 17.0, *DEC), (0.25, 17.5), (0.40, 1.0, *SMO), (0.5, 0.0)]),
        "sp_x": fromstart("sp_x", [(0.10, 11.0, *ACC), (0.20, 0.0, *DEC), (0.25, -0.5), (0.40, 0.2, *SMO), (0.5, 0.0)]),
        "sp_z": fromstart("sp_z", [(0.10, -6.0, *ACC), (0.20, 5.0, *DEC), (0.38, 0.5, *SMO), (0.5, 0.0)]),
        "hd_x": fromstart("hd_x", [(0.10, 4.5), (0.20, -1.5), (0.40, 0.0, *SMO), (0.5, 0.0)]),
        "hd_z": fromstart("hd_z", [(0.10, 3.0), (0.20, -3.0), (0.40, 0.0, *SMO), (0.5, 0.0)]),
        "fl_z": fromstart("fl_z", [(0.22, start["fl_z"], *SMO), (0.36, 0.0, *SMO), (0.5, 0.0)], True),
        "fl_y": fromstart("fl_y", [(0.22, 0.0), (0.29, 0.7), (0.36, 0.0), (0.5, 0.0)], True),
        "fr_z": fromstart("fr_z", [(0.10, -0.4, *SMO), (0.36, 0.0, *SMO), (0.5, 0.0)], True),
        "fr_y": fromstart("fr_y", [(0.5, 0.0)], True),
        "fl_x": fromstart("fl_x", [(0.5, 0.0)], True),
        "fr_x": fromstart("fr_x", [(0.5, 0.0)], True),
    }
    hy = head_follow(B, D)
    D["hd_y"] = [(t, v, *SMO) for t, v in hy]
    D["hd_y"][0] = (0.0, start["hd_y"] - B["hd_y"])
    D["hd_y"][-1] = (0.5, 0.0)
    for p in ("ck_x", "ck_z", "wr_x", "wr_y", "wr_z", "wl_x", "wl_y", "wl_z"):
        D[p] = [(0.0, start[p] - B.get(p, 0.0)), (0.5, 0.0)]
    key_body(B, D, L)
    body_at = body_at_factory()
    goals = [
        # draw: the blade turns over low left and trails behind the hip
        (0.05, (4.0, 12.4, -4.4), (0.55, 0.35, 0.76), None, None),
        (0.10, (4.4, 11.6, -2.8), (0.35, 0.10, 0.93), None, ACC),
        # contact: rising backhand through the target, low left -> high right
        (0.20, (-0.6, 6.6, -9.4), (-0.50, -0.50, -0.72), None, None),
        (0.25, (-1.5, 5.8, -9.1), (-0.55, -0.56, -0.62), None, SMO),   # eased release out of the hit-stop
        (0.33, (-2.4, 6.0, -9.2), (-0.12, -0.62, -0.77), None, SMO),
        (0.37, (-2.8, 6.6, -8.4), (0.05, -0.62, -0.78), None, SMO),   # recovery stays in front of the chest
        (0.41, None, None, None, SMO),      # back on the stance by tick 8 (finisher hand-off)
        (0.50, None, None, None, None),
    ]
    log = solve_goals(B, goals, body_at, start=start)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, start["al_x"] - B["al_x"]), (0.10, 14.0, *ACC), (0.20, -16.0, *DEC), (0.26, -16.5), (0.40, -0.5, *SMO), (0.5, 0.0)],
        "al_y": [(0.0, start["al_y"] - B["al_y"]), (0.10, -6.0), (0.20, 8.0), (0.4, 0.0, *SMO), (0.5, 0.0)],
        "al_z": [(0.0, start["al_z"] - B["al_z"]), (0.10, 4.0), (0.20, -6.0), (0.4, 0.0, *SMO), (0.5, 0.0)],
        "el_l": [(0.0, start["el_l"] - B["el_l"]), (0.10, -16.0, *ACC), (0.20, 10.0, *DEC), (0.4, 0.0, *SMO), (0.5, 0.0)],
        "tw_l": [(0.0, start["tw_l"] - B["tw_l"]), (0.5, 0.0)],
    }, L)
    roll = ck.key_edge_roll(L, start["wr_roll"], 0.0, tail=0.14)
    log.append({"edge_roll_every_0.1s": roll})
    return L, False, (0.10, 0.20, 0.25, 0.41), "0.50 s one-shot; starts on MELEE@0.30 s (combo tick 6); contact t=0.20 s = tick 4; hit-stop 0.20-0.25; on the stance by 0.41 (finisher chains at tick 8)", 0.20, log


# =========================================================================== FINISHER (combo 3 / low-health drive)
def setup_finisher(B):
    """Lunge-step-thrust: coil onto the rear foot, push off, the lead foot drives
    a long step, the hips and shoulder carry the point through, then the guard
    recovers two steps back into the stance."""
    L = 0.7
    D = {
        "hip_x": [(0.0, 0.0), (0.10, -0.4, *ACC), (0.20, 0.4, *DEC), (0.30, 0.45), (0.50, 0.15, *SMO), (0.7, 0.0)],
        "hip_y": [(0.0, 0.0), (0.10, -0.9, *ACC), (0.20, -2.0, *DEC), (0.30, -2.05), (0.50, -0.7, *SMO), (0.7, 0.0)],
        "hip_z": [(0.0, 0.0), (0.10, 1.4, *ACC), (0.20, -3.4, *DEC), (0.30, -3.45), (0.52, -0.8, *SMO), (0.7, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.08, 10.0, *ACC), (0.18, -12.0, *DEC), (0.30, -12.5), (0.5, -3.0, *SMO), (0.7, 0.0)],
        "sp_y": [(0.0, 0.0), (0.10, 14.0, *ACC), (0.20, -15.0, *DEC), (0.30, -15.5), (0.5, -3.5, *SMO), (0.7, 0.0)],
        "sp_x": [(0.0, 0.0), (0.10, -4.0, *ACC), (0.20, 18.0, *DEC), (0.30, 18.5), (0.5, 5.0, *SMO), (0.7, 0.0)],
        "sp_z": [(0.0, 0.0), (0.10, 2.0), (0.20, -3.0), (0.5, -0.5, *SMO), (0.7, 0.0)],
        "hd_x": [(0.0, 0.0), (0.10, 3.0), (0.20, -13.0), (0.30, -13.0), (0.5, -4.0, *SMO), (0.7, 0.0)],
        "fl_z": [(0.0, 0.0), (0.09, 0.0, *SMO), (0.19, -4.8, *DEC), (0.44, -4.8, *SMO), (0.56, 0.0, *DEC), (0.7, 0.0)],
        "fl_y": [(0.0, 0.0), (0.06, 0.0, *DEC), (0.11, 2.0, *SMO), (0.155, 1.6, *ACC), (0.19, 0.0), (0.41, 0.0, *DEC),
                 (0.46, 2.0, *SMO), (0.52, 1.4, *ACC), (0.56, 0.0), (0.7, 0.0)],
        "fr_z": [(0.0, 0.0), (0.14, 0.0, *SMO), (0.26, -1.6, *DEC), (0.47, -1.6, *SMO), (0.60, 0.0, *DEC), (0.7, 0.0)],
        "fr_y": [(0.0, 0.0), (0.11, 0.0, *DEC), (0.17, 0.8, *SMO), (0.23, 0.4, *ACC), (0.26, 0.0), (0.44, 0.0, *DEC),
                 (0.50, 0.8, *SMO), (0.56, 0.4, *ACC), (0.60, 0.0), (0.7, 0.0)],
    }
    D["hd_y"] = [(t, v, *SMO) for t, v in head_follow(B, D)]
    D["hd_y"][-1] = (0.7, 0.0)
    key_body(B, D, L)
    body_at = body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.11, (-5.0, 11.2, 1.6), (0.10, -0.16, -0.98), None, ACC),     # gather: hilt back at the hip
        (0.20, (-2.0, 10.2, -13.2), (0.04, -0.06, -1.0), None, None),   # drive: arm locks out
        (0.30, (-1.9, 10.3, -13.0), (0.04, -0.05, -1.0), None, DEC3),   # hit-stop 4->6
        (0.50, (-2.8, 8.6, -8.4), (0.14, -0.45, -0.88), None, SMO),
        (0.70, None, None, None, None),
    ]
    log = solve_goals(B, goals, body_at)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.10, -12.0, *ACC), (0.20, 20.0, *DEC), (0.30, 21.0), (0.5, 5.0, *SMO), (0.7, 0.0)],
        "al_z": [(0.0, 0.0), (0.10, -3.0), (0.20, 10.0), (0.30, 10.5), (0.5, 2.0, *SMO), (0.7, 0.0)],
        "el_l": [(0.0, 0.0), (0.10, 8.0), (0.20, -18.0), (0.5, -4.0, *SMO), (0.7, 0.0)],
    }, L)
    roll = ck.key_edge_roll(L, 0.0, 0.0, tail=0.2)
    log.append({"edge_roll_every_0.1s": roll})
    return L, False, (0.10, 0.20, 0.30), "0.70 s one-shot; lunge-thrust contact t=0.20 s = tick 4 (MELEE ticket); hit-stop 0.20-0.30; starts/ends on stance (also the low-health drive)", 0.20, log


# =========================================================================== HEAVY
def setup_heavy(B):
    """Two-handed overhead chop that could split a shield. Rises tall onto the rear
    foot with the lead knee lifted (the read-me hang), then stomps the lead foot
    down WITH the blade, drops the hips, wrist snaps through at impact, and the
    guard stays low and committed for the punish window.

    Overkill re-author 2026-09-26: the old 3-frame downswing (0.50 -> 0.55) had a
    shoulder-branch switch (10-12 px pops at 0.533 s) and the hands hung beside the
    ear so the left forearm crossed the face. Now: hands high IN FRONT of the brow
    at the hang (point back over the head, forearms clear of the face), a 0.09 s
    downswing keyed on its arc (blade up -> blade forward -> chop), the trunk whips
    over the same 0.09 s, and the recovery climbs back on keyed goals in front of
    the belly (no Euler short-cut through the body). Contact stays t=0.55 (tick 11)."""
    L = 1.1
    D = {
        "hip_x": [(0.0, 0.0), (0.25, -0.7, *SMO), (0.38, -0.85), (0.46, -0.85, *ACC), (0.55, 0.5, *DEC),
                  (0.65, 0.55), (0.85, 0.35, *SMO), (1.1, 0.0)],
        "hip_y": [(0.0, 0.0), (0.12, -0.5, *SMO), (0.38, 0.35), (0.46, 0.3, *ACC), (0.55, -2.2, *DEC),
                  (0.65, -2.3), (0.85, -1.3, *SMO), (1.1, 0.0)],
        "hip_z": [(0.0, 0.0), (0.30, 1.1, *SMO), (0.46, 1.2, *ACC), (0.55, -1.8, *DEC), (0.65, -1.9),
                  (0.9, -0.6, *SMO), (1.1, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.30, 13.0, *SMO), (0.42, 15.0), (0.46, 14.5, *ACC), (0.55, -6.0, *DEC),
                    (0.65, -7.0), (0.88, -2.0, *SMO), (1.1, 0.0)],
        "sp_x": [(0.0, 0.0), (0.12, 2.0, *SMO), (0.35, -12.0), (0.46, -13.0, *ACC), (0.55, 24.0, *DEC),
                 (0.65, 25.0), (0.85, 14.0, *SMO), (1.1, 0.0)],
        "sp_y": [(0.0, 0.0), (0.30, 12.0, *SMO), (0.46, 13.0, *ACC), (0.55, -7.0, *DEC), (0.65, -7.5),
                 (0.88, -2.0, *SMO), (1.1, 0.0)],
        "sp_z": [(0.0, 0.0), (0.35, 5.0, *SMO), (0.46, 5.2, *ACC), (0.55, -1.5, *DEC), (0.9, 0.0, *SMO), (1.1, 0.0)],
        "hd_x": [(0.0, 0.0), (0.35, 6.0, *SMO), (0.46, 7.0, *ACC), (0.55, -14.0, *DEC), (0.65, -15.0),
                 (0.85, -8.0, *SMO), (1.1, 0.0)],
        "gl_w": [(0.0, 0.0), (0.12, 0.0, *SMO), (0.26, 1.0), (0.72, 1.0, *SMO), (0.95, 0.0), (1.1, 0.0)],
        # wrist: cocked back at the top, SNAPS through at impact
        "wr_x": [(0.0, 0.0), (0.14, -8.0, *SMO), (0.38, -24.0), (0.46, -26.0, *ACC), (0.53, 24.0, *LIN),
                 (0.55, 46.0, *DEC), (0.65, 48.0), (0.85, 30.0, *SMO), (1.0, 8.0, *SMO), (1.1, 0.0)],
        # lead knee lifts through the hang, the foot STOMPS down with the blade
        "fl_z": [(0.0, 0.0), (0.30, 0.0, *SMO), (0.42, -1.0), (0.48, -1.4, *ACC), (0.55, -2.8), (0.85, -2.8, *SMO),
                 (1.0, 0.0, *SMO), (1.1, 0.0)],
        "fl_y": [(0.0, 0.0), (0.30, 0.0, *SMO), (0.40, 1.2), (0.47, 1.3, *ACC), (0.55, 0.0), (0.85, 0.0),
                 (0.92, 0.6), (1.0, 0.0), (1.1, 0.0)],
    }
    D["hd_y"] = [(t, v, *SMO) for t, v in head_follow(B, D, 0.85)]
    D["hd_y"][-1] = (1.1, 0.0)
    key_body(B, D, L)
    body_at = body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.14, (-4.0, 5.0, -6.5), (0.05, -0.98, -0.18), None, SMO),     # blade comes up vertical
        (0.28, (-5.2, -0.5, -6.8), (0.08, -0.92, 0.38), None, SMO),     # rising, point tipping back
        (0.38, (-5.4, -3.4, -6.2), (0.10, -0.66, 0.74), None, None),    # hang: hilt before the brow
        (0.46, (-5.2, -3.8, -6.0), (0.10, -0.60, 0.79), None, ACC),     # slow creep, no freeze
        (0.50, (-3.6, -3.4, -8.4), (0.06, -0.97, -0.05), None, LIN),    # downswing: blade up
        (0.53, (-2.4, 2.0, -8.4), (0.03, -0.45, -0.89), None, LIN),     # blade forward
        (0.55, (-1.8, 6.6, -7.8), (0.02, 0.22, -0.97), None, None),     # CHOP (wrist snapped +46)
        (0.65, (-1.7, 7.2, -7.7), (0.02, 0.31, -0.95), None, DEC3),     # 2-tick hit-stop
        (0.85, (-2.6, 8.8, -7.6), (0.06, 0.30, -0.95), None, SMO),      # committed: blade stays low
        (0.97, (-3.0, 8.2, -7.2), (0.14, -0.30, -0.94), None, SMO),     # point climbs back to the eyes
        (1.10, None, None, None, None),
    ]
    log = solve_goals(B, goals, body_at, w_seed=0.004)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.14, -40.0, *SMO), (0.9, -40.0, *SMO), (1.1, 0.0)],
        "el_l": [(0.0, 0.0), (0.14, -20.0, *SMO), (0.9, -20.0, *SMO), (1.1, 0.0)],
    }, L)
    roll = ck.key_edge_roll(L, 0.0, 0.0, tail=0.25)
    log.append({"edge_roll_every_0.1s": roll})
    return L, False, (0.14, 0.28, 0.38, 0.46, 0.50, 0.53, 0.55, 0.65, 0.85, 0.97), "1.10 s one-shot; telegraph hang 0.38-0.46 (lead knee up); downswing 0.46-0.55; chop contact t=0.55 s = tick 11 with foot stomp + wrist snap; hit-stop 0.55-0.65; recovery 0.65-1.10 (punish window)", 0.55, log


# =========================================================================== SHIELD BASH
def setup_bash(B):
    """Shoulder charge: the shield side winds back, the lead foot drives a step, the
    whole trunk turns the shoulder through and the shield RIM leads the punch.
    Fast and small in time, big in body. Sword stays tucked."""
    L = 0.5
    D = {
        "hip_x": [(0.0, 0.0), (0.08, -0.3, *ACC), (0.15, 0.7, *DEC), (0.20, 0.75), (0.34, 0.25, *SMO), (0.5, 0.0)],
        "hip_y": [(0.0, 0.0), (0.08, -0.6, *ACC), (0.15, -1.4, *DEC), (0.20, -1.45), (0.36, -0.4, *SMO), (0.5, 0.0)],
        "hip_z": [(0.0, 0.0), (0.08, 0.6, *ACC), (0.15, -2.8, *DEC), (0.20, -2.85), (0.38, -0.6, *SMO), (0.5, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.07, -10.0, *ACC), (0.13, 12.0, *DEC), (0.20, 12.5), (0.36, 2.0, *SMO), (0.5, 0.0)],
        "sp_y": [(0.0, 0.0), (0.08, -14.0, *ACC), (0.15, 17.0, *DEC), (0.20, 17.5), (0.36, 3.0, *SMO), (0.5, 0.0)],
        "sp_x": [(0.0, 0.0), (0.08, 4.0, *ACC), (0.15, 14.0, *DEC), (0.20, 14.5), (0.36, 4.0, *SMO), (0.5, 0.0)],
        "sp_z": [(0.0, 0.0), (0.08, -1.0), (0.15, 4.0), (0.36, 1.0, *SMO), (0.5, 0.0)],
        "hd_x": [(0.0, 0.0), (0.08, 4.0), (0.15, -8.0), (0.36, -2.0, *SMO), (0.5, 0.0)],
        # shield rim leads: the plate tips top-edge-first into the target
        "wl_x": [(0.0, 0.0), (0.08, -12.0, *ACC), (0.15, 26.0, *DEC), (0.20, 27.0), (0.36, 8.0, *SMO), (0.5, 0.0)],
        "fl_z": [(0.0, 0.0), (0.05, 0.0, *SMO), (0.14, -3.6, *DEC), (0.28, -3.6, *SMO), (0.40, 0.0, *DEC), (0.5, 0.0)],
        "fl_y": [(0.0, 0.0), (0.02, 0.0, *DEC), (0.07, 1.9, *SMO), (0.11, 1.4, *ACC), (0.14, 0.0), (0.25, 0.0, *DEC),
                 (0.31, 1.8, *SMO), (0.36, 1.2, *ACC), (0.40, 0.0), (0.5, 0.0)],
        "fr_z": [(0.0, 0.0), (0.06, 0.0, *SMO), (0.16, -1.0, *SMO), (0.26, -1.0, *SMO), (0.42, 0.0, *SMO), (0.5, 0.0)],
    }
    D["hd_y"] = [(t, v, *SMO) for t, v in head_follow(B, D)]
    D["hd_y"][-1] = (0.5, 0.0)
    key_body(B, D, L)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.08, -16.0, *ACC), (0.15, -58.0, *DEC), (0.20, -59.0), (0.36, -24.0, *SMO), (0.5, 0.0)],
        "al_y": [(0.0, 0.0), (0.08, 14.0, *ACC), (0.15, 24.0, *DEC), (0.20, 24.0), (0.36, 12.0, *SMO), (0.5, 0.0)],
        "al_z": [(0.0, 0.0), (0.08, 10.0), (0.15, 6.0), (0.36, 2.0, *SMO), (0.5, 0.0)],
        "el_l": [(0.0, 0.0), (0.08, -16.0, *ACC), (0.15, 42.0, *DEC), (0.20, 41.0), (0.36, 15.0, *SMO), (0.5, 0.0)],
    }, L)
    key_arm_fk(B, "right", {
        "ar_x": [(0.0, 0.0), (0.08, -4.0), (0.15, 8.0), (0.20, 8.0), (0.36, 1.0, *SMO), (0.5, 0.0)],
        "el_r": [(0.0, 0.0), (0.08, -5.0), (0.15, -14.0), (0.36, -3.0, *SMO), (0.5, 0.0)],
    }, L)
    return L, False, (0.08, 0.15, 0.20), "0.50 s one-shot; shield-rim contact t=0.15 s = tick 3 behind a lead-foot step; hit-stop 0.15-0.20", 0.15, []


# =========================================================================== STAGGER
def setup_stagger(B):
    """Rocked by a raider heavy: the blow knocks the lead foot back a stumble step,
    the trunk whips back, arms fly wide for balance, an off-balance wobble, then a
    step forward back into the stance. Full-body (absolute), stance at both ends."""
    L = 0.6
    D = {
        "hip_x": [(0.0, 0.0), (0.10, -0.8, *DEC), (0.22, 0.6, *SMO), (0.34, -0.4, *SMO), (0.46, 0.12, *SMO), (0.6, 0.0)],
        "hip_y": [(0.0, 0.0), (0.10, -2.0, *DEC), (0.22, -1.4, *SMO), (0.34, -1.7, *SMO), (0.48, -0.4, *SMO), (0.6, 0.0)],
        "hip_z": [(0.0, 0.0), (0.10, 2.6, *DEC), (0.24, 2.2, *SMO), (0.40, 1.2, *SMO), (0.6, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.10, 12.0, *DEC), (0.24, 3.0, *SMO), (0.36, 7.0, *SMO), (0.5, 1.5, *SMO), (0.6, 0.0)],
        "hip_r": [(0.0, 0.0), (0.10, -3.0, *DEC), (0.22, 3.0, *SMO), (0.34, -2.0, *SMO), (0.46, 0.6, *SMO), (0.6, 0.0)],
        "sp_x": [(0.0, 0.0), (0.08, -22.0, *DEC), (0.20, -12.0, *SMO), (0.32, -15.0, *SMO), (0.46, -3.0, *SMO), (0.6, 0.0)],
        "sp_y": [(0.0, 0.0), (0.08, 16.0, *DEC), (0.22, 4.0, *SMO), (0.34, 9.0, *SMO), (0.48, 1.5, *SMO), (0.6, 0.0)],
        "sp_z": [(0.0, 0.0), (0.08, 7.0, *DEC), (0.22, -5.0, *SMO), (0.34, 4.0, *SMO), (0.46, -1.0, *SMO), (0.6, 0.0)],
        "hd_x": [(0.0, 0.0), (0.06, -24.0, *DEC), (0.18, -8.0, *SMO), (0.30, -12.0, *SMO), (0.46, -1.5, *SMO), (0.6, 0.0)],
        "hd_y": [(0.0, 0.0), (0.06, 18.0, *DEC), (0.20, -5.0, *SMO), (0.34, 6.0, *SMO), (0.48, -1.0, *SMO), (0.6, 0.0)],
        "hd_z": [(0.0, 0.0), (0.06, -10.0, *DEC), (0.20, 6.0, *SMO), (0.34, -4.0, *SMO), (0.6, 0.0)],
        "ck_x": [(0.0, 0.0), (0.10, -18.0), (0.26, 10.0), (0.42, -3.0), (0.6, 0.0)],
        "wr_x": [(0.0, 0.0), (0.08, 18.0, *DEC), (0.24, 6.0, *SMO), (0.4, 8.0, *SMO), (0.6, 0.0)],
        "fl_z": [(0.0, 0.0), (0.04, 0.0), (0.14, 2.6, *DEC), (0.38, 2.6, *SMO), (0.52, 0.0, *SMO), (0.6, 0.0)],
        "fl_y": [(0.0, 0.0), (0.04, 0.0), (0.09, 1.2), (0.14, 0.0), (0.38, 0.0), (0.45, 0.9), (0.52, 0.0), (0.6, 0.0)],
        "fr_z": [(0.0, 0.0), (0.12, 0.8, *SMO), (0.40, 0.8, *SMO), (0.54, 0.0, *SMO), (0.6, 0.0)],
    }
    key_body(B, D, L)
    key_arm_fk(B, "right", {
        "ar_x": [(0.0, 0.0), (0.08, 30.0, *DEC), (0.22, 12.0, *SMO), (0.34, 18.0, *SMO), (0.48, 3.0, *SMO), (0.6, 0.0)],
        "ar_z": [(0.0, 0.0), (0.08, 34.0, *DEC), (0.22, 16.0, *SMO), (0.34, 22.0, *SMO), (0.48, 3.0, *SMO), (0.6, 0.0)],
        "el_r": [(0.0, 0.0), (0.08, 22.0, *DEC), (0.22, 10.0, *SMO), (0.4, 5.0, *SMO), (0.6, 0.0)],
    }, L)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.08, 16.0, *DEC), (0.22, -8.0, *SMO), (0.34, 6.0, *SMO), (0.48, -1.0, *SMO), (0.6, 0.0)],
        "al_z": [(0.0, 0.0), (0.08, -38.0, *DEC), (0.22, -18.0, *SMO), (0.34, -26.0, *SMO), (0.48, -4.0, *SMO), (0.6, 0.0)],
        "el_l": [(0.0, 0.0), (0.08, 28.0, *DEC), (0.22, 10.0, *SMO), (0.4, 4.0, *SMO), (0.6, 0.0)],
    }, L)
    return L, False, (0.08, 0.22, 0.34), "0.60 s one-shot, full body, no hit; stumble step back + recovery step; stance at both ends", None, []


# =========================================================================== HIT REACT
def setup_hit_react(B):
    """Sword-only impact response (empty offhand). Full-body hold: frame 0 is the
    stance at the instant of impact, the recoil peaks one tick later (no invented
    anticipation), the free hand flinches up to cover the face, the sword stays
    drawn, then the guard re-sets into the stance by 0.30 s."""
    L = 0.3
    D = {
        "hip_x": [(0.0, 0.0), (0.05, -0.35, *DEC), (0.14, -0.2, *SMO), (0.3, 0.0)],
        "hip_y": [(0.0, 0.0), (0.05, -0.9, *DEC), (0.14, -0.65, *SMO), (0.3, 0.0)],
        "hip_z": [(0.0, 0.0), (0.05, 1.2, *DEC), (0.14, 0.8, *SMO), (0.3, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.05, 6.0, *DEC), (0.14, 3.5, *SMO), (0.3, 0.0)],
        "sp_x": [(0.0, 0.0), (0.05, -10.0, *DEC), (0.14, -5.0, *SMO), (0.24, 0.8, *SMO), (0.3, 0.0)],
        "sp_y": [(0.0, 0.0), (0.05, 10.0, *DEC), (0.14, 6.0, *SMO), (0.3, 0.0)],
        "sp_z": [(0.0, 0.0), (0.05, -3.0, *DEC), (0.14, -1.5, *SMO), (0.3, 0.0)],
        "hd_x": [(0.0, 0.0), (0.04, -12.0, *DEC), (0.12, -5.0, *SMO), (0.24, 1.0, *SMO), (0.3, 0.0)],
        "hd_y": [(0.0, 0.0), (0.04, 10.0, *DEC), (0.14, 4.0, *SMO), (0.3, 0.0)],
        "hd_z": [(0.0, 0.0), (0.04, -4.0, *DEC), (0.14, -2.0, *SMO), (0.3, 0.0)],
        "ck_x": [(0.0, 0.0), (0.06, -10.0), (0.16, 5.0), (0.3, 0.0)],
    }
    key_body(B, D, L)
    key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.05, -58.0, *DEC), (0.14, -40.0, *SMO), (0.3, 0.0)],
        "al_y": [(0.0, 0.0), (0.05, -14.0, *DEC), (0.14, -10.0, *SMO), (0.3, 0.0)],
        "al_z": [(0.0, 0.0), (0.05, 10.0, *DEC), (0.14, 6.0, *SMO), (0.3, 0.0)],
        "el_l": [(0.0, 0.0), (0.05, -40.0, *DEC), (0.14, -28.0, *SMO), (0.3, 0.0)],
    }, L)
    key_arm_fk(B, "right", {
        "ar_x": [(0.0, 0.0), (0.05, 12.0, *DEC), (0.14, 7.0, *SMO), (0.3, 0.0)],
        "ar_z": [(0.0, 0.0), (0.05, 10.0, *DEC), (0.14, 6.0, *SMO), (0.3, 0.0)],
        "el_r": [(0.0, 0.0), (0.05, -14.0, *DEC), (0.14, -8.0, *SMO), (0.3, 0.0)],
    }, L)
    return L, False, (0.05, 0.14), "0.30 s one-shot, full body; recoil peak at tick 1, back on the stance at 0.30", None, []
