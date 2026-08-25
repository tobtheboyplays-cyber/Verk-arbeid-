---
name: minecraft-ui
description: How to build Minecraft screens that look designed rather than assembled — the Hearthstead design language (iron-and-oak panels, layout grid, hierarchy recipes, state rules, color semantics), the nine-slice sprite kit, the offline preview that measures real font widths, vanilla's metrics, and the pitfalls that cost a client boot each. Use whenever writing or revising any GUI in this repository (screens, HUD, plaque UI, tooltips, widgets), when critiquing a screenshot, or when a screen looks "off" and it is not obvious why.
---

# Minecraft UI — the design system, the kit, and the traps

Written against **NeoForge 1.21.1, mojmaps**. Everything below is either
verified in this repository or taken from the sources at the end.

## 0. The workflow, before anything else

```bash
python3 tools/gen_ui.py                       # regenerate the sprite kit
python3 tools/ui/mockups.py                   # rebuild the layout specs
python3 tools/ui_preview.py --all --scale 3 --strict   # render + fail on overflow
tools/hearthstead-qa quick                    # compile + asset validation
```

**Design in the preview, not in the game.** A client boot costs minutes; the
preview costs a second, draws the *same* sprites through the *same*
nine-slice rules, measures text with the *real* vanilla font, and renders to
`build/uipreview/`. It earns its keep by **failing**: any string wider than
its box is marked red; `--strict` makes it a non-zero exit. Run it for
**both en_us and nb_no** — the hire screen's most important sentence was
190px in a 152px box before a line of Java existed. When the layout is
settled, then boot (`tools/hearthstead-qa live`) — the preview cannot judge
motion, item rendering, or feel under the mouse.

## 1. The Hearthstead design language (DESIGN.md §10, codified)

North star: **dark iron-and-oak panels** — portrait citizen cards with skill
pips, rarity frames, the Tingboka tabbed book, alert cards. Two grounds:
**dark** (default for all new screens — coal interiors inside carved oak
frames with brass accents, bone-white text) and **parchment** (the Hearth
ledger interior, book pages — ink text, used only where the fiction is
literally paper).

### 1a. The ramps (from `tools/texlib.py` — never invent a new hex ad hoc)

| ramp | 0 (dark) → 4 (light) | role |
|---|---|---|
| `oak_carved` | `#241a0e #3a2a18 #4a3421 #5a4229 #6b5137` | frames, tab bodies |
| `iron_forged` | `#1c1c20 #2b2b30 #3b3b42 #48484f #55555c` | rims, idle edges, common tier |
| `brass` | `#5c4715 #8a6c22 #b8912f #c6a043 #d4af5a` | accent, hover edges, top tier |
| `charcoal` | `#121212 #1a1a1a #1e1e1e #242424 #2e2e2e` | interiors, fields, wells |
| `bone` | `#6f6a5e #8a8578 #a8a294 #c8c0ae #e8e0d0` | text on dark |
| `emerald`/`crimson`/`amber` · `parchment`/`ink` | see texlib | good/bad/warn tones · the paper ground |

### 1b. Panel anatomy (exact, from `gen_ui.py`)

| surface | nine-slice border | construction |
|---|---|---|
| `panel/window` | **7px** | 1px near-black outline (`shade(OAK[0],0.55)`), 1px lit oak chamfer (`OAK[3]`), 3px oak boards darkening inward, brass inner rule, then coal interior |
| `panel/inset` | **3px** | recessed coal well with an iron rim — shadow at top-left, lit lip bottom-right (it is a hole, so depth reverses) |
| `panel/card` | **4px** | oak body, iron edge; `card_hover` swaps the edge to `BRASS[1]` |
| `widget/button_*` | **3px** | oak body, brass edge states (see §5) |
| `widget/tab_*` | **3px** | selected = brass-topped oak; unselected = iron-rimmed coal |
| slot / scrollbar / bars | **2px** | iron-rimmed coal wells |

Rules that keep it one language:

