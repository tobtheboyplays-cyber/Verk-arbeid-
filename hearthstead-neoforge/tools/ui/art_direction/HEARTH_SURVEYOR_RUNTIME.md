# Hearth — Surveyor's Roll-Top runtime direction

## Runtime invariant

The menu remains one fixed `320 x 220` interactive core at every viewport.
No responsive branch changes a slot index or relative coordinate.

- Communal store: indices `0..23`, `6 x 4`, origin `(106, 42)`.
- Player inventory: indices `24..50`, `9 x 3`, origin `(79, 142)`.
- Hotbar: indices `51..59`, `9 x 1`, origin `(79, 200)`.

## Information architecture

- The dominant horizontal survey scroll owns `Next Action`, state, cause and
  the next player action.
- A five-position rotary chapter wheel replaces the old top tab strip. Every
  seal retains its localized tooltip, keyboard focus and narrated label.
- One abacus rail summarizes population, employment, food and morale in four
  equal 76px cells. Each cell reserves a 20px icon gutter and a bounded 48px
  value plane; `100/100` retains an 8px icon-to-text clearance.
- Settlement radius belongs to the survey compass.
- All inventory slots sit in one physical pull-out drawer.

## Static assets and performance

- `hearth_surveyor_core.png` is one immutable `320 x 220` background blit.
- `hearth_surveyor_chapters.png` is a `100 x 60` five-column/three-state sheet.
- Dynamic labels are cached when data, font or language changes.
- No texture generation, parallax, glow, layout rebuild or new art allocation
  occurs in the steady render path.

## Editable and reproducible sources

- Generator: `build_hearth_surveyor_runtime_art.py`
- Named planes: `runtime_hearth_surveyor_layers/`
- Concept reference: `hearth_ia_divergence/concept_b_surveyor_rolltop_source.png`

## Evidence

`build/ui_art_direction/hearth_surveyor_runtime/` contains normal, hover,
blocked and compact runtime-equivalent previews, the approval board, and the
machine-readable bounds/accessibility audit.

This integration changes Hearth presentation only. Plaque, Development,
Courier, menu authority, packet handling and gameplay logic are untouched.
