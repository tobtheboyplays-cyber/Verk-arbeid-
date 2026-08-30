#!/usr/bin/env bash
# Shared helpers sourced by every launching QA script: preflight port checks
# (AC-13), pidfile registration for reap.sh (AC-7), and the one evidence
# layout (AC-11): qa/reports/artifacts/<scenario-id>/<TS>/{manifest.json,
# result.json,reproduction.md,logs/,shots/,film/}.
#
# Source, don't execute: `. "$(dirname "$0")/lib_harness.sh"`

HSQA_REPO="${HSQA_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
HSQA_TRACKED_ENTRY="$HSQA_REPO/qa/scripts/tracked_exec_entry.sh"
HSQA_ARTIFACTS="${HSQA_ARTIFACTS:-$HSQA_REPO/qa/reports/artifacts}"
. "$HSQA_REPO/qa/scripts/lib_safe_paths.sh"
HSQA_PIDDIR=$(hsqa_safe_target \
    "${HSQA_PIDDIR:-/tmp/claude-0/hsqa-pids-v2}" "pid root") || exit 2
export HSQA_PIDDIR
if [ "${HSQA_LIB_READ_ONLY:-0}" != 1 ]; then
    hsqa_claim_empty_directory \
        "$HSQA_PIDDIR" .hsqa-pid-root-owned hsqa-pid-root-v1 || exit 2
    mkdir -p "$HSQA_ARTIFACTS"

    # D-H3: a repo-root symlink so the contract's `artifacts/qa/...` path
    # resolves literally, without forking the canonical store.
    if [ ! -e "$HSQA_REPO/artifacts" ]; then
        mkdir -p "$HSQA_REPO/artifacts"
        ln -sfn "../qa/reports/artifacts" "$HSQA_REPO/artifacts/qa"
    fi
fi

# ---- fingerprint / dirty-hash (AC-9, finding 9) ----------------------------
# Identical computation to tools/hearthstead-qa's own fingerprint()/dirty_hash()
# so a scenario manifest's fingerprint can be compared directly against the
# controller's latest.json to prove which source state actually produced a
# given piece of evidence — this is what makes "shots prove it ran a later
# scenario than its own manifest claims" detectable instead of invisible.
hsqa_fingerprint() {
    # MUST stay byte-for-byte equivalent to the controller's fingerprint() —
    # see the reasoning for each included and excluded path there. If the two
    # drift, a manifest's fingerprint stops being comparable to latest.json's,
    # which is the entire point of recording it (finding 9).
    local helper="$HSQA_REPO/qa/scripts/source_fingerprint.sh" value
    [ -f "$helper" ] || return 1
    value="$(bash "$helper" "$HSQA_REPO")" || return 1
    [[ "$value" =~ ^[0-9a-f]{64}$ ]] || return 1
    printf '%s\n' "$value"
}
hsqa_dirty_hash() {
    git -C "$HSQA_REPO" status --porcelain 2>/dev/null | sha256sum | cut -d' ' -f1
}

# ---- evidence scaffold (AC-11) --------------------------------------------
ev_write_manifest() { # <scenario-id>; atomically (re)write current EV_DIR manifest
    local scenario="$1"
    local fp dh
    if ! fp="$(hsqa_fingerprint)"; then
        return 1
    fi
    dh="$(hsqa_dirty_hash)"
    python3 - "$scenario" "$EV_DIR" "$fp" "$dh" <<'PYEOF'
import json, os, subprocess, sys, time
scenario, ev_dir, fp, dh = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
def sh(cmd):
    try: return subprocess.check_output(cmd, text=True).strip()
    except Exception: return "unknown"
manifest = {
    "scenario": scenario,
    "started": time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()),
    "git_commit": sh(["git", "-C", os.environ.get("HSQA_REPO", "."), "rev-parse", "HEAD"]),
    "fingerprint": fp,
    "dirty_hash": dh,
}
path = os.path.join(ev_dir, "manifest.json")
temporary = path + ".tmp-" + str(os.getpid())
with open(temporary, "w") as handle:
    json.dump(manifest, handle, indent=2)
os.replace(temporary, path)
PYEOF
}

