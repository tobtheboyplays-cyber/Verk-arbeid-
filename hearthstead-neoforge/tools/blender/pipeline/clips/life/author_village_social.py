"""VILLAGE_CHAT and VILLAGE_LISTEN (SettlerAnimations), authored in Blender. SUPERSEDED (26 Sep): VILLAGE_CHAT(+__V2),
VILLAGE_LISTEN and BARD_PLAY(+__V2/__V3) are now authored by clips/tavern/author_remakes.py
(the VILLAGE_LISTEN__V2 flavour stays here); running this overwrites them. Run headless:

    blender -b --factory-startup --python author_village_social.py -- [chat|listen|both] [--fast|--full] [--no-export]

Contract kept (SettlerModel village-social branch, VillageSocial.CHAT_TICKS = 96):
  both 2.4 s loops, sampled from the server social clock (4 loops per 96-tick
  moment). The branch resets arms/torso/root/legs/head and applies the clip
  last, with no blend envelope, so frame 0 stays close to the rest pose (small
  arm angles only) and every loop is seamless.

CHAT (the talker): weight on one hip, the right hand comes up palm-up to
explain (small dip first), two emphatic beats with nods, then the arm swings
out to point "over there" while the head and chest turn to look along it,
and a closing nod back to the partner before settling.
LISTEN (the partner): hands loosely together at the belt, the head tilts in
interest, one measured nod (eased, not a twitch), a smaller second nod, weight
drifting from hip to hip, leaning in a touch.
"""

import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lifekit as lk  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import numpy as np  # noqa: E402
import bpy  # noqa: E402

A = lk.args()
WHICH = [x for x in A["rest"] if x in ("chat", "listen", "both", "chat_v2", "listen_v2", "variants")] or ["both"]
WHICH = {"both": ["chat", "listen"], "variants": ["chat_v2", "listen_v2"]}.get(WHICH[0], WHICH)
L = 2.4
ACC, ACC2, DEC, DEC3, INOUT = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT
c = lk.c

