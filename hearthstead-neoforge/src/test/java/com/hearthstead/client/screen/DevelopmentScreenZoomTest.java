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

        assertEquals(44, ultra.width());
        assertEquals(18, ultra.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.OVERVIEW, ultra.mode());
        assertEquals(61, overview.width());
        assertEquals(23, overview.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.OVERVIEW, overview.mode());
        assertTrue(ultra.width() < overview.width(),
            "30% overview must show more map than 42% overview");
    }

    @Test
    void detailedGeometryBeginsAtFiftySixPercent() {
        DevelopmentScreen.NodeGeometry detailed = DevelopmentScreen.nodeGeometry(0.56F);

        assertEquals(82, detailed.width());
        assertEquals(36, detailed.height());
        assertEquals(DevelopmentScreen.NodeVisualMode.DETAILED, detailed.mode());
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
