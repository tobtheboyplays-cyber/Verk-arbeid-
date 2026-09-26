# 02: Save/load robustness review (CLOUD-03)

**Source reviewed:** branch `claude/pensive-lamport-1i69ux` at `f0fa348`, whose `hearthstead-neoforge/src` is identical to `6dac68f`. The two fixes marked *fixed on branch* came later, in `5e2e036` and `f35d20b`.

All paths are relative to `hearthstead-neoforge/src/main/java/com/hearthstead/`.

**Method:**
- Three read-only reviewers each took a disjoint slice: settlement core and ledgers; raid, event, builder and relation data; entities and block entities.
- I re-read every finding marked **verified** at its cited lines. Findings marked **reported** have not been re-read line by line.
- Everything was checked against the vanilla/NeoForge 1.21.1 sources in the Gradle cache.

**Stale areas** (newer local work I cannot see):
- Courier: `RequestLedger`
- Builder: `BuildSiteSavedData`
- New tech tree: `Development` nodes
- Visitors: recruitment and conversation

Re-check these against the local tree.

## Two facts about vanilla behaviour that drive most findings

1. **A throwing `SavedData` loader is a silent reset.** `DimensionDataStorage.readSavedData` catches the exception and returns null, and `computeIfAbsent` then builds an **empty** instance. The first `setDirty` afterwards overwrites the original file. So a quarantine only helps if it keeps the raw tag and never becomes dirty.
2. **Chunks and `SavedData` are saved separately.** Order: player files, then `SavedData`, then chunks, with chunks also saved when they unload. A crash between the two, or a kill with no final save, can leave them out of step.

## Executed evidence (kept separate from source review)

| Test | Result |
|---|---|
| **Crash/restart A.** Throwaway NeoForge 21.1.248 server, flat world, project's own restart fixture (`/hearthstead foodrestartqa prepare`: 1 settlement, 1 warehouse, 1 employed settler). Then `save-all flush`, then **`kill -9`**, then restart. | After restart: overworld `STATE_LOAD_SUMMARY reason=buildings_1_settlers_1`, and `execute if entity @e[type=hearthstead:settler]` gives **count 1**. No duplicate, no loss, no load error. |
| **Crash/restart B.** Same setup, but **`kill -9` with no save.** | After restart: `buildings_0_settlers_0` and **no** settler entity. A consistent loss of the unsaved period, with no orphaned record and no orphaned entity. |
| GameTest controls for the two fixes below | Both **fail on the old code** and pass with the fix (details under S-01 and S-02). |

The fixture needs a system-property token, a world named `food_restart_world` and an owned-instance marker file, so it is inert on a normal server. Each run used a fresh world in my sandbox. Nothing was run against any real save.

## Findings

### P1: fixed on this branch (main to compare before integrating)

#### S-01 (verified, fixed in `5e2e036`): after 512 planted seeds, farming and lumbering stop for good

- **Trigger:** normal farming.
  - `WorkerProvenanceService.supplyOneSeedAt` opens one `FARM_PLANT` row per seed (`inputCount = 1`, one planned plot).
  - Planting completes the row as `WORK_COMMITTED` with no output (`settlement/work/WorkerProvenanceService.java:848`, `:1535`).
- **Code:** `pruneForCapacity` removed only fully deposited `OUTPUT_COMMITTED` or `RETIRED` rows (`settlement/work/WorkerProvenanceSavedData.java:541-556`), so spent plantings were never removed.
  - `MAX_ACTIONS = 512` (`:35`) applies **per dimension**, across all settlements.
  - At the cap, `add()` returns false (`:470`), which blocks farm plant, farm harvest and lumber authority.
  - The state is persisted, so a restart does not help.
- **Fix:** spent plantings (no output, no pending operation) are now prunable, oldest first, which the `LinkedHashMap` order guarantees.
- **Evidence:** GameTest `spentPlantRowsNeverFillTheActionTable`. The control fails at seed 513. With the fix, worker_provenance, farmer, lumber and first_raid pass **100/100**. First-raid readiness still sees its planting evidence.

#### S-02 (verified, fixed in `f35d20b`): a creeper or TNT beside the Banner disbands the whole settlement

