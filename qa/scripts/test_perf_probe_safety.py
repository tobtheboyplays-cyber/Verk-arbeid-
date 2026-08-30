#!/usr/bin/env python3
"""Executable fail-closed contract for the performance QA launcher.

This test deliberately exercises the production ``lib_harness.sh`` launcher
and its real exec entrypoint. It must not carry a second, simplified copy of
the launch protocol: that is exactly how the old test stayed green after the
real launcher had changed underneath it.
"""

from __future__ import annotations

import json
import os
import re
import secrets
import signal
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / "qa" / "scripts" / "perf_probe.sh"
HARNESS = ROOT / "qa" / "scripts" / "lib_harness.sh"
ENTRY = ROOT / "qa" / "scripts" / "tracked_exec_entry.sh"
INSTANCE = ROOT / "qa" / "scripts" / "server_instance.sh"
SAFE_LIB = ROOT / "qa" / "scripts" / "lib_safe_paths.sh"
CONTROLLER = ROOT / "tools" / "hearthstead-qa"
FINGERPRINT = ROOT / "qa" / "scripts" / "source_fingerprint.sh"
STRESS_ITERATIONS = 120
STRESS_ROLE_STEMS = (("direct", "d"), ("nonexec", "n"), ("pipeline", "p"))
RUN_NONCE = secrets.token_hex(12)
FIXTURE_TOKEN_FILE = ".hsqa-reap-fixture-token"
PID_ROOT_MARKER_BYTES = b"hsqa-pid-root-v1\n"
REPO_HOSTILE_ARTIFACTS = tuple(
    ROOT / f"{stem}{suffix}"
    for stem in ("hostile-bash-env", "hostile-env-ran", "hostile-function-ran")
    for suffix in ("", "-a", "-b")
)
EXPECTED_SUITES = (
    "doctor", "assets", "animation", "build", "gametest", "behavior",
    "dedicated", "blessing_restart", "performance", "client", "playtest",
    "visual",
)
SAFE_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
PRODUCTION_PIDDIR = Path("/tmp/claude-0/hsqa-pids-v2")
REAPER = ROOT / "qa" / "scripts" / "reap.sh"
SANITIZED_BASH = (
    "/usr/bin/env", "-u", "BASH_ENV", "-u", "ENV", "-u", "SHELLOPTS",
    "-u", "BASHOPTS", f"PATH={SAFE_PATH}",
    "/bin/bash", "--noprofile", "--norc", "-p",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def require_plain_owned_file(
    path: Path, context: str, *, modes: tuple[int, ...], size: int | None = None,
) -> os.stat_result:
    """Validate one exact local QA authority without following a final symlink."""
    require(os.path.lexists(path), f"{context} is missing: {path}")
    metadata = path.lstat()
    require(stat.S_ISREG(metadata.st_mode) and not path.is_symlink(),
            f"{context} is not a plain regular file: {path}")
    require(metadata.st_uid == os.getuid() and metadata.st_nlink == 1,
            f"{context} has unsafe owner/link count: {path}")
    require(stat.S_IMODE(metadata.st_mode) in modes,
            f"{context} has unsafe mode: {path}")
    if size is not None:
        require(metadata.st_size == size,
                f"{context} has wrong size: {path}")
    return metadata


def require_safe_empty_pidfile(pidfile: Path, context: str) -> None:
    """Require the exact retained sentinel shape accepted by reap check."""
    require(re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}\.pids", pidfile.name)
            is not None, f"{context} has an invalid role path: {pidfile}")
    pid_root = pidfile.parent
    root_metadata = pid_root.lstat()
    require(stat.S_ISDIR(root_metadata.st_mode) and not pid_root.is_symlink()
            and root_metadata.st_uid == os.getuid(),
            f"{context} has an unsafe PID root: {pid_root}")

    marker = pid_root / ".hsqa-pid-root-owned"
    require_plain_owned_file(marker, f"{context} ownership marker",
                             modes=(0o600, 0o644),
                             size=len(PID_ROOT_MARKER_BYTES))
    require(marker.read_bytes() == PID_ROOT_MARKER_BYTES,
            f"{context} ownership marker has wrong bytes")

    require_plain_owned_file(pidfile, f"{context} empty sentinel",
                             modes=(0o600,), size=0)
    require(pidfile.read_bytes() == b"",
            f"{context} empty sentinel is not byte-empty")
    lockfile = pid_root / f"{pidfile.name}.lock"
    require_plain_owned_file(lockfile, f"{context} role lock",
                             modes=(0o600, 0o644), size=0)


def require_no_pidfiles(pid_root: Path, context: str) -> None:
    published = sorted(path.name for path in pid_root.glob("*.pids")) \
        if pid_root.is_dir() else []
    require(not published,
            f"{context} published PID authority before registration: {published}")


def require_fixture_pid_root_clean(
    fixture_root: Path, token: str, expected_roles: set[str], context: str,
) -> None:
    """Prove exact sentinels and have the frozen reaper inspect them as clean."""
    pid_root = fixture_root / "pids"
    actual_roles = {
        path.name[:-5] for path in pid_root.glob("*.pids")
        if path.name.endswith(".pids")
    }
    require(actual_roles == expected_roles,
            f"{context} retained the wrong sentinel roster: "
            f"expected={sorted(expected_roles)} actual={sorted(actual_roles)}")
    for role in sorted(expected_roles):
        require_safe_empty_pidfile(pid_root / f"{role}.pids", context)
    inspected = subprocess.run(
        bash_command(str(REAPER), "check"), cwd=ROOT,
        env=clean_environment(
            HSQA_REAP_FIXTURE_ROOT=str(fixture_root),
            HSQA_REAP_FIXTURE_TOKEN=token,
        ), text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=20, check=False,
    )
    require(inspected.returncode == 0,
            f"{context} was not clean under reaper inspection: "
            f"stdout={inspected.stdout!r} stderr={inspected.stderr!r}")


def require_clean_shell_diagnostics(stderr: str, context: str) -> None:
    require(not re.search(
        r"(?im)(?:^|\s)trap:|syntax error|unexpected EOF|unmatched",
        stderr,
    ), f"{context} emitted a shell parser/trap diagnostic: {stderr!r}")


def clean_environment(**extra: str) -> dict[str, str]:
    """Minimal deterministic environment; never forward ambient HSQA/shell hooks."""
    environment = {
        "PATH": SAFE_PATH,
        "HOME": "/tmp",
        "LC_ALL": "C",
        "LANG": "C",
    }
    environment.update(extra)
    return environment


def bash_command(*arguments: str) -> list[str]:
    return [*SANITIZED_BASH, *arguments]


