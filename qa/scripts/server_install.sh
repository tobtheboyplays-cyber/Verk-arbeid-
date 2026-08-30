#!/usr/bin/env bash
# Idempotent shared NeoForge server install (D-H2). One cache shared by every
# role/instance; per-role state (world, mods, logs, server.properties) lives
# separately under server_instance.sh so suites never contend for the same
# world or port. Args: <mod-dir> [--force]
set -eu
MOD="$1"
FORCE="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/lib_safe_paths.sh"
MOD=$(realpath -m -- "$MOD")
[ -d "$MOD" ] || { echo "FAIL: missing mod directory $MOD" >&2; exit 1; }
[ -z "$FORCE" ] || [ "$FORCE" = "--force" ] \
    || { echo "FAIL: unsupported server_install option '$FORCE'" >&2; exit 1; }
INSTALL_DIR=$(hsqa_safe_target \
    "${HSQA_INSTALL_DIR:-/tmp/claude-0/hsqa-install-v2}" "install") || exit 1
NEO_VERSION=$(grep -oP 'neoforge_version=\K.*' "$MOD/gradle.properties")

if [ ! -e "$INSTALL_DIR" ]; then
    hsqa_claim_empty_directory \
        "$INSTALL_DIR" .hsqa-install-owned hsqa-install-v1 || exit 1
elif [ ! -e "$INSTALL_DIR/.hsqa-install-owned" ]; then
    # Never infer ownership from familiar filenames. A user directory can
    # contain libraries/run.sh too; adopting it would let a later --force
    # recursively delete data that this harness did not create.
    hsqa_claim_empty_directory \
        "$INSTALL_DIR" .hsqa-install-owned hsqa-install-v1 || exit 1
fi
hsqa_require_owned_directory \
    "$INSTALL_DIR" .hsqa-install-owned hsqa-install-v1 || exit 1

if [ "$FORCE" = "--force" ]; then
    rm -rf -- "$INSTALL_DIR"
    hsqa_claim_empty_directory \
        "$INSTALL_DIR" .hsqa-install-owned hsqa-install-v1 || exit 1
fi

if [ -f "$INSTALL_DIR/installed-$NEO_VERSION" ] && [ -d "$INSTALL_DIR/libraries" ]; then
    echo "install cached: $INSTALL_DIR (neoforge $NEO_VERSION)"
    exit 0
fi

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
rm -rf libraries run.sh run.bat user_jvm_args.txt installed-* installer.jar* install.log
echo "installing NeoForge $NEO_VERSION server into $INSTALL_DIR ..."
curl -sS -o installer.jar "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VERSION/neoforge-$NEO_VERSION-installer.jar"
java -jar installer.jar --install-server . > install.log 2>&1
[ -d libraries ] && [ -f run.sh ] || { echo "FAIL: install did not produce libraries/ and run.sh — see $INSTALL_DIR/install.log"; exit 1; }
chmod +x run.sh
touch "installed-$NEO_VERSION"
echo "install ok: $INSTALL_DIR (neoforge $NEO_VERSION)"
