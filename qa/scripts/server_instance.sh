#!/usr/bin/env bash
# Materialise a fresh, isolated dedicated-server instance for one role,
# sharing the big NeoForge install (libraries/) read-only via symlink so
# each instance provisions in seconds, not minutes (D-H2).
#
# Args: <role> <port> <mod-dir>
# Prints the instance directory path on the last line of stdout.
set -eu
ROLE="$1"; PORT="$2"; MOD="$3"
INSTALL_DIR="${HSQA_INSTALL_DIR:-/tmp/claude-0/hsqa-install}"
INST_ROOT="${HSQA_INST_ROOT:-/tmp/claude-0/hsqa-inst}"
INST="$INST_ROOT/$ROLE"

[ -d "$INSTALL_DIR/libraries" ] && [ -x "$INSTALL_DIR/run.sh" ] \
    || { echo "FAIL: shared install missing at $INSTALL_DIR — run server_install.sh first" >&2; exit 1; }

JAR=$(ls -t "$MOD"/build/libs/hearthstead-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || { echo "FAIL: no mod jar built in $MOD/build/libs — build first" >&2; exit 1; }

rm -rf "$INST"
mkdir -p "$INST/mods"
ln -s "$INSTALL_DIR/libraries" "$INST/libraries"
cp "$INSTALL_DIR/run.sh" "$INST/run.sh"; chmod +x "$INST/run.sh"
[ -f "$INSTALL_DIR/run.bat" ] && cp "$INSTALL_DIR/run.bat" "$INST/run.bat"
[ -f "$INSTALL_DIR/user_jvm_args.txt" ] && cp "$INSTALL_DIR/user_jvm_args.txt" "$INST/user_jvm_args.txt"
cp "$JAR" "$INST/mods/"

echo "eula=true" > "$INST/eula.txt"
cat > "$INST/server.properties" <<EOF
server-port=$PORT
online-mode=false
spawn-protection=0
level-type=minecraft\\:flat
max-tick-time=180000
EOF

echo "instance ready: role=$ROLE port=$PORT jar=$(basename "$JAR")"
echo "$INST"
