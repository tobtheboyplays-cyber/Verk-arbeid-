# Tech tree v3: implementation map (Sunday plan)

Written by the tech tree framework lane on 26 Sep. The node data lives in `src/main/resources/data/hearthstead/techtree/<branch>.json`, one file per branch. Each branch lane owns its own file. The framework and its rules are summarised in section 1. How to write an effect is in `BRANCH-BRIEFS.md`.

## Classes

| Class | Meaning | What the lane does |
|---|---|---|
| **A** | Exists and works, and the node text matches the code | Nothing. It is already learnable through its legacy entry. |
| **B** | Exists but differs from the design | Implement the difference, or reword `offers` and set `impl: "A"` |
| **C** | New, but can be built from existing systems (numeric bonus, unlock, flag read by existing code) | Register handlers in `<Branch>Effects.java` and add the hooks |
| **D** | Needs real new gameplay | Not before Sunday. It stays **Planned** in game and cannot be learned. |

Totals (84 nodes):
- **Raw design:** about 21 A, 32 B, 14 C, 17 D (from the two code surveys).
- **Sunday plan:** 30 A, 12 B, 42 C, 0 D.
- **Lead decisions (26 Sep):** every honest reduction is accepted and must feel good, not a token +1%. hall_and_learning becomes "+1 trade level for new recruits". commanders_horn keeps the keys free and adds reach, longer orders and a horn rally. We get there by replacing D promises with the closest honest version, flagged ⚑ for the lead.

Every ⚑ `offers` text in the data already says only what the Sunday version does.

**No fake promises.** A node with no registered effect shows as **Planned** in game ("not in the game yet") and cannot be learned. Its offers text is honest because nobody can get it.

Effort:
- **S** is under 2 h.
- **M** is 2–6 h.
- **L** is over 6 h, and is not planned for Sunday.

## 1. Framework (done by this lane)

| Piece | Where | What it does |
|---|---|---|
| Node data | `data/hearthstead/techtree/{tree,crown,watch,logistics,commons,craft}.json` | Holds, per node: id, tier, type, x/y, coins + goods (`#tag` lines), `study_days`, requires, excludes, structured `gates`, `auto`, `legacy` (`node:`/`upgrade:`), `impl` class, and English text (name/offers/details/flavor). It is read from the jar on both sides (`TechTreeData`). Generated once by `plan/techtree/gen_nodes.py` + `honest.json`. **After generation the branch files are the source of truth.** |
| Effects | `settlement/techtree/EffectRegistry`, `TechEffect`, `TechBonus`, `effects/<Branch>Effects.java` | Builder: `r.node(id).building(T).profession(P).bonus(KEY, n).flag(readBy, line).onLearn(line, hook).grandfatheredBy(old...)`. A `building`/`profession` claim is **authoritative**: once claimed, only claimants unlock that plan/emblem (legacy lists and `RoleUnlocks` step aside). Legacy-backed nodes get an automatic `Legacy` effect for any plans and emblems nobody claimed. |
| Queries | `Development.has(level, settlement, "id")`, `TechTree.bonus(level, settlement, KEY)` | Side-effect free, safe in AI ticks, false on quarantine or while studying. Old spellings are accepted (`TechIdMigration`). |
| Learning | `TechTree.learn(...)` | Behind the revision fence. Checks, in order: learned/studying → implemented → **excludes** → requires (+ legacy load prerequisites) → gates (legacy quests keep their baselines) → price. Coins + goods are paid **exactly once**, all-or-nothing (`Development.pay`), then the node is recorded in its legacy catalogue or in `DevelopmentState.techIds`. |
| Pick-one | `TechTree.excludedBy` | Also enforced on the old `purchaseNode`/`purchaseUpgrade` paths (`DOCTRINE_EXCLUSIVE`). A choice being studied counts as taken. |
| Study timer | `DevelopmentState` `TechStudies`, `TechTree.tick` | `study_days` × 24 000 day-time ticks × `[techtree] studyTimeScale`. It advances only while a player is in the level (sleep skips ahead) and adds `STUDY_SPEED` %. Paid at the start; learned at the end. |
| Auto nodes | `auto: true` | `first_raid_aftermath` (Village Charter) stamps itself once its requires + first-raid gate are met. There is no Learn click. |
| Save compat | `DevelopmentState` | New NBT `TechNodes`, `TechStudies` and `TechGrandfathered` are optional and additive, with no schema bump. Unknown ids are kept, never quarantined. `grandfatheredBy` grants moved knowledge once to pre-v3 saves. Five legacy prerequisites were loosened to `first_raid_aftermath` (fortification, border_wardens, land_and_harvest, craft_and_industry, hall_and_learning); loosening can never quarantine a save. |
| Co-op | `TechTreeNetwork` | One tree per settlement. Any player who may build within 8 blocks of the Banner can learn. Every player with the tree open gets the new state pushed (`PayloadSend`). |
| Screen | `client/screen/TechTreeScreen` | Radial graph from data x/y, with arrows, dashed pick-one lines, state rings, eased pan/zoom to the cursor, hover path highlight and a side panel (cost have/need, milestones, requires with jump links, pick-one warning, "In game" effect lines, Research button with the disabled reason). Banner opens it when `[techtree] enabled=true`; `false` falls back to the old `DevelopmentScreen`. |
| Kill switch | `[techtree] enabled`, `studyTimeScale` | Off = old screen, no study clock, no auto stamps. Learned nodes and their effects are kept. |
| GearGate | `settlement/gear/GearGate` | `node:<id>` now also resolves v3-only ids (castle_charter, master_armoury, kingdom_crown), so Gear Tiers 3/4 can unlock. |
| Tests | `TechTreeDataTest` (JUnit, green), `TechTreeSundayGateTest` (gate), `TechTreeCoreGameTests` (batch `techtree_core`) | See section 7. |

