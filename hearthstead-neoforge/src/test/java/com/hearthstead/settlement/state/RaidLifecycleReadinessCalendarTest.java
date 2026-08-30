package com.hearthstead.settlement.state;

import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidLifecycleReadinessCalendarTest {
    @Test
    void fastPlayerKeepsTheSingleFoundingRoll() {
        RaidLifecycle lifecycle = new RaidLifecycle();

        assertTrue(lifecycle.prepareAtFounding(10L, 7, 2));
        assertEquals(FirstRaidState.PREPARING, lifecycle.firstState());
        assertEquals(17L, lifecycle.rolledNotBeforeNight());
        assertEquals(RaidLifecycle.UNSET_NIGHT, lifecycle.firstWarningNight());
        assertEquals(RaidLifecycle.UNSET_NIGHT, lifecycle.firstAttackNight());

        assertTrue(lifecycle.scheduleAfterReadiness(12L));
        assertEquals(FirstRaidState.SCHEDULED, lifecycle.firstState());
        assertEquals(12L, lifecycle.readinessNight());
        assertEquals(15L, lifecycle.firstWarningNight());
        assertEquals(17L, lifecycle.firstAttackNight());
    }

    @Test
    void slowPlayerAlwaysReceivesTheFullWarningLead() {
        RaidLifecycle lifecycle = new RaidLifecycle();

        assertTrue(lifecycle.prepareAtFounding(10L, 4, 2));
        assertTrue(lifecycle.scheduleAfterReadiness(20L));
        assertEquals(20L, lifecycle.firstWarningNight());
        assertEquals(22L, lifecycle.firstAttackNight());
        assertEquals(2L,
            lifecycle.firstAttackNight() - lifecycle.readinessNight());
    }

    @Test
    void readinessCommitIsOneShotAndRestartStable() {
        RaidLifecycle lifecycle = new RaidLifecycle();
        assertTrue(lifecycle.prepareAtFounding(30L, 5, 1));
        assertTrue(lifecycle.scheduleAfterReadiness(40L));
        long warning = lifecycle.firstWarningNight();
        long attack = lifecycle.firstAttackNight();

        assertFalse(lifecycle.scheduleAfterReadiness(90L));
        RaidLifecycle loaded = RaidLifecycle.readNbt(lifecycle.writeNbt());
        assertFalse(loaded.integrityLost());
        assertEquals(FirstRaidState.SCHEDULED, loaded.firstState());
        assertEquals(40L, loaded.readinessNight());
        assertEquals(warning, loaded.firstWarningNight());
        assertEquals(attack, loaded.firstAttackNight());
        assertFalse(loaded.scheduleAfterReadiness(90L));
    }

    @Test
    void partialCurrentCalendarFailsClosed() {
        RaidLifecycle lifecycle = new RaidLifecycle();
        assertTrue(lifecycle.prepareAtFounding(4L, 4, 2));
        CompoundTag damaged = lifecycle.writeNbt();
        damaged.remove("WarningLead");

        RaidLifecycle loaded = RaidLifecycle.readNbt(damaged);
        assertTrue(loaded.integrityLost());
        assertEquals(FirstRaidState.PREPARING, loaded.firstState());
        assertFalse(loaded.scheduleAfterReadiness(8L));
    }

    @Test
    void olderScheduledSavePreservesItsExactDates() {
        RaidLifecycle oldShape = new RaidLifecycle();
        assertTrue(oldShape.initializeAtFounding(20L, 6, 1));
        CompoundTag legacy = oldShape.writeNbt();
        legacy.remove("RolledNotBeforeNight");
        legacy.remove("WarningLead");
        legacy.remove("ReadinessNight");

        RaidLifecycle loaded = RaidLifecycle.readNbt(legacy);
        assertFalse(loaded.integrityLost());
        assertEquals(FirstRaidState.SCHEDULED, loaded.firstState());
        assertEquals(25L, loaded.firstWarningNight());
        assertEquals(26L, loaded.firstAttackNight());
    }

    @Test
    void preparingAppendsWireIdWithoutRenumberingExistingStates() {
        assertEquals(0, FirstRaidState.UNINITIALIZED.wireId());
        assertEquals(1, FirstRaidState.SCHEDULED.wireId());
        assertEquals(2, FirstRaidState.ACTIVE.wireId());
        assertEquals(3, FirstRaidState.COMPLETED.wireId());
        assertEquals(4, FirstRaidState.PREPARING.wireId());
    }

    @Test
    void terminalOutcomePlanAndAftermathRoundTripAsOneExactReceipt() {
        RaidPlan plan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000610"),
            RaidObjective.BRANN, -42.5F, 7L);
        RaidLogEntry aftermath = new RaidLogEntry(7L, "Eira Storm-Song",
            "brann", false, 0, 2, "rolig");
        RaidLifecycle lifecycle = activeTerminalReady(plan);

        assertTrue(lifecycle.completeFirstRaid(JourneyOutcome.HIT, aftermath));
        assertFalse(lifecycle.mayGrantReward(), "HIT must never carry a reward token");

        RaidLifecycle loaded = RaidLifecycle.readNbt(lifecycle.writeNbt());
        assertFalse(loaded.integrityLost());
        assertEquals(JourneyOutcome.HIT,
            loaded.firstRaidTerminal().orElseThrow().outcome());
        assertEquals(plan, loaded.firstRaidTerminal().orElseThrow().plan());
        assertEquals(aftermath,
            loaded.firstRaidTerminal().orElseThrow().aftermath());
    }

    @Test
    void malformedOrMismatchedTerminalReceiptFailsClosed() {
        RaidPlan plan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000611"),
            RaidObjective.KORN, 20.0F, 8L);
        RaidLifecycle lifecycle = activeTerminalReady(plan);
        assertTrue(lifecycle.completeFirstRaid(JourneyOutcome.HELD,
            new RaidLogEntry(8L, "Torv Kniv", "korn", true,
                0, 1, "uro")));

        CompoundTag badOutcome = lifecycle.writeNbt();
        badOutcome.getCompound("FirstRaidTerminal")
            .putInt("OutcomeWireId", JourneyOutcome.HIT.wireId());
        assertTrue(RaidLifecycle.readNbt(badOutcome).integrityLost());

        CompoundTag badReport = lifecycle.writeNbt();
        badReport.getCompound("FirstRaidTerminal")
            .getCompound("Aftermath").putBoolean("Held", false);
        assertTrue(RaidLifecycle.readNbt(badReport).integrityLost());

        CompoundTag missing = lifecycle.writeNbt();
        missing.remove("FirstRaidTerminal");
        assertTrue(RaidLifecycle.readNbt(missing).integrityLost());
    }

    private static RaidLifecycle activeTerminalReady(RaidPlan plan) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        UUID participant = UUID.fromString(
            "00000000-0000-0000-0000-000000000612");
        assertTrue(lifecycle.initializeAtFounding(plan.night() - 4L, 4, 2));
        assertTrue(lifecycle.queueFirstPlan(plan));
        assertTrue(lifecycle.beginFirstRaid(plan));
        assertTrue(lifecycle.recordParticipant(participant));
        assertTrue(lifecycle.sealParticipants());
        assertTrue(lifecycle.recordTerminalParticipant(participant));
        return lifecycle;
    }
}
