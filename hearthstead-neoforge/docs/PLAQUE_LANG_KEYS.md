# Plaque localisation keys — prepared inventory for PLAQUE-1

Read-only preparation done during HARNESS-1 so the plaque slice does not spend
its own time enumerating this. **Nothing here is implemented yet** — the lang
files are untouched. `en_us.json` is the source language and `nb_no.json` must
keep full key parity (the validator enforces it).

## Why the validator only names two

`tools/validate_assets.py` derives keys from the registry, so it reports only
`block.hearthstead.plaque` and the item key. Every key below is referenced
from Java by `Component.translatable(...)` and would render as a raw key in
game — visible to the player, invisible to the validator. That gap is worth
closing in PLAQUE-1: a check that every literal passed to `translatable(` in
`src/` exists in `en_us.json` would have caught all of it.

## Concrete keys referenced from code

Registry-derived (validator sees these):
`block.hearthstead.plaque`, `item.hearthstead.plaque`

Plaque screen and interaction:
`hearthstead.plaque.title`, `.level`, `.residents`, `.needs`, `.empty`,
`.no_room`, `.assign`, `.choose`, `.back`, `.evict`, `.evict.tip`, `.close`,
`.refresh`, `.dissolved`, `.too_far`, `.stale`, `.not_ready`,
`.settler_gone`, `.blocked.full`, `.blocked.not_ready`

Commands: `hearthstead.command.no_plaque`, `hearthstead.command.scan_done`

Mood: `hearthstead.mood.happy`, `.content`, `.unhappy`

## Keys built by concatenation — the prefixes the grep shows

These appear in the code as a prefix plus an id, so the concrete keys must be
enumerated from the enums rather than found by searching for string literals.

`hearthstead.building.` + type id → **house, lodging, warehouse, lumber_camp,
farmhouse, architects_study**

`hearthstead.requirement.` + requirement id → **beds, doors, lights,
floor_space** (built-ins) and **storage, workbench, composter, lectern,
bookshelf** (the ids `BuildingType` passes to `Requirement.blocks(...)`)

`hearthstead.plaque.state.` + state id → **unlinked, incomplete, linked,
orphaned** — and after D-006 also **empty** and **plan_inserted_unlinked**,
plus **no_permission** if that is ever surfaced as a state string rather than
a per-viewer condition.

`hearthstead.profession.` + lowercased profession → **none, farmer, lumberer,
guard**

## Notes for whoever writes the strings

- The requirement lines are rendered as "have / needed" next to the label, so
  the label should be a plain noun phrase ("Beds", "Lit torches"), not a
  sentence.
- `hearthstead.plaque.level` takes a Roman numeral argument.
- `hearthstead.plaque.evict.tip` takes the settler's name.
- `hearthstead.requirement.*` entries receive `have` and `needed` as arguments.
- `hearthstead.plaque.dissolved` takes the building type's display name.
- All player-facing text is English; `nb_no.json` needs the same key set.

## Pre-existing prefixes seen in the same sweep (NOT plaque work)

`hearthstead.gui.tooltip.`, `hearthstead.morale.`, `hearthstead.guide.page`
are older dynamic prefixes from the handbook and settler screens. They are out
of scope here; noted only so a future sweep does not mistake them for a
regression introduced by the plaque.

---

## Verified against source (2026-08-24, HARNESS-1 close-out)

Re-derived mechanically rather than by hand: every string literal passed to
`Component.translatable(` under `src/main` was extracted and diffed against
`en_us.json`.

- **39 keys must be added, to BOTH `en_us.json` and `nb_no.json`.** The exact
  list is `docs/plaque_missing_keys.txt` — 19 referenced literally, 20 produced
  by concatenated prefixes that the extractor can only see as a stem.
- **Key parity between the two lang files is currently intact (0 breaks)**, so
  W7 must add to both or it breaks the validator's parity rule.
- Only three prefix families are genuinely empty: `hearthstead.building.*`,
  `hearthstead.plaque.state.*` and `hearthstead.requirement.*`. The other
  families the extractor flags as stems — `activity` (10 keys), `gui.tooltip`
  (11), `morale` (4), `profession` (4), `guide.pageN` (13) — are all fully
  populated. An earlier reading of this audit suggested the Hearth UI and
  handbook were also missing localisation; that was wrong, and checking it is
  what showed KF-004 really is plaque-scoped.
- The `plaque.state.*` ids in the list follow **PLAQUE-1's new state machine**
  (`empty`, `plan_inserted_unlinked`, `linked_incomplete`, `linked_valid`,
  `orphaned`), not the current four — W2 and W7 have to land together.

**Worth building in W7:** the validator derives keys from the registry, so it
sees exactly two of these 39. A check that every `translatable(` literal in
`src/` resolves in `en_us.json` would have caught all of them, and would keep
catching them. The extractor above is ~15 lines and belongs in
`tools/validate_assets.py`.
