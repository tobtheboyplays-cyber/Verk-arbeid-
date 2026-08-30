#!/usr/bin/env bash
# Destructive-path and interpolation safety contract for disposable QA state.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib_safe_paths.sh"

TEST_ROOT=''
cleanup() {
    local target="${TEST_ROOT:-}" canonical
    [ -n "$target" ] || return 0
    canonical=$(realpath -m -- "$target") || return 1
    case "$canonical" in
        /tmp/hsqa-safe-paths.*) ;;
        *) echo "safe-path selftest: refusing unsafe cleanup $canonical" >&2; return 1 ;;
    esac
    rm -rf -- "$canonical" || return 1
    [ ! -e "$canonical" ]
}
fail() {
    echo "safe-path selftest: FAIL: $*" >&2
    exit 1
}
reject() { # <description> <command...>
    local description="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        fail "$description was accepted"
    fi
}

TEST_ROOT=$(mktemp -d /tmp/hsqa-safe-paths.XXXXXX) || exit 1
TEST_ROOT=$(realpath -- "$TEST_ROOT") || exit 1
trap cleanup EXIT
SAFE_BASE="$TEST_ROOT/safe"
mkdir -p -- "$SAFE_BASE"
export HSQA_SAFE_TMP_ROOT="$SAFE_BASE"

[ "$(hsqa_safe_base)" = "$SAFE_BASE" ] || fail "safe base did not round-trip"
[ "$(hsqa_safe_target "$SAFE_BASE/install" install)" = "$SAFE_BASE/install" ] \
    || fail "valid nested target did not round-trip"
reject "safe base itself" hsqa_safe_target "$SAFE_BASE" base
reject "filesystem root" hsqa_safe_target / root
reject "relative target" hsqa_safe_target relative/path relative
reject "outside target" hsqa_safe_target "$TEST_ROOT/outside" outside

mkdir -p -- "$TEST_ROOT/real"
ln -s "$TEST_ROOT/real" "$SAFE_BASE/link"
reject "symlink traversal" hsqa_safe_target "$SAFE_BASE/link/child" symlink

FRESH_ROOT="$SAFE_BASE/hsqa-inst-v2"
hsqa_claim_empty_directory \
    "$FRESH_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1 \
    || fail "fresh v2 root could not be claimed"
hsqa_require_owned_directory \
    "$FRESH_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1 \
    || fail "fresh v2 ownership could not be verified"

LEGACY_ROOT="$SAFE_BASE/hsqa-inst"
mkdir -p -- "$LEGACY_ROOT/old-role"
printf 'legacy\n' > "$LEGACY_ROOT/old-role/state"
reject "non-empty legacy root" hsqa_claim_empty_directory \
    "$LEGACY_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1

# Exercise the destructive caller itself, not only its helper. This minimal
# fixture proves a fresh marker-owned v2 root is materialised successfully,
# while the legacy non-empty unowned root is rejected before any removal.
grep -Fq '${HSQA_INST_ROOT:-/tmp/claude-0/hsqa-inst-v2}' \
    "$SCRIPT_DIR/server_instance.sh" \
    || fail "server_instance default is not the fresh v2 root"
FAKE_INSTALL="$SAFE_BASE/install-v2"
hsqa_claim_empty_directory \
    "$FAKE_INSTALL" .hsqa-install-owned hsqa-install-v1 \
    || fail "fake install root could not be claimed"
mkdir -p -- "$FAKE_INSTALL/libraries"
printf '#!/usr/bin/env bash\nexit 0\n' > "$FAKE_INSTALL/run.sh"
chmod +x "$FAKE_INSTALL/run.sh"
FAKE_MOD="$SAFE_BASE/fake-mod"
mkdir -p -- "$FAKE_MOD/build/libs"
printf 'fixture jar\n' > "$FAKE_MOD/build/libs/hearthstead-fixture.jar"
printf 'neoforge_version=fixture\n' > "$FAKE_MOD/gradle.properties"
INSTANCE_OUTPUT=$( \
    HSQA_SAFE_TMP_ROOT="$SAFE_BASE" \
    HSQA_INSTALL_DIR="$FAKE_INSTALL" \
    HSQA_INST_ROOT="$FRESH_ROOT" \
    HSQA_LEVEL_TYPE=flat \
    bash "$SCRIPT_DIR/server_instance.sh" fixture 24567 "$FAKE_MOD" \
) || fail "actual fresh-v2 server_instance fixture failed"
ACTUAL_INSTANCE=$(printf '%s\n' "$INSTANCE_OUTPUT" | tail -1)
[ "$ACTUAL_INSTANCE" = "$FRESH_ROOT/fixture" ] \
    || fail "server_instance returned unexpected fixture path"
hsqa_require_owned_directory \
    "$ACTUAL_INSTANCE" .hsqa-instance-owned hsqa-instance-v1:fixture \
    || fail "actual fixture instance ownership was not authored"
