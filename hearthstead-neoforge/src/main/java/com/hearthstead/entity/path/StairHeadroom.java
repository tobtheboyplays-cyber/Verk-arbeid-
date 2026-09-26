package com.hearthstead.entity.path;

import javax.annotation.Nullable;

/**
 * Can a settler climb onto this stair? Pure geometry, no world access.
 *
 * <p>Stepping up onto a stair lifts the body (1.95 tall, 0.6 wide) while it
 * still overlaps the column it steps from: vanilla {@code Entity.collide}
 * clips the step and the jump at any ceiling there, and
 * {@code WalkNodeEvaluator.tryJumpOn} plans exactly that. So the cell stood
 * in before the step needs three clear blocks above its floor (itself and
 * the two above), and the stair itself two clear blocks above its tread.
 * Two clear blocks everywhere is not enough; even a 1.8-tall player bumps.
 *
 * <p>Shared by the plaque's room scan (a non-blocking warning) and by the
 * blueprint generator's rule (tools/blueprints/kit.py {@code _stairs}).
 */
public final class StairHeadroom {
    /** Clear blocks needed above the floor of the cell a settler climbs from. */
    public static final int CLEAR_ABOVE_APPROACH = 3;
    /** Clear blocks needed above the stair's own tread. */
    public static final int CLEAR_ABOVE_TREAD = 2;

    /** One block cell. */
    public record Cell(int x, int y, int z) {
    }

    /** Whether a cell leaves a centred settler body free. */
    @FunctionalInterface
    public interface Clearance {
        boolean clear(int x, int y, int z);
    }

    private StairHeadroom() {
    }

    /**
     * The first cell in the way of climbing onto the bottom-half stair at
     * ({@code x,y,z}) that ascends toward ({@code dx,dz}) (its facing), or
     * null when the climb fits. The approach is the cell in front of the
     * stair's low side, at the stair's own height; the caller decides that
     * a settler can stand there at all.
     */
    @Nullable
    public static Cell blocker(int x, int y, int z, int dx, int dz, Clearance cells) {
        int ax = x - dx;
        int az = z - dz;
        for (int k = 0; k < CLEAR_ABOVE_APPROACH; k++) {
            if (!cells.clear(ax, y + k, az)) return new Cell(ax, y + k, az);
        }
        for (int k = 1; k <= CLEAR_ABOVE_TREAD; k++) {
            if (!cells.clear(x, y + k, z)) return new Cell(x, y + k, z);
        }
        return null;
    }

    /** The step the settler stands on before climbing: the floor or tread under the approach. */
    public static Cell stepBelowApproach(int x, int y, int z, int dx, int dz) {
        return new Cell(x - dx, y - 1, z - dz);
    }
}
