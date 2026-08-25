# Architectural decisions

Newest first. Each entry: the decision, why, what was rejected, what it
affects, and any migration concern.

---

## D-018 — Showcase pose/pulse/lineup are a viewing aid, never a test oracle

**Decision.** `/hearthstead pose`, `pulse` and `lineup`
(`HearthsteadCommand`, this session) let an operator put any settler into
any of the 33 catalogued poses on demand, for filming and eyeballing. **No
GameTest may call `pose` and then assert on the result.**

**Reason.** `applyPose` calls `settler.setNoAi(true)` and writes the
activity/profession projection directly onto the entity, skipping goal
selection entirely. A posed settler is wearing the job's costume, not doing
the job. Asserting after a pose would prove the poser works, not that any
settler's own AI ever reaches that state — the one thing a GameTest exists
to check.

**Rejected.** Reusing showcase as a cheap animation regression check (pose
every clip, screenshot, diff) — tempting because the wiring already exists,
but it would silently certify clips no AI path ever plays.

**Affects.** `HearthsteadCommand`, `qa/scripts/showcase.sh`, and every
future animation GameTest — assert on the activity a settler's own goal
selected, never on a posed one.

---

## D-017 — The D-016 motion invariant now holds for every trade; certification stays a separate, still-open claim

**Decision.** `Employment.motionOf` maps every trade that runs through
`Production` to a distinct `SettlerActivity` — the switch has twelve arms
and twelve different clips, none shared. D-016's "twelve trades still on a
base motion" note is closed: cook → `WORK_STIR`, carpenter → `WORK_PLANE`,
mason → `WORK_CHISEL`, fletcher → `WORK_FLETCH`, tanner → `WORK_SCRAPE`
(`docs/ANIMATION_CATALOGUE.md` §20), on top of the seven landed earlier in
the same slice.

**Reason.** Motion distinctness is not the same claim as "the job is done."
`tools/job_audit.py`'s CERTIFIED set gained only **smith** this round (its
seventh member); cook, carpenter, mason, fletcher and tanner stay off it —
each still borrows another trade's work sound, and job standard point 6 ("a
distinct sound") is unmet until that debt is paid. Recording both halves
here stops a future pass from either re-deriving which trades still owe
work, or over-claiming "D-016 done" as "five more jobs certified."

**Affects.** `SettlerActivity` (the five new activities are **appended**,
per the enum's own "ordinals... must never shift" rule —
`SettlerEntity.DATA_ACTIVITY` is ordinal-keyed wire format),
`Employment.motionOf` and `.soundOf`, `tools/job_audit.py`'s `MOTION_CLIP`
table, `JOB_STANDARD.md`'s trade table (five rows now read "not yet" with a
named **sound** debt, not a motion debt).

---

## D-007b — A courier's load is visible, and carry capacity is a real mechanic

*(Renumbered from a duplicate D-007 2026-08-25; the chain decision below
keeps the original number because other documents cite it as D-007.)*

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

---

## D-009 — Crafters own a material domain; recipes are never taught

**Decision.** Each work building owns a **material domain**, and its worker can
make anything in that domain the settlement has inputs for. The Sawmill owns
"anything made only of planks, logs or sticks". The Stonemason owns the stone
family. The Smithy owns metal tools; the Armoury owns worn metal and leather.
There is no teach-a-recipe step.

**Reason.** Owner's choice, 2026-08-25, taking MineColonies' best idea — you
never wonder who makes a thing, you look at what it is made of — without the
cost that comes with it. It also keeps D-007 true: a building is useful the
hour it opens rather than after the player has taught it something.

**Rejected.** MineColonies' teaching system, which lets one blacksmith be a
tool shop and another an armourer. Real control, but it needs a teaching screen
and leaves a new building inert.

**Affects.** Every work building's behaviour, the request system, and the
Tingbok's "who can make this?" answer.

---

## D-010 — Levels gate professions WITHIN a building, never between buildings

**Decision.** Buildings have levels, and a level admits additional professions
to that room — a level-3 smithy can seat an armourer beside the smith. **Every
building at level 1 has its primary worker and does its primary job.** No
building ever waits on a different building's level.

**Reason.** Owner's choice, 2026-08-25, of the full MineColonies progression
shape. The gate was flagged as being what D-007 was written against, and the
choice was made with that flagged.

**How it lives with D-007.** On one line: **a building never waits on another
BUILDING; it may wait on ITSELF.** D-007 is untouched for cross-building
dependencies — a bakery bakes with no mill in the world. A level is a decision
about where to invest inside one room.

**Reversal condition, written down now so it is not argued later.** If levels
read as a wall rather than a choice in play — a player stuck rather than
choosing — **D-007 wins and levels stop gating**, keeping only capacity and
speed. That is the test, and it is a play test, not an argument.

**Affects.** `Building.level`, the plaque's sheet and screen, profession
assignment, and the upgrade path the architect sells.

---

## D-011 — Employment is a relationship between a settler and a BUILDING

**Decision.** A settler stores **which building employs them**, and nothing
else about their job. Their profession is *derived* from that building's type.
There is no second place where a job is written down.

