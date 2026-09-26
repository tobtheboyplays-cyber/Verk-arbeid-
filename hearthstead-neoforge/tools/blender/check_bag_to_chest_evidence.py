"""Deterministically validate a durable bag-to-chest Blender evidence package.

This checker deliberately validates the package structure, source binding,
transition coverage and numeric gates. It does not claim a Blender render is
native Minecraft proof.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
CONTRACT = HERE / "BAG_TO_CHEST_STATE_CONTRACT.md"
REQUIRED_VIEWS = ("front34", "left", "right", "back34")
REQUIRED_TRANSITION_TICKS = (
    11, 12, 13,       # bag becomes world-owned
    29, 30, 31, 32,   # bag -> worker and post-transfer item read
    35, 36, 37,       # lid opens
    47, 48, 49,       # worker -> chest
    63, 64, 65,       # lid closes
)
BBMODEL_NAME = "hearthstead_bag_to_chest_evidence.bbmodel"
REQUIRED_CHECKS = (
    "palmContactsPass", "setDownForwardLeanPass", "forwardLeanPass",
    "worldBagAnchorPass", "targetFacingPass", "withdrawalClearancePass",
    "itemTransformContinuityPass", "meshClearancePass", "silhouetteReadPass",
    "blockbenchEvidencePass", "runtimeChannelParityPass", "candidateOnly",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def current_candidate_hash() -> str:
    digest = hashlib.sha256()
    for path in (HERE / "author_bag_to_chest.py", CONTRACT,
                 HERE / "make_bag_contact_sheets.py"):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()[:16]


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path,
                        help="Exact durable evidence directory. Defaults to current source hash.")
    args = parser.parse_args()
    candidate_hash = current_candidate_hash()
    root = args.candidate or (PROJECT / "qa" / "evidence" / "blender" /
                              "bag_to_chest_v1" / candidate_hash)
    manifest_path = root / "manifest.json"
    report_path = root / "contact_report.json"
    errors: list[str] = []
    if not manifest_path.is_file():
        fail(errors, f"missing manifest: {manifest_path}")
    if not report_path.is_file():
        fail(errors, f"missing report: {report_path}")
    if errors:
        print("BAG_TO_CHEST_EVIDENCE: FAIL")
        print("\n".join(errors))
        return 1

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if manifest.get("candidateHash") != candidate_hash:
        fail(errors, "manifest candidate hash does not match current source/contract")
    if report.get("candidateHash") != manifest.get("candidateHash"):
        fail(errors, "report candidate hash does not match manifest")
    if report.get("status", "").lower().find("candidate") == -1:
        fail(errors, "report must remain Candidate-only evidence")
    if set(manifest.get("reviewViews", ())) != set(REQUIRED_VIEWS):
        fail(errors, "review views must be exactly front34/left/right/back34")

    review_ticks = set(manifest.get("reviewTicks", ()))
    for tick in REQUIRED_TRANSITION_TICKS:
        if tick not in review_ticks:
            fail(errors, f"missing transition tick in manifest: {tick}")
        for view in REQUIRED_VIEWS:
            image = root / f"{view}_tick{tick:02d}.png"
            if not image.is_file():
                fail(errors, f"missing transition render: {image.name}")

    checks = report.get("checks", {})
    for check in REQUIRED_CHECKS:
        if checks.get(check) is not True:
            fail(errors, f"report check failed or missing: {check}")
    setdown = report.get("setDownForwardLeanDegrees")
    minimum = report.get("limits", {}).get("minForwardLeanDegrees")
    if not isinstance(setdown, (int, float)) or not isinstance(minimum, (int, float)):
        fail(errors, "set-down forward-lean measurement is missing")
    elif setdown < minimum:
        fail(errors, f"set-down lean {setdown} is below minimum {minimum}")

    withdrawal = report.get("withdrawalClearance", {})
    steps = withdrawal.get("steps", [])
    max_withdrawal = report.get("limits", {}).get("maxWithdrawalStep")
    if len(steps) < 6 or not isinstance(max_withdrawal, (int, float)):
        fail(errors, "withdrawal clearance samples or limit are incomplete")
    else:
        expected_pairs = {(30, 31), (31, 32), (32, 33), (33, 34), (34, 35), (35, 36)}
        actual_pairs = {(row.get("fromTick"), row.get("toTick")) for row in steps}
        if not expected_pairs.issubset(actual_pairs):
            fail(errors, "withdrawal clearance is missing one or more 30-36 boundary steps")
        for row in steps:
            if not isinstance(row.get("positionStep"), (int, float)) or \
                    row["positionStep"] > max_withdrawal:
                fail(errors, f"withdrawal step exceeds limit: {row}")

    continuity = report.get("itemTransformContinuity", {})
    for name in ("bagToHandAt30", "handToChestAt48", "approach47ToDeposit48"):
        row = continuity.get(name)
        if not isinstance(row, dict):
            fail(errors, f"missing item transform continuity row: {name}")
            continue
        for key in ("position", "rotationDegrees", "scale"):
            if not isinstance(row.get(key), (int, float)):
                fail(errors, f"missing transform metric {name}.{key}")
    for name in ("bagToHandAt30", "handToChestAt48"):
        row = continuity.get(name, {})
        if row.get("position", 1.0) > 0.0001 or row.get("rotationDegrees", 1.0) > 0.001 or \
                row.get("scale", 1.0) > 0.0001:
            fail(errors, f"ownership transform discontinuity at {name}: {row}")
    approach = continuity.get("approach47ToDeposit48", {})
    approach_limit = report.get("limits", {}).get("maxOwnershipPositionStep")
    if not isinstance(approach_limit, (int, float)) or \
            approach.get("position", float("inf")) > approach_limit or \
            approach.get("rotationDegrees", float("inf")) > 0.001 or \
            approach.get("scale", float("inf")) > 0.0001:
        fail(errors, f"47-48 approach discontinuity: {approach}")

    mesh_clearance = report.get("meshClearance", [])
    if not mesh_clearance:
        fail(errors, "mesh clearance samples are missing")
    for row in mesh_clearance:
        for test in row.get("tests", []):
            if test.get("intersects") is True:
                fail(errors, f"mesh collision at tick {row.get('tick')}: {test.get('target')}")
    silhouettes = report.get("silhouetteRead", [])
    if len(silhouettes) < 6:
        fail(errors, "insufficient log silhouette samples")
    for row in silhouettes:
        bark_meshes = row.get("barkBodyMeshes", 0) + row.get("barkRidgeMeshes", 0)
        if row.get("xLongAxis", 0.0) < 0.45 or bark_meshes != 3 or \
                row.get("endGrainMeshes") != 2 or row.get("minChestMeshGap", -1.0) < 0.01:
            fail(errors, f"unreadable or merged log silhouette: {row}")

    source_hashes = manifest.get("sourceSha256", {})
    expected_sources = {
        "author_bag_to_chest.py": sha256(HERE / "author_bag_to_chest.py"),
        CONTRACT.name: sha256(CONTRACT),
    }
    for name, digest in expected_sources.items():
        if source_hashes.get(name) != digest:
            fail(errors, f"source hash mismatch: {name}")
    for name in (CONTRACT.name, "author_bag_to_chest.py", "hearthstead_bag_to_chest_v1.blend",
                 BBMODEL_NAME):
        preserved = root / name
        if not preserved.is_file():
            fail(errors, f"candidate does not preserve {name}")
        elif name in source_hashes and sha256(preserved) != source_hashes.get(name):
            fail(errors, f"preserved source does not match manifest: {name}")

    bbmodel_path = root / BBMODEL_NAME
    if bbmodel_path.is_file():
        try:
            bbmodel = json.loads(bbmodel_path.read_text(encoding="utf-8"))
            if bbmodel.get("hearthsteadEvidence", {}).get("candidateHash") != candidate_hash:
                fail(errors, "evidence-only bbmodel is not tied to candidate hash")
        except json.JSONDecodeError:
            fail(errors, "evidence-only bbmodel is not valid JSON")

    for name, digest in manifest.get("evidenceSha256", {}).items():
        file = root / name
        if not file.is_file():
            fail(errors, f"manifest file missing: {name}")
        elif sha256(file) != digest:
            fail(errors, f"manifest hash mismatch: {name}")

    if errors:
        print("BAG_TO_CHEST_EVIDENCE: FAIL")
        print("\n".join(errors))
        return 1
    print("BAG_TO_CHEST_EVIDENCE: PASS")
    print(f"CANDIDATE_HASH={candidate_hash}")
    print(f"EVIDENCE_ROOT={root}")
    print(f"RENDER_COUNT={len(list(root.glob('*.png')))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
