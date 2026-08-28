#!/usr/bin/env bash
# AC-8: four DRIVEN negative tests proving the harness names the true first
# cause instead of a misleading downstream symptom. Each test actually
# breaks a precondition, runs the real suite through the real controller,
# and asserts on its actual output — nothing here is merely described.
#
#   N1 port held         -> names the holding PID/cmdline, says nothing about settlers
#   N2 client build broken -> "client build failed", quotes the first javac
#                             error, never says "player never joined"
#   N3 server never Done(  -> says that (a clean EULA-false exit, distinct
#                             from N1's bind error, so both first-cause paths
#                             are proven, not just one)
#   N4 client up, no join  -> says that, stores FAILED-state.png
#
# N2 makes a TEMPORARY, fully-reverted edit under hearthstead-neoforge/src to
# actually break the build — the only way to drive a real client-build
# failure. It refuses to run unless src is clean beforehand, and a trap
# guarantees the revert runs even if the test itself fails or is interrupted.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MOD="$REPO/hearthstead-neoforge"
CTL="$REPO/tools/hearthstead-qa"
. "$HERE/lib_harness.sh"
# NOT "${1:-...}": $1 is the subcommand (n1/n2/n3/n4/all) — defaulting OUT
# from it was a real bug, caught live: it silently set OUT to the literal
# string "n1"/"n2"/etc (a relative path), scattering stray n1/ n2/ n3/ n4/
# directories in the repo root instead of writing anywhere near the
# canonical evidence store. Fixed default, override only via env var.
OUT="${HSQA_NEGATIVE_OUT:-/tmp/claude-0/hsqa-negative}"
mkdir -p "$OUT"

# Finding 5: n1..n4's transcripts and verdicts used to live ONLY under
# /tmp/claude-0/hsqa-negative — which dies with the container, so nothing
# durable proved these were ever driven. ev_init gives one durable evidence
# dir per invocation (qa/reports/artifacts/negative/<TS>/); each test's
# log still also lands in $OUT for quick local inspection, but the verdict
# and a copy of the transcript are now recorded in $EV_DIR too.
ev_init "negative"

pass=0; fail=0
report() { # <name> <ok:0/1> <detail>
    if [ "$2" = 0 ]; then
        echo "PASS N$1: $3"; pass=$((pass+1))
        check_pass "n$1" "$3"
    else
        echo "FAIL N$1: $3"; fail=$((fail+1))
        check_fail "n$1" "$3"
    fi
}

n1() {
    echo "=== N1: port held ==="
    python3 -c "
import socket, time
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('0.0.0.0', 25571)); s.listen(1)
time.sleep(60)
" &
    HOLDER_PID=$!
    sleep 1
    LOG="$OUT/n1.log"
    "$CTL" dedicated > "$LOG" 2>&1
    RC=$?
    kill "$HOLDER_PID" 2>/dev/null; wait "$HOLDER_PID" 2>/dev/null
    if [ $RC -eq 0 ]; then report 1 1 "dedicated PASSED while port was held (should have FAILed)"; return; fi
    if ! grep -qE "port 25571.*already in use.*held by pid" "$LOG"; then
        report 1 1 "did not name the holding PID/cmdline — see $LOG"; return
    fi
    if grep -qiE "settler" "$LOG"; then
        report 1 1 "message mentions settlers — should stop at the port fact"; return
    fi
    report 1 0 "named holder, said nothing about settlers ($LOG)"
}

