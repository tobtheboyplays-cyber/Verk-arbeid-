#!/usr/bin/env bash
# Evidence uniqueness, single-flight locking and fail-monotonic result contract.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_ROOT=''
PID_EMPTY_EXTERNAL_PID=''
cleanup() {
    local target="${TEST_ROOT:-}" canonical
    if [[ "${PID_EMPTY_EXTERNAL_PID:-}" =~ ^[1-9][0-9]*$ ]]; then
        kill -9 -- "-$PID_EMPTY_EXTERNAL_PID" 2>/dev/null || true
        wait "$PID_EMPTY_EXTERNAL_PID" 2>/dev/null || true
        PID_EMPTY_EXTERNAL_PID=''
    fi
    [ -n "$target" ] || return 0
    canonical=$(realpath -m -- "$target") || return 1
    case "$canonical" in /tmp/hsqa-harness-artifacts.*) ;; *) return 1 ;; esac
    rm -rf -- "$canonical" || return 1
    [ ! -e "$canonical" ]
}
fail() { echo "harness artifact selftest: FAIL: $*" >&2; exit 1; }
safe_empty_pidfile() { # <path>; exact retained post-unregister sentinel
    local path="$1" uid
    uid=$(/usr/bin/id -u) || return 1
    [ -f "$path" ] && [ ! -L "$path" ] && [ ! -s "$path" ] \
        && [ "$(/usr/bin/stat -c %u -- "$path" 2>/dev/null)" = "$uid" ] \
        && [ "$(/usr/bin/stat -c %h -- "$path" 2>/dev/null)" = 1 ] \
        && [ "$(/usr/bin/stat -c %a -- "$path" 2>/dev/null)" = 600 ]
}

TEST_ROOT=$(mktemp -d /tmp/hsqa-harness-artifacts.XXXXXX) || exit 1
TEST_ROOT=$(realpath -- "$TEST_ROOT") || exit 1
trap cleanup EXIT
export HSQA_REPO="$REPO"
export HSQA_ARTIFACTS="$TEST_ROOT/artifacts"
export HSQA_PIDDIR="$TEST_ROOT/pids"
. "$SCRIPT_DIR/lib_harness.sh"
hsqa_fingerprint() { printf '%064d\n' 0; }
hsqa_dirty_hash() { printf '%064d\n' 0; }
LAUNCH_RUN_NONCE=$(/usr/bin/od -An -N8 -tx1 /dev/urandom \
    | /usr/bin/tr -d ' \n')
[[ "$LAUNCH_RUN_NONCE" =~ ^[0-9a-f]{16}$ ]] \
    || fail "could not create run-scoped launcher nonce"

# Port fallback must never interpolate an ambient value into Python source.
# Remove lsof from PATH, pass a newline/code-shaped token, and prove validation
# rejects it before Python can touch the sentinel.
NO_LSOF_PATH="$TEST_ROOT/no-lsof-path"
PORT_INJECTION_SENTINEL="$TEST_ROOT/port-injection-sentinel"
mkdir -p "$NO_LSOF_PATH" || fail "could not create no-lsof fixture"
ln -s "$(command -v python3)" "$NO_LSOF_PATH/python3" \
    || fail "could not expose python3 in no-lsof fixture"
if PATH="$NO_LSOF_PATH" port_holder \
        $'25572\n__import__("pathlib").Path("'"$PORT_INJECTION_SENTINEL"'").write_text("owned")' \
        > "$TEST_ROOT/port-injection.out" 2>&1; then
    fail "port_holder accepted a non-canonical/code-shaped port"
fi
[ ! -e "$PORT_INJECTION_SENTINEL" ] \
    || fail "port_holder interpolated an unsafe port into Python source"

# Two same-instant initializers must reserve different directories atomically.
for slot in 1 2; do
    (
        . "$SCRIPT_DIR/lib_harness.sh"
        hsqa_fingerprint() { printf '%064d\n' 0; }
        hsqa_dirty_hash() { printf '%064d\n' 0; }
        ev_init parallel
        printf '%s\n' "$EV_DIR"
    ) > "$TEST_ROOT/path-$slot" &
done
wait || fail "parallel ev_init subprocess failed"
PATH_ONE=$(cat "$TEST_ROOT/path-1")
PATH_TWO=$(cat "$TEST_ROOT/path-2")
[ -n "$PATH_ONE" ] && [ -n "$PATH_TWO" ] && [ "$PATH_ONE" != "$PATH_TWO" ] \
    || fail "parallel ev_init paths collided"
[ -f "$PATH_ONE/manifest.json" ] && [ -f "$PATH_TWO/manifest.json" ] \
    || fail "parallel manifests were not complete"

# A historical FAIL is monotonic even when a later line reuses its name with
# PASS and the caller explicitly requests PASS.
ev_init result-invariant
check_fail repeated "first observation failed" >/dev/null
check_pass repeated "later observation passed" >/dev/null
finish_result PASS
python3 - "$EV_DIR/result.json" <<'PYEOF' \
    || fail "later PASS erased an earlier FAIL"
import json, sys
result = json.load(open(sys.argv[1]))
assert result["overall"] == "FAIL"
PYEOF

# Truncated JSONL, failed check append, result replacement failure and
# reproduction replacement failure must all terminate nonzero and never print
# a success marker.
ev_init truncated-checks
printf '{not-json\n' > "$EV_DIR/.checks.jsonl"
if (finish_result PASS) > "$TEST_ROOT/truncated.out" 2>&1; then
    fail "truncated checks were accepted"
fi
grep -q 'PASS:' "$TEST_ROOT/truncated.out" \
    && fail "truncated checks emitted success"

ev_init append-failure
mkdir "$EV_DIR/.checks.jsonl"
if (check_pass impossible "must not append") > "$TEST_ROOT/append.out" 2>&1; then
    fail "check append failure returned success"
fi
grep -q 'PASS:' "$TEST_ROOT/append.out" \
    && fail "failed check append emitted success"

ev_init result-failure
mkdir "$EV_DIR/result.json"
if (finish_result PASS) > "$TEST_ROOT/result.out" 2>&1; then
    fail "result replacement failure returned success"
fi

ev_init reproduction-failure
mkdir "$EV_DIR/reproduction.md"
if (write_reproduction 'must fail') > "$TEST_ROOT/reproduction.out" 2>&1; then
    fail "reproduction replacement failure returned success"
fi

# A signal that arrives while an EXIT-triggered cleanup is already running
# must not be swallowed by that cleanup.  The cleanup finishes first, then the
# artifact records the interrupt and the shell returns the canonical 130/143.
for EXIT_SIGNAL in INT TERM; do
    EXIT_EXPECTED=130; [ "$EXIT_SIGNAL" = TERM ] && EXIT_EXPECTED=143
    EXIT_READY="$TEST_ROOT/exit-cleanup-${EXIT_SIGNAL,,}.ready"
    EXIT_HOLD="$TEST_ROOT/exit-cleanup-${EXIT_SIGNAL,,}.hold"
    EXIT_ARTIFACT="$TEST_ROOT/exit-cleanup-${EXIT_SIGNAL,,}.artifact"
    : > "$EXIT_HOLD"
    /usr/bin/env --default-signal=INT,TERM \
        /bin/bash --noprofile --norc -p -c '
        repo="$1"; test_root="$2"; script_dir="$3"; signal_name="$4"
        exit_ready="$5"; exit_hold="$6"; exit_artifact="$7"
        export HSQA_REPO="$repo"
        export HSQA_ARTIFACTS="$test_root/exit-artifacts-${signal_name,,}"
        export HSQA_PIDDIR="$test_root/exit-pids-${signal_name,,}"
        . "$script_dir/lib_harness.sh"
        hsqa_fingerprint() { printf "%064d\n" 0; }
        hsqa_dirty_hash() { printf "%064d\n" 0; }
        ev_init "exit-signal-${signal_name,,}"
        printf "%s\n" "$EV_DIR" > "$exit_artifact"
        TEARDOWN_PENDING_SIGNAL=0
        teardown_defer_signal() {
            [ "$TEARDOWN_PENDING_SIGNAL" -ne 0 ] \
                || TEARDOWN_PENDING_SIGNAL="$1"
        }
        teardown() {
            trap "teardown_defer_signal 130" INT
            trap "teardown_defer_signal 143" TERM
            printf ready > "$exit_ready"
            while [ -e "$exit_hold" ]; do /usr/bin/sleep 0.01; done
            return 0
        }
        on_exit_fixture() {
            hsqa_exit_after_cleanup "$?" exit-signal \
                "tools/hearthstead-qa exit-signal" teardown \
                TEARDOWN_PENDING_SIGNAL
        }
        trap on_exit_fixture EXIT
        exit 1
    ' -- "$REPO" "$TEST_ROOT" "$SCRIPT_DIR" "$EXIT_SIGNAL" "$EXIT_READY" \
        "$EXIT_HOLD" "$EXIT_ARTIFACT" \
        > "$TEST_ROOT/exit-cleanup-${EXIT_SIGNAL,,}.out" 2>&1 &
    EXIT_CHILD=$!
    for _ in $(seq 1 500); do
        [ -e "$EXIT_READY" ] && break
        sleep 0.01
    done
    [ -e "$EXIT_READY" ] || fail "$EXIT_SIGNAL EXIT cleanup never reached ready seam"
    kill -"$EXIT_SIGNAL" "$EXIT_CHILD" \
        || fail "could not signal $EXIT_SIGNAL EXIT-cleanup child"
    rm -f -- "$EXIT_HOLD"
    EXIT_STATUS=0
    wait "$EXIT_CHILD" || EXIT_STATUS=$?
    [ "$EXIT_STATUS" -eq "$EXIT_EXPECTED" ] \
        || fail "$EXIT_SIGNAL EXIT cleanup returned $EXIT_STATUS instead of $EXIT_EXPECTED"
    EXIT_EV_DIR=$(cat "$EXIT_ARTIFACT")
    python3 - "$EXIT_EV_DIR/result.json" "$EXIT_EV_DIR/reproduction.md" \
            "$EXIT_EXPECTED" <<'PYEOF' \
        || fail "$EXIT_SIGNAL EXIT cleanup artifact was incomplete"
