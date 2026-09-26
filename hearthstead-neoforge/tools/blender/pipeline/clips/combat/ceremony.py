"""Guard ceremony clips (owner 2026-09-26). No gameplay contact tick.

    blender -b --factory-startup --python guard_attention.py -- [--fast|--full] [--no-export]

GUARD_ATTENTION_SNAP  0.40 s one-shot  GUARD_STANCE -> attention: the rear foot steps
                                        in, the lead heel clicks home at 0.28 s, the
                                        spine straightens with a small proud overshoot,
                                        the sword comes upright at the right shoulder.
GUARD_ATTENTION       2.00 s loop      the attention hold: heels together, back
                                        straight, chin up, sword upright ("shoulder
                                        arms"), subtle breathing only.
GUARD_SALUTE          1.20 s one-shot  from attention: hilt raised to the face
                                        (knightly), a short bow of the head, the blade
                                        swept down and out to the right, back to
                                        attention. Ends exactly on GUARD_ATTENTION.
All absolute full-body clips. The right_item wrist bone keeps the blade truly
vertical at attention and turns the flat to the face in the salute.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import bpy  # noqa: E402
import numpy as np  # noqa: E402

import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

S, ACC, DEC, DEC3 = ck.SMO, ck.ACC, ck.DEC, ck.DEC3
c = hsrig.ctrl
ATT_FOOT_R = np.array([-2.5, 24.0, 0.2])
ATT_FOOT_L = np.array([2.5, 24.0, 0.2])
ATT = {"hip_x": 0.0, "hip_y": -0.1, "hip_z": 0.0, "hip_p": 0.0, "hip_yaw": 0.0, "hip_r": 0.0,
       "sp_x": -1.5, "sp_y": 0.0, "sp_z": 0.0, "sp_lift": 0.1,
       "hd_x": -4.0, "hd_y": 0.0, "hd_z": 0.0, "ck_x": 2.0, "ck_z": 0.0, "gl_w": 0.0,
       "wr_x": 0.0, "wr_y": 0.0, "wr_z": 0.0, "wl_x": 0.0, "wl_y": 0.0, "wl_z": 0.0}
ATT_HAND = np.array([-4.6, 9.4, -3.6])       # fist at the belt, forearm forward
ATT_DIR = np.array([0.0, -1.0, 0.04])         # blade straight up past the shoulder
SALUTE_HAND = np.array([-1.2, -2.2, -5.4])    # hilt before the chin
SALUTE_DIR = np.array([0.0, -1.0, -0.06])
SWEEP_HAND = np.array([-5.6, 11.0, -6.4])     # blade swept down and out to the right
SWEEP_DIR = np.array([-0.30, 0.55, -0.78])
BODY = ("hip_x", "hip_y", "hip_z", "hip_p", "hip_yaw", "hip_r", "sp_x", "sp_y", "sp_z", "sp_lift",
        "hd_x", "hd_y", "hd_z", "ck_x", "ck_z", "gl_w", "wr_x", "wr_y", "wr_z", "wl_x", "wl_y", "wl_z")


def attention_arms():
    g = ck.static_getter(ATT)
    ch = ck.body_channels(g)
    sol_r, e = ck.solve_arm(ch, "right", ATT_HAND, ATT_DIR, seed=(-20, -10, 5, -80, 0), elbow_pref=-85.0,
                            w_dir=20.0)
    arms = dict(zip(ck.ARM_PROPS["right"], sol_r))
    arms.update({"al_x": 1.0, "al_y": 0.0, "al_z": -2.5, "el_l": -6.0, "tw_l": 0.0})
    return arms, e


def key_const(vals, L, loop, over=None):
    K = {p: [(0.0, v), (L, v)] for p, v in vals.items()}
    for p in ck.FEET_PROPS:
        K.setdefault(p, [(0.0, 0.0), (L, 0.0)])
    K.update(over or {})
    ck.key_all(K, L, loop)


def clip_attention(B):
    L = 2.0
    arms, _ = attention_arms()
    vals = dict(ATT, **arms)
    over = {
        "sp_lift": [(0.0, 0.1, *S), (1.1, 0.34, *S), (2.0, 0.1)],
        "sp_x": [(0.0, -1.5, *S), (1.1, -2.2, *S), (2.0, -1.5)],
        "hd_x": [(0.0, -4.0, *S), (1.1, -4.4, *S), (2.0, -4.0)],
        "ar_x": [(0.0, arms["ar_x"], *S), (1.1, arms["ar_x"] - 0.6, *S), (2.0, arms["ar_x"])],
        "al_z": [(0.0, -2.5, *S), (1.1, -3.2, *S), (2.0, -2.5)],
        "ck_x": [(0.0, 2.0, *S), (1.1, 2.8, *S), (2.0, 2.0)],
    }
    key_const(vals, L, True, over)
    solve = ck.make_solve(L, True, feet=(ATT_FOOT_R, ATT_FOOT_L), cloak_drag=False)
    return L, True, solve, "2.0 s loop: attention hold, breathing only"


def clip_snap(B):
    """Stance -> attention. Feet props are offsets from the STANCE feet."""
    L = 0.4
    arms, _ = attention_arms()
    dR = ATT_FOOT_R - ck.FOOT_R
    dL = ATT_FOOT_L - ck.FOOT_L
    K = {}
    for p in BODY:
        a, b = B.get(p, 0.0), ATT.get(p, 0.0)
        if p in ("sp_x",):          # straightens with a proud overshoot
            K[p] = [(0.0, a), (0.10, a + 1.0, *ACC), (0.28, b - 2.0, *DEC), (0.34, b - 2.3, *S), (0.4, b)]
        elif p in ("hd_x",):
            K[p] = [(0.0, a), (0.12, a + 2.0, *ACC), (0.28, b - 1.5, *DEC), (0.4, b)]
        elif p in ("hip_y",):       # small gather dip, then rise tall
            K[p] = [(0.0, a), (0.08, a - 0.3, *S), (0.28, b + 0.08, *DEC), (0.4, b)]
        else:
            K[p] = [(0.0, a, *S), (0.28, b, *DEC3), (0.4, b)]
    # rear (right) foot steps in first, then the lead heel CLICKS home at 0.28
    # (the lift LEADS each step so no sole slides while it still touches the ground)
    K["fr_x"] = [(0.0, 0.0), (0.06, 0.0, *S), (0.18, float(dR[0]), *DEC), (0.4, float(dR[0]))]
    K["fr_z"] = [(0.0, 0.0), (0.06, 0.0, *S), (0.18, float(dR[2]), *DEC), (0.4, float(dR[2]))]
    K["fr_y"] = [(0.0, 0.0), (0.03, 0.0, *DEC), (0.09, 0.9, *S), (0.14, 0.6, *ACC), (0.18, 0.0), (0.4, 0.0)]
    K["fl_x"] = [(0.0, 0.0), (0.16, 0.0, *S), (0.28, float(dL[0]), *DEC), (0.4, float(dL[0]))]
    K["fl_z"] = [(0.0, 0.0), (0.16, 0.0, *S), (0.28, float(dL[2]), *DEC), (0.4, float(dL[2]))]
    K["fl_y"] = [(0.0, 0.0), (0.13, 0.0, *DEC), (0.19, 1.1, *S), (0.23, 0.8, *ACC), (0.28, 0.0), (0.4, 0.0)]
    for p in ck.ARM_PROPS["right"] + ck.ARM_PROPS["left"]:
        a, b = B[p], arms[p]
        K[p] = [(0.0, a), (0.06, a, *S), (0.26, b, *DEC3), (0.4, b)]
    ck.key_all(K, L, False)
    solve = ck.make_solve(L, False, cloak_drag=True)
    return L, False, solve, "0.40 s one-shot: GUARD_STANCE -> GUARD_ATTENTION (heel click at 0.28 s)"


def clip_salute(B):
    """Knightly salute, re-authored (anim overkill 2026-09-26): the old sweep unwound the
    85 deg flat-turn while the hilt was still at the chin, so the blade swung back THROUGH
    the head (2.6 px at 0.82 s). Now the hilt first drives FORWARD off the face, the point
    tips forward, and only then the blade sweeps down-out to the right (all blade
    directions solved at dense keys, so every in-between stays in front of the body).
    Beats: 0.00 gather dip, 0.10-0.36 hilt up (overshoot) -> 0.44 settle at the chin,
    0.46-0.62 short bow, 0.72-0.80 hilt drives forward, 0.80-0.93 sweep, 0.97 overshoot,
    1.02 lowered-blade hold, 1.02-1.16 back to attention."""
    L = 1.2
    arms, _ = attention_arms()
    vals = dict(ATT, **arms)
    rest = [arms[p] for p in ck.ARM_PROPS["right"]]
    over = {
        "sp_x": [(0.0, -1.5), (0.10, -0.6, *S), (0.36, -2.8, *DEC), (0.46, -2.4, *S), (0.56, 2.2, *S),
                 (0.68, 1.6, *S), (0.80, -1.8, *S), (0.95, 1.6, *DEC), (1.04, 1.2, *S), (1.16, -1.7, *S),
                 (1.2, -1.5)],
        "hd_x": [(0.0, -4.0), (0.10, -3.2, *S), (0.40, -5.0, *DEC), (0.48, -4.6, *S), (0.58, 7.0, *DEC),
                 (0.66, 6.2, *S), (0.76, -5.2, *DEC), (0.95, -3.0, *S), (1.08, -4.6, *S), (1.2, -4.0)],
        "hd_y": [(0.0, 0.0), (0.80, 0.0, *S), (0.95, -4.0, *S), (1.10, 0.0, *S), (1.2, 0.0)],
        "hip_y": [(0.0, -0.1), (0.10, -0.40, *S), (0.36, 0.05, *DEC), (0.60, -0.15, *S), (0.84, -0.05, *S),
                  (0.97, -0.35, *DEC), (1.10, -0.05, *S), (1.2, -0.1)],
        "sp_y": [(0.0, 0.0), (0.80, 0.0, *S), (0.95, -3.0, *DEC), (1.12, 0.0, *S), (1.2, 0.0)],
        "ck_x": [(0.0, 2.0), (0.4, 1.0), (0.9, 4.0), (1.2, 2.0)],
        # the flat of the blade turns to the face at the salute, and unwinds only while the
        # hilt drives forward and down (0.74-0.93), never beside the face
        "wr_y": [(0.0, 0.0), (0.16, 0.0, *S), (0.40, 85.0, *DEC), (0.72, 85.0, *S), (0.93, 0.0, *S), (1.2, 0.0)],
        # the wrist tips the point down-forward in the sweep (the arm alone cannot aim it)
        "wr_x": [(0.0, 0.0), (0.78, 0.0, *S), (0.93, 38.0, *DEC), (0.97, 45.0, *S), (1.03, 38.0, *S),
                 (1.16, 0.0, *S), (1.2, 0.0)],
    }
    key_const(vals, L, False, over)
    body_at = lambda t: ck.body_channels(lambda p: c(p, t))  # noqa: E731
    hilt = np.array([-1.0, -2.3, -7.0])        # before the chin, a hand's width off the face
    up = np.array([0.0, -1.0, -0.10])
    goals = [
        (0.10, ATT_HAND + np.array([0.1, 0.5, -0.3]), ATT_DIR, ACC),                  # gather (fist sinks)
        (0.36, hilt + np.array([0.0, -0.6, -0.2]), up, DEC),                           # up, overshoot
        (0.44, hilt, up, S),
        (0.72, hilt + np.array([0.0, 0.2, -0.1]), up, ACC),
        (0.80, np.array([-2.6, 0.2, -8.6]), np.array([-0.10, -0.78, -0.62]), ck.LIN),  # hilt drives forward
        (0.86, np.array([-4.4, 4.6, -8.8]), np.array([-0.26, -0.08, -0.96]), ck.LIN),  # blade level, pointing out
    ]
    keys = {p: [(0.0, v, *S)] for p, v in zip(ck.ARM_PROPS["right"], rest)}
    seed = rest
    log = []
    for t, hand, d, e in goals:
        sol, err = ck.solve_arm(body_at(t), "right", hand, d, seed=seed, w_dir=8.0, pole_out=2.0)
        seed = sol
        log.append({"t": t, "cost": round(err, 3)})
        print("SALUTE_GOAL", t, round(err, 2), [round(v, 1) for v in sol])
        for p, v in zip(ck.ARM_PROPS["right"], sol):
            keys[p].append((t, v, *e))
    # the sweep: arm swung down and out to the right (FK; the wrist aims the point), a small
    # overshoot of the arm at 0.97, the lowered-blade hold, then back to attention
    sweep = {"ar_x": -14.0, "ar_y": 4.0, "ar_z": 20.0, "el_r": -12.0, "tw_r": 0.0}
    over_s = {"ar_x": -11.5, "ar_y": 4.0, "ar_z": 22.0, "el_r": -10.0, "tw_r": 0.0}
    for p, v in zip(ck.ARM_PROPS["right"], rest):
        keys[p] += [(0.93, sweep[p], *DEC), (0.97, over_s[p], *S), (1.03, sweep[p], *DEC3), (1.16, v, *S), (1.2, v)]
    ck.key_all(keys, L, False)
    solve = ck.make_solve(L, False, feet=(ATT_FOOT_R, ATT_FOOT_L), cloak_drag=True)
    return L, False, solve, ("1.20 s one-shot from/to GUARD_ATTENTION: hilt to the face 0.36-0.72 (bow 0.58), "
                             "hilt drives forward 0.80, blade swept down-out 0.93, lowered hold to 1.03, "
                             "attention by 1.16")


CLIPS = {"GUARD_ATTENTION": clip_attention, "GUARD_ATTENTION_SNAP": clip_snap, "GUARD_SALUTE": clip_salute}


def run(const):
    args = hsrig.parse_args()
    base, B = ck.stance_base()
    objs = ck.build("settler_guard.png", right="sword")
    L, loop, solve, contract = CLIPS[const](B)
    times, samples = hsrig.bake(solve, L, objs)
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract}
    checks.update(ck.ground_report(samples if const != "GUARD_ATTENTION_SNAP" else samples[-6:]))
    w = mcrig.pose_matrices(samples[-1])
    pom, _, tip = ck.sword_points(w)
    d = (tip - pom) / np.linalg.norm(tip - pom)
    checks["end_blade_dir"] = [round(float(v), 3) for v in d]
    print("CHECKS", checks)
    if args["export"]:
        doc, e = ck.export(const, L, loop, times, samples,
                           meta={"source": "tools/blender/pipeline/clips/combat/ceremony.py (Blender "
                                           + bpy.app.version_string + ")", "contract": contract},
                           out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(e, 4)
    ck.write_report(const.lower(), checks)
    ck.preview(const.lower(), L, args, loop=loop, step=2 if L >= 2.0 else 1)