## 2. Crown (5): the framework lane implements these as the worked example

| Node | Class | Effect in code terms | Files | Tests | Effort |
|---|---|---|---|---|---|
| settlement_charter | B | Root seal; opens ring 1 (structural). Flag `TechTree requires root`. `shelter` stays hidden and always learned. | CrownEffects | data test | S |
| first_raid_aftermath | B | `auto` stamp when the first raid resolves (framework). Opens ring 2, Gear Tier 1 Mail (GearTier `node:first_raid_aftermath`, already live). Flag. The Healer emblem moves to `battle_healer` (commons). | CrownEffects | techtree_core stamp test | S |
| town_charter | C | Gates: 15 settlers, 3 raids held, 2 Village nodes in 3 branches. 20 C + goods, 1 day study. Bonus `crown.merchant_purse` +4 read by `GoldCoinTrades` purse. | CrownEffects, GoldCoinTrades | gate + study test | M |
| castle_charter | C ⚑ | Gates: 25 settlers, 6 raids (the "stone walls around the Banner" check is dropped; `stone_walls` is already required). Purse +4. Gear Tier 3 with master_armoury (GearGate). The Keep building is dropped (D). | CrownEffects | — | S |
| kingdom_crown | C ⚑ | Gates: 40 settlers, 12 raids (outpost/alliance dropped: phase 6). Purse +4. Gear Tier 4 with master_armoury. "Two Blessings per victory" and the rival kingdoms are dropped. | CrownEffects | — | S |

## 3. Watch & Defense (23)

