#!/usr/bin/env bash
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

PIDDIR="${HSQA_PIDDIR:-/tmp/claude-0/hsqa-pids}"
# The four isolated instance ports (D-H2): dedicated, performance, playtest, live.
PORTS="25571 25572 25573 25574"
# Restricted fallback pattern — anything broader risks catching the Gradle
# daemon or an unrelated process. GradleDaemon is excluded unconditionally,
# regardless of what else a line matches.
# Extended beyond the plan's original literal pattern (hsqa-inst|hsqa-server|
# Xvfb :9[5-9]|tmux.*hsqa-live) to also match playtest.sh's own tmux session
# (hsqa-playtest) — added when playtest.sh moved its server console from a
# FIFO to tmux for the same reliability reasons as live.sh (D-H1).
PATTERN='hsqa-inst|hsqa-server|Xvfb :9[5-9]|tmux.*hsqa-(live|playtest)'
EXCLUDE='GradleDaemon'

mkdir -p "$PIDDIR"

matching_procs() { # prints "PID CMD..." lines that match PATTERN and not EXCLUDE
    # Bracket one letter of each alternative so the grep invocation's own
    # argv (which literally contains this pattern text) never self-matches —
    # the classic pgrep self-exclusion trick. Real process lines don't have
    # brackets, so [h]sqa-inst still matches a literal "hsqa-inst" in them.
    local self_safe='[h]sqa-inst|[h]sqa-server|[X]vfb :9[5-9]|tmux.*[h]sqa-(live|playtest)'
    ps -eo pid=,args= 2>/dev/null | grep -E "$self_safe" | grep -v -E "$EXCLUDE"
}

port_holder() { # <port> -> "PID CMD" if held, empty if free
    # `ss -ltnp` proved unreliable in this environment (see lib_harness.sh) —
    # lsof is the trustworthy source here.
    local port="$1" pid
    pid=$(lsof -ti tcp:"$port" -sTCP:LISTEN 2>/dev/null | head -1)
    [ -z "$pid" ] && return 0
    printf '%s %s\n' "$pid" "$(ps -o args= -p "$pid" 2>/dev/null)"
}

cmd_check() {
    local dirty=0
    local procs
    procs=$(matching_procs)
    if [ -n "$procs" ]; then
        echo "LEAKED PROCESSES:"
        echo "$procs" | sed 's/^/  /'
        dirty=1
    else
        echo "no leaked harness processes"
    fi
    for p in $PORTS; do
        local h; h=$(port_holder "$p")
        if [ -n "$h" ]; then
            echo "PORT $p HELD by: $h"
            dirty=1
        else
            echo "port $p free"
        fi
    done
    return $dirty
}

cmd_dryrun() {
    echo "-- pidfiles in $PIDDIR --"
    if ls "$PIDDIR"/*.pids >/dev/null 2>&1; then
        for f in "$PIDDIR"/*.pids; do
            echo "$f:"
            sed 's/^/  would kill pid /' "$f" 2>/dev/null
        done
    else
        echo "  (none)"
    fi
    echo "-- pattern fallback would additionally match --"
    local procs; procs=$(matching_procs)
    [ -n "$procs" ] && echo "$procs" | sed 's/^/  /' || echo "  (none)"
    echo "-- would then verify: --"
    cmd_check || true
    return 0   # dry-run is informational only; never fails on current state
}

cmd_reap() {
    echo "reap: pidfiles first"
    if ls "$PIDDIR"/*.pids >/dev/null 2>&1; then
        for f in "$PIDDIR"/*.pids; do
            while read -r pid; do
                [ -z "$pid" ] && continue
                kill -9 "$pid" 2>/dev/null && echo "  killed pid $pid (from $f)"
            done < "$f"
            rm -f "$f"
        done
    fi
    echo "reap: pattern fallback"
    local procs; procs=$(matching_procs)
    if [ -n "$procs" ]; then
        echo "$procs" | while read -r pid rest; do
            [ -z "$pid" ] && continue
            kill -9 "$pid" 2>/dev/null && echo "  killed pid $pid ($rest)"
        done
    fi
    # tmux's own session kill is the proven-reliable cascade to a pane's
    # descendants (a plain kill -9 of the pane's shell PID alone does not
    # reliably reach a java process several forks deep in the pipeline) —
    # explicit per session name, not just the pattern-matched PIDs above.
    for s in hsqa-live hsqa-playtest; do
        tmux has-session -t "$s" 2>/dev/null && tmux kill-session -t "$s" 2>/dev/null \
            && echo "  killed tmux session $s"
    done
    sleep 1
    echo "reap: verifying"
    cmd_check
}

cmd_selftest() {
    # Feed synthetic ps-style lines through the same grep pipeline the real
    # matcher uses, without touching any real process.
    local fixture
    fixture=$(cat <<'EOF'
12345 /usr/bin/java -cp hsqa-server/... net.minecraft.server.Main nogui
12346 Xvfb :98 -screen 0 1280x720x24
12347 /usr/bin/tmux new-session -s hsqa-live
12349 /usr/bin/tmux new-session -s hsqa-playtest
12348 /usr/local/bin/python3 /home/user/Verk-arbeid-/qa/scripts/analyze_trace.py
22375 java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3
22999 /usr/bin/java -jar /tmp/claude-0/hsqa-inst/dedicated/... server
EOF
)
    local matched
    matched=$(printf '%s\n' "$fixture" | grep -E "$PATTERN" | grep -v -E "$EXCLUDE")
    local ok=1
    echo "$matched" | grep -q '^12345' || { echo "FAIL: should match hsqa-server line 12345"; ok=0; }
    echo "$matched" | grep -q '^12346' || { echo "FAIL: should match Xvfb :98 line 12346"; ok=0; }
    echo "$matched" | grep -q '^12347' || { echo "FAIL: should match tmux hsqa-live line 12347"; ok=0; }
    echo "$matched" | grep -q '^12349' || { echo "FAIL: should match tmux hsqa-playtest line 12349"; ok=0; }
    echo "$matched" | grep -q '^22999' || { echo "FAIL: should match hsqa-inst line 22999"; ok=0; }
    echo "$matched" | grep -q '^22375' && { echo "FAIL: must NEVER match the GradleDaemon line 22375"; ok=0; }
    echo "$matched" | grep -q '^12348' && { echo "FAIL: should not match an unrelated python process"; ok=0; }
    if [ "$ok" = 1 ]; then echo "reap selftest: PASS (GradleDaemon excluded, harness processes matched)"; return 0
    else echo "reap selftest: FAIL"; return 1; fi
}

case "${1:-check}" in
    dry-run|dryrun) cmd_dryrun;;
    check)          cmd_check;;
    selftest)       cmd_selftest;;
    ""|reap)        cmd_reap;;
    *) echo "usage: reap.sh [dry-run|check|selftest|reap]"; exit 1;;
esac
