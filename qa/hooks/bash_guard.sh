#!/usr/bin/env bash
# PreToolUse(Bash) hook: block direct Minecraft test invocations that bypass
# the QA controller. Compilation and inspection stay allowed.
#
# Must block an actual invocation, not the same words appearing inside a
# heredoc body (a commit message), a quoted echo/printf argument, or a
# comment — see qa/hooks/test_bash_guard.py for the fixtures this must pass.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "${1:-}" = "--selftest" ]; then
    exec python3 "$HERE/test_bash_guard.py"
fi

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

VERDICT=$(python3 "$HERE/bash_guard_filter.py" "$CMD")
if [ "$VERDICT" = "BLOCK" ]; then
    echo "Blocked: Minecraft test/run tasks must go through the QA controller (qa/PROTOCOL.md)." >&2
    echo "Use instead: tools/hearthstead-qa gametest | behavior | dedicated | client | playtest | live | full" >&2
    echo "(compileJava, build and inspection commands remain allowed.)" >&2
    exit 2
fi
exit 0
