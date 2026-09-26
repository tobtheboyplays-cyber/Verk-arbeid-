# Sunday gate — Bannerhold friend test

Maintained by the integration captain. Last update: **26 Sep 13:45** (W20 full suite).
Freeze: **Sunday morning**. After that, bug fixes only. Unstable features still ship, but each one needs a working off switch (owner decision, 02:00).

Evidence ladder: **I**mplemented, then **C**ompiled, then **J**Unit, then **G**ameTest, then **S**een in game.
Logs are in `C:\Users\tobia\Hearthstead-Claude\build-agent-integration\` (`gt-<window>.log`, `gt-<window>-triage.txt`). The scoreboard is in `plan/INTEGRATION-LOG.md`.

## Gate conditions (all must be true for the Sunday build)

| # | Condition | Status |
|---|---|---|
| 1 | Shared tree compiles (compileJava + JUnit green) | 11:37 (isolated build): 1029/1031. 2 animation-asset JUnit failures (brew_mash__v3 without a base clip; tavern clink point) routed to the anim lane. The tree goes red for minutes at a time while new lanes land. |
| 2 | Full GameTest suite green, or each failure explicitly waived below | (guard_critical_rest test semantics "route ends beside the Hearth" accepted by the lead at 08:12) **W20 (13:30): FULL SUITE, 1860 tests: 1818 pass, 42 fail, 0 crashes, 0 mixin errors.** Most failures are new features landing: event walk-out departures ×10, tech-gated crafting 4/6, open-air yard blueprints ×12, captain. **Blocker candidate:** raid_civilians_survive_far_side_raid, where 8 of 8 civilians die with a staffed guard post (balance lane). |
| 3 | No crash in a **2-hour** dedicated-server soak on the freeze candidate | **PASS on the 09:10 build**: captain1, 2 h 17 m, 0 crashes, 0 ERROR lines, 0 can't-keep-up. Repeat a shorter soak on the freeze candidate. **Real NeoForge 21.1.248 dedicated server (12:38): PASS.** Clean boot (0 class/Exception/ERROR). A client joins over the network, the mod UI works, disconnect and reconnect work. Still to do: the Banner screen, tech tree and a raid on the real server. |
| 4 | Stable TPS: MSPT mean <= 35 ms with ~40 settlers + raid + realm map open | **PASS (09:16)**: dedicated server, 34 settlers + a 9-raider band, MSPT mean ~3.6 ms over 5 min (samples 1.6–14.9 ms); one melee hitch with P99 370 ms. Map: 0.12–0.28 ms per 10-tick push. Re-measure on the freeze candidate. |
| 5 | Config defaults correct | Code defaults OK (see "Defaults" below) |
| 5b | **Pre-play step:** check the EFFECTIVE `world/serverconfig/hearthstead-server.toml` of the test world. Code defaults never overwrite values already in an existing toml; only missing keys get defaults. | TODO before play |
| 6 | Kill-switch for every big system, each with its real contract (table below) tested | DONE for all 11 switches (W8). Still untested: the off-transition for revive and finisher sessions already running |
| 7 | **Top blockers = 0**: no crash, no save corruption, no item duplication or loss | See Known issues. The 3 server-crash sends are fixed; nothing is open |
| 8 | Release hygiene: bump `ModBusEvents.NETWORK_PROTOCOL` (`event/ModBusEvents.java:43`, still "18"; the realm map, conversation and builder payload layouts have changed). Every friend runs the exact same jar (AutoModpack) and the BuildIdentity is checked. | TODO at freeze |

### Final freeze candidate — evidence (fill in once, for the exact jar that ships)
| Item | Value |
|---|---|
| Build (commit / BuildIdentity hash / jar SHA-256) | interim (not the freeze): 0.2.0+gcb3ddf83b333.i0ea487b3ec553a8d417c (12:15 tree), used for the real-server test |
| compileJava + JUnit | - |
| GameTests: all batches, with the failures waived (list) | interim: W19 1626/1654 (12:09) |
| Dedicated 2 h soak: crashes / stalls | interim: captain1 (09:10 tree), 2 h 17 m, 0 crashes; job stalls listed under Known issues |
| Measured MSPT: mean / P95 / max (settlers, raid, map viewers) | interim: captain2 (12:12 tree, 34 settlers + 9 raiders): avg 3.6–14.8 ms, P99 at most 96 ms; map 0.12–0.28 ms per push |
| Effective test-world toml checked | - |

## Kill-switches: the actual contract of each switch (Codex audit T14, 07:04)

All switches are in `serverconfig/hearthstead-server.toml`. "Off" never deletes saved data.

| System | Key | What "off" really does | Verified by | Owner |
|---|---|---|---|---|
| Builder | `[features] builder` | BuilderWorkGoal won't start or continue; new build orders and Builder's Plan use are refused; sites stay saved | GameTest `builder_switch` PASS (W3a) | builder |
| World events | `[features] worldEvents` (+ per-event switches) | No new events start. A RUNNING event is ended conservation-safely (outcome `:disabled`, visitors removed) when the master or its own switch is off, including after a save and reload (bug hunter, T14). | `event_disabled` + `bughunt_event_switch_off` PASS (W8) | events / bug hunter |
| Conversations + raid parley | `[features] conversations`, `[conversations] raidParley` | New conversations and parleys are refused. An ACTIVE parley releases its band; a duel closes unscored with no payout; damage hooks are inert (bug hunter, T14). | talk_* + `bughunt_parley_switch` PASS (W8) | conversation / bug hunter |
| Battle roles | `[features] battleRoles` | Role goals, damage rules and modifiers stand down, and role hires are refused. Role jobs idle; they don't fall back to Guard. | `battle_roles_kill_switch` PASS (W3a) | battle roles |
| Guard commands + Summon | `[features] guardCommands` | New orders and summons are refused; live orders are dropped and synced empty; guards return to default defence | `command_switch_off` PASS (W3a) | command |
| Warehouse levels | `[features] warehouseLevels` | Reverts to the baseline scan (first 64 containers, no level capacity or writes); items are untouched | `warehouse_levels` PASS (W3a) | warehouse |
| Gear tiers | `[features] gearTiers` | Opens all caps and goes back to the fixed rank kits and old armour overlays; nothing is moved | `gear_killswitch` PASS (W3a); JUnit GearTierTest | gear |
| Logistics | `[features] logisticsUpgrades` | Reverts the capacity, speed and cart/sack visuals to base for new applications. An already-raised bag is never shrunk. | `haul_gear_killswitch` PASS (W3a) | captain |
| Extended trades | `[features] extendedTrades` | Unlocks and the shop only: the 15 new trades are not sold, but emblems already held still hire (owner decision) | `trades_unlock` PASS (W3a) | trades |
| Living village (ambient barks, greetings, cheer, mourning) | `[features] livingVillage` | gates the whole com.hearthstead.ambient package (plus client toggles in [ambient]) | alive_ambient pending | living-village a14c5d2829b368127 |
| Crafted goods quality | `[quality] craftedQuality` / `statBonuses` / `merchantPremiums` | grading off = everything counts as Basic; legacy items read as Basic | goods_quality_ pending; JUnit CraftedQualityTest 6/6 | quality a5c747dc68a8ab1f7 |
| Revive | `[revive] enabled` | Refuses NEW downs at admission only; an existing downed session needs an explicit safe-completion test | revive_* 9 batches PASS (W3a); off-transition untested | revive (done) |
| Finisher | `[finisher] enabled` | Refuses new requests and guard contacts; a running execution finishes | finisher_* PASS (W4a); off-transition untested | finisher (closed) |

## Defaults — keep; revisit after the playtest (Codex T14 recommendation)
- `dayLengthMultiplier = 2.0`; recurring raids every 3–4 days (roughly 2–2.7 h of play; not every session).
- `[revive] enabled = true`, `soloDowned = false` (a teammate must be near to revive), bleed-out 50 s, revive 3 s.
- Events frequency 1.0 (dailyChance 0.55, at most one per eligible day). Trial 1.5 only after the safety fixes.
- Enemy base HP: Skirmisher 28, **Brute 70** (lowered from 110 on 26 Sep at 07:15: the first wave is winnable with 4 guards; revisit after the playtest), Goblin 20; `enemyHealthMultiplier = 1.0` (menace and captain scaling come on top).
- `workerWatchdog = false`; all `[features]` true.

## Feature lanes — current evidence

| Lane | Evidence | Open items |
|---|---|---|
| Builder | I C J(42) G (core/robust partial; switch and regress PASS) | palisade foundation logs unreachable, cottage plaque registration, 1 plank unreachable in two storeys (W4a) |
| World events | I C J G (all event_ PASS in W4a, including wild boar after the 256-settlement fix) | active-event off-switch gap |
| Conversations / parley | I C J(19) G (talk_ PASS; parley duel fails) | `talk_parley_duel`: an absorbed blow counts as a yield (W4a); active-parley off-switch gap |
| Guard command menu / summon | I C G (field_orders_ and salute PASS in W4a) | `command_summon` TOO_FAST (test timing) |
| Battle roles | I C J G (all role batches PASS in W3a; BH-09 death supplies PASS) | mage_frost flaky in the full suite; hardened, re-run pending |
| Finisher | I C J(25) G (finisher_ all PASS in W4a) | - |
| Revive | I C J G (9/9 batches PASS in W3a) | off-transition untested |
| Gear tiers | I C J G (gear_ PASS) | - |
| Warehouse levels | I C G (warehouse PASS in W3a) | - |
| Logistics / HaulGear | I C G (haul_gear PASS in W4a after the BH-22 time budget) | - |
| Extended trades | I C J G (trades_unlock PASS) | miner_drops: miner IDLE (MineShaft search), assigned to the bug hunter |
| Economy tuning ([economy]) | I C J G (economy_tuned_ 5/5 PASS in W13: lift n=4, crash replay, stow bundle, purse cap, self-fetch) | per-job work % from the 2 h soak pending |
| Balance / raids | I C G (raid_engage_, raid_health_, raider_rework_, guard_moveset_, raid_robust_ PASS in W4a) | guards whiff vs Skirmishers: RECOVER phase added |
| Archers | I C G (archer batch 12/12 PASS in the W6 isolation window) | the W3b failures were order-dependent (full-suite world state) |
| Guards (rest/food) | I C G FAIL | guard_critical_rest ×2 and guard_food_recovery ×2 (W3b), gametest-fixer |
| First-raid readiness | I C J (BH-24 fix), G re-run pending | **real progression blocker:** Arm the Watch never completed while gearTiers=true (Guard-delivery credit compared a capped requirement). Fixed by the bug hunter at 07:18. |
| Miner (soak lane) | I C J G FAIL | miner_pit: a trapped miner doesn't reach the surface (W4a), gametest-fixer |
| Map / Banner UI | I C J(29) G(pending) S (film) | realm_map_scope re-run pending |
| Settler UI | I C J(13) S (in game at 07:05) | stills retake pending |
| Animation / motion | I C S (films) | - |
| Repair nail (Codex T1) | I C J G (repair_day PASS in W3a) | - |

## Lang integrity
Checked at 05:35 with `build-agent-integration/langcheck.py`. No keys were lost in the en_us.json re-serialisation (compared against 12 snapshots and git HEAD). The builder's 7 strict keys have since been added. Event texts use English fallbacks.

## Known issues (owner-readable, before the test)

**Blockers (crash / corruption / dupe / item loss): none open.**
- FIXED 07:18, GameTest pending: BH-24, the first-raid quest 'Arm the Watch' could never complete with gear tiers on, which blocked progression to the first raid.
- FIXED: the server crashed when a payload was sent to a connection without the channel (revive, conversation, builder, finisher and pickup sends). GameTests found it; in real play it would hit fake players or proxy clients. All sends now go through PayloadSend.
- FIXED: BH-09, a healer's or mage's offhand supplies were lost on death (GameTest PASS). BH-16, items were deleted by popResource when doTileDrops=false.

**Should know:**
- Weak jobs in the 2 h soak: lumberer 9% (stuck chopping, no tool), miner 0% (a miner trapped in an old pit never escapes in the real world), scholar 0%, herder 6%, fisher 17%, hunter 3%. Crafters now work 75–81% (was 0–15%).
- ~~Raid-start lag spike~~: FIXED (BH-31). The JFR re-measure at 12:19 shows P99 at most 96 ms, down from 764 ms.
- Settler status labels in the world (e.g. "FARMER") are oversized up close (SettlerThoughtBubble/SettlerRenderer label scale). Assigned to the settler-UI lane at 08:41.
- ~~Settlers wedged under the window-box shelves~~: FIXED by the headroom lane. RoadNodeEvaluator now blocks head-height obstacles, 206 shelves are now top slabs and 15 stairwells are widened. GameTests path_headroom 6/6, builder_ and pathing_house PASS (W13/W14). Not yet seen in game; the builder re-film is pending.
- Founders can die to mobs on night 1 (QUEUE 14).
- The founding numbers show "4/3 people" and "Beds 4/3" (QUEUE 12).
- Early Coins are tight (QUEUE 13).
- UI polish: the tech-tree tooltip covers the header; the emblem shop says LOCKED when it's only unaffordable; handbook tabs are clipped (QUEUE 10).
- Revive only works with a teammate nearby (soloDowned=false). Explain this at the start.
