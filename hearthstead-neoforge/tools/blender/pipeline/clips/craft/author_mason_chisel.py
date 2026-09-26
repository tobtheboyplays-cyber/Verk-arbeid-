"""MASON_CHISEL (mason), authored in Blender.

    blender -b --factory-startup --python author_mason_chisel.py -- [--fast|--full] [--no-export]

Contract kept: 1.05 s loop (21 ticks). CHISEL_TAP at clip tick 10
(Employment.soundContactOf(WORK_CHISEL) = 10): the mallet lands on the
chisel head at t = 0.50 s and holds from 0.50.

Mallet in the right fist, chisel in the left fist on the stone. The mallet
strike is elbow-driven and compact (a mason works to a line, not a smith's
full swing): lift to the shoulder, a short hang, an accelerating drop that is
fastest on the chisel, a two-tick hold, a small rebound. The chisel hand is
jarred by each blow, then lifts and re-seats the edge a hair along the line
while the mallet comes back up.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.05
CONTACT, HOLD_END = 0.50, 0.56
TOP, HANG = 0.33, 0.40

V = ck.variant()
clip = ck.Clip("MASON_CHISEL" + ("__V2" if V == "v2" else ""), LENGTH, True, "settler_mason.png",
               cycles=2 if V == "v2" else 1,
               feet=((-3.2, 24.0, 1.2), (3.0, 24.0, -1.4)))
clip.prop("stone", (-7, 8, -20), (14, 16, 12), (0.62, 0.62, 0.60, 1))

CHISEL = np.array([1.6, 5.4, -10.6])            # left fist around the chisel
STRIKE = np.array([-1.3, 2.2, -10.4])           # right fist on the chisel head

K = {
    "root_y": [(0.0, -0.45), (0.30, -0.3, "inout"), (0.40, -0.32, "accel"), (0.49, -0.8, "out"),
               (0.60, -0.75, "inout"), (0.80, -0.5, "smooth")],
    "root_x": [(0.0, 0.0), (0.30, -0.25, "inout"), (0.49, 0.2, "out"), (0.85, 0.0, "smooth")],
    "root_z": [(0.0, 0.0)],
    "root_yaw": [(0.0, 0.0), (0.30, 3.0, "inout"), (0.38, 2.5, "accel"), (0.48, -1.5, "out"),
                 (0.80, -0.5, "smooth")],
    "torso_x": [(0.0, 20.0), (0.32, 16.0, "inout"), (0.40, 16.5, "accel"), (0.49, 23.0, "out"),
                (0.62, 22.0, "inout"), (0.85, 20.0, "smooth")],
    "torso_y": [(0.0, -2.0), (0.32, 5.0, "inout"), (0.40, 4.5, "accel"), (0.49, -3.0, "out"),
                (0.70, -2.5, "smooth")],
    "torso_z": [(0.0, 0.0), (0.33, 2.0, "inout"), (0.49, -1.0, "out"), (0.80, 0.0, "smooth")],
    "head_nod": [(0.0, 3.0), (0.35, 1.5, "inout"), (0.51, 5.0, "out"), (0.80, 3.0, "smooth")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)], "cloak_add": [(0.0, 0.0)],
    # chisel hand: jarred by the blow, then lifts and re-seats the edge
    "lh_dy": [(0.0, 0.0), (0.50, 0.0, "linear"), (0.52, 0.3, "decel"), (0.60, 0.0, "inout"),
              (0.72, -0.5, "inout"), (0.84, -0.45, "inout"), (0.95, 0.0, "smooth")],
    "lh_dx": [(0.0, 0.0), (0.66, 0.0, "inout"), (0.84, 0.45, "inout"), (1.0, 0.0, "smooth")],
}
clip.keys(K)
clip.key_vec("look", [(0.0, (0.8, 7.8, -11.0))])
P_REST, P_UP = (-0.75, 0.3, 0.6), (-0.85, -0.2, 0.5)
LOG = clip.arm_goals("right", [
    (0.00, (-2.0, 1.2, -10.8), "inout", P_REST),    # mallet resting just above the chisel (QA: off the chin)
    (0.12, (-2.6, 0.6, -9.6), "smooth", P_REST),
    (TOP, (-6.4, -4.6, -5.2), "inout", P_UP),        # lifted beside the shoulder, clear of the cheek
    (HANG, (-6.6, -5.0, -4.8), "accel", P_UP),
    (CONTACT, tuple(STRIKE), "hold", P_REST),        # CONTACT, tick 10
    (HOLD_END, tuple(STRIKE + np.array([0, -0.05, 0])), "out", P_REST),
    (0.64, (-1.8, 1.2, -10.8), "inout", P_REST),      # small rebound
    (0.84, (-2.0, 1.4, -10.7), "smooth", P_REST),
], P_REST)
clip.arm_live("left", lambda t, w: CHISEL + np.array([ck.c("lh_dx", t), ck.c("lh_dy", t), 0.0]),
              (0.75, 0.45, 0.55))
if V == "v2":
    # __v2: after the first blow the mason sweeps the dust off the line with
    # the edge of the chisel hand (two short strokes) and leans in to check
    # it, back on the mark before the second wind-up (strike 1.55 s).
    clip.vary({
        "lh_dx": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.72, -2.6, "inout"), (0.80, 0.8, "inout"),
                  (0.88, -2.4, "inout"), (0.98, 0.6, "inout"), (1.10, 0.0)],
        "lh_dy": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.68, 1.8, "inout"), (0.96, 1.8, "inout"),
                  (1.10, 0.0)],
        "torso_x": [(0.0, 0.0), (0.62, 0.0, "inout"), (0.80, 4.0, "inout"), (1.00, 4.0, "inout"),
                    (1.14, 0.0)],
        "head_nod": [(0.0, 0.0), (0.64, 0.0, "inout"), (0.82, 7.0, "inout"), (1.00, 6.0, "inout"),
                     (1.14, 0.0)],
        "head_yaw": [(0.0, 0.0), (0.64, 0.0, "inout"), (0.86, -6.0, "inout"), (1.12, 0.0)],
    })
clip.head_w = (0.3, 0.5)
clip.run(contacts=[("mallet_on_chisel", CONTACT, "right", STRIKE), ("chisel_held", CONTACT, "left", CHISEL)],
         keep=(TOP, HANG, HOLD_END), strike=("right", CONTACT),
         surfaces=[("stone", -7, 7, -20, -8, 8.0)],
         meta={"variant": V or "base", "contract": "MASON_CHISEL 1.05 s loop; CHISEL_TAP contact t=0.50 s = tick 10 of 21, "
                           "hold 0.50-0.56 s",
               "held_item": "none in game (empty MAINHAND); fists authored as mallet + chisel grips"})
