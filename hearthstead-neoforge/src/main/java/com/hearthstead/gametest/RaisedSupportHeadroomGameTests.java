package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.SettlerDoorGoal;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.Collection;
import java.util.List;

/** QA-HOUSE-EXIT-02 controls; actual geometry/path/physics, no synthetic path nodes. */
@GameTestHolder(Hearthstead.MODID)
public final class RaisedSupportHeadroomGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> cases() {
        return List.of(test("low_eave_fence_rejected", 0), test("open_fence_crosses", 1),
            test("supported_fence_start", 2), test("slab_stairs_cross", 3),
            test("carpet_low_door_rejected", 4), test("bare_low_door_crosses", 5));
    }

    private static TestFunction test(String name, int kind) {
        return new TestFunction("house_exit_controls", "house_exit_control_" + name,
            Hearthstead.MODID + ":empty16", Rotation.NONE, 600, 0L, true, h -> run(h, name, kind));
    }

    private static void run(GameTestHelper h, String name, int kind) {
        lane(h);
        Vec3 start = new Vec3(6.5D, 1D, 3.5D);
        BlockPos destination = new BlockPos(6, 1, 9);
        if (kind <= 2) {
            put(h, 6, 1, 5, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            put(h, 6, 1, 6, Blocks.OAK_FENCE.defaultBlockState());
            start = kind == 2 ? new Vec3(6.5D, 2.5D, 6.5D) : new Vec3(6.5D, 2D, 5.5D);
            if (kind == 0) put(h, 6, 4, 5, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
        } else if (kind == 3) {
            put(h, 6, 1, 5, Blocks.OAK_SLAB.defaultBlockState());
            put(h, 6, 1, 6, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            for (int z = 7; z <= 10; z++) put(h, 6, 1, z, Blocks.STONE.defaultBlockState());
            destination = new BlockPos(6, 2, 9);
        } else {
            put(h, 6, 3, 5, Blocks.STONE.defaultBlockState());
            BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            put(h, 6, 1, 5, door);
            put(h, 6, 2, 5, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            if (kind == 4) put(h, 6, 1, 6, Blocks.WHITE_CARPET.defaultBlockState());
        }
        Vec3 origin = Vec3.atLowerCornerOf(h.absolutePos(BlockPos.ZERO));
        Vec3 at = start.add(origin);
        SettlerEntity mob = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(mob != null, "Raised-support control actor constructs");
        mob.moveTo(at.x, at.y, at.z, 0, 0); // sole initial placement
        for (var goal : List.copyOf(mob.goalSelector.getAvailableGoals())) {
            if (!(goal.getGoal() instanceof SettlerDoorGoal)) mob.goalSelector.removeGoal(goal.getGoal());
        }
        for (var goal : List.copyOf(mob.targetSelector.getAvailableGoals())) mob.targetSelector.removeGoal(goal.getGoal());
        mob.bag.setItem(0, new ItemStack(Items.DIRT, 3));
        List<ItemStack> bag = new java.util.ArrayList<>();
        for (int i = 0; i < mob.bag.getContainerSize(); i++) bag.add(mob.bag.getItem(i).copy());
        h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox()), "Raised-support start is collision-free");
        h.assertTrue(!h.getLevel().noCollision(mob, mob.getBoundingBox().move(0, -.01D, 0)),
            "Raised-support start has actual support");
        h.assertTrue(h.getLevel().addFreshEntity(mob), "Raised-support actor enters fixture");
        float health = mob.getHealth();
        BlockPos target = h.absolutePos(destination);
        boolean reject = kind == 0 || kind == 4;
        boolean[] finished = {false};
        boolean[] requested = {false};
        boolean[] crossed = {false};
        Vec3[] previous = {mob.position()};
        long started = h.getTick();
        h.onEachTick(() -> {
            if (finished[0]) return;
            h.assertTrue(mob.isAlive() && mob.getHealth() == health, "Raised-support travel preserves life and health");
            h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6D)),
                "Raised-support travel obeys whole-body collision");
            h.assertTrue(mob.position().distanceToSqr(previous[0]) <= 1D, "Raised-support travel has no discontinuous movement");
            previous[0] = mob.position();
            for (int i = 0; i < bag.size(); i++) h.assertTrue(ItemStack.matches(bag.get(i), mob.bag.getItem(i)),
                "Raised-support travel preserves every inventory slot");
            if (mob.getBoundingBox().minZ > origin.z + 7D) crossed[0] = true;
            long elapsed = h.getTick() - started;
            if (elapsed >= 21 && (elapsed - 21) % 20 == 0) {
                Path path = mob.getNavigation().createPath(target, 0);
                if (!requested[0]) Hearthstead.LOGGER.info("HOUSE_SUPPORT start name={} feet={} target={} path={}",
                    name, mob.position().subtract(origin), destinationText(target, origin), describe(path, origin));
                requested[0] = true;
                if (reject) {
                    if (path != null && path.canReach()) Hearthstead.LOGGER.error("HOUSE_SUPPORT illegal complete route {} {}",
                        name, describe(path, origin));
                    h.assertTrue(path == null || !path.canReach(), "Low clearance must not promise a complete route");
                } else h.assertTrue(path != null && path.canReach(), "Clear supported control must retain a complete route");
                if (path != null) mob.getNavigation().moveTo(path, .8D);
            }
        });
        GameTestTicks.at(h, 200, () -> {
            if (reject && !finished[0]) {
                h.assertTrue(requested[0] && !crossed[0], "Blocked control remains before its physical obstruction");
                finished[0] = true;
                Hearthstead.LOGGER.info("HOUSE_SUPPORT rejected-and-safe {}", name);
                h.succeed();
            }
        });
        GameTestTicks.at(h, 580, () -> {
            if (!finished[0]) {
                finished[0] = true;
                Hearthstead.LOGGER.error("HOUSE_SUPPORT deadline name={} feet={} box={} motion={} path={}",
                    name, mob.position().subtract(origin), mob.getBoundingBox(), mob.getDeltaMovement(),
                    describe(mob.getNavigation().getPath(), origin));
                h.assertTrue(false, "Clear supported control must physically reach its destination");
            }
        });
        if (!reject) h.startSequence().thenWaitUntil(() -> {
            Vec3 delta = mob.position().subtract(Vec3.atBottomCenterOf(target));
            h.assertTrue(requested[0] && crossed[0] && delta.x * delta.x + delta.z * delta.z < .25D && Math.abs(delta.y) < .2D,
                "Clear supported control must physically cross and arrive");
        }).thenExecute(() -> {
            finished[0] = true;
            Hearthstead.LOGGER.info("HOUSE_SUPPORT physical-success name={} tick={} feet={}",
                name, h.getTick(), mob.position().subtract(origin));
        }).thenSucceed();
    }

    private static void lane(GameTestHelper h) {
        for (int x = 3; x <= 9; x++) for (int z = 1; z <= 13; z++) {
            put(h, x, 0, z, Blocks.STONE.defaultBlockState());
            for (int y = 1; y <= 7; y++) put(h, x, y, z, Blocks.AIR.defaultBlockState());
        }
        // Tall continuous walls/end caps prevent an outside route from passing.
        for (int y = 1; y <= 6; y++) for (int z = 2; z <= 12; z++) {
            put(h, 5, y, z, Blocks.STONE.defaultBlockState());
            put(h, 7, y, z, Blocks.STONE.defaultBlockState());
        }
        for (int y = 1; y <= 6; y++) {
            put(h, 6, y, 2, Blocks.STONE.defaultBlockState());
            put(h, 6, y, 12, Blocks.STONE.defaultBlockState());
        }
    }

    private static void put(GameTestHelper h, int x, int y, int z, BlockState state) {
        h.getLevel().setBlock(h.absolutePos(new BlockPos(x, y, z)), state, Block.UPDATE_CLIENTS);
    }

    private static String destinationText(BlockPos pos, Vec3 origin) {
        return Vec3.atLowerCornerOf(pos).subtract(origin).toString();
    }

    private static String describe(Path path, Vec3 origin) {
        if (path == null) return "none";
        StringBuilder out = new StringBuilder("reachable=" + path.canReach() + " nodes=[");
        for (int i = 0; i < path.getNodeCount(); i++) out.append(destinationText(path.getNodePos(i), origin))
            .append('/').append(path.getNode(i).type).append(';');
        return out.append(']').toString();
    }
}
