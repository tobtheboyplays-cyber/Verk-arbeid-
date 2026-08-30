#!/usr/bin/env bash
# Bounded 1/25/50/100-settler performance matrix on one isolated dedicated
# server. Each scale has its own warm-up, exact scoreboard population proof,
# delimited /tick-query samples, median and average. Absolute average-MSPT
# gates: 25 <= 45 ms (legacy ceiling), 50 <= 45 ms, 100 <= 50 ms.
#
# The stages share a server/JIT and add settlers cumulatively. This keeps the
# full suite practical while making every measurement window independent and
# auditable. Sampling duration is extensible through the HSQA_PERF_* timing
# variables below; the scale list and budgets are deliberately not runtime
# knobs, so a run cannot weaken its own gate.
#
# Ordered fact ladder (AC-13), same discipline as dedicated_e2e.sh: preflight
# port -> server reaches "Done (" -> exact population proofs -> MSPT matrix.
# Args: <mod-dir> <artifact-dir>; invoke only through the QA controller.
set -u
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export PATH
MOD="$1"; OUT="$2"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HSQA_REPO=$(realpath -e -- "$SCRIPT_DIR/../..") \
    || { echo "FATAL: performance repository cannot be resolved" >&2; exit 2; }
export HSQA_REPO LC_ALL=C

# Production recovery authority is global and canonical.  Tests may redirect
# it only through one authenticated, disposable root; accepting the old raw
# HSQA_PIDDIR/HSQA_SAFE_TMP_ROOT overrides would let a crashed run hide its
# durable record from the next controller invocation.
PERF_FIXTURE_MODE="${HSQA_PERF_FIXTURE_MODE:-}"
PERF_FIXTURE_ROOT="${HSQA_PERF_FIXTURE_ROOT:-}"
PERF_FIXTURE_TOKEN="${HSQA_PERF_FIXTURE_TOKEN:-}"
validate_perf_fixture_authority() {
    local root="$1" token="$2" token_file lexical canonical current_uid
    [[ "$root" =~ ^/tmp/hsqa-reap-fixture\.[A-Za-z0-9]{6,64}$ ]] || return 1
    [[ "$token" =~ ^[0-9a-f]{48}$ ]] || return 1
    lexical=$(realpath -ms -- "$root") || return 1
    canonical=$(realpath -m -- "$root") || return 1
    [ "$root" = "$lexical" ] && [ "$root" = "$canonical" ] \
        && [ -d "$root" ] && [ ! -L "$root" ] || return 1
    current_uid=$(id -u) || return 1
    [ "$(stat -c %u -- "$root" 2>/dev/null)" = "$current_uid" ] \
        && [ "$(stat -c %a -- "$root" 2>/dev/null)" = 700 ] || return 1
    token_file="$root/.hsqa-reap-fixture-token"
    [ -f "$token_file" ] && [ ! -L "$token_file" ] \
        && [ "$(stat -c %u -- "$token_file" 2>/dev/null)" = "$current_uid" ] \
        && [ "$(stat -c %a -- "$token_file" 2>/dev/null)" = 600 ] \
        && [ "$(stat -c %h -- "$token_file" 2>/dev/null)" = 1 ] \
        && [ "$(stat -c %s -- "$token_file" 2>/dev/null)" = 48 ] \
        && cmp -s -- "$token_file" <(printf '%s' "$token")
}

