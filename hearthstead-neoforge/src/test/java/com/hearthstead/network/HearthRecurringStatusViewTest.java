package com.hearthstead.network;

import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RecurringRaidRun;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthRecurringStatusViewTest {
    @Test
    void everyBoundedServerStatusRoundTripsThroughItsVersionedCodec() {
        for (HearthMayorSnapshot.RecurringStatusView view : List.of(
                HearthMayorSnapshot.RecurringStatusView.closed(),
                status(HearthMayorSnapshot.RecurringStatusView.Status.RECOVERING,
                    -1L, 24_000L),
                status(HearthMayorSnapshot.RecurringStatusView.Status.WARNED,
                    11L, 0L),
                status(HearthMayorSnapshot.RecurringStatusView.Status.QUEUED,
                    12L, 0L),
                status(HearthMayorSnapshot.RecurringStatusView.Status.ACTIVE,
                    13L, 0L),
                status(HearthMayorSnapshot.RecurringStatusView.Status.BLOCKED,
                    -1L, 0L))) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                HearthMayorSnapshot.RecurringStatusView.CODEC.encode(buffer, view);
                assertEquals(view,
                    HearthMayorSnapshot.RecurringStatusView.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
        assertEquals(1, HearthMayorSnapshot.RecurringStatusView.WIRE_VERSION);
    }

    @Test
    void mayorSnapshotRoundTripKeepsTheRecurringSlotBeforeAftermath() {
        HearthMayorSnapshot.RecurringStatusView active = status(
            HearthMayorSnapshot.RecurringStatusView.Status.ACTIVE, 18L, 0L);
        HearthMayorSnapshot snapshot = new HearthMayorSnapshot(7, false,
            HearthMayorAction.NO_ID, "", "", 0L, false, 0L, List.of(),
            List.of(new HearthMayorSnapshot.Resident(
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000007"),
                73, "Loaded", "FARMER", "work_farm", true)), 1, false,
            HearthMayorSnapshot.RecruitmentCard.empty(),
            HearthMayorSnapshot.RequestView.closed(),
            HearthMayorSnapshot.ReadinessView.closed(), active,
            HearthMayorSnapshot.AftermathView.closed());
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            HearthMayorSnapshot.CODEC.encode(buffer, snapshot);
            assertEquals(snapshot, HearthMayorSnapshot.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void peopleRequestWireIdIsExplicitAndUnknownIdsStayInert() {
        assertEquals(HearthMayorAction.Kind.OPEN_PEOPLE,
            HearthMayorAction.Kind.fromWireId(10));
        assertEquals(HearthMayorAction.Kind.VIEW_SETTLER,
            HearthMayorAction.Kind.fromWireId(11));
        assertEquals(HearthMayorAction.Kind.UNKNOWN,
            HearthMayorAction.Kind.fromWireId(12));
    }

    @Test
    void malformedTimingAndUnknownStatusFailClosedAtThePacketBoundary() {
        assertThrows(IllegalArgumentException.class,
            () -> status(HearthMayorSnapshot.RecurringStatusView.Status.RECOVERING,
                4L, 10L));
        assertThrows(IllegalArgumentException.class,
            () -> status(HearthMayorSnapshot.RecurringStatusView.Status.WARNED,
                4L, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.RecurringStatusView(99, -1L, 0L));
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(99);
            assertThrows(IllegalArgumentException.class,
                () -> HearthMayorSnapshot.RecurringStatusView.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void projectionUsesLifecycleAndRunAuthorityWithServerMeasuredCooldown() {
        RaidLifecycle lifecycle = completedLifecycle(4L);
        RecurringRaidRun run = new RecurringRaidRun();
        assertEquals(HearthMayorSnapshot.RecurringStatusView.Status.NONE,
            HearthNetwork.recurringStatusFor(lifecycle, run, 50L).status());

        lifecycle.recordRecurringRecovery(100L, true);
        HearthMayorSnapshot.RecurringStatusView recovering =
            HearthNetwork.recurringStatusFor(lifecycle, run, 250L);
        assertEquals(HearthMayorSnapshot.RecurringStatusView.Status.RECOVERING,
            recovering.status());
        assertEquals(23_850L, recovering.cooldownRemainingTicks(),
            "the transmitted value is measured on the server tick passed to the projection");

        RaidLifecycle warnedLifecycle = completedLifecycle(4L);
        RaidPlan warnedPlan = plan(12L);
        assertTrue(warnedLifecycle.commitRecurringWarning(warnedPlan, 50L, 4L));
        assertEquals(HearthMayorSnapshot.RecurringStatusView.Status.WARNED,
            HearthNetwork.recurringStatusFor(warnedLifecycle,
                new RecurringRaidRun(), 51L).status());

        RecurringRaidRun queued = new RecurringRaidRun();
        assertTrue(queued.queue(plan(13L)));
        assertEquals(HearthMayorSnapshot.RecurringStatusView.Status.QUEUED,
            HearthNetwork.recurringStatusFor(completedLifecycle(4L), queued,
                51L).status());

        RecurringRaidRun active = new RecurringRaidRun();
        RaidPlan activePlan = plan(14L);
        assertTrue(active.queue(activePlan)
            && active.sealAndActivate(activePlan, List.of(UUID.randomUUID())));
        assertEquals(HearthMayorSnapshot.RecurringStatusView.Status.ACTIVE,
            HearthNetwork.recurringStatusFor(completedLifecycle(4L), active,
                51L).status());

        RaidLifecycle blocked = completedLifecycle(4L);
        blocked.blockRecurringSchedule();
        assertEquals(HearthMayorSnapshot.RecurringStatusView.Status.BLOCKED,
            HearthNetwork.recurringStatusFor(blocked, new RecurringRaidRun(),
                51L).status());
    }

    private static HearthMayorSnapshot.RecurringStatusView status(
            HearthMayorSnapshot.RecurringStatusView.Status status,
            long night, long cooldown) {
        return new HearthMayorSnapshot.RecurringStatusView(status.wireId(),
            night, cooldown);
    }

    private static RaidLifecycle completedLifecycle(long night) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = plan(night);
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

    private static RaidPlan plan(long night) {
        return new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, night);
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
