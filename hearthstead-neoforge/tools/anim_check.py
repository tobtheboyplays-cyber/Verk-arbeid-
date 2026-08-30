#!/usr/bin/env python3
"""Static QA for the animation library files (SettlerAnimations.java,
RaiderAnimations.java, ...): parses every keyframe channel and checks it
against docs/ANIMATION_CATALOGUE.md's §17 assertion list -- structural
sanity (bone whitelist, tick grid, loop closure, amplitude caps, duplicate
channels), the sound-sync contract (accent-frame keyframe exists, matches
the goal's tick-modulo math, the sound exists in sounds.json), and craft
rules (work clips have legs, carry clips lock their arms, one-shots return
to neutral, catalogue coverage).

No client boot required -- pure source parsing, so this stays in the fast
gate (tools/hearthstead-qa animation).

Adding a THIRD animation file (or a fourth, ...): append one entry to
ANIMATION_SOURCES below with that file's own bone whitelist and per-file
exemption sets. Every structural/craft check in this module (§17.1-17.2,
17.4 minus the settler-only sound/damping tables) runs once per registered
source and is scoped entirely by that entry's own config -- nothing in
main() is hardcoded to a specific file or class any more. The sound-sync
tables (SOUND_CONTRACTS, ENTITY_SOUND_CONTRACTS) key off clip NAME, not
file, and already check every clip from every source against one merged
`defs` map, so a new file's clips need only a new table row, not a new
table."""
import json
import math
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(__file__), "..")
MODEL_DIR = os.path.join(ROOT, "src/main/java/com/hearthstead/client/model")
AI_DIR = os.path.join(ROOT, "src/main/java/com/hearthstead/entity/ai")
SOUNDS_JSON = os.path.join(ROOT, "src/main/resources/assets/hearthstead/sounds.json")
CATALOGUE = os.path.join(os.path.dirname(__file__), "..",
                         "docs", "ANIMATION_CATALOGUE.md")
PROP_CONTRACT = os.path.join(os.path.dirname(__file__), "blockbench",
                             "prop_contract.json")
BB_RENDER = os.path.join(os.path.dirname(__file__), "blockbench",
                         "bb_render.mjs")
SETTLER_ENTITY_SOURCE = os.path.join(
    ROOT, "src/main/java/com/hearthstead/entity/SettlerEntity.java")
SETTLER_ACTIVITY_SOURCE = os.path.join(
    ROOT, "src/main/java/com/hearthstead/entity/SettlerActivity.java")
SETTLER_MODEL_SOURCE = os.path.join(
    ROOT, "src/main/java/com/hearthstead/client/model/SettlerModel.java")
LUMBER_CRAFT_GOAL_SOURCE = os.path.join(
    AI_DIR, "LumbererSelfCraftGoal.java")
LUMBER_CRAFT_SERVICE_SOURCE = os.path.join(
    AI_DIR, "LumbererSelfCraftingService.java")
SETTLER_RENDERER_SOURCE = os.path.join(
    ROOT, "src/main/java/com/hearthstead/client/render/SettlerRenderer.java")

SETTLER_BONES = {"root", "torso", "head", "right_arm", "left_arm",
                 "right_leg", "left_leg", "cloak"}
# The raider rig has no cloak bone (RaiderModel.createBodyLayer()); hood,
# helm and pauldron are visibility-toggled parts, never animation targets,
# the same rule the settler's hood/hat_brim already follow -- see
# RaiderAnimations.java's own header.
RAIDER_BONES = {"root", "torso", "head", "right_arm", "left_arm",
                "right_leg", "left_leg"}

# One registered animation source per model file. Every key below is a
# per-file version of the settler-era globals that used to be hardcoded to
# one path -- see each field's original comment (still accurate, just now
# scoped per entry instead of implicitly "the one file").
ANIMATION_SOURCES = [
    {
        "label": "settler",
        "path": os.path.join(MODEL_DIR, "SettlerAnimations.java"),
        "bones": SETTLER_BONES,
        "has_cloak": True,
        # Clips exempt from "every clip touches >= 3 bones": genuinely 1-2
        # bone additive layers by design.
        "bone_count_exempt": {
            "GUARD_PATROL", "ARCHER_PATROL", "HAUL_LOG", "HAUL_LOG_HEAVY",
            "FARMER_CARRY"
        },
        # Clips exempt from "cloak motion on loops >= 1s": the load pins the
        # cloak still, per §0.5.
        "cloak_pin_allowlist": {"SLEEP_IN_BED", "SHIELD_BLOCK"},
        # Clips exempt from "work clips have legs": layers, or clips the
        # catalogue explicitly scopes to arms/torso/head/cloak only (EAT:
        # "Kept as-is... add only cloak, root" -- no leg instruction was ever
        # given). COURIER_CARRY is the same shape as GUARD_PATROL: catalogue
        # §5.2 says its legs are "inherited from WALK_LADEN; do not author"
        # -- an arm+torso+head+cloak+root overlay by design.
        "legs_exempt": {"IDLE", "GUARD_PATROL", "ARCHER_PATROL", "EAT", "HAUL_LOG",
                        "HAUL_LOG_HEAVY", "FARMER_CARRY",
                        "COURIER_CARRY", "MELEE"},
        # One-shots allowed to end away from their start pose. COURIER_LIFT
        # arrives at the carry handoff pose (catalogue §5, ~line 862) and
        # COURIER_SET_DOWN departs from it -- by design, per §5.1/§5.3.
        "ends_in_pose_allowlist": {
            "COURIER_LIFT", "COURIER_SET_DOWN",
            # Reaction-first by design: authoritative damage is already
            # accepted at t=0, and the final frame hands back to low guard.
            "GUARD_HIT_REACT",
            "GROUND_ITEM_PICKUP", "WORK_CONTAINER_STOW",
            "WORK_CONTAINER_UP",
        },
        # Clips declared as carry/arm layers (§16.2) -- must lock arm
        # rotation to <= 6 degrees of travel. HAUL_LOG is an arms-only tool
        # hold over speed-coupled WALK_LADEN: the visible sack and spine own
        # the load, while the axe must stay below the shoulder line.
        # COURIER_CARRY is the CRATE-grammar carry clip: its own text says
        # "Total travel: 3 degrees. The arms are a clamp." -- tighter than
        # the checker's 6-degree limit, so this only confirms the clamp
        # holds.
        "carry_layer_clips": {
            "HAUL_LOG", "HAUL_LOG_HEAVY", "FARMER_CARRY", "COURIER_CARRY"
        },
        # Exact-bone contracts for deliberately tiny overlays. This is
        # stronger than merely exempting them from the generic bone/leg rule:
        # adding fixed-timer legs or torso back into HAUL_LOG must fail.
        "exact_overlay_bones": {
            "GUARD_PATROL": {"right_arm", "left_arm", "head", "torso"},
            "ARCHER_PATROL": {"right_arm", "left_arm", "head"},
            "HAUL_LOG": {"right_arm", "left_arm"},
            "HAUL_LOG_HEAVY": {"right_arm", "left_arm"},
            "FARMER_CARRY": {"right_arm", "left_arm"},
        },
        # Absolute posture bounds complement the travel limit below. The old
        # HAUL_LOG arm sat at -142deg but moved only a few degrees, so a span-
        # only gate incorrectly accepted the hand-over-head silhouette.
        "arm_posture_bounds": {
            "IDLE_LUMBERER": {
                "right_arm": (-25.0, 15.0),
                "left_arm": (-35.0, 20.0),
            },
            "HAUL_LOG": {
                "right_arm": (-35.0, 15.0),
                "left_arm": (-60.0, 30.0),
            },
            "HAUL_LOG_HEAVY": {
                "right_arm": (-35.0, 15.0),
                "left_arm": (-60.0, 30.0),
            },
            "FARMER_CARRY": {
                # The real MAINHAND hoe remains below the shoulder; the free
                # hand reaches forward to the front strap, never backwards
                # into the produce sack.
                "right_arm": (-35.0, 5.0),
                "left_arm": (35.0, 60.0),
            },
            "GATHER_LOG": {
                "right_arm": (-45.0, 5.0),
                "left_arm": (-85.0, 5.0),
            },
            "WORK_CONTAINER_DOWN": {
                "right_arm": (-45.0, 5.0),
                # This rig's positive X is the verified ground-facing reach;
                # the earlier negative values were the backward-bend defect.
                "left_arm": (-40.0, 90.0),
            },
            "GROUND_ITEM_PICKUP": {
                "right_arm": (-45.0, 5.0),
                "left_arm": (-60.0, 90.0),
            },
            "WORK_CONTAINER_STOW": {
                "right_arm": (-45.0, 5.0),
                "left_arm": (-60.0, 90.0),
            },
            "WORK_CONTAINER_UP": {
                "right_arm": (-45.0, 5.0),
                "left_arm": (-40.0, 85.0),
            },
            "WALK_CARRY_ITEM": {
                "right_arm": (-30.0, 5.0),
                # Side-view evidence proved this rig's negative X lifts the
                # offhand behind the spine. A visible item cradled in front
                # of the ribs uses the same positive-X reach direction as
                # pickup and stow, just shallower than ground contact.
                "left_arm": (40.0, 60.0),
            },
            # Combat arms are bounded by their truthful equipment roles.
            # These caps turn hand-over-crown regressions and a bow falling
            # back into the sword pose into deterministic failures.
            "GUARD_STANCE": {
                "right_arm": (-40.0, -20.0),
                "left_arm": (-35.0, -10.0),
            },
            "GUARD_PATROL": {
                "right_arm": (-40.0, -20.0),
                "left_arm": (-35.0, -10.0),
            },
            "MELEE": {
                "right_arm": (-95.0, 50.0),
                "left_arm": (-35.0, 25.0),
            },
            "GUARD_HIT_REACT": {
                "right_arm": (-55.0, -20.0),
                "left_arm": (-75.0, -10.0),
            },
            "SHIELD_BLOCK": {
                "right_arm": (-60.0, -35.0),
                "left_arm": (-115.0, -85.0),
            },
            "ARCHER_STANCE": {
                "right_arm": (60.0, 90.0),
                "left_arm": (60.0, 90.0),
            },
            "ARCHER_PATROL": {
                "right_arm": (-30.0, 0.0),
                "left_arm": (-40.0, 0.0),
            },
            "IDLE_ARCHER": {
                "right_arm": (-30.0, 0.0),
                "left_arm": (-40.0, 10.0),
            },
            # Transaction one-shots: a table/chest reach may approach
            # horizontal. Side-view contact evidence proves positive X is the
            # forward/table direction on this rig; the one negative table arm
            # interval is the authored pre-strike wind-up.
            "LUMBER_CRAFT": {
                "right_arm": (-40.0, 100.0),
                "left_arm": (0.0, 100.0),
            },
            "CRAFT_OUTPUT_STORE": {
                "right_arm": (0.0, 90.0),
                "left_arm": (0.0, 90.0),
            },
        },
    },
    {
        "label": "raider",
        "path": os.path.join(MODEL_DIR, "RaiderAnimations.java"),
        "bones": RAIDER_BONES,
        "has_cloak": False,
        "bone_count_exempt": set(),
        "cloak_pin_allowlist": set(),
        "legs_exempt": set(),
        "ends_in_pose_allowlist": set(),
        "carry_layer_clips": set(),
        "exact_overlay_bones": {},
        "arm_posture_bounds": {},
    },
]

# Head-tracking damping table (§17.4 check 24), for clips this phase gives a
# non-default damp value to. Cross-checked against SettlerModel.java's damp
# table by grepping for each literal below.
DAMPING_TABLE = {
    "SLEEP_IN_BED": 0.0,
    "SHIELD_BLOCK": 0.15,
    "CLIMB_LADDER": 0.3,
    "RUN_PANIC": 0.4,
    "REST": 0.25,
    "EAT": 0.25,
}

# Clips requiring a per-entity phase-offset or amplitude-jitter call site
# (§17.4 check 25).
PER_ENTITY_VARIATION_CLIPS = {"CELEBRATE", "SLEEP_IN_BED", "WAKE_STRETCH", "IDLE"}

