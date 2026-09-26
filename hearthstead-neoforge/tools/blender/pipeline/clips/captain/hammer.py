"""Hero Captain -- WARHAMMER v2 (plan/captain/ANIM-REFERENCES.md), battle-roles lane 2026-09-26.

    blender -b --factory-startup --python hammer.py -- [--keys | --three] [--no-export] CONST ...

The slowest weapon (WeaponClass.WARHAMMER: 24-tick swing, contact tick 12). The heavy grammar of
axe.py with the warhammer geometry: a long lift, a clear hang with the head behind the back, the
hips fire, the head is driven down and the body follows onto the front knee.

CAPTAIN_HAMMER_STANCE   2.40 s loop  the head low and forward, near the ground, both hands on the haft
CAPTAIN_HAMMER_SWING_A  1.20 s       over the right shoulder, overhead crush, contact 0.60 (tick 12)
CAPTAIN_HAMMER_SWING_B  1.20 s       drawn back right, flat side swing, contact 0.60 (tick 12)
CAPTAIN_SHIELD_BREAKER  1.20 s       short high cock, hooking blow onto the shield rim, contact 0.70 (tick 14)
CAPTAIN_GROUND_SLAM     1.50 s       straight up, long hang, the head into the ground, contact 0.90 (tick 18)
CAPTAIN_CRUSHING_BLOW   1.60 s       the longest hang, a full-body diagonal, contact 1.00 (tick 20)
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
axe.ITEM = "warhammer"
axe.STANCE = dict(axe.STANCE, gl_s=kit.HAM_GRIP2_S, sp_x=10.0)
axe.HAND = np.array([-2.2, 10.4, -8.0])
axe.DIR = u(0.18, 0.62, -0.76)                 # the head low and forward, near the ground


def stance():
    L, loop, solve, _, keep, more = axe.stance()
    return L, loop, solve, "2.40 s loop: warhammer head low and forward, weight settled, breathing", keep, more


def _clip(L, HIT, lift, hang, poses, yaw, lean, drop, step, text, bite=None):
    _, _, keep = axe.heavy(L, HIT, lift, hang, *poses, yaw, lean, drop, step, bite=bite)
    return L, False, axe.solve(L, False), text, keep, None


def swing_a():
    return _clip(1.20, 0.60, 0.34, 0.46,
                 ((np.array([-6.0, -2.8, 1.6]), u(-0.15, -0.35, 0.92)), (np.array([-4.4, 0.2, -7.6]), u(0.08, -0.82, -0.57)),
                  (np.array([-1.8, 8.4, -11.4]), u(0.20, 0.55, -0.81)), (np.array([-0.8, 12.4, -9.6]), u(0.25, 0.88, -0.40))),
                 (24.0, -8.0, -14.0), (12.0, 22.0, 28.0), (0.6, -2.6, -3.4), ("l", -3.4, -0.2, 2.0),
                 "1.20 s one-shot; over the right shoulder by 0.34, hang to 0.46, overhead crush, contact t=0.60 s = "
                 "tick 12 (WeaponClass.WARHAMMER), the head buried low")


def swing_b():
    return _clip(1.20, 0.60, 0.34, 0.46,
                 ((np.array([-7.6, 6.6, 2.6]), u(-0.80, -0.30, 0.52)), (np.array([-6.6, 7.2, -6.8]), u(-0.70, -0.05, -0.71)),
                  (np.array([-1.6, 7.8, -11.8]), u(0.15, 0.10, -0.98)), (np.array([4.0, 8.8, -9.6]), u(0.86, 0.20, -0.47))),
                 (34.0, -6.0, -30.0), (2.0, 10.0, 14.0), (-0.4, -2.0, -2.6), ("l", -2.4, 1.0, 1.6),
                 "1.20 s one-shot (variant): drawn back right, a flat side swing from the hips, contact t=0.60 s = tick 12")


def shield_breaker():
    return _clip(1.20, 0.70, 0.44, 0.60,
                 ((np.array([-6.8, -0.8, 0.4]), u(-0.35, -0.55, 0.76)), (np.array([-4.6, 2.0, -8.4]), u(0.00, -0.55, -0.83)),
                  (np.array([-2.0, 7.6, -11.8]), u(0.10, 0.62, -0.78)), (np.array([-1.2, 10.8, -10.4]), u(0.15, 0.85, -0.50))),
                 (22.0, -8.0, -12.0), (8.0, 20.0, 24.0), (0.3, -2.4, -3.0), ("l", -2.8, -0.2, 1.8),
                 "1.20 s one-shot; a short high cock by 0.44, hang to 0.60 (telegraph), the head hooks down onto the "
                 "shield rim, contact t=0.70 s = tick 14 (CaptainSpecial.SHIELD_BREAKER)")


def ground_slam():
    return _clip(1.50, 0.90, 0.56, 0.80,
                 ((np.array([-2.6, -5.6, 2.4]), u(0.04, -0.24, 0.97)), (np.array([-2.4, -1.8, -8.4]), u(0.04, -0.88, -0.47)),
                  (np.array([-1.8, 11.0, -11.4]), u(0.04, 0.80, -0.60)), (np.array([-1.6, 13.6, -9.8]), u(0.04, 0.95, -0.30))),
                 (6.0, -2.0, -2.0), (18.0, 30.0, 36.0), (1.0, -3.8, -4.4), ("l", -3.8, -0.2, 3.0),
                 "1.50 s one-shot; straight up over the head by 0.56, the lead knee lifts, the long hang to 0.80 "
                 "(telegraph), the head driven into the ground with a stomp, contact t=0.90 s = tick 18 "
                 "(CaptainSpecial.GROUND_SLAM)", bite=1.00)


def crushing_blow():
    return _clip(1.60, 1.00, 0.60, 0.88,
                 ((np.array([-7.0, -3.4, 2.8]), u(-0.30, -0.35, 0.89)), (np.array([-5.0, 0.6, -8.2]), u(0.10, -0.78, -0.62)),
                  (np.array([-1.6, 8.6, -12.0]), u(0.25, 0.50, -0.83)), (np.array([0.4, 12.8, -10.0]), u(0.35, 0.85, -0.40))),
                 (34.0, -12.0, -22.0), (14.0, 24.0, 32.0), (0.8, -3.0, -3.8), ("l", -4.0, -0.4, 2.2),
                 "1.60 s one-shot; the longest hang 0.60-0.88 (telegraph), a full-body diagonal, contact t=1.00 s = "
                 "tick 20 (CaptainSpecial.CRUSHING_BLOW)", bite=1.12)


CLIPS = {"CAPTAIN_HAMMER_STANCE": stance, "CAPTAIN_HAMMER_SWING_A": swing_a, "CAPTAIN_HAMMER_SWING_B": swing_b,
         "CAPTAIN_SHIELD_BREAKER": shield_breaker, "CAPTAIN_GROUND_SLAM": ground_slam,
         "CAPTAIN_CRUSHING_BLOW": crushing_blow}

KEYPOSES = {
    "CAPTAIN_HAMMER_STANCE": [(0.0, "ready")],
    "CAPTAIN_HAMMER_SWING_A": [(0.40, "wind-up (hang)"), (0.60, "contact"), (0.70, "buried")],
    "CAPTAIN_HAMMER_SWING_B": [(0.40, "wind-up (hang)"), (0.60, "contact"), (0.70, "carried round")],
    "CAPTAIN_SHIELD_BREAKER": [(0.52, "cocked (hang)"), (0.70, "contact"), (0.80, "follow-through")],
    "CAPTAIN_GROUND_SLAM": [(0.70, "raised, knee up (hang)"), (0.90, "slam"), (1.00, "buried")],
    "CAPTAIN_CRUSHING_BLOW": [(0.76, "wind-up (hang)"), (1.00, "contact"), (1.12, "follow-through")],
}

if __name__ == "__main__":
    kit.main(CLIPS, "hammer", keyposes=KEYPOSES)
