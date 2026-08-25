# The full building roster: what each one does alone, and the chains on top

*"Lag alle husene som kommer slik du slipper å gjøre det senere. Så tenk langt
fram hva du vil lage. Da må du tenke på lange avanserte flows som det skal
lages som råvarer til mat. Eller tre til verktøy."* — owner, 2026-08-25.

*"Vil at alle bygningene skal funke alene også å gi en funksjon for landsbyen.
Ikke at du må ha en flow for å få noe ut av bygningen."* — owner, same day,
correcting the first draft of this document.

All 28 building types exist in `BuildingType`, each with its own emblem item,
its own requirements and its name in both languages.

## THE RULE THAT OVERRIDES EVERYTHING ELSE HERE

> **Every building is useful the day it is finished, on its own, with no other
> building in the settlement. A chain is an efficiency multiplier, never a
> gate.**

The first draft of this document got that backwards. It described the mill as
"the step between the farm and the bakery", which would mean a bakery does
nothing until a mill exists, and a mill does nothing until a farm exists — a
player would build three rooms before seeing one loaf. That is how a spreadsheet
is designed, not a village.

The correct shape: **a bakery bakes bread from whatever grain the settlement
has, from the first day.** Add a mill and the same grain yields more bread,
faster. The chain is the reward for building the whole thing, not the price of
admission to any part of it.

Two consequences that fall straight out of that rule, and both are good:

1. **Every building accepts inputs from any source** — a courier's delivery, the
   player's own chest, a caravan's cargo, a raid's plunder. Nothing checks
   where a sack of grain came from.
2. **Some buildings are pure services with no inputs at all.** The smithy
   repairs worn tools. The watchtower extends the warning radius. The infirmary
   treats the downed. These are useful before they ever produce a thing.

## What each building does ALONE

| building | on its own, with nothing else built |
|---|---|
| House | houses settlers; its furnishing raises their morale |
| Lodging House | newcomers stop sleeping rough at the hearth |
| Warehouse | the settlement's storage index; couriers gather loose goods into it |
| Architect's Study | **produces Build Plans** — nothing else does |
| School | children grow talents faster; adults retrain |
| Farmhouse | grows and harvests its own fields |
| Mill | grinds **any** grain the village holds; also mills fodder for the byre |
| Bakery | bakes bread from grain *or* flour — flour is faster and yields more |
| Kitchen | turns whatever food exists into meals, satisfying the variety need |
| Dining Hall | settlers eat **together**: morale and the social need |
| Pasture | animals breed → wool, milk, meat |
| Butcher | any raw meat → cuts that keep longer |
| Fishery | fish from water in reach |
| Hunter's Lodge | game and hides from the wild |
| Brewery | ale from any grain |
| Tavern | travellers arrive → recruiting; settlers relax → morale |
| Well House | drinking water, and buckets for the fire during a raid |
| Lumber Camp | fells trees and replants them |
| Sawmill | logs → planks, far faster than a settler by hand |
| Carpenter's Shop | planks → furniture, which raises **every** building's quality |
| Mine Entrance | ore and stone |
| Smelter | any ore → ingots |
| Smithy | **repairs worn tools** — a service needing no inputs — and forges new |
| Weaver's Cottage | wool → cloth → warm clothing before winter |
| Infirmary | the downed are treated rather than lost |
| Barracks | guards train; veterans rank up |
| Watchtower | extends the warning radius; raids are seen coming |
| Market | caravans trade; the only source of Sølvmark |

## The goods that move between them

Decided with the owner, 2026-08-25 (D-008): intermediates are **real items in
real chests**, but only where vanilla has no equivalent.

| new item | made by | from | what it buys |
|---|---|---|---|
| **Flour** | Mill | any grain | a bakery makes more bread, faster, per grain |
| **Cured meat** | Butcher | any raw meat | food that keeps, and feeds the kitchen |
| **Meal** | Kitchen | any two food kinds | satisfies the food-variety need properly |
| **Ale** | Brewery | grain + water | travellers stay; morale at the tavern |
| **Cloth** | Weaver | wool | winter clothing, before the warmth need bites |
| **Tool haft** | Carpenter | planks | tools from the smithy that last far longer |

Everywhere vanilla already has the item — planks, ingots, bread, wool, raw meat
— the chain uses the vanilla one. Six items, not thirty: enough that a chain is
a thing you can see on a courier's back and a raider can steal, few enough that
a warehouse stays readable.

## And then the chains, on top

Everything below is what the settlement gains by connecting buildings that
already work. Each arrow is a courier trip between real chests (INV: chest
truth) — grain physically leaving a farmhouse, riding a courier's back, coming
out as flour that physically exists. That is also why a raider stealing from
the warehouse hurts: they take the middle out of a chain that was making the
whole village better than the sum of its rooms.

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
puzzle, not a build order — and a bakery with no mill still feeds the village,
just on more grain per loaf.

## Chain 2 — Meat and the table

```
Pasture ──livestock──┐
Hunter's Lodge ─game─┼──▶ Butcher ──cuts──▶ Kitchen ──meals──▶ Dining Hall
Fishery ──────fish───┘
```

Three sources into one butcher, because **food variety is a need**. A settlement
that eats only bread has miserable settlers even with a full warehouse — so the
player is pushed to run all three, and each one wants different terrain. Any
one of them alone already feeds people; the butcher alone already makes what
they catch keep longer.

## Chain 3 — Ale, and why the tavern matters

```
Farmhouse ──barley──┐
Well House ──water──┴──▶ Brewery ──ale──▶ Tavern ──▶ travellers recruited
```

The tavern is where recruiting happens (R23). With a brewery behind it the
settlement recruits **faster and better** — travellers stay for good ale — but
a tavern with no brewery still draws travellers and still recruits. The chain
raises the ceiling; it does not hold the door shut.

## Chain 4 — Timber to tools

```
Lumber Camp ──logs──▶ Sawmill ──planks──▶ Carpenter ──hafts──┐
                                       └──furniture──▶ (building quality)
                                                             ▼
Mine ──ore──▶ Smelter ──ingots──────────────────────────▶ Smithy ──tools
```

This is the chain the owner named, and it is where the standalone rule needs
saying out loud, because the obvious design is the wrong one. A smithy that
demanded *both* an ingot and a carpenter's haft before it would make an axe
would be a smithy that does nothing until two other buildings exist.

So: **the smithy forges a tool from metal alone** — a rough one, worn out
quickly. Give it hafts from the carpenter and the same metal makes tools that
last far longer. The chain buys durability, not permission. And the smithy also
*repairs*, which needs no inputs at all, so it earns its room from the hour it
opens.

Furniture from the carpenter feeds **building quality**, which feeds morale, so
the wood chain pays twice — and the sawmill and carpenter are each useful on
their own before either is connected to anything.

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