# Every WORK_* activity is required to own a reachable client state and clip.
# This list is intentionally checked against the enum, so appending a new
# work activity without extending this mapping fails the fast gate. That is
# the exact regression shape that originally left WORK_CRAFT invisible.
ACTIVITY_ANIMATION_REACHABILITY = {
    "WORK_FARM": ("farmState", "FARM_TILL"),
    "WORK_CHOP": ("chopState", "CHOP"),
    "WORK_PLANT": ("plantState", "FARM_PLANT"),
    "WORK_HARVEST": ("harvestState", "FARM_HARVEST"),
    "WORK_WATER": ("waterState", "FARM_WATER"),
    "WORK_LIMB": ("limbState", "LIMB_BRANCHES"),
    "WORK_KNEAD": ("kneadState", "KNEAD"),
    "WORK_CLEAVE": ("cleaveState", "CLEAVE"),
    "WORK_STOKE": ("stokeState", "STOKE"),
    "WORK_HAMMER": ("hammerState", "HAMMER_ANVIL"),
    "WORK_SAW": ("sawState", "SAW"),
    "WORK_WEAVE": ("fineWorkState", "FINE_WORK"),
    "WORK_OVEN": ("ovenState", "OVEN_TEND"),
    "WORK_SOW": ("sowState", "SOW_BROADCAST"),
    "WORK_MINE": ("mineState", "MINE_PICK"),
    "WORK_STIR": ("stirState", "COOK_STIR"),
    "WORK_PLANE": ("planeState", "CARPENTER_PLANE"),
    "WORK_CHISEL": ("chiselState", "MASON_CHISEL"),
    "WORK_FLETCH": ("fletchState", "FLETCHER_FLETCH"),
    "WORK_SCRAPE": ("scrapeState", "TANNER_SCRAPE"),
    "WORK_SHEAR": ("shearState", "HERDER_SHEAR"),
    "WORK_FISH": ("fishState", "FISHER_CAST"),
    "WORK_HUNT": ("huntState", "HUNTER_LOOSE"),
    "WORK_CRAFT": ("craftState", "LUMBER_CRAFT"),
}
EXPLICIT_ACTIVITY_ANIMATION_REACHABILITY = {
    "STORE_CRAFT_OUTPUT": ("craftStoreState", "CRAFT_OUTPUT_STORE"),
}

# Sound-sync contract table (§17.3 check 13): one row per accent frame.
# (clip, bone, target, accent_seconds, sound_field, tick, period)
# sound_field is the ModSounds constant name; tick/period are the goal-side
# workTicks%period==tick this accent must match exactly.
SOUND_CONTRACTS = [
    ("FARM_TILL", "right_arm", "ROTATION", 0.60, "FARMER_WORK", 12, 30),
    ("FARM_PLANT", "left_arm", "ROTATION", 0.70, "SEED_PRESS", 14, 40),
    ("FARM_HARVEST", "right_arm", "ROTATION", 0.45, "CROP_PULL", 9, 36),
    ("FARM_WATER", "right_arm", "ROTATION", 0.80, "WATER_POUR", 16, 48),
    ("CHOP", "right_arm", "ROTATION", 0.55, "CHOP", 11, 20),
    ("LIMB_BRANCHES", "right_arm", "ROTATION", 0.30, "CHOP", 6, 26),
    ("HAUL_LOG", "right_arm", "ROTATION", 1.20, "SETTLER_HM", 24, 48),
]

# Sounds reused via SoundEvent pitch parameter rather than a new asset --
# the goal-side call for these plays an *existing* registered sound at a
# different pitch, so "sound existence" (check 15) still resolves correctly
# against sounds.json under the sound's own canonical key, not a synthetic
# one. LIMB_BRANCHES reuses CHOP; HAUL_LOG reuses SETTLER_HM.

# Sound-sync contracts whose trigger is NOT a single repeating "workTicks %
# period == tick" loop inside one AI_DIR goal file, so they don't fit
# SOUND_CONTRACTS' assumptions (period == the clip's own loop length; a
# single owning AI-goal file; a LINEAR "snap" keyframe). These cover: a
# throttled first-cycle-only vocal (RUN_PANIC), an every-Nth-cycle grunt on
# a super-cycle (WALK_LIMP), a server-side scheduler living in
# SettlerEntity.java rather than AI_DIR (all of these), and genuine
# one-shots triggered by a broadcast event rather than a modulo (SHIELD_BLOCK,
# CELEBRATE, WAKE_STRETCH). Each row is still checked for real: the clip has
# a keyframe at every listed accent second (any interpolation -- these are
# not all impact snaps, e.g. EAT/CELEBRATE's accents ride a continuous
# CATMULLROM motion on purpose), the sound resolves in sounds.json, and
# every tick constant is grepped -- by name, not by guessed value -- out of
# the Java file that actually owns it, so a value that drifts from this
# table fails loudly instead of silently.
# (clip, [accent_seconds...] or [] if the trigger isn't loop-relative,
#  sound_field, java_file relative to src/main/java/com/hearthstead,
#  [(tick_constant_name, expected_value), ...])
ENTITY_SOUND_CONTRACTS = [
    # FREQUENCY-ONLY accents (no accent_seconds): these two cannot be
    # phase-locked to their clip and must not claim to be. CLIMB_LADDER is
    # sampled from climbState's accumulated time, whose phase depends on
    # when the state started; WALK_LIMP is driven by animateWalk() from
    # limbSwing, i.e. distance travelled, so it has no time phase at all.
    # The constants are still checked -- the RATE is the contract.
    ("CLIMB_LADDER", [], "LADDER_CREAK", "entity/SettlerEntity.java",
     [("LADDER_CREAK_TICK_A", 5), ("LADDER_CREAK_TICK_B", 15),
      ("LADDER_CREAK_PERIOD", 20)]),
    ("WALK_LIMP", [], "SETTLER_HM", "entity/SettlerEntity.java",
     [("LIMP_GRUNT_TICK", 8)]),
    ("RUN_PANIC", [0.15], "SETTLER_PANIC", "entity/ai/SettlerPanicGoal.java",
     [("PANIC_YELP_TICK", 3)]),
    # PHASE-LOCKED ONE-SHOT: EV_MELEE starts the wind-up at tick zero; the
    # same one-use server ticket authorizes blade audio and damage at t=0.20.
    ("MELEE", [0.20], "BLADE_HIT", "entity/ai/GuardMeleeGoal.java",
     [("MELEE_CONTACT_TICK", 4)]),
    ("SHIELD_BLOCK", [], "SHIELD_THUD", "entity/SettlerEntity.java",
     [("SHIELD_THUD_DELAY", 2)]),
    ("CELEBRATE", [0.45, 1.10], "CHEER", "entity/SettlerEntity.java",
     [("CHEER_TICK_A", 9), ("CHEER_TICK_B", 22)]),
    ("WAKE_STRETCH", [1.20], "YAWN", "entity/SettlerEntity.java",
     [("WAKE_YAWN_TICK", 24)]),

    # Courier (catalogue §5 / §1.2). None of these fit SOUND_CONTRACTS above:
    # CourierWorkGoal.java gates its cycle with `workTicks % SORT_PERIOD !=
    # SORT_MOVE_TICK` (an early-return guard), not the `% period == tick`
    # form that table's regex looks for, and several of these sounds are not
    # tied to the clip whose activity is actually active when they fire --
    # see the honest per-row notes and the class-header comment in
    # SettlerAnimations.java for the full picture.
    #
    # FREQUENCY-ONLY (no accents=[] keyframe check -- rate is real, phase is
    # not, or the clip they're attached to never actually plays in-game):
    ("WALK_LADEN", [], "HAUL_STEP", "entity/ai/CourierWorkGoal.java",
     [("HAUL_STEP_PERIOD", 18)]),
    ("WALK_LADEN", [], "HAUL_STRAIN", "entity/ai/CourierWorkGoal.java",
     [("HAUL_STRAIN_PERIOD", 96)]),
    ("COURIER_LIFT", [], "CRATE_GRIP", "entity/ai/CourierWorkGoal.java",
     [("LIFT_GRIP_TICK", 12)]),
    ("COURIER_SET_DOWN", [], "CRATE_DOWN", "entity/ai/CourierWorkGoal.java",
     [("SET_DOWN_TICK", 12)]),
    # PHASE-LOCKED: workTicks and sortState's own clock both reset in the
    # same tick when Mode.SORTING starts at the warehouse, so tick 16 of a
    # 32-tick cycle really does land on this clip's own t=0.80s "place in
    # the chest" accent.
    ("COURIER_SORT", [0.80], "CHEST_STOW", "entity/ai/CourierWorkGoal.java",
     [("SORT_MOVE_TICK", 16), ("SORT_PERIOD", 32)]),
    # crate_creak and item_pickup are registered sounds (piece 4) that
    # CourierWorkGoal never calls playAt(...) for -- no row here, because
    # there is no tick constant to check and adding one would fabricate a
    # contract the code does not have. See the piece 3 report.

    # Raider (RaiderAnimations.java). Both FREQUENCY-ONLY, same shape as
    # CLIMB_LADDER/COURIER_LIFT above: each clip is triggered by a
    # broadcastEntityEvent the instant the real game event happens (a block
    # actually breaks; a stack actually leaves the chest), not by a fixed
    # delay from some earlier tick, so there is no accent-second keyframe to
    # phase-lock against. Unlike ticketed guard MELEE, these are
    # reaction-first presentations of an already accepted world change. The tick
    # constants are still real: RaiderBreachGoal/RaiderLootGoal's own swing
    # and grab cadence, cross-checked here so they cannot silently drift out
    # from under the trigger-site comments in those files.
    ("BREACH_SLAM", [], "CHOP", "entity/ai/RaiderBreachGoal.java",
     [("SWING_CONTACT", 11), ("SWING_PERIOD", 20)]),
    ("LOOT_SNATCH", [], "ITEM_PICKUP", "entity/ai/RaiderLootGoal.java",
     [("GRAB_PERIOD", 20)]),
]


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def _wrap_degrees(value):
    return (value + 180.0) % 360.0 - 180.0


def runtime_target_look(target, errors=None):
    """Compose the same body-yaw-relative look intent used by melee AI.

    ``MeleeAttackGoal`` makes the body face the target and its look control
    supplies ``netHeadYaw`` plus ``headPitch`` to ``SettlerModel``. The
    offline evidence must therefore derive those values from the physical
    target bearing; a convenient literal zero would silently accept the old
    side-on target and away-facing head.
    """
    sink = errors if errors is not None else []
    if not isinstance(target, dict):
        sink.append("MELEE target-bearing contract: target must be an object")
        return None

    def finite_vec3(name):
        value = target.get(name)
        if not isinstance(value, list) or len(value) != 3 \
                or any(not isinstance(item, (int, float))
                       or not math.isfinite(item) for item in value):
            sink.append(f"MELEE target-bearing contract: {name} must be a finite vec3")
            return None
        return [float(item) for item in value]

    forward = finite_vec3("actorForwardModel")
    center = finite_vec3("centerModelPixels")
    actor_eye = finite_vec3("actorEyeModelPixels")
    target_eye = finite_vec3("targetEyeModelPixels")
    body_yaw = target.get("bodyYawDegrees")
    yaw_limit = target.get("lookYawLimitDegrees")
    pitch_limit = target.get("lookPitchLimitDegrees")
    numeric = (body_yaw, yaw_limit, pitch_limit)
    if any(not isinstance(value, (int, float)) or not math.isfinite(value)
           for value in numeric):
        sink.append("MELEE target-bearing contract: body yaw and look limits "
                    "must be finite numbers")
        return None
    if not all((forward, center, actor_eye, target_eye)):
        return None
    if yaw_limit <= 0 or pitch_limit <= 0 or yaw_limit > 60 or pitch_limit > 60:
        sink.append("MELEE target-bearing contract: look limits must be in (0, 60]")
        return None

    forward_length = math.hypot(forward[0], forward[2])
    target_horizontal = math.hypot(center[0], center[2])
    if forward_length <= 1e-6 or target_horizontal <= 1e-6:
        sink.append("MELEE target-bearing contract: forward and target bearing "
                    "must be non-zero")
        return None

    forward_bearing = math.degrees(math.atan2(forward[0], -forward[2]))
    target_bearing = math.degrees(math.atan2(center[0], -center[2]))
    bearing_error = abs(_wrap_degrees(target_bearing - forward_bearing))
    body_error = abs(_wrap_degrees(float(body_yaw) - forward_bearing))
    relative_yaw = _wrap_degrees(target_bearing - float(body_yaw))
    net_head_yaw = max(-float(yaw_limit),
                       min(float(yaw_limit), relative_yaw))

    eye_dx = target_eye[0] - actor_eye[0]
    eye_dy = target_eye[1] - actor_eye[1]
    eye_dz = target_eye[2] - actor_eye[2]
    eye_horizontal = math.hypot(eye_dx, eye_dz)
    raw_pitch = -math.degrees(math.atan2(eye_dy, eye_horizontal))
    pitch = max(-float(pitch_limit), min(float(pitch_limit), raw_pitch))
    return {
        "forwardBearingDegrees": forward_bearing,
        "targetBearingDegrees": target_bearing,
        "bearingErrorDegrees": bearing_error,
        "bodyErrorDegrees": body_error,
        "bodyYawDegrees": float(body_yaw),
        "relativeTargetYawDegrees": relative_yaw,
        "netHeadYawDegrees": net_head_yaw,
        "rawPitchDegrees": raw_pitch,
        "headPitchDegrees": pitch,
    }


