#!/usr/bin/env bash
# Exact tracked tmux-window launch. Source after lib_harness.sh.

HSQA_TMUX_TRACKED_ENTRY="${HSQA_TRACKED_ENTRY:?lib_harness.sh must be sourced first}"
HSQA_TMUX_DEFAULT_SOCKET=/tmp/claude-0/hearthstead-qa-v2.tmux
HSQA_TMUX_SOCKET="${HSQA_TMUX_SOCKET:-$HSQA_TMUX_DEFAULT_SOCKET}"
if [ "${HSQA_TMUX_SELFTEST_MODE:-0}" = 1 ]; then
    HSQA_TMUX_SELFTEST_ROOT="${HSQA_PIDDIR%/pids}"
    case "$HSQA_TMUX_SELFTEST_ROOT" in /tmp/hsqa-tmux-tracked.*) ;;
        *) echo "FATAL: unsafe tmux selftest root" >&2; return 2;;
    esac
    [ "$HSQA_PIDDIR" = "$HSQA_TMUX_SELFTEST_ROOT/pids" ] \
        && [ "$HSQA_TMUX_SOCKET" = "$HSQA_TMUX_SELFTEST_ROOT/tmux.sock" ] \
        || { echo "FATAL: tmux selftest socket/PID roots diverge" >&2; return 2; }
elif [ "$HSQA_TMUX_SOCKET" != "$HSQA_TMUX_DEFAULT_SOCKET" ]; then
    echo "FATAL: production tmux socket override is forbidden" >&2
    return 2
fi
case "$HSQA_TMUX_SOCKET" in /|''|*[$'\r\n']*) return 2;; esac
[ "$(realpath -ms -- "$HSQA_TMUX_SOCKET")" = \
    "$(realpath -m -- "$HSQA_TMUX_SOCKET")" ] || return 2

hsqa_tmux() {
    # The tmux server may itself invoke an outer shell before our tracked
    # entry. Start both client and server from a minimal environment so
    # BASH_ENV, imported functions, TMUX and user config cannot execute or
    # redirect anything ahead of the durable pre-exec barrier.
    /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        HOME=/tmp LANG=C.UTF-8 LC_ALL=C SHELL=/bin/sh \
        TERM=xterm-256color TMPDIR=/tmp \
        /usr/bin/tmux -f /dev/null -S "$HSQA_TMUX_SOCKET" "$@"
}

hsqa_prepare_tmux_socket_parent() {
    local parent current_uid mode
    parent="${HSQA_TMUX_SOCKET%/*}"
    current_uid=$(id -u) || return 2
    if [ ! -e "$parent" ]; then
        case "$parent" in /tmp/claude-0|/tmp/hsqa-tmux-tracked.*) ;;
            *) return 2;;
        esac
        mkdir -m 700 -- "$parent" || return 2
    fi
    [ -d "$parent" ] && [ ! -L "$parent" ] \
        && [ "$(realpath -ms -- "$parent")" = "$(realpath -m -- "$parent")" ] \
        && [ "$(stat -c %u -- "$parent" 2>/dev/null)" = "$current_uid" ] \
        || return 2
    mode=$(stat -c %a -- "$parent" 2>/dev/null) || return 2
    [ "$mode" = 700 ] || return 2
    if [ -e "$HSQA_TMUX_SOCKET" ] || [ -L "$HSQA_TMUX_SOCKET" ]; then
        [ -S "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] \
            && [ "$(stat -c %u -- "$HSQA_TMUX_SOCKET" 2>/dev/null)" = "$current_uid" ] \
            || return 2
    fi
}

hsqa_validate_tmux_name() { # <session-or-window>
    [[ "$1" =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$ ]]
}

