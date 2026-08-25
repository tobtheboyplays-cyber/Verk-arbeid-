# SLICE PLAQUE-2 — the plaque becomes the surveyor you can read

Status: **specified by the user, not yet built.** Reference mockup saved
alongside this file as `plaque_reference.png`.

## Why this is Core, in the user's words

*"Så putter du inn en build plan som gir disse arkene som oppdaterer seg
når du putter inn mer ting — prioriter dette siden det er veldig viktig og
brukes i Core. Bruk tid på det, lag et bedre plaque design, og prøv å putte
inn forskjellige build plans med forskjellige bilder og krav til de
forskjellige bygningene."*

This is the permanent invariant made legible. **The plaque is the surveyor**
(D-005/D-006): a building exists because a player hung a plaque and the room
satisfied it.

> **Correction to an earlier claim in this document.** I first wrote that
> three plaques in three states "render identically". **That was my
> measurement error, not the game's behaviour.** I had staged the test by
> `data merge`-ing the block entity's `State` field directly, which never
> calls `survey()` — and `updateGlow()` is what writes the `glow` blockstate
> property. So all three stayed on the default `empty` variant because
> nothing had ever surveyed them. The plaque DOES already signal its state:
> a lamp jewel set into the board, `EMPTY / RED / AMBER / GREEN`, with amber
> specifically meaning "some progress" (`Requirement.Status.partial()`).

**What is genuinely missing is still exactly what the mockup asks for.** A
lamp tells you *whether*. It cannot tell you *what is missing* — that the
room needs one more torch, or has no bed at all. The parchment sheet with
the building's picture, its title and a per-requirement line with counts and
ticks is the part that does not exist, and it is the difference between a
player who knows what to do next and one who is guessing.

## What it must become

**A parchment sheet in a taller frame, readable without interacting.**
The current block is a square iron-and-brass frame with a blank slate
centre. It becomes taller, and the centre carries a parchment sheet with:

1. **The building's picture** — a drawn illustration of the building type
   (house, warehouse, …), taken from the inserted Build Plan.
2. **The title** — `House Lv.1`.
3. **The requirements list**, live: an icon, a name, `have/need`, and a
   green ✓ or a red ✗ per line — `Torch 1/1 ✓`, `Bed 0/1 ✗`, `Chest 0/1 ✗`.

**It updates itself.** No right-click needed to see progress; place the bed
and the line flips to ✓ on the wall. This is the "TekTopia-style glowing
plaque, made satisfying" from the design interview (R13), and it is what
turns the survey from a hidden rule into a build checklist you work against.

## The four states the block must show

From the mockup's own flow strip:

| State | Sheet shows |
|---|---|
| **Empty plaque** | blank parchment with a faint cross-rule — no plan fitted |
| **Plan inserted** | picture + title + all requirements, mostly ✗ |
| **Gathering** | same, some lines flipped to ✓ as items appear |
| **Ready / complete** | every line ✓, the sheet reading as finished |

The mockup labels the three requirement stages INCOMPLETE / PARTIAL /
COMPLETE. These map onto the existing `PlaqueState` — the states already
exist in code and are already persisted; what is missing is that they reach
the *renderer*.

## The right-click UI

A focused screen: building picture, `Level: 1`, a one-line description
("A small house for your settlers. Provides a place to live."), the same
requirements list larger, and a **READY TO BUILD** button when every line is
met. It is a bigger read of the same data, never a second source of truth —
the plaque reads from the settlement, as D-006 requires.

## Different plans, different pictures, different requirements

`BuildingType` already carries per-type requirements — WAREHOUSE needs 4
storage blocks, 1 door, 2 lights and 25 floor space; HOUSE, LODGING,
LUMBER_CAMP, FARMHOUSE and ARCHITECTS_STUDY each carry their own. So the
data exists. What this slice adds is the **picture per plan** and the
**per-requirement icon**, so a warehouse plaque is visibly a warehouse plan
and a house plaque is visibly a house.

