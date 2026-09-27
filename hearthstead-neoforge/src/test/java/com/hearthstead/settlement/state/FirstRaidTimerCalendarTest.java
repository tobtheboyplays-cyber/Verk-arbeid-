package com.hearthstead.settlement.state;

import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FirstRaidTimerCalendarTest {
    private RaidLifecycle preparing() {
        RaidLifecycle state = new RaidLifecycle();
        assertTrue(state.prepareAtFounding(10L, 2, 1, RaidProfile.BALANCED));
        return state;
    }

    @Test void timerWaitsForWarningDayAndDoesNotClaimPlayerReadiness() {
        RaidLifecycle state = preparing();
        assertFalse(state.scheduleAfterTimer(11, 3, 3));
        assertTrue(state.scheduleAfterTimer(12, 3, 3));
        assertEquals(12, state.firstWarningNight());
        assertEquals(13, state.firstAttackNight());
        assertEquals(-1, state.readinessNight());
        assertFalse(state.scheduleAfterReadiness(12));
        assertFalse(state.scheduleAfterTimer(100, 1, 6));
        RaidLifecycle loaded = RaidLifecycle.readNbt(state.writeNbt());
        assertTrue(loaded.isTimerScheduled());
        assertFalse(loaded.integrityLost());
        assertEquals(3, loaded.firstTimerBandSize());
        assertEquals(13, loaded.firstAttackNight());
    }

    @Test void delayedWarningKeepsAFullNightAndTheSameCaptain() {
        RaidLifecycle state = preparing();
        assertTrue(state.scheduleAfterTimer(20, 3, 3));
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, 90, 21);
        assertTrue(state.queueFirstPlan(plan));
        assertFalse(state.beginFirstRaid(plan));
        assertTrue(state.deferTimerWarning(23));
        RaidPlan deferred = state.queuedPlan().orElseThrow();
        assertEquals(plan.captainId(), deferred.captainId());
        assertEquals(plan.objective(), deferred.objective());
        assertEquals(24, deferred.night());
        assertTrue(state.recordTimerWarningPresented());
        assertFalse(state.deferTimerWarning(25));
        assertTrue(RaidLifecycle.readNbt(state.writeNbt()).timerWarningPresented());
    }

    @Test void missingOrCorruptProvenanceCannotBecomeAnEarlyDeclaration() {
        RaidLifecycle state = preparing();
        assertTrue(state.scheduleAfterTimer(12, 3, 3));
        CompoundTag missing = state.writeNbt();
        missing.remove("FirstRaidTimer");
        assertTrue(RaidLifecycle.readNbt(missing).integrityLost());
        for (String key : new String[]{"Days", "BandSize", "CommittedNight", "WarningPresented",
                "SunsetPresented", "RetreatAtGameTime", "Schema"}) {
            CompoundTag damaged = state.writeNbt();
            damaged.getCompound("FirstRaidTimer").remove(key);
            assertTrue(RaidLifecycle.readNbt(damaged).integrityLost(), key);
        }
        CompoundTag oversized = state.writeNbt();
        oversized.getCompound("FirstRaidTimer").putInt("BandSize", 9);
        assertTrue(RaidLifecycle.readNbt(oversized).integrityLost());
    }

    @Test void playerCalendarRemainsUnchangedAndCannotBeReplacedByTimer() {
        RaidLifecycle state = preparing();
        assertTrue(state.scheduleAfterReadiness(10));
        assertEquals(12, state.firstAttackNight());
        assertFalse(state.scheduleAfterTimer(12, 3, 3));
        RaidLifecycle loaded = RaidLifecycle.readNbt(state.writeNbt());
        assertFalse(loaded.integrityLost());
        assertFalse(loaded.isTimerScheduled());
        assertEquals(10, loaded.readinessNight());
    }

    @Test void timerRosterAndBudgetSurviveReloadAndCannotGrow() {
        RaidLifecycle state = preparing();
        assertTrue(state.scheduleAfterTimer(12, 3, 3));
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, 90, 13);
        assertTrue(state.queueFirstPlan(plan));
        assertTrue(state.recordTimerWarningPresented());
        assertTrue(state.recordTimerSunsetPresented());
        assertTrue(state.beginFirstRaid(plan));
        state.armFirstTimerRetreat(100);
        for (int i = 0; i < 3; i++) {
            assertTrue(state.recordParticipant(new RaidParticipantRecord(UUID.randomUUID(),
                RaidParticipantRecord.Build.BANDIT, i == 0)));
        }
        assertTrue(state.sealParticipants());
        RaidLifecycle loaded = RaidLifecycle.readNbt(state.writeNbt());
        assertFalse(loaded.integrityLost());
        assertTrue(loaded.participantRosterTracked());
        assertFalse(loaded.firstTimerRetreatDue(12099));
        assertTrue(loaded.firstTimerRetreatDue(12100));
        loaded.armFirstTimerRetreat(99999);
        assertTrue(loaded.firstTimerRetreatDue(12100));
        CompoundTag corrupted = state.writeNbt();
        corrupted.getCompound("FirstRaidTimer").putInt("BandSize", 4);
        assertTrue(RaidLifecycle.readNbt(corrupted).integrityLost());
    }
}
