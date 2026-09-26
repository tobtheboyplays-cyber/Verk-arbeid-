package com.hearthstead.entity.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * The indoor block rules settlers share between planning (node evaluator),
 * following (navigation) and acting (door goal).
 *
 * <p>Pure, read-only helpers over a {@link BlockGetter}; they never load a
 * chunk themselves, callers pass an already-bounded getter.
 */
public final class IndoorBlocks {
    /** A collision top at or below this is a floor covering (carpet, snow, repeater). */
    public static final double LOW_COVER_MAX_Y = 0.5D;

    private IndoorBlocks() {
    }

    /**
     * A cell a settler can climb through: ladders and vines (the vanilla
     * climbable tag, except scaffolding whose standing top is not a climb),
     * and a trapdoor hatch directly above a ladder of the same facing. A
     * closed hatch counts only when it is wooden: the door goal opens it.
     */
    public static boolean climbable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.CLIMBABLE)) {
            return !state.is(Blocks.SCAFFOLDING);
        }
        return hatchOverLadder(level, pos, state);
    }

    /** A trapdoor in a floor opening, directly above a ladder facing the same way. */
    public static boolean hatchOverLadder(BlockGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof TrapDoorBlock)) return false;
        if (!state.getValue(TrapDoorBlock.OPEN) && !state.is(BlockTags.WOODEN_TRAPDOORS)) return false;
        BlockState below = level.getBlockState(pos.below());
        return below.is(Blocks.LADDER)
            && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
    }

    /**
     * A closed wooden door, any closed fence gate, or a closed wooden hatch
     * over a ladder: something a settler may open by hand to pass. Iron doors
     * and iron trapdoors are never openable. Returns the block position that
     * holds the open/closed state (the lower half for doors), or null.
     */
    @Nullable
    public static BlockPos openablePassage(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door) {
            if (!door.type().canOpenByHand()) return null;
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            return pos;
        }
        if (state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS)
            && hatchOverLadder(level, pos, state)) {
            return pos;
        }
        return null;
    }

    /**
     * True when closing the passage at {@code pos} now would put its closed
     * collision into a living body. A closed door is a 3/16 plate: a settler
     * standing right beside the doorway is not in its way.
     */
    public static boolean closingWouldHit(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BlockStateProperties.OPEN)) return false;
        BlockState closed = state.setValue(BlockStateProperties.OPEN, false);
        java.util.List<net.minecraft.world.phys.AABB> boxes = new java.util.ArrayList<>();
        for (var box : closed.getCollisionShape(level, pos).toAabbs()) boxes.add(box.move(pos));
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos upper = pos.above();
            BlockState upperState = level.getBlockState(upper);
            if (upperState.getBlock() instanceof DoorBlock && upperState.hasProperty(BlockStateProperties.OPEN)) {
                for (var box : upperState.setValue(BlockStateProperties.OPEN, false)
                        .getCollisionShape(level, upper).toAabbs()) boxes.add(box.move(upper));
            }
        }
        for (var box : boxes) {
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    box.inflate(0.02D), net.minecraft.world.entity.LivingEntity::isAlive).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOpen(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
    }

    /** True for blocks whose passability the pathfinder already models specially. */
    public static boolean modelledSpecially(BlockState state) {
        return state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof FenceGateBlock
            || state.getBlock() instanceof TrapDoorBlock
            || state.is(BlockTags.CLIMBABLE)
            || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS);
    }

    /**
     * Collision top of a low floor covering in this cell, or -1 when the cell
     * has no collision or a taller obstacle.
     */
    public static double lowCoverTop(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir()) return -1.0D;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return -1.0D;
        double top = shape.max(Direction.Axis.Y);
        return top <= LOW_COVER_MAX_Y && shape.min(Direction.Axis.Y) <= 0.0D ? top : -1.0D;
    }
}
