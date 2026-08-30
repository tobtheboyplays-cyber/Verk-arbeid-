#!/usr/bin/env python3
"""Contracts for the one-shot marker and native-log sealing helper."""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from unittest import mock

from native_evidence_contract import sha256_file

from native_release_session import (
    MARKER_HEADER,
    MARKER_NAME,
    NativeSessionError,
    append_launch_registry,
    append_operator_input_seal,
    create_marker,
    input_precommit_line,
    marker_payload,
    parse_native_log,
    path_token,
    seal_log_segment,
    validate_marker_payload,
)


SESSION = "native-session-contract"
PROFILE = "1280x720-gui3-en_us"
TOKEN = "a" * 64


def authority_line(clock: str, target: str, tick: int) -> str:
    return (
        f"[{clock}] [Server thread/INFO] [hearthstead/]: "
        "HEARTHSTEAD_AUTHORITY_V1 event=STATE_LOAD_SUMMARY result=OBSERVED "
        f"settlement=none target={target} revision_before=0 revision_after=0 "
        "count_before=0 count_after=0 item=none item_before=0 item_after=0 "
        f"item_expected_delta=0 item_conserved=true reason=restart tick={tick}"
    )


def rejects(callable_value, expected: str) -> None:
    try:
        callable_value()
    except NativeSessionError as exc:
        assert expected.lower() in str(exc).lower(), (expected, str(exc))
    else:
        raise AssertionError(f"expected NativeSessionError containing {expected!r}")


def native_log(nonce: str, observed_millis: int, *, server_line: str | None = None) -> bytes:
    lines = [
        "[20:00:00] [Render thread/INFO] [hearthstead/]: "
        f"HSQA_OBSERVER_ENABLED source=one_shot_marker qaSession={SESSION} "
        "markerConsumed=true "
        f"markerCreatedEpochSeconds={observed_millis // 1000 - 30} "
        f"markerExpiresEpochSeconds={observed_millis // 1000 + 600}\n",
        "[20:00:01] [Render thread/INFO] [hearthstead/]: HSQA_FRAME_ACK "
        f"nonce={nonce} qaSession={SESSION} observerSource=one_shot_marker "
        "nativeWindows=true runtimeJarSha256=" + TOKEN + " "
        "runtimeJarSource=code_source "
        "gameDirectoryToken=" + TOKEN + " runtimeJarPathToken=" + TOKEN + " "
        "worldPathToken=" + TOKEN + " integratedServer=true "
        f"observedEpochMillis={observed_millis} "
        "screen=HearthScreen screenClass=com.hearthstead.client.screen.HearthScreen "
        "grabbed=true auxButtonBound=none yaw=0.0 pitch=0.0 "
        "playerPos=10.0,64.0,20.0 hitType=miss hitBlock=none "
        "guiScale=3.0 gui=426x240 framebuffer=1280x720 language=en_us "
        "blessingTitleResolved=true blessingTitleLanguageMatch=true "
        "uiState=ready uiTransition=screen_HearthScreen frameSamples=180 "
        "frameP50Ms=12.0 frameP95Ms=16.0 frameP99Ms=20.0 frameMaxMs=25.0 "
        "framesOver33Ms=0 framesOver100Ms=0\n",
    ]
    if server_line is not None:
        lines.append(server_line + "\n")
    return "".join(lines).encode("utf-8")


