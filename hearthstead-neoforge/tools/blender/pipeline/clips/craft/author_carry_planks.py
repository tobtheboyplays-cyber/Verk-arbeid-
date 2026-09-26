"""CARRY_PLANKS (Builder lane): a bundle of planks carried to the site. New clip.

    blender -b --factory-startup --python author_carry_planks.py -- [--fast|--full] [--no-export]

Contract: animation.settler.carry_planks, 1.20 s (24 ticks), looping WALK
OVERLAY: only torso, head, arms and cloak are exported (legs and root are
stripped after export) so the laden walk still drives the feet. The load is
heavy: forearms under the bundle at chest height, the torso leans BACK
against the weight, and just after every footfall (0.00 / 0.60 s) the
bundle and elbows sink ~1 px and recover -- the weight is visible.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import craftkit as ck  # noqa: E402

LENGTH = 1.2
TEX = "settler_builder.png" if os.path.exists(os.path.join(ck.TEX_DIR, "settler_builder.png")) \
    else "settler_carpenter.png"

clip = ck.Clip("CARRY_PLANKS", LENGTH, True, TEX,
               feet=((-2.6, 24.0, 0.0), (2.6, 24.0, 0.0)), knee_out=0.1)
clip.keys({
    "root_y": [(0.0, 0.0)], "root_x": [(0.0, 0.0)], "root_z": [(0.0, 0.0)], "root_yaw": [(0.0, 0.0)],
    "torso_x": [(0.0, -7.0), (0.10, -5.0, "out"), (0.35, -7.5, "inout"), (0.60, -7.0),
                (0.70, -5.0, "out"), (0.95, -7.5, "inout")],
    "torso_y": [(0.0, 2.0), (0.60, -2.0, "inout")],
    "torso_z": [(0.0, 1.5), (0.60, -1.5, "inout")],
    "head_nod": [(0.0, -4.0), (0.10, -2.5, "out"), (0.40, -4.0, "inout"), (0.70, -2.5, "out"),
                 (1.00, -4.0, "inout")],
    "head_yaw": [(0.0, 0.0)], "head_roll": [(0.0, 0.0)],
    "cloak_add": [(0.0, 0.0), (0.30, 2.5, "inout"), (0.60, 0.0, "inout"), (0.90, 2.5, "inout")],
})
clip.key_vec("look", [(0.0, (0.0, 4.0, -40.0))])
# bundle underside at chest height; sinks 1 px just after each footfall
HANDS = ((-4.2, 6.0, -6.5), (4.2, 6.0, -6.5))
for side, (x, y, z) in (("right", HANDS[0]), ("left", HANDS[1])):
    clip.arm_goals(side, [
        (0.00, (x, y, z), "out"), (0.12, (x, y + 1.1, z + 0.2), "inout"), (0.40, (x, y - 0.2, z), "inout"),
        (0.60, (x, y, z), "out"), (0.72, (x, y + 1.1, z + 0.2), "inout"), (1.00, (x, y - 0.2, z), "inout"),
    ], (-0.8 if side == "right" else 0.8, 0.5, 0.3))
clip.prop("bundle", (-6, 0, -12), (12, 5, 10), (0.66, 0.50, 0.30, 1))
clip.hand_props = [{"hand": "mainhand", "item": "minecraft:oak_planks", "from": 0.0, "to": LENGTH}]
clip.run(keep=(0.12, 0.72),
         meta={"contract": "CARRY_PLANKS 1.20 s walk overlay (24 ticks); arms/torso/head only; "
                           "load sinks after each footfall (0.00/0.60 s)"})

# Overlay: strip every channel the laden walk owns.
path = os.path.join(ck.ANIM_DIR, "carry_planks.animation.json")
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    for anim in data.get("animations", {}).values():
        for bone in ("root", "right_leg", "left_leg", "right_shin", "left_shin"):
            anim.get("bones", {}).pop(bone, None)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print("CARRY_PLANKS: stripped legs/root for the walk overlay")
