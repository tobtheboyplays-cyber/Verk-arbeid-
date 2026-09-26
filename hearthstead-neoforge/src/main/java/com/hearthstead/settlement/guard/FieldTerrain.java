package com.hearthstead.settlement.guard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Terrain questions asked identically by the client preview and the server
 * order: where a soldier would stand, whether an aimed block is "high ground"
 * (wall walk or tower top), and the slot list for a formation. Pure reads of a
 * {@link BlockGetter}; never loads chunks on its own (callers pass a level and
 * check {@code hasChunkAt} for the centre).
 */
public final class FieldTerrain {
    /** Height above the surrounding ground that counts as a wall or tower. */
    public static final int HIGH_GROUND_RISE = 3;
    private static final int SNAP_UP = 3;
    private static final int SNAP_DOWN = 5;

    public static boolean standable(BlockGetter level, BlockPos feet) {
        BlockState at = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        BlockState below = level.getBlockState(feet.below());
        return at.getCollisionShape(level, feet).isEmpty()
            && at.getFluidState().isEmpty()
            && head.getCollisionShape(level, feet.above()).isEmpty()
            && !below.getCollisionShape(level, feet.below()).isEmpty()
            && below.getFluidState().isEmpty();
    }

    /** Nearest standable cell in this column around {@code near}, or null. */
    @Nullable
    public static BlockPos snap(BlockGetter level, BlockPos near) {
        if (standable(level, near)) return near;
        for (int d = 1; d <= Math.max(SNAP_UP, SNAP_DOWN); d++) {
            if (d <= SNAP_DOWN) {
                BlockPos down = near.below(d);
                if (standable(level, down)) return down;
            }
            if (d <= SNAP_UP) {
                BlockPos up = near.above(d);
                if (standable(level, up)) return up;
            }
        }
        return null;
    }

    /** Ground cell below {@code from} (falls through air), within {@code depth}. */
    @Nullable
    public static BlockPos groundBelow(BlockGetter level, BlockPos from, int depth) {
        for (int d = 0; d <= depth; d++) {
            BlockPos cell = from.below(d);
            if (standable(level, cell)) return cell;
        }
        return null;
    }

    /**
     * True when {@code feet} stands on something raised: at least one of the
     * cells two or three blocks away drops by {@link #HIGH_GROUND_RISE} or more.
     */
    public static boolean elevated(BlockGetter level, BlockPos feet) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int reach = 2; reach <= 3; reach++) {
                BlockPos probe = feet.relative(dir, reach);
                if (!level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()) {
                    continue;
                }
                BlockPos ground = groundBelow(level, probe, 12);
                int drop = ground == null ? 12 : feet.getY() - ground.getY();
                if (drop >= HIGH_GROUND_RISE) return true;
            }
        }
        return false;
    }

    /** What a raycast on a block means for a command. */
    public record Aim(BlockPos stand, boolean high, BlockPos base) {
    }

    /**
     * Resolves an aimed block face into where soldiers stand. {@code stand}
     * is the walkable cell on top (wall walk / tower floor / ground),
     * {@code high} marks a wall or tower top and {@code base} is the ground in
     * front of it on the commander's side (knights hold the line there).
     */
    @Nullable
    public static Aim resolve(BlockGetter level, BlockPos hit, Direction face,
                              BlockPos commanderFeet) {
        BlockPos stand;
        if (face == Direction.UP) {
            stand = snap(level, hit.above());
        } else if (face == Direction.DOWN) {
            stand = groundBelow(level, hit.below(), 16);
        } else {
            // Side face: climb the struck column to its walkable top.
            BlockPos top = null;
            for (int up = 1; up <= 10; up++) {
                BlockPos cell = hit.above(up);
                if (standable(level, cell)) { top = cell; break; }
            }
            BlockPos front = groundBelow(level, hit.relative(face), 16);
            if (top != null && front != null && top.getY() - front.getY() >= HIGH_GROUND_RISE) {
                return new Aim(top, true, front);
            }
            stand = front != null ? front : top;
        }
        if (stand == null) return null;
        boolean high = elevated(level, stand);
        BlockPos base = stand;
        if (high && commanderFeet != null) {
            base = baseToward(level, stand, commanderFeet);
        }
        return new Aim(stand, high, base);
    }

    /** Ground at the foot of a raised surface, on the side facing the commander. */
    public static BlockPos baseToward(BlockGetter level, BlockPos top, BlockPos commander) {
        int dx = Integer.signum(commander.getX() - top.getX());
        int dz = Integer.signum(commander.getZ() - top.getZ());
        if (dx == 0 && dz == 0) dz = 1;
        for (int step = 1; step <= 6; step++) {
            BlockPos probe = top.offset(dx * step, 0, dz * step);
            BlockPos ground = groundBelow(level, probe, 16);
            if (ground != null && top.getY() - ground.getY() >= HIGH_GROUND_RISE) {
                return ground;
            }
        }
        return top;
    }

    /** A formation slot after terrain snapping. */
    public record PlacedSlot(BlockPos pos, boolean valid) {
    }

    /** Line/block formation snapped to the ground column by column. */
    public static List<PlacedSlot> placeLine(BlockGetter level, BlockPos center, int octant,
                                             int soldiers, int width, FieldOrderRules.Group role) {
        List<PlacedSlot> placed = new ArrayList<>();
        int spacing = FormationMath.spacing(role);
        int gap = FormationMath.rankGap(role);
        for (FormationMath.Slot slot : FormationMath.line(center, octant, soldiers, width, spacing, gap)) {
            BlockPos snapped = snap(level, slot.pos());
            placed.add(snapped == null ? new PlacedSlot(slot.pos(), false)
                : new PlacedSlot(snapped, Math.abs(snapped.getY() - center.getY()) <= 4));
        }
        return placed;
    }

    /** Wall walk / tower top: as many cells as fit, then invalid overflow at the centre. */
    public static List<PlacedSlot> placeHigh(BlockGetter level, BlockPos stand, int soldiers) {
        List<PlacedSlot> placed = new ArrayList<>();
        List<BlockPos> cells = FormationMath.surface(stand, Math.min(soldiers, FormationMath.MAX_SOLDIERS), 6,
            cell -> standable(level, cell));
        for (BlockPos cell : cells) placed.add(new PlacedSlot(cell, true));
        while (placed.size() < Math.min(soldiers, FormationMath.MAX_SOLDIERS)) {
            placed.add(new PlacedSlot(stand, false));
        }
        return placed;
    }

    /** Escort ring around the commander, snapped. */
    public static List<PlacedSlot> placeEscort(BlockGetter level, BlockPos commander, int octant,
                                               int soldiers, boolean archers) {
        List<PlacedSlot> placed = new ArrayList<>();
        for (FormationMath.Slot slot : FormationMath.escort(commander, octant, soldiers, archers)) {
            BlockPos snapped = snap(level, slot.pos());
            placed.add(snapped == null ? new PlacedSlot(slot.pos(), false) : new PlacedSlot(snapped, true));
        }
        return placed;
    }

    private FieldTerrain() {
    }
}
