# Bannerhold economy: production graph, imbalances, fixes, rhythm

Economy lane (a60cce1309921f3b3), 26 Sep 2026. Every number comes from the code (file:line in the lane notes) or the 26 Sep watchdog soak (`soak/results/`, 35 settlers, all 26 trades). A number marked "projected" is arithmetic from the code, not yet seen in a soak.

## 0. Summary

- **Why crafters idle:** a crafter at the recipe table's speed can use 3-20 times what the village's gatherers produce. It empties its chest in about an hour, then waits for a restock that the gatherers cannot supply. Effort does not stop anyone: no work goal checks `isEffortSpent()`, and the watchdog's "EFFORT_SPENT" was a mislabel.
- **Why the village starved:** only couriers fill the Hearth, and only from a Warehouse. Bread needs 4 courier trips and a carrot 2. Three couriers moved about 400 items a day; 35 settlers needed about 430 food moves. Everyone's hunger reached 0 by day 4 in both soaks.
- **Why coins made no sense:** one merchant with a fixed purse of 12, twice a day, is the only real income, and it does not grow with the village. Nothing bought crafted goods, so bread, leather, bricks and tools were dead ends for coins.
- **Fix:** ten config levers in the new `[economy]` section, all neutral on the GameTest server:
  - slower, steadier crafting (×3);
  - crafters fetch their own inputs, and otherwise do real workshop upkeep;
  - couriers restock starving benches first and move 4 items per chest motion (both at the Hearth and at chests);
  - hunger ×0.75;
  - the merchant buys crafted goods, and his purse grows with the village (12 + 2 per 5 settlers, capped at 40).

## 1. Clock

- One in-game day is 48,000 server ticks at `dayLengthMultiplier` 2.0.
- Work is dayTime 1000-5500 plus 7000-11000, which is **17,000 server ticks (about 14 real minutes) of work per day**.
- The merchant comes every 24,000 ticks, so **2 visits per in-game day**.
- Crops, breeding and the hunting spawner run on game ticks, so they get twice the vanilla growth per in-game day.

## 2. Production graph

### 2a. Gatherers (raw producers), per worker per work-day

| Job | Output | Rate/day (1 worker) | Limited by | Goes to |
|---|---|---|---|---|
| Farmer | wheat (+~1.7 seeds), carrot/potato ~2.7 per tile, beetroot, sugar cane | ~50 wheat + 85 seeds, or ~135 carrots/potatoes (40-tile field) | crop growth; a seed trip to the Farmhouse chest per replant | Farmhouse chest (keeps 8) |
| Lumberer | logs (1 per block); free sapling replant | 50-90 logs | trees in the zone; axe wear (~3 days per iron axe) | Lumber Camp chest |
| Miner | cobble, raw iron/copper/gold, coal (world-dependent) | 150-170 blocks, mostly cobble | 60 ticks per block; the mine is finite (~2,000 blocks) | Mine containers |
| Fisher | river perch / trout (fish meal at the Hearth) | 34-47 fish | cast time 300→160 ticks with DEX | Fish rack (4 slots; the soak lane is fixing it), then fishery chests |
| Hunter | carcass → meat, leather, rabbit hide, feathers; forages brown mushroom | 5-10 carcasses = 10-25 meat, 3-10 hide/feather | spawner: 1 animal per 150 s, cap 8 | Lodge chest |
| Herder | eggs, wool, meat by culling | depends on the herd the player supplies | vanilla breeding | Pasture containers |

**No producer at all:** string (weaver, fletcher bows), flint (fletcher, except gravel from the miner), kelp (kitchen). Raw copper and gold depend on the world.

### 2b. Crafters: inputs, outputs and capacity

Capacity is at 100% of the 17,000 work ticks at the table speed (×1). With the new ×3 the per-day input needs are a third of this.

