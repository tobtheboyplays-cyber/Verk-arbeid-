"""FISHER_CAST, authored in Blender. Run headless:

    blender -b --factory-startup --python author_fisher_cast.py -- [--fast|--full]

Contract kept (SettlerAnimations.FISHER_CAST / Employment WORK_FISH accent 29):
  length 2.00 s looping; the nibble beat stays at t = 1.45 s = tick 29.

Runtime ownership (SettlerModel.applyFishingPose, read 2026-09-26): this clip is
played as the PLANTED LOWER-BODY BASE, then the server-synced fishing cycle
(cast -> patient watch -> hook -> reel -> net, ~300 ticks) overwrites both arms
and forearms, torso x/y, head x and the net. What shows from this clip is: root,
legs + knees, torso roll (z), head yaw/roll (y/z) and the cloak. The runtime
comment is explicit that no 2-second loop may announce a catch that never
happened, so the old full-body hook-set every 2 s is gone: 1.45 s is now a
small NIBBLE (the fisher's weight settles forward a hair and the eyes snap to
the float), not a strike. The arms are authored to the runtime's own resting
rod pose (so nothing pops if the engine ever hands them back), with a 4-degree
rod-tip twitch on the nibble.

Life: slow breathing, the weight drifts onto the left hip and back over the
loop (planted feet, knees take it), the head scans along the bank and returns
to the float.
"""

import json
import os
import sys

import bpy
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import farmkit as fk  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402

V = fk.variant_flag()                   # None = base, 2 = __v2 (neck stretch + look back up the bank)
CONST, SLUG = ("FISHER_CAST", "fisher_cast") if V is None else (f"FISHER_CAST__v{V}", f"fisher_cast__v{V}")
NCYC = 1 if V is None else 2
L, NIBBLE = 2.0, 1.45
OUT = os.path.join(fk.WORK, "out", "farm", SLUG)
os.makedirs(OUT, exist_ok=True)
c = hsrig.ctrl

sc, objs = fk.build(fk.tex("fisher"), item="rod")
fk.box("water", (-60, 25, -60), (120, 0.5, 44), (0.20, 0.35, 0.55, 1))
FEET = (np.array([-3.0, 24.0, 0.6]), np.array([3.0, 24.0, -0.4]))
OUTS = ("SINE", "EASE_OUT")
SNAP = ("QUAD", "EASE_OUT")
K = {
    "pelvis_x":   [(0.0, 0.0), (0.55, 0.35), (1.00, 0.55), (1.40, 0.35), (1.46, 0.3), (1.75, 0.1), (L, 0.0)],
    "pelvis_roll": [(0.0, 0.0), (1.00, 0.9), (1.45, 0.6), (L, 0.0)],
    "pelvis_y":   [(0.0, -0.15), (0.5, -0.08), (1.0, -0.2), (1.40, -0.12), (1.47, -0.32, *OUTS), (1.60, -0.28),
                   (1.85, -0.15), (L, -0.15)],
    "pelvis_z":   [(0.0, 0.0), (1.40, 0.0), (1.47, -0.25, *OUTS), (1.70, -0.1), (L, 0.0)],
    "spine_x":    [(0.0, 5.0), (0.5, 4.2), (1.0, 5.4), (1.40, 4.8), (1.47, 6.2, *OUTS), (1.75, 5.4), (L, 5.0)],
    "spine_z":    [(0.0, 0.0), (1.00, -1.4), (1.45, -1.0), (L, 0.0)],
    "spine_yaw":  [(0.0, 0.0), (0.8, -1.5), (1.4, 0.5), (L, 0.0)],
    # head scans the bank, returns and SNAPS to the float on the nibble
    "head_yaw":   [(0.0, 3.0), (0.35, 3.5), (0.80, -9.0), (1.05, -8.0), (1.38, -2.0), (1.46, 0.0, *SNAP),
                   (1.75, 1.5), (L, 3.0)],
    "head_x":     [(0.0, 6.0), (0.8, 4.0), (1.40, 6.0), (1.47, 8.5, *OUTS), (L, 6.0)],
    "head_roll":  [(0.0, 0.0), (0.8, -1.5), (1.4, 0.5), (L, 0.0)],
    "twitch":     [(0.0, 0.0), (1.42, 0.0), (1.46, 1.0, *SNAP), (1.62, 0.2), (1.80, 0.0), (L, 0.0)],
}
BASE_L = L
if V == 2:
    # __v2, two cycles (4.00 s), nibble beats kept at 1.45 s and 3.45 s. Only
    # what the runtime leaves visible is used: in cycle 1 a slow neck stretch
    # (head rolls one way, then the other, the trunk rolls against it); in
    # cycle 2 the weight settles onto the left hip and he looks back over his
    # left shoulder up the bank for a moment before the eyes return to the float.
    K = {k: fk.tile(v, L, NCYC) for k, v in K.items()}
    K["head_roll"] = fk.splice(K["head_roll"], 0.25, 1.35, [(0.50, 9.0), (0.80, 10.0), (1.05, -7.0),
                                                            (1.22, -6.0)])
    K["spine_z"] = fk.splice(K["spine_z"], 0.25, 1.35, [(0.55, -3.0), (1.05, 2.5)])
    K["head_yaw"] = fk.splice(K["head_yaw"], 2.15, 3.40, [(2.40, -6.0), (2.62, -34.0), (2.95, -36.0),
                                                          (3.20, -6.0)])
    K["pelvis_x"] = fk.splice(K["pelvis_x"], 2.10, 3.40, [(2.50, 0.7), (3.00, 0.75)])
    K["pelvis_roll"] = fk.splice(K["pelvis_roll"], 2.10, 3.40, [(2.50, 1.2), (3.00, 1.3)])
    K["spine_z"] = fk.splice(K["spine_z"], 2.10, 3.40, [(2.50, -2.6), (3.00, -2.8)])
    L = L * NCYC
