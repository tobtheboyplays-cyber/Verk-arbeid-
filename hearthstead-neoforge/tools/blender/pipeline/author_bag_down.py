"""BAG_DOWN (owner name) == SettlerAnimations.WORK_CONTAINER_DOWN, authored in Blender.

    blender -b --factory-startup --python author_bag_down.py -- [--render]

Contract kept (LumbererWorkGoal / GroundedBagSession / SettlerEntity):
  length 1.40 s, one-shot; ground contact at t = 1.00 s = tick 20
  (CONTAINER_DOWN_CONTACT_TICK, BAG_DOWN sound via WorkSoundSync at clock 20);
  the phase ends at clock 28 (1.40 s) and SettlerEntity stops the state at 1450 ms.
  First frame = HAUL_LOG's strap grip (arms/forearms of the Blender HAUL_LOG
  frame 0, 2026-09-26), last frame = the Java
  neutral (arms -12/-8, torso 2) so neither neighbour pops.

Beats (weight first): 0.00-0.25 slow shrug under the straps (unweight, lean back);
0.25-0.52 slip the load round the LEFT side to the front, torso counter-turns;
0.52-1.00 controlled lowering through the knees with a fairly straight back,
decelerating into a soft set-down; 1.00-1.05 hold on contact; 1.05-1.20 hands
release and the hips drive up; 1.20-1.40 small relief stretch and settle.
Root is translation only (the grounded prop is a root child).
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
CONST = "WORK_CONTAINER_DOWN"
SLUG = "bag_down"
LENGTH = 1.40
CONTACT = 1.00
OUT = os.path.join(br.WORK, "out", SLUG)
os.makedirs(OUT, exist_ok=True)

sc = hsrig.reset()
objs = hsrig.build_scene(os.path.join(br.TEX_DIR, "settler_lumberer.png"), br.AXE)
hsrig.prop_box("ground", (-60, 24, -60), (120, 1, 120), (0.30, 0.45, 0.22, 1))
sack = br.box_mesh_object("sack", br.SACK_CUBES, br.BURLAP)
SACK_SCALE = 1.05          # full sack (SettlerModel COURIER_PACK_MAX_SCALE)

FEET = br.stance_feet()

# Java boundary poses (FK) -> palm points the IK starts / ends on.
# HAUL_LOG (Blender, arm-only layer): fists on the shoulder straps, elbows bent.
START = {"torso": {"rot": (0, 0, 0)}, "right_arm": {"rot": (-14.78, -37.9, 24.59)},
         "left_arm": {"rot": (-14.78, 37.9, -24.59)}, "right_forearm": {"rot": (-102.87, 0, 0)},
         "left_forearm": {"rot": (-102.87, 0, 0)}}
END = {"torso": {"rot": (2, 0, 0)}, "right_arm": {"rot": (-12, -8, -3)},
       "left_arm": {"rot": (-8, 8, 4)}}
w0, w1 = mcrig.pose_matrices(START), mcrig.pose_matrices(END)
R0, L0 = br.palm(w0, "right"), br.palm(w0, "left")
R1, L1 = br.palm(w1, "right"), br.palm(w1, "left")

GROUND_PIVOT = np.array([0.0, br.GROUND - 8.0 * SACK_SCALE, -10.0])

B = "BEZIER"
K = {
    # spine pitch (+ = forward). Lean back into the shrug, then hinge only as
    # far as a straight back allows; the knees do the rest.
    "torso_x": [(0.0, 0.0), (0.20, -4.5), (0.40, 1.0), (0.55, 10.0), (0.80, 24.0),
                (0.97, 31.0), (1.05, 31.5), (1.20, 15.0), (1.31, -1.5), (1.40, 2.0)],
    # counter-turn while the load comes round the left side (- = turn left)
    "torso_y": [(0.0, 0.0), (0.22, 2.0), (0.40, -15.0), (0.55, -9.0), (0.80, -2.0),
                (1.00, 0.0), (1.40, 0.0)],
    "torso_z": [(0.0, 0.0), (0.40, -5.0), (0.60, 1.5), (0.85, 0.0), (1.40, 0.0)],
    # root: rise to unweight, then sit down and back into the lift (+z = back)
    "root_y": [(0.0, 0.0), (0.20, 0.12), (0.40, -0.4), (0.62, -1.6), (0.85, -4.9),
               (0.98, -6.2), (1.05, -6.25), (1.20, -2.6), (1.31, 0.1), (1.40, 0.0)],
    "root_z": [(0.0, 0.0), (0.40, 0.1), (0.62, 0.6), (0.98, 1.6), (1.05, 1.6),
               (1.20, 0.6), (1.40, 0.0)],
    "root_x": [(0.0, 0.0), (0.40, 0.5), (0.70, 0.1), (1.40, 0.0)],
    # hand ownership of the sack (0 = free path, 1 = on the sack)
    # the left hand reaches back for the load at 0.26 and owns it from 0.32 to the
    # release: SettlerModel places the prop from this palm (pivot = palm - grip)
    "lh_on": [(0.0, 0.0), (0.27, 0.0), (0.40, 1.0), (1.05, 1.0), (1.16, 0.0), (1.40, 0.0)],
    "rh_on": [(0.0, 0.0), (0.40, 0.0), (0.52, 1.0), (1.05, 1.0), (1.14, 0.0), (1.40, 0.0)],
    "head_nod": [(0.0, -2.0), (0.20, -6.0), (0.55, 2.0), (1.00, 4.0), (1.25, -3.0), (1.40, -2.0)],
}
br.key(K)
# free palm paths (straps -> shrug -> released -> rest)
br.key_vec("lh_free", [(0.0, L0), (0.16, (4.0, 3.2, -4.0)), (0.30, (5.6, 8.0, 4.4)),
                       (1.05, (5.0, 17.0, -6.0)), (1.20, (6.5, 12.0, -3.5)), (1.40, L1)])
br.key_vec("rh_free", [(0.0, R0), (0.18, (-4.0, 3.2, -4.0)), (0.40, (-2.0, 4.0, -4.5)),
                       (0.52, (-3.5, 9.0, -8.0)), (1.05, (-5.0, 17.0, -6.0)),
                       (1.20, (-6.5, 12.0, -3.5)), (1.40, R1)])
# sack pivot after it leaves the back (model space), decelerating into contact
br.key_vec("sack", [(0.31, (0.0, 1.3, 2.6)), (0.40, (8.8, 4.5, 0.2)), (0.52, (5.5, 6.8, -7.8)),
                    (0.62, (1.5, 8.6, -9.4)), (0.80, (0.4, 12.6, -10.0)),
                    (0.95, (0.0, GROUND_PIVOT[1] - 0.25, -10.0)), (1.00, tuple(GROUND_PIVOT))])
c = hsrig.ctrl


def torso_x(t):
    return c("torso_x", t)


def attached_pivot(world):
    return mcrig.xform(world["torso"], (0.0, -10.5, 2.5))


def sack_pivot(t, world):
    if t >= CONTACT:
        return GROUND_PIVOT.copy()
    s = br.smoothstep((t - 0.27) / 0.09)
    return (1 - s) * attached_pivot(world) + s * br.cv("sack", max(t, 0.31))


def grip_y(t):
    """Left palm slides from the load's lower corner (lifting it off the back) up to
    its side as it comes round to the belly. SettlerModel uses the same rule."""
    return 6.0 + (2.6 - 6.0) * br.smoothstep((t - 0.40) / 0.12)


def grip(pivot, side, t=1.0):
    sx = 1.0 if side == "left" else -1.0
    return pivot + SACK_SCALE * np.array([sx * 4.1, grip_y(t) if side == "left" else 2.6, 3.0])


def solve(t):
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("root_x", t), c("root_y", t), c("root_z", t))},
          "torso": {"rot": (torso_x(t), c("torso_y", t), c("torso_z", t))}}
    ch["cloak"] = {"rot": br.cloak(t, torso_x(t), torso_x)}
    world = mcrig.pose_matrices(ch)
    prev = getattr(solve, "_prev", {})
    br.begin_frame(not prev)
    piv = sack_pivot(t, world)
    for side, on, pole in (("left", "lh_on", (0.8, 0.5, 0.6)), ("right", "rh_on", (-0.8, 0.5, 0.6))):
        w = c(on, t)
        tgt = (1 - w) * br.cv(("lh" if side == "left" else "rh") + "_free", t) + w * grip(piv, side, t)
        edge = START if t < LENGTH / 2 else END
        br.arm_ik(ch, world, side, tgt, pole, prev, match=edge[side + "_arm"]["rot"],
                  match_w=br.edge_weight(t, LENGTH))
    # QA 2026-09-26: while the sack still rides the back its pivot is straight behind
    # the neck, where atan2 yaw flips -50 <-> +50 in one frame. Eyes start on the
    # spot it will be set down, and pick the sack up once it has come round.
    front = GROUND_PIVOT + np.array([0, 4.0, 3.0])
    follow = br.smoothstep((t - 0.30) / 0.25)
    br.head_look(ch, world, front + (piv + np.array([0, 4.0, 3.0]) - front) * follow,
                 w_pitch=0.4, w_yaw=0.6, nod=c("head_nod", t))
    br.leg_ik(ch, world, FEET, prev)
    br.blend_to_pose(ch, START if t < LENGTH / 2 else END, br.edge_weight(t, LENGTH, ramp=0.12))
    solve._prev = {k: ch[k]["rot"] for k in ("left_arm", "right_arm", "right_leg", "left_leg")}
    solve._sack = piv
    return ch


times, samples, sack_path = [], [], []


def solve_rec(t):
    ch = solve(t)
    sack_path.append(solve._sack.copy())
    return ch


times, samples = hsrig.bake(solve_rec, LENGTH, objs)

# prop keys (preview only; SettlerModel owns the real prop)
for f, (t, s) in enumerate(zip(times, samples)):
    m = mcrig.T(*sack_path[f]) @ mcrig.mat4(s=SACK_SCALE)
    br.key_prop(sack, m, f)
    # SettlerRenderer hides the mainhand axe while both palms own the load
    objs["axe_mesh"].hide_render = t < 1.20
    objs["axe_mesh"].keyframe_insert("hide_render", frame=f)

checks = br.contact_report(times, samples, FEET, {
    "left_on_sack": ("left", lambda t: grip(sack_path[int(round(t * 60))], "left", t) if 0.40 <= t <= 1.04 else None),
    "right_on_sack": ("right", lambda t: grip(sack_path[int(round(t * 60))], "right") if 0.54 <= t <= 1.04 else None),
})
checks["contact_t"] = CONTACT
checks["lowest_root_y"] = round(min(s["root"]["pos"][1] for s in samples), 2)
checks["sack_speed_px_s_into_contact"] = round(float(np.linalg.norm(sack_path[60] - sack_path[57]) * 20), 2)
checks["edge_err_deg"] = round(max(
    abs(a - b) for f, pose in ((0, START), (-1, END))
    for bone in ("right_arm", "left_arm", "right_forearm", "left_forearm")
    for a, b in zip(samples[f][bone]["rot"], pose.get(bone, {}).get("rot", (0, 0, 0)))), 3)
checks["knee_flex_max_deg"] = round(max(s["right_shin"]["rot"][0] for s in samples), 1)
checks["forearm_flex_min_deg"] = round(min(min(s["left_forearm"]["rot"][0], s["right_forearm"]["rot"][0]) for s in samples), 1)
checks["left_arm_x_range"] = [round(min(s["left_arm"]["rot"][0] for s in samples), 1),
                              round(max(s["left_arm"]["rot"][0] for s in samples), 1)]
print("CHECKS", json.dumps(checks))

meta = {"source": "tools/blender/pipeline/author_bag_down.py (Blender " + bpy.app.version_string + ")",
        "owner_action": "BAG_DOWN",
        "contract": "WORK_CONTAINER_DOWN 1.40 s one-shot; ground contact t=1.00 s = tick 20 "
                    "(BAG_DOWN sound, CONTAINER_DOWN_CONTACT_TICK); root translation only",
        "prop_path_model_px": {f"{times[i]:.3f}": [round(float(v), 2) for v in sack_path[i]]
                               for i in range(0, len(times), 6)},
        "checks": checks}
path, doc, report, worst = br.export(CONST, LENGTH, False, times, samples,
                                     keep_times=(0.0, 0.25, CONTACT, 1.05, 1.20, LENGTH), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(br.WORK, SLUG + ".blend"))

if "--render" in ARGS:
    br.render_clip(SLUG, LENGTH, br.cameras(), step=6 if "--quick" in ARGS else 2)
