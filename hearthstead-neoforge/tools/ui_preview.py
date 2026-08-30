#!/usr/bin/env python3
"""Render a screen layout to a PNG without booting Minecraft.

Booting the client to look at a panel costs minutes; this costs a second, so
composition gets iterated instead of guessed. It is honest about three things
that a hand-drawn mockup is not:

  * it draws the SAME sprites the game will draw, through the same nine-slice
    and tiling rules vanilla uses;
  * it measures text with the REAL vanilla font (tools/mcfont.py), so a label
    that fits here fits in game, in English and in Norwegian;
  * it FAILS on overflow. Any text wider than the box it was given is marked in
    the image and reported, and --strict makes that a non-zero exit.

It is not a replacement for looking at the running game -- see the QA protocol
-- it is what you use before you get there.

    python3 tools/ui_preview.py tools/ui/screens/plaque_hire.json -o /tmp/a.png
    python3 tools/ui_preview.py ... --scale 3 --guides --strict
    python3 tools/ui_preview.py --all           # every screen in tools/ui/screens
"""
import argparse
import functools
import io
import json
import glob
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw  # noqa: E402
import mcfont  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SPRITES = os.path.join(HERE, "..",
                       "src/main/resources/assets/hearthstead/textures/gui/sprites")
TEXTURES = os.path.abspath(os.path.join(
    HERE, "..", "src/main/resources/assets/hearthstead/textures"))
SCREENS = os.path.join(HERE, "ui", "screens")
TOKENS = json.load(open(os.path.join(HERE, "ui", "tokens.json")))

WARNINGS = []
_VANILLA_ICON_CACHE = {}


@functools.lru_cache(maxsize=1)
def vanilla_client_jar():
    """Locate the current NeoForge-generated client asset jar for previews."""
    root = os.path.join(os.path.expanduser("~"), ".gradle", "caches",
                        "neoformruntime", "artifacts")
    candidates = sorted(glob.glob(os.path.join(root, "minecraft_*_client.jar")))
    if not candidates:
        raise SystemExit(f"no NeoForge Minecraft client jar found below {root}")
    return max(candidates, key=os.path.getmtime)


def argb(value):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF,
            (value >> 24) & 0xFF)


def colour(name):
    if isinstance(name, int):
        return argb(name)
    if isinstance(name, str) and name.startswith("#"):
        return argb(int(name[1:], 16))
    return argb(TOKENS["colour"][name])


def rgba(name, alpha):
    """Return a semantic colour with an explicit preview alpha."""
    red, green, blue, _ = colour(name)
    return red, green, blue, max(0, min(255, int(alpha)))


# ------------------------------------------------------------- sprite kit ---

