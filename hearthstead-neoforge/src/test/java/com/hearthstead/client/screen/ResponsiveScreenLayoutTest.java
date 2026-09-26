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
    void councilNavigationLivesInsideTheTableAtScaleThree() {
        int top = HearthScreen.hearthTopFor(240, 224);
        assertEquals(8, top);
        assertTrue(top + 224 <= 240, "council table must be on-screen");
        assertTrue(HearthScreen.hearthTabsFitFor(320, 304));
        assertTrue(HearthScreen.hearthTabsFitFor(427, 411));
        assertTrue(HearthScreen.hearthTabsFitFor(640, 512));
        assertFalse(HearthScreen.hearthTabsFitFor(320, 512));
    }

    @Test
    void mayorAndJourneyPopoutsFitEveryNativeTargetHeight() {
        for (int viewportHeight : TARGET_HEIGHTS) {
            HearthScreen.MayorLayout mayor =
                HearthScreen.mayorLayoutFor(427, viewportHeight);
            assertEquals(411, mayor.panelWidth());
            assertTrue(mayor.panelHeight() <= viewportHeight);
            assertEquals(3, mayor.visibleRows(),
                "every 240px-or-taller native viewport fits three people");
            assertEquals(mayor.visibleRows() * 42 - 4,
                mayor.listHeight());
            assertEquals(mayor.foot() + 6, mayor.buttonY());
            assertEquals(mayor.buttonY() + 20 + 8, mayor.panelHeight(),
                "Mayor pagination and bottom padding must remain inside");

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
    void mayorRosterContractsWithoutLosingRowsAtMinimumSupportedViewport() {
        HearthScreen.MayorLayout narrow =
            HearthScreen.mayorLayoutFor(320, 240);
        assertEquals(304, narrow.panelWidth());
        assertEquals(224, narrow.panelHeight());
        assertEquals(3, narrow.visibleRows());

        HearthScreen.MayorLayout approved =
            HearthScreen.mayorLayoutFor(427, 240);
        assertEquals(411, approved.panelWidth());
        assertEquals(224, approved.panelHeight());
        assertEquals(3, approved.visibleRows());
    }

    @Test
    void mayorRosterPagesExactGroupsWithoutSkippingOrInventingRows() {
        assertEquals(1, HearthScreen.mayorPageCount(0, 3));
        assertEquals(1, HearthScreen.mayorPageCount(3, 3));
        assertEquals(2, HearthScreen.mayorPageCount(4, 3));
        assertEquals(22, HearthScreen.mayorPageCount(64, 3));
        assertEquals(0, HearthScreen.mayorPageStart(-5, 7, 3));
        assertEquals(3, HearthScreen.mayorPageStart(1, 7, 3));
        assertEquals(6, HearthScreen.mayorPageStart(99, 7, 3));
        assertEquals(2, HearthScreen.mayorClampPage(99, 7, 3));
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
    void handbookFrameStaysInsideEveryTargetViewport() {
        // The handbook's own rectangles and page layout are covered in depth
        // by HandbookLayoutTest; this keeps it in the shared native-target sweep.
        for (int viewportHeight : TARGET_HEIGHTS) {
            for (int viewportWidth : new int[] {320, 427, 480, 640}) {
                var geo = com.hearthstead.client.ui2.handbook.HandbookGeometry
                    .forViewport(viewportWidth, viewportHeight);
                assertTrue(geo.width() <= viewportWidth && geo.height() <= viewportHeight,
                    "handbook panel fits " + viewportWidth + "x" + viewportHeight);
                assertTrue(geo.content().height() >= 60, "page has reading room");
                assertTrue(geo.next().right() <= geo.inner().right(), "Next stays on the board");
            }
        }
    }

    @Test
    void emblemShopKeepsInspectMayorAndCloseReachableAtScaleThree() {
        EmblemShopScreen.ScreenLayout layout =
            EmblemShopScreen.layoutFor(427, 240);
        assertEquals(411, layout.panelWidth());
        assertEquals(224, layout.panelHeight());
        assertEquals(2, layout.visibleRows());
        assertTrue(layout.inspectButtonWidth() >= 96,
            "Inspect Mayor must retain a readable native button");
        assertTrue(layout.footerTextWidth() >= 120,
            "server feedback must retain a useful two-line column");
        assertTrue(52 + layout.visibleRows() * 62 - 4
                < layout.panelHeight() - 46,
            "the emblem cards must clear both footer actions");
        assertTrue(2 * 10 + layout.footerTextWidth() + 8
                + layout.inspectButtonWidth() + 6 + 64
                <= layout.panelWidth(),
            "feedback, Inspect Mayor and Close must not overlap");
    }
}
