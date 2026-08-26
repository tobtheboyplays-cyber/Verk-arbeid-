# Hearthstead — Quick Start

Found a hearth. Welcome settlers. Watch a living medieval settlement grow —
built by your own hands, worked by theirs. Everything below was checked
against the actual built jar and the mod's own recipe/source files tonight;
nothing here is guessed.

## Requirements

Verified from `hearthstead-neoforge/gradle.properties` and the jar's own
`META-INF/neoforge.mods.toml`:

- **Minecraft 1.21.1**
- **NeoForge 21.1.248**
- **Java 21**

No other mods required — Hearthstead depends only on NeoForge itself.

## Install

1. Install NeoForge **21.1.248** for Minecraft **1.21.1** (run the official
   NeoForge installer, "Install Client").
2. Copy `hearthstead-0.2.0.jar` into your `mods` folder:
   - Windows: `%appdata%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
3. Launch the `neoforge-21.1.248` profile.
4. Start a **new Survival world**. Pick a normal world type, not Superflat —
   a flat world has no trees, stone or ore anywhere, and you cannot even
   punch your first log on one (this cost us a whole playtest tonight).

## The first ten minutes

This is the real survival path — gather and craft it yourself, no creative
mode. Every recipe below is quoted from the mod's own recipe files or
Minecraft's own vanilla recipes, not remembered.

**1. Gather the basics.** Punch a few logs from any tree, make a crafting
table, and get a wood pickaxe going so you can mine cobblestone (any
pickaxe works) and start digging toward iron and copper — you'll need both
soon.

**2. Craft a campfire** (vanilla): 2 sticks + 1 coal or charcoal + 3 logs
of any kind, at a crafting table.

**3. Craft the Hearth**, at a crafting table: **3 logs (any kind) + 5
cobblestone + 1 campfire.**

**4. Place the Hearth** on the ground. About a second later it founds your
settlement on its own — a chime, a burst of happy-villager particles, and
**three settlers appear**, with the settlement given a random name (ours
was "Ashdale" in testing). Keep some food in the Hearth's own storage
(right-click it) — hungry settlers eat from there.

**5. Mine iron and copper**, smelt them, and **craft a Plaque**: 6 iron
ingots + 1 copper ingot + 3 oak planks.

**6. Craft a Build Plan for a Lumber Camp**: 1 paper + 1 feather + 1 log
of any kind (paper is 3 sugar cane → 3 paper, vanilla; feather comes from
a chicken).

**7. Build a small enclosed room** — walls, a roof, no gaps — at least
16 floor blocks, containing:
- 1 crafting table
- 1 chest or barrel
- 1 door
- 1 light source (a torch is enough)

**8. Hang the Plaque** on an interior wall (right-click the wall with the
Plaque in hand, facing into the room). It starts blank and dark — nothing
happens until you fit a plan into it.

**9. Right-click the Plaque with your Lumber Camp Build Plan.** It snaps
into the socket and its screen opens automatically. If the room isn't
complete yet, the Plaque glows **red** (nothing met) or **amber**
(partly met) — the screen lists exactly what's still missing, line by
line, and re-checks itself every 10 seconds (or press Refresh).

**10. Once everything is met, the Plaque glows green.** Right-click it
again (empty hand) and open the **Hire** tab. One of your three founders
can be hired for **free** — press Hire.

**11. That settler walks to the Lumber Camp and becomes your first
lumberjack.** From here on, felled logs flow into the camp's own chest
with no further input from you — that's the loop.

**Bonus, worth doing early:** craft a **Handbook** (1 book + 1 oak
sapling) and right-click it. It opens an in-game guide with chapters on
Founding, the Plaque, Jobs, Logistics, Recruiting, the Mayor, Research,
Threat, the Watch and the Saga — a real second reference once you're past
this document.

*(There's also a `/hearthstead demo` command that instantly hands you a
Hearth, a Build Plan, food, saplings, an iron hoe and 4 settler spawn
eggs — a creative-adjacent shortcut for a fast look at the systems, not
the survival path above.)*

## What NOT to expect

The core loop above — hearth, settlers, plaque, first lumberjack — is
real and solid. Calibrate the rest:

- **Some buildings validate green and then do nothing.** The School, the
  Market, the Well and the Infirmary can be built to spec and the plaque
  will confirm "complete" — but nothing in the game currently reacts to
  their presence (the Infirmary will not heal anyone). Only the Dining
  Hall and the Library, of the buildings with no hireable worker, actually
  do something.
- **Research materials are never delivered by courier**, to the
  Architect's Study or anywhere else — every project's paper and domain
  items are a manual errand, every time, however mature your logistics
  otherwise get.
- **The Smithy still needs a real anvil** (31 iron ingots, vanilla's own
  recipe) before it can be staffed — the most expensive single room in
  the game. The Armoury used to need a second one; that's been reduced
  to a smithing table (2 iron + 4 planks) instead.
- **The Library is the biggest material bill in the mod**: 8 bookshelves
  + 1 lectern, 81 paper + 27 leather total. Both halves can now be
  settler-supplied at scale (a staffed Mill grinds paper, a staffed
  Tannery makes leather), but the first batch is still on you.
- **The Brewery needs a brewing stand**, which needs a blaze rod — a real
  Nether trip. This is a deliberate design choice, not a bug, but nothing
  in the plan or the room tells you that until you're standing there with
  the wrong ingredient.
- See `KNOWN_ISSUES.md` for the specific bugs and rough edges you might
  actually run into.
