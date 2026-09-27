package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.state.RaidLifecycle;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaidSleepPolicyTest {
    @Test
    void warningNightIsSleepableButAttackNightIsNot() {
        Settlement settlement = settlement();
        assertTrue(settlement.raidLifecycle.initializeAtFounding(6L, 4, 1));

        assertFalse(RaidSleepPolicy.blocksSleep(List.of(settlement), 9L));
        assertTrue(RaidSleepPolicy.blocksSleep(List.of(settlement), 10L));
        assertTrue(RaidSleepPolicy.blocksSleep(List.of(settlement), 11L));
    }

    @Test
    void activeFirstRaidBlocksEvenWhenIntegrityIsLost() {
        Settlement settlement = settlement();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD,
            0.0F, 10L);
        assertTrue(settlement.raidLifecycle.initializeAtFounding(6L, 4, 1));
        assertTrue(settlement.raidLifecycle.queueFirstPlan(plan));
        assertTrue(settlement.raidLifecycle.beginFirstRaid(plan));
        settlement.raidLifecycle.markIntegrityLost();

        assertTrue(RaidSleepPolicy.blocksSleep(List.of(settlement), 10L));
    }

    @Test
    void queuedRecurringRaidBlocksTheActivationRace() {
        Settlement settlement = settlement();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN,
            30.0F, 12L);
        assertTrue(settlement.recurringRaidRun.queue(plan));

        assertTrue(RaidSleepPolicy.blocksSleep(List.of(settlement), 12L));
    }

    @Test
    void emptyDimensionKeepsVanillaSleepPolicy() {
        assertFalse(RaidSleepPolicy.blocksSleep(List.of(), 10L));
    }

    private static Settlement settlement() {
        return new Settlement(UUID.randomUUID(), "Sleep Policy",
            BlockPos.ZERO);
    }
}
