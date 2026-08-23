#!/usr/bin/env bash
# PostToolUse hook: after Claude edits QA-relevant files, mark QA stale and
# name the next focused command. Never runs expensive suites itself.
INPUT=$(cat)
FILE=$(printf '%s' "$INPUT" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get('tool_input',{}).get('file_path','') or '')
except Exception:
    print('')")
[ -z "$FILE" ] && exit 0

case "$FILE" in
    */hearthstead-neoforge/src/*|*/hearthstead-neoforge/tools/*|*/hearthstead-neoforge/build.gradle|*/hearthstead-neoforge/gradle.properties|*/hearthstead-neoforge/settings.gradle|*/qa/PROTOCOL.md|*/qa/scripts/*|*/tools/hearthstead-qa)
        REPO="${CLAUDE_PROJECT_DIR:-$(pwd)}"
        mkdir -p "$REPO/qa/reports"
        touch "$REPO/qa/reports/.stale"
        REL="${FILE#"$REPO"/}"
        SUITES=$(cd "$REPO" && case "$REL" in
            hearthstead-neoforge/src/main/java/com/hearthstead/entity/*|hearthstead-neoforge/src/main/java/com/hearthstead/settlement/*|hearthstead-neoforge/src/main/java/com/hearthstead/event/*) echo "behavior, then dedicated";;
            hearthstead-neoforge/src/main/java/com/hearthstead/client/*) echo "animation + client";;
            hearthstead-neoforge/src/main/java/com/hearthstead/menu/*) echo "gametest + client";;
            hearthstead-neoforge/src/main/resources/assets/*) echo "validate + animation";;
            hearthstead-neoforge/src/main/resources/data/*) echo "validate + gametest";;
            hearthstead-neoforge/src/main/java/com/hearthstead/gametest/*) echo "gametest";;
            *) echo "full";;
        esac)
        echo "HSQA: QA state marked STALE by edit to $REL. Focused next step: tools/hearthstead-qa ${SUITES%%,*} — completion still requires 'tools/hearthstead-qa full' (green_streak>=2)."
        ;;
esac
exit 0
