package com.hearthstead.settlement.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns two clicked points into the columns of a defensive line -- pure
 * integer math, covered by plain JUnit.
 *
 * <p>A line snaps to the nearest of the eight compass directions (the two
 * axes or a clean 45-degree diagonal), because a ragged Bresenham wall
 * leaves corner gaps a raider walks straight through and reads as a mistake
 * rather than a fortification. Its length is capped so one order is one
 * evening's work, not a hundred-block wall nobody asked the price of.
 */
public final class DefenseLinePlanner {

    /** Longest line one order may describe, in columns. */
    public static final int MAX_LENGTH = 32;

    /** One column of the line. {@code gate} marks the gate's own column. */
    public record Column(int x, int z, boolean gate, boolean post) {
    }

    /** A planned line: its columns in order from A, and the snapped direction. */
    public record Line(List<Column> columns, int dx, int dz) {
        public boolean diagonal() {
            return dx != 0 && dz != 0;
        }

        public int gateIndex() {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).gate()) {
                    return i;
                }
            }
            return -1;
        }
    }

    private DefenseLinePlanner() {
    }

    /**
     * Plans the line from (ax, az) toward (bx, bz).
     *
     * @param gate       whether the line carries a gate
     * @param gateOffset how far the gate sits from the centre (scrolled by
     *                   the player); clamped so it never lands on an end post
     * @param postEvery  a post (taller/stronger column) every N columns,
     *                   plus both ends; 0 for none
     */
    public static Line plan(int ax, int az, int bx, int bz, boolean gate,
                            int gateOffset, int postEvery) {
        int rawDx = bx - ax;
        int rawDz = bz - az;
        int dx;
        int dz;
        int length;
        int adx = Math.abs(rawDx);
        int adz = Math.abs(rawDz);
        if (adx == 0 && adz == 0) {
            dx = 1;
            dz = 0;
            length = 1;
        } else if (adx >= 2 * adz) {
            dx = Integer.signum(rawDx);
            dz = 0;
            length = adx + 1;
        } else if (adz >= 2 * adx) {
            dx = 0;
            dz = Integer.signum(rawDz);
            length = adz + 1;
        } else {
            dx = Integer.signum(rawDx);
            dz = Integer.signum(rawDz);
            length = Math.max(adx, adz) + 1;
        }
        length = Math.min(length, MAX_LENGTH);
        boolean diagonal = dx != 0 && dz != 0;
        // A gate needs a straight run: a fence gate on a diagonal opens into
        // the wall beside it. Diagonal lines are wall only.
        boolean hasGate = gate && !diagonal && length >= 3;
        int gateIndex = -1;
        if (hasGate) {
            gateIndex = clamp(length / 2 + gateOffset, 1, length - 2);
        }
        List<Column> columns = new ArrayList<>(length * (diagonal ? 2 : 1));
        for (int i = 0; i < length; i++) {
            int x = ax + dx * i;
            int z = az + dz * i;
            boolean isGate = i == gateIndex;
            boolean post = !isGate && (i == 0 || i == length - 1
                || (postEvery > 0 && i % postEvery == 0)
                || (hasGate && Math.abs(i - gateIndex) == 1));
            columns.add(new Column(x, z, isGate, post));
            if (diagonal && i < length - 1) {
                // Close the corner gap between two diagonal steps: a filler
                // column on the x-step keeps the line watertight.
                columns.add(new Column(x + dx, z, false, false));
            }
        }
        return new Line(List.copyOf(columns), dx, dz);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
