package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): the first raid LOST, which until now had JUnit
 * coverage only (batches {@code scenario_raid_*}). The band gets what it came
 * for (the stores escape for KORN, a settler is hurt for BLOD): the raid
 * closes once as HIT with no Coins and no Blessing, logs one not-held
 * aftermath, keeps that across a reload, and does not block the recurring
 * raids that follow. A village with nobody left is SETTLEMENT_LOST.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioRaidGameTests {

    private static Settlement settlement(GameTestHelper helper, String name, int residents) {
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(6, 1, 6)));
        s.radius = 10;
        for (int i = 0; i < residents; i++) s.putRecord(UUID.randomUUID(), "Resident" + i, Profession.NONE);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static RaidPlan armFirstRaid(GameTestHelper helper, Settlement s, RaidObjective objective) {
        helper.assertTrue(s.raidLifecycle.initializeAtFounding(0L, 4, 2), "fixture: first raid initialises");
        RaidCaptain captain = RaidDirector.pickCaptain(s, helper.getLevel().getRandom());
        RaidPlan plan = new RaidPlan(captain.id(), objective, 0.0F, 4L);
        helper.assertTrue(s.raidLifecycle.queueFirstPlan(plan) && s.raidLifecycle.beginFirstRaid(plan),
            "fixture: the exact queued plan becomes the active first raid");
        s.pendingRaid = plan;
        return plan;
    }

    private static RaiderEntity participant(GameTestHelper helper, Settlement s, RaidPlan plan, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(plan.captainId(), s.id, plan.objective(), 1.0F, false);
        helper.assertTrue(s.raidLifecycle.recordParticipant(raider.getUUID()), "fixture: the raider joins the capture");
        return raider;
    }

    private static void lose(GameTestHelper helper, Settlement s, RaidObjective objective,
                             JourneyOutcome expected) {
        RaidPlan plan = armFirstRaid(helper, s, objective);
        RaiderEntity thief = participant(helper, s, plan, new BlockPos(3, 1, 3));
        RaiderEntity brute = participant(helper, s, plan, new BlockPos(8, 1, 8));
        helper.assertTrue(s.raidLifecycle.sealParticipants(), "fixture: two raiders seal the band");
        int pressureBefore = s.raidPressure.pressure();
        // The band gets what it came for, then leaves the field.
        switch (objective) {
            case KORN, LOSEPENGER -> s.raidLootEscaped = true;
            case BLOD -> s.raidSettlersHurtTonight = 1;
            default -> throw new IllegalArgumentException(objective.name());
        }
        thief.discard();
        brute.hurt(helper.getLevel().damageSources().genericKill(), brute.getMaxHealth() + 100.0F);
        helper.assertTrue(s.raidLifecycle.allParticipantsTerminal(), "every raider is accounted for");
        helper.assertTrue(RaidDirector.resolveIfOver(helper.getLevel(), s), "the first raid closes");

        RaidLifecycle life = s.raidLifecycle;
        helper.assertTrue(life.firstState() == FirstRaidState.COMPLETED, "first raid completed: " + life.firstState());
        helper.assertTrue(life.firstRaidTerminal().isPresent()
                && life.firstRaidTerminal().get().outcome() == expected,
            "terminal outcome " + expected + ", got " + life.firstRaidTerminal().map(t -> t.outcome().name()).orElse("none"));
        helper.assertTrue(!life.mayGrantReward() && s.raidCoinRewards.pending() == 0,
            "a lost first raid pays no Coins (pending " + s.raidCoinRewards.pending() + ")");
        helper.assertTrue(s.blessingState.earned() == 0, "a lost first raid earns no Blessing");
        helper.assertTrue(s.raidLog.size() == 1 && !s.raidLog.get(0).held() && s.pendingRaid == null,
            "one aftermath, not held, runtime mirror cleared");
        helper.assertTrue(s.raidPressure.pressure() <= pressureBefore,
            "losing never raises the pressure (" + pressureBefore + " -> " + s.raidPressure.pressure() + ")");
        helper.assertTrue(!life.recurringScheduleBlocked(), "the recurring raids are not blocked by a loss");

        int logSize = s.raidLog.size();
        helper.assertTrue(!RaidDirector.resolveIfOver(helper.getLevel(), s) && s.raidLog.size() == logSize
                && s.blessingState.earned() == 0, "a second resolution changes nothing");
        Settlement loaded = Settlement.readNbt(s.writeNbt(), SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(loaded.raidLifecycle.firstState() == FirstRaidState.COMPLETED
                && loaded.raidLifecycle.firstRaidTerminal().map(t -> t.outcome() == expected).orElse(false)
                && loaded.raidCoinRewards.pending() == 0 && loaded.blessingState.earned() == 0
                && loaded.raidLog.size() == 1 && !loaded.raidLog.get(0).held(),
            "a reload keeps the lost first raid exactly");
        SettlementSavedData.get(helper.getLevel()).settlements.remove(s.id);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "scenario_raid_first_lost_korn")
    public void firstRaidLostWhenTheStoresEscape(GameTestHelper helper) {
        lose(helper, settlement(helper, "Kornvik", 4), RaidObjective.KORN, JourneyOutcome.HIT);
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "scenario_raid_first_lost_blod")
    public void firstRaidLostWhenASettlerIsHurt(GameTestHelper helper) {
        lose(helper, settlement(helper, "Blodvik", 4), RaidObjective.BLOD, JourneyOutcome.HIT);
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "scenario_raid_first_lost_empty")
    public void firstRaidLostWithNobodyLeftIsSettlementLost(GameTestHelper helper) {
        lose(helper, settlement(helper, "Tomvik", 0), RaidObjective.KORN, JourneyOutcome.SETTLEMENT_LOST);
    }
}
