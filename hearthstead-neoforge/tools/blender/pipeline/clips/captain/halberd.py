"""Hero Captain -- HALBERD v2 (plan/captain/ANIM-REFERENCES.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python halberd.py -- [--keys | --three] [--no-export] CONST ...

Long reach, medium speed (WeaponClass.HALBERD: 16-tick swing, contact tick 7, reach x1.5). Fenced
from a low guard, pole forward: thrusts drive from the back hand with a step, the sweep is a wide
flat arc from the hips, the hook reaches over, catches and yanks back with the whole body. The heavy
grammar of axe.py with the halberd geometry; the hands sit a forearm apart on the pole.

CAPTAIN_HALBERD_STANCE   2.40 s loop  low guard, the head forward at chest height
CAPTAIN_HALBERD_SWING_A  0.80 s       drawn back, driven out: the thrust, contact 0.35 (tick 7)
CAPTAIN_HALBERD_SWING_B  0.80 s       a short chopping sweep from the right, contact 0.35 (tick 7)
CAPTAIN_HALBERD_SWEEP    1.30 s       carried far back right, hang, a wide flat arc, contact 0.60 (tick 12)
CAPTAIN_BRACE_CHARGE     0.80 s       already low and braced, the point snaps up into the charger, contact 0.20
CAPTAIN_HOOK_PULL        1.20 s       raised, reach past the target, hook 0.50 (tick 10), yanked back by 0.70
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import numpy as np  # noqa: E402

import captainkit as kit  # noqa: E402
import axe  # noqa: E402

u = axe.u
axe.ITEM = "halberd"
axe.STANCE = dict(axe.STANCE, gl_s=kit.HAL_GRIP2_S, hip_yaw=6.0, sp_y=10.0)
axe.HAND = np.array([-2.8, 10.0, -6.4])
axe.DIR = u(0.06, -0.30, -0.95)                # pole forward, head at the enemy's chest


def stance():
    L, loop, solve, _, keep, more = axe.stance()
    return L, loop, solve, "2.40 s loop: halberd in a low guard, the head forward at chest height", keep, more


def _clip(L, HIT, lift, hang, poses, yaw, lean, drop, step, text, bite=None):
    _, _, keep = axe.heavy(L, HIT, lift, hang, *poses, yaw, lean, drop, step, bite=bite)
    return L, False, axe.solve(L, False), text, keep, None


def swing_a():
    return _clip(0.80, 0.35, 0.16, 0.22,
                 ((np.array([-3.4, 10.6, -0.4]), u(0.05, -0.26, -0.96)), (np.array([-2.6, 10.0, -6.0]), u(0.05, -0.20, -0.98)),
                  (np.array([-1.4, 9.2, -13.4]), u(0.05, -0.12, -0.99)), (np.array([-1.3, 9.4, -13.6]), u(0.05, -0.10, -0.99))),
                 (14.0, -10.0, -12.0), (-2.0, 14.0, 16.0), (0.2, -1.8, -2.0), ("l", -3.4, -0.2, 1.6),
                 "0.80 s one-shot from/to CAPTAIN_HALBERD_STANCE; drawn back by 0.16, hang to 0.22, driven out with a "
                 "step, the thrust, contact t=0.35 s = tick 7 (WeaponClass.HALBERD)", bite=0.43)


def swing_b():
    return _clip(0.80, 0.35, 0.16, 0.22,
                 ((np.array([-7.2, 6.8, 0.6]), u(-0.85, -0.30, -0.43)), (np.array([-5.0, 7.6, -7.6]), u(-0.50, -0.10, -0.86)),
                  (np.array([-1.4, 8.0, -11.4]), u(0.12, 0.00, -0.99)), (np.array([3.6, 8.4, -9.6]), u(0.80, 0.05, -0.60))),
                 (26.0, -6.0, -24.0), (2.0, 10.0, 12.0), (-0.3, -1.6, -2.0), ("l", -2.0, 0.8, 1.4),
                 "0.80 s one-shot (variant): a short chopping sweep from the right, contact t=0.35 s = tick 7",
                 bite=0.43)


def halberd_sweep():
    return _clip(1.30, 0.60, 0.36, 0.50,
                 ((np.array([-8.0, 7.4, 2.0]), u(-0.90, -0.20, 0.38)), (np.array([-6.0, 7.8, -7.0]), u(-0.70, -0.05, -0.71)),
                  (np.array([-1.2, 8.0, -12.0]), u(0.05, 0.00, -1.0)), (np.array([5.0, 8.4, -8.6]), u(0.95, 0.05, -0.30))),
                 (44.0, -8.0, -46.0), (4.0, 12.0, 16.0), (-0.8, -2.4, -2.8), ("l", -2.8, 0.8, 1.8),
                 "1.30 s one-shot; the head carried far back right by 0.36, hang to 0.50 (telegraph), a wide flat arc "
                 "from the hips, contact t=0.60 s = tick 12 (CaptainSpecial.HALBERD_SWEEP)", bite=0.74)


def brace_charge():
    return _clip(0.80, 0.20, 0.08, 0.12,
                 ((np.array([-2.8, 12.6, -3.2]), u(0.05, -0.22, -0.97)), (np.array([-2.4, 12.0, -6.0]), u(0.05, -0.40, -0.92)),
                  (np.array([-1.8, 11.0, -10.6]), u(0.05, -0.46, -0.89)), (np.array([-1.7, 10.8, -11.0]), u(0.05, -0.50, -0.87))),
                 (6.0, -6.0, -6.0), (6.0, 14.0, 16.0), (-2.4, -3.0, -3.0), ("l", -1.6, 0.0, 1.0),
                 "0.80 s one-shot; already low and braced, butt dropped by 0.08, the point snaps up into the charger, "
                 "contact t=0.20 s = tick 4 (CaptainSpecial.BRACE_CHARGE)", bite=0.30)


def hook_pull():
    return _clip(1.20, 0.50, 0.28, 0.40,
                 ((np.array([-4.0, 4.0, -3.0]), u(0.05, -0.70, -0.71)), (np.array([-2.4, 6.0, -10.0]), u(0.05, -0.30, -0.95)),
                  (np.array([-1.4, 8.6, -13.0]), u(0.05, 0.12, -0.99)), (np.array([-3.2, 10.8, -2.0]), u(0.05, -0.18, -0.98))),
                 (10.0, -8.0, 10.0), (-4.0, 16.0, -6.0), (0.4, -1.6, -2.4), ("l", -2.6, 0.0, 1.6),
                 "1.20 s one-shot; the pole raised by 0.28, hang to 0.40 (telegraph), reach past the target and hook at "
                 "t=0.50 s = tick 10 (CaptainSpecial.HOOK_PULL), yanked back to the body by 0.70", bite=0.70)


CLIPS = {"CAPTAIN_HALBERD_STANCE": stance, "CAPTAIN_HALBERD_SWING_A": swing_a, "CAPTAIN_HALBERD_SWING_B": swing_b,
         "CAPTAIN_HALBERD_SWEEP": halberd_sweep, "CAPTAIN_BRACE_CHARGE": brace_charge, "CAPTAIN_HOOK_PULL": hook_pull}

KEYPOSES = {
    "CAPTAIN_HALBERD_STANCE": [(0.0, "low guard")],
    "CAPTAIN_HALBERD_SWING_A": [(0.20, "drawn back"), (0.35, "thrust"), (0.43, "extended")],
    "CAPTAIN_HALBERD_SWING_B": [(0.20, "wind-up"), (0.35, "contact"), (0.43, "carried round")],
    "CAPTAIN_HALBERD_SWEEP": [(0.45, "coil (hang)"), (0.60, "contact"), (0.74, "follow-through")],
    "CAPTAIN_BRACE_CHARGE": [(0.10, "braced"), (0.20, "point up")],
    "CAPTAIN_HOOK_PULL": [(0.36, "raised (hang)"), (0.50, "hook"), (0.70, "yank back")],
}

if __name__ == "__main__":
    kit.main(CLIPS, "halberd", keyposes=KEYPOSES)
