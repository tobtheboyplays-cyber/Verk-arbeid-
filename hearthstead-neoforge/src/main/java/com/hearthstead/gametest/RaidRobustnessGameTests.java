package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.raid.RaidSleepPolicy;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Raid soft-lock guards added 26 Sep after a QA code reading: the authored
 * first raid retreats at dawn like recurring raids, and a quarantined raid
 * no longer leaves its PendingRaid mirror (civilians hiding) behind.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidRobustnessGameTests {
    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement registeredSettlement(GameTestHelper helper, String name) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name,
            helper.absolutePos(new BlockPos(5, 1, 5)));
        settlement.radius = 10;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static void forget(GameTestHelper helper, Settlement settlement) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.remove(settlement.id);
        data.setDirty();
    }

    private static RaidPlan activeFirstRaid(GameTestHelper helper, Settlement settlement) {
        helper.assertTrue(settlement.raidLifecycle.initializeAtFounding(0L, 4, 2),
            "first raid fixture should initialize");
        RaidCaptain captain = RaidDirector.pickCaptain(settlement, helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.KORN, 0.0F, 4L);
        helper.assertTrue(settlement.raidLifecycle.queueFirstPlan(plan)
                && settlement.raidLifecycle.beginFirstRaid(plan),
            "first raid fixture should activate the exact queued plan");
        settlement.pendingRaid = plan;
        return plan;
    }

    private static RaiderEntity participant(GameTestHelper helper, Settlement settlement,
                                            RaidPlan plan, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(plan.captainId(), settlement.id, plan.objective(), 1.0F, false);
        helper.assertTrue(settlement.raidLifecycle.recordParticipant(raider.getUUID()),
            "actual raider UUID should enter the first-raid capture");
        return raider;
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_robust_first_dawn_retreat")
    public void firstRaidWithAStuckRaiderRetreatsAtDawnWithoutReward(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement settlement = registeredSettlement(helper, "Stuckvik");
        try {
            RaidPlan plan = activeFirstRaid(helper, settlement);
            RaiderEntity fallen = participant(helper, settlement, plan, new BlockPos(3, 1, 5));
            RaiderEntity stuck = participant(helper, settlement, plan, new BlockPos(8, 1, 5));
            helper.assertTrue(settlement.raidLifecycle.sealParticipants(), "two raiders should seal");
            helper.assertTrue(fallen.hurt(helper.getLevel().damageSources().genericKill(),
                    fallen.getMaxHealth() + 100.0F) && !fallen.isAlive(),
                "one raider falls in the fight");
            int pressureBefore = settlement.raidPressure.pressure();

            // A raid that was already live gets its retreat armed lazily.
            helper.assertTrue(settlement.raidLifecycle.recurringRetreatAtDayTime() < 0L,
                "the fixture raid starts without an armed retreat");
            RaidDirector.tick(helper.getLevel(), settlement);
            long retreatAt = settlement.raidLifecycle.recurringRetreatAtDayTime();
            helper.assertTrue(retreatAt > helper.getLevel().getDayTime()
                    && settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE
                    && !stuck.isRemoved() && settlement.pendingRaid != null,
                "before dawn the first raid stays live, retreat armed for a dawn: " + retreatAt);
            helper.assertTrue(RaidSleepPolicy.blocksSleep(List.of(settlement),
                    RaidDirector.nightOf(helper.getLevel().getDayTime())),
                "a live first raid still denies sleep");

            helper.getLevel().setDayTime(retreatAt);
            RaidDirector.tick(helper.getLevel(), settlement);
            helper.assertTrue(settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                    && settlement.pendingRaid == null && stuck.isRemoved()
                    && settlement.raidLifecycle.isRetreatedRaider(stuck.getUUID())
                    && !settlement.raidLifecycle.isRetreatedRaider(fallen.getUUID()),
                "dawn must close the first raid, remove the stuck raider and remember it");
            helper.assertTrue(!settlement.raidLifecycle.mayGrantReward()
                    && settlement.raidCoinRewards.pending() == 0
                    && settlement.blessingState.earned() == 0
                    && settlement.raidPressure.pressure() == pressureBefore
                    && settlement.raidLifecycle.raidsSurvived() == 0,
                "a retreated first raid pays no Coins or Blessing and changes no pressure");
            helper.assertTrue(settlement.raidLog.size() == 1
                    && settlement.raidLifecycle.recurringRetreatAtDayTime() < 0L
                    && settlement.raidLifecycle.recurringNextAttackNight() >= 0L,
                "the aftermath is logged once and the recurring cadence starts");
            helper.assertTrue(!RaidSleepPolicy.blocksSleep(List.of(settlement),
                    RaidDirector.nightOf(helper.getLevel().getDayTime())),
                "after the retreat players may sleep again");

            Settlement loaded = Settlement.readNbt(settlement.writeNbt(),
                SettlementSavedData.CURRENT_DATA_VERSION);
            helper.assertTrue(loaded.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                    && !loaded.raidLifecycle.mayGrantReward()
                    && loaded.raidCoinRewards.pending() == 0
                    && loaded.raidLifecycle.isRetreatedRaider(stuck.getUUID()),
                "reload keeps the retreat reward-free and the straggler remembered");
        } finally {
            for (RaiderEntity raider : RaidDirector.livingRaidersOf(helper.getLevel(), settlement)) {
                raider.discard();
            }
            forget(helper, settlement);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raid_robust_first_unsealed_retreat")
    public void unsealedFirstRaidCaptureStillClosesAtDawn(GameTestHelper helper) {
        buildArena(helper, 12);
        Settlement settlement = registeredSettlement(helper, "Halvfangst");
        try {
            RaidPlan plan = activeFirstRaid(helper, settlement);
            RaiderEntity raider = participant(helper, settlement, plan, new BlockPos(6, 1, 6));
            // The capture failed before sealing: before 26 Sep this ACTIVE raid
            // could never close and denied sleep forever.
            settlement.raidLifecycle.markIntegrityLost();
            helper.assertTrue(!settlement.raidLifecycle.participantsTracked()
                    && !settlement.raidLifecycle.allParticipantsTerminal(),
                "fixture: an unsealed, integrity-lost first raid");
            helper.assertTrue(RaidDirector.resolveFirstRaidRetreat(helper.getLevel(), settlement),
                "the dawn retreat must close even an unsealed capture");
            helper.assertTrue(settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                    && raider.isRemoved() && settlement.pendingRaid == null
                    && !settlement.raidLifecycle.mayGrantReward()
                    && settlement.raidCoinRewards.pending() == 0
                    && settlement.blessingState.earned() == 0,
                "the damaged raid closes without any reward");
        } finally {
            for (RaiderEntity raider : RaidDirector.livingRaidersOf(helper.getLevel(), settlement)) {
                raider.discard();
            }
            forget(helper, settlement);
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raid_robust_quarantine_clears_mirror")
    public void quarantinedRaidDropsItsPendingRaidMirror(GameTestHelper helper) {
        Settlement completed = registeredSettlement(helper, "Karantene");
        Settlement damaged = registeredSettlement(helper, "Skadevik");
        try {
            // Completed first raid, then a recurring run quarantined while its
            // compatibility mirror was live.
            RaidPlan first = activeFirstRaid(helper, completed);
            UUID ghost = UUID.randomUUID();
            helper.assertTrue(completed.raidLifecycle.recordParticipant(ghost)
                    && completed.raidLifecycle.sealParticipants()
                    && completed.raidLifecycle.recordTerminalParticipant(ghost)
                    && completed.raidLifecycle.completeFirstRaid(true),
                "fixture: a completed first raid");
            RaidPlan recurringPlan = new RaidPlan(first.captainId(), RaidObjective.BLOD, 0.0F, 9L);
            completed.recurringRaidRun.block();
            completed.pendingRaid = recurringPlan;
            RaidDirector.tick(helper.getLevel(), completed);
            helper.assertTrue(completed.pendingRaid == null
                    && completed.recurringRaidRun.isBlocked(),
                "a blocked recurring run must not keep its PendingRaid mirror");

            // A damaged, never-started first schedule with a stray mirror.
            helper.assertTrue(damaged.raidLifecycle.initializeAtFounding(0L, 4, 2),
                "fixture: scheduled first raid");
            damaged.raidLifecycle.markIntegrityLost();
            damaged.pendingRaid = recurringPlan;
            RaidDirector.tick(helper.getLevel(), damaged);
            helper.assertTrue(damaged.pendingRaid == null,
                "damaged first-raid state must stop without leaving civilians hiding");

            // A live authored first raid keeps its mirror.
            Settlement live = registeredSettlement(helper, "Levende");
            try {
                RaidPlan plan = activeFirstRaid(helper, live);
                live.recurringRaidRun.block();
                RaidDirector.tick(helper.getLevel(), live);
                helper.assertTrue(live.pendingRaid != null && live.pendingRaid.equals(plan),
                    "an active first raid owns its mirror even beside a blocked recurring run");
            } finally {
                forget(helper, live);
            }
        } finally {
            forget(helper, completed);
            forget(helper, damaged);
        }
        helper.succeed();
    }
}
