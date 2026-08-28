# COSTS — what everything costs, and how the village earns it down

*The pricing constitution, companion to FLOWS.md. Written by the coordinator
at the user's request: "finn opp priser som virker naturlig … og villagen
hjelper med å få ned kostnaden." Every price a player pays must obey the
three laws below, and every price lives in real goods taken chest-true from
real containers — Hearthstead has no abstract points to spend.*

## The three laws

1. **Pay in what the thing is made of.** A build plan costs drafting
   materials; a recruit costs hospitality; research costs paper and the
   samples being studied. A price should EXPLAIN itself.
2. **Bygda hjelper til.** Every recurring cost carries one or two *named
   discount hooks* — capabilities the village can build that visibly lower
   it. Discounts multiply, cap at **-50%**, and always name their source in
   the UI ("Vertshusholderen pruter: -25%"). The player should always be
   able to answer "how do I make this cheaper?" by building something.
3. **First one cheap, the rest honest.** The first of anything costs about
   half its steady price (the mod teaches through the first purchase);
   scaling beyond that is gentle and linear, never exponential.

**How discounts stack (settled by implementation, 2026-08-25):** additively,
then capped at -50%. This document's own worked example — an innkeeper and a
dining hall turning 4 bread + 8 planks into 2 + 4 — only reproduces under
addition; multiplicative stacking lands at -43.75%. The discount amount
rounds up so the price rounds down (the player's favour), and no line ever
falls below one item.

## Price tables (v1 numbers — one balance pass expected after live play)

### Build Plans (crafted, static recipes — discounts cannot apply at a
crafting table, so plans are priced LOW; the real cost of a building is the
architecture the player builds around the plaque)

SHIPPED 2026-08-25 (RECIPES-1, Byggherre-dom #1 krav 5): all 33 registered
BuildingTypes are craftable, every ingredient bare vanilla and obtainable in
the first days — no mod intermediate, because a fresh world must reach the
smithy before the mod's own economy runs. Every plan carries paper + feather
(the writ) plus its domain sample; tier shows in the paper count and the
sample's value. `BuildPlanRecipeGameTests` is the ratchet: a 34th building
fails the suite until it is priced here.

**Deviation from this document, deliberate:** the skilled tier said "iron
nugget". Vanilla 1.21.1 cannot craft a nugget from an ingot (only by melting
a finished tool, or by loot), so a nugget price would force a day-one player
to destroy a tool. Ingots carry the same signal without the trap: 1 for
skilled, 2 for martial.

**Second deviation, added 2026-08-26 (SURVIVAL_AUDIT F4):** the civic tier's
market sample was `minecraft:emerald` — the one ingredient across all 33
plans with no guaranteed hand-source (no craft, no biome/seed guarantee).
Fixed by swapping it for `hearthstead:wool_bolt`, the weaver's own fed-path
good, which is also the only build-plan ingredient in this table that is not
bare vanilla. That is a deliberate, narrow break from "no mod intermediate"
above: MARKET is civic tier, never a day-one building (it already needs a
book, itself downstream of a functioning economy), so gating it on the
settlement's OWN production — Law 1, "pay in what the thing is made of" —
reads truer than gating it on vanilla luck ever did. It also closes
BALANCE_AUDIT finding 5's WOOL_BOLT dead end in the same stroke (see
FLOWS.md's CHAINS note). No other plan below needs this exception.

