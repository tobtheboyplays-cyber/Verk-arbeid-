package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.PathWear;
import com.hearthstead.entity.path.RoadNodeEvaluator;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Settlers keep to the road, and the road appears where they walk.
 *
 * <p>Owner's ask, 2026-08-25: <i>"Vil ogsÃ¥ at villagerne skal fÃ¸lge stier etter
 * beste evne. Vakter slipper selvfÃ¸lgelig om det er mobs i nÃ¦rheten."</i>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class PathGameTests {

    /** Actual nbWHjC doorway geometry and fractional approach; baseline reproduction unrun. */
    @GameTest(batch="tavern_double_door", template="empty32", timeoutTicks=200)
    public void tavernDoubleDoorGroundEntryFromCorner(GameTestHelper helper) {
        meadow(helper,32);
        for(int x=4;x<=17;x++) for(int z=4;z<=17;z++) {
            helper.setBlock(new BlockPos(x,4,z),Blocks.OAK_PLANKS);
            if(x==4||x==17||z==4||z==17) for(int y=1;y<=3;y++)
                helper.setBlock(new BlockPos(x,y,z),Blocks.OAK_PLANKS);
        }
        for(int x:new int[]{5,6}) {
            var state=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.NORTH);
            helper.setBlock(new BlockPos(x,1,4),state);
            helper.setBlock(new BlockPos(x,2,4),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
        }
        SettlerEntity walker=settler(helper,new BlockPos(6,1,3));
        var origin=helper.absolutePos(new BlockPos(0,0,0));
        walker.moveTo(origin.getX()+6.224,origin.getY()+1,origin.getZ()+3.525,0,0);
        BlockPos target=helper.absolutePos(new BlockPos(11,1,11));
        OneShotMoveGoal route=new OneShotMoveGoal(walker,target);
        java.util.ArrayDeque<String> entryTimeline=new java.util.ArrayDeque<>();
        helper.runAtTickTime(5L,()->walker.goalSelector.addGoal(0,route));
        for(long sampleTick=20L;sampleTick<=180L;sampleTick+=20L) {
            helper.runAtTickTime(sampleTick,()->{
                if(entryTimeline.size()==9) entryTimeline.removeFirst();
                entryTimeline.addLast(tavernEntrySnapshot(helper,walker,target,route));
            });
        }
        helper.runAtTickTime(195L,()->{
            if(walker.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(target))<=2.25
                && Math.abs(walker.getY()-(origin.getY()+1))<.01) return;
            for(String snapshot:entryTimeline)
                Hearthstead.LOGGER.error("TAVERN_ENTRY_TIMELINE {}",snapshot);
            Hearthstead.LOGGER.error("TAVERN_ENTRY_FAILURE {}",tavernEntrySnapshot(helper,walker,target,route));
        });
        helper.succeedWhen(()->{
            helper.assertTrue(walker.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(target))<=2.25
                && Math.abs(walker.getY()-(origin.getY()+1))<.01,
                "actual ground entry must reach interior within the existing200tick visit approach budget; pos="+walker.position());
        });
    }

    /**
     * A real elevated home route: two sequential closed doors and a three-step
     * stair. It pins the live failure shape rather than treating a bed as
     * reachable through a wall or releasing the home claim.
     */
    @GameTest(batch="resident_two_door_stair", template="empty16", timeoutTicks=360)
    public void settlerPassesTwoDoorsAndStairsToAnUpperGoal(GameTestHelper helper) {
        meadow(helper, 16);
        // Lower one-wide corridor. Its only exit is the first closed door.
        for (int x = 1; x <= 11; x++) {
            for (int y = 1; y <= 6; y++) {
                helper.setBlock(new BlockPos(x, y, 6), Blocks.OAK_PLANKS);
                helper.setBlock(new BlockPos(x, y, 10), Blocks.OAK_PLANKS);
            }
        }
        for (int z = 6; z <= 10; z++) {
            for (int y = 1; y <= 6; y++) {
                helper.setBlock(new BlockPos(1, y, z), Blocks.OAK_PLANKS);
            }
        }
        BlockPos firstDoor = new BlockPos(5, 1, 8);
        placeClosedDoor(helper, firstDoor, Direction.EAST);

        // Physical oak stairs rise from lower feet y=1 to upper feet y=4.
        helper.setBlock(new BlockPos(6, 1, 8), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(7, 2, 8), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST));
        helper.setBlock(new BlockPos(8, 3, 8), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST));
        for (int x = 9; x <= 14; x++) {
            for (int z = 7; z <= 9; z++) helper.setBlock(new BlockPos(x, 3, z), Blocks.OAK_PLANKS);
        }
        // Second wall closes the upper landing; no side route reaches target.
        for (int z = 6; z <= 10; z++) {
            for (int y = 4; y <= 6; y++) helper.setBlock(new BlockPos(11, y, z), Blocks.OAK_PLANKS);
        }
        BlockPos secondDoor = new BlockPos(11, 4, 8);
        placeClosedDoor(helper, secondDoor, Direction.EAST);

        SettlerEntity walker = settler(helper, new BlockPos(3, 1, 8));
        BlockPos target = helper.absolutePos(new BlockPos(13, 4, 8));
        OneShotMoveGoal route = new OneShotMoveGoal(walker, target);
        boolean[] firstOpened = {false};
        boolean[] secondOpened = {false};
        helper.runAtTickTime(5L, () -> walker.goalSelector.addGoal(0, route));
        helper.onEachTick(() -> {
            firstOpened[0] |= doorOpen(helper, firstDoor);
            secondOpened[0] |= doorOpen(helper, secondDoor);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(firstOpened[0] && secondOpened[0],
                "the worker must physically open both closed home doors"
                    + " [pos=" + walker.blockPosition().toShortString()
                    + ", first=" + doorOpen(helper, firstDoor)
                    + ", second=" + doorOpen(helper, secondDoor) + "]");
            helper.assertTrue(walker.blockPosition().getY() >= target.getY()
                    && walker.distanceToSqr(target.getX() + .5D, target.getY(),
                        target.getZ() + .5D) <= 2.25D,
                "the worker must climb the real stairs and reach the upper goal"
                    + " [pos=" + walker.blockPosition().toShortString()
                    + ", route=" + walker.routeFailureNote() + "]");
        });
    }

    private static void placeClosedDoor(GameTestHelper helper, BlockPos lower,
                                        Direction facing) {
        var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing);
        helper.setBlock(lower, state);
        helper.setBlock(lower.above(), state.setValue(DoorBlock.HALF,
            DoubleBlockHalf.UPPER));
    }

    /** Failure-only state recorder for the real 200-tick double-door route. */
    private static String tavernEntrySnapshot(GameTestHelper helper,
            SettlerEntity walker, BlockPos target, OneShotMoveGoal route) {
        Path path=walker.getNavigation().getPath();
        String nav=path==null ? "null" : "done="+path.isDone()+",reach="+path.canReach()
            +",next="+path.getNextNodeIndex()+",nodes="+path.getNodeCount()
            +",target="+path.getTarget();
        StringBuilder goals=new StringBuilder();
        for(net.minecraft.world.entity.ai.goal.WrappedGoal wrapped:walker.goalSelector.getAvailableGoals()) {
            if(!wrapped.isRunning()) continue;
            if(!goals.isEmpty()) goals.append('|');
            goals.append(wrapped.getGoal().getClass().getSimpleName())
                .append(wrapped.getFlags());
        }
        StringBuilder doors=new StringBuilder();
        for(int x:new int[]{5,6}) {
            BlockPos door=helper.absolutePos(new BlockPos(x,1,4));
            var state=helper.getLevel().getBlockState(door);
            if(!doors.isEmpty()) doors.append(';');
            doors.append(door).append('=').append(state)
                .append(" shape=").append(state.getCollisionShape(helper.getLevel(),door).toAabbs());
        }
        return "tick="+helper.getTick()+" actor="+walker.getUUID()
            +" pos="+walker.position()+" box="+walker.getBoundingBox()
            +" onGround="+walker.onGround()+" collision="+walker.horizontalCollision
            +" routeAttempted="+route.attempted()+" routeAccepted="+route.accepted()
            +" nav="+nav+" navTarget="+walker.getNavigation().getTargetPos()
            +" requestedDoor="+walker.requestedDoorPassage().orElse(null)
            +" goals="+goals+" doors="+doors;
    }
    private static void meadow(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static SettlerEntity settler(GameTestHelper helper, BlockPos rel) {
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Gjenger");
        settler.bindTo(s.id, s.center);
        return settler;
    }

    /**
     * The point of the whole feature: a settler accepts a longer walk to stay
     * on the path a player dug with a shovel.
     *
     * <p>The road here is a deliberate detour â€” two steps aside, across, and
     * two steps back, against a straight line over grass. If the preference
     * were merely cosmetic the pathfinder would cut across and this fails.
     */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 300)
    public void settlersTakeTheLongWayRoundToStayOnTheRoad(GameTestHelper helper) {
        meadow(helper, 16);
        // A road from (2,8) north to row 6, along row 6, and back down at x=13.
        for (int z = 6; z <= 8; z++) {
            helper.setBlock(new BlockPos(2, 0, z), Blocks.DIRT_PATH);
            helper.setBlock(new BlockPos(13, 0, z), Blocks.DIRT_PATH);
        }
        for (int x = 2; x <= 13; x++) {
            helper.setBlock(new BlockPos(x, 0, 6), Blocks.DIRT_PATH);
        }

        SettlerEntity walker = settler(helper, new BlockPos(2, 1, 8));
        BlockPos target = helper.absolutePos(new BlockPos(13, 1, 8));

        // Asked for on a later tick, not at tick zero: a settler spawned this
        // instant is not yet standing on anything, and navigation for a mob
        // that is still falling returns null.
        helper.succeedWhen(() -> {
            Path path = walker.getNavigation().createPath(target, 1);
            helper.assertTrue(path != null, "a walkable meadow must yield a path");
            int onRoad = 0;
            int total = path.getNodeCount();
            for (int i = 0; i < total; i++) {
                var node = path.getNode(i);
                if (helper.getLevel().getBlockState(
                    new BlockPos(node.x, node.y - 1, node.z)).is(Blocks.DIRT_PATH)) {
                    onRoad++;
                }
            }
            helper.assertTrue(total > 12,
                "the straight line is about 11 steps; taking the road must cost "
                    + "more than that, got " + total);
            helper.assertTrue(onRoad * 2 > total,
                "most of the walk must be on the road, got " + onRoad
                    + " of " + total);
        });
    }

    /** Re-expanding real cached neighbors must not compound road preference. */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 200)
    public void roadPenaltyIsStableAcrossExpansionsAndSearches(GameTestHelper helper) {
        meadow(helper, 16);
        SettlerEntity guard = settler(helper, new BlockPos(8, 1, 8));
        guard.assignProfession(Profession.GUARD);
        RoadNodeEvaluator evaluator = new RoadNodeEvaluator();

        helper.succeedWhen(() -> {
            helper.assertTrue(guard.onGround(),
                "the real evaluator fixture must first settle on its meadow");
            // Match WalkNodeEvaluator's grounded start height, including a
            // dirt path's fractional surface; no entity relocation is needed.
            BlockPos feet = BlockPos.containing(guard.getX(),
                Math.floor(guard.getY() + 0.5D), guard.getZ());
            BlockPos road = feet.east();
            BlockPos grass = feet.west();
            helper.getLevel().setBlockAndUpdate(road.below(),
                Blocks.DIRT_PATH.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(grass.below(),
                Blocks.GRASS_BLOCK.defaultBlockState());
            float previousMalus = guard.getPathfindingMalus(PathType.WALKABLE);
            SettlerActivity previousActivity = guard.getActivity();
            try {
                guard.setActivity(SettlerActivity.IDLE);
                guard.setPathfindingMalus(PathType.WALKABLE, 0.0F);
                Node zeroCost = assertRepeatedRoadCosts(helper, evaluator,
                    guard, feet, road, grass, 0.0F, true);

                guard.setPathfindingMalus(PathType.WALKABLE, 4.0F);
                Node terrainCost = assertRepeatedRoadCosts(helper, evaluator,
                    guard, feet, road, grass, 4.0F, true);
                helper.assertTrue(terrainCost != zeroCost,
                    "prepare must create fresh nodes for the next search");

                guard.setActivity(SettlerActivity.COMBAT);
                assertRepeatedRoadCosts(helper, evaluator, guard, feet,
                    road, grass, 4.0F, false);
                guard.setActivity(SettlerActivity.FLEEING);
                assertRepeatedRoadCosts(helper, evaluator, guard, feet,
                    road, grass, 4.0F, false);
                guard.setActivity(SettlerActivity.IDLE);
                Node resumed = assertRepeatedRoadCosts(helper, evaluator,
                    guard, feet, road, grass, 4.0F, true);
                helper.assertTrue(resumed != terrainCost,
                    "a later patrol search must use fresh, once-penalized nodes");
            } finally {
                guard.setPathfindingMalus(PathType.WALKABLE, previousMalus);
                guard.setActivity(previousActivity);
            }
        });
    }

    private static Node assertRepeatedRoadCosts(GameTestHelper helper,
            RoadNodeEvaluator evaluator, SettlerEntity guard, BlockPos feet,
            BlockPos road, BlockPos grass, float terrainMalus,
            boolean prefersRoads) {
        helper.assertTrue(guard.prefersRoads() == prefersRoads,
            "the test must exercise the actual requested road preference");
        PathNavigationRegion region = new PathNavigationRegion(helper.getLevel(),
            feet.offset(-3, -1, -3), feet.offset(3, 3, 3));
        evaluator.prepare(region, guard);
        try {
            Node parent = evaluator.getStart();
            Node[] neighbors = new Node[32];
            Node firstGrass = null;
            Node firstRoad = null;
            for (int expansion = 0; expansion < 4; expansion++) {
                int count = evaluator.getNeighbors(neighbors, parent);
                Node grassNode = neighborAt(neighbors, count, grass);
                Node roadNode = neighborAt(neighbors, count, road);
                helper.assertTrue(grassNode != null && roadNode != null,
                    "real vanilla enumeration must return both physical surfaces");
                helper.assertTrue(grassNode.type == PathType.WALKABLE
                        && roadNode.type == PathType.WALKABLE,
                    "both candidates must retain their actual walkable terrain type");
                float expectedGrass = terrainMalus
                    + (prefersRoads ? RoadNodeEvaluator.OFF_ROAD_MALUS : 0.0F);
                helper.assertTrue(grassNode.costMalus == expectedGrass,
                    "off-road cost must remain terrain plus one surcharge; expansion="
                        + expansion + "; expected=" + expectedGrass
                        + "; actual=" + grassNode.costMalus);
                helper.assertTrue(roadNode.costMalus == terrainMalus,
                    "a dirt path must retain terrain cost without a road surcharge");
                if (firstGrass == null) {
                    firstGrass = grassNode;
                    firstRoad = roadNode;
                } else {
                    helper.assertTrue(grassNode == firstGrass && roadNode == firstRoad,
                        "repeated expansion must prove reuse of the same cached nodes");
                }
            }
            return firstGrass;
        } finally {
            evaluator.done();
        }
    }

    private static Node neighborAt(Node[] neighbors, int count, BlockPos position) {
        for (int index = 0; index < count; index++) {
            Node node = neighbors[index];
            if (node.x == position.getX() && node.y == position.getY()
                    && node.z == position.getZ()) return node;
        }
        return null;
    }

    /** Peripheral flowing water must not put a grounded dry-centre start
     * inside its solid support. Observe real fluid/gravity flags, never set them. */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 300)
    public void peripheralWaterKeepsGroundedStartAboveSupportAndWalks(GameTestHelper helper) {
        meadow(helper, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // Source water must actually flow seven cells along this flat channel.
        // Its level-7 edge cannot spread horizontally into the dry exit cell.
        helper.setBlock(new BlockPos(0, 1, 8), Blocks.STONE_BRICKS);
        for (int x = 1; x <= 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 7), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x, 1, 9), Blocks.STONE_BRICKS);
        }
        helper.setBlock(new BlockPos(1, 1, 8), Blocks.WATER);
        BlockPos edge = helper.absolutePos(new BlockPos(8, 1, 8));
        BlockPos dry = helper.absolutePos(new BlockPos(9, 1, 8));
        BlockPos target = helper.absolutePos(new BlockPos(12, 1, 8));
        SettlerEntity[] walker = {null};
        boolean[] observed = {false};
        boolean[] routed = {false};
        helper.onEachTick(() -> {
            if (walker[0] == null) {
                var water = helper.getLevel().getBlockState(edge);
                if (!water.is(Blocks.WATER)
                    || water.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 7
                    || !helper.getLevel().getBlockState(dry).isAir()) return;
                walker[0] = settler(helper, new BlockPos(9, 1, 8));
                SettlerEntity actor = walker[0];
                // One initial fixture placement, followed only by ordinary physics.
                // Width .60 overlaps x8 while the centre remains in dry x9.
                actor.setPos(dry.getX() + 0.10D, dry.getY() + 0.05D, dry.getZ() + 0.5D);
                actor.setDeltaMovement(0.0D, -0.08D, 0.0D);
                actor.setHunger(100.0F);
                actor.goalSelector.addGoal(0, new Goal() {
                    { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
                    @Override public boolean canUse() { return true; }
                    @Override public boolean requiresUpdateEveryTick() { return true; }
                    @Override public void tick() {
                        if (!observed[0] || routed[0]) return;
                        Path path = actor.getNavigation().createPath(target, 0);
                        helper.assertTrue(path != null && path.canReach()
                            && path.getNode(0).y == dry.getY(),
                            "real navigation must start above support and reach the dry destination");
                        helper.assertTrue(actor.getNavigation().moveTo(path, 1.0D),
                            "ordinary navigation must accept the verified dry exit route");
                        routed[0] = true;
                    }
                });
                return;
            }
            SettlerEntity actor = walker[0];
            if (observed[0] || !actor.onGround() || !actor.isInWater()
                || !actor.blockPosition().equals(dry)) return;
            var water = helper.getLevel().getBlockState(edge);
            if (!water.is(Blocks.WATER)
                || water.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 7
                || !helper.getLevel().getBlockState(dry).isAir()
                || actor.getBoundingBox().minX >= edge.getX() + 1.0D) return;
            helper.assertTrue(actor.getNavigation().canFloat()
                && actor.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 0.0D
                && helper.getLevel().noCollision(actor)
                && helper.getLevel().getBlockState(dry.below()).is(Blocks.STONE_BRICKS),
                "observed wet-AABB/dry-centre actor must have actual clear supported footing");
            RoadNodeEvaluator evaluator = new RoadNodeEvaluator();
            evaluator.setCanFloat(actor.getNavigation().canFloat());
            evaluator.prepare(new PathNavigationRegion(helper.getLevel(),
                helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(15, 4, 15))), actor);
            try {
                Node start = evaluator.getStart();
                helper.assertTrue(start.x == dry.getX() && start.y == dry.getY()
                    && start.z == dry.getZ() && start.type != PathType.BLOCKED
                    && start.costMalus >= 0.0F,
                    "grounded peripheral water must not select the solid support as start: y="
                        + start.y + " expected=" + dry.getY() + " type=" + start.type);
            } finally {
                evaluator.done();
            }
            observed[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(observed[0] && routed[0],
                "real flowing-water overlap, ground contact and supported start must be observed");
            helper.assertTrue(walker[0].distanceToSqr(target.getX() + 0.5D,
                target.getY(), target.getZ() + 0.5D) < 0.36D && walker[0].onGround(),
                "the same actor must physically arrive by ordinary navigation after the edge query");
        });
    }

    /** A reachable shallow-water route must also be physically walkable against its current. */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 300)
    public void groundedSettlerWalksUpstreamThroughShallowFlow(GameTestHelper helper) {
        meadow(helper, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // Real source flow, not injected fluid flags. Tall walls exclude dry detours
        // over the channel sides; the target remains below FloatGoal's swim depth.
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(0, y, 8), Blocks.STONE_BRICKS);
            for (int x = 1; x <= 8; x++) {
                helper.setBlock(new BlockPos(x, y, 7), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, y, 9), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(new BlockPos(1, 1, 8), Blocks.WATER);
        BlockPos edge = helper.absolutePos(new BlockPos(8, 1, 8));
        BlockPos dry = helper.absolutePos(new BlockPos(9, 1, 8));
        BlockPos target = helper.absolutePos(new BlockPos(6, 1, 8));
        SettlerEntity[] walker = {null};
        boolean[] observed = {false};
        boolean[] routed = {false};
        helper.onEachTick(() -> {
            if (walker[0] == null) {
                var water = helper.getLevel().getBlockState(edge);
                var upstream = helper.getLevel().getBlockState(target);
                if (!water.is(Blocks.WATER)
                    || water.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 7
                    || !upstream.is(Blocks.WATER)
                    || upstream.getValue(net.minecraft.world.level.block.LiquidBlock.LEVEL) != 5
                    || !helper.getLevel().getBlockState(dry).isAir()) return;
                walker[0] = settler(helper, new BlockPos(9, 1, 8));
                SettlerEntity actor = walker[0];
                actor.assignProfession(Profession.GUARD);
                // Initial fixture placement only. Gravity establishes real support,
                // and the downstream fringe overlaps the .60-wide actor's bounds.
                actor.setPos(dry.getX() + 0.10D, dry.getY() + 0.05D, dry.getZ() + 0.5D);
                actor.setDeltaMovement(0.0D, -0.08D, 0.0D);
                actor.setHunger(100.0F);
                actor.goalSelector.addGoal(0, new Goal() {
                    { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
                    @Override public boolean canUse() { return true; }
                    @Override public boolean requiresUpdateEveryTick() { return true; }
                    @Override public void tick() {
                        if (!observed[0] || routed[0]) return;
                        Path path = actor.getNavigation().createPath(target, 0);
                        helper.assertTrue(path != null && path.canReach(),
                            "ordinary navigation must find the upstream shallow-water target");
                        for (int index = 0; index < path.getNodeCount(); index++) {
                            Node node = path.getNode(index);
                            helper.assertTrue(node.y == dry.getY() && node.z == dry.getZ()
                                && node.x >= target.getX() && node.x <= dry.getX(),
                                "the actual path must stay on the supported channel floor");
                        }
                        helper.assertTrue(actor.getNavigation().moveTo(path, 0.9D),
                            "the upstream route must start at ordinary Guard patrol speed");
                        routed[0] = true;
                    }
                });
                return;
            }
            SettlerEntity actor = walker[0];
            if (observed[0] || !actor.onGround() || !actor.isInWater()
                || !actor.blockPosition().equals(dry)) return;
            var flow = helper.getLevel().getFluidState(edge);
            helper.assertTrue(actor.getNavigation().canFloat()
                && actor.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 0.0D
                && actor.getFluidHeight(net.minecraft.tags.FluidTags.WATER) <= 0.4D
                && helper.getLevel().getBlockState(dry).isAir()
                && helper.getLevel().getBlockState(dry.below()).is(Blocks.STONE_BRICKS)
                && actor.getBoundingBox().minX < edge.getX() + 1.0D
                && helper.getLevel().noCollision(actor)
                && flow.getFlow(helper.getLevel(), edge).x > 0.9D,
                "observe supported dry-centre wet bounds and real current opposing the target");
            observed[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(observed[0] && routed[0],
                "actual shallow-flow contact and the upstream navigation route must be observed");
            SettlerEntity actor = walker[0];
            helper.assertTrue(actor.blockPosition().equals(target)
                && actor.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) < 0.36D
                && actor.onGround() && actor.isInWater()
                && actor.getFluidHeight(net.minecraft.tags.FluidTags.WATER) <= 0.4D
                && helper.getLevel().getBlockState(actor.blockPosition().below()).is(Blocks.STONE_BRICKS),
                "the same grounded actor must physically reach upstream through shallow water");
        });
    }

    /** Nobody follows the road with a raider in the wheat. */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 200)
    public void nobodyFollowsTheRoadWhileFightingOrFleeing(GameTestHelper helper) {
        meadow(helper, 16);
        SettlerEntity guard = settler(helper, new BlockPos(4, 1, 4));
        guard.assignProfession(Profession.GUARD);

        helper.assertTrue(guard.prefersRoads(),
            "a guard on an ordinary patrol keeps to the road like everyone else");

        guard.setActivity(SettlerActivity.COMBAT);
        helper.assertFalse(guard.prefersRoads(),
            "a guard in combat takes the straight line");

        guard.setActivity(SettlerActivity.FLEEING);
        helper.assertFalse(guard.prefersRoads(),
            "and so does anyone running for their life");

        guard.setActivity(SettlerActivity.IDLE);
        helper.assertTrue(guard.prefersRoads(), "and goes back to the road after");
        helper.succeed();
    }

    /**
     * The village draws its own map: grass walked often enough becomes a path,
     * with nobody building anything.
     */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 200)
    public void feetWearGrassIntoAPath(GameTestHelper helper) {
        meadow(helper, 16);
        PathWear.forget(helper.getLevel());
        SettlerEntity walker = settler(helper, new BlockPos(5, 1, 5));
        BlockPos under = new BlockPos(5, 0, 5);

        for (int i = 0; i < PathWear.FOOTFALLS_TO_WEAR - 1; i++) {
            PathWear.step(helper.getLevel(), walker);
        }
        helper.assertBlockPresent(Blocks.GRASS_BLOCK, under);

        boolean worn = PathWear.step(helper.getLevel(), walker);

        helper.assertTrue(worn, "the last footfall must wear the grass through");
        helper.assertBlockPresent(Blocks.DIRT_PATH, under);
        PathWear.forget(helper.getLevel());
        helper.succeed();
    }

    /**
     * The guard rails on a system that edits the world by itself: grass only,
     * outdoors only. A floor a player laid must never be worn away.
     */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 200)
    public void wearTouchesNothingButOpenGrass(GameTestHelper helper) {
        meadow(helper, 16);
        PathWear.forget(helper.getLevel());

        // A player's stone floor: walked on forever, never worn.
        helper.setBlock(new BlockPos(5, 0, 5), Blocks.STONE_BRICKS);
        SettlerEntity onStone = settler(helper, new BlockPos(5, 1, 5));
        for (int i = 0; i < PathWear.FOOTFALLS_TO_WEAR * 2; i++) {
            PathWear.step(helper.getLevel(), onStone);
        }
        helper.assertBlockPresent(Blocks.STONE_BRICKS, new BlockPos(5, 0, 5));

        // Grass under a roof is somebody's floor too.
        helper.setBlock(new BlockPos(9, 3, 9), Blocks.STONE_BRICKS);
        SettlerEntity indoors = settler(helper, new BlockPos(9, 1, 9));
        for (int i = 0; i < PathWear.FOOTFALLS_TO_WEAR * 2; i++) {
            PathWear.step(helper.getLevel(), indoors);
        }
        helper.assertBlockPresent(Blocks.GRASS_BLOCK, new BlockPos(9, 0, 9));

        PathWear.forget(helper.getLevel());
        helper.succeed();
    }

    /**
     * Regression for the live warehouse/lumber-camp geometry: a closed
     * wooden door is the only path through the wall. The settler must open it
     * before collision, cross, and close it after passing.
     */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 300)
    public void settlersReliablyCrossAndCloseAClosedDoor(GameTestHelper helper) {
        meadow(helper, 16);
        // Seal the fixture from the host world. Without this perimeter the
        // pathfinder can legally walk around either end of the divider on
        // standable terrain outside the 16x16 template.
        for (int edge = 0; edge < 16; edge++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(edge, y, 0), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(edge, y, 15), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(0, y, edge), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(15, y, edge), Blocks.STONE_BRICKS);
            }
        }
        for (int x = 0; x < 16; x++) {
            if (x == 8) {
                continue;
            }
            helper.setBlock(new BlockPos(x, 1, 7), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(x, 2, 7), Blocks.STONE_BRICKS);
        }
        var lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.NORTH)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        helper.setBlock(new BlockPos(8, 1, 7), lower);
        helper.setBlock(new BlockPos(8, 2, 7), lower
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        SettlerEntity walker = settler(helper, new BlockPos(8, 1, 3));
        // The target is deliberately diagonal from the terminal node before
        // the door. Recovery must use the last safe route edge rather than
        // reject this ordinary off-axis room layout as ambiguous.
        BlockPos target = helper.absolutePos(new BlockPos(13, 1, 11));
        BlockPos door = helper.absolutePos(new BlockPos(8, 1, 7));
        boolean[] sawOpen = {false};
        OneShotMoveGoal route = new OneShotMoveGoal(walker, target);

        // Own MOVE for the lifetime of this one route. A raw navigation call
        // leaves the production BoundedStrollGoal free to overwrite it; and
        // re-submitting after navigation becomes done would conceal the exact
        // partial-terminal door regression this test exists to catch.
        helper.runAtTickTime(5L, () -> walker.goalSelector.addGoal(0, route));
        helper.onEachTick(() -> {
            var state = helper.getLevel().getBlockState(door);
            if (state.is(Blocks.OAK_DOOR)
                && state.getValue(DoorBlock.OPEN)) {
                sawOpen[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(route.attempted() && route.accepted(),
                "the one authoritative route through the closed door must be accepted");
            helper.assertTrue(sawOpen[0],
                "the closed path door must visibly open before crossing");
            helper.assertTrue(walker.distanceToSqr(target.getX() + 0.5D,
                    target.getY(), target.getZ() + 0.5D) <= 2.25D,
                "the settler must reach the diagonal target beyond the doorway"
                    + " [pos=" + walker.blockPosition().toShortString()
                    + " navDone=" + walker.getNavigation().isDone()
                    + " navTarget=" + walker.getNavigation().getTargetPos()
                    + " doorOpen=" + doorOpen(helper, door)
                    + " route=" + walker.routeFailureNote() + "]");
            helper.assertTrue(!doorOpen(helper, door),
                "the door must close after the settler has passed");
        });
    }

    /** Real low-budget vanilla search: no walls or doors can explain its
     * incomplete result. Only ContainerApproach owns movement toward contact. */
    @GameTest(batch = "path", template = "empty16", timeoutTicks = 600)
    public void usefulPartialContainerPathMakesPhysicalProgress(GameTestHelper helper) {
        meadow(helper, 16);
        BlockPos chestRel = new BlockPos(13, 1, 8);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chest = helper.absolutePos(chestRel);
        SettlerEntity walker = settler(helper, new BlockPos(2, 1, 8));
        var start = walker.position();
        // 512 * 0.05 = 25 visited-node budget: enough to discover a real
        // forward prefix, deliberately too small for this off-road search.
        // The assertions below require that premise in the actual engine.
        walker.getNavigation().setMaxVisitedNodesMultiplier(0.05F);
        walker.goalSelector.addGoal(0, new Goal() {
            { setFlags(EnumSet.of(Flag.MOVE)); }
            @Override public boolean canUse() { return true; }
            @Override public boolean canContinueToUse() { return true; }
        });
        // Same five legal contact candidates as production in this open
        // geometry. This is a real pathfinder query, not a constructed Path.
        java.util.Set<BlockPos> approaches = new java.util.LinkedHashSet<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            approaches.add(chest.relative(side));
        }
        approaches.add(chest.above());
        boolean[] firstPartialStarted = {false};
        int[] attempts = {0};
        long[] lastRepath = {-20L};
        helper.onEachTick(() -> {
            if (helper.getTick() < 5L) return;
            if (!walker.onGround()) {
                helper.assertTrue(helper.getTick() < 30L,
                    "fixture must settle naturally on its flat meadow");
                return;
            }
            if (com.hearthstead.settlement.work.ContainerApproach.inspect(
                    helper.getLevel(), walker, chest).canInteract()) return;
            if (!firstPartialStarted[0]) {
                Path partial = walker.getNavigation().createPath(approaches, 0);
                helper.assertTrue(partial != null && !partial.canReach(),
                    "fixture must produce an actual incomplete vanilla path at first settled search");
                helper.assertTrue(!com.hearthstead.entity.ai.SettlerDoorGoal
                        .canRecoverPartialPathThroughClosedDoor(helper.getLevel(), partial),
                    "old closed-door recovery must not explain this open-meadow prefix");
                var result = com.hearthstead.settlement.work.ContainerApproach
                    .moveToContact(helper.getLevel(), walker, chest, 1.0D);
                helper.assertTrue(result.state() ==
                        com.hearthstead.settlement.work.ContainerApproach.State.PATH_STARTED,
                    "useful incomplete meadow path must start immediately; baseline rejects it"
                        + " [result=" + result.state() + " nodeCount=" + partial.getNodeCount()
                        + " endpoint=" + partial.getEndNode().asBlockPos()
                        + " target=" + partial.getTarget() + "]");
                Path installed = walker.getNavigation().getPath();
                helper.assertTrue(installed != null && !installed.canReach()
                        && !installed.isDone() && chest.equals(result.target())
                        && installed.getEndNode().asBlockPos().equals(result.approach()),
                    "accepted movement must retain actual partial endpoint and exact container authority");
                firstPartialStarted[0] = true;
                attempts[0]++;
                lastRepath[0] = helper.getTick();
            } else if (walker.getNavigation().isDone()
                    && helper.getTick() - lastRepath[0] >= 20L) {
                helper.assertTrue(++attempts[0] <= 20,
                    "physical contact must be reached within twenty bounded search attempts");
                com.hearthstead.settlement.work.ContainerApproach.moveToContact(
                    helper.getLevel(), walker, chest, 1.0D);
                lastRepath[0] = helper.getTick();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(firstPartialStarted[0],
                "first settled partial must be accepted before success");
            helper.assertTrue(walker.position().distanceToSqr(start) >= 36.0D,
                "the settler must physically walk across the meadow");
            helper.assertTrue(com.hearthstead.settlement.work.ContainerApproach.inspect(
                    helper.getLevel(), walker, chest).canInteract(),
                "bounded partial repaths must finish at actual visible container CONTACT");
        });
    }

    private static boolean doorOpen(GameTestHelper helper, BlockPos door) {
        var state = helper.getLevel().getBlockState(door);
        return state.getBlock() instanceof DoorBlock
            && state.hasProperty(DoorBlock.OPEN)
            && state.getValue(DoorBlock.OPEN);
    }

    /**
     * Test-only route ownership: starts navigation exactly once and retains
     * MOVE even if the path reaches an incomplete terminal node. This lets the
     * production flag-free door goal recover the route without allowing idle
     * wandering to replace it or the fixture to retry it behind our back.
     */
    private static final class OneShotMoveGoal extends Goal {
        private final SettlerEntity walker;
        private final BlockPos target;
        private boolean attempted;
        private boolean accepted;

        private OneShotMoveGoal(SettlerEntity walker, BlockPos target) {
            this.walker = walker;
            this.target = target;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return !attempted;
        }

        @Override
        public void start() {
            attempted = true;
            accepted = walker.getNavigation().moveTo(target.getX() + 0.5D,
                target.getY(), target.getZ() + 0.5D, 1.0D);
        }

        @Override
        public boolean canContinueToUse() {
            // Deliberately remain active when navigation isDone(): that is the
            // partial-terminal state SettlerDoorGoal must recover. Keep MOVE
            // until the fixture ends so no idle goal can manufacture a later
            // crossing (or pull the settler back) after this exact route stops.
            return accepted;
        }

        private boolean attempted() {
            return attempted;
        }

        private boolean accepted() {
            return accepted;
        }
    }
}
