package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 26 Sep raid robustness: sleep distance, first-raid retreat ledger and the info line. */
class RaidRobustnessTest {
    private static final long DAY = 24_000L;

    @Test
    void sleepDenialUsesThePlayerPresenceDistance() {
        assertEquals(32, RaidDirector.PLAYER_PRESENCE_MARGIN);
        assertEquals(RaidDirector.PLAYER_PRESENCE_MARGIN, RaidSleepPolicy.SLEEP_DENIAL_MARGIN);
        Settlement settlement = settlement();
        settlement.radius = 10;
        assertTrue(settlement.raidLifecycle.initializeAtFounding(6L, 4, 1));
        assertTrue(RaidSleepPolicy.blocksSleep(List.of(settlement), 10L, new BlockPos(42, 64, 0)),
            "radius + 32 is still inside");
        assertFalse(RaidSleepPolicy.blocksSleep(List.of(settlement), 10L, new BlockPos(43, 64, 0)),
            "a player who cannot start or hear the raid may sleep");
    }

    @Test
    void firstRaidRetreatClosesTheLedgerWithoutReward() {
        Settlement settlement = settlement();
        RaidPlan plan = activeFirst(settlement);
        UUID fallen = UUID.randomUUID();
        UUID stuck = UUID.randomUUID();
        assertTrue(settlement.raidLifecycle.recordParticipant(fallen));
        assertTrue(settlement.raidLifecycle.recordParticipant(stuck));
        assertTrue(settlement.raidLifecycle.sealParticipants());
        assertTrue(settlement.raidLifecycle.recordTerminalParticipant(fallen));
        assertFalse(settlement.raidLifecycle.allParticipantsTerminal());

        assertEquals(List.of(stuck), settlement.raidLifecycle.retreatFirstRaid());
        assertTrue(settlement.raidLifecycle.allParticipantsTerminal());
        assertFalse(settlement.raidLifecycle.integrityLost(), "a sealed retreat is not damage");
        assertTrue(settlement.raidLifecycle.activePlanMatches(plan));
    }

    @Test
    void firstRaidRetreatClosesAnUnsealedCaptureAsDamaged() {
        Settlement settlement = settlement();
        activeFirst(settlement);
        UUID only = UUID.randomUUID();
        assertTrue(settlement.raidLifecycle.recordParticipant(only));
        settlement.raidLifecycle.markIntegrityLost();

        assertEquals(List.of(only), settlement.raidLifecycle.retreatFirstRaid());
        assertTrue(settlement.raidLifecycle.allParticipantsTerminal()
            && settlement.raidLifecycle.integrityLost());
        assertTrue(settlement.raidLifecycle.completeFirstRaid(true));
        assertFalse(settlement.raidLifecycle.mayGrantReward());
    }

    @Test
    void retreatNeedsAnActiveFirstRaidWithKnownParticipants() {
        Settlement settlement = settlement();
        assertNull(settlement.raidLifecycle.retreatFirstRaid());
        activeFirst(settlement);
        assertNull(settlement.raidLifecycle.retreatFirstRaid(), "no captured participant yet");
    }

    @Test
    void armedFirstRaidRetreatFallsOnTheNextDawn() {
        Settlement settlement = settlement();
        long dusk = 10L * DAY + 13_000L;
        settlement.raidLifecycle.armRecurringRetreat(dusk);
        assertEquals(11L * DAY, settlement.raidLifecycle.recurringRetreatAtDayTime());
        assertFalse(settlement.raidLifecycle.recurringRetreatDue(11L * DAY - 1L));
        assertTrue(settlement.raidLifecycle.recurringRetreatDue(11L * DAY));
    }

    @Test
    void infoBeforeDeclareReadySaysNoRaidIsComing() {
        Settlement fresh = settlement();
        assertKey("hearthstead.command.info_threat_preparing", RaidThreatInfo.line(fresh, 0L));
        Settlement preparing = settlement();
        assertTrue(preparing.raidLifecycle.prepareAtFounding(0L, 4, 1));
        assertEquals(FirstRaidState.PREPARING, preparing.raidLifecycle.firstState());
        Component line = RaidThreatInfo.line(preparing, 5L * DAY);
        assertKey("hearthstead.command.info_threat_preparing", line);
        assertEquals(0, ((TranslatableContents) line.getContents()).getArgs().length,
            "no chance or nights-since-raid numbers before the first raid");
    }

    @Test
    void infoCountsDownToTheScheduledFirstRaid() {
        Settlement settlement = settlement();
        assertTrue(settlement.raidLifecycle.initializeAtFounding(6L, 4, 1)); // attack night 10
        Component eight = RaidThreatInfo.line(settlement, 8L * DAY + 100L);
        assertKey("hearthstead.command.info_threat_first_scheduled", eight);
        Component when = (Component) ((TranslatableContents) eight.getContents()).getArgs()[0];
        assertKey("hearthstead.command.when.nights", when);
        assertArrayEquals(new Object[] {2L}, ((TranslatableContents) when.getContents()).getArgs());
        assertKey("hearthstead.command.when.tomorrow", (Component) ((TranslatableContents)
            RaidThreatInfo.line(settlement, 9L * DAY).getContents()).getArgs()[0]);
        assertKey("hearthstead.command.when.tonight", (Component) ((TranslatableContents)
            RaidThreatInfo.line(settlement, 10L * DAY + 20_000L).getContents()).getArgs()[0]);
        assertKey("hearthstead.command.info_threat_first_held",
            RaidThreatInfo.line(settlement, 11L * DAY));
    }

    @Test
    void infoAfterTheFirstRaidReadsTheCadence() {
        Settlement settlement = settlement();
        activeFirst(settlement);
        UUID raider = UUID.randomUUID();
        assertTrue(settlement.raidLifecycle.recordParticipant(raider));
        assertKey("hearthstead.command.info_threat_active", RaidThreatInfo.line(settlement, 4L * DAY));
        assertTrue(settlement.raidLifecycle.sealParticipants()
            && settlement.raidLifecycle.recordTerminalParticipant(raider)
            && settlement.raidLifecycle.completeFirstRaid(true));
        settlement.raidLifecycle.recordRecurringCadence(1_000L, 5L * DAY + 500L,
            com.hearthstead.settlement.state.RaidLifecycle.CadenceOutcome.HELD, 3);
        long next = settlement.raidLifecycle.recurringNextAttackNight();
        assertTrue(next > 5L, "cadence sets the next attack night: " + next);
        Component line = RaidThreatInfo.line(settlement, 5L * DAY + 600L);
        assertKey("hearthstead.command.info_threat_recurring", line);
        Object[] args = ((TranslatableContents) line.getContents()).getArgs();
        assertEquals(4, args.length);
        assertEquals(1, args[2], "one raid held");
        settlement.recurringRaidRun.block();
        assertKey("hearthstead.command.info_threat_paused", RaidThreatInfo.line(settlement, 6L * DAY));
    }

    private static RaidPlan activeFirst(Settlement settlement) {
        assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2));
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        assertTrue(settlement.raidLifecycle.queueFirstPlan(plan)
            && settlement.raidLifecycle.beginFirstRaid(plan));
        return plan;
    }

    private static void assertKey(String key, Component component) {
        assertTrue(component.getContents() instanceof TranslatableContents,
            "expected a translatable line: " + component);
        assertEquals(key, ((TranslatableContents) component.getContents()).getKey());
    }

    private static Settlement settlement() {
        return new Settlement(UUID.randomUUID(), "Raid Robustness", BlockPos.ZERO);
    }
}
