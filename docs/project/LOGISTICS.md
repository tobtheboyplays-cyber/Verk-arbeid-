# LOGISTICS — the optimization game

*Owner: the coordinator, personally, by the owner's instruction
(2026-08-26): "det skal være gøy å holde på for å optimize logistikk. Putte
ting som er tungt nærme warehouse og lignende og UI skal være forståelig og
intuitivt. Men fortsatt et avansert system."*

This document is the contract. Everything below is either **built**,
**next**, or **later**, and the labels are kept honest — a plan that
describes unbuilt things in the present tense is how the armoury ended up
with recipes nobody could be hired to run.

## The loop this system has to produce

A logistics system is a game only when three things are true at once:

1. **The cost is real** — a bad layout genuinely slows the village down.
2. **The cost is visible** — the player can see *which* route is expensive,
   not just that things feel slow.
3. **The cost is reducible by a decision the player can make** — move a
   building, hire a courier, upgrade a cart, split a warehouse.

Miss (1) and optimizing is theatre. Miss (2) and it is guesswork. Miss (3)
and it is a punishment. Every feature below is judged against which of the
three it serves.

## What is already built

- Chests are the truth. Nothing is teleported, nothing is minted; a courier
  physically carries every item (`CourierWorkGoal`, D-A2a-2).
- Four named routes on one priority ladder: **CRAFTER_RESTOCK → FOOD_DELIVERY
  → OUTPUT_COLLECTION → WAREHOUSE_CONSOLIDATION** (`FLOWS.md` holds the
  canonical map; the names are the code's own).
- A reservation ledger, so two couriers never fetch the same stack.
- A bag of `BAG_SIZE = 8` slots, and carry-slow up to `MAX_CARRY_SLOW = 0.38`
  scaled by how full the bag is.
- `StorageScreen`, read-only by design — it shows, the couriers do.

So the skeleton is real and honest. What it does not yet have is **a reason
for layout to matter** or **a way to see the cost.**

## The one missing physical fact: weight

Carry-slow currently scales with **how full** the bag is, not **what is in
it**. Eight iron ingots and eight feathers cost the same trip. That single
fact is why layout does not matter yet, and fixing it is the foundation for
everything else.

**Every item gets a weight class**, and a bag has a *weight budget* as well
as eight slots — whichever runs out first ends the load.

| class | examples | per unit |
|---|---|---|
| light | grain, seed, thread, feather, arrow | 1 |
| ordinary | bread, plank, leather, cloth, tool | 2 |
| heavy | ingot, brick, charcoal, log | 4 |
| dead weight | stone, ore, sand, gravel | 6 |

Consequences, all of which are the point:
- A stone chain moves a quarter as much per trip as a grain chain, so the
  mason near the warehouse is **mechanically correct**, not flavour text.
- Distance stops being linear in importance: it multiplies against trips,
  and trips multiply against weight.
- Nothing about chest truth changes. Item identity, counts and conservation
  are untouched — only how many fit in one walk.

This is the whole of the user's "heavy things near the warehouse" in one
mechanic, and it is deliberately the smallest change that produces it.

## The number the player optimizes: courier-minutes per day

One number, per route: **how much of a courier's working day this route eats.**

    courier-minutes/day  =  trips/day  ×  (walk time out + walk time back)
    trips/day            =  ceil(daily demand / load size)
    load size            =  min(8 slots, weight budget / unit weight)
    walk time            =  path distance / walk speed(load)

It is derived from things the game already knows, and every term is a lever
the player can pull:

| term | how the player lowers it |
|---|---|
| path distance | move the building, or add a nearer depot |
| load size | a cart upgrade, a stronger settler, lighter goods |
| trips/day | reduce demand, or produce nearer the consumer |
| walk speed | roads (later), or a lighter load |

## The UI: the freight book (Fraktboka)

Intuitive on the surface, deep underneath — a tab in the Tingboka.

**The top half answers "what should I fix?" in one glance.** Routes listed
worst-first, one bar each, sorted by courier-minutes/day. The top row is the
thing to fix. That is the entire beginner-facing design: *one number, sorted,
biggest first.*

**The bottom half answers "why?"** for the selected route: from, to, the
goods, distance in blocks, weight per trip, trips per day, and what the
number would become if the building were half as far away. That last line is
the teaching moment — it names the reward before the player commits to the
work.

Rules it inherits and does not get to break:
- **Read-only, like `StorageScreen`.** It shows; couriers do. A UI that could
  move items becomes a second source of truth the moment it disagrees with
  the world.
- Every number is redrawn from the server's latest snapshot with no
  client-side arithmetic, for the same reason.
- Built on the `HsUi` nine-slice kit and the `minecraft-ui` skill's language,
  not flat fills.

**The teaching moment happens earlier, at the plaque.** When a Build Plan is
inserted and the plaque previews the building, it also shows the estimated
courier cost of this site against the nearest warehouse — *before* the player
builds. Placement is where the decision actually gets made, so that is where
the number belongs. A freight book that only ever reports history teaches
nothing in time to matter.

## The advanced layer (for players who want it)

Optional, discoverable, never mandatory:
- **Satellite depots** — a second warehouse claiming a radius, so a far
  quarter stops paying the full walk to the centre.
- **Per-building priority and reserves** — this bench never drops below N.
- **Carts** — a crafted upgrade raising the weight budget, not the slot count.
- **Courier assignment** — pin a courier to a route.
- **Roads** — later; a surfaced path raising walk speed, so distance can be
  bought down instead of only designed down.

## Order of work

1. **KF-027 first.** Eight iron ingots are unaccounted for in the restock
   conservation test. Nothing gets built on top of a logistics system that
   might be losing items — chest truth is the foundation the whole design
   rests on, and I now own it.
2. **Weight classes + weight-budgeted bag.** Foundation. Nothing above works
   without it. Rebalance the existing chains against it and expect several
   throughput tests to move.
3. **Courier-minutes as a real, server-side computed metric**, with GameTests
   that pin the arithmetic.
4. **The freight book**, top half only — worst-first list.
5. **The plaque's placement estimate** — the teaching moment.
6. **Freight-book detail pane**, then the advanced layer.

Steps 2 and 3 change balance. Per the standing sequencing rule they land only
while the suite is green and reproducible, so that every number that moves
afterwards has one explanation and not two.
