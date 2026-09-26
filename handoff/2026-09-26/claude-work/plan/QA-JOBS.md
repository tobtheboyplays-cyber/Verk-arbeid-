# QA-JOBS: every job does what it should (Super QA lane)

Status (26 Sep 2026, 06:20): **static (code-level) loop mapping is done for all 33 professions. The dynamic soak/watchdog pass has not started.** The lane is paused by the lead to save usage.

Criteria per job:
1. starts work
2. produces output
3. output reaches the right container
4. missing inputs → clear status
5. meals and bed, then back to work
6. survives a restart
7. animation and held tool match

Cells: P = pass, R = risk, F = fail, N/A. All verdicts are **static**. Dynamic verification is still to do.

## Release scope (important)
Only **16 professions are hireable in survival**, through Mayor emblems (`JobEmblemCatalog.RELEASE_CATALOG`):
- Lumberer, Farmer, Fisher, Courier, Trader, Innkeeper, Guard, Archer, Hunter, Sawyer, Scholar
- Builder
- Spearman, Longswordsman, Healer, Rune Mage

Mayor is appointed; NONE is the unemployed state.

The other **15 are unreachable in survival**. They have no emblem, and their tech node is `implemented=false`. They can only be hired with the admin command (HearthsteadCommand:654).

| Node | Professions |
|---|---|
| LAND_AND_HARVEST | Miller, Herder, Baker, Butcher |
| CRAFT_AND_INDUSTRY | Miner, Carpenter, Mason, Smelter, Smith, Tanner, Weaver |
| HALL_AND_LEARNING | Cook, Brewer |
| FORTIFICATION | Armourer |
| no node | Fletcher |

## Shared mechanics (from code)
**Goal priorities** (SettlerEntity:762-871):

| Priority | Goals |
|---|---|
| 1 | Healer medic, evacuate |
| 2 | Role combat, guard melee/leap, archer attack |
| 3 | Field orders, banner team, alert, summons |
| 4 | GuardOrder, **Eat** |
| 5 | **RestAtNight**, acquire equipment, repair |
| 6 | GoToPost, all job work goals, patrol, archer post |

All job goals claim MOVE, so eating and bed always pre-empt work. The work-hours checks bring settlers back afterwards.

**Status to the player:**
- `setLogisticsStop` → `hearthstead.settler.fix.*` on the sheet
- the equipment-request icon
- the activity label

`recordRouteFailure` is debug-only.

**Martial shifts** (Employment.watchOf:1351):
- Even roster index takes the day watch; odd takes the night watch.
- Night watch sleeps through the morning work and meal phases.
- An alert wakes armed martial settlers.

**Watchdog:** `/hearthstead watchdog on|off|report|reset` (op 2; qa/watchdog/WorkerWatchdog.java:799). It flags `need_no_recovery`, `no_output` and `static`.

