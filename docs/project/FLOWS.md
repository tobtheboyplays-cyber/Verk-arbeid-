# FLOWS — how every building feeds every other, and none is hostage

*The economy's constitution. Written by the coordinator at the user's request:
"de perfekte flows som knytter alle bygningene sammen … mest at de er
avhengig av hverandre, men samtidig ikke." Every recipe table, courier
route and building added from now on must fit this map or argue here first.
D-007 is the law this document operationalizes; the physical rules
(chest truth, conservation, budgeted scanning) bind everything below.*

## The one rule: multiply, never gate

Every dependency edge in the village is a **multiplier between ×1.5 and ×2,
never a requirement**. Concretely: every refining building has a **rough
path** (works from raw goods it can get alone or from the hearth) and a
**fed path** (an upstream building's product makes it noticeably better).
Remove any single building from the village and nothing stops; everything
adjacent gets a little worse. That is the whole trick — the web feels
tightly woven when you have it, and forgiving when you don't.

The **one soft exception** is military hardware: an arrow needs a shaft and
a head somewhere, so the martial chain is allowed real inputs — but always
with a low-tier alone-path (stone-tipped, leather-padded) so a poor village
can still defend itself badly rather than not at all.

## Three rings

**Ring 1 — SOURCES.** Touch the world, depend on nothing, ever:
farmhouse (grain/greens), pasture (animals/wool), fishery (fish),
hunters_lodge (game/hides/feathers), lumber_camp (logs), mine (stone/ore).
Their limits are physical: field size (skill-scaled), herd size, tree
regrowth (the lumberjack replants), vein depth — never another building.

**Ring 2 — REFINERS.** Transform goods; each lists rough path → fed path:

| building | rough path (alone) | fed path (multiplier) | feeds |
|---|---|---|---|
| mill | — (pure upstream: wheat→flour) | — | bakery, brewery |
| bakery | wheat→bread (slow) | flour→bread ×1.5 (effort, not ticks — see note) | hearth, dining hall |
| kitchen | meat/fish→cooked (slow) | + greens→stew ×2 (variety!) | dining hall |
| butcher | livestock→raw meat + hides | pasture keeps it stocked | kitchen, tannery |
| brewery | wheat→small ale (slow) | malt→ale ×1.8 (effort, not ticks) | nobody yet — see note below |
| smelter | ore→ingot (slow) | + bloom path with smithy ×1.67 (ticks — NOT yet re-verified on effort, see note) | smithy, mason |
| smithy | cobble→stone tools (slow) | ingots→iron tools/arms | EVERY worker (tool wear), armoury |
| sawmill | logs→planks | steady lumber-camp supply | carpenter, builds |
| carpenter | logs→rough goods (slow) | planks/beams→furniture, barrels ×1.73 (effort, not ticks) | tavern (build plan), warehouse upkeep (not yet wired) |
| mason | stone→bricks/cut stone | — | repairs (raids!), hearth tiers |
| tannery | hides→leather (slow) | cured-hide path with butcher ×1.5 (effort, not ticks) | armoury, fletcher |
| weaver | wool→cloth | steady pasture supply | market (build plan), outfits (not yet wired) |
| fletcher | flint→crude arrows | feathers→fletched arrows ×1.75 (effort AND ticks agree — no upstream building) | barracks/watchtower |

**Ring 3 — HUBS.** Consume goods, output *effects* on people, never items:
dining_hall (meals eaten together → morale beyond food value),
tavern (ale+food → recruiting draw, fun), infirmary (herbs → healing),
barracks + watchtower + armoury (arms/armor → guard rank ceilings, watch
coverage), school + library (→ learning speed for the young and the clever),
market (surplus → sølvmark, external spice only — can never buy tiers or
blessings), well (water → kitchen/farm cadence), architects_study (→ build
quality reading), warehouse (the circulatory heart — see routes),
house/lodging (sleep quality → tomorrow's dagsverk), dining_hall & tavern
double as the idle-hours social anchors.

## The physical routes (what couriers actually walk)

**Routes are NAMED, never numbered.** They used to be numbered here and
numbered differently in `PLAN_CIRCULATION.md`, so "route 5" meant the
warehouse->hearth food leg in one document and the armoury leg in the other
(Byggherre-dom #3, krav 11). The names below are the canonical ones, and
they are the same strings the code uses -- `CourierWorkGoal.JobPriority` --
so a route can be grepped instead of counted.

| route | leg | courier tier | state |
|---|---|---|---|
| **HARVEST-IN** | farmer's own harvest -> hearth | none: the farmer walks it herself, not the courier | live |
| **CRAFTER_RESTOCK** | warehouse -> refiner input chest (ingredients AND fuel), reservation ledger, chest-true | tier 1 | live |
| **FOOD_DELIVERY** | warehouse -> hearth larder when it runs LOW | tier 2 | live |
| **OUTPUT_COLLECTION** | producer's output chest (and every gathering building's pure yield) -> warehouse | tier 3 | live |
| **WAREHOUSE_CONSOLIDATION** | hearth overflow -> warehouse, the tidy loop | tier 4 | live |
| **MILITARY-OUT** | warehouse -> armoury / barracks / watchtower chests, so smithed arms physically arrive where they are consumed | not yet a tier | planned |
| **SOURCE-OUT** | Ring-1 gathering chests (mine, pasture, fishery, hunters_lodge) -> warehouse | folded into OUTPUT_COLLECTION for all four -- see "Seam findings" below | live |

The four live tiers are one ladder, in that order: **restock -> food ->
collection -> consolidation.** Restock outranks food deliberately: a crafter
with an empty input chest is stopped DEAD this second, while the food route
only fires once the larder is under a day's buffer (4 per living settler,
capped at the hearth's 24 slots) and `EatFromHearthGoal` keeps working down
to the last loaf. Food still outranks tidying -- a hungry village beats neat
shelves. Collection sits above tidying because a stranded output is stock the
restock route cannot even see until it reaches a warehouse.

The hearth is a one-way food valve: food moves TOWARD it and never away
(D-A2a-1). MILITARY-OUT, when it is built, is the same restock machinery
pointed at hub chests, and it belongs below food and above consolidation.

## The one cross-cutting loop: tool wear

Every worker's tool wears with use; a worn tool works at ~70%, never 0%
(a gate would starve the smithy of the very iron it needs). The smithy's
output is therefore the village's tempo dial — always wanted, never
mandatory. Guards' arms/armor live on the same loop through the armoury,
gated by RANK (earned, not bought): a recruit in iron plate is a lie the
village cannot tell.

## Acyclicity (no value mints)

The goods graph must stay a DAG on value: no cycle of recipes may return
more base-value than it consumes. Planks→beam→anything must never yield
planks back; bread is terminal (eaten); ale is terminal (drunk); arms decay
through wear. ChainsGameTests asserts this statically over the Production
table — any new recipe that breaks it fails the build, not the review.

## What this means for the six CHAINS items

FLOUR (mill), MALT (brewery), IRON_BLOOM (smelter↔smithy), TIMBER_BEAM
(sawmill→carpenter), CURED_HIDE (butcher→tannery), WOOL_BOLT (weaver) —
each sits exactly on a fed-path edge above, each has a rough path around
it, and none is consumed by a Ring-1 building (sources stay dependency-free).

**A seventh item, and an honest gap (as of 2026-08-26).** ALE (brewery's
fed-path terminal, malt→ale) is not one of the six above — `ModItems.java`'s
own comment already says why: vanilla has no ale-equivalent item the way the
other five chains arrive at a real vanilla good, so ale is new rather than
filled in. It sits on the same fed-path shape as the rest (rough: wheat→ale
directly; fed: malt→ale at half the ticks per unit), but **nothing consumes
it.** The Ring 3 line above ("tavern (ale+food → recruiting draw, fun)")
names the intended destination, not a live one: `SettlementManager
.tickRecruitment` gates recruiting on cached food and average morale only,
never on ale, and no serving goal exists on `InnkeeperWorkGoal`. This is not
an oversight to paper over with a fake sink — ale's real consumer is a
tavern-serving mechanic, or a festival, that has not been built yet. When one
is, ale is what it drinks. Until then a brewery's ale output is honestly a
dead end, same as BALANCE_AUDIT finding 5 and SURVIVAL_AUDIT F11 both said.

**Three other dead ends from that same finding are closed, one-time.**
WOOL_BOLT (weaver's fed-path good) now has a live consumer: MARKET's build
plan (`build_plan_market.json`) asks for one, closing SURVIVAL_AUDIT F4's
emerald wall and BALANCE_AUDIT finding 5's WOOL_BOLT dead end in the same
stroke — a settlement's own cloth pays for the market plaque instead of a
mountain-biome gamble. BARREL (carpenter's rough/fed output) now feeds
TAVERN's build plan alongside its bread — the "tavern (build plan)" note in
the Ring 2 table above. WHITE_BANNER, not one of the six/seven CHAINS items
but the same shape of pre-existing dead end (BALANCE_AUDIT finding 5), now
feeds WATCHTOWER's build plan: the tower flies the settlement's colours from
the tallest thing it owns. All three are ONE-TIME build-plan costs, not
repeating recipe inputs — a settlement's ongoing surplus of any of them
still has nowhere further to go once its one plaque is drafted. That is
honest progress, not a solved chain: "leads somewhere real" and "has a
repeating economic destination" are different claims, and only the first one
is true here.

## Finding 3 and finding 7, closed — and closed AGAIN, on the right metric (2026-08-26, ECON-1)

**First pass (superseded).** BALANCE_AUDIT.md finding 3 caught this document
overclaiming: the ×1.5-×2 band above was true of bread/leather/ale/barrel
only when measured per-batch-at-the-consuming-building, not when measured
end to end from raw material the way the doc's own iron row and
PLAN_CHAINS.md's "tick-cost rule" both measure it. The first fix retuned
upstream TICKS (MILL's flour/paper, BUTCHER's hide, BREWERY's malt all cut)
and recomputed the same ratio in ticks. That was wrong, and the coordinator
caught it before this task closed: **a crafter's batch costs a flat 2
effort regardless of ticks** (`CrafterWorkGoal`, `spendResearched(2, ...)`),
and effort — not the clock — is what caps a worker's batches/day
(BALANCE_AUDIT.md finding 2/Q4, the SAME defect that made four research
projects measurably inert). A tick cut changes wall-clock feel and changes
batches/day by exactly zero. PLAN_CHAINS.md's own "tick-cost rule" is the
document that taught this mistake in the first place — see that file's own
correction note.

**Second pass, on the correct metric.** Redone as total EFFORT across every
building in a chain, per unit of final output — count batches, since every
batch (any building, any recipe) costs the same 2 effort. Under this metric
bread/leather/ale/barrel ALL measured below the band as originally written
(bread and leather at exactly ×1.0 — no advantage at all; ale at ×1.2;
barrel, even after the first Job 4 fix, at ×2.17, OVER the ceiling). Fixed
the way Job 4 fixed barrel the first time — the only lever effort responds
to is more final good per DOWNSTREAM batch, never a shorter clip:
`bread_flour` now yields 3 bread per 2 flour (was 2), `leather_cured` 3
leather per 2 cured hide (was 2), `ale_malt` 3 ale per 2 malt (was 2), and
`barrel_beam` was re-tuned once more from its first-pass "2 beam → 2 barrel"
(×2.17, over the ceiling) to "3 beam → 2 barrel" (×1.73). Final ratios, all
measured in effort across every building in the chain: bread ×1.5, leather
×1.5, ale ×1.8, barrel ×1.73 — all inside FLOWS.md's band, honestly this
time. The upstream tick cuts from the first pass (flour/paper/hide/malt) are
KEPT — a shorter clip is real for a settler pulled off the bench
mid-batch, and it makes the village look busier — but no comment anywhere
may present them as the source of this multiplier again; `Production.java`'s
comments on MILL/BAKERY/BUTCHER/TANNERY/BREWERY/CARPENTER all say this
explicitly now. Regression coverage:
`ChainsGameTests#fedPathsClearTheFlowsBandMeasuredAsEffortAcrossAllBuildings`
pins all four ratios on the real metric by construction.

**A bigger finding, surfaced and handed back, not fixed here.** Computing
iron's own ratio the same way (effort across SMELTER and SMITHY together)
gives ≈×1.33 — BELOW the ×1.5 floor. `FuelGameTests
#bloomFedPathBeatsRoughSmeltingWithinTheFlowsBand`, the test this document
and PLAN_CHAINS.md both point to as the model this whole slice was supposed
to follow, asserts its band in TICKS — the exact defect this section just
spent two passes fixing. SMELTER/SMITHY's recipes and FuelGameTests.java
both belong to other workers, and iron's chain carries a live fuel-economy
argument (BALANCE_AUDIT.md Q3, ×1.33 less firewood/ingot) this worker has
not re-derived, so it is flagged here for the coordinator rather than
retuned on this worker's own judgement. The smelter row above is left
labelled "ticks — NOT yet re-verified on effort" rather than silently
corrected or silently left to imply it is fine.

## Job 1: the fletcher's missing input, closed (2026-08-26, ECON-1)

Three new gathering trades landed producing real feathers from hunted birds
and wild game (`HunterWorkGoal`), and nothing consumed one until now. The
fletcher row above already named the intent ("feathers+iron heads→true
arrows"), but `Production.Recipe` takes exactly one ingredient, so a recipe
needing both feather AND an iron head at once cannot exist. Implemented as
the fed-pair shape instead: `arrows_feather` (feather, listed first) beside
the untouched `arrows` (flint) rough path — D-007's alone-path holds for a
settlement with no hunter. Arithmetic and acyclicity argument:
`Production.java`'s FLETCHER comment; ratio pinned by
`ChainsGameTests#fletcherWithFeathersOutproducesFlintAlone`.

## Seam findings, closed (adversarial review, 2026-08-26)

Two courier-side seam defects: every worker correct inside its own files,
wrong across the boundary between them.

**Finding 1 -- three gathering trades produced into chests no courier would
ever open.** `CourierWorkGoal.findCollectionJob` recognised only
`BuildingType.MINE` by name as a pure-yield source; PASTURE, FISHERY and
HUNTERS_LODGE (Job 1 above; `HerderWorkGoal`/`FisherWorkGoal`/
`HunterWorkGoal`) have no `Production` table either, but the gate special-
cased MINE and skipped every other no-Production building outright -- the
fisher's cod, the herder's wool and eggs and the hunter's meat and hides sat
in their own chests forever, unreachable by any courier and therefore never
eaten by anyone. Fixed by generalising MINE's own precedent into
`CourierWorkGoal.GATHERING_BUILDINGS`, a small, explicitly-maintained set
(MINE, PASTURE, FISHERY, HUNTERS_LODGE) rather than a blanket "no recipe
table" predicate -- FARMHOUSE and LUMBER_CAMP also have no Production table
but their goals deposit straight into the HEARTH, never their own chests, and
a blanket predicate would have had a courier haul a watchtower's own arrows
(restocked in, never collected out) or a player's own storage out of a
residential/hub building. `findCollectionJob`, `findSurplusOutput` and
`keepBackFor` all read this one set, so the next gathering building joins it
once and every route picks it up for free.
`CourierWorkshopRouteGameTests#gatheredCodReachesAWarehouseAndFeedsAHungrySettler`
proves the whole seam end to end: seeded cod travels fishery -> warehouse ->
hearth, and a genuinely hungry settler is watched actually eating it (hunger
rising through the real `EatFromHearthGoal`), not just chest counts moving.

**Finding 2 -- two courier routes could shuttle the same stack forever.**
`MATERIAL_RESERVE_BATCHES` (restock's own top-up target, raised 1 -> 4 so a
round trip is worth making) pushed several dual-role items' restock target
above the flat `OUTPUT_KEEP_BACK` (8) collection floor: the mason's STONE
restocks to 16 (stone_bricks needs 4/batch x 4) but collected back to 8, the
smithy's IRON_INGOT restocks to 12 (its priciest tool needs 3/batch x 4) but
collected back to 8. Collection trimmed the crafter to the low floor;
restock's very next look saw it short of the high target and hauled the
identical warehouse stock straight back in -- forever, and restock is
`JobPriority`'s TOP tier, so a courier wedged in that shuttle never even
reached the food route. `keepBackFor` already carried this exact guard for
fuel (`FUEL_RESERVE_BATCHES` vs `OUTPUT_KEEP_BACK`); the fix generalises it
to any material an output doubles as: the keep-back for an item is raised to
the restock target of every recipe at that building that consumes it as an
input, so the collection floor is always >= the restock ceiling and the two
bands can never straddle a live stock level.
`CourierWorkshopRouteGameTests#masonsDualRoleStoneReachesAStableRestNotAShuttle`
seeds the mason's STONE at exactly the (now-shared) stable point and asserts
it never moves across a window long enough for the old shuttle to have run
twice over.

## Sequencing honesty

A young village runs entirely on rough paths and feels busy, not broken.
The first mill is the first ×2 the player FEELS; the first smithy is the
second. That order — food tempo before tool tempo before war tempo — is the
intended emotional ramp, and balance changes should protect it.
