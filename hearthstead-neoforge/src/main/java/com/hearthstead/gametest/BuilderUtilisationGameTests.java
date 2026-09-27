package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.BuilderUtilisation;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuilderMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * Owner feedback on film take 2 ("she takes a lot of breaks"): a Builder with
 * every material in his hut spends at least 70 % of his working time placing
 * blocks or carrying them, and never stands idle for long between blocks.
 * Batch {@code builder_util}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderUtilisationGameTests {

    private static final BlockPos SITE = new BlockPos(8, 1, 8);

    /** 6x6 cottage: stone floor, three plank wall courses with a doorway, plank roof (130 blocks). */
    static Blueprint cottage() {
        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 5, 0, 5, stone)
            .box(0, 1, 0, 5, 3, 5, planks).box(1, 1, 1, 4, 3, 4, air)
            .box(0, 4, 0, 5, 4, 5, planks);
        c.set(2, 1, 0, air).set(2, 2, 0, air);
        return new Blueprint(BuilderTestKit.meta("test_util_cottage", BlueprintMeta.Kind.DEFENSE, null, null, null),
            6, 5, 6, c.list());
    }

    @GameTest(template = "empty16", timeoutTicks = 6000, batch = "builder_util")
    public void builderWorksMostOfTheTimeWhenMaterialsExist(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), cottage(),
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null,
            "cottage must validate: " + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), plan.job()) == null, "queued");
        BuildJob job = plan.job();
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        SettlerEntity builder = BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Hild");
        BuilderUtilisation.reset(builder.getUUID());
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "cottage complete: " + (live == null ? "gone" : live.state + " " + live.status
                    + " done " + live.doneCount() + "/" + live.size()));
            BuilderUtilisation.Stats stats = BuilderUtilisation.stats(builder.getUUID());
            String report = stats.describe();
            Hearthstead.LOGGER.info("builder_util cottage: {}", report);
            helper.assertTrue(stats.total() > 0, "the Builder's time was sampled");
            helper.assertTrue(stats.utilisation() >= 0.70D,
                "at least 70% placing or carrying while materials exist: " + report);
            helper.assertTrue(stats.longestIdle() <= 80L,
                "never idle more than 4 s at a stretch: " + report);
            for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                helper.assertTrue(BuilderTestKit.count(arena.chest(), e.getKey()) == 0, "used exactly " + e);
            }
        });
    }
}
