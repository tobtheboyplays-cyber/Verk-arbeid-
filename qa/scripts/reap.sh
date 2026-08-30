#!/bin/bash -p
# Unconditional teardown for everything the QA harness might have left
# running: dedicated-server instances, Xvfb displays, the tmux live session.
#
# MUST NEVER kill the Gradle daemon (GradleDaemon in its cmdline) — that is
# reused across every suite in this session and killing it would be far more
# disruptive than the leak this script exists to clean up.
#
# Strategy: pidfiles first (exact, no guessing), then a restricted pattern
# fallback for anything a pidfile missed (a crash before the pidfile was
# written, a hand-started process). The fallback pattern and the
# GradleDaemon exclusion are covered by `reap.sh selftest` before ever being
# used for a real kill — see qa/PROTOCOL.md AC-7.
#
# Usage:
#   reap.sh              real teardown: pidfiles, then pattern fallback
#   reap.sh dry-run       print what WOULD be killed; kills nothing
#   reap.sh check          report leaked harness processes + held ports;
#                           exit 0 only if clean (no launch, no kill)
#   reap.sh selftest       prove the pattern matcher is correct against a set
#                           of fixture process listings (no real processes)
set -u
set -o pipefail
SYSTEM_PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
PATH="$SYSTEM_PATH"; LANG=C.UTF-8; LC_ALL=C; TMPDIR=/tmp
export PATH LANG LC_ALL TMPDIR
while IFS= read -r IMPORTED_FUNCTION; do
    [ -n "$IMPORTED_FUNCTION" ] \
        && builtin unset -f -- "$IMPORTED_FUNCTION" 2>/dev/null || true
done < <(builtin compgen -A function)
builtin export -n BASHOPTS SHELLOPTS 2>/dev/null || true
REQUESTED_FIXTURE_ROOT="${HSQA_REAP_FIXTURE_ROOT:-}"
REQUESTED_FIXTURE_TOKEN="${HSQA_REAP_FIXTURE_TOKEN:-}"
builtin unset BASH_ENV ENV CDPATH PYTHONPATH PYTHONHOME PYTHONSTARTUP \
    PYTHONINSPECT PYTHONWARNINGS PYTHONUSERBASE _JAVA_OPTIONS \
    JAVA_TOOL_OPTIONS GRADLE_OPTS TMUX TMUX_PANE HSQA_REPO HSQA_ARTIFACTS \
    HSQA_SAFE_TMP_ROOT HSQA_PIDDIR HSQA_INST_ROOT HSQA_TMUX_SOCKET \
    HSQA_TMUX_SELFTEST_MODE HSQA_LIB_READ_ONLY HSQA_PROCESS_IDENTITY \
    HSQA_REAP_FIXTURE_ROOT HSQA_REAP_FIXTURE_TOKEN 2>/dev/null || true
SCRIPT_PATH=$(/usr/bin/realpath -e -- "${BASH_SOURCE[0]}") || exit 2
HERE="${SCRIPT_PATH%/reap.sh}"
[ "$HERE/reap.sh" = "$SCRIPT_PATH" ] || exit 2
REPO="${HERE%/qa/scripts}"
SUBCMD="${1:-check}"
case "$SUBCMD" in check|dry-run|dryrun|reap|selftest) ;;
    *) echo "usage: reap.sh [dry-run|check|selftest|reap]" >&2; exit 1;;
esac

fixture_authority_valid() { # <root> <token>
    local root="$1" token="$2" suffix lexical canonical uid token_file
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

SELFTEST_ROOT=''
SELFTEST_EXTERNAL_FIXTURE=''
SELFTEST_EXTERNAL_REPO=''
SELFTEST_EXTERNAL_GROUP=''
SELFTEST_EXTERNAL_SENTINEL=''
SELFTEST_SWAP_ORIGINAL_GROUP=''
SELFTEST_SWAP_EXTERNAL_GROUP=''
SELFTEST_COMMIT_EXTERNAL_GROUP=''
REAP_FIXTURE_ACTIVE=0
if [ "$SUBCMD" = selftest ]; then
    [ -z "$REQUESTED_FIXTURE_ROOT$REQUESTED_FIXTURE_TOKEN" ] \
        || { echo "FATAL: production fixture authority on selftest" >&2; exit 2; }
    SELFTEST_ROOT=$(/usr/bin/mktemp -d /tmp/hsqa-tmux-tracked.reap.XXXXXX) \
        || exit 2
    /usr/bin/chmod 700 -- "$SELFTEST_ROOT" || exit 2
    HSQA_SAFE_TMP_ROOT="$SELFTEST_ROOT"
    HSQA_PIDDIR="$SELFTEST_ROOT/pids"
    HSQA_ARTIFACTS="$SELFTEST_ROOT/artifacts"
    HSQA_INST_ROOT="$SELFTEST_ROOT/instances"
    HSQA_TMUX_SOCKET="$SELFTEST_ROOT/tmux.sock"
    HSQA_TMUX_SELFTEST_MODE=1
elif [ -n "$REQUESTED_FIXTURE_ROOT$REQUESTED_FIXTURE_TOKEN" ]; then
    case "$REPO" in
        /tmp/hsqa-perf-launch-*)
            suffix="${REPO#/tmp/hsqa-perf-launch-}"
            case "$suffix" in ''|*/*) exit 2;; esac;;
        *) echo "FATAL: reaper fixture authority requires disposable controller copy" >&2; exit 2;;
    esac
    fixture_authority_valid "$REQUESTED_FIXTURE_ROOT" "$REQUESTED_FIXTURE_TOKEN" \
        || { echo "FATAL: unauthenticated reaper fixture authority" >&2; exit 2; }
    HSQA_SAFE_TMP_ROOT="$REQUESTED_FIXTURE_ROOT"
    HSQA_PIDDIR="$REQUESTED_FIXTURE_ROOT/pids"
    HSQA_ARTIFACTS="$REQUESTED_FIXTURE_ROOT/artifacts"
    # Fixture reaping is deliberately pidfile-only.  Keep every configured
    # path inside the authenticated root as well, so merely sourcing the
    # shared safety library cannot observe or depend on production state.
    HSQA_INST_ROOT="$REQUESTED_FIXTURE_ROOT/instances"
    HSQA_TMUX_SOCKET="$REQUESTED_FIXTURE_ROOT/tmux.sock"
    HSQA_REAP_FIXTURE_ROOT="$REQUESTED_FIXTURE_ROOT"
    HSQA_REAP_FIXTURE_TOKEN="$REQUESTED_FIXTURE_TOKEN"
    REAP_FIXTURE_ACTIVE=1
else
    case "$REPO" in
        /tmp/hsqa-full-dispatch.*)
            suffix="${REPO#/tmp/hsqa-full-dispatch.}"
            case "$suffix" in ''|*/*) exit 2;; esac
            HSQA_SAFE_TMP_ROOT="$REPO/tmp"
            HSQA_PIDDIR="$REPO/tmp/pids"
            HSQA_INST_ROOT="$REPO/tmp/instances";;
        *)
            HSQA_SAFE_TMP_ROOT=/tmp
            HSQA_PIDDIR=/tmp/claude-0/hsqa-pids-v2
            HSQA_INST_ROOT=/tmp/claude-0/hsqa-inst-v2;;
    esac
    HSQA_ARTIFACTS="$REPO/qa/reports/artifacts"
    HSQA_TMUX_SOCKET=/tmp/claude-0/hearthstead-qa-v2.tmux
fi
export HSQA_REPO="$REPO" HSQA_SAFE_TMP_ROOT HSQA_PIDDIR HSQA_INST_ROOT \
    HSQA_ARTIFACTS HSQA_TMUX_SOCKET
[ -z "${HSQA_TMUX_SELFTEST_MODE:-}" ] || export HSQA_TMUX_SELFTEST_MODE
case "$SUBCMD" in check|dry-run|dryrun) export HSQA_LIB_READ_ONLY=1;; esac
[ "$REAP_FIXTURE_ACTIVE" -eq 0 ] || export HSQA_LIB_READ_ONLY=1
. "$HERE/lib_harness.sh" \
    || { echo "FATAL: reaper could not load the harness safety library" >&2; exit 2; }
if [ "$REAP_FIXTURE_ACTIVE" -eq 0 ]; then
    . "$HERE/lib_tmux_harness.sh" \
        || { echo "FATAL: reaper could not load the tmux safety library" >&2; exit 2; }
fi

cleanup_reap_selftest() {
    local status=$?
    trap - EXIT INT TERM
    if [[ "$SELFTEST_EXTERNAL_GROUP" =~ ^[1-9][0-9]*$ ]]; then
        /bin/kill -9 -- "-$SELFTEST_EXTERNAL_GROUP" 2>/dev/null || true
    fi
    if [[ "$SELFTEST_EXTERNAL_SENTINEL" =~ ^[1-9][0-9]*$ ]]; then
        /bin/kill -9 -- "$SELFTEST_EXTERNAL_SENTINEL" 2>/dev/null || true
        wait "$SELFTEST_EXTERNAL_SENTINEL" 2>/dev/null || true
    fi
    if [[ "$SELFTEST_SWAP_ORIGINAL_GROUP" =~ ^[1-9][0-9]*$ ]]; then
        /bin/kill -9 -- "-$SELFTEST_SWAP_ORIGINAL_GROUP" 2>/dev/null || true
    fi
    if [[ "$SELFTEST_SWAP_EXTERNAL_GROUP" =~ ^[1-9][0-9]*$ ]]; then
        /bin/kill -9 -- "-$SELFTEST_SWAP_EXTERNAL_GROUP" 2>/dev/null || true
    fi
    if [[ "$SELFTEST_COMMIT_EXTERNAL_GROUP" =~ ^[1-9][0-9]*$ ]]; then
        /bin/kill -9 -- "-$SELFTEST_COMMIT_EXTERNAL_GROUP" 2>/dev/null || true
    fi
    if [ -n "$SELFTEST_EXTERNAL_FIXTURE" ]; then
        case "$SELFTEST_EXTERNAL_FIXTURE" in /tmp/hsqa-reap-fixture.*) ;;
            *) exit 125;;
        esac
        /usr/bin/rm -rf -- "$SELFTEST_EXTERNAL_FIXTURE"
    fi
    if [ -n "$SELFTEST_EXTERNAL_REPO" ]; then
        case "$SELFTEST_EXTERNAL_REPO" in /tmp/hsqa-perf-launch-reap-*) ;;
            *) exit 125;;
        esac
        /usr/bin/rm -rf -- "$SELFTEST_EXTERNAL_REPO"
    fi
    if [ -n "$SELFTEST_ROOT" ]; then
        case "$SELFTEST_ROOT" in /tmp/hsqa-tmux-tracked.reap.*) ;;
            *) echo "FATAL: refusing unsafe reaper selftest cleanup" >&2; exit 125;;
        esac
        [ "$(/usr/bin/realpath -m -- "$SELFTEST_ROOT")" = "$SELFTEST_ROOT" ] \
            || exit 125
        # Test-only last resort on the isolated, random socket. Production
        # cleanup below never uses kill-server/kill-session/kill-window.
        if [ -S "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ]; then
            /usr/bin/env -i PATH="$SYSTEM_PATH" HOME="${HOME:-/tmp}" \
                LANG=C.UTF-8 LC_ALL=C TERM=xterm-256color \
                /usr/bin/tmux -f /dev/null -S "$HSQA_TMUX_SOCKET" \
                    kill-server 2>/dev/null || true
        fi
        /usr/bin/rm -rf -- "$SELFTEST_ROOT"
        [ ! -e "$SELFTEST_ROOT" ] && [ ! -L "$SELFTEST_ROOT" ] || exit 125
    fi
    exit "$status"
}
[ "$SUBCMD" != selftest ] || trap cleanup_reap_selftest EXIT INT TERM

PIDDIR="$HSQA_PIDDIR"
# The four isolated instance ports (D-H2): dedicated, performance, playtest, live.
PORTS="25571 25572 25573 25574 25576"
# Restricted fallback pattern — anything broader risks catching the Gradle
# daemon or an unrelated process. GradleDaemon is excluded unconditionally,
# regardless of what else a line matches.
# The JVM fallback keys on the deliberately injected -Dhsqa.instanceDir token,
# not on a bare path substring. That token covers both legacy hsqa-inst and the
# marker-owned hsqa-inst-v2 without ever
# matching the shared hsqa-install cache or a diagnostic that merely mentions
# a path. Xvfb and tmux keep their exact harness identities.
# Extended beyond the plan's Xvfb/tmux pattern to also match playtest.sh's tmux
# session
# (hsqa-playtest) — added when playtest.sh moved its server console from a
# FIFO to tmux for the same reliability reasons as live.sh (D-H1).
# hsqa-inst/ (with the trailing slash) NOT bare hsqa-inst — proven live:
# "hsqa-inst" is a literal substring of "hsqa-install" (the shared,
# never-torn-down install cache dir), so the bare form false-matched a
# harmless diagnostic command that merely echoed an hsqa-install path,
# which `reap reap` would then have tried to kill. The trailing slash
# still matches every real "/tmp/.../hsqa-inst/<role>/..." instance path
# and no longer matches "hsqa-install".
#
# This is the ONE canonical pattern (finding 10: there used to be a second,
# separately-typed copy that only `selftest` looked at — the two could
# silently diverge and selftest would still say PASS). Bracket one letter of
# each alternative so the grep invocation's own argv (which literally
# contains this pattern text when `ps` lists reap.sh itself) never
# self-matches — the classic pgrep self-exclusion trick. Real process lines
# don't have brackets, so [h]sqa-inst still matches a literal "hsqa-inst".
SELF_SAFE_PATTERN='-D[h]sqa\.instanceDir=[^[:space:]]+/[a-z0-9][a-z0-9_-]{0,31}([[:space:]]|$)|[X]vfb :9[5-9]'
EXCLUDE='GradleDaemon'
CONFIGURED_INST_ROOT=$(hsqa_safe_target \
    "${HSQA_INST_ROOT:-/tmp/claude-0/hsqa-inst-v2}" "instance root") || exit 2
REAP_SELFTEST_ACTIVE=0
# These test-only barrier names are overwritten unconditionally before any
# command dispatch.  cmd_selftest shadows them with dynamically-scoped locals;
# ambient callers can never activate a destructive-boundary pause.
HSQA_REAP_PIDFILE_SWAP_READY=''
HSQA_REAP_PIDFILE_SWAP_HOLD=''
HSQA_REAP_PIDFILE_COMMIT_READY=''
HSQA_REAP_PIDFILE_COMMIT_HOLD=''
HSQA_REAP_PIDFILE_EMPTY_READY=''
HSQA_REAP_PIDFILE_EMPTY_HOLD=''
if [ "$SUBCMD" != selftest ]; then
    for test_var in HSQA_REAP_TEST_INPUT HSQA_REAP_TEST_OWNED_INSTANCES \
            HSQA_REAP_TEST_CMDLINES HSQA_REAP_TEST_STARTTIMES \
            HSQA_REAP_TEST_IDENTITIES HSQA_REAP_TEST_GROUPS; do
        if [ "${!test_var+x}" = x ]; then
            echo "FATAL: $test_var is a selftest-only seam and is forbidden for $SUBCMD" >&2
            exit 2
        fi
    done
fi

# The PIDs of this process and everything that launched it. A reap must never
# consider its own caller: proven live (reap/20260824T002935Z) that an
# invoking `bash -c '... hsqa-inst/f1proof ...'` wrapper MATCHED the harness
# pattern, because its argv legitimately contains the path being tested, and
# was reported as a leak. The SELF_SAFE_PATTERN bracket trick only protects
# grep's OWN argv, never the caller's — no pattern can, so the fix has to be
# by identity, not by text.
ancestry_pids() {
    local p="${1:-$$}" out=""
    while [ -n "$p" ] && [ "$p" != 0 ] && [ "$p" != 1 ]; do
        out="$out $p"
        p=$(awk '{print $4}' "/proc/$p/stat" 2>/dev/null)
    done
    printf '%s' "$out"
}

matching_procs() { # prints exact candidates; 2 means observation/classification error
    # Finding 10: `selftest` used to grep its OWN re-typed copy of this
    # pattern, which meant it never actually exercised this function or its
    # self_safe bracket-escaping — a change here could silently diverge from
    # what selftest claims is covered. Source of process lines is
    # injectable via HSQA_REAP_TEST_INPUT so selftest can feed a fixture
    # through this EXACT function instead of a re-typed stand-in.
    local source_lines mine
    mine=" $(ancestry_pids) "
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_INPUT:-}" ]; then
        source_lines="$(printf '%s\n' "$HSQA_REAP_TEST_INPUT")"
    else
        source_lines="$(/usr/bin/ps -eo pid=,args= 2>/dev/null)" || return 2
    fi
    while read -r pid rest; do
        [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 2
        printf '%s\n' "$rest" | /usr/bin/grep -Eq -- "$SELF_SAFE_PATTERN" || continue
        printf '%s\n' "$rest" | /usr/bin/grep -Eq "$EXCLUDE" && continue
        case "$mine" in *" $pid "*) continue;; esac
        case "$rest" in
            *-Dhsqa.instanceDir=*)
                fallback_instance_detected "$pid" "$rest"
                case $? in 0) ;; 1) continue;; *) return 2;; esac;;
            *Xvfb\ :9[5-9]*)
                fallback_xvfb_detected "$pid" "$rest"
                case $? in 0) ;; 1) continue;; *) return 2;; esac;;
        esac
        printf '%s %s\n' "$pid" "$rest"
    done <<< "$source_lines"
}

pid_exact_instance_token() { # <pid> [synthetic cmdline]
    local pid="$1" synthetic="${2:-}" arg found=''
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ]; then
        for arg in $synthetic; do
            case "$arg" in
                -Dhsqa.instanceDir=*)
                    [ -z "$found" ] || return 1
                    found="$arg";;
            esac
        done
    else
        [ -r "/proc/$pid/cmdline" ] || {
            [ -e "/proc/$pid" ] && return 2
            return 1
        }
        while IFS= read -r -d '' arg; do
            case "$arg" in
                -Dhsqa.instanceDir=*)
                    [ -z "$found" ] || return 1
                    found="$arg";;
            esac
        done < "/proc/$pid/cmdline" || return 2
    fi
    [ -n "$found" ] || return 1
    printf '%s\n' "$found"
}

