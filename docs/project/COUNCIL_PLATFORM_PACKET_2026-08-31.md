# Hearthstead Council — Platform Selection Packet V2

Packet ID: `HS-PLATFORM-20260831-V2`  
Decision phase: `PLATFORM_TARGET`  
Authorization: `IMPLEMENTATION_ALREADY_AUTHORIZED` for continuing the active
Hearthstead demo/UI patch; platform migration is authorized only if selected by
the Orchestrator as the best way to satisfy the explicit version mandate.  
Snapshot time: 2026-08-31 Europe/Oslo

## Tobias mandate

- The Orchestrator is Tobias's executive proxy and directs the four chiefs. It
  may disagree with one or all chiefs and issues the final work order.
- Choose the Minecraft patch with the strongest practical mod support. Do not
  choose a version merely because it is newest.
- Preserve the goal of an extremely modern, intuitive, performant and enjoyable
  Hearthstead demo.

## Current project FACTS

- Hearthstead currently targets Minecraft `1.21.1`, NeoForge `21.1.248`, Java
  21, Gradle 8.14.3 and ModDevGradle 2.0.144.
- The current profile's active mod folder contains Hearthstead only; no actual
  third-party target modpack has yet been frozen.
- The new shared constant-cost UI renderer builds and the controller `quick`
  suite passes. Native A/B frame-time evidence is still pending.
- Existing native baseline for the prior exact JAR:
  - same world: p95 9.37 ms;
  - vanilla Creative inventory: p95 9.26 ms;
  - Settler: p95 50.59 ms;
  - Hearth: p95 48.80 ms;
  - Development: p95 47.22 ms.
- The shared old UI path generated an estimated 14,300–18,864 sprite blits per
  frame on the three measured screens. The new backend caps each resizable
  surface at nine raw-texture slices; runtime improvement is not yet proven.

## Current ecosystem FACTS

Official Modrinth search API queries used `project_type:mod`, an exact loader
category and an exact game version. `total_hits` is a dynamic catalogue count,
not proof that every project is current, compatible with every other project,
or available on CurseForge.

| Minecraft / loader | Modrinth mod projects | Modrinth modpacks |
|---|---:|---:|
| 1.20.1 / Forge | 24,495 | 2,957 |
| 1.21.1 / NeoForge | 20,076 | 1,928 |
| 1.21.4 / NeoForge | 9,159 | 73 |
| 1.21.8 / NeoForge | 8,797 | 71 |
| 1.21.11 / NeoForge | 7,583 | 80 |
| 26.1.2 / NeoForge | 7,001 | 58 |
| 26.2 / NeoForge | 5,519 | 37 |
| 1.20.1 / NeoForge | 4,961 | not decision-leading |

An independent specialist repeated the queries and found the same totals. A
second catalogue check on CurseForge also placed Minecraft 1.21.1 first among
the compared NeoForge-compatible game versions (shown as `10,000+` projects in
that catalogue), while 26.2 showed 4,510. Catalogue filters and indexing differ,
so these figures corroborate direction; they must not be summed with Modrinth.

The specialist also checked a deliberately imperfect quality proxy: Modrinth
projects with at least 100,000 project-wide downloads. Minecraft 1.21.1 /
NeoForge led with 3,182, versus 1,564 on 1.21.4, 1,393 on 1.21.8, 1,398 on
1.21.11, 1,538 on 26.1.2 and 1,238 on 26.2. Downloads aggregate across a
project's versions, so this supports ecosystem maturity but does not prove a
specific version file is popular or compatible.

Reproducible primary queries:

- 1.21.1 / NeoForge mods:
  <https://api.modrinth.com/v2/search?limit=0&facets=%5B%5B%22project_type%3Amod%22%5D%2C%5B%22categories%3Aneoforge%22%5D%2C%5B%22versions%3A1.21.1%22%5D%5D>
- 1.20.1 / Forge mods:
  <https://api.modrinth.com/v2/search?limit=0&facets=%5B%5B%22project_type%3Amod%22%5D%2C%5B%22categories%3Aforge%22%5D%2C%5B%22versions%3A1.20.1%22%5D%5D>
- Modrinth search facet contract:
  <https://docs.modrinth.com/api/operations/searchprojects/>
- NeoForge's own 2024 retrospective says 1.21.1 was its first widely adopted
  line, with more than 4,000 NeoForge mods already available at that historical
  point, while NeoForge had dropped 1.20.1 focus:
  <https://neoforged.net/news/2024-retrospection/>
- NeoForge's earlier retrospective says its limited 1.20.1 effort was better
  served by Forge rather than NeoForge:
  <https://neoforged.net/news/2023-retrospection/>
- CurseForge's current 1.21.1 mod catalogue with version/loader filters:
  <https://www.curseforge.com/minecraft/search?class=mc-mods&gameVersionTypeId=6&page=1&pageSize=20&sortBy=popularity&version=1.21.1>

## Platform migration FACTS

- The newest same-Minecraft NeoForge patch found is `21.1.249`. Its official
  changelog only updates FancyModLoader to 4.0.44; no UI, animation or AI API
  gain is documented.
- A 1.21.1 → 26.2 port requires Java 25, Gradle 9.1+, the new GUI extraction
  pipeline, renderer-state changes, `ResourceLocation` → `Identifier`, JSpecify
  nullability and inventory/capability migration to resource handlers.
- A 1.21.1 NeoForge → 1.20.1 Forge move is a Minecraft backport plus loader
  migration. Raw catalogue size is higher, but Hearthstead would have to trade
  its already-working modern codebase and QA baseline for an older platform.
- Neither platform move automatically fixes the current Hearthstead UI layout,
  Farmer/Courier behavior, animations, sound or raids.

Primary migration sources:

- NeoForge migration primers: <https://docs.neoforged.net/primer/docs/>
- NeoForge 26.1 release: <https://neoforged.net/news/26.1release/>
- NeoForge 21.1.249 changelog:
  <https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-changelog.txt>

## INFERENCES to challenge

- `1.20.1 + Forge` is the raw catalogue leader among the compared combinations,
  but `1.21.1 + NeoForge` is the strongest practical target that preserves
  Hearthstead's current loader, code, QA evidence and modern content while still
  retaining a very large ecosystem.
- Porting before the native UI A/B would contaminate the causal measurement and
  delay the visible overhaul.
- Updating 21.1.248 to 21.1.249 may be a low-risk maintenance step, but it has no
  demonstrated player or compatibility benefit for the active demo candidate.

## Candidates

### P1 — practical ecosystem leader

Keep Minecraft `1.21.1` and NeoForge `21.1.248` for the active demo, finish the
exact-JAR UI/gameplay verification, then separately test `21.1.249` as the new
same-line maintenance baseline.

### P2 — absolute raw catalogue leader

Backport and migrate Hearthstead to Minecraft `1.20.1` + Forge before finishing
the demo.

### P3 — newest platform

Port Hearthstead to Minecraft `26.2` + current stable NeoForge before finishing
the demo.

## Required chief and Orchestrator decision

Each chief must vote `APPROVE` or `REJECT` one named candidate for
`PLATFORM_TARGET`, state its largest discipline-specific risk, and challenge one
other claim in Round 2. The Orchestrator then issues the executive order and may
follow or overrule the votes.

The order must state:

- exact Minecraft and loader line for the active demo;
- whether/when 21.1.249 is adopted;
- whether a later 26.2 port remains planned;
- how a future friend-modpack compatibility matrix changes the choice;
- the next implementation action;
- evidence required before any UI completion claim.

Release remains blocked until fresh native evidence exists for the exact chosen
candidate.
