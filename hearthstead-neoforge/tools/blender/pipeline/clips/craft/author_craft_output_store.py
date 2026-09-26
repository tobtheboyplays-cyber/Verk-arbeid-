"""CRAFT_OUTPUT_STORE (lumberer puts the crafted output into storage).

    blender -b --factory-startup --python author_craft_output_store.py -- [--fast|--full] [--no-export]

Contract kept (SettlerAnimations.CRAFT_OUTPUT_STORE / LumbererSelfCraftGoal):
  1.20 s (24 ticks), ONE-SHOT, starts and ends at exact neutral.
  The server deposits atomically at DEPOSIT_CONTACT_TICK 14 = t 0.70 s: the
  LEFT hand is in the chest mouth on that frame. No hand item is invented
  (the renderer draws the escrow copy just outside the chest).

Beats: the right hand takes the lid and swings it up (0.20-0.45) while the
left hand draws the output from the bag side; knees give and the spine folds
over the chest (hips first); the left hand goes in and holds a beat at the
deposit (0.65-0.80); it withdraws, the right hand lowers the lid, and the
body rises back to neutral.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.20
DEPOSIT = 0.70

clip = ck.Clip("CRAFT_OUTPUT_STORE", LENGTH, False, "settler_lumberer.png",
               feet=ck.REST_FEET, knee_out=0.0)
clip.prop("chest", (-7, 10, -23), (14, 14, 14), (0.55, 0.38, 0.20, 1))

IN = (1.0, 9.6, -11.2)                    # left palm in the chest mouth
LID = [(-2.6, 9.8, -9.4), (-2.6, 5.0, -10.8), (-2.6, 4.0, -10.9)]   # lid front edge closed -> open

K = {
    # QA 2026-09-26: axe tipped down and away while the left hand stores the
    # output (was 3.2 px through the face at 0.35 s). Neutral at both ends.
    "right_wrist_x": [(0.0, 0.0), (0.24, 50.0, "inout"), (0.96, 50.0, "inout"), (1.20, 0.0)],
    "torso_x": [(0.0, 0.0), (0.20, 10.0, "inout"), (0.50, 20.0, "inout"), (0.65, 26.0, "inout"),
                (DEPOSIT, 28.0, "hold"), (0.80, 27.0, "inout"), (1.00, 9.0, "inout"), (1.15, 0.5, "inout"),
                (LENGTH, 0.0)],
    "torso_y": [(0.0, 0.0), (0.30, 3.0, "inout"), (DEPOSIT, -4.0, "inout"), (0.95, 1.0, "inout"),
                (LENGTH, 0.0)],
    "torso_z": [(0.0, 0.0), (LENGTH, 0.0)],
    "root_y": [(0.0, 0.0), (0.30, -0.4, "inout"), (0.62, -1.2, "inout"), (DEPOSIT, -1.3, "hold"),
               (0.84, -1.25, "inout"), (1.05, -0.25, "inout"), (LENGTH, 0.0)],
    "root_z": [(0.0, 0.0), (0.62, -0.3, "inout"), (0.90, -0.2, "inout"), (LENGTH, 0.0)],
    "root_x": [(0.0, 0.0), (LENGTH, 0.0)], "root_yaw": [(0.0, 0.0), (LENGTH, 0.0)],
    "head_env": [(0.0, 0.0), (0.18, 1.0, "inout"), (1.02, 1.0, "inout"), (LENGTH, 0.0)],
    "head_nod": [(0.0, 0.0), (DEPOSIT, 3.0, "inout"), (LENGTH, 0.0)],
    "head_yaw": [(0.0, 0.0), (LENGTH, 0.0)], "head_roll": [(0.0, 0.0), (LENGTH, 0.0)],
    "cloak_add": [(0.0, 0.0), (LENGTH, 0.0)],
}
clip.keys(K)
clip.head_env = True
clip.key_vec("look", [(0.0, (0.0, 9.0, -12.0)), (0.25, LID[0], "inout"), (0.55, IN, "inout")])
clip.arm_goals("right", [
    (0.0, "rest", "inout"),
    (0.22, LID[0], "inout"),                    # takes the lid
    (0.36, LID[1], "inout"), (0.48, LID[2], "inout"), (0.84, LID[2], "inout"),   # holds it up
    (0.96, LID[1], "inout"), (1.04, LID[0], "inout"),                             # lowers it
    (1.13, (-5.0, 10.0, -3.0), "inout"),
    (LENGTH, "rest"),
], (-0.75, 0.3, 0.6))
clip.arm_goals("left", [
    (0.0, "rest", "inout"),
    (0.20, (5.6, 9.4, -2.8), "inout"),          # draws the output from the bag side
    (0.45, (3.2, 6.4, -8.6), "inout"),          # presents it
    (0.62, (1.4, 8.6, -10.6), "inout"),
    (DEPOSIT, IN, "hold"),                      # DEPOSIT (tick 14)
    (0.80, IN, "inout"),
    (0.95, (3.6, 7.4, -7.2), "inout"),          # withdraws
    (1.10, (5.4, 10.0, -2.4), "inout"),
    (LENGTH, "rest"),
], (0.75, 0.3, 0.6))
clip.head_w = (0.45, 0.6)
clip.run(contacts=[("deposit_l", DEPOSIT, "left", IN), ("lid_open_r", 0.60, "right", LID[2])],
         keep=(0.22, 0.48, 0.80, 0.84, 1.04),
         meta={"contract": "CRAFT_OUTPUT_STORE 1.20 s one-shot; left-hand deposit contact tick 14 "
                           "(0.70 s); exact neutral at both ends"})