import json, pathlib, sys
result = json.load(open(sys.argv[1]))
reproduction = pathlib.Path(sys.argv[2]).read_text()
expected = sys.argv[3]
assert result["overall"] == "FAIL"
assert result["checks"].get("interrupted", {}).get("status") == "FAIL"
assert f"exit {expected}" in reproduction
PYEOF
done

# Shared launcher contract: traps remain installed after registration, an
# exact marked process group (including a TERM-resistant process) is reaped and
# unregistered, an unrelated "NeoForge client" sentinel survives, and a
# pidfile-write failure after launch leaves no untracked process behind.
TRACKED_ROLE=''; TRACKED_PID=''; TRACKED_PGID=''; TRACKED_RECORD=''; TRACKED_SIGNAL=0
tracked_signal_handler() {
    TRACKED_SIGNAL="$1"
    hsqa_stop_tracked "$TRACKED_ROLE" "$TRACKED_RECORD" \
        || fail "signal cleanup could not prove tracked group absence"
    TRACKED_RECORD=''
}
trap 'tracked_signal_handler 130' INT
trap 'tracked_signal_handler 143' TERM
trap -p INT > "$TEST_ROOT/trap-before"
setsid bash -c 'exec -a neoforge-unrelated-client sleep 30' &
UNRELATED_CLIENT=$!
hsqa_launch_tracked_group tracked-selftest tracked-selftest \
    tracked_signal_handler TRACKED_PID TRACKED_PGID TRACKED_RECORD TRACKED_ROLE \
    python3 -c 'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)' \
    || fail "shared tracked launcher failed"
trap -p INT > "$TEST_ROOT/trap-after"
cmp -s "$TEST_ROOT/trap-before" "$TEST_ROOT/trap-after" \
    || fail "tracked launcher did not restore the caller signal handler"
kill -INT $$
[ "$TRACKED_SIGNAL" -eq 130 ] || fail "post-launch INT did not reach caller handler"
[ -z "$TRACKED_RECORD" ] || fail "signal cleanup did not clear exact record"
kill -0 -- "-$TRACKED_PGID" 2>/dev/null \
    && fail "TERM-resistant tracked group survived exact signal cleanup"
kill -0 "$UNRELATED_CLIENT" 2>/dev/null \
    || fail "unrelated NeoForge client sentinel was killed"

# The exec-entry marker must remain visible when the tracked command itself is
# a long-running Bash body or a pipeline, not only when it immediately execs a
# single binary.  Both shapes are used by the real dedicated/performance
# feeders.
for TRACKED_SHAPE in nonexec pipeline; do
    SHAPE_ROLE=''; SHAPE_PID=''; SHAPE_PGID=''; SHAPE_RECORD=''
    if [ "$TRACKED_SHAPE" = nonexec ]; then
        SHAPE_COMMAND='while :; do sleep 1; done'
    else
        SHAPE_COMMAND='set +m; tail -f /dev/null | cat >/dev/null'
    fi
    hsqa_launch_tracked_group tracked-selftest tracked-selftest \
        tracked_signal_handler SHAPE_PID SHAPE_PGID SHAPE_RECORD SHAPE_ROLE \
        bash -c "$SHAPE_COMMAND" \
        || fail "$TRACKED_SHAPE command could not be durably registered"
    [ -n "$SHAPE_RECORD" ] \
        || fail "$TRACKED_SHAPE command returned no exact recovery record"
    hsqa_stop_tracked "$SHAPE_ROLE" "$SHAPE_RECORD" \
        || fail "$TRACKED_SHAPE command could not be stopped exactly"
    kill -0 -- "-$SHAPE_PGID" 2>/dev/null \
        && fail "$TRACKED_SHAPE command leaked its process group"
done

# A hostile noninteractive startup file and exported Bash functions must not
# execute at the trusted tracked-entry boundary.  The payload also proves the
# four startup-control variables were removed from its actual environment.
HOSTILE_ENV="$TEST_ROOT/hostile-bash-env"
HOSTILE_ENV_SENTINEL="$TEST_ROOT/hostile-bash-env-ran"
HOSTILE_FORK_PIDFILE="$TEST_ROOT/hostile-bash-env-fork.pid"
HOSTILE_FUNC_SENTINEL="$TEST_ROOT/hostile-exported-function-ran"
HOSTILE_PAYLOAD_SENTINEL="$TEST_ROOT/hostile-clean-payload"
{
    /usr/bin/printf '/usr/bin/touch %q\n' "$HOSTILE_ENV_SENTINEL"
    /usr/bin/printf '%s\n' '/usr/bin/setsid /usr/bin/sleep 30 &'
    /usr/bin/printf '/bin/echo "$!" > %q\n' "$HOSTILE_FORK_PIDFILE"
} > "$HOSTILE_ENV" || fail "could not write hostile BASH_ENV fixture"
(
    export BASH_ENV="$HOSTILE_ENV" ENV="$HOSTILE_ENV"
    HOSTILE_FUNC_LITERAL=$(builtin printf '%q' "$HOSTILE_FUNC_SENTINEL")
    eval "printf() { [ -z \"\${HSQA_PROCESS_IDENTITY:-}\" ] || /usr/bin/touch $HOSTILE_FUNC_LITERAL; builtin printf \"\$@\"; }"
    eval "sed() { [ -z \"\${HSQA_PROCESS_IDENTITY:-}\" ] || /usr/bin/touch $HOSTILE_FUNC_LITERAL; /usr/bin/sed \"\$@\"; }"
    eval "sleep() { [ -z \"\${HSQA_PROCESS_IDENTITY:-}\" ] || /usr/bin/touch $HOSTILE_FUNC_LITERAL; /usr/bin/sleep \"\$@\"; }"
    eval "exec() { [ -z \"\${HSQA_PROCESS_IDENTITY:-}\" ] || /usr/bin/touch $HOSTILE_FUNC_LITERAL; builtin exec \"\$@\"; }"
    export -f printf sed sleep exec
    HOSTILE_PID=''; HOSTILE_PGID=''; HOSTILE_RECORD=''; HOSTILE_ROLE=''
    hsqa_launch_tracked_group hostile-env hostile-env '' \
        HOSTILE_PID HOSTILE_PGID HOSTILE_RECORD HOSTILE_ROLE \
        /bin/bash -c 'bad=$(env | grep -E "^(BASH_ENV|ENV|SHELLOPTS|BASHOPTS|BASH_FUNC_.*%%)=" || true); [ -z "$bad" ] || exit 2; printf "clean\n" > "$1"; sleep 30' \
        -- "$HOSTILE_PAYLOAD_SENTINEL" \
        || exit 31
    hsqa_stop_tracked "$HOSTILE_ROLE" "$HOSTILE_RECORD" || exit 32
) || fail "clean privileged tracked entry rejected its payload"
if [ -f "$HOSTILE_FORK_PIDFILE" ]; then
    HOSTILE_FORK_PID=$(head -1 "$HOSTILE_FORK_PIDFILE" 2>/dev/null || true)
    [[ "$HOSTILE_FORK_PID" =~ ^[1-9][0-9]*$ ]] \
        && kill -9 "$HOSTILE_FORK_PID" 2>/dev/null || true
    fail "hostile BASH_ENV forked before durable registration"
fi
[ ! -e "$HOSTILE_ENV_SENTINEL" ] \
    || fail "hostile BASH_ENV executed before tracked entry"
