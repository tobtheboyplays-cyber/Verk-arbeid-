#!/usr/bin/env python3
"""Bend retrofit: every remaining legacy settler clip, re-issued as bedrock
JSON with natural elbows.

For each arm key the elbow flexes according to where the arm is (relaxed at
the side, working bend in front, softer again overhead) and the shoulder
pitch is corrected so the HAND keeps its legacy direction -- so the hand path,
every key time and every sound-contact beat stay exactly as authored in
SettlerAnimations.java, while the silhouette loses the straight broomstick
arms. All other channels are copied verbatim (same values, same LINEAR /
CATMULLROM modes).

These JSON files are starting points: once written they are the source of
truth (edit them in Blockbench). The script skips existing files unless
--force.

usage: python tools/motion/retrofit_bends.py [--force] [CONST ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clipkit import channel, elbow_offset, legacy_defs, legacy_meta, write_clip  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(os.path.dirname(HERE)),
                   "src/main/resources/assets/hearthstead/animations/settler")

RETROFIT = [
    # trade idles
    "IDLE_FARMER", "IDLE_LUMBERER", "IDLE_COURIER", "IDLE_TRADER", "IDLE_FORGE", "IDLE_BAKER",
    "IDLE_COOK", "IDLE_SIGHT_EDGE", "IDLE_FLETCHER", "IDLE_MINER", "IDLE_SCHOLAR", "IDLE_INNKEEPER",
    "IDLE_WEAVER", "IDLE_BLADE_BENCH", "IDLE_FISHER", "IDLE_SENTRY",
    # bench and field work
    "CLEAVE", "STOKE", "SAW", "FINE_WORK", "OVEN_TEND", "SOW_BROADCAST", "CARPENTER_PLANE",
    "MASON_CHISEL", "FLETCHER_FLETCH", "TANNER_SCRAPE", "FARM_WATER", "LIMB_BRANCHES",
    "LUMBER_CRAFT", "CRAFT_OUTPUT_STORE", "FISHER_CAST", "COURIER_SORT",
    # carry holds
    "HAUL_LOG", "HAUL_LOG_HEAVY", "FARMER_CARRY", "COURIER_CARRY_GRIP", "WALK_CARRY_ITEM",
    # life and social
    "EAT", "REST", "CELEBRATE", "WAKE_STRETCH", "INN_WELCOME", "VILLAGE_CHAT", "VILLAGE_LISTEN",
    "BARD_PLAY", "BLESSING_RECEIVE", "CLIMB_LADDER", "GUARD_HIT_REACT",
]


def elbow_for(pitch):
    """Natural elbow flexion (negative degrees) for an upper-arm pitch."""
    a = pitch
    if a >= 0:
        return -10.0
    if a > -40:
        return -10.0 - 18.0 * (-a / 40.0)
    if a > -110:
        return -28.0 - 27.0 * ((-a - 40.0) / 70.0)
    return -55.0 + 20.0 * min(1.0, (-a - 110.0) / 60.0)


def retrofit(const):
    d = legacy_defs()[const]
    names = {"ROTATION": "rotation", "POSITION": "position", "SCALE": "scale"}
    bones = {}
    for bone, target, frames in d["channels"]:
        keys = [(t, [x, y, z], "cat" if interp == "CATMULLROM" else "lin")
                for (t, _k, (x, y, z), interp) in frames]
        if bone in ("right_arm", "left_arm") and target == "ROTATION":
            arm, fore = [], []
            for t, (x, y, z), ease in keys:
                e = elbow_for(x)
                arm.append((t, [x + elbow_offset(e), y, z], ease))
                fore.append((t, [e, 0, 0], ease))
            bones.setdefault(bone, {})["rotation"] = channel(arm)
            bones.setdefault(bone.replace("_arm", "_forearm"), {})["rotation"] = channel(fore)
        else:
            bones.setdefault(bone, {})[names[target]] = channel(keys)
    return d["length"], d["looping"], bones


def main():
    force = "--force" in sys.argv
    wanted = [a.upper() for a in sys.argv[1:] if not a.startswith("--")] or RETROFIT
    for const in wanted:
        key = const.lower()
        path = os.path.join(OUT, f"{key}.animation.json")
        if os.path.exists(path) and not force:
            print(f"skip {key}: exists")
            continue
        length, loop, bones = retrofit(const)
        write_clip(OUT, key, length, loop, bones,
                   note=f"Bend retrofit of SettlerAnimations.{const}: same key times and contact beats, "
                        "elbows added with the hand direction preserved. Edit freely in Blockbench.")
        print("wrote", key)


if __name__ == "__main__":
    main()
