package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.BuilderWorkGoal;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildExecutor;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPlanner;
import com.hearthstead.settlement.builder.BuilderScaffold;
import com.hearthstead.settlement.builder.BuilderStock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Isolates stale scaffold recovery, not the complete Fishery build or navigation. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderScaffoldRecoveryGameTests {
    private static final String BATCH = "builder_scaffold_recovery";

    private record Fixture(BuilderTestKit.Arena arena, SettlerEntity builder,
                           BuilderWorkGoal goal, BuildJob job, BuilderScaffold.Column column) {
        BlockPos top() {
            return column.rungs().get(2);
        }
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void obstructedThirdRungReleasesColumnAndResumesWork(GameTestHelper h) {
        Fixture f = fixture(h);
        f.arena.level().setBlockAndUpdate(f.top(), Blocks.BRICKS.defaultBlockState());
        long retryClock = (long) field(f.goal, "stepSince");
        Vec3 position = f.builder.position();

        tick(f.goal, BuilderWorkGoal.PLACE_TICKS);
        h.assertTrue(field(f.goal, "column") == null, "a failed rung cannot retain a stale column forever");
        h.assertTrue((int) field(f.goal, "scaffoldNeed") == 0, "abandoned column does not request more ladders");
        h.assertTrue((int) field(f.goal, "columnStep") == 1
            && (long) field(f.goal, "stepSince") == retryClock, "the existing step retry guard is preserved");
        h.assertTrue(f.builder.position().equals(position), "rung recovery does not teleport the Builder");
        h.assertTrue(f.arena.level().getBlockState(f.top()).is(Blocks.BRICKS), "the obstacle is untouched");
        assertExistingWork(h, f, 2, 1);
        h.assertTrue(f.job.doneCount() == 1 && f.job.isDone(0) && !f.job.isDone(1)
            && f.job.skippedCount() == 0 && f.job.blockedCount() == 0, "rung failure does not alter job progress");

        // Public goal ticks must leave scaffolding and execute the reachable job step.
        finishReachableStep(f);
        h.assertTrue(f.job.doneCount() == 2 && f.job.isDone(0) && f.job.skippedCount() == 0,
            "normal building resumes after the stale column is released");
        h.assertTrue(BuilderStock.bagCount(f.builder.bag, Items.COBBLESTONE) == 0,
            "exactly the two actual job blocks consumed the two cobblestone");
        h.assertTrue(f.arena.level().getBlockState(f.top()).is(Blocks.BRICKS), "resumed work keeps the obstacle");
        assertExistingWork(h, f, 2, 1);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void successfulThirdRungConsumesExactlyOneLadder(GameTestHelper h) {
        Fixture f = fixture(h);
        tick(f.goal, BuilderWorkGoal.PLACE_TICKS);
        h.assertTrue(f.arena.level().getBlockState(f.top()).is(Blocks.LADDER), "the third rung is hung");
        h.assertTrue(f.job.scaffold.contains(f.top()), "the new rung remains tracked for cleanup");
        assertExistingWork(h, f, 3, 0);
        h.assertTrue(f.job.doneCount() == 1, "scaffolding does not mark the job step done");
        f.goal.tick();
        h.assertTrue(field(f.goal, "column") == null && (int) field(f.goal, "scaffoldNeed") == 0,
            "the completed column returns to ordinary work");
        finishReachableStep(f);
        h.assertTrue(f.job.doneCount() == 2, "work resumes after successful scaffolding");
        assertExistingWork(h, f, 3, 0);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void missingLadderKeepsColumnAndReturnsToLoading(GameTestHelper h) {
        Fixture f = fixture(h);
        ItemStack removed = f.builder.bag.removeItem(1, 1);
        h.assertTrue(removed.is(Items.LADDER) && removed.getCount() == 1, "fixture removes the last carried ladder");
        // Isolate the no-ladder branch if the bag changes after the outer tick's bag check.
        // Ordinary public tick() instead enters LOAD before reaching tickScaffold.
        for (int i = 0; i < BuilderWorkGoal.PLACE_TICKS; i++) {
            scaffoldTick(f);
        }
        h.assertTrue(field(f.goal, "column") == f.column, "material shortage keeps the valid column plan");
        h.assertTrue(field(f.goal, "stage").toString().equals("LOAD"), "the Builder returns to loading");
        h.assertTrue((int) field(f.goal, "scaffoldNeed") == 1, "only the remaining rung is needed");
        h.assertTrue(f.arena.level().getBlockState(f.top()).isAir(), "no free ladder is placed");
        assertExistingWork(h, f, 2, 0);
        h.assertTrue(f.job.doneCount() == 1 && f.job.skippedCount() == 0
            && BuilderStock.bagCount(f.builder.bag, Items.COBBLESTONE) == 1,
            "missing ladder neither spends job material nor changes progress");
        h.assertTrue(f.job.scaffold.size() + removed.getCount() == 3, "all three original ladders are accounted for");
        h.succeed();
    }

    private static Fixture fixture(GameTestHelper h) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 16, 7);
        Blueprint blueprint = new Blueprint(BuilderTestKit.meta("test_scaffold_recovery",
            BlueprintMeta.Kind.DEFENSE, null, null, null), 2, 1, 1,
            new BuilderTestKit.Cells().box(0, 0, 0, 1, 0, 0, Blocks.COBBLESTONE.defaultBlockState()).list());
        var plan = BuildPlanner.planBlueprint(arena.level(), arena.settlement(), blueprint,
            h.absolutePos(new BlockPos(10, 1, 9)), 0, false, null);
        h.assertTrue(plan.validation().ok() && plan.job() != null, "the small work fixture plans");
        BuildJob job = plan.job();
        h.assertTrue(job.size() == 2 && BuildJobs.commit(arena.level(), arena.settlement(), job) == null,
            "exactly two job steps enter the queue");
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, arena, new BlockPos(9, 1, 11), "Bram");
        builder.setNoAi(true); // Drive the entity's real goal explicitly, without a competing scheduler.
        builder.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        builder.bag.setItem(1, new ItemStack(Items.LADDER, 3));
        h.assertTrue(BuildExecutor.execute(arena.level(), job, 0, builder.bag,
            List.of(arena.chest()), builder.blockPosition()) == BuildExecutor.Outcome.DONE,
            "the first job step is completed through the actual executor");

        List<BlockPos> rungs = List.of(h.absolutePos(new BlockPos(9, 1, 9)),
            h.absolutePos(new BlockPos(9, 2, 9)), h.absolutePos(new BlockPos(9, 3, 9)));
        for (BlockPos rung : rungs) {
            arena.level().setBlockAndUpdate(rung.north(), Blocks.STONE.defaultBlockState());
        }
        BuilderScaffold.Column column = new BuilderScaffold.Column(rungs, Direction.SOUTH);
        h.assertTrue(BuilderScaffold.hang(arena.level(), job, rungs.get(0), column.facing(), builder.bag)
            && BuilderScaffold.hang(arena.level(), job, rungs.get(1), column.facing(), builder.bag),
            "the first two rungs are really placed and paid for");
        h.assertTrue(builder.getEyePosition().distanceToSqr(Vec3.atCenterOf(rungs.get(2)))
            <= BuilderWorkGoal.BUILD_REACH * BuilderWorkGoal.BUILD_REACH
            && !builder.getBoundingBox().intersects(new AABB(rungs.get(2))),
            "the remaining rung is reachable and does not intersect the Builder");
        BuilderWorkGoal goal = builder.goalSelector.getAvailableGoals().stream()
            .map(wrapped -> wrapped.getGoal()).filter(BuilderWorkGoal.class::isInstance)
            .map(BuilderWorkGoal.class::cast).findFirst().orElseThrow();
        h.assertTrue(goal.canUse(), "the real Builder goal claims the fixture job");
        goal.start();
        // Reflection seeds only the in-flight plan; all placement and recovery use production code.
        field(goal, "step", 1);
        field(goal, "stepSince", arena.level().getGameTime());
        field(goal, "column", column);
        field(goal, "columnStep", 1);
        field(goal, "scaffoldNeed", 1);
        return new Fixture(arena, builder, goal, job, column);
    }

    private static void assertExistingWork(GameTestHelper h, Fixture f, int installed, int carried) {
        for (int i = 0; i < 2; i++) {
            BlockPos rung = f.column.rungs().get(i);
            h.assertTrue(f.arena.level().getBlockState(rung).is(Blocks.LADDER) && f.job.scaffold.contains(rung),
                "previous rungs stay installed and tracked");
        }
        h.assertTrue(f.job.scaffold.size() == installed
            && BuilderStock.bagCount(f.builder.bag, Items.LADDER) == carried,
            "installed and carried ladder counts are conserved");
        h.assertTrue(f.job.isDone(0) && f.arena.level().getBlockState(f.job.pos(0)).is(Blocks.COBBLESTONE),
            "previously completed job work remains intact");
    }

    private static void finishReachableStep(Fixture f) {
        // Stop before end-of-job ladder cleanup, including when attributes shorten placement.
        for (int i = 0; i < BuilderWorkGoal.PLACE_TICKS && f.job.doneCount() < 2; i++) {
            f.goal.tick();
        }
    }

    private static void tick(BuilderWorkGoal goal, int count) {
        for (int i = 0; i < count; i++) goal.tick();
    }

    private static Object field(BuilderWorkGoal goal, String name) {
        try {
            Field field = BuilderWorkGoal.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(goal);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect Builder goal " + name, failure);
        }
    }

    private static void field(BuilderWorkGoal goal, String name, Object value) {
        try {
            Field field = BuilderWorkGoal.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(goal, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot seed Builder goal " + name, failure);
        }
    }

    private static void scaffoldTick(Fixture f) {
        try {
            Method method = BuilderWorkGoal.class.getDeclaredMethod("tickScaffold", ServerLevel.class, BuildJob.class, long.class);
            method.setAccessible(true);
            method.invoke(f.goal, f.arena.level(), f.job, f.arena.level().getGameTime());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot drive the Builder scaffold branch", failure);
        }
    }
}