[ ! -e "$HOSTILE_FUNC_SENTINEL" ] \
    || fail "exported Bash function executed in tracked entry or released bash payload"
[ -f "$HOSTILE_PAYLOAD_SENTINEL" ] \
    || fail "clean tracked payload did not run"

# Emergency recovery publication is O_EXCL and content-verified.  Existing
# regular files, wrong content and symlinks are never overwritten/adopted.
ORIGINAL_RECOVERY_TOKEN=$(declare -f hsqa_recovery_token)
hsqa_recovery_token() { builtin printf '%s\n' aaaaaaaaaaaaaaaaaaaaaaaa; }
ATOMIC_RECORD='-12345|12345|67890|atomic-identity'
ATOMIC_ROLE='atomic-test-aaaaaaaaaaaaaaaaaaaaaaaa'
ATOMIC_FILE="$HSQA_PIDDIR/$ATOMIC_ROLE.pids"
hsqa_publish_unique_recovery_record atomic-test "$ATOMIC_RECORD" \
    || fail "unique atomic recovery publication failed"
[ "$HSQA_REGISTERED_ROLE" = "$ATOMIC_ROLE" ] \
    && grep -Fxq -- "$ATOMIC_RECORD" "$ATOMIC_FILE" \
    || fail "atomic recovery publication returned wrong authority"
rm -f -- "$ATOMIC_FILE"
printf 'foreign-content\n' > "$ATOMIC_FILE" || fail "could not create collision"
if hsqa_publish_unique_recovery_record atomic-test "$ATOMIC_RECORD"; then
    fail "atomic recovery publication overwrote an existing record"
fi
grep -Fxq 'foreign-content' "$ATOMIC_FILE" \
    || fail "record collision content was changed"
rm -f -- "$ATOMIC_FILE"
ATOMIC_EXTERNAL="$TEST_ROOT/atomic-external-sentinel"
printf 'external\n' > "$ATOMIC_EXTERNAL" || fail "could not create symlink sentinel"
ln -s "$ATOMIC_EXTERNAL" "$ATOMIC_FILE" || fail "could not create recovery symlink"
if hsqa_publish_unique_recovery_record atomic-test "$ATOMIC_RECORD"; then
    fail "atomic recovery publication followed a symlink"
fi
grep -Fxq external "$ATOMIC_EXTERNAL" \
    || fail "recovery symlink target was modified"
rm -f -- "$ATOMIC_FILE"
eval "$ORIGINAL_RECOVERY_TOKEN"

# Fixed role pidfiles and their locks are hostile inputs until an O_NOFOLLOW,
# uid, nlink and inode-stability check succeeds under the exact lock. Neither
# symlinks, hardlinks nor a path replacement in the final commit window may
# alter/delete an external target.
PID_SAFE_EXTERNAL="$TEST_ROOT/pid-safe-external"
printf 'external\n' > "$PID_SAFE_EXTERNAL" || fail "could not create pidfile sentinel"
PID_SAFE_RECORD="1|1|1|pid-safe-$LAUNCH_RUN_NONCE"
PID_SAFE_ROLE="pid-safe-$LAUNCH_RUN_NONCE"
ln -s "$PID_SAFE_EXTERNAL" "$HSQA_PIDDIR/$PID_SAFE_ROLE.pids.lock" \
    || fail "could not create lock-symlink fixture"
if append_pid_record "$PID_SAFE_ROLE" "$PID_SAFE_RECORD"; then
    fail "pidfile append followed a lock symlink"
fi
grep -Fxq external "$PID_SAFE_EXTERNAL" \
    || fail "lock symlink target was modified"
rm -f -- "$HSQA_PIDDIR/$PID_SAFE_ROLE.pids.lock"

ln -s "$PID_SAFE_EXTERNAL" "$HSQA_PIDDIR/$PID_SAFE_ROLE.pids" \
    || fail "could not create pidfile-symlink fixture"
if append_pid_record "$PID_SAFE_ROLE" "$PID_SAFE_RECORD"; then
    fail "pidfile append followed a record symlink"
fi
grep -Fxq external "$PID_SAFE_EXTERNAL" \
    || fail "pidfile symlink target was modified"
rm -f -- "$HSQA_PIDDIR/$PID_SAFE_ROLE.pids"

ln "$PID_SAFE_EXTERNAL" "$HSQA_PIDDIR/$PID_SAFE_ROLE.pids" \
    || fail "could not create pidfile-hardlink fixture"
if append_pid_record "$PID_SAFE_ROLE" "$PID_SAFE_RECORD"; then
    fail "pidfile append adopted a hardlink"
fi
grep -Fxq external "$PID_SAFE_EXTERNAL" \
    || fail "pidfile hardlink target was modified"
rm -f -- "$HSQA_PIDDIR/$PID_SAFE_ROLE.pids"

PID_RACE_ROLE="pid-race-$LAUNCH_RUN_NONCE"
PID_RACE_RECORD="1|1|1|pid-race-first"
PID_RACE_SECOND="2|2|2|pid-race-second"
PID_RACE_FILE="$HSQA_PIDDIR/$PID_RACE_ROLE.pids"
PID_RACE_BACKUP="$TEST_ROOT/pid-race-backup"
PID_RACE_READY="$TEST_ROOT/pid-race-ready"
PID_RACE_HOLD="$TEST_ROOT/pid-race-hold"
append_pid_record "$PID_RACE_ROLE" "$PID_RACE_RECORD" \
    || fail "could not seed pidfile replace-race fixture"
: > "$PID_RACE_HOLD"
(
    for _ in $(seq 1 500); do
        [ -e "$PID_RACE_READY" ] && break
        sleep 0.01
    done
    [ -e "$PID_RACE_READY" ] || exit 1
    mv -- "$PID_RACE_FILE" "$PID_RACE_BACKUP" || exit 2
    ln -s "$PID_SAFE_EXTERNAL" "$PID_RACE_FILE" || exit 3
    rm -f -- "$PID_RACE_HOLD"
) & PID_RACE_SWAPPER=$!
if HSQA_PIDFILE_SELFTEST_MODE=1 \
        HSQA_PIDFILE_SELFTEST_READY="$PID_RACE_READY" \
        HSQA_PIDFILE_SELFTEST_HOLD="$PID_RACE_HOLD" \
        append_pid_record "$PID_RACE_ROLE" "$PID_RACE_SECOND"; then
    fail "pidfile replacement race was committed"
fi
wait "$PID_RACE_SWAPPER" || fail "pidfile replacement swapper failed"
[ -L "$PID_RACE_FILE" ] \
    || fail "pidfile transaction deleted/replaced the raced unknown target"
grep -Fxq external "$PID_SAFE_EXTERNAL" \
    || fail "pidfile replacement race modified external content"
grep -Fxq -- "$PID_RACE_RECORD" "$PID_RACE_BACKUP" \
    || fail "pidfile replacement race modified the original inode"
grep -Fxq -- "$PID_RACE_SECOND" "$PID_RACE_BACKUP" \
    && fail "pidfile replacement race appended to the displaced inode"
find "$HSQA_PIDDIR" -maxdepth 1 -name ".${PID_RACE_ROLE}.pids.tmp-*" \
    -print | grep -q . && fail "pidfile replacement race leaked a candidate"
rm -f -- "$PID_RACE_FILE"
mv -- "$PID_RACE_BACKUP" "$PID_RACE_FILE" \
    || fail "could not restore exact pidfile race fixture"
if clear_pidfile "$PID_RACE_ROLE" >/dev/null 2>&1; then
    fail "role-wide pidfile deletion remained enabled"
fi
grep -Fxq -- "$PID_RACE_RECORD" "$PID_RACE_FILE" \
    || fail "forbidden role-wide deletion removed the recovery record"
unregister_pid "$PID_RACE_ROLE" "$PID_RACE_RECORD" \
    || fail "could not exact-unregister restored pidfile race fixture"

# The replacement candidate is anonymous and mode 000 until its write lease is
# held.  Once it is deliberately linked behind an unpredictable private name,
# replace that name at the authenticated seam.  The transaction must refuse
# the displaced inode without deleting the foreign replacement or publishing
# the pending record.
PID_CANDIDATE_ROLE="pid-candidate-race-$LAUNCH_RUN_NONCE"
PID_CANDIDATE_FIRST="6|6|6|pid-candidate-first"
PID_CANDIDATE_SECOND="7|7|7|pid-candidate-second"
PID_CANDIDATE_FILE="$HSQA_PIDDIR/$PID_CANDIDATE_ROLE.pids"
PID_CANDIDATE_READY="$TEST_ROOT/pid-candidate-ready"
PID_CANDIDATE_HOLD="$TEST_ROOT/pid-candidate-hold"
PID_CANDIDATE_EXTERNAL="$TEST_ROOT/pid-candidate-external"
PID_CANDIDATE_PRIVATE="$TEST_ROOT/pid-candidate-private-name"
append_pid_record "$PID_CANDIDATE_ROLE" "$PID_CANDIDATE_FIRST" \
    || fail "could not seed anonymous-candidate fixture"
