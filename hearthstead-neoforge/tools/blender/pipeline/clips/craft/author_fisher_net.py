"""FISHER_NET (RING-1 lane, Fisher's Nets): the Fisher hauls his net in off
the bank, shakes the catch out and tosses it back. New clip.

    blender -b --factory-startup --python author_fisher_net.py -- [--fast|--full] [--no-export]

Contract: animation.settler.fisher_net, 4.00 s (80 ticks = FisherNetGoal.NET_TICKS),
looping. The catch goes into the bag at 3.00 s (tick 60, FisherNetGoal.HAUL_TICK),
the float lands on the water at 3.50 s (tick 70, TOSS_TICK). The first set of a
net plays the same clip: the haul of an empty head rope reads as laying it out.

Beats: hand over hand on the head rope, each pull a little different (the net
drags, then comes easier, the last pull is the heavy one as the net breaks the
surface): right grabs at 0.35, left at 0.80, right at 1.30, left at 1.85; weight
rocks back into the heels on every draw. The hands gather the wet net (2.2-2.4),
lift it to the chest with the trunk leaning back (2.8), give it two short shakes
(3.0-3.2: the fish drop into the bag at 3.0), wind up at the right hip with a
turn of the shoulders (3.30) and throw it out and up (3.50, the float lands),
then settle, watching it ride.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 4.0
GRABS = (("right", 0.35), ("left", 0.80), ("right", 1.30), ("left", 1.85))
HAUL, TOSS = 3.0, 3.5

clip = ck.Clip("FISHER_NET", LENGTH, True, "settler_fisher.png",
               feet=((-3.2, 24.0, 2.2), (3.0, 24.0, -2.0)), knee_out=0.2)
clip.cam_side = ((-3.6, -1.4, 1.45), (0.0, -0.9, 0.7), 36)
clip.prop("water", (-60, 23.4, -62), (120, 0.7, 56), (0.20, 0.35, 0.55, 1))   # the lake, just below the bank edge

REACH = {"right": np.array([-2.0, 8.0, -11.0]), "left": np.array([2.0, 8.3, -11.2])}   # low, out along the rope
DRAW = {"right": np.array([-3.6, 10.6, -2.4]), "left": np.array([3.6, 10.8, -2.6])}   # in to the hip
GATHER = np.array([0.0, 8.2, -8.4])
LIFT = np.array([0.0, -0.6, -8.0])
WIND = np.array([-5.6, 11.6, 0.6])
THROW = np.array([-0.8, -2.8, -13.5])
REST = {"right": np.array([-2.6, 9.6, -5.4]), "left": np.array([2.6, 9.8, -5.6])}
SPREAD = {"right": np.array([-2.4, 0.0, 0.0]), "left": np.array([2.4, 0.0, 0.0])}


def hand_track(side):
    """(t, hand_xyz, ease) keys for one side over the whole clip."""
    k = [(0.0, REST[side] + [0.0, -1.0, -2.0], "inout")]
    for i, (s, t) in enumerate(GRABS):
        heavy = 1.0 + 0.25 * i                  # the net drags harder as it comes in
        if s == side:
            k += [(t - 0.18, REACH[side] + [0.0, -0.6, -0.4], "inout"),
                  (t, REACH[side], "accel"),                                  # grab the rope
                  (t + 0.27 + 0.03 * i, DRAW[side] + [0.0, 0.2 * heavy, 0.3], "out")]
        else:
            # the other hand holds the rope where it is, sliding forward a touch
            k += [(t + 0.05, DRAW[side] + [0.0, -0.4, -1.2], "inout")]
    k += [(2.32, GATHER + SPREAD[side] * 0.9, "inout"),
          (2.50, GATHER + SPREAD[side] * 0.7 + [0.0, 0.6, 0.0], "inout"),
          (2.80, LIFT + SPREAD[side], "out"),
          (HAUL, LIFT + SPREAD[side] + [0.0, 0.8, 0.0], "inout"),             # catch into the bag
          (3.10, LIFT + SPREAD[side] + [0.0, -0.6, 0.0], "inout"),
          (3.20, LIFT + SPREAD[side] + [0.0, 0.5, 0.0], "inout"),
          (3.30, WIND + SPREAD[side] * 0.5 + ([0.0, 0.0, -2.6] if side == "left" else 0.0), "accel"),   # left elbow stays in front (job pack)
          (TOSS, THROW + SPREAD[side] * 0.7, "out"),                          # float lands
          (3.72, REST[side] + [0.0, -2.0, -3.0], "inout")]
    k.sort(key=lambda x: x[0])
    return k


K = {
    "root_z": [(0.0, 0.0), (0.35, -0.4, "inout"), (0.62, 0.8, "out"), (0.80, -0.2, "inout"),
               (1.07, 0.9, "out"), (1.30, -0.3, "inout"), (1.60, 1.0, "out"), (1.85, -0.2, "inout"),
               (2.18, 1.4, "out"), (2.80, 1.0, "inout"), (3.30, 1.2, "inout"), (TOSS, -1.3, "out"),
               (3.8, -0.4, "inout")],
    "root_y": [(0.0, -0.4), (0.62, -0.9, "inout"), (1.07, -0.8, "inout"), (1.60, -1.0, "inout"),
               (2.18, -1.3, "inout"), (2.80, -0.3, "inout"), (3.30, -1.2, "inout"), (TOSS, -0.2, "out"),
               (3.8, -0.4, "inout")],
    "root_x": [(0.0, 0.0), (0.62, -0.3, "inout"), (1.07, 0.3, "inout"), (1.60, -0.3, "inout"),
               (2.18, 0.3, "inout"), (3.30, -0.8, "inout"), (TOSS, 0.4, "out")],
    "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, 12.0), (0.35, 19.0, "inout"), (0.62, 6.0, "out"), (0.80, 18.0, "inout"),
                (1.07, 5.0, "out"), (1.30, 18.0, "inout"), (1.60, 4.0, "out"), (1.85, 20.0, "inout"),
                (2.18, 2.0, "out"), (2.42, 10.0, "inout"), (2.80, -6.0, "out"), (3.20, -4.0, "inout"),
                (3.30, 4.0, "inout"), (TOSS, 14.0, "out"), (3.72, 12.0, "inout")],
    "torso_y": [(0.0, 0.0), (0.62, -5.0, "inout"), (1.07, 5.0, "inout"), (1.60, -5.0, "inout"),
                (2.18, 6.0, "inout"), (2.60, 0.0, "inout"), (3.30, -16.0, "inout"), (TOSS, 6.0, "out"),
                (3.8, 0.0, "inout")],
    "torso_z": [(0.0, 0.0), (3.30, 3.0, "inout"), (TOSS, -2.0, "out"), (3.8, 0.0, "inout")],
    "head_nod": [(0.0, 8.0), (2.2, 10.0, "inout"), (2.8, 2.0, "inout"), (3.3, 4.0, "inout"),
                 (TOSS, -2.0, "out"), (3.8, 6.0, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0), (3.0, 0.0, "inout"), (3.12, 4.0, "inout"),
                                            (3.3, 0.0, "inout")],
    "cloak_add": [(0.0, 0.0), (TOSS, 4.0, "out"), (3.8, 0.0, "inout")],
}
clip.keys(K)
clip.key_vec("look", [(0.0, (0.0, 26.0, -40.0)), (2.1, (0.0, 20.0, -24.0), "inout"),
                      (2.8, tuple(LIFT + [0, 2, 0]), "inout"), (3.25, tuple(LIFT + [0, 2, 0]), "inout"),
                      (3.6, (4.0, 26.0, -48.0), "inout")])
clip.arm_goals("right", hand_track("right"), (-0.75, 0.25, 0.6))
clip.arm_goals("left", hand_track("left"), (0.75, 0.25, 0.6))
clip.head_w = (0.45, 0.55)
clip.hand_props = [{"hand": "mainhand", "item": "minecraft:string", "from": 0.0, "to": LENGTH,
                    "hide_real": True}]
clip.run(contacts=[(f"grab{i + 1}_{s}", t, s, REACH[s]) for i, (s, t) in enumerate(GRABS)]
         + [("toss_r", TOSS, "right", THROW + SPREAD["right"] * 0.7)],
         keep=(2.80, HAUL, 3.30, TOSS),
         strike=("right", [TOSS]),
         meta={"contract": "FISHER_NET 4.00 s loop (80 ticks); catch into the bag at tick 60 "
                           "(FisherNetGoal.HAUL_TICK), float lands at tick 70 (TOSS_TICK)",
               "held_item": "rod hidden (hide_real); a coil of line (minecraft:string) in the right hand; "
                            "the fisher_net part rides the left forearm"})
