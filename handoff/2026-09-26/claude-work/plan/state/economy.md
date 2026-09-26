# Economy lane state note (a60cce1309921f3b3), paused 26 Sep ~05:30

## Status (26 Sep ~08:50, resumed after the owner's decisions)
Everything is applied to the shared tree and passes a private javac. JUnit is 874/0/0 (captain, 08:41). W13 (08:53): all 5 economy_tuned_* GameTests PASS, no leak. Waiting on:
- the captain's full GameTest run, which includes the new batch prefix `economy_tuned_`;
- the captain's 2 h soak at ~09:05, which runs the tuned levers and reports per-job work %.

`plan/ECONOMY.md` is written: graph, imbalances, fixes, projections, rhythm chart.

## Files changed (all [economy], neutral on the GameTest server unless EconomyConfig.testOverride)
- NEW `settlement/economy/EconomyConfig.java`; its define hook is in `HearthsteadServerConfig`, after ConversationConfig.
- `building/Production.ticksFor`: × craftTimeMultiplier (3.0).
- `entity/SettlerEntity`: hunger × 0.75.
- `entity/ai/CrafterWorkGoal`: CRAFT, then FETCH (self-fetch using the courier's restock key), then UPKEEP (tidy / sharpen / study, 30 s, +1 XP). A due fetch interrupts upkeep. Keeps J-01 WorkStopReasons and the null guard. Backups are in `build-agent-economy/*.orig/.live2/.applied1.java`.
- `entity/ai/CourierWorkGoal`: restockOrder (starved benches first); stow bundle min(4, room); claim/releaseRestockKey; hearthLiftBundle wiring.
- `entity/ai/CourierHearthBagSession`: n-item lift unit; receipt uses SourceCount-n.
- `event/GoldCoinTrades`: crafted rows (3 per visit); purse 12+2/5 settlers, cap 40; MAX_PURSE_BOUND 64; "Crafted" family.
- `network/MerchantPursePayload`: bound 12 → 64. This was a real bug: it would have thrown with a grown purse.
- NEW `gametest/EconomyTunedGameTests.java`: lift, replay, stow, fetch, merchant.

## Next
1. Read the captain's GameTest results and fix any failures, especially `economy_tuned_stow` (partial stow followed by planned=0) and whether @AfterBatch is registered.
2. Read the captain's soak, then put the before/after per-job work %, and coins and food per day, into ECONOMY.md section 5.
3. Proposals still open:
   - food delivery from bakery/kitchen/butcher surplus straight to the Hearth (ledger owner);
   - a string source for the weaver.

## 26 Sep ~09:10: owner decisions done, then paused
- Weaver `wool_bolt_any` (any of 16 wool colours, 4 → 2 bolts) in `Production.java`, with JUnit `src/test/java/com/hearthstead/building/ProductionWeaverRecipeTest.java`. Compiles privately; the JUnit is not run yet (captain).
- Guide text in en_us `hearthstead.guide.logistics.body`: 1 Courier per 10 settlers, self-fetch/upkeep, new purse and crafted goods.
- Next when resumed: read the captain's 2 h soak and fill ECONOMY.md section 5 with measured after-numbers.

- 09:05: full JUnit is green, 895/0/0 (private build dir build-agent-economy/gradle). MerchantPursePayloadTest was updated on purpose for the bound 64. Paused, waiting for the tuned soak.
- Attributes lane (a89a1e6fc08a88993) effects that touch the economy: trader Presence up to +10% coins, carry +4, extra finds +10%, crafting up to -30%.
  - I asked for the trader bonus to be drawn FROM the purse (the purse stays the hard ceiling) instead of minted on top.
  - The rest is accepted. These effects are neutral in GameTests.
- The attributes lane has switched the trader bonus to be drawn from the purse (GoldCoinTrades.takeFromOwnedPurse, called inside TraderSaleService.execute's receipted commit). I reviewed it: it is exact-once, because a replayed transaction returns ALREADY_COMMITTED before the bonus runs. The purse ceiling holds.

## 11:45 captain1 tuned soak analysed (ECONOMY.md 5b)
- Crafters 75-81% work, but real crafting is only 0-29%: the old world is starved and upkeep fills the rest.
- Fixed an upkeep loop: a due fetch cut every session. CrafterWorkGoal compiles; needs a GameTest rerun of economy_tuned_fetch.
- CRITICAL, sent to the warehouse lane: labelled empty chests refuse other groups, so there is no CROPS/FOOD destination and the village starves.
- Next: a fresh-village soak after the warehouse fix.
- ~12:00: the warehouse lane fixed the stale labels (test in warehouse_levels, not yet run). I proposed a food/crops overflow fallback. I asked the captain for GameTests warehouse_levels + economy_tuned_fetch, then a FRESH-village soak.
- The warehouse lane implemented the food overflow fallback (StopReason FOOD_OVERFLOW, chat notice; test in warehouse_levels). Waiting on the captain: GameTests, then the fresh-village soak.
- The soak lane (ab8f4ef2bb37b0168) is adding a carry-back timeout and backoff in CrafterWorkGoal tickFetch/beginCarryBack (a Codex P2 lead). I agreed; I stay out of the file until they report done. Asked them to gate canUse's carry-back on the backoff and to re-run economy_tuned_fetch.