PID_CANDIDATE_BEFORE=$(/usr/bin/sha256sum "$PID_CANDIDATE_FILE") \
    || fail "could not hash anonymous-candidate seed"
append_pid_record "$PID_CANDIDATE_ROLE" "$PID_CANDIDATE_FIRST" \
    || fail "idempotent exact append was rejected"
[ "$PID_CANDIDATE_BEFORE" = "$(/usr/bin/sha256sum "$PID_CANDIDATE_FILE")" ] \
    && [ "$(/usr/bin/wc -l < "$PID_CANDIDATE_FILE")" -eq 1 ] \
    || fail "idempotent exact append created a duplicate record"
printf 'foreign-candidate\n' > "$PID_CANDIDATE_EXTERNAL" \
    || fail "could not create private-candidate replacement"
: > "$PID_CANDIDATE_HOLD" || fail "could not create private-candidate hold"
(
    for _ in $(seq 1 500); do
        [ -s "$PID_CANDIDATE_READY" ] && break
        sleep 0.01
    done
    [ -s "$PID_CANDIDATE_READY" ] || exit 1
    IFS= read -r CANDIDATE_NAME < "$PID_CANDIDATE_READY" || exit 2
    [[ "$CANDIDATE_NAME" =~ ^\.${PID_CANDIDATE_ROLE}\.pids\.tmp-[0-9]+-[0-9a-f]{16}$ ]] \
        || exit 3
    printf '%s\n' "$CANDIDATE_NAME" > "$PID_CANDIDATE_PRIVATE" || exit 4
    mv -f -- "$PID_CANDIDATE_EXTERNAL" "$HSQA_PIDDIR/$CANDIDATE_NAME" || exit 5
    rm -f -- "$PID_CANDIDATE_HOLD" || exit 6
) & PID_CANDIDATE_SWAPPER=$!
if HSQA_PIDFILE_SELFTEST_MODE=1 \
        HSQA_PIDFILE_SELFTEST_READY="$PID_CANDIDATE_READY" \
        HSQA_PIDFILE_SELFTEST_HOLD="$PID_CANDIDATE_HOLD" \
        append_pid_record "$PID_CANDIDATE_ROLE" "$PID_CANDIDATE_SECOND"; then
    fail "linked anonymous candidate replacement was committed"
fi
wait "$PID_CANDIDATE_SWAPPER" \
    || fail "linked anonymous candidate replacement worker failed"
IFS= read -r PID_CANDIDATE_PRIVATE_NAME < "$PID_CANDIDATE_PRIVATE" \
    || fail "private candidate name was not captured"
cmp -s -- "$PID_CANDIDATE_FILE" \
        <(printf '%s\n' "$PID_CANDIDATE_FIRST") \
    || fail "private candidate race changed the public pidfile"
grep -Fxq foreign-candidate "$HSQA_PIDDIR/$PID_CANDIDATE_PRIVATE_NAME" \
    || fail "private candidate race deleted the foreign replacement"
rm -f -- "$HSQA_PIDDIR/$PID_CANDIDATE_PRIVATE_NAME"
unregister_pid "$PID_CANDIDATE_ROLE" "$PID_CANDIDATE_FIRST" \
    || fail "could not retire anonymous-candidate fixture"
safe_empty_pidfile "$PID_CANDIDATE_FILE" \
    || fail "anonymous-candidate role did not converge to an empty sentinel"

# Writer/reaper byte grammar is deliberately identical: 0 bytes or 1..512
# unique exact ASCII rows, single LF separators, exactly one terminal LF, and
# no more than 131072 encoded bytes.  Exercise both accepted boundaries and
# the first rejected value without allowing a candidate to be published.
PID_BOUND_ABSENT="9|9|9|pid-bound-absent"
PID_COUNT_ROLE="pid-count-$LAUNCH_RUN_NONCE"
PID_COUNT_FILE="$HSQA_PIDDIR/$PID_COUNT_ROLE.pids"
/usr/bin/python3 -I - "$PID_COUNT_FILE" <<'PYEOF' \
    || fail "could not create 512-record boundary fixture"
import os
import sys
path = sys.argv[1]
with open(path, "xb") as stream:
    for index in range(512):
        stream.write(f"1|1|1|bound-{index:03d}\n".encode("ascii"))
os.chmod(path, 0o600)
PYEOF
PID_COUNT_BEFORE=$(/usr/bin/sha256sum "$PID_COUNT_FILE") \
    || fail "could not hash 512-record boundary fixture"
unregister_pid "$PID_COUNT_ROLE" "$PID_BOUND_ABSENT" \
    || fail "exact 512-record roster was rejected"
[ "$PID_COUNT_BEFORE" = "$(/usr/bin/sha256sum "$PID_COUNT_FILE")" ] \
    || fail "512-record parse-only check changed bytes"
if append_pid_record "$PID_COUNT_ROLE" "10|10|10|bound-513"; then
    fail "513th pidfile record was accepted"
fi
[ "$PID_COUNT_BEFORE" = "$(/usr/bin/sha256sum "$PID_COUNT_FILE")" ] \
    || fail "rejected 513th record changed the roster"
rm -f -- "$PID_COUNT_FILE" "$HSQA_PIDDIR/$PID_COUNT_ROLE.pids.lock"

PID_DUP_ROLE="pid-duplicate-$LAUNCH_RUN_NONCE"
PID_DUP_FILE="$HSQA_PIDDIR/$PID_DUP_ROLE.pids"
printf '11|11|11|duplicate\n11|11|11|duplicate\n' > "$PID_DUP_FILE" \
    || fail "could not create duplicate-record fixture"
/usr/bin/chmod 600 "$PID_DUP_FILE"
if unregister_pid "$PID_DUP_ROLE" "$PID_BOUND_ABSENT"; then
    fail "duplicate pidfile roster was accepted"
fi
rm -f -- "$PID_DUP_FILE" "$HSQA_PIDDIR/$PID_DUP_ROLE.pids.lock"

PID_CR_ROLE="pid-cr-$LAUNCH_RUN_NONCE"
PID_CR_FILE="$HSQA_PIDDIR/$PID_CR_ROLE.pids"
printf '12|12|12|carriage-return\r\n' > "$PID_CR_FILE" \
    || fail "could not create CR pidfile fixture"
/usr/bin/chmod 600 "$PID_CR_FILE"
if unregister_pid "$PID_CR_ROLE" "$PID_BOUND_ABSENT"; then
    fail "CR/alternate pidfile separator was accepted"
fi
rm -f -- "$PID_CR_FILE" "$HSQA_PIDDIR/$PID_CR_ROLE.pids.lock"

PID_BYTES_ROLE="pid-bytes-$LAUNCH_RUN_NONCE"
PID_BYTES_FILE="$HSQA_PIDDIR/$PID_BYTES_ROLE.pids"
/usr/bin/python3 -I - "$PID_BYTES_FILE" 131072 <<'PYEOF' \
    || fail "could not create 131072-byte boundary fixture"
import os
import sys
path, size_text = sys.argv[1:]
size = int(size_text)
payload = b"1|1|" + (b"1" * (size - 7)) + b"|b\n"
assert len(payload) == size
with open(path, "xb") as stream:
    stream.write(payload)
os.chmod(path, 0o600)
PYEOF
PID_BYTES_BEFORE=$(/usr/bin/sha256sum "$PID_BYTES_FILE") \
    || fail "could not hash 131072-byte boundary fixture"
unregister_pid "$PID_BYTES_ROLE" "$PID_BOUND_ABSENT" \
    || fail "exact 131072-byte pidfile was rejected"
if append_pid_record "$PID_BYTES_ROLE" "13|13|13|byte-overflow"; then
    fail "post-update pidfile beyond 131072 bytes was accepted"
fi
[ "$PID_BYTES_BEFORE" = "$(/usr/bin/sha256sum "$PID_BYTES_FILE")" ] \
    || fail "rejected byte overflow changed the pidfile"
rm -f -- "$PID_BYTES_FILE" "$HSQA_PIDDIR/$PID_BYTES_ROLE.pids.lock"

PID_OVERSIZE_ROLE="pid-oversize-$LAUNCH_RUN_NONCE"
PID_OVERSIZE_FILE="$HSQA_PIDDIR/$PID_OVERSIZE_ROLE.pids"
/usr/bin/python3 -I - "$PID_OVERSIZE_FILE" 131073 <<'PYEOF' \
    || fail "could not create 131073-byte rejection fixture"
