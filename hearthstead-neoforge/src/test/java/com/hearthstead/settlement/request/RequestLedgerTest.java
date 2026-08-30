package com.hearthstead.settlement.request;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestLedgerTest {
    private static final ResourceLocation OVERWORLD =
        ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation OAK_LOG =
        ResourceLocation.withDefaultNamespace("oak_log");

    @Test
    void stableWireIdsAreExplicitUniqueAndAppendOnly() {
        assertWireIds(RequestType.values(), 0, 1, 2, 3, 4, 5);
        assertWireIds(RequestState.values(), 0, 1, 2, 3, 4, 5, 6, 7, 8);
        assertWireIds(RequestPriority.values(), 0, 1, 2);
        assertWireIds(RequestBlocker.values(), 0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, 14, 15, 16);
        for (RequestType value : RequestType.values()) {
            assertSame(value, RequestType.fromWireId(value.wireId()).orElseThrow());
        }
        for (RequestState value : RequestState.values()) {
            assertSame(value, RequestState.fromWireId(value.wireId()).orElseThrow());
        }
    }

    @Test
    void rejectedEdgesAreAtomicAndCannotChangeOwnerOrTrace() {
        RequestRecord request = row(4, 0, 10L, new BlockPos(1, 2, 3));
        UUID winner = UUID.randomUUID();
        UUID loser = UUID.randomUUID();
        assertTrue(request.reserve(winner, 11L, 100L));
        String before = request.writeNbt().toString();

        assertFalse(request.releaseToOpen(loser, 12L));
        assertFalse(request.markPickup(loser, 12L));
        assertFalse(request.markInTransit(winner, 3, 12L));
        assertFalse(request.block(RequestBlocker.NONE, 12L));

        assertEquals(before, request.writeNbt().toString(),
            "a refused edge must leave the complete persisted image unchanged");
        assertEquals(winner, request.courierId());
        assertEquals(RequestState.RESERVED, request.state());
    }

    @Test
    void completeTraceHasExactMonotoneCountsAndOneCourier() {
        RequestRecord request = row(4, 7, 20L, new BlockPos(2, 2, 2));
        UUID courier = UUID.randomUUID();
        assertTrue(request.reserve(courier, 21L, 100L));
        assertTrue(request.markPickup(courier, 22L));
        assertTrue(request.markInTransit(courier, 4, 23L));
        assertTrue(request.noteDelivered(courier, 1, 24L));
        assertEquals(RequestState.IN_TRANSIT, request.state());
        assertEquals(1, request.deliveredCount());
        assertTrue(request.noteDelivered(courier, 3, 25L));
        assertEquals(RequestState.DELIVERED, request.state());
        assertTrue(request.markSatisfied(courier, 26L));

        assertTrue(request.hasFullTransportTrace());
        assertEquals(4, request.movedCount());
        assertEquals(4, request.deliveredCount());
        int moved = 0;
        int delivered = 0;
        for (RequestTransition edge : request.transitions()) {
            assertEquals(courier, edge.courierId());
            assertTrue(edge.movedCount() >= moved);
            assertTrue(edge.deliveredCount() >= delivered);
            assertTrue(edge.deliveredCount() <= edge.movedCount());
            moved = edge.movedCount();
            delivered = edge.deliveredCount();
        }
        assertThrows(UnsupportedOperationException.class,
            () -> request.transitions().clear());
    }

    @Test
    void sourceSlotHasExactlyOneActiveIntentAndOneReservationWinner() {
        UUID settlement = UUID.randomUUID();
        RequestLedger ledger = new RequestLedger(settlement);
        BlockPos slot = new BlockPos(4, 2, 4);
        RequestRecord first = row(settlement, UUID.randomUUID(), 2, 0, 1L, slot);
        RequestRecord replay = row(settlement, UUID.randomUUID(), 2, 0, 2L, slot);
        assertEquals(RequestLedger.OpenResult.CREATED,
            ledger.open(first).result());
        RequestLedger.OpenDecision duplicate = ledger.open(replay);
        assertEquals(RequestLedger.OpenResult.DUPLICATE, duplicate.result());
        assertSame(first, duplicate.record());

        UUID winner = UUID.randomUUID();
        assertTrue(first.reserve(winner, 3L, 100L));
        assertFalse(first.reserve(UUID.randomUUID(), 3L, 100L));
        assertEquals(winner, first.courierId());
    }

    @Test
    void terminalHistoryIsBoundedAndEvictsOnlyTheOldestProof() {
        UUID settlement = UUID.randomUUID();
        RequestLedger ledger = new RequestLedger(settlement);
        UUID firstId = null;
        for (int i = 0; i <= RequestLedger.MAX_TERMINAL; i++) {
            UUID id = UUID.randomUUID();
            if (i == 0) {
                firstId = id;
            }
            RequestRecord request = row(settlement, id, 1, 0, 10L + i,
                new BlockPos(i, 2, 0));
            assertEquals(RequestLedger.OpenResult.CREATED,
                ledger.open(request).result());
            UUID courier = UUID.randomUUID();
            long tick = 20L + i;
            assertTrue(request.reserve(courier, tick, 100L));
            assertTrue(request.markPickup(courier, tick));
            assertTrue(request.markInTransit(courier, 1, tick));
            assertTrue(request.noteDelivered(courier, 1, tick));
            assertTrue(request.markSatisfied(courier, tick));
            assertTrue(ledger.mutationCommitted(request));
        }
        assertEquals(RequestLedger.MAX_TERMINAL,
            ledger.terminalHistory().size());
        assertNull(ledger.any(firstId));
        assertTrue(ledger.active().isEmpty());
    }

    @Test
    void fingerprintsBindComponentsAndCountNotOnlyItemId() {
        RequestItemFingerprint plain = RequestItemFingerprint.synthetic(
            OAK_LOG, "plain", 4);
        RequestItemFingerprint named = RequestItemFingerprint.synthetic(
            OAK_LOG, "custom_name", 4);
        RequestItemFingerprint fewer = RequestItemFingerprint.synthetic(
            OAK_LOG, "plain", 3);
        assertNotEquals(plain, named);
        assertNotEquals(plain, fewer);
        assertNotEquals(plain.digest(), named.digest());
        assertEquals(plain, RequestItemFingerprint.synthetic(OAK_LOG,
            "plain", 4));
        assertTrue(plain.stableKey().endsWith("x4"));
    }

    private static RequestRecord row(int count, int targetBefore, long tick,
                                     BlockPos source) {
        return row(UUID.randomUUID(), UUID.randomUUID(), count, targetBefore,
            tick, source);
    }

    private static RequestRecord row(UUID settlement, UUID requestId,
                                     int count, int targetBefore, long tick,
                                     BlockPos source) {
        UUID sourceBuilding = UUID.randomUUID();
        UUID targetBuilding = UUID.randomUUID();
        return RequestRecord.openOutput(requestId, settlement,
            RequestPriority.NORMAL, OVERWORLD, sourceBuilding, source, 0,
            targetBuilding, source.offset(10, 0, 0),
            RequestItemFingerprint.synthetic(OAK_LOG, "plain", count),
            Math.max(count, 16), targetBefore, tick);
    }

    private static void assertWireIds(Object[] values, int... expected) {
        assertEquals(expected.length, values.length);
        Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < values.length; i++) {
            int actual = values[i] instanceof RequestType value ? value.wireId()
                : values[i] instanceof RequestState value ? value.wireId()
                : values[i] instanceof RequestPriority value ? value.wireId()
                : ((RequestBlocker) values[i]).wireId();
            assertEquals(expected[i], actual);
            assertTrue(unique.add(actual), "wire ids must be unique");
        }
    }
}
