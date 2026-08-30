#!/usr/bin/env python3
"""Render and fail-closed validate the frozen Guard MELEE v4 Candidate.

This is offline Blockbench evidence only. It deliberately cannot approve the
animation: native Minecraft inspection remains a separate release gate.
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile

from PIL import Image, ImageDraw


MOD_ROOT = Path(__file__).resolve().parents[1]
RENDERER = MOD_ROOT / "tools" / "blockbench" / "bb_render.mjs"
MODEL = MOD_ROOT / "tools" / "blockbench" / "settler.bbmodel"
PROP_CONTRACT = MOD_ROOT / "tools" / "blockbench" / "prop_contract.json"
DEFAULT_OUTPUT = (MOD_ROOT / "qa" / "reports" / "previews"
                  / "melee-recovery-candidate-v4-20260828" / "final")
TIMES = (0.00, 0.05, 0.10, 0.15, 0.20, 0.25,
         0.30, 0.35, 0.40, 0.45, 0.50)
LABEL = "MELEE V4 CANDIDATE - NATIVE PENDING"


def scenario(key, kind, phase, view, primary=False):
    base = "GUARD_STANCE" if kind == "stance" else "WALK"
    state = (f"STATIONARY GUARD | {base} {phase:.2f} + MELEE | FORWARD TARGET"
             if kind == "stance" else
             f"MOVING COMBAT | WALK {phase:.2f} + GUARD_PATROL 0.00 + MELEE | "
             "FORWARD TARGET")
    return {
        "key": key,
        "kind": kind,
        "base": base,
        "phase": phase,
        "view": view,
        "primary": primary,
        "state": state,
    }


SCENARIOS = [
    scenario("stance-p000-front34", "stance", 0.00, "front34", True),
    scenario("stance-p180-front34", "stance", 1.80, "front34", True),
    scenario("stance-p260-front34", "stance", 2.60, "front34", True),
    scenario("stance-p310-front34", "stance", 3.10, "front34", True),
    scenario("stance-p360-front34", "stance", 3.60, "front34", True),
    scenario("stance-p000-left", "stance", 0.00, "left"),
    scenario("stance-p000-right", "stance", 0.00, "right"),
    scenario("stance-p000-oblique-left", "stance", 0.00, "oblique_left"),
    scenario("moving-p000-front34", "moving", 0.00, "front34", True),
    scenario("moving-p025-front34", "moving", 0.25, "front34", True),
    scenario("moving-p050-front34", "moving", 0.50, "front34", True),
    scenario("moving-p075-front34", "moving", 0.75, "front34", True),
    scenario("moving-p000-left", "moving", 0.00, "left"),
    scenario("moving-p000-right", "moving", 0.00, "right"),
    scenario("moving-p000-oblique-left", "moving", 0.00, "oblique_left"),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def frame_time(path: Path) -> float:
    match = re.search(r"-t(\d+(?:_\d+)?)\.png$", path.name)
    if not match:
        raise ValueError(f"cannot parse frame time from {path.name}")
    return float(match.group(1).replace("_", "."))


def frame_map(directory: Path):
    frames = {}
    for path in directory.glob("*.png"):
        if path.name == "timeline.png":
            continue
        at = round(frame_time(path), 2)
        if at in frames:
            raise RuntimeError(f"duplicate {at:.2f}s frame in {directory}")
        frames[at] = path
    expected = {round(value, 2) for value in TIMES}
    if set(frames) != expected:
        raise RuntimeError(f"{directory.name}: frame times {sorted(frames)} != "
                           f"{sorted(expected)}")
    return frames


def fit_image(source: Image.Image, size, background=(22, 24, 28)):
    result = Image.new("RGB", size, background)
    image = source.convert("RGB").copy()
    image.thumbnail(size, Image.Resampling.LANCZOS)
    left = (size[0] - image.width) // 2
    top = (size[1] - image.height) // 2
    result.paste(image, (left, top))
    return result


def labelled_grid(cells, output: Path, columns: int, cell_size,
                  title: str, title_height=58):
    rows = math.ceil(len(cells) / columns)
    canvas = Image.new("RGB", (columns * cell_size[0],
                               title_height + rows * cell_size[1]),
                       (13, 15, 19))
    draw = ImageDraw.Draw(canvas)
    draw.text((14, 12), title, fill=(238, 222, 177))
    draw.text((14, 32), "Candidate only - native in-game validation pending",
              fill=(255, 163, 96))
    for index, (path, label) in enumerate(cells):
        source = Image.open(path)
        fitted = fit_image(source, (cell_size[0], cell_size[1] - 22))
        x = index % columns * cell_size[0]
        y = title_height + index // columns * cell_size[1]
        canvas.paste(fitted, (x, y))
        draw.rectangle((x, y + cell_size[1] - 22,
                        x + cell_size[0] - 1, y + cell_size[1] - 1),
                       fill=(25, 29, 35))
        draw.text((x + 6, y + cell_size[1] - 18), label,
                  fill=(224, 228, 233))
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output, optimize=False)


def make_timeline(scenario_dir: Path, spec):
    frames = frame_map(scenario_dir)
    cells = [(frames[round(at, 2)], f"t={at:.2f}s") for at in TIMES]
    labelled_grid(cells, scenario_dir / "timeline.png", columns=4,
                  cell_size=(350, 272),
                  title=f"{spec['key']} | {spec['state']} | view={spec['view']}")


def run_renderer(output: Path, spec):
    scenario_dir = output / spec["key"]
    scenario_dir.mkdir(parents=True, exist_ok=False)
    environment = os.environ.copy()
    for name in tuple(environment):
        if name.startswith("BB_"):
            environment.pop(name)
    environment.update({
        "BB_MODEL": str(MODEL),
        "BB_VIEW": spec["view"],
        "BB_BASE_PHASE": str(spec["phase"]),
        "BB_LIMB_SWING_AMOUNT": "1",
        # Capture the actual Blockbench preview host. Full-page screenshots
        # include a volatile bottom status/FPS field even when the model,
        # camera and every geometry sample are bit-identical.
        "BB_CAPTURE": "preview",
        "BB_EVIDENCE_LABEL": LABEL,
        "BB_STATE": spec["state"],
    })
    if spec["kind"] == "moving":
        environment["BB_MARTIAL_BASE"] = "GUARD_PATROL"
        environment["BB_MARTIAL_PHASE"] = "0"
    command = [
        "node", str(RENDERER), str(scenario_dir), "--composite",
        spec["base"], "MELEE", *(f"{at:.2f}" for at in TIMES),
    ]
    result = subprocess.run(command, cwd=MOD_ROOT, env=environment,
                            text=True, capture_output=True, timeout=60)
    if result.returncode != 0:
        raise RuntimeError(
            f"renderer rejected {spec['key']}\n{result.stdout}\n{result.stderr}")
    reports = list(scenario_dir.glob("*-contact.json"))
    if len(reports) != 1:
        raise RuntimeError(f"{spec['key']}: expected one contact report, "
                           f"found {len(reports)}")
    make_timeline(scenario_dir, spec)
    return reports[0]


def load_report(path: Path):
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def validate_report(spec, report, contract):
    errors = []
    if report.get("schema") != 4:
        errors.append("schema is not 4")
    if report.get("status") != "Candidate - native in-game validation pending":
        errors.append("status claims more than Candidate")
    if report.get("targetContract") != contract:
        errors.append("target contract drift")
    if report.get("state") != spec["state"] or report.get("view") != spec["view"]:
        errors.append("state/view label drift")
    records = report.get("records", [])
    if len(records) != len(TIMES):
        errors.append(f"expected 11 records, found {len(records)}")
        return errors
    by_time = {round(record["time"], 2): record for record in records}
    if set(by_time) != {round(at, 2) for at in TIMES}:
        errors.append("record times drift")
        return errors

    for at in contract["mustBeClearSeconds"]:
        record = by_time[round(at, 2)]
        if record["orientedIntersections"] != 0 \
                or record["centerline"]["travelModelPixels"] > 1e-6 \
                or record["physicalContact"]:
            errors.append(f"pre-contact t={at:.2f} is not clear")
    for at in contract["impactBandSeconds"]:
        record = by_time[round(at, 2)]
        if record["orientedIntersections"] <= 0 \
                or record["centerline"]["travelModelPixels"] \
                < contract["minimumCenterlineTravelPixels"] - 1e-6 \
                or not record["physicalContact"]:
            errors.append(f"impact t={at:.2f} lacks oriented centreline contact")
    for at in contract["mustBeSeparatedSeconds"]:
        record = by_time[round(at, 2)]
        if record["orientedIntersections"] != 0 \
                or record["centerline"]["travelModelPixels"] > 1e-6 \
                or record["physicalContact"]:
            errors.append(f"post-contact t={at:.2f} is not separated")
    if report.get("impactDriftModelPixels", float("inf")) \
            > contract["maximumImpactLineDriftPixels"] + 1e-6:
        errors.append("within-phase impact midpoint drift exceeds contract")
    for record in records:
        look = record.get("runtimeLook", {})
        if abs(look.get("relativeTargetYawDegrees", float("inf"))) > 1e-6 \
                or abs(look.get("netHeadYawDegrees", float("inf"))) > 1e-6 \
                or abs(look.get("headPitchDegrees", float("inf"))) > 1e-6:
            errors.append(f"t={record['time']:.2f} runtime gaze is off target")
    return errors


def maximum_pairwise_distance(points):
    return max((math.dist(first, second)
                for first, second in itertools.combinations(points, 2)),
               default=0.0)


def make_matrices(output: Path, reports):
    primary = [(spec, report) for spec, report in reports if spec["primary"]]
    contact_cells = []
    impact_cells = []
    separation_cells = []
    for spec, _ in primary:
        frames = frame_map(output / spec["key"])
        contact_cells.append((frames[0.20], spec["key"]))
        for at in (0.20, 0.25):
            impact_cells.append((frames[at], f"{spec['key']} t={at:.2f}"))
        for at in (0.15, 0.20, 0.30):
            separation_cells.append((frames[at], f"{spec['key']} t={at:.2f}"))
    labelled_grid(contact_cells, output / "contact-phase-matrix.png", 3,
                  (420, 322), "Primary phases | exact server contact T+4 / t=.20")
    labelled_grid(impact_cells, output / "impact-band-matrix.png", 6,
                  (310, 242), "Primary phases | narrow oriented impact band .20-.25")
    labelled_grid(separation_cells, output / "clear-separation-matrix.png", 9,
                  (230, 182), "Primary phases | pre .15 / impact .20 / post .30")
    timeline_cells = [
        (output / spec["key"] / "timeline.png", spec["key"])
        for spec in SCENARIOS
    ]
    labelled_grid(timeline_cells, output / "all-timelines-contact-sheet.png", 3,
                  (530, 356), "All 15 MELEE v4 Candidate timelines | 165 frames")


def render_package(output: Path):
    output.mkdir(parents=True, exist_ok=False)
    with PROP_CONTRACT.open(encoding="utf-8") as source:
        contract = json.load(source)["meleeEvidenceTarget"]
    report_pairs = []
    for index, spec in enumerate(SCENARIOS, 1):
        report_path = run_renderer(output, spec)
        report = load_report(report_path)
        errors = validate_report(spec, report, contract)
        if errors:
            raise RuntimeError(f"{spec['key']} rejected: {'; '.join(errors)}")
        report_pairs.append((spec, report))
        print(f"PASS {index:02d}/{len(SCENARIOS)} {spec['key']}", flush=True)

    primary = [(spec, report) for spec, report in report_pairs if spec["primary"]]
    cross_phase_drift = {}
    for at in contract["impactBandSeconds"]:
        points = []
        for _, report in primary:
            record = next(record for record in report["records"]
                          if abs(record["time"] - at) <= 1e-6)
            midpoint = record["centerline"]["midpoint"]
            if midpoint is None:
                raise RuntimeError(f"primary impact t={at:.2f} lacks midpoint")
            points.append(midpoint)
        drift = maximum_pairwise_distance(points)
        cross_phase_drift[f"{at:.2f}"] = drift
        if drift > contract["maximumImpactLineDriftPixels"] + 1e-6:
            raise RuntimeError(
                f"cross-phase impact midpoint drift {drift:.3f}px at t={at:.2f} "
                f"exceeds {contract['maximumImpactLineDriftPixels']:.3f}px")

    make_matrices(output, report_pairs)
    index = {
        "schema": 1,
        "status": "Candidate - native in-game validation pending",
        "timelineCount": len(SCENARIOS),
        "frameCount": len(SCENARIOS) * len(TIMES),
        "primaryPhaseCount": len(primary),
        "crossPhaseImpactMidpointDriftPixels": cross_phase_drift,
        "scenarios": SCENARIOS,
    }
    with (output / "scenario-index.json").open("w", encoding="utf-8",
                                                newline="\n") as target:
        json.dump(index, target, indent=2, sort_keys=True)
        target.write("\n")
    return index


def artifact_hashes(output: Path):
    return {
        path.relative_to(output).as_posix(): sha256(path)
        for path in sorted(output.rglob("*")) if path.is_file()
    }


def verify_determinism(reference: Path):
    with tempfile.TemporaryDirectory(prefix="hearthstead-melee-v4-") as temp:
        rerender = Path(temp) / "final"
        render_package(rerender)
        expected = artifact_hashes(reference)
        actual = artifact_hashes(rerender)
        if expected != actual:
            missing = sorted(set(expected) - set(actual))
            extra = sorted(set(actual) - set(expected))
            changed = sorted(name for name in set(expected) & set(actual)
                             if expected[name] != actual[name])
            raise RuntimeError("determinism rerender mismatch: "
                               f"missing={missing}, extra={extra}, "
                               f"changed={changed}")
        return len(expected)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--verify-determinism", action="store_true")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise SystemExit(f"refusing to overwrite existing evidence: {output}")
    index = render_package(output)
    deterministic_files = 0
    if args.verify_determinism:
        deterministic_files = verify_determinism(output)
    hashes = artifact_hashes(output)
    manifest = {
        **index,
        "determinism": {
            "rerendered": args.verify_determinism,
            "exactMatchingArtifactCount": deterministic_files,
        },
        "inputs": {
            "settlerBbmodelSha256": sha256(MODEL),
            "propContractSha256": sha256(PROP_CONTRACT),
            "rendererSha256": sha256(RENDERER),
        },
        "artifacts": hashes,
    }
    manifest_path = output.parent / "evidence-manifest.json"
    with manifest_path.open("w", encoding="utf-8", newline="\n") as target:
        json.dump(manifest, target, indent=2, sort_keys=True)
        target.write("\n")
    print(f"PASS complete: {index['timelineCount']} timelines / "
          f"{index['frameCount']} frames")
    print(f"manifest {manifest_path}")


if __name__ == "__main__":
    main()
