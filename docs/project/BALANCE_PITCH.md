# Balansepitch — ni forslag, til byggherrens dom

*Owner's instruction, 2026-08-26: play a full survival playthrough, come with
proposals to balance and improve, and pitch them to the owner-critic for
judgment.*

Each proposal below states the **measured** defect, the **proposal**, and what
it **costs**. Every number is read from the source, not remembered. Where a
claim rests on reading rather than on play, it says so — that distinction has
cost this project twice already.

---

## P1 — Wire the weight table (the flagship, currently a no-op)

**Measured.** `logistics/Weight.java` has **zero callers in the entire mod.**
Not one. A courier's load is bounded by item COUNT alone, so eight iron
ingots and eight feathers cost exactly the same walk.

**Why it matters most.** This is the feature the owner asked for by name —
*"putte ting som er tungt nærme warehouse"*. With every trip priced
identically, **no arrangement of buildings can beat any other**, and the whole
optimization game he asked for does not exist. "Put the mason near the
warehouse" is currently flavour text.

**Proposal.** Bound each load by mass as well as count: LIGHT 1 / ORDINARY 2 /
HEAVY 4 / DEAD_WEIGHT 6 against a 16-unit bag. Eight ORDINARY items = exactly
16, so **the median load does not change** and every chain balanced to date
keeps its throughput. Stone and ore halve or third. Grain and bread are
untouched.

**Cost.** Heavy chains (mine→smelter, mason) move less per trip, so distance
starts multiplying: trips × weight × walk. That is the intended effect and it
is also a real economy change — it must not ship without a green suite. Patch
is written and held back deliberately.

---

## P2 — Two production outputs still have no consumer

**Measured.** `WOOL_BOLT` and `WHITE_BANNER`: zero consumer files outside the
recipe that creates them. (ALE and BARREL were on this list and have since
been wired — 13 and 3 consumers now. These two were not.)

**Proposal.** Either give each a sink, or cut it. Cheapest honest sink:
wool bolt → a bed/decor requirement in HOUSE quality; banner → a Tradition or
festival marker. If neither lands today, **remove them from the recipe table
for the demo** rather than let a weaver produce goods that do nothing.

**Cost.** Small. Removing is one table edit; the weaver keeps its other output.

---

## P3 — Recruitment gates on a food STOCK, not on food FLOW

**Measured.** `SettlementManager`: `attractive = population < capacity &&
foodCache >= 8 && morale >= 60`. Housing (`capacity() = 3 + beds`) is the only
real throttle. Nothing tests whether the village can *keep* feeding the people
it recruits.

**Proposal.** Add a flow term: require food stock ≥ 2 × population, not a flat
8. It is one expression, uses a number already tracked, and turns "I built
beds" into "I built beds AND a farm".

**Cost.** Slower growth mid-game. That is the point — but it is a difficulty
change and the owner should choose it, not have it chosen for him.

---

## P4 — The fed-path bonus misses its own stated band on 3 of 5 chains

**Measured.** `FLOWS.md` mandates ×1.5–2. Measured end-to-end: bread ×1.07,
barrel ×1.29, leather ×1.06, ale ×1.36. Only iron (×1.67) clears the floor.

**Proposal.** Pick one and hold it. Either raise the three low chains to the
band, or lower the documented band to what the game actually does. **The
current state is the only unacceptable one** — a design doc that states a rule
the code does not follow is how the next person builds on a false floor.

**Cost.** Raising yields is an economy change needing a suite run. Lowering
the doc is free and honest. My recommendation: lower the doc today, raise the
chains after the demo.

---

## P5 — The barrel's "fed" recipe is strictly worse than the rough one

**Measured.** `barrel_beam` (fed) and `barrel` (rough) both yield exactly 1
barrel per batch. Effort — a flat 2 per batch regardless of building — is the
binding daily resource, so the fed path costs a sawyer's separate effort
budget and returns nothing extra.

**Proposal.** Fed barrel yields 2. One number.

**Cost.** One number, and it makes a currently pointless choice into a real one.

---

## P6 — Charcoal is fuel-neutral

**Measured.** The smelter's `charcoal` recipe turns 1 log into 1 charcoal at
90 ticks. Both are flat "1 unit" to `Fuel.perBatch` and both stack to 64. It
spends smelter time to convert one fuel item into one fuel item.

**Proposal.** Make charcoal worth 2 fuel units, or cost 2 logs. Either makes
the recipe mean something; today it is a way to waste 90 ticks.

**Cost.** Touches `Fuel`, which several chains read. Needs a suite run.

---

## P7 — The Well does nothing at all

**Measured.** `WELL` declares 0 residents and 0 workers and is referenced by
**no production code anywhere**. It is the only building type inert in both
directions. It can be planned, built to spec, and validated green, and nothing
in the game ever reacts.

**Proposal.** Cut it from the demo build. A building that validates and then
does nothing teaches the player that validation means nothing.

**Cost.** None mechanically. It is a visible feature removal, which is the
owner's call, not mine.

---

## P8 — Three buildings advertise jobs that cannot be filled

**Measured.** SCHOOL (1 worker), MARKET (2), INFIRMARY (1) all declare worker
capacity, so the plaque offers hiring — but none is in `Employment.TRADES`, so
every hire is refused with `no_trade`.

**Proposal.** Set their `workerCapacity` to 0 until their trades exist. The
refusal is honest, but offering a slot that can never be filled is a promise
the building cannot keep.

**Cost.** Three numbers. Reversible the day a SCHOOLMASTER exists.

---

## P9 — Research materials are the only goods no courier will carry

**Measured.** Long-standing and documented. Research consumes items, logistics
delivers items, and the two are not connected. Every project's paper and
domain items are a manual errand, forever, however mature the logistics get.

**Proposal.** Add the Architect's Study as a restock destination on the
existing courier route. It is the same job shape the route already runs.

**Cost.** Real work in `CourierWorkGoal` — not a today change. Listed because
it is the largest remaining hole in the logistics web, and the owner should
know it is known.

---

## What I am NOT proposing

- **No difficulty rebalance of raids.** Pressure curve and raid pacing are
  plausible-but-unmeasured; I have not played enough raid nights to have a
  number worth defending, and a guess here would be worse than silence.
- **No change to the plaque/room rules.** They are the spine, they work, and
  the demo depends on them.

## Ranking, if only some land

1. **P1 (weight)** — it is the owner's own asked-for feature and it is dead.
2. **P8, P7, P2** — cheap, honest, remove things that lie to the player.
3. **P5, P4-as-doc-fix** — one number each.
4. **P3, P6** — real balance changes, need a suite and an owner decision.
5. **P9** — after the demo.
