# Tech tree v3: branch lane briefs

There is one brief per branch (Watch, Logistics, Craft, Commons). Crown is done: it is the worked example.

Each brief is self-contained. Give a lane the **Common rules** section plus its own branch section. The per-node tables with files and effort are in `plan/techtree/IMPLEMENTATION.md`.

**Sunday gate:** every node does something in game.
- Progress: `./gradlew test --tests '*TechTreeSundayGateTest'`. The report line is printed and the test is skipped until the gate is met.
- Hard gate: `HEARTHSTEAD_SUNDAY_GATE=1 ./gradlew test --tests '*TechTreeSundayGateTest'`.
- State at framework handoff: **35/84** (30 A nodes + 5 crown).

---

## Common rules (every branch lane)

**Repo:** `C:\Users\tobia\Hearthstead-Claude\Verk-arbeid-\hearthstead-neoforge`. NeoForge 1.21.1, Java 21, modid `hearthstead`. It is a shared tree and many lanes edit it.

**Build rules:**
- Use a private build dir: `-PhearthsteadBuildDir=C:/Users/tobia/Hearthstead-Claude/build-agent-tt-<branch>` plus `-Dorg.gradle.daemon.idletimeout=300000`.
- Keep the shared tree compiling. Write new files first, then the references to them, and compile in private before you leave a file saved.
- No git commands that change the working tree. Preserve line endings: many files are CRLF, so check with `file X.java`.
- Keep RAM at or below 90%: check `C:\Users\tobia\AppData\Local\Temp\claude\C--Users-tobia-OneDrive-Documents-Claude-apper\4f4a9c83-1374-46cf-9243-77f4104be392\scratchpad\load.ps1`.
- Do not start a Minecraft client or GameTest server. Hand GameTests to the integration captain (agent `ace73d42bf01cec8d`); a full suite takes about 70 s.

**You own exactly two files:**
- `src/main/java/com/hearthstead/settlement/techtree/effects/<Branch>Effects.java`
- `src/main/resources/data/hearthstead/techtree/<branch>.json`

You also edit the gameplay classes that must read your nodes (the hook sites listed below). Keep every edit there small and guarded, so behaviour is unchanged when the node is not learned.

**Never edit these framework files:**
- `EffectRegistry`, `TechEffects`, `TechTree`, `TechTreeData`
- `DevelopmentState`, `Development`
- `TechTreeScreen`, `TechTreeNetwork`
- the other lanes' `*Effects` and `*.json` files

If you need something in them, message the framework lane or the lead.

### How a node becomes real

1. **The gameplay code reads the node.** Pick one:
   - `Development.has(level, settlement, "node_id")`. It is side-effect free and safe in AI ticks. It returns false until the node is learned, including while it is being studied.
   - `TechTree.bonus(level, settlement, KEY)`. This sums a `TechBonus` over learned nodes and returns 0 when none are learned. Declare the key in your `*Effects` class:

     ```java
     public static final TechBonus KEY = TechBonus.percent("watch.x", "+%s%% ...");
     ```

   - A `building`/`profession` claim (see step 2). It needs no gameplay code.
2. **Register the effect** in `register(EffectRegistry r)` in your `*Effects.java`:

   ```java
   r.node("spearmen")
       .building(BuildingType.PIKE_YARD)          // AUTHORITATIVE: only claimants unlock this plan from now on
       .profession(Profession.SPEARMAN)           // AUTHORITATIVE: only claimants unlock this Mayor emblem
       .grandfatheredBy("shield_doctrine");       // pre-v3 saves that owned the old node get this one free, once
   r.node("watchfires")
       .flag("SettlerPanicGoal.shelterSpeed", "Villagers run for shelter 25% faster");  // readBy = the real reader
   r.node("palisade").bonus(PALISADE_HITS, 100);  // shown in the side panel as the fallback text
   r.node("x").onLearn("Line for the panel", (level, settlement, def) -> { ... });      // runs once when learned
   ```

   Never call `EffectRegistry.get()` inside `register`: it would recurse.
