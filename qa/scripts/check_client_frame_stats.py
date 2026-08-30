#!/usr/bin/env python3
"""Fail-closed parser for Hearthstead's real-client frame acknowledgements.

The QA observer writes one ``HSQA_FRAME_ACK`` line after a requested render
barrier. This tool extracts those rows and can compare named UI-transition
windows with a same-launch world baseline. It never substitutes encoded-video
FPS for real client frame intervals.

Example:

    python3 qa/scripts/check_client_frame_stats.py CLIENT_LOG \
        --baseline screen_none \
        --require screen_HearthScreen \
        --require screen_DevelopmentScreen \
        --min-samples 180 --max-p95-ratio 1.35 --max-slow-rise 0.10 \
        --output frame-time-report.json

Exit 0 means every requested transition had enough samples and stayed inside
the relative budgets. Exit 1 means the evidence is missing, malformed, or
outside budget. With no ``--baseline``/``--require`` arguments the command is
an extractor only and exits 0 when at least one valid acknowledgement exists.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path


ACK = "HSQA_FRAME_ACK "
PAIR = re.compile(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)")
REQUIRED_KEYS = {
    "nonce",
    "qaSession",
    "observerSource",
    "nativeWindows",
    "runtimeJarSha256",
    "runtimeJarSource",
    "gameDirectoryToken",
    "runtimeJarPathToken",
    "worldPathToken",
    "integratedServer",
    "observedEpochMillis",
    "screen",
    "screenClass",
    "grabbed",
    "yaw",
    "pitch",
    "playerPos",
    "hitType",
    "hitBlock",
    "guiScale",
    "framebuffer",
    "language",
    "blessingTitleResolved",
    "blessingTitleLanguageMatch",
    "uiState",
    "uiTransition",
    "frameSamples",
    "frameP50Ms",
    "frameP95Ms",
    "frameP99Ms",
    "frameMaxMs",
    "framesOver33Ms",
    "framesOver100Ms",
}
SHA256 = re.compile(r"[0-9a-f]{64}")
SESSION = re.compile(r"[A-Za-z0-9_-]{8,80}")
FRAMEBUFFER = re.compile(r"([1-9][0-9]*)x([1-9][0-9]*)")


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


def finite_float(value: str, name: str, line_number: int) -> float:
    try:
        parsed = float(value)
    except ValueError as exc:
        raise ValueError(f"line {line_number}: {name} is not a number") from exc
    if not math.isfinite(parsed) or parsed < 0.0:
        raise ValueError(f"line {line_number}: {name} must be finite and non-negative")
    return parsed


def signed_finite_float(value: str, name: str, line_number: int) -> float:
    try:
        parsed = float(value)
    except ValueError as exc:
        raise ValueError(f"line {line_number}: {name} is not a number") from exc
    if not math.isfinite(parsed):
        raise ValueError(f"line {line_number}: {name} must be finite")
    return parsed


def nonnegative_int(value: str, name: str, line_number: int) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise ValueError(f"line {line_number}: {name} is not an integer") from exc
    if parsed < 0:
        raise ValueError(f"line {line_number}: {name} must be non-negative")
    return parsed


def parse_log(path: Path) -> tuple[list[dict[str, object]], list[str]]:
    rows: list[dict[str, object]] = []
    errors: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        return [], [f"cannot read {path}: {exc}"]

    seen_nonces: set[str] = set()
    for line_number, line in enumerate(lines, 1):
        marker = line.find(ACK)
        if marker < 0:
            continue
        prefix = line[:marker]
        if "Render thread/INFO" not in prefix or "hearthstead" not in prefix.lower():
            errors.append(
                f"line {line_number}: acknowledgement lacks the native render-thread logger prefix"
            )
            continue
        fields = dict(PAIR.findall(line[marker + len(ACK) :]))
        missing = sorted(REQUIRED_KEYS - fields.keys())
        if missing:
            errors.append(
                f"line {line_number}: acknowledgement missing {', '.join(missing)}"
            )
            continue
        try:
            samples = nonnegative_int(fields["frameSamples"], "frameSamples", line_number)
            over = nonnegative_int(fields["framesOver33Ms"], "framesOver33Ms", line_number)
            over_100 = nonnegative_int(
                fields["framesOver100Ms"], "framesOver100Ms", line_number
            )
            p50 = finite_float(fields["frameP50Ms"], "frameP50Ms", line_number)
            p95 = finite_float(fields["frameP95Ms"], "frameP95Ms", line_number)
            p99 = finite_float(fields["frameP99Ms"], "frameP99Ms", line_number)
            maximum = finite_float(fields["frameMaxMs"], "frameMaxMs", line_number)
            gui_scale = finite_float(fields["guiScale"], "guiScale", line_number)
            yaw = signed_finite_float(fields["yaw"], "yaw", line_number)
            pitch = signed_finite_float(fields["pitch"], "pitch", line_number)
            player_pos = tuple(
                signed_finite_float(value, "playerPos", line_number)
                for value in fields["playerPos"].split(",")
            )
        except ValueError as exc:
            errors.append(str(exc))
            continue
        if len(player_pos) != 3:
            errors.append(f"line {line_number}: playerPos must contain exactly x,y,z")
            continue
        if not -90.0 <= pitch <= 90.0:
            errors.append(f"line {line_number}: pitch lies outside [-90, 90]")
            continue
        if over > samples:
            errors.append(
                f"line {line_number}: framesOver33Ms {over} exceeds samples {samples}"
            )
            continue
        if over_100 > over:
            errors.append(
                f"line {line_number}: framesOver100Ms {over_100} exceeds "
                f"framesOver33Ms {over}"
            )
            continue
        if not (p50 <= p95 <= p99 <= maximum):
            errors.append(
                f"line {line_number}: percentiles are not monotonic "
                f"({p50}, {p95}, {p99}, {maximum})"
            )
            continue
        if fields["nonce"] in seen_nonces:
            errors.append(f"line {line_number}: duplicate acknowledgement nonce {fields['nonce']}")
            continue
        seen_nonces.add(fields["nonce"])
        if SESSION.fullmatch(fields["qaSession"]) is None:
            errors.append(f"line {line_number}: qaSession is missing or malformed")
            continue
        if fields["nativeWindows"] not in {"true", "false"}:
            errors.append(f"line {line_number}: nativeWindows is not boolean")
            continue
        if fields["grabbed"] not in {"true", "false"}:
            errors.append(f"line {line_number}: grabbed is not boolean")
            continue
        if fields["observerSource"] not in {"environment", "one_shot_marker"}:
            errors.append(f"line {line_number}: observerSource is not an approved activation path")
            continue
        if fields["runtimeJarSource"] not in {"code_source", "mod_list"}:
            errors.append(
                f"line {line_number}: runtimeJarSource is not an installed-mod JAR source"
            )
            continue
        if (SHA256.fullmatch(fields["gameDirectoryToken"]) is None
                or SHA256.fullmatch(fields["runtimeJarPathToken"]) is None
                or SHA256.fullmatch(fields["worldPathToken"]) is None):
            errors.append(f"line {line_number}: runtime path/world identity token is malformed")
            continue
        if fields["integratedServer"] not in {"true", "false"}:
            errors.append(f"line {line_number}: integratedServer is not boolean")
            continue
        try:
            observed_epoch_millis = nonnegative_int(
                fields["observedEpochMillis"], "observedEpochMillis", line_number
            )
        except ValueError as exc:
            errors.append(str(exc))
            continue
        if SHA256.fullmatch(fields["runtimeJarSha256"]) is None:
            errors.append(f"line {line_number}: runtimeJarSha256 is not a lowercase SHA-256")
            continue
        framebuffer = FRAMEBUFFER.fullmatch(fields["framebuffer"])
        if framebuffer is None:
            errors.append(f"line {line_number}: framebuffer is malformed")
            continue
        if gui_scale < 1.0 or not gui_scale.is_integer():
            errors.append(f"line {line_number}: guiScale is not a positive integer scale")
            continue
        if fields["blessingTitleResolved"] not in {"true", "false"} \
                or fields["blessingTitleLanguageMatch"] not in {"true", "false"}:
            errors.append(f"line {line_number}: language resolution flags are not boolean")
            continue
        transition = fields["uiTransition"]
        expected = expected_screen(transition)
        if expected is not None:
            screen = fields["screen"]
            screen_class = fields["screenClass"]
            if screen != expected or (expected != "null" and not screen_class.endswith("." + expected)):
                errors.append(
                    f"line {line_number}: transition {transition} was acknowledged on "
                    f"screen={screen} screenClass={screen_class}"
                )
                continue
        if transition != "screen_none" and fields["uiState"] in {
            "unavailable", "uninitialised", "none", "null", ""
        }:
            errors.append(
                f"line {line_number}: transition {transition} has unusable uiState={fields['uiState']}"
            )
            continue
        profile = (
            f"{framebuffer.group(1)}x{framebuffer.group(2)}-"
            f"gui{int(gui_scale)}-{fields['language']}"
        )
        rows.append(
            {
                "line": line_number,
                "nonce": fields["nonce"],
                "qaSession": fields["qaSession"],
                "observerSource": fields["observerSource"],
                "nativeWindows": fields["nativeWindows"] == "true",
                "runtimeJarSha256": fields["runtimeJarSha256"],
                "runtimeJarSource": fields["runtimeJarSource"],
                "gameDirectoryToken": fields["gameDirectoryToken"],
                "runtimeJarPathToken": fields["runtimeJarPathToken"],
                "worldPathToken": fields["worldPathToken"],
                "integratedServer": fields["integratedServer"] == "true",
                "observedEpochMillis": observed_epoch_millis,
                "screen": fields["screen"],
                "screenClass": fields["screenClass"],
                "grabbed": fields["grabbed"] == "true",
                "yaw": yaw,
                "pitch": pitch,
                "playerPos": list(player_pos),
                "hitType": fields["hitType"],
                "hitBlock": fields["hitBlock"],
                "uiState": fields["uiState"],
                "transition": transition,
                "displayProfile": profile,
                "framebuffer": fields["framebuffer"],
                "guiScale": int(gui_scale),
                "language": fields["language"],
                "languageResolved": fields["blessingTitleResolved"] == "true",
                "languageMatch": fields["blessingTitleLanguageMatch"] == "true",
                "samples": samples,
                "p50Ms": p50,
                "p95Ms": p95,
                "p99Ms": p99,
                "maxMs": maximum,
                "framesOver33Ms": over,
                "framesOver100Ms": over_100,
                "slowFraction": (over / samples) if samples else None,
            }
        )
    return rows, errors


def latest_window(rows: list[dict[str, object]], transition: str) -> dict[str, object] | None:
    matches = [row for row in rows if row["transition"] == transition]
    if not matches:
        return None
    # One transition may be measured more than once. The newest requested
    # window is authoritative: otherwise an older 360-frame pass can hide a
    # later 180-frame regression from the same native-client run.
    return max(matches, key=lambda row: int(row["line"]))


def identity_failures(row: dict[str, object], args: argparse.Namespace) -> list[str]:
    reasons: list[str] = []
    if args.require_native_windows and row["nativeWindows"] is not True:
        reasons.append("acknowledgement is not from native Windows")
    if args.expected_jar_sha256 is not None \
            and row["runtimeJarSha256"] != args.expected_jar_sha256:
        reasons.append("runtime JAR hash differs from the exact candidate")
    if args.expected_session is not None and row["qaSession"] != args.expected_session:
        reasons.append("QA session differs from the current native run")
    if args.expected_profile is not None and row["displayProfile"] != args.expected_profile:
        reasons.append(
            f"display profile {row['displayProfile']} differs from {args.expected_profile}"
        )
    if (args.expected_game_directory_token is not None
            and row["gameDirectoryToken"] != args.expected_game_directory_token):
        reasons.append("game directory token differs from the expected native profile")
    if (args.expected_runtime_jar_path_token is not None
            and row["runtimeJarPathToken"] != args.expected_runtime_jar_path_token):
        reasons.append("runtime JAR path token differs from the installed JAR")
    if args.expected_world_path_token is not None \
            and row["worldPathToken"] != args.expected_world_path_token:
        reasons.append("world path token differs from the exact native save")
    if args.require_integrated_server and row["integratedServer"] is not True:
        reasons.append("acknowledgement is not bound to the integrated server")
    if row["languageResolved"] is not True or row["languageMatch"] is not True:
        reasons.append("selected language did not resolve to the expected Hearthstead text")
    return reasons


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("client_log", type=Path)
    parser.add_argument("--baseline")
    parser.add_argument("--require", action="append", default=[])
    parser.add_argument("--min-samples", type=int, default=180)
    parser.add_argument("--max-p95-ratio", type=float, default=1.35)
    parser.add_argument("--max-slow-rise", type=float, default=0.10)
    parser.add_argument("--max-p95-ms", type=float)
    parser.add_argument("--max-frame-ms", type=float)
    parser.add_argument("--max-frames-over100", type=int)
    parser.add_argument("--expected-jar-sha256")
    parser.add_argument("--expected-session")
    parser.add_argument("--expected-profile")
    parser.add_argument("--expected-game-directory-token")
    parser.add_argument("--expected-runtime-jar-path-token")
    parser.add_argument("--expected-world-path-token")
    parser.add_argument("--require-native-windows", action="store_true")
    parser.add_argument("--require-integrated-server", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    configuration_errors: list[str] = []
    if args.min_samples < 1:
        configuration_errors.append("--min-samples must be positive")
    if not math.isfinite(args.max_p95_ratio) or args.max_p95_ratio < 1.0:
        configuration_errors.append("--max-p95-ratio must be finite and at least 1.0")
    if not math.isfinite(args.max_slow_rise) or not 0.0 <= args.max_slow_rise <= 1.0:
        configuration_errors.append("--max-slow-rise must be between 0.0 and 1.0")
    if args.max_p95_ms is not None and (
        not math.isfinite(args.max_p95_ms) or args.max_p95_ms <= 0.0
    ):
        configuration_errors.append("--max-p95-ms must be finite and positive")
    if args.max_frame_ms is not None and (
        not math.isfinite(args.max_frame_ms) or args.max_frame_ms <= 0.0
    ):
        configuration_errors.append("--max-frame-ms must be finite and positive")
    if args.max_frames_over100 is not None and args.max_frames_over100 < 0:
        configuration_errors.append("--max-frames-over100 must be non-negative")
    if bool(args.baseline) != bool(args.require):
        configuration_errors.append(
            "--baseline and at least one --require must be used together"
        )
    if args.expected_jar_sha256 is not None \
            and SHA256.fullmatch(args.expected_jar_sha256) is None:
        configuration_errors.append("--expected-jar-sha256 must be a lowercase SHA-256")
    if args.expected_session is not None \
            and SESSION.fullmatch(args.expected_session) is None:
        configuration_errors.append("--expected-session is malformed")
    if args.expected_profile is not None \
            and re.fullmatch(r"[1-9][0-9]*x[1-9][0-9]*-gui[1-8]-(?:en_us|nb_no)",
                             args.expected_profile) is None:
        configuration_errors.append("--expected-profile is malformed")
    for name, value in (
        ("--expected-game-directory-token", args.expected_game_directory_token),
        ("--expected-runtime-jar-path-token", args.expected_runtime_jar_path_token),
        ("--expected-world-path-token", args.expected_world_path_token),
    ):
        if value is not None and SHA256.fullmatch(value) is None:
            configuration_errors.append(f"{name} must be a lowercase SHA-256")

    rows, parse_errors = parse_log(args.client_log)
    failures = configuration_errors + parse_errors
    checks: list[dict[str, object]] = []

    if not rows:
        failures.append("no valid HSQA_FRAME_ACK rows found")
    if (args.require_native_windows or args.require_integrated_server
            or args.expected_jar_sha256 is not None
            or args.expected_session is not None or args.expected_profile is not None
            or args.expected_game_directory_token is not None
            or args.expected_runtime_jar_path_token is not None
            or args.expected_world_path_token is not None):
        for row in rows:
            for reason in identity_failures(row, args):
                failures.append(f"line {row['line']}: {reason}")

    baseline = latest_window(rows, args.baseline) if args.baseline else None
    if args.baseline:
        if baseline is None:
            failures.append(f"missing baseline transition {args.baseline}")
        elif int(baseline["samples"]) < args.min_samples:
            failures.append(
                f"baseline {args.baseline} has {baseline['samples']} samples; "
                f"need {args.min_samples}"
            )
        elif float(baseline["p95Ms"]) <= 0.0 or baseline["slowFraction"] is None:
            failures.append(f"baseline {args.baseline} has no usable timing window")
        else:
            for reason in identity_failures(baseline, args):
                failures.append(f"baseline {args.baseline}: {reason}")
            if args.max_p95_ms is not None and float(baseline["p95Ms"]) > args.max_p95_ms:
                failures.append(
                    f"baseline {args.baseline} p95 {baseline['p95Ms']}ms exceeds "
                    f"{args.max_p95_ms}ms"
                )
            if args.max_frame_ms is not None and float(baseline["maxMs"]) > args.max_frame_ms:
                failures.append(
                    f"baseline {args.baseline} max {baseline['maxMs']}ms exceeds "
                    f"{args.max_frame_ms}ms"
                )
            if (args.max_frames_over100 is not None
                and int(baseline["framesOver100Ms"]) > args.max_frames_over100):
                failures.append(
                    f"baseline {args.baseline} has {baseline['framesOver100Ms']} frames "
                    f"over 100ms; allow {args.max_frames_over100}"
                )

    for transition in args.require:
        row = latest_window(rows, transition)
        check: dict[str, object] = {"transition": transition, "pass": False}
        if row is None:
            check["reason"] = "missing transition"
            failures.append(f"missing required transition {transition}")
            checks.append(check)
            continue
        check["window"] = row
        if int(row["samples"]) < args.min_samples:
            check["reason"] = (
                f"{row['samples']} samples; need {args.min_samples}"
            )
            failures.append(f"{transition}: {check['reason']}")
            checks.append(check)
            continue
        if baseline is None or int(baseline["samples"]) < args.min_samples:
            check["reason"] = "baseline unavailable"
            checks.append(check)
            continue

        p95_ratio = float(row["p95Ms"]) / float(baseline["p95Ms"])
        slow_rise = float(row["slowFraction"]) - float(baseline["slowFraction"])
        check["p95Ratio"] = round(p95_ratio, 4)
        check["slowFractionRise"] = round(slow_rise, 4)
        reasons: list[str] = []
        reasons.extend(identity_failures(row, args))
        if p95_ratio > args.max_p95_ratio:
            reasons.append(
                f"p95 ratio {p95_ratio:.3f} exceeds {args.max_p95_ratio:.3f}"
            )
        if slow_rise > args.max_slow_rise:
            reasons.append(
                f"slow-frame rise {slow_rise:.3f} exceeds {args.max_slow_rise:.3f}"
            )
        if args.max_p95_ms is not None and float(row["p95Ms"]) > args.max_p95_ms:
            reasons.append(
                f"p95 {row['p95Ms']}ms exceeds {args.max_p95_ms}ms"
            )
        if args.max_frame_ms is not None and float(row["maxMs"]) > args.max_frame_ms:
            reasons.append(
                f"max {row['maxMs']}ms exceeds {args.max_frame_ms}ms"
            )
        if (args.max_frames_over100 is not None
            and int(row["framesOver100Ms"]) > args.max_frames_over100):
            reasons.append(
                f"{row['framesOver100Ms']} frames over 100ms exceeds "
                f"{args.max_frames_over100}"
            )
        if reasons:
            check["reason"] = "; ".join(reasons)
            failures.append(f"{transition}: {check['reason']}")
        else:
            check["pass"] = True
            check["reason"] = "relative frame-time budget met"
        checks.append(check)

    try:
        client_log_sha256 = hashlib.sha256(args.client_log.read_bytes()).hexdigest()
    except OSError as exc:
        client_log_sha256 = None
        failures.append(f"cannot hash {args.client_log}: {exc}")

    report = {
        "clientLog": str(args.client_log.resolve()),
        "clientLogSha256": client_log_sha256,
        "acknowledgements": rows,
        "baseline": baseline,
        "checks": checks,
        "budgets": {
            "minSamples": args.min_samples,
            "maxP95Ratio": args.max_p95_ratio,
            "maxSlowFractionRise": args.max_slow_rise,
            "maxP95Ms": args.max_p95_ms,
            "maxFrameMs": args.max_frame_ms,
            "maxFramesOver100Ms": args.max_frames_over100,
        },
        "pass": not failures,
        "failures": failures,
    }
    rendered = json.dumps(report, indent=2, sort_keys=True)
    if args.output:
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            temporary = args.output.with_name(args.output.name + ".tmp")
            temporary.write_text(rendered + "\n", encoding="utf-8")
            temporary.replace(args.output)
        except OSError as exc:
            print(f"cannot write {args.output}: {exc}", file=sys.stderr)
            return 1
    print(rendered)
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