fk.key(K, cyclic=True, length=L)


def solve(t):
    t = t % L
    ch = fk.body(t)
    ch["cloak"] = {"rot": fk.cloak(t, L, base=3.0)}
    tw = c("twitch", t)
    breathe = 0.6 * np.sin(2 * np.pi * t / BASE_L)
    # runtime resting rod pose (applyFishingPose with the engine elbow): arm -41/-6/3, elbow -24
    ch["right_arm"] = {"rot": (-41.0 - 4.0 * tw + breathe, -5.7, 3.4)}
    ch["right_forearm"] = {"rot": (-24.0 + 2.0 * tw, 0.0, 0.0)}
    ch["left_arm"] = {"rot": (7.4 - breathe, 6.9, -6.9)}
    ch["left_forearm"] = {"rot": (-31.5, 0.0, 0.0)}
    ch["head"] = {"rot": (c("head_x", t), c("head_yaw", t), c("head_roll", t))}
    prev = getattr(solve, "_prev", {})
    fk.legs(ch, FEET, prev)
    solve._prev = {k: ch[k]["rot"] for k in ("right_leg", "left_leg")}
    return ch


times, samples = hsrig.bake(solve, L, objs)
fk.fix_wraps(samples)
checks = {
    "nibble_t": NIBBLE, "nibble_tick": 29,
    "foot_slide_px_max": fk.foot_slide(samples, FEET),
    "loop_seam_max": fk.loop_seam(samples),
    "knee_flex_range_deg": [round(min(s["right_shin"]["rot"][0] for s in samples), 1),
                            round(max(s["right_shin"]["rot"][0] for s in samples), 1)],
    "visible_under_runtime": "root, legs, shins, torso z, head y/z, cloak",
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/farm/author_fisher_cast.py (Blender " + bpy.app.version_string + ")",
        "contract": "FISHER_CAST 2.00 s loop; nibble beat t=1.45 s = tick 29 (Employment WORK_FISH); "
                    "arms/torso x,y/head x are overwritten by SettlerModel.applyFishingPose"
                    + ("" if V is None else f"; variant = {NCYC} cycles, nibbles at 1.45/3.45 s, neck stretch + look back up the bank"),
        "checks": checks}
path, doc, report, worst = fk.export(CONST, L, True, times, samples, keep_times=tuple(sorted({NIBBLE + i * BASE_L for i in range(NCYC)} | {0.0, L})), meta=meta)
with open(os.path.join(OUT, "export_report.json"), "w") as fh:
    json.dump({"checks": checks, "channels": report, "roundtrip_max_err": worst}, fh, indent=1)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, SLUG + ".blend"))
fk.finish(SLUG, L, OUT)
