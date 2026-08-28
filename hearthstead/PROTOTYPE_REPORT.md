# Hearthstead — Prototype Report

An honest account of what this vertical slice contains, what was actually
executed in verification, what its limits are, and where development should
go next. Built against Minecraft 1.20.1 / Forge 47.4.23 / Java 17.

## Fully implemented

**Core loop** — craft/receive the Settlement Hearth → place it → settlement
founds itself with a generated name and a 48-block radius → three settlers
gather → communal food/resource storage in the hearth → professions assigned
with reusable writ items → autonomous visible work → live management screen →
full day/night rhythm (work, eat from stores, rest at the fire, flee danger)
→ recruitment of a traveler when food ≥ 8 and morale ≥ Content.

**Professions** — Farmer (budgeted crop scanning, walks to mature crops,
visible hoe-work animation, harvests, withholds a seed, replants, hauls the
yield to the hearth in a bag); Lumberer (natural-tree heuristic that refuses
player builds, fells top-down so no floating trunks, replants a matching
sapling, delivers logs); Guard (waypoint patrol around the hearth, targets
hostiles inside the radius, melee combat with a dedicated attack animation,
responds to alarms). Zombies actively hunt settlers, so guards genuinely
protect.

**Simulation** — per-settler persistent name, profession, settlement binding,
hunger, energy, morale, and activity; needs decay with work, meals restore
hunger, night rest restores energy, morale drifts toward a computed target
(food, rest, employment, safety) with four semantic bands that feed the UI
and recruitment. Settler death is real, mourned (morale hit), and broadcast.

**Animation** — original stocky medieval settler model (hood, shoulder cape,
belt, backpack as separately animated parts; per-profession headgear
silhouettes) with a 9-profile keyframe library on the vanilla 1.20.1
animation engine: idle/breathe, walk, eat (two-bite), farm (bent hoe-work
with snap downstroke), chop (strike synced to the sound at 0.55 s), guard
stance/scan, melee slash (one-shot), rest (sunk by the fire), celebrate
(one-shot on assignment). Additive head tracking with damping while
eating/resting; procedural hurt flinch; tools held via an armed-model layer.

**UI** — custom parchment/oak/wrought-iron management screen (population,
employment, food, radius, semantic morale bar, alert banner, recruitment
progress, tooltips on every stat, communal 6×4 stores with correct
shift-click rules); right-click settler card with live hunger/energy/morale
bars; six-page in-game handbook; two-line nameplate showing profession and
current activity; all of it in English **and** Norwegian Bokmål (full key
parity, hand-written Bokmål).

**Audio** — 12 original OGGs synthesized from scratch (founding bell-and-drum,
writ chime, recruitment horn, 3 farm rustles, 3 axe thocks, alarm bell,
2 settler hums) with subtitles for every event.

**Onboarding** — `/hearthstead demo` kit, `/hearthstead info`, crafting
recipes, tooltips, handbook, README five-minute script.

## What was actually executed (vs. inspected)

Executed, with real output:

- `./gradlew build` → reobfuscated `hearthstead-0.1.0.jar` (BUILD SUCCESSFUL).
- **GameTest suite: 9/9 passing** on a headless Minecraft server
  (`./gradlew runGameTestServer`): founding spawns 3 settlers; writ assignment
  equips tools and updates records; farmer harvests, replants and deposits;
  lumberer fells a tree cleanly, replants a sapling, delivers ≥ 4 logs; guard
  engages a zombie; a hungry settler walks to the hearth and eats; a zombie
  attack raises the alarm; settler NBT round-trip; settlement SavedData
  round-trip.
- **Dedicated-server smoke test, three boots** of a real Forge 1.20.1-47.4.23
  server with the built jar in `mods/`: clean load ("Hearthstead is kindling
  the fire", Done in ~4 s, zero `ClassNotFoundException` /
  `NoClassDefFoundError` across all boots — no client classes leak
  server-side). Driven end-to-end from the console: hearth placed → settlement
  "Fenholm" founded itself → `population 3/8, morale 85` → restart →
  settlement and settlers reloaded intact (`hearthstead_settlements.dat`
  present in `world/data/`).
- `tools/validate_assets.py`: 144/144 checks — every registered
  block/item/entity/sound/menu cross-referenced against blockstates, models,
  textures (dimensions + alpha via Pillow), loot, recipes, tags, both lang
  files (key-set parity), sounds.json ↔ OGG files.
- `tools/anim_check.py`: 9 definitions / 48 channels — loop closure,
  amplitude bounds, timestamp ordering, chop-strike sound-sync contract,
  rest-pose sink direction.
- Texture/GUI/turnaround previews rendered and visually reviewed during
  authoring (committed under `tools/`).

Only statically inspected (honest limits of a headless environment):

- The mod was **not run with a graphical client** here. In-motion animation
  feel, GUI rendering at each scale, and texture appearance in-game are
  verified by compile-time checks, the shared UV table, numeric animation QA,
  and orthographic preview composites — not by eyes on a running client.
  A human smoke-look is the first thing to do with the jar.
- Container quick-move rules, capability invalidation, and scan budgets were
  code-reviewed; quick-move is not covered by a GameTest.

## Known limitations

- Starvation lowers morale but does not damage settlers; laziness, not death,
  is the penalty for an empty larder.
- Capacity is fixed at 8 per settlement (no beds/housing yet); radius fixed
  at 48; one settlement per hearth with a simple spacing rule.
- Farmer handles vanilla `CropBlock` crops (wheat, carrots, potatoes,
  beetroot); Lumberer knows the 8 vanilla overworld log→sapling pairs.
- Guards always fight with an iron sword; no equipment progression.
- Recruitment and work require the settlement's chunks to be loaded (no
  offline simulation).
- Voice lines are not included: the account's unlimited allowance did not
  cover TTS and the instruction was to spend no credits. The sound events
  and subtitle hooks are in place — dropping `voice_*.ogg` files into
  `assets/hearthstead/sounds/` plus entries in `sounds.json` is all a future
  voice pass needs. The synthesized settler hums stand in as rare vocal
  acknowledgments.
- In the GameTest harness the custom NBT structure templates reserved bounds
  but their block contents did not apply, so tests build their arenas
  explicitly with `setBlock` (more robust anyway). Worth revisiting the
  template writer before using templates for real content.
- No config file yet (rates and thresholds are constants).

## Strongest next feature

**Housing.** Let players designate simple homes (a bed + a door within the
radius); each claimed home raises capacity, gives its settler a real bed to
sleep in (instead of gathering at the fire), and a home-quality score that
feeds morale. It deepens every existing system at once — recruitment gets a
building incentive, morale gets a lever players understand instantly, the
day/night rhythm gets doors closing at dusk — and it is the natural bridge to
the feature after it (a Builder profession that constructs the houses
itself). That is the moment this stops being a prototype and starts being a
living town.
