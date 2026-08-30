package com.hearthstead.client.screen;

import com.hearthstead.settlement.development.DevelopmentNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentScreenTopologyTest {

    @Test
    void correctedTutorialTrunkIsVisibleInExactPlayableOrder() {
        DevelopmentNode[] trunk = {
            DevelopmentNode.SETTLEMENT_CHARTER,
            DevelopmentNode.SHELTER,
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.ARM_THE_WATCH,
            DevelopmentNode.FIRST_RAID_AFTERMATH
        };

        for (int i = 1; i < trunk.length; i++) {
            DevelopmentNode previous = trunk[i - 1];
            DevelopmentNode current = trunk[i];
            assertTrue(hasEdge(previous, current),
                () -> "missing visible trunk edge " + previous + " -> " + current);
            assertEquals(180,
                DevelopmentScreen.worldX(current)
                    - DevelopmentScreen.worldX(previous),
                () -> current + " must remain one readable column after " + previous);
        }

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

        assertEquals(180, DevelopmentScreen.worldX(DevelopmentNode.ARM_THE_WATCH)
            - DevelopmentScreen.worldX(DevelopmentNode.FIRST_WATCH));
        assertEquals(180,
            DevelopmentScreen.worldX(DevelopmentNode.FIRST_RAID_AFTERMATH)
                - DevelopmentScreen.worldX(DevelopmentNode.ARM_THE_WATCH));
        assertTrue(DevelopmentScreen.worldX(DevelopmentNode.SHIELD_DOCTRINE)
                > DevelopmentScreen.worldX(DevelopmentNode.FIRST_RAID_AFTERMATH),
            "the doctrine split must remain downstream of the new trunk node");
    }

    private static boolean hasEdge(DevelopmentNode from, DevelopmentNode to) {
        return Arrays.stream(DevelopmentScreen.EDGES).anyMatch(edge ->
            edge.length == 2 && edge[0] == from && edge[1] == to);
    }
}