[ "$(grep -Fxc 'level-type=minecraft\:flat' \
        "$ACTUAL_INSTANCE/server.properties")" -eq 1 ] \
    || fail "actual fixture did not author exact flat world type"

if HSQA_SAFE_TMP_ROOT="$SAFE_BASE" \
        HSQA_INSTALL_DIR="$FAKE_INSTALL" \
        HSQA_INST_ROOT="$LEGACY_ROOT" \
        bash "$SCRIPT_DIR/server_instance.sh" fixture 24568 "$FAKE_MOD" \
        >/dev/null 2>&1; then
    fail "actual server_instance accepted legacy non-empty unowned root"
fi
[ -f "$LEGACY_ROOT/old-role/state" ] \
    || fail "legacy rejection modified existing contents"
if HSQA_SAFE_TMP_ROOT="$SAFE_BASE" \
        HSQA_INSTALL_DIR="$FAKE_INSTALL" \
        HSQA_INST_ROOT="$FRESH_ROOT" \
        HSQA_LEVEL_TYPE=$'flat\nlevel-seed=owned' \
        bash "$SCRIPT_DIR/server_instance.sh" injected 24569 "$FAKE_MOD" \
        >/dev/null 2>&1; then
    fail "actual server_instance accepted injected level type"
fi
[ ! -e "$FRESH_ROOT/injected" ] \
    || fail "invalid level type mutated the instance root"

WORLD_MOD="$SAFE_BASE/world-mod"
mkdir -p -- "$WORLD_MOD/run/world" "$WORLD_MOD/run/keep"
printf 'generated state\n' > "$WORLD_MOD/run/world/state"
hsqa_clear_gametest_world "$WORLD_MOD" \
    || fail "exact ordinary GameTest world could not be cleared"
[ ! -e "$WORLD_MOD/run/world" ] && [ -d "$WORLD_MOD/run/keep" ] \
    || fail "GameTest cleanup escaped the exact world directory"
EXTERNAL_WORLD="$TEST_ROOT/external-world"
mkdir -p -- "$EXTERNAL_WORLD" "$WORLD_MOD/run"
printf 'preserve me\n' > "$EXTERNAL_WORLD/sentinel"
ln -s "$EXTERNAL_WORLD" "$WORLD_MOD/run/world"
reject "symlinked GameTest world" hsqa_clear_gametest_world "$WORLD_MOD"
[ -f "$EXTERNAL_WORLD/sentinel" ] \
    || fail "symlinked GameTest rejection removed external sentinel"

RUN_GUARD_MOD="$SAFE_BASE/run-guard-mod"
RUN_EXTERNAL="$TEST_ROOT/run-external"
mkdir -p "$RUN_GUARD_MOD" "$RUN_EXTERNAL"
printf 'external options\n' > "$RUN_EXTERNAL/options.txt"
ln -s "$RUN_EXTERNAL" "$RUN_GUARD_MOD/run"
reject "symlinked module run directory" hsqa_require_plain_mod_run "$RUN_GUARD_MOD"
[ "$(cat "$RUN_EXTERNAL/options.txt")" = 'external options' ] \
    || fail "run-directory rejection modified external options"
rm "$RUN_GUARD_MOD/run"
mkdir "$RUN_GUARD_MOD/run"
ln -s "$RUN_EXTERNAL/options.txt" "$RUN_GUARD_MOD/run/options.txt"
reject "symlinked module options file" hsqa_require_plain_mod_run_file \
    "$RUN_GUARD_MOD" options.txt
[ "$(cat "$RUN_EXTERNAL/options.txt")" = 'external options' ] \
    || fail "options-file rejection modified external sentinel"
rm "$RUN_GUARD_MOD/run/options.txt"
LOG_EXTERNAL="$TEST_ROOT/log-external"
mkdir "$LOG_EXTERNAL"
printf 'external latest\n' > "$LOG_EXTERNAL/latest.log"
ln -s "$LOG_EXTERNAL" "$RUN_GUARD_MOD/run/logs"
reject "symlinked module log directory" hsqa_require_plain_mod_log "$RUN_GUARD_MOD"
[ "$(cat "$LOG_EXTERNAL/latest.log")" = 'external latest' ] \
    || fail "log-directory rejection modified external latest.log"

WRONG_ROOT="$SAFE_BASE/wrong"
mkdir -p -- "$WRONG_ROOT"
printf 'wrong-owner\n' > "$WRONG_ROOT/.hsqa-instance-root-owned"
reject "wrong ownership marker" hsqa_require_owned_directory \
    "$WRONG_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1

SYMLINK_MARKER_ROOT="$SAFE_BASE/symlink-marker"
mkdir -p -- "$SYMLINK_MARKER_ROOT"
printf 'hsqa-instance-root-v1\n' > "$TEST_ROOT/outside-marker"
ln -s "$TEST_ROOT/outside-marker" \
    "$SYMLINK_MARKER_ROOT/.hsqa-instance-root-owned"