## Matrix (released jobs first)
| Profession | Loop (short) | 1 | 2 | 3 | 4 | 5 | 6 | 7 | Main GameTests |
|---|---|---|---|---|---|---|---|---|---|
| LUMBERER | zone + axe → walk → chop/limb → collect logs → bag → camp storage | P | P | R (full camp keeps logs in the bag, no loss) | R (full or unreachable is debug-only) | P | P | P | LumbererGameTests (24), SelfCraft (11) |
| FARMER | zone + hoe → survey → harvest/plant/till/water → farmhouse storage; after hours takes the harvest home | P | P | R | R (storage full is debug-only) | P | P | P | FarmerBootstrap (10), Recovery (10), BagLifecycle (6) and more |
| FISHER | rod + water + rack → sit → cast → bag → rack or barrel | P | P | P | R (no water / rack full / no chair spot are debug-only) | R (fish stay in the bag overnight, no loss) | P | P | TradeFisher (5), FisherRackUnload (2) |
| HUNTER | bow + arrows → track → shoot → loot → haul carcass → butcher → lodge chest | P | P | P | P | **R (J-03: drops can despawn overnight)** | P | R (GAME_SCARCE has no idle pose) | HunterPhysical (12), HunterCarcass (11) |
| COURIER | resume bag session → rebuild delivery from ledger → food, restock, output, consolidate → tidy warehouse | P | P | P | P | P | P | P | Courier (9), FoodRoute (19), StopReason (12) and more |
| TRADER | pick a Wandering Trader in 128 blocks → wait stock → load → walk → sell → return cargo | P | P | **R (J-05)** | **F (J-05: no stop reason at all)** | P | P | R | TraderWorkGoal (3), TraderSale (3) |
| INNKEEPER | at the tavern → table service → ale refill | P | P | P | R (empty stores debug-only) | P | P | R (J-07: frozen if a hand is full; TRAVELING pose after a refill) | TavernHost (15), Restaurant (20), Seating (28) |
| SCHOLAR | at the lectern → active or first affordable project → timed sessions → progress | P | P | N/A | R (no affordable project is silent) | P | P | P | ResearchGameTests (16) |
| SAWYER | post → CrafterWorkGoal → Production.run → own chests → courier | P | P | P | **R (J-01)** | P (progress resets on interrupt, J-09) | P | P | TradeSawyer |
| BUILDER | claim job → load at hut → fetch → build with scaffold → return bag | P | P | P | P | P | P | P | Builder (9), Robustness (10), Regression (8) |
| MAYOR | office; as Mayor-Courier runs CourierWorkGoal | P | P | P | P | P | P | P | MayorCourier (3) |
| GUARD | sword → patrol on watch → target → melee/leap → recovery / orders | P | P | N/A | P | P (night-shift rule) | P | P | GuardMeleeContact (17), GuardControl (24) and more |
| ARCHER | bow → default tower post → real arrows from chests → OUT_OF_AMMO status | P | P | N/A | P | P | P | P | ArcherGameTests (12) |
| SPEARMAN | spear from Pike Yard → combat goal (brace, moves) | **R (J-02: no peacetime duty)** | P | N/A | P | P | P | P | BattleRole: spearBrace |
| LONGSWORDSMAN | longsword → combat goal (cleave, heavy, half-sword) | **R (J-02)** | P | N/A | P | P | P | P | BattleRole: longswordCleave |
| HEALER | Infirmary post → flee > revive > evacuate > bandage > restock | P | P / R (revive untested) | N/A | R (J-06: no bandages → silent) | **R (J-04: sleeps through night raids, ignores alerts)** | R (heal-over-time is in memory only) | R (no idle clip) | BattleRole: healer (2), Revive (1) |
| RUNE_MAGE | enemy in 20 blocks → keep distance → channel → firebolt/frost/ward | **R (J-02)** | P | N/A | **R (J-08: rune stones only from the offhand; nothing supplies them)** | P | P (charges persisted) | R (no idle clip) | BattleRole: mage (3) |
| NONE | free time, stroll, idle pose | N/A | N/A | N/A | N/A | P | P | P | – |
| *Not in release:* | | | | | | | | | |
| MINER | mine valid → staircase cut → loot table → mine storage | **F (J-10: no pickaxe needed; uses an imaginary iron pickaxe)** | P | P/R | **F (full or no stone is silent)** | P | P | R (bare-hand swing; no give-up timeout) | MinerPit (2), MinerDrops (2) |
| HERDER | eggs > shear > feed/breed > cull → pasture storage | **F (J-10: shears without shears)** | P | R (J-11 egg lock) | **F (no reasons at all)** | P | P | R (empty-hand shear) | TradeHerder (1) |
| BAKER, COOK, BUTCHER, SMELTER, SMITH, CARPENTER, MASON, FLETCHER, WEAVER, TANNER, MILLER, BREWER | post → CrafterWorkGoal → own chests | P | P (all 14 buildings have recipes) | P | R (J-01) | P | P | P (empty hands by design) | Trade*GameTests per trade (the Baker only at Production level) |
| ARMOURER | same | P | P | P | R | R (J-09: long recipes can never finish) | P | P | ArmouryGameTests |