| Node | Class | Effect in code terms | Files to touch | Tests | Effort |
|---|---|---|---|---|---|
| first_watch | A | Barracks + Guard (legacy) | — | — | — |
| guard_drill | A (reworded) | +15% guard XP (`DevelopmentBonuses.guardCombatXp`). Sparring is L, so it was dropped from the text. | — | — | — |
| arm_the_watch | A | Watchtower + Archer + Tower Post | — | — | — |
| commanders_horn | C (lead decision) | Keys stay FREE for everyone. When owned: order earshot 48→80 blocks (`FieldOrders` EARSHOT), orders issued during an alarm last longer, and each order plays a horn and gives listeners +10% move speed for 10 s. | WatchEffects, command/FieldOrders | field_orders test with/without node | M |
| barricades | C | When owned: barricade line max ×2 (`BuildPlanner.BARRICADE` max 5→10), and on the raid warning every Builder takes barricade jobs first (`BuildJobs` rush already exists; extend to all builders). | WatchEffects, builder/BuildPlanner, BuildJobs | builder_core variant | M |
| defense_plans | A | Palisade lines (legacy upgrade) | — | — | — |
| shield_doctrine | B | Keeps +25% XP. **Add:** in `RoleWorld.onIncomingDamage`, a GUARD in `FieldOrders.bracing` with a shield and not flanked takes ×0.7. After spearmen/longswords claim their emblems it has no legacy effect left, so it **must** register a flag or bonus. | WatchEffects, entity/combat/role/RoleWorld | guard_control test | S–M |
| spearmen | B | Claims `PIKE_YARD` + `SPEARMAN`, `grandfatheredBy("shield_doctrine")` | WatchEffects | battle_roles_spear_brace grant | S |
| guard_arms_iron | A | Legacy; the +6 iron price comes from data (v3 path) | — | — | — |
| archer_longbow_drill | A (reworded) | Legacy range/draw. Volley was dropped from the text. Pick-one enforced by the framework. | — | techtree_core excludes | — |
| crossbows | C | Bonus/flag read in `ArcherAttackGoal`: draw ticks ×1.5, arrow damage ×1.4 | WatchEffects, ArcherAttackGoal | archer test | M |
| fortification | A | Armoury + Armourer + Fletcher (legacy; `extendedTrades`) | — | — | — |
| watchfires | C | `SettlerPanicGoal` shelter move speed ×1.25 when owned | WatchEffects, SettlerPanicGoal | alarm_bell | S |
| palisade | C | `RaiderBreachGoal.WALL_HITS` ×2 on `segmentAt=="palisade"` | WatchEffects, RaiderBreachGoal | raid breach test | S–M |
| earthworks | C | Raiders within 3 blocks of a built defense segment get Slowness II (`BuildSiteSavedData.segmentAt`) | WatchEffects, raider tick | test | M |
| stone_walls | C | `WALL_HITS` ×3 on stone segments. Arrow slits dropped (D). | WatchEffects, RaiderBreachGoal | test | S–M |
| veteran_techniques | C | Settlement-aware: shield bash cooldown ×0.7, combo window +4 ticks, heavy ×1.3 (`GuardMove` / `[combat]` readers) | WatchEffects, GuardMeleeGoal | guard test | M |
| longswords | B | Claims `SWORD_HALL` + `LONGSWORDSMAN`, `grandfatheredBy("shield_doctrine")` | WatchEffects | battle_roles grant | S |
| rune_mage | B | Claims `RUNE_HALL` + `RUNE_MAGE`, `grandfatheredBy("hearth_doctrine")`. Mage cap 1 until high_runes. | WatchEffects, RuneMageBrain/RoleHiring | mage tests grant | S–M |
| master_armoury | C | Flag; GearTier DIAMOND/NETHERITE already name `node:master_armoury` (GearGate now resolves it) | WatchEffects | gear test | S |
| high_runes | C | `RoleHiring.mageCap(highRunes=has)`, ward `WARD_DURATION_TICKS_HIGH_RUNES` | WatchEffects, RoleHiring, RuneSpell | mage test | S |
| knights | C ⚑ | **Mounted knights (horses, mounted AI) are L.** Honest version: Sergeant+ guards +25% damage and knockback resistance 1.0. | WatchEffects, GuardMeleeGoal/attributes | test | M |
| pike_square | C | Brace strike ×1.5 in `SpearmanCombatGoal`; braced spearmen get no knockback | WatchEffects, SpearmanCombatGoal | spear test | S |

