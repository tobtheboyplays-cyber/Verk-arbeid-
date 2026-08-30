#!/usr/bin/env python3
"""Fail-closed validator for Hearthstead's native in-game approval evidence.

This is intentionally separate from the headless/full controller verdict.
Builds, GameTests, static previews, Xvfb captures, and offline audio analysis
can produce a Candidate. Only this validator can accept an APPROVED native
client evidence set, and it refuses partial or stale matrices.
"""

from __future__ import annotations

import argparse
import array
import datetime as dt
import hashlib
import hmac
import json
import math
import os
import re
import shutil
import stat
import struct
import subprocess
import sys
import uuid
import wave
import zipfile
import zlib
from pathlib import Path
from typing import NoReturn

# ``hearthstead-qa`` deliberately launches this validator with ``python3 -I``.
# Isolated mode removes the script directory from sys.path, so bind sibling
# imports to this script's resolved, source-fingerprinted directory instead of
# inheriting an ambient PYTHONPATH or the caller's working directory.
SCRIPT_DIRECTORY = Path(__file__).resolve(strict=True).parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))

from native_evidence_contract import (
    ACK_FIELDS,
    ACK_MARKER,
    ACTIVATION_FIELDS,
    ACTIVATION_MARKER,
    EvidenceContractError,
    INPUT_DRIVER,
    INPUT_RECORD_SCHEMA,
    INPUT_TRANSCRIPT_PATH,
    LAUNCH_REGISTRY_PATH,
    LOG_SEGMENT_INDEX_PATH,
    canonical_json,
    chained_record_sha256,
    launch_identity_sha256,
    local_absolute_path,
    parse_fixed_log_record,
    parse_launch_registry,
    parse_operator_seal_ledger,
    path_token as contract_path_token,
    require_plain_path,
)


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MATRIX = ROOT / "qa" / "release_client_matrix.json"
SHA256 = re.compile(r"[0-9a-f]{64}")
SESSION = re.compile(r"[A-Za-z0-9_-]{8,80}")
VALID_NONCE = re.compile(r"[A-Za-z0-9_-]{1,80}")
PROFILE = re.compile(r"([1-9][0-9]*)x([1-9][0-9]*)-gui([1-8])-(en_us|nb_no)")
UTC_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]{1,6})?Z"
)
ACK = ACK_MARKER + " "
SERVER_AUTHORITY_LINE = re.compile(
    r"\[Server thread/INFO\]\s+\[hearthstead(?:/[^\]]*)?\]", re.IGNORECASE
)
AUTHORITY_MARKER = "HEARTHSTEAD_AUTHORITY_V1"
AUTHORITY_MAX_PAYLOAD = 768
AUTHORITY_TOKEN = re.compile(r"[A-Za-z0-9_.:/@+\-]{1,96}")
AUTHORITY_FIELDS = (
    "event", "result", "settlement", "target", "revision_before",
    "revision_after", "count_before", "count_after", "item",
    "item_before", "item_after", "item_expected_delta", "item_conserved",
    "reason", "tick",
)
AUTHORITY_EVENTS = frozenset({
    "FOUNDING_COMMITTED",
    "DEVELOPMENT_NODE_COMMITTED",
    "PLAN_UNLOCK_COMMITTED",
    "DOCTRINE_COMMITTED",
    "EMBLEM_PURCHASED",
    "MAYOR_APPOINTED",
    "EMPLOYMENT_AUTO_HIRED",
    "EQUIPMENT_REQUEST_OPENED",
    "EQUIPMENT_REQUEST_CLAIMED",
    "EQUIPMENT_ITEM_PICKED_UP",
    "EQUIPMENT_ITEM_DELIVERED",
    "REQUEST_LEDGER_VIEWED",
    "OUTPUT_PICKUP_REQUEST_OPENED",
    "OUTPUT_PICKUP_RESERVED",
    "OUTPUT_PICKUP_PICKED_UP",
    "OUTPUT_PICKUP_DELIVERED",
    "OUTPUT_PICKUP_SATISFIED",
    "OUTPUT_PICKUP_BLOCKED",
    "COURIER_ROUTE_CLAIMED",
    "COURIER_ITEM_PICKED_UP",
    "COURIER_ITEM_DELIVERED",
    "RECRUITMENT_QUALIFICATION_STARTED",
    "TRAVELER_ARRIVED_AT_TAVERN",
    "TRAVELER_ADMITTED",
    "RECRUITMENT_COMMITTED",
    "GUARD_ORDER_COMMITTED",
    "RAID_READINESS_COMMITTED",
    "RAID_WARNING_COMMITTED",
    "RAID_STARTED",
    "RAID_RESOLVED",
    "RAID_REWARD_ISSUED",
    "RAID_AFTERMATH_VIEWED",
    "BLESSING_OFFER_COMMITTED",
    "BLESSING_BOUND_SETTLER",
    "BLESSING_BOUND_BUILDING",
    "WORK_ZONE_PREVIEWED",
    "WORK_ZONE_CANCELLED",
    "WORK_ZONE_COMMITTED",
    "LUMBER_TREE_COMMITTED",
    "FARM_SEED_PLANTED_COMMITTED",
    "FARM_HARVEST_COMMITTED",
    "WORKPLACE_OUTPUT_COMMITTED",
    "BUILDING_LINK_COMMITTED",
    "SETTLER_INVENTORY_TRANSFER_COMMITTED",
    "GUARD_XP_COMMITTED",
    "MELEE_CONTACT_COMMITTED",
    "SHIELD_BLOCK_COMMITTED",
    "ARCHER_CONTACT_COMMITTED",
    "STATE_LOAD_SUMMARY",
    "AUTHORITY_REJECTED",
})
AUTHORITY_RESULTS = frozenset({"COMMITTED", "REJECTED", "OBSERVED"})
CRITICAL_RAID_AUTHORITY_EVENTS = frozenset({
    "RAID_WARNING_COMMITTED",
    "RAID_AFTERMATH_VIEWED",
})
CANONICAL_UUID = (
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
)
RAID_OBJECTIVE = r"(?:korn|blod|brann|losepenger)"
RAID_WARNING_TARGET = re.compile(
    rf"first_raid_warning:(0|[1-9][0-9]*):captain:({CANONICAL_UUID})"
)
RAID_WARNING_REASON = re.compile(
    rf"persisted_plan:({RAID_OBJECTIVE}):approach_bits:(0|[1-9][0-9]*)"
)
RAID_AFTERMATH_TARGET = re.compile(
    r"raid_aftermath:(0|[1-9][0-9]*):report:([0-9a-f]{64})"
)
RAID_AFTERMATH_REASON = re.compile(
    rf"report_viewed:(?:held|lost):({RAID_OBJECTIVE}):"
    r"stolen:(0|[1-9][0-9]*):hurt:(0|[1-9][0-9]*):"
    r"stage:(?:rolig|uro|varsel|beleiring)"
)
MAX_REPORTED_RAID_NIGHT = 10_000_000
MAX_REPORTED_RAID_COUNT = 1_000_000
NATIVE_LOG_SEGMENT_INDEX = "logs/native-log-segments.json"
NATIVE_INPUT_TRANSCRIPT = "logs/native-input.jsonl"
NATIVE_LAUNCH_REGISTRY = "logs/native-launch-registry.jsonl"
NATIVE_INPUT_ACTIONS = frozenset({
    "focus", "move", "click", "modified-click", "scroll", "key",
    "hold", "look", "text",
})
NATIVE_INPUT_KEYS = frozenset({
    "backspace", "enter", "shift", "escape",
    "space", "pageup", "pagedown", "end", "home", "left", "up",
    "right", "down", "delete",
    *tuple(str(number) for number in range(10)),
    *tuple(chr(code) for code in range(ord("a"), ord("z") + 1)),
    *tuple(f"f{number}" for number in range(1, 13)),
})
ALLOWED_SUFFIXES = {
    "audio": {".wav"},
    "client_log": {".log"},
    "contact_sheet": {".png"},
    "frame_report": {".json"},
    "screenshot": {".png"},
    "server_log": {".log"},
    "video": {".mp4"},
    "world_manifest": {".json"},
}
EVIDENCE_DIRECTORIES = {
    "audio": "audio",
    "client_log": "logs",
    "contact_sheet": "film",
    "frame_report": "logs",
    "screenshot": "shots",
    "server_log": "logs",
    "video": "film",
    "world_manifest": "world",
}
REQUIRED_DIRECTORIES = ("logs", "shots", "film", "audio", "world")
REQUIRED_MANIFEST_STRINGS = (
    "gitCommit",
    "dirtyHash",
    "minecraftVersion",
    "neoforgeVersion",
    "javaVersion",
    "renderer",
    "displayProfile",
    "language",
    "worldId",
    "worldSeed",
    "startedAt",
    "finishedAt",
    "audioOutputDevice",
    "nativeSessionId",
    "gameDirectory",
    "worldDirectory",
)
REQUIRED_DISPLAY_PROFILES = (
    "1280x720-gui2-en_us",
    "1280x720-gui3-en_us",
    "1280x720-gui3-nb_no",
    "1920x1080-gui4-en_us",
)
REQUIRED_MATRIX_ROW_IDS = (
    "identity.exact_jar_native_boot",
    "identity.keyboard_mouse_look_round_trip",
    "journey.found_hearth_three_settlers",
    "journey.hearth_and_development_navigation",
    "journey.research_cost_and_plan_unlock",
    "journey.house_plaque_and_housing_capacity",
    "journey.mayor_appointment_and_emblem_shop",
    "journey.emblem_auto_hire",
    "journey.settler_inventory_and_need_icon",
    "journey.lumber_work_zone",
    "journey.cultivated_ground_farmer_unlock",
    "journey.farm_work_zone",
    "journey.stores_roads_courier_unlock",
    "journey.hospitality_tavern_unlock",
    "journey.tavern_recruitment_fourth_settler",
    "journey.first_watch_barracks_guard_unlock",
    "journey.arm_the_watch_guard_weapon_delivery",
    "journey.watchtower_archer_emblem_and_bow",
    "journey.first_raid_readiness_warning_attack",
    "journey.raid_resolution_and_reward",
    "journey.shield_doctrine_permanent_choice",
    "journey.blessing_settler_permanent",
    "journey.blessing_building_permanent",
    "journey.restart_persistence",
    "role.lumberer_complete_physical_loop",
    "role.farmer_complete_physical_loop",
    "role.courier_request_collection_delivery",
    "role.guard_equipment_hold_defend_patrol",
    "role.guard_combat_credit_xp_and_return",
    "role.archer_bow_stance_patrol_and_combat",
    "ui.hearth_mayor_journey",
    "ui.development_pan_zoom_inspector",
    "ui.emblem_shop_scroll_and_purchase",
    "ui.plaque_requirements_and_plan",
    "ui.settler_sheet_inventory_guard_orders",
    "ui.work_scepter_zone_preview_confirm_cancel",
    "ui.courier_request_list",
    "ui.blessing_offer_select_confirm_later",
    "ui.handbook_navigation",
    "ui.en_us_compact_normal_large",
    "ui.nb_no_normal",
    "animation.lumber_pack_ground_contact_turns",
    "animation.farmer_work_and_carry",
    "animation.courier_pack_and_transfer",
    "animation.guard_sword_hit_react",
    "animation.guard_melee_contact_authority",
    "animation.guard_real_shield_block",
    "animation.archer_bow_stance_and_patrol",
    "animation.raider_arrival_combat_death",
    "audio.ui_and_research_mix",
    "audio.work_contact_mix",
    "audio.combat_guard_xp_mix",
    "audio.founding_recruitment_raid_blessing_mix",
    "authority.invalid_range_stale_revision_no_mutation",
    "authority.work_zone_invalid_stale_unloaded_no_mutation",
    "authority.melee_interruption_no_early_damage",
    "stability.no_ui_fps_regression_or_log_errors",
)
PROFILE_BOUND_KINDS = {
    "audio", "client_log", "contact_sheet", "frame_report", "screenshot", "video"
}
RAW_MATERIALS = {
    "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
    "dark_oak_log", "mangrove_log", "cherry_log",
    # Minimal first-raid survival-crafting inputs.  Finished plans, plaques,
    # furniture, equipment and progression items are deliberately absent.
    "bread", "coal", "cobblestone", "copper_ingot", "dirt", "feather",
    "flint", "gold_ingot", "iron_ingot", "leather", "stick", "string",
    "sugar_cane", "wheat_seeds", "white_wool",
}
SHELL_BLOCKS = {
    "andesite", "bricks", "cobbled_deepslate", "cobblestone",
    "coarse_dirt", "deepslate_bricks", "diorite", "dirt", "glass",
    "granite", "grass_block", "gravel", "mossy_cobblestone",
    "mossy_stone_bricks", "sand", "smooth_stone", "stone",
    "stone_bricks",
    "oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
    "acacia_planks", "dark_oak_planks", "mangrove_planks",
    "cherry_planks", "bamboo_planks",
}
HOSTILE_SUMMONS = {
    "cave_spider", "drowned", "husk", "pillager", "ravager", "skeleton",
    "spider", "stray", "vindicator", "witch", "zombie", "zombie_villager",
}
MAX_FILL_AXIS = 128
MAX_FILL_VOLUME = 65_536
MAX_RELATIVE_COORDINATE = 256
RECENT_RUN_AGE = dt.timedelta(hours=24)
CLOCK_SKEW = dt.timedelta(minutes=5)
DEFAULT_EXPECTED_GAME_DIRECTORY = (
    Path(r"C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)")
    if os.name == "nt"
    else Path("/mnt/c/Users/tobia/curseforge/minecraft/Instances/SIVILASJON (1)")
)
MANDATORY_ASSERTIONS = {
    "identity.exact_jar_native_boot": (
        "The native client boots from the exact installed candidate JAR, reports the same SHA-256 and game directory through the observer, joins the bound fresh world, and shows no duplicate Hearthstead JAR, development classpath, crash, missing resource, or loader error.",
        "The first full-frame world capture and integrated-server acknowledgement share the same native session, world identity, display profile, language, and post-launch epoch.",
    ),
    "identity.keyboard_mouse_look_round_trip": (
        "Registered physical keyboard input moves the player by at least the required distance while Minecraft owns the foreground and has the mouse grabbed, and a release returns every pressed key to the neutral state.",
        "Registered physical mouse input changes both camera pick direction and yaw or pitch by the required amount, produces row-nonced integrated-client acknowledgements, and leaves no unmatched input intent.",
    ),
    "journey.hearth_and_development_navigation": (
        "Right-clicking the physical Hearth opens the settlement-bound Hearth screen, and Journey and Development are reachable through visible controls with clear current objectives, blockers, costs, building descriptions, and a reliable Back path.",
        "Repeated tab changes, scrolling, Development pan and zoom, modal open and close, and returning to the world produce no overlap, dead control, stale settlement data, duplicate overlay, or visible frame hitch.",
    ),
    "journey.first_watch_barracks_guard_unlock": (
        "After four physically housed settlers, First Watch displays and consumes its exact cost once, teaches the Barracks plan and Guard Emblem, and keeps both unavailable before the housing objective is satisfied.",
        "A player-built valid Barracks links through one consumed plan and stable plaque, registers accessible storage, and exposes Guard auto-hire, weapon need, Courier delivery, and Guard Orders without a separate Hire action.",
    ),
    "journey.shield_doctrine_permanent_choice": (
        "After the first raid and exact objective and cost are satisfied, the confirmation names all three permanent doctrine trade-offs, commits exactly one selected branch, and leaves the other two unavailable without consuming their costs.",
        "Shield Doctrine is a post-raid permanent specialization choice; it does not unlock the baseline Watchtower, Archer Emblem or Tower Post needed for the first raid, and its copy never tells the player otherwise.",
        "The chosen doctrine's effect, icon, sound, Development state, defender behavior, and exclusion of both alternatives remain identical after a full client restart.",
    ),
    "journey.blessing_settler_permanent": (
        "With one physical Blessing Seal visible in hand, Shift-right-clicking a valid same-settlement settler consumes exactly one seal, binds exactly one permanent rank, and presents contact-synchronised visual feedback without mutating another target.",
        "Invalid, cross-settlement, duplicate, empty-hand, and stale attempts consume nothing; the exact settler identity and Blessing rank remain inspectable and unchanged after restart.",
    ),
    "journey.blessing_building_permanent": (
        "With one physical Blessing Seal visible in hand, Shift-right-clicking a valid same-settlement linked plaque consumes exactly one seal, binds exactly one permanent building rank, and presents contact-synchronised feedback on that plaque only.",
        "Invalid, unlinked, cross-settlement, duplicate, and stale plaque attempts consume nothing; the exact building identity and Blessing rank remain inspectable and unchanged after restart.",
    ),
    "authority.invalid_range_stale_revision_no_mutation": (
        "An out-of-range or invalid-menu interaction and a stale Development revision are both rejected by the integrated server with the expected reasons, while screen authority, node revision, inventories, costs, settlement state, and learned plans remain byte-for-byte unchanged.",
        "A fresh in-range retry with the current revision succeeds at most once, proving the rejected packets neither queued a delayed mutation nor consumed the later valid transaction.",
    ),
    "authority.melee_interruption_no_early_damage": (
        "Interrupting or invalidating a Guard melee wind-up before the authored contact frame causes zero target-health change, zero durability or combat-ledger credit, and no blade-hit or success cue.",
        "One later uninterrupted retry commits exactly one contact, one bounded damage delta, one authority record, and one matching impact cue without replaying the cancelled strike.",
    ),
    "journey.mayor_appointment_and_emblem_shop": (
        "Inspect Mayor opens the ordinary settler sheet for the server-authenticated Mayor identity.",
        "Out-of-range, wrong-identity, and stale-revision Mayor inspection attempts are denied without opening or mutating a sheet.",
    ),
    "journey.settler_inventory_and_need_icon": (
        "A newly assigned worker starts without fabricated job tools and exposes a visible need icon and request.",
        "Shift-right-click with both main hand and offhand empty opens that exact settler inventory and a player item transfer persists server-side.",
    ),
    "journey.lumber_work_zone": (
        "The player selects two physical 3D corners with the Work Scepter, reviews a transparent full-height Lumber Work Zone preview, and explicitly confirms or cancels before any server commit.",
        "A confirmed Lumber Work Zone persists exact bounds and a monotonic revision through restart; without one the Lumberer stays idle behind a truthfully labelled no-zone fallback.",
    ),
    "journey.cultivated_ground_farmer_unlock": (
        "The Farmer begins without a hoe, requests it visibly, receives it through real inventory transfer, and deposits harvest in the farm job chest.",
    ),
    "journey.farm_work_zone": (
        "The player selects two physical 3D corners with the Work Scepter, reviews a transparent full-height Farm Work Zone preview, and explicitly confirms or cancels before any server commit.",
        "A confirmed Farm Work Zone persists exact bounds and a monotonic revision through restart; without one the Farmer stays idle behind a truthfully labelled no-zone fallback.",
    ),
    "journey.stores_roads_courier_unlock": (
        "The Courier reads the visible request list, collects from a real worker job chest, carries the item, and completes one server-authoritative delivery.",
    ),
    "journey.arm_the_watch_guard_weapon_delivery": (
        "The Guard starts without a weapon, raises a visible equipment need, and equips only after a real Courier delivery.",
        "Arm the Watch remains locked until that exact Guard delivery objective is committed; buying it before the first raid unlocks the baseline Watchtower plan, Archer Emblem and Tower Post command without granting Shield Doctrine.",
    ),
    "journey.watchtower_archer_emblem_and_bow": (
        "After FJ-550 and Arm the Watch, Journey visibly requires at least five unique loaded physical bed heads, then durably binds the first eligible Watch-slot Tavern transaction, including the same nonterminal natural transaction already running when fifth-bed proof lands: four-to-eight-minute Call to Arms qualification on that attempt and every retry, physical arrival, real joining payment and one distinct admitted Watch recruit; dismissal, timeout, death, invalid Tavern, mature cycle numbers, missing bed, admin priming, and restart cannot skip or permanently deadlock the slot or demand a sixth bed.",
        "One current settler receives an Archer Emblem through one exact purchase-and-bind transaction at the same valid linked Watchtower and must be distinct from the melee Guard; a wrong profession, worker, settlement, building or transaction does not advance staffing.",
        "The Archer starts without a bow or arrows, exposes a visible bow request, and equips only after a real Courier delivery. At least one physical Arrow must be inside storage of this exact employer Watchtower; arrows in an unrelated tower or only an internal quiver do not satisfy the Journey.",
        "FJ-559A truthfully says to stock arrows and then confirm Tower Post. The Tower Post click re-observes the exact rack and intentionally advances the physical-ammo and post facts together, reaches a real standable tower cell, and persists without changing the melee Guard.",
    ),
    "journey.first_raid_readiness_warning_attack": (
        "The server-authored readiness panel lists every remaining blocker, keeps Declare Ready disabled while blocked, and commits one unchanged warning and attack calendar only after all blockers are clear.",
        "Readiness requires five distinct live members and at least five unique physical bed heads plus two different defenders at the same time: Guard 1/1 with a serviceable physical melee weapon and Stand/Patrol order, and Archer 1/1 with a physical bow, a Tower Post order, and physical arrows either in the exact employer Watchtower rack or in that same Archer's bounded persisted quiver after real withdrawal from that rack.",
        "Zero defenders, Guard-only, Archer-only, duplicate UUIDs, wrong profession or employer, missing or unserviceable equipment, arrows in another tower or another settler's quiver, either invalid order, fewer than five members or beds, and any stale or hand-mutated state each remain a visible blocker before and after restart.",
        "The first warning, named captain, objective, approach, and sealed two-to-nine-raider band match the persisted plan and arrive without a teleport pop or silent replacement roll.",
    ),
    "role.lumberer_complete_physical_loop": (
        "The Lumberer starts without an axe, requests and receives one, deposits timber in the lumber job chest, and exposes it for Courier collection.",
        "The lumber bag is attached only while worn; during ground packing the placed bag stays fixed while the Lumberer bends toward it, stows drops, picks it up, and resumes walking without a pop.",
        "The Lumberer never searches, claims, breaks, collects, or deposits work sourced outside the explicitly committed Lumber Work Zone; with no zone it stays idle and the fallback is visibly labelled.",
    ),
    "role.farmer_complete_physical_loop": (
        "The Farmer starts without a hoe, requests and receives one, works only the player-defined field, and deposits harvest in the farm job chest for Courier collection.",
        "The Farmer never tills, plants, searches, harvests, collects, or deposits work sourced outside the explicitly committed Farm Work Zone; with no zone it stays idle and the fallback is visibly labelled.",
    ),
    "role.courier_request_collection_delivery": (
        "Hearth to Requests visibly names the item and count, source-to-target route, assigned Courier, current physical owner, and truthful stop reason before the Courier acts.",
        "The Courier physically removes the exact reserved stack from the correct worker job chest, carries it, inserts it into the exact destination, clears the request only after insertion, and resumes reachable work without duplication or unexplained idle.",
    ),
    "role.archer_bow_stance_patrol_and_combat": (
        "The Archer starts without a bow or arrows, raises a visible request, receives physical equipment through player inventory or a real Courier delivery, and never fires fabricated ammunition.",
    ),
    "ui.plaque_requirements_and_plan": (
        "The plaque and learned build-plan preview remain continuously visible without blinking, z-fighting, stale overlay, or frame-to-frame disappearance.",
    ),
    "ui.work_scepter_zone_preview_confirm_cancel": (
        "The Work Scepter identifies the selected job site and both physical corner blocks, then renders a readable transparent full-height 3D volume without hiding terrain or settlers.",
        "Confirm commits the exact previewed bounds once, Cancel commits nothing, and every default, missing, invalid, stale, or unloaded state is labelled truthfully before input.",
    ),
    "authority.work_zone_invalid_stale_unloaded_no_mutation": (
        "Out-of-range, stale-revision, cross-settlement, oversized, invalid-height, malformed, unloaded-chunk, and spherical-boundary Work Zone requests are denied without mutation or forced chunk loading; every one of the previewed box's eight corners must be inside the settlement.",
        "Only one bounded server-validated two-corner confirmation may advance the persisted zone revision once; preview and Cancel never persist or revise a zone.",
    ),
}
MANDATORY_AUTHORITY_TRANSACTIONS = {
    "journey.first_raid_readiness_warning_attack": (
        {
            "id": "raid_ready",
            "event": "RAID_READINESS_COMMITTED",
            "result": "COMMITTED",
            "targetPrefix": "first_raid_calendar:",
            "reason": "readiness_calendar_committed",
        },
        {
            "id": "raid_warning",
            "event": "RAID_WARNING_COMMITTED",
            "result": "COMMITTED",
            "targetPrefix": "first_raid_warning:",
            "reasonPrefix": "persisted_plan:",
        },
        {
            "id": "raid_started",
            "event": "RAID_STARTED",
            "result": "COMMITTED",
            "targetPrefix": "first_raid:",
            "reason": "participants_sealed",
        },
    ),
    "journey.raid_resolution_and_reward": (
        {
            "id": "raid_resolved",
            "event": "RAID_RESOLVED",
            "result": "COMMITTED",
            "targetPrefix": "first_raid:",
            "reasonPrefix": "settlement_",
        },
        {
            "id": "raid_reward",
            "event": "RAID_REWARD_ISSUED",
            "result": "COMMITTED",
            "targetPrefix": "blessing_offer:",
            "reason": "authoritative_raid_reward",
        },
        {
            "id": "raid_aftermath",
            "event": "RAID_AFTERMATH_VIEWED",
            "result": "COMMITTED",
            "targetPrefix": "raid_aftermath:",
            "reasonPrefix": "report_viewed:",
        },
    ),
}