- **Light comes from top-left.** Crown highlight top-left, contact shadow
  bottom-right on every raised form; reversed on every well. (Same law as
  the polermester's texture doctrine — the UI is not exempt.)
- **No anti-aliasing, no painted gradients** — dither between ramp steps;
  `fillGradient` is for translucent black scrims only.
- **Brass is scarce.** It marks the primary action, the selected tab, the
  hovered edge, the accent value — two brass things at rest on one panel
  means one of them is wrong.
- **Header treatment:** title centred in the 16px title band (baseline
  y≈12), `TEXT_STRONG`, full-width divider at y≈26 inset `PAD`. Emblem
  headers (small icon left of title) allowed; two-line titles are not.
- **Divider discipline:** dividers separate semantic groups only — never two
  within 12px, ≤3 per 200px of height, always inset `PAD`, always the 2px
  sprite (under ~2px a line is a rumour — trap #1).

## 2. Layout — numbers, not taste

All from `tools/ui/tokens.json` (one source of truth, §14):
`GRID 4 · PAD 8 · GUTTER 4 · TEXT_H 8 · LINE_GAP 11 · ROW_H 20 · CARD_H 36 ·
BUTTON_H 20 · TITLE_H 16 · SLOT 18 · SCROLL_W 6 · DIVIDER_H 2`.

- **The 4px grid is law.** Every x, y, w, h is a multiple of 2; every gap
  between siblings a multiple of 4; panel edge to content = `PAD` (8); icon
  to label = 4–5 (the stat row uses icon 12 + 5). Inconsistent gutters are
  the single most common reason a modded screen reads as amateur.
- **Panel widths:** small dialog 176 (vanilla container width), standard
  popout 256 (the Mayor panel), never wider than ~320 — it must survive GUI
  scale 4 on 1080p next to its parent window.
- **Max text measure:** the vanilla font averages ~6px/char; body copy sits
  best at 30–50 characters ≈ **152–256px**, hard cap 300px. Below ~90px,
  wrap or redesign — three-word lines read worse than a tooltip.
- **Vertical rhythm:** rows advance by `ROW_H`, wrapped text by `LINE_GAP`
  (11 = 8px glyph + 3); use `HsUi.Column` so the rhythm is stated once.
- **Alignment:** label left edges identical down a column; numbers
  right-aligned within their column; buttons right in cards, bottom-right in
  dialogs. 1px of drift is a defect — measure in the preview PNG.
- **One primary action per panel.** Size, position, and the accent edge mark
  it; everything else is secondary (iron edge) or a text link. Two
  equally-loud buttons means the player reads neither.

## 3. Hierarchy recipes (the standard assemblies)

**Citizen / candidate card** (CARD_H 36–38, step = h+4, from the Mayor list):
1. portrait 24×24 inset at left (when portraits exist); text block at
   `x+10` (no portrait) or `x+34`;
2. **name** — `TEXT_STRONG`, y+6, in a measured box that ellipsises;
3. **profession / consequence line** — `TEXT_MUTED`, y+20 ("would bring X" —
   say what the choice *does*, not what the thing *is*);
4. **skill pips** right of the text block (5 pips, 4px step, Tone.ACCENT);
5. **one action button** 64×20, right edge inset 8. Hover = card edge brass.
Needs/mood, when shown, are a 3-tone mini-bar under the name — never numbers.

**Requirement row** (plaque scan results): icon 12×12 → 5px → label
(`TEXT`) → count right-aligned as `have / need` (`TEXT_MUTED`, tone when
short) → state mark at far right (`✔`/`✘`, tone-coloured). Row height 16.
The row reads left-to-right as: *what → how much → satisfied?*

**Stat row** (Hearth ledger): icon 12×12 + value at +17, ROW 16, tooltip
carries the label — the icon *is* the label at rest (progressive disclosure).

**Alert card / banner:** icon + one sentence + tone colour; blinks at
**400ms** period between two readable shades; at most **one** blinking
element per screen, and it is always the thing that loses the settlement.

**Tooltips are tiered:** line 1 = name (`TEXT`), line 2+ = gray description
(`ChatFormatting.GRAY`), costs in COSTS.md's two-line price form.
Always-visible beats tooltip for anything consulted every visit; the
reverse for anything consulted once.

## 4. Pips, bars, and integers

**Pips, not integers.** You read "four of five" at a glance; you never read
"4" at a glance — the concrete fix for MineColonies' hire-tab wall of
numbers. The mapping is fixed:

| data shape | control |
|---|---|
| 0–5 scale (skill, knack) | 5 pips, `HsUi.pips` |
| 0–100 continuous (morale, progress) | bar with quarter tick marks |
| exact counts that matter (items, population) | integer, `a / b` form |
| booleans | `✔`/`✘` (U+2714/2718 — see trap #3), tone-coloured |

`Tone.of(ratio)`: **≥0.70 GOOD, ≥0.35 WARN, else BAD** — bars, pips and
text agree everywhere; never re-derive thresholds per screen.

## 5. State & feedback (every interactive thing, four states)

| state | rule |
|---|---|
| idle | iron edge, base body |
| hover | edge → `BRASS[1]`, body one ramp step lighter (≈+10–15% value); cards swap to `card_hover`. Appears the frame the mouse enters — no fade-in |
| pressed | body one ramp step darker, **content offset +1px right/down** (the inset illusion); if animated, 2–4 frames at 20tps = 100–200ms |
| disabled | body desaturated toward iron, label `TEXT_MUTED` — **and a tooltip that says why** (D-014, §8b) |

Sound: **click on activation, never on hover** in lists (hover sound × 20
rows = noise). Samples 100–300ms, pitch-varied ±5% per play so repetition
does not grate; destructive confirmations one octave lower. Destructive
actions also get the danger treatment (crimson edge) for the same reason a
fire alarm is red — colour the one press a player can regret.

## 6. Colour semantics — the status and rarity language

**Status tones (dark ground):** `GOOD #5FA860 · WARN #C98A2E · BAD #8A3A35
· ACCENT #B8912F`; on parchment the ledger uses its ink-side set
(`#5B8A4A/#C9A83C/#C07A35/#A03535`) — same meanings, per-ground values.

**Rarity frames** (role cards, blessings, trade goods) — the industry
language is grey < white < green < blue < purple < orange (Diablo → WoW);
Hearthstead compresses it to four tiers inside the house palette, keeping
gold-at-top, which reads universally:

| tier | frame ramp | text |
|---|---|---|
| Common | `iron_forged` | `TEXT` |
| Fine | `emerald` | `#84c184` |
| Superior | `amber` | amber[3] |
| Masterwork | `brass` + corner glint | `#d4af5a` |

A fifth tier would need a cold (lapis) ramp added to texlib as a recorded
decision — never an improvised blue.

**Contrast is measured, not felt** (WCAG numbers adapted to games; Xbox XAG
102 uses the same 4.5:1). Measured for this palette:

| pair | ratio | verdict |
|---|---|---|
| `TEXT` on field/coal | 13.3:1 | body text, always safe |
| `TEXT_MUTED` on field `#1A1A1A` | 4.7:1 | passes — **on fields only** |
| `TEXT_MUTED` on oak boards `#4a3421` | 3.2:1 | secondary/large only — no body copy on bare oak |
| tones (GOOD/WARN/ACCENT) on field | ~6:1 | fine as text |
| **`BAD #8A3A35` as text on dark** | **2.3:1** | **forbidden** — fills/pips only; error *text* uses `crimson[4] #d9584a` (4.5:1) |
| ink on parchment | 9.7:1 | body; `INK_SOFT` 5.3:1 secondary |

Rules: body text ≥4.5:1 against its local ground; secondary/large ≥3:1;
non-text state marks ≥3:1 or paired with a shape (colour is never the only
channel — a colour-blind player must still read pass/fail from `✔`/`✘`).

## 7. Colony-sim information density (RimWorld's lesson, our terms)

- **At-a-glance beats drill-down** for anything checked every minute: state
  shows *on the element itself*, visually, not as text (mood as card tint,
  job as portrait badge). Menus are for acting, not monitoring.
- **List vs card:** cards (portrait + pips) up to ~8 items; beyond that a
  dense list with the same columns in the same order. Never paginate what
  can scroll.
- **Pinned alerts, not modal:** alerts stack in a fixed corner, ordered by
  severity tone, click-to-focus, never stealing the cursor. Interrupting is
  reserved for "the settlement is lost *now*".
- **Progressive disclosure has exactly three levels:** glance (tint, pip,
  badge) → hover (tiered tooltip) → open (the screen). A fact needing a
  fourth level is cut or promoted.
- **HUD restraint:** the HUD shows only what is actionable this minute;
  everything else lives in the Tingboka. Elements fade when irrelevant;
  design for peripheral readability (shape + tone), not foveal reading.

## 8. Never draw a panel as rectangles again

| approach | why it fails |
|---|---|
| stacked `graphics.fill` rectangles | costs no art and always looks like stacked rectangles |
| blit one big PNG | fixes the panel at one size forever; every layout change is an art change |
| **nine-slice sprite** | one 18×18 image is every panel at every size |

Vanilla has done the third since 1.20.2 and it is what `HsUi` uses. A sprite
lives in `textures/gui/sprites/<path>.png` with a `<path>.png.mcmeta` beside
it, addressed **without folder or extension**:
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
they do not stretch.** A top edge whose pixels vary along x visibly repeats,
and a noisy centre becomes a checkerboard. The fix is not care, it is
construction: **paint every frame pixel as a function of its distance to the
nearest edge.** A top-edge pixel then depends only on `y`, so the edge is
constant along `x` and tiles invisibly — and the same profile mitres the
corners for free. Directional light survives it, because *which* edge is
nearest is itself constant along an edge. Per-corner detail (rivets, plates)
goes inside the fixed corner squares, which never tile. This is `frame()` in
`tools/gen_ui.py` and why that generator is 200 lines instead of 1000.

## 8a. Vanilla's metrics — deviate deliberately or not at all

Half of "this mod's UI looks wrong" is a number that disagrees with vanilla
for no reason: item slot **18×18** (16×16 item + 1px lip) · button height
**20** · container panel 176×166 · text line height **8**, spacing 9–11 ·
icon sizes 12–16px. Icons are **silhouette-first**: block the shape in one
colour — if it does not read at actual size, no shading will save it.
Outline in a darker step of the body's own ramp, never pure black; at
12–16px more colours are noise, not detail.

## 8b. D-014 — no dead controls (a standing rule, and it is enforced)

> Every button, tab and clickable thing does a real thing. A control that
> cannot act right now is **visibly disabled and says why**. No placeholders,
> no decoration, no "coming soon".

A button that does nothing teaches the player to distrust the whole screen —
trust in a UI is spent, not earned back. `validate_assets.py` fails the build
on any empty press handler in `client/`. When you want a placeholder, the
honest move is to not draw the control until it does something — or draw it
disabled with the reason in its tooltip.

## 9. The API, exactly (1.21.1)

```java
// surfaces
graphics.blitSprite(ResourceLocation sprite, int x, int y, int w, int h);
graphics.blit(ResourceLocation tex, int x, int y, int u, int v, int w, int h);  // assumes 256x256
graphics.fill(int x1, int y1, int x2, int y2, int argb);
graphics.fillGradient(int x1, int y1, int x2, int y2, int argbTop, int argbBottom); // scrims only

// text  (all colours are ARGB — forget the alpha and you draw nothing)
graphics.drawString(Font f, Component text, int x, int y, int argb, boolean shadow);
graphics.drawCenteredString(Font f, Component text, int centreX, int y, int argb);
graphics.drawWordWrap(Font f, FormattedText t, int x, int y, int width, int argb);
font.width(Component);                  // measure BEFORE you place
font.plainSubstrByWidth(String, int);   // the ellipsis primitive
graphics.enableScissor(int x1, int y1, int x2, int y2);  // then disableScissor()
graphics.renderTooltip(Font, Component, int mouseX, int mouseY);
```

Screen lifecycle: `init()` (build widgets, precompute positions — never in
`render`), `render(...)`, `tick()`, `onClose()`, `removed()`. Add widgets with
`addRenderableWidget` (rendered + interactive + narrated), `addWidget`, or
`addRenderableOnly`. Container screens: `containerTick()`, `renderBg(...)`,
`renderLabels(...)` — inside `renderLabels` the pose is **already translated**
by `leftPos/topPos`; do not add them again.

**Text-truncation policy** (from the Mayor footer, hard-won): *names*
ellipsise inside measured boxes; *sentences* word-wrap into height reserved
unconditionally — an ellipsised sentence reads as a different, shorter
sentence, and a panel's shape must never depend on the data.

## 10. The traps, each of which has cost someone a client boot

1. **Thin geometry is measured in screen pixels, not intent.** Ruled lines at
   0.9 font units were present in code and invisible in game. Under ~2px, a
   line is a rumour. *(This repo, PLAQUE-2.)*
2. **Do not guess text width — measure it.** English fits, Norwegian
   overflows; `æøå` come from a different, taller glyph sheet than ASCII.
3. **`✓`/`✗` (U+2713/2717) are unifont-only and render as boxes.** Use
   **`✔`/`✘` (U+2714/2718)** from `nonlatin_european.png` (verified).
4. **Items in UI use `ItemDisplayContext.GUI`.** `FIXED` is the item-frame
   pose and collapses two-block models — a bed becomes a strip of planks.
5. **A missing sprite is a magenta checkerboard, not an exception** — the
   path has the folder prefix or a `.png` it should not have.
6. **Reset the blit offset / pose** — push/pop around anything translating,
   or later elements land in the wrong layer.
7. **`enableScissor` takes screen coordinates**, not your panel's — wrong in
   a way that only shows at non-default GUI scale.
8. **Relativize every coordinate to `width`/`height`**, verified by cycling
   GUI Scale 1–4.
9. **A disabled button that looks enabled is a lie** — four states (§5) or
   the screen feels dead under the mouse.
10. **Hit targets under 10px are misses at GUI scale 1** — pad the clickable
    region beyond the drawn glyph if the art must stay small.

## 11. Critique checklist — run it on every screenshot or screen class
Every item is measurable; a miss is a defect, not a taste difference.

1. **Grid:** every sibling gap divisible by 4; panel-edge padding = 8; any
   two gutters in a column equal.
2. **Fonts:** ≤2 text colours per panel at rest (`TEXT_STRONG` + one of
   `TEXT`/`TEXT_MUTED`); tone colours only on stateful values. 3+ = fail.
3. **One primary action**, and it is the only accent-edged control at rest.
4. **Contrast:** body ≥4.5:1 vs its *local* ground; no `TEXT_MUTED` body
   copy on bare oak; no `BAD` text on dark, ever (§6 table).
5. **Alignment drift:** label left edges identical down a column, numbers
   right-aligned — zoom the preview PNG to 300% and look for 1px steps.
6. **Every string measured** — preview `--strict` green for en_us AND nb_no.
7. **Hover on everything interactive; tooltip-why on everything disabled.**
8. **Divider budget:** ≤3 per 200px of height, inset PAD, none within 12px.
9. **No orphans:** no label without its value/control in the same row; no
   icon without a tooltip; no unit-less number.
10. **Data shape → control** mapping honoured (§4 table) — no raw "73/100"
    where a bar belongs, no bar where an exact count matters.
11. **Names ellipsise, sentences wrap** into pre-reserved height.
12. **≤1 blinking element**, 400ms, both phases ≥3:1 contrast.
13. **Scale sweep:** GUI scale 1–4 — nothing clips, scissors still correct.
14. **Squint test** (scale the screenshot to 25%): primary action, title and
    every alert still findable — if not, hierarchy, not decoration, is what
    is missing.

## 12. Failure smells from shipped colony-sim UIs

- **The wall of names and numbers** (MineColonies hire tab) — no pips, no
  portraits, no at-a-glance state; every question costs a read.
- **Tab explosion** — a seventh tab instead of an information hierarchy.
- **Alert spam** — undifferentiated warnings until players ignore them all.
- **Monitoring hidden behind menus** (anti-RimWorld) — mood/health/job only
  visible after two clicks per citizen.
- **Modal stacking / scroll-in-scroll** — a popout needing its own popout
  means the first popout is the wrong container.
- **Mystery-meat icons** — no tooltip, no established convention behind them.
- **Colour as the only channel** — pass/fail readable only by hue.
- **Layout at the mercy of data** — one long name reflows the panel, instead
  of measured, reserved boxes (§9 policy).
- **Placeholder controls** — forbidden outright by D-014 (§8b).

## 13. Why this repository has no UI library

The good ones — **owo-lib**, **LibGui**, **YACL**, **Cloth Config** — are
Fabric-first, and the config libraries are built for config screens, not
game UI. This mod is NeoForge (D-002) with no third-party runtime
dependencies by policy, so we take the *ideas* — declarative composition,
layout containers, a token system — and build them on vanilla `GuiGraphics`;
`HsUi.Column` is that idea at the size this project actually needs.

## 14. One source of truth for numbers

`tools/ui/tokens.json` holds every colour and metric. `tools/gen_ui.py`
emits both the sprites **and** `HsUiTokens.java` from it; the preview reads
the same file; `validate_assets.py` runs the generator twice under different
`PYTHONHASHSEED`s and fails on any differing byte or stale commit. A preview
drawn from different numbers than the game lies — and a lying preview is
worse than none, because you would trust it.

## Sources

- [NeoForge — Screens](https://docs.neoforged.net/docs/1.20.6/gui/screens/) · [GuiGraphics javadoc 1.21.1](https://lexxie.dev/neoforge/1.21.1/net/minecraft/client/gui/GuiGraphics.html) · [Minecraft Wiki — GUI sprite scaling](https://minecraft.wiki/w/Resource_pack)
- [Game UI Database](https://www.gameuidatabase.com/) · [Interface In Game](https://interfaceingame.com/) — the reference galleries; check both before inventing a pattern.
- [Game Accessibility Guidelines — contrast](https://gameaccessibilityguidelines.com/provide-high-contrast-between-text-ui-and-background/) · [Xbox Accessibility Guideline 102](https://learn.microsoft.com/en-us/gaming/accessibility/xbox-accessibility-guidelines/102) (4.5:1 body, 3:1 inactive/large/non-text, 7:1 high-contrast mode)
- [RimWorld's at-a-glance UI — Moonlit Development](https://www.moonlitdevelopment.com/development-blog/2017/12/25/the-designers-folder-rimworlds-at-a-glance-user-interface-elements)
- [Baymard — line length](https://baymard.com/blog/line-length-readability) · [UXPin — the 50–75 character rule](https://www.uxpin.com/studio/blog/optimal-line-length-for-readability/)
- [Color-Coded Item Tiers — TV Tropes](https://tvtropes.org/pmwiki/pmwiki.php/Main/ColorCodedItemTiers) · [Claire Fishman — color theory & item quality](https://medium.com/@ClaireFish/how-color-theory-codifies-item-quality-in-video-games-104d8118044)
- [SFX Engine — game UI sounds](https://sfxengine.com/blog/best-practices-for-game-ui-sounds) · [BetterLink — game feedback & feel](https://eastondev.com/blog/en/posts/dev/20260521-game-feedback-feel/)
- [Pixnote — outlines & sel-out](https://pixnote.net/en/learn/outlines/) · [Creative Freedom — 16×16 icons](https://creativefreedom.co.uk/icon-designers-blog/designing-small-icons-in-photoshop/) · [RocketBrush — HUD best practices](https://rocketbrush.com/blog/designing-practical-and-pretty-hud-in-video-games) · [the minimal-HUD paradox](https://medium.com/@salamatizm/the-minimal-hud-paradox-how-dreams-of-diegetic-game-interfaces-often-lead-to-cluttered-nightmares-e9cf7fae9d73)
- Verified in-repo: `tools/gen_ui.py`, `tools/texlib.py`, `tools/mcfont.py`,
  `tools/ui_preview.py`, `tools/ui/tokens.json`, `client/ui/HsUi.java`,
  `client/screen/HearthScreen.java`. Contrast ratios computed (WCAG 2.x
  relative luminance) against this repository's actual token values, 2026-08-25.
