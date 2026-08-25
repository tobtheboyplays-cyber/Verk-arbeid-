---
name: minecraft-ui
description: How to build Minecraft screens that look designed rather than assembled — the nine-slice sprite kit, the offline preview that measures real font widths, vanilla's metrics, and the pitfalls that cost a client boot each. Use whenever writing or revising any GUI in this repository (screens, HUD, plaque UI, tooltips, widgets), or when a screen looks "off" and it is not obvious why.
---

# Minecraft UI — the kit, the metrics, and the traps

Written against **NeoForge 1.21.1, mojmaps**. Everything below is either
verified in this repository or taken from the sources at the end.

## 0. The workflow, before anything else

```bash
python3 tools/gen_ui.py                       # regenerate the sprite kit
python3 tools/ui/mockups.py                   # rebuild the layout specs
python3 tools/ui_preview.py --all --scale 3 --strict   # render + fail on overflow
tools/hearthstead-qa quick                    # compile + asset validation
```

**Design in the preview, not in the game.** Booting the client to look at a
panel costs minutes; the preview costs a second, draws the *same* sprites
through the *same* nine-slice rules, and measures text with the *real* vanilla
font. It renders to `build/uipreview/`. When a layout is settled, then boot —
`tools/hearthstead-qa live` — because the preview cannot judge motion, item
rendering or how it feels under the mouse.

The preview earns its keep by **failing**. Any string wider than the box it was
given is marked in red in the image and reported on stdout; `--strict` makes it
a non-zero exit. The first run of the hire screen reported *"The Farmhouse
would have no farmer" is 190px in a 152px box* — the most important sentence on
the screen, discovered before a line of Java existed.

## 1. Never draw a panel as rectangles again

Three ways to put a panel on screen, in increasing order of correctness:

| approach | why it fails |
|---|---|
| stacked `graphics.fill` rectangles | costs no art and always looks like stacked rectangles |
| blit one big PNG | fixes the panel at one size forever; every layout change is an art change |
| **nine-slice sprite** | one 18×18 image is every panel at every size |

Vanilla has done the third since 1.20.2 and it is what `HsUi` uses. A sprite
lives in `textures/gui/sprites/<path>.png` with a `<path>.png.mcmeta` beside
it, and is addressed **without the folder or the extension**:
`ResourceLocation.fromNamespaceAndPath("hearthstead", "panel/window")`.

### The .mcmeta schema (all of it)

```json
{ "gui": { "scaling": {
    "type": "stretch" | "tile" | "nine_slice",
    "width":  <int>,          // required for tile and nine_slice
    "height": <int>,          // required for tile and nine_slice
    "border": 4,              // nine_slice: int, or {left,top,right,bottom}
    "stretch_inner": false    // nine_slice only, default false
} } }
```

### The rule that makes or breaks nine-slice

**With `stretch_inner` false — the default — the edges and the middle TILE,
they do not stretch.** So a top edge whose pixels vary along x will visibly
repeat every few pixels, and a noisy centre becomes a checkerboard.

The fix is not care, it is construction:

> **Paint every frame pixel as a function of its distance to the nearest edge.**

A pixel on the top edge then depends only on `y`, so that edge is constant along
`x` and tiles invisibly — and the same profile mitres the corners for free.
Directional light survives it, because *which* edge is nearest is itself
constant along an edge. All per-corner detail (rivets, plates) goes inside the
fixed corner squares, which never tile. This is `frame()` in `tools/gen_ui.py`
and it is why that generator is 200 lines instead of 1000.

## 2. Vanilla's metrics — deviate deliberately or not at all

Half of "this mod's UI looks wrong" is a number that disagrees with vanilla for
no reason. Match these unless there is a reason to differ:

| thing | value |
|---|---|
| item slot | **18×18** (a 16×16 item with a 1px lip) |
| button height | **20** |
| vanilla container panel | 176×166 |
| text line height | **8**, line spacing 9–11 |
| text colour on dark | `0xFFE8E0D0`-ish; muted `0xFF8A8578` |
| spacing grid | **4px**, everywhere |

Inconsistent gutters are the single most common reason a modded screen reads as
amateur next to a vanilla one. Use `HsUi.Column`, which advances by row height
plus one gutter, so the rhythm is stated once instead of re-derived per call.

**Pips, not integers.** You read "four of five" at a glance; you never read "4"
at a glance. This is the concrete fix for the most common complaint about
MineColonies' hire tab — a wall of names and numbers.

## 3. The API, exactly (1.21.1)

```java
// surfaces
graphics.blitSprite(ResourceLocation sprite, int x, int y, int w, int h);
graphics.blit(ResourceLocation tex, int x, int y, int u, int v, int w, int h);  // assumes 256x256
graphics.fill(int x1, int y1, int x2, int y2, int argb);
graphics.fillGradient(int x1, int y1, int x2, int y2, int argbTop, int argbBottom);
graphics.hLine(int x1, int x2, int y, int argb);

// text  (all colours are ARGB — forget the alpha and you draw nothing)
graphics.drawString(Font f, Component text, int x, int y, int argb, boolean shadow);
graphics.drawCenteredString(Font f, Component text, int centreX, int y, int argb);
graphics.drawWordWrap(Font f, FormattedText t, int x, int y, int width, int argb);
font.width(Component);                  // measure BEFORE you place
font.plainSubstrByWidth(String, int);   // the ellipsis primitive

// clipping and tooltips
graphics.enableScissor(int x1, int y1, int x2, int y2);  // then disableScissor()
graphics.renderTooltip(Font, Component, int mouseX, int mouseY);
```