ev_init() { # <scenario-id> -> sets EV_DIR/EV_LOGS/EV_SHOTS/EV_FILM/EV_TS
    local scenario="$1" scenario_root stamp
    [[ "$scenario" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        || { echo "FATAL: unsafe evidence scenario id '$scenario'" >&2; exit 2; }
    stamp="$(date -u +%Y%m%dT%H%M%S.%NZ)"
    scenario_root="$HSQA_ARTIFACTS/$scenario"
    mkdir -p "$scenario_root" || exit 2
    EV_DIR=$(mktemp -d "$scenario_root/${stamp}-$$.XXXXXX") || exit 2
    EV_TS="$(basename "$EV_DIR")"
    EV_LOGS="$EV_DIR/logs"
    EV_SHOTS="$EV_DIR/shots"
    EV_FILM="$EV_DIR/film"
    mkdir -p "$EV_LOGS" "$EV_SHOTS" "$EV_FILM"
    export EV_DIR EV_LOGS EV_SHOTS EV_FILM EV_TS
    if ! ev_write_manifest "$scenario"; then
        # perf_probe deliberately defers INT/TERM around initialization so it
        # can finish a coherent FAIL artifact in its early-signal handler.
        # Every other caller retains the historical immediate fail-closed path.
        if [ "${HSQA_DEFERRED_INIT_SIGNAL:-0}" -ne 0 ]; then
            return 1
        fi
        echo "FATAL: scenario source fingerprint failed or was not 64 lowercase hex" >&2
        exit 2
    fi
}

# ---- port preflight (AC-13, AC-8/N1) ---------------------------------------
# `ss -ltnp` is UNRELIABLE in this sandboxed environment — proven empirically
# (a real leaked listener on a test port was invisible to `ss -tan` in every
# mode, including state time-wait, while `lsof -i` found it immediately and
# a direct bind() confirmed the port was genuinely held). Use lsof, with a
# raw-bind-attempt fallback if lsof itself is ever unavailable, so preflight
# and reap.sh never again report a leaked/held port as free.
port_holder() { # <port> -> "PID CMD..." or empty
    local port="$1" pid
    hsqa_validate_port "$port" \
        || { echo "FAIL: invalid QA port '$port'" >&2; return 2; }
    if command -v lsof >/dev/null; then
        pid=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null | head -1)
        [ -z "$pid" ] && return 0
        printf '%s %s\n' "$pid" "$(ps -o args= -p "$pid" 2>/dev/null)"
        return 0
    fi
    # Fallback: try to bind it ourselves. Not a substitute for lsof (can't
    # name the holder), but still correctly detects held vs free.
    if ! python3 - "$port" <<'PYEOF' 2>/dev/null
import socket, sys
s_port = sys.argv[1]
if not s_port.isascii() or not s_port.isdecimal():
    sys.exit(2)
port = int(s_port)
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    s.bind(('0.0.0.0', port)); sys.exit(0)
except OSError:
    sys.exit(1)
PYEOF
    then
        printf '?  (lsof unavailable; direct bind failed, holder unidentified)\n'
    fi
}

# preflight_port <port> <role> — echoes a FAIL line and returns 1 if held,
# naming the holder's PID/cmdline; says nothing about the mod. Silent+0 if
# free. Retries briefly first: a port a previous run's server just released
# can sit in a kernel-level release delay for a second or two after the
# process is killed — genuine transient, not a real conflict — so this
# waits up to 5s for it to clear before reporting a holder.
preflight_port() {
    local port="$1" role="$2" holder
    hsqa_validate_port "$port" \
        || { echo "FAIL: invalid port '$port' needed for '$role'"; return 1; }
    for _ in 1 2 3 4 5; do
        holder=$(port_holder "$port")
        [ -z "$holder" ] && return 0
        sleep 1
    done
    echo "FAIL: port $port needed for '$role' is already in use — held by pid $holder"
    return 1
}

# ---- pidfile registration for reap.sh (AC-7) -------------------------------
hsqa_process_starttime() { # <bare pid>
    /usr/bin/sed -E 's/^[0-9]+ \([^)]*\) //' "/proc/$1/stat" 2>/dev/null \
        | /usr/bin/awk '{print $20}'
}
hsqa_pidfile_selftest_contract() { # <role>; authenticated pre-commit race seam
    local role="$1" mode="${HSQA_PIDFILE_SELFTEST_MODE:-}"
    local ready="${HSQA_PIDFILE_SELFTEST_READY:-}" hold="${HSQA_PIDFILE_SELFTEST_HOLD:-}"
    local root lexical canonical path
    if [ -z "$mode$ready$hold" ]; then
        return 0
    fi
    [ "$mode" = 1 ] \
        && [[ "$role" =~ ^pid-(race|candidate-race|dispose-race|empty-race|empty-lease)-[0-9a-f]{16}$ ]] \
        && [ -n "$ready" ] && [ -n "$hold" ] || return 2
    root=$(realpath -m -- "$HSQA_PIDDIR/..") || return 2
    case "$root" in /tmp/hsqa-harness-artifacts.*) ;; *) return 2;; esac
    for path in "$ready" "$hold"; do
        lexical=$(realpath -ms -- "$path") || return 2
        canonical=$(realpath -m -- "$path") || return 2
        [ "$lexical" = "$canonical" ] || return 2
        case "$canonical" in "$root"/*) ;; *) return 2;; esac
    done
}

hsqa_pidfile_transaction() { # <append|remove> <role> <immutable-record>
    local action="$1" role="$2" record="$3"
    hsqa_pidfile_selftest_contract "$role" || return 2
    /usr/bin/python3 -I - "$HSQA_PIDDIR" "$action" "$role" "$record" <<'PYEOF'
import ctypes
import errno
import fcntl
import os
import re
import secrets
import signal
import stat
import sys
import time

root, action, role, record = sys.argv[1:]
role_re = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
record_text_re = re.compile(r"^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$")
record_bytes_re = re.compile(rb"^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$")
if action not in ("append", "remove") or not role_re.fullmatch(role) or not record_text_re.fullmatch(record):
    raise SystemExit(2)
record_bytes = record.encode("ascii")

uid = os.getuid()
nofollow = getattr(os, "O_NOFOLLOW", 0)
cloexec = getattr(os, "O_CLOEXEC", 0)
root_fd = lock_fd = record_fd = candidate_fd = None
temporary = None
candidate_identity = None
lease_break_requested = False
max_bytes = 131072
max_records = 512

def lease_break_handler(_signum, _frame):
    global lease_break_requested
    lease_break_requested = True

signal.signal(signal.SIGIO, lease_break_handler)

def same_inode(left, right):
    return left.st_dev == right.st_dev and left.st_ino == right.st_ino

def lstat_at(name):
    return os.stat(name, dir_fd=root_fd, follow_symlinks=False)

def validate_regular_fd(fd, name):
    actual = os.fstat(fd)
    if not stat.S_ISREG(actual.st_mode) or actual.st_uid != uid or actual.st_nlink != 1:
        raise RuntimeError("unsafe inode")
    named = lstat_at(name)
    if not stat.S_ISREG(named.st_mode) or named.st_uid != uid or named.st_nlink != 1:
        raise RuntimeError("unsafe path")
    if not same_inode(actual, named):
        raise RuntimeError("path replaced")
    return actual

def open_existing(name, flags=os.O_RDONLY):
    try:
        fd = os.open(name, flags | cloexec | nofollow, dir_fd=root_fd)
    except FileNotFoundError:
        return None, None
    return fd, validate_regular_fd(fd, name)

def read_bounded(fd):
    os.lseek(fd, 0, os.SEEK_SET)
    chunks = []
    total = 0
    while True:
        chunk = os.read(fd, min(65536, max_bytes + 1 - total))
        if not chunk:
            break
        chunks.append(chunk)
        total += len(chunk)
        if total > max_bytes:
            raise RuntimeError("pidfile exceeds byte bound")
    return b"".join(chunks)

def parse_records(raw):
    if not raw:
        return []
    if not raw.endswith(b"\n"):
        raise RuntimeError("pidfile lacks terminal LF")
    lines = raw[:-1].split(b"\n")
    if (not lines or len(lines) > max_records or any(not line for line in lines)
            or any(record_bytes_re.fullmatch(line) is None for line in lines)
            or len(set(lines)) != len(lines)):
        raise RuntimeError("malformed pidfile roster")
    return lines

def read_records(fd):
    return parse_records(read_bounded(fd))

def write_all(fd, payload):
    view = memoryview(payload)
    while view:
        written = os.write(fd, view)
        if written <= 0:
            raise RuntimeError("short write")
        view = view[written:]
    os.fsync(fd)

def acquire_lease(fd, kind):
    if (not hasattr(fcntl, "F_SETLEASE") or not hasattr(fcntl, "F_GETLEASE")
            or not hasattr(fcntl, "F_SETOWN")):
        raise RuntimeError("kernel file leases unavailable")
    fcntl.fcntl(fd, fcntl.F_SETOWN, os.getpid())
    fcntl.fcntl(fd, fcntl.F_SETLEASE, kind)
    if fcntl.fcntl(fd, fcntl.F_GETLEASE) != kind:
        raise RuntimeError("kernel file lease was not acquired")

def lease_intact(fd, kind):
    return (not lease_break_requested
            and fcntl.fcntl(fd, fcntl.F_GETLEASE) == kind)

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

def link_tmpfile(fd, name):
    libc = ctypes.CDLL(None, use_errno=True)
    function = getattr(libc, "linkat", None)
    if function is None:
        raise RuntimeError("linkat unavailable")
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int,
                         ctypes.c_char_p, ctypes.c_int]
    function.restype = ctypes.c_int
    if function(fd, b"", root_fd, os.fsencode(name), 0x1000) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))

def selftest_barrier(phase):
    if os.environ.get("HSQA_PIDFILE_SELFTEST_MODE") != "1":
        return
    expected_prefix = {
        "pre-commit": "pid-race-",
        "candidate-linked": "pid-candidate-race-",
        "dispose-old": "pid-dispose-race-",
    }.get(phase)
    if phase == "empty-remove":
        matches = (role.startswith("pid-empty-race-")
                   or role.startswith("pid-empty-lease-"))
    else:
        matches = expected_prefix is not None and role.startswith(expected_prefix)
    if not matches:
        return
    ready = os.environ["HSQA_PIDFILE_SELFTEST_READY"]
    hold = os.environ["HSQA_PIDFILE_SELFTEST_HOLD"]
    ready_fd = os.open(ready, os.O_WRONLY | os.O_CREAT | os.O_EXCL | cloexec | nofollow, 0o600)
    ready_payload = ((temporary + "\n").encode("ascii")
                     if phase in ("candidate-linked", "dispose-old")
                     else b"ready\n")
    if os.write(ready_fd, ready_payload) != len(ready_payload):
        os.close(ready_fd)
        raise RuntimeError("short pidfile selftest barrier write")
    os.close(ready_fd)
    for _ in range(500):
        if not os.path.lexists(hold):
            break
        time.sleep(0.01)
    else:
        raise RuntimeError("selftest barrier timeout")
    if phase == "empty-remove" and role.startswith("pid-empty-lease-"):
        for _ in range(500):
            if (lease_break_requested
                    or fcntl.fcntl(candidate_fd, fcntl.F_GETLEASE) != fcntl.F_WRLCK):
                return
            time.sleep(0.01)
        raise RuntimeError("candidate lease-break selftest timed out")

def create_candidate(name, payload):
    global temporary, candidate_identity, candidate_fd
    if not hasattr(os, "O_TMPFILE"):
        raise RuntimeError("O_TMPFILE unavailable")
    candidate_fd = os.open(
        ".", os.O_RDWR | os.O_TMPFILE | cloexec, 0o000, dir_fd=root_fd,
    )
    try:
        anonymous = os.fstat(candidate_fd)
        if (not stat.S_ISREG(anonymous.st_mode) or anonymous.st_uid != uid
                or anonymous.st_nlink != 0 or stat.S_IMODE(anonymous.st_mode) != 0):
            raise RuntimeError("unsafe anonymous candidate")
        # The candidate has no public name and no writable byte until the
        # kernel write lease is held.  fchmod/write happen only afterwards.
        acquire_lease(candidate_fd, fcntl.F_WRLCK)
        os.fchmod(candidate_fd, 0o600)
        write_all(candidate_fd, payload)
        candidate_identity = os.fstat(candidate_fd)
        if not stat.S_ISREG(candidate_identity.st_mode) or candidate_identity.st_uid != uid \
                or candidate_identity.st_nlink != 0 \
                or stat.S_IMODE(candidate_identity.st_mode) != 0o600 \
                or candidate_identity.st_size != len(payload) \
                or not lease_intact(candidate_fd, fcntl.F_WRLCK) \
                or read_bounded(candidate_fd) != payload \
                or parse_records(payload) != updated:
            raise RuntimeError("unsafe candidate")
        temporary = f".{role}.pids.tmp-{os.getpid()}-{secrets.token_hex(8)}"
        link_tmpfile(candidate_fd, temporary)
        linked = validate_regular_fd(candidate_fd, temporary)
        if (not same_inode(linked, candidate_identity)
                or stat.S_IMODE(linked.st_mode) != 0o600
                or linked.st_size != len(payload)):
            raise RuntimeError("linked candidate metadata mismatch")
        selftest_barrier("candidate-linked")
        linked = validate_regular_fd(candidate_fd, temporary)
        if (not same_inode(linked, candidate_identity)
                or stat.S_IMODE(linked.st_mode) != 0o600
                or linked.st_size != len(payload)
                or not lease_intact(candidate_fd, fcntl.F_WRLCK)
                or read_bounded(candidate_fd) != payload
                or parse_records(payload) != updated):
            raise RuntimeError("candidate changed before commit")
    except Exception:
        os.close(candidate_fd)
        candidate_fd = None
        raise
    return temporary

def cleanup_candidate():
    if temporary is None or candidate_identity is None or candidate_fd is None:
        return
    try:
        current = validate_regular_fd(candidate_fd, temporary)
    except (FileNotFoundError, RuntimeError):
        return
    if (not same_inode(current, candidate_identity)
            or stat.S_IMODE(current.st_mode) != 0o600
            or not lease_intact(candidate_fd, fcntl.F_WRLCK)):
        return
    # Best-effort proof-or-retain cleanup: only the still-open, leased
    # candidate may be removed. A substituted or unprovable name is deliberate
    # red evidence and is left untouched.
    os.unlink(temporary, dir_fd=root_fd)
    os.fsync(root_fd)

try:
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | cloexec | nofollow)
    root_stat = os.fstat(root_fd)
    if not stat.S_ISDIR(root_stat.st_mode) or root_stat.st_uid != uid:
        raise RuntimeError("unsafe pid root")
    lock_name = f"{role}.pids.lock"
    file_name = f"{role}.pids"
    lock_fd = os.open(lock_name, os.O_RDWR | os.O_CREAT | cloexec | nofollow,
                      0o600, dir_fd=root_fd)
    validate_regular_fd(lock_fd, lock_name)
    fcntl.flock(lock_fd, fcntl.LOCK_EX)
    validate_regular_fd(lock_fd, lock_name)

    record_fd, original_stat = open_existing(file_name)
    if record_fd is not None:
        if stat.S_IMODE(original_stat.st_mode) != 0o600:
            raise RuntimeError("unsafe pidfile mode")
        # A read lease proves there was no pre-open legacy writer and blocks
        # every new write-open while the immutable original is read/exchanged.
        # The candidate carries its own write lease below, so publication of a
        # new public inode cannot open a second writer window.
        acquire_lease(record_fd, fcntl.F_RDLCK)
    lines = [] if record_fd is None else read_records(record_fd)
    if action == "append":
        if record_bytes in lines:
            raise SystemExit(0)
        updated = lines + [record_bytes]
    else:
        if record_fd is None or record_bytes not in lines:
            raise SystemExit(0)
        updated = [line for line in lines if line != record_bytes]

    if len(updated) > max_records or len(set(updated)) != len(updated) \
            or any(record_bytes_re.fullmatch(line) is None for line in updated):
        raise RuntimeError("updated pidfile roster exceeds record contract")
    payload = b"\n".join(updated) + (b"\n" if updated else b"")
    if len(payload) > max_bytes:
        raise RuntimeError("updated pidfile roster exceeds byte bound")
    create_candidate(file_name, payload)
    if original_stat is not None:
        validate_regular_fd(record_fd, file_name)
    else:
        try:
            lstat_at(file_name)
        except FileNotFoundError:
            pass
        else:
            raise RuntimeError("pidfile appeared")
    selftest_barrier("pre-commit")
    validate_regular_fd(lock_fd, lock_name)
    if record_fd is not None and not lease_intact(record_fd, fcntl.F_RDLCK):
        raise RuntimeError("original pidfile write lease break requested")
    if not lease_intact(candidate_fd, fcntl.F_WRLCK):
        raise RuntimeError("candidate pidfile lease break requested")

    if original_stat is None:
        if not updated:
            cleanup_candidate()
        else:
            # Atomic first publication; never link then unlink a predictable
            # name, because a lock-ignoring replacement could occupy that name
            # between the two operations.
            rename_noreplace(temporary, file_name)
            temporary = None
    else:
        rename_exchange(temporary, file_name)
        swapped_old = lstat_at(temporary)
        swapped_new = lstat_at(file_name)
        if (not same_inode(swapped_old, original_stat)
                or not stat.S_ISREG(swapped_old.st_mode)
                or not same_inode(swapped_new, candidate_identity)
                or not stat.S_ISREG(swapped_new.st_mode)):
            rename_exchange(temporary, file_name)
            raise RuntimeError("pidfile replaced during commit")
        if (not lease_intact(record_fd, fcntl.F_RDLCK)
                or not lease_intact(candidate_fd, fcntl.F_WRLCK)):
            # Do not dispose either inode when a writer has requested a break.
            # The public candidate and private original remain durable red
            # authority until the failed transaction releases its leases.
            os.fsync(root_fd)
            raise RuntimeError("pidfile lease broke during exchange")
        selftest_barrier("dispose-old")
        private_old = validate_regular_fd(record_fd, temporary)
        if (not same_inode(private_old, original_stat)
                or not same_inode(private_old, swapped_old)
                or stat.S_IMODE(private_old.st_mode) != 0o600
                or private_old.st_size != swapped_old.st_size
                or private_old.st_mtime_ns != swapped_old.st_mtime_ns
                or private_old.st_ctime_ns != swapped_old.st_ctime_ns):
            raise RuntimeError("private original changed before disposal")
        if (not lease_intact(record_fd, fcntl.F_RDLCK)
                or not lease_intact(candidate_fd, fcntl.F_WRLCK)):
            os.fsync(root_fd)
            raise RuntimeError("pidfile lease broke before disposal")
        os.unlink(temporary, dir_fd=root_fd)
        temporary = None
        if not updated:
            # Keep the exact empty candidate at the public role path. There is
            # no atomic "lease still intact + unlink this inode" primitive: a
            # writer may already be blocked in open(2) when the last check is
            # made, then receive an FD to an unlinked inode after lease release.
            # A permanent, validated zero-byte sentinel keeps that late write
            # reachable and therefore red instead of losing recovery authority.
            selftest_barrier("empty-remove")
            if not lease_intact(candidate_fd, fcntl.F_WRLCK):
                os.fsync(root_fd)
                raise RuntimeError("empty candidate write lease break requested")

    # Reopening the public inode would ask the kernel to break our own
    # candidate write lease. Verify through the already-open leased FD and
    # independently prove that the public name still resolves to it. This also
    # proves the retained zero-byte sentinel after final-record removal.
    validate_regular_fd(candidate_fd, file_name)
    if not lease_intact(candidate_fd, fcntl.F_WRLCK) \
            or read_records(candidate_fd) != updated:
        raise RuntimeError("pidfile verification failed")
    os.fsync(root_fd)
except SystemExit:
    raise
except Exception as exc:
    print(f"FATAL: secure pidfile transaction failed: {exc}", file=sys.stderr)
    raise SystemExit(2)
finally:
    try:
        cleanup_candidate()
    except Exception:
        pass
    for fd in (candidate_fd, record_fd, lock_fd, root_fd):
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
PYEOF
}

append_pid_record() { # <role> <already-validated immutable record>
    local role="$1" record="$2"
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        && [[ "$record" =~ ^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        || { echo "FATAL: invalid pid append contract" >&2; return 2; }
    hsqa_pidfile_transaction append "$role" "$record" || return 2
    HSQA_REGISTERED_RECORD="$record"
}

register_pid() { # <role> <pid-or-negative-pgid> <identity-tag> [immutable-expected-starttime]
    local role="$1" target="$2" identity="$3" expected_start="${4:-}"
    local leader starttime record
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        && [[ "$target" =~ ^-?[1-9][0-9]*$ ]] \
        && [[ "$identity" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        || { echo "FATAL: invalid pid registration contract" >&2; return 2; }
    leader="${target#-}"
    starttime=$(hsqa_process_starttime "$leader")
    [[ "$starttime" =~ ^[0-9]+$ ]] \
        || { echo "FATAL: cannot register missing process $leader" >&2; return 2; }
    if [ -n "$expected_start" ]; then
        [[ "$expected_start" =~ ^[0-9]+$ ]] \
            && [ "$starttime" = "$expected_start" ] \
            || { echo "FATAL: process identity changed before pid registration" >&2; return 2; }
        starttime="$expected_start"
    fi
    record="$target|$leader|$starttime|$identity"
    append_pid_record "$role" "$record"
}

hsqa_recovery_token() {
    /usr/bin/od -An -N12 -tx1 /dev/urandom 2>/dev/null \
        | /usr/bin/tr -d ' \n'
}

hsqa_publish_unique_recovery_record() { # <role-prefix> <immutable-record>
    local prefix="$1" record="$2" token role file current_uid
    [[ "$prefix" =~ ^[a-z0-9][a-z0-9_-]{0,31}$ ]] \
        && [[ "$record" =~ ^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        || return 2
    token=$(hsqa_recovery_token) || return 2
    [[ "$token" =~ ^[0-9a-f]{24}$ ]] || return 2
    role="$prefix-$token"
    file="$HSQA_PIDDIR/$role.pids"
    # noclobber maps to O_CREAT|O_EXCL for a redirection.  A pre-existing
    # regular file or symlink is therefore never adopted or overwritten.
    if ! (umask 077; set -o noclobber; builtin printf '%s\n' "$record" > "$file") \
            2>/dev/null; then
        return 2
    fi
    current_uid=$(/usr/bin/id -u) || return 2
    [ -f "$file" ] && [ ! -L "$file" ] \
        && [ "$(/usr/bin/stat -c %u -- "$file" 2>/dev/null)" = "$current_uid" ] \
        && [ "$(/usr/bin/stat -c %h -- "$file" 2>/dev/null)" = 1 ] \
        && /usr/bin/cmp -s -- "$file" <(builtin printf '%s\n' "$record") \
        || return 2
    HSQA_REGISTERED_RECORD="$record"
    HSQA_REGISTERED_ROLE="$role"
}
unregister_pid() { # <role> <exact record returned in HSQA_REGISTERED_RECORD>
    local role="$1" record="$2"
    [[ "$role" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        && [[ "$record" =~ ^-?[1-9][0-9]*\|[1-9][0-9]*\|[0-9]+\|[a-z0-9][a-z0-9_-]{0,63}$ ]] \
        || { echo "FATAL: invalid pid unregistration contract" >&2; return 2; }
    hsqa_pidfile_transaction remove "$role" "$record"
}
clear_pidfile() { # <role>
    echo "FATAL: role-wide pidfile deletion is forbidden; unregister exact records" >&2
    return 2
}

# ---- exact tracked launch / teardown --------------------------------------
# Shared by every full-suite launcher.  A background callback starts in its
# own process group, writes BASHPID before doing work, and is durably registered
# before a deferred INT/TERM can invoke the caller's signal handler.
hsqa_process_group_of() { # <pid>
    ps -o pgid= -p "$1" 2>/dev/null | tr -d '[:space:]'
}

HSQA_LAUNCH_PENDING=0
HSQA_LAUNCH_SIGNAL_HANDLER=''
HSQA_LAUNCH_EFFECTIVE_ROLE=''
HSQA_LAUNCH_DIAGNOSTIC=''
HSQA_STOP_DIAGNOSTIC=''
hsqa_defer_launch_signal() { # <130|143>
    [ "$HSQA_LAUNCH_PENDING" -ne 0 ] || HSQA_LAUNCH_PENDING="$1"
}

hsqa_install_signal_handler() { # <function name or empty>
    local handler="$1"
    if [ -n "$handler" ]; then
        [[ "$handler" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || return 2
        trap "$handler 130" INT
        trap "$handler 143" TERM
    else
        trap - INT TERM
    fi
}

hsqa_begin_registration_window() { # <signal-handler>
    HSQA_LAUNCH_SIGNAL_HANDLER="$1"
    HSQA_LAUNCH_PENDING=0
    trap 'hsqa_defer_launch_signal 130' INT
    trap 'hsqa_defer_launch_signal 143' TERM
}

hsqa_finish_registration_window() { # <signal-handler>
    local signal_handler="$1" pending="$HSQA_LAUNCH_PENDING"
    [ "$signal_handler" = "$HSQA_LAUNCH_SIGNAL_HANDLER" ] || return 2
    hsqa_install_signal_handler "$signal_handler" || return 2
    HSQA_LAUNCH_SIGNAL_HANDLER=''
    if [ "$pending" -ne 0 ]; then
        [ -z "$signal_handler" ] || "$signal_handler" "$pending"
        return "$pending"
    fi
}

# Construct the only environment permitted to cross the privileged tracked
# entry exec boundary.  `bash -p` suppresses startup hooks, but only `env -i`
# also removes exported BASH_FUNC_* values and accidental build/test knobs from
# the entry's raw process environment.  Workload-specific values outside this
# allowlist must be supplied in the explicit command vector.
hsqa_build_tracked_entry_environment() { # <array-var> <identity>
    local output_var="$1" identity="$2" allowed_name allowed_value safe_home
    local canonical_path=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    local -n output_ref="$output_var"
    [[ "$identity" =~ ^[a-z0-9][a-z0-9_-]{0,63}$ ]] || return 2
    safe_home="${HOME:-/tmp}"
    case "$safe_home" in /*) ;; *) safe_home=/tmp;; esac
    case "$safe_home" in *[$'\r\n']*) safe_home=/tmp;; esac
    output_ref=(
        /usr/bin/env -i
        "PATH=$canonical_path"
        "HOME=$safe_home"
        LANG=C.UTF-8
        LC_ALL=C
        TMPDIR=/tmp
        "HSQA_PROCESS_IDENTITY=$identity"
    )
    for allowed_name in JAVA_HOME GRADLE_USER_HOME DISPLAY XAUTHORITY XDG_RUNTIME_DIR TERM \
            HSQA_ACTIVE HSQA_REPO HSQA_REPORTS HSQA_ARTIFACTS HSQA_SAFE_TMP_ROOT \
            HSQA_PIDDIR HSQA_INST_ROOT HSQA_INSTALL_DIR HSQA_CLIENT_INSTALL_DIR \
            HSQA_GRADLE_CACHE HSQA_LEVEL_TYPE HSQA_TMUX_SOCKET HSQA_PROTO \
            HSQA_REAP_FIXTURE_ROOT HSQA_REAP_FIXTURE_TOKEN \
            HSQA_PERF_FIXTURE_MODE HSQA_PERF_FIXTURE_ROOT HSQA_PERF_FIXTURE_TOKEN; do
        if [[ -v "$allowed_name" ]]; then
            allowed_value="${!allowed_name}"
            case "$allowed_value" in *[$'\r\n']*) return 2;; esac
            output_ref+=("$allowed_name=$allowed_value")
        fi
    done
}

hsqa_provisional_group_owned() { # <pgid> <identity>; all live members must carry exact marker
    local pgid="$1" identity="$2" members member found=0
    members=$(hsqa_group_members "$pgid")
    [ -z "$members" ] && return 0
    while IFS= read -r member; do
        [ -n "$member" ] || continue
        hsqa_pid_has_exact_identity "$member" "$identity" || return 1
        found=1
    done <<< "$members"
    [ "$found" -eq 1 ]
}

hsqa_terminate_provisional_group() { # <pgid> <identity>; launch-window-only authority
    local pgid="$1" identity="$2" attempt
    [[ "$pgid" =~ ^[1-9][0-9]*$ ]] || return 1
    [ -n "$(hsqa_group_members "$pgid")" ] || return 0
    hsqa_provisional_group_owned "$pgid" "$identity" || return 1
    kill -TERM -- "-$pgid" 2>/dev/null || true
    for attempt in $(seq 1 30); do
        [ -n "$(hsqa_group_members "$pgid")" ] || return 0
        sleep 0.1
    done
    hsqa_provisional_group_owned "$pgid" "$identity" || return 1
    kill -9 -- "-$pgid" 2>/dev/null || true
    for attempt in $(seq 1 50); do
        [ -n "$(hsqa_group_members "$pgid")" ] || return 0
        sleep 0.1
    done
    return 1
}

hsqa_launch_selftest_contract() { # <role>; reject ambient seams outside exact disposable fixture
    local role="$1" ready="${HSQA_LAUNCH_SELFTEST_READY:-}" hold="${HSQA_LAUNCH_SELFTEST_HOLD:-}"
    local mode="${HSQA_LAUNCH_SELFTEST_MODE:-}" phase="${HSQA_LAUNCH_SELFTEST_PHASE:-}"
    local root lexical canonical path
    if [ -z "$mode$phase$ready$hold" ]; then
        return 0
    fi
    [ "$mode" = 1 ] && [ -n "$ready" ] && [ -n "$hold" ] || return 2
    [[ "$phase:$role" =~ ^pre-register:writefail-(int|term)-[0-9a-f]{16}$ \
        || "$phase:$role" =~ ^pre-release:cancel-(int|term)-[0-9a-f]{16}$ ]] \
        || return 2
    root=$(realpath -m -- "$HSQA_PIDDIR/..") || return 2
    case "$root" in /tmp/hsqa-harness-artifacts.*) ;; *) return 2;; esac
    for path in "$ready" "$hold"; do
        lexical=$(realpath -ms -- "$path") || return 2
        canonical=$(realpath -m -- "$path") || return 2
        [ "$lexical" = "$canonical" ] || return 2
        case "$canonical" in "$root"/*) ;; *) return 2;; esac
    done
}

hsqa_launch_selftest_barrier() { # <pre-register|pre-release>
    local requested="$1" attempt
    [ "${HSQA_LAUNCH_SELFTEST_MODE:-}" = 1 ] \
        && [ "${HSQA_LAUNCH_SELFTEST_PHASE:-}" = "$requested" ] || return 0
    printf 'ready\n' > "$HSQA_LAUNCH_SELFTEST_READY" || return 2
    for attempt in $(seq 1 500); do
        [ "$HSQA_LAUNCH_PENDING" -eq 0 ] || return 0
        [ -e "$HSQA_LAUNCH_SELFTEST_HOLD" ] || return 0
        sleep 0.01
    done
    return 2
}

hsqa_launch_tracked_group() { # role identity signal-handler pid-var pgid-var record-var effective-role-var command [args...]
    local role="$1" identity="$2" signal_handler="$3"
    local pid_var="$4" pgid_var="$5" record_var="$6" effective_role_var="$7"
    shift 7
    local registration release ack release_tmp='' provisional candidate child_start
    local current_start pgid controller_pgid record attempt parent_start release_token
    local ack_value emergency emergency_tmp cleanup_ok=1 released=0
    local monitor_was_enabled=0 status=0 provisional_start=''
    local diagnostic_phase=initial
    local barrier_rc=unset pending_before_barrier=unset pending_after_barrier=unset
    local PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    local -a entry_environment=()
    [ "$#" -gt 0 ] && [ -x "$HSQA_TRACKED_ENTRY" ] || return 2
    hsqa_launch_selftest_contract "$role" || return 2
    hsqa_build_tracked_entry_environment entry_environment "$identity" || return 2
    HSQA_LAUNCH_EFFECTIVE_ROLE="$role"
    HSQA_LAUNCH_DIAGNOSTIC=''
    printf -v "$effective_role_var" '%s' "$role"
    case $- in *m*) monitor_was_enabled=1;; esac
    registration=$(mktemp "$HSQA_PIDDIR/.launch.XXXXXX") || return 2
    release="$registration.release"
    ack="$registration.ack"
    parent_start=$(hsqa_process_starttime "$$")
    if ! [[ "$parent_start" =~ ^[0-9]+$ ]]; then
        rm -f -- "$registration"
        return 2
    fi
    HSQA_LAUNCH_PENDING=0
    trap 'hsqa_defer_launch_signal 130' INT
    trap 'hsqa_defer_launch_signal 143' TERM
    set -m
    # An actual exec boundary is mandatory: Linux exposes the environment
    # inherited at exec in /proc/PID/environ, not later Bash exports.  `env`
    # and the entry script both exec in-place, so the job leader/PID/PGID stay
    # immutable while the marker becomes authoritative for every descendant.
    "${entry_environment[@]}" \
        /bin/bash --noprofile --norc -p "$HSQA_TRACKED_ENTRY" \
            "$registration" "$release" "$ack" \
            "$$" "$parent_start" -- "$@" &
    provisional=$!
    provisional_start=$(hsqa_process_starttime "$provisional")
    printf -v "$pid_var" '%s' "$provisional"
    printf -v "$pgid_var" '%s' "$provisional"
    candidate=''
    for attempt in $(seq 1 200); do
        if [ -s "$registration" ]; then
            IFS='|' read -r candidate child_start < "$registration" \
                || { candidate=''; child_start=''; }
            break
        fi
        kill -0 "$provisional" 2>/dev/null || break
        sleep 0.01
    done
    if ! [[ "$candidate" =~ ^[1-9][0-9]*$ ]] \
            || ! [[ "$child_start" =~ ^[0-9]+$ ]] \
            || [ "$candidate" != "$provisional" ]; then
        status=2
    else
        provisional_start="$child_start"
        pgid=$(hsqa_process_group_of "$candidate")
        controller_pgid=$(hsqa_process_group_of $$)
        current_start=$(hsqa_process_starttime "$candidate")
        if [ "$pgid" != "$candidate" ] || [ "$pgid" = "$controller_pgid" ] \
                || [ "$current_start" != "$child_start" ] \
                || ! hsqa_pid_has_exact_identity "$candidate" "$identity"; then
            status=2
        else
            if ! hsqa_launch_selftest_barrier pre-register; then
                diagnostic_phase=pre-register-barrier-failed
                status=2
            elif register_pid "$role" "-$pgid" "$identity" "$child_start"; then
                diagnostic_phase=durably-registered
                record="$HSQA_REGISTERED_RECORD"
                printf -v "$pid_var" '%s' "$candidate"
                printf -v "$pgid_var" '%s' "$pgid"
                printf -v "$record_var" '%s' "$record"
                # Only durable registration releases the entry into the actual
                # command.  The ack proves the entry observed this exact token
                # before the control files are removed.
                pending_before_barrier="$HSQA_LAUNCH_PENDING"
                hsqa_launch_selftest_barrier pre-release
                barrier_rc=$?
                pending_after_barrier="$HSQA_LAUNCH_PENDING"
                if [ "$barrier_rc" -ne 0 ]; then
                    diagnostic_phase=pre-release-barrier-failed
                    status=125
                elif [ "$HSQA_LAUNCH_PENDING" -ne 0 ]; then
                    # Cancellation won the race before payload release. The
                    # durable record authorizes exact entry cleanup, but the
                    # command itself must never execute.
                    diagnostic_phase=pending-before-release
                    status=2
                else
                    diagnostic_phase=publishing-release
                    release_token="$candidate|$child_start|$$|$parent_start"
                    release_tmp="$release.tmp.$$.$RANDOM"
                    if printf '%s\n' "$release_token" > "$release_tmp" \
                            && mv -f -- "$release_tmp" "$release"; then
                        for attempt in $(seq 1 500); do
                            if [ -f "$ack" ] && [ ! -L "$ack" ]; then
                                IFS= read -r ack_value < "$ack" || ack_value=''
                                if [ "$ack_value" = "$release_token" ]; then
                                    released=1
                                    break
                                fi
                            fi
                            kill -0 "$candidate" 2>/dev/null || break
                            sleep 0.01
                        done
                        if [ "$released" -ne 1 ]; then
                            diagnostic_phase=release-ack-failed
                            status=125
                        fi
                    else
                        rm -f -- "$release_tmp"
                        diagnostic_phase=release-publish-failed
                        status=125
                    fi
                fi
            else
                status=2
            fi
        fi
    fi
    rm -f -- "$release" "$ack"
    [ -z "$release_tmp" ] || rm -f -- "$release_tmp"
    [ "$monitor_was_enabled" -eq 1 ] || set +m
    if [ "$status" -ne 0 ]; then
        local provisional_record="-$provisional|$provisional|${provisional_start:-0}|$identity"
        if [ -n "${record:-}" ]; then
            # Release/ack failure happened after durable registration. Exact
            # cleanup may unregister only after whole-group absence; otherwise
            # the original recovery record remains authoritative.
            if hsqa_stop_tracked "$role" "$record"; then
                diagnostic_phase="$diagnostic_phase:cleanup-proven"
                record=''
                printf -v "$record_var" '%s' ''
            else
                diagnostic_phase="$diagnostic_phase:cleanup-uncertain"
                cleanup_ok=0
                status=125
            fi
        elif [[ "$provisional_start" =~ ^[0-9]+$ ]]; then
            hsqa_terminate_owned_record "$provisional_record" allow-exact-child \
                || { diagnostic_phase="$diagnostic_phase:provisional-cleanup-uncertain"; cleanup_ok=0; status=125; }
        else
            # A child that never published a starttime cannot become durable
            # recovery authority.  While signals are still deferred, require
            # every member of this newly-created job group to carry the exact
            # inherited identity, terminate it boundedly, and prove the whole
            # group absent.  Uncertainty dominates any pending INT/TERM.
            hsqa_terminate_provisional_group "$provisional" "$identity" \
                || { diagnostic_phase="$diagnostic_phase:unregistered-cleanup-uncertain"; cleanup_ok=0; status=125; }
        fi
        if [ "$cleanup_ok" -eq 0 ] && [ -z "${record:-}" ] \
                && hsqa_record_target_present "$provisional_record"; then
            # Registration failed and cleanup is uncertain.  Preserve durable
            # recovery authority under a fixed fallback role.  The record's
            # immutable starttime may be 0 only when it was unreadable; reaper
            # will then REFUSE a destructive action but still keep the gate red.
            if append_pid_record launch-recovery "$provisional_record"; then
                record="$HSQA_REGISTERED_RECORD"
                HSQA_LAUNCH_EFFECTIVE_ROLE=launch-recovery
                printf -v "$effective_role_var" '%s' launch-recovery
                printf -v "$record_var" '%s' "$record"
            else
                # Last-resort atomic unique record: unlike an in-memory PID,
                # this survives controller exit/SIGKILL and is discovered by
                # reap.sh.  Never overwrite another launch's authority.
                if hsqa_publish_unique_recovery_record launch-recovery \
                        "$provisional_record"; then
                    record="$provisional_record"
                    HSQA_LAUNCH_EFFECTIVE_ROLE="$HSQA_REGISTERED_ROLE"
                    printf -v "$effective_role_var" '%s' "$HSQA_LAUNCH_EFFECTIVE_ROLE"
                    printf -v "$record_var" '%s' "$record"
                else
                    echo "FATAL: uncertain launch cleanup could not persist recovery authority" >&2
                fi
            fi
        fi
        if ! hsqa_record_target_present "$provisional_record"; then
            wait "$provisional" 2>/dev/null || true
        elif [ -z "${record:-}" ]; then
            status=125
        fi
        rm -f -- "$registration"
        hsqa_install_signal_handler "$signal_handler" || status=125
        if [ "${HSQA_LAUNCH_SELFTEST_MODE:-0}" = 1 ]; then
            local diagnostic_record_state=empty
            [ -z "${record:-}" ] || diagnostic_record_state=set
            HSQA_LAUNCH_DIAGNOSTIC="auth_phase=${HSQA_LAUNCH_SELFTEST_PHASE:-production} phase=$diagnostic_phase barrier_rc=$barrier_rc pending_before=$pending_before_barrier pending_after=$pending_after_barrier pending_final=$HSQA_LAUNCH_PENDING status=$status cleanup_ok=$cleanup_ok released=$released ack=${ack_value:-unset} record=$diagnostic_record_state effective_role=${HSQA_LAUNCH_EFFECTIVE_ROLE:-unset} provisional=$provisional pgid=${pgid:-unset} stop={$HSQA_STOP_DIAGNOSTIC}"
        fi
        if [ "$status" -eq 125 ]; then
            return 125
        fi
        if [ "$HSQA_LAUNCH_PENDING" -ne 0 ]; then
            [ -z "$signal_handler" ] || "$signal_handler" "$HSQA_LAUNCH_PENDING"
            return "$HSQA_LAUNCH_PENDING"
        fi
        return "$status"
    fi
    rm -f -- "$registration"
    hsqa_install_signal_handler "$signal_handler" || return 125
    if [ "${HSQA_LAUNCH_SELFTEST_MODE:-0}" = 1 ]; then
        HSQA_LAUNCH_DIAGNOSTIC="auth_phase=${HSQA_LAUNCH_SELFTEST_PHASE:-production} phase=released barrier_rc=$barrier_rc pending_before=$pending_before_barrier pending_after=$pending_after_barrier pending_final=$HSQA_LAUNCH_PENDING status=0 cleanup_ok=1 released=$released ack=${ack_value:-unset} record=set effective_role=${HSQA_LAUNCH_EFFECTIVE_ROLE:-unset} provisional=$provisional pgid=${pgid:-unset} stop={$HSQA_STOP_DIAGNOSTIC}"
    fi
    if [ "$HSQA_LAUNCH_PENDING" -ne 0 ]; then
        if [ -n "$signal_handler" ]; then
            "$signal_handler" "$HSQA_LAUNCH_PENDING"
        fi
        return "$HSQA_LAUNCH_PENDING"
    fi
    return 0
}

hsqa_record_target_present() { # <target|leader|starttime|identity>
    local record="$1" target leader recorded_start identity extra
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    [ -z "${extra:-}" ] || return 0
    if [[ "$target" = -* ]]; then
        # A dead group leader can remain as a zombie until tmux/Bash reaps it.
        # `kill -0 -PGID` still reports that zombie-only group as present even
        # though it cannot execute work or own descendants.  Treat the target
        # as present only while at least one non-zombie member remains; live
        # descendants keep the recovery record authoritative after the leader
        # exits.
        [ -n "$(hsqa_group_members "$leader")" ]
    else
        [ "$(hsqa_process_starttime "$leader")" = "$recorded_start" ]
    fi
}

hsqa_group_members() { # <positive pgid>; prints non-zombie members only
    local pgid="$1" stat line pid state group
    for stat in /proc/[0-9]*/stat; do
        [ -r "$stat" ] || continue
        pid="${stat#/proc/}"; pid="${pid%/stat}"
        line=$(cat "$stat" 2>/dev/null) || continue
        line="${line##*) }"
        state=$(printf '%s\n' "$line" | awk '{print $1}')
        group=$(printf '%s\n' "$line" | awk '{print $3}')
        [ "$group" = "$pgid" ] && [ "$state" != Z ] && printf '%s\n' "$pid"
    done
}

