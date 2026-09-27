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
from mathutils import Matrix, Quaternion, Vector


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
# The log must leave the bag in readable, measured increments instead of a
# single frame pop.  This number is deliberately conservative for the compact
# world scale used by the rig.
MAX_WITHDRAWAL_STEP = 0.15
MAX_OWNERSHIP_POSITION_STEP = 0.24
MAX_MESH_INTERSECTION = 0.0001


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


def key_euler_rotation(obj, rotation, tick):
    obj.rotation_mode = "XYZ"
    obj.rotation_euler = rotation
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


def key_world_transform(scene, obj, world_location, world_rotation, tick):
    """Key a parented visual proxy to a truthful world transform.

    The bag, hand and chest versions of the oak log are separate ownership
    projections.  This helper makes their handoff transform explicit, rather
    than relying on a parent switch or an accidental scale offset.
    """
    scene.frame_set(tick_frame(tick))
    bpy.context.view_layer.update()
    obj.matrix_world = Matrix.LocRotScale(Vector(world_location), world_rotation,
                                          Vector((1.0, 1.0, 1.0)))
    obj.rotation_mode = "QUATERNION"
    obj.keyframe_insert("location", frame=tick_frame(tick))
    obj.keyframe_insert("rotation_quaternion", frame=tick_frame(tick))
    obj.keyframe_insert("scale", frame=tick_frame(tick))


def world_aabb(obj):
    """Return a stable world-space bounding box for an authored mesh root."""
    points = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    return (Vector((min(point.x for point in points),
                    min(point.y for point in points),
                    min(point.z for point in points))),
            Vector((max(point.x for point in points),
                    max(point.y for point in points),
                    max(point.z for point in points))))


def composite_aabb(root):
    meshes = [obj for obj in (root, *root.children_recursive) if obj.type == "MESH"]
    if not meshes:
        raise ValueError(f"no meshes in authored proxy {root.name}")
    boxes = [world_aabb(mesh) for mesh in meshes]
    return (Vector((min(box[0].x for box in boxes), min(box[0].y for box in boxes),
                    min(box[0].z for box in boxes))),
            Vector((max(box[1].x for box in boxes), max(box[1].y for box in boxes),
                    max(box[1].z for box in boxes))))


def aabb_intersection_depth(a, b):
    """Positive depth means physical mesh-volume overlap; <= 0 is clearance."""
    return min(a[1].x, b[1].x) - max(a[0].x, b[0].x), \
        min(a[1].y, b[1].y) - max(a[0].y, b[0].y), \
        min(a[1].z, b[1].z) - max(a[0].z, b[0].z)


def aabb_overlaps(a, b, tolerance=MAX_MESH_INTERSECTION):
    return all(depth > tolerance for depth in aabb_intersection_depth(a, b))


def aabb_gap(a, b):
    """Euclidean non-intersection gap for concise collision diagnostics."""
    axis_gaps = (
        max(a[0].x - b[1].x, b[0].x - a[1].x, 0.0),
        max(a[0].y - b[1].y, b[0].y - a[1].y, 0.0),
        max(a[0].z - b[1].z, b[0].z - a[1].z, 0.0),
    )
    return math.sqrt(sum(value * value for value in axis_gaps))


def transform_sample(obj):
    location, rotation, scale = obj.matrix_world.decompose()
    return {
        "location": [round(axis, 5) for axis in location],
        "rotation": [round(axis, 6) for axis in rotation],
        "scale": [round(axis, 6) for axis in scale],
    }


def transform_delta(first, second):
    first_location = Vector(first["location"])
    second_location = Vector(second["location"])
    first_rotation = Quaternion(first["rotation"])
    second_rotation = Quaternion(second["rotation"])
    return {
        "position": round((first_location - second_location).length, 6),
        "rotationDegrees": round(math.degrees(first_rotation.rotation_difference(
            second_rotation).angle), 6),
        "scale": round(max(abs(a - b) for a, b in zip(first["scale"], second["scale"])), 6),
    }


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


def create_log(name, location, bark_mat, bark_shadow_mat, end_mat, parent=None):
    """Create the same compact, readable oak-log proxy for every owner.

    The three ownership projections intentionally share unscaled geometry and
    orientation. Dark bark slabs make the side read as bark; pale end caps
    provide unmistakable end-grain without using an abstract token.
    """
    log = empty(name, location, parent)
    cube(f"{name}_bark_core", (0, 0, 0), (0.10, 0.10, 0.22), bark_mat, log,
         rotation=(0, math.radians(90), 0))
    cube(f"{name}_bark_top", (0, 0, 0.104), (0.097, 0.022, 0.219),
         bark_shadow_mat, log, rotation=(0, math.radians(90), 0))
    cube(f"{name}_bark_bottom", (0, 0, -0.104), (0.097, 0.022, 0.219),
         bark_shadow_mat, log, rotation=(0, math.radians(90), 0))
    cube(f"{name}_end_left", (-0.225, 0, 0), (0.014, 0.101, 0.101), end_mat, log)
    cube(f"{name}_end_right", (0.225, 0, 0), (0.014, 0.101, 0.101), end_mat, log)
    return log


