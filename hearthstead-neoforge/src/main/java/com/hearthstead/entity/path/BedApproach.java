package com.hearthstead.entity.path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Where a settler may stand to lie down in one claimed bed.
 *
 * <p>Shared by the night-rest goal and its GameTests so both measure the same
 * rule. Read-only: it never moves the settler or changes a claim.
 */
public final class BedApproach {
    /** Squared distance from the feet to either bed half's centre within which sleep may start. */
    public static final double SLEEP_REACH_SQR = 4.5D;

    private BedApproach() {
    }

    /**
     * Legal, visible feet cells from which the settler can lie down in
     * {@code bed}: all eight cells around either half, on the bed's floor or
     * one block up or down (a stair tread beside the bed, a slab, a step),
     * carpet included. A bed tucked between another bed, a wall and a stair
     * (the owner's upstairs room) is reached from the diagonal floor cell or
     * the stair tread, which the old four-sides-of-the-head rule never
     * offered, leaving that settler sleeping rough every night.
     */
    public static Set<BlockPos> contactCells(ServerLevel level, Mob settler, BlockPos bed) {
        Set<BlockPos> parts = bedParts(level, bed);
        if (parts.isEmpty()) return new LinkedHashSet<>();
        return StandCells.standCells(level, settler, parts, SLEEP_REACH_SQR);
    }

    /** True when the settler, where it stands now, may lie down in {@code bed}. */
    public static boolean canContact(ServerLevel level, Mob settler, BlockPos bed) {
        Set<BlockPos> parts = bedParts(level, bed);
        return !parts.isEmpty() && StandCells.inReach(level, settler, parts, SLEEP_REACH_SQR);
    }

    /** The claimed bed block and its other half, when both are loaded and consistent. */
    public static Set<BlockPos> bedParts(ServerLevel level, BlockPos claimedBed) {
        Set<BlockPos> parts = new LinkedHashSet<>();
        if (!level.hasChunkAt(claimedBed)) return parts;
        if (!(level.getBlockState(claimedBed).getBlock() instanceof BedBlock)) return parts;
        parts.add(claimedBed.immutable());
        var state = level.getBlockState(claimedBed);
        var facing = state.getValue(BedBlock.FACING);
        var connection = state.getValue(BedBlock.PART) == BedPart.HEAD
            ? facing.getOpposite() : facing;
        BlockPos other = claimedBed.relative(connection);
        if (level.hasChunkAt(other)) {
            var otherState = level.getBlockState(other);
            if (otherState.is(state.getBlock())
                && otherState.getValue(BedBlock.FACING).equals(state.getValue(BedBlock.FACING))
                && otherState.getValue(BedBlock.PART) != state.getValue(BedBlock.PART)) {
                parts.add(other.immutable());
            }
        }
        return parts;
    }
}