def block_coordinate(token: str, axis: str) -> tuple[str, int] | None:
    """Parse the deliberately narrow coordinate grammar used by setup shells."""
    if re.fullmatch(r"-?[0-9]+", token):
        value = int(token)
        if axis == "y":
            return ("absolute", value) if -64 <= value <= 319 else None
        return ("absolute", value) if -29_999_984 <= value <= 29_999_984 else None
    relative = re.fullmatch(r"~(-?[0-9]+)?", token)
    if relative is None:
        return None
    value = int(relative.group(1) or 0)
    if abs(value) > MAX_RELATIVE_COORDINATE:
        return None
    return "relative", value


def shell_block_command(tokens: list[str]) -> bool:
    if not tokens or tokens[0] not in {"fill", "setblock"}:
        return False
    coordinate_count = 6 if tokens[0] == "fill" else 3
    # `keep` is mandatory: setup may add inert shell blocks only into air. It
    # may not overwrite a plaque, furnishing, job chest, Hearth or saved state.
    if len(tokens) != coordinate_count + 3 or tokens[-1] != "keep":
        return False
    block = re.fullmatch(r"minecraft:([a-z0-9_]+)", tokens[-2])
    if block is None or block.group(1) not in SHELL_BLOCKS:
        return False
    axes = ("x", "y", "z") * (2 if tokens[0] == "fill" else 1)
    coordinates = [block_coordinate(token, axis)
                   for token, axis in zip(tokens[1:1 + coordinate_count], axes)]
    if any(value is None for value in coordinates):
        return False
    parsed = [value for value in coordinates if value is not None]
    if tokens[0] == "setblock":
        return True
    extents: list[int] = []
    for axis in range(3):
        first = parsed[axis]
        second = parsed[axis + 3]
        if first[0] != second[0]:
            return False
        extent = abs(first[1] - second[1]) + 1
        if extent > MAX_FILL_AXIS:
            return False
        extents.append(extent)
    return math.prod(extents) <= MAX_FILL_VOLUME


def allowed_setup_command(command: str) -> bool:
    value = command.strip()
    if value.startswith("/"):
        value = value[1:]
    lowered = value.lower()
    if re.search(r"(?:^|\s)(?:hearthstead|data|loot|advancement|recipe)(?:\s|$)",
                 lowered):
        return False
    if re.fullmatch(r"(?:weather\s+(?:clear|rain|thunder)(?:\s+[0-9]+)?|"
                    r"time\s+(?:set|add)\s+(?:day|night|noon|midnight|[0-9]+))",
                    lowered):
        return True
    if shell_block_command(lowered.split()):
        return True
    give = re.fullmatch(
        r"give\s+@s\s+minecraft:([a-z0-9_]+)(?:\s+([1-9][0-9]*))?",
        lowered,
    )
    if give is not None:
        count = int(give.group(2) or 1)
        return give.group(1) in RAW_MATERIALS and count <= 64
    # Targeted shield-contact qualification is outside natural progression;
    # this one narrow vanilla offhand setup is listed and reviewed separately.
    if re.fullmatch(
        r"item\s+replace\s+entity\s+\S+\s+weapon\.offhand\s+with\s+"
        r"minecraft:shield(?:\s+1)?", lowered
    ):
        return True
    summon = re.fullmatch(
        r"summon\s+minecraft:([a-z0-9_]+)"
        r"(?:\s+(~(?:-?[0-9]+)?|-?[0-9]+)"
        r"\s+(~(?:-?[0-9]+)?|-?[0-9]+)"
        r"\s+(~(?:-?[0-9]+)?|-?[0-9]+))?",
        lowered,
    )
    if summon is not None and summon.group(1) in HOSTILE_SUMMONS:
        coordinates = summon.groups()[1:]
        if all(value is None for value in coordinates):
            return True
        return all(
            block_coordinate(value, axis) is not None
            for value, axis in zip(coordinates, ("x", "y", "z"))
            if value is not None
        )
    return False


def fail(message: str) -> NoReturn:
    print(f"release-client evidence: {message}", file=sys.stderr)
    raise SystemExit(1)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def validate_critical_raid_authority(
    values: dict[str, str], numbers: dict[str, int], label: str,
) -> None:
    """Apply the Java V1 fail-closed rules for release-critical raid facts."""
    event = values["event"]
    if event not in CRITICAL_RAID_AUTHORITY_EVENTS:
        return
    require(values["result"] == "COMMITTED" and values["settlement"] != "none",
            f"{label} critical raid authority is not a committed settlement event")
    require(numbers["revision_before"] >= 0
            and numbers["revision_before"] < 2 ** 63 - 1
            and numbers["revision_after"] == numbers["revision_before"] + 1,
            f"{label} critical raid authority revision delta is not exactly +1")
    require(numbers["count_before"] < 2 ** 63 - 1
            and numbers["count_after"] == numbers["count_before"] + 1,
            f"{label} critical raid authority completed-count delta is not exactly +1")
    require(values["item"] == "none"
            and numbers["item_before"] == 0
            and numbers["item_after"] == 0
            and numbers["item_expected_delta"] == 0,
            f"{label} critical raid authority invents an item mutation")

    if event == "RAID_WARNING_COMMITTED":
        target = RAID_WARNING_TARGET.fullmatch(values["target"])
        reason = RAID_WARNING_REASON.fullmatch(values["reason"])
        require(target is not None and reason is not None,
                f"{label} raid warning persisted-plan facts are malformed")
        assert target is not None and reason is not None
        night = int(target.group(1))
        approach_bits = int(reason.group(2))
        require(night <= 2 ** 63 - 1 and approach_bits <= 2 ** 32 - 1,
                f"{label} raid warning persisted-plan facts exceed their bounds")
        approach = struct.unpack(">f", approach_bits.to_bytes(4, "big"))[0]
        require(math.isfinite(approach) and -180.0 <= approach < 180.0,
                f"{label} raid warning approach is not a valid persisted float")
        return

    target = RAID_AFTERMATH_TARGET.fullmatch(values["target"])
    reason = RAID_AFTERMATH_REASON.fullmatch(values["reason"])
    require(target is not None and reason is not None,
            f"{label} raid aftermath persisted-report facts are malformed")
    assert target is not None and reason is not None
    require(int(target.group(1)) <= MAX_REPORTED_RAID_NIGHT
            and int(reason.group(2)) <= MAX_REPORTED_RAID_COUNT
            and int(reason.group(3)) <= MAX_REPORTED_RAID_COUNT,
            f"{label} raid aftermath persisted-report facts exceed their bounds")


def require_critical_raid_authority_exactly_once(
    records: list[dict[str, object]],
    required: set[tuple[str, str]],
    label: str,
) -> None:
    """Reject extra, duplicate, or replayed critical events in sealed logs."""
    require({event for event, _settlement in required}
            == CRITICAL_RAID_AUTHORITY_EVENTS,
            f"{label} does not require both release-critical raid events")
    observed = [
        (str(record["event"]), str(record["settlement"]))
        for record in records
        if record["event"] in CRITICAL_RAID_AUTHORITY_EVENTS
    ]
    require(set(observed) == required,
            f"{label} critical raid authority roster contains an extra or missing event")
    for event, settlement in sorted(required):
        require(observed.count((event, settlement)) == 1,
                f"{label} critical raid authority event {event} for settlement "
                f"{settlement} was duplicated/replayed across sealed native logs")


def parse_authority_v1_line(line: str, label: str) -> dict[str, object]:
    """Parse one genuine, fixed-schema, server-thread authority record.

    The native gate intentionally duplicates the Java formatter contract. This
    prevents a human-written substring, client echo, truncated line, reordered
    payload, or conservation-false transaction from masquerading as ordinary
    integrated-server evidence.
    """
    require("\n" not in line and "\r" not in line,
            f"{label} authority record contains a forged line break")
    require(SERVER_AUTHORITY_LINE.search(line) is not None,
            f"{label} authority record is not a genuine integrated "
            "Server-thread Hearthstead line")
    require(line.count(AUTHORITY_MARKER) == 1,
            f"{label} authority record lacks one exact {AUTHORITY_MARKER} marker")
    payload = line[line.index(AUTHORITY_MARKER):]
    require(len(payload) <= AUTHORITY_MAX_PAYLOAD,
            f"{label} authority payload exceeds {AUTHORITY_MAX_PAYLOAD} characters")
    parts = payload.split(" ")
    require(len(parts) == len(AUTHORITY_FIELDS) + 1
            and parts[0] == AUTHORITY_MARKER,
            f"{label} authority payload does not have the fixed V1 field count")

    values: dict[str, str] = {}
    encountered: list[str] = []
    for token in parts[1:]:
        require(token.count("=") == 1,
                f"{label} authority payload has a malformed key/value token")
        key, value = token.split("=", 1)
        require(key not in values,
                f"{label} authority payload repeats field {key!r}")
        require(AUTHORITY_TOKEN.fullmatch(value) is not None,
                f"{label} authority field {key!r} is empty, overlong, or unsafe")
        values[key] = value
        encountered.append(key)
    require(tuple(encountered) == AUTHORITY_FIELDS,
            f"{label} authority payload fields are missing, extra, or reordered")

    require(values["event"] in AUTHORITY_EVENTS,
            f"{label} authority event is outside the V1 vocabulary")
    require(values["result"] in AUTHORITY_RESULTS,
            f"{label} authority result is outside the V1 vocabulary")
    settlement = values["settlement"]
    if settlement != "none":
        try:
            parsed_settlement = uuid.UUID(settlement)
        except ValueError:
            fail(f"{label} authority settlement is not a canonical UUID")
        require(str(parsed_settlement) == settlement,
                f"{label} authority settlement is not a canonical lowercase UUID")

    signed_fields = (
        "revision_before", "revision_after", "count_before", "count_after",
        "item_before", "item_after", "item_expected_delta", "tick",
    )
    numbers: dict[str, int] = {}
    for key in signed_fields:
        raw = values[key]
        require(re.fullmatch(r"-?(?:0|[1-9][0-9]*)", raw) is not None,
                f"{label} authority field {key!r} is not a canonical integer")
        number = int(raw)
        require(-(2 ** 63) <= number <= 2 ** 63 - 1,
                f"{label} authority field {key!r} exceeds signed long range")
        numbers[key] = number
    for key in ("count_before", "count_after", "item_before", "item_after", "tick"):
        require(numbers[key] >= 0,
                f"{label} authority field {key!r} may not be negative")

    require(values["item_conserved"] == "true",
            f"{label} authority record does not prove item conservation")
    require(numbers["item_after"] - numbers["item_before"]
            == numbers["item_expected_delta"],
            f"{label} authority item delta contradicts its conservation claim")

    rejected = values["result"] == "REJECTED"
    require((values["event"] == "AUTHORITY_REJECTED") == rejected,
            f"{label} authority rejection event/result pairing is inconsistent")
    if rejected:
        require(numbers["revision_before"] == numbers["revision_after"]
                and numbers["count_before"] == numbers["count_after"]
                and numbers["item_before"] == numbers["item_after"]
                and numbers["item_expected_delta"] == 0,
                f"{label} rejected authority transaction changed server state")

    validate_critical_raid_authority(values, numbers, label)

    parsed: dict[str, object] = dict(values)
    parsed.update(numbers)
    parsed["item_conserved"] = True
    return parsed


