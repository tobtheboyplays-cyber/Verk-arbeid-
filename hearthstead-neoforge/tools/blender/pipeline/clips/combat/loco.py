"""Combat locomotion: GUARD_WALK (1.0 s), WALK_HURRIED (0.7 s), RUN_PANIC (0.6 s). Loops, lengths kept.

    blender -b --factory-startup --python guard_walk.py -- [--fast|--full] [--no-export]

GUARD_WALK exports ONLY root, legs, shins and cloak (GUARD_PATROL owns the
upper body); its preview shows the patrol hold on top, like the game does.
WALK_HURRIED / RUN_PANIC are full-body. All are distance-clocked by the
engine; hearthstead_meta.blocks_per_cycle gives the stride.
RUN_PANIC keeps its 0.60 s cycle so SettlerPanicGoal's tick-3 vocal accent
(t = 0.15 s) still lands on the first right-foot push-off.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import bpy  # noqa: E402

import combatkit as ck  # noqa: E402
import gait  # noqa: E402
import hsrig  # noqa: E402

STYLES = {
    # Martial march: compact stride, each heel plants flat, hips rock ~1 deg over
    # the stance leg; the upper body is the patrol hold.
    "GUARD_WALK": dict(length=1.0, excursion=11.0, duty=0.6, lift=2.2, drop=0.8, base=-0.35,
                       sway=0.3, roll=1.2, pelvis_yaw=2.0, spine_yaw=0.0, lean=4.0, lean_dip=0.8,
                       arm=0.0, elbow=(-20.0, -20.0), arm_out=3.0, head_up=0.0, cloak=4.0,
                       bones=["root", "right_leg", "left_leg", "right_shin", "left_shin", "cloak"],
                       upper="patrol", tex="settler_guard.png", item="sword"),
    # Brisk urgent walk: longer stride, forward lean, pumping bent arms.
    "WALK_HURRIED": dict(length=0.7, excursion=14.0, duty=0.56, lift=3.2, drop=1.3, base=-0.6,
                         sway=0.3, roll=1.0, pelvis_yaw=5.0, spine_yaw=6.0, lean=10.0, lean_dip=1.8,
                         arm=30.0, elbow=(-40.0, -70.0), arm_out=5.0, head_up=-4.0, cloak=8.0,
                         bones=None, upper=None, tex="settler_guard.png", item=None),
    # Flight: a real run (flight phase), chest pitched forward, hands thrown up
    # high and wide in front of the face -- fear, not form.
    "RUN_PANIC": dict(length=0.6, excursion=12.5, duty=0.40, lift=4.2, drop=1.2, base=-0.7, flight=0.7,
                      sway=0.25, roll=1.5, pelvis_yaw=6.0, spine_yaw=9.0, lean=14.0, lean_dip=2.2,
                      arm=42.0, arm_base=-38.0, elbow=(-60.0, -95.0), arm_out=14.0, head_up=-9.0, cloak=14.0,
                      bones=None, upper=None, tex="settler_farmer.png", item=None),
}


def run(const):
    args = hsrig.parse_args()
    st = STYLES[const]
    L = st["length"]
    base, B = ck.stance_base()
    objs = ck.build(st["tex"], right=st["item"])
    gait.key_gait(st)
    upper = None
    if st["upper"] == "patrol":
        def upper(t, ch):
            for b in ("right_arm", "right_forearm", "left_arm", "left_forearm"):
                ch[b] = base[b]
            ch["torso"] = {"rot": (5.0, 2.0, 0.0)}
            ch["head"] = {"rot": (-2.0, -2.0, 0.0)}
    solve = gait.make_solve(st, upper)
    times, samples = hsrig.bake(solve, L, objs)
    checks = gait.check(st, times, samples)
    checks.update({"clip": const, "length": L, "loop": True})
    print("CHECKS", const, checks)
    if args["export"]:
        ck.export(const, L, True, times, samples, bones=st["bones"],
                  meta={"source": "tools/blender/pipeline/clips/combat/loco.py (Blender "
                                  + bpy.app.version_string + ")",
                        "blocks_per_cycle": checks["blocks_per_cycle"],
                        "contract": f"{L} s loop, distance-clocked",
                        "checks": checks},
                  out_dir=ck.out_dir(const.lower()))
    ck.write_report(const.lower(), checks)
    ck.preview(const.lower(), L, args, loop=True, step=1)
