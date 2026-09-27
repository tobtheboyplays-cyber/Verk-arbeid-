package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SettlerFlourish;
import com.hearthstead.entity.ai.FreeTimeGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * "Alive village" slice: free-time life and visible skill growth. Own batch;
 * the clock is never moved -- the phase gate is exercised through the pure
 * {@link FreeTimeGoal#allowed} predicate plus the real goal where the live
 * phase already makes the answer certain.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class AliveVillageGameTests {
    private static Settlement settlement(GameTestHelper helper) {
        Settlement settlement = new Settlement(UUID.randomUUID(), "Lindmere",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 8;
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        return settlement;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, z));
        settler.bindTo(settlement.id, settlement.center);
        settler.setNoAi(true); // The test drives the real goal instance deliberately.
        return settler;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    @GameTest(batch = "alive_village", template = "empty16", timeoutTicks = 60)
    public void freeTimeNeverStartsDuringWorkPhaseOrAlarm(GameTestHelper helper) {
        floor(helper);
        Settlement settlement = settlement(helper);
        SettlerEntity resident = settler(helper, settlement, 8, 8);
        long now = helper.getLevel().getGameTime();

        helper.assertTrue(FreeTimeGoal.allowed(resident, settlement, DayPhase.EVENING, now),
            "baseline: a rested, idle resident may take free time in the evening");
        helper.assertTrue(FreeTimeGoal.allowed(resident, settlement, DayPhase.MEAL, now),
            "baseline: the midday meal is free time too");
        for (DayPhase phase : new DayPhase[] {DayPhase.MORNING_WORK, DayPhase.AFTERNOON_WORK,
                DayPhase.REST, DayPhase.RISE}) {
            helper.assertFalse(FreeTimeGoal.allowed(resident, settlement, phase, now),
                "free time must never start in " + phase);
        }
        resident.assignProfession(Profession.COURIER);
        helper.assertFalse(FreeTimeGoal.allowed(resident, settlement, DayPhase.MORNING_WORK, now),
            "an employed resident must never idle through the work phase");

        settlement.alertUntilGameTime = now + 400L;
        helper.assertFalse(FreeTimeGoal.allowed(resident, settlement, DayPhase.EVENING, now),
            "free time must never start while the alarm is raised");
        helper.assertFalse(FreeTimeGoal.allowed(resident, settlement, DayPhase.MEAL, now),
            "not at the meal either while the alarm is raised");
        // The real goal, at whatever the live clock says, must refuse too.
        helper.assertFalse(new FreeTimeGoal(resident).canUse(),
            "the live goal must refuse during an alarm");
        settlement.alertUntilGameTime = 0L;

        if (resident.dayPhase().work()) {
            helper.assertFalse(new FreeTimeGoal(resident).canUse(),
                "the live goal must refuse in the live work phase");
        }
        helper.succeed();
    }

    @GameTest(batch = "alive_village", template = "empty16", timeoutTicks = 60)
    public void rankUpFlourishFiresOncePerRankUp(GameTestHelper helper) {
        floor(helper);
        Settlement settlement = settlement(helper);
        SettlerEntity resident = settler(helper, settlement, 8, 8);
        resident.assignProfession(Profession.COURIER);
        Attribute attribute = Attribute.STRENGTH;
        for (Attribute candidate : Attribute.values()) {
            if (resident.attribute(candidate) < resident.attribute(attribute)) {
                attribute = candidate;
            }
        }
        Attribute trained = attribute;
        int before = SettlerFlourish.firedCount(resident);

        // Progress that does not cross a point never flourishes.
        int startValue = resident.attribute(trained);
        resident.train(trained, 0.0001F);
        helper.assertTrue(resident.attribute(trained) == startValue
                && SettlerFlourish.firedCount(resident) == before,
            "training without a rank-up must not flourish");

        int first = trainUntilRise(resident, trained);
        helper.assertTrue(first > startValue, "fixture: the attribute must rise");
        helper.assertTrue(SettlerFlourish.firedCount(resident) == before + 1,
            "one rank-up must fire exactly one flourish, got "
                + (SettlerFlourish.firedCount(resident) - before));

        helper.runAfterDelay(2, () -> {
            int second = trainUntilRise(resident, trained);
            helper.assertTrue(second > first, "fixture: the attribute must rise again");
            helper.assertTrue(SettlerFlourish.firedCount(resident) == before + 2,
                "a second rank-up must fire exactly one more flourish, got "
                    + (SettlerFlourish.firedCount(resident) - before));
            helper.succeed();
        });
    }

    /** Small steps so exactly one point is gained. */
    private static int trainUntilRise(SettlerEntity settler, Attribute attribute) {
        int start = settler.attribute(attribute);
        int guard = 0;
        while (settler.attribute(attribute) == start && guard++ < 200000) {
            settler.train(attribute, 0.5F);
        }
        return settler.attribute(attribute);
    }
    @GameTest(batch = "alive_village", template = "empty16", timeoutTicks = 260)
    public void wellFedSettlersRegainHealthAndHungryOnesDoNot(GameTestHelper helper) {
        floor(helper);
        Settlement settlement = settlement(helper);
        SettlerEntity fed = settler(helper, settlement, 5, 8);
        SettlerEntity hungry = settler(helper, settlement, 11, 8);
        fed.setHunger(100.0F);
        fed.setHealth(10.0F);
        hungry.setHunger(50.0F);
        hungry.setHealth(10.0F);
        helper.runAfterDelay(200, () -> {
            helper.assertTrue(fed.getHealth() > 10.0F,
                "a settler at full hunger must slowly regain health, got " + fed.getHealth());
            helper.assertTrue(fed.getHunger() < 100.0F,
                "healing must cost hunger, got " + fed.getHunger());
            helper.assertTrue(hungry.getHealth() <= 10.0F,
                "a settler below full hunger must not regenerate, got " + hungry.getHealth());
            helper.succeed();
        });
    }
}
