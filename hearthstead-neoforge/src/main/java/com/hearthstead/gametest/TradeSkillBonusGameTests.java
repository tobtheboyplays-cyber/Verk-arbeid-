package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Per-trade job bonuses wired through {@link SkillLevels}: a level-1 worker
 * is exactly unchanged, a level-10 worker gets the modest capped bonus.
 * Own batch; never touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class TradeSkillBonusGameTests {

    private static SettlerEntity settler(GameTestHelper helper, Profession profession,
                                         int x, int level) {
        helper.setBlock(new BlockPos(x, 0, 2), Blocks.STONE_BRICKS);
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, 2));
        settler.setSettlerName("Brann");
        settler.setProfessionProjection(profession);
        settler.setNoAi(true);
        for (Attribute a : Attribute.values()) {
            settler.attributes().pinForTest(a, 40);
        }
        settler.tradeSkills().set(profession, SkillLevels.xpForLevel(level));
        return settler;
    }

    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) {
            helper.fail(message);
        }
    }

    /** Every wired wait/roll is identity at level 1, even with strong attributes. */
    @GameTest(batch = "trade_skill_bonus", template = "empty16", timeoutTicks = 20)
    public void levelOneWorkersAreUnchanged(GameTestHelper helper) {
        Profession[] trades = {Profession.FARMER, Profession.MINER, Profession.HERDER,
            Profession.HUNTER, Profession.INNKEEPER, Profession.COURIER, Profession.SCHOLAR};
        int x = 0;
        for (Profession trade : trades) {
            SettlerEntity fresh = settler(helper, trade, x++, 1);
            check(helper, SkillLevels.levelOf(fresh) == 1, trade + " must start at level 1");
            for (int ticks : new int[] {20, 39, 60, 80}) {
                check(helper, SkillLevels.shortenWait(fresh, ticks) == ticks,
                    trade + " level-1 wait " + ticks + " must be unchanged");
            }
            check(helper, !SkillLevels.rollSide(fresh), trade + " level-1 never rolls a side bonus");
            check(helper, SkillLevels.carryBonus(fresh) == 0, trade + " level-1 carry unchanged");
            check(helper, SkillLevels.paceBonus(fresh) == 0.0D, trade + " level-1 pace unchanged");
            check(helper, SkillLevels.researchBonus(fresh) == 0.0D,
                trade + " level-1 research unchanged");
            // Only the cross-job Intelligence XP line may show at level 1.
            check(helper, SkillLevels.describeBonuses(fresh).size()
                    == (SkillLevels.witsXpBonus(40) > 0.0D ? 1 : 0),
                trade + " level-1 lists no job bonus");
        }
        helper.succeed();
    }

    /** A level-10 worker gets each wired, capped bonus and the UI lists it. */
    @GameTest(batch = "trade_skill_bonus", template = "empty16", timeoutTicks = 20)
    public void masterWorkersGetCappedBonuses(GameTestHelper helper) {
        SettlerEntity innkeeper = settler(helper, Profession.INNKEEPER, 0, 10);
        int cooked = SkillLevels.shortenWait(innkeeper, 80);
        check(helper, cooked < 80 && cooked >= 66, "master innkeeper meal wait 80 -> " + cooked);
        SettlerEntity miner = settler(helper, Profession.MINER, 1, 10);
        int pause = SkillLevels.shortenWait(miner, 20);
        check(helper, pause < 20 && pause >= 16, "master miner pause 20 -> " + pause);
        SettlerEntity courier = settler(helper, Profession.COURIER, 2, 10);
        check(helper, SkillLevels.carryBonus(courier) == SkillLevels.MAX_CARRY_BONUS,
            "master courier carries +3");
        double pace = SkillLevels.paceBonus(courier);
        check(helper, pace > 0.0D && pace <= SkillLevels.MAX_SIDE_CHANCE + 1e-9,
            "master courier pace bonus capped at 8%, got " + pace);
        SettlerEntity scholar = settler(helper, Profession.SCHOLAR, 3, 10);
        check(helper, Math.abs(SkillLevels.researchBonus(scholar) - 0.25D) < 1e-9,
            "master scholar with WITS 40 gets the +25% research cap");
        check(helper, SkillLevels.describeBonuses(innkeeper).size() >= 2,
            "innkeeper bonuses are listed for the UI");
        check(helper, SkillLevels.describeBonuses(courier).size() >= 2,
            "courier carry/pace are listed for the UI");
        helper.succeed();
    }

    /** Farmer seed bonus only ever grows the crop's own vanilla seed drop. */
    @GameTest(batch = "trade_skill_bonus", template = "empty16", timeoutTicks = 20)
    public void farmerSeedBonusOnlyForOwnSeed(GameTestHelper helper) {
        check(helper, FarmerWorkGoal.isOwnSeed(new ItemStack(Items.WHEAT_SEEDS), Blocks.WHEAT),
            "wheat seeds are wheat's own seed");
        check(helper, FarmerWorkGoal.isOwnSeed(new ItemStack(Items.CARROT), Blocks.CARROTS),
            "a carrot replants carrots");
        check(helper, !FarmerWorkGoal.isOwnSeed(new ItemStack(Items.WHEAT), Blocks.WHEAT),
            "the wheat produce is never bonus-grown");
        check(helper, !FarmerWorkGoal.isOwnSeed(new ItemStack(Items.SUGAR_CANE), Blocks.SUGAR_CANE),
            "cane is not a crop block: no seed bonus");
        check(helper, !FarmerWorkGoal.isOwnSeed(new ItemStack(Items.BEETROOT_SEEDS), Blocks.WHEAT),
            "another crop's seed is never grown");
        helper.succeed();
    }
}
