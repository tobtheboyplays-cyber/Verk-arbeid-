---
name: premium-build-loop
description: The mandatory vertical-slice loop for this repository — recover state, baseline, spec, Opus plan, Sonnet build, automated verification, two clean Minecraft QA passes, fresh Opus review, verdict handling. Use for ANY implementation, debugging, behaviour, AI, UI, animation, asset-integration or feature work before editing begins. Not for questions, explanations, or read-only inspection.
---

# Premium build loop

One vertical slice at a time, carried from specification to LOCKED. The agent
that writes the code is never the agent that decides it is good, and nothing
is called finished on the strength of a summary.

| phase | who | may edit? |
|---|---|---|
| 0 recover · 1 baseline · 2 spec | you (orchestrator) | yes |
| 3 plan | `opus-planner` (claude-opus-5) | no |
| 4 build | `sonnet-builder` (claude-sonnet-5) | yes |
| 5 automated verification | `sonnet-builder` | yes |
| 6–7 two clean runtime passes | `minecraft-qa` (claude-sonnet-5) | harness only |
| 8 review | `opus-quality-gate` (claude-opus-5) | no |
| escalation | `fable-escalation` (claude-fable-5) | **dormant, budget 0** |

## When it applies

Implementation, debugging, behaviour changes, AI, UI, animation, asset
integration, features. **Not** questions, explanations, or read-only
inspection. Judge triviality *before* starting: once implementation has begun,
a task that turns out easy is exactly when a skipped review costs most.

## The loop

**Phase 0 — recover context.** Read the user's request, `CLAUDE.md`, and
`docs/project/`: `CURRENT_STATE.md`, `DECISIONS.md`, `KNOWN_FAILURES.md`,
`QUALITY_STANDARD.md`, `QA_MATRIX.md`, `NEXT_ACTION.md`. Read `git status` and
`git diff`. **Never assume the tree is clean**, and preserve unrelated changes.

**Phase 1 — baseline.** Build, run the existing tests, and confirm the QA
harness can still launch, act, capture and exit. Record pre-existing failures
*separately* — attributing an inherited failure to the new work wastes a whole
correction cycle.

**Phase 2 — specification.** Write `REQ-*`, `AC-*` and scenario ids
(`PLAQUE-*`, `CITIZEN-*`, `AI-*`, `HARNESS-*`). Every criterion states an
observable completion condition.

**Phase 3 — plan.** `opus-planner`. Wait for it; never implement in parallel
with planning. On `BLOCKED_FOR_REQUIREMENT`, ask the user **one** focused
question — only for a genuine product decision. Otherwise take the most
conservative interpretation consistent with the architecture and record it in
`DECISIONS.md`.

**Phase 4 — build.** `sonnet-builder`, with the verbatim request, the
acceptance criteria, the repository state and the whole plan. This slice only.

**Phase 5 — automated verification.** Compilation, formatting, static checks,
GameTests, data/resource/model/texture/translation validation, regression
tests — through `tools/hearthstead-qa`. **Never** delete a failing test,
weaken an expectation, silence an error, or disable the harness to reach
green.

**Phase 6 — runtime pass one.** `minecraft-qa`, from a clean launch. Evidence
to `artifacts/qa/`. Shut down completely.

**Phase 7 — runtime pass two.** A second clean launch. Two consecutive clean
passes are required, and FLAKY is not PASS.

**Phase 8 — review.** A **fresh** `opus-quality-gate`. Never reuse a reviewer
between rounds; never let the builder review itself.

**Phase 9 — verdict.**
- **PASS** → update `docs/project/`, mark `OPUS_APPROVED`, and mark `LOCKED`
  only when every definition-of-done item in `QUALITY_STANDARD.md` is met.
  Then stop editing that slice.
- **REVISE** → resume the **same** `sonnet-builder` (SendMessage to its agent
  id) with the complete numbered findings. Fix every critical and high item,
  re-run phase 5, re-run **both** runtime passes, then a **fresh** reviewer.
- **BLOCKED** → preserve progress, record the blocker, reproduction and
  evidence, and stop claiming completion.

If the same root problem survives **two** full correction cycles, prepare the
Fable escalation packet, show the user why, and **ask permission**. Do not
invoke Fable. Budget is zero.

## Hard rules

- Dependent phases never run in parallel; independent read-only investigation
  may.
- Never two code-writing agents on the same files.
- The builder never approves its own work; QA never edits production code to
  pass.
- Compilation is not completion; a green test is not visual quality; a working
  runtime is not sound architecture.
- Do not push, publish, release, spend money, invoke Fable, or perform
  destructive source-control operations without explicit user authorisation.
  Local builds, tests, game launches, evidence collection and safe edits are
  authorised within the current task.

## Reporting

After baseline, plan, implementation, QA, review, locking, and any blocker:
say what changed, what was tested, what evidence proves it, what is still
uncertain, and what happens next. Label claims **PROVEN / LIKELY / ASSUMED /
UNKNOWN / BLOCKED**. Keep logs and screenshots out of the conversation — cite
their paths. After a major milestone, mark `HUMAN_PLAYTEST_RECOMMENDED`:
automation cannot prove that the mod feels alive.
