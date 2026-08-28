# Building Plaque — design of record

**Superseded in part by D-006 (`docs/project/DECISIONS.md`), implemented in
PLAQUE-1.** The owner later reversed the "no separate Build Plan item, the
plaque is pre-stamped" answer recorded below: a plaque is now placed **blank**
and does nothing — no UI, no survey — until a separate **Build Plan** item is
inserted into it (six plans, one plaque; recipes, not an architect vendor,
since no Architect profession exists in this codebase). The state machine,
glow-vs-state mapping and "no plaque, no building" core idea below are
otherwise current; only the "no plan item" row and its immediate consequences
are the superseded part. See `PLAN_PLAQUE-1.md` for the implementation and
`PlaqueState`/`PlaqueBlockEntity`/`BuildPlanItem` for the code of record.

The owner's uploaded spec plus the decisions taken in the session that
followed it. Where this document and the uploaded spec differ, the decisions
here win: they came later and directly from the owner. Where the spec says
Fabric 1.20.1, this repository is NeoForge 1.21.1 — the spec's own rule
("keep the current versions unless the repository proves a different target")
settles that.

## The one-line idea

**The plaque is the surveyor.** You buy one from the architect, already
dedicated to a building type. You hang it in a room. It scans that room,
measures it against its type's requirements, and glows red / amber / green.
When it is green the building exists, and the plaque is where you decide who
lives or works there.

Nothing else declares what a building is. **No plaque, no building.**

## Decisions (owner, this session)

| Question | Decision |
|---|---|
| What defines a building? | The plaque. Not an automatic sweep, not an item frame. |
| Who scans? | The plaque, on placement and on changes inside its room. |
| Where do plaques come from? | Bought from the **Architect** profession at a drafting table. Not crafted. |
| Price | Village produce plus a couple of minerals. Cheap for a house, dear for a warehouse. |
| Separate "Build Plan" item? | No. The plaque *is* the plan; the UI shows its blueprint. |
| Building levels | Bought as an **upgrade plan from the architect**, and the stricter requirements must also be met. |
| Unmet requirements | Glow **red** when nothing is satisfied, **amber** when partially (e.g. 1 of 2 lanterns), **green** when complete — plus an exact list in the UI. |
| Plaque removed | The building dissolves. Residents go homeless with a morale penalty, after a clear warning. The plaque returns to the player. |
| Work buildings | The same plaque assigns workers, MineColonies-style. |
| Language | English throughout. |
| Signature art | 64x64 for the pieces the player studies up close (plaque, hearth, UI). |

## Architecture

### Ownership of truth

```
Settlement (SavedData)          <- the authority; persists
  └── Building                  <- one per green plaque
        type, level, plaquePos, bounds, beds, workers, residents
SettlerEntity.claimedBed        <- the actual home link
PlaqueBlockEntity               <- anchor + presentation ONLY
        type, buildingId, state, revision
```

The plaque never keeps its own resident list or building registry. It stores a
building id and reads everything else from the settlement. This is the
invariant the spec cares most about, and the one most likely to rot: any code
that puts a resident name inside the block entity is wrong.

### Where the room is

A wall plaque's `FACING` points away from its wall. Players mount plaques both
*inside* the room and *outside* beside the door (the owner's reference image
shows the latter), so the seed search tries, in order:

1. `pos.relative(facing)` — mounted inside, the room is in front.
2. `pos.relative(facing.getOpposite(), 2)` — mounted outside, the room is
   through the wall.
3. the same, at distance 3, for a thick wall.

The first candidate that yields an enclosed room wins. Guessing wrong here
would be the single most common "why doesn't my plaque work" report, so it is
solved by trying rather than by documentation.

### States

`EMPTY` is not reachable here — a plaque is always typed, because it is sold
that way. The rest of the spec's model stands:

| state | meaning |
|---|---|
| `UNLINKED` | placed, but no enclosed room found from any seed candidate |
| `INCOMPLETE` | room found, requirements not all met — amber/red, list in UI |
| `LINKED` | requirements met, building registered — green |
| `ORPHANED` | the building it linked to is gone (settlement disbanded, dimension mismatch) |
| `NO_PERMISSION` | per-viewer, never stored |

Transitions happen server-side only, on: placement, a block change inside the
bounds, the periodic revalidation sweep, and settlement disband.

### Scan budget

Scans stay on the existing `BuildingManager` queue: one scan per 10 ticks per
level, bounded flood fill, bounded retries. Plaque-seeded scanning is strictly
cheaper than the event-driven sweep it replaces, because only plaques can
trigger a scan — a player building a wall no longer seeds speculative scans.

## Requirements, sized against vanilla effort

Each requirement reports a count, not a boolean, so the UI can say "1 of 2".

| type | requires |
|---|---|
| House | 1 bed, 1 door, 1 light, 9 interior cells |
| Lodging | 4 beds, 1 door, 2 lights, 24 cells |
| Warehouse | 4 chests/barrels, 1 door, 2 lights, 25 cells |
| Lumber camp | crafting table, 1 storage, 1 door, 1 light, 16 cells |
| Farmhouse | composter, 1 storage, 1 door, 1 light, 16 cells |
| Architect's study | lectern, 2 bookshelves, 1 door, 2 lights, 16 cells |

All types additionally require what the scanner enforces for any room:
enclosed, roofed, and bounded in size.

## What this replaces

The A1c engine (`RoomScanner`, `BuildingManager` queue, retry, roof rule,
`MAX_HOME_VOLUME`, bed assignment) is kept whole — it was always a *seeded*
scanner. What changes is who seeds it: plaques instead of block events. The
verified behaviour from A1c (5/5 stability, 15 GameTests) carries forward, and
its tests are rewritten to place a plaque where they previously called
`requestScan` directly.

## Acceptance

No part of this is "done" until: the plaque UI cannot open on an unlinked
plaque without showing its diagnostic; assignment and eviction go through the
settlement API and survive a restart; a stale UI cannot act (revision guard);
breaking a plaque drops exactly one plaque and dissolves the building; and the
whole flow has been photographed in a running client, not described.
