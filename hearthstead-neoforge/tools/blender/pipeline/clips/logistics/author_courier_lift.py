"""COURIER_LIFT and COURIER_SET_DOWN (one-shots), authored in Blender. Run headless:

    blender -b --factory-startup --python author_courier_lift.py -- <lift|setdown|up> [--fast|--full]

Contracts kept from SettlerAnimations / CourierWorkGoal / SettlerModel:
  COURIER_LIFT      1.40 s one-shot (LIFT_DURATION_TICKS 28); hands close on the
                    load at t = 0.60 s = LIFT_GRIP_TICK 12 (CRATE_GRIP). Starts in
                    the neutral stance, ENDS on the carry handoff: arms exactly on
                    COURIER_CARRY_GRIP's frame 0 (the strap grip), torso 9 fwd,
                    head 6, root -0.4 (then WALK_LADEN + the grip layer take over).
  COURIER_SET_DOWN  1.20 s one-shot (SET_DOWN_DURATION_TICKS 24); floor contact
                    at t = 0.60 s = SET_DOWN_TICK 12 (CRATE_DOWN). Starts on the
                    carry pose, ENDS exactly on COURIER_SORT's frame 0 (every bone),
                    so the sort loop picks up with no pop.
    WORK_CONTAINER_UP 1.60 s one-shot (Lumberer CONTAINER_UP_DURATION 32); both
                    palms close on the grounded load at t = 0.60 s =
                    CONTAINER_UP_CONTACT_TICK 12. Starts on the Java neutral stance
                    (the stow's end), ENDS exactly on HAUL_LOG's frame-0 strap grip
                    (the bag animator's WORK_CONTAINER_DOWN starts there too), torso
                    8 fwd, root -0.9: HAULING_LOG's WALK_LADEN + HAUL_LOG take over.
  All three are in anim_check's ENDS_IN_POSE_ALLOWLIST (they hand off, not return).
  SettlerModel resets every bone they author before playing them.

LIFT beats: a breath, then a deep squat with a flat back (knees take it), both
hands down to the load in front; grip at 0.60 and a short hold (the weight
registers, hips sink a touch); the legs drive up, the load comes up close to the
body, the shoulders haul back past upright (1.10), then settle forward into the
carry lean as the fists find the straps.
SET_DOWN beats: from the carry, fists leave the straps and the courier squats,
lowering the load in front; floor contact at 0.60 (decelerating, soft); release,
straighten, and turn into the sorting stance.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lkit  # noqa: E402
from lkit import hsrig, mcrig, ex  # noqa: E402
import motionkit as mk  # noqa: E402

A = lkit.args()
KIND = "up" if "up" in A["rest"] else ("setdown" if "setdown" in A["rest"] else "lift")
LIFT = KIND in ("lift", "up")
CONST = {"lift": "COURIER_LIFT", "setdown": "COURIER_SET_DOWN", "up": "WORK_CONTAINER_UP"}[KIND]
SLUG = CONST.lower()
L = {"lift": 1.40, "setdown": 1.20, "up": 1.60}[KIND]
CONTACT = 0.60
ALL = mcrig.EXPORT_BONES


def pose_from(clip, t=0.0):
    path = os.path.join(lkit.ANIM_DIR, clip + ".animation.json")
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    name = "animation.settler." + clip
    out = {}
    for b, kinds in doc["animations"][name]["bones"].items():
        out[b] = {("rot" if k == "rotation" else "pos"): tuple(ex.sample(doc, name, b, k, t)) for k in kinds}
    return out


NEUTRAL = {"right_arm": {"rot": (-6.0, 4.0, -2.0)}, "left_arm": {"rot": (-6.0, -4.0, 2.0)}}
CARRY = dict(pose_from("courier_carry_grip"))
CARRY.update({"torso": {"rot": (9.0, 2.0, 0.0)}, "head": {"rot": (6.0, 0.0, 0.0)},
              "root": {"pos": (0.0, -0.4, 0.0)}, "cloak": {"rot": (-5.0, 0.0, 0.0)}})
SORT0 = pose_from("courier_sort")
START, END = (NEUTRAL, CARRY) if LIFT else (CARRY, SORT0)
JAVA_NEUTRAL = {"right_arm": {"rot": (-12.0, -8.0, -3.0)}, "left_arm": {"rot": (-8.0, 8.0, 4.0)},
                "torso": {"rot": (2.0, 0.0, 0.0)}, "head": {"rot": (-2.0, 0.0, 0.0)},
                "cloak": {"rot": (2.0, 0.0, 0.0)}, "right_leg": {"rot": (-3.0, 0.0, -2.0)},
                "left_leg": {"rot": (3.0, 0.0, 2.0)}}
HAUL_END = dict(pose_from("haul_log"))
HAUL_END.update({"torso": {"rot": (8.0, 0.0, 0.0)}, "head": {"rot": (-4.0, 0.0, 0.0)},
                 "root": {"pos": (0.0, -0.9, 0.0)}, "cloak": {"rot": (4.0, 0.0, 0.0)}})
if KIND == "up":
    START, END = JAVA_NEUTRAL, HAUL_END
    _w = mcrig.pose_matrices(JAVA_NEUTRAL)
    FEET = tuple(np.array([*mcrig.xform(_w[s + "_shin"], (0, 6, 0))[:1], 24.0,
                           mcrig.xform(_w[s + "_shin"], (0, 6, 0))[2]]) for s in ("right", "left"))
elif LIFT:
    FEET = (np.array([-2.6, 24.0, 0.0]), np.array([2.6, 24.0, 0.0]))
else:
    FEET = (np.array([-3.2, 24.0, 0.9]), np.array([3.0, 24.0, -0.7]))
LOAD = np.array([0.0, 21.0, -6.5])        # the load in front of the feet (hands at its sides)
if KIND == "up":
    LOAD = np.array([0.0, 19.2, -9.0])    # the grounded frame/sack (bag animator's GROUND_PIVOT area)

objs = lkit.scene("settler_lumberer.png" if KIND == "up" else "settler_courier.png")
if KIND == "up":
    lkit.attach_frame(objs, logs=3)
    back = [o for o in bpy.data.objects if o.name.startswith("prop:frame") or o.name.startswith("prop:logs")]
    crate = lkit.box_object("prop:load", [((-4.5, -5.0, -2.0), (9.0, 10.0, 4.0))], lkit.OAK, None)
else:
    back = []
    lkit.attach_sack(objs, 1.0)
    crate = lkit.box_object("prop:load", [((-3.0, -2.5, -3.0), (6.0, 5.0, 6.0))], (0.55, 0.42, 0.25, 1), None)

S, I, O = "smooth", "in2", "out2"
if KIND == "up":
    BODY = {"pelvis_y": [(0.0, 0.0), (0.35, -3.0, S), (CONTACT, -4.3, O), (0.75, -4.5, S), (1.05, -2.0, I),
                         (1.22, -0.5, O), (1.40, -1.0, S), (L, -0.9, S)],
            "spine_x": [(0.0, 2.0), (0.35, 22.0, S), (CONTACT, 36.0, O), (0.75, 37.0, S), (1.05, 20.0, I),
                        (1.20, -2.0, O), (1.38, 6.0, S), (L, 8.0, S)],
            "nod": [(0.0, 0.0), (CONTACT, 12.0, O), (0.8, 8.0, S), (1.15, -6.0, S), (L, 0.0, S)]}
    HAND = [(0.0, (6.2, 12.0, -1.5)), (0.35, (5.0, 16.0, -6.0), S), (CONTACT, (4.3, 18.3, -6.9), O),
            (0.75, (4.3, 18.4, -6.8), S), (1.05, (4.3, 9.5, -6.8), I), (1.20, (4.6, 3.0, -2.5), O),
            (1.38, (3.8, 4.5, -4.4), S), (L, (3.8, 4.5, -4.4), S)]
elif LIFT:
    BODY = {"pelvis_y": [(0.0, 0.0), (0.08, 0.25, S), (0.45, -6.4, O), (CONTACT, -6.6, S), (0.72, -6.9, S),
                         (1.00, -2.0, I), (1.12, -0.9, O), (1.26, -0.2, S), (L, -0.4, S)],
            "spine_x": [(0.0, 0.0), (0.08, -2.0, S), (0.45, 32.0, O), (CONTACT, 30.0, S), (0.72, 33.0, S),
                        (1.00, 10.0, I), (1.12, 2.0, O), (1.26, 6.0, S), (L, 9.0, S)],
            "nod": [(0.0, 0.0), (0.45, 10.0, O), (0.72, 4.0, S), (1.05, -8.0, S), (L, 0.0, S)]}
    # hands (per side mirrored x): at the sides -> the load's sides -> up the body -> straps
    HAND = [(0.0, (6.3, 12.0, -1.0)), (0.10, (6.0, 12.5, -2.0), S), (0.45, (4.2, 20.6, -6.2), O),
            (CONTACT, (3.9, 20.8, -6.5), S), (0.72, (3.9, 20.9, -6.4), S),
            (1.00, (3.9, 12.0, -5.8), I), (1.18, (3.6, 5.0, -4.8), O), (L, (3.4, 3.5, -4.4), S)]
else:
    BODY = {"pelvis_y": [(0.0, -0.4), (0.10, -0.1, S), (0.48, -6.2, S), (CONTACT, -6.5, O), (0.70, -6.3, S),
                         (0.98, -1.6, S), (L, -0.9, S)],
            "spine_x": [(0.0, 9.0), (0.10, 7.0, S), (0.48, 30.0, S), (CONTACT, 31.0, O), (0.70, 30.0, S),
                        (0.98, 14.0, S), (L, 12.0, S)],
            "nod": [(0.0, 0.0), (0.45, 12.0, S), (CONTACT, 14.0, O), (0.9, 6.0, S), (L, 0.0, S)]}
    HAND = [(0.0, (3.4, 3.5, -4.4)), (0.16, (3.9, 9.0, -6.2), S), (0.48, (3.9, 19.8, -6.5), S),
            (CONTACT, (3.9, 20.8, -6.5), O), (0.70, (4.6, 20.2, -5.6), S), (0.98, (5.2, 13.0, -4.0), S),
            (L, (5.2, 12.0, -3.0), S)]
prev = {}


def val(name, t):
    return mk.track([(p[0], (p[1], 0, 0)) + tuple(p[2:]) for p in BODY[name]], t)[0]


def blend(ch, pose, w):
    if w <= 1e-5:
        return
    for b in ALL:
        want = pose.get(b, {})
        have = ch.setdefault(b, {})
        for k in ("rot", "pos"):
            if b in mcrig.ROTATION_ONLY and k == "pos":
                continue
            a = have.get(k, (0.0, 0.0, 0.0))
            z = want.get(k, (0.0, 0.0, 0.0))
            have[k] = tuple(x + (y - x) * w for x, y in zip(a, z))


def solve(t):
    t = min(max(t, 0.0), L)
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, val("pelvis_y", t), 0.0)},
          "torso": {"rot": (val("spine_x", t), 0.0, 0.0)}}
    ch["cloak"] = {"rot": (max(-10, min(20, -4.0 + 0.4 * val("spine_x", t))), 0.0, 0.0)}
    hx, hy, hz = mk.track([(p[0], tuple(p[1])) + tuple(p[2:]) for p in HAND], t)
    for side, s in (("right", -1), ("left", 1)):
        lkit.arm_ik_world(ch, side, np.array([s * hx, hy, hz]), (s * 0.8, 0.8, 0.5), prev, lower=5.5)
    # QA 2026-09-26: the eyes used to jump between the load and the horizon in ONE
    # frame at 0.20 / 0.90 s (40-48 deg head snaps). Ease the look target instead.
    far = np.array([0.0, 4.0, -40.0])
    on_load = lkit_smooth((t - 0.10) / 0.22) * (1.0 - lkit_smooth((t - 0.78) / 0.30))
    lkit.head_aim(ch, far + (np.asarray(LOAD, float) - far) * on_load, w_pitch=0.5, w_yaw=0.5,
                  nod=val("nod", t))
    lkit.legs_ik(ch, FEET, prev, knee=(0.0, 0.0, -1.0), knee_out=0.35)
    # exact neighbour poses on the first and last frames
    w0 = 1.0 - lkit_smooth(t / 0.12)
    w1 = lkit_smooth((t - (L - 0.22)) / 0.22)
    blend(ch, START, w0)
    blend(ch, END, w1)
    return ch


def lkit_smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


times, samples = hsrig.bake(solve, L, objs)
for f, t in enumerate(times):
    for o in back:
        lkit.key_world(o, f, visible=t >= 1.18)
    if KIND == "up":
        held = CONTACT <= t < 1.18
        if t >= 1.18:
            lkit.key_world(crate, f, loc=(LOAD[0], LOAD[1], LOAD[2]), visible=False)
            continue
    elif LIFT:
        held = t >= CONTACT
    else:
        held = t < CONTACT
    if held:
        w = mcrig.pose_matrices(samples[f])
        mid = 0.5 * (lkit.palm(w, "right", 5.5) + lkit.palm(w, "left", 5.5))
        loc = (mid[0], mid[1] + 0.5, mid[2] - 0.5)
    else:
        loc = (LOAD[0], LOAD[1], LOAD[2])
    lkit.key_world(crate, f, loc=loc)

wc = mcrig.pose_matrices(samples[int(round(CONTACT * 60))])
gap = float(np.linalg.norm(lkit.palm(wc, "right", 5.5) - lkit.palm(wc, "left", 5.5)))
end_err = max(abs(a - b) for bone in ("right_arm", "left_arm", "right_forearm", "left_forearm")
              for a, b in zip(samples[-1][bone]["rot"], END.get(bone, {}).get("rot", (0, 0, 0))))
checks = {"length_s": L, "loop": False, "contact_t": CONTACT, "contact_tick": 12,
          "palm_height_at_contact_px": round(24.0 - float(lkit.palm(wc, "right", 5.5)[1]), 2),
          "hands_apart_at_contact_px": round(gap, 2),
          "end_pose_arm_err_deg": round(end_err, 4),
          "ends_on": {"lift": "COURIER_CARRY_GRIP frame 0 (arms) + carry torso/head/root",
                      "setdown": "COURIER_SORT frame 0",
                      "up": "HAUL_LOG frame 0 (arms) + torso 8, head -4, root -0.9"}[KIND]}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/logistics/author_courier_lift.py (Blender "
                  + bpy.app.version_string + ")",
        "contract": (f"{CONST} {L} s one-shot; contact t=0.60 s = tick 12 ("
                     + {"lift": "LIFT_GRIP_TICK, CRATE_GRIP", "setdown": "SET_DOWN_TICK, CRATE_DOWN",
                                "up": "CONTAINER_UP_CONTACT_TICK"}[KIND] + ")"),
        "checks": checks}
if A["export"]:
    lkit.export(CONST, L, False, times, samples, keep_times=(CONTACT,), meta=meta)
if A["fast"] or A["full"]:
    lkit.preview(SLUG, L, cams={"side": hsrig.camera("cam_side", (-3.8, -0.3, 1.0), (0.0, -0.3, 0.8), lens=40),
                                "front34": hsrig.camera("cam_front34", (-2.4, -2.8, 1.5), (0.0, -0.2, 0.8), lens=40)},
                 full=A["full"])
    if "review" in A["rest"]:
        lkit.review_sheet(SLUG, L, (-1.4, -3.2, 1.4), (0.0, -0.2, 0.75), lens=42, n=6)
