"""Bake + check + export + preview one guard-moveset clip (see moves.py).

Used by the thin per-clip scripts (melee.py, guard_light_slash_b.py, ...):
    blender -b --factory-startup --python melee.py -- [--fast|--full] [--no-export]
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import bpy  # noqa: E402
import numpy as np  # noqa: E402

import combatkit as ck  # noqa: E402
import hsrig  # noqa: E402
import mcrig  # noqa: E402
import moves  # noqa: E402

CLIPS = {
    # const: (setup, additive, right item, left item)
    "MELEE": ("melee", True, "sword", None),
    "GUARD_LIGHT_SLASH_B": ("light_b", True, "sword", None),
    "GUARD_FINISHER_DRIVE": ("finisher", True, "sword", None),
    "GUARD_HEAVY_OVERHEAD": ("heavy", True, "sword", None),
    "GUARD_SHIELD_BASH": ("bash", True, "sword", "shield"),
    "GUARD_STAGGER": ("stagger", False, "sword", None),
    "GUARD_HIT_REACT": ("hit_react", False, "sword", None),
}


def run(const):
    args = hsrig.parse_args()
    setup, additive, ritem, litem = CLIPS[const]
    base, B = ck.stance_base()
    objs = ck.build("settler_guard.png", right=ritem, left=litem)
    if setup == "light_b":
        moves.setup_melee(B)
        start = moves.snapshot(0.30)
        L, loop, keep, contract, hit, log = moves.setup_light_b(B, start)
    else:
        L, loop, keep, contract, hit, log = getattr(moves, "setup_" + setup)(B)
    solve = ck.make_solve(L, loop)
    times, samples = hsrig.bake(solve, L, objs)

    # ---- checks
    tips, sole = [], 0.0
    for s in samples:
        w = mcrig.pose_matrices(s)
        tips.append(ck.sword_points(w)[2])
        sole = max(sole, ck.sole_error(s, ck.FOOT_R, ck.FOOT_L))
    sp = ck.world_speed(tips)
    checks = {"clip": const, "length": L, "loop": loop, "contract": contract,
              "sole_max_error_px": round(sole, 3), "goal_solves": log}
    checks.update(ck.ground_report(samples))
    if hit is not None:
        f = int(round(hit * ck.FPS))
        pre = sp[:f]
        checks.update({
            "contact_t": hit,
            "tip_speed_peak_t": round(float(np.argmax(sp)) / ck.FPS, 3),
            "tip_speed_into_contact_px_s": round(float(sp[f - 1]), 1),
            "tip_speed_peak_before_contact_px_s": round(float(pre.max()), 1),
            "tip_speed_hitstop_px_s": round(float(sp[f + 1:f + 3].mean()), 1),
            "tip_at_contact_model_px": [round(float(v), 2) for v in tips[f]],
        })
        if const == "GUARD_SHIELD_BASH":
            sc = [ck.shield_centre(mcrig.pose_matrices(s))[0] for s in samples]
            ssp = ck.world_speed(sc)
            checks.update({"shield_speed_peak_t": round(float(np.argmax(ssp)) / ck.FPS, 3),
                           "shield_centre_at_contact": [round(float(v), 2) for v in sc[f]]})
    # additive clips must start and end on the stance exactly
    def off(s):
        return max(abs(a - b) for bone in base for kind in ("rot", "pos")
                   for a, b in zip(s.get(bone, {}).get(kind, (0, 0, 0)), base[bone].get(kind, (0, 0, 0))))
    def worst(s):
        best = (0.0, None)
        for bone in base:
            for kind in ("rot", "pos"):
                for a, b in zip(s.get(bone, {}).get(kind, (0, 0, 0)), base[bone].get(kind, (0, 0, 0))):
                    if abs(a - b) > best[0]:
                        best = (abs(a - b), bone + "." + kind)
        return best
    checks["end_worst_bone"] = worst(samples[-1])[1]
    checks["start_offset_from_stance"] = round(off(samples[0]), 4)
    checks["end_offset_from_stance"] = round(off(samples[-1]), 4)
    # snap first/last to the stance exactly (solver noise < 0.05 deg)
    if checks["end_offset_from_stance"] < 0.5:
        samples[-1] = base
    if setup != "light_b" and checks["start_offset_from_stance"] < 0.5:
        samples[0] = base
    print("CHECKS", json.dumps({k: v for k, v in checks.items() if k != "goal_solves"}))
    if args["export"]:
        doc, err = ck.export(const, L, loop, times, samples, base=base if additive else None,
                             keep_times=keep,
                             meta={"source": "tools/blender/pipeline/clips/combat/moves.py via strike_runner.py (Blender "
                                             + bpy.app.version_string + ")",
                                   "contract": contract,
                                   "grammar": ("additive over GUARD_STANCE (stance JSON t=0 rest pose)"
                                               if additive else "absolute full-body hold"),
                                   "checks": {k: v for k, v in checks.items() if k != "goal_solves"}},
                             out_dir=ck.out_dir(const.lower()))
        checks["roundtrip_max_err"] = round(err, 4)
    ck.write_report(const.lower(), checks)
    ck.preview(const.lower(), L, args, loop=False)
    return checks
