# Next action

One action. After a restart or a compaction, start exactly here — do not redo
proven work, and do not start a different slice because it looks easier.

## Do this

Finish the **HARNESS-1 correction round**. RELEASE_GATE returned REVISE with 14
findings; all 14 are fixed in code (see `.claude/WORK_STATE.md` for each fix and
its evidence path). What remains is *evidence*, not code — every item below is a
run that must land at the current source fingerprint and store its verdict.

Run these **one at a time, never concurrently**: they all launch a real client
and a real server, and a concurrent client cold-start's broad `pkill` has
already killed another run's client once (that was the N4 FAIL in
`negative/20260824T003809Z`, not a defect).

```bash
cd /home/user/Verk-arbeid-
tools/hearthstead-qa reap check      # before and after every item below
tools/hearthstead-qa negative all    # AC-8 — must store 4/4 verdicts in ONE invocation
tools/hearthstead-qa provision       # AC-10 — 0 stored runs so far
tools/hearthstead-qa playtest        # twice, cold starts
tools/hearthstead-qa live start ; ... ; tools/hearthstead-qa live stop   # twice
```

For `live`, also capture the **finding-3 proof**: a static camera (do NOT pass
the opt-in `pan` argument) pointed at a settler, `median_mad > 2.0` coming from
the settler's own motion, and a contact sheet in which a pose change is
visible. A pan makes `motion_ok` pass unconditionally and proves nothing.

Then, and only then, one **short** `opus-quality-gate` re-review scoped to the
changed areas. That is Opus call 3 — the absolute maximum under the resource
governor. Do not run `full` during the correction round; `full` is red on the
three known plaque failures by design and would only re-prove that.

## Then: SLICE PLAQUE-1

The plan is already written and needs no second PLAN_GATE:
**`docs/project/PLAN_PLAQUE-1.md`** (state machine, W1–W9 with acceptance
criteria, test pyramid, risks). It closes KF-001, KF-004 and KF-005, and gives
the plaque the survival recipe it does not currently have.

Hand it straight to `sonnet-builder`. Slice order after that is unchanged:
PLAQUE-1 → VISUAL-1 → ANIM-1.

## Explicitly not now

- `KF-007` (`gen_settler.py` reproducibility) — it is VISUAL-1's first task,
  because regenerating skins before that fix produces bytes nobody can
  reproduce.
- The animation catalogue (ANIM-1), including the two verified defects already
  recorded: FARM's sound fires on `%12==3` against a 1.5s loop (contract is
  `%30==12`), and CHOP/MELEE animate no leg bones.
- `KF-002`/`KF-003` — closed. Both were harness port contention, not mod
  defects, and both re-measured PASS twice after isolation.

## Standing constraints

- Do not push, publish, invoke Fable, or do anything destructive to source
  control without explicit authorisation. Local builds, tests, game launches
  and safe edits are authorised.
- `FABLE_INVOCATIONS_ALLOWED = 0`.
- Nothing is "done" before **LOCKED** (see `QUALITY_STANDARD.md`).
