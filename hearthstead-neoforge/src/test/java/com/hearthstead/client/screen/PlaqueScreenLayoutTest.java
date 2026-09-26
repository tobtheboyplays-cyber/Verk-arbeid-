package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standard-frame geometry for the building plaque: nothing clipped, nothing overlapping at GUI 2-4. */
class PlaqueScreenLayoutTest {

    @Test
    void frameHoldsTabsRowsNoteAndActionWithoutClippingAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            PlaqueScreen.Layout l = PlaqueScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            assertEquals(PlaqueScreen.PANEL_W, f.width(), "preferred width fits at " + at);
            assertEquals(PlaqueScreen.LIST_W, l.list().width(), "text budgets match the card column at " + at);
            Rect content = f.content();
            List<Rect> blocks = List.of(l.tabs(), l.list(), l.scrollbar(), l.note(), l.refresh());
            for (Rect r : blocks) Ui2LayoutAssert.inside(content, r, at);
            Ui2LayoutAssert.disjoint(blocks, at);
            Ui2LayoutAssert.inside(f.footer(), l.refresh(), at + " refresh");
            assertTrue(l.visibleRows() >= 2, "at least two rows at " + at);
            Ui2LayoutAssert.disjoint(List.of(f.close(), f.page()), at + " close key");
        }
    }

    @Test
    void threeRowsAtOrdinaryScalesAndOneRowWhenShort() {
        assertEquals(3, PlaqueScreen.layoutFor(960, 540).visibleRows());
        assertEquals(3, PlaqueScreen.layoutFor(640, 360).visibleRows());
        PlaqueScreen.Layout shortest = PlaqueScreen.layoutFor(427, 200);
        assertEquals(1, shortest.visibleRows());
        Rect content = shortest.frame().content();
        List<Rect> blocks = List.of(shortest.tabs(), shortest.list(), shortest.note(), shortest.refresh());
        for (Rect r : blocks) Ui2LayoutAssert.inside(content, r, "427x200");
        Ui2LayoutAssert.disjoint(blocks, "427x200");
    }

    @Test
    void rowActionsStayInsideTheirCardsAndClearOfTheText() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            PlaqueScreen.Layout l = PlaqueScreen.layoutFor(v[0], v[1]);
            List<Rect> cards = new ArrayList<>();
            for (int row = 0; row < l.visibleRows(); row++) {
                Rect card = l.card(row);
                cards.add(card);
                Ui2LayoutAssert.inside(l.list(), card, at + " card " + row);
                for (Rect action : List.of(l.dismiss(row), l.fire(row), l.summon(row), l.hire(row))) {
                    Ui2LayoutAssert.inside(card, action, at + " action " + row);
                }
                Ui2LayoutAssert.disjoint(List.of(l.summon(row), l.fire(row)), at + " staff actions");
                // Text columns end before the actions that share their lines.
                int textX = card.x() + PlaqueScreen.TEXT_OFF;
                assertTrue(textX + PlaqueScreen.NAME_BOX <= card.x() + PlaqueScreen.PIPS_OFF, at + " name/pips");
                assertTrue(card.x() + PlaqueScreen.PIPS_OFF + PlaqueScreen.PIPS_W <= l.hire(row).x(),
                    at + " pips/hire");
                assertTrue(textX + PlaqueScreen.POST_BOX <= l.dismiss(row).x(), at + " post/dismiss");
                assertTrue(textX + PlaqueScreen.STAFF_ROW_BOX <= l.summon(row).x(), at + " staff/summon");
                // The hire cost sentence runs full width on the third line, under the Hire button.
                assertTrue(l.hire(row).bottom() <= card.y() + 27, at + " hire above cost line");
                assertTrue(textX + PlaqueScreen.COST_BOX <= card.right(), at + " cost line");
                assertTrue(card.x() + PlaqueScreen.REQ_TEXT_OFF + PlaqueScreen.REQ_BOX
                    <= card.x() + PlaqueScreen.REQ_MARK_OFF, at + " requirement/mark");
            }
            Ui2LayoutAssert.disjoint(cards, at + " cards");
        }
    }

    @Test
    void costSentenceBudgetKeepsTheMeasuredWorstCase() {
        // "The Carpenter's Shop would have no worker" measured 224px.
        assertTrue(PlaqueScreen.COST_BOX >= 228);
        // Crowded-settlement fallback "Gislebert the Younger" measured 111px.
        assertTrue(PlaqueScreen.NAME_BOX >= 126);
    }
}
