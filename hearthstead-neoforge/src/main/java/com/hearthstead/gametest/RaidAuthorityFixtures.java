package com.hearthstead.gametest;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Strict raid-authority setup shared by tests that exercise aftermath. */
final class RaidAuthorityFixtures {

    /** Arms an active recurring run with the exact entity UUID capture. */
    static void armActive(Settlement settlement, RaidPlan plan,
                          Collection<UUID> participants) {
        ensureFirstCompleted(settlement);
        if (!settlement.recurringRaidRun.isEmpty()
            || !settlement.recurringRaidRun.queue(plan)
            || !settlement.recurringRaidRun.sealAndActivate(plan, participants)) {
            throw new IllegalStateException("could not arm strict recurring raid fixture");
        }
        settlement.pendingRaid = plan;
    }

    /** Arms a run whose synthetic participant has definitive terminal proof. */
    static void armTerminal(Settlement settlement, RaidPlan plan) {
        UUID participant = UUID.randomUUID();
        armActive(settlement, plan, List.of(participant));
        if (!settlement.recurringRaidRun.recordTerminalParticipant(participant)) {
            throw new IllegalStateException("could not close strict recurring raid fixture");
        }
    }

    private static void ensureFirstCompleted(Settlement settlement) {
        if (settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED) {
            return;
        }
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan first = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            0.0F, 4L);
        UUID participant = UUID.randomUUID();
        boolean complete = lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(first)
            && lifecycle.beginFirstRaid(first)
            && lifecycle.recordParticipant(participant)
            && lifecycle.sealParticipants()
            && lifecycle.recordTerminalParticipant(participant)
            && lifecycle.completeFirstRaid(false);
        if (!complete || lifecycle.firstState() != FirstRaidState.COMPLETED) {
            throw new IllegalStateException("could not complete first-raid fixture");
        }
        settlement.raidLifecycle = lifecycle;
    }

    private RaidAuthorityFixtures() {
    }
}
