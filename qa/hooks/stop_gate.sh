#!/usr/bin/env bash
# Stop hook: quick freshness gate only — never launches Minecraft.
REPO="${CLAUDE_PROJECT_DIR:-$(pwd)}"
OUT=$("$REPO/tools/hearthstead-qa" gate 2>&1)
RC=$?
if [ $RC -ne 0 ]; then
    echo "Hearthstead QA gate is RED — completion/stop is blocked (qa/PROTOCOL.md)." >&2
    echo "$OUT" >&2
    echo "Fix the failures and re-run the named command, or document a genuine external blocker in qa/reports/BLOCKED (failing command, error evidence, work completed, exact resume action) to stop with NOT READY — BLOCKED." >&2
    exit 2
fi
exit 0
