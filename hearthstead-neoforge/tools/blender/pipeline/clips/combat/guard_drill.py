"""GUARD DRILL -- the morning sparring clips (Guard Drill, Watch & Defense ring 1) + a pair reel.

    blender -b --factory-startup --python guard_drill.py -- [--fast|--full] [--no-export] [CLIP ...|REEL]

Clips (absolute full-body, played on reset bones by SettlerModel; one-shots start and end on
the drill stance's t=0 pose; lengths/contacts = GuardDrillScript.LENGTH_S / CONTACT_S):

  GUARD_DRILL_STANCE      3.20 s loop  relaxed sparring stance: loose knees, weight rocking,
                                       sword low-ready, a slow breath and a small bounce
  GUARD_DRILL_CUT_HIGH    1.20 s  contact 0.55  high diagonal practice cut, pulled short, lead
                                                foot steps in and back
  GUARD_DRILL_CUT_LOW     1.10 s  contact 0.50  flat flank cut at the waist
  GUARD_DRILL_PARRY_HIGH  1.00 s  contact 0.45  blade raised across the head, catches the high cut
  GUARD_DRILL_PARRY_LOW   1.00 s  contact 0.42  hanging parry, point down, covers the flank
  GUARD_DRILL_EVADE       0.90 s  furthest back 0.40  a quick step back out of range (no answer)
  GUARD_DRILL_BREATHER    3.00 s  step back, sword down, wipe the brow, roll the shoulder, step in

REEL renders two guards 2 blocks apart following the real GuardDrillScript plan (ported below,
bit-exact RNG) with each guard's own phase and speed, sampling the EXPORTED JSON with the
runtime's interpolation -- what the game shows. Output: $HS_COMBAT_VIDEOS/guard_drill_reel/.
"""
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import bpy  # noqa: E402
import numpy as np  # noqa: E402
from mathutils import Matrix  # noqa: E402

import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import moves  # noqa: E402
import export_mc_clip as ex  # noqa: E402
import guard_drill_moves  # noqa: E402

c = hsrig.ctrl
ACC, ACC4, DEC, DEC3, LIN, SMO = ck.ACC, ck.ACC4, ck.DEC, ck.DEC3, ck.LIN, ck.SMO

# --------------------------------------------------------------------------- drill stance base
# Relaxed where GUARD_STANCE is readied: hips higher, less forward lean, the sword hand at the
# belly with the point at the partner's chest, the free fist low by the belt.
DRILL_CTRL = dict(ck.STANCE_CTRL)
DRILL_CTRL.update({"hip_y": -0.35, "hip_z": 0.2, "hip_yaw": 6.0, "sp_x": 4.0, "sp_y": 2.0,
                   "hd_x": -3.0, "hd_y": -6.0, "ck_x": 3.0})
HAND_R = np.array([-3.4, 8.8, -5.4])
_d = np.array([0.16, -0.36, -0.92])
DIR_R = _d / np.linalg.norm(_d)
HAND_L = np.array([2.9, 9.6, -2.6])


def drill_base():
    g = ck.static_getter(DRILL_CTRL)
    ch = ck.body_channels(g)
    sol_r, _ = ck.solve_arm(ch, "right", HAND_R, DIR_R, seed=(-35, -10, 5, -40, 0), elbow_pref=-50.0)
    ch["right_arm"] = {"rot": tuple(sol_r[:3])}
    ch["right_forearm"] = {"rot": (sol_r[3], sol_r[4], 0.0)}
    sol_l, _ = ck.solve_arm(ch, "left", HAND_L, None, seed=(-20, 10, -5, -40, 0), pole_out=4.0)
    ch["left_arm"] = {"rot": tuple(sol_l[:3])}
    ch["left_forearm"] = {"rot": (sol_l[3], 0.0, 0.0)}
    ck.legs_ik(ch, ck.FOOT_R, ck.FOOT_L)
    ch["cloak"] = {"rot": (DRILL_CTRL["ck_x"], 0.0, 0.0)}
    ctrl = dict(DRILL_CTRL)
    for side, sol in (("right", sol_r), ("left", sol_l)):
        for name, v in zip(ck.ARM_PROPS[side], sol):
            ctrl[name] = v
    ctrl["tw_l"] = 0.0
    return ch, ctrl


# --------------------------------------------------------------------------- clip setups
def setup_stance(B):
    L = 3.2
    S = SMO
    K = {
        # one breath per loop: rise 1.7 s, fall 1.5 s
        "sp_lift": [(0.0, 0.0, *S), (1.7, 0.28, *S), (3.2, 0.0)],
        "sp_x": [(0.0, B["sp_x"], *S), (1.7, B["sp_x"] - 0.9, *S), (3.2, B["sp_x"])],
        "sp_y": [(0.0, B["sp_y"], *S), (1.0, B["sp_y"] + 1.2, *S), (2.4, B["sp_y"] - 1.0, *S), (3.2, B["sp_y"])],
        "sp_z": [(0.0, 0.0, *S), (1.4, -0.7, *S), (2.8, 0.4, *S), (3.2, 0.0)],
        # weight rocks onto the lead foot and back, with a small knee bounce on each settle
        "hip_z": [(0.0, B["hip_z"], *S), (0.8, B["hip_z"] - 0.5, *S), (1.6, B["hip_z"] + 0.1, *S),
                  (2.4, B["hip_z"] + 0.45, *S), (3.2, B["hip_z"])],
        "hip_y": [(0.0, B["hip_y"], *S), (0.8, B["hip_y"] - 0.3, *S), (1.2, B["hip_y"] - 0.1, *S),
                  (2.4, B["hip_y"] - 0.28, *S), (2.8, B["hip_y"] - 0.08, *S), (3.2, B["hip_y"])],
        "hip_x": [(0.0, B["hip_x"], *S), (1.6, B["hip_x"] + 0.3, *S), (3.2, B["hip_x"])],
        "hip_yaw": [(0.0, B["hip_yaw"], *S), (1.2, B["hip_yaw"] + 1.2, *S), (2.6, B["hip_yaw"] - 0.8, *S),
                    (3.2, B["hip_yaw"])],
        "hip_r": [(0.0, 0.0, *S), (1.6, 0.6, *S), (3.2, 0.0)],
        # eyes stay on the partner; a small settle of the chin with the breath
        "hd_x": [(0.0, B["hd_x"], *S), (1.7, B["hd_x"] + 0.9, *S), (3.2, B["hd_x"])],
        "hd_y": [(0.0, B["hd_y"], *S), (1.2, B["hd_y"] - 1.2, *S), (2.6, B["hd_y"] + 0.8, *S), (3.2, B["hd_y"])],
        "hd_z": [(0.0, 0.0, *S), (1.6, 1.2, *S), (3.2, 0.0)],
        "ck_x": [(0.0, B["ck_x"], *S), (1.7, B["ck_x"] + 1.0, *S), (3.2, B["ck_x"])],
        "ck_z": [(0.0, 0.0, *S), (1.6, 0.8, *S), (3.2, 0.0)],
    }
    for p in ("gl_w", "tw_r", "tw_l", "wr_x", "wr_y", "wr_z", "wl_x", "wl_y", "wl_z", "wr_roll"):
        K.setdefault(p, [(0.0, B.get(p, 0.0)), (L, B.get(p, 0.0))])
    for p in ck.FEET_PROPS:
        K[p] = [(0.0, 0.0), (L, 0.0)]
    # arms: the rest solution + a loose drift; the sword tip bobs with the breath
    for p in ("ar_x", "ar_y", "ar_z", "el_r", "al_x", "al_y", "al_z", "el_l"):
        v = B[p]
        d = {"ar_x": -1.6, "el_r": 1.0, "ar_z": 0.6, "al_x": -1.4, "el_l": 1.4, "al_z": -0.6}.get(p, 0.0)
        K[p] = [(0.0, v, *S), (1.7, v + d, *S), (3.2, v)]
    ck.key_all(K, L, True)
    return L, True, (), "3.20 s loop; t=0 is the pose every drill one-shot starts and ends on", None, []