3. **Make the text true.** In `<branch>.json`, the node's `offers` must say exactly what steps 1 and 2 do. If you implement a B node's difference, keep `impl: "B"`. If you drop the difference and reword instead, set `impl: "A"`. The lead already approved the honest reductions: make them *feel good*, not a token +1%.
4. **Test it.** Add `settlement/development/TechTree<Branch>GameTests.java` (or your package), batch `techtree_<branch>`. Per non-A node: set up the state, learn it with `TechTree.learn(level, settlement, hearth, "id", state.revision(), null)`, or grant it with `state.learnTech("id")` from the `settlement.development` package, then assert the hook changed. Copy the fixtures from `TechTreeCoreGameTests`.
5. **Check the gate:** run the JUnit `TechTreeDataTest` (must stay green) and `TechTreeSundayGateTest` (your branch should vanish from the Missing list).

### Things to know

- **Claims move knowledge.** Claiming `PIKE_YARD` for spearmen means `shield_doctrine` no longer unlocks it. Always add `grandfatheredBy(old)` so existing worlds keep what they had. If a legacy node loses all of its plans and emblems to claims and has no other effect, it becomes **Planned**. Register its remaining effect: for example, `shield_doctrine` must register its XP bonus and brace flag.
- **Existing GameTests** that relied on an old node unlocking something must still pass. Where your claim moves a plan or emblem, update those fixtures to also learn the new node. Keep such edits minimal and list them in your report.
- **Feature switches still apply.** `[features] battleRoles`, `extendedTrades`, `logisticsUpgrades`, `builder`, `guardCommands` and `gearTiers` all still work. A node whose claimed emblem is hidden by its switch shows as Planned. Keep every hook behind the same switch its system uses today.
- **Legacy-backed nodes** (`"legacy": "node:..."` / `"upgrade:..."`) are stored in the old catalogue and read by the old code (`hasUpgrade`, `hasNode`). Their price comes from the data file on the v3 screen. The old enum price is only used by the legacy screen, which is the kill-switch fallback.
- **Gates:** the kinds are `objective` (a DevelopmentObjective id), `settlers`, `raids_won`, `first_raid`, `owns_any` and `branch_nodes`. A new kind goes in `TechTree.registerGate(kind, evaluator)`: call it from a static block in your own code, and ask the framework lane to add it to `TechTreeDataTest`'s known list.
- **Pick-one** (`excludes`) is enforced by the framework on both screens. Study time (`study_days`) is also automatic.
- **Text:** English lives in the data file. Lang keys `hearthstead.techtree.node.<id>.name|offers|flavor` override it when they exist. Codex may add Norwegian later; do not edit `en_us.json` for node text.

---

## Crown (5): DONE, worked example (framework lane)

`effects/CrownEffects.java`:
- **settlement_charter** and **first_raid_aftermath:** flags. The Village Charter stamps itself when the first raid resolves.
- **town_charter, castle_charter, kingdom_crown:** `MERCHANT_PURSE` +4 each, read in `GoldCoinTrades.ensureOwnedMarket`. Gear Tier 3/4 flags are read by `GearGate` through `node:castle_charter` / `node:kingdom_crown`.
- **Charter gates:** settlers, raids held (`RaidLifecycle.raidsSurvived`), and 2 Village nodes in 3 branches. Study is 1/2/3 days.
- **Tests:** `TechTreeCoreGameTests.townCharterStudiesThenGrantsItsBonus` and `villageCharterStampsItselfAfterTheFirstRaid`.

---

## Watch & Defense (23 nodes): lane "techtree-watch"

**Files you own:** `effects/WatchEffects.java`, `data/hearthstead/techtree/watch.json`. **Test batch:** `techtree_watch`.

**Already done (A, nothing to do):** first_watch, arm_the_watch, defense_plans, fortification, guard_arms_iron (the +6 iron price comes from data), guard_drill (reworded: +15% XP), archer_longbow_drill (reworded: range and draw; pick-one with crossbows is automatic).

**Write these:**

