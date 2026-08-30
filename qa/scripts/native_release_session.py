#!/usr/bin/env python3
"""Safely arm and seal Hearthstead's native release-client observer session.

The production CLI is intentionally pinned to the exact CurseForge profile.
Tests call the pure functions with an isolated expected directory; there is no
CLI flag that can redirect production marker creation to an arbitrary path.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import stat
import sys
import time
from pathlib import Path
from typing import Mapping, NoReturn

from native_evidence_contract import (
    ACK_FIELDS,
    ACK_MARKER,
    ACTIVATION_FIELDS,
    ACTIVATION_MARKER,
    EvidenceContractError,
    INPUT_TRANSCRIPT_PATH,
    LAUNCH_REGISTRY_PATH,
    LAUNCH_REGISTRY_SCHEMA,
    LOG_SEGMENT_INDEX_PATH,
    OPERATOR_ID,
    PROFILE as CONTRACT_PROFILE,
    SEAL_RECORD_FIELDS,
    SESSION as CONTRACT_SESSION,
    SHA256 as CONTRACT_SHA256,
    canonical_json,
    chained_record_sha256,
    launch_identity_sha256,
    operator_seal_hmac,
    parse_fixed_log_record,
    parse_launch_registry,
    parse_operator_seal_ledger,
    path_token as contract_path_token,
    require_plain_path,
    sha256_bytes,
    sha256_file,
)


EXACT_GAME_DIRECTORY = Path(
    r"C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)"
)
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
RELEASE_RUN_ROOT = REPOSITORY_ROOT / "qa" / "reports" / "artifacts" / "release-client"
MARKER_NAME = "hsqa-native-observer-once.txt"
MARKER_HEADER = "HEARTHSTEAD_NATIVE_QA_V2"
SEGMENT_INDEX_NAME = "native-log-segments.json"
SESSION = re.compile(r"[A-Za-z0-9_-]{8,80}")
PROFILE = re.compile(r"[1-9][0-9]*x[1-9][0-9]*-gui[1-8]-(?:en_us|nb_no)")
SHA256 = re.compile(r"[0-9a-f]{64}")
SERVER_AUTHORITY = re.compile(
    r"\[Server thread/INFO\]\s+\[hearthstead(?:/[^\]]*)?\]", re.IGNORECASE
)
MAX_MARKER_AGE_SECONDS = 300
MAX_MARKER_TTL_SECONDS = 3600
MIN_MARKER_TTL_SECONDS = 60
MAX_CLOCK_SKEW_SECONDS = 300
MAX_LOG_AGE_SECONDS = 24 * 60 * 60


class NativeSessionError(RuntimeError):
    """A fail-closed native-session precondition was not met."""


def fail(message: str) -> NoReturn:
    raise NativeSessionError(message)


def is_reparse_point(metadata: os.stat_result) -> bool:
    return bool(
        getattr(metadata, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def plain_directory(path: Path, label: str) -> Path:
    try:
        return require_plain_path(path, label, directory=True)
    except EvidenceContractError as exc:
        fail(str(exc))


def plain_file(path: Path, label: str) -> Path:
    try:
        return require_plain_path(path, label, directory=False, nonempty=True)
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


def exact_game_directory(game_directory: Path, expected: Path) -> Path:
    actual = plain_directory(game_directory, "native game directory")
    required = plain_directory(expected, "expected CurseForge game directory")
    try:
        matches = os.path.samefile(actual, required)
    except OSError as exc:
        fail(f"cannot compare native game directory identity: {exc}")
    if not matches:
        fail("native game directory is not the exact expected CurseForge profile")
    return actual


def production_run_directory(path: Path) -> Path:
    run = plain_directory(path, "release-client run directory")
    root = plain_directory(RELEASE_RUN_ROOT, "release-client artifact root")
    try:
        direct_child = os.path.samefile(run.parent, root)
    except OSError as exc:
        fail(f"cannot compare release-client run identity: {exc}")
    if not direct_child:
        fail("run directory is not a direct child of the canonical release-client root")
    return run


def valid_session(value: str) -> str:
    if SESSION.fullmatch(value) is None:
        fail("session must contain 8-80 ASCII letters, digits, underscores or hyphens")
    return value


def marker_payload(session: str, created: int, expires: int) -> bytes:
    valid_session(session)
    return (
        f"{MARKER_HEADER}\n"
        f"session={session}\n"
        f"createdEpochSeconds={created}\n"
        f"expiresEpochSeconds={expires}\n"
    ).encode("ascii")


def validate_marker_payload(payload: bytes, session: str, now: int) -> tuple[int, int]:
    valid_session(session)
    if not 1 <= len(payload) <= 256:
        fail("marker is empty or larger than 256 bytes")
    try:
        text = payload.decode("ascii")
    except UnicodeDecodeError as exc:
        fail(f"marker is not strict ASCII: {exc}")
    lines = text.splitlines()
    if len(lines) != 4 or lines[0] != MARKER_HEADER:
        fail("marker does not have the exact V2 four-line shape")
    expected_prefixes = (
        "session=", "createdEpochSeconds=", "expiresEpochSeconds=",
    )
    if any(not line.startswith(prefix)
           for line, prefix in zip(lines[1:], expected_prefixes)):
        fail("marker keys or order differ from the V2 contract")
    if lines[1][len("session="):] != session:
        fail("marker session differs from the supplied native session")
    try:
        created = int(lines[2][len("createdEpochSeconds="):])
        expires = int(lines[3][len("expiresEpochSeconds="):])
    except ValueError:
        fail("marker timestamps are not base-10 integers")
    if created > now or now - created > MAX_MARKER_AGE_SECONDS:
        fail("marker creation time is future-dated or stale")
    if expires <= now or expires - created > MAX_MARKER_TTL_SECONDS:
        fail("marker expiry is elapsed or exceeds the V2 one-hour ceiling")
    return created, expires


def create_marker(
    game_directory: Path,
    expected_game_directory: Path,
    session: str,
    *,
    ttl_seconds: int = 900,
    now_epoch_seconds: int | None = None,
    environment: Mapping[str, str] | None = None,
) -> dict[str, object]:
    """Create one fresh marker atomically, refusing replacement or ambiguity."""
    directory = exact_game_directory(game_directory, expected_game_directory)
    valid_session(session)
    if not MIN_MARKER_TTL_SECONDS <= ttl_seconds <= MAX_MARKER_TTL_SECONDS:
        fail("marker TTL must be between 60 and 3600 seconds")
    active_environment = os.environ if environment is None else environment
    if (active_environment.get("HSQA_CLIENT_OBSERVER") is not None
            or active_environment.get("HSQA_CLIENT_SESSION") is not None):
        fail("environment activation is present; refusing ambiguous marker activation")
    now = int(time.time()) if now_epoch_seconds is None else now_epoch_seconds
    payload = marker_payload(session, now, now + ttl_seconds)
    marker = directory / MARKER_NAME
    if marker.exists() or marker.is_symlink():
        fail("an activation marker already exists; inspect/remove it before re-arming")
    try:
        with marker.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    except FileExistsError:
        fail("activation marker appeared concurrently; refusing to overwrite it")
    except OSError as exc:
        fail(f"could not create activation marker atomically: {exc}")
    try:
        resolved = plain_file(marker, "created activation marker")
        if resolved.parent != directory:
            fail("created activation marker escaped the exact game directory")
        actual = resolved.read_bytes()
        created, expires = validate_marker_payload(actual, session, now)
        if actual != payload:
            fail("created activation marker bytes changed during verification")
    except BaseException:
        try:
            if marker.exists() and not marker.is_symlink():
                marker.unlink()
        except OSError:
            pass
        raise
    return {
        "status": "CREATED",
        "session": session,
        "createdEpochSeconds": created,
        "expiresEpochSeconds": expires,
        "marker": str(marker),
    }


def path_token(path: Path) -> str:
    return contract_path_token(path)


def parse_native_log(
    payload: bytes, session: str, now_epoch_millis: int,
) -> dict[str, object]:
    try:
        text = payload.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        fail(f"latest.log is not strict UTF-8: {exc}")
    if "\x00" in text:
        fail("latest.log contains NUL bytes")
    enabled = []
    acknowledgements: list[dict[str, str]] = []
    server_authority_lines = 0
    for line_number, line in enumerate(text.splitlines(), 1):
        try:
            activation = parse_fixed_log_record(
                line, ACTIVATION_MARKER, ACTIVATION_FIELDS
            )
            acknowledgement = parse_fixed_log_record(
                line, ACK_MARKER, ACK_FIELDS
            )
        except EvidenceContractError as exc:
            fail(f"latest.log line {line_number}: {exc}")
        if activation is not None:
            enabled.append(activation)
        if acknowledgement is not None:
            acknowledgements.append(acknowledgement)
        if SERVER_AUTHORITY.search(line) is not None:
            server_authority_lines += 1
    if len(enabled) != 1:
        fail("latest.log must contain exactly one observer-enabled record")
    activation = enabled[0]
    source = activation.get("source")
    consumed = activation.get("markerConsumed")
    if activation.get("qaSession") != session:
        fail("observer-enabled record belongs to another session")
    if source not in {"environment", "one_shot_marker"}:
        fail("observer-enabled record has an invalid activation source")
    if (source == "one_shot_marker" and consumed != "true") \
            or (source == "environment" and consumed != "false"):
        fail("observer-enabled marker-consumption state is inconsistent")
    if not acknowledgements:
        fail("latest.log contains no native frame acknowledgement")
    identity_fields = (
        "runtimeJarSha256", "gameDirectoryToken", "runtimeJarPathToken",
        "worldPathToken",
    )
    identities: dict[str, set[str]] = {field: set() for field in identity_fields}
    observed_values: list[int] = []
    observed_profiles: set[str] = set()
    for index, fields in enumerate(acknowledgements, 1):
        if fields.get("qaSession") != session:
            fail(f"frame acknowledgement {index} belongs to another session")
        if fields.get("observerSource") != source:
            fail(f"frame acknowledgement {index} activation source differs")
        if fields.get("nativeWindows") != "true" \
                or fields.get("integratedServer") != "true":
            fail(f"frame acknowledgement {index} is not native integrated-client evidence")
        if fields.get("runtimeJarSource") not in {"code_source", "mod_list"}:
            fail(f"frame acknowledgement {index} has no installed runtime-JAR source")
        for field in identity_fields:
            value = fields.get(field, "")
            if SHA256.fullmatch(value) is None:
                fail(f"frame acknowledgement {index} has invalid {field}")
            identities[field].add(value)
        try:
            observed = int(fields.get("observedEpochMillis", ""))
        except ValueError:
            fail(f"frame acknowledgement {index} has invalid observedEpochMillis")
        if (observed > now_epoch_millis + MAX_CLOCK_SKEW_SECONDS * 1000
                or now_epoch_millis - observed > MAX_LOG_AGE_SECONDS * 1000):
            fail(f"frame acknowledgement {index} is future-dated or stale")
        observed_values.append(observed)
        framebuffer = fields.get("framebuffer", "")
        gui_scale = fields.get("guiScale", "")
        language = fields.get("language", "")
        if re.fullmatch(r"[1-9][0-9]*x[1-9][0-9]*", framebuffer) is None \
                or re.fullmatch(r"[1-8](?:\.0)?", gui_scale) is None \
                or language not in {"en_us", "nb_no"}:
            fail(f"frame acknowledgement {index} has an invalid display profile")
        observed_profiles.add(
            f"{framebuffer}-gui{int(float(gui_scale))}-{language}"
        )
    for field, values in identities.items():
        if len(values) != 1:
            fail(f"latest.log mixes {field} identities")
    marker_created: int | None = None
    marker_expires: int | None = None
    if source == "one_shot_marker":
        try:
            marker_created = int(activation.get("markerCreatedEpochSeconds", ""))
            marker_expires = int(activation.get("markerExpiresEpochSeconds", ""))
        except ValueError:
            fail("observer-enabled record has invalid marker timestamps")
        first_observed_seconds = min(observed_values) // 1000
        if (marker_created > first_observed_seconds
                or first_observed_seconds - marker_created > MAX_MARKER_AGE_SECONDS
                or marker_expires <= first_observed_seconds
                or marker_expires - marker_created > MAX_MARKER_TTL_SECONDS):
            fail("consumed observer marker was stale, future-dated or overlong")
    elif (activation.get("markerCreatedEpochSeconds") != "none"
          or activation.get("markerExpiresEpochSeconds") != "none"):
        fail("environment activation unexpectedly reports marker timestamps")
    return {
        "observerActivationSource": source,
        "markerConsumed": consumed == "true",
        "ackCount": len(acknowledgements),
        "serverAuthorityLineCount": server_authority_lines,
        "firstObservedEpochMillis": min(observed_values),
        "lastObservedEpochMillis": max(observed_values),
        "observedDisplayProfiles": sorted(observed_profiles),
        "markerCreatedEpochSeconds": marker_created,
        "markerExpiresEpochSeconds": marker_expires,
        **{field: next(iter(values)) for field, values in identities.items()},
    }


def atomic_json_replace(path: Path, value: object) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    if temporary.exists() or temporary.is_symlink():
        fail(f"temporary index path already exists: {temporary.name}")
    payload = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
    try:
        with temporary.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        plain_file(temporary, "temporary segment index")
        os.replace(temporary, path)
    finally:
        if temporary.exists() and not temporary.is_symlink():
            temporary.unlink()


def seal_log_segment(
    game_directory: Path,
    expected_game_directory: Path,
    run_directory: Path,
    session: str,
    display_profile: str,
    *,
    now_epoch_millis: int | None = None,
) -> dict[str, object]:
    """Copy one unmodified latest.log segment and append its immutable seal."""
    game = exact_game_directory(game_directory, expected_game_directory)
    run = plain_directory(run_directory, "release-client run directory")
    logs = plain_directory(run / "logs", "release-client logs directory")
    if logs.parent != run:
        fail("release-client logs directory is not a direct child of the run")
    valid_session(session)
    if PROFILE.fullmatch(display_profile) is None:
        fail("display profile does not match the native release matrix grammar")
    source = plain_file(game / "logs" / "latest.log", "native latest.log")
    if source.parent != plain_directory(game / "logs", "native logs directory"):
        fail("latest.log is not directly inside the exact profile logs directory")
    now = int(time.time() * 1000) if now_epoch_millis is None else now_epoch_millis
    metadata = source.stat()
    modified = int(metadata.st_mtime * 1000)
    if (modified > now + MAX_CLOCK_SKEW_SECONDS * 1000
            or now - modified > MAX_LOG_AGE_SECONDS * 1000):
        fail("native latest.log modification time is future-dated or stale")
    payload = source.read_bytes()
    parsed = parse_native_log(payload, session, now)
    if parsed["observedDisplayProfiles"] != [display_profile]:
        fail("latest.log ACKs do not belong exclusively to the declared display profile")

    index_path = logs / SEGMENT_INDEX_NAME
    if index_path.exists() or index_path.is_symlink():
        existing = plain_file(index_path, "native log segment index")
        try:
            index = json.loads(existing.read_text(encoding="utf-8", errors="strict"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            fail(f"native log segment index is malformed: {exc}")
        if not isinstance(index, dict) or index.get("schemaVersion") != 1:
            fail("native log segment index has an unsupported schema")
        if index.get("nativeSessionId") != session:
            fail("native log segment index belongs to another session")
        if index.get("gameDirectoryToken") != path_token(game):
            fail("native log segment index belongs to another game directory")
        segments = index.get("segments")
        if not isinstance(segments, list):
            fail("native log segment index has no segment list")
    else:
        index = {
            "schemaVersion": 1,
            "nativeSessionId": session,
            "gameDirectoryToken": path_token(game),
            "segments": [],
        }
        segments = index["segments"]
    assert isinstance(segments, list)
    source_hash = sha256_bytes(payload)
    for prior_position, prior in enumerate(segments, 1):
        if not isinstance(prior, dict) or prior.get("index") != prior_position:
            fail("native log segment index is non-contiguous or malformed")
        prior_path = logs / str(prior.get("path", ""))
        sealed = plain_file(prior_path, f"sealed native log segment {prior_position}")
        if sealed.parent != logs or sha256_bytes(sealed.read_bytes()) != prior.get("sha256"):
            fail(f"sealed native log segment {prior_position} changed after sealing")
        if prior.get("sha256") == source_hash:
            fail("latest.log is byte-identical to an already sealed segment")

    position = len(segments) + 1
    safe_profile = display_profile.replace("-", "_")
    destination_name = f"native-{session}-segment-{position:04d}-{safe_profile}.log"
    destination = logs / destination_name
    if destination.exists() or destination.is_symlink():
        fail("sealed segment destination already exists")
    try:
        with destination.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        sealed = plain_file(destination, "new sealed native log segment")
        if sealed.parent != logs or sealed.read_bytes() != payload:
            fail("sealed native log bytes differ from latest.log")
        previous_sealed = int(segments[-1]["sealedEpochMillis"]) if segments else -1
        sealed_at = max(now, previous_sealed + 1)
        record = {
            "index": position,
            "path": destination.name,
            "sha256": source_hash,
            "sizeBytes": len(payload),
            "sourceRelativePath": "logs/latest.log",
            "sourceModifiedEpochMillis": modified,
            "sealedEpochMillis": sealed_at,
            "displayProfile": display_profile,
            **parsed,
        }
        segments.append(record)
        atomic_json_replace(index_path, index)
    except BaseException:
        if destination.exists() and not destination.is_symlink():
            destination.unlink()
        raise
    return {
        "status": "SEALED",
        "segment": record,
        "indexPath": str(index_path),
        "indexSha256": sha256_bytes(index_path.read_bytes()),
    }


def append_launch_registry(
    run_directory: Path,
    session: str,
    launch_nonce: str,
    pid: int,
    process_creation_epoch_millis: int,
    process_image_path: Path,
    game_directory: Path,
    expected_game_directory: Path,
    runtime_jar: Path,
    world_directory: Path,
    display_profile: str,
    *,
    registered_epoch_millis: int | None = None,
) -> dict[str, object]:
    """Append one independently observed live launch to its hash chain."""
    run = plain_directory(run_directory, "release-client run directory")
    logs = plain_directory(run / "logs", "release-client logs directory")
    if logs.parent != run:
        fail("release-client logs directory is not a direct child of the run")
    valid_session(session)
    if CONTRACT_PROFILE.fullmatch(display_profile) is None:
        fail("display profile does not match the native release matrix grammar")
    if not isinstance(pid, int) or pid <= 0:
        fail("launch PID is not a positive integer")
    if not isinstance(process_creation_epoch_millis, int) \
            or process_creation_epoch_millis <= 0:
        fail("launch process creation time is invalid")
    if re.fullmatch(r"[A-Za-z0-9_-]{16,96}", launch_nonce) is None:
        fail("launch nonce must contain 16-96 ASCII letters, digits, _ or -")

    game = exact_game_directory(game_directory, expected_game_directory)
    image = plain_file(process_image_path, "live Java process image")
    runtime = plain_file(runtime_jar, "installed runtime JAR")
    world = plain_directory(world_directory, "native world directory")
    if runtime.parent != plain_directory(game / "mods", "native mods directory"):
        fail("installed runtime JAR is not directly inside the exact profile mods")
    if world.parent != plain_directory(game / "saves", "native saves directory"):
        fail("native world is not directly inside the exact profile saves")
    if image.name.lower() not in {"java.exe", "javaw.exe"}:
        fail("live process image is not an exact java/javaw executable path")

    registry_path = logs / Path(LAUNCH_REGISTRY_PATH).name
    if registry_path.exists() or registry_path.is_symlink():
        try:
            prior, previous = parse_launch_registry(registry_path, session)
        except EvidenceContractError as exc:
            fail(str(exc))
    else:
        prior = []
        previous = "0" * 64
    now = int(time.time() * 1000) if registered_epoch_millis is None \
        else registered_epoch_millis
    if now < process_creation_epoch_millis:
        fail("launch registration predates the process creation time")
    if prior:
        if now <= int(prior[-1]["registeredEpochMillis"]):
            fail("launch registration time does not follow the prior launch")
        if process_creation_epoch_millis \
                <= int(prior[-1]["processCreationEpochMillis"]):
            fail("process creation time does not prove a new launch")
        if any(entry["pid"] == pid for entry in prior):
            fail("launch PID is already registered; one PID may identify one launch only")
        if any(entry["launchNonce"] == launch_nonce for entry in prior):
            fail("launch nonce is already registered")
    if len(prior) >= 256:
        fail("native launch registry already reached its 256-record ceiling")

    index = len(prior) + 1
    segment = (
        f"logs/native-{session}-segment-{index:04d}-"
        f"{display_profile.replace('-', '_')}.log"
    )
    record: dict[str, object] = {
        "schemaVersion": LAUNCH_REGISTRY_SCHEMA,
        "launchIndex": index,
        "nativeSessionId": session,
        "launchNonce": launch_nonce,
        "pid": pid,
        "processCreationEpochMillis": process_creation_epoch_millis,
        "processPath": str(image),
        "gameDirectory": str(game),
        "runtimeJarPath": str(runtime),
        "runtimeJarSha256": sha256_file(runtime),
        "worldDirectory": str(world),
        "displayProfile": display_profile,
        "observerLogSegment": segment,
        "registeredEpochMillis": now,
        "launchIdentitySha256": "",
        "previousChainSha256": previous,
    }
    record["launchIdentitySha256"] = launch_identity_sha256(record)
    record["chainSha256"] = chained_record_sha256(
        previous, record, "chainSha256"
    )
    try:
        with registry_path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(record, ensure_ascii=True, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        parse_launch_registry(registry_path, session)
    except (OSError, EvidenceContractError) as exc:
        fail(f"cannot append native launch registry: {exc}")
    return {
        "status": "REGISTERED",
        "registryPath": str(registry_path),
        "registrySha256": sha256_file(registry_path),
        "launch": record,
    }


def _input_component(path: Path, relative: str, session: str) -> dict[str, object]:
    from windows_native_input import NativeInputError, transcript_tail

    before = sha256_file(path)
    try:
        tail, next_sequence, pending = transcript_tail(path, session)
    except NativeInputError as exc:
        fail(f"native input transcript cannot be sealed: {exc}")
    after = sha256_file(path)
    if before != after:
        fail("native input transcript changed while its seal was being built")
    if pending is not None or next_sequence <= 1:
        fail("native input transcript is empty or ends in an incomplete intent")
    return {
        "path": relative,
        "sha256": after,
        "recordCount": next_sequence - 1,
        "tailSha256": tail,
    }


def append_operator_input_seal(
    run_directory: Path,
    seal_ledger: Path,
    operator_key_file: Path,
    session: str,
    operator_id: str,
    *,
    operator_nonce: str | None = None,
    captured_epoch_millis: int | None = None,
) -> dict[str, object]:
    """Append an HMAC-authenticated operator seal outside the run directory.

    The helper does not claim OS-level immutability.  Integrity comes from the
    separately held operator key plus a create/append-only ledger outside the
    mutable run artifact.  Validation therefore requires both files.
    """
    run = plain_directory(run_directory, "release-client run directory")
    valid_session(session)
    if OPERATOR_ID.fullmatch(operator_id) is None:
        fail("operator id must contain 3-80 safe ASCII characters")
    key_path = plain_file(operator_key_file, "operator seal key")
    if path_is_within(key_path, run):
        fail("operator seal key must be held outside the run artifact")
    key = key_path.read_bytes()
    if not 32 <= len(key) <= 4096:
        fail("operator seal key must contain 32..4096 bytes")

    ledger_parent = plain_directory(seal_ledger.parent, "operator seal directory")
    destination = ledger_parent / seal_ledger.name
    if path_is_within(destination.absolute(), run):
        fail("operator input-seal ledger must live outside the run artifact")
    if destination.exists() or destination.is_symlink():
        try:
            existing = parse_operator_seal_ledger(destination, key)
        except EvidenceContractError as exc:
            fail(str(exc))
        previous = str(existing[-1]["sealSha256"])
        sequence = len(existing) + 1
        previous_captured = int(existing[-1]["capturedEpochMillis"])
        if any(record["nativeSessionId"] == session for record in existing):
            fail("operator input-seal ledger already contains this session")
    else:
        existing = []
        previous = "0" * 64
        sequence = 1
        previous_captured = -1

    transcript_path = plain_file(
        run / INPUT_TRANSCRIPT_PATH, "native input transcript"
    )
    registry_path = plain_file(
        run / LAUNCH_REGISTRY_PATH, "native launch registry"
    )
    segment_path = plain_file(
        run / LOG_SEGMENT_INDEX_PATH, "native log segment index"
    )
    registry_before = sha256_file(registry_path)
    try:
        launches, launch_tail = parse_launch_registry(registry_path, session)
    except EvidenceContractError as exc:
        fail(str(exc))
    registry_after = sha256_file(registry_path)
    if registry_before != registry_after:
        fail("native launch registry changed while its seal was being built")
    input_component = _input_component(
        transcript_path, INPUT_TRANSCRIPT_PATH, session
    )
    launch_component = {
        "path": LAUNCH_REGISTRY_PATH,
        "sha256": registry_after,
        "recordCount": len(launches),
        "tailSha256": launch_tail,
    }
    now = int(time.time() * 1000) if captured_epoch_millis is None \
        else captured_epoch_millis
    if now <= previous_captured:
        fail("operator seal timestamp does not follow the previous ledger record")
    nonce = secrets.token_hex(32) if operator_nonce is None else operator_nonce
    if re.fullmatch(r"[0-9a-f]{64}", nonce) is None:
        fail("operator nonce must be exactly 64 lowercase hexadecimal characters")
    record: dict[str, object] = {
        "schemaVersion": 1,
        "sequence": sequence,
        "nativeSessionId": session,
        "operatorId": operator_id,
        "operatorNonce": nonce,
        "capturedEpochMillis": now,
        "runDirectoryToken": path_token(run),
        "inputTranscript": input_component,
        "launchRegistry": launch_component,
        "nativeLogSegments": {
            "path": LOG_SEGMENT_INDEX_PATH,
            "sha256": sha256_file(segment_path),
        },
        "previousSealSha256": previous,
    }
    record["sealSha256"] = sha256_bytes(
        previous.encode("ascii") + canonical_json(record)
    )
    record["hmacSha256"] = operator_seal_hmac(key, record)
    if set(record) != set(SEAL_RECORD_FIELDS):
        fail("internal operator seal field set differs from the locked schema")
    try:
        with destination.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(record, ensure_ascii=True, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        parse_operator_seal_ledger(destination, key)
    except (OSError, EvidenceContractError) as exc:
        fail(f"cannot append operator input seal: {exc}")
    return {
        "status": "OPERATOR_SEALED",
        "inputSeal": str(destination),
        "sequence": sequence,
        "session": session,
        "sealSha256": record["sealSha256"],
    }


def input_precommit_line(
    run_directory: Path,
    operator_key_file: Path,
    session: str,
    session_nonce: str,
    game_directory: Path,
    expected_game_directory: Path,
    runtime_jar: Path,
    world_directory: Path,
) -> str:
    """Build the exact line an operator publishes before the first input."""
    run = plain_directory(run_directory, "release-client run directory")
    valid_session(session)
    if re.fullmatch(r"[0-9a-f]{64}", session_nonce) is None:
        fail("precommit session nonce must be 64 lowercase hexadecimal characters")
    key = plain_file(operator_key_file, "operator seal key")
    if path_is_within(key, run):
        fail("operator seal key must be held outside the run artifact")
    key_bytes = key.read_bytes()
    if not 32 <= len(key_bytes) <= 4096:
        fail("operator seal key must contain 32..4096 bytes")
    game = exact_game_directory(game_directory, expected_game_directory)
    runtime = plain_file(runtime_jar, "installed runtime JAR")
    world = plain_directory(world_directory, "native world directory")
    if runtime.parent != plain_directory(game / "mods", "native mods directory"):
        fail("installed runtime JAR is not directly inside the exact profile mods")
    if world.parent != plain_directory(game / "saves", "native saves directory"):
        fail("native world is not directly inside the exact profile saves")
    if re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", world.name) is None:
        fail("world id is not safe for the fixed PRECOMMIT line")
    return (
        "HSQA_INPUT_PRECOMMIT_V1 "
        f"operatorKeySha256={sha256_bytes(key_bytes)} "
        f"nativeSessionId={session} "
        f"sessionNonce={session_nonce} "
        f"runDirectoryToken={path_token(run)} "
        f"runtimeJarSha256={sha256_file(runtime)} "
        f"worldId={world.name}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    marker = subparsers.add_parser("create-marker")
    marker.add_argument("--session", required=True)
    marker.add_argument("--ttl-seconds", type=int, default=900)
    seal = subparsers.add_parser("seal-log")
    seal.add_argument("--session", required=True)
    seal.add_argument("--run-directory", type=Path, required=True)
    seal.add_argument("--display-profile", required=True)
    launch = subparsers.add_parser("register-launch")
    launch.add_argument("--session", required=True)
    launch.add_argument("--run-directory", type=Path, required=True)
    launch.add_argument("--pid", type=int, required=True)
    launch.add_argument("--launch-nonce", required=True)
    launch.add_argument("--display-profile", required=True)
    launch.add_argument("--runtime-jar", type=Path, required=True)
    launch.add_argument("--world-directory", type=Path, required=True)
    input_seal = subparsers.add_parser("seal-input")
    input_seal.add_argument("--session", required=True)
    input_seal.add_argument("--run-directory", type=Path, required=True)
    input_seal.add_argument("--input-seal", type=Path, required=True)
    input_seal.add_argument("--operator-key-file", type=Path, required=True)
    input_seal.add_argument("--operator-id", required=True)
    input_seal.add_argument("--session-nonce", required=True)
    precommit = subparsers.add_parser("input-precommit")
    precommit.add_argument("--session", required=True)
    precommit.add_argument("--session-nonce", required=True)
    precommit.add_argument("--run-directory", type=Path, required=True)
    precommit.add_argument("--operator-key-file", type=Path, required=True)
    precommit.add_argument("--runtime-jar", type=Path, required=True)
    precommit.add_argument("--world-directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "create-marker":
            result = create_marker(
                EXACT_GAME_DIRECTORY, EXACT_GAME_DIRECTORY, args.session,
                ttl_seconds=args.ttl_seconds,
            )
        elif args.command == "seal-log":
            run_directory = production_run_directory(args.run_directory)
            result = seal_log_segment(
                EXACT_GAME_DIRECTORY, EXACT_GAME_DIRECTORY,
                run_directory, args.session, args.display_profile,
            )
        elif args.command == "register-launch":
            run_directory = production_run_directory(args.run_directory)
            if os.name != "nt":
                fail("live launch registration is available only on Windows")
            from windows_native_input import (
                NativeInputError,
                process_creation_epoch_millis,
                process_path,
            )
            try:
                image = process_path(args.pid)
                creation = process_creation_epoch_millis(args.pid)
            except NativeInputError as exc:
                fail(f"cannot inspect live launch: {exc}")
            if not image:
                fail("live launch executable path could not be resolved")
            result = append_launch_registry(
                run_directory, args.session, args.launch_nonce, args.pid,
                creation, Path(image), EXACT_GAME_DIRECTORY,
                EXACT_GAME_DIRECTORY, args.runtime_jar, args.world_directory,
                args.display_profile,
            )
        elif args.command == "seal-input":
            run_directory = production_run_directory(args.run_directory)
            result = append_operator_input_seal(
                run_directory, args.input_seal, args.operator_key_file,
                args.session, args.operator_id,
                operator_nonce=args.session_nonce,
            )
        else:
            run_directory = production_run_directory(args.run_directory)
            line = input_precommit_line(
                run_directory, args.operator_key_file, args.session,
                args.session_nonce, EXACT_GAME_DIRECTORY,
                EXACT_GAME_DIRECTORY, args.runtime_jar,
                args.world_directory,
            )
            print(line)
            return 0
    except NativeSessionError as exc:
        print(f"native release session: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
