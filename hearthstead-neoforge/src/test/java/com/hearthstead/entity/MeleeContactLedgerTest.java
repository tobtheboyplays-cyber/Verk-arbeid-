package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeContactLedgerTest {

    @Test
    void contactConsumesOnlyOnItsExactTargetAndExactTick() {
        MeleeContactLedger ledger = new MeleeContactLedger();
        UUID target = UUID.randomUUID();
        long ticket = ledger.begin(target, 104L);

        assertNotEquals(MeleeContactLedger.NO_TICKET, ticket);
        assertFalse(ledger.consume(ticket, target, 100L),
            "wind-up must never damage before the authored contact tick");
        assertFalse(ledger.consume(ticket, UUID.randomUUID(), 104L),
            "a target switch must not inherit another target's blade");
        assertTrue(ledger.isActive(ticket),
            "early/wrong-target attempts must not consume the real ticket");
        assertTrue(ledger.consume(ticket, target, 104L));
        assertFalse(ledger.consume(ticket, target, 104L),
            "one contact ticket must authorize exactly one damage attempt");
    }

    @Test
    void missedContactTickExpiresFailClosedWhenCanceled() {
        MeleeContactLedger ledger = new MeleeContactLedger();
        UUID target = UUID.randomUUID();
        long ticket = ledger.begin(target, 44L);

        assertFalse(ledger.consume(ticket, target, 45L),
            "late catch-up damage is not the authored contact frame");
        ledger.cancel(ticket);
        assertFalse(ledger.consume(ticket, target, 44L));
    }

    @Test
    void interruptionAndReloadResetCannotReplayAContact() {
        MeleeContactLedger ledger = new MeleeContactLedger();
        UUID target = UUID.randomUUID();
        long interrupted = ledger.begin(target, 24L);
        ledger.cancel(interrupted);
        assertFalse(ledger.consume(interrupted, target, 24L));

        long beforeReload = ledger.begin(target, 64L);
        ledger.resetTransientState();
        assertFalse(ledger.consume(beforeReload, target, 64L),
            "runtime contact authority must never survive a save/reload seam");
    }

    @Test
    void terminalCountAdvancesOnlyAfterSuccessfulHurtCommit() {
        MeleeContactLedger ledger = new MeleeContactLedger();
        UUID target = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        long ticket = ledger.begin(target, 20L, action);

        MeleeContactLedger.Attempt attempt = ledger.consumeAttempt(ticket,
            target, 20L);
        assertNotNull(attempt);
        assertEquals(0L, ledger.committedCount(),
            "consuming the one-shot before hurt is not a successful contact");

        MeleeContactLedger.Commit commit = ledger.commit(attempt);
        assertNotNull(commit);
        assertEquals(action, commit.actionId());
        assertEquals(1L, ledger.committedCount());
        assertNull(ledger.commit(attempt),
            "replaying immutable attempt evidence must not count twice");
    }

    @Test
    void failedHurtAttemptNeverBecomesACommit() {
        MeleeContactLedger ledger = new MeleeContactLedger();
        UUID target = UUID.randomUUID();
        long failed = ledger.begin(target, 30L, UUID.randomUUID());
        assertNotNull(ledger.consumeAttempt(failed, target, 30L));
        // Production deliberately does not call commit when doHurtTarget=false.
        assertEquals(0L, ledger.committedCount());

        long successful = ledger.begin(target, 31L, UUID.randomUUID());
        MeleeContactLedger.Attempt next = ledger.consumeAttempt(successful,
            target, 31L);
        assertNotNull(ledger.commit(next));
        assertEquals(1L, ledger.committedCount());
    }
}
