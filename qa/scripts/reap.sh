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
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$HERE/lib_harness.sh"   # finding 5: durable evidence for check/dry-run/reap

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
SELF_SAFE_PATTERN='[h]sqa-inst/|[h]sqa-server|[X]vfb :9[5-9]|tmux.*[h]sqa-(live|playtest)'
EXCLUDE='GradleDaemon'

mkdir -p "$PIDDIR"

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

matching_procs() { # prints "PID CMD..." lines that match SELF_SAFE_PATTERN and not EXCLUDE
    # Finding 10: `selftest` used to grep its OWN re-typed copy of this
    # pattern, which meant it never actually exercised this function or its
    # self_safe bracket-escaping — a change here could silently diverge from
    # what selftest claims is covered. Source of process lines is
    # injectable via HSQA_REAP_TEST_INPUT so selftest can feed a fixture
    # through this EXACT function instead of a re-typed stand-in.
    local source_lines mine
    mine=" $(ancestry_pids) "
    if [ -n "${HSQA_REAP_TEST_INPUT:-}" ]; then
        source_lines="$(printf '%s\n' "$HSQA_REAP_TEST_INPUT")"
    else
        source_lines="$(ps -eo pid=,args= 2>/dev/null)"
    fi
    printf '%s\n' "$source_lines" \
        | grep -E "$SELF_SAFE_PATTERN" \
        | grep -v -E "$EXCLUDE" \
        | while read -r pid rest; do
              case "$mine" in *" $pid "*) continue;; esac
              printf '%s %s\n' "$pid" "$rest"
          done
}

# The pidfile-stage decision, as a function so it can actually be TESTED.
# Finding 11 put this logic inline in cmd_reap's kill loop and finding 7 of
# the re-review pointed out that selftest then graded a re-typed copy of it —
# the same defect finding 10 was raised for. One definition, two callers.
pid_cmdline() { # <bare-pid>
    if [ -n "${HSQA_REAP_TEST_CMDLINES:-}" ]; then
        printf '%s\n' "$HSQA_REAP_TEST_CMDLINES" \
            | awk -v p="$1" '$1 == p { $1 = ""; sub(/^ /, ""); print; exit }'
    else
        ps -o args= -p "$1" 2>/dev/null
    fi
}

