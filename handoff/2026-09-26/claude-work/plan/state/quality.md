# Quality lane state note (26 Sep ~11:00)

## Status
- Code is written and applied to the shared tree. It compiled privately (build-agent-quality).
- JUnit:
  - `CraftedQualityTest` passes 6/6.
  - A full private run gave 916/918. The 2 failures are not from this lane (BarkPicker en_us race, Handbook builder chapter).
- GameTests: the `goods_quality_` batches are handed to the captain (ace73d42bf01cec8d) and have not run yet.
- The shared tree currently fails to compile at `TechTreeConfig.java:52/64`. That is techtree lane work in progress, not quality code.
- Full findings, design and numbers are in `plan/QUALITY.md`.

## For the economy lane (a60cce1309921f3b3, paused)
I changed these parts of `GoldCoinTrades`. The purse formula, receipts and family surcharge are untouched.
- `purchase()` no longer throws for a graded crafted good. It quotes a graded crafted good with `CraftedQuality.salePrice`: x1.25/1.5/2/3/5, 1-5 Coins per sale.
- `finishOwnedPurchase` debits the Coins actually paid (Basic rows are still 1). New `retireUnaffordableRows` closes any row that pays more than the purse still holds.
- `isPurchase` accepts a graded crafted row only when its quote, payout and uses are exact.
- `craftedCandidates` uses `effectiveQuote` (the quote clamped to the stack size). Before, a single sword never earned a row, because the quote asked for 2-3 tools while vanilla clamps an unstackable cost to 1.
- Note: a Basic iron tool therefore really sells at 1 tool = 1 Coin, which is about 3-4x its ingots. The economy lane may want to revisit this.
- Graded rows only appear when `[quality] merchantPremiums` is on and that grade is actually on hand.

## Next, if resumed
1. Read the captain's GameTest results for `goods_quality_*` and fix anything that fails.
2. A soak with `[quality]` defaults, to report the real grade mix per trade.

## Open idea from the ATTRIBUTES lane (a89a1e6fc08a88993)
- They suggest feeding the secondary attribute into the roll as well, e.g. attribute = min(99, primary + secondary/3).
- Not applied. The roll stays primary-only.
- If the owner wants it, change `Production.rollQuality` together with `SettlerScreen.craftsAtLine` and the odds table in `QUALITY.md`.
- The attributes lane's new craft-speed effects do not touch the grade roll.
