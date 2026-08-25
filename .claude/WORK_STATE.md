# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

- **HARNESS-1, PLAQUE-1, VISUAL-1, FIX-1, ANIM-1 — DONE.** KF-007/009/010/011/012.
- **A2a / A2b — DONE.** Warehouse, courier, the visible sack. **KF-013**
  (courier loaded, never delivered) was found by *playing* after `gate` said
  green — deliver to a real container, arrival needs the bounds, failed routes
  rest, RETURNING carries an undeliverable load home.
- **A3 steps 1-3 — DONE.** `RaidPressure`: a real roll every night, 5%→55%,
  **repelling raises it** (inverse of MineColonies), never two quiet nights
  below Beleiring. Captains carry a byname, record, grudge, and an approach
  ≥60° off their last. **KF-014**: fixtures registering warehouses with no
  plaque block.

## SLICE PLAQUE-2 — the readable plaque. Steps 1-4 DONE, gated, pushed.

The plaque shows, on the block with no click: its plan's **emblem**, its title,
then the live requirement checklist or — once registered — **`People n/m`**.

- **The emblem is the real Minecraft item** (`ItemRenderer.renderStatic`,
  `ItemDisplayContext.GUI`; `FIXED` collapses a bed to planks). Eight drawn
  styles were rejected first. A new building type needs no art at all.
- **Occupancy is wire-only**: recomputed in `survey()`, in `getUpdateTag`,
  **never** `saveAdditional`. `BuildingManager.livesOrWorksIn` is the one shared
  predicate. No working/not-working line — the lamp already says it.
- **33 building types** with emblems, requirements and en+nb names.

**Next: step 5** — the right-click screen rebuilt on the UI kit, which is also
the JOBS-1 hire/fire screen. The protocol already exists (`PlaqueSnapshot` /
`PlaqueAction` with revision-checked ASSIGN/EVICT).

## CHAINS-1 — the production seam. `Production.java` IN, gated, pushed.

The shape every work building shares — take from your own chests, spend time,
put back — written **once**; a building type is a table entry, not a class.
Seeded: bakery, butcher, smelter. `ready()` is a pure read so a work goal can
ask every tick; `run()` checks room **before** removing anything; nothing is
ever destroyed (overflow is `popResource`d). `productionNeverDestroysAnything`
is **mutation-proven**. Design: `docs/project/PLAN_PRODUCTION_CHAINS.md`.

**Next: step 5** — the right-click screen rebuilt to match the mockup (full
requirement list, READY TO BUILD). Then professions on top of this seam, one
chain at a time (food first), each standalone-useful. The six intermediate
items after that — decision stands (D-008), art to be redone.

## SLICE UI-1 — the UI toolkit. Built, gated, pushed. **It is the standard.**

Vanilla's GUI sprite atlas replaces both dead ends (whole-PNG blits and stacked
`fill` rectangles): one 18x18 nine-slice image is a panel at any size.

- **`tools/gen_ui.py`** — 26 deterministic sprites + `.mcmeta`. **Load-bearing
  trick:** nine-slice edges TILE, so every frame pixel is painted as a function
  of **distance to the nearest edge** — edges are then constant along their own
  axis, corners mitre free, directional light survives.
- **`tools/mcfont.py`** — the real vanilla font from the client jar; self-tests
  against known widths and æøå. Nothing from the jar is committed.
- **`tools/ui_preview.py`** — layout spec → PNG in a second, vanilla's own
  scaling rules, and **it fails on overflow** (`--strict`).
- **`HsUi` / `HsButton` / `HsUiTokens`** — tokens GENERATED from the same
  `tools/ui/tokens.json` the preview reads; `validate_assets` fails on drift.
- **`.claude/skills/minecraft-ui`** — metrics, the 1.21.1 API, ten traps.
- **D-014 no dead controls** — enforced by `check_dead_controls`,
  mutation-proven.

**Not yet proven in game**: no screen uses the kit. That is PLAQUE-2 step 5.

## SLICE JOBS-1 — employment, the village day, roads. IN, gate pending.