## 4. Logistics (18)

| Node | Class | Effect in code terms | Files | Tests | Effort |
|---|---|---|---|---|---|
| stores_and_roads | A | Warehouse + Courier | — | — | — |
| stout_straps | A (reworded) | +2 carry. Pickup batching dropped (D). | — | — | — |
| paved_roads | A | Road speed (HaulGear) | — | — | — |
| courier_satchel / leather_pack / frame_pack / hand_cart / worker_packs | A | HaulGear sack/cart tiers (legacy upgrades) | — | — | — |
| swift_couriers | A (reworded) | +10% courier speed. The urgent jog was dropped. | — | — | — |
| warehouse_racks | A (reworded) | WarehouseLevels L3 recognition. Labelled shelves dropped. | — | — | — |
| courier_ledger | C | `CourierWorkGoal` picks URGENT/HIGH `RequestPriority` first when owned. Per-building priority UI dropped. | LogisticsEffects, CourierWorkGoal | logistics_day | M |
| costers_cart | C | `HaulGear.cartPercent()` 200→300 when owned | LogisticsEffects, HaulGear | haul gear test | S–M |
| porters_guild | C | Pick-one. Courier capacity +50 points, speed −10% (`HaulGear.terrainPercent`) | LogisticsEffects, HaulGear | test | S |
| runners_guild | C | Pick-one. Courier speed +15%, `RETRY_COOLDOWN_TICKS` ÷2 | LogisticsEffects, HaulGear, CourierWorkGoal | test | S |
| great_storehouse | A | L4 recognition (data requires town_charter) | — | — | — |
| mule_cart | C ⚑ | Cart 400%, roads +20%; no mules (animals/Stable are L) | LogisticsEffects, HaulGear | test | S–M |
| royal_storehouse | A | L5 recognition | — | — | — |
| caravan_routes | C ⚑ | **Outposts don't exist.** Honest version: `WorldEventDirector` CARAVAN weight ×3 when owned. | LogisticsEffects, event/worldevent | event test | M |

## 5. Craft & Production (17)

| Node | Class | Effect in code terms | Files | Tests | Effort |
|---|---|---|---|---|---|
| timber_rights | A | Lumber Camp + Lumberer (the Builder moves to builders_hut) | — | — | — |
| builders_hut | C | Claims `BUILDERS_HUT` + `BUILDER`, `grandfatheredBy("timber_rights")`. `BuilderUnlocks.owns(BUILDERS_HUT)` keeps working through `isBuildingUnlocked`. | CraftEffects | builder_core fixtures learn it | S |
| sharpened_axes | A (reworded) | Felling −10%. Grindstone dropped; replanting is base behaviour. | — | — | — |
| cultivated_ground / shore_provisions | A | Farm / Fishery | — | — | — |
| fishers_nets | A (reworded) | Cast −10%. Net buoy dropped. | — | — | — |
| guild_doctrine | A | Sawmill + Sawyer | — | — | — |
| land_and_harvest | A | Mill/Pasture/Bakery/Butcher (legacy prerequisite loosened to Village) | — | — | — |
| border_wardens | A | Hunters' Lodge (moved to Craft; prerequisite loosened) | — | — | — |
| tannery | C | Claims `TANNERY` + `TANNER`, `grandfatheredBy("craft_and_industry")`. Pedlar's Sack dropped. | CraftEffects | trades_unlock variant | S |
| craft_and_industry | B | After tannery/carpenter_mason claim theirs: Mine, Smelter, Smithy. **Add** the tool-tier speed hook (`toolSpeedScale`: iron ×0.9, diamond ×0.8 in Lumberer/Miner/Farmer timers), or reword. | CraftEffects, work goals | test | M |
| masonry | A | Stone lines (legacy upgrade) | — | — | — |
| carpenter_mason | C | Claims `CARPENTER`, `MASON`, `WEAVER` + professions, `grandfatheredBy("craft_and_industry")` | CraftEffects | test | S |
| charcoal_kilns | C | Pick-one. Smelter `Production.ticksFor` ×0.75 | CraftEffects, Production | test | S |
| deep_mine | C | Pick-one. `MinerWorkGoal.REACH_DOWN` 12→14, +1 ore drop at 15% on ores | CraftEffects, MinerWorkGoal | test | S |
| masters_apprentices | C ⚑ | Pairing/follow/inheritance is L. Honest version: +50% XP in `SkillLevels.completeUnit` when a co-worker at the same workplace is level ≥ 5. | CraftEffects, SkillLevels | test | M |
| guild_halls | C ⚑ | Extra worker slots touch 6+ sites. Honest version: a `CraftedQuality` roll bonus (Fine+ more often). | CraftEffects, CraftedQuality | test | S–M |