import os
import sys
path, size_text = sys.argv[1:]
size = int(size_text)
payload = b"1|1|" + (b"1" * (size - 7)) + b"|b\n"
assert len(payload) == size
with open(path, "xb") as stream:
    stream.write(payload)
os.chmod(path, 0o600)
PYEOF
if unregister_pid "$PID_OVERSIZE_ROLE" "$PID_BOUND_ABSENT"; then
    fail "131073-byte pidfile was accepted"
fi
rm -f -- "$PID_OVERSIZE_FILE" "$HSQA_PIDDIR/$PID_OVERSIZE_ROLE.pids.lock"

# Removing the final record must not use a public-name check followed by
# unlink.  At the exact empty-removal boundary, replace the empty candidate
# with a different, live identity-valid recovery record while deliberately
# ignoring the role lock.  The transaction must fail, restore/preserve the
# injected record byte-for-byte, and leave its process alive for the normal
# reaper rather than deleting its only durable authority.
PID_EMPTY_ROLE="pid-empty-race-$LAUNCH_RUN_NONCE"
PID_EMPTY_TARGET="3|3|3|pid-empty-target"
PID_EMPTY_FILE="$HSQA_PIDDIR/$PID_EMPTY_ROLE.pids"
PID_EMPTY_REPLACEMENT="$TEST_ROOT/pid-empty-live-record"
PID_EMPTY_READY="$TEST_ROOT/pid-empty-ready"
PID_EMPTY_HOLD="$TEST_ROOT/pid-empty-hold"
PID_EMPTY_IDENTITY="pid-empty-live-$LAUNCH_RUN_NONCE"
append_pid_record "$PID_EMPTY_ROLE" "$PID_EMPTY_TARGET" \
    || fail "could not seed final-record race fixture"
/usr/bin/env HSQA_PROCESS_IDENTITY="$PID_EMPTY_IDENTITY" \
    /usr/bin/setsid /usr/bin/sleep 300 &
PID_EMPTY_EXTERNAL_PID=$!
PID_EMPTY_EXTERNAL_START=''
for _ in $(seq 1 500); do
    PID_EMPTY_EXTERNAL_START=$(hsqa_process_starttime "$PID_EMPTY_EXTERNAL_PID")
    if [[ "$PID_EMPTY_EXTERNAL_START" =~ ^[0-9]+$ ]] \
            && hsqa_pid_has_exact_identity "$PID_EMPTY_EXTERNAL_PID" \
                "$PID_EMPTY_IDENTITY"; then
        break
    fi
    sleep 0.01
done
[[ "$PID_EMPTY_EXTERNAL_START" =~ ^[0-9]+$ ]] \
    || fail "live final-record race process did not become observable"
PID_EMPTY_EXTERNAL_RECORD="-$PID_EMPTY_EXTERNAL_PID|$PID_EMPTY_EXTERNAL_PID|$PID_EMPTY_EXTERNAL_START|$PID_EMPTY_IDENTITY"
printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD" > "$PID_EMPTY_REPLACEMENT" \
    || fail "could not write live replacement recovery record"
/usr/bin/chmod 600 "$PID_EMPTY_REPLACEMENT" \
    || fail "could not secure live replacement recovery record"
: > "$PID_EMPTY_HOLD" || fail "could not create final-record race hold"
(
    for _ in $(seq 1 500); do
        [ -e "$PID_EMPTY_READY" ] && break
        sleep 0.01
    done
    [ -e "$PID_EMPTY_READY" ] || exit 1
    mv -f -- "$PID_EMPTY_REPLACEMENT" "$PID_EMPTY_FILE" || exit 2
    rm -f -- "$PID_EMPTY_HOLD" || exit 3
) & PID_EMPTY_SWAPPER=$!
if HSQA_PIDFILE_SELFTEST_MODE=1 \
        HSQA_PIDFILE_SELFTEST_READY="$PID_EMPTY_READY" \
        HSQA_PIDFILE_SELFTEST_HOLD="$PID_EMPTY_HOLD" \
        unregister_pid "$PID_EMPTY_ROLE" "$PID_EMPTY_TARGET"; then
    fail "final-record replacement race was committed green"
fi
wait "$PID_EMPTY_SWAPPER" || fail "final-record replacement swapper failed"
kill -0 -- "-$PID_EMPTY_EXTERNAL_PID" 2>/dev/null \
    || fail "final-record transaction killed the injected live process"
