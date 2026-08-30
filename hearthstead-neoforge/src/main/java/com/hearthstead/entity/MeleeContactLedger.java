package com.hearthstead.entity;

import java.util.UUID;

/**
 * One transient, server-authored melee contact ticket.
 *
 * <p>The ledger deliberately has no NBT representation. A save taken during
 * wind-up therefore reloads with no contact to replay: the guard may begin a
 * new, fully validated swing later, but an old blade can never deal damage
 * after the entity that authored it has gone away.
 */
final class MeleeContactLedger {
    static final long NO_TICKET = 0L;

    private long nextTicket = 1L;
    private long activeTicket = NO_TICKET;
    private UUID targetId;
    private UUID actionId;
    private long contactTick = Long.MIN_VALUE;
    private long terminalRevision;
    private long committedCount;

    record Attempt(long ticket, UUID targetId, UUID actionId,
                   long terminalRevisionBefore, long committedCountBefore) {
    }

    record Commit(UUID actionId, long revisionBefore, long revisionAfter,
                  long countBefore, long countAfter) {
    }

    long begin(UUID target, long dueTick) {
        return begin(target, dueTick, UUID.randomUUID());
    }

    long begin(UUID target, long dueTick, UUID authoredActionId) {
        if (target == null || authoredActionId == null
            || isNil(authoredActionId) || activeTicket != NO_TICKET) {
            return NO_TICKET;
        }
        long issued = nextTicket++;
        if (issued == NO_TICKET) {
            // Long overflow is not reachable in a real world, but zero is the
            // stable sentinel and must never become a valid authority token.
            issued = nextTicket++;
        }
        activeTicket = issued;
        targetId = target;
        actionId = authoredActionId;
        contactTick = dueTick;
        return issued;
    }

    /**
     * Consumes before damage is attempted. Even re-entrant code or a retry on
     * the same server tick therefore observes an already-spent ticket.
     */
    boolean consume(long ticket, UUID target, long now) {
        return consumeAttempt(ticket, target, now) != null;
    }

    /**
     * Consumes the one-shot before damage and returns immutable evidence that
     * may be committed only if the caller's real hurt operation succeeds.
     */
    Attempt consumeAttempt(long ticket, UUID target, long now) {
        if (ticket == NO_TICKET || ticket != activeTicket
            || target == null || !target.equals(targetId)
            || now != contactTick) {
            return null;
        }
        Attempt attempt = new Attempt(ticket, targetId, actionId,
            terminalRevision, committedCount);
        clearActive();
        return attempt;
    }

    /** Records one successful hurt; a miss or immune target never calls this. */
    Commit commit(Attempt attempt) {
        if (attempt == null || attempt.actionId() == null
            || isNil(attempt.actionId())
            || attempt.terminalRevisionBefore() != terminalRevision
            || attempt.committedCountBefore() != committedCount
            || terminalRevision == Long.MAX_VALUE
            || committedCount == Long.MAX_VALUE) {
            return null;
        }
        long revisionBefore = terminalRevision;
        long countBefore = committedCount;
        terminalRevision++;
        committedCount++;
        return new Commit(attempt.actionId(), revisionBefore, terminalRevision,
            countBefore, committedCount);
    }

    void cancel(long ticket) {
        if (ticket != NO_TICKET && ticket == activeTicket) {
            clearActive();
        }
    }

    void resetTransientState() {
        clearActive();
        terminalRevision = 0L;
        committedCount = 0L;
    }

    boolean isActive(long ticket) {
        return ticket != NO_TICKET && ticket == activeTicket;
    }

    long committedCount() {
        return committedCount;
    }

    private void clearActive() {
        activeTicket = NO_TICKET;
        targetId = null;
        actionId = null;
        contactTick = Long.MIN_VALUE;
    }

    private static boolean isNil(UUID id) {
        return id.getMostSignificantBits() == 0L
            && id.getLeastSignificantBits() == 0L;
    }
}