hsqa_pid_has_exact_identity() { # <pid> <identity>
    [ -r "/proc/$1/environ" ] \
        && tr '\0' '\n' < "/proc/$1/environ" 2>/dev/null \
            | grep -Fxq -- "HSQA_PROCESS_IDENTITY=$2"
}

hsqa_record_owned() { # <record> [allow-exact-child]
    local record="$1" allow_child="${2:-}" target leader recorded_start identity extra
    local current_start members member cmd identity_found=0
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    [ -z "${extra:-}" ] || return 1
    if [[ "$target" = -* ]]; then
        members=$(hsqa_group_members "$leader")
        [ -n "$members" ] || return 1
        current_start=$(hsqa_process_starttime "$leader")
        if [ -e "/proc/$leader" ]; then
            [[ "$current_start" =~ ^[0-9]+$ ]] \
                && [ "$current_start" = "$recorded_start" ] \
                || return 1
        fi
        while IFS= read -r member; do
            [ -n "$member" ] || continue
            cmd=$(tr '\0' ' ' < "/proc/$member/cmdline" 2>/dev/null || true)
            # Every descendant of a tracked launch inherits the exact marker.
            # A single marked leader must never authorize killing an unrelated
            # process that later joined the same numeric PGID.  This also
            # permits owned single-use GradleDaemon children while refusing
            # unmarked/shared daemons and every other unmarked member.
            hsqa_pid_has_exact_identity "$member" "$identity" || return 1
            identity_found=1
        done <<< "$members"
        [ "$identity_found" -eq 1 ]
        return
    fi
    current_start=$(hsqa_process_starttime "$leader")
    [ "$current_start" = "$recorded_start" ] \
        && hsqa_pid_has_exact_identity "$leader" "$identity"
}

