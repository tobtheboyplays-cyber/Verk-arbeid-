# Architectural decisions

Newest first. Each entry: the decision, why, what was rejected, what it
affects, and any migration concern.

---

## D-007 — A courier's load is visible, and carry capacity is a real mechanic

**Decision** (owner directive, A2a). The courier wears a visible load sack.
What they are carrying, and how much they *can* carry, is a real gameplay
quantity — not flavour. Capacity is a property of the carrying rig, so it
is upgradeable: the sack is tier 1, and a **cart** is the intended later
upgrade (bigger capacity, likely slower, possibly path-constrained).

**Reason.** Logistics is the flagship system (`DESIGN.md`) and its whole
appeal is that goods are physically real and physically moved. A hauler
whose load you cannot see, and whose limit you cannot feel, turns that back
into invisible bookkeeping — exactly what the mod exists not to be. Making
capacity visible on the body also gives the upgrade path a silhouette the
player reads at a glance across the settlement.

**Consequences.**
- `SettlerEntity.bag` (8 slots today) becomes the courier's capacity budget
  rather than a fixed constant; the carrying rig determines the limit.
- The pack's fill state should read visually (A2b or later — at minimum the
  courier's carry animation already differs laden vs. empty).
- Bag contents drop on death (implemented in this seam) — a lost load is a
  real loss, which only makes sense if the load was real to begin with.
- The cart upgrade is NOT in A2a. Recorded here so the capacity plumbing is
  not hard-coded in a way that blocks it.

**Rejected.** A fixed invisible capacity constant shared by all settlers,
which would have been simpler but makes the upgrade path meaningless.

**Affects.** `Profession.COURIER`, `outfit_courier` layer (load sack painted
in `gen_settler.py`), `CourierWorkGoal`, `SettlerEntity.bag`, and the
future cart work.

---

## D-006 — Build Plan is a separate item; the plaque does not carry the type

**Decision.** A plaque is placed blank. A **House Plan** item is inserted into
it, and only then does the plaque have a building type and a UI. The state
model is `EMPTY → PLAN_INSERTED_UNLINKED → LINKED_VALID / LINKED_INCOMPLETE`,
plus `ORPHANED` and the per-viewer `NO_PERMISSION`.

**Reason.** The owner chose this explicitly when shown the conflict between
the master operating contract and an earlier answer of theirs. The contract's
rule "no inserted Build Plan means no plaque UI" is non-negotiable.

**Rejected.** The plaque-is-the-plan model (architect sells pre-stamped
plaques, no plan item) — which the owner had answered earlier the same
session, and which the current code implements.

**Affects.** `PlaqueBlockEntity` (must gain `EMPTY` and
`PLAN_INSERTED_UNLINKED`), `PlaqueItemData` (type no longer comes from the
placed item), a new House Plan item, plaque interaction, `PlaqueScreen`,
`PlaqueNetwork`, and `docs/PLAQUE_SYSTEM_DESIGN.md` which still documents the
superseded model.

**Migration.** The code currently stamps the type onto the plaque item via a
data component and surveys on placement. That is a real behavioural reversal,
not an addition. Do it as its own slice, not as a side effect of another.

---

## D-005 — Room detection is seeded by the plaque, not by block events

**Decision.** Only a hung plaque causes a room scan. `BuildingManager` no
longer discovers buildings; it does bed bookkeeping and dissolves buildings
whose plaque is gone. Block changes near a plaque nudge it to re-survey.

**Reason.** The owner: "Et rom blir ikke oppdaget uten plaque. Det er plaquen
som søker etter rom." It also bounds scanning naturally — a player building a
wall no longer triggers speculative scans.

**Rejected.** Automatic world-wide detection with the plaque as a viewer only.

**Affects.** `RoomScanner` (unchanged — it was always seeded), `BuildingManager`
(reduced), `CommonEvents`, `HearthsteadCommand`, every room GameTest.

**Migration.** Buildings created before this have a `plaquePos`; the sweep
dissolves any whose plaque block is absent, which is the intended raid/grief
behaviour.

---

## D-004 — A dwelling must be a bounded room (`MAX_HOME_VOLUME = 512`)

**Decision.** `validHome()` requires interior volume ≤ 512 cells.

**Reason.** Enclosure and roofing alone are not enough: a fill that escapes a
breached house into a large enclosed space (a cave, a walled courtyard, a
GameTest arena under its barrier ceiling) comes back "enclosed and roofed".
Measured: a breached hut filled 1856 cells where the hut interior is 27.

**Rejected.** Relying on the flood fill's own caps; they are a performance
budget, not a semantic rule.

**Affects.** `RoomScanner.Result.validHome()`, every building type.

