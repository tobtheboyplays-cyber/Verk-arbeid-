package com.hearthstead.settlement.raid;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaidCoinRewardsTest {
    @Test void firstVictoryIsEightAndRecurringVictoriesAreFourExactlyOnce() {
        RaidCoinRewards rewards = new RaidCoinRewards();

        assertTrue(rewards.awardFirst());
        assertEquals(8, rewards.pending());
        assertTrue(rewards.awardFirst());
        assertEquals(8, rewards.pending());

        assertTrue(rewards.awardRecurring(1L));
        assertEquals(12, rewards.pending());
        assertTrue(rewards.awardRecurring(1L));
        assertEquals(12, rewards.pending());
        assertTrue(rewards.awardRecurring(2L));
        assertEquals(16, rewards.pending());
    }

    @Test void legacyPendingDebtKeepsItsExactPhysicalAmount() {
        CompoundTag storedReward = new CompoundTag();
        storedReward.putInt("Version", 1);
        storedReward.putBoolean("FirstAwarded", true);
        storedReward.putLong("LastRecurringSerial", 3L);
        storedReward.putInt("Pending", 8);
        storedReward.putBoolean("Quarantined", false);
        CompoundTag settlement = new CompoundTag();
        settlement.put("RaidCoinRewards", storedReward);

        RaidCoinRewards loaded = RaidCoinRewards.readNbt(settlement);
        assertEquals(8, loaded.pending());
        assertTrue(loaded.awardRecurring(4L));
        assertEquals(12, loaded.pending());
    }
}