fallback_instance_detected() { # <pid> <ps cmdline>; exact token under configured/test root
    local pid="$1" cmd="$2" token instance role lexical root
    token=$(pid_exact_instance_token "$pid" "$cmd"); local token_status=$?
    [ "$token_status" -eq 0 ] || return "$token_status"
    instance="${token#-Dhsqa.instanceDir=}"
    role="${instance##*/}"
    hsqa_validate_role "$role" || return 1
    lexical=$(/usr/bin/realpath -ms -- "$instance") || return 2
    root="${lexical%/*}"
    if [ "$root" = "$CONFIGURED_INST_ROOT" ]; then
        return 0
    fi
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] \
            && printf '%s\n' "${HSQA_REAP_TEST_OWNED_INSTANCES:-}" \
                | awk -v p="$instance" -v r="$role" \
                    '$1 == p && $2 == r { found=1 } END { exit !found }'; then
        return 0
    fi
    return 1
}

fallback_xvfb_detected() { # <pid> <ps cmdline>
    local pid="$1" cmd="$2" arg first=1 display=''
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ]; then
        printf '%s\n' "$cmd" | /usr/bin/grep -qE '(^|[[:space:]])Xvfb :9[5-9]([[:space:]]|$)'
        return 0
    fi
    [ -r "/proc/$pid/cmdline" ] || {
        [ -e "/proc/$pid" ] && return 2
        return 1
    }
    while IFS= read -r -d '' arg; do
        if [ "$first" -eq 1 ]; then
            [ "${arg##*/}" = Xvfb ] || return 1
            first=0
        elif [[ "$arg" =~ ^:9[5-9]$ ]]; then
            [ -z "$display" ] || return 1
            display="$arg"
        fi
    done < "/proc/$pid/cmdline" || return 2
    [ -n "$display" ]
}

fallback_instance_owned() { # <cmdline containing exact instanceDir token>
    local cmd="$1" instance role root lexical canonical
    instance="${cmd#*-Dhsqa.instanceDir=}"
    instance="${instance%%[[:space:]]*}"
    role="${instance##*/}"
    hsqa_validate_role "$role" || return 1
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_OWNED_INSTANCES:-}" ]; then
        printf '%s\n' "$HSQA_REAP_TEST_OWNED_INSTANCES" \
            | awk -v p="$instance" -v r="$role" '$1 == p && $2 == r { found=1 } END { exit !found }'
        return
    fi
    lexical=$(realpath -ms -- "$instance") || return 1
    canonical=$(realpath -m -- "$instance") || return 1
    [ "$lexical" = "$canonical" ] && [ "$canonical" = "$instance" ] || return 1
    root="${canonical%/*}"
    [ "$root" = "$CONFIGURED_INST_ROOT" ] || return 1
    hsqa_require_owned_directory "$root" \
        .hsqa-instance-root-owned hsqa-instance-root-v1 >/dev/null 2>&1 \
        && hsqa_require_owned_directory "$canonical" \
            .hsqa-instance-owned "hsqa-instance-v1:$role" >/dev/null 2>&1
}

pid_env_identity_value() { # <pid>; prints one well-formed identity, 1 gone/none, 2 malformed/unreadable
    local pid="$1" environment line value found='' count=0
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_IDENTITIES:-}" ]; then
        environment=$(printf '%s\n' "$HSQA_REAP_TEST_IDENTITIES" \
            | /usr/bin/awk -v p="$pid" '$1 == p { print $2 }') || return 2
    else
        [ -e "/proc/$pid" ] || return 1
        [ -r "/proc/$pid/environ" ] || return 2
        environment=$(/usr/bin/tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null) \
            || return 2
        environment=$(printf '%s\n' "$environment" \
            | /usr/bin/sed -n 's/^HSQA_PROCESS_IDENTITY=//p') || return 2
    fi
    [ -n "$environment" ] || return 1
    while IFS= read -r value; do
        [[ "$value" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 2
        found="$value"; count=$((count + 1))
    done <<< "$environment"
    [ "$count" -eq 1 ] || return 2
    printf '%s\n' "$found"
}

fallback_process_authorized() { # <pid> <ps cmdline>; prints immutable start|identity
    local pid="$1" cmd="$2" token instance role identity exe display start status
    case "$cmd" in
        *-Dhsqa.instanceDir=*)
            fallback_instance_detected "$pid" "$cmd"; status=$?
            [ "$status" -eq 0 ] || return "$status"
            token=$(pid_exact_instance_token "$pid" "$cmd"); status=$?
            [ "$status" -eq 0 ] || return "$status"
            instance="${token#-Dhsqa.instanceDir=}"
            role="${instance##*/}"
            fallback_instance_owned "$token" || return 1
            identity=$(pid_env_identity_value "$pid"); status=$?
            [ "$status" -eq 0 ] || return "$status"
            case "$role" in
                performance)
                    [[ "$identity" =~ ^perf-server(-[0-9a-f]{24})?$ ]] || return 1;;
                dedicated) [ "$identity" = dedicated-server ] || return 1;;
                playtest) [ "$identity" = playtest-server ] || return 1;;
                blessing-restart) [ "$identity" = restart-server ] || return 1;;
                *) return 1;;
            esac
            if [ "$REAP_SELFTEST_ACTIVE" -ne 1 ]; then
                exe=$(/usr/bin/readlink -f -- "/proc/$pid/exe" 2>/dev/null || true)
                case "${exe##*/}" in java|java.exe) ;; *) return 1;; esac
            fi
            ;;
        *Xvfb\ :9[5-9]*)
            fallback_xvfb_detected "$pid" "$cmd"; status=$?
            [ "$status" -eq 0 ] || return "$status"
            if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ]; then
                display=$(printf '%s\n' "$cmd" \
                    | /usr/bin/grep -oE '(^|[[:space:]]):9[5-9]([[:space:]]|$)' \
                    | /usr/bin/tr -d '[:space:]' | /usr/bin/head -1)
            else
                display=$(/usr/bin/tr '\0' '\n' < "/proc/$pid/cmdline" \
                    | /usr/bin/grep -Ex ':9[5-9]' | /usr/bin/head -1) || return 2
            fi
            case "$display" in
                :96) identity=restart-xvfb ;;
                :97) identity=client-boot-xvfb ;;
                :98) identity=playtest-xvfb ;;
                *) return 1 ;;
            esac
            pid_env_identity_status "$identity" "$pid"; status=$?
            [ "$status" -eq 0 ] || return "$status"
            if [ "$REAP_SELFTEST_ACTIVE" -ne 1 ]; then
                exe=$(/usr/bin/readlink -f -- "/proc/$pid/exe" 2>/dev/null || true)
                [ "${exe##*/}" = Xvfb ] || return 1
            fi
            ;;
        *) return 1;;
    esac
    start=$(pid_starttime "$pid")
    [[ "$start" =~ ^[0-9]+$ ]] || {
        [ -e "/proc/$pid" ] && return 2
        return 1
    }
    printf '%s|%s\n' "$start" "$identity"
}

# The pidfile-stage decision, as a function so it can actually be TESTED.
# Finding 11 put this logic inline in cmd_reap's kill loop and finding 7 of
# the re-review pointed out that selftest then graded a re-typed copy of it —
# the same defect finding 10 was raised for. One definition, two callers.
pid_cmdline() { # <bare-pid>
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_CMDLINES:-}" ]; then
        printf '%s\n' "$HSQA_REAP_TEST_CMDLINES" \
            | awk -v p="$1" '$1 == p { $1 = ""; sub(/^ /, ""); print; exit }'
    else
        ps -o args= -p "$1" 2>/dev/null
    fi
}

pid_starttime() { # <bare-pid>
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_STARTTIMES:-}" ]; then
        printf '%s\n' "$HSQA_REAP_TEST_STARTTIMES" \
            | awk -v p="$1" '$1 == p { print $2; exit }'
    else
        hsqa_process_starttime "$1"
    fi
}

pid_identity_matches() { # <identity-tag> <current cmdline>
    local identity="$1" cmd="$2"
    case "$identity" in
        perf-build)     printf '%s' "$cmd" | grep -qE 'perf_probe\.sh|gradlew.*(^|[[:space:]])build([[:space:]]|$)' ;;
        perf-install)   printf '%s' "$cmd" | grep -qE 'perf_probe\.sh|server_install\.sh' ;;
        perf-server)    printf '%s' "$cmd" | grep -qE 'perf_probe\.sh|run\.sh nogui|-Dhsqa\.instanceDir=.*/performance([[:space:]]|$)' ;;
        perf-active)    printf '%s' "$cmd" | grep -qE 'perf_probe\.sh|runGameTestServer' ;;
        dedicated-server) printf '%s' "$cmd" | grep -qE 'dedicated_e2e\.sh|run\.sh nogui|-Dhsqa\.instanceDir=.*/dedicated([[:space:]]|$)' ;;
        playtest-server) printf '%s' "$cmd" | grep -qE 'playtest\.sh|run\.sh nogui|-Dhsqa\.instanceDir=.*/playtest([[:space:]]|$)' ;;
        restart-server) printf '%s' "$cmd" | grep -qE 'blessing_restart_probe\.sh|run\.sh nogui|-Dhsqa\.instanceDir=.*/blessing-restart([[:space:]]|$)' ;;
        playtest-xvfb|client-boot-xvfb|restart-xvfb)
            printf '%s' "$cmd" | grep -qE '(^|[[:space:]])Xvfb :9[5-9]([[:space:]]|$)' ;;
        playtest-client) printf '%s' "$cmd" | grep -qE 'playtest\.sh|gradlew.*runClient' ;;
        client-boot-client) printf '%s' "$cmd" | grep -qE 'client_boot\.sh|gradlew.*runClient' ;;
        restart-client) printf '%s' "$cmd" | grep -qE 'blessing_restart_probe\.sh|launch\.py' ;;
        *) return 1 ;;
    esac
}

pid_env_identity_status() { # <identity-tag> <pid>; 0 match, 1 mismatch/gone, 2 unreadable/malformed
    local identity="$1" pid="$2" environment line value count=0 matched=0
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_IDENTITIES:-}" ]; then
        environment=$(printf '%s\n' "$HSQA_REAP_TEST_IDENTITIES" \
            | /usr/bin/awk -v p="$pid" '$1 == p { print $2 }') || return 2
        [ -n "$environment" ] || return 1
        while IFS= read -r value; do
            [[ "$value" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 2
            count=$((count + 1))
            [ "$value" = "$identity" ] && matched=$((matched + 1))
        done <<< "$environment"
        [ "$count" -eq 1 ] && [ "$matched" -eq 1 ]
        return
    fi
    [ -e "/proc/$pid" ] || return 1
    [ -r "/proc/$pid/environ" ] || return 2
    environment=$(/usr/bin/tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null) \
        || return 2
    while IFS= read -r line; do
        case "$line" in
            HSQA_PROCESS_IDENTITY=*)
                value="${line#HSQA_PROCESS_IDENTITY=}"
                [[ "$value" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 2
                count=$((count + 1))
                [ "$value" = "$identity" ] && matched=$((matched + 1));;
        esac
    done <<< "$environment"
    [ "$count" -eq 1 ] && [ "$matched" -eq 1 ]
}

pid_env_identity_matches() { # compatibility predicate for non-destructive callers
    pid_env_identity_status "$1" "$2"
}

pid_has_identity() { # <identity-tag> <pid>
    local identity="$1" pid="$2" cmd
    pid_env_identity_matches "$identity" "$pid" && return 0
    cmd=$(pid_cmdline "$pid")
    [ -n "$cmd" ] && pid_identity_matches "$identity" "$cmd"
}

pid_group_members() { # <positive pgid>; one PID per line, 2=ps/query error
    local pgid="$1" rows pid current_group extra
    [[ "$pgid" =~ ^[1-9][0-9]*$ ]] || return 2
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] && [ -n "${HSQA_REAP_TEST_GROUPS:-}" ]; then
        rows="$HSQA_REAP_TEST_GROUPS"
        while read -r current_group pid extra; do
            [ -z "${extra:-}" ] && [[ "$current_group" =~ ^(0|[1-9][0-9]*)$ ]] \
                && [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 2
            [ "$current_group" = "$pgid" ] && printf '%s\n' "$pid"
        done <<< "$rows"
        return
    fi
    rows=$(/usr/bin/ps -eo pid=,pgid= 2>/dev/null) || return 2
    while read -r pid current_group extra; do
        [ -z "${extra:-}" ] && [[ "$pid" =~ ^[1-9][0-9]*$ ]] \
            && [[ "$current_group" =~ ^(0|[1-9][0-9]*)$ ]] || return 2
        [ "$current_group" = "$pgid" ] && printf '%s\n' "$pid"
    done <<< "$rows"
    return 0
}

pid_record_disposition() { # <target|leader|starttime|identity>
    local record="$1" target leader recorded_start identity current_start cmd extra
    local members member identity_found=0 leader_present=0
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    [ -z "${extra:-}" ] \
        && [[ "$target" =~ ^-?[1-9][0-9]*$ ]] \
        && [[ "$leader" =~ ^[1-9][0-9]*$ ]] \
        && [ "${target#-}" = "$leader" ] \
        && [[ "$recorded_start" =~ ^[0-9]+$ ]] \
        && [[ "$identity" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        || { echo refuse; return; }
    if [[ "$target" = -* ]]; then
        members=$(pid_group_members "$leader"); local members_status=$?
        [ "$members_status" -eq 0 ] || return 2
        [ -n "$members" ] || { echo gone; return; }
        current_start=$(pid_starttime "$leader")
        [ -e "/proc/$leader" ] && leader_present=1
        if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] \
                && printf '%s\n' "${HSQA_REAP_TEST_STARTTIMES:-}" \
                    | awk -v p="$leader" '$1 == p { found=1 } END { exit !found }'; then
            leader_present=1
        fi
        if [ "$leader_present" -eq 1 ]; then
            [[ "$current_start" =~ ^[0-9]+$ ]] \
                && [ "$current_start" = "$recorded_start" ] \
                || { echo refuse; return; }
        fi
        while IFS= read -r member; do
            [ -n "$member" ] || continue
            # Every tracked descendant inherits the exact launch marker.
            # One marked leader never authorizes an unmarked process that
            # happens to share the numeric PGID.  Marked single-use Gradle
            # daemons are accepted; unmarked/shared daemons are refused.
            pid_env_identity_status "$identity" "$member"
            case $? in 0) ;; 1) echo refuse; return;; *) return 2;; esac
            identity_found=1
        done <<< "$members"
        [ "$identity_found" -eq 1 ] && echo kill || echo refuse
        return
    fi
    current_start=$(pid_starttime "$leader")
    if [ -z "$current_start" ]; then
        [ -e "/proc/$leader" ] && return 2
        echo gone
        return
    fi
    [ "$current_start" = "$recorded_start" ] || { echo refuse; return; }
    pid_env_identity_status "$identity" "$leader"
    case $? in 0) echo kill;; 1) echo refuse;; *) return 2;; esac
}

record_target_present() { # 0=present, 1=proven absent/recycled, 2=observation error
    local record="$1" target leader recorded_start identity extra members current
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    [ -z "${extra:-}" ] || return 0
    if [[ "$target" = -* ]]; then
        # A negative PID addresses the whole process group.  Checking only the
        # recorded leader is wrong once that leader has exited but a descendant
        # is still alive (the exact crash shape recovery pidfiles exist for).
        members=$(pid_group_members "$leader"); local members_status=$?
        [ "$members_status" -eq 0 ] || return 2
        [ -n "$members" ]
    else
        current=$(pid_starttime "$leader")
        if [ -z "$current" ]; then
            [ -e "/proc/$leader" ] && return 2
            return 1
        fi
        [ "$current" = "$recorded_start" ]
    fi
}

kill_verified_record() { # <record> <source-pidfile>; 0=drop, 1=retain
    local record="$1" source="$2" target leader recorded_start identity extra
    local cmd disposition attempt target_state second
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    cmd=$(pid_cmdline "${leader:-0}")
    disposition=$(pid_record_disposition "$record"); local disposition_status=$?
    if [ "$disposition_status" -ne 0 ]; then
        echo "  REFUSING record $record (from $source): process identity could not be observed"
        return 1
    fi
    case "$disposition" in
        gone)
            echo "  skip record $record (from $source): verified target already gone"
            return 0;;
        refuse)
            echo "  REFUSING record $record (from $source): identity/starttime mismatch — cmd: $cmd"
            return 1;;
        kill)
            # Revalidate at the destructive boundary. A recycled PGID/PID or
            # a concurrent unmarked group member appearing after the first
            # classification removes all kill authority.
            second=$(pid_record_disposition "$record"); disposition_status=$?
            [ "$disposition_status" -eq 0 ] && [ "$second" = kill ] || {
                echo "  REFUSING record $record (from $source): identity changed at kill boundary"
                return 1
            }
            kill -9 -- "$target" 2>/dev/null || true
            # Do not drop a negative-PGID recovery record merely because its
            # leader vanished.  The record remains authoritative until the
            # kernel reports the entire group absent.  Five seconds is bounded
            # and also allows an adopted, killed child to be reaped by init.
            for ((attempt=1; attempt<=500; attempt++)); do
                record_target_present "$record"; target_state=$?
                case "$target_state" in
                    0) /bin/sleep 0.01;;
                    1) break;;
                    *)
                        echo "  REFUSING to drop record after SIGKILL observation error: $record"
                        return 1;;
                esac
            done
            record_target_present "$record"; target_state=$?
            [ "$target_state" -eq 1 ] || {
                [ "$target_state" -eq 0 ] \
                    && echo "  REFUSING to drop live record after SIGKILL: $record" \
                    || echo "  REFUSING to drop uncertain record after SIGKILL: $record"
                return 1
            }
            echo "  killed verified target $target (from $source): $cmd"
            return 0;;
        *)
            echo "  REFUSING record $record (from $source): invalid disposition '$disposition'"
            return 1;;
    esac
}

plain_owned_file() { # <file>; exact current-uid, non-link, one-link regular file
    local file="$1" uid
    uid=$(/usr/bin/id -u) || return 1
    [ -f "$file" ] && [ ! -L "$file" ] \
        && [ "$(/usr/bin/stat -c %u -- "$file" 2>/dev/null)" = "$uid" ] \
        && [ "$(/usr/bin/stat -c %h -- "$file" 2>/dev/null)" = 1 ]
}

