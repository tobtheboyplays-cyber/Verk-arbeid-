# Next action

One action. After a restart or a compaction, start exactly here — do not redo
proven work, and do not start a different slice because it looks easier.

## Do this

Run the `premium-build-loop` skill for slice **HARNESS-1: make in-game testing
work end to end.**

Phase 0 is already done and is captured in `CURRENT_STATE.md`,
`KNOWN_FAILURES.md` and `DECISIONS.md` — read those instead of re-deriving
them.

Begin at **Phase 1 (baseline)**:

```bash
cd /home/user/Verk-arbeid-
git status                      # expect clean; branch claude/hearthstead-settlement-mod-vbdb9n
cd hearthstead-neoforge && ./gradlew build -q
cd .. && tools/hearthstead-qa doctor
```

Then **Phase 3**: invoke `opus-planner` for HARNESS-1. Its brief must include:

- The acceptance criteria in `QA_MATRIX.md` under `HARNESS-*`.
- The already-fixed defects in `KF-006` (game dir is `run/` not `run/client`;
  server needs `nogui`; capture the game window not the root window; no
  opening `Escape` in a scenario) — so they are not rediscovered.
- The open question in `KF-006`: one run reached the world and a later one did
  not, and the cause was never established.
- The honest design question: can `live.sh`'s persistent session work at all,
  given that each shell invocation is a separate process and the X display,
  the server, its FIFO writer and the client must all survive between them? If
  it cannot, plan the design that can.
- The constraint that the `/tmp` server install does not survive a new
  container, so the harness must be able to rebuild it.

Then Phase 4 (`sonnet-builder`), Phase 5 (automated verification), Phases 6–7
(**two** clean `minecraft-qa` passes), Phase 8 (fresh `opus-quality-gate`),
Phase 9 (verdict). Update `CURRENT_STATE.md` after each phase.

## Explicitly not now

- The plaque slice (`KF-001`, `KF-004`, `KF-005`, and the D-006 reversal to a
  separate Build Plan item). It is the next slice, not this one — and it
  cannot be *proven* until the harness works, which is precisely why the owner
  chose this order.
- `KF-002` and `KF-003` (dedicated server and performance regressions). They
  are real and recorded; they belong to the plaque slice's correction, unless
  the baseline shows they also block launching the game — in which case they
  become part of HARNESS-1 and must be re-scoped deliberately, not absorbed
  silently.
- `KF-007` (`gen_settler.py` reproducibility), settler appearance, and the
  animation catalogue. All later slices.

## Standing constraints

- Do not push, publish, invoke Fable, or do anything destructive to source
  control without explicit authorisation. Local builds, tests, game launches
  and safe edits are authorised.
- `FABLE_INVOCATIONS_ALLOWED = 0`.
- Nothing is "done" before **LOCKED** (see `QUALITY_STANDARD.md`).
