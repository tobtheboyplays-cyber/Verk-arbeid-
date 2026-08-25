# WORK STATE

Compact working file. Max ~120 lines. Not a diary — compress, don't append.

## Closed slices (full history in docs/, not here)

- **HARNESS-1, PLAQUE-1, VISUAL-1, FIX-1 — DONE.** KF-007/009/010 in
  KNOWN_FAILURES; VISUAL-1 passed RELEASE_GATE (all 3 Opus calls spent).
- **ANIM-1 — DONE.** All 23 A1 clips; both Opus calls spent, both REVISE
  rounds landed (KF-011, KF-012).
- **A2a / A2b — DONE.** Warehouse + courier + the visible sack. **KF-013**
  (courier loaded, never delivered — the anchor was a plaque in a wall) was
  found by *playing* after `gate` said green: deliver to a real container,
  arrival requires being inside the bounds, failed routes rest, RETURNING
  carries an undeliverable load home.
- **A3 steps 1-3 — DONE.** `RaidPressure` replaces the timer both references
  use: a real roll every night, 5%→55%, **repelling raises it** (the inverse
  of MineColonies), never two quiet nights below Beleiring. Captains carry a
  byname, a record, a grudge and an approach ≥60° off their last. **KF-014**
  was fixtures registering warehouses at anchors with no plaque block.

## SLICE PLAQUE-2 — the readable plaque. Steps 1-4 DONE.

**GATE: PASS (green_streak=2)**, 68/68. Pushed on PR #1.

The plaque now shows, on the block with no click: its plan's **emblem**, its
title, then either the live requirement checklist or — once registered —
**`People n/m`**, amber when full. A chest on one wall and a bed on the other
are told apart at four blocks, before any text is legible.

**The emblem took eight versions and the answer was to delete the art.** Eight
drawn styles were rejected; it now renders the **real Minecraft item**
(`BuildingType.emblem()` + `ItemRenderer.renderStatic`) in
`ItemDisplayContext.GUI`, the pose from the player's own hotbar. `FIXED` (what
item frames use) collapses a bed to a strip of planks in a sheet this shallow.
No textures, no generator; a new building type needs no art at all.

**Layout.** The writing sits at the foot of the sheet, the picture takes
everything above it. Ruled lines — strong under the title, fainter between
rows — via `RenderType.textBackground()` (no texture, vertex colour, lit).

**Occupancy is wire-only**: recomputed in `survey()`, written into
`getUpdateTag`, **never** into `saveAdditional`, pinned by
`occupancyNeverReachesTheDisk`. `BuildingManager.livesOrWorksIn` is the one
predicate the screen and the sheet share. No working/not-working line — the
owner asked, then withdrew it: the lamp already says it.

**THE FULL ROSTER IS IN** — **33** building types, each with its emblem,
requirements built from the vanilla block that station actually is, and its
name in en + nb. No profession works in the new ones yet; that is CHAINS-1.

## CHAINS-1 — the production seam. `Production.java` IN, gate pending.

Twenty-eight buildings cannot each own a bespoke crafting implementation and
stay correct, so the shape every work building shares — take from your own
chests, spend time, put back — is written **once**. A building type is one
table entry, not one class. Seeded with bakery (3 wheat → bread), butcher
(four raw → cooked) and smelter (three ores → ingots).

- `ready()` is a **pure read**, so a work goal may ask "anything to do?" every
  tick with no side effects; `run()` checks room **before** removing anything.
- **Nothing is ever destroyed.** A half-finished withdrawal gives back what it
  took; an impossible overflow is `popResource`d, never voided.
- `productionNeverDestroysAnything` is **mutation-proven**: forcing
  `hasRoomFor` true and voiding the overflow made it fail with exactly the
  right message. It is a judge, not a rubber stamp.

**Next: step 5** — the right-click screen rebuilt to match the mockup (full
requirement list, READY TO BUILD). Then professions on top of this seam, one
chain at a time (food first), each standalone-useful. The six intermediate
items after that — decision stands (D-008), art to be redone.

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
- `data merge` CAN set persisted fields on a live entity; synced-only it cannot
  — and merging `State` on a plaque never calls `survey()`, which is how I once
  reported "the plaque shows nothing" when it worked.
- **Thin geometry is measured in screen pixels, not intent.** Ruled lines at
  0.9 font units lifted 0.0008 blocks were invisible in game and perfectly
  present in the code.

## Known problems (pre-existing, other slices' scope)

- `safe_regrab()`'s Y=300 round trip causes drift warnings (architecture note).
- Village-wide dawn wake window (settlement scheduler) — deferred.
