package com.hearthstead.settlement.state;

import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
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
        legacy.remove("FirstRaidCalendarProvenance");

        RaidLifecycle loaded = RaidLifecycle.readNbt(legacy);
        assertFalse(loaded.integrityLost());
        assertEquals(FirstRaidState.SCHEDULED, loaded.firstState());
        assertEquals(25L, loaded.firstWarningNight());
        assertEquals(26L, loaded.firstAttackNight());
    }

    @Test
    void newBalancedPreparingCalendarRoundTripsAndAnchorsFastOrSlowReadiness() {
        RaidLifecycle randomized = new RaidLifecycle();
        assertTrue(randomized.prepareAtFounding(10L,
            RandomSource.create(0xB02L), RaidProfile.BALANCED));
        assertEquals(FirstRaidState.PREPARING, randomized.firstState());
        assertTrue(randomized.rolledNotBeforeNight() >= 12L);
        assertTrue(randomized.rolledNotBeforeNight() <= 13L);
        assertEquals(1, randomized.warningLead());

        RaidLifecycle fast = new RaidLifecycle();
        assertTrue(fast.prepareAtFounding(10L, 2, 1, RaidProfile.BALANCED));
        RaidLifecycle reloadedFast = RaidLifecycle.readNbt(fast.writeNbt());

        assertFalse(reloadedFast.integrityLost());
        assertEquals(FirstRaidState.PREPARING, reloadedFast.firstState());
        assertEquals(12L, reloadedFast.rolledNotBeforeNight());
        assertTrue(reloadedFast.scheduleAfterReadiness(11L));
        assertEquals(11L, reloadedFast.firstWarningNight());
        assertEquals(12L, reloadedFast.firstAttackNight());

        RaidLifecycle slow = new RaidLifecycle();
        assertTrue(slow.prepareAtFounding(10L, 3, 1, RaidProfile.BALANCED));
        RaidLifecycle reloadedSlow = RaidLifecycle.readNbt(slow.writeNbt());

        assertFalse(reloadedSlow.integrityLost());
        assertTrue(reloadedSlow.scheduleAfterReadiness(20L));
        assertEquals(20L, reloadedSlow.firstWarningNight());
        assertEquals(21L, reloadedSlow.firstAttackNight());
        assertFalse(reloadedSlow.scheduleAfterReadiness(30L));
    }

    @Test
    void oldPreparingAndScheduledCalendarsRetainTheStrictLegacyFloor() {
        RaidLifecycle preparing = new RaidLifecycle();
        assertTrue(preparing.prepareAtFounding(10L, 4, 2));
        CompoundTag oldPreparing = preparing.writeNbt();
        oldPreparing.remove("FirstRaidCalendarProvenance");
        RaidLifecycle loadedPreparing = RaidLifecycle.readNbt(oldPreparing);

        assertFalse(loadedPreparing.integrityLost());
        assertTrue(loadedPreparing.scheduleAfterReadiness(10L));
        assertEquals(12L, loadedPreparing.firstWarningNight());
        assertEquals(14L, loadedPreparing.firstAttackNight());

        RaidLifecycle scheduled = new RaidLifecycle();
        assertTrue(scheduled.initializeAtFounding(20L, 7, 2));
        CompoundTag oldScheduled = scheduled.writeNbt();
        oldScheduled.remove("FirstRaidCalendarProvenance");
        RaidLifecycle loadedScheduled = RaidLifecycle.readNbt(oldScheduled);

        assertFalse(loadedScheduled.integrityLost());
        assertEquals(25L, loadedScheduled.firstWarningNight());
        assertEquals(27L, loadedScheduled.firstAttackNight());
    }

    @Test
    void otherProfilesRetainTheirExistingFoundingCalendars() {
        RaidLifecycle peaceful = new RaidLifecycle();
        assertTrue(peaceful.prepareAtFounding(10L, 4, 2, RaidProfile.PEACEFUL));
        assertTrue(peaceful.scheduleAfterReadiness(10L));
        assertEquals(12L, peaceful.firstWarningNight());
        assertEquals(14L, peaceful.firstAttackNight());

        RaidLifecycle ironWinter = new RaidLifecycle();
        assertTrue(ironWinter.prepareAtFounding(10L, 7, 1,
            RaidProfile.IRON_WINTER));
        assertTrue(ironWinter.scheduleAfterReadiness(10L));
        assertEquals(16L, ironWinter.firstWarningNight());
        assertEquals(17L, ironWinter.firstAttackNight());

        assertFalse(new RaidLifecycle().prepareAtFounding(10L, 3, 2,
            RaidProfile.PEACEFUL));
        assertFalse(new RaidLifecycle().prepareAtFounding(10L, 3, 1,
            RaidProfile.IRON_WINTER));
        assertFalse(new RaidLifecycle().prepareAtFounding(10L, 2, 2,
            RaidProfile.BALANCED));
    }

    @Test
    void unknownPartialOrWrongTypedNewCalendarFailsClosedWithoutReroll() {
        RaidLifecycle balanced = new RaidLifecycle();
        assertTrue(balanced.prepareAtFounding(10L, 2, 1, RaidProfile.BALANCED));

        CompoundTag unknown = balanced.writeNbt();
        unknown.putInt("FirstRaidCalendarProvenance", 99);
        RaidLifecycle unknownLoaded = RaidLifecycle.readNbt(unknown);
        assertTrue(unknownLoaded.integrityLost());
        assertFalse(unknownLoaded.scheduleAfterReadiness(11L));
        assertFalse(unknownLoaded.prepareAtFounding(20L, 2, 1,
            RaidProfile.BALANCED));

        CompoundTag wrongTyped = balanced.writeNbt();
        wrongTyped.putString("FirstRaidCalendarProvenance", "balanced_b02");
        RaidLifecycle wrongTypedLoaded = RaidLifecycle.readNbt(wrongTyped);
        assertTrue(wrongTypedLoaded.integrityLost());
        assertFalse(wrongTypedLoaded.scheduleAfterReadiness(11L));

        CompoundTag wrongBalancedLead = balanced.writeNbt();
        wrongBalancedLead.putInt("WarningLead", 2);
        RaidLifecycle wrongBalancedLeadLoaded = RaidLifecycle.readNbt(wrongBalancedLead);
        assertTrue(wrongBalancedLeadLoaded.integrityLost());
        assertFalse(wrongBalancedLeadLoaded.scheduleAfterReadiness(11L));

        CompoundTag partial = balanced.writeNbt();
        partial.remove("RolledNotBeforeNight");
        RaidLifecycle partialLoaded = RaidLifecycle.readNbt(partial);
        assertTrue(partialLoaded.integrityLost());
        assertFalse(partialLoaded.scheduleAfterReadiness(11L));
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

    @Test
    void recurringRecoveryRoundTripsAndUsesOnlyGameTime() {
        RaidLifecycle lifecycle = completedLifecycle(70L);
        lifecycle.recordRecurringRecovery(1_000L, true);

        assertEquals(25_000L, lifecycle.recurringCooldownUntilGameTime());
        assertTrue(lifecycle.recurringCoolingDown(24_999L));
        assertFalse(lifecycle.recurringCoolingDown(25_000L),
            "a day-time/sleep change cannot alter the persisted game-time deadline");

        RaidLifecycle loaded = RaidLifecycle.readNbt(lifecycle.writeNbt());
        assertFalse(loaded.recurringScheduleBlocked());
        assertEquals(25_000L, loaded.recurringCooldownUntilGameTime());
        assertTrue(loaded.recurringCoolingDown(24_999L));
        assertFalse(loaded.recurringCoolingDown(25_000L));

        loaded.recordRecurringRecovery(9_000L, false);
        assertEquals(57_000L, loaded.recurringCooldownUntilGameTime(),
            "defeat receives the separately persisted 48,000-tick recovery");
    }

    @Test
    void recurringQuietCounterForcesOnePersistedPlanAndResets() {
        RaidLifecycle lifecycle = completedLifecycle(80L);
        assertFalse(lifecycle.recordRecurringQuietEligibleRoll(1L));
        assertFalse(lifecycle.recordRecurringQuietEligibleRoll(24_001L));
        assertTrue(lifecycle.recordRecurringQuietEligibleRoll(48_001L));
        assertEquals(3, lifecycle.recurringQuietEligibleRolls());

        RaidPlan plan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000b04"),
            RaidObjective.KORN, 15.0F, 4L);
        assertTrue(lifecycle.commitRecurringWarning(plan, 48_001L, 3L));
        assertEquals(0, lifecycle.recurringQuietEligibleRolls());
        assertEquals(plan, lifecycle.recurringWarnedPlan().orElseThrow());
        assertFalse(lifecycle.recurringWarningDue(54_000L, 4L));
        assertTrue(lifecycle.recurringWarningDue(54_001L, 4L));

        RaidLifecycle loaded = RaidLifecycle.readNbt(lifecycle.writeNbt());
        assertFalse(loaded.recurringScheduleBlocked());
        assertEquals(plan, loaded.recurringWarnedPlan().orElseThrow());
        assertTrue(loaded.consumeRecurringWarning(plan));
        assertTrue(loaded.recurringWarnedPlan().isEmpty());

        // Warning lead and the plan's own authored night are independent.
        // Reload must not reinterpret a valid long-lead plan as due at the
        // first night after its warning.
        RaidLifecycle futureArrival = completedLifecycle(4L);
        RaidPlan futurePlan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000b08"),
            RaidObjective.KORN, 15.0F, 8L);
        assertTrue(futureArrival.commitRecurringWarning(futurePlan, 1_000L, 3L));
        RaidLifecycle futureReloaded = RaidLifecycle.readNbt(futureArrival.writeNbt());
        assertFalse(futureReloaded.recurringWarningDue(7_000L, 4L),
            "a reloaded night-8 plan must not arrive on night 4 after its lead");
        assertEquals(futurePlan, futureReloaded.recurringWarnedPlan().orElseThrow(),
            "the not-yet-due persisted plan must remain exact");
        assertTrue(futureReloaded.recurringWarningDue(7_000L, 8L),
            "the same persisted plan becomes due at its authored arrival night");
    }

    @Test
    void malformedRecurringScheduleBlocksOnlyFutureScheduling() {
        RaidLifecycle lifecycle = completedLifecycle(90L);
        CompoundTag corrupt = lifecycle.writeNbt();
        corrupt.putInt("RecurringScheduleSchema", 99);

        RaidLifecycle loaded = RaidLifecycle.readNbt(corrupt);
        assertTrue(loaded.recurringScheduleBlocked());
        assertEquals(FirstRaidState.COMPLETED, loaded.firstState(),
            "the old completed first-raid receipt is not rewritten by schedule corruption");
        assertFalse(loaded.mayRollRecurring(1L));
    }

    private static RaidLifecycle completedLifecycle(long night) {
        RaidPlan plan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000b03"),
            RaidObjective.KORN, 0.0F, night);
        RaidLifecycle lifecycle = activeTerminalReady(plan);
        assertTrue(lifecycle.completeFirstRaid(JourneyOutcome.HELD,
            new RaidLogEntry(night, "Fixture Captain", "korn", true,
                0, 0, "rolig")));
        return lifecycle;
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
