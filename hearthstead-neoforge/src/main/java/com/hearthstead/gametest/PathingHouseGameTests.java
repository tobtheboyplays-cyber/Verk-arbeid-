package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.hearthstead.gametest.PathingHarness.bed;
import static com.hearthstead.gametest.PathingHarness.container;
import static com.hearthstead.gametest.PathingHarness.point;
import static com.hearthstead.gametest.PathingHarness.station;

/**
 * Indoor pathing regression suite: realistic houses a player builds.
 *
 * <p>{@code path_server_house} is the owner's real two-storey house and
 * barracks, exported block-for-block from the live server copy of
 * 2026-09-25 (region x101..120, y70..81, z-135..-115). Another Furniture
 * tables and chairs became oak fences (obstacles of the same footprint that
 * are never walkable); the hearth became cobblestone. Everything else,
 * including the outside ladder, the raised upper doors, the one-wide spiral
 * stair around a log pillar and the bed tucked between a bed, a wall and a
 * stair, is exactly what the settlers face every night.
 *
 * <p>Every leg uses the production approach code and has a tick budget.
 * Every wooden door, gate and trapdoor must be closed again at the end.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PathingHouseGameTests {
    private static final String BATCH = "pathing_house";

    // Real house coordinates: world (x,y,z) -> (x-101, y-70, z+135).
    private static final BlockPos REAL_START = new BlockPos(10, 2, 18);   // by the hearth
    private static final BlockPos REAL_OUTSIDE = new BlockPos(12, 2, 14); // path in front
    private static final BlockPos REAL_MIN = new BlockPos(0, 0, 0);
    private static final BlockPos REAL_MAX = new BlockPos(19, 11, 20);

    @GameTest(template = "path_server_house", batch = BATCH, timeoutTicks = 3200)
    public void serverHouseUpstairsBedChestBenchAndOut(GameTestHelper helper) {
        var settler = PathingHarness.walker(helper, abs(helper, REAL_START));
        PathingHarness.run(helper, "server_upstairs", settler, List.of(
            bed("ansgar_bed_115_76_-128", abs(helper, 14, 6, 7), 900),
            container("upstairs_chest_112_76_-129", abs(helper, 11, 6, 6), 400),
            station("upstairs_bench_112_76_-130", abs(helper, 11, 6, 5), 300),
            point("outside", abs(helper, REAL_OUTSIDE), 900)), abs(helper, REAL_MIN), abs(helper, REAL_MAX));
    }

    @GameTest(template = "path_server_house", batch = BATCH, timeoutTicks = 2200)
    public void serverHouseTuckedUpstairsBed(GameTestHelper helper) {
        var settler = PathingHarness.walker(helper, abs(helper, REAL_START));
        PathingHarness.run(helper, "server_tucked_bed", settler, List.of(
            bed("dunstan_bed_115_76_-127", abs(helper, 14, 6, 8), 900),
            point("outside", abs(helper, REAL_OUTSIDE), 900)), abs(helper, REAL_MIN), abs(helper, REAL_MAX));
    }

    @GameTest(template = "path_server_house", batch = BATCH, timeoutTicks = 4200)
    public void serverHouseGroundBedsAndBarracksChest(GameTestHelper helper) {
        var settler = PathingHarness.walker(helper, abs(helper, REAL_START));
        PathingHarness.run(helper, "server_ground", settler, List.of(
            bed("wilmot_bed_111_72_-130", abs(helper, 10, 2, 5), 600),
            bed("eira_bed_115_72_-130", abs(helper, 14, 2, 5), 400),
            container("barracks_chest_109_72_-127", abs(helper, 8, 2, 8), 900),
            // Barracks upper room: the one-wide spiral round the log pillar,
            // or the raised doors from the house loft.
            point("barracks_loft_106_76_-129", abs(helper, 5, 6, 6), 900),
            point("outside", abs(helper, REAL_OUTSIDE), 900)), abs(helper, REAL_MIN), abs(helper, REAL_MAX));
    }

    /**
     * The owner's outside ladder, both ways. The loft's west door and the
     * barracks balcony door are walled up so the only way between the loft
     * and the ground is the ladder at 116,73..76,-125, the log ledge and the
     * raised door at 112,77,-126. A lumberer was seen wedged at the ladder
     * top (116,77,-124) on the live copy.
     */
    @GameTest(template = "path_server_house", batch = BATCH, timeoutTicks = 3200)
    public void serverHouseOutsideLadderBothWays(GameTestHelper helper) {
        Kit k = new Kit(helper);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        k.set(10, 8, 8, planks);
        k.set(10, 7, 8, planks);   // loft west door 111,77,-127
        k.set(8, 8, 12, planks);
        k.set(8, 7, 12, planks);   // barracks balcony door 109,77,-123
        var settler = PathingHarness.walker(helper, abs(helper, 12, 6, 6));
        PathingHarness.run(helper, "server_ladder", settler, List.of(
            point("down_the_ladder_outside", abs(helper, REAL_OUTSIDE), 900),
            bed("up_the_ladder_dunstan_bed", abs(helper, 14, 6, 8), 900),
            point("down_again", abs(helper, REAL_START), 900)), abs(helper, REAL_MIN), abs(helper, REAL_MAX));
    }

    /** Six settlers leave a 7x7 room through its one door at once (soak dining hall). */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 900)
    public void crowdLeavesThroughOneDoor(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(8, 6, 14, 12, 5);
        k.door(11, 1, 12, Direction.SOUTH);
        List<com.hearthstead.entity.SettlerEntity> crowd = new java.util.ArrayList<>();
        int[][] spots = {{9, 7}, {11, 7}, {13, 7}, {9, 9}, {11, 9}, {13, 10}};
        for (int[] spot : spots) crowd.add(PathingHarness.walker(helper, abs(helper, spot[0], 1, spot[1])));
        int[] next = {0};
        PathingHarness.runCrowd(helper, "crowd_door", crowd,
            settler -> point("out_" + next[0], abs(helper, 8 + 2 * (next[0]++ % 4), 1, 17 + next[0] / 5), 700),
            abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** Three floors, straight 1-wide stairs, doors between rooms, bed on top. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 2400)
    public void threeFloorHouseStraightStairs(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(4, 4, 16, 14, 15);
        k.floor(5, 5, 15, 13, 5);
        k.floor(5, 5, 15, 13, 10);
        k.door(10, 1, 14, Direction.NORTH);
        // Ground: partition with door, straight stair east along the north wall.
        k.wall(5, 1, 9, 15, 4, 9);
        k.door(8, 1, 9, Direction.NORTH);
        for (int i = 0; i < 4; i++) {
            k.fill(10 + i, 1, 5, 10 + i, i, 5, Blocks.STONE_BRICKS.defaultBlockState());
            k.stair(10 + i, 1 + i, 5, Direction.EAST);
        }
        k.fill(11, 5, 5, 13, 5, 5, Blocks.AIR.defaultBlockState());
        k.fill(11, 6, 6, 13, 6, 6, Blocks.OAK_FENCE.defaultBlockState());
        // Floor 2: store room behind a door, stair west to floor 3.
        k.wall(5, 6, 9, 15, 9, 9);
        k.door(13, 6, 9, Direction.NORTH);
        k.chest(6, 6, 12);
        for (int i = 0; i < 4; i++) {
            k.fill(12 - i, 6, 7, 12 - i, 5 + i, 7, Blocks.OAK_PLANKS.defaultBlockState());
            k.stair(12 - i, 6 + i, 7, Direction.WEST);
        }
        k.fill(9, 10, 7, 11, 10, 7, Blocks.AIR.defaultBlockState());
        k.fill(9, 11, 6, 11, 11, 6, Blocks.OAK_FENCE.defaultBlockState());
        k.fill(9, 11, 8, 11, 11, 8, Blocks.OAK_FENCE.defaultBlockState());
        // Floor 3: bedroom behind a door.
        k.wall(5, 11, 9, 15, 14, 9);
        k.door(7, 11, 9, Direction.NORTH);
        k.bed(12, 11, 11, Direction.SOUTH);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(10, 1, 18)));
        PathingHarness.run(helper, "three_floor", settler, List.of(
            bed("top_floor_bed", abs(helper, 12, 11, 12), 700),
            container("floor2_store_chest", abs(helper, 6, 6, 12), 600),
            point("outside", abs(helper, 10, 1, 18), 700)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** 3x3 spiral of one-wide steps around a pillar up to a bedroom. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 1600)
    public void spiralStairToBedroom(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(6, 6, 16, 14, 10);
        k.floor(7, 7, 15, 13, 5);
        k.door(12, 1, 14, Direction.NORTH);
        // Ring around pillar (9,9): p2(10,8) p3(10,9) p4(10,10) p5(9,10) p6(8,10).
        k.fill(9, 1, 9, 9, 4, 9, Blocks.OAK_LOG.defaultBlockState());
        k.fill(8, 5, 8, 10, 5, 10, Blocks.AIR.defaultBlockState());
        k.stair(10, 1, 8, Direction.SOUTH);
        k.fill(10, 1, 9, 10, 1, 9, Blocks.OAK_PLANKS.defaultBlockState());
        k.stair(10, 2, 9, Direction.SOUTH);
        k.fill(10, 1, 10, 10, 2, 10, Blocks.OAK_PLANKS.defaultBlockState());
        k.stair(10, 3, 10, Direction.WEST);
        k.fill(9, 1, 10, 9, 3, 10, Blocks.OAK_PLANKS.defaultBlockState());
        k.stair(9, 4, 10, Direction.WEST);
        k.fill(8, 1, 10, 8, 5, 10, Blocks.OAK_PLANKS.defaultBlockState());
        // Floor above the ring except where heads pass while stepping up
        // (p3, p4, p5): a player needs the same opening.
        k.fill(8, 5, 8, 9, 5, 9, Blocks.OAK_PLANKS.defaultBlockState());
        k.set(10, 5, 8, Blocks.OAK_PLANKS.defaultBlockState());
        k.bed(14, 6, 11, Direction.SOUTH);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(12, 1, 18)));
        PathingHarness.run(helper, "spiral", settler, List.of(
            bed("spiral_bedroom_bed", abs(helper, 14, 6, 12), 600),
            point("outside", abs(helper, 12, 1, 18), 600)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** Ladder shaft through the floor, closed oak trapdoor on top. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 1600)
    public void ladderShaftWithTrapdoor(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(6, 6, 14, 14, 10);
        k.floor(7, 7, 13, 13, 5);
        k.door(10, 1, 14, Direction.NORTH);
        for (int y = 1; y <= 4; y++) {
            k.set(8, y, 7, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        k.set(8, 5, 7, Blocks.OAK_TRAPDOOR.defaultBlockState()
            .setValue(TrapDoorBlock.FACING, Direction.SOUTH).setValue(TrapDoorBlock.HALF, Half.BOTTOM)
            .setValue(TrapDoorBlock.OPEN, false));
        k.bed(12, 6, 11, Direction.SOUTH);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(10, 1, 17)));
        PathingHarness.run(helper, "ladder_trapdoor", settler, List.of(
            bed("loft_bed", abs(helper, 12, 6, 12), 600),
            point("outside", abs(helper, 10, 1, 17), 600)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** Workshop behind two doors: bench and corner chest, then back out. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 1600)
    public void workRoomBehindTwoDoors(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(4, 6, 20, 14, 5);
        k.door(6, 1, 14, Direction.NORTH);
        k.wall(10, 1, 7, 10, 4, 13);
        k.door(10, 1, 12, Direction.EAST);
        k.wall(15, 1, 7, 15, 4, 13);
        k.door(15, 1, 8, Direction.EAST);
        k.set(19, 1, 11, Blocks.CRAFTING_TABLE.defaultBlockState());
        k.chest(19, 1, 7);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(6, 1, 17)));
        PathingHarness.run(helper, "work_room", settler, List.of(
            station("bench", abs(helper, 19, 1, 11), 500),
            container("corner_chest", abs(helper, 19, 1, 7), 300),
            point("outside", abs(helper, 6, 1, 17), 500)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** Chest in a room corner with a slab table in front of both open faces. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 1200)
    public void cornerChestBehindTable(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(6, 6, 14, 14, 5);
        k.door(10, 1, 14, Direction.NORTH);
        k.chest(7, 1, 7);
        BlockState table = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        k.set(8, 1, 7, table);
        k.set(7, 1, 8, table);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(10, 1, 17)));
        PathingHarness.run(helper, "corner_chest", settler, List.of(
            new FloorContactLeg(container("chest_behind_table", abs(helper, 7, 1, 7), 400),
                abs(helper, 7, 1, 7).getY()),
            point("outside", abs(helper, 10, 1, 17), 400)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /**
     * One-wide carpeted corridor with a slab step. A short 2-high branch is
     * carpeted too: a 1.95-tall settler cannot physically stand on carpet
     * under a 2-high ceiling, so it must take the 3-high corridor.
     */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 1600)
    public void narrowCorridorCarpetAndSlabs(GameTestHelper helper) {
        Kit k = new Kit(helper);
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        // Solid block 3..22 x, 4..16 z, 5 high; corridors are carved out of it.
        k.fill(3, 1, 4, 22, 5, 16, wall);
        // Long 3-high corridor: z=14 from x=4 to 21, then north along x=21.
        k.fill(4, 1, 14, 21, 3, 14, air);
        k.fill(21, 1, 6, 21, 3, 13, air);
        // Store room x 14..20, z 6..8, 3 high.
        k.fill(14, 1, 6, 20, 3, 8, air);
        // Short 2-high branch: north along x=4, then east along z=7 to the room.
        k.fill(4, 1, 7, 4, 2, 13, air);
        k.fill(5, 1, 7, 13, 2, 7, air);
        // The first cell inside the door stays bare: carpet directly under
        // a two-high door lintel is physically impassable at 1.95 tall.
        k.fill(5, 1, 14, 21, 1, 14, Blocks.RED_CARPET.defaultBlockState());
        k.fill(21, 1, 6, 21, 1, 13, Blocks.RED_CARPET.defaultBlockState());
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
        k.fill(12, 1, 14, 14, 1, 14, slab);
        k.fill(21, 1, 9, 21, 1, 10, slab);
        k.fill(4, 1, 7, 4, 1, 13, Blocks.WHITE_CARPET.defaultBlockState());
        k.fill(5, 1, 7, 13, 1, 7, Blocks.WHITE_CARPET.defaultBlockState());
        k.chest(14, 1, 6);
        k.door(3, 1, 14, Direction.EAST);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(1, 1, 14)));
        PathingHarness.run(helper, "corridor", settler, List.of(
            container("corridor_chest", abs(helper, 14, 1, 6), 700),
            point("outside", abs(helper, 1, 1, 14), 700)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** Fenced yard with an oak gate, a shed with a door and a chest inside. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 1200)
    public void fenceGateYard(GameTestHelper helper) {
        Kit k = new Kit(helper);
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        k.fill(4, 1, 4, 20, 1, 4, fence);
        k.fill(4, 1, 16, 20, 1, 16, fence);
        k.fill(4, 1, 4, 4, 1, 16, fence);
        k.fill(20, 1, 4, 20, 1, 16, fence);
        k.set(12, 1, 16, Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, false));
        k.shell(12, 6, 18, 11, 4);
        k.door(15, 1, 11, Direction.NORTH);
        k.chest(16, 1, 7);
        var settler = PathingHarness.walker(helper, abs(helper, new BlockPos(12, 1, 20)));
        PathingHarness.run(helper, "gate_yard", settler, List.of(
            container("shed_chest", abs(helper, 16, 1, 7), 500),
            point("outside", abs(helper, 12, 1, 20), 500)), abs(helper, 0, 0, 0), abs(helper, 27, 19, 27));
    }

    /** An iron door is not opened by hand: the room behind it stays shut and unreached. */
    @GameTest(template = "path_lot", batch = BATCH, timeoutTicks = 400)
    public void ironDoorIsNeverOpened(GameTestHelper helper) {
        Kit k = new Kit(helper);
        k.shell(8, 6, 14, 12, 5);
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
            .setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        k.set(11, 1, 12, lower);
        k.set(11, 2, 12, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        k.chest(11, 1, 8);
        var settler = PathingHarness.walker(helper, abs(helper, 11, 1, 16));
        BlockPos door = abs(helper, 11, 1, 12);
        BlockPos chest = abs(helper, 11, 1, 8);
        net.minecraft.world.phys.AABB room = new net.minecraft.world.phys.AABB(
            abs(helper, 9, 1, 7).getX(), abs(helper, 9, 1, 7).getY(), abs(helper, 9, 1, 7).getZ(),
            abs(helper, 13, 4, 11).getX() + 1, abs(helper, 13, 4, 11).getY() + 1, abs(helper, 13, 4, 11).getZ() + 1);
        var leg = container("behind_iron_door", chest, 300);
        long[] lastDrive = {-100};
        helper.onEachTick(() -> {
            helper.assertTrue(!helper.getLevel().getBlockState(door).getValue(DoorBlock.OPEN),
                "a settler must never open an iron door");
            helper.assertTrue(!room.contains(settler.position()),
                "nothing may get the settler into the room behind a shut iron door: settler="
                    + settler.position() + " door=" + door + " tick=" + helper.getTick());
            if (helper.getTick() - lastDrive[0] >= PathingHarness.DRIVE_INTERVAL) {
                lastDrive[0] = helper.getTick();
                leg.drive(helper.getLevel(), settler);
            }
        });
        GameTestTicks.at(helper, 300, helper::succeed);
    }

    /**
     * Template coordinates to world. The GameTest framework places a template
     * one block above its structure block while {@code absolutePos} counts
     * from the structure block, so template layer {@code y} is helper
     * {@code y + 1}. Every coordinate in this class is in template space.
     */
    static BlockPos abs(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y + 1, z));
    }

    static BlockPos abs(GameTestHelper helper, BlockPos rel) {
        return abs(helper, rel.getX(), rel.getY(), rel.getZ());
    }

    /** Container contact that must also be made from the floor, never from a table top. */
    private static final class FloorContactLeg extends PathingHarness.Leg {
        private final PathingHarness.Leg inner;
        private final int floorY;

        FloorContactLeg(PathingHarness.Leg inner, int floorY) {
            super(inner.name, inner.budget);
            this.inner = inner;
            this.floorY = floorY;
        }

        @Override
        boolean arrived(net.minecraft.server.level.ServerLevel level,
                        com.hearthstead.entity.SettlerEntity settler) {
            return inner.arrived(level, settler) && settler.onGround()
                && settler.getY() < floorY + 0.6D;
        }

        @Override
        void drive(net.minecraft.server.level.ServerLevel level,
                   com.hearthstead.entity.SettlerEntity settler) {
            inner.drive(level, settler);
        }
    }

    /** Tiny builder in test-relative coordinates. */
    private static final class Kit {
        private final GameTestHelper helper;

        Kit(GameTestHelper helper) {
            this.helper = helper;
        }

        void set(int x, int y, int z, BlockState state) {
            helper.getLevel().setBlockAndUpdate(abs(helper, x, y, z), state);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (BlockPos p : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) {
                set(p.getX(), p.getY(), p.getZ(), state);
            }
        }

        /** Stone-brick walls around x1..x2, z1..z2 from y=1 to top-1, plank roof at top. */
        void shell(int x1, int z1, int x2, int z2, int top) {
            BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
            fill(x1, 1, z1, x2, top - 1, z1, wall);
            fill(x1, 1, z2, x2, top - 1, z2, wall);
            fill(x1, 1, z1, x1, top - 1, z2, wall);
            fill(x2, 1, z1, x2, top - 1, z2, wall);
            fill(x1, top, z1, x2, top, z2, Blocks.OAK_PLANKS.defaultBlockState());
        }

        void floor(int x1, int z1, int x2, int z2, int y) {
            fill(x1, y, z1, x2, y, z2, Blocks.OAK_PLANKS.defaultBlockState());
        }

        void wall(int x1, int y1, int z1, int x2, int y2, int z2) {
            fill(x1, y1, z1, x2, y2, z2, Blocks.OAK_PLANKS.defaultBlockState());
        }

        void door(int x, int y, int z, Direction facing) {
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.OPEN, false).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            set(x, y, z, lower);
            set(x, y + 1, z, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }

        void stair(int x, int y, int z, Direction facing) {
            set(x, y, z, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing));
        }

        void chest(int x, int y, int z) {
            set(x, y, z, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
        }

        /** Foot at (x,y,z), head one block towards {@code facing}. */
        void bed(int x, int y, int z, Direction facing) {
            Block bed = Blocks.WHITE_BED;
            BlockState foot = bed.defaultBlockState().setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT);
            set(x, y, z, foot);
            BlockPos head = new BlockPos(x, y, z).relative(facing);
            set(head.getX(), head.getY(), head.getZ(), foot.setValue(BedBlock.PART, BedPart.HEAD));
        }
    }
}
