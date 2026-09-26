# Proposal: courier throughput and the daily effort budget

This is a proposal, not a change. Nothing in the balance was changed. It is written by the reliability/soak lane, 2026-09-26.

## Evidence

The evidence comes from the worker-watchdog soak (`C:\Users\tobia\Hearthstead-Claude\soak`): a village of 35 settlers with all 26 trades, 3 couriers plus the mayor-courier, and 20 workshops. It ran for 10.4 in-game days after the stall fixes.

| trade | work % of work phase | idle: no input | idle: "effort spent" (mislabel, see 3) |
|---|---|---|---|
| tanner | 0.9 | 89% | — |
| miller | 1.0 | 85% | 3% |
| smith | 4.3 | 85% | 1% |
| butcher | 4.7 | 87% | — |
| brewer | 3.0 | 83% | 1% |
| cook | 8.6 | 78% | 4% |
| smelter | 11.5 | 54% | 25% |
| fletcher | 14.7 | 51% | 25% |
| mason | 10.9 | 66% | 12% |
| carpenter | 9.9 | 47% | 28% |
| courier (x3) | 62.9 | logistics stop 25% | — |

How to read the table:
- **No input:** the workshop's own chest has no batch of any recipe. The watchdog checks `Production.ready()` read-only.
- **Crafters run dry within about one day.** Every workshop started with 1-2 stacks, and after that it depends on couriers restocking it from the Warehouse.
- **Couriers are not saturated.** They are busy 63% of the work phase and idle 25%, mostly `WAITING_INPUT`: the Warehouse has none of the raw input a workshop asks for. So restock is starved by supply (gatherers → Warehouse) more than by courier headcount.
- **Only one raw stream is steady.** Farmers (81% working) and lumberers (44-70%) supply wheat and logs. Nothing supplies raw iron except the miner, leather/hide only comes from the hunter, and wool/string only from the herder. Each has one worker at under 25% work.

## Proposals (numbers to tune, not decisions)

1. **Show supply, not "idle".** When a crafter idles with NO_INPUT and the Warehouse has none of the input either, publish a settlement-level "Missing: raw iron (smithy, smelter)" line on the Banner/Hearth screen.
   - This is UI only; it changes no balance.
   - It turns 80% invisible idleness into a player decision: hire a miner, trade, or stock it.
2. **One courier per ~6 workshops.** This soak had 3 couriers for 20 workshops plus 5 gatherers.
   - The soak did not measure restock latency directly. The watchdog's `samples-*.csv` plus the ledger history could; that is the next measurement to take before changing the headcount.
   - A soft recruitment hint: show "logistics strained" when (workshops + gatherers) / couriers > 7.
   - A hard cap is not recommended.
3. **Effort budget: currently not a gate.** The economy lane checked this on 2026-09-26, and I confirmed it with grep: no work goal calls `isEffortSpent()`. Effort only paces work.
   - The watchdog's "EFFORT_SPENT" idle share in the table above is therefore a **mislabel**. That check ran before the NO_INPUT check, so an input-starved crafter who had spent its units was reported as EFFORT_SPENT. The watchdog now reports EFFORT_SPENT only when nothing else explains the idle.
   - Read the smelter, fletcher and carpenter rows as mostly NO_INPUT.
   - No effort-number change is proposed until the budget actually gates something. That is a design decision for the owner.
4. **Gatherer yields.** Before any crafter number changes, raise the one-worker raw-input trades (miner, hunter, herder) or allow 2 workers per building, so crafters have something to craft. Measure with the watchdog: the target is crafter NO_INPUT under 40%.

## How to re-measure

```
python soak/village.py
python soak/soak.py <label> realtime days3
python soak/analyze.py soak/rundir/world
```

Or turn it on in any world with `/hearthstead watchdog on`, then read `<world>/hearthstead-watchdog/summary-*.json`. Per-job idle reasons are in `idleReasons`.