def static_contract(probe: str, harness: str, entry: str) -> None:
    launches = re.findall(r"(?m)^\s*hsqa_launch_tracked_group\b", probe)
    require(len(launches) == 5,
            "crash fixture, build, install, baseline and active must use the shared launcher")
    for identity in (
        "PERF_BUILD_ID", "PERF_INSTALL_ID", "PERF_SERVER_ID",
        "PERF_ACTIVE_ID", "PERF_CRASH_ID",
    ):
        require(identity in probe, f"missing run-scoped performance identity {identity}")
    require('PERF_PROCESS_ROLE="performance-${PERF_RUN_NONCE:0:16}"' in probe,
            "performance recovery role is not run-scoped")
    require("/tmp/claude-0/hsqa-pids-v2" in probe,
            "production performance recovery authority is not canonical")
    require("validate_perf_fixture_authority" in probe
            and "^/tmp/hsqa-reap-fixture" in probe
            and "^[0-9a-f]{48}$" in probe,
            "isolated performance authority lacks exact root/token authentication")
    require('HSQA_PIDDIR="$PERF_FIXTURE_ROOT/pids"' in probe,
            "fixture PIDDIR must be derived, never accepted independently")
    require('PERF_FIXTURE_MODE" = post-release-crash' in probe
            and "perf-controller-ready" in probe
            and "perf-payload-ready" in probe,
            "post-release crash seam is missing")

    require(probe.count("--kill-after=10") == 4,
            "all four performance phases need bounded timeout escalation")
    require(probe.count("--no-daemon") == 2,
            "both Gradle phases must be isolated single-use processes")
    require('require_uint HSQA_PERFORMANCE_PORT "$PORT" 1024 65535' in probe,
            "performance port must be validated before interpolation")
    for exact in (
        'require_uint HSQA_PERF_WARMUP_SECONDS "$WARMUP_SECONDS" 6 1800',
        'require_uint HSQA_PERF_SAMPLE_COUNT "$SAMPLE_COUNT" 5 1000',
        'require_uint HSQA_PERF_SAMPLE_INTERVAL_SECONDS "$SAMPLE_INTERVAL_SECONDS" 2 300',
        'require_uint HSQA_PERF_START_DELAY_SECONDS "$START_DELAY_SECONDS" 20 300',
    ):
        require(exact in probe,
                f"performance profile minimum is missing: {exact}")
    require("INST=$(env HSQA_LEVEL_TYPE=flat" in probe,
            "performance instance must pin flat world in the scoped child")
    require("grep -Fxc 'level-type=minecraft\\:flat'" in probe,
            "performance must verify the authored flat server.properties line")
    require("trap on_exit EXIT" in probe,
            "fail-closed EXIT cleanup must remain installed")
    require("trap 'on_signal 130' INT" in probe,
            "INT must terminate through artifact-aware cleanup")
    require("trap 'on_signal 143' TERM" in probe,
            "TERM must terminate through artifact-aware cleanup")
    require("on_prelaunch_signal" in probe
            and "received $signal_name before process launch" in probe,
            "pre-launch signals must produce explicit FAIL evidence")
    require("finish_result FAIL" in probe and "write_reproduction" in probe,
            "signal paths must complete result and reproduction artifacts")
    require("pre-existing performance recovery records" in probe,
            "a previous unresolved record must block a new performance run")
    require("pkill" not in probe and "clear_pidfile" not in probe,
            "performance cleanup may not use broad kills or whole-role clearing")

    for stem in ("BUILD", "INSTALL", "SERVER", "ACTIVE"):
        require(f'wait "${stem}_PID"' in probe,
                f"{stem} must preserve the authoritative child status")
        require(f"{stem}_STATUS=$?" in probe,
                f"{stem} exit status is not captured")
        require(
            f"complete_perf_phase {stem}_PID_RECORD {stem}_PID {stem}_PGID {stem}_PID_ROLE"
            in probe,
            f"{stem} does not prove exact post-phase absence",
        )

    require(
        'hsqa_build_tracked_entry_environment()' in harness
        and 'output_ref=(' in harness
        and '/usr/bin/env -i' in harness
        and '"PATH=$canonical_path"' in harness
        and '"HSQA_PROCESS_IDENTITY=$identity"' in harness
        and 'hsqa_build_tracked_entry_environment entry_environment "$identity"'
        in harness
        and '"${entry_environment[@]}"' in harness
        and '/bin/bash --noprofile --norc -p "$HSQA_TRACKED_ENTRY"' in harness,
        "shared launch must build and use one exact env-i privileged boundary",
    )
    require('/usr/bin/env -i' in entry
            and '"HSQA_PROCESS_IDENTITY=$HSQA_PROCESS_IDENTITY"' in entry
            and 'exec "${payload_environment[@]}" "$@"' in entry,
            "release boundary must rebuild an exact fresh payload environment")
    require(
        'register_pid "$role" "-$pgid" "$identity" "$child_start"'
        in harness,
        "registration must use the immutable child-published starttime",
    )
    require("hsqa_provisional_group_owned" in harness
            and "hsqa_terminate_provisional_group" in harness,
            "failed registration must clean only a fully identity-owned group")
    require("HSQA_LAUNCH_PENDING" in harness
            and "hsqa_defer_launch_signal" in harness,
            "INT/TERM must remain deferred until registration is durable or absent")

    # BASHPID changes inside command substitutions. Freeze the entrypoint PID
    # once, then use that same value for both /proc and publication.
    capture = entry.find('entry_pid="$BASHPID"')
    stat_read = entry.find('"/proc/$entry_pid/stat"')
    publication = entry.find('printf \'%s|%s\\n\' "$entry_pid" "$starttime"')
    require(0 <= capture < stat_read < publication,
            "exec entrypoint does not freeze one PID across starttime capture")
    require('printf \'%s|%s\\n\' "$BASHPID"' not in entry,
            "exec entrypoint republishes mutable BASHPID")
    parent_check = entry.find('"/proc/$expected_parent/stat"')
    release_wait = entry.find('if [ -f "$release" ]')
    acknowledgement = entry.find('printf \'%s\\n\' "$expected_release" > "$ack"')
    payload_exec = entry.find('exec "${payload_environment[@]}" "$@"')
    require(publication < parent_check < release_wait < acknowledgement < payload_exec,
            "exec entrypoint does not enforce parent-bound release/ack before payload exec")
    require("for _ in $(/usr/bin/seq 1 1000)" in entry,
            "exec entrypoint release wait is not bounded")


def marked_processes(identity: str) -> list[int]:
    marker = f"HSQA_PROCESS_IDENTITY={identity}".encode()
    found: list[int] = []
    proc = Path("/proc")
    if not proc.is_dir():
        return found
    for child in proc.iterdir():
        if not child.name.isdigit():
            continue
        try:
            environment = (child / "environ").read_bytes().split(b"\0")
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        if marker in environment:
            found.append(int(child.name))
    return found


def process_starttime(pid: int) -> str:
    try:
        text = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    except OSError as exc:
        raise AssertionError(f"could not observe /proc starttime for {pid}") from exc
    close = text.rfind(") ")
    require(close >= 0, f"malformed /proc stat for {pid}")
    fields = text[close + 2:].split()
    require(len(fields) >= 20 and fields[19].isdigit(),
            f"missing starttime for {pid}")
    return fields[19]


