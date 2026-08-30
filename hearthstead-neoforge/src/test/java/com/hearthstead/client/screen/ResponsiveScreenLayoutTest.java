package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic preflight for the logical viewports used by native QA.
 * These are geometry contracts only; they deliberately do not replace a
 * rendered Minecraft client pass in either language.
 */
class ResponsiveScreenLayoutTest {

    private static final int[] TARGET_HEIGHTS = {240, 270, 360};

    @Test
    void hearthLedgerLeavesRealPixelsForTabsAtScaleThree() {
        int top = HearthScreen.hearthTopFor(240, 218);
        assertEquals(20, top);
        assertTrue(top - 20 >= 0, "folder tabs must be on-screen");
        assertTrue(top + 218 <= 240, "command center must be on-screen");
        assertTrue(HearthScreen.hearthTabsFitFor(427, 220));
        assertTrue(HearthScreen.hearthTabsFitFor(480, 220));
        assertTrue(HearthScreen.hearthTabsFitFor(640, 220));
    }

    @Test
    void mayorAndJourneyPopoutsFitEveryNativeTargetHeight() {
        for (int viewportHeight : TARGET_HEIGHTS) {
            HearthScreen.MayorLayout mayor =
                HearthScreen.mayorLayoutFor(viewportHeight);
            assertTrue(mayor.panelHeight() <= viewportHeight);
            assertTrue(mayor.visibleRows() >= 1);
            assertEquals(mayor.visibleRows() * 42 - 4,
                mayor.listHeight());
            assertTrue(mayor.foot() + 33 <= mayor.panelHeight(),
                "Mayor footer and bottom padding must remain inside");

            HearthScreen.JourneyLayout journey =
                HearthScreen.journeyLayoutFor(viewportHeight);
            assertTrue(journey.panelHeight() <= viewportHeight);
            int lastCardBottom = 66
                + (journey.stepHeight() + journey.stepGap())
                + journey.stepHeight();
            assertTrue(lastCardBottom < journey.footDividerY(),
                "the bounded current and next cards must clear the footer divider");
            assertTrue(journey.buttonY() + 20 <= journey.panelHeight(),
                "skip/confirm controls must remain clickable");
            int readinessRows = HearthScreen.readinessRowsFor(
                journey.panelHeight());
            int readinessListBottom = 59 + readinessRows * 34 - 4;
            assertTrue(readinessListBottom < journey.footDividerY(),
                "readiness blocker cards must clear the footer divider");
            assertTrue(readinessRows >= 3,
                "the compact checklist must retain a useful scroll window");
        }
    }

    @Test
    void requestLedgerWaitIsBoundedAndCannotFailWhileHidden() {
        assertFalse(HearthScreen.requestLoadTimedOut(true, true, 99));
        assertTrue(HearthScreen.requestLoadTimedOut(true, true, 100));
        assertFalse(HearthScreen.requestLoadTimedOut(false, true,
            Integer.MAX_VALUE), "a closed panel cannot publish a false error");
        assertFalse(HearthScreen.requestLoadTimedOut(true, false,
            Integer.MAX_VALUE), "a completed request cannot regress to error");
    }

    @Test
    void handbookKeepsNavigationAndScrollableBodyInsideEveryTarget() {
        for (int viewportHeight : TARGET_HEIGHTS) {
            HandbookScreen.HandbookLayout layout =
                HandbookScreen.layoutFor(viewportHeight);
            assertTrue(layout.panelHeight() <= viewportHeight);
            assertTrue(layout.bodyRows() >= 1);
            assertTrue(layout.sidebarRows() >= 1);
            assertTrue(layout.divider3Y() > 48);
            assertTrue(layout.pageListY() >= layout.divider3Y());
            assertTrue(layout.counterY() >= layout.pageListY() + 14);
            assertTrue(layout.navY() + 20 <= layout.panelHeight());
        }
    }

    @Test
    void handbookManualIndexScrollCanActuallyReachLaterChapters() {
        assertEquals(1, HandbookScreen.chapterScrollFor(0, 1, 8, false),
            "manual scrolling must not snap back to chapter one");
        assertEquals(0, HandbookScreen.chapterScrollFor(0, 1, 8, true),
            "page navigation must still reveal the active chapter");
        assertEquals(6, HandbookScreen.chapterScrollFor(13, 0, 8, true),
            "the final chapter must be brought into the eight-row window");
    }

    @Test
    void emblemShopKeepsInspectMayorAndCloseReachableAtScaleThree() {
        EmblemShopScreen.ScreenLayout layout =
            EmblemShopScreen.layoutFor(427, 240);
        assertEquals(354, layout.panelWidth());
        assertEquals(224, layout.panelHeight());
        assertEquals(3, layout.visibleRows());
        assertTrue(layout.inspectButtonWidth() >= 96,
            "Inspect Mayor must retain a readable native button");
        assertTrue(layout.footerTextWidth() >= 120,
            "server feedback must retain a useful two-line column");
        assertTrue(52 + layout.visibleRows() * 43 - 4
                < layout.panelHeight() - 38,
            "the emblem cards must clear both footer actions");
        assertTrue(2 * 10 + layout.footerTextWidth() + 8
                + layout.inspectButtonWidth() + 6 + 64
                <= layout.panelWidth(),
            "feedback, Inspect Mayor and Close must not overlap");
    }
}
