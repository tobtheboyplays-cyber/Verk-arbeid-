package com.hearthstead.settlement.request;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stout Straps batching rules (CourierBatching): the per-Courier ownership
 * limit N (1, or 2 with the switch on and the node learned), the hard
 * recovery ceiling that ignores the switch, and the conservation arithmetic
 * of the stow. The world half (stow, pickup, delivery, reload, death) is
 * CourierBatchingGameTests.
 */
class CourierBatchingTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");

    @Test
    void newReservationsAllowTwoOnlyWithTheSwitchAndStoutStraps() {
        assertEquals(1, CourierBatching.maxNewOwned(false, false));
        assertEquals(1, CourierBatching.maxNewOwned(true, false), "switch on, node not learned");
        assertEquals(1, CourierBatching.maxNewOwned(false, true), "node learned, switch off");
        assertEquals(2, CourierBatching.maxNewOwned(true, true));
        assertEquals(2, CourierBatching.MAX_OWNED, "never more than two per Courier");
    }

    @Test
    void recoveryCeilingIgnoresTheSwitchSoATripInFlightFinishes() {
        assertTrue(CourierBatching.ownershipAllowed(0));
        assertTrue(CourierBatching.ownershipAllowed(1));
        assertTrue(CourierBatching.ownershipAllowed(2), "a batch formed before the switch went off");
        assertFalse(CourierBatching.ownershipAllowed(3), "three owned is corruption: quarantine");
        assertFalse(CourierBatching.ownershipAllowed(-1));
    }

    @Test
    void stowHoldsExactlyOneRequestsRemainingCargoAndNothingElse() {
        assertTrue(CourierBatching.stowHolds(4, 4, 4));
        assertFalse(CourierBatching.stowHolds(3, 4, 4), "a foreign item in the stow");
        assertFalse(CourierBatching.stowHolds(4, 4, 3), "more than the request owns");
        assertFalse(CourierBatching.stowHolds(2, 2, 3), "less than the request owns");
        assertFalse(CourierBatching.stowHolds(0, 0, 0), "an empty stow owns nothing");
    }

    @Test
    void bothLoadsMustFitOneTripAndThePartnerMustBeNear() {
        assertTrue(CourierBatching.fits(8, 8, 16));
        assertFalse(CourierBatching.fits(8, 9, 16));
        assertFalse(CourierBatching.fits(0, 4, 16), "no first load, no batch");
        assertTrue(CourierBatching.near(16.0D * 16.0D));
        assertFalse(CourierBatching.near(16.0D * 16.0D + 1.0D));
        assertTrue(CourierBatching.batchable(RequestType.OUTPUT_PICKUP));
        assertTrue(CourierBatching.batchable(RequestType.FOOD));
        assertTrue(CourierBatching.batchable(RequestType.MATERIAL_INPUT));
        assertTrue(CourierBatching.batchable(RequestType.AMMUNITION));
        assertFalse(CourierBatching.batchable(RequestType.EQUIPMENT), "tools keep their own spine");
        assertFalse(CourierBatching.batchable(RequestType.CRAFT_ORDER));
    }

    @Test
    void aBatchedPairKeepsExactPerRequestTracesUnderOneCourier() {
        UUID settlement = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        RequestLedger ledger = new RequestLedger(settlement);
        RequestRecord first = row(settlement, Items.OAK_LOG, 2, new BlockPos(1, 1, 1), 10L);
        RequestRecord second = row(settlement, Items.BIRCH_LOG, 3, new BlockPos(3, 1, 1), 11L);
        assertEquals(RequestLedger.OpenResult.CREATED, ledger.open(first).result());
        assertEquals(RequestLedger.OpenResult.CREATED, ledger.open(second).result());
        // A: reserved, lifted, in transit.
        assertTrue(first.reserve(courier, 12L, 400L));
        assertTrue(first.markPickup(courier, 13L));
        assertTrue(first.markInTransit(courier, 2, 14L));
        // B: the batched second reservation of the same Courier.
        assertTrue(second.reserve(courier, 15L, 400L));
        assertEquals(2, ledger.activeForCourier(courier).size());
        assertTrue(CourierBatching.ownershipAllowed(ledger.activeForCourier(courier).size()));
        assertTrue(second.markPickup(courier, 16L));
        assertTrue(second.markInTransit(courier, 3, 17L));
        // Conservation: every carried unit belongs to exactly one trace.
        int carried = first.remainingCount() + second.remainingCount();
        assertEquals(5, carried);
        assertTrue(second.noteDelivered(courier, 3, 18L));
        assertTrue(second.markSatisfied(courier, 19L));
        assertTrue(first.noteDelivered(courier, 2, 20L));
        assertTrue(first.markSatisfied(courier, 21L));
        assertTrue(first.hasFullTransportTrace() && second.hasFullTransportTrace());
        assertEquals(first.movedCount(), first.deliveredCount());
        assertEquals(second.movedCount(), second.deliveredCount());
    }

    private static RequestRecord row(UUID settlement, net.minecraft.world.item.Item item, int count,
                                     BlockPos source, long tick) {
        return RequestRecord.openOutput(UUID.randomUUID(), settlement,
            RequestPriority.NORMAL, OVERWORLD, UUID.randomUUID(), source, 0,
            UUID.randomUUID(), source.offset(10, 0, 0),
            RequestItemFingerprint.synthetic(BuiltInRegistries.ITEM.getKey(item), "plain", count),
            Math.max(count, 16), 0, tick);
    }
}