hsqa_terminate_owned_record() { # <record> [allow-exact-child]
    local record="$1" allow_child="${2:-}" target leader recorded_start identity extra attempt
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    hsqa_record_target_present "$record" || return 0
    hsqa_record_owned "$record" "$allow_child" || {
        echo "FATAL: refusing teardown of recycled/unowned target; retaining $record" >&2
        return 1
    }
    kill -TERM -- "$target" 2>/dev/null || true
    for attempt in $(seq 1 30); do
        hsqa_record_target_present "$record" || return 0
        sleep 0.1
    done
    hsqa_record_owned "$record" "$allow_child" || {
        echo "FATAL: target identity changed before SIGKILL; retaining $record" >&2
        return 1
    }
    kill -9 -- "$target" 2>/dev/null || true
    for attempt in $(seq 1 50); do
        hsqa_record_target_present "$record" || return 0
        sleep 0.1
    done
    echo "FATAL: exact owned target survived SIGKILL; retaining $record" >&2
    return 1
}

hsqa_stop_tracked() { # <role> <exact record>; unregisters only after absence
    local role="$1" record="$2" target leader recorded_start identity extra attempt
    local wait_status=unset present_after=unset
    [ -n "$record" ] || return 0
    IFS='|' read -r target leader recorded_start identity extra <<< "$record"
    [ -z "${extra:-}" ] \
        && [ "${target#-}" = "$leader" ] \
        || return 1
    hsqa_terminate_owned_record "$record" || return 1
    if wait "$leader" 2>/dev/null; then
        wait_status=0
    else
        wait_status=$?
    fi
    if hsqa_record_target_present "$record"; then
        present_after=1
        [ "${HSQA_LAUNCH_SELFTEST_MODE:-0}" != 1 ] \
            || HSQA_STOP_DIAGNOSTIC="wait_status=$wait_status present_after=$present_after"
        echo "FATAL: exact tracked target survived teardown; retaining $record" >&2
        return 1
    fi
    present_after=0
    [ "${HSQA_LAUNCH_SELFTEST_MODE:-0}" != 1 ] \
        || HSQA_STOP_DIAGNOSTIC="wait_status=$wait_status present_after=$present_after"
    unregister_pid "$role" "$record"
}

