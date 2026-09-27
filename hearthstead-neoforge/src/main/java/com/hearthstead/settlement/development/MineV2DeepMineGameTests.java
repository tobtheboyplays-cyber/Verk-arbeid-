package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.ai.MineShaftPlan;
import com.hearthstead.entity.ai.MineShaftWork;
import com.hearthstead.gametest.MineV2GameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINE V2: the Deep Mine tech opens level 2. Here (learnTech is package
 * private) rather than in MineV2GameTests.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MineV2DeepMineGameTests {

    /** Level 1 finished; Deep Mine learned: the shaft goes on down and a second lane is dug there. */
    @GameTest(batch = "mine_v2_shaft", template = "empty16", timeoutTicks = 4000)
    public void mineV2DeepMineOpensLevelTwo(GameTestHelper helper) {
        MineV2GameTests.Fixture f = MineV2GameTests.mine(helper, Items.IRON_PICKAXE, new ItemStack(Items.LADDER, 16));
        int ground = MineV2GameTests.GROUND;
        int one = ground - MineV2GameTests.DEPTH_ONE;
        int two = one - MineV2GameTests.DEPTH_TWO;
        int lane = MineV2GameTests.LANE;
        // A finished level 1 (ladders, dig column, lane).
        for (int y = ground - 1; y >= one; y--) {
            helper.setBlock(new BlockPos(8, y, 8), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
            helper.setBlock(new BlockPos(8, y, 9), Blocks.AIR);
        }
        for (int i = 1; i <= lane; i++) {
            helper.setBlock(new BlockPos(8, one, 9 + i), Blocks.AIR);
            helper.setBlock(new BlockPos(8, one + 1, 9 + i), Blocks.AIR);
        }
        var level = helper.getLevel();
        MineShaftPlan.Site site = MineShaftWork.find(level, f.mine());
        helper.assertTrue(site != null, "site");
        helper.assertTrue(MineShaftWork.floorTwo(level, f.settlement(), site) == null, "no level 2 before Deep Mine");
        DevelopmentState state = Development.of(level, f.settlement());
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlock(DevelopmentNode.ARM_THE_WATCH);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        state.learnTech("deep_mine");
        Integer floorTwo = MineShaftWork.floorTwo(level, f.settlement(), site);
        helper.assertTrue(floorTwo != null && floorTwo == helper.absolutePos(new BlockPos(0, two, 0)).getY(),
            "Deep Mine opens level 2 at " + two + ": " + floorTwo);
        f.miner().moveTo(helper.absolutePos(new BlockPos(8, one, 9)).getBottomCenter());
        level.setDayTime(1000);
        helper.succeedWhen(() -> {
            for (int y = one - 1; y >= two; y--) {
                helper.assertTrue(helper.getBlockState(new BlockPos(8, y, 8)).is(Blocks.LADDER), "ladder at " + y + " " + MineV2GameTests.debug(f.miner()));
            }
            for (int i = 1; i <= lane; i++) {
                helper.assertTrue(helper.getBlockState(new BlockPos(8, two + 1, 9 + i)).isAir(), "level-2 lane " + i
                    + " " + MineV2GameTests.debug(f.miner()));
            }
            helper.assertTrue(MineV2GameTests.debug(f.miner()).contains("last=FACE"),
                "works the level-2 face: " + MineV2GameTests.debug(f.miner()));
        });
    }
}