class Sprites:
    """Vanilla's GUI sprite atlas rules, reimplemented faithfully.

    The three scaling modes are exactly the ones a .mcmeta may ask for. The
    part worth being careful about is nine_slice with stretch_inner false --
    vanilla TILES the edges and the middle, which is why the generator paints
    frames as a function of edge distance. If this tiled where vanilla
    stretched, every preview would be subtly wrong.
    """

    def __init__(self, root):
        self.root = root
        self._cache = {}

    def get(self, name):
        if name not in self._cache:
            png = os.path.join(self.root, name + ".png")
            if not os.path.exists(png):
                raise SystemExit(f"no such sprite: {name} ({png})")
            img = Image.open(png).convert("RGBA")
            meta_path = png + ".mcmeta"
            meta = {"gui": {"scaling": {"type": "stretch"}}}
            if os.path.exists(meta_path):
                meta = json.load(open(meta_path))
            self._cache[name] = (img, meta["gui"]["scaling"])
        return self._cache[name]

    def draw(self, dst, name, x, y, w, h):
        img, scaling = self.get(name)
        kind = scaling.get("type", "stretch")
        if w <= 0 or h <= 0:
            return
        if kind == "stretch":
            dst.alpha_composite(img.resize((w, h), Image.NEAREST), (x, y))
        elif kind == "tile":
            self._tile(dst, img, x, y, w, h,
                       scaling.get("width", img.width),
                       scaling.get("height", img.height))
        elif kind == "nine_slice":
            self._nine(dst, img, scaling, x, y, w, h)
        else:
            raise SystemExit(f"unknown sprite scaling {kind!r} on {name}")

    def _tile(self, dst, img, x, y, w, h, tw, th):
        src = img if (tw, th) == img.size else img.resize((tw, th), Image.NEAREST)
        for oy in range(0, h, th):
            for ox in range(0, w, tw):
                part = src.crop((0, 0, min(tw, w - ox), min(th, h - oy)))
                dst.alpha_composite(part, (x + ox, y + oy))

    def _nine(self, dst, img, scaling, x, y, w, h):
        border = scaling["border"]
        if isinstance(border, int):
            l = t = r = b = border
        else:
            l, t = border.get("left", 0), border.get("top", 0)
            r, bt = border.get("right", 0), border.get("bottom", 0)
            b = bt
        sw, sh = scaling.get("width", img.width), scaling.get("height", img.height)
        src = img if (sw, sh) == img.size else img.resize((sw, sh), Image.NEAREST)
        stretch_inner = scaling.get("stretch_inner", False)
        # A panel narrower than its own borders is a layout bug, not something
        # to paper over: vanilla would overlap the corners and so do we, but we
        # say so.
        if w < l + r or h < t + b:
            WARNINGS.append(f"panel {w}x{h} is smaller than its {l+r}x{t+b} borders")
        mid_w, mid_h = max(0, w - l - r), max(0, h - t - b)
        s_mid_w, s_mid_h = max(1, sw - l - r), max(1, sh - t - b)

        def piece(box):
            return src.crop(box)

        def put(part, px, py, pw, ph):
            if pw <= 0 or ph <= 0:
                return
            if stretch_inner:
                dst.alpha_composite(part.resize((pw, ph), Image.NEAREST), (px, py))
            else:
                self._tile(dst, part, px, py, pw, ph, part.width, part.height)

        # corners are always 1:1 -- this is the whole point of nine-slice
        dst.alpha_composite(piece((0, 0, l, t)), (x, y))
        dst.alpha_composite(piece((sw - r, 0, sw, t)), (x + w - r, y))
        dst.alpha_composite(piece((0, sh - b, l, sh)), (x, y + h - b))
        dst.alpha_composite(piece((sw - r, sh - b, sw, sh)), (x + w - r, y + h - b))
        put(piece((l, 0, sw - r, t)), x + l, y, mid_w, t)
        put(piece((l, sh - b, sw - r, sh)), x + l, y + h - b, mid_w, b)
        put(piece((0, t, l, sh - b)), x, y + t, l, mid_h)
        put(piece((sw - r, t, sw, sh - b)), x + w - r, y + t, r, mid_h)
        put(piece((l, t, sw - r, sh - b)), x + l, y + t, mid_w, mid_h)


# ---------------------------------------------------------------- elements ---

