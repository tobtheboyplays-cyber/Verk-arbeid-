# Bug hunter state (paused 26 Sep 08:16)

Full history: `plan/BUGHUNT-LOG.md` (BH-01 .. BH-27, J-02/04/06/08, T14).
Build dir: `build-agent-bughunt-b` (one build at a time; check load.ps1 SEPARATELY first, build only at RAM <= 90%; `-Dorg.gradle.daemon.idletimeout=300000`).
Last evidence: tree compiles 08:14, JUnit 874/0/0.
No background watchers are running.

## Waiting on the captain's re-runs (next GameTest window)
| Batch | What should now pass | Why |
|---|---|---|
| (GREEN in W9) `miner_drops`, `bughunt_miner_diag` | the Miner banks COBBLESTONE / RAW_IRON; the diag reports `OK ... canUse=true` | BH-25: the fixture left stone at rel y=1; plankFloor now clears y=1..4 |
| `bughunt_roles_healer_alarm` | "keeps going while nothing changes" | the NoAI spearman is set onGround before start() (it used to report "path null") |
| `battle_roles_mage_frost` | frost released once | the gametest-fixer changed BattleRoleGameTests to a base + t schedule |
| `bughunt_roles_rune_supply` | exhausted mage still fights | the nested schedule is now runAfterDelay |
| `bughunt_parley_switch` | duel control step | W9: failed because the config had the parley off; the test now forces it ON as control (08:28). `bughunt_event_switch_off`: check it in W9 |
| `first_raid_readiness*`, `equipment` | arm_the_watch unlocks; the guard-watch credit counts | BH-24 (sameNeed) — confirm green |
| `bughunt_event_*`, `bughunt_item_conservation`, `bughunt_roles_duty` | still green | regression guard |

What to watch in every run log:
- One `HEARTHSTEAD_GAMETEST_ISOLATION batch reset` line per batch (BH-23). If they're missing, the install hook broke.
- Any new failures that aren't order-dependent. W8a had 4 failures total, all listed above.

## Open items
1. **Absolute `runAtTickTime` flake risk** (routed to the lead). 113 schedules can fire early when a test's first tick starts late. The worst file is GuardMeleeContactGameTests (48). The fix is to offset by `helper.getTick()`.
2. **Economy**: the Hearth lift isn't bundled (1 item per 40 ticks); I sent this to the economy lane (a60cce1309921f3b3), and it's their call.
3. **BH-19** (the raider's stolen loot is returned on death): the balance lane fixed it, but it has only been compiled, not GameTested.
4. **NETWORK_PROTOCOL bump** at freeze, done together with the captain.
5. Minor, not fixed: CourierWorkGoal RESERVATIONS never prunes expired entries (small memory growth). `restockJobIsHeld` is test-only.

## Files I own or touched today (re-read before editing; line endings in brackets)
- New: `util/ItemSpill.java` (LF), `network/PayloadSend.java`, `gametest/GameTestIsolation.java` (LF), `gametest/BughuntGameTests.java` (CRLF), `conversation/parley/RaidParleySwitchGameTests.java` (LF), JUnit `settlement/equipment/EquipmentRequirementNeedTest.java`.
- Edited: `WorldEventDirector` (LF), `WorldEventConfig` (LF), `FieldFoxEvent` (LF), `RaidParley` (LF), `DevelopmentQuests` (CRLF), `EquipmentRequirement` (LF), `BuilderNetwork` (CRLF), `MinerDropsGameTests` (CRLF), `HaulGearGameTests` (LF), and the role goals (RestAtNight / GuardRespondToAlert / GuardPatrol / RuneMage / HealerMedic).

## Resume checklist
1. Read the captain's latest results. Then check the tables above.
2. Run clientonly.py on a fresh build dir. Expect only the 12 known lambda-gated MODCLIENT flags.
3. Continue the risk-based sweep: new lanes' files first (`find src -mmin -60`).
