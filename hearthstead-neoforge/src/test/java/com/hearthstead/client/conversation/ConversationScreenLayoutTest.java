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
 * it overlaps, from 427x240 up to 1920x1080 at GUI 2-4, in three
 * zones (header bar, text box, answer area) that never overlap.
 */
class ConversationScreenLayoutTest {
    private static final int[][] VIEWPORTS = {{960, 540}, {640, 360}, {480, 270}, {427, 240}};

    private static void check(int vw, int vh, ConversationScreen.PanelLayout l, String at) {
        Rect p = l.panel();
        Ui2LayoutAssert.inViewport(vw, vh, p, at);
        assertTrue(p.width() >= Math.min(ConversationScreen.MIN_W, vw - 16) && p.width() <= ConversationScreen.MAX_W,
            at + ": width " + p.width());
        assertEquals(vw / 2, p.x() + p.width() / 2, 1, at + ": centred");
        // Three clear zones (owner, round 2): header bar, text box, answer area; plus the shared strip on top.
        List<Rect> zones = new ArrayList<>();
        if (l.strip().height() > 0) zones.add(l.strip());
        zones.add(l.headerBar());
        zones.add(l.body());
        zones.add(l.answers());
        for (Rect r : zones) Ui2LayoutAssert.inside(p, r, at);
        Ui2LayoutAssert.disjoint(zones, at + " zones");
        if (l.strip().height() > 0) assertTrue(l.strip().bottom() <= l.headerBar().y(), at + ": strip at the very top");
        assertTrue(l.headerBar().bottom() < l.body().y(), at + ": text box below the header bar");
        assertEquals(ConversationScreen.GUTTER, l.body().y() - l.headerBar().bottom(), at + ": 4 px gutter");
        assertEquals(ConversationScreen.GUTTER, l.answers().y() - 1 - l.body().bottom(), at + ": 4 px gutter + divider");
        assertTrue(l.body().bottom() < l.answers().y(), at + ": answers below the text box");
        assertTrue(l.headerBar().height() >= ConversationScreen.PORTRAIT + 4, at + ": header holds the portrait");
        // Header bar content: portrait left, name block, relation badge right; none overlap.
        List<Rect> head = List.of(l.portrait(), l.header(), l.relation());
        for (Rect r : head) Ui2LayoutAssert.inside(l.headerBar(), r, at + " header");
        Ui2LayoutAssert.disjoint(head, at + " header");
        assertTrue(l.portrait().x() < l.header().x() && l.header().right() <= l.relation().x(), at + ": header order");
        assertTrue(l.header().width() >= 60, at + ": room for the name");
        // Text box: three lines plus padding.
        assertTrue(l.body().height() >= l.bodyLines() * ConversationScreen.BODY_LINE + 2 * ConversationScreen.TEXT_PAD - 2,
            at + ": text box padding");
        // Answers: inside their area, same width, even spacing, no overlap.
        for (Rect r : l.options()) Ui2LayoutAssert.inside(l.answers(), r, at + " answers");
        Ui2LayoutAssert.disjoint(l.options(), at + " answers");
        for (int i = 1; i < l.options().size(); i++) {
            Rect prev = l.options().get(i - 1);
            Rect cur = l.options().get(i);
            assertEquals(prev.width(), cur.width(), at + ": same button width");
            assertEquals(ConversationScreen.GAP, cur.y() - prev.bottom(), at + ": even spacing");
        }
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
            assertTrue(l.strip().bottom() <= l.headerBar().y(), at + ": strip on top");
            ConversationScreen.PanelLayout waiting = ConversationScreen.layoutFor(v[0], v[1], 200, 1, new int[] {1}, 1);
            check(v[0], v[1], waiting, at + " waiting");
            assertTrue(waiting.panel().bottom() <= v[1] - ConversationScreen.HOTBAR_CLEAR, at + ": waiting above the hotbar");
        }
    }

    @Test
    void relationPillShowsASignedStanding() {
        assertEquals("+12", ConversationScreen.relationNumber(12));
        assertEquals("-40", ConversationScreen.relationNumber(-40));
        assertEquals("", ConversationScreen.relationNumber(0));
        assertEquals("+100", ConversationScreen.relationNumber(500));
    }

    @Test
    void welcomeIntroNeverHoldsInputForMoreThanFourSeconds() {
        assertTrue(ConversationScreen.WELCOME_SECONDS > 2.5F && ConversationScreen.WELCOME_SECONDS <= 4.0F);
        assertEquals("conversation.hearthstead.gm_welcome.title", ConversationClient.WELCOME_TITLE);
    }
    @org.junit.jupiter.api.Test
    void waitingDistanceInstructionUsesTheUntruncatedPagedBody() {
        var status = net.minecraft.network.chat.Component.translatable(
            "conversation.hearthstead.shared.waiting", "Partner", 1, 2);
        var instruction = net.minecraft.network.chat.Component.translatable(
            "conversation.hearthstead.shared.waiting_hint", 12);
        org.junit.jupiter.api.Assertions.assertTrue(ConversationScreen.stripLine(status));
        org.junit.jupiter.api.Assertions.assertFalse(ConversationScreen.stripLine(instruction));
        org.junit.jupiter.api.Assertions.assertFalse(ConversationScreen.stripLine(
            net.minecraft.network.chat.Component.literal("Ordinary dialogue")));
        // Any wrapped instruction length still gets at most the approved three body rows.
        var shortBody = ConversationScreen.layoutFor(640, 360, 400, 3, new int[]{1}, 1);
        var longBody = ConversationScreen.layoutFor(640, 360, 400, 12, new int[]{1}, 1);
        org.junit.jupiter.api.Assertions.assertEquals(shortBody.panel(), longBody.panel());
        org.junit.jupiter.api.Assertions.assertEquals(3, longBody.bodyLines());
    }
}