| Building (job) | Recipe (input → output, ticks) | Max input/day ×1 | Consumers of the output |
|---|---|---|---|
| Mill (miller) | 3 wheat → 2 flour (40); 2 cane → 3 paper (40) | 1,275 wheat | flour → bakery; paper → research, emblems, courier ledger node |
| Bakery (baker) | 2 flour → 3 bread (160); 3 wheat → 1 bread (160) | 212 flour / 319 wheat | **food**; tech nodes (hospitality, feasts); merchant (new) |
| Brewery (brewer) | 4 wheat → 3 malt (60); 2 malt → 3 ale (200); 3 wheat → 1 ale | 1,133 wheat | tavern (travellers pay 1 coin); feasts, great tavern; merchant (new) |
| Butcher | 1 raw meat → 1 cooked (120); 2 rabbit → 2 cured hide (40) | 142 meat | **food**; cured hide → tannery; merchant (new) |
| Kitchen (cook) | 1 potato → baked potato (100); 2 mushroom → stew; kelp | 170 potatoes | **food**; merchant (new) |
| Smelter | 3 raw iron → 4 bloom (160); ore → ingot (200-240); log → charcoal (90, cold start) | 319 raw iron | ingots → smithy, armoury, many tech nodes; charcoal → fuel |
| Smithy (smith) | 2 bloom → 3 ingot; 2-3 ingot → axe/pickaxe/hoe/sword (240-300) | 131-170 ingots | tools → gatherers (equipment requests), guards; merchant (new) |
| Armoury (armourer) | 4-8 leather or ingot → armour (320-1,040) | 131 ingots / 212 leather | guards (gear tiers) |
| Sawmill (sawyer) | 1 log → 6 planks (120); 3 logs → 2 beams (180) | 142-283 logs | planks → carpenter, builder, nodes; beams → barrels, deep mine, carts |
| Carpenter | planks → sticks, barrel; beams → barrel; sticks → ladder | 458-567 planks | builder, nodes (barrel ×4 alehouse), merchant (new) |
| Mason | cobble → stone (120); 4 stone → 4 bricks (160) | 142 cobble | **stone bricks: town/castle charter, stone walls, storehouses**; merchant (new) |
| Fletcher | feather → 7 arrows, flint → 4 arrows (100); 3 string → bow | 170 feathers/flint | archers, hunter ammo |
| Weaver | 4 string → wool; 3 wool → 2 bolts; 6 wool → banner | 486 string | bolts → guild halls, crown; banners → caravan, hall of heroes |
| Tannery (tanner) | 2 cured hide → 3 leather; 4 rabbit hide → 1 leather | 189 hides | leather → armoury, many tier-1/2 nodes (straps, packs), emblems |

### 2c. Flow diagram (→ = a courier or self-fetch trip; [W] = Warehouse)

```
 FARMER ─wheat─→[W]─→ MILL ─flour─→[W]─→ BAKERY ─bread──┐
   │  └─carrot/potato─→[W]─→ KITCHEN ─baked potato──────┤
   │                    [W]─→ BREWERY ─ale─→ TAVERN      │
 HUNTER ─meat─→[W]─→ BUTCHER ─cooked meat───────────────┼─→[W]─→ HEARTH ─→ settlers eat
   └─rabbit,hide,feather─→[W]─→ TANNERY / FLETCHER       │         (only couriers fill it)
 FISHER ─fish─→ rack/[W] ────────────────────────────────┘
 LUMBERER ─logs─→[W]─→ SAWMILL ─planks/beams─→[W]─→ CARPENTER ─→ builder, nodes
                  └─→ SMELTER (charcoal fuel) ─→ every burning bench
 MINER ─cobble─→[W]─→ MASON ─bricks─→ charters, walls, storehouses
   └─raw iron─→[W]─→ SMELTER ─bloom/ingot─→[W]─→ SMITHY ─tools─→ gatherers, guards
                                         └─→ ARMOURY ─armour─→ guards
 HERDER ─wool/eggs/meat─→[W]─→ WEAVER / BUTCHER
 [W] surplus ─→ TRADER ─→ MERCHANT ─coins─→ tech nodes, emblems, recruits, upgrades
```

## 3. Imbalances, with numbers

### 3a. Input starvation (the main cause)

Real crafting share of the work phase, if the bench gets a typical supply share. Projected from the table; supply shares are assumptions for a 35-settler village with 3 farmers, 3 lumberers and 1 each of the other gatherers.

