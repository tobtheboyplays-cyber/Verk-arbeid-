package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.builder.BuildExecutor;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildPhase;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderScaffold;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Ownership and fair retry of exhausted cleanup jobs; does not reproduce navigation failures. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderScaffoldRetryGameTests {
    private static final String BATCH = "builder_scaffold_retry";
    private record Fixture(BuilderTestKit.Arena arena, BuildJob cleanup) {}

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void skippedCleanupSurvivesPruningAndReloadThenReturnsRealLadders(GameTestHelper h) {
        Fixture f = fixture(h);
        BuildSiteSavedData data = BuildSiteSavedData.get(f.arena.level());
        exhaustBothPasses(h, f.arena, f.cleanup);
        for (int i = 0; i < BuildSiteSavedData.KEEP_FINISHED + 3; i++) {
            BuildJob history = ordinary(f.arena, f.cleanup.createdAt + i + 1L);
            history.state = BuildJob.State.COMPLETE;
            h.assertTrue(data.add(history), "finished history fixture enters the bounded store");
        }
        h.assertTrue(data.job(f.arena.settlement().id, f.cleanup.id) == f.cleanup,
            "finished-history pruning cannot erase the six outstanding scaffold steps");
        CompoundTag saved = data.save(new CompoundTag(), f.arena.level().registryAccess());
        BuildSiteSavedData loaded = BuildSiteSavedData.load(saved, f.arena.level().registryAccess());
        f.arena.level().getDataStorage().set("hearthstead_build_sites", loaded);
        BuildJob restored = loaded.job(f.arena.settlement().id, f.cleanup.id);
        h.assertTrue(restored != null && restored.state == BuildJob.State.COMPLETE
            && restored.secondPass && restored.skippedCount() == 6 && restored.doneCount() == 1,
            "the actual saved cleanup retains its skipped ownership and completed step after reload");
        int entries = loaded.jobs(f.arena.settlement().id).size();
        BuildJobs.tick(f.arena.level(), f.arena.settlement());
        h.assertTrue(restored.state == BuildJob.State.ACTIVE && restored.skippedCount() == 0
            && restored.doneCount() == 1 && restored.secondPass && !restored.rush,
            "idle upkeep reopens only the remaining work for one ordinary-priority pass");
        h.assertTrue(loaded.jobs(f.arena.settlement().id).size() == entries,
            "recovery reuses the same persisted job instead of appending another cleanup");
        assertRungs(h, f.arena, restored, 6, 1);
        for (int i = 1; i < restored.size(); i++) execute(h, f.arena, restored, i);
        BuildJobs.finish(f.arena.level(), f.arena.settlement(), restored);
        h.assertTrue(restored.state == BuildJob.State.COMPLETE && restored.status == BuildStatus.DONE
            && restored.doneCount() == 7 && restored.skippedCount() == 0, "every real rung was removed");
        assertRungs(h, f.arena, restored, 0, 7);
        BuildJobs.tick(f.arena.level(), f.arena.settlement());
        h.assertTrue(loaded.activeJobs(f.arena.settlement().id).isEmpty(), "successful cleanup does not reopen");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void newConstructionRunsBetweenFailedCleanupPasses(GameTestHelper h) {
        Fixture f = fixture(h);
        BuildSiteSavedData data = BuildSiteSavedData.get(f.arena.level());
        exhaustBothPasses(h, f.arena, f.cleanup);
        BuildJob first = ordinary(f.arena, 1L);
        h.assertTrue(data.add(first), "new construction is queued after cleanup completed with skips");
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, f.arena, new BlockPos(4, 1, 7), "Bram");
        builder.setNoAi(true);
        BuildJobs.tick(f.arena.level(), f.arena.settlement());
        h.assertTrue(f.cleanup.state == BuildJob.State.COMPLETE && f.cleanup.skippedCount() == 6,
            "upkeep preserves cleanup ownership while construction is active");
        h.assertTrue(BuildJobs.claimNext(f.arena.level(), f.arena.settlement(), builder) == first,
            "the actual claimant receives the newly queued construction");
        execute(h, f.arena, first, 0);
        BuildJobs.finish(f.arena.level(), f.arena.settlement(), first);
        BuildJobs.tick(f.arena.level(), f.arena.settlement());
        h.assertTrue(BuildJobs.claimNext(f.arena.level(), f.arena.settlement(), builder) == f.cleanup,
            "cleanup gets an idle retry after ordinary work finishes");
        BuildJob second = ordinary(f.arena, 2L);
        h.assertTrue(data.add(second), "more construction arrives during that cleanup pass");
        skipRemaining(f.cleanup);
        BuildJobs.finish(f.arena.level(), f.arena.settlement(), f.cleanup);
        h.assertTrue(f.cleanup.state == BuildJob.State.COMPLETE && f.cleanup.skippedCount() == 6,
            "the retry finishes after one exhausted pass and preserves all six obligations");
        BuildJobs.tick(f.arena.level(), f.arena.settlement());
        h.assertTrue(f.cleanup.state == BuildJob.State.COMPLETE
            && BuildJobs.claimNext(f.arena.level(), f.arena.settlement(), builder) == second,
            "failed cleanup cannot monopolize repeated passes ahead of newly queued work");
        assertRungs(h, f.arena, f.cleanup, 6, 1);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void absentAndReplacedRungsAreNotRefundedOrDestroyed(GameTestHelper h) {
        Fixture f = fixture(h);
        exhaustBothPasses(h, f.arena, f.cleanup);
        BlockPos absent = f.cleanup.pos(1);
        BlockPos replaced = f.cleanup.pos(2);
        f.arena.level().setBlockAndUpdate(absent, Blocks.AIR.defaultBlockState());
        f.arena.level().setBlockAndUpdate(replaced, Blocks.BRICKS.defaultBlockState());
        BuildJobs.tick(f.arena.level(), f.arena.settlement());
        execute(h, f.arena, f.cleanup, 1);
        execute(h, f.arena, f.cleanup, 2);
        h.assertTrue(f.arena.level().getBlockState(absent).isAir()
            && f.arena.level().getBlockState(replaced).is(Blocks.BRICKS),
            "absent ladder stays absent and the player's replacement survives");
        h.assertTrue(BuilderTestKit.count(f.arena.chest(), Items.LADDER) == 1,
            "neither absent nor replaced ladder fabricates a refund");
        for (int i = 3; i < f.cleanup.size(); i++) execute(h, f.arena, f.cleanup, i);
        BuildJobs.finish(f.arena.level(), f.arena.settlement(), f.cleanup);
        h.assertTrue(f.cleanup.doneCount() == 7 && f.cleanup.skippedCount() == 0,
            "changed positions are safely resolved and remaining ladders removed");
        assertRungs(h, f.arena, f.cleanup, 0, 5);
        h.assertTrue(f.arena.level().getBlockState(replaced).is(Blocks.BRICKS), "cleanup preserves the replacement");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void mixedDismantleRetriesOnlyFlaggedScaffoldSteps(GameTestHelper h) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 16, 8);
        BlockPos rung = h.absolutePos(new BlockPos(9, 1, 9));
        BlockPos ordinary = rung.east(2);
        arena.level().setBlockAndUpdate(rung.north(), Blocks.STONE.defaultBlockState());
        arena.level().setBlockAndUpdate(rung, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        arena.level().setBlockAndUpdate(ordinary, Blocks.COBBLESTONE.defaultBlockState());
        BuildJob.Builder rows = new BuildJob.Builder();
        rows.add(rung, arena.level().getBlockState(rung), BuildPhase.DISMANTLE, BuildJob.F_SCAFFOLD, null, null);
        rows.add(ordinary, Blocks.COBBLESTONE.defaultBlockState(), BuildPhase.DISMANTLE, 0, null, null);
        BuildJob job = rows.build(UUID.randomUUID(), arena.settlement().id, BuildJob.Kind.DISMANTLE,
            "test_mixed_cleanup", "Mixed cleanup", rung, 0, false, null, 0L);
        h.assertTrue(BuildSiteSavedData.get(arena.level()).addSystem(job), "mixed job admitted");
        skipRemaining(job);
        BuildJobs.finish(arena.level(), arena.settlement(), job);
        skipRemaining(job);
        BuildJobs.finish(arena.level(), arena.settlement(), job);
        BuildJobs.tick(arena.level(), arena.settlement());
        int scaffoldIndex = job.hasFlag(0, BuildJob.F_SCAFFOLD) ? 0 : 1;
        int ordinaryIndex = 1 - scaffoldIndex;
        h.assertTrue(!job.isSkipped(scaffoldIndex) && job.isSkipped(ordinaryIndex),
            "ordinary skipped dismantle remains skipped during scaffold retry");
        execute(h, arena, job, scaffoldIndex);
        BuildJobs.finish(arena.level(), arena.settlement(), job);
        BuildJobs.tick(arena.level(), arena.settlement());
        h.assertTrue(job.state == BuildJob.State.COMPLETE && job.isSkipped(ordinaryIndex)
            && arena.level().getBlockState(ordinary).is(Blocks.COBBLESTONE),
            "general COMPLETE/SKIPPED semantics and unrelated blocks remain unchanged");
        h.assertTrue(BuilderTestKit.count(arena.chest(), Items.LADDER) == 1, "only the real ladder is returned");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void playerBreakAndPlaceCancelCompletedCleanupOwnership(GameTestHelper h) {
        Fixture f = fixture(h);
        exhaustBothPasses(h, f.arena, f.cleanup);
        var level = f.arena.level();
        var player = h.makeMockServerPlayerInLevel();
        BlockPos broken = f.cleanup.pos(1);
        var ladder = level.getBlockState(broken);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
            new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, broken, ladder, player));
        h.assertTrue(f.cleanup.isDone(1) && !f.cleanup.isSkipped(1),
            "the actual player break hook relinquishes completed cleanup ownership immediately");
        level.setBlockAndUpdate(broken, Blocks.AIR.defaultBlockState());
        var brokenSnapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, broken);
        level.setBlockAndUpdate(broken, ladder);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
            new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(brokenSnapshot,
                level.getBlockState(broken.north()), player));

        // Also exercise placement independently: an earlier removal need not have been a player break.
        BlockPos placed = f.cleanup.pos(2);
        level.setBlockAndUpdate(placed, Blocks.AIR.defaultBlockState());
        var placedSnapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, placed);
        level.setBlockAndUpdate(placed, ladder);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
            new net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent(placedSnapshot,
                level.getBlockState(placed.north()), player));
        h.assertTrue(f.cleanup.isDone(2) && !f.cleanup.isSkipped(2),
            "the actual player placement hook independently relinquishes completed cleanup ownership");
        h.assertTrue(BuilderTestKit.count(f.arena.chest(), Items.LADDER) == 1,
            "ownership cancellation itself refunds nothing");
        BuildJobs.tick(level, f.arena.settlement());
        for (int i = 0; i < f.cleanup.size(); i++) execute(h, f.arena, f.cleanup, i);
        BuildJobs.finish(level, f.arena.settlement(), f.cleanup);
        h.assertTrue(level.getBlockState(broken).equals(ladder) && level.getBlockState(placed).equals(ladder),
            "retry must leave both player-owned replacement ladders untouched");
        assertRungs(h, f.arena, f.cleanup, 2, 5);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = BATCH)
    public void failedCleanupRotationSurvivesReloadAndOffersTheNextColumn(GameTestHelper h) {
        Fixture f = fixture(h);
        exhaustBothPasses(h, f.arena, f.cleanup);
        var level = f.arena.level();
        BuildSiteSavedData data = BuildSiteSavedData.get(level);
        BuildJob source = ordinary(f.arena, 1L);
        h.assertTrue(data.add(source), "second source job admitted");
        BlockPos rung = h.absolutePos(new BlockPos(6, 1, 9));
        level.setBlockAndUpdate(rung.north(), Blocks.STONE.defaultBlockState());
        h.assertTrue(BuilderScaffold.hang(level, source, rung, Direction.SOUTH,
            new SimpleContainer(new ItemStack(Items.LADDER))), "the second column has a real paid rung");
        BuildJobs.act(level, f.arena.settlement(), source.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
        BuildJob second = data.activeJobs(f.arena.settlement().id).stream()
            .filter(job -> job.kind == BuildJob.Kind.DISMANTLE).findFirst().orElseThrow();
        skipRemaining(second);
        BuildJobs.finish(level, f.arena.settlement(), second);
        skipRemaining(second);
        BuildJobs.finish(level, f.arena.settlement(), second);
        h.assertTrue(second.state == BuildJob.State.COMPLETE && second.skippedCount() == 1,
            "both cleanup jobs have failed their original two passes");
        int entries = data.jobs(f.arena.settlement().id).size();
        BuildJobs.tick(level, f.arena.settlement());
        h.assertTrue(f.cleanup.state == BuildJob.State.ACTIVE && second.state == BuildJob.State.COMPLETE,
            "the earlier cleanup gets the first retry");
        skipRemaining(f.cleanup);
        BuildJobs.finish(level, f.arena.settlement(), f.cleanup);

        BuildSiteSavedData loaded = BuildSiteSavedData.load(data.save(new CompoundTag(), level.registryAccess()),
            level.registryAccess());
        level.getDataStorage().set("hearthstead_build_sites", loaded);
        BuildJob firstReloaded = loaded.job(f.arena.settlement().id, f.cleanup.id);
        BuildJob secondReloaded = loaded.job(f.arena.settlement().id, second.id);
        h.assertTrue(firstReloaded != null && secondReloaded != null
            && secondReloaded.order < firstReloaded.order, "retry rotation survives the actual NBT codec");
        BuildJobs.tick(level, f.arena.settlement());
        h.assertTrue(firstReloaded.state == BuildJob.State.COMPLETE && firstReloaded.skippedCount() == 6
            && secondReloaded.state == BuildJob.State.ACTIVE && secondReloaded.skippedCount() == 0,
            "a repeated first-column failure cannot starve the second column after reload");
        skipRemaining(secondReloaded);
        BuildJobs.finish(level, f.arena.settlement(), secondReloaded);
        BuildJobs.tick(level, f.arena.settlement());
        h.assertTrue(firstReloaded.state == BuildJob.State.ACTIVE && secondReloaded.state == BuildJob.State.COMPLETE,
            "failed retries rotate back instead of starving the earlier column");
        h.assertTrue(loaded.jobs(f.arena.settlement().id).size() == entries,
            "rotation never appends duplicate ownership records");
        h.assertTrue(level.getBlockState(rung).is(Blocks.LADDER), "rotation leaves the second physical rung intact");
        assertRungs(h, f.arena, firstReloaded, 6, 1);
        h.succeed();
    }

    private static Fixture fixture(GameTestHelper h) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(h, 16, 9);
        BuildJob source = ordinary(arena, 0L);
        BuildSiteSavedData data = BuildSiteSavedData.get(arena.level());
        h.assertTrue(data.add(source), "source job admitted");
        SimpleContainer materials = new SimpleContainer(new ItemStack(Items.LADDER, 7));
        for (int y = 1; y <= 7; y++) {
            BlockPos rung = h.absolutePos(new BlockPos(9, y, 9));
            arena.level().setBlockAndUpdate(rung.north(), Blocks.STONE.defaultBlockState());
            h.assertTrue(BuilderScaffold.hang(arena.level(), source, rung, Direction.SOUTH, materials),
                "the fixture physically hangs and pays for rung " + y);
        }
        h.assertTrue(materials.isEmpty(), "all seven supplied ladders became physical rungs");
        BuildJobs.act(arena.level(), arena.settlement(), source.id, BuildJobs.SiteAction.CANCEL_KEEP, null);
        BuildJob cleanup = data.jobs(arena.settlement().id).stream()
            .filter(job -> job.kind == BuildJob.Kind.DISMANTLE).findFirst().orElseThrow();
        h.assertTrue(source.scaffold.isEmpty() && cleanup.size() == 7, "the existing cleanup route transfers ownership");
        execute(h, arena, cleanup, 0);
        h.assertTrue(cleanup.isDone(0), "one rung already returned before the six remaining failures");
        assertRungs(h, arena, cleanup, 6, 1);
        return new Fixture(arena, cleanup);
    }

    private static BuildJob ordinary(BuilderTestKit.Arena arena, long age) {
        BuildJob.Builder rows = new BuildJob.Builder();
        rows.add(arena.settlement().center.offset(4, 0, 4), Blocks.COBBLESTONE.defaultBlockState(),
            BuildPhase.STRUCTURE, 0, null, null);
        return rows.build(UUID.randomUUID(), arena.settlement().id, BuildJob.Kind.BLUEPRINT,
            "test_scaffold_retry", "Ordinary work", arena.settlement().center, 0, false, null, age);
    }

    private static void exhaustBothPasses(GameTestHelper h, BuilderTestKit.Arena arena, BuildJob job) {
        skipRemaining(job);
        BuildJobs.finish(arena.level(), arena.settlement(), job);
        h.assertTrue(job.state == BuildJob.State.ACTIVE && job.secondPass && job.skippedCount() == 0,
            "the existing first-pass retry remains intact");
        skipRemaining(job);
        BuildJobs.finish(arena.level(), arena.settlement(), job);
        h.assertTrue(job.state == BuildJob.State.COMPLETE && job.skippedCount() == 6 && job.doneCount() == 1,
            "fixture reproduces exhausted second-pass cleanup with six live rungs");
        assertRungs(h, arena, job, 6, 1);
    }

    private static void skipRemaining(BuildJob job) {
        for (int i = 0; i < job.size(); i++) if (!job.isDone(i)) job.skipStep(i);
    }

    private static void execute(GameTestHelper h, BuilderTestKit.Arena arena, BuildJob job, int step) {
        SimpleContainer materials = new SimpleContainer(new ItemStack(Items.COBBLESTONE));
        h.assertTrue(BuildExecutor.execute(arena.level(), job, step, materials,
            List.of(arena.chest()), arena.hut().plaquePos) == BuildExecutor.Outcome.DONE,
            "actual executor resolves step " + step);
    }

    private static void assertRungs(GameTestHelper h, BuilderTestKit.Arena arena, BuildJob job,
                                    int installed, int returned) {
        int physical = 0;
        for (int i = 0; i < job.size(); i++) {
            if (arena.level().getBlockState(job.pos(i)).is(Blocks.LADDER)) physical++;
        }
        h.assertTrue(physical == installed && BuilderTestKit.count(arena.chest(), Items.LADDER) == returned,
            "physical rung and returned item counts are exact: installed=" + physical
                + " returned=" + BuilderTestKit.count(arena.chest(), Items.LADDER));
    }
}
