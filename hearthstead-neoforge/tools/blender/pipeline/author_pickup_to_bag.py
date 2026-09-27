"""PICKUP_TO_BAG (owner name) == GROUND_ITEM_PICKUP + WORK_CONTAINER_STOW, authored in Blender.

    blender -b --factory-startup --python author_pickup_to_bag.py -- --clip pickup|stow [--render]

The game plays the owner's one action as TWO server-timed clips with a carry
walk between them (GroundedBagSession / LumbererWorkGoal):
  GROUND_ITEM_PICKUP  1.00 s one-shot; grab contact t = 0.60 s = tick 12
                      (GROUND_PICKUP_CONTACT_TICK: the log enters the real
                      OFFHAND); phase ends at clock 20; state stops at 1050 ms.
  WORK_CONTAINER_STOW 1.10 s one-shot; stow contact t = 0.60 s = tick 12
                      (CONTAINER_STOW_CONTACT_TICK, BAG_STOW sound); phase ends
                      at clock 22.
Pickup ends and stow starts on the WALK_CARRY_ITEM cradle (left arm
(-48.21,57.3,-41.24), left forearm -47.38, torso 7, root -0.45) so the carry gait between
them is unchanged. The LEFT arm pitch stays negative throughout, so that flip
is a no-op on these curves.

Two-handed log: the left (offhand) hand owns the item, the right hand (still
holding the axe) closes on the other end of the log for the lift and again for
the stuff into the sack. The in-hand log is the vanilla block-item transform,
solved so the drawn log sits exactly on the ground item at the contact tick.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bagclip_rig as br  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

ARGS = br.argv()
CLIP = ARGS[ARGS.index("--clip") + 1] if "--clip" in ARGS else "pickup"
PICKUP = CLIP == "pickup"
CONST = "GROUND_ITEM_PICKUP" if PICKUP else "WORK_CONTAINER_STOW"
SLUG = "pickup_to_bag_1_pickup" if PICKUP else "pickup_to_bag_2_stow"
LENGTH = 1.00 if PICKUP else 1.10
CONTACT = 0.60
OUT = os.path.join(br.WORK, "out", SLUG)
os.makedirs(OUT, exist_ok=True)

sc = hsrig.reset()
objs = hsrig.build_scene(os.path.join(br.TEX_DIR, "settler_lumberer.png"), br.AXE)
hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
log = br.box_mesh_object("log", [((0, 0, 0), (16, 16, 16))], (0.42, 0.30, 0.17, 1.0))
ground_log = br.box_mesh_object("ground_log", [((-2, -2, -2), (4, 4, 4))], (0.42, 0.30, 0.17, 1.0))
sack = br.box_mesh_object("sack", br.SACK_CUBES, br.BURLAP)
SACK_SCALE = 1.05          # full sack (SettlerModel COURIER_PACK_MAX_SCALE)

FEET = br.stance_feet()
NEUTRAL = {"torso": {"rot": (2, 0, 0)}, "right_arm": {"rot": (-12, -8, -3)},
           "left_arm": {"rot": (-8, 8, 4)}}
# WALK_CARRY_ITEM (Blender, 2026-09-26): the left arm cradles the log against the
# chest (constant over the cycle); the right arm is its mid-swing value.
CARRY = {"root": {"pos": (0, -0.45, 0)}, "torso": {"rot": (7, 0, 0)},
         "right_arm": {"rot": (0.0, 0.0, 4.0)}, "left_arm": {"rot": (-48.21, 57.3, -41.24)},
         "right_forearm": {"rot": (-19.0, 0, 0)}, "left_forearm": {"rot": (-47.38, 0, 0)}}
wn, wc = mcrig.pose_matrices(NEUTRAL), mcrig.pose_matrices(CARRY)
RN, LN = br.palm(wn, "right"), br.palm(wn, "left")
RC, LC = br.palm(wc, "right"), br.palm(wc, "left")
LOG_CARRY = br.block_centre_in_hand(wc["left_forearm"])
HELD_LOCAL = br.held_block_local()      # drawn log in the carry pose

# The ground item (ItemEntity block, ~4 px, bobbing just above the grass) a
# block-length ahead and a little left: the side the offhand reaches for.
LOG_GROUND = np.array([2.0, 20.6, -9.5])
SACK_PIVOT = np.array([0.5, br.GROUND - 8.0 * SACK_SCALE, -12.0])
MOUTH = SACK_PIVOT + SACK_SCALE * np.array([0.0, 0.0, 3.0])

if PICKUP:
    K = {
        "torso_x": [(0.0, 2.0), (0.10, -1.5), (0.28, 22.0), (0.46, 44.0), (0.60, 46.0),
                    (0.69, 46.5), (0.78, 40.0), (0.90, 17.0), (1.00, 7.0)],
        "torso_y": [(0.0, 0.0), (0.30, -6.0), (0.60, -7.0), (0.85, -3.0), (1.00, 0.0)],
        "torso_z": [(0.0, 0.0), (0.30, -3.0), (0.60, -3.5), (0.90, 0.0), (1.00, 0.0)],
        "root_y": [(0.0, 0.0), (0.10, 0.1), (0.30, -3.4), (0.48, -6.5), (0.60, -6.8),
                   (0.69, -7.0), (0.77, -6.0), (0.89, -1.3), (1.00, -0.45)],
        "root_z": [(0.0, 0.0), (0.10, -0.1), (0.48, 1.9), (0.69, 2.0), (0.89, 0.4), (1.00, 0.0)],
        "root_x": [(0.0, 0.0), (0.48, 0.6), (0.70, 0.6), (1.00, 0.0)],
        "lh_on": [(0.0, 0.0), (0.12, 0.0), (0.46, 1.0), (1.00, 1.0)],
        "rh_on": [(0.0, 0.0), (0.18, 0.0), (0.54, 1.0), (0.84, 1.0), (0.97, 0.0), (1.00, 0.0)],
        "head_nod": [(0.0, 0.0), (0.08, 8.0), (0.60, 4.0), (0.90, 0.0), (1.00, -3.0)],
    }
    br.key(K)
    br.key_vec("lh_free", [(0.0, LN), (0.12, LN + np.array([0.5, 0.3, -1.5])), (1.0, LC)])
    br.key_vec("rh_free", [(0.0, RN), (0.18, RN + np.array([-0.4, 0.2, -1.0])),
                           (0.90, RC + np.array([-0.5, 1.5, -2.5])), (1.00, RC)])
    # log path after the grab: a heavy, slow break from the ground, then the
    # legs bring it to the belly and into the Java carry pose.
    br.key_vec("log", [(0.60, LOG_GROUND), (0.69, LOG_GROUND + np.array([0, -0.4, 0.2])),
                       (0.78, LOG_GROUND + np.array([0.2, -2.8, 1.2])),
                       (0.90, (LOG_GROUND + LOG_CARRY) / 2 + np.array([0, -1.2, 0])),
                       (1.00, LOG_CARRY)])
else:
    K = {
        "torso_x": [(0.0, 7.0), (0.12, 3.0), (0.30, 16.0), (0.46, 31.0), (0.60, 38.0),
                    (0.70, 35.0), (0.84, 15.0), (0.98, -1.5), (1.10, 2.0)],
        "torso_y": [(0.0, 0.0), (0.46, -4.0), (0.60, -3.0), (1.10, 0.0)],
        "root_y": [(0.0, -0.45), (0.12, -0.2), (0.30, -1.8), (0.46, -4.6), (0.60, -6.0),
                   (0.70, -5.7), (0.84, -1.6), (0.98, 0.15), (1.10, 0.0)],
        "root_z": [(0.0, 0.0), (0.46, 1.2), (0.62, 1.4), (0.84, 0.4), (1.10, 0.0)],
        "lh_on": [(0.0, 1.0), (0.60, 1.0), (0.70, 0.0), (1.10, 0.0)],
        "rh_on": [(0.0, 0.0), (0.20, 1.0), (0.61, 1.0), (0.71, 0.0), (1.10, 0.0)],
        "head_nod": [(0.0, -3.0), (0.30, 0.0), (0.60, 2.0), (0.80, -2.0), (1.10, -2.0)],
        # rising out of the stoop, the free left hand pushes off the left knee
        "rh_knee": [(0.0, 0.0), (0.70, 0.0), (0.77, 1.0), (0.86, 1.0), (0.98, 0.0), (1.10, 0.0)],
    }
    br.key(K)
    # log: lift to the chest (anticipation), swing over the open mouth, then the
    # accelerating shove through the rim; contact at 0.60 (item leaves the hand)
    br.key_vec("log", [(0.0, LOG_CARRY), (0.12, LOG_CARRY + np.array([-0.5, -1.8, -0.8])),
                       (0.30, (LOG_CARRY + MOUTH) / 2 + np.array([0, -4.5, 0])),
                       (0.46, MOUTH + np.array([0, -3.4, -0.3])),
                       (0.49, MOUTH + np.array([0, -2.8, -0.3]), "QUAD", "EASE_IN"),
                       (0.60, MOUTH + np.array([0, 2.2, 0.2]), "SINE", "EASE_OUT"),
                       (0.68, MOUTH + np.array([0, 3.4, 0.3]))])     # follow-through: hands keep driving in
    # free paths after the stow: left withdraws, right gives the sack one tamp
    br.key_vec("lh_free", [(0.60, MOUTH + np.array([2.5, 3.2, 1.0])), (0.72, MOUTH + np.array([4.0, -2.5, 2.0])),
                           (0.90, LN + np.array([0.0, -2.0, -4.5])), (1.10, LN)])
    br.key_vec("rh_free", [(0.0, RC), (0.60, MOUTH + np.array([-2.5, 2.6, 0.8])),
                           (0.66, MOUTH + np.array([-2.0, 0.2, 0.8])),
                           (0.73, MOUTH + np.array([-1.5, 1.2, 0.8])),
                           (0.90, RN + np.array([-0.5, -1.0, -2.0])), (1.10, RN)])
c = hsrig.ctrl


def torso_x(t):
    return c("torso_x", t)


def log_target(t):
    if PICKUP:
        return LOG_GROUND.copy() if t < 0.60 else br.cv("log", t)
    return br.cv("log", min(t, 0.68))


def solve(t):
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
          "torso": {"rot": (torso_x(t), c("torso_y", t), c("torso_z", t))}}
    ch["cloak"] = {"rot": br.cloak(t, torso_x(t), torso_x)}
    world = mcrig.pose_matrices(ch)
    prev = getattr(solve, "_prev", {})
    br.begin_frame(not prev)
    br.leg_ik(ch, world, FEET, prev)
    knee = br.knee_point(mcrig.pose_matrices(ch), "right")
    # STOW: elbows flare out and a little forward as the arms drive the log down
    # (keeps the offhand upper arm ahead of the chest, pitch <= 0)
    lpole, rpole = ((0.9, 0.35, 0.5), (-0.9, 0.35, 0.5)) if PICKUP else ((1.0, 0.1, -0.35), (-1.0, 0.1, -0.35))
    ew = br.edge_weight(t, LENGTH)
    first, last = (NEUTRAL, CARRY) if PICKUP else (CARRY, NEUTRAL)
    edge = first if t < LENGTH / 2 else last
    # left palm: blend free path -> "log in hand sits on the log target"
    logt = log_target(t)
    w = c("lh_on", t)
    free = br.cv("lh_free", t)
    lmatch = dict(match=edge["left_arm"]["rot"], match_w=ew)
    if w < 1e-3:
        br.arm_ik(ch, world, "left", free, lpole, prev, **lmatch)
    else:
        # where the held point would be on the free path, blended onto the log target
        br.arm_ik(ch, world, "left", free, lpole, prev, **lmatch)
        h_free = br.block_centre_in_hand(mcrig.pose_matrices(ch)["left_forearm"])
        _, solve._p = br.arm_ik_point(ch, world, "left", (1 - w) * h_free + w * logt, HELD_LOCAL, lpole,
                                      prev, p0=getattr(solve, "_p", None), **lmatch)
    wl = mcrig.pose_matrices(ch)
    held = br.block_centre_in_hand(wl["left_forearm"])
    # right palm closes on the far (right) end of the log
    wr = c("rh_on", t)
    kw = c("rh_knee", t)
    rfree = (1 - kw) * br.cv("rh_free", t) + kw * (knee + np.array([0.0, -1.0, 0.0]))
    rtgt = (1 - wr) * rfree + wr * (held + np.array([-3.4, 0.6, 0.6]))
    br.arm_ik(ch, world, "right", rtgt, rpole, prev, match=edge["right_arm"]["rot"], match_w=ew)
    look = held if (PICKUP and t >= 0.60) or (not PICKUP and t < 0.62) else (LOG_GROUND if PICKUP else MOUTH)
    br.head_look(ch, world, look, w_pitch=0.4 if PICKUP else 0.3, w_yaw=0.6, nod=c("head_nod", t))
    br.blend_to_pose(ch, edge, br.edge_weight(t, LENGTH, ramp=0.12))
    if PICKUP:
        # QA 2026-09-26: tip the MAINHAND axe forward in the fist through the stoop
        # (it swept 3.5 px through the face at 0.40 s); neutral at both ends.
        wk = [(0.0, 0.0), (0.25, 30.0), (0.85, 30.0), (1.0, 0.0)]
        wx = 0.0
        for (a, va), (b, vb) in zip(wk, wk[1:]):
            if a <= t <= b:
                x = (t - a) / (b - a)
                wx = va + (vb - va) * x * x * (3 - 2 * x)
        ch["right_item"] = {"rot": (wx, 0.0, 0.0)}
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_arm", "right_leg", "left_leg")}
    solve._held = held
    return ch


held_path = []


def solve_rec(t):
    ch = solve(t)
    held_path.append(solve._held.copy())
    return ch


times, samples = hsrig.bake(solve_rec, LENGTH, objs)

for f, t in enumerate(times):
    w = mcrig.pose_matrices(samples[f])
    in_hand = (t >= CONTACT) if PICKUP else (t < CONTACT)
    br.key_prop(log, br.block_in_hand_matrix(w["left_forearm"]), f, visible=in_hand)
    br.key_prop(ground_log, mcrig.T(*LOG_GROUND), f, visible=PICKUP and t < CONTACT)
    br.key_prop(sack, mcrig.T(*SACK_PIVOT) @ mcrig.mat4(s=SACK_SCALE), f, visible=not PICKUP)

ci = int(round(CONTACT * 60))
checks = br.contact_report(times, samples, FEET, {})
checks["contact_t"] = CONTACT
lw = [K["lh_on"]]
checks["log_err_px_max_while_owned"] = round(max(
    float(np.linalg.norm(held_path[i] - log_target(t))) for i, t in enumerate(times)
    if hsrig.ctrl("lh_on", t) > 0.999 and ((PICKUP and 0.46 <= t <= 0.93) or (not PICKUP and 0.08 <= t <= CONTACT))), 3)
checks["log_error_at_contact_px"] = round(float(np.linalg.norm(
    held_path[ci] - (LOG_GROUND if PICKUP else br.cv("log", CONTACT)))), 3)
checks["log_below_rim_px_at_contact"] = None if PICKUP else round(float(held_path[ci][1] - MOUTH[1]), 2)
checks["left_arm_x_max_deg"] = round(max(s["left_arm"]["rot"][0] for s in samples), 2)
checks["lowest_root_y"] = round(min(s["root"]["pos"][1] for s in samples), 2)
checks["knee_flex_max_deg"] = round(max(s["right_shin"]["rot"][0] for s in samples), 1)
checks["edge_pose_err_deg"] = round(max(
    abs(a - b) for f, pose in ((0, NEUTRAL if PICKUP else CARRY), (-1, CARRY if PICKUP else NEUTRAL))
    for bone in ("right_arm", "left_arm", "right_forearm", "left_forearm")
    for a, b in zip(samples[f][bone]["rot"], pose.get(bone, {}).get("rot", (0, 0, 0)))), 2)
print("CHECKS", json.dumps(checks))
if checks["left_arm_x_max_deg"] > 0.0:
    print("WARNING left arm pitch goes positive; SettlerModel's -abs flip would mirror it")

contract = ("GROUND_ITEM_PICKUP 1.00 s one-shot; grab contact t=0.60 s = tick 12 "
            "(GROUND_PICKUP_CONTACT_TICK)" if PICKUP else
            "WORK_CONTAINER_STOW 1.10 s one-shot; stow contact t=0.60 s = tick 12 "
            "(CONTAINER_STOW_CONTACT_TICK, BAG_STOW sound)")
meta = {"source": "tools/blender/pipeline/author_pickup_to_bag.py --clip " + CLIP
                  + " (Blender " + bpy.app.version_string + ")",
        "owner_action": "PICKUP_TO_BAG (" + ("1/2 pickup" if PICKUP else "2/2 stow") + ")",
        "contract": contract + "; left arm pitch <= 0 throughout; root translation only",
        "checks": checks}
path, doc, report, worst = br.export(CONST, LENGTH, False, times, samples,
                                     keep_times=(0.0, 0.46, CONTACT, LENGTH), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(br.WORK, SLUG + ".blend"))

if "--render" in ARGS:
    br.render_clip(SLUG, LENGTH, br.cameras(), step=6 if "--quick" in ARGS else 2)
