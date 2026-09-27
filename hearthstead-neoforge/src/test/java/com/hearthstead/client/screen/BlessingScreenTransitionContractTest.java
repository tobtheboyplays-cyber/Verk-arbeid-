package com.hearthstead.client.screen;

import com.hearthstead.network.BlessingSnapshotPayload;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Valid choices cannot be skipped; server refusal and terminal cleanup stay escapable. */
class BlessingScreenTransitionContractTest {
    @Test
    void pendingAndQueuedOffersRequireChoiceWhileTerminalReceiptsAllowExit() {
        for (var feedback : new BlessingSnapshotPayload.Feedback[] {
                BlessingSnapshotPayload.Feedback.NONE, BlessingSnapshotPayload.Feedback.STALE,
                BlessingSnapshotPayload.Feedback.INVALID_CHOICE, BlessingSnapshotPayload.Feedback.MAXED}) {
            assertTrue(BlessingScreen.requiresChoice(snapshot(BlessingSnapshotPayload.Delivery.UPDATE, feedback, 2)));
        }
        for (var feedback : new BlessingSnapshotPayload.Feedback[] {
                BlessingSnapshotPayload.Feedback.ACCEPTED, BlessingSnapshotPayload.Feedback.OTHER_PLAYER_CHOSE}) {
            var queued = snapshot(BlessingSnapshotPayload.Delivery.UPDATE, feedback, 2);
            assertTrue(BlessingScreen.requiresChoice(queued));
            assertTrue(BlessingScreen.requiresChoice(queued.asChoiceUpdate()));
            assertFalse(BlessingScreen.requiresChoice(snapshot(BlessingSnapshotPayload.Delivery.RESULT, feedback, 0)));
        }
    }

    @Test
    void unusableDeliveryOrUnavailableSessionNeverTrapsThePlayer() {
        assertFalse(BlessingScreen.requiresChoice(null));
        assertFalse(BlessingScreen.requiresChoice(snapshot(BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.DELIVERY_BACKLOG, 2)));
        assertFalse(BlessingScreen.requiresChoice(snapshot(BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.TOO_FAR, 2)));
        assertFalse(BlessingScreen.requiresChoice(snapshot(BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.UNAVAILABLE, 2)));
    }

    @Test
    void directClaimIsBlockedDuringSubmissionAndCelebration() {
        var pending = snapshot(BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.NONE, 2);
        assertTrue(BlessingScreen.canClaim(pending, false, false));
        assertFalse(BlessingScreen.canClaim(pending, true, false));
        assertFalse(BlessingScreen.canClaim(pending, false, true));
        var queued = snapshot(BlessingSnapshotPayload.Delivery.UPDATE,
            BlessingSnapshotPayload.Feedback.ACCEPTED, 2);
        assertFalse(BlessingScreen.canClaim(queued, false, false));
        assertTrue(BlessingScreen.canClaim(queued.asChoiceUpdate(), false, false));
        assertFalse(BlessingScreen.canClaim(null, false, false));
        assertFalse(BlessingScreen.canClaim(snapshot(BlessingSnapshotPayload.Delivery.RESULT,
            BlessingSnapshotPayload.Feedback.UNAVAILABLE, 2), false, false));
    }

    @Test
    void heldInputCannotClaimAgainAfterRefusalResizeOrNextOffer() {
        var edges = new BlessingScreen.InputEdges();
        // The screen retains this same latch while widgets and snapshots change.
        for (int input : new int[] {-1, 32, 257, 335}) {
            assertTrue(edges.press(input));
            assertFalse(edges.press(input));
            assertFalse(edges.press(input));
            edges.release(input);
            assertTrue(edges.press(input));
            edges.release(input);
        }
        assertTrue(edges.press(257));
        edges.release(-1);
        assertFalse(edges.press(257), "mouse release cannot rearm a held Enter key");
        edges.release(257);
        assertTrue(edges.press(257));
    }

    private static BlessingSnapshotPayload snapshot(BlessingSnapshotPayload.Delivery delivery,
                                                    BlessingSnapshotPayload.Feedback feedback, int serial) {
        return new BlessingSnapshotPayload(new UUID(1, 2), new UUID(3, 4), "Hearth",
            4, serial, 0, 0, 0, delivery, feedback, -1);
    }
}
