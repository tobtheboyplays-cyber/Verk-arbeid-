#!/usr/bin/env bash
# Internal exec boundary for lib_harness.sh.  Do not invoke directly.
set -u

registration="${1:-}"
release="${2:-}"
ack="${3:-}"
expected_parent="${4:-}"
expected_parent_start="${5:-}"
shift 5 || exit 125
[ "${1:-}" = -- ] || exit 125
shift
[ "$#" -gt 0 ] || exit 125
[[ "${HSQA_PROCESS_IDENTITY:-}" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || exit 125
[[ "$expected_parent" =~ ^[1-9][0-9]*$ ]] || exit 125
[[ "$expected_parent_start" =~ ^[0-9]+$ ]] || exit 125

trap - INT TERM
entry_pid="$BASHPID"
stat_line=$(/usr/bin/sed -E 's/^[0-9]+ \([^)]*\) //' "/proc/$entry_pid/stat" 2>/dev/null) \
    || exit 125
starttime=$(builtin printf '%s\n' "$stat_line" | /usr/bin/awk '{print $20}')
[[ "$starttime" =~ ^[0-9]+$ ]] || exit 125
builtin printf '%s|%s\n' "$entry_pid" "$starttime" > "$registration" || exit 125

# Pre-exec barrier: the payload is not allowed to fork, unset its identity, or
# otherwise escape until the parent has validated this exact PID/PGID/starttime
# and durably registered recovery authority. A SIGKILLed parent is detected by
# immutable /proc starttime, so this marked entry exits without descendants.
expected_release="$entry_pid|$starttime|$expected_parent|$expected_parent_start"
# The privileged entry itself starts under `env -i` + `bash -p`. Rebuild the
# payload environment from the same explicit allowlist at release so neither
# entry nor payload can inherit BASH_FUNC_*, BASH_ENV, or ambient build knobs.
# Infrastructure paths are pinned/validated by the controller; workload-
# specific values belong in the command vector (`env NAME=value command`).
canonical_path=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
safe_home="${HOME:-/tmp}"
case "$safe_home" in /*) ;; *) safe_home=/tmp;; esac
case "$safe_home" in *[$'\r\n']*) safe_home=/tmp;; esac
payload_environment=(
    /usr/bin/env -i
    "PATH=$canonical_path"
    "HOME=$safe_home"
    LANG=C.UTF-8
    LC_ALL=C
    TMPDIR=/tmp
    "HSQA_PROCESS_IDENTITY=$HSQA_PROCESS_IDENTITY"
)
for allowed_name in JAVA_HOME GRADLE_USER_HOME DISPLAY XAUTHORITY XDG_RUNTIME_DIR TERM \
        HSQA_ACTIVE HSQA_REPO HSQA_REPORTS HSQA_ARTIFACTS HSQA_SAFE_TMP_ROOT \
        HSQA_PIDDIR HSQA_INST_ROOT HSQA_INSTALL_DIR HSQA_CLIENT_INSTALL_DIR \
        HSQA_GRADLE_CACHE HSQA_LEVEL_TYPE HSQA_TMUX_SOCKET HSQA_PROTO \
        HSQA_REAP_FIXTURE_ROOT HSQA_REAP_FIXTURE_TOKEN \
        HSQA_PERF_FIXTURE_MODE HSQA_PERF_FIXTURE_ROOT HSQA_PERF_FIXTURE_TOKEN; do
    if [[ -v "$allowed_name" ]]; then
        allowed_value="${!allowed_name}"
        case "$allowed_value" in *[$'\r\n']*) exit 125;; esac
        payload_environment+=("$allowed_name=$allowed_value")
    fi
done
for _ in $(/usr/bin/seq 1 1000); do
    parent_line=$(/usr/bin/sed -E 's/^[0-9]+ \([^)]*\) //' \
        "/proc/$expected_parent/stat" 2>/dev/null) || exit 125
    parent_start=$(builtin printf '%s\n' "$parent_line" | /usr/bin/awk '{print $20}')
    [ "$parent_start" = "$expected_parent_start" ] || exit 125
    if [ -f "$release" ] && [ ! -L "$release" ]; then
        IFS= read -r release_value < "$release" || exit 125
        [ "$release_value" = "$expected_release" ] || exit 125
        builtin printf '%s\n' "$expected_release" > "$ack" || exit 125
        exec "${payload_environment[@]}" "$@"
    fi
    /usr/bin/sleep 0.01
done
exit 125
