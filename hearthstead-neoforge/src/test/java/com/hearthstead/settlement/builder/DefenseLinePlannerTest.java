package com.hearthstead.settlement.builder;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefenseLinePlannerTest {

    @Test
    void mostlyEastSnapsToTheXAxisWithAGateInTheMiddle() {
        DefenseLinePlanner.Line line = DefenseLinePlanner.plan(0, 0, 10, 2, true, 0, 4);
        assertEquals(1, line.dx());
        assertEquals(0, line.dz());
        assertEquals(11, line.columns().size());
        for (DefenseLinePlanner.Column c : line.columns()) {
            assertEquals(0, c.z());
        }
        assertEquals(5, line.gateIndex());
        assertTrue(line.columns().get(0).post());
        assertTrue(line.columns().get(10).post());
        // The gate is flanked by posts.
        assertTrue(line.columns().get(4).post());
        assertTrue(line.columns().get(6).post());
        assertFalse(line.columns().get(5).post());
    }

    @Test
    void gateOffsetMovesTheGateButNeverOntoAnEndPost() {
        DefenseLinePlanner.Line far = DefenseLinePlanner.plan(0, 0, 0, 8, true, 99, 0);
        assertEquals(7, far.gateIndex());
        DefenseLinePlanner.Line near = DefenseLinePlanner.plan(0, 0, 0, 8, true, -99, 0);
        assertEquals(1, near.gateIndex());
    }

    @Test
    void diagonalLinesAreWatertightAndGateless() {
        DefenseLinePlanner.Line line = DefenseLinePlanner.plan(0, 0, 5, 5, true, 0, 0);
        assertTrue(line.diagonal());
        assertEquals(-1, line.gateIndex(), "a diagonal wall carries no gate");
        Set<Long> cells = new HashSet<>();
        for (DefenseLinePlanner.Column c : line.columns()) {
            cells.add(((long) c.x() << 32) | (c.z() & 0xffffffffL));
        }
        // Every diagonal step has an orthogonal neighbour filled: no corner gap.
        for (int i = 0; i < 5; i++) {
            long filler = ((long) (i + 1) << 32) | i;
            assertTrue(cells.contains(filler), "filler at step " + i);
        }
    }

    @Test
    void lengthIsCapped() {
        DefenseLinePlanner.Line line = DefenseLinePlanner.plan(0, 0, 200, 0, false, 0, 4);
        assertEquals(DefenseLinePlanner.MAX_LENGTH, line.columns().size());
    }

    @Test
    void aSinglePointIsOneColumnWithoutGate() {
        DefenseLinePlanner.Line line = DefenseLinePlanner.plan(3, 3, 3, 3, true, 0, 4);
        assertEquals(1, line.columns().size());
        assertEquals(-1, line.gateIndex());
    }
}