## Job findings
| # | Sev | Where | Problem | Status |
|---|---|---|---|---|
| J-01 | MEDIUM | CrafterWorkGoal.java:154-160; SettlerScreen:1760 | Crafters (the Sawyer in release) never set a stop reason. With missing inputs or a full output chest the sheet says "Nothing needed from you". The lang keys `settler.fix.waiting_input` and `chest_full` already exist. | open, to assign (crafter/economy lane a60cce1309921f3b3) |
| J-02 | MEDIUM | GuardPatrolGoal:50, ArcherDefaultPostGoal:61, Schedule:109-113 | Spearman, Longswordsman and Rune Mage have no duty between fights. They only stroll; BATTLE-ROLES.md says "holds nearest post". | open, to assign (Battle roles ab2beb0f1e2d547df) |
| J-03 | MEDIUM | HunterWorkGoal:1124-1129; GroundCollectionSession:728 | The hunter abandons loot collection at the evening bell. Dropped game lives about 12,000 ticks but the night is about 14,000, so kills can despawn. | open, to assign (bug hunter a8450891fcfc4a7a2) |
| J-04 | MEDIUM | RestAtNightGoal:73-80; GuardRespondToAlertGoal:30 | The Healer is not martial: it sleeps through night raids and ignores alerts. | open, to assign (Battle roles) |
| J-05 | MEDIUM | TraderWorkGoal:165-174 | Returning cargo to a full or unreachable post keeps the goal alive with no stop reason and no path timeout (the trader just stands). No merchant or no stock is also silent. | open, to assign (bug hunter) |
| J-06 | LOW | HealerMedicGoal:455-457 | The Healer can't restock if its offhand holds another item. No bandages means a silent idle. | open, to assign (Battle roles) |
| J-07 | LOW | InnkeeperWorkGoal:81 | The Innkeeper freezes silently if either hand holds something. | open, to assign (Tavern a3f6c811019e78601) |
| J-08 | LOW-MED | RuneMageGoal:327-342 | Rune stones are read only from the offhand, and nothing puts them there (the design says the Rune Hall chest or the bag). | open, to assign (Battle roles) |
| J-09 | LOW-MED | CrafterWorkGoal:200-205 | Any interruption resets the batch. An Armourer chestplate takes 3120 ticks at the default multiplier (10400 at the max, longer than a work window). The Scholar session likewise. | open, to assign (economy lane) |
| J-10 | MEDIUM (not in release) | EquipmentRequests:63-85; MinerWorkGoal:220; HerderWorkGoal:406 | Miner and Herder work without a tool. There is no requirement entry and no equip gate. | open, low priority (not reachable in survival) |
| J-11 | MEDIUM (not in release) | HerderWorkGoal:149-156, :299 | With a full pasture chest the Herder retries the same egg forever, so it never shears or feeds. | open, low priority |
| J-12 | LOW | SettlerEntity:1345 `setProfessionProjection` | An old stop reason survived a job change (it was cleared only when leaving Courier). | **FAIL→fixed**: `clearLogisticsStop()` now runs on every profession change. javac OK. Dynamic check pending. |
| J-13 | LOW | HunterWorkGoal:369; InnkeeperWorkGoal:142 | GAME_SCARCE and TRAVELING while standing have no idle pose, so the settler stands rigid. | open, to assign (Animation ab37ea5dab0102132) |
| J-14 | LOW | FisherWorkGoal:74 vs :91; HunterWorkGoal:233 vs :280 | Cargo stays in the bag overnight (no loss). | open, low |
| J-15 | LOW | BuilderWorkGoal:899-906 | Returning materials has no path give-up. | open, to assign (Builder a1f86e34f18da84e3) |
| J-16 | LOW | MinerWorkGoal:238-247 | `hasRoom` ignores part-filled stacks. | open (not in release) |
| J-17 | test gap | – | Missing GameTests: a hired Baker through the goal, healer revive and evacuate, rune-stone use, battle-role peacetime, miner and herder with full storage or no tool, herder egg lock, hunter drops at the bell, trader return to a full post, innkeeper with a hand item. | for the captain |

## Dynamic plan (not started)
1. Take a disposable copy of `soak/pristine-world-with-config`.
2. Hire one of each released job (16) with the admin hire command. Run `/hearthstead watchdog on`, then 2 in-game days, then `watchdog report`.
3. Restart the server mid-day 2 to test survival across a restart.
4. Capture screenshots per job for poses and tools.
5. Needs the WSL lock. Queue behind the captain, soak, finisher, conversation, builder and economy in WSL-QUEUE.md.
