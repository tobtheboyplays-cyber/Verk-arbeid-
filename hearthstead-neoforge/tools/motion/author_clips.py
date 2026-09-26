#!/usr/bin/env python3
"""Initial authoring of the re-made settler clips (Better Combat-style:
bending elbows/knees, eased anticipation -> impact -> follow-through ->
settle), written as Blockbench/GeckoLib bedrock JSON into
src/main/resources/assets/hearthstead/animations/settler/.

THE JSON FILES ARE THE SOURCE OF TRUTH once written. This script only
produced the first versions; it refuses to overwrite a clip that exists
unless --force is given, so Blockbench edits are never clobbered.

Every clip keeps the legacy clip's length and its sound-contact beat
(see WorkSoundSync / Employment.soundContactOf / SettlerAnimations header):
  mine_pick     0.95 s loop, strike 0.45 s (tick 9 of 19)
  hammer_anvil  1.00 s loop, strike 0.45 s (tick 9 of 20)
  farm_till     1.50 s loop, blade bite 0.60 s (tick 12 of 30)
  farm_harvest  1.80 s loop, grab 0.45 s (tick 9), stow 0.90 s (tick 18)
  melee         0.50 s one-shot, blade contact 0.20 s (tick 4)
  knead         1.20 s loop, palms bottom out 0.45 s (tick 9 of 24)
  cook_stir     1.50 s loop, pot accent 1.20 s (tick 24 of 30)
  herder_shear  1.00 s loop, snip 0.45 s (tick 9 of 20)
  fine_work     0.90 s loop, deeper pass 0.45 s (tick 9 of 18)
  oven_tend     1.60 s loop, peel push 0.25 s (tick 5 of 32)
Clips owned by other agents (chop, walk, walk_laden, bag clips) are not
authored here.

usage: python tools/motion/author_clips.py [--force] [clip ...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clipkit import merge, pos, rot, scale, stance_keys, write_clip  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(os.path.dirname(HERE)),
                   "src/main/resources/assets/hearthstead/animations/settler")

CLIPS = {}


def clip(fn):
    CLIPS[fn.__name__] = fn
    return fn


# ------------------------------------------------------------- miner ---

@clip
def mine_pick():
    L = 0.95
    legs = stance_keys([(0, 0.5, "io"), (0.15, 0.8, "io"), (0.3, 0.0, "outq"), (0.36, 0.1, "io"),
                        (0.45, 1.7, "inc"), (0.52, 1.5, "outq"), (0.65, 1.4, "io"),
                        (0.8, 0.4, "io"), (L, 0.5, "io")],
                       right_foot_z=-2.6, left_foot_z=2.4, right_splay=3, left_splay=-3)
    bones = merge(legs, {
        "right_arm": rot([(0, [-40, -12, -6], "io"), (0.15, [-78, -11, -7], "io"),
                          (0.3, [-166, -8, -9], "outq"), (0.36, [-160, -8, -9], "io"),
                          (0.45, [-55, -13, -5], "inc"), (0.52, [-60, -13, -5], "outq"),
                          (0.65, [-52, -13, -5], "io"), (0.8, [-30, -12, -6], "io"),
                          (L, [-40, -12, -6], "io")]),
        "right_forearm": rot([(0, [-30, 0, 0], "io"), (0.15, [-50, 0, 0], "io"),
                              (0.3, [-52, 0, 0], "outq"), (0.36, [-46, 0, 0], "io"),
                              (0.45, [-8, 0, 0], "inc"), (0.52, [-16, 0, 0], "outq"),
                              (0.65, [-22, 0, 0], "io"), (0.8, [-46, 0, 0], "io"),
                              (L, [-30, 0, 0], "io")]),
        "left_arm": rot([(0, [-48, 16, 6], "io"), (0.15, [-80, 14, 7], "io"),
                         (0.3, [-152, 11, 9], "outq"), (0.36, [-146, 11, 9], "io"),
                         (0.45, [-60, 17, 5], "inc"), (0.52, [-64, 17, 5], "outq"),
                         (0.65, [-57, 17, 5], "io"), (0.8, [-32, 16, 6], "io"),
                         (L, [-48, 16, 6], "io")]),
        "left_forearm": rot([(0, [-44, 0, 0], "io"), (0.15, [-62, 0, 0], "io"),
                             (0.3, [-58, 0, 0], "outq"), (0.36, [-52, 0, 0], "io"),
                             (0.45, [-14, 0, 0], "inc"), (0.52, [-20, 0, 0], "outq"),
                             (0.65, [-26, 0, 0], "io"), (0.8, [-50, 0, 0], "io"),
                             (L, [-44, 0, 0], "io")]),
        "torso": rot([(0, [12, 3, 0], "io"), (0.15, [4, 10, 0], "io"), (0.3, [-17, 16, 0], "outq"),
                      (0.36, [-15, 15, 0], "io"), (0.45, [38, -9, 0], "inc"), (0.52, [35, -8, 0], "outq"),
                      (0.65, [32, -7, 0], "io"), (0.8, [4, 8, 0], "io"), (L, [12, 3, 0], "io")]),
        "head": rot([(0, [18, 0, 0], "io"), (0.3, [6, 2, 0], "outq"), (0.45, [26, -2, 0], "inc"),
                     (0.6, [24, -2, 0], "io"), (0.8, [16, -6, 0], "io"), (L, [18, 0, 0], "io")]),
        "cloak": rot([(0, [4, 0, 0], "io"), (0.3, [-22, 0, 0], "io"), (0.47, [8, 0, 0], "inq"),
                      (0.58, [22, 0, 0], "outq"), (0.78, [6, 0, 0], "io"), (L, [4, 0, 0], "io")]),
    })
    return L, True, bones


import clips_core  # noqa: E402
CLIPS.update(clips_core.CLIPS)


def main():
    force = "--force" in sys.argv
    # Clips another lane took over: authored there (Blender pipeline / combat animator).
    owned_elsewhere = {"guard_stance", "guard_patrol", "melee"}
    wanted = [a for a in sys.argv[1:] if not a.startswith("--")] or [c for c in CLIPS if c not in owned_elsewhere]
    os.makedirs(OUT, exist_ok=True)
    for name in wanted:
        path = os.path.join(OUT, f"{name}.animation.json")
        if os.path.exists(path) and not force:
            print(f"skip {name}: exists (use --force to overwrite authored JSON)")
            continue
        length, loop, bones = CLIPS[name]()
        print("wrote", write_clip(OUT, name, length, loop, bones))


if __name__ == "__main__":
    main()