hsqa_snapshot_tmux_sessions() { # prints immutable session IDs; absent server is empty success
    local attempt value
    for attempt in $(seq 1 100); do
        if [ -S "$HSQA_TMUX_SOCKET" ]; then
            if value=$(hsqa_tmux list-sessions -F '#{session_id}' 2>/dev/null); then
                printf '%s\n' "$value"
                return 0
            fi
            sleep 0.01
            continue
        fi
        [ ! -e "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] \
            && return 0
        return 2
    done
    # tmux can leave its owned Unix-socket inode briefly after the final
    # server exits. A query error is tolerated only if a direct Unix connect
    # proves no server is listening; permission/other errors remain fatal.
    [ ! -e "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] \
        && return 0
    [ -S "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] || return 2
    python3 - "$HSQA_TMUX_SOCKET" <<'PYEOF'
import errno, socket, sys
client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
try:
    client.connect(sys.argv[1])
except OSError as exc:
    raise SystemExit(0 if exc.errno in (errno.ECONNREFUSED, errno.ENOENT) else 2)
else:
    raise SystemExit(1)
finally:
    client.close()
PYEOF
}

hsqa_tmux_exact_session_present() { # <session-id>; 0=present, 1=proven absent, 2=query error
    local wanted="$1" sessions id
    [[ "$wanted" =~ ^\$[0-9]+$ ]] || return 2
    sessions=$(hsqa_snapshot_tmux_sessions) || return 2
    while IFS= read -r id; do
        [ -z "$id" ] && continue
        [[ "$id" =~ ^\$[0-9]+$ ]] || return 2
        [ "$id" = "$wanted" ] && return 0
    done <<< "$sessions"
    return 1
}

hsqa_finalize_empty_tmux_socket() { # remove only an owned, proven listener-free canonical socket
    local sessions selftest_root=''
    sessions=$(hsqa_snapshot_tmux_sessions) || return 1
    [ -z "$sessions" ] || return 2
    [ ! -e "$HSQA_TMUX_SOCKET" ] && [ ! -L "$HSQA_TMUX_SOCKET" ] \
        && return 0
    [ "${HSQA_TMUX_SELFTEST_MODE:-0}" = 1 ] \
        && selftest_root="$HSQA_TMUX_SELFTEST_ROOT"
    /usr/bin/python3 -I - "$HSQA_TMUX_SOCKET" "$selftest_root" <<'PYEOF'
import ctypes
import errno
import os
import secrets
import socket
import stat
import sys
import time

path, selftest_root = sys.argv[1:]
parent = os.path.dirname(path)
name = os.path.basename(path)
uid = os.getuid()
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
root_fd = None

def same_inode(left, right):
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino

def rename_noreplace(first, second):
    libc = ctypes.CDLL(None, use_errno=True)
    function = getattr(libc, "renameat2", None)
    if function is None:
        raise RuntimeError("renameat2 unavailable")
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int,
                         ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(root_fd, os.fsencode(first), root_fd, os.fsencode(second), 1) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))

def listener_refused(target):
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        client.connect(target)
    except OSError as exc:
        if exc.errno == errno.ECONNREFUSED:
            return True
        raise RuntimeError(f"socket observation error: {exc.errno}") from exc
    finally:
        client.close()
    return False

def selftest_barrier():
    if not selftest_root:
        return
    expected_root = os.path.dirname(path)
    if (selftest_root != expected_root
            or not selftest_root.startswith("/tmp/hsqa-tmux-tracked.")
            or os.path.realpath(selftest_root) != selftest_root):
        raise RuntimeError("unsafe socket-finalize selftest root")
    ready = os.path.join(selftest_root, "socket-finalize-ready")
    hold = os.path.join(selftest_root, "socket-finalize-hold")
    if not os.path.lexists(hold):
        return
    ready_fd = os.open(ready, os.O_WRONLY | os.O_CREAT | os.O_EXCL
                       | cloexec | nofollow, 0o600)
    try:
        if os.write(ready_fd, b"ready\n") != 6:
            raise RuntimeError("short socket-finalize barrier write")
        os.fsync(ready_fd)
    finally:
        os.close(ready_fd)
    for _ in range(500):
        if not os.path.lexists(hold):
            return
        time.sleep(0.01)
    raise RuntimeError("socket-finalize barrier timeout")

try:
    root_fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY | cloexec | nofollow)
    root_stat = os.fstat(root_fd)
    if (not stat.S_ISDIR(root_stat.st_mode) or root_stat.st_uid != uid
            or stat.S_IMODE(root_stat.st_mode) != 0o700):
        raise RuntimeError("unsafe tmux socket parent")
    initial = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    if (not stat.S_ISSOCK(initial.st_mode) or initial.st_uid != uid
            or initial.st_nlink != 1):
        raise RuntimeError("unsafe tmux socket inode")
    if not listener_refused(path):
        raise RuntimeError("tmux socket still has a listener")
    current = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    if (not same_inode(current, initial) or not stat.S_ISSOCK(current.st_mode)
            or current.st_uid != uid or current.st_nlink != 1):
        raise RuntimeError("tmux socket changed during observation")
    selftest_barrier()

    # Never validate and unlink the predictable public socket name.  Move the
    # current inode atomically behind an unpredictable NOREPLACE name, inspect
    # the moved inode, and delete only the exact listener-free socket observed
    # above.  A live/foreign replacement is restored or retained as durable
    # red evidence and is never unlinked.
    quarantine = f".{name}.empty-{secrets.token_hex(12)}"
    rename_noreplace(name, quarantine)
    moved = os.stat(quarantine, dir_fd=root_fd, follow_symlinks=False)
    if (not same_inode(moved, initial) or not stat.S_ISSOCK(moved.st_mode)
            or moved.st_uid != uid or moved.st_nlink != 1):
        try:
            rename_noreplace(quarantine, name)
        except OSError:
            pass
        os.fsync(root_fd)
        raise RuntimeError("tmux socket replaced before quarantine")
    quarantine_path = os.path.join(parent, quarantine)
    if not listener_refused(quarantine_path):
        try:
            rename_noreplace(quarantine, name)
        except OSError:
            pass
        os.fsync(root_fd)
        raise RuntimeError("quarantined tmux socket has a listener")
    os.unlink(quarantine, dir_fd=root_fd)
    try:
        os.stat(name, dir_fd=root_fd, follow_symlinks=False)
    except FileNotFoundError:
        pass
    else:
        raise RuntimeError("tmux socket survived quarantine removal")
    os.fsync(root_fd)
except Exception:
    raise SystemExit(1)
finally:
    if root_fd is not None:
        os.close(root_fd)
PYEOF
}

hsqa_parse_tmux_identity() { # <identity>
    [[ "$1" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]]
}

