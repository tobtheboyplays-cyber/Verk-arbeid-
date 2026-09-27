package com.hearthstead.settlement.guard.patrol;

import com.hearthstead.settlement.guard.FormationMath;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walking formation slots (FormationMath lines) measured along the leader's trail. */
class PatrolFormationTest {
    private static final BlockPos LEADER = new BlockPos(10, 64, 10);

    @Test
    void theLeaderHoldsTheFrontSlot() {
        for (PatrolRoute.Formation f : PatrolRoute.Formation.values()) {
            for (int size = 1; size <= 4; size++) {
                assertEquals(new PatrolFormation.Offset(0, 0), PatrolFormation.slot(f, size, 0), f + " size " + size);
            }
        }
    }

    @Test
    void aColumnIsSingleFileTwoBlocksApart() {
        assertEquals(new PatrolFormation.Offset(1, 0), PatrolFormation.slot(PatrolRoute.Formation.COLUMN, 4, 1));
        assertEquals(new PatrolFormation.Offset(3, 0), PatrolFormation.slot(PatrolRoute.Formation.COLUMN, 4, 3));
        // Walking south (octant 0, +Z) with no trail yet: straight behind, to the north.
        BlockPos third = PatrolFormation.target(PatrolRoute.Formation.COLUMN, 4, 3, LEADER, List.of(), 0);
        assertEquals(new BlockPos(10, 64, 10 - 3 * PatrolFormation.RANK_GAP), third);
    }

    @Test
    void pairsWalkTwoAbreastWithTheSecondOnTheLeadersRight() {
        assertEquals(new PatrolFormation.Offset(0, 1), PatrolFormation.slot(PatrolRoute.Formation.PAIRS, 4, 2));
        assertEquals(new PatrolFormation.Offset(1, 0), PatrolFormation.slot(PatrolRoute.Formation.PAIRS, 4, 1));
        assertEquals(new PatrolFormation.Offset(1, 1), PatrolFormation.slot(PatrolRoute.Formation.PAIRS, 4, 3));
        BlockPos right = PatrolFormation.target(PatrolRoute.Formation.PAIRS, 4, 2, LEADER, List.of(), 0);
        assertEquals(LEADER.offset(FormationMath.rightX(0), 0, FormationMath.rightZ(0)), right);
    }

    @Test
    void everySlotIsADistinctCell() {
        for (PatrolRoute.Formation f : PatrolRoute.Formation.values()) {
            Set<BlockPos> cells = new HashSet<>();
            for (int i = 0; i < 4; i++) cells.add(PatrolFormation.target(f, 4, i, LEADER, List.of(), 6));
            assertEquals(4, cells.size(), f.name());
        }
    }

    @Test
    void theColumnFollowsTheTrailRoundACorner() {
        // The leader walked east along z=10 then turned north up x=14: newest first.
        List<BlockPos> trail = List.of(new BlockPos(14, 64, 9), new BlockPos(14, 64, 10), new BlockPos(13, 64, 10),
            new BlockPos(12, 64, 10), new BlockPos(11, 64, 10), new BlockPos(10, 64, 10));
        BlockPos leader = new BlockPos(14, 64, 8);
        int octant = PatrolFormation.heading(new BlockPos(14, 64, 10), leader, 0);
        assertEquals(4, octant, "heading north");
        BlockPos second = PatrolFormation.target(PatrolRoute.Formation.COLUMN, 3, 1, leader, trail, octant);
        BlockPos third = PatrolFormation.target(PatrolRoute.Formation.COLUMN, 3, 2, leader, trail, octant);
        assertEquals(new BlockPos(14, 64, 10), second, "two blocks back along the trail");
        assertEquals(new BlockPos(12, 64, 10), third, "round the corner, not across it");
        assertNotEquals(new BlockPos(14, 64, 12), third);
    }

    @Test
    void headingMatchesMinecraftOctants() {
        BlockPos o = BlockPos.ZERO;
        assertEquals(0, PatrolFormation.heading(o, new BlockPos(0, 0, 5), 7));
        assertEquals(2, PatrolFormation.heading(o, new BlockPos(-5, 0, 0), 7));
        assertEquals(6, PatrolFormation.heading(o, new BlockPos(5, 0, 0), 7));
        assertEquals(7, PatrolFormation.heading(o, o, 7), "no movement keeps the fallback");
        assertTrue(FormationMath.validOctant(PatrolFormation.heading(o, new BlockPos(3, 0, -4), 0)));
    }
}
