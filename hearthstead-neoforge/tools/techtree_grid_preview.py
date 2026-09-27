#!/usr/bin/env python3
"""Offscreen stills of the SWIMLANE tech tree (v3 screen layout, 26 Sep).

Rows = branches (bands), columns = ranks (Hamlet -> Kingdom), node cards with
icon + name, short elbow connectors inside a band only, cross-branch needs as
a corner marker (+ chip on focus), pick-one pairs joined by an OR plate.

The layout algorithm here is the SPEC for client/screen/TechTreeGridLayout
(Java): keep both in step. Reuses tools/techtree_preview.py for the frame,
side panel, font and icons.

    python tools/techtree_grid_preview.py            # writes plan/techtree/stills/v3_*.png
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mcfont  # noqa: E402
import techtree_preview as tp  # noqa: E402

BANDS = ["crown", "watch", "commons", "craft", "logistics"]
BAND_NAMES = {"crown": "Crown", "watch": "Watch & Defense", "commons": "Commons & Household",
              "craft": "Craft & Production", "logistics": "Logistics"}
BAND_ICONS = {"crown": "minecraft:white_banner", "watch": "minecraft:iron_sword",
              "commons": "minecraft:bread", "craft": "minecraft:iron_pickaxe",
              "logistics": "minecraft:chest"}
CARD_W, CARD_H = 96, 22
CELL_W, CELL_H = 114, 28
HEAD_W = 66          # sticky band header column
COL_HEAD_H = 13      # sticky rank header strip
CHIPS_H = 15         # filter chips row
BAND_GAP = 3


# ------------------------------------------------------------------ layout

def layout(data, bands, rows_cap):
    """Returns (cells{id:(col,row)}, band_rows{band:n}, tier_cols[(start,width)] per tier 1..5)."""
    nodes = [n for n in data.nodes if n["branch"] in bands]
    by = {n["id"]: n for n in nodes}

    depth = {}

    def dep(n):
        if n["id"] in depth:
            return depth[n["id"]]
        ds = [dep(by[r]) + 1 for r in n["requires"]
              if r in by and by[r]["branch"] == n["branch"] and by[r]["tier"] == n["tier"]]
        depth[n["id"]] = max(ds) if ds else 0
        return depth[n["id"]]

    for n in nodes:
        dep(n)
    band_rows = {b: (1 if b == "crown" else rows_cap) for b in bands}
    # Per (band, tier): assign local (col, row) inside the tier block.
    local = {}
    tier_width = {t: 1 for t in range(1, 6)}
    row_of = {}
    for t in range(1, 6):
        for b in bands:
            group = [n for n in data.nodes if n["branch"] == b and n["tier"] == t and n["id"] in by]
            if not group:
                continue
            R = band_rows[b]
            col, row = 0, 0
            for dlev in sorted({depth[n["id"]] for n in group}):
                level = [n for n in group if depth[n["id"]] == dlev]

                def key(n):
                    ps = [row_of[r] for r in n["requires"] if r in row_of and by.get(r, {}).get("branch") == b]
                    return (sum(ps) / len(ps) if ps else 99, data.nodes.index(n))
                level.sort(key=key)
                # keep pick-one partners adjacent: pull partner right after its mate
                ordered, seen = [], set()
                for n in level:
                    if n["id"] in seen:
                        continue
                    ordered.append([n])
                    seen.add(n["id"])
                    for ex in n["excludes"]:
                        m = by.get(ex)
                        if m is not None and m in level and ex not in seen:
                            ordered[-1].append(m)
                            seen.add(ex)
                if row != 0:
                    col, row = col + 1, 0      # a new depth starts a new column
                for unit in ordered:
                    if row + len(unit) > R and row != 0:
                        col, row = col + 1, 0
                    for n in unit:
                        local[n["id"]] = (col, row)
                        row_of[n["id"]] = row
                        row += 1
                        if row >= R and n is not unit[-1]:
                            col, row = col + 1, 0
                    if row >= R:
                        col, row = col + 1, 0
            used = col + (1 if row > 0 else 0)
            tier_width[t] = max(tier_width[t], used)
    for b in bands:
        used = [lr for i, (lc, lr) in local.items() if by[i]["branch"] == b]
        band_rows[b] = (max(used) + 1) if used else 1
    starts, c = {}, 0
    for t in range(1, 6):
        starts[t] = c
        c += tier_width[t]
    cells = {i: (starts[by[i]["tier"]] + lc, lr) for i, (lc, lr) in local.items()}
    return cells, band_rows, {t: (starts[t], tier_width[t]) for t in range(1, 6)}, c


# ------------------------------------------------------------------ screen

class GridScreen(tp.TechTreeScreen):
    def __init__(self, *a, filt="all", **k):
        self.filt = filt
        super().__init__(*a, **k)

    def init(self):
        super().init()
        s_bands = BANDS if self.filt == "all" else [self.filt]
        self.bands = s_bands
        top = self.viewY + CHIPS_H + COL_HEAD_H
        avail = self.viewY + self.viewH - top - 2
        # Rows per band: the most that still fit vertically at the crisp
        # "GUI 2 look" zoom (fewer columns = less panning).
        want = 2.0 / getattr(self, "gui", 3)
        rows_cap = 2
        for cap in range(2, 12):
            rows = sum((1 if b == "crown" else cap) * CELL_H + BAND_GAP for b in s_bands)
            if rows * want <= avail:
                rows_cap = cap
        self.cells, self.band_rows, self.tiers, self.ncols = layout(self.data, s_bands, rows_cap)
        total_h = sum(self.band_rows[b] * CELL_H + BAND_GAP for b in s_bands)
        # zoom: fit bands vertically, never below 2 physical px per text px
        self.gui = getattr(self, "gui", 3)
        min_zoom = max(0.5, 2.0 / self.gui)
        self.zoom = tp.clamp(avail / total_h, min_zoom, 1.0)
        self.camX = 0.0   # world x at left edge of grid area
        self.camY = 0.0

    def fit_view(self):
        """Crisp zoom (whole physical px per font px): the 'GUI 2 look' 2/gui if the
        bands fit vertically, else the largest crisp step that fits; then centre the
        current rank column."""
        top = self.viewY + CHIPS_H + COL_HEAD_H
        avail = self.viewY + self.viewH - top - 2
        total_h = sum(self.band_rows[b] * CELL_H + BAND_GAP for b in self.bands)
        steps = [k / self.gui for k in range(1, 2 * self.gui + 1)]
        want = 2.0 / self.gui
        fit = [z for z in steps if total_h * z <= avail]
        self.zoom = want if want in fit or not fit else max(fit)
        start, w = self.tiers[2]
        centre = (start + w / 2.0) * CELL_W
        visible = (self.viewW - HEAD_W) / self.zoom
        self.camX = max(0.0, min(centre - visible / 2, self.ncols * CELL_W - visible))

    # world -> screen
    def gx(self, wx):
        return self.viewX + HEAD_W + (wx - self.camX) * self.zoom

    def gy(self, wy):
        return self.viewY + CHIPS_H + COL_HEAD_H + (wy - self.camY) * self.zoom

    def band_top(self, b):
        y = 0
        for bb in self.bands:
            if bb == b:
                return y
            y += self.band_rows[bb] * CELL_H + BAND_GAP
        return y

    def card_rect(self, i):
        col, row = self.cells[i]
        b = self.data.node(i)["branch"]
        wx = col * CELL_W + (CELL_W - CARD_W) / 2
        wy = self.band_top(b) + row * CELL_H + (CELL_H - CARD_H) / 2
        return wx, wy

    def render(self, s):
        g = tp.G(self.width, self.height, s)
        tp.render_background(g, self.width, self.height)
        f = self.frame
        tp.panel(g, f["x"], f["y"], f["w"], f["h"])
        h = f["header"]
        g.fill(h.x, f["rule_y"], h.right, f["rule_y"] + 1, tp.PLATE_SHADOW)
        g.fill(h.x, f["rule_y"] + 1, h.right, f["rule_y"] + 2, tp.PLATE_HIGHLIGHT)
        p = f["page"]
        tp.parchment(g, p.x, p.y, p.w, p.h)
        c = f["crest"]
        tp.crest(g, c.x, c.y, c.w, c.h)
        self.render_header(g)
        vx, vy, vw, vh = self.viewX, self.viewY, self.viewW, self.viewH
        g.enable_scissor(vx, vy, vx + vw, vy + vh)
        self.render_bands(g)
        self.render_grid_edges(g)
        self.render_cards(g)
        self.render_col_headers(g)
        self.render_band_headers(g)
        self.render_chips(g)
        if self.legend_open:
            self.render_mini_legend(g)
        g.disable_scissor()
        self.rebuild_panel()
        self.render_side(g)
        self.render_widgets(g)
        return g.img.convert("RGB")

    # ---- pieces
    def render_chips(self, g):
        x = self.viewX + 3
        y = self.viewY + 2
        g.fill(self.viewX, self.viewY, self.viewX + self.viewW, self.viewY + CHIPS_H, tp.PAPER_DEEP)
        for key, label in [("all", "All")] + [(b, BAND_NAMES[b].split(" ")[0]) for b in BANDS]:
            w = tp.FONT.width(label) + 8
            on = key == self.filt
            col = self.data.branches[key]["color"] if key in self.data.branches else tp.INK_SOFT
            g.fill(x, y, x + w, y + 11, col if on else tp.FRAME_INNER)
            tp.outline(g, x, y, w, 11, col if on else tp.RULE_STRONG)
            g.text(x + 4, y + 2, label, tp.ON_ACCENT if on else tp.INK_SOFT)
            x += w + 3
        g.fill(self.viewX, self.viewY + CHIPS_H - 1, self.viewX + self.viewW, self.viewY + CHIPS_H, tp.RULE_STRONG)

    def render_col_headers(self, g):
        y = self.viewY + CHIPS_H
        g.fill(self.viewX, y, self.viewX + self.viewW, y + COL_HEAD_H, tp.with_alpha(tp.PAPER, 0xF0))
        cur = 2  # Village in the fake state
        for t in range(1, 6):
            start, w = self.tiers[t]
            x0 = self.gx(start * CELL_W)
            x1 = self.gx((start + w) * CELL_W)
            name = self.data.tiers[t - 1]["name"]
            if t == cur:
                g.fill(x0, y, x1, y + COL_HEAD_H, tp.with_alpha(tp.P_GOLD, 0x40))
            tw = tp.FONT.width(name)
            g.text((x0 + x1) / 2 - tw / 2, y + 3, name, tp.INK if t == cur else tp.INK_MUTED)
            g.fill(x1, y, x1 + 1, self.viewY + self.viewH, tp.with_alpha(tp.RULE_STRONG, 0xC0))
        g.fill(self.viewX, y + COL_HEAD_H - 1, self.viewX + self.viewW, y + COL_HEAD_H, tp.RULE_STRONG)

    def render_bands(self, g):
        for i, b in enumerate(self.bands):
            y0 = self.gy(self.band_top(b))
            y1 = self.gy(self.band_top(b) + self.band_rows[b] * CELL_H)
            col = self.data.branches[b]["color"]
            g.fill(self.viewX, y0, self.viewX + self.viewW, y1, tp.with_alpha(col, 0x16 if i % 2 == 0 else 0x0E))
            g.fill(self.viewX, y1, self.viewX + self.viewW, y1 + 1, tp.with_alpha(col, 0x50))

    def render_band_headers(self, g):
        x = self.viewX
        g.fill(x, self.viewY + CHIPS_H, x + HEAD_W, self.viewY + self.viewH, tp.PAPER)
        g.fill(x + HEAD_W - 1, self.viewY + CHIPS_H, x + HEAD_W, self.viewY + self.viewH, tp.RULE_STRONG)
        for b in self.bands:
            y0 = self.gy(self.band_top(b))
            y1 = self.gy(self.band_top(b) + self.band_rows[b] * CELL_H)
            col = self.data.branches[b]["color"]
            g.fill(x, y0, x + HEAD_W - 1, y1, tp.with_alpha(col, 0x30))
            g.fill(x, y0, x + 3, y1, col)
            words = BAND_NAMES[b].replace(" & ", " &\n").split("\n")
            lines = []
            for wline in words:
                lines += tp.split(wline, HEAD_W - 12)
            ty = (y0 + y1) / 2 - (len(lines) * 9 + (0 if y1 - y0 < 30 else 12)) / 2
            if y1 - y0 >= 30:
                g.item(tp.texture(BAND_ICONS[b]), x + 6, ty, 11)
                ty += 12
            for ln in lines:
                g.text(x + 6, ty, ln, tp.darken(col, 0.7))
                ty += 9

    def render_grid_edges(self, g):
        focus = self.hovered or self.selected
        for n in self.data.nodes:
            i = n["id"]
            if i not in self.cells:
                continue
            for r in n["requires"]:
                p = self.data.node(r)
                if r not in self.cells or p["branch"] != n["branch"]:
                    continue
                strong = focus in (i, r) or (i in self.hover_path and r in self.hover_path and self.hovered)
                ax, ay = self.card_rect(r)
                bx, by = self.card_rect(i)
                x1 = self.gx(ax + CARD_W)
                y1 = self.gy(ay + CARD_H / 2)
                x2 = self.gx(bx)
                y2 = self.gy(by + CARD_H / 2)
                learned = self.status(r) == "LEARNED"
                colr = (tp.P_GOLD if learned else tp.INK_MUTED) if strong else tp.with_alpha(
                    tp.P_GOLD if learned and self.status(i) == "LEARNED" else tp.INK_MUTED, 0x80)
                t = 2 if strong else 1
                if x2 <= x1:     # same column (row below): drop from card bottom
                    xm = self.gx(ax + 10)
                    g.fill(xm, self.gy(ay + CARD_H), xm + t, y2, colr)
                    g.fill(xm, y2, x2, y2 + t, colr)
                    continue
                mid = x2 - (CELL_W - CARD_W) / 2 * self.zoom
                g.fill(x1, y1, mid, y1 + t, colr)
                g.fill(mid, min(y1, y2), mid + t, max(y1, y2) + t, colr)
                g.fill(mid, y2, x2, y2 + t, colr)
                g.fill(x2 - 3, y2 - 2 + t // 2, x2 - 2, y2 + 3 + t // 2, colr)
                g.fill(x2 - 2, y2 - 1 + t // 2, x2 - 1, y2 + 2 + t // 2, colr)

    def render_cards(self, g):
        self._chip = None
        self._render_cards(g)
        if self._chip:
            x, y, others, oc = self._chip
            chip = "needs: " + ", ".join(self.data.node(r)["name"] for r in others)
            k = self.zoom
            cw = (tp.FONT.width(chip) + 6) * k
            g.fill(x - 1, y - 12 * k - 1, x + cw + 1, y - 1, tp.darken(oc, 0.8))
            g.fill(x, y - 12 * k, x + cw, y - 2, tp.FRAME_INNER)
            g.text(x + 3 * k, y - 11 * k, chip, tp.darken(oc, 0.8), k)

    def _render_cards(self, g):
        focus = self.hovered or self.selected
        needs_hi = set()
        if focus:
            fn = self.data.node(focus)
            needs_hi = {r for r in fn["requires"] if self.data.node(r)["branch"] != fn["branch"]}
        drawn_or = set()
        for n in self.data.nodes:
            i = n["id"]
            if i not in self.cells:
                continue
            wx, wy = self.card_rect(i)
            x, y = self.gx(wx), self.gy(wy)
            w, h = CARD_W * self.zoom, CARD_H * self.zoom
            st = self.status(i)
            frame = {"LEARNED": tp.P_GOLD, "READY": tp.FOREST, "AVAILABLE": tp.AMBER,
                     "BLOCKED": tp.DANGER, "STUDYING": 0xFF4A6A86}.get(st, tp.DISABLED_BORDER)
            fill = {"LEARNED": tp.GOLD_SOFT, "READY": tp.FRAME_INNER, "AVAILABLE": tp.FRAME_INNER,
                    "BLOCKED": tp.PAPER_DEEP}.get(st, tp.DISABLED_FILL)
            if i == focus or i in needs_hi:
                g.fill(x - 2, y - 2, x + w + 2, y + h + 2, tp.with_alpha(tp.INK if i == focus else tp.FOREST, 0x90))
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, frame)
            g.fill(x, y, x + w, y + h, fill)
            if st == "LEARNED":
                g.fill(x, y, x + w, y + 1, tp.lighten(tp.P_GOLD, 0.5))
            if st == "PLANNED":
                for yy in range(int(y), int(y + h)):
                    for xx in range(int(x), int(x + w)):
                        if (xx + yy) % 4 == 0:
                            g.fill(xx, yy, xx + 1, yy + 1, tp.with_alpha(tp.INK_MUTED, 0x50))
            k = self.zoom
            g.item(tp.node_icon(n), x + 3 * k, y + 3 * k, 16 * k)
            lines = tp.split(n["name"], 74)
            if len(lines) > 2:
                lines = lines[:2]
                while tp.FONT.width(lines[1] + "...") > 74 and len(lines[1]) > 1:
                    lines[1] = lines[1][:-1]
                lines[1] += "..."
            ink = tp.INK_MUTED if st in ("LOCKED", "PLANNED") else tp.INK
            ty = y + (h - len(lines) * 9 * k) / 2 + 1
            for ln in lines:
                g.text(x + 22 * k, ty, ln, ink, k)
                ty += 9 * k
            # corner badge
            bx, by = x + w - 5, y - 3
            badge = {"LEARNED": ("✔", tp.P_GOLD), "READY": ("!", tp.FOREST), "BLOCKED": ("x", tp.DANGER),
                     "STUDYING": ("⌛", 0xFF4A6A86)}.get(st)
            if badge:
                g.fill(bx - 1, by, bx + 7, by + 8, badge[1])
                g.text(bx + 1, by, badge[0], tp.ON_ACCENT)
            elif st == "LOCKED":
                g.fill(bx - 1, by, bx + 7, by + 8, tp.INK_DISABLED)
                tp.lock_glyph(g, bx + 1, by + 1, tp.ON_ACCENT)
            elif st == "PLANNED":
                g.fill(bx - 1, by, bx + 7, by + 8, tp.DISABLED_BORDER)
                g.text(bx + 1, by, "?", tp.INK_SOFT)
            # cross-branch needs marker (Crown seals are implied by the column)
            others = [r for r in n["requires"] if self.data.node(r)["branch"] not in (n["branch"], "crown")]
            if others:
                oc = self.data.branches[self.data.node(others[0])["branch"]]["color"]
                g.fill(x - 1, y + h - 5, x + 4, y + h + 1, oc)
                if i == focus:
                    self._chip = (x, y, others, oc)
            # OR plate between pick-one partners
            for ex in n["excludes"]:
                key = tuple(sorted((i, ex)))
                if ex in self.cells and key not in drawn_or:
                    drawn_or.add(key)
                    ox, oy = self.card_rect(ex)
                    if abs(ox - wx) < 1:
                        top_y = min(wy, oy) + CARD_H
                        bot_y = max(wy, oy)
                        cy = self.gy((top_y + bot_y) / 2)
                        cx = self.gx(wx + CARD_W / 2)
                        k = self.zoom
                        g.fill(cx - 8 * k, cy - 4 * k, cx + 8 * k, cy + 4 * k, tp.DANGER)
                        g.text(cx - 5.5 * k, cy - 3.5 * k, "OR", tp.ON_ACCENT, k)


def main():
    tp.FONT = mcfont.McFont.load()
    tp.JAR = mcfont.find_client_jar()
    out = tp.DEFAULT_OUT
    os.makedirs(out, exist_ok=True)
    data = tp.Data()
    base = tp.fake_state(data, False)
    base["crossbows"] = "BLOCKED"
    base["archer_longbow_drill"] = "LEARNED"
    base["town_charter"] = "STUDYING"
    have = {"shield_doctrine": [23, 14, 6], "knights": [23, 1, 4, 9]}
    gates = {"shield_doctrine": [("Guard combat XP earned: 40/40", 40, 40)]}
    for gui, W, H in ((3, 1920, 1080), (2, 1920, 1080), (4, 1920, 1080)):
        for name, filt, sel in (("overview", "all", "shield_doctrine"), ("watch", "watch", "barricades")):
            if gui != 3 and name != "overview":
                continue
            GridScreen.gui = gui
            scr = GridScreen(data, W // gui, H // gui, base, sel, None, None, None,
                             have=have, gates=gates, filt=filt)
            scr.gui = gui
            scr.fit_view()
            img = scr.render(gui)
            path = os.path.join(out, f"v3_{name}_gui{gui}.png")
            img.save(path)
            print(path, "cols", scr.ncols, "rows", scr.band_rows, "zoom %.2f" % scr.zoom)


if __name__ == "__main__":
    main()