[ -f "$PID_EMPTY_FILE" ] && [ ! -L "$PID_EMPTY_FILE" ] \
    && [ "$(stat -c %h -- "$PID_EMPTY_FILE" 2>/dev/null)" = 1 ] \
    && cmp -s -- "$PID_EMPTY_FILE" \
        <(printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD") \
    || fail "injected live recovery record did not survive final-record race"
# A second writer can target the newly-published empty candidate itself.  Its
# write-open must block on the candidate's kernel lease, request a lease break,
# and force the remover red before the writer is allowed to append.  Once the
# failed remover releases its lease, the live record must land at the public
# path and remain the only record there.
PID_EMPTY_LEASE_ROLE="pid-empty-lease-$LAUNCH_RUN_NONCE"
PID_EMPTY_LEASE_TARGET="4|4|4|pid-empty-lease-target"
PID_EMPTY_LEASE_FILE="$HSQA_PIDDIR/$PID_EMPTY_LEASE_ROLE.pids"
PID_EMPTY_LEASE_READY="$TEST_ROOT/pid-empty-lease-ready"
PID_EMPTY_LEASE_HOLD="$TEST_ROOT/pid-empty-lease-hold"
append_pid_record "$PID_EMPTY_LEASE_ROLE" "$PID_EMPTY_LEASE_TARGET" \
    || fail "could not seed candidate-lease fixture"
: > "$PID_EMPTY_LEASE_HOLD" || fail "could not create candidate-lease hold"
(
    for _ in $(seq 1 500); do
        [ -e "$PID_EMPTY_LEASE_READY" ] && break
        sleep 0.01
    done
    [ -e "$PID_EMPTY_LEASE_READY" ] || exit 1
    (printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD" >> "$PID_EMPTY_LEASE_FILE") &
    LEASE_WRITER=$!
    rm -f -- "$PID_EMPTY_LEASE_HOLD" || exit 2
    wait "$LEASE_WRITER" || exit 3
) & PID_EMPTY_LEASE_WORKER=$!
if HSQA_PIDFILE_SELFTEST_MODE=1 \
        HSQA_PIDFILE_SELFTEST_READY="$PID_EMPTY_LEASE_READY" \
        HSQA_PIDFILE_SELFTEST_HOLD="$PID_EMPTY_LEASE_HOLD" \
        unregister_pid "$PID_EMPTY_LEASE_ROLE" "$PID_EMPTY_LEASE_TARGET"; then
    fail "new-public-candidate writer was accepted during final removal"
fi
wait "$PID_EMPTY_LEASE_WORKER" \
    || fail "candidate-lease writer did not complete after failed removal"
kill -0 -- "-$PID_EMPTY_EXTERNAL_PID" 2>/dev/null \
    || fail "candidate-lease transaction killed the injected live process"
cmp -s -- "$PID_EMPTY_LEASE_FILE" \
        <(printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD") \
    || fail "candidate lease did not preserve the post-break live record"

# After the exchange, the old public inode lives behind the random private
# candidate name. Replace that private name at the exact pre-disposal seam
# with an identity-valid live recovery record. The fresh FD-to-name proof must
# refuse deletion, preserve the external bytes/process, and leave the newly
# published empty candidate safe while the unexpected private entry keeps the
# recovery root visibly red.
PID_DISPOSE_ROLE="pid-dispose-race-$LAUNCH_RUN_NONCE"
PID_DISPOSE_TARGET="8|8|8|pid-dispose-target"
PID_DISPOSE_FILE="$HSQA_PIDDIR/$PID_DISPOSE_ROLE.pids"
PID_DISPOSE_REPLACEMENT="$TEST_ROOT/pid-dispose-live-record"
PID_DISPOSE_READY="$TEST_ROOT/pid-dispose-ready"
PID_DISPOSE_HOLD="$TEST_ROOT/pid-dispose-hold"
PID_DISPOSE_PRIVATE_NAME_FILE="$TEST_ROOT/pid-dispose-private-name"
append_pid_record "$PID_DISPOSE_ROLE" "$PID_DISPOSE_TARGET" \
    || fail "could not seed private-disposal fixture"
printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD" > "$PID_DISPOSE_REPLACEMENT" \
    || fail "could not create private-disposal live record"
/usr/bin/chmod 600 "$PID_DISPOSE_REPLACEMENT" \
    || fail "could not secure private-disposal live record"
: > "$PID_DISPOSE_HOLD" || fail "could not create private-disposal hold"
(
    for _ in $(seq 1 500); do
        [ -s "$PID_DISPOSE_READY" ] && break
        sleep 0.01
    done
    [ -s "$PID_DISPOSE_READY" ] || exit 1
    IFS= read -r DISPOSE_NAME < "$PID_DISPOSE_READY" || exit 2
    [[ "$DISPOSE_NAME" =~ ^\.${PID_DISPOSE_ROLE}\.pids\.tmp-[0-9]+-[0-9a-f]{16}$ ]] \
        || exit 3
    printf '%s\n' "$DISPOSE_NAME" > "$PID_DISPOSE_PRIVATE_NAME_FILE" || exit 4
    mv -f -- "$PID_DISPOSE_REPLACEMENT" "$HSQA_PIDDIR/$DISPOSE_NAME" || exit 5
    rm -f -- "$PID_DISPOSE_HOLD" || exit 6
) & PID_DISPOSE_SWAPPER=$!
if HSQA_PIDFILE_SELFTEST_MODE=1 \
        HSQA_PIDFILE_SELFTEST_READY="$PID_DISPOSE_READY" \
        HSQA_PIDFILE_SELFTEST_HOLD="$PID_DISPOSE_HOLD" \
        unregister_pid "$PID_DISPOSE_ROLE" "$PID_DISPOSE_TARGET"; then
    fail "private-old disposal substitution was committed green"
fi
wait "$PID_DISPOSE_SWAPPER" \
    || fail "private-old disposal substitution worker failed"
IFS= read -r PID_DISPOSE_PRIVATE_NAME < "$PID_DISPOSE_PRIVATE_NAME_FILE" \
    || fail "private-old disposal name was not captured"
kill -0 -- "-$PID_EMPTY_EXTERNAL_PID" 2>/dev/null \
    || fail "private-old disposal killed the injected live process"
safe_empty_pidfile "$PID_DISPOSE_FILE" \
    || fail "private-old disposal changed the safe public candidate"
[ -f "$HSQA_PIDDIR/$PID_DISPOSE_PRIVATE_NAME" ] \
    && [ ! -L "$HSQA_PIDDIR/$PID_DISPOSE_PRIVATE_NAME" ] \
    && [ "$(/usr/bin/stat -c %h -- "$HSQA_PIDDIR/$PID_DISPOSE_PRIVATE_NAME" \
        2>/dev/null)" = 1 ] \
    && cmp -s -- "$HSQA_PIDDIR/$PID_DISPOSE_PRIVATE_NAME" \
        <(printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD") \
    || fail "private-old disposal deleted or changed external recovery authority"
[ "$(/usr/bin/find -P "$HSQA_PIDDIR" -mindepth 1 -maxdepth 1 \
        -name ".${PID_DISPOSE_ROLE}.pids.tmp-*" -print \
        | /usr/bin/wc -l)" -eq 1 ] \
    || fail "private-old disposal did not retain exactly one red private entry"
rm -f -- "$HSQA_PIDDIR/$PID_DISPOSE_PRIVATE_NAME"

# A writer that already holds the original pidfile open predates every rename
# barrier.  Acquiring the original read lease must fail before any mutation;
# after the transaction returns red and releases authority, the held writer may
# append and both the old and new records must remain durable.
PID_PREOPEN_ROLE="pid-preopen-$LAUNCH_RUN_NONCE"
PID_PREOPEN_TARGET="5|5|5|pid-preopen-target"
PID_PREOPEN_FILE="$HSQA_PIDDIR/$PID_PREOPEN_ROLE.pids"
PID_PREOPEN_READY="$TEST_ROOT/pid-preopen-ready"
PID_PREOPEN_HOLD="$TEST_ROOT/pid-preopen-hold"
append_pid_record "$PID_PREOPEN_ROLE" "$PID_PREOPEN_TARGET" \
    || fail "could not seed pre-open writer fixture"
: > "$PID_PREOPEN_HOLD" || fail "could not create pre-open writer hold"
(
    exec 9>> "$PID_PREOPEN_FILE" || exit 1
    printf 'ready\n' > "$PID_PREOPEN_READY" || exit 2
    for _ in $(seq 1 500); do
        [ ! -e "$PID_PREOPEN_HOLD" ] && break
        sleep 0.01
    done
    [ ! -e "$PID_PREOPEN_HOLD" ] || exit 3
    printf '%s\n' "$PID_EMPTY_EXTERNAL_RECORD" >&9 || exit 4
    exec 9>&-
) & PID_PREOPEN_WORKER=$!
for _ in $(seq 1 500); do
    [ -e "$PID_PREOPEN_READY" ] && break
    sleep 0.01
done
[ -e "$PID_PREOPEN_READY" ] || fail "pre-open writer did not become ready"
if unregister_pid "$PID_PREOPEN_ROLE" "$PID_PREOPEN_TARGET"; then
    fail "transaction acquired authority despite a pre-open writer"
fi
rm -f -- "$PID_PREOPEN_HOLD"
wait "$PID_PREOPEN_WORKER" || fail "pre-open writer fixture failed"
kill -0 -- "-$PID_EMPTY_EXTERNAL_PID" 2>/dev/null \
    || fail "pre-open writer transaction killed the injected live process"
grep -Fxq -- "$PID_PREOPEN_TARGET" "$PID_PREOPEN_FILE" \
    && grep -Fxq -- "$PID_EMPTY_EXTERNAL_RECORD" "$PID_PREOPEN_FILE" \
    || fail "pre-open writer records were not both preserved"

unregister_pid "$PID_EMPTY_ROLE" "$PID_EMPTY_EXTERNAL_RECORD" \
    || fail "could not retire preserved replacement-race authority"
unregister_pid "$PID_EMPTY_LEASE_ROLE" "$PID_EMPTY_EXTERNAL_RECORD" \
    || fail "could not retire preserved candidate-lease authority"
unregister_pid "$PID_PREOPEN_ROLE" "$PID_PREOPEN_TARGET" \
    || fail "could not retire pre-open fixture target record"
unregister_pid "$PID_PREOPEN_ROLE" "$PID_EMPTY_EXTERNAL_RECORD" \
    || fail "could not retire pre-open fixture live authority"
kill -9 -- "-$PID_EMPTY_EXTERNAL_PID" 2>/dev/null || true
wait "$PID_EMPTY_EXTERNAL_PID" 2>/dev/null || true
PID_EMPTY_EXTERNAL_PID=''
safe_empty_pidfile "$PID_EMPTY_FILE" \
    || fail "final-record race did not converge to an exact empty sentinel"
safe_empty_pidfile "$PID_EMPTY_LEASE_FILE" \
    || fail "candidate-lease role did not converge to an exact empty sentinel"
safe_empty_pidfile "$PID_PREOPEN_FILE" \
    || fail "pre-open writer role did not converge to an exact empty sentinel"

for FAILURE_SIGNAL in INT TERM; do
    FAILURE_ROLE="writefail-${FAILURE_SIGNAL,,}-$LAUNCH_RUN_NONCE"
    FAILURE_READY="$TEST_ROOT/$FAILURE_ROLE.barrier-ready"
    FAILURE_PAYLOAD="$TEST_ROOT/$FAILURE_ROLE.payload-ran"
    FAILURE_HOLD="$TEST_ROOT/$FAILURE_ROLE.hold"
    FAILURE_TRANSCRIPT="$TEST_ROOT/$FAILURE_ROLE.transcript"
    : > "$FAILURE_HOLD" || fail "could not create registration hold fixture"
    mkdir "$HSQA_PIDDIR/$FAILURE_ROLE.pids.lock" \
        || fail "could not create registration-failure fixture"
    FAILED_ROLE=''; FAILED_PID=''; FAILED_PGID=''; FAILED_RECORD=''; TRACKED_SIGNAL=0
    (
        for _ in $(seq 1 500); do
            if [ -f "$FAILURE_READY" ]; then
                kill -"$FAILURE_SIGNAL" "$$"
                exit $?
            fi
            sleep 0.01
        done
        exit 1
    ) &
    SIGNAL_SENDER=$!
    HSQA_LAUNCH_SELFTEST_MODE=1 \
    HSQA_LAUNCH_SELFTEST_PHASE=pre-register \
    HSQA_LAUNCH_SELFTEST_READY="$FAILURE_READY" \
    HSQA_LAUNCH_SELFTEST_HOLD="$FAILURE_HOLD" \
    hsqa_launch_tracked_group "$FAILURE_ROLE" tracked-selftest \
        tracked_signal_handler FAILED_PID FAILED_PGID FAILED_RECORD FAILED_ROLE \
        bash -c 'printf "ready\n" > "$1"; exec python3 -c '\''import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)'\''' \
        hsqa-writefail "$FAILURE_PAYLOAD" > "$FAILURE_TRANSCRIPT" 2>&1
    FAILURE_STATUS=$?
    if [ "$FAILURE_STATUS" -eq 0 ]; then
        fail "pidfile-write failure with $FAILURE_SIGNAL was accepted"
    fi
    wait "$SIGNAL_SENDER" \
        || fail "deterministic $FAILURE_SIGNAL sender did not observe the registration seam"
    case "$FAILURE_SIGNAL:$FAILURE_STATUS" in
        INT:130|TERM:143) ;;
        *) fail "$FAILURE_SIGNAL registration failure returned $FAILURE_STATUS instead of its exact signal status";;
    esac
    if grep -Eiq '(^|: )(trap:|syntax error|unexpected EOF|unbound variable)' \
            "$FAILURE_TRANSCRIPT"; then
        fail "$FAILURE_SIGNAL registration transcript concealed a shell/trap diagnostic"
    fi
    [ -z "$FAILED_RECORD" ] || fail "failed registration returned a recovery record"
    [ ! -e "$FAILURE_PAYLOAD" ] \
        || fail "registration failure released the payload past the pre-exec barrier"
    [ -z "$FAILED_PGID" ] \
        || ! kill -0 -- "-$FAILED_PGID" 2>/dev/null \
        || fail "pidfile-write failure leaked its provisional group"
    case "$FAILURE_SIGNAL:$TRACKED_SIGNAL" in
        INT:130|TERM:143) ;;
        *) fail "$FAILURE_SIGNAL was not deferred through failure cleanup";;
    esac
    rmdir "$HSQA_PIDDIR/$FAILURE_ROLE.pids.lock" \
        || fail "could not remove registration-failure fixture"
    rm -f -- "$FAILURE_HOLD" "$FAILURE_READY" "$FAILURE_PAYLOAD"
