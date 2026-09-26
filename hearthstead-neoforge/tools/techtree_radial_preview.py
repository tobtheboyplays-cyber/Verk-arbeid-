#!/usr/bin/env python3
"""Offscreen stills of the POLISHED RADIAL tech tree (owner's choice, 26 Sep).

Centre medallion (Banner Raised) on a compass rose; rank rings (Hamlet ->
Castle) with the Crown charters as seals in the top gap; branch wedges
sized by node count; nodes on their rank's sub-rings (a same-rank
prerequisite pushes a node one sub-ring out); edges = spoke out, arc along,
spoke in, never leaving the wedge; semantic zoom (LOD 0 dots, LOD 1 icons +
short names, LOD 2 full names + cost chips + effect line).

The layout here is the SPEC for client/screen/TechTreeRadialLayout (Java).

    python tools/techtree_radial_preview.py
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mcfont  # noqa: E402
import techtree_preview as tp  # noqa: E402

SECTORS = ["watch", "logistics", "commons", "craft"]   # clockwise from the top gap
NAMES = {"watch": "Watch & Defense", "logistics": "Logistics", "commons": "Commons & Household",
         "craft": "Craft & Production"}
ICONS = {"watch": "minecraft:iron_sword", "logistics": "minecraft:chest",
         "commons": "minecraft:bread", "craft": "minecraft:iron_pickaxe"}
GAP = 0.0         # closed circle (owner, 26 Sep)
SEAL_PAD = 7.0    # extra clearance at the top spoke where the charter seals sit
PAD = 3.5         # degrees kept clear next to each spoke
S = 70.0          # min arc spacing between node centres (world)
RG = 54.0         # sub-ring spacing (world)
R0 = 118.0        # first sub-ring radius
MED = 11.0        # medallion radius (world)
CENTRE = 30.0     # centre medallion radius (world)


def load_icons():
    import json
    p = os.path.join(tp.DATA, "icons.json")
    return json.load(open(p, encoding="utf-8"))["icons"] if os.path.exists(p) else {}


ICON_MAP = {}


def icon_for(n):
    i = n.get("icon") or ICON_MAP.get(n["id"])
    if i:
        t = tp.texture(i)
        if t is not None:
            return t
    return tp.node_icon(n)


# ------------------------------------------------------------------ layout

def layout(data):
    counts = {b: sum(1 for n in data.nodes if n["branch"] == b) for b in SECTORS}
    total = sum(counts.values())
    sectors, a = {}, GAP / 2
    for b in SECTORS:
        w = (360 - GAP) * counts[b] / total
        sectors[b] = (a, a + w)
        a += w
    by = data.by_id
    depth = {}

    def dep(n):
        if n["id"] in depth:
            return depth[n["id"]]
        ds = [dep(by[r]) + 1 for r in n["requires"]
              if r in by and by[r]["branch"] == n["branch"] and by[r]["tier"] == n["tier"]]
        depth[n["id"]] = max(ds) if ds else 0
        return depth[n["id"]]

    for n in data.nodes:
        dep(n)
    pos, rank_start, r = {}, {}, R0
    for t in range(1, 5):
        rank_start[t] = r
        subs_needed = 1
        plan = {}
        for b in SECTORS:
            a0, a1 = sectors[b]
            grp = [n for n in data.nodes if n["branch"] == b and n["tier"] == t]
            sub = 0
            rings = []
            for L in sorted({depth[n["id"]] for n in grp}):
                level = [n for n in grp if depth[n["id"]] == L]

                def bary(n):
                    ps = [pos[p][1] for p in n["requires"] if p in pos and by[p]["branch"] == b]
                    return (sum(ps) / len(ps) if ps else (a0 + a1) / 2, data.nodes.index(n))
                level.sort(key=bary)
                units, seen = [], set()
                for n in level:
                    if n["id"] in seen:
                        continue
                    u = [n]
                    seen.add(n["id"])
                    for ex in n["excludes"]:
                        if ex in by and by[ex] in level and ex not in seen:
                            u.append(by[ex])
                            seen.add(ex)
                    units.append(u)
                flat = [n for u in units for n in u]
                while flat:
                    rr = r + sub * RG
                    cap = max(1, int(rr * math.radians(a1 - a0 - 2 * PAD - SEAL_PAD) / S))
                    take, flat = flat[:cap], flat[cap:]
                    rings.append((sub, take))
                    sub += 1
            plan[b] = rings
            subs_needed = max(subs_needed, sub)
        for b, rings in plan.items():
            a0, a1 = sectors[b]
            lo = a0 + PAD + (SEAL_PAD if abs(a0) < 0.01 else 0)
            hi = a1 - PAD - (SEAL_PAD if abs(a1 - 360) < 0.01 else 0)
            for sub, take in rings:
                rr = r + sub * RG
                step = math.degrees(S / rr)
                # desired angle: parents' mean (same wedge), else spread evenly
                want = []
                k = len(take)
                for idx, n in enumerate(take):
                    ps = [pos[p][1] for p in n["requires"] if p in pos and by[p]["branch"] == b]
                    even = lo + (hi - lo) * (idx + 0.5) / k
                    want.append(sum(ps) / len(ps) if ps else even)
                order = sorted(range(k), key=lambda j: (want[j], j))
                # keep pick-one partners adjacent in the order
                for j in list(order):
                    for ex in take[j]["excludes"]:
                        m = next((q for q in range(k) if take[q]["id"] == ex), None)
                        if m is not None and abs(order.index(m) - order.index(j)) > 1:
                            order.remove(m)
                            order.insert(order.index(j) + 1, m)
                ang = [min(max(want[j], lo), hi) for j in order]
                for it in range(40):          # 1-D relaxation with min spacing
                    for q in range(1, k):
                        if ang[q] < ang[q - 1] + step:
                            mid = (ang[q] + ang[q - 1]) / 2
                            ang[q - 1], ang[q] = mid - step / 2, mid + step / 2
                    shift_lo = lo - ang[0]
                    shift_hi = hi - ang[-1]
                    if shift_lo > 0:
                        ang = [x + shift_lo for x in ang]
                    elif shift_hi < 0:
                        ang = [x + shift_hi for x in ang]
                for q, j in enumerate(order):
                    pos[take[j]["id"]] = (rr, ang[q])
        r += subs_needed * RG
    rank_start[5] = r
    # Crown: centre + seals in the top gap on each rank boundary ring.
    pos["settlement_charter"] = (0.0, 0.0)
    for t, i in ((2, "first_raid_aftermath"), (3, "town_charter"), (4, "castle_charter"), (5, "kingdom_crown")):
        pos[i] = (rank_start[t] - RG / 2, 0.0)
    return pos, sectors, rank_start


# ------------------------------------------------------------------ screen

class RadialScreen(tp.TechTreeScreen):
    def __init__(self, *a, lod_zoom=None, filt="all", centre=(0, 0), **k):
        self.filt = filt
        self._z = lod_zoom
        self._c = centre
        super().__init__(*a, **k)

    def init(self):
        super().init()
        self.pos, self.sectors, self.rank_start = layout(self.data)
        self.rot = 0.0
        if self.filt != "all":
            a0, a1 = self.sectors[self.filt]
            self.rot = -(a0 + a1) / 2          # rotate the sector to the top
        self.zoom = self._z
        self.camX, self.camY = self._c

    def world(self, i):
        r, a = self.pos[i]
        a = math.radians(a + self.rot)
        return math.sin(a) * r, -math.cos(a) * r

    def lod(self):
        eff = self.zoom * self.gui
        return 0 if eff < 1.25 else (1 if eff < 3.4 else 2)

    def render(self, s):
        self.gui = s
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
        g.enable_scissor(self.viewX, self.viewY, self.viewX + self.viewW, self.viewY + self.viewH)
        self.r_bands(g)
        self.r_wedges(g)
        self.r_rings(g)
        self.r_rim_ornament(g)
        self.r_edges(g)
        self.r_centre(g)
        self.r_nodes(g)
        self.r_seals(g)
        self.r_spoke_labels(g)
        self.r_rim(g)
        self.r_vignette(g)
        self.r_chips(g)
        self.r_ladder(g)
        g.disable_scissor()
        self.rebuild_panel()
        self.render_side(g)
        self.render_widgets(g)
        return g.img.convert("RGB")

    def P(self, wx, wy):
        return self.sx(wx), self.sy(wy)

    def polar(self, r, a):
        a = math.radians(a + self.rot)
        return self.P(math.sin(a) * r, -math.cos(a) * r)

    def faded(self, b):
        return self.filt != "all" and b != self.filt

    # ---- layers

    # ---- v5 layers (tree rings, seals, ornament)
    def band_edges(self):
        edges = [CENTRE + 16]
        for t in range(2, 6):
            edges.append(self.rank_start[t] - RG / 2)
        return edges            # edges[i]..edges[i+1] = band of tier i+1

    def annulus(self, g, r0, r1, a0, a1, col):
        steps = max(12, int((a1 - a0) / 3))
        for k in range(steps):
            t0 = a0 + (a1 - a0) * k / steps
            t1 = a0 + (a1 - a0) * (k + 1) / steps
            g.poly([self.polar(r0, t0), self.polar(r1, t0), self.polar(r1, t1), self.polar(r0, t1)], col)

    def r_bands(self, g):
        e = self.band_edges()
        cur = 2
        for i in range(4):
            t = i + 1
            if t == cur:
                col = tp.with_alpha(0xFFE8C98A, 0x55)
            elif t < cur:
                col = tp.with_alpha(tp.PAPER_DEEP, 0x70 if i % 2 == 0 else 0x30)
            else:
                col = tp.with_alpha(0xFFB9B2A4, 0x40 if i % 2 == 0 else 0x28)
            self.annulus(g, e[i], e[i + 1], 0, 360, col)

    def r_rim_ornament(self, g):
        cx, cy = self.P(0, 0)
        outer = (self.rank_start[5] - RG / 2) * self.zoom
        tp.dotted_circle(g, cx, cy, outer + 3, tp.with_alpha(tp.P_GOLD, 0xB0))
        for ang in range(0, 360, 2):
            pass
        # thin gold + ink border as continuous circles
        for rr, col in ((outer + 1.5, tp.with_alpha(tp.INK, 0x90)), (outer + 4.5, tp.with_alpha(tp.P_GOLD, 0xD0)),
                        (outer + 6, tp.with_alpha(tp.INK, 0x60))):
            steps = max(90, int(rr * 0.8))
            pts = [(cx + math.sin(2 * math.pi * j / steps) * rr, cy - math.cos(2 * math.pi * j / steps) * rr)
                   for j in range(steps + 1)]
            for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                tp.line(g, x0, y0, x1, y1, 1.0, col)
        if self.lod() >= 1:
            for ang in (45, 135, 225, 315):
                a = math.radians(ang)
                x, y = cx + math.sin(a) * (outer + 3), cy - math.cos(a) * (outer + 3)
                d = 4
                g.poly([(x, y - d), (x + d, y), (x, y + d), (x - d, y)], tp.P_GOLD)
                g.poly([(x, y - d / 2), (x + d / 2, y), (x, y + d / 2), (x - d / 2, y)], tp.GOLD_SOFT)

    def r_seals(self, g):
        names = {"first_raid_aftermath": 2, "town_charter": 3, "castle_charter": 4, "kingdom_crown": 5}
        for i, t in names.items():
            x, y = self.P(*self.world(i))
            st = self.status(i)
            R = max(6.5, 13 * self.zoom)
            learned = st == "LEARNED"
            wax = 0xFF8C2A24 if not learned else 0xFF9C7B3C
            if learned:
                tp.disc(g, x, y, R + 5, tp.with_alpha(0xFFE8C98A, 0x60))
            if st in ("READY", "STUDYING"):
                tp.disc(g, x, y, R + 4, tp.with_alpha(tp.FOREST, 0x50))
            # wax blob: scalloped edge
            for j in range(12):
                a = math.radians(j * 30)
                tp.disc(g, x + math.cos(a) * R * 0.78, y + math.sin(a) * R * 0.78, R * 0.34, wax)
            tp.disc(g, x, y, R * 0.9, wax)
            tp.disc(g, x, y, R * 0.66, tp.darken(wax, 0.8))
            tp.dotted_circle(g, x, y, R * 0.58, tp.with_alpha(tp.lighten(wax, 0.4), 0xC0))
            g.item(icon_for(self.data.node(i)), x - R * 0.45, y - R * 0.45, R * 0.9)

    def text_arc(self, g, text, r, a_start, col, k):
        """Text written clockwise along a circle starting at angle a_start (deg)."""
        sp = tp.FONT
        ang = a_start
        for ch in text:
            w = sp.width(ch) * k
            mid = ang + math.degrees((w / 2) / max(1.0, r * self.zoom))
            x, y = self.polar(r, mid)
            tmp = tp.Image.new("RGBA", (int(12 * g.s), int(12 * g.s)), (0, 0, 0, 0)) if hasattr(tp, "Image") else None
            # draw glyph rotated around its centre
            from PIL import Image
            gl = Image.new("RGBA", (sp.width(ch) + 2, 16), (0, 0, 0, 0))
            sp.draw(gl, 0, 4, ch, tp.rgba(col), shadow=False)
            f = g.s * k
            gl = gl.resize((max(1, int(gl.width * f)), max(1, int(16 * f))), Image.NEAREST)
            gl = gl.rotate(-(mid + self.rot), resample=Image.NEAREST, expand=True)
            g.comp(gl, int(x * g.s - gl.width / 2), int(y * g.s - gl.height / 2))
            ang += math.degrees(w / max(1.0, r * self.zoom))

    def r_spoke_labels(self, g):
        e = self.band_edges()
        names = ["Hamlet", "Village", "Town", "Castle"]
        k = 2.0 / self.gui
        lod = self.lod()
        spokes = [self.sectors[b][0] for b in SECTORS] if lod >= 1 else [self.sectors["watch"][0]]
        for sp_a in spokes:
            for i, nm in enumerate(names):
                rmid = (e[i] + e[i + 1]) / 2
                col = tp.with_alpha(tp.INK_MUTED, 0xC0) if i + 1 != 2 else tp.with_alpha(tp.P_GOLD, 0xFF)
                self.text_arc(g, nm.upper() if lod == 0 else nm, rmid, sp_a + 1.2, col, k)

    def r_vignette(self, g):
        for i in range(10):
            a = int(0x22 * (1 - i / 10))
            c = tp.with_alpha(0xFF3A2A18, a)
            g.fill(self.viewX, self.viewY + i, self.viewX + self.viewW, self.viewY + i + 1, c)
            g.fill(self.viewX, self.viewY + self.viewH - i - 1, self.viewX + self.viewW, self.viewY + self.viewH - i, c)
            g.fill(self.viewX + i, self.viewY, self.viewX + i + 1, self.viewY + self.viewH, c)
            g.fill(self.viewX + self.viewW - i - 1, self.viewY, self.viewX + self.viewW - i, self.viewY + self.viewH, c)

    def r_ladder(self, g):
        steps = ["Hamlet", "Village", "Town", "Castle", "Kingdom"]
        cur = 1
        y = self.viewY + self.viewH - 13
        parts = []
        for i, s_ in enumerate(steps):
            parts.append((s_, i))
        total = sum(tp.FONT.width(p) for p, _ in parts) + 14 * (len(parts) - 1) + 12
        x = self.viewX + (self.viewW - total) / 2
        g.fill(x - 2, y - 2, x + total + 2, y + 11, tp.with_alpha(tp.PAPER, 0xE8))
        tp.outline(g, x - 2, y - 2, total + 4, 13, tp.with_alpha(tp.RULE_STRONG, 0xFF))
        x += 6
        for s_, i in parts:
            on = i == cur
            w = tp.FONT.width(s_)
            if on:
                g.fill(x - 3, y - 1, x + w + 3, y + 10, tp.with_alpha(0xFFE8C98A, 0xC0))
            g.text(x, y + 1, s_, tp.INK if on else (tp.INK_SOFT if i < cur else tp.INK_MUTED))
            x += w
            if i < len(parts) - 1:
                g.text(x + 4, y + 1, "\u25b8", tp.INK_MUTED)
                x += 14

    def r_wedges(self, g):
        outer = self.rank_start[5] - RG / 2
        for idx, b in enumerate(SECTORS):
            a0, a1 = self.sectors[b]
            col = self.data.branches[b]["color"]
            alpha = 0x08 if self.faded(b) else 0x10
            steps = max(8, int((a1 - a0) / 2))
            for k in range(steps):
                t0 = a0 + (a1 - a0) * k / steps
                t1 = a0 + (a1 - a0) * (k + 1) / steps
                pts = [self.polar(CENTRE + 8, t0), self.polar(outer, t0), self.polar(outer, t1),
                       self.polar(CENTRE + 8, t1)]
                g.poly(pts, tp.with_alpha(col, alpha))
            for ang in (a0, a1):
                x0, y0 = self.polar(CENTRE + 16, ang)
                x1, y1 = self.polar(self.rank_start[5] - RG / 2, ang)
                tp.line(g, x0, y0, x1, y1, 1.0, tp.with_alpha(tp.INK_MUTED, 0x90))
            if self.lod() >= 1:
                wx, wy = self.polar((self.rank_start[2] + self.rank_start[4]) / 2, (a0 + a1) / 2)
                size = 150 * self.zoom
                tex = tp.texture(ICONS[b])
                if tex is not None:
                    im = tex.convert("RGBA").resize((max(1, int(size * g.s)), max(1, int(size * g.s))), tp.Image.NEAREST) if hasattr(tp, "Image") else None
                    from PIL import Image
                    im = tex.convert("RGBA").resize((max(1, int(size * g.s)), max(1, int(size * g.s))), Image.NEAREST)
                    a = im.getchannel("A").point(lambda v: v * 22 // 255)
                    im.putalpha(a)
                    g.comp(im, int((wx - size / 2) * g.s), int((wy - size / 2) * g.s))

    def r_rings(self, g):
        cx, cy = self.P(0, 0)
        for r in self.band_edges()[1:]:
            for d, a in ((0.0, 0x70), (2.0, 0x38)):
                rr = r * self.zoom + d
                steps = max(90, int(rr * 0.8))
                pts = [(cx + math.sin(2 * math.pi * q / steps) * rr, cy - math.cos(2 * math.pi * q / steps) * rr)
                       for q in range(steps + 1)]
                for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                    tp.line(g, x0, y0, x1, y1, 1.0, tp.with_alpha(tp.INK, a))

    def r_centre(self, g):
        cx, cy = self.P(0, 0)
        R = CENTRE * self.zoom
        # compass rose
        for ang in range(0, 360, 45):
            long_ = ang % 90 == 0
            L = R * (2.1 if long_ else 1.6)
            a = math.radians(ang)
            tip = (cx + math.sin(a) * L, cy - math.cos(a) * L)
            side = math.radians(ang + 90)
            wv = R * (0.28 if long_ else 0.2)
            b1 = (cx + math.sin(side) * wv, cy - math.cos(side) * wv)
            b2 = (cx - math.sin(side) * wv, cy + math.cos(side) * wv)
            g.poly([tip, b1, b2], tp.with_alpha(tp.P_GOLD if long_ else tp.INK_MUTED, 0x90 if long_ else 0x60))
        for q in range(8, 0, -1):
            tp.disc(g, cx, cy, R + 4 + q * 3 * max(self.zoom, 0.4), tp.with_alpha(0xFFE8C98A, 0x0C))
        for ang in range(0, 360, 15):
            a = math.radians(ang)
            L0 = R * 2.3
            L1 = R * (2.6 if ang % 90 == 0 else 2.45)
            tp.line(g, cx + math.sin(a) * L0, cy - math.cos(a) * L0, cx + math.sin(a) * L1, cy - math.cos(a) * L1,
                    1.0, tp.with_alpha(tp.INK_MUTED, 0x90))
        tp.disc(g, cx, cy, R + 5, tp.with_alpha(tp.GOLD_SOFT, 0x50))
        tp.disc(g, cx, cy, R + 2, tp.with_alpha(tp.P_GOLD, 0xE0))
        tp.disc(g, cx, cy, R, tp.GOLD_SOFT)
        tp.dotted_circle(g, cx, cy, R - 3, tp.P_GOLD)
        g.item(tp.banner_icon(), cx - R * 0.6, cy - R * 0.6, R * 1.2)

    def edge_path(self, p, c):
        r1, a1 = self.pos[p]
        r2, a2 = self.pos[c]
        rm = r1 + (r2 - r1) * 0.5 if r2 > r1 else r1 + RG / 2
        pts = [(r1 + MED + 1, a1), (rm, a1)]
        steps = max(1, int(abs(a2 - a1) / 1.5))
        for k in range(1, steps + 1):
            pts.append((rm, a1 + (a2 - a1) * k / steps))
        pts.append((r2 - MED - 2, a2))
        return pts

    def r_edges(self, g):
        lod = self.lod()
        focus = self.hovered or self.selected
        path = self.data.ancestors(focus) | {focus} if focus else set()
        for n in self.data.nodes:
            i = n["id"]
            for p in n["requires"]:
                pn = self.data.node(p)
                if pn["branch"] != n["branch"] or n["branch"] == "crown":
                    continue
                strong = i in path and p in path
                if lod == 0 and not strong:
                    continue
                learned = self.status(p) == "LEARNED"
                both = learned and self.status(i) == "LEARNED"
                ink = 0xFF6B4A2A   # learned-path ink (brown-gold)
                col = (ink if learned else self.branch_color(n)) if strong else tp.with_alpha(
                    ink if both else tp.INK_MUTED, (0xB0 if both else 0x55) if not self.faded(n["branch"]) else 0x20)
                pts = [self.polar(r, a) for r, a in self.edge_path(p, i)]
                for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                    tp.line(g, x0, y0, x1, y1, 2.0 if strong else 1.0, col)
                if lod >= 2 or strong:
                    (x0, y0), (x1, y1) = pts[-2], pts[-1]
                    ang = math.atan2(y1 - y0, x1 - x0)
                    for sp in (0.5, -0.5):
                        tp.line(g, x1 - math.cos(ang + sp) * 4, y1 - math.sin(ang + sp) * 4, x1, y1,
                                1.5 if strong else 1.0, col)

    def status_style(self, st):
        frame = {"LEARNED": tp.P_GOLD, "READY": tp.FOREST, "AVAILABLE": tp.AMBER,
                 "BLOCKED": tp.DANGER, "STUDYING": 0xFF4A6A86}.get(st, tp.DISABLED_BORDER)
        fill = {"LEARNED": tp.GOLD_SOFT, "READY": tp.FRAME_INNER, "AVAILABLE": tp.FRAME_INNER,
                "BLOCKED": tp.PAPER_DEEP}.get(st, tp.DISABLED_FILL)
        return frame, fill

    def r_nodes(self, g):
        lod = self.lod()
        focus = self.hovered or self.selected
        k = 2.0 / self.gui          # crisp small font: 2 physical px per font px
        drawn_or = set()
        self._boxes = []
        prio = {"READY": 0, "STUDYING": 1, "AVAILABLE": 2, "LEARNED": 3, "BLOCKED": 4, "LOCKED": 5, "PLANNED": 6}
        ordered = sorted(self.data.nodes, key=lambda n: (0 if n["id"] == focus else 1, prio.get(self.status(n["id"]), 9)))
        # medallion boxes first, so no label covers a node
        for n in ordered:
            if n["branch"] == "crown":
                continue
            x, y = self.P(*self.world(n["id"]))
            R = max(MED * self.zoom, 7.0)
            self._boxes.append((x - R, y - R, x + R, y + R))
        for n in ordered:
            i = n["id"]
            if i == "settlement_charter" or n["branch"] == "crown":
                continue
            x, y = self.P(*self.world(i))
            if x < self.viewX - 60 or x > self.viewX + self.viewW + 60 or y < self.viewY - 60 or y > self.viewY + self.viewH + 60:
                continue
            st = self.status(i)
            frame, fill = self.status_style(st)
            fade = self.faded(n["branch"])
            crown = n["branch"] == "crown"
            if lod == 0:
                rr = 4.5 if crown else 3.0
                tp.disc(g, x, y, rr + 1, tp.with_alpha(frame, 0x40 if fade else 0xFF))
                tp.disc(g, x, y, rr, tp.with_alpha(fill if st != "LEARNED" else frame, 0x40 if fade else 0xFF))
                continue
            R = (MED * (1.35 if crown else 1.0)) * self.zoom
            R = max(R, 7.0)
            if i == focus:
                tp.disc(g, x, y, R + 4, tp.with_alpha(tp.INK, 0x70))
            if st == "READY":
                tp.disc(g, x, y, R + 3, tp.with_alpha(tp.FOREST, 0x50))
            tp.disc(g, x, y, R + 1.5, tp.with_alpha(frame, 0x50 if fade else 0xFF))
            tp.disc(g, x, y, R, tp.with_alpha(fill, 0x60 if fade else 0xFF))
            if st == "PLANNED":
                tp.hatch(g, x, y, R, tp.with_alpha(tp.INK_MUTED, 0x70))
            if st == "STUDYING":
                for j in range(40):
                    aa = math.radians(-90 + j * 9)
                    c = 0xFF4A6A86 if j < 22 else tp.with_alpha(0xFF4A6A86, 0x40)
                    g.fill(x + math.cos(aa) * (R + 3), y + math.sin(aa) * (R + 3),
                           x + math.cos(aa) * (R + 3) + 1, y + math.sin(aa) * (R + 3) + 1, c)
            isz = R * 1.25
            g.item(icon_for(n), x - isz / 2, y - isz / 2, isz)
            # corner badge
            bx, by = x + R * 0.62, y - R * 0.95
            badge = {"LEARNED": ("✔", tp.P_GOLD), "READY": ("!", tp.FOREST), "BLOCKED": ("x", tp.DANGER),
                     "PLANNED": ("?", tp.DISABLED_BORDER), "STUDYING": ("⌛", 0xFF4A6A86),
                     "LOCKED": (None, tp.INK_DISABLED)}.get(st)
            if badge and not fade:
                g.fill(bx, by, bx + 7, by + 7, badge[1])
                if badge[0]:
                    g.text(bx + 1, by, badge[0], tp.ON_ACCENT)
                else:
                    tp.lock_glyph(g, bx + 1, by + 1, tp.ON_ACCENT)
            if fade:
                continue
            # labels (a Crown seal is named by its rank label)
            if crown:
                continue
            ty = y + R + 3
            name = n["name"]
            ink = tp.INK_MUTED if st in ("LOCKED", "PLANNED") else tp.INK
            if lod == 1:
                lines = word_wrap(name, (S * self.zoom) / k - 6)[:2]
            else:
                lines = word_wrap(name, (S * self.zoom) / k - 6)[:2]
            bw = max([tp.FONT.width(ln) for ln in lines] + [0]) * k
            box = (x - bw / 2 - 1, ty - 1, x + bw / 2 + 1, ty + len(lines) * 9 * k)
            clash = any(not (box[2] <= b[0] or box[0] >= b[2] or box[3] <= b[1] or box[1] >= b[3]) for b in self._boxes)
            if clash and i != focus:
                lines = []
            else:
                self._boxes.append(box)
            for ln in lines:
                w = tp.FONT.width(ln) * k
                g.fill(x - w / 2 - 1, ty - 1, x + w / 2 + 1, ty + 7 * k + 1, tp.with_alpha(tp.PAPER, 0xC8))
                g.text(x - w / 2, ty, ln, ink, k)
                ty += 9 * k
            if lod >= 2 and not crown:
                ty += 1
                # cost chip row
                costs = [("hearthstead:coins", n["coins"])] if n["coins"] else []
                costs += [(gd.get("item") or {"minecraft:logs": "minecraft:oak_log", "minecraft:planks": "minecraft:oak_planks",
                           "minecraft:wool": "minecraft:white_wool"}.get(gd.get("tag"), "minecraft:paper"), gd["count"])
                          for gd in n["goods"]][:3]
                rowW = sum(8 + tp.FONT.width(str(c)) * k + 3 for _, c in costs)
                cxp = x - rowW / 2
                ok = st in ("READY", "LEARNED")
                for it, c in costs:
                    g.item(tp.texture(it), cxp, ty, 7)
                    g.text(cxp + 8, ty + 1, str(c), tp.FOREST if ok else tp.INK_SOFT, k)
                    cxp += 8 + tp.FONT.width(str(c)) * k + 3
                ty += 8
                eff = short_effect(n)
                maxw = (S * self.zoom - 8) / k
                while eff and tp.FONT.width(eff) > maxw:
                    eff = eff[:-4] + "..."
                if eff:
                    w = tp.FONT.width(eff) * k
                    g.text(x - w / 2, ty, eff, tp.INK_MUTED, k)
            # OR clasp
            for ex in n["excludes"]:
                key = tuple(sorted((i, ex)))
                if ex in self.pos and key not in drawn_or:
                    drawn_or.add(key)
                    x2, y2 = self.P(*self.world(ex))
                    mx, my = (x + x2) / 2, (y + y2) / 2
                    ck = max(k, 0.5)
                    g.fill(mx - 7 * ck, my - 4 * ck, mx + 7 * ck, my + 4 * ck, tp.DANGER)
                    g.text(mx - 5.5 * ck, my - 3.5 * ck, "OR", tp.ON_ACCENT, ck)

    def r_rim(self, g):
        outer = self.rank_start[5] - RG / 2 + 10
        lod = self.lod()
        for b in SECTORS:
            a0, a1 = self.sectors[b]
            col = self.data.branches[b]["color"]
            total = sum(1 for n in self.data.nodes if n["branch"] == b)
            done = sum(1 for n in self.data.nodes if n["branch"] == b and self.status(n["id"]) == "LEARNED")
            # progress arc along the rim
            steps = 60
            for j in range(steps):
                t0 = a0 + PAD + (a1 - a0 - 2 * PAD) * j / steps
                t1 = a0 + PAD + (a1 - a0 - 2 * PAD) * (j + 1) / steps
                x0, y0 = self.polar(outer, t0)
                x1, y1 = self.polar(outer, t1)
                c = col if j < steps * done / total else tp.with_alpha(col, 0x40)
                tp.line(g, x0, y0, x1, y1, 3.0, c if not self.faded(b) else tp.with_alpha(col, 0x30))
            x, y = self.polar(outer + 18 / max(self.zoom, 0.3), (a0 + a1) / 2)
            k = 1.0 if lod == 0 else 2.0 / self.gui
            label = NAMES[b]
            sub = f"{done}/{total} learned"
            w = max(tp.FONT.width(label), tp.FONT.width(sub)) * k + 16
            x = tp.clamp(x, self.viewX + w / 2 + 4, self.viewX + self.viewW - w / 2 - 4)
            y = tp.clamp(y, self.viewY + 26, self.viewY + self.viewH - 30)
            if self.faded(b):
                continue
            g.fill(x - w / 2 - 1, y - 9, x + w / 2 + 1, y + 11 * k + 1, tp.darken(col, 0.8))
            g.fill(x - w / 2, y - 8, x + w / 2, y + 11 * k, tp.FRAME_INNER)
            g.item(tp.texture(ICONS[b]), x - w / 2 + 2, y - 7, 12)
            g.text(x - w / 2 + 15, y - 6, label, tp.darken(col, 0.75), k)
            g.text(x - w / 2 + 15, y - 6 + 9 * k, sub, tp.INK_MUTED, k)

    def r_chips(self, g):
        x = self.viewX + 3
        y = self.viewY + 3
        for key, label in [("all", "All")] + [(b, NAMES[b].split(" ")[0]) for b in SECTORS]:
            w = tp.FONT.width(label) + 8
            on = key == self.filt
            col = self.data.branches[key]["color"] if key in self.data.branches else tp.INK_SOFT
            g.fill(x, y, x + w, y + 11, col if on else tp.FRAME_INNER)
            tp.outline(g, x, y, w, 11, col if on else tp.RULE_STRONG)
            g.text(x + 4, y + 2, label, tp.ON_ACCENT if on else tp.INK_SOFT)
            x += w + 3


STOP = {"the", "of", "&", "and", "to", "a"}


def word_wrap(text, width):
    text = text.replace(" (Warehouse ", " (W")
    out, cur = [], ""
    for wd in text.split(" "):
        t = (cur + " " + wd).strip()
        if tp.FONT.width(t) <= width or not cur:
            cur = t
        else:
            out.append(cur)
            cur = wd
    if cur:
        out.append(cur)
    # never end a line on a stop word ("Raise the / Barricades")
    for q in range(len(out) - 1):
        ws = out[q].split(" ")
        if len(ws) > 1 and ws[-1].lower() in STOP:
            out[q] = " ".join(ws[:-1])
            out[q + 1] = ws[-1] + " " + out[q + 1]
    if len(out) > 2:
        out = [out[0], " ".join(out[1:])]
    if len(out) == 2 and tp.FONT.width(out[1]) > width:
        s = out[1]
        while len(s) > 2 and tp.FONT.width(s + "...") > width:
            s = s[:-1]
        out[1] = s + "..."
    return out


def short_effect(n):
    o = n["offers"]
    for sep in (":", ".", ","):
        if sep in o:
            o = o.split(sep)[0] if sep != ":" else o.split(":")[1].split(".")[0].split(",")[0].strip()
            break
    return (o[:26] + "...") if len(o) > 28 else o


def main():
    global ICON_MAP
    tp.FONT = mcfont.McFont.load()
    tp.JAR = mcfont.find_client_jar()
    ICON_MAP = load_icons()
    out = tp.DEFAULT_OUT
    data = tp.Data()
    st = tp.fake_state(data, False)
    st["crossbows"] = "BLOCKED"
    st["archer_longbow_drill"] = "LEARNED"
    st["town_charter"] = "STUDYING"
    have = {"shield_doctrine": [23, 14, 6]}
    gates = {"shield_doctrine": [("Guard combat XP earned: 40/40", 40, 40)]}
    gui = 3
    shots = [
        ("v5_lod0_all.png", "all", None, (0, 0), "shield_doctrine"),
        ("v5_lod1_default.png", "all", 0.5, None, "shield_doctrine"),
        ("v5_lod2_close.png", "all", 1.2, None, "shield_doctrine"),
    ]
    for name, filt, zoom, centre, sel in shots:
        scr = RadialScreen(data, 1920 // gui, 1080 // gui, st, sel, None, None, None,
                           have=have, gates=gates, filt=filt, lod_zoom=1.0, centre=(0, 0))
        scr.gui = gui
        outer = scr.rank_start[5] + 60
        if zoom is None and filt == "all":
            scr.zoom = min(scr.viewW, scr.viewH - 40) / (2 * (scr.rank_start[5] - RG / 2 + 40))
            scr.camX, scr.camY = 0, 0
        elif filt != "all":
            # rotated sector sits at the top: frame it
            scr.zoom = 0.45
            scr.camX, scr.camY = 0, -(outer * 0.55)
        else:
            scr.zoom = zoom
            if zoom < 0.9:     # default: centred on the Banner
                scr.camX, scr.camY = 0, 0
            else:
                x, y = scr.world(sel)
                scr.camX, scr.camY = x * 0.6, y * 0.6
        img = scr.render(gui)
        path = os.path.join(out, name)
        img.save(path)
        print(path, "zoom %.2f lod %d outer %.0f" % (scr.zoom, scr.lod(), outer))


if __name__ == "__main__":
    main()
