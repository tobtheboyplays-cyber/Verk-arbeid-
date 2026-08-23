---
name: premium-build-loop
description: Mandatory three-phase quality pipeline for non-trivial work in this repository — Opus 5 plans, Sonnet 5 builds and verifies, a fresh Opus 5 reviews and returns PASS/REVISE/BLOCKED. Use for ANY implementation, debugging, behaviour, UI, animation, asset-integration or feature task before editing begins. Not for simple questions, explanations, or read-only inspection.
---

# Premium build loop

Three agents, strictly in sequence, never in parallel:

| phase | agent | model | may edit? |
|---|---|---|---|
| 1 plan | `opus-planner` | claude-opus-5 | no |
| 2 build | `sonnet-builder` | claude-sonnet-5 | yes |
| 3 review | `opus-quality-gate` | claude-opus-5 | no |

The separation is the whole value: the agent that wrote the code is never the
agent that decides it is good.

## When it applies

**Use it** for implementation, debugging, behaviour changes, UI, animation,
asset integration, and features — anything non-trivial that changes the
repository.

**Skip it** for questions, explanations, and read-only inspection. Skip it
when the user explicitly says to skip the quality pipeline.

Judge triviality **before** starting. Once implementation has begun, a task
that turns out to be easy does not become exempt — that is precisely when a
skipped review costs the most.

## The loop

1. **Preserve the original task verbatim.** Every phase receives the user's
   own words, not your paraphrase of them. Requirements erode through
   retelling.
2. **Plan.** Run `opus-planner`. Wait for it.
3. If it returns `BLOCKED_FOR_REQUIREMENT`, ask the user **one** focused
   question — only when a genuine product decision cannot be settled from the
   repository. Then re-plan.
4. **Build.** Run `sonnet-builder` with: the original request, the acceptance
   criteria, the repository state, and the complete plan.
5. Wait for implementation **and** its verification.
6. **Review.** Run a **fresh** `opus-quality-gate`. Never reuse a reviewer
   instance between rounds — a reviewer that already reasoned its way to a
   conclusion is anchored to it.
7. **On PASS:** stop. Make no further edits. Report completion with the
   verification evidence.
8. **On REVISE:** resume the **same** `sonnet-builder` agent (via SendMessage
   with its agent id) and give it the complete numbered defect report. Resuming
   preserves the context the builder already holds; a new builder would
   re-derive it and often re-introduce the defect.
9. After corrections and re-testing, go to step 6 with a **new** reviewer.
10. **At most three revision rounds.**
11. If three rounds do not reach PASS, return **BLOCKED** with the unresolved
    defects and the evidence. Do not claim success.

## Hard rules

- Never run dependent phases in parallel.
- Never let the builder approve its own work — only the reviewer returns PASS.
- Never skip a phase because the task started to look easy.
- Never resume a reviewer; never discard a builder.

## Reporting

Report the verdict, the evidence behind it, and what remains. When the outcome
is BLOCKED, say plainly what could not be established and why — an honest
BLOCKED is a useful result, a hopeful PASS is not.
