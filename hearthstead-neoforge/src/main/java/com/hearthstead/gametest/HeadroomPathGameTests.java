package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Headroom: the settler's whole body must fit wherever its route puts it.
 *
 * <p>Live builder film, 2026-09-26: 68 of the 77 town blueprints hung a
 * closed top oak trapdoor as a window-box shelf 1.8125 above the ground in
 * front of the house. Vanilla calls every trapdoor passable, so a 1.95-tall
 * settler walking along the front wall was routed under the shelf and
 * wedged there. Players build shelves like this too.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HeadroomPathGameTests {
    private static final String BATCH = "path_headroom";

    /** The blueprint window box: shelf at 1.8125 along a wall, a pot on it. */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 400)
    public void settlerWalksAlongAWallPastAHeadHeightShelf(GameTestHelper helper) {
        meadow(helper);
        for (int x = 2; x <= 13; x++) {
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE_BRICKS);
        }
        List<BlockPos> shelf = new java.util.ArrayList<>();
        for (int x = 5; x <= 10; x++) {
            helper.setBlock(new BlockPos(x, 2, 7), trapdoor(Half.TOP, false));
            helper.setBlock(new BlockPos(x, 3, 7), Blocks.POTTED_POPPY);
            shelf.add(helper.absolutePos(new BlockPos(x, 1, 7)));
        }
        SettlerEntity walker = quietSettler(helper, new BlockPos(3, 1, 7));
        BlockPos target = helper.absolutePos(new BlockPos(12, 1, 7));
        OneShotMove route = new OneShotMove(walker, target);
        GameTestTicks.at(helper, 5L, () -> walker.goalSelector.addGoal(0, route));
        String[] seen = {null};
        helper.onEachTick(() -> {
            String bad = nodeUnder(walker.getNavigation().getPath(), shelf);
            if (bad != null && seen[0] == null) seen[0] = bad;
            if (shelf.contains(walker.blockPosition()) && seen[0] == null) {
                seen[0] = "body at " + walker.blockPosition().toShortString();
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0] == null,
                "no route node and no settler may stand under the head-height shelf: " + seen[0]);
            helper.assertTrue(route.attempted() && route.accepted(),
                "the real navigator must accept the walk past the shelf");
            helper.assertTrue(walker.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < 2.25D,
                "the settler must walk round the shelf to the far end, not wedge under it; pos="
                    + walker.position() + " path=" + describe(walker.getNavigation().getPath()));
        });
    }

    /** The shelf closes a one-wide lane: the route stops in front of it. */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 300)
    public void settlerNeverWedgesUnderAShelfThatClosesTheOnlyLane(GameTestHelper helper) {
        meadow(helper);
        lane(helper, 3);
        List<BlockPos> shelf = List.of(helper.absolutePos(new BlockPos(7, 1, 7)),
            helper.absolutePos(new BlockPos(8, 1, 7)));
        helper.setBlock(new BlockPos(7, 2, 7), trapdoor(Half.TOP, false));
        helper.setBlock(new BlockPos(8, 2, 7), trapdoor(Half.TOP, false));
        SettlerEntity walker = quietSettler(helper, new BlockPos(3, 1, 7));
        BlockPos target = helper.absolutePos(new BlockPos(12, 1, 7));
        OneShotMove route = new OneShotMove(walker, target);
        String[] seen = {null};
        GameTestTicks.at(helper, 10L, () -> {
            helper.assertTrue(walker.onGround(), "the walker must settle in the lane first");
            Path probe = walker.getNavigation().createPath(target, 0);
            helper.assertTrue(probe == null || !probe.canReach(),
                "a lane closed at head height must not report a complete route: " + describe(probe));
            String bad = nodeUnder(probe, shelf);
            helper.assertTrue(bad == null, "the partial route must stop before the shelf: " + bad);
            walker.goalSelector.addGoal(0, route);
        });
        helper.onEachTick(() -> {
            String bad = nodeUnder(walker.getNavigation().getPath(), shelf);
            if (bad != null && seen[0] == null) seen[0] = bad;
            if (walker.getX() > shelf.get(0).getX() - 0.2D && seen[0] == null) {
                seen[0] = "body reached x=" + walker.getX();
            }
        });
        GameTestTicks.at(helper, 200L, () -> {
            helper.assertTrue(seen[0] == null,
                "the settler must never be routed or pushed under the shelf: " + seen[0]);
            helper.assertTrue(walker.getX() < shelf.get(0).getX(),
                "the settler must wait in front of the shelf; pos=" + walker.position());
            helper.succeed();
        });
    }

    /**
     * Everything that clears a 1.95-tall body stays walkable in a one-wide
     * lane: an open door in a two-high doorway, a closed bottom trapdoor
     * shelf exactly 2.0 up, a top slab at 2.5, an open trapdoor standing
     * against the wall at head height and a closed top trapdoor at 2.8125.
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 400)
    public void corridorUnderHighShelvesStaysWalkable(GameTestHelper helper) {
        meadow(helper);
        lane(helper, 4);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.OPEN, true)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(new BlockPos(4, 1, 7), door);
        helper.setBlock(new BlockPos(4, 2, 7), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(new BlockPos(4, 3, 7), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(4, 4, 7), Blocks.OAK_PLANKS);
        helper.setBlock(new BlockPos(6, 3, 7), trapdoor(Half.BOTTOM, false));
        helper.setBlock(new BlockPos(8, 3, 7), Blocks.OAK_SLAB.defaultBlockState()
            .setValue(SlabBlock.TYPE, SlabType.TOP));
        helper.setBlock(new BlockPos(10, 2, 7), trapdoor(Half.TOP, true)
            .setValue(TrapDoorBlock.FACING, Direction.SOUTH));
        helper.setBlock(new BlockPos(11, 3, 7), trapdoor(Half.TOP, false));
        SettlerEntity walker = quietSettler(helper, new BlockPos(2, 1, 7));
        BlockPos target = helper.absolutePos(new BlockPos(13, 1, 7));
        OneShotMove route = new OneShotMove(walker, target);
        GameTestTicks.at(helper, 10L, () -> {
            helper.assertTrue(walker.onGround(), "the walker must settle in the lane first");
            Path probe = walker.getNavigation().createPath(target, 0);
            helper.assertTrue(probe != null && probe.canReach(),
                "shelves at 2.0+ must leave the lane walkable: " + describe(probe));
            walker.goalSelector.addGoal(0, route);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(route.attempted() && route.accepted(),
                "the real navigator must accept the corridor walk");
            helper.assertTrue(walker.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < 2.25D,
                "the settler must walk the whole corridor; pos=" + walker.position()
                    + " path=" + describe(walker.getNavigation().getPath()));
        });
    }

    /**
     * A player-style stair up through a floor, built the way players and the
     * owner's own house do it: the upper floor is open over every step whose
     * third block above the tread would otherwise be floor. The settler
     * climbs it and reaches the far side of the loft.
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 500)
    public void settlerClimbsAStairwellWithThreeClearAboveEveryStep(GameTestHelper helper) {
        meadow(helper);
        stairwell(helper, true);
        SettlerEntity walker = quietSettler(helper, new BlockPos(2, 1, 7));
        BlockPos target = helper.absolutePos(new BlockPos(10, 5, 7));
        OneShotMove route = new OneShotMove(walker, target);
        GameTestTicks.at(helper, 10L, () -> {
            helper.assertTrue(walker.onGround(), "the walker must settle at the stair foot first");
            Path probe = walker.getNavigation().createPath(target, 0);
            helper.assertTrue(probe != null && probe.canReach(),
                "an open stairwell must give a complete route up: " + describe(probe));
            walker.goalSelector.addGoal(0, route);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(route.attempted() && route.accepted(),
                "the real navigator must accept the climb");
            helper.assertTrue(walker.position().distanceToSqr(Vec3.atBottomCenterOf(target)) < 2.25D
                    && Math.abs(walker.getY() - target.getY()) < 0.1D,
                "the settler must climb the stair and cross the loft; pos=" + walker.position()
                    + " path=" + describe(walker.getNavigation().getPath()));
        });
    }

    /**
     * The same stair where the floor over the first tread was left in: only
     * two blocks clear above it. Nothing 1.95 tall and 0.6 wide can step up
     * from there, so the route must say so (partial), and the settler waits
     * at the foot or on the first tread instead of jumping at the ceiling.
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 300)
    public void twoHighStairwellIsReportedPartialAndNobodyWedges(GameTestHelper helper) {
        meadow(helper);
        stairwell(helper, false);
        SettlerEntity walker = quietSettler(helper, new BlockPos(2, 1, 7));
        BlockPos target = helper.absolutePos(new BlockPos(10, 5, 7));
        OneShotMove route = new OneShotMove(walker, target);
        double[] highest = {Double.NEGATIVE_INFINITY};
        GameTestTicks.at(helper, 10L, () -> {
            helper.assertTrue(walker.onGround(), "the walker must settle at the stair foot first");
            Path probe = walker.getNavigation().createPath(target, 0);
            helper.assertTrue(probe == null || !probe.canReach(),
                "a stair the body cannot climb must not be promised as a complete route: "
                    + describe(probe));
            // Control in the same fixture, so the partial route above cannot
            // come from anything but the one plank over the first tread:
            // open it and the same search must complete; then put it back.
            BlockPos plank = new BlockPos(4, 4, 7);
            helper.setBlock(plank, Blocks.AIR);
            Path opened = walker.getNavigation().createPath(target, 0);
            helper.assertTrue(opened != null && opened.canReach(),
                "with the plank over the first tread removed the same climb must complete: "
                    + describe(opened));
            helper.setBlock(plank, Blocks.OAK_PLANKS);
            walker.goalSelector.addGoal(0, route);
        });
        helper.onEachTick(() -> highest[0] = Math.max(highest[0], walker.getY()));
        GameTestTicks.at(helper, 220L, () -> {
            double floor = helper.absolutePos(BlockPos.ZERO).getY();
            helper.assertTrue(highest[0] < floor + 2.9D,
                "the settler cannot and must not get past the first tread; highest y="
                    + (highest[0] - floor));
            helper.assertTrue(walker.getNavigation().isDone(),
                "the partial route must end, not keep the settler jumping at the ceiling; path="
                    + describe(walker.getNavigation().getPath()) + " pos=" + walker.position());
            helper.succeed();
        });
    }

    /**
     * Physics evidence for the rule above, with no pathfinder at all: a
     * settler on the first tread, driven straight at the second one every
     * tick, cannot step up under a floor two blocks above its tread (vanilla
     * clips the step and the jump at the ceiling while the body still
     * overlaps the column it is leaving).
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 200)
    public void settlerPhysicallyCannotStepUpUnderATwoHighStairwell(GameTestHelper helper) {
        meadow(helper);
        stairwell(helper, false);
        SettlerEntity walker = quietSettler(helper, new BlockPos(4, 2, 7));
        BlockPos next = helper.absolutePos(new BlockPos(5, 3, 7));
        double floor = helper.absolutePos(BlockPos.ZERO).getY();
        double[] highest = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> {
            walker.getMoveControl().setWantedPosition(next.getX() + 0.5D, next.getY(),
                next.getZ() + 0.5D, 1.0D);
            highest[0] = Math.max(highest[0], walker.getY());
        });
        GameTestTicks.at(helper, 120L, () -> {
            helper.assertTrue(highest[0] < floor + 2.9D,
                "a 1.95-tall settler must not fit up a two-high stairwell; highest y="
                    + (highest[0] - floor) + " pos=" + walker.position());
            helper.succeed();
        });
    }

    /**
     * The plaque warns, without failing anything, when a room has a stair a
     * settler cannot climb for lack of headroom, and names the step. The
     * same room with the stair clear gives no warning, so the note comes
     * from the lantern hung over the approach and nothing else.
     */
    @GameTest(batch = BATCH, template = "empty16", timeoutTicks = 200)
    public void plaqueWarnsAboutAStairWithoutHeadroom(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
        }
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 10;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        BlockPos o = new BlockPos(4, 0, 4);
        warehouse(helper, o);
        helper.setBlock(o.offset(3, 1, 3), Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST)
            .setValue(StairBlock.HALF, Half.BOTTOM));
        BlockPos plaqueRel = o.offset(1, 2, -1);
        helper.setBlock(plaqueRel, com.hearthstead.registry.ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(com.hearthstead.block.PlaqueBlock.FACING, Direction.NORTH));
        helper.runAfterDelay(20, () -> {
            var be = helper.getLevel().getBlockEntity(helper.absolutePos(plaqueRel));
            helper.assertTrue(be instanceof com.hearthstead.block.PlaqueBlockEntity, "the plaque must exist");
            var plaque = (com.hearthstead.block.PlaqueBlockEntity) be;
            plaque.insertPlan(helper.getLevel(), com.hearthstead.block.PlaqueItemData.stamped(
                new net.minecraft.world.item.ItemStack(com.hearthstead.registry.ModItems.BUILD_PLAN.get()),
                com.hearthstead.building.BuildingType.WAREHOUSE));
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
                "the fixture warehouse must register first; got " + plaque.state()
                    + " reason=" + plaque.lastScanReason());
            helper.assertTrue(plaque.lastScanReason() == null,
                "a stair with headroom must not warn; got " + plaque.lastScanReason());

            helper.setBlock(o.offset(2, 3, 3), Blocks.LANTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
            var direct = com.hearthstead.settlement.RoomScanner.scan(helper.getLevel(),
                helper.absolutePos(o.offset(2, 1, 3)));
            BlockPos step = helper.absolutePos(o.offset(2, 0, 3));
            helper.assertTrue(direct != null && step.equals(direct.lowStairStep()),
                "the scan must name the floor step in front of the stair; got "
                    + (direct == null ? null : direct.lowStairStep()) + " want " + step);
            plaque.survey(helper.getLevel());
            helper.assertTrue(plaque.state() == com.hearthstead.building.PlaqueState.LINKED_VALID,
                "the stair warning must not fail the room; got " + plaque.state());
            net.minecraft.network.chat.Component reason = plaque.lastScanReason();
            helper.assertTrue(reason != null
                    && reason.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                    && tc.getKey().equals("hearthstead.plaque.scan.stair_headroom")
                    && java.util.Arrays.equals(tc.getArgs(),
                        new Object[]{step.getX(), step.getY(), step.getZ()}),
                "the plaque must warn and point at the step; got " + reason);
            helper.succeed();
        });
    }

    /** RoomScannerGameTests' open warehouse: 7x7 shell, roof at y=4, door north, storage and torches. */
    private static void warehouse(GameTestHelper helper, BlockPos o) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean wall = x == 0 || z == 0 || x == 6 || z == 6;
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(o.offset(x, y, z), wall ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
                helper.setBlock(o.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(o.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(o.offset(3, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(o.offset(3, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(o.offset(1, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(2, 1, 1), Blocks.CHEST);
        helper.setBlock(o.offset(1, 1, 2), Blocks.CHEST);
        helper.setBlock(o.offset(4, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 1), Blocks.BARREL);
        helper.setBlock(o.offset(5, 1, 2), Blocks.BARREL);
        helper.setBlock(o.offset(1, 1, 4), Blocks.TORCH);
        helper.setBlock(o.offset(5, 1, 4), Blocks.TORCH);
        helper.setBlock(o.offset(3, 1, 5), Blocks.TORCH);
    }

    /**
     * Kit-style stair in a one-wide stairwell: oak stairs facing east at
     * (4,1,7)..(7,4,7) with planks under, walls along z=6 and z=8, an upper
     * floor at y=4 over x=2..12. The floor is opened over the second and
     * third treads, and over the first one too when {@code threeClear}.
     */
    private static void stairwell(GameTestHelper helper, boolean threeClear) {
        for (int x = 2; x <= 12; x++) {
            for (int z = 5; z <= 9; z++) helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_PLANKS);
        }
        for (int x = 2; x <= 8; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, y, 8), Blocks.STONE_BRICKS);
            }
        }
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST)
            .setValue(StairBlock.HALF, Half.BOTTOM)
            .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        for (int i = 0; i <= 3; i++) {
            for (int y = 1; y <= i; y++) helper.setBlock(new BlockPos(4 + i, y, 7), Blocks.OAK_PLANKS);
            helper.setBlock(new BlockPos(4 + i, 1 + i, 7), stair);
        }
        for (int x = threeClear ? 4 : 5; x <= 6; x++) helper.setBlock(new BlockPos(x, 4, 7), Blocks.AIR);
    }

    private static void meadow(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
                for (int y = 1; y <= 7; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    /** A one-wide east-west lane at z=7 from x=2 to 13, walls {@code height} high, both ends capped. */
    private static void lane(GameTestHelper helper, int height) {
        for (int y = 1; y <= height; y++) {
            for (int x = 1; x <= 14; x++) {
                helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, y, 8), Blocks.STONE_BRICKS);
            }
            helper.setBlock(new BlockPos(1, y, 7), Blocks.STONE_BRICKS);
            helper.setBlock(new BlockPos(14, y, 7), Blocks.STONE_BRICKS);
        }
    }

    private static BlockState trapdoor(Half half, boolean open) {
        return Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(TrapDoorBlock.FACING, Direction.SOUTH)
            .setValue(TrapDoorBlock.HALF, half)
            .setValue(TrapDoorBlock.OPEN, open);
    }

    private static SettlerEntity quietSettler(GameTestHelper helper, BlockPos rel) {
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Hode");
        settler.bindTo(s.id, s.center);
        settler.goalSelector.removeAllGoals(goal -> true);
        settler.targetSelector.removeAllGoals(goal -> true);
        return settler;
    }

    private static String nodeUnder(Path path, List<BlockPos> cells) {
        if (path == null) return null;
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos node = path.getNodePos(i);
            if (cells.contains(node)) return "node " + node.toShortString() + " in " + describe(path);
        }
        return null;
    }

    private static String describe(Path path) {
        if (path == null) return "null";
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++) nodes.append(path.getNodePos(i).toShortString()).append(' ');
        return "reach=" + path.canReach() + " nodes=" + nodes;
    }

    /** Starts one route and keeps MOVE so nothing else replaces it. */
    private static final class OneShotMove extends Goal {
        private final SettlerEntity walker;
        private final BlockPos target;
        private boolean attempted;
        private boolean accepted;

        private OneShotMove(SettlerEntity walker, BlockPos target) {
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
