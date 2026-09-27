# Wilmot two-door home-route audit — 2026-09-12

## Read-only live evidence

The live server was queried without moving entities or changing blocks.

- At daytime 4397 Wilmot was at `110.300, 77.000, -118.300`, with
  `GoToPostGoal` running for the Warehouse post. He had no saved
  `ClaimedBed`.
- His previous claim block still exists at `(115, 76, -127)`.
- The flushed live region contains two closed oak doors on the route from
  Wilmot's earlier lower-floor position `(115, 72, -124)` to a legal
  bed-contact square `(115, 77, -128)`:
  1. `(109, 72, -122)`
  2. `(111, 77, -127)`

A conservative world-block topology walk that treats only those doors as
open reaches the bed-contact square in 32 physical steps. The relevant
vertical route is:

`(109,72,-122)` door -> `(107,72,-128)` -> oak stairs at
`(105,73,-129)` -> `(107,76,-127)` -> `(109,77,-127)` ->
`(111,77,-127)` door -> `(115,77,-128)`.

The doors are separated by an outside/intermediate stair route; they are
not adjacent. The structure therefore has a physical way up and down in
the saved world.

## Code audit

`RoadNavigation` enables both `canPassDoors` and `canOpenDoors`.
`SettlerDoorGoal` runs flag-free at priority 1 and recomputes the
navigation path when it opens a closed wooden door. It then closes only
after the actor has crossed and cleared the doorway.

That existing flow should process the two doors independently: first
door -> recompute -> walk stairs -> second door -> recompute. A change
that keeps multiple doors open at once would broaden door authority and
is not justified by this topology.

`RestAtNightGoal` is intentionally simple: it calls
`navigation.moveTo(bed.x + .5, bed.y + 1, bed.z + .5, .9)` every
60 ticks until sleep contact. It currently exposes no path-node or
door-goal diagnostic, so the current live state cannot identify which
of the first door, stair segment, second door, or collision/stuck
detection failed before the claim disappeared.

## Safe next reproduction

Add one bounded GameTest before changing production routing:

- spawn a real bound settler at `(115,72,-124)`;
- reproduce this two-door/stair sequence, with both doors initially
  closed and no alternative route;
- drive a real `RestAtNightGoal` toward an upper bed;
- assert each exact door was visibly opened, the actor reached a legal
  bed-contact cell and slept, then assert both doors closed only after
  clearance;
- on failure log path `isDone/canReach/nextNode/nodeCount/target`,
  running goal names, both door states and actor position.

This distinguishes a pathfinder/stair defect from a door-goal lifecycle
defect without releasing a valid bed claim or bypassing the route.

