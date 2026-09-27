package com.hearthstead.client.workzone;

import com.hearthstead.network.WorkZoneSnapshotPayload;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic preflight for the native mouse/dimension state machine. */
class WorkZoneClientContractTest {

    @Test
    void everyRightClickStageIsCapturedBeforeVanillaUse() {
        assertEquals(WorkZoneClient.AttackDecision.SET_FIRST_CORNER,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.TARGET_SELECTED, false,
                true, true, WorkZoneClient.WorldTarget.BLOCK));
        assertEquals(WorkZoneClient.AttackDecision.SET_SECOND_CORNER,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, true, WorkZoneClient.WorldTarget.BLOCK));
        assertEquals(WorkZoneClient.AttackDecision.SET_HEIGHT,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.CORNER_TWO, false,
                true, true, WorkZoneClient.WorldTarget.BLOCK),
            "the third height click must be captured before vanilla use");
        assertEquals(WorkZoneClient.AttackDecision.SELECT_SETTLER,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.UNKNOWN, false,
                true, true, WorkZoneClient.WorldTarget.SETTLER));
        assertEquals(WorkZoneClient.AttackDecision.SELECT_WORKPLACE,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.UNKNOWN, false,
                true, true, WorkZoneClient.WorldTarget.WORKPLACE));
    }

    @Test
    void scepterConsumesUnmappedRightClicksButNeverHijacksUiOrOtherHands() {
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideUse(false,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, true, WorkZoneClient.WorldTarget.BLOCK));
        assertEquals(WorkZoneClient.AttackDecision.CONSUME,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.UNKNOWN, false,
                true, true, WorkZoneClient.WorldTarget.BLOCK));
        assertEquals(WorkZoneClient.AttackDecision.CONSUME,
            WorkZoneClient.decideUse(true,
                WorkZoneSnapshotPayload.Stage.UNKNOWN, false,
                true, true, WorkZoneClient.WorldTarget.ENTITY));
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideUse(true,
            WorkZoneSnapshotPayload.Stage.CORNER_ONE, true,
                true, true, WorkZoneClient.WorldTarget.BLOCK));
        assertEquals(WorkZoneClient.AttackDecision.CONSUME,
            WorkZoneClient.decideUse(true,
            WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                false, true, WorkZoneClient.WorldTarget.BLOCK));
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideUse(true,
            WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, false, WorkZoneClient.WorldTarget.BLOCK), "offhand-only Scepter is not a valid session");
    }

    @Test
    void dimensionMismatchIsAnExplicitFailClosedDecision() {
        assertTrue(WorkZoneClient.dimensionsMatch("minecraft:overworld",
            "minecraft:overworld"));
        assertFalse(WorkZoneClient.dimensionsMatch("minecraft:the_nether",
            "minecraft:overworld"));
        assertFalse(WorkZoneClient.dimensionsMatch(null, "minecraft:overworld"));
    }

    @Test
    void lateResetCannotMatchANewerSession() {
        UUID settlement = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        WorkZoneSnapshotPayload old = snapshot(UUID.randomUUID(), settlement,
            building, WorkZoneSnapshotPayload.Stage.RESET);
        WorkZoneSnapshotPayload fresh = snapshot(UUID.randomUUID(), settlement,
            building, WorkZoneSnapshotPayload.Stage.CORNER_ONE);
        WorkZoneSnapshotPayload same = snapshot(fresh.sessionId(), settlement,
            building, WorkZoneSnapshotPayload.Stage.RESET);

        assertFalse(WorkZoneClient.sameIdentity(fresh, old));
        assertTrue(WorkZoneClient.sameIdentity(fresh, same));
    }

    @Test
    void lateForeignDimensionPacketCannotEraseANewerLocalSession() {
        UUID settlement = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        WorkZoneSnapshotPayload local = snapshot(UUID.randomUUID(), settlement,
            building, "minecraft:overworld",
            WorkZoneSnapshotPayload.Stage.CORNER_ONE);
        WorkZoneSnapshotPayload lateNether = snapshot(UUID.randomUUID(), settlement,
            building, "minecraft:the_nether",
            WorkZoneSnapshotPayload.Stage.RESET);
        WorkZoneSnapshotPayload sameSessionNether = snapshot(local.sessionId(),
            settlement, building, "minecraft:the_nether",
            WorkZoneSnapshotPayload.Stage.RESET);

        assertFalse(WorkZoneClient.shouldClearCurrentForForeignSnapshot(
            "minecraft:overworld", local, lateNether));
        assertTrue(WorkZoneClient.shouldClearCurrentForForeignSnapshot(
            "minecraft:overworld", local, sameSessionNether));
        assertTrue(WorkZoneClient.shouldClearCurrentForForeignSnapshot(
            "minecraft:the_nether", local, lateNether),
            "an active snapshot from the departed dimension must be dropped");
    }

    private static WorkZoneSnapshotPayload snapshot(UUID session,
                                                     UUID settlement,
                                                     UUID building,
                                                     WorkZoneSnapshotPayload.Stage stage) {
        return snapshot(session, settlement, building, "minecraft:overworld", stage);
    }

    private static WorkZoneSnapshotPayload snapshot(UUID session,
                                                     UUID settlement,
                                                     UUID building,
                                                     String dimension,
                                                     WorkZoneSnapshotPayload.Stage stage) {
        return new WorkZoneSnapshotPayload(session, settlement, building, 0,
            dimension, 0, stage, Optional.empty(), Optional.empty(),
            Component.literal("Lumber Camp"), Optional.empty());
    }
}