def load_object(path: Path, label: str) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"{label} is unreadable or malformed: {exc}")
    require(isinstance(value, dict), f"{label} root is not an object")
    return value


def is_reparse_point(metadata: os.stat_result) -> bool:
    return bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def require_plain_file(path: Path, label: str, *, nonempty: bool = True) -> Path:
    try:
        return require_plain_path(
            path, label, directory=False, nonempty=nonempty
        )
    except EvidenceContractError as exc:
        fail(str(exc))


def require_plain_directory(path: Path, label: str) -> Path:
    try:
        return require_plain_path(path, label, directory=True)
    except EvidenceContractError as exc:
        fail(str(exc))


def path_is_within(path: Path, root: Path) -> bool:
    """Containment comparison that cannot be bypassed with Windows casing."""
    try:
        common = os.path.commonpath((str(root), str(path)))
    except ValueError:
        return False
    return os.path.normcase(os.path.normpath(common)) == os.path.normcase(
        os.path.normpath(str(root))
    )


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def canonical_identity_path(path: Path) -> str:
    """Return the same privacy-safe path identity input used by the Java observer."""
    rendered = path.resolve(strict=True).as_posix()
    # The native observer runs on Windows while the canonical controller can
    # validate from WSL. Normalise /mnt/<drive>/... to the Windows spelling
    # before hashing so both processes attest the same physical location.
    match = re.fullmatch(r"/mnt/([A-Za-z])/(.*)", rendered)
    if match is not None:
        rendered = f"{match.group(1)}:/{match.group(2)}"
    if re.match(r"^[A-Za-z]:/", rendered):
        rendered = rendered.lower()
    return rendered


def path_identity_token(path: Path) -> str:
    return hashlib.sha256(
        canonical_identity_path(path).encode("utf-8")
    ).hexdigest()


def require_sha(value: object, label: str) -> str:
    require(isinstance(value, str) and SHA256.fullmatch(value) is not None,
            f"{label} is not a lowercase SHA-256")
    return value


