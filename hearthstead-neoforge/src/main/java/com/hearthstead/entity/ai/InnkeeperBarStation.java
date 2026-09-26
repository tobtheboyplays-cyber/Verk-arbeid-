package com.hearthstead.entity.ai;

import com.hearthstead.block.AleTapBlock;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.TavernSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tavern lane: where an idle innkeeper stands to tend the bar (presentation only; it owns no stock,
 * cargo or reservation). Preferred: in front of an ale tap mounted one block up (the client plays
 * ALE_POUR there); otherwise facing a counter-high solid block (COUNTER_WIPE). Seats are never
 * counters and a stand is never beside a seat (a diner's aisle / exit corridor). Bounded scan of
 * the Tavern's own cells.
 */
final class InnkeeperBarStation {
    static final int MAX_CELLS = 2048;

    record Station(BlockPos stand, Direction face, boolean tap) {}

    private InnkeeperBarStation() {
    }

    static Station find(ServerLevel level, SettlerEntity host, Building tavern) {
        if (tavern == null || tavern.bounds == null) return null;
        Station counter = null;
        int cells = 0;
        for (BlockPos p : BlockPos.betweenClosed(tavern.bounds.minX(), tavern.bounds.minY(), tavern.bounds.minZ(),
                tavern.bounds.maxX(), tavern.bounds.maxY(), tavern.bounds.maxZ())) {
            if (++cells > MAX_CELLS) break;
            if (!level.hasChunkAt(p)) continue;
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof AleTapBlock) {
                // the nozzle points along FACING: stand below-and-in-front of it, facing the tap
                Direction out = state.getValue(AleTapBlock.FACING);
                BlockPos stand = p.below().relative(out);
                if (tavern.contains(stand) && !nextToSeat(level, stand) && TavernSeating.clearStand(level, host, stand))
                    return new Station(stand.immutable(), out.getOpposite(), true);
                continue;
            }
            if (counter != null || !counterTop(level, p)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos stand = p.relative(d);
                if (tavern.contains(stand) && !counterTop(level, stand) && !nextToSeat(level, stand)
                    && TavernSeating.clearStand(level, host, stand)) {
                    counter = new Station(stand.immutable(), d.getOpposite(), false);
                    break;
                }
            }
        }
        return counter;
    }

    /** A counter-high solid top (0.85..1.0) with open air above; the stand cell beside it must be
     * clear floor at the same height, so floors and walls never qualify. */
    static boolean counterTop(ServerLevel level, BlockPos p) {
        if (seat(level.getBlockState(p))) return false;          // a chair / bench / stool is never a bar
        var shape = level.getBlockState(p).getCollisionShape(level, p);
        if (shape.isEmpty() || shape.max(Direction.Axis.Y) < .85 || shape.max(Direction.Axis.Y) > 1.0) return false;
        if (!level.hasChunkAt(p.above())) return false;
        var above = level.getBlockState(p.above()).getCollisionShape(level, p.above());
        return above.isEmpty();
    }

    /** Anything a guest may sit on (vanilla stair chairs, tagged or Another Furniture seats). */
    static boolean seat(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.StairBlock
            || state.is(TavernSeating.SETTLER_SEATS) || TavernSeating.anotherFurnitureKind(state) != null;
    }

    /** The innkeeper never parks beside a seat: that cell is a diner's aisle / exit corridor. */
    static boolean nextToSeat(ServerLevel level, BlockPos stand) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(d);
            if (level.hasChunkAt(n) && seat(level.getBlockState(n))) return true;
        }
        return false;
    }
}
