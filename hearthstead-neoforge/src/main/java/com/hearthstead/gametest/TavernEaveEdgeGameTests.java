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

/** Isolated recorded edge geometry, not a full Tavern or historical path replay. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TavernEaveEdgeGameTests {
    private static final BlockPos SOURCE_ORIGIN = new BlockPos(-2967234, -59, 7003498);
    private static final BlockPos OFFSET = new BlockPos(8, 2, 4);
    @GameTest(template = "empty32", timeoutTicks = 600, batch = "tavern_eave_edge_lower")
    public void capturedUnderEaveCanReachSlab(GameTestHelper h) {
        run(h, new Vec3(3.686486683, 1, 10.731811434), "captured");
    }
    @GameTest(template = "empty32", timeoutTicks = 600, batch = "tavern_eave_edge_control")
    public void outsideEaveCanReachSameSlab(GameTestHelper h) {
        run(h, new Vec3(4.5, 1, 11.5), "outside_control");
    }
    private static void run(GameTestHelper h, Vec3 localStart, String name) {
        Map<BlockPos, BlockState> blocks = geometry();
        BlockPos origin = h.absolutePos(OFFSET);
        // Only this bounded observed crop is authoritative; no later blueprint blocks are filled in.
        for (BlockPos at : BlockPos.betweenClosed(origin.offset(1, 0, 8), origin.offset(6, 6, 13)))
            h.getLevel().setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        blocks.forEach((pos, state) -> h.getLevel().setBlock(origin.offset(pos), state, Block.UPDATE_CLIENTS));
        blocks.forEach((pos, state) -> h.assertTrue(h.getLevel().getBlockState(origin.offset(pos)).equals(state),
            "recorded edge states remain exact"));
        SettlerEntity mob = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(mob != null, "edge actor constructs");
        // Isolate ordinary navigation from unrelated profession/needs destination goals.
        for (var wrapped : List.copyOf(mob.goalSelector.getAvailableGoals())) mob.goalSelector.removeGoal(wrapped.getGoal());
        for (var wrapped : List.copyOf(mob.targetSelector.getAvailableGoals())) mob.targetSelector.removeGoal(wrapped.getGoal());
        Vec3 start = Vec3.atLowerCornerOf(origin).add(localStart);
        mob.moveTo(start.x, start.y, start.z, 0, 0); // Setup only.
        mob.bag.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox()), "start body is collision-free");
        h.assertTrue(!h.getLevel().noCollision(mob, mob.getBoundingBox().move(0, -.01, 0)), "start has physical support");
        BlockPos target = origin.offset(4, 2, 10);
        var targetBody = mob.getBoundingBox().move(Vec3.atBottomCenterOf(target).subtract(mob.position()));
        h.assertTrue(h.getLevel().noCollision(mob, targetBody)
            && !h.getLevel().noCollision(mob, targetBody.move(0, -.01, 0)), "destination fits and has slab support");
        h.assertTrue(h.getLevel().addFreshEntity(mob), "edge actor enters world");
        float health = mob.getHealth();
        boolean[] finished = {false};
        Hearthstead.LOGGER.info("TAVERN_EAVE_EDGE start name={} sourceOrigin={} sourceStart={} actualOrigin={} actualStart={} target={}",
            name, SOURCE_ORIGIN, Vec3.atLowerCornerOf(SOURCE_ORIGIN).add(localStart), origin, start, target);
        h.onEachTick(() -> {
            if (finished[0]) return;
            h.assertTrue(mob.isAlive() && mob.getHealth() == health, "edge navigation preserves health");
            int count = 0;
            for (int i = 0; i < mob.bag.getContainerSize(); i++) {
                ItemStack stack = mob.bag.getItem(i);
                h.assertTrue(stack.isEmpty() || stack.is(Items.COBBLESTONE), "edge bag has no unrelated items");
                count += stack.getCount();
            }
            h.assertTrue(count == 1, "edge navigation preserves physical custody");
            Vec3 relative = mob.position().subtract(Vec3.atLowerCornerOf(origin));
            h.assertTrue(relative.x >= 2.3 && relative.x <= 5.7 && relative.z >= 9.3 && relative.z <= 12.7
                && relative.y >= .99, "route remains in the recorded supported crop");
            h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6)), "body never clips recorded blocks");
            if (h.getTick() % 20 == 0) {
                // Ordinary exact-target path creation, no injected path nodes or movement state.
                var path = mob.getNavigation().createPath(target, 0);
                boolean accepted = path != null && mob.getNavigation().moveTo(path, .8);
                Hearthstead.LOGGER.info("TAVERN_EAVE_EDGE sample name={} tick={} feet={} relative={} body={} accepted={} reach={} next={}",
                    name, h.getTick(), mob.position(), relative, mob.getBoundingBox(), accepted,
                    path != null && path.canReach(), path == null || path.isDone() ? null : path.getNextNodePos());
            }
        });
        GameTestTicks.at(h, 580, () -> {
            if (!finished[0]) Hearthstead.LOGGER.error(
                "TAVERN_EAVE_EDGE deadline name={} sourceOrigin={} actualOrigin={} feet={} eye={} body={} target={} navDone={}",
                name, SOURCE_ORIGIN, origin, mob.position(), mob.getEyePosition(), mob.getBoundingBox(), target,
                mob.getNavigation().isDone());
        });
        h.succeedWhen(() -> {
            Vec3 delta = mob.position().subtract(Vec3.atBottomCenterOf(target));
            h.assertTrue(delta.horizontalDistanceSqr() < .09 && Math.abs(delta.y) < .08 && mob.onGround(),
                "actor must physically arrive on the supported slab");
            h.assertTrue(mob.getHealth() == health && mob.bag.getItem(0).is(Items.COBBLESTONE)
                && mob.bag.getItem(0).getCount() == 1, "arrival preserves health and carried item");
            finished[0] = true;
            Hearthstead.LOGGER.info("TAVERN_EAVE_EDGE arrival name={} feet={} target={}", name, mob.position(), target);
        });
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
