package com.hearthstead.entity.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class MineShaftTest {

    private static MineShaft.Model flatOpen() {
        MineShaft.Model m = new MineShaft.Model(64);
        java.util.Arrays.fill(m.open, true);
        return m;
    }

    @Test
    void aVerticalShaftIsNotReachableButAStairIs() {
        MineShaft.Model m = flatOpen();
        int c = MineShaft.index(0, 0);
        m.depth[c] = 3; // a 3-deep hole with vertical walls
        assertFalse(MineShaft.reachable(m)[c], "3-block wall cannot be climbed");
        m.depth[MineShaft.index(1, 0)] = 2;
        m.depth[MineShaft.index(2, 0)] = 1;
        assertTrue(MineShaft.reachable(m)[c], "a one-block stair leads out");
    }

    @Test
    void theRuleRefusesTheCutThatWallsItsOwnMinerIn() {
        MineShaft.Model m = flatOpen();
        int c = MineShaft.index(0, 0);
        m.depth[c] = 1;
        boolean[] before = MineShaft.reachable(m);
        assertTrue(before[c]);
        assertFalse(MineShaft.safeToDeepen(m, c, 2, before),
            "2 deep with every neighbour at 0 is a trap");
        m.depth[MineShaft.index(1, 0)] = 1;
        before = MineShaft.reachable(m);
        assertTrue(MineShaft.safeToDeepen(m, c, 2, before), "a neighbour one step up makes it a stair");
    }

    @Test
    void headRoomUnderAWallBlocksTheStep() {
        MineShaft.Model m = flatOpen();
        int c = MineShaft.index(0, 0);
        m.depth[c] = 1;
        for (int i = 0; i < m.open.length; i++) {
            m.open[i] = false; // everything around is wall at head height
        }
        m.open[c] = true;
        m.open[MineShaft.index(0, 1)] = true;
        assertTrue(MineShaft.reachable(m)[c]);
        m.open[MineShaft.index(0, 1)] = false;
        assertFalse(MineShaft.reachable(m)[c], "no open surface column next to it");
    }

    /** Many random greedy digs under the rule: never a trapped column. */
    @Test
    void greedyDiggingUnderTheRuleNeverTrapsAnyColumn() {
        Random random = new Random(42);
        for (int run = 0; run < 40; run++) {
            MineShaft.Model m = flatOpen();
            for (int step = 0; step < 400; step++) {
                boolean[] before = MineShaft.reachable(m);
                int column = random.nextInt(before.length);
                if (!before[column] || !MineShaft.inDigArea(column) || m.depth[column] >= 12) {
                    continue;
                }
                int next = m.depth[column] + 1;
                if (MineShaft.safeToDeepen(m, column, next, before)) {
                    m.depth[column] = next;
                }
            }
            boolean[] reach = MineShaft.reachable(m);
            int deepest = 0;
            for (int i = 0; i < reach.length; i++) {
                if (m.depth[i] > 0) {
                    assertTrue(reach[i], "run " + run + " trapped column " + i);
                }
                deepest = Math.max(deepest, m.depth[i]);
            }
            assertTrue(deepest >= 3, "the rule must still let the mine go down, got " + deepest);
        }
    }
}
