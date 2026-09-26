# Reliability / soak lane: state note (2026-09-26 12:30)

Goal from the lead: "every settler works". The 6 broken jobs come from captain1 (2 h soak, 09:10 tree, 34 settlers).

Build and test status:
- Private build copy: `soak/proj/hearthstead-neoforge`. The build script is `soak/pbuild.sh`; it syncs the shared tree, and `NOSYNC=1` skips the sync.
- The shared tree compiled green at 12:16 with everything below.
- JUnit on the private copy: 1047 tests, 2 failures. Both are other lanes' tests and have nothing to do with my changes: TechTreeRadialLayoutTest and TavernTableMathTest.

## Per job

### Miner (0%): Thyra trapped in a legacy pit for 2.7 h
- **Cause** (from world geometry, `soak/mca.py` reader): the old dig left a 2-deep, 10x13 sheet under the mine. Its edges are 1-high crawlspaces under the grass. MinerEscapeGoal only carved into an adjacent solid wall, and Thyra had open floor on all 4 sides, so it gave up; that is where "miner_escape_gave_up" came from.
- **Fix:** `MinerEscapeGoal.planStep` is now a bounded Dijkstra dig-route.
  - Moves cost 1; each cut costs +3.
  - Cuts are allowed only for rock or earth inside the dig square and below the mine floor.
  - The goal no longer cuts plank walls or "loose cover" (the old goal cut the mine's own wall).
- **Test:**
  - Python simulation on the real world copy (`soak/escsim2.py`): out in 10 actions.
  - GameTest `miner_pit` `aMinerInTheRealWideLegacyPitWalksToAWallAndClimbsOut` copies the real geometry. Queued with the captain, not run yet.
- **Still open:** J-10 requires a pickaxe, and no iron exists in the village. A design proposal (a stone pickaxe from the mason or carpenter) is below.

### Lumberer (9%)
1. **Bramwell: static work_chop for hours.**
   - Cause: his axe had 10 uses left, which passes the 8-use floor, but a 10+ log tree needs more. Every chop contact paused ("tree_requires_more_axe_durability"), and no replacement was ever requested. The saved chop clock was capped with `Math.min(20000, ...)`; 20000 % 20 never equals the contact tick 11, so the clock then froze for good.
   - Fix, in `LumbererWorkGoal`: the cap keeps cycling now. On the durability pause, `EquipmentRequests.requireJobUses(settler, logs + 1)` plus `refreshFor` opens a WORN replacement request, which shows as "Needs: axe"; `finishTree` clears it.
   - `EquipmentRequests.requirementFor(profession, settler)` applies the per-worker floor in refreshFor, equipFromPersonalInventory, equipFromWorkplace(At) and readyForProfession.
   - GameTest: `lumber_worn_axe`.
2. **Baldric:** the axe has 4 uses left, so there is a genuine NO_TOOL request, and it is already shown on the sheet. The village has no iron, so no axe can be made. Economy lane, and see the proposals.
3. **Dunstan (Elmfield):** he is on top of the owner's house log beam at 116,77,-124, next to the balcony fence and the ladder top. Pathing reports nav:stuck; he has 0 energy and 0 hunger and has been stuck since before the soak. Not fixed. It needs a live repro and a ladder-descent investigation.

### Scholar (0%)
- **Cause:** with no affordable Research project and no tech study, nothing ran and no reason was shown.
- **Fix:**
  - New `TechTree.anyStudy` and `TechTree.scholarSession`: each completed scholar session adds 200 day-ticks to all tech studies, which roughly doubles study speed while he works.
  - New StopReason `NOTHING_TO_STUDY` (wire 11), with a thought-bubble case, 2 lang keys and the pin test updated.
- **Test:** GameTest `scholar_study` (2 tests).

### Herder (6%)
- **Cause:** no shears (J-10), and the village cannot make shears. The pasture chest has no feed either; eggs and wool pile up.
- **Fix:** the herder now reports WAITING_INPUT (breedable herd, no feed) or NO_VALID_TARGET instead of idling silently. Missing shears already show as the equipment request.
- **Still open:** the design gaps are below.

### Fisher (17%)
- **Cause:** after the meal the fisher stood 40-60 blocks from the chair. The exact aisle route failed beyond the 32-block follow range, giving `fisher_chair_unreachable` / NO_PATH.
- **Fix:** `FisherWorkGoal` gains an approach mode. It walks towards a standable aisle on partial routes, switches to the exact aisle within 24 blocks, and has a 1200-tick budget.
- **Test:** GameTest `fisher_far_shore`.

### Hunter (3%)
- **Cause:** a cap/floor deadlock. There were 3 cows, 3 pigs and 3 rabbits: 9 animals, over the spawner cap of 8, with none above the hunt floor of 4. The spawner never spawned and the hunter never hunted. `hunter_drop_unreachable` was the hunter lane's set-aside, working as designed.
- **Fix:** `HuntingGroundsPolicy.mayAdd` lets the spawner top up the largest herd past the cap until one herd is huntable. That is bounded to floor+1 extra animals. `HuntingGroundsSpawner` uses it, with no variety pick while topping up.
- **Test:** JUnit `HuntingGroundsPolicyTest`, +2 tests; 8/8 pass.

### Codex P2: CrafterWorkGoal carry-back (confirmed)
- **Cause:** the TO_BENCH leg had no timeout and re-pathed every tick once navigation was done.
- **Fix:**
  - The leg now times out after 1200 ticks, then tries the next bench chest, then records `crafter_carry_back_unreachable` and sets carryBackBlockedUntil (400 ticks). The bag is kept and the key released.
  - A minimum of 5 ticks between re-paths.
- **Test:** GameTest `crafter_carry_back`. The captain should also re-run `economy_tuned_fetch`.

### Watchdog
- `/hearthstead watchdog inspect <name>` dumps goals with their mode/target/done fields, using reflection only when called.
- Raise lines now include `state=`.

## Pending
- **Live repro:** repro slot #5 in WSL-QUEUE, about 14:05, on a copy of `results/captain1-world` in `soak/rundir-repro` (script `soak/repro.py`, snapshot `soak/snap-repro`; re-snapshot from pbuild first).
- **Unfinished jobs:** Dunstan's roof beam, and the herder LOOP (cause unknown).
- **GameTest results from the captain:**
  - miner_pit (3)
  - fisher_far_shore
  - scholar_study
  - lumber_worn_axe
  - crafter_carry_back
  - economy_tuned_fetch
  - CourierStopReason pin

## Design proposals (not implemented)
- The village cannot make shears or a first pickaxe without iron, and no settler supplies iron without a pickaxe (J-10 circularity). Proposals:
  - a smithy "shears" recipe (2 iron);
  - a mason or carpenter stone/wooden pickaxe recipe;
  - or waive the tool for a Miner with no pickaxe in the whole village.
- Couriers never restock pasture feed (wheat) for the herder.
- The captain1 village starved: 0 food at the Hearth, 89 wheat sitting in the farm chest, and 321 logs uncollected at the lumber camp. That is the warehouse lane's full/labelled-chest issue, and their food-overflow fix targets it.
