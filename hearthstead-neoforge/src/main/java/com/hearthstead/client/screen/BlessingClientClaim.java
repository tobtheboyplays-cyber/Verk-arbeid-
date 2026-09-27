package com.hearthstead.client.screen;

import com.hearthstead.network.BlessingReceipt;
import com.hearthstead.network.BlessingSnapshotPayload;
import com.hearthstead.settlement.state.BlessingId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Screen-lifetime receipt guards. Resizing rebuilds widgets, never this state. */
final class BlessingClientClaim {
    enum Phase { NONE, WAITING_SLOT, ANIMATING, NOTICE, FINISHED }
    private final UUID settlement;
    private final UUID session;
    private final Set<UUID> deliveries = new HashSet<>();
    private BlessingSnapshotPayload latest;
    private int acceptedRevision = -1;
    private int submittedRevision = -1;
    private int submittedSerial;
    private BlessingId submittedChoice;
    private int submittedUnits = 1;
    private boolean submissionPending;
    private BlessingReceipt receipt;
    private BlessingId winner;
    private Phase phase = Phase.NONE;
    private int ticks;

    BlessingClientClaim(BlessingSnapshotPayload initial) {
        settlement = initial.settlementId();
        session = initial.sessionId();
        latest = initial;
        if (initial.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED) {
            // Opening a screen with an old receipt is never a local submission.
            acceptedRevision = initial.revision();
            winner = initial.feedbackBlessing().orElse(null);
            phase = Phase.NOTICE;
        }
    }

    boolean submit(BlessingSnapshotPayload current, BlessingId choice) {
        if (current == null || choice == null || submissionPending || phase != Phase.NONE
                || !current.hasPendingOffer()
                || !(current.equals(latest) || latest.hasFollowUpOffer()
                    && current.equals(latest.asChoiceUpdate()))) {
            return false;
        }
        submittedRevision = current.revision();
        submittedSerial = current.offerSerial();
        submittedChoice = choice;
        submittedUnits = current.rankUnits(choice);
        submissionPending = true;
        return true;
    }

    boolean accept(BlessingSnapshotPayload fresh, UUID player) {
        if (!settlement.equals(fresh.settlementId()) || !session.equals(fresh.sessionId())
                || fresh.revision() < latest.revision()) return false;
        boolean accepted = fresh.feedback() == BlessingSnapshotPayload.Feedback.ACCEPTED;
        if (fresh.equals(latest) && !(submissionPending && isRefusal(fresh))) return false;
        if (accepted && (fresh.revision() <= acceptedRevision
                || fresh.receipt() != null && deliveries.contains(fresh.receipt().deliveryId()))) return false;
        // Do not let an equal-revision refresh replay an already consumed offer.
        if (fresh.hasPendingOffer() && fresh.revision() <= acceptedRevision
                && fresh.offerSerial() <= submittedSerial) return false;
        latest = fresh;
        submissionPending = false;
        if (accepted) {
            acceptedRevision = fresh.revision();
            winner = fresh.feedbackBlessing().orElse(null);
            receipt = fresh.receipt();
            if (receipt != null) deliveries.add(receipt.deliveryId());
            boolean matched = receipt != null && receipt.matchesSubmission(player,
                submittedRevision, submittedSerial, submittedChoice, submittedUnits);
            if (!matched) receipt = null;
            phase = matched && receipt.outcome().direct() ? Phase.WAITING_SLOT : Phase.NOTICE;
            ticks = 0;
            submittedChoice = null;
        }
        return true;
    }

    private static boolean isRefusal(BlessingSnapshotPayload snapshot) {
        return switch (snapshot.feedback()) {
            case STALE, INVALID_CHOICE, MAXED, DELIVERY_BACKLOG, TOO_FAR, UNAVAILABLE -> true;
            default -> false;
        };
    }

    /** Exact slot proof is supplied by BlessingReceipt.matchesSlot, never by item type alone. */
    boolean tickSlot(boolean exactSlot) {
        if (phase != Phase.WAITING_SLOT) return false;
        if (exactSlot) {
            phase = Phase.ANIMATING;
            return true;
        }
        if (++ticks >= 60) {
            phase = Phase.NOTICE;
            ticks = 0;
        }
        return false;
    }

    void tickNotice() {
        if (phase == Phase.NOTICE && ++ticks >= 30) phase = Phase.FINISHED;
    }

    void animationFinished() {
        if (phase == Phase.ANIMATING) phase = Phase.FINISHED;
    }

    void clearFinished() {
        if (phase == Phase.FINISHED) phase = Phase.NONE;
    }

    boolean presenting() { return phase != Phase.NONE && phase != Phase.FINISHED; }
    Phase phase() { return phase; }
    BlessingReceipt receipt() { return receipt; }
    BlessingId winner() { return winner; }
}
