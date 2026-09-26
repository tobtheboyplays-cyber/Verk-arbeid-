# REJECTED — non-runtime evidence: Master Carpenter's Fold-out Desk

This candidate was visually rejected and its textures were removed from the
packaged resource path. The document remains only as decision history.

## Runtime invariant

The menu remains a fixed `320 x 220` interactive core at every supported GUI
width. No breakpoint changes a slot index or relative slot coordinate.

- Communal store: indices `0..23`, `6 x 4`, frame origin `(106, 42)`.
- Player inventory: indices `24..50`, `9 x 3`, frame origin `(79, 142)`.
- Hotbar: indices `51..59`, `9 x 1`, frame origin `(79, 200)`.
- At viewports of at least `416 px`, two `48 px` furniture wings are drawn
  outside the core. They contain no controls, data, slots, or hitboxes.

## Static runtime assets

- `hearth_carpenter_core.png` — art-only fold-out desk around the real slots.
- `hearth_carpenter_wing_left.png` — optional clipped work-order furniture.
- `hearth_carpenter_wing_right.png` — optional survey/map furniture.
- `hearth_tab_idle.png`, `hearth_tab_hover.png`, `hearth_tab_selected.png` —
  three-slice leather bookmark states; hover lifts one pixel and never glows.
- `hearth_compact_work_order.png`, `hearth_compact_info.png` — narrow-only
  physical slips for the work order and radius.

Actual text, values, tooltips, item sprites, stack counts, focus, narration and
container interaction remain runtime-owned. In wide mode, the left clipboard
carries cached State/Cause/Next text and the right map carries radius. Compact
mode uses the two bounded slips above. No mockup content is baked in.

## Editable source and reproduction

- Layered source: `sources/hearth_runtime_carpenter_core.aseprite`
- Named planes: `BACK_CAST_SHADOW`, `FRAME_WOOD`,
  `INSET_PAPER_AND_DRAWERS`, `FOREGROUND_HARDWARE`
- Generator: `build_hearth_runtime_art.py`
- Aseprite assembler: `assemble_hearth_runtime.lua`

## Runtime-equivalent evidence

`build/ui_art_direction/hearth_runtime_integration/` contains normal, hover
and blocked states at `427 x 240` and `320 x 240`, plus the approval board.
These are geometry/art proofs, not a claim of native in-game verification.

## Scope boundary

This integration changes only Hearth presentation. Plaque, Development and
Courier presentation and all gameplay/server authority are intentionally
untouched.
