#!/usr/bin/env bash
# Executable contract for the tracked tmux pre-exec barrier.
set -u
set -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
command -v tmux >/dev/null || { echo "tmux tracked selftest: FAIL: tmux missing" >&2; exit 1; }

TEST_ROOT=$(mktemp -d /tmp/hsqa-tmux-tracked.XXXXXX) || exit 1
case "$TEST_ROOT" in /tmp/hsqa-tmux-tracked.*) ;; *) exit 1;; esac
chmod 700 "$TEST_ROOT" || exit 1
export HSQA_REPO="$REPO"
export HSQA_SAFE_TMP_ROOT="$TEST_ROOT"
export HSQA_PIDDIR="$TEST_ROOT/pids"
export HSQA_ARTIFACTS="$TEST_ROOT/artifacts"
export HSQA_TMUX_SELFTEST_MODE=1
export HSQA_TMUX_SOCKET="$TEST_ROOT/tmux.sock"
unset TMUX TMUX_TMPDIR

. "$HERE/lib_harness.sh"
. "$HERE/lib_tmux_harness.sh"

RUN_ID="$(printf '%s-%s-%s' "$$" "$RANDOM" "$RANDOM")"
SESSIONS=()
CONTROL_TUPLES=()
SOCKET_EXTERNAL_PID=''
cleanup() {
    local session
    if [[ "${SOCKET_EXTERNAL_PID:-}" =~ ^[1-9][0-9]*$ ]]; then
        kill -9 "$SOCKET_EXTERNAL_PID" 2>/dev/null || true
        wait "$SOCKET_EXTERNAL_PID" 2>/dev/null || true
        SOCKET_EXTERNAL_PID=''
    fi
    for session in "${SESSIONS[@]}"; do
        hsqa_tmux has-session -t "$session" 2>/dev/null \
            && hsqa_tmux kill-session -t "$session" 2>/dev/null || true
    done
    hsqa_tmux kill-server 2>/dev/null || true
    case "$TEST_ROOT" in /tmp/hsqa-tmux-tracked.*) rm -rf -- "$TEST_ROOT";; esac
}
trap cleanup EXIT

fail() {
    echo "tmux tracked selftest: FAIL: $*" >&2
    exit 1
}

wait_path() { # <path> [attempts]
    local path="$1" attempts="${2:-500}" attempt
    for attempt in $(seq 1 "$attempts"); do
        [ -e "$path" ] && return 0
        sleep 0.01
    done
    return 1
}

wait_group_absent() { # <pgid>
    local pgid="$1" attempt
    for attempt in $(seq 1 500); do
        kill -0 -- "-$pgid" 2>/dev/null || return 0
        sleep 0.01
    done
    return 1
}

