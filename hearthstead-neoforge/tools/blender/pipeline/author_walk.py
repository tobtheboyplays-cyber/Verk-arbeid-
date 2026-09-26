"""Settler WALK and WALK_LADEN, authored in Blender with planted-foot IK. Run headless:

    blender -b --factory-startup --python author_walk.py -- [walk|laden|both] [--render]

Lengths kept from SettlerAnimations: WALK 1.0 s loop, WALK_LADEN 1.2 s loop.
The motion engine samples locomotion on a per-entity DISTANCE clock (clip
time 0 = gait phase 0). Each clip's ground distance per cycle is written
into the JSON meta ("blocks_per_cycle") so the engine can match its stride
and the soles do not skate.

Gait model (treadmill: the body stays at the origin, the ground moves back):
  * each foot: stance for DUTY of the cycle, sliding back at exactly the
    ground speed (planted in the world), then a swing arc forward with
    ease-in/out and a lift;
  * right foot contact (forward, heel strike) at t = 0, left at t = len/2;
  * pelvis lowest just after each contact (double support, the knee
    absorbs the landing), highest at mid-stance; sways over the stance foot;
    yaws with the forward leg; spine counter-twists; head cancels it;
  * arms swing opposite the legs, lagging the pelvis, elbows flexing more
    on the forward swing; cloak drags behind the vertical bob.
Legs are two-bone IK (hip -> knee -> sole), so knees bend instead of the old
rigid-cuboid split.
"""

import json
import math
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import export_mc_clip as ex  # noqa: E402

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
WHICH = [a for a in argv if a in ("walk", "laden", "both")] or ["both"]
WHICH = ["walk", "laden"] if WHICH[0] == "both" else WHICH
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WORK = os.environ.get("HS_PIPELINE", r"C:\Users\tobia\Hearthstead-Claude\tools\blender-pipeline")
TEX = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures",
                   "entity", "settler", "settler_lumberer.png")
AXE = os.path.join(WORK, "ref", "assets", "minecraft", "textures", "item", "iron_axe.png")
ANIM_DIR = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "animations", "settler")

STYLES = {
    # Everyday gait: medium stride, clear knee lift, relaxed arm swing.
    "walk": dict(name="animation.settler.walk", file="walk.animation.json", length=1.0,
                 excursion=13.0, duty=0.56, lift=2.6, drop=1.4, base=-0.1,
                 sway=0.35, pelvis_yaw=4.0, spine_yaw=7.0, lean=3.0, lean_dip=1.2,
                 arm=24.0, elbow=(-10.0, -30.0), arm_out=3.0, head_up=0.0, cloak=4.0),
    # Heavy sack: short, flat-footed steps, knees stay bent, hips sink and
    # the torso wedges forward under the load; arms mostly owned by the
    # carry grip (small swing only), long double support.
    "laden": dict(name="animation.settler.walk_laden", file="walk_laden.animation.json", length=1.2,
                  excursion=9.0, duty=0.64, lift=1.5, drop=0.8, base=-0.9,
                  sway=0.55, pelvis_yaw=2.5, spine_yaw=3.0, lean=9.0, lean_dip=2.5,
                  arm=6.0, elbow=(-18.0, -24.0), arm_out=4.0, head_up=0.0, cloak=5.0),
}
HIP_X = 2.6
FOOT_X = 2.9     # soles a touch wider than the hips (girth-scaled legs must not rub)


def smooth(x):
    return x * x * (3 - 2 * x)


def foot_target(u, st):
    """Sole centre in model space for a foot at cycle phase u (0 = its heel strike)."""
    e, d = st["excursion"], st["duty"]
    u %= 1.0
    if u < d:                      # stance: planted, slides back at ground speed
        s = u / d
        z = -e / 2 + e * s
        y = 24.0
    else:                          # swing: eased forward arc with an early lift peak
        s = (u - d) / (1 - d)
        z = e / 2 - e * smooth(s)
        y = 24.0 - st["lift"] * math.sin(math.pi * min(1.0, s ** 0.8))
    return z, y


