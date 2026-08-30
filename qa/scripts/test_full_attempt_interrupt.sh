#!/usr/bin/env bash
# Executable old-PASS -> interrupted real `full` dispatch -> red gate contract.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_ROOT=''
LAUNCHER=''
EXPECTED_SUITES=(doctor assets animation build gametest behavior dedicated \
    blessing_restart performance client playtest visual)

cleanup() {
    [ -z "${LAUNCHER:-}" ] || kill -9 -- "-$LAUNCHER" 2>/dev/null || true
    local target="${TEST_ROOT:-}" canonical
    [ -n "$target" ] || return 0
    canonical=$(realpath -m -- "$target") || return 1
    case "$canonical" in /tmp/hsqa-full-dispatch.*) ;; *) return 1;; esac
    rm -rf -- "$canonical" || return 1
    [ ! -e "$canonical" ]
}
fail() { echo "full-attempt interrupt selftest: FAIL: $*" >&2; exit 1; }

# None of these ambient values may grant child/lock authority or redirect the
# copied fixture back into the real checkout. The fixture supplies its own
# bounded roots below.
unset HSQA_SUITE_LOCK_TOKEN HSQA_LOCK_BOOTSTRAPPED HSQA_FULL_MARKED \
    HSQA_REPO HSQA_PROCESS_IDENTITY HSQA_FULL_MARK_TEST_PAUSE \
    HSQA_REAP_TEST_INPUT HSQA_REAP_TEST_OWNED_INSTANCES \
    HSQA_REAP_TEST_CMDLINES HSQA_REAP_TEST_STARTTIMES \
    HSQA_REAP_TEST_IDENTITIES HSQA_REAP_TEST_GROUPS

# This is intentionally standalone: when invoked from inside another
# controller, that controller already owns the host lock and the real-full
# contender must be rejected.  The controller's doctor covers the helper-level
# marker contract; this test covers the actual outer flock/bootstrap dispatch.
flock -n --close --conflict-exit-code 75 /tmp/hearthstead-qa-v2.lock true
[ "$?" -eq 0 ] || fail "host-global suite lock is already held"

TEST_ROOT=$(mktemp -d /tmp/hsqa-full-dispatch.XXXXXX) || exit 1
TEST_ROOT=$(realpath -- "$TEST_ROOT") || exit 1
trap cleanup EXIT
mkdir -p "$TEST_ROOT/tmp" || fail "could not create isolated harness root"
export HSQA_SAFE_TMP_ROOT="$TEST_ROOT/tmp"
export HSQA_PIDDIR="$TEST_ROOT/tmp/pids"
export HSQA_INST_ROOT="$TEST_ROOT/tmp/instances"
export HSQA_ARTIFACTS="$TEST_ROOT/qa/reports/artifacts"
mkdir -p "$TEST_ROOT/qa" "$TEST_ROOT/tools" "$TEST_ROOT/hearthstead-neoforge/docs"
cp -a "$REPO/qa/hooks" "$REPO/qa/scripts" "$REPO/qa/scenarios" "$TEST_ROOT/qa/" \
    || fail "could not copy QA assertion inputs"
cp "$REPO/qa/PROTOCOL.md" "$TEST_ROOT/qa/PROTOCOL.md" \
    || fail "could not copy protocol"
cp "$REPO/tools/hearthstead-qa" "$TEST_ROOT/tools/hearthstead-qa" \
    || fail "could not copy controller"
cp -a "$REPO/hearthstead-neoforge/src" "$REPO/hearthstead-neoforge/tools" \
    "$REPO/hearthstead-neoforge/gradle" "$TEST_ROOT/hearthstead-neoforge/" \
    || fail "could not copy fingerprinted discovered inputs"
cp "$REPO/hearthstead-neoforge/build.gradle" \
    "$REPO/hearthstead-neoforge/gradle.properties" \
    "$REPO/hearthstead-neoforge/settings.gradle" \
    "$REPO/hearthstead-neoforge/gradlew" \
    "$REPO/hearthstead-neoforge/gradlew.bat" \
    "$TEST_ROOT/hearthstead-neoforge/" \
    || fail "could not copy fingerprinted build inputs"
cp "$REPO/hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md" \
    "$TEST_ROOT/hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md" \
    || fail "could not copy animation contract"
cp "$REPO/.gitattributes" "$TEST_ROOT/.gitattributes" \
    || fail "could not copy EOL contract"
chmod +x "$TEST_ROOT/tools/hearthstead-qa" \
    "$TEST_ROOT/qa/scripts/controller_lock_bootstrap.sh"

FINGERPRINT=$(bash "$TEST_ROOT/qa/scripts/source_fingerprint.sh" "$TEST_ROOT") \
    || fail "fixture fingerprint failed"
OLD_RUN="$TEST_ROOT/qa/reports/artifacts/old-pass"
OLD_LATEST="$TEST_ROOT/qa/reports/latest.json"
OLD_RUN_MANIFEST="$OLD_RUN/manifest.json"
OLD_LATEST_SNAPSHOT="$TEST_ROOT/old-latest.snapshot"
OLD_RUN_SNAPSHOT="$TEST_ROOT/old-run.snapshot"
mkdir -p "$OLD_RUN"
python3 - "$OLD_LATEST" "$OLD_RUN_MANIFEST" "$OLD_RUN" "$FINGERPRINT" \
        "${EXPECTED_SUITES[@]}" <<'PYEOF' \
    || fail "could not seed old PASS"
import json, sys
latest, run_manifest, run_dir, fingerprint = sys.argv[1:5]
suites = {name: {"status": "PASS", "note": "interrupt-selftest"}
          for name in sys.argv[5:]}
