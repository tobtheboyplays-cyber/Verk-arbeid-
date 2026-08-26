# Does it all hang together? — the interconnection audit

*Asked by the owner, 2026-08-26: "Sjekk om alt henger sammen og at hele
modden blir en spider web."*

The test applied here is not "does this system exist" but **"does it both
feed something and get fed"**. A system that only produces, or only
consumes, is a thread that dangles. A feature nothing reads is not a
feature; it is a file.

Every claim below was measured against the source, not remembered — the
method was to take each system's public surface and count who references
it in production code (tests excluded, since a test reading something
proves nothing about the game reading it).

---

## The web that is real

These are load-bearing and genuinely interwoven:

**The spine — plaque to goods.** Plaque → `RoomScanner` → `Building` →
`Employment.TRADES` → `Profession` → the profession's `WorkGoal` →
`Production` → real items in real chests. Every link is referenced by the
next. This is the core loop and it is whole.

**The warehouse is the hub, as designed.** `WAREHOUSE` has more distinct
consumers than any other building type (7): `Employment`,
`StorageNetwork`, `CourierWorkGoal`, `RaidObjective` (raiders target real
stores), and the debug command. That concentration is what a hub is
supposed to look like.

**Housing feeds population.** `HOUSE` and `LODGING` are the only types
with `residentCapacity > 0`. That flows: `housesResidents()` →
`validBedCount()` → `Settlement.capacity()` → recruitment gating in
`SettlementManager` (three separate call sites). Beds you build are
settlers you can hold.

**Research changes production, not a number on a screen.** All six
`ResearchKey`s have real consumers: BAKERY/SAWMILL/SMELTER/TANNERY_TICKS
are read by `Production` and `CrafterWorkGoal` (craft speed), FARM_GROWTH
by `FarmerWorkGoal` (crop growth), GUARD_TRAINING by the guard path. No
key is decorative.

**Morale has consequences.** Morale gates recruitment attractiveness
(`>= 60`), scales the growth rate (`>= 80` gains 2 instead of 1), drives
settlers to quit a post, and takes a settlement-wide hit with three days
of mourning on a death. Traits feed back into it through
`Trait.moraleDecay`/`moraleGain`.

**Raids reach the saga.** `Captain` is read by 7 files outside its own
package — raids produce named enemies who persist and accumulate.

---

## The threads that dangle

### 1. The weight system is orphaned — nothing calls it

**This is the most serious finding, and it is the one the owner asked for
personally** ("putte ting som er tungt nærme warehouse"). The whole
`logistics/` package is a single file, `Weight.java`, and it has **zero
consumers in the entire mod.** Not one call site. Not even a test.

The consequence is that the feature does not exist in play: a courier's
load is bounded by item COUNT alone, so eight iron ingots and eight
feathers cost exactly the same walk. With every trip priced identically,
no arrangement of buildings can beat any other, and "put the mason near
the warehouse" is flavour text rather than advice. The table is written,
documented and correct — and inert.

*Status: a patch wiring it into `CourierWorkGoal`'s two load loops is
written and held back deliberately, because it changes throughput for
every heavy chain in the economy and must not go in ahead of a certified
green baseline. It goes in after the gate, or not at all today.*

### 2. The Well does nothing whatsoever

`WELL` declares 0 residents and 0 workers and is referenced by no
production code at all. It can be planned, built to spec, and validated
green — and there is no code path anywhere that reacts to its existence.
It is the only building type that is inert in *both* directions.

### 3. Three buildings advertise jobs that cannot be filled

`SCHOOL` (1 worker), `MARKET` (2) and `INFIRMARY` (1) all declare worker
capacity, so `employsWorkers()` is true and the plaque offers hiring. But
none of the three appears in `Employment.TRADES`, so `tradeOf` returns
`NONE` and every hire is refused with `no_trade`.

To the mod's credit this is **honest** — the refusal has a real reason and
the game does not pretend someone got hired. But the thread still dangles:
the building offers a slot that connects to no profession.

### 4. Research materials never reach the study

Already known and documented in `KNOWN_ISSUES.md`. Worth restating here
because it is precisely a web break: research consumes items, logistics
delivers items, and the two are not connected. Every project's materials
are a manual errand forever.

---

## Not a break: not built yet

**Blessings do not exist** — zero references anywhere. This is Phase A3 in
the roadmap, not a disconnected system. Worth separating from the findings
above: "planned and absent" and "present and unwired" are different
problems, and only the second is a defect.

---

## The shape of it

The core loop — build a room, hang a plaque, hire a settler, watch goods
move — is genuinely a web: dense, mutually referencing, and it holds. The
dangling threads are concentrated at the edges, in buildings added for
completeness ahead of the systems that would give them meaning, and in one
flagship feature that was written and never plugged in.
