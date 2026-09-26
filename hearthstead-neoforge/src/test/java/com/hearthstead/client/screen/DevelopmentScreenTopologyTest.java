package com.hearthstead.client.screen;

import com.hearthstead.settlement.development.DevelopmentNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentScreenTopologyTest {

    @Test
    void storesAndRoadsSplitsIntoReadableEconomicAndEarlyWatchRoutes() {
        DevelopmentNode[] economicRoute = {
            DevelopmentNode.SETTLEMENT_CHARTER,
            DevelopmentNode.SHELTER,
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND
        };

        for (int i = 1; i < economicRoute.length; i++) {
            DevelopmentNode previous = economicRoute[i - 1];
            DevelopmentNode current = economicRoute[i];
            assertTrue(hasEdge(previous, current),
                () -> "missing visible economic edge " + previous + " -> " + current);
        }
        // Hub layout: Charter / First Fire at the centre, Craft & Trade to the
        // west (Lumber, Fields), Logistics to the east (Warehouse).
        assertTrue(radius(DevelopmentNode.SETTLEMENT_CHARTER) < 120);
        assertTrue(radius(DevelopmentNode.SHELTER) < 120);
        assertTrue(DevelopmentScreen.worldX(DevelopmentNode.TIMBER_RIGHTS) < 0);
        assertTrue(DevelopmentScreen.worldX(DevelopmentNode.CULTIVATED_GROUND)
            < DevelopmentScreen.worldX(DevelopmentNode.TIMBER_RIGHTS));
        assertTrue(DevelopmentScreen.worldX(DevelopmentNode.STORES_AND_ROADS) > 0);

        assertTrue(hasEdge(DevelopmentNode.SHELTER, DevelopmentNode.HOME));
        assertTrue(hasEdge(DevelopmentNode.HOME, DevelopmentNode.HOSPITALITY));
        assertFalse(hasEdge(DevelopmentNode.CULTIVATED_GROUND, DevelopmentNode.HOME),
            "housing must be available before automated farming");
        assertTrue(DevelopmentScreen.worldY(DevelopmentNode.HOME)
                > DevelopmentScreen.worldY(DevelopmentNode.SHELTER),
            "Commons & Household radiates south of Banner Raised");
        assertTrue(DevelopmentScreen.worldY(DevelopmentNode.HOSPITALITY)
            > DevelopmentScreen.worldY(DevelopmentNode.HOME));
        assertTrue(DevelopmentScreen.worldY(DevelopmentNode.HOME)
                != DevelopmentScreen.worldY(DevelopmentNode.TIMBER_RIGHTS),
            "housing and production need distinct visible lanes");

        assertTrue(hasEdge(DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.FIRST_WATCH));
        assertFalse(hasEdge(DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH),
            "First Watch must not visually claim Tavern as its prerequisite");
        assertTrue(DevelopmentScreen.worldY(DevelopmentNode.FIRST_WATCH) < -150,
            "Watch & Defense radiates north of the centre");
        assertTrue(Math.abs(DevelopmentScreen.worldX(DevelopmentNode.FIRST_WATCH)) < 100);

        assertFalse(hasEdge(DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.CULTIVATED_GROUND),
            "Farmhouse must not visually bypass the Warehouse-first logistics loop");
        assertFalse(hasEdge(DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.HOSPITALITY),
            "Tavern must not visually bypass the physical Home step");
    }

    @Test
    void armTheWatchIsAVisibleIntermediateStepNotAHiddenSideDoor() {
        assertTrue(hasEdge(DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.ARM_THE_WATCH));
        assertTrue(hasEdge(DevelopmentNode.ARM_THE_WATCH,
            DevelopmentNode.FIRST_RAID_AFTERMATH));
        assertFalse(hasEdge(DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.FIRST_RAID_AFTERMATH),
            "the UI must not draw the obsolete direct progression edge");

        assertTrue(radius(DevelopmentNode.ARM_THE_WATCH)
            > radius(DevelopmentNode.FIRST_WATCH));
        assertTrue(radius(DevelopmentNode.FIRST_RAID_AFTERMATH)
            > radius(DevelopmentNode.ARM_THE_WATCH));
        for (DevelopmentNode doctrine : new DevelopmentNode[] {
                DevelopmentNode.SHIELD_DOCTRINE, DevelopmentNode.GUILD_DOCTRINE,
                DevelopmentNode.HEARTH_DOCTRINE}) {
            assertTrue(radius(doctrine) > radius(DevelopmentNode.FIRST_RAID_AFTERMATH),
                () -> doctrine + " must sit on the post-raid outer ring");
        }
    }

    private static double radius(DevelopmentNode node) {
        return Math.hypot(DevelopmentScreen.worldX(node), DevelopmentScreen.worldY(node));
    }

    private static boolean hasEdge(DevelopmentNode from, DevelopmentNode to) {
        return Arrays.stream(DevelopmentScreen.EDGES).anyMatch(edge ->
            edge.length == 2 && edge[0] == from && edge[1] == to);
    }
}
