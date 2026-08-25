# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

- **HARNESS-1, PLAQUE-1, VISUAL-1, FIX-1 — DONE.** KF-007/009/010 in
  KNOWN_FAILURES; VISUAL-1 passed RELEASE_GATE (all 3 Opus calls spent).
- **ANIM-1 — DONE.** All 23 A1 clips; both Opus calls spent, both REVISE
  rounds landed. Detail in `docs/HEARTHSTEAD_QUALITY_LEDGER.md` and
  KNOWN_FAILURES (KF-011, KF-012). A third Opus call needs a real
  BLOCKER_GATE.
- **A2a / A2b — DONE.** Warehouse + courier + the visible sack.
  **KF-013** (courier loaded and never delivered — the anchor was a plaque in
  a wall) was found by *playing* after `gate` said green: deliver to a real
  container, arrival requires being inside the bounds, failed routes rest,
  RETURNING carries an undeliverable load home. Also fixed a
  `Long.MIN_VALUE` overflow that made every warehouse read as empty.
- **A3 steps 1-3 — DONE.** `RaidPressure` replaces the timer both references
  use: a real roll every night, 5%→55%, **repelling raises it** (the inverse
  of MineColonies), never two quiet nights below Beleiring. Captains carry a
  byname, a record, a grudge and an approach ≥60° off their last. Raiders,
  the Korn objective and destination-first theft are built. **KF-014** (four
  occurrences) was fixtures registering warehouses at anchors with no plaque
  block — `BuildingManager` was correctly dissolving them mid-test.

## SLICE PLAQUE-2 — the readable plaque. Steps 1-4 DONE.

**GATE: PASS (green_streak=2)**, 68/68. Pushed on PR #1.

The plaque now shows, on the block, with no click: its plan's **emblem**, its
title, and then either the live requirement checklist or — once registered —
**`People n/m`**, amber when full. A chest on one wall and a bed on the other
are told apart at four blocks, before any text is legible.

**The emblem took eight versions and the answer was to delete the art.** Sepia
elevations, bolder, tonal, silhouettes with badges, coloured boxes,
three-quarter view with shading, 16x16 sprites — all rejected. It renders the
**real Minecraft item** (`BuildingType.emblem()` + `ItemRenderer.renderStatic`)
in `ItemDisplayContext.GUI`, the pose from the player's own hotbar. `FIXED`
(what item frames use) collapses a bed to a strip of planks in a sheet this
shallow. No textures, no generator; a new building type needs no art at all.

**Layout.** The writing sits at the foot of the sheet and the picture takes
everything above it, so a two-line registered sheet gets a big emblem. Ruled
lines — one strong under the title, fainter between rows — via
`RenderType.textBackground()` (no texture, vertex colour, lit).

**Occupancy is wire-only**: recomputed in `survey()`, written into
`getUpdateTag`, **never** into `saveAdditional`, pinned by
`occupancyNeverReachesTheDisk`. `BuildingManager.livesOrWorksIn` is the one
predicate the screen and the sheet share. No working/not-working line — the
owner asked, then withdrew it: the lamp already says it.

**THE FULL ROSTER IS IN** — **33** building types, each with its emblem,
requirements built from the vanilla block that station actually is, and its
name in en + nb. No profession works in the new ones yet; that is CHAINS-1.

**Next: step 5** — the right-click screen rebuilt to match the mockup (full
requirement list, READY TO BUILD). Then the six intermediate items, then
professions, one chain at a time, each standalone-useful first.

## Design decisions taken this session

- **D-007 — every building is useful ALONE; a chain is a multiplier, never a
  gate.** A bakery bakes from any grain on day one with no mill in the world;
  a smithy forges from metal alone and repairs with no inputs. Every future
  profession must have work the moment its room registers.
- **D-008 — six real intermediate items** (flour, cured meat, meals, ale,
  cloth, tool hafts), vanilla items everywhere vanilla has one. **The art for
  them was drawn and thrown away** — the owner judged it bad and said to come
  back to it. The decision stands; the sprites do not exist.
- **D-009 — crafters own a MATERIAL DOMAIN, recipes are never taught.**
  MineColonies' best idea (you never wonder who makes a thing, you look at what
  it is made of) without the teaching screen that would leave a new building
  inert.
- **D-010 — levels gate professions WITHIN a building, never between them.**
  The owner chose the full MineColonies progression shape knowing it bumps
  against D-007. Resolved on one line: **a building never waits on another
  BUILDING; it may wait on ITSELF.** Every level-1 building does its primary
  job. The reversal condition is written into D-010 and it is a play test.
- Chains, per-building work and outputs:
  `docs/project/PLAN_WORK_AND_CHAINS.md` (MineColonies-derived, owner-picked
  chains: food, tools, weapons+armour, drink+recruiting, stone+repair,
  knowledge+care; cloth/warmth deferred until seasons exist).
  `docs/project/PLAN_PRODUCTION_CHAINS.md` holds the standalone-function table.

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
- `data merge` CAN set persisted fields on a live entity; synced-only it cannot
  — and merging `State` on a plaque never calls `survey()`, which is how I once
  reported "the plaque shows nothing" when it worked.
- **Thin geometry is measured in screen pixels, not intent.** Ruled lines at
  0.9 font units lifted 0.0008 blocks were invisible in game and perfectly
  present in the code.

## Known problems (pre-existing, other slices' scope)

- `safe_regrab()`'s Y=300 round trip causes drift warnings (architecture note).
- Village-wide dawn wake window (settlement scheduler) — deferred.