class Canvas:
    def __init__(self, w, h, font, sprites):
        self.img = Image.new("RGBA", (w, h), argb(0xFF101010))
        self.font = font
        self.sprites = sprites

    def rect(self, x, y, w, h, col):
        if w <= 0 or h <= 0:
            return
        self.img.alpha_composite(Image.new("RGBA", (w, h), col), (x, y))

    def stroke(self, x, y, w, h, col, width=1):
        for offset in range(max(1, width)):
            self.rect(x + offset, y + offset, w - 2 * offset, 1, col)
            self.rect(x + offset, y + h - 1 - offset,
                      w - 2 * offset, 1, col)
            self.rect(x + offset, y + offset, 1, h - 2 * offset, col)
            self.rect(x + w - 1 - offset, y + offset,
                      1, h - 2 * offset, col)

    def _draw_overlay(self, callback):
        overlay = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        callback(ImageDraw.Draw(overlay))
        self.img.alpha_composite(overlay)

    def ellipse(self, x, y, w, h, fill, outline=None, width=1):
        if w <= 0 or h <= 0:
            return
        self._draw_overlay(lambda draw: draw.ellipse(
            (x, y, x + w - 1, y + h - 1), fill=fill,
            outline=outline, width=max(1, width)))

    def line(self, points, fill, width=1):
        if len(points) < 2:
            return
        self._draw_overlay(lambda draw: draw.line(
            points, fill=fill, width=max(1, width), joint="curve"))

    def polygon(self, points, fill, outline=None):
        if len(points) < 3:
            return
        self._draw_overlay(lambda draw: draw.polygon(
            points, fill=fill, outline=outline))

    def arc(self, x, y, w, h, start, end, fill, width=1):
        if w <= 0 or h <= 0:
            return
        self._draw_overlay(lambda draw: draw.arc(
            (x, y, x + w - 1, y + h - 1), start=start, end=end,
            fill=fill, width=max(1, width)))

    def icon(self, asset, x, y, size, anchor="top_left"):
        relative = os.path.normpath(asset).replace("\\", "/")
        source = os.path.abspath(os.path.join(TEXTURES, relative))
        if os.path.commonpath((TEXTURES, source)) != TEXTURES:
            raise SystemExit(f"icon escapes Hearthstead textures: {asset!r}")
        if not os.path.isfile(source):
            raise SystemExit(f"no such Hearthstead texture: {asset!r} ({source})")
        icon = Image.open(source).convert("RGBA")
        icon.thumbnail((size, size), Image.Resampling.NEAREST)
        if anchor == "center":
            x -= icon.width // 2
            y -= icon.height // 2
        self.img.alpha_composite(icon, (x, y))

    def texture_crop(self, asset, crop, x, y, w, h, anchor="top_left"):
        """Nearest-neighbour crop for readable faces/details from local textures."""
        relative = os.path.normpath(asset).replace("\\", "/")
        source = os.path.abspath(os.path.join(TEXTURES, relative))
        if os.path.commonpath((TEXTURES, source)) != TEXTURES:
            raise SystemExit(f"texture crop escapes Hearthstead textures: {asset!r}")
        if not os.path.isfile(source):
            raise SystemExit(f"no such Hearthstead texture: {asset!r}")
        if len(crop) != 4 or crop[2] <= crop[0] or crop[3] <= crop[1]:
            raise SystemExit(f"invalid texture crop for {asset!r}: {crop!r}")
        with Image.open(source).convert("RGBA") as texture:
            if crop[0] < 0 or crop[1] < 0 or crop[2] > texture.width \
                    or crop[3] > texture.height:
                raise SystemExit(
                    f"texture crop outside {texture.width}x{texture.height}: {crop!r}")
            detail = texture.crop(tuple(crop)).resize(
                (w, h), Image.Resampling.NEAREST)
        if anchor == "center":
            x -= detail.width // 2
            y -= detail.height // 2
        self.img.alpha_composite(detail, (x, y))

    def vanilla_icon(self, resource, x, y, size, anchor="top_left"):
        normalized = resource.replace("\\", "/").lstrip("/")
        if normalized.startswith("../") or "/../" in normalized:
            raise SystemExit(f"vanilla icon escapes asset root: {resource!r}")
        archive_path = "assets/minecraft/" + normalized
        cache_key = (vanilla_client_jar(), archive_path)
        icon = _VANILLA_ICON_CACHE.get(cache_key)
        if icon is None:
            try:
                with zipfile.ZipFile(cache_key[0]) as archive:
                    icon = Image.open(io.BytesIO(archive.read(archive_path))).convert("RGBA")
            except (KeyError, OSError, zipfile.BadZipFile) as exc:
                raise SystemExit(
                    f"no such vanilla texture: {resource!r} in {cache_key[0]}") from exc
            _VANILLA_ICON_CACHE[cache_key] = icon
        rendered = icon.copy()
        rendered.thumbnail((size, size), Image.Resampling.NEAREST)
        if anchor == "center":
            x -= rendered.width // 2
            y -= rendered.height // 2
        self.img.alpha_composite(rendered, (x, y))

    def text(self, x, y, s, col, shadow=True, align="left", box=None,
             label=""):
        width = self.font.width(s)
        if box is not None and width > box:
            WARNINGS.append(
                f'{label or "text"}: "{s}" is {width}px in a {box}px box '
                f"(overflows by {width - box})")
            self.rect(x + box, y - 1, 2, 10, argb(0xFFFF0044))
        if align == "center":
            x -= width // 2
        elif align == "right":
            x -= width
        self.font.draw(self.img, x, y, s, col, shadow=shadow)
        return width

    def paragraph(self, x, y, text, box, max_lines, col, line_gap=11,
                  label="paragraph"):
        words = text.split()
        lines = []
        current = ""
        for word in words:
            candidate = word if not current else current + " " + word
            if self.font.width(candidate) <= box:
                current = candidate
            else:
                if current:
                    lines.append(current)
                current = word
        if current:
            lines.append(current)
        if len(lines) > max_lines:
            WARNINGS.append(
                f'{label}: "{text}" needs {len(lines)} lines, has {max_lines}')
            lines = lines[:max_lines]
        for index, line in enumerate(lines):
            self.text(x, y + index * line_gap, line, col, box=box,
                      label=f"{label} line {index + 1}")
        return len(lines)


