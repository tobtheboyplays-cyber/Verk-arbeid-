#!/usr/bin/env python3
"""Offscreen stills of the v3 Tech Tree screen, without booting Minecraft.

This is a line-by-line port of the drawing code in
    src/main/java/com/hearthstead/client/screen/TechTreeScreen.java
(v2: Ui2FrameLayout frame with crest, serif title, rank, coin counter, "?"
and close keys; the graph on the page parchment; focus-only strong edges and
cross-branch stubs; ring labels; Planned hatching and tags; the "?" legend
plate; the side inset with chips, costs, serif headings and the burgundy
Research button) plus the ui2 kit it uses (BannerChrome, HearthMaterials.paper,
Ui2Frame, Ui2Surface.badge, Ui2Serif small caps from droid_serif_bold.ttf).

How it matches the game:
  * text is the REAL vanilla font (tools/mcfont.py) at GUI-pixel size, scaled
    up with nearest neighbour exactly like the GUI scale does;
  * every g.fill() lands on whole GUI pixels, as in game;
  * rotated fills (edges, arrowheads, dashes) and scaled item icons are
    rasterised at window resolution, as the GPU does;
  * item icons come from the client jar / mod assets (block items get a
    small isometric cube).

What it is NOT: an in-game capture. The world behind the screen is a blurred
fake, the snapshot state is invented (see fake_state()), italics are drawn
upright and nothing animates (the READY pulse is frozen at its mid point).

    python tools/techtree_preview.py                 # v2_overview / v2_zoom / v2_selected
    python tools/techtree_preview.py --out some/dir
"""
import argparse
import json
import math
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFilter  # noqa: E402
import mcfont  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, ".."))
DATA = os.path.join(REPO, "src", "main", "resources", "data", "hearthstead", "techtree")
MOD_TEX = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "textures")
DEFAULT_OUT = os.path.abspath(os.path.join(REPO, "..", "..", "plan", "techtree", "stills"))

FONT = None
JAR = None

# ------------------------------------------------------------------ palette
# Ui2Palette
PAPER = 0xFFEFE7D6
PAPER_TEXTURE_VEIL = 0xD8EFE7D6
FRAME_INNER = 0xFFF8F2E4
SHADOW = 0x38201408
RULE = 0xFFD2C4A6
RULE_STRONG = 0xFFB9A682
INK = 0xFF2E261C
INK_SOFT = 0xFF5B4F3F
INK_MUTED = 0xFF82745D
INK_DISABLED = 0xFFA39781
ON_ACCENT = 0xFFF5F0E3
FOREST = 0xFF3E6243
FOREST_DARK = 0xFF2B4630
FOREST_HIGHLIGHT = 0xFF5F8163
P_GOLD = 0xFF9C7B3C
DANGER = 0xFF8C3A31
WALNUT = 0xFF4B3323
WALNUT_DARK = 0xFF24170F
WALNUT_LIGHT = 0xFF6E4B32
WALNUT_GRAIN = 0xFF3E2A1C
IRON = 0xFF3A3836
IRON_LIGHT = 0xFF7C776F
IRON_DARK = 0xFF1E1D1C
AMBER = 0xFFA87B2E
DISABLED_FILL = 0xFFDCD2BE
DISABLED_BORDER = 0xFFC4B79C
TRACK = 0xFFDCD0B6
# BannerChrome
TEXT_ON_WOOD = 0xFFF1E5CB
TEXT_ON_WOOD_MUTED = 0xFFBFAE8F
PLATE_SHADOW = 0xFF1B110A
PLATE_HIGHLIGHT = 0xFF5B412D
GOLD_EDGE = 0xFFD9B880
INSET_DARK = 0xFF24170E
GRAIN_LIGHT = 0xFF56392A
FRAME = 6  # BannerSheetLayout.FRAME
# TechTreeScreen
MIN_ZOOM, MAX_ZOOM = 0.16, 1.8
HEADER_H, FOOTER_H = 26, 16
GOLD = 0xFFD9B04A
READY = 0xFF4E9A52
AVAILABLE = 0xFF6F8F5E
LOCKED = 0xFF9A907E
LOCKED_FILL = 0xFFD9D0BE
BLOCKED = 0xFF8C3A31
PLANNED = 0xFFB9AE98
STUDYING = 0xFFB07D2A
EDGE_IDLE = 0x5A6B5A45
PAPER_OVERLAY = 0x60F3EBD6  # HearthMaterials


def jround(v):
    """Java Math.round(float)."""
    return int(math.floor(v + 0.5))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def with_alpha(c, a):
    return (clamp(a, 0, 255) << 24) | (c & 0xFFFFFF)


def darken(c, f):
    r = jround(((c >> 16) & 0xFF) * f)
    g = jround(((c >> 8) & 0xFF) * f)
    b = jround((c & 0xFF) * f)
    return 0xFF000000 | (r << 16) | (g << 8) | b


def lighten(c, f):
    r, g, b = (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF
    r = jround(r + (255 - r) * f)
    g = jround(g + (255 - g) * f)
    b = jround(b + (255 - b) * f)
    return 0xFF000000 | (r << 16) | (g << 8) | b


def rgba(c):
    return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >> 24) & 0xFF)


# ------------------------------------------------------------ graphics ----

