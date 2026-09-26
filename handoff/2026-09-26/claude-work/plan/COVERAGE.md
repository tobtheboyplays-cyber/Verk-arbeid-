# COVERAGE: every system, every branch, from a fresh start (scenario lane)

Owner order (26 Sep): "On Sunday we may start over, so you must check everything! All paths, all scenarios."
This is the map of every player-facing branch. For each one it gives the GameTest [batch] or JUnit that proves it, or the gap. New tests from this lane use batch prefix `scenario_`.

"Covered" means a test **asserts** that outcome; a fixture that only walks through the branch does not count. JUnit on pure rules is listed, but it is not behavioural coverage.
RJ = `FirstRaidReadinessGameTests.realAuthoritativeJourneyUnlocksOneExactFirstRaid` [first_raid_readiness_real_journey]. HC = its sister test `persistedScheduledCalendarRecoversMissingJourneyReceipt`.
Status column: **OK** covered · **GAP** missing · **FILL** a scenario_ test now covers it (see "Filled by") · **BUG** a scenario test found a real bug.

Coverage count at the map (26 Sep 11:10): **about 150 GAP rows**, counting each blueprint as one row. **After (13:40): 18 GAP rows remain; 62 are FILL.** Run W20c (13:33, prefix s, 498 tests): 483 pass. The 15 fails: 12 open-air yard blueprints (routed to the blueprint lane), and 3 fixed after the run (study bonus timing, duel-lost fixture). The slow scenario_blueprint_build tier (now 194 builds) has not run yet; it needs its own captain window.

---

## 1. Fresh-world founding, handbook, Journey, first merchant

| Branch | Covered by | Status |
|---|---|---|
| Banner item placed: needs 2 clear blocks, faces the player | SettlementBannerGameTests.placementNeedsClearanceAndFacesPlayer | OK |
| Hearth tick founds: 3 workers + dedicated Mayor, idempotent | FoundingJourneyGameTests.fourFoundersIncludeExactlyThreeHireableWorkers [founding_dedicated_mayor]; HearthsteadGameTests.foundingSpawnsSettlers | OK |
| 4th founder fails → whole founding rolls back | FoundingJourneyGameTests.fourthFounderFailureRollsBackTheWholeFounding | OK |
| Founder deaths shrink the roster, Mayor seat vacated | FoundingJourneyGameTests.killingThreeFoundersIncludingMayor… | OK |
| Founding refused too close to another settlement | – | GAP |
| Banner item placed → founded settlement in one test | – | GAP (small) |
| Starter handbook: login delivery, once only, full-inventory retry, existing copy | – | GAP → FILL scenario_founding |
| Early merchant published for a founded village, reachable, reload-safe | EarlyCoinMerchantGameTests.* [early_coin_merchant] | OK (calls visit() directly) |
| Early merchant arrives via the real onTick trigger (player within 80) | – | GAP → FILL scenario_founding_merchant_arrives |
| Sell logs → Coins → buy TIMBER_RIGHTS with those Coins (end to end) | – | GAP → FILL scenario_founding |
| fj_010 found hearth | FoundingJourneyGameTests (dedicated mayor) | OK |
| fj_020 open journey, fj_030 appoint mayor, fj_130 lumberer inventory, fj_230 request ledger | RJ + HC (fixture asserts each) | OK |
| fj_100…fj_559b (Lumber, Warehouse, Farm, Home, Tavern, recruit, Guard, 5th settler, Archer) | RJ `completedThrough(FJ_560)`; closure only completes a step after all its prerequisites | OK (transitive: no per-step assert; work loops are scripted service calls, not the AI) |
| fj_553–555 second traveler | HC; JourneyV3GameTests.waitingWatchObservationSurvivesRestart… | OK |
| fj_560 declare raid ready, half-commit recovery | RJ, HC | OK |
| fj_600/610/620 warning, raid resolved, aftermath | RJ | OK (fj_610 closed with a hand-called HELD, no real combat) |
| Fisher alternative for fj_330/fj_360 | JUnit FisherJourneyDefinitionTest only | GAP |

## 2. Tech tree (Development nodes)

