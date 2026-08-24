# PLAN — SLICE A2a: Warehouse, Courier and the carry grammar

Source: opus-planner PLAN_GATE output (A2a's one planning call, spent).
STEP 0 (the seam) is **DONE** — see the commit that added
`Profession.COURIER`, `CARRYING`/`SORTING`, `WarehouseIndex.containers()`,
the `CourierWorkGoal` stub, the writ item chain and the bag-drops-on-death
fix. What follows is the fan-out.

## Goal

A courier visibly carries real goods from the hearth to a warehouse, and
the player can see what the settlement is storing. Chests stay the truth.

## Firm scope

**IN:** warehouse as a logistics destination + derived item index;
`CourierWorkGoal`'s real state machine; `WALK_LADEN` +
`COURIER_LIFT/CARRY/SET_DOWN/SORT`; 7 sounds; a read-only Storage view.

**OUT (→ A2b):** tavern/recruiting chain, role cards, lodging assignment,
cook/meals/dining hall, needs expansion, wishes, reservations/requests/
priority routes, courier fetching *to* work sites.

## Key decisions (record in DECISIONS.md as they land)

- **D-A2a-1 — Food stays at the hearth.** The courier hauls **non-food**
  goods only. `EatFromHearthGoal` and `Settlement.foodCache` read hearth
  contents; draining food would starve the settlement. Food test:
  `stack.getItem().components().has(FOOD)`.
- **D-A2a-2 — Chests are truth (INV-3).** `WarehouseIndex` is a derived,
  revisioned cache with a bounded refresh. It never holds items and is
  never the basis for a transfer without re-reading the container.
- **D-A2a-3 — Transfer ordering.** Insert into the destination first,
  remove from the source only by the accepted amount; the bag is the only
  intermediate; leftovers return to the hearth. Bag drops on death (done).
- **D-A2a-4 — Employment.** The writ makes a settler a courier; the goal
  requires ≥1 *valid* WAREHOUSE building, preferring one whose
  `Building.workers` contains this settler. `PlaqueNetwork` already
  assigns workers — no new employment system.
- **D-A2a-5 — Storage view.** Sneak-use the Handbook inside a settlement
  to open a read-only `StorageScreen`. Does **not** touch plaque UI
  (protects D-005/D-006).
- **D-007 (recorded)** — the courier's load is visible; carry capacity is
  a real, upgradeable mechanic (sack now, cart later).

## Pieces (parallel after STEP 0, strict file ownership)

**Piece 1 — Warehouse index (server).**
OWNS `settlement/warehouse/WarehouseIndex.java` (extend the seam),
new `WarehouseStorage.java`, `gametest/WarehouseGameTests.java`.
Builds: revisioned per-building tally + container list, refresh at most
once per N ticks or on demand, hard visit cap, `insert(level, building,
stack) → leftover` honouring D-A2a-3.
*Accept:* index equals a brute-force recount after arbitrary player edits;
refresh cost bounded (assert the visit counter); insert conserves items
when chests are full; index is rebuilt on load, never persisted.

**Piece 2 — Courier goal (AI).**
OWNS `entity/ai/CourierWorkGoal.java`, `gametest/CourierGameTests.java`.
State machine: idle → hearth (non-food present) → `COURIER_LIFT` event,
set `CARRYING` → path to warehouse (`WALK_LADEN` + `COURIER_CARRY`) →
`COURIER_SET_DOWN` → `SORTING`, one stack per 32-tick loop with the actual
move on `workTicks % 32 == 16` → idle. Sound accents at the catalogue's
exact tick-modulo values (anim_check greps the goal source for them).
*Accept:* goods reach warehouse chests; **total world item count identical
before/after**; food never leaves the hearth; no warehouse ⇒ idles without
thrashing; warehouse full ⇒ goods return, nothing vanishes; killed
mid-carry ⇒ bag drops, count conserved; save/reload mid-carry is safe.

**Piece 3 — Animation (client).**
OWNS `client/model/SettlerAnimations.java`, `client/model/SettlerModel.java`,
`tools/anim_check.py`.
The 5 clips per catalogue §1.2, §5.1–5.4 incl. the standing-still carry
variant. `WALK_LADEN` replaces `WALK` when carrying (and is suppressed
while stopped); `COURIER_CARRY` arms clamp to 3°. Add
`CARRY_LAYER_CLIPS ⊇ {COURIER_CARRY}`, `ENDS_IN_POSE_ALLOWLIST ⊇
{COURIER_LIFT}`, and the new sound-contract rows.
**Apply `.claude/skills/animation-quality`** — the lift and set-down are
weight-bearing beats and need anticipation, a hold and follow-through, not
a linear pass. Verify visually via the Blockbench bridge before shipping.
*Accept:* `tools/hearthstead-qa animation` green (needs piece 4's sounds.json rows).

**Piece 4 — Sounds.**
OWNS `tools/gen_sounds.py`, `sounds.json`, the new `.ogg` files.
`haul_step, crate_grip, haul_strain, crate_creak, crate_down, item_pickup,
chest_stow`.
*Accept:* byte-deterministic regeneration (the encoder is already bitexact);
each sound audibly distinct and short; `validate_assets.py` green.

**Piece 5 — Storage view (client + net).**
OWNS `network/StorageIndexPayload.java`, `StorageRequestPayload.java`,
`client/screen/StorageScreen.java`, `item/HandbookItem.java`.
Payload shape fixed: `(String warehouseName, int distinctTypes, int
totalItems, List<ItemStack> top12)` / `()`.
*Accept:* sneak-use shows distinct types, totals and top stacks; empty
state when there's no warehouse; server never loads a client class
(INV-6); screenshot inspected.

## Shared-file rules

| File | Owner |
|---|---|
| SettlerEntity, SettlerActivity, Profession, ModItems, ModSounds, ModBusEvents, both lang files | **STEP 0 only** — no piece edits them; raise, don't patch |
| SettlerAnimations, SettlerModel, anim_check.py | Piece 3 |
| sounds.json, gen_sounds.py, .ogg | Piece 4 |
| HearthsteadGameTests.java | **Nobody** — each piece adds its own `@GameTestHolder` class |
| WarehouseIndex | Piece 1 (2 and 5 read only) |
| Payload records / screen | Piece 5 |

**Ordering:** piece 4 lands before piece 3's final `animation` run
(anim_check asserts each contracted sound exists in sounds.json). Until
then piece 3 may see exactly those rows red and must say so rather than
edit the check.

**ModSounds constants** are deliberately NOT in the seam: adding a
registered SoundEvent with no `sounds.json` entry fails the assets check
immediately. Piece 4 adds the constants together with the assets.

## Risks and what reveals them

- Starvation (courier draining hearth food) → existing
  `settlerEatsFromHearth` + a new food-untouched assertion.
- Item duplication/loss → conservation assertions + dedicated-server
  restart persistence.
- Plaque drift (D-005/D-006) → existing plaque GameTests stay green.
- Locomotion regression (WALK_LADEN hijacking non-carrying settlers) →
  visual check that other professions still walk normally.
- Per-tick scan cost → `performance` suite must not regress.
- Wire-format break → activity ordinals appended only; a pre-A2a save
  must load.

## Unknown to prove first

Multi-`@GameTestHolder` discovery: everything lives in one class today.
The first piece to add a new holder class must confirm
`tools/hearthstead-qa gametest` actually discovers it; if not, fall back
to clearly-delimited non-overlapping regions in `HearthsteadGameTests.java`
and say so.

## Test strategy

Per piece (cheap): `compileJava` while iterating, then
`tools/hearthstead-qa quick`. Integration: `behavior` (thrash/stuck/
starvation on a courier round trip), `dedicated` (restart persistence),
`performance`. Runtime proof: `playtest` + `visual` + a `live film`
showing one courier lifting at the hearth, walking laden with a visible
lean and locked arms, setting down and sorting into a chest — with the
Storage view screenshot showing the same items. Nothing is complete until
`tools/hearthstead-qa gate` reports PASS with green_streak ≥ 2.
