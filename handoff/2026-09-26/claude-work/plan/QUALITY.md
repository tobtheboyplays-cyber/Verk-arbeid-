# Bannerhold goods quality: what existed, what was built, the numbers

Quality lane, 26 Sep 2026. Owner ask: "Different qualities of the equipment settlers make for money is also something you must verify and build."
Every number below is from the code (file:line). Odds are exact (analytic), and a seeded JUnit checks them.

## 1. What existed (verified before any change)

| Question | Finding | Where |
|---|---|---|
| Tiers | 6 tiers as ints: BASIC 0, FINE 1, SUPERIOR 2, EXCEPTIONAL 3, MASTERWORK 4, LEGENDARY 5. Basic = **no component**. The codec accepts only 1..5. | `settlement/work/GoodsQuality.java:17-21` |
| Storage | `hearthstead:goods_quality` data component: persistent (codec) + network-synced (stream codec). Saved and synced correctly; JUnit rejects out-of-range values. | `registry/ModComponents.java:29-34`, `GoodsQualityTest` |
| Who assigned quality | **Only gatherers.** Lumberer logs and farmer crops (`GoodsQuality.forWork`: skill attribute >= 20 + iron/diamond/netherite axe or hoe + workplace level; a stable hash as the roll), and fish (`FisherProgression.rollCatch`, by Dexterity). | `WorkerProvenanceService.java:982-990`, `FisherProgression.java:23-41` |
| Crafted goods | **No quality at all.** `Production.run` made `new ItemStack(output, count)`; `CrafterWorkGoal` never passed the crafter. Smith, armourer, fletcher, baker, cook, tanner, weaver, mason, carpenter, brewer, sawyer: all Basic. | `building/Production.java` (old `run`) |
| Effect of quality | **Price only.** No durability, attack, toughness or food effect anywhere. | — |
| Price | Logs/crops/fish: a fixed per-tier table of items per Coin (e.g. logs 8/7/6/5/4/4). Crafted goods (economy lane): **Basic only**; `purchase()` threw for a graded crafted good. | `GoldCoinTrades.java:124-145` (old) |
| Tooltip | Coloured grade line, but shown only for stacks with the component **or** a hard-coded list (logs, planks, wheat, carrot, potato, beetroot). | `event/GoodsQualityTooltip.java` (old) |
| Storage page / merchant screen | Already show the grade: Storage rows and detail ("Fine quality", tier colour); merchant rows ("Fine shipment"). The storage index aggregates only exact item+component variants. | `StorageScreen.java:296-300,365,402,460-478`; `CoinMerchantScreen.java:140,193,331-342`; `StorageIndexPayload.java:113` |
| Stacking | Different grades never merge (vanilla component equality). The Basic/no-component rule means Basic crafted goods stack with vanilla ones. The trader's warehouse export and `TraderSaleService.matches` match the grade exactly. | `GoodsQualityGameTests`, `TraderSaleService.java:93-96`, `TraderWorkGoal.java:144` |
| Room check bug risk | `Production.hasRoomFor` counted any same-item partial stack as room, whatever its components; a graded output could then fail to merge and be spilled on the floor. Latent until crafted goods got grades. | `Production.java` (old `hasRoomFor`) |
| Unstackable crafted quotes | Iron tools are quoted "2-3 per Coin", but vanilla clamps a trade's cost to the stack size, so a sale really takes **one** tool. The crafted-row picker still required 2-3 tools on hand, so one sword never earned a row. | `GoldCoinTrades.java` `craftedCandidates` (old) |

## 2. What was built

### 2a. The crafted roll (`settlement/work/CraftedQuality.java`)

```
mean  = 0.33 x (tradeLevel - 1)       trade level 1..10 (SkillLevels), the main factor
      + 0.005 x attribute             the trade's primary attribute (0..99)
      + 0.30 x (workshopLevel - 1)    the building's checklist level
      + 0.12 x toolTier               GearTiers of the crafter's main-hand item (0..4)
      + 0.20
value = mean + 1.5 x (u1 + u2 - 1)    triangular spread; u1, u2 from the settler's RandomSource
grade = value >= 1 / 2 / 3 / 4.25 / 5.5  ->  Fine / Superior / Exceptional / Masterwork / Legendary
gates: Masterwork needs trade level 7, Legendary trade level 9 (otherwise capped one tier lower)
```

