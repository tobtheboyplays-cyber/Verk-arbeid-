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
    @Test
    void unavailableFarmHarvestSurvivesReloadWithoutCreditingDelivery() {
        var data = new WorkerProvenanceSavedData();
        var farm = zone(WorkZone.Type.FARM);
        UUID worker = UUID.randomUUID();
        BlockPos crop = new BlockPos(4, 4, 4);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.FARM_HARVEST,
            WorkerProvenanceSavedData.Phase.WORK_COMMITTED, worker, farm, crop,
            List.of(crop), 100);
        action.resolved.add(crop);
        action.appliedToolDamage = 1;
        var wheat = ResourceLocation.withDefaultNamespace("wheat");
        action.produced.put(wheat, 1);
        assertTrue(data.add(action));
        assertFalse(data.suspendUnavailableFarmOutput(action.id, worker, 699));
        assertFalse(data.suspendUnavailableFarmOutput(action.id, UUID.randomUUID(), 700));
        assertFalse(data.suspendUnavailableLumberOutput(action.id, worker, 700));
        assertTrue(data.suspendUnavailableFarmOutput(action.id, worker, 700));
        assertFalse(data.suspendUnavailableFarmOutput(action.id, worker, 1400));
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), null), null);
        assertFalse(loaded.quarantined());
        var retained = loaded.action(action.id);
        assertEquals(WorkerProvenanceSavedData.Phase.OUTPUT_UNAVAILABLE, retained.phase());
        assertEquals(1, retained.remaining(wheat));
        assertTrue(retained.deposited().isEmpty());
        assertTrue(loaded.receiptsFor(action.id).isEmpty());
        assertTrue(retained.workTerminal());
    }

    private static WorkZone zone(WorkZone.Type type) {
        return new WorkZone(UUID.randomUUID(), UUID.randomUUID(), type,
            ResourceLocation.withDefaultNamespace("overworld"),
            new BlockPos(0, 0, 0), new BlockPos(8, 12, 8), 3);
    }

    @Test
    void unavailableLumberOutputRetainsEvidenceAcrossReloadWithoutInventingDeposit() {
        var data = new WorkerProvenanceSavedData();
        WorkZone zone = zone(WorkZone.Type.LUMBER);
        UUID worker = UUID.randomUUID();
        BlockPos root = new BlockPos(4, 4, 4);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.WORK_COMMITTED, worker, zone, root,
            List.of(root), 100);
        action.resolved.add(root);
        action.appliedToolDamage = 1;
        var oak = ResourceLocation.withDefaultNamespace("oak_log");
        action.produced.put(oak, 1);
        assertTrue(data.add(action));
        assertFalse(data.suspendUnavailableLumberOutput(action.id, worker, 699));
        assertFalse(data.suspendUnavailableLumberOutput(action.id, UUID.randomUUID(), 700));
        assertTrue(data.suspendUnavailableLumberOutput(action.id, worker, 700));
        assertFalse(data.suspendUnavailableLumberOutput(action.id, worker, 1400));
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), null), null);
        assertFalse(loaded.quarantined());
        var retained = loaded.action(action.id);
        assertEquals(WorkerProvenanceSavedData.Phase.OUTPUT_UNAVAILABLE, retained.phase());
        assertEquals(1, retained.remaining(oak));
        assertEquals(1, retained.produced().get(oak));
        assertTrue(retained.deposited().isEmpty());
        assertTrue(WorkerProvenanceService.hasPersistedRemainder(retained),
            "unavailable goods remain accounted for rather than falsely deposited");
        assertTrue(loaded.receiptsFor(action.id).isEmpty());
        assertTrue(retained.workTerminal(), "real returned cargo retains terminal deposit authority");
        assertTrue(loaded.add(new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, zone, root,
            List.of(root), 701)));
    }

    @Test
    void zoneReplacementRetiresUntouchedWorkAndAllowsFreshActionAfterReload() {
        var data = new WorkerProvenanceSavedData();
        WorkZone old = zone(WorkZone.Type.LUMBER);
        WorkZone replacement = new WorkZone(old.settlementId(), old.buildingId(),
            old.type(), old.dimension(), old.min(), old.max(), old.revision() + 1);
        UUID worker = UUID.randomUUID();
        BlockPos root = new BlockPos(4, 4, 4);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, old, root,
            List.of(root), 10);
        assertTrue(data.add(action));
        assertFalse(data.retireLumber(worker, old, 11));
        assertTrue(data.retireLumber(worker, replacement, 12));
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), null), null);
        assertFalse(loaded.quarantined());
        assertEquals(WorkerProvenanceSavedData.Phase.RETIRED, loaded.action(action.id).phase());
        assertFalse(loaded.action(action.id).workTerminal());
        assertTrue(loaded.action(action.id).produced().isEmpty());
        assertTrue(loaded.add(new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, replacement, root,
            List.of(root), 13)));
    }

    @Test
    void lostEmploymentRetainsPartialOutputAndDoesNotInventWholeTreeCompletion() {
        var data = new WorkerProvenanceSavedData();
        WorkZone old = zone(WorkZone.Type.LUMBER);
        UUID worker = UUID.randomUUID();
        BlockPos root = new BlockPos(4, 4, 4);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, old, root,
            List.of(root.above(), root), 10);
        action.resolved.add(root.above());
        action.appliedToolDamage = 1;
        var oak = ResourceLocation.withDefaultNamespace("oak_log");
        action.produced.put(oak, 1);
        assertTrue(data.add(action));
        assertTrue(data.retireLumber(worker, null, 12));
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), null), null);
        var retained = loaded.action(action.id);
        assertFalse(loaded.quarantined());
        assertEquals(old, retained.zone());
        assertEquals(List.of(root.above(), root), retained.planned());
        assertEquals(java.util.Set.of(root.above()), retained.resolved());
        assertEquals(1, retained.remaining(oak));
        assertEquals(1, retained.appliedToolDamage());
        assertFalse(retained.workTerminal());
        assertNull(loaded.activeFor(worker, WorkerProvenanceSavedData.Kind.LUMBER_TREE));
    }

    @Test
    void obsoletePendingToolReceiptCannotBeDiscardedByRetirement() {
        var data = new WorkerProvenanceSavedData();
        WorkZone old = zone(WorkZone.Type.LUMBER);
        UUID worker = UUID.randomUUID();
        BlockPos root = new BlockPos(4, 4, 4);
        var action = new WorkerProvenanceSavedData.Action(UUID.randomUUID(),
            WorkerProvenanceSavedData.Kind.LUMBER_TREE,
            WorkerProvenanceSavedData.Phase.ACTIVE, worker, old, root,
            List.of(root), 10);
        action.pendingOperation = root;
        action.pendingToolItem = ResourceLocation.withDefaultNamespace("iron_axe");
        action.pendingToolBefore = 4;
        action.pendingToolAfter = 5;
        action.pendingToolApplied = true;
        action.appliedToolDamage = 1;
        assertTrue(data.add(action));
        assertFalse(data.retireLumber(worker, null, 12));
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), null), null);
        assertFalse(loaded.quarantined());
        assertEquals(root, loaded.activeFor(worker,
            WorkerProvenanceSavedData.Kind.LUMBER_TREE).pendingOperation());
        assertEquals(1, loaded.action(action.id).appliedToolDamage());
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
