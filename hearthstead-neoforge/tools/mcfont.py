#!/usr/bin/env python3
"""The real Minecraft font, for layout that does not guess.

A UI preview whose text is the wrong width is worse than no preview: it will
happily show you a label that fits and a game that clips it. So this module
reads the *actual* vanilla font out of the client jar -- the provider list, the
glyph sheets, the advances -- and rasterises text exactly as the game does.

Nothing from the jar is ever written into the repository. Everything is
extracted to build/uicache/, which is gitignored: these are Mojang's assets and
they stay on the machine that already has them.

Public API
----------
    font = McFont.load()          # raises FontUnavailable if no jar
    font.width("Hire Astrid")     # advance in GUI pixels, exactly as MC counts
    font.draw(img, x, y, text, colour, shadow=True)
    font.trim("a long label", 90) # ellipsised to fit, MC-style

Run it directly to self-test the metrics against known vanilla widths.
"""
import glob
import json
import os
import sys
import zipfile

try:
    from PIL import Image
except ImportError:  # pragma: no cover - the caller reports this nicely
    Image = None

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "..", "build", "uicache")

JAR_GLOBS = [
    os.environ.get("MC_CLIENT_JAR", ""),
    "/root/.gradle/caches/neoformruntime/artifacts/minecraft_*_client.jar",
    os.path.expanduser("~/.gradle/caches/neoformruntime/artifacts/minecraft_*_client.jar"),
    os.path.expanduser("~/.gradle/caches/forge_gradle/minecraft_repo/versions/*/client.jar"),
]

# What the font must measure if we parsed it correctly. These are vanilla
# advances every modder knows by eye; if one of them is wrong the parse is
# wrong, and a preview built on a wrong parse is a liar.
KNOWN_WIDTHS = {
    " ": 4, "a": 6, "A": 6, "i": 2, "l": 3, "t": 4, "f": 5, "k": 5,
    "!": 2, ".": 2, ",": 2, ":": 2, "'": 2, "@": 7, "~": 7,
}


class FontUnavailable(RuntimeError):
    """No client jar on this machine -- the caller should degrade, not crash."""


def find_client_jar():
    for pattern in JAR_GLOBS:
        if not pattern:
            continue
        for hit in sorted(glob.glob(pattern), reverse=True):
            if os.path.isfile(hit):
                return hit
    return None


class _Bitmap:
    """One bitmap provider: a sheet, a grid of codepoints, and a scale.

    Vanilla renders a sheet cell at `height` pixels tall regardless of how many
    pixels the cell actually is, so a 9x12 cell in a height-12 provider is 1:1
    while ascii.png's 8x8 cell in a height-8 provider is also 1:1 -- but the
    accented sheet is not, and getting this wrong is how a preview drifts.
    """

    def __init__(self, sheet, rows, height, ascent):
        self.sheet = sheet.convert("RGBA")
        self.rows = rows
        self.height = height
        self.ascent = ascent
        self.cols = max(len(r) for r in rows)
        self.cell_w = self.sheet.width // self.cols
        self.cell_h = self.sheet.height // len(rows)
        self.scale = self.height / self.cell_h
        self._cache = {}

    def index(self, codepoint):
        for row, chars in enumerate(self.rows):
            col = chars.find(chr(codepoint))
            if col >= 0:
                return row, col
        return None

    def glyph(self, codepoint):
        """(image, advance) for a codepoint, or None if this sheet lacks it."""
        if codepoint in self._cache:
            return self._cache[codepoint]
        at = self.index(codepoint)
        if at is None:
            self._cache[codepoint] = None
            return None
        row, col = at
        box = (col * self.cell_w, row * self.cell_h,
               (col + 1) * self.cell_w, (row + 1) * self.cell_h)
        cell = self.sheet.crop(box)
        # Vanilla trims to the last column carrying any alpha, then adds one
        # pixel of letter spacing. A wholly blank cell is one pixel wide, which
        # is why space comes from the space provider and not from ascii.png.
        last = -1
        px = cell.load()
        for i in range(cell.width):
            for j in range(cell.height):
                if px[i, j][3] != 0:
                    last = i
                    break
        pixel_w = last + 1
        advance = round(pixel_w * self.scale) + 1
        if self.scale != 1.0:
            target = (max(1, round(cell.width * self.scale)),
                      max(1, round(cell.height * self.scale)))
            cell = cell.resize(target, Image.NEAREST)
        self._cache[codepoint] = (cell, advance, pixel_w)
        return self._cache[codepoint]