class G:
    """GuiGraphics stand-in: GUI-space calls, window-space raster."""

    def __init__(self, gw, gh, s):
        self.s = s
        self.gw, self.gh = gw, gh
        self.img = Image.new("RGB", (gw * s, gh * s), (0, 0, 0))
        self.d = ImageDraw.Draw(self.img, "RGBA")
        self._scissor = None

    # g.fill: integer GUI rectangle, swapped like vanilla
    def fill(self, x0, y0, x1, y1, c):
        if (c >> 24) & 0xFF == 0:
            return
        x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
        if x1 < x0:
            x0, x1 = x1, x0
        if y1 < y0:
            y0, y1 = y1, y0
        s = self.s
        if x1 <= x0 or y1 <= y0:
            return
        self.d.rectangle([x0 * s, y0 * s, x1 * s - 1, y1 * s - 1], fill=rgba(c))

    def poly(self, pts, c):
        s = self.s
        self.d.polygon([(x * s - 0.5, y * s - 0.5) for x, y in pts], fill=rgba(c))

    def comp(self, im, X, Y):
        W, H = self.img.size
        x0, y0 = max(0, X), max(0, Y)
        x1, y1 = min(W, X + im.width), min(H, Y + im.height)
        if x1 <= x0 or y1 <= y0:
            return
        sub = im.crop((x0 - X, y0 - Y, x1 - X, y1 - Y))
        self.img.paste(sub, (x0, y0), sub)

    def text(self, x, y, t, c, k=1.0):
        """drawString(..., shadow=false); k is a pose scale (Ui2Type.title)."""
        if not t:
            return 0
        a = (c >> 24) & 0xFF
        if (c & 0xFC000000) == 0:
            a = 255
        w = FONT.width(t)
        tmp = Image.new("RGBA", (w + 2, 16), (0, 0, 0, 0))
        FONT.draw(tmp, 0, 4, t, (255, 255, 255, 255), shadow=False)
        alpha = tmp.getchannel("A").point(lambda v: v * a // 255)
        layer = Image.new("RGBA", tmp.size, rgba(c)[:3] + (255,))
        layer.putalpha(alpha)
        f = self.s * k
        layer = layer.resize((max(1, jround(layer.width * f)), max(1, jround(16 * f))), Image.NEAREST)
        self.comp(layer, jround(x * self.s), jround((y - 4 * k) * self.s))
        return w

    def item(self, tex, x, y, size=16.0):
        if tex is None:
            return
        px = max(1, jround(size * self.s))
        self.comp(tex.resize((px, px), Image.NEAREST), jround(x * self.s), jround(y * self.s))

    def enable_scissor(self, x0, y0, x1, y1):
        self._scissor = ((x0 * self.s, y0 * self.s, x1 * self.s, y1 * self.s), self.img.copy())

    def disable_scissor(self):
        box, snap = self._scissor
        inside = self.img.crop(box)
        self.img.paste(snap, (0, 0))
        self.img.paste(inside, box[:2])
        self._scissor = None


def split(text, w):
    """Font.split: greedy word wrap, over-long words broken by character."""
    lines, cur = [], ""
    for word in text.split(" "):
        cand = word if not cur else cur + " " + word
        if FONT.width(cand) <= w:
            cur = cand
            continue
        if cur:
            lines.append(cur)
            cur = ""
        while FONT.width(word) > w and len(word) > 1:
            i = 1
            while i < len(word) and FONT.width(word[:i + 1]) <= w:
                i += 1
            lines.append(word[:i])
            word = word[i:]
        cur = word
    if cur or not lines:
        lines.append(cur)
    return lines


# --------------------------------------------------------------- textures --

_TEX = {}


def jar_png(member):
    try:
        with zipfile.ZipFile(JAR) as zf:
            import io
            return Image.open(io.BytesIO(zf.read(member))).convert("RGBA")
    except (KeyError, OSError, TypeError):
        return None


def iso_cube(top, side, front=None):
    """A GUI block-item cube, drawn texel by texel into 64x64."""
    front = front or side
    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    L, T, R, B = (1, 4.25), (8, 0.75), (15, 4.25), (8, 7.75)
    faces = [
        (top, L, (T[0] - L[0], T[1] - L[1]), (B[0] - L[0], B[1] - L[1]), 1.0),
        (front, L, (B[0] - L[0], B[1] - L[1]), (0, 8), 0.8),
        (side, B, (R[0] - B[0], R[1] - B[1]), (0, 8), 0.6),
    ]
    for tex, o, eu, ev, shade in faces:
        t = tex.resize((16, 16), Image.NEAREST)
        px = t.load()
        for v in range(16):
            for u in range(16):
                r, g, b, a = px[u, v]
                if a == 0:
                    continue
                pts = []
                for du, dv in ((0, 0), (1, 0), (1, 1), (0, 1)):
                    uu, vv = (u + du) / 16.0, (v + dv) / 16.0
                    pts.append(((o[0] + eu[0] * uu + ev[0] * vv) * 4,
                                (o[1] + eu[1] * uu + ev[1] * vv) * 4))
                d.polygon(pts, fill=(int(r * shade), int(g * shade), int(b * shade), 255))
    return out


def placeholder(colour):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([2, 2, 13, 13], fill=rgba(colour)[:3] + (255,), outline=(40, 28, 16, 255))
    return im


def banner_icon():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([7, 1, 8, 15], fill=(110, 80, 50, 255))
    d.rectangle([3, 2, 12, 3], fill=(110, 80, 50, 255))
    d.rectangle([4, 3, 11, 12], fill=(235, 235, 228, 255))
    return im


def texture(item_id):
    """16x16 (or 64x64 cube) RGBA for an item id like 'minecraft:iron_ingot'."""
    if item_id in _TEX:
        return _TEX[item_id]
    ns, path = item_id.split(":", 1) if ":" in item_id else ("minecraft", item_id)
    tex = None
    if ns == "hearthstead":
        p = os.path.join(MOD_TEX, "item", path + ".png")
        if os.path.exists(p):
            tex = Image.open(p).convert("RGBA")
    else:
        tex = jar_png(f"assets/minecraft/textures/item/{path}.png")
        if tex is None:
            if path == "chest":
                tex = placeholder(0xFFA0702E)
            elif path.endswith("_banner"):
                tex = banner_icon()
            else:
                base = f"assets/minecraft/textures/block/{path}"
                plain = jar_png(base + ".png")
                side = jar_png(base + "_side.png") or plain
                top = jar_png(base + "_top.png") or plain or side
                front = jar_png(base + "_front.png") or side
                if side is not None and top is not None:
                    tex = iso_cube(top.crop((0, 0, 16, 16)), side.crop((0, 0, 16, 16)),
                                   front.crop((0, 0, 16, 16)))
    if tex is not None and tex.height > tex.width:  # animated strip
        tex = tex.crop((0, 0, tex.width, tex.width))
    _TEX[item_id] = tex if tex is not None else placeholder(0xFF9A907E)
    return _TEX[item_id]


# Legacy DevelopmentNode professions (first one wins, as computeIcon does).
LEGACY_PROFESSIONS = {
    "timber_rights": "lumberer", "stores_and_roads": "courier",
    "cultivated_ground": "farmer", "shore_provisions": "fisher",
    "hospitality": "innkeeper", "first_watch": "guard", "arm_the_watch": "archer",
    "first_raid_aftermath": "healer", "shield_doctrine": "spearman",
    "guild_doctrine": "sawyer", "hearth_doctrine": "scholar",
    "fortification": "armourer", "border_wardens": "hunter",
    "land_and_harvest": "miller", "craft_and_industry": "miner",
    "hall_and_learning": "cook",
}
BRANCH_DEFAULT = {"watch": "minecraft:iron_sword", "logistics": "minecraft:chest",
                  "commons": "minecraft:bread", "craft": "minecraft:iron_pickaxe"}


def node_icon(n):
    leg = n.get("legacy") or ""
    if leg.startswith("node:") and n["id"] in LEGACY_PROFESSIONS:
        return texture("hearthstead:" + LEGACY_PROFESSIONS[n["id"]] + "_emblem")
    for g in n["goods"]:
        if "item" in g:
            return texture(g["item"])
    return texture(BRANCH_DEFAULT.get(n["branch"], "minecraft:white_banner"))


# ------------------------------------------------------------------ data --

class Data:
    def __init__(self):
        tree = json.load(open(os.path.join(DATA, "tree.json"), encoding="utf-8"))
        self.branches = {b["id"]: dict(b, color=0xFF000000 | int(b["color"][1:], 16))
                         for b in tree["branches"]}
        self.tiers = tree["tiers"]
        self.nodes = []
        for br in ["crown", "watch", "logistics", "commons", "craft"]:
            f = json.load(open(os.path.join(DATA, br + ".json"), encoding="utf-8"))
            for n in f["nodes"]:
                n["branch"] = br
                n.setdefault("excludes", [])
                n.setdefault("requires", [])
                self.nodes.append(n)
        self.by_id = {n["id"]: n for n in self.nodes}

    def node(self, i):
        return self.by_id.get(i)

    def ancestors(self, i):
        seen, open_ = set(), list(self.by_id[i]["requires"])
        while open_:
            nxt = open_.pop()
            if nxt not in seen:
                seen.add(nxt)
                if nxt in self.by_id:
                    open_.extend(self.by_id[nxt]["requires"])
        return seen


# ----------------------------------------------------------- fake state ---

LEARNED_IDS = ["settlement_charter", "timber_rights", "stores_and_roads", "home",
               "hospitality", "first_watch", "arm_the_watch", "first_raid_aftermath",
               "cultivated_ground", "warm_hearth", "guard_drill", "stout_straps"]
READY_IDS = ["shield_doctrine", "courier_satchel", "spearmen"]


def fake_state(data, zoom_variant):
    learned = set(LEARNED_IDS)
    if zoom_variant:
        learned.add("archer_longbow_drill")
    st = {}
    for n in data.nodes:
        i = n["id"]
        if i in learned:
            s = "LEARNED"
        elif i in READY_IDS:
            s = "READY"
        elif zoom_variant and i == "crossbows":
            s = "BLOCKED"
        elif all(r in learned for r in n["requires"]):
            s = "AVAILABLE"
        elif not n.get("legacy") and n["tier"] >= 3 and n["branch"] != "crown":
            # Crown charters have registered effects (CrownEffects), so never Planned.
            s = "PLANNED"
        else:
            s = "LOCKED"
        st[i] = s
    return st


# ---------------------------------------------------------------- screen --
# Port of TechTreeScreen (v2: Ui2FrameLayout frame, graph on the page
# parchment, light side inset, focus-only strong edges, "?" legend plate).

BURGUNDY = 0xFF7A2E2A
BURGUNDY_DARK = 0xFF571E1B
BURGUNDY_HIGHLIGHT = 0xFF9A4A43
GOLD_SOFT = 0xFFCDB57E
PAPER_DEEP = 0xFFE7DDC8
PLATE_HOVER = 0xFF46311F
GRAY = 0xFFAAAAAA  # ChatFormatting.GRAY
ON_BURGUNDY = 0xFFF4E9D8
# TechTreeScreen status colours are Ui2Palette tokens now.
GOLD = P_GOLD
READY = FOREST
AVAILABLE = FOREST_HIGHLIGHT
LOCKED = INK_DISABLED
LOCKED_FILL = DISABLED_FILL
BLOCKED = DANGER
PLANNED = DISABLED_BORDER
STUDYING = AMBER
EDGE_IDLE = INK_MUTED

# Ui2FrameLayout
FL_MARGIN, FL_GUTTER, FL_S = 6, 8, 4
FL_HEADER_H, FL_CLOSE = 20, 11

SERIF_TTF = os.path.join(REPO, "src", "main", "resources", "assets", "hearthstead", "font",
                         "droid_serif_bold.ttf")
SERIF_SIZES = {"cap": 14.0, "mid": 11.0, "small": 9.0}
_SERIF = {}


def serif_font(kind, s):
    """The ttf provider: size * oversample px per ScaleForPixelHeight (ascent - descent)."""
    key = (kind, s)
    if key not in _SERIF:
        from PIL import ImageFont
        a, d = ImageFont.truetype(SERIF_TTF, 1000).getmetrics()
        em = SERIF_SIZES[kind] * s * 1000.0 / (a + d)
        _SERIF[key] = ImageFont.truetype(SERIF_TTF, max(1, jround(em)))
    return _SERIF[key]


def small_caps(text, big="cap", small="mid"):
    """Ui2Serif.smallCaps: word initials big, the rest upper-cased small."""
    runs, run, run_big, word_start = [], "", False, True
    for ch in text:
        letter = ch.isalnum()
        is_big = letter and word_start
        if run and is_big != run_big:
            runs.append((run, big if run_big else small))
            run = ""
        run_big = is_big
        run += ch.upper()
        word_start = not letter and ch != "'"
    if run:
        runs.append((run, big if run_big else small))
    return runs


def serif_width(runs, s):
    return math.ceil(sum(serif_font(k, s).getlength(t) for t, k in runs) / s)


def serif_draw(g, runs, x, y, c):
    """All sizes share the vanilla baseline (y + 7)."""
    s = g.s
    pen = x * s
    base = (y + 7) * s
    for t, k in runs:
        f = serif_font(k, s)
        g.d.text((pen, base), t, font=f, fill=rgba(c), anchor="ls")
        pen += f.getlength(t)


def text_segments(g, x, y, segs):
    for t, c in segs:
        g.text(x, y, t, c)
        x += FONT.width(t)


def seg_width(segs):
    return sum(FONT.width(t) for t, _ in segs)


class Rect:
    def __init__(self, x, y, w, h):
        self.x, self.y, self.w, self.h = x, y, w, h

    @property
    def right(self):
        return self.x + self.w

    @property
    def bottom(self):
        return self.y + self.h


def frame_at(x, y, w, h):
    """Ui2FrameLayout.at(x, y, w, h, subtitle=false)."""
    header = Rect(x + FRAME + FL_MARGIN, y + FRAME + FL_S, w - (FRAME + FL_MARGIN) * 2, FL_HEADER_H)
    crest = Rect(header.x, y - 2, 22, FL_HEADER_H + 10)
    close = Rect(header.right - FL_CLOSE, header.y + 1, FL_CLOSE, FL_CLOSE)
    title_x = crest.right + FL_GUTTER
    title = Rect(title_x, header.y, max(1, close.x - FL_GUTTER - title_x), FL_HEADER_H)
    rule_y = header.bottom + 2
    page_top = rule_y + FL_S
    page = Rect(header.x, page_top, header.w, max(1, y + h - FRAME - FL_S - page_top))
    return dict(x=x, y=y, w=w, h=h, header=header, crest=crest, close=close, title=title,
                rule_y=rule_y, page=page)


SHORT_BRANCH = {"watch": "Watch", "logistics": "Logistics", "commons": "Commons", "craft": "Craft"}
EFFECT_BUILDINGS = {"first_watch": ["Barracks"]}  # legacy DevelopmentNode buildings (only what the stills show)
EFFECT_PROFESSIONS = {"shield_doctrine": ["Spearman Emblem", "Longswordsman Emblem"],
                      "first_watch": ["Guard Emblem"]}


class TechTreeScreen:
    def __init__(self, data, width, height, status, selected, hovered=None,
                 cam=None, zoom=None, coins=23, have=None, gates=None, legend=False):
        self.data = data
        self.width, self.height = width, height
        self.states = status
        self.selected = selected
        self.hovered = hovered
        self.coins = coins
        self.have = have or {}
        self.gates = gates or {}
        self.legend_open = legend
        self.hover_path = set()
        if hovered:
            self.hover_path = set(data.ancestors(hovered)) | {hovered}
        self.problems = []
        self.init()
        if cam is not None:
            self.camX, self.camY = cam
        if zoom is not None:
            self.zoom = zoom

    # ------------------------------------------------------------ layout
    def init(self):
        width, height = self.width, self.height
        margin = 2 if width < 520 else 6
        self.frame = frame_at(margin, margin + 2, width - margin * 2, height - margin * 2 - 2)
        page = self.frame["page"]
        self.sideW = clamp(jround(page.w * 0.3), 150, 240)
        self.sideX = page.right - self.sideW - 4
        self.viewX = page.x + 1
        self.viewY = page.y + 1
        self.viewW = self.sideX - 5 - self.viewX
        self.viewH = page.h - 2
        fit = min(self.viewW, self.viewH) / (2.0 * 560.0)
        self.zoom = clamp(fit, MIN_ZOOM, MAX_ZOOM)
        self.camX = self.camY = 0.0
        self.btn = (self.sideX + 6, self.viewY + self.viewH - 26, self.sideW - 12, 20)
        c = self.frame["close"]
        self.close_key = (c.x, c.y, c.w, c.h)
        self.legend_key = (c.x - 15, c.y, 11, 11)

    def sx(self, wx):
        return self.viewX + self.viewW / 2.0 + (wx - self.camX) * self.zoom

    def sy(self, wy):
        return self.viewY + self.viewH / 2.0 + (wy - self.camY) * self.zoom

    def status(self, i):
        return self.states.get(i, "PLANNED")

    def radius(self, n):
        world = {"capstone": 23.0, "unlock": 17.0}.get(n["type"], 14.0)
        if n["branch"] == "crown":
            world = 25.0
        return max(4.5, world * self.zoom)

    def branch_color(self, n):
        b = self.data.branches.get(n["branch"])
        return P_GOLD if b is None else b["color"]

    # ------------------------------------------------------------ render
    def render(self, s):
        g = G(self.width, self.height, s)
        render_background(g, self.width, self.height)
        f = self.frame
        panel(g, f["x"], f["y"], f["w"], f["h"])                       # Ui2Frame.board
        h = f["header"]
        g.fill(h.x, f["rule_y"], h.right, f["rule_y"] + 1, PLATE_SHADOW)
        g.fill(h.x, f["rule_y"] + 1, h.right, f["rule_y"] + 2, PLATE_HIGHLIGHT)
        p = f["page"]
        parchment(g, p.x, p.y, p.w, p.h)
        c = f["crest"]
        crest(g, c.x, c.y, c.w, c.h)
        self.render_header(g)
        g.enable_scissor(self.viewX, self.viewY, self.viewX + self.viewW, self.viewY + self.viewH)
        self.render_guides(g)
        self.render_edges(g)
        self.render_nodes(g)
        self.render_ring_labels(g)
        self.render_labels(g)
        if self.legend_open:
            self.render_mini_legend(g)
        g.disable_scissor()
        self.rebuild_panel()
        self.render_side(g)
        self.render_widgets(g)
        return g.img.convert("RGB")

    def render_header(self, g):
        t = self.frame["title"]
        coins = f"{self.coins} Coins"
        rank = "Rank: " + self.rank_name()
        boxW = FONT.width(coins) + 24
        boxX = self.frame["close"].x - 21 - boxW
        rankX = boxX - 10 - FONT.width(rank)
        runs = small_caps("Tech Tree")
        title_w = serif_width(runs, g.s)
        serif_draw(g, runs, t.x, t.y + 7, TEXT_ON_WOOD)
        y = t.y + (t.h - 16) // 2
        if rankX > t.x + title_w + 12:
            g.text(rankX, y + 4, rank, TEXT_ON_WOOD_MUTED)
        else:
            self.problems.append("header: rank hidden")
        if boxX > t.x + title_w + 8:
            counter_box(g, boxX, y, boxW, 16)
            g.item(texture("hearthstead:coins"), boxX + 2, y + 1, 14.0)
            g.text(boxX + 19, y + 4, coins, TEXT_ON_WOOD)

    def rank_name(self):
        for seal, name in (("kingdom_crown", "Kingdom"), ("castle_charter", "Castle"),
                           ("town_charter", "Town"), ("first_raid_aftermath", "Village")):
            if self.status(seal) == "LEARNED":
                return name
        return "Hamlet"

    RINGS = [330, 570, 810, 1060]
    RING_NAMES = ["Hamlet", "Village", "Town", "Castle"]

    def render_guides(self, g):
        for r in self.RINGS:
            dotted_circle(g, self.sx(0), self.sy(0), r * self.zoom, 0x30503C24)
        self.branch_labels_visible = 0
        for bid, ax, ay in (("watch", 0, -1), ("logistics", 1, 0), ("commons", 0, 1), ("craft", -1, 0)):
            b = self.data.branches.get(bid)
            cxs, cys = self.sx(0), self.sy(0)
            bx = jround(cxs if ax == 0 else clamp(cxs + ax * 1150.0 * self.zoom,
                                                  self.viewX + 50, self.viewX + self.viewW - 50))
            by = jround(cys if ay == 0 else clamp(cys + ay * 1150.0 * self.zoom, self.viewY + 12,
                                                  self.viewY + self.viewH - (40 if ay > 0 else 12)))
            if bx < self.viewX or bx > self.viewX + self.viewW or by < self.viewY or by > self.viewY + self.viewH:
                continue
            self.branch_labels_visible += 1
            name = b["name"]
            w = FONT.width(name)
            g.fill(bx - w // 2 - 4, by - 6, bx + w // 2 + 4, by + 7, 0xC0F4ECDC)
            g.fill(bx - w // 2 - 4, by + 6, bx + w // 2 + 4, by + 7, b["color"])
            g.text(bx - w // 2, by - 3, name, darken(b["color"], 0.75))

    def render_ring_labels(self, g):
        if self.zoom <= 0.22:
            return
        ang = math.radians(20.0)
        for i, ring in enumerate(self.RINGS):
            label = self.RING_NAMES[i]
            r = ring * self.zoom
            lx = jround(self.sx(0) + math.cos(ang) * r) - FONT.width(label) // 2
            ly = jround(self.sy(0) + math.sin(ang) * r) - 4
            g.fill(lx - 2, ly - 1, lx + FONT.width(label) + 2, ly + 9, 0x90F4ECDC)
            g.text(lx, ly, label, 0xA0503C24)

    def render_edges(self, g):
        focus = self.hovered if self.hovered is not None else self.selected
        heads = self.zoom >= 0.3
        hp = self.hover_path
        drawn = set()
        for n in self.data.nodes:
            for req_id in n["requires"]:
                req = self.data.node(req_id)
                if req is None:
                    continue
                cross = req["branch"] != n["branch"]
                strong = (focus is not None and (n["id"] == focus or req_id == focus)) \
                    or (self.hovered is not None and n["id"] in hp and req_id in hp)
                from_learned = self.status(req_id) == "LEARNED"
                both = from_learned and self.status(n["id"]) == "LEARNED"
                if strong:
                    color = GOLD if both else self.branch_color(n) if from_learned else LOCKED
                    if cross:
                        dashed(g, self.sx(req["x"]), self.sy(req["y"]), self.sx(n["x"]), self.sy(n["y"]),
                               color, 1.8)
                        if heads:
                            self.arrow(g, req, n, color, 1.8, True, True)
                    else:
                        self.arrow(g, req, n, color, 2.2, heads, False)
                elif cross:
                    self.stub(g, req, n, with_alpha(GOLD if both else EDGE_IDLE, 0x70 if not hp else 0x30))
                else:
                    color = with_alpha(GOLD, 0xA0) if both else with_alpha(EDGE_IDLE, 0x60)
                    if hp:
                        color = with_alpha(color, 0x28)
                    self.arrow(g, req, n, color, 1.0, False, False)
            for ex in n["excludes"]:
                key = "|".join(sorted([n["id"], ex]))
                other = self.data.node(ex)
                if other is None or key in drawn:
                    continue
                drawn.add(key)
                strong = focus is not None and focus in (n["id"], ex)
                dashed(g, self.sx(n["x"]), self.sy(n["y"]), self.sx(other["x"]), self.sy(other["y"]),
                       with_alpha(BLOCKED, 0xE0 if strong else 0x70 if not hp else 0x30),
                       1.8 if strong else 1.2)

    def stub(self, g, frm, to, color):
        x2, y2 = self.sx(to["x"]), self.sy(to["y"])
        dx, dy = self.sx(frm["x"]) - x2, self.sy(frm["y"]) - y2
        ln = math.sqrt(dx * dx + dy * dy)
        if ln < 1.0:
            return
        r = self.radius(to) + 2.0
        reach = min(ln - r, r + 14.0)
        if reach <= r:
            return
        ux, uy = dx / ln, dy / ln
        dashed(g, x2 + ux * r, y2 + uy * r, x2 + ux * reach, y2 + uy * reach, color, 1.0)

    def arrow(self, g, frm, to, color, thick, head, head_only):
        x1, y1 = self.sx(frm["x"]), self.sy(frm["y"])
        x2, y2 = self.sx(to["x"]), self.sy(to["y"])
        dx, dy = x2 - x1, y2 - y1
        ln = math.sqrt(dx * dx + dy * dy)
        if ln < 1.0:
            return
        ux, uy = dx / ln, dy / ln
        r1 = self.radius(frm) + 1.5
        r2 = self.radius(to) + 2.0
        if ln <= r1 + r2 + 2.0:
            return
        ax, ay = x1 + ux * r1, y1 + uy * r1
        bx, by = x2 - ux * r2, y2 - uy * r2
        if not head_only:
            line(g, ax, ay, bx, by, thick, color)
        if head:
            size = clamp(7.0 * self.zoom + 2.0, 4.0, 9.0)
            ang = math.atan2(uy, ux)
            for spread in (0.45, -0.45):
                line(g, bx - math.cos(ang + spread) * size, by - math.sin(ang + spread) * size,
                     bx, by, thick, color)

    def render_nodes(self, g):
        hp = self.hover_path
        for n in self.data.nodes:
            cx, cy = self.sx(n["x"]), self.sy(n["y"])
            r = self.radius(n)
            if cx + r < self.viewX or cx - r > self.viewX + self.viewW \
                    or cy + r < self.viewY or cy - r > self.viewY + self.viewH:
                continue
            status = self.status(n["id"])
            dim = bool(hp) and n["id"] not in hp
            focus = n["id"] in (self.hovered, self.selected)
            ring = ring_color(status)
            fill = self.fill_color(n, status)
            if status == "READY":
                disc(g, cx, cy, r + 2.5 + 0.5 * 2.0, with_alpha(READY, 0x50))  # pulse frozen at 0.5
            if focus:
                disc(g, cx, cy, r + 4.0, with_alpha(INK, 0x60))
            capstone = n["type"] == "capstone"
            disc(g, cx, cy, r + 1.0, 0x70201408)
            disc(g, cx, cy, r, with_alpha(ring, 0x60) if dim else ring)
            inner = r - (3.0 if capstone else 2.0)
            disc(g, cx, cy, inner, with_alpha(fill, 0x70) if dim else fill)
            if capstone and r > 8:
                dotted_circle(g, cx, cy, inner - 2.0, with_alpha(ring, 0xA0))
            if status == "PLANNED":
                dotted_circle(g, cx, cy, r + 2.0, with_alpha(PLANNED, 0xC0))
                hatch(g, cx, cy, inner, with_alpha(0xFF8C7F68, 0x40 if dim else 0x90))
            icon_size = jround(clamp(inner * 1.3, 6.0, 24.0))
            if inner >= 5.0:
                g.item(node_icon(n), cx - icon_size / 2.0, cy - icon_size / 2.0, icon_size)
                if dim or status in ("LOCKED", "PLANNED"):
                    disc(g, cx, cy, inner - 0.5, 0x70D9D0BE)
            if status == "BLOCKED":
                d = inner * 0.6
                line(g, cx - d, cy - d, cx + d, cy + d, 2.0, BLOCKED)
                line(g, cx - d, cy + d, cx + d, cy - d, 2.0, BLOCKED)
            if status == "LEARNED" and r > 7:
                disc(g, cx + r * 0.72, cy - r * 0.72, max(2.5, r * 0.28), GOLD)

    def fill_color(self, n, status):
        if status == "LEARNED":
            return GOLD_SOFT
        if status in ("READY", "AVAILABLE", "STUDYING"):
            return FRAME_INNER
        if status == "BLOCKED":
            return PAPER_DEEP
        return LOCKED_FILL

    def render_labels(self, g):
        all_ = self.zoom >= 0.62
        self.label_boxes = []
        for n in self.data.nodes:
            focus = n["id"] in (self.hovered, self.selected)
            if not all_ and not focus and not (self.zoom >= 0.4 and n["type"] == "capstone"):
                continue
            if self.hover_path and n["id"] not in self.hover_path and not focus:
                continue
            cx = self.sx(n["x"])
            cy = self.sy(n["y"]) + self.radius(n) + 3.0
            lines = split(n["name"], 84)
            shown = min(2, len(lines))
            w = max(FONT.width(lines[i]) for i in range(shown))
            if cx - w / 2.0 < self.viewX + 1 or cx + w / 2.0 > self.viewX + self.viewW - 1 \
                    or cy < self.viewY or cy + shown * 9 > self.viewY + self.viewH:
                if focus:
                    self.problems.append(f"focused node label hidden (at viewport edge): {n['name']}")
                continue
            if len(lines) > 2:
                self.problems.append(f"label '{n['name']}' needs {len(lines)} lines, only 2 shown")
            bx = jround(cx) - w // 2
            by = jround(cy)
            planned = self.status(n["id"]) == "PLANNED"
            extra = 9 if planned else 0
            g.fill(bx - 2, by - 1, bx + w + 2, by + shown * 9 + extra, 0xF0F8F2E4 if focus else 0xC8F4ECDC)
            if planned:
                tag = "Planned"
                if FONT.width(tag) > w + 4:
                    self.problems.append(f"'Planned' tag wider than label box: {n['name']}")
                g.text(jround(cx) - FONT.width(tag) // 2, by + shown * 9, tag, AMBER)
            self.label_boxes.append((n["id"], bx - 2, by - 1, bx + w + 2, by + shown * 9 + extra))
            for i in range(shown):
                col = INK_MUTED if self.status(n["id"]) == "LOCKED" else INK
                g.text(jround(cx) - FONT.width(lines[i]) // 2, by + i * 9, lines[i], col)
        # overlap check between label boxes and other nodes' discs
        for a in range(len(self.label_boxes)):
            for b in range(a + 1, len(self.label_boxes)):
                A, B = self.label_boxes[a], self.label_boxes[b]
                if A[1] < B[3] and B[1] < A[3] and A[2] < B[4] and B[2] < A[4]:
                    self.problems.append(f"labels overlap: {A[0]} / {B[0]}")

    def render_mini_legend(self, g):
        keys = [("Learned", GOLD), ("Ready to research", READY), ("Needs more (see panel)", AVAILABLE),
                ("Locked", LOCKED), ("Closed: other choice taken", BLOCKED),
                ("Planned: not in game yet", PLANNED), ("Being studied", STUDYING)]
        helps = ["Drag: pan", "Wheel: zoom", "Click: details", "Home: recentre", "Enter: research"]
        w = max(FONT.width(k) + 16 for k, _ in keys)
        help_w = max(FONT.width(h) for h in helps)
        two = self.viewW >= w + help_w + 30
        rows = max(len(keys), len(helps)) if two else len(keys) + len(helps) + 1
        plate_w = w + help_w + 22 if two else max(w, help_w) + 12
        plate_h = 16 + rows * 11
        x = self.viewX + 6
        y = self.viewY + self.viewH - plate_h - 6
        if y < self.viewY:
            self.problems.append("legend plate taller than the viewport")
        g.fill(x - 1, y - 1, x + plate_w + 1, y + plate_h + 1, RULE_STRONG)
        g.fill(x, y, x + plate_w, y + plate_h, PAPER)
        g.text(x + 6, y + 4, "Legend", INK_MUTED)
        ry = y + 15
        for label, col in keys:
            disc(g, x + 10, ry + 4, 4, col)
            if label.startswith("Planned"):
                dotted_circle(g, x + 10, ry + 4, 5.5, PLANNED)
            g.text(x + 18, ry, label, INK_SOFT)
            ry += 11
        hx = x + w + 16 if two else x + 6
        hy = y + 15 if two else ry + 4
        for h in helps:
            g.text(hx, hy, h, INK_MUTED)
            hy += 11
        self.legend_rect = (x, y, plate_w, plate_h)

    # -------------------------------------------------------- side panel
    def row(self, rows, kind, text=None, color=0, icon=None, progress=-1, height=10, link=None):
        rows.append(dict(kind=kind, text=text, color=color, icon=icon, progress=progress,
                         height=height, link=link))

    def rows_text(self, rows, text, color, w):
        for seq in split(text, w):
            self.row(rows, "text", [(seq, color)], color)

    def rows_header(self, rows, text):
        self.row(rows, "gap", height=4)
        self.row(rows, "header", [(text, INK_SOFT)], height=14)

    def rebuild_panel(self):
        rows = []
        w = self.sideW - 16
        n = self.data.node(self.selected) if self.selected else None
        self.btn_visible = n is not None
        if n is None:
            self.rows_text(rows, "Click a node to see what it costs and what it gives.", INK_SOFT, w)
            self.panel_rows = rows
            self.btn_visible = False
            return
        status = self.status(n["id"])
        b = self.data.branches.get(n["branch"])
        tier = self.data.tiers[n["tier"] - 1]["name"] if 1 <= n["tier"] <= len(self.data.tiers) else str(n["tier"])
        tname = {"capstone": "Capstone", "choice": "Pick one", "bonus": "Bonus"}.get(n["type"], "Unlock")
        self.chips = [SHORT_BRANCH.get(n["branch"], "Crown"), tier, tname]
        self.chip_color = darken(b["color"], 0.8)
        self.row(rows, "chips", height=15)
        if status == "PLANNED":
            self.row(rows, "planned", height=15)
        self.rows_text(rows, n["offers"], INK, w)
        self.row(rows, "gap", height=4)
        self.rows_text(rows, self.status_line(n, status), status_color(status), w)
        if status not in ("LEARNED", "STUDYING"):
            self.rows_header(rows, "Cost")
            costs = []
            if n.get("coins", 0) > 0:
                costs.append(("hearthstead:coins", "Coins", n["coins"]))
            for gl in n["goods"]:
                if "tag" in gl:
                    it, nm = {"minecraft:logs": ("minecraft:oak_log", "Any Log"),
                              "minecraft:planks": ("minecraft:oak_planks", "Any Planks"),
                              "minecraft:wool": ("minecraft:white_wool", "Any Wool")}.get(
                        gl["tag"], ("minecraft:paper", gl["tag"]))
                else:
                    it, nm = gl["item"], gl["item"].split(":")[1].replace("_", " ").title()
                costs.append((it, nm, gl["count"]))
            if not costs:
                self.rows_text(rows, "Free", INK_SOFT, w)
            have_list = self.have.get(n["id"], [])
            for i, (it, nm, count) in enumerate(costs):
                have = have_list[i] if i < len(have_list) else 0
                ok = have >= count
                txt = f"✔ {count} {nm}" if ok else f"{count} {nm} (have {min(have, 9999)})"
                if 8 + 18 + FONT.width(txt) > self.sideW - 2:
                    self.problems.append(f"cost row overflows side panel: {txt!r}")
                self.row(rows, "text", [(txt, FOREST if ok else DANGER)], FOREST if ok else DANGER,
                         icon=texture(it), height=16)
            if n.get("study_days", 0) > 0:
                self.rows_text(rows, f"Then {n['study_days']} in-game day(s) of study", INK_SOFT, w)
        gates = self.gates.get(n["id"], [])
        if gates and status != "LEARNED":
            self.rows_header(rows, "Milestones")
            for txt, prog, target in gates:
                met = prog >= target
                self.rows_text(rows, txt, FOREST if met else INK_SOFT, w)
                self.row(rows, "bar", color=FOREST if met else AMBER,
                         progress=min(1.0, prog / max(1, target)), height=5)
        if n["requires"]:
            self.rows_header(rows, "Requires")
            for req in n["requires"]:
                r = self.data.node(req)
                ok = self.status(req) == "LEARNED"
                col = FOREST if ok else INK
                segs = [(("✔ " if ok else "• ") + (r["name"] if r else req), col)]
                if r is not None and r["branch"] != n["branch"]:
                    segs.append((" (" + SHORT_BRANCH.get(r["branch"], "Crown") + ")", GRAY))
                if 8 + seg_width(segs) > self.sideW - 2:
                    self.problems.append(f"requires row overflows side panel (not wrapped): {segs}")
                self.row(rows, "text", segs, col, link=req)
        if n["excludes"]:
            self.rows_header(rows, "Pick one")
            for ex in n["excludes"]:
                o = self.data.node(ex)
                for seq in split(f"Choosing this locks {o['name'] if o else ex} forever", w):
                    self.row(rows, "text", [(seq, BLOCKED)], BLOCKED, link=ex)
        effects = [f"Build plan: {b_}" for b_ in EFFECT_BUILDINGS.get(n["id"], [])] + \
                  [f"Mayor sells the {p}" for p in EFFECT_PROFESSIONS.get(n["id"], [])]
        if effects:
            self.rows_header(rows, "In game")
            for e in dict.fromkeys(effects):
                self.rows_text(rows, "• " + e, INK_SOFT, w)
        if n.get("flavor"):
            self.row(rows, "gap", height=4)
            self.rows_text(rows, n["flavor"], INK_MUTED, w)
        self.panel_rows = rows
        self.btn_visible = status != "LEARNED"
        self.btn_active = status == "READY"
        self.btn_label = "Research" if status == "READY" else \
            "Studying…" if status == "STUDYING" else "Can't research yet"

    def status_line(self, n, status):
        return {"LEARNED": "Learned", "READY": "Ready to research",
                "PLANNED": "Planned: not in the game yet",
                "BLOCKED": "Closed: you chose Longbows",
                "LOCKED": "Needs its requirements first",
                "AVAILABLE": "Not enough Coins or goods (Banner, your pack and Warehouse)."}.get(status, "")

    def render_side(self, g):
        x, y, h, sideW = self.sideX, self.viewY, self.viewH, self.sideW
        g.fill(x - 3, y + 4, x - 2, y + h - 4, RULE_STRONG)
        g.fill(x, y, x + sideW, y + h, 0x40D8CCB2)
        n = self.data.node(self.selected) if self.selected else None
        ty = y + 6
        if n is not None:
            g.item(node_icon(n), x + 6, ty)
            title = split(n["name"], sideW - 34)
            for i in range(min(2, len(title))):
                g.text(x + 26, ty + (4 if len(title) == 1 else i * 9), title[i], INK)
            ty += 22
            g.fill(x + 6, ty - 3, x + sideW - 6, ty - 2, RULE)
        bottom = self.btn[1] - 4 if self.btn_visible else y + h - 6
        g.enable_scissor(x + 1, ty, x + sideW - 1, bottom)
        total = sum(r["height"] for r in self.panel_rows)
        max_scroll = max(0, total - (bottom - ty))
        ry = ty
        for row in self.panel_rows:
            if ry + row["height"] >= ty and ry <= bottom:
                k = row["kind"]
                if k == "bar":
                    g.fill(x + 8, ry + 1, x + sideW - 8, ry + 3, TRACK)
                    g.fill(x + 8, ry + 1, x + 8 + jround((sideW - 16) * row["progress"]), ry + 3, row["color"])
                elif k == "chips":
                    cx = x + 8
                    for i, chip in enumerate(self.chips):
                        cw = FONT.width(chip) + 6
                        if cx + cw > x + sideW - 6:
                            self.problems.append(f"chip dropped: {chip}")
                            break
                        cx += badge(g, chip, cx, ry + 1, self.chip_color if i == 0 else INK_MUTED) + 3
                elif k == "planned":
                    tag = "PLANNED: not in the game yet"
                    if 8 + FONT.width(tag) + 6 > sideW - 2:
                        self.problems.append("PLANNED badge wider than side panel")
                    badge(g, tag, x + 8, ry + 1, AMBER)
                elif k == "header":
                    # Ui2Frame.heading(g, font, cache(HEADING), text, x + 8, ry, sideW - 16)
                    txt = row["text"][0][0]
                    runs = small_caps(txt, "mid", "small")
                    serif_draw(g, runs, x + 8, ry + 2, INK_SOFT)
                    rx = x + 8 + serif_width(runs, g.s) + 5
                    if rx < x + 8 + sideW - 16:
                        g.fill(rx, ry + 6, x + 8 + sideW - 16, ry + 7, RULE)
                elif k == "text":
                    tx = x + 8
                    if row["icon"] is not None:
                        g.item(row["icon"], tx, ry)
                        tx += 18
                    text_segments(g, tx, ry + (4 if row["icon"] is not None else 1), row["text"])
            ry += row["height"]
        g.disable_scissor()
        if max_scroll > 0:
            track = bottom - ty
            thumb = max(10, track * track // (track + max_scroll))
            g.fill(x + sideW - 4, ty, x + sideW - 2, ty + thumb, RULE_STRONG)
            self.problems.append(f"side panel needs scrolling: {max_scroll}px of {total}px hidden "
                                 f"(visible {bottom - ty}px)")

    def render_widgets(self, g):
        # researchButton: Ui2Button.banner (renderBanner)
        if self.btn_visible:
            x, y, w, h = self.btn
            active = self.btn_active
            g.fill(x, y, x + w, y + h, BURGUNDY_DARK if active else DISABLED_BORDER)
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFC9A46A if active else DISABLED_BORDER)
            g.fill(x + 2, y + 2, x + w - 2, y + h - 2, BURGUNDY if active else DISABLED_FILL)
            if active:
                g.fill(x + 2, y + 2, x + w - 2, y + 3, BURGUNDY_HIGHLIGHT)
            ink = ON_BURGUNDY if active else INK_DISABLED
            left = x + 6
            if not active:
                lock_glyph(g, left, y + (h - 7) // 2, INK_DISABLED)
                left += 8
            right = x + w - 10
            cy = y + h // 2
            for i in range(3):
                g.fill(right + i, cy - 3 + i, right + i + 1, cy - 2 + i, ink)
                g.fill(right + i, cy + 2 - i, right + i + 1, cy + 3 - i, ink)
            g.fill(right + 3, cy - 1, right + 4, cy + 1, ink)
            room = right - 4 - left
            label = self.btn_label
            runs = small_caps(label, "mid", "small")
            while serif_width(runs, g.s) > room and len(label) > 1:
                label = label[:-1].rstrip()
                runs = small_caps(label + "…", "mid", "small")
                self.problems.append("research label ellipsised")
            tx = left + max(0, (room - serif_width(runs, g.s)) // 2)
            serif_draw(g, runs, tx, y + (h - 8) // 2, ink)
        # Ui2Frame.closeKey then the "?" legend key: Ui2WoodKey, resting state
        for (kx, ky, kw, kh), label in ((self.close_key, "×"), (self.legend_key, "?")):
            outline(g, kx, ky, kw, kh, PLATE_HIGHLIGHT)
            tw = FONT.width(label)
            g.text(kx + (kw - tw + 1) // 2, ky + (kh - 8) // 2 + 1, label, TEXT_ON_WOOD_MUTED)


def badge(g, text, x, y, c):
    """Ui2Surface.badge; returns its width."""
    w = FONT.width(text) + 6
    g.fill(x, y, x + w, y + 11, PAPER_DEEP)
    g.fill(x, y, x + w, y + 1, c)
    g.fill(x, y + 10, x + w, y + 11, c)
    g.fill(x, y, x + 1, y + 11, c)
    g.fill(x + w - 1, y, x + w, y + 11, c)
    g.text(x + 3, y + 2, text, c)
    return w


def hatch(g, cx, cy, r, c):
    top, bottom = int(math.floor(cy - r)), int(math.ceil(cy + r))
    for y in range(top, bottom):
        dy = y + 0.5 - cy
        span = r * r - dy * dy
        if span <= 0:
            continue
        half = math.sqrt(span)
        for x in range(jround(cx - half), jround(cx + half)):
            if ((x + y) & 3) == 0:
                g.fill(x, y, x + 1, y + 1, c)


def banner_cloth(g, x, top, w, bottom, notch, s):
    g.fill(x + 2, top + 2, x + w + 2, bottom - notch + 2, 0x40100804)
    g.fill(x, top, x + w, bottom - notch, BURGUNDY_DARK)
    g.fill(x + 1, top, x + w - 1, bottom - notch, BURGUNDY)
    g.fill(x + 1, top, x + 2, bottom - notch, BURGUNDY_HIGHLIGHT)
    half = w // 2
    for i in range(notch):
        row = bottom - notch + i
        cut = i + 1
        g.fill(x, row, x + half - cut, row + 1, BURGUNDY_DARK)
        g.fill(x + 1, row, x + half - cut, row + 1, BURGUNDY)
        g.fill(x + half + cut, row, x + w, row + 1, BURGUNDY_DARK)
        g.fill(x + half + cut, row, x + w - 1, row + 1, BURGUNDY)
    g.fill(x + 2, top + 2, x + w - 2, top + 3, GOLD_SOFT)
    g.fill(x + 2, top + 2, x + 3, bottom - notch - 1, GOLD_SOFT)
    g.fill(x + w - 3, top + 2, x + w - 2, bottom - notch - 1, GOLD_SOFT)
    tree(g, x + w // 2, top + 6 + s, s, 0xFFEBDDC0)


def tree(g, cx, ty, s, c):
    g.fill(cx - s, ty, cx + s, ty + s, c)
    g.fill(cx - 2 * s, ty + s, cx + 2 * s, ty + 2 * s, c)
    g.fill(cx - 3 * s, ty + 2 * s, cx + 3 * s, ty + 4 * s, c)
    g.fill(cx - 2 * s, ty + 4 * s, cx + 2 * s, ty + 5 * s, c)
    g.fill(cx - s // 2 - (1 if s == 1 else 0), ty + 5 * s, cx + s // 2 + 1, ty + 8 * s, c)
    g.fill(cx - 3 * s, ty + 8 * s, cx + 3 * s, ty + 8 * s + max(1, s // 2), c)


def crest(g, x, y, w, h):
    g.fill(x - 3, y + 2, x + w + 3, y + 5, IRON_DARK)
    g.fill(x - 2, y + 3, x + w + 2, y + 4, IRON_LIGHT)
    banner_cloth(g, x, y + 5, w, y + h, 7, 2 if w >= 24 else 1)


def lock_glyph(g, x, y, c):
    g.fill(x + 1, y, x + 4, y + 1, c)
    g.fill(x + 1, y, x + 2, y + 3, c)
    g.fill(x + 3, y, x + 4, y + 3, c)
    g.fill(x, y + 3, x + 5, y + 7, c)


def ring_color(status):
    return {"LEARNED": GOLD, "READY": READY, "AVAILABLE": AVAILABLE, "BLOCKED": BLOCKED,
            "PLANNED": PLANNED, "STUDYING": STUDYING}.get(status, LOCKED)


def status_color(status):
    return {"LEARNED": P_GOLD, "READY": FOREST, "AVAILABLE": AMBER, "STUDYING": AMBER,
            "BLOCKED": DANGER}.get(status, INK_MUTED)



# --------------------------------------------------------- draw helpers ---

def disc(g, cx, cy, r, c):
    if r <= 0.5:
        return
    top = int(math.floor(cy - r))
    bottom = int(math.ceil(cy + r))
    for y in range(top, bottom):
        dy = y + 0.5 - cy
        span = r * r - dy * dy
        if span <= 0:
            continue
        half = math.sqrt(span)
        x0, x1 = jround(cx - half), jround(cx + half)
        if x1 > x0:
            g.fill(x0, y, x1, y + 1, c)


def dotted_circle(g, cx, cy, r, c):
    if r < 2:
        return
    dots = clamp(jround(r * 0.9), 12, 720)
    for i in range(0, dots, 2):
        a = i * math.pi * 2.0 / dots
        x = jround(cx + math.cos(a) * r)
        y = jround(cy + math.sin(a) * r)
        g.fill(x, y, x + 1, y + 1, c)


def line(g, x1, y1, x2, y2, thick, c):
    dx, dy = x2 - x1, y2 - y1
    ln = math.sqrt(dx * dx + dy * dy)
    if ln < 0.5:
        return
    nx, ny = -dy / ln * thick / 2.0, dx / ln * thick / 2.0
    g.poly([(x1 + nx, y1 + ny), (x2 + nx, y2 + ny), (x2 - nx, y2 - ny), (x1 - nx, y1 - ny)], c)


def dashed(g, x1, y1, x2, y2, c, thick):
    dx, dy = x2 - x1, y2 - y1
    ln = math.sqrt(dx * dx + dy * dy)
    dashes = max(1, jround(ln / 7.0))
    for i in range(0, dashes, 2):
        a = i / dashes
        b = min(1.0, (i + 1) / dashes)
        line(g, x1 + dx * a, y1 + dy * a, x1 + dx * b, y1 + dy * b, thick, c)


# ---------------------------------------------------------- ui2 chrome ----

def outline(g, x, y, w, h, c):
    g.fill(x, y, x + w, y + 1, c)
    g.fill(x, y + h - 1, x + w, y + h, c)
    g.fill(x, y + 1, x + 1, y + h - 1, c)
    g.fill(x + w - 1, y + 1, x + w, y + h - 1, c)


def panel(g, x, y, w, h):
    g.fill(x + 3, y + h, x + w + 3, y + h + 3, SHADOW)
    g.fill(x + w, y + 3, x + w + 3, y + h, SHADOW)
    g.fill(x, y, x + w, y + h, WALNUT_DARK)
    g.fill(x + 1, y + 1, x + w - 1, y + h - 1, WALNUT)
    grain(g, x + 1, y + 1, w - 2, h - 2)
    f = FRAME
    g.fill(x + 1, y + 1, x + w - 1, y + 2, WALNUT_LIGHT)
    g.fill(x + 1, y + 1, x + 2, y + h - 1, WALNUT_LIGHT)
    g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PLATE_SHADOW)
    g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, PLATE_SHADOW)
    outline(g, x + f - 1, y + f - 1, w - (f - 1) * 2, h - (f - 1) * 2, PLATE_SHADOW)
    outline(g, x + f, y + f, w - f * 2, h - f * 2, 0x40F1E5CB)
    bracket(g, x, y, False, False)
    bracket(g, x + w, y, True, False)
    bracket(g, x, y + h, False, True)
    bracket(g, x + w, y + h, True, True)


def grain(g, x, y, w, h):
    for row in range(1, h - 1, 3):
        seed = row * 7919
        px = x + 2 + (seed >> 3) % 17
        while px < x + w - 4:
            seed = (seed * 1103515245 + 12345) & 0xFFFFFFFF
            ln = 10 + ((seed >> 16) & 31)
            col = GRAIN_LIGHT if ((seed >> 8) & 3) == 0 else WALNUT_GRAIN
            g.fill(px, y + row, min(px + ln, x + w - 2), y + row + 1, col)
            px += ln + 6 + ((seed >> 20) & 15)


def bracket(g, cx, cy, fx, fy):
    arm, t = 14, 5
    plate(g, cx - arm if fx else cx, cy - t if fy else cy, arm, t)
    plate(g, cx - t if fx else cx, cy - arm if fy else cy, t, arm)
    rivet(g, cx - arm + 2 if fx else cx + arm - 4, cy - 3 if fy else cy + 2)
    rivet(g, cx - 3 if fx else cx + 2, cy - arm + 2 if fy else cy + arm - 4)


def plate(g, x, y, w, h):
    g.fill(x, y, x + w, y + h, IRON_DARK)
    g.fill(x + 1, y + 1, x + w - 1, y + h - 1, IRON)
    g.fill(x + 1, y + 1, x + w - 1, y + 2, IRON_LIGHT)
    g.fill(x + 1, y + 1, x + 2, y + h - 1, IRON_LIGHT)


def rivet(g, x, y):
    g.fill(x, y, x + 2, y + 2, IRON_DARK)
    g.fill(x, y, x + 1, y + 1, IRON_LIGHT)


_ATLAS = None


def paper(g, x, y, w, h):
    """HearthMaterials.paper: quadrant (627,0) of the atlas, 128 GUI px per tile."""
    global _ATLAS
    if _ATLAS is None:
        p = os.path.join(MOD_TEX, "gui", "materials", "premium_atlas.png")
        _ATLAS = Image.open(p).convert("RGBA").crop((627, 0, 1254, 627)) if os.path.exists(p) else False
    if _ATLAS:
        tile, q, s = 128, 627, g.s
        for dy in range(0, h, tile):
            for dx in range(0, w, tile):
                dw, dh = min(tile, w - dx), min(tile, h - dy)
                src = _ATLAS.crop((0, 0, jround(dw * q / tile), jround(dh * q / tile)))
                g.comp(src.resize((dw * s, dh * s), Image.NEAREST), (x + dx) * s, (y + dy) * s)
    else:
        g.fill(x, y, x + w, y + h, PAPER)
    g.fill(x, y, x + w, y + h, PAPER_OVERLAY)


def parchment(g, x, y, w, h):
    g.fill(x - 1, y - 1, x + w + 1, y + h + 1, PLATE_SHADOW)
    paper(g, x, y, w, h)
    g.fill(x, y, x + w, y + h, PAPER_TEXTURE_VEIL)
    g.fill(x, y, x + w, y + 1, FRAME_INNER)
    g.fill(x, y, x + 1, y + h, FRAME_INNER)
    g.fill(x, y + h - 1, x + w, y + h, 0x30201408)
    g.fill(x + w - 1, y, x + w, y + h, 0x30201408)


def counter_box(g, x, y, w, h):
    g.fill(x, y, x + w, y + h, INSET_DARK)
    g.fill(x, y, x + w, y + 1, PLATE_SHADOW)
    g.fill(x, y, x + 1, y + h, PLATE_SHADOW)
    g.fill(x, y + h - 1, x + w, y + h, PLATE_HIGHLIGHT)
    g.fill(x + w - 1, y, x + w, y + h, PLATE_HIGHLIGHT)


def render_background(g, gw, gh):
    """Fake blurred world + vanilla in-world menu background."""
    small = Image.new("RGB", (gw // 4, gh // 4))
    d = ImageDraw.Draw(small)
    sw, sh = small.size
    for yy in range(sh):
        t = yy / sh
        if t < 0.55:
            c = (int(120 + 40 * t), int(160 + 30 * t), int(215 - 20 * t))
        else:
            u = (t - 0.55) / 0.45
            c = (int(90 - 30 * u), int(130 - 40 * u), int(60 - 20 * u))
        d.line([(0, yy), (sw, yy)], fill=c)
    d.rectangle([sw * 0.15, sh * 0.42, sw * 0.28, sh * 0.6], fill=(120, 90, 60))
    d.rectangle([sw * 0.62, sh * 0.38, sw * 0.8, sh * 0.62], fill=(140, 120, 100))
    small = small.filter(ImageFilter.GaussianBlur(3))
    g.img.paste(small.resize(g.img.size, Image.BILINEAR), (0, 0))
    tex = jar_png("assets/minecraft/textures/gui/inworld_menu_background.png")
    if tex is not None:
        t = tex.resize((32 * g.s, 32 * g.s), Image.NEAREST)
        for yy in range(0, g.img.height, t.height):
            for xx in range(0, g.img.width, t.width):
                g.comp(t, xx, yy)
    else:
        g.fill(0, 0, gw, gh, 0x80101010)


# ------------------------------------------------------------------ main --

def main():
    global FONT, JAR
    ap = argparse.ArgumentParser(description=__doc__.split(chr(10))[0])
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args()
    FONT = mcfont.McFont.load()
    JAR = mcfont.find_client_jar()
    os.makedirs(args.out, exist_ok=True)
    data = Data()
    base = fake_state(data, False)
    zoomed = fake_state(data, True)
    have = {"shield_doctrine": [23, 14, 6], "knights": [23, 1, 4, 9]}
    gates = {"shield_doctrine": [("Guard combat XP earned: 40/40", 40, 40)]}
    s = 3
    shots = [
        # name, state, selected, hovered, cam, zoom, legend
        ("v2_overview.png", base, "shield_doctrine", None, None, None, False),
        ("v2_zoom.png", zoomed, "shield_doctrine", "shield_doctrine", (-10, -330), 0.75, False),
        ("v2_selected.png", base, "knights", None, (-60, -800), 0.7, True),
    ]
    for name, state, sel, hov, cam, zoom, legend in shots:
        scr = TechTreeScreen(data, 1920 // s, 1080 // s, state, sel, hov, cam, zoom,
                             have=have, gates=gates, legend=legend)
        img = scr.render(s)
        path = os.path.join(args.out, name)
        img.save(path)
        print(f"{path}  gui={scr.width}x{scr.height} view={scr.viewX},{scr.viewY} {scr.viewW}x{scr.viewH} "
              f"side={scr.sideX}+{scr.sideW} zoom={scr.zoom:.3f} branch_labels={scr.branch_labels_visible}")
        for p in dict.fromkeys(scr.problems):
            print("   ! " + p)


if __name__ == "__main__":
    main()
