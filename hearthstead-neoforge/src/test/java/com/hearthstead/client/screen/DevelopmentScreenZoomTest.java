package com.hearthstead.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentScreenZoomTest {
    @Test
    void ultraOverviewShowsMoreMapWithoutCollapsingCards() {
        int ultraWidth = DevelopmentScreen.scaledNodeWidth(0.30F);
        int overviewWidth = DevelopmentScreen.scaledNodeWidth(0.42F);
        int ultraHeight = DevelopmentScreen.scaledNodeHeight(0.30F);

        assertTrue(ultraWidth <= overviewWidth,
            "30% overview must never show less horizontal map than 42%");
        assertEquals(72, ultraWidth,
            "overview cards retain the readable minimum width");
        assertEquals(36, ultraHeight,
            "overview cards retain the readable minimum height");
    }

    @Test
    void overviewZoomActuallyShrinksCards() {
        int overviewWidth = DevelopmentScreen.scaledNodeWidth(0.42F);
        int normalWidth = DevelopmentScreen.scaledNodeWidth(1.0F);
        int overviewHeight = DevelopmentScreen.scaledNodeHeight(0.42F);
        int normalHeight = DevelopmentScreen.scaledNodeHeight(1.0F);

        assertEquals(72, overviewWidth);
        assertEquals(146, normalWidth);
        assertEquals(36, overviewHeight);
        assertEquals(54, normalHeight);
        assertTrue(overviewWidth * 2 <= normalWidth,
            "overview cards must be small enough to materially widen the map");
    }
}
