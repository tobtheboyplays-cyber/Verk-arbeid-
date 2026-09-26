"""Hero Captain -- HALBERD (plan/CAPTAIN.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python halberd.py -- [--fast|--full|--three] [--no-export] CONST ...

Long reach, medium speed (WeaponClass.HALBERD: 16-tick swing, contact tick 7, reach x1.5). The
halberd works at the END of its pole: thrusts drive from the back hand, the sweep is a wide flat
arc, the hook reaches past the target and yanks it in. Reuses the great-axe body mechanics
(axe.py) with the halberd's own geometry (weapons lane) and timing.

CAPTAIN_HALBERD_STANCE   2.40 s loop  pole at the ready, head forward and up, butt by the hip.
CAPTAIN_HALBERD_SWING_A  0.80 s       contact 0.35 (tick 7): the thrust -- drawn back, driven out.
CAPTAIN_HALBERD_SWING_B  0.80 s       contact 0.35: the variant -- a short chopping sweep from the right.
CAPTAIN_HALBERD_SWEEP    1.30 s       wind-up 0.60 (tick 12): head carried far back right, a wide flat arc.
CAPTAIN_BRACE_CHARGE     0.80 s       wind-up 0.20 (tick 4): already low and braced, the point snaps
                                      up into the charger.
CAPTAIN_HOOK_PULL        1.20 s       wind-up 0.50 (tick 10): reach high past the target, hook, yank
                                      back hard with the whole body.
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
# re-point the shared great-axe mechanics at the halberd
axe.ITEM = "halberd"
axe.STANCE = dict(axe.STANCE, gl_s=kit.HAL_GRIP2_S, hip_yaw=12.0)
axe.HAND = np.array([-2.6, 11.0, -5.0])
axe.DIR = np.array([0.08, -0.45, -0.89])       # pole forward and up, head well out in front
ITEM = axe.ITEM


def stance():
    L, loop, solve, _, keep, more = axe.stance()
    return L, loop, solve, "2.40 s loop: halberd at the ready, head forward and up", keep, more


def swing_a():
    L, HIT = 0.80, 0.35
    _, _, keep = axe._swing(L, HIT, np.array([-2.4, 10.4, -0.8]), np.array([0.05, -0.30, -0.95]),
                            np.array([-1.6, 9.8, -10.6]), np.array([0.05, -0.14, -0.99]),
                            np.array([-1.5, 10.0, -11.0]), np.array([0.05, -0.12, -0.99]), 6.0, 8.0, -2.8, False,
                            c0=0.18, c1=0.24, lift=0.08)
    return (L, False, axe.solve(L, False), "0.80 s one-shot from/to CAPTAIN_HALBERD_STANCE; drawn back by 0.18, hang "
            "to 0.24, thrust, contact t=0.35 s = tick 7 (WeaponClass.HALBERD)", keep, None)


def swing_b():
    L, HIT = 0.80, 0.35
    _, _, keep = axe._swing(L, HIT, np.array([-7.0, 7.6, -0.6]), np.array([-0.80, -0.35, -0.48]),
                            np.array([-1.4, 9.0, -9.4]), np.array([0.10, -0.10, -0.99]),
                            np.array([2.6, 9.4, -8.0]), np.array([0.80, -0.08, -0.59]), 14.0, 16.0, -2.2, False,
                            c0=0.18, c1=0.24, lift=0.08)
    return (L, False, axe.solve(L, False), "0.80 s one-shot (variant): short chopping sweep from the right, contact "
            "t=0.35 s = tick 7", keep, None)


def halberd_sweep():
    L, HIT = 1.30, 0.60
    _, _, keep = axe._swing(L, HIT, np.array([-8.0, 8.2, 1.6]), np.array([-0.90, -0.20, 0.38]),
                            np.array([-1.2, 9.2, -9.6]), np.array([0.05, -0.05, -1.0]),
                            np.array([4.6, 9.4, -6.8]), np.array([0.94, -0.05, -0.33]), 26.0, 26.0, -2.6, False,
                            c0=0.36, c1=0.50)
    return (L, False, axe.solve(L, False), "1.30 s one-shot; head carried far back right by 0.36, hang to 0.50 "
            "(telegraph), a wide flat arc, contact t=0.60 s = tick 12 (CaptainSpecial.HALBERD_SWEEP)", keep, None)


def brace_charge():
    L, HIT = 0.80, 0.20
    _, _, keep = axe._swing(L, HIT, np.array([-2.6, 12.6, -2.2]), np.array([0.05, -0.20, -0.98]),
                            np.array([-1.8, 11.6, -9.8]), np.array([0.05, -0.42, -0.91]),
                            np.array([-1.7, 11.4, -10.2]), np.array([0.05, -0.46, -0.89]), 4.0, 6.0, -1.2, False,
                            c0=0.08, c1=0.12, lift=0.04)
    return (L, False, axe.solve(L, False), "0.80 s one-shot; already low and braced, butt dropped by 0.08, the point "
            "snaps up into the charger, contact t=0.20 s = tick 4 (CaptainSpecial.BRACE_CHARGE)", keep, None)


def hook_pull():
    L, HIT = 1.20, 0.50
    _, _, keep = axe._swing(L, HIT, np.array([-2.8, 8.6, -1.4]), np.array([0.05, -0.62, -0.78]),
                            np.array([-1.4, 8.8, -10.6]), np.array([0.05, 0.10, -0.99]),
                            np.array([-2.8, 10.8, -2.6]), np.array([0.08, -0.20, -0.98]), 6.0, 10.0, -2.4, False,
                            c0=0.28, c1=0.40)
    return (L, False, axe.solve(L, False), "1.20 s one-shot; pole raised high by 0.28, hang to 0.40 (telegraph), "
            "reach past the target and hook at t=0.50 s = tick 10 (CaptainSpecial.HOOK_PULL), yanked back to the "
            "body by 0.70", keep, None)


CLIPS = {"CAPTAIN_HALBERD_STANCE": stance, "CAPTAIN_HALBERD_SWING_A": swing_a, "CAPTAIN_HALBERD_SWING_B": swing_b,
         "CAPTAIN_HALBERD_SWEEP": halberd_sweep, "CAPTAIN_BRACE_CHARGE": brace_charge, "CAPTAIN_HOOK_PULL": hook_pull}

if __name__ == "__main__":
    kit.main(CLIPS, "halberd")