| Node | Handler to write | Reuse / hook site |
|---|---|---|
| commanders_horn (C, lead decision) | Keys stay **free**. When `has("commanders_horn")`: order earshot 48→80 blocks; orders given while an alarm is active last longer (e.g. ×2 expiry); each order plays a horn sound and gives listening soldiers +10% move speed for 10 s. `flag(...)` naming `FieldOrders`. | `command/FieldOrders` (EARSHOT, `issue`), FieldOrderRules expiry, a MobEffect or attribute modifier for the rally |
| barricades (C) | Barricade line max ×2 (`BuildPlanner.BARRICADE` 5→10) and, on the raid warning, every Builder takes barricade jobs first | `builder/BuildPlanner`, `BuildJobs` (warning rush ~l.394), `BuilderWorkGoal` |
| shield_doctrine (B) | Guard in `FieldOrders.bracing` + shield + not flanked takes ×0.7 damage; keep +25% XP. **Must register** a flag and a bonus, because spearmen/longswords claim away its emblems. | `entity/combat/role/RoleWorld.onIncomingDamage` (GUARD branch), `isFlankHit`; `DevelopmentBonuses.guardCombatXp` |
| spearmen (B) | `.building(PIKE_YARD).profession(SPEARMAN).grandfatheredBy("shield_doctrine")` | RoleUnlocks is bypassed automatically once claimed. Update battle_roles fixtures to learn `spearmen`. |
| longswords (B) | `.building(SWORD_HALL).profession(LONGSWORDSMAN).grandfatheredBy("shield_doctrine")` | same |
| rune_mage (B) | `.building(RUNE_HALL).profession(RUNE_MAGE).grandfatheredBy("hearth_doctrine")`; mage cap 1 until high_runes | `RoleHiring.mageCap`, `RuneMageBrain.mageCap` |
| high_runes (C) | `mageCap(highRunes = has("high_runes"))`, ward duration `WARD_DURATION_TICKS_HIGH_RUNES` | `RoleHiring`, `RuneSpell` |
| crossbows (C) | When owned: archer draw ticks ×1.5, arrow damage ×1.4 (optionally a crossbow in hand for looks) | `ArcherAttackGoal` (~l.340/437 draw and range, the arrow damage) |
| watchfires (C) | Shelter move speed ×1.25 during the alarm | `SettlerPanicGoal` (~l.344 `moveTo(path, 1.25)`) |
| palisade (C) | `RaiderBreachGoal.WALL_HITS` ×2 when `BuildSiteSavedData.segmentAt(pos)=="palisade"` | `RaiderBreachGoal` (WALL_HITS=6, l.152) |
| earthworks (C) | Raiders within 3 blocks of any built defense segment get Slowness II (refresh every 20 ticks) | raider tick / `RaidDirector`, `BuildSiteSavedData.segmentAt` |
| stone_walls (C) | `WALL_HITS` ×3 on stone segments. Its gate `owns_any palisade/earthworks` is automatic. | `RaiderBreachGoal` |
| veteran_techniques (C) | Shield bash cooldown ×0.7, combo window +4 ticks, heavy ×1.3 when owned | `GuardMove`, `GuardMeleeGoal`, `[combat]` readers |
| master_armoury (C) | `flag("GearTier DIAMOND/NETHERITE via GearGate", ...)`. GearGate already resolves `node:master_armoury`. | `settlement/gear/GearTier` |
| knights (C ⚑) | Sergeant+ guards: +25% damage and knockback resistance 1.0 (attribute modifiers applied when rank/node changes, or in the damage hook) | `GuardRank`, `GuardMeleeGoal` |
| pike_square (C) | Brace strike ×1.5; braced spearmen get no knockback | `SpearmanCombatGoal`, `RoleCombatRules.BRACE_HOLD_TICKS` |

**Tests:** one GameTest per row above in `techtree_watch`. Make sure the existing batches still pass: `battle_roles_*`, `field_orders_*`, `guard_*`, `archer*`, `builder_core`, `gear_*`.

---

## Logistics (18 nodes): lane "techtree-logistics"

**Files you own:** `effects/LogisticsEffects.java`, `data/hearthstead/techtree/logistics.json`. **Test batch:** `techtree_logistics`.

**Already done (A):** stores_and_roads, paved_roads, courier_satchel, leather_pack, frame_pack, hand_cart, worker_packs, great_storehouse, royal_storehouse, stout_straps (reworded), swift_couriers (reworded), warehouse_racks (reworded).

**Write these:**

