# UI frame cost — the measurements behind the 2026-08-29 UI pass

Everything here was measured on a real Minecraft 1.21.1 client (NeoForge
21.1.248) running under Xvfb with Mesa `llvmpipe`, driven by
`qa/scripts/live.sh`. The instrument is `client/debug/UiProfiler`, which times
`ScreenEvent.Render` Pre→Post on the render thread and reports wall time,
per-frame allocation and Minecraft's own FPS counter every 120 frames.

**Read the relative numbers, not the absolute ones.** A software rasteriser is
not a GPU. What transfers is the ratio and, more importantly, *where the time
goes* — which is the same on any hardware.

## The scene

One dedicated server, one flat world, the settlement `hearthstead:hearth`
placed at 115/64/115, the player's inventory filled (35 item stacks, so the
container screens pay vanilla's real per-item render cost), `guiScale` as
noted. Identical before and after.

## Hearth screen (a container screen — never paid for the blur)

| | mean ms | p50 | p95 | alloc KB/frame | fps |
|---|---|---|---|---|---|
| parchment ledger, original sprites | 4.76 | 4.52 | 6.17 | 942 | 28 |
| parchment ledger, sprite geometry fixed | 2.87 | 2.74 | 3.49 | 510 | 31–34 |
| command centre (final), guiScale 3 | 3.55–4.06 | 3.43–3.72 | 4.55–5.96 | 526–542 | 27–29 |
| command centre (final), guiScale 2 | 1.28–1.39 | 1.21–1.27 | 1.67–2.00 | 224–226 | 33–36 |

The middle row is the sprite fix alone, on unchanged screen code, and the
screenshots either side of it are pixel-identical. The final row is a screen
that draws far more than the parchment one did — icons, a live morale bar, a
wrapped status strip, sixty slot sockets it used to get free from a baked
background image — for 44% less garbage per frame than the original.

## Settler sheet (a plain Screen — paid for the blur every frame)

| | mean ms | p50 | p95 | alloc KB/frame | fps |
|---|---|---|---|---|---|
| before | 117.08 | 116.07 | 125.42 | 662 | 6 |
| before, entity preview off | 118.17 | 115.99 | 137.86 | 612 | 6 |
| before, ENTIRE panel body skipped | 110.20 | 108.61 | 118.32 | 287 | 6–7 |
| after (scrim instead of blur), guiScale 3 | 3.98 | 3.86 | 4.99 | 550 | 26 |
| after, guiScale 4 / 320x256 (narrow layout) | 4.62–4.68 | 4.39–4.42 | 6.06–6.37 | 582–633 | 18–19 |

The third row is the whole finding. With every sprite, bar, card, divider and
string this mod draws removed, the frame still cost 110ms — so ~7ms was
Hearthstead and ~110ms was `Screen#renderBackground`'s full-screen blur.

## The control: untouched vanilla screens, same scene, same run

| screen | mean ms | fps |
|---|---|---|
| `ConfirmLinkScreen` (vanilla, plain Screen) | 113.4–116.4 | 6 |
| `PauseScreen` (vanilla, plain Screen) | 112.5–116.4 | 6–7 |
| `CreativeModeInventoryScreen` (vanilla, container) | 3.76 | 22 |

Vanilla's own plain Screens still measure ~115ms in the very same scene where
Hearthstead's now measure ~4. That is the cleanest confirmation available that
the cost was the inherited background and not this mod's drawing — and that
vanilla container screens, which skip the blur, were never affected.

## Reproducing

    HSQA_UIPROFILE=1 bash qa/scripts/live.sh start
    # open a screen, leave it open ~10s per measurement window
    python3 qa/scripts/uiprofile_report.py <artifact>/logs/live-client.log

On a real machine (including the owner's), add `-Dhearthstead.uiprofile=true`
to the launcher's JVM arguments and run `uiprofile_report.py` against
`logs/latest.log`; pass two logs to diff them.

## What the numbers could not have told us

Two defects in this pass were found by pressing the button, not by measuring:
the Mayor tab drew its panel *underneath* the hearth's own labels and item
stacks, ran its footer off the bottom of a 240px viewport, and covered the tab
strip it was opened from — leaving Escape, which closes everything, as the
only way out. Every suite was green through all three.

`shots/before-mayor-tab-bleeding.png` is what that looked like;
`shots/after-mayor-modal.png` is the same tab afterwards. It is the reason the
protocol says a green test is not visual quality.

## Screenshots kept here

| file | what it shows |
|---|---|
| `before-hearth-parchment.png` | the legacy ledger: clipped tabs, "Communal Stores" across the title bar, the status sentence running out of the window |
| `after-hearth-command-centre.png` | the same screen rebuilt, guiScale 3 |
| `after-hearth-scale2.png` | guiScale 2 |
| `after-hearth-tooltip.png` | a stat row's tiered tooltip |
| `before-settler-twocolumn-slow.png` | the two-column sheet before the backdrop fix (117ms/frame) |
| `after-settler-scrim.png` | the same sheet at 3.98ms |
| `after-settler-narrow-scale4.png` | the single-column fallback, guiScale 4, 320x256 |
| `before-mayor-tab-bleeding.png` | the seat panel drawn under the screen it belongs to |
| `after-mayor-modal.png` | the seat panel as a modal that fits, with its way back |