def utc_timestamp(value: object, label: str) -> dt.datetime:
    require(isinstance(value, str) and UTC_TIMESTAMP.fullmatch(value) is not None,
            f"{label} is not a strict UTC timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as exc:
        fail(f"{label} is not a valid UTC timestamp: {exc}")
    return parsed


def require_session(value: object, label: str) -> str:
    require(isinstance(value, str) and SESSION.fullmatch(value) is not None,
            f"{label} is not a bounded native-session id")
    return value


def require_profile(value: object, allowed: tuple[str, ...], label: str) -> str:
    require(isinstance(value, str) and value in allowed,
            f"{label} is not one of the required native profiles")
    return value


def png_dimensions(path: Path, label: str) -> tuple[int, int, str]:
    data = path.read_bytes()
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), f"{label} is not a real PNG")
    offset = 8
    header: bytes | None = None
    compressed = bytearray()
    saw_end = False
    while offset + 12 <= len(data):
        length = int.from_bytes(data[offset:offset + 4], "big")
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        require(end <= len(data), f"{label} has a truncated PNG chunk")
        payload = data[offset + 8:offset + 8 + length]
        declared_crc = int.from_bytes(data[offset + 8 + length:end], "big")
        require((zlib.crc32(kind + payload) & 0xFFFFFFFF) == declared_crc,
                f"{label} has a corrupt PNG chunk")
        if kind == b"IHDR":
            require(header is None and length == 13,
                    f"{label} has a malformed PNG header")
            header = payload
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            require(length == 0, f"{label} has a malformed PNG end chunk")
            saw_end = True
            offset = end
            break
        offset = end
    require(header is not None and compressed and saw_end and offset == len(data),
            f"{label} is not a complete canonical PNG")
    width = int.from_bytes(header[0:4], "big")
    height = int.from_bytes(header[4:8], "big")
    require(width >= 320 and height >= 180,
            f"{label} dimensions {width}x{height} are too small for review")
    bit_depth, colour_type, compression, filter_method, interlace = header[8:13]
    require(bit_depth == 8 and colour_type in {2, 6}
            and compression == 0 and filter_method == 0 and interlace == 0,
            f"{label} PNG encoding is unsupported for deterministic pixel proof")
    bytes_per_pixel = 3 if colour_type == 2 else 4
    stride = width * bytes_per_pixel
    try:
        filtered = zlib.decompress(bytes(compressed))
    except zlib.error as exc:
        fail(f"{label} PNG pixels cannot be decoded: {exc}")
    require(len(filtered) == height * (stride + 1),
            f"{label} PNG pixel payload has the wrong length")
    previous = bytearray(stride)
    pixels = bytearray()
    cursor = 0
    for _ in range(height):
        filter_type = filtered[cursor]
        cursor += 1
        current = bytearray(filtered[cursor:cursor + stride])
        cursor += stride
        require(filter_type <= 4, f"{label} uses an invalid PNG row filter")
        for index in range(stride):
            left = current[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
            above = previous[index]
            upper_left = (previous[index - bytes_per_pixel]
                          if index >= bytes_per_pixel else 0)
            if filter_type == 1:
                current[index] = (current[index] + left) & 0xFF
            elif filter_type == 2:
                current[index] = (current[index] + above) & 0xFF
            elif filter_type == 3:
                current[index] = (current[index] + ((left + above) // 2)) & 0xFF
            elif filter_type == 4:
                estimate = left + above - upper_left
                distances = (abs(estimate - left), abs(estimate - above),
                             abs(estimate - upper_left))
                predictor = (left if distances[0] <= distances[1]
                             and distances[0] <= distances[2]
                             else above if distances[1] <= distances[2]
                             else upper_left)
                current[index] = (current[index] + predictor) & 0xFF
        pixels.extend(current)
        previous = current
    fingerprint = hashlib.sha256(
        width.to_bytes(4, "big") + height.to_bytes(4, "big")
        + bytes((colour_type,)) + pixels
    ).hexdigest()
    return width, height, fingerprint


MEDIA_PROBE_BY_HASH: dict[str, dict[str, object]] = {}


def probe_media(path: Path, label: str) -> dict[str, object]:
    media_hash = digest(path)
    cached = MEDIA_PROBE_BY_HASH.get(media_hash)
    if cached is not None:
        return cached
    ffprobe = shutil.which("ffprobe")
    require(ffprobe is not None, "ffprobe is required to validate native media evidence")
    completed = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries",
         "format=duration:stream=index,codec_type,width,height,avg_frame_rate,sample_rate,channels",
         "-of", "json", str(path)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False,
    )
    require(completed.returncode == 0,
            f"{label} is not decodable media: {completed.stderr.strip()}")
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        fail(f"{label} ffprobe output is malformed: {exc}")
    require(isinstance(value, dict), f"{label} ffprobe output is not an object")
    MEDIA_PROBE_BY_HASH[media_hash] = value
    return value


def media_duration(probe: dict[str, object], label: str) -> float:
    raw_format = probe.get("format")
    require(isinstance(raw_format, dict), f"{label} has no media format record")
    try:
        duration = float(raw_format.get("duration", "nan"))
    except (TypeError, ValueError):
        duration = float("nan")
    require(duration >= 0.5 and duration <= 60.0,
            f"{label} is not a dedicated [0.5, 60] second row excerpt")
    return duration


def validate_audio(path: Path, label: str) -> float:
    probe = probe_media(path, label)
    duration = media_duration(probe, label)
    streams = probe.get("streams")
    require(isinstance(streams, list), f"{label} has no streams")
    audio = [stream for stream in streams
             if isinstance(stream, dict) and stream.get("codec_type") == "audio"]
    require(len(audio) == 1, f"{label} must contain exactly one audio stream")
    try:
        sample_rate = int(audio[0].get("sample_rate", 0))
        channels = int(audio[0].get("channels", 0))
    except (TypeError, ValueError):
        fail(f"{label} audio stream metadata is malformed")
    require(sample_rate >= 44100 and channels >= 2,
            f"{label} is not at least 44.1 kHz stereo native loopback audio")
    return duration


def validate_audio_segment(
    path: Path, start_seconds: float, end_seconds: float, label: str,
) -> str:
    try:
        with wave.open(str(path), "rb") as source:
            require(source.getsampwidth() == 2,
                    f"{label} is not 16-bit PCM loopback audio")
            rate = source.getframerate()
            start_frame = int(start_seconds * rate)
            frame_count = max(1, int((end_seconds - start_seconds) * rate))
            source.setpos(min(start_frame, source.getnframes()))
            raw = source.readframes(frame_count)
    except (OSError, wave.Error) as exc:
        fail(f"{label} WAV segment cannot be read: {exc}")
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder != "little":
        samples.byteswap()
    require(samples, f"{label} audio locator contains no PCM samples")
    peak = 0
    active = 0
    clipped = 0
    for sample in samples:
        value = abs(sample)
        peak = max(peak, value)
        active += value >= 32
        clipped += value >= 32760
    require(peak >= 128 and active / len(samples) >= 0.001,
            f"{label} located audio segment is silent/near-silent")
    require(clipped / len(samples) <= 0.005,
            f"{label} located audio segment is digitally clipped")
    return hashlib.sha256(raw).hexdigest()


def validate_audio_capture_report(
    report: object, path: Path, label: str, identity: dict[str, object],
) -> None:
    require(isinstance(report, dict), f"{label} captureReport is missing")
    required = {
        "status", "output", "device_index", "device_name", "channels",
        "sample_rate", "frames", "requested_seconds", "wall_seconds",
    }
    require(required <= report.keys(),
            f"{label} captureReport lacks {sorted(required - report.keys())}")
    require(report.get("status") == "PASS", f"{label} loopback capture did not PASS")
    try:
        reported_output = local_absolute_path(
            str(report.get("output")), f"{label} captureReport output"
        ).resolve(strict=False)
    except OSError as exc:
        fail(f"{label} captureReport output is malformed: {exc}")
    require(reported_output == path,
            f"{label} captureReport output does not name this WAV")
    require(report.get("device_name") == identity["audioDevice"],
            f"{label} loopback device differs from the manifest")
    require(type(report.get("device_index")) is int
            and int(report["device_index"]) >= 0,
            f"{label} captureReport device_index is malformed")
    try:
        with wave.open(str(path), "rb") as source:
            channels = source.getnchannels()
            sample_rate = source.getframerate()
            frames = source.getnframes()
            sample_width = source.getsampwidth()
    except (OSError, wave.Error) as exc:
        fail(f"{label} WAV header cannot be read: {exc}")
    require(sample_width == 2 and channels >= 2 and sample_rate >= 44100,
            f"{label} is not 16-bit, stereo, 44.1-kHz-or-better loopback PCM")
    require(report.get("channels") == channels
            and report.get("sample_rate") == sample_rate
            and report.get("frames") == frames,
            f"{label} captureReport disagrees with the WAV header")
    requested = finite_number(report.get("requested_seconds"),
                              f"{label} captureReport requested_seconds",
                              nonnegative=True)
    wall = finite_number(report.get("wall_seconds"),
                         f"{label} captureReport wall_seconds", nonnegative=True)
    decoded_duration = frames / sample_rate
    require(requested > 0.0 and abs(requested - decoded_duration) <= 0.1,
            f"{label} captureReport duration disagrees with the WAV")
    require(wall > 0.0 and 0.5 * requested <= wall <= 2.0 * requested + 5.0,
            f"{label} captureReport wall time is not credible")


def validate_video(path: Path, label: str) -> tuple[float, str]:
    probe = probe_media(path, label)
    duration = media_duration(probe, label)
    streams = probe.get("streams")
    require(isinstance(streams, list), f"{label} has no streams")
    video = [stream for stream in streams
             if isinstance(stream, dict) and stream.get("codec_type") == "video"]
    require(len(video) == 1, f"{label} must contain exactly one video stream")
    stream = video[0]
    try:
        width = int(stream.get("width", 0))
        height = int(stream.get("height", 0))
        numerator, denominator = str(stream.get("avg_frame_rate", "0/1")).split("/", 1)
        fps = float(numerator) / float(denominator)
    except (TypeError, ValueError, ZeroDivisionError):
        fail(f"{label} video stream metadata is malformed")
    require(width >= 640 and height >= 360, f"{label} video resolution is too small")
    require(fps >= 29.0, f"{label} video frame rate {fps:.3f} is below 29 fps")
    ffmpeg = shutil.which("ffmpeg")
    require(ffmpeg is not None, "ffmpeg is required to fingerprint decoded video evidence")
    # Container bytes and compressed packets change under a harmless remux or
    # codec re-encode. Fingerprint fixed-timestamp decoded RGB frames instead,
    # so a second encoding of the same visual take cannot satisfy another row.
    command = [
        ffmpeg, "-v", "error", "-i", str(path), "-map", "0:v:0", "-an", "-sn",
        "-vf", "fps=30:start_time=0:round=near,scale=160:90:flags=bilinear,format=rgb24",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1",
    ]
    try:
        process = subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    except OSError as exc:
        fail(f"{label} decoded-frame fingerprint could not start: {exc}")
    assert process.stdout is not None and process.stderr is not None
    fingerprint_value = hashlib.sha256(b"rgb24:160x90:30fps\n")
    decoded_bytes = 0
    while True:
        chunk = process.stdout.read(1024 * 1024)
        if not chunk:
            break
        decoded_bytes += len(chunk)
        fingerprint_value.update(chunk)
    stderr = process.stderr.read().decode("utf-8", errors="replace")
    return_code = process.wait()
    frame_bytes = 160 * 90 * 3
    require(return_code == 0,
            f"{label} decoded-frame fingerprint failed: {stderr.strip()}")
    require(decoded_bytes >= 15 * frame_bytes and decoded_bytes % frame_bytes == 0,
            f"{label} has no complete fixed-rate decoded-frame sequence")
    fingerprint_value.update((decoded_bytes // frame_bytes).to_bytes(8, "big"))
    fingerprint = fingerprint_value.hexdigest()
    return duration, fingerprint


def validate_log_file(path: Path, label: str) -> str:
    try:
        text = path.read_text(encoding="utf-8", errors="strict")
    except (OSError, UnicodeError) as exc:
        fail(f"{label} is not a UTF-8 log: {exc}")
    require("\x00" not in text and len(text.splitlines()) >= 1,
            f"{label} is not usable text-log evidence")
    require("hearthstead" in text.lower(), f"{label} contains no Hearthstead log source")
    return text


def validate_native_log_segments(
    entry: object, run_dir: Path, identity: dict[str, object],
) -> dict[str, dict[str, object]]:
    """Validate immutable integrated-client latest.log segments across restarts."""
    require(isinstance(entry, dict), "manifest nativeLogSegments is missing")
    require(entry.get("path") == NATIVE_LOG_SEGMENT_INDEX,
            "manifest nativeLogSegments path is not the canonical segment index")
    declared_index_hash = require_sha(
        entry.get("sha256"), "manifest nativeLogSegments sha256"
    )
    index_path = resolve_evidence(
        run_dir, entry.get("path"), "manifest native log segment index"
    )
    require(digest(index_path) == declared_index_hash,
            "native log segment index hash differs")
    index = load_object(index_path, "native log segment index")
    require(index.get("schemaVersion") == 1,
            "native log segment index schemaVersion is not 1")
    require(index.get("nativeSessionId") == identity["nativeSessionId"],
            "native log segment index belongs to another session")
    require(index.get("gameDirectoryToken") == identity["gameDirectoryToken"],
            "native log segment index belongs to another game directory")
    raw_segments = index.get("segments")
    require(isinstance(raw_segments, list) and len(raw_segments) >= 2,
            "native log segment index does not prove an actual relaunch")

    started_millis = int(identity["startedAt"].timestamp() * 1000)
    finished_millis = int(identity["finishedAt"].timestamp() * 1000)
    expected_profiles = set(identity["profiles"])
    segment_by_path: dict[str, dict[str, object]] = {}
    seen_hashes: set[str] = set()
    seen_profiles: list[str] = []
    previous_sealed = -1
    observed_sources: set[str] = set()
    for position, raw in enumerate(raw_segments, 1):
        label = f"native log segment {position}"
        require(isinstance(raw, dict), f"{label} is not an object")
        require(raw.get("index") == position,
                f"{label} index is not contiguous")
        profile = require_profile(raw.get("displayProfile"), identity["profiles"],
                                  f"{label} displayProfile")
        expected_name = (
            f"native-{identity['nativeSessionId']}-segment-{position:04d}-"
            f"{profile.replace('-', '_')}.log"
        )
        require(raw.get("path") == expected_name,
                f"{label} path is not the helper-owned canonical name")
        relative_path = f"logs/{expected_name}"
        path = resolve_evidence(run_dir, relative_path, label)
        declared_hash = require_sha(raw.get("sha256"), f"{label} sha256")
        require(declared_hash not in seen_hashes,
                f"{label} duplicates a previously sealed log")
        seen_hashes.add(declared_hash)
        require(digest(path) == declared_hash, f"{label} hash differs")
        require(type(raw.get("sizeBytes")) is int
                and raw["sizeBytes"] == path.stat().st_size,
                f"{label} sizeBytes differs from the sealed file")
        require(raw.get("sourceRelativePath") == "logs/latest.log",
                f"{label} source is not the native profile latest.log")
        for field in (
            "sourceModifiedEpochMillis", "sealedEpochMillis",
            "firstObservedEpochMillis", "lastObservedEpochMillis",
        ):
            require(type(raw.get(field)) is int and not isinstance(raw.get(field), bool),
                    f"{label} {field} is not an integer")
        source_modified = int(raw["sourceModifiedEpochMillis"])
        sealed = int(raw["sealedEpochMillis"])
        first_observed = int(raw["firstObservedEpochMillis"])
        last_observed = int(raw["lastObservedEpochMillis"])
        require(started_millis <= source_modified <= sealed <= finished_millis,
                f"{label} source/seal timestamps lie outside the native run")
        sealed_file_mtime = int(path.stat().st_mtime * 1000)
        require(abs(sealed_file_mtime - sealed) <= 2_000,
                f"{label} filesystem mtime disagrees with its immutable seal")
        require(started_millis <= first_observed <= last_observed <= sealed,
                f"{label} ACK timestamps lie outside the sealed run interval")
        require(sealed > previous_sealed,
                f"{label} is not strictly ordered after the prior segment")
        previous_sealed = sealed
        require(raw.get("runtimeJarSha256") == identity["jarHash"],
                f"{label} runtime JAR hash differs")
        require(raw.get("gameDirectoryToken") == identity["gameDirectoryToken"],
                f"{label} game directory token differs")
        require(raw.get("runtimeJarPathToken") == identity["runtimeJarPathToken"],
                f"{label} runtime JAR path token differs")
        require(raw.get("worldPathToken") == identity["worldPathToken"],
                f"{label} save-directory token differs")
        source = raw.get("observerActivationSource")
        require(source in identity["observerSources"],
                f"{label} observer activation source differs")
        observed_sources.add(str(source))
        marker_consumed = raw.get("markerConsumed")
        require(type(marker_consumed) is bool,
                f"{label} markerConsumed is not Boolean")
        marker_created = raw.get("markerCreatedEpochSeconds")
        marker_expires = raw.get("markerExpiresEpochSeconds")
        if source == "one_shot_marker":
            require(marker_consumed is True
                    and type(marker_created) is int
                    and type(marker_expires) is int,
                    f"{label} does not bind the consumed one-shot marker")
            require(started_millis <= int(marker_created) * 1000 <= first_observed,
                    f"{label} marker creation lies outside the native run")
            require(first_observed // 1000 - int(marker_created) <= 300,
                    f"{label} consumed marker was stale")
            require(int(marker_expires) * 1000 > first_observed
                    and int(marker_expires) - int(marker_created) <= 3600,
                    f"{label} consumed marker expiry is invalid")
        else:
            require(marker_consumed is False and marker_created is None
                    and marker_expires is None,
                    f"{label} environment activation reports marker state")

        text = validate_log_file(path, label)
        enabled_records: list[dict[str, str]] = []
        for line_number, line in enumerate(text.splitlines(), 1):
            try:
                activation = parse_fixed_log_record(
                    line, ACTIVATION_MARKER, ACTIVATION_FIELDS
                )
            except EvidenceContractError as exc:
                fail(f"{label} line {line_number}: {exc}")
            if activation is not None:
                enabled_records.append(activation)
        require(len(enabled_records) == 1,
                f"{label} lacks one exact observer activation record")
        enabled = enabled_records[0]
        require(enabled.get("source") == source
                and enabled.get("qaSession") == identity["nativeSessionId"]
                and enabled.get("markerConsumed") == str(marker_consumed).lower(),
                f"{label} observer activation record differs from its seal")
        if source == "one_shot_marker":
            require(enabled.get("markerCreatedEpochSeconds") == str(marker_created)
                    and enabled.get("markerExpiresEpochSeconds") == str(marker_expires),
                    f"{label} marker timestamps differ from its activation record")
        else:
            require(enabled.get("markerCreatedEpochSeconds") == "none"
                    and enabled.get("markerExpiresEpochSeconds") == "none",
                    f"{label} environment activation has marker timestamps")

        parsed_profiles, records = parse_native_acks(text, label, identity)
        require(parsed_profiles == {profile},
                f"{label} mixes or relabels display profiles")
        require(type(raw.get("ackCount")) is int
                and raw["ackCount"] == len(records),
                f"{label} ackCount differs from the sealed log")
        observed_values = [int(record["observedEpochMillis"]) for record in records]
        require(min(observed_values) == first_observed
                and max(observed_values) == last_observed,
                f"{label} ACK time bounds differ from the sealed log")
        require(raw.get("observedDisplayProfiles") == [profile],
                f"{label} observed display-profile roster differs")
        server_lines = sum(
            1 for line in text.splitlines()
            if SERVER_AUTHORITY_LINE.search(line) is not None
        )
        require(type(raw.get("serverAuthorityLineCount")) is int
                and raw["serverAuthorityLineCount"] == server_lines,
                f"{label} server-authority line count differs from the sealed log")
        record = dict(raw)
        record["relativePath"] = relative_path
        segment_by_path[relative_path] = record
        seen_profiles.append(profile)

    require(set(seen_profiles) == expected_profiles,
            "sealed native logs do not cover the exact display-profile roster")
    require(len(seen_profiles) > len(set(seen_profiles)),
            "sealed native logs do not contain a repeated profile across a relaunch")
    require(observed_sources == set(identity["observerSources"]),
            "manifest observerActivationSources differ from the sealed logs")
    return segment_by_path


def validate_native_launch_registry(
    entry: object, run_dir: Path, identity: dict[str, object],
    log_segments: dict[str, dict[str, object]],
) -> dict[str, object]:
    """Bind every PID to one actual creation time and one sealed launch log."""
    require(isinstance(entry, dict), "manifest nativeLaunchRegistry is missing")
    require(set(entry) == {
        "path", "sha256", "recordCount", "tailSha256",
    }, "manifest nativeLaunchRegistry has an unexpected field set")
    require(entry.get("path") == NATIVE_LAUNCH_REGISTRY,
            "manifest nativeLaunchRegistry path is not canonical")
    declared_hash = require_sha(entry.get("sha256"),
                                "manifest nativeLaunchRegistry sha256")
    declared_tail = require_sha(entry.get("tailSha256"),
                                "manifest nativeLaunchRegistry tailSha256")
    declared_count = entry.get("recordCount")
    require(type(declared_count) is int and 2 <= int(declared_count) <= 256,
            "manifest nativeLaunchRegistry recordCount is not 2..256")
    path = resolve_evidence(run_dir, entry.get("path"),
                            "manifest native launch registry")
    require(digest(path) == declared_hash,
            "native launch registry hash differs")
    try:
        launches, tail = parse_launch_registry(
            path, str(identity["nativeSessionId"])
        )
    except EvidenceContractError as exc:
        fail(str(exc))
    require(digest(path) == declared_hash,
            "native launch registry changed while it was being validated")
    require(len(launches) == declared_count,
            "native launch registry count differs from its manifest")
    require(tail == declared_tail,
            "native launch registry tail differs from its manifest")
    require(len(launches) == len(log_segments),
            "native launch registry does not map one-to-one to sealed launches")

    started_millis = int(identity["startedAt"].timestamp() * 1000)
    finished_millis = int(identity["finishedAt"].timestamp() * 1000)
    previous_segment: dict[str, object] | None = None
    by_nonce: dict[str, dict[str, object]] = {}
    for position, launch in enumerate(launches, 1):
        label = f"native launch registry record {position}"
        require(launch_identity_sha256(launch)
                == launch.get("launchIdentitySha256"),
                f"{label} identity hash differs")
        game = require_plain_directory(local_absolute_path(
                                           str(launch["gameDirectory"]),
                                           f"{label} gameDirectory"),
                                       f"{label} gameDirectory")
        runtime = require_plain_file(local_absolute_path(
                                         str(launch["runtimeJarPath"]),
                                         f"{label} runtimeJarPath"),
                                     f"{label} runtimeJarPath")
        world = require_plain_directory(local_absolute_path(
                                            str(launch["worldDirectory"]),
                                            f"{label} worldDirectory"),
                                        f"{label} worldDirectory")
        require(os.path.samefile(game, Path(str(identity["gameDirectory"]))),
                f"{label} game directory differs")
        require(os.path.samefile(runtime, Path(str(identity["runtimeJarPath"]))),
                f"{label} runtime JAR path differs")
        require(os.path.samefile(world, Path(str(identity["worldDirectory"]))),
                f"{label} world directory differs")
        require(launch.get("runtimeJarSha256") == identity["jarHash"]
                and digest(runtime) == identity["jarHash"],
                f"{label} runtime JAR hash differs")
        process_path = str(launch.get("processPath", ""))
        local_process_path = local_absolute_path(process_path,
                                                 f"{label} processPath")
        require(local_process_path.name.lower() in {"java.exe", "javaw.exe"},
                f"{label} process path is not one full Java executable path")
        creation = int(launch["processCreationEpochMillis"])
        registered = int(launch["registeredEpochMillis"])
        require(started_millis <= creation <= registered <= finished_millis,
                f"{label} process/registration timestamps leave the native run")
        relative_segment = str(launch["observerLogSegment"])
        segment = log_segments.get(relative_segment)
        require(segment is not None,
                f"{label} does not name one exact sealed observer log segment")
        assert segment is not None
        require(segment.get("index") == position
                and segment.get("displayProfile") == launch["displayProfile"],
                f"{label} observer segment identity differs")
        require(creation <= int(segment["firstObservedEpochMillis"])
                <= int(segment["lastObservedEpochMillis"])
                <= int(segment["sealedEpochMillis"]),
                f"{label} observer log predates its process launch")
        require(creation <= registered <= int(segment["sealedEpochMillis"]),
                f"{label} registration falls outside its sealed process launch")
        if previous_segment is not None:
            require(int(previous_segment["sealedEpochMillis"]) < creation,
                    f"{label} does not prove seal-before-relaunch ordering")
        previous_segment = segment
        by_nonce[str(launch["launchNonce"])] = launch
    return {
        "path": NATIVE_LAUNCH_REGISTRY,
        "sha256": declared_hash,
        "recordCount": declared_count,
        "tailSha256": declared_tail,
        "launches": tuple(launches),
        "byNonce": by_nonce,
    }


def native_input_record_hash(previous: str, record: dict[str, object]) -> str:
    return chained_record_sha256(previous, record, "chainSha256")


def validate_native_input_transcript(
    entry: object, run_dir: Path, identity: dict[str, object],
) -> dict[str, object]:
    """Validate the player-equivalent, hash-chained Windows SendInput trace."""
    require(isinstance(entry, dict), "manifest nativeInputTranscript is missing")
    require(set(entry) == {
        "path", "sha256", "driver", "recordCount", "tailSha256",
    }, "manifest nativeInputTranscript has an unexpected field set")
    require(entry.get("path") == NATIVE_INPUT_TRANSCRIPT,
            "manifest nativeInputTranscript path is not canonical")
    require(entry.get("driver") == INPUT_DRIVER,
            "manifest nativeInputTranscript driver is not the locked Windows driver")
    declared_hash = require_sha(
        entry.get("sha256"), "manifest nativeInputTranscript sha256"
    )
    declared_tail = require_sha(
        entry.get("tailSha256"), "manifest nativeInputTranscript tailSha256"
    )
    declared_count = entry.get("recordCount")
    require(type(declared_count) is int
            and 200 <= int(declared_count) <= 200_000
            and int(declared_count) % 2 == 0,
            "manifest nativeInputTranscript recordCount is not an even 200..200000")
    path = resolve_evidence(
        run_dir, entry.get("path"), "manifest native input transcript"
    )
    require(path.stat().st_size <= 32 * 1024 * 1024,
            "native input transcript exceeds the 32 MiB ceiling")
    require(digest(path) == declared_hash,
            "native input transcript hash differs")
    try:
        lines = path.read_text(encoding="utf-8", errors="strict").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"native input transcript is not strict UTF-8: {exc}")
    require(digest(path) == declared_hash,
            "native input transcript changed while it was being validated")
    require(len(lines) == declared_count,
            "native input transcript count differs from its manifest")

    expected_record_keys = {
        "schemaVersion", "sequence", "session", "observedEpochMillis",
        "phase", "action", "detail", "window", "launchNonce",
        "launchIdentitySha256", "postcondition", "previousChainSha256",
        "chainSha256",
    }
    expected_window_keys = {
        "hwnd", "pid", "title", "class_name", "process_path",
        "client_left", "client_top", "client_width", "client_height",
    }
    started_millis = int(identity["startedAt"].timestamp() * 1000)
    finished_millis = int(identity["finishedAt"].timestamp() * 1000)
    previous_hash = "0" * 64
    previous_observed = -1
    actions: list[str] = []
    pids: set[int] = set()
    buttons: set[str] = set()
    movement_keys: set[str] = set()
    saw_shift_right_click = False
    pending_intent: dict[str, object] | None = None
    seen_launch_nonces: set[str] = set()
    launch_registry = identity.get("launchRegistry")
    require(isinstance(launch_registry, dict)
            and isinstance(launch_registry.get("byNonce"), dict),
            "native launch registry was not validated before input")
    launches_by_nonce = launch_registry["byNonce"]
    assert isinstance(launches_by_nonce, dict)

    for index, line in enumerate(lines, 1):
        label = f"native input record {index}"
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            fail(f"{label} is malformed JSON: {exc}")
        require(isinstance(record, dict), f"{label} is not an object")
        require(set(record) == expected_record_keys,
                f"{label} has an unexpected field set")
        require(record.get("schemaVersion") == INPUT_RECORD_SCHEMA,
                f"{label} schemaVersion is not {INPUT_RECORD_SCHEMA}")
        require(record.get("sequence") == index,
                f"{label} sequence is not contiguous")
        require(record.get("session") == identity["nativeSessionId"],
                f"{label} belongs to another native session")
        observed = record.get("observedEpochMillis")
        require(type(observed) is int
                and started_millis <= int(observed) <= finished_millis,
                f"{label} timestamp lies outside the native run")
        require(int(observed) >= previous_observed,
                f"{label} timestamp regresses")
        previous_observed = int(observed)
        require(record.get("previousChainSha256") == previous_hash,
                f"{label} breaks the previous-hash link")
        declared_record_hash = require_sha(
            record.get("chainSha256"), f"{label} chainSha256"
        )
        require(native_input_record_hash(previous_hash, record)
                == declared_record_hash,
                f"{label} hash chain proves the record was changed")
        previous_hash = declared_record_hash

        expected_phase = "INTENT" if index % 2 == 1 else "COMPLETED"
        require(record.get("phase") == expected_phase,
                f"{label} breaks the INTENT/COMPLETED pairing")
        if expected_phase == "INTENT":
            require(record.get("postcondition") is None,
                    f"{label} intent contains a forged completion postcondition")
            pending_intent = record
        else:
            require(pending_intent is not None,
                    f"{label} has no matching input intent")
            for field in (
                "session", "action", "detail", "window", "launchNonce",
                "launchIdentitySha256",
            ):
                require(record.get(field) == pending_intent.get(field),
                        f"{label} differs from its intent {field}")
            postcondition = record.get("postcondition")
            require(isinstance(postcondition, dict) and set(postcondition) == {
                "foregroundHwnd", "boundPid", "launchNonce",
                "processCreationEpochMillis", "allEventsDelivered",
                "cleanupRequired",
            }, f"{label} completion postcondition is malformed")
            pending_intent = None

        action = record.get("action")
        require(isinstance(action, str) and action in NATIVE_INPUT_ACTIONS,
                f"{label} action is outside the locked input vocabulary")
        if expected_phase == "COMPLETED":
            actions.append(action)
        detail = record.get("detail")
        require(isinstance(detail, dict), f"{label} detail is not an object")
        window = record.get("window")
        require(isinstance(window, dict) and set(window) == expected_window_keys,
                f"{label} window identity is malformed")
        for field in ("hwnd", "pid", "client_width", "client_height"):
            require(type(window.get(field)) is int and int(window[field]) > 0,
                    f"{label} window {field} is not a positive integer")
        for field in ("client_left", "client_top"):
            require(type(window.get(field)) is int,
                    f"{label} window {field} is not an integer")
        require(320 <= int(window["client_width"]) <= 16_384
                and 180 <= int(window["client_height"]) <= 16_384,
                f"{label} window client bounds are implausible")
        title = window.get("title")
        class_name = window.get("class_name")
        process_path = window.get("process_path")
        require(isinstance(title, str)
                and re.search(r"(?:^|\s)Minecraft(?:\s|$)", title,
                              re.IGNORECASE) is not None,
                f"{label} does not identify a Minecraft title")
        require(class_name in {"GLFW30", "LWJGL"},
                f"{label} does not identify a GLFW/LWJGL window")
        launch_nonce = record.get("launchNonce")
        require(isinstance(launch_nonce, str)
                and launch_nonce in launches_by_nonce,
                f"{label} does not bind one sealed launch nonce")
        launch = launches_by_nonce[str(launch_nonce)]
        require(isinstance(launch, dict), f"{label} launch identity is malformed")
        require(record.get("launchIdentitySha256")
                == launch.get("launchIdentitySha256"),
                f"{label} launch identity hash differs")
        require(int(window["pid"]) == int(launch["pid"]),
                f"{label} PID differs from its sealed launch")
        require(isinstance(process_path, str)
                and os.path.normcase(os.path.normpath(process_path))
                == os.path.normcase(os.path.normpath(str(launch["processPath"]))),
                f"{label} executable path differs from its sealed launch")
        creation = int(launch["processCreationEpochMillis"])
        registered = int(launch["registeredEpochMillis"])
        segment = identity["logSegments"][launch["observerLogSegment"]]
        require(creation <= registered <= int(observed)
                <= int(segment["sealedEpochMillis"]),
                f"{label} timestamp lies outside its exact process launch")
        seen_launch_nonces.add(str(launch_nonce))
        pids.add(int(window["pid"]))

        if expected_phase == "COMPLETED":
            postcondition = record["postcondition"]
            assert isinstance(postcondition, dict)
            require(postcondition == {
                "foregroundHwnd": int(window["hwnd"]),
                "boundPid": int(window["pid"]),
                "launchNonce": str(launch_nonce),
                "processCreationEpochMillis": creation,
                "allEventsDelivered": True,
                "cleanupRequired": False,
            }, f"{label} does not prove the exact foreground completion postcondition")

        coordinate_fields = {"clientX", "clientY", "screenX", "screenY"}
        if action == "focus":
            require(detail == {}, f"{label} focus detail is not empty")
        elif action == "move":
            require(set(detail) == coordinate_fields,
                    f"{label} move detail is malformed")
        elif action in {"click", "modified-click"}:
            expected = coordinate_fields | {"button", "count"}
            if action == "modified-click":
                expected.add("modifiers")
            require(set(detail) == expected,
                    f"{label} click detail is malformed")
            button = detail.get("button")
            count = detail.get("count")
            require(button in {"left", "right", "middle"}
                    and type(count) is int and 1 <= int(count) <= 3,
                    f"{label} click button/count is invalid")
            buttons.add(str(button))
            if action == "modified-click":
                modifiers = detail.get("modifiers")
                require(isinstance(modifiers, list) and modifiers
                        and modifiers == ["shift"],
                        f"{label} modified-click modifiers are invalid")
                saw_shift_right_click |= button == "right" and "shift" in modifiers
        elif action == "scroll":
            require(set(detail) == coordinate_fields | {"clicks"}
                    and type(detail.get("clicks")) is int
                    and 0 < abs(int(detail["clicks"])) <= 12,
                    f"{label} scroll detail is malformed")
        elif action == "key":
            require(set(detail) == {"key", "repeat"}
                    and detail.get("key") in NATIVE_INPUT_KEYS
                    and type(detail.get("repeat")) is int
                    and 1 <= int(detail["repeat"]) <= 20,
                    f"{label} key detail is malformed")
        elif action == "hold":
            require(set(detail) == {"key", "milliseconds"}
                    and detail.get("key") in NATIVE_INPUT_KEYS
                    and type(detail.get("milliseconds")) is int
                    and 25 <= int(detail["milliseconds"]) <= 5000,
                    f"{label} hold detail is malformed")
            if detail["key"] in {"w", "a", "s", "d"}:
                movement_keys.add(str(detail["key"]))
        elif action == "look":
            require(set(detail) == {"dx", "dy"}
                    and type(detail.get("dx")) is int
                    and type(detail.get("dy")) is int
                    and not (detail["dx"] == 0 and detail["dy"] == 0)
                    and abs(int(detail["dx"])) <= 2000
                    and abs(int(detail["dy"])) <= 2000,
                    f"{label} look detail is malformed")
        elif action == "text":
            text_value = detail.get("text")
            require(set(detail) == {"purpose", "text", "asciiLength"}
                    and detail.get("purpose") == "settlement_name"
                    and isinstance(text_value, str)
                    and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 _'-]{0,31}",
                                     text_value) is not None
                    and text_value == text_value.strip()
                    and "  " not in text_value
                    and not re.match(r"(?i)(?:command|cmd|execute|run)\b",
                                     text_value)
                    and type(detail.get("asciiLength")) is int
                    and int(detail["asciiLength"])
                    == len(text_value.encode("ascii")),
                    f"{label} text detail is malformed")

        if coordinate_fields <= set(detail):
            require(all(type(detail.get(field)) is int for field in coordinate_fields),
                    f"{label} client/screen coordinates are not integers")
            require(0 <= int(detail["clientX"]) < int(window["client_width"])
                    and 0 <= int(detail["clientY"]) < int(window["client_height"]),
                    f"{label} client coordinate leaves the exact Minecraft window")
            require(int(detail["screenX"])
                    == int(window["client_left"]) + int(detail["clientX"])
                    and int(detail["screenY"])
                    == int(window["client_top"]) + int(detail["clientY"]),
                    f"{label} screen coordinate contradicts the client bounds")

    require(previous_hash == declared_tail,
            "native input transcript tail differs from its manifest")
    require(pending_intent is None,
            "native input transcript ends with an incomplete intent")
    required_actions = {
        "move", "click", "modified-click", "scroll", "key", "hold",
        "look", "text",
    }
    require(required_actions <= set(actions),
            "native input transcript lacks required player-equivalent action classes")
    require({"left", "right"} <= buttons,
            "native input transcript lacks both left- and right-click interaction")
    require(saw_shift_right_click,
            "native input transcript lacks the required Shift-right-click interaction")
    require(bool(movement_keys),
            "native input transcript lacks held in-world movement")
    require(seen_launch_nonces == set(launches_by_nonce),
            "native input transcript does not cover every sealed process launch")
    require(pids == {int(value["pid"]) for value in launches_by_nonce.values()},
            "native input transcript PID roster differs from the launch registry")
    return {
        "path": NATIVE_INPUT_TRANSCRIPT,
        "sha256": declared_hash,
        "recordCount": declared_count,
        "tailSha256": declared_tail,
        "pids": tuple(sorted(pids)),
    }


def select_operator_input_seal(
    seal_path: Path, operator_key_path: Path, run_dir: Path, session: str,
    expected_operator_key_sha256: str,
) -> dict[str, object]:
    seal = require_plain_file(seal_path, "operator input-seal ledger")
    key_path = require_plain_file(operator_key_path, "operator seal key")
    for candidate, label in (
        (seal, "operator input-seal ledger"),
        (key_path, "operator seal key"),
    ):
        require(not path_is_within(candidate, run_dir),
                f"{label} must live outside the mutable run artifact")
    key = key_path.read_bytes()
    require(32 <= len(key) <= 4096,
            "operator seal key must contain 32..4096 bytes")
    require(hashlib.sha256(key).hexdigest() == expected_operator_key_sha256,
            "operator seal key hash differs from the external PRECOMMIT")
    try:
        records = parse_operator_seal_ledger(seal, key)
    except EvidenceContractError as exc:
        fail(str(exc))
    matches = [record for record in records
               if record.get("nativeSessionId") == session]
    require(len(matches) == 1,
            "operator input-seal ledger does not contain exactly one matching session")
    return matches[0]


def validate_operator_input_seal_binding(
    seal: dict[str, object], manifest: dict[str, object], run_dir: Path,
    identity: dict[str, object], expected_session_nonce: str,
    expected_run_directory_token: str, expected_jar_sha256: str,
    expected_world_id: str,
) -> None:
    require(seal.get("nativeSessionId") == identity["nativeSessionId"],
            "operator input seal belongs to another session")
    require(seal.get("runDirectoryToken") == contract_path_token(run_dir),
            "operator input seal belongs to another run directory")
    require(seal.get("operatorNonce") == expected_session_nonce,
            "operator input seal nonce differs from the external PRECOMMIT")
    require(contract_path_token(run_dir) == expected_run_directory_token,
            "run directory differs from the external PRECOMMIT")
    require(identity["jarHash"] == expected_jar_sha256,
            "installed runtime JAR hash differs from the external PRECOMMIT")
    require(identity["worldId"] == expected_world_id,
            "native world id differs from the external PRECOMMIT")
    captured = seal.get("capturedEpochMillis")
    require(type(captured) is int,
            "operator input seal capturedEpochMillis is not an integer")
    finished_millis = int(identity["finishedAt"].timestamp() * 1000)
    now_millis = int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000)
    require(finished_millis <= int(captured) <= now_millis + int(CLOCK_SKEW.total_seconds() * 1000),
            "operator input seal was captured before completion or implausibly in future")

    input_summary = identity["inputTranscript"]
    launch_summary = identity["launchRegistry"]
    assert isinstance(input_summary, dict) and isinstance(launch_summary, dict)
    expected_input = {
        key: input_summary[key]
        for key in ("path", "sha256", "recordCount", "tailSha256")
    }
    expected_launch = {
        key: launch_summary[key]
        for key in ("path", "sha256", "recordCount", "tailSha256")
    }
    require(seal.get("inputTranscript") == expected_input,
            "operator input seal does not bind the exact transcript")
    require(seal.get("launchRegistry") == expected_launch,
            "operator input seal does not bind the exact launch registry")
    segment_manifest = manifest.get("nativeLogSegments")
    require(isinstance(segment_manifest, dict),
            "manifest nativeLogSegments is missing")
    expected_segments = {
        "path": NATIVE_LOG_SEGMENT_INDEX,
        "sha256": segment_manifest.get("sha256"),
    }
    require(seal.get("nativeLogSegments") == expected_segments,
            "operator input seal does not bind the exact native log segment index")


def resolve_evidence(run_dir: Path, relative: object, label: str) -> Path:
    require(isinstance(relative, str) and relative != "", f"{label} path is missing")
    path = Path(relative)
    require(not path.is_absolute(), f"{label} path must be run-relative")
    require(path.as_posix() == relative, f"{label} path is not canonical POSIX-relative")
    require(".." not in path.parts and "." not in path.parts,
            f"{label} path contains traversal components")
    resolved = require_plain_file(run_dir / path, label)
    require(path_is_within(resolved, run_dir),
            f"{label} escapes the evidence directory")
    return resolved


def validate_jar(entry: object, label: str) -> tuple[Path, str]:
    require(isinstance(entry, dict), f"manifest {label} is not an object")
    raw_path = entry.get("path")
    require(isinstance(raw_path, str) and raw_path != "", f"manifest {label}.path is missing")
    path = require_plain_file(
        local_absolute_path(raw_path, f"manifest {label}.path"),
        f"manifest {label}.path",
    )
    require(path.suffix.lower() == ".jar", f"manifest {label}.path is not a JAR")
    require(zipfile.is_zipfile(path), f"manifest {label}.path is not a readable ZIP/JAR")
    try:
        with zipfile.ZipFile(path) as archive:
            entries = set(archive.namelist())
            bad_member = archive.testzip()
    except (OSError, zipfile.BadZipFile) as exc:
        fail(f"manifest {label}.path is not a valid JAR: {exc}")
    require(bad_member is None, f"manifest {label}.path has corrupt member {bad_member}")
    required_entries = {
        "META-INF/MANIFEST.MF",
        "META-INF/neoforge.mods.toml",
        "com/hearthstead/client/QaClientObserver.class",
        "assets/hearthstead/lang/en_us.json",
        "assets/hearthstead/lang/nb_no.json",
    }
    require(required_entries <= entries,
            f"manifest {label}.path lacks Hearthstead runtime entries "
            f"{sorted(required_entries - entries)}")
    declared = require_sha(entry.get("sha256"), f"manifest {label}.sha256")
    actual = digest(path)
    require(actual == declared, f"manifest {label} hash differs from the physical JAR")
    return path, actual


def matrix_contract(
    matrix: dict[str, object],
) -> tuple[dict[str, dict[str, object]], tuple[str, ...]]:
    require(matrix.get("schemaVersion") == 2, "matrix schemaVersion is not 2")
    raw_profiles = matrix.get("requiredNativeProfiles")
    require(isinstance(raw_profiles, list)
            and tuple(raw_profiles) == REQUIRED_DISPLAY_PROFILES,
            "matrix native-profile roster is not the exact release roster")
    profiles = tuple(str(profile) for profile in raw_profiles)
    raw_rows = matrix.get("rows")
    require(isinstance(raw_rows, list) and raw_rows, "matrix rows are missing")
    require(len(raw_rows) == len(REQUIRED_MATRIX_ROW_IDS),
            f"matrix row count is not the exact {len(REQUIRED_MATRIX_ROW_IDS)}")
    rows: dict[str, dict[str, object]] = {}
    for index, raw in enumerate(raw_rows):
        require(isinstance(raw, dict), f"matrix row {index} is not an object")
        row_id = raw.get("id")
        require(isinstance(row_id, str) and row_id != "", f"matrix row {index} has no id")
        require(row_id not in rows, f"matrix row id is duplicated: {row_id}")
        trigger = raw.get("triggerMode")
        require(trigger in {"physical_player", "real_ai", "natural_runtime", "restart"},
                f"matrix row {row_id} has invalid triggerMode")
        kinds = raw.get("requiredEvidenceKinds")
        require(isinstance(kinds, list) and kinds, f"matrix row {row_id} has no evidence kinds")
        require(len(kinds) == len(set(kinds)), f"matrix row {row_id} repeats an evidence kind")
        require(all(kind in ALLOWED_SUFFIXES for kind in kinds),
                f"matrix row {row_id} names an unknown evidence kind")
        required_profiles = raw.get("requiredNativeProfiles", [])
        require(isinstance(required_profiles, list)
                and len(required_profiles) == len(set(required_profiles))
                and all(profile in profiles for profile in required_profiles),
                f"matrix row {row_id} has invalid requiredNativeProfiles")
        transitions = raw.get("requiredFrameTransitions", [])
        require(isinstance(transitions, list) and len(transitions) == len(set(transitions))
                and all(isinstance(value, str) and value for value in transitions),
                f"matrix row {row_id} has invalid requiredFrameTransitions")
        require(not transitions or "frame_report" in kinds,
                f"matrix row {row_id} requires transitions without frame_report evidence")
        require(not required_profiles
                or {"screenshot", "frame_report"} <= set(kinds)
                or row_id == "stability.no_ui_fps_regression_or_log_errors",
                f"matrix row {row_id} requires profiles without visual/frame evidence")
        interaction = raw.get("requiredClientInteraction")
        if row_id == "identity.keyboard_mouse_look_round_trip":
            require(interaction == {
                "minAcks": 2,
                "requireGrabbed": True,
                "minLookDeltaDegrees": 5.0,
                "minMovementDeltaBlocks": 0.5,
            }, "physical-input matrix row has a weakened interaction contract")
            require("client_log" in kinds and "video" in kinds,
                    "physical-input matrix row lacks native log/video evidence")
        else:
            require(interaction is None,
                    f"matrix row {row_id} has an unexpected interaction contract")
        assertions = raw.get("acceptanceAssertions", [])
        require(isinstance(assertions, list)
                and len(assertions) == len(set(assertions))
                and all(isinstance(value, str) and len(value.strip()) >= 24
                        for value in assertions),
                f"matrix row {row_id} has invalid acceptanceAssertions")
        mandatory = MANDATORY_ASSERTIONS.get(row_id)
        if mandatory is not None:
            require(assertions == list(mandatory),
                    f"matrix row {row_id} weakens a mandatory acceptance assertion")

        raw_authority = raw.get("requiredAuthorityTransactions", [])
        require(isinstance(raw_authority, list),
                f"matrix row {row_id} has invalid requiredAuthorityTransactions")
        mandatory_authority = MANDATORY_AUTHORITY_TRANSACTIONS.get(row_id)
        if mandatory_authority is not None:
            require(raw_authority == list(mandatory_authority),
                    f"matrix row {row_id} weakens a mandatory raid authority "
                    "transaction contract")
        require(("server_log" in kinds) == bool(raw_authority),
                f"matrix row {row_id} must bind every server-log verdict to "
                "one or more exact authority transactions")
        authority_ids: set[str] = set()
        for requirement_index, requirement in enumerate(raw_authority):
            authority_label = (
                f"matrix row {row_id} authority requirement {requirement_index}"
            )
            require(isinstance(requirement, dict),
                    f"{authority_label} is not an object")
            require(set(requirement) <= {
                "id", "event", "result", "targetPrefix", "reason",
                "reasonPrefix", "minOccurrences",
            }, f"{authority_label} contains unknown fields")
            requirement_id = requirement.get("id")
            require(isinstance(requirement_id, str)
                    and AUTHORITY_TOKEN.fullmatch(requirement_id) is not None,
                    f"{authority_label} has no stable id")
            require(requirement_id not in authority_ids,
                    f"matrix row {row_id} repeats authority id {requirement_id}")
            authority_ids.add(requirement_id)
            event = requirement.get("event")
            result = requirement.get("result")
            require(event in AUTHORITY_EVENTS,
                    f"{authority_label} event is outside the V1 vocabulary")
            require(result in AUTHORITY_RESULTS,
                    f"{authority_label} result is outside the V1 vocabulary")
            require((event == "AUTHORITY_REJECTED") == (result == "REJECTED"),
                    f"{authority_label} has an inconsistent rejection pair")
            target_prefix = requirement.get("targetPrefix")
            require(isinstance(target_prefix, str)
                    and AUTHORITY_TOKEN.fullmatch(target_prefix) is not None,
                    f"{authority_label} has no safe targetPrefix")
            exact_reason = requirement.get("reason")
            reason_prefix = requirement.get("reasonPrefix")
            require((exact_reason is None) != (reason_prefix is None),
                    f"{authority_label} must define exactly one reason contract")
            reason_contract = exact_reason if exact_reason is not None else reason_prefix
            require(isinstance(reason_contract, str)
                    and AUTHORITY_TOKEN.fullmatch(reason_contract) is not None,
                    f"{authority_label} has no safe reason contract")
            occurrences = requirement.get("minOccurrences", 1)
            require(type(occurrences) is int and 1 <= occurrences <= 4,
                    f"{authority_label} has invalid minOccurrences")
            require(event != "STATE_LOAD_SUMMARY"
                    or row_id in {
                        "journey.restart_persistence",
                        "stability.no_ui_fps_regression_or_log_errors",
                    }, f"{authority_label} weakens gameplay proof to a load summary")
        rows[row_id] = raw
    require(tuple(rows) == REQUIRED_MATRIX_ROW_IDS,
            "matrix row ID roster/order differs from the exact release contract")
    return rows, profiles


def validate_manifest(
    manifest: dict[str, object], expected_fingerprint: str,
    profiles: tuple[str, ...], matrix_hash: str, reproduction_hash: str,
    expected_game_directory: Path, run_dir: Path,
    operator_input_seal: dict[str, object], expected_session_nonce: str,
    expected_precommit_run_directory_token: str,
    expected_precommit_jar_sha256: str, expected_precommit_world_id: str,
) -> dict[str, object]:
    require(manifest.get("schemaVersion") == 3, "manifest schemaVersion is not 3")
    require(manifest.get("verdict") == "APPROVED", "manifest verdict is not APPROVED")
    require(manifest.get("sourceFingerprint") == expected_fingerprint,
            "manifest source fingerprint differs from the current frozen source")
    for key in REQUIRED_MANIFEST_STRINGS:
        require(isinstance(manifest.get(key), str) and str(manifest[key]).strip() != "",
                f"manifest {key} is missing")
    require(manifest.get("matrixSha256") == matrix_hash,
            "manifest matrix hash differs from the canonical approval matrix")
    require(manifest.get("reproductionSha256") == reproduction_hash,
            "manifest reproduction hash differs from reproduction.md")
    require(re.fullmatch(r"[0-9a-f]{40}", str(manifest.get("gitCommit"))) is not None,
            "manifest gitCommit is not a full lowercase commit hash")
    require(SHA256.fullmatch(str(manifest.get("dirtyHash"))) is not None,
            "manifest dirtyHash is not a lowercase SHA-256")
    require(manifest.get("minecraftVersion") == "1.21.1",
            "manifest Minecraft version is not the release target 1.21.1")
    require(str(manifest.get("javaVersion")).startswith("21"),
            "manifest Java version is not Java 21")
    renderer = str(manifest.get("renderer"))
    require(not re.search(r"llvmpipe|swiftshader|software|xvfb|fixture|unknown", renderer,
                          re.IGNORECASE),
            "manifest renderer is not a native hardware renderer")
    native_session = require_session(manifest.get("nativeSessionId"),
                                     "manifest nativeSessionId")
    require(manifest.get("clientObserverEnabled") is True,
            "manifest does not attest the release observer was enabled")
    observer_sources = manifest.get("observerActivationSources")
    require(isinstance(observer_sources, list) and observer_sources
            and len(observer_sources) == len(set(observer_sources))
            and all(source in {"environment", "one_shot_marker"}
                    for source in observer_sources),
            "manifest observerActivationSources are missing or invalid")
    started = utc_timestamp(manifest.get("startedAt"), "manifest startedAt")
    finished = utc_timestamp(manifest.get("finishedAt"), "manifest finishedAt")
    require(finished > started, "manifest finishedAt does not follow startedAt")
    now = dt.datetime.now(dt.timezone.utc)
    require(started <= now + CLOCK_SKEW and finished <= now + CLOCK_SKEW,
            "manifest native-run timestamps are implausibly in the future")
    require(now - finished <= RECENT_RUN_AGE,
            "manifest native run is older than the 24-hour approval window")
    require(type(manifest.get("guiScale")) is int and 1 <= int(manifest["guiScale"]) <= 8,
            "manifest guiScale is not a bounded integer")
    duration = manifest.get("durationSeconds")
    require(type(duration) in {int, float} and not isinstance(duration, bool)
            and 0 < float(duration) <= 172800,
            "manifest durationSeconds is not within (0, 172800]")
    measured_duration = (finished - started).total_seconds()
    require(abs(float(duration) - measured_duration) <= 5.0,
            "manifest durationSeconds disagrees with startedAt/finishedAt")
    require(manifest.get("nativeWindowsClient") is True,
            "manifest does not prove a native Windows client")
    require(manifest.get("freshWorld") is True, "manifest world is not marked fresh")
    require(manifest.get("naturalProgression") is True,
            "manifest does not attest normal progression")
    require(manifest.get("progressionCommandsUsed") == [],
            "progression commands were used; the journey cannot be approved")
    require(manifest.get("displayProfiles") == list(profiles),
            "manifest does not contain the exact ordered native display-profile roster")
    require(manifest.get("languages") == ["en_us", "nb_no"],
            "manifest does not prove both required language profiles")
    setup_commands = manifest.get("setupCommandsUsed")
    require(isinstance(setup_commands, list)
            and all(isinstance(command, str) for command in setup_commands),
            "manifest setupCommandsUsed is not a string list")
    disallowed_setup = [command for command in setup_commands
                        if not allowed_setup_command(command)]
    require(not disallowed_setup,
            f"setupCommandsUsed contains unapproved or progression-capable commands: "
            f"{disallowed_setup}")
    primary_profile = require_profile(manifest.get("displayProfile"), profiles,
                                      "manifest displayProfile")
    primary_match = PROFILE.fullmatch(primary_profile)
    assert primary_match is not None
    require(manifest.get("guiScale") == int(primary_match.group(3)),
            "manifest guiScale differs from displayProfile")
    require(manifest.get("language") == primary_match.group(4),
            "manifest language differs from displayProfile")
    require(re.fullmatch(r"-?[0-9]+", str(manifest.get("worldSeed"))) is not None,
            "manifest worldSeed is not an integer string")
    audio_device = str(manifest.get("audioOutputDevice")).strip().lower()
    require(audio_device not in {"none", "null", "default", "unknown", "fixture"},
            "manifest audioOutputDevice is not an identified native output device")

    game_directory = require_plain_directory(local_absolute_path(
        str(manifest["gameDirectory"]), "manifest gameDirectory"
    ), "manifest gameDirectory")
    require(os.path.samefile(game_directory, expected_game_directory),
            "manifest gameDirectory is not the exact expected CurseForge profile")
    mods_directory = require_plain_directory(game_directory / "mods",
                                             "expected profile mods directory")
    saves_directory = require_plain_directory(game_directory / "saves",
                                               "expected profile saves directory")
    world_directory = require_plain_directory(local_absolute_path(
        str(manifest["worldDirectory"]), "manifest worldDirectory"
    ), "manifest worldDirectory")
    require(os.path.samefile(world_directory.parent, saves_directory),
            "manifest worldDirectory is not a direct save in the expected profile")
    require(str(manifest["worldId"]) == world_directory.name,
            "manifest worldId differs from the exact save-directory identity")

    candidate_path, candidate_hash = validate_jar(manifest.get("candidateJar"), "candidateJar")
    installed_path, installed_hash = validate_jar(manifest.get("installedJar"), "installedJar")
    require(candidate_path != installed_path,
            "candidate and installed JAR must be separately hashed copies")
    require(candidate_hash == installed_hash,
            "installed JAR is not byte-identical to the candidate")
    require(os.path.samefile(installed_path.parent, mods_directory),
            "installed JAR is not inside the exact expected profile mods directory")
    identity: dict[str, object] = {
        "jarHash": candidate_hash,
        "nativeSessionId": native_session,
        "worldId": str(manifest["worldId"]),
        "worldSeed": str(manifest["worldSeed"]),
        "startedAt": started,
        "finishedAt": finished,
        "profiles": profiles,
        "observerSources": tuple(observer_sources),
        "audioDevice": str(manifest["audioOutputDevice"]).strip(),
        "gameDirectory": game_directory,
        "runtimeJarPath": installed_path,
        "worldDirectory": world_directory,
        "gameDirectoryToken": path_identity_token(game_directory),
        "runtimeJarPathToken": path_identity_token(installed_path),
        "worldPathToken": path_identity_token(world_directory),
    }
    identity["logSegments"] = validate_native_log_segments(
        manifest.get("nativeLogSegments"), run_dir, identity
    )
    identity["launchRegistry"] = validate_native_launch_registry(
        manifest.get("nativeLaunchRegistry"), run_dir, identity,
        identity["logSegments"],
    )
    identity["inputTranscript"] = validate_native_input_transcript(
        manifest.get("nativeInputTranscript"), run_dir, identity
    )
    validate_operator_input_seal_binding(
        operator_input_seal, manifest, run_dir, identity,
        expected_session_nonce, expected_precommit_run_directory_token,
        expected_precommit_jar_sha256, expected_precommit_world_id,
    )
    return identity


def profile_from_fields(fields: dict[str, object], label: str) -> str:
    framebuffer = str(fields.get("framebuffer", ""))
    match = re.fullmatch(r"([1-9][0-9]*)x([1-9][0-9]*)", framebuffer)
    require(match is not None, f"{label} has malformed framebuffer")
    try:
        scale_value = float(fields.get("guiScale", "nan"))
    except (TypeError, ValueError):
        fail(f"{label} has malformed guiScale")
    require(scale_value.is_integer() and 1 <= scale_value <= 8,
            f"{label} has invalid guiScale")
    language = fields.get("language")
    profile = f"{match.group(1)}x{match.group(2)}-gui{int(scale_value)}-{language}"
    return profile


def expected_screen(transition: str) -> str | None:
    if transition == "screen_none":
        return "null"
    if transition.startswith("screen_"):
        return transition.removeprefix("screen_")
    prefixes = {
        "hearth_": "HearthScreen",
        "development_": "DevelopmentScreen",
        "emblem_shop_": "EmblemShopScreen",
        "emblem_result_": "EmblemShopScreen",
        "plaque_": "PlaqueScreen",
        "settler_inventory_": "SettlerInventoryScreen",
        "settler_": "SettlerScreen",
        "guard_orders_": "GuardOrderScreen",
        "handbook_": "HandbookScreen",
    }
    for prefix, screen in prefixes.items():
        if transition.startswith(prefix):
            return screen
    return None


def finite_number(value: object, label: str, *, nonnegative: bool = False) -> float:
    require(not isinstance(value, bool), f"{label} is malformed")
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        fail(f"{label} is malformed")
    require(math.isfinite(parsed) and (not nonnegative or parsed >= 0.0),
            f"{label} is not a finite{' non-negative' if nonnegative else ''} number")
    return parsed


def nonnegative_integer(value: object, label: str) -> int:
    require(not isinstance(value, bool), f"{label} is malformed")
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        fail(f"{label} is malformed")
    require(str(parsed) == str(value) or type(value) is int,
            f"{label} is not an integer")
    require(parsed >= 0, f"{label} is negative")
    return parsed


def boolean_value(value: object, label: str) -> bool:
    require(value is True or value is False or value == "true" or value == "false",
            f"{label} is not boolean")
    return value is True or value == "true"


def player_position(value: object, label: str) -> tuple[float, float, float]:
    parts: object
    if isinstance(value, str):
        parts = value.split(",")
    else:
        parts = value
    require(isinstance(parts, (list, tuple)) and len(parts) == 3,
            f"{label} is not x,y,z")
    return tuple(finite_number(part, label) for part in parts)  # type: ignore[return-value]


def validate_ack_identity(
    fields: dict[str, object], label: str, identity: dict[str, object],
) -> str:
    require(fields.get("nativeWindows") in {True, "true"},
            f"{label} is not native Windows")
    require(fields.get("runtimeJarSha256") == identity["jarHash"],
            f"{label} runtime JAR hash differs from the exact candidate")
    require(fields.get("runtimeJarSource") in {"code_source", "mod_list"},
            f"{label} runtime JAR did not resolve from an installed mod JAR")
    require(fields.get("gameDirectoryToken") == identity["gameDirectoryToken"],
            f"{label} game directory differs from the expected CurseForge profile")
    require(fields.get("runtimeJarPathToken") == identity["runtimeJarPathToken"],
            f"{label} runtime JAR path differs from the exact installed JAR")
    require(fields.get("worldPathToken") == identity["worldPathToken"],
            f"{label} belongs to a different save directory")
    require(fields.get("integratedServer") in {True, "true"},
            f"{label} is not bound to the integrated server")
    require(fields.get("qaSession") == identity["nativeSessionId"],
            f"{label} belongs to a stale/different native QA session")
    require(fields.get("observerSource") in identity["observerSources"],
            f"{label} observer activation source is missing or differs")
    require(fields.get("languageResolved") in {True, "true"}
            or fields.get("blessingTitleResolved") in {True, "true"},
            f"{label} did not resolve Hearthstead language text")
    require(fields.get("languageMatch") in {True, "true"}
            or fields.get("blessingTitleLanguageMatch") in {True, "true"},
            f"{label} language does not match the selected profile")
    profile = profile_from_fields(fields, label)
    require(profile in identity["profiles"], f"{label} uses unexpected profile {profile}")
    observed_millis = nonnegative_integer(fields.get("observedEpochMillis"),
                                          f"{label} observedEpochMillis")
    try:
        observed = dt.datetime.fromtimestamp(
            observed_millis / 1000.0, tz=dt.timezone.utc
        )
    except (OverflowError, OSError, ValueError) as exc:
        fail(f"{label} observedEpochMillis is outside the UTC range: {exc}")
    require(identity["startedAt"] <= observed <= identity["finishedAt"],
            f"{label} runtime timestamp lies outside the native run")
    return profile


def validate_ack_state(
    fields: dict[str, object], label: str, identity: dict[str, object],
    *, require_timing_window: bool,
) -> str:
    profile = validate_ack_identity(fields, label, identity)
    declared_profile = fields.get("displayProfile")
    require(declared_profile in {None, profile},
            f"{label} displayProfile differs from framebuffer/scale/language")
    nonce = fields.get("nonce")
    require(isinstance(nonce, str) and VALID_NONCE.fullmatch(nonce) is not None,
            f"{label} nonce is malformed")
    transition = fields.get("transition", fields.get("uiTransition"))
    require(isinstance(transition, str) and transition,
            f"{label} transition is missing")
    screen = fields.get("screen")
    screen_class = fields.get("screenClass")
    require(isinstance(screen, str) and isinstance(screen_class, str),
            f"{label} screen identity is missing")
    required_screen = expected_screen(transition)
    if required_screen is not None:
        require(screen == required_screen
                and (required_screen == "null"
                     or screen_class.endswith("." + required_screen)),
                f"{label} transition {transition} is on the wrong screen")
    boolean_value(fields.get("grabbed"), f"{label} grabbed")
    finite_number(fields.get("yaw"), f"{label} yaw")
    pitch = finite_number(fields.get("pitch"), f"{label} pitch")
    require(-90.0 <= pitch <= 90.0, f"{label} pitch lies outside [-90, 90]")
    player_position(fields.get("playerPos"), f"{label} playerPos")
    require(isinstance(fields.get("hitType"), str) and fields.get("hitType") != "",
            f"{label} hitType is missing")
    require(isinstance(fields.get("hitBlock"), str) and fields.get("hitBlock") != "",
            f"{label} hitBlock is missing")
    ui_state = fields.get("uiState")
    require(transition == "screen_none"
            or ui_state not in {None, "", "none", "null", "unavailable", "uninitialised"},
            f"{label} has no inspectable UI state")

    samples = nonnegative_integer(
        fields.get("samples", fields.get("frameSamples")), f"{label} samples"
    )
    over_33 = nonnegative_integer(
        fields.get("framesOver33Ms"), f"{label} framesOver33Ms"
    )
    over_100 = nonnegative_integer(
        fields.get("framesOver100Ms"), f"{label} framesOver100Ms"
    )
    require(over_100 <= over_33 <= samples,
            f"{label} slow-frame counters exceed the sample count")
    p50 = finite_number(fields.get("p50Ms", fields.get("frameP50Ms")),
                        f"{label} p50Ms", nonnegative=True)
    p95 = finite_number(fields.get("p95Ms", fields.get("frameP95Ms")),
                        f"{label} p95Ms", nonnegative=True)
    p99 = finite_number(fields.get("p99Ms", fields.get("frameP99Ms")),
                        f"{label} p99Ms", nonnegative=True)
    maximum = finite_number(fields.get("maxMs", fields.get("frameMaxMs")),
                            f"{label} maxMs", nonnegative=True)
    require(p50 <= p95 <= p99 <= maximum,
            f"{label} frame percentiles are not monotonic")
    if require_timing_window:
        require(samples >= 180, f"{label} has fewer than 180 post-warm-up frames")
        require(p95 <= 33.3, f"{label} p95 exceeds the native 33.3 ms ceiling")
        require(maximum <= 100.0, f"{label} maximum frame exceeds 100 ms")
        require(over_100 == 0, f"{label} contains a frame over 100 ms")
    if fields.get("slowFraction") is not None:
        expected_fraction = over_33 / samples if samples else None
        require(expected_fraction is not None
                and abs(finite_number(fields.get("slowFraction"),
                                      f"{label} slowFraction", nonnegative=True)
                        - expected_fraction) <= 0.000001,
                f"{label} slowFraction disagrees with its counters")
    return profile


def ack_signature(fields: dict[str, object], label: str) -> tuple[object, ...]:
    return (
        fields.get("nonce"), fields.get("qaSession"),
        fields.get("observerSource"), boolean_value(fields.get("nativeWindows"),
                                                     f"{label} nativeWindows"),
        fields.get("runtimeJarSha256"), fields.get("runtimeJarSource"),
        fields.get("gameDirectoryToken"), fields.get("runtimeJarPathToken"),
        fields.get("worldPathToken"),
        boolean_value(fields.get("integratedServer"),
                      f"{label} integratedServer"),
        nonnegative_integer(fields.get("observedEpochMillis"),
                            f"{label} observedEpochMillis"),
        fields.get("screen"),
        fields.get("screenClass"), boolean_value(fields.get("grabbed"),
                                                  f"{label} grabbed"),
        finite_number(fields.get("yaw"), f"{label} yaw"),
        finite_number(fields.get("pitch"), f"{label} pitch"),
        player_position(fields.get("playerPos"), f"{label} playerPos"),
        fields.get("hitType"), fields.get("hitBlock"), fields.get("uiState"),
        fields.get("transition", fields.get("uiTransition")),
        profile_from_fields(fields, label),
        boolean_value(fields.get("languageResolved",
                                 fields.get("blessingTitleResolved")),
                      f"{label} languageResolved"),
        boolean_value(fields.get("languageMatch",
                                 fields.get("blessingTitleLanguageMatch")),
                      f"{label} languageMatch"),
        nonnegative_integer(fields.get("samples", fields.get("frameSamples")),
                            f"{label} samples"),
        finite_number(fields.get("p50Ms", fields.get("frameP50Ms")),
                      f"{label} p50Ms", nonnegative=True),
        finite_number(fields.get("p95Ms", fields.get("frameP95Ms")),
                      f"{label} p95Ms", nonnegative=True),
        finite_number(fields.get("p99Ms", fields.get("frameP99Ms")),
                      f"{label} p99Ms", nonnegative=True),
        finite_number(fields.get("maxMs", fields.get("frameMaxMs")),
                      f"{label} maxMs", nonnegative=True),
        nonnegative_integer(fields.get("framesOver33Ms"),
                            f"{label} framesOver33Ms"),
        nonnegative_integer(fields.get("framesOver100Ms"),
                            f"{label} framesOver100Ms"),
    )


def parse_native_acks(
    text: str, label: str, identity: dict[str, object],
) -> tuple[set[str], list[dict[str, object]]]:
    profiles: set[str] = set()
    records: list[dict[str, object]] = []
    seen_nonces: set[str] = set()
    count = 0
    for line_number, line in enumerate(text.splitlines(), 1):
        try:
            parsed = parse_fixed_log_record(line, ACK_MARKER, ACK_FIELDS)
        except EvidenceContractError as exc:
            fail(f"{label} line {line_number}: {exc}")
        if parsed is None:
            continue
        count += 1
        fields: dict[str, object] = dict(parsed)
        nonce = str(fields["nonce"])
        require(nonce not in seen_nonces,
                f"{label} repeats native acknowledgement nonce {nonce}")
        seen_nonces.add(nonce)
        record: dict[str, object] = dict(fields)
        record["line"] = line_number
        record["transition"] = fields["uiTransition"]
        record["displayProfile"] = profile_from_fields(record,
                                                        f"{label} line {line_number}")
        record["languageResolved"] = fields["blessingTitleResolved"] == "true"
        record["languageMatch"] = fields["blessingTitleLanguageMatch"] == "true"
        record["samples"] = nonnegative_integer(fields["frameSamples"],
                                                 f"{label} line {line_number} samples")
        record["p50Ms"] = finite_number(fields["frameP50Ms"],
                                         f"{label} line {line_number} p50Ms",
                                         nonnegative=True)
        record["p95Ms"] = finite_number(fields["frameP95Ms"],
                                         f"{label} line {line_number} p95Ms",
                                         nonnegative=True)
        record["p99Ms"] = finite_number(fields["frameP99Ms"],
                                         f"{label} line {line_number} p99Ms",
                                         nonnegative=True)
        record["maxMs"] = finite_number(fields["frameMaxMs"],
                                         f"{label} line {line_number} maxMs",
                                         nonnegative=True)
        record["framesOver33Ms"] = nonnegative_integer(
            fields["framesOver33Ms"], f"{label} line {line_number} framesOver33Ms"
        )
        record["framesOver100Ms"] = nonnegative_integer(
            fields["framesOver100Ms"], f"{label} line {line_number} framesOver100Ms"
        )
        record["slowFraction"] = (
            int(record["framesOver33Ms"]) / int(record["samples"])
            if int(record["samples"]) else None
        )
        record_label = f"{label} line {line_number}"
        profiles.add(validate_ack_state(record, record_label, identity,
                                        require_timing_window=False))
        records.append(record)
    require(count > 0, f"{label} has no native HSQA_FRAME_ACK")
    return profiles, records


def validate_frame_window(
    window: object, label: str, identity: dict[str, object],
) -> str:
    require(isinstance(window, dict), f"{label} window is missing")
    return validate_ack_state(window, label, identity, require_timing_window=True)


def validate_frame_report(
    path: Path, label: str, identity: dict[str, object],
) -> tuple[
    set[str], set[str], str, set[tuple[object, ...]],
    list[dict[str, object]],
]:
    report = load_object(path, label)
    require(report.get("pass") is True and report.get("failures") == [],
            f"{label} does not contain an unambiguous PASS")
    budgets = report.get("budgets")
    require(isinstance(budgets, dict), f"{label} budgets are missing")
    require(type(budgets.get("minSamples")) is int
            and int(budgets["minSamples"]) >= 180,
            f"{label} minSamples is weaker than 180")
    numeric_limits = (
        ("maxP95Ratio", 1.35),
        ("maxSlowFractionRise", 0.10),
        ("maxP95Ms", 33.3),
        ("maxFrameMs", 100.0),
    )
    for key, ceiling in numeric_limits:
        require(type(budgets.get(key)) in {int, float}
                and not isinstance(budgets.get(key), bool)
                and 0 < float(budgets[key]) <= ceiling,
                f"{label} {key} is missing or weaker than {ceiling}")
    require(budgets.get("maxFramesOver100Ms") == 0,
            f"{label} allows frames above 100 ms")
    baseline = report.get("baseline")
    profiles = {validate_frame_window(baseline, f"{label} baseline", identity)}
    assert isinstance(baseline, dict)
    require(float(baseline["p95Ms"]) > 0.0,
            f"{label} baseline p95 is not usable")
    acknowledgements = report.get("acknowledgements")
    require(isinstance(acknowledgements, list) and acknowledgements,
            f"{label} acknowledgements are missing")
    acknowledgement_keys: set[tuple[object, ...]] = set()
    acknowledgement_signatures: set[tuple[object, ...]] = set()
    seen_nonces: set[str] = set()
    for index, acknowledgement in enumerate(acknowledgements):
        ack_label = f"{label} acknowledgement {index}"
        require(isinstance(acknowledgement, dict), f"{ack_label} is not an object")
        profiles.add(validate_ack_identity(acknowledgement, ack_label, identity))
        nonce = acknowledgement.get("nonce")
        require(isinstance(nonce, str) and nonce and nonce not in seen_nonces,
                f"{ack_label} nonce is missing or duplicated")
        seen_nonces.add(nonce)
        signature = ack_signature(acknowledgement, ack_label)
        require(signature not in acknowledgement_signatures,
                f"{ack_label} duplicates an acknowledgement record")
        acknowledgement_signatures.add(signature)
        acknowledgement_keys.add((
            nonce, acknowledgement.get("transition"), acknowledgement.get("samples"),
            acknowledgement.get("displayProfile"),
        ))
    baseline_key = (
        baseline.get("nonce"), baseline.get("transition"), baseline.get("samples"),
        baseline.get("displayProfile"),
    )
    require(baseline_key in acknowledgement_keys,
            f"{label} baseline is not one of the logged acknowledgements")
    selected_windows: list[dict[str, object]] = [baseline]
    checks = report.get("checks")
    require(isinstance(checks, list) and checks, f"{label} checks are missing")
    transitions: set[str] = set()
    for index, check in enumerate(checks):
        check_label = f"{label} check {index}"
        require(isinstance(check, dict) and check.get("pass") is True,
                f"{check_label} is not PASS")
        transition = check.get("transition")
        require(isinstance(transition, str) and transition
                and transition not in transitions,
                f"{check_label} transition is missing or duplicated")
        transitions.add(transition)
        window = check.get("window")
        profiles.add(validate_frame_window(window, check_label, identity))
        assert isinstance(window, dict)
        window_key = (
            window.get("nonce"), window.get("transition"), window.get("samples"),
            window.get("displayProfile"),
        )
        require(window_key in acknowledgement_keys,
                f"{check_label} window is not one of the logged acknowledgements")
        selected_windows.append(window)
        p95_ratio = float(window["p95Ms"]) / float(baseline["p95Ms"])
        slow_rise = float(window["slowFraction"]) - float(baseline["slowFraction"])
        require(p95_ratio <= float(budgets["maxP95Ratio"]),
                f"{check_label} p95 ratio exceeds its budget")
        require(slow_rise <= float(budgets["maxSlowFractionRise"]),
                f"{check_label} slow-frame rise exceeds its budget")
        require(type(check.get("p95Ratio")) in {int, float}
                and abs(float(check["p95Ratio"]) - round(p95_ratio, 4)) <= 0.0001,
                f"{check_label} recorded p95Ratio disagrees with the window")
        require(type(check.get("slowFractionRise")) in {int, float}
                and abs(float(check["slowFractionRise"]) - round(slow_rise, 4)) <= 0.0001,
                f"{check_label} recorded slowFractionRise disagrees with the window")
    client_hash = require_sha(report.get("clientLogSha256"),
                              f"{label} clientLogSha256")
    require(isinstance(report.get("clientLog"), str) and report["clientLog"],
            f"{label} clientLog path is missing")
    return (profiles, transitions, client_hash, acknowledgement_signatures,
            selected_windows)


def validate_world_manifest(
    path: Path, label: str, run_dir: Path, expected_fingerprint: str,
    identity: dict[str, object],
) -> None:
    world = load_object(path, label)
    require(world.get("schemaVersion") == 1, f"{label} schemaVersion is not 1")
    require(world.get("sourceFingerprint") == expected_fingerprint,
            f"{label} source fingerprint differs")
    require(world.get("jarSha256") == identity["jarHash"],
            f"{label} JAR hash differs")
    require(world.get("nativeSessionId") == identity["nativeSessionId"],
            f"{label} native session differs")
    require(world.get("worldId") == identity["worldId"], f"{label} worldId differs")
    require(str(world.get("worldSeed")) == identity["worldSeed"],
            f"{label} worldSeed differs")
    require(world.get("freshWorld") is True, f"{label} world is not marked fresh")
    before = world.get("beforeRestart")
    after = world.get("afterRestart")
    require(isinstance(before, dict) and before == after,
            f"{label} before/after restart state differs")
    required_state = {
        "settlementIds", "buildingIds", "settlerIds", "developmentUnlocked",
        "raidOutcome", "blessingTargets", "guardXp", "inventoryDigest",
    }
    require(required_state <= before.keys(),
            f"{label} restart state lacks {sorted(required_state - before.keys())}")
    require(isinstance(before["settlementIds"], list)
            and len(before["settlementIds"]) == 1,
            f"{label} does not preserve exactly one settlement identity")
    require(isinstance(before["buildingIds"], list) and before["buildingIds"],
            f"{label} has no persisted building identity")
    require(isinstance(before["settlerIds"], list) and len(before["settlerIds"]) >= 4,
            f"{label} has fewer than four persisted settler identities")
    archive = world.get("saveArchive")
    require(isinstance(archive, dict), f"{label} saveArchive is missing")
    archive_path = resolve_evidence(run_dir, archive.get("path"), f"{label} saveArchive")
    require(archive_path.parent.name == "world" and archive_path.suffix.lower() == ".zip",
            f"{label} saveArchive is not a world/*.zip artifact")
    archive_hash = require_sha(archive.get("sha256"), f"{label} saveArchive sha256")
    require(digest(archive_path) == archive_hash, f"{label} saveArchive hash differs")
    require(zipfile.is_zipfile(archive_path), f"{label} saveArchive is not a valid ZIP")
    with zipfile.ZipFile(archive_path) as saved:
        names = saved.namelist()
        bad_member = saved.testzip()
    require(bad_member is None, f"{label} saveArchive has corrupt member {bad_member}")
    require(any(name == "level.dat" or name.endswith("/level.dat") for name in names),
            f"{label} saveArchive contains no level.dat")


def validate_result(
    result: dict[str, object], matrix_rows: dict[str, dict[str, object]],
    run_dir: Path, expected_fingerprint: str, matrix_hash: str,
    identity: dict[str, object],
) -> None:
    require(result.get("schemaVersion") == 2, "result schemaVersion is not 2")
    require(result.get("verdict") == "APPROVED", "result verdict is not APPROVED")
    require(result.get("sourceFingerprint") == expected_fingerprint,
            "result source fingerprint differs from the frozen source")
    require(result.get("matrixSha256") == matrix_hash,
            "result matrix hash differs from the canonical matrix")
    require(result.get("jarSha256") == identity["jarHash"],
            "result JAR hash differs from the exact JAR")
    require(result.get("nativeSessionId") == identity["nativeSessionId"],
            "result native session differs from the manifest")
    require(result.get("worldId") == identity["worldId"],
            "result worldId differs from the manifest")
    raw_rows = result.get("rows")
    require(isinstance(raw_rows, list), "result rows are missing")

    actual: dict[str, dict[str, object]] = {}
    for index, raw in enumerate(raw_rows):
        require(isinstance(raw, dict), f"result row {index} is not an object")
        row_id = raw.get("id")
        require(isinstance(row_id, str) and row_id != "", f"result row {index} has no id")
        require(row_id not in actual, f"result row is duplicated: {row_id}")
        actual[row_id] = raw
    missing = sorted(set(matrix_rows) - set(actual))
    extra = sorted(set(actual) - set(matrix_rows))
    require(not missing and not extra,
            f"result matrix mismatch missing={missing} extra={extra}")

    native_profiles: set[str] = set()
    client_log_hashes: set[str] = set()
    frame_client_log_hashes: set[str] = set()
    client_log_ack_signatures: dict[str, set[tuple[object, ...]]] = {}
    client_log_ack_records: dict[str, list[dict[str, object]]] = {}
    frame_report_ack_signatures: list[
        tuple[
            str, str, set[tuple[object, ...]], list[dict[str, object]],
        ]
    ] = []
    used_locators: set[tuple[object, ...]] = set()
    used_authority_lines: set[tuple[str, int]] = set()
    used_authority_transactions: set[tuple[object, ...]] = set()
    unique_visual_fingerprints: dict[tuple[str, str, str], str] = {}
    unique_native_media_hashes: dict[tuple[str, str], str] = {}
    unique_native_media_fingerprints: dict[tuple[str, str, str], str] = {}
    sealed_segments = identity.get("logSegments")
    assert isinstance(sealed_segments, dict)
    used_client_segment_paths: set[str] = set()
    complete_authority_by_log: dict[str, list[dict[str, object]]] = {}
    required_critical_authority: set[tuple[str, str]] = set()

    for row_id, expected in matrix_rows.items():
        row = actual[row_id]
        require(row.get("status") == "APPROVED", f"{row_id} is not APPROVED")
        require(row.get("realClient") is True, f"{row_id} is not marked real-client observed")
        require(row.get("triggerMode") == expected["triggerMode"],
                f"{row_id} triggerMode differs from the matrix")
        require(row.get("verifiedAssertions")
                == expected.get("acceptanceAssertions", []),
                f"{row_id} does not explicitly verify its matrix assertions")
        require(row.get("nativeSessionId") == identity["nativeSessionId"],
                f"{row_id} belongs to a stale/different native session")
        require(row.get("worldId") == identity["worldId"],
                f"{row_id} belongs to a different world")
        observed_at = utc_timestamp(row.get("observedAt"), f"{row_id} observedAt")
        reviewed_at = utc_timestamp(row.get("reviewedAt"), f"{row_id} reviewedAt")
        require(identity["startedAt"] <= observed_at <= reviewed_at <= identity["finishedAt"],
                f"{row_id} observation/review time lies outside the current run")
        for field in ("authorityNote", "observationNote"):
            require(isinstance(row.get(field), str) and len(str(row[field]).strip()) >= 24,
                    f"{row_id} {field} is missing or too vague")
        require(isinstance(row.get("reviewedBy"), str)
                and len(str(row["reviewedBy"]).strip()) >= 3,
                f"{row_id} reviewedBy is missing")

        required_kinds = set(expected["requiredEvidenceKinds"])
        raw_authority_requirements = expected.get(
            "requiredAuthorityTransactions", []
        )
        assert isinstance(raw_authority_requirements, list)
        authority_requirements = {
            str(requirement["id"]): requirement
            for requirement in raw_authority_requirements
        }
        authority_requirement_counts = {
            requirement_id: 0 for requirement_id in authority_requirements
        }
        if "server_log" in required_kinds:
            require(row.get("serverAuthorityObserved") is True,
                    f"{row_id} lacks an explicit server-authority verdict")
        if "audio" in required_kinds:
            require(row.get("nativeAudioObserved") is True,
                    f"{row_id} lacks an explicit native-audio verdict")
        if "frame_report" in required_kinds:
            require(row.get("frameTimingObserved") is True,
                    f"{row_id} lacks an explicit native-frame verdict")
        if expected["triggerMode"] == "restart":
            require(row.get("restartObserved") is True,
                    f"{row_id} lacks an explicit restart verdict")

        raw_evidence = row.get("evidence")
        require(isinstance(raw_evidence, list) and raw_evidence,
                f"{row_id} has no evidence")
        seen_kinds: set[str] = set()
        row_profiles: set[str] = set()
        row_profile_kinds: set[tuple[str, str]] = set()
        row_transitions: set[str] = set()
        row_client_acks: list[dict[str, object]] = []
        row_log_segment_indices: dict[str, set[int]] = {
            "client_log": set(), "server_log": set(),
        }
        for evidence_index, item in enumerate(raw_evidence):
            label = f"{row_id} evidence {evidence_index}"
            require(isinstance(item, dict), f"{label} is not an object")
            kind = item.get("kind")
            require(kind in ALLOWED_SUFFIXES, f"{label} has an unknown kind")
            raw_path = item.get("path")
            require(isinstance(raw_path, str), f"{label} path is missing")
            parts = Path(raw_path).parts
            require(bool(parts) and parts[0] == EVIDENCE_DIRECTORIES[str(kind)],
                    f"{label} is outside the required {EVIDENCE_DIRECTORIES[str(kind)]}/ directory")
            path = resolve_evidence(run_dir, raw_path, label)
            require(path.suffix.lower() in ALLOWED_SUFFIXES[str(kind)],
                    f"{label} extension does not match kind {kind}")
            declared = require_sha(item.get("sha256"), f"{label} sha256")
            actual_hash = digest(path)
            require(actual_hash == declared, f"{label} hash differs from the file")
            captured_at = utc_timestamp(item.get("capturedAt"), f"{label} capturedAt")
            require(identity["startedAt"] <= captured_at <= reviewed_at,
                    f"{label} timestamp lies outside the current native run")
            require(item.get("nativeSessionId") == identity["nativeSessionId"],
                    f"{label} belongs to a stale/different native session")
            require(item.get("worldId") == identity["worldId"],
                    f"{label} belongs to a different world")

            evidence_profile: str | None = None
            if kind in PROFILE_BOUND_KINDS:
                evidence_profile = require_profile(
                    item.get("displayProfile"), identity["profiles"],
                    f"{label} displayProfile",
                )
                profile_match = PROFILE.fullmatch(evidence_profile)
                assert profile_match is not None
                require(item.get("language") == profile_match.group(4),
                        f"{label} language differs from displayProfile")
                row_profiles.add(evidence_profile)
                row_profile_kinds.add((str(kind), evidence_profile))

            locator = item.get("locator")
            require(isinstance(locator, dict), f"{label} locator is missing")
            locator_key: tuple[object, ...]
            if kind in {"client_log", "server_log"}:
                sealed_segment = sealed_segments.get(raw_path)
                require(isinstance(sealed_segment, dict),
                        f"{label} is not an immutable helper-sealed native log segment")
                require(sealed_segment.get("sha256") == actual_hash,
                        f"{label} differs from its native log-segment seal")
                segment_index = int(sealed_segment["index"])
                row_log_segment_indices[str(kind)].add(segment_index)
                text = validate_log_file(path, label)
                lines = text.splitlines()
                if raw_path not in complete_authority_by_log:
                    complete_authority_by_log[raw_path] = [
                        parse_authority_v1_line(
                            source_line,
                            f"{label} complete source log line {line_number}",
                        )
                        for line_number, source_line in enumerate(lines, 1)
                        if AUTHORITY_MARKER in source_line
                    ]
                start = locator.get("lineStart")
                end = locator.get("lineEnd")
                require(type(start) is int and type(end) is int
                        and 1 <= start <= end <= len(lines),
                        f"{label} log locator is outside the file")
                excerpt = "\n".join(lines[start - 1:end])
                locator_key = (actual_hash, kind, start, end)
                if kind == "client_log":
                    used_client_segment_paths.add(raw_path)
                    require(locator.get("state") == row_id,
                            f"{label} client-log locator is not bound to its matrix row")
                    excerpt_profiles, excerpt_records = parse_native_acks(
                        excerpt, label, identity
                    )
                    require(excerpt_profiles == {evidence_profile},
                            f"{label} locator does not prove exactly its declared profile")
                    nonce_token = re.sub(r"[^A-Za-z0-9_-]", "_", row_id)
                    require(any(nonce_token in str(record.get("nonce"))
                                for record in excerpt_records),
                            f"{label} ACK nonce is not bound to its matrix row")
                    row_client_acks.extend(excerpt_records)
                    if actual_hash not in client_log_ack_signatures:
                        _, complete_records = parse_native_acks(
                            text, f"{label} complete source log", identity
                        )
                        for record in complete_records:
                            record["segmentIndex"] = segment_index
                        client_log_ack_signatures[actual_hash] = {
                            ack_signature(record, f"{label} complete source log")
                            for record in complete_records
                        }
                        client_log_ack_records[actual_hash] = complete_records
                    native_profiles.update(excerpt_profiles)
                    client_log_hashes.add(actual_hash)
                else:
                    require(int(sealed_segment.get("serverAuthorityLineCount", 0)) > 0,
                            f"{label} sealed launch contains no integrated Server-thread line")
                    require(locator.get("state") == row_id,
                            f"{label} server-log locator is not bound to its matrix row")
                    authority_event = locator.get("authorityEvent")
                    authority_result = locator.get("authorityResult")
                    authority_target = locator.get("authorityTarget")
                    authority_reason = locator.get("authorityReason")
                    authority_requirement_id = locator.get(
                        "authorityRequirement"
                    )
                    require(authority_event in AUTHORITY_EVENTS,
                            f"{label} has no exact V1 authorityEvent")
                    require(authority_result in AUTHORITY_RESULTS,
                            f"{label} has no exact V1 authorityResult")
                    require(isinstance(authority_target, str)
                            and AUTHORITY_TOKEN.fullmatch(authority_target) is not None,
                            f"{label} has no safe exact V1 authorityTarget")
                    if authority_reason is not None:
                        require(isinstance(authority_reason, str)
                                and AUTHORITY_TOKEN.fullmatch(authority_reason) is not None,
                                f"{label} authorityReason is not a safe exact V1 token")
                    require(isinstance(authority_requirement_id, str)
                            and authority_requirement_id in authority_requirements,
                            f"{label} is not bound to a matrix authority requirement")
                    authority_requirement = authority_requirements[
                        authority_requirement_id
                    ]
                    require(authority_event == authority_requirement["event"]
                            and authority_result == authority_requirement["result"],
                            f"{label} authority event/result differs from its "
                            "matrix requirement")
                    require(str(authority_target).startswith(
                        str(authority_requirement["targetPrefix"])
                    ), f"{label} authority target differs from its matrix requirement")
                    if "reason" in authority_requirement:
                        require(authority_reason == authority_requirement["reason"],
                                f"{label} authority reason differs from its "
                                "matrix requirement")
                    else:
                        require(isinstance(authority_reason, str)
                                and authority_reason.startswith(str(
                                    authority_requirement["reasonPrefix"]
                                )), f"{label} authority reason differs from its "
                                "matrix requirement")

                    marker_lines = [
                        (offset, line)
                        for offset, line in enumerate(excerpt.splitlines())
                        if AUTHORITY_MARKER in line
                    ]
                    require(marker_lines,
                            f"{label} excerpt has no {AUTHORITY_MARKER} record")
                    parsed_authority = [
                        (offset, parse_authority_v1_line(line, label))
                        for offset, line in marker_lines
                    ]
                    matching_authority = [
                        (offset, record) for offset, record in parsed_authority
                        if record["event"] == authority_event
                        and record["result"] == authority_result
                        and record["target"] == authority_target
                        and (authority_reason is None
                             or record["reason"] == authority_reason)
                    ]
                    require(len(matching_authority) == 1,
                            f"{label} must contain exactly one structurally valid, "
                            "exactly located V1 authority transaction")
                    matched_offset, matched_record = matching_authority[0]
                    authority_line_key = (actual_hash, int(start) + matched_offset)
                    require(authority_line_key not in used_authority_lines,
                            f"{label} reuses an authority log line already claimed "
                            "by another matrix requirement")
                    used_authority_lines.add(authority_line_key)
                    authority_transaction_key = tuple(
                        matched_record[field] for field in AUTHORITY_FIELDS
                    )
                    require(authority_transaction_key
                            not in used_authority_transactions,
                            f"{label} reuses an authority transaction signature "
                            "already claimed by another matrix requirement")
                    used_authority_transactions.add(authority_transaction_key)
                    authority_requirement_counts[authority_requirement_id] += 1
                    if matched_record["event"] in CRITICAL_RAID_AUTHORITY_EVENTS:
                        required_critical_authority.add((
                            str(matched_record["event"]),
                            str(matched_record["settlement"]),
                        ))
            elif kind in {"audio", "video"}:
                content_fingerprint: str | None = None
                if kind == "audio":
                    duration = validate_audio(path, label)
                else:
                    duration, content_fingerprint = validate_video(path, label)
                start = locator.get("startSeconds")
                end = locator.get("endSeconds")
                require(type(start) in {int, float} and type(end) in {int, float}
                        and not isinstance(start, bool) and not isinstance(end, bool)
                        and 0 <= float(start) < float(end) <= duration + 0.05
                        and 0.25 <= float(end) - float(start) <= 60.0,
                        f"{label} media locator is outside the decoded clip")
                if kind == "audio":
                    content_fingerprint = validate_audio_segment(
                        path, float(start), float(end), label
                    )
                    validate_audio_capture_report(
                        item.get("captureReport"), path, label, identity
                    )
                assert content_fingerprint is not None
                require(locator.get("state") == row_id,
                        f"{label} media locator is not bound to its matrix row")
                media_key = (str(kind), actual_hash)
                previous = unique_native_media_hashes.get(media_key)
                require(previous is None,
                        f"{label} reuses generic {kind} evidence from {previous}")
                unique_native_media_hashes[media_key] = label
                fingerprint_key = (
                    str(kind), str(evidence_profile), content_fingerprint,
                )
                previous_fingerprint = unique_native_media_fingerprints.get(
                    fingerprint_key
                )
                require(previous_fingerprint is None,
                        f"{label} decodes to generic {kind} evidence from "
                        f"{previous_fingerprint}")
                unique_native_media_fingerprints[fingerprint_key] = label
                locator_key = (
                    actual_hash, kind, round(float(start), 3), round(float(end), 3),
                    row_id,
                )
            elif kind in {"screenshot", "contact_sheet"}:
                width, height, pixel_fingerprint = png_dimensions(path, label)
                frame_label = locator.get("frameLabel")
                require(isinstance(frame_label, str) and len(frame_label.strip()) >= 8,
                        f"{label} frameLabel is missing or vague")
                require(row_id in frame_label,
                        f"{label} frameLabel is not bound to its matrix row")
                if kind == "screenshot":
                    profile_match = PROFILE.fullmatch(str(evidence_profile))
                    assert profile_match is not None
                    require((width, height) == (int(profile_match.group(1)),
                                                 int(profile_match.group(2))),
                            f"{label} PNG size does not match displayProfile")
                visual_key = (str(kind), str(evidence_profile), pixel_fingerprint)
                previous = unique_visual_fingerprints.get(visual_key)
                require(previous is None,
                        f"{label} decodes to generic visual evidence from {previous}")
                unique_visual_fingerprints[visual_key] = row_id
                locator_key = (actual_hash, kind, frame_label)
            elif kind == "frame_report":
                (report_profiles, transitions, client_hash, report_signatures,
                 selected_windows) = validate_frame_report(path, label, identity)
                require(report_profiles == {evidence_profile},
                        f"{label} mixes or mislabels native profiles")
                native_profiles.update(report_profiles)
                row_transitions.update(transitions)
                frame_client_log_hashes.add(client_hash)
                frame_report_ack_signatures.append(
                    (label, client_hash, report_signatures, selected_windows)
                )
                located = locator.get("transitions")
                require(isinstance(located, list) and located
                        and len(located) == len(set(located))
                        and all(isinstance(value, str) and value in transitions
                                for value in located),
                        f"{label} transition locator is missing or not in the report")
                locator_key = (actual_hash, kind, *located)
            elif kind == "world_manifest":
                require(locator == {"state": "before-after-restart"},
                        f"{label} world locator is not before-after-restart")
                validate_world_manifest(path, label, run_dir, expected_fingerprint,
                                        identity)
                locator_key = (actual_hash, kind, "before-after-restart")
            else:
                fail(f"{label} kind has no fail-closed validator")

            require(locator_key not in used_locators,
                    f"{label} reuses an evidence file and locator already claimed by another row")
            used_locators.add(locator_key)
            seen_kinds.add(str(kind))
        require(required_kinds <= seen_kinds,
                f"{row_id} lacks evidence kinds {sorted(required_kinds - seen_kinds)}")
        for requirement_id, requirement in authority_requirements.items():
            expected_occurrences = int(requirement.get("minOccurrences", 1))
            require(authority_requirement_counts[requirement_id]
                    == expected_occurrences,
                    f"{row_id} authority requirement {requirement_id} has "
                    f"{authority_requirement_counts[requirement_id]} observations; "
                    f"expected exactly {expected_occurrences}")
        declared_profiles = row.get("displayProfiles")
        expected_profile_order = [profile for profile in identity["profiles"]
                                  if profile in row_profiles]
        require(declared_profiles == expected_profile_order and expected_profile_order,
                f"{row_id} displayProfiles do not exactly match its evidence")
        row_languages = {PROFILE.fullmatch(profile).group(4) for profile in row_profiles}
        expected_language_order = [language for language in ("en_us", "nb_no")
                                   if language in row_languages]
        require(row.get("languages") == expected_language_order,
                f"{row_id} languages do not exactly match its evidence")

        required_profiles = set(expected.get("requiredNativeProfiles", []))
        for profile in required_profiles:
            for kind in required_kinds & PROFILE_BOUND_KINDS:
                require((kind, profile) in row_profile_kinds,
                        f"{row_id} lacks {kind} evidence for required profile {profile}")
        required_transitions = set(expected.get("requiredFrameTransitions", []))
        require(required_transitions <= row_transitions,
                f"{row_id} lacks frame transitions "
                f"{sorted(required_transitions - row_transitions)}")
        if expected["triggerMode"] == "restart":
            require(len(row_log_segment_indices["client_log"]) >= 2
                    and len(row_log_segment_indices["server_log"]) >= 2,
                    f"{row_id} does not bind both sides of a real integrated-client relaunch")
        interaction = expected.get("requiredClientInteraction")
        if isinstance(interaction, dict):
            require(len(row_client_acks) >= int(interaction["minAcks"]),
                    f"{row_id} lacks before/after native input acknowledgements")
            interaction_proven = False
            for first_index, first in enumerate(row_client_acks):
                first_profile = first.get("displayProfile")
                first_yaw = finite_number(first.get("yaw"), f"{row_id} yaw")
                first_pitch = finite_number(first.get("pitch"), f"{row_id} pitch")
                first_pos = player_position(first.get("playerPos"),
                                            f"{row_id} playerPos")
                if not boolean_value(first.get("grabbed"), f"{row_id} grabbed"):
                    continue
                for second in row_client_acks[first_index + 1:]:
                    if second.get("displayProfile") != first_profile \
                            or not boolean_value(second.get("grabbed"),
                                                 f"{row_id} grabbed"):
                        continue
                    second_yaw = finite_number(second.get("yaw"), f"{row_id} yaw")
                    second_pitch = finite_number(second.get("pitch"), f"{row_id} pitch")
                    second_pos = player_position(second.get("playerPos"),
                                                 f"{row_id} playerPos")
                    yaw_delta = abs((second_yaw - first_yaw + 180.0) % 360.0 - 180.0)
                    look_delta = math.hypot(yaw_delta, second_pitch - first_pitch)
                    movement_delta = math.sqrt(sum(
                        (right - left) ** 2
                        for left, right in zip(first_pos, second_pos)
                    ))
                    if (look_delta >= float(interaction["minLookDeltaDegrees"])
                            and movement_delta
                            >= float(interaction["minMovementDeltaBlocks"])):
                        interaction_proven = True
                        break
                if interaction_proven:
                    break
            require(interaction_proven,
                    f"{row_id} has no same-profile grabbed look-and-movement round trip")

    require(set(identity["profiles"]) <= native_profiles,
            "native ACK/frame evidence does not cover every required display profile")
    require(used_client_segment_paths == set(sealed_segments),
            "client-log evidence does not consume the exact sealed launch-segment roster")
    require_critical_raid_authority_exactly_once(
        [
            record
            for records in complete_authority_by_log.values()
            for record in records
        ],
        required_critical_authority,
        "sealed native log set",
    )
    require(frame_client_log_hashes <= client_log_hashes,
            "a frame report is not bound to a hashed client-log evidence file")
    for (label, client_hash, report_signatures,
         selected_windows) in frame_report_ack_signatures:
        require(client_hash in client_log_ack_signatures,
                f"{label} source client log was not parsed as native evidence")
        require(report_signatures == client_log_ack_signatures[client_hash],
                f"{label} acknowledgement roster is not the exact hashed client log")
        complete_records = [
            record
            for records in client_log_ack_records.values()
            for record in records
        ]
        for selected in selected_windows:
            transition = selected.get("transition", selected.get("uiTransition"))
            profile = profile_from_fields(selected, f"{label} selected window")
            relevant = [
                record for record in complete_records
                if record.get("transition") == transition
                and record.get("displayProfile") == profile
            ]
            require(relevant,
                    f"{label} selected window has no matching ACK in its hashed client log")
            latest = max(
                relevant,
                key=lambda record: (
                    int(record.get("segmentIndex", 0)), int(record["line"]),
                ),
            )
            require(
                ack_signature(selected, f"{label} selected window")
                == ack_signature(latest, f"{label} latest hashed-log window"),
                f"{label} selected {transition} window is not the newest matching ACK",
            )

    all_records = [
        record for records in client_log_ack_records.values() for record in records
    ]
    all_nonces = [str(record.get("nonce")) for record in all_records]
    require(len(all_nonces) == len(set(all_nonces)),
            "sealed native log segments repeat an acknowledgement nonce")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("run_directory", type=Path)
    parser.add_argument("--expected-source-fingerprint", required=True)
    parser.add_argument(
        "--expected-game-directory", type=Path,
        default=DEFAULT_EXPECTED_GAME_DIRECTORY,
        help="exact native CurseForge profile game directory",
    )
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument(
        "--input-seal", type=Path, required=True,
        help="operator HMAC seal ledger outside the mutable run directory",
    )
    parser.add_argument(
        "--operator-key-file", type=Path, required=True,
        help="separately held key that authenticates --input-seal",
    )
    parser.add_argument("--expected-operator-key-sha256", required=True)
    parser.add_argument("--expected-precommit-session-nonce", required=True)
    parser.add_argument("--expected-precommit-run-directory-token", required=True)
    parser.add_argument("--expected-precommit-jar-sha256", required=True)
    parser.add_argument("--expected-precommit-world-id", required=True)
    args = parser.parse_args()

    expected_fingerprint = require_sha(
        args.expected_source_fingerprint, "--expected-source-fingerprint"
    )
    expected_operator_key_sha256 = require_sha(
        args.expected_operator_key_sha256,
        "--expected-operator-key-sha256",
    )
    expected_precommit_jar_sha256 = require_sha(
        args.expected_precommit_jar_sha256,
        "--expected-precommit-jar-sha256",
    )
    expected_precommit_run_directory_token = require_sha(
        args.expected_precommit_run_directory_token,
        "--expected-precommit-run-directory-token",
    )
    require(re.fullmatch(r"[0-9a-f]{64}",
                         args.expected_precommit_session_nonce) is not None,
            "--expected-precommit-session-nonce is not 64 lowercase hex")
    require(re.fullmatch(r"[A-Za-z0-9_.-]{1,80}",
                         args.expected_precommit_world_id) is not None,
            "--expected-precommit-world-id is not a safe exact world id")
    expected_game_directory = require_plain_directory(
        args.expected_game_directory, "expected native game directory"
    )
    run_dir = require_plain_directory(args.run_directory, "run directory")
    matrix_path = require_plain_file(args.matrix, "matrix")
    canonical_matrix = require_plain_file(DEFAULT_MATRIX, "canonical matrix")
    matrix_hash = digest(canonical_matrix)
    require(digest(matrix_path) == matrix_hash,
            "supplied matrix differs from the canonical release matrix")
    matrix_rows, profiles = matrix_contract(load_object(matrix_path, "matrix"))

    manifest_path = require_plain_file(run_dir / "manifest.json", "manifest")
    result_path = require_plain_file(run_dir / "result.json", "result")
    reproduction_path = require_plain_file(run_dir / "reproduction.md", "reproduction")
    try:
        reproduction = reproduction_path.read_text(encoding="utf-8", errors="strict")
    except (OSError, UnicodeError) as exc:
        fail(f"reproduction is not UTF-8 text: {exc}")
    require(len(reproduction) >= 500,
            "reproduction.md is too short to contain the complete physical script")
    for token in (
        "HSQA_CLIENT_SESSION", "HSQA_INPUT_PRECOMMIT_V1",
        "native-launch-registry.jsonl", "Physical input", "Display profiles",
        "progressionCommandsUsed: []",
    ):
        require(token in reproduction, f"reproduction.md lacks required token {token!r}")
    require(
        f"runDirectoryToken={expected_precommit_run_directory_token}" in reproduction,
        "reproduction.md lacks the exact externally posted runDirectoryToken",
    )
    reproduction_hash = digest(reproduction_path)
    for name in REQUIRED_DIRECTORIES:
        require_plain_directory(run_dir / name, f"required {name} directory")

    manifest = load_object(manifest_path, "manifest")
    result = load_object(result_path, "result")
    native_session = require_session(
        manifest.get("nativeSessionId"), "manifest nativeSessionId"
    )
    operator_input_seal = select_operator_input_seal(
        args.input_seal, args.operator_key_file, run_dir, native_session,
        expected_operator_key_sha256,
    )
    identity = validate_manifest(
        manifest, expected_fingerprint, profiles, matrix_hash, reproduction_hash,
        expected_game_directory, run_dir, operator_input_seal,
        args.expected_precommit_session_nonce,
        expected_precommit_run_directory_token,
        expected_precommit_jar_sha256,
        args.expected_precommit_world_id,
    )
    validate_result(
        result, matrix_rows, run_dir, expected_fingerprint, matrix_hash, identity
    )
    print(
        f"release-client evidence: APPROVED rows={len(matrix_rows)} "
        f"jar_sha256={identity['jarHash']} source_fingerprint={expected_fingerprint}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
