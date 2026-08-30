#!/usr/bin/env python3
"""Shared, fail-closed contracts for Hearthstead native input evidence.

This module deliberately contains no input-sending code.  The Windows driver,
the native-session sealing helper, and the release validator all import these
same schemas so a fixture cannot silently exercise a weaker vocabulary than
the production gate.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import stat
from pathlib import Path
from typing import NoReturn


SHA256 = re.compile(r"[0-9a-f]{64}")
SESSION = re.compile(r"[A-Za-z0-9_-]{8,80}")
LAUNCH_NONCE = re.compile(r"[A-Za-z0-9_-]{16,96}")
OPERATOR_ID = re.compile(r"[A-Za-z0-9_.@-]{3,80}")
PROFILE = re.compile(r"[1-9][0-9]*x[1-9][0-9]*-gui[1-8]-(?:en_us|nb_no)")

INPUT_DRIVER = "windows_sendinput_v3"
INPUT_RECORD_SCHEMA = 3
LAUNCH_REGISTRY_SCHEMA = 1
OPERATOR_SEAL_SCHEMA = 1
LAUNCH_REGISTRY_PATH = "logs/native-launch-registry.jsonl"
INPUT_TRANSCRIPT_PATH = "logs/native-input.jsonl"
LOG_SEGMENT_INDEX_PATH = "logs/native-log-segments.json"

ACTIVATION_MARKER = "HSQA_OBSERVER_ENABLED"
ACTIVATION_FIELDS = (
    "source",
    "qaSession",
    "markerConsumed",
    "markerCreatedEpochSeconds",
    "markerExpiresEpochSeconds",
)
ACK_MARKER = "HSQA_FRAME_ACK"
ACK_FIELDS = (
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
    "auxButtonBound",
    "yaw",
    "pitch",
    "playerPos",
    "hitType",
    "hitBlock",
    "guiScale",
    "gui",
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
)

LAUNCH_IDENTITY_FIELDS = (
    "launchIndex",
    "nativeSessionId",
    "launchNonce",
    "pid",
    "processCreationEpochMillis",
    "processPath",
    "gameDirectory",
    "runtimeJarPath",
    "runtimeJarSha256",
    "worldDirectory",
    "displayProfile",
    "observerLogSegment",
)
LAUNCH_RECORD_FIELDS = (
    "schemaVersion",
    *LAUNCH_IDENTITY_FIELDS,
    "registeredEpochMillis",
    "launchIdentitySha256",
    "previousChainSha256",
    "chainSha256",
)

SEAL_COMPONENT_FIELDS = (
    "path",
    "sha256",
    "recordCount",
    "tailSha256",
)
SEAL_RECORD_FIELDS = (
    "schemaVersion",
    "sequence",
    "nativeSessionId",
    "operatorId",
    "operatorNonce",
    "capturedEpochMillis",
    "runDirectoryToken",
    "inputTranscript",
    "launchRegistry",
    "nativeLogSegments",
    "previousSealSha256",
    "sealSha256",
    "hmacSha256",
)

# Exact Log4j provenance used by QaClientObserver.  A message containing these
# words elsewhere, a forged thread suffix, or a different logger is not an ACK.
LOG_PREFIX = re.compile(
    r"^\[[^\]\r\n]{1,96}\] \[Render thread/INFO\] \[hearthstead/\]: "
)
SAFE_LOG_VALUE = re.compile(r"[^\s=\x00-\x1f\x7f]{1,512}")


class EvidenceContractError(RuntimeError):
    """One shared native-evidence contract was violated."""


def fail(message: str) -> NoReturn:
    raise EvidenceContractError(message)


def canonical_json(value: object) -> bytes:
    return json.dumps(
        value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("ascii")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_absolute_path(value: str, label: str, *, host_os_name: str | None = None) -> Path:
    """Translate one strict native absolute path into this host's path syntax.

    Native capture helpers run on Windows and therefore seal drive-qualified
    paths.  The canonical controller validates those records from WSL.  A raw
    ``Path(r"C:\\...")`` is relative under POSIX, so it must be translated
    *before* the existing physical-file/reparse checks.  No other foreign path
    form is accepted: UNC/device paths, drive-relative paths, traversal,
    malformed separators and control characters fail closed.
    """
    if not isinstance(value, str) or not value or value != value.strip() \
            or any(ord(character) < 32 or ord(character) == 127
                   for character in value):
        fail(f"{label} is not one strict absolute path")
    platform = os.name if host_os_name is None else host_os_name
    if platform not in {"nt", "posix"}:
        fail(f"{label} cannot be resolved on unsupported host {platform!r}")

    # Reject UNC and Windows device namespaces before considering separators.
    if value.startswith(("\\\\", "//")):
        fail(f"{label} uses a forbidden UNC/device path")

    windows = re.fullmatch(r"([A-Za-z]):([\\/])(.*)", value)
    if windows is not None:
        drive, separator, remainder = windows.groups()
        if not remainder or ("/" in remainder and "\\" in remainder):
            fail(f"{label} has malformed Windows separators")
        if separator == "\\" and "/" in remainder \
                or separator == "/" and "\\" in remainder:
            fail(f"{label} has mixed Windows separators")
        components = remainder.split(separator)
        if (any(component in {"", ".", ".."} for component in components)
                or any(component.endswith((" ", ".")) for component in components)
                or any(re.search(r'[<>:"|?*]', component) is not None
                       for component in components)):
            fail(f"{label} has unsafe Windows path components")
        if platform == "posix":
            return Path("/mnt") / drive.lower() / Path(*components)
        return Path(f"{drive.upper()}:\\" + "\\".join(components))

    path = Path(value)
    if platform != "posix" or not path.is_absolute():
        fail(f"{label} is not an absolute native or Windows-drive path")
    if any(component in {".", ".."} for component in path.parts):
        fail(f"{label} contains traversal components")
    return path


def canonical_path_text(path: Path) -> str:
    rendered = path.resolve(strict=True).as_posix()
    wsl = re.fullmatch(r"/mnt/([A-Za-z])/(.*)", rendered)
    if wsl is not None:
        rendered = f"{wsl.group(1)}:/{wsl.group(2)}"
    if re.match(r"^[A-Za-z]:/", rendered):
        rendered = rendered.lower()
    return rendered


def path_token(path: Path) -> str:
    return sha256_bytes(canonical_path_text(path).encode("utf-8"))


def is_reparse_point(metadata: os.stat_result) -> bool:
    return bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def require_plain_path(path: Path, label: str, *, directory: bool,
                       nonempty: bool = False) -> Path:
    """Reject symlinks/reparse points in every existing path component."""
    absolute = path.absolute()
    parts = absolute.parts
    if not parts:
        fail(f"{label} has no path components")
    cursor = Path(parts[0])
    for component in parts[1:]:
        cursor /= component
        try:
            metadata = cursor.lstat()
        except OSError as exc:
            fail(f"{label} is unavailable: {exc}")
        if cursor.is_symlink() or is_reparse_point(metadata):
            fail(f"{label} crosses a symlink or Windows reparse point")
    try:
        metadata = absolute.lstat()
    except OSError as exc:
        fail(f"{label} is unavailable: {exc}")
    expected = stat.S_ISDIR(metadata.st_mode) if directory \
        else stat.S_ISREG(metadata.st_mode)
    if not expected:
        fail(f"{label} is not a plain {'directory' if directory else 'regular file'}")
    if nonempty and metadata.st_size < 1:
        fail(f"{label} is empty")
    return absolute.resolve(strict=True)


def parse_fixed_log_record(line: str, marker: str,
                           fields: tuple[str, ...]) -> dict[str, str] | None:
    """Parse one exact ordered Log4j record, or return None when absent."""
    if marker not in line:
        return None
    if line.count(marker) != 1:
        fail(f"{marker} line repeats its marker")
    prefix = LOG_PREFIX.match(line)
    if prefix is None:
        fail(f"{marker} lacks exact Render thread/hearthstead Log4j provenance")
    remainder = line[prefix.end():]
    marker_prefix = marker + " "
    if not remainder.startswith(marker_prefix):
        fail(f"{marker} is not at the start of the Hearthstead log message")
    tokens = remainder[len(marker_prefix):].split(" ")
    if len(tokens) != len(fields) or any(token == "" for token in tokens):
        fail(f"{marker} has missing, extra, or empty tokens")
    parsed: dict[str, str] = {}
    encountered: list[str] = []
    for token in tokens:
        if token.count("=") != 1:
            fail(f"{marker} contains a malformed key/value token")
        key, value = token.split("=", 1)
        if key in parsed:
            fail(f"{marker} repeats field {key}")
        if SAFE_LOG_VALUE.fullmatch(value) is None:
            fail(f"{marker} field {key} has an unsafe value")
        parsed[key] = value
        encountered.append(key)
    if tuple(encountered) != fields:
        fail(f"{marker} fields are missing, extra, duplicated, or reordered")
    return parsed


def launch_identity_sha256(record: dict[str, object]) -> str:
    identity = {field: record.get(field) for field in LAUNCH_IDENTITY_FIELDS}
    return sha256_bytes(canonical_json(identity))


def chained_record_sha256(previous: str, record: dict[str, object],
                          hash_field: str) -> str:
    unsigned = dict(record)
    unsigned.pop(hash_field, None)
    return sha256_bytes(previous.encode("ascii") + canonical_json(unsigned))


def parse_launch_registry(path: Path, expected_session: str | None = None) \
        -> tuple[list[dict[str, object]], str]:
    registry = require_plain_path(path, "native launch registry", directory=False,
                                  nonempty=True)
    try:
        lines = registry.read_text(encoding="utf-8", errors="strict").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"native launch registry is unreadable: {exc}")
    if not 1 <= len(lines) <= 256:
        fail("native launch registry must contain 1..256 records")
    previous = "0" * 64
    launches: list[dict[str, object]] = []
    seen_pids: set[int] = set()
    seen_nonces: set[str] = set()
    previous_creation = -1
    previous_registered = -1
    for position, line in enumerate(lines, 1):
        label = f"native launch registry record {position}"
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            fail(f"{label} is malformed JSON: {exc}")
        if not isinstance(record, dict) or tuple(sorted(record)) != tuple(sorted(LAUNCH_RECORD_FIELDS)):
            fail(f"{label} has an unexpected field set")
        if record.get("schemaVersion") != LAUNCH_REGISTRY_SCHEMA:
            fail(f"{label} has an unsupported schema")
        if record.get("launchIndex") != position:
            fail(f"{label} launchIndex is not contiguous")
        session = record.get("nativeSessionId")
        if not isinstance(session, str) or SESSION.fullmatch(session) is None:
            fail(f"{label} has an invalid session")
        if expected_session is not None and session != expected_session:
            fail(f"{label} belongs to another native session")
        nonce = record.get("launchNonce")
        if not isinstance(nonce, str) or LAUNCH_NONCE.fullmatch(nonce) is None \
                or nonce in seen_nonces:
            fail(f"{label} has an invalid or repeated launch nonce")
        seen_nonces.add(nonce)
        pid = record.get("pid")
        creation = record.get("processCreationEpochMillis")
        registered = record.get("registeredEpochMillis")
        if (type(pid) is not int or pid <= 0 or pid in seen_pids
                or type(creation) is not int or creation <= previous_creation
                or type(registered) is not int or registered < creation
                or registered <= previous_registered):
            fail(f"{label} does not prove one unique, ordered process launch")
        seen_pids.add(pid)
        previous_creation = creation
        previous_registered = registered
        for field in ("processPath", "gameDirectory", "runtimeJarPath", "worldDirectory"):
            value = record.get(field)
            if not isinstance(value, str):
                fail(f"{label} {field} is not an absolute path")
            local_absolute_path(value, f"{label} {field}")
        if SHA256.fullmatch(str(record.get("runtimeJarSha256", ""))) is None:
            fail(f"{label} has an invalid runtime JAR hash")
        profile = record.get("displayProfile")
        if not isinstance(profile, str) or PROFILE.fullmatch(profile) is None:
            fail(f"{label} has an invalid display profile")
        expected_segment = (
            f"logs/native-{session}-segment-{position:04d}-"
            f"{profile.replace('-', '_')}.log"
        )
        if record.get("observerLogSegment") != expected_segment:
            fail(f"{label} observer log segment is not canonical")
        identity_hash = record.get("launchIdentitySha256")
        if identity_hash != launch_identity_sha256(record):
            fail(f"{label} launch identity hash differs")
        if record.get("previousChainSha256") != previous:
            fail(f"{label} breaks the previous-hash link")
        declared = record.get("chainSha256")
        if not isinstance(declared, str) or SHA256.fullmatch(declared) is None:
            fail(f"{label} has an invalid chain hash")
        actual = chained_record_sha256(previous, record, "chainSha256")
        if declared != actual:
            fail(f"{label} hash differs")
        previous = declared
        launches.append(record)
    return launches, previous


def operator_seal_hmac(key: bytes, record: dict[str, object]) -> str:
    unsigned = dict(record)
    unsigned.pop("hmacSha256", None)
    return hmac.new(key, canonical_json(unsigned), hashlib.sha256).hexdigest()


def parse_operator_seal_ledger(path: Path, key: bytes) \
        -> list[dict[str, object]]:
    if len(key) < 32:
        fail("operator seal key is shorter than 32 bytes")
    ledger = require_plain_path(path, "operator input-seal ledger", directory=False,
                                nonempty=True)
    try:
        lines = ledger.read_text(encoding="utf-8", errors="strict").splitlines()
    except (OSError, UnicodeError) as exc:
        fail(f"operator input-seal ledger is unreadable: {exc}")
    if not 1 <= len(lines) <= 10_000:
        fail("operator input-seal ledger must contain 1..10000 records")
    previous = "0" * 64
    records: list[dict[str, object]] = []
    seen_sessions: set[str] = set()
    previous_captured = -1
    for position, line in enumerate(lines, 1):
        label = f"operator input seal {position}"
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            fail(f"{label} is malformed JSON: {exc}")
        if not isinstance(record, dict) or set(record) != set(SEAL_RECORD_FIELDS):
            fail(f"{label} has an unexpected field set")
        if record.get("schemaVersion") != OPERATOR_SEAL_SCHEMA \
                or record.get("sequence") != position:
            fail(f"{label} has an unsupported schema or non-contiguous sequence")
        session = record.get("nativeSessionId")
        if not isinstance(session, str) or SESSION.fullmatch(session) is None \
                or session in seen_sessions:
            fail(f"{label} has an invalid or repeated session")
        seen_sessions.add(session)
        if not isinstance(record.get("operatorId"), str) \
                or OPERATOR_ID.fullmatch(str(record["operatorId"])) is None:
            fail(f"{label} has an invalid operatorId")
        if re.fullmatch(r"[0-9a-f]{64}", str(record.get("operatorNonce", ""))) is None:
            fail(f"{label} has an invalid operator nonce")
        captured = record.get("capturedEpochMillis")
        if type(captured) is not int or captured <= previous_captured:
            fail(f"{label} captured timestamp is not strictly ordered")
        previous_captured = captured
        if SHA256.fullmatch(str(record.get("runDirectoryToken", ""))) is None:
            fail(f"{label} has an invalid run-directory token")
        for component in ("inputTranscript", "launchRegistry"):
            value = record.get(component)
            if not isinstance(value, dict) or set(value) != set(SEAL_COMPONENT_FIELDS):
                fail(f"{label} {component} has an unexpected field set")
            if (not isinstance(value.get("path"), str)
                    or SHA256.fullmatch(str(value.get("sha256", ""))) is None
                    or type(value.get("recordCount")) is not int
                    or int(value["recordCount"]) < 1
                    or SHA256.fullmatch(str(value.get("tailSha256", ""))) is None):
                fail(f"{label} {component} is malformed")
        segments = record.get("nativeLogSegments")
        if not isinstance(segments, dict) or set(segments) != {"path", "sha256"} \
                or not isinstance(segments.get("path"), str) \
                or SHA256.fullmatch(str(segments.get("sha256", ""))) is None:
            fail(f"{label} nativeLogSegments is malformed")
        if record.get("previousSealSha256") != previous:
            fail(f"{label} breaks the append-only seal chain")
        declared = record.get("sealSha256")
        if not isinstance(declared, str) or SHA256.fullmatch(declared) is None:
            fail(f"{label} has an invalid seal hash")
        unsigned = dict(record)
        unsigned.pop("hmacSha256", None)
        unsigned.pop("sealSha256", None)
        actual = sha256_bytes(previous.encode("ascii") + canonical_json(unsigned))
        if declared != actual:
            fail(f"{label} seal hash differs")
        supplied_hmac = record.get("hmacSha256")
        if not isinstance(supplied_hmac, str) or SHA256.fullmatch(supplied_hmac) is None \
                or not hmac.compare_digest(supplied_hmac,
                                           operator_seal_hmac(key, record)):
            fail(f"{label} operator HMAC differs")
        previous = declared
        records.append(record)
    return records