def _common_tail(B, D, L):
    """Head keeps the eyes on the partner through the twist."""
    D["hd_y"] = [(t, v, *SMO) for t, v in moves.head_follow(B, D, gain=0.8)]
    D["hd_y"][0] = (0.0, 0.0)
    D["hd_y"][-1] = (L, 0.0)


def setup_cut_high(B):
    """High diagonal practice cut: coil, step in, cut from high right to the partner's
    shoulder, stop it short on the parry (a drill, not a kill), recoil, step home."""
    L, hit = 1.2, 0.55
    D = {
        "hip_z": [(0.0, 0.0), (0.28, 0.9, *SMO), (0.42, 0.2, *ACC), (0.55, -5.2, *DEC), (0.64, -5.3),
                  (0.86, -3.0, *SMO), (1.04, -0.2, *SMO), (1.2, 0.0)],
        "hip_y": [(0.0, 0.0), (0.28, 0.1, *SMO), (0.55, -1.2, *DEC), (0.64, -1.25), (0.9, -0.6, *SMO),
                  (1.2, 0.0)],
        "hip_x": [(0.0, 0.0), (0.28, -0.6, *SMO), (0.55, 0.6, *DEC), (0.9, 0.3, *SMO), (1.2, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.30, 14.0, *ACC), (0.52, -11.0, *DEC), (0.64, -11.5), (0.95, -2.0, *SMO),
                    (1.2, 0.0)],
        "sp_y": [(0.0, 0.0), (0.32, 20.0, *ACC), (0.55, -16.0, *DEC), (0.64, -16.5), (0.95, -2.5, *SMO),
                 (1.2, 0.0)],
        "sp_x": [(0.0, 0.0), (0.32, -5.0, *ACC), (0.55, 13.0, *DEC), (0.64, 13.5), (0.95, 2.0, *SMO),
                 (1.2, 0.0)],
        "sp_z": [(0.0, 0.0), (0.32, 4.0), (0.55, -5.0), (0.8, -2.0, *SMO), (1.2, 0.0)],
        "hd_x": [(0.0, 0.0), (0.32, -2.0), (0.55, 3.0), (0.8, 1.5, *SMO), (1.2, 0.0)],
        # lead (left) foot steps in under the cut, and home again
        "fl_z": [(0.0, 0.0), (0.34, 0.0, *SMO), (0.52, -5.5, *DEC), (0.84, -5.5, *SMO), (1.02, 0.0, *DEC),
                 (1.2, 0.0)],
        "fl_y": [(0.0, 0.0), (0.34, 0.0), (0.43, 1.0), (0.52, 0.0), (0.84, 0.0), (0.93, 0.8), (1.02, 0.0),
                 (1.2, 0.0)],
        "fr_z": [(0.0, 0.0), (0.45, 0.0, *SMO), (0.62, -1.0, *SMO), (0.9, -1.0, *SMO), (1.08, 0.0), (1.2, 0.0)],
    }
    _common_tail(B, D, L)
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.20, (-5.2, 4.6, -3.0), (-0.05, -0.80, -0.60), None, SMO),     # lift
        (0.34, (-7.0, 0.4, 0.8), (-0.10, -0.55, 0.83), None, ACC),       # cocked over the right shoulder
        (0.55, (-1.5, 1.8, -13.3), (-0.05, -0.56, -0.82), None, None),       # contact: pulled short at the parry
        (0.64, (-1.2, 2.2, -13.0), (-0.03, -0.54, -0.84), None, SMO),        # held a beat against the blade
        (0.82, (-1.6, 7.0, -7.6), (0.30, -0.10, -0.95), None, SMO),      # recoil, point back on line
        (1.00, (-3.0, 8.4, -5.8), (0.18, -0.34, -0.92), None, SMO),
        (1.20, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at, w_dir=12.0)
    moves.key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.32, -18.0, *ACC), (0.55, 14.0, *DEC), (0.7, 14.0), (1.0, 2.0, *SMO), (1.2, 0.0)],
        "al_y": [(0.0, 0.0), (0.32, 6.0), (0.55, -6.0), (0.8, -3.0, *SMO), (1.2, 0.0)],
        "al_z": [(0.0, 0.0), (0.32, -6.0), (0.55, 5.0), (0.8, 2.0, *SMO), (1.2, 0.0)],
        "el_l": [(0.0, 0.0), (0.32, 10.0), (0.55, -16.0), (0.8, -8.0, *SMO), (1.2, 0.0)],
    }, L)
    log.append({"edge_roll": ck.key_edge_roll(L, 0.0, 0.0, tail=0.3)})
    return L, False, (0.34, hit, 0.64), "1.20 s one-shot; blade meets the parry at 0.55 s", hit, log


def setup_cut_low(B):
    """Flat flank cut at the waist, from the right hip across to the partner's side."""
    L, hit = 1.1, 0.50
    D = {
        "hip_z": [(0.0, 0.0), (0.26, 0.6, *SMO), (0.50, -4.4, *DEC), (0.58, -4.5), (0.84, -1.8, *SMO),
                  (1.1, 0.0)],
        "hip_y": [(0.0, 0.0), (0.26, -0.3, *SMO), (0.50, -1.5, *DEC), (0.58, -1.55), (0.84, -0.6, *SMO),
                  (1.1, 0.0)],
        "hip_x": [(0.0, 0.0), (0.26, -0.8, *SMO), (0.50, 0.7, *DEC), (0.84, 0.3, *SMO), (1.1, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.28, 18.0, *ACC), (0.48, -14.0, *DEC), (0.58, -15.0), (0.88, -2.0, *SMO),
                    (1.1, 0.0)],
        "sp_y": [(0.0, 0.0), (0.28, 22.0, *ACC), (0.50, -20.0, *DEC), (0.58, -20.5), (0.88, -3.0, *SMO),
                 (1.1, 0.0)],
        "sp_x": [(0.0, 0.0), (0.28, 3.0), (0.50, 12.0, *DEC), (0.58, 12.5), (0.88, 2.0, *SMO), (1.1, 0.0)],
        "sp_z": [(0.0, 0.0), (0.28, -3.0), (0.50, 4.0), (0.8, 1.5, *SMO), (1.1, 0.0)],
        "hd_x": [(0.0, 0.0), (0.28, 2.0), (0.50, 4.0), (0.8, 2.0, *SMO), (1.1, 0.0)],
        "fl_z": [(0.0, 0.0), (0.30, 0.0, *SMO), (0.47, -4.5, *DEC), (0.78, -4.5, *SMO), (0.95, 0.0, *DEC),
                 (1.1, 0.0)],
        "fl_y": [(0.0, 0.0), (0.30, 0.0), (0.39, 0.9), (0.47, 0.0), (0.78, 0.0), (0.86, 0.7), (0.95, 0.0),
                 (1.1, 0.0)],
    }
    _common_tail(B, D, L)
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.28, (-7.6, 10.6, 0.4), (-0.62, 0.10, 0.78), None, ACC),        # drawn back past the right hip
        (0.50, (-1.0, 9.0, -12.5), (-0.29, 0.0, -0.96), None, None),        # contact: flat, waist high
        (0.58, (-0.6, 9.2, -12.2), (-0.20, 0.02, -0.98), None, SMO),
        (0.80, (-1.8, 9.2, -7.4), (0.30, -0.20, -0.93), None, SMO),
        (1.10, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at)
    moves.key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.28, -10.0, *ACC), (0.50, 10.0, *DEC), (0.8, 4.0, *SMO), (1.1, 0.0)],
        "al_z": [(0.0, 0.0), (0.28, -8.0), (0.50, 8.0), (0.8, 3.0, *SMO), (1.1, 0.0)],
        "el_l": [(0.0, 0.0), (0.28, 8.0), (0.50, -12.0), (0.8, -5.0, *SMO), (1.1, 0.0)],
    }, L)
    log.append({"edge_roll": ck.key_edge_roll(L, 0.0, 0.0, tail=0.3)})
    return L, False, (0.28, hit, 0.58), "1.10 s one-shot; blade meets the parry at 0.50 s", hit, log