hsqa_register_existing_pid() { # <role> <pid> <identity> <record-var>
    local role="$1" pid="$2" identity="$3" record_var="$4"
    register_pid "$role" "$pid" "$identity" || return 2
    printf -v "$record_var" '%s' "$HSQA_REGISTERED_RECORD"
}

hsqa_wait_port_free() { # <port>; bounded post-teardown proof
    local port="$1" attempt
    for attempt in $(seq 1 50); do
        [ -z "$(port_holder "$port")" ] && return 0
        sleep 0.1
    done
    echo "FATAL: port $port remained held after teardown" >&2
    return 1
}

# ---- structured per-directive checks (AC-14) and result.json (AC-11) ------
# Each check is appended as one JSONL line, independent of bash quoting
# concerns — no need to reconstruct a bash-side data structure. A directive
# that silently did nothing simply never appends a line, which is itself
# visible when result.json is inspected (AC-14).
check_pass() { # <name> <evidence>
    if ! python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"status":"PASS","evidence":sys.argv[2]}))' \
            "$1" "$2" >> "$EV_DIR/.checks.jsonl"; then
        echo "FATAL: could not append PASS evidence" >&2
        exit 2
    fi
    echo "PASS: $1 -- $2"
}
check_fail() { # <name> <evidence>  (records only; caller decides whether to exit)
    if ! python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"status":"FAIL","evidence":sys.argv[2]}))' \
            "$1" "$2" >> "$EV_DIR/.checks.jsonl"; then
        echo "FATAL: could not append FAIL evidence" >&2
        exit 2
    fi
    echo "FAIL: $1 -- $2"
}