pid_root_inspect() { # 0 clean, 1 recovery residue, 2 unsafe/unobservable
    local entries status line name type role mode dirty=0
    if [ ! -e "$PIDDIR" ] && [ ! -L "$PIDDIR" ]; then
        return 0
    fi
    hsqa_require_owned_directory "$PIDDIR" \
        .hsqa-pid-root-owned hsqa-pid-root-v1 >/dev/null 2>&1 \
        || { echo "RECOVERY ROOT UNSAFE OR UNOWNED: $PIDDIR"; return 2; }
    entries=$(/usr/bin/find -P "$PIDDIR" -mindepth 1 -maxdepth 1 \
        -printf '%f|%y\n' 2>/dev/null); status=$?
    [ "$status" -eq 0 ] \
        || { echo "RECOVERY ROOT OBSERVABILITY ERROR: $PIDDIR"; return 2; }
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        IFS='|' read -r name type extra <<< "$line"
        [ -z "${extra:-}" ] || return 2
        case "$name" in
            .hsqa-pid-root-owned) ;;
            *.pids.lock)
                role="${name%.pids.lock}"
                [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
                    && [ "$type" = f ] && plain_owned_file "$PIDDIR/$name" \
                    || { echo "RECOVERY LOCK UNSAFE: $PIDDIR/$name"; return 2; }
                mode=$(/usr/bin/stat -c %a -- "$PIDDIR/$name" 2>/dev/null) \
                    || return 2
                [ "$mode" = 600 ] || [ "$mode" = 644 ] \
                    || { echo "RECOVERY LOCK MODE UNSAFE: $PIDDIR/$name"; return 2; };;
            *.pids)
                role="${name%.pids}"
                [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
                    && [ "$type" = f ] && plain_owned_file "$PIDDIR/$name" \
                    || { echo "RECOVERY PIDFILE UNSAFE: $PIDDIR/$name"; return 2; }
                empty_pidfile_sentinel_status "$role"; status=$?
                case "$status" in
                    0) ;;
                    1)
                        echo "RECOVERY PIDFILE PRESENT: $PIDDIR/$name"
                        dirty=1;;
                    *)
                        echo "RECOVERY PIDFILE UNSAFE OR UNOBSERVABLE: $PIDDIR/$name"
                        return 2;;
                esac;;
            *)
                echo "UNEXPECTED RECOVERY-ROOT ENTRY: $PIDDIR/$name"
                dirty=1;;
        esac
    done <<< "$entries"
    return "$dirty"
}

empty_pidfile_sentinel_status() { # <role>; 0 exact empty, 1 nonempty, 2 unsafe
    local role="$1"
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 2
    /usr/bin/python3 -I - "$PIDDIR" "$role" <<'PYEOF'
import fcntl
import os
import re
import signal
import stat
import sys

root, role = sys.argv[1:]
if re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,63}", role) is None:
    raise SystemExit(2)

uid = os.getuid()
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
root_fd = marker_fd = lock_fd = file_fd = None
lease_held = False
lease_break_requested = False

def lease_break_handler(_signum, _frame):
    global lease_break_requested
    lease_break_requested = True

def same_inode(left, right):
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino

def validate_named(fd, name, expected=None, modes=None):
    actual = os.fstat(fd)
    named = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    for value in (actual, named):
        if (not stat.S_ISREG(value.st_mode) or value.st_uid != uid
                or value.st_nlink != 1):
            raise RuntimeError("unsafe sentinel authority")
        if modes is not None and stat.S_IMODE(value.st_mode) not in modes:
            raise RuntimeError("unsafe sentinel mode")
    if not same_inode(actual, named):
        raise RuntimeError("sentinel name changed")
    if expected is not None and not same_inode(actual, expected):
        raise RuntimeError("sentinel inode changed")
    return actual

try:
    required = ("F_SETLEASE", "F_GETLEASE", "F_SETOWN", "F_RDLCK", "F_UNLCK")
    if any(not hasattr(fcntl, name) for name in required):
        raise RuntimeError("kernel file leases unavailable")
    signal.signal(signal.SIGIO, lease_break_handler)
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | cloexec | nofollow)
    root_stat = os.fstat(root_fd)
    if not stat.S_ISDIR(root_stat.st_mode) or root_stat.st_uid != uid:
        raise RuntimeError("unsafe pid root")
    marker_name = ".hsqa-pid-root-owned"
    marker_fd = os.open(marker_name, os.O_RDONLY | cloexec | nofollow,
                        dir_fd=root_fd)
    marker_stat = validate_named(marker_fd, marker_name)
    if os.read(marker_fd, 64) != b"hsqa-pid-root-v1\n" or os.read(marker_fd, 1):
        raise RuntimeError("invalid pid-root marker")
    validate_named(marker_fd, marker_name, expected=marker_stat)
    lock_name = f"{role}.pids.lock"
    file_name = f"{role}.pids"
    file_fd = os.open(file_name, os.O_RDONLY | cloexec | nofollow,
                      dir_fd=root_fd)
    initial = validate_named(file_fd, file_name, modes=(0o600,))
    fcntl.fcntl(file_fd, fcntl.F_SETOWN, os.getpid())
    fcntl.fcntl(file_fd, fcntl.F_SETLEASE, fcntl.F_RDLCK)
    lease_held = True
    if (lease_break_requested
            or fcntl.fcntl(file_fd, fcntl.F_GETLEASE) != fcntl.F_RDLCK):
        raise RuntimeError("empty sentinel lease unavailable")
    data = os.read(file_fd, 1)
    final = validate_named(file_fd, file_name, expected=initial, modes=(0o600,))
    validate_named(marker_fd, marker_name, expected=marker_stat)
    if (lease_break_requested
            or fcntl.fcntl(file_fd, fcntl.F_GETLEASE) != fcntl.F_RDLCK
            or final.st_size != initial.st_size
            or final.st_mtime_ns != initial.st_mtime_ns
            or final.st_ctime_ns != initial.st_ctime_ns):
        raise RuntimeError("empty sentinel changed during inspection")
    if data != b"" or final.st_size != 0:
        raise SystemExit(1)
    # Only a prospective clean verdict requires the exact pre-existing role
    # lock. Nonempty recovery authority is already safely red even if a crash
    # happened before its lock was durably published.
    lock_fd = os.open(lock_name, os.O_RDONLY | cloexec | nofollow,
                      dir_fd=root_fd)
    lock_stat = validate_named(lock_fd, lock_name, modes=(0o600, 0o644))
    fcntl.flock(lock_fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
    validate_named(lock_fd, lock_name, expected=lock_stat, modes=(0o600, 0o644))
    validate_named(file_fd, file_name, expected=initial, modes=(0o600,))
    validate_named(marker_fd, marker_name, expected=marker_stat)
    if (lease_break_requested
            or fcntl.fcntl(file_fd, fcntl.F_GETLEASE) != fcntl.F_RDLCK):
        raise RuntimeError("empty sentinel lease broke before clean verdict")
    raise SystemExit(0)
except SystemExit:
    raise
except Exception:
    raise SystemExit(2)
finally:
    if lease_held and file_fd is not None:
        try:
            fcntl.fcntl(file_fd, fcntl.F_SETLEASE, fcntl.F_UNLCK)
        except OSError:
            pass
    for descriptor in (file_fd, lock_fd, marker_fd, root_fd):
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass
PYEOF
}

remove_empty_pidfile() { # <role>; lock-aware and exact
    local role="$1" snapshot header tag dev ino lock_dev lock_ino digest count extra
    local -a snapshot_lines=()
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 1
    if [ ! -e "$PIDDIR/$role.pids" ] && [ ! -L "$PIDDIR/$role.pids" ]; then
        return 0
    fi
    snapshot=$(snapshot_pidfile_records "$role") || return 1
    mapfile -t snapshot_lines <<< "$snapshot"
    [ "${#snapshot_lines[@]}" -eq 1 ] || return 1
    header="${snapshot_lines[0]}"
    IFS='|' read -r tag dev ino lock_dev lock_ino digest count extra <<< "$header"
    [ "$tag" = S ] && [ -z "${extra:-}" ] && [ "$count" = 0 ] \
        && [[ "$dev" =~ ^[0-9]+$ ]] && [[ "$ino" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$lock_dev" =~ ^[0-9]+$ ]] \
        && [[ "$lock_ino" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || return 1
    finalize_pidfile_snapshot "$role" "$dev" "$ino" "$lock_dev" \
        "$lock_ino" "$digest"
}

snapshot_pidfile_records() { # <role>; strict immutable snapshot under exact role lock
    local role="$1"
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 2
    /usr/bin/python3 -I - "$PIDDIR" "$role" <<'PYEOF'
import fcntl
import hashlib
import os
import re
import stat
import sys

root, role = sys.argv[1:]
role_re = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
record_re = re.compile(
    rb"^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$"
)
if not role_re.fullmatch(role):
    raise SystemExit(2)

uid = os.getuid()
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
max_bytes = 131072
max_records = 512
root_fd = marker_fd = lock_fd = file_fd = None

def same_inode(left, right):
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino

def validate_named(fd, name, expected=None, lock=False):
    actual = os.fstat(fd)
    named = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    for value in (actual, named):
        if (not stat.S_ISREG(value.st_mode) or value.st_uid != uid
                or value.st_nlink != 1):
            raise RuntimeError("unsafe inode")
    if not same_inode(actual, named):
        raise RuntimeError("named inode changed")
    if expected is not None and not same_inode(actual, expected):
        raise RuntimeError("open inode changed")
    if lock and stat.S_IMODE(actual.st_mode) not in (0o600, 0o644):
        raise RuntimeError("unsafe lock mode")
    return actual

def read_bounded(fd):
    chunks = []
    total = 0
    while True:
        chunk = os.read(fd, min(65536, max_bytes + 1 - total))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        if total > max_bytes:
            raise RuntimeError("pidfile exceeds bound")
    return b"".join(chunks)

def parse_records(data):
    if not data:
        return []
    if not data.endswith(b"\n"):
        raise RuntimeError("pidfile lacks terminal newline")
    records = data[:-1].split(b"\n")
    if not records or len(records) > max_records or any(not value for value in records):
        raise RuntimeError("invalid pidfile record count")
    if any(record_re.fullmatch(value) is None for value in records):
        raise RuntimeError("malformed pidfile record")
    if len(set(records)) != len(records):
        raise RuntimeError("duplicate pidfile record")
    return records

try:
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | cloexec | nofollow)
    root_stat = os.fstat(root_fd)
    if not stat.S_ISDIR(root_stat.st_mode) or root_stat.st_uid != uid:
        raise RuntimeError("unsafe pid root")
    marker_name = ".hsqa-pid-root-owned"
    marker_fd = os.open(marker_name, os.O_RDONLY | cloexec | nofollow,
                        dir_fd=root_fd)
    marker_stat = validate_named(marker_fd, marker_name)
    if os.read(marker_fd, 64) != b"hsqa-pid-root-v1\n" or os.read(marker_fd, 1):
        raise RuntimeError("invalid pid-root marker")
    validate_named(marker_fd, marker_name, expected=marker_stat)
    lock_name = f"{role}.pids.lock"
    file_name = f"{role}.pids"
    lock_fd = os.open(lock_name, os.O_RDWR | os.O_CREAT | cloexec | nofollow,
                      0o600, dir_fd=root_fd)
    lock_stat = validate_named(lock_fd, lock_name, lock=True)
    fcntl.flock(lock_fd, fcntl.LOCK_EX)
    validate_named(lock_fd, lock_name, expected=lock_stat, lock=True)
    file_fd = os.open(file_name, os.O_RDONLY | cloexec | nofollow,
                      dir_fd=root_fd)
    initial = validate_named(file_fd, file_name)
    if stat.S_IMODE(initial.st_mode) != 0o600:
        raise RuntimeError("unsafe pidfile mode")
    data = read_bounded(file_fd)
    final = validate_named(file_fd, file_name, expected=initial)
    if (final.st_size != initial.st_size or final.st_mtime_ns != initial.st_mtime_ns
            or final.st_ctime_ns != initial.st_ctime_ns):
        raise RuntimeError("pidfile changed during snapshot")
    records = parse_records(data)
    validate_named(marker_fd, marker_name, expected=marker_stat)
    validate_named(lock_fd, lock_name, expected=lock_stat, lock=True)
    validate_named(file_fd, file_name, expected=initial)
    digest = hashlib.sha256(data).hexdigest()
    print(
        f"S|{initial.st_dev}|{initial.st_ino}|{lock_stat.st_dev}|"
        f"{lock_stat.st_ino}|{digest}|{len(records)}"
    )
    for value in records:
        print("R|" + value.decode("ascii"))
except Exception:
    raise SystemExit(2)
finally:
    for descriptor in (file_fd, lock_fd, marker_fd, root_fd):
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass
PYEOF
}

finalize_pidfile_snapshot() { # <role> <dev> <ino> <lock-dev> <lock-ino> <sha256> [records-proven-clear...]
    local role="$1" expected_dev="$2" expected_ino="$3"
    local expected_lock_dev="$4" expected_lock_ino="$5" expected_digest="$6"
    local barrier_kind='' barrier_ready='' barrier_hold='' barrier_root=''
    shift 6
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        && [[ "$expected_dev" =~ ^[0-9]+$ ]] \
        && [[ "$expected_ino" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$expected_lock_dev" =~ ^[0-9]+$ ]] \
        && [[ "$expected_lock_ino" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$expected_digest" =~ ^[0-9a-f]{64}$ ]] || return 2
    if [ "$REAP_SELFTEST_ACTIVE" -eq 1 ]; then
        case "$role" in
            commit-exchange)
                barrier_kind=exchange
                barrier_ready="$HSQA_REAP_PIDFILE_COMMIT_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_COMMIT_HOLD"
                barrier_root="$SELFTEST_ROOT";;
            commit-append)
                barrier_kind=append
                barrier_ready="$HSQA_REAP_PIDFILE_COMMIT_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_COMMIT_HOLD"
                barrier_root="$SELFTEST_ROOT";;
            commit-candidate)
                barrier_kind=candidate
                barrier_ready="$HSQA_REAP_PIDFILE_COMMIT_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_COMMIT_HOLD"
                barrier_root="$SELFTEST_ROOT";;
            commit-private)
                barrier_kind=private
                barrier_ready="$HSQA_REAP_PIDFILE_COMMIT_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_COMMIT_HOLD"
                barrier_root="$SELFTEST_ROOT";;
            commit-unlinked)
                barrier_kind=unlinked
                barrier_ready="$HSQA_REAP_PIDFILE_COMMIT_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_COMMIT_HOLD"
                barrier_root="$SELFTEST_ROOT";;
            commit-dispose)
                barrier_kind=dispose
                barrier_ready="$HSQA_REAP_PIDFILE_COMMIT_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_COMMIT_HOLD"
                barrier_root="$SELFTEST_ROOT";;
            commit-empty)
                barrier_kind=empty
                barrier_ready="$HSQA_REAP_PIDFILE_EMPTY_READY"
                barrier_hold="$HSQA_REAP_PIDFILE_EMPTY_HOLD"
                barrier_root="$SELFTEST_ROOT";;
        esac
    fi
    /usr/bin/python3 -I - "$PIDDIR" "$role" "$expected_dev" \
            "$expected_ino" "$expected_lock_dev" "$expected_lock_ino" \
            "$expected_digest" "$barrier_kind" "$barrier_ready" \
            "$barrier_hold" "$barrier_root" "$@" <<'PYEOF'
import ctypes
import fcntl
import hashlib
import os
import re
import secrets
import signal
import stat
import sys
import time

root, role, expected_dev, expected_ino, expected_lock_dev, expected_lock_ino, \
    expected_digest, barrier_kind, barrier_ready, barrier_hold, barrier_root, \
    *cleared_text = sys.argv[1:]
expected_dev = int(expected_dev)
expected_ino = int(expected_ino)
expected_lock_dev = int(expected_lock_dev)
expected_lock_ino = int(expected_lock_ino)
role_re = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
record_re = re.compile(
    rb"^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$"
)
if (not role_re.fullmatch(role)
        or not re.fullmatch(r"[0-9a-f]{64}", expected_digest)
        or len(cleared_text) > 512):
    raise SystemExit(2)
if barrier_kind:
    expected_role = {
        "exchange": "commit-exchange",
        "append": "commit-append",
        "candidate": "commit-candidate",
        "private": "commit-private",
        "unlinked": "commit-unlinked",
        "dispose": "commit-dispose",
        "empty": "commit-empty",
    }.get(barrier_kind)
    expected_ready = os.path.join(barrier_root, f"{barrier_kind}-commit-ready")
    expected_hold = os.path.join(barrier_root, f"{barrier_kind}-commit-hold")
    expected_pid_root = os.path.join(barrier_root, f"{barrier_kind}-commit-pids")
    if (role != expected_role or root != expected_pid_root
            or not re.fullmatch(r"/tmp/hsqa-tmux-tracked\.reap\.[A-Za-z0-9]{6}",
                                barrier_root)
            or barrier_ready != expected_ready or barrier_hold != expected_hold
            or os.path.realpath(barrier_root) != barrier_root
            or os.path.realpath(root) != root):
        raise SystemExit(2)
elif any((barrier_ready, barrier_hold, barrier_root)):
    raise SystemExit(2)
try:
    cleared = [value.encode("ascii") for value in cleared_text]
except UnicodeEncodeError:
    raise SystemExit(2)
if (any(record_re.fullmatch(value) is None for value in cleared)
        or len(set(cleared)) != len(cleared)):
    raise SystemExit(2)

uid = os.getuid()
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
max_bytes = 131072
root_fd = marker_fd = lock_fd = file_fd = temporary_fd = None
temporary_name = None
temporary_stat = None
original_lease_held = False
candidate_lease_held = False
lease_break_requested = False
previous_sigio_handler = None

def lease_break_handler(_signum, _frame):
    global lease_break_requested
    lease_break_requested = True

def acquire_write_lease(fd):
    required = ("F_SETLEASE", "F_GETLEASE", "F_SETOWN", "F_WRLCK", "F_UNLCK")
    if any(not hasattr(fcntl, name) for name in required):
        raise RuntimeError("kernel file leases unavailable")
    fcntl.fcntl(fd, fcntl.F_SETOWN, os.getpid())
    try:
        fcntl.fcntl(fd, fcntl.F_SETLEASE, fcntl.F_WRLCK)
        if fcntl.fcntl(fd, fcntl.F_GETLEASE) != fcntl.F_WRLCK:
            raise RuntimeError("kernel write lease was not acquired")
    except Exception:
        try:
            fcntl.fcntl(fd, fcntl.F_SETLEASE, fcntl.F_UNLCK)
        except OSError:
            pass
        raise