| Node | Handler to write | Reuse / hook site |
|---|---|---|
| courier_ledger (C) | Couriers pick URGENT, then HIGH, then NORMAL requests when owned (sort the candidate list by `RequestPriority`) | `CourierWorkGoal` request selection, `settlement/request/RequestPriority` |
| costers_cart (C) | `HaulGear.cartPercent()` 200→300 when owned (cart tier 2; the renderer may scale) | `HaulGear`, `client/render/HandCartRenderer` |
| porters_guild (C, pick-one) | Courier capacity +50 percentage points, walk −10% | `HaulGear.courierCapacity` / `terrainPercent` |
| runners_guild (C, pick-one) | Courier walk +15%; `CourierWorkGoal.RETRY_COOLDOWN_TICKS` ÷2 | `HaulGear.terrainPercent`, `CourierWorkGoal` |
| mule_cart (C ⚑) | Cart 400% and +20% road speed when owned (no animals) | `HaulGear` |
| caravan_routes (C ⚑) | `WorldEventDirector` CARAVAN weight ×3 (and/or a guaranteed caravan every 3 days) when owned | `event/worldevent/WorldEventDirector` |

**Tests:** `techtree_logistics`. Keep `haul_gear_killswitch`, `logistics_day` and `warehouse*` green. All new hooks sit behind `[features] logisticsUpgrades` like HaulGear.

---

## Craft & Production (17 nodes): lane "techtree-craft"

**Files you own:** `effects/CraftEffects.java`, `data/hearthstead/techtree/craft.json`. **Test batch:** `techtree_craft`.

**Already done (A):** timber_rights, cultivated_ground, shore_provisions, guild_doctrine, land_and_harvest, border_wardens, masonry, sharpened_axes (reworded), fishers_nets (reworded).

**Write these:**

| Node | Handler to write | Reuse / hook site |
|---|---|---|
| builders_hut (C) | `.building(BUILDERS_HUT).profession(BUILDER).grandfatheredBy("timber_rights")`. `BuilderUnlocks.owns(BUILDERS_HUT)` keeps working (it reads `isBuildingUnlocked`). **Update builder GameTest fixtures** to learn `builders_hut`. | `settlement/builder/BuilderUnlocks` |
| tannery (C) | `.building(TANNERY).profession(TANNER).grandfatheredBy("craft_and_industry")`. The gate (`owns_any border_wardens/land_and_harvest`) is automatic. | `JobEmblemCatalog` (Tanner entry exists; extendedTrades) |
| carpenter_mason (C) | `.building(CARPENTER/MASON/WEAVER).profession(CARPENTER/MASON/WEAVER).grandfatheredBy("craft_and_industry")` | same |
| craft_and_industry (B) | After the claims: Mine/Smelter/Smithy. **Add** a tool-tier speed hook: iron tool ×0.9 time, diamond+ ×0.8, in Lumberer/Miner/Farmer timers, when owned. Register a bonus/flag. Or reword and set A. | `LumbererWorkGoal:1254`, `MinerWorkGoal.TICKS_PER_BLOCK`, FarmerWorkGoal timers |
| charcoal_kilns (C, pick-one) | Smelter recipes ×0.75 time when owned | `building/Production.ticksFor`, `CrafterWorkGoal.researchEffortMultiplier` |
| deep_mine (C, pick-one) | `MinerWorkGoal.REACH_DOWN` 12→14; +1 drop on ores at 15% | `MinerWorkGoal`, `minedDrops` |
| masters_apprentices (C ⚑) | +50% XP in `SkillLevels.completeUnit` when a co-worker at the same workplace is trade level ≥ 5 | `SkillLevels`, `Employment.employerOf` |
| guild_halls (C ⚑) | Crafted quality rolls Fine+ noticeably more often (e.g. treat as +2 trade levels in the roll) | `settlement/work/CraftedQuality`, `Production:951` |

**Tests:** `techtree_craft`. Keep `trades_unlock`, `builder_*` and the crafting/quality batches green. The extended-trade claims stay behind `[features] extendedTrades`.

---

## Commons & Household (21 nodes): lane "techtree-commons"

**Files you own:** `effects/CommonsEffects.java`, `data/hearthstead/techtree/commons.json`. **Test batch:** `techtree_commons`.

**Already done (A):** home, hospitality.

**Write these:**

