#!/usr/bin/env python3
"""Self-test for qa/hooks/bash_guard_filter.py.

Run directly: python3 qa/hooks/test_bash_guard.py
Also reachable as: qa/hooks/bash_guard.sh --selftest
Wired into `tools/hearthstead-qa doctor` so a regression here fails fast,
without launching Minecraft.
"""
import subprocess
import sys
from pathlib import Path

FILTER = Path(__file__).with_name("bash_guard_filter.py")

ALLOW = [
    # The exact false positive the coordinator hit: a commit message
    # (delivered via heredoc) that merely *describes* a failed run.
    (
        "commit-message-heredoc",
        """git commit -m "$(cat <<'EOF'\nFix: the ./gradlew runClient task failed under the client build.\n\nCo-Authored-By: Claude <noreply@anthropic.com>\nEOF\n)\"""",
    ),
    ("echo-prose", 'echo "note: gradlew runClient is blocked by policy"'),
    ("comment-line", "# gradlew runClient historically failed here"),
    ("printf-prose", "printf 'we saw ./gradlew runServer fail\\n'"),
    (
        "double-quoted-mention",
        'git log --oneline | grep "gradlew runGameTestServer regression"',
    ),
]

BLOCK = [
    ("direct-client", "./gradlew runClient"),
    (
        "direct-gametest-server-chained",
        "cd hearthstead-neoforge && ./gradlew runGameTestServer",
    ),
    ("extra-whitespace", "./gradlew   runGameTestClient"),
    ("dangerous-bash-c", "bash -c './gradlew runClient'"),
    ("dangerous-eval", 'eval "./gradlew runServer"'),
    ("with-display-env", 'DISPLAY=:98 timeout 420 ./gradlew runClient > out.log 2>&1'),
]


def run(cmd: str) -> str:
    result = subprocess.run(
        [sys.executable, str(FILTER), cmd],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def main() -> int:
    failures = []
    for name, cmd in ALLOW:
        verdict = run(cmd)
        if verdict != "ALLOW":
            failures.append(f"FAIL (expected ALLOW, got {verdict}): {name}")
    for name, cmd in BLOCK:
        verdict = run(cmd)
        if verdict != "BLOCK":
            failures.append(f"FAIL (expected BLOCK, got {verdict}): {name}")

    total = len(ALLOW) + len(BLOCK)
    if failures:
        print(f"bash_guard selftest: {len(failures)}/{total} FAILED")
        for f in failures:
            print(" -", f)
        return 1
    print(f"bash_guard selftest: {total}/{total} PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
