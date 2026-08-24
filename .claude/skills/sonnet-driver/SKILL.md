---
name: sonnet-driver
description: The standing operating system for running this repository with Sonnet 5 as the main session model. Use at the START of every session and whenever unsure what to do next — it defines session recovery, the build loop, cheap-vs-full testing cadence, when and how to escalate to Opus gates, user communication, and the hard nevers. Fable is NOT required to run this repository.
---

# Sonnet 5 driver — how to run Hearthstead without Fable

This repository is designed so a **Sonnet 5 main session drives everything**.
Opus is a scarce gate reviewer; Fable is dormant (budget 0). Everything a
session needs is written down — never guess, always read.

## Session start (every session, in order — ~2 minutes)

1. `.claude/WORK_STATE.md` — the current slice, its acceptance criteria, and
   the recorded **next action**. Continue that action.
2. `qa/QUICKSTART.md` — the whole testing workflow on one page.
3. `tools/hearthstead-qa gate` — instant read of the current quality state.
4. After a restart or compaction, recover from `docs/project/` (DECISIONS.md,
   KNOWN_FAILURES.md, QUALITY_STANDARD.md) rather than from memory.

## The build loop

- Implement directly, or fan out **parallel `sonnet-builder` agents** for
  independent pieces per the ownership rules in the `premium-build-loop`
  skill: a sequential seam lands shared files first, every worker owns a
  disjoint file set, cross-file constants/keys are fixed in the prompts,
  integration is sequential.
- After every change package: `tools/hearthstead-qa quick` (~15 s). Fix what
  it finds before moving on. Batch related changes; don't run heavy suites
  per small edit — `changed` tells you which heavy suites your diff maps to.
- Feature complete → prove it in-game: `tools/hearthstead-qa live start`,
  drive the scenario, `shot`/`film` the evidence.
- Slice end → `tools/hearthstead-qa full` twice back-to-back (green_streak
  ≥ 2, one fingerprint, hands off the tree while it runs) → only then
  request the RELEASE_GATE.

## Escalating to Opus (the ONLY Opus uses; models are pinned in .claude/agents/)

| situation | action |
|---|---|
| new slice about to start | `Agent(subagent_type: "opus-planner")` — one PLAN_GATE |
| candidate finished, full×2 green | `Agent(subagent_type: "opus-quality-gate")` — one RELEASE_GATE |
| RELEASE_GATE said REVISE | fix ALL findings in one coordinated round, then ONE short re-review |
| same documented defect survived 3 real, different attempts | BLOCKER_GATE (same reviewer agent, scoped to that defect) |

Normal max two Opus calls per slice, absolute max three. Ordinary compile,
test, import and API errors are Sonnet's job — investigate, never escalate.
Spawning these agents from a Sonnet session still gets Opus: the model is in
the agent definition, not the session.

## User communication

- The user writes Norwegian — answer in Norwegian, plainly, no jargon.
- Status only at milestones: slice done, important test green, real blocker,
  gate starting/finished. Not a running commentary.
- **Every finished task/slice: a short in-game video** (`live` + `film`) with
  a 3–5 line summary, sent as a file. The user follows progress by video.

## Git

Work on the designated `claude/...` branch (see `.claude/WORK_STATE.md`),
commit with clear messages, push with `git push -u origin <branch>`, keep the
existing draft PR updated. Never force-push someone else's history.

## Never

- Fable-model agents (`fable-escalation` stays dormant) unless the user
  explicitly asks in this session.
- Two QA suites at the same time; editing/compiling source while a `full`
  run executes (both produce false results — see qa/QUICKSTART.md).
- `gradlew runGameTestServer|runServer|runClient` directly.
- INV-10: deleting/skipping/loosening tests, inflating timeouts without
  diagnosis, silencing exceptions, editing reports. Spec genuinely wrong →
  record a correction in `hearthstead-neoforge/docs/HEARTHSTEAD_QUALITY_LEDGER.md`.
- Claiming completion without `gate` PASS at green_streak ≥ 2. Compilation
  is not completion; a green test is not visual quality. Only **LOCKED**
  (docs/project/QUALITY_STANDARD.md) means finished.
