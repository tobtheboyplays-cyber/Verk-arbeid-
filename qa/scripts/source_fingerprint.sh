#!/usr/bin/env bash
# One path-safe implementation shared by the controller and scenario harness.
set -u

SELFTEST_TEMP_BASE=''
SELFTEST_TEMP_ROOT=''
cleanup_selftest() {
    local target="${SELFTEST_TEMP_ROOT:-}" base="${SELFTEST_TEMP_BASE:-}"
    [ -n "$target" ] || return 0
    case "$target" in
        "$base"/'hsqa fingerprint.'*) ;;
        *) echo "fingerprint selftest: refusing unsafe cleanup $target" >&2; return 1 ;;
    esac
    rm -rf -- "$target" || return 1
    [ ! -e "$target" ] || return 1
    SELFTEST_TEMP_ROOT=''
}

fingerprint_repo() { # <repository root>
    local repo="$1" manifest
    manifest="$({
        cd "$repo" || exit 1
        export LC_ALL=C
        local discovered explicit
        discovered="$(
            set -o pipefail
            LC_ALL=C find hearthstead-neoforge/src hearthstead-neoforge/tools \
                    hearthstead-neoforge/gradle/wrapper \
                    qa/hooks qa/scripts qa/scenarios \
                -type f \
                -not -path '*/__pycache__/*' \
                -not -path '*/node_modules/*' \
                -print0 2>/dev/null \
                | LC_ALL=C sort -z \
                | xargs -0 -r sha256sum
        )" || exit 1
        explicit="$(sha256sum \
            hearthstead-neoforge/build.gradle \
            hearthstead-neoforge/gradle.properties \
            hearthstead-neoforge/settings.gradle \
            hearthstead-neoforge/gradlew \
            hearthstead-neoforge/gradlew.bat \
            hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md \
            qa/PROTOCOL.md qa/RELEASE_CLIENT_GATE.md \
            qa/release_client_matrix.json tools/hearthstead-qa .gitattributes
        )" || exit 1
        printf '%s\n%s\n' "$discovered" "$explicit"
    })" || return 1
    printf '%s' "$manifest" | sha256sum | cut -d' ' -f1
}

selftest() {
    local first second third
    SELFTEST_TEMP_BASE=$(realpath -m "${TMPDIR:-/tmp}")
    SELFTEST_TEMP_ROOT=$(mktemp -d \
        "$SELFTEST_TEMP_BASE/hsqa fingerprint.XXXXXX") || return 1
    SELFTEST_TEMP_ROOT=$(realpath "$SELFTEST_TEMP_ROOT")
    case "$SELFTEST_TEMP_ROOT" in
        "$SELFTEST_TEMP_BASE"/'hsqa fingerprint.'*) ;;
        *) echo "fingerprint selftest: unsafe temp path $SELFTEST_TEMP_ROOT" >&2; return 1 ;;
    esac
    trap cleanup_selftest EXIT

    mkdir -p \
        "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/src" \
        "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/tools" \
        "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/gradle/wrapper" \
        "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/docs" \
        "$SELFTEST_TEMP_ROOT/qa/hooks" \
        "$SELFTEST_TEMP_ROOT/qa/scripts" \
        "$SELFTEST_TEMP_ROOT/qa/scenarios" \
        "$SELFTEST_TEMP_ROOT/tools"
    printf 'alpha\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/src/file with space.txt"
    printf 'tool\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/tools/tool.py"
    mkdir -p "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/tools/blockbench/node_modules/vendor"
    printf 'environment-only dependency\n' \
        > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/tools/blockbench/node_modules/vendor/index.js"
    printf 'wrapper\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/gradle/wrapper/gradle-wrapper.properties"
    printf 'hook\n' > "$SELFTEST_TEMP_ROOT/qa/hooks/stop_gate.sh"
    printf 'fingerprint helper\n' > "$SELFTEST_TEMP_ROOT/qa/scripts/source_fingerprint.sh"
    printf 'scenario\n' > "$SELFTEST_TEMP_ROOT/qa/scenarios/default.txt"
    printf 'build\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/build.gradle"
    printf 'properties\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/gradle.properties"
    printf 'settings\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/settings.gradle"
    printf 'gradlew\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/gradlew"
    printf 'gradlew bat\r\n' > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/gradlew.bat"
    printf 'animation contract\n' \
        > "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/docs/ANIMATION_CATALOGUE.md"
    printf 'protocol\n' > "$SELFTEST_TEMP_ROOT/qa/PROTOCOL.md"
    printf 'release client gate\n' \
        > "$SELFTEST_TEMP_ROOT/qa/RELEASE_CLIENT_GATE.md"
    printf '{"schemaVersion":1,"rows":[]}\n' \
        > "$SELFTEST_TEMP_ROOT/qa/release_client_matrix.json"
    printf 'controller\n' > "$SELFTEST_TEMP_ROOT/tools/hearthstead-qa"
    printf 'attributes\n' > "$SELFTEST_TEMP_ROOT/.gitattributes"

    first=$(fingerprint_repo "$SELFTEST_TEMP_ROOT") || return 1
    [[ "$first" =~ ^[0-9a-f]{64}$ ]] || return 1
    printf 'beta\n' >> "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/src/file with space.txt"
    second=$(fingerprint_repo "$SELFTEST_TEMP_ROOT") || return 1
    [ "$first" != "$second" ] || return 1

    # Ignored dependency trees are not present in a clean source sync and must
    # not make fingerprints depend on one developer machine's npm install.
    printf 'local node_modules mutation\n' \
        >> "$SELFTEST_TEMP_ROOT/hearthstead-neoforge/tools/blockbench/node_modules/vendor/index.js"
    third=$(fingerprint_repo "$SELFTEST_TEMP_ROOT") || return 1
    [ "$second" = "$third" ] || return 1

    # A missing discovered root must propagate through find/sort/xargs rather
    # than being hidden behind the final sha256sum process.
    rm -f -- "$SELFTEST_TEMP_ROOT/qa/scenarios/default.txt"
    rmdir "$SELFTEST_TEMP_ROOT/qa/scenarios"
    if fingerprint_repo "$SELFTEST_TEMP_ROOT" >/dev/null 2>&1; then
        return 1
    fi
    cleanup_selftest || return 1
    trap - EXIT
    echo "source fingerprint selftest: PASS (space path + ignored dependencies + failure propagation)"
}

case "${1:-}" in
    --selftest) selftest ;;
    '') echo "usage: source_fingerprint.sh <repo-root>|--selftest" >&2; exit 2 ;;
    *) fingerprint_repo "$(realpath -m "$1")" ;;
esac
