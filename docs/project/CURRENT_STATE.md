# Current state

Updated: 2026-08-23, end of the long build session.
Read this first after a restart or a context compaction. It is the recovery
point — trust it and the repository over memory.

## Current vertical slice

**HARNESS-1 — in-game testing must work end to end.**

Chosen by the owner ("Prioritet nå fiks in game testing", then confirmed when
asked which slice to lock first). It is also what the operating contract's
own initial execution order requires: nothing later can be *proven* until the
QA agent can launch the game, enter the world, act, capture evidence and exit.

**Lifecycle state: SPEC_READY** (planning was in flight at session end).

## Latest proven build

`./gradlew build` → BUILD SUCCESSFUL, `build/libs/hearthstead-0.2.0.jar`.
Compilation is PROVEN. It is not completion.

## Latest QA results (run 20260823T204752Z, `tools/hearthstead-qa full`)

| suite | result |
|---|---|
| doctor | PASS |
| assets | **FAIL** — plaque lang keys missing (en_us + nb_no) |
| animation | PASS |
| build | PASS |
| gametest | **FAIL** — 5 of 15 |
| behavior | **FAIL** — consequence of the gametest failures |
| dedicated | **FAIL** — settlers did not spawn on the dedicated server |
| performance | **FAIL** — could not stand up 25+ settlers |
| client | PASS — real client boot under Xvfb, screenshot captured |
| visual | PASS — screenshots present (presence only; see QUALITY_STANDARD) |

**overall FAIL, green_streak 0.** All five failures are understood or have a
named next step; see `KNOWN_FAILURES.md`.

## Latest Opus verdict

None yet for this slice. No slice has ever reached OPUS_APPROVED or LOCKED
under this contract.

## Files currently being changed

None in flight — working tree was committed and pushed at session end
(`25e101a` on `claude/hearthstead-settlement-mod-vbdb9n`). The plaque feature
is committed but incomplete; see `KNOWN_FAILURES.md` before touching it.

## Known blockers

- The QA gate is RED and honestly recorded as such in `qa/reports/BLOCKED`.
  That file is deleted automatically by the next successful full run.
- The dedicated-server install used by the harness lives in `/tmp` and does
  **not** survive a new container. The harness must be able to rebuild it.

## Next exact action

See `NEXT_ACTION.md`.

## Fable

    FABLE_INVOCATIONS_ALLOWED = 0
    FABLE_INVOCATIONS_USED    = 0

Dormant. Configuration exists; that is not permission.
