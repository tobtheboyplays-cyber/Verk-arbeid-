"""LUMBER_CRAFT (lumberer's emergency wooden-axe craft at a crafting table).

    blender -b --factory-startup --python author_lumber_craft.py -- [--fast|--full] [--no-export]

Contract kept (tools/blender/LUMBER_CRAFT_STATE_CONTRACT.md, LumbererSelfCraftGoal):
  2.40 s (48 ticks), ONE-SHOT, starts and ends at exact neutral (every channel 0).
  Ingredient layout contacts at ticks 4/8/12/16/18 (0.20/0.40/0.60/0.80/0.90 s),
  alternating right/left/right/left/right hand on the table top.
  Decisive right-hand strike on the table at tick 30 (1.50 s) + short weight hold.
  Result read 1.80-1.95 s; left-hand pickup contact at tick 43 (2.15 s).
  Feet planted, torso always leans INTO the table, hands never above the crown.

The table is the next block: top at y = 8, the renderer's 3x3 grid centred
16 px ahead. From the adjacent block a 32-px settler can only reach the near
rows, so layout contacts land on the near two rows and the strike/pickup on
the near-centre of the grid.
"""

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 2.40
LAYOUT = (0.20, 0.40, 0.60, 0.80, 0.90)
STRIKE, PICKUP = 1.50, 2.15

clip = ck.Clip("LUMBER_CRAFT", LENGTH, False, "settler_lumberer.png",
               feet=ck.REST_FEET, knee_out=0.0)
clip.prop("table", (-8, 8, -24), (16, 16, 16), (0.58, 0.42, 0.25, 1))
TOP = 7.4                             # palm on the table top (hand thickness above y = 8)
A = (-3.4, TOP, -11.4)                # right, near-right slot
B = (3.2, TOP, -11.6)                 # left, near-left slot
C = (-1.4, TOP, -11.9)                # right, centre
D = (1.9, TOP, -11.8)                 # left, centre-left
E = (-3.0, TOP, -12.4)                # right, mid-right
HIT = (-0.8, TOP, -12.2)              # strike on the grid (craft commit)
TAKE = (0.4, TOP, -12.4)              # left-hand pickup of the result

K = {
    # QA 2026-09-26: the axe stays in the MAINHAND while the hands lay out the
    # table; tip it down and away in the fist (right_item wrist) so it no longer
    # sweeps through the face (3.9 px) and chest. Neutral at both ends.
    "right_wrist_x": [(0.0, 0.0), (0.30, 70.0, "inout"), (2.08, 70.0, "inout"), (2.40, 0.0)],
    "torso_x": [(0.0, 0.0), (0.12, 10.0, "inout"), (0.20, 16.0, "inout"), (0.40, 17.0, "inout"),
                (0.60, 19.0, "inout"), (0.80, 19.0, "inout"), (0.95, 18.0, "inout"),
                (1.20, 12.0, "inout"), (1.40, 11.0, "accel"), (STRIKE, 26.0, "hold"),
                (1.62, 26.0, "inout"), (1.85, 18.0, "inout"), (2.00, 21.0, "inout"),
                (PICKUP, 25.0, "inout"), (2.30, 8.0, "inout"), (LENGTH, 0.0)],
    "torso_y": [(0.0, 0.0), (0.20, -5.0, "inout"), (0.40, 5.0, "inout"), (0.60, -4.0, "inout"),
                (0.80, 4.0, "inout"), (0.90, -3.0, "inout"), (1.10, 0.0, "inout"),
                (1.40, 5.0, "accel"), (STRIKE, -3.0, "inout"), (1.80, 0.0, "inout"),
                (PICKUP, 4.0, "inout"), (2.32, 0.0, "inout"), (LENGTH, 0.0)],
    "torso_z": [(0.0, 0.0), (LENGTH, 0.0)],
    "root_y": [(0.0, 0.0), (0.20, -0.3, "inout"), (0.95, -0.5, "inout"), (1.30, -0.2, "inout"),
               (1.40, -0.25, "accel"), (STRIKE, -1.0, "out"), (1.70, -0.8, "inout"),
               (1.95, -0.6, "inout"), (PICKUP, -0.95, "inout"), (2.32, -0.15, "inout"), (LENGTH, 0.0)],
    "root_x": [(0.0, 0.0), (LENGTH, 0.0)],
    "root_z": [(0.0, 0.0), (0.20, -0.3, "inout"), (1.95, -0.3, "inout"), (PICKUP, -0.5, "inout"),
               (2.32, -0.05, "inout"), (LENGTH, 0.0)],
    "root_yaw": [(0.0, 0.0), (LENGTH, 0.0)],
    "head_env": [(0.0, 0.0), (0.12, 1.0, "inout"), (2.22, 1.0, "inout"), (LENGTH, 0.0)],
    "head_nod": [(0.0, 0.0), (STRIKE, 4.0, "out"), (1.85, 2.0, "inout"), (LENGTH, 0.0)],
    "head_yaw": [(0.0, 0.0), (LENGTH, 0.0)], "head_roll": [(0.0, 0.0), (LENGTH, 0.0)],
    "cloak_add": [(0.0, 0.0), (LENGTH, 0.0)],
}
clip.keys(K)
clip.head_env = True
clip.key_vec("look", [(0.0, (0.0, 7.5, -12.0)), (0.20, A, "inout"), (0.40, B, "inout"),
                      (0.60, C, "inout"), (0.80, D, "inout"), (1.0, (0.0, 7.5, -12.5), "inout"),
                      (PICKUP, TAKE, "inout")])
