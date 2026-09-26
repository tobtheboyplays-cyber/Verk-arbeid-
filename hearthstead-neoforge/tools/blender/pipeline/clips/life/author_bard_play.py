"""BARD_PLAY (SettlerAnimations.BARD_PLAY), authored in Blender. SUPERSEDED (26 Sep): VILLAGE_CHAT(+__V2),
VILLAGE_LISTEN and BARD_PLAY(+__V2/__V3) are now authored by clips/tavern/author_remakes.py
(the VILLAGE_LISTEN__V2 flavour stays here); running this overwrites them. Run headless:

    blender -b --factory-startup --python author_bard_play.py -- [--fast|--full] [--no-export]

Contract kept (SettlerModel PLAYING_MUSIC branch):
  length 1.0 s, looping; sampled from ageInTicks. Seated on a TavernSeatEntity:
  the runtime resets root and draws the seated legs itself, and resets arms +
  head before this clip, so ONLY torso, head, arms, forearms and cloak are
  exported (no root / legs / shins). Mimed lute, no prop.

Rhythm (0.5 s beat, as before): down-strokes cross the strings at t = 0.15 and
0.65 s (accelerating from a small lift, fastest mid-string, easing out below),
lighter up-strokes at 0.40 and 0.90 s that only catch the top strings (the
hand stands off the soundboard a little). The fretting hand changes chord on
each beat (slides along the neck with a small lift of the fingers). The
spine gives a small yaw into each down-stroke and an alternating roll per
half bar; the head bobs on the beat (bigger on the one) and watches the neck.
Legs/stool in the preview are scenery only.
"""

import json
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
VAR = "v3" if "v3" in A["rest"] else ("v2" if "v2" in A["rest"] else None)
# BARD_PLAY__v2: 2 cycles, a flourish after the third down-stroke.
# BARD_PLAY__v3: 3 cycles, stops for a seated bow to the room, hand on heart, then plays on.
CONST, SLUG = ("BARD_PLAY__" + VAR.upper(), "bard_play__" + VAR) if VAR else ("BARD_PLAY", "bard_play")
L = 1.0
LT = {"v2": 2.0, "v3": 3.0}.get(VAR, L)
DOWN = (0.15, 0.65)
UP = (0.40, 0.90)
objs = lk.scene("settler_innkeeper.png")
lk.box("stool", (-5, 16, -4), (10, 8, 8), (0.42, 0.3, 0.18, 1))
SEAT_FEET = (np.array([-2.9, 24.0, -7.0]), np.array([2.9, 24.0, -7.5]))
EXPORT = ["torso", "head", "right_arm", "left_arm", "right_forearm", "left_forearm", "cloak"]

ACC, ACC2, DEC, DEC3, INOUT = lk.ACC, lk.ACC2, lk.DEC, lk.DEC3, lk.INOUT
K = {
    # 0 = strum hand above the strings, 1 = below them
    "strum": [(0.0, 0.12), (0.09, 0.0, *ACC), (0.15, 0.5), (0.21, 1.0, *DEC), (0.33, 0.9),
              (0.40, 0.5), (0.46, 0.15, *DEC), (0.59, 0.0, *ACC), (0.65, 0.5), (0.71, 1.0, *DEC),
              (0.83, 0.9), (0.90, 0.5), (0.96, 0.14), (1.0, 0.12)],
    # hand stands off the soundboard on the up-strokes
    "strum_off": [(0.0, 0.2), (0.12, 0.0), (0.24, 0.1), (0.40, 0.9), (0.50, 0.3), (0.62, 0.0),
                  (0.74, 0.1), (0.90, 0.9), (1.0, 0.2)],
    # chord position along the neck (px) and finger lift
    "chord": [(0.0, 0.0), (0.04, 0.9, *DEC3), (0.46, 0.9), (0.54, -0.4, *DEC3), (0.96, -0.4), (1.0, 0.0)],
    "fret_lift": [(0.0, 0.0), (0.02, 0.45), (0.06, 0.0), (0.48, 0.0), (0.52, 0.45), (0.56, 0.0), (1.0, 0.0)],
    "torso_y": [(0.0, 0.0), (0.18, -1.8), (0.42, 0.2), (0.68, -1.6), (0.92, 0.2), (1.0, 0.0)],
    "torso_z": [(0.0, 0.0), (0.25, 1.1), (0.5, 0.0), (0.75, -1.1), (1.0, 0.0)],
    "torso_x": [(0.0, 4.0), (0.20, 5.2), (0.45, 3.8), (0.70, 4.8), (0.95, 3.9), (1.0, 4.0)],
    "head_x": [(0.0, 8.0), (0.08, 7.0), (0.24, 13.0, *DEC), (0.50, 7.5), (0.58, 7.2), (0.74, 10.5, *DEC),
               (1.0, 8.0)],
    "head_y": [(0.0, 9.0), (0.3, 10.5), (0.6, 8.0), (1.0, 9.0)],
    "head_z": [(0.0, 2.0), (0.25, 3.5), (0.75, 0.5), (1.0, 2.0)],
}
lk.key(K, cyclic=True)
c = lk.c

# torso space (+x left, +y down, +z back)
STRUM_TOP = np.array([-0.5, -5.0, -5.6])
STRUM_BOT = np.array([-1.7, -1.7, -5.2])
NECK_FRET = np.array([6.6, -5.8, -6.8])      # fretting hand on the lute neck
NECK_DIR = np.array([0.72, -0.42, -0.55])     # along the neck, toward the peg head
NECK_DIR /= np.linalg.norm(NECK_DIR)


