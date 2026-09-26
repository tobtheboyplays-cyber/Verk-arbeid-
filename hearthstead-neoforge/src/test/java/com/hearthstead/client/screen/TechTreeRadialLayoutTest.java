package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The radial tech tree layout: every node placed, no medallions overlap,
 * each node inside its own wedge and rank band, prerequisites never further
 * out than their dependants in a wedge, pick-one partners side by side, and
 * edges that stay inside the wedge. Plus the screen frame at GUI 2-4.
 */
class TechTreeRadialLayoutTest {
    private static final TechTreeData DATA = TechTreeData.get();
    private static final TechTreeRadialLayout L = TechTreeRadialLayout.compute(DATA);

    @Test
    void everyNodePlacedInsideItsWedgeAndBand() {
        for (TechNodeDef n : DATA.nodes()) {
            TechTreeRadialLayout.Polar p = L.pos(n.id());
            assertNotNull(p, n.id());
            if ("crown".equals(n.branch())) {
                continue;
            }
            TechTreeRadialLayout.Sector s = L.sector(n.branch());
            assertTrue(p.angle() >= s.a0() && p.angle() <= s.a1(), n.id() + " leaves its wedge: " + p);
            assertTrue(p.r() > L.bandEdge(n.tier() - 1) && p.r() < L.bandEdge(n.tier()),
                n.id() + " outside its rank band: " + p);
        }
        assertEquals(0.0, L.pos("settlement_charter").r(), 1e-9);
    }

    @Test
    void medallionsNeverOverlap() {
        List<TechNodeDef> nodes = DATA.nodes();
        double min = 2 * TechTreeRadialLayout.MED + 2;
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                TechTreeRadialLayout.Polar a = L.pos(nodes.get(i).id());
                TechTreeRadialLayout.Polar b = L.pos(nodes.get(j).id());
                double d = Math.hypot(a.x(0) - b.x(0), a.y(0) - b.y(0));
                assertTrue(d >= min, nodes.get(i).id() + " overlaps " + nodes.get(j).id() + " (" + d + ")");
            }
        }
    }

    @Test
    void inWedgeEdgesRunOutwardAndStayInside() {
        for (TechNodeDef n : DATA.nodes()) {
            if ("crown".equals(n.branch())) {
                continue;
            }
            TechTreeRadialLayout.Sector s = L.sector(n.branch());
            for (String r : n.requires()) {
                TechNodeDef p = DATA.node(r);
                if (p == null || !p.branch().equals(n.branch())) {
                    continue;
                }
                assertTrue(L.pos(r).r() < L.pos(n.id()).r(), r + " -> " + n.id() + " does not run outward");
                for (TechTreeRadialLayout.Polar q : L.edge(r, n.id())) {
                    assertTrue(q.angle() >= s.a0() - 1e-6 && q.angle() <= s.a1() + 1e-6,
                        "edge " + r + " -> " + n.id() + " leaves the wedge");
                }
            }
        }
    }

    @Test
    void pickOnePartnersSitSideBySide() {
        for (TechNodeDef n : DATA.nodes()) {
            for (String ex : n.excludes()) {
                TechTreeRadialLayout.Polar a = L.pos(n.id());
                TechTreeRadialLayout.Polar b = L.pos(ex);
                assertEquals(a.r(), b.r(), 1e-6, n.id() + " and " + ex + " must share a ring");
                double step = Math.toDegrees(TechTreeRadialLayout.S / a.r());
                assertTrue(Math.abs(a.angle() - b.angle()) <= step * 1.6,
                    n.id() + " and " + ex + " are not adjacent");
            }
        }
    }

    @Test
    void chartersAreSealsOnTheTopSpoke() {
        String[] seals = {"first_raid_aftermath", "town_charter", "castle_charter", "kingdom_crown"};
        for (int t = 0; t < seals.length; t++) {
            TechTreeRadialLayout.Polar p = L.pos(seals[t]);
            assertEquals(0.0, p.angle(), 1e-9, seals[t]);
            assertEquals(L.rankRing(t + 2), p.r(), 1e-6, seals[t] + " sits on the ring it opens");
        }
    }

    @Test
    void frameFitsEveryGuiScale() {
        for (int[] vp : Ui2LayoutAssert.GUI_2_TO_4) {
            int margin = vp[0] < 520 ? 2 : 6;
            Ui2FrameLayout f = Ui2FrameLayout.at(margin, margin + 2, vp[0] - margin * 2, vp[1] - margin * 2 - 2, false);
            Rect page = f.page();
            int sideW = Math.max(150, Math.min(240, Math.round(page.width() * 0.3F)));
            int sideX = page.right() - sideW - 4;
            Rect view = new Rect(page.x() + 1, page.y() + 1, sideX - 5 - (page.x() + 1), page.height() - 2);
            Rect side = new Rect(sideX, view.y(), sideW, view.height());
            Ui2LayoutAssert.inViewport(vp[0], vp[1], view, "map view");
            Ui2LayoutAssert.inViewport(vp[0], vp[1], side, "side panel");
            List<Rect> parts = new ArrayList<>(List.of(view, side));
            Ui2LayoutAssert.disjoint(parts, "tech tree panes");
            assertTrue(view.width() >= 120 && view.height() >= 120,
                "map view too small at " + vp[0] + "x" + vp[1] + ": " + view);
        }
    }
}