| tier | paper | buildings and their sample |
|---|---|---|
| hearthside | 1 | house (#planks) · lodging (bed) · well (stick) |
| working | 1 | farmhouse (seeds) · lumber_camp (#logs) · warehouse (chest) · bakery (wheat+coal) · mill (2 wheat) · tavern (2 bread+barrel) · pasture (wheat+#wool) · butcher (porkchop) · fishery (string) · hunters_lodge (arrow) |
| skilled | 1 | smithy (2 ingot) · smelter (cobble+coal) · mason (2 stone) · weaver (2 string) · tannery (2 leather) · fletcher (flint+stick) · carpenter (2 #planks) · sawmill (2 #logs) · mine (2 raw_iron) · kitchen (beef+coal) · brewery (sugar+bottle) |
| civic | 2 | architects_study (book) · dining_hall (book+bread) · infirmary (book+bottle) · market (book+wool_bolt) · school (2 books) · library (3 books) |
| martial | 2 | barracks (ingot+#wool) · watchtower (ingot+arrow+banner) · armoury (2 ingots) |

All wood is tagged (`#minecraft:planks`, `#minecraft:logs`, `#minecraft:wool`)
— the exact-oak trap that stopped a birch-forest settlement from recruiting
is not repeated here. All 33 ingredient multisets are pairwise distinct, so
no shapeless recipe can shadow another.

### Recruiting (charged chest-true from the hearth when a traveler joins)
Base: **4 bread + 8 planks** (a bed's worth of boards and a week of meals —
the price says "we can house and feed you").
Hooks: **innkeeper employed -25%** (hospitality is her trade);
**dining hall registered -25%** (a village that eats together convinces).
Floor at -50%: 2 bread + 4 planks. A rich village barely notices; a young
one feels every loaf — exactly the curve we want.

### Research (charged on project start, at the study)
Base per project: **4 paper + domain samples** (see PLAN_RESEARCH tables) +
3 scholar work-days.
Hooks: **library registered -25% materials**; **scholar's WITS tier**
shortens work-days (not materials): -1 day at WITS 40, -1 more at 80.
Cancel refunds 50% (the spoiled half is the price of indecision).

### Hiring & dismissal (unchanged, deliberately priceless)
Hiring costs no goods — its price is the candidate's fitness and what their
old post loses (the cost sentence on the card). Dismissal costs morale.
These stay social costs, never material ones: staffing should be fluid.

### Mayor swap (new, small): the feast
Appointing a NEW mayor while one sits costs **8 bread** (the handover
feast) — charged from the hearth. First appointment free. Hook: **dining
hall registered -50%** (the feast is cheaper where feasts are normal).
Mourning still blocks appointment outright; grief takes no bribes.

### Repairs after raids (SHIPPED 2026-08-26, COSTS-2)
The repair dugnad (`RepairWorkGoal`) consumes one real matching material
PER BLOCK, per scar, from the repairer's own building or the hearth —
there is no settlement-level *price* here for a percentage to come off of,
so **mason registered -25% stone costs, sawmill -25% wood costs** cannot
mean "fewer blocks pay" the way every other price on this page works.
Wired instead as **some scars mend free**: a running per-settlement count
of completed mends waives the material on every `(100 / percent)`th one —
every 4th scar with one hook (the capped 25%), every 2nd with both (the
capped 50%). Deterministic on purpose (a coin flip has no place next to a
suite already fighting nondeterminism), and still chest-true — fewer
items ever leave a chest, nothing is conjured, and no item is ever
partially consumed. Full reasoning: `Costs.PriceKey#REPAIR`'s javadoc;
mechanism: `RepairWorkGoal#shouldMendFree`.

## Implementation map (who owns which number today)
- Recruiting: SettlementManager.RECRUIT_PRICE (+ innkeeper patience hook
  already landed). Discount hooks: SHIPPED in Costs.discountsFor (2026-08-25).
- Research: CHARGED, live (`Research.start`, Research.java:181-220): every
  cost line is paid up front and atomically from chests/barrels standing in
  the study, with the library's -25% applied via `Costs.discounted`. This
  line previously said "still NOT charged anywhere" — that was stale, caught
  by SURVIVAL_AUDIT.md's cross-check on 2026-08-26, and is exactly the class
  of drift the audit exists to catch: the code moved and the ledger did not.
- Build plans: recipe JSONs (static — align tiers in a recipe pass).
- **Mayor feast: SHIPPED (COSTS-2, 2026-08-26)** in `Mayor.appoint` via
  `Costs.mayorFeast()` — charged chest-true from the settlement's hearth on
  an actual swap only (the first appointment stays free); the dining-hall
  -50% hook applies through `Costs.afterDiscounts`; a village that cannot
  pay is refused and the seat does not change.
- **Repairs: SHIPPED (COSTS-2, 2026-08-26)** in `RepairWorkGoal` — the
  mason/sawmill hooks are real (read through `Costs.discountsFor`) but
  spent as "some scars mend free", not as a `Price`/`Line` reduction; see
  the Repairs section above.
- **Costs.java (SHIPPED 2026-08-25)**: one central class computing
  (base, hooks[], captotal) so every screen can show the same
  itemized "price → your price" breakdown, with the discount lines named.
  No number may live hard-coded in a goal once Costs.java exists. All four
  `PriceKey` rows now have a real caller except `RESEARCH`, which stays
  reserved for the research slice's own not-yet-built pricing.

## UI rule
Every price shown anywhere uses the same two-line form:
"Full pris: 4 brød + 8 planker" / "Bygdas pris: 2 brød + 4 planker
(Vertshusholderen -25%, Spisesalen -25%)" — the discount lines are the
reward surface. Affordability colors from the live chest truth.
