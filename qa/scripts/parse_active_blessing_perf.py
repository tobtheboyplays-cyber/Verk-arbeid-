#!/usr/bin/env python3
"""Strict parser for the isolated active-Blessing structural matrix.

Requires the exact four one-test GameTest batches, one exact structural marker
per supported scale, an authoritative four-test success summary, and no
failure summary. Timing is recorded but never substitutes for these counters.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path


SCALES = (1, 25, 50, 100)
HOT_PASSES = 25
MAX_RAIDERS_PER_RUN = 9
EXPECTED_CASES = {
    scale: f"active_blessing_scale_{scale:03d}" for scale in SCALES
}
EXPECTED_BATCHES = {f"{case}:0" for case in EXPECTED_CASES.values()}
MARKER_TOKEN = "HSQA_ACTIVE_BLESSING_RESULT"
MARKER_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])HSQA_ACTIVE_BLESSING_RESULT(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
SUCCESS_SUMMARY_TOKEN_RE = re.compile(
    r"\brequired\s+tests\s+passed\b", re.IGNORECASE
)
FAILED_SUMMARY_TOKEN_RE = re.compile(
    r"\brequired\s+tests\s+failed\b", re.IGNORECASE
)
BATCH_TOKEN_RE = re.compile(
    r"\brunning\s+test\s+batch\b", re.IGNORECASE
)
HEARTHSTEAD_EMITTER = (
    r"(?:com\.hearthstead\.Hearthstead/|co\.he\.Hearthstead/)"
)
MARKER_RE = re.compile(
    r"^\[[^]]+\] \[Server thread/INFO\] "
    rf"\[{HEARTHSTEAD_EMITTER}\]: "
    r"HSQA_ACTIVE_BLESSING_RESULT "
    r"case=(?P<case>\S+) "
    r"scale=(?P<scale>\d+) "
    r"passes=(?P<passes>\d+) "
    r"groups=(?P<groups>\d+) "
    r"live_entities=(?P<live_entities>\d+) "
    r"records=(?P<records>\d+) "
    r"personal=(?P<personal>\d+) "
    r"building_zones=(?P<building_zones>\d+) "
    r"physical_zones=(?P<physical_zones>\d+) "
    r"buildings=(?P<buildings>\d+) "
    r"authorized=(?P<authorized>\d+) "
    r"unauthorized_rejected=(?P<unauthorized_rejected>\d+) "
    r"outgoing=(?P<outgoing>\d+) "
    r"incoming=(?P<incoming>\d+) "
    r"snared=(?P<snared>\d+) "
    r"stable_modifiers=(?P<stable_modifiers>\d+) "
    r"lookups=(?P<lookups>\d+) "
    r"rebuilds=(?P<rebuilds>\d+) "
    r"candidate_checks=(?P<candidate_checks>\d+) "
    r"entities_discarded=(?P<entities_discarded>\d+) "
    r"settlements_removed=(?P<settlements_removed>\d+) "
    r"duration_ns=(?P<duration_ns>\d+)$"
)
RUNNER = r"(?:minecraft/GameTestRunner|net\.minecraft\.gametest\.framework\.GameTestRunner/)"
SERVER = r"(?:minecraft/GameTestServer|net\.minecraft\.gametest\.framework\.GameTestServer/)"
BATCH_RE = re.compile(
    rf"^\[[^]]+\] \[Server thread/INFO\] \[{RUNNER}\]: "
    r"Running test batch '([^']+)' \((\d+) tests\)\.\.\.$"
)
SUMMARY_RE = re.compile(
    rf"^\[[^]]+\] \[Server thread/INFO\] \[{SERVER}\]: "
    r"All (\d+) required tests passed :\)$"
)
FAILED_SUMMARY_RE = re.compile(
    rf"^\[[^]]+\] \[Server thread/(?:INFO|ERROR)\] \[{SERVER}\]: "
    r"[1-9]\d* Required tests failed :\($"
)


@dataclass(frozen=True)
class ScaleResult:
    case: str
    scale: int
    passes: int
    groups: int
    live_entities: int
    records: int
    personal: int
    building_zones: int
    physical_zones: int
    buildings: int
    authorized: int
    unauthorized_rejected: int
    outgoing: int
    incoming: int
    snared: int
    stable_modifiers: int
    lookups: int
    rebuilds: int
    candidate_checks: int
    entities_discarded: int
    settlements_removed: int
    duration_ns: int
    evidence_line: int
    gate: str


@dataclass(frozen=True)
class MatrixResult:
    schema_version: int
    profile: str
    source_log: str
    expected_game_tests: int
    observed_pass_summaries: list[int]
    observed_batches: list[str]
    scales_expected: list[int]
    hot_passes: int
    overall: str
    scales: list[ScaleResult]
    errors: list[str]


def groups_for(scale: int) -> int:
    return (scale + MAX_RAIDERS_PER_RUN - 1) // MAX_RAIDERS_PER_RUN


def expected_fields(scale: int) -> dict[str, int]:
    groups = groups_for(scale)
    effects = scale * HOT_PASSES
    return {
        "passes": HOT_PASSES,
        "groups": groups,
        "live_entities": scale * 2,
        "records": scale,
        "personal": scale,
        "building_zones": scale,
        "physical_zones": groups,
        "buildings": groups,
        "authorized": scale,
        "unauthorized_rejected": 1,
        "outgoing": effects,
        "incoming": effects,
        "snared": effects,
        "stable_modifiers": scale * (HOT_PASSES - 1),
        "lookups": (1 + HOT_PASSES * 2) * scale,
        "rebuilds": groups,
        "candidate_checks": groups,
        "entities_discarded": scale * 2 + 1,
        "settlements_removed": groups,
    }


def parse_lines(lines: list[str], source: str) -> MatrixResult:
    errors: list[str] = []
    rows: dict[int, ScaleResult] = {}
    pass_summaries: list[int] = []
    batches: list[str] = []

    for line_number, line in enumerate(lines, 1):
        summary_match = SUMMARY_RE.fullmatch(line)
        if SUCCESS_SUMMARY_TOKEN_RE.search(line):
            if summary_match is None:
                errors.append(
                    f"line {line_number}: malformed GameTest success summary"
                )
            else:
                pass_summaries.append(int(summary_match.group(1)))

        failed_match = FAILED_SUMMARY_RE.fullmatch(line)
        if FAILED_SUMMARY_TOKEN_RE.search(line):
            if failed_match is None:
                errors.append(
                    f"line {line_number}: malformed GameTest failure summary"
                )
            else:
                errors.append(
                    f"line {line_number}: GameTest reported required failures"
                )

        batch_match = BATCH_RE.fullmatch(line)
        if BATCH_TOKEN_RE.search(line) and batch_match is None:
            errors.append(f"line {line_number}: malformed GameTest batch line")
        if batch_match:
            batch, test_count = batch_match.groups()
            batches.append(batch)
            if batch not in EXPECTED_BATCHES:
                errors.append(
                    f"line {line_number}: unexpected active GameTest batch {batch!r}"
                )
            if int(test_count) != 1:
                errors.append(
                    f"line {line_number}: batch {batch!r} contained "
                    f"{test_count} tests, expected 1"
                )
        if MARKER_TOKEN_RE.search(line) is None:
            continue
        match = MARKER_RE.fullmatch(line)
        if match is None:
            errors.append(f"line {line_number}: malformed {MARKER_TOKEN} marker")
            continue
        values = match.groupdict()
        case = values.pop("case")
        numeric = {key: int(value) for key, value in values.items()}
        scale = numeric.pop("scale")
        if scale not in EXPECTED_CASES:
            errors.append(f"line {line_number}: unsupported extra scale {scale}")
            continue
        if scale in rows:
            errors.append(f"line {line_number}: duplicate marker for scale {scale}")
            continue
        if case != EXPECTED_CASES[scale]:
            errors.append(
                f"line {line_number}: scale {scale} case was {case!r}, "
                f"expected {EXPECTED_CASES[scale]!r}"
            )
        for field, expected in expected_fields(scale).items():
            actual = numeric[field]
            if actual != expected:
                errors.append(
                    f"line {line_number}: scale {scale} {field} was "
                    f"{actual}, expected {expected}"
                )
        if numeric["duration_ns"] <= 0:
            errors.append(
                f"line {line_number}: scale {scale} duration_ns must be positive"
            )
        rows[scale] = ScaleResult(
            case=case,
            scale=scale,
            evidence_line=line_number,
            gate="PASS",
            **numeric,
        )

    for scale in SCALES:
        if scale not in rows:
            errors.append(
                f"missing exact marker for {EXPECTED_CASES[scale]} at scale {scale}"
            )
    if pass_summaries != [len(SCALES)]:
        errors.append(
            "GameTest success summary must occur exactly once with "
            f"{len(SCALES)} required tests; observed {pass_summaries}"
        )
    if len(batches) != len(EXPECTED_BATCHES) or set(batches) != EXPECTED_BATCHES:
        errors.append(
            "active GameTest batches must be exactly one each of "
            f"{sorted(EXPECTED_BATCHES)}; observed {batches}"
        )

    ordered = [rows[scale] for scale in SCALES if scale in rows]
    if errors:
        ordered = [
            ScaleResult(**{**asdict(row), "gate": "ERROR"}) for row in ordered
        ]
    return MatrixResult(
        schema_version=2,
        profile="active_blessing_structural_gametest",
        source_log=source,
        expected_game_tests=len(SCALES),
        observed_pass_summaries=pass_summaries,
        observed_batches=batches,
        scales_expected=list(SCALES),
        hot_passes=HOT_PASSES,
        overall="ERROR" if errors else "PASS",
        scales=ordered,
        errors=errors,
    )


def write_json(result: MatrixResult, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(asdict(result), indent=2, sort_keys=False) + "\n"
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=destination.parent, delete=False
    ) as handle:
        handle.write(payload)
        temporary = Path(handle.name)
    temporary.replace(destination)


def print_tsv(result: MatrixResult) -> None:
    print(
        "scale\tcase\tpasses\tgroups\tlive_entities\trecords\tpersonal\t"
        "building_zones\tphysical_zones\tbuildings\tauthorized\t"
        "unauthorized_rejected\toutgoing\tincoming\tsnared\tstable_modifiers\t"
        "lookups\trebuilds\tcandidate_checks\t"
        "entities_discarded\tsettlements_removed\tduration_ns\tgate"
    )
    for row in result.scales:
        print(
            "\t".join(
                str(value)
                for value in (
                    row.scale,
                    row.case,
                    row.passes,
                    row.groups,
                    row.live_entities,
                    row.records,
                    row.personal,
                    row.building_zones,
                    row.physical_zones,
                    row.buildings,
                    row.authorized,
                    row.unauthorized_rejected,
                    row.outgoing,
                    row.incoming,
                    row.snared,
                    row.stable_modifiers,
                    row.lookups,
                    row.rebuilds,
                    row.candidate_checks,
                    row.entities_discarded,
                    row.settlements_removed,
                    row.duration_ns,
                    row.gate,
                )
            )
        )


def marker(scale: int, **overrides: int | str) -> str:
    fields: dict[str, int | str] = {
        "case": EXPECTED_CASES[scale],
        "scale": scale,
        **expected_fields(scale),
        "duration_ns": 1000 + scale,
    }
    fields.update(overrides)
    order = (
        "case", "scale", "passes", "groups", "live_entities", "records",
        "personal", "building_zones", "physical_zones", "buildings",
        "authorized", "unauthorized_rejected", "outgoing", "incoming",
        "snared", "stable_modifiers", "lookups", "rebuilds",
        "candidate_checks", "entities_discarded", "settlements_removed",
        "duration_ns",
    )
    return (
        "[00:00:00] [Server thread/INFO] [com.hearthstead.Hearthstead/]: "
        + MARKER_TOKEN + " " + " ".join(
        f"{field}={fields[field]}" for field in order
        )
    )


def selftest() -> None:
    passing = [
        "[00:00:00] [Server thread/INFO] [minecraft/GameTestRunner]: "
        f"Running test batch '{EXPECTED_CASES[scale]}:0' (1 tests)..."
        for scale in SCALES
    ]
    passing.extend(marker(scale) for scale in SCALES)
    passing.append(
        "[00:00:01] [Server thread/INFO] [minecraft/GameTestServer]: "
        "All 4 required tests passed :)"
    )
    result = parse_lines(passing, "passing.log")
    assert result.overall == "PASS", result
    assert [row.scale for row in result.scales] == list(SCALES)

    # Log4j may render the same trusted Hearthstead logger using its compact
    # package form. Both observed real forms are explicit; arbitrary emitters
    # remain rejected by the full-line marker grammar.
    abbreviated = [
        line.replace(
            "[com.hearthstead.Hearthstead/]", "[co.he.Hearthstead/]"
        )
        for line in passing
    ]
    result = parse_lines(abbreviated, "abbreviated-emitter.log")
    assert result.overall == "PASS", result

    def assert_append_rejected(
        source: str, appended_line: str, error_fragment: str
    ) -> None:
        rejected = parse_lines(passing + [appended_line], source)
        assert rejected.overall == "ERROR", (source, rejected)
        assert any(error_fragment in error for error in rejected.errors), (
            source,
            rejected.errors,
        )

    # Detection is deliberately case-insensitive and token-aware, while each
    # accepted protocol line remains a strict, case-sensitive full match. A
    # valid matrix followed by any mixed-case, untrusted, trailing, duplicate,
    # or wrong-count protocol line must therefore become ERROR.
    protocol_families = (
        (
            "summary",
            passing[-1],
            "required tests passed",
            "ReQuIrEd TeStS PaSsEd",
            "success summary",
        ),
        (
            "batch",
            passing[0],
            "Running test batch",
            "rUnNiNg TeSt BaTcH",
            "GameTest batch",
        ),
        (
            "marker",
            marker(1),
            MARKER_TOKEN,
            "hsqa_Active_Blessing_Result",
            f"malformed {MARKER_TOKEN}",
        ),
    )
    for family, canonical, token, mixed_token, error_fragment in protocol_families:
        mixed = canonical.replace(token, mixed_token)
        assert_append_rejected(
            f"mixed-case-{family}.log", mixed, error_fragment
        )
        untrusted_upper = canonical.replace(token, token.upper()).replace(
            "[minecraft/GameTestServer]", "[co.example.Mod]"
        ).replace(
            "[minecraft/GameTestRunner]", "[co.example.Mod]"
        ).replace(
            "[com.hearthstead.Hearthstead/]", "[co.example.Mod/]"
        )
        assert_append_rejected(
            f"untrusted-uppercase-{family}.log",
            untrusted_upper,
            error_fragment,
        )
        assert_append_rejected(
            f"trailing-{family}-append.log",
            canonical + " injected",
            error_fragment,
        )
        duplicate_result = parse_lines(
            passing + [canonical], f"duplicate-{family}-append.log"
        )
        assert duplicate_result.overall == "ERROR", (
            family,
            duplicate_result,
        )

    assert_append_rejected(
        "wrong-count-summary-append.log",
        passing[-1].replace("All 4", "All 99"),
        "success summary must occur exactly once",
    )
    assert_append_rejected(
        "mixed-case-wrong-count-summary-append.log",
        passing[-1].replace(
            "All 4 required tests passed",
            "All 99 Required tests passed",
        ),
        "malformed GameTest success summary",
    )
    assert_append_rejected(
        "uppercase-wrong-count-summary-append.log",
        passing[-1].replace(
            "All 4 required tests passed",
            "ALL 99 REQUIRED TESTS PASSED",
        ),
        "malformed GameTest success summary",
    )
    canonical_failure = (
        "[00:00:02] [Server thread/ERROR] [minecraft/GameTestServer]: "
        "1 Required tests failed :("
    )
    assert_append_rejected(
        "canonical-failure-append.log",
        canonical_failure,
        "reported required failures",
    )
    assert_append_rejected(
        "mixed-case-failure-append.log",
        canonical_failure.replace(
            "Required tests failed", "rEqUiReD TeStS fAiLeD"
        ),
        "malformed GameTest failure summary",
    )
    assert_append_rejected(
        "untrusted-uppercase-failure-append.log",
        canonical_failure.replace(
            "[minecraft/GameTestServer]", "[co.example.Mod]"
        ).replace("Required tests failed", "REQUIRED TESTS FAILED"),
        "malformed GameTest failure summary",
    )
    assert_append_rejected(
        "say-marker-append.log",
        marker(1).replace(
            "[com.hearthstead.Hearthstead/]: ",
            "[minecraft/MinecraftServer]: [Not Secure] [Server] ",
        ),
        f"malformed {MARKER_TOKEN}",
    )

    wrong_marker_emitter = list(passing)
    marker_index = next(i for i, line in enumerate(wrong_marker_emitter)
                        if MARKER_TOKEN in line)
    wrong_marker_emitter[marker_index] = wrong_marker_emitter[
        marker_index
    ].replace("[com.hearthstead.Hearthstead/]", "[co.example.Mod/]")
    result = parse_lines(wrong_marker_emitter, "wrong-marker-emitter.log")
    assert result.overall == "ERROR", result
    assert any("malformed" in error for error in result.errors)

    missing = [line for line in passing if "scale=100 " not in line]
    result = parse_lines(missing, "missing.log")
    assert result.overall == "ERROR"
    assert any("scale 100" in error for error in result.errors)

    duplicate = passing[:-1] + [marker(25), passing[-1]]
    result = parse_lines(duplicate, "duplicate.log")
    assert result.overall == "ERROR"
    assert any("duplicate" in error for error in result.errors)

    wrong = [marker(scale, authorized=24) if scale == 25 else marker(scale)
             for scale in SCALES]
    wrong = passing[:4] + wrong + [passing[-1]]
    result = parse_lines(wrong, "wrong-count.log")
    assert result.overall == "ERROR"
    assert any("authorized was 24" in error for error in result.errors)

    result = parse_lines(passing[:-1] + [
        "[00:00:01] [Server thread/INFO] [minecraft/GameTestServer]: "
        "All 1 required tests passed"
    ],
                         "wrong-summary.log")
    assert result.overall == "ERROR"
    assert any("success summary" in error for error in result.errors)

    rogue_batch = list(passing)
    rogue_batch[0] = (
        "[00:00:00] [Server thread/INFO] [minecraft/GameTestRunner]: "
        "Running test batch 'rogue_active_case:0' (1 tests)..."
    )
    result = parse_lines(rogue_batch, "rogue-batch.log")
    assert result.overall == "ERROR"
    assert any("unexpected active GameTest batch" in error
               for error in result.errors)

    duplicate_batch = list(passing)
    duplicate_batch.insert(1, duplicate_batch[0])
    result = parse_lines(duplicate_batch, "duplicate-batch.log")
    assert result.overall == "ERROR"
    assert any("batches must be exactly" in error for error in result.errors)

    spoofed_summary = list(passing[:-1])
    spoofed_summary.append(
        "[00:00:01] [Server thread/INFO] [co.example.Mod]: embedded "
        "[minecraft/GameTestServer]: All 4 required tests passed"
    )
    result = parse_lines(spoofed_summary, "spoofed-summary.log")
    assert result.overall == "ERROR"
    assert any("success summary" in error for error in result.errors)

    trailing_marker = list(passing)
    marker_index = next(i for i, line in enumerate(trailing_marker)
                        if MARKER_TOKEN in line)
    trailing_marker[marker_index] += " injected=true"
    result = parse_lines(trailing_marker, "trailing-marker.log")
    assert result.overall == "ERROR"
    assert any("malformed" in error for error in result.errors)

    trailing_batch = list(passing)
    trailing_batch[0] += " injected"
    result = parse_lines(trailing_batch, "trailing-batch.log")
    assert result.overall == "ERROR"
    assert any("batches must be exactly" in error for error in result.errors)

    trailing_summary = list(passing)
    trailing_summary[-1] += " injected"
    result = parse_lines(trailing_summary, "trailing-summary.log")
    assert result.overall == "ERROR"
    assert any("success summary" in error for error in result.errors)

    appended_trailing_batch = list(passing) + [
        "[00:00:02] [Server thread/INFO] [minecraft/GameTestRunner]: "
        "Running test batch 'rogue:0' (1 tests)... injected"
    ]
    result = parse_lines(appended_trailing_batch, "appended-trailing-batch.log")
    assert result.overall == "ERROR", result
    assert any("malformed GameTest batch" in error for error in result.errors)

    appended_word_count_batch = list(passing) + [
        "[00:00:02] [Server thread/INFO] [minecraft/GameTestRunner]: "
        "Running test batch 'rogue:0' (one tests)..."
    ]
    result = parse_lines(appended_word_count_batch, "word-count-batch.log")
    assert result.overall == "ERROR", result
    assert any("malformed GameTest batch" in error for error in result.errors)

    appended_trailing_summary = list(passing) + [
        "[00:00:02] [Server thread/INFO] [minecraft/GameTestServer]: "
        "All 99 required tests passed :) injected"
    ]
    result = parse_lines(
        appended_trailing_summary, "appended-trailing-summary.log"
    )
    assert result.overall == "ERROR", result
    assert any("malformed GameTest success summary" in error
               for error in result.errors)

    appended_untrusted_summary = list(passing) + [
        "[00:00:02] [Server thread/INFO] [co.example.Mod]: embedded "
        "[minecraft/GameTestServer]: All 4 required tests passed :)"
    ]
    result = parse_lines(
        appended_untrusted_summary, "appended-untrusted-summary.log"
    )
    assert result.overall == "ERROR", result
    assert any("malformed GameTest success summary" in error
               for error in result.errors)

    with tempfile.TemporaryDirectory() as temp_dir:
        destination = Path(temp_dir) / "active.json"
        write_json(result, destination)
        loaded = json.loads(destination.read_text(encoding="utf-8"))
        assert loaded["overall"] == "ERROR"

    print("active Blessing performance parser selftest: PASS")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", nargs="?", type=Path)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)

    if args.selftest:
        selftest()
        return 0
    if args.log is None or args.json_out is None:
        parser.error("log and --json-out are required unless --selftest is used")
    try:
        lines = args.log.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        print(f"active Blessing parser: could not read {args.log}: {exc}",
              file=sys.stderr)
        return 1

    result = parse_lines(lines, str(args.log))
    write_json(result, args.json_out)
    print_tsv(result)
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    return 0 if result.overall == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
