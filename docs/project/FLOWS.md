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
| bakery | wheat→bread (slow) | flour→bread ×2 | hearth, dining hall |
| kitchen | meat/fish→cooked (slow) | + greens→stew ×2 (variety!) | dining hall |
| butcher | livestock→raw meat + hides | pasture keeps it stocked | kitchen, tannery |
| brewery | wheat→small ale (slow) | malt→ale ×2 | tavern |
| smelter | ore→ingot (slow) | + bloom path with smithy ×2 | smithy, mason |
| smithy | cobble→stone tools (slow) | ingots→iron tools/arms | EVERY worker (tool wear), armoury |
| sawmill | logs→planks | steady lumber-camp supply | carpenter, builds |
| carpenter | logs→rough goods (slow) | planks/beams→furniture, barrels ×2 | tavern, warehouse upkeep |
| mason | stone→bricks/cut stone | — | repairs (raids!), hearth tiers |
| tannery | hides→leather (slow) | cured-hide path with butcher ×2 | armoury, fletcher |
| weaver | wool→cloth | steady pasture supply | outfits, market |
| fletcher | flint+stick→crude arrows | feathers+iron heads→true arrows ×2 | barracks/watchtower |

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

1. **Sources → hearth** for food only (farmer delivers own harvest; food
   never leaves the hearth — settlers eat there).
2. **Sources → warehouse** for materials (courier fetches from the hearth's
   overflow and gathering points).
3. **Warehouse → refiner input chests** — the restock route with the
   reservation ledger (no double-fetch, chest-true).
4. **Refiner output → warehouse** — consolidation, the tidy loop.
5. **Warehouse → hub chests** (dining hall meals, armoury arms) — same
   restock machinery, hub-priority below food, above consolidation.

Priority ladder (as encoded in CourierWorkGoal.JobPriority, 2026-08-25):
crafter restock (ingredients AND fuel) → food delivery → output collection
→ hearth consolidation. Restock outranks food deliberately: a crafter with
an empty input chest is stopped DEAD this second, while the food route only
fires once the larder is under a day's buffer (4 per living settler, capped
at the hearth's 24 slots). Food still outranks tidying — a hungry village
beats neat shelves.

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

## Sequencing honesty

A young village runs entirely on rough paths and feels busy, not broken.
The first mill is the first ×2 the player FEELS; the first smithy is the
second. That order — food tempo before tool tempo before war tempo — is the
intended emotional ramp, and balance changes should protect it.
