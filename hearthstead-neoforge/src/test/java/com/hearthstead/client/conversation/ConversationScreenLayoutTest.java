package com.hearthstead.client.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The compact talk panel (27 Sep): bottom-centre above the hotbar, fitted to its
 * content between 260 and 440 GUI px, at most three speech lines, and nothing in
 * it overlaps, from 427x240 up to 1920x1080 at GUI 2-4.
 */
class ConversationScreenLayoutTest {
    private static final int[][] VIEWPORTS = {{960, 540}, {640, 360}, {480, 270}, {427, 240}};

    private static void check(int vw, int vh, ConversationScreen.PanelLayout l, String at) {
        Rect p = l.panel();
        Ui2LayoutAssert.inViewport(vw, vh, p, at);
        assertTrue(p.width() >= Math.min(ConversationScreen.MIN_W, vw - 16) && p.width() <= ConversationScreen.MAX_W,
            at + ": width " + p.width());
        assertEquals(vw / 2, p.x() + p.width() / 2, 1, at + ": centred");
        List<Rect> parts = new ArrayList<>();
        if (l.strip().height() > 0) parts.add(l.strip());
        parts.add(l.portrait());
        parts.add(l.header());
        parts.add(l.relation());
        parts.add(l.body());
        parts.addAll(l.options());
        for (Rect r : parts) Ui2LayoutAssert.inside(p, r, at);
        Ui2LayoutAssert.disjoint(parts, at);
        assertTrue(l.bodyLines() >= 1 && l.bodyLines() <= ConversationScreen.BODY_LINES, at + ": body lines");
        assertTrue(l.portrait().width() >= ConversationScreen.PORTRAIT && l.portrait().height() >= ConversationScreen.PORTRAIT,
            at + ": 32x32 portrait");
    }

    @Test
    void shortTalkIsCompactAndSitsAboveTheHotbar() {
        for (int[] v : VIEWPORTS) {
            String at = v[0] + "x" + v[1] + " short";
            ConversationScreen.PanelLayout l = ConversationScreen.layoutFor(v[0], v[1], 120, 1, new int[] {1, 1}, 0);
            check(v[0], v[1], l, at);
            assertEquals(ConversationScreen.MIN_W, l.panel().width(), at + ": short content keeps the minimum width");
            assertEquals(1, l.bodyLines(), at);
            assertTrue(l.panel().bottom() <= v[1] - ConversationScreen.HOTBAR_CLEAR, at + ": above the hotbar");
            for (Rect r : l.options()) assertEquals(ConversationScreen.BUTTON_H, r.height(), at + ": 14 px buttons");
        }
    }

    @Test
    void typicalTalkWithFourRepliesFitsAboveTheHotbar() {
        for (int[] v : VIEWPORTS) {
            String at = v[0] + "x" + v[1] + " typical";
            ConversationScreen.PanelLayout l = ConversationScreen.layoutFor(v[0], v[1], 330, 5, new int[] {1, 1, 2, 1}, 0);
            check(v[0], v[1], l, at);
            assertEquals(ConversationScreen.BODY_LINES, l.bodyLines(), at + ": long speech pages at three lines");
            assertTrue(l.panel().bottom() <= v[1] - ConversationScreen.HOTBAR_CLEAR, at + ": above the hotbar");
        }
    }

    @Test
    void sharedTalkStripAndLongestContentStillFit() {
        for (int[] v : VIEWPORTS) {
            String at = v[0] + "x" + v[1] + " shared";
            ConversationScreen.PanelLayout l = ConversationScreen.layoutFor(v[0], v[1], 2000, 20, new int[] {2, 2, 2, 2}, 2);
            check(v[0], v[1], l, at);
            assertEquals(Math.min(ConversationScreen.MAX_W, v[0] - 16), l.panel().width(), at + ": capped width");
            assertTrue(l.strip().bottom() <= l.portrait().y(), at + ": strip on top");
            ConversationScreen.PanelLayout waiting = ConversationScreen.layoutFor(v[0], v[1], 200, 1, new int[] {1}, 1);
            check(v[0], v[1], waiting, at + " waiting");
            assertTrue(waiting.panel().bottom() <= v[1] - ConversationScreen.HOTBAR_CLEAR, at + ": waiting above the hotbar");
        }
    }

    @Test
    void welcomeIntroNeverHoldsInputForMoreThanFourSeconds() {
        assertTrue(ConversationScreen.WELCOME_SECONDS > 2.5F && ConversationScreen.WELCOME_SECONDS <= 4.0F);
        assertEquals("conversation.hearthstead.gm_welcome.title", ConversationClient.WELCOME_TITLE);
    }
}
