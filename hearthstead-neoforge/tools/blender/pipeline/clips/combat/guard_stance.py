"""GUARD_STANCE -- the guard at rest, sword ready. 4.0 s loop (length/loop kept).

    blender -b --factory-startup --python guard_stance.py -- [--fast|--full] [--no-export]

Absolute clip (SettlerModel plays it on the reset pose). A trained swordsman:
left foot leads, sword-side foot back, knees soft (IK: hips 0.75 px down),
hips bladed 7 deg to the right, chest 5 deg forward, blade forward-up at the
opponent's chest, free fist in front of the belt. One 4 s breath (rise 2.2 s,
fall 1.8 s), a slow weight shift onto the front foot and back, ONE deliberate
head scan to the settler's left, arms drift < 1.5 deg. The moveset's additive
clips are computed against STANCE_BASE (combatkit), which is this clip's
t = 0 rest pose, so strike contacts land where they were authored.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import bpy  # noqa: E402

NAME, L, LOOP = "GUARD_STANCE", 4.0, True
args = hsrig.parse_args()
base, B = ck.stance_base()
objs = ck.build("settler_guard.png", right="sword")

S = ck.SMO
K = {
    # breath: chest rises 2.2 s, falls 1.8 s
    "sp_lift": [(0.0, 0.0, *S), (2.2, 0.32, *S), (4.0, 0.0)],
    "sp_x": [(0.0, B["sp_x"], *S), (2.2, B["sp_x"] - 0.8, *S), (4.0, B["sp_x"])],
    "sp_y": [(0.0, B["sp_y"], *S), (1.2, B["sp_y"] + 0.8, *S), (3.0, B["sp_y"] - 0.8, *S), (4.0, B["sp_y"])],
    "sp_z": [(0.0, 0.0, *S), (1.6, -0.6, *S), (3.2, 0.3, *S), (4.0, 0.0)],
    # weight settles onto the lead (left) foot, then back -- knees absorb it
    "hip_x": [(0.0, B["hip_x"], *S), (1.6, B["hip_x"] + 0.35, *S), (3.2, B["hip_x"] - 0.1, *S), (4.0, B["hip_x"])],
    "hip_y": [(0.0, B["hip_y"], *S), (1.6, B["hip_y"] - 0.12, *S), (3.2, B["hip_y"] + 0.05, *S), (4.0, B["hip_y"])],
    "hip_z": [(0.0, B["hip_z"]), (4.0, B["hip_z"])],
    "hip_yaw": [(0.0, B["hip_yaw"], *S), (1.6, B["hip_yaw"] - 0.6, *S), (3.2, B["hip_yaw"] + 0.4, *S), (4.0, B["hip_yaw"])],
    "hip_r": [(0.0, 0.0, *S), (1.6, -0.5, *S), (3.2, 0.2, *S), (4.0, 0.0)],
    # one slow scan to the settler's left and back, with a small settle
    "hd_y": [(0.0, B["hd_y"], *S), (0.9, B["hd_y"] + 1.0, *S), (1.8, B["hd_y"], *S),
             (2.6, B["hd_y"] - 17.0, *S), (3.1, B["hd_y"] - 15.5, *S), (3.6, B["hd_y"] - 1.2, *S),
             (4.0, B["hd_y"])],
    "hd_x": [(0.0, B["hd_x"], *S), (2.2, B["hd_x"] - 0.8, *S), (2.8, B["hd_x"] + 0.6, *S), (4.0, B["hd_x"])],
    "ck_x": [(0.0, B["ck_x"], *S), (2.4, B["ck_x"] + 1.4, *S), (4.0, B["ck_x"])],
    "ck_z": [(0.0, 0.0, *S), (1.8, 0.8, *S), (3.4, -0.8, *S), (4.0, 0.0)],
}
# arms: rest solution + breath-coupled drift (sword tip rises ~1 px with the chest)
for p in ("ar_x", "ar_y", "ar_z", "el_r", "tw_r", "al_x", "al_y", "al_z", "el_l", "tw_l"):
    v = B[p]
    d = {"ar_x": -1.0, "el_r": 0.6, "al_x": -1.2, "el_l": 1.0, "ar_z": 0.4, "al_z": -0.5}.get(p, 0.0)
    K[p] = [(0.0, v, *S), (2.2, v + d, *S), (4.0, v)]
ck.key_all(K, L, LOOP)
solve = ck.make_solve(L, LOOP, cloak_drag=False)
times, samples = hsrig.bake(solve, L, objs)

err = max(ck.sole_error(s, ck.FOOT_R, ck.FOOT_L) for s in samples)
checks = {"sole_max_error_px": round(err, 3), "stance_base": {b: v for b, v in base.items()}}
checks.update(ck.ground_report(samples))
print("CHECKS", NAME, checks["sole_max_error_px"], ck.ground_report(samples))
if args["export"]:
    ck.export(NAME, L, LOOP, times, samples,
              meta={"source": "tools/blender/pipeline/clips/combat/guard_stance.py (Blender "
                              + bpy.app.version_string + ")",
                    "contract": "4.0 s loop; absolute base for the additive guard moveset"},
              out_dir=ck.out_dir(NAME.lower()))
ck.write_report(NAME.lower(), checks)
ck.preview(NAME.lower(), L, args, loop=LOOP)