def leases_intact(require_candidate=False):
    if lease_break_requested or not original_lease_held:
        return False
    try:
        if fcntl.fcntl(file_fd, fcntl.F_GETLEASE) != fcntl.F_WRLCK:
            return False
        if require_candidate and (not candidate_lease_held
                or fcntl.fcntl(temporary_fd, fcntl.F_GETLEASE) != fcntl.F_WRLCK):
            return False
    except OSError:
        return False
    return True

def require_leases_intact(require_candidate=False):
    if not leases_intact(require_candidate):
        raise RuntimeError("pidfile write lease broke")

def same_inode(left, right):
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino

def rename_exchange(first, second):
    libc = ctypes.CDLL(None, use_errno=True)
    function = getattr(libc, "renameat2", None)
    if function is None:
        raise RuntimeError("renameat2 unavailable")
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int,
                         ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(root_fd, os.fsencode(first), root_fd, os.fsencode(second), 2) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))

def link_tmpfile(fd, name):
    libc = ctypes.CDLL(None, use_errno=True)
    function = getattr(libc, "linkat", None)
    if function is None:
        raise RuntimeError("linkat unavailable")
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int,
                         ctypes.c_char_p, ctypes.c_int]
    function.restype = ctypes.c_int
    at_empty_path = 0x1000
    if function(fd, b"", root_fd, os.fsencode(name), at_empty_path) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))

def test_barrier(phase):
    if barrier_kind != phase and not (phase == "exchange" and barrier_kind == "append"):
        return
    ready_fd = os.open(
        barrier_ready,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL | cloexec | nofollow,
        0o600,
    )
    try:
        if os.write(ready_fd, b"ready\n") != 6:
            raise RuntimeError("short barrier write")
        os.fsync(ready_fd)
    finally:
        os.close(ready_fd)
    for _ in range(500):
        if lease_break_requested:
            raise RuntimeError("pidfile lease break requested at test barrier")
        if not os.path.lexists(barrier_hold):
            return
        time.sleep(0.01)
    raise RuntimeError("pidfile commit barrier timed out")

def validate_named(fd, name, expected=None, lock=False):
    actual = os.fstat(fd)
    named = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    for value in (actual, named):
        if (not stat.S_ISREG(value.st_mode) or value.st_uid != uid
                or value.st_nlink != 1):
            raise RuntimeError("unsafe inode")
    if not same_inode(actual, named):
        raise RuntimeError("named inode changed")
    if expected is not None and not same_inode(actual, expected):
        raise RuntimeError("snapshot inode changed")
    if lock and stat.S_IMODE(actual.st_mode) not in (0o600, 0o644):
        raise RuntimeError("unsafe lock mode")
    return actual

def read_bounded(fd):
    chunks = []
    total = 0
    while True:
        chunk = os.read(fd, min(65536, max_bytes + 1 - total))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        if total > max_bytes:
            raise RuntimeError("pidfile exceeds bound")
    return b"".join(chunks)

def parse_records(data):
    if not data:
        return []
    if not data.endswith(b"\n"):
        raise RuntimeError("pidfile lacks terminal newline")
    values = data[:-1].split(b"\n")
    if (not values or len(values) > 512 or any(not value for value in values)
            or any(record_re.fullmatch(value) is None for value in values)
            or len(set(values)) != len(values)):
        raise RuntimeError("malformed pidfile snapshot")
    return values

try:
    previous_sigio_handler = signal.getsignal(signal.SIGIO)
    signal.signal(signal.SIGIO, lease_break_handler)
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | cloexec | nofollow)
    root_stat = os.fstat(root_fd)
    if not stat.S_ISDIR(root_stat.st_mode) or root_stat.st_uid != uid:
        raise RuntimeError("unsafe pid root")
    marker_name = ".hsqa-pid-root-owned"
    marker_fd = os.open(marker_name, os.O_RDONLY | cloexec | nofollow,
                        dir_fd=root_fd)
    marker_stat = validate_named(marker_fd, marker_name)
    if os.read(marker_fd, 64) != b"hsqa-pid-root-v1\n" or os.read(marker_fd, 1):
        raise RuntimeError("invalid pid-root marker")
    validate_named(marker_fd, marker_name, expected=marker_stat)
    lock_name = f"{role}.pids.lock"
    file_name = f"{role}.pids"
    lock_fd = os.open(lock_name, os.O_RDWR | os.O_CREAT | cloexec | nofollow,
                      0o600, dir_fd=root_fd)
    lock_stat = validate_named(lock_fd, lock_name, lock=True)
    if lock_stat.st_dev != expected_lock_dev or lock_stat.st_ino != expected_lock_ino:
        raise RuntimeError("role lock replaced after snapshot")
    fcntl.flock(lock_fd, fcntl.LOCK_EX)
    validate_named(lock_fd, lock_name, expected=lock_stat, lock=True)
    file_fd = os.open(file_name, os.O_RDWR | cloexec | nofollow,
                      dir_fd=root_fd)
    initial = validate_named(file_fd, file_name)
    if stat.S_IMODE(initial.st_mode) != 0o600:
        raise RuntimeError("unsafe pidfile mode")
    if initial.st_dev != expected_dev or initial.st_ino != expected_ino:
        raise RuntimeError("named pidfile replaced after snapshot")
    acquire_write_lease(file_fd)
    original_lease_held = True
    require_leases_intact()
    data = read_bounded(file_fd)
    after_read = validate_named(file_fd, file_name, expected=initial)
    if (hashlib.sha256(data).hexdigest() != expected_digest
            or after_read.st_size != initial.st_size
            or after_read.st_mtime_ns != initial.st_mtime_ns
            or after_read.st_ctime_ns != initial.st_ctime_ns):
        raise RuntimeError("pidfile bytes changed after snapshot")
    require_leases_intact()
    records = parse_records(data)
    cleared = set(cleared)
    if not cleared.issubset(set(records)):
        raise RuntimeError("clear roster is not in immutable snapshot")
    remaining = [value for value in records if value not in cleared]
    validate_named(marker_fd, marker_name, expected=marker_stat)
    validate_named(lock_fd, lock_name, expected=lock_stat, lock=True)
    validate_named(file_fd, file_name, expected=initial)
    encoded = b"\n".join(remaining) + (b"\n" if remaining else b"")
    if len(encoded) > max_bytes or len(remaining) > 512:
        raise RuntimeError("replacement pidfile exceeds canonical bounds")
    if not hasattr(os, "O_TMPFILE"):
        raise RuntimeError("anonymous candidate files unavailable")
    temporary_name = f".{file_name}.reap.{secrets.token_hex(12)}"
    temporary_fd = os.open(
        ".", os.O_RDWR | os.O_TMPFILE | cloexec, 0o000, dir_fd=root_fd,
    )
    anonymous_stat = os.fstat(temporary_fd)
    if (not stat.S_ISREG(anonymous_stat.st_mode)
            or anonymous_stat.st_uid != uid or anonymous_stat.st_nlink != 0
            or stat.S_IMODE(anonymous_stat.st_mode) != 0):
        raise RuntimeError("unsafe anonymous candidate inode")
    # The unlinked selftest barrier proves there is no discoverable private
    # candidate name before the lease and first byte. Production executes the
    # same path without a barrier.
    test_barrier("unlinked")
    acquire_write_lease(temporary_fd)
    candidate_lease_held = True
    require_leases_intact(require_candidate=True)
    os.fchmod(temporary_fd, 0o600)
    os.lseek(temporary_fd, 0, os.SEEK_SET)
    offset = 0
    while offset < len(encoded):
        written = os.write(temporary_fd, encoded[offset:])
        if written <= 0:
            raise RuntimeError("short replacement write")
        offset += written
    os.fsync(temporary_fd)
    candidate_before = os.fstat(temporary_fd)
    os.lseek(temporary_fd, 0, os.SEEK_SET)
    candidate_data = read_bounded(temporary_fd)
    candidate_after = os.fstat(temporary_fd)
    if (not stat.S_ISREG(candidate_after.st_mode)
            or candidate_after.st_uid != uid or candidate_after.st_nlink != 0
            or stat.S_IMODE(candidate_after.st_mode) != 0o600
            or candidate_data != encoded or candidate_after.st_size != len(encoded)
            or candidate_after.st_size != candidate_before.st_size
            or candidate_after.st_mtime_ns != candidate_before.st_mtime_ns
            or candidate_after.st_ctime_ns != candidate_before.st_ctime_ns):
        raise RuntimeError("unsafe replacement inode")
    require_leases_intact(require_candidate=True)
    link_tmpfile(temporary_fd, temporary_name)
    temporary_stat = validate_named(temporary_fd, temporary_name)
    if (not same_inode(temporary_stat, candidate_after)
            or stat.S_IMODE(temporary_stat.st_mode) != 0o600):
        raise RuntimeError("linked candidate identity changed")
    os.fsync(root_fd)
    # Active same-UID mutation at the first named exposure is held behind the
    # candidate write lease and must abort before the public authority exchange.
    test_barrier("private")
    candidate_before_linked = os.fstat(temporary_fd)
    os.lseek(temporary_fd, 0, os.SEEK_SET)
    linked_data = read_bounded(temporary_fd)
    candidate_after_linked = validate_named(
        temporary_fd, temporary_name, expected=temporary_stat
    )
    if (linked_data != encoded or candidate_after_linked.st_size != len(encoded)
            or candidate_after_linked.st_size != candidate_before_linked.st_size
            or candidate_after_linked.st_mtime_ns != candidate_before_linked.st_mtime_ns
            or candidate_after_linked.st_ctime_ns != candidate_before_linked.st_ctime_ns):
        raise RuntimeError("linked candidate bytes or metadata changed")
    require_leases_intact(require_candidate=True)
    validate_named(file_fd, file_name, expected=initial)
    validate_named(marker_fd, marker_name, expected=marker_stat)
    validate_named(lock_fd, lock_name, expected=lock_stat, lock=True)
    require_leases_intact(require_candidate=True)
    test_barrier("exchange")
    require_leases_intact(require_candidate=True)

    # Atomic compare-and-swap: after the exchange, the private random name
    # must contain the exact inode snapshotted above and the public name must
    # contain our exact candidate.  If an actor ignored the role lock and
    # replaced the public name at the commit boundary, exchange back before
    # reporting failure; never unlink that foreign inode.
    rename_exchange(temporary_name, file_name)
    os.fsync(root_fd)

    swapped_old = os.stat(temporary_name, dir_fd=root_fd,
                          follow_symlinks=False)
    swapped_new = os.stat(file_name, dir_fd=root_fd,
                          follow_symlinks=False)

    def verified_rollback(observed_old, observed_new):
        # Roll back only when the two public/private names still designate the
        # exact two inodes observed immediately after our exchange, and the
        # public side is still our candidate. The private side may be a raced
        # replacement rather than the original FD; exchanging the exact pair
        # back restores that foreign authority to the public name. If either
        # name changed again, leave both untouched so the root remains red.
        current_old = os.stat(temporary_name, dir_fd=root_fd,
                              follow_symlinks=False)
        current_new = os.stat(file_name, dir_fd=root_fd,
                              follow_symlinks=False)
        if (not same_inode(observed_new, temporary_stat)
                or not same_inode(current_old, observed_old)
                or not same_inode(current_new, observed_new)):
            raise RuntimeError("commit names changed before safe rollback")
        rename_exchange(temporary_name, file_name)
        restored_old = os.stat(file_name, dir_fd=root_fd, follow_symlinks=False)
        restored_candidate = os.stat(temporary_name, dir_fd=root_fd,
                                     follow_symlinks=False)
        if (not same_inode(restored_old, observed_old)
                or not same_inode(restored_candidate, observed_new)):
            raise RuntimeError("commit rollback identity mismatch")
        os.fsync(root_fd)

    # A writer opening the newly-public candidate must request a break on the
    # candidate lease before it can return from open(2). The test barrier makes
    # that boundary deterministic; production relies on the same sticky SIGIO
    # flag plus synchronous F_GETLEASE checks.
    test_barrier("candidate")

    commit_error = None
    try:
        require_leases_intact(require_candidate=True)
        post_exchange_before = os.fstat(file_fd)
        os.lseek(file_fd, 0, os.SEEK_SET)
        post_exchange_data = read_bounded(file_fd)
        post_exchange_old = os.fstat(file_fd)
        if (not same_inode(swapped_old, initial)
                or not same_inode(swapped_old, post_exchange_old)
                or not stat.S_ISREG(swapped_old.st_mode)
                or swapped_old.st_uid != uid or swapped_old.st_nlink != 1
                or post_exchange_data != data
                or hashlib.sha256(post_exchange_data).hexdigest() != expected_digest
                or post_exchange_old.st_size != initial.st_size
                or post_exchange_old.st_mtime_ns != initial.st_mtime_ns
                or post_exchange_old.st_size != post_exchange_before.st_size
                or post_exchange_old.st_mtime_ns != post_exchange_before.st_mtime_ns
                or post_exchange_old.st_ctime_ns != post_exchange_before.st_ctime_ns
                or not same_inode(swapped_new, temporary_stat)
                or not stat.S_ISREG(swapped_new.st_mode)
                or swapped_new.st_uid != uid or swapped_new.st_nlink != 1):
            raise RuntimeError("pidfile commit metadata changed")
        validate_named(marker_fd, marker_name, expected=marker_stat)
        validate_named(lock_fd, lock_name, expected=lock_stat, lock=True)
        require_leases_intact(require_candidate=True)
    except Exception as error:
        commit_error = error
    if commit_error is not None:
        if leases_intact(require_candidate=True):
            verified_rollback(swapped_old, swapped_new)
        else:
            # Once either lease has requested a break, do not perform another
            # destructive exchange. Keep both public candidate and private
            # original linked as deliberate red recovery evidence.
            os.fsync(root_fd)
        raise RuntimeError(
            "pidfile bytes, metadata, authority, or lease changed during final commit"
        ) from commit_error

    # The old inode is now behind an unpredictable private name and has been
    # identity-checked after the exchange.  Removing it cannot remove a
    # replacement that raced at the public pidfile name.
    test_barrier("dispose")
    require_leases_intact(require_candidate=True)
    private_old = validate_named(file_fd, temporary_name, expected=initial)
    if (not same_inode(private_old, swapped_old)
            or private_old.st_size != post_exchange_old.st_size
            or private_old.st_mtime_ns != post_exchange_old.st_mtime_ns
            or private_old.st_ctime_ns != post_exchange_old.st_ctime_ns):
        raise RuntimeError("private original changed before disposal")
    require_leases_intact(require_candidate=True)
    os.unlink(temporary_name, dir_fd=root_fd)
    temporary_name = None
    os.fsync(root_fd)
    require_leases_intact(require_candidate=True)

    # Reopening by the public name would ask the kernel to break our own write
    # lease. Verify the published candidate through the already-open leased FD.
    candidate_before = os.fstat(temporary_fd)
    os.lseek(temporary_fd, 0, os.SEEK_SET)
    candidate_data = read_bounded(temporary_fd)
    candidate_after = validate_named(
        temporary_fd, file_name, expected=temporary_stat
    )
    if (candidate_data != encoded
            or candidate_after.st_size != len(encoded)
            or candidate_after.st_size != candidate_before.st_size
            or candidate_after.st_mtime_ns != candidate_before.st_mtime_ns
            or candidate_after.st_ctime_ns != candidate_before.st_ctime_ns):
        raise RuntimeError("published replacement bytes or metadata differ")
    require_leases_intact(require_candidate=True)
    if not remaining:
        # The exact 0-byte candidate is a permanent clean sentinel. Never
        # unlink it: an open that is blocked on this write lease may complete
        # after lease release. Keeping the inode linked guarantees that any
        # resulting record remains observable and makes the recovery root red.
        test_barrier("empty")
        validate_named(temporary_fd, file_name, expected=temporary_stat)
        if os.fstat(temporary_fd).st_size != 0:
            raise RuntimeError("empty pidfile sentinel became nonempty")
        require_leases_intact(require_candidate=True)
    os.fsync(root_fd)
    require_leases_intact(require_candidate=True)
except Exception as error:
    print(
        f"pidfile finalize refused: {type(error).__name__}: {error}",
        file=sys.stderr,
    )
    raise SystemExit(1)
finally:
    if (not lease_break_requested and temporary_name is not None
            and temporary_stat is not None
            and root_fd is not None):
        try:
            current_temporary = os.stat(temporary_name, dir_fd=root_fd,
                                        follow_symlinks=False)
            if (same_inode(current_temporary, temporary_stat)
                    and stat.S_ISREG(current_temporary.st_mode)
                    and current_temporary.st_uid == uid
                    and current_temporary.st_nlink == 1):
                os.unlink(temporary_name, dir_fd=root_fd)
        except OSError:
            pass
    lease_cleanup_failed = False
    for descriptor, held in (
            (temporary_fd, candidate_lease_held),
            (file_fd, original_lease_held)):
        if held and descriptor is not None:
            try:
                fcntl.fcntl(descriptor, fcntl.F_SETLEASE, fcntl.F_UNLCK)
            except OSError:
                lease_cleanup_failed = True
    if previous_sigio_handler is not None:
        try:
            signal.signal(signal.SIGIO, previous_sigio_handler)
        except (OSError, RuntimeError, ValueError):
            lease_cleanup_failed = True
    for descriptor in (temporary_fd, file_fd, lock_fd, marker_fd, root_fd):
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass
    if lease_cleanup_failed:
        raise SystemExit(1)
PYEOF
}