- **Code:**
  - `block/HearthBlock.java:224-233`: `onRemove` calls `SettlementManager.disbandAt` for **any** removal.
  - `settlement/SettlementManager.java` `disbandAt` removes the settlement record (buildings, ledgers, research) and unbinds only loaded members.
  - The Banner had `strength(3.5F)`, which is blast resistance 3.5 (`registry/ModBlocks.java:24`).
- **Fix:** `.explosionResistance(1200.0F)`. Hardness and player breaking are unchanged.
- **Evidence:** GameTest `anExplosionBesideTheBannerDoesNotDisbandTheSettlement` (TNT-strength blast one block away). The control fails. With the fix, bughunt, banner, hearth, founding and raid pass **173/173**.
- **Still open:** a deliberate player break also deletes owed rows and strands settlers. See S-09.

### P1: open (production fixes proposed, not made)

#### S-03 (verified; recruitment may be a stale lane): a traveler killed on the road locks recruitment forever

- **Code:** `entity/SettlerEntity.java:3951-3953` runs `if (settlementId != null) { SettlementManager.onSettlerDied(...) }`.
  - A traveler has only `targetSettlementId` (`markTraveler`, `:1468-1474`), so the traveler branch of `onSettlerDied` (`settlement/SettlementManager.java:1401-1415`, `left(ENTITY_GONE)`) never runs.
  - Afterwards, `tickTravelingTraveler` and `tickWaitingTraveler` treat a missing entity as "unloaded, never terminalize" (`:423-427`, `:598-602`).
  - If the Hearth tick sees the corpse instead, the transaction moves to `QUARANTINED`, which stays inert (`:373-377`).
- **Trigger:** a zombie or raider kills an arriving or departing traveler at night. Zombies hunt settlers (`event/CommonEvents.java:152`, reported).
- **Consequence:** that settlement can never recruit again.
- **Smallest fix:** `if (settlementId != null || isTraveler()) SettlementManager.onSettlerDied(...)`.

#### S-04 (verified): a settler's main-hand tool, and a non-cargo off-hand item, vanish on death

- **Code:** `die()` handles each slot as follows (`entity/SettlerEntity.java:3923-3954`):
  - armor goes home through `GuardRank.clearEquipment` (`entity/GuardRank.java`, armor slots only);
  - the bag and flagged off-hand cargo are handled by `transferTerminalCargo`;
  - `MAINHAND` and any other off-hand item have no handling.
- **Why they are lost:** vanilla drops hand items only with `handDropChances` (0.085), and only when the killer was a player. The captain's main hand drop chance is 0 (`entity/combat/captain/CaptainService.java:185`).
- **What is lost:** tools that `EquipmentRequests` took from real chests (reported: `:347/439/536`) vanish when a raider or mob kills a guard, lumberer, miner, hunter or fisher. This breaks the chest-truth invariant.
- **Smallest fix:** in `transferTerminalCargo`, stage `MAINHAND` and a non-cargo `OFFHAND` through `DeferredItemMaterializationSavedData` exactly like bag slots, then clear them. Or return them through `GuardRank.depositToStores`.

### P2

#### S-05 (mechanism verified in part): a failed `SettlementSavedData` load erases every settlement, and Banners found new ones

- **Vanilla side:** a throwing `SettlementSavedData.load` is a reset (fact 1 above).
  - Throw points include `DataVersionException` for a newer save (`settlement/SettlementSavedData.java:74-98`, `:246-257`).
  - Also `rt.getUUID("EntityId")` without `hasUUID` in the roster (`settlement/Settlement.java:642`, verified).
  - Also `getUUID` on `Id` (`Settlement.java:593`; `Building.java:278`, `:297`, reported).
- **Hearth side:** a Hearth whose settlement is missing clears its id (`block/HearthBlockEntity.java:81-86`, verified) and then founds a new settlement with new founders.
- **Trigger:** rolling the server jar back to an older build during the week, or a corrupted file.
- **Consequence:** the empty data is written over the original on the next dirty.
- **Smallest fixes:**
  - catch in `load`, keep `tag.copy()`, and return a quarantined read-only instance whose `save` writes the raw tag back; the pattern already exists in `event/worldevent/WorldEventSavedData.java:139-144` and `settlement/work/TavernOrderDeathSavedData.java`;
  - guard the roster `getUUID`;
  - never let the Hearth clear an existing id on its own.
