package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.BuilderNeedsNetwork;
import com.hearthstead.network.BuilderNeedsPayload;
import com.hearthstead.network.BuilderNeedsRequest;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.builder.*;
import com.hearthstead.settlement.request.RequestLedgerService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderNeedsGameTests {
    @GameTest(template = "empty16", timeoutTicks = 60, batch = "builder_needs")
    public void needsPayloadUsesRealBillStockAndPickedUpCargo(GameTestHelper h) {
        var arena = BuilderTestKit.arena(h, 16, 4);
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, arena, new BlockPos(4, 1, 4), "Needs builder");
        builder.setNoAi(true);
        BuildJob job = job(h, arena, builder);
        arena.level().setBlock(job.pos(0), job.state(0), 3);
        h.assertTrue(BuildExecutor.execute(arena.level(), job, 0, builder.bag, java.util.List.of(arena.chest()),
            builder.blockPosition()) == BuildExecutor.Outcome.DONE, "already placed stair completes");
        job.skipStep(5);
        job.setStatus(BuildStatus.WAITING_FOR, "2", "item:minecraft:oak_stairs", "4", "0");
        arena.chest().setItem(0, new ItemStack(Items.OAK_STAIRS, 2));
        builder.bag.setItem(0, new ItemStack(Items.OAK_STAIRS, 1));
        var warehouse = GameTestFixtures.register(h, arena.settlement(), BuildingType.WAREHOUSE, 9, 2);
        BlockPos source = h.absolutePos(new BlockPos(10, 1, 3));
        h.setBlock(new BlockPos(10, 1, 3), Blocks.CHEST);
        Container stock = (Container) arena.level().getBlockEntity(source);
        stock.setItem(0, new ItemStack(Items.OAK_STAIRS, 4));
        var opened = RequestLedgerService.openBuilderMaterial(arena.level(), arena.settlement(), warehouse,
            source, 0, arena.hut(), h.absolutePos(BuilderTestKit.HUT_CHEST), new ItemStack(Items.OAK_STAIRS), 1);
        h.assertTrue(opened.request() != null, "physical material request opens: " + opened);
        BuilderNeedsPayload before = BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true);
        BuilderPayloads.Stock stairs = before.materials().getFirst();
        h.assertTrue(stairs.item() == Items.OAK_STAIRS && stairs.needed() == 5 && stairs.inHut() == 3
            && stairs.inWarehouse() == 4 && stairs.onTheWay() == 0 && stairs.shortfall() == 2,
            "OPEN request is warehouse stock, not cargo: " + stairs);
        h.assertTrue(before.done() == 1 && before.total() == 6 && before.phase() == BuildPhase.STRUCTURE.ordinal(),
            "exact progress and phase are visible");
        h.assertTrue(before.helpCount() == 1 && before.help().getFirst().pos().equals(job.pos(5)),
            "skipped step exposes its exact coordinates");
        h.assertTrue(before.status() == BuildStatus.WAITING_FOR.ordinal() && before.statusArgs().equals(job.statusArgs),
            "status and its arguments are preserved");
        SettlerEntity courier = h.spawn(ModEntities.SETTLER.get(), new BlockPos(10, 1, 4));
        courier.bindTo(arena.settlement().id, arena.settlement().center);
        arena.settlement().putRecord(courier.getUUID(), "Needs courier", Profession.NONE);
        h.assertTrue(Employment.hire(arena.level(), arena.settlement(), warehouse, courier).ok(), "hire Courier");
        courier.setNoAi(true);
        h.assertTrue(RequestLedgerService.reserve(arena.level(), arena.settlement(), opened.request().id(), courier).accepted(),
            "reserve parcel");
        h.assertTrue(RequestLedgerService.pickup(arena.level(), arena.settlement(), opened.request().id(), courier).accepted(),
            "physical pickup");
        stairs = BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true).materials().getFirst();
        h.assertTrue(stairs.inWarehouse() == 3 && stairs.onTheWay() == 1 && stairs.shortfall() == 1,
            "picked-up unit changes store to cargo exactly once: " + stairs);
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "builder_needs")
    public void needsSheetRequiresMemberPermissionAndDistance(GameTestHelper h) {
        var arena = BuilderTestKit.arena(h, 16, 4);
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, arena, new BlockPos(4, 1, 4), "Private builder");
        var player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(builder.getX(), builder.getY(), builder.getZ());
        h.assertTrue(!BuilderNeedsNetwork.mayView(player, builder), "nonmember denied");
        arena.settlement().members.add(player.getUUID());
        h.assertTrue(BuilderNeedsNetwork.mayView(player, builder), "nearby member allowed");
        player.setGameMode(GameType.SPECTATOR);
        h.assertTrue(!BuilderNeedsNetwork.mayView(player, builder), "spectator denied");
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(builder.getX() + 25, builder.getY(), builder.getZ());
        h.assertTrue(!BuilderNeedsNetwork.mayView(player, builder), "distant member denied");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "builder_needs")
    public void largestShortageBeyondOldSiteCapRemainsVisible(GameTestHelper h) {
        var arena = BuilderTestKit.arena(h, 16, 4);
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, arena, new BlockPos(4, 1, 4), "Many materials");
        BuildJob.Builder steps = new BuildJob.Builder();
        var blocks = new net.minecraft.world.level.block.Block[] {Blocks.STONE, Blocks.COBBLESTONE,
            Blocks.DIRT, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.BRICKS,
            Blocks.STONE_BRICKS, Blocks.SANDSTONE, Blocks.GRANITE, Blocks.ANDESITE, Blocks.DIORITE,
            Blocks.QUARTZ_BLOCK};
        for (int i = 0; i < blocks.length; i++) steps.add(h.absolutePos(new BlockPos(i + 1, 1, 10)),
            blocks[i].defaultBlockState(), BuildPhase.STRUCTURE, 0, null, null);
        for (int i = 0; i < 10; i++) steps.add(h.absolutePos(new BlockPos(i + 1, 2, 10)),
            Blocks.GLASS.defaultBlockState(), BuildPhase.INTERIOR, 0, null, null);
        BuildJob job = steps.build(UUID.randomUUID(), arena.settlement().id, BuildJob.Kind.BLUEPRINT,
            "many_materials", "Many materials", h.absolutePos(new BlockPos(1, 1, 10)), 0, false, null,
            arena.level().getGameTime());
        h.assertTrue(BuildSiteSavedData.get(arena.level()).add(job), "queued bill");
        BuilderNeedsPayload data = BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true);
        h.assertTrue(data.queued() && data.materialCount() == 14 && data.materials().size() == 14,
            "all bill types survive the old 12-row Sites cap");
        h.assertTrue(data.materials().getFirst().item() == Items.GLASS
            && data.materials().getFirst().shortfall() == 10, "largest shortage sorts before the visible-row cap");
        job.claimant = UUID.randomUUID();
        job.leaseUntil = arena.level().getGameTime() + 600;
        h.assertTrue(BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true).total() == 0,
            "a different Builder's active lease is not presented as this Builder's site");
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 120, batch = "builder_needs")
    public void repeatedOpensRefreshesAndCloseShareOneSnapshotBudget(GameTestHelper h) {
        var arena = BuilderTestKit.arena(h, 16, 4);
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, arena, new BlockPos(4, 1, 4), "Rate limited");
        builder.setNoAi(true);
        var player = h.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(builder.getX(), builder.getY(), builder.getZ());
        arena.settlement().members.add(player.getUUID());
        UUID token = BuilderNeedsNetwork.open(player, builder);
        h.assertTrue(token != null, "initial open admitted");
        h.assertTrue(BuilderNeedsNetwork.open(player, builder) == null, "repeated Shift cannot rebuild or replace session");
        h.assertTrue(!BuilderNeedsNetwork.handle(player, request(builder, token, BuilderNeedsRequest.REFRESH)),
            "open and refresh share their budget");
        h.assertTrue(BuilderNeedsNetwork.handle(player, request(builder, token, BuilderNeedsRequest.CLOSE)),
            "original token remains valid after rejected open");
        h.assertTrue(BuilderNeedsNetwork.open(player, builder) == null, "CLOSE cannot reset snapshot budget");
        h.runAfterDelay(40, () -> {
            UUID reopened = BuilderNeedsNetwork.open(player, builder);
            h.assertTrue(reopened != null && !reopened.equals(token), "reopen admitted after cooldown");
            h.runAfterDelay(40, () -> {
                h.assertTrue(BuilderNeedsNetwork.handle(player, request(builder, reopened, BuilderNeedsRequest.REFRESH)),
                    "refresh admitted after cooldown");
                h.assertTrue(BuilderNeedsNetwork.open(player, builder) == null, "refresh also throttles subsequent opens");
                h.assertTrue(BuilderNeedsNetwork.handle(player, request(builder, reopened, BuilderNeedsRequest.CLOSE)),
                    "close refreshed session");
                h.assertTrue(BuilderNeedsNetwork.open(player, builder) == null, "refresh budget survives close");
                h.succeed();
            });
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 60, batch = "builder_needs")
    public void nextSiteSkipsPausedAndBlockedOnlyJobsLikeActualClaims(GameTestHelper h) {
        var arena = BuilderTestKit.arena(h, 16, 4);
        SettlerEntity builder = BuilderTestKit.hireBuilder(h, arena, new BlockPos(4, 1, 4), "Queue reader");
        builder.setNoAi(true);
        BuildJob paused = singleJob(h, arena, "Paused first", 8, 0);
        paused.paused = true;
        BuildJob blocked = singleJob(h, arena, "Blocked second", 9, 1);
        arena.level().setBlock(blocked.pos(0), Blocks.BRICKS.defaultBlockState(), 3);
        builder.bag.setItem(0, new ItemStack(Items.OAK_STAIRS));
        h.assertTrue(BuildExecutor.execute(arena.level(), blocked, 0, builder.bag,
            java.util.List.of(arena.chest()), builder.blockPosition()) == BuildExecutor.Outcome.BLOCKED,
            "player bricks block the only step");
        h.assertTrue(blocked.exhausted() && blocked.blockedCount() == 1, "blocked-only fixture");
        BuildJob eligible = singleJob(h, arena, "Eligible third", 10, 2);
        var data = BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true);
        h.assertTrue(data.queued() && data.siteName().equals(eligible.label), "next site skips both ineligible jobs");
        h.assertTrue(paused.claimant == null && blocked.claimant == null && eligible.claimant == null,
            "viewing the queue does not claim any job");
        h.assertTrue(BuildJobs.claimNext(arena.level(), arena.settlement(), builder) == eligible,
            "actual Builder chooses the same site");
        BuildJobs.release(eligible, builder);
        eligible.paused = true;
        h.assertTrue(BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true).total() == 0,
            "only paused and blocked-only jobs yield no next site");
        blocked.allowOverwrite = true;
        h.assertTrue(BuilderNeedsNetwork.snapshot(arena.level(), builder, UUID.randomUUID(), true)
            .siteName().equals(blocked.label), "explicit overwrite permission makes the blocked site eligible");
        h.succeed();
    }

    private static BuilderNeedsRequest request(SettlerEntity builder, UUID token, int action) {
        return new BuilderNeedsRequest(builder.getId(), builder.getUUID(), token, action);
    }

    private static BuildJob singleJob(GameTestHelper h, BuilderTestKit.Arena arena, String name, int x, int order) {
        var steps = new BuildJob.Builder();
        BlockPos at = h.absolutePos(new BlockPos(x, 1, 10));
        steps.add(at, Blocks.OAK_STAIRS.defaultBlockState(), BuildPhase.STRUCTURE, 0, null, null);
        BuildJob job = steps.build(UUID.randomUUID(), arena.settlement().id, BuildJob.Kind.BLUEPRINT,
            "needs_queue", name, at, 0, false, null, arena.level().getGameTime());
        h.assertTrue(BuildSiteSavedData.get(arena.level()).add(job), "queue fixture recorded");
        job.order = order;
        return job;
    }

    private static BuildJob job(GameTestHelper h, BuilderTestKit.Arena arena, SettlerEntity builder) {
        BuildJob.Builder steps = new BuildJob.Builder();
        for (int i = 0; i < 6; i++) steps.add(h.absolutePos(new BlockPos(8 + i, 1, 10)),
            Blocks.OAK_STAIRS.defaultBlockState(), BuildPhase.STRUCTURE, 0, null, null);
        BuildJob job = steps.build(UUID.randomUUID(), arena.settlement().id, BuildJob.Kind.BLUEPRINT,
            "needs_test", "Needs test stairs", h.absolutePos(new BlockPos(8, 1, 10)), 0, false, null,
            arena.level().getGameTime());
        h.assertTrue(BuildSiteSavedData.get(arena.level()).add(job), "site recorded");
        job.claimant = builder.getUUID();
        job.leaseUntil = arena.level().getGameTime() + 600;
        return job;
    }
}