def setup_parry_high(B):
    """High parry: the blade comes up across the head, flat to the incoming diagonal; the
    knees take the shock, then the guard pushes the blade off and settles."""
    L, hit = 1.0, 0.45
    D = {
        "hip_z": [(0.0, 0.0), (0.30, 0.1, *SMO), (0.45, 0.3, *DEC), (0.52, 0.7), (0.75, 0.4, *SMO), (1.0, 0.0)],
        "hip_y": [(0.0, 0.0), (0.30, -0.5, *SMO), (0.45, -1.3, *DEC), (0.52, -1.5), (0.8, -0.5, *SMO),
                  (1.0, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.30, -6.0, *SMO), (0.45, -8.0), (0.7, -3.0, *SMO), (1.0, 0.0)],
        "sp_y": [(0.0, 0.0), (0.30, -8.0, *SMO), (0.45, -10.0), (0.52, -9.0), (0.75, -3.0, *SMO), (1.0, 0.0)],
        "sp_x": [(0.0, 0.0), (0.30, -2.0, *SMO), (0.45, -3.5), (0.52, -4.5), (0.75, 0.0, *SMO), (1.0, 0.0)],
        "sp_z": [(0.0, 0.0), (0.45, 3.0), (0.52, 4.0), (0.8, 1.0, *SMO), (1.0, 0.0)],
        "hd_x": [(0.0, 0.0), (0.30, -4.0), (0.45, -3.0), (0.52, -1.0), (1.0, 0.0)],
        # the rear (right) foot slides back a little to take the blow
        "fr_z": [(0.0, 0.0), (0.26, 0.0, *SMO), (0.44, 1.4, *DEC), (0.78, 1.4, *SMO), (0.94, 0.0), (1.0, 0.0)],
        "fr_y": [(0.0, 0.0), (0.26, 0.0), (0.35, 0.6), (0.44, 0.0), (0.78, 0.0), (0.86, 0.5), (0.94, 0.0),
                 (1.0, 0.0)],
    }
    _common_tail(B, D, L)
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.20, (-2.4, 2.4, -7.6), (0.55, -0.60, -0.58), None, ACC),
        (0.34, (-1.7, 0.7, -9.6), (0.64, -0.52, -0.56), None, SMO),       # set early: the blow may come a tick early       # blade up across the brow
        (0.45, (-1.6, 0.6, -9.7), (0.65, -0.51, -0.56), None, None),      # meets the cut
        (0.52, (-2.2, 1.8, -7.2), (0.76, -0.56, -0.34), None, SMO),       # the blow pushes it back
        (0.72, (-2.8, 5.0, -7.4), (0.40, -0.40, -0.83), None, SMO),       # pushed off, point returns
        (1.00, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at)
    moves.key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.30, -14.0, *SMO), (0.52, -16.0), (0.8, -4.0, *SMO), (1.0, 0.0)],
        "al_z": [(0.0, 0.0), (0.30, -4.0), (0.52, -5.0), (1.0, 0.0)],
        "el_l": [(0.0, 0.0), (0.30, -10.0), (0.52, -12.0), (0.8, -3.0, *SMO), (1.0, 0.0)],
    }, L)
    return L, False, (0.30, hit, 0.52), "1.00 s one-shot; blade meets the high cut at 0.45 s", hit, log


def setup_parry_low(B):
    """Hanging parry on the flank: hilt up by the chest, point down, the knees drop under it."""
    L, hit = 1.0, 0.42
    D = {
        "hip_y": [(0.0, 0.0), (0.28, -0.8, *SMO), (0.42, -1.8, *DEC), (0.50, -1.9), (0.78, -0.7, *SMO),
                  (1.0, 0.0)],
        "hip_z": [(0.0, 0.0), (0.42, 0.2, *DEC), (0.5, 0.5), (0.78, 0.2, *SMO), (1.0, 0.0)],
        "hip_yaw": [(0.0, 0.0), (0.28, -10.0, *SMO), (0.42, -13.0), (0.52, -12.0), (0.8, -3.0, *SMO), (1.0, 0.0)],
        "sp_y": [(0.0, 0.0), (0.28, -12.0, *SMO), (0.42, -15.0), (0.52, -14.0), (0.8, -3.0, *SMO), (1.0, 0.0)],
        "sp_x": [(0.0, 0.0), (0.28, 5.0, *SMO), (0.42, 7.0), (0.52, 6.0), (0.8, 1.5, *SMO), (1.0, 0.0)],
        "sp_z": [(0.0, 0.0), (0.42, -3.0), (0.8, -1.0, *SMO), (1.0, 0.0)],
        "hd_x": [(0.0, 0.0), (0.28, 4.0), (0.42, 6.0), (0.8, 2.0, *SMO), (1.0, 0.0)],
    }
    _common_tail(B, D, L)
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.20, (0.2, 6.8, -7.4), (0.28, 0.50, -0.82), None, ACC),
        (0.32, (1.1, 6.1, -8.5), (0.32, 0.55, -0.77), None, SMO),
        (0.42, (1.2, 6.0, -8.6), (0.32, 0.54, -0.78), None, None),        # meets the flank cut
        (0.50, (0.6, 7.2, -7.6), (0.30, 0.84, -0.46), None, SMO),
        (0.74, (-2.0, 8.4, -6.8), (0.22, 0.10, -0.97), None, SMO),        # rolls the point back up
        (1.00, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at)
    moves.key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.28, 8.0, *SMO), (0.5, 9.0), (0.8, 2.0, *SMO), (1.0, 0.0)],
        "al_z": [(0.0, 0.0), (0.28, -6.0), (0.5, -7.0), (1.0, 0.0)],
    }, L)
    return L, False, (0.28, hit, 0.50), "1.00 s one-shot; blade meets the low cut at 0.42 s", hit, log


