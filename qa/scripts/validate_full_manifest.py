#!/usr/bin/env python3
"""Fail-closed validation for a published full-controller manifest pair."""

from __future__ import annotations

import json
import os
import stat
import sys
from typing import NoReturn


def fail(message: str) -> NoReturn:
    print(f"full manifest contract: {message}", file=sys.stderr)
    raise SystemExit(2)


def canonical_plain_directory(path: str, label: str) -> str:
    if not isinstance(path, str) or not os.path.isabs(path):
        fail(f"{label} is not an absolute path")
    absolute = os.path.abspath(path)
    if os.path.normpath(path) != path or os.path.realpath(path) != absolute:
        fail(f"{label} is non-canonical or contains a symlink")
    try:
        mode = os.lstat(path).st_mode
    except OSError as exc:
        fail(f"{label} is unavailable: {exc}")
    if not stat.S_ISDIR(mode) or stat.S_ISLNK(mode):
        fail(f"{label} is not a plain directory")
    return absolute


def canonical_plain_file(path: str, label: str) -> str:
    if not isinstance(path, str) or not os.path.isabs(path):
        fail(f"{label} is not an absolute path")
    absolute = os.path.abspath(path)
    if os.path.normpath(path) != path or os.path.realpath(path) != absolute:
        fail(f"{label} is non-canonical or contains a symlink")
    try:
        mode = os.lstat(path).st_mode
    except OSError as exc:
        fail(f"{label} is unavailable: {exc}")
    if not stat.S_ISREG(mode) or stat.S_ISLNK(mode):
        fail(f"{label} is not a plain regular file")
    return absolute


def load_json(path: str, label: str) -> dict[str, object]:
    try:
        with open(path, encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"{label} is unreadable or malformed: {exc}")
    if not isinstance(value, dict):
        fail(f"{label} root is not an object")
    return value


def main() -> int:
    if len(sys.argv) < 8:
        fail("internal invocation has too few arguments")
    latest_path, reports_arg, artifacts_arg, fingerprint, protocol, minimum_text = sys.argv[1:7]
    expected = set(sys.argv[7:])
    if not expected:
        fail("expected suite roster is empty")
    try:
        minimum_streak = int(minimum_text)
    except ValueError:
        fail("minimum streak is not an integer")
    if minimum_streak < 0:
        fail("minimum streak is negative")

    reports = canonical_plain_directory(reports_arg, "reports directory")
    artifacts_root = canonical_plain_directory(artifacts_arg, "artifacts root")
    if artifacts_root != os.path.join(reports, "artifacts"):
        fail("artifacts root is not the exact reports/artifacts directory")
    latest = canonical_plain_file(latest_path, "latest manifest")
    if latest != os.path.join(reports, "latest.json"):
        fail("latest manifest is outside the exact reports directory")

    published = load_json(latest, "latest manifest")
    run_arg = published.get("artifacts")
    if not isinstance(run_arg, str) or not run_arg:
        fail("artifacts path is missing")
    run_dir = canonical_plain_directory(run_arg, "run artifacts directory")
    try:
        contained = os.path.commonpath((artifacts_root, run_dir)) == artifacts_root
    except ValueError:
        contained = False
    if not contained or run_dir == artifacts_root:
        fail("run artifacts directory is outside the exact artifacts root")
    if run_arg != run_dir:
        fail("run artifacts path is not canonical")

    run_manifest_path = canonical_plain_file(
        os.path.join(run_dir, "manifest.json"), "run manifest"
    )
    run_manifest = load_json(run_manifest_path, "run manifest")
    if published != run_manifest:
        fail("latest and run manifests do not match exactly")

    suites = published.get("suites")
    streak = published.get("green_streak")
    if published.get("fingerprint") != fingerprint:
        fail("source fingerprint mismatch")
    if published.get("protocol_version") != protocol:
        fail("protocol version mismatch")
    if published.get("overall") != "PASS":
        fail("overall result is not PASS")
    if type(streak) is not int or not 0 <= streak <= 1_000_000:
        fail("green streak is not a bounded canonical integer")
    if streak < minimum_streak:
        fail(f"green streak {streak} is below required {minimum_streak}")
    if not isinstance(suites, dict) or set(suites) != expected:
        actual = set(suites) if isinstance(suites, dict) else set()
        fail(
            f"suite roster mismatch missing={sorted(expected - actual)} "
            f"extra={sorted(actual - expected)}"
        )
    for name, row in suites.items():
        if not isinstance(row, dict):
            fail(f"suite {name} row is not an object")
        allowed = {"PASS", "BLOCKED"} if name == "visual" else {"PASS"}
        if row.get("status") not in allowed:
            fail(f"suite {name} has invalid status {row.get('status')!r}")

    print(streak)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