if VAR == "v2":
    lk.key({"fl": [(0.0, 0.0), (1.21, 0.0, *ACC2), (1.36, 1.0, *DEC), (1.46, 1.0, *INOUT), (1.59, 0.0),
                   (LT, 0.0)]}, cyclic=False)
if VAR == "v3":
    lk.key({"stop": [(0.0, 0.0), (0.93, 0.0, *INOUT), (1.25, 1.0), (2.15, 1.0, *INOUT), (2.55, 0.0), (LT, 0.0)],
            "bow": [(0.0, 0.0), (1.30, 0.0, *INOUT), (1.62, 1.0), (1.82, 1.0, *INOUT), (2.12, 0.0), (LT, 0.0)]},
           cyclic=False)
FLOURISH = np.array([-10.0, -9.6, -4.4])
HEART = np.array([1.4, -7.4, -3.5])


def solve(t):
    tv = t % LT
    t %= L
    fl = lk.smoothstep(max(0.0, min(1.0, c("fl", tv)))) if VAR == "v2" else 0.0
    st = lk.smoothstep(max(0.0, min(1.0, c("stop", tv)))) if VAR == "v3" else 0.0
    bw = c("bow", tv) if VAR == "v3" else 0.0
    ch = {"root": {"rot": (0.0, 0.0, 0.0), "pos": (0.0, -4.0, 0.0)},
          "torso": {"rot": (c("torso_x", t), c("torso_y", t), c("torso_z", t)),
                    "scale": lk.breath_scale(lk.breath(t, L, phase=0.3), 0.008)}}
    ch["head"] = {"rot": (c("head_x", t) - 7.0 * fl + 16.0 * bw - 4.0 * st,
                          c("head_y", t) * (1 - st) - 6.0 * fl, c("head_z", t) - 4.0 * fl)}
    tr = ch["torso"]["rot"]
    ch["torso"]["rot"] = (tr[0] - 2.0 * fl + 22.0 * bw, tr[1] - 4.0 * fl, tr[2] - 2.5 * fl)
    prev = solve.prev
    s = c("strum", t)
    strum = STRUM_TOP + (STRUM_BOT - STRUM_TOP) * s + np.array([0.0, 0.0, -0.9]) * c("strum_off", t)
    strum = strum * (1 - fl) + FLOURISH * fl
    strum = strum * (1 - st) + HEART * st
    lk.arm_ik_local(ch, "right", strum, (-0.9, 0.5 + 0.3 * st, 0.45), prev, twist=8.0 * (s - 0.5) * (1 - st))
    fret = NECK_FRET + NECK_DIR * c("chord", t) + np.array([0.0, -0.5, -0.3]) * c("fret_lift", t)
    fret = fret + np.array([-1.2, 2.2, 1.0]) * st
    lk.arm_ik_local(ch, "left", fret, (0.6, 0.9, 0.2), prev)
    lk.leg_ik(ch, SEAT_FEET, prev)
    yrate = lk.lagged_rate(lambda u: c("torso_y", u % L), t)
    ch["cloak"] = {"rot": lk.cloak(1.5, 0.0, 0.0, yrate, k_yaw=0.1)}
    return ch


solve.prev = {}
for f in range(int(LT * lk.FPS) + 1):
    solve(f / lk.FPS)
times, samples = hsrig.bake(solve, LT, objs)

py = []
for s in samples:
    w = mcrig.pose_matrices(s)
    py.append(float(mcrig.xform(np.linalg.inv(w["torso"]), lk.palm(w, "right"))[1]))
vel = [(b - a) * 60 for a, b in zip(py, py[1:])] + [0.0]
checks = {
    "length": LT, "loop": True, "down_strokes_t": list(DOWN), "up_strokes_t": list(UP),
    "fastest_down_stroke_t": [lk.extreme_time(times[:30], vel[:30], "max"),
                              lk.extreme_time(times[30:], vel[30:], "max")],
    "fastest_up_stroke_t": [lk.extreme_time(times[:30], vel[:30], "min"),
                            lk.extreme_time(times[30:], vel[30:], "min")],
    "loop_seam": lk.loop_seam(samples, EXPORT),
    "exported_bones": EXPORT,
}
print("CHECKS", json.dumps(checks))
meta = {"source": "tools/blender/pipeline/clips/life/author_bard_play.py (Blender " + bpy.app.version_string + ")",
        "contract": "BARD_PLAY 1.0 s loop, seated (upper body only: root/legs belong to the seat); "
                    "down-strokes t=0.15/0.65 s, up-strokes 0.40/0.90 s",
        "checks": checks}
if VAR:
    meta["variant_of"] = "animation.settler.bard_play"
    meta["contract"] += "; variant = %d base cycles, same frame-0 pose" % int(LT)
keep = tuple(k + i * L for i in range(int(LT)) for k in DOWN + UP + (L,))
path, doc, report, worst = lk.export(CONST, LT, True, times, samples, keep_times=(0.0,) + keep,
                                     meta=meta, bones=EXPORT, write=A["export"])
lk.save_report(SLUG, {"checks": checks, "channels": report, "roundtrip_max_err": worst})
lk.preview(SLUG, LT, A, cams=lk.cameras(side=((-3.6, -0.6, 0.95), (0.0, -0.3, 0.8)),
                                        front34=((-2.2, -3.0, 1.3), (0.0, -0.2, 0.85))))