done

# A signal after durable registration but before release is cancellation, not
# permission to execute the payload.  Exercise the exact pre-release seam with
# a normal writable pidfile and require record cleanup before 130/143 returns.
CANCEL_SEEN=0
cancel_signal_handler() {
    CANCEL_SEEN="$1"
    if [ -n "$CANCEL_RECORD" ]; then
        hsqa_stop_tracked "$CANCEL_ROLE" "$CANCEL_RECORD" \
            || fail "pre-release cancellation could not stop its exact record"
        CANCEL_RECORD=''
    fi
}
for CANCEL_SIGNAL in INT TERM; do
    CANCEL_REQUESTED_ROLE="cancel-${CANCEL_SIGNAL,,}-$LAUNCH_RUN_NONCE"
    CANCEL_READY="$TEST_ROOT/$CANCEL_REQUESTED_ROLE.barrier-ready"
    CANCEL_HOLD="$TEST_ROOT/$CANCEL_REQUESTED_ROLE.hold"
    CANCEL_PAYLOAD="$TEST_ROOT/$CANCEL_REQUESTED_ROLE.payload-ran"
    CANCEL_SENDER_DIAG="$TEST_ROOT/$CANCEL_REQUESTED_ROLE.sender-diagnostic"
    : > "$CANCEL_HOLD" || fail "could not create pre-release hold fixture"
    CANCEL_ROLE=''; CANCEL_PID=''; CANCEL_PGID=''; CANCEL_RECORD=''; CANCEL_SEEN=0
    CANCEL_TARGET_PID=$BASHPID
    (
        for _ in $(seq 1 500); do
            if [ -f "$CANCEL_READY" ]; then
                if [ -s "$CANCEL_READY" ]; then
                    CANCEL_READY_STATE=nonempty
                else
                    CANCEL_READY_STATE=empty
                fi
                /bin/kill -"$CANCEL_SIGNAL" "$CANCEL_TARGET_PID"
                CANCEL_KILL_RC=$?
                builtin printf 'ready=%s kill_rc=%s target=%s sender=%s\n' \
                    "$CANCEL_READY_STATE" "$CANCEL_KILL_RC" \
                    "$CANCEL_TARGET_PID" "$BASHPID" > "$CANCEL_SENDER_DIAG"
                exit "$CANCEL_KILL_RC"
            fi
            sleep 0.01
        done
        exit 1
    ) &
    CANCEL_SENDER=$!
    HSQA_LAUNCH_SELFTEST_MODE=1 \
    HSQA_LAUNCH_SELFTEST_PHASE=pre-release \
    HSQA_LAUNCH_SELFTEST_READY="$CANCEL_READY" \
    HSQA_LAUNCH_SELFTEST_HOLD="$CANCEL_HOLD" \
    hsqa_launch_tracked_group "$CANCEL_REQUESTED_ROLE" tracked-selftest \
        cancel_signal_handler CANCEL_PID CANCEL_PGID CANCEL_RECORD CANCEL_ROLE \
        bash -c 'printf "payload\n" > "$1"; sleep 30' \
        hsqa-cancel "$CANCEL_PAYLOAD" \
        > "$TEST_ROOT/$CANCEL_REQUESTED_ROLE.transcript" 2>&1
    CANCEL_STATUS=$?
    wait "$CANCEL_SENDER" \
        || fail "deterministic $CANCEL_SIGNAL sender missed pre-release seam"
    case "$CANCEL_SIGNAL:$CANCEL_STATUS:$CANCEL_SEEN" in
        INT:130:130|TERM:143:143) ;;
        *)
            builtin printf 'launcher diagnostic: signal=%s status=%s seen=%s pending=%s pid=%s pgid=%s role=%s record=%s\n' \
                "$CANCEL_SIGNAL" "$CANCEL_STATUS" "$CANCEL_SEEN" \
                "$HSQA_LAUNCH_PENDING" "${CANCEL_PID:-}" "${CANCEL_PGID:-}" \
                "${CANCEL_ROLE:-}" "$([ -n "${CANCEL_RECORD:-}" ] && printf set || printf empty)" >&2
            builtin printf 'launcher diagnostic detail: %s\n' "$HSQA_LAUNCH_DIAGNOSTIC" >&2
            builtin printf 'launcher sender diagnostic: %s\n' \
                "$CANCEL_SENDER_DIAG" >&2
            /usr/bin/sed 's/^/launcher sender | /' \
                "$CANCEL_SENDER_DIAG" >&2 2>/dev/null || true
            builtin printf 'launcher diagnostic transcript: %s\n' \
                "$TEST_ROOT/$CANCEL_REQUESTED_ROLE.transcript" >&2
            /usr/bin/sed 's/^/launcher transcript | /' \
                "$TEST_ROOT/$CANCEL_REQUESTED_ROLE.transcript" >&2 2>/dev/null || true
            fail "$CANCEL_SIGNAL pre-release cancellation returned status=$CANCEL_STATUS seen=$CANCEL_SEEN";;
    esac
    [ ! -e "$CANCEL_PAYLOAD" ] \
        || fail "$CANCEL_SIGNAL cancellation released the payload"
    [ -z "$CANCEL_RECORD" ] \
        || fail "$CANCEL_SIGNAL cancellation retained a cleaned record"
    [ -z "$CANCEL_PGID" ] || ! kill -0 -- "-$CANCEL_PGID" 2>/dev/null \
        || fail "$CANCEL_SIGNAL cancellation leaked its entry group"
    [ ! -s "$HSQA_PIDDIR/$CANCEL_ROLE.pids" ] \
        || fail "$CANCEL_SIGNAL cancellation left a nonempty recovery pidfile"
    if grep -Eiq '(^|: )(trap:|syntax error|unexpected EOF|unbound variable)' \
            "$TEST_ROOT/$CANCEL_REQUESTED_ROLE.transcript"; then
        fail "$CANCEL_SIGNAL cancellation transcript concealed a shell diagnostic"
    fi
    rm -f -- "$CANCEL_HOLD" "$CANCEL_READY" "$CANCEL_PAYLOAD" \
        "$CANCEL_SENDER_DIAG"
done

# Ambient seam variables without the explicit disposable selftest mode must be
# rejected before a process is launched.
NO_MODE_PID=''; NO_MODE_PGID=''; NO_MODE_RECORD=''; NO_MODE_ROLE=''
if HSQA_LAUNCH_SELFTEST_PHASE=pre-release \
        HSQA_LAUNCH_SELFTEST_READY="$TEST_ROOT/no-mode.ready" \
        HSQA_LAUNCH_SELFTEST_HOLD="$TEST_ROOT/no-mode.hold" \
        hsqa_launch_tracked_group "cancel-int-$LAUNCH_RUN_NONCE" tracked-selftest '' \
            NO_MODE_PID NO_MODE_PGID NO_MODE_RECORD NO_MODE_ROLE sleep 30; then
    fail "launch selftest seam was accepted without HSQA_LAUNCH_SELFTEST_MODE"
fi
[ -z "$NO_MODE_PID$NO_MODE_PGID$NO_MODE_RECORD$NO_MODE_ROLE" ] \
    || fail "rejected ambient launch seam mutated process outputs"
kill -0 "$UNRELATED_CLIENT" 2>/dev/null \
    || fail "unrelated NeoForge sentinel did not survive failure cleanup"

