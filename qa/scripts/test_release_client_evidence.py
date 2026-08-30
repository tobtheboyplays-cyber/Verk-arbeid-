#!/usr/bin/env python3
"""Contract tests for the native in-game approval validator."""

from __future__ import annotations

import copy
import contextlib
import datetime as dt
import hashlib
import io
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import wave
import zipfile
import zlib
from pathlib import Path
from unittest import mock

# The canonical doctor runs this file with ``/usr/bin/python3 -I``.  Resolve
# sibling imports from this source-fingerprinted script directory so isolated
# mode cannot fall back to an ambient PYTHONPATH or the invoking directory.
SCRIPT_DIRECTORY = Path(__file__).resolve(strict=True).parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))

from native_release_session import (
    append_launch_registry,
    append_operator_input_seal,
    parse_native_log,
    path_token,
)
from native_evidence_contract import (
    EvidenceContractError,
    INPUT_DRIVER,
    chained_record_sha256,
    launch_identity_sha256,
    local_absolute_path,
    parse_launch_registry,
)
from validate_release_client_evidence import (
    AUTHORITY_EVENTS,
    AUTHORITY_MARKER,
    REQUIRED_MATRIX_ROW_IDS,
    RAW_MATERIALS,
    allowed_setup_command,
    matrix_contract,
    parse_authority_v1_line,
    require_critical_raid_authority_exactly_once,
    validate_audio_capture_report,
    validate_native_input_transcript,
    validate_operator_input_seal_binding,
)


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "qa" / "scripts" / "validate_release_client_evidence.py"
FRAME_CHECKER = ROOT / "qa" / "scripts" / "check_client_frame_stats.py"
MATRIX = ROOT / "qa" / "release_client_matrix.json"
FINGERPRINT = "a" * 64
SESSION = "native-session-20260828"
NOW = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def stamp(value: dt.datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


STARTED_AT = NOW - dt.timedelta(minutes=70)
CAPTURED_AT = NOW - dt.timedelta(minutes=50)
OBSERVED_AT = NOW - dt.timedelta(minutes=40)
REVIEWED_AT = NOW - dt.timedelta(minutes=20)
FINISHED_AT = NOW - dt.timedelta(minutes=5)
STARTED = stamp(STARTED_AT)
CAPTURED = stamp(CAPTURED_AT)
OBSERVED = stamp(OBSERVED_AT)
REVIEWED = stamp(REVIEWED_AT)
FINISHED = stamp(FINISHED_AT)
OBSERVED_EPOCH_MILLIS = int(CAPTURED_AT.timestamp() * 1000)
DEFAULT_PROFILE = "1280x720-gui3-en_us"


def recipe_components(recipe_name: str) -> set[str]:
    """Return direct item/tag dependencies from one checked-in recipe."""
    recipe = json.loads((
        ROOT / "hearthstead-neoforge" / "src" / "main" / "resources"
        / "data" / "hearthstead" / "recipe" / f"{recipe_name}.json"
    ).read_text(encoding="utf-8"))
    ingredients = recipe.get("ingredients")
    if ingredients is None:
        ingredients = recipe.get("key", {}).values()
    components: set[str] = set()
    for ingredient in ingredients:
        if "item" in ingredient:
            components.add(ingredient["item"])
        elif "tag" in ingredient:
            components.add(f"#{ingredient['tag']}")
        else:
            raise AssertionError(f"unsupported ingredient in {recipe_name}: {ingredient}")
    return components


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def path_token(path: Path) -> str:
    rendered = path.resolve().as_posix()
    if len(rendered) >= 3 and rendered[1:3] == ":/":
        rendered = rendered.lower()
    return hashlib.sha256(rendered.encode("utf-8")).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def rewrite_input_records(path: Path, records: list[dict[str, object]]) -> str:
    """Rewrite a forged transcript with a valid internal chain.

    Attack tests deliberately recompute both the transcript chain and mutable
    manifest metadata.  The external operator seal must still detect a
    semantically valid rewrite.
    """
    previous = "0" * 64
    for record in records:
        record["previousChainSha256"] = previous
        record.pop("chainSha256", None)
        payload = json.dumps(
            record, ensure_ascii=True, sort_keys=True, separators=(",", ":")
        ).encode("ascii")
        previous = hashlib.sha256(
            previous.encode("ascii") + payload
        ).hexdigest()
        record["chainSha256"] = previous
    path.write_text(
        "\n".join(
            json.dumps(record, ensure_ascii=True, sort_keys=True)
            for record in records
        ) + "\n",
        encoding="utf-8",
    )
    return previous


def make_native_input_transcript(
    path: Path,
    launches: list[dict[str, object]],
    segments: list[dict[str, object]],
) -> dict[str, object]:
    actions: tuple[tuple[str, dict[str, object]], ...] = (
        ("focus", {}),
        ("move", {"clientX": 400, "clientY": 300,
                  "screenX": 400, "screenY": 300}),
        ("click", {"clientX": 400, "clientY": 300,
                   "screenX": 400, "screenY": 300,
                   "button": "left", "count": 1}),
        ("click", {"clientX": 410, "clientY": 310,
                   "screenX": 410, "screenY": 310,
                   "button": "right", "count": 1}),
        ("modified-click", {"clientX": 420, "clientY": 320,
                            "screenX": 420, "screenY": 320,
                            "button": "right", "count": 1,
                            "modifiers": ["shift"]}),
        ("scroll", {"clientX": 500, "clientY": 350,
                    "screenX": 500, "screenY": 350, "clicks": -3}),
        ("key", {"key": "e", "repeat": 1}),
        ("hold", {"key": "w", "milliseconds": 750}),
        ("look", {"dx": 120, "dy": -45}),
        ("text", {"purpose": "settlement_name", "text": "Oak Haven",
                  "asciiLength": 9}),
    )
    import windows_native_input as native_input

    original_root = native_input.TRANSCRIPT_ROOT
    native_input.TRANSCRIPT_ROOT = path.parents[2]
    try:
        for action_index in range(1, 122):
            action, detail = actions[(action_index - 1) % len(actions)]
            launch_position = min(
                len(launches) - 1,
                (action_index - 1) * len(launches) // 121,
            )
            launch = launches[launch_position]
            segment = segments[launch_position]
            pid = int(launch["pid"])
            hwnd = 135790 + launch_position
            window = native_input.WindowInfo(
                hwnd=hwnd,
                pid=pid,
                title="Minecraft 1.21.1 - Singleplayer",
                class_name="GLFW30",
                process_path=str(launch["processPath"]),
                client_left=0,
                client_top=0,
                client_width=1920,
                client_height=1080,
            )
            binding = native_input.LaunchBinding(
                launch_index=int(launch["launchIndex"]),
                native_session_id=SESSION,
                launch_nonce=str(launch["launchNonce"]),
                pid=pid,
                process_creation_epoch_millis=int(
                    launch["processCreationEpochMillis"]
                ),
                registered_epoch_millis=int(launch["registeredEpochMillis"]),
                process_path=str(launch["processPath"]),
                game_directory=str(launch["gameDirectory"]),
                runtime_jar_path=str(launch["runtimeJarPath"]),
                runtime_jar_sha256=str(launch["runtimeJarSha256"]),
                world_directory=str(launch["worldDirectory"]),
                display_profile=str(launch["displayProfile"]),
                observer_log_segment=str(launch["observerLogSegment"]),
                launch_identity_sha256=str(launch["launchIdentitySha256"]),
            )
            target = native_input.BoundTarget(
                window,
                binding,
                path.parent / "native-launch-registry.jsonl",
                sha(path.parent / "native-launch-registry.jsonl"),
            )
            launch_start = int(launch["processCreationEpochMillis"])
            launch_end = int(segment["sealedEpochMillis"])
            action_offset = action_index - (launch_position * 121 // len(launches))
            observed = min(
                launch_end - 2,
                int(launch["registeredEpochMillis"]) + 5_000
                + action_offset * 20,
            )
            completion = {
                "foregroundHwnd": hwnd,
                "boundPid": pid,
                "launchNonce": binding.launch_nonce,
                "processCreationEpochMillis": binding.process_creation_epoch_millis,
                "allEventsDelivered": True,
                "cleanupRequired": False,
            }
            with mock.patch.object(native_input.time, "time",
                                   side_effect=[observed / 1000,
                                                (observed + 1) / 1000]):
                native_input.append_transcript(
                    path, SESSION, target, "INTENT", action, detail,
                )
                native_input.append_transcript(
                    path, SESSION, target, "COMPLETED", action, detail,
                    completion,
                )
    finally:
        native_input.TRANSCRIPT_ROOT = original_root
    lines = path.read_text(encoding="utf-8").splitlines()
    previous = json.loads(lines[-1])["chainSha256"]
    return {
        "path": "logs/native-input.jsonl",
        "sha256": sha(path),
        "driver": INPUT_DRIVER,
        "recordCount": len(lines),
        "tailSha256": previous,
    }


def make_jar(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        archive.writestr("META-INF/neoforge.mods.toml", 'modId="hearthstead"\n')
        archive.writestr("com/hearthstead/client/QaClientObserver.class", b"class")
        archive.writestr("assets/hearthstead/lang/en_us.json", b"{}")
        archive.writestr("assets/hearthstead/lang/nb_no.json", b"{}")


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return (len(data).to_bytes(4, "big") + kind + data
            + (zlib.crc32(kind + data) & 0xFFFFFFFF).to_bytes(4, "big"))


def make_png(path: Path, width: int, height: int, colour: tuple[int, int, int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    row = b"\x00" + bytes(colour) * width
    raw = row * height
    header = width.to_bytes(4, "big") + height.to_bytes(4, "big") + bytes((8, 2, 0, 0, 0))
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", header)
        + png_chunk(b"IDAT", zlib.compress(raw, 9))
        + png_chunk(b"IEND", b"")
    )


def make_audio(path: Path, seconds: int, *, frequency: int = 440) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    sample_rate = 48000
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(sample_rate)
        frames = bytearray()
        for index in range(sample_rate * seconds):
            value = int(5000 * math.sin(2 * math.pi * frequency * index / sample_rate))
            frames.extend(struct.pack("<hh", value, value))
        output.writeframes(frames)


def make_video(path: Path, seconds: int, *, colour: str = "0x203040") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    assert ffmpeg is not None, "ffmpeg required by validator contract"
    completed = subprocess.run(
        [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
         "-i", f"color=c={colour}:s=1280x720:r=30", "-t", str(seconds),
         "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
         str(path)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False,
    )
    assert completed.returncode == 0, completed.stderr


def profile_parts(profile: str) -> tuple[int, int, int, str]:
    resolution, gui, language = profile.split("-", 2)
    width, height = (int(value) for value in resolution.split("x", 1))
    return width, height, int(gui.removeprefix("gui")), language


def screen_for_transition(transition: str) -> str:
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
    return "HearthScreen"


def nonce_name(value: str) -> str:
    return "".join(character if character.isalnum() or character in "_-" else "_"
                   for character in value)


def bounded_nonce(value: str) -> str:
    rendered = nonce_name(value)
    if len(rendered) <= 80:
        return rendered
    suffix = hashlib.sha256(rendered.encode("utf-8")).hexdigest()[:16]
    return rendered[:63] + "_" + suffix


def ack_line(profile: str, nonce: str, jar_hash: str,
             game_directory_token: str, runtime_jar_path_token: str,
             world_path_token: str, *,
             transition: str = "screen_HearthScreen", yaw: float = 0.0,
             pitch: float = 0.0,
             position: tuple[float, float, float] = (10.0, 64.0, 20.0),
             observed_millis: int = OBSERVED_EPOCH_MILLIS) -> str:
    width, height, gui, language = profile_parts(profile)
    screen = screen_for_transition(transition)
    screen_class = ("null" if screen == "null"
                    else f"com.hearthstead.client.screen.{screen}")
    ui_state = "unavailable" if transition == "screen_none" else "ready"
    return (
        "[21:20:00] [Render thread/INFO] [hearthstead/]: HSQA_FRAME_ACK "
        f"nonce={nonce} qaSession={SESSION} observerSource=one_shot_marker "
        "nativeWindows=true "
        f"runtimeJarSha256={jar_hash} runtimeJarSource=mod_list "
        f"gameDirectoryToken={game_directory_token} "
        f"runtimeJarPathToken={runtime_jar_path_token} "
        f"worldPathToken={world_path_token} integratedServer=true "
        f"observedEpochMillis={observed_millis} "
        f"screen={screen} screenClass={screen_class} "
        f"grabbed=true auxButtonBound=none yaw={yaw} pitch={pitch} "
        f"playerPos={position[0]},{position[1]},{position[2]} "
        "hitType=miss hitBlock=none "
        f"guiScale={gui}.0 gui={width // gui}x{height // gui} "
        f"framebuffer={width}x{height} language={language} "
        "blessingTitleResolved=true blessingTitleLanguageMatch=true "
        f"uiState={ui_state} uiTransition={transition} frameSamples=180 "
        "frameP50Ms=12.0 frameP95Ms=16.0 frameP99Ms=20.0 "
        "frameMaxMs=25.0 framesOver33Ms=0 framesOver100Ms=0\n"
    )


def activation_line(observed_millis: int) -> str:
    created = observed_millis // 1000 - 30
    expires = observed_millis // 1000 + 600
    return (
        "[21:19:59] [Render thread/INFO] [hearthstead/]: "
        "HSQA_OBSERVER_ENABLED source=one_shot_marker "
        f"qaSession={SESSION} markerConsumed=true "
        f"markerCreatedEpochSeconds={created} "
        f"markerExpiresEpochSeconds={expires}\n"
    )


def authority_line(target: str, tick: int, *,
                   event: str = "STATE_LOAD_SUMMARY",
                   result: str = "OBSERVED",
                   reason: str = "fixture_observation",
                   revision_before: int = 1,
                   revision_after: int = 1,
                   count_before: int = 1,
                   count_after: int = 1,
                   item: str = "none",
                   item_before: int = 0,
                   item_after: int = 0,
                   item_expected_delta: int = 0,
                   item_conserved: str = "true") -> str:
    """Synthetic Log4j line matching AuthorityTelemetry.format exactly."""
    return (
        "[21:20:00] [Server thread/INFO] [hearthstead/]: "
        f"{AUTHORITY_MARKER} event={event} result={result} "
        "settlement=11111111-1111-1111-1111-111111111111 "
        f"target={target} revision_before={revision_before} "
        f"revision_after={revision_after} count_before={count_before} "
        f"count_after={count_after} item={item} item_before={item_before} "
        f"item_after={item_after} item_expected_delta={item_expected_delta} "
        f"item_conserved={item_conserved} reason={reason} tick={tick}\n"
    )


def assert_authority_parser_rejects(line: str, message_fragment: str) -> None:
    error = io.StringIO()
    try:
        with contextlib.redirect_stderr(error):
            parse_authority_v1_line(line.rstrip("\n"), "contract fixture")
    except SystemExit as exc:
        assert exc.code == 1
        assert message_fragment in error.getvalue(), error.getvalue()
    else:
        raise AssertionError("malformed authority record was accepted")


def frame_window(profile: str, transition: str, jar_hash: str, tag: str,
                 game_directory_token: str, runtime_jar_path_token: str,
                 world_path_token: str,
                 *, baseline: bool = False) -> dict[str, object]:
    width, height, gui, language = profile_parts(profile)
    effective_transition = "screen_none" if baseline else transition
    screen = screen_for_transition(effective_transition)
    return {
        "line": 1,
        "nonce": bounded_nonce(f"{tag}_{effective_transition}"),
        "qaSession": SESSION,
        "observerSource": "one_shot_marker",
        "nativeWindows": True,
        "runtimeJarSha256": jar_hash,
        "runtimeJarSource": "mod_list",
        "gameDirectoryToken": game_directory_token,
        "runtimeJarPathToken": runtime_jar_path_token,
        "worldPathToken": world_path_token,
        "integratedServer": True,
        "observedEpochMillis": OBSERVED_EPOCH_MILLIS,
        "screen": screen,
        "screenClass": ("null" if screen == "null"
                        else f"com.hearthstead.client.screen.{screen}"),
        "grabbed": True,
        "yaw": 0.0,
        "pitch": 0.0,
        "playerPos": [10.0, 64.0, 20.0],
        "hitType": "miss",
        "hitBlock": "none",
        "uiState": "unavailable" if baseline else "ready",
        "transition": effective_transition,
        "displayProfile": profile,
        "framebuffer": f"{width}x{height}",
        "guiScale": gui,
        "language": language,
        "languageResolved": True,
        "languageMatch": True,
        "samples": 180,
        "p50Ms": 12.0,
        "p95Ms": 16.0,
        "p99Ms": 20.0,
        "maxMs": 25.0,
        "framesOver33Ms": 0,
        "framesOver100Ms": 0,
        "slowFraction": 0.0,
    }


def make_frame_report(
    path: Path, profile: str, transitions: list[str], client_log: Path,
    jar_hash: str, game_directory_token: str, runtime_jar_path_token: str,
    world_path_token: str,
) -> None:
    command = [
        sys.executable, str(FRAME_CHECKER), str(client_log),
        "--baseline", "screen_none", "--min-samples", "180",
        "--max-p95-ratio", "1.35", "--max-slow-rise", "0.10",
        "--max-p95-ms", "33.3", "--max-frame-ms", "100",
        "--max-frames-over100", "0", "--expected-jar-sha256", jar_hash,
        "--expected-session", SESSION, "--expected-profile", profile,
        "--expected-game-directory-token", game_directory_token,
        "--expected-runtime-jar-path-token", runtime_jar_path_token,
        "--expected-world-path-token", world_path_token,
        "--require-native-windows", "--require-integrated-server",
        "--output", str(path),
    ]
    for transition in transitions:
        command.extend(("--require", transition))
    completed = subprocess.run(
        command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False,
    )
    assert completed.returncode == 0, (completed.stdout, completed.stderr)


def safe_name(value: str) -> str:
    return "".join(character if character.isalnum() else "-" for character in value)


def build_fixture(root: Path) -> tuple[Path, dict[str, object], dict[str, object]]:
    run = root / "run"
    for name in ("logs", "shots", "film", "audio", "world"):
        (run / name).mkdir(parents=True, exist_ok=True)
    reproduction = (
        "# Native release reproduction\n\n"
        "HSQA_CLIENT_SESSION=native-session-20260828\n\n"
        f"HSQA_INPUT_PRECOMMIT_V1 runDirectoryToken={path_token(run)} "
        "was externally posted before input.\n"
        "Launches are sealed in logs/native-launch-registry.jsonl.\n\n"
        "## Display profiles\nAll four canonical profiles are captured.\n\n"
        "## Physical input\nKeyboard, mouse look, clicks, Shift-right-click and restart "
        "are recorded in ordered native Minecraft takes.\n\n"
        "progressionCommandsUsed: []\n\n"
        + "Every matrix row has an exact row locator and authority observation.\n" * 12
    )
    (run / "reproduction.md").write_text(reproduction, encoding="utf-8")
    candidate = root / "candidate.jar"
    game_directory = root / "profile"
    installed = game_directory / "mods" / "installed.jar"
    world_directory = game_directory / "saves" / "fresh-native-world"
    make_jar(candidate)
    installed.parent.mkdir(parents=True, exist_ok=True)
    world_directory.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(candidate, installed)
    jar_hash = sha(candidate)
    game_directory_token = path_token(game_directory)
    runtime_jar_path_token = path_token(installed)
    world_path_token = path_token(world_directory)

    matrix = json.loads(MATRIX.read_text(encoding="utf-8"))
    profiles = matrix["requiredNativeProfiles"]
    client_logs: dict[str, Path] = {}
    client_ranges: dict[tuple[str, str], tuple[int, int]] = {}
    profile_observed_millis: dict[str, int] = {}
    for segment_index, profile in enumerate(profiles, 1):
        profile_observed_millis[profile] = int((
            STARTED_AT + dt.timedelta(minutes=5 + (segment_index - 1) * 10)
        ).timestamp() * 1000)
        segment_observed = profile_observed_millis[profile]
        path = run / "logs" / (
            f"native-{SESSION}-segment-{segment_index:04d}-"
            f"{profile.replace('-', '_')}.log"
        )
        lines: list[str] = [activation_line(segment_observed)]
        for expected in matrix["rows"]:
            if "client_log" not in expected["requiredEvidenceKinds"]:
                continue
            evidence_profiles = (expected.get("requiredNativeProfiles")
                                 or [DEFAULT_PROFILE])
            if profile not in evidence_profiles:
                continue
            start = len(lines) + 1
            token = nonce_name(f"{profile}_{expected['id']}")
            if expected.get("requiredClientInteraction"):
                lines.append(ack_line(
                    profile, bounded_nonce(token + "_before"), jar_hash,
                    game_directory_token, runtime_jar_path_token,
                    world_path_token,
                    yaw=0.0, position=(10.0, 64.0, 20.0),
                    observed_millis=segment_observed,
                ))
                lines.append(ack_line(
                    profile, bounded_nonce(token + "_after"), jar_hash,
                    game_directory_token, runtime_jar_path_token,
                    world_path_token,
                    yaw=12.0, pitch=3.0, position=(11.0, 64.0, 20.0),
                    observed_millis=segment_observed,
                ))
            else:
                lines.append(ack_line(
                    profile, bounded_nonce(token + "_proof"), jar_hash,
                    game_directory_token, runtime_jar_path_token,
                    world_path_token,
                    observed_millis=segment_observed,
                ))
            client_ranges[(expected["id"], profile)] = (start, len(lines))
        path.write_text("".join(lines), encoding="utf-8")
        client_logs[profile] = path

    frame_transitions_for_row: dict[tuple[str, str], list[str]] = {}
    combined_transitions: dict[str, list[str]] = {profile: [] for profile in profiles}
    for row_index, expected in enumerate(matrix["rows"], 1):
        if "frame_report" not in expected["requiredEvidenceKinds"]:
            continue
        evidence_profiles = expected.get("requiredNativeProfiles") or [DEFAULT_PROFILE]
        for profile_index, profile in enumerate(evidence_profiles):
            transitions = list(expected.get("requiredFrameTransitions") or [
                f"fixture_state_{row_index}_{profile_index}"
            ])
            frame_transitions_for_row[(expected["id"], profile)] = transitions
            for transition in transitions:
                if transition not in combined_transitions[profile]:
                    combined_transitions[profile].append(transition)

    frame_reports: dict[str, Path] = {}
    for profile, transitions in combined_transitions.items():
        if not transitions:
            continue
        with client_logs[profile].open("a", encoding="utf-8") as log:
            log.write(ack_line(
                profile, bounded_nonce(f"{safe_name(profile)}_screen_none"),
                jar_hash, game_directory_token, runtime_jar_path_token,
                world_path_token, transition="screen_none",
                observed_millis=profile_observed_millis[profile],
            ))
            for transition in transitions:
                log.write(ack_line(
                    profile,
                    bounded_nonce(f"{safe_name(profile)}_{transition}"),
                    jar_hash, game_directory_token, runtime_jar_path_token,
                    world_path_token, transition=transition,
                    observed_millis=profile_observed_millis[profile],
                ))
        report_path = run / "logs" / f"native-{safe_name(profile)}-frames.json"
        make_frame_report(
            report_path, profile, transitions, client_logs[profile], jar_hash,
            game_directory_token, runtime_jar_path_token, world_path_token,
        )
        frame_reports[profile] = report_path

    # Singleplayer's genuine Server-thread authority lines live in the same
    # Log4j latest.log segment as the Render-thread observer ACKs.
    server_log = client_logs[DEFAULT_PROFILE]
    server_ranges: dict[
        str, list[tuple[int, str, str, str, str, str]]
    ] = {}
    server_line_number = len(server_log.read_text(encoding="utf-8").splitlines())
    with server_log.open("a", encoding="utf-8") as server_handle:
        authority_index = 0
        for expected in matrix["rows"]:
            if "server_log" not in expected["requiredEvidenceKinds"]:
                continue
            server_ranges[expected["id"]] = []
            for requirement in expected["requiredAuthorityTransactions"]:
                authority_index += 1
                authority_event = requirement["event"]
                authority_result = requirement["result"]
                authority_target = (
                    requirement["targetPrefix"]
                    + f"fixture_{authority_index}"
                )
                authority_reason = requirement.get(
                    "reason",
                    requirement.get("reasonPrefix", "reason:")
                    + "fixture",
                )
                authority_state: dict[str, int] = {}
                if authority_event == "RAID_WARNING_COMMITTED":
                    authority_target = (
                        f"first_raid_warning:{authority_index}:captain:"
                        "22222222-2222-2222-2222-222222222222"
                    )
                    approach_bits = struct.unpack(
                        ">I", struct.pack(">f", -42.5)
                    )[0]
                    authority_reason = (
                        "persisted_plan:brann:approach_bits:"
                        f"{approach_bits}"
                    )
                    authority_state = {
                        "revision_before": authority_index,
                        "revision_after": authority_index + 1,
                        "count_before": authority_index,
                        "count_after": authority_index + 1,
                    }
                elif authority_event == "RAID_AFTERMATH_VIEWED":
                    report_hash = hashlib.sha256(
                        f"raid-report-fixture:{authority_index}".encode("utf-8")
                    ).hexdigest()
                    authority_target = (
                        f"raid_aftermath:{authority_index}:report:{report_hash}"
                    )
                    authority_reason = (
                        "report_viewed:held:korn:stolen:0:hurt:0:stage:rolig"
                    )
                    authority_state = {
                        "revision_before": authority_index,
                        "revision_after": authority_index + 1,
                        "count_before": authority_index,
                        "count_after": authority_index + 1,
                    }
                server_handle.write(authority_line(
                    authority_target, 2_000 + authority_index,
                    event=authority_event, result=authority_result,
                    reason=authority_reason,
                    **authority_state,
                ))
                server_line_number += 1
                server_ranges[expected["id"]].append((
                    server_line_number, requirement["id"], authority_event,
                    authority_result, authority_target, authority_reason,
                ))

    post_restart_observed = int((
        STARTED_AT + dt.timedelta(minutes=45)
    ).timestamp() * 1000)
    post_restart_profile = DEFAULT_PROFILE
    post_restart_path = run / "logs" / (
        f"native-{SESSION}-segment-{len(profiles) + 1:04d}-"
        f"{post_restart_profile.replace('-', '_')}.log"
    )
    restart_matrix_row = next(
        row for row in matrix["rows"]
        if row["id"] == "journey.restart_persistence"
    )
    post_restart_requirement = next(
        requirement
        for requirement in restart_matrix_row["requiredAuthorityTransactions"]
        if int(requirement.get("minOccurrences", 1)) >= 2
    )
    post_restart_event = post_restart_requirement["event"]
    post_restart_result = post_restart_requirement["result"]
    post_restart_target = (
        post_restart_requirement["targetPrefix"] + "fixture_restart"
    )
    post_restart_reason = post_restart_requirement.get(
        "reason",
        post_restart_requirement.get("reasonPrefix", "reason:") + "restart",
    )
    post_restart_path.write_text(
        activation_line(post_restart_observed)
        + ack_line(
            post_restart_profile,
            bounded_nonce("journey_restart_persistence_after"),
            jar_hash, game_directory_token, runtime_jar_path_token,
            world_path_token, transition="restart_after_world_reload",
            observed_millis=post_restart_observed,
        )
        + authority_line(
            post_restart_target, 9_000, event=post_restart_event,
            result=post_restart_result, reason=post_restart_reason,
        ),
        encoding="utf-8",
    )
    post_restart_client_range = (2, 2)
    post_restart_server_range = (3, 3)

    archive = run / "world" / "fresh-world.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as saved:
        saved.writestr("Fresh World/level.dat", b"level")
    persisted = {
        "settlementIds": ["11111111-1111-1111-1111-111111111111"],
        "buildingIds": ["22222222-2222-2222-2222-222222222222"],
        "settlerIds": [
            "33333333-3333-3333-3333-333333333331",
            "33333333-3333-3333-3333-333333333332",
            "33333333-3333-3333-3333-333333333333",
            "33333333-3333-3333-3333-333333333334",
        ],
        "developmentUnlocked": ["arm_the_watch", "shield_doctrine"],
        "raidOutcome": "victory",
        "blessingTargets": ["settler", "building"],
        "guardXp": 40,
        "inventoryDigest": "c" * 64,
    }
    world_manifest_path = run / "world" / "world.json"
    world_manifest = {
        "schemaVersion": 1,
        "sourceFingerprint": FINGERPRINT,
        "jarSha256": jar_hash,
        "nativeSessionId": SESSION,
        "worldId": "fresh-native-world",
        "worldSeed": "1234",
        "freshWorld": True,
        "beforeRestart": persisted,
        "afterRestart": copy.deepcopy(persisted),
        "saveArchive": {
            "path": archive.relative_to(run).as_posix(),
            "sha256": sha(archive),
        },
    }
    write_json(world_manifest_path, world_manifest)

    counters = {"video": 0, "audio": 0, "colour": 1}
    rows = []
    for row_index, expected in enumerate(matrix["rows"], 1):
        row_name = safe_name(expected["id"])
        required_profiles = expected.get("requiredNativeProfiles") or [DEFAULT_PROFILE]
        evidence: list[dict[str, object]] = []
        for kind in expected["requiredEvidenceKinds"]:
            evidence_profiles = required_profiles if kind in {
                "audio", "client_log", "contact_sheet", "frame_report", "screenshot", "video"
            } and expected.get("requiredNativeProfiles") else [DEFAULT_PROFILE]
            for profile_index, profile in enumerate(evidence_profiles):
                item: dict[str, object] = {
                    "kind": kind,
                    "capturedAt": CAPTURED,
                    "nativeSessionId": SESSION,
                    "worldId": "fresh-native-world",
                }
                if kind in {"audio", "client_log", "contact_sheet", "frame_report",
                            "screenshot", "video"}:
                    item["displayProfile"] = profile
                    item["language"] = profile_parts(profile)[3]
                if kind == "client_log":
                    path = client_logs[profile]
                    start, end = client_ranges[(expected["id"], profile)]
                    item["locator"] = {
                        "lineStart": start,
                        "lineEnd": end,
                        "state": expected["id"],
                    }
                elif kind == "server_log":
                    path = server_log
                    server_requirements = server_ranges[expected["id"]]
                    (line, authority_requirement, authority_event,
                     authority_result, authority_target,
                     authority_reason) = server_requirements[0]
                    item["locator"] = {
                        "lineStart": line,
                        "lineEnd": line,
                        "state": expected["id"],
                        "authorityRequirement": authority_requirement,
                        "authorityEvent": authority_event,
                        "authorityResult": authority_result,
                        "authorityTarget": authority_target,
                        "authorityReason": authority_reason,
                    }
                    for (extra_line, extra_requirement, extra_event,
                         extra_result, extra_target,
                         extra_reason) in server_requirements[1:]:
                        evidence.append({
                            "kind": "server_log",
                            "capturedAt": CAPTURED,
                            "nativeSessionId": SESSION,
                            "worldId": "fresh-native-world",
                            "path": path.relative_to(run).as_posix(),
                            "sha256": sha(path),
                            "locator": {
                                "lineStart": extra_line,
                                "lineEnd": extra_line,
                                "state": expected["id"],
                                "authorityRequirement": extra_requirement,
                                "authorityEvent": extra_event,
                                "authorityResult": extra_result,
                                "authorityTarget": extra_target,
                                "authorityReason": extra_reason,
                            },
                        })
                elif kind == "video":
                    counters["video"] += 1
                    path = run / "film" / (
                        f"{row_name}-{safe_name(profile)}-{counters['video']}.mp4"
                    )
                    colour = (
                        f"0x{(counters['video'] * 2654435761) & 0xFFFFFF:06x}"
                    )
                    make_video(path, 1, colour=colour)
                    item["locator"] = {
                        "startSeconds": 0.0,
                        "endSeconds": 0.75,
                        "state": expected["id"],
                    }
                elif kind == "audio":
                    counters["audio"] += 1
                    path = run / "audio" / (
                        f"{row_name}-{safe_name(profile)}-{counters['audio']}.wav"
                    )
                    make_audio(path, 1, frequency=300 + counters["audio"] * 11)
                    item["locator"] = {
                        "startSeconds": 0.0,
                        "endSeconds": 0.75,
                        "state": expected["id"],
                    }
                    item["captureReport"] = {
                        "status": "PASS",
                        "output": str(path.resolve()),
                        "device_index": 7,
                        "device_name": "PRO X 2 LIGHTSPEED",
                        "channels": 2,
                        "sample_rate": 48000,
                        "frames": 48000,
                        "requested_seconds": 1,
                        "wall_seconds": 1.01,
                    }
                elif kind == "screenshot":
                    width, height, _, _ = profile_parts(profile)
                    path = run / "shots" / f"{row_name}-{safe_name(profile)}.png"
                    value = counters["colour"]
                    counters["colour"] += 1
                    make_png(path, width, height,
                             ((value * 31) % 251, (value * 67) % 251, (value * 101) % 251))
                    item["locator"] = {"frameLabel": f"{expected['id']} profile {profile}"}
                elif kind == "contact_sheet":
                    path = run / "film" / f"{row_name}-contact.png"
                    value = counters["colour"]
                    counters["colour"] += 1
                    make_png(path, 640, 360,
                             ((value * 37) % 251, (value * 71) % 251, (value * 109) % 251))
                    item["locator"] = {"frameLabel": f"{expected['id']} contact states"}
                elif kind == "frame_report":
                    transitions = frame_transitions_for_row[(expected["id"], profile)]
                    path = frame_reports[profile]
                    item["locator"] = {"transitions": transitions}
                elif kind == "world_manifest":
                    path = world_manifest_path
                    item["locator"] = {"state": "before-after-restart"}
                else:
                    raise AssertionError(kind)
                item["path"] = path.relative_to(run).as_posix()
                item["sha256"] = sha(path)
                evidence.append(item)

        if expected["id"] == "journey.restart_persistence":
            evidence.append({
                "kind": "client_log",
                "capturedAt": OBSERVED,
                "nativeSessionId": SESSION,
                "worldId": "fresh-native-world",
                "displayProfile": post_restart_profile,
                "language": profile_parts(post_restart_profile)[3],
                "path": post_restart_path.relative_to(run).as_posix(),
                "sha256": sha(post_restart_path),
                "locator": {
                    "lineStart": post_restart_client_range[0],
                    "lineEnd": post_restart_client_range[1],
                    "state": expected["id"],
                },
            })
            evidence.append({
                "kind": "server_log",
                "capturedAt": OBSERVED,
                "nativeSessionId": SESSION,
                "worldId": "fresh-native-world",
                "path": post_restart_path.relative_to(run).as_posix(),
                "sha256": sha(post_restart_path),
                "locator": {
                    "lineStart": post_restart_server_range[0],
                    "lineEnd": post_restart_server_range[1],
                    "state": expected["id"],
                    "authorityRequirement": post_restart_requirement["id"],
                    "authorityEvent": post_restart_event,
                    "authorityResult": post_restart_result,
                    "authorityTarget": post_restart_target,
                    "authorityReason": post_restart_reason,
                },
            })

        row_profiles = [profile for profile in profiles if any(
            item.get("displayProfile") == profile for item in evidence
        )]
        languages = [language for language in ("en_us", "nb_no") if any(
            profile.endswith("-" + language) for profile in row_profiles
        )]
        rows.append({
            "id": expected["id"],
            "status": "APPROVED",
            "realClient": True,
            "triggerMode": expected["triggerMode"],
            "verifiedAssertions": expected.get("acceptanceAssertions", []),
            "nativeSessionId": SESSION,
            "worldId": "fresh-native-world",
            "observedAt": OBSERVED,
            "reviewedAt": REVIEWED,
            "reviewedBy": "native contract reviewer",
            "displayProfiles": row_profiles,
            "languages": languages,
            "serverAuthorityObserved": True,
            "nativeAudioObserved": True,
            "frameTimingObserved": True,
            "restartObserved": True,
            "authorityNote": "Server state and physical inventory changed exactly once.",
            "observationNote": "Native Minecraft motion, layout, sound and result were reviewed.",
            "evidence": evidence,
        })

    # Frame reports are generated while their source logs are still growing.
    # Seal every report and client-log evidence item only after all native ACKs
    # have been appended, mirroring a post-run report generation pass.
    final_client_hashes = {profile: sha(path) for profile, path in client_logs.items()}
    for row in rows:
        for item in row["evidence"]:
            if item["kind"] == "client_log":
                item["sha256"] = sha(run / item["path"])
            elif item["kind"] == "frame_report":
                report_path = run / item["path"]
                report = json.loads(report_path.read_text(encoding="utf-8"))
                report["clientLogSha256"] = final_client_hashes[item["displayProfile"]]
                write_json(report_path, report)
                item["sha256"] = sha(report_path)

    segment_sources = [
        (profile, client_logs[profile]) for profile in profiles
    ] + [(post_restart_profile, post_restart_path)]
    segment_records: list[dict[str, object]] = []
    for segment_index, (profile, path) in enumerate(segment_sources, 1):
        segment_observed = (
            profile_observed_millis[profile]
            if segment_index <= len(profiles) else post_restart_observed
        )
        sealed_millis = segment_observed + 60_000
        payload = path.read_bytes()
        parsed = parse_native_log(payload, SESSION, sealed_millis)
        os.utime(path, (sealed_millis / 1000, sealed_millis / 1000))
        segment_records.append({
            "index": segment_index,
            "path": path.name,
            "sha256": sha(path),
            "sizeBytes": len(payload),
            "sourceRelativePath": "logs/latest.log",
            "sourceModifiedEpochMillis": sealed_millis - 1000,
            "sealedEpochMillis": sealed_millis,
            "displayProfile": profile,
            **parsed,
        })
    segment_index_path = run / "logs" / "native-log-segments.json"
    write_json(segment_index_path, {
        "schemaVersion": 1,
        "nativeSessionId": SESSION,
        "gameDirectoryToken": game_directory_token,
        "segments": segment_records,
    })

    java_image = root / "runtime" / "bin" / "javaw.exe"
    java_image.parent.mkdir(parents=True)
    java_image.write_bytes(b"contract-java-runtime-image")
    launch_records: list[dict[str, object]] = []
    for position, segment in enumerate(segment_records, 1):
        observed = int(segment["firstObservedEpochMillis"])
        launch_result = append_launch_registry(
            run,
            SESSION,
            f"launch_nonce_contract_{position:04d}",
            24679 + position,
            observed - 120_000,
            java_image,
            game_directory,
            game_directory,
            installed,
            world_directory,
            str(segment["displayProfile"]),
            registered_epoch_millis=observed - 60_000,
        )
        launch_records.append(launch_result["launch"])
    launch_registry_path = run / "logs" / "native-launch-registry.jsonl"
    launch_lines = launch_registry_path.read_text(encoding="utf-8").splitlines()
    native_launch_registry = {
        "path": "logs/native-launch-registry.jsonl",
        "sha256": sha(launch_registry_path),
        "recordCount": len(launch_lines),
        "tailSha256": launch_records[-1]["chainSha256"],
    }
    native_input = make_native_input_transcript(
        run / "logs" / "native-input.jsonl", launch_records, segment_records
    )
    operator_key = root / "operator.key"
    operator_key.write_bytes(b"independent-operator-contract-key" * 2)
    input_seal = root / "operator-input-seals.jsonl"
    append_operator_input_seal(
        run, input_seal, operator_key, SESSION, "native-contract-reviewer",
        operator_nonce="5" * 64,
        captured_epoch_millis=int(
            (FINISHED_AT + dt.timedelta(minutes=1)).timestamp() * 1000
        ),
    )

    matrix_hash = sha(MATRIX)
    manifest = {
        "schemaVersion": 3,
        "verdict": "APPROVED",
        "sourceFingerprint": FINGERPRINT,
        "matrixSha256": matrix_hash,
        "reproductionSha256": sha(run / "reproduction.md"),
        "gitCommit": "d" * 40,
        "dirtyHash": "e" * 64,
        "minecraftVersion": "1.21.1",
        "neoforgeVersion": "21.1.248",
        "javaVersion": "21.0.8",
        "renderer": "NVIDIA GeForce RTX contract renderer",
        "displayProfile": DEFAULT_PROFILE,
        "guiScale": 3,
        "language": "en_us",
        "worldId": "fresh-native-world",
        "gameDirectory": str(game_directory.resolve()),
        "worldDirectory": str(world_directory.resolve()),
        "worldSeed": "1234",
        "startedAt": STARTED,
        "finishedAt": FINISHED,
        "durationSeconds": (FINISHED_AT - STARTED_AT).total_seconds(),
        "audioOutputDevice": "PRO X 2 LIGHTSPEED",
        "nativeSessionId": SESSION,
        "clientObserverEnabled": True,
        "observerActivationSources": ["one_shot_marker"],
        "nativeWindowsClient": True,
        "freshWorld": True,
        "naturalProgression": True,
        "progressionCommandsUsed": [],
        "setupCommandsUsed": ["/weather clear", "/time set night"],
        "displayProfiles": profiles,
        "languages": ["en_us", "nb_no"],
        "candidateJar": {"path": str(candidate.resolve()), "sha256": jar_hash},
        "installedJar": {"path": str(installed.resolve()), "sha256": jar_hash},
        "nativeLogSegments": {
            "path": segment_index_path.relative_to(run).as_posix(),
            "sha256": sha(segment_index_path),
        },
        "nativeLaunchRegistry": native_launch_registry,
        "nativeInputTranscript": native_input,
    }
    result = {
        "schemaVersion": 2,
        "verdict": "APPROVED",
        "sourceFingerprint": FINGERPRINT,
        "matrixSha256": matrix_hash,
        "jarSha256": jar_hash,
        "nativeSessionId": SESSION,
        "worldId": "fresh-native-world",
        "rows": rows,
    }
    write_json(run / "manifest.json", manifest)
    write_json(run / "result.json", result)
    return run, manifest, result


def invoke(run: Path, *, matrix: Path | None = None,
           input_seal: Path | None = None,
           precommit_nonce: str = "5" * 64,
           precommit_run_directory_token: str | None = None,
           expected_key_sha256: str | None = None,
           precommit_jar_sha256: str | None = None,
           precommit_world_id: str = "fresh-native-world",
           operator_key_file: Path | None = None) -> subprocess.CompletedProcess[str]:
    seal = run.parent / "operator-input-seals.jsonl" \
        if input_seal is None else input_seal
    key = run.parent / "operator.key" \
        if operator_key_file is None else operator_key_file
    key_sha256 = sha(key) if expected_key_sha256 is None \
        else expected_key_sha256
    jar_sha256 = sha(run.parent / "candidate.jar") \
        if precommit_jar_sha256 is None else precommit_jar_sha256
    run_token = path_token(run) if precommit_run_directory_token is None \
        else precommit_run_directory_token
    command = [sys.executable, str(VALIDATOR), str(run),
               "--expected-source-fingerprint", FINGERPRINT,
               "--expected-game-directory", str(run.parent / "profile"),
               "--input-seal", str(seal),
               "--operator-key-file", str(key),
               "--expected-operator-key-sha256", key_sha256,
               "--expected-precommit-session-nonce", precommit_nonce,
               "--expected-precommit-run-directory-token", run_token,
               "--expected-precommit-jar-sha256",
               jar_sha256,
               "--expected-precommit-world-id", precommit_world_id]
    if matrix is not None:
        command.extend(("--matrix", str(matrix)))
    return subprocess.run(
        command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False,
    )


def native_input_v3_security_contract() -> int:
    """Focused input/launch checks that do not depend on the mutable matrix."""
    with tempfile.TemporaryDirectory(prefix="hsqa-input-v3-") as temporary:
        root = Path(temporary)
        run = root / "run"
        logs = run / "logs"
        logs.mkdir(parents=True)
        registry = logs / "native-launch-registry.jsonl"
        registry.write_text("sealed-registry-fixture\n", encoding="utf-8")
        creation = int((NOW - dt.timedelta(minutes=10)).timestamp() * 1000)
        registered = creation + 10_000
        sealed = creation + 120_000
        nonce = "launch_nonce_security_0001"
        segment_path = "logs/native-security-segment.log"
        launch = {
            "launchIndex": 1,
            "nativeSessionId": SESSION,
            "launchNonce": nonce,
            "pid": 24680,
            "processCreationEpochMillis": creation,
            "registeredEpochMillis": registered,
            "processPath": str((root / "javaw.exe").resolve()),
            "gameDirectory": str((root / "profile").resolve()),
            "runtimeJarPath": str((root / "profile" / "mods" / "hearthstead.jar").resolve()),
            "runtimeJarSha256": "a" * 64,
            "worldDirectory": str((root / "profile" / "saves" / "fresh-world").resolve()),
            "displayProfile": DEFAULT_PROFILE,
            "observerLogSegment": segment_path,
            "launchIdentitySha256": "b" * 64,
        }
        segment = {"sealedEpochMillis": sealed}
        entry = make_native_input_transcript(
            logs / "native-input.jsonl", [launch], [segment]
        )
        identity: dict[str, object] = {
            "nativeSessionId": SESSION,
            "startedAt": dt.datetime.fromtimestamp(
                (creation - 1_000) / 1000, tz=dt.timezone.utc
            ),
            "finishedAt": dt.datetime.fromtimestamp(
                (sealed + 1_000) / 1000, tz=dt.timezone.utc
            ),
            "launchRegistry": {"byNonce": {nonce: launch}},
            "logSegments": {segment_path: segment},
        }
        validated = validate_native_input_transcript(entry, run, identity)
        assert validated["recordCount"] == entry["recordCount"]

        transcript = logs / "native-input.jsonl"
        records = [
            json.loads(line)
            for line in transcript.read_text(encoding="utf-8").splitlines()
        ]
        records[0]["observedEpochMillis"] = registered - 1
        records[1]["observedEpochMillis"] = registered - 1
        tail = rewrite_input_records(transcript, records)
        backdated_entry = dict(entry)
        backdated_entry["sha256"] = sha(transcript)
        backdated_entry["tailSha256"] = tail
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            try:
                validate_native_input_transcript(backdated_entry, run, identity)
            except SystemExit as exc:
                assert exc.code == 1
            else:
                raise AssertionError("backdated pre-registration input was accepted")
        assert "timestamp lies outside its exact process launch" in stderr.getvalue()

        seal = {
            "nativeSessionId": SESSION,
            "runDirectoryToken": path_token(run),
            "operatorNonce": "5" * 64,
        }
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            try:
                validate_operator_input_seal_binding(
                    seal, {}, run,
                    {"nativeSessionId": SESSION}, "5" * 64, "8" * 64,
                    "a" * 64, "fresh-world",
                )
            except SystemExit as exc:
                assert exc.code == 1
            else:
                raise AssertionError("cross-run PRECOMMIT token was accepted")
        assert "run directory differs from the external PRECOMMIT" in stderr.getvalue()
    print("native input v3 validator security contract: PASS")
    return 0


def matrix_roster_contract(*, verbose: bool = True) -> int:
    """Lock the canonical 57 IDs, order, uniqueness, and mandatory assertions."""
    canonical = json.loads(MATRIX.read_text(encoding="utf-8"))
    rows, _profiles = matrix_contract(copy.deepcopy(canonical))
    assert len(rows) == 57
    assert len(set(rows)) == 57
    assert tuple(rows) == REQUIRED_MATRIX_ROW_IDS

    def rejects(value: dict[str, object], expected: str) -> None:
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            try:
                matrix_contract(value)
            except SystemExit as exc:
                assert exc.code == 1
            else:
                raise AssertionError(f"matrix mutation was accepted: {expected}")
        assert expected in stderr.getvalue(), stderr.getvalue()

    deleted = copy.deepcopy(canonical)
    deleted["rows"].pop()  # type: ignore[index,union-attr]
    rejects(deleted, "matrix row count is not the exact 57")

    added = copy.deepcopy(canonical)
    extra = copy.deepcopy(added["rows"][-1])  # type: ignore[index]
    extra["id"] = "unexpected.added_release_row"
    added["rows"].append(extra)  # type: ignore[index,union-attr]
    rejects(added, "matrix row count is not the exact 57")

    duplicated = copy.deepcopy(canonical)
    duplicated["rows"][-1]["id"] = duplicated["rows"][0]["id"]  # type: ignore[index]
    rejects(duplicated, "matrix row id is duplicated")

    renamed = copy.deepcopy(canonical)
    renamed["rows"][0]["id"] = "identity.renamed_native_boot"  # type: ignore[index]
    rejects(renamed, "matrix row ID roster/order differs")

    reordered = copy.deepcopy(canonical)
    reordered["rows"][0], reordered["rows"][1] = (  # type: ignore[index]
        reordered["rows"][1], reordered["rows"][0]  # type: ignore[index]
    )
    rejects(reordered, "matrix row ID roster/order differs")

    for row_id in (
        "journey.arm_the_watch_guard_weapon_delivery",
        "journey.watchtower_archer_emblem_and_bow",
        "journey.first_raid_readiness_warning_attack",
        "role.courier_request_collection_delivery",
    ):
        weakened = copy.deepcopy(canonical)
        row = next(  # type: ignore[arg-type]
            value for value in weakened["rows"] if value["id"] == row_id
        )
        row["acceptanceAssertions"].pop()
        rejects(weakened, "weakens a mandatory acceptance assertion")

    for row_id, event in (
        ("journey.first_raid_readiness_warning_attack",
         "RAID_WARNING_COMMITTED"),
        ("journey.raid_resolution_and_reward", "RAID_AFTERMATH_VIEWED"),
    ):
        weakened = copy.deepcopy(canonical)
        row = next(  # type: ignore[arg-type]
            value for value in weakened["rows"] if value["id"] == row_id
        )
        row["requiredAuthorityTransactions"] = [
            requirement
            for requirement in row["requiredAuthorityTransactions"]
            if requirement["event"] != event
        ]
        rejects(weakened, "weakens a mandatory raid authority transaction contract")

    if verbose:
        print("release matrix 57-row roster contract: PASS")
    return 0


def cross_runtime_path_contract() -> int:
    """Prove strict Windows evidence paths survive the canonical WSL judge."""
    windows_paths = {
        "processPath": r"C:\Program Files\Java\bin\javaw.exe",
        "gameDirectory": r"C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)",
        "runtimeJarPath": r"C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)\mods\hearthstead.jar",
        "worldDirectory": r"C:\Users\tobia\curseforge\minecraft\Instances\SIVILASJON (1)\saves\HSQA-Release",
    }
    assert local_absolute_path(
        windows_paths["runtimeJarPath"], "runtime", host_os_name="posix"
    ) == Path(
        "/mnt/c/Users/tobia/curseforge/minecraft/Instances/"
        "SIVILASJON (1)/mods/hearthstead.jar"
    )

    record: dict[str, object] = {
        "schemaVersion": 1,
        "launchIndex": 1,
        "nativeSessionId": SESSION,
        "launchNonce": "launch_nonce_0001",
        "pid": 4242,
        "processCreationEpochMillis": 1_700_000_000_000,
        **windows_paths,
        "runtimeJarSha256": "a" * 64,
        "displayProfile": DEFAULT_PROFILE,
        "observerLogSegment": (
            f"logs/native-{SESSION}-segment-0001-"
            f"{DEFAULT_PROFILE.replace('-', '_')}.log"
        ),
        "registeredEpochMillis": 1_700_000_000_100,
        "launchIdentitySha256": "",
        "previousChainSha256": "0" * 64,
    }
    record["launchIdentitySha256"] = launch_identity_sha256(record)
    record["chainSha256"] = chained_record_sha256(
        "0" * 64, record, "chainSha256"
    )
    with tempfile.TemporaryDirectory() as temporary:
        registry = Path(temporary) / "native-launch-registry.jsonl"
        registry.write_text(
            json.dumps(record, sort_keys=True) + "\n", encoding="utf-8"
        )
        launches, _tail = parse_launch_registry(registry, SESSION)
        assert launches[0]["runtimeJarPath"] == windows_paths["runtimeJarPath"]

    rejected = (
        r"\\server\share\hearthstead.jar",
        r"\\?\C:\mods\hearthstead.jar",
        r"C:mods\hearthstead.jar",
        r"C:\mods\..\hearthstead.jar",
        r"C:\mods/hearthstead.jar",
        r"C:\mods\\hearthstead.jar",
        r"C:\mods\bad?.jar",
        "relative/path",
        "/tmp/../escape",
    )
    for attack in rejected:
        try:
            local_absolute_path(attack, "attack", host_os_name="posix")
        except EvidenceContractError:
            pass
        else:
            raise AssertionError(f"unsafe native path accepted: {attack!r}")

    # On the canonical WSL host, exercise the required unedited Windows
    # captureReport.output against the same physical /mnt/<drive> WAV.
    root_text = ROOT.resolve().as_posix()
    match = re.fullmatch(r"/mnt/([A-Za-z])/(.*)", root_text)
    if os.name == "posix" and match is not None:
        report_root = ROOT / "qa" / "reports"
        with tempfile.TemporaryDirectory(
            prefix=".cross-runtime-audio.", dir=report_root
        ) as temporary:
            wav_path = Path(temporary) / "native-row.wav"
            with wave.open(str(wav_path), "wb") as output:
                output.setnchannels(2)
                output.setsampwidth(2)
                output.setframerate(44_100)
                output.writeframes(b"\0\0\0\0" * 22_050)
            resolved = wav_path.resolve().as_posix()
            wav_match = re.fullmatch(r"/mnt/([A-Za-z])/(.*)", resolved)
            assert wav_match is not None
            windows_output = (
                f"{wav_match.group(1).upper()}:\\"
                + wav_match.group(2).replace("/", "\\")
            )
            validate_audio_capture_report({
                "status": "PASS",
                "output": windows_output,
                "device_index": 1,
                "device_name": "Native Speakers",
                "channels": 2,
                "sample_rate": 44_100,
                "frames": 22_050,
                "requested_seconds": 0.5,
                "wall_seconds": 0.5,
            }, wav_path, "cross-runtime audio", {
                "audioDevice": "Native Speakers",
            })
    print("release-client cross-runtime native path contract: PASS")
    return 0


def isolated_import_bootstrap_contract() -> int:
    """Exercise the exact isolated interpreter shape used by the controller."""
    child_environment = dict(os.environ)
    for variable in (
        "PYTHONHOME", "PYTHONPATH", "PYTHONSTARTUP", "PYTHONINSPECT",
        "PYTHONWARNINGS", "PYTHONUSERBASE",
    ):
        child_environment.pop(variable, None)
    commands = (
        (
            sys.executable,
            "-I",
            str(Path(__file__).resolve(strict=True)),
            "cross-runtime-paths",
        ),
        (
            sys.executable,
            "-I",
            str(VALIDATOR.resolve(strict=True)),
            "--help",
        ),
    )
    for command in commands:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
            # Windows needs SystemRoot and its provider environment to import
            # stdlib networking modules.  The child still uses -I, and every
            # Python-specific ambient path/startup hook is removed explicitly.
            env=child_environment,
        )
        assert completed.returncode == 0, (
            f"isolated import smoke failed for {command[2]}:\n"
            f"stdout={completed.stdout!r}\nstderr={completed.stderr!r}"
        )
    print("release-client isolated import bootstrap contract: PASS")
    return 0


def main() -> int:
    if sys.argv[1:] == ["native-input-v3"]:
        return native_input_v3_security_contract()
    if sys.argv[1:] == ["matrix-roster"]:
        return matrix_roster_contract()
    if sys.argv[1:] == ["cross-runtime-paths"]:
        return cross_runtime_path_contract()
    if sys.argv[1:] == ["isolated-imports"]:
        return isolated_import_bootstrap_contract()
    isolated_import_bootstrap_contract()
    cross_runtime_path_contract()
    matrix_roster_contract(verbose=False)
    telemetry_source = (
        ROOT / "hearthstead-neoforge" / "src" / "main" / "java"
        / "com" / "hearthstead" / "util" / "AuthorityTelemetry.java"
    ).read_text(encoding="utf-8")
    event_block = re.search(
        r"public enum Event\s*\{(?P<body>.*?)\n\s*\}",
        telemetry_source,
        flags=re.DOTALL,
    )
    assert event_block is not None, "AuthorityTelemetry.Event enum not found"
    java_authority_events = frozenset(re.findall(
        r"^\s*([A-Z][A-Z0-9_]*)\s*,?\s*$",
        event_block.group("body"),
        flags=re.MULTILINE,
    ))
    assert java_authority_events == AUTHORITY_EVENTS, (
        "native authority vocabulary drift: "
        f"missing={sorted(java_authority_events - AUTHORITY_EVENTS)} "
        f"stale={sorted(AUTHORITY_EVENTS - java_authority_events)}"
    )

    valid_authority = authority_line(
        "row:contract", 42, event="PLAN_UNLOCK_COMMITTED",
        result="COMMITTED", reason="plan_learned",
        revision_before=4, revision_after=5,
        count_before=1, count_after=2,
        item="minecraft:paper", item_before=3, item_after=2,
        item_expected_delta=-1,
    ).rstrip("\n")
    parsed_authority = parse_authority_v1_line(
        valid_authority, "contract fixture"
    )
    assert parsed_authority["event"] == "PLAN_UNLOCK_COMMITTED"
    assert parsed_authority["target"] == "row:contract"
    assert parsed_authority["item_expected_delta"] == -1
    assert_authority_parser_rejects(
        valid_authority.replace(" reason=plan_learned", ""),
        "fixed V1 field count",
    )
    assert_authority_parser_rejects(
        valid_authority.replace(
            " target=row:contract",
            " target=row:contract target=row:forged",
        ),
        "fixed V1 field count",
    )
    assert_authority_parser_rejects(
        valid_authority.replace("item_conserved=true", "item_conserved=false"),
        "does not prove item conservation",
    )
    assert_authority_parser_rejects(
        valid_authority.replace("item_expected_delta=-1", "item_expected_delta=0"),
        "contradicts its conservation claim",
    )
    assert_authority_parser_rejects(
        valid_authority.replace(
            "event=PLAN_UNLOCK_COMMITTED result=COMMITTED",
            "event=AUTHORITY_REJECTED result=COMMITTED",
        ),
        "rejection event/result pairing is inconsistent",
    )
    assert_authority_parser_rejects(
        valid_authority.replace("[Server thread/INFO]", "[Render thread/INFO]"),
        "not a genuine integrated Server-thread",
    )
    assert_authority_parser_rejects(
        "[21:20:00] [Server thread/INFO] [hearthstead/]: "
        "HSQA_AUTH row=forged result=observed",
        f"lacks one exact {AUTHORITY_MARKER} marker",
    )
    rejected_mutation = authority_line(
        "row:rejected", 43, event="AUTHORITY_REJECTED",
        result="REJECTED", reason="stale_revision",
        revision_before=4, revision_after=5,
    ).rstrip("\n")
    assert_authority_parser_rejects(
        rejected_mutation, "rejected authority transaction changed server state"
    )

    approach_bits = struct.unpack(">I", struct.pack(">f", -42.5))[0]
    valid_warning = authority_line(
        "first_raid_warning:72:captain:"
        "22222222-2222-2222-2222-222222222222",
        44,
        event="RAID_WARNING_COMMITTED",
        result="COMMITTED",
        reason=f"persisted_plan:brann:approach_bits:{approach_bits}",
        revision_before=39,
        revision_after=40,
        count_before=39,
        count_after=40,
    ).rstrip("\n")
    parsed_warning = parse_authority_v1_line(
        valid_warning, "raid warning contract fixture"
    )
    assert parsed_warning["event"] == "RAID_WARNING_COMMITTED"
    assert parsed_warning["revision_after"] == 40
    assert_authority_parser_rejects(
        valid_warning.replace("revision_after=40", "revision_after=39"),
        "revision delta is not exactly +1",
    )
    assert_authority_parser_rejects(
        valid_warning.replace("count_after=40", "count_after=39"),
        "completed-count delta is not exactly +1",
    )
    assert_authority_parser_rejects(
        valid_warning.replace("result=COMMITTED", "result=OBSERVED"),
        "not a committed settlement event",
    )
    assert_authority_parser_rejects(
        valid_warning.replace(str(approach_bits), str(0x7FC00000)),
        "approach is not a valid persisted float",
    )
    assert_authority_parser_rejects(
        valid_warning.replace(
            "22222222-2222-2222-2222-222222222222", "not-a-captain"
        ),
        "persisted-plan facts are malformed",
    )

    report_hash = hashlib.sha256(b"complete-persisted-raid-report").hexdigest()
    valid_aftermath = authority_line(
        f"raid_aftermath:72:report:{report_hash}",
        45,
        event="RAID_AFTERMATH_VIEWED",
        result="COMMITTED",
        reason="report_viewed:held:korn:stolen:2:hurt:1:stage:rolig",
        revision_before=40,
        revision_after=41,
        count_before=40,
        count_after=41,
    ).rstrip("\n")
    parsed_aftermath = parse_authority_v1_line(
        valid_aftermath, "raid aftermath contract fixture"
    )
    assert parsed_aftermath["event"] == "RAID_AFTERMATH_VIEWED"
    assert_authority_parser_rejects(
        valid_aftermath.replace(report_hash, report_hash[:-1]),
        "persisted-report facts are malformed",
    )
    assert_authority_parser_rejects(
        valid_aftermath.replace(":stage:rolig", ""),
        "persisted-report facts are malformed",
    )
    assert_authority_parser_rejects(
        valid_aftermath.replace("stolen:2", "stolen:1000001"),
        "persisted-report facts exceed their bounds",
    )

    critical_required = {
        ("RAID_WARNING_COMMITTED", str(parsed_warning["settlement"])),
        ("RAID_AFTERMATH_VIEWED", str(parsed_aftermath["settlement"])),
    }
    require_critical_raid_authority_exactly_once(
        [parsed_warning, parsed_aftermath],
        critical_required,
        "critical raid fixture",
    )
    replay_error = io.StringIO()
    try:
        with contextlib.redirect_stderr(replay_error):
            require_critical_raid_authority_exactly_once(
                [parsed_warning, parsed_warning, parsed_aftermath],
                critical_required,
                "critical raid fixture",
            )
    except SystemExit as exc:
        assert exc.code == 1
    else:
        raise AssertionError("duplicate raid warning was accepted")
    assert "duplicated/replayed across sealed native logs" in replay_error.getvalue()

    # Pin the actual checked-in first-raid recipe chain before judging which
    # survival inputs may be staged. Finished intermediate/output items stay
    # command-forbidden and must be crafted by the player.
    expected_recipe_components = {
        "hearth": {
            "#minecraft:logs", "#minecraft:stone_crafting_materials",
            "minecraft:campfire",
        },
        "plaque": {
            "#minecraft:planks", "minecraft:copper_ingot",
            "minecraft:iron_ingot",
        },
        "build_plan_house": {
            "#minecraft:planks", "minecraft:feather", "minecraft:paper",
        },
        "build_plan_lumber_camp": {
            "#minecraft:logs", "minecraft:feather", "minecraft:paper",
        },
        "build_plan_farmhouse": {
            "minecraft:feather", "minecraft:paper", "minecraft:wheat_seeds",
        },
        "build_plan_warehouse": {
            "minecraft:chest", "minecraft:feather", "minecraft:paper",
        },
        "build_plan_tavern": {
            "minecraft:barrel", "minecraft:bread", "minecraft:feather",
            "minecraft:paper",
        },
        "build_plan_barracks": {
            "#minecraft:wool", "minecraft:feather", "minecraft:iron_ingot",
            "minecraft:paper",
        },
        "build_plan_watchtower": {
            "minecraft:arrow", "minecraft:feather", "minecraft:iron_ingot",
            "minecraft:paper", "minecraft:white_banner",
        },
        "bell": {
            "minecraft:gold_ingot", "minecraft:iron_ingot", "minecraft:stick",
        },
    }
    for recipe_name, expected in expected_recipe_components.items():
        actual = recipe_components(recipe_name)
        assert actual == expected, (recipe_name, actual, expected)

    expected_raw_materials = {
        "oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
        "dark_oak_log", "mangrove_log", "cherry_log", "bread", "coal",
        "cobblestone", "copper_ingot", "dirt", "feather", "flint",
        "gold_ingot", "iron_ingot", "leather", "stick", "string",
        "sugar_cane", "wheat_seeds", "white_wool",
    }
    assert RAW_MATERIALS == expected_raw_materials

    assert allowed_setup_command(
        "/fill ~-12 ~ ~-12 ~12 ~8 ~12 minecraft:stone keep"
    )
    assert allowed_setup_command(
        "/setblock ~ ~ ~ minecraft:oak_planks keep"
    )
    assert allowed_setup_command("/summon minecraft:pillager ~ ~ ~")
    for raw_material in (
        "copper_ingot", "gold_ingot", "coal", "sugar_cane", "feather",
        "white_wool", "string", "flint", "stick", "cobblestone",
    ):
        command = f"/give @s minecraft:{raw_material} 64"
        assert allowed_setup_command(command), command
    for forbidden_command in (
        "/setblock ~ ~ ~ hearthstead:hearth keep",
        "/setblock ~ ~ ~ minecraft:chest keep",
        "/fill ~ ~ ~ ~200 ~1 ~1 minecraft:stone keep",
        "/fill ~ ~ ~ ~4 ~4 ~4 minecraft:stone replace",
        "/summon minecraft:item ~ ~ ~",
        "/summon minecraft:pillager ~999 ~ ~",
        "/summon minecraft:pillager 0 400 0",
        "/summon minecraft:zombie ~ ~ ~ {CustomName:'fake'}",
        "/tp @e[type=hearthstead:settler] ~ ~ ~",
        # Survival-crafting inputs may be staged, but the gate must reject
        # every finished first-raid/progression/furnishing/equipment shortcut.
        "/give @s minecraft:paper 64",
        "/give @s minecraft:campfire",
        "/give @s minecraft:chest",
        "/give @s minecraft:barrel",
        "/give @s minecraft:white_bed",
        "/give @s minecraft:white_banner",
        "/give @s minecraft:bell",
        "/give @s minecraft:bow",
        "/give @s minecraft:arrow 64",
        "/give @s minecraft:iron_axe",
        "/give @s minecraft:iron_hoe",
        "/give @s minecraft:iron_sword",
        "/give @s minecraft:shield",
        "/give @s minecraft:water_bucket",
        "/give @s minecraft:coal 65",
        "/give @s minecraft:coal 0",
        "/give @a minecraft:coal 1",
        "/give @s hearthstead:plaque",
        "/give @s hearthstead:build_plan",
        "/give @s hearthstead:lumberer_emblem",
    ):
        assert not allowed_setup_command(forbidden_command), forbidden_command

    with tempfile.TemporaryDirectory(prefix="hsqa-release-client-") as temporary:
        root = Path(temporary)
        run, manifest, result = build_fixture(root)
        baseline_manifest = copy.deepcopy(manifest)
        baseline_result = copy.deepcopy(result)
        passed = invoke(run)
        assert passed.returncode == 0, (passed.stdout, passed.stderr)

        manifest_without_input = copy.deepcopy(baseline_manifest)
        del manifest_without_input["nativeInputTranscript"]
        write_json(run / "manifest.json", manifest_without_input)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "nativeInputTranscript is missing" in rejected.stderr

        input_path = run / baseline_manifest["nativeInputTranscript"]["path"]
        original_input = input_path.read_bytes()
        records = [
            json.loads(line) for line in original_input.decode("utf-8").splitlines()
        ]
        records[0]["window"]["class_name"] = "Chrome_WidgetWin_1"
        previous = rewrite_input_records(input_path, records)
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeInputTranscript"]["sha256"] = sha(input_path)
        forged_manifest["nativeInputTranscript"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "does not identify a GLFW/LWJGL window" in rejected.stderr
        input_path.write_bytes(original_input)
        write_json(run / "manifest.json", baseline_manifest)

        # Recompute both mutable transcript metadata and the entire internal
        # hash chain after a semantically valid rewrite.  Only the separately
        # held operator seal remains unchanged, so it must catch this attack.
        records = [
            json.loads(line) for line in original_input.decode("utf-8").splitlines()
        ]
        for record in records:
            record["window"]["title"] = "Minecraft 1.21.1 - Rewritten"
        previous = rewrite_input_records(input_path, records)
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeInputTranscript"]["sha256"] = sha(input_path)
        forged_manifest["nativeInputTranscript"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "operator input seal does not bind the exact transcript" \
            in rejected.stderr, rejected.stderr
        input_path.write_bytes(original_input)
        write_json(run / "manifest.json", baseline_manifest)

        # Text cannot become a disguised chat command or generic text route,
        # even when both records and the mutable manifest are rewritten.
        records = [
            json.loads(line) for line in original_input.decode("utf-8").splitlines()
        ]
        text_indexes = [
            index for index, record in enumerate(records)
            if record["action"] == "text"
        ]
        assert len(text_indexes) >= 2
        for index in text_indexes[:2]:
            records[index]["detail"] = {
                "purpose": "settlement_name",
                "text": "/hearthstead demo",
                "asciiLength": 17,
            }
        previous = rewrite_input_records(input_path, records)
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeInputTranscript"]["sha256"] = sha(input_path)
        forged_manifest["nativeInputTranscript"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "text detail is malformed" in rejected.stderr, rejected.stderr
        input_path.write_bytes(original_input)
        write_json(run / "manifest.json", baseline_manifest)

        # A GLFW-looking record cannot swap in a different executable path.
        records = [
            json.loads(line) for line in original_input.decode("utf-8").splitlines()
        ]
        for index in (0, 1):
            records[index]["window"]["process_path"] = \
                str((run.parent / "malicious" / "javaw.exe").resolve())
        previous = rewrite_input_records(input_path, records)
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeInputTranscript"]["sha256"] = sha(input_path)
        forged_manifest["nativeInputTranscript"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "executable path differs from its sealed launch" \
            in rejected.stderr, rejected.stderr
        input_path.write_bytes(original_input)
        write_json(run / "manifest.json", baseline_manifest)

        # A valid launch nonce/identity from another process cannot be replayed
        # against the original window PID.
        launch_records = [
            json.loads(line) for line in (
                run / baseline_manifest["nativeLaunchRegistry"]["path"]
            ).read_text(encoding="utf-8").splitlines()
        ]
        records = [
            json.loads(line) for line in original_input.decode("utf-8").splitlines()
        ]
        assert records[0]["window"]["pid"] != launch_records[1]["pid"]
        for index in (0, 1):
            records[index]["launchNonce"] = launch_records[1]["launchNonce"]
            records[index]["launchIdentitySha256"] = \
                launch_records[1]["launchIdentitySha256"]
        previous = rewrite_input_records(input_path, records)
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeInputTranscript"]["sha256"] = sha(input_path)
        forged_manifest["nativeInputTranscript"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "PID differs from its sealed launch" in rejected.stderr, rejected.stderr
        input_path.write_bytes(original_input)
        write_json(run / "manifest.json", baseline_manifest)

        # The seal, key and values externally posted before input are all
        # mandatory.  A mutable manifest cannot substitute for any of them.
        rejected = invoke(run, input_seal=run.parent / "missing-seal.jsonl")
        assert rejected.returncode != 0
        assert "operator input-seal ledger is unavailable" \
            in rejected.stderr.lower(), rejected.stderr
        rejected = invoke(run, expected_key_sha256="f" * 64)
        assert rejected.returncode != 0
        assert "operator seal key hash differs from the external PRECOMMIT" \
            in rejected.stderr, rejected.stderr
        rejected = invoke(run, precommit_nonce="9" * 64)
        assert rejected.returncode != 0
        assert "operator input seal nonce differs from the external PRECOMMIT" \
            in rejected.stderr, rejected.stderr
        rejected = invoke(run, precommit_run_directory_token="8" * 64)
        assert rejected.returncode != 0
        assert "reproduction.md lacks the exact externally posted runDirectoryToken" \
            in rejected.stderr, rejected.stderr
        rejected = invoke(run, precommit_jar_sha256="e" * 64)
        assert rejected.returncode != 0
        assert "installed runtime JAR hash differs from the external PRECOMMIT" \
            in rejected.stderr, rejected.stderr
        rejected = invoke(run, precommit_world_id="wrong-world")
        assert rejected.returncode != 0
        assert "native world id differs from the external PRECOMMIT" \
            in rejected.stderr, rejected.stderr

        # A record may not be backdated into the gap between process creation
        # and the independently appended launch registration.
        records = [
            json.loads(line) for line in original_input.decode("utf-8").splitlines()
        ]
        launch_records = [
            json.loads(line) for line in (
                run / baseline_manifest["nativeLaunchRegistry"]["path"]
            ).read_text(encoding="utf-8").splitlines()
        ]
        backdated = int(launch_records[0]["registeredEpochMillis"]) - 1
        records[0]["observedEpochMillis"] = backdated
        records[1]["observedEpochMillis"] = backdated
        previous = rewrite_input_records(input_path, records)
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeInputTranscript"]["sha256"] = sha(input_path)
        forged_manifest["nativeInputTranscript"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "timestamp lies outside its exact process launch" \
            in rejected.stderr, rejected.stderr
        input_path.write_bytes(original_input)
        write_json(run / "manifest.json", baseline_manifest)

        # Even a newly HMAC-sealed registry rewrite cannot place the next
        # process creation at/before the preceding segment's seal.  This is
        # the actual stop/seal/relaunch ordering boundary, not a PID guess.
        launch_registry_path = \
            run / baseline_manifest["nativeLaunchRegistry"]["path"]
        original_launch_registry = launch_registry_path.read_bytes()
        launch_records = [
            json.loads(line) for line in
            original_launch_registry.decode("utf-8").splitlines()
        ]
        segment_index = json.loads((
            run / baseline_manifest["nativeLogSegments"]["path"]
        ).read_text(encoding="utf-8"))
        launch_records[1]["processCreationEpochMillis"] = int(
            segment_index["segments"][0]["sealedEpochMillis"]
        )
        previous = "0" * 64
        for record in launch_records:
            record["previousChainSha256"] = previous
            record["launchIdentitySha256"] = launch_identity_sha256(record)
            record["chainSha256"] = chained_record_sha256(
                previous, record, "chainSha256"
            )
            previous = record["chainSha256"]
        launch_registry_path.write_text(
            "\n".join(
                json.dumps(record, ensure_ascii=True, sort_keys=True)
                for record in launch_records
            ) + "\n",
            encoding="utf-8",
        )
        forged_manifest = copy.deepcopy(baseline_manifest)
        forged_manifest["nativeLaunchRegistry"]["sha256"] = \
            sha(launch_registry_path)
        forged_manifest["nativeLaunchRegistry"]["tailSha256"] = previous
        write_json(run / "manifest.json", forged_manifest)
        relaunch_attack_seal = \
            run.parent / "operator-relaunch-attack-seal.jsonl"
        append_operator_input_seal(
            run, relaunch_attack_seal, run.parent / "operator.key", SESSION,
            "native-contract-reviewer", operator_nonce="7" * 64,
            captured_epoch_millis=int(
                (FINISHED_AT + dt.timedelta(minutes=2)).timestamp() * 1000
            ),
        )
        rejected = invoke(
            run, input_seal=relaunch_attack_seal,
            precommit_nonce="7" * 64,
        )
        assert rejected.returncode != 0
        assert "does not prove seal-before-relaunch ordering" \
            in rejected.stderr, rejected.stderr
        launch_registry_path.write_bytes(original_launch_registry)
        write_json(run / "manifest.json", baseline_manifest)

        missing = result["rows"].pop()
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "matrix mismatch" in rejected.stderr
        result["rows"].append(missing)

        result["rows"][0]["status"] = "CANDIDATE"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "is not APPROVED" in rejected.stderr
        result["rows"][0]["status"] = "APPROVED"

        result["rows"][0]["evidence"][0]["sha256"] = "b" * 64
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "hash differs" in rejected.stderr
        result = copy.deepcopy(baseline_result)

        original_path = result["rows"][0]["evidence"][0]["path"]
        result["rows"][0]["evidence"][0]["path"] = "shots/not-the-log.png"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "outside the required" in rejected.stderr
        result["rows"][0]["evidence"][0]["path"] = original_path

        manifest["progressionCommandsUsed"] = ["/hearthstead recruit"]
        write_json(run / "manifest.json", manifest)
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "progression commands were used" in rejected.stderr
        manifest = copy.deepcopy(baseline_manifest)

        manifest["setupCommandsUsed"] = ["/give @s hearthstead:guard_emblem"]
        write_json(run / "manifest.json", manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "progression-capable commands" in rejected.stderr
        manifest = copy.deepcopy(baseline_manifest)

        manifest["finishedAt"] = stamp(NOW + dt.timedelta(minutes=10))
        write_json(run / "manifest.json", manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "implausibly in the future" in rejected.stderr
        manifest = copy.deepcopy(baseline_manifest)

        old_finish = NOW - dt.timedelta(hours=25)
        old_start = old_finish - dt.timedelta(hours=1)
        manifest["startedAt"] = stamp(old_start)
        manifest["finishedAt"] = stamp(old_finish)
        manifest["durationSeconds"] = 3600
        write_json(run / "manifest.json", manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "older than the 24-hour" in rejected.stderr
        manifest = copy.deepcopy(baseline_manifest)

        manifest["nativeLogSegments"]["sha256"] = "f" * 64
        write_json(run / "manifest.json", manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "segment index hash differs" in rejected.stderr
        manifest = copy.deepcopy(baseline_manifest)

        wrong_profile = root / "wrong-profile"
        (wrong_profile / "mods").mkdir(parents=True)
        (wrong_profile / "saves").mkdir()
        manifest["gameDirectory"] = str(wrong_profile.resolve())
        write_json(run / "manifest.json", manifest)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "exact expected CurseForge profile" in rejected.stderr
        manifest = copy.deepcopy(baseline_manifest)

        reduced = root / "reduced-matrix.json"
        matrix_value = json.loads(MATRIX.read_text(encoding="utf-8"))
        matrix_value["rows"] = matrix_value["rows"][:1]
        write_json(reduced, matrix_value)
        write_json(run / "manifest.json", manifest)
        rejected = invoke(run, matrix=reduced)
        assert rejected.returncode != 0 and "differs from the canonical" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        result["rows"][0]["reviewedAt"] = "2026-08-27T21:40:00Z"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "outside the current run" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        archer_row = next(
            row for row in result["rows"]
            if row["id"] == "journey.watchtower_archer_emblem_and_bow"
        )
        archer_row["verifiedAssertions"] = []
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "explicitly verify" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        work_zone_row = next(
            row for row in result["rows"]
            if row["id"] == "role.lumberer_complete_physical_loop"
        )
        work_zone_row["verifiedAssertions"] = work_zone_row[
            "verifiedAssertions"
        ][:-1]
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "explicitly verify" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        frame_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "frame_report"
        )
        frame_path = run / frame_item["path"]
        original_frame = frame_path.read_bytes()
        frame_value = json.loads(original_frame)
        frame_value["baseline"]["runtimeJarSha256"] = "f" * 64
        write_json(frame_path, frame_value)
        frame_item["sha256"] = sha(frame_path)
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "runtime JAR hash differs" in rejected.stderr
        frame_path.write_bytes(original_frame)

        result = copy.deepcopy(baseline_result)
        frame_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "frame_report"
        )
        frame_path = run / frame_item["path"]
        original_frame = frame_path.read_bytes()
        frame_value = json.loads(original_frame)
        frame_value["acknowledgements"][0]["p95Ms"] = 15.5
        write_json(frame_path, frame_value)
        for row in result["rows"]:
            for item in row["evidence"]:
                if item["path"] == frame_item["path"]:
                    item["sha256"] = sha(frame_path)
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "acknowledgement roster is not the exact hashed client log" in rejected.stderr
        frame_path.write_bytes(original_frame)

        # A hand-edited PASS report must not select an older good window while
        # its exact hashed log contains a newer regression for the same state.
        result = copy.deepcopy(baseline_result)
        frame_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "frame_report"
        )
        frame_path = run / frame_item["path"]
        original_frame = frame_path.read_bytes()
        frame_value = json.loads(original_frame)
        selected = frame_value["checks"][0]["window"]
        profile = selected["displayProfile"]
        transition = selected["transition"]
        client_path = Path(frame_value["clientLog"])
        original_client = client_path.read_bytes()
        original_client_mtime_ns = client_path.stat().st_mtime_ns
        bad_nonce = bounded_nonce(f"new_bad_{transition}")
        bad_line = ack_line(
            profile, bad_nonce, baseline_manifest["candidateJar"]["sha256"],
            path_token(Path(baseline_manifest["gameDirectory"])),
            path_token(Path(baseline_manifest["installedJar"]["path"])),
            path_token(Path(baseline_manifest["worldDirectory"])),
            transition=transition,
            observed_millis=int(selected["observedEpochMillis"]),
        ).replace("frameP95Ms=16.0", "frameP95Ms=45.0") \
         .replace("frameP99Ms=20.0", "frameP99Ms=45.0") \
         .replace("frameMaxMs=25.0", "frameMaxMs=50.0") \
         .replace("framesOver33Ms=0", "framesOver33Ms=20")
        with client_path.open("a", encoding="utf-8") as log:
            log.write(bad_line)
        segment_index_path = run / baseline_manifest["nativeLogSegments"]["path"]
        original_segment_index = segment_index_path.read_bytes()
        segment_index_value = json.loads(original_segment_index)
        segment_record = next(
            record for record in segment_index_value["segments"]
            if (run / "logs" / record["path"]).resolve() == client_path.resolve()
        )
        segment_record.update({
            "sha256": sha(client_path),
            "sizeBytes": client_path.stat().st_size,
            **parse_native_log(
                client_path.read_bytes(), SESSION,
                int(segment_record["sealedEpochMillis"]),
            ),
        })
        os.utime(
            client_path,
            ns=(int(segment_record["sealedEpochMillis"]) * 1_000_000,) * 2,
        )
        write_json(segment_index_path, segment_index_value)
        attack_manifest = copy.deepcopy(baseline_manifest)
        attack_manifest["nativeLogSegments"]["sha256"] = sha(segment_index_path)
        write_json(run / "manifest.json", attack_manifest)
        bad_window = copy.deepcopy(selected)
        bad_window.update({
            "nonce": bad_nonce,
            "p95Ms": 45.0,
            "p99Ms": 45.0,
            "maxMs": 50.0,
            "framesOver33Ms": 20,
            "slowFraction": 20 / 180,
        })
        frame_value["acknowledgements"].append(bad_window)
        frame_value["clientLogSha256"] = sha(client_path)
        write_json(frame_path, frame_value)
        for row in result["rows"]:
            for item in row["evidence"]:
                if item["path"] == frame_item["path"]:
                    item["sha256"] = sha(frame_path)
                if (run / item["path"]).resolve() == client_path.resolve():
                    item["sha256"] = sha(client_path)
        write_json(run / "result.json", result)
        attack_seal = run.parent / "operator-attack-seal.jsonl"
        append_operator_input_seal(
            run, attack_seal, run.parent / "operator.key", SESSION,
            "native-contract-reviewer", operator_nonce="6" * 64,
            captured_epoch_millis=int(
                (FINISHED_AT + dt.timedelta(minutes=2)).timestamp() * 1000
            ),
        )
        rejected = invoke(run, input_seal=attack_seal,
                          precommit_nonce="6" * 64)
        assert rejected.returncode != 0
        assert "is not the newest matching ACK" in rejected.stderr, rejected.stderr
        frame_path.write_bytes(original_frame)
        client_path.write_bytes(original_client)
        os.utime(client_path, ns=(original_client_mtime_ns,) * 2)
        segment_index_path.write_bytes(original_segment_index)
        write_json(run / "manifest.json", baseline_manifest)

        result = copy.deepcopy(baseline_result)
        input_row = next(
            row for row in result["rows"]
            if row["id"] == "identity.keyboard_mouse_look_round_trip"
        )
        input_log = next(item for item in input_row["evidence"]
                         if item["kind"] == "client_log")
        input_log["locator"]["lineEnd"] = input_log["locator"]["lineStart"]
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0
        assert "lacks before/after native input acknowledgements" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        audio_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "audio"
        )
        del audio_item["captureReport"]
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "captureReport is missing" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        screenshots = []
        for row in result["rows"]:
            for item in row["evidence"]:
                if item["kind"] == "screenshot" and item["displayProfile"] == DEFAULT_PROFILE:
                    screenshots.append((row, item))
        first_row, first = screenshots[0]
        _, second = next(value for value in screenshots[1:]
                         if value[0]["id"] != first_row["id"])
        second_path = run / second["path"]
        original_second = second_path.read_bytes()
        second_path.write_bytes((run / first["path"]).read_bytes())
        second["sha256"] = sha(second_path)
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "decodes to generic visual evidence" in rejected.stderr
        second_path.write_bytes(original_second)

        result = copy.deepcopy(baseline_result)
        videos = [
            (row, item)
            for row in result["rows"]
            for item in row["evidence"]
            if item["kind"] == "video"
        ]
        first_row, first = videos[0]
        _, second = next(value for value in videos[1:]
                         if value[0]["id"] != first_row["id"])
        second_path = run / second["path"]
        original_second = second_path.read_bytes()
        second_path.write_bytes((run / first["path"]).read_bytes())
        second["sha256"] = sha(second_path)
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "reuses generic video evidence" in rejected.stderr
        second_path.write_bytes(original_second)

        result = copy.deepcopy(baseline_result)
        videos = [
            (row, item)
            for row in result["rows"]
            for item in row["evidence"]
            if item["kind"] == "video"
        ]
        first_row, first = videos[0]
        _, second = next(value for value in videos[1:]
                         if value[0]["id"] != first_row["id"])
        second_path = run / second["path"]
        original_second = second_path.read_bytes()
        ffmpeg = shutil.which("ffmpeg")
        assert ffmpeg is not None
        transcoded = subprocess.run(
            [ffmpeg, "-y", "-v", "error", "-i", str(run / first["path"]),
             "-an", "-c:v", "libx264", "-qp", "0", "-pix_fmt", "yuv420p",
             str(second_path)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            check=False,
        )
        assert transcoded.returncode == 0, transcoded.stderr
        assert sha(second_path) != sha(run / first["path"])
        second["sha256"] = sha(second_path)
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "decodes to generic video evidence" in rejected.stderr
        second_path.write_bytes(original_second)

        result = copy.deepcopy(baseline_result)
        server_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "server_log"
        )
        server_item["locator"]["authorityEvent"] = "NOT_IN_NATIVE_LOG"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert rejected.returncode != 0 and "exact V1 authorityEvent" in rejected.stderr

        result = copy.deepcopy(baseline_result)
        server_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "server_log"
        )
        server_item["locator"]["authorityResult"] = (
            "OBSERVED"
            if server_item["locator"]["authorityResult"] == "COMMITTED"
            else "COMMITTED"
        )
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert (rejected.returncode != 0
                and "event/result differs" in rejected.stderr)

        result = copy.deepcopy(baseline_result)
        server_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "server_log"
        )
        server_item["locator"]["authorityTarget"] = "row:forged_target"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert (rejected.returncode != 0
                and "target differs" in rejected.stderr)

        result = copy.deepcopy(baseline_result)
        server_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "server_log"
        )
        server_item["locator"]["authorityReason"] = "forged_reason"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert (rejected.returncode != 0
                and "reason differs" in rejected.stderr)

        result = copy.deepcopy(baseline_result)
        server_item = next(
            item for row in result["rows"] for item in row["evidence"]
            if item["kind"] == "server_log"
        )
        server_item["locator"]["authorityRequirement"] = "forged_requirement"
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert (rejected.returncode != 0
                and "not bound to a matrix authority requirement" in rejected.stderr)

        result = copy.deepcopy(baseline_result)
        restart_row = next(
            row for row in result["rows"]
            if row["id"] == "journey.restart_persistence"
        )
        matching_indices = [
            index for index, item in enumerate(restart_row["evidence"])
            if item["kind"] == "server_log"
            and item["locator"].get("authorityRequirement") == "state_reload"
        ]
        assert len(matching_indices) == 2
        del restart_row["evidence"][matching_indices[-1]]
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert (rejected.returncode != 0
                and "expected exactly 2" in rejected.stderr)

        result = copy.deepcopy(baseline_result)
        equipment_authority = [
            (row, item)
            for row in result["rows"]
            for item in row["evidence"]
            if item["kind"] == "server_log"
            and item["locator"].get("authorityEvent")
                == "EQUIPMENT_REQUEST_OPENED"
        ]
        assert len(equipment_authority) >= 2
        first_row, first_item = equipment_authority[0]
        second_row, second_item = next(
            pair for pair in equipment_authority[1:]
            if pair[0]["id"] != first_row["id"]
        )
        first_locator = first_item["locator"]
        second_requirement = second_item["locator"]["authorityRequirement"]
        second_item["path"] = first_item["path"]
        second_item["sha256"] = first_item["sha256"]
        second_item["locator"] = copy.deepcopy(first_locator)
        second_item["locator"]["state"] = second_row["id"]
        second_item["locator"]["authorityRequirement"] = second_requirement
        if second_item["locator"]["lineStart"] > 1:
            second_item["locator"]["lineStart"] -= 1
        else:
            second_item["locator"]["lineEnd"] += 1
        write_json(run / "result.json", result)
        rejected = invoke(run)
        assert (rejected.returncode != 0
                and "reuses an authority log line" in rejected.stderr)

    print("release-client evidence validator contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