# finish_result <status> — aggregates .checks.jsonl into result.json.
finish_result() {
    local status="$1"
    if ! python3 - "$status" "$EV_DIR" <<'PYEOF'
import json, os, sys, time
status, ev_dir = sys.argv[1], sys.argv[2]
checks = {}
saw_failure = False
jsonl = os.path.join(ev_dir, ".checks.jsonl")
if os.path.exists(jsonl):
    for line in open(jsonl):
        line = line.strip()
        if not line:
            continue
        c = json.loads(line)
        if c["status"] == "FAIL":
            saw_failure = True
        checks[c["name"]] = {"status": c["status"], "evidence": c["evidence"]}
# No requested status can erase a historical FAIL, including a later PASS
# reusing the same check name. AUTO is PASS only when every observed line was
# non-failing.
if saw_failure:
    status = "FAIL"
elif status == "AUTO":
    status = "PASS"
result = {
    "overall": status,
    "finished": time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()),
    "checks": checks,
}
path = os.path.join(ev_dir, "result.json")
temporary = path + ".tmp-" + str(os.getpid())
with open(temporary, "w") as handle:
    json.dump(result, handle, indent=2)
os.replace(temporary, path)
PYEOF
    then
        echo "FATAL: could not produce atomic result.json" >&2
        exit 2
    fi
}

