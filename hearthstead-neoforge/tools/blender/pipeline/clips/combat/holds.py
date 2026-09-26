"""Combat holds and overlays: GUARD_PATROL, SHIELD_BLOCK, ARCHER_STANCE,
ARCHER_PATROL, HUNTER_LOOSE. Lengths / loop flags kept from SettlerAnimations.

    blender -b --factory-startup --python guard_patrol.py -- [--fast|--full] [--no-export]

GUARD_PATROL   4.0 s loop  overlay: torso, head, arms (+elbows). Same arm pose as
                           GUARD_STANCE's rest, so the additive strikes land on the
                           same sword whether the guard stands or walks.
SHIELD_BLOCK   1.6 s loop  full body: crouched behind a real OFFHAND shield.
ARCHER_STANCE  3.2 s loop  full body: bow held low-ready in front, arrow hand on
                           the string. (Sign fix: the old Java clip used +60..+90
                           arm X believing positive = forward; on this rig negative
                           X swings the arm forward -- this clip is authored in that
                           verified convention.) applyBowMotion lerps from this
                           pose into the procedural draw.
ARCHER_PATROL  3.6 s loop  overlay: arms (+elbows) and head; bow carried low.
HUNTER_LOOSE   1.2 s shot  torso/head/cloak only (body-only contract). The draw
                           builds back tension until the release at tick 14
                           (HunterWorkGoal.HUNT_RELEASE_TICK, t = 0.70 s), then a
                           short follow-through and settle.
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

S = ck.SMO
c = hsrig.ctrl
PATROL_BONES = ["torso", "head", "right_arm", "right_forearm", "left_arm", "left_forearm"]


def breathe(v, amp, L, rise=0.55):
    return [(0.0, v, *S), (rise * L, v + amp, *S), (L, v)]


def static(props, L, loop=True, extra=None):
    """Key every CTRL prop at a constant value (plus optional breath/drift curves)."""
    K = {p: [(0.0, v), (L, v)] for p, v in props.items()}
    for p in ck.FEET_PROPS:
        K.setdefault(p, [(0.0, 0.0), (L, 0.0)])
    if extra:
        K.update(extra)
    ck.key_all(K, L, loop)


def guard_patrol(B):
    L = 4.0
    P = dict(B)
    P.update({"hip_x": 0.0, "hip_y": 0.0, "hip_z": 0.0, "hip_yaw": 0.0, "sp_y": 2.0, "hd_y": -2.0, "hd_x": -2.0})
    ex = {
        "sp_x": breathe(P["sp_x"], -0.7, L),
        "sp_lift": breathe(0.0, 0.25, L),
        "sp_y": [(0.0, 2.0, *S), (1.4, 2.8, *S), (3.0, 1.2, *S), (4.0, 2.0)],
        # ONE slow scan to the settler's right (stance scans left), so a guard
        # passing between post and patrol covers both flanks
        "hd_y": [(0.0, -2.0, *S), (0.9, -1.0, *S), (1.8, -2.0, *S), (2.6, 15.0, *S), (3.1, 13.5, *S),
                 (3.6, -0.8, *S), (4.0, -2.0)],
        "hd_x": [(0.0, -2.0, *S), (2.6, -3.0, *S), (4.0, -2.0)],
    }
    for p, d in (("ar_x", -0.8), ("el_r", 0.5), ("al_x", -1.0), ("el_l", 0.8)):
        ex[p] = breathe(P[p], d, L)
    static(P, L, True, ex)
    return L, True, ck.make_solve(L, True, cloak_drag=False), PATROL_BONES, "sword", None, \
        "4.0 s loop overlay (torso/head/arms); WALK/GUARD_WALK own root+legs"


def shield_block(B):
    L = 1.6
    P = dict(B)
    P.update({"hip_x": 0.1, "hip_y": -1.5, "hip_z": 0.5, "hip_yaw": 14.0, "hip_r": 0.0,
              "sp_x": 13.0, "sp_y": 8.0, "sp_z": -1.0,
              "hd_x": 7.0, "hd_y": -18.0, "hd_z": 0.0, "ck_x": 7.0, "ck_z": -3.0,
              # shield arm: forward and yawed in so the face covers the chest and chin
              "al_x": -58.0, "al_y": 32.0, "al_z": 6.0, "el_l": -34.0, "tw_l": 0.0,
              # sword drawn back at the hip, point forward: ready to counter
              "ar_x": 8.0, "ar_y": -16.0, "ar_z": 12.0, "el_r": -62.0, "tw_r": 0.0})
    ex = {
        # braced breathing: short, tense, the whole mass sinks into the brace and back
        "hip_y": breathe(P["hip_y"], -0.18, L, 0.5),
        "sp_x": breathe(P["sp_x"], 1.2, L, 0.5),
        "sp_lift": breathe(0.0, -0.2, L, 0.5),
        "al_x": breathe(P["al_x"], -2.0, L, 0.5),
        "el_l": breathe(P["el_l"], 1.5, L, 0.5),
        "ar_x": breathe(P["ar_x"], 1.5, L, 0.5),
        "hd_x": breathe(P["hd_x"], 1.0, L, 0.5),
    }
    static(P, L, True, ex)
    return L, True, ck.make_solve(L, True, cloak_drag=False), None, "sword", "shield", \
        "1.6 s loop, full body (real OFFHAND shield)"


def _bow_hand_target(t, world):
    """Arrow hand rests on the string just inside the bow grip."""
    hand = mcrig.xform(world["right_forearm"], (0, 6, 0))
    return hand + np.array([2.2, -1.4, 0.8]), 1.0


def archer_stance(B):
    L = 3.2
    P = {p: 0.0 for p in ck.STANCE_CTRL}
    P.update({"hip_x": 0.2, "hip_y": -0.3, "hip_z": 0.0, "hip_yaw": 12.0, "sp_x": 3.0, "sp_y": 6.0,
              "hd_x": -2.0, "hd_y": -16.0, "ck_x": 4.0,
              # bow arm: low-ready, bow diagonal in front of the belly
              "ar_x": -24.0, "ar_y": -14.0, "ar_z": 8.0, "el_r": -28.0, "tw_r": 0.0,
              "al_x": -20.0, "al_y": -10.0, "al_z": -6.0, "el_l": -45.0, "tw_l": 0.0})
    ex = {
        "sp_lift": breathe(0.0, 0.28, L),
        "sp_x": breathe(P["sp_x"], -0.8, L),
        "ar_x": breathe(P["ar_x"], -1.2, L),
        "hip_x": [(0.0, 0.2, *S), (1.4, 0.45, *S), (2.6, 0.05, *S), (3.2, 0.2)],
        "hd_y": [(0.0, -16.0, *S), (1.2, -13.0, *S), (2.0, -21.0, *S), (2.6, -20.0, *S), (3.2, -16.0)],
        "hd_x": [(0.0, -2.0, *S), (1.8, -1.0, *S), (3.2, -2.0)],
        "ck_x": breathe(4.0, 1.2, L),
    }
    static(P, L, True, ex)
    return L, True, ck.make_solve(L, True, cloak_drag=False, left_ik=_bow_hand_target), None, "bow", None, \
        "3.2 s loop, full body; procedural draw (applyBowMotion) blends from this pose"


def archer_patrol(B):
    L = 3.6
    P = {p: 0.0 for p in ck.STANCE_CTRL}
    P.update({"sp_x": 3.0, "ck_x": 4.0,
              "ar_x": -12.0, "ar_y": -6.0, "ar_z": 5.0, "el_r": -22.0,
              "al_x": 4.0, "al_y": 0.0, "al_z": -4.0, "el_l": -18.0})
    ex = {
        "ar_x": breathe(-12.0, -1.0, L),
        "al_x": breathe(4.0, -1.0, L),
        "hd_y": [(0.0, 0.0, *S), (0.9, 2.0, *S), (1.8, 0.0, *S), (2.45, 15.0, *S), (2.85, 14.0, *S),
                 (3.4, 0.5, *S), (3.6, 0.0)],
        "hd_x": [(0.0, 0.5, *S), (2.45, -1.0, *S), (3.6, 0.5)],
    }
    static(P, L, True, ex)
    return L, True, ck.make_solve(L, True, cloak_drag=False), \
        ["head", "right_arm", "right_forearm", "left_arm", "left_forearm"], "bow", None, \
        "3.6 s loop overlay (arms/head); WALK owns feet, hips, torso, cloak"


def hunter_loose(B):
    L = 1.2
    P = {p: 0.0 for p in ck.STANCE_CTRL}
    P.update({"ar_x": -90.0, "ar_y": -6.0, "al_x": -90.0, "al_y": 28.0, "el_l": -60.0})  # preview only
    ex = {
        # draw: the back loads -- chest opens toward the bow side, shoulders square
        "sp_y": [(0.0, 0.0, *S), (0.30, 3.5, *S), (0.55, 6.0), (0.70, 6.2, *ck.DEC3), (0.78, 3.6, *S),
                 (0.95, 1.6, *S), (1.2, 0.0)],
        "sp_x": [(0.0, 0.0, *S), (0.40, -1.8, *S), (0.70, -2.0, *ck.DEC3), (0.76, 0.8, *S), (0.95, 0.3, *S),
                 (1.2, 0.0)],
        "sp_lift": [(0.0, 0.0, *S), (0.50, 0.3), (0.70, 0.32, *ck.DEC3), (0.80, 0.05, *S), (1.2, 0.0)],
        # head holds the aim: counter-yaws the chest, never moves at the release
        "hd_y": [(0.0, 0.0, *S), (0.30, -3.5, *S), (0.55, -6.0), (0.70, -6.2, *ck.DEC3), (0.78, -3.6, *S),
                 (0.95, -1.6, *S), (1.2, 0.0)],
        "hd_x": [(0.0, 0.0, *S), (0.40, 1.8, *S), (0.70, 2.0, *ck.DEC3), (0.76, -0.8, *S), (0.95, -0.3, *S),
                 (1.2, 0.0)],
        # the release shivers the cloak
        "ck_x": [(0.0, 0.0), (0.55, 2.0), (0.70, 2.2, *ck.DEC3), (0.76, 5.0, *S), (0.9, 1.0, *S), (1.2, 0.0)],
    }
    static(P, L, False, ex)
    return L, False, ck.make_solve(L, False, cloak_drag=False), ["torso", "head", "cloak"], "bow", None, \
        "1.2 s one-shot, torso/head/cloak only; release t=0.70 s = tick 14 (HUNT_RELEASE_TICK)"


CLIPS = {"GUARD_PATROL": (guard_patrol, "settler_guard.png"),
         "SHIELD_BLOCK": (shield_block, "settler_guard.png"),
         "ARCHER_STANCE": (archer_stance, "settler_archer.png"),
         "ARCHER_PATROL": (archer_patrol, "settler_archer.png"),
         "HUNTER_LOOSE": (hunter_loose, "settler_hunter.png")}


def run(const):
    args = hsrig.parse_args()
    fn, tex = CLIPS[const]
    base, B = ck.stance_base()
    L, loop, solve, bones, ritem, litem, contract = fn(B)
    objs = ck.build(tex, right=ritem, left=litem)
    # build() resets the scene (and CTRL): key again after it
    L, loop, solve, bones, ritem, litem, contract = fn(B)
    times, samples = hsrig.bake(solve, L, objs)
    if const == "HUNTER_LOOSE":
        # body-only: SettlerModel resets legs/root first; export only the body channels
        pass
    err = max(ck.sole_error(s, ck.FOOT_R, ck.FOOT_L) for s in samples) if bones is None else 0.0
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract, "sole_max_error_px": round(err, 3)}
    print("CHECKS", checks)
    if args["export"]:
        doc, e = ck.export(const, L, loop, times, samples, bones=bones,
                           keep_times=(0.55, 0.70) if const == "HUNTER_LOOSE" else (),
                           meta={"source": "tools/blender/pipeline/clips/combat/holds.py (Blender "
                                           + bpy.app.version_string + ")", "contract": contract},
                           out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(e, 4)
    ck.write_report(const.lower(), checks)
    ck.preview(const.lower(), L, args, loop=loop, step=2 if L >= 2.0 else 1)
