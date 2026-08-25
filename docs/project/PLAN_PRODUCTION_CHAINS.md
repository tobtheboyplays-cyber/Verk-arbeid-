# The full building roster, and the chains that justify it

*"Lag alle husene som kommer slik du slipper å gjøre det senere. Så tenk langt
fram hva du vil lage. Da må du tenke på lange avanserte flows som det skal
lages som råvarer til mat. Eller tre til verktøy."* — owner, 2026-08-25.

All 28 building types now exist in `BuildingType`, each with its own emblem
item, its own requirements and its name in both languages. This document is the
reason each one is on the list: **no building exists because it sounds nice. It
exists because a chain needs that step.**

## The rule that shapes every chain

A chain is only interesting if each step is a *place with a person in it*.
MineColonies' depth comes from exactly this and TekTopia's does not — TekTopia's
professions mostly turn a raw input into a finished good in one hop, which is
why its village stops being interesting once it is built. So:

> **Every chain is at least three buildings long, and every arrow is a courier
> trip between real chests.**

That last clause is the invariant doing the work (INV: chest truth). A chain
step is not a recipe in a menu; it is grain physically leaving a farmhouse,
riding a courier's back to a mill, and coming out as flour that physically
exists. It is also why raiders stealing from the warehouse hurts — they take
the middle out of a chain.

---

## Chain 1 — Bread

```
Farmhouse ──grain──▶ Mill ──flour──▶ Bakery ──bread──▶ Dining Hall
   │                                                       ▲
   └──vegetables───────────────────────▶ Kitchen ──meals───┘
```

| step | building | why it is its own place |
|---|---|---|
| grow | **Farmhouse** | fields need a farmer who walks them |
| grind | **Mill** | one millstone serves many fields — the first bottleneck the player must widen |
| bake | **Bakery** | ovens are fuel-hungry; it competes with the smelter for coal |
| serve | **Dining Hall** | settlers eat *together*: morale and the social need |

**The interesting decision:** a mill serves several farms, so the player must
notice the queue at the mill before the bread runs out. That is a logistics
puzzle, not a build order.

## Chain 2 — Meat and the table

```
Pasture ──livestock──┐
Hunter's Lodge ─game─┼──▶ Butcher ──cuts──▶ Kitchen ──meals──▶ Dining Hall
Fishery ──────fish───┘
```

Three sources into one butcher, because **food variety is a need**. A settlement
that eats only bread has miserable settlers even with a full warehouse — so the
player is pushed to run all three, and each one wants different terrain.

## Chain 3 — Ale, and why the tavern matters

```
Farmhouse ──barley──┐
Well House ──water──┴──▶ Brewery ──ale──▶ Tavern ──▶ travellers recruited
```

The tavern is where recruiting happens (R23), so **the recruiting loop is
downstream of the farm**. You cannot buy population; you grow it, brew it and
pour it. That is the single strongest argument for the whole chain design.

## Chain 4 — Timber to tools

```
Lumber Camp ──logs──▶ Sawmill ──planks──▶ Carpenter ──hafts──┐
                                       └──furniture──▶ (building quality)
                                                             ▼
Mine ──ore──▶ Smelter ──ingots──────────────────────────▶ Smithy ──tools
```

This is the chain the owner named, and it is the one that proves the rule: a
tool needs **both** halves. Iron alone is not an axe; a haft alone is not an
axe. The smithy is where two chains meet, which makes it the building whose
loss hurts most — and therefore the one worth defending.

Furniture from the carpenter feeds **building quality**, which feeds morale, so
the wood chain pays twice.

## Chain 5 — Wool to cloth

```
Pasture ──wool──▶ Weaver's Cottage ──cloth──▶ clothing (winter warmth)
```

Short on purpose. It exists because of **seasons**: winter adds a warmth need,
and a settlement that ignored the weaver in summer freezes in winter. A chain
whose consequence is delayed by a season teaches planning better than any
tutorial.

## Chain 6 — Knowledge

```
Architect's Study ──build plans──▶ every other plaque
School ──taught children──▶ faster talent growth
```

The Architect's Study is upstream of *everything*, since a plaque without a
plan does nothing (D-006). It is the first work building any settlement needs
and it should feel like it.

## Chain 7 — Care and defence

```
Infirmary ──▶ the downed are rescued rather than lost
Barracks ──▶ guards;  Watchtower ──▶ warning;  Market ──▶ external trade
```

Defence is not a chain, it is a **tax on every chain** — guards eat, wear tools
and need beds, so a large garrison is paid for in bread.

---

## The roster

| building | works | emblem | the one thing it needs |
|---|---|---|---|
| House | 1 | red bed | a bed |
| Lodging House | — | white bed | four beds |
| Warehouse | 2 | chest | four chests |
| Architect's Study | 1 | lectern | lectern + shelves |
| School | 1 | book | four shelves |
| Farmhouse | 2 | wheat | composter |
| Mill | 1 | hay | millstone (grindstone) |
| Bakery | 2 | bread | two ovens |
| Kitchen | 2 | cooked beef | oven + cauldron |
| Dining Hall | 1 | cake | a hearth fire, and room for a crowd |
| Pasture | 2 | wool | fodder (hay bales) |
| Butcher | 1 | porkchop | smoker |
| Fishery | 1 | fishing rod | water inside |
| Hunter's Lodge | 2 | bow | fletching bench |
| Brewery | 1 | barrel | still + cauldron |
| Tavern | 2 | bell | a bell, and room |
| Well House | — | bucket | four water |
| Lumber Camp | 2 | iron axe | workbench |
| Sawmill | 2 | planks | saw bench |
| Carpenter's Shop | 2 | crafting table | two workbenches |
| Mine Entrance | 3 | iron pickaxe | ladders down |
| Smelter | 2 | furnace | two forges |
| Smithy | 2 | anvil | anvil + smithing bench + forge |
| Weaver's Cottage | 2 | loom | loom + dye cauldron |
| Infirmary | 1 | golden apple | a still and two beds |
| Barracks | 4 | iron sword | four beds and space |
| Watchtower | 2 | spyglass | ladders and light |
| Market | 2 | emerald | stalls |

### Why these requirements

Each work building demands **the vanilla block that station actually is**, so
the player builds a recognisable room rather than hitting a checklist. A smithy
has an anvil in it because a smithy has an anvil in it. That also means every
requirement is measurable by the existing `RoomScanner` with no new machinery.

### What is deliberately NOT a building

- **Graveyard.** A building here is an enclosed room (D-004), and a graveyard
  is not one. It will be a settlement-level place, not a plaque.
- **Fields, pastures out of doors, quarries.** Same reason. The Pasture in the
  list is the *byre* — the roofed part with the fodder.
- **Armoury.** Folded into the Barracks; a separate room with no separate
  decision in it is a chore, not a building.

## What this does not build yet

The types exist, they are surveyable, they show their own emblem and their own
requirements on the plaque, and they persist. **No profession works in most of
them yet.** That is the next several slices, and each one is small now that the
roster and its chains are decided:

1. professions per building (the worker who stands there),
2. the recipe each building turns its inputs into,
3. courier routes between them — which A2a already built the machinery for.

The point of doing the roster now is that none of that work has to stop and
argue about what the buildings are.
