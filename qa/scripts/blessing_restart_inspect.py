#!/usr/bin/env python3
"""Fail-closed reader for the Blessing restart probe's SavedData snapshots.

The project already owns a strict, stdlib-only NBT parser in
``hearthstead-neoforge/tools/gen_structures.py``.  Reusing it here keeps the
runtime gate independent of the mod's Java decoder without adding a network
dependency or a second hand-written NBT implementation.
"""

from __future__ import annotations

import argparse
import gzip
import importlib.util
import json
import sys
import uuid
from pathlib import Path
from typing import Any


TAG_BYTE = 1
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11


class InspectionError(RuntimeError):
    pass


def load_parser(repo: Path):
    source = repo / "hearthstead-neoforge" / "tools" / "gen_structures.py"
    spec = importlib.util.spec_from_file_location("hearthstead_nbt", source)
    if spec is None or spec.loader is None:
        raise InspectionError(f"cannot load NBT parser from {source}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.parse_nbt


def field(compound: dict[str, tuple[int, Any]], key: str, tag_type: int) -> Any:
    raw = compound.get(key)
    if raw is None:
        raise InspectionError(f"missing {key}")
    actual_type, value = raw
    if actual_type != tag_type:
        raise InspectionError(
            f"{key} has tag type {actual_type}, expected {tag_type}"
        )
    return value


def optional_field(
    compound: dict[str, tuple[int, Any]], key: str, tag_type: int
) -> Any | None:
    raw = compound.get(key)
    if raw is None:
        return None
    actual_type, value = raw
    if actual_type != tag_type:
        raise InspectionError(
            f"{key} has tag type {actual_type}, expected {tag_type}"
        )
    return value


def uuid_from_ints(values: list[int], label: str) -> str:
    if len(values) != 4:
        raise InspectionError(f"{label} UUID has {len(values)} ints, expected 4")
    number = 0
    for value in values:
        number = (number << 32) | (value & 0xFFFFFFFF)
    return str(uuid.UUID(int=number))


def list_compounds(
    compound: dict[str, tuple[int, Any]], key: str
) -> list[dict[str, tuple[int, Any]]]:
    elem_type, items = field(compound, key, TAG_LIST)
    if elem_type not in (0, TAG_COMPOUND):
        raise InspectionError(f"{key} list element type is {elem_type}, expected compound")
    if elem_type == 0 and items:
        raise InspectionError(f"{key} is non-empty but declares TAG_End elements")
    return items


def locate_saved_data(root: dict[str, tuple[int, Any]]) -> dict[str, tuple[int, Any]]:
    """Find the compound owned by SettlementSavedData.

    Minecraft currently wraps SavedData under ``data``.  Recursive discovery
    keeps the reader explicit about the one semantic anchor (Settlements) while
    tolerating a vanilla wrapper change.  Ambiguity is a hard failure.
    """

    matches: list[dict[str, tuple[int, Any]]] = []

    def visit(compound: dict[str, tuple[int, Any]]) -> None:
        if "Settlements" in compound:
            matches.append(compound)
        for tag_type, value in compound.values():
            if tag_type == TAG_COMPOUND:
                visit(value)

    visit(root)
    if len(matches) != 1:
        raise InspectionError(
            f"found {len(matches)} compounds containing Settlements, expected exactly 1"
        )
    return matches[0]


def block_pos(compound: dict[str, tuple[int, Any]], key: str) -> tuple[int, int, int]:
    """Read Minecraft 1.21's strict NbtUtils.writeBlockPos representation."""

    values = field(compound, key, TAG_INT_ARRAY)
    if len(values) != 3:
        raise InspectionError(
            f"{key} block position has {len(values)} ints, expected exactly 3"
        )
    return tuple(values)


def inspect(
    repo: Path,
    source: Path,
    plaque: tuple[int, int, int],
    expected_settlement: str | None,
    expected_building: str | None,
) -> dict[str, Any]:
    if not source.is_file() or source.stat().st_size <= 0:
        raise InspectionError(f"SavedData file missing or empty: {source}")
    try:
        raw = gzip.decompress(source.read_bytes())
    except (OSError, EOFError) as exc:
        raise InspectionError(f"SavedData is not valid gzip: {exc}") from exc

    parse_nbt = load_parser(repo)
    _root_name, root = parse_nbt(raw)
    data = locate_saved_data(root)
    data_version = field(data, "DataVersion", TAG_INT)
    if data_version != 4:
        raise InspectionError(f"settlement DataVersion is {data_version}, expected 4")

    settlements = list_compounds(data, "Settlements")
    if len(settlements) != 1:
        raise InspectionError(
            f"world contains {len(settlements)} settlements, expected exactly 1"
        )
    settlement = settlements[0]
    settlement_id = uuid_from_ints(
        field(settlement, "Id", TAG_INT_ARRAY), "settlement"
    )
    if expected_settlement and settlement_id != expected_settlement:
        raise InspectionError(
            f"settlement id changed: {settlement_id} != {expected_settlement}"
        )

    buildings = list_compounds(settlement, "Buildings")
    matches = [
        building
        for building in buildings
        if block_pos(building, "Plaque") == plaque
    ]
    if len(matches) != 1:
        raise InspectionError(
            f"found {len(matches)} buildings at plaque {plaque}, expected exactly 1"
        )
    if len(buildings) != 1:
        raise InspectionError(
            f"settlement contains {len(buildings)} buildings, expected exactly 1"
        )
    building = matches[0]
    building_id = uuid_from_ints(field(building, "Id", TAG_INT_ARRAY), "building")
    if expected_building and building_id != expected_building:
        raise InspectionError(
            f"building id changed: {building_id} != {expected_building}"
        )
    building_type = field(building, "Type", TAG_STRING)
    valid = field(building, "Valid", TAG_BYTE)
    if building_type != "house" or valid != 1:
        raise InspectionError(
            f"building is not the registered valid house: type={building_type} valid={valid}"
        )

    blessings = field(building, "TargetBlessings", TAG_COMPOUND)
    blessing_version = field(blessings, "DataVersion", TAG_INT)
    quarantined = field(blessings, "Quarantined", TAG_BYTE)
    ranks = list_compounds(blessings, "Ranks")
    if blessing_version != 1 or quarantined != 0:
        raise InspectionError(
            "building blessing ledger is not current and active: "
            f"version={blessing_version} quarantined={quarantined}"
        )
    if len(ranks) != 1:
        raise InspectionError(f"building has {len(ranks)} blessing ranks, expected 1")
    rank = ranks[0]
    normalized_rank = {
        "wire_id": field(rank, "WireId", TAG_INT),
        "id": field(rank, "Id", TAG_STRING),
        "rank": field(rank, "Rank", TAG_INT),
    }
    if normalized_rank != {"wire_id": 2, "id": "thorned_roads", "rank": 3}:
        raise InspectionError(
            f"building blessing rank differs from exact Thorned Roads III: {normalized_rank}"
        )

    settlement_blessing = field(settlement, "BlessingState", TAG_COMPOUND)
    settlement_blessing_version = field(settlement_blessing, "DataVersion", TAG_INT)
    settlement_quarantined = field(settlement_blessing, "Quarantined", TAG_BYTE)
    if settlement_blessing_version != 2 or settlement_quarantined != 0:
        raise InspectionError(
            "settlement blessing ledger became invalid: "
            f"version={settlement_blessing_version} "
            f"quarantined={settlement_quarantined}"
        )

    founding_journey = field(settlement, "FoundingJourney", TAG_COMPOUND)
    normalized_journey = {
        "data_version": field(founding_journey, "DataVersion", TAG_INT),
        "phase_wire_id": field(founding_journey, "PhaseWireId", TAG_INT),
        "phase": field(founding_journey, "Phase", TAG_STRING),
        "revision": field(founding_journey, "Revision", TAG_INT),
    }
    expected_journey = {
        "data_version": 1,
        "phase_wire_id": 0,
        "phase": "build_lumber_camp",
        "revision": 0,
    }
    if normalized_journey != expected_journey:
        raise InspectionError(
            "real founding journey did not survive at its exact first task: "
            f"{normalized_journey}"
        )

    return {
        "saved_data_version": data_version,
        "settlement_id": settlement_id,
        "settlement_count": len(settlements),
        "settlement_blessing_version": settlement_blessing_version,
        "settlement_blessing_quarantined": bool(settlement_quarantined),
        "founding_journey": normalized_journey,
        "building_id": building_id,
        "building_count": len(buildings),
        "building_type": building_type,
        "building_valid": bool(valid),
        "plaque": list(plaque),
        "building_target_blessings": {
            "data_version": blessing_version,
            "quarantined": bool(quarantined),
            "ranks": [normalized_rank],
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("saved_data", type=Path)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--plaque", nargs=3, type=int, required=True)
    parser.add_argument("--expected-settlement")
    parser.add_argument("--expected-building")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    try:
        result = inspect(
            args.repo.resolve(),
            args.saved_data.resolve(),
            tuple(args.plaque),
            args.expected_settlement,
            args.expected_building,
        )
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(
            "SavedData exact: "
            f"settlement={result['settlement_id']} "
            f"building={result['building_id']} "
            "plaque=522,-58,519 thorned_roads=III quarantine=false"
        )
        return 0
    except (InspectionError, ValueError, KeyError, TypeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