hsqa_tmux_window_topology() { # <window-id> <pane-id> <pane-pid>
    local window_id="$1" pane_id="$2" pane_pid="$3" topology
    [[ "$window_id" =~ ^@[0-9]+$ ]] \
        && [[ "$pane_id" =~ ^%[0-9]+$ ]] \
        && [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    topology=$(hsqa_tmux list-panes -t "$window_id" \
        -F '#{window_id}|#{pane_id}|#{pane_pid}' 2>/dev/null) || return 1
    [ "$topology" = "$window_id|$pane_id|$pane_pid" ]
}

hsqa_tmux_exact_pane_tuple() { # <pane-id>; prints exact pane-id|pid, 1=absent, 2=malformed/duplicate
    local wanted="$1" panes sessions line pane_id pane_pid extra found='' count=0
    [[ "$wanted" =~ ^%[0-9]+$ ]] || return 2
    if ! panes=$(hsqa_tmux list-panes -a -F '#{pane_id}|#{pane_pid}' 2>/dev/null); then
        sessions=$(hsqa_snapshot_tmux_sessions) || return 2
        [ -z "$sessions" ] && return 1
        return 2
    fi
    while IFS= read -r line; do
        IFS='|' read -r pane_id pane_pid extra <<< "$line"
        [ -z "${extra:-}" ] && [[ "$pane_id" =~ ^%[0-9]+$ ]] \
            && [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] || return 2
        [ "$pane_id" = "$wanted" ] || continue
        found="$pane_id|$pane_pid"
        count=$((count + 1))
    done <<< "$panes"
    [ "$count" -eq 0 ] && return 1
    [ "$count" -eq 1 ] || return 2
    printf '%s\n' "$found"
}

hsqa_parse_tmux_creation() { # <$session|@window|%pane|pid> <session-var> <window-var> <pane-var> <pid-var>
    local value="$1" extra parsed_session parsed_window parsed_pane parsed_pid
    local -n output_session="$2" output_window="$3" output_pane="$4" output_pid="$5"
    IFS='|' read -r parsed_session parsed_window parsed_pane parsed_pid extra \
        <<< "$value"
    [ -z "${extra:-}" ] \
        && [[ "$parsed_session" =~ ^\$[0-9]+$ ]] \
        && [[ "$parsed_window" =~ ^@[0-9]+$ ]] \
        && [[ "$parsed_pane" =~ ^%[0-9]+$ ]] \
        && [[ "$parsed_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    output_session="$parsed_session"
    output_window="$parsed_window"
    output_pane="$parsed_pane"
    output_pid="$parsed_pid"
}

hsqa_recover_added_tmux_session() { # <name> <control-identity> <before-session-list> <session-var> <window-var> <pane-var> <pid-var>
    local name="$1" control_identity="$2" before="$3" sessions line extra attempt
    local candidate_session candidate_name windows candidate_window window_name pane_count
    local topology candidate_pane candidate_pid found_session='' found_window=''
    local found_pane='' found_pid='' count
    local -a window_lines
    local -n output_session="$4" output_window="$5" output_pane="$6" output_pid="$7"
    for attempt in $(seq 1 200); do
        sessions=$(hsqa_tmux list-sessions -F '#{session_id}|#{session_name}' \
            2>/dev/null) || return 2
        count=0
        while IFS= read -r line; do
            IFS='|' read -r candidate_session candidate_name extra <<< "$line"
            [ -z "${extra:-}" ] && [[ "$candidate_session" =~ ^\$[0-9]+$ ]] \
                || return 2
            grep -Fxq -- "$candidate_session" <<< "$before" && continue
            [ "$candidate_name" = "$name" ] || continue
            windows=$(hsqa_tmux list-windows -t "$candidate_session" \
                -F '#{window_id}|#{window_name}|#{window_panes}' 2>/dev/null) \
                || continue
            mapfile -t window_lines <<< "$windows"
            [ "${#window_lines[@]}" -eq 1 ] || continue
            IFS='|' read -r candidate_window window_name pane_count extra \
                <<< "${window_lines[0]}"
            [ -z "${extra:-}" ] && [[ "$candidate_window" =~ ^@[0-9]+$ ]] \
                && [ "$window_name" = control ] && [ "$pane_count" = 1 ] \
                || continue
            topology=$(hsqa_tmux list-panes -t "$candidate_window" \
                -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
                2>/dev/null) || continue
            candidate_pane=''; candidate_pid=''
            hsqa_parse_tmux_creation "$topology" candidate_session candidate_window \
                candidate_pane candidate_pid || continue
            hsqa_pid_has_exact_identity "$candidate_pid" "$control_identity" \
                || continue
            found_session="$candidate_session"; found_window="$candidate_window"
            found_pane="$candidate_pane"; found_pid="$candidate_pid"
            count=$((count + 1))
        done <<< "$sessions"
        if [ "$count" -eq 1 ]; then
            output_session="$found_session"; output_window="$found_window"
            output_pane="$found_pane"; output_pid="$found_pid"
            return 0
        fi
        [ "$count" -eq 0 ] || return 2
        sleep 0.01
    done
    return 2
}

hsqa_recover_added_tmux_window() { # <session-id> <before-list> <identity> <session-var> <window-var> <pane-var> <pid-var>
    local session_id="$1" before="$2" identity="$3" after id topology
    local found_session='' found_window='' found_pane='' found_pid=''
    local candidate_session candidate_window candidate_pane candidate_pid count attempt
    local -n output_session="$4" output_window="$5" output_pane="$6" output_pid="$7"
    for attempt in $(seq 1 200); do
        count=0
        after=$(hsqa_tmux list-windows -t "$session_id" -F '#{window_id}' \
            2>/dev/null) || return 2
        while IFS= read -r id; do
            [[ "$id" =~ ^@[0-9]+$ ]] || return 2
            grep -Fxq -- "$id" <<< "$before" && continue
            topology=$(hsqa_tmux list-panes -t "$id" \
                -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
                2>/dev/null) || continue
            candidate_session=''; candidate_window=''; candidate_pane=''; candidate_pid=''
            hsqa_parse_tmux_creation "$topology" candidate_session candidate_window \
                candidate_pane candidate_pid || continue
            [ "$candidate_session" = "$session_id" ] \
                && [ "$candidate_window" = "$id" ] \
                && hsqa_pid_has_exact_identity "$candidate_pid" "$identity" \
                || continue
            found_session="$candidate_session"; found_window="$candidate_window"
            found_pane="$candidate_pane"; found_pid="$candidate_pid"
            count=$((count + 1))
        done <<< "$after"
        if [ "$count" -eq 1 ]; then
            output_session="$found_session"; output_window="$found_window"
            output_pane="$found_pane"; output_pid="$found_pid"
            return 0
        fi
        [ "$count" -eq 0 ] || return 2
        sleep 0.01
    done
    return 2
}

hsqa_tmux_session_owned() { # <immutable-session-id> <owner-identity>
    local session_id="$1" owner="$2" option windows line window_id window_name
    local control_identity pane_count extra identity topology pane_id pane_pid more seen=0 control=0
    [[ "$session_id" =~ ^\$[0-9]+$ ]] && hsqa_parse_tmux_identity "$owner" \
        || return 1
    hsqa_tmux has-session -t "$session_id" 2>/dev/null || return 1
    option=$(hsqa_tmux show-options -v -t "$session_id" @hsqa_owner 2>/dev/null) \
        || return 1
    [ "$option" = "$owner" ] || return 1
    control_identity=$(hsqa_tmux show-options -v -t "$session_id" \
        @hsqa_control_identity 2>/dev/null) || return 1
    hsqa_parse_tmux_identity "$control_identity" || return 1
    windows=$(hsqa_tmux list-windows -t "$session_id" \
        -F '#{window_id}|#{window_name}|#{window_panes}' 2>/dev/null) \
        || return 1
    [ -n "$windows" ] || return 1
    while IFS= read -r line; do
        IFS='|' read -r window_id window_name pane_count extra <<< "$line"
        [ -z "${extra:-}" ] && [[ "$window_id" =~ ^@[0-9]+$ ]] \
            && [ "$pane_count" = 1 ] || return 1
        identity=$(hsqa_tmux show-options -w -v -t "$window_id" \
            @hsqa_identity 2>/dev/null) || return 1
        hsqa_parse_tmux_identity "$identity" || return 1
        if [ "$window_name" = control ]; then
            [ "$identity" = "$control_identity" ] || return 1
            control=$((control + 1))
        fi
        topology=$(hsqa_tmux list-panes -t "$window_id" \
            -F '#{pane_id}|#{pane_pid}' 2>/dev/null) || return 1
        IFS='|' read -r pane_id pane_pid more <<< "$topology"
        [ -z "${more:-}" ] && [[ "$pane_id" =~ ^%[0-9]+$ ]] \
            && [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] \
            && hsqa_pid_has_exact_identity "$pane_pid" "$identity" \
            || return 1
        seen=$((seen + 1))
    done <<< "$windows"
    [ "$seen" -ge 1 ] && [ "$control" -eq 1 ]
}

hsqa_tmux_control_tuple_owned() { # <session> <window> <pane> <pid> <control-identity>
    local session_id="$1" window_id="$2" pane_id="$3" pane_pid="$4"
    local control_identity="$5" value parsed_session parsed_window window_name
    local pane_count parsed_pane parsed_pid extra
    value=$(hsqa_tmux display-message -p -t "$pane_id" \
        '#{session_id}|#{window_id}|#{window_name}|#{window_panes}|#{pane_id}|#{pane_pid}' \
        2>/dev/null) || return 1
    IFS='|' read -r parsed_session parsed_window window_name pane_count parsed_pane \
        parsed_pid extra <<< "$value"
    [ -z "${extra:-}" ] && [ "$parsed_session" = "$session_id" ] \
        && [ "$parsed_window" = "$window_id" ] && [ "$window_name" = control ] \
        && [ "$pane_count" = 1 ] && [ "$parsed_pane" = "$pane_id" ] \
        && [ "$parsed_pid" = "$pane_pid" ] \
        && hsqa_pid_has_exact_identity "$pane_pid" "$control_identity"
}

hsqa_create_owned_tmux_session() { # <session-name> <owner-identity> <signal-handler> <session-id-var>
    local session="$1" owner="$2" signal_handler="$3" output_name="$4"
    local control_command creation='' before_sessions='' created_session='' window_id='' pane_id=''
    local control_identity create_ok=0
    local pane_pid='' pane_start='' current_start='' attempt
    local status=0 cleanup_ok=1 finish_status=0
    local PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    local -n output_session_id="$output_name"
    hsqa_validate_tmux_name "$session" && hsqa_parse_tmux_identity "$owner" \
        || return 2
    output_session_id=''
    hsqa_prepare_tmux_socket_parent || return 2
    # A same-name session is never adopted or killed. The caller must first
    # prove the host clean; this refusal keeps an unrelated user's session safe.
    hsqa_tmux has-session -t "$session" 2>/dev/null && return 2
    before_sessions=$(hsqa_snapshot_tmux_sessions) || return 2
    while IFS= read -r attempt; do
        [ -z "$attempt" ] || [[ "$attempt" =~ ^\$[0-9]+$ ]] || return 2
    done <<< "$before_sessions"
    control_identity="tmux-control-$(hsqa_recovery_token)"
    hsqa_parse_tmux_identity "$control_identity" || return 2
    printf -v control_command '%q ' exec /usr/bin/env \
        "HSQA_PROCESS_IDENTITY=$control_identity" /usr/bin/sleep 2147483647
    hsqa_begin_registration_window "$signal_handler"
    if creation=$(hsqa_tmux new-session -d -P \
            -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' \
            -s "$session" -n control -x 500 -y 50 "$control_command"); then
        create_ok=1
    fi
    if [ "$create_ok" -eq 1 ]; then
        if ! hsqa_parse_tmux_creation "$creation" created_session window_id \
                pane_id pane_pid \
                || ! hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid"; then
            if ! hsqa_recover_added_tmux_session "$session" "$control_identity" \
                    "$before_sessions" created_session window_id pane_id pane_pid; then
                # No immutable tuple means no destructive authority. The
                # stable name remains visible to gate/reap and keeps it red.
                status=125
                cleanup_ok=0
            fi
        fi
    else
        status=2
        # tmux can return nonzero after it has created a session. Recover only
        # the one immutable session newly added after our snapshot and carrying
        # this launch's random control identity; never adopt by reusable name.
        if hsqa_recover_added_tmux_session "$session" "$control_identity" \
                "$before_sessions" created_session window_id pane_id pane_pid; then
            :
        else
            created_session=''; window_id=''; pane_id=''; pane_pid=''
        fi
    fi
    if [[ "$created_session" =~ ^\$[0-9]+$ ]]; then
        if [ "$status" -eq 0 ] || [ "$create_ok" -eq 0 ]; then
            pane_start=''
            for attempt in $(seq 1 200); do
                current_start=$(hsqa_process_starttime "$pane_pid")
                if [[ "$current_start" =~ ^[0-9]+$ ]] \
                        && hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid" \
                        && hsqa_pid_has_exact_identity "$pane_pid" "$control_identity"; then
                    if [ -z "$pane_start" ]; then
                        pane_start="$current_start"
                    elif [ "$current_start" = "$pane_start" ]; then
                        break
                    else
                        pane_start=''
                    fi
                else
                    pane_start=''
                fi
                sleep 0.01
            done
            if ! [[ "$pane_start" =~ ^[0-9]+$ ]] \
                    || [ "$current_start" != "$pane_start" ] \
                    || ! hsqa_pid_has_exact_identity "$pane_pid" "$control_identity" \
                    || ! hsqa_tmux set-option -t "$created_session" \
                        @hsqa_owner "$owner" 2>/dev/null \
                    || ! hsqa_tmux set-option -t "$created_session" \
                        @hsqa_control_identity "$control_identity" 2>/dev/null \
                    || ! hsqa_tmux set-option -w -t "$window_id" \
                        @hsqa_identity "$control_identity" 2>/dev/null \
                    || ! hsqa_tmux_session_owned "$created_session" "$owner"; then
                status=2
            fi
        fi
    fi
    if [ "$status" -eq 0 ] && [ "$HSQA_LAUNCH_PENDING" -eq 0 ]; then
        output_session_id="$created_session"
    else
        [ "$status" -ne 0 ] || status=2
        current_start=$(hsqa_process_starttime "$pane_pid")
        if [[ "$created_session" =~ ^\$[0-9]+$ ]] \
                && [[ "$pane_start" =~ ^[0-9]+$ ]] \
                && [ "$current_start" = "$pane_start" ] \
                && hsqa_tmux_control_tuple_owned "$created_session" "$window_id" \
                    "$pane_id" "$pane_pid" "$control_identity"; then
            # Pane IDs are immutable authority. Killing only our exact control
            # pane preserves any concurrent foreign split/window; tmux closes
            # the session automatically only when this was its final pane.
            hsqa_kill_exact_tmux_pane "$pane_id" "$pane_pid" "$pane_start" \
                "$control_identity" || cleanup_ok=0
            hsqa_tmux_exact_session_present "$created_session"
            case $? in 0) status=125;; 1) ;; *) cleanup_ok=0; status=125;; esac
        elif [[ "$created_session" =~ ^\$[0-9]+$ ]]; then
            cleanup_ok=0
            status=125
        fi
        output_session_id=''
    fi
    hsqa_finish_registration_window "$signal_handler" || finish_status=$?
    [ "$cleanup_ok" -eq 1 ] || return 125
    [ "$finish_status" -eq 0 ] || return "$finish_status"
    [ "$status" -eq 0 ]
}

hsqa_stop_owned_tmux_session() { # <immutable-session-id> <owner-identity>
    local session_id="$1" owner="$2" attempt windows window_id window_name
    local pane_count pane_id pane_pid control_identity topology start extra
    local -a window_lines
    local PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    hsqa_tmux_exact_session_present "$session_id"
    case $? in 1) return 0;; 0) ;; *) return 1;; esac
    # Workload panes are never a session-cleanup target. Every caller must
    # first stop its exact tracked record/group; the workload window then
    # closes naturally. Give that close a bounded grace period, but never kill
    # the workload itself. Only the one marked control pane is authorized.
    for attempt in $(seq 1 50); do
        windows=$(hsqa_tmux list-windows -t "$session_id" \
            -F '#{window_id}|#{window_name}|#{window_panes}' \
            2>/dev/null) || return 1
        mapfile -t window_lines <<< "$windows"
        [ "${#window_lines[@]}" -eq 1 ] && break
        sleep 0.1
    done
    [ "${#window_lines[@]}" -eq 1 ] || {
        echo "FATAL: workload/foreign tmux panes remain in $session_id" >&2
        return 1
    }
    IFS='|' read -r window_id window_name pane_count extra \
        <<< "${window_lines[0]}"
    [ -z "${extra:-}" ] && [ "$window_name" = control ] \
        && [ "$pane_count" = 1 ] || return 1
    hsqa_tmux_session_owned "$session_id" "$owner" || {
        echo "FATAL: refusing to kill unowned/replaced tmux session $session_id" >&2
        return 1
    }
    control_identity=$(hsqa_tmux show-options -v -t "$session_id" \
        @hsqa_control_identity 2>/dev/null) || return 1
    topology=$(hsqa_tmux list-panes -t "$window_id" \
        -F '#{pane_id}|#{pane_pid}' 2>/dev/null) || return 1
    IFS='|' read -r pane_id pane_pid extra <<< "$topology"
    [ -z "${extra:-}" ] && [[ "$pane_id" =~ ^%[0-9]+$ ]] \
        && [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    start=$(hsqa_process_starttime "$pane_pid")
    [[ "$start" =~ ^[0-9]+$ ]] \
        && hsqa_pid_has_exact_identity "$pane_pid" "$control_identity" || return 1
    hsqa_kill_exact_tmux_pane "$pane_id" "$pane_pid" "$start" \
        "$control_identity" || return 1
    for attempt in $(seq 1 50); do
        hsqa_tmux_exact_session_present "$session_id"
        case $? in
            1)
                hsqa_finalize_empty_tmux_socket
                case $? in
                    0|2) return 0;;
                    *) sleep 0.1; continue;;
                esac
                ;;
            0) ;;
            *) return 1;;
        esac
        sleep 0.1
    done
    echo "FATAL: tmux session $session_id retained an unowned/concurrent pane" >&2
    return 1
}

