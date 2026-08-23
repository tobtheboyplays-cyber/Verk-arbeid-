#!/usr/bin/env bash
# PreToolUse(Bash) hook: block direct Minecraft test invocations that bypass
# the QA controller. Compilation and inspection stay allowed.
INPUT=$(cat)
CMD=$(printf '%s' "$INPUT" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get('tool_input',{}).get('command','') or '')
except Exception:
    print('')")

case "$CMD" in
    *hearthstead-qa*) exit 0;;   # controller invocations are the approved path
esac

if printf '%s' "$CMD" | grep -qE 'gradlew[^|;&]*(runGameTestServer|runClient|runServer|runGameTestClient)'; then
    echo "Blocked: Minecraft test/run tasks must go through the QA controller (qa/PROTOCOL.md)." >&2
    echo "Use instead: tools/hearthstead-qa gametest | behavior | dedicated | client | full" >&2
    echo "(compileJava, build and inspection commands remain allowed.)" >&2
    exit 2
fi
exit 0