| Node | Handler to write | Reuse / hook site |
|---|---|---|
| warm_hearth (B) | Keep −10% night hunger. Dusk WARM gathering gives +3 morale once per day (day key like `TavernBard.LIFT_DAY_KEY`), with a higher WARM chance. | `entity/ai/FreeTimeGoal`, `DevelopmentBonuses.hungerDrainScale` |
| sturdy_beds (B) | Keep +2 bed morale. HOUSE L2 is named "Cottage" and tech-capped (like `DevelopmentBonuses.warehouseTechMaxLevel`). | `building/BuildingLevels`, `BuildingManager.homeQualityFor` |
| feather_quilts (B) | Well Rested: effort refills to 110% on waking unless woken by the alarm (`RaidSleepPolicy`) | `SettlerEntity.tickEffortRefill` |
| hearth_doctrine (B) | `.building(LIBRARY)` (it moves here). Optional: `.bonus(TechTree.STUDY_SPEED, 25)`. | `settlement/Costs` library discount stays |
| bards_songbook (C) | Bard evening lift 5→8; travellers tip 1 Coin (max 3/evening) into the Banner treasury | `settlement/TavernBard`, `TavernGuestPayment` |
| kitchen_and_hall (C) | `.building(KITCHEN).building(DINING_HALL).profession(COOK).grandfatheredBy("hall_and_learning")` | `Schedule:137` meal posting |
| war_feast (C) | At the raid warning, if owned and the Banner treasury holds 12 meals + 4 ale: consume them (all-or-nothing), set a per-raid flag, and that night guards get +10% damage and skip the recovery flee. Announce it. | `raid/RaidTelegraph` warning hook, `GuardMeleeGoal`/`RoleCombat:173`, `GuardRecoveryPolicy` |
| two_storey_houses (C) | HOUSE `residentCapacity` +2 (settlement-aware helper used at every site) | `BuildingManager.capacityOf:242`, `JourneyServerHooks:1634`, `FirstRaidReadiness:387`, `FirstRaidReadinessService:183/1006` |
| wayside_shrine (C, pick-one) | +5 morale to everyone on a raid victory | raid victory resolution (`RaidLifecycle.recordRecurringCadence` caller) |
| alehouse (C, pick-one) | `.building(BREWERY).profession(BREWER).grandfatheredBy("hall_and_learning")`; ale morale 3→5 | `TavernServingEntity:648` |
| infirmary (C) | `.building(INFIRMARY).grandfatheredBy("first_raid_aftermath")`; settlers inside a valid Infirmary heal 1 HP / 40 ticks | `SettlerEntity`, `Building.contains` |
| battle_healer (B) | `.profession(HEALER).grandfatheredBy("first_raid_aftermath")` | `HealerMedicGoal`; update healer fixtures |
| manors (C ⚑) | HOUSE +2 capacity again (stacks with Townhouses) and +2 morale target for residents of a level-3 house | same capacity helper, `homeQualityFor` |
| great_tavern (C) | `WorldEventDirector` MINSTRELS and CARAVAN weights ×2 | `event/worldevent/WorldEventDirector` |
| harvest_feast (C ⚑) | Every 4 in-game days at dawn, if owned and stores hold 48 bread + 16 ale: consume, +15 morale, and +10% work speed until the next dawn (shared work-speed hook; `Mayor.workSpeed` has no callers yet) | `Production.ticksFor`, the Lumberer/Miner/Farmer timers |
| hall_of_heroes (C ⚑) | Death/mourning morale loss halved; newly recruited settlers +5 morale | `ambient/LivingVillage`, recruitment |
| hall_and_learning (C, lead approved) | New recruits arrive +1 level in their assigned trade. It loses KITCHEN/LIBRARY/DINING_HALL/BREWERY/COOK/BREWER to the claims above, so it **must** register its own effect. | `RecruitmentQuote` / `SkillLevels` |
| cathedral (C ⚑, pick-one) | +10 morale to everyone on a victory (plus a second Blessing seal only if `BlessingState` allows it cheaply) | victory hook, `state/BlessingState` |
| hall_of_revels (C, pick-one) | Tavern guest prices ×2 (raise the `canPayOrder` cap); bard lift ×2 | `TavernGuestPayment`, `TavernBard` |

**Tests:** `techtree_commons`. Keep the tavern, readiness (`FirstRaidReadiness*`), healer and trades_unlock batches green.
