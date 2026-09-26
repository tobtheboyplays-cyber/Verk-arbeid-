package com.hearthstead.settlement.state;

import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidParticipantRosterTest {
    @Test
    void exactRosterAndTerminalTruthRoundTripTogether() {
        RaidLifecycle lifecycle = activeLifecycle();
        List<RaidParticipantRecord> roster = exactFirstRoster();
        for (RaidParticipantRecord record : roster) {
            assertTrue(lifecycle.recordParticipant(record));
        }
        assertTrue(lifecycle.sealParticipants());
        assertTrue(lifecycle.participantRosterTracked());
        assertTrue(lifecycle.recordTerminalParticipant(roster.get(1).entityId()));

        RaidLifecycle loaded = RaidLifecycle.readNbt(lifecycle.writeNbt());

        assertFalse(loaded.integrityLost());
        assertTrue(loaded.participantsTracked());
        assertTrue(loaded.participantRosterTracked());
        assertEquals(roster, loaded.participantRoster());
        assertEquals(Set.of(roster.get(1).entityId()),
            loaded.terminalParticipants());
    }

    @Test
    void uuidOnlyActiveSaveMigratesWithoutInventingRoles() {
        RaidLifecycle lifecycle = activeLifecycle();
        UUID participant = UUID.fromString(
            "00000000-0000-0000-0000-000000000701");
        assertTrue(lifecycle.recordParticipant(participant));
        assertTrue(lifecycle.sealParticipants());
        CompoundTag legacy = lifecycle.writeNbt();
        legacy.remove("ParticipantRosterSchema");
        legacy.remove("ParticipantRosterTracked");
        legacy.remove("ParticipantRoster");

        RaidLifecycle loaded = RaidLifecycle.readNbt(legacy);

        assertFalse(loaded.integrityLost());
        assertTrue(loaded.participantsTracked());
        assertFalse(loaded.participantRosterTracked());
        assertTrue(loaded.participantRoster().isEmpty());
        assertEquals(Set.of(participant), loaded.participants());

        RaidLifecycle loadedAgain = RaidLifecycle.readNbt(loaded.writeNbt());
        assertFalse(loadedAgain.integrityLost());
        assertTrue(loadedAgain.participantsTracked());
        assertFalse(loadedAgain.participantRosterTracked());
        assertEquals(Set.of(participant), loadedAgain.participants());
    }

    @Test
    void partialRosterSchemaFailsClosed() {
        RaidLifecycle lifecycle = activeLifecycle();
        UUID participant = UUID.fromString(
            "00000000-0000-0000-0000-000000000702");
        assertTrue(lifecycle.recordParticipant(participant));
        assertTrue(lifecycle.sealParticipants());
        CompoundTag damaged = lifecycle.writeNbt();
        damaged.remove("ParticipantRoster");

        RaidLifecycle loaded = RaidLifecycle.readNbt(damaged);

        assertTrue(loaded.integrityLost());
        assertFalse(loaded.participantRosterTracked());
        assertTrue(loaded.participantRoster().isEmpty());
    }

    @Test
    void upgradedScheduledSavePromotesOnlyWhenNewRaidBegins() {
        RaidLifecycle scheduled = new RaidLifecycle();
        assertTrue(scheduled.initializeAtFounding(6L, 4, 1));
        CompoundTag oldScheduled = scheduled.writeNbt();
        oldScheduled.remove("ParticipantRosterSchema");
        oldScheduled.remove("ParticipantRosterTracked");
        oldScheduled.remove("ParticipantRoster");
        RaidLifecycle loaded = RaidLifecycle.readNbt(oldScheduled);
        assertFalse(loaded.integrityLost());

        RaidPlan plan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000705"),
            RaidObjective.KORN, 15.0F, 10L);
        assertTrue(loaded.queueFirstPlan(plan));
        assertTrue(loaded.beginFirstRaid(plan));
        List<RaidParticipantRecord> roster = exactFirstRoster();
        for (RaidParticipantRecord record : roster) {
            assertTrue(loaded.recordParticipant(record));
        }
        assertTrue(loaded.sealParticipants());
        for (RaidParticipantRecord record : roster) {
            assertTrue(loaded.recordTerminalParticipant(record.entityId()));
        }
        assertTrue(loaded.completeFirstRaid(JourneyOutcome.HELD,
            new RaidLogEntry(plan.night(), "Upgrade Captain", "korn", true,
                0, 0, "rolig")));

        RaidLifecycle restarted = RaidLifecycle.readNbt(loaded.writeNbt());
        assertFalse(restarted.integrityLost());
        assertTrue(restarted.participantRosterTracked());
        assertEquals(roster, restarted.participantRoster());
        assertTrue(restarted.mayGrantReward());
    }

    @Test
    void multipleCaptainsCannotSealPresentationAuthority() {
        RaidLifecycle lifecycle = activeLifecycle();
        assertTrue(lifecycle.recordParticipant(new RaidParticipantRecord(
            UUID.fromString("00000000-0000-0000-0000-000000000703"),
            RaidParticipantRecord.Build.SKIRMISHER, true)));
        assertTrue(lifecycle.recordParticipant(new RaidParticipantRecord(
            UUID.fromString("00000000-0000-0000-0000-000000000704"),
            RaidParticipantRecord.Build.BRUTE, true)));

        assertFalse(lifecycle.sealParticipants());
        assertTrue(lifecycle.integrityLost());
        assertFalse(lifecycle.participantRosterTracked());
    }

    private static RaidLifecycle activeLifecycle() {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = new RaidPlan(UUID.fromString(
            "00000000-0000-0000-0000-000000000700"),
            RaidObjective.KORN, 15.0F, 10L);
        assertTrue(lifecycle.initializeAtFounding(6L, 4, 1));
        assertTrue(lifecycle.queueFirstPlan(plan));
        assertTrue(lifecycle.beginFirstRaid(plan));
        return lifecycle;
    }

    private static List<RaidParticipantRecord> exactFirstRoster() {
        return List.of(
            record(710, RaidParticipantRecord.Build.SKIRMISHER, true),
            record(711, RaidParticipantRecord.Build.BRUTE, false),
            record(712, RaidParticipantRecord.Build.SKIRMISHER, false),
            record(713, RaidParticipantRecord.Build.SKIRMISHER, false),
            record(714, RaidParticipantRecord.Build.SKIRMISHER, false));
    }

    private static RaidParticipantRecord record(long suffix,
                                                RaidParticipantRecord.Build build,
                                                boolean captain) {
        return new RaidParticipantRecord(new UUID(0L, suffix), build, captain);
    }
}