**Reason.** Owner's choice, 2026-08-25: MineColonies' hire/fire in place of
TekTopia's emblem. The shape is the substance — hiring a person *into a room*
rather than stamping a trade *onto a person* is what makes the roster
affordable (28 buildings need no writ items, recipes or sprites), what keeps
the plaque honest (it is an access point; the settlement holds the truth), and
what closes the player's flow, which currently dead-ends the moment a building
registers.

**Rejected.** Keeping `Profession` as stored state alongside an employer field.
Two sources of truth for one fact is the exact mistake the plaque invariant
exists to prevent, and it would drift the first time a building changed type.

**Affects.** `SettlerEntity` persistence, `Profession`, the plaque screen and
sheet, every work goal, and the settler's outfit and tool.

---

## D-012 — The writ of trade is retired

**Decision.** `ProfessionWritItem` and its per-profession items stop being how
a job is given. Employment is commanded at the plaque.

**Reason.** The owner asked for hire/fire **instead of** ("heller") the emblem
model. Leaving the writ in as a second path would mean two ways to change one
fact, which D-011 exists to forbid, and the writ cannot express the thing that
makes hire/fire good — that taking a worker from one building costs another
building its trade.

**Cost, stated plainly.** This deletes items, recipes, sprites and lang keys.
The mod is private and unreleased, so no save in the world depends on them.

**Sequencing, which is not optional.** The writ is removed **after** the hire
screen works, never before. Deleting it first would leave a build in which no
settler can be given a job at all — the replacement has to be standing before
the old thing comes down. Until then the writ remains as the working path and
`Employment` is the one used by everything new.

**Affects.** `item/`, recipes, the creative tab, en_us + nb_no, and every
GameTest that calls `assignProfession` directly.

---

## D-013 — Suggestion, never automation

**Decision.** No system may change a settler's employer. Only a player command
can. The plaque *suggests* a candidate, with a written reason, and the player
presses the button.

**Reason.** Automatic hiring is the MineColonies feature whose own community
advice is to switch it off, because unpredictable reassignment is worse than no
help at all. It is also the same promise the printed raid odds make: the
settlement tells you the truth and then you decide.

**Reversal condition.** If staffing thirty settlers by hand reads as a chore
rather than a decision, auto-staffing arrives as a **visible standing order**
with a name and an off switch — never as a hidden default.

**Affects.** The hire service, the plaque screen, and anything later tempted to
"helpfully" fill an empty post.

---

## D-014 — No dead controls

**Decision.** Every button, tab and clickable thing in a Hearthstead screen
performs a real action. A control that cannot act right now is **visibly
disabled and says why**. There are no placeholder buttons, no decorative
controls and no "coming soon".

**Reason.** Owner's standing standard, 2026-08-25: *"Viktig at alle knappene
gjør noe og har en mening."* A button that does nothing is worse than a missing
one — the player presses it, nothing happens, and from then on they do not
trust the screen. Trust in a UI is spent, not earned back.

**How it is enforced.** `validate_assets.py`'s `check_dead_controls` fails the
build on any press handler in `client/` with an empty body. That is the
mechanical half — "wired to nothing" is decidable. Whether an action is
*meaningful* is a review judgement and belongs to the release gate. The check
was mutation-proven when it was written: a `Button.builder(..., b -> {})` added
to `PlaqueScreen` failed it by name and line.

**Corollary.** Disabled is a first-class state, not an afterthought — which is
why the UI kit ships a `button_disabled` sprite and `HsButton` draws it. A
disabled button that looks enabled is the same lie by another route.

**Affects.** Every screen, and the `minecraft-ui` skill.

---

## D-016 — Every trade gets its own signature motion

**Decision.** Each job has **one motion nobody else has** — the beat you
recognise it by from across the square. D-015's motion-sharing stands only as a
*base* a trade starts from, never as where it ends up.

**Reason.** Owner, 2026-08-25: *"Vil at alle som jobber har kule animasjoner …
pass på alle har sin distinkte ting. Da virker moden gjennomtenkt."* And the
reasoning is right: a village where two trades move identically reads as a
village where nobody thought about either of them. Distinctness is not
decoration here — it is the thing that makes the settlement look considered.

**What supersedes what.** D-015 argued that a butcher and a tanner make the
same stroke, which is true of the *physics* and beside the point for the
*reading*. The clips stay as the shared base; each trade then gets its
signature on top, and the trade is not finished (job standard, point 4) until
it has one.

**The first three, named by the owner.**
- **Lumberjack** — `GATHER_LOG`: felling was half the job; the stoop for the
  log is the other half, and it rises slower than it drops.
- **Baker** — `OVEN_TEND`: the peel goes in, holds, and the wrists snap over.
  Nothing else in the mod rolls the arms on Z.
- **Farmer** — `SOW_BROADCAST`: seed cast by hand in a wide arc driven from
  the hips, with the release as the beat.

**How it is enforced.** `tools/job_audit.py` point 4, and `anim_preview.py`
holds every new clip to the craft standard — an accelerating wind-up, a torso
that leads, a real beat, a recovery that overshoots.

**Affects.** Every trade still on a shared base motion: cook, butcher, smelter,
smith, sawyer, carpenter, mason, fletcher, weaver, tanner, courier, guard.