- **Operational rule for Sunday:** pin one jar and never downgrade. The hourly backups in `tools/deploy` are the recovery path.

#### S-06 (reported): quarantines that destroy their own data

A quarantine should keep the raw tag and write it back unchanged, as `PendingPlayerDeliveryLedger` (`:270-274`, `:329`), `WorldEventSavedData`, `VillageMoment`, `MerchantDemand` and `GoblinTheft` already do. These don't:

| Store | Behaviour | Code |
|---|---|---|
| `Development` | Future schema or unknown ids give an empty state, and `Quarantined=true` is persisted. Quest counters keep marking it dirty, so unlocks and pending emblem deliveries are overwritten and every purchase answers QUARANTINED. Stale area (tech tree). | `settlement/development/DevelopmentState.java:460-469`, `:384`, `:401`, `:580`; `DevelopmentQuests.java:401-404` |
| `WorkerProvenanceSavedData` | `quarantine()` calls `setDirty()` during `load`, so the partial rows and a sticky flag are written back. Farming and lumbering stay off. | `settlement/work/WorkerProvenanceSavedData.java:558-562`, `:290-326` |
| `RequestLedger` | A strict-read failure, even one bad *history* row, drops all of that settlement's rows. The next unrelated dirty makes it permanent. Stale area (courier). | `settlement/request/RequestLedger.java:182-256`, `:269-281`; root at `RequestLedgerSavedData.java:86-89` |
| `EmploymentAuthorizationLedger` | Writes empty lists when quarantined and re-quarantines from the flag, so Fire and emblem hires are refused for good. | `:170`, `:181`, `:204` |

Only a malformed file, or a jar change that renames ids, can trigger any of these. That is why they are P2, not P1.

#### S-07 (reported): items can duplicate or be lost across an unclean kill

