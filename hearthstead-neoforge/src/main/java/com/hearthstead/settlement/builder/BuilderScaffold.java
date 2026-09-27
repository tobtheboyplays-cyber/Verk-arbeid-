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
import net.minecraft.world.phys.Vec3;

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
                // A bed's head / a door's upper half is placed WITH its
                // partner: never hang a rung in that cell (W29a lodging_small:
                // a rung in a bed-head cell left the bed blocked forever).
                BlockPos partner = job.companionPos(i);
                if (partner != null) {
                    pending.add(partner.asLong());
                }
            }
        }
        // The classic column: its top rung two below the target, one step out.
        Column direct = columnAt(level, pending, target.below(2), null);
        if (direct != null) {
            return direct;
        }
        // Sunday blocker (203 of 222 skips were roof courses): an eave or
        // ridge block has no wall two below it -- it overhangs, or sits over
        // the room. Search the cells around it for any rung whose top reaches
        // the target (eye within the building reach) and that can hang on a
        // wall face, fewest rungs first.
        Column best = null;
        Vec3 aim = Vec3.atCenterOf(target);
        for (int dy = 1; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos top = target.offset(dx, -dy, dz);
                    Vec3 eye = new Vec3(top.getX() + 0.5D, top.getY() + 1.62D, top.getZ() + 0.5D);
                    if (eye.distanceToSqr(aim) > REACH * REACH || top.equals(target)) {
                        continue;
                    }
                    Column column = columnAt(level, pending, top, target);
                    if (column != null && (best == null || column.rungs().size() < best.rungs().size())) {
                        best = column;
                    }
                }
            }
        }
        return best;
    }

    /** Reach from the top rung, a little inside the Builder's own. */
    private static final double REACH = 4.2D;

    /**
     * A ladder column whose TOP rung is {@code top} (or, with {@code target}
     * null, whose top rung is next to {@code top} in the classic layout),
     * hung on a sturdy wall face all the way down to standing ground.
     */
    @Nullable
    private static Column columnAt(ServerLevel level, Set<Long> pending, BlockPos top, @Nullable BlockPos target) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            List<BlockPos> rungs = new ArrayList<>();
            BlockPos cell = target == null ? top.relative(dir) : top;
            boolean ok = true;
            // walk down to the ground this column stands on
            while (true) {
                if (!level.isLoaded(cell) || pending.contains(cell.asLong())
                    || (target != null && cell.equals(target.below()))) {
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
                // Headroom over the top rung: the climber's body needs it.
                BlockPos head = rungs.get(rungs.size() - 1).above();
                if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()
                    || (target != null && head.equals(target))) {
                    continue;
                }
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
