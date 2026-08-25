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

## Price tables (v1 numbers — one balance pass expected after live play)

### Build Plans (crafted, static recipes — discounts cannot apply at a
crafting table, so plans are priced LOW; the real cost of a building is the
architecture the player builds around the plaque)
| plan tier | examples | recipe |
|---|---|---|
| hearthside | house, lodging, well | 1 paper + 1 plank |
| working | farmhouse, lumber camp, warehouse, bakery, mill, tavern | 2 paper + 2 planks + 1 sample of the trade's good (wheat / log / bread …) |
| skilled | smithy, mason, weaver, tannery, fletcher, carpenter, sawyer, smelter, mine, kitchen, brewery | 3 paper + 2 planks + 1 iron nugget |
| civic | dining hall, school, library, architects' study, infirmary, market | 4 paper + 4 planks + 1 book |
| martial | barracks, watchtower, armoury | 3 paper + 2 planks + 1 iron ingot |

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

### Repairs after raids (forward-looking)
Repair dugnad consumes matching materials from the warehouse; **mason
registered -25% stone costs, sawmill -25% wood costs**. Recorded here so
the raid slice prices against the same laws.

## Implementation map (who owns which number today)
- Recruiting: SettlementManager.RECRUIT_PRICE (+ innkeeper patience hook
  already landed). Discount hooks: FOLLOW-UP — move to Costs.java.
- Research: settlement/research project tables (in flight).
- Build plans: recipe JSONs (static — align tiers in a recipe pass).
- Mayor feast: NOT implemented — follow-up in Mayor.appoint.
- **Costs.java (follow-up slice)**: one central class computing
  (base, hooks[], captotal) so every screen can show the same
  itemized "price → your price" breakdown, with the discount lines named.
  No number may live hard-coded in a goal once Costs.java exists.

## UI rule
Every price shown anywhere uses the same two-line form:
"Full pris: 4 brød + 8 planker" / "Bygdas pris: 2 brød + 4 planker
(Vertshusholderen -25%, Spisesalen -25%)" — the discount lines are the
reward surface. Affordability colors from the live chest truth.
