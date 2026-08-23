---
name: premium-build-loop
description: The mandatory delivery loop for this repository under the resource governor — one Opus PLAN_GATE, then a single continuous Sonnet worker implementing, fixing and testing to a release candidate, then one Opus RELEASE_GATE. Use for ANY implementation, debugging, behaviour, AI, UI, animation, asset-integration or feature work. Not for questions, explanations, or read-only inspection.
---

# Premium build loop (governed)

Maximum quality per token. The saving comes from **fewer model switches, less
context and batched fixes** — never from skipping tests or acceptance criteria.

## Model policy

**Sonnet 5 is the continuous worker.** It owns implementation, coding, fixing,
refactoring, building, unit tests, GameTests, Minecraft testing, UI, animation
and sound testing, documentation, and ordinary technical decisions. Medium
effort by default; low effort for mechanical work (search, moves, formatting,
trivial fixes).

**Opus 5 is used only at gates:**

| gate | when | what |
|---|---|---|
| PLAN_GATE | once, before implementation | ≤1000-word plan: goal, acceptance criteria, key technical decisions, likely failure areas, implementation order, test strategy. No code, no full-repo exploration. |
| RELEASE_GATE | once, on the finished candidate | Find real defects and regressions; rank BLOCKER / HIGH / MEDIUM / LOW; short concrete fix instructions. No rewrites, no optional polish. |
| BLOCKER_GATE | only if the *same documented* defect survives **three real and different** Sonnet attempts | Adjudicate that one defect. |

Normal maximum **two** Opus calls per task; absolute maximum **three**.

Never bounce between Opus and Sonnet per phase, per test, or per failure.
Sonnet finishes the entire implement-and-fix loop before Opus is involved
again.

**Fable is dormant.** It is not a watcher, coordinator or periodic checker, and
is invoked only if the user explicitly asks. Budget 0.

**One worker.** No agent teams or parallel model instances by default. Helper
agents must be short, tightly scoped, and finished immediately.

## Sonnet's loop (no model switching inside it)

1. Read only the files the next sub-goal needs.
2. Implement one coherent change package.
3. Run the cheapest relevant verification first.
4. Analyse the concrete failures.
5. Fix all related failures **together**.
6. Re-run.
7. Repeat until the sub-goal is proven done.

Never escalate ordinary compiler, test, import or API errors — investigate
them. Never repeat a failed fix: record what was tried, why it failed, and
what the next attempt does differently.

## Test pyramid

**Level A — after code changes.** Compile, static checks, unit tests, relevant
GameTests, resource and data validation.

**Level B — when a feature is complete.** Launch Minecraft via the established
self-play harness (`qa/scripts/playtest.sh`, `qa/scripts/live.sh`), exercise
the changed feature, check real behaviour, UI, animation, sound and error
states. Only the screenshots and logs actually needed.

**Level C — before RELEASE_GATE.** Full play test: coherent user flow,
regression, UI at relevant resolutions, AI behaviour, animation, textures,
sound, save/load, multiplayer where affected, performance, error logs.

Do not run a full visual play test after every small text, config or code
change — batch related changes and test them as one functional package.
**A successful build is never a successful play test.** All test execution
routes through `tools/hearthstead-qa`.

## Context budget

Targeted search over whole-file reads; never re-read an unchanged file without
a reason; prefer `git diff` and symbol navigation. Surface test failures,
stack traces and the relevant log lines — not whole build logs (cap ordinary
output at ~100 relevant lines). Don't restate the plan or the requirements in
every status. No model calls as a waiting mechanism or a periodic "are we done
yet" check.

Keep `.claude/WORK_STATE.md` current and **compressed** (~120 lines): goal,
acceptance criteria, key decisions, changed files, passing tests, failing
tests, known problems, next action. It is a working file, not a diary.

## Handoff to RELEASE_GATE

A compact review package: the original goal; acceptance criteria; a short
architecture note; `git diff --stat`; the actual diff or the changed files;
test results; self-play results; known limitations; and the points Sonnet is
unsure about. Not the whole conversation, not all logs, not the repository.

If RELEASE_GATE fails: Sonnet receives **all** findings at once, fixes them in
one coordinated round, re-runs every affected test, and Opus gets **one** short
re-review of the changed areas only. After that single re-review Sonnet
continues alone on ordinary defects, unless a documented BLOCKER meets
BLOCKER_GATE.

## Status messages

Only on: a completed milestone, an important test passing, a real blocker,
RELEASE_GATE starting, and task completion. Not a running commentary of every
search, file or command.

## Quality rule

Low usage does not mean a lower bar. Nothing here permits skipping tests,
acceptance criteria, or real in-game verification. Compilation is not
completion; a green test is not visual quality; a working runtime is not sound
architecture. Only **LOCKED** (see `docs/project/QUALITY_STANDARD.md`) means
finished.