def render(spec, scale=1, guides=False):
    font = mcfont.McFont.load()
    sprites = Sprites(SPRITES)
    w, h = spec["width"], spec["height"]
    canvas = Canvas(w, h, font, sprites)
    for el in flatten(spec["elements"]):
        draw_element(canvas, el)
    if guides:
        overlay_guides(canvas, spec)
    out = canvas.img
    if scale > 1:
        out = out.resize((w * scale, h * scale), Image.NEAREST)
    return out


def flatten(elements):
    """Expand `stack` containers: children get x/w from the parent and a y
    assigned by walking down with a fixed gutter. A layout written as a stack
    cannot drift out of alignment, which is most of the battle."""
    out = []
    for el in elements:
        if el.get("t") != "stack":
            out.append(el)
            continue
        y = el["y"]
        gap = el.get("gap", TOKENS["metric"]["gutter"])
        for child in el["items"]:
            child = dict(child)
            child.setdefault("x", el["x"])
            child.setdefault("w", el["w"])
            child["y"] = y
            y += child.get("h", TOKENS["metric"]["row_h"]) + gap
            out.extend(flatten([child]))
    return out


def draw_element(c, el):
    kind = el["t"]
    x, y = el.get("x", 0), el.get("y", 0)
    w, h = el.get("w", 0), el.get("h", 0)
    m = TOKENS["metric"]

    if kind in ("window", "inset", "card", "card_hover"):
        c.sprites.draw(c.img, "panel/" + kind, x, y, w, h)
    elif kind == "frame":
        c.sprites.draw(c.img, "panel/" + el.get("surface", "window"),
                       x, y, w, h)
        tone = el.get("tone", "accent")
        c.stroke(x, y, w, h, colour(tone), el.get("stroke", 1))
        if el.get("inner", True) and w > 6 and h > 6:
            c.stroke(x + 3, y + 3, w - 6, h - 6,
                     rgba(tone, el.get("inner_alpha", 72)))
    elif kind == "soft_panel":
        tone = el.get("tone", "accent")
        c.rect(x, y, w, h, rgba(tone, el.get("alpha", 18)))
        c.stroke(x, y, w, h, rgba(tone, el.get("stroke_alpha", 145)),
                 el.get("stroke", 1))
    elif kind == "ellipse":
        tone = el.get("tone", "good")
        outline = colour(el["outline"]) if el.get("outline") else None
        c.ellipse(x, y, w, h, rgba(tone, el.get("alpha", 16)), outline,
                  el.get("stroke", 1))
    elif kind in ("line", "road"):
        points = [(point[0], point[1]) for point in el.get("points", [])]
        tone = el.get("tone", "accent")
        if kind == "road":
            c.line(points, colour(el.get("edge", "road_edge")),
                   el.get("width", 5) + 2)
        c.line(points, rgba(tone, el.get("alpha", 255)),
               el.get("width", 2))
    elif kind == "polygon":
        points = [(point[0], point[1]) for point in el.get("points", [])]
        tone = el.get("tone", "accent")
        outline = colour(el["outline"]) if el.get("outline") else None
        c.polygon(points, rgba(tone, el.get("alpha", 64)), outline)
    elif kind == "icon":
        c.icon(el["asset"], x, y, el.get("size", min(w or 16, h or 16)),
               el.get("anchor", "top_left"))
    elif kind == "texture_crop":
        c.texture_crop(el["asset"], el["crop"], x, y,
                       w or el.get("size", 16), h or el.get("size", 16),
                       el.get("anchor", "top_left"))
    elif kind == "vanilla_icon":
        c.vanilla_icon(el["resource"], x, y,
                       el.get("size", min(w or 16, h or 16)),
                       el.get("anchor", "top_left"))
    elif kind == "medallion":
        radius = el.get("radius", min(w or 36, h or 36) // 2)
        tone = el.get("tone", "accent")
        c.ellipse(x - radius, y - radius, radius * 2, radius * 2,
                  rgba(tone, el.get("alpha", 25)), colour(tone), 2)
        if radius >= 12:
            c.ellipse(x - radius + 4, y - radius + 4,
                      radius * 2 - 8, radius * 2 - 8,
                      (0, 0, 0, 0), rgba(tone, 105), 1)
        if el.get("asset"):
            c.icon(el["asset"], x, y, el.get("icon_size", radius), "center")
        if el.get("text"):
            c.text(x, y - 3, el["text"], colour(el.get("text_tone", "text")),
                   align="center", box=radius * 2 - 8,
                   label=el.get("id", "medallion"))
    elif kind == "ring":
        diameter = el.get("diameter", min(w or 36, h or 36))
        tone = el.get("tone", "good")
        track = el.get("track", "field")
        c.arc(x, y, diameter, diameter, -90, 269, colour(track),
              el.get("stroke", 3))
        pct = max(0.0, min(1.0, el.get("value", 0.0)))
        if pct > 0:
            c.arc(x, y, diameter, diameter, -90, -90 + round(359 * pct),
                  colour(tone), el.get("stroke", 3))
    elif kind in ("badge", "ribbon"):
        tone = el.get("tone", "accent")
        c.rect(x, y, w, h, rgba(tone, el.get("alpha", 28)))
        c.stroke(x, y, w, h, colour(tone))
        text = el.get("text", "")
        c.text(x + w // 2, y + (h - m["text_h"]) // 2 + 1, text,
               colour(el.get("text_tone", tone)), align="center", box=w - 8,
               label=el.get("id", kind))
    elif kind == "section":
        tone = el.get("tone", "accent")
        text = el.get("text", "")
        width = c.text(x, y, text, colour(tone), box=w,
                       label=el.get("id", "section"))
        line_x = x + min(max(0, w - 4), width + 8)
        c.rect(line_x, y + 4, max(0, x + w - line_x), 1,
               rgba(tone, el.get("alpha", 110)))
    elif kind == "paragraph":
        c.paragraph(x, y, el.get("text", ""), el.get("box", w),
                    el.get("lines", max(1, h // el.get("line_gap", 11))),
                    colour(el.get("tone", "text")),
                    el.get("line_gap", 11), el.get("id", "paragraph"))
    elif kind == "slot":
        c.sprites.draw(c.img, "widget/slot", x, y, m["slot"], m["slot"])
    elif kind in ("item_slot", "inventory_grid"):
        columns = el.get("columns", 1) if kind == "inventory_grid" else 1
        rows = el.get("rows", 1) if kind == "inventory_grid" else 1
        slot = el.get("slot", m["slot"])
        gap = el.get("gap", 0)
        for row in range(rows):
            for column in range(columns):
                sx = x + column * (slot + gap)
                sy = y + row * (slot + gap)
                c.sprites.draw(c.img, "widget/slot", sx, sy, slot, slot)
        for item in el.get("items", []):
            index = item.get("index", 0)
            row, column = divmod(index, columns)
            if row < 0 or row >= rows:
                WARNINGS.append(
                    f"inventory item index {index} outside {columns}x{rows} grid")
                continue
            sx = x + column * (slot + gap)
            sy = y + row * (slot + gap)
            tone = item.get("state")
            if tone:
                c.stroke(sx, sy, slot, slot, colour(tone), 2)
            icon_size = max(1, slot - 4)
            if item.get("resource"):
                c.vanilla_icon(item["resource"], sx + slot // 2,
                               sy + slot // 2, icon_size, "center")
            elif item.get("asset"):
                c.icon(item["asset"], sx + slot // 2, sy + slot // 2,
                       icon_size, "center")
            count = item.get("count")
            if count not in (None, 1):
                value = str(count)
                c.text(sx + slot - 2, sy + slot - 8, value, colour("text"),
                       align="right", box=max(1, slot - 3),
                       label=f"inventory count {index}")
    elif kind == "divider":
        c.sprites.draw(c.img, "widget/divider", x, y, w, m["divider_h"])
    elif kind == "button":
        state = el.get("state", "idle")
        c.sprites.draw(c.img, "widget/button_" + state, x, y,
                       w, h or m["button_h"])
        label = el.get("text", "")
        tone = "text_muted" if state == "disabled" else "text"
        bh = h or m["button_h"]
        c.text(x + w // 2, y + (bh - m["text_h"]) // 2 + 1, label,
               colour(tone), align="center", box=w - 8,
               label=f'button "{label}"')
    elif kind == "tab":
        sel = el.get("selected", False)
        c.sprites.draw(c.img, "widget/tab_" + ("selected" if sel else "unselected"),
                       x, y, w, h or m["title_h"])
        c.text(x + w // 2, y + 4, el.get("text", ""),
               colour("text" if sel else "text_muted"), align="center",
               box=w - 6, label="tab")
    elif kind in ("title", "label"):
        tone = el.get("tone", "text_strong" if kind == "title" else "text")
        c.text(x, y, el.get("text", ""), colour(tone),
               align=el.get("align", "left"), box=el.get("box"),
               label=el.get("id", kind))
    elif kind == "pips":
        tone = el.get("tone", "accent")
        for i in range(el.get("of", 5)):
            name = f"widget/pip_{tone}" if i < el.get("n", 0) else "widget/pip_empty"
            c.sprites.draw(c.img, name, x + i * 6, y, 5, 5)
    elif kind == "bar":
        c.sprites.draw(c.img, "bar/track", x, y, w, h or 6)
        pct = max(0.0, min(1.0, el.get("value", 0.0)))
        tone = el.get("tone", "good")
        c.sprites.draw(c.img, f"bar/fill_{tone}", x + 1, y + 1,
                       int((w - 2) * pct), (h or 6) - 2)
    elif kind == "fill":
        c.rect(x, y, w, h, colour(el.get("colour", "field")))
    elif kind == "item":
        # A stand-in for a rendered ItemStack: exact 16x16 so the box maths is
        # checked even though the icon is not the real one.
        c.rect(x, y, 16, 16, argb(0x40FFFFFF))
        c.text(x + 8, y + 5, el.get("text", "?"), colour("text_muted"),
               align="center", shadow=False)
    else:
        raise SystemExit(f"unknown element type {kind!r}")


def overlay_guides(c, spec):
    grid = TOKENS["metric"]["grid"]
    for gx in range(0, spec["width"], grid):
        c.rect(gx, 0, 1, spec["height"], argb(0x14FF00FF))
    for gy in range(0, spec["height"], grid):
        c.rect(0, gy, spec["width"], 1, argb(0x14FF00FF))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec", nargs="?")
    ap.add_argument("-o", "--out")
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--guides", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--all", action="store_true")
    args = ap.parse_args()

    specs = sorted(glob.glob(os.path.join(SCREENS, "*.json"))) if args.all \
        else [args.spec]
    if not specs or specs == [None]:
        ap.error("give a spec path or --all")

    failed = False
    for path in specs:
        WARNINGS.clear()
        # Windows' locale default is commonly cp1252. Explicit UTF-8 keeps
        # canonical UI glyphs (•, ×, — and Norwegian letters) from turning
        # into mojibake in the very preview used to approve them.
        spec = json.load(open(path, encoding="utf-8"))
        try:
            img = render(spec, scale=args.scale, guides=args.guides)
        except mcfont.FontUnavailable as exc:
            print(f"SKIP  {os.path.basename(path)}: {exc}")
            continue
        out = args.out if (args.out and not args.all) else os.path.join(
            HERE, "..", "build", "uipreview",
            os.path.basename(path).replace(".json", ".png"))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        img.save(out)
        status = "WARN" if WARNINGS else "ok  "
        print(f"{status}  {os.path.basename(path)} -> {out}")
        for warning in WARNINGS:
            print(f"        {warning}")
        failed = failed or bool(WARNINGS)
    return 1 if (failed and args.strict) else 0


if __name__ == "__main__":
    sys.exit(main())