def check_melee_evidence_target_contract(target, errors):
    """Reject a cosmetic or off-bearing target before any screenshots exist."""
    if not isinstance(target, dict):
        errors.append("MELEE target-bearing contract: missing meleeEvidenceTarget")
        return None
    if target.get("role") != "primary_forward_attack_bearing":
        errors.append("MELEE target-bearing contract: primary target must be "
                      "labelled primary_forward_attack_bearing; lateral targets "
                      "are adversarial evidence only")

    look = runtime_target_look(target, errors)
    if look is None:
        return None
    maximum_error = target.get("maxPrimaryBearingErrorDegrees")
    if not isinstance(maximum_error, (int, float)) \
            or not math.isfinite(maximum_error) or maximum_error < 0 \
            or maximum_error > 0.5:
        errors.append("MELEE target-bearing contract: primary bearing tolerance "
                      "must be finite and no wider than 0.5deg")
        maximum_error = 0.5
    if look["bearingErrorDegrees"] > maximum_error + 1e-6:
        errors.append("MELEE target-bearing contract: physical target is "
                      f"{look['bearingErrorDegrees']:.2f}deg off primary attack "
                      "bearing; lateral targets are adversarial evidence only")
    if look["bodyErrorDegrees"] > maximum_error + 1e-6:
        errors.append("MELEE target-bearing contract: emulated body yaw is "
                      f"{look['bodyErrorDegrees']:.2f}deg off actor forward")
    if abs(look["relativeTargetYawDegrees"] - look["netHeadYawDegrees"]) > 1e-6:
        errors.append("MELEE target-bearing contract: target exceeds the real "
                      "MeleeAttackGoal netHeadYaw limit")
    if abs(look["rawPitchDegrees"] - look["headPitchDegrees"]) > 1e-6:
        errors.append("MELEE target-bearing contract: target exceeds the real "
                      "MeleeAttackGoal headPitch limit")

    size = target.get("sizeModelPixels")
    center = target.get("centerModelPixels")
    if size != [9.6, 28.8, 9.6]:
        errors.append("MELEE target-bearing contract: evidence target must retain "
                      "the Minecraft raider 9.6x28.8x9.6px body")
    elif isinstance(center, list) and len(center) == 3 \
            and abs(float(center[1]) - size[1] / 2.0) > 1e-6:
        errors.append("MELEE target-bearing contract: raider target must stand on "
                      "the same ground plane as the guard")

    exact_timing = {
        "contactSeconds": 0.20,
        "impactBandSeconds": [0.20, 0.25],
        "mustBeClearSeconds": [0.00, 0.05, 0.10, 0.15],
        "mustBeSeparatedSeconds": [0.30, 0.35, 0.40, 0.45, 0.50],
    }
    for name, expected in exact_timing.items():
        if target.get(name) != expected:
            errors.append(f"MELEE target-bearing contract: {name} must remain "
                          f"{expected} for exact T+4/t=.20 authority")
    minimum_travel = target.get("minimumCenterlineTravelPixels")
    if not isinstance(minimum_travel, (int, float)) \
            or not math.isfinite(minimum_travel) or minimum_travel < 0.75:
        errors.append("MELEE contact contract: oriented blade centreline must "
                      "travel at least 0.75px through the target")
    maximum_drift = target.get("maximumImpactLineDriftPixels")
    if not isinstance(maximum_drift, (int, float)) \
            or not math.isfinite(maximum_drift) or maximum_drift > 2.0 \
            or maximum_drift < 0:
        errors.append("MELEE contact contract: cross-phase impact midpoint drift "
                      "must stay within 2.0px")
    return look


def check_crafting_evidence_target_contract(target, errors):
    """Reject a cosmetic tabletop reconstruction before screenshots exist."""
    expected = {
        "role": "fixed_world_authority_reconstruction",
        "actorForwardModel": [0, 0, -1],
        "tableCenterModelPixels": [0, 8, -12],
        "tableSizeModelPixels": [16, 16, 16],
        "gridSpacingModelPixels": 3.6,
        "recipeId": "minecraft:wooden_axe",
        "recipeOccupiedSlots": [0, 1, 3, 4, 7],
        "recipeSlotItems": {
            "0": "minecraft:oak_planks",
            "1": "minecraft:oak_planks",
            "3": "minecraft:oak_planks",
            "4": "minecraft:stick",
            "7": "minecraft:stick",
        },
        "layoutContactSeconds": [0.2, 0.4, 0.6, 0.8, 0.9],
        "transformContactSeconds": 1.5,
        "pickupContactSeconds": 2.15,
        "tableBeatDurationSeconds": 2.4,
        "storageContactSeconds": 0.7,
        "storageBeatDurationSeconds": 1.2,
        "outputItem": "minecraft:wooden_axe",
        "worldPropsMustNotInheritActorTransform": True,
    }
    if target != expected:
        errors.append("CRAFT evidence contract: exact fixed-world 3x3 wooden-axe "
                      "layout/timing/output contract drifted")


def activity_animation_reachability_errors(activity_text, entity_text,
                                           model_text, defs,
                                           work_mapping=None,
                                           explicit_mapping=None):
    """Return source-level activity -> state -> clip reachability failures.

    Kept pure so its mutation test can prove that deleting WORK_CRAFT from
    the mapping is caught without booting Minecraft.
    """
    failures = []
    clean_activity = strip_comments(activity_text)
    enum_match = re.search(
        r'public\s+enum\s+SettlerActivity\s*\{(.*?)\n\s*public\s+static',
        clean_activity, re.S)
    if enum_match is None:
        return ["activity reachability: SettlerActivity enum body not found"]
    activities = set(re.findall(
        r'^\s*([A-Z][A-Z0-9_]*)\s*\(', enum_match.group(1), re.M))
    work_activities = {name for name in activities if name.startswith("WORK_")}
    work_mapping = (ACTIVITY_ANIMATION_REACHABILITY
                    if work_mapping is None else work_mapping)
    explicit_mapping = (EXPLICIT_ACTIVITY_ANIMATION_REACHABILITY
                        if explicit_mapping is None else explicit_mapping)

    missing_mapping = sorted(work_activities - set(work_mapping))
    stale_mapping = sorted(set(work_mapping) - work_activities)
    if missing_mapping:
        failures.append("activity reachability: WORK_* enum value(s) have no "
                        "state/clip contract: " + ", ".join(missing_mapping))
    if stale_mapping:
        failures.append("activity reachability: mapping names absent WORK_* "
                        "enum value(s): " + ", ".join(stale_mapping))
    for activity in explicit_mapping:
        if activity not in activities:
            failures.append(f"activity reachability: explicit activity {activity} "
                            "is absent from SettlerActivity")

    clean_entity = strip_comments(entity_text)
    clean_model = strip_comments(model_text)
    all_mapping = {**work_mapping, **explicit_mapping}
    for activity, (state, clip) in sorted(all_mapping.items()):
        if not re.search(rf'AnimationState\s+{re.escape(state)}\s*=\s*'
                         r'new\s+AnimationState\s*\(\s*\)', clean_entity):
            failures.append(f"activity reachability: {activity} declares no "
                            f"AnimationState {state}")
        state_gate = re.search(
            rf'{re.escape(state)}\.animateWhen\(\s*'
            rf'activity\s*==\s*SettlerActivity\.{re.escape(activity)}\b'
            r'.{0,160}?tickCount\s*\)\s*;', clean_entity, re.S)
        if state_gate is None:
            failures.append(f"activity reachability: {activity} never starts "
                            f"{state} from its live activity")
        model_call = re.search(
            rf'animate\(\s*entity\.{re.escape(state)}\s*,\s*'
            rf'SettlerAnimations\.{re.escape(clip)}\s*,\s*'
            r'ageInTicks(?:\s*\+\s*\(id\s*%\s*\d+\))?\s*\)\s*;',
            clean_model, re.S)
        if model_call is None:
            failures.append(f"activity reachability: {state} is not mapped "
                            f"to {clip} through an accepted animation clock")
        if activity in {"WORK_CRAFT", "STORE_CRAFT_OUTPUT"} and re.search(
                rf'animate\(\s*entity\.{re.escape(state)}\s*,\s*'
                rf'SettlerAnimations\.{re.escape(clip)}\s*,\s*'
                r'ageInTicks\s*\)\s*;', clean_model, re.S) is None:
            failures.append(f"activity reachability: {state} contact clip "
                            f"{clip} must not use a phase offset")
        if clip not in defs:
            failures.append(f"activity reachability: {activity} maps to "
                            f"unimplemented clip {clip}")
    return failures


def check_activity_animation_reachability(defs, errors):
    paths = (SETTLER_ACTIVITY_SOURCE, SETTLER_ENTITY_SOURCE,
             SETTLER_MODEL_SOURCE)
    if any(not os.path.isfile(path) for path in paths):
        errors.append("activity reachability: one or more runtime source files "
                      "are missing")
        return
    with open(SETTLER_ACTIVITY_SOURCE, encoding="utf-8") as source_file:
        activity_text = source_file.read()
    with open(SETTLER_ENTITY_SOURCE, encoding="utf-8") as source_file:
        entity_text = source_file.read()
    with open(SETTLER_MODEL_SOURCE, encoding="utf-8") as source_file:
        model_text = source_file.read()
    errors.extend(activity_animation_reachability_errors(
        activity_text, entity_text, model_text, defs))


def _channel(definition, bone, target):
    return next((channel for channel in definition.get("channels", [])
                 if channel[0] == bone and channel[1] == target), None)


def _frame_at(definition, bone, target, seconds):
    channel = _channel(definition, bone, target)
    return None if channel is None else next(
        (frame for frame in channel[2]
         if abs(frame[0] - seconds) <= 1e-6), None)


def check_crafting_truth_contract(defs, errors):
    """Static timing/authority seam for ANIM-TRUTH-0A."""
    table = defs.get("LUMBER_CRAFT")
    storage = defs.get("CRAFT_OUTPUT_STORE")
    if table is None or storage is None:
        errors.append("CRAFT truth: both LUMBER_CRAFT and "
                      "CRAFT_OUTPUT_STORE must be implemented")
        return
    if abs(table["length"] - 2.4) > 1e-6 or table["looping"]:
        errors.append("CRAFT truth: LUMBER_CRAFT must be non-looping 2.40s/48t")
    if abs(storage["length"] - 1.2) > 1e-6 or storage["looping"]:
        errors.append("CRAFT truth: CRAFT_OUTPUT_STORE must be non-looping "
                      "1.20s/24t")
    required_frames = (
        (table, "right_arm", "ROTATION", 1.50, "table transform"),
        (table, "torso", "ROTATION", 1.50, "table mass"),
        (table, "left_arm", "ROTATION", 2.15, "escrow pickup"),
        (storage, "left_arm", "ROTATION", 0.70, "storage deposit"),
    )
    for definition, bone, target, seconds, label in required_frames:
        frame = _frame_at(definition, bone, target, seconds)
        if frame is None:
            errors.append(f"CRAFT truth: {label} needs {bone}.{target} key at "
                          f"{seconds:.2f}s")
        elif frame[3] != "LINEAR":
            errors.append(f"CRAFT truth: {label}@{seconds:.2f}s must be LINEAR")
    for definition, clip in ((table, "LUMBER_CRAFT"),
                             (storage, "CRAFT_OUTPUT_STORE")):
        torso = _channel(definition, "torso", "ROTATION")
        # SettlerModel/Blockbench multi-angle evidence pins negative X as the
        # forward waist hinge. Positive X bends into the backpack and is a
        # hard transaction reject.
        if torso is None or any(frame[2][0] > 1e-6 for frame in torso[2]):
            errors.append(f"CRAFT truth: {clip} contains a backward torso key")
        root = _channel(definition, "root", "POSITION")
        if root is None or any(abs(frame[2][0]) > 1e-6
                               or abs(frame[2][2]) > 1e-6 for frame in root[2]):
            errors.append(f"CRAFT truth: {clip} root may compress vertically "
                          "but may not slide")
        for leg in ("right_leg", "left_leg"):
            channel = _channel(definition, leg, "ROTATION")
            if channel is None or any(any(abs(value) > 1e-6
                                          for value in frame[2])
                                      for frame in channel[2]):
                errors.append(f"CRAFT truth: {clip} must keep {leg} planted")

    source_paths = (LUMBER_CRAFT_GOAL_SOURCE,
                    LUMBER_CRAFT_SERVICE_SOURCE,
                    SETTLER_RENDERER_SOURCE)
    if any(not os.path.isfile(path) for path in source_paths):
        errors.append("CRAFT truth: goal/service/renderer source missing")
        return
    with open(LUMBER_CRAFT_GOAL_SOURCE, encoding="utf-8") as source_file:
        goal = strip_comments(source_file.read())
    with open(LUMBER_CRAFT_SERVICE_SOURCE, encoding="utf-8") as source_file:
        service = strip_comments(source_file.read())
    with open(SETTLER_RENDERER_SOURCE, encoding="utf-8") as source_file:
        renderer = strip_comments(source_file.read())
    constants = {
        "CRAFT_DURATION_TICKS": 48,
        "CRAFT_CONTACT_TICK": 30,
        "PICKUP_CONTACT_TICK": 43,
        "DEPOSIT_DURATION_TICKS": 24,
        "DEPOSIT_CONTACT_TICK": 14,
    }
    for name, value in constants.items():
        if re.search(rf'\b{name}\s*=\s*{value}\s*;', goal) is None:
            errors.append(f"CRAFT truth: goal constant {name} must remain {value}")
    goal_needles = (
        "CraftPresentation.Phase.LAY_OUT",
        "CraftPresentation.Phase.WIND_UP",
        "CraftPresentation.Phase.RESULT_READ",
        "CraftPresentation.Phase.PICK_UP",
        "CraftPresentation.Phase.CARRIED",
        "CraftPresentation.Phase.DEPOSIT",
        "SettlerActivity.WORK_CRAFT",
        "SettlerActivity.STORE_CRAFT_OUTPUT",
        "LumbererSelfCraftingService.depositEscrow",
    )
    for needle in goal_needles:
        if needle not in goal:
            errors.append(f"CRAFT truth: goal phase/authority seam missing {needle}")
    service_needles = (
        "worker.beginCraftOutputEscrow",
        "worker.clearCraftOutputEscrow",
        "reservationStillExact",
        "recipeGrid",
        "plan.actionId()",
    )
    for needle in service_needles:
        if needle not in service:
            errors.append(f"CRAFT truth: service authority seam missing {needle}")
    renderer_needles = (
        "entity.craftPresentation()",
        "worldX - entityPosition.x",
        "worldY - entityPosition.y",
        "worldZ - entityPosition.z",
        "CraftPresentation.Phase.RESULT_READ",
        "CraftPresentation.Phase.DEPOSIT",
    )
    for needle in renderer_needles:
        if needle not in renderer:
            errors.append(f"CRAFT truth: fixed-world renderer seam missing {needle}")