CLIPS = {
    "chat": dict(
        const="VILLAGE_CHAT", tex="settler_farmer.png",
        K={
            "root_x": [(0.0, 0.0), (0.9, 0.55), (1.6, 0.45), (2.4, 0.0)],
            "root_y": [(0.0, -0.15), (0.45, -0.3), (1.2, -0.4), (1.45, -0.2), (2.4, -0.15)],
            "torso_x": [(0.0, 2.0), (0.20, 1.5), (0.45, 4.5), (0.95, 3.0), (1.45, 1.0), (1.9, 2.5), (2.4, 2.0)],
            "torso_y": [(0.0, 0.0), (0.45, -4.0), (0.95, -2.0), (1.45, -7.0), (1.75, -6.0), (2.1, -1.0), (2.4, 0.0)],
            "torso_z": [(0.0, 0.0), (0.9, -1.8), (1.6, -1.4), (2.4, 0.0)],
            "shrug": [(0.0, 0.0), (2.4, 0.0)],
            "head_x": [(0.0, 2.0), (0.25, 0.0), (0.45, 7.0, *DEC), (0.60, 2.5), (0.72, 6.0, *DEC), (0.95, 1.0),
                       (1.45, -2.0), (1.75, -1.0), (2.0, 6.0, *DEC), (2.2, 2.0), (2.4, 2.0)],
            "head_y": [(0.0, 0.0), (0.45, -5.0), (1.0, 2.0), (1.35, -6.0), (1.50, -16.0), (1.75, -14.0),
                       (1.95, 2.0), (2.4, 0.0)],
            "head_z": [(0.0, 0.0), (0.45, -2.0), (1.45, 1.0), (1.95, 3.0), (2.4, 0.0)],
        },
        rh=[(0.0, (-6.1, -0.4, -1.3)), (0.20, (-5.9, 0.0, -1.2), *ACC2), (0.45, (-4.0, -7.2, -6.0), *INOUT),
            (0.60, (-4.3, -6.4, -5.8)), (0.72, (-3.8, -7.6, -6.2)), (0.95, (-5.4, -3.0, -4.0)),
            (1.15, (-5.8, -2.2, -3.2), *ACC2), (1.40, (-11.2, -9.0, -7.8), *DEC), (1.70, (-11.0, -8.6, -7.6)),
            (2.00, (-6.4, -1.8, -2.6)), (2.4, (-6.1, -0.4, -1.3))],
        lh=[(0.0, (6.1, -0.4, -1.2)), (0.45, (5.9, -1.6, -2.8)), (0.75, (5.8, -1.9, -3.2)),
            (1.40, (6.3, -1.0, -2.0)), (1.75, (6.2, -0.8, -1.8)), (2.05, (6.1, -0.5, -1.3)),
            (2.4, (6.1, -0.4, -1.2))],
    ),
    "listen": dict(
        const="VILLAGE_LISTEN", tex="settler_courier.png",
        K={
            "root_x": [(0.0, 0.0), (1.2, -0.5), (2.4, 0.0)],
            "root_y": [(0.0, -0.15), (0.9, -0.35), (1.8, -0.25), (2.4, -0.15)],
            "torso_x": [(0.0, 3.0), (0.8, 4.5), (1.4, 3.5), (1.9, 4.0), (2.4, 3.0)],
            "torso_y": [(0.0, 0.0), (0.7, 2.0), (1.5, 0.5), (2.0, -1.5), (2.4, 0.0)],
            "torso_z": [(0.0, 0.0), (1.2, 1.6), (2.4, 0.0)],
            "shrug": [(0.0, 0.0), (2.4, 0.0)],
            "head_x": [(0.0, 2.0), (0.45, 1.0), (0.80, 12.0, *DEC), (1.10, 3.0), (1.70, 2.5), (1.92, 7.0, *DEC),
                       (2.15, 2.5), (2.4, 2.0)],
            "head_y": [(0.0, 0.0), (0.6, 3.0), (1.3, 1.0), (1.9, -3.0), (2.4, 0.0)],
            "head_z": [(0.0, 0.0), (0.35, 6.0), (0.75, 5.0), (1.10, 2.0), (1.5, -3.0), (2.0, -2.0), (2.4, 0.0)],
        },
        rh=[(0.0, (-2.6, -2.0, -4.2)), (0.8, (-2.4, -2.4, -4.4)), (1.6, (-2.8, -1.9, -4.1)),
            (2.4, (-2.6, -2.0, -4.2))],
        lh=[(0.0, (2.0, -1.7, -4.4)), (0.9, (1.9, -2.0, -4.6)), (1.7, (2.2, -1.6, -4.3)),
            (2.4, (2.0, -1.7, -4.4))],
    ),
    # ---- flavour variants (one base cycle each, start/end on the base frame-0 pose):
    # the pair laugh together. The talker delivers the punchline, throws the head
    # back and laughs with a hand on the belly, the other hand slapping the thigh;
    # the listener catches it a beat later, a hand up to the mouth, shoulders shaking.
    "chat_v2": dict(
        const="VILLAGE_CHAT__V2", tex="settler_farmer.png", variant_of="animation.settler.village_chat",
        K={
            "root_x": [(0.0, 0.0), (1.0, 0.4), (2.4, 0.0)],
            "root_y": [(0.0, -0.15), (0.30, -0.25), (0.55, -0.5), (1.1, -0.65), (1.8, -0.3), (2.4, -0.15)],
            "torso_x": [(0.0, 2.0), (0.25, 3.5), (0.45, -5.0, *DEC), (0.80, -3.0), (1.05, 9.0), (1.45, 7.0),
                        (1.85, 2.5), (2.4, 2.0)],
            "torso_y": [(0.0, 0.0), (0.45, -3.0), (1.05, 4.0), (1.8, 1.0), (2.4, 0.0)],
            "torso_z": [(0.0, 0.0), (0.9, -2.0), (1.5, -1.0), (2.4, 0.0)],
            "shrug": [(0.0, 0.0), (0.45, 0.25), (1.05, -0.2), (1.9, 0.0), (2.4, 0.0)],
            "shake": [(0.0, 0.0), (0.40, 0.0), (0.55, 1.0), (1.40, 0.8), (1.85, 0.0), (2.4, 0.0)],
            "head_x": [(0.0, 2.0), (0.25, 5.0), (0.45, -13.0, *DEC), (0.85, -10.0), (1.05, 8.0), (1.45, 5.0),
                       (1.95, 2.0), (2.4, 2.0)],
            "head_y": [(0.0, 0.0), (0.45, -3.0), (1.05, 6.0), (1.7, 2.0), (2.4, 0.0)],
            "head_z": [(0.0, 0.0), (0.5, 4.0), (1.1, -3.0), (1.8, 0.0), (2.4, 0.0)],
        },
        rh=[(0.0, (-6.1, -0.4, -1.3)), (0.35, (-4.0, -4.5, -4.6)), (0.55, (-2.2, -3.8, -3.6)),
            (1.40, (-2.4, -3.6, -3.5)), (1.95, (-5.6, -1.2, -2.0)), (2.4, (-6.1, -0.4, -1.3))],
        lh=[(0.0, (6.1, -0.4, -1.2)), (0.60, (6.8, -2.6, -2.8)), (0.95, (5.6, 1.6, -3.2), *ACC2),
            (1.05, (5.4, 2.2, -3.0), *DEC), (1.35, (6.0, 0.4, -2.2)), (1.9, (6.2, -0.4, -1.4)),
            (2.4, (6.1, -0.4, -1.2))],
    ),
    "listen_v2": dict(
        const="VILLAGE_LISTEN__V2", tex="settler_courier.png", variant_of="animation.settler.village_listen",
        K={
            "root_x": [(0.0, 0.0), (1.2, -0.4), (2.4, 0.0)],
            "root_y": [(0.0, -0.15), (0.6, -0.3), (1.2, -0.55), (2.0, -0.25), (2.4, -0.15)],
            "torso_x": [(0.0, 3.0), (0.55, 1.5), (0.75, 9.0, *DEC), (1.3, 7.0), (1.7, 2.0), (2.1, 3.5), (2.4, 3.0)],
            "torso_y": [(0.0, 0.0), (0.75, -4.0), (1.5, -2.0), (2.4, 0.0)],
            "torso_z": [(0.0, 0.0), (0.9, -1.5), (2.4, 0.0)],
            "shrug": [(0.0, 0.0), (0.7, 0.2), (1.6, 0.0), (2.4, 0.0)],
            "shake": [(0.0, 0.0), (0.60, 0.0), (0.78, 1.0), (1.55, 0.7), (1.95, 0.0), (2.4, 0.0)],
            "head_x": [(0.0, 2.0), (0.55, -4.0), (0.75, 12.0, *DEC), (1.3, 9.0), (1.7, -2.0), (2.05, 3.0),
                       (2.4, 2.0)],
            "head_y": [(0.0, 0.0), (0.75, -6.0), (1.6, -2.0), (2.4, 0.0)],
            "head_z": [(0.0, 0.0), (0.7, -3.0), (1.4, 2.0), (2.4, 0.0)],
        },
        rh=[(0.0, (-2.6, -2.0, -4.2)), (0.55, (-2.8, -6.5, -5.2)), (0.80, (-0.9, -11.6, -6.2), *DEC),
            (1.50, (-1.0, -11.2, -6.0)), (1.85, (-2.6, -4.0, -4.6)), (2.4, (-2.6, -2.0, -4.2))],
        lh=[(0.0, (2.0, -1.7, -4.4)), (0.75, (2.6, -3.0, -3.6)), (1.5, (2.8, -2.8, -3.4)),
            (2.0, (2.1, -1.8, -4.3)), (2.4, (2.0, -1.7, -4.4))],
    ),
}