## What is built (steps 1-3 DONE)

- **Step 1 — the survey crosses the wire.** `getUpdateTag` carries
  `(id, have, needed)` triples; `survey()` calls `sendBlockUpdated`, so a
  bed placed in front of the player ticks its line over immediately instead
  of waiting for a chunk reload.
- **Step 2 — the block is portrait, and the parchment is shown.**
  `plaque_plan.png` had been generated long before and was referenced by
  nothing; the three lit glow variants now use it.
- **Step 3 — the sheet is written on.** The frame opened out from 5.4x7.4 to
  7.4x8.3 model px (28% -> 51% of the board) so text at a readable size fits;
  the iron brackets were shortened to stop exactly at the brass rails, so no
  bracket crosses a corner of the sheet; the lower rail lifted off the status
  lamp, which had been showing as a 1px sliver of a dome. The parchment was
  re-cut into a **header drawing + ruled line + clear field**, and
  `PlaqueRenderer` writes the title and the live requirement lines onto that
  field. `PlaqueSheet` decides what the lines say — deliberately common code,
  so a GameTest can judge it rather than a screenshot.

**Two honest deviations from the mockup, both recorded rather than hidden:**

1. **The title is `House`, not `House Lv.1`.** `Building.level` exists but
   nothing changes it yet, and the plaque would have to cache a settlement
   field to show it — a second source of truth for a number that is always 1.
   The tier goes on the sheet when the tier system does.
2. **The marks are U+2714/U+2718, not the mockup's lighter U+2713/U+2717.**
   Vanilla's own `nonlatin_european.png` carries the heavy pair; the light
   pair exists only in the unifont fallback and would render as a box.
   Checked in the client jar, not assumed.

**Owner note, acted on twice (2026-08-25):** *"Kan lage litt tydeligere
bygg."* Three versions, each looked at in game at real size:

1. The original 54x54 drawing had corner studs, half-timber braces and thin
   light-ink windows. The sheet face is about half a block wide in world, and
   all of that turned to noise a stride away.
2. Bigger and heavier, one ink weight — clearer, but sized so its eaves and
   chimney ran off the visible edge of the frame's opening, which reads as a
   mistake rather than a drawing.
3. What carries it is **tone, not more line**: the darkest ink in the ramp for
   the roof, a light parchment-shadow fill on the wall, darker panes in the
   windows and a nearly black doorway, so the building reads as a lit solid
   with openings in it. Sized to sit inside the opening with clear parchment
   all round, and with no ground-shadow band of its own — the ruled line below
   already grounds it, and two horizontal rules two pixels apart read as one
   smudge.

## Build order

1. **Get the state to the client first.** The requirement statuses are
   computed server-side in `PlaqueBlockEntity.survey()`; they must be synced
   so the block entity renderer can draw them. Nothing else works without
   this, and it is testable headlessly. **DONE.**
2. **The taller block model** plus the parchment sheet texture, generated by
   the deterministic pipeline like every other asset. **DONE.**
3. **The in-world sheet renderer**: picture, title, and the live
   requirement lines with ✓/✗. **DONE.**
4. **Per-type pictures and per-requirement icons**, one plan at a time.
5. **The right-click screen**, rebuilt to match.

## Acceptance

- A plaque shows **which requirements are unmet and by how much**, on the
  block, with no command and no right-click. The existing lamp already
  distinguishes the states; the sheet is what turns "not yet" into "you need
  one more torch".
- Placing a required block flips its line to ✓ **without** re-opening
  anything.
- A warehouse plan and a house plan are distinguishable at a glance.
- The plaque still stores only its type, state, revision and building id,
  and reads everything else from the settlement (D-006). A requirements
  list cached on the plaque would be a second source of truth.
- Assets stay byte-reproducible across two generator runs (KF-007).
