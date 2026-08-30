#!/usr/bin/env python3
"""Contract tests for native frame-stat extraction and absolute UI gates."""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "qa" / "scripts" / "check_client_frame_stats.py"
JAR_SHA = "a" * 64
SESSION = "native-session-20260828"
PROFILE = "1280x720-gui3-en_us"
GAME_DIRECTORY_TOKEN = "b" * 64
RUNTIME_JAR_PATH_TOKEN = "c" * 64
WORLD_PATH_TOKEN = "d" * 64


def ack(transition: str, *, p95: float = 16.0, maximum: float = 25.0,
        over_33: int = 0, over_100: int = 0,
        include_over_100: bool = True, samples: int = 180,
        nonce: str | None = None, jar_sha: str = JAR_SHA,
        ui_state: str = "ready", runtime_source: str = "mod_list",
        game_directory_token: str = GAME_DIRECTORY_TOKEN,
        runtime_jar_path_token: str = RUNTIME_JAR_PATH_TOKEN,
        world_path_token: str = WORLD_PATH_TOKEN,
        integrated_server: bool = True) -> str:
    suffix = f" framesOver100Ms={over_100}" if include_over_100 else ""
    nonce = nonce or transition
    screen = "null" if transition == "screen_none" else "HearthScreen"
    screen_class = (
        "null" if transition == "screen_none"
        else "com.hearthstead.client.screen.HearthScreen"
    )
    state = "unavailable" if transition == "screen_none" else ui_state
    return (
        "[00:00:00] [Render thread/INFO] [hearthstead/]: HSQA_FRAME_ACK "
        f"nonce={nonce} qaSession={SESSION} observerSource=one_shot_marker "
        "nativeWindows=true "
        f"runtimeJarSha256={jar_sha} runtimeJarSource={runtime_source} "
        f"gameDirectoryToken={game_directory_token} "
        f"runtimeJarPathToken={runtime_jar_path_token} "
        f"worldPathToken={world_path_token} "
        f"integratedServer={'true' if integrated_server else 'false'} "
        "observedEpochMillis=1787880000000 "
        f"screen={screen} screenClass={screen_class} "
        "grabbed=true yaw=12.0 pitch=-4.0 playerPos=10.0,64.0,20.0 "
        "hitType=miss hitBlock=none "
        "guiScale=3.0 framebuffer=1280x720 language=en_us "
        "blessingTitleResolved=true blessingTitleLanguageMatch=true "
        f"uiState={state} uiTransition=" + transition
        + f" frameSamples={samples} frameP50Ms=12.0 "
        f"frameP95Ms={p95} frameP99Ms={p95} frameMaxMs={maximum} "
        f"framesOver33Ms={over_33}{suffix}\n"
    )


def invoke(log: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(log),
         "--baseline", "screen_none", "--require", "screen_HearthScreen",
         "--min-samples", "180", "--max-p95-ratio", "1.35",
         "--max-slow-rise", "0.10", "--max-p95-ms", "33.3",
         "--max-frame-ms", "100", "--max-frames-over100", "0",
         "--expected-jar-sha256", JAR_SHA,
         "--expected-session", SESSION,
         "--expected-profile", PROFILE, "--require-native-windows",
         "--expected-game-directory-token", GAME_DIRECTORY_TOKEN,
         "--expected-runtime-jar-path-token", RUNTIME_JAR_PATH_TOKEN,
         "--expected-world-path-token", WORLD_PATH_TOKEN,
         "--require-integrated-server"],
        cwd=ROOT, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="hsqa-frame-stats-") as temporary:
        log = Path(temporary) / "client.log"
        log.write_text(ack("screen_none") + ack("screen_HearthScreen"),
                       encoding="utf-8")
        passed = invoke(log)
        assert passed.returncode == 0, (passed.stdout, passed.stderr)

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", include_over_100=False),
            encoding="utf-8",
        )
        missing = invoke(log)
        assert missing.returncode != 0 and "framesOver100Ms" in missing.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", p95=40.0, maximum=45.0,
                  over_33=12),
            encoding="utf-8",
        )
        slow = invoke(log)
        assert slow.returncode != 0 and "p95 40.0ms exceeds 33.3ms" in slow.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", p95=20.0, maximum=140.0,
                  over_33=1, over_100=1),
            encoding="utf-8",
        )
        hitch = invoke(log)
        assert hitch.returncode != 0
        assert "1 frames over 100ms exceeds 0" in hitch.stdout

        # A stale wider pass must not hide the newest measured regression.
        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", samples=360, nonce="old-good")
            + ack("screen_HearthScreen", p95=45.0, maximum=50.0,
                  over_33=20, samples=180, nonce="new-bad"),
            encoding="utf-8",
        )
        newest = invoke(log)
        assert newest.returncode != 0
        assert "p95 45.0ms exceeds 33.3ms" in newest.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", jar_sha="b" * 64),
            encoding="utf-8",
        )
        wrong_jar = invoke(log)
        assert wrong_jar.returncode != 0
        assert "runtime JAR hash differs" in wrong_jar.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", runtime_source="development_directory"),
            encoding="utf-8",
        )
        wrong_source = invoke(log)
        assert wrong_source.returncode != 0
        assert "runtimeJarSource is not an installed-mod JAR source" in wrong_source.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", runtime_jar_path_token="e" * 64),
            encoding="utf-8",
        )
        wrong_path = invoke(log)
        assert wrong_path.returncode != 0
        assert "runtime JAR path token differs" in wrong_path.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", world_path_token="e" * 64),
            encoding="utf-8",
        )
        wrong_world = invoke(log)
        assert wrong_world.returncode != 0
        assert "world path token differs" in wrong_world.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", integrated_server=False),
            encoding="utf-8",
        )
        not_integrated = invoke(log)
        assert not_integrated.returncode != 0
        assert "not bound to the integrated server" in not_integrated.stdout

        log.write_text(
            ack("screen_none")
            + ack("screen_HearthScreen", ui_state="unavailable"),
            encoding="utf-8",
        )
        unavailable = invoke(log)
        assert unavailable.returncode != 0
        assert "unusable uiState" in unavailable.stdout

    print("client frame-stat contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