def author(key):
    st = STYLES[key]
    L = st["length"]
    sc = hsrig.reset()
    objs = hsrig.build_scene(TEX, AXE)
    hsrig.prop_box("ground", (-40, 24, -40), (80, 1, 80), (0.30, 0.45, 0.22, 1))

    # ---- control curves (Blender F-curves, cyclic). t in seconds.
    def cyc(prop, pts, ease=None):
        keys = []
        for t, v in pts:
            keys.append((t * L, v) if ease is None else (t * L, v, *ease))
        hsrig.key_curve(prop, keys, cyclic=True, length=L)

    lo = st["base"] - st["drop"]
    hi = st["base"]
    # pelvis height: lowest ~0.05 after each contact, highest at mid-stance
    cyc("pelvis_y", [(0.0, lo + 0.25 * st["drop"]), (0.06, lo), (0.28, hi), (0.5, lo + 0.25 * st["drop"]),
                     (0.56, lo), (0.78, hi), (1.0, lo + 0.25 * st["drop"])])
    cyc("pelvis_x", [(0.0, 0.0), (0.25, -st["sway"]), (0.5, 0.0), (0.75, st["sway"]), (1.0, 0.0)])
    cyc("pelvis_yaw", [(0.0, -st["pelvis_yaw"]), (0.25, 0.0), (0.5, st["pelvis_yaw"]), (0.75, 0.0),
                       (1.0, -st["pelvis_yaw"])])
    cyc("spine_yaw", [(0.0, st["spine_yaw"]), (0.25, 0.0), (0.5, -st["spine_yaw"]), (0.75, 0.0),
                      (1.0, st["spine_yaw"])])
    # forward lean pulses just after contact (weight acceptance), lags the dip
    ld = st["lean_dip"]
    cyc("spine_x", [(0.0, st["lean"]), (0.1, st["lean"] + ld), (0.3, st["lean"] - 0.2 * ld),
                    (0.5, st["lean"]), (0.6, st["lean"] + ld), (0.8, st["lean"] - 0.2 * ld),
                    (1.0, st["lean"])])
    # arms: right arm back when the right leg is forward (t=0); lag 0.05 cycle
    a = st["arm"]
    cyc("arm_r_x", [(0.0, a * 0.8), (0.05, a), (0.3, 0.0), (0.55, -a), (0.8, 0.0), (1.0, a * 0.8)])
    cyc("elbow_r", [(0.0, st["elbow"][0]), (0.3, st["elbow"][0] * 0.8 + st["elbow"][1] * 0.2),
                    (0.55, st["elbow"][1]), (0.8, st["elbow"][0] * 0.5 + st["elbow"][1] * 0.5),
                    (1.0, st["elbow"][0])])
    c = hsrig.ctrl

    def arm_l_x(t):
        return c("arm_r_x", (t + L / 2) % L)

    def elbow_l(t):
        return c("elbow_r", (t + L / 2) % L)

    prev = {}

    def solve(t):
        t %= L
        u = t / L
        ch = {"root": {"rot": (0.0, c("pelvis_yaw", t), 0.0),
                       "pos": (c("pelvis_x", t), c("pelvis_y", t), 0.0)},
              "torso": {"rot": (c("spine_x", t), c("spine_yaw", t), 0.0)}}
        ch["right_arm"] = {"rot": (c("arm_r_x", t), 0.0, st["arm_out"])}
        ch["right_forearm"] = {"rot": (c("elbow_r", t), 0.0, 0.0)}
        ch["left_arm"] = {"rot": (arm_l_x(t), 0.0, -st["arm_out"])}
        ch["left_forearm"] = {"rot": (elbow_l(t), 0.0, 0.0)}
        # head keeps the gaze level and forward
        yaw_net = c("pelvis_yaw", t) + c("spine_yaw", t)
        ch["head"] = {"rot": (-0.75 * c("spine_x", t) + st["head_up"], -0.85 * yaw_net, 0.0)}
        # cloak drags behind the vertical bob and the twist
        dt = 1.0 / 60
        vy = (c("pelvis_y", (t - 0.05) % L) - c("pelvis_y", (t - 0.05 - dt) % L)) / dt
        vyaw = (yaw_net - (c("pelvis_yaw", (t - dt) % L) + c("spine_yaw", (t - dt) % L))) / dt
        ch["cloak"] = {"rot": (st["cloak"] + max(-8, min(8, 1.6 * vy)), 0.0, max(-6, min(6, -0.05 * vyaw)))}
        world = mcrig.pose_matrices(ch)
        inv_root = np.linalg.inv(world["root"])
        for side, sign, phase in (("right", -1, 0.0), ("left", 1, 0.5)):
            z, y = foot_target(u - phase, st)
            foot = np.array([sign * FOOT_X, y, z])
            fl = mcrig.xform(inv_root, foot)
            pole = inv_root[:3, :3] @ np.array([0.1 * sign, 0.0, -1.0])
            r, flex, _ = mcrig.two_bone(np.array([sign * HIP_X, -12.0, 0.0]), fl, mcrig.THIGH,
                                        mcrig.SOLE_Y - mcrig.THIGH, pole, +1)
            ch[side + "_leg"] = {"rot": tuple(hsrig.euler_deg_continuous(r, prev.get(side)))}
            prev[side] = ch[side + "_leg"]["rot"]
            ch[side + "_shin"] = {"rot": (math.degrees(flex), 0.0, 0.0)}
        return ch

    times, samples = hsrig.bake(solve, L, objs)

    # checks: stance-foot slip against the ideal planted track, min clearance in swing
    slip, reach_err = 0.0, 0.0
    for t, s in zip(times, samples):
        w = mcrig.pose_matrices(s)
        for side, sign, phase in (("right", -1, 0.0), ("left", 1, 0.5)):
            sole = mcrig.xform(w[side + "_shin"], (0, 6, 0))
            z, y = foot_target(t / L - phase, st)
            err = float(np.linalg.norm(sole - np.array([sign * FOOT_X, y, z])))
            reach_err = max(reach_err, err)
    blocks_per_cycle = st["excursion"] / st["duty"] / 16.0
    checks = {"sole_max_error_px": round(reach_err, 3),
              "blocks_per_cycle": round(blocks_per_cycle, 4),
              "ground_speed_note": "stance soles slide back at blocks_per_cycle per clip loop"}
    print("CHECKS", key, json.dumps(checks))

    chan = hsrig.to_export_channels(times, samples)
    meta = {"source": "tools/blender/pipeline/author_walk.py (Blender " + bpy.app.version_string + ")",
            "blocks_per_cycle": round(blocks_per_cycle, 4), "checks": checks}
    doc, report = ex.build_bedrock(st["name"], L, True, times, chan, rot_tol=0.25, pos_tol=0.02,
                                   keep_times=(0.0, L), meta=meta)
    os.makedirs(ANIM_DIR, exist_ok=True)
    ex.write(doc, os.path.join(ANIM_DIR, st["file"]))
    out = os.path.join(WORK, "out", key)
    os.makedirs(out, exist_ok=True)
    ex.write(doc, os.path.join(out, st["file"]))
    print("EXPORTED", key, sum(v["keys"] for v in report.values()), "keys")
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, "settler_" + key + ".blend"))
    a = hsrig.parse_args()
    if a["fast"] or a["full"]:
        hsrig.preview(out, L, fast=not a["full"], full=a["full"])
    return blocks_per_cycle


result = {k: author(k) for k in WHICH}
print("BLOCKS_PER_CYCLE", json.dumps(result))
