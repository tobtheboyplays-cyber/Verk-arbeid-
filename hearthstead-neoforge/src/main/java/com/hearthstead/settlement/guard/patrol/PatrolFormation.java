package com.hearthstead.settlement.guard.patrol;

import com.hearthstead.settlement.guard.FormationMath;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Pure squad geometry for a walking patrol. Slots come from
 * {@link FormationMath#line} (the same slot math as the R/G field orders),
 * one or two abreast; rank depth is measured along the leader's own trail
 * rather than as a straight line behind him, so a column follows the wall
 * walk round its corners instead of cutting across them.
 */
public final class PatrolFormation {
    /** Blocks between ranks while walking. */
    public static final int RANK_GAP = 2;
    /** Blocks between the two files of a pair. */
    public static final int FILE_SPACING = 1;

    /** A squad slot: rank 0 is the leader's rank; lateral 1 is the leader's right. */
    public record Offset(int rank, int lateral) {
    }

    public static int width(PatrolRoute.Formation formation) {
        return formation == PatrolRoute.Formation.PAIRS ? 2 : 1;
    }

    /**
     * The slot of squad member {@code index} (0 = leader) in a squad of
     * {@code size}. The leader always holds (0, 0).
     */
    public static Offset slot(PatrolRoute.Formation formation, int size, int index) {
        int n = Math.max(1, Math.min(PatrolRules.MAX_SQUAD, size));
        int i = Math.max(0, Math.min(n - 1, index));
        List<FormationMath.Slot> slots = FormationMath.line(BlockPos.ZERO, 0, n, width(formation),
            FILE_SPACING, RANK_GAP);
        FormationMath.Slot s = slots.get(i);
        return new Offset(s.rank(), s.lateral());
    }

    /**
     * The point {@code back} blocks behind the leader measured along his
     * trail (newest first). A trail too short to measure falls back to a
     * straight line behind him along {@code octant}.
     */
    public static BlockPos trailPoint(BlockPos leader, List<BlockPos> trail, double back, int octant) {
        if (back <= 0.0D) return leader;
        double walked = 0.0D;
        BlockPos from = leader;
        for (BlockPos p : trail) {
            double leg = Math.sqrt(horizontalSq(from, p));
            if (walked + leg >= back) return p;
            walked += leg;
            from = p;
        }
        double rest = back - walked;
        int steps = (int) Math.round(rest);
        return from.offset(-FormationMath.forwardX(octant) * steps, 0, -FormationMath.forwardZ(octant) * steps);
    }

    /** The block member {@code index} should stand on (Y from the trail; the caller snaps it). */
    public static BlockPos target(PatrolRoute.Formation formation, int size, int index, BlockPos leader,
                                  List<BlockPos> trail, int octant) {
        Offset o = slot(formation, size, index);
        BlockPos spine = trailPoint(leader, trail, o.rank() * RANK_GAP, octant);
        if (o.lateral() == 0) return spine;
        int step = o.lateral() * FILE_SPACING;
        return spine.offset(FormationMath.rightX(octant) * step, 0, FormationMath.rightZ(octant) * step);
    }

    /** Octant (see {@link FormationMath}) of the direction from {@code a} to {@code b}. */
    public static int heading(BlockPos a, BlockPos b, int fallback) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        if (dx == 0 && dz == 0) return fallback;
        // Minecraft yaw: 0 = +Z (south), 90 = -X (west).
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return FormationMath.octant(yaw);
    }

    static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private PatrolFormation() {
    }
}