**Migration.** None. It only ever rejects spaces that were never rooms.

---

## D-003 — Roofing is geometric, never `canSeeSky`

**Decision.** For every cell at the top of its column, search upward within
`MAX_HEIGHT` for a block with a collision shape.

**Reason.** `canSeeSky` reads the heightmap, which the light engine settles
asynchronously. After a large batch of block writes a finished house read as
open to the sky for a tick or two, its single scan was rejected, and nothing
re-scanned it — measured as a 1-in-5 pass rate. It also wrongly rejected glass
roofs.

**Rejected.** Waiting for the light engine, or re-scanning on a timer only.

**Affects.** `RoomScanner`. Regression-locked by `glassRoofCountsAsRoofed`.

---

## D-002 — Target is NeoForge 1.21.1, not Fabric 1.20.1

**Decision.** The mod is built for **NeoForge 21.1.248 / MC 1.21.1** and will
not be migrated to Fabric.

**Reason.** Verified repository fact: `hearthstead-neoforge/gradle.properties`
declares NeoForge; there is no Fabric anywhere in the project. The frozen
`hearthstead/` prototype is Forge 47.4.23 / 1.20.1 — this project has never
been Fabric. The owner chose the version themselves during the design
interview ("du velger den som er best og nyest"), and the operating contract
states that the actual repository takes precedence over its own historical
context section.

**Rejected.** Taking the contract's "Fabric 1.20.1" literally, which would
mean rewriting ~11k lines against a different loader and an older Minecraft.

**Affects.** Everything. Wherever project instructions say "Fabric", read
"the repository's actual loader, NeoForge".

**Migration.** None. Flagged to the owner explicitly rather than silently
reinterpreted.

---

## D-001 — One authoritative owner per kind of data

**Decision.** `Settlement` (SavedData) owns buildings and settler records;
`SettlerEntity.claimedBed` owns the home link. The plaque block entity stores
only its own type, state, revision and a building id — never a resident list
or a building registry.

**Reason.** Two registries drift the first time a settler dies while a chunk
is unloaded, and the drift is invisible until it corrupts a save.

**Rejected.** A plaque-owned resident list, which would have made the UI
trivial and the truth unknowable.

**Affects.** `PlaqueBlockEntity`, `PlaqueNetwork`, `BuildingManager`.

---

## D-007 — Every building is useful alone; a chain is a multiplier, never a gate

**Decision.** No building requires another building to do something. A bakery
bakes bread from whatever grain the settlement holds, on its first day, with no
mill in the world. A smithy forges a rough tool from metal alone, and repairs
worn tools with no inputs at all. Connecting buildings makes each one *better*
— more yield, faster, longer-lasting output — and never makes one *possible*.

**Reason.** The owner's correction to the first chain design, and it is right:
*"Vil at alle bygningene skal funke alene også å gi en funksjon for landsbyen.
Ikke at du må ha en flow for å få noe ut av bygningen."* A gated chain means a
player builds three rooms before seeing one loaf, which is how a spreadsheet is
designed rather than a village. It also makes every raid catastrophic in the
boring way: lose the mill and the bakery stops, rather than slows.

**Rejected.** Strict input chains (MineColonies' shape), where a building idles
until its supplier exists. Depth there comes at the cost of a long dead start.

**Affects.** Every work building, `docs/project/PLAN_PRODUCTION_CHAINS.md`, and
every future profession: a worker must have something to do the moment their
room is registered.

---

## D-008 — A few real intermediate items, not bookkeeping and not a full economy

**Decision.** Intermediate goods exist as **real items in real chests**, but
only where vanilla has no equivalent: **flour, cloth, tool hafts, ale, cured
meat, and prepared meals**. Everywhere vanilla already has the item — planks,
ingots, bread, wool, raw meat — the chain uses the vanilla one.

**Reason.** Owner's choice, 2026-08-25, from three options put to them. It is
the only one that keeps chest truth (INV-3) intact for the interesting part: a
raider can steal your flour, a courier visibly carries it, and the Tingbok can
index it. Bookkeeping-on-the-building would make a chain an invisible bonus
nobody can see moving.

**Rejected.**
- *Vanilla items only*, where a mill merely raises the bakery's yield: cheapest,
  but the chain stops being a thing in the world.
- *A full goods economy* (grain → flour → dough → loaf, ore → ingot → billet →
  head → tool): deepest, but 30+ items to draw, store, route and balance, and a
  warehouse nobody can read.

**Affects.** `ModItems`, the asset pipeline (six new item sprites),
`WarehouseStorage`, courier routing, and every chain in
`PLAN_PRODUCTION_CHAINS.md`.