**Employment is a relationship to a BUILDING (D-011).** `Building.workers` is
the only record; the settler's `Profession` is a *projection* recomputed from
it, like plaque occupancy. Hiring away vacates the old post in the same
operation; dissolving a building frees its workers; a building whose trade is
not implemented refuses honestly. Design: `docs/project/PLAN_EMPLOYMENT.md`
(the six things MineColonies' hire tab gets wrong + what we do instead).
**The writ stays until the hire screen exists** — removing it first would leave
no way to give anyone a job (D-012 sequencing).

**One village clock (`DayPhase`, 6 phases).** Rise → morning work → the meal
→ afternoon work → evening → rest. The midday break is real (work goals stop),
and `EatFromHearthGoal` brings the merely peckish to the table so a dining hall
is worth building. `Schedule.postFor` sends everyone somewhere real;
`GoToPostGoal` walks them. **A load in your hands outranks the clock.**

**Guards split the clock** — `watchOf` halves a barracks by worker index
(derived, never stored, survives reload). The night watch rests in the
*afternoon*, so 2am raids meet waking guards.

**Five attributes + ten traits.** Newcomers cap at **15/100** (median 4, 3% at
the cap); growth is asymptotic so **nobody reaches 100**; each settler has one
`knack`. Every trait has a real trade-off, enforced by
`everyTraitCostsSomething`. `Employment.fitness` now reads the real attribute a
trade leans on. Design + probabilities: `docs/project/PLAN_ATTRIBUTES.md`.
**Nothing calls `train()` yet** — that is the next slice's first job.

**Roads.** Settlers prefer dirt path (`OFF_ROAD_MALUS=1.5`, a penalty for
everything else); combat and flight take the straight line. **Grass wears into
path underfoot** — the only part of the settlement that draws itself. Grass
only, outdoors only (explicit roof scan, not `canSeeSky`), capped table, per
block entered. `docs/project/PLAN_DAILY_RHYTHM.md`.

**89/89 GameTests** (+18). Mutation-proven: zeroing the road malus and removing
the vacate step both failed by name. Ledger iteration 8 records the clock
specification correction.

## Design decisions taken this session

Full text in `docs/project/DECISIONS.md`; the one-line versions:

- **D-007 — every building is useful ALONE**; a chain is a multiplier, never a
  gate. Every future profession must have work the moment its room registers.
- **D-008 — six real intermediate items** (flour, cured meat, meals, ale,
  cloth, hafts). Decision stands; **the art was drawn and thrown away**.
- **D-009 — crafters own a MATERIAL DOMAIN**, recipes are never taught.
- **D-010 — levels gate professions WITHIN a building, never between them**: a
  building never waits on another BUILDING; it may wait on ITSELF.
- Chains and outputs: `docs/project/PLAN_WORK_AND_CHAINS.md`;
  standalone-function table: `docs/project/PLAN_PRODUCTION_CHAINS.md`; the
  fifteen: `docs/project/PLAN_DIFFERENTIATION.md`.

**KF-015 is OPEN** — a raid can resolve as repelled while raiders live in
unloaded chunks. Design question, recorded not patched.

## Standing infrastructure (use it)

- **`tools/hearthstead-qa quick`** after every change (~15 s); `fast` before
  moving on; `full` ×2 + `gate` before any completion claim.
  `qa/QUICKSTART.md` is the whole workflow on one page.
- **Fast-quality mode** (CLAUDE.md + premium-build-loop): parallel Sonnet
  workers under strict file ownership, Opus only at gates.
- **`sonnet-driver` skill** — session-start recovery + escalation table.
- **Blockbench bridge** (`tools/blockbench/README.md`) — mandated for art work.
- **`animation-quality` skill** — weight/impact principles + CHOP template.

## Load-bearing findings from live debugging (do not re-derive)

- `ss -ltnp` unreliable here — use `lsof -ti tcp:<port> -sTCP:LISTEN`.
- GNU `timeout` needs `--foreground`; Xvfb ignores SIGHUP (`pkill -9`).
- GLFW needs a real click before relative look works; quickPlay never gives one.
- Console `~`-relative commands resolve against the console — use `execute at`.
- `execute at ... run <anything>` suppresses ALL feedback silently.
- A screen left open absorbs later clicks — `key Escape` between iterations.
- The FIRST `live click right` after a `key` often misses; re-issue it.
- Never run two suites at once (KF-002/KF-003); never edit/compile source
  while `full` runs (false "stale jar").
- Client boot under software GL takes minutes while genuinely progressing.
- `sendSuccess(msg, false)` leaves NO server-log trace for player commands.
- Silence in a log is not proof a path ran.
- `data merge` sets persisted fields on a live entity but not synced-only ones,
  and merging `State` on a plaque never calls `survey()`.
- UI findings now live in the **`minecraft-ui` skill** (thin geometry, ✔ vs ✓,
  GUI vs FIXED item poses, nine-slice tiling) — read it before touching a screen.

## Known problems (pre-existing, other slices' scope)

- `safe_regrab()`'s Y=300 round trip causes drift warnings (architecture note).
- Village-wide dawn wake window (settlement scheduler) — deferred.