setsid sleep 30 &
RECYCLED_PID=$!
RECYCLED_START=$(hsqa_process_starttime "$RECYCLED_PID")
RECYCLED_RECORD="-$RECYCLED_PID|$RECYCLED_PID|$((RECYCLED_START + 1))|tracked-selftest"
printf '%s\n' "$RECYCLED_RECORD" > "$HSQA_PIDDIR/recycled.pids"
if hsqa_stop_tracked recycled "$RECYCLED_RECORD" >/dev/null 2>&1; then
    fail "recycled process-group record was accepted for teardown"
fi
kill -0 "$RECYCLED_PID" 2>/dev/null \
    || fail "recycled unrelated process group did not survive REFUSE"
grep -Fxq -- "$RECYCLED_RECORD" "$HSQA_PIDDIR/recycled.pids" \
    || fail "recycled recovery record was dropped on REFUSE"
kill "$RECYCLED_PID" 2>/dev/null || true
wait "$RECYCLED_PID" 2>/dev/null || true
rm -f -- "$HSQA_PIDDIR/recycled.pids"
kill "$UNRELATED_CLIENT" 2>/dev/null || true
wait "$UNRELATED_CLIENT" 2>/dev/null || true
trap - INT TERM

# Exercise the controller's exact nonblocking flock semantics. The contender
# must exit 75 and must not run its mutation command.
LOCK_FILE="$TEST_ROOT/suite.lock"
READY_FILE="$TEST_ROOT/lock-ready"
mkdir -p "$TEST_ROOT/repo-one" "$TEST_ROOT/repo-two"
(
    cd "$TEST_ROOT/repo-one" || exit 1
    exec 9>> "$LOCK_FILE"
    flock 9
    printf 'ready\n' > "$READY_FILE"
    sleep 2
) &
LOCK_HOLDER=$!
for _ in $(seq 1 100); do
    [ -f "$READY_FILE" ] && break
    sleep 0.01
done
[ -f "$READY_FILE" ] || fail "lock holder did not become ready"
(cd "$TEST_ROOT/repo-two" && \
    flock -n --close --conflict-exit-code 75 "$LOCK_FILE" \
        bash -c 'touch "$1"' -- "$TEST_ROOT/contender-mutated")
LOCK_STATUS=$?
[ "$LOCK_STATUS" -eq 75 ] || fail "suite-lock contender did not exit 75"
[ ! -e "$TEST_ROOT/contender-mutated" ] \
    || fail "rejected suite mutated state"
grep -Fq 'flock -n --close --conflict-exit-code 75' "$REPO/tools/hearthstead-qa" \
    || fail "controller does not use the tested single-flight primitive"
grep -Fq 'SUITE_LOCK="/tmp/hearthstead-qa-v2.lock"' "$REPO/tools/hearthstead-qa" \
    || fail "controller lock is not host-global across checkouts"

# Ctrl-C is delivered to the complete foreground process group.  The flock
# owner must ignore INT/TERM while its controller child resets those signals,
# runs cleanup, and exits.  A contender remains rejected throughout that
# cleanup window, then succeeds only after the child has left and flock drops
# the descriptor.
SIGNAL_LOCK="$TEST_ROOT/signal.lock"
SIGNAL_CHILD="$TEST_ROOT/signal-child.sh"
SIGNAL_READY="$TEST_ROOT/signal-ready"
SIGNAL_CLEANUP_STARTED="$TEST_ROOT/signal-cleanup-started"
SIGNAL_CLEANUP_DONE="$TEST_ROOT/signal-cleanup-done"
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'READY_PATH="$1"' \
    'STARTED_PATH="$2"' \
    'DONE_PATH="$3"' \
    'on_signal() {' \
    '  printf "started\n" > "$STARTED_PATH"' \
    '  sleep 0.5' \
    '  printf "done\n" > "$DONE_PATH"' \
    '  exit 130' \
    '}' \
    'trap on_signal INT TERM' \
    'printf "ready\n" > "$READY_PATH"' \
    'while :; do sleep 0.05; done' > "$SIGNAL_CHILD" \
    || fail "could not write signal-lock fixture"
chmod 700 "$SIGNAL_CHILD" || fail "could not make signal-lock fixture executable"
setsid bash -c 'trap "" INT TERM; exec flock -n --close --conflict-exit-code 75 "$1" env --default-signal=INT,TERM bash "$2" "$3" "$4" "$5"' \
    -- "$SIGNAL_LOCK" "$SIGNAL_CHILD" "$SIGNAL_READY" \
    "$SIGNAL_CLEANUP_STARTED" "$SIGNAL_CLEANUP_DONE" &
SIGNAL_KEEPER=$!
for _ in $(seq 1 200); do
    [ -f "$SIGNAL_READY" ] && break
    sleep 0.01
done
[ -f "$SIGNAL_READY" ] || fail "signal-lock child did not become ready"
kill -INT -- "-$SIGNAL_KEEPER" 2>/dev/null \
    || fail "could not signal isolated lock process group"
for _ in $(seq 1 200); do
    [ -f "$SIGNAL_CLEANUP_STARTED" ] && break
    sleep 0.01
done
[ -f "$SIGNAL_CLEANUP_STARTED" ] || fail "controller child did not enter signal cleanup"
flock -n --close --conflict-exit-code 75 "$SIGNAL_LOCK" true
SIGNAL_CONTENDER_DURING=$?
[ "$SIGNAL_CONTENDER_DURING" -eq 75 ] \
    || fail "suite lock was released before controller signal cleanup finished"
wait "$SIGNAL_KEEPER"
SIGNAL_KEEPER_STATUS=$?
[ "$SIGNAL_KEEPER_STATUS" -eq 130 ] \
    || fail "signal-lock keeper returned $SIGNAL_KEEPER_STATUS instead of 130"
[ -f "$SIGNAL_CLEANUP_DONE" ] || fail "controller signal cleanup did not finish"
flock -n --close --conflict-exit-code 75 "$SIGNAL_LOCK" true \
    || fail "suite lock remained held after controller child exited"
grep -Fq "trap '' INT TERM" "$REPO/tools/hearthstead-qa" \
    && grep -Fq 'env --default-signal=INT,TERM' "$REPO/qa/scripts/controller_lock_bootstrap.sh" \
    || fail "controller does not use the signal-resistant lock primitive"

FAKE_TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef
GLOBAL_LOCK=/tmp/hearthstead-qa-v2.lock
GLOBAL_HOLDER=''
if flock -n --close "$GLOBAL_LOCK" true; then
    GLOBAL_READY="$TEST_ROOT/global-lock-ready"
    (
        exec 8>> "$GLOBAL_LOCK"
        flock 8
        printf 'ready\n' > "$GLOBAL_READY"
        sleep 5
    ) &
    GLOBAL_HOLDER=$!
    for _ in $(seq 1 100); do
        [ -f "$GLOBAL_READY" ] && break
        sleep 0.01
    done
    [ -f "$GLOBAL_READY" ] || fail "global lock holder did not become ready"
fi
if HSQA_SUITE_LOCK_TOKEN="$FAKE_TOKEN" \
        bash "$REPO/tools/hearthstead-qa" note-selftest \
        > "$TEST_ROOT/ambient-lock-spoof.out" 2>&1; then
    fail "ambient suite-lock token bypassed flock ownership"
fi
grep -q '^PASS:' "$TEST_ROOT/ambient-lock-spoof.out" \
    && fail "ambient suite-lock spoof reached controller mutation"

# Even a real flock executable on a DIFFERENT inode must not authenticate the
# child merely because a separate legitimate process holds the global lock and
# the target path/token are smuggled into later argv text.
OTHER_LOCK="$TEST_ROOT/other.lock"
if flock "$OTHER_LOCK" env HSQA_SUITE_LOCK_TOKEN="$FAKE_TOKEN" \
        bash "$REPO/tools/hearthstead-qa" note-selftest \
        /tmp/hearthstead-qa-v2.lock \
        "HSQA_SUITE_LOCK_TOKEN=$FAKE_TOKEN" \
        > "$TEST_ROOT/wrong-inode-spoof.out" 2>&1; then
    fail "real flock on wrong inode authenticated controller child"
fi
grep -q '^PASS:' "$TEST_ROOT/wrong-inode-spoof.out" \
    && fail "wrong-inode flock spoof reached controller mutation"
[ -z "$GLOBAL_HOLDER" ] \
    || { wait "$GLOBAL_HOLDER" || fail "global lock holder failed"; }
wait "$LOCK_HOLDER" || fail "lock holder failed"

cleanup || fail "validated temporary root was not removed"
TEST_ROOT=''
trap - EXIT
echo "harness artifact selftest: PASS (unique runs + fail monotonicity + single-flight lock)"
