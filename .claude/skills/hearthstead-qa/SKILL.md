---
name: hearthstead-qa
description: Mandatory QA workflow for the Hearthstead mod. Use for ANY task touching testing, debugging, verification, completion claims, settler/guard/NPC AI behavior, raids, logistics/couriers, persistence/SavedData, networking, UI/screens, animation, assets, or performance in hearthstead-neoforge/. Routes all test execution through tools/hearthstead-qa and enforces the evidence gate.
---

# Hearthstead QA workflow

All testing flows through **`tools/hearthstead-qa`** (spec:
`qa/PROTOCOL.md`). Direct `gradlew runGameTestServer|runClient|runServer`
invocations are blocked by hooks — and would produce no evidence manifest
anyway.

## The mandatory loop: diagnose → reproduce → repair → regression → gate

1. **Diagnose from evidence, never from intent.** Read the latest manifest
   (`tools/hearthstead-qa status`), the suite logs and failure files under
   `qa/reports/artifacts/<ts>/`, decision traces (`trace.jsonl` +
   `behavior-analysis.txt`), and screenshots. For a recorded failure id:
   `tools/hearthstead-qa reproduce FB-xxxxxxxxxx`.
2. **Reproduce before fixing.** A failure you cannot reproduce is not
   understood; rerun the owning suite (`behavior`, `gametest`, `dedicated`,
   `performance`, `client`).
3. **Repair the root cause.** Minimal, robust fixes. Never weaken a test,
   loosen an assertion, inflate a timeout, or silence an exception to get
   green (PROTOCOL INV-10). Spec conflicts → record a specification
   correction in `hearthstead-neoforge/docs/HEARTHSTEAD_QUALITY_LEDGER.md`.
4. **Regression-protect.** Extend the GameTest suite / trace detectors so
   the failure class stays caught.
5. **Gate.** `tools/hearthstead-qa changed` shows which suites the edits
   require; run those focused suites while iterating, then
   `tools/hearthstead-qa full`. Completion claims require the full gate to
   PASS on the current fingerprint with **green_streak ≥ 2** — verify with
   `tools/hearthstead-qa gate`.

## Commands

| command | purpose |
|---|---|
| `tools/hearthstead-qa doctor` | environment sanity |
| `tools/hearthstead-qa changed` | changed files → required suites |
| `tools/hearthstead-qa gametest` | headless GameTest arenas |
| `tools/hearthstead-qa behavior` | gametests + decision traces + thrash/stuck/starvation detectors |
| `tools/hearthstead-qa dedicated` | real NeoForge server E2E incl. restart persistence |
| `tools/hearthstead-qa performance` | ~27 settlers, MSPT budget via /tick query |
| `tools/hearthstead-qa client` | real client boot under Xvfb + screenshot |
| `tools/hearthstead-qa visual` | screenshot presence/inspection gate |
| `tools/hearthstead-qa full` | everything + manifest (completion evidence) |
| `tools/hearthstead-qa gate` | fast freshness/completeness check (no MC launch) |
| `tools/hearthstead-qa reproduce <id>` | replay a recorded failure |
| `tools/hearthstead-qa status` | latest manifest |

Artifacts: `qa/reports/latest.json` (current manifest),
`qa/reports/artifacts/<timestamp>/` (logs, traces, screenshots, failures).
Inspect them — do not summarize what the code *should* do.

## Hard product invariants (never "fix" these away)

Room detection stays automatic (a plaque may present a building, never gate
its registration); settlers never build autonomously; item
conservation in logistics; budgeted scans; UUID-idempotent settler records;
no client classes on the server; guards protect civilians first; loss-free
persistence. Full list: `qa/PROTOCOL.md`.