def setup_evade(B):
    """No answer with the blade: a quick step back out of range, torso swaying away."""
    L, hit = 0.9, 0.40
    D = {
        "hip_z": [(0.0, 0.0), (0.16, 0.6, *ACC), (0.40, 4.4, *DEC), (0.50, 4.5), (0.72, 1.4, *SMO), (0.9, 0.0)],
        "hip_y": [(0.0, 0.0), (0.16, -0.4), (0.28, 0.2), (0.40, -0.9, *DEC), (0.6, -0.5, *SMO), (0.9, 0.0)],
        "sp_x": [(0.0, 0.0), (0.18, -3.0, *ACC), (0.40, -8.0, *DEC), (0.52, -7.0), (0.75, -1.0, *SMO), (0.9, 0.0)],
        "sp_y": [(0.0, 0.0), (0.40, 6.0), (0.75, 1.0, *SMO), (0.9, 0.0)],
        "hd_x": [(0.0, 0.0), (0.40, -4.0), (0.75, -1.0, *SMO), (0.9, 0.0)],
        # both feet step back (rear first), then forward home
        "fr_z": [(0.0, 0.0), (0.06, 0.0), (0.26, 4.5, *DEC), (0.56, 4.5, *SMO), (0.80, 0.0, *DEC), (0.9, 0.0)],
        "fr_y": [(0.0, 0.0), (0.06, 0.0), (0.16, 1.2), (0.26, 0.0), (0.56, 0.0), (0.68, 0.9), (0.80, 0.0), (0.9, 0.0)],
        "fl_z": [(0.0, 0.0), (0.18, 0.0), (0.38, 4.0, *DEC), (0.62, 4.0, *SMO), (0.84, 0.0, *DEC), (0.9, 0.0)],
        "fl_y": [(0.0, 0.0), (0.18, 0.0), (0.28, 1.0), (0.38, 0.0), (0.62, 0.0), (0.73, 0.8), (0.84, 0.0), (0.9, 0.0)],
    }
    _common_tail(B, D, L)
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.40, (-3.4, 6.4, -3.8), (0.10, -0.70, -0.70), None, SMO),       # blade drawn in and up
        (0.70, (-3.4, 8.0, -5.0), (0.16, -0.42, -0.89), None, SMO),
        (0.90, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at)
    moves.key_arm_fk(B, "left", {
        "al_x": [(0.0, 0.0), (0.40, -12.0, *SMO), (0.9, 0.0)],
        "al_z": [(0.0, 0.0), (0.40, -8.0, *SMO), (0.9, 0.0)],
    }, L)
    return L, False, (hit,), "0.90 s one-shot; furthest back at 0.40 s (the blow falls short)", hit, log


def setup_breather(B):
    """Breather between exchanges: step back, sword point to the ground, wipe the brow with
    the back of the free hand, a slow exhale and a shoulder roll, then step back in."""
    L = 3.0
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
        # a step back (rear foot first) and back in
        "fr_z": [(0.0, 0.0), (0.05, 0.0), (0.28, 3.4, *DEC), (2.4, 3.4, *SMO), (2.72, 0.0, *DEC), (3.0, 0.0)],
        "fr_y": [(0.0, 0.0), (0.05, 0.0), (0.16, 1.1), (0.28, 0.0), (2.4, 0.0), (2.56, 0.9), (2.72, 0.0), (3.0, 0.0)],
        "fl_z": [(0.0, 0.0), (0.2, 0.0), (0.46, 3.0, *DEC), (2.56, 3.0, *SMO), (2.86, 0.0, *DEC), (3.0, 0.0)],
        "fl_y": [(0.0, 0.0), (0.2, 0.0), (0.33, 1.0), (0.46, 0.0), (2.56, 0.0), (2.71, 0.8), (2.86, 0.0), (3.0, 0.0)],
    }
    moves.key_body(B, D, L)
    body_at = moves.body_at_factory()
    goals = [
        (0.00, None, None, None, None),
        (0.50, (-6.6, 11.4, -2.0), (0.05, 0.75, -0.66), None, SMO),       # point to the ground
        (2.30, (-6.6, 11.6, -1.6), (0.04, 0.78, -0.62), None, SMO),       # rests there
        (2.75, (-3.6, 9.4, -5.0), (0.16, -0.20, -0.96), None, SMO),       # comes back up on line
        (3.00, None, None, None, None),
    ]
    log = moves.solve_goals(B, goals, body_at)
    # the free hand: up to the brow (0.95 s), the back of the wrist wipes across (to 1.35 s), down
    lgoals = [
        (0.00, None, None, None, None),
        (0.55, (3.2, 4.6, -4.2), None, None, SMO),
        (0.95, (2.6, -4.8, -4.6), None, None, SMO),
        (1.35, (-1.8, -5.4, -4.4), None, None, SMO),
        (1.75, (3.4, 8.8, -1.6), None, None, SMO),
        (2.60, (3.2, 9.4, -2.2), None, None, SMO),
        (3.00, None, None, None, None),
    ]
    log += moves.solve_goals(B, lgoals, body_at, side="left", w_dir=0.0)
    return L, False, (0.95, 1.35), "3.00 s one-shot; step back, wipe the brow 0.95-1.35 s, step back in", None, log


CLIPS = {
    "GUARD_DRILL_STANCE": setup_stance,
    "GUARD_DRILL_CUT_HIGH": setup_cut_high,
    "GUARD_DRILL_CUT_LOW": setup_cut_low,
    "GUARD_DRILL_PARRY_HIGH": setup_parry_high,
    "GUARD_DRILL_PARRY_LOW": setup_parry_low,
    "GUARD_DRILL_EVADE": setup_evade,
    "GUARD_DRILL_BREATHER": setup_breather,
}
# v2 (owner, 26 Sep): footwork, feint, stumble, nod, and the shorter breather
CLIPS.update(guard_drill_moves.CLIPS_V2)