reap_pidfile_swap_selftest_barrier() { # <role>; internal executable race seam
    local role="$1" path lexical canonical attempt
    if [ -z "$HSQA_REAP_PIDFILE_SWAP_READY$HSQA_REAP_PIDFILE_SWAP_HOLD" ]; then
        return 0
    fi
    [ "$REAP_SELFTEST_ACTIVE" -eq 1 ] \
        && [ -n "$HSQA_REAP_PIDFILE_SWAP_READY" ] \
        && [ -n "$HSQA_REAP_PIDFILE_SWAP_HOLD" ] \
        && [ "$role" = swap-race ] || return 2
    for path in "$HSQA_REAP_PIDFILE_SWAP_READY" "$HSQA_REAP_PIDFILE_SWAP_HOLD"; do
        lexical=$(/usr/bin/realpath -ms -- "$path") || return 2
        canonical=$(/usr/bin/realpath -m -- "$path") || return 2
        [ "$lexical" = "$canonical" ] \
            && case "$canonical" in "$SELFTEST_ROOT"/*) true;; *) false;; esac \
            || return 2
    done
    builtin printf 'ready\n' > "$HSQA_REAP_PIDFILE_SWAP_READY" || return 2
    for attempt in $(seq 1 500); do
        [ -e "$HSQA_REAP_PIDFILE_SWAP_HOLD" ] || return 0
        /bin/sleep 0.01
    done
    return 2
}

reap_pidfile_records() {
    local files status f role record failed=0 snapshot header tag dev ino digest
    local lock_dev lock_ino expected_count extra index finalize_status
    local -a snapshot_lines=() cleared_records=()
    if [ ! -e "$PIDDIR" ] && [ ! -L "$PIDDIR" ]; then return 0; fi
    hsqa_require_owned_directory "$PIDDIR" \
        .hsqa-pid-root-owned hsqa-pid-root-v1 >/dev/null 2>&1 || return 1
    files=$(/usr/bin/find -P "$PIDDIR" -mindepth 1 -maxdepth 1 \
        -type f -name '*.pids' -print 2>/dev/null); status=$?
    [ "$status" -eq 0 ] || return 1
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        role="${f##*/}"; role="${role%.pids}"
        [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
            || { echo "  REFUSING malformed pidfile role $f"; failed=1; continue; }
        snapshot=$(snapshot_pidfile_records "$role"); status=$?
        if [ "$status" -ne 0 ]; then
            echo "  REFUSING unsafe, replaced, oversized, or malformed pidfile $f"
            failed=1
            continue
        fi
        mapfile -t snapshot_lines <<< "$snapshot"
        [ "${#snapshot_lines[@]}" -ge 1 ] \
            || { echo "  REFUSING empty snapshot protocol for $f"; failed=1; continue; }
        header="${snapshot_lines[0]}"
        IFS='|' read -r tag dev ino lock_dev lock_ino digest expected_count extra <<< "$header"
        if [ "$tag" != S ] || [ -n "${extra:-}" ] \
                || ! [[ "$dev" =~ ^[0-9]+$ ]] \
                || ! [[ "$ino" =~ ^[1-9][0-9]*$ ]] \
                || ! [[ "$lock_dev" =~ ^[0-9]+$ ]] \
                || ! [[ "$lock_ino" =~ ^[1-9][0-9]*$ ]] \
                || ! [[ "$digest" =~ ^[0-9a-f]{64}$ ]] \
                || ! [[ "$expected_count" =~ ^(0|[1-9][0-9]{0,2})$ ]] \
                || [ "$expected_count" -gt 512 ] \
                || [ "${#snapshot_lines[@]}" -ne $((expected_count + 1)) ]; then
            echo "  REFUSING malformed snapshot protocol for $f"
            failed=1
            continue
        fi
        if ! reap_pidfile_swap_selftest_barrier "$role"; then
            echo "  REFUSING pidfile selftest barrier failure for $f"
            failed=1
            continue
        fi
        cleared_records=()
        for ((index=1; index<${#snapshot_lines[@]}; index++)); do
            case "${snapshot_lines[$index]}" in R\|*) record="${snapshot_lines[$index]#R|}";;
                *) echo "  REFUSING malformed snapshot record for $f"; failed=1; continue 2;;
            esac
            if kill_verified_record "$record" "$f"; then
                cleared_records+=("$record")
            else
                failed=1
            fi
        done
        if [ "$expected_count" -gt 0 ] && [ "${#cleared_records[@]}" -eq 0 ]; then
            continue
        fi
        finalize_pidfile_snapshot "$role" "$dev" "$ino" "$lock_dev" \
            "$lock_ino" "$digest" \
            "${cleared_records[@]}"; finalize_status=$?
        if [ "$finalize_status" -ne 0 ]; then
            echo "  REFUSING to mutate replaced/changed pidfile $f; recovery state retained"
            failed=1
        fi
    done <<< "$files"
    return "$failed"
}


proc_port_listening() { # <port>; 0 listener, 1 free, 2 /proc observation error
    local port="$1" hex file rows status found=1
    hsqa_validate_port "$port" || return 2
    printf -v hex '%04X' "$port"
    for file in /proc/net/tcp /proc/net/tcp6; do
        [ -r "$file" ] || return 2
        rows=$(/usr/bin/awk -v wanted="$hex" '
            NR == 1 { next }
            {
                split($2, address, ":")
                if (length(address) != 2 || $4 !~ /^[0-9A-Fa-f][0-9A-Fa-f]$/) {
                    bad=1; exit
                }
                if (toupper(address[2]) == wanted && toupper($4) == "0A") found=1
            }
            END { if (bad) exit 2; if (found) exit 0; exit 1 }
        ' "$file" 2>/dev/null); status=$?
        case "$status" in 0) found=0;; 1) ;; *) return 2;; esac
    done
    return "$found"
}

port_holder() { # <port> -> "PID CMD" if held, empty if proven free
    local port="$1" output status pid cmd
    hsqa_validate_port "$port" || return 2
    proc_port_listening "$port"; status=$?
    case "$status" in
        1) return 0;;
        2) return 2;;
    esac
    if [ -x /usr/bin/lsof ]; then
        output=$(/usr/bin/lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null); status=$?
        if [ "$status" -eq 0 ]; then
            while IFS= read -r pid; do
                [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 2
            done <<< "$output"
            pid=$(printf '%s\n' "$output" | /usr/bin/head -1)
            [ -n "$pid" ] || return 2
            cmd=$(/usr/bin/ps -o args= -p "$pid" 2>/dev/null) || cmd='(cmdline unavailable)'
            printf '%s %s\n' "$pid" "$cmd"
            return 0
        fi
        [ "$status" -eq 1 ] || return 2
    fi
    printf '? listener-visible-in-/proc\n'
}

tmux_sessions_present() {
    local sessions id name extra
    sessions=$(hsqa_snapshot_tmux_sessions) || return 2
    [ -n "$sessions" ] || return 0
    # Re-read names from the same canonical socket; any session on this
    # dedicated socket is harness state and keeps check/gate red.
    sessions=$(hsqa_tmux list-sessions -F '#{session_id}|#{session_name}' \
        2>/dev/null) || return 2
    while IFS= read -r id; do
        IFS='|' read -r id name extra <<< "$id"
        [ -z "${extra:-}" ] && [[ "$id" =~ ^\$[0-9]+$ ]] \
            && hsqa_validate_tmux_name "$name" || return 2
        printf '%s|%s\n' "$id" "$name"
    done <<< "$sessions"
}

reap_harness_tmux_sessions() {
    local sessions line id name extra owner failed=0
    sessions=$(tmux_sessions_present) || return 1
    [ -n "$sessions" ] || { hsqa_finalize_empty_tmux_socket; return $?; }
    while IFS= read -r line; do
        IFS='|' read -r id name extra <<< "$line"
        [ -z "${extra:-}" ] || { failed=1; continue; }
        case "$name" in
            hsqa-playtest) owner=playtest-tmux;;
            hsqa-blessing-restart) owner=restart-tmux;;
            *)
                echo "  REFUSING unowned/legacy tmux session $id ($name)"
                failed=1
                continue;;
        esac
        if hsqa_stop_owned_tmux_session "$id" "$owner"; then
            echo "  stopped exact owned tmux control pane $id ($name)"
        else
            echo "  REFUSING tmux session $id ($name): ownership/topology not exact"
            failed=1
        fi
    done <<< "$sessions"
    return "$failed"
}

cmd_check() {
    local dirty=0 uncertain=0 s sessions pid_root_status
    local procs
    if [ "$REAP_FIXTURE_ACTIVE" -eq 1 ]; then
        pid_root_inspect
        return $?
    fi
    procs=$(matching_procs); local proc_status=$?
    if [ "$proc_status" -ne 0 ]; then
        echo "PROCESS OBSERVABILITY ERROR: ps/process classification failed"
        dirty=1
        uncertain=1
        procs=''
    fi
    if [ -n "$procs" ]; then
        echo "LEAKED PROCESSES:"
        echo "$procs" | sed 's/^/  /'
        dirty=1
    else
        echo "no leaked harness processes"
    fi
    pid_root_inspect; pid_root_status=$?
    case "$pid_root_status" in
        0) ;;
        1) dirty=1;;
        *) dirty=1; uncertain=1;;
    esac
    if [ -x /usr/bin/tmux ]; then
        sessions=$(tmux_sessions_present); local tmux_status=$?
        if [ "$tmux_status" -ne 0 ]; then
            echo "TMUX OBSERVABILITY ERROR: canonical socket could not be read"
            dirty=1
            uncertain=1
            sessions=''
        fi
        if [ -n "$sessions" ]; then
            while IFS= read -r s; do
                [ -n "$s" ] && echo "HARNESS TMUX SESSION PRESENT: $s"
            done <<< "$sessions"
            dirty=1
        fi
    else
        echo "REQUIRED TOOL MISSING: tmux (session cleanliness cannot be proven)"
        dirty=1
        uncertain=1
    fi
    for p in $PORTS; do
        local h port_status; h=$(port_holder "$p"); port_status=$?
        if [ "$port_status" -ne 0 ]; then
            echo "PORT $p OBSERVABILITY ERROR"
            dirty=1
            uncertain=1
            continue
        fi
        if [ -n "$h" ]; then
            echo "PORT $p HELD by: $h"
            dirty=1
        else
            echo "port $p free"
        fi
    done
    [ "$uncertain" -eq 0 ] || return 2
    return "$dirty"
}

cmd_dryrun() {
    echo "-- pidfiles in $PIDDIR --"
    local files file_status f procs proc_status check_status
    if [ -d "$PIDDIR" ] && [ ! -L "$PIDDIR" ]; then
        files=$(/usr/bin/find -P "$PIDDIR" -mindepth 1 -maxdepth 1 \
            -type f -name '*.pids' -print 2>/dev/null); file_status=$?
        [ "$file_status" -eq 0 ] || return 2
        while IFS= read -r f; do
            [ -n "$f" ] || continue
            plain_owned_file "$f" || return 2
            echo "$f:"
            /usr/bin/sed 's/^/  would evaluate record /' "$f" 2>/dev/null \
                || return 2
        done <<< "$files"
    else
        echo "  (none)"
    fi
    if [ "$REAP_FIXTURE_ACTIVE" -eq 1 ]; then
        pid_root_inspect >/dev/null
        file_status=$?
        [ "$file_status" -ne 2 ] || return 2
        return 0
    fi
    echo "-- pattern fallback would additionally match --"
    procs=$(matching_procs); proc_status=$?
    [ "$proc_status" -eq 0 ] || return 2
    [ -n "$procs" ] && echo "$procs" | sed 's/^/  /' || echo "  (none)"
    echo "-- would then verify: --"
    cmd_check; check_status=$?
    [ "$check_status" -ne 2 ] || return 2
    return 0   # leaks are informational; observation failures are not
}

cmd_reap() {
    echo "reap: pidfiles first"
    reap_pidfile_records || return 1
    if [ "$REAP_FIXTURE_ACTIVE" -eq 1 ]; then
        echo "reap: authenticated fixture is pidfile-only"
        pid_root_inspect
        return $?
    fi
    echo "reap: pattern fallback"
    local procs pid rest attempt fallback_failed=0 s proc_status
    local authorization second start identity current
    procs=$(matching_procs); proc_status=$?
    [ "$proc_status" -eq 0 ] \
        || { echo "  REFUSING fallback: process observation failed"; return 1; }
    if [ -n "$procs" ]; then
        while read -r pid rest; do
            [ -z "$pid" ] && continue
            authorization=$(fallback_process_authorized "$pid" "$rest"); proc_status=$?
            if [ "$proc_status" -eq 1 ] && [ ! -e "/proc/$pid" ]; then
                continue
            fi
            if [ "$proc_status" -ne 0 ]; then
                echo "  REFUSING fallback pid $pid: exact argv/marker/identity not proven ($rest)"
                fallback_failed=1
                continue
            fi
            second=$(fallback_process_authorized "$pid" "$rest"); proc_status=$?
            if [ "$proc_status" -eq 1 ] && [ ! -e "/proc/$pid" ]; then
                continue
            fi
            if [ "$proc_status" -ne 0 ] || [ "$second" != "$authorization" ]; then
                echo "  REFUSING fallback pid $pid: identity changed at kill boundary"
                fallback_failed=1
                continue
            fi
            IFS='|' read -r start identity extra <<< "$authorization"
            [ -z "${extra:-}" ] && [[ "$start" =~ ^[0-9]+$ ]] \
                || { fallback_failed=1; continue; }
            kill -9 "$pid" 2>/dev/null || true
            for ((attempt=1; attempt<=100; attempt++)); do
                current=$(pid_starttime "$pid")
                [ -z "$current" ] || [ "$current" != "$start" ] && break
                /bin/sleep 0.01
            done
            current=$(pid_starttime "$pid")
            if [ "$current" = "$start" ]; then
                echo "  REFUSING to claim cleanup: fallback pid $pid survived SIGKILL"
                fallback_failed=1
            elif [ -z "$current" ] && [ -e "/proc/$pid" ]; then
                echo "  REFUSING to claim cleanup: fallback pid $pid became unobservable"
                fallback_failed=1
            else
                echo "  killed exact authorized fallback pid $pid ($rest)"
            fi
        done <<< "$procs"
    fi
    # The shared tmux helper revalidates immutable session/pane/process
    # identity and kills only the exact owned control pane. It never uses a
    # reusable session/window name as destructive authority.
    reap_harness_tmux_sessions || fallback_failed=1
    /bin/sleep 1
    echo "reap: verifying"
    cmd_check || return 1
    [ "$fallback_failed" -eq 0 ]
}

reap_tree_snapshot() { # <small fixture root>; bytes/inode/mtime/entries, no atime
    /usr/bin/python3 -I - "$1" <<'PYEOF'
import hashlib, os, stat, sys
root = sys.argv[1]
result = hashlib.sha256()
for current, dirs, files in os.walk(root, topdown=True, followlinks=False):
    dirs.sort(); files.sort()
    for name in ["."] + dirs + files:
        path = current if name == "." else os.path.join(current, name)
        value = os.lstat(path)
        result.update(os.path.relpath(path, root).encode() + b"\0")
        result.update((f"{value.st_dev}:{value.st_ino}:{value.st_mode}:"
                       f"{value.st_nlink}:{value.st_uid}:{value.st_size}:"
                       f"{value.st_mtime_ns}").encode() + b"\0")
        if stat.S_ISREG(value.st_mode):
            with open(path, "rb") as handle:
                result.update(hashlib.sha256(handle.read()).digest())
        elif stat.S_ISLNK(value.st_mode):
            result.update(os.readlink(path).encode())
print(result.hexdigest())
PYEOF
}

