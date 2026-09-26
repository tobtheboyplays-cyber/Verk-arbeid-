package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchScreenLayoutTest {
    @Test
    void compactAndDesktopProfilesKeepResearchActionsAndTextInsideThePanel() {
        for (int[] viewport : new int[][] {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {960, 540}}) {
            ResearchScreen.ResearchLayout layout = ResearchScreen.layoutFor(viewport[0], viewport[1]);
            assertTrue(layout.left() >= 0 && layout.top() >= 0);
            assertTrue(layout.left() + layout.panelWidth() <= viewport[0]);
            assertTrue(layout.top() + layout.panelHeight() <= viewport[1]);
            assertTrue(layout.textBox() >= 174, "readable project effect column at the actual minimum width");
            Rect content = layout.frame().content();
            assertTrue(layout.buttonX() >= content.x()
                && layout.buttonX() + ResearchScreen.BTN_W <= content.right());
            assertTrue(layout.cancel().bottom() + 6 <= layout.listTop(), "active-project Cancel is above the list");
            // The Close button is now the wood close key in the header: reachable and inside the panel.
            Rect close = layout.frame().close();
            assertTrue(close.x() >= layout.left() && close.right() <= layout.left() + layout.panelWidth());
            assertTrue(close.bottom() <= layout.frame().page().y(), "close key sits above the page");
            assertTrue(layout.listTop() + layout.listHeight() <= content.bottom());
            for (int row = 0; row < layout.visibleRows(); row++) {
                Rect choose = layout.choose(row);
                assertTrue(choose.y() >= layout.listTop() && choose.bottom() <= content.bottom());
            }
        }
    }

    @Test
    void compactWindowReducesVisibleRowsAndKeepsTheCloseKeyReachable() {
        ResearchScreen.ResearchLayout compact = ResearchScreen.layoutFor(320, 240);
        assertEquals(304, compact.panelWidth());
        assertEquals(224, compact.panelHeight());
        assertEquals(1, compact.visibleRows());
        Ui2LayoutAssert.inViewport(320, 240, compact.frame().close(), "close key");
        assertEquals(3, ResearchScreen.layoutFor(640, 360).visibleRows());
    }

    @Test
    void standardFrameContentNeverClipsOrOverlapsAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            ResearchScreen.ResearchLayout l = ResearchScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Rect content = f.content();
            List<Rect> blocks = new ArrayList<>(List.of(l.hero(), l.list(), l.scrollbar()));
            for (Rect r : blocks) Ui2LayoutAssert.inside(content, r, at);
            Ui2LayoutAssert.disjoint(blocks, at);
            Ui2LayoutAssert.inside(l.hero(), l.cancel(), at + " cancel");
            List<Rect> cards = new ArrayList<>();
            for (int row = 0; row < l.visibleRows(); row++) {
                Rect card = l.card(row);
                Ui2LayoutAssert.inside(l.list(), card, at + " card " + row);
                Ui2LayoutAssert.inside(card, l.choose(row), at + " choose " + row);
                // The Choose column never covers the project text column.
                assertTrue(card.x() + ResearchScreen.TEXT_OFF + l.textBox() <= l.choose(row).x(), at);
                cards.add(card);
            }
            Ui2LayoutAssert.disjoint(cards, at + " cards");
            assertTrue(l.heroTextBox() > 0 && l.hero().x() + ResearchScreen.HERO_TEXT_OFF + l.heroTextBox()
                <= l.hero().right(), at + " hero text");
            assertTrue(l.visibleRows() >= 1, at);
        }
    }
}
