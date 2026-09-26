package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * Fishery basins and wells (Main, 26 Sep): template water is poured, not
 * skipped -- two water buckets per connected body, the rest scooped from
 * the source they make, and the two empty buckets come home. Batch
 * {@code builder_water}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderWaterGameTests {

    private static final BlockPos SITE = new BlockPos(9, 1, 9);

    /** A 4x3 cobble tub with a 2x3 basin of water inside (6 water cells, one body). */
    static Blueprint basin() {
        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 3, 0, 4, stone)
            .box(0, 1, 0, 3, 1, 4, stone)
            .box(1, 1, 1, 2, 1, 3, water);
        return new Blueprint(BuilderTestKit.meta("test_basin", BlueprintMeta.Kind.DEFENSE, null, null, null),
            4, 2, 5, c.list());
    }

    @GameTest(template = "empty16", timeoutTicks = 4000, batch = "builder_water")
    public void basinIsPouredWithTwoBucketsAndTheBucketsComeHome(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        BuildPlanner.Plan plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), basin(),
            helper.absolutePos(SITE), 0, false, null);
        helper.assertTrue(plan.validation().ok() && plan.job() != null,
            "basin must validate: " + plan.validation().reasonKey() + " " + plan.validation().reasonArgs());
        BuildJob job = plan.job();
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        helper.assertTrue(bill.getOrDefault(Items.WATER_BUCKET, 0) == 2,
            "one body of 6 water cells costs exactly 2 water buckets: " + bill);
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        for (Map.Entry<Item, Integer> e : bill.entrySet()) {
            BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
        }
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Sigrun");
        helper.succeedWhen(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE && live.skippedCount() == 0,
                "basin complete, nothing skipped: " + (live == null ? "gone" : live.state + " " + live.status
                    + " done " + live.doneCount() + "/" + live.size() + " [" + live.skipReport() + "]"));
            for (int x = 1; x <= 2; x++) {
                for (int z = 1; z <= 3; z++) {
                    BlockState at = helper.getBlockState(SITE.offset(x, 1, z));
                    helper.assertTrue(at.is(Blocks.WATER) && at.getFluidState().isSource(),
                        "water source at basin cell " + x + "," + z + ": " + at);
                }
            }
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.WATER_BUCKET) == 0, "both water buckets poured");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.BUCKET) == 2, "the two empty buckets came home");
            helper.assertTrue(BuilderTestKit.count(arena.chest(), Items.COBBLESTONE) == 0, "exact cobble used");
        });
    }
}