reject "symlink ownership marker" hsqa_require_owned_directory \
    "$SYMLINK_MARKER_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1

HARDLINK_MARKER_ROOT="$SAFE_BASE/hardlink-marker"
mkdir -p -- "$HARDLINK_MARKER_ROOT"
printf 'hsqa-instance-root-v1\n' > "$TEST_ROOT/outside-hardlink-marker"
ln "$TEST_ROOT/outside-hardlink-marker" \
    "$HARDLINK_MARKER_ROOT/.hsqa-instance-root-owned"
reject "hard-linked ownership marker" hsqa_require_owned_directory \
    "$HARDLINK_MARKER_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1
reject "unsafe marker filename" hsqa_claim_empty_directory \
    "$SAFE_BASE/unsafe-marker" '../owned' hsqa-instance-root-v1
reject "trailing marker contract content" hsqa_validate_marker_contract \
    .hsqa-test-owned 'hsqa-test-v1 trailing'
reject "newline marker contract content" hsqa_validate_marker_contract \
    .hsqa-test-owned $'hsqa-test-v1\ntrailing'

grep -Fq '${HSQA_CLIENT_INSTALL_DIR:-/tmp/claude-0/hsqa-client-install-v2}' \
    "$SCRIPT_DIR/client_install.sh" \
    || fail "client_install default is not the fresh v2 root"
CLIENT_FRESH="$SAFE_BASE/hsqa-client-install-v2"
hsqa_claim_empty_directory \
    "$CLIENT_FRESH" .hsqa-client-install-owned hsqa-client-install-v1 \
    || fail "fresh client install root could not be claimed"
mkdir -p -- "$CLIENT_FRESH/libraries"
printf '#!/usr/bin/env python3\n' > "$CLIENT_FRESH/launch.py"
touch "$CLIENT_FRESH/installed-fixture"
HSQA_SAFE_TMP_ROOT="$SAFE_BASE" \
HSQA_CLIENT_INSTALL_DIR="$CLIENT_FRESH" \
    bash "$SCRIPT_DIR/client_install.sh" "$FAKE_MOD" >/dev/null \
    || fail "actual cached client_install v2 fixture failed"

CLIENT_LEGACY="$SAFE_BASE/hsqa-client-install"
mkdir -p -- "$CLIENT_LEGACY/libraries"
printf '#!/usr/bin/env python3\n' > "$CLIENT_LEGACY/launch.py"
touch "$CLIENT_LEGACY/installed-fixture"
printf 'preserve client data\n' > "$CLIENT_LEGACY/sentinel"
if HSQA_SAFE_TMP_ROOT="$SAFE_BASE" \
        HSQA_CLIENT_INSTALL_DIR="$CLIENT_LEGACY" \
        bash "$SCRIPT_DIR/client_install.sh" "$FAKE_MOD" --force \
        >/dev/null 2>&1; then
    fail "client_install force accepted unowned legacy root"
fi
[ -f "$CLIENT_LEGACY/sentinel" ] \
    || fail "client_install rejection removed legacy sentinel"

CLIENT_EXTERNAL="$TEST_ROOT/client-external"
mkdir -p -- "$CLIENT_EXTERNAL"
printf 'preserve external client data\n' > "$CLIENT_EXTERNAL/sentinel"
ln -s "$CLIENT_EXTERNAL" "$SAFE_BASE/client-link"
if HSQA_SAFE_TMP_ROOT="$SAFE_BASE" \
        HSQA_CLIENT_INSTALL_DIR="$SAFE_BASE/client-link" \
        bash "$SCRIPT_DIR/client_install.sh" "$FAKE_MOD" --force \
        >/dev/null 2>&1; then
    fail "client_install accepted symlink-escaped install root"
fi
[ -f "$CLIENT_EXTERNAL/sentinel" ] \
    || fail "client_install symlink rejection removed external sentinel"

for value in performance active_perf role-1; do
    hsqa_validate_role "$value" || fail "valid role $value was rejected"
done
for value in '' '../escape' 'role/name' $'role\nother' '$(touch injected)'; do
    reject "unsafe role '$value'" hsqa_validate_role "$value"
done

for value in 1024 25572 65535; do
    hsqa_validate_port "$value" || fail "valid port $value was rejected"
done
for value in '' 0 1023 65536 -1 1.5 nope $'25572\nstop' '$(touch injected)'; do
    reject "unsafe port '$value'" hsqa_validate_port "$value"
done

for value in flat normal; do
    hsqa_validate_level_type "$value" \
        || fail "valid level type $value was rejected"
done
for value in '' default $'flat\nlevel-seed=owned' '$(touch injected)' 'flat;stop'; do
    reject "unsafe level type '$value'" hsqa_validate_level_type "$value"
done

cleanup || fail "validated temporary root was not removed"
TEST_ROOT=''
trap - EXIT
echo "safe-path selftest: PASS (containment + ownership + interpolation)"
