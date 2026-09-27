"""Print authored local shoulder/forearm rotations for runtime mapping."""
import json
import math
import bpy

TICKS = (0, 4, 7, 8, 9, 10, 11, 12, 13, 18, 24, 29, 30, 31, 32, 33,
         34, 35, 36, 37, 42, 43, 44, 45, 46, 47, 48, 56, 64, 72, 80)
NAMES = ("bag_left_upper_arm", "bag_left_forearm",
         "bag_right_upper_arm", "bag_right_forearm")
result = {}
for name in NAMES:
    obj = bpy.data.objects[name]
    rows = []
    for tick in TICKS:
        bpy.context.scene.frame_set(1 + tick)
        bpy.context.view_layer.update()
        euler = obj.rotation_quaternion.to_euler("XYZ")
        rows.append([tick, *[round(math.degrees(v), 3) for v in euler]])
    result[name] = rows
print("HEARTHSTEAD_CHANNELS=" + json.dumps(result, separators=(",", ":")))