PR, PL = (-0.75, 0.35, 0.6), (0.75, 0.35, 0.6)
R_FETCH, L_FETCH = (-4.8, 9.6, -5.2), (4.8, 9.6, -5.2)   # items come from the belt/bag side
clip.arm_goals("right", [
    (0.0, "rest", "inout"),
    (0.10, R_FETCH, "inout"), (0.15, (-3.8, 5.6, -10.2), "inout"),
    (LAYOUT[0], A, "inout"),                                      # layout 1 (tick 4)
    (0.26, (-4.0, 5.6, -9.8), "inout"),
    (0.32, R_FETCH, "inout"), (0.50, (-3.2, 5.4, -10.0), "inout"),
    (LAYOUT[2], C, "inout"),                                      # layout 3 (tick 12)
    (0.67, (-2.8, 5.4, -10.4), "inout"),
    (0.74, (-3.8, 5.6, -10.0), "inout"), (LAYOUT[4], E, "inout"),  # layout 5 (tick 18)
    (1.05, (-3.8, 6.0, -9.2), "inout"),                           # read the layout
    (1.28, (-6.6, -0.5, -6.0), "inout"),                          # wind-up, below the crown
    (1.40, (-6.8, -1.6, -5.4), "accel"),                          # hang, then drive
    (STRIKE, HIT, "hold"),                                        # STRIKE (tick 30), weight on it
    (1.62, HIT, "inout"),
    (1.82, (-3.6, 6.0, -9.4), "inout"),                           # result read
    (2.15, (-4.0, 7.4, -8.4), "inout"),
    (2.30, (-4.4, 10.4, -4.0), "inout"),
    (LENGTH, "rest"),
], PR)
clip.arm_goals("left", [
    (0.0, "rest", "inout"),
    (0.26, L_FETCH, "inout"), (0.34, (3.6, 5.6, -10.2), "inout"),
    (LAYOUT[1], B, "inout"),                                      # layout 2 (tick 8)
    (0.47, (3.8, 5.4, -10.0), "inout"),
    (0.58, L_FETCH, "inout"), (0.70, (2.8, 5.2, -10.2), "inout"),
    (LAYOUT[3], D, "inout"),                                      # layout 4 (tick 16)
    (0.88, (3.2, 5.6, -10.0), "inout"),
    (0.98, (4.4, 8.2, -8.0), "inout"),
    (1.18, (3.8, TOP, -9.6), "inout"), (STRIKE, (3.8, TOP, -9.6), "inout"),   # braces on the edge
    (1.85, (3.9, 7.0, -9.4), "inout"),
    (1.98, (2.4, 5.8, -10.8), "inout"),                           # reaches for the result
    (PICKUP, TAKE, "hold"),                                       # PICKUP (tick 43)
    (2.19, TAKE, "inout"),
    (2.30, (4.2, 8.6, -6.0), "inout"),
    (LENGTH, "rest"),
], PL)
clip.head_w = (0.45, 0.6)
clip.run(contacts=[("layout1_r", LAYOUT[0], "right", A), ("layout2_l", LAYOUT[1], "left", B),
                   ("layout3_r", LAYOUT[2], "right", C), ("layout4_l", LAYOUT[3], "left", D),
                   ("layout5_r", LAYOUT[4], "right", E), ("strike_r", STRIKE, "right", HIT),
                   ("pickup_l", PICKUP, "left", TAKE)],
         keep=(1.40, 1.62, 2.19),
         surfaces=[("table", -8, 8, -24, -8, 8.0)],
         strike=("right", STRIKE),
         meta={"contract": "LUMBER_CRAFT 2.40 s one-shot; layout ticks 4/8/12/16/18, table strike "
                           "tick 30 (1.50 s), left pickup tick 43 (2.15 s); exact neutral at both ends "
                           "(tools/blender/LUMBER_CRAFT_STATE_CONTRACT.md)"})