manifest = {"protocol_version": "1.3.0", "fingerprint": fingerprint,
            "overall": "PASS", "green_streak": 2, "suites": suites,
            "artifacts": run_dir}
for path in (latest, run_manifest):
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
PYEOF
cp -- "$OLD_LATEST" "$OLD_LATEST_SNAPSHOT" \
    && cp -- "$OLD_RUN_MANIFEST" "$OLD_RUN_SNAPSHOT" \
    || fail "could not snapshot valid old evidence"

# Establish the premise, not merely the postcondition: the exact pair must be
# gate-green before the interrupted full attempt. Otherwise any later red
# verdict would be vacuous.
if ! bash "$TEST_ROOT/tools/hearthstead-qa" gate \
        > "$TEST_ROOT/gate-before.out" 2>&1; then
    fail "valid old full-evidence pair was not gate-green before interruption"
fi
grep -Fxq 'GATE: PASS (green_streak=2)' "$TEST_ROOT/gate-before.out" \
    || fail "pre-interrupt gate omitted its exact PASS verdict"
grep -q '^GATE: FAIL\|^GATE: BLOCKED' "$TEST_ROOT/gate-before.out" \
    && fail "pre-interrupt gate emitted a contradictory verdict"

touch "$TEST_ROOT/.full-mark-hold"
setsid env HSQA_FULL_MARK_TEST_PAUSE=1 \
    bash "$TEST_ROOT/tools/hearthstead-qa" full \
    > "$TEST_ROOT/full.out" 2>&1 &
LAUNCHER=$!
for _ in $(seq 1 500); do
    [ -f "$TEST_ROOT/.full-mark-ready" ] && break
    kill -0 "$LAUNCHER" 2>/dev/null || break
    sleep 0.01
done
[ -f "$TEST_ROOT/.full-mark-ready" ] \
    || fail "real full dispatch never durably marked the attempt"
[ -f "$TEST_ROOT/qa/reports/.stale" ] \
    && [ ! -L "$TEST_ROOT/qa/reports/.stale" ] \
    || fail "real full dispatch did not publish a regular stale marker"

LAUNCHER_PGID=$(ps -o pgid= -p "$LAUNCHER" 2>/dev/null | tr -d '[:space:]')
[ "$LAUNCHER_PGID" = "$LAUNCHER" ] \
    || fail "isolated full dispatch did not own its exact process group"
kill -0 -- "-$LAUNCHER" 2>/dev/null \
    || fail "paused full process group disappeared before interruption"

# Gate is globally serialized with full. A gate racing this accepted paused
# attempt must be rejected by the lock, never print the previous PASS.
if bash "$TEST_ROOT/tools/hearthstead-qa" gate \
        > "$TEST_ROOT/gate-during.out" 2>&1; then
    GATE_DURING_STATUS=0
else
    GATE_DURING_STATUS=$?
fi
[ "$GATE_DURING_STATUS" -eq 75 ] \
    || fail "gate contender returned $GATE_DURING_STATUS instead of lock conflict 75"
grep -q '^GATE: PASS' "$TEST_ROOT/gate-during.out" \
    && fail "gate contender printed stale PASS while full owned the lock"

# SIGKILL is deliberate: it is the one untrappable termination. INT/TERM
# cleanup is covered by test_harness_artifacts after the controller starts;
# this test proves the durable pre-controller marker survives the harder gap.
kill -9 -- "-$LAUNCHER" 2>/dev/null \
    || fail "could not interrupt isolated full process group"
wait "$LAUNCHER" 2>/dev/null || true
LAUNCHER=''

for _ in $(seq 1 500); do
    kill -0 -- "-$LAUNCHER_PGID" 2>/dev/null || break
    sleep 0.01
done
kill -0 -- "-$LAUNCHER_PGID" 2>/dev/null \
    && fail "SIGKILLed full left a live process-group member"
flock -n --close --conflict-exit-code 75 /tmp/hearthstead-qa-v2.lock true \
    || fail "host-global suite lock was not reacquirable after interrupted full"

if bash "$TEST_ROOT/tools/hearthstead-qa" gate > "$TEST_ROOT/gate.out" 2>&1; then
    GATE_STATUS=0
else
    GATE_STATUS=$?
fi
[ "$GATE_STATUS" -eq 2 ] \
    || fail "interrupted full gate returned $GATE_STATUS instead of fail-closed 2"
grep -Fq 'GATE: FAIL — QA is stale or a full run is incomplete.' \
    "$TEST_ROOT/gate.out" \
    || fail "interrupted full did not fail for the durable stale marker"
grep -q '^GATE: PASS' "$TEST_ROOT/gate.out" \
    && fail "interrupted full emitted a contradictory green gate marker"
grep -q '^GATE: BLOCKED' "$TEST_ROOT/gate.out" \
    && fail "interrupted full was misreported as an external blocker"
cmp -s -- "$OLD_LATEST" "$OLD_LATEST_SNAPSHOT" \
    || fail "old latest PASS bytes were unexpectedly rewritten"
cmp -s -- "$OLD_RUN_MANIFEST" "$OLD_RUN_SNAPSHOT" \
    || fail "old run-manifest bytes were unexpectedly rewritten"
[ -f "$TEST_ROOT/qa/reports/.stale" ] \
    && [ ! -L "$TEST_ROOT/qa/reports/.stale" ] \
    || fail "post-interrupt gate removed or replaced the durable stale marker"
flock -n --close --conflict-exit-code 75 /tmp/hearthstead-qa-v2.lock true \
    || fail "gate did not release the host-global suite lock"

cleanup || fail "disposable repository fixture was not removed"
TEST_ROOT=''
trap - EXIT
echo "full-attempt interrupt selftest: PASS (real full dispatch + SIGKILL + fail-closed gate)"
