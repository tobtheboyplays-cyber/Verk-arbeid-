package com.hearthstead.settlement.work;

import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkerProvenanceSavedDataTest {
    private static WorkZone zone(WorkZone.Type type) {
        return new WorkZone(UUID.randomUUID(), UUID.randomUUID(), type,
            ResourceLocation.withDefaultNamespace("overworld"),
            new BlockPos(0, 0, 0), new BlockPos(8, 12, 8), 3);
    }

    @Test
    void terminalLumberActionAndDepositReceiptRoundTripExactly() {
        WorkerProvenanceSavedData data = new WorkerProvenanceSavedData();
        WorkZone zone = zone(WorkZone.Type.LUMBER);
        UUID worker = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        BlockPos top = new BlockPos(4, 5, 4);
        BlockPos root = new BlockPos(4, 4, 4);
        var action = new WorkerProvenanceSavedData.Action(actionId,
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.WORK_COMMITTED, worker, zone,
            root, List.of(top, root), 100L);
        action.resolved.add(top);
        action.resolved.add(root);
        action.appliedToolDamage = 2;
        ResourceLocation logs = ResourceLocation.withDefaultNamespace("oak_log");
        action.produced.put(logs, 2);
        action.deposited.put(logs, 1);
        assertTrue(data.add(action));
        UUID receiptId = WorkerProvenanceSavedData.receiptId(actionId, logs, 0);
        assertTrue(data.addReceipt(new WorkerProvenanceSavedData.DepositReceipt(
            receiptId, actionId, zone.settlementId(), zone.buildingId(), worker,
            zone.dimension(), new BlockPos(1, 1, 1), logs, 1, 9, 10, 120L)));

        CompoundTag saved = data.save(new CompoundTag(), null);
        WorkerProvenanceSavedData loaded = WorkerProvenanceSavedData.load(saved,
            null);
        assertFalse(loaded.quarantined());
        var decoded = loaded.action(actionId);
        assertNotNull(decoded);
        assertEquals(2, decoded.resolved().size());
        assertEquals(2, decoded.appliedToolDamage());
        assertEquals(1, decoded.remaining(logs));
        assertTrue(WorkerProvenanceService.hasPersistedRemainder(decoded),
            "restart must retain an unresolved physical-output remainder");
        assertEquals(1, loaded.receiptsFor(actionId).size());
        assertEquals(10, loaded.receipt(receiptId).destinationAfter());
    }

    @Test
    void duplicateActiveWorkerActionIsRejected() {
        WorkerProvenanceSavedData data = new WorkerProvenanceSavedData();
        WorkZone zone = zone(WorkZone.Type.LUMBER);
        UUID worker = UUID.randomUUID();
        BlockPos root = new BlockPos(1, 1, 1);
        var first = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, zone, root,
            List.of(root), 1L);
        var replay = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, zone, root,
            List.of(root), 2L);
        assertTrue(data.add(first));
        assertFalse(data.add(replay));
        assertEquals(first.id, data.activeFor(worker,
            WorkerProvenanceSavedData.Kind.LUMBER_TREE).id());
    }

    @Test
    void depositedBeyondProducedQuarantinesInsteadOfMintingAuthority() {
        WorkerProvenanceSavedData data = new WorkerProvenanceSavedData();
        WorkZone zone = zone(WorkZone.Type.FARM);
        BlockPos crop = new BlockPos(2, 1, 2);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.FARM_HARVEST,
            WorkerProvenanceSavedData.Phase.WORK_COMMITTED, UUID.randomUUID(),
            zone, crop, List.of(crop), 10L);
        action.resolved.add(crop);
        action.appliedToolDamage = 1;
        ResourceLocation wheat = ResourceLocation.withDefaultNamespace("wheat");
        action.produced.put(wheat, 1);
        assertTrue(data.add(action));
        action.deposited.put(wheat, 2);
        data.changed(action, 11L);
        assertTrue(data.quarantined());
        assertEquals("runtime_action_invariant", data.quarantineReason());
    }

    @Test
    void futureVersionAndOverCapPayloadQuarantineFailClosed() {
        CompoundTag future = new CompoundTag();
        future.putInt("DataVersion", WorkerProvenanceSavedData.DATA_VERSION + 1);
        assertTrue(WorkerProvenanceSavedData.load(future, null).quarantined());

        CompoundTag oversized = new CompoundTag();
        oversized.putInt("DataVersion", WorkerProvenanceSavedData.DATA_VERSION);
        ListTag actions = new ListTag();
        for (int i = 0; i <= WorkerProvenanceSavedData.MAX_ACTIONS; i++) {
            actions.add(new CompoundTag());
        }
        oversized.put("Actions", actions);
        oversized.put("Receipts", new ListTag());
        assertTrue(WorkerProvenanceSavedData.load(oversized, null).quarantined());
        assertEquals("row_cap_exceeded",
            WorkerProvenanceSavedData.load(oversized, null).quarantineReason());
    }

    @Test
    void receiptIdIsReplayStableButSequenceSpecific() {
        UUID action = UUID.randomUUID();
        ResourceLocation wheat = ResourceLocation.withDefaultNamespace("wheat");
        assertEquals(WorkerProvenanceSavedData.receiptId(action, wheat, 0),
            WorkerProvenanceSavedData.receiptId(action, wheat, 0));
        assertNotEquals(WorkerProvenanceSavedData.receiptId(action, wheat, 0),
            WorkerProvenanceSavedData.receiptId(action, wheat, 1));
    }

    @Test
    void pendingOnePointToolUseSurvivesRestartWithoutReplayDamage() {
        WorkerProvenanceSavedData data = new WorkerProvenanceSavedData();
        WorkZone zone = zone(WorkZone.Type.LUMBER);
        BlockPos log = new BlockPos(3, 3, 3);
        UUID actionId = UUID.randomUUID();
        var action = new WorkerProvenanceSavedData.Action(actionId,
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, UUID.randomUUID(), zone,
            log, List.of(log), 20L);
        action.pendingOperation = log;
        action.pendingToolApplied = true;
        action.pendingToolItem = ResourceLocation.withDefaultNamespace(
            "iron_axe");
        action.pendingToolBefore = 4;
        action.pendingToolAfter = 5;
        action.appliedToolDamage = 1;
        assertTrue(data.add(action));

        WorkerProvenanceSavedData loaded = WorkerProvenanceSavedData.load(
            data.save(new CompoundTag(), null), null);
        var resumed = loaded.action(actionId);
        assertNotNull(resumed);
        assertFalse(loaded.quarantined());
        assertEquals(log, resumed.pendingOperation());
        assertTrue(resumed.pendingToolApplied());
        assertEquals(1, resumed.appliedToolDamage(),
            "restart must retain the already-applied point for replay guard");
        assertTrue(resumed.resolved().isEmpty(),
            "prepared is not the same as physically completed work");
    }

    @Test
    void levelBindingQuarantinesCrossDimensionDomainAndActions() {
        WorkerProvenanceSavedData domainMismatch =
            new WorkerProvenanceSavedData();
        domainMismatch.bindDimension(ResourceLocation.withDefaultNamespace(
            "the_nether"));
        CompoundTag saved = domainMismatch.save(new CompoundTag(), null);
        WorkerProvenanceSavedData loaded = WorkerProvenanceSavedData.load(saved,
            null);
        loaded.bindDimension(ResourceLocation.withDefaultNamespace("overworld"));
        assertTrue(loaded.quarantined());
        assertEquals("saveddata_dimension_mismatch",
            loaded.quarantineReason());

        WorkerProvenanceSavedData actionMismatch =
            new WorkerProvenanceSavedData();
        WorkZone nether = new WorkZone(UUID.randomUUID(), UUID.randomUUID(),
            WorkZone.Type.LUMBER,
            ResourceLocation.withDefaultNamespace("the_nether"),
            new BlockPos(0, 0, 0), new BlockPos(4, 6, 4), 1);
        BlockPos log = new BlockPos(2, 2, 2);
        assertTrue(actionMismatch.add(new WorkerProvenanceSavedData.Action(
            UUID.randomUUID(), WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, UUID.randomUUID(), nether,
            log, List.of(log), 1L)));
        actionMismatch.bindDimension(ResourceLocation.withDefaultNamespace(
            "overworld"));
        assertTrue(actionMismatch.quarantined());
        assertEquals("action_dimension_mismatch",
            actionMismatch.quarantineReason());
    }

    @Test
    void terminalMutationWithMissingDurabilityQuarantines() {
        WorkerProvenanceSavedData data = new WorkerProvenanceSavedData();
        WorkZone zone = zone(WorkZone.Type.FARM);
        BlockPos crop = new BlockPos(2, 1, 2);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.FARM_HARVEST,
            WorkerProvenanceSavedData.Phase.ACTIVE, UUID.randomUUID(), zone,
            crop, List.of(crop), 10L);
        assertTrue(data.add(action));
        action.resolved.add(crop);
        action.produced.put(ResourceLocation.withDefaultNamespace("wheat"), 1);
        action.phase = WorkerProvenanceSavedData.Phase.WORK_COMMITTED;
        data.changed(action, 11L);
        assertTrue(data.quarantined(),
            "a terminal crop without its one physical hoe point is forged");
        assertEquals("runtime_action_invariant", data.quarantineReason());
    }

    @Test
    void terminalPreflightFailureLeavesActionByteForByteUnchanged() {
        WorkZone zone = zone(WorkZone.Type.FARM);
        BlockPos first = new BlockPos(2, 1, 2);
        BlockPos second = first.east();
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.FARM_PLANT,
            WorkerProvenanceSavedData.Phase.INPUT_HELD, UUID.randomUUID(), zone,
            first, List.of(first), 10L);
        // Forge a two-position plan after construction to mutation-test the
        // terminal preflight. FARM_PLANT would never author this at runtime.
        action.planned.add(second);
        action.inputItem = ResourceLocation.withDefaultNamespace("wheat_seeds");
        action.inputCount = 1;
        action.pendingOperation = first;
        action.pendingToolApplied = true;
        action.pendingToolItem = ResourceLocation.withDefaultNamespace("iron_hoe");
        action.pendingToolBefore = 0;
        action.pendingToolAfter = 1;
        action.appliedToolDamage = 1;
        var before = action.view();

        assertFalse(WorkerProvenanceService.completionCanApply(action, first,
            java.util.Map.of(), true));
        assertEquals(before, action.view(),
            "failed terminal preflight must not mutate any action field");
        assertTrue(action.resolved.isEmpty());
        assertTrue(action.produced.isEmpty());
        assertEquals(first, action.pendingOperation);
        assertTrue(action.pendingToolApplied);
        assertEquals(WorkerProvenanceSavedData.Phase.INPUT_HELD, action.phase);
    }
}
