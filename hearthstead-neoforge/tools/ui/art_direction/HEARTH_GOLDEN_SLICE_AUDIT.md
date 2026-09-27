# Hearth premium golden slice — visual approval audit

Status: **offline runtime-equivalent candidate, not an in-game claim**. This gate is Hearth-only. Plaque is rejected and untouched; Development and Courier integration are paused.

## Ground truth used

- The object is the real `320 x 220` Hearth ledger with its five real tabs.
- Communal storage uses the real `6 x 4`, 18-pixel slot rhythm anchored at `(106, 42)`.
- Player inventory uses the real `9 x 3` inventory plus hotbar anchored at `(79, 142)`.
- The examples are recognizable real item types: Oak Log, Wheat Seeds, Iron Ingot, Iron Axe and Bread.
- The blocked state comes from `RecruitmentPolicy.Blocker.NO_TAVERN` and the existing `hearthstead.gui.recruit_blocked.tavern` path. The next action follows the existing journey language: build and link a valid Tavern.

## Adopted depth techniques

1. Three explicit planes: quiet table/world field, forged-stone/oak ledger, then clasps/rivets/seal above it.
2. One top-left light vector: every raised plane has a one-pixel warm top/left highlight and a two-to-four-pixel cool bottom/right shadow.
3. Recesses reverse the edge relationship: communal and player slots use dark top/right occlusion with a restrained lower/left reflected lip.
4. Physical overlap carries hierarchy: tabs overlap the frame, brass clips occlude the communal well, and the warning seal sits above its paper docket.
5. Material identity is authored, not recoloured: charred oak grain, soot-stone cheeks, hammered iron hardware, brass clips and paper thickness use distinct edge/noise behaviour.

Technical reference: [NeoForge 1.21.1 screens and GUI sprites](https://docs.neoforged.net/docs/1.21.1/gui/screens/). Art references: [Pixnote consistent-light shading](https://pixnote.net/en/learn/shading/), [Zapcode pixel UI affordance](https://www.zapcode.dev/learn/pixel-art-games-with-ui-ux-design), and [Creative Bloq value grouping](https://www.creativebloq.com/art/digital-art/how-to-master-light-and-depth-in-photoshop). Principles only; all Hearthstead artwork is original.

## Exactly five approval criteria — pending Tobias

1. **Depth — PENDING.** Check the three independently editable planes, cast shadows, recesses and visible occlusion at thumbnail size.
2. **Readability — PENDING.** Check that active data, real slots and alert hierarchy fit without scrolling or clipping.
3. **Intuitiveness — PENDING.** State B demonstrates a real Oak Log slot with restrained one-pixel lift; State C reads State, Cause, Owner, Next.
4. **Hearth identity — PENDING.** Judge whether flame crest, communal store, charred oak, soot-stone and iron feel specific enough.
5. **Render-cost safety — PENDING.** Static sprite planes and fixed geometry avoid glow, flicker, parallax, texture generation and per-frame layout rebuilding.

## Flatness self-audit

**Ready for visual review, with one honest limitation.** The communal store is a recess rather than a flat black void, information is split between a carved tally and a tucked Hearth summary, and the alert is a physically anchored pull-out docket. The offline renderer uses symbolic item sprites and Pillow's compact bitmap font; runtime must retain Minecraft's actual item renderer, font, focus/narration and tooltips. Therefore this board proposes visual direction only, not final in-game legibility or performance.
