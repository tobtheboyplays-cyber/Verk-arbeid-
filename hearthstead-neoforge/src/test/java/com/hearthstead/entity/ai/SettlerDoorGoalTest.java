package com.hearthstead.entity.ai;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerDoorGoalTest {

    @Test
    void centreCrossingAloneDoesNotCloseDoorOnSettlersBack() {
        assertFalse(SettlerDoorGoal.safelyClearedDoor(true, -0.01D, 0.0D));
        assertFalse(SettlerDoorGoal.safelyClearedDoor(true, -0.99D, 0.0D));
    }

    @Test
    void doorMayCloseOnlyAfterCrossingAndClearingAFullBlock() {
        assertTrue(SettlerDoorGoal.safelyClearedDoor(true, -1.01D, 0.0D));
        assertFalse(SettlerDoorGoal.safelyClearedDoor(false, -2.0D, 0.0D));
    }

    @Test
    void terminalRecoveryIsOnlyForCompletedPartialPaths() {
        assertTrue(SettlerDoorGoal.terminalRecoveryEligible(true, false));
        assertFalse(SettlerDoorGoal.terminalRecoveryEligible(true, true),
            "a successful arrival must not open a door beyond its target");
        assertFalse(SettlerDoorGoal.terminalRecoveryEligible(false, false));
    }

    @Test
    void terminalRecoveryUsesOneDominantTargetAxis() {
        assertEquals(new BlockPos(1, 0, 0),
            SettlerDoorGoal.dominantCardinalStep(new BlockPos(8, 1, 4),
                new BlockPos(12, 1, 5)));
        assertEquals(new BlockPos(0, 0, -1),
            SettlerDoorGoal.dominantCardinalStep(new BlockPos(8, 1, 4),
                new BlockPos(7, 1, 0)));
        assertNull(SettlerDoorGoal.dominantCardinalStep(
            new BlockPos(8, 1, 4), new BlockPos(10, 1, 6)),
            "an equal-axis target is ambiguous and must fail closed");
        assertNull(SettlerDoorGoal.dominantCardinalStep(
            new BlockPos(8, 0, 5), new BlockPos(8, 3, 5)),
            "a vertical-only target must not create a horizontal door scan");
    }

    @Test
    void diagonalTargetTieUsesOnlyTheForwardTerminalPathEdge() {
        BlockPos previous = new BlockPos(8, 1, 3);
        BlockPos terminal = new BlockPos(8, 1, 4);
        BlockPos diagonalTarget = new BlockPos(11, 1, 7);
        assertEquals(new BlockPos(0, 0, 1),
            SettlerDoorGoal.terminalContinuationStep(previous, terminal,
                diagonalTarget),
            "a door directly ahead remains recoverable in a diagonal room");
        assertNull(SettlerDoorGoal.terminalContinuationStep(
            new BlockPos(7, 1, 3), terminal, diagonalTarget),
            "a diagonal final path edge is still ambiguous and must fail closed");
        assertNull(SettlerDoorGoal.terminalContinuationStep(
            new BlockPos(8, 1, 5), terminal, diagonalTarget),
            "a final edge pointing away from the selected target has no authority");
    }

    @Test
    void terminalRecoveryStopsAtWallBeforeDoor() {
        BlockPos origin = new BlockPos(0, 1, 0);
        BlockPos door = origin.east(2);
        assertNull(SettlerDoorGoal.firstDoorBeforeObstruction(origin,
            new BlockPos(1, 0, 0), 3,
            candidate -> candidate.equals(door) ? door : null,
            candidate -> false),
            "a closed door behind the first solid cell must never be opened");
    }

    @Test
    void terminalRecoveryFindsFirstDoorAcrossOpenFloorOnly() {
        BlockPos origin = new BlockPos(0, 1, 0);
        BlockPos open = origin.east();
        BlockPos door = origin.east(2);
        assertEquals(door, SettlerDoorGoal.firstDoorBeforeObstruction(origin,
            new BlockPos(1, 0, 0), 3,
            candidate -> candidate.equals(door) ? door : null,
            candidate -> candidate.equals(open)));
        assertNull(SettlerDoorGoal.firstDoorBeforeObstruction(origin,
            new BlockPos(1, 0, 1), 3,
            candidate -> candidate.equals(door) ? door : null,
            candidate -> true),
            "a diagonal scan must not select an unrelated side door");
        BlockPos remoteDoor = origin.east(4);
        assertNull(SettlerDoorGoal.firstDoorBeforeObstruction(origin,
            new BlockPos(1, 0, 0), 3,
            candidate -> candidate.equals(remoteDoor) ? remoteDoor : null,
            candidate -> true),
            "terminal recovery must never exceed its three-cell bound");
    }
}
