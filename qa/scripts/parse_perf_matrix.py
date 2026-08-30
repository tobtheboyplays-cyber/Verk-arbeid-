#!/usr/bin/env python3
"""Parse Hearthstead's bounded 1/25/50/100 dedicated-server MSPT matrix.

The probe writes fixed, condition-gated markers around vanilla ``/tick query``
output. This parser deliberately does not infer populations from selectors or
from how many summon commands were sent. It requires exact dimension transit
and return proofs for every growth batch, one authoritative ``hearthstead info``
UUID-record population per scale, and the two real scoreboard read-backs before
the scale's conditional OK marker. Thus tagged entity shells cannot stand in
for the SettlementManager workload the MSPT windows claim to measure.

Exit codes:
  0  structurally complete matrix and every absolute average-MSPT gate passed
  1  malformed/incomplete evidence
  2  complete evidence, but one or more absolute MSPT gates failed
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


SCALES = (1, 25, 50, 100)
# Scale 25 preserves the old ~27-settler 45 ms absolute ceiling.  Scale 50
# must meet the same ceiling; 100 gets the explicitly approved 50 ms stress
# ceiling.  These are intentionally code constants, not environment knobs:
# longer sampling may be configured, but the gate cannot be weakened at run
# time.
AVERAGE_BUDGET_MS = {25: 45.0, 50: 45.0, 100: 50.0}

SERVER_EMITTER = (
    r"(?:minecraft/MinecraftServer|net\.minecraft\.server\.MinecraftServer/)"
)
SERVER_PREFIX = (
    rf"^\[[^]]+\] \[Server thread/INFO\] \[{SERVER_EMITTER}\]: "
)
SAY_PREFIX = SERVER_PREFIX + r"\[Not Secure\] \[Server\] "
# SettlementManager founds this isolated fixture with one name assembled from
# SettlerNames' fixed first/second tables.  Pinning that complete production
# grammar makes the command-feedback line distinguishable from `/say` (whose
# MinecraftServer payload starts with ``[Not Secure] [Server]``), arbitrary
# bracketed prefixes, and prose that merely embeds a population-shaped token.
SETTLEMENT_NAME = (
    r"(?:Ash|Elm|Thorn|Birch|Stone|Fen|Harrow|Wolf|Raven|Ember|Frost|"
    r"Heather|Oak|Mill)"
    r"(?:ford|wick|stead|holm|dale|mere|field|brook|gate|haven|moor|bridge)"
)
BEGIN_RE = re.compile(
    SAY_PREFIX + r"HSQA_PERF_BEGIN scale=(1|25|50|100)$"
)
END_RE = re.compile(
    SAY_PREFIX + r"HSQA_PERF_END scale=(1|25|50|100)$"
)
POP_RE = re.compile(
    SAY_PREFIX
    + r"HSQA_PERF_POPULATION_OK scale=(1|25|50|100) "
      r"expected=(1|25|50|100)$"
)
RECORD_RE = re.compile(
    SERVER_PREFIX
    + SETTLEMENT_NAME
    + r" — population (\d+)/(\d+), employed -?\d+, "
      r"food -?\d+, morale -?\d+, radius -?\d+$",
)
TRANSIT_RE = re.compile(
    SAY_PREFIX + r"HSQA_PERF_TRANSIT_OK "
    r"added=(\d+) target_scale=(25|50|100)$"
)
RETURN_RE = re.compile(
    SAY_PREFIX + r"HSQA_PERF_RETURN_OK "
    r"added=(\d+) target_scale=(25|50|100)$"
)
EXPECTED_ADDITIONS = {25: 24, 50: 25, 100: 50}
COUNT_RE = re.compile(
    SERVER_PREFIX + r"hsqa_count has (-?\d+) \[hsqa_pop\]$"
)
MEMBER_COUNT_RE = re.compile(
    SERVER_PREFIX + r"hsqa_member_count has (-?\d+) \[hsqa_pop\]$"
)
TICK_HEADER_RE = re.compile(
    SERVER_PREFIX + r"The game is running normally$"
)
TICK_TARGET_RE = re.compile(
    SERVER_PREFIX + r"Target tick rate: 20\.0 per second\.$"
)
TICK_CONTINUATION_RE = re.compile(
    r"^Average time per tick: ([0-9]+(?:\.[0-9]+)?)ms "
    r"\(Target: 50\.0ms\)$"
)
RECORD_TOKEN_RE = re.compile(r"\s—\s+population\s+", re.IGNORECASE)
TRANSIT_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])HSQA_PERF_TRANSIT_OK(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
RETURN_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])HSQA_PERF_RETURN_OK(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
COUNT_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])hsqa_count\s+has\b", re.IGNORECASE
)
MEMBER_COUNT_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])hsqa_member_count\s+has\b", re.IGNORECASE
)
POP_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])HSQA_PERF_POPULATION_OK(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
BEGIN_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])HSQA_PERF_BEGIN(?![A-Za-z0-9_])", re.IGNORECASE
)
END_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_])HSQA_PERF_END(?![A-Za-z0-9_])", re.IGNORECASE
)
TICK_HEADER_TOKEN_RE = re.compile(
    r"\bthe\s+game\s+is\s+running\s+normally\b", re.IGNORECASE
)
TICK_TARGET_TOKEN_RE = re.compile(
    r"\btarget\s+tick\s+rate\s*:", re.IGNORECASE
)
TICK_CONTINUATION_TOKEN_RE = re.compile(
    r"\baverage\s+time\s+per\s+tick\s*:", re.IGNORECASE
)
INLINE_TICK_TOKEN_RE = re.compile(
    r"\bthe\s+server\s+is\s+running\s+at\s+an\s+average\s+of\b",
    re.IGNORECASE,
)
@dataclass
class ScaleResult:
    scale: int
    population: int | None
    bound_members: int | None
    settlement_records: int | None
    settlement_capacity: int | None
    transit_added: int | None
    returned_added: int | None
    required_samples: int
    sample_count: int
    samples_mspt: list[float]
    median_mspt: float | None
    average_mspt: float | None
    budget_mspt: float | None
    gate: str
    population_scoreboard_line: int | None
    member_scoreboard_line: int | None
    settlement_record_line: int | None
    transit_line: int | None
    return_line: int | None
    population_marker_line: int | None
    begin_line: int | None
    end_line: int | None


@dataclass
class MatrixResult:
    schema_version: int
    source_log: str
    required_samples_per_scale: int
    absolute_average_budgets_mspt: dict[str, float]
    overall: str
    scales: list[ScaleResult]
    errors: list[str]


def matched_tick_value(match: re.Match[str] | None) -> float | None:
    if match:
        value = float(match.group(1))
        if math.isfinite(value) and value >= 0.0:
            return value
    return None


def parse_lines(lines: Iterable[str], required_samples: int, source: str) -> MatrixResult:
    samples: dict[int, list[float]] = {scale: [] for scale in SCALES}
    population: dict[int, int] = {}
    members: dict[int, int] = {}
    population_lines: dict[int, int] = {}
    member_lines: dict[int, int] = {}
    record_population: dict[int, int] = {}
    record_capacity: dict[int, int] = {}
    record_lines: dict[int, int] = {}
    population_marker_lines: dict[int, int] = {}
    transit: dict[int, tuple[int, int]] = {}
    returned: dict[int, tuple[int, int]] = {}
    begin_lines: dict[int, int] = {}
    end_lines: dict[int, int] = {}
    errors: list[str] = []

    active_scale: int | None = None
    pending_populations: list[tuple[int, int]] = []
    pending_members: list[tuple[int, int]] = []
    pending_records: list[tuple[int, int, int]] = []
    # Vanilla emits /tick query's average as an exact three-line frame:
    # trusted normal-status header -> trusted target-rate line -> bare average.
    # A state machine prevents an unrelated/spoofed bare average from being
    # accepted merely because a valid header occurred earlier in the log.
    tick_frame_state = "idle"

    for line_number, line in enumerate(lines, 1):
        if tick_frame_state == "target":
            if TICK_TARGET_RE.fullmatch(line):
                tick_frame_state = "average"
                continue
            tick_frame_state = "idle"
            errors.append(
                f"line {line_number}: tick-query header lacks exact "
                "Target tick rate continuation"
            )
        elif tick_frame_state == "average":
            tick_frame_state = "idle"
            value = matched_tick_value(TICK_CONTINUATION_RE.fullmatch(line))
            if value is not None and active_scale is not None:
                samples[active_scale].append(value)
                continue
            errors.append(
                f"line {line_number}: tick-query target lacks exact "
                "Average time continuation"
            )

        record_match = RECORD_RE.fullmatch(line)
        if RECORD_TOKEN_RE.search(line) and record_match is None:
            errors.append(
                f"line {line_number}: malformed settlement-record read-back"
            )
        if record_match:
            pending_records.append((
                int(record_match.group(1)), int(record_match.group(2)), line_number
            ))

        transit_match = TRANSIT_RE.fullmatch(line)
        if TRANSIT_TOKEN_RE.search(line) and transit_match is None:
            errors.append(f"line {line_number}: malformed transit proof")
        if transit_match:
            added = int(transit_match.group(1))
            target = int(transit_match.group(2))
            if target in transit:
                errors.append(f"duplicate transit proof for target scale {target}")
            transit[target] = (added, line_number)

        return_match = RETURN_RE.fullmatch(line)
        if RETURN_TOKEN_RE.search(line) and return_match is None:
            errors.append(f"line {line_number}: malformed return proof")
        if return_match:
            added = int(return_match.group(1))
            target = int(return_match.group(2))
            if target in returned:
                errors.append(f"duplicate return proof for target scale {target}")
            returned[target] = (added, line_number)

        count_match = COUNT_RE.fullmatch(line)
        if COUNT_TOKEN_RE.search(line) and count_match is None:
            errors.append(f"line {line_number}: malformed population scoreboard")
        if count_match:
            pending_populations.append((int(count_match.group(1)), line_number))

        member_match = MEMBER_COUNT_RE.fullmatch(line)
        if MEMBER_COUNT_TOKEN_RE.search(line) and member_match is None:
            errors.append(f"line {line_number}: malformed member scoreboard")
        if member_match:
            pending_members.append((int(member_match.group(1)), line_number))

        pop_match = POP_RE.fullmatch(line)
        if POP_TOKEN_RE.search(line) and pop_match is None:
            errors.append(f"line {line_number}: malformed population marker")
        if pop_match:
            scale = int(pop_match.group(1))
            expected = int(pop_match.group(2))
            if scale != expected:
                errors.append(
                    f"population marker scale={scale} has expected={expected}"
                )
            if scale in population:
                errors.append(f"duplicate population proof for scale {scale}")
            if len(pending_populations) != 1 or len(pending_members) != 1:
                errors.append(
                    f"scale {scale} population marker requires exactly one "
                    f"population and member scoreboard read-back; observed "
                    f"{len(pending_populations)}/{len(pending_members)}"
                )
            elif scale not in population:
                count, count_line = pending_populations[0]
                member_count, member_line = pending_members[0]
                population[scale] = count
                members[scale] = member_count
                population_lines[scale] = count_line
                member_lines[scale] = member_line
                if count != scale:
                    errors.append(
                        f"scale {scale} scoreboard population was {count}, expected {scale}"
                    )
                if member_count != scale:
                    errors.append(
                        f"scale {scale} bound-member scoreboard was {member_count}, expected {scale}"
                    )
            if len(pending_records) != 1:
                errors.append(
                    f"scale {scale} population marker requires exactly one "
                    f"authoritative settlement-record read-back; observed "
                    f"{len(pending_records)}"
                )
            elif scale not in record_population:
                records, capacity, record_line = pending_records[0]
                record_population[scale] = records
                record_capacity[scale] = capacity
                record_lines[scale] = record_line
                if records != scale:
                    errors.append(
                        f"scale {scale} settlement record population was "
                        f"{records}, expected {scale}"
                    )
            # A later marker must be backed by later scoreboard output, never
            # by stale values left over from the preceding scale.
            if scale not in population_marker_lines:
                population_marker_lines[scale] = line_number
            pending_populations = []
            pending_members = []
            pending_records = []

        begin_match = BEGIN_RE.fullmatch(line)
        if BEGIN_TOKEN_RE.search(line) and begin_match is None:
            errors.append(f"line {line_number}: malformed begin marker")
        if begin_match:
            scale = int(begin_match.group(1))
            if active_scale is not None:
                errors.append(
                    f"scale {scale} began while scale {active_scale} was still active"
                )
            if scale in begin_lines:
                errors.append(f"duplicate begin marker for scale {scale}")
            if scale not in population:
                errors.append(f"scale {scale} began before exact population proof")
            active_scale = scale
            begin_lines[scale] = line_number
            tick_frame_state = "idle"
            continue

        end_match = END_RE.fullmatch(line)
        if END_TOKEN_RE.search(line) and end_match is None:
            errors.append(f"line {line_number}: malformed end marker")
        if end_match:
            scale = int(end_match.group(1))
            if active_scale != scale:
                errors.append(
                    f"scale {scale} ended while active scale was {active_scale}"
                )
            if scale in end_lines:
                errors.append(f"duplicate end marker for scale {scale}")
            end_lines[scale] = line_number
            active_scale = None
            tick_frame_state = "idle"
            continue

        tick_header_match = TICK_HEADER_RE.fullmatch(line)
        if TICK_HEADER_TOKEN_RE.search(line) and tick_header_match is None:
            errors.append(f"line {line_number}: malformed tick-query header")
            continue
        if tick_header_match:
            if active_scale is None:
                errors.append(
                    f"line {line_number}: tick-query header outside a scale window"
                )
            else:
                tick_frame_state = "target"
            continue

        if TICK_TARGET_TOKEN_RE.search(line):
            if TICK_TARGET_RE.fullmatch(line):
                errors.append(
                    f"line {line_number}: orphan tick-query target continuation"
                )
            else:
                errors.append(f"line {line_number}: malformed tick-query target")
            continue

        continuation_value = matched_tick_value(
            TICK_CONTINUATION_RE.fullmatch(line)
        )
        if continuation_value is not None:
            errors.append(
                f"line {line_number}: orphan tick-query continuation"
            )
            continue
        if (TICK_CONTINUATION_TOKEN_RE.search(line)
                and TICK_CONTINUATION_RE.fullmatch(line) is None):
            errors.append(f"line {line_number}: malformed tick-query sample")
        if INLINE_TICK_TOKEN_RE.search(line):
            errors.append(f"line {line_number}: unsupported inline tick-query sample")

    if active_scale is not None:
        errors.append(f"scale {active_scale} has no end marker")
    if tick_frame_state == "target":
        errors.append("final tick-query header has no Target tick rate continuation")
    elif tick_frame_state == "average":
        errors.append("final tick-query target has no Average time continuation")
    if pending_records:
        errors.append(
            "settlement-record read-back was not consumed by a population marker"
        )
    if pending_populations or pending_members:
        errors.append(
            "scoreboard read-back was not consumed by a population marker"
        )

    for target, expected_added in EXPECTED_ADDITIONS.items():
        transit_proof = transit.get(target)
        return_proof = returned.get(target)
        if transit_proof is None:
            errors.append(f"missing transit proof for target scale {target}")
        elif transit_proof[0] != expected_added:
            errors.append(
                f"target scale {target} transit added {transit_proof[0]}, "
                f"expected {expected_added}"
            )
        if return_proof is None:
            errors.append(f"missing return proof for target scale {target}")
        elif return_proof[0] != expected_added:
            errors.append(
                f"target scale {target} return added {return_proof[0]}, "
                f"expected {expected_added}"
            )
    previous_end = 0
    for scale in SCALES:
        record_line = record_lines.get(scale)
        count_line = population_lines.get(scale)
        member_line = member_lines.get(scale)
        pop_line = population_marker_lines.get(scale)
        begin_line = begin_lines.get(scale)
        end_line = end_lines.get(scale)
        prefix_lines: list[int] = [previous_end]
        if scale in EXPECTED_ADDITIONS:
            transit_proof = transit.get(scale)
            return_proof = returned.get(scale)
            if transit_proof is not None:
                prefix_lines.append(transit_proof[1])
            if return_proof is not None:
                prefix_lines.append(return_proof[1])
        complete = all(value is not None for value in (
            record_line, count_line, member_line, pop_line, begin_line, end_line
        ))
        if complete:
            chain = prefix_lines + [
                record_line, count_line, member_line, pop_line,
                begin_line, end_line,
            ]
            if len(prefix_lines) != (3 if scale in EXPECTED_ADDITIONS else 1) \
                    or any(left >= right
                           for left, right in zip(chain, chain[1:])):
                errors.append(
                    f"scale {scale} evidence must follow canonical order: "
                    "previous end, transit/return, record, population/member "
                    "scoreboards, population marker, begin and end"
                )
            previous_end = end_line

    scale_results: list[ScaleResult] = []
    budget_failed = False
    for scale in SCALES:
        values = samples[scale]
        if scale not in population:
            errors.append(f"missing exact population proof for scale {scale}")
        if scale not in begin_lines:
            errors.append(f"missing begin marker for scale {scale}")
        if scale not in end_lines:
            errors.append(f"missing end marker for scale {scale}")
        if len(values) != required_samples:
            errors.append(
                f"scale {scale} captured {len(values)} tick samples, "
                f"expected exactly {required_samples}"
            )

        median = statistics.median(values) if values else None
        average = statistics.fmean(values) if values else None
        budget = AVERAGE_BUDGET_MS.get(scale)
        if budget is None:
            gate = "INFO"
        elif average is None:
            gate = "ERROR"
        elif average <= budget:
            gate = "PASS"
        else:
            gate = "FAIL"
            budget_failed = True

        scale_results.append(
            ScaleResult(
                scale=scale,
                population=population.get(scale),
                bound_members=members.get(scale),
                settlement_records=record_population.get(scale),
                settlement_capacity=record_capacity.get(scale),
                transit_added=(transit.get(scale) or (None, None))[0],
                returned_added=(returned.get(scale) or (None, None))[0],
                required_samples=required_samples,
                sample_count=len(values),
                samples_mspt=values,
                median_mspt=median,
                average_mspt=average,
                budget_mspt=budget,
                gate=gate,
                population_scoreboard_line=population_lines.get(scale),
                member_scoreboard_line=member_lines.get(scale),
                settlement_record_line=record_lines.get(scale),
                transit_line=(transit.get(scale) or (None, None))[1],
                return_line=(returned.get(scale) or (None, None))[1],
                population_marker_line=population_marker_lines.get(scale),
                begin_line=begin_lines.get(scale),
                end_line=end_lines.get(scale),
            )
        )

    if errors:
        overall = "ERROR"
    elif budget_failed:
        overall = "FAIL"
    else:
        overall = "PASS"

    return MatrixResult(
        schema_version=2,
        source_log=source,
        required_samples_per_scale=required_samples,
        absolute_average_budgets_mspt={
            str(scale): budget for scale, budget in AVERAGE_BUDGET_MS.items()
        },
        overall=overall,
        scales=scale_results,
        errors=errors,
    )


def write_json(result: MatrixResult, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(asdict(result), indent=2, sort_keys=False) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=destination.parent,
        prefix=f".{destination.name}.",
        suffix=".tmp",
        delete=False,
    ) as temporary:
        temporary.write(payload)
        temporary_path = Path(temporary.name)
    temporary_path.replace(destination)


def print_tsv(result: MatrixResult) -> None:
    print(
        "scale\tpopulation\tbound_members\tsettlement_records\tsamples\tmedian_mspt\t"
        "average_mspt\tbudget_mspt\tgate"
    )
    for row in result.scales:
        def value(item: object | None) -> str:
            if item is None:
                return "-"
            if isinstance(item, float):
                return f"{item:.3f}"
            return str(item)

        print(
            "\t".join(
                (
                    str(row.scale),
                    value(row.population),
                    value(row.bound_members),
                    value(row.settlement_records),
                    str(row.sample_count),
                    value(row.median_mspt),
                    value(row.average_mspt),
                    value(row.budget_mspt),
                    row.gate,
                )
            )
        )


def synthetic_log(values: dict[int, list[float]], populations: dict[int, int] | None = None) -> str:
    populations = populations or {scale: scale for scale in SCALES}
    lines: list[str] = []
    prefix = (
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
    )
    say_prefix = prefix + "[Not Secure] [Server] "
    for scale in SCALES:
        pop = populations[scale]
        if scale in EXPECTED_ADDITIONS:
            added = EXPECTED_ADDITIONS[scale]
            lines.extend((
                say_prefix
                + f"HSQA_PERF_TRANSIT_OK added={added} target_scale={scale}",
                say_prefix
                + f"HSQA_PERF_RETURN_OK added={added} target_scale={scale}",
            ))
        lines.extend(
            (
                prefix
                + f"Ashford — population {pop}/100, employed 0, food 0, morale 0, radius 24",
                prefix + f"hsqa_count has {pop} [hsqa_pop]",
                prefix + f"hsqa_member_count has {pop} [hsqa_pop]",
                say_prefix
                + f"HSQA_PERF_POPULATION_OK scale={scale} expected={scale}",
                say_prefix + f"HSQA_PERF_BEGIN scale={scale}",
            )
        )
        lines.extend(
            line
            for sample in values[scale]
            for line in (
                prefix + "The game is running normally",
                prefix + "Target tick rate: 20.0 per second.",
                f"Average time per tick: {sample}ms (Target: 50.0ms)",
            )
        )
        lines.append(say_prefix + f"HSQA_PERF_END scale={scale}")
    return "\n".join(lines) + "\n"


def selftest() -> None:
    passing = {
        1: [1.0, 1.1, 1.2],
        25: [10.0, 11.0, 12.0],
        50: [40.0, 45.0, 44.0],
        100: [49.0, 50.0, 50.0],
    }
    result = parse_lines(synthetic_log(passing).splitlines(), 3, "passing.log")
    assert result.overall == "PASS", result
    assert result.scales[2].median_mspt == 44.0
    assert round(result.scales[3].average_mspt or -1, 3) == 49.667

    over_budget = {scale: list(samples) for scale, samples in passing.items()}
    over_budget[50] = [45.1, 45.2, 45.3]
    result = parse_lines(
        synthetic_log(over_budget).splitlines(), 3, "over-budget.log"
    )
    assert result.overall == "FAIL", result
    assert result.scales[2].gate == "FAIL"

    missing_sample = {scale: list(samples) for scale, samples in passing.items()}
    missing_sample[25] = missing_sample[25][:-1]
    result = parse_lines(
        synthetic_log(missing_sample).splitlines(), 3, "missing-sample.log"
    )
    assert result.overall == "ERROR", result
    assert any("scale 25 captured 2" in error for error in result.errors)

    wrong_population = dict(zip(SCALES, SCALES))
    wrong_population[100] = 99
    result = parse_lines(
        synthetic_log(passing, wrong_population).splitlines(),
        3,
        "wrong-population.log",
    )
    assert result.overall == "ERROR", result
    assert any("scale 100 scoreboard population was 99" in error for error in result.errors)

    missing_transit = synthetic_log(passing).replace(
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
        "[Not Secure] [Server] HSQA_PERF_TRANSIT_OK added=24 target_scale=25\n",
        "",
    )
    result = parse_lines(missing_transit.splitlines(), 3, "missing-transit.log")
    assert result.overall == "ERROR", result
    assert any("missing transit proof for target scale 25" in error
               for error in result.errors)

    base_lines = synthetic_log(passing).splitlines()
    transit_25 = next(line for line in base_lines
                      if "TRANSIT_OK added=24 target_scale=25" in line)
    return_25 = next(line for line in base_lines
                     if "RETURN_OK added=24 target_scale=25" in line)

    duplicate_return = list(base_lines)
    duplicate_return.insert(duplicate_return.index(return_25), return_25)
    result = parse_lines(duplicate_return, 3, "duplicate-return.log")
    assert result.overall == "ERROR", result
    assert any("duplicate return proof" in error for error in result.errors)

    duplicate_transit = list(base_lines)
    duplicate_transit.insert(duplicate_transit.index(transit_25), transit_25)
    result = parse_lines(duplicate_transit, 3, "duplicate-transit.log")
    assert result.overall == "ERROR", result
    assert any("duplicate transit proof" in error for error in result.errors)

    misordered = list(base_lines)
    transit_index = misordered.index(transit_25)
    return_index = misordered.index(return_25)
    misordered[transit_index], misordered[return_index] = (
        misordered[return_index], misordered[transit_index]
    )
    result = parse_lines(misordered, 3, "misordered-transit-return.log")
    assert result.overall == "ERROR", result
    assert any("canonical order" in error for error in result.errors)

    malformed_return = synthetic_log(passing).replace(
        "HSQA_PERF_RETURN_OK added=25 target_scale=50",
        "HSQA_PERF_RETURN_OK added=nope target_scale=50",
    )
    result = parse_lines(malformed_return.splitlines(), 3,
                         "malformed-return.log")
    assert result.overall == "ERROR", result
    assert any("malformed return proof" in error for error in result.errors)

    malformed_transit = synthetic_log(passing).replace(
        "HSQA_PERF_TRANSIT_OK added=50 target_scale=100",
        "HSQA_PERF_TRANSIT_OK added=nope target_scale=100",
    )
    result = parse_lines(malformed_transit.splitlines(), 3,
                         "malformed-transit.log")
    assert result.overall == "ERROR", result
    assert any("malformed transit proof" in error for error in result.errors)

    missing_return = "\n".join(
        line for line in base_lines
        if "RETURN_OK added=50 target_scale=100" not in line
    )
    result = parse_lines(missing_return.splitlines(), 3, "missing-return.log")
    assert result.overall == "ERROR", result
    assert any("missing return proof for target scale 100" in error
               for error in result.errors)

    missing_record = "\n".join(
        line for line in base_lines
        if "Ashford — population 50/100" not in line
    )
    result = parse_lines(missing_record.splitlines(), 3, "missing-record.log")
    assert result.overall == "ERROR", result
    assert any("scale 50 population marker requires exactly one" in error
               for error in result.errors)

    record_25 = next(line for line in base_lines
                     if "Ashford — population 25/100" in line)
    duplicate_record = list(base_lines)
    duplicate_record.insert(duplicate_record.index(record_25), record_25)
    result = parse_lines(duplicate_record, 3, "duplicate-record.log")
    assert result.overall == "ERROR", result
    assert any("observed 2" in error for error in result.errors)

    malformed_record = synthetic_log(passing).replace(
        "Ashford — population 25/100", "Ashford — population nope/100"
    )
    result = parse_lines(malformed_record.splitlines(), 3,
                         "malformed-record.log")
    assert result.overall == "ERROR", result
    assert any("scale 25 population marker requires exactly one" in error
               for error in result.errors)

    record_payload = (
        "Ashford — population 25/100, employed 0, food 0, morale 0, "
        "radius 24"
    )
    trusted_server_prefix = (
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
    )
    record_index = base_lines.index(record_25)
    record_spoofs = {
        "say-record.log": (
            trusted_server_prefix + "[Not Secure] [Server] " + record_payload
        ),
        "bracket-prefix-record.log": (
            trusted_server_prefix + "[Injected] " + record_payload
        ),
        "embedded-prefix-record.log": (
            trusted_server_prefix + "diagnostic " + record_payload
        ),
        "wrong-emitter-record.log": (
            "[00:00:00] [Server thread/INFO] [co.example.Mod]: "
            + record_payload
        ),
        "wrong-case-record.log": (
            trusted_server_prefix + record_payload.replace(
                "Ashford", "ashford"
            )
        ),
        "decimal-field-record.log": (
            trusted_server_prefix + record_payload.replace(
                "employed 0", "employed 0.0"
            )
        ),
    }
    for source, spoof in record_spoofs.items():
        spoofed_record = list(base_lines)
        spoofed_record[record_index] = spoof
        result = parse_lines(spoofed_record, 3, source)
        assert result.overall == "ERROR", (source, result)
        assert any("malformed settlement-record read-back" in error
                   for error in result.errors), (source, result.errors)

    stale_records = synthetic_log(passing).replace(
        "Ashford — population 100/100", "Ashford — population 99/100"
    )
    result = parse_lines(stale_records.splitlines(), 3, "stale-records.log")
    assert result.overall == "ERROR", result
    assert any("settlement record population was 99" in error
               for error in result.errors)

    count_25 = next(line for line in base_lines
                    if "hsqa_count has 25 [hsqa_pop]" in line)
    duplicate_scoreboard = list(base_lines)
    duplicate_scoreboard.insert(
        duplicate_scoreboard.index(count_25),
        count_25.replace("has 25", "has 24"),
    )
    result = parse_lines(duplicate_scoreboard, 3,
                         "wrong-then-right-scoreboard.log")
    assert result.overall == "ERROR", result
    assert any("observed 2/1" in error for error in result.errors)

    trailing_population = list(base_lines)
    pop_index = next(i for i, line in enumerate(trailing_population)
                     if "POPULATION_OK scale=50" in line)
    trailing_population[pop_index] += " injected=true"
    result = parse_lines(trailing_population, 3, "trailing-population.log")
    assert result.overall == "ERROR", result
    assert any("malformed population marker" in error
               for error in result.errors)

    wrong_emitter = list(base_lines)
    begin_index = next(i for i, line in enumerate(wrong_emitter)
                       if "HSQA_PERF_BEGIN scale=25" in line)
    wrong_emitter[begin_index] = wrong_emitter[begin_index].replace(
        "[minecraft/MinecraftServer]", "[co.example.Mod]"
    )
    result = parse_lines(wrong_emitter, 3, "wrong-emitter.log")
    assert result.overall == "ERROR", result
    assert any("malformed begin marker" in error for error in result.errors)

    trailing_scoreboard = list(base_lines)
    score_index = trailing_scoreboard.index(count_25)
    trailing_scoreboard[score_index] += " injected"
    result = parse_lines(trailing_scoreboard, 3, "trailing-scoreboard.log")
    assert result.overall == "ERROR", result
    assert any("malformed population scoreboard" in error
               for error in result.errors)

    trailing_tick = list(base_lines)
    tick_index = next(i for i, line in enumerate(trailing_tick)
                      if line.startswith("Average time per tick: 10.0ms"))
    trailing_tick[tick_index] += " injected"
    result = parse_lines(trailing_tick, 3, "trailing-tick.log")
    assert result.overall == "ERROR", result
    assert any("tick-query target lacks exact" in error
               for error in result.errors)

    target_line = (
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
        "Target tick rate: 20.0 per second."
    )
    missing_target = list(base_lines)
    missing_target.remove(target_line)
    result = parse_lines(missing_target, 3, "missing-target.log")
    assert result.overall == "ERROR", result
    assert any("header lacks exact Target tick rate" in error
               for error in result.errors)

    trailing_target = list(base_lines)
    target_index = trailing_target.index(target_line)
    trailing_target[target_index] += " injected"
    result = parse_lines(trailing_target, 3, "trailing-target.log")
    assert result.overall == "ERROR", result
    assert any("malformed tick-query target" in error
               for error in result.errors)

    inserted_between = list(base_lines)
    target_index = inserted_between.index(target_line)
    inserted_between.insert(
        target_index,
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
        "unrelated output",
    )
    result = parse_lines(inserted_between, 3, "inserted-tick-line.log")
    assert result.overall == "ERROR", result
    assert any("header lacks exact Target tick rate" in error
               for error in result.errors)
    assert any("orphan tick-query target" in error
               for error in result.errors)

    wrong_target_emitter = list(base_lines)
    target_index = wrong_target_emitter.index(target_line)
    wrong_target_emitter[target_index] = target_line.replace(
        "[minecraft/MinecraftServer]", "[co.example.Mod]"
    )
    result = parse_lines(wrong_target_emitter, 3, "wrong-target-emitter.log")
    assert result.overall == "ERROR", result
    assert any("malformed tick-query target" in error
               for error in result.errors)

    appended_untrusted_header = list(base_lines) + [
        "[00:00:03] [Server thread/INFO] [co.example.Mod]: embedded "
        "The game is running normally"
    ]
    result = parse_lines(
        appended_untrusted_header, 3, "appended-untrusted-header.log"
    )
    assert result.overall == "ERROR", result
    assert any("malformed tick-query header" in error
               for error in result.errors)

    def untrusted(line: str) -> str:
        if "[minecraft/MinecraftServer]" in line:
            return line.replace(
                "[minecraft/MinecraftServer]", "[co.example.Mod]"
            )
        return (
            "[00:00:03] [Server thread/INFO] [co.example.Mod]: embedded "
            + line
        )

    # Every protocol family is detected case-insensitively, but only its
    # exact case-sensitive authoritative full line is accepted. Exercise the
    # dangerous append shape: the original matrix remains entirely valid, so
    # PASS is possible only if the appended spoof is accidentally ignored.
    family_lines = (
        (
            "record",
            record_25,
            " — population ",
            " — PoPuLaTiOn ",
            "malformed settlement-record read-back",
        ),
        (
            "transit",
            transit_25,
            "HSQA_PERF_TRANSIT_OK",
            "hsqa_perf_Transit_Ok",
            "malformed transit proof",
        ),
        (
            "return",
            return_25,
            "HSQA_PERF_RETURN_OK",
            "hsqa_perf_Return_Ok",
            "malformed return proof",
        ),
        (
            "population-scoreboard",
            count_25,
            "hsqa_count has",
            "HsQa_CoUnT HaS",
            "malformed population scoreboard",
        ),
        (
            "member-scoreboard",
            next(line for line in base_lines
                 if "hsqa_member_count has 25" in line),
            "hsqa_member_count has",
            "HsQa_MeMbEr_CoUnT HaS",
            "malformed member scoreboard",
        ),
        (
            "population-marker",
            next(line for line in base_lines
                 if "HSQA_PERF_POPULATION_OK scale=25" in line),
            "HSQA_PERF_POPULATION_OK",
            "hsqa_perf_Population_Ok",
            "malformed population marker",
        ),
        (
            "begin",
            next(line for line in base_lines
                 if "HSQA_PERF_BEGIN scale=25" in line),
            "HSQA_PERF_BEGIN",
            "hsqa_perf_Begin",
            "malformed begin marker",
        ),
        (
            "end",
            next(line for line in base_lines
                 if "HSQA_PERF_END scale=25" in line),
            "HSQA_PERF_END",
            "hsqa_perf_End",
            "malformed end marker",
        ),
        (
            "tick-header",
            next(line for line in base_lines
                 if "The game is running normally" in line),
            "The game is running normally",
            "tHe GaMe Is RuNnInG NoRmAlLy",
            "malformed tick-query header",
        ),
        (
            "tick-target",
            target_line,
            "Target tick rate:",
            "tArGeT TiCk RaTe:",
            "malformed tick-query target",
        ),
        (
            "tick-average",
            next(line for line in base_lines
                 if line.startswith("Average time per tick:")),
            "Average time per tick:",
            "aVeRaGe TiMe PeR TiCk:",
            "malformed tick-query sample",
        ),
    )
    for family, canonical, token, mixed_token, error_fragment in family_lines:
        for variant, appended in (
            ("mixed-trusted", canonical.replace(token, mixed_token)),
            ("uppercase-untrusted", untrusted(
                canonical.replace(token, token.upper())
            )),
            ("trailing-trusted", canonical + " injected"),
        ):
            rejected = parse_lines(
                base_lines + [appended],
                3,
                f"{variant}-{family}-append.log",
            )
            assert rejected.overall == "ERROR", (
                family,
                variant,
                rejected,
            )
            assert any(error_fragment in error for error in rejected.errors), (
                family,
                variant,
                rejected.errors,
            )
        duplicated = parse_lines(
            base_lines + [canonical], 3, f"duplicate-{family}-append.log"
        )
        assert duplicated.overall == "ERROR", (family, duplicated)

    unsupported_mixed_inline = list(base_lines) + [
        "[00:00:03] [Server thread/INFO] [co.example.Mod]: embedded "
        "tHe SeRvEr Is RuNnInG At An AvErAgE Of 2.5 ms per tick"
    ]
    result = parse_lines(
        unsupported_mixed_inline, 3, "mixed-untrusted-inline-tick.log"
    )
    assert result.overall == "ERROR", result
    assert any("unsupported inline tick-query sample" in error
               for error in result.errors)

    wrong_average_target = list(base_lines)
    tick_index = next(i for i, line in enumerate(wrong_average_target)
                      if line.startswith("Average time per tick: 10.0ms"))
    wrong_average_target[tick_index] = wrong_average_target[
        tick_index
    ].replace("Target: 50.0ms", "Target: 100.0ms")
    result = parse_lines(wrong_average_target, 3, "wrong-average-target.log")
    assert result.overall == "ERROR", result
    assert any("target lacks exact Average time" in error
               for error in result.errors)

    reordered_chain = list(base_lines)
    record_index = next(i for i, line in enumerate(reordered_chain)
                        if "Ashford — population 25/100" in line)
    count_index = reordered_chain.index(count_25)
    reordered_chain[record_index], reordered_chain[count_index] = (
        reordered_chain[count_index], reordered_chain[record_index]
    )
    result = parse_lines(reordered_chain, 3, "record-after-scoreboard.log")
    assert result.overall == "ERROR", result
    assert any("canonical order" in error for error in result.errors)

    start_25 = next(i for i, line in enumerate(base_lines)
                    if "TRANSIT_OK added=24 target_scale=25" in line)
    end_25 = next(i for i, line in enumerate(base_lines)
                  if "HSQA_PERF_END scale=25" in line) + 1
    start_50 = next(i for i, line in enumerate(base_lines)
                    if "TRANSIT_OK added=25 target_scale=50" in line)
    end_50 = next(i for i, line in enumerate(base_lines)
                  if "HSQA_PERF_END scale=50" in line) + 1
    wrong_scale_order = (
        base_lines[:start_25]
        + base_lines[start_50:end_50]
        + base_lines[start_25:end_25]
        + base_lines[end_50:]
    )
    result = parse_lines(wrong_scale_order, 3, "wrong-scale-order.log")
    assert result.overall == "ERROR", result
    assert any("canonical order" in error for error in result.errors)

    # The verified current vanilla three-line shape is the only accepted
    # sample frame; a legacy/forged single-line phrasing cannot bypass it.
    assert TICK_TARGET_RE.fullmatch(
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
        "Target tick rate: 20.0 per second."
    )
    assert matched_tick_value(TICK_CONTINUATION_RE.fullmatch(
        "Average time per tick: 1.25ms (Target: 50.0ms)")) == 1.25
    legacy_inline = list(base_lines)
    average_index = next(i for i, line in enumerate(legacy_inline)
                         if line.startswith("Average time per tick:"))
    legacy_inline[average_index] = (
        "[00:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: "
        "The server is running at an average of 2.5 ms per tick"
    )
    result = parse_lines(legacy_inline, 3, "legacy-inline.log")
    assert result.overall == "ERROR", result
    assert any("unsupported inline tick-query sample" in error
               for error in result.errors)

    with tempfile.TemporaryDirectory() as temp_dir:
        destination = Path(temp_dir) / "matrix.json"
        write_json(result, destination)
        loaded = json.loads(destination.read_text(encoding="utf-8"))
        assert loaded["overall"] == "ERROR"

    print("perf matrix parser selftest: PASS")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", nargs="?", type=Path)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)

    if args.selftest:
        selftest()
        return 0
    if args.log is None or args.json_out is None:
        parser.error("log and --json-out are required unless --selftest is used")
    if not 1 <= args.samples <= 1000:
        parser.error("--samples must be between 1 and 1000")

    try:
        lines = args.log.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        print(f"perf matrix parser: could not read {args.log}: {exc}", file=sys.stderr)
        return 1

    result = parse_lines(lines, args.samples, str(args.log))
    write_json(result, args.json_out)
    print_tsv(result)
    for error in result.errors:
        print(f"ERROR: {error}", file=sys.stderr)

    if result.overall == "PASS":
        return 0
    if result.overall == "FAIL":
        return 2
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
