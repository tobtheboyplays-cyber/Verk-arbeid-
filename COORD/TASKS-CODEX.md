# Codex tasks (assigned by Claude). Do them in order. Tick status here; report in to-claude.md.

## T1 — Nailing for wooden repairs  · status: CODEX DONE (Compiled + JUnit; GameTest pending Claude)
Owner asked for "spikking". Post-raid repairs (`entity/ai/RepairWorkGoal.java`) always use
`SettlerActivity.WORK_CHISEL` (~line 266), even on wood.
- Add `SettlerActivity.WORK_NAIL` — APPEND only, never renumber (ids are saved + synced; check how).
- In `RepairWorkGoal`, pick the activity by the material of the block being restored: wood-like
  (use tags: `BlockTags.PLANKS`, `LOGS`, `WOODEN_DOORS`, `WOODEN_TRAPDOORS`, `WOODEN_FENCES`,
  `WOODEN_STAIRS`, `WOODEN_SLABS`, fence gates) → `WORK_NAIL`; everything else → `WORK_CHISEL`.
  Timing and behaviour otherwise identical.
- Status text for the settler sheet: "Nailing boards back" (targeted edit in `en_us.json`; find how
  the chisel repair status is keyed and mirror it).
- Animation/sound hookup is NOT yours: Claude's engine agent maps `WORK_NAIL` → clip `NAIL_HAMMER`
  (looping 40 ticks, taps at 8/16/24) and the sound agent adds `nail_tap`. Just tell Claude when the
  activity exists (name + id).
- Write a GameTest (new file, e.g. `gametest/RepairNailGameTests.java`): wooden scar → WORK_NAIL,
  stone scar → WORK_CHISEL, both restore exactly once. Do NOT run it; Claude runs GameTests.
- Allowed files: `RepairWorkGoal.java`, `SettlerActivity.java` (append), `en_us.json` (targeted),
  the new GameTest file.
- Acceptance: compiles, JUnit green, existing `RepairGameTests` logic unchanged.

## T2 — JUnit coverage for new pure logic · status: CODEX DONE (624 JUnit, 0 failures/errors/skipped)
New systems added this week have thin unit coverage. Add NEW test files only (`src/test/**`):
`SkillLevels` (curve, caps, describeBonuses start levels), `DevelopmentBonuses`, `PostRaidUpgrade`
(wire ids stable, costs), `CraftingOrderBook` (state transitions, exact-once), `LifeNeed`,
`HearthsteadDayLength` edge cases, pickup notice queue merging (`client/pickup/PickupNoticeQueue`).
Don't change production code; if you find a bug, report it in to-claude.md with a failing test
marked `@Disabled("bug: …")`.
Acceptance: `./gradlew test` green with your build dir; list tests added.

## T3 — Exact-once review (read-only) · status: CODEX DONE (static review reported; T3b pending Claude)
Review these for item duplication/loss, desync and co-op permission holes; write findings (file:line,
scenario, severity, suggested fix) to to-claude.md. Do not edit them.
`settlement/work/CraftingOrderService`, `network/DevelopmentNetwork` (BUY_UPGRADE),
`settlement/development/PostRaidUpgrade*`, `client/pickup/*` + `PickupNoticeNetwork`,
`event/GoldCoinTrades`, `settlement/request/RequestLedger*` quarantine repair (new today),
`entity/SettlerEntity` death drops (offhand + craftOutputEscrow).

## T4 — (later, ask before starting) Xaero minimap/world map optional integration
Show settlers, buildings and raid alerts on Xaero's maps as an OPTIONAL compat (no hard dependency).
Needs `build.gradle` change → propose the plan in to-claude.md first.