def main() -> int:
    now_seconds = 2_000_000_000
    valid = marker_payload(SESSION, now_seconds, now_seconds + 900)
    assert valid.startswith((MARKER_HEADER + "\n").encode("ascii"))
    assert validate_marker_payload(valid, SESSION, now_seconds) \
        == (now_seconds, now_seconds + 900)
    rejects(
        lambda: validate_marker_payload(
            valid.replace(b"HEARTHSTEAD_NATIVE_QA_V2", b"HEARTHSTEAD_NATIVE_QA_V1"),
            SESSION, now_seconds,
        ),
        "V2 four-line",
    )
    rejects(
        lambda: validate_marker_payload(
            marker_payload(SESSION, now_seconds - 301, now_seconds + 60),
            SESSION, now_seconds,
        ),
        "stale",
    )
    rejects(
        lambda: validate_marker_payload(
            marker_payload(SESSION, now_seconds, now_seconds + 3601),
            SESSION, now_seconds,
        ),
        "one-hour ceiling",
    )
    strict_log = native_log("strict_nonce", now_seconds * 1000)
    rejects(
        lambda: parse_native_log(
            strict_log.replace(
                b"nonce=strict_nonce ",
                b"nonce=strict_nonce nonce=duplicate ",
            ),
            SESSION, now_seconds * 1000,
        ),
        "missing, extra",
    )
    rejects(
        lambda: parse_native_log(
            strict_log.replace(
                b"[Render thread/INFO] [hearthstead/]: HSQA_FRAME_ACK",
                b"[Render thread/INFO] [other/]: hearthstead HSQA_FRAME_ACK",
            ),
            SESSION, now_seconds * 1000,
        ),
        "exact Render thread/hearthstead",
    )
    rejects(
        lambda: parse_native_log(
            strict_log.replace(
                b"source=one_shot_marker qaSession=",
                b"source=one_shot_marker source=environment qaSession=",
            ),
            SESSION, now_seconds * 1000,
        ),
        "missing, extra",
    )

    with tempfile.TemporaryDirectory(prefix="hsqa-native-session-") as temporary:
        root = Path(temporary)
        game = root / "profile"
        (game / "logs").mkdir(parents=True)
        run = root / "run"
        (run / "logs").mkdir(parents=True)

        (game / "mods").mkdir()
        (game / "saves" / "fresh-world").mkdir(parents=True)
        java_image = game / "runtime" / "javaw.exe"
        java_image.parent.mkdir()
        java_image.write_bytes(b"java-runtime")
        runtime_jar = game / "mods" / "hearthstead.jar"
        runtime_jar.write_bytes(b"installed-hearthstead-runtime")

        created = create_marker(
            game, game, SESSION, ttl_seconds=900,
            now_epoch_seconds=now_seconds, environment={},
        )
        marker = game / MARKER_NAME
        assert created["status"] == "CREATED" and marker.is_file()
        assert marker.read_bytes() == valid
        rejects(
            lambda: create_marker(
                game, game, SESSION, now_epoch_seconds=now_seconds,
                environment={},
            ),
            "already exists",
        )
        marker.unlink()

        marker.write_text("malformed\n", encoding="utf-8")
        rejects(
            lambda: create_marker(
                game, game, SESSION, now_epoch_seconds=now_seconds,
                environment={},
            ),
            "already exists",
        )
        marker.unlink()
        marker.write_bytes(marker_payload(
            SESSION, now_seconds - 301, now_seconds + 60,
        ))
        rejects(
            lambda: create_marker(
                game, game, SESSION, now_epoch_seconds=now_seconds,
                environment={},
            ),
            "already exists",
        )
        marker.unlink()
        rejects(
            lambda: create_marker(
                game, game, SESSION, now_epoch_seconds=now_seconds,
                environment={
                    "HSQA_CLIENT_OBSERVER": "1",
                    "HSQA_CLIENT_SESSION": SESSION,
                },
            ),
            "ambiguous",
        )
        other = root / "other-profile"
        other.mkdir()
        rejects(
            lambda: create_marker(
                game, other, SESSION, now_epoch_seconds=now_seconds,
                environment={},
            ),
            "exact expected",
        )

        now_millis = now_seconds * 1000
        latest = game / "logs" / "latest.log"
        latest.write_bytes(native_log(
            "first", now_millis - 2_000,
            server_line=authority_line("20:00:02", "restart_before", 40),
        ))
        os.utime(latest, (now_seconds - 1, now_seconds - 1))
        first = seal_log_segment(
            game, game, run, SESSION, PROFILE,
            now_epoch_millis=now_millis,
        )
        assert first["status"] == "SEALED"
        assert first["segment"]["index"] == 1
        assert first["segment"]["serverAuthorityLineCount"] == 1
        rejects(
            lambda: seal_log_segment(
                game, game, run, SESSION, PROFILE,
                now_epoch_millis=now_millis,
            ),
            "byte-identical",
        )

        latest.write_bytes(native_log(
            "second", now_millis - 1_000,
            server_line=authority_line("20:10:02", "restart_after", 80),
        ))
        os.utime(latest, (now_seconds, now_seconds))
        second = seal_log_segment(
            game, game, run, SESSION, PROFILE,
            now_epoch_millis=now_millis,
        )
        assert second["segment"]["index"] == 2
        index = json.loads((run / "logs" / "native-log-segments.json")
                           .read_text(encoding="utf-8"))
        assert [entry["index"] for entry in index["segments"]] == [1, 2]
        assert len({entry["sha256"] for entry in index["segments"]}) == 2

        first_launch = append_launch_registry(
            run, SESSION, "launch_nonce_contract_0001", 24680,
            now_millis - 8_000, java_image, game, game, runtime_jar,
            game / "saves" / "fresh-world", PROFILE,
            registered_epoch_millis=now_millis - 7_000,
        )
        second_launch = append_launch_registry(
            run, SESSION, "launch_nonce_contract_0002", 24681,
            now_millis - 4_000, java_image, game, game, runtime_jar,
            game / "saves" / "fresh-world", PROFILE,
            registered_epoch_millis=now_millis - 3_000,
        )
        assert first_launch["launch"]["launchIndex"] == 1
        assert second_launch["launch"]["launchIndex"] == 2
        rejects(
            lambda: append_launch_registry(
                run, SESSION, "launch_nonce_contract_0003", 24681,
                now_millis - 2_000, java_image, game, game, runtime_jar,
                game / "saves" / "fresh-world", PROFILE,
                registered_epoch_millis=now_millis - 1_000,
            ),
            "PID is already registered",
        )
        rejects(
            lambda: append_launch_registry(
                run, SESSION, "launch_nonce_contract_0004", 24682,
                now_millis - 6_000, java_image, game, game, runtime_jar,
                game / "saves" / "fresh-world", PROFILE,
                registered_epoch_millis=now_millis - 1_000,
            ),
            "process creation time does not prove a new launch",
        )
        rejects(
            lambda: append_launch_registry(
                run, "different-native-session", "launch_nonce_contract_0005",
                24683, now_millis - 2_000, java_image, game, game,
                runtime_jar, game / "saves" / "fresh-world", PROFILE,
                registered_epoch_millis=now_millis - 1_000,
            ),
            "belongs to another native session",
        )

        import windows_native_input as native_input

        registry_path = run / "logs" / "native-launch-registry.jsonl"
        transcript = run / "logs" / "native-input.jsonl"
        original_root = native_input.TRANSCRIPT_ROOT
        native_input.TRANSCRIPT_ROOT = root
        try:
            with mock.patch.object(native_input.time, "time",
                                   return_value=now_seconds):
                for launch_result in (first_launch, second_launch):
                    launch_record = launch_result["launch"]
                    window = native_input.WindowInfo(
                        hwnd=100_000 + int(launch_record["launchIndex"]),
                        pid=int(launch_record["pid"]),
                        title="Minecraft 1.21.1",
                        class_name="GLFW30",
                        process_path=str(java_image.resolve()),
                        client_left=0,
                        client_top=0,
                        client_width=1280,
                        client_height=720,
                    )
                    binding = native_input.LaunchBinding(
                        launch_index=int(launch_record["launchIndex"]),
                        native_session_id=SESSION,
                        launch_nonce=str(launch_record["launchNonce"]),
                        pid=int(launch_record["pid"]),
                        process_creation_epoch_millis=int(
                            launch_record["processCreationEpochMillis"]
                        ),
                        registered_epoch_millis=int(
                            launch_record["registeredEpochMillis"]
                        ),
                        process_path=str(launch_record["processPath"]),
                        game_directory=str(launch_record["gameDirectory"]),
                        runtime_jar_path=str(launch_record["runtimeJarPath"]),
                        runtime_jar_sha256=str(launch_record["runtimeJarSha256"]),
                        world_directory=str(launch_record["worldDirectory"]),
                        display_profile=str(launch_record["displayProfile"]),
                        observer_log_segment=str(launch_record["observerLogSegment"]),
                        launch_identity_sha256=str(
                            launch_record["launchIdentitySha256"]
                        ),
                    )
                    target = native_input.BoundTarget(
                        window, binding, registry_path, sha256_file(registry_path)
                    )
                    native_input.append_transcript(
                        transcript, SESSION, target, "INTENT", "focus", {},
                    )
                    native_input.append_transcript(
                        transcript, SESSION, target, "COMPLETED", "focus", {},
                        {
                            "foregroundHwnd": window.hwnd,
                            "boundPid": window.pid,
                            "launchNonce": binding.launch_nonce,
                            "processCreationEpochMillis": (
                                binding.process_creation_epoch_millis
                            ),
                            "allEventsDelivered": True,
                            "cleanupRequired": False,
                        },
                    )
        finally:
            native_input.TRANSCRIPT_ROOT = original_root

        operator_key = root / "operator.key"
        operator_key.write_bytes(b"operator-held-contract-key-material" * 2)
        precommit = input_precommit_line(
            run, operator_key, SESSION, "1" * 64, game, game,
            runtime_jar, game / "saves" / "fresh-world",
        )
        assert precommit == (
            "HSQA_INPUT_PRECOMMIT_V1 "
            f"operatorKeySha256={sha256_file(operator_key)} "
            f"nativeSessionId={SESSION} sessionNonce={'1' * 64} "
            f"runDirectoryToken={path_token(run)} "
            f"runtimeJarSha256={sha256_file(runtime_jar)} worldId=fresh-world"
        )
        in_run_key = run / "operator.key"
        in_run_key.write_bytes(b"operator-held-contract-key-material" * 2)
        rejects(
            lambda: input_precommit_line(
                run, in_run_key, SESSION, "1" * 64, game, game,
                runtime_jar, game / "saves" / "fresh-world",
            ),
            "outside the run artifact",
        )
        rejects(
            lambda: append_operator_input_seal(
                run, run / "logs" / "native-input-seals.jsonl",
                operator_key, SESSION, "contract-reviewer",
                operator_nonce="2" * 64,
            ),
            "outside the run artifact",
        )
        seal_dir = root / "operator-seals"
        seal_dir.mkdir()
        seal_ledger = seal_dir / "native-input-seals.jsonl"
        sealed = append_operator_input_seal(
            run, seal_ledger, operator_key, SESSION, "contract-reviewer",
            operator_nonce="1" * 64,
            captured_epoch_millis=now_millis + 1_000,
        )
        assert sealed["status"] == "OPERATOR_SEALED"
        rejects(
            lambda: append_operator_input_seal(
                run, seal_ledger, operator_key, SESSION, "contract-reviewer",
                operator_nonce="2" * 64,
                captured_epoch_millis=now_millis + 2_000,
            ),
            "already contains this session",
        )

        fabricated = native_log(
            "third", now_millis,
            server_line=(
                "[20:20:02] [Render thread/INFO] [hearthstead/]: "
                "HEARTHSTEAD_AUTHORITY_V1 event=STATE_LOAD_SUMMARY "
                "result=OBSERVED settlement=none target=fake "
                "revision_before=0 revision_after=0 count_before=0 count_after=0 "
                "item=none item_before=0 item_after=0 item_expected_delta=0 "
                "item_conserved=true reason=restart tick=120 Server thread/INFO"
            ),
        )
        assert parse_native_log(fabricated, SESSION, now_millis)[
            "serverAuthorityLineCount"
        ] == 0

    print("native release-session helper contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
