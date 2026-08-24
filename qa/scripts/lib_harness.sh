#!/usr/bin/env bash
# Shared helpers sourced by every launching QA script: preflight port checks
# (AC-13), pidfile registration for reap.sh (AC-7), and the one evidence
# layout (AC-11): qa/reports/artifacts/<scenario-id>/<TS>/{manifest.json,
# result.json,reproduction.md,logs/,shots/,film/}.
#
# Source, don't execute: `. "$(dirname "$0")/lib_harness.sh"`

HSQA_REPO="${HSQA_REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
HSQA_ARTIFACTS="$HSQA_REPO/qa/reports/artifacts"
HSQA_PIDDIR="${HSQA_PIDDIR:-/tmp/claude-0/hsqa-pids}"
mkdir -p "$HSQA_ARTIFACTS" "$HSQA_PIDDIR"

# D-H3: a repo-root symlink so the contract's `artifacts/qa/...` path
# resolves literally, without forking the canonical store.
if [ ! -e "$HSQA_REPO/artifacts" ]; then
    mkdir -p "$HSQA_REPO/artifacts"
    ln -sfn "../qa/reports/artifacts" "$HSQA_REPO/artifacts/qa"
fi

# ---- fingerprint / dirty-hash (AC-9, finding 9) ----------------------------
# Identical computation to tools/hearthstead-qa's own fingerprint()/dirty_hash()
# so a scenario manifest's fingerprint can be compared directly against the
# controller's latest.json to prove which source state actually produced a
# given piece of evidence — this is what makes "shots prove it ran a later
# scenario than its own manifest claims" detectable instead of invisible.
hsqa_fingerprint() {
    local mod="$HSQA_REPO/hearthstead-neoforge" qa="$HSQA_REPO/qa"
    { find "$mod/src" "$mod/tools" -type f 2>/dev/null | sort | xargs sha256sum 2>/dev/null
      sha256sum "$mod/build.gradle" "$mod/gradle.properties" "$mod/settings.gradle" \
                "$qa/PROTOCOL.md" 2>/dev/null
    } | sha256sum | cut -d' ' -f1
}
hsqa_dirty_hash() {
    git -C "$HSQA_REPO" status --porcelain 2>/dev/null | sha256sum | cut -d' ' -f1
}

# ---- evidence scaffold (AC-11) --------------------------------------------
ev_init() { # <scenario-id> -> sets EV_DIR/EV_LOGS/EV_SHOTS/EV_FILM/EV_TS
    local scenario="$1"
    EV_TS="$(date -u +%Y%m%dT%H%M%SZ)"
    EV_DIR="$HSQA_ARTIFACTS/$scenario/$EV_TS"
    EV_LOGS="$EV_DIR/logs"
    EV_SHOTS="$EV_DIR/shots"
    EV_FILM="$EV_DIR/film"
    mkdir -p "$EV_LOGS" "$EV_SHOTS" "$EV_FILM"
    local fp dh
    fp="$(hsqa_fingerprint)"
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
json.dump(manifest, open(os.path.join(ev_dir, "manifest.json"), "w"), indent=2)
PYEOF
    export EV_DIR EV_LOGS EV_SHOTS EV_FILM EV_TS
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
    if command -v lsof >/dev/null; then
        pid=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null | head -1)
        [ -z "$pid" ] && return 0
        printf '%s %s\n' "$pid" "$(ps -o args= -p "$pid" 2>/dev/null)"
        return 0
    fi
    # Fallback: try to bind it ourselves. Not a substitute for lsof (can't
    # name the holder), but still correctly detects held vs free.
    if ! python3 -c "
import socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    s.bind(('0.0.0.0', $port)); sys.exit(0)
except OSError:
    sys.exit(1)
" 2>/dev/null; then
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
    for _ in 1 2 3 4 5; do
        holder=$(port_holder "$port")
        [ -z "$holder" ] && return 0
        sleep 1
    done
    echo "FAIL: port $port needed for '$role' is already in use — held by pid $holder"
    return 1
}

# ---- pidfile registration for reap.sh (AC-7) -------------------------------
register_pid() { # <role> <pid>
    echo "$2" >> "$HSQA_PIDDIR/$1.pids"
}
clear_pidfile() { # <role>
    rm -f "$HSQA_PIDDIR/$1.pids"
}

# ---- structured per-directive checks (AC-14) and result.json (AC-11) ------
# Each check is appended as one JSONL line, independent of bash quoting
# concerns — no need to reconstruct a bash-side data structure. A directive
# that silently did nothing simply never appends a line, which is itself
# visible when result.json is inspected (AC-14).
check_pass() { # <name> <evidence>
    python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"status":"PASS","evidence":sys.argv[2]}))' \
        "$1" "$2" >> "$EV_DIR/.checks.jsonl"
    echo "PASS: $1 -- $2"
}
check_fail() { # <name> <evidence>  (records only; caller decides whether to exit)
    python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"status":"FAIL","evidence":sys.argv[2]}))' \
        "$1" "$2" >> "$EV_DIR/.checks.jsonl"
    echo "FAIL: $1 -- $2"
}

# finish_result <status> — aggregates .checks.jsonl into result.json.
finish_result() {
    local status="$1"
    python3 - "$status" "$EV_DIR" <<'PYEOF'
import json, os, sys, time
status, ev_dir = sys.argv[1], sys.argv[2]
checks = {}
jsonl = os.path.join(ev_dir, ".checks.jsonl")
if os.path.exists(jsonl):
    for line in open(jsonl):
        line = line.strip()
        if not line:
            continue
        c = json.loads(line)
        checks[c["name"]] = {"status": c["status"], "evidence": c["evidence"]}
result = {
    "overall": status,
    "finished": time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()),
    "checks": checks,
}
json.dump(result, open(os.path.join(ev_dir, "result.json"), "w"), indent=2)
PYEOF
}

write_reproduction() { # <text>
    printf '%s\n' "$1" > "$EV_DIR/reproduction.md"
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
