"""Hero Captain -- WARHAMMER (plan/CAPTAIN.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python hammer.py -- [--fast|--full|--three] [--no-export] CONST ...

The slowest weapon (WeaponClass.WARHAMMER: 24-tick swing, contact tick 12): a long, readable
wind-up, the whole body falls in behind the head, a jarring stop. Reuses the great-axe body
mechanics (axe.py) with the warhammer's own geometry (weapons lane) and timing.

CAPTAIN_HAMMER_STANCE   2.40 s loop  the hammer head grounded before the right foot, hands on the
                                     haft, weight settled.
CAPTAIN_HAMMER_SWING_A  1.20 s       contact 0.60 (tick 12): hauled over the right shoulder,
                                     overhead crush.
CAPTAIN_HAMMER_SWING_B  1.20 s       contact 0.60: the variant -- a flat side swing from the right.
CAPTAIN_SHIELD_BREAKER  1.20 s       wind-up 0.70 (tick 14): a short high cock, a hooking downward
                                     blow onto the shield rim.
CAPTAIN_GROUND_SLAM     1.50 s       wind-up 0.90 (tick 18): raised high, a leap-less plunge, the head
                                     driven into the ground before the feet.
CAPTAIN_CRUSHING_BLOW   1.60 s       wind-up 1.00 (tick 20): the longest hang, then everything.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np  # noqa: E402

import captainkit as kit  # noqa: E402
import axe_v1 as axe  # noqa: E402  (v1 mechanics until the v2 re-author)

rk = kit.rk
S, ACC, ACC4, DEC, DEC3, LIN = kit.S, kit.ACC, kit.ACC4, kit.DEC, kit.DEC3, kit.LIN
# re-point the shared great-axe mechanics at the warhammer
axe.ITEM = "warhammer"
axe.STANCE = dict(axe.STANCE, gl_s=kit.HAM_GRIP2_S, sp_x=8.0, hip_y=-1.3)
axe.HAND = np.array([-2.6, 11.4, -5.4])
axe.DIR = np.array([0.20, 0.55, -0.81])        # head down and forward, resting before the right foot
ITEM = axe.ITEM


def stance():
    L, loop, solve, _, keep, more = axe.stance()
    return L, loop, solve, "2.40 s loop: warhammer grounded before the right foot, weight settled", keep, more


def swing_a():
    L, HIT = 1.20, 0.60
    _, _, keep = axe._swing(L, HIT, np.array([-6.4, -0.6, 0.4]), np.array([-0.25, -0.90, 0.36]),
                            np.array([-1.4, 8.8, -9.2]), np.array([0.10, 0.55, -0.83]),
                            np.array([-0.4, 11.4, -8.0]), np.array([0.10, 0.85, -0.52]), 12.0, 14.0, -2.6, False,
                            c0=0.36, c1=0.46)
    return (L, False, axe.solve(L, False), "1.20 s one-shot from/to CAPTAIN_HAMMER_STANCE; hauled over the right "
            "shoulder by 0.36, hang to 0.46, overhead crush, contact t=0.60 s = tick 12 (WeaponClass.WARHAMMER)",
            keep, None)


def swing_b():
    L, HIT = 1.20, 0.60
    _, _, keep = axe._swing(L, HIT, np.array([-7.6, 8.0, 1.2]), np.array([-0.72, -0.12, 0.68]),
                            np.array([-1.0, 7.8, -9.6]), np.array([0.05, 0.05, -1.0]),
                            np.array([3.0, 8.2, -8.2]), np.array([0.96, 0.10, -0.26]), 22.0, 28.0, -2.2, False,
                            c0=0.36, c1=0.46)
    return (L, False, axe.solve(L, False), "1.20 s one-shot (variant): flat side swing from the right, contact "
            "t=0.60 s = tick 12", keep, None)


def shield_breaker():
    L, HIT = 1.20, 0.70
    _, _, keep = axe._swing(L, HIT, np.array([-5.4, 2.4, -3.6]), np.array([-0.10, -0.95, 0.30]),
                            np.array([-1.2, 7.8, -9.8]), np.array([0.05, 0.62, -0.78]),
                            np.array([-0.8, 10.6, -8.6]), np.array([0.08, 0.90, -0.42]), 8.0, 10.0, -2.8, False,
                            c0=0.44, c1=0.60)
    return (L, False, axe.solve(L, False), "1.20 s one-shot; short high cock 0.44, hang to 0.60 (telegraph), a hooking "
            "downward blow onto the shield rim, contact t=0.70 s = tick 14 (CaptainSpecial.SHIELD_BREAKER)", keep, None)


def ground_slam():
    L, HIT = 1.50, 0.90
    _, _, keep = axe._swing(L, HIT, np.array([-4.6, -3.6, -2.0]), np.array([-0.10, -0.80, 0.59]),
                            np.array([-1.6, 10.4, -8.4]), np.array([0.05, 0.92, -0.38]),
                            np.array([-1.6, 11.0, -8.0]), np.array([0.05, 0.95, -0.30]), 6.0, 8.0, -3.0, False,
                            c0=0.56, c1=0.80)
    return (L, False, axe.solve(L, False), "1.50 s one-shot; raised high by 0.56, the long hang to 0.80 (telegraph), "
            "the head driven into the ground before the feet, contact t=0.90 s = tick 18 (CaptainSpecial.GROUND_SLAM)",
            keep, None)


def crushing_blow():
    L, HIT = 1.60, 1.00
    _, _, keep = axe._swing(L, HIT, np.array([-6.8, -1.6, 1.2]), np.array([-0.30, -0.80, 0.52]),
                            np.array([-1.4, 8.6, -9.4]), np.array([0.05, 0.45, -0.89]),
                            np.array([-0.6, 11.0, -8.2]), np.array([0.08, 0.82, -0.56]), 16.0, 20.0, -3.2, False,
                            c0=0.60, c1=0.88)
    return (L, False, axe.solve(L, False), "1.60 s one-shot; the longest hang 0.60-0.88 (telegraph), everything behind "
            "it, contact t=1.00 s = tick 20 (CaptainSpecial.CRUSHING_BLOW)", keep, None)


CLIPS = {"CAPTAIN_HAMMER_STANCE": stance, "CAPTAIN_HAMMER_SWING_A": swing_a, "CAPTAIN_HAMMER_SWING_B": swing_b,
         "CAPTAIN_SHIELD_BREAKER": shield_breaker, "CAPTAIN_GROUND_SLAM": ground_slam,
         "CAPTAIN_CRUSHING_BLOW": crushing_blow}

if __name__ == "__main__":
    kit.main(CLIPS, "hammer")
