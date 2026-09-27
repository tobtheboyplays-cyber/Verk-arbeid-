# Owner decisions (25 Sep 2026) — these override older project docs

- **Name**: player-facing name is **Bannerhold** (temporary). Internal modid `hearthstead`, registry
  IDs, NBT keys, packet ids and Java class names stay (worlds/server must keep loading).
- **Hearth → Banner**: the settlement centre block becomes a village banner on a pole with a small
  stand in front (same block ID; old Hearths convert in place). Say "Banner" in new player text.
- **Audience**: Tobias + friends first (co-op, ONE shared kingdom, all players equal rights).
  Public release "when ready", free + donations. Friends update via AutoModpack.
- **Next friend test: Sunday.** Bar: "everything feels smooth and new and works". Server deploy
  ONLY after Tobias' explicit yes.
- **Feel**: balanced cozy + threat; frequent varied events; recurring raids every **3–4 in-game days**
  (first raid ~100–120 min at 2x day length). Day length 2x vanilla.
- **Death** permanent unless the village has a hospital/infirmary (later phase).
- **Settlers heal** slowly when hunger ≥ 90 (done: `SettlerEntity.tickWellFedRegen`).
- **Tax: postponed** — do not build taxes.
- **Scope**: aim to fully replace MineColonies (depth); hybrid building; 100+ settlers target;
  content before performance; mod dependencies OK if clearly better.
- **Animations**: all clips are being remade in Blender (headless pipeline in
  `hearthstead-neoforge/tools/blender/pipeline/`) with bending elbows/knees, easing, weight, and
  lots of per-job personality (variants, break-time details like the lumberer sharpening his axe).
- **Guard combat**: light / heavy / combo / shield bash moveset (in progress).
- **Hunter**: hunts → carries carcass home → butchers at a station → stores; game spawns in hunting
  grounds while a hunter is employed (in progress).
- **UI**: main Banner screen in the owner's reference style (wood frame, parchment, left nav,
  live realm map centre, info column right). No fake widgets (no "Priorities", no Wood/Stone).
- **Questions to Tobias** go through Claude as multiple choice; only big things (new systems,
  economy, server deploy).
- Roadmap: `C:\Users\tobia\Hearthstead-Claude\plan\ROADMAP.md`.

## 2026-09-26 01:45 — claude — Builder exception to "settlers never construct"
Settlers still never build on their own initiative. The one exception is the Builder, which builds only what a player explicitly ordered: a placed plan, a drawn line, or a confirmed Upgrade Order. Barricade rush only re-raises barricade plans a player placed earlier. Nothing is auto-generated or placed spontaneously. (DESIGN.md "settlers never construct" is to be read with this exception.)

## 2026-09-26 02:00 — owner via claude — Sunday freeze
The feature freeze is **Sunday morning**. New features keep landing until then; after that it's bugfix and polish only. If something isn't fully stable in time, it **ships anyway** (the owner prefers showing everything). Mitigation: every big system keeps a config on/off switch, so it can be disabled on site if it breaks.

## 2026-09-26 08:30 — owner — economy
- The merchant (the wandering trader who BUYS goods for Coins) will also buy crafted goods, and his purse grows with the village.
- Hunger drain stays at 0.75.
- Idle crafter: first fetch its own inputs from the Warehouse; if there are none, tidy the workshop, sharpen tools and study (small trade-XP trickle, real and visible activity).
- 08:55 owner: workshop food keeps going via the Warehouse (no direct-to-Hearth route); plan about 1 courier per 10 settlers. The weaver gets a basic recipe from wool. The plaque warns when a stair in a room lacks 3-block headroom.

## 2026-09-26 10:55 — owner — Sunday will probably be a FRESH world
Test everything from a new world: all paths, all scenarios. A fresh server config is generated, so check the shipped defaults and not old tomls.

## 2026-09-26 13:30 — owner via claude — HANDBOOK RULE
The handbook must keep up with the game. **Any lane that adds or changes a player-facing feature updates its handbook data in the SAME change**:
- **New item:** add it to `assets/hearthstead/handbook/items.json` under a family with `page`, `use`, `steps` (1-4) and `obtain` (when it has no recipe). Give every recipe a recipe-book advancement.
- **New job, building, world event, conversation kind, tech node, key binding or [features] switch:** the reference pages (All jobs, All buildings, Events and visitors, Tech Tree branches, Options) are generated from the code by `tools/handbook/facts/gen_facts.py`. Add the one-line player text for your new id to its hand-written table (it prints MISSING for any id without text), then run the command below. For bigger features, write your own chapter file in `assets/hearthstead/handbook/chapters/<id>.json` and add its id to the right group in `chapters.json`; the generator keeps hand-added ids.
- **English text:** new keys in en_us.json, as targeted inserts.

Then run the guards (they read the live registry and data, and fail on anything undocumented), with one command:
`bash C:/Users/tobia/Hearthstead-Claude/tools/handbook/check.sh C:/Users/tobia/Hearthstead-Claude/build-agent-<yourlane>`

Dated allowlists in the guard tests are only for work genuinely in progress, and must be empty at the freeze. If unsure, ping the handbook lane (Claude); schema: COORD/to-codex.md 26 Sep 11:20 + 13:00.