write_reproduction() { # <text>
    local temporary="$EV_DIR/.reproduction.tmp.$$.$RANDOM"
    if ! python3 - "$temporary" "$EV_DIR/reproduction.md" "$1" <<'PYEOF'
import os, sys
temporary, destination, content = sys.argv[1:]
with open(temporary, "w") as handle:
    handle.write(content)
    handle.write("\n")
os.replace(temporary, destination)
PYEOF
    then
        rm -f -- "$temporary" 2>/dev/null || true
        echo "FATAL: could not produce atomic reproduction.md" >&2
        exit 2
    fi
}

hsqa_finish_interrupted() { # <130|143> <suite> <controller-command> <cleanup-status>
    local status="$1" suite="$2" command="$3" cleanup_status="$4" signal_name
    case "$status" in
        130) signal_name=INT ;;
        143) signal_name=TERM ;;
        *) return 2 ;;
    esac
    check_fail interrupted \
        "$suite controller received $signal_name (exit $status)"
    if [ "$cleanup_status" -ne 0 ]; then
        check_fail process_cleanup \
            "$suite cleanup could not prove exact process absence; recovery records retained"
    fi
    finish_result FAIL
    write_reproduction "# Reproduce: interrupted $suite
$command
Verdict: FAIL — controller received $signal_name (exit $status).
Cleanup status: $cleanup_status (125 means cleanup could not prove absence).
Artifact: $EV_DIR
"
    [ "$cleanup_status" -eq 0 ] && return "$status"
    return 125
}

