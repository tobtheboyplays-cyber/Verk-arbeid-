#!/usr/bin/env python3
"""The Hearthstead UI kit: nine-slice sprites that scale to any panel size.

Why this exists
---------------
The screens in this mod were drawn two ways, and both are dead ends. Some blit
a whole 256x256 PNG, which fixes the panel at one size forever -- change the
layout and you regenerate the art. Others stack `graphics.fill` rectangles,
which costs no art but always looks like a rectangle stack.

Vanilla's own answer since 1.20.2 is the GUI sprite atlas: a small sprite in
textures/gui/sprites/ plus a .mcmeta that says how it scales. A nine-slice
sprite has fixed corners, tiling edges and a tiling middle, so ONE 18x18 image
is every window from a tooltip to a full screen.

The one rule that makes nine-slice work
---------------------------------------
The edges TILE (stretch_inner defaults to false). So a top edge whose pixels
vary along x will visibly repeat every few pixels. The fix is not to be careful
-- it is to make variation along the tiling axis impossible:

    paint every frame pixel as a function of its DISTANCE TO THE NEAREST EDGE.

A pixel on the top edge then depends only on y, so the top edge is constant
along x and tiles invisibly; the same profile mitres the corners for free. All
per-corner detail (rivets, plates) is drawn inside the fixed corner squares,
which never tile. Directional light survives this, because which edge is
nearest is itself constant along an edge.

Everything here is deterministic: fixed integer seeds, never hash(). That is
enforced, not asserted -- gen_ui.py is in validate_assets.py's
PIPELINE_GENERATORS, which runs it twice under different PYTHONHASHSEEDs and
fails the build if a single output byte differs, or if what is committed does
not match a fresh run.
"""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from texlib import ramp, shade, mix, new_image, fill, put, save  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "src/main/resources/assets/hearthstead")
SPRITES = os.path.join(ASSETS, "textures/gui/sprites")
TOKENS_JSON = os.path.join(HERE, "ui", "tokens.json")
TOKENS_JAVA = os.path.join(
    HERE, "..", "src/main/java/com/hearthstead/client/ui/HsUiTokens.java")

SEED = 730114  # explicit constant: hash() is salted per process (KF-007)

OAK = ramp("oak_carved")
BRASS = ramp("brass")
IRON = ramp("iron_forged")
COAL = ramp("charcoal")
BONE = ramp("bone")
GREEN = ramp("emerald")
RED = ramp("crimson")
AMBER = ramp("amber")

# --------------------------------------------------------------- tokens ---
# The single source of truth for every number both the game and the preview
# need. Python builds the sprites from it, Java reads it as constants, and the
# preview renders from the same file -- so a preview cannot flatter a layout
# the game will draw differently.
TOKENS = {
    "colour": {
        "text":          0xFFE8E0D0,
        "text_muted":    0xFF8A8578,
        "text_strong":   0xFFF2ECDC,
        "text_on_light": 0xFF241A0E,
        "accent":        0xFFB8912F,
        "good":          0xFF5FA860,
        "warn":          0xFFC98A2E,
        "bad":           0xFF8A3A35,
        "field":         0xFF1A1A1A,
        "row_odd":       0xFF212121,
        "shadow":        0x66000000,
    },
    # Spacing is a 4px grid. Every gutter, pad and row height below is a
    # multiple of 4 (or 2 where 4 would waste a cramped panel), because
    # inconsistent gutters are the single most common reason a modded screen
    # reads as amateur next to vanilla.
    "metric": {
        "grid":         4,
        "pad":          8,
        "gutter":       4,
        "row_h":        20,
        "card_h":       36,
        "button_h":     20,
        "title_h":      16,
        "divider_h":    2,
        "scroll_w":     6,
        "slot":         18,
        "text_h":       8,
        "line_gap":     11,
    },
}


def rng():
    return random.Random(SEED)


# ------------------------------------------------------------- nine-slice ---

def frame(size, border, bands, centre, corner_plate=None, r=None):
    """A tile-safe frame sprite.

    `bands[d]` is the colour at depth d from the nearest edge, as a
    (top_left, bottom_right) pair so the light can come from the top-left the
    way the rest of this mod's art does. Depths at or past `border` are the
    centre colour.
    """
    img = new_image(size, size)
    for y in range(size):
        for x in range(size):
            top = min(x, y)
            bottom = min(size - 1 - x, size - 1 - y)
            depth = min(x, y, size - 1 - x, size - 1 - y)
            if depth >= border:
                put(img, x, y, centre)
                continue
            lit, dim = bands[depth]
            put(img, x, y, lit if top <= bottom else dim)
    if corner_plate:
        for cx, cy in ((0, 0), (size - border, 0),
                       (0, size - border), (size - border, size - border)):
            corner_plate(img, cx, cy, border, r or rng())
    return img