hsqa_kill_exact_tmux_pane() { # <pane-id> <pane-pid> <immutable-start> <identity>
    local pane_id="$1" pane_pid="$2" expected_start="$3" identity="$4"
    local attempt current parsed_pane parsed_pid extra current_start tuple_status
    current=$(hsqa_tmux_exact_pane_tuple "$pane_id"); tuple_status=$?
    if [ "$tuple_status" -eq 1 ]; then
        for attempt in $(seq 1 50); do
            current_start=$(hsqa_process_starttime "$pane_pid")
            [ -z "$current_start" ] || [ "$current_start" != "$expected_start" ] \
                && return 0
            sleep 0.1
        done
        return 1
    fi
    [ "$tuple_status" -eq 0 ] || return 1
    IFS='|' read -r parsed_pane parsed_pid extra <<< "$current"
    [ -z "${extra:-}" ] && [ "$parsed_pane" = "$pane_id" ] \
        && [ "$parsed_pid" = "$pane_pid" ] || {
        echo "FATAL: refusing to kill replaced tmux pane $pane_id" >&2
        return 1
    }
    current_start=$(hsqa_process_starttime "$pane_pid")
    [ "$current_start" = "$expected_start" ] \
        && hsqa_pid_has_exact_identity "$pane_pid" "$identity" || {
        echo "FATAL: refusing tmux pane $pane_id with changed process identity" >&2
        return 1
    }
    hsqa_tmux kill-pane -t "$pane_id" 2>/dev/null || return 1
    for attempt in $(seq 1 50); do
        current=$(hsqa_tmux_exact_pane_tuple "$pane_id"); tuple_status=$?
        current_start=$(hsqa_process_starttime "$pane_pid")
        if [ "$tuple_status" -eq 1 ] \
                && { [ -z "$current_start" ] \
                    || [ "$current_start" != "$expected_start" ]; }; then
            return 0
        fi
        [ "$tuple_status" -ne 2 ] || return 1
        sleep 0.1
    done
    return 1
}