- It is wired through `Production.run(level, building, recipe, crafter)`. `CrafterWorkGoal` now passes its settler. The grade is rolled **before** anything is taken, and the room check then needs room for exactly that graded stack, so a batch that cannot be stored removes nothing.
- The pure `ready()` / `idleReason()` reads count only an **empty** slot as room for a graded output, because its grade is not yet known.
- **Which outputs get a grade** (`CraftedQuality.bearsQuality`):
  - anything with durability (tools, weapons, armour, bows, shields);
  - anything with a food component;
  - arrows, barrels, stone bricks, leather, white banners, wool bolts, timber beams and ale.
- Intermediates never get a grade: flour, malt, iron bloom, ingots, sticks, planks, charcoal, stone, paper and white wool. The next recipe ignores the grade, so a grade there would only split chest stacks.
- Crafting trades keep their hands free today, so `toolTier` is normally 0. It is wired for when a crafter holds a tool.

### 2b. What a grade does

| Grade | Durability (baked `max_damage`) | Weapon attack | Armour toughness (per piece) | Food saturation | Merchant price x |
|---|---|---|---|---|---|
| Basic | +0% | +0 | +0 | +0% | 1.0 |
| Fine | +10% | +0.5 | +0.25 | +10% | 1.25 |
| Superior | +20% | +1 | +0.5 | +20% | 1.5 |
| Exceptional | +35% | +1.5 | +0.75 | +30% | 2 |
| Masterwork | +50% | +2 | +1.0 | +40% | 3 |
| Legendary | +75% | +3 | +1.5 | +50% | 5 |

- **Durability and food** are baked when the good is made (`CraftedQuality.stamp`). A Legendary iron sword has 438 uses instead of 250. Nutrition never changes.
- **Attack and toughness** are added live by `event/GoodsQualityStats.java` through NeoForge's `ItemAttributeModifierEvent`:
  - it uses its own modifier ids (`hearthstead:quality_attack`, `hearthstead:quality_toughness_<slot>`);
  - it goes **on top of** the item's own vanilla modifiers, never in place of them;
  - the vanilla tooltip shows each bonus as its own "+1 Attack Damage" line;
  - it applies to swords, axes, maces and tridents (main hand) and to armour (its own slot);
  - a full Legendary iron armour set gets +6 toughness, still below diamond's +8.
- **Tooltip** (`event/GoodsQualityTooltip.java`):
  - the coloured grade name appears on every stack with a grade;
  - "Basic quality" appears on every workshop output that can be graded, plus the old list;
  - graded goods add "Durability +35%" / "Saturation +20%", computed from the real stack, never assumed.

### 2c. Money (`GoldCoinTrades`)

- `purchase(craftedGood, grade)` now quotes graded crafted rows through `CraftedQuality.salePrice`: the fewest Coins per sale (1-5) whose rate is within 10% of `multiplier / basicItemsPerCoin`.
- An unstackable good (a sword) is always **one** item for 1/1/2/2/3/5 Coins.
- The cost carries the exact grade (`ItemCost.withComponents(expect goods_quality)`), so Basic goods cannot fill a Fine row.
- Graded rows are published only for grades really on hand in the village stock or the player's inventory. They appear after the Basic row of the same good, within the 18-row cap.
- **Purse is still the hard cap:**
  - a sale debits exactly the Coins it paid, 1..5, instead of 1 (`finishOwnedPurchase`);
  - any row that pays more than the purse still holds closes at once, after each sale, on publication and on reopen (`retireUnaffordableRows`);
  - the exact-once receipt (`isDuplicateOwnedSale`), the family surcharge and the purse formula (12 + 2 per 5 settlers, cap 40) are unchanged.
- `isPurchase` accepts a graded crafted row only if it has exactly today's quote, payout and use budget.
- The use budget follows the existing per-grade table: 4 for Basic to Superior, 2 for Exceptional and Masterwork, 1 for Legendary.
- Small fix: the crafted-row picker now uses the effective quote (clamped to the stack size), so one sword on hand is enough to earn a sword row.

