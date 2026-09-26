package com.hearthstead.settlement.equipment;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestItemFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentRequestTest {

    @Test
    void onlyUnclaimedUntracedNeedsMaySayItemNotYetSourced() {
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.LUMBERER,
            new EquipmentRequirement(Items.IRON_AXE,
                ResourceLocation.withDefaultNamespace("axes"), 8), 1,
            EquipmentRequest.Priority.HIGH, EquipmentRequest.Reason.WORN, 70L);
        assertTrue(request.awaitingSource());
        CompoundTag before = request.writeNbt();
        assertTrue(request.awaitingSource());
        assertEquals(before, request.writeNbt(), "display projection must not mutate authority");
        EquipmentRequest restored = EquipmentRequest.readNbt(before);
        assertNotNull(restored);
        assertTrue(restored.awaitingSource());
        assertEquals(EquipmentRequest.Reason.WORN, restored.reason());
        for (String flag : new String[] {"LegacyClaimLocked", "TraceQuarantined", "CancelPending"}) {
            CompoundTag unsafe = before.copy();
            unsafe.putBoolean(flag, true);
            EquipmentRequest guarded = EquipmentRequest.readNbt(unsafe);
            assertNotNull(guarded);
            assertFalse(guarded.awaitingSource(), flag + " must retain uncertainty");
        }
        UUID courier = UUID.randomUUID();
        assertTrue(request.claim(courier, 100L, 100L));
        assertFalse(request.awaitingSource(), "even an untraced assigned claim is not unsourced proof");
        assertTrue(request.bindRoute(courier, UUID.randomUUID(),
            new BlockPos(1, 64, 1), 0, 1, new BlockPos(8, 64, 8), 0,
            fingerprint(new ItemStack(Items.IRON_AXE))));
        assertFalse(request.awaitingSource());
        assertTrue(request.markPickedUp(courier));
        assertFalse(request.awaitingSource());
        assertTrue(request.markReturned(courier));
        assertEquals(EquipmentRequest.Status.OPEN, request.status());
        assertNull(request.claimedBy());
        assertFalse(request.awaitingSource(), "OPEN returned traces still have physical history");
        CompoundTag partial = request.writeNbt();
        partial.getCompound("Trace").remove("SourceBuilding");
        EquipmentRequest malformed = EquipmentRequest.readNbt(partial);
        assertNotNull(malformed);
        assertFalse(malformed.awaitingSource(), "a malformed partial trace is not an ordinary new need");
    }
    private static RequestItemFingerprint fingerprint(ItemStack stack) {
        return RequestItemFingerprint.capture(RegistryAccess.EMPTY, stack, 1);
    }

    @Test
    void claimDeliveryAndNbtRoundTripRemainIdempotent() {
        UUID worker = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        EquipmentRequest request = new EquipmentRequest(worker, building,
            Profession.LUMBERER,
            new EquipmentRequirement(Items.IRON_AXE,
                ResourceLocation.withDefaultNamespace("axes"), 8),
            1, EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);

        assertTrue(request.claim(courier, 100L, 50L));
        assertTrue(request.claim(courier, 101L, 50L),
            "the same courier may idempotently resume its claim");

        EquipmentRequest restored = EquipmentRequest.readNbt(request.writeNbt());
        assertNotNull(restored);
        assertEquals(request.id(), restored.id());
        assertEquals(EquipmentRequest.Status.CLAIMED, restored.status());
        assertEquals(courier, restored.claimedBy());
        assertFalse(restored.markDeliveredTraced(courier, 120L),
            "an untraced row cannot bypass physical bag/target proof");
        assertFalse(restored.release(courier),
            "a restarted pre-trace claim stays locked instead of guessing where its tool is");
        assertEquals(EquipmentRequest.Status.CLAIMED, restored.status());
    }

    @Test
    void expiredClaimReopensButMalformedRowsFailClosed() {
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.FARMER,
            new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);
        UUID courier = UUID.randomUUID();
        assertTrue(request.claim(courier, 10L, 5L));
        assertFalse(request.reopenExpiredClaim(14L));
        assertTrue(request.reopenExpiredClaim(15L));
        assertEquals(EquipmentRequest.Status.OPEN, request.status());

        CompoundTag malformed = request.writeNbt();
        malformed.putString("Status", "TELEPORTED_FROM_NOWHERE");
        assertNull(EquipmentRequest.readNbt(malformed));
    }

    @Test
    void unchangedNeedDoesNotPretendPersistenceChanged() {
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.FARMER,
            new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);

        assertFalse(request.updateNeed(EquipmentRequest.Priority.URGENT,
                EquipmentRequest.Reason.MISSING),
            "an idempotent AI refresh must not dirty SavedData every tick");
        assertTrue(request.updateNeed(EquipmentRequest.Priority.HIGH,
                EquipmentRequest.Reason.WORN),
            "a real reason/priority transition must still be persisted");
    }

    @Test
    void exactClaimPickupRestartAndDeliveryTraceIsMonotone() {
        UUID worker = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        BlockPos source = new BlockPos(12, 70, -4);
        BlockPos target = new BlockPos(28, 69, 11);
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        axe.setDamageValue(3);
        RequestItemFingerprint exact = fingerprint(axe);
        EquipmentRequest request = new EquipmentRequest(worker, workplace,
            Profession.LUMBERER,
            new EquipmentRequirement(Items.IRON_AXE,
                ResourceLocation.withDefaultNamespace("axes"), 8),
            1, EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING, 40L);

        assertTrue(request.claim(courier, 100L, 50L));
        assertTrue(request.bindRoute(courier, warehouse, source, 4, 1,
            target, 0, exact));
        EquipmentRequest atSource = EquipmentRequest.readNbt(
            request.writeNbt());
        assertNotNull(atSource);
        assertEquals(EquipmentRequest.TraceStage.SOURCE,
            atSource.traceStage());
        assertEquals(source, atSource.sourceContainer());
        assertEquals(target, atSource.targetContainer());
        assertEquals(4, atSource.sourceSlot());
        assertEquals(40L, atSource.createdAtTick());
        assertTrue(atSource.traceFingerprint(RegistryAccess.EMPTY)
            .matches(RegistryAccess.EMPTY, axe));

        assertTrue(atSource.markPickedUp(courier));
        assertFalse(atSource.release(courier),
            "a physical bag load may not reopen for a second Courier");
        assertFalse(atSource.reopenExpiredClaim(10_000L),
            "an expired lease may not duplicate a physical bag load");
        EquipmentRequest inBag = EquipmentRequest.readNbt(atSource.writeNbt());
        assertNotNull(inBag);
        assertEquals(EquipmentRequest.TraceStage.COURIER_BAG,
            inBag.traceStage());
        assertEquals(1, inBag.movedCount());
        assertEquals(0, inBag.deliveredCount());
        assertEquals(courier, inBag.claimedBy());

        assertTrue(inBag.markDeliveredTraced(courier, 140L));
        EquipmentRequest delivered = EquipmentRequest.readNbt(inBag.writeNbt());
        assertNotNull(delivered);
        assertEquals(EquipmentRequest.TraceStage.TARGET,
            delivered.traceStage());
        assertEquals(EquipmentRequest.Status.DELIVERED, delivered.status());
        assertNull(delivered.claimedBy());
        assertEquals(courier, delivered.traceCourierId());
        assertEquals(1, delivered.movedCount());
        assertEquals(1, delivered.deliveredCount());
        assertEquals(0, delivered.returnedCount());
    }

    @Test
    void provedReturnReopensAndNextRouteResetsCounts() {
        UUID worker = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID firstCourier = UUID.randomUUID();
        UUID secondCourier = UUID.randomUUID();
        ItemStack hoe = new ItemStack(Items.IRON_HOE);
        RequestItemFingerprint exact = fingerprint(hoe);
        EquipmentRequest request = new EquipmentRequest(worker, workplace,
            Profession.FARMER,
            new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING, 7L);

        assertTrue(request.claim(firstCourier, 10L, 100L));
        assertTrue(request.bindRoute(firstCourier, warehouse,
            new BlockPos(1, 64, 1), 0, 1, new BlockPos(8, 64, 8), 0,
            exact));
        assertTrue(request.markPickedUp(firstCourier));
        assertTrue(request.markReturned(firstCourier));
        assertEquals(EquipmentRequest.Status.OPEN, request.status());
        assertEquals(EquipmentRequest.TraceStage.SOURCE,
            request.traceStage());
        assertEquals(1, request.movedCount());
        assertEquals(0, request.deliveredCount());
        assertEquals(1, request.returnedCount());
        assertEquals(RequestBlocker.RETURNED_TO_SOURCE,
            request.traceBlocker());

        EquipmentRequest restored = EquipmentRequest.readNbt(request.writeNbt());
        assertNotNull(restored);
        assertEquals(1, restored.returnedCount());
        assertTrue(restored.claim(secondCourier, 200L, 50L));
        assertTrue(restored.bindRoute(secondCourier, warehouse,
            new BlockPos(2, 64, 2), 1, 1, new BlockPos(8, 64, 8), 0,
            exact));
        assertEquals(0, restored.movedCount());
        assertEquals(0, restored.returnedCount());
        assertEquals(RequestBlocker.NONE, restored.traceBlocker());
        assertEquals(secondCourier, restored.traceCourierId());
    }

    @Test
    void malformedTraceQuarantinesWhileOldRowsRemainLegacyLimited() {
        UUID courier = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.FARMER,
            new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING, 9L);
        assertTrue(request.claim(courier, 10L, 100L));
        assertTrue(request.bindRoute(courier, warehouse,
            new BlockPos(1, 64, 1), 0, 1, new BlockPos(2, 64, 2), 0,
            fingerprint(new ItemStack(Items.IRON_HOE))));

        CompoundTag damaged = request.writeNbt();
        damaged.getCompound("Trace").putInt("Moved", 2);
        EquipmentRequest quarantined = EquipmentRequest.readNbt(damaged);
        assertNotNull(quarantined);
        assertTrue(quarantined.traceQuarantined());
        assertEquals(EquipmentRequest.TraceStage.NONE,
            quarantined.traceStage());
        assertFalse(quarantined.release(courier));
        assertFalse(quarantined.reopenExpiredClaim(1_000L));
        EquipmentRequest quarantinedAgain = EquipmentRequest.readNbt(
            quarantined.writeNbt());
        assertNotNull(quarantinedAgain);
        assertTrue(quarantinedAgain.traceQuarantined());

        CompoundTag oldSave = request.writeNbt();
        oldSave.remove("Trace");
        oldSave.remove("TraceQuarantined");
        EquipmentRequest legacy = EquipmentRequest.readNbt(oldSave);
        assertNotNull(legacy);
        assertFalse(legacy.traceQuarantined());
        assertEquals(EquipmentRequest.TraceStage.NONE, legacy.traceStage());
        assertEquals(EquipmentRequest.Status.CLAIMED, legacy.status());
        assertFalse(legacy.release(courier),
            "a pre-trace loaded claim must stay locked rather than duplicate");
        assertFalse(legacy.reopenExpiredClaim(1_000L));
    }

    @Test
    void persistedBagRouteClassifiesUnavailableTargetsForSafeReturn() {
        assertEquals(RequestBlocker.TARGET_UNLOADED,
            EquipmentRequests.targetResumeBlocker(false, false, false));
        assertEquals(RequestBlocker.TARGET_INVALID,
            EquipmentRequests.targetResumeBlocker(true, false, false));
        assertEquals(RequestBlocker.TARGET_FULL,
            EquipmentRequests.targetResumeBlocker(true, true, false));
        assertEquals(RequestBlocker.NONE,
            EquipmentRequests.targetResumeBlocker(true, true, true));
    }

    @Test
    void corruptIdentityUuidsAreRejectedBeforeSnapshotConstruction() {
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.FARMER,
            new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);
        UUID zero = new UUID(0L, 0L);
        for (String key : new String[] {"Id", "Requester", "Destination"}) {
            CompoundTag corrupt = request.writeNbt();
            corrupt.putUUID(key, zero);
            assertNull(EquipmentRequest.readNbt(corrupt),
                key + " must never enter the persistent queue as nil");
        }

        UUID courier = UUID.randomUUID();
        assertTrue(request.claim(courier, 10L, 100L));
        CompoundTag corruptClaim = request.writeNbt();
        corruptClaim.putUUID("ClaimedBy", zero);
        assertNull(EquipmentRequest.readNbt(corruptClaim),
            "a nil claim owner cannot become an in-flight authority");
    }

    @Test
    void cancelPendingSurvivesRestartUntilOnePhysicalReturnCommits() {
        UUID courier = UUID.randomUUID();
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.LUMBERER,
            new EquipmentRequirement(Items.IRON_AXE,
                ResourceLocation.withDefaultNamespace("axes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING, 30L);
        assertTrue(request.claim(courier, 40L, 100L));
        assertTrue(request.bindRoute(courier, UUID.randomUUID(),
            new BlockPos(1, 64, 1), 2, 1,
            new BlockPos(8, 64, 8), 0,
            fingerprint(new ItemStack(Items.IRON_AXE))));
        assertTrue(request.markPickedUp(courier));
        assertTrue(request.requestCancellation());
        assertTrue(request.cancelPending());
        assertEquals(RequestBlocker.RETURNED_TO_SOURCE,
            request.traceBlocker());
        assertFalse(request.release(courier));
        assertFalse(request.claim(courier, 50L, 100L));
        assertFalse(request.claim(UUID.randomUUID(), 50L, 100L));
        assertFalse(request.markDeliveredTraced(courier, 50L));
        assertFalse(request.reopenExpiredClaim(10_000L));

        EquipmentRequest restarted = EquipmentRequest.readNbt(
            request.writeNbt());
        assertNotNull(restarted);
        assertTrue(restarted.cancelPending());
        assertEquals(EquipmentRequest.TraceStage.COURIER_BAG,
            restarted.traceStage());
        assertEquals(1, restarted.movedCount());
        assertEquals(0, restarted.deliveredCount());
        assertEquals(0, restarted.returnedCount());
        assertFalse(restarted.cancellationReadyForRemoval());

        assertTrue(restarted.markReturned(courier));
        assertTrue(restarted.cancellationReadyForRemoval());
        assertEquals(1, restarted.movedCount());
        assertEquals(0, restarted.deliveredCount());
        assertEquals(1, restarted.returnedCount());
        assertEquals(restarted.movedCount(), restarted.deliveredCount()
            + restarted.returnedCount(),
            "the single physical tool is delivered or returned exactly once");

        EquipmentRequest returnedAfterRestart = EquipmentRequest.readNbt(
            restarted.writeNbt());
        assertNotNull(returnedAfterRestart);
        assertTrue(returnedAfterRestart.cancellationReadyForRemoval());
        assertFalse(returnedAfterRestart.claim(UUID.randomUUID(), 20_000L,
            100L), "a returned cancellation row is removed, never reclaimed");
    }

    @Test
    void cancelPendingIsHiddenFromDeliveryButRetainedForRecoveryLookup() {
        UUID courier = UUID.randomUUID();
        UUID workplaceId = UUID.randomUUID();
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            workplaceId, Profession.LUMBERER,
            new EquipmentRequirement(Items.IRON_AXE,
                ResourceLocation.withDefaultNamespace("axes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);
        assertTrue(request.claim(courier, 1L, 100L));
        assertTrue(request.bindRoute(courier, UUID.randomUUID(),
            new BlockPos(1, 64, 1), 0, 1,
            new BlockPos(2, 64, 2), 0,
            fingerprint(new ItemStack(Items.IRON_AXE))));
        assertTrue(request.markPickedUp(courier));

        Settlement settlement = new Settlement(UUID.randomUUID(), "Test",
            BlockPos.ZERO);
        Building workplace = new Building(workplaceId,
            BuildingType.LUMBER_CAMP, BlockPos.ZERO, BlockPos.ZERO,
            new BoundingBox(BlockPos.ZERO));
        workplace.equipmentRequests.add(request);
        settlement.buildings.add(workplace);
        assertSame(request, EquipmentRequests.byId(settlement, request.id()));

        assertTrue(request.requestCancellation());
        assertNull(EquipmentRequests.byId(settlement, request.id()),
            "live delivery checks must turn the current Courier back before insertion");
        assertSame(request, workplace.equipmentRequests.getFirst(),
            "the persisted row remains until physical return commits");
    }

    @Test
    void onlyFullyRevalidatedTargetBlockersMayClear() {
        UUID courier = UUID.randomUUID();
        EquipmentRequest request = new EquipmentRequest(UUID.randomUUID(),
            UUID.randomUUID(), Profession.FARMER,
            new EquipmentRequirement(Items.IRON_HOE,
                ResourceLocation.withDefaultNamespace("hoes"), 8), 1,
            EquipmentRequest.Priority.URGENT,
            EquipmentRequest.Reason.MISSING);
        assertTrue(request.claim(courier, 1L, 100L));
        assertTrue(request.bindRoute(courier, UUID.randomUUID(),
            new BlockPos(1, 64, 1), 0, 1,
            new BlockPos(2, 64, 2), 0,
            fingerprint(new ItemStack(Items.IRON_HOE))));
        assertTrue(request.block(courier, RequestBlocker.TARGET_FULL));
        assertTrue(request.clearRevalidatedTargetBlocker(courier));
        assertEquals(RequestBlocker.NONE, request.traceBlocker());
        assertFalse(request.clearRevalidatedTargetBlocker(courier));

        assertTrue(request.block(courier, RequestBlocker.NO_PATH));
        assertFalse(request.clearRevalidatedTargetBlocker(courier),
            "route validation cannot erase a distinct path failure");
        assertEquals(RequestBlocker.NO_PATH, request.traceBlocker());
    }

    @Test
    void legacyStateOnlyDeliveryMethodIsNotPublicApi() throws Exception {
        assertThrows(NoSuchMethodException.class, () ->
            EquipmentRequest.class.getDeclaredMethod("markDelivered",
                UUID.class, long.class));
        assertFalse(Modifier.isPublic(EquipmentRequest.class
            .getDeclaredMethod("markDeliveredTraced", UUID.class, long.class)
            .getModifiers()));
    }
}
