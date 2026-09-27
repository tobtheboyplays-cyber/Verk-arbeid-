"""Author Hearthstead's visible bag-to-chest unloading Candidate in Blender.

This is deliberately a physical, Minecraft-proportioned review rig rather
than a cinematic substitute.  It encodes the 20 TPS transaction beats and
produces three-angle evidence plus numeric contact/ownership checks.  It does
not modify Minecraft runtime code or claim that the animation is integrated.
"""

from __future__ import annotations

import json
import hashlib
import math
from pathlib import Path
import shutil

import bpy
from mathutils import Vector


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
CONTRACT = HERE / "BAG_TO_CHEST_STATE_CONTRACT.md"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def current_candidate_hash():
    """Hash the authored contract and scene generator, not volatile renders."""
    digest = hashlib.sha256()
    for path in (Path(__file__), CONTRACT):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()[:16]


CANDIDATE_HASH = current_candidate_hash()
OUT = PROJECT / "qa" / "evidence" / "blender" / "bag_to_chest_v1" / CANDIDATE_HASH
BLEND = HERE / "hearthstead_bag_to_chest_v1.blend"
FPS = 20
END_TICK = 80
END_FRAME = END_TICK + 1
UPPER_LENGTH = 0.58
FOREARM_LENGTH = 0.74
PALM_LOCAL = Vector((0.0, 0.0, -FOREARM_LENGTH))
MAX_PALM_GAP = 0.12
MIN_FORWARD_LEAN = 8.0
MAX_GAZE_ERROR = 35.0
MAX_HEAD_PITCH = math.radians(14.0)
MAX_HEAD_YAW = math.radians(35.0)


def material(name, colour, metallic=0.0, roughness=0.72):
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
    if parent is not None:
        obj.parent = parent
    obj.location = location
    return obj


