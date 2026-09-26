# Write ownership (current) — paths under hearthstead-neoforge/src/main unless noted

Claude's sub-agents own these RIGHT NOW. Codex: do not write here without asking in `to-claude.md`.

| Area | Owner |
|---|---|
| `client/motion/**`, `client/model/SettlerModel.java`, `SettlerAnimations.java`, `RaiderModel.java`, `RaiderAnimations.java`, `GoblinThiefModel.java`, `client/render/SettlerRenderer.java` | Claude — animation engine |
| `resources/assets/hearthstead/animations/**`, `hearthstead-neoforge/tools/blender/**` | Claude — 9 Blender animators |
| `client/ui2/**`, `client/screen/HearthScreen.java`, realm-map network payloads | Claude — UI/map |
| `resources/assets/hearthstead/sounds.json`, `resources/assets/hearthstead/sounds/**` | Claude — sound |
| Navigation / pathfinding / door handling / `ContainerApproach` | Claude — pathing |
| `entity/ai/*WorkGoal.java` (except where TASKS-CODEX grants), worker watchdog | Claude — soak/reliability |
| `HunterWorkGoal`, carcass item, butcher station, hunting-grounds spawner | Claude — hunter |
| `settlement/raid/**`, `RaiderEntity` | Claude — raid cadence/spawn |
| `GuardMeleeGoal`, combat moveset | Claude — combat |
| `block/HearthBlock*`, `neoforge.mods.toml`, rename sweep of `en_us.json` | Claude — rename/Banner |
| `client/screen/DevelopmentScreen.java`, `HandbookScreen.java` | Claude (ask first) |
| `settlement/request/CraftingOrderBook.java` (membership fix) | Codex (granted 00:40) |

| `finisher/**`, `client/finisher/**`, `mixin/client/HumanoidModelFinisherMixin.java`, `hearthstead.mixins.json`, `animations/player/finisher_*`, `animations/raider/finisher_victim_*` | Claude — finisher |
| Guard command (R/G "To rop"), command input handler | Claude — command |
| Downed/revive for players | Claude — revive |
| Sack tiers, carts, road speed, logistics bonuses | Claude — logistics |
| Gear progression tiers + custom settler armour visuals | Claude — gear |
| Tech tree design data `plan/techtree/*` | Claude — design |

Shared (targeted edits only, re-read first): `en_us.json`, `HearthsteadServerConfig.java`,
`SettlerEntity.java`, `SettlerActivity.java`, `build.gradle`.

**Codex owns** whatever `TASKS-CODEX.md` lists under "Allowed files" for its active task, plus any
NEW files it creates in `src/test/**` and `COORD/to-claude.md`.

| entity/ai/SettlerPanicGoal.java | bug hunter (a8450891fcfc4a7a2) | assigned 26 Sep 11:20 for the raid-panic pathfinding hitch |
| `settlement/YardScanner.java`, the work-yard hook in `PlaqueBlockEntity.surveyRoom`, `BuildingType.validationMode()`; blueprint content `data/hearthstead/blueprints/**`, `structure/blueprints/**`, `blueprint_catalog.txt`, `tools/blueprints/**`, TownStyle re-skin | Claude — blueprint artist (26 Sep) |
| `client/look/**`, `entity/look/**`, `gametest/CharacterLookGameTests.java`, `textures/entity/look/**`, `Hearthstead-Claude/tools/skins/**` (+ anchored hooks in SettlerTextureCache, RaiderRenderer, SettlerEntity costume, WorldEventActors, `[features] characterSkins`) | Claude — character skins lane (26 Sep 12:50) |