def check_offline_prop_contract(defs, errors, warns):
    """K1 gate: item-bearing clips must be reviewable through Minecraft's
    complete hand transform chains, not merely near either hand cube.

    This is intentionally a source gate, not a claim that an offline proxy is
    final visual evidence. The Blockbench runner also asserts its neutral
    matrices when it renders; live QA remains authoritative for the textured
    item and runtime wiring.
    """
    if not os.path.isfile(PROP_CONTRACT):
        errors.append("K1: tools/blockbench/prop_contract.json not found")
        return
    if not os.path.isfile(BB_RENDER):
        errors.append("K1: tools/blockbench/bb_render.mjs not found")
        return

    try:
        with open(PROP_CONTRACT, encoding="utf-8") as contract_file:
            contract = json.load(contract_file)
    except (OSError, ValueError) as exc:
        errors.append(f"K1: invalid prop_contract.json: {exc}")
        return

    if contract.get("schema") != 4:
        errors.append("K1: prop contract schema must be 4")
    if contract.get("minecraftVersion") != "1.21.1":
        errors.append("K1: prop transform must be pinned to Minecraft 1.21.1")
    official = contract.get("officialClient", {})
    if official.get("clientSha1") != "30c73b1c5da787909b2f73340419fdf13b9def88" \
            or official.get("clientMappingsSha1") \
            != "2244b6f072256667bcd9a73df124d6c58de77992":
        errors.append("K1: official 1.21.1 client evidence hashes drifted")
    if contract.get("hand") != "right":
        errors.append("K1: settler tools must attach to the right hand")

    check_melee_evidence_target_contract(
        contract.get("meleeEvidenceTarget"), errors)
    check_crafting_evidence_target_contract(
        contract.get("craftingEvidenceTarget"), errors)

    layer = contract.get("itemInHandLayer", {})
    expected_rotations = [
        {"axis": "x", "degrees": -90},
        {"axis": "y", "degrees": 180},
    ]
    if layer.get("rotateSequence") != expected_rotations \
            or layer.get("translateModelPixels") != [1, 2, -10]:
        errors.append("K1: ItemInHandLayer must apply X -90, then Y 180, "
                      "then translate [1,2,-10] model pixels")

    expected_display_profiles = {
        "handheld": {
            "sourceModel": "minecraft:item/handheld",
            "translateModelPixels": [0, 4, 0.5],
            "rotationXYZDegrees": [0, -90, 55],
            "scale": [0.85, 0.85, 0.85],
            "centerModelPixels": [-8, -8, -8],
        },
        "generated": {
            "sourceModel": "minecraft:item/generated",
            "translateModelPixels": [0, 3, 1],
            "rotationXYZDegrees": [0, 0, 0],
            "scale": [0.55, 0.55, 0.55],
            "centerModelPixels": [-8, -8, -8],
        },
        "handheld_rod": {
            "sourceModel": "minecraft:item/handheld_rod",
            "translateModelPixels": [0, 4, 2.5],
            "rotationXYZDegrees": [0, 90, 55],
            "scale": [0.85, 0.85, 0.85],
            "centerModelPixels": [-8, -8, -8],
        },
        "bow": {
            "sourceModel": "minecraft:item/bow",
            "translateModelPixels": [-1, -2, 2.5],
            "rotationXYZDegrees": [-80, 260, -40],
            "scale": [0.9, 0.9, 0.9],
            "centerModelPixels": [-8, -8, -8],
        },
        "block": {
            "sourceModel": "minecraft:block/block",
            "translateModelPixels": [0, 2.5, 0],
            "rotationXYZDegrees": [75, 45, 0],
            "scale": [0.375, 0.375, 0.375],
            "centerModelPixels": [-8, -8, -8],
        },
    }
    display_profiles = contract.get("displayProfiles")
    if display_profiles != expected_display_profiles:
        errors.append("K1: one or more vanilla third-person-right display "
                      "profiles drifted")
    expected_prop_profiles = {
        "axe": "handheld", "hammer": "handheld",
        "pickaxe": "handheld", "hoe": "handheld", "sword": "handheld",
        "shears": "generated", "fishing_rod": "handheld_rod", "bow": "bow",
    }
    if contract.get("propDisplayProfiles") != expected_prop_profiles:
        errors.append("K1: prop-to-vanilla-display-profile mapping drifted")

    bb = contract.get("blockbench", {})
    if bb.get("modelToBlockbenchAxisSigns") != [-1, -1, 1]:
        errors.append("K1: Java-to-Blockbench axes must be [-1,-1,1]")
    if bb.get("rightArmOrigin") != [6, 22, 0]:
        errors.append("K1: Blockbench right-arm origin drifted from [6,22,0]")
    if bb.get("leftArmOrigin") != [-6, 22, 0]:
        errors.append("K1: Blockbench left-arm origin drifted from [-6,22,0]")
    if bb.get("rightHandCubeCenter") != [6, 12, 0]:
        errors.append("K1: informational hand-cube centre drifted from [6,12,0]")

    # Independent arithmetic check for every neutral origin and basis asserted
    # by bb_render.mjs. The old K1 draft attached directly at [6,12,0] and only
    # logged the vanilla numbers; deriving the result here makes that bug a
    # failing animation gate rather than a visually plausible comment. It also
    # prevents applying the common handheld transform to bow, shears or rod.
    def matrix_multiply(left, right):
        return [[sum(left[row][k] * right[k][column] for k in range(3))
                 for column in range(3)] for row in range(3)]

    def matrix_vector(matrix, vector):
        return [sum(matrix[row][k] * vector[k] for k in range(3))
                for row in range(3)]

    def rotation_matrix(axis, degrees):
        import math
        angle = math.radians(degrees)
        cosine, sine = math.cos(angle), math.sin(angle)
        if axis == "x":
            return [[1, 0, 0], [0, cosine, -sine], [0, sine, cosine]]
        if axis == "y":
            return [[cosine, 0, sine], [0, 1, 0], [-sine, 0, cosine]]
        return [[cosine, -sine, 0], [sine, cosine, 0], [0, 0, 1]]

    signs = [-1, -1, 1]
    orientation = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for step in expected_rotations:
        axis_index = "xyz".index(step["axis"])
        orientation = matrix_multiply(
            orientation,
            rotation_matrix(step["axis"], step["degrees"] * signs[axis_index]))
    arm_origin = [6, 22, 0]
    mapped_layer_translation = [value * signs[index]
                                for index, value in enumerate([1, 2, -10])]
    layer_origin = [arm_origin[index] + value for index, value in enumerate(
        matrix_vector(orientation, mapped_layer_translation))]
    def same_vector(left, right):
        return isinstance(right, list) and len(right) == len(left) \
            and all(abs(a - b) <= 1e-6 for a, b in zip(left, right))

    if not same_vector(layer_origin, bb.get("expectedNeutralLayerOrigin")):
        errors.append("K1: derived neutral ItemInHandLayer origin must be [7,12,-2]")
    if same_vector(layer_origin, bb.get("rightHandCubeCenter")):
        errors.append("K1: layer origin must not collapse to the hand-cube centre")
    right_display_profiles = {
        name: profile for name, profile in expected_display_profiles.items()
        if name != "block"
    }
    expected_displays = bb.get("expectedNeutralDisplays")
    if not isinstance(expected_displays, dict) \
            or set(expected_displays) != set(right_display_profiles):
        errors.append("K1: neutral display assertions must cover all four profiles")
        expected_displays = {}
    for profile_name, display in right_display_profiles.items():
        mapped_display_translation = [
            value * signs[index]
            for index, value in enumerate(display["translateModelPixels"])
        ]
        display_origin = [
            layer_origin[index] + value
            for index, value in enumerate(
                matrix_vector(orientation, mapped_display_translation))
        ]
        display_orientation = orientation
        for axis_index, degrees in enumerate(display["rotationXYZDegrees"]):
            display_orientation = matrix_multiply(
                display_orientation,
                rotation_matrix("xyz"[axis_index], degrees * signs[axis_index]))
        expected = expected_displays.get(profile_name, {})
        if not same_vector(display_origin, expected.get("origin")):
            errors.append(f"K1: derived {profile_name} display origin drifted")
        expected_basis = expected.get("basisRows")
        if not isinstance(expected_basis, list) or len(expected_basis) != 3 \
                or any(not same_vector(row, expected_basis[index])
                       for index, row in enumerate(display_orientation)):
            errors.append(f"K1: derived {profile_name} display basis drifted")

    # The portable-container preview also renders the real OFFHAND oak-log
    # block. ItemInHandLayer mirrors the layer's X translation, and
    # ItemTransform#apply mirrors the block profile's Y/Z rotations for a
    # left-hand display. Derive that separately so a convenient forearm cube
    # can never masquerade as runtime evidence again.
    left_arm_origin = [-6, 22, 0]
    left_layer_translation = [-1, 2, -10]
    mapped_left_layer = [value * signs[index]
                         for index, value in enumerate(left_layer_translation)]
    left_layer_origin = [
        left_arm_origin[index] + value
        for index, value in enumerate(
            matrix_vector(orientation, mapped_left_layer))
    ]
    if not same_vector(left_layer_origin,
                       bb.get("expectedNeutralLeftLayerOrigin")):
        errors.append("K1: derived left ItemInHandLayer origin must be [-7,12,-2]")
    block = expected_display_profiles["block"]
    left_block_translation = list(block["translateModelPixels"])
    left_block_translation[0] *= -1
    mapped_left_block = [value * signs[index]
                         for index, value in enumerate(left_block_translation)]
    left_block_origin = [
        left_layer_origin[index] + value
        for index, value in enumerate(
            matrix_vector(orientation, mapped_left_block))
    ]
    left_block_orientation = orientation
    for axis_index, original_degrees in enumerate(block["rotationXYZDegrees"]):
        degrees = original_degrees
        if axis_index in (1, 2):
            degrees *= -1
        left_block_orientation = matrix_multiply(
            left_block_orientation,
            rotation_matrix("xyz"[axis_index], degrees * signs[axis_index]))
    expected_left_displays = bb.get("expectedNeutralLeftDisplays")
    if not isinstance(expected_left_displays, dict) \
            or set(expected_left_displays) != {"block"}:
        errors.append("K1: neutral left display assertions must cover block")
        expected_left_displays = {}
    expected_left_block = expected_left_displays.get("block", {})
    if not same_vector(left_block_origin, expected_left_block.get("origin")):
        errors.append("K1: derived left block display origin drifted")
    expected_left_basis = expected_left_block.get("basisRows")
    if not isinstance(expected_left_basis, list) or len(expected_left_basis) != 3 \
            or any(not same_vector(row, expected_left_basis[index])
                   for index, row in enumerate(left_block_orientation)):
        errors.append("K1: derived left block display basis drifted")

    clips = contract.get("clips")
    if not isinstance(clips, dict):
        errors.append("K1: prop contract needs a clips object")
        return
    runtime_required = {
        "FARM_PLANT": "hoe", "FARM_HARVEST": "hoe",
        "FARM_TILL": "hoe", "FARM_WATER": "hoe", "FARMER_CARRY": "hoe",
        "CHOP": "axe", "LIMB_BRANCHES": "axe", "GATHER_LOG": "axe",
        "HAUL_LOG": "axe", "HAUL_LOG_HEAVY": "axe",
        "MINE_PICK": "pickaxe", "MELEE": "sword",
        "LEAP_STRIKE": "sword",
        "GUARD_STANCE": "sword", "GUARD_PATROL": "sword",
        "GUARD_HIT_REACT": "sword",
        "ARCHER_STANCE": "bow", "ARCHER_PATROL": "bow",
        "IDLE_ARCHER": "bow",
        "SHIELD_BLOCK": "sword", "HERDER_SHEAR": "shears",
        "FISHER_CAST": "fishing_rod", "HUNTER_LOOSE": "bow",
    }
    missing = sorted(set(runtime_required) - set(clips))
    if missing:
        errors.append("K1: item-bearing clips missing offline props: "
                      + ", ".join(missing))
    wrong_props = sorted(name for name, wanted in runtime_required.items()
                         if name in clips and clips[name] != wanted)
    if wrong_props:
        errors.append("K1: runtime-held clip uses wrong proxy: "
                      + ", ".join(wrong_props))
    unsupported = sorted({str(prop) for prop in clips.values()}
                         - set(expected_prop_profiles))
    if unsupported:
        errors.append("K1: unsupported proxy prop(s): " + ", ".join(unsupported))
    unknown_clips = sorted(set(clips) - set(defs))
    if unknown_clips:
        errors.append("K1: prop contract names unimplemented clip(s): "
                      + ", ".join(unknown_clips))
    expected_contextual = {
        "IDLE_LUMBERER": {"lumberer": "axe"},
        "WALK": {"lumberer": "axe"},
        "WALK_LADEN": {"lumberer": "axe"},
        "WALK_CARRY_ITEM": {"lumberer": "axe"},
        "WORK_CONTAINER_DOWN": {"lumberer": "axe"},
        "GROUND_ITEM_PICKUP": {"lumberer": "axe"},
        "WORK_CONTAINER_STOW": {"lumberer": "axe"},
        "WORK_CONTAINER_UP": {"lumberer": "axe"},
        "PICKUP_STOW": {"lumberer": "axe"},
        "IDLE_SENTRY": {"guard": "sword", "hunter": "none"},
        "CLEAVE": {"butcher": "none", "herder_cull": "shears"},
        "LUMBER_CRAFT": {"missing": "none", "worn_existing_axe": "axe"},
        "CRAFT_OUTPUT_STORE": {"escrow_output": "none"},
    }
    expected_conceptual = {
        "HAMMER_ANVIL": "hammer", "MASON_CHISEL": "pickaxe",
    }
    expected_fixed = {**runtime_required, **expected_conceptual}
    if clips != expected_fixed:
        errors.append("K1: fixed clip-to-prop mapping drifted; runtime and "
                      "conceptual proxies must stay explicitly separated")
    contextual = contract.get("contextualClips")
    if contextual != expected_contextual:
        errors.append("K1: contextual clips must pin every exact runtime "
                      "role-to-prop mapping")
    contextual_unknown = sorted(set(contextual or {}) - set(defs)) \
        if isinstance(contextual, dict) else []
    if contextual_unknown:
        errors.append("K1: contextual prop contract names unimplemented clip(s): "
                      + ", ".join(contextual_unknown))
    contextual_props = {
        str(prop)
        for choices in (contextual or {}).values()
        if isinstance(choices, dict)
        for prop in choices.values()
    } if isinstance(contextual, dict) else set()
    unsupported_contextual = sorted(
        contextual_props - ({"none"} | set(expected_prop_profiles)))
    if unsupported_contextual:
        errors.append("K1: unsupported contextual proxy prop(s): "
                      + ", ".join(unsupported_contextual))

    runtime_declared = contract.get("fixedRuntimeMainHandClips")
    if not isinstance(runtime_declared, list) \
            or set(runtime_declared) != set(runtime_required):
        errors.append("K1: fixedRuntimeMainHandClips must exactly cover clips "
                      "with one real, context-independent MAINHAND item")
    conditional_offhand = contract.get("conditionalRuntimeOffhandClips")
    if conditional_offhand != {"SHIELD_BLOCK": "shield"}:
        errors.append("K1: SHIELD_BLOCK must declare a physical conditional "
                      "OFFHAND shield instead of a renderer-only proxy")
    conceptual = contract.get("conceptualProxyClips")
    if not isinstance(conceptual, list) \
            or set(conceptual) != set(expected_conceptual):
        errors.append("K1: conceptual proxy clips must be explicitly labelled")
    elif set(conceptual) & set(runtime_required):
        errors.append("K1: conceptual and runtime-held clips must be disjoint")
    known_no_go = contract.get("knownVisualNoGoClips")
    required_no_go = {"CLEAVE", "HUNTER_LOOSE"}
    if not isinstance(known_no_go, dict) \
            or set(known_no_go) != required_no_go \
            or not all(token in str(known_no_go.get("HUNTER_LOOSE", ""))
                       for token in ("MAINHAND", "right_arm", "left_arm", "pulling")) \
            or not all(token in str(known_no_go.get("CLEAVE", ""))
                       for token in ("BUTCHER", "HERDER", "BB_CONTEXT")):
        errors.append("K1: runtime/catalogue hand mismatches must remain explicit "
                      "visual NO-GOs until runtime or the clips are repaired")
    else:
        warns.append("K1 VISUAL NO-GO HUNTER_LOOSE: runtime MAINHAND bow follows "
                     "right_arm, but the clip authors left_arm as the bow arm; "
                     "do not approve its item render")
        warns.append("K1 contextual clips: CLEAVE requires BB_CONTEXT=butcher "
                     "(empty hand) or herder_cull (shears); IDLE_SENTRY requires "
                     "BB_CONTEXT=guard (sword) or hunter (empty hand). Neither clip "
                     "has one truthful universal prop")

    entity_path = os.path.join(ROOT,
        "src/main/java/com/hearthstead/entity/SettlerEntity.java")
    model_path = os.path.join(ROOT,
        "src/main/java/com/hearthstead/client/model/SettlerModel.java")
    try:
        with open(entity_path, encoding="utf-8") as source_file:
            entity_source = source_file.read()
        with open(model_path, encoding="utf-8") as source_file:
            model_source = source_file.read()
    except OSError as exc:
        errors.append(f"K1: cannot read runtime equipment-pose source: {exc}")
    else:
        entity_needles = (
            "shieldThudDelayFor(getOffhandItem())",
            "hasPhysicalOffhandShield()",
            "hasPhysicalMainhandSword()",
            "hasPhysicalMainhandBow()",
        )
        model_needles = (
            "physicalShieldLoadout",
            "SettlerAnimations.GUARD_HIT_REACT",
            "SettlerAnimations.ARCHER_STANCE",
            "SettlerAnimations.ARCHER_PATROL",
            "SettlerAnimations.IDLE_ARCHER",
        )
        for needle in entity_needles:
            if needle not in entity_source:
                errors.append(f"K1: runtime equipment truth gate missing {needle}")
        for needle in model_needles:
            if needle not in model_source:
                errors.append(f"K1: equipment-specific pose selection missing {needle}")

    with open(BB_RENDER, encoding="utf-8") as renderer_file:
        renderer = renderer_file.read()
    for needle in ("prop_contract.json", "k1_vanilla_hand_", "BB_CHROMIUM",
                   "BB_CONTEXT",
                   "k1_layer_translate", "k1_item_display_translate",
                   "k1_offhand_layer_translate", "hs_preview_offhand_log",
                   "conditionalRuntimeOffhandClips",
                   "verified neutral origins", "verified neutral display basis",
                   "verified local item scale basis",
                   "VISUAL NO-GO", "CONTRACT OVERRIDE", "canonicalClipName",
                   "BB_BASE_PHASE", "meleeEvidenceTarget",
                   "hs_melee_target_hitbox", "TICK 4 CONTACT",
                   "craftingEvidenceTarget", "hs_craft_table",
                   "hs_craft_output_wooden_axe"):
        if needle not in renderer:
            errors.append(f"K1: bb_render.mjs no longer consumes required marker {needle!r}")


