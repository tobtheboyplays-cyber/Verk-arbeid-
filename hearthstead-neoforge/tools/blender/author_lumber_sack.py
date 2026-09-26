"""Build the editable Blender source for Hearthstead's loaded Lumberer gait.

The scene reproduces the actual runtime division of responsibility:
WALK_LADEN owns legs/root/torso/head/cloak, HAUL_LOG_HEAVY owns both arms, and
the full-load procedural contribution adds 0.22 radians of forward spine lean.
The timber frame is a torso child and the hands are authored onto its lower
grips.  Review images and a machine-readable contact report are emitted beside
the .blend file so this cannot be approved from one flattering angle.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import bpy
from mathutils import Vector


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
OUT = PROJECT / "build" / "blender" / "lumber_sack_v1"
BLEND = HERE / "hearthstead_lumber_sack_v1.blend"
FPS = 20
END_FRAME = 49


WALK_KEYS = {
    "right_leg": [(0.0, (-24, 0, 0)), (0.3, (0, 0, 0)),
                  (0.6, (24, 0, 0)), (0.9, (0, 0, 0)),
                  (1.2, (-24, 0, 0))],
    "left_leg": [(0.0, (24, 0, 0)), (0.3, (0, 0, 0)),
                 (0.6, (-24, 0, 0)), (0.9, (0, 0, 0)),
                 (1.2, (24, 0, 0))],
    # Runtime adds 0.22 rad (12.61 degrees) at full load.
    "torso": [(0.0, (16.61, 3, 0)), (0.6, (16.61, -3, 0)),
               (1.2, (16.61, 3, 0))],
    "head": [(0.0, (-13.56, 0, 0)), (1.2, (-13.56, 0, 0))],
    "cloak": [(0.0, (4, 0, 0)), (0.6, (5, 0, 0)),
               (1.2, (4, 0, 0))],
}

ARM_KEYS = {
    "right_arm": [(0.0, (-43, -5, -9)), (1.2, (-45, -4, -10)),
                  (2.4, (-43, -5, -9))],
    "left_arm": [(0.0, (-43, 5, 9)), (1.2, (-45, 4, 10)),
                 (2.4, (-43, 5, 9))],
}

ROOT_Y = [(0.0, -1.0), (0.3, -1.2), (0.6, -1.0), (0.9, -1.2),
          (1.2, -1.0)]


def material(name, colour, metallic=0.0, roughness=0.75):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = colour
    mat.use_nodes = True
    principled = mat.node_tree.nodes.get("Principled BSDF")
    principled.inputs["Base Color"].default_value = colour
    principled.inputs["Metallic"].default_value = metallic
    principled.inputs["Roughness"].default_value = roughness
    return mat


def cube(name, location, scale, mat, parent=None, rotation=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(location=location, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    if parent is not None:
        obj.parent = parent
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


def java_rotation_to_blender(bone, java_xyz):
    x, y, z = java_xyz
    # The proxy's limbs and head all hang from their Minecraft-style pivots,
    # so Java's +X hinge maps to Blender -X consistently. Treating the head as
    # a special case made its intended counter-rotation add to the torso lean
    # and visually threw the face backward in the side review.
    return (math.radians(-x), math.radians(z), math.radians(-y))


def key_rotation(obj, bone, time_s, xyz):
    obj.rotation_mode = "XYZ"
    obj.rotation_euler = java_rotation_to_blender(bone, xyz)
    obj.keyframe_insert("rotation_euler", frame=1 + round(time_s * FPS))


def repeat_walk_keys(rig):
    for bone, keys in WALK_KEYS.items():
        for cycle in (0.0, 1.2):
            for time_s, xyz in keys:
                # Do not duplicate the shared 1.20-second boundary.
                if cycle > 0.0 and time_s == 0.0:
                    continue
                key_rotation(rig[bone], bone, time_s + cycle, xyz)
    for cycle in (0.0, 1.2):
        for time_s, value in ROOT_Y:
            if cycle > 0.0 and time_s == 0.0:
                continue
            rig["root"].location.z = value / 16.0
            rig["root"].keyframe_insert("location", frame=1 + round((time_s + cycle) * FPS))


def look_at(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()


def world_point(obj, local):
    return obj.matrix_world @ Vector(local)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.edit.keyframe_new_interpolation_type = "BEZIER"

    forest = material("Lumberer forest", (0.07, 0.23, 0.13, 1))
    moss = material("Lumberer trim", (0.24, 0.42, 0.22, 1))
    linen = material("Work linen", (0.54, 0.40, 0.25, 1))
    skin = material("Skin", (0.63, 0.37, 0.22, 1))
    leather = material("Harness leather", (0.16, 0.055, 0.018, 1))
    oak = material("Timber frame", (0.28, 0.12, 0.035, 1))
    oak_cut = material("Cut log", (0.54, 0.29, 0.09, 1))
    iron = material("Iron fittings", (0.12, 0.15, 0.16, 1), 0.5)
    contact = material("Contact marker", (0.06, 0.82, 0.34, 1), 0.05, 0.35)

    root = empty("root", (0, 0, 0))
    torso = empty("torso", (0, 0, 1.72), root)
    cube("body", (0, 0, -0.34), (0.31, 0.18, 0.36), forest, torso)
    cube("belt", (0, -0.19, -0.55), (0.32, 0.025, 0.07), leather, torso)
    cube("shoulder_yoke", (0, 0.21, -0.06), (0.35, 0.055, 0.075), leather, torso)

    head = empty("head", (0, 0, 0.06), torso)
    cube("head_mesh", (0, -0.005, 0.22), (0.255, 0.255, 0.255), skin, head)
    cube("hair", (0, 0.01, 0.49), (0.26, 0.25, 0.035), leather, head)
    cube("nose", (0, -0.275, 0.20), (0.045, 0.035, 0.065), skin, head)
    cube("eye_l", (-0.09, -0.274, 0.29), (0.026, 0.012, 0.026), iron, head)
    cube("eye_r", (0.09, -0.274, 0.29), (0.026, 0.012, 0.026), iron, head)

    right_arm = empty("right_arm", (-0.43, 0, -0.02), torso)
    left_arm = empty("left_arm", (0.43, 0, -0.02), torso)
    cube("right_sleeve", (0, 0, -0.23), (0.13, 0.13, 0.24), moss, right_arm)
    cube("right_hand", (0, 0, -0.55), (0.115, 0.115, 0.18), skin, right_arm)
    cube("left_sleeve", (0, 0, -0.23), (0.13, 0.13, 0.24), moss, left_arm)
    cube("left_hand", (0, 0, -0.55), (0.115, 0.115, 0.18), skin, left_arm)

    right_leg = empty("right_leg", (-0.17, 0, 0.86), root)
    left_leg = empty("left_leg", (0.17, 0, 0.86), root)
    cube("right_trouser", (0, 0, -0.40), (0.145, 0.16, 0.40), linen, right_leg)
    cube("left_trouser", (0, 0, -0.40), (0.145, 0.16, 0.40), linen, left_leg)
    cube("right_boot", (0, -0.05, -0.75), (0.15, 0.22, 0.11), leather, right_leg)
    cube("left_boot", (0, -0.05, -0.75), (0.15, 0.22, 0.11), leather, left_leg)

    cloak = empty("cloak", (0, 0.20, -0.05), torso)
    cube("cloak_mesh", (0, 0.02, -0.32), (0.28, 0.035, 0.31), forest, cloak)

    # The frame is torso-owned exactly like SettlerModel.lumber_frame. Its
    # lower grip centres are aligned to the palm centres at the heavy midpoint.
    frame = empty("lumber_frame", (0, 0.42, -0.03), torso)
    for x in (-0.42, 0.42):
        cube(f"rail_{x}", (x, 0, -0.33), (0.045, 0.045, 0.48), oak, frame)
    cube("crossbar_top", (0, 0, 0.08), (0.42, 0.045, 0.045), oak, frame)
    cube("crossbar_bottom", (0, 0, -0.66), (0.42, 0.045, 0.045), oak, frame)
    cube("cradle", (0, 0.12, -0.75), (0.46, 0.16, 0.05), oak, frame)
    for x, z, tilt in ((-0.22, -0.28, -0.08), (0, -0.25, 0.05), (0.22, -0.30, -0.04)):
        cube(f"log_{x}", (x, 0.10, z), (0.105, 0.105, 0.39), oak_cut, frame,
             rotation=(0, tilt, 0))
    right_grip = cube("right_lower_grip", (-0.43, -0.01, -0.49),
                      (0.105, 0.09, 0.09), leather, frame)
    left_grip = cube("left_lower_grip", (0.43, -0.01, -0.49),
                     (0.105, 0.09, 0.09), leather, frame)
    # Small rivets give the contact area a readable silhouette from the back.
    cube("right_rivet", (-0.43, -0.105, -0.49), (0.035, 0.018, 0.035), iron, frame)
    cube("left_rivet", (0.43, -0.105, -0.49), (0.035, 0.018, 0.035), iron, frame)

    rig = {"root": root, "torso": torso, "head": head, "cloak": cloak,
           "right_arm": right_arm, "left_arm": left_arm,
           "right_leg": right_leg, "left_leg": left_leg}
    repeat_walk_keys(rig)
    for bone, keys in ARM_KEYS.items():
        for time_s, xyz in keys:
            key_rotation(rig[bone], bone, time_s, xyz)

    # Contact markers are disabled for beauty frames but remain in the editable
    # source for an animator to inspect the exact grip targets interactively.
    right_marker = cube("QA_right_palm_target", (0, 0, -0.68),
                        (0.035, 0.035, 0.035), contact, right_arm)
    left_marker = cube("QA_left_palm_target", (0, 0, -0.68),
                       (0.035, 0.035, 0.035), contact, left_arm)
    right_marker.hide_render = True
    left_marker.hide_render = True

    cube("floor", (0, 0, -0.08), (3.6, 3.6, 0.08),
         material("Floor", (0.035, 0.055, 0.042, 1)))
    # Short path markers make planted-foot and stride direction readable.
    for z in range(-3, 4):
        cube(f"path_{z}", (0, z * 0.65, 0.012), (0.24, 0.18, 0.012),
             material(f"Path {z}", (0.12, 0.14, 0.10, 1)))

    bpy.ops.object.light_add(type="AREA", location=(-3.5, -4.5, 6.0))
    bpy.context.object.data.energy = 1100
    bpy.context.object.data.shape = "DISK"
    bpy.context.object.data.size = 4.0
    bpy.ops.object.light_add(type="AREA", location=(3.5, 3.0, 4.0))
    bpy.context.object.data.energy = 700
    bpy.context.object.data.color = (0.28, 0.62, 0.40)
    bpy.context.object.data.size = 3.0
    bpy.ops.object.light_add(type="POINT", location=(0, -2.2, 2.4))
    bpy.context.object.data.energy = 220
    bpy.context.object.data.color = (1.0, 0.30, 0.08)

    cameras = {
        "front34": ((4.4, -5.6, 3.4), (0, 0, 1.15)),
        "left": ((-5.6, -0.15, 2.45), (0, 0, 1.10)),
        "right": ((5.6, -0.15, 2.45), (0, 0, 1.10)),
        "back34": ((-4.2, 5.2, 3.3), (0, 0.20, 1.20)),
    }
    camera_objects = {}
    for name, (location, target) in cameras.items():
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"review_camera_{name}"
        camera.data.lens = 58
        look_at(camera, target)
        camera_objects[name] = camera

    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 560
    scene.render.resolution_y = 560
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world = bpy.data.worlds.new("Hearthstead forest workshop")
    scene.world.color = (0.012, 0.018, 0.014)
    scene.frame_start = 1
    scene.frame_end = END_FRAME
    scene.render.fps = FPS

    review_times = (0.00, 0.30, 0.60, 0.90, 1.20)
    metrics = []
    for view, camera in camera_objects.items():
        scene.camera = camera
        for time_s in review_times:
            frame_no = 1 + round(time_s * FPS)
            scene.frame_set(frame_no)
            bpy.context.view_layer.update()
            label = f"{view}_{time_s:0.2f}s".replace(".", "p")
            scene.render.filepath = str(OUT / f"{label}.png")
            bpy.ops.render.render(write_still=True)

            if view == "front34":
                right_palm = world_point(right_arm, (0, 0, -0.68))
                left_palm = world_point(left_arm, (0, 0, -0.68))
                right_target = world_point(right_grip, (0, 0, 0))
                left_target = world_point(left_grip, (0, 0, 0))
                metrics.append({
                    "timeSeconds": time_s,
                    "rightPalmGap": round((right_palm - right_target).length, 4),
                    "leftPalmGap": round((left_palm - left_target).length, 4),
                    "torsoForwardDegrees": round(-math.degrees(torso.rotation_euler.x), 2),
                })

    payload = {
        "animation": "WALK_LADEN + HAUL_LOG_HEAVY + full-load spine",
        "fps": FPS,
        "durationSeconds": 2.4,
        "reviewTimesSeconds": list(review_times),
        "maxAcceptedPalmGap": 0.12,
        "measurements": metrics,
        "runtimeChannels": {"walk": WALK_KEYS, "arms": ARM_KEYS,
                            "rootY": ROOT_Y},
    }
    (OUT / "carry_contact_report.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    scene.camera = camera_objects["front34"]
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND))
    print(f"HEARTHSTEAD_BLEND={BLEND}")
    print(f"HEARTHSTEAD_REPORT={OUT / 'carry_contact_report.json'}")
    print(f"HEARTHSTEAD_RENDERS={len(cameras) * len(review_times)}")


if __name__ == "__main__":
    main()
