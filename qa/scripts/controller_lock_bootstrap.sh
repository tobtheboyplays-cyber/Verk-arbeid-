#!/bin/bash -p
# Signal-resistant, lock-owned prelude for tools/hearthstead-qa.
#
# Entered only as the exact command child of /usr/bin/flock.  It proves that
# parent's executable, argv, lock inode and ignored INT/TERM state, publishes
# the full-run stale marker when required, then execs a clean controller.
set -u
set -o pipefail

SYSTEM_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
[ "${PATH:-}" = "$SYSTEM_PATH" ] && [ "${LC_ALL:-}" = C ] \
    && [ "${LANG:-}" = C.UTF-8 ] && [ "${TMPDIR:-}" = /tmp ] \
    || { echo "FATAL: non-hermetic controller bootstrap environment" >&2; exit 2; }
[ -z "${BASH_ENV:-}${ENV:-}${CDPATH:-}${PYTHONPATH:-}${PYTHONHOME:-}" ] \
    || { echo "FATAL: hostile startup hook reached controller bootstrap" >&2; exit 2; }
if builtin compgen -A function | /usr/bin/grep -q .; then
    echo "FATAL: imported shell function reached controller bootstrap" >&2
    exit 2
fi
builtin export -n BASHOPTS SHELLOPTS 2>/dev/null || true

CONTROLLER="${1:-}"
[ -n "$CONTROLLER" ] || exit 2
shift
COMMAND="${1:-help}"
CONTROLLER=$(/usr/bin/realpath -e -- "$CONTROLLER") || exit 2
case "$CONTROLLER" in */tools/hearthstead-qa) ;; *) exit 2;; esac
REPO="${CONTROLLER%/tools/hearthstead-qa}"
BOOTSTRAP=$(/usr/bin/realpath -e -- "${BASH_SOURCE[0]}") || exit 2
[ "$BOOTSTRAP" = "$REPO/qa/scripts/controller_lock_bootstrap.sh" ] \
    && [ "$CONTROLLER" = "$REPO/tools/hearthstead-qa" ] \
    || { echo "FATAL: invalid controller bootstrap target" >&2; exit 2; }

SUITE_LOCK=/tmp/hearthstead-qa-v2.lock
TOKEN="${HSQA_SUITE_LOCK_TOKEN:-}"
[[ "$TOKEN" =~ ^[0-9a-f]{48}$ ]] \
    || { echo "FATAL: invalid controller bootstrap token" >&2; exit 2; }