def parse_definitions(path):
    with open(path, encoding="utf-8") as source_file:
        text = strip_comments(source_file.read())
    defs = {}
    for m in re.finditer(
            r'AnimationDefinition (\w+) = AnimationDefinition\.Builder\s*'
            r'\.withLength\(([\d.]+)F\)(\.looping\(\))?(.*?)\.build\(\);',
            text, re.S):
        name, length, looping, body = m.group(1), float(m.group(2)), bool(m.group(3)), m.group(4)
        channels = []
        for cm in re.finditer(
                r'\.addAnimation\("(\w+)",\s*new AnimationChannel\((\w+),(.*?)\)\)\s*'
                r'(?=\.addAnimation|\Z)',
                body, re.S):
            bone, target, kbody = cm.group(1), cm.group(2), cm.group(3)
            frames = []
            for km in re.finditer(
                    r'new Keyframe\(([\d.]+)F,\s*KeyframeAnimations\.(\w+)\('
                    r'([-\d.F]+),\s*([-\d.F]+),\s*([-\d.F]+)\),\s*(\w+)\)', kbody):
                t = float(km.group(1))
                vec = tuple(float(v.rstrip('F')) for v in km.group(3, 4, 5))
                frames.append((t, km.group(2), vec, km.group(6)))
            channels.append((bone, target, frames))
        defs[name] = dict(length=length, looping=looping, channels=channels)
    return defs


def parse_catalogue_clip_names(path):
    """Clip names declared in the catalogue's numbered §N.M headings.
    One heading (§12.5) declares two names joined by 'and'."""
    if not os.path.isfile(path):
        return set()
    text = open(path, encoding="utf-8").read()
    names = set()
    for m in re.finditer(r'^### \d+\.\d+[a-z]? `([A-Z_]+)`(?:\s*\(?\+?`([A-Z_]+)`)?', text, re.M):
        names.add(m.group(1))
        if m.group(2):
            names.add(m.group(2))
    # §12.5 heading form: "`SOCIAL_TALK` and `SOCIAL_LISTEN`"
    for m in re.finditer(r'^### \d+\.\d+[a-z]? `([A-Z_]+)` and `([A-Z_]+)`', text, re.M):
        names.add(m.group(1))
        names.add(m.group(2))
    # Sub-variant names in parens, e.g. "HEAL_REVIVE (+REVIVE_SUCCESS/REVIVE_FAIL)"
    for m in re.finditer(r'\(\+\s*`([A-Z_]+)`(?:/`([A-Z_]+)`)?\)', text):
        names.add(m.group(1))
        if m.group(2):
            names.add(m.group(2))
    return names


def parse_goal_tick_contracts(ai_dir):
    """Every ``x % N == K`` goal contract, including named int constants.

    Contact ticks are production invariants rather than unexplained literals,
    so both sides may be a same-file constant (for example
    ``workTicks % WATER_DURATION == WATER_CONTACT_TICK``).  Resolving the
    constants here keeps the validator tied to the deterministic source of
    truth without forcing the runtime to duplicate magic numbers.
    """
    contracts = {}
    if not os.path.isdir(ai_dir):
        return contracts
    for fn in sorted(os.listdir(ai_dir)):
        if not fn.endswith(".java"):
            continue
        path = os.path.join(ai_dir, fn)
        text = strip_comments(open(path, encoding="utf-8").read())

        def resolve_int(token):
            if token.isdigit():
                return int(token)
            declaration = re.search(
                rf'\b{re.escape(token)}\s*=\s*(\d+)\s*;', text)
            return int(declaration.group(1)) if declaration else None

        for i, line in enumerate(text.splitlines(), 1):
            m = re.search(
                r'\w+\s*%\s*(\w+|\d+)\s*==\s*(\w+|\d+)', line)
            if not m:
                continue
            period = resolve_int(m.group(1))
            tick = resolve_int(m.group(2))
            contracts.setdefault((period, tick), []).append(f"{fn}:{i}")
    return contracts


def load_sounds_json():
    if not os.path.isfile(SOUNDS_JSON):
        return {}
    with open(SOUNDS_JSON, encoding="utf-8") as f:
        return json.load(f)


def check_pipeline_present_in_model(model_path, needle):
    if not os.path.isfile(model_path):
        return False
    return needle in open(model_path, encoding="utf-8").read()


TICK_CONSTANT_DECL_RE_TMPL = r'\b{name}\s*=\s*(\d+)\s*;'