def look_at(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat(
        "-Z", "Y").to_euler()


def world_point(obj, local):
    return obj.matrix_world @ Vector(local)


def tick_frame(tick):
    return 1 + tick


def set_visibility(obj, visible, tick):
    frame = tick_frame(tick)
    # Blender does not guarantee a hidden empty hides its mesh children, so
    # key every member of a physical prop. This makes ownership review exact.
    for member in (obj, *obj.children_recursive):
        member.hide_render = not visible
        member.hide_viewport = not visible
        member.keyframe_insert("hide_render", frame=frame)
        member.keyframe_insert("hide_viewport", frame=frame)


def key_scalar_rotation(obj, radians, tick):
    obj.rotation_mode = "XYZ"
    obj.rotation_euler = (radians, 0.0, 0.0)
    obj.keyframe_insert("rotation_euler", frame=tick_frame(tick))


def key_root_height(root, height, tick):
    root.location.z = height
    root.keyframe_insert("location", frame=tick_frame(tick))


def key_world_location(scene, obj, parent, world_location, tick):
    """Key a parented proxy to an explicit world point without inheriting drift."""
    scene.frame_set(tick_frame(tick))
    bpy.context.view_layer.update()
    obj.location = parent.matrix_world.inverted() @ Vector(world_location)
    obj.keyframe_insert("location", frame=tick_frame(tick))


def key_arm_target(scene, arm, elbow, target_world, tick):
    """Solve a two-link block arm so its palm reaches the physical target."""
    scene.frame_set(tick_frame(tick))
    bpy.context.view_layer.update()
    parent_inverse = arm.parent.matrix_world.inverted()
    target_local = parent_inverse @ Vector(target_world)
    shoulder = arm.location
    shoulder_to_target = target_local - shoulder
    reach = shoulder_to_target.length
    if reach < 0.001:
        raise ValueError(f"arm target is at the shoulder for {arm.name}")
    if reach > UPPER_LENGTH + FOREARM_LENGTH + 0.0001:
        raise ValueError(f"arm target is unreachable for {arm.name}: {reach:.3f}m")
    direction = shoulder_to_target.normalized()
    # Keep elbows visibly out of the torso. Projection makes the bend stable
    # even when the chest target is directly in front of the worker.
    side = -1.0 if arm.name.startswith("left") else 1.0
    preferred = Vector((side, 0.18, 0.0))
    perpendicular = preferred - direction * preferred.dot(direction)
    if perpendicular.length < 0.001:
        perpendicular = Vector((0.0, 1.0, 0.0)).cross(direction)
    perpendicular.normalize()
    cosine = max(-1.0, min(1.0, (UPPER_LENGTH ** 2 + reach ** 2 - FOREARM_LENGTH ** 2)
                           / (2.0 * UPPER_LENGTH * reach)))
    upper_direction = direction * cosine + perpendicular * math.sqrt(1.0 - cosine ** 2)
    arm.rotation_mode = "QUATERNION"
    arm.rotation_quaternion = Vector((0.0, 0.0, -1.0)).rotation_difference(upper_direction)
    arm.keyframe_insert("rotation_quaternion", frame=tick_frame(tick))
    elbow.location = (0.0, 0.0, -UPPER_LENGTH)
    elbow.keyframe_insert("location", frame=tick_frame(tick))
    elbow_world_local = shoulder + upper_direction * UPPER_LENGTH
    lower_direction_parent = (target_local - elbow_world_local).normalized()
    lower_direction_local = arm.rotation_quaternion.inverted() @ lower_direction_parent
    elbow.rotation_mode = "QUATERNION"
    elbow.rotation_quaternion = Vector((0.0, 0.0, -1.0)).rotation_difference(lower_direction_local)
    elbow.keyframe_insert("rotation_quaternion", frame=tick_frame(tick))


def key_head_target(scene, head, target_world, tick):
    """Aim eyes toward target without letting the neck flip through the torso."""
    scene.frame_set(tick_frame(tick))
    bpy.context.view_layer.update()
    target_local = head.parent.matrix_world.inverted() @ Vector(target_world)
    direction = target_local - head.location
    if direction.length < 0.001:
        raise ValueError("head target is at the neck pivot")
    horizontal = math.hypot(direction.x, direction.y)
    # The local model faces -Y. Positive X pitch lowers that facing vector
    # toward the active target. A hard anatomical clamp rejects the old
    # full-quaternion flip that turned the face into an unreadable dark plane.
    pitch = math.atan2(-direction.z, max(horizontal, 0.001))
    yaw = math.atan2(direction.x, -direction.y)
    pitch = max(-MAX_HEAD_PITCH, min(MAX_HEAD_PITCH, pitch))
    yaw = max(-MAX_HEAD_YAW, min(MAX_HEAD_YAW, yaw))
    head.rotation_mode = "XYZ"
    head.rotation_euler = (pitch, 0.0, yaw)
    head.keyframe_insert("rotation_euler", frame=tick_frame(tick))


def create_bag(name, location, mats, parent=None):
    """Return a bag root with a readable mouth and two palm-sized lower rails."""
    bag = empty(name, location, parent)
    leather, canvas, oak, iron = mats
    cube(f"{name}_body", (0, 0, 0.31), (0.34, 0.20, 0.31), canvas, bag)
    cube(f"{name}_flap", (0, -0.185, 0.53), (0.28, 0.030, 0.10), leather, bag)
    cube(f"{name}_mouth", (0, -0.02, 0.62), (0.29, 0.17, 0.035), leather, bag)
    cube(f"{name}_rail_left", (-0.31, -0.02, 0.42), (0.055, 0.055, 0.12), oak, bag)
    cube(f"{name}_rail_right", (0.31, -0.02, 0.42), (0.055, 0.055, 0.12), oak, bag)
    cube(f"{name}_strap", (0, 0.17, 0.37), (0.24, 0.025, 0.04), iron, bag)
    return bag


def create_log(name, location, mat, end_mat, parent=None):
    # Minecraft-like oak log: a compact, recognisable block with end caps.
    log = empty(name, location, parent)
    cube(f"{name}_wood", (0, 0, 0), (0.14, 0.14, 0.32), mat, log,
         rotation=(0, math.radians(90), 0))
    cube(f"{name}_end_left", (-0.335, 0, 0), (0.014, 0.14, 0.14), end_mat, log)
    cube(f"{name}_end_right", (0.335, 0, 0), (0.014, 0.14, 0.14), end_mat, log)
    return log


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.edit.keyframe_new_interpolation_type = "BEZIER"

    forest = material("Worker forest cloth", (0.065, 0.23, 0.13, 1))
    moss = material("Worker moss trim", (0.20, 0.43, 0.22, 1))
    linen = material("Worker linen", (0.56, 0.42, 0.27, 1))
    skin = material("Worker skin", (0.63, 0.37, 0.22, 1))
    leather = material("Harness leather", (0.16, 0.055, 0.018, 1))
    canvas = material("Bag canvas", (0.40, 0.25, 0.105, 1))
    oak = material("Oak frame", (0.30, 0.14, 0.045, 1))
    # Deliberately high-contrast original oak treatment so the transferred
    # real item remains readable against the chest rim and work clothes.
    oak_log = material("Oak log", (0.82, 0.36, 0.055, 1))
    oak_end = material("Oak end grain", (0.96, 0.72, 0.34, 1))
    iron = material("Forged iron", (0.12, 0.15, 0.17, 1), 0.52)
    chest_wood = material("Chest dark oak", (0.22, 0.10, 0.035, 1))
    chest_trim = material("Chest brass", (0.72, 0.45, 0.12, 1), 0.38)
    qa_green = material("QA contact marker", (0.08, 0.82, 0.32, 1), 0.0, 0.35)

    # Worker skeleton. Facing negative Y: a positive X torso rotation hinges
    # chest-forward toward the bag/chest rather than arching toward the back.
    # The worker arrives close enough to the chest to use a real arm reach;
    # the bag is set down just behind-left, not at an exaggerated distance.
    root = empty("worker_root", (0, -0.15, 0))
    torso = empty("worker_torso", (0, 0, 1.48), root)
    cube("worker_body", (0, 0, -0.31), (0.31, 0.18, 0.37), forest, torso)
    cube("worker_belt", (0, -0.19, -0.54), (0.32, 0.025, 0.07), leather, torso)
    cube("worker_yoke", (0, 0.20, -0.06), (0.35, 0.055, 0.075), leather, torso)

    head = empty("worker_head", (0, 0, 0.04), torso)
    cube("worker_head_mesh", (0, -0.005, 0.23), (0.255, 0.255, 0.255), skin, head)
    cube("worker_hair", (0, 0.01, 0.49), (0.26, 0.25, 0.035), leather, head)
    cube("worker_nose", (0, -0.275, 0.20), (0.045, 0.035, 0.065), skin, head)
    cube("worker_eye_l", (-0.09, -0.274, 0.29), (0.026, 0.012, 0.026), iron, head)
    cube("worker_eye_r", (0.09, -0.274, 0.29), (0.026, 0.012, 0.026), iron, head)

    left_arm = empty("left_arm", (-0.43, 0, -0.02), torso)
    right_arm = empty("right_arm", (0.43, 0, -0.02), torso)
    left_elbow = empty("left_elbow", (0, 0, -UPPER_LENGTH), left_arm)
    right_elbow = empty("right_elbow", (0, 0, -UPPER_LENGTH), right_arm)
    for side, arm, elbow in (("left", left_arm, left_elbow),
                             ("right", right_arm, right_elbow)):
        cube(f"{side}_sleeve", (0, 0, -0.27), (0.13, 0.13, 0.27), moss, arm)
        cube(f"{side}_forearm", (0, 0, -0.33), (0.115, 0.115, 0.33), skin, elbow)
        cube(f"{side}_hand", (0, 0, -0.65), (0.125, 0.125, 0.13), skin, elbow)
        marker = cube(f"QA_{side}_palm", tuple(PALM_LOCAL), (0.03, 0.03, 0.03),
                      qa_green, elbow)
        marker.hide_render = True

    for side, x in (("left", -0.17), ("right", 0.17)):
        leg = empty(f"{side}_leg", (x, 0, 0.83), root)
        cube(f"{side}_trouser", (0, 0, -0.40), (0.145, 0.16, 0.40), linen, leg)
        cube(f"{side}_boot", (0, -0.055, -0.75), (0.15, 0.22, 0.11), leather, leg)
    cloak = empty("worker_cloak", (0, 0.20, -0.04), torso)
    cube("worker_cloak_mesh", (0, 0.02, -0.31), (0.28, 0.035, 0.31), forest, cloak)

    # The fixed ground anchor is deliberately independent from the worker.
    # Forward-left placement keeps the world bag beside the worker while
    # letting both the eyes and the spine face the next physical target.
    BAG_ANCHOR = Vector((-0.62, -0.36, 0.0))
    bag_mats = (leather, canvas, oak, iron)
    carried_bag = create_bag("carried_bag", (0.0, 0.34, -0.40), bag_mats, torso)
    world_bag = create_bag("world_bag", BAG_ANCHOR, bag_mats)
    bag_mouth = BAG_ANCHOR + Vector((0.0, -0.03, 0.65))
    bag_left_rail = BAG_ANCHOR + Vector((-0.31, -0.02, 0.42))
    bag_right_rail = BAG_ANCHOR + Vector((0.31, -0.02, 0.42))

    # World-owned chest. The hinge is at its far/back edge; the lid is only
    # animated after the right palm reaches the pull.
    chest_center = Vector((0.0, -1.10, 0.35))
    # Keep the chest Minecraft-readable but compact enough that an open lid
    # cannot swallow the item/hand silhouette from ordinary player angles.
    cube("chest_body", chest_center, (0.46, 0.32, 0.30), chest_wood)
    cube("chest_front_trim", (0, -0.77, 0.38), (0.34, 0.022, 0.050), chest_trim)
    cube("chest_lock", (0, -0.795, 0.51), (0.06, 0.035, 0.08), chest_trim)
    lid_pivot = empty("chest_lid_pivot", (0.0, -1.42, 0.66))
    cube("chest_lid", (0, 0.26, 0.075), (0.46, 0.32, 0.075), chest_wood, lid_pivot)
    cube("chest_lid_trim", (0, 0.55, 0.07), (0.34, 0.022, 0.045), chest_trim, lid_pivot)
    lid_pull_local = Vector((0.0, 0.55, 0.08))
    chest_cavity = Vector((0.0, -1.09, 0.68))

    # Visible item ownership: bag -> worker hand -> chest at ticks 30 and 48.
    log_in_bag = create_log("oak_log_in_bag", BAG_ANCHOR + Vector((0, -0.02, 0.69)),
                            oak_log, oak_end)
    log_in_hand = create_log("oak_log_in_left_hand", tuple(PALM_LOCAL), oak_log,
                             oak_end, left_elbow)
    # A shallow diagonal produces an unmistakable log silhouette before the
    # chest opens; it is still parented to the true left palm.
    log_in_hand.rotation_euler = (math.radians(8), math.radians(35), math.radians(-24))
    log_in_hand.scale = (1.32, 1.32, 1.32)
    log_in_chest = create_log("oak_log_in_chest", chest_cavity, oak_log, oak_end)

    # Exact world-space anchor route for the carried bag before the atomic
    # handoff to the fixed world bag at tick 12.
    scene = bpy.context.scene
    key_scalar_rotation(torso, math.radians(0), 0)
    key_scalar_rotation(torso, math.radians(3), 8)
    key_scalar_rotation(torso, math.radians(10), 12)
    key_scalar_rotation(torso, math.radians(12), 18)
    key_scalar_rotation(torso, math.radians(17), 24)
    key_scalar_rotation(torso, math.radians(19), 30)
    key_scalar_rotation(torso, math.radians(18), 36)
    key_scalar_rotation(torso, math.radians(17), 48)
    key_scalar_rotation(torso, math.radians(12), 56)
    key_scalar_rotation(torso, math.radians(7), 64)
    key_scalar_rotation(torso, math.radians(0), 80)
    for tick, height in ((0, 0.0), (8, -0.025), (12, -0.055), (18, -0.12),
                         (24, -0.18), (30, -0.20), (36, -0.18), (48, -0.16),
                         (56, -0.10), (64, -0.045), (80, 0.0)):
        key_root_height(root, height, tick)

    # The face establishes intent before each contact. The target only changes
    # when the task changes: placed bag -> chest pull -> chest cavity.
    head_targets = {
        # Aim points are the visually readable upper faces of the active
        # object. A full neck pivot to the ground would make the head flip
        # through the torso, which is the opposite of a natural bag read.
        0: Vector((0.0, -1.10, 1.22)),
        8: BAG_ANCHOR + Vector((0.0, 0.0, 1.24)),
        12: BAG_ANCHOR + Vector((0.0, -0.03, 1.24)),
        18: BAG_ANCHOR + Vector((0.0, -0.03, 1.22)),
        24: BAG_ANCHOR + Vector((0.0, -0.03, 1.22)),
        30: BAG_ANCHOR + Vector((0.0, -0.03, 1.22)),
        31: Vector((0.0, -0.89, 1.20)),
        36: Vector((0.0, -1.09, 1.18)),
        48: Vector((0.0, -1.09, 1.18)),
        56: Vector((0.0, -1.09, 1.18)),
        64: Vector((0.0, -1.10, 1.12)),
        80: Vector((0.0, -1.10, 1.20)),
    }
    for tick, target in head_targets.items():
        key_head_target(scene, head, target, tick)

    scene.frame_set(tick_frame(0))
    bpy.context.view_layer.update()
    carried_start = world_point(carried_bag, (0, 0, 0))
    key_world_location(scene, carried_bag, torso, carried_start, 0)
    key_world_location(scene, carried_bag, torso, carried_start.lerp(BAG_ANCHOR, 0.50), 8)
    key_world_location(scene, carried_bag, torso, BAG_ANCHOR, 12)

    # Visibility is a stepped ownership handoff: no duplicated or following bag.
    set_visibility(carried_bag, True, 0)
    set_visibility(carried_bag, True, 11)
    set_visibility(carried_bag, False, 12)
    set_visibility(world_bag, False, 0)
    set_visibility(world_bag, False, 11)
    set_visibility(world_bag, True, 12)
    for obj in (log_in_bag,):
        set_visibility(obj, False, 0)
        set_visibility(obj, True, 26)
        set_visibility(obj, True, 29)
        set_visibility(obj, False, 30)
    set_visibility(log_in_hand, False, 0)
    set_visibility(log_in_hand, False, 29)
    set_visibility(log_in_hand, True, 30)
    set_visibility(log_in_hand, True, 47)
    set_visibility(log_in_hand, False, 48)
    set_visibility(log_in_chest, False, 0)
    set_visibility(log_in_chest, False, 47)
    set_visibility(log_in_chest, True, 48)

    # Lid timing: right contact at 31, fully open 36, deposit 48, closed 64.
    for tick, angle in ((0, 0), (30, 0), (31, 0), (36, 65), (48, 65),
                        (56, 42), (64, 0), (80, 0)):
        key_scalar_rotation(lid_pivot, math.radians(angle), tick)

    # Target history owns all exact contact measurements.  We keep the arms
    # independently aimed with an actual palm point instead of declaring a
    # contact from a flattering camera angle.
    contact_targets = {}

    def rest_target(elbow):
        return world_point(elbow, (0.0, -0.04, PALM_LOCAL.z))

    # Carried-brace and set-down grips.
    for tick in (0, 8):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        key_arm_target(scene, left_arm, left_elbow,
                       world_point(carried_bag, (-0.31, -0.02, 0.42)), tick)
        key_arm_target(scene, right_arm, right_elbow,
                       world_point(carried_bag, (0.31, -0.02, 0.42)), tick)
    key_arm_target(scene, left_arm, left_elbow, bag_left_rail, 12)
    key_arm_target(scene, right_arm, right_elbow, bag_right_rail, 12)
    contact_targets[12] = {"left_bag_rail": bag_left_rail, "right_bag_rail": bag_right_rail}

    # The left hand enters and withdraws from the bag while the free hand moves
    # to the chest.  The log transfer is exact on tick 30.
    key_arm_target(scene, left_arm, left_elbow, bag_mouth, 18)
    # Free hand starts a readable, near-body reach toward the chest rather
    # than snapping from the bag to the lid across an impossible distance.
    key_arm_target(scene, right_arm, right_elbow, Vector((0.25, -0.60, 0.86)), 18)
    key_arm_target(scene, left_arm, left_elbow, bag_mouth, 24)
    contact_targets[24] = {"left_bag_mouth": bag_mouth}
    key_arm_target(scene, left_arm, left_elbow, bag_mouth, 30)
    contact_targets[30] = {"left_withdraw": bag_mouth}
    # Immediately after the transfer, lift the real log clear of the bag and
    # chest silhouette before the free hand opens the lid.
    key_arm_target(scene, left_arm, left_elbow, Vector((-0.58, -0.54, 1.04)), 31)

    scene.frame_set(tick_frame(31))
    bpy.context.view_layer.update()
    lid_closed_pull = world_point(lid_pivot, lid_pull_local)
    key_arm_target(scene, right_arm, right_elbow, lid_closed_pull, 31)
    contact_targets[31] = {"right_lid_pull_closed": lid_closed_pull}

    scene.frame_set(tick_frame(36))
    bpy.context.view_layer.update()
    lid_open_pull = world_point(lid_pivot, lid_pull_local)
    # The right palm triggers the lid at tick 31, then releases while the
    # hinged lid completes its own natural open motion. This avoids a rubbery
    # arm following an impossible high arc while preserving clear causality.
    key_arm_target(scene, right_arm, right_elbow, Vector((0.27, -0.53, 0.84)), 36)
    key_arm_target(scene, left_arm, left_elbow, Vector((-0.34, -0.72, 1.08)), 36)

    key_arm_target(scene, left_arm, left_elbow, Vector((-0.15, -0.88, 1.02)), 42)
    key_arm_target(scene, left_arm, left_elbow, chest_cavity, 48)
    scene.frame_set(tick_frame(48))
    bpy.context.view_layer.update()
    contact_targets[48] = {"left_chest_cavity": chest_cavity}
    key_arm_target(scene, right_arm, right_elbow, Vector((0.24, -0.47, 0.76)), 48)

    scene.frame_set(tick_frame(56))
    bpy.context.view_layer.update()
    key_arm_target(scene, left_arm, left_elbow, Vector((-0.18, -0.48, 0.72)), 56)
    key_arm_target(scene, right_arm, right_elbow, Vector((0.20, -0.36, 0.70)), 56)
    scene.frame_set(tick_frame(64))
    bpy.context.view_layer.update()
    closed_pull = world_point(lid_pivot, lid_pull_local)
    key_arm_target(scene, right_arm, right_elbow, Vector((0.18, -0.25, 0.67)), 64)
    for tick in (72, 80):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        key_arm_target(scene, left_arm, left_elbow, rest_target(left_elbow), tick)
        key_arm_target(scene, right_arm, right_elbow, rest_target(right_elbow), tick)

    # Ground, cameras, and readable warm workshop lighting for normal play
    # angles, not one cinematic-only review view.
    cube("workshop_floor", (0, -0.75, -0.08), (3.4, 3.4, 0.08),
         material("Workshop floor", (0.035, 0.055, 0.042, 1)))
    bpy.ops.object.light_add(type="AREA", location=(-3.4, -4.0, 5.6))
    bpy.context.object.data.energy = 1050
    bpy.context.object.data.shape = "DISK"
    bpy.context.object.data.size = 4.0
    bpy.ops.object.light_add(type="AREA", location=(3.0, 1.6, 3.7))
    bpy.context.object.data.energy = 500
    bpy.context.object.data.color = (0.32, 0.63, 0.42)
    bpy.context.object.data.size = 3.0
    bpy.ops.object.light_add(type="POINT", location=(0, -1.15, 2.5))
    bpy.context.object.data.energy = 190
    bpy.context.object.data.color = (1.0, 0.28, 0.08)

    cameras = {
        # Review from the bag-facing front three-quarter: it exposes the
        # actual withdrawn log before the chest opens instead of hiding it
        # behind the chest front.
        "front34": ((-4.55, -5.8, 3.35), (-0.18, -0.72, 1.08)),
        "left": ((-5.65, -0.40, 2.50), (-0.15, -0.78, 1.00)),
        "right": ((5.65, -0.40, 2.50), (-0.15, -0.78, 1.00)),
        "back34": ((-4.45, 4.70, 3.20), (-0.10, -0.72, 1.08)),
    }
    camera_objects = {}
    for name, (location, target) in cameras.items():
        bpy.ops.object.camera_add(location=location)
        camera = bpy.context.object
        camera.name = f"review_camera_{name}"
        camera.data.lens = 58
        look_at(camera, target)
        camera_objects[name] = camera

    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 560
    scene.render.resolution_y = 560
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world = bpy.data.worlds.new("Hearthstead chest workshop")
    scene.world.color = (0.012, 0.018, 0.014)
    scene.frame_start = 1
    scene.frame_end = END_FRAME
    scene.render.fps = FPS

    # Every ownership or chest-state transition has pre / exact / post proof.
    # The remaining beats establish neutral, read, reach and recovery context.
    review_ticks = (0, 11, 12, 13, 24, 29, 30, 31, 32, 35, 36, 37,
                    42, 47, 48, 49, 63, 64, 65, 80)
    for view, camera in camera_objects.items():
        scene.camera = camera
        for tick in review_ticks:
            scene.frame_set(tick_frame(tick))
            bpy.context.view_layer.update()
            scene.render.filepath = str(OUT / f"{view}_tick{tick:02d}.png")
            bpy.ops.render.render(write_still=True)

    measurements = []
    for tick, target_map in contact_targets.items():
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        gaps = {}
        for label, target in target_map.items():
            elbow = left_elbow if label.startswith("left") else right_elbow
            gaps[label] = round((world_point(elbow, PALM_LOCAL) - target).length, 5)
        measurements.append({
            "tick": tick,
            "palmGaps": gaps,
            "maxPalmGap": round(max(gaps.values()), 5),
            "torsoForwardDegrees": round(math.degrees(torso.rotation_euler.x), 3),
        })

    anchor_samples = []
    for tick in (12, 24, 30, 36, 48, 64, 80):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        anchor_samples.append({
            "tick": tick,
            "drift": round((world_bag.matrix_world.translation - BAG_ANCHOR).length, 7),
        })

    gaze_samples = []
    for tick, target in head_targets.items():
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        eye_forward = head.matrix_world.to_3x3() @ Vector((0.0, -1.0, 0.0))
        target_direction = Vector(target) - head.matrix_world.translation
        gaze_samples.append({
            "tick": tick,
            "target": [round(axis, 4) for axis in target],
            "gazeErrorDegrees": round(math.degrees(eye_forward.angle(target_direction)), 4),
        })

    max_gap = max(row["maxPalmGap"] for row in measurements)
    setdown_lean = next(row["torsoForwardDegrees"] for row in measurements
                        if row["tick"] == 12)
    # Set-down is deliberately separate from heavy-action effort, but it is a
    # hard gate too: this exact key must never read as a backward bag drop.
    effort_ticks = (24, 30, 31, 36, 48)
    min_lean = min(row["torsoForwardDegrees"] for row in measurements
                   if row["tick"] in effort_ticks)
    max_anchor_drift = max(row["drift"] for row in anchor_samples)
    max_gaze_error = max(row["gazeErrorDegrees"] for row in gaze_samples)
    checks = {
        "palmContactsPass": max_gap <= MAX_PALM_GAP,
        "setDownForwardLeanPass": setdown_lean >= MIN_FORWARD_LEAN,
        "forwardLeanPass": min_lean >= MIN_FORWARD_LEAN,
        "worldBagAnchorPass": max_anchor_drift <= 0.0001,
        "targetFacingPass": max_gaze_error <= MAX_GAZE_ERROR,
        "candidateOnly": True,
    }
    payload = {
        "name": "BAG_TO_CHEST_UNLOAD_CANDIDATE",
        "status": "Candidate — offline Blender checks only; native Minecraft pending",
        "candidateHash": CANDIDATE_HASH,
        "evidenceRoot": str(OUT),
        "fps": FPS,
        "durationTicks": END_TICK,
        "durationSeconds": END_TICK / FPS,
        "ownershipCommitTicks": {"bagWorldAnchor": 12, "bagToWorker": 30,
                                 "chestOpen": 36, "workerToChest": 48,
                                 "chestClose": 64},
        "limits": {"maxPalmGap": MAX_PALM_GAP,
                   "minForwardLeanDegrees": MIN_FORWARD_LEAN,
                   "maxWorldBagDrift": 0.0001,
                   "maxGazeErrorDegrees": MAX_GAZE_ERROR},
        "measurements": measurements,
        "setDownForwardLeanDegrees": setdown_lean,
        "worldBagAnchorSamples": anchor_samples,
        "gazeSamples": gaze_samples,
        "checks": checks,
        "reviewTicks": list(review_ticks),
        "reviewViews": list(cameras),
    }
    (OUT / "contact_report.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    scene.camera = camera_objects["front34"]
    scene.frame_set(1)
    bpy.ops.wm.save_as_mainfile(filepath=str(BLEND))
    # `build/` is disposable under Gradle clean. Preserve source-backed proof
    # in a content-addressed QA folder along with the exact contract it used.
    shutil.copy2(CONTRACT, OUT / CONTRACT.name)
    shutil.copy2(Path(__file__), OUT / Path(__file__).name)
    shutil.copy2(BLEND, OUT / BLEND.name)
    evidence_files = sorted((OUT / name for name in ["contact_report.json", CONTRACT.name,
                                                      Path(__file__).name, BLEND.name]),
                            key=lambda path: path.name)
    evidence_files.extend(sorted(OUT.glob("*.png")))
    manifest = {
        "kind": "hearthstead-blender-evidence",
        "animation": "BAG_TO_CHEST_UNLOAD_CANDIDATE",
        "candidateHash": CANDIDATE_HASH,
        "sourceSha256": {
            Path(__file__).name: sha256(Path(__file__)),
            CONTRACT.name: sha256(CONTRACT),
            BLEND.name: sha256(BLEND),
        },
        "reviewViews": list(cameras),
        "reviewTicks": list(review_ticks),
        "checks": checks,
        "evidenceSha256": {path.name: sha256(path) for path in evidence_files},
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n",
                                         encoding="utf-8")
    print(f"HEARTHSTEAD_BLEND={BLEND}")
    print(f"HEARTHSTEAD_REPORT={OUT / 'contact_report.json'}")
    print(f"HEARTHSTEAD_MANIFEST={OUT / 'manifest.json'}")
    print(f"HEARTHSTEAD_RENDERS={len(cameras) * len(review_ticks)}")


if __name__ == "__main__":
    main()
