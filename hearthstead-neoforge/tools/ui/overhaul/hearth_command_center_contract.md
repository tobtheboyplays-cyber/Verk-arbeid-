# Hearth command-center preview contract

Preview-only design note. This does not authorize a runtime, language, texture,
sound or data-model change.

## Composition intent

- One calm hierarchy: settlement identity, independently bounded Mayor status,
  one large navigation strip, then Summary / Attention / Progress.
- Primary routes are visible without opening or overlapping a second panel:
  Alerts, Requests, Journey and Development.
- The first-raid state is always visible in Progress; it cannot masquerade as
  ready while authoritative blockers remain.
- Asynchronous states use explicit words as well as colour:
  `Loading...`, `EMPTY`, and `BLOCKED`.
- Desktop target is exactly 512x274 logical pixels. Narrow stress target is
  320x240 and reflows content into a 2x2 card grid instead of shrinking text or
  placing panels over one another.

## Geometry and readability rules

- All authored element coordinates and dimensions use the shared 4px grid,
  except the required 274px desktop canvas height and its full-height window.
- Shared `tokens.json` colours, metrics, sprites and the real Minecraft font
  are rendered by `tools/ui_preview.py`.
- Buttons are at least 20px high. Cards are at least 32px high on desktop;
  narrow content/state cards are 40px high while its compact, two-line Mayor
  status remains a bounded 32px header card. Text boxes have explicit bounds
  and strict overflow is a hard failure.
- Information surfaces are siblings in one layout. No popout is painted over
  the settlement summary or an inventory surface.

## State semantics shown

| Surface | Preview state | Required reading without colour |
|---|---|---|
| Mayor | Partial asynchronous refresh | `Candidates: Loading...` |
| Alerts | Authoritative empty result | `EMPTY` and `No active ...` |
| Requests | One actionable transport failure | `BLOCKED` and a short reason |
| Journey | Active step | Current step name and progress |
| Development | Available progression | Learned/available counts or primary route |
| First Raid | Not ready | `BLOCKED` and remaining requirement count |

## Clean-room boundary

The composition uses only Hearthstead's existing preview primitives and shared
tokens. It does not reproduce MineColonies, TekTopia or any other third-party
layout, copy, code or assets.