| Job | Typical supply/day | Real work at ×1 (before) | Real work at ×3 (after) |
|---|---|---|---|
| Baker (flour path) | 66 flour | 31% | 93% |
| Baker (rough) | 50 wheat | 16% | 47% |
| Miller | 75 wheat | 6% | 18% |
| Brewer (malt) | 40 wheat | 20% | 61% |
| Smelter (bloom) | 40 raw iron | 13% | 38% |
| Smith | 30 ingots | 18% | 53% |
| Armourer | 15 ingots | 12% | 34% |
| Sawyer | 40 logs | 28% | 85% |
| Carpenter | 60 planks | 13% | 39% |
| Mason | 60 cobble | 57% | 100% |
| Butcher | 15 meat | 11% | 32% |
| Cook | 40 potatoes | 24% | 71% |
| Tanner | 10 cured hide | 5% | 16% |
| Fletcher | 10 feathers | 6% | 18% |
| Weaver | 0 string | 0% | 0% |

Seen in the soak before any change: day 0, while the starting stock lasted, crafters worked 40-96%. From day 1 on they worked 0-15%, and the idle reason was 47-89% NO_INPUT.

### 3b. Dead-end outputs (no consumer, before)

- **Coins:** bread, cooked meat, leather, cured hide, wool, bolts, beams, charcoal, stone, stone bricks, tools and ale (except through tavern travellers) had **no coin buyer**. The merchant's 21-item list was raw goods only.
- **Goods:** they were used only by recipes, tech nodes, research and food.
- **Demand pressure:** `MerchantDemandSavedData.recordCompletedSale` is never called outside tests, so demand pressure was dead code.
- **Seeds pile up:** the Farmhouse chest fills with seeds, which are never collected.

### 3c. Jobs with nothing useful to do

- Every crafter once its input ran out (3a).
- The scholar once research is done: 83-88% idle. The soak lane has fixed this.
- The weaver has **no string source at all**.
- The archer idles by design outside threats: 0% work. That is the battle-roles lane's area.

### 3d. Courier throughput versus demand

| Step | Before | After |
|---|---|---|
| Lift at the Hearth (consolidation) | 1 item / 40 ticks | up to 4 items / 40 ticks |
| Stow into a chest | 1 item / ~40-80 ticks (8 items ≈ 360 ticks) | up to 4 per motion (8 items ≈ 120 ticks) |
| Full trip (walk ~600 + load + unload) | ~1,000-1,100 ticks | ~750-850 ticks |
| Trips per courier per work day | ~16 | ~21 |
| Items per courier per day (8-item bag) | ~130 | ~170; ~250 with the Satchel (12) |
| **3 couriers** | **~400** | **~500-750** |

Demand per day for 35 settlers:
- food: ~100 items at the Hearth, which is ~250-370 moves (carrots take 2, bread via the mill about 5 per loaf);
- crafter restocks at ×1: several hundred moves;
- output collection: about the same again.

Before, demand was about 2× capacity. After:
- ×3 crafting cuts the restock moves to a third;
- self-fetch takes over when no courier is free;
- starving benches get served first instead of being topped up in list order.

**Rule of thumb for players: 1 courier per ~8-10 settlers.** The soak village had 3 couriers for 35 settlers.

### 3e. Coins per in-game day (before)

| | Early (day 1-2, 6-10 settlers) | Mid (day 5+, 35 settlers) |
|---|---|---|
| Merchant (2 visits × 12 purse) | 8-12 realistic (what raw goods you have) | 24 (capped) |
| Tavern travellers | 0-3 | 2-3 |
| Raid wins | 0 (first win +8 once) | ~1.2 (4 per win / 3.5 days) |
| Goblin theft | ~-1 | -1 to -4 |
| **Net** | **~8-14** | **~24-27, flat** |

Sinks:
- tier-1 nodes 1-4 each (tier-1 total 48);
- emblems 1-6;
- recruits 4-8;
- post-raid upgrades 129 in total;
- peddler 1-12 per ware;
- minstrels 4 (optional);
- brute toll 10 C (or food).

At a flat ~25 a day, v3 tier 2 (218) takes ~9 days and tier 3 (254) ~10 days. **The late game was coin-starved and did not speed up as the village grew.**

### 3f. Food per settler (before)