## 6. Commons & Household (21)

| Node | Class | Effect in code terms | Files | Tests | Effort |
|---|---|---|---|---|---|
| home | A | House + Lodging | — | — | — |
| warm_hearth | B | Keeps −10% night hunger. **Add:** dusk WARM gathering in `FreeTimeGoal` gives +3 morale once per day. Or reword to the hunger line and set A. | CommonsEffects, FreeTimeGoal | test | S |
| hospitality | A | Tavern + Trading Post | — | — | — |
| sturdy_beds | B | Keeps +2 bed morale. **Add:** HOUSE L2 named "Cottage" and tech-capped like `warehouseTechMaxLevel`. Or reword and set A. | CommonsEffects, BuildingLevels | test | M |
| feather_quilts | B | Keeps +20% sleep energy. **Add:** Well Rested: effort 110% at wake unless woken by the alarm. | CommonsEffects, SettlerEntity.tickEffortRefill | test | S–M |
| hearth_doctrine | B | Claims `LIBRARY` (moves from hall_and_learning), keeps Architect's Study/Scholar. `STUDY_SPEED` bonus +25 is optional. | CommonsEffects | test | S |
| bards_songbook | C | `TavernBard` evening lift 5→8, travellers tip 1 C (max 3/evening) | CommonsEffects, TavernBard | test | S–M |
| kitchen_and_hall | C | Claims `KITCHEN`, `DINING_HALL`, `COOK`, `grandfatheredBy("hall_and_learning")`. Cravings dropped. | CommonsEffects | trades_unlock variant | S |
| war_feast | C | On the raid warning, if owned and the Banner holds 12 meals + 4 ale: consume them, set a per-raid flag, and guards get +10% damage and skip recovery-flee that night. No UI needed. | CommonsEffects, RaidTelegraph hook, GuardMeleeGoal | test | M |
| two_storey_houses | C | Settlement-aware `residentCapacity` +2 for HOUSE (sites: `BuildingManager.capacityOf`, `JourneyServerHooks`, `FirstRaidReadiness*`) | CommonsEffects, BuildingManager | test | M |
| wayside_shrine | C | Pick-one. +5 morale to all on raid victory | CommonsEffects, raid victory hook | test | S |
| alehouse | C | Pick-one. Claims `BREWERY` + `BREWER` (from hall_and_learning, `grandfatheredBy("hall_and_learning")`); ale morale 3→5 | CommonsEffects, TavernServingEntity | test | S |
| infirmary | C | Claims `INFIRMARY` (from RoleUnlocks), `grandfatheredBy("first_raid_aftermath")`; settlers inside heal 1 HP / 40 ticks | CommonsEffects, SettlerEntity | test | S–M |
| battle_healer | B | Claims `HEALER`, `grandfatheredBy("first_raid_aftermath")` | CommonsEffects | healer test grant | S |
| manors | C ⚑ | Honest version: HOUSE +2 capacity again, plus +2 morale target for residents of a level-3 house | CommonsEffects | test | M |
| great_tavern | C | `WorldEventDirector` MINSTRELS/CARAVAN weights ×2 | CommonsEffects, event/worldevent | test | S–M |
| harvest_feast | C ⚑ | Every 4 days at dawn, if owned and there are 48 bread + 16 ale in stores: consume, +15 morale, and a `festivalUntil` flag read by a shared work-speed hook (+10%) | CommonsEffects, Mayor.workSpeed callers | test | M–L |
| hall_of_heroes | C ⚑ | Mourning morale loss halved (`LivingVillage`); recruits +5 morale | CommonsEffects, LivingVillage, recruitment | test | M |
| hall_and_learning | C ⚑ (lead approved) | New recruits arrive +1 level in their trade (recruitment / SkillLevels). Old plans move out (kitchen_and_hall, hearth_doctrine, alehouse). | CommonsEffects, recruitment | test | M |
| cathedral | C ⚑ | Pick-one. +10 morale to all on victory (a second Blessing seal only if the blessing code allows it cheaply) | CommonsEffects | test | S–M |
| hall_of_revels | C | Pick-one. `TavernGuestPayment` prices ×2 (cap raised), bard lift ×2 | CommonsEffects, TavernGuestPayment, TavernBard | test | M |