# The crafter trades' sound-sync contract (audit F8). CrafterWorkGoal fires
# one sound per motion loop at Employment.soundContactOf(type) -- a symbolic
# contact tick, not a literal "% P == T" in an AI goal file, so check 13
# cannot see it. This check parses BOTH Employment switches instead and
# asserts the one wrong answer can never come back: the seam. Rows here are
# the accents the catalogue documents EXACTLY (S7.2, S8.1, S18.4, S20.3,
# S20.4); the (est.) rows in Employment are structural-checked only
# (in-range, present) until their clips get exact accent lines.
CRAFTER_DOCUMENTED = {
    "WORK_STIR": ("COOK_STIR", 1.20),
    "WORK_CHISEL": ("MASON_CHISEL", 0.50),
    "WORK_MINE": ("MINE_PICK", 0.45),
    "WORK_HAMMER": ("HAMMER_ANVIL", 0.45),
    "WORK_FLETCH": ("FLETCHER_FLETCH", 0.75),
}

EMPLOYMENT_PATH = os.path.join(
    ROOT, "src/main/java/com/hearthstead/settlement/Employment.java")
CRAFTER_GOAL_PATH = os.path.join(
    ROOT, "src/main/java/com/hearthstead/entity/ai/CrafterWorkGoal.java")


def parse_employment_switch(text, method_name):
    """The explicit WORK_* -> int cases of one switch-bearing method."""
    start = text.find("int " + method_name)
    if start < 0:
        return None
    nxt = text.find("public static", start + 1)
    body = text[start:nxt if nxt > 0 else len(text)]
    return dict((m.group(1), int(m.group(2))) for m in
                re.finditer(r"case (WORK_\w+) -> (\d+);", body))


def check_crafter_sound_contracts(defs, errors, warns):
    try:
        emp = strip_comments(io_read(EMPLOYMENT_PATH))
        # strip_comments here too: the goal's own WHY comment names the old
        # "% period == 0" form as the thing it replaced.
        goal = strip_comments(io_read(CRAFTER_GOAL_PATH))
    except OSError as e:
        errors.append(f"crafter sound contract: cannot read source: {e}")
        return
    periods = parse_employment_switch(emp, "soundPeriodOf")
    contacts = parse_employment_switch(emp, "soundContactOf")
    if periods is None or contacts is None:
        errors.append("crafter sound contract: Employment.soundPeriodOf/"
                      "soundContactOf not found -- the F8 fix regressed")
        return
    if "Employment.soundContactOf" not in goal:
        errors.append("CrafterWorkGoal no longer fires on "
                      "Employment.soundContactOf -- the F8 fix regressed")
    if re.search(r"% period == 0\b", goal):
        errors.append("CrafterWorkGoal contains '% period == 0' -- the loop-"
                      "seam trigger is back (audit F8)")
    for act, period in periods.items():
        contact = contacts.get(act)
        if contact is None:
            errors.append(f"{act}: has a sound period ({period}) but no "
                          f"explicit contact tick in soundContactOf")
            continue
        if not (1 <= contact <= period - 1):
            errors.append(f"{act}: contact tick {contact} is outside "
                          f"[1, {period - 1}] -- it aliases onto the loop "
                          f"seam, the one place the sound must never fire")
        doc = CRAFTER_DOCUMENTED.get(act)
        if doc:
            clip, accent_s = doc
            expect = round(accent_s * 20)
            if contact != expect:
                errors.append(f"{act}: catalogue documents {clip}'s accent at "
                              f"{accent_s}s (tick {expect}) but "
                              f"soundContactOf says {contact}")
            d = defs.get(clip)
            if d is not None:
                if round(d["length"] * 20) != period:
                    errors.append(f"{act}: {clip} is {d['length']}s but "
                                  f"soundPeriodOf says {period}")
                near = any(abs(f[0] - accent_s) <= 0.10
                           for c in d["channels"] for f in c[2])
                if not near:
                    warns.append(f"{act}: {clip} has no keyframe within 0.10s "
                                 f"of the documented accent {accent_s}s -- "
                                 f"retimed clip? true up catalogue + "
                                 f"soundContactOf together")


def io_read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def check_entity_sound_contracts(defs, sounds_data, errors, warns):
    """ENTITY_SOUND_CONTRACTS: sound-sync contracts driven by a server-side
    scheduler or a one-shot trigger rather than a single AI-goal's repeating
    workTicks%period==tick line (see the table's own comment for why these
    don't fit SOUND_CONTRACTS)."""
    java_root = os.path.join(ROOT, "src/main/java/com/hearthstead")
    for clip, accents, sound_field, rel_path, tick_constants in ENTITY_SOUND_CONTRACTS:
        d = defs.get(clip)
        if d is None:
            errors.append(f"entity sound contract for {clip}: clip not implemented")
            continue
        for accent_s in accents:
            hit = any(any(abs(f[0] - accent_s) < 1e-6 for f in frames)
                      for _, _, frames in d["channels"])
            if not hit:
                errors.append(f"{clip}: no keyframe at accent_seconds={accent_s} on any "
                              f"channel -- the sound accent has nothing to sync to")
        sound_key = sound_field.lower()
        if sound_key not in sounds_data:
            errors.append(f"{clip}: sound '{sound_key}' (ModSounds.{sound_field}) has no "
                          f"entry in sounds.json")
        src_path = os.path.join(java_root, rel_path)
        if not os.path.isfile(src_path):
            errors.append(f"{clip}: sound contract source {rel_path} does not exist")
            continue
        text = strip_comments(open(src_path, encoding="utf-8").read())
        for const_name, expect_value in tick_constants:
            m = re.search(TICK_CONSTANT_DECL_RE_TMPL.format(name=re.escape(const_name)), text)
            if m is None:
                errors.append(f"{clip}: constant {const_name} not found in {rel_path} -- "
                              f"the sound-sync contract (catalogue/goal/checker) is broken")
            elif int(m.group(1)) != expect_value:
                errors.append(f"{clip}: {const_name} in {rel_path} is {m.group(1)}, "
                              f"but the contract table says {expect_value} (fix whichever "
                              f"is wrong -- they must agree)")


def check_structural(source, defs, errors, warns):
    """§17.1/17.2/17.4 structural + craft checks for one registered
    ANIMATION_SOURCES entry, scoped entirely by that entry's own bone
    whitelist and exemption sets -- see the ANIMATION_SOURCES docstring for
    why this is the one place a third file needs zero new code, only a new
    entry in that list."""
    label = source["label"]
    bone_whitelist = source["bones"]
    for name, d in defs.items():
        assert d["channels"], f"{name}: no channels parsed"
        seen_bone_targets = set()
        touched_bones = set()
        for bone, target, frames in d["channels"]:
            chan_label = f"{name}.{bone}.{target}"
            if bone not in bone_whitelist:
                errors.append(f"{chan_label}: '{bone}' is not one of the "
                              f"{label} model's whitelisted bones")
            key = (bone, target)
            if key in seen_bone_targets:
                errors.append(f"{chan_label}: duplicate (bone, target) channel -- the second "
                              f"silently discards the first")
            seen_bone_targets.add(key)
            touched_bones.add(bone)
            if not frames:
                errors.append(f"{chan_label}: no keyframes parsed")
                continue
            if d["looping"] and len(frames) < 2:
                errors.append(f"{chan_label}: looping clip has a single-keyframe channel")
            times = [f[0] for f in frames]
            if times != sorted(times):
                errors.append(f"{chan_label}: timestamps not ascending: {times}")
            if times[-1] > d["length"] + 1e-6:
                errors.append(f"{chan_label}: last key {times[-1]} exceeds length {d['length']}")
            for t in times + [d["length"]]:
                # Tick grid: every timestamp and the clip length must be a
                # multiple of 0.05s (within float tolerance).
                ticks = t / 0.05
                if abs(ticks - round(ticks)) > 1e-3:
                    errors.append(f"{chan_label}: {t}s is not on the 0.05s tick grid")
            if d["looping"]:
                first, last = frames[0][2], frames[-1][2]
                if any(abs(a - b) > 0.01 for a, b in zip(first, last)):
                    errors.append(f"{chan_label}: loop does not close: {first} -> {last}")
                if abs(times[-1] - d["length"]) > 1e-6:
                    errors.append(f"{chan_label}: looping channel ends at {times[-1]}, "
                                  f"not at length {d['length']} (holds last pose, "
                                  f"desyncs from the other channels across the loop)")
            for t, kind, vec, interp in frames:
                if kind == "degreeVec" and any(abs(v) > 180 for v in vec):
                    errors.append(f"{chan_label}@{t}: rotation beyond 180deg: {vec}")
                if kind == "posVec" and any(abs(v) > 12 for v in vec):
                    errors.append(f"{chan_label}@{t}: position offset beyond 12px: {vec}")
                if kind == "scaleVec" and any(v < 0.5 or v > 1.5 for v in vec):
                    errors.append(f"{chan_label}@{t}: extreme scale: {vec}")

        if name not in source["bone_count_exempt"] and len(touched_bones) < 3:
            errors.append(f"{name}: touches only {len(touched_bones)} bone(s) "
                          f"({sorted(touched_bones)}) -- looks like a placeholder")

        expected_overlay_bones = source["exact_overlay_bones"].get(name)
        if expected_overlay_bones is not None and touched_bones != expected_overlay_bones:
            errors.append(f"{name}: overlay must touch exactly "
                          f"{sorted(expected_overlay_bones)}, got "
                          f"{sorted(touched_bones)} -- locomotion belongs to "
                          "the speed-coupled base clip")

        # 17.4-18: cloak motion on loops >= 1.0s. Only meaningful for a model
        # that has a cloak bone at all (source["has_cloak"]) -- the raider
        # rig has none, so this whole check is skipped for that source
        # rather than warning on every raider loop for a bone that could
        # never exist. Bone-count-exempt layer clips (e.g. GUARD_PATROL)
        # deliberately touch only a couple of bones and are exempt too, for
        # the same reason.
        if source["has_cloak"] and d["looping"] and d["length"] >= 1.0 \
                and name not in source["cloak_pin_allowlist"] \
                and name not in source["bone_count_exempt"]:
            cloak_channels = [c for c in d["channels"] if c[0] == "cloak"]
            has_motion = any(
                any(abs(a - b) > 0.01 for a, b in zip(c[2][0][2], c[2][-1][2]))
                or len(c[2]) > 2
                for c in cloak_channels)
            if not cloak_channels:
                warns.append(f"{name}: no cloak channel on a >=1s loop (allowlist it in "
                             f"that source's cloak_pin_allowlist if the load genuinely pins it)")
            elif not has_motion and len(cloak_channels[0][2]) < 3:
                warns.append(f"{name}: cloak channel present but static -- "
                             f"the cape should have secondary motion")

        # 17.4-20: work clips have legs.
        if name not in source["legs_exempt"] and "right_leg" not in touched_bones \
                and "left_leg" not in touched_bones:
            errors.append(f"{name}: no right_leg/left_leg channel -- the entity will "
                          f"read as floating (add it to that source's legs_exempt if "
                          f"this is deliberate)")

        # 17.4-21: one-shots return to neutral.
        if not d["looping"] and name not in source["ends_in_pose_allowlist"]:
            for bone, target, frames in d["channels"]:
                if len(frames) < 2:
                    continue
                first, last = frames[0][2], frames[-1][2]
                tol = 3.0 if frames[0][1] == "degreeVec" else (0.5 if frames[0][1] == "posVec" else 0.01)
                if any(abs(a - b) > tol for a, b in zip(first, last)):
                    errors.append(f"{name}.{bone}.{target}: one-shot does not return to its "
                                  f"start pose ({first} -> {last}) -- the entity will snap "
                                  f"when it expires")

        # 17.4-22: carry-layer arms are locked (<=6deg travel).
        if name in source["carry_layer_clips"]:
            for bone, target, frames in d["channels"]:
                if bone not in ("right_arm", "left_arm") or target != "ROTATION":
                    continue
                vecs = [f[2] for f in frames]
                for axis in range(3):
                    span = max(v[axis] for v in vecs) - min(v[axis] for v in vecs)
                    if span > 6.0:
                        errors.append(f"{name}.{bone}: carry layer arm travels {span:.1f}deg "
                                      f"on axis {axis} (limit 6deg) -- a 'locked' arm that "
                                      f"visibly swings breaks the whole carry read")

        # A small span can still be a catastrophically high static pose.
        # Pin the authored X rotation itself for carry overlays that have a
        # silhouette contract, independent of how little they move.
        posture_bounds = source["arm_posture_bounds"].get(name, {})
        for bone, (minimum, maximum) in posture_bounds.items():
            arm_channels = [c for c in d["channels"]
                            if c[0] == bone and c[1] == "ROTATION"]
            if not arm_channels:
                errors.append(f"{name}.{bone}: missing ROTATION channel required by "
                              "the carry posture bound")
                continue
            x_values = [frame[2][0] for channel in arm_channels
                        for frame in channel[2]]
            outside = [value for value in x_values
                       if value < minimum or value > maximum]
            if outside:
                errors.append(f"{name}.{bone}: x rotation {outside} outside "
                              f"[{minimum:.0f}, {maximum:.0f}]deg -- hand may cross "
                              "the shoulder/crown line")