cmd_selftest() {
    REAP_SELFTEST_ACTIVE=1
    local HSQA_REAP_PIDFILE_SWAP_READY='' HSQA_REAP_PIDFILE_SWAP_HOLD=''
    # Finding 10: feed synthetic ps-style lines through matching_procs()
    # ITSELF (via HSQA_REAP_TEST_INPUT) — the exact function cmd_check,
    # cmd_dryrun and cmd_reap all call — not a locally re-typed stand-in
    # pattern that could silently diverge from the real matcher.
    local fixture
    fixture=$(cat <<'EOF'
12345 /usr/bin/java -Dhsqa.instanceDir=/tmp/claude-0/hsqa-inst/dedicated net.minecraft.server.Main nogui
12346 Xvfb :98 -screen 0 1280x720x24
12347 /usr/bin/tmux new-session -s hsqa-live
12349 /usr/bin/tmux new-session -s hsqa-playtest
12350 /usr/bin/tmux new-session -s hsqa-blessing-restart
12348 /usr/local/bin/python3 /home/user/Verk-arbeid-/qa/scripts/analyze_trace.py
22375 java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3
22999 /usr/bin/java -Dhsqa.instanceDir=/tmp/claude-0/hsqa-inst-v2/performance net.minecraft.server.Main nogui
23001 /bin/bash -c echo hsqa-install backup moved aside
23002 /usr/bin/java -Dhsqa.instanceDir=/tmp/claude-0/hsqa-install-v2/foo unrelated.Main
23003 /usr/bin/java -Dhsqa.instanceDir=/tmp/unrelated/hsqa-inst-v2/foo unrelated.Main
23004 /usr/bin/java -Dhsqa.instanceDir=/tmp/custom-safe/instances/custom unrelated.Main
EOF
)
    local matched
    matched=$(HSQA_REAP_TEST_INPUT="$fixture" \
        HSQA_REAP_TEST_OWNED_INSTANCES=$'/tmp/claude-0/hsqa-inst-v2/performance performance\n/tmp/custom-safe/instances/custom custom' \
        matching_procs)
    local ok=1
    echo "$matched" | grep -q '^12345' && { echo "FAIL: unowned legacy instance must not match line 12345"; ok=0; }
    echo "$matched" | grep -q '^12346' || { echo "FAIL: should match Xvfb :98 line 12346"; ok=0; }
    echo "$matched" | grep -Eq '^123(47|49|50)' \
        && { echo "FAIL: process fallback must not infer tmux ownership from argv"; ok=0; }
    echo "$matched" | grep -q '^22999' || { echo "FAIL: should match v2 instanceDir token line 22999"; ok=0; }
    echo "$matched" | grep -q '^23001' && { echo "FAIL: must NEVER match a bare 'hsqa-install' mention (substring false-positive, proven live)"; ok=0; }
    echo "$matched" | grep -q '^23002' && { echo "FAIL: must NEVER match hsqa-install-v2 instanceDir"; ok=0; }
    echo "$matched" | grep -q '^23003' && { echo "FAIL: must NEVER match an unrelated /tmp instance root"; ok=0; }
    echo "$matched" | grep -q '^23004' || { echo "FAIL: marker-authorized custom instance root should match"; ok=0; }
    echo "$matched" | grep -q '^22375' && { echo "FAIL: must NEVER match the GradleDaemon line 22375"; ok=0; }
    echo "$matched" | grep -q '^12348' && { echo "FAIL: should not match an unrelated python process"; ok=0; }
    HSQA_REAP_TEST_INPUT='not-a-pid malformed observer row' matching_procs \
        >/dev/null 2>&1
    [ $? -eq 2 ] \
        || { echo "FAIL: malformed ps observation was not tri-state error"; ok=0; }
    HSQA_REAP_TEST_GROUPS='malformed group row' \
        pid_record_disposition '-4242|4242|111|perf-server' >/dev/null 2>&1
    [ $? -eq 2 ] \
        || { echo "FAIL: malformed process-group observation was not tri-state error"; ok=0; }
    local saved_proc_port
    saved_proc_port=$(declare -f proc_port_listening) || return 1
    proc_port_listening() { return 2; }
    port_holder 25572 >/dev/null 2>&1
    [ $? -eq 2 ] \
        || { echo "FAIL: port observation error was not tri-state"; ok=0; }
    eval "$saved_proc_port"

    # Empty pidfile cleanup must not open a hostile lock with shell `>>`:
    # redirection follows a final symlink before any later ownership check.
    # Exercise both symlink and hardlink variants against an external sentinel,
    # then prove the same code removes an ordinary empty pidfile.
    local empty_pid_root="$SELFTEST_ROOT/empty-pid-root"
    local empty_external="$SELFTEST_ROOT/empty-pid-external"
    local empty_previous_pid_dir="$PIDDIR"
    /usr/bin/mkdir -m 700 -- "$empty_pid_root" || return 1
    (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
        > "$empty_pid_root/.hsqa-pid-root-owned") || return 1
    builtin printf 'external\n' > "$empty_external" || return 1
    PIDDIR="$empty_pid_root"
    (umask 077; : > "$PIDDIR/empty.pids")
    /bin/ln -s -- "$empty_external" "$PIDDIR/empty.pids.lock" || return 1
    if remove_empty_pidfile empty >/dev/null 2>&1; then
        echo "FAIL: empty-pid cleanup followed a lock symlink"; ok=0
    fi
    /usr/bin/grep -Fxq external "$empty_external" \
        || { echo "FAIL: lock-symlink target was modified"; ok=0; }
    [ -f "$PIDDIR/empty.pids" ] && [ ! -L "$PIDDIR/empty.pids" ] \
        || { echo "FAIL: lock-symlink refusal dropped the empty pidfile"; ok=0; }
    /usr/bin/rm -f -- "$PIDDIR/empty.pids.lock"
    /bin/ln -- "$empty_external" "$PIDDIR/empty.pids.lock" || return 1
    if remove_empty_pidfile empty >/dev/null 2>&1; then
        echo "FAIL: empty-pid cleanup adopted a lock hardlink"; ok=0
    fi
    /usr/bin/grep -Fxq external "$empty_external" \
        || { echo "FAIL: lock-hardlink target was modified"; ok=0; }
    /usr/bin/rm -f -- "$PIDDIR/empty.pids.lock"
    remove_empty_pidfile empty >/dev/null \
        || { echo "FAIL: safe empty-pid cleanup failed"; ok=0; }
    [ -f "$PIDDIR/empty.pids" ] && [ ! -L "$PIDDIR/empty.pids" ] \
        && [ ! -s "$PIDDIR/empty.pids" ] \
        && [ "$(/usr/bin/stat -c '%a:%h' -- "$PIDDIR/empty.pids" 2>/dev/null)" = 600:1 ] \
        || { echo "FAIL: safe empty pidfile sentinel was not retained"; ok=0; }
    pid_root_inspect >/dev/null 2>&1
    [ $? -eq 0 ] \
        || { echo "FAIL: exact empty pidfile sentinel was not classified clean"; ok=0; }
    PIDDIR="$empty_previous_pid_dir"
    /usr/bin/rm -rf -- "$empty_pid_root"

    # Writer/reaper byte-grammar parity: 0 or 1..512 unique exact ASCII rows,
    # LF-only with a terminal LF, and at most 131072 encoded bytes. Exercise
    # the actual immutable snapshot parser at its valid count boundary and
    # against the common divergent-parser mutants.
    local parser_pid_root="$SELFTEST_ROOT/parser-pids" parser_role=parser
    local parser_pidfile parser_snapshot parser_header parser_count parser_extra
    local parser_tag parser_dev parser_ino parser_lock_dev parser_lock_ino
    local parser_digest parser_index
    local -a parser_lines=()
    /usr/bin/mkdir -m 700 -- "$parser_pid_root" || return 1
    (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
        > "$parser_pid_root/.hsqa-pid-root-owned") || return 1
    PIDDIR="$parser_pid_root"
    parser_pidfile="$PIDDIR/$parser_role.pids"
    (umask 077; : > "$parser_pidfile"
        for parser_index in $(seq 1 512); do
            builtin printf -- '-%s|%s|0|parser\n' \
                "$parser_index" "$parser_index" >> "$parser_pidfile" || exit 1
        done) || return 1
    parser_snapshot=$(snapshot_pidfile_records "$parser_role") || return 1
    mapfile -t parser_lines <<< "$parser_snapshot"
    parser_header="${parser_lines[0]:-}"
    IFS='|' read -r parser_tag parser_dev parser_ino parser_lock_dev \
        parser_lock_ino parser_digest parser_count parser_extra <<< "$parser_header"
    [ "$parser_tag" = S ] && [ -z "${parser_extra:-}" ] \
        && [ "$parser_count" = 512 ] && [ "${#parser_lines[@]}" -eq 513 ] \
        || { echo "FAIL: canonical 512-record parser boundary was rejected"; ok=0; }
    parser_reject_mutant() { # <label>; pidfile bytes are already installed
        local label="$1"
        if snapshot_pidfile_records "$parser_role" >/dev/null 2>&1; then
            echo "FAIL: pidfile parser accepted $label"
            return 1
        fi
        return 0
    }
    (umask 077; builtin printf '%s\n%s\n' \
        '-1|1|0|parser' '-1|1|0|parser' > "$parser_pidfile") || return 1
    parser_reject_mutant 'duplicate records' || ok=0
    (umask 077; builtin printf '%s\r\n' '-1|1|0|parser' \
        > "$parser_pidfile") || return 1
    parser_reject_mutant 'CRLF bytes' || ok=0
    (umask 077; builtin printf '%s' '-1|1|0|parser' \
        > "$parser_pidfile") || return 1
    parser_reject_mutant 'missing terminal LF' || ok=0
    (umask 077; builtin printf '%s\n\n' '-1|1|0|parser' \
        > "$parser_pidfile") || return 1
    parser_reject_mutant 'empty record' || ok=0
    (umask 077; : > "$parser_pidfile"
        for parser_index in $(seq 1 513); do
            builtin printf -- '-%s|%s|0|parser\n' \
                "$parser_index" "$parser_index" >> "$parser_pidfile" || exit 1
        done) || return 1
    parser_reject_mutant '513 records' || ok=0
    /usr/bin/python3 -I - "$parser_pidfile" <<'PYEOF' || return 1
import os
import sys
path = sys.argv[1]
fd = os.open(path, os.O_WRONLY | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0))
try:
    payload = b"1" * 131073
    offset = 0
    while offset < len(payload):
        written = os.write(fd, payload[offset:])
        if written <= 0:
            raise SystemExit(1)
        offset += written
    os.fsync(fd)
finally:
    os.close(fd)