fixture_authority_valid() { # <root> <token>
    local root="$1" token="$2" token_file uid lexical canonical suffix
    [[ "$root" =~ ^/tmp/hsqa-reap-fixture\.[A-Za-z0-9]{6,64}$ ]] \
        && [[ "$token" =~ ^[0-9a-f]{48}$ ]] || return 1
    suffix="${root#/tmp/hsqa-reap-fixture.}"
    case "$suffix" in ''|*/*) return 1;; esac
    lexical=$(/usr/bin/realpath -ms -- "$root") || return 1
    canonical=$(/usr/bin/realpath -m -- "$root") || return 1
    [ "$root" = "$lexical" ] && [ "$root" = "$canonical" ] \
        && [ -d "$root" ] && [ ! -L "$root" ] || return 1
    uid=$(/usr/bin/id -u) || return 1
    [ "$(/usr/bin/stat -c %u -- "$root" 2>/dev/null)" = "$uid" ] \
        && [ "$(/usr/bin/stat -c %a -- "$root" 2>/dev/null)" = 700 ] \
        || return 1
    token_file="$root/.hsqa-reap-fixture-token"
    [ -f "$token_file" ] && [ ! -L "$token_file" ] \
        && [ "$(/usr/bin/stat -c %u -- "$token_file" 2>/dev/null)" = "$uid" ] \
        && [ "$(/usr/bin/stat -c %a -- "$token_file" 2>/dev/null)" = 600 ] \
        && [ "$(/usr/bin/stat -c %h -- "$token_file" 2>/dev/null)" = 1 ] \
        && [ "$(/usr/bin/stat -c %s -- "$token_file" 2>/dev/null)" = 48 ] \
        && /usr/bin/cmp -s -- "$token_file" <(builtin printf '%s' "$token")
}

allowed_assignment() {
    case "$1" in
        PATH|HOME|LANG|LC_ALL|SHELL|TERM|TMPDIR|HSQA_ACTIVE|HSQA_REPO|\
        HSQA_REPORTS|HSQA_ARTIFACTS|HSQA_SAFE_TMP_ROOT|HSQA_PIDDIR|\
        HSQA_INST_ROOT|HSQA_INSTALL_DIR|HSQA_CLIENT_INSTALL_DIR|\
        HSQA_GRADLE_CACHE|HSQA_LEVEL_TYPE|HSQA_TMUX_SOCKET|\
        HSQA_SUITE_LOCK_TOKEN|HSQA_REAP_FIXTURE_ROOT|\
        HSQA_REAP_FIXTURE_TOKEN|HSQA_PERF_FIXTURE_MODE|\
        HSQA_PERF_FIXTURE_ROOT|HSQA_PERF_FIXTURE_TOKEN|\
        HSQA_FULL_MARK_TEST_PAUSE|HSQA_BLESSING_RESTART_DISPLAY|\
        HSQA_BLESSING_RESTART_PORT|HSQA_CLIENT_FRAME_TIMEOUT|\
        HSQA_DEDICATED_PORT|HSQA_DISPLAY_HEIGHT|HSQA_DISPLAY_WIDTH|\
        HSQA_ENSURE_GRAB_ATTEMPTS|HSQA_GUI_SCALE|\
        HSQA_INPUT_RESPONSE_TIMEOUT|HSQA_LANGUAGE|HSQA_LIVE_PORT|\
        HSQA_PERFORMANCE_PORT|HSQA_PERFORMANCE_TIMEOUT_SECONDS|\
        HSQA_PERF_SAMPLE_COUNT|HSQA_PERF_SAMPLE_INTERVAL_SECONDS|\
        HSQA_PERF_START_DELAY_SECONDS|HSQA_PERF_WARMUP_SECONDS|\
        HSQA_PLAYTEST_PORT|HSQA_PLAYTEST_SCENARIO|\
        HSQA_ROTATION_QUERY_TIMEOUT) return 0;;
        *) return 1;;
    esac
}

# Prove the direct flock parent, not merely a forgeable environment flag.
[ "$(/usr/bin/readlink -f -- "/proc/$PPID/exe" 2>/dev/null || true)" \
    = /usr/bin/flock ] \
    || { echo "FATAL: controller bootstrap parent is not /usr/bin/flock" >&2; exit 2; }
PARENT_SIGIGN=$(/usr/bin/awk '$1 == "SigIgn:" { print $2 }' \
    "/proc/$PPID/status" 2>/dev/null) || exit 2
/usr/bin/python3 -I - "$PARENT_SIGIGN" <<'PYEOF' \
    || { echo "FATAL: controller bootstrap parent is not signal-resistant" >&2; exit 2; }
import re, sys
value = sys.argv[1]
if not re.fullmatch(r"[0-9A-Fa-f]+", value):
    raise SystemExit(1)
mask = int(value, 16)
raise SystemExit(0 if mask & 2 and mask & (1 << 14) else 1)
PYEOF
LOCK_FD_FOUND=0
for PARENT_FD in "/proc/$PPID/fd/"*; do
    [ "$(/usr/bin/readlink -f -- "$PARENT_FD" 2>/dev/null || true)" \
        = "$SUITE_LOCK" ] && LOCK_FD_FOUND=1
done
[ "$LOCK_FD_FOUND" -eq 1 ] \
    || { echo "FATAL: controller bootstrap parent lacks suite-lock descriptor" >&2; exit 2; }
LOCK_INODE=$(/usr/bin/stat -Lc %i -- "$SUITE_LOCK" 2>/dev/null || true)
[[ "$LOCK_INODE" =~ ^[0-9]+$ ]] \
    && /usr/bin/awk -v owner="$PPID" -v inode="$LOCK_INODE" \
        '$2 == "FLOCK" && $4 == "WRITE" && $5 == owner && $6 ~ (":" inode "$") { found=1 } END { exit !found }' \
        /proc/locks \
    || { echo "FATAL: controller bootstrap parent does not own suite lock" >&2; exit 2; }

PARENT_ARGV=()
mapfile -d '' -t PARENT_ARGV < "/proc/$PPID/cmdline" || exit 2
[ "${PARENT_ARGV[0]:-}" = /usr/bin/flock ] \
    && [ "${PARENT_ARGV[1]:-}" = -n ] \
    && [ "${PARENT_ARGV[2]:-}" = --close ] \
    && [ "${PARENT_ARGV[3]:-}" = --conflict-exit-code ] \
    && [ "${PARENT_ARGV[4]:-}" = 75 ] \
    && [ "${PARENT_ARGV[5]:-}" = "$SUITE_LOCK" ] \
    && [ "${PARENT_ARGV[6]:-}" = /usr/bin/env ] \
    && [ "${PARENT_ARGV[7]:-}" = -i ] \
    || { echo "FATAL: invalid flock/bootstrap argv prefix" >&2; exit 2; }

declare -A SEEN_ASSIGNMENT=()
PARENT_ASSIGNMENTS=()
INDEX=8
while [ "$INDEX" -lt "${#PARENT_ARGV[@]}" ] \
        && [ "${PARENT_ARGV[$INDEX]}" != /bin/bash ]; do
    ENTRY="${PARENT_ARGV[$INDEX]}"
    NAME="${ENTRY%%=*}"
    [ "$ENTRY" != "$NAME" ] && allowed_assignment "$NAME" \
        && [ -z "${SEEN_ASSIGNMENT[$NAME]:-}" ] && [[ -v "$NAME" ]] \
        && [ "$ENTRY" = "$NAME=${!NAME}" ] \
        || { echo "FATAL: invalid/duplicate bootstrap environment assignment" >&2; exit 2; }
    SEEN_ASSIGNMENT["$NAME"]=1
    PARENT_ASSIGNMENTS+=("$ENTRY")
    INDEX=$((INDEX + 1))
done
for NAME in PATH HOME LANG LC_ALL SHELL TERM TMPDIR HSQA_ACTIVE HSQA_REPO \
        HSQA_REPORTS HSQA_ARTIFACTS HSQA_SAFE_TMP_ROOT HSQA_PIDDIR \
        HSQA_INST_ROOT HSQA_INSTALL_DIR HSQA_CLIENT_INSTALL_DIR \
        HSQA_GRADLE_CACHE HSQA_LEVEL_TYPE HSQA_TMUX_SOCKET \
        HSQA_SUITE_LOCK_TOKEN; do
    [ "${SEEN_ASSIGNMENT[$NAME]:-}" = 1 ] \
        || { echo "FATAL: bootstrap environment omits $NAME" >&2; exit 2; }
done
[ "${PARENT_ARGV[$INDEX]:-}" = /bin/bash ] \
    && [ "${PARENT_ARGV[$((INDEX + 1))]:-}" = --noprofile ] \
    && [ "${PARENT_ARGV[$((INDEX + 2))]:-}" = --norc ] \
    && [ "${PARENT_ARGV[$((INDEX + 3))]:-}" = -p ] \
    && [ "${PARENT_ARGV[$((INDEX + 4))]:-}" = "$BOOTSTRAP" ] \
    && [ "${PARENT_ARGV[$((INDEX + 5))]:-}" = "$CONTROLLER" ] \
    || { echo "FATAL: invalid bootstrap interpreter/target argv" >&2; exit 2; }
INDEX=$((INDEX + 6))
EXPECTED_ARGS=("$@")
[ $((${#PARENT_ARGV[@]} - INDEX)) -eq "${#EXPECTED_ARGS[@]}" ] || exit 2
for ((ARG_INDEX=0; ARG_INDEX<${#EXPECTED_ARGS[@]}; ARG_INDEX++)); do
    [ "${PARENT_ARGV[$((INDEX + ARG_INDEX))]}" = "${EXPECTED_ARGS[$ARG_INDEX]}" ] \
        || { echo "FATAL: bootstrap command argv mismatch" >&2; exit 2; }
done

# Validate authority-bearing values independently of the controller that
# constructed them.  This keeps a direct bootstrap invocation fail-closed.
[ "$PATH" = "$SYSTEM_PATH" ] && [ "$HSQA_ACTIVE" = 1 ] \
    && [ "$HSQA_REPO" = "$REPO" ] \
    && [ "$HSQA_REPORTS" = "$REPO/qa/reports" ] \
    && [ "$HSQA_ARTIFACTS" = "$REPO/qa/reports/artifacts" ] \
    && [ "$HSQA_LEVEL_TYPE" = flat ] \
    && [ "$HSQA_TMUX_SOCKET" = /tmp/claude-0/hearthstead-qa-v2.tmux ] \
    && [ "$HSQA_GRADLE_CACHE" = "$HOME/.gradle/caches" ] \
    || { echo "FATAL: bootstrap authority values diverge" >&2; exit 2; }
case "$REPO" in
    /tmp/hsqa-full-dispatch.*)
        SUFFIX="${REPO#/tmp/hsqa-full-dispatch.}"
        case "$SUFFIX" in ''|*/*) exit 2;; esac
        [ "$HSQA_SAFE_TMP_ROOT" = "$REPO/tmp" ] \
            && [ "$HSQA_PIDDIR" = "$REPO/tmp/pids" ] \
            && [ "$HSQA_INST_ROOT" = "$REPO/tmp/instances" ] \
            && [ "$HSQA_INSTALL_DIR" = "$REPO/tmp/install" ] \
            && [ "$HSQA_CLIENT_INSTALL_DIR" = "$REPO/tmp/client-install" ] \
            || { echo "FATAL: full-fixture roots diverge" >&2; exit 2; }
        [ -z "${HSQA_REAP_FIXTURE_ROOT:-}${HSQA_REAP_FIXTURE_TOKEN:-}\
${HSQA_PERF_FIXTURE_MODE:-}${HSQA_PERF_FIXTURE_ROOT:-}\
${HSQA_PERF_FIXTURE_TOKEN:-}" ] || exit 2
        if [ -n "${HSQA_FULL_MARK_TEST_PAUSE:-}" ]; then
            [ "$HSQA_FULL_MARK_TEST_PAUSE" = 1 ] || exit 2
        fi;;
    /tmp/hsqa-perf-launch-*)
        SUFFIX="${REPO#/tmp/hsqa-perf-launch-}"
        case "$SUFFIX" in ''|*/*) exit 2;; esac
        case "$COMMAND" in performance|gate|reap) ;; *) exit 2;; esac
        [ "${HSQA_PERF_FIXTURE_MODE:-}" = prelaunch-artifact \
            ] || [ "${HSQA_PERF_FIXTURE_MODE:-}" = post-release-crash ] \
            || exit 2
        [ "${HSQA_PERF_FIXTURE_ROOT:-}" = "${HSQA_REAP_FIXTURE_ROOT:-}" ] \
            && [ "${HSQA_PERF_FIXTURE_TOKEN:-}" = "${HSQA_REAP_FIXTURE_TOKEN:-}" ] \
            && fixture_authority_valid "$HSQA_REAP_FIXTURE_ROOT" \
                "$HSQA_REAP_FIXTURE_TOKEN" \
            && [ "$HSQA_SAFE_TMP_ROOT" = "$HSQA_REAP_FIXTURE_ROOT" ] \
            && [ "$HSQA_PIDDIR" = "$HSQA_REAP_FIXTURE_ROOT/pids" ] \
            || { echo "FATAL: performance fixture authority diverges" >&2; exit 2; }
        [ -z "${HSQA_FULL_MARK_TEST_PAUSE:-}" ] || exit 2;;
    *)
        [ "$HSQA_SAFE_TMP_ROOT" = /tmp ] \
            && [ "$HSQA_PIDDIR" = /tmp/claude-0/hsqa-pids-v2 ] \
            && [ "$HSQA_INST_ROOT" = /tmp/claude-0/hsqa-inst-v2 ] \
            && [ "$HSQA_INSTALL_DIR" = /tmp/claude-0/hsqa-install-v2 ] \
            && [ "$HSQA_CLIENT_INSTALL_DIR" = /tmp/claude-0/hsqa-client-install-v2 ] \
            && [ -z "${HSQA_REAP_FIXTURE_ROOT:-}${HSQA_REAP_FIXTURE_TOKEN:-}\
${HSQA_PERF_FIXTURE_MODE:-}${HSQA_PERF_FIXTURE_ROOT:-}\
${HSQA_PERF_FIXTURE_TOKEN:-}${HSQA_FULL_MARK_TEST_PAUSE:-}" ] \
            || { echo "FATAL: production authority override reached bootstrap" >&2; exit 2; };;
esac

FULL_MARKED=0
if [ "$COMMAND" = full ]; then
    REPORTS="$REPO/qa/reports"
    /usr/bin/mkdir -p -- "$REPORTS/artifacts" || exit 2
    /usr/bin/python3 -I - "$REPORTS/.stale" "$$" \
        "$(/usr/bin/date -u +%Y%m%dT%H%M%S.%NZ)" <<'PYEOF' || exit 2
import os, stat, sys, tempfile
destination, pid, started = sys.argv[1:]
if os.path.lexists(destination):
    mode = os.lstat(destination).st_mode
    if not stat.S_ISREG(mode) or stat.S_ISLNK(mode):
        raise SystemExit("refusing corrupt/non-regular stale marker")
parent = os.path.dirname(destination)
fd, temporary = tempfile.mkstemp(prefix=".stale.tmp.", dir=parent)
try:
    with os.fdopen(fd, "w") as handle:
        handle.write(f"full-attempt-in-progress pid={pid} started={started}\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, destination)
    result = os.lstat(destination)
    if not stat.S_ISREG(result.st_mode) or stat.S_ISLNK(result.st_mode):
        raise RuntimeError("stale marker publication was not a regular file")
finally:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
PYEOF
    FULL_MARKED=1

    if [ "${HSQA_FULL_MARK_TEST_PAUSE:-0}" = 1 ]; then
        [ ! -e "$REPO/.full-mark-ready" ] && [ ! -L "$REPO/.full-mark-ready" ] \
            || exit 2
        (umask 077; set -o noclobber; builtin printf 'ready\n' \
            > "$REPO/.full-mark-ready") 2>/dev/null || exit 2
        while [ -e "$REPO/.full-mark-hold" ] || [ -L "$REPO/.full-mark-hold" ]; do
            /bin/sleep 0.01
        done
    fi
fi

# Keep the direct flock parent, rebuild the environment once more, and restore
# signals only after the full marker is durable.
exec /usr/bin/env --default-signal=INT,TERM -i "${PARENT_ASSIGNMENTS[@]}" \
    HSQA_LOCK_BOOTSTRAPPED=1 HSQA_FULL_MARKED="$FULL_MARKED" \
    /bin/bash --noprofile --norc -p "$CONTROLLER" "$@"