- **Hunger drain:** 0.10/s working, 0.04/s otherwise. A worker needs 147 hunger a day, which is 3.7 bread (40 each), 6.1 carrots or 2.3 cooked beef.
- **Village total:** 35 settlers need about 130 bread-equivalents a day (+10-20% waste from topping up at 74).
- **Supply:**
  - 3 farmers ≈ 150 wheat (≈150 bread via the mill) or ≈135 carrots each;
  - fisher 34-47;
  - hunter 10-25 meat.
  - That is enough on paper, but it could not reach the Hearth (3d).
- **Seen in the soak:** average hunger fell 84 → 73 → 73 → 47 → 23 → 8 → 0 over days 0-6. There is no death from starving; morale drops by 30, healing stops, and at average morale below 60 no one can be recruited.

## 4. Fixes (smallest coherent set)

All ten are in `[economy]` in the server config. The class is `settlement/economy/EconomyConfig.java`, hooked from `HearthsteadServerConfig`.

**Every lever is NEUTRAL on the GameTest server** (×1, bundle 1, purse 12, no crafted rows, no fallbacks), unless a test sets `EconomyConfig.testOverride = true`. The existing trade, courier and merchant tests keep measuring the unchanged table.

| Key | Default | What it does | Where |
|---|---|---|---|
| `craftTimeMultiplier` | 3.0 | Every batch takes 3× its table ticks. Output is unchanged while inputs are the limit; crafters work 3× longer on the same supply; each 4-batch restock lasts 3× longer. Research bonuses still stack. | `Production.ticksFor` |
| `selfFetch` | true | After a bench has stood empty for `selfFetchAfterSeconds` (20 s, so a courier gets first claim), the crafter walks to a Warehouse within `selfFetchRadius` (64) and carries back up to `selfFetchMaxItems` (16; the bag weight limit also applies) of one missing input, or fuel. It takes the courier's own restock key, so no courier makes the same trip. Items move only chest → bag → chest. A load interrupted by the end of the shift goes to the bench first; it never rides around overnight. | `CrafterWorkGoal` + `CourierWorkGoal.claimRestockKey` |
| `workshopUpkeep` / `upkeepSeconds` | true / 30 | With nothing to make and nothing to fetch, the crafter does a 30 s session in three visible parts on existing clips: **tidy** (sorting clip; merges split stacks in the workshop chests), **sharpen** (the trade's own motion; mends 4 durability on damaged tools at the bench and in hand), **study** (scholar clip; +1 trade XP). The bench is re-checked every second, and the first input that arrives ends upkeep. The XP trickle is at most 28 a day, against ~50-70 from real crafting. | `CrafterWorkGoal` |
| `starvingWorkshopsFirst` | true | The courier restock scan visits benches that cannot run one batch before benches that are merely below their 4-batch reserve (cached for 40 ticks). | `CourierWorkGoal.restockOrder` |
| `courierDepositBundle` | 4 | Up to 4 items per stow motion at chests, and per lift cycle at the Hearth. Exact-once is kept: the Hearth receipt now checks `SourceCount - n` and the bag image of an n-item unit. Default n = 1 keeps the old contract. | `CourierWorkGoal.plannedTransferCount`, `CourierHearthBagSession` |
| `hungerDrainMultiplier` | 0.75 (owner decision) | A worker eats about 2.8 loaves a day instead of 3.7. | `SettlerEntity` hunger tick |
| `merchantBuysCrafted` | true | Each visit adds 3 Basic rows for crafted goods the village or the player actually holds (most units first, then a rotation). They use the same purse, sale receipts and 4 uses per row; they have their own "Crafted" family surcharge; the trader's 128-food / 64-other Warehouse reserve still applies. | `GoldCoinTrades` |
| `merchantPurseBase` / `…PerFiveSettlers` / `…Cap` | 12 / 2 / 40 (owner decision) | Purse = 12 + 2 per 5 settlers, capped at 40. 10 settlers → 16, 35 → 26, 70 → 40. The stored-purse validation ceiling and the client purse packet bound are now 64 (`MerchantPursePayload` used to throw above 12). | `GoldCoinTrades`, `MerchantPursePayload` |

Crafted quotes, in items per Coin at Basic quality. Each is about 1.3-2× the value of its raw inputs, so labour is worth something:

| Item | Quote | Item | Quote | Item | Quote |
|---|---|---|---|---|---|
| bread | 4 | stone bricks | 16 | iron axe / pickaxe | 2 |
| baked potato | 8 | charcoal | 6 | iron hoe / sword | 3 |
| cooked beef / pork | 4 | white wool | 6 | timber beam | 3 |
| cooked mutton / chicken | 6 | arrow | 32 | wool bolt | 2 |
| leather | 3 | barrel | 2 | ale / cured hide | 4 / 4 |

Owner decisions, 26 Sep (after W13):
- **Weaver:** a basic recipe `wool_bolt_any` takes 4 wool of any of the 16 colours (the herder's shearing) → 2 wool bolts. It is listed after white wool's better 3 → 2 recipe (`Production.java`, `ANY_WOOL`). JUnit: `ProductionWeaverRecipeTest`.
- **Workshop food keeps going through the Warehouse.** Instead, the in-game guide (`hearthstead.guide.logistics.body`) now says to plan about 1 Courier per 10 settlers, and explains crafter self-fetch and upkeep. The same text replaces the stale "8- or 12-Coin purse" with the new purse and crafted-goods rule.

Not changed, handed to other owners:
- the fish rack, the shift-end put-away and the scholar idle (soak lane);
- event numbers (events lane);
- raid numbers (balance lane).

## 5. After: targets and projection

A soak is not yet run; the numbers below are projections from sections 3a-3e.

- **Work % by day 3 (target ≥70% real work or real fallback, no job below 50%):**
  - crafters: real crafting 16-100% by job (3a), and fetch or upkeep fills the rest of the work phase. So crafters should read ≥85% WORK in the watchdog, because `CrafterWorkGoal` is a WORK goal. The watchdog `activity` column tells crafting, fetching (TRAVELING/CARRYING) and upkeep (SORTING + trade clip + study clip) apart.
  - Real crafting stays low for the miller, tanner, fletcher and weaver. That is the true supply picture: the player must add gatherers (a hunter for hides and feathers, a herder with sheep for string/wool).
- **Coins:**

  | | Early (8 settlers) | Mid (35 settlers) |
  |---|---|---|
  | Purse max per day | 2 × 14 = 28 | 2 × 26 = 52 |
  | Realistic net per day | ~10-16 | ~40-50 (the trader can now fill crafted rows) |

  - The first tier-1 node (1-4 C) is affordable on day 1. The 27 C pre-raid set takes about day 2-3 of mixed sales. **Tier 1 (48 C) cannot all be bought before the first raid, so the player has to choose.**
  - At mid game: tier 2 ≈ 4-5 days, tier 3 ≈ 5-6 days, tier 4 ≈ 7 days. It is still tight, but it grows with the village.
- **Food:**
  - need = 35 × 110 hunger ≈ 96 bread-equivalents a day, +15% waste ≈ 110;
  - supply ≈ 150-280 bread-equivalents from 3 farmers + fisher + hunter;
  - Hearth delivery capacity ≈ 170-250 food items a day if ~40% of courier time goes to food;
  - **projected surplus ≈ +15-40%**, near the 10-30% target. It must be measured; if it is short, add a 4th courier or the Satchel node.

### 5b. MEASURED: captain1 tuned soak (09:10-11:27, 4.0 in-game days, 34 settlers)

Caveat: it ran on the **old, already-starved soak world** (day 144, with the fullchests/emptystores/brokenchest history), not on a fresh village. It proves the fallbacks and that the courier and merchant code is stable. It does not show a healthy supply chain.

Watchdog work % of the work phase:

| Job | Before (after3b) | Tuned (captain1) | of which real crafting | fetch | upkeep |
|---|---|---|---|---|---|
| baker | 6.7 | 76.0 | 0 | 0 | 76 |
| brewer | 3.0 | 74.6 | 0 | 0 | 74 |
| butcher | 4.7 | 76.5 | 0 | 0 | 76 |
| carpenter | 9.9 | 76.5 | 19 | 8 | 49 |
| cook | 8.6 | 76.2 | 0 | 0 | 77 |
| mason | 10.9 | 77.5 | 0 | 0 | 78 |
| miller | 1.0 | 75.9 | 0 | 0 | 76 |
| sawyer | 5.9 | 75.4 | 8 | 0 | 67 |
| smelter | 11.5 | 77.2 | 14 | 0 | 64 |
| smith | 4.3 | 75.6 | 0 | 0 | 76 |
| tanner | 0.9 | 76.6 | 0 | 0 | 77 |
| weaver | 2.6 | 81.1 | 29 | 5 | 46 |
| farmer | 81 | 78 | | | |
| courier | 63 | 60 (27% none) | | | |
| trader / innkeeper | 99 / 99 | 93 / 89 | | | |
| lumberer / miner / scholar / herder / fisher / hunter | 44 / 16 / 0.5 / 23 / 1 / 12 | 9 / 0 / 0 / 6 / 17 / 3 | | | |

The split into real crafting / fetch / upkeep comes from the samples' activity sequence (`build-agent-economy/split.py`).

What it shows:
1. **The target "≥70% real work or real fallback" is met for every crafter (75-81%)**, but in this starved world almost all of it is upkeep. Real crafting is 0-29%.
2. **Upkeep bug found and fixed:** a due self-fetch cut every upkeep session at ~20 s. In a world with nothing to fetch, that looped tidy → sharpen → tidy and almost never reached "study". Fixed so the session always finishes (`CrafterWorkGoal`, 11:40). Fetch now waits at most one 30 s session.
3. **Food: hunger was 0 for all 4 days; the ledger had 0 FOOD routes and the Hearth was empty.** Root cause is NOT the economy numbers but warehouse sorting:
   - All 6 Warehouse chests were labelled BUILDING_MATERIALS ×4, WOOD and OTHER. Three of them are empty but still labelled.
   - `WarehouseSorting.candidate` refuses a WRONG_GROUP chest even when it is empty.
   - So crops and food had no destination, and nothing was collected: farmhouse wheat 89, lumber camp 321 logs, pasture 391 wool + 16 beef, 23 fish all stuck at the source. The couriers completed only ~15 ledger routes a day.
   - Sent to the warehouse lane (a053cd97fe5e2bfab) as critical. A player who dumps cobble into a new small warehouse can hit it.
   - **Fixed by the warehouse lane (~12:00):** an empty unit whose label belongs to another group (label older than 1200 ticks) is now a last-resort destination and is relabelled at reservation. GameTest `WarehouseLevelGameTests#emptiedStaleLabelledChestTakesFood` is written but not yet run. The food/crops overflow fallback is implemented too: with no proper home, FOOD/CROPS go into any unit with room, labels are never changed, and the player sees the StopReason FOOD_OVERFLOW plus a one-time chat notice. Test: `WarehouseLevelGameTests#foodOverflowsWhenEveryChestHoldsOtherGoods`, compiled, captain to run. Food delivery to the Hearth and crafter self-fetch read every warehouse container regardless of label, so overflowed food still reaches the Hearth.
4. **Coins:** not in the watchdog. The merchant/crafted rows are proven by `economy_tuned_merchant`, not by soak.
5. **Gatherers regressed** (lumberer 9% with 40% stalled, miner trapped, hunter 3%). Those are the soak/engine lanes' stall findings, not economy levers.

**Still needed:** a FRESH-village soak (`soak/village.py`, 3-4 days) after the warehouse fix, to measure real crafting %, food per day and coins per day.

## 6. Rhythm: events, raids and economy over 10 days

Rules, from the events lane and the balance lane:
- **Small events:** chance min(0.9, 0.55 × frequency) per visited day, so about 45% of days are quiet; at most 1 a day; per-type gaps of 2-4 days.
  - None during a raid, after a raid warning is out, or on the eve or day of an attack.
  - Days nobody visits are not planned.
- **First raid:** only after Declare Ready; it lands on night 2-3 with a dusk warning the day before.
- **Recurring raids:** 3-4 days after the previous raid ends, warned at dusk the day before.

A representative 10 days (R = raid night, w = warning dusk, · = quiet, E = small event, $ = merchant visit, twice a day):

```
day          1    2    3    4    5    6    7    8    9    10
merchant     $$   $$   $$   $$   $$   $$   $$   $$   $$   $$
raid              w    R              w    R              w/R
small event  E    ·    ·    E    ·    ·    ·    E    ·    ·        (blocked on the eve and day of each raid)
coins net   +12  +14  +18  +22  +28  +30  +34  +38  +42  +44     (projected; raid win +8, then +4)
food bal.   +    +    +    +    +    +    +    +    +    +       (projected +15-40% a day; buffers ~2 days)
```

- **Rhythm check:** there is always at least one quiet day between a small event and a raid eve, and small events land on about 3-4 of 10 days. With the raid gate this gives a "calm → event → calm → warning → raid → recovery" beat. It needs no change.
- **Can events or raids bankrupt or starve a normal village?**
  - **Brute toll:** 24-32 food (≈ 25-30% of a 35-settler day) or 10 C (≈ 20-25% of a mid-game day). Refusing means fighting 3 brutes. It is harsh but not ruinous, and the gap is 4 days.
  - **Refugees:** +3 mouths is about +8 food a day. That is fine when the food surplus is at least 10%, but a risk while logistics are short. Flag: in the "before" state the village starved with no events at all; the real starvation risk was courier throughput, not events.
  - **Field fox / wild boar:** they reset at most 4 or 6 crops, which is under 5% of a day's harvest.
  - **Wolf pack:** at most 1 animal.
  - **Raids:** KORN steals at most 6 items per raider, and killing the raider returns them. BRANN scars are rebuilt with the original blocks; after a raid, idle crafters and settlers help repair, which is real fallback work. Victory pays +8 the first time, then +4.
  - **Goblins:** at most 1-3 C every ~0.75 day.
  - **Verdict:** nothing bankrupts or starves a village that has **≥1 courier per ~10 settlers**. Below that, food is the weak point whatever the events.

## 7. Evidence and next steps

| Change | Implemented | Compiled (private javac vs live tree) | JUnit | GameTest | Seen in soak |
|---|---|---|---|---|---|
| [economy] config + hook | yes | yes | JUnit 895/0/0 (09:05) | W13 08:53: all 5 economy_tuned_* PASS; no leak | captain1 soak: crafters 75-81% work (mostly upkeep in a starved world); fresh-village soak still needed |
| craft ×3 | yes | yes | — | neutral in GT | pending |
| self-fetch + upkeep | yes | yes | — | neutral in GT | pending |
| starving-first restock, bundle 4 (stow + Hearth lift) | yes | yes | — | neutral in GT | pending |
| hunger ×0.75 | yes | yes | — | neutral in GT | pending |
| merchant crafted rows + scaled purse | yes | yes | MerchantPursePayloadTest updated (0/8/12/40/64 ok, 65 rejected) | economy_tuned_merchant PASS | pending |
| weaver any-wool recipe | yes | yes | ProductionWeaverRecipeTest 3/3; full JUnit 895/0/0 (09:05) | ChainsGameTests acyclicity covers it | pending |
| guide text (courier rule of thumb, purse) | yes | JSON validated | — | — | — |

Next:
1. Full GameTest run (captain).
2. A 3.5-day soak with `soak/village.py`. Report per-job work % by day with `build-agent-economy/win.py`, and hunger by day with `hung.py`.
3. Opt-in tuned GameTests are written (`gametest/EconomyTunedGameTests.java`, batch prefix `economy_tuned_`), waiting for the captain's run:
   - Hearth lift with n = 4, conserved every tick and really bundled;
   - a replayed lift receipt after a crash between extract and ack, acknowledged and never extracted twice;
   - stow bundle 4 into a chest that fills mid-bundle, which ends exactly full with nothing lost;
   - the merchant's crafted rows pay exactly once under replayed callbacks, with the purse capped at 40.
   Still to add: a self-fetch conservation test (warehouse + bench + bag totals before = after).
4. Proposals for other lanes:
   - allow food delivery straight from Bakery/Kitchen/Butcher surplus to the Hearth. This needs `validateFoodEndpoints` to accept those sources; it is the ledger owner's decision.
   - ~~string source~~: done, the weaver takes any wool (owner decision).
   - ~~food delivered straight from workshops to the Hearth~~: declined by the owner; it is replaced by the guide's courier rule of thumb.
