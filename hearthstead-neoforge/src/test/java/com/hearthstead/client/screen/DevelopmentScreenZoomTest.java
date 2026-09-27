package com.hearthstead.client.screen;

import com.hearthstead.settlement.development.DevelopmentNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentScreenZoomTest {
    @Test
    void overviewGeometryScalesWithoutForcingDetailCardsToOverlap() {
        DevelopmentScreen.NodeGeometry ultra = DevelopmentScreen.nodeGeometry(0.30F);
        DevelopmentScreen.NodeGeometry overview = DevelopmentScreen.nodeGeometry(0.42F);

        assertEquals(48, ultra.width());
        assertEquals(20, ultra.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.OVERVIEW, ultra.mode());
        assertEquals(68, overview.width());
        assertEquals(34, overview.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.OVERVIEW, overview.mode());
        assertTrue(ultra.width() < overview.width(),
            "30% overview must show more map than 42% overview");
    }

    @Test
    void openingOverviewReservesAnItemIconAndOneUnbrokenShortName() {
        DevelopmentScreen.NodeGeometry opening = DevelopmentScreen.nodeGeometry(0.42F);
        assertTrue(opening.width() >= 16 + 4 + 42,
            "42% opening cards need a 16px item icon and a short readable label");
        assertTrue(opening.height() >= 34,
            "42% opening cards need the full item silhouette without text overlap");
    }

    @Test
    void detailedGeometryBeginsAtFiftySixPercent() {
        DevelopmentScreen.NodeGeometry detailed = DevelopmentScreen.nodeGeometry(0.56F);

        assertEquals(84, detailed.width());
        assertEquals(41, detailed.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.DETAILED, detailed.mode());
    }

    @Test
    void defaultCardsReserveTwoReadableNameLines() {
        DevelopmentScreen.NodeGeometry defaultCard = DevelopmentScreen.nodeGeometry(0.86F);

        assertEquals(129, defaultCard.width());
        assertEquals(64, defaultCard.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.DETAILED, defaultCard.mode());
    }
    @Test
    void overviewNodeRectanglesNeverOverlapAtSupportedLowZooms() {
        assertNoOverlap(0.30F);
        assertNoOverlap(0.42F);
    }

    private static void assertNoOverlap(float zoom) {
        DevelopmentNode[] nodes = DevelopmentNode.PRESENTATION_ORDER;
        DevelopmentScreen.NodeGeometry geometry = DevelopmentScreen.nodeGeometry(zoom);
        for (int first = 0; first < nodes.length; first++) {
            for (int second = first + 1; second < nodes.length; second++) {
                DevelopmentNode a = nodes[first];
                DevelopmentNode b = nodes[second];
                assertFalse(intersects(a, b, zoom, geometry), () ->
                    "overview nodes overlap at " + zoom + ": " + a + " and " + b);
            }
        }
    }

    private static boolean intersects(DevelopmentNode a, DevelopmentNode b,
                                      float zoom, DevelopmentScreen.NodeGeometry geometry) {
        int ax = Math.round(DevelopmentScreen.worldX(a) * zoom);
        int ay = Math.round(DevelopmentScreen.worldY(a) * zoom);
        int bx = Math.round(DevelopmentScreen.worldX(b) * zoom);
        int by = Math.round(DevelopmentScreen.worldY(b) * zoom);
        return ax < bx + geometry.width() && ax + geometry.width() > bx
            && ay < by + geometry.height() && ay + geometry.height() > by;
    }
}
