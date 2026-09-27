package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Physical controls for the eave transition guard; no path nodes or movement fields injected. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TavernEaveTransitionControlGameTests {
    @GameTest(template="empty32", timeoutTicks=400, batch="tavern_eave_edge_02_removed")
    public void removedOverhangAllowsCapturedApproach(GameTestHelper h) { run(h, "removed"); }
    @GameTest(template="empty32", timeoutTicks=400, batch="tavern_eave_edge_02_diagonal")
    public void openDiagonalJumpRemainsUsable(GameTestHelper h) { run(h, "diagonal"); }
    @GameTest(template="empty32", timeoutTicks=400, batch="tavern_eave_edge_02_stairs")
    public void ordinaryStairAscentRemainsUsable(GameTestHelper h) { run(h, "stairs"); }
    @GameTest(template="empty32", timeoutTicks=400, batch="tavern_eave_edge_02_closed")
    public void closedDestinationIsRefusedWithoutClipping(GameTestHelper h) { run(h, "closed"); }

    private static void run(GameTestHelper h, String mode) {
        BlockPos origin = h.absolutePos(new BlockPos(8, 2, 4));
        boolean removed = mode.equals("removed"), closed = mode.equals("closed");
        for (BlockPos at : BlockPos.betweenClosed(origin, origin.offset(8, 7, 14)))
            h.getLevel().setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        Vec3 localStart;
        BlockPos target;
        if (removed) {
            Map<BlockPos, BlockState> blocks = geometry();
            blocks.put(new BlockPos(3, 3, 10), Blocks.AIR.defaultBlockState());
            blocks.forEach((pos, state) -> h.getLevel().setBlock(origin.offset(pos), state, Block.UPDATE_CLIENTS));
            blocks.forEach((pos, state) -> h.assertTrue(h.getLevel().getBlockState(origin.offset(pos)).equals(state),
                "removed-eave fixture preserves all other recorded states"));
            localStart = new Vec3(3.686486683, 1, 10.731811434);
            target = origin.offset(4, 2, 10);
        } else {
            for (BlockPos at : BlockPos.betweenClosed(origin.offset(1, 0, 1), origin.offset(7, 0, 7)))
                h.getLevel().setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            localStart = new Vec3(3.5, 1, 3.5);
            if (mode.equals("stairs")) {
                h.getLevel().setBlock(origin.offset(4, 1, 3), Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, Direction.EAST), Block.UPDATE_CLIENTS);
                target = origin.offset(5, 2, 3);
                h.getLevel().setBlock(target.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else {
                target = origin.offset(4, 2, 4);
                h.getLevel().setBlock(target.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (closed) h.getLevel().setBlock(target.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        SettlerEntity mob = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(mob != null, "control actor constructs");
        for (var wrapped : List.copyOf(mob.goalSelector.getAvailableGoals())) mob.goalSelector.removeGoal(wrapped.getGoal());
        for (var wrapped : List.copyOf(mob.targetSelector.getAvailableGoals())) mob.targetSelector.removeGoal(wrapped.getGoal());
        Vec3 start = Vec3.atLowerCornerOf(origin).add(localStart);
        mob.moveTo(start.x, start.y, start.z, 0, 0);
        mob.bag.setItem(0, new ItemStack(Items.COBBLESTONE));
        h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox())
            && !h.getLevel().noCollision(mob, mob.getBoundingBox().move(0, -.01, 0)), "control starts on clear supported ground");
        var targetBody = mob.getBoundingBox().move(Vec3.atBottomCenterOf(target).subtract(start));
        h.assertTrue(closed != h.getLevel().noCollision(mob, targetBody), "destination clearance matches control");
        h.assertTrue(!h.getLevel().noCollision(mob, targetBody.move(0, -.01, 0)), "target has physical support");
        h.assertTrue(h.getLevel().addFreshEntity(mob), "control actor enters world");
        float health = mob.getHealth();
        boolean[] diagonal = {false}, reached = {false};
        int[] requests = {0};
        Runnable invariants = () -> {
            h.assertTrue(mob.isAlive() && mob.getHealth() == health, "control preserves health");
            h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6)), "control never clips blocks");
            int count = 0;
            for (int i = 0; i < mob.bag.getContainerSize(); i++) {
                ItemStack item = mob.bag.getItem(i);
                h.assertTrue(item.isEmpty() || item.is(Items.COBBLESTONE), "control keeps exact item type");
                count += item.getCount();
            }
            h.assertTrue(count == 1, "control preserves physical custody");
        };
        h.onEachTick(() -> {
            invariants.run();
            reached[0] |= physicallyArrived(h, mob, target);
            if (closed) h.assertTrue(!reached[0], "closed destination cannot be physically entered");
            if (h.getTick() >= 20 && h.getTick() % 20 == 0) {
                requests[0]++;
                var path = mob.getNavigation().createPath(target, 0);
                if (path != null) for (int i = 1; i < path.getNodeCount(); i++) {
                    BlockPos a = path.getNodePos(i - 1), b = path.getNodePos(i);
                    diagonal[0] |= b.getY() > a.getY() && b.getX() != a.getX() && b.getZ() != a.getZ();
                }
                if (closed) h.assertTrue(path == null || !path.canReach(), "closed target must not claim a reachable path");
                if (path != null) mob.getNavigation().moveTo(path, .8);
            }
        });
        GameTestTicks.at(h, 380, () -> Hearthstead.LOGGER.info(
            "TAVERN_EAVE02 control={} feet={} target={} body={} requests={} diagonal={} reached={} arrival={}",
            mode, mob.position(), target, mob.getBoundingBox(), requests[0], diagonal[0], reached[0],
            arrivalDetails(h, mob, target)));
        h.succeedWhen(() -> {
            invariants.run();
            h.assertTrue(requests[0] > 0, "control exercises ordinary path requests");
            if (closed) h.assertTrue(h.getTick() >= 200 && !reached[0], "closed control observes sustained safe refusal");
            else h.assertTrue(reached[0], "control must physically arrive");
            if (mode.equals("diagonal")) h.assertTrue(diagonal[0], "open upward diagonal remains planned and traversable");
        });
    }
    private static double waypointTolerance(SettlerEntity mob) {
        // Mapped PathNavigation.followThePath: the normal per-axis waypoint acceptance.
        return mob.getBbWidth() > .75F ? mob.getBbWidth() / 2.0F : .75F - mob.getBbWidth() / 2.0F;
    }

    private static boolean targetSupports(GameTestHelper h, SettlerEntity mob, BlockPos target) {
        var body = mob.getBoundingBox();
        BlockPos support = target.below();
        var shape = h.getLevel().getBlockState(support).getCollisionShape(h.getLevel(), support,
            net.minecraft.world.phys.shapes.CollisionContext.of(mob));
        for (var local : shape.toAabbs()) {
            var top = local.move(support);
            if (Math.abs(body.minY - top.maxY) < .001D
                && body.maxX > top.minX && body.minX < top.maxX
                && body.maxZ > top.minZ && body.minZ < top.maxZ) return true;
        }
        return false;
    }

    private static boolean physicallyArrived(GameTestHelper h, SettlerEntity mob, BlockPos target) {
        Vec3 delta = mob.position().subtract(Vec3.atBottomCenterOf(target));
        double tolerance = waypointTolerance(mob);
        return Math.abs(delta.x) <= tolerance && Math.abs(delta.z) <= tolerance
            && mob.onGround() && targetSupports(h, mob, target)
            && h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6));
    }

    private static String arrivalDetails(GameTestHelper h, SettlerEntity mob, BlockPos target) {
        BlockPos support = target.below();
        return "delta=" + mob.position().subtract(Vec3.atBottomCenterOf(target))
            + " axisTolerance=" + waypointTolerance(mob) + " onGround=" + mob.onGround()
            + " targetSupported=" + targetSupports(h, mob, target)
            + " bodyClear=" + h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6))
            + " support=" + support + ":" + h.getLevel().getBlockState(support)
            + " supportShapeLocal=" + h.getLevel().getBlockState(support).getCollisionShape(h.getLevel(), support,
                net.minecraft.world.phys.shapes.CollisionContext.of(mob)).toAabbs();
    }

    private static Map<BlockPos, BlockState> geometry() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(new BlockPos(2, 0, 9), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(2, 0, 10), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(2, 0, 11), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(2, 0, 12), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 9), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 2, 9), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 2, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 2, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 2, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 3, 9), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 3, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 3, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 3, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 4, 9), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 4, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(2, 4, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 0, 9), Blocks.STONE_BRICKS.defaultBlockState());
        blocks.put(new BlockPos(3, 0, 10), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(3, 0, 11), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(3, 0, 12), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(3, 1, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(3, 1, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 1, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 1, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 2, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(3, 2, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 2, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 2, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 3, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(3, 3, 10), Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH).setValue(StairBlock.HALF, Half.TOP));
        blocks.put(new BlockPos(3, 3, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 3, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3, 4, 9), Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        blocks.put(new BlockPos(3, 4, 10), Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        blocks.put(new BlockPos(3, 4, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 0, 9), Blocks.STONE_BRICKS.defaultBlockState());
        blocks.put(new BlockPos(4, 0, 10), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(4, 0, 11), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(4, 0, 12), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(4, 1, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(4, 1, 10), Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        blocks.put(new BlockPos(4, 1, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 1, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 2, 9), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 2, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 2, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 2, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 3, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(4, 3, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 3, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 3, 12), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(4, 4, 9), Blocks.DARK_OAK_PLANKS.defaultBlockState());
        blocks.put(new BlockPos(4, 4, 10), Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        blocks.put(new BlockPos(4, 4, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 0, 9), Blocks.STONE_BRICKS.defaultBlockState());
        blocks.put(new BlockPos(5, 0, 10), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(5, 0, 11), Blocks.GRASS_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(5, 1, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(5, 1, 10), Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        blocks.put(new BlockPos(5, 1, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 2, 9), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 2, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 2, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 3, 9), Blocks.COBBLESTONE.defaultBlockState());
        blocks.put(new BlockPos(5, 3, 10), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 3, 11), Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(5, 4, 9), Blocks.DARK_OAK_PLANKS.defaultBlockState());
        blocks.put(new BlockPos(5, 4, 10), Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        blocks.put(new BlockPos(5, 4, 11), Blocks.AIR.defaultBlockState());
        return blocks;
    }
}