n2() {
    echo "=== N2: client build broken ==="
    if [ -n "$(git -C "$REPO" status --porcelain -- hearthstead-neoforge/src)" ]; then
        report 2 1 "SKIPPED — hearthstead-neoforge/src is not clean; refusing to touch it"
        return
    fi
    TARGET="$MOD/src/main/java/com/hearthstead/command/HearthsteadCommand.java"
    [ -f "$TARGET" ] || { report 2 1 "target file missing: $TARGET"; return; }
    revert() { git -C "$REPO" checkout -- "$TARGET" 2>/dev/null; }
    # Finding 13: RETURN alone only fires if this function returns normally.
    # A Ctrl-C (or any interrupt) mid-run would leave the injected syntax
    # error in place, corrupting real source under hearthstead-neoforge/src.
    # EXIT/INT/TERM cover every way this shell can stop; `revert` is
    # idempotent (a `git checkout` of an already-clean file is a harmless
    # no-op), so trapping all four is safe even if more than one fires.
    trap revert RETURN EXIT INT TERM
    # An unambiguous syntax error: an unterminated statement.
    printf '\nBROKEN SYNTAX HERE NO SEMICOLON\n' >> "$TARGET"
    LOG="$OUT/n2.log"
    "$CTL" client > "$LOG" 2>&1
    RC=$?
    revert
    trap - RETURN EXIT INT TERM
    if [ -n "$(git -C "$REPO" status --porcelain -- hearthstead-neoforge/src)" ]; then
        report 2 1 "REVERT FAILED — src is still dirty, fix manually: git checkout -- $TARGET"; return
    fi
    if [ $RC -eq 0 ]; then report 2 1 "client PASSED with a broken build (should have FAILed)"; return; fi
    if ! grep -qi "client build failed" "$LOG"; then report 2 1 "did not say 'client build failed' — see $LOG"; return; fi
    if ! grep -qE "error:" "$LOG"; then report 2 1 "did not quote a javac error — see $LOG"; return; fi
    if grep -qi "player never joined" "$LOG"; then report 2 1 "misreported as 'player never joined' — see $LOG"; return; fi
    report 2 0 "named the build failure and quoted the first error ($LOG)"
}

n3() {
    echo "=== N3: server never reaches Done( (EULA declined, not a port clash) ==="
    LOG="$OUT/n3.log"
    HSQA_TEST_BAD_EULA=1 "$CTL" dedicated > "$LOG" 2>&1
    RC=$?
    if [ $RC -eq 0 ]; then report 3 1 "dedicated PASSED despite eula=false (should have FAILed)"; return; fi
    if ! grep -qi "never reached Done" "$LOG"; then report 3 1 "did not say 'never reached Done(' — see $LOG"; return; fi
    report 3 0 "correctly named 'server never reached Done(' ($LOG)"
}

n4() {
    echo "=== N4: client up, never joins ==="
    LOG="$OUT/n4.log"
    HSQA_TEST_BAD_JOIN_PORT=25599 "$CTL" playtest > "$LOG" 2>&1
    RC=$?
    if [ $RC -eq 0 ]; then report 4 1 "playtest PASSED despite pointing at a dead port (should have FAILed)"; return; fi
    if ! grep -qi "player never joined the world" "$LOG"; then report 4 1 "did not say 'player never joined the world' — see $LOG"; return; fi
    SHOT=$(find "$REPO/qa/reports/artifacts/playtest" -name 'FAILED-state.png' -newer "$LOG" 2>/dev/null | head -1)
    [ -z "$SHOT" ] && SHOT=$(find "$REPO/qa/reports/artifacts/playtest" -name 'FAILED-state.png' 2>/dev/null | tail -1)
    if [ -z "$SHOT" ] || [ ! -s "$SHOT" ]; then report 4 1 "FAILED-state.png not stored — see $LOG"; return; fi
    report 4 0 "correctly named 'player never joined', stored $SHOT"
}

SELECTED="${1:-all}"
case "$SELECTED" in
    n1) shift; n1;;
    n2) shift; n2;;
    n3) shift; n3;;
    n4) shift; n4;;
    all|"") n1; n2; n3; n4;;
    *) echo "usage: negative_tests.sh [n1|n2|n3|n4|all]"; exit 1;;
esac

# Durable copies of every transcript this invocation actually produced
# (finding 5) — whichever of n1..n4 ran land in $EV_LOGS alongside the
# per-test verdicts already recorded via check_pass/check_fail above.
for f in "$OUT"/n1.log "$OUT"/n2.log "$OUT"/n3.log "$OUT"/n4.log; do
    [ -f "$f" ] && cp "$f" "$EV_LOGS/$(basename "$f")" 2>/dev/null
done

echo "--- negative tests: $pass passed, $fail failed ---"
if [ "$fail" -eq 0 ]; then
    finish_result PASS
else
    finish_result FAIL
fi
write_reproduction "# Reproduce: negative $SELECTED
tools/hearthstead-qa negative $SELECTED
$pass passed, $fail failed. Per-test verdicts and transcripts: logs/n{1,2,3,4}.log
"
[ "$fail" -eq 0 ]
