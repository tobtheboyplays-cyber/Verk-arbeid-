#!/usr/bin/env python3
"""Decide whether a shell command string is a real invocation of a blocked
gradlew task (runGameTestServer, runClient, runServer, runGameTestClient),
as opposed to the same words appearing inside a heredoc body, a quoted
argument (echo/printf/git commit -m), or a comment.

Prints BLOCK or ALLOW to stdout. Used by qa/hooks/bash_guard.sh.

Design (see qa/hooks/test_bash_guard.py for the fixtures this must satisfy):

  1. Safety fallback: if the command launches a shell interpreter with a
     quoted script (`bash -c '...'`, `sh -c "..."`, `eval "..."`), do NOT
     strip anything — quoted/heredoc content there can actually execute, so
     we fall back to the old raw whole-string match. This must never get
     more permissive than before for that shape of command.
  2. Otherwise, strip heredoc bodies, quoted string contents, and comments
     (in that order — heredoc delimiters use quote chars, so heredocs must
     be stripped before generic quote-stripping), then match the cleaned
     text for an actual `gradlew ... <task>` invocation.
"""
import re
import sys

BLOCKED_TASKS = r"(runGameTestServer|runClient|runServer|runGameTestClient)"
INVOCATION_RE = re.compile(r"gradlew[^|;&\n]*" + BLOCKED_TASKS)

# Shell launchers that can execute a quoted string as a new command line.
# If one of these appears with a -c style flag (or eval), stripping quotes
# would hide a real invocation inside the quotes, so we skip stripping.
DANGEROUS_LAUNCHER_RE = re.compile(
    r"\b(bash|sh|zsh|dash)\s+(-\S*\s+)*-c\b|\beval\b"
)

HEREDOC_RE = re.compile(
    r"<<-?\s*(['\"]?)(\w+)\1[^\n]*\n(?:.*?\n)??\2\b", re.DOTALL
)


def strip_heredocs(cmd: str) -> str:
    prev = None
    while prev != cmd:
        prev = cmd
        cmd = HEREDOC_RE.sub(" ", cmd)
    return cmd


def strip_quotes(cmd: str) -> str:
    cmd = re.sub(r"'[^']*'", "''", cmd)
    cmd = re.sub(r'"(?:[^"\\]|\\.)*"', '""', cmd)
    return cmd


def strip_comments(cmd: str) -> str:
    # Safe only after quotes are stripped, so a '#' inside a quoted string
    # (already replaced) can't be mistaken for a comment marker.
    return re.sub(r"(^|\s)#[^\n]*", r"\1", cmd)


def is_blocked(cmd: str) -> bool:
    if not cmd:
        return False
    if DANGEROUS_LAUNCHER_RE.search(cmd):
        # Fall back to the original strict behaviour: do not strip anything,
        # since the quoted/heredoc content here is live shell input.
        return bool(INVOCATION_RE.search(cmd))
    cleaned = strip_heredocs(cmd)
    cleaned = strip_quotes(cleaned)
    cleaned = strip_comments(cleaned)
    return bool(INVOCATION_RE.search(cleaned))


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    print("BLOCK" if is_blocked(command) else "ALLOW")