hsqa_tmux_pane_record_owned() { # <pane-id> <pane-pid> <record> <identity>
    local pane_id="$1" pane_pid="$2" record="$3" identity="$4"
    local tuple tuple_status parsed_pane parsed_pid extra target leader start recorded_identity
    tuple=$(hsqa_tmux_exact_pane_tuple "$pane_id"); tuple_status=$?
    [ "$tuple_status" -eq 0 ] || return 1
    IFS='|' read -r parsed_pane parsed_pid extra <<< "$tuple"
    [ -z "${extra:-}" ] && [ "$parsed_pane" = "$pane_id" ] \
        && [ "$parsed_pid" = "$pane_pid" ] || return 1
    IFS='|' read -r target leader start recorded_identity extra <<< "$record"
    [ -z "${extra:-}" ] && [ "$target" = "-$pane_pid" ] \
        && [ "$leader" = "$pane_pid" ] && [ "$recorded_identity" = "$identity" ] \
        && [ "$(hsqa_process_starttime "$pane_pid")" = "$start" ] \
        && hsqa_record_owned "$record"
}

hsqa_launch_tracked_tmux_window() { # session-id owner window role identity handler window-id-var pane-id-var pid-var pgid-var record-var role-var command [args...]
    local session_id="$1" owner="$2" window="$3" role="$4" identity="$5"
    local signal_handler="$6" window_var="$7" pane_var="$8" pid_var="$9"
    local pgid_var="${10}" record_var="${11}" effective_role_var="${12}"
    shift 12
    local registration release ack release_tmp='' parent_start tmux_command creation=''
    local before_windows='' pane_start=''
    local created_session='' window_id='' pane_id='' pane_pid='' pgid='' child_start='' current_start=''
    local controller_pgid='' candidate='' record='' release_token='' ack_value=''
    local attempt provisional_record emergency emergency_tmp finish_status=0
    local status=0 released=0 cleanup_ok=1 created=0 topology_ok=0
    local PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    local -a entry_environment=()
    [ "$#" -gt 0 ] || return 2
    [[ "$session_id" =~ ^\$[0-9]+$ ]] && hsqa_validate_tmux_name "$window" \
        && hsqa_parse_tmux_identity "$owner" \
        && hsqa_parse_tmux_identity "$role" \
        && hsqa_parse_tmux_identity "$identity" || return 2
    hsqa_build_tracked_entry_environment entry_environment "$identity" || return 2
    hsqa_tmux_session_owned "$session_id" "$owner" || return 2
    before_windows=$(hsqa_tmux list-windows -t "$session_id" -F '#{window_id}' \
        2>/dev/null) || return 2
    registration=$(mktemp "$HSQA_PIDDIR/.tmux-launch.XXXXXX") || return 2
    release="$registration.release"
    ack="$registration.ack"
    parent_start=$(hsqa_process_starttime "$$")
    if ! [[ "$parent_start" =~ ^[0-9]+$ ]]; then
        rm -f -- "$registration"
        return 2
    fi
    printf -v "$window_var" '%s' ''
    printf -v "$pane_var" '%s' ''
    printf -v "$effective_role_var" '%s' "$role"
    printf -v "$pid_var" '%s' ''
    printf -v "$pgid_var" '%s' ''
    printf -v "$record_var" '%s' ''

    hsqa_begin_registration_window "$signal_handler"
    printf -v tmux_command '%q ' exec "${entry_environment[@]}" \
        /bin/bash --noprofile --norc -p "$HSQA_TMUX_TRACKED_ENTRY" \
        "$registration" "$release" "$ack" \
        "$$" "$parent_start" -- "$@"
    if creation=$(hsqa_tmux new-window -d -P \
            -F '#{session_id}|#{window_id}|#{pane_id}|#{pane_pid}' -t "$session_id" \
            -n "$window" "$tmux_command"); then
        created=1
        if hsqa_parse_tmux_creation "$creation" created_session window_id pane_id pane_pid \
                && [ "$created_session" = "$session_id" ] \
                && hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid"; then
            topology_ok=1
            pane_start=$(hsqa_process_starttime "$pane_pid")
            printf -v "$window_var" '%s' "$window_id"
            printf -v "$pane_var" '%s' "$pane_id"
            printf -v "$pid_var" '%s' "$pane_pid"
            printf -v "$pgid_var" '%s' "$pane_pid"
        elif hsqa_recover_added_tmux_window "$session_id" "$before_windows" \
                "$identity" created_session window_id pane_id pane_pid \
                && hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid"; then
            topology_ok=1
            pane_start=$(hsqa_process_starttime "$pane_pid")
            printf -v "$window_var" '%s' "$window_id"
            printf -v "$pane_var" '%s' "$pane_id"
            printf -v "$pid_var" '%s' "$pane_pid"
            printf -v "$pgid_var" '%s' "$pane_pid"
        else
            # A successful tmux create without immutable IDs cannot be killed
            # safely. Keep the named session visible to the cleanliness gate.
            status=125
            cleanup_ok=0
        fi
    else
        status=2
        # A tmux client can report nonzero after the server has already added
        # the window. Recover only one newly-added immutable pane carrying this
        # exact launch identity; it remains behind the pre-exec barrier and is
        # retired below without ever releasing the payload.
        if hsqa_recover_added_tmux_window "$session_id" "$before_windows" \
                "$identity" created_session window_id pane_id pane_pid \
                && hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid"; then
            created=1
            topology_ok=1
            pane_start=$(hsqa_process_starttime "$pane_pid")
            printf -v "$window_var" '%s' "$window_id"
            printf -v "$pane_var" '%s' "$pane_id"
            printf -v "$pid_var" '%s' "$pane_pid"
            printf -v "$pgid_var" '%s' "$pane_pid"
        else
            # If the before/after diff is ambiguous, no name-based destructive
            # fallback is authorized. The canonical socket remains gate-red.
            cleanup_ok=0
            status=125
        fi
    fi

    if [ "$status" -eq 0 ]; then
        for attempt in $(seq 1 500); do
            if [ -s "$registration" ]; then
                IFS='|' read -r candidate child_start < "$registration" \
                    || { candidate=''; child_start=''; }
                break
            fi
            kill -0 "$pane_pid" 2>/dev/null || break
            sleep 0.01
        done
        pgid=$(hsqa_process_group_of "$pane_pid")
        controller_pgid=$(hsqa_process_group_of "$$")
        current_start=$(hsqa_process_starttime "$pane_pid")
        if [ "$candidate" != "$pane_pid" ] \
                || ! hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid" \
                || ! [[ "$child_start" =~ ^[0-9]+$ ]] \
                || [ "$current_start" != "$child_start" ] \
                || [ "$pgid" != "$pane_pid" ] \
                || [ "$pgid" = "$controller_pgid" ] \
                || ! hsqa_pid_has_exact_identity "$pane_pid" "$identity" \
                || ! hsqa_tmux set-option -w -t "$window_id" @hsqa_identity \
                    "$identity" 2>/dev/null \
                || ! hsqa_tmux_session_owned "$session_id" "$owner"; then
            status=2
        elif register_pid "$role" "-$pgid" "$identity" "$child_start"; then
            record="$HSQA_REGISTERED_RECORD"
            printf -v "$record_var" '%s' "$record"
            if [ "$HSQA_LAUNCH_PENDING" -ne 0 ]; then
                status=2
            else
                release_token="$pane_pid|$child_start|$$|$parent_start"
                release_tmp="$release.tmp.$$.$RANDOM"
                if hsqa_tmux_window_topology "$window_id" "$pane_id" "$pane_pid" \
                        && printf '%s\n' "$release_token" > "$release_tmp" \
                        && [ "$HSQA_LAUNCH_PENDING" -eq 0 ] \
                        && mv -f -- "$release_tmp" "$release"; then
                    for attempt in $(seq 1 500); do
                        if [ -f "$ack" ] && [ ! -L "$ack" ]; then
                            IFS= read -r ack_value < "$ack" || ack_value=''
                            if [ "$ack_value" = "$release_token" ]; then
                                released=1
                                break
                            fi
                        fi
                        kill -0 "$pane_pid" 2>/dev/null || break
                        sleep 0.01
                    done
                    [ "$released" -eq 1 ] || status=125
                else
                    rm -f -- "$release_tmp"
                    status=125
                fi
            fi
        else
            status=2
        fi
    fi

    rm -f -- "$release" "$ack"
    [ -z "$release_tmp" ] || rm -f -- "$release_tmp"
    if [ "$status" -ne 0 ]; then
        provisional_record="-${pane_pid:-0}|${pane_pid:-0}|${child_start:-0}|$identity"
        if [ -n "$record" ]; then
            if hsqa_stop_tracked "$role" "$record"; then
                record=''
                printf -v "$record_var" '%s' ''
            else
                cleanup_ok=0
                status=125
            fi
        elif [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] \
                && [[ "$child_start" =~ ^[0-9]+$ ]]; then
            hsqa_terminate_owned_record "$provisional_record" allow-exact-child \
                || { cleanup_ok=0; status=125; }
        elif [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]]; then
            hsqa_terminate_provisional_group "$pane_pid" "$identity" \
                || { cleanup_ok=0; status=125; }
        fi
        if [ "$cleanup_ok" -eq 0 ] && [ -z "$record" ] \
                && [[ "$pane_pid" =~ ^[1-9][0-9]*$ ]] \
                && hsqa_record_target_present "$provisional_record"; then
            provisional_record="-$pane_pid|$pane_pid|${child_start:-0}|$identity"
            if append_pid_record launch-recovery "$provisional_record"; then
                record="$HSQA_REGISTERED_RECORD"
                printf -v "$record_var" '%s' "$record"
                printf -v "$effective_role_var" '%s' launch-recovery
            else
                if hsqa_publish_unique_recovery_record tmux-recovery \
                        "$provisional_record"; then
                    record="$provisional_record"
                    printf -v "$record_var" '%s' "$record"
                    printf -v "$effective_role_var" '%s' "$HSQA_REGISTERED_ROLE"
                else
                    echo "FATAL: uncertain tmux cleanup could not persist recovery authority" >&2
                fi
            fi
        fi
        if [ "$created" -eq 1 ] && [ "$topology_ok" -eq 1 ] \
                && ! hsqa_record_target_present "$provisional_record"; then
            hsqa_kill_exact_tmux_pane "$pane_id" "$pane_pid" \
                "${child_start:-$pane_start}" "$identity" \
                || { cleanup_ok=0; status=125; }
        elif [ "$created" -eq 1 ] && [ "$topology_ok" -eq 1 ] \
                && [ -z "$record" ]; then
            cleanup_ok=0
            status=125
        fi
        rm -f -- "$registration"
        hsqa_finish_registration_window "$signal_handler" || finish_status=$?
        [ "$cleanup_ok" -eq 1 ] || return 125
        [ "$finish_status" -eq 0 ] || return "$finish_status"
        return "$status"
    fi
    rm -f -- "$registration"
    hsqa_finish_registration_window "$signal_handler"
}