def bake_one(const, args):
    base, B = drill_base()
    objs = ck.build("settler_guard.png", right="sword")
    L, loop, keep, contract, hit, log = CLIPS[const](B)
    solve = ck.make_solve(L, loop, cloak_drag=not loop)
    times, samples = hsrig.bake(solve, L, objs)

    tips, sole = [], 0.0
    for s in samples:
        w = mcrig.pose_matrices(s)
        tips.append(ck.sword_points(w)[2])
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract}
    checks.update(ck.ground_report(samples))

    def off(s):
        return max(abs(a - b) for bone in base for kind in ("rot", "pos")
                   for a, b in zip(s.get(bone, {}).get(kind, (0, 0, 0)), base[bone].get(kind, (0, 0, 0))))
    checks["start_offset_from_base"] = round(off(samples[0]), 4)
    checks["end_offset_from_base"] = round(off(samples[-1]), 4)
    if not loop:
        if checks["end_offset_from_base"] < 0.6:
            samples[-1] = base
        if checks["start_offset_from_base"] < 0.6:
            samples[0] = base
    if hit is not None:
        f = int(round(hit * ck.FPS))
        sp = ck.world_speed(tips)
        checks.update({"contact_t": hit,
                       "tip_at_contact_model_px": [round(float(v), 2) for v in tips[f]],
                       "tip_speed_peak_t": round(float(np.argmax(sp)) / ck.FPS, 3),
                       "tip_speed_peak_px_s": round(float(sp.max()), 1)})
    checks["max_goal_cost"] = round(max((g.get("cost", 0.0) for g in log if isinstance(g, dict)), default=0.0), 3)
    print("CHECKS", json.dumps(checks))
    if args["export"]:
        doc, err = ck.export(const, L, loop, times, samples, base=None, keep_times=keep,
                             meta={"source": "tools/blender/pipeline/clips/combat/guard_drill.py (Blender "
                                             + bpy.app.version_string + ")",
                                   "contract": contract,
                                   "grammar": "absolute full-body clip over reset bones (GuardDrillMotion)",
                                   "checks": checks},
                             out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(err, 4)
    ck.write_report(const.lower(), checks)
    ck.preview(const.lower(), L, args, loop=loop)
    return checks


# =========================================================================== GuardDrillScript port (v2)
M64 = (1 << 64) - 1
LENGTH_S = [3.2, 1.2, 1.1, 1.0, 1.0, 0.9, 2.2, 0.8, 0.8, 0.7, 0.7, 0.8, 1.4, 1.3]
CONTACT_S = [0.0, 0.55, 0.50, 0.45, 0.42, 0.40, 0.0, 0.0, 0.0, 0.0, 0.0, 0.32, 0.35, 0.0]
CHAIN_S = [0.0, 0.80, 0.74, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.52, 0.0, 0.0]
MOVE_LEFT = [0, 0, 0, 0, 0, 0, 0, 0.5, -0.5, 0, 0, 0, 0, 0]
MOVE_FWD = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0.375, -0.375, 0, -0.375, 0]
(STANCE, CUT_HIGH, CUT_LOW, PARRY_HIGH, PARRY_LOW, EVADE, BREATHER, STEP_LEFT, STEP_RIGHT, ADVANCE, RETREAT,
 FEINT, STUMBLE, NOD) = range(14)
CLIP_KEYS = ["guard_drill_stance", "guard_drill_cut_high", "guard_drill_cut_low", "guard_drill_parry_high",
             "guard_drill_parry_low", "guard_drill_evade", "guard_drill_breather", "guard_drill_step_left",
             "guard_drill_step_right", "guard_drill_advance", "guard_drill_retreat", "guard_drill_feint",
             "guard_drill_stumble", "guard_drill_nod"]
BLEND_S = 0.25
SOLO = 2


def _mix(z):
    z &= M64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    return z ^ (z >> 31)


def unit(seed, salt):
    z = _mix(((seed << 16) & M64) ^ ((salt * 0x632BE59BD9B4E019) & M64))
    return (z >> 40) / float(1 << 24)


def phase_ticks(seed, slot):
    return 2 + unit(seed, 11 + slot) * 14


def speed(seed, slot):
    return 0.9 + unit(seed, 23 + slot) * 0.2


