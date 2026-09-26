"""Author and render Hearthstead's Lumberer crafting transaction in Blender.

This file is the editable animation source. It deliberately uses a simple
Minecraft-proportioned block rig and truthful recipe/table props instead of a
cinematic substitute. The emitted JSON contains the exact Java-space channel
keys consumed by SettlerAnimations; Blender-space rotations are derived from
those values for visual review.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import bpy
from mathutils import Vector


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
OUT = PROJECT / "qa" / "evidence" / "blender" / "lumber_craft_v1"
BLEND = HERE / "hearthstead_lumber_craft_v1.blend"
FPS = 20
END_FRAME = 49


JAVA_KEYS = {
    "right_arm": [
        (0.00, (0, 0, 0)), (0.10, (24, -10, 5)),
        (0.20, (58, -18, 8)), (0.30, (22, -6, 3)),
        (0.50, (28, 8, -4)), (0.60, (61, 15, -8)),
        (0.70, (24, 5, -2)), (0.85, (28, -2, 2)),
        (0.90, (56, 0, 3)), (1.05, (38, -4, 4)),
        (1.25, (-18, -12, 12)), (1.40, (-31, -8, 9)),
        (1.50, (61, -3, 4)), (1.65, (58, -2, 3)),
        (1.80, (35, -4, 3)), (2.15, (35, -5, 3)),
        (2.30, (15, -3, 2)), (2.40, (0, 0, 0)),
    ],
    "left_arm": [
        (0.00, (0, 0, 0)), (0.25, (22, 7, -3)),
        (0.40, (59, 18, -8)), (0.50, (24, 7, -3)),
        (0.70, (23, -8, 4)), (0.80, (61, -16, 8)),
        (0.90, (25, -5, 3)), (1.10, (46, 10, -5)),
        (1.40, (52, 11, -6)), (1.50, (55, 9, -5)),
        (1.80, (48, 7, -4)), (1.95, (59, 3, -5)),
        (2.15, (79, -29, -3)), (2.25, (37, -9, -12)),
        (2.35, (14, 6, -6)), (2.40, (0, 0, 0)),
    ],
    "torso": [
        (0.00, (0, 0, 0)), (0.15, (-8, -2, 0)),
        (0.40, (-12, 3, 0)), (0.60, (-13, -3, 0)),
        (0.80, (-14, 3, 0)), (0.95, (-16, 0, 0)),
        (1.30, (-10, -5, 0)), (1.40, (-12, -2, 0)),
        (1.50, (-23, 2, 0)), (1.70, (-20, 1, 0)),
        (1.95, (-21, 0, 0)), (2.15, (-24, 0, 0)),
        (2.30, (-9, 0, 0)), (2.40, (0, 0, 0)),
    ],
    "head": [
        (0.00, (0, 0, 0)), (0.20, (8, -8, 0)),
        (0.40, (9, 8, 0)), (0.60, (10, -7, 0)),
        (0.80, (10, 7, 0)), (1.10, (12, 0, 0)),
        (1.40, (7, -2, 0)), (1.50, (13, 1, 0)),
        (1.80, (10, 0, 0)), (2.15, (14, 0, 0)),
        (2.30, (6, 0, 0)), (2.40, (0, 0, 0)),
    ],
    "root": [
        (0.00, (0, 0, 0)), (0.20, (0, -0.25, 0)),
        (0.95, (0, -0.48, 0)), (1.30, (0, -0.18, 0)),
        (1.50, (0, -0.82, 0)), (1.75, (0, -0.55, 0)),
        (2.15, (0, -0.86, 0)), (2.30, (0, -0.18, 0)),
        (2.40, (0, 0, 0)),
    ],
    "cloak": [
        (0.00, (0, 0, 0)), (0.95, (3, 0, 0)),
        (1.30, (1, 0, 0)), (1.55, (6, 0, 0)),
        (1.80, (3, 0, 0)), (2.15, (6, 0, 0)),
        (2.40, (0, 0, 0)),
    ],
    "right_leg": [(0.00, (0, 0, 0)), (2.40, (0, 0, 0))],
    "left_leg": [(0.00, (0, 0, 0)), (2.40, (0, 0, 0))],
}


def material(name: str, colour: tuple[float, float, float, float], metallic=0.0,
             roughness=0.72):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = colour
    mat.use_nodes = True
    principled = mat.node_tree.nodes.get("Principled BSDF")
    principled.inputs["Base Color"].default_value = colour
    principled.inputs["Metallic"].default_value = metallic
    principled.inputs["Roughness"].default_value = roughness
    return mat


def cube(name, location, scale, mat, parent=None):
    bpy.ops.mesh.primitive_cube_add(location=location)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    if parent is not None:
        obj.parent = parent
        # Parent-owned body geometry is authored in local model space. Keeping
        # a copied world matrix here detached the head/limbs as soon as their
        # pivots animated, which a still frame can expose immediately.
        obj.location = location
    return obj


def empty(name, location, parent=None):
    obj = bpy.data.objects.new(name, None)
    bpy.context.collection.objects.link(obj)
    obj.empty_display_type = "PLAIN_AXES"
    obj.location = location
    if parent is not None:
        obj.parent = parent
    return obj


def look_at(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()


def set_visibility(obj, visible, frame):
    obj.hide_render = not visible
    obj.hide_viewport = not visible
    obj.keyframe_insert("hide_render", frame=frame)
    obj.keyframe_insert("hide_viewport", frame=frame)


def java_rotation_to_blender(bone: str, java_xyz):
    x, y, z = java_xyz
    if bone == "head":
        return (math.radians(x), math.radians(z), math.radians(-y))
    return (math.radians(-x), math.radians(z), math.radians(-y))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)

    forest = material("Lumberer green", (0.10, 0.27, 0.17, 1))
    linen = material("Linen", (0.62, 0.48, 0.31, 1))
    skin = material("Skin", (0.61, 0.35, 0.20, 1))
    leather = material("Leather", (0.19, 0.075, 0.025, 1))
    iron = material("Forged iron", (0.12, 0.14, 0.17, 1), 0.45)
    oak = material("Oak", (0.30, 0.15, 0.055, 1))
    oak_light = material("Oak highlight", (0.58, 0.33, 0.12, 1))
    ember = material("Craft contact", (0.95, 0.27, 0.06, 1), 0.05, 0.45)

    root = empty("root", (0, 0, 0))
    torso = empty("torso", (0, 0, 1.00), root)
    cube("body", (0, 0, 0.28), (0.31, 0.18, 0.38), forest, torso)
    cube("apron", (0, -0.195, 0.20), (0.25, 0.025, 0.28), leather, torso)

    head = empty("head", (0, 0, 0.72), torso)
    cube("head_mesh", (0, -0.01, 0.20), (0.255, 0.255, 0.255), skin, head)
    cube("hair", (0, 0.01, 0.48), (0.26, 0.25, 0.035), leather, head)
    cube("nose", (0, -0.275, 0.18), (0.045, 0.035, 0.065), skin, head)
    cube("left_eye", (-0.09, -0.274, 0.27), (0.026, 0.012, 0.026), iron, head)
    cube("right_eye", (0.09, -0.274, 0.27), (0.026, 0.012, 0.026), iron, head)

    right_arm = empty("right_arm", (-0.43, 0, 0.50), torso)
    left_arm = empty("left_arm", (0.43, 0, 0.50), torso)
    cube("right_sleeve", (0, 0, -0.23), (0.13, 0.13, 0.24), linen, right_arm)
    cube("right_forearm", (0, 0, -0.52), (0.115, 0.115, 0.19), skin, right_arm)
    cube("left_sleeve", (0, 0, -0.23), (0.13, 0.13, 0.24), linen, left_arm)
    cube("left_forearm", (0, 0, -0.52), (0.115, 0.115, 0.19), skin, left_arm)

    cloak = empty("cloak", (0, 0.20, 0.55), torso)
    cube("cloak_mesh", (0, 0.02, -0.10), (0.28, 0.035, 0.34), forest, cloak)
    right_leg = empty("right_leg", (-0.17, 0, 1.00), root)
    left_leg = empty("left_leg", (0.17, 0, 1.00), root)
    cube("right_leg_mesh", (0, 0, -0.43), (0.145, 0.16, 0.43), linen, right_leg)
    cube("left_leg_mesh", (0, 0, -0.43), (0.145, 0.16, 0.43), linen, left_leg)
    cube("right_boot", (0, -0.05, -0.81), (0.15, 0.22, 0.10), leather, right_leg)
    cube("left_boot", (0, -0.05, -0.81), (0.15, 0.22, 0.10), leather, left_leg)

    # Fixed world target. The front edge sits inside comfortable reach and the
    # 3x3 top grid makes every ingredient contact readable from the camera.
    cube("crafting_table", (0, -0.75, 0.52), (0.48, 0.48, 0.52), oak)
    for gx in (-0.27, 0.0, 0.27):
        for gy in (-1.02, -0.75, -0.48):
            cube(f"grid_{gx}_{gy}", (gx, gy, 1.045), (0.105, 0.105, 0.012), oak_light)

    ingredients = []
    # Exact minecraft:wooden_axe grid emitted by woodenAxeGrid(): PP_ / PS_ /
    # _S_. The worker stands on the +Y side, so row two is the near edge.
    slots = [(-0.27, -1.02), (0, -1.02), (-0.27, -0.75),
             (0, -0.75), (0, -0.48)]
    for index, (x, y) in enumerate(slots):
        narrow = index >= 3
        ingredients.append(cube(
            f"ingredient_{index + 1}", (x, y, 1.095),
            (0.035 if narrow else 0.10, 0.13 if narrow else 0.09, 0.025),
            oak_light if not narrow else oak))

    # One unmistakable, flat tabletop axe. Its parent owns the diagonal so
    # head and handle cannot drift apart. After pickup there is intentionally
    # no hand proxy: runtime puts the committed item in protected bag escrow.
    result_root = empty("result_axe_world", (0, -0.75, 1.115))
    result_root.rotation_euler.z = math.radians(-32)
    result_handle = cube("result_axe_handle_world", (0, 0.01, 0),
                         (0.025, 0.25, 0.025), oak, result_root)
    result_head = cube("result_axe_head_world", (0, -0.20, 0.015),
                       (0.15, 0.075, 0.04), oak_light, result_root)
    result_beard = cube("result_axe_beard_world", (-0.105, -0.14, 0.015),
                        (0.055, 0.08, 0.04), oak_light, result_root)

    rig = {"root": root, "torso": torso, "head": head,
           "right_arm": right_arm, "left_arm": left_arm, "cloak": cloak,
           "right_leg": right_leg, "left_leg": left_leg}

    for bone, keys in JAVA_KEYS.items():
        obj = rig[bone]
        for time_s, xyz in keys:
            frame = 1 + round(time_s * FPS)
            if bone == "root":
                # Java root Y is vertical model-space compression.
                obj.location.z = xyz[1] / 16.0
                obj.keyframe_insert("location", frame=frame)
            else:
                obj.rotation_mode = "XYZ"
                obj.rotation_euler = java_rotation_to_blender(bone, xyz)
                obj.keyframe_insert("rotation_euler", frame=frame)

    # Contact visibility is stepped, never interpolated. Blender 5.2 stores
    # channels in slotted Actions, so author the interpolation policy at key
    # creation instead of reaching into the legacy Action.fcurves API.
    bpy.context.preferences.edit.keyframe_new_interpolation_type = "CONSTANT"
    contacts = (5, 9, 13, 17, 19)  # frames for ticks 4/8/12/16/18
    for ingredient, contact in zip(ingredients, contacts):
        set_visibility(ingredient, False, 1)
        set_visibility(ingredient, False, contact - 1)
        set_visibility(ingredient, True, contact)
        set_visibility(ingredient, True, 30)
        set_visibility(ingredient, False, 31)
    for obj in (result_head, result_handle, result_beard):
        set_visibility(obj, False, 1)
        set_visibility(obj, False, 30)
        set_visibility(obj, True, 31)
        # Frame 44 is tick 43: the exact left-hand pickup contact. Keep the
        # world projection visible on contact and hand it off on tick 44.
        set_visibility(obj, True, 44)
        set_visibility(obj, False, 45)

    cube("floor", (0, 0, -0.08), (3.2, 3.2, 0.08),
         material("Floor", (0.045, 0.07, 0.055, 1)))
    bpy.ops.object.light_add(type="AREA", location=(-3.5, -4.0, 6.0))
    bpy.context.object.data.energy = 1050
    bpy.context.object.data.shape = "DISK"
    bpy.context.object.data.size = 4.0
    bpy.ops.object.light_add(type="AREA", location=(3.0, 1.5, 3.8))
    bpy.context.object.data.energy = 550
    bpy.context.object.data.color = (0.35, 0.65, 0.52)
    bpy.context.object.data.size = 3.0
    bpy.ops.object.light_add(type="POINT", location=(0, -1.3, 2.0))
    bpy.context.object.data.energy = 160
    bpy.context.object.data.color = (1.0, 0.22, 0.04)

    bpy.ops.object.camera_add(location=(4.8, -5.6, 4.8))
    camera = bpy.context.object
    camera.name = "review_camera_front_3_4"
    camera.data.lens = 56
    look_at(camera, (0, -0.35, 1.22))
    bpy.context.scene.camera = camera

    scene = bpy.context.scene
    # Blender 5.2 exposes the current Eevee engine through the stable
    # BLENDER_EEVEE identifier (the older _NEXT spelling is no longer valid).
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 640
    scene.render.resolution_y = 640
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world = bpy.data.worlds.new("Hearthstead night workshop")
    scene.world.color = (0.015, 0.02, 0.018)
    scene.frame_start = 1
    scene.frame_end = END_FRAME
    scene.render.fps = FPS

    # The release gate is a matrix, not a hero-shot selection. Every physical
    # ownership beat is rendered from the face-visible front 3/4, both sides,
    # and the back 3/4 (the cloak is an authored secondary-motion channel).
    review_frames = {
        "rest_tick00": 1,
        "ingredient_contact_tick04": 5,
        "layout_complete_tick18": 19,
        "windup_tick28": 29,
        "craft_pre_tick29": 30,
        "craft_contact_tick30": 31,
        "craft_post_tick31": 32,
        "result_read_tick36": 37,
        "pickup_pre_tick42": 43,
        "pickup_contact_tick43": 44,
        "pickup_post_tick44": 45,
        "recovery_tick47": 48,
        "neutral_tick48": 49,
    }
    cameras = {"front34": camera}
    for view, location in {
        "left": (-4.5, -1.7, 2.75),
        "right": (4.5, -1.7, 2.75),
        "back34": (-4.4, 4.8, 4.1),
    }.items():
        bpy.ops.object.camera_add(location=location)
        review_camera = bpy.context.object
        review_camera.name = f"review_camera_{view}"
        review_camera.data.lens = 58 if view != "back34" else 56
        look_at(review_camera, (0, -0.38, 1.05))
        cameras[view] = review_camera

    # The work table necessarily hides the boots in the face-visible view.
    # These unobstructed low cameras are evidence-only and prove that both
    # planted feet and the root return to the same world registration.
    for view, location in {
        "feet_left_low": (-4.8, 0.5, 1.05),
        "feet_right_low": (4.8, 0.5, 1.05),
        "feet_rear_low": (0.0, 5.1, 1.05),
    }.items():
        bpy.ops.object.camera_add(location=location)
        review_camera = bpy.context.object
        review_camera.name = f"review_camera_{view}"
        review_camera.data.lens = 58
        look_at(review_camera, (0, 0.10, 0.58))
        cameras[view] = review_camera

    for view, review_camera in cameras.items():
        scene.camera = review_camera
        for label, frame in review_frames.items():
            scene.frame_set(frame)
            scene.render.filepath = str(OUT / f"{view}_{label}.png")
            bpy.ops.render.render(write_still=True)
    scene.camera = camera

    payload = {
        "name": "LUMBER_CRAFT",
        "durationSeconds": 2.40,
        "fps": FPS,
        "contacts": {"ingredientTicks": [4, 8, 12, 16, 18],
                     "craftCommitTick": 30, "pickupTick": 43},
        "coordinateContract": {
            "rotation": "Java degrees; Blender mapping documented in author script",
            "root": "Java posVec where Y is vertical model-space compression",
        },
        "channels": {
            bone: [{"time": time_s, "value": list(value)}
                   for time_s, value in keys]
            for bone, keys in JAVA_KEYS.items()
        },
        "reviewFrames": review_frames,
        "reviewViews": list(cameras),
    }
    (OUT / "lumber_craft_java_channels.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND))
    print(f"HEARTHSTEAD_BLEND={BLEND}")
    print(f"HEARTHSTEAD_CHANNELS={OUT / 'lumber_craft_java_channels.json'}")
    print(f"HEARTHSTEAD_RENDERS={len(review_frames) * len(cameras)}")


if __name__ == "__main__":
    main()