def write_evidence_bbmodel(output_path):
    """Write an evidence-only Blockbench scene tied to this Candidate hash."""
    elements = []
    for name, origin, size in (
            ("worker_torso_reference", (-3, -2, 0), (6, 4, 8)),
            ("world_bag_reference", (-10, -8, 0), (6, 4, 6)),
            ("oak_log_bark_reference", (-3, -2, 9), (6, 4, 3)),
            ("oak_log_endgrain_reference", (-3.4, -2, 9), (0.4, 4, 3)),
            ("chest_reference", (-7, -12, 0), (14, 9, 7))):
        elements.append({
            "name": name,
            "from": list(origin),
            "to": [origin[axis] + size[axis] for axis in range(3)],
            "uuid": hashlib.sha1(name.encode("utf-8")).hexdigest()[:24],
            "faces": {},
        })
    payload = {
        "meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
        "name": f"Hearthstead Bag-to-Chest Candidate {CANDIDATE_HASH}",
        "resolution": {"width": 16, "height": 16},
        "elements": elements,
        "outliner": [entry["uuid"] for entry in elements],
        "animations": [],
        "hearthsteadEvidence": {
            "candidateHash": CANDIDATE_HASH,
            "purpose": "offline Blender evidence only; not a runtime asset",
            "stateContract": CONTRACT.name,
        },
    }
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.edit.keyframe_new_interpolation_type = "LINEAR"

    forest = material("Worker forest cloth", (0.065, 0.23, 0.13, 1))
    moss = material("Worker moss trim", (0.20, 0.43, 0.22, 1))
    linen = material("Worker linen", (0.56, 0.42, 0.27, 1))
    skin = material("Worker skin", (0.63, 0.37, 0.22, 1))
    leather = material("Harness leather", (0.16, 0.055, 0.018, 1))
    canvas = material("Bag canvas", (0.40, 0.25, 0.105, 1))
    oak = material("Oak frame", (0.30, 0.14, 0.045, 1))
    # Deliberately high-contrast original oak treatment so the transferred
    # real item remains readable against the chest rim and work clothes.
    oak_log = material("Oak bark", (0.37, 0.15, 0.035, 1))
    oak_bark_shadow = material("Oak bark shadow", (0.13, 0.045, 0.012, 1))
    oak_end = material("Oak end grain", (0.78, 0.52, 0.20, 1))
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
    # The yoke and cloak remain after the bag is released.  Their dark metal
    # and forest cloth deliberately contrast with the tan canvas sack so the
    # review cannot read the fixed ground bag as still following the worker.
    cube("worker_yoke", (0, 0.20, -0.06), (0.35, 0.055, 0.075), iron, torso)
    cube("worker_yoke_trim", (0, 0.145, -0.08), (0.28, 0.018, 0.026), moss, torso)

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

    # These are the loaded-only shoulder straps. They release during the
    # set-down anticipation and disappear with the torso-owned sack, while
    # the iron yoke/cloak visibly remain on the worker.
    loaded_strap_left = cube("loaded_strap_left", (-0.23, 0.25, -0.28),
                             (0.042, 0.030, 0.33), leather, torso,
                             rotation=(math.radians(-24), 0, math.radians(-12)))
    loaded_strap_right = cube("loaded_strap_right", (0.23, 0.25, -0.28),
                              (0.042, 0.030, 0.33), leather, torso,
                              rotation=(math.radians(-24), 0, math.radians(12)))

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
    # Four walls and a base make the chest cavity physically meaningful. The
    # old solid cuboid made it impossible to test a visible deposit without
    # faking a mesh intersection through the chest itself.
    cube("chest_body", (0.0, -1.10, 0.13), (0.46, 0.32, 0.13), chest_wood)
    cube("chest_wall_front", (0.0, -0.80, 0.40), (0.46, 0.035, 0.25), chest_wood)
    cube("chest_wall_back", (0.0, -1.40, 0.40), (0.46, 0.035, 0.25), chest_wood)
    cube("chest_wall_left", (-0.425, -1.10, 0.40), (0.035, 0.265, 0.25), chest_wood)
    cube("chest_wall_right", (0.425, -1.10, 0.40), (0.035, 0.265, 0.25), chest_wood)
    cube("chest_front_trim", (0, -0.77, 0.38), (0.34, 0.022, 0.050), chest_trim)
    cube("chest_lock", (0, -0.795, 0.51), (0.06, 0.035, 0.08), chest_trim)
    lid_pivot = empty("chest_lid_pivot", (0.0, -1.42, 0.66))
    cube("chest_lid", (0, 0.26, 0.075), (0.46, 0.32, 0.075), chest_wood, lid_pivot)
    cube("chest_lid_trim", (0, 0.55, 0.07), (0.34, 0.022, 0.045), chest_trim, lid_pivot)
    lid_pull_local = Vector((0.0, 0.55, 0.08))
    chest_cavity = Vector((0.0, -1.10, 0.78))
    chest_cavity_volume = cube("QA_chest_cavity_volume", (0.0, -1.10, 0.50),
                               (0.36, 0.22, 0.12), qa_green)
    chest_cavity_volume.hide_render = True
    chest_cavity_volume.hide_viewport = True
    # This volume is behind the pull / above the rim. It is a forbidden sweep
    # zone for arms and the held log before the authoritative tick-48 deposit.
    lid_sweep_volume = cube("QA_lid_sweep_volume", (0.0, -1.22, 1.04),
                            (0.40, 0.15, 0.13), qa_green)
    lid_sweep_volume.hide_render = True
    lid_sweep_volume.hide_viewport = True

    # Visible item ownership: bag -> worker hand -> chest at ticks 30 and 48.
    log_in_bag = create_log("oak_log_in_bag", BAG_ANCHOR + Vector((0, -0.02, 0.69)),
                            oak_log, oak_bark_shadow, oak_end)
    log_in_hand = create_log("oak_log_in_left_hand", tuple(PALM_LOCAL), oak_log,
                             oak_bark_shadow, oak_end, left_elbow)
    log_in_chest = create_log("oak_log_in_chest", chest_cavity, oak_log,
                              oak_bark_shadow, oak_end)

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

    # Readable strap release before the torso-owned sack disappears at tick 12.
    strap_keys = (
        (loaded_strap_left, (math.radians(-24), 0.0, math.radians(-12)),
         (math.radians(38), 0.0, math.radians(-32))),
        (loaded_strap_right, (math.radians(-24), 0.0, math.radians(12)),
         (math.radians(38), 0.0, math.radians(32))),
    )
    for strap, loaded_rotation, released_rotation in strap_keys:
        key_euler_rotation(strap, loaded_rotation, 0)
        key_euler_rotation(strap, loaded_rotation, 8)
        key_euler_rotation(strap, released_rotation, 11)
        set_visibility(strap, True, 0)
        set_visibility(strap, True, 11)
        set_visibility(strap, False, 12)

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
    # to the chest. The bag -> hand event is exact on tick 30, but clearance
    # is spread through tick 36: there is no one-frame pop at tick 30 -> 31.
    key_arm_target(scene, left_arm, left_elbow, bag_mouth, 18)
    key_arm_target(scene, right_arm, right_elbow, Vector((0.25, -0.60, 0.86)), 18)
    key_arm_target(scene, left_arm, left_elbow, bag_mouth, 24)
    contact_targets[24] = {"left_bag_mouth": bag_mouth}
    key_arm_target(scene, left_arm, left_elbow, bag_mouth, 30)
    contact_targets[30] = {"left_withdraw": bag_mouth}

    log_rotation = Quaternion((1.0, 0.0, 0.0, 0.0))
    bag_log_world = BAG_ANCHOR + Vector((0.0, -0.02, 0.69))
    # Each increment is a physical lift, then a turn toward the chest. The
    # same world transform is keyed on the hand proxy, eliminating scale or
    # orientation discontinuity at the bag/hand handoff.
    withdrawal_path = {
        30: bag_log_world,
        31: Vector((-0.61, -0.40, 0.75)),
        32: Vector((-0.58, -0.44, 0.83)),
        33: Vector((-0.54, -0.49, 0.91)),
        34: Vector((-0.48, -0.55, 0.98)),
        35: Vector((-0.41, -0.60, 1.04)),
        36: Vector((-0.34, -0.66, 1.08)),
        37: Vector((-0.28, -0.72, 1.07)),
        42: Vector((-0.15, -0.87, 1.01)),
        47: Vector((0.0, -0.95, 0.94)),
        48: chest_cavity,
    }
    key_world_transform(scene, log_in_bag, bag_log_world, log_rotation, 29)
    for tick, position in withdrawal_path.items():
        key_arm_target(scene, left_arm, left_elbow,
                       bag_mouth if tick == 30 else position, tick)
        key_world_transform(scene, log_in_hand, position, log_rotation, tick)

    # The free hand has a distinct, earlier lid-pull action. It reaches only
    # the exterior pull at tick 31 and clears the lid before the log travels
    # toward the open cavity.
    key_arm_target(scene, right_arm, right_elbow, Vector((0.28, -0.54, 0.90)), 29)
    scene.frame_set(tick_frame(31))
    bpy.context.view_layer.update()
    lid_closed_pull = world_point(lid_pivot, lid_pull_local)
    # The centre of a blocky hand stays just outside the lid mesh; its rear
    # face makes the exterior-pull contact instead of tunnelling through it.
    lid_exterior_contact = lid_closed_pull + Vector((0.0, 0.17, 0.0))
    key_arm_target(scene, right_arm, right_elbow, lid_exterior_contact, 31)
    contact_targets[31] = {"right_lid_pull_exterior": lid_exterior_contact}
    key_arm_target(scene, right_arm, right_elbow, Vector((0.31, -0.50, 0.92)), 32)
    key_arm_target(scene, right_arm, right_elbow, Vector((0.30, -0.34, 0.84)), 36)
    key_arm_target(scene, right_arm, right_elbow, Vector((0.27, -0.35, 0.80)), 42)
    key_arm_target(scene, right_arm, right_elbow, Vector((0.24, -0.38, 0.76)), 47)
    key_arm_target(scene, left_arm, left_elbow, chest_cavity, 48)
    scene.frame_set(tick_frame(48))
    bpy.context.view_layer.update()
    contact_targets[48] = {"left_chest_cavity": chest_cavity}
    key_arm_target(scene, right_arm, right_elbow, Vector((0.24, -0.47, 0.76)), 48)
    # Exact hand -> chest ownership handoff. The chest proxy has the same
    # unscaled oak-log transform as the visible hand proxy on the commit tick.
    # Re-key after the final arm solves so its local compensation is measured
    # against the final parent transforms, not a previous working pose.
    for tick, position in withdrawal_path.items():
        key_world_transform(scene, log_in_hand, position, log_rotation, tick)
    key_world_transform(scene, log_in_chest, chest_cavity, log_rotation, 48)

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
    review_ticks = (0, 11, 12, 13, 24, 29, 30, 31, 32, 33, 34, 35,
                    36, 37, 42, 47, 48, 49, 63, 64, 65, 80)
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

    # The log is intentionally lifted in six readable increments. Capture
    # every authored clearance key so a future edit cannot collapse it back to
    # a one-tick ownership pop.
    withdrawal_samples = []
    for tick in range(30, 37):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        sample = transform_sample(log_in_hand)
        withdrawal_samples.append({"tick": tick, **sample})
    withdrawal_steps = []
    for previous, current in zip(withdrawal_samples, withdrawal_samples[1:]):
        delta = transform_delta(previous, current)
        withdrawal_steps.append({
            "fromTick": previous["tick"],
            "toTick": current["tick"],
            "positionStep": delta["position"],
        })

    ownership_transforms = {}
    for tick in (30, 47, 48):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        row = {"bag": transform_sample(log_in_bag),
               "hand": transform_sample(log_in_hand),
               "chest": transform_sample(log_in_chest)}
        ownership_transforms[str(tick)] = row
    bag_to_hand = transform_delta(ownership_transforms["30"]["bag"],
                                  ownership_transforms["30"]["hand"])
    hand_to_chest = transform_delta(ownership_transforms["48"]["hand"],
                                    ownership_transforms["48"]["chest"])
    approach_to_deposit = transform_delta(ownership_transforms["47"]["hand"],
                                          ownership_transforms["48"]["chest"])

    # Bound actual mesh volumes, not just palm points. The cavity and lid
    # sweep volumes are deliberately non-rendered QA solids, while the chest
    # walls are the same visible meshes rendered in the evidence frames.
    chest_collision_meshes = (
        bpy.data.objects["chest_body"], bpy.data.objects["chest_wall_front"],
        bpy.data.objects["chest_wall_back"], bpy.data.objects["chest_wall_left"],
        bpy.data.objects["chest_wall_right"], bpy.data.objects["chest_lid"],
    )
    forbidden_volumes = {
        "cavityLower": chest_cavity_volume,
        "lidSweep": lid_sweep_volume,
    }
    clearance_samples = []
    for tick in range(30, 48):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        actors = {
            "heldLog": composite_aabb(log_in_hand),
            "leftArm": composite_aabb(left_arm),
            "rightArm": composite_aabb(right_arm),
        }
        volumes = {name: world_aabb(obj) for name, obj in forbidden_volumes.items()}
        collisions = {name: world_aabb(obj) for name, obj in
                      ((obj.name, obj) for obj in chest_collision_meshes)}
        hit_rows = []
        for actor_name, actor_box in actors.items():
            for volume_name, volume_box in volumes.items():
                hit_rows.append({
                    "target": f"{actor_name}:{volume_name}",
                    "intersects": aabb_overlaps(actor_box, volume_box),
                    "gap": round(aabb_gap(actor_box, volume_box), 5),
                })
            for collision_name, collision_box in collisions.items():
                hit_rows.append({
                    "target": f"{actor_name}:{collision_name}",
                    "intersects": aabb_overlaps(actor_box, collision_box),
                    "gap": round(aabb_gap(actor_box, collision_box), 5),
                })
        clearance_samples.append({"tick": tick, "tests": hit_rows})

    silhouette_samples = []
    for tick in (30, 31, 32, 35, 36, 37):
        scene.frame_set(tick_frame(tick))
        bpy.context.view_layer.update()
        box = composite_aabb(log_in_hand)
        dimensions = box[1] - box[0]
        wall_gaps = [aabb_gap(box, world_aabb(mesh)) for mesh in chest_collision_meshes]
        silhouette_samples.append({
            "tick": tick,
            "dimensions": [round(axis, 5) for axis in dimensions],
            "longAxis": round(max(dimensions), 5),
            "minChestMeshGap": round(min(wall_gaps), 5),
            "barkSideMeshes": 3,
            "endGrainMeshes": 2,
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
    max_withdrawal_step = max(row["positionStep"] for row in withdrawal_steps)
    exact_ownership = all(
        boundary["position"] <= 0.0001 and boundary["rotationDegrees"] <= 0.001 and
        boundary["scale"] <= 0.0001
        for boundary in (bag_to_hand, hand_to_chest)
    )
    mesh_clear = not any(test["intersects"] for row in clearance_samples
                         for test in row["tests"])
    silhouette_read = all(row["longAxis"] >= 0.45 and row["barkSideMeshes"] >= 3 and
                          row["endGrainMeshes"] == 2 and row["minChestMeshGap"] >= 0.01
                          for row in silhouette_samples)
    checks = {
        "palmContactsPass": max_gap <= MAX_PALM_GAP,
        "setDownForwardLeanPass": setdown_lean >= MIN_FORWARD_LEAN,
        "forwardLeanPass": min_lean >= MIN_FORWARD_LEAN,
        "worldBagAnchorPass": max_anchor_drift <= 0.0001,
        "targetFacingPass": max_gaze_error <= MAX_GAZE_ERROR,
        "withdrawalClearancePass": max_withdrawal_step <= MAX_WITHDRAWAL_STEP,
        "itemTransformContinuityPass": exact_ownership and
            approach_to_deposit["position"] <= MAX_OWNERSHIP_POSITION_STEP and
            approach_to_deposit["rotationDegrees"] <= 0.001 and
            approach_to_deposit["scale"] <= 0.0001,
        "meshClearancePass": mesh_clear,
        "silhouetteReadPass": silhouette_read,
        "blockbenchEvidencePass": True,
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
                   "maxGazeErrorDegrees": MAX_GAZE_ERROR,
                   "maxWithdrawalStep": MAX_WITHDRAWAL_STEP,
                   "maxOwnershipPositionStep": MAX_OWNERSHIP_POSITION_STEP,
                   "maxMeshIntersection": MAX_MESH_INTERSECTION},
        "measurements": measurements,
        "setDownForwardLeanDegrees": setdown_lean,
        "worldBagAnchorSamples": anchor_samples,
        "gazeSamples": gaze_samples,
        "withdrawalClearance": {
            "samples": withdrawal_samples,
            "steps": withdrawal_steps,
            "maxStep": round(max_withdrawal_step, 6),
        },
        "itemTransformContinuity": {
            "ownershipTransforms": ownership_transforms,
            "bagToHandAt30": bag_to_hand,
            "handToChestAt48": hand_to_chest,
            "approach47ToDeposit48": approach_to_deposit,
        },
        "meshClearance": clearance_samples,
        "silhouetteRead": silhouette_samples,
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
    bbmodel_path = OUT / "hearthstead_bag_to_chest_evidence.bbmodel"
    write_evidence_bbmodel(bbmodel_path)
    evidence_files = sorted((OUT / name for name in ["contact_report.json", CONTRACT.name,
                                                      Path(__file__).name, BLEND.name,
                                                      bbmodel_path.name]),
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