Graded crafted quotes, as items -> Coins per sale (Basic / Fine / Superior / Exceptional / Masterwork / Legendary):

| Good | Basic | Fine | Superior | Exceptional | Masterwork | Legendary |
|---|---|---|---|---|---|---|
| bread, cooked beef/pork, ale | 4->1 | 3->1 | 5->2 | 2->1 | 4->3 | 3->4 |
| baked potato | 8->1 | 6->1 | 5->1 | 4->1 | 5->2 | 3->2 |
| cooked mutton/chicken | 6->1 | 5->1 | 4->1 | 3->1 | 2->1 | 4->3 |
| leather, timber beam | 3->1 | 5->2 | 2->1 | 3->2 | 1->1 | 2->3 |
| barrel, wool bolt | 2->1 | 3->2 | 4->3 | 1->1 | 2->3 | 2->5 |
| stone bricks | 16->1 | 13->1 | 11->1 | 8->1 | 5->1 | 3->1 |
| arrows | 32->1 | 26->1 | 21->1 | 16->1 | 11->1 | 6->1 |
| iron axe / pickaxe / hoe / sword | 1->1 | 1->1 | 1->2 | 1->2 | 1->3 | 1->5 |

Income is still limited by the purse (12-40 Coins per visit, 2 visits a day). Grades change how many goods it takes to empty the purse, not how much it holds.

### 2d. Visibility

- **Tooltip:** see 2b.
- **Storage page and merchant screen:** already showed the grade (verified; no change needed).
- **Settler sheet:** the trade-level tooltip of every grading trade (baker, cook, butcher, smith, sawyer, carpenter, mason, fletcher, weaver, tanner, brewer, armourer) now says "Crafts at: Fine – Superior".
  - It lists the grades that are each at least 10% likely at this level and primary attribute, in a level-1 workshop, bare-handed (`CraftedQuality.usualRange`).
  - It is a tooltip line only; there is no layout change (`SettlerScreen.craftsAtLine`).

### 2e. Stacking, couriers and warehouse

- Different grades never merge, because component equality handles it.
- The courier's Hearth lift, stow and the warehouse move `ItemStack` copies and splits. The only component-dropping `new ItemStack(item)` calls in the courier code are classification helpers, not moves (`CourierWorkGoal.java:4404-4426`).
- GameTest `goods_quality_courier` proves this at runtime: grades are conserved every tick and arrive unmerged.

### 2f. Config `[quality]` (`settlement/economy/QualityConfig.java`, hooked in `HearthsteadServerConfig` after `[economy]`)

| Key | Default | Effect |
|---|---|---|
| `craftedQuality` | true | Workshop outputs roll a grade; false = everything crafted is Basic. |
| `statBonuses` | true | Attack/toughness bonuses apply; baked durability/food stay on goods already made. |
| `merchantPremiums` | true | The merchant publishes graded crafted rows. |

- On the GameTest server, `craftedQuality` and `merchantPremiums` are **off** unless a test sets `QualityConfig.testOverride`. This is the same pattern as `EconomyConfig`, so existing trade tests keep pinning Basic outputs.
- This is a server config, so it is synced to clients and the tooltip agrees with the server.
- The integration captain owns `[features]` and has been told about the new section.

## 3. Roll odds by skill (exact, % per grade)

