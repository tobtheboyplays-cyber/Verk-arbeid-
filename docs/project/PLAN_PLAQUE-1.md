# PLAN — SLICE PLAQUE-1 (PLAN_GATE)

**Status:** approved plan, not started. Begins only after HARNESS-1 reaches
OPUS_APPROVED. No code in this document.

## Goal

Make the plaque match the owner's spec (`DECISIONS.md` D-005, D-006): a plaque
is hung blank, and it does nothing at all until a **Build Plan** — a separate
item that carries the building type — is inserted into it. Inserting the plan
is the moment the surveyor starts working. This closes KF-001, KF-004 and
KF-005, and gives the plaque a survival acquisition path it does not currently
have (there is no `plaque` recipe in the repo at all).

## Product spec

- **The plaque is the surveyor** (INV-2). A building exists because a player
  hung a plaque and the room satisfied that plaque's requirements. No plaque,
  no building.
- **No inserted Build Plan means no plaque UI** (D-006). An empty plaque is a
  blank board with a dark lamp; right-clicking it opens nothing and says, once,
  what it is waiting for.
- **The plan is the dedication.** The building type lives on the Build Plan
  item, not on the plaque item. One plaque item, six plans (house, lodging,
  warehouse, lumber_camp, farmhouse, architects_study) — so a player re-purposes
  a wall by swapping a slip of paper, not by re-crafting the board.
- The plaque stays an ACCESS POINT (INV-2): type, state, revision, building id.
  No building registry, no resident list of its own.
- Taking the plan back out returns the physical item (INV-3, conservation) and
  dissolves the building exactly as breaking the plaque does today.

## State machine (replaces the current four-state enum)

    EMPTY                    hung, no plan            no UI, dark lamp
      | insert Build Plan
    PLAN_INSERTED_UNLINKED   plan in, no room found   UI, red lamp
      | room found, requirements unmet
    LINKED_INCOMPLETE        UI lists what is missing  amber lamp
      | requirements met
    LINKED_VALID             building registered       green lamp
      | building gone from the settlement
    ORPHANED                 UI offers re-survey       red lamp

`NO_PERMISSION` stays out of the enum on purpose — it is a property of the
viewer, decided when a screen opens, never stored or synced.

## Work items

| # | Item | Acceptance criterion |
|---|---|---|
| W1 | `BuildPlanItem` + `build_plan` registration, `BUILDING_TYPE` component reused | six stamped stacks exist; an unstamped plan is not obtainable |
| W2 | `PlaqueState` gains `EMPTY` and `PLAN_INSERTED_UNLINKED`; `LINKED`→`LINKED_VALID`, `INCOMPLETE`→`LINKED_INCOMPLETE` | `byId` round-trips every value; an old save's `State` string still loads |
| W3 | `PlaqueBlockEntity` stores the inserted plan and refuses to survey while `EMPTY` | a hung plaque with no plan runs zero scans over 400 ticks (trace-proven) |
| W4 | `PlaqueBlock.useItemOn` inserts a plan; `useWithoutItem` on `EMPTY` opens no screen and sends one hint | GameTest: screen open count is 0 while empty, 1 after insertion |
| W5 | Plan extraction (empty-hand sneak-use) returns the exact stamped item and dissolves the building | item in = item out, same component; residents released |
| W6 | `GLOW` gains `EMPTY`; four block models (`plaque_empty|red|amber|green`) | KF-005: every blockstate variant resolves; validator green |
| W7 | Lang keys from `docs/PLAQUE_LANG_KEYS.md` (25 missing) in `en_us` + `nb_no` | KF-004: validator's key-parity check green |
| W8 | Recipes: `plaque`, and one `build_plan` per building type with the component on the result | each recipe loads; result carries the right `building_type` |
| W9 | GameTest helper `hangPlaque()` hangs in the **air cell** against the wall and inserts a plan | KF-001: `roomdetectedashome` reports `vol=27`, not `vol=1856`; 15/15 green |

## Test plan (pyramid)

- **A (cheap, after every change):** `tools/hearthstead-qa gametest`, then
  `assets` for W6–W8. Repeat-run rule: the five KF-001 tests must be green
  **5/5**, not once — this suite has been flaky-green before.
- **B (feature complete):** `tools/hearthstead-qa playtest` with a new scenario
  that hangs a plaque, proves no screen opens, inserts a plan, and proves the
  building registers — verified server-side via `hearthstead info`, never from
  a screenshot alone.
- **C (before RELEASE_GATE):** `tools/hearthstead-qa full`, green_streak ≥ 2.

## Out of scope

Building tiers, furnishing quality, resident assignment UI, the Architect's
Study as a working profession building, and any settler behaviour change. This
slice makes the plaque behave as specified and clears its three known failures;
it does not extend what a building means.

## Risks

- **Save compatibility.** Existing worlds hold `State=linked|incomplete`.
  W2 must map the old ids forward, not default them to `EMPTY` — that would
  silently un-home a running settlement. A GameTest loads a synthetic old tag.
- **Scan budget (INV-4).** Adding a state that never scans reduces work; but
  insertion must not trigger an unbounded immediate re-survey storm if a player
  fills a wall with plaques. Insertion reuses the existing `SURVEY_INTERVAL`
  cooldown path.