def check_melee_transition_contract(defs, errors, target_contract=None):
    """Fail closed on MELEE's additive contact, feet and base handoff.

    Generic one-shot closure catches the final pose but is intentionally
    tolerant and says nothing about the path used to get there. MELEE used to
    exploit that gap: its torso reversed roughly 45 degrees across two ticks,
    both overlay legs slid their feet about six model pixels, and the last
    recovery step still carried visible velocity when the layer expired.
    This contract measures those physical failure modes instead of banning a
    particular authored channel set. A future root/hip compensation channel
    is allowed, but it must honestly keep one support foot planted.
    """
    if target_contract is None:
        try:
            with open(PROP_CONTRACT, encoding="utf-8") as contract_file:
                target_contract = json.load(contract_file).get(
                    "meleeEvidenceTarget")
        except (OSError, ValueError) as exc:
            errors.append(f"MELEE gaze contract: cannot load target bearing: {exc}")
            target_contract = None
    target_look = check_melee_evidence_target_contract(
        target_contract, errors) if target_contract is not None else None

    clip = defs.get("MELEE")
    if clip is None:
        errors.append("MELEE transition contract: clip not implemented")
        return

    patrol = defs.get("GUARD_PATROL")
    if patrol is None:
        errors.append("MELEE martial-base contract: GUARD_PATROL is missing")
    else:
        patrol_channels = {(bone, target) for bone, target, _ in patrol["channels"]}
        required_patrol = {
            ("right_arm", "ROTATION"), ("left_arm", "ROTATION"),
            ("head", "ROTATION"), ("torso", "ROTATION"),
        }
        missing_patrol = required_patrol - patrol_channels
        if missing_patrol:
            errors.append("MELEE martial-base contract: GUARD_PATROL lacks stable "
                          f"upper-body channel(s) {sorted(missing_patrol)}")
        forbidden_patrol = {("root", "ROTATION"), ("root", "POSITION"),
                            ("right_leg", "ROTATION"),
                            ("right_leg", "POSITION"),
                            ("left_leg", "ROTATION"),
                            ("left_leg", "POSITION")} & patrol_channels
        if forbidden_patrol:
            errors.append("MELEE locomotion ownership: GUARD_PATROL must leave "
                          f"distance-sampled root/legs untouched: {sorted(forbidden_patrol)}")
    if clip["looping"] or abs(clip["length"] - 0.50) > 1e-6:
        errors.append("MELEE transition contract: must remain a 0.50s one-shot")

    channels = {(bone, target): frames
                for bone, target, frames in clip["channels"]}
    required_channels = {
        ("right_arm", "ROTATION"),
        ("left_arm", "ROTATION"),
        ("torso", "ROTATION"),
        ("torso", "POSITION"),
        ("head", "ROTATION"),
        ("cloak", "ROTATION"),
    }
    missing_channels = required_channels - set(channels)
    if missing_channels:
        errors.append("MELEE transition contract: missing required channel(s) "
                      f"{sorted(missing_channels)}")
        return

    recovery_ticks = {0.25, 0.30, 0.35, 0.40, 0.45, 0.50}
    primary_contact = required_channels - {("cloak", "ROTATION")}
    for channel, frames in channels.items():
        label = f"MELEE.{channel[0]}.{channel[1]}"
        by_time = {round(frame[0], 2): frame for frame in frames}
        if 0.00 not in by_time or 0.50 not in by_time:
            errors.append(f"{label}: additive layer needs keys at 0.00 and 0.50s")
            continue
        for edge in (0.00, 0.50):
            vec = by_time[edge][2]
            if any(abs(value) > 0.01 for value in vec):
                errors.append(f"{label}@{edge:.2f}: must be zero-offset for the "
                              f"GUARD_STANCE handoff, got {vec}")
        missing_recovery = sorted(recovery_ticks - set(by_time))
        if missing_recovery:
            errors.append(f"{label}: missing authored recovery tick(s) "
                          f"{missing_recovery}")
            continue

        # The first tick after the narrow 0.20-0.25 contact band may carry a
        # controlled follow-through: that is the visible inertia the previous
        # epsilon-only recovery lacked. From 0.30 onward every axis must shrink
        # toward rest (with one intentional zero crossing), so follow-through
        # cannot become a second uncontrolled strike.
        recovery = [by_time[t] for t in sorted(recovery_ticks)]
        for pair_index, (previous, current) in enumerate(zip(recovery, recovery[1:])):
            for axis, (before, after) in enumerate(zip(previous[2], current[2])):
                if pair_index > 0 and abs(after) > abs(before) + 0.01:
                    errors.append(f"{label}: recovery axis {axis} moves away from "
                                  f"zero at {previous[0]:.2f}->{current[0]:.2f}s "
                                  f"({before}->{after})")
            if channel[1] == "ROTATION":
                rate = max(abs(a - b) for a, b in zip(previous[2], current[2]))
                ticks = (current[0] - previous[0]) / 0.05
                # The torso/head pair carries a deliberate one-tick yaw
                # follow-through after contact. Seventeen degrees is visibly
                # inertial but still bounded; every later tick remains under
                # the tighter twelve-degree recovery cap.
                rate_limit = 17.0 if pair_index == 0 else 12.0
                if rate / ticks > rate_limit + 1e-6:
                    errors.append(f"{label}: recovery moves {rate / ticks:.1f} "
                                  f"deg/tick ({rate_limit:.0f} deg/tick maximum)")

        # A zero pose at the final key is not enough. With a non-zero 0.45 s
        # key, Catmull-Rom still reaches expiry with residual velocity and the
        # base layer appears to catch it. Two identical rest keys make the
        # final tick a real settled hold.
        terminal = by_time[0.45][2]
        final = by_time[0.50][2]
        terminal_tolerance = 0.01
        if any(abs(value) > terminal_tolerance for value in terminal) \
                or any(abs(value) > terminal_tolerance for value in final):
            unit = "px" if channel[1] == "POSITION" else "deg"
            residual = max(abs(a - b) for a, b in zip(terminal, final))
            errors.append(f"{label}: terminal hold must be at rest for both "
                          f"0.45 and 0.50s; got {terminal} -> {final} "
                          f"({residual:.2f}{unit}/tick residual)")

        if channel in primary_contact:
            contact = by_time.get(0.20)
            after = by_time.get(0.25)
            if contact is None or contact[3] != "LINEAR":
                errors.append(f"{label}: exact tick-4 contact needs a LINEAR "
                              "0.20s key")
            if contact is not None and after is not None:
                limit = 0.10 if channel[1] == "POSITION" else 3.0
                drift = max(abs(a - b) for a, b in zip(contact[2], after[2]))
                if drift > limit + 1e-6:
                    unit = "px" if channel[1] == "POSITION" else "deg"
                    errors.append(f"{label}: contact hold drifts {drift:.2f}{unit} "
                                  f"from 0.20 to 0.25s (limit {limit:.2f})")

    # Real overshoot means crossing the zero-offset rest pose, not merely
    # reaching another large after-contact extreme. Pin it on independent
    # sword, torso and mass channels so a decorative cape twitch cannot make
    # a mechanically dead recovery pass.
    overshoot_contracts = [
        (("right_arm", "ROTATION"), 0, 0.25, 0.40, 0.25),
        (("torso", "ROTATION"), 0, 0.25, 0.40, 0.25),
        (("torso", "POSITION"), 2, 0.25, 0.40, 0.02),
    ]
    for channel, axis, anchor_time, overshoot_time, minimum in overshoot_contracts:
        by_time = {round(frame[0], 2): frame for frame in channels[channel]}
        anchor = by_time.get(anchor_time)
        overshoot = by_time.get(overshoot_time)
        label = f"MELEE.{channel[0]}.{channel[1]}.axis{axis}"
        if anchor is None or overshoot is None:
            errors.append(f"{label}: missing authored overshoot beats at "
                          f"{anchor_time:.2f}/{overshoot_time:.2f}s")
            continue
        anchor_value = anchor[2][axis]
        overshoot_value = overshoot[2][axis]
        if abs(anchor_value) < minimum or abs(overshoot_value) < minimum \
                or anchor_value * overshoot_value >= 0:
            errors.append(f"{label}: recovery must cross rest with at least "
                          f"{minimum:.2f} authored overshoot; got "
                          f"{anchor_value:.2f} -> {overshoot_value:.2f}")

    def sample(channel, at):
        """Linearly sample a source channel for deterministic joint QA.

        All current MELEE plant beats are authored on the 20 Hz tick grid, so
        exact samples dominate. Linear interpolation intentionally provides a
        conservative fallback if optional root/leg compensation is later
        authored at a lower cadence.
        """
        if not channel:
            return (0.0, 0.0, 0.0)
        ordered = sorted(channel, key=lambda frame: frame[0])
        for frame in ordered:
            if abs(frame[0] - at) <= 1e-6:
                return tuple(frame[2])
        if at <= ordered[0][0]:
            return tuple(ordered[0][2])
        if at >= ordered[-1][0]:
            return tuple(ordered[-1][2])
        before, after = next(
            (a, b) for a, b in zip(ordered, ordered[1:])
            if a[0] < at < b[0]
        )
        mix = (at - before[0]) / (after[0] - before[0])
        return tuple(a + (b - a) * mix
                     for a, b in zip(before[2], after[2]))

    def rotate_xyz(vector, degrees):
        """Minecraft model-space XYZ Euler rotation for a point vector."""
        x, y, z = vector
        rx, ry, rz = (math.radians(value) for value in degrees)
        cos_x, sin_x = math.cos(rx), math.sin(rx)
        y, z = y * cos_x - z * sin_x, y * sin_x + z * cos_x
        cos_y, sin_y = math.cos(ry), math.sin(ry)
        x, z = x * cos_y + z * sin_y, -x * sin_y + z * cos_y
        cos_z, sin_z = math.cos(rz), math.sin(rz)
        x, y = x * cos_z - y * sin_z, x * sin_z + y * cos_z
        return (x, y, z)

    def add(*vectors):
        return tuple(sum(vector[axis] for vector in vectors)
                     for axis in range(3))

    def foot_position(side, at):
        # SettlerModel pivots in model pixels; the foot endpoint is twelve
        # pixels below the hip. Measuring the final joint-space point catches
        # the old +/-30-degree leg lunge as ~6.2 px of physical foot slide.
        pivot_x = -2.6 if side == "right" else 2.6
        pivot = (pivot_x, -12.0, 0.0)
        foot_from_hip = (0.0, 12.0, 0.0)
        leg_rotation = sample(channels.get((f"{side}_leg", "ROTATION")), at)
        raw_leg_position = sample(channels.get((f"{side}_leg", "POSITION")), at)
        # KeyframeAnimations.posVec stores -Y internally before ModelPart's
        # offset target consumes it. Mirror that production transform here.
        leg_position = (raw_leg_position[0], -raw_leg_position[1],
                        raw_leg_position[2])
        root_rotation = sample(channels.get(("root", "ROTATION")), at)
        raw_root_position = sample(channels.get(("root", "POSITION")), at)
        root_position = (raw_root_position[0], -raw_root_position[1],
                         raw_root_position[2])
        articulated = add(pivot, leg_position,
                          rotate_xyz(foot_from_hip, leg_rotation))
        return add(root_position, rotate_xyz(articulated, root_rotation))

    plant_ticks = [round(tick * 0.05, 2) for tick in range(7)]
    max_displacements = {}
    for side in ("right", "left"):
        origin = foot_position(side, 0.0)
        max_displacements[side] = max(
            math.dist(origin, foot_position(side, at))
            for at in plant_ticks
        )
    plant_limit = 0.75
    if min(max_displacements.values()) > plant_limit + 1e-6:
        errors.append("MELEE foot-plant contract: no consistent support foot "
                      f"stays within {plant_limit:.2f}px through 0.00–0.30s; "
                      f"right={max_displacements['right']:.2f}px, "
                      f"left={max_displacements['left']:.2f}px. Leave lower "
                      "body to the base or author real root/hip compensation.")

    # Compose authored torso/head rotations with the real MeleeAttackGoal
    # target-bearing body yaw, netHeadYaw and pitch. Their hierarchy means a
    # locally attractive pose can still turn the actual eyes away in game.
    torso = channels.get(("torso", "ROTATION"), [])
    head = channels.get(("head", "ROTATION"), [])
    torso_at = {round(frame[0], 2): frame[2] for frame in torso}
    head_at = {round(frame[0], 2): frame[2] for frame in head}
    if target_look is not None:
        for beat in (0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40):
            if beat not in torso_at or beat not in head_at:
                errors.append("MELEE target-bearing gaze contract: missing "
                              f"torso/head key at {beat:.2f}s")
                continue
            world_yaw = (target_look["bodyYawDegrees"]
                         + torso_at[beat][1] + head_at[beat][1]
                         + target_look["netHeadYawDegrees"])
            yaw_error = abs(_wrap_degrees(
                world_yaw - target_look["targetBearingDegrees"]))
            if yaw_error > 8.0:
                errors.append("MELEE target-bearing gaze contract"
                              f"@{beat:.2f}: composed eyes are {yaw_error:.1f}deg "
                              "off the physical target")
            world_pitch = (torso_at[beat][0] + head_at[beat][0]
                           + target_look["headPitchDegrees"])
            if abs(world_pitch - target_look["rawPitchDegrees"]) > 12.0:
                errors.append("MELEE target-bearing gaze contract"
                              f"@{beat:.2f}: composed pitch is {world_pitch:.1f}deg "
                              "away from the physical target")


