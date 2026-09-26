package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.SettlerDoorGoal;
import com.hearthstead.entity.path.RoadNodeEvaluator;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class IndoorRouteGameTests {
    @GameTest(template = "empty64", batch = "indoor_route", timeoutTicks = 2000)
    public void upstairsWorkerWalksRoomsStairsAndDoorsBothWays(GameTestHelper helper) {
        house(helper);
        SettlerEntity walker = walker(helper);
        BlockPos upstairs = helper.absolutePos(new BlockPos(3, 6, 3));
        BlockPos outside = helper.absolutePos(new BlockPos(3, 1, 0));
        BlockPos bedroomDoor = helper.absolutePos(new BlockPos(5, 6, 3));
        BlockPos roomDoor = helper.absolutePos(new BlockPos(2, 1, 7));
        BlockPos exitDoor = helper.absolutePos(new BlockPos(3, 1, 1));
        BlockPos farHall = helper.absolutePos(new BlockPos(34, 6, 7));
        boolean[] observed = new boolean[6];
        boolean[] returning = {false};
        boolean[] refreshed = {false};
        long[] refreshTick = {-1};
        boolean[] refreshVerified = {false};
        Path[] returnPath = {null};
        long[] returnStartTick = {-1};
        boolean[] returnRecovery = {false};
        String[] firstReturnPathLoss = {null};
        boolean[] returnDoorOpened = new boolean[3];
        StringBuilder returnTrace = new StringBuilder();
        float health = walker.getHealth();
        GameTestTicks.at(helper, 5, () -> {
            Path ordinary = new OrdinaryNavigation(walker).createPath(outside, 0);
            helper.assertTrue(ordinary != null && !ordinary.canReach(),
                "the same real house must exceed the ordinary 32-block route budget");
            start(helper, walker, outside);
        });
        helper.onEachTick(() -> {
            if (helper.getTick() < 5) return;
            observed[0] |= open(helper, bedroomDoor);
            observed[1] |= open(helper, roomDoor);
            observed[2] |= open(helper, exitDoor);
            observed[3] |= close(walker, farHall);
            for (int x = 3; x <= 7; x++) {
                observed[4] |= close(walker,
                    helper.absolutePos(new BlockPos(x, x - 2, 9)));
            }
            // The production door goal also refreshes on open. Force a second
            // real refresh while still far from the downstairs goal, without
            // replacing the destination or moving the actor in the fixture.
            if (!refreshed[0] && observed[0] && helper.getTick() > 45
                    && close(walker, farHall)
                    && walker.blockPosition().distSqr(outside) > 32.0 * 32.0) {
                refreshed[0] = true;
                refreshTick[0] = helper.getTick();
                walker.getNavigation().recomputePath();
            }
            if (refreshed[0] && !refreshVerified[0] && helper.getTick() >= refreshTick[0] + 25) {
                Path refreshedPath = walker.getNavigation().getPath();
                helper.assertTrue(refreshedPath != null && refreshedPath.canReach()
                        && refreshedPath.getTarget().equals(outside),
                    "live route refresh must retain the long indoor detour");
                refreshVerified[0] = true;
            }
            if (!returning[0] && close(walker, outside)) {
                observed[5] = true;
                returning[0] = true;
                // Let the exit-door goal finish the passage it just opened
                // before a new destination asks it to own that same door in
                // the opposite direction. The worker must still walk the
                // complete physical return route below.
                helper.runAfterDelay(2, () -> {
                    returnPath[0] = start(helper, walker, upstairs);
                    returnStartTick[0] = helper.getTick();
                });
            }
            // Real worker goals retry their owned destination after a stalled
            // navigation sample. Exercise that recovery after the prior door
            // passage has fully settled; it must still physically climb the
            // same stairs and cross the same doors.
            // The navigator no longer goes idle part-way (stuck recovery and
            // door refreshes keep the route alive), so re-issue the route
            // mid-return exactly as a goal's repath interval would.
            if (returnStartTick[0] >= 0 && !returnRecovery[0]
                    && helper.getTick() >= returnStartTick[0] + 40
                    && !close(walker, upstairs)) {
                returnRecovery[0] = true;
                returnPath[0] = start(helper, walker, upstairs);
            }
            if (returnStartTick[0] >= 0 && helper.getTick() > returnStartTick[0]
                    && !close(walker, upstairs) && walker.getNavigation().getPath() == null
                    && firstReturnPathLoss[0] == null) {
                firstReturnPathLoss[0] = "tick=" + helper.getTick()
                    + ",pos=" + helper.relativePos(walker.blockPosition())
                    + ",bedroomOpen=" + open(helper, bedroomDoor)
                    + ",roomOpen=" + open(helper, roomDoor)
                    + ",exitOpen=" + open(helper, exitDoor);
            }
            if (returnStartTick[0] >= 0) {
                returnDoorOpened[0] |= open(helper, bedroomDoor);
                returnDoorOpened[1] |= open(helper, roomDoor);
                returnDoorOpened[2] |= open(helper, exitDoor);
            }
            if (returnStartTick[0] >= 0 && helper.getTick() % 20 == 0
                    && returnTrace.length() < 5000) {
                Path live = walker.getNavigation().getPath();
                returnTrace.append("t=").append(helper.getTick())
                    .append(",pos=").append(helper.relativePos(walker.blockPosition()))
                    .append(",next=").append(live == null || live.isDone() ? null : live.getNextNodePos())
                    .append(",index=").append(live == null ? null : live.getNextNodeIndex())
                    .append(",doors=").append(open(helper, bedroomDoor))
                    .append('/').append(open(helper, roomDoor))
                    .append('/').append(open(helper, exitDoor)).append(';');
            }
            if (helper.getTick() % 100 == 0) {
                Hearthstead.LOGGER.info("HSQA_INDOOR_ROUTE actor={} return={} pos={} pathReach={} next={}",
                    walker.getUUID(), returning[0], walker.blockPosition(),
                    walker.getNavigation().getPath() != null && walker.getNavigation().getPath().canReach(),
                    walker.getNavigation().getPath() == null ? null : walker.getNavigation().getPath().getNextNodeIndex());
            }
        });
        helper.succeedWhen(() -> {
            for (boolean checkpoint : observed) helper.assertTrue(checkpoint,
                "must physically pass the far corridor, stairs, all three doors and outside; pos="
                    + helper.relativePos(walker.blockPosition()));
            helper.assertTrue(refreshVerified[0] && returning[0] && returnRecovery[0] && close(walker, upstairs),
                "must walk back upstairs after live route refresh and actual outside arrival: return="
                    + brief(returnPath[0]) + ", live=" + brief(walker.getNavigation().getPath())
                    + ", pos=" + helper.relativePos(walker.blockPosition())
                    + ", firstPathLoss=" + firstReturnPathLoss[0]
                    + ", returnDoorOpened=" + java.util.Arrays.toString(returnDoorOpened)
                    + ", trace=" + returnTrace);
            helper.assertTrue(walker.getHealth() >= health, "stairs must not be replaced by a damaging drop");
            helper.assertTrue(!open(helper, roomDoor) && !open(helper, exitDoor),
                "downstairs doors must close after the worker returns upstairs");
        });
    }

    @GameTest(template = "empty64", batch = "indoor_sealed_route", timeoutTicks = 120)
    public void sealedStairDoesNotBecomeAReachableExit(GameTestHelper helper) {
        house(helper);
        for (int y = 6; y <= 9; y++) helper.setBlock(new BlockPos(8, y, 9), Blocks.STONE_BRICKS);
        SettlerEntity walker = walker(helper);
        BlockPos outside = helper.absolutePos(new BlockPos(3, 1, 0));
        GameTestTicks.at(helper, 5, () -> {
            Path route = walker.getNavigation().createPath(outside, 0);
            helper.assertTrue(route == null || !route.canReach(),
                "a sealed stair must remain unreachable despite expanded search");
            helper.assertTrue(!close(walker, outside), "planning must not teleport the worker outside");
            helper.succeed();
        });
    }

    private static Path start(GameTestHelper helper, SettlerEntity walker, BlockPos target) {
        Path path = walker.getNavigation().createPath(target, 0);
        Path generous = path == null || !path.canReach()
            ? new DiagnosticNavigation(walker).probe(target) : null;
        String stages = "";
        if (path == null || !path.canReach()) {
            for (BlockPos checkpoint : java.util.List.of(new BlockPos(6, 6, 3),
                    new BlockPos(34, 6, 7), new BlockPos(8, 6, 9), new BlockPos(2, 1, 9))) {
                Path probe = new DiagnosticNavigation(walker).probe(helper.absolutePos(checkpoint));
                stages += " checkpoint=" + checkpoint + " " + describe(probe);
            }
            stages += " range192=" + describe(new DiagnosticNavigation(walker).probeWide(target));
        }
        helper.assertTrue(path != null && path.canReach(), "complete indoor route must exist to " + target
            + " actual=" + describe(path) + " largerBudget=" + describe(generous) + stages
            + " actorPos=" + walker.position() + " actorBlock=" + walker.blockPosition()
            + " onGround=" + walker.onGround() + " onClimbable=" + walker.onClimbable()
            + " velocity=" + walker.getDeltaMovement()
            + " support=" + walker.level().getBlockState(walker.blockPosition().below()));
        helper.assertTrue(walker.getNavigation().moveTo(path, 1.0), "physical route must start");
        return path;
    }

    private static boolean close(SettlerEntity walker, BlockPos target) {
        return walker.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < .64;
    }

    private static boolean open(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(pos).getValue(DoorBlock.OPEN);
    }

    private static SettlerEntity walker(GameTestHelper helper) {
        SettlerEntity walker = ModEntities.SETTLER.get().create(helper.getLevel());
        BlockPos start = helper.absolutePos(new BlockPos(3, 6, 3));
        walker.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5, 0, 0);
        // Keep the actual door opener. Remove unrelated idle/work selectors so
        // they cannot manufacture success by replacing the single requested route.
        for (var goal : java.util.List.copyOf(walker.goalSelector.getAvailableGoals())) {
            if (!(goal.getGoal() instanceof SettlerDoorGoal)) walker.goalSelector.removeGoal(goal.getGoal());
        }
        helper.getLevel().addFreshEntity(walker);
        return walker;
    }

    private static void house(GameTestHelper helper) {
        for (int x = 0; x < 40; x++) for (int z = 0; z <= 13; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 10; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        for (int x = 1; x <= 37; x++) for (int z = 1; z <= 12; z++) {
            helper.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(x, 10, z), Blocks.OAK_PLANKS);
            if (x == 1 || x == 37 || z == 1 || z == 12) {
                for (int y = 1; y <= 9; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
            }
        }
        // Upper U-shaped hall: first walk away from the downstairs destination.
        for (int x = 1; x <= 33; x++) for (int y = 6; y <= 9; y++)
            helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE_BRICKS);
        for (int z = 2; z <= 5; z++) for (int y = 6; y <= 9; y++)
            helper.setBlock(new BlockPos(5, y, z), Blocks.STONE_BRICKS);
        for (int x = 2; x <= 36; x++) for (int y = 1; y <= 4; y++)
            helper.setBlock(new BlockPos(x, y, 7), Blocks.STONE_BRICKS);
        // Real, supported oak staircase. Rails and shaft walls forbid a
        // shortcut by dropping from the upper floor beside the steps.
        for (int x = 3; x <= 7; x++) {
            helper.setBlock(new BlockPos(x, 5, 9), Blocks.AIR);
            for (int y = 1; y < x - 2; y++) helper.setBlock(new BlockPos(x, y, 9), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x, x - 2, 9), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST));
            for (int y = 1; y <= 9; y++) for (int z : new int[]{8, 10})
                helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
        }
        for (int y = 6; y <= 9; y++) helper.setBlock(new BlockPos(2, y, 9), Blocks.STONE_BRICKS);
        door(helper, new BlockPos(5, 6, 3), Direction.EAST);
        door(helper, new BlockPos(2, 1, 7), Direction.NORTH);
        door(helper, new BlockPos(3, 1, 1), Direction.NORTH);
    }

    private static void door(GameTestHelper helper, BlockPos pos, Direction facing) {
        var lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(pos, lower);
        helper.setBlock(pos.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** Same road evaluator and door policy, with the original vanilla budget. */
    static final class OrdinaryNavigation extends GroundPathNavigation {
        OrdinaryNavigation(SettlerEntity walker) { super(walker, walker.level()); }
        @Override protected PathFinder createPathFinder(int maximum) {
            nodeEvaluator = new RoadNodeEvaluator();
            nodeEvaluator.setCanPassDoors(true);
            nodeEvaluator.setCanOpenDoors(true);
            return new PathFinder(nodeEvaluator, maximum);
        }
    }

    private static String describe(Path path) {
        if (path == null) return "null";
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++) nodes.append(path.getNodePos(i)).append(' ');
        return "reach=" + path.canReach() + " count=" + path.getNodeCount() + " nodes=" + nodes;
    }

    private static String brief(Path path) {
        if (path == null) return "null";
        int count = path.getNodeCount();
        return "reach=" + path.canReach() + ",count=" + count + ",target=" + path.getTarget()
            + ",end=" + (count == 0 ? null : path.getNodePos(count - 1));
    }

    private static final class DiagnosticNavigation extends com.hearthstead.entity.path.RoadNavigation {
        DiagnosticNavigation(SettlerEntity walker) { super(walker, walker.level()); }
        Path probe(BlockPos target) {
            setMaxVisitedNodesMultiplier(8.0F);
            return createPath(java.util.Set.of(target), 8, false, 0, 96.0F);
        }
        Path probeWide(BlockPos target) {
            setMaxVisitedNodesMultiplier(8.0F);
            return createPath(java.util.Set.of(target), 8, false, 0, 192.0F);
        }
    }
}