pid_disposition() { # <pid as stored, may carry a leading - for a group kill>
    local bare="${1#-}" cmd
    cmd=$(pid_cmdline "$bare")
    if [ -z "$cmd" ]; then echo gone
    elif printf '%s' "$cmd" | grep -qE "$EXCLUDE"; then echo refuse
    else echo kill; fi
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
                # Finding 11: pidfiles can outlive a SIGKILLed suite, and a
                # PID is only unique while its process is alive — an OS can
                # and does recycle a numeric PID for a completely unrelated
                # later process (this is the exact risk the plan called
                # out). Validate before killing: strip the process-group
                # minus sign (register_pid stores "-$PID" for group kills)
                # to look the PID up, refuse to kill anything that is now a
                # GradleDaemon, and just note (not kill) a PID that is
                # already gone.
                local cmd; cmd=$(pid_cmdline "${pid#-}")
                case "$(pid_disposition "$pid")" in
                    gone)   echo "  skip pid $pid (from $f): already gone";;
                    refuse) echo "  REFUSING to kill pid $pid (from $f): now belongs to a GradleDaemon (recycled PID) — cmd: $cmd";;
                    kill)   kill -9 "$pid" 2>/dev/null && echo "  killed pid $pid (from $f): $cmd";;
                esac
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
    # Finding 10: feed synthetic ps-style lines through matching_procs()
    # ITSELF (via HSQA_REAP_TEST_INPUT) — the exact function cmd_check,
    # cmd_dryrun and cmd_reap all call — not a locally re-typed stand-in
    # pattern that could silently diverge from the real matcher.
    local fixture
    fixture=$(cat <<'EOF'
12345 /usr/bin/java -cp hsqa-server/... net.minecraft.server.Main nogui
12346 Xvfb :98 -screen 0 1280x720x24
12347 /usr/bin/tmux new-session -s hsqa-live
12349 /usr/bin/tmux new-session -s hsqa-playtest
12348 /usr/local/bin/python3 /home/user/Verk-arbeid-/qa/scripts/analyze_trace.py
22375 java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3
22999 /usr/bin/java -jar /tmp/claude-0/hsqa-inst/dedicated/... server
23001 /bin/bash -c echo hsqa-install backup moved aside
EOF
)
    local matched
    matched=$(HSQA_REAP_TEST_INPUT="$fixture" matching_procs)
    local ok=1
    echo "$matched" | grep -q '^12345' || { echo "FAIL: should match hsqa-server line 12345"; ok=0; }
    echo "$matched" | grep -q '^12346' || { echo "FAIL: should match Xvfb :98 line 12346"; ok=0; }
    echo "$matched" | grep -q '^12347' || { echo "FAIL: should match tmux hsqa-live line 12347"; ok=0; }
    echo "$matched" | grep -q '^12349' || { echo "FAIL: should match tmux hsqa-playtest line 12349"; ok=0; }
    echo "$matched" | grep -q '^22999' || { echo "FAIL: should match hsqa-inst line 22999"; ok=0; }
    echo "$matched" | grep -q '^23001' && { echo "FAIL: must NEVER match a bare 'hsqa-install' mention (substring false-positive, proven live)"; ok=0; }
    echo "$matched" | grep -q '^22375' && { echo "FAIL: must NEVER match the GradleDaemon line 22375"; ok=0; }
    echo "$matched" | grep -q '^12348' && { echo "FAIL: should not match an unrelated python process"; ok=0; }

    # The reaper must never report its OWN CALLER as a leak. A wrapper whose
    # argv legitimately contains a harness path (a `bash -c` line mentioning
    # hsqa-inst/...) matched the pattern and was reported, because no text
    # pattern can exclude the caller's argv — only identity can. This feeds
    # this shell's real PID through the real matcher on a line that WOULD
    # otherwise match.
    local self_line="$$ /bin/bash -c reap-selftest /tmp/claude-0/hsqa-inst/f1proof"
    local self_matched
    self_matched=$(HSQA_REAP_TEST_INPUT="$self_line" matching_procs)
    [ -z "$self_matched" ] || { echo "FAIL: matched its own caller pid $$ — got: $self_matched"; ok=0; }

    # The pidfile-stage guard, exercised through pid_disposition() ITSELF —
    # the same function cmd_reap calls. It used to be re-typed here, which is
    # the very defect finding 10 was raised for, one stage along.
    local d
    d=$(HSQA_REAP_TEST_CMDLINES="4242 java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3" \
        pid_disposition 4242)
    [ "$d" = refuse ] || { echo "FAIL: a recycled PID that is now a GradleDaemon must be 'refuse', got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_CMDLINES="4242 java -jar /tmp/claude-0/hsqa-inst/live/server.jar" pid_disposition 4242)
    [ "$d" = kill ] || { echo "FAIL: a live harness process must be 'kill', got '$d'"; ok=0; }
    d=$(HSQA_REAP_TEST_CMDLINES="" pid_disposition 999999999)
    [ "$d" = gone ] || { echo "FAIL: a PID with no process must be 'gone', got '$d'"; ok=0; }
    # And the leading '-' a group-kill entry carries must not defeat the lookup.
    d=$(HSQA_REAP_TEST_CMDLINES="4242 java ... GradleDaemon 8.14.3" pid_disposition -4242)
    [ "$d" = refuse ] || { echo "FAIL: a '-PID' group entry must resolve like its bare PID, got '$d'"; ok=0; }

    if [ "$ok" = 1 ]; then echo "reap selftest: PASS (GradleDaemon excluded, harness processes matched, own caller never matched, pidfile-guard exercised through pid_disposition)"; return 0
    else echo "reap selftest: FAIL"; return 1; fi
}

SUBCMD="${1:-check}"
case "$SUBCMD" in
    dry-run|dryrun|check|reap)
        # Finding 5: AC-7's teardown trials each end in a `reap check` whose
        # transcript and verdict used to be stored nowhere durable — proven
        # clean only in whatever ran the command, gone the moment that
        # process exited. One evidence dir per invocation, same layout as
        # every other suite.
        ev_init "reap"
        LOGNAME="${SUBCMD}.log"
        case "$SUBCMD" in
            dry-run|dryrun) cmd_dryrun  > "$EV_LOGS/$LOGNAME" 2>&1;;
            check)          cmd_check   > "$EV_LOGS/$LOGNAME" 2>&1;;
            reap)           cmd_reap    > "$EV_LOGS/$LOGNAME" 2>&1;;
        esac
        RC=$?
        cat "$EV_LOGS/$LOGNAME"
        if [ "$RC" = 0 ]; then
            check_pass "$SUBCMD" "clean — see logs/$LOGNAME"
            finish_result PASS
        else
            check_fail "$SUBCMD" "leaked processes/ports found — see logs/$LOGNAME"
            finish_result FAIL
        fi
        write_reproduction "# Reproduce: reap $SUBCMD
tools/hearthstead-qa reap $SUBCMD
Verdict: $([ "$RC" = 0 ] && echo PASS || echo FAIL) (exit $RC)
Full transcript: logs/$LOGNAME
"
        exit "$RC"
        ;;
    selftest)       cmd_selftest;;
    *) echo "usage: reap.sh [dry-run|check|selftest|reap]"; exit 1;;
esac
