package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RecurringRaidRun;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidDirectorRecurringScheduleTest {
    @Test
    void absentPlayerDoesNotCreateAnOfflineBacklog() {
        RaidLifecycle lifecycle = completedLifecycle(4L);
        RecurringRaidRun run = new RecurringRaidRun();

        assertFalse(RaidDirector.mayRunRecurringSchedule(true, false,
            lifecycle, run, 13_000L));
        assertTrue(RaidDirector.mayRunRecurringSchedule(true, true,
            lifecycle, run, 13_000L),
            "a later player return merely permits that night’s one normal roll");
    }

    @Test
    void recoveryGatePrecedesOmenPressureAndQuietRollsAcrossReload() {
        RecurringRaidRun run = new RecurringRaidRun();
        RaidPressure pressure = new RaidPressure();
        RaidLifecycle held = completedLifecycle(4L);
        held.recordRecurringRecovery(1_000L, true);
        RaidLifecycle heldReloaded = RaidLifecycle.readNbt(held.writeNbt());

        assertFalse(RaidDirector.mayRunRecurringSchedule(true, true,
            heldReloaded, run, 24_999L),
            "HELD recovery must stop the director before its omen or roll path");
        assertEquals(0, heldReloaded.recurringQuietEligibleRolls());
        assertEquals(0, pressure.pressure(),
            "the blocked director gate cannot consume quiet-night pressure");
        assertTrue(RaidDirector.mayRunRecurringSchedule(true, true,
            heldReloaded, run, 25_000L),
            "the exact persisted HELD boundary may resume ordinary scheduling");

        RaidLifecycle defeated = completedLifecycle(5L);
        defeated.recordRecurringRecovery(1_000L, false);
        RaidLifecycle defeatReloaded = RaidLifecycle.readNbt(defeated.writeNbt());

        assertFalse(RaidDirector.mayRunRecurringSchedule(true, true,
            defeatReloaded, run, 48_999L),
            "defeat recovery remains closed through its final game-time tick");
        assertEquals(0, defeatReloaded.recurringQuietEligibleRolls());
        assertEquals(0, pressure.pressure());
        assertTrue(RaidDirector.mayRunRecurringSchedule(true, true,
            defeatReloaded, run, 49_000L),
            "the exact persisted defeat boundary may resume ordinary scheduling");
    }

    @Test
    void activeRosterKeepsItsExistingOwner() {
        RaidLifecycle lifecycle = completedLifecycle(4L);
        RecurringRaidRun active = new RecurringRaidRun();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        assertTrue(active.queue(plan)
                && active.sealAndActivate(plan, List.of(UUID.randomUUID())),
            "the existing run owns this plan and participant before scheduling");

        assertFalse(RaidDirector.mayRunRecurringSchedule(true, true,
            lifecycle, active, 13_000L));
    }

    private static RaidLifecycle completedLifecycle(long night) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, night);
        UUID participant = UUID.randomUUID();
        assertTrue(lifecycle.initializeAtFounding(night - 4L, 4, 2)
                && lifecycle.queueFirstPlan(plan)
                && lifecycle.beginFirstRaid(plan)
                && lifecycle.recordParticipant(participant)
                && lifecycle.sealParticipants()
                && lifecycle.recordTerminalParticipant(participant)
                && lifecycle.completeFirstRaid(JourneyOutcome.HELD,
                    new RaidLogEntry(night, "Fixture Captain", "korn", true,
                        0, 0, "rolig")));
        return lifecycle;
    }
}