Screen lifecycle: `init()` (build widgets and precompute positions — never in
`render`), `render(GuiGraphics, mouseX, mouseY, partialTick)`, `tick()`,
`onClose()`, `removed()`. Add widgets with `addRenderableWidget` (rendered +
interactive + narrated), `addWidget` (interactive only) or `addRenderableOnly`.
For container screens: `containerTick()`, `renderBg(...)`, `renderLabels(...)` —
and inside `renderLabels` the pose is **already translated** by
`leftPos/topPos`, so do not add them again.

## 4. The traps, each of which has cost someone a client boot

1. **Thin geometry is measured in screen pixels, not intent.** Ruled lines at
   0.9 font units lifted 0.0008 blocks were perfectly present in the code and
   invisible in the game. Under ~2px, a line is a rumour. *(This repo, PLAQUE-2.)*
2. **Do not guess text width — measure it.** A label that fits in English
   overflows in Norwegian. `æøå` come from a different, taller glyph sheet than
   ASCII, so even the height assumptions differ.
3. **`✓` and `✗` (U+2713/2717) are unifont-only and render as boxes** at
   default settings. Use **`✔` and `✘` (U+2714/2718)**, which are in
   `nonlatin_european.png`. Verified by reading the client jar's font providers.
4. **Rendering an item in a UI: use `ItemDisplayContext.GUI`.** `FIXED` is the
   item-frame pose and collapses two-block models — a bed becomes a strip of
   planks. GUI is the pose the player already knows from the hotbar.
5. **A missing sprite is not an exception**, it is a magenta checkerboard. If a
   panel renders as checks, the path is wrong — check for the folder prefix or
   the `.png` you should not have written.
6. **Reset the blit offset / pose.** Push and pop around anything that
   translates, or later elements land in the wrong layer.
7. **`enableScissor` takes screen coordinates**, not your panel's. Scaled GUI
   makes this wrong in a way that only shows at non-default GUI scale.
8. **Relativize every coordinate to `width`/`height`.** Then actually check it:
   the fastest test is cycling GUI Scale in video settings, 1 through 4.
9. **A disabled button that looks enabled is a lie.** Four states — idle,
   hover, pressed, disabled — or the screen feels dead under the mouse.
10. **Colour the one press a player can regret.** A destructive action gets the
    danger sprite for the same reason a fire alarm is red.

## 4b. D-014 — no dead controls (a standing rule, and it is enforced)

> Every button, tab and clickable thing does a real thing. A control that
> cannot act right now is **visibly disabled and says why**. No placeholders,
> no decoration, no "coming soon".

A button that does nothing is worse than a missing one: the player presses it,
nothing happens, and they stop trusting the whole screen. Trust in a UI is
spent, not earned back.

`validate_assets.py` fails the build on any press handler in `client/` with an
empty body, so this cannot rot quietly. It catches "wired to nothing"; whether
an action is *meaningful* is a review judgement. When you find yourself wanting
a placeholder, the honest move is to not draw the control at all until it does
something — or to draw it disabled with the reason in its tooltip.

## 5. Why this repository has no UI library

The good ones — **owo-lib** (declarative UI, the nicest of the bunch),
**LibGui**, **YACL**, **Cloth Config** — are Fabric-first, and the config
libraries are built for config screens rather than game UI. This mod is
NeoForge (D-002) and has no third-party runtime dependencies by policy. So we
take the *ideas* — declarative composition, layout containers, a token system —
and build them on vanilla `GuiGraphics`. `HsUi.Column` is that idea at the size
this project actually needs.

## 6. One source of truth for numbers

`tools/ui/tokens.json` holds every colour and metric. `tools/gen_ui.py` emits
both the sprites **and** `HsUiTokens.java` from it, and the preview reads the
same file. `validate_assets.py` runs the generator twice under different
`PYTHONHASHSEED`s and fails if a byte differs or if what is committed is stale.

A preview drawn from different numbers than the game is a preview that lies,
and a lying preview is worse than none — you would trust it.

## Sources

- [NeoForge — Screens](https://docs.neoforged.net/docs/1.20.6/gui/screens/)
- [GuiGraphics javadoc, 1.21.1](https://lexxie.dev/neoforge/1.21.1/net/minecraft/client/gui/GuiGraphics.html)
- [Minecraft Wiki — resource pack GUI sprite scaling](https://minecraft.wiki/w/Resource_pack)
- [Minecraft Wiki — texture atlas](https://minecraft.wiki/w/Texture_atlas)
- [owo-lib](https://modrinth.com/mod/owo-lib) · [YACL](https://docs.isxander.dev/yet-another-config-lib)
- Verified in-repo: `tools/gen_ui.py`, `tools/mcfont.py`, `tools/ui_preview.py`,
  `src/main/java/com/hearthstead/client/ui/HsUi.java`.
