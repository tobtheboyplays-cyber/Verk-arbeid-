# The village day: where everyone is, and when

*"Så må du lage et system at når det er «jobbtid» så drar de til jobb.
Ettermiddag så er det mat så kveld legge seg. Hvordan vaktene sin pathing og
slik skal fungere."* — owner, 2026-08-25.

## Why one clock

Before this, "when" was decided independently inside each goal: the rest goal
knew about night, the work goals knew about nothing at all, and a farmer would
happily till at three in the morning.

A settlement reads as **alive** because everyone moves at the same time. The
square fills at the meal and empties at dusk; you can tell the time by looking
out of the window. That only happens if there is one clock, so there is one:
`DayPhase`.

## The day

| phase | ticks | where everyone is |
|---|---|---|
| **RISE** | 23000–1000 | waking, leaving home, gathering |
| **MORNING_WORK** | 1000–5500 | at their own building |
| **MEAL** | 5500–7000 | the dining hall, **together** |
| **AFTERNOON_WORK** | 7000–11500 | back at work |
| **EVENING** | 11500–12700 | tavern, hearth, the square |
| **REST** | 12700–23000 | in their own beds |

Cut at Minecraft's own daylight marks so the phases line up with the light the
player sees.

Two consequences worth naming, because both are free and both are visible:

- **The midday break is real.** `work()` is false during MEAL, so the work goals
  stop and everyone converges on the hall. `EatFromHearthGoal` was widened to
  bring a merely peckish settler to the table at the meal and in the evening —
  without that, everyone grazes alone whenever their own bar dips and a dining
  hall is worth nothing.
- **Dawn is not working hours.** RISE exists so the village wakes *together*
  before it disperses. It is the cheapest moment of life in the whole day.

## A posting is a default, never a law

`Schedule.postFor` answers "walk to which block, and doing what". It defers in
three cases, and the deferring is the design:

1. **Sleep** — the bed goal owns the night; the schedule says nothing.
2. **A guard on watch** — the patrol owns it.
3. **A load in your hands** — a courier mid-delivery is never dragged off to
   stand in the square. This was found by `aFullSackSlowsTheCarrier`, which
   caught a laden courier arriving at the gathering point empty: the schedule
   had walked him somewhere and he had set the goods down there.

Everything above a posting in the goal order — panic, combat, eating, rest —
overrides it, because an alarm and an urgent need both outrank the timetable.

**The unemployed have a posting too**: they gather in plain sight during working
hours. A village whose unemployed are visible is a village whose problem the
player can see without opening a screen.

## Guards keep the other half of the clock

A garrison that all sleeps at midnight is not a garrison.

`Employment.watchOf` splits a barracks in half by **worker index** — derived,
never stored, exactly balanced, and stable across a reload because the worker
list is. A guard with no barracks falls back to the parity of their UUID.

- **Day watch**: patrols through work, meal and evening; sleeps at night.
- **Night watch**: patrols through evening, night and dawn; **takes its rest in
  the afternoon**.

So a raid at two in the morning meets guards who are already awake, and it does
so without a single extra field being persisted.

## Pathing: they keep to the road

Settlers prefer to walk on **dirt path** — what a shovel makes out of grass, and
nothing else. That was the owner's choice and it is the right one for a mod with
no blocks of its own to teach: the player already knows how to build one, it
works in a world they started before installing this, and it needs no recipe,
no research and no explanation.

The preference is expressed as a **penalty for everything else**
(`OFF_ROAD_MALUS = 1.5`, roughly the cost of one and a half ordinary steps), so
a settler will happily walk a good deal further to stay on a path and will still
cut across when the detour is absurd. It bends the route; it does not build a
wall around it.

**Nobody follows the road with a raider in the wheat.** Combat and flight take
the straight line — that was the owner's exemption, and it applies to everyone,
not only guards, because a settler who detoured along a path while running for
their life would look ridiculous.

## And the village wears its own paths

Where settlers walk the same line often enough, the grass gives up and becomes
a dirt path. Nobody decides to build it and nobody is animated laying it: it is
**erosion**, and it appears exactly where the traffic actually is.

This is the only part of the settlement that draws itself. A village that has
stood a month looks lived in without the player placing a block — and because
the pathfinder then prefers those tracks, the routes the village uses most
become the routes it uses more. **Neither TekTopia nor MineColonies does
anything like it.**

It does not break "settlers never construct": a building is a room a player made
and a plaque approved. This is a footprint.

The guard rails, because a system that edits the world on its own is one bad
rule away from vandalising a build:

- **Grass, and only grass** — never a placed block, never farmland, never a
  floor. One block type in, one block type out.
- **Outdoors only** — checked by looking up eight blocks for anything that
  blocks motion, not by `canSeeSky`, whose heightmap is not settled the tick
  after a roof is placed. (That exact difference wore a hole in a test floor.)
  It also means no track under a canopy, which is right: that is a tree, not a
  thoroughfare.
- **Bounded memory** — footfalls live in a capped table that is pruned, never
  allowed to grow with the world, and never persisted. Losing counts costs a
  little patience, never correctness.
- **Counted per block entered**, never per tick, so a sleeper never digs a
  track under their own bed.
