package com.hearthstead.settlement;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidParticipantRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class B3EconomyContractsTest {
    @Test void freshDefaultsDisableBannerFoundingCoinsAndKeepSixteenMealReserve() {
        ModConfigSpec.ValueSpec coins = (ModConfigSpec.ValueSpec) HearthsteadServerConfig.SPEC.getSpec().get("start.startCoins");
        assertNotNull(coins);
        assertEquals(0, coins.getDefault(), "owner 27 Sep: the Guildmaster welcome gift replaces the Banner Coins");
        assertTrue(coins.test(5), "server owners can still turn the founding gift back on");
        assertTrue(coins.test(0));
        assertFalse(coins.test(-1));
        assertFalse(coins.test(65));
        assertEquals(16, HearthsteadServerConfig.preFirstRaidMealReserve());
    }

    @Test void smallerReserveSurvivesEveryPreRaidPhaseAndEndsOnActualCompletion() {
        Settlement s = new Settlement(UUID.randomUUID(), "Reserve", BlockPos.ZERO);
        assertEquals(40, RecruitmentPolicy.requiredReserve(s, 5), "unknown legacy state is not a fresh village");
        assertTrue(s.raidLifecycle.prepareAtFounding(0, 4, 2));
        assertEquals(16, RecruitmentPolicy.requiredReserve(s, 5));
        assertEquals(8, RecruitmentPolicy.requiredReserve(s, 1), "cap never raises a smaller real reserve");
        assertTrue(s.raidLifecycle.scheduleAfterReadiness(2));
        s.raidLifecycle = RaidLifecycle.readNbt(s.raidLifecycle.writeNbt());
        assertEquals(16, RecruitmentPolicy.requiredReserve(s, 5));
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0, 4);
        assertTrue(s.raidLifecycle.queueFirstPlan(plan));
        assertTrue(s.raidLifecycle.beginFirstRaid(plan));
        for (int i = 0; i < 4; i++) assertTrue(s.raidLifecycle.recordParticipant(
            new RaidParticipantRecord(UUID.randomUUID(), RaidParticipantRecord.Build.BANDIT, i == 0)));
        assertTrue(s.raidLifecycle.sealParticipants());
        assertEquals(16, RecruitmentPolicy.requiredReserve(s, 5), "active first raid still has founding reserve");
        for (UUID id : s.raidLifecycle.participants()) assertTrue(s.raidLifecycle.recordTerminalParticipant(id));
        assertTrue(s.raidLifecycle.completeFirstRaid(JourneyOutcome.HELD,
            new RaidLogEntry(4, "Captain", "korn", true, 0, 0, "rolig")));
        s.raidLifecycle = RaidLifecycle.readNbt(s.raidLifecycle.writeNbt());
        assertFalse(s.raidLifecycle.integrityLost());
        assertEquals(40, RecruitmentPolicy.requiredReserve(s, 5), "later economy restores two full days");
    }

    @Test void missingOrDamagedRaidAuthorityNeverGrantsAnEarlyReserveDiscount() {
        Settlement s = new Settlement(UUID.randomUUID(), "Damaged", BlockPos.ZERO);
        assertTrue(s.raidLifecycle.prepareAtFounding(0, 4, 2));
        s.raidLifecycle.markIntegrityLost();
        assertEquals(40, RecruitmentPolicy.requiredReserve(s, 5));
        assertEquals(40, RecruitmentPolicy.requiredReserve(null, 5));
        assertEquals(0, RecruitmentPolicy.requiredReserve(s, 0));
        assertEquals(Integer.MAX_VALUE, RecruitmentPolicy.requiredReserve(s, Integer.MAX_VALUE));
    }

    @Test void merchantReloadNeverTreatsAnOldSiteAsAnUnattendedFirstVisit() {
        BlockPos site = new BlockPos(5, 64, 9);
        EarlyCoinMerchant.Receipts receipts = new EarlyCoinMerchant.Receipts();
        assertTrue(EarlyCoinMerchant.visitAllowedByPresence(receipts.hasVisited(site), false));
        assertTrue(receipts.available(site, 100));
        receipts.commit(site, UUID.randomUUID(), 100);
        EarlyCoinMerchant.Receipts loaded = EarlyCoinMerchant.Receipts.load(receipts.save(new CompoundTag(), null), null);
        long next = 100 + EarlyCoinMerchant.PERIOD_TICKS;
        assertTrue(loaded.hasVisited(site));
        assertFalse(loaded.available(site, next - 1));
        assertTrue(loaded.available(site, next));
        assertFalse(EarlyCoinMerchant.visitAllowedByPresence(loaded.hasVisited(site), false),
            "elapsed cadence and reload cannot waive repeat-visit player proximity");
        assertTrue(EarlyCoinMerchant.visitAllowedByPresence(loaded.hasVisited(site), true));
    }

    @Test void malformedMerchantReceiptCannotCombineFirstVisitExceptionWithRestock() {
        EarlyCoinMerchant.Receipts loaded = EarlyCoinMerchant.Receipts.load(new CompoundTag(), null);
        BlockPos site = new BlockPos(5, 64, 9);
        assertFalse(loaded.available(site, Long.MAX_VALUE),
            "even if a damaged ledger has no remembered site, quarantine blocks publication");
    }
}
