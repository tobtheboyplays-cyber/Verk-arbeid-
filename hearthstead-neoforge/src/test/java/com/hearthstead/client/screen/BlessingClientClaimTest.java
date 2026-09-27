package com.hearthstead.client.screen;

import com.hearthstead.network.BlessingReceipt;
import com.hearthstead.network.BlessingSnapshotPayload;
import com.hearthstead.settlement.state.BlessingId;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BlessingClientClaimTest {
    private static final UUID SETTLEMENT = new UUID(1, 2);
    private static final UUID SESSION = new UUID(3, 4);
    private static final UUID PLAYER = new UUID(5, 6);
    private static final UUID DELIVERY = new UUID(7, 8);

    @Test
    void directSubmissionDebouncesAndRejectsDifferentOrUnacknowledgedOffers() {
        var open = open(4, SESSION);
        var claim = new BlessingClientClaim(open);
        assertFalse(claim.submit(open(4, new UUID(9, 10)), BlessingId.WARDEN_OATH));
        assertFalse(claim.submit(open(5, SESSION), BlessingId.WARDEN_OATH));
        assertTrue(claim.submit(open, BlessingId.WARDEN_OATH));
        assertFalse(claim.submit(open, BlessingId.HEARTHWARD));
        var received = accepted(BlessingReceipt.Outcome.INVENTORY, 12, SESSION, DELIVERY);
        assertTrue(claim.accept(received, PLAYER));
        assertFalse(claim.submit(received.asChoiceUpdate(), BlessingId.HEARTHWARD));
        assertTrue(claim.tickSlot(true));
        assertFalse(claim.submit(received.asChoiceUpdate(), BlessingId.HEARTHWARD));
        claim.animationFinished();
        assertFalse(claim.submit(received.asChoiceUpdate(), BlessingId.HEARTHWARD));
        claim.clearFinished();
        assertTrue(claim.submit(received.asChoiceUpdate(), BlessingId.HEARTHWARD));
        assertFalse(claim.submit(received.asChoiceUpdate(), BlessingId.WARDEN_OATH));
    }

    @Test
    void deferredSlotStartsOnlyOnceAndDuplicateCannotResetAnimationOrReplayAfterCompletion() {
        var open = open(4, SESSION);
        var claim = new BlessingClientClaim(open);
        claim.submit(open, BlessingId.WARDEN_OATH);
        var accepted = accepted(BlessingReceipt.Outcome.INVENTORY, 12, SESSION, DELIVERY);
        assertTrue(claim.accept(accepted, PLAYER));
        for (int i = 0; i < 59; i++) assertFalse(claim.tickSlot(false));
        assertEquals(BlessingClientClaim.Phase.WAITING_SLOT, claim.phase());
        assertFalse(claim.accept(accepted, PLAYER));
        assertTrue(claim.tickSlot(true));
        assertFalse(claim.tickSlot(true));
        assertFalse(claim.accept(accepted, PLAYER));
        assertEquals(BlessingClientClaim.Phase.ANIMATING, claim.phase());
        claim.animationFinished();
        claim.clearFinished();
        assertFalse(claim.accept(accepted, PLAYER));
        assertEquals(BlessingClientClaim.Phase.NONE, claim.phase());
        assertFalse(claim.accept(open, PLAYER));
    }

    @Test
    void absentSlotTimesOutWithoutFlightAndRepeatedUpdateCannotExtendWait() {
        var open = open(4, SESSION);
        var claim = new BlessingClientClaim(open);
        claim.submit(open, BlessingId.WARDEN_OATH);
        var accepted = accepted(BlessingReceipt.Outcome.MAIN_HAND, 0, SESSION, DELIVERY);
        assertTrue(claim.accept(accepted, PLAYER));
        for (int i = 0; i < 60; i++) {
            assertFalse(claim.accept(accepted, PLAYER));
            assertFalse(claim.tickSlot(false));
        }
        assertEquals(BlessingClientClaim.Phase.NOTICE, claim.phase());
        assertFalse(claim.tickSlot(true));
        for (int i = 0; i < 30; i++) claim.tickNotice();
        assertEquals(BlessingClientClaim.Phase.FINISHED, claim.phase());
    }

    @Test
    void wrongSessionOldRevisionAndMismatchedSubmissionCannotAuthorizeFlight() {
        var open = open(4, SESSION);
        var claim = new BlessingClientClaim(open);
        claim.submit(open, BlessingId.HEARTHWARD);
        assertFalse(claim.accept(accepted(BlessingReceipt.Outcome.MAIN_HAND, 0,
            new UUID(9, 10), DELIVERY), PLAYER));
        assertFalse(claim.accept(open(3, SESSION), PLAYER));
        assertTrue(claim.accept(accepted(BlessingReceipt.Outcome.MAIN_HAND, 0,
            SESSION, DELIVERY), PLAYER));
        assertEquals(BlessingClientClaim.Phase.NOTICE, claim.phase());
        assertNull(claim.receipt());
        assertFalse(claim.tickSlot(true));
    }

    @Test
    void nonDirectAndPlainReceiptsNeverAnimateAndNextOfferNeedsNewSubmission() {
        for (var outcome : new BlessingReceipt.Outcome[] {BlessingReceipt.Outcome.DROP,
                BlessingReceipt.Outcome.PENDING, BlessingReceipt.Outcome.ALREADY_DELIVERED}) {
            var open = open(4, SESSION);
            var claim = new BlessingClientClaim(open);
            claim.submit(open, BlessingId.WARDEN_OATH);
            var accepted = accepted(outcome, -1, SESSION, DELIVERY);
            assertTrue(claim.accept(accepted, PLAYER));
            assertEquals(BlessingClientClaim.Phase.NOTICE, claim.phase());
            assertEquals(outcome, claim.receipt().outcome());
            assertFalse(claim.tickSlot(true));
            for (int i = 0; i < 30; i++) claim.tickNotice();
            claim.clearFinished();
            assertTrue(claim.accept(accepted.asChoiceUpdate(), PLAYER));
            assertEquals(BlessingClientClaim.Phase.NONE, claim.phase());
        }
        var claim = new BlessingClientClaim(open(4, SESSION));
        var plain = new BlessingSnapshotPayload(SETTLEMENT, SESSION, "Hearth", 5, 2,
            1, 0, 0, BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED, 0);
        assertTrue(claim.accept(plain, PLAYER));
        assertEquals(BlessingClientClaim.Phase.NOTICE, claim.phase());
        assertFalse(claim.tickSlot(true));
    }

    @Test
    void repeatedRefusalAcknowledgesOnlyANewSubmissionWithoutReplayingAcceptedReceipt() {
        for (var feedback : new BlessingSnapshotPayload.Feedback[] {
                BlessingSnapshotPayload.Feedback.DELIVERY_BACKLOG,
                BlessingSnapshotPayload.Feedback.INVALID_CHOICE,
                BlessingSnapshotPayload.Feedback.MAXED}) {
            var open = open(4, SESSION);
            var claim = new BlessingClientClaim(open);
            var refusal = new BlessingSnapshotPayload(SETTLEMENT, SESSION, "Hearth", 4, 1,
                0, 0, 0, BlessingSnapshotPayload.Delivery.UPDATE, feedback, -1);
            claim.submit(open, BlessingId.WARDEN_OATH);
            assertTrue(claim.accept(refusal, PLAYER));
            assertFalse(claim.accept(refusal, PLAYER));
            claim.submit(refusal, BlessingId.WARDEN_OATH);
            assertTrue(claim.accept(refusal, PLAYER),
                "the same refusal must release a newly submitted screen's waiting state");
            assertFalse(claim.accept(refusal, PLAYER));
            assertEquals(BlessingClientClaim.Phase.NONE, claim.phase());
            claim.submit(refusal, BlessingId.WARDEN_OATH);
            var accepted = accepted(BlessingReceipt.Outcome.INVENTORY, 12, SESSION, DELIVERY);
            assertTrue(claim.accept(accepted, PLAYER));
            assertFalse(claim.accept(accepted, PLAYER));
            assertEquals(BlessingClientClaim.Phase.WAITING_SLOT, claim.phase());
        }
    }

    @Test
    void aDifferentQualityCannotAuthorizeFlightForTheSameOfferChoice() {
        var rareOpen = new BlessingSnapshotPayload(SETTLEMENT, SESSION, "Hearth", 4, 1,
            0, 0, 0, BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1, null, 2, 1, 1);
        var claim = new BlessingClientClaim(rareOpen);
        claim.submit(rareOpen, BlessingId.WARDEN_OATH);
        assertTrue(claim.accept(accepted(BlessingReceipt.Outcome.INVENTORY, 12, SESSION, DELIVERY), PLAYER));
        assertEquals(BlessingClientClaim.Phase.NOTICE, claim.phase());
        assertFalse(claim.tickSlot(true));

        var matching = new BlessingClientClaim(rareOpen);
        matching.submit(rareOpen, BlessingId.WARDEN_OATH);
        var rareReceipt = new BlessingReceipt(PLAYER, DELIVERY, 4, 1, 0,
            BlessingReceipt.Outcome.INVENTORY, 12, 2);
        var rareAccepted = new BlessingSnapshotPayload(SETTLEMENT, SESSION, "Hearth", 5, 2,
            1, 0, 0, BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED, 0, rareReceipt, 1, 2, 1);
        assertTrue(matching.accept(rareAccepted, PLAYER));
        assertEquals(BlessingClientClaim.Phase.WAITING_SLOT, matching.phase());
    }

    private static BlessingSnapshotPayload open(int revision, UUID session) {
        return new BlessingSnapshotPayload(SETTLEMENT, session, "Hearth", revision, 1,
            0, 0, 0, BlessingSnapshotPayload.Delivery.OPEN,
            BlessingSnapshotPayload.Feedback.NONE, -1);
    }

    private static BlessingSnapshotPayload accepted(BlessingReceipt.Outcome outcome,
            int slot, UUID session, UUID delivery) {
        var receipt = new BlessingReceipt(PLAYER, delivery, 4, 1, 0, outcome, slot);
        return new BlessingSnapshotPayload(SETTLEMENT, session, "Hearth", 5, 2,
            1, 0, 0, BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED, 0, receipt);
    }
}
