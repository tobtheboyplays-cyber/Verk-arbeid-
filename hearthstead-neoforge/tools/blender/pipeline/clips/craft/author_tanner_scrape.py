"""TANNER_SCRAPE (tanner; hunter skinning), authored in Blender.

    blender -b --factory-startup --python author_tanner_scrape.py -- [--fast|--full] [--no-export]

Contract kept: 1.20 s loop (24 ticks). HIDE_SCRAPE at clip tick 4
(Employment.soundContactOf(WORK_SCRAPE) = 4, HunterButchery SCRAPE_CONTACT):
the two-handed draw stroke starts on the hide at t = 0.20 s and runs to 0.50 s.

A fleshing beam slopes down toward the worker. Both fists on the scraper
bar (live IK on the bar), which is pressed in at the far end, then drawn
down the slope toward the body with the weight sitting back into the heels
and the spine unfolding; lifted off at the near end, carried back up in the
air while the torso leans out over the beam again, and set down with a
little press of the shoulders before the next draw.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.20
DRAW0, DRAW1 = 0.20, 0.50          # tick 4 = the draw starts on the hide

V = ck.variant()
clip = ck.Clip("TANNER_SCRAPE" + ("__V2" if V == "v2" else ""), LENGTH, True, "settler_tanner.png",
               cycles=2 if V == "v2" else 1,
               feet=((-3.1, 24.0, 1.8), (3.1, 24.0, -1.2)))
HIDE = (0.62, 0.48, 0.34, 1)
# sloped beam (stepped boxes stand in for the slant): top y 7.6 far -> 10.4 near
for i, (z0, y0) in enumerate(((-17, 7.2), (-14, 8.1), (-11, 9.0), (-8, 9.9))):
    clip.prop(f"beam{i}", (-4, y0, z0), (8, 24 - y0, 3), HIDE)

FAR = np.array([0.0, 7.0, -13.2])       # bar centre pressed on the far end of the beam
NEAR = np.array([0.0, 9.6, -7.6])       # end of the draw
GRIP = np.array([2.6, 0.0, 0.0])        # fists either side of the bar centre

K = {
    "s": [(0.0, 0.0, "inout"), (DRAW0, 0.0, "inout"), (DRAW1, 1.0, "out"), (0.58, 1.02, "inout"),
          (1.02, 0.04, "inout"), (1.12, 0.0, "smooth")],
    "lift": [(0.0, -0.9, "inout"), (0.14, 0.2, "out"), (DRAW0, 0.0, "linear"), (DRAW1, 0.0, "out"),
             (0.60, -1.3, "inout"), (1.00, -1.4, "inout")],
    "root_z": [(0.0, -0.3), (0.18, -0.4, "inout"), (0.48, 0.9, "out"), (0.62, 0.8, "inout"),
               (1.05, -0.3, "inout")],
    "root_y": [(0.0, -0.6), (0.18, -0.75, "inout"), (0.48, -1.2, "out"), (0.66, -0.8, "inout"),
               (1.05, -0.55, "inout")],
    "root_x": [(0.0, 0.0)],
    "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, 24.0), (0.16, 26.0, "inout"), (0.48, 12.0, "out"), (0.62, 12.5, "inout"),
                (1.00, 25.0, "inout"), (1.10, 24.5, "smooth")],
    "torso_y": [(0.0, 0.0), (0.35, 1.5, "inout"), (0.80, -1.0, "inout")],
    "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, 3.0), (0.48, 6.0, "out"), (1.0, 3.0, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)], "cloak_add": [(0.0, 0.0)],
}
clip.keys(K)
clip.key_vec("look", [(0.0, (0.0, 8.0, -12.0)), (0.40, (0.0, 9.0, -9.0), "inout"),
                      (0.95, (0.0, 8.0, -12.5), "inout")])


def bar(t):
    return FAR + (NEAR - FAR) * ck.c("s", t) + np.array([0.0, ck.c("lift", t), 0.0])


def hand(t, name, grip):
    w = ck.smoothstep(ck.c("free", t))       # 0 on the bar; variants blend to free hand targets
    return (1 - w) * (bar(t) + grip) + w * clip.cv(name, t)


clip.arm_live("right", lambda t, w: hand(t, "rfree", -GRIP), (-0.75, 0.4, 0.55))
clip.arm_live("left", lambda t, w: hand(t, "lfree", GRIP), (0.75, 0.4, 0.55))
if V == "v2":
    # __v2: after the first draw the tanner lets go of the scraper, takes the
    # hide by both edges and stretches it across the beam to feel for thin
    # spots, releases, and is back on the bar, pressed in, for the second draw
    # at 1.40 s (tick 28 = 4 + 24).
    clip.vary({
        "free": [(0.0, 0.0), (0.56, 0.0, "inout"), (0.68, 1.0, "inout"), (1.12, 1.0, "inout"),
                 (1.30, 0.0)],
        "torso_x": [(0.0, 0.0), (0.60, 0.0, "inout"), (0.80, 4.0, "inout"), (0.92, 1.0, "inout"),
                    (1.08, 2.0, "inout"), (1.24, 0.0)],
        "root_z": [(0.0, 0.0), (0.60, 0.0, "inout"), (0.88, 0.5, "inout"), (1.24, 0.0)],
        "head_nod": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.86, 5.0, "inout"), (1.20, 0.0)],
    })
    # free-hand targets: grab the edges, pull apart (tension), ease off.
    # QA 2026-09-26: these are ABSOLUTE palm targets, so they must already sit on
    # the hide edge while the "free" weight rises (0.56-0.68) and stay there while
    # it falls (1.12-1.30). They used to ramp from/to the model origin (0,0,0) --
    # the neck -- and both arms spun ~175 deg through the chest at 1.12-1.22 s.
    for side, sx in (("rfree", -1.0), ("lfree", 1.0)):
        clip.vary({
            f"{side}_x": [(0.0, 0.0), (0.50, sx * 4.8, "inout"), (0.68, sx * 4.8, "inout"), (0.86, sx * 6.6, "inout"),
                          (0.94, sx * 6.4, "inout"), (1.06, sx * 6.8, "inout"), (1.32, sx * 6.8), (1.40, 0.0)],
            f"{side}_y": [(0.0, 0.0), (0.50, 8.8, "inout"), (0.68, 8.8, "inout"), (0.86, 8.6, "inout"),
                          (1.06, 8.6, "inout"), (1.32, 8.6), (1.40, 0.0)],
            f"{side}_z": [(0.0, 0.0), (0.50, -10.0, "inout"), (0.68, -10.0, "inout"), (0.86, -9.2, "inout"),
                          (1.06, -9.3, "inout"), (1.32, -9.3), (1.40, 0.0)],
        })
clip.head_w = (0.3, 0.5)
clip.run(contacts=[("draw_start_r", DRAW0, "right", FAR - GRIP), ("draw_start_l", DRAW0, "left", FAR + GRIP),
                   ("draw_end_r", DRAW1, "right", NEAR - GRIP)],
         meta={"variant": V or "base", "contract": "TANNER_SCRAPE 1.20 s loop; HIDE_SCRAPE at t=0.20 s = tick 4 of 24, "
                           "draw stroke 0.20-0.50 s",
               "held_item": "none in game (tanner empty-handed; hunter keeps the bow in MAINHAND)"})