class Rng:
    def __init__(self, seed):
        self.state = (seed * 0x9E3779B97F4A7C15 + 0x1D8E4E27C47D124F) & M64

    def next(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & M64
        return _mix(self.state)

    def f(self):
        return (self.next() >> 40) / float(1 << 24)

    def i(self, bound):
        return (self.next() >> 33) % bound

    def b(self):
        return (self.next() & 1) != 0

    def range(self, lo, hi):
        return lo + self.f() * (hi - lo)

    def wobble(self):
        return 0.97 + self.f() * 0.06


class Act:
    __slots__ = ("clip", "start", "speed")

    def __init__(self, clip, start, spd):
        self.clip, self.start, self.speed = clip, start, spd

    def end(self):
        return self.start + LENGTH_S[self.clip] * 20.0 / self.speed

    def at(self, sec):
        return self.start + sec * 20.0 / self.speed


def plan(seed, pair, length):
    """Line-by-line port of GuardDrillScript.plan (same RNG call order)."""
    rng = Rng(seed)
    a, b, contacts = [], [], []
    L = lambda slot: b if slot == 1 else a  # noqa: E731
    t = 16.0 + rng.range(0, 12)
    until = 6 + rng.i(4)
    attacker = rng.i(2) if pair else 0
    last_stumble = -500.0
    guard = 0
    while t < length - 50 and guard < 600:
        guard += 1
        if until <= 0:
            skip = rng.i(2) if (pair and rng.f() < 0.35) else -1
            first = rng.i(2)
            lead = rng.range(2, 16)
            lag = lead + rng.range(8, 30)
            end = t
            for slot in range(2 if pair else 1):
                if slot == skip:
                    continue
                act = Act(BREATHER, t + (lead if (slot == first or not pair) else lag), speed(seed, slot) * rng.wobble())
                L(slot).append(act)
                end = max(end, act.end())
            t = end + rng.range(6, 18)
            until = 6 + rng.i(4)
            continue
        r = rng.f()
        if r < 0.22:
            rk = rng.f()
            kind = 0 if rk < 0.5 else 1 if rk < 0.75 else 2
            steps = 1 + rng.i(3)
            side = STEP_LEFT if rng.b() else STEP_RIGHT
            for _ in range(steps):
                together = ADVANCE if rng.b() else RETREAT
                end = t
                for slot in range(2 if pair else 1):
                    clip = side if kind == 0 else ((ADVANCE if slot == attacker else RETREAT) if kind == 1 else together)
                    st = t + rng.range(2, 16)
                    act = Act(clip, st, speed(seed, slot) * rng.wobble())
                    L(slot).append(act)
                    end = max(end, act.end())
                t = end - BLEND_S * 20.0
            t += rng.range(2, 10)
            continue
        rb = rng.f()
        blows = 1 if rb < 0.35 else 2 if rb < 0.77 else 3
        dfd = 1 - attacker if pair else SOLO
        sa = speed(seed, attacker) * rng.wobble()
        cursor = t + rng.range(2, 16)
        end = t
        stepped_in = False
        if rng.f() < 0.4:
            stepped_in = True
            step_in = Act(ADVANCE, cursor, sa)
            L(attacker).append(step_in)
            cursor = step_in.at(0.5)
            end = max(end, step_in.end())
        if rng.f() < 0.15:
            feint = Act(FEINT, cursor, sa)
            L(attacker).append(feint)
            fake = feint.at(CONTACT_S[FEINT])
            bites = pair and rng.f() < 0.5
            if bites:
                sd = speed(seed, dfd) * rng.wobble()
                flinch = Act(EVADE, fake + rng.range(-1, 2) - CONTACT_S[EVADE] * 20.0 / sd, sd)
                L(dfd).append(flinch)
                end = max(end, flinch.end())
            contacts.append((fake, attacker if pair else SOLO, "EVADE" if bites else "NONE", True, True))
            cursor = feint.at(CHAIN_S[FEINT])
            end = max(end, feint.end())
        last, last_answer = "NONE", None
        for i in range(blows):
            high = rng.b()
            cut_clip = CUT_HIGH if high else CUT_LOW
            cut = Act(cut_clip, cursor, sa * (0.98 + rng.f() * 0.04))
            L(attacker).append(cut)
            contact = cut.at(CONTACT_S[cut_clip])
            answer = "NONE"
            if pair:
                ra = rng.f()
                answer = "PARRY" if ra < 0.62 else "EVADE" if ra < 0.80 else "NONE"
                if i == blows - 1 and contact - last_stumble > 500 and rng.f() < 0.08:
                    answer = "STUMBLE"
                if answer != "NONE":
                    clip = {"PARRY": PARRY_HIGH if high else PARRY_LOW, "EVADE": EVADE}.get(answer, STUMBLE)
                    sd = speed(seed, dfd) * rng.wobble()
                    react = rng.range(-1, 2)
                    ans = Act(clip, contact + react - CONTACT_S[clip] * 20.0 / sd, sd)
                    L(dfd).append(ans)
                    end = max(end, ans.end())
                    last_answer = ans
            contacts.append((contact, attacker if pair else SOLO, answer, high, False))
            last = answer
            end = max(end, cut.end())
            cursor = cut.at(CHAIN_S[cut_clip])
        if not pair and rng.f() < 0.35:
            clip = PARRY_HIGH if rng.b() else PARRY_LOW
            up = Act(clip, cursor, speed(seed, 0) * rng.wobble())
            a.append(up)
            end = max(end, up.end())
        if last == "STUMBLE":
            last_stumble = last_answer.start
            nod1 = Act(NOD, last_answer.end() - BLEND_S * 20.0 + rng.range(2, 8), speed(seed, dfd) * rng.wobble())
            nod2 = Act(NOD, end + rng.range(4, 16), speed(seed, attacker) * rng.wobble())
            L(dfd).append(nod1)
            L(attacker).append(nod2)
            end = max(nod1.end(), nod2.end())
            attacker = 1 - attacker
        elif pair and last == "PARRY" and rng.f() < 0.38:
            high = rng.b()
            cut_clip = CUT_HIGH if high else CUT_LOW
            sd = speed(seed, dfd) * rng.wobble()
            rip = Act(cut_clip, last_answer.at(0.62), sd)
            L(dfd).append(rip)
            contact = rip.at(CONTACT_S[cut_clip])
            answer = "PARRY" if rng.f() < 0.7 else "EVADE"
            clip = (PARRY_HIGH if high else PARRY_LOW) if answer == "PARRY" else EVADE
            sb = speed(seed, attacker) * rng.wobble()
            ans = Act(clip, contact + rng.range(-1, 2) - CONTACT_S[clip] * 20.0 / sb, sb)
            L(attacker).append(ans)
            contacts.append((contact, dfd, answer, high, False))
            end = max(end, rip.end(), ans.end())
            attacker = dfd
        else:
            if stepped_in and rng.f() < 0.6:
                out = Act(RETREAT, end - BLEND_S * 20.0 + rng.range(0, 6), speed(seed, attacker) * rng.wobble())
                L(attacker).append(out)
                end = max(end, out.end())
            if pair and rng.f() < 0.45:
                clip = RETREAT if rng.f() < 0.6 else (STEP_LEFT if rng.b() else STEP_RIGHT)
                after = end - BLEND_S * 20.0
                give = Act(clip, after + rng.range(0, 6), speed(seed, dfd) * rng.wobble())
                L(dfd).append(give)
                if clip != RETREAT:
                    with_ = Act(clip, after + rng.range(4, 14), speed(seed, attacker) * rng.wobble())
                    L(attacker).append(with_)
                    end = max(end, with_.end())
                end = max(end, give.end())
            if pair and rng.f() < 0.7:
                attacker = 1 - attacker
        t = end + rng.range(8, 24)
        until -= 1
    a.sort(key=lambda x: x.start)
    b.sort(key=lambda x: x.start)
    contacts.sort(key=lambda x: x[0])
    return {"seed": seed, "len": length, "a": a, "b": b, "contacts": contacts, "pair": pair}


def stance_local(seed, person, elapsed):
    s = (elapsed + phase_ticks(seed, person) * 3.0) * speed(seed, person) / 20.0
    return s % LENGTH_S[STANCE]


def _fade(x):
    w = max(0.0, min(1.0, x / BLEND_S))
    return w * w * (3 - 2 * w)


def _env(act, el):
    local = (el - act.start) * act.speed / 20.0
    return min(_fade(local), _fade(LENGTH_S[act.clip] - local))


def sample(p, slot, el):
    """-> (clip, local, w, prev_clip, prev_local, prev_w, stance_local), like GuardDrillScript.sample."""
    person = 0 if slot == SOLO else slot
    st = stance_local(p["seed"], person, el)
    if el < 0 or el > p["len"] + 40:
        return STANCE, st, 0.0, STANCE, 0.0, 0.0, st
    newest = older = None
    for act in (p["a"] if person == 0 else p["b"]):
        if act.start > el:
            break
        if el < act.end():
            older, newest = newest, act
    if newest is None:
        return STANCE, st, 0.0, STANCE, 0.0, 0.0, st
    w = _env(newest, el)
    local = (el - newest.start) * newest.speed / 20.0
    if older is None:
        return newest.clip, local, w, STANCE, 0.0, 0.0, st
    wo = (1 - w) * _env(older, el)
    return newest.clip, local, w, older.clip, (el - older.start) * older.speed / 20.0, wo, st


def move_ease(clip, local):
    u = (local / LENGTH_S[clip] - 0.1) / 0.7
    u = max(0.0, min(1.0, u))
    return u * u * (3 - 2 * u)


def displacement(p, slot, frm, to):
    left = fwd = 0.0
    person = 0 if slot == SOLO else slot
    for act in (p["a"] if person == 0 else p["b"]):
        if act.start > to:
            break
        if not (MOVE_LEFT[act.clip] or MOVE_FWD[act.clip]) or act.end() < frm:
            continue
        l0 = max(0.0, (frm - act.start) * act.speed / 20.0)
        l1 = max(0.0, (to - act.start) * act.speed / 20.0)
        d = move_ease(act.clip, l1) - move_ease(act.clip, l0)
        left += d * MOVE_LEFT[act.clip]
        fwd += d * MOVE_FWD[act.clip]
    return left, fwd


STRIKE_GAP = 2.0
CLOSE_PER_TICK = 0.08


def closing(p, slot, el):
    person = 0 if slot == SOLO else slot
    for act in (p["a"] if person == 0 else p["b"]):
        if act.start > el:
            break
        if act.clip in (CUT_HIGH, CUT_LOW) and el < act.end():
            if (el - act.start) * act.speed / 20.0 < CONTACT_S[act.clip] - 0.05:
                return True
    return False


def active_fraction(p):
    def fighting(c):
        return c in (CUT_HIGH, CUT_LOW, PARRY_HIGH, PARRY_LOW, EVADE, FEINT, STUMBLE)

    def at(lst, t):
        return any(x.start <= t < x.end() and fighting(x.clip) for x in lst)
    n = int(p["len"])
    return sum(1 for t in range(n) if at(p["a"], t) or at(p["b"], t)) / float(n)


# =========================================================================== reel (v2)
def _load_clip(key):
    path = os.path.join(ck.ANIM_DIR, key + ".animation.json")
    doc = json.load(open(path, encoding="utf-8"))
    anim = "animation.settler." + key
    bones = doc["animations"][anim]["bones"]
    out = {}
    for bn, kinds in bones.items():
        out[bn] = {}
        for kind in ("rotation", "position"):
            if kind in kinds:
                ks = ex.load_keys(doc, anim, bn, kind)
                out[bn][kind] = ([k[0] for k in ks], [k[1] for k in ks])
    return out


def _eval(keys, t):
    ts, vs = keys
    return [ex.evaluate([(ts[i], vs[i][c3]) for i in range(len(ts))], t) for c3 in range(3)]


def _pose(clip, t):
    ch = {}
    for bn, kinds in clip.items():
        rot = _eval(kinds["rotation"], t) if "rotation" in kinds else [0.0, 0.0, 0.0]
        pos = _eval(kinds["position"], t) if "position" in kinds else [0.0, 0.0, 0.0]
        ch[bn] = {"rot": tuple(rot), "pos": tuple(pos)}
    return ch


def _mixpose(parts):
    """parts: [(pose, weight)] -> weighted channel sum (the runtime's additive weighted play)."""
    out = {}
    for pose, w in parts:
        if w <= 1e-4:
            continue
        for bn, ch in pose.items():
            o = out.setdefault(bn, {"rot": [0.0, 0.0, 0.0], "pos": [0.0, 0.0, 0.0]})
            for k in ("rot", "pos"):
                for c3 in range(3):
                    o[k][c3] += ch[k][c3] * w
    return {bn: {k: tuple(v) for k, v in ch.items()} for bn, ch in out.items()}


def _add_rig(tex):
    objs = hsrig.build_scene(os.path.join(ck.SETTLER_TEX, tex), None)
    eye = mcrig.T(0, -6, 0)
    rpar = objs.get("right_item", objs["right_forearm"])
    if "right_item" not in objs:
        eye = np.eye(4)
    e = ck._item_empty("item:sword", rpar, ck.display_matrix(eye, ck.HANDHELD, True))
    hsrig.sprite_mesh("mesh:sword", ck.SWORD_PNG, e)
    return objs


def pick_seed(ticks):
    """A seed whose first stretch shows every beat: combos, a riposte, a feint, a stumble and nods,
    circling and pressing, and a breather."""
    best = None
    for s in range(1, 20000):
        seed = s * 2654435761 & 0x7FFFFFFF
        p = plan(seed, True, 1600)
        early = [c3 for c3 in p["contacts"] if c3[0] < ticks - 40]
        kinds = {c3[2] for c3 in early if not c3[4]}
        feints = any(c3[4] for c3 in early)
        acts = [x for x in p["a"] + p["b"] if x.start < ticks - 50]
        clips = {x.clip for x in acts}
        rip = any(early[i][1] != early[i - 1][1] and early[i - 1][2] == "PARRY" and early[i][0] - early[i - 1][0] < 30
                  for i in range(1, len(early)))
        combo = any(early[i][1] == early[i - 1][1] and early[i][0] - early[i - 1][0] < 20 for i in range(1, len(early)))
        score = (("STUMBLE" in kinds) * 4 + feints + rip + combo + ({"PARRY", "EVADE"} <= kinds)
                 + (BREATHER in clips) + ((STEP_LEFT in clips) or (STEP_RIGHT in clips)) + (NOD in clips))
        if best is None or score > best[0]:
            best = (score, seed)
        if score >= 11:
            return seed
    return best[1]


def _frame(pos, theta):
    f = np.array([math.sin(theta), -math.cos(theta)])
    lft = np.array([math.cos(theta), math.sin(theta)])
    return f, lft


def _to_world(pos, theta, pm):
    """model px point -> world px (x, y_up, z) for an entity at pos (blocks) facing theta."""
    f, lft = _frame(pos, theta)
    xy = np.array(pos) * 16.0 + lft * pm[0] + f * (-pm[2])
    return np.array([xy[0], 24.0 - pm[1], xy[1]])


def _to_model(pos, theta, pw):
    f, lft = _frame(pos, theta)
    rel = np.array([pw[0], pw[2]]) - np.array(pos) * 16.0
    return np.array([float(rel @ lft), 24.0 - pw[1], -float(rel @ f)])


def _inside(local, frm, size, pad=0.0):
    return all(frm[i] - pad < local[i] < frm[i] + size[i] + pad for i in range(3))


def reel(args):
    seconds = 36.0
    ticks = seconds * 20.0
    seed = pick_seed(int(ticks))
    p = plan(seed, True, 1600)
    clips = [_load_clip(k) for k in CLIP_KEYS]
    hsrig.reset()
    rigs = [_add_rig("settler_guard.png"), _add_rig("settler_guard.png")]
    sc = bpy.context.scene
    ents = []
    for i, objs in enumerate(rigs):
        space = objs["root"].parent
        ent = bpy.data.objects.new("ENTITY_%d" % i, None)
        sc.collection.objects.link(ent)
        space.parent = ent
        ents.append(ent)
    world = bpy.data.objects.new("WORLD_MC", None)
    sc.collection.objects.link(world)
    world.matrix_basis = hsrig.MC_TO_BLENDER
    hsrig.prop_box("ground", (-160, 24, -160), (320, 1, 320), (0.36, 0.50, 0.26, 1), parent_name="WORLD_MC")
    for k, (x0, z0) in enumerate(((70, -40), (92, 36), (60, 90))):
        hsrig.prop_box("post%d" % k, (x0, 0, z0), (4, 24, 4), (0.45, 0.33, 0.2, 1), parent_name="WORLD_MC")
    fps = 30
    n = int(seconds * fps)
    # the server's footwork rules (GuardDrillGoal.step): ring-keeping side-steps, 1.8-3 block gap,
    # 3 block leash round each guard's own slot
    slots = [np.array([0.0, 1.0]), np.array([0.0, -1.0])]
    pos = [slots[0].copy(), slots[1].copy()]
    last_tick = 0.0
    audit = {"penetration_frames": 0, "max_depth_px": 0.0, "gap_min": 9.0, "gap_max": 0.0, "travel_blocks": [0.0, 0.0]}
    meets = []
    cam_track = []
    phi_s = None
    contacts = sorted(p["contacts"])
    for f_ in range(n):
        tick = f_ * 20.0 / fps
        # footwork first (as the server does, then it faces the partner again)
        for s_ in (0, 1):
            other = pos[1 - s_]
            d = displacement(p, s_, last_tick, tick) if f_ else (0.0, 0.0)
            if f_ and closing(p, s_, tick):
                gap0 = float(np.linalg.norm(pos[s_] - other))
                want = min(gap0 - STRIKE_GAP, CLOSE_PER_TICK * (tick - last_tick))
                if want > 0.005:
                    d = (d[0], d[1] + want)
            if abs(d[0]) + abs(d[1]) < 1e-6:
                continue
            fvec = other - pos[s_]
            theta = math.atan2(fvec[0], -fvec[1])
            fw, lf = _frame(pos[s_], theta)
            nxt = pos[s_] + lf * d[0] + fw * d[1]
            cur = float(np.linalg.norm(pos[s_] - other))
            gap = float(np.linalg.norm(nxt - other))
            if d[1] == 0.0 and gap > 1e-3:
                nxt = other + (nxt - other) / gap * cur
                gap = cur
            if (gap < 1.8 and gap < cur) or (gap > 3.0 and gap > cur):
                continue
            if np.linalg.norm(nxt - slots[s_]) > 3.0 and np.linalg.norm(nxt - slots[s_]) > np.linalg.norm(pos[s_] - slots[s_]):
                continue
            audit["travel_blocks"][s_] += float(np.linalg.norm(nxt - pos[s_]))
            pos[s_] = nxt
        last_tick = tick
        gap = float(np.linalg.norm(pos[0] - pos[1]))
        axis = pos[1] - pos[0]
        phi = math.atan2(float(axis[1]), float(axis[0]))
        if phi_s is None:
            phi_s = phi
        phi += 2 * math.pi * round((phi_s - phi) / (2 * math.pi))
        phi_s += (phi - phi_s) * 0.04        # the camera follows the pair's turn, slowly
        cam_track.append(((pos[0] + pos[1]) / 2.0, phi_s))
        audit["gap_min"] = min(audit["gap_min"], gap)
        audit["gap_max"] = max(audit["gap_max"], gap)
        poses, thetas = [], []
        for s_, objs in enumerate(rigs):
            clip, local, w, pclip, plocal, pw, st = sample(p, s_, tick)
            ws = max(0.0, 1.0 - w - pw)
            pose = _mixpose([(_pose(clips[STANCE], st), ws), (_pose(clips[pclip], plocal), pw),
                             (_pose(clips[clip], local), w)])
            fvec = pos[1 - s_] - pos[s_]
            theta = math.atan2(fvec[0], -fvec[1])
            poses.append(pose)
            thetas.append(theta)
            ents[s_].location = (float(pos[s_][0]), float(pos[s_][1]), 0.0)
            ents[s_].rotation_euler = (0.0, 0.0, theta)
            ents[s_].keyframe_insert("location", frame=f_)
            ents[s_].keyframe_insert("rotation_euler", frame=f_)
            for name, e in objs.items():
                if name not in mcrig.PARTS:
                    continue
                pivot = mcrig.PARTS[name][1]
                cc = pose.get(name, {})
                rot, pp = cc.get("rot", (0, 0, 0)), cc.get("pos", (0, 0, 0))
                e.location = (pivot[0] + pp[0], pivot[1] - pp[1], pivot[2] + pp[2])
                e.rotation_euler = tuple(math.radians(r) for r in rot)
                e.keyframe_insert("location", frame=f_)
                e.keyframe_insert("rotation_euler", frame=f_)
        # audit: no blade through the partner's torso or head (practice blows stop short)
        worlds = [mcrig.pose_matrices(ps) for ps in poses]
        for s_ in (0, 1):
            pom, _, tip = ck.sword_points(worlds[s_])
            o = 1 - s_
            inv_t = np.linalg.inv(worlds[o]["torso"])
            inv_h = np.linalg.inv(worlds[o]["head"])
            for u in np.linspace(0.3, 1.0, 12):
                pm = pom + (tip - pom) * u
                pw_ = _to_world(pos[s_], thetas[s_], pm)
                po = _to_model(pos[o], thetas[o], pw_)
                lt = mcrig.xform(inv_t, po)
                lh = mcrig.xform(inv_h, po)
                if _inside(lt, (-5, -12, -2.5), (10, 12, 5)) or _inside(lh, (-4, -8, -4), (8, 8, 8)):
                    audit["penetration_frames"] += 1
                    depth = min(min(lt[0] + 5, 5 - lt[0]), min(lt[2] + 2.5, 2.5 - lt[2]))
                    audit["max_depth_px"] = max(audit["max_depth_px"], round(float(depth), 2))
                    break
        # blade-to-blade at every parried blow
        for ct in contacts:
            if ct[4] or ct[2] != "PARRY" or not (tick - 20.0 / fps < ct[0] <= tick):
                continue
            segs = []
            for s_ in (0, 1):
                pom, _, tip = ck.sword_points(worlds[s_])
                segs.append((_to_world(pos[s_], thetas[s_], pom), _to_world(pos[s_], thetas[s_], tip)))
            (a0, a1), (b0, b1) = segs
            us = np.linspace(0.0, 1.0, 24)
            pa = a0[None, :] + (a1 - a0)[None, :] * us[:, None]
            pb = b0[None, :] + (b1 - b0)[None, :] * us[:, None]
            meets.append({"t": round(ct[0] / 20.0, 2), "high": ct[3],
                          "blade_to_blade_px": round(float(np.min(np.linalg.norm(pa[:, None] - pb[None, :], axis=2))), 2)})
    for ob in ents + [o for objs in rigs for o in objs.values()]:
        ad = ob.animation_data
        if ad and ad.action:
            for fc in ad.action.fcurves:
                for kp in fc.keyframe_points:
                    kp.interpolation = "LINEAR"
    sc.frame_start, sc.frame_end = 0, n - 1
    d = os.path.join(ck.VIDEO_ROOT, "guard_drill_reel_v2")
    os.makedirs(d, exist_ok=True)
    info = {"seed": seed, "seconds": seconds, "start_gap_blocks": 2,
            "speed": [round(speed(seed, 0), 3), round(speed(seed, 1), 3)],
            "active_fighting_fraction_session": round(active_fraction(p), 3),
            "contacts": [{"t": round(x[0] / 20.0, 2), "attacker": x[1], "answer": x[2], "high": x[3], "feint": x[4]}
                         for x in contacts if x[0] < ticks],
            "clips_in_reel": sorted({CLIP_KEYS[x.clip][12:] for x in p["a"] + p["b"] if x.start < ticks}),
            "breathers": [{"slot": 0 if x in p["a"] else 1, "t": round(x.start / 20.0, 2)}
                          for x in p["a"] + p["b"] if x.clip == BREATHER and x.start < ticks],
            "audit": audit, "blade_meets": meets}
    json.dump(info, open(os.path.join(d, "reel.json"), "w"), indent=1)
    print("REELINFO", json.dumps(info)[:900])
    if "--norender" in sys.argv:
        return
    ck._setup_workbench((960, 540) if args["full"] else (640, 360))
    # a camera rig that stays side-on to the pair while they circle
    rig = bpy.data.objects.new("CAMROOT", None)
    sc.collection.objects.link(rig)
    cams = {"side": hsrig.camera("cam_side", (0.0, -5.6, 1.35), (0.0, 0.0, 0.9), lens=30),
            "three_quarter": hsrig.camera("cam_34", (-2.6, -4.6, 2.0), (0.0, 0.0, 0.85), lens=28)}
    for cam in cams.values():
        tgt = bpy.data.objects[cam.name + "_target"]
        cam.parent = rig
        tgt.parent = rig
    for f_, (mid, ph) in enumerate(cam_track):
        rig.location = (float(mid[0]), float(mid[1]), 0.0)
        rig.rotation_euler = (0.0, 0.0, ph)
        rig.keyframe_insert("location", frame=f_)
        rig.keyframe_insert("rotation_euler", frame=f_)
    for name, cam in cams.items():
        fd = os.path.join(d, "_frames_" + name)
        os.makedirs(fd, exist_ok=True)
        for fn in os.listdir(fd):
            os.remove(os.path.join(fd, fn))
        sc.camera = cam
        for f_ in range(n):
            sc.frame_set(f_)
            sc.render.filepath = os.path.join(fd, "f%04d.png" % f_)
            bpy.ops.render.render(write_still=True)
        ck._encode(fd, n, fps, os.path.join(d, "guard_drill_v2_%s.mp4" % name))
        k = 12
        idx = [int(round(i * (n - 1) / (k - 1))) for i in range(k)]
        hsrig._sheet([os.path.join(fd, "f%04d.png" % i) for i in idx], os.path.join(d, "sheet_%s.png" % name))
    print("REEL", d)


if __name__ == "__main__":
    args = hsrig.parse_args()
    names = args["rest"] or list(CLIPS)
    if names == ["REEL"]:
        reel(args)
    else:
        results = {}
        for n_ in names:
            results[n_] = bake_one(n_, args)
        print("ALLCHECKS", json.dumps(results))