def wait_for_path(path: Path, timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            return
        time.sleep(0.01)
    raise AssertionError(f"timed out waiting for {path}")


def barrier_runtime_contract() -> None:
    """No release and dead parent must never reach the payload."""
    identity = f"stress-barrier-{RUN_NONCE}"
    with tempfile.TemporaryDirectory(prefix="hsqa-entry-barrier-") as directory:
        root = Path(directory).resolve()
        registration = root / "registration"
        release = root / "release"
        ack = root / "ack"
        sentinel = root / "payload-ran"
        hostile = root / "hostile-bash-env"
        hostile_sentinel = root / "hostile-ran"
        hostile.write_text(
            f'printf "hostile\\n" > "{hostile_sentinel}"\n', encoding="utf-8"
        )
        environment = clean_environment(
            HSQA_PROCESS_IDENTITY=identity,
            BASH_ENV=str(hostile),
            ENV=str(hostile),
            **{f"BASH_FUNC_hsqa_hostile%%": "() { return 99; }"},
        )
        parent_pid = os.getpid()
        command = bash_command(
            str(ENTRY), str(registration), str(release), str(ack),
            str(parent_pid), process_starttime(parent_pid), "--",
            "/bin/bash", "--noprofile", "--norc", "-p", "-c",
            'printf "payload\\n" > "$1"; sleep 30',
            "hsqa-barrier", str(sentinel),
        )
        entry_process = subprocess.Popen(
            command, env=environment, start_new_session=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
        )
        try:
            wait_for_path(registration)
            time.sleep(0.15)
            require(not sentinel.exists(),
                    "entry executed payload without a release token")
            require(not ack.exists(),
                    "entry acknowledged a release token that did not exist")
            require(not hostile_sentinel.exists(),
                    "sanitized entry sourced ambient BASH_ENV/ENV")
        finally:
            try:
                os.killpg(entry_process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            _, stderr = entry_process.communicate(timeout=5)
        require_clean_shell_diagnostics(stderr, "no-release entry barrier")

    with tempfile.TemporaryDirectory(
        prefix="hsqa-entry-parent-death-"
    ) as directory:
        root = Path(directory).resolve()
        registration = root / "registration"
        release = root / "release"
        ack = root / "ack"
        sentinel = root / "payload-ran"
        child_pid_path = root / "entry.pid"
        launcher_parent = os.fork()
        if launcher_parent == 0:
            try:
                parent_pid = os.getpid()
                environment = clean_environment(HSQA_PROCESS_IDENTITY=identity)
                entry_process = subprocess.Popen(
                    bash_command(
                        str(ENTRY), str(registration), str(release),
                        str(ack), str(parent_pid), process_starttime(parent_pid),
                        "--", "/bin/bash", "--noprofile", "--norc", "-p", "-c",
                        'printf "payload\\n" > "$1"; sleep 30',
                        "hsqa-parent-death", str(sentinel),
                    ),
                    env=environment,
                    start_new_session=True,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                child_pid_path.write_text(str(entry_process.pid), encoding="utf-8")
                wait_for_path(registration)
                os.kill(parent_pid, signal.SIGKILL)
            except BaseException:
                os._exit(99)
            os._exit(98)
        _, parent_status = os.waitpid(launcher_parent, 0)
        require(os.WIFSIGNALED(parent_status)
                and os.WTERMSIG(parent_status) == signal.SIGKILL,
                f"barrier parent did not die by SIGKILL: {parent_status}")
        wait_for_path(child_pid_path)
        child_pid = int(child_pid_path.read_text(encoding="utf-8"))
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and Path(f"/proc/{child_pid}").exists():
            time.sleep(0.01)
        if Path(f"/proc/{child_pid}").exists():
            try:
                os.killpg(child_pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            raise AssertionError("entry survived exact parent death")
        require(not sentinel.exists(),
                "entry executed payload after its parent died before release")
        require(not marked_processes(identity),
                "parent-death barrier left a marked process")


def cleanup_identity(identity: str) -> None:
    groups: set[int] = set()
    for pid in marked_processes(identity):
        try:
            groups.add(os.getpgid(pid))
        except ProcessLookupError:
            pass
    for pgid in groups:
        try:
            os.killpg(pgid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    for _ in range(100):
        if not marked_processes(identity):
            return
        time.sleep(0.01)


def kill_private_session_and_drain(
    process: subprocess.Popen[str], identities: tuple[str, ...], context: str,
    *, timeout: float = 10,
) -> tuple[str, str]:
    """Boundedly stop one start_new_session wrapper and its tracked payloads."""
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    for identity in identities:
        cleanup_identity(identity)
    try:
        return process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired as drain_timeout:
        stdout = drain_timeout.stdout or ""
        stderr = drain_timeout.stderr or ""
        if process.stdout is not None:
            process.stdout.close()
        if process.stderr is not None:
            process.stderr.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired as reap_timeout:
            raise AssertionError(
                f"{context} survived private-session SIGKILL and exact "
                "identity cleanup"
            ) from reap_timeout
        return stdout, stderr


def run_marked_shell(
    script: str,
    environment: dict[str, str],
    identities: tuple[str, ...],
    timeout: float,
    arguments: tuple[str, ...],
) -> subprocess.CompletedProcess[str]:
    process = subprocess.Popen(
        bash_command("-c", script, "--", *arguments),
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        # The tracked payloads run in their own process groups and inherit
        # these pipes.  Clean the private wrapper session and their exact
        # identities before a bounded drain so `doctor` can never wedge.
        stdout, stderr = kill_private_session_and_drain(
            process, identities, "shared launcher wrapper"
        )
        raise AssertionError(
            f"shared launcher mutant timed out: stdout={stdout!r} stderr={stderr!r}"
        )
    leftovers: dict[str, list[int]] = {}
    for identity in identities:
        pids = marked_processes(identity)
        if pids:
            leftovers[identity] = pids
    if leftovers:
        for identity in identities:
            cleanup_identity(identity)
        raise AssertionError(
            f"shared launcher mutant left marked processes {leftovers}: "
            f"stdout={stdout!r} stderr={stderr!r}"
        )
    return subprocess.CompletedProcess(
        process.args, process.returncode, stdout, stderr
    )


def stress_script() -> str:
    return r'''
set -u
repo="$1"; harness="$2"; fixture="$3"; iterations="$4"; nonce="$5"
worker="$6"; role_nonce="$7"; collision_role="$8"
export HSQA_REPO="$repo"
. "$harness"

require_empty_pidfile_sentinel() {
    local path="$1" uid
    uid=$(/usr/bin/id -u) || return 1
    [ -f "$path" ] && [ ! -L "$path" ] && [ ! -s "$path" ] \
        && [ "$(/usr/bin/stat -c '%u:%a:%h:%s' -- "$path" 2>/dev/null)" \
            = "$uid:600:1:0" ]
}

hostile_bash_env="$fixture/hostile-bash-env-$worker"
hostile_env_sentinel="$fixture/hostile-env-ran-$worker"
hostile_function_sentinel="$fixture/hostile-function-ran-$worker"
printf 'printf "hostile-env\\n" > "%s"\n' "$hostile_env_sentinel" \
    > "$hostile_bash_env"
export BASH_ENV="$hostile_bash_env" ENV="$hostile_bash_env"
export HSQA_HOSTILE_SENTINEL="$hostile_function_sentinel"
hsqa_hostile() { printf 'hostile-function\n' > "$HSQA_HOSTILE_SENTINEL"; }
export -f hsqa_hostile

current_role=''; current_pid=''; current_pgid=''; current_record=''
emergency_cleanup() {
    if [ -n "$current_record" ]; then
        hsqa_stop_tracked "$current_role" "$current_record" >/dev/null 2>&1 || true
    elif [[ "$current_pgid" =~ ^[1-9][0-9]*$ ]]; then
        kill -9 -- "-$current_pgid" 2>/dev/null || true
        [ -z "$current_pid" ] || wait "$current_pid" 2>/dev/null || true
    fi
}
trap emergency_cleanup EXIT

# Adversarial shared-role transaction: both parallel workers durably publish
# distinct exact records to the same pidfile. Worker A removes only its own
# record; worker B proves its record and group survived before removing the
# final record. This exercises the real shared recovery namespace.
collision_identity="stress-collision-$nonce"
current_role="$collision_role"
current_pid=''; current_pgid=''; current_record=''
hsqa_launch_tracked_group "$current_role" "$collision_identity" '' \
        current_pid current_pgid current_record current_role sleep 60 \
    || { echo "collision launch failed worker=$worker" >&2; exit 30; }
[[ "$current_record" =~ ^-$current_pgid\|$current_pid\|[0-9]+\|$collision_identity$ ]] \
    || { echo "malformed collision record worker=$worker" >&2; exit 31; }
grep -Fxq -- "$current_record" "$HSQA_PIDDIR/$collision_role.pids" \
    || { echo "collision record was not durable worker=$worker" >&2; exit 32; }
printf '%s\n' "$current_record" > "$fixture/collision-$worker.ready" \
    || exit 33
for attempt in $(seq 1 1000); do
    [ -f "$fixture/collision-a.ready" ] \
        && [ -f "$fixture/collision-b.ready" ] && break
    sleep 0.01
done
[ -f "$fixture/collision-a.ready" ] && [ -f "$fixture/collision-b.ready" ] \
    || { echo "collision ready barrier timed out worker=$worker" >&2; exit 34; }
[ "$(wc -l < "$HSQA_PIDDIR/$collision_role.pids")" -eq 2 ] \
    || { echo "shared collision pidfile did not contain two records" >&2; exit 35; }
grep -Fxq -- "$current_record" "$HSQA_PIDDIR/$collision_role.pids" \
    || { echo "own collision record disappeared before stop" >&2; exit 36; }
printf 'observed\n' > "$fixture/collision-$worker.observed" || exit 37
for attempt in $(seq 1 1000); do
    [ -f "$fixture/collision-a.observed" ] \
        && [ -f "$fixture/collision-b.observed" ] && break
    sleep 0.01
done
[ -f "$fixture/collision-a.observed" ] \
    && [ -f "$fixture/collision-b.observed" ] \
    || { echo "collision observed barrier timed out worker=$worker" >&2; exit 38; }

if [ "$worker" = a ]; then
    stopped_pgid="$current_pgid"
    hsqa_stop_tracked "$collision_role" "$current_record" \
        || { echo "worker A exact collision stop failed" >&2; exit 39; }
    ! kill -0 -- "-$stopped_pgid" 2>/dev/null \
        || { echo "worker A collision group survived" >&2; exit 40; }
    [ "$(wc -l < "$HSQA_PIDDIR/$collision_role.pids")" -eq 1 ] \
        || { echo "worker A deleted foreign collision record" >&2; exit 41; }
    printf 'stopped\n' > "$fixture/collision-a.stopped" || exit 42
else
    for attempt in $(seq 1 1000); do
        [ -f "$fixture/collision-a.stopped" ] && break
        sleep 0.01
    done
    [ -f "$fixture/collision-a.stopped" ] \
        || { echo "worker B timed out waiting for worker A stop" >&2; exit 43; }
    grep -Fxq -- "$current_record" "$HSQA_PIDDIR/$collision_role.pids" \
        || { echo "worker B record was removed by worker A" >&2; exit 44; }
    kill -0 -- "-$current_pgid" 2>/dev/null \
        || { echo "worker B group was killed by worker A" >&2; exit 45; }
    hsqa_stop_tracked "$collision_role" "$current_record" \
        || { echo "worker B exact collision stop failed" >&2; exit 46; }
    require_empty_pidfile_sentinel "$HSQA_PIDDIR/$collision_role.pids" \
        || { echo "final collision sentinel was unsafe" >&2; exit 47; }
fi
current_pid=''; current_pgid=''; current_record=''

for iteration in $(seq 1 "$iterations"); do
    case $((iteration % 3)) in
        0) shape=direct; stem=d ;;
        1) shape=nonexec; stem=n ;;
        2) shape=pipeline; stem=p ;;
    esac
    identity="stress-$shape-$nonce"
    current_role="s-$stem-$role_nonce"
    current_pid=''; current_pgid=''; current_record=''
    status=0
    case "$shape" in
        direct)
            hsqa_launch_tracked_group "$current_role" "$identity" '' \
                current_pid current_pgid current_record current_role sleep 60 || status=$? ;;
        nonexec)
            hsqa_launch_tracked_group "$current_role" "$identity" '' \
                current_pid current_pgid current_record current_role \
                bash -c 'if declare -F hsqa_hostile >/dev/null; then hsqa_hostile; exit 91; fi; while :; do sleep 60; done' \
                || status=$? ;;
        pipeline)
            hsqa_launch_tracked_group "$current_role" "$identity" '' \
                current_pid current_pgid current_record current_role \
                bash -c 'if declare -F hsqa_hostile >/dev/null; then hsqa_hostile; exit 91; fi; set +m; tail -f /dev/null | cat >/dev/null' \
                || status=$? ;;
    esac
    [ "$status" -eq 0 ] || {
        echo "launch failed iteration=$iteration shape=$shape status=$status pid=$current_pid pgid=$current_pgid record=$current_record" >&2
        exit 10
    }
    [[ "$current_record" =~ ^-$current_pgid\|$current_pid\|[0-9]+\|$identity$ ]] \
        || { echo "malformed durable record: $current_record" >&2; exit 11; }
    grep -Fxq -- "$current_record" "$HSQA_PIDDIR/$current_role.pids" \
        || { echo "record was not durable: $current_record" >&2; exit 12; }
    members=$(hsqa_group_members "$current_pgid")
    [ -n "$members" ] || { echo "tracked group had no members" >&2; exit 13; }
    while IFS= read -r member; do
        [ -z "$member" ] || hsqa_pid_has_exact_identity "$member" "$identity" \
            || { echo "unmarked group member $member" >&2; exit 14; }
    done <<< "$members"
    hsqa_stop_tracked "$current_role" "$current_record" \
        || { echo "exact stop failed: $current_record" >&2; exit 15; }
    kill -0 -- "-$current_pgid" 2>/dev/null \
        && { echo "group survived exact stop: $current_pgid" >&2; exit 16; }
    require_empty_pidfile_sentinel "$HSQA_PIDDIR/$current_role.pids" \
        || { echo "safe empty sentinel missing: $current_role" >&2; exit 17; }
    current_pid=''; current_pgid=''; current_record=''
done
[ ! -e "$hostile_env_sentinel" ] \
    || { echo "tracked payload sourced ambient BASH_ENV/ENV" >&2; exit 18; }
[ ! -e "$hostile_function_sentinel" ] \
    || { echo "tracked payload imported ambient BASH_FUNC" >&2; exit 19; }
trap - EXIT
printf 'STRESS PASS %s\n' "$iterations"
'''


def stress_role_nonce(worker: str) -> str:
    require(worker in {"a", "b"}, f"invalid stress worker {worker!r}")
    # Put the worker discriminator before the 16-character role truncation.
    return f"{worker}{RUN_NONCE[:15]}"


def stress_role_roster() -> dict[str, dict[str, str]]:
    return {
        worker: {
            shape: f"s-{stem}-{stress_role_nonce(worker)}"
            for shape, stem in STRESS_ROLE_STEMS
        }
        for worker in ("a", "b")
    }


def validate_parallel_role_roster(roster: dict[str, dict[str, str]]) -> None:
    require(set(roster) == {"a", "b"}, "parallel role roster lost a worker")
    expected_shapes = {shape for shape, _stem in STRESS_ROLE_STEMS}
    for worker in ("a", "b"):
        require(set(roster[worker]) == expected_shapes,
                f"parallel role roster lost a shape for worker {worker}")
        for role in roster[worker].values():
            require(re.fullmatch(r"s-[dnp]-[ab][0-9a-f]{15}", role) is not None,
                    f"parallel role is not run/worker scoped: {role!r}")
    for shape in expected_shapes:
        require(roster["a"][shape] != roster["b"][shape],
                f"parallel workers collide on {shape} role")
    all_roles = [role for worker in ("a", "b")
                 for role in roster[worker].values()]
    require(len(set(all_roles)) == len(all_roles),
            "parallel role roster contains a cross-shape collision")


def parallel_role_collision_mutant_contract(
    roster: dict[str, dict[str, str]],
) -> None:
    mutant = {worker: dict(roles) for worker, roles in roster.items()}
    mutant["b"]["direct"] = mutant["a"]["direct"]
    try:
        validate_parallel_role_roster(mutant)
    except AssertionError:
        return
    raise AssertionError("parallel role validator accepted an exact collision mutant")


def one_shared_launcher_stress(
    worker: str, root: Path, roster: dict[str, dict[str, str]],
) -> None:
    nonce = f"{RUN_NONCE}{worker}"
    identities = (f"stress-collision-{nonce}",) + tuple(
        f"stress-{shape}-{nonce}" for shape, _stem in STRESS_ROLE_STEMS
    )
    environment = clean_environment(
        HSQA_SAFE_TMP_ROOT=str(root),
        HSQA_ARTIFACTS=str(root / "artifacts"),
        HSQA_PIDDIR=str(root / "pids"),
    )
    run = run_marked_shell(
        # Each worker performs STRESS_ITERATIONS complete publish/observe/stop
        # transactions against a filesystem mounted from Windows.  Keep the
        # test volume fixed, but scale its hard wall-clock budget so a correct
        # run is not rejected solely because OneDrive/9P metadata operations
        # are slower than a native Linux filesystem.
        stress_script(), environment, identities,
        timeout=max(90, STRESS_ITERATIONS * 3),
        arguments=(
            str(ROOT), str(HARNESS), str(root),
            str(STRESS_ITERATIONS), nonce, worker,
            stress_role_nonce(worker), f"collision-{RUN_NONCE[:16]}",
        ),
    )
    require(run.returncode == 0,
            "shared launcher stress failed: "
            f"stdout={run.stdout!r} stderr={run.stderr!r}")
    require_clean_shell_diagnostics(run.stderr, "shared launcher stress")
    require(f"STRESS PASS {STRESS_ITERATIONS}" in run.stdout,
            "shared launcher stress omitted its completion marker")
    require(set(roster[worker].values()) == {
        f"s-{stem}-{stress_role_nonce(worker)}"
        for _shape, stem in STRESS_ROLE_STEMS
    }, f"worker {worker} runtime roles drifted from validated roster")
    for identity in identities:
        require(not marked_processes(identity),
                f"shared launcher left marked process for {identity}")


def require_no_repo_hostile_artifacts(context: str) -> None:
    polluted = [str(path) for path in REPO_HOSTILE_ARTIFACTS if path.exists()]
    require(not polluted, f"{context} polluted the repository: {polluted}")


def shared_launcher_stress_contract() -> None:
    # This is intentionally concurrent.  A second whole selftest process may
    # run at the same time as well. Both local workers use one authenticated
    # PID root, while role/identity authority is worker+run scoped so neither
    # worker or reviewer can terminate the other's process.
    require_no_repo_hostile_artifacts("pre-stress state")
    try:
        roster = stress_role_roster()
        validate_parallel_role_roster(roster)
        parallel_role_collision_mutant_contract(roster)
        with authenticated_reap_fixture() as (root, fixture_token):
            environment = clean_environment(
                HSQA_SAFE_TMP_ROOT=str(root),
                HSQA_ARTIFACTS=str(root / "artifacts"),
                HSQA_PIDDIR=str(root / "pids"),
            )
            initialized = subprocess.run(
                bash_command(
                    "-c", 'export HSQA_REPO="$1"; . "$2"; '
                    '[ "$HSQA_PIDDIR" = "$3" ]',
                    "--", str(ROOT), str(HARNESS), str(root / "pids"),
                ),
                cwd=ROOT, env=environment, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=15, check=False,
            )
            require(initialized.returncode == 0,
                    "shared recovery namespace initialization failed: "
                    f"{initialized.stdout!r} {initialized.stderr!r}")
            with ThreadPoolExecutor(max_workers=2) as executor:
                futures = [executor.submit(
                    one_shared_launcher_stress, worker, root, roster,
                ) for worker in ("a", "b")]
                for future in futures:
                    future.result()
            expected_roles = {f"collision-{RUN_NONCE[:16]}"}
            expected_roles.update(
                role for worker_roles in roster.values()
                for role in worker_roles.values()
            )
            require_fixture_pid_root_clean(
                root, fixture_token, expected_roles,
                "shared launcher successful unregistration",
            )
    finally:
        require_no_repo_hostile_artifacts("shared launcher stress")


def failure_mutant_script(signal_name: str, role: str, identity: str) -> str:
    expected = 130 if signal_name == "INT" else 143
    return rf'''
set -u
root="$1"; harness="$2"; fixture="$3"
export HSQA_REPO="$root"
. "$harness"
role='{role}'; identity='{identity}'
pid=''; pgid=''; record=''; effective_role="$role"; seen=0
payload_sentinel="$fixture/payload-ran"
emergency_cleanup() {{
    if [ -n "$record" ]; then
        hsqa_stop_tracked "$effective_role" "$record" >/dev/null 2>&1 || true
    elif [[ "$pgid" =~ ^[1-9][0-9]*$ ]]; then
        kill -9 -- "-$pgid" 2>/dev/null || true
        [ -z "$pid" ] || wait "$pid" 2>/dev/null || true
    fi
}}
trap emergency_cleanup EXIT
on_signal() {{ seen="$1"; }}
status=0
hsqa_launch_tracked_group "$role" "$identity" on_signal \
    pid pgid record effective_role \
    /bin/bash --noprofile --norc -p -c \
    'printf "payload\n" > "$1"; exec /bin/sleep 30' \
    hsqa-writefail "$payload_sentinel" \
    || status=$?
[ "$status" -eq {expected} ] \
    || {{ echo "wrong status=$status expected={expected}" >&2; exit 20; }}
[ "$seen" -eq {expected} ] \
    || {{ echo "pending signal was not dispatched: $seen" >&2; exit 21; }}
[ -z "$record" ] || {{ echo "failed registration returned authority" >&2; exit 22; }}
[ -z "$pgid" ] || ! kill -0 -- "-$pgid" 2>/dev/null \
    || {{ echo "registration failure leaked group $pgid" >&2; exit 23; }}
[ ! -e "$HSQA_PIDDIR/$role.pids" ] \
    || {{ echo "registration failure published pidfile" >&2; exit 24; }}
[ ! -e "$payload_sentinel" ] \
    || {{ echo "registration failure released payload" >&2; exit 25; }}
pid=''; pgid=''; record=''
trap - EXIT
printf 'WRITEFAIL PASS {signal_name}\n'
'''


def registration_failure_contract() -> None:
    for signal_name in ("INT", "TERM"):
        role = f"writefail-{signal_name.lower()}-{RUN_NONCE[:16]}"
        identity = f"stress-writefail-{signal_name.lower()}-{RUN_NONCE}"
        with tempfile.TemporaryDirectory(
            prefix="hsqa-harness-artifacts.", dir="/tmp",
        ) as directory:
            root = Path(directory).resolve()
            ready = root / "registration-window-ready"
            hold = root / "registration-window-hold"
            hold.write_text("hold\n", encoding="utf-8")
            environment = clean_environment(
                HSQA_SAFE_TMP_ROOT=str(root),
                HSQA_ARTIFACTS=str(root / "artifacts"),
                HSQA_PIDDIR=str(root / "pids"),
                HSQA_LAUNCH_SELFTEST_MODE="1",
                HSQA_LAUNCH_SELFTEST_PHASE="pre-register",
                HSQA_LAUNCH_SELFTEST_READY=str(ready),
                HSQA_LAUNCH_SELFTEST_HOLD=str(hold),
            )
            process = subprocess.Popen(
                bash_command(
                    "-c", failure_mutant_script(signal_name, role, identity),
                    "--", str(ROOT), str(HARNESS), str(root),
                ),
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                start_new_session=True,
            )
            try:
                wait_for_path(ready)
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline and not marked_processes(identity):
                    time.sleep(0.01)
                require(marked_processes(identity),
                        f"{signal_name} barrier lacked exact marked entry process")
                # Exactly one catchable signal while the helper explicitly
                # reports its deferred pre-registration window.
                process.send_signal(
                    signal.SIGINT if signal_name == "INT" else signal.SIGTERM
                )
                stdout, stderr = process.communicate(timeout=15)
            except BaseException:
                kill_private_session_and_drain(
                    process, (identity,),
                    f"{signal_name} write-failure mutant cleanup",
                )
                raise
            require(process.returncode == 0,
                    f"{signal_name} write-failure mutant failed: "
                    f"stdout={stdout!r} stderr={stderr!r}")
            require_clean_shell_diagnostics(
                stderr, f"{signal_name} write-failure mutant"
            )
            require(f"WRITEFAIL PASS {signal_name}" in stdout,
                    f"{signal_name} write-failure mutant omitted PASS marker")
            require(not marked_processes(identity),
                    f"{signal_name} write-failure mutant leaked its child")
            require(not (root / "payload-ran").exists(),
                    f"{signal_name} registration cancellation ran payload")
            require_no_pidfiles(
                root / "pids", f"{signal_name} registration cancellation"
            )

    # The barrier variables themselves are not authority.  Omitting the exact
    # authenticated mode must reject the seam before a child can run.
    with tempfile.TemporaryDirectory(
        prefix="hsqa-harness-artifacts.", dir="/tmp",
    ) as directory:
        root = Path(directory).resolve()
        role = f"writefail-int-{RUN_NONCE[:16]}"
        identity = f"stress-unauthorized-{RUN_NONCE}"
        sentinel = root / "unauthorized-payload"
        environment = clean_environment(
            HSQA_SAFE_TMP_ROOT=str(root),
            HSQA_ARTIFACTS=str(root / "artifacts"),
            HSQA_PIDDIR=str(root / "pids"),
            HSQA_LAUNCH_SELFTEST_PHASE="pre-register",
            HSQA_LAUNCH_SELFTEST_READY=str(root / "ready"),
            HSQA_LAUNCH_SELFTEST_HOLD=str(root / "hold"),
        )
        script = r'''
set -u
export HSQA_REPO="$1"
. "$2"
pid=''; pgid=''; record=''; effective=''
status=0
hsqa_launch_tracked_group "$3" "$4" '' pid pgid record effective \
    /bin/bash --noprofile --norc -p -c 'printf bad > "$1"' x "$5" \
    || status=$?
[ "$status" -eq 2 ] || exit 31
[ -z "$pid$pgid$record" ] || exit 32
'''
        run = subprocess.run(
            bash_command(
                "-c", script, "--", str(ROOT), str(HARNESS), role,
                identity, str(sentinel),
            ),
            cwd=ROOT, env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
            check=False,
        )
        require(run.returncode == 0,
                f"unauthenticated launcher seam was not rejected: {run.stderr!r}")
        require(not sentinel.exists() and not marked_processes(identity),
                "unauthenticated launcher seam executed or leaked a payload")
        require_no_pidfiles(root / "pids", "unauthenticated launcher seam")


def port_contract() -> None:
    for value in ("1024", "25572", "65535"):
        run = subprocess.run(
            bash_command("-c", '. "$1"; hsqa_validate_port "$2"',
                         "--", str(SAFE_LIB), value),
            env=clean_environment(), check=False,
        )
        require(run.returncode == 0, f"valid port {value!r} was rejected")
    for value in (
        "", "0", "01024", "1023", "65536", "-1", "12.5", "abc",
        "25572\nstop", "9" * 1000,
    ):
        run = subprocess.run(
            bash_command("-c", '. "$1"; hsqa_validate_port "$2"',
                         "--", str(SAFE_LIB), value),
            env=clean_environment(), check=False,
        )
        require(run.returncode != 0, f"unsafe port {value!r} was accepted")
    with tempfile.TemporaryDirectory(prefix="hsqa-port-selftest-") as directory:
        marker = Path(directory) / "injected"
        value = f"$(touch {marker})"
        run = subprocess.run(
            bash_command("-c", '. "$1"; hsqa_validate_port "$2"',
                         "--", str(SAFE_LIB), value),
            env=clean_environment(), check=False,
        )
        require(run.returncode != 0, "shell-token port was accepted")
        require(not marker.exists(), "port validation executed a shell token")


def profile_contract() -> None:
    cases = (
        ("warmup", "6", "6", "1800", True),
        ("samples", "5", "5", "1000", True),
        ("interval", "2", "2", "300", True),
        ("start", "20", "20", "300", True),
        ("warmup-short", "5", "6", "1800", False),
        ("samples-short", "4", "5", "1000", False),
        ("interval-short", "1", "2", "300", False),
        ("start-short", "19", "20", "300", False),
        ("leading-zero", "020", "20", "300", False),
        ("newline", "6\n0", "6", "1800", False),
        ("shell-token", "$(echo 20)", "20", "300", False),
        ("overflow", "1" + "0" * 1000, "20", "300", False),
    )
    for name, value, minimum, maximum, expected in cases:
        run = subprocess.run(
            bash_command(
                "-c", '. "$1"; hsqa_validate_uint "$2" "$3" "$4"',
                "--", str(SAFE_LIB), value, minimum, maximum,
            ),
            env=clean_environment(), check=False,
        )
        require((run.returncode == 0) is expected,
                f"profile validator produced wrong verdict for {name}")


def level_type_contract() -> None:
    source = INSTANCE.read_text(encoding="utf-8")
    require('hsqa_validate_level_type "$LEVEL_TYPE"' in source,
            "server_instance must validate world type before interpolation")
    for value in ("flat", "normal"):
        run = subprocess.run(
            bash_command(
                "-c", '. "$1"; hsqa_validate_level_type "$2"',
                "--", str(SAFE_LIB), value,
            ),
            env=clean_environment(), check=False,
        )
        require(run.returncode == 0, f"valid level type {value!r} was rejected")
    for value in (
        "", "default", "flat\nlevel-seed=owned", "$(touch injected)",
        "flat;stop",
    ):
        run = subprocess.run(
            bash_command(
                "-c", '. "$1"; hsqa_validate_level_type "$2"',
                "--", str(SAFE_LIB), value,
            ),
            env=clean_environment(), check=False,
        )
        require(run.returncode != 0,
                f"unsafe level type {value!r} was accepted")

    environment = clean_environment(HSQA_LEVEL_TYPE="normal")
    scoped = subprocess.run(
        ["/usr/bin/env", "HSQA_LEVEL_TYPE=flat", *bash_command(
            "-c", '[ "$HSQA_LEVEL_TYPE" = flat ]'
        )],
        env=environment,
        check=False,
    )
    require(scoped.returncode == 0,
            "scoped performance launch inherited ambient world type")


@contextmanager
def authenticated_reap_fixture() -> Iterator[tuple[Path, str]]:
    root = Path("/tmp") / f"hsqa-reap-fixture.{secrets.token_hex(12)}"
    root.mkdir(mode=0o700)
    root_identity: tuple[int, int] | None = None
    try:
        root = root.resolve()
        root.chmod(0o700)
        root_metadata = root.lstat()
        require(stat.S_ISDIR(root_metadata.st_mode)
                and root_metadata.st_uid == os.getuid()
                and stat.S_IMODE(root_metadata.st_mode) == 0o700,
                "authenticated reap fixture root has unsafe ownership/mode")
        root_identity = (root_metadata.st_dev, root_metadata.st_ino)
        token = secrets.token_hex(24)
        token_file = root / FIXTURE_TOKEN_FILE
        token_file.write_bytes(token.encode("ascii"))
        token_file.chmod(0o600)
        require(re.fullmatch(r"/tmp/hsqa-reap-fixture\.[A-Za-z0-9]{6,64}", str(root)) is not None,
                f"fixture root is outside the authenticated shape: {root}")
        yield root, token
    finally:
        require(re.fullmatch(r"/tmp/hsqa-reap-fixture\.[A-Za-z0-9]{6,64}", str(root)) is not None,
                f"refusing unsafe fixture cleanup target: {root}")
        if os.path.lexists(root):
            current = root.lstat()
            require(root_identity is not None
                    and (current.st_dev, current.st_ino) == root_identity
                    and stat.S_ISDIR(current.st_mode)
                    and current.st_uid == os.getuid()
                    and stat.S_IMODE(current.st_mode) == 0o700,
                    "refusing replaced/unsafe authenticated fixture cleanup")
            shutil.rmtree(root)


def fixture_authority_rejection_contract() -> None:
    """A fixture-looking mode/name without the exact token is never authority."""
    mod = ROOT / "hearthstead-neoforge"
    with authenticated_reap_fixture() as (root, token):
        # Even an otherwise valid token cannot authorize a test seam in the
        # production checkout.  Rejection is before lib sourcing/evidence/PID
        # creation, so the global recovery namespace remains untouched.
        environment = clean_environment(
            HSQA_PERF_FIXTURE_MODE="post-release-crash",
            HSQA_PERF_FIXTURE_ROOT=str(root),
            HSQA_PERF_FIXTURE_TOKEN=token,
        )
        run = subprocess.run(
            bash_command(str(PROBE), str(mod), str(root / "output")),
            cwd=ROOT, env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
            check=False,
        )
        require(run.returncode == 2,
                f"production checkout accepted valid fixture token: {run.returncode}")
        require(not (root / "pids").exists()
                and not (root / "artifacts").exists()
                and not (root / "perf-controller-ready").exists(),
                "production fixture rejection mutated fixture state")

    with tempfile.TemporaryDirectory(
        prefix="hsqa-perf-launch-", dir="/tmp",
    ) as repo_directory, authenticated_reap_fixture() as (root, token):
        repo = Path(repo_directory).resolve()
        copy_fingerprint_repo(repo)
        wrong = "0" * 48 if token != "0" * 48 else "1" * 48
        environment = clean_environment(
            HSQA_PERF_FIXTURE_MODE="post-release-crash",
            HSQA_PERF_FIXTURE_ROOT=str(root),
            HSQA_PERF_FIXTURE_TOKEN=wrong,
        )
        run = subprocess.run(
            bash_command(
                str(repo / "qa" / "scripts" / "perf_probe.sh"),
                str(repo / "hearthstead-neoforge"), str(root / "output"),
            ),
            cwd=repo, env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10,
            check=False,
        )
        require(run.returncode == 2,
                f"disposable copy accepted wrong fixture token: {run.returncode}")
        require(not (root / "pids").exists()
                and not (root / "artifacts").exists()
                and not (root / "perf-controller-ready").exists(),
                "wrong fixture token mutated fixture state")


def prelaunch_artifact_contract() -> None:
    with tempfile.TemporaryDirectory(
        prefix="hsqa-perf-launch-", dir="/tmp",
    ) as repo_directory:
        repo = Path(repo_directory).resolve()
        copy_fingerprint_repo(repo)
        probe_script = repo / "qa" / "scripts" / "perf_probe.sh"
        mod = repo / "hearthstead-neoforge"
        for signal_name, expected_status in (("int", 130), ("term", 143)):
            with authenticated_reap_fixture() as (root, token):
                artifacts = root / "artifacts"
                pid_dir = root / "pids"
                output = root / "output"
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                    probe.bind(("127.0.0.1", 0))
                    port = probe.getsockname()[1]
                ambient_safe = root / "ambient-safe-must-not-be-used"
                ambient_pid = root / "ambient-pids-must-not-be-used"
                ambient_artifacts = root / "ambient-artifacts-must-not-be-used"
                environment = clean_environment(
                    HSQA_PERF_FIXTURE_MODE="prelaunch-artifact",
                    HSQA_PERF_FIXTURE_ROOT=str(root),
                    HSQA_PERF_FIXTURE_TOKEN=token,
                    HSQA_REAP_FIXTURE_ROOT=str(root),
                    HSQA_REAP_FIXTURE_TOKEN=token,
                    HSQA_SAFE_TMP_ROOT=str(ambient_safe),
                    HSQA_ARTIFACTS=str(ambient_artifacts),
                    HSQA_PIDDIR=str(ambient_pid),
                    HSQA_PERFORMANCE_PORT=str(port),
                    HSQA_TEST_SIGNAL_PHASE=f"prelaunch-{signal_name}",
                )
                run = subprocess.run(
                    bash_command(str(probe_script), str(mod), str(output)),
                    cwd=repo,
                    env=environment,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    timeout=20,
                    check=False,
                )
                require(run.returncode == expected_status,
                        f"early {signal_name} exited {run.returncode}: "
                        f"{run.stdout!r} {run.stderr!r}")
                require_clean_shell_diagnostics(
                    run.stderr, f"early {signal_name} artifact path"
                )
                runs = list((artifacts / "performance").glob("*"))
                require(len(runs) == 1,
                        f"early {signal_name} produced {len(runs)} artifacts")
                artifact = runs[0]
                for relative in (
                    "manifest.json", "result.json", "reproduction.md",
                    "logs", "shots", "film",
                ):
                    require((artifact / relative).exists(),
                            f"early {signal_name} missing {relative}")
                result = json.loads((artifact / "result.json").read_text())
                require(result.get("overall") == "FAIL",
                        f"early {signal_name} artifact was not FAIL")
                require_no_pidfiles(pid_dir, f"early {signal_name} prelaunch signal")
                require(not ambient_safe.exists() and not ambient_pid.exists()
                        and not ambient_artifacts.exists(),
                        f"early {signal_name} trusted raw ambient path overrides")
                with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as verify:
                    verify.bind(("127.0.0.1", port))


def copy_fingerprint_repo(destination: Path) -> None:
    """Copy exactly the source-fingerprint inputs into a disposable controller."""
    directories = (
        "hearthstead-neoforge/src",
        "hearthstead-neoforge/tools",
        "hearthstead-neoforge/gradle/wrapper",
        "qa/hooks",
        "qa/scripts",
        "qa/scenarios",
    )
    files = (
        "hearthstead-neoforge/build.gradle",
        "hearthstead-neoforge/gradle.properties",
        "hearthstead-neoforge/settings.gradle",
        "hearthstead-neoforge/gradlew",
        "hearthstead-neoforge/gradlew.bat",
        "hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md",
        "qa/PROTOCOL.md",
        "tools/hearthstead-qa",
        ".gitattributes",
    )

    def ignored(_directory: str, names: list[str]) -> set[str]:
        return {name for name in names if name in {"node_modules", "__pycache__"}}

    for relative in directories:
        source = ROOT / relative
        target = destination / relative
        require(source.is_dir(), f"missing fingerprint directory {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, target, ignore=ignored)
    for relative in files:
        source = ROOT / relative
        target = destination / relative
        require(source.is_file(), f"missing fingerprint file {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    (destination / "tools" / "hearthstead-qa").chmod(0o755)
    (destination / "hearthstead-neoforge" / "gradlew").chmod(0o755)
    for script in (destination / "qa" / "scripts").glob("*.sh"):
        script.chmod(0o755)


def repository_fingerprint(repo: Path) -> str:
    run = subprocess.run(
        bash_command(str(repo / "qa" / "scripts" / "source_fingerprint.sh"), str(repo)),
        cwd=repo, env=clean_environment(), text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False,
    )
    value = run.stdout.strip()
    require(run.returncode == 0 and re.fullmatch(r"[0-9a-f]{64}", value) is not None,
            f"disposable repository fingerprint failed: {run.stderr!r}")
    return value


def seed_valid_full_pass(repo: Path, fingerprint: str) -> tuple[Path, Path, bytes, bytes]:
    reports = repo / "qa" / "reports"
    artifacts = reports / "artifacts"
    run_dir = artifacts / "seed-valid-full-pass"
    run_dir.mkdir(parents=True)
    suites = {name: {"status": "PASS", "note": "isolated safety fixture"}
              for name in EXPECTED_SUITES}
    manifest = {
        "protocol_version": "1.2.0",
        "fingerprint": fingerprint,
        "git_commit": "fixture",
        "dirty_hash": "0" * 64,
        "minecraft": "1.21.1",
        "neoforge": "fixture",
        "java": "fixture",
        "mod_version": "fixture",
        "started": "20260827T000000Z",
        "finished": "20260827T000001Z",
        "suites": suites,
        "overall": "PASS",
        "green_streak": 2,
        "artifacts": str(run_dir),
    }
    encoded = (json.dumps(manifest, indent=2) + "\n").encode("utf-8")
    latest = reports / "latest.json"
    run_manifest = run_dir / "manifest.json"
    latest.write_bytes(encoded)
    run_manifest.write_bytes(encoded)
    return latest, run_manifest, latest.read_bytes(), run_manifest.read_bytes()


def controller_environment(
    fixture_root: Path,
    fixture_token: str,
    *,
    performance_mode: str | None = None,
) -> dict[str, str]:
    values = {
        "HSQA_REAP_FIXTURE_ROOT": str(fixture_root),
        "HSQA_REAP_FIXTURE_TOKEN": fixture_token,
    }
    if performance_mode is not None:
        values.update({
            "HSQA_PERF_FIXTURE_MODE": performance_mode,
            "HSQA_PERF_FIXTURE_ROOT": str(fixture_root),
            "HSQA_PERF_FIXTURE_TOKEN": fixture_token,
        })
    return clean_environment(**values)


def run_controller(
    repo: Path,
    arguments: tuple[str, ...],
    environment: dict[str, str],
    *,
    timeout: float = 45,
) -> subprocess.CompletedProcess[str]:
    """Retry only the controller's documented host-global lock conflict."""
    deadline = time.monotonic() + timeout
    last: subprocess.CompletedProcess[str] | None = None
    while time.monotonic() < deadline:
        last = subprocess.run(
            bash_command(str(repo / "tools" / "hearthstead-qa"), *arguments),
            cwd=repo, env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=max(5, deadline - time.monotonic()), check=False,
        )
        if last.returncode != 75:
            return last
        time.sleep(0.05)
    raise AssertionError(
        f"controller lock remained unavailable: stdout={last.stdout if last else ''!r} "
        f"stderr={last.stderr if last else ''!r}"
    )


def parse_ready_file(path: Path) -> dict[str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    require(len(lines) == 7, f"crash readiness has {len(lines)} fields")
    parsed: dict[str, str] = {}
    for line in lines:
        require(re.fullmatch(r"[a-z_]+=[^\r\n]+", line) is not None,
                f"malformed crash readiness line: {line!r}")
        key, value = line.split("=", 1)
        require(key not in parsed, f"duplicate crash readiness field {key}")
        parsed[key] = value
    require(set(parsed) == {
        "probe_pid", "probe_pgid", "role", "identity", "pid", "pgid", "record",
    }, f"wrong crash readiness roster: {sorted(parsed)}")
    return parsed


def start_crash_controller(
    repo: Path,
    environment: dict[str, str],
    ready: Path,
    *,
    timeout: float = 60,
) -> subprocess.Popen[str]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        process = subprocess.Popen(
            bash_command(str(repo / "tools" / "hearthstead-qa"), "performance"),
            cwd=repo, env=environment, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True,
        )
        while time.monotonic() < deadline:
            if ready.is_file():
                return process
            status = process.poll()
            if status is None:
                time.sleep(0.01)
                continue
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                stdout, stderr = kill_private_session_and_drain(
                    process, (), "exited crash controller pipe cleanup"
                )
            if status == 75:
                time.sleep(0.05)
                break
            raise AssertionError(
                f"performance controller exited before post-release readiness: "
                f"status={status} stdout={stdout!r} stderr={stderr!r}"
            )
        else:
            kill_private_session_and_drain(
                process, (), "post-release crash controller"
            )
            raise AssertionError("timed out waiting for post-release readiness")
    raise AssertionError("timed out acquiring controller lock for crash fixture")


def wait_process_identity_absent(identity: str, timeout: float = 10) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not marked_processes(identity):
            return
        time.sleep(0.02)
    raise AssertionError(f"marked process survived cleanup: {identity}")


def production_sentinel_script(role: str, identity: str) -> str:
    """Launch one real tracked child in the canonical production PID root."""
    return rf'''
set -u
repo="$1"; harness="$2"; ready="$3"; fixture="$4"
export HSQA_REPO="$repo"
export HSQA_SAFE_TMP_ROOT=/tmp
export HSQA_PIDDIR=/tmp/claude-0/hsqa-pids-v2
export HSQA_ARTIFACTS="$fixture/artifacts"
unset HSQA_LAUNCH_SELFTEST_MODE HSQA_LAUNCH_SELFTEST_PHASE \
    HSQA_LAUNCH_SELFTEST_READY HSQA_LAUNCH_SELFTEST_HOLD \
    HSQA_PIDFILE_SELFTEST_MODE HSQA_PIDFILE_SELFTEST_READY \
    HSQA_PIDFILE_SELFTEST_HOLD
. "$harness"
role='{role}'; identity='{identity}'
pid=''; pgid=''; record=''; effective_role="$role"

stop_exact() {{
    [ -n "$record" ] || return 1
    hsqa_stop_tracked "$effective_role" "$record" || return 1
    pid=''; pgid=''; record=''
}}
on_signal() {{
    code="$1"
    trap - EXIT INT TERM
    if [ -n "$record" ]; then
        stop_exact || exit 125
    elif [ -n "$pid$pgid" ]; then
        exit 125
    fi
    exit "$code"
}}
on_exit() {{
    code=$?
    trap - EXIT INT TERM
    if [ -n "$record" ]; then
        stop_exact || exit 125
    elif [ -n "$pid$pgid" ]; then
        exit 125
    fi
    exit "$code"
}}
trap on_exit EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

hsqa_launch_tracked_group "$role" "$identity" on_signal \
        pid pgid record effective_role \
        /usr/bin/python3 -c 'import time; time.sleep(300)' \
        -Dhsqa.instanceDir=/tmp/claude-0/hsqa-inst-v2/performance \
    || exit $?
ready_tmp="$ready.tmp.$$.$RANDOM"
{{
    printf 'role=%s\n' "$effective_role"
    printf 'identity=%s\n' "$identity"
    printf 'pid=%s\n' "$pid"
    printf 'pgid=%s\n' "$pgid"
    printf 'record=%s\n' "$record"
}} > "$ready_tmp" && mv -f -- "$ready_tmp" "$ready" || exit 125

wait "$pid"
status=$?
stop_exact || exit 125
trap - EXIT
exit "$status"
'''


def parse_production_sentinel_ready(
    ready: Path, role: str, identity: str, pidfile: Path,
) -> dict[str, str]:
    lines = ready.read_text(encoding="utf-8").splitlines()
    require(len(lines) == 5, "production sentinel readiness has wrong field count")
    parsed: dict[str, str] = {}
    for line in lines:
        require(re.fullmatch(r"[a-z_]+=[^\r\n]+", line) is not None,
                f"malformed production sentinel readiness: {line!r}")
        key, value = line.split("=", 1)
        require(key not in parsed, f"duplicate production sentinel field {key}")
        parsed[key] = value
    require(set(parsed) == {"role", "identity", "pid", "pgid", "record"},
            f"wrong production sentinel roster: {sorted(parsed)}")
    require(parsed["role"] == role and parsed["identity"] == identity,
            "production sentinel changed its run-scoped authority")
    require(re.fullmatch(r"[1-9][0-9]*", parsed["pid"]) is not None
            and re.fullmatch(r"[1-9][0-9]*", parsed["pgid"]) is not None,
            "production sentinel published invalid PID/PGID")
    record_match = re.fullmatch(
        rf"-{parsed['pgid']}\|{parsed['pid']}\|([1-9][0-9]*)\|"
        rf"{re.escape(identity)}", parsed["record"],
    )
    require(record_match is not None, "production sentinel record is malformed")
    parsed["start"] = record_match.group(1)
    require(pidfile.is_file() and not pidfile.is_symlink()
            and pidfile.stat().st_nlink == 1
            and pidfile.read_text(encoding="utf-8").splitlines() == [parsed["record"]],
            "production sentinel record was not durably published")
    require(int(parsed["pid"]) in marked_processes(identity)
            and process_starttime(int(parsed["pid"])) == parsed["start"],
            "production sentinel child lacks exact live identity/starttime")
    return parsed


def cleanup_production_sentinel_authority(
    role: str, *, sentinel_required: bool,
) -> None:
    """Remove only this run's exact, already-stopped production test authority."""
    require(re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", role) is not None,
            f"refusing invalid production sentinel role {role!r}")
    pidfile = PRODUCTION_PIDDIR / f"{role}.pids"
    lockfile = PRODUCTION_PIDDIR / f"{role}.pids.lock"
    owned: list[tuple[Path, os.stat_result]] = []
    if os.path.lexists(pidfile):
        require_safe_empty_pidfile(
            pidfile, "production sentinel successful unregistration"
        )
        owned.append((pidfile, pidfile.lstat()))
    else:
        require(not sentinel_required,
                "production sentinel successful unregistration omitted its "
                "safe empty sentinel")
    if os.path.lexists(lockfile):
        owned.append((lockfile, require_plain_owned_file(
            lockfile, "production sentinel run-scoped lock",
            modes=(0o600, 0o644), size=0,
        )))
    for path, expected in owned:
        current = path.lstat()
        require((current.st_dev, current.st_ino)
                == (expected.st_dev, expected.st_ino),
                f"refusing replaced production sentinel cleanup target: {path}")
        path.unlink()
    root_fd = os.open(PRODUCTION_PIDDIR, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(root_fd)
    finally:
        os.close(root_fd)
    require(not os.path.lexists(pidfile) and not os.path.lexists(lockfile),
            "production sentinel retained run-scoped cleanup authority")


def start_production_shaped_sentinel(
    fixture_root: Path,
) -> tuple[subprocess.Popen[str], dict[str, str]]:
    """Publish a canonical production record that fixture reap must not see."""
    role = f"prod-sentinel-{RUN_NONCE[:16]}"
    identity = f"perf-sentinel-{RUN_NONCE}"
    ready = fixture_root / "production-sentinel-ready"
    pidfile = PRODUCTION_PIDDIR / f"{role}.pids"
    lockfile = PRODUCTION_PIDDIR / f"{role}.pids.lock"
    require(not os.path.lexists(pidfile) and not os.path.lexists(lockfile),
            "run-unique production sentinel authority already exists")
    process = subprocess.Popen(
        bash_command(
            "-c", production_sentinel_script(role, identity), "--",
            str(ROOT), str(HARNESS), str(ready), str(fixture_root),
        ),
        cwd=ROOT, env=clean_environment(), text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        start_new_session=True,
    )
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if ready.is_file():
            break
        status = process.poll()
        if status is not None:
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                stdout, stderr = kill_private_session_and_drain(
                    process, (identity,),
                    "exited production sentinel pipe cleanup",
                )
            else:
                cleanup_identity(identity)
            wait_process_identity_absent(identity)
            cleanup_production_sentinel_authority(
                role, sentinel_required=False
            )
            raise AssertionError(
                "production-shaped sentinel exited before readiness: "
                f"status={status} stdout={stdout!r} stderr={stderr!r}"
            )
        time.sleep(0.01)
    else:
        process.send_signal(signal.SIGTERM)
        try:
            stdout, stderr = process.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            stdout, stderr = kill_private_session_and_drain(
                process, (identity,), "production sentinel readiness"
            )
        else:
            cleanup_identity(identity)
        wait_process_identity_absent(identity)
        cleanup_production_sentinel_authority(role, sentinel_required=False)
        raise AssertionError(
            "production-shaped sentinel readiness timed out: "
            f"stdout={stdout!r} stderr={stderr!r}"
        )

    try:
        parsed = parse_production_sentinel_ready(
            ready, role, identity, pidfile
        )
    except BaseException:
        if process.poll() is None:
            process.send_signal(signal.SIGTERM)
        try:
            process.communicate(timeout=20)
        except subprocess.TimeoutExpired:
            kill_private_session_and_drain(
                process, (identity,), "production sentinel parse cleanup"
            )
        wait_process_identity_absent(identity)
        cleanup_production_sentinel_authority(role, sentinel_required=False)
        raise
    return process, parsed


def stop_production_shaped_sentinel(
    process: subprocess.Popen[str], parsed: dict[str, str],
) -> None:
    """Stop/unregister only this run's canonical sentinel and remove its lock."""
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
    try:
        stdout, stderr = process.communicate(timeout=20)
    except subprocess.TimeoutExpired:
        stdout, stderr = kill_private_session_and_drain(
            process, (parsed["identity"],), "production sentinel teardown"
        )
        raise AssertionError(
            "production sentinel wrapper ignored bounded TERM: "
            f"stdout={stdout!r} stderr={stderr!r}"
        )
    require(process.returncode == 143,
            "production sentinel wrapper did not propagate exact TERM 143: "
            f"status={process.returncode} stdout={stdout!r} stderr={stderr!r}")
    require_clean_shell_diagnostics(stderr, "production sentinel teardown")
    wait_process_identity_absent(parsed["identity"])
    cleanup_production_sentinel_authority(
        parsed["role"], sentinel_required=True
    )


def controller_crash_recovery_contract() -> None:
    """Old green evidence must go red after a real post-release orphan."""
    with tempfile.TemporaryDirectory(
        prefix="hsqa-perf-launch-", dir="/tmp",
    ) as repo_directory, authenticated_reap_fixture() as (fixture_root, fixture_token):
        repo = Path(repo_directory).resolve()
        require(re.fullmatch(r"/tmp/hsqa-perf-launch-[A-Za-z0-9_-]+", str(repo)) is not None,
                f"disposable controller root has wrong authority shape: {repo}")
        copy_fingerprint_repo(repo)
        fingerprint = repository_fingerprint(repo)
        latest, run_manifest, latest_before, run_before = seed_valid_full_pass(
            repo, fingerprint
        )
        # The disposable controller authenticates the copy and fixture with
        # both matching trios for every allowed command.  Gate itself forwards
        # only the REAP pair into the read-only reaper child.
        base_environment = controller_environment(
            fixture_root, fixture_token, performance_mode="post-release-crash"
        )

        before = run_controller(repo, ("gate",), base_environment)
        require(before.returncode == 0 and "GATE: PASS" in before.stdout,
                f"valid pre-crash gate was not green: {before.stdout!r} {before.stderr!r}")
        require(not (fixture_root / "pids").exists(),
                "read-only clean gate created the fixture PID root")

        crash_environment = controller_environment(
            fixture_root, fixture_token, performance_mode="post-release-crash"
        )
        ready_path = fixture_root / "perf-controller-ready"
        controller = start_crash_controller(repo, crash_environment, ready_path)
        parsed: dict[str, str] = {}
        production_sentinel: subprocess.Popen[str] | None = None
        sentinel_data: dict[str, str] = {}
        try:
            parsed = parse_ready_file(ready_path)
            require(re.fullmatch(r"performance-[0-9a-f]{16}", parsed["role"]) is not None,
                    f"crash role is not run-scoped: {parsed['role']!r}")
            require(re.fullmatch(r"perf-crash-[0-9a-f]{24}", parsed["identity"]) is not None,
                    f"crash identity is not run-scoped: {parsed['identity']!r}")
            for field in ("probe_pid", "probe_pgid", "pid", "pgid"):
                require(re.fullmatch(r"[1-9][0-9]*", parsed[field]) is not None,
                        f"invalid numeric readiness field {field}")
            record_match = re.fullmatch(
                rf"-{parsed['pgid']}\|{parsed['pid']}\|([1-9][0-9]*)\|"
                rf"{re.escape(parsed['identity'])}", parsed["record"],
            )
            require(record_match is not None,
                    f"crash record is malformed: {parsed['record']!r}")
            pidfile = fixture_root / "pids" / f"{parsed['role']}.pids"
            require(pidfile.is_file() and not pidfile.is_symlink()
                    and pidfile.stat().st_nlink == 1
                    and parsed["record"] in pidfile.read_text(encoding="utf-8").splitlines(),
                    "post-release recovery record was not durable")
            require(int(parsed["pid"]) in marked_processes(parsed["identity"]),
                    "post-release payload lacks its exact exec identity")
            probe_pid = int(parsed["probe_pid"])
            probe_pgid = int(parsed["probe_pgid"])
            require(os.getpgid(probe_pid) == probe_pgid,
                    "readiness did not identify the live performance controller group")
            require(probe_pgid != int(parsed["pgid"]),
                    "payload shares the performance controller process group")

            # Kill only the performance probe after release/ack.  Its tracked
            # payload is a separate group and must remain discoverable solely
            # through the durable fixture PID record.
            os.killpg(probe_pgid, signal.SIGKILL)
            stdout, stderr = controller.communicate(timeout=30)
            require(controller.returncode not in (0, 75),
                    f"SIGKILLed performance controller reported success: {stdout!r} {stderr!r}")
            require(int(parsed["pid"]) in marked_processes(parsed["identity"]),
                    "post-release payload did not survive controller SIGKILL")
            production_sentinel, sentinel_data = start_production_shaped_sentinel(
                fixture_root
            )
            require(latest.read_bytes() == latest_before
                    and run_manifest.read_bytes() == run_before,
                    "single-suite crash modified old full-PASS evidence")

            red_gate = run_controller(repo, ("gate",), base_environment)
            require(red_gate.returncode != 0 and "GATE: PASS" not in red_gate.stdout,
                    f"old PASS stayed green with live recovery record: "
                    f"{red_gate.stdout!r} {red_gate.stderr!r}")
            require(latest.read_bytes() == latest_before
                    and run_manifest.read_bytes() == run_before,
                    "red gate mutated old full-PASS evidence")

            red_check = run_controller(repo, ("reap", "check"), base_environment)
            require(red_check.returncode != 0,
                    f"reap check missed live fixture orphan: {red_check.stdout!r}")
            reaped = run_controller(repo, ("reap", "reap"), base_environment)
            require(reaped.returncode == 0,
                    f"canonical reaper failed: {reaped.stdout!r} {reaped.stderr!r}")
            clean = run_controller(repo, ("reap", "check"), base_environment)
            require(clean.returncode == 0,
                    f"post-reap cleanliness stayed red: {clean.stdout!r} {clean.stderr!r}")
            wait_process_identity_absent(parsed["identity"])
            sentinel_pid = int(sentinel_data["pid"])
            sentinel_pidfile = (
                PRODUCTION_PIDDIR / f"{sentinel_data['role']}.pids"
            )
            require(production_sentinel.poll() is None
                    and process_starttime(sentinel_pid) == sentinel_data["start"]
                    and sentinel_pid in marked_processes(sentinel_data["identity"])
                    and sentinel_pidfile.is_file()
                    and not sentinel_pidfile.is_symlink()
                    and sentinel_pidfile.read_text(encoding="utf-8").splitlines()
                    == [sentinel_data["record"]],
                    "authenticated fixture reap touched production-shaped sentinel")
            fixture_pidfiles = {
                path.name for path in (fixture_root / "pids").glob("*.pids")
            }
            require(fixture_pidfiles == {pidfile.name},
                    "canonical reap retained the wrong PID sentinel roster: "
                    f"{sorted(fixture_pidfiles)}")
            require_safe_empty_pidfile(
                pidfile, "canonical fixture reap successful unregistration"
            )
            final_gate = run_controller(repo, ("gate",), base_environment)
            require(final_gate.returncode == 0 and "GATE: PASS" in final_gate.stdout,
                    f"gate did not recover after exact reap: "
                    f"{final_gate.stdout!r} {final_gate.stderr!r}")
            require(latest.read_bytes() == latest_before
                    and run_manifest.read_bytes() == run_before,
                    "recovery chain rewrote immutable old PASS evidence")
        finally:
            if controller.poll() is None:
                cleanup = ((parsed["identity"],)
                           if parsed.get("identity") else ())
                kill_private_session_and_drain(
                    controller, cleanup, "crash controller final cleanup"
                )
            if parsed.get("identity") and marked_processes(parsed["identity"]):
                # Cleanup is exact and run-scoped; never scan or kill a foreign
                # fixed role/identity from a concurrent reviewer.
                cleanup_identity(parsed["identity"])
            if production_sentinel is not None:
                stop_production_shaped_sentinel(
                    production_sentinel, sentinel_data
                )


def main() -> int:
    probe = PROBE.read_text(encoding="utf-8")
    harness = HARNESS.read_text(encoding="utf-8")
    entry = ENTRY.read_text(encoding="utf-8")
    for path, source in ((PROBE, probe), (HARNESS, harness), (ENTRY, entry)):
        require("\r" not in source, f"{path.name} must be LF-only")
    static_contract(probe, harness, entry)
    shared_launcher_stress_contract()
    barrier_runtime_contract()
    registration_failure_contract()
    port_contract()
    profile_contract()
    level_type_contract()
    fixture_authority_rejection_contract()
    prelaunch_artifact_contract()
    controller_crash_recovery_contract()
    print(
        "perf probe safety selftest: PASS "
        f"(2x{STRESS_ITERATIONS} concurrent production-launcher iterations, "
        "pidfile failure INT/TERM, post-release SIGKILL/fixture reap, "
        "canonical production sentinel preserved, zero leaks)"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, subprocess.TimeoutExpired) as exc:
        print(f"perf probe safety selftest: FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
