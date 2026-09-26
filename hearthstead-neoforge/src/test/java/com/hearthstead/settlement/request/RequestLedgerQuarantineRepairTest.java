package com.hearthstead.settlement.request;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the Elmfield save: an older build opened an OUTPUT_PICKUP
 * whose source chest was also the Warehouse destination (a producer building
 * overlapping the Warehouse). Delivering it back into the same chest could not
 * raise the target count, so {@code deliver_satisfaction_proof_failed}
 * quarantined the whole ledger and the Courier never worked again, even after
 * reloading. The saved rows are consistent; the ledger must repair itself on
 * load without moving any items.
 */
class RequestLedgerQuarantineRepairTest {
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation WHEAT =
        ResourceLocation.withDefaultNamespace("wheat");
    private static final String PROOF_FAILED = "deliver_satisfaction_proof_failed";

    @Test
    void runtimeQuarantineFromSelfLoopDeliveryRepairsWithoutTouchingCargo() {
        UUID settlement = UUID.randomUUID();
        RequestLedger ledger = new RequestLedger(settlement);
        BlockPos sharedChest = new BlockPos(121, 76, -111);

        RequestRecord selfLoop = open(ledger, settlement, sharedChest, sharedChest, 3, 11, 100L);
        UUID wilmot = UUID.randomUUID();
        assertTrue(selfLoop.reserve(wilmot, 101L, 1200L));
        assertTrue(selfLoop.markPickup(wilmot, 102L));
        assertTrue(selfLoop.markInTransit(wilmot, 3, 103L));
        assertTrue(selfLoop.noteDelivered(wilmot, 3, 104L));
        assertTrue(ledger.mutationCommitted(selfLoop));
        assertEquals(RequestState.DELIVERED, selfLoop.state());

        // A second, healthy row with cargo still in another Courier's bag.
        RequestRecord inFlight = open(ledger, settlement, new BlockPos(1, 64, 1),
            new BlockPos(9, 64, 9), 4, 0, 100L);
        UUID other = UUID.randomUUID();
        assertTrue(inFlight.reserve(other, 101L, 1200L));
        assertTrue(inFlight.markPickup(other, 102L));
        assertTrue(inFlight.markInTransit(other, 4, 103L));
        assertTrue(ledger.mutationCommitted(inFlight));

        ledger.quarantine(PROOF_FAILED);
        assertTrue(ledger.quarantined());
        assertTrue(ledger.writeNbt().getBoolean("Quarantined"),
            "the owner's save persisted this flag");

        assertTrue(ledger.repairAfterRuntimeQuarantine(PROOF_FAILED));
        assertFalse(ledger.quarantined());
        assertEquals("none", ledger.quarantineReason());
        assertTrue(ledger.repairedFromQuarantine());
        assertEquals(PROOF_FAILED, ledger.repairedReason());
        assertFalse(ledger.writeNbt().getBoolean("Quarantined"));

        assertNull(ledger.active(selfLoop.id()), "fully deposited row closes");
        assertEquals(RequestState.SATISFIED, ledger.any(selfLoop.id()).state());
        assertEquals(3, ledger.any(selfLoop.id()).deliveredCount());

        RequestRecord kept = ledger.active(inFlight.id());
        assertNotNull(kept, "bag cargo keeps its owning row");
        assertEquals(RequestState.IN_TRANSIT, kept.state());
        assertEquals(4, kept.movedCount());
        assertEquals(0, kept.deliveredCount());
        assertEquals(other, kept.courierId());
    }

    @Test
    void cargoFreeSelfLoopRowExpiresAndHealthyOpenRowStays() {
        UUID settlement = UUID.randomUUID();
        RequestLedger ledger = new RequestLedger(settlement);
        BlockPos shared = new BlockPos(5, 70, 5);
        RequestRecord selfLoop = open(ledger, settlement, shared, shared, 2, 0, 50L);
        RequestRecord healthy = open(ledger, settlement, new BlockPos(0, 70, 0),
            new BlockPos(3, 70, 0), 2, 0, 50L);
        ledger.quarantine("pickup_bag_commit_failed");

        assertTrue(ledger.repairAfterRuntimeQuarantine("pickup_bag_commit_failed"));
        assertFalse(ledger.quarantined());
        assertEquals(RequestState.EXPIRED, ledger.any(selfLoop.id()).state());
        assertSame(healthy, ledger.active(healthy.id()));
        assertEquals(RequestState.OPEN, healthy.state());
    }

    @Test
    void loadTimeAndStructuralQuarantinesStayFailClosed() {
        for (String reason : new String[] {"malformed_active_row",
                "malformed_terminal_row", "malformed_ledger_header",
                "ledger_bounds", "ledger_cardinality", "revision_bound",
                "settlement_bound", "unknown", "none", ""}) {
            assertFalse(RequestLedger.repairableSavedQuarantine(reason), reason);
            RequestLedger ledger = new RequestLedger(UUID.randomUUID());
            ledger.quarantine("x");
            assertFalse(ledger.repairAfterRuntimeQuarantine(reason), reason);
            assertTrue(ledger.quarantined(), reason);
        }
        assertTrue(RequestLedger.repairableSavedQuarantine(PROOF_FAILED));
    }

    private static RequestRecord open(RequestLedger ledger, UUID settlement,
                                      BlockPos source, BlockPos target,
                                      int count, int targetBefore, long tick) {
        RequestRecord row = RequestRecord.openOutput(UUID.randomUUID(), settlement,
            RequestPriority.NORMAL, OVERWORLD, UUID.randomUUID(), source, 0,
            UUID.randomUUID(), target,
            RequestItemFingerprint.synthetic(WHEAT, "plain-" + source.asLong(), count),
            Math.max(count, 16), targetBefore, tick);
        RequestLedger.OpenDecision decision = ledger.open(row);
        assertEquals(RequestLedger.OpenResult.CREATED, decision.result());
        return row;
    }
}