def author(which):
    cfg = CLIPS[which]
    slug = cfg["const"].lower()
    objs = lk.scene(cfg["tex"])
    lk.key(cfg["K"], cyclic=True)
    lk.key_vec("rh", cfg["rh"], cyclic=True)
    lk.key_vec("lh", cfg["lh"], cyclic=True)
    prev = {}

    def solve(t):
        t %= L
        sh = c("shake", t) * math.sin(2 * math.pi * 6.0 * t) if "shake" in cfg["K"] else 0.0
        ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (c("root_x", t), c("root_y", t), 0.0)},
              "torso": {"rot": (c("torso_x", t) + 1.6 * sh, c("torso_y", t), c("torso_z", t)),
                        "pos": (0.0, c("shrug", t) + 0.18 * abs(sh), 0.0),
                        "scale": lk.breath_scale(lk.breath(t, L / 2.0, phase=0.1), 0.012)},
              "head": {"rot": (c("head_x", t) - 1.2 * sh, c("head_y", t), c("head_z", t))}}
        lk.arm_ik_local(ch, "right", lk.cv("rh", t), (-0.55, 0.7, 0.9), prev)
        lk.arm_ik_local(ch, "left", lk.cv("lh", t), (0.55, 0.7, 0.9), prev)
        lk.leg_ik(ch, lk.FEET, prev)
        yrate = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
        prate = lk.lagged_rate(lambda u: c("torso_x", u % L), t)
        ch["cloak"] = {"rot": lk.cloak(1.0, ch["torso"]["rot"][0], prate, yrate, k_pitch=0.3, k_yaw=0.05)}
        return ch

    for f in range(int(L * lk.FPS) + 1):
        solve(f / lk.FPS)
    times, samples = hsrig.bake(solve, L, objs)
    checks = {"length": L, "loop": True, "loop_seam": lk.loop_seam(samples),
              "foot_slide_px_max": lk.foot_slide(samples, lk.FEET),
              "frame0_arm_deg": [lk.value_at(times, samples, 0.0, "right_arm"),
                                 lk.value_at(times, samples, 0.0, "right_forearm")]}
    print("CHECKS", which, json.dumps(checks))
    meta = {"source": "tools/blender/pipeline/clips/life/author_village_social.py (Blender "
                      + bpy.app.version_string + ")",
            "contract": cfg["const"] + " 2.4 s loop, sampled from the village-social clock (96 ticks = 4 loops)",
            "checks": checks}
    if cfg.get("variant_of"):
        meta["variant_of"] = cfg["variant_of"]
        meta["contract"] += "; flavour variant, one base cycle, same frame-0 pose"
    path, doc, report, worst = lk.export(cfg["const"], L, True, times, samples, keep_times=(0.0, L),
                                         meta=meta, write=A["export"])
    lk.save_report(slug, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
    lk.preview(slug, L, A)


for w in WHICH:
    author(w)