def rivet(img, x, y, border, r):
    """A brass stud in a corner square. Corners never tile, so detail is safe."""
    cx, cy = x + border // 2, y + border // 2
    put(img, cx, cy, BRASS[4])
    for dx, dy in ((1, 0), (0, 1), (-1, 0), (0, -1)):
        put(img, cx + dx, cy + dy, BRASS[2])
    put(img, cx - 1, cy - 1, BRASS[4])
    put(img, cx + 1, cy + 1, shade(BRASS[0], 0.8))


def mcmeta(name, kind, border=None, width=None, height=None,
           stretch_inner=False):
    meta = {"gui": {"scaling": {"type": kind}}}
    scaling = meta["gui"]["scaling"]
    if kind in ("tile", "nine_slice"):
        scaling["width"] = width
        scaling["height"] = height
    if kind == "nine_slice":
        scaling["border"] = border
        if stretch_inner:
            scaling["stretch_inner"] = True
    path = os.path.join(SPRITES, name + ".png.mcmeta")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=2, sort_keys=True)
        fh.write("\n")


def emit(name, img, kind="nine_slice", border=None, stretch_inner=False):
    path = os.path.join(SPRITES, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    save(img, path)
    mcmeta(name, kind, border=border, width=img.width, height=img.height,
           stretch_inner=stretch_inner)


# ----------------------------------------------------------------- panels ---

def window():
    """The main screen frame: dark outline, carved oak, a brass inner rule."""
    bands = [
        (shade(OAK[0], 0.55), shade(OAK[0], 0.45)),   # 0 outline
        (OAK[3], OAK[1]),                             # 1 lit chamfer
        (OAK[2], OAK[1]),                             # 2 board
        (OAK[2], OAK[0]),                             # 3 board
        (OAK[1], OAK[0]),                             # 4 board, into shadow
        (BRASS[1], BRASS[0]),                         # 5 brass rule, dim
        (BRASS[3], BRASS[2]),                         # 6 brass rule, catch
    ]
    return frame(18, 7, bands, COAL[1], corner_plate=rivet)


def inset():
    """A recessed field: light lip at the bottom-right, shadow at the top-left,
    which is the inverse of `window` and is what makes it read as sunken."""
    bands = [
        (shade(COAL[0], 0.6), IRON[2]),
        (COAL[0], IRON[1]),
        (COAL[1], COAL[2]),
    ]
    return frame(8, 3, bands, COAL[1])


def card(hover=False):
    base = COAL[3] if hover else COAL[2]
    edge = BRASS[1] if hover else IRON[1]
    bands = [
        (shade(edge, 1.15), shade(edge, 0.7)),
        (mix(base, edge, 0.35), shade(base, 0.85)),
        (shade(base, 1.08), shade(base, 0.94)),
        (base, base),
    ]
    return frame(12, 4, bands, base)


# ---------------------------------------------------------------- widgets ---

def button(state):
    """idle / hover / pressed / disabled / danger / danger_hover."""
    if state.startswith("danger"):
        body = RED[1] if "hover" not in state else RED[2]
        edge = RED[3] if "hover" in state else RED[0]
    elif state == "disabled":
        body, edge = shade(IRON[1], 0.9), IRON[0]
    elif state == "hover":
        body, edge = OAK[3], BRASS[3]
    elif state == "pressed":
        body, edge = OAK[1], BRASS[1]
    else:
        body, edge = OAK[2], BRASS[1]
    if state == "pressed":
        bands = [(shade(edge, 0.7), edge),
                 (shade(body, 0.8), shade(body, 1.05)),
                 (body, body)]
    else:
        bands = [(shade(edge, 1.1), shade(edge, 0.65)),
                 (shade(body, 1.18), shade(body, 0.82)),
                 (body, body)]
    return frame(10, 3, bands, body)


def tab(selected):
    if selected:
        bands = [(BRASS[2], BRASS[0]), (OAK[3], OAK[1]), (OAK[2], OAK[2])]
        centre = OAK[2]
    else:
        bands = [(IRON[1], IRON[0]), (COAL[3], COAL[1]), (COAL[2], COAL[2])]
        centre = COAL[2]
    return frame(8, 3, bands, centre)


def scroll_track():
    bands = [(shade(COAL[0], 0.7), COAL[2]), (COAL[0], COAL[1])]
    return frame(6, 2, bands, COAL[0])


def scroll_thumb(hover):
    top = BRASS[3] if hover else IRON[3]
    body = BRASS[1] if hover else IRON[2]
    bands = [(top, shade(body, 0.7)), (body, shade(body, 0.85))]
    return frame(6, 2, bands, body)


def slot():
    """An 18x18 item slot, vanilla's own size so items sit at 16x16 with a
    one-pixel lip -- deviating from 18 is the fastest way to look wrong."""
    bands = [(shade(COAL[0], 0.5), IRON[2]), (COAL[0], IRON[0])]
    return frame(18, 2, bands, COAL[2])


def divider():
    """A ruled line: one dark pixel, one catch-light. Tiles along x."""
    img = new_image(2, 2)
    for x in range(2):
        put(img, x, 0, shade(OAK[0], 0.7))
        put(img, x, 1, mix(OAK[3], BRASS[0], 0.4))
    return img


def pip(filled, tone="accent"):
    """A 5x5 skill/rating pip. Pips beat printed integers: you read four of
    five at a glance and never read '4' at a glance."""
    ramp_for = {"accent": BRASS, "good": GREEN, "bad": RED, "warn": AMBER}[tone]
    img = new_image(5, 5)
    core = ramp_for[3] if filled else COAL[3]
    rim = ramp_for[1] if filled else COAL[0]
    for y in range(5):
        for x in range(5):
            d = abs(x - 2) + abs(y - 2)
            if d > 2:
                continue
            put(img, x, y, core if d <= 1 else rim)
    if filled:
        put(img, 1, 1, ramp_for[4])
    return img


def bar(kind):
    """Need/progress bars. `track` is the groove, `fill` the contents."""
    if kind == "track":
        bands = [(shade(COAL[0], 0.6), IRON[1]), (COAL[0], COAL[1])]
        return frame(6, 2, bands, COAL[0])
    tone = {"fill_good": GREEN, "fill_warn": AMBER, "fill_bad": RED}[kind]
    bands = [(tone[4], tone[1]), (tone[3], tone[2])]
    return frame(6, 2, bands, tone[3])


# ------------------------------------------------------------------ emit ----

SPRITE_SET = []


def build():
    r = rng()
    del r  # every sprite here is deterministic without noise; kept for parity
    emit("panel/window", window(), border=7)
    emit("panel/inset", inset(), border=3)
    emit("panel/card", card(False), border=4)
    emit("panel/card_hover", card(True), border=4)
    for state in ("idle", "hover", "pressed", "disabled",
                  "danger", "danger_hover"):
        emit("widget/button_" + state, button(state), border=3)
    emit("widget/tab_selected", tab(True), border=3)
    emit("widget/tab_unselected", tab(False), border=3)
    emit("widget/scroll_track", scroll_track(), border=2)
    emit("widget/scroll_thumb", scroll_thumb(False), border=2)
    emit("widget/scroll_thumb_hover", scroll_thumb(True), border=2)
    emit("widget/slot", slot(), border=2)
    emit("widget/divider", divider(), kind="tile")
    for tone in ("accent", "good", "bad", "warn"):
        emit(f"widget/pip_{tone}", pip(True, tone), kind="stretch")
    emit("widget/pip_empty", pip(False), kind="stretch")
    emit("bar/track", bar("track"), border=2)
    for tone in ("good", "warn", "bad"):
        emit(f"bar/fill_{tone}", bar("fill_" + tone), border=2)


def write_tokens():
    os.makedirs(os.path.dirname(TOKENS_JSON), exist_ok=True)
    with open(TOKENS_JSON, "w", encoding="utf-8") as fh:
        json.dump(TOKENS, fh, indent=2, sort_keys=True)
        fh.write("\n")

    lines = [
        "package com.hearthstead.client.ui;",
        "",
        "/**",
        " * GENERATED by tools/gen_ui.py -- do not edit by hand.",
        " *",
        " * <p>The design tokens the screens and the offline preview share. One",
        " * source, two consumers: a preview that renders from different numbers",
        " * than the game is a preview that lies.",
        " */",
        "public final class HsUiTokens {",
        "",
    ]
    for name, value in sorted(TOKENS["colour"].items()):
        lines.append(f"    public static final int {name.upper()} "
                     f"= 0x{value:08X};")
    lines.append("")
    for name, value in sorted(TOKENS["metric"].items()):
        lines.append(f"    public static final int {name.upper()} = {value};")
    lines += ["", "    private HsUiTokens() {", "    }", "}", ""]
    os.makedirs(os.path.dirname(TOKENS_JAVA), exist_ok=True)
    with open(TOKENS_JAVA, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def main():
    build()
    write_tokens()
    count = sum(1 for _, _, fs in os.walk(SPRITES) for f in fs
                if f.endswith(".png"))
    print(f"gen_ui: {count} sprites -> {os.path.relpath(SPRITES, HERE)}")
    print(f"gen_ui: tokens -> ui/tokens.json + HsUiTokens.java")


if __name__ == "__main__":
    main()
