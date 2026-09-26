package com.hearthstead.settlement.work;

import com.hearthstead.block.FishersChairBlock;
import com.hearthstead.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Bounded loaded-only surface-water survey. Deep columns and disconnected puddles do not add up. */
public final class FishingGrounds {
    public static final int RADIUS = 16, MIN_WATER = 20, HEIGHT = 3;
    public record Result(int waterCount, BlockPos shorePosition, Direction direction,
                         boolean ready, boolean loadedComplete, String blocker) {}
    private FishingGrounds() {}

    public static Result scan(ServerLevel level, BlockPos anchor) {
        Set<BlockPos> water = new HashSet<>();
        boolean loaded = true;
        for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-RADIUS,-HEIGHT,-RADIUS),
                anchor.offset(RADIUS,HEIGHT,RADIUS))) {
            if (!level.hasChunkAt(p)) { loaded = false; continue; }
            var fluid = level.getFluidState(p);
            if (fluid.is(FluidTags.WATER) && fluid.isSource()
                    && !level.getFluidState(p.above()).is(FluidTags.WATER)
                    && level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()) {
                water.add(p.immutable());
            }
        }
        int largest = 0, chosenCount = 0;
        BlockPos chosen = null;
        Direction chosenDir = null;
        double nearest = Double.MAX_VALUE;
        while (!water.isEmpty()) {
            BlockPos seed = water.iterator().next();
            Set<BlockPos> body = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            water.remove(seed); queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos p = queue.removeFirst(); body.add(p);
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos next = p.relative(dir);
                    if (water.remove(next)) queue.addLast(next);
                }
            }
            largest = Math.max(largest,body.size());
            if (body.size() < MIN_WATER) continue;
            for (BlockPos surface : body) for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos chair = surface.relative(dir).above();
                Direction facing = dir.getOpposite();
                if (!validChair(level,chair,facing)) continue;
                double distance = chair.distSqr(anchor);
                if (distance < nearest) {
                    nearest = distance; chosen = chair; chosenDir = facing; chosenCount = body.size();
                }
            }
        }
        return new Result(chosen == null ? largest : chosenCount, chosen, chosenDir,
            chosen != null, loaded, chosen != null ? "ready" : largest < MIN_WATER
                ? "fisher_needs_connected_water" : "fisher_needs_shore_chair");
    }

    public static boolean validChair(ServerLevel level, BlockPos chair, Direction facing) {
        if (!level.hasChunkAt(chair) || !level.hasChunkAt(chair.relative(facing))) return false;
        var state = level.getBlockState(chair);
        if (!state.is(ModBlocks.FISHERS_CHAIR.get()) || state.getValue(FishersChairBlock.FACING) != facing
                || !level.getFluidState(chair).isEmpty()
                || !level.getBlockState(chair.below()).isFaceSturdy(level,chair.below(),Direction.UP)
                || !level.getBlockState(chair.above()).getCollisionShape(level,chair.above()).isEmpty()) return false;
        BlockPos water = chair.below().relative(facing);
        if (!level.getFluidState(water).is(FluidTags.WATER) || !level.getFluidState(water).isSource()) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side != facing && standable(level,chair.relative(side))) return true;
        }
        return false;
    }

    public static boolean standable(ServerLevel level, BlockPos p) {
        return level.hasChunkAt(p) && level.getFluidState(p).isEmpty()
            && level.getFluidState(p.above()).isEmpty()
            && level.getBlockState(p).getCollisionShape(level,p).isEmpty()
            && level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()
            && level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP);
    }
}
