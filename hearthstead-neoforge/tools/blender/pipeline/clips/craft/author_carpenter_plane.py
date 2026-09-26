"""CARPENTER_PLANE (carpenter), authored in Blender.

    blender -b --factory-startup --python author_carpenter_plane.py -- [--fast|--full] [--no-export]

Contract kept: 1.30 s loop (26 ticks). PLANE_SHAVE at clip tick 3
(Employment.soundContactOf(WORK_PLANE) = 3): the push stroke starts on the
board at t = 0.15 s and runs to 0.45 s.

Two hands on the plane (right on the tote behind, left on the front knob),
both live IK on the plane so they slide together along the board. The hips
lead the push (weight rolls from the back foot onto the front foot), the
spine folds over the bench and the arms extend last. At the far end the
plane is lifted off, carried back in the air while the body rises, set
down, and the hips sink back a touch (anticipation) before the next push.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.30
PUSH0, PUSH1 = 0.15, 0.45           # tick 3 = the push starts on the board

V = ck.variant()
clip = ck.Clip("CARPENTER_PLANE" + ("__V2" if V == "v2" else ""), LENGTH, True, "settler_carpenter.png",
               cycles=2 if V == "v2" else 1,
               feet=((-3.4, 24.0, 2.2), (3.0, 24.0, -2.0)))
clip.prop("bench", (-7, 8, -20), (14, 16, 12), (0.55, 0.40, 0.24, 1))
clip.prop("board", (-3.5, 7, -19), (5, 1, 12), (0.80, 0.66, 0.44, 1))

NEAR = np.array([-1.0, 6.2, -7.2])      # plane body on the board, near end
FAR = np.array([-1.0, 6.2, -11.6])      # far end of the stroke
TOTE = np.array([-1.3, 0.0, 1.7])       # right hand behind the plane centre
KNOB = np.array([1.3, 0.3, -1.7])       # left hand on the front knob

K = {
    # plane centre travel: 0 = near, 1 = far; lift = off the board (px, up = negative y)
    "s": [(0.0, 0.0), (PUSH0, 0.0, "inout"), (PUSH1, 1.0, "out"), (0.52, 1.02, "inout"),
          (1.02, 0.02, "inout"), (1.16, 0.0, "smooth")],
    "lift": [(0.0, 0.0), (PUSH1, 0.0, "out"), (0.55, -1.4, "inout"), (0.95, -1.2, "inout"),
             (1.10, 0.0, "hold")],
    # hips lead the push (they arrive ~0.05 s before the hands)
    "root_z": [(0.0, 0.45), (0.10, 0.55, "inout"), (0.40, -1.2, "out"), (0.60, -0.9, "inout"),
               (1.00, 0.35, "inout"), (1.20, 0.5, "smooth")],
    "root_y": [(0.0, -0.75), (0.10, -0.8, "inout"), (0.40, -1.05, "out"), (0.62, -0.6, "inout"),
               (1.05, -0.55, "inout"), (1.22, -0.8, "smooth")],
    "root_x": [(0.0, -0.2), (0.12, -0.3, "inout"), (0.40, 0.45, "out"), (0.80, 0.1, "inout"),
               (1.18, -0.2, "smooth")],
    "root_yaw": [(0.0, 3.0), (0.40, -1.0, "out"), (0.90, 2.5, "inout")],
    # QA 2026-09-26: peak lean 29 -> 23 (the head dropped onto the left forearm, 2.4 px)
    "torso_x": [(0.0, 17.0), (0.12, 16.5, "inout"), (0.43, 23.0, "out"), (0.60, 21.0, "inout"),
                (1.02, 15.0, "inout"), (1.20, 16.5, "smooth")],
    "torso_y": [(0.0, 3.0), (0.43, -2.0, "out"), (0.95, 3.5, "inout")],
    "torso_z": [(0.0, 0.0)],
    "head_nod": [(0.0, 3.0), (0.45, -2.0, "out"), (0.80, 1.0, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)], "cloak_add": [(0.0, 0.0)],
}
clip.keys(K)
# eyes follow the shaving along the board, then back to the near end
clip.key_vec("look", [(0.0, (-1.0, 7.0, -9.5)), (0.45, (-1.0, 7.0, -13.5), "inout"),
                      (1.0, (-1.0, 7.0, -9.0), "inout")])


def plane(t):
    return NEAR + (FAR - NEAR) * ck.c("s", t) + np.array([0.0, ck.c("lift", t), 0.0])


clip.arm_live("right", lambda t, w: plane(t) + TOTE, (-0.7, 0.35, 0.65))
clip.arm_live("left", lambda t, w: plane(t) + KNOB, (0.8, 0.45, 0.4))
if V == "v2":
    # __v2: after the first stroke the carpenter leaves the plane at the far
    # end, drops low and sights down the board for straightness (head tipped,
    # one eye along the edge), then brings the plane back in time for the
    # second push at 1.45 s (tick 29 = 3 + 26).
    clip.vary({
        "s": [(0.0, 0.0), (0.52, 0.0, "inout"), (0.75, 0.5, "inout"), (1.02, 0.98, "inout"),
              (1.12, 0.9, "inout"), (1.40, 0.0)],
        "lift": [(0.0, 0.0), (0.55, 0.0, "inout"), (0.66, 1.35, "inout"), (1.05, 1.2, "inout"),
                 (1.12, 0.0, "inout"), (1.25, -1.2, "inout"), (1.40, 0.0)],
        "root_y": [(0.0, 0.0), (0.52, 0.0, "inout"), (0.80, -2.1, "inout"), (1.04, -2.1, "inout"),
                   (1.30, 0.0)],
        "torso_x": [(0.0, 0.0), (0.52, 0.0, "inout"), (0.80, -13.0, "inout"), (1.04, -12.0, "inout"),
                    (1.30, 0.0)],
        "head_nod": [(0.0, 0.0), (0.55, 0.0, "inout"), (0.82, -9.0, "inout"), (1.04, -9.0, "inout"),
                     (1.28, 0.0)],
        "head_roll": [(0.0, 0.0), (0.60, 0.0, "inout"), (0.84, 9.0, "inout"), (1.02, 8.0, "inout"),
                      (1.25, 0.0)],
    })
clip.head_w = (0.35, 0.5)
clip.run(contacts=[("push_start_tote", PUSH0, "right", NEAR + TOTE), ("push_start_knob", PUSH0, "left", NEAR + KNOB),
                   ("push_end_tote", PUSH1, "right", FAR + TOTE), ("push_end_knob", PUSH1, "left", FAR + KNOB)],
         surfaces=[("bench", -7, 7, -20, -8, 8.0)],
         meta={"variant": V or "base", "contract": "CARPENTER_PLANE 1.30 s loop; PLANE_SHAVE at t=0.15 s = tick 3 of 26, "
                           "push stroke 0.15-0.45 s",
               "held_item": "none in game (empty hands); both fists authored on a plane (tote + knob)"})
