# Hearthstead identity surfaces — visual rationale

Status: **offline art candidate only**. No Java integration or in-game claim.

## Targeted research adopted

1. **Three render planes, not colour substitution.** Background table grain is low-contrast; the physical screen object owns the middle plane; clasps, pins, seals and tools overlap it on a foreground plane. This follows the general art principle that foreground/midground/background separation is created primarily through value grouping and controlled contrast: <https://www.creativebloq.com/art/digital-art/how-to-master-light-and-depth-in-photoshop>.
2. **One light model.** Every material receives a one-pixel top/left highlight and a darker bottom/right edge. Raised pieces cast a hard 2–4 pixel bottom-right shadow. Consistent light direction is a core pixel-art depth rule: <https://pixnote.net/en/learn/shading/>.
3. **Interaction needs physical affordance.** Buttons, tickets and landmarks use a bevel, inset or cast shadow so clickability is visible without glow. This is consistent with the pixel-UI guidance that borders, shadows and highlights communicate affordance: <https://www.zapcode.dev/learn/pixel-art-games-with-ui-ux-design>.
4. **Material depth is authored, not procedural at render time.** Oak grain, soot, parchment speckle, hammered iron and nicks are baked into static PNG layers. NeoForge supports sprite atlas textures, relative GUI coordinates, render depth and nine-slice scaling; that lets the implementation retain these authored edges without per-frame texture generation: <https://docs.neoforged.net/docs/1.21.1/gui/screens/>.
5. **Nearest-neighbour logical pixels.** The art is checked at Minecraft logical GUI sizes and must not be arbitrarily rescaled into blur. Community reports show oversized GUI art becoming blurry when it is scaled rather than authored for the actual display size: <https://www.reddit.com/r/ModdedMinecraft/comments/1vpdv59/weird_gui_png_blur/>.

## Shared rules

- The low-level scalable widget/performance foundation remains available, but it does not dictate a screen's silhouette.
- Gold/brass marks focus and commitment. Green and red are semantic state colours only.
- Body copy is reduced to the current decision; secondary explanation belongs in hover/focus detail.
- Every large surface owns a physical metaphor and a recognisable silhouette.
- Foreground fasteners visibly overlap their paper/wood base and cast a hard shadow; hover implementation should move/depress by one logical pixel, never glow or flicker.
- All art is original Hearthstead work. No third-party texture, layout, code or protected asset was copied.

## Hearth Ledger

The Hearth is a charred-oak civic object held by soot-stone cheeks and forged-iron shoulders. The flame seal is the first focal point, communal storage is the second, and the open ledger at the bottom presents exactly one pending decision. Inventory remains a real 18-pixel slot grid. The shape avoids the previous tabbed admin-dashboard read.

## Settlement Survey

Development is a wooden survey table with a clipped parchment map. A river, tree groups and an organic road make progression spatial and memorable. Landmarks use different state materials rather than rectangular cards. Only the currently focused landmark opens the folded research document on the right, where unlock, prerequisite, cost and action remain readable.

## Courier Dispatch

Courier work is a soot-felt board with pinned, slightly irregular paper tickets. Each queue ticket answers only item, route and state. Selecting a ticket exposes courier and destination detail on its own docket. This removes repeated six-line request cards while preserving a physical drag-to-prioritise model.

## Building Plaque — Architect's Workbench (REJECTED)

This concept is retained only as rejected visual evidence. It is excluded from runtime integration and `PlaqueScreen` remains unchanged. Its model/readability and recipe-density problems must not be inherited by a later plaque direction.

## Before / after depth correction

- Before: colour separated the surfaces, but the Hearth store could still read as a black rectangle. After: it uses reversed recess lighting, a lower reflected lip, individually carved slot wells and a few recognisable item silhouettes.
- Before: map and research document shared nearly the same depth. After: the map has a clipped cloth shadow/dowel while the document has an independent cast shadow, wax seal and ruler/clip occlusion.
- Before: courier paper was primarily a fill. After: every ticket has a one-pixel thickness edge, bottom/right occlusion and pins that sit on a separate foreground plane.
- Before: helper labels competed with the art. After: essential operation hints sit on quiet dark wood and secondary explanations are removed from the primary surface.

## Self red-team gate

- Generic rectangles: **pass with caution** — internal information still needs bounded hit areas, but the three outer silhouettes and content structures are distinct.
- Excessive borders/decorative lines: **pass** — borders now describe material joins or interactive separation.
- Microtext: **pass at target logical resolutions** — no explanatory paragraph is required for primary operation.
- Dead space: **pass** — quiet space surrounds the current focal action rather than being filled with metadata.
- Green dominance / flat beige: **pass** — coal, charred oak, forged iron, parchment and brass carry identity; state colours remain sparse.
- Focal point: **pass** — flame/stores, route landmark/document and selected ticket/docket respectively.
- Three named depth planes: **pass** — every editable source has `BACK/CAST_SHADOW`, `FRAME_WOOD`, `INSET_PAPER_OR_LEATHER`, `FOREGROUND_HARDWARE`, `TEXT_AND_ICONS` and `INTERACTION_STATE`.
- Main-surface flatness: **pass** — communal storage is a reversed-edge recess with individually recessed slots; map and research docket have independent shadows; courier paper has a visible thickness edge and selected-ticket shadow.
- Thumbnail/value grouping: **pass** — `identity_surfaces_value_check.png` preserves three distinct focal structures without relying on state colour.
- Hover language: **pass as authored contract** — selected surfaces use a one-pixel raised edge plus shadow change; no glow texture exists. Runtime focus behaviour remains unverified until integration.

## Integration constraints

- Keep labels and item icons dynamic; do not bake copy into runtime art.
- Use screen-specific layers in `textures/gui/identity`, with nine-slice only for smaller repeated controls.
- Re-run visual checks at 320x240, 427x240 and 512x274 after Java integration.
- Candidate becomes approved only after real in-game focus, hover, narration, GUI-scale and multiplayer checks.