def check_melee_runtime_composition_source(entity_text, model_text, errors):
    """Pin locomotion/martial ownership at the real runtime composition site.

    WALK owns root, cloak and both legs, but its phase-dependent arm, head and
    torso channels must be discarded before the stable martial base is
    applied. This gate intentionally parses the composition code rather than
    trusting a visually favourable WALK phase in offline evidence.
    """
    entity = strip_comments(entity_text)
    model = strip_comments(model_text)

    patrol = re.search(
        r"patrolState\s*\.\s*animateWhen\s*\((.*?)\s*,\s*tickCount\s*\)\s*;",
        entity, flags=re.S)
    if patrol is None:
        errors.append("MELEE runtime composition: patrolState.animateWhen source "
                      "was not found")
    else:
        expression = patrol.group(1)
        if not re.search(r"\bmoving\b", expression):
            errors.append("MELEE runtime composition: martial base must be owned "
                          "only while locomotion is moving")
        guard = re.search(
            r"profession\s*==\s*Profession\.GUARD(?P<body>.*?)"
            r"\|\|\s*\(\s*profession\s*==\s*Profession\.ARCHER",
            expression, flags=re.S)
        guard_body = guard.group("body") if guard else ""
        if guard is None \
                or "activity == SettlerActivity.PATROLLING" not in guard_body \
                or "activity == SettlerActivity.COMBAT" not in guard_body:
            errors.append("MELEE runtime composition: moving GUARD must own its "
                          "stable martial base in both PATROLLING and COMBAT")

    martial_start = model.find("else if (entity.patrolState.isStarted()")
    martial_end = model.find("else if (entity.carryState.isStarted()",
                             martial_start + 1)
    if martial_start < 0 or martial_end < 0:
        errors.append("MELEE runtime composition: moving martial branch was not "
                      "found in SettlerModel")
        martial = ""
    else:
        martial = model[martial_start:martial_end]

    patrol_apply = martial.find("SettlerAnimations.GUARD_PATROL")
    for bone in ("rightArm", "leftArm", "head", "torso"):
        reset = martial.find(f"{bone}.resetPose()")
        if reset < 0 or patrol_apply < 0 or reset > patrol_apply:
            errors.append("MELEE gait-arm contamination: moving martial branch "
                          f"must reset {bone} before GUARD_PATROL")
    for locomotion_part in ("root", "rightLeg", "leftLeg"):
        if f"{locomotion_part}.resetPose()" in martial:
            errors.append("MELEE locomotion ownership: moving martial branch must "
                          f"not reset WALK-owned {locomotion_part}")
    if 'torso.getChild("cloak").resetPose()' in martial:
        errors.append("MELEE locomotion ownership: moving martial branch must not "
                      "reset WALK-owned cloak")

    melee_calls = list(re.finditer(
        r"animate\s*\(\s*entity\.meleeState\s*,\s*"
        r"SettlerAnimations\.MELEE\s*,", model, flags=re.S))
    if len(melee_calls) != 1:
        errors.append("MELEE runtime composition: exactly one EV_MELEE animation "
                      f"application is required, found {len(melee_calls)}")
        return
    melee_position = melee_calls[0].start()
    stance_position = model.find("SettlerAnimations.GUARD_STANCE")
    if martial_start < 0 or melee_position < martial_end \
            or stance_position < 0 or melee_position < stance_position:
        errors.append("MELEE runtime composition: zero-offset strike must be "
                      "applied after the moving or stationary martial base")
    condition_window = model[max(0, melee_position - 260):melee_position]
    for requirement in ("entity.meleeState.isStarted()",
                        "profession == Profession.GUARD", "physicalSword"):
        if requirement not in condition_window:
            errors.append("MELEE runtime composition: strike application must be "
                          f"guarded by {requirement}")


def check_melee_runtime_composition_files(errors):
    try:
        with open(SETTLER_ENTITY_SOURCE, encoding="utf-8") as source_file:
            entity_text = source_file.read()
        with open(SETTLER_MODEL_SOURCE, encoding="utf-8") as source_file:
            model_text = source_file.read()
    except OSError as exc:
        errors.append(f"MELEE runtime composition: cannot read source: {exc}")
        return
    check_melee_runtime_composition_source(entity_text, model_text, errors)


def main():
    errors = []
    warns = []

    # ---- 17.1 keep + 17.2 structural, once per registered source --------
    defs = {}
    for source in ANIMATION_SOURCES:
        if not os.path.isfile(source["path"]):
            errors.append(f"{source['label']}: source file not found: {source['path']}")
            continue
        source_defs = parse_definitions(source["path"])
        assert source_defs, f"{source['label']}: no definitions parsed from {source['path']}"
        dupes = set(source_defs) & set(defs)
        if dupes:
            errors.append(f"clip name(s) declared in more than one animation source: "
                          f"{sorted(dupes)}")
        defs.update(source_defs)
        check_structural(source, source_defs, errors, warns)

    check_melee_transition_contract(defs, errors)
    check_melee_runtime_composition_files(errors)
    check_activity_animation_reachability(defs, errors)
    check_crafting_truth_contract(defs, errors)

    # 17.2-12: catalogue coverage.
    catalogued = parse_catalogue_clip_names(CATALOGUE)
    if catalogued:
        implemented = set(defs.keys())
        missing = sorted(catalogued - implemented)
        uncatalogued = sorted(implemented - catalogued)
        if missing:
            warns.append(f"catalogued but not yet implemented ({len(missing)}, phased "
                         f"authoring expected): {', '.join(missing[:12])}"
                         + (" ..." if len(missing) > 12 else ""))
        for name in uncatalogued:
            errors.append(f"{name}: implemented but not in ANIMATION_CATALOGUE.md -- "
                          f"every clip must be designed there first")
    else:
        warns.append("ANIMATION_CATALOGUE.md not found or has no clip headings -- "
                     "skipped catalogue-coverage check")

    # 17.3: sound-sync contract table.
    goal_contracts = parse_goal_tick_contracts(AI_DIR)
    sounds_data = load_sounds_json()
    check_offline_prop_contract(defs, errors, warns)
    for clip, bone, target, accent_s, sound_field, tick, period in SOUND_CONTRACTS:
        d = defs.get(clip)
        if d is None:
            errors.append(f"sound contract for {clip}: clip not implemented")
            continue
        chan = next((c for c in d["channels"] if c[0] == bone and c[1] == target), None)
        if chan is None:
            errors.append(f"{clip}: sound contract channel {bone}.{target} not found")
            continue
        hit = next((f for f in chan[2] if abs(f[0] - accent_s) < 1e-6), None)
        if hit is None:
            errors.append(f"{clip}: no keyframe at accent_seconds={accent_s} on "
                          f"{bone}.{target}")
        elif hit[3] != "LINEAR":
            errors.append(f"{clip}@{accent_s}: accent keyframe interpolation is "
                          f"{hit[3]}, must be LINEAR")
        expect_tick = round(accent_s * 20)
        if expect_tick != tick:
            errors.append(f"{clip}: accent_seconds={accent_s} -> tick {expect_tick}, "
                          f"but the contract table says tick {tick} (fix the table)")
        expect_period = round(d["length"] * 20)
        if expect_period != period:
            errors.append(f"{clip}: length={d['length']}s -> period {expect_period}, "
                          f"but the contract table says period {period} (fix the table)")
        if (period, tick) not in goal_contracts:
            errors.append(f"{clip}: no AI goal source has 'x % {period} == {tick}' -- "
                          f"the sound-sync contract (comment/goal/checker) is broken")
        sound_key = sound_field.lower()
        if sound_key not in sounds_data:
            errors.append(f"{clip}: sound '{sound_key}' (ModSounds.{sound_field}) has no "
                          f"entry in sounds.json")

        # 17.3-16: impact-frame neighbours (warning).
        idx = chan[2].index(hit) if hit else -1
        if idx > 0:
            prev = chan[2][idx - 1]
            if prev[3] != "LINEAR" and (hit[0] - prev[0]) > 0.10:
                warns.append(f"{clip}@{accent_s}: the preceding keyframe at {prev[0]}s is "
                             f"CATMULLROM and more than 0.10s before the impact -- risk of "
                             f"pre-swinging through the contact point")

    # 17.3 (extended): entity-scheduler / one-shot sound contracts.
    check_entity_sound_contracts(defs, sounds_data, errors, warns)

    # 17.3 (crafter trades, audit F8): the Employment-table contract.
    check_crafter_sound_contracts(defs, errors, warns)

    # HAUL_LOG is deliberately an arms-only persistent hold. It must stay
    # active while the worker pauses at storage; adding `&& moving` here
    # makes both arms snap to bind pose at the walk-speed threshold even
    # though the animation definitions themselves are correct.
    entity_path = os.path.join(ROOT,
        "src/main/java/com/hearthstead/entity/SettlerEntity.java")
    if not os.path.isfile(entity_path):
        errors.append("HAUL_LOG state gate: SettlerEntity.java not found")
    else:
        entity_text = strip_comments(open(entity_path, encoding="utf-8").read())
        persistent_haul_gate = re.search(
            r'haulState\.animateWhen\(\s*haulPoseBlend\s*>\s*0\.0F\s*,'
            r'\s*tickCount\s*\)\s*;', entity_text)
        if persistent_haul_gate is None:
            errors.append("HAUL_LOG state gate must remain alive for haulPoseBlend -- "
                          "a moving/activity-edge gate snaps the arms at start or stop")

    # 17.4-24: head-damping table cross-check. Structural, not a bare
    # substring search: scoped to the actual `damp = ...F;` assignment
    # block (found from its own `float damp;` declaration) so a coincidental
    # occurrence of the same number elsewhere in a 1000+ line file can't
    # produce a false pass -- only real assignments inside the damping
    # cascade itself count.
    model_path = os.path.join(ROOT,
        "src/main/java/com/hearthstead/client/model/SettlerModel.java")
    if not os.path.isfile(model_path):
        errors.append("damping table: SettlerModel.java not found")
    else:
        model_text = strip_comments(open(model_path, encoding="utf-8").read())
        block_m = re.search(r'\bfloat\s+damp\s*;(.*?)head\.yRot', model_text, re.S)
        if block_m is None:
            errors.append("damping table: no 'float damp;' cascade found in "
                          "SettlerModel.setupAnim -- head-tracking damping is unwired")
        else:
            assigned = {round(float(v), 3)
                       for v in re.findall(r'\bdamp\s*=\s*([\d.]+)F\s*;', block_m.group(1))}
            for clip, damp in DAMPING_TABLE.items():
                if round(float(damp), 3) not in assigned:
                    errors.append(f"damping table: {clip} needs damp={damp} assigned "
                                  f"somewhere in SettlerModel.setupAnim's damping cascade, "
                                  f"no such assignment found")

    # 17.4-25: per-entity variation call sites (grep-based, warning).
    for clip in PER_ENTITY_VARIATION_CLIPS:
        needle = f"SettlerAnimations.{clip}"
        text = open(model_path, encoding="utf-8").read() if os.path.isfile(model_path) else ""
        if needle in text:
            around = text[text.index(needle):text.index(needle) + 200]
            if "% " not in around and "id %" not in text:
                warns.append(f"{clip}: no visible per-entity phase-offset/jitter call site "
                             f"near its animate() call -- crowds may move in unison")
        else:
            warns.append(f"{clip}: not wired in SettlerModel.setupAnim -- cannot check "
                         f"per-entity variation")

    total = sum(len(d["channels"]) for d in defs.values())
    print(f"parsed {len(defs)} definitions, {total} channels")
    for w in warns:
        # ASCII labels keep the direct checker usable in stock Windows
        # PowerShell, whose inherited cp1252 stdout cannot encode the old
        # warning/cross glyphs. The QA wrapper still captures identical text.
        print("  [WARN]", w)
    if errors:
        for e in errors:
            print("  [ERROR]", e)
        print(f"anim check FAIL: {len(errors)} error(s), {len(warns)} warning(s)")
        sys.exit(1)
    print(f"anim check PASS ({len(warns)} warning(s))")


if __name__ == "__main__":
    main()