PYEOF
    parser_reject_mutant '131073-byte file' || ok=0
    (umask 077; builtin printf '\302\265\n' > "$parser_pidfile") || return 1
    parser_reject_mutant 'non-ASCII bytes' || ok=0
    unset -f parser_reject_mutant
    PIDDIR="$empty_previous_pid_dir"
    /usr/bin/rm -rf -- "$parser_pid_root"

    # The reaper must never report its OWN CALLER as a leak. A wrapper whose
    # argv legitimately contains a harness path (a `bash -c` line mentioning
    # hsqa-inst/...) matched the pattern and was reported, because no text
    # pattern can exclude the caller's argv — only identity can. This feeds
    # this shell's real PID through the real matcher on a line that WOULD
    # otherwise match.
    local self_line="$$ /bin/bash -c reap-selftest -Dhsqa.instanceDir=/tmp/claude-0/hsqa-inst-v2/f1proof"
    local self_matched
    self_matched=$(HSQA_REAP_TEST_INPUT="$self_line" matching_procs)
    [ -z "$self_matched" ] || { echo "FAIL: matched its own caller pid $$ — got: $self_matched"; ok=0; }

    # The pidfile-stage guard, exercised through pid_record_disposition()
    # the same function cmd_reap calls. It used to be re-typed here, which is
    # the very defect finding 10 was raised for, one stage along.
    local d
    d=$(HSQA_REAP_TEST_STARTTIMES="4242 111" \
        HSQA_REAP_TEST_CMDLINES="4242 java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3" \
        pid_record_disposition '4242|4242|111|perf-build')
    [ "$d" = refuse ] || { echo "FAIL: a recycled PID that is now a GradleDaemon must be 'refuse', got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_GROUPS="4242 4242" HSQA_REAP_TEST_STARTTIMES="4242 111" \
        HSQA_REAP_TEST_CMDLINES="4242 bash /repo/qa/scripts/perf_probe.sh" \
        HSQA_REAP_TEST_IDENTITIES="4242 perf-server" \
        pid_record_disposition '-4242|4242|111|perf-server')
    [ "$d" = kill ] || { echo "FAIL: a live harness process must be 'kill', got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_STARTTIMES="" HSQA_REAP_TEST_CMDLINES="" \
        pid_record_disposition '999999999|999999999|111|perf-server')
    [ "$d" = gone ] || { echo "FAIL: a PID with no process must be 'gone', got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_GROUPS="4242 4242" HSQA_REAP_TEST_STARTTIMES="4242 222" \
        HSQA_REAP_TEST_CMDLINES="4242 sleep 60" \
        pid_record_disposition '-4242|4242|111|perf-server')
    [ "$d" = refuse ] || { echo "FAIL: recycled starttime must be REFUSE, got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_GROUPS="4242 4242" HSQA_REAP_TEST_STARTTIMES="4242 111" \
        HSQA_REAP_TEST_CMDLINES="4242 sleep 60" \
        pid_record_disposition '-4242|4242|111|perf-server')
    [ "$d" = refuse ] || { echo "FAIL: unrelated identity must be REFUSE, got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_GROUPS=$'5252 5252\n5252 5253' \
        HSQA_REAP_TEST_STARTTIMES="5252 111" \
        HSQA_REAP_TEST_CMDLINES=$'5252 bash /repo/qa/scripts/perf_probe.sh\n5253 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3' \
        HSQA_REAP_TEST_IDENTITIES=$'5252 perf-build\n5253 perf-build' \
        pid_record_disposition '-5252|5252|111|perf-build')
    [ "$d" = kill ] \
        || { echo "FAIL: marked single-use GradleDaemon must be kill, got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_GROUPS=$'5252 5252\n5252 5253' \
        HSQA_REAP_TEST_STARTTIMES="5252 111" \
        HSQA_REAP_TEST_CMDLINES=$'5252 bash /repo/qa/scripts/perf_probe.sh\n5253 java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3' \
        HSQA_REAP_TEST_IDENTITIES="5252 perf-build" \
        pid_record_disposition '-5252|5252|111|perf-build')
    [ "$d" = refuse ] \
        || { echo "FAIL: unmarked/shared GradleDaemon must be REFUSE, got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_STARTTIMES="4242 111" \
        HSQA_REAP_TEST_CMDLINES="4242 bash /repo/qa/scripts/perf_probe.sh" \
        pid_record_disposition 'malformed')
    [ "$d" = refuse ] || { echo "FAIL: malformed record must be REFUSE, got '$d'"; ok=0; }

    # Executable recycled-PID mutant: a real unrelated process with the same
    # numeric leader but a deliberately stale starttime must survive.
    setsid sleep 5 &
    local unrelated_pid=$! unrelated_start
    unrelated_start=$(pid_starttime "$unrelated_pid")
    d=$(pid_record_disposition "-$unrelated_pid|$unrelated_pid|$((unrelated_start + 1))|perf-server")
    [ "$d" = refuse ] || { echo "FAIL: live recycled-PID mutant was not REFUSE"; ok=0; }
    kill -0 "$unrelated_pid" 2>/dev/null \
        || { echo "FAIL: unrelated recycled-PID mutant did not survive"; ok=0; }
    kill "$unrelated_pid" 2>/dev/null || true
    wait "$unrelated_pid" 2>/dev/null || true

    # Real orphaned-process-group mutant: the recorded leader exits, while a
    # child in that exact group retains the inherited harness identity and
    # ignores TERM. The disposition must still be kill, and the record is not
    # considered clear until the whole negative PGID is absent.
    local orphan_ids orphan_ready orphan_launcher orphan_leader orphan_child orphan_record
    local orphan_pid_dir previous_piddir previous_hsqa_piddir orphan_pidfile
    orphan_ids=$(mktemp) || return 1
    orphan_ready=$(mktemp) || { rm -f "$orphan_ids"; return 1; }
    rm -f -- "$orphan_ids" "$orphan_ready"
    HSQA_PROCESS_IDENTITY=perf-active /usr/bin/setsid /usr/bin/python3 -I - \
            "$orphan_ids" "$orphan_ready" <<'PYEOF' &
import os, signal, sys, time
ids, ready = sys.argv[1:]
leader = os.getpid()
child = os.fork()
if child:
    with open(ids, "w") as handle:
        handle.write(f"{leader} {child}\n")
    os._exit(0)
signal.signal(signal.SIGHUP, signal.SIG_IGN)
signal.signal(signal.SIGTERM, signal.SIG_IGN)
with open(ready, "w") as handle:
    handle.write(str(os.getpid()))
while True:
    time.sleep(1)
PYEOF
    orphan_launcher=$!
    wait "$orphan_launcher" 2>/dev/null || true
    for _ in $(seq 1 100); do
        [ -s "$orphan_ids" ] && [ -s "$orphan_ready" ] && break
        sleep 0.01
    done
    if [ ! -s "$orphan_ids" ] || [ ! -s "$orphan_ready" ]; then
        echo "FAIL: orphaned-group mutant did not become ready"
        ok=0
    else
        read -r orphan_leader orphan_child < "$orphan_ids"
        orphan_record="-$orphan_leader|$orphan_leader|1|perf-active"
        d=$(pid_record_disposition "$orphan_record")
        [ "$d" = kill ] \
            || { echo "FAIL: leader-gone live group must be kill, got '$d'"; ok=0; }
        kill -TERM -- "-$orphan_leader" 2>/dev/null || true
        sleep 0.05
        kill -0 -- "-$orphan_leader" 2>/dev/null \
            || { echo "FAIL: orphan child did not survive TERM as fixture requires"; ok=0; }
        orphan_pid_dir="$SELFTEST_ROOT/orphan-pids"
        /usr/bin/mkdir -m 700 -- "$orphan_pid_dir" || return 1
        (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
            > "$orphan_pid_dir/.hsqa-pid-root-owned") || return 1
        previous_piddir="$PIDDIR"
        previous_hsqa_piddir="$HSQA_PIDDIR"
        PIDDIR="$orphan_pid_dir"
        HSQA_PIDDIR="$orphan_pid_dir"
        orphan_pidfile="$PIDDIR/performance.pids"
        (umask 077; printf '%s\n' "$orphan_record" > "$orphan_pidfile")
        [ -s "$orphan_pidfile" ] \
            || { echo "FAIL: orphan recovery record was not durable while group lived"; ok=0; }
        reap_pidfile_records >/dev/null \
            || { echo "FAIL: production pidfile reaper could not clear orphan group"; ok=0; }
        kill -0 -- "-$orphan_leader" 2>/dev/null \
            && { echo "FAIL: production pidfile reaper left orphan group alive"; ok=0; }
        [ -f "$orphan_pidfile" ] && [ ! -L "$orphan_pidfile" ] \
            && [ ! -s "$orphan_pidfile" ] \
            && [ "$(/usr/bin/stat -c '%a:%h' -- "$orphan_pidfile" 2>/dev/null)" = 600:1 ] \
            || { echo "FAIL: production pidfile reaper did not retain a safe empty sentinel"; ok=0; }
        pid_root_inspect >/dev/null 2>&1
        [ $? -eq 0 ] \
            || { echo "FAIL: reaped orphan root was not clean with empty sentinel"; ok=0; }
        PIDDIR="$previous_piddir"
        HSQA_PIDDIR="$previous_hsqa_piddir"
        /usr/bin/rm -rf -- "$orphan_pid_dir"
    fi
    kill -9 -- "-${orphan_leader:-999999999}" 2>/dev/null || true
    rm -f -- "$orphan_ids" "$orphan_ready"

    # Executable pidfile-swap mutant. The reaper snapshots one exact owned
    # record under the role lock, then a test-only barrier replaces the named
    # pidfile with a different live, identity-valid record. Only the immutable
    # original snapshot target may be killed; the injected process and record
    # must survive and the recovery root must remain red.
    local swap_pid_root="$SELFTEST_ROOT/swap-pids" swap_role=swap-race
    local swap_pidfile swap_replacement swap_ready swap_hold swap_worker
    local swap_worker_status swap_reap_status swap_inspect_status
    local swap_original_pid swap_original_start swap_original_record
    local swap_external_pid swap_external_start swap_external_record
    local swap_previous_piddir="$PIDDIR" swap_previous_hsqa_piddir="$HSQA_PIDDIR"
    /usr/bin/mkdir -m 700 -- "$swap_pid_root" || return 1
    (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
        > "$swap_pid_root/.hsqa-pid-root-owned") || return 1
    HSQA_PROCESS_IDENTITY=perf-server /usr/bin/setsid \
        /usr/bin/sleep 300 &
    swap_original_pid=$!
    SELFTEST_SWAP_ORIGINAL_GROUP="$swap_original_pid"
    HSQA_PROCESS_IDENTITY=perf-server /usr/bin/setsid \
        /usr/bin/sleep 300 &
    swap_external_pid=$!
    SELFTEST_SWAP_EXTERNAL_GROUP="$swap_external_pid"
    for _ in $(seq 1 100); do
        swap_original_start=$(pid_starttime "$swap_original_pid")
        swap_external_start=$(pid_starttime "$swap_external_pid")
        if [[ "$swap_original_start" =~ ^[0-9]+$ ]] \
                && [[ "$swap_external_start" =~ ^[0-9]+$ ]] \
                && pid_env_identity_status perf-server "$swap_original_pid" \
                && pid_env_identity_status perf-server "$swap_external_pid"; then
            break
        fi
        /bin/sleep 0.01
    done
    if ! [[ "${swap_original_start:-}" =~ ^[0-9]+$ ]] \
            || ! [[ "${swap_external_start:-}" =~ ^[0-9]+$ ]]; then
        echo "FAIL: pidfile-swap processes did not become observable"
        ok=0
    else
        swap_original_record="-$swap_original_pid|$swap_original_pid|$swap_original_start|perf-server"
        swap_external_record="-$swap_external_pid|$swap_external_pid|$swap_external_start|perf-server"
        PIDDIR="$swap_pid_root"
        HSQA_PIDDIR="$swap_pid_root"
        swap_pidfile="$PIDDIR/$swap_role.pids"
        swap_replacement="$PIDDIR/.swap-replacement"
        swap_ready="$SELFTEST_ROOT/swap-snapshot-ready"
        swap_hold="$SELFTEST_ROOT/swap-snapshot-hold"
        (umask 077; builtin printf '%s\n' "$swap_original_record" \
            > "$swap_pidfile") || return 1
        (umask 077; builtin printf '%s\n' "$swap_external_record" \
            > "$swap_replacement") || return 1
        : > "$swap_hold" || return 1
        HSQA_REAP_PIDFILE_SWAP_READY="$swap_ready"
        HSQA_REAP_PIDFILE_SWAP_HOLD="$swap_hold"
        (
            local attempt
            for attempt in $(seq 1 500); do
                [ -e "$swap_ready" ] && break
                /bin/sleep 0.01
            done
            [ -e "$swap_ready" ] || exit 91
            /bin/mv -f -- "$swap_replacement" "$swap_pidfile" || exit 92
            /bin/rm -f -- "$swap_hold" || exit 93
        ) &
        swap_worker=$!
        reap_pidfile_records >/dev/null 2>&1; swap_reap_status=$?
        wait "$swap_worker"; swap_worker_status=$?
        HSQA_REAP_PIDFILE_SWAP_READY=''
        HSQA_REAP_PIDFILE_SWAP_HOLD=''
        [ "$swap_worker_status" -eq 0 ] \
            || { echo "FAIL: pidfile-swap worker failed ($swap_worker_status)"; ok=0; }
        [ "$swap_reap_status" -ne 0 ] \
            || { echo "FAIL: replaced pidfile was reported successfully reaped"; ok=0; }
        kill -0 -- "-$swap_original_pid" 2>/dev/null \
            && { echo "FAIL: immutable original snapshot target survived"; ok=0; }
        kill -0 -- "-$swap_external_pid" 2>/dev/null \
            || { echo "FAIL: injected external pidfile target was killed"; ok=0; }
        [ -f "$swap_pidfile" ] && [ ! -L "$swap_pidfile" ] \
            && [ "$(/usr/bin/stat -c %h -- "$swap_pidfile" 2>/dev/null)" = 1 ] \
            && /usr/bin/cmp -s -- "$swap_pidfile" \
                <(builtin printf '%s\n' "$swap_external_record") \
            || { echo "FAIL: injected external recovery record did not survive"; ok=0; }
        pid_root_inspect >/dev/null 2>&1; swap_inspect_status=$?
        [ "$swap_inspect_status" -eq 1 ] \
            || { echo "FAIL: pidfile replacement did not retain red recovery state"; ok=0; }
    fi
    /bin/kill -9 -- "-$swap_original_pid" 2>/dev/null || true
    /bin/kill -9 -- "-$swap_external_pid" 2>/dev/null || true
    wait "$swap_original_pid" 2>/dev/null || true
    wait "$swap_external_pid" 2>/dev/null || true
    SELFTEST_SWAP_ORIGINAL_GROUP=''
    SELFTEST_SWAP_EXTERNAL_GROUP=''
    PIDDIR="$swap_previous_piddir"
    HSQA_PIDDIR="$swap_previous_hsqa_piddir"
    /usr/bin/rm -rf -- "$swap_pid_root"
    /bin/rm -f -- "$SELFTEST_ROOT/swap-snapshot-ready" \
        "$SELFTEST_ROOT/swap-snapshot-hold"

    # Commit-boundary mutants exercise anonymous creation, first named
    # exposure, exchange, both leased authority inodes, private-old disposal,
    # and the final empty-sentinel check. An actor that ignores the exact role
    # lock must never make recovery authority disappear or become falsely clean.
    # The
    # injected, live identity-valid record/process must survive byte-for-byte,
    # finalize must fail, and the recovery root must remain red.
    local commit_external_pid commit_external_start commit_external_record
    HSQA_PROCESS_IDENTITY=perf-server /usr/bin/setsid /usr/bin/sleep 300 &
    commit_external_pid=$!
    SELFTEST_COMMIT_EXTERNAL_GROUP="$commit_external_pid"
    for _ in $(seq 1 100); do
        commit_external_start=$(pid_starttime "$commit_external_pid")
        if [[ "$commit_external_start" =~ ^[0-9]+$ ]] \
                && pid_env_identity_status perf-server "$commit_external_pid"; then
            break
        fi
        /bin/sleep 0.01
    done
    if ! [[ "${commit_external_start:-}" =~ ^[0-9]+$ ]]; then
        echo "FAIL: commit-boundary external process did not become observable"
        ok=0
    else
        commit_external_record="-$commit_external_pid|$commit_external_pid|$commit_external_start|perf-server"
        finalize_commit_swap_mutant() { # <unlinked|private|exchange|append|candidate|dispose|empty>
            local phase="$1" role="commit-$1" result=0
            local test_pid_root="$SELFTEST_ROOT/$phase-commit-pids"
            local test_pidfile="$test_pid_root/$role.pids"
            local replacement="$test_pid_root/.external-replacement"
            local ready="$SELFTEST_ROOT/$phase-commit-ready"
            local hold="$SELFTEST_ROOT/$phase-commit-hold"
            local original_record snapshot header tag dev ino lock_dev lock_ino
            local digest count extra worker worker_status finalize_status inspect_status
            local private_entry=''
            local -a snapshot_lines=()
            case "$phase" in
                unlinked) original_record='-999999989|999999989|1|perf-server';;
                private) original_record='-999999990|999999990|1|perf-server';;
                exchange) original_record='-999999991|999999991|1|perf-server';;
                append) original_record='-999999992|999999992|1|perf-server';;
                candidate) original_record='-999999993|999999993|1|perf-server';;
                dispose) original_record='-999999994|999999994|1|perf-server';;
                empty) original_record='-999999995|999999995|1|perf-server';;
                *) return 1;;
            esac
            /usr/bin/mkdir -m 700 -- "$test_pid_root" || return 1
            (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
                > "$test_pid_root/.hsqa-pid-root-owned") || return 1
            PIDDIR="$test_pid_root"
            HSQA_PIDDIR="$test_pid_root"
            (umask 077; builtin printf '%s\n' "$original_record" \
                > "$test_pidfile") || return 1
            if [ "$phase" = exchange ] || [ "$phase" = dispose ] \
                    || [ "$phase" = empty ]; then
                (umask 077; builtin printf '%s\n' "$commit_external_record" \
                    > "$replacement") || return 1
            fi
            snapshot=$(snapshot_pidfile_records "$role") || return 1
            mapfile -t snapshot_lines <<< "$snapshot"
            [ "${#snapshot_lines[@]}" -eq 2 ] || return 1
            header="${snapshot_lines[0]}"
            IFS='|' read -r tag dev ino lock_dev lock_ino digest count extra <<< "$header"
            [ "$tag" = S ] && [ -z "${extra:-}" ] && [ "$count" = 1 ] \
                && [[ "$dev" =~ ^[0-9]+$ ]] && [[ "$ino" =~ ^[1-9][0-9]*$ ]] \
                && [[ "$lock_dev" =~ ^[0-9]+$ ]] \
                && [[ "$lock_ino" =~ ^[1-9][0-9]*$ ]] \
                && [[ "$digest" =~ ^[0-9a-f]{64}$ ]] \
                && [ "${snapshot_lines[1]}" = "R|$original_record" ] \
                || return 1
            : > "$hold" || return 1
            case "$phase" in
                unlinked|private|exchange|append|candidate|dispose)
                    HSQA_REAP_PIDFILE_COMMIT_READY="$ready"
                    HSQA_REAP_PIDFILE_COMMIT_HOLD="$hold";;
                empty)
                    HSQA_REAP_PIDFILE_EMPTY_READY="$ready"
                    HSQA_REAP_PIDFILE_EMPTY_HOLD="$hold";;
            esac
            (
                local attempt
                for attempt in $(seq 1 500); do
                    [ -e "$ready" ] && break
                    /bin/sleep 0.01
                done
                [ -e "$ready" ] || exit 91
                case "$phase" in
                    unlinked)
                        [ -z "$(/usr/bin/find -P "$test_pid_root" -mindepth 1 \
                            -maxdepth 1 -name ".${role}.pids.reap.*" -print \
                            2>/dev/null)" ] || exit 92;;
                    private)
                        private_entry=$(/usr/bin/find -P "$test_pid_root" -mindepth 1 \
                            -maxdepth 1 -type f -name ".${role}.pids.reap.*" \
                            -print 2>/dev/null) || exit 92
                        [ "$(builtin printf '%s\n' "$private_entry" \
                            | /usr/bin/sed '/^$/d' | /usr/bin/wc -l)" -eq 1 ] || exit 92
                        builtin printf '%s\n' "$commit_external_record" \
                            >> "$private_entry" || exit 92;;
                    append|candidate)
                        builtin printf '%s\n' "$commit_external_record" \
                            >> "$test_pidfile" || exit 92;;
                    dispose)
                        private_entry=$(/usr/bin/find -P "$test_pid_root" -mindepth 1 \
                            -maxdepth 1 -type f -name ".${role}.pids.reap.*" \
                            -print 2>/dev/null) || exit 92
                        [ "$(builtin printf '%s\n' "$private_entry" \
                            | /usr/bin/sed '/^$/d' | /usr/bin/wc -l)" -eq 1 ] || exit 92
                        /bin/mv -f -- "$replacement" "$private_entry" || exit 92;;
                    exchange|empty)
                        /bin/mv -f -- "$replacement" "$test_pidfile" || exit 92;;
                    *) exit 92;;
                esac
                /bin/rm -f -- "$hold" || exit 93
            ) &
            worker=$!
            finalize_pidfile_snapshot "$role" "$dev" "$ino" "$lock_dev" \
                "$lock_ino" "$digest" "$original_record" >/dev/null 2>&1
            finalize_status=$?
            wait "$worker"; worker_status=$?
            HSQA_REAP_PIDFILE_COMMIT_READY=''
            HSQA_REAP_PIDFILE_COMMIT_HOLD=''
            HSQA_REAP_PIDFILE_EMPTY_READY=''
            HSQA_REAP_PIDFILE_EMPTY_HOLD=''
            [ "$worker_status" -eq 0 ] \
                || { echo "FAIL: $phase commit-boundary worker failed ($worker_status)"; result=1; }
            if [ "$phase" = unlinked ]; then
                [ "$finalize_status" -eq 0 ] \
                    || { echo "FAIL: anonymous unlinked candidate did not finalize ($finalize_status)"; result=1; }
            else
                [ "$finalize_status" -ne 0 ] \
                    || { echo "FAIL: $phase commit-boundary replacement finalized green"; result=1; }
            fi
            /bin/kill -0 -- "-$commit_external_pid" 2>/dev/null \
                || { echo "FAIL: $phase commit-boundary external process was killed"; result=1; }
            case "$phase" in
                unlinked)
                    [ -f "$test_pidfile" ] && [ ! -L "$test_pidfile" ] \
                        && [ ! -s "$test_pidfile" ] \
                        && [ "$(/usr/bin/stat -c '%a:%h' -- "$test_pidfile" \
                            2>/dev/null)" = 600:1 ] \
                        || { echo "FAIL: anonymous candidate did not publish exact empty sentinel"; result=1; };;
                append)
                    [ -f "$test_pidfile" ] && [ ! -L "$test_pidfile" ] \
                        && [ "$(/usr/bin/stat -c %h -- "$test_pidfile" 2>/dev/null)" = 1 ] \
                        && /usr/bin/cmp -s -- "$test_pidfile" \
                            <(builtin printf '%s\n%s\n' "$original_record" \
                                "$commit_external_record") \
                        || { echo "FAIL: append commit-boundary records were not preserved"; result=1; };;
                private)
                    /usr/bin/cmp -s -- "$test_pidfile" \
                        <(builtin printf '%s\n' "$original_record") \
                        || { echo "FAIL: first-exposure mutation changed public original"; result=1; }
                    private_entry=$(/usr/bin/find -P "$test_pid_root" -mindepth 1 \
                        -maxdepth 1 -type f -name ".${role}.pids.reap.*" -print \
                        2>/dev/null) || result=1
                    [ "$(builtin printf '%s\n' "$private_entry" \
                        | /usr/bin/sed '/^$/d' | /usr/bin/wc -l)" -eq 1 ] \
                        && /usr/bin/cmp -s -- "$private_entry" \
                            <(builtin printf '%s\n' "$commit_external_record") \
                        || { echo "FAIL: first-exposure writer record became unreachable"; result=1; };;
                dispose)
                    [ -f "$test_pidfile" ] && [ ! -L "$test_pidfile" ] \
                        && [ ! -s "$test_pidfile" ] \
                        || { echo "FAIL: disposal substitution changed public candidate"; result=1; }
                    private_entry=$(/usr/bin/find -P "$test_pid_root" -mindepth 1 \
                        -maxdepth 1 -type f -name ".${role}.pids.reap.*" -print \
                        2>/dev/null) || result=1
                    [ "$(builtin printf '%s\n' "$private_entry" \
                        | /usr/bin/sed '/^$/d' | /usr/bin/wc -l)" -eq 1 ] \
                        && /usr/bin/cmp -s -- "$private_entry" \
                            <(builtin printf '%s\n' "$commit_external_record") \
                        || { echo "FAIL: private disposal substitution record was deleted"; result=1; };;
                *)
                    [ -f "$test_pidfile" ] && [ ! -L "$test_pidfile" ] \
                        && [ "$(/usr/bin/stat -c %h -- "$test_pidfile" 2>/dev/null)" = 1 ] \
                        && /usr/bin/cmp -s -- "$test_pidfile" \
                            <(builtin printf '%s\n' "$commit_external_record") \
                        || { echo "FAIL: $phase commit-boundary record was not preserved"; result=1; };;
            esac
            pid_root_inspect >/dev/null 2>&1; inspect_status=$?
            if [ "$phase" = unlinked ]; then
                [ "$inspect_status" -eq 0 ] \
                    || { echo "FAIL: anonymous candidate clean root was not clean"; result=1; }
            else
                [ "$inspect_status" -eq 1 ] \
                    || { echo "FAIL: $phase commit-boundary recovery root was not red"; result=1; }
            fi
            /usr/bin/rm -rf -- "$test_pid_root"
            /bin/rm -f -- "$ready" "$hold"
            return "$result"
        }
        finalize_commit_swap_mutant unlinked || ok=0
        finalize_commit_swap_mutant private || ok=0
        finalize_commit_swap_mutant exchange || ok=0
        finalize_commit_swap_mutant append || ok=0
        finalize_commit_swap_mutant candidate || ok=0
        finalize_commit_swap_mutant dispose || ok=0
        finalize_commit_swap_mutant empty || ok=0
        unset -f finalize_commit_swap_mutant

        # A legacy writer may already hold an O_APPEND descriptor before the
        # finalizer starts. A kernel write lease must reject that state before
        # any exchange or candidate publication. After refusal, the same named
        # inode and original bytes remain; once released, the writer's live
        # recovery record is still linked and keeps the root red.
        finalize_preopen_writer_mutant() {
            local role=lease-held result=0 writer='' writer_status=0
            local test_pid_root="$SELFTEST_ROOT/lease-held-pids"
            local test_pidfile="$test_pid_root/$role.pids"
            local ready="$SELFTEST_ROOT/lease-held-ready"
            local hold="$SELFTEST_ROOT/lease-held-hold"
            local original_record='-999999995|999999995|1|perf-server'
            local inode_before='' inode_after='' snapshot='' status=0
            local header tag dev ino lock_dev
            local lock_ino digest count extra finalize_status inspect_status residue
            local -a snapshot_lines=()
            /usr/bin/mkdir -m 700 -- "$test_pid_root" || return 1
            (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
                > "$test_pid_root/.hsqa-pid-root-owned") || return 1
            (umask 077; builtin printf '%s\n' "$original_record" \
                > "$test_pidfile") || return 1
            : > "$hold" || return 1
            /usr/bin/python3 -I - "$test_pidfile" "$ready" "$hold" \
                    "$commit_external_record" <<'PYEOF' &
import os
import stat
import sys
import time

path, ready, hold, record = sys.argv[1:]
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
fd = os.open(path, os.O_WRONLY | os.O_APPEND | cloexec | nofollow)
try:
    value = os.fstat(fd)
    if not stat.S_ISREG(value.st_mode) or value.st_nlink != 1:
        raise SystemExit(2)
    ready_fd = os.open(
        ready, os.O_WRONLY | os.O_CREAT | os.O_EXCL | cloexec | nofollow, 0o600
    )
    try:
        os.write(ready_fd, b"ready\n")
        os.fsync(ready_fd)
    finally:
        os.close(ready_fd)
    for _ in range(500):
        if not os.path.lexists(hold):
            break
        time.sleep(0.01)
    else:
        raise SystemExit(3)
    payload = record.encode("ascii") + b"\n"
    if os.write(fd, payload) != len(payload):
        raise SystemExit(4)
    os.fsync(fd)
finally:
    os.close(fd)
PYEOF
            writer=$!
            for _ in $(seq 1 500); do
                [ -s "$ready" ] && break
                /bin/sleep 0.01
            done
            if [ ! -s "$ready" ]; then
                echo "FAIL: pre-open pidfile writer did not become ready"
                result=1
            else
                PIDDIR="$test_pid_root"
                HSQA_PIDDIR="$test_pid_root"
                inode_before=$(/usr/bin/stat -c '%d:%i' -- "$test_pidfile" 2>/dev/null) \
                    || result=1
                snapshot=$(snapshot_pidfile_records "$role"); status=$?
                if [ "$status" -ne 0 ]; then
                    echo "FAIL: pre-open writer snapshot failed ($status)"
                    result=1
                else
                    mapfile -t snapshot_lines <<< "$snapshot"
                    header="${snapshot_lines[0]:-}"
                    IFS='|' read -r tag dev ino lock_dev lock_ino digest count extra \
                        <<< "$header"
                    if [ "${#snapshot_lines[@]}" -ne 2 ] || [ "$tag" != S ] \
                            || [ -n "${extra:-}" ] || [ "$count" != 1 ] \
                            || ! [[ "$dev" =~ ^[0-9]+$ ]] \
                            || ! [[ "$ino" =~ ^[1-9][0-9]*$ ]] \
                            || ! [[ "$lock_dev" =~ ^[0-9]+$ ]] \
                            || ! [[ "$lock_ino" =~ ^[1-9][0-9]*$ ]] \
                            || ! [[ "$digest" =~ ^[0-9a-f]{64}$ ]] \
                            || [ "${snapshot_lines[1]}" != "R|$original_record" ]; then
                        echo "FAIL: malformed pre-open writer snapshot protocol"
                        result=1
                    else
                        finalize_pidfile_snapshot "$role" "$dev" "$ino" \
                            "$lock_dev" "$lock_ino" "$digest" "$original_record" \
                            >/dev/null 2>&1
                        finalize_status=$?
                        [ "$finalize_status" -ne 0 ] \
                            || { echo "FAIL: pre-open writer finalized green"; result=1; }
                        inode_after=$(/usr/bin/stat -c '%d:%i' -- "$test_pidfile" \
                            2>/dev/null) || result=1
                        [ "$inode_before" = "$inode_after" ] \
                            || { echo "FAIL: pre-open writer changed named inode"; result=1; }
                        /usr/bin/cmp -s -- "$test_pidfile" \
                            <(builtin printf '%s\n' "$original_record") \
                            || { echo "FAIL: pre-open refusal changed original bytes"; result=1; }
                    fi
                fi
            fi
            /bin/rm -f -- "$hold"
            wait "$writer"; writer_status=$?
            [ "$writer_status" -eq 0 ] \
                || { echo "FAIL: pre-open writer failed ($writer_status)"; result=1; }
            /bin/kill -0 -- "-$commit_external_pid" 2>/dev/null \
                || { echo "FAIL: pre-open writer external process was killed"; result=1; }
            [ -f "$test_pidfile" ] && [ ! -L "$test_pidfile" ] \
                && [ "$(/usr/bin/stat -c %h -- "$test_pidfile" 2>/dev/null)" = 1 ] \
                && /usr/bin/cmp -s -- "$test_pidfile" \
                    <(builtin printf '%s\n%s\n' "$original_record" \
                        "$commit_external_record") \
                || { echo "FAIL: pre-open writer records were not preserved"; result=1; }
            pid_root_inspect >/dev/null 2>&1; inspect_status=$?
            [ "$inspect_status" -eq 1 ] \
                || { echo "FAIL: pre-open writer recovery root was not red"; result=1; }
            residue=$(/usr/bin/find -P "$test_pid_root" -mindepth 1 -maxdepth 1 \
                -type f \( -name ".${role}.pids.reap.*" \
                    -o -name ".${role}.pids.empty.*" \) -print -quit 2>/dev/null)
            [ -z "$residue" ] \
                || { echo "FAIL: pre-open writer left private transaction residue"; result=1; }
            PIDDIR="$swap_previous_piddir"
            HSQA_PIDDIR="$swap_previous_hsqa_piddir"
            /usr/bin/rm -rf -- "$test_pid_root"
            /bin/rm -f -- "$ready" "$hold"
            return "$result"
        }
        finalize_preopen_writer_mutant || ok=0
        unset -f finalize_preopen_writer_mutant
    fi
    /bin/kill -9 -- "-$commit_external_pid" 2>/dev/null || true
    wait "$commit_external_pid" 2>/dev/null || true
    SELFTEST_COMMIT_EXTERNAL_GROUP=''
    PIDDIR="$swap_previous_piddir"
    HSQA_PIDDIR="$swap_previous_hsqa_piddir"

    # Session ownership comes only from immutable IDs/options/process markers
    # on the isolated socket.  A foreign session deliberately shares that
    # socket; cleanup must stop our exact control pane and preserve it.
    local tmux_sessions owned_session='' foreign_creation foreign_session=''
    local foreign_window='' foreign_pane='' foreign_pid='' foreign_start=''
    local foreign_identity foreign_command tmux_reap_status saved_snapshot
    if [ -x /usr/bin/tmux ]; then
        foreign_identity="foreign-control-$(hsqa_recovery_token)"
        printf -v foreign_command '%q ' exec /usr/bin/env \
            "HSQA_PROCESS_IDENTITY=$foreign_identity" /usr/bin/sleep 2147483647
        foreign_creation=$(hsqa_tmux new-session -d -P \
            -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
            -s unrelated-session -n control "$foreign_command") \
            || { echo "FAIL: could not create isolated foreign tmux session"; ok=0; }
        hsqa_parse_tmux_creation "$foreign_creation" foreign_session foreign_window \
            foreign_pane foreign_pid \
            || { echo "FAIL: malformed foreign tmux creation tuple"; ok=0; }
        foreign_start=$(hsqa_process_starttime "$foreign_pid")
        hsqa_create_owned_tmux_session hsqa-blessing-restart restart-tmux '' \
            owned_session \
            || { echo "FAIL: could not create exact owned tmux session"; ok=0; }
        tmux_sessions=$(tmux_sessions_present) \
            || { echo "FAIL: exact tmux snapshot failed"; ok=0; tmux_sessions=''; }
        printf '%s\n' "$tmux_sessions" \
            | /usr/bin/grep -Eq '^\$[0-9]+\|hsqa-blessing-restart$' \
            || { echo "FAIL: exact tmux query missed owned session"; ok=0; }
        reap_harness_tmux_sessions >/dev/null 2>&1; tmux_reap_status=$?
        [ "$tmux_reap_status" -ne 0 ] \
            || { echo "FAIL: tmux reap ignored foreign session refusal"; ok=0; }
        hsqa_tmux_exact_session_present "$owned_session"
        [ $? -eq 1 ] || { echo "FAIL: exact owned tmux session survived"; ok=0; }
        hsqa_tmux_exact_session_present "$foreign_session"
        [ $? -eq 0 ] || { echo "FAIL: foreign tmux session was destroyed"; ok=0; }

        saved_snapshot=$(declare -f hsqa_snapshot_tmux_sessions) || return 1
        hsqa_snapshot_tmux_sessions() { return 2; }
        tmux_sessions_present >/dev/null 2>&1
        [ $? -eq 2 ] || { echo "FAIL: tmux observation error was not tri-state"; ok=0; }
        eval "$saved_snapshot"

        [[ "$foreign_start" =~ ^[0-9]+$ ]] \
            && hsqa_kill_exact_tmux_pane "$foreign_pane" "$foreign_pid" \
                "$foreign_start" "$foreign_identity" \
            || { echo "FAIL: exact foreign fixture pane cleanup failed"; ok=0; }
        hsqa_finalize_empty_tmux_socket >/dev/null 2>&1 \
            || { echo "FAIL: isolated tmux socket did not finalize"; ok=0; }
    fi

    # Authenticated crash-fixture proof. `check` must be byte/inode/mtime/
    # entry read-only on both its dirty and clean verdicts; `reap` may mutate
    # only the marker-owned fixture PIDDIR, and an unrelated live sentinel
    # must survive. The perf safety test adds the production-shaped Java
    # sentinel; this local mutant proves the same branch isolation cheaply.
    local fixture_root fixture_token fixture_token_file fixture_pid_dir
    local fixture_identity fixture_pid fixture_group fixture_record fixture_role
    local fixture_info fixture_launcher
    local fixture_before fixture_after fixture_log fixture_status
    local sentinel_pid sentinel_start
    local saved_matching saved_tmux fixture_branch_sentinel
    local fixture_repo fixture_reaper
    fixture_root=$(/usr/bin/mktemp -d /tmp/hsqa-reap-fixture.XXXXXX) || return 1
    SELFTEST_EXTERNAL_FIXTURE="$fixture_root"
    /usr/bin/chmod 700 -- "$fixture_root" || return 1
    fixture_token=$(/usr/bin/od -An -N24 -tx1 /dev/urandom \
        | /usr/bin/tr -d ' \n')
    [[ "$fixture_token" =~ ^[0-9a-f]{48}$ ]] || return 1
    fixture_token_file="$fixture_root/.hsqa-reap-fixture-token"
    (umask 077; builtin printf '%s' "$fixture_token" > "$fixture_token_file") \
        || return 1
    fixture_pid_dir="$fixture_root/pids"
    /usr/bin/mkdir -m 700 -- "$fixture_pid_dir" || return 1
    (umask 077; builtin printf 'hsqa-pid-root-v1\n' \
        > "$fixture_pid_dir/.hsqa-pid-root-owned") || return 1
    fixture_identity="perf-crash-$(hsqa_recovery_token)"
    fixture_role="performance-$(hsqa_recovery_token)"
    fixture_info="$SELFTEST_ROOT/fixture-orphan-info"
    HSQA_PROCESS_IDENTITY="$fixture_identity" /usr/bin/setsid \
        /bin/bash --noprofile --norc -p -c \
        '/usr/bin/sleep 60 </dev/null >/dev/null 2>&1 & printf "%s %s\n" "$$" "$!" > "$1"' \
        -- "$fixture_info" &
    fixture_launcher=$!
    wait "$fixture_launcher" 2>/dev/null || true
    [ -s "$fixture_info" ] || return 1
    read -r fixture_group fixture_pid < "$fixture_info"
    [[ "$fixture_group" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$fixture_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    SELFTEST_EXTERNAL_GROUP="$fixture_group"
    fixture_record="-$fixture_group|$fixture_group|1|$fixture_identity"
    (umask 077; builtin printf '%s\n' "$fixture_record" \
        > "$fixture_pid_dir/$fixture_role.pids") || return 1
    /usr/bin/setsid /usr/bin/sleep 60 &
    sentinel_pid=$!
    SELFTEST_EXTERNAL_SENTINEL="$sentinel_pid"
    sentinel_start=$(hsqa_process_starttime "$sentinel_pid")
    fixture_log="$fixture_root/check.log.external"
    fixture_repo=$(/usr/bin/mktemp -d /tmp/hsqa-perf-launch-reap-XXXXXX) \
        || return 1
    SELFTEST_EXTERNAL_REPO="$fixture_repo"
    /usr/bin/mkdir -p -- "$fixture_repo/qa/scripts" || return 1
    /usr/bin/cp -- "$SCRIPT_PATH" "$HERE/lib_harness.sh" \
        "$HERE/lib_safe_paths.sh" "$HERE/lib_tmux_harness.sh" \
        "$fixture_repo/qa/scripts/" || return 1
    fixture_reaper="$fixture_repo/qa/scripts/reap.sh"

    fixture_before=$(reap_tree_snapshot "$fixture_root") || return 1
    /usr/bin/env -i PATH="$SYSTEM_PATH" HOME="${HOME:-/tmp}" LANG=C.UTF-8 \
        LC_ALL=C SHELL=/bin/bash TERM=xterm-256color TMPDIR=/tmp \
        HSQA_REAP_FIXTURE_ROOT="$fixture_root" \
        HSQA_REAP_FIXTURE_TOKEN="$fixture_token" \
        /bin/bash --noprofile --norc -p "$fixture_reaper" check \
        > "$SELFTEST_ROOT/fixture-dirty-check.log" 2>&1
    fixture_status=$?
    if [ "$fixture_status" -ne 1 ]; then
        echo "FAIL: fixture check did not report durable record (status $fixture_status)"
        /usr/bin/sed 's/^/  fixture check: /' \
            "$SELFTEST_ROOT/fixture-dirty-check.log" 2>/dev/null || true
        ok=0
    fi
    fixture_after=$(reap_tree_snapshot "$fixture_root") || return 1
    [ "$fixture_before" = "$fixture_after" ] \
        || { echo "FAIL: dirty fixture check mutated bytes/identity/entries"; ok=0; }

    /usr/bin/env -i PATH="$SYSTEM_PATH" HOME="${HOME:-/tmp}" LANG=C.UTF-8 \
        LC_ALL=C SHELL=/bin/bash TERM=xterm-256color TMPDIR=/tmp \
        HSQA_REAP_FIXTURE_ROOT="$fixture_root" \
        HSQA_REAP_FIXTURE_TOKEN="$fixture_token" \
        /bin/bash --noprofile --norc -p "$fixture_reaper" reap \
        > "$SELFTEST_ROOT/fixture-reap.log" 2>&1
    fixture_status=$?
    if [ "$fixture_status" -ne 0 ]; then
        echo "FAIL: authenticated fixture pidfile reap failed (status $fixture_status)"
        /usr/bin/sed 's/^/  fixture reap: /' \
            "$SELFTEST_ROOT/fixture-reap.log" 2>/dev/null || true
        ok=0
    fi
    [ "$(hsqa_process_starttime "$sentinel_pid")" = "$sentinel_start" ] \
        || { echo "FAIL: fixture reap touched unrelated production sentinel"; ok=0; }
    [ -z "$(hsqa_process_starttime "$fixture_pid")" ] \
        || { echo "FAIL: fixture reap left exact tracked child"; ok=0; }
    fixture_before=$(reap_tree_snapshot "$fixture_root") || return 1
    /usr/bin/env -i PATH="$SYSTEM_PATH" HOME="${HOME:-/tmp}" LANG=C.UTF-8 \
        LC_ALL=C SHELL=/bin/bash TERM=xterm-256color TMPDIR=/tmp \
        HSQA_REAP_FIXTURE_ROOT="$fixture_root" \
        HSQA_REAP_FIXTURE_TOKEN="$fixture_token" \
        /bin/bash --noprofile --norc -p "$fixture_reaper" check \
        > "$SELFTEST_ROOT/fixture-clean-check.log" 2>&1
    fixture_status=$?
    if [ "$fixture_status" -ne 0 ]; then
        echo "FAIL: clean fixture check was not clean (status $fixture_status)"
        /usr/bin/sed 's/^/  clean fixture check: /' \
            "$SELFTEST_ROOT/fixture-clean-check.log" 2>/dev/null || true
        ok=0
    fi
    fixture_after=$(reap_tree_snapshot "$fixture_root") || return 1
    [ "$fixture_before" = "$fixture_after" ] \
        || { echo "FAIL: clean fixture check mutated bytes/identity/entries"; ok=0; }

    # Executable call-graph mutant: even if production scanners would mutate
    # a sentinel, the fixture branch must return before invoking either one.
    saved_matching=$(declare -f matching_procs) || return 1
    saved_tmux=$(declare -f reap_harness_tmux_sessions) || return 1
    fixture_branch_sentinel="$SELFTEST_ROOT/fixture-cross-scope-called"
    matching_procs() { : > "$fixture_branch_sentinel"; return 2; }
    reap_harness_tmux_sessions() { : > "$fixture_branch_sentinel"; return 2; }
    local previous_fixture_active="$REAP_FIXTURE_ACTIVE" previous_pid_dir="$PIDDIR"
    REAP_FIXTURE_ACTIVE=1; PIDDIR="$fixture_pid_dir"
    cmd_reap >/dev/null 2>&1 \
        || { echo "FAIL: clean pidfile-only fixture branch failed"; ok=0; }
    [ ! -e "$fixture_branch_sentinel" ] \
        || { echo "FAIL: fixture branch invoked production fallback/tmux"; ok=0; }
    REAP_FIXTURE_ACTIVE="$previous_fixture_active"; PIDDIR="$previous_pid_dir"
    eval "$saved_matching"; eval "$saved_tmux"

    /bin/kill "$sentinel_pid" 2>/dev/null || true
    wait "$sentinel_pid" 2>/dev/null || true
    /bin/kill -9 -- "-$fixture_group" 2>/dev/null || true
    /usr/bin/rm -rf -- "$fixture_root"
    /usr/bin/rm -rf -- "$fixture_repo"
    SELFTEST_EXTERNAL_FIXTURE=''; SELFTEST_EXTERNAL_REPO=''
    SELFTEST_EXTERNAL_GROUP=''; SELFTEST_EXTERNAL_SENTINEL=''

    # Synthetic seam variables are rejected before evidence creation for every
    # production command.  A stale shell environment must never redirect
    # process discovery or grant kill authority.
    local ambient_log
    ambient_log=$(mktemp) || return 1
    if HSQA_REAP_TEST_INPUT='999999 sleep 60' \
            HSQA_REAP_TEST_OWNED_INSTANCES='/tmp/fake fake' \
            HSQA_REAP_TEST_CMDLINES='999999 fake' \
            HSQA_REAP_TEST_STARTTIMES='999999 1' \
            HSQA_REAP_TEST_IDENTITIES='999999 perf-server' \
            HSQA_REAP_TEST_GROUPS='999999 999999' \
            /bin/bash --noprofile --norc -p "$0" check > "$ambient_log" 2>&1; then
        echo "FAIL: production reap check accepted ambient selftest seams"
        ok=0
    fi
    grep -q 'selftest-only seam' "$ambient_log" \
        || { echo "FAIL: ambient selftest seam rejection was not explicit"; ok=0; }
    rm -f -- "$ambient_log"

    if [ "$ok" = 1 ]; then echo "reap selftest: PASS (v1/v2 instance token + starttime/identity records + recycled PID REFUSE)"; return 0
    else echo "reap selftest: FAIL"; return 1; fi
}

case "$SUBCMD" in
    check)
        # Gate/check is a pure observation: no pid-root claim, evidence
        # directory, marker, symlink, socket finalization, log or manifest.
        cmd_check
        exit $?;;
    dry-run|dryrun)
        cmd_dryrun
        exit $?;;
    reap)
        # Only the explicitly mutating cleanup command records a teardown
        # artifact.  A failed/uncertain cleanup is durable and never presented
        # as a clean check.
        if [ "$REAP_FIXTURE_ACTIVE" -eq 1 ]; then
            cmd_reap
            exit $?
        fi
        ev_init "reap"
        LOGNAME=reap.log
        cmd_reap > "$EV_LOGS/$LOGNAME" 2>&1
        RC=$?
        /usr/bin/cat "$EV_LOGS/$LOGNAME"
        if [ "$RC" -eq 0 ]; then
            check_pass reap "clean — see logs/$LOGNAME"
            finish_result PASS
        else
            check_fail reap "cleanup incomplete/uncertain — see logs/$LOGNAME"
            finish_result FAIL
        fi
        write_reproduction "# Reproduce: reap
tools/hearthstead-qa reap reap
Verdict: $([ "$RC" -eq 0 ] && echo PASS || echo FAIL) (exit $RC)
Full transcript: logs/$LOGNAME
"
        exit "$RC";;
    selftest)
        cmd_selftest
        RC=$?
        exit "$RC";;
esac