- **(a) Deferred items.** Deferred item rows live in `SavedData` while the source block lives in the chunk. With a materialize delay of up to 100 ticks, a crash between the two saves can lose or duplicate one stack (`entity/ai/GroundCollectionSession.java:179-182`, `settlement/DeferredItemMaterializationSavedData.java:345-352`).
- **(b) Player deliveries.** `PendingPlayerDeliveryLedger.anyOnlinePlayerOwns` checks only online inventories (`:359`), so an item stored before logout and then a crash can be delivered again.
- **Mitigation:** always stop with `stop` (the deploy kit's wrapper does), never `kill -9`, and run `save-all flush` before any forced stop. Crash/restart B above shows that a plain unsaved crash simply loses the period since the last save.

#### S-08 (reported): the Build Plan in a plaque is lost when anything but a player destroys it

`PlaqueBlock.onDestroyedByPlayer` returns the plan, but there is no `onRemove` override and the loot table drops only the plaque. A creeper therefore deletes the plan. **Fix:** conserve the plan in `onRemove`.

#### S-09 (verified in part): disbanding strands settlers in unloaded chunks and deletes owed rows

- `disbandAt` unbinds only `loadedMembers` (verified). Unloaded settlers keep a dead `settlementId`, `reRegisterWithSettlement` does nothing for them, and nothing ever calls `unbind()` later.
- Pending emblem returns, raid coin rewards and blessing deliveries are embedded in the removed record (reported).
- Since S-02, only a deliberate player break reaches this path.
- **Fix:** a periodic `unbind()` when a loaded settler's settlement is missing, and move pending rows to a world-level outbox before removal.

### P3 (hardening; one line each)

- **Raid soft-lock:** a won raid cannot close while `RaidCoinRewards` is quarantined, because the dawn retreat check comes after the early return (`settlement/raid/RaidDirector.java:1528-1532`, `:2448-2459`).
- **No reset path:** permanent BLOCKED raid schedules (`RecurringRaidRun.java:297`, `RaidLifecycle.java:547`) have no operator reset.
- **Unguarded `getUUID`:** a missing key resets the whole file in `RaidScars.load` (`RaidDirector.java:2853`), `Research.load` (`settlement/research/Research.java:107`) and `CaptainSavedData` (`:43`).
- **Tavern death notices** are never pruned. At 4096, corpses holding a tavern order never despawn (`settlement/work/TavernOrderDeathSavedData.java:24-29`; `SettlerEntity.java:4033-4038`).
- **A lost goblin thief** (its entity missing after a crash) blocks future goblin visits (`GoblinTheftSavedData.java:52`). Stolen coins are otherwise conserved (`RaiderEntity.java:1928-1935`).
- **Research project removal:** a project removed in an update loses its paid cost (`ResearchState.java:43-46`).
- **Constant changes:** changing merchant cadence or market constants mid-week quarantines the early-coin merchant or merchant demand (`event/EarlyCoinMerchant.java:551-554`).
- **Relations:** `RelationSavedData.met()` grows past 512 people, and load drops the newest (`conversation/RelationSavedData.java:107`, `:163`).
- **Banner teams:** `BannerTeamBook` has no remove, so dead members count against the 256 lifetime cap.
- **Plaques:** a plaque linked to a disbanded settlement stays `PLAN_INSERTED_UNLINKED` until the plan is pulled and re-inserted (`block/PlaqueBlockEntity.java:1297`).
- **Lumberer escrow:** a self-crafted tool stays escrowed forever if its camp is gone (`LumbererSelfCraftGoal.java:400-413`). Not lost.
- **Tavern serving entities** leak when a host or guest died (`entity/TavernServingEntity.java:815`, `:828`).
- **Orphaned raiders:** raiders with no settlement never despawn (`entity/RaiderEntity.java:1780`).
- **Item components:** carcass and goods-quality components decode all-or-nothing (`registry/ModComponents.java`); only matters if registries change.
- **Bag slot order** is not kept across save/load (`SimpleContainer.fromTag`); counts are.

## Reviewed OK (selected)

- **Active raid across reload:**
  - The plan and roster are persisted, and the mirror is repaired from the ledger.
  - Completion comes only from the persisted terminal ledger, never from "no raiders loaded" (`RaidDirector.java:2360-2368`, `:2440-2465`).
  - The dawn retreat removes unloaded stragglers (`:1274-1403`).
- **Settler death:** cargo, bag and armor transfer is atomic and retried from `tickDeath` (`SettlerEntity.java:3963-4042`).
- **Duplicates on reload:**
  - A corpse saved mid-death is not re-added (`:4294`).
  - `putRecord` never duplicates (`Settlement.java:455`).
  - Nothing respawns settlers from the roster, and vanilla rejects duplicate UUIDs.
- **Settlement ledgers:** ledgers inside `Settlement` quarantine without a reset, with a v0 migration (`Settlement.java:733-860`). Duplicate recruitment identities are quarantined (`SettlementSavedData.java:140-176`).
- **Deferred items and builder:**
  - The deferred-item receipt is written before the pending row is removed (`DeferredItemMaterializationSavedData.java:579-600`), and its load quarantine never becomes dirty.
  - Builder jobs load one at a time with try/catch, and materials stay chest-true (`settlement/builder/BuildSiteSavedData.java:110-119`).
- **Collection bounds:** request rows, crafting orders, pending deliveries, world events, designs and patrol routes are all bounded.
- **UUID guards:** every UUID read in the settler, raider, seat, plaque and Hearth loaders is guarded by `hasUUID`.

## Coverage and limits

| Area | Status |
|---|---|
| All 21 `SavedData` classes, 6 entity and 4 block-entity save paths, attachments, item components | source-reviewed. The per-goal `getPersistentData()` state (TraderWorkGoal, GroundCollectionSession, TavernHostService) was **not** reviewed |
| Crash/restart consistency, settlement plus settler | **executed** (A and B above), sandbox and flat world only, one settler |
| Crash window between chunk and `SavedData` for deferred items (S-07) | source reasoning only; not reproduced |
| Load-failure paths (S-05, S-06) | source reasoning only; no corrupted-file test was run |
| Local uncommitted lanes (courier, builder, tech tree, visitors) | **unresolved** / stale |

## Recommended order

1. **Integrate S-01 and S-02 after comparing them with the local tree.** S-01 is the one that would silently stop farming and lumbering during the week.
2. **Fix S-03 and S-04 next.** Both are small.
3. **For Sunday, operationally:** one pinned jar, stop only with `stop`, hourly backups on, no `kill -9`, and no QA or demo commands (`/hstalk raid`, `/hearthstead battleqa`) on the live world.
4. **Later:** S-05 and S-06, making every quarantine keep and rewrite its raw tag.