| Trade lv | Attr | Workshop lv | Tool tier | Basic | Fine | Superior | Exceptional | Masterwork | Legendary |
|---|---|---|---|---|---|---|---|---|---|
| 1 (new) | 10 | 1 | 0 | 87.5 | 12.5 | 0 | 0 | 0 | 0 |
| 2 (~1 day) | 15 | 1 | 0 | 72.9 | 26.9 | 0.2 | 0 | 0 | 0 |
| 3 (~3 days) | 20 | 1 | 0 | 52.6 | 42.7 | 4.7 | 0 | 0 | 0 |
| 5 (~10 days) | 30 | 1 | 0 | 15.3 | 54.3 | 29.8 | 0.6 | 0 | 0 |
| 5 | 30 | 2 | 1 | 3.7 | 40.4 | 48.1 | 7.7 | 0 | 0 |
| 7 (~20 days) | 40 | 1 | 0 | 0.3 | 27.6 | 54.9 | 17.2 | 0 | 0 |
| 7 | 40 | 3 | 2 | 0 | 1.7 | 34.7 | 58.7 | 4.9 | 0 |
| 9 (~35 days) | 50 | 1 | 0 | 0 | 3.7 | 40.4 | 53.3 | 2.6 | 0 |
| 10 (~44 days) | 60 | 1 | 0 | 0 | 0 | 23.6 | 64.9 | 11.5 | 0 |
| 10 | 60 | 3 | 2 | 0 | 0 | 0.8 | 45.3 | 51.8 | 2.1 |
| 10 | 99 | 3 | 4 | 0 | 0 | 0 | 22.4 | 65.2 | 12.3 |

Early crafts are Basic or Fine. Legendary is impossible without trade level 9 plus a better workshop or a tool, and it stays near or below 12% even when everything is maxed.

## 4. Tests and evidence

| What | Evidence level |
|---|---|
| `CraftedQualityTest` (JUnit, 6 tests): seeded 200k-roll distribution = analytic odds within 1% at 7 skill points; same seed gives the same grades; early crafts Basic/Fine; the level gates hold even on the luckiest roll; Legendary < 20% at max; durability/attack/toughness/saturation/price tables; iron sword 250/275/300/338/375/438; bread and sword quotes; coins <= 5 a sale; never cheaper than the grade below; stackables within 10% of the multiplier | **PASS** (private build, 26 Sep) |
| Existing `GoodsQualityTest`, `MerchantPursePayloadTest`, `EarlyCoinMerchantTest`, `ProductionWeaverRecipeTest`, `EarlyCoinBudgetTest` | **PASS** (private build) |
| GameTest batch `goods_quality_smith`: a level-10 smith forges an iron sword through `Production.run(..., smith)`. The result is >= Superior, has the graded max durability (> 250), carries the quality attack modifier with exactly the tier's bonus, and uses 2 ingots. A level-1 smith never makes Masterwork+ | W17a (11:29) FAILED: the fixture had no Smithy fuel. Fixed with 16 charcoal; waiting for a re-run |
| `goods_quality_courier`: a real courier lifts 5 Basic + 4 Fine + 3 Superior arrows from the Hearth to the Warehouse. Each grade is conserved every tick, arrives complete, and uses separate stacks | **PASS** (captain W17a) |
| `goods_quality_merchant`: Basic + Fine leather and Legendary sword rows published. Fine leather sells 5 for 2 Coins; each sale debits exactly 2 once under replayed callbacks; Basic leather cannot fill the Fine row; the 5-Coin row closes when the purse is 4 | **PASS** (captain W17a) |
| `goods_quality_legacy`: an old component-less sword and bread read as Basic, with vanilla durability and attack; the tooltip says Basic; Basic crafted bread stacks with old bread; Fine bread does not merge and has more saturation; a graded sword survives a save round trip; an old sword sells on the Basic row | **PASS** (captain W17a) |
| Live gameplay (a settler really crafting graded goods over a day) | not observed; a soak with `[quality]` defaults would show the grade mix |

## 5. Known limits and follow-ups

- **Input grades are ignored.** A Superior leather hide makes the same armour as a Basic one. A future step could add a small bonus for graded inputs.
- **More Warehouse slots.** Graded food takes more Warehouse slots, and the trader's reserve is kept per item and grade (`TraderWorkGoal.java:144`: 128 food / 64 other of each grade).
- **A full bench can waste a batch.** When a graded roll finds no room, the batch does not run. The crafter tries again next cycle, and the couriers empty the bench. `idleReason` reports OUTPUT_FULL when no slot is empty.
- **Vanilla repairs drop the grade.** Vanilla's two-tool crafting-grid repair makes a fresh ungraded tool. Anvil repair keeps the grade.
- **Gatherer grades use older code.** Logs, crops and fish keep their existing rules and price tables. They are not changed here.