control_tuple_for_session() { # <immutable-session-id>
    local session_id="$1" control_identity topology pane_id pane_pid extra start
    control_identity=$(hsqa_tmux show-options -v -t "$session_id" \
        @hsqa_control_identity 2>/dev/null) || return 1
    topology=$(hsqa_tmux list-panes -t "$session_id:control" \
        -F '#{pane_id}|#{pane_pid}' 2>/dev/null) || return 1
    IFS='|' read -r pane_id pane_pid extra <<< "$topology"
    [ -z "${extra:-}" ] && [[ "$pane_id" =~ ^%[0-9]+$ ]] \
        && [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    start=$(hsqa_process_starttime "$pane_pid")
    [[ "$start" =~ ^[0-9]+$ ]] || return 1
    printf '%s|%s|%s\n' "$pane_pid" "$start" "$control_identity"
}

new_owned_session() { # <session-name> <owner> <session-id-var>
    local output_var="$3" tuple
    hsqa_create_owned_tmux_session "$1" "$2" '' "$output_var" \
        || fail "could not create owned session $1"
    SESSIONS+=("${!output_var}")
    tuple=$(control_tuple_for_session "${!output_var}") \
        || fail "control tuple missing"
    CONTROL_TUPLES+=("$tuple")
}

# Happy path: the payload becomes observable only after durable registration,
# and exact record/session cleanup removes only this run's processes.
SESSION_OK="hsqa-tmux-ok-$RUN_ID"
OWNER_OK="tmux-owner-$RUN_ID"
ROLE_OK="tmux-role-$RUN_ID"
IDENTITY_OK="tmux-server-$RUN_ID"
SENTINEL_OK="$TEST_ROOT/happy-payload"
SESSION_OK_ID=''
new_owned_session "$SESSION_OK" "$OWNER_OK" SESSION_OK_ID
WINDOW_ID=''; PANE_ID=''; PID=''; PGID=''; RECORD=''; EFFECTIVE_ROLE="$ROLE_OK"
hsqa_launch_tracked_tmux_window "$SESSION_OK_ID" "$OWNER_OK" server \
    "$ROLE_OK" "$IDENTITY_OK" '' WINDOW_ID PANE_ID PID PGID RECORD EFFECTIVE_ROLE \
    bash -c 'printf payload > "$1"; sleep 30' -- "$SENTINEL_OK" \
    || fail "happy-path launch failed"
wait_path "$SENTINEL_OK" || fail "happy payload was never released"
[[ "$RECORD" =~ ^-$PGID\|$PID\|[0-9]+\|$IDENTITY_OK$ ]] \
    || fail "happy launch returned malformed record $RECORD"
grep -Fxq -- "$RECORD" "$HSQA_PIDDIR/$EFFECTIVE_ROLE.pids" \
    || fail "happy record was not durable"
hsqa_stop_tracked "$EFFECTIVE_ROLE" "$RECORD" \
    || fail "happy exact process cleanup failed"
RECORD=''
hsqa_stop_owned_tmux_session "$SESSION_OK_ID" "$OWNER_OK" \
    || fail "happy exact session cleanup failed"

# Hostile Bash startup hooks, imported functions and PATH entries must be
# unable to run in either tmux's outer shell or the privileged tracked entry.
HOSTILE_BASH_ENV="$TEST_ROOT/tmux-hostile-bash-env"
HOSTILE_SENTINEL="$TEST_ROOT/tmux-hostile-sentinel"
HOSTILE_PIDFILE="$TEST_ROOT/tmux-hostile-fork.pid"
HOSTILE_FUNC_SENTINEL="$TEST_ROOT/tmux-hostile-function"
HOSTILE_PAYLOAD="$TEST_ROOT/tmux-hostile-payload"
HOSTILE_BIN="$TEST_ROOT/hostile-bin"
mkdir "$HOSTILE_BIN" || fail "could not create hostile PATH fixture"
printf '%s\n' \
    '#!/bin/sh' \
    '/usr/bin/touch "$HSQA_TMUX_HOSTILE_SENTINEL"' \
    'exit 97' > "$HOSTILE_BIN/tmux"
chmod 700 "$HOSTILE_BIN/tmux"
{
    /usr/bin/printf '/usr/bin/touch %q\n' "$HOSTILE_SENTINEL"
    /usr/bin/printf '%s\n' '/usr/bin/setsid /usr/bin/sleep 30 &'
    /usr/bin/printf '/bin/echo "$!" > %q\n' "$HOSTILE_PIDFILE"
} > "$HOSTILE_BASH_ENV"
(
    export BASH_ENV="$HOSTILE_BASH_ENV" ENV="$HOSTILE_BASH_ENV"
    PATH="$HOSTILE_BIN:$PATH"
    HOSTILE_FUNC_LITERAL=$(builtin printf '%q' "$HOSTILE_FUNC_SENTINEL")
    eval "sleep() { [ -z \"\${HSQA_PROCESS_IDENTITY:-}\" ] || /usr/bin/touch $HOSTILE_FUNC_LITERAL; /usr/bin/sleep \"\$@\"; }"
    eval "exec() { [ -z \"\${HSQA_PROCESS_IDENTITY:-}\" ] || /usr/bin/touch $HOSTILE_FUNC_LITERAL; builtin exec \"\$@\"; }"
    export -f sleep exec
    H_SESSION=''; H_WINDOW=''; H_PANE=''; H_PID=''; H_PGID=''; H_RECORD=''
    H_ROLE="tmux-hostile-$RUN_ID"; H_OWNER="tmux-owner-h-$RUN_ID"
    hsqa_create_owned_tmux_session "hsqa-tmux-hostile-$RUN_ID" "$H_OWNER" '' \
        H_SESSION || exit 41
    H_CONTROL=$(control_tuple_for_session "$H_SESSION") || exit 42
    hsqa_launch_tracked_tmux_window "$H_SESSION" "$H_OWNER" server \
        "$H_ROLE" "tmux-child-h-$RUN_ID" '' H_WINDOW H_PANE H_PID H_PGID \
        H_RECORD H_ROLE /bin/bash -c \
        'bad=$(env | grep -E "^(BASH_ENV|ENV|SHELLOPTS|BASHOPTS|BASH_FUNC_.*%%)=" || true); [ -z "$bad" ] || exit 2; printf "clean\n" > "$1"; sleep 30' \
        -- "$HOSTILE_PAYLOAD" || exit 43
    hsqa_stop_tracked "$H_ROLE" "$H_RECORD" || exit 44
    hsqa_stop_owned_tmux_session "$H_SESSION" "$H_OWNER" || exit 45
    IFS='|' read -r H_CONTROL_PID H_CONTROL_START _ <<< "$H_CONTROL"
    H_CURRENT=$(hsqa_process_starttime "$H_CONTROL_PID")
    [ -z "$H_CURRENT" ] || [ "$H_CURRENT" != "$H_CONTROL_START" ] || exit 46
) || fail "tmux clean environment rejected its exact workload"
if [ -f "$HOSTILE_PIDFILE" ]; then
    HOSTILE_PID=$(head -1 "$HOSTILE_PIDFILE" 2>/dev/null || true)
    [[ "$HOSTILE_PID" =~ ^[1-9][0-9]*$ ]] \
        && kill -9 "$HOSTILE_PID" 2>/dev/null || true
    fail "tmux outer shell sourced hostile BASH_ENV and forked"
fi
[ ! -e "$HOSTILE_SENTINEL" ] \
    || fail "tmux outer/entry boundary ran hostile startup or PATH code"
[ ! -e "$HOSTILE_FUNC_SENTINEL" ] \
    || fail "tmux boundary imported a hostile Bash function"
[ -f "$HOSTILE_PAYLOAD" ] || fail "clean tmux payload did not execute"

# A same-name unowned session is refused and preserved.
SESSION_FOREIGN="hsqa-tmux-foreign-$RUN_ID"
OWNER_FOREIGN="tmux-owner-f-$RUN_ID"
FOREIGN_CREATION=$(hsqa_tmux new-session -d -P \
    -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
    -s "$SESSION_FOREIGN" -n control 'sleep 30') \
    || fail "could not create foreign fixture session"
FOREIGN_ID=''; FOREIGN_WINDOW=''; FOREIGN_PANE=''; FOREIGN_PID=''
hsqa_parse_tmux_creation "$FOREIGN_CREATION" FOREIGN_ID FOREIGN_WINDOW \
    FOREIGN_PANE FOREIGN_PID || fail "foreign fixture IDs missing"
SESSIONS+=("$FOREIGN_ID")
IGNORED_SESSION_ID=''
if hsqa_create_owned_tmux_session "$SESSION_FOREIGN" "$OWNER_FOREIGN" '' \
        IGNORED_SESSION_ID; then
    fail "same-name foreign session was adopted"
fi
hsqa_tmux has-session -t "$FOREIGN_ID" 2>/dev/null \
    || fail "same-name foreign session was killed"
if hsqa_stop_owned_tmux_session "$FOREIGN_ID" "$OWNER_FOREIGN"; then
    fail "unowned session teardown was authorized"
fi
hsqa_tmux has-session -t "$FOREIGN_ID" 2>/dev/null \
    || fail "unowned session did not survive refusal"
hsqa_tmux kill-session -t "$FOREIGN_ID" || fail "fixture cleanup failed"

# Session names are not authority. After the original immutable session ID is
# gone, a foreign replacement using the same name must survive every old-ID
# operation.
SESSION_REPLACE="hsqa-tmux-replace-$RUN_ID"
OWNER_REPLACE="tmux-owner-r-$RUN_ID"
OLD_SESSION_ID=''
new_owned_session "$SESSION_REPLACE" "$OWNER_REPLACE" OLD_SESSION_ID
hsqa_stop_owned_tmux_session "$OLD_SESSION_ID" "$OWNER_REPLACE" \
    || fail "could not retire replacement fixture origin"
REPLACEMENT=$(hsqa_tmux new-session -d -P \
    -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
    -s "$SESSION_REPLACE" -n control 'sleep 30') \
    || fail "could not create same-name replacement"
REPLACEMENT_ID=''; REPLACEMENT_WINDOW=''; REPLACEMENT_PANE=''; REPLACEMENT_PID=''
hsqa_parse_tmux_creation "$REPLACEMENT" REPLACEMENT_ID REPLACEMENT_WINDOW \
    REPLACEMENT_PANE REPLACEMENT_PID || fail "replacement IDs missing"
REPLACEMENT_SENTINEL="$TEST_ROOT/replacement-payload"
OLD_WINDOW=''; OLD_PANE=''; OLD_PID=''; OLD_PGID=''; OLD_RECORD=''; OLD_ROLE="old-role-$RUN_ID"
if hsqa_launch_tracked_tmux_window "$OLD_SESSION_ID" "$OWNER_REPLACE" server \
        "$OLD_ROLE" "old-child-$RUN_ID" '' OLD_WINDOW OLD_PANE OLD_PID OLD_PGID \
        OLD_RECORD OLD_ROLE bash -c 'printf bad > "$1"' -- "$REPLACEMENT_SENTINEL"; then
    fail "dead immutable session ID targeted same-name replacement"
fi
hsqa_tmux has-session -t "$REPLACEMENT_ID" 2>/dev/null \
    || fail "same-name replacement was killed"
[ ! -e "$REPLACEMENT_SENTINEL" ] || fail "same-name replacement released payload"
hsqa_tmux kill-session -t "$REPLACEMENT_ID" || fail "replacement cleanup failed"

# A malformed tmux formatter result is recovered from the one exact marked
# immutable pane; neither session creation nor window creation may leak just
# because the human-readable command output was damaged.
eval "$(declare -f hsqa_tmux | sed '1s/hsqa_tmux/real_hsqa_tmux/')"
TMUX_OUTPUT_MUTANT=new-session
hsqa_tmux() {
    local operation="$1" output status
    shift
    if [ "$operation" = "$TMUX_OUTPUT_MUTANT" ]; then
        output=$(real_hsqa_tmux "$operation" "$@"); status=$?
        [ "$status" -eq 0 ] && printf 'malformed-output\n'
        return "$status"
    fi
    real_hsqa_tmux "$operation" "$@"
}
SESSION_FORMAT="hsqa-tmux-format-$RUN_ID"
OWNER_FORMAT="tmux-owner-m-$RUN_ID"
SESSION_FORMAT_ID=''
new_owned_session "$SESSION_FORMAT" "$OWNER_FORMAT" SESSION_FORMAT_ID
TMUX_OUTPUT_MUTANT=new-window
FORMAT_WINDOW=''; FORMAT_PANE=''; FORMAT_PID=''; FORMAT_PGID=''; FORMAT_RECORD=''
FORMAT_ROLE="tmux-format-$RUN_ID"; FORMAT_SENTINEL="$TEST_ROOT/format-payload"
hsqa_launch_tracked_tmux_window "$SESSION_FORMAT_ID" "$OWNER_FORMAT" server \
    "$FORMAT_ROLE" "tmux-format-child-$RUN_ID" '' FORMAT_WINDOW FORMAT_PANE FORMAT_PID \
    FORMAT_PGID FORMAT_RECORD FORMAT_ROLE bash -c \
    'printf payload > "$1"; sleep 30' -- "$FORMAT_SENTINEL" \
    || fail "malformed new-window output was not recovered"
wait_path "$FORMAT_SENTINEL" || fail "recovered formatter launch missed payload"
hsqa_stop_tracked "$FORMAT_ROLE" "$FORMAT_RECORD" \
    || fail "formatter record cleanup failed"
hsqa_stop_owned_tmux_session "$SESSION_FORMAT_ID" "$OWNER_FORMAT" \
    || fail "formatter session cleanup failed"
eval "$(declare -f real_hsqa_tmux | sed '1s/real_hsqa_tmux/hsqa_tmux/')"
unset -f real_hsqa_tmux

# Catchable signals during create and after durable window registration are
# deferred until exact cleanup completes. Neither control nor payload may be
# released/leaked, and the original 130/143 status is preserved.
TMUX_SIGNAL_SEEN=0
tmux_signal_handler() { TMUX_SIGNAL_SEEN="$1"; }
for TMUX_TEST_SIGNAL in INT TERM; do
    TMUX_EXPECTED=130; [ "$TMUX_TEST_SIGNAL" = TERM ] && TMUX_EXPECTED=143
    CREATE_READY="$TEST_ROOT/create-${TMUX_TEST_SIGNAL,,}.ready"
    CREATE_HOLD="$TEST_ROOT/create-${TMUX_TEST_SIGNAL,,}.hold"
    : > "$CREATE_HOLD"
    eval "$(declare -f hsqa_tmux | sed '1s/hsqa_tmux/real_hsqa_tmux/')"
    hsqa_tmux() {
        local operation="$1" output status
        shift
        if [ "$operation" = new-session ]; then
            output=$(real_hsqa_tmux "$operation" "$@"); status=$?
            printf ready > "$CREATE_READY"
            while [ -e "$CREATE_HOLD" ]; do /usr/bin/sleep 0.01; done
            printf '%s\n' "$output"
            return "$status"
        fi
        real_hsqa_tmux "$operation" "$@"
    }
    (
        wait_path "$CREATE_READY" || exit 1
        kill -"$TMUX_TEST_SIGNAL" $$ || exit 2
        rm -f -- "$CREATE_HOLD"
    ) & CREATE_SENDER=$!
    CREATE_SESSION=''; TMUX_SIGNAL_SEEN=0; CREATE_STATUS=0
    hsqa_create_owned_tmux_session \
        "hsqa-tmux-sigc-${TMUX_TEST_SIGNAL,,}-$RUN_ID" \
        "tmux-owner-sc-${TMUX_TEST_SIGNAL,,}-$RUN_ID" \
        tmux_signal_handler CREATE_SESSION || CREATE_STATUS=$?
    wait "$CREATE_SENDER" || fail "$TMUX_TEST_SIGNAL create sender failed"
    eval "$(declare -f real_hsqa_tmux | sed '1s/real_hsqa_tmux/hsqa_tmux/')"
    unset -f real_hsqa_tmux
    [ "$CREATE_STATUS" -eq "$TMUX_EXPECTED" ] \
        && [ "$TMUX_SIGNAL_SEEN" -eq "$TMUX_EXPECTED" ] \
        || fail "$TMUX_TEST_SIGNAL create signal status=$CREATE_STATUS seen=$TMUX_SIGNAL_SEEN"
    [ -z "$CREATE_SESSION" ] || fail "$TMUX_TEST_SIGNAL create returned session authority"
    rm -f -- "$CREATE_READY" "$CREATE_HOLD"
    trap - INT TERM

    LAUNCH_SESSION=''; LAUNCH_OWNER="tmux-owner-sl-${TMUX_TEST_SIGNAL,,}-$RUN_ID"
    new_owned_session "hsqa-tmux-sigl-${TMUX_TEST_SIGNAL,,}-$RUN_ID" \
        "$LAUNCH_OWNER" LAUNCH_SESSION
    LAUNCH_READY="$TEST_ROOT/launch-${TMUX_TEST_SIGNAL,,}.ready"
    LAUNCH_HOLD="$TEST_ROOT/launch-${TMUX_TEST_SIGNAL,,}.hold"
    LAUNCH_PAYLOAD="$TEST_ROOT/launch-${TMUX_TEST_SIGNAL,,}.payload"
    : > "$LAUNCH_HOLD"
    eval "$(declare -f register_pid | sed '1s/register_pid/real_register_pid/')"
    register_pid() {
        real_register_pid "$@" || return
        printf ready > "$LAUNCH_READY"
        while [ -e "$LAUNCH_HOLD" ]; do /usr/bin/sleep 0.01; done
    }
    (
        wait_path "$LAUNCH_READY" || exit 1
        kill -"$TMUX_TEST_SIGNAL" $$ || exit 2
        rm -f -- "$LAUNCH_HOLD"
    ) & LAUNCH_SENDER=$!
    L_WINDOW=''; L_PANE=''; L_PID=''; L_PGID=''; L_RECORD=''
    L_ROLE="tmux-sigl-${TMUX_TEST_SIGNAL,,}-$RUN_ID"
    TMUX_SIGNAL_SEEN=0; LAUNCH_STATUS=0
    hsqa_launch_tracked_tmux_window "$LAUNCH_SESSION" "$LAUNCH_OWNER" server \
        "$L_ROLE" "tmux-child-sl-${TMUX_TEST_SIGNAL,,}-$RUN_ID" \
        tmux_signal_handler L_WINDOW L_PANE L_PID L_PGID L_RECORD L_ROLE \
        bash -c 'printf payload > "$1"; sleep 30' -- "$LAUNCH_PAYLOAD" \
        || LAUNCH_STATUS=$?
    wait "$LAUNCH_SENDER" || fail "$TMUX_TEST_SIGNAL launch sender failed"
    eval "$(declare -f real_register_pid | sed '1s/real_register_pid/register_pid/')"
    unset -f real_register_pid
    [ "$LAUNCH_STATUS" -eq "$TMUX_EXPECTED" ] \
        && [ "$TMUX_SIGNAL_SEEN" -eq "$TMUX_EXPECTED" ] \
        || fail "$TMUX_TEST_SIGNAL launch signal status=$LAUNCH_STATUS seen=$TMUX_SIGNAL_SEEN"
    [ ! -e "$LAUNCH_PAYLOAD" ] || fail "$TMUX_TEST_SIGNAL launch released payload"
    [ -z "$L_RECORD" ] || fail "$TMUX_TEST_SIGNAL launch retained cleaned record"
    hsqa_stop_owned_tmux_session "$LAUNCH_SESSION" "$LAUNCH_OWNER" \
        || fail "$TMUX_TEST_SIGNAL launch control cleanup failed"
    rm -f -- "$LAUNCH_READY" "$LAUNCH_HOLD" "$LAUNCH_PAYLOAD"
    trap - INT TERM
done

# Once the payload has been released, the restored caller handler owns exact
# record-first teardown. Exercise both catchable signals in a separate shell so
# the observed process status is the real 130/143, not a copied variable.
for POST_SIGNAL in INT TERM; do
    POST_EXPECTED=130; [ "$POST_SIGNAL" = TERM ] && POST_EXPECTED=143
    POST_PAYLOAD="$TEST_ROOT/post-${POST_SIGNAL,,}.payload"
    POST_AUTHORITY="$TEST_ROOT/post-${POST_SIGNAL,,}.authority"
    POST_CLEANUP="$TEST_ROOT/post-${POST_SIGNAL,,}.cleanup"
    /usr/bin/env --default-signal=INT,TERM \
        /bin/bash --noprofile --norc -p -c '
        set -u
        here="$1"; suffix="$2"; payload="$3"; authority="$4"; cleaned="$5"
        . "$here/lib_harness.sh"
        . "$here/lib_tmux_harness.sh"
        POST_SESSION=""; POST_WINDOW=""; POST_PANE=""; POST_PID=""
        POST_PGID=""; POST_RECORD=""; POST_EFFECTIVE_ROLE=""
        owner="tmux-owner-post-$suffix"; role="tmux-post-$suffix"
        control_pid=""; control_start=""; control_identity=""
        post_handler() {
            local signal_status="$1" cleanup_status=0 topology extra
            trap - EXIT INT TERM
            hsqa_stop_tracked "$POST_EFFECTIVE_ROLE" "$POST_RECORD" || cleanup_status=1
            POST_RECORD=""
            hsqa_stop_owned_tmux_session "$POST_SESSION" "$owner" || cleanup_status=1
            printf "%s\n" "$cleanup_status" > "$cleaned"
            exit "$signal_status"
        }
        hsqa_create_owned_tmux_session "hsqa-tmux-post-$suffix" "$owner" \
            post_handler POST_SESSION || exit 41
        control_identity=$(hsqa_tmux show-options -v -t "$POST_SESSION" \
            @hsqa_control_identity 2>/dev/null) || exit 42
        topology=$(hsqa_tmux list-panes -t "$POST_SESSION:control" \
            -F "#{pane_id}|#{pane_pid}" 2>/dev/null) || exit 43
        IFS="|" read -r _ control_pid extra <<< "$topology"
        [ -z "${extra:-}" ] && [[ "$control_pid" =~ ^[1-9][0-9]*$ ]] || exit 44
        control_start=$(hsqa_process_starttime "$control_pid")
        [[ "$control_start" =~ ^[0-9]+$ ]] || exit 45
        hsqa_launch_tracked_tmux_window "$POST_SESSION" "$owner" server "$role" \
            "tmux-child-post-$suffix" post_handler POST_WINDOW POST_PANE \
            POST_PID POST_PGID POST_RECORD POST_EFFECTIVE_ROLE \
            /bin/bash -c '\''printf payload > "$1"; while :; do /usr/bin/sleep 1; done'\'' \
            -- "$payload" || exit 46
        printf "%s|%s|%s|%s|%s|%s|%s\n" "$POST_SESSION" "$control_pid" \
            "$control_start" "$control_identity" "$POST_PGID" \
            "$POST_EFFECTIVE_ROLE" "$POST_RECORD" \
            > "$authority"
        while :; do /usr/bin/sleep 1; done
    ' -- "$HERE" "${POST_SIGNAL,,}-$RUN_ID" "$POST_PAYLOAD" \
        "$POST_AUTHORITY" "$POST_CLEANUP" \
        > "$TEST_ROOT/post-${POST_SIGNAL,,}.out" 2>&1 &
    POST_PARENT=$!
    if ! wait_path "$POST_PAYLOAD"; then
        sed -n '1,120p' "$TEST_ROOT/post-${POST_SIGNAL,,}.out" >&2 || true
        fail "$POST_SIGNAL post-release payload never ran"
    fi
    wait_path "$POST_AUTHORITY" || fail "$POST_SIGNAL post-release authority missing"
    kill -"$POST_SIGNAL" "$POST_PARENT" \
        || fail "could not signal $POST_SIGNAL post-release launcher"
    POST_STATUS=0
    wait "$POST_PARENT" || POST_STATUS=$?
    [ "$POST_STATUS" -eq "$POST_EXPECTED" ] \
        || fail "$POST_SIGNAL post-release returned $POST_STATUS instead of $POST_EXPECTED"
    [ "$(cat "$POST_CLEANUP" 2>/dev/null)" = 0 ] \
        || fail "$POST_SIGNAL post-release cleanup was not exact"
    IFS='|' read -r POST_SESSION POST_CONTROL_PID POST_CONTROL_START \
        POST_CONTROL_ID POST_PGID POST_ROLE POST_RECORD < "$POST_AUTHORITY"
    wait_group_absent "$POST_PGID" \
        || fail "$POST_SIGNAL post-release left tracked group $POST_PGID"
    POST_CURRENT=$(hsqa_process_starttime "$POST_CONTROL_PID")
    [ -z "$POST_CURRENT" ] || [ "$POST_CURRENT" != "$POST_CONTROL_START" ] \
        || fail "$POST_SIGNAL post-release left control process"
    [ ! -s "$HSQA_PIDDIR/$POST_ROLE.pids" ] \
        || fail "$POST_SIGNAL post-release left recovery record"
    CONTROL_TUPLES+=("$POST_CONTROL_PID|$POST_CONTROL_START|$POST_CONTROL_ID")
done

# A tmux client may return nonzero after the server committed creation. The
# immutable before/after diff must recover and retire that barrier pane; the
# payload is never released and no session/window is adopted by name.
eval "$(declare -f hsqa_tmux | sed '1s/hsqa_tmux/real_hsqa_tmux/')"
TMUX_NONZERO_MUTANT=new-session
hsqa_tmux() {
    local operation="$1" output status
    shift
    if [ "$operation" = "$TMUX_NONZERO_MUTANT" ]; then
        output=$(real_hsqa_tmux "$operation" "$@"); status=$?
        printf '%s\n' "$output"
        [ "$status" -eq 0 ] && return 7
        return "$status"
    fi
    real_hsqa_tmux "$operation" "$@"
}
NZ_SESSION_NAME="hsqa-tmux-nonzero-$RUN_ID"; NZ_OWNER="tmux-owner-nz-$RUN_ID"
NZ_SESSION=''
if hsqa_create_owned_tmux_session "$NZ_SESSION_NAME" "$NZ_OWNER" '' NZ_SESSION; then
    fail "nonzero-after-session-create returned success"
fi
[ -z "$NZ_SESSION" ] || fail "nonzero session create returned authority"
NZ_PRESENT=$(real_hsqa_tmux list-sessions -F '#{session_name}' 2>/dev/null || true)
printf '%s\n' "$NZ_PRESENT" | grep -Fxq "$NZ_SESSION_NAME" \
    && fail "nonzero session create leaked its control pane"

TMUX_NONZERO_MUTANT=none
NZ_BASE_SESSION=''
hsqa_create_owned_tmux_session "hsqa-tmux-nzbase-$RUN_ID" "$NZ_OWNER" '' \
    NZ_BASE_SESSION || fail "could not create nonzero-window base session"
NZ_CONTROL=$(control_tuple_for_session "$NZ_BASE_SESSION") \
    || fail "nonzero-window control tuple missing"
CONTROL_TUPLES+=("$NZ_CONTROL")
TMUX_NONZERO_MUTANT=new-window
NZ_WINDOW=''; NZ_PANE=''; NZ_PID=''; NZ_PGID=''; NZ_RECORD=''
NZ_ROLE="tmux-nz-$RUN_ID"; NZ_SENTINEL="$TEST_ROOT/nonzero-window-payload"
if hsqa_launch_tracked_tmux_window "$NZ_BASE_SESSION" "$NZ_OWNER" server \
        "$NZ_ROLE" "tmux-child-nz-$RUN_ID" '' NZ_WINDOW NZ_PANE NZ_PID \
        NZ_PGID NZ_RECORD NZ_ROLE bash -c \
        'printf payload > "$1"; sleep 30' -- "$NZ_SENTINEL"; then
    fail "nonzero-after-window-create returned success"
fi
[ ! -e "$NZ_SENTINEL" ] || fail "nonzero window create released payload"
[ -z "$NZ_RECORD" ] || fail "nonzero window create retained a cleaned record"
TMUX_NONZERO_MUTANT=none
hsqa_stop_owned_tmux_session "$NZ_BASE_SESSION" "$NZ_OWNER" \
    || fail "nonzero-window base control cleanup failed"
eval "$(declare -f real_hsqa_tmux | sed '1s/real_hsqa_tmux/hsqa_tmux/')"
unset -f real_hsqa_tmux

# One owned session may carry multiple independently registered windows. The
# per-window identity registry keeps the second launch valid without weakening
# teardown authorization for either group.
SESSION_MULTI="hsqa-tmux-multi-$RUN_ID"
OWNER_MULTI="tmux-owner-u-$RUN_ID"
SESSION_MULTI_ID=''
new_owned_session "$SESSION_MULTI" "$OWNER_MULTI" SESSION_MULTI_ID
MA_WINDOW=''; MA_PANE=''; MA_PID=''; MA_PGID=''; MA_RECORD=''; MA_ROLE="tmux-ma-$RUN_ID"
MB_WINDOW=''; MB_PANE=''; MB_PID=''; MB_PGID=''; MB_RECORD=''; MB_ROLE="tmux-mb-$RUN_ID"
hsqa_launch_tracked_tmux_window "$SESSION_MULTI_ID" "$OWNER_MULTI" server-a \
    "$MA_ROLE" "tmux-child-ma-$RUN_ID" '' MA_WINDOW MA_PANE MA_PID MA_PGID \
    MA_RECORD MA_ROLE sleep 30 || fail "first multi-window launch failed"
hsqa_launch_tracked_tmux_window "$SESSION_MULTI_ID" "$OWNER_MULTI" server-b \
    "$MB_ROLE" "tmux-child-mb-$RUN_ID" '' MB_WINDOW MB_PANE MB_PID MB_PGID \
    MB_RECORD MB_ROLE sleep 30 || fail "second multi-window launch failed"
hsqa_tmux_session_owned "$SESSION_MULTI_ID" "$OWNER_MULTI" \
    || fail "two exact owned windows invalidated their session"
hsqa_stop_tracked "$MA_ROLE" "$MA_RECORD" || fail "multi-window A cleanup failed"
hsqa_stop_tracked "$MB_ROLE" "$MB_RECORD" || fail "multi-window B cleanup failed"
hsqa_stop_owned_tmux_session "$SESSION_MULTI_ID" "$OWNER_MULTI" \
    || fail "multi-window session cleanup failed"

# Duplicate names do not redirect an immutable window target. An unregistered
# duplicate makes whole-session deletion fail closed while the original exact
# workload remains untouched.
SESSION_DUP="hsqa-tmux-dup-$RUN_ID"; OWNER_DUP="tmux-owner-q-$RUN_ID"
SESSION_DUP_ID=''; new_owned_session "$SESSION_DUP" "$OWNER_DUP" SESSION_DUP_ID
DUP_WINDOW=''; DUP_PANE=''; DUP_PID=''; DUP_PGID=''; DUP_RECORD=''; DUP_ROLE="tmux-dup-$RUN_ID"
hsqa_launch_tracked_tmux_window "$SESSION_DUP_ID" "$OWNER_DUP" server \
    "$DUP_ROLE" "tmux-child-q-$RUN_ID" '' DUP_WINDOW DUP_PANE DUP_PID DUP_PGID \
    DUP_RECORD DUP_ROLE sleep 30 || fail "duplicate fixture launch failed"
DUP_FOREIGN=$(hsqa_tmux new-window -d -P \
    -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
    -t "$SESSION_DUP_ID" -n server 'sleep 30') || fail "duplicate window fixture failed"
DF_SESSION=''; DF_WINDOW=''; DF_PANE=''; DF_PID=''
hsqa_parse_tmux_creation "$DUP_FOREIGN" DF_SESSION DF_WINDOW DF_PANE DF_PID \
    || fail "duplicate foreign IDs missing"
[ "$DF_WINDOW" != "$DUP_WINDOW" ] || fail "duplicate name changed immutable ID"
if hsqa_tmux_session_owned "$SESSION_DUP_ID" "$OWNER_DUP"; then
    fail "unregistered duplicate window was authorized"
fi
kill -0 "$DUP_PID" 2>/dev/null || fail "duplicate refusal killed original pane"
kill -0 "$DF_PID" 2>/dev/null || fail "duplicate refusal killed foreign pane"
hsqa_stop_tracked "$DUP_ROLE" "$DUP_RECORD" || fail "duplicate original cleanup failed"
hsqa_tmux kill-window -t "$DF_WINDOW" || fail "duplicate fixture cleanup failed"
hsqa_stop_owned_tmux_session "$SESSION_DUP_ID" "$OWNER_DUP" \
    || fail "duplicate session did not recover after fixture removal"

# A split pane violates the exact one-pane topology. Session teardown refuses
# it and preserves both panes; only the test's isolated socket may remove the
# deliberately foreign split afterwards.
SESSION_SPLIT="hsqa-tmux-split-$RUN_ID"; OWNER_SPLIT="tmux-owner-s-$RUN_ID"
SESSION_SPLIT_ID=''; new_owned_session "$SESSION_SPLIT" "$OWNER_SPLIT" SESSION_SPLIT_ID
SPLIT_WINDOW=''; SPLIT_PANE=''; SPLIT_PID=''; SPLIT_PGID=''; SPLIT_RECORD=''; SPLIT_ROLE="tmux-split-$RUN_ID"
hsqa_launch_tracked_tmux_window "$SESSION_SPLIT_ID" "$OWNER_SPLIT" server \
    "$SPLIT_ROLE" "tmux-child-s-$RUN_ID" '' SPLIT_WINDOW SPLIT_PANE SPLIT_PID SPLIT_PGID \
    SPLIT_RECORD SPLIT_ROLE sleep 30 || fail "split fixture launch failed"
SPLIT_FOREIGN=$(hsqa_tmux split-window -d -P \
    -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
    -t "$SPLIT_WINDOW" 'sleep 30') || fail "split pane fixture failed"
SF_SESSION=''; SF_WINDOW=''; SF_PANE=''; SF_PID=''
hsqa_parse_tmux_creation "$SPLIT_FOREIGN" SF_SESSION SF_WINDOW SF_PANE SF_PID \
    || fail "split pane IDs missing"
[ "$SF_WINDOW" = "$SPLIT_WINDOW" ] || fail "split escaped exact window"
if hsqa_stop_owned_tmux_session "$SESSION_SPLIT_ID" "$OWNER_SPLIT" \
        2>"$TEST_ROOT/split-refusal.stderr"; then
    fail "split session teardown was authorized"
fi
kill -0 "$SPLIT_PID" 2>/dev/null || fail "split refusal killed tracked pane"
kill -0 "$SF_PID" 2>/dev/null || fail "split refusal killed foreign pane"
hsqa_stop_tracked "$SPLIT_ROLE" "$SPLIT_RECORD" \
    || fail "split tracked record cleanup failed"
hsqa_tmux kill-window -t "$SPLIT_WINDOW" 2>/dev/null || true
hsqa_stop_owned_tmux_session "$SESSION_SPLIT_ID" "$OWNER_SPLIT" \
    || fail "split control session cleanup failed"

# Inject a foreign split in the exact race between the final ownership check
# and kill-pane. Production must kill only the immutable control pane, preserve
# the intruder and return red because the session remains.
SESSION_RACE="hsqa-tmux-race-$RUN_ID"; OWNER_RACE="tmux-owner-race-$RUN_ID"
SESSION_RACE_ID=''; new_owned_session "$SESSION_RACE" "$OWNER_RACE" SESSION_RACE_ID
eval "$(declare -f hsqa_tmux | sed '1s/hsqa_tmux/real_hsqa_tmux/')"
RACE_INJECT=1; RACE_FOREIGN=''
hsqa_tmux() {
    local operation="$1" target
    if [ "$operation" = kill-pane ] && [ "$RACE_INJECT" -eq 1 ]; then
        target="$3"
        RACE_FOREIGN=$(real_hsqa_tmux split-window -d -P \
            -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
            -t "$target" 'sleep 30') || return 2
        RACE_INJECT=0
    fi
    real_hsqa_tmux "$@"
}
if hsqa_stop_owned_tmux_session "$SESSION_RACE_ID" "$OWNER_RACE"; then
    fail "kill-boundary intrusion was reported clean"
fi
RF_SESSION=''; RF_WINDOW=''; RF_PANE=''; RF_PID=''
hsqa_parse_tmux_creation "$RACE_FOREIGN" RF_SESSION RF_WINDOW RF_PANE RF_PID \
    || fail "kill-boundary foreign pane IDs missing"
kill -0 "$RF_PID" 2>/dev/null \
    || fail "exact control-pane cleanup killed the concurrent foreign pane"
eval "$(declare -f real_hsqa_tmux | sed '1s/real_hsqa_tmux/hsqa_tmux/')"
unset -f real_hsqa_tmux
hsqa_tmux kill-pane -t "$RF_PANE" || fail "race fixture cleanup failed"

# A live canonical socket that cannot answer an authoritative query is an
# observer error, never proof of absence. Exact teardown must return red and
# preserve the owned process until observability is restored.
SESSION_QUERY="hsqa-tmux-query-$RUN_ID"; OWNER_QUERY="tmux-owner-query-$RUN_ID"
SESSION_QUERY_ID=''; new_owned_session "$SESSION_QUERY" "$OWNER_QUERY" SESSION_QUERY_ID
QUERY_CONTROL=$(control_tuple_for_session "$SESSION_QUERY_ID") \
    || fail "query-error control tuple missing"
IFS='|' read -r QUERY_PID QUERY_START QUERY_IDENTITY <<< "$QUERY_CONTROL"
eval "$(declare -f hsqa_tmux | sed '1s/hsqa_tmux/real_hsqa_tmux/')"
hsqa_tmux() {
    [ "$1" = list-sessions ] && return 73
    real_hsqa_tmux "$@"
}
if hsqa_stop_owned_tmux_session "$SESSION_QUERY_ID" "$OWNER_QUERY"; then
    fail "tmux query error was accepted as clean absence"
fi
kill -0 "$QUERY_PID" 2>/dev/null \
    || fail "query-error refusal killed the owned control process"
eval "$(declare -f real_hsqa_tmux | sed '1s/real_hsqa_tmux/hsqa_tmux/')"
unset -f real_hsqa_tmux
hsqa_stop_owned_tmux_session "$SESSION_QUERY_ID" "$OWNER_QUERY" \
    || fail "query-error session did not clean after observability returned"

# Pane disappearance and process reaping are separate observations. Simulate a
# leader that remains visible for several polls after tmux has dropped the pane;
# success is allowed only after both immutable authorities are absent.
SESSION_DELAY="hsqa-tmux-delay-$RUN_ID"; OWNER_DELAY="tmux-owner-delay-$RUN_ID"
SESSION_DELAY_ID=''; new_owned_session "$SESSION_DELAY" "$OWNER_DELAY" SESSION_DELAY_ID
DELAY_TOPOLOGY=$(hsqa_tmux list-panes -t "$SESSION_DELAY_ID:control" \
    -F '#{pane_id}|#{pane_pid}' 2>/dev/null) || fail "delayed-reap topology missing"
IFS='|' read -r DELAY_PANE DELAY_PID DELAY_EXTRA <<< "$DELAY_TOPOLOGY"
[ -z "${DELAY_EXTRA:-}" ] || fail "delayed-reap topology malformed"
DELAY_IDENTITY=$(hsqa_tmux show-options -v -t "$SESSION_DELAY_ID" \
    @hsqa_control_identity 2>/dev/null) || fail "delayed-reap identity missing"
DELAY_START=$(hsqa_process_starttime "$DELAY_PID")
[[ "$DELAY_START" =~ ^[0-9]+$ ]] || fail "delayed-reap starttime missing"
DELAY_COUNTER="$TEST_ROOT/delayed-reap.count"; printf '0\n' > "$DELAY_COUNTER"
DELAY_ACTIVE=0
eval "$(declare -f hsqa_tmux | sed '1s/hsqa_tmux/real_hsqa_tmux/')"
eval "$(declare -f hsqa_process_starttime | sed '1s/hsqa_process_starttime/real_hsqa_process_starttime/')"
hsqa_tmux() {
    if [ "$1" = kill-pane ] && [ "$3" = "$DELAY_PANE" ]; then
        DELAY_ACTIVE=1
    fi
    real_hsqa_tmux "$@"
}
hsqa_process_starttime() {
    local requested="$1" count
    if [ "$requested" = "$DELAY_PID" ] && [ "$DELAY_ACTIVE" -eq 1 ]; then
        count=$(cat "$DELAY_COUNTER")
        if [ "$count" -lt 7 ]; then
            printf '%s\n' "$((count + 1))" > "$DELAY_COUNTER"
            printf '%s\n' "$DELAY_START"
            return 0
        fi
    fi
    real_hsqa_process_starttime "$requested"
}
hsqa_kill_exact_tmux_pane "$DELAY_PANE" "$DELAY_PID" "$DELAY_START" \
    "$DELAY_IDENTITY" || fail "delayed leader reap was reported clean too early/red forever"
[ "$(cat "$DELAY_COUNTER")" -ge 7 ] \
    || fail "delayed-reap path did not wait for process absence"
eval "$(declare -f real_hsqa_tmux | sed '1s/real_hsqa_tmux/hsqa_tmux/')"
eval "$(declare -f real_hsqa_process_starttime | sed '1s/real_hsqa_process_starttime/hsqa_process_starttime/')"
unset -f real_hsqa_tmux real_hsqa_process_starttime
hsqa_tmux_exact_session_present "$SESSION_DELAY_ID"
[ "$?" -eq 1 ] || fail "delayed-reap session remained after exact pane exit"

# Registration failure: the tracked entry is killed before payload release,
# the exact tmux window disappears, and no recovery record is lost/forged.
SESSION_FAIL="hsqa-tmux-fail-$RUN_ID"
OWNER_FAIL="tmux-owner-x-$RUN_ID"
ROLE_FAIL="tmux-fail-$RUN_ID"
IDENTITY_FAIL="tmux-child-x-$RUN_ID"
SENTINEL_FAIL="$TEST_ROOT/register-fail-payload"
SESSION_FAIL_ID=''
new_owned_session "$SESSION_FAIL" "$OWNER_FAIL" SESSION_FAIL_ID
eval "$(declare -f register_pid | sed '1s/register_pid/real_register_pid/')"
register_pid() { return 2; }
WINDOW_ID=''; PANE_ID=''; PID=''; PGID=''; RECORD=''; EFFECTIVE_ROLE="$ROLE_FAIL"
STATUS=0
hsqa_launch_tracked_tmux_window "$SESSION_FAIL_ID" "$OWNER_FAIL" server \
    "$ROLE_FAIL" "$IDENTITY_FAIL" '' WINDOW_ID PANE_ID PID PGID RECORD EFFECTIVE_ROLE \
    bash -c 'printf payload > "$1"; sleep 30' -- "$SENTINEL_FAIL" \
    || STATUS=$?
[ "$STATUS" -ne 0 ] || fail "forced registration failure returned success"
[ ! -e "$SENTINEL_FAIL" ] || fail "registration failure released payload"
[ -z "$RECORD" ] || fail "registration failure returned a record"
if [[ "$PGID" =~ ^[1-9][0-9]*$ ]]; then
    wait_group_absent "$PGID" || fail "registration failure leaked group $PGID"
fi
hsqa_tmux list-windows -t "$SESSION_FAIL_ID" -F '#W' 2>/dev/null \
    | grep -Fxq server && fail "registration failure left server window"
eval "$(declare -f real_register_pid | sed '1s/real_register_pid/register_pid/')"
unset -f real_register_pid
hsqa_stop_owned_tmux_session "$SESSION_FAIL_ID" "$OWNER_FAIL" \
    || fail "registration-failure control session cleanup failed"

# Parent SIGKILL before release: the entry notices immutable parent death,
# never executes the payload, and the durable record stays available until
# the observing test proves group absence and unregisters it.
SESSION_DEATH="hsqa-tmux-death-$RUN_ID"
OWNER_DEATH="tmux-owner-d-$RUN_ID"
ROLE_DEATH="tmux-death-$RUN_ID"
IDENTITY_DEATH="tmux-child-d-$RUN_ID"
READY_DEATH="$TEST_ROOT/parent-ready"
HOLD_DEATH="$TEST_ROOT/parent-hold"
SENTINEL_DEATH="$TEST_ROOT/parent-death-payload"
touch "$HOLD_DEATH"
SESSION_DEATH_ID=''
new_owned_session "$SESSION_DEATH" "$OWNER_DEATH" SESSION_DEATH_ID
bash -c '
    set -u
    here="$1"; session="$2"; owner="$3"; role="$4"; identity="$5"
    ready="$6"; hold="$7"; sentinel="$8"
    . "$here/lib_harness.sh"
    . "$here/lib_tmux_harness.sh"
    eval "$(declare -f register_pid | sed '\''1s/register_pid/parent_real_register_pid/'\'')"
    register_pid() {
        parent_real_register_pid "$@" || return
        printf ready > "$ready"
        while [ -e "$hold" ]; do sleep 0.01; done
    }
    child_window=""; child_pane=""; child_pid=""; child_pgid=""; child_record=""; child_role="$role"
    hsqa_launch_tracked_tmux_window "$session" "$owner" server \
        "$role" "$identity" "" child_window child_pane child_pid child_pgid \
        child_record child_role bash -c \
        '\''printf payload > "$1"; sleep 30'\'' -- "$sentinel"
' -- "$HERE" "$SESSION_DEATH_ID" "$OWNER_DEATH" "$ROLE_DEATH" \
    "$IDENTITY_DEATH" "$READY_DEATH" "$HOLD_DEATH" "$SENTINEL_DEATH" \
    >/dev/null 2>"$TEST_ROOT/parent-death.stderr" &
LAUNCH_PARENT=$!
wait_path "$READY_DEATH" || fail "parent-death launch never reached durable barrier"
RECORD_DEATH=$(head -1 "$HSQA_PIDDIR/$ROLE_DEATH.pids" 2>/dev/null || true)
[[ "$RECORD_DEATH" =~ ^-([1-9][0-9]*)\|[1-9][0-9]*\|[0-9]+\|$IDENTITY_DEATH$ ]] \
    || fail "parent-death record was not durable"
PGID_DEATH="${BASH_REMATCH[1]}"
IFS='|' read -r _ _ START_DEATH _ <<< "$RECORD_DEATH"
CREATION_DEATH=$(hsqa_tmux display-message -p -t "$SESSION_DEATH_ID:server" \
    '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' 2>/dev/null || true)
DEATH_SESSION=''; DEATH_WINDOW=''; DEATH_PANE=''; DEATH_PID=''
hsqa_parse_tmux_creation "$CREATION_DEATH" DEATH_SESSION DEATH_WINDOW \
    DEATH_PANE DEATH_PID \
    || fail "parent-death immutable tmux IDs missing"
[ "$DEATH_SESSION" = "$SESSION_DEATH_ID" ] || fail "parent-death session changed"
[ "$DEATH_PID" = "$PGID_DEATH" ] || fail "parent-death pane/group mismatch"
kill -9 "$LAUNCH_PARENT" 2>/dev/null || fail "could not SIGKILL launcher parent"
wait "$LAUNCH_PARENT" 2>/dev/null || true
rm -f -- "$HOLD_DEATH"
wait_group_absent "$PGID_DEATH" || fail "entry survived launcher parent death"
[ ! -e "$SENTINEL_DEATH" ] || fail "parent death released payload"
hsqa_stop_tracked "$ROLE_DEATH" "$RECORD_DEATH" \
    || fail "parent-death durable record could not be retired"
hsqa_kill_exact_tmux_pane "$DEATH_PANE" "$DEATH_PID" "$START_DEATH" \
    "$IDENTITY_DEATH" \
    || fail "parent-death empty window cleanup failed"
hsqa_stop_owned_tmux_session "$SESSION_DEATH_ID" "$OWNER_DEATH" \
    || fail "parent-death control session cleanup failed"

# Two distinct sessions/roles may run concurrently without records, identity
# scans or teardown from one touching the other.
parallel_case() {
    local suffix="$1" session="hsqa-tmux-p${1}-$RUN_ID"
    local owner="tmux-owner-p${1}-$RUN_ID" role="tmux-role-p${1}-$RUN_ID"
    local identity="tmux-child-p${1}-$RUN_ID" sentinel="$TEST_ROOT/p${1}" P_SESSION=''
    local P_WINDOW='' P_PANE='' P_PID='' P_PGID='' P_RECORD='' P_ROLE="$role"
    local control_tuple control_pid control_start control_identity extra current
    hsqa_create_owned_tmux_session "$session" "$owner" '' P_SESSION || return 31
    control_tuple=$(control_tuple_for_session "$P_SESSION") || return 36
    hsqa_launch_tracked_tmux_window "$P_SESSION" "$owner" server "$role" \
        "$identity" '' P_WINDOW P_PANE P_PID P_PGID P_RECORD P_ROLE bash -c \
        'printf payload > "$1"; sleep 30' -- "$sentinel" || return 32
    wait_path "$sentinel" || return 33
    hsqa_stop_tracked "$P_ROLE" "$P_RECORD" || return 34
    hsqa_stop_owned_tmux_session "$P_SESSION" "$owner" || return 35
    IFS='|' read -r control_pid control_start control_identity extra \
        <<< "$control_tuple"
    [ -z "${extra:-}" ] || return 37
    current=$(hsqa_process_starttime "$control_pid")
    [ -z "$current" ] || [ "$current" != "$control_start" ] || return 38
}
parallel_case a & PA=$!
parallel_case b & PB=$!
PA_STATUS=0; PB_STATUS=0
wait "$PA" || PA_STATUS=$?
wait "$PB" || PB_STATUS=$?
[ "$PA_STATUS" -eq 0 ] || fail "parallel tmux case a failed ($PA_STATUS)"
[ "$PB_STATUS" -eq 0 ] || fail "parallel tmux case b failed ($PB_STATUS)"

# Final stale-socket cleanup is also an authority boundary.  Replace the exact
# listener-free socket after its final observation with a different same-UID,
# live Unix listener.  Cleanup must return red, atomically restore/preserve the
# foreign socket inode and leave its listener alive; only the later explicit
# fixture shutdown may make that socket eligible for exact removal.
SOCKET_FOREIGN="$TEST_ROOT/foreign-live.sock"
SOCKET_FOREIGN_READY="$TEST_ROOT/foreign-live.ready"
SOCKET_FINALIZE_READY="$TEST_ROOT/socket-finalize-ready"
SOCKET_FINALIZE_HOLD="$TEST_ROOT/socket-finalize-hold"
/usr/bin/python3 -I - "$HSQA_TMUX_SOCKET" <<'PYEOF' \
    || fail "could not seed listener-free tmux socket"
import os, socket, sys
path = sys.argv[1]
try:
    os.unlink(path)
except FileNotFoundError:
    pass
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
server.close()
PYEOF
/usr/bin/python3 -I - "$SOCKET_FOREIGN" "$SOCKET_FOREIGN_READY" <<'PYEOF' &
import os, socket, sys
path, ready = sys.argv[1:]
try:
    os.unlink(path)
except FileNotFoundError:
    pass
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(path)
server.listen(4)
with open(ready, "x", encoding="ascii") as handle:
    handle.write("ready\n")
while True:
    client, _ = server.accept()
    client.close()
PYEOF
SOCKET_EXTERNAL_PID=$!
wait_path "$SOCKET_FOREIGN_READY" \
    || fail "foreign Unix listener did not become ready"
: > "$SOCKET_FINALIZE_HOLD" \
    || fail "could not create socket-finalize hold"
(
    wait_path "$SOCKET_FINALIZE_READY" || exit 1
    mv -f -- "$SOCKET_FOREIGN" "$HSQA_TMUX_SOCKET" || exit 2
    rm -f -- "$SOCKET_FINALIZE_HOLD" || exit 3
) & SOCKET_SWAP_WORKER=$!
if hsqa_finalize_empty_tmux_socket; then
    fail "foreign live socket replacement was finalized clean"
fi
wait "$SOCKET_SWAP_WORKER" || fail "socket replacement worker failed"
kill -0 "$SOCKET_EXTERNAL_PID" 2>/dev/null \
    || fail "socket finalizer killed the foreign listener"
[ -S "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] \
    || fail "foreign socket inode was not restored at the canonical path"
/usr/bin/python3 -I - "$HSQA_TMUX_SOCKET" <<'PYEOF' \
    || fail "restored foreign Unix socket is no longer live"
import socket, sys
client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
client.settimeout(1)
client.connect(sys.argv[1])
client.close()
PYEOF
find "$TEST_ROOT" -maxdepth 1 -name '.tmux.sock.empty-*' -print \
    | grep -q . && fail "foreign socket replacement leaked a quarantine entry after restore"
kill -9 "$SOCKET_EXTERNAL_PID" 2>/dev/null || true
wait "$SOCKET_EXTERNAL_PID" 2>/dev/null || true
SOCKET_EXTERNAL_PID=''
hsqa_finalize_empty_tmux_socket \
    || fail "listener-free restored socket did not finalize after owner exit"
[ ! -e "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] \
    || fail "restored stale socket survived exact finalization"
rm -f -- "$SOCKET_FOREIGN_READY" "$SOCKET_FINALIZE_READY"

assert_isolated_tmux_empty() {
    local sessions cmdline attempt
    sessions=$(hsqa_snapshot_tmux_sessions) || return 1
    [ -z "$sessions" ] || return 1
    [ ! -e "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] || return 1
    for attempt in $(seq 1 50); do
        cmdline=''
        for proc in /proc/[0-9]*/cmdline; do
            [ -r "$proc" ] || continue
            cmdline=$(tr '\0' ' ' < "$proc" 2>/dev/null || true)
            case "$cmdline" in
                *'/usr/bin/tmux -f /dev/null -S '"$HSQA_TMUX_SOCKET"*) break;;
                *) cmdline='';;
            esac
        done
        [ -z "$cmdline" ] && return 0
        sleep 0.1
    done
    return 1
}

assert_control_processes_absent() {
    local tuple pid start identity extra current
    for tuple in "${CONTROL_TUPLES[@]}"; do
        IFS='|' read -r pid start identity extra <<< "$tuple"
        [ -z "${extra:-}" ] || return 1
        current=$(hsqa_process_starttime "$pid")
        if [ "$current" = "$start" ]; then
            return 1
        fi
    done
}

# Prove the pre-PASS topology assertion itself rejects a leaked control pane;
# emergency EXIT cleanup is not part of this verdict.
SESSION_LEAK="hsqa-tmux-leak-$RUN_ID"; OWNER_LEAK="tmux-owner-leak-$RUN_ID"
SESSION_LEAK_ID=''; new_owned_session "$SESSION_LEAK" "$OWNER_LEAK" SESSION_LEAK_ID
if assert_isolated_tmux_empty; then
    fail "pre-PASS topology assertion accepted a leaked control session"
fi
hsqa_stop_owned_tmux_session "$SESSION_LEAK_ID" "$OWNER_LEAK" \
    || fail "leak-mutant control cleanup failed"

# The verdict also tracks every control PID/start tuple, independent of tmux
# topology. A deliberately orphan-shaped marked sleep must make it red before
# the test performs its bounded fixture cleanup.
/usr/bin/env HSQA_PROCESS_IDENTITY=tmux-control-orphan-$RUN_ID \
    /usr/bin/setsid /usr/bin/sleep 30 &
ORPHAN_CONTROL_PID=$!
ORPHAN_CONTROL_START=$(hsqa_process_starttime "$ORPHAN_CONTROL_PID")
CONTROL_TUPLES+=("$ORPHAN_CONTROL_PID|$ORPHAN_CONTROL_START|tmux-control-orphan-$RUN_ID")
if assert_control_processes_absent; then
    fail "pre-PASS control-process assertion accepted an orphan"
fi
kill -9 "$ORPHAN_CONTROL_PID" 2>/dev/null || true
wait "$ORPHAN_CONTROL_PID" 2>/dev/null || true

LEFTOVER_RECORDS=$(find "$HSQA_PIDDIR" -maxdepth 1 -type f -name '*.pids' \
    -size +0c -print)
[ -z "$LEFTOVER_RECORDS" ] \
    || fail "nonempty recovery record survived selftest: $LEFTOVER_RECORDS"
find /proc -maxdepth 2 -name environ -readable 2>/dev/null \
    -exec grep -l -a -E "HSQA_PROCESS_IDENTITY=tmux-(owner|child)-.*-$RUN_ID" {} + \
    2>/dev/null | grep -q . && fail "marked tmux process survived selftest"
assert_isolated_tmux_empty \
    || fail "isolated tmux topology/socket/server was not empty before PASS"
assert_control_processes_absent \
    || fail "a tracked control PID/start identity survived before PASS"

echo "tmux tracked selftest: PASS (barrier, parent death, refusal, parallel isolation)"