validate_perf_fixture_repo() {
    local repo="$1" suffix current_uid script
    [[ "$repo" =~ ^/tmp/hsqa-perf-launch-[A-Za-z0-9_-]{6,80}$ ]] || return 1
    suffix="${repo#/tmp/hsqa-perf-launch-}"
    [ -n "$suffix" ] && [[ "$suffix" != */* ]] \
        && [ -d "$repo" ] && [ ! -L "$repo" ] || return 1
    current_uid=$(id -u) || return 1
    [ "$(stat -c %u -- "$repo" 2>/dev/null)" = "$current_uid" ] \
        && [ "$(stat -c %a -- "$repo" 2>/dev/null)" = 700 ] || return 1
    script=$(realpath -e -- "${BASH_SOURCE[0]}") || return 1
    [ "$script" = "$repo/qa/scripts/perf_probe.sh" ] \
        && [ -f "$script" ] && [ ! -L "${BASH_SOURCE[0]}" ] \
        && [ "$(stat -c %u -- "$script" 2>/dev/null)" = "$current_uid" ] \
        && [ "$(stat -c %h -- "$script" 2>/dev/null)" = 1 ]
}

if [ -n "$PERF_FIXTURE_MODE$PERF_FIXTURE_ROOT$PERF_FIXTURE_TOKEN" ]; then
    case "$PERF_FIXTURE_MODE" in
        prelaunch-artifact|post-release-crash) ;;
        *) echo "FATAL: invalid authenticated performance fixture mode" >&2; exit 2;;
    esac
    validate_perf_fixture_repo "$HSQA_REPO" \
        || { echo "FATAL: performance fixture requires a canonical disposable controller copy" >&2; exit 2; }
    validate_perf_fixture_authority "$PERF_FIXTURE_ROOT" "$PERF_FIXTURE_TOKEN" \
        || { echo "FATAL: invalid authenticated performance fixture authority" >&2; exit 2; }
    if [ -n "${HSQA_REAP_FIXTURE_ROOT:-}${HSQA_REAP_FIXTURE_TOKEN:-}" ]; then
        [ "${HSQA_REAP_FIXTURE_ROOT:-}" = "$PERF_FIXTURE_ROOT" ] \
            && [ "${HSQA_REAP_FIXTURE_TOKEN:-}" = "$PERF_FIXTURE_TOKEN" ] \
            || { echo "FATAL: performance/reaper fixture authority mismatch" >&2; exit 2; }
    fi
    HSQA_SAFE_TMP_ROOT="$PERF_FIXTURE_ROOT"
    HSQA_PIDDIR="$PERF_FIXTURE_ROOT/pids"
    HSQA_ARTIFACTS="$PERF_FIXTURE_ROOT/artifacts"
else
    [ -z "${HSQA_REAP_FIXTURE_ROOT:-}${HSQA_REAP_FIXTURE_TOKEN:-}" ] \
        || { echo "FATAL: reaper fixture authority requires performance fixture authentication" >&2; exit 2; }
    HSQA_SAFE_TMP_ROOT=/tmp
    HSQA_PIDDIR=/tmp/claude-0/hsqa-pids-v2
    HSQA_ARTIFACTS="$HSQA_REPO/qa/reports/artifacts"
fi
export HSQA_SAFE_TMP_ROOT HSQA_PIDDIR HSQA_ARTIFACTS
unset HSQA_REAP_FIXTURE_ROOT HSQA_REAP_FIXTURE_TOKEN BASH_ENV ENV 2>/dev/null || true
. "$SCRIPT_DIR/lib_harness.sh"

ROLE="performance"
PERF_RUN_NONCE=$(/usr/bin/od -An -N12 -tx1 /dev/urandom | tr -d ' \n')
[[ "$PERF_RUN_NONCE" =~ ^[0-9a-f]{24}$ ]] \
    || { echo "FATAL: could not create performance run nonce" >&2; exit 2; }
PERF_PROCESS_ROLE="performance-${PERF_RUN_NONCE:0:16}"
PERF_BUILD_ID="perf-build-$PERF_RUN_NONCE"
PERF_INSTALL_ID="perf-install-$PERF_RUN_NONCE"
PERF_SERVER_ID="perf-server-$PERF_RUN_NONCE"
PERF_ACTIVE_ID="perf-active-$PERF_RUN_NONCE"
PERF_CRASH_ID="perf-crash-$PERF_RUN_NONCE"
PORT="${HSQA_PERFORMANCE_PORT:-25572}"
WARMUP_SECONDS="${HSQA_PERF_WARMUP_SECONDS:-6}"
SAMPLE_COUNT="${HSQA_PERF_SAMPLE_COUNT:-5}"
SAMPLE_INTERVAL_SECONDS="${HSQA_PERF_SAMPLE_INTERVAL_SECONDS:-2}"
START_DELAY_SECONDS="${HSQA_PERF_START_DELAY_SECONDS:-20}"

# Establish an artifact-aware signal path before fingerprinting creates the
# first evidence directory. Bash dispatches traps between commands, so a
# signal in the init critical section is remembered, initialization is allowed
# to reach a coherent boundary, and the pending interrupt is then recorded as
# a complete FAIL artifact before exiting.
HSQA_DEFERRED_INIT_SIGNAL=0
defer_init_signal() { # <shell-compatible exit status>; preserve first signal
    [ "$HSQA_DEFERRED_INIT_SIGNAL" -ne 0 ] \
        || HSQA_DEFERRED_INIT_SIGNAL="$1"
}
on_prelaunch_signal() { # <shell-compatible exit status>
    local status="$1" signal_name manifest_status=0
    trap - INT TERM
    [ "$status" -eq 130 ] && signal_name=INT || signal_name=TERM
    if [ ! -s "$EV_DIR/manifest.json" ]; then
        ev_write_manifest "$ROLE" || manifest_status=1
    fi
    check_fail interrupted \
        "performance controller received $signal_name before process launch (exit $status)"
    [ "$manifest_status" -eq 0 ] \
        || check_fail manifest "could not complete interrupted-run manifest"
    finish_result FAIL
    write_reproduction "# Reproduce: interrupted performance matrix
tools/hearthstead-qa performance
Verdict: FAIL — controller received $signal_name before process launch (exit $status).
Artifact: $EV_DIR
"
    [ "$manifest_status" -eq 0 ] && exit "$status"
    exit 125
}
trap 'defer_init_signal 130' INT
trap 'defer_init_signal 143' TERM
EV_INIT_STATUS=0
ev_init "$ROLE" || EV_INIT_STATUS=$?
trap 'on_prelaunch_signal 130' INT
trap 'on_prelaunch_signal 143' TERM
if [ "$HSQA_DEFERRED_INIT_SIGNAL" -ne 0 ]; then
    on_prelaunch_signal "$HSQA_DEFERRED_INIT_SIGNAL"
fi
[ "$EV_INIT_STATUS" -eq 0 ] || exit 2
MANIFEST_FINGERPRINT=$(python3 - "$EV_DIR/manifest.json" <<'PYEOF'
import json, sys
print(json.load(open(sys.argv[1]))["fingerprint"])
PYEOF
) || exit 2
START_FINGERPRINT=$(hsqa_fingerprint) \
    || die source_fingerprint "could not capture performance start fingerprint"
if [ "$MANIFEST_FINGERPRINT" != "$START_FINGERPRINT" ]; then
    die source_changed_during_run \
        "source changed between evidence initialization and performance start"
fi
assert_perf_source_unchanged() { # <phase boundary>
    local phase="$1" current
    current=$(hsqa_fingerprint) \
        || die source_fingerprint "fingerprint failed at $phase"
    [ "$current" = "$START_FINGERPRINT" ] \
        || die source_changed_during_run \
            "source changed during performance at $phase: start=$START_FINGERPRINT current=$current"
}
check_pass source_fingerprint \
    "manifest=start=$START_FINGERPRINT"
case "${HSQA_TEST_SIGNAL_PHASE:-}" in
    '') ;;
    prelaunch-int)
        [ "$PERF_FIXTURE_MODE" = prelaunch-artifact ] || exit 2
        kill -INT $$ ;;
    prelaunch-term)
        [ "$PERF_FIXTURE_MODE" = prelaunch-artifact ] || exit 2
        kill -TERM $$ ;;
    *) exit 2 ;;
esac

# This script is a controller-owned probe for exactly this repository module,
# not a generic recursive-cleanup utility. Resolve both sides before any build,
# instance creation or world deletion so a typo/symlink cannot widen scope.
EXPECTED_MOD=$(realpath -m "$SCRIPT_DIR/../../hearthstead-neoforge")
CANONICAL_MOD=$(realpath -m "$MOD")
if [ ! -d "$CANONICAL_MOD" ] || [ "$CANONICAL_MOD" != "$EXPECTED_MOD" ] \
        || [ "$CANONICAL_MOD" = / ]; then
    die configuration \
        "performance module must resolve exactly to $EXPECTED_MOD; got $CANONICAL_MOD"
fi
MOD="$CANONICAL_MOD"

require_uint() { # <name> <value> <minimum> <maximum>
    local name="$1" value="$2" minimum="$3" maximum="$4"
    if ! hsqa_validate_uint "$value" "$minimum" "$maximum"; then
        die configuration "$name must be an integer in [$minimum,$maximum], got '$value'"
    fi
}

# These are the established comparable baseline, not merely defaults. Runtime
# knobs may lengthen the profile for a soak run, but must never shorten it and
# manufacture a cheaper PASS than the 6/5/2/20 contract.
require_uint HSQA_PERF_WARMUP_SECONDS "$WARMUP_SECONDS" 6 1800
require_uint HSQA_PERF_SAMPLE_COUNT "$SAMPLE_COUNT" 5 1000
require_uint HSQA_PERF_SAMPLE_INTERVAL_SECONDS "$SAMPLE_INTERVAL_SECONDS" 2 300
require_uint HSQA_PERF_START_DELAY_SECONDS "$START_DELAY_SECONDS" 20 300
require_uint HSQA_PERFORMANCE_PORT "$PORT" 1024 65535

# Default covers boot + founding + four windows + three membership round-trips
# + a generous shutdown margin. Longer soak-like samples therefore extend
# safely without editing this script. An explicit timeout can only change the
# wall-clock guard, never population or MSPT budgets.
ESTIMATED_SECONDS=$((
    START_DELAY_SECONDS + 10 + 2
    + 4 * WARMUP_SECONDS
    + 4 * (SAMPLE_COUNT - 1) * SAMPLE_INTERVAL_SECONDS
    + 3 * 3 + 5
))
DEFAULT_TIMEOUT_SECONDS=$((ESTIMATED_SECONDS + 90))
[ "$DEFAULT_TIMEOUT_SECONDS" -lt 180 ] && DEFAULT_TIMEOUT_SECONDS=180
SERVER_TIMEOUT_SECONDS="${HSQA_PERFORMANCE_TIMEOUT_SECONDS:-$DEFAULT_TIMEOUT_SECONDS}"
require_uint HSQA_PERFORMANCE_TIMEOUT_SECONDS "$SERVER_TIMEOUT_SECONDS" 120 7200

PARSER="$SCRIPT_DIR/parse_perf_matrix.py"
[ -f "$PARSER" ] || die parser_present "missing $PARSER"
ACTIVE_BLESSING_PARSER="$SCRIPT_DIR/parse_active_blessing_perf.py"
[ -f "$ACTIVE_BLESSING_PARSER" ] \
    || die active_blessing_parser_present "missing $ACTIVE_BLESSING_PARSER"
check_pass configuration \
    "scales=1/25/50/100 warmup=${WARMUP_SECONDS}s samples=$SAMPLE_COUNT interval=${SAMPLE_INTERVAL_SECONDS}s timeout=${SERVER_TIMEOUT_SECONDS}s"

BUILD_PID=''; BUILD_PGID=''; BUILD_PID_RECORD=''; BUILD_PID_ROLE="$PERF_PROCESS_ROLE"
INSTALL_PID=''; INSTALL_PGID=''; INSTALL_PID_RECORD=''; INSTALL_PID_ROLE="$PERF_PROCESS_ROLE"
SERVER_PID=''; SERVER_PGID=''; SERVER_PID_RECORD=''; SERVER_PID_ROLE="$PERF_PROCESS_ROLE"
ACTIVE_PID=''; ACTIVE_PGID=''; ACTIVE_PID_RECORD=''; ACTIVE_PID_ROLE="$PERF_PROCESS_ROLE"
CRASH_PID=''; CRASH_PGID=''; CRASH_PID_RECORD=''; CRASH_PID_ROLE="$PERF_PROCESS_ROLE"
TEARDOWN_DONE=0
TEARDOWN_STATUS=0
teardown() {
    local record_var pid_var pgid_var role_var record effective_role cleanup_status=0
    [ "$TEARDOWN_DONE" = 1 ] && return "$TEARDOWN_STATUS"
    TEARDOWN_DONE=1
    # Clean up only records created by this run.  A pre-existing recovery
    # record is never deleted or used as authority to kill a process; the
    # fail-closed preflight below leaves it intact for an explicit reap.
    for record_var in CRASH_PID_RECORD ACTIVE_PID_RECORD SERVER_PID_RECORD \
            INSTALL_PID_RECORD BUILD_PID_RECORD; do
        case "$record_var" in
            CRASH_PID_RECORD) pid_var=CRASH_PID; pgid_var=CRASH_PGID; role_var=CRASH_PID_ROLE ;;
            ACTIVE_PID_RECORD) pid_var=ACTIVE_PID; pgid_var=ACTIVE_PGID; role_var=ACTIVE_PID_ROLE ;;
            SERVER_PID_RECORD) pid_var=SERVER_PID; pgid_var=SERVER_PGID; role_var=SERVER_PID_ROLE ;;
            INSTALL_PID_RECORD) pid_var=INSTALL_PID; pgid_var=INSTALL_PGID; role_var=INSTALL_PID_ROLE ;;
            BUILD_PID_RECORD) pid_var=BUILD_PID; pgid_var=BUILD_PGID; role_var=BUILD_PID_ROLE ;;
        esac
        record="${!record_var:-}"
        effective_role="${!role_var:-}"
        if [ -n "$record" ]; then
            if [ -n "$effective_role" ] \
                    && hsqa_stop_tracked "$effective_role" "$record"; then
                printf -v "$record_var" '%s' ''
                printf -v "$pid_var" '%s' ''
                printf -v "$pgid_var" '%s' ''
            else
                cleanup_status=1
            fi
        elif [ -n "${!pid_var:-}" ] || [ -n "${!pgid_var:-}" ]; then
            # A live numeric target without its durable starttime+identity
            # record is not safe kill authority.  Every launch failure must
            # prove its provisional group absent before reaching this point.
            echo "performance teardown: unregistered $record_var target retained for manual review" >&2
            cleanup_status=1
        fi
    done
    if [ -n "$(port_holder "$PORT")" ]; then
        echo "performance teardown: port $PORT remained occupied" >&2
        cleanup_status=1
    fi
    TEARDOWN_STATUS="$cleanup_status"
    return "$cleanup_status"
}

complete_perf_phase() { # <record-var> <pid-var> <pgid-var> <role-var>; 1=cleaned residual, 2=uncertain
    local record_var="$1" pid_var="$2" pgid_var="$3" role_var="$4"
    local record effective_role residual=0
    record="${!record_var:-}"
    effective_role="${!role_var:-}"
    [ -n "$record" ] || return 2
    [ -n "$effective_role" ] || return 2
    hsqa_record_target_present "$record" && residual=1
    hsqa_stop_tracked "$effective_role" "$record" || return 2
    printf -v "$record_var" '%s' ''
    printf -v "$pid_var" '%s' ''
    printf -v "$pgid_var" '%s' ''
    return "$residual"
}
on_signal() { # <shell-compatible exit status>
    local status="$1" signal_name cleanup_status=0
    trap - INT TERM
    [ "$status" -eq 130 ] && signal_name=INT || signal_name=TERM
    teardown || cleanup_status=1
    check_fail interrupted \
        "performance controller received $signal_name (exit $status)"
    if [ "$cleanup_status" -ne 0 ]; then
        check_fail process_cleanup \
            "tracked process survived signal cleanup; recovery pidfile retained"
    fi
    finish_result FAIL
    write_reproduction "# Reproduce: interrupted performance matrix
tools/hearthstead-qa performance
Verdict: FAIL — controller received $signal_name (exit $status).
Cleanup status: $cleanup_status (125 means cleanup could not prove absence).
Artifact: $EV_DIR
"
    [ "$cleanup_status" -eq 0 ] && exit "$status"
    exit 125
}
on_exit() {
    local status=$? cleanup_status=0
    trap - EXIT INT TERM
    teardown || cleanup_status=1
    if [ "$cleanup_status" -ne 0 ]; then
        check_fail process_cleanup \
            "performance exit could not prove exact process/port cleanup; recovery records retained"
        finish_result FAIL
        [ -f "$EV_DIR/reproduction.md" ] || write_reproduction "# Reproduce: performance cleanup failure
tools/hearthstead-qa performance
Verdict: FAIL — exact process cleanup could not be proven.
Artifact: $EV_DIR
"
        exit 125
    fi
    exit "$status"
}
trap on_exit EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# Every historical fixed-role record and every run-scoped performance record
# is recovery authority.  Check the whole exact family before even the crash
# fixture is allowed to create a new child.
for RECOVERY_FILE in "$HSQA_PIDDIR/performance.pids" \
        "$HSQA_PIDDIR"/performance-*.pids; do
    [ -e "$RECOVERY_FILE" ] || continue
    [ -f "$RECOVERY_FILE" ] && [ ! -L "$RECOVERY_FILE" ] \
        || die process_recovery_preflight \
            "unsafe performance recovery record path must be resolved manually"
    [ ! -s "$RECOVERY_FILE" ] \
        || die process_recovery_preflight \
            "pre-existing performance recovery records must be resolved with tools/hearthstead-qa reap"
done

if [ "$PERF_FIXTURE_MODE" = post-release-crash ]; then
    PERF_PAYLOAD_SENTINEL="$PERF_FIXTURE_ROOT/perf-payload-ready"
    PERF_CONTROLLER_READY="$PERF_FIXTURE_ROOT/perf-controller-ready"
    [ ! -e "$PERF_PAYLOAD_SENTINEL" ] && [ ! -e "$PERF_CONTROLLER_READY" ] \
        || die fixture_state "performance crash fixture contains stale readiness state"
    hsqa_launch_tracked_group "$PERF_PROCESS_ROLE" "$PERF_CRASH_ID" on_signal \
            CRASH_PID CRASH_PGID CRASH_PID_RECORD CRASH_PID_ROLE \
            /bin/bash --noprofile --norc -p -c \
            'printf "payload-ready\n" > "$1"; exec /bin/sleep 86400' \
            hsqa-perf-crash "$PERF_PAYLOAD_SENTINEL" \
        || die crash_fixture_launch \
            "post-release fixture could not launch and durably register its payload"
    [ -f "$PERF_PAYLOAD_SENTINEL" ] && [ ! -L "$PERF_PAYLOAD_SENTINEL" ] \
        || die crash_fixture_release "tracked payload was not released"
    grep -Fxq -- "$CRASH_PID_RECORD" "$HSQA_PIDDIR/$CRASH_PID_ROLE.pids" \
        || die crash_fixture_record "post-release recovery record was not durable"
    hsqa_record_target_present "$CRASH_PID_RECORD" \
        || die crash_fixture_process "post-release tracked group is not live"
    PERF_PROBE_PGID=$(hsqa_process_group_of $$)
    [[ "$PERF_PROBE_PGID" =~ ^[1-9][0-9]*$ ]] \
        || die crash_fixture_controller "could not identify performance controller group"
    PERF_READY_TMP="$PERF_CONTROLLER_READY.tmp.$$.$RANDOM"
    {
        printf 'probe_pid=%s\n' "$$"
        printf 'probe_pgid=%s\n' "$PERF_PROBE_PGID"
        printf 'role=%s\n' "$CRASH_PID_ROLE"
        printf 'identity=%s\n' "$PERF_CRASH_ID"
        printf 'pid=%s\n' "$CRASH_PID"
        printf 'pgid=%s\n' "$CRASH_PGID"
        printf 'record=%s\n' "$CRASH_PID_RECORD"
    } > "$PERF_READY_TMP" \
        && mv -f -- "$PERF_READY_TMP" "$PERF_CONTROLLER_READY" \
        || die crash_fixture_ready "could not publish crash-fixture readiness"
    while hsqa_record_target_present "$CRASH_PID_RECORD"; do
        sleep 1
    done
    die crash_fixture_unexpected_exit \
        "post-release payload exited before the controller was SIGKILLed"
fi

if ! MSG=$(preflight_port "$PORT" "$ROLE"); then die port_preflight "$MSG"; fi
check_pass port_preflight "port $PORT free before launch"

# A standalone performance verdict must measure the source fingerprint that
# appears in its manifest, never whichever stale JAR happened to be newest.
# Keep the normal GameTest namespace out of this build as well; only the later
# explicitly-scoped active subprocess may select its four-test namespace.
assert_perf_source_unchanged pre_build
hsqa_launch_tracked_group "$PERF_PROCESS_ROLE" "$PERF_BUILD_ID" on_signal \
        BUILD_PID BUILD_PGID BUILD_PID_RECORD BUILD_PID_ROLE \
        bash -c 'cd "$1" || exit 1; exec env -u HSQA_GAMETEST_NAMESPACE timeout --foreground --kill-after=10 600 ./gradlew --no-daemon --quiet build' \
        -- "$MOD" \
        > "$EV_LOGS/build.log" 2>&1 \
    || die current_build_process_group \
        "current build wrapper could not be isolated and durably registered"
wait "$BUILD_PID" 2>/dev/null
BUILD_STATUS=$?
BUILD_GROUP_LEAK=0
complete_perf_phase BUILD_PID_RECORD BUILD_PID BUILD_PGID BUILD_PID_ROLE \
    || BUILD_GROUP_LEAK=$?
if [ "$BUILD_STATUS" -ne 0 ] || [ "$BUILD_GROUP_LEAK" -ne 0 ]; then
    die current_build \
        "current source build exited $BUILD_STATUS (residual_group=$BUILD_GROUP_LEAK) — see logs/build.log"
fi
MOD_VERSION=$(sed -n 's/^mod_version=//p' "$MOD/gradle.properties" \
    | tr -d '\r' | tail -1)
[ -n "$MOD_VERSION" ] \
    || die current_build "gradle.properties has no mod_version"
JAR="$MOD/build/libs/hearthstead-${MOD_VERSION}.jar"
[ -f "$JAR" ] \
    || die current_build "current build did not produce exact $(basename "$JAR")"
JAR_SHA=$(sha256sum "$JAR" | cut -d' ' -f1)
check_pass current_build "$(basename "$JAR") sha256=$JAR_SHA"
assert_perf_source_unchanged post_build

hsqa_launch_tracked_group "$PERF_PROCESS_ROLE" "$PERF_INSTALL_ID" on_signal \
        INSTALL_PID INSTALL_PGID INSTALL_PID_RECORD INSTALL_PID_ROLE \
        timeout --foreground --kill-after=10 600 \
        bash "$SCRIPT_DIR/server_install.sh" "$MOD" \
        > "$EV_LOGS/install.log" 2>&1 \
    || die install_process_group \
        "install wrapper could not be isolated and durably registered"
wait "$INSTALL_PID" 2>/dev/null
INSTALL_STATUS=$?
INSTALL_GROUP_LEAK=0
complete_perf_phase INSTALL_PID_RECORD INSTALL_PID INSTALL_PGID INSTALL_PID_ROLE \
    || INSTALL_GROUP_LEAK=$?
if [ "$INSTALL_STATUS" -ne 0 ] || [ "$INSTALL_GROUP_LEAK" -ne 0 ]; then
    die install \
        "shared install exited $INSTALL_STATUS (residual_group=$INSTALL_GROUP_LEAK) — see logs/install.log"
fi
check_pass install "shared install present"

INST=$(env HSQA_LEVEL_TYPE=flat \
    bash "$SCRIPT_DIR/server_instance.sh" "$ROLE" "$PORT" "$MOD" \
    2>"$EV_LOGS/instance.log" | tail -1)
[ -d "$INST" ] \
    || die instance "server_instance.sh did not produce a usable instance — see logs/instance.log"
if [ "$(grep -Fxc 'level-type=minecraft\:flat' "$INST/server.properties" 2>/dev/null)" -ne 1 ]; then
    die instance_world_type \
        "performance instance must contain exactly one level-type=minecraft\\:flat"
fi
check_pass instance "$INST"
check_pass instance_world_type "exact flat-world server.properties projection"
INSTANCE_JAR="$INST/mods/$(basename "$JAR")"
[ -f "$INSTANCE_JAR" ] \
    || die instance_jar "instance omitted exact current $(basename "$JAR")"
INSTANCE_JAR_SHA=$(sha256sum "$INSTANCE_JAR" | cut -d' ' -f1)
[ "$INSTANCE_JAR_SHA" = "$JAR_SHA" ] \
    || die instance_jar \
        "instance JAR sha256=$INSTANCE_JAR_SHA differs from current build $JAR_SHA"
check_pass instance_jar "$(basename "$INSTANCE_JAR") sha256=$INSTANCE_JAR_SHA"

COMMANDS="$INST/perf_cmds.txt"

# A 33x33 walled yard spans nine forced chunks. Keeping all of it loaded is
# essential on a playerless dedicated server; otherwise edge settlers can
# silently unload and make a population marker lie by omission.
{
    echo 'difficulty peaceful'
    echo 'gamerule doMobSpawning false'
    echo 'gamerule doDaylightCycle false'
    echo 'gamerule doWeatherCycle false'
    echo 'time set day'
    echo 'forceload add -16 -16 16 16'
    # Cross-dimension travel is the production registration seam exercised
    # below. A playerless server does not keep the Nether arrival chunks
    # loaded after the teleport ticket expires, so make the transit yard an
    # explicit part of this isolated fixture rather than letting the entities
    # unload before the return selector can see them. The fixed floor/air box
    # also removes seed-dependent netherrack, lava and entity cramming from
    # the one-second round-trip without changing the measured Overworld yard.
    echo 'execute in minecraft:the_nether run forceload add -16 -16 16 16'
    echo 'execute in minecraft:the_nether run fill -16 79 -16 16 79 16 minecraft:stone'
    echo 'execute in minecraft:the_nether run fill -16 80 -16 16 84 16 minecraft:air'
    echo 'execute in minecraft:the_nether run fill -16 80 -16 -16 84 16 minecraft:stone_bricks'
    echo 'execute in minecraft:the_nether run fill 16 80 -16 16 84 16 minecraft:stone_bricks'
    echo 'execute in minecraft:the_nether run fill -16 80 -16 16 84 -16 minecraft:stone_bricks'
    echo 'execute in minecraft:the_nether run fill -16 80 16 16 84 16 minecraft:stone_bricks'
    echo 'fill -16 -55 -16 16 -55 16 minecraft:stone'
    echo 'fill -16 -54 -16 -16 -49 16 minecraft:stone_bricks'
    echo 'fill 16 -54 -16 16 -49 16 minecraft:stone_bricks'
    echo 'fill -16 -54 -16 16 -49 -16 minecraft:stone_bricks'
    echo 'fill -16 -54 16 16 -49 16 minecraft:stone_bricks'
    echo 'setblock 0 -54 0 hearthstead:hearth'
    # Founding is synchronous once the hearth ticks. Ten seconds allows for
    # the first hearth tick and produces the real three-member starter state.
    echo 'SLEEP 10'
    echo 'scoreboard objectives add hsqa_pop dummy'
    echo 'tag @e[type=hearthstead:settler] add hsqa_perf'
    # The 1-settler window retains one genuinely founded member. Death paths
    # remove the other two records; two seconds lets dead entities leave @e.
    echo 'execute positioned 0 -54 0 run kill @e[type=hearthstead:settler,tag=hsqa_perf,sort=furthest,limit=2]'
    echo 'SLEEP 2'
    echo 'execute positioned 0 -54 0 run tag @e[type=hearthstead:settler,tag=hsqa_perf,sort=nearest,limit=1] add hsqa_template'
    # Killing the other founders exercises their real removal path, which
    # applies a morale penalty to survivors. Restore the ordinary fresh
    # baselines so scale 1 is not accidentally a mourning benchmark while
    # every later /summon member starts at the normal 80/90/60 values.
    echo 'data merge entity @e[type=hearthstead:settler,tag=hsqa_template,limit=1] {Hunger:80.0f,Energy:90.0f,Morale:60.0f}'
    echo 'tag @e[type=hearthstead:settler,tag=hsqa_template] add hsqa_member'
} > "$COMMANDS"

slot_x() { echo $(( (($1 - 1) % 10) * 3 - 13 )); }
slot_z() { echo $(( (($1 - 1) / 10) * 3 - 13 )); }

append_new_settlers() { # <first-slot> <last-slot>, inclusive
    local first="$1" last="$2" slot x z batch_count
    batch_count=$((last - first + 1))
    for slot in $(seq "$first" "$last"); do
        x=$(slot_x "$slot"); z=$(slot_z "$slot")
        printf 'summon hearthstead:settler %s -54 %s {Tags:["hsqa_perf","hsqa_pending","hsqa_slot_%03d"]}\n' \
            "$x" "$z" "$slot" >> "$COMMANDS"
    done

    # Plain /summon settlers are not members. Copy the founded template's
    # stable binding, prove both fields exist, then round-trip the new batch
    # through another dimension. EntityJoinLevelEvent re-registers every
    # returned member in the settlement's UUID-keyed records, so large-scale
    # manager loops and AI are exercised rather than measuring unbound shells.
    cat >> "$COMMANDS" <<'EOF'
execute as @e[type=hearthstead:settler,tag=hsqa_pending] run data modify entity @s SettlementId set from entity @e[type=hearthstead:settler,tag=hsqa_template,limit=1] SettlementId
execute as @e[type=hearthstead:settler,tag=hsqa_pending] run data modify entity @s HearthPos set from entity @e[type=hearthstead:settler,tag=hsqa_template,limit=1] HearthPos
execute as @e[type=hearthstead:settler,tag=hsqa_pending] if data entity @s SettlementId if data entity @s HearthPos run tag @s add hsqa_member
EOF

    # Give every traveler its own deterministic Nether slot. Apart from
    # avoiding the vanilla cramming rule at scale 50, per-slot commands make
    # a missing traveler name the exact broken fact in the server log.
    for slot in $(seq "$first" "$last"); do
        x=$(slot_x "$slot"); z=$(slot_z "$slot")
        printf 'execute as @e[type=hearthstead:settler,tag=hsqa_slot_%03d,limit=1] in minecraft:the_nether run tp @s %s 80 %s\n' \
            "$slot" "$x" "$z" >> "$COMMANDS"
    done

    cat >> "$COMMANDS" <<'EOF'
SLEEP 1
execute in minecraft:the_nether store result score hsqa_transit hsqa_pop if entity @e[type=hearthstead:settler,tag=hsqa_pending]
scoreboard players get hsqa_transit hsqa_pop
EOF
    printf 'execute if score hsqa_transit hsqa_pop matches %d run say HSQA_PERF_TRANSIT_OK added=%d target_scale=%d\n' \
        "$batch_count" "$batch_count" "$last" >> "$COMMANDS"

    # Returning to the normal dimension fires EntityJoinLevelEvent and is the
    # production path that repairs/creates the settlement's UUID record. Send
    # each member straight back to its measured slot, then prove the complete
    # batch is visible in the Overworld before removing the transit tag.
    for slot in $(seq "$first" "$last"); do
        x=$(slot_x "$slot"); z=$(slot_z "$slot")
        printf 'execute in minecraft:the_nether as @e[type=hearthstead:settler,tag=hsqa_slot_%03d,limit=1] in minecraft:overworld run tp @s %s -54 %s\n' \
            "$slot" "$x" "$z" >> "$COMMANDS"
    done
    cat >> "$COMMANDS" <<'EOF'
execute store result score hsqa_returned hsqa_pop if entity @e[type=hearthstead:settler,tag=hsqa_pending]
scoreboard players get hsqa_returned hsqa_pop
EOF
    printf 'execute if score hsqa_returned hsqa_pop matches %d run say HSQA_PERF_RETURN_OK added=%d target_scale=%d\n' \
        "$batch_count" "$batch_count" "$last" >> "$COMMANDS"
    cat >> "$COMMANDS" <<'EOF'
tag @e[type=hearthstead:settler,tag=hsqa_pending] remove hsqa_pending
SLEEP 2
EOF
}

append_measurement() { # <exact-scale>
    local scale="$1" sample
    cat >> "$COMMANDS" <<'EOF'
execute positioned 0 -54 0 run hearthstead info
EOF
    # The record lookup is a setup proof, not measured work. Flush it through
    # the complete warm-up before the population marker and first tick query.
    printf 'SLEEP %s\n' "$WARMUP_SECONDS" >> "$COMMANDS"
    cat >> "$COMMANDS" <<'EOF'
execute store result score hsqa_count hsqa_pop if entity @e[type=hearthstead:settler]
execute store result score hsqa_member_count hsqa_pop if entity @e[type=hearthstead:settler,tag=hsqa_member]
scoreboard players get hsqa_count hsqa_pop
scoreboard players get hsqa_member_count hsqa_pop
EOF
    printf 'execute if score hsqa_count hsqa_pop matches %d if score hsqa_member_count hsqa_pop matches %d run say HSQA_PERF_POPULATION_OK scale=%d expected=%d\n' \
        "$scale" "$scale" "$scale" "$scale" >> "$COMMANDS"
    printf 'say HSQA_PERF_BEGIN scale=%d\n' "$scale" >> "$COMMANDS"
    for sample in $(seq 1 "$SAMPLE_COUNT"); do
        echo 'tick query' >> "$COMMANDS"
        if [ "$sample" -lt "$SAMPLE_COUNT" ]; then
            printf 'SLEEP %s\n' "$SAMPLE_INTERVAL_SECONDS" >> "$COMMANDS"
        fi
    done
    printf 'say HSQA_PERF_END scale=%d\n' "$scale" >> "$COMMANDS"
}

append_measurement 1
append_new_settlers 2 25
append_measurement 25
append_new_settlers 26 50
append_measurement 50
append_new_settlers 51 100
append_measurement 100

hsqa_launch_tracked_group "$PERF_PROCESS_ROLE" "$PERF_SERVER_ID" on_signal \
        SERVER_PID SERVER_PGID SERVER_PID_RECORD SERVER_PID_ROLE \
        bash -c 'delay="$1"; commands="$2"; inst="$3"; server_timeout="$4"; set +m; (sleep "$delay"; while IFS= read -r line; do case "$line" in SLEEP*) sleep "${line#SLEEP }";; *) echo "$line";; esac; done < "$commands"; sleep 5; echo stop) | (cd "$inst" && exec timeout --foreground --kill-after=10 "$server_timeout" ./run.sh nogui)' \
        -- "$START_DELAY_SECONDS" "$COMMANDS" "$INST" "$SERVER_TIMEOUT_SECONDS" \
        > "$EV_LOGS/perf-boot.out" 2>&1 \
    || die performance_process_group \
        "dedicated performance wrapper could not be isolated and durably registered"
wait "$SERVER_PID" 2>/dev/null
SERVER_STATUS=$?
SERVER_GROUP_LEAK=0
complete_perf_phase SERVER_PID_RECORD SERVER_PID SERVER_PGID SERVER_PID_ROLE \
    || SERVER_GROUP_LEAK=$?
cp "$INST/logs/latest.log" "$EV_LOGS/performance.server.log" 2>/dev/null || true
if [ "$SERVER_STATUS" -ne 0 ] || [ "$SERVER_GROUP_LEAK" -ne 0 ]; then
    REASON=$(tail -8 "$EV_LOGS/perf-boot.out" 2>/dev/null | tr '\n' ' ')
    die performance_server_exit \
        "dedicated performance server exited $SERVER_STATUS (residual_group=$SERVER_GROUP_LEAK): $REASON"
fi
if ! MSG=$(preflight_port "$PORT" "$ROLE"); then
    die performance_server_teardown \
        "dedicated performance server left its port occupied: $MSG"
fi
check_pass performance_server_teardown \
    "process group reaped and port $PORT free"

# FACT: server up, before anything about settlers or MSPT.
if ! grep -q 'Done (' "$EV_LOGS/performance.server.log" 2>/dev/null; then
    REASON=$(grep -m1 -E 'FAILED TO BIND|Address already in use|Exception|Error' \
        "$EV_LOGS/performance.server.log" 2>/dev/null \
        || echo 'no Done( line — see logs/performance.server.log')
    die server_started "server never reached Done( : $REASON"
fi
check_pass server_started "$(grep -m1 'Done (' "$EV_LOGS/performance.server.log")"

MATRIX_JSON="$EV_DIR/performance-matrix.json"
MATRIX_TSV="$EV_LOGS/performance-matrix.tsv"
PARSER_ERRORS="$EV_LOGS/performance-matrix-parser.err"
python3 "$PARSER" "$EV_LOGS/performance.server.log" \
    --samples "$SAMPLE_COUNT" --json-out "$MATRIX_JSON" \
    > "$MATRIX_TSV" 2> "$PARSER_ERRORS"
PARSER_STATUS=$?

if [ "$PARSER_STATUS" -eq 1 ]; then
    ERROR=$(tail -4 "$PARSER_ERRORS" 2>/dev/null | tr '\n' ' ')
    die performance_matrix "incomplete/malformed 1/25/50/100 evidence: $ERROR"
fi
if [ "$PARSER_STATUS" -ne 0 ] && [ "$PARSER_STATUS" -ne 2 ]; then
    die performance_matrix "parser exited unexpectedly with status $PARSER_STATUS"
fi
check_pass performance_matrix \
    "four exact-population windows parsed; machine summary performance-matrix.json"

BUDGET_FAILED=0
ROW_COUNT=0
BASELINE_HEADER=$'scale\tpopulation\tbound_members\tsettlement_records\tsamples\tmedian_mspt\taverage_mspt\tbudget_mspt\tgate'
[ "$(head -1 "$MATRIX_TSV")" = "$BASELINE_HEADER" ] \
    || die performance_matrix "unexpected baseline parser TSV schema"
while IFS=$'\t' read -r scale population members records samples median average budget gate; do
    [ "$scale" = 'scale' ] && continue
    [ -z "$scale" ] && continue
    ROW_COUNT=$((ROW_COUNT + 1))
    check_pass "population_$(printf '%03d' "$scale")" \
        "scoreboard population=$population bound_members=$members settlement_records=$records expected=$scale"
    if [ "$gate" = 'INFO' ]; then
        check_pass "mspt_$(printf '%03d' "$scale")" \
            "samples=$samples median=${median}ms average=${average}ms (reporting scale; no absolute gate)"
    elif [ "$gate" = 'PASS' ]; then
        check_pass "mspt_$(printf '%03d' "$scale")" \
            "samples=$samples median=${median}ms average=${average}ms <= ${budget}ms absolute average gate"
    else
        check_fail "mspt_$(printf '%03d' "$scale")" \
            "samples=$samples median=${median}ms average=${average}ms exceeds ${budget}ms absolute average gate"
        BUDGET_FAILED=1
    fi
done < "$MATRIX_TSV"

[ "$ROW_COUNT" -eq 4 ] \
    || die performance_matrix "parser emitted $ROW_COUNT scale rows, expected 4"
assert_perf_source_unchanged post_baseline

# The stable dedicated-server matrix above remains the absolute MSPT gate.
# This second, isolated GameTest namespace proves the permanent personal,
# valid-building-zone and exact raid-authority paths at the same supported
# scales with deterministic structural counters. Its duration is reporting
# only: GameTest startup/fixtures are not mixed into the dedicated budgets.
ACTIVE_NAMESPACE='hearthstead_active_perf'
ACTIVE_TIMEOUT_SECONDS=300
ACTIVE_GRADLE_LOG="$EV_LOGS/active-blessing-performance.gradle.log"
ACTIVE_SERVER_LOG="$EV_LOGS/active-blessing-performance.server.log"
ACTIVE_SOURCE_LOG=$(hsqa_require_plain_mod_log "$MOD") \
    || die active_blessing_gametest "module run/logs/latest.log path is symlinked or unsafe"
ACTIVE_JSON="$EV_DIR/active-blessing-performance-matrix.json"
ACTIVE_TSV="$EV_LOGS/active-blessing-performance-matrix.tsv"
ACTIVE_ERRORS="$EV_LOGS/active-blessing-performance-parser.err"

# Only this exact disposable GameTest world is removed. The namespace
# override is scoped to the one Gradle command and cannot leak into any later
# suite; build.gradle also rejects every namespace outside its two-value
# allow-list.
assert_perf_source_unchanged pre_active
hsqa_clear_gametest_world "$MOD" \
    || die active_blessing_world "refusing unsafe GameTest world cleanup"
rm -f -- "$ACTIVE_SOURCE_LOG"
[ ! -e "$ACTIVE_SOURCE_LOG" ] \
    || die active_blessing_gametest \
        "could not remove stale active GameTest source log"
ACTIVE_LAUNCHED_AT=$(date +%s)
hsqa_launch_tracked_group "$PERF_PROCESS_ROLE" "$PERF_ACTIVE_ID" on_signal \
        ACTIVE_PID ACTIVE_PGID ACTIVE_PID_RECORD ACTIVE_PID_ROLE \
        bash -c 'cd "$1" || exit 1; exec env HSQA_GAMETEST_NAMESPACE="$2" timeout --foreground --kill-after=10 "$3" ./gradlew --no-daemon runGameTestServer' \
        -- "$MOD" "$ACTIVE_NAMESPACE" "$ACTIVE_TIMEOUT_SECONDS" \
        > "$ACTIVE_GRADLE_LOG" 2>&1 \
    || die active_blessing_process_group \
        "active GameTest wrapper could not be isolated and durably registered"
wait "$ACTIVE_PID" 2>/dev/null
ACTIVE_STATUS=$?
ACTIVE_GROUP_LEAK=0
complete_perf_phase ACTIVE_PID_RECORD ACTIVE_PID ACTIVE_PGID ACTIVE_PID_ROLE \
    || ACTIVE_GROUP_LEAK=$?
if [ "$ACTIVE_STATUS" -ne 0 ] || [ "$ACTIVE_GROUP_LEAK" -ne 0 ]; then
    ACTIVE_REASON=$(tail -8 "$ACTIVE_GRADLE_LOG" 2>/dev/null | tr '\n' ' ')
    die active_blessing_gametest \
        "isolated active-Blessing GameTest exited $ACTIVE_STATUS (residual_group=$ACTIVE_GROUP_LEAK): $ACTIVE_REASON"
fi
[ -f "$ACTIVE_SOURCE_LOG" ] \
    || die active_blessing_gametest "active GameTest produced no server log"
ACTIVE_LOG_MTIME=$(stat -c %Y "$ACTIVE_SOURCE_LOG" 2>/dev/null || echo 0)
[ "$ACTIVE_LOG_MTIME" -ge "$ACTIVE_LAUNCHED_AT" ] \
    || die active_blessing_gametest \
        "active GameTest source log predates this launch"
cp "$ACTIVE_SOURCE_LOG" "$ACTIVE_SERVER_LOG" \
    || die active_blessing_gametest "could not preserve active server log"

python3 "$ACTIVE_BLESSING_PARSER" "$ACTIVE_SERVER_LOG" \
    --json-out "$ACTIVE_JSON" > "$ACTIVE_TSV" 2> "$ACTIVE_ERRORS"
ACTIVE_PARSER_STATUS=$?
if [ "$ACTIVE_PARSER_STATUS" -ne 0 ]; then
    ACTIVE_REASON=$(tail -6 "$ACTIVE_ERRORS" 2>/dev/null | tr '\n' ' ')
    die active_blessing_matrix \
        "missing/malformed active 1/25/50/100 evidence: $ACTIVE_REASON"
fi
check_pass active_blessing_gametest \
    "exactly four required active-namespace GameTests passed"

ACTIVE_ROW_COUNT=0
ACTIVE_HEADER=$'scale\tcase\tpasses\tgroups\tlive_entities\trecords\tpersonal\tbuilding_zones\tphysical_zones\tbuildings\tauthorized\tunauthorized_rejected\toutgoing\tincoming\tsnared\tstable_modifiers\tlookups\trebuilds\tcandidate_checks\tentities_discarded\tsettlements_removed\tduration_ns\tgate'
[ "$(head -1 "$ACTIVE_TSV")" = "$ACTIVE_HEADER" ] \
    || die active_blessing_matrix "unexpected active parser TSV schema"
while IFS=$'\t' read -r scale case_id passes groups live_entities records \
        personal building_zones physical_zones buildings authorized denied \
        outgoing incoming snared stable_modifiers lookups rebuilds \
        candidate_checks discarded settlements_removed \
        duration_ns gate; do
    [ "$scale" = 'scale' ] && continue
    [ -z "$scale" ] && continue
    ACTIVE_ROW_COUNT=$((ACTIVE_ROW_COUNT + 1))
    [ "$gate" = 'PASS' ] \
        || die active_blessing_matrix "scale $scale parser gate was $gate"
    check_pass "active_blessing_$(printf '%03d' "$scale")" \
        "case=$case_id live=$live_entities records=$records personal=$personal building_zones=$building_zones physical_zones=$physical_zones authorized=$authorized effects=$outgoing/$incoming/$snared stable_modifiers=$stable_modifiers lookups=$lookups rebuilds=$rebuilds groups=$groups teardown=$discarded/$settlements_removed duration_ns=$duration_ns"
done < "$ACTIVE_TSV"
[ "$ACTIVE_ROW_COUNT" -eq 4 ] \
    || die active_blessing_matrix \
        "active parser emitted $ACTIVE_ROW_COUNT scale rows, expected 4"
assert_perf_source_unchanged post_active

write_reproduction "# Reproduce: performance matrix
tools/hearthstead-qa performance
Instance: $INST (port $PORT)
Default bounded profile: warmup=${WARMUP_SECONDS}s, samples=$SAMPLE_COUNT, interval=${SAMPLE_INTERVAL_SECONDS}s
Longer profile: set HSQA_PERF_WARMUP_SECONDS, HSQA_PERF_SAMPLE_COUNT and HSQA_PERF_SAMPLE_INTERVAL_SECONDS; absolute gates remain fixed.
Machine summary: $MATRIX_JSON
Active Blessing structural summary: $ACTIVE_JSON
"

if [ "$BUDGET_FAILED" -ne 0 ] || [ "$PARSER_STATUS" -eq 2 ]; then
    finish_result FAIL
    echo 'FAIL: one or more absolute average-MSPT gates failed — see performance-matrix.json'
    exit 1
fi

assert_perf_source_unchanged final
if ! teardown; then
    check_fail process_cleanup \
        "performance success path could not prove exact process/port cleanup; recovery records retained"
    finish_result FAIL
    write_reproduction "# Reproduce: performance cleanup failure
tools/hearthstead-qa performance
Verdict: FAIL — success evidence was withheld because exact cleanup was not proven.
Artifact: $EV_DIR
"
    trap - EXIT
    exit 125
fi
trap - EXIT
finish_result PASS
SUMMARY=$(tail -n +2 "$MATRIX_TSV" \
    | awk -F '\t' '{printf "%s%s:median=%sms avg=%sms", sep, $1, $6, $7; sep="; "}')
echo "performance matrix ok: $SUMMARY; active Blessing 1/25/50/100 structural matrix PASS; artifact=$EV_DIR"
