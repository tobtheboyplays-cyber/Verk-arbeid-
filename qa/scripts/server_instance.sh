#!/usr/bin/env bash
# Materialise a fresh, isolated dedicated-server instance for one role,
# sharing the big NeoForge install (libraries/) read-only via symlink so
# each instance provisions in seconds, not minutes (D-H2).
#
# Args: <role> <port> <mod-dir>
# Prints the instance directory path on the last line of stdout.
set -eu
ROLE="$1"; PORT="$2"; MOD="$3"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib_safe_paths.sh"
hsqa_validate_role "$ROLE" \
    || { echo "FAIL: invalid QA role '$ROLE'" >&2; exit 1; }
hsqa_validate_port "$PORT" \
    || { echo "FAIL: invalid server port '$PORT'" >&2; exit 1; }
LEVEL_TYPE="${HSQA_LEVEL_TYPE:-flat}"
hsqa_validate_level_type "$LEVEL_TYPE" \
    || { echo "FAIL: invalid world level type '$LEVEL_TYPE'" >&2; exit 1; }
MOD=$(realpath -m -- "$MOD")
[ -d "$MOD" ] || { echo "FAIL: missing mod directory $MOD" >&2; exit 1; }
INSTALL_DIR=$(hsqa_safe_target \
    "${HSQA_INSTALL_DIR:-/tmp/claude-0/hsqa-install-v2}" "install") || exit 1
INST_ROOT=$(hsqa_safe_target \
    "${HSQA_INST_ROOT:-/tmp/claude-0/hsqa-inst-v2}" "instance root") || exit 1
hsqa_claim_empty_directory \
    "$INST_ROOT" .hsqa-instance-root-owned hsqa-instance-root-v1 || exit 1
INST="$INST_ROOT/$ROLE"
INST=$(hsqa_safe_target "$INST" "instance") || exit 1

[ -f "$INSTALL_DIR/.hsqa-install-owned" ] \
    && hsqa_require_owned_directory \
        "$INSTALL_DIR" .hsqa-install-owned hsqa-install-v1 \
    || { echo "FAIL: shared install lacks HSQA ownership marker" >&2; exit 1; }
[ -d "$INSTALL_DIR/libraries" ] && [ -x "$INSTALL_DIR/run.sh" ] \
    || { echo "FAIL: shared install missing at $INSTALL_DIR — run server_install.sh first" >&2; exit 1; }

JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || { echo "FAIL: no mod jar built in $MOD/build/libs — build first" >&2; exit 1; }

if [ -e "$INST" ]; then
    hsqa_require_owned_directory \
        "$INST" .hsqa-instance-owned "hsqa-instance-v1:$ROLE" || exit 1
    rm -rf -- "$INST"
fi
mkdir -p "$INST/mods"
printf '%s\n' "hsqa-instance-v1:$ROLE" > "$INST/.hsqa-instance-owned"
ln -s "$INSTALL_DIR/libraries" "$INST/libraries"
cp "$INSTALL_DIR/run.sh" "$INST/run.sh"; chmod +x "$INST/run.sh"
[ -f "$INSTALL_DIR/run.bat" ] && cp "$INSTALL_DIR/run.bat" "$INST/run.bat"
[ -f "$INSTALL_DIR/user_jvm_args.txt" ] && cp "$INSTALL_DIR/user_jvm_args.txt" "$INST/user_jvm_args.txt"
# A real JVM arg (shows up in `ps -eo args` for the actual java process, not
# just a wrapper shell) so reap.sh's pattern fallback can find and kill a
# leaked server even when a pidfile was never written (crash before launch
# finished registering it).
# A leading newline matters: the shared file's last line has no trailing
# newline, so a bare >> would otherwise glue this onto the end of a comment
# line (silently making the marker arg part of that comment, and inert).
printf '\n-Dhsqa.instanceDir=%s\n' "$INST" >> "$INST/user_jvm_args.txt"
cp "$JAR" "$INST/mods/"

# HSQA_TEST_BAD_EULA is a test-only hook (AC-8/N3): forces a real, distinct
# "server never reaches Done(" failure — a clean early exit, not a bind
# error — so the harness's ordered fact ladder can be proven against a
# second real cause, not just the port-contention one (N1).
if [ "${HSQA_TEST_BAD_EULA:-}" = "1" ]; then
    echo "eula=false" > "$INST/eula.txt"
else
    echo "eula=true" > "$INST/eula.txt"
fi
# World type. FLAT is the right default and stays the default: every
# automated suite wants a fast, deterministic, featureless world, and the
# GameTest and E2E scenarios build their own arenas anyway.
#
# But flat also means NO TREES, NO STONE AND NO ORE ANYWHERE -- which is
# invisible until someone tries to play. The first survival playthrough
# (2026-08-26) could not found a settlement at all: not because the mod was
# broken, but because there was nothing in the world to pick up. That is
# proof, of a kind nobody enjoys, that this harness had never once been
# driven by a player who had to gather anything.
#
# So: overridable, defaulting to what the suites need.
#   HSQA_LEVEL_TYPE=normal  -> a real world, for survival playthroughs
cat > "$INST/server.properties" <<EOF
server-port=$PORT
online-mode=false
spawn-protection=0
level-type=minecraft\\:$LEVEL_TYPE
max-tick-time=180000
EOF

echo "instance ready: role=$ROLE port=$PORT jar=$(basename "$JAR")"
echo "$INST"
