package com.hearthstead.settlement.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLedgerSnapshotOwnershipTest {

    @Test
    void sourceOwnerRequiresThePersistedSlotFingerprintAndCount() {
        assertTrue(RequestLedgerSnapshot.exactSourceSlotOwnership(
            2, 9, true, 1, 1));
        assertFalse(RequestLedgerSnapshot.exactSourceSlotOwnership(
            2, 9, false, 1, 1),
            "a matching tool in a different slot cannot own this route");
        assertFalse(RequestLedgerSnapshot.exactSourceSlotOwnership(
            9, 9, true, 1, 1), "an out-of-range persisted slot fails closed");
        assertFalse(RequestLedgerSnapshot.exactSourceSlotOwnership(
            2, 9, true, 0, 1), "the exact slot must still own the count");
        assertFalse(RequestLedgerSnapshot.exactSourceSlotOwnership(
            2, 9, true, 1, 2), "partial source ownership is not authoritative");
    }

    @Test
    void courierOwnerRequiresOneBoundSameSettlementCourierAndExactBag() {
        assertTrue(RequestLedgerSnapshot.exactCourierBagOwnership(
            true, true, 1, 1, 1));
        assertFalse(RequestLedgerSnapshot.exactCourierBagOwnership(
            false, true, 1, 1, 1),
            "unbound, wrong-role, or cross-settlement entities never own it");
        assertFalse(RequestLedgerSnapshot.exactCourierBagOwnership(
            true, false, 1, 1, 1),
            "the live claim and persisted trace must name the same Courier");
        assertFalse(RequestLedgerSnapshot.exactCourierBagOwnership(
            true, true, 2, 1, 1),
            "unrelated cargo in the bag makes ownership ambiguous");
        assertFalse(RequestLedgerSnapshot.exactCourierBagOwnership(
            true, true, 1, 0, 1),
            "the exact fingerprint must be physically present once");
        assertFalse(RequestLedgerSnapshot.exactCourierBagOwnership(
            true, true, 1, 1, 0),
            "a completed trace cannot still claim Courier-bag ownership");
    }
}
