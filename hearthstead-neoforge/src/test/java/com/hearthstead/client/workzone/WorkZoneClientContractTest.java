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
    void exactSecondCornerAttackIsCapturedBeforeBlockDamage() {
        assertEquals(WorkZoneClient.AttackDecision.CAPTURE_AND_CANCEL_BLOCK_DAMAGE,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, true, true));
        assertEquals(WorkZoneClient.AttackDecision.CAPTURE_AND_CANCEL_BLOCK_DAMAGE,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.CORNER_TWO, false,
                true, true, true),
            "the third height click must be captured without damaging its block");
    }

    @Test
    void everyNonExactAttackStatePassesThrough() {
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideAttack(false,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, true, true));
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.TARGET_SELECTED, false,
                true, true, true));
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, true,
                true, true, true));
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                false, true, true));
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, false, true), "offhand-only Scepter is not a valid session");
        assertEquals(WorkZoneClient.AttackDecision.PASS,
            WorkZoneClient.decideAttack(true,
                WorkZoneSnapshotPayload.Stage.CORNER_ONE, false,
                true, true, false));
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