hsqa_exit_after_cleanup() { # <original-status> <suite> <controller-command> <cleanup-function> [pending-signal-var]
    local status="$1" suite="$2" command="$3" cleanup_function="$4"
    local pending_var="${5:-}" pending=0 cleanup_status=0 final_status
    trap - EXIT INT TERM
    [[ "$cleanup_function" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || exit 125
    [ -z "$pending_var" ] \
        || [[ "$pending_var" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || exit 125
    "$cleanup_function" || cleanup_status=1
    if [ -n "$pending_var" ]; then
        pending="${!pending_var:-0}"
        case "$pending" in
            0) ;;
            130|143)
                hsqa_finish_interrupted "$pending" "$suite" "$command" \
                    "$cleanup_status"
                final_status=$?
                exit "$final_status"
                ;;
            *) exit 125 ;;
        esac
    fi
    if [ "$cleanup_status" -ne 0 ]; then
        check_fail process_cleanup \
            "$suite exit could not prove exact process absence; recovery records retained"
        finish_result FAIL
        [ -f "$EV_DIR/reproduction.md" ] || write_reproduction "# Reproduce: $suite cleanup failure
$command
Verdict: FAIL — exact process cleanup could not be proven.
Artifact: $EV_DIR
"
        exit 125
    fi
    exit "$status"
}

# die <check-name> <message> — records the check as FAIL, finishes result.json
# as FAIL, prints "FAIL: <message>" (the ordered-fact-ladder line callers grep
# for) and exits 1. Use for the first-wrong-fact in an ordered fact ladder.
die() {
    check_fail "$1" "$2"
    finish_result FAIL
    # AC-11: every scenario dir has all five elements unconditionally, pass
    # or fail — a caller-supplied write_reproduction never runs on a die()
    # exit, so write a fallback here rather than leave it missing.
    [ -f "$EV_DIR/reproduction.md" ] || write_reproduction "# Reproduce: $1 failed
tools/hearthstead-qa <suite>
First-cause check: $1
Evidence: $2
See logs/ and result.json in this directory for the full record.
"
    echo "FAIL: $2"
    exit 1
}