Update 11:55: the **v3 tech tree** (data/hearthstead/techtree/*.json, 84 nodes, `TechTree.learn`) landed during this lane. It has **7 pick-one pairs** (wayside_shrine/alehouse, cathedral/hall_of_revels, charcoal_kilns/deep_mine, porters_guild/runners_guild, archer_longbow_drill/crossbows, palisade/earthworks, knights/pike_square) and 27 gated nodes. It also moves some plans and emblems to their own nodes (builders_hut, tannery, carpenter_mason and others). The legacy doctrines still stack, and `DOCTRINE_EXCLUSIVE` is only returned for the legacy upgrade path of a v3 pair.
**FILL scenario_techtree** (84 tests, one per node). An implemented node is learned after its prerequisites through `TechTree.learn` and pays exactly once; the replay is refused; its plans and emblems open; each side of a pick-one pair blocks the other (BLOCKED + EXCLUDED, nothing paid). A planned node cannot be bought.

| Node | Learnable | Paid exactly once | Grants its unlock | Status |
|---|---|---|---|---|
| SETTLEMENT_CHARTER, SHELTER | free at founding (DevelopmentGameTests) | n/a | yes | OK |
| TIMBER_RIGHTS | DevelopmentGameTests | refusal takes nothing; success charge not asserted | Lumber Camp yes; Builder's Hut/Builder no | GAP → FILL scenario_tech |
| STORES_AND_ROADS | DevelopmentGameTests, RJ | not asserted | Warehouse/Courier | GAP → FILL scenario_tech |
| CULTIVATED_GROUND | RJ, development_runtime | not asserted | Farmhouse/Farmer | GAP → FILL scenario_tech |
| SHORE_PROVISIONS | never bought in any test | – | – | GAP → FILL scenario_tech |
| HOME | early_housing | not asserted | HOUSE/LODGING | GAP → FILL scenario_tech |
| HOSPITALITY | development | not asserted | Trading Post + Trader emblem; Tavern/Innkeeper not asserted | GAP → FILL scenario_tech |
| FIRST_WATCH | development (quest gate) | payment not asserted | Barracks/Guard | GAP → FILL scenario_tech |
| ARM_THE_WATCH | RJ (quest gate, free) | n/a | Watchtower/Archer | OK |
| FIRST_RAID_AFTERMATH | DevelopmentGameTests.borderWardens… | – | Healer/Infirmary not asserted; FIRST_RAID_REQUIRED refusal untested | GAP → FILL scenario_tech |
| SHIELD / GUILD / HEARTH doctrines, BORDER_WARDENS | DoctrineProgressionGameTests, DevelopmentGameTests | yes (payAndAssert) | emblems yes; Pike Yard / Sword Hall / Sawmill / Architect's Study / Rune Hall plans not asserted | GAP (plan grants) → FILL scenario_tech |
| FORTIFICATION, LAND_AND_HARVEST, CRAFT_AND_INDUSTRY, HALL_AND_LEARNING (extended) | TradesUnlockGameTests [trades_unlock] | not asserted | 1 plan + 1 emblem each; the other 11 emblems and plans not asserted | GAP → FILL scenario_tech |
| extendedTrades ON (default) | JUnit ExtendedTradesUnlockTest | | | OK |
| extendedTrades OFF: all 4 nodes FUTURE, emblems hidden, 16 base still sold | TradesUnlockGameTests (1 node); JUnit (all 4) | | | OK (GameTest partial) |
| extendedTrades OFF: an already-hired extended worker keeps working | – | | | GAP → FILL scenario_tech |
| Research projects (6): start cost, refusals, Scholar advances, cancel refunds half | ResearchGameTests [research] | | | OK |

## 3. Professions: real survival hire path + one work loop

The real path is: learn the node, fit the building plan, buy the emblem from the Mayor (`Development.purchaseEmblem`), receive it (`deliverPending`), use it on a settler. **Only 9 of 33 are hired this way in any test**; the rest use `Employment.hire` / `assignProfession` directly. QA-JOBS.md is out of date: with extendedTrades on (the default), all 31 catalogue emblems can be bought.

| Profession | Real hire path tested | Work loop after hire | Status |
|---|---|---|---|
| MAYOR | founding + appointment + second-Mayor feast (costs) | MayorCourierGameTests | OK |
| LUMBERER, COURIER, FARMER, GUARD, ARCHER | RJ (real purchase + emblem) | separate tests with direct hire; RJ scripts the loop | OK (split) |
| MILLER, CARPENTER, COOK, FLETCHER | TradesUnlockGameTests (buy → give → AI works) | yes | OK |
| FISHER, TRADER, INNKEEPER, BUILDER, HUNTER, SAWYER, SCHOLAR | – | direct-hire loops exist | GAP → FILL scenario_hire |
| SPEARMAN, LONGSWORDSMAN, HEALER, RUNE_MAGE (battle roles, hall gate) | – | BattleRoleGameTests (assignProfession, forced moves) | GAP → FILL scenario_hire |
| HERDER, BAKER, BUTCHER, MINER, MASON, SMELTER, SMITH, TANNER, WEAVER, BREWER, ARMOURER (extended) | – | direct-hire loops exist | GAP → FILL scenario_hire |
| Battle role hired without its hall (RoleHiring refusal) | – | | GAP → FILL scenario_hire |

## 4. Buildings and blueprints

| Branch | Covered by | Status |
|---|---|---|
| HOUSE, WAREHOUSE, LUMBER_CAMP, FARMHOUSE, TAVERN, BARRACKS, WATCHTOWER, INFIRMARY, MARKET reach L1 from a real room | Builder/RoomScanner/RJ fixtures/TavernTapRequirement/SurvivalAuditWall | OK |
| The other 26 blueprint types reach L1 from a real room | – | GAP → FILL scenario_blueprint (the real blueprint's finished shape, surveyed through its plaque) |
| PIKE_YARD, SWORD_HALL, RUNE_HALL reach L1 (**no blueprint exists**; hand-built only) | – | GAP → FILL scenario_hire (hand-built hall room) |
| Levels above L1 (only HOUSE L1→L2 is tested) | BuilderGameTests.upgradeOrder… | GAP (L2+ for other types) |
| **All 77 blueprints** load, plan on flat ground, have a chargeable bill, fit their own plan, and register valid | – (no committed test loaded a real blueprint file) | GAP ×77 → FILL scenario_blueprint (78 tests incl. catalogue guard) |
| **All 77 blueprints** built by a real Builder self-fetching the exact bill from a fresh Warehouse, nothing skipped, ladders returned, registered | – | GAP ×77 → FILL scenario_blueprint_build (77 slow tests) |
| Typed defense blueprints (watchtower_timber, watchtower_stone, stone_gatehouse) register through their plaque | – | **BUG → fixed**: BuildPlanner.java:143 fitted the plan only for kind BUILDING, so a Builder-built watchtower never registered. Fixed in the planner; scenario_blueprint is the regression |
| Builder exact bill, save/reload mid-build, player block protected, stop/cancel, dismantle, scaffold, two storeys | BuilderGameTests / Robustness / Regression | OK |
| Upgrade Order (HOUSE) | BuilderGameTests.upgradeOrder… | OK (other types and the Hut L2 gate: GAP) |
| Deconstruct a registered building | – | GAP → FILL scenario_builder |
| PAUSE / RESUME / RUSH / UP / DOWN / REQUEST_NOW | – | GAP → FILL scenario_builder |
| Missing materials → WAITING_FOR, nothing charged; then stocked → completes | – | GAP → FILL scenario_builder |
| Builder switch off, then on again: resumes | builder_switch (off only) | GAP → FILL scenario_builder |

## 5. Warehouse levels 1–5

| Branch | Covered by | Status |
|---|---|---|
| L1 = 16 managed containers; over-capacity never blocks | WarehouseLevelGameTests [warehouse_levels] | OK |
| L2 = 32, L3 = 64 + Racks gate | WarehouseLevelGameTests.upgradeRaisesCapacity (level set by hand) | OK (partial) |
| L4 = 128 (Great Storehouse), L5 = 256 (Royal Storehouse) managed in world | JUnit only | GAP → FILL scenario_warehouse |
| Tech cap: level capped by the learned node, then raised by learning it | – | GAP → FILL scenario_warehouse |
| Grandfathered saves, player marks, index updates, kill switch | WarehouseLevelGameTests | OK |

## 6. Gear tiers 0–4

| Branch | Covered by | Status |
|---|---|---|
| T0 always allowed | JUnit GearTierTest; GuardRankGameTests | OK |
| T1 Mail, T2 Plate: refused, then promoted | GearProgressionGameTests [gear_progression] | OK |
| T3 Diamond: refused on rank, refused on knowledge, accepted | GearProgressionGameTests | OK |
| T4 Netherite: Captain + crown + master armoury accepted, Sergeant refused (in world) | JUnit only | GAP → FILL scenario_gear |
| Demotion: kit refresh strips iron | GuardRankGameTests.armorNeverOutstripsTheCurrentRank | OK |
| Demotion with self-equipped diamond/netherite from the pack | – | GAP → FILL scenario_gear |
| Hand-over refusal message (GearGate.refusal) | – | GAP → FILL scenario_gear |
| "Refuse gear below tier" | the rule does not exist (T0 always allowed) | n/a |
| Kill switch | gear_killswitch | OK |

## 7. Raids

| Branch | Covered by | Status |
|---|---|---|
| First raid starts from a real journey | RJ | OK |
| First raid WIN (8 Coins, 1 Blessing offer, once, reload) | FirstRaidRuntimeGameTests.definitiveHeldCompletion… | OK |
| First raid LOSS (stores escaped / settlers hurt / arson): HIT, no reward, cadence LOST | JUnit only | GAP → FILL scenario_raid |
| First raid loss with population 0 → SETTLEMENT_LOST | – | GAP |
| Recurring raid win (seal, one reward), loss (report, pressure) | RecurringRaidGameTests, RaiderGameTests, RaidPressureGameTests | OK |
| Recurring scheduling after win/loss (warning the dusk before) | RaidCadenceGameTests | OK |
| Dawn retreat (recurring and first raid) | RaidCadenceGameTests, RaidRobustnessGameTests | OK |
| Footing: dry rays, water-surrounded, forest canopy, hills, claim-only (no band), fluids | RaidSpawnClaim/Terrain/RaiderGameTests | OK |
| Footing: cliff beyond SURFACE_REACH, snow layer, fire, roof/cave, unloaded chunk, no footing → NO_FOOTING notice | JUnit only / – | GAP → FILL scenario_raid (snow, cliff, no footing) |
| Parley: tribute coins | ConversationGameTests.payingTributeMakesTheRaidLeave | OK |
| Parley: tribute food | – | GAP → FILL scenario_parley |
| Parley: persuade SUCCESS (truce → raid retreats) | – (no roll seam) | GAP → FILL scenario_parley |
| Parley: persuade FAILURE (enraged band) | – | GAP → FILL scenario_parley |
| Parley: duel won / lost: the outcome is recorded, the consequence is not | RaidParleyDuelGameTests | GAP (consequence) → FILL scenario_parley |
| Parley: refuse | – | GAP → FILL scenario_parley |
| Parley: timeout (45 s → impatient, release) | – | GAP → FILL scenario_parley |
| Parley: striking a holding raider breaks it | – | GAP → FILL scenario_parley |
| Parley: switch off mid-parley | bughunt_parley_switch | OK |

## 8. Revive and finisher

| Branch | Covered by | Status |
|---|---|---|
| Solo player NOT downed (soloDowned=false) | JUnit ReviveRulesTest only | GAP → FILL scenario_revive |
| Downed during raid; revived by teammate; interrupt; bleed-out; logout while down; /kill never downs; login recovery | ReviveGameTests (9 batches) | OK |
| `[revive] enabled` off while a player is downed | – (SUNDAY-GATE: untested) | GAP → FILL scenario_revive |
| Healer revives a downed player | – | GAP → FILL scenario_revive |
| Raider finisher on a downed player | – | GAP |
| Finisher: kill once, busy, no window, reach/LOS, double, lapse, untouchable, guard gate | FinisherGameTests | OK |
| Finisher: DISABLED, INVALID_TARGET | – | GAP → FILL scenario_finisher |
| `[finisher] enabled` off while an execution runs (must finish) | – (SUNDAY-GATE: untested) | GAP → FILL scenario_finisher |
| Guard finisher contact becomes an execution | – | GAP |

## 9. Commands

| Branch | Covered by | Status |
|---|---|---|
| Knights LINE / ATTACK / revert / RETURN / expiry; refusals | FieldOrderGameTests | OK |
| Knights FOLLOW (only the assignment kind is asserted) | FieldOrderGameTests.requestsAreChecked… | GAP (movement) |
| Archers LINE / ATTACK / HIGH_GROUND, HOLD_FIRE / FIRE_AT_WILL | JUnit only | GAP → FILL scenario_command |
| Spearmen LINE → brace; Longsword / Mage / Healer / ALL orders; ALL FOLLOW / ALL RETURN | JUnit shapes only | GAP → FILL scenario_command |
| Guard menu STAND_POST, patrol route, TOWER_POST, RALLY | GuardOrderNetwork / GuardControl | OK |
| Guard menu DEFEND_HEARTH, REMOVE_PATROL_POINT, TOGGLE_TRAVERSAL, CLEAR_ORDER success | – | GAP → FILL scenario_command |
| Banner team MOVE / HOLD / FOLLOW | BannerTeam / BannerOrderNetwork | OK |
| Banner team ATTACK | – | GAP |
| Summon guard; summon civilian | PlayerSummonGameTests | OK |
| Summon refusals NOT_FOUND / NOT_SETTLER / NO_PERMISSION / ASLEEP / TOO_FAST | – | GAP → FILL scenario_command |
| Guard salute: once, held until past, alarm | GuardSaluteGameTests | OK |
| Salute cooldown / field-order cancel / co-op second player | – | GAP (minor) |
| Command switch off | command_switch_off | OK |

## 10. World events (11): every choice and the no-answer branch

Existing tests cover one branch per event, and most end the event with a hand-called finish(). No test ran any event's **budget** to its end. scenario_event runs every branch below through the real `choose` / conversation path, drives the director until the event ends **by itself**, asserts the recorded outcome, that the price was paid exactly once, that a second player's answer is refused, and that every actor is gone (or kept, for adopted dogs and taken-in refugees).

| Event | Branches | Before | After |
|---|---|---|---|
| Peddler | trade (static wares), timeout "left" | 1 partial | +timeout |
| Refugees | accept, work_yes, work_no, feed, decline, timeout "moved_on" | accept, decline (hand finish) | all 6 |
| Minstrels | host "feast", tips, away "sent_away", no answer → tips set | host | all 4 |
| Field fox | killed, timeout "chased_off"/"stole_and_left" | 0 outcomes | both |
| Wolf pack | slain, timeout "driven_off"/"took_livestock" | 0 outcomes | both |
| Wild boar | killed, hunter_kill, timeout "wandered_off" | killed | all 3 |
| Tavern brawl | separate, timeout "tired" | separate | +timeout |
| Brute toll | pay_food, pay_coins, talked_down, insulted, refuse, attack, no answer → "took_food" from the stores | pay_food, refuse, default (if/else) | all 7 |
| Stray dog | feed_dog "adopted", shoo "shooed", timeout "wandered_off" | feed_dog | all 3 |
| Caravan | escort (walk the route), pass "passed", timeout "moved_on" | escort (teleported) | all 3 |
| Rival envoy | pact, pact_failed, befriend, insult, dismiss, timeout "ignored" | befriend | all 6 |

## 11. Conversations

| Branch | Covered by | Status |
|---|---|---|
| Reply cost exactly once; barter exact; replays refused; line of sight | ConversationGameTests [talk_*] | OK |
| Relations persist across save/restart (RelationSavedData) | – | GAP → FILL scenario_talk (JUnit-style roundtrip + GameTest restart) |
| Persuasion success / failure applied in game | – (no roll seam) | GAP → FILL via scenario_event (work_yes/no, talked_down/insulted, pact/pact_failed) |
| Talk timeout closes the talk | – | GAP |

## 12. Economy and goods quality

| Branch | Covered by | Status |
|---|---|---|
| Merchant buys raw goods (logs), pays once | GoldCoinTradeGameTests, MerchantDemandGameTests | OK |
| Merchant buys crafted goods (tuned) | EconomyTunedGameTests (bread only) | OK (partial) |
| Merchant buys quality goods | GoldCoinTradeGameTests.qualitySales…, FisherTradeGameTests | OK |
| Purse grows with the village (12 + 2 per 5 settlers, cap 40) | only cap and wire bound | GAP → FILL scenario_economy |
| Courier: Hearth → Warehouse, fuel/inputs to a workshop, food to the Hearth | CourierGameTests, CourierFoodRouteGameTests, EconomyTuned lift | OK |
| Crafter self-fetch | EconomyTunedGameTests.tunedCrafterFetchesItsOwnInputs… | OK |
| Idle workshop upkeep (tidy, sharpen, study XP; first input ends it) | – | GAP → FILL scenario_economy |
| Quality tiers in world above FINE; crafters stamp quality | – | coordinate with quality lane (a5c747dc68a8ab1f7) |

## 13. Save/restart in the middle of each long process

| Process | Covered by | Status |
|---|---|---|
| Build | BuilderGameTests.saveReloadMidBuild…; cleanupSurvivesSaveAndReload | OK |
| Raid | NBT roundtrips (RecurringRaid, Raider) | OK (partial: no live combat resumed) |
| World event | JUnit roundtrip; bughunt restored-event switch off | GAP (restored event then answered/timed out) → FILL scenario_event (reload branch) |
| Revive | by design a restart bleeds out; login recovery | OK |
| Parley | – (static ACTIVE map; onJoin frees HOLD_TAG raiders) | GAP → FILL scenario_parley |
| Courier delivery in flight | Logistics / CourierFoodRoute / EconomyTuned replay | OK |
| Crafting order | CraftingOrderGameTests.orderSurvivesSaveReload… | OK (mid-batch reload: GAP) |

## 14. Co-op (two players)

| Branch | Covered by | Status |
|---|---|---|
| Both give orders (latest wins; atomic revision) | FieldOrderGameTests, GuardOrderNetworkGameTests | OK |
| Both trade (one after the other) | GoldCoinTradeGameTests | OK |
| Both answer one world event (first wins, paid once) | – | GAP → FILL scenario_event (every answered branch) |
| One leaves mid-action: revive | ReviveGameTests.loggingOutWhileDownedBleedsOut | OK |
| One leaves mid-escort / mid-parley / mid-conversation | – | GAP → FILL scenario_event (escort leave) |

## 15. Kill-switches on a fresh world

| Key | Covered by | Status |
|---|---|---|
| builder, worldEvents, battleRoles, guardCommands, warehouseLevels, gearTiers, logisticsUpgrades, extendedTrades, raidParley, per-event | see SUNDAY-GATE table | OK |
| `[features] conversations` master off (events fall back to chat answers) | – | GAP → FILL scenario_switch |
| `[conversations] encounters` off | – | GAP |
| `[revive] enabled` off (GameTest) | – | GAP → FILL scenario_revive |
| `[finisher] enabled` off | – | GAP → FILL scenario_finisher |
| `[hunting] huntingGrounds` off | – | GAP |
| `[economy]` selfFetch / workshopUpkeep / merchantBuysCrafted off | – | GAP |
| Every key false at boot (TOML) on a truly fresh world | – (all tests toggle at runtime) | GAP (needs a config-false GameTest server launch; captain) |

---

## Filled so far (scenario_ tests)
Updated as each file lands. See the bottom of this file.

| File | Batches | Tests | Covers |
|---|---|---|---|
| gametest/ScenarioBlueprintGameTests.java | scenario_blueprint, scenario_blueprint_build (slow; skip with -Dhearthstead.gametest.skipBlueprintBuilds=true) | 78 + 77 | §4: all 77 blueprints plan, are chargeable, fit their plan and register; a real Builder builds each from a fresh Warehouse (self-fetch) |
| event/worldevent/ScenarioWorldEventGameTests.java | scenario_event | 42 | §10: every answer + the no-answer branch of all 11 events, ending by themselves; exact price; replay refused; co-op second answer refused (§14); escort abandoned by a logout (§14); restart then answer (§13) |
| conversation/parley/ScenarioParleyGameTests.java | scenario_parley_* | 11 | §7 parley: tribute food, unaffordable, truce success/failure, refuse, timeout, duel won/lost (real fight), leash, duelist logout (§14), strike breaks it, restart releases the held band (§13) |
| settlement/development/ScenarioHireGameTests.java | scenario_hire | 31 | §2 + §3: every Mayor emblem via the real path (node paid exactly once, replay refused, plan + emblem granted, emblem paid once, refused without a workplace, hired with one); workshop trades run one production loop |
| gametest/ScenarioReviveGameTests.java | scenario_revive_* | 5 | §8: solo not downed (default), soloDowned=true, switched off refuses new downs, switched off while down → revive still works / bleed-out still ends it |
| finisher/ScenarioFinisherGameTests.java | scenario_finisher_* | 3 | §8: disabled, invalid target, switched off mid-move finishes cleanly |
| gametest/ScenarioRaidGameTests.java | scenario_raid_first_lost_* | 3 | §7: first raid LOST (KORN, BLOD) → HIT, no Coins/Blessing, one log, reload-safe; nobody left → SETTLEMENT_LOST |
| gametest/ScenarioFoundingGameTests.java | scenario_founding_* | 5 | §1: handbook on login, never twice on relog, full-inventory retry, existing copy; first merchant's Coins buy Timber Rights; Banner too close founds nothing |
| gametest/ScenarioBuilderGameTests.java | scenario_builder_* | 5 | §4: missing materials wait and name the item, pause/resume, UP reorders, deconstruct a house (+ own-hut refusal), switch off → on resumes |
| settlement/development/ScenarioProgressionGameTests.java | scenario_warehouse_levels, scenario_gear_* | 4 | §5: L3/L4/L5 (64/128/256 of 300) through the real tree cap; §6: T4 netherite Captain vs Sergeant, demotion keeps worn / refuses next, refusal line |
| gametest/ScenarioCommandGameTests.java | scenario_command, scenario_summon_refusals | 49 + 1 | §9: every (role group × order) pair on a live 6-role army, with each role's meaning; summon NOT_FOUND / NOT_SETTLER / NO_PERMISSION / TOO_FAST |
| settlement/development/ScenarioTechTreeGameTests.java | scenario_techtree | 84 | §2 v3: every node learnable from fresh via TechTree.learn (exact price once, replay refused, plans/emblems open); all 7 pick-one pairs block both ways; planned nodes unbuyable |
| gametest/ScenarioMerchantGameTests.java | scenario_merchant_reload | 1 | §12/§13 (Codex gap): graded 2-Coin sales, merchant saved + reloaded, a replayed receipt pays 0, the next sale pays exactly 2 |
| gametest/ScenarioHallRoomGameTests.java | scenario_hall_rooms | 3 | §4: Pike Yard, Sword Hall and Rune Hall (no blueprint) hand-built to their checklist survey to a valid L1 building |
| gametest/ScenarioEconomyGameTests.java | scenario_economy_upkeep | 1 | §12: tuned idle upkeep merges split stacks (nothing lost), the first input ends it and is crafted |
| gametest/ScenarioSwitchesGameTests.java | scenario_switches_all_off | 1 | §15: all 13 switches off through the real config on a fresh world: founding, founders and Timber Rights still work; orders/summons DISABLED, extended emblems unsold |
| network/ScenarioGuardMenuGameTests.java | scenario_guard_menu | 1 | §9: Defend the Hearth, add/remove point, loop/ping-pong toggle, one-point route refused, Start Patrol, Clear, second Clear refused |
| conversation/parley/ScenarioParleyGameTests (addendum) | scenario_parley_conversations_off | 1 | §15: conversations off: no parley, no talk window, the raid just comes |
| src/test ScenarioRelationRestartTest, ScenarioMerchantPurseTest | JUnit | 2 | §11 relations/reputation/memory/parley marker survive restart; §12 purse growth 12 + 2 per 5, cap 40 |

## Bugs found by the scenario lane

| # | Where | Bug | Status |
|---|---|---|---|
| SC-1 | settlement/builder/BuildPlanner.java:143 | Typed DEFENSE blueprints (watchtower_timber, watchtower_stone, stone_gatehouse; building_type watchtower) never got the FIT_PLAN step, so a Builder-built watchtower stood with an empty plaque and never registered as a Watchtower (no Archer tower post). BUILDER.md says typed defense pieces register. | FIXED (DEFENSE with a type now fits); regression scenario_blueprint_plan_watchtower_* |
| SC-2 | blueprints fishery_small / fishery_large + BuildPlanner.clearIfNeeded (F_DRAIN) | The fishery has no water and a 4x7 AIR channel at y=0 under the south wall. On land the room is open and has no fishing water; at a lake the Builder DRAINS the channel. So a Builder-built fishery can never register. | ROUTED to the lead (builder / blueprints). Red: scenario_blueprint_plan_fishery_* |
| SC-3 | conversation/parley/RaidParley.tryStart | Started a parley even with conversations or the parley switched off (the QA command path). | FIXED in the tree (gate on raidParley()); regression scenario_parley_conversations_off |
| — | several (test side) | v3 tech tree drift (emblems and plans moved to new nodes, study time); pick-one chains; the guard reach of 8 blocks; Inventory.add empties the stack. | fixture fixes only |
