package com.hearthstead.settlement.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Temporary climbing for tall work (owner parity item: "scaffold or ladder
 * use is OK"). Settler pathing climbs ladders but deliberately not vanilla
 * scaffolding, so the Builder's scaffold is a column of real ladders hung
 * on the outside face of the wall he is raising (built bottom-up, so the
 * wall below his target always stands). He carries the ladders from the
 * hut, climbs, builds from the top rung, and takes every rung back down at
 * the end -- the ladders return to the hut. Recorded on the job, so a
 * restart still removes them.
 */
public final class BuilderScaffold {

    /** Rungs never go higher than this above the ground they start on. */
    public static final int MAX_RUNGS = 24;

    private BuilderScaffold() {
    }

    /** A planned column: rung cells bottom-up and the direction the ladders face. */
    public record Column(List<BlockPos> rungs, Direction facing) {
        public int missing(ServerLevel level) {
            int n = 0;
            for (BlockPos rung : rungs) {
                if (!level.getBlockState(rung).is(Blocks.LADDER)) {
                    n++;
                }
            }
            return n;
        }

        /** The lowest rung still to hang, or null when the column stands. */
        @Nullable
        public BlockPos next(ServerLevel level) {
            for (BlockPos rung : rungs) {
                if (!level.getBlockState(rung).is(Blocks.LADDER)) {
                    return rung;
                }
            }
            return null;
        }
    }

    /**
     * A ladder column from which {@code target} can be reached from the top
     * rung (two below it), hung on built wall, in cells the job does not
     * still need to fill. Null when none fits.
     */
    @Nullable
    public static Column plan(ServerLevel level, BuildJob job, BlockPos target) {
        Set<Long> pending = new HashSet<>();
        for (int i = 0; i < job.size(); i++) {
            if (!job.isDone(i) && !job.state(i).isAir()) {
                pending.add(job.pos(i).asLong());
            }
        }
        BlockPos top = target.below(2);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            List<BlockPos> rungs = new ArrayList<>();
            BlockPos cell = top.relative(dir);
            boolean ok = true;
            // walk down to the ground this column stands on
            while (true) {
                if (!level.isLoaded(cell) || pending.contains(cell.asLong())) {
                    ok = false;
                    break;
                }
                BlockState here = level.getBlockState(cell);
                if (!here.isAir() && !here.is(Blocks.LADDER)) {
                    ok = false;
                    break;
                }
                BlockPos wall = cell.relative(dir.getOpposite());
                if (!level.getBlockState(wall).isFaceSturdy(level, wall, dir)) {
                    ok = false;
                    break;
                }
                rungs.add(0, cell.immutable());
                BlockPos below = cell.below();
                if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    break; // standing ground reached
                }
                if (rungs.size() > MAX_RUNGS) {
                    ok = false;
                    break;
                }
                cell = below;
            }
            if (ok && !rungs.isEmpty()) {
                return new Column(List.copyOf(rungs), dir);
            }
        }
        return null;
    }

    /** Hangs one rung from the bag (exact-once: the ladder and the block together). */
    public static boolean hang(ServerLevel level, BuildJob job, BlockPos rung, Direction facing, Container bag) {
        if (!level.getBlockState(rung).isAir()) {
            return level.getBlockState(rung).is(Blocks.LADDER);
        }
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
        if (!ladder.canSurvive(level, rung) || BuilderStock.bagCount(bag, Items.LADDER) < 1) {
            return false;
        }
        BuilderStock.takeFromBag(bag, Items.LADDER, 1);
        if (!level.setBlock(rung, ladder, Block.UPDATE_ALL)) {
            BuilderStock.insert(bag, new ItemStack(Items.LADDER));
            return false;
        }
        job.scaffold.add(rung.immutable());
        BuildSiteSavedData.get(level).setDirty();
        return true;
    }

    /** Takes one rung down (topmost first); its ladder goes to the hut. */
    public static void takeDown(ServerLevel level, BuildJob job, BlockPos rung, List<Container> hut, BlockPos where) {
        if (level.getBlockState(rung).is(Blocks.LADDER)) {
            level.setBlock(rung, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            BuilderStock.store(level, hut, new ItemStack(Items.LADDER), where);
        }
        job.scaffold.remove(rung);
        BuildSiteSavedData.get(level).setDirty();
    }

    /** The highest rung still standing, or null. */
    @Nullable
    public static BlockPos topRung(BuildJob job) {
        BlockPos best = null;
        for (BlockPos rung : job.scaffold) {
            if (best == null || rung.getY() > best.getY()) {
                best = rung;
            }
        }
        return best;
    }
}
