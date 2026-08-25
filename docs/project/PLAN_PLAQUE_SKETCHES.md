# PLAQUE-2 step 4 — a picture for every building

*"Så må du lage en sketch for alle fremtidige bygg"* — owner, 2026-08-25.

## Eight versions, and the one that was right

The ask started as "a sketch for every building" and ended by deleting all the
art. Every note from the owner killed one wrong idea, and they are worth
keeping in order, because the last one is only obvious after the first seven:

| note | what it killed |
|---|---|
| *"Kan lage litt tydeligere bygg"* | thin-line sepia elevations |
| *"Synes tegningene er litt tamme … gjerne en hel ny tegnestil"* | refining the same elevation again |
| *"Nei mye mye tydligere. Se på tektopia og minecolonies så tilpasser du"* | drawing the BUILDING at all |
| *"Må ha tydelige logoer så man vet hva de forskjellige er"* | a picture with no trade mark |
| *"Jeg ser ikke hva de er. Det skal ikke være tvil"* | smooth, shaded, vector-looking art |
| *"Lag heller som icon som tektopia"* | anything that is not an item |
| *"Bildene er for stygge tenk på en helt ny og forbedret løsning"* | **hand-drawn art, entirely** |

## What it is now

**The plaque renders the real Minecraft item.** No textures, no generator, no
sprites. `BuildingType` names an `Item`, and `PlaqueRenderer` draws it with
`ItemRenderer.renderStatic`.

| building | item |
|---|---|
| House | red bed |
| Lodging | white bed |
| Warehouse | chest |
| Lumber camp | iron axe |
| Farmhouse | wheat |
| Architect's study | lectern |

This is TekTopia's convention taken literally — there you declare a building by
hanging **an item in a frame** beside its door. It is better than any sprite
this mod could author, for three reasons that are not matters of taste:

1. **Recognition.** A player has been looking at these exact shapes for years.
2. **Consistency.** The emblem is drawn by the people who drew everything else
   on the screen, so it cannot look pasted on.
3. **Cost.** The seventh building type needs no art at all — one enum constant.

### `GUI`, not `FIXED`

`ItemDisplayContext.FIXED` is what an item frame uses and was the obvious first
choice. It is wrong here: it hangs a bed the way a frame does — edge-on, two
blocks long — and squashed into a sheet this shallow the bed collapsed to a
strip of planks. `GUI` is the pose every player has seen ten thousand times:
**the icon in their own hotbar.** A chest looks like the chest in slot one.

Depth is squashed to a tenth, because there is only 0.044 of a block between
the sunken panel and the front of the brass frame, and a chest at natural depth
pushes straight through it and out of the block.

### The picture is not a fixed band any more

It used to occupy 26 rows of the sheet however much text there was.
`PlaqueRenderer` now lays the writing along the foot of the parchment and gives
the picture **everything above it**, square and centred — so a registered
building, whose sheet is two lines, has a big emblem, and one still gathering
requirements has a smaller one above its checklist.

## Adding the seventh building

One line: name an item on the enum constant. The rule for choosing it is
TekTopia's own — **what would you hang in a frame by that door?** A smithy gets
an anvil, an infirmary a potion, a graveyard a skull.

`everyPlanHasItsOwnEmblem` fails if two types share an item or one names none,
so a new building cannot ship indistinguishable from an old one.

## Acceptance

- Every building type renders a distinct, instantly-known item.
- **A warehouse plaque and a house plaque are told apart before any text is
  legible** — verified in game.
- No emblem pushes through the brass frame.
- No art asset to maintain, and none to get wrong.

---

# Step 3b — the sheet says who is in it

*"Ha en liten working/not working der også så jeg vet om det fungerer. Og en
population slik jeg vet om et hus er fullt. People 1/2 eller lignende"*

**Corrected by the owner minutes later:** *"Rettelse dropp working eller ikke.
Det lyset fungerer veldig fint."* So there is **no working / not-working
line**. The lamp in the board already says it, in a way that reads across the
square where a word would not, and a second copy of the same signal on the
same block is clutter. Only the occupancy line is built.

## The sheet gets two faces, not six lines

Stacking an occupancy line on top of five requirement lines would mean six
lines in a field 4 model pixels tall, and everything would shrink. It also
gets the priorities backwards. So the sheet switches face with the building:

**Not registered yet** — the checklist is the whole point:

```
        House            (large)
      Beds  0/1  ✘
      Doors 1/1  ✔
      Light 1/1  ✔
      Floor 27/9 ✔
```

**Registered** — the checklist has done its job and becomes noise; what you
want from across the room is whether there is still space:

```
        House            (large)
      People 1/2         (green; amber when full)
```

Two lines means big type. And the switch is not cosmetic: if a requirement
later fails — someone takes the bed — the plaque unlinks on its next survey
and the checklist comes back by itself, naming exactly what broke. The lamp
goes amber or red at the same moment. The full list stays available in the
right-click screen (step 5).

Work buildings read `Workers 0/2` from `BuildingType.workerCapacity`;
dwellings read `People n/m` where m is the beds actually found, capped by
`residentCapacity`.

## Where the numbers come from

Occupancy is settlement truth, and the plaque must not become a second copy of
it (D-006). It follows exactly the rule `lastSurvey` already follows:

- recomputed inside `survey()` from the building and the settlement,
- written into `getUpdateTag` so the renderer can draw it,
- **never** written by `saveAdditional`.

So it cannot persist, cannot drift, and cannot outlive the settlement's own
answer. A plaque that saved an occupant count would be wrong the first time a
settler died in an unloaded chunk.

## Acceptance

- A registered house shows `People n/m`, and the number changes when a settler
  claims or loses a bed there.
- A full house is visibly full without opening anything.
- Breaking the bed brings back the checklist with the beds line unmet.
- The occupancy never appears in the plaque's saved NBT.
- No working/not-working text anywhere on the block — that is the lamp's job.