## 7. Tests and the Sunday gate

- **`TechTreeDataTest` (JUnit, green):**
  - 84 nodes / 5 branches;
  - ids unique;
  - every requires/excludes/gate id valid;
  - pick-ones symmetric (exactly the 7 designed pairs);
  - no cycles, and every node reaches the Banner;
  - distinct x/y;
  - costs resolve (Coins first);
  - gate kinds known;
  - legacy mapping valid, with legacy prerequisites implied by requires and legacy quests equal to data gates;
  - old names migrate (`shelter`, enum names, `node:`/`upgrade:` prefixes, v2 draft ids).
- **`TechTreeSundayGateTest`:** every node has a registered effect, `impl` is filled, and B/C/D nodes have a non-legacy effect.
  - Normal suite: reported and skipped (stays green).
  - Gate run: `HEARTHSTEAD_SUNDAY_GATE=1 ./gradlew test --tests '*TechTreeSundayGateTest'`
- **GameTests, batch `techtree_core`:**
  - learn → pay once → effect active; a stale replay pays nothing;
  - excludes block both ways, including the legacy path;
  - the co-op second player's snapshot shows the learned node;
  - the study timer finishes;
  - the Village Charter auto-stamps;
  - a pre-v3 save migrates.
- **Each branch lane** adds a GameTest per non-A node in its own batch `techtree_<branch>`: learn (via `TechTree.learn` or a grant seam), then assert the hook changed.

## 8. Flags for the lead (⚑)

Each flag is a design promise that is not realistic before Sunday. The node text in the data already says only the honest version. The lead decides whether the text stays or the node stays Planned.

1. castle_charter: no Keep, no "stone walls around Banner" check.
2. kingdom_crown: no outposts, alliance or rival kingdoms; no second Blessing.
3. knights: no horses; a guard damage/knockback perk instead.
4. mule_cart: no mules.
5. caravan_routes: no outposts; more caravan events instead.
6. masters_apprentices: an XP boost only.
7. guild_halls: a quality roll only.
8. manors: capacity + morale only.
9. harvest_feast: a morale + work-speed festival without decorations/crowds.
10. hall_of_heroes: grief softening only.
11. hall_and_learning (Families & School): DECIDED (lead, 26 Sep): "+1 trade level for new recruits", learnable.
12. cathedral: morale only.
13. commanders_horn: DECIDED (lead, 26 Sep): the keys stay free; the node adds 80-block reach, longer orders and a horn rally.

Rewordings that drop design hooks (the numbers stay):
- guard_drill: sparring.
- archer_longbow_drill: volley.
- stout_straps: batching.
- swift_couriers: jog.
- warehouse_racks: labelled shelves.
- sharpened_axes: grindstone.
- fishers_nets: net buoy.
- tannery: Pedlar's Sack.