class McFont:
    def __init__(self, providers, spaces):
        self.providers = providers
        self.spaces = spaces
        self._glyphs = {}

    # -- construction ------------------------------------------------------

    @classmethod
    def load(cls, jar=None):
        if Image is None:
            raise FontUnavailable("Pillow is not installed")
        jar = jar or find_client_jar()
        if not jar:
            raise FontUnavailable(
                "no Minecraft client jar found; set MC_CLIENT_JAR to one")
        os.makedirs(CACHE, exist_ok=True)
        with zipfile.ZipFile(jar) as zf:
            def read_json(path):
                return json.loads(zf.read(path).decode("utf-8"))

            def read_png(rl):
                # "minecraft:font/ascii.png" -> assets/minecraft/textures/font/...
                ns, path = rl.split(":", 1) if ":" in rl else ("minecraft", rl)
                member = f"assets/{ns}/textures/{path}"
                out = os.path.join(CACHE, member.replace("/", "_"))
                if not os.path.exists(out):
                    with open(out, "wb") as fh:
                        fh.write(zf.read(member))
                return Image.open(out)

            entries = []
            spaces = {}

            def walk(descriptor):
                for p in read_json(descriptor)["providers"]:
                    kind = p.get("type")
                    if kind == "reference":
                        ns, path = p["id"].split(":", 1)
                        walk(f"assets/{ns}/font/{path}.json")
                    elif kind == "space":
                        for ch, adv in p["advances"].items():
                            spaces.setdefault(ch, adv)
                    elif kind == "bitmap":
                        entries.append(_Bitmap(
                            read_png(p["file"]), p["chars"],
                            p.get("height", 8), p.get("ascent", 7)))

            walk("assets/minecraft/font/default.json")
        return cls(entries, spaces)

    # -- metrics -----------------------------------------------------------

    def glyph(self, ch):
        if ch in self._glyphs:
            return self._glyphs[ch]
        if ch in self.spaces:
            self._glyphs[ch] = (None, self.spaces[ch], 0)
            return self._glyphs[ch]
        found = None
        for provider in self.providers:
            got = provider.glyph(ord(ch))
            if got:
                found = (got[0], got[1], provider.ascent)
                break
        if found is None:
            found = (None, 6, 7)  # vanilla's missing-glyph box is 6 wide
        self._glyphs[ch] = found
        return found

    def width(self, text, bold=False):
        total = 0
        for ch in text:
            total += self.glyph(ch)[1] + (1 if bold else 0)
        return total

    def trim(self, text, max_width, ellipsis="..."):
        """Shorten to fit, exactly the way a too-long label must be handled."""
        if self.width(text) <= max_width:
            return text
        budget = max_width - self.width(ellipsis)
        if budget <= 0:
            return ellipsis
        out, used = [], 0
        for ch in text:
            w = self.glyph(ch)[1]
            if used + w > budget:
                break
            out.append(ch)
            used += w
        return "".join(out).rstrip() + ellipsis

    # -- drawing -----------------------------------------------------------

    def draw(self, img, x, y, text, colour, shadow=True):
        """Draw at MC's own baseline convention: y is the top of an 8px line."""
        if shadow:
            self._draw_plain(img, x + 1, y + 1, text, _dim(colour))
        self._draw_plain(img, x, y, text, colour)
        return self.width(text)

    def _draw_plain(self, img, x, y, text, colour):
        pen = x
        for ch in text:
            cell, advance, ascent = self.glyph(ch)
            if cell is not None:
                tinted = Image.new("RGBA", cell.size, colour)
                tinted.putalpha(cell.getchannel("A"))
                # ascent 7 is the ASCII baseline; taller sheets hang higher so
                # that accented capitals line up with plain ones.
                img.alpha_composite(tinted, (pen, y + (7 - ascent)))
            pen += advance


def _dim(colour):
    r, g, b, a = colour
    return (r // 4, g // 4, b // 4, a)


def self_test():
    try:
        font = McFont.load()
    except FontUnavailable as exc:
        print(f"SKIP  {exc}")
        return 0
    bad = []
    for ch, expect in KNOWN_WIDTHS.items():
        got = font.width(ch)
        if got != expect:
            bad.append(f"    {ch!r}: expected {expect}, parsed {got}")
    # Norwegian matters here: nb_no is a supported language and the accented
    # sheet is the one whose scaling is easy to get wrong.
    for ch in "æøåÆØÅ":
        if font.width(ch) <= 0:
            bad.append(f"    {ch!r}: no glyph -- nb_no would render as boxes")
    if bad:
        print("FAIL  font metrics do not match vanilla:")
        print("\n".join(bad))
        return 1
    print(f"PASS  font metrics match vanilla "
          f"({len(font.providers)} bitmap providers, "
          f"width('Hearthstead')={font.width('Hearthstead')})")
    return 0


if __name__ == "__main__":
    sys.exit(self_test())
