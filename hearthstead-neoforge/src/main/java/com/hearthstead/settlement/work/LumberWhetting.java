package com.hearthstead.settlement.work;

/**
 * Sharpened Axes (tech tree, ring 1): every {@link #TREES_PER_WHET} trees the
 * Lumberer whets his axe at the Lumber Camp grindstone (LumbererWhetGoal).
 *
 * <p>Pure counter and timing rules, shared by the goal, the settler sheet and
 * the tests. The whetted edge is what the node's existing -10% felling time
 * stands for; that bonus itself stays unconditional (DevelopmentBonuses), so a
 * whet that cannot happen never costs the town anything.
 */
public final class LumberWhetting {
    /** Trees felled between two whets (node card: "every 8 trees"). */
    public static final int TREES_PER_WHET = 8;
    /** Per-settler persistent counter (saved with the entity). */
    public static final String TREES_TAG = "HearthsteadWhetTrees";
    /** Game time of the last finished whet (sheet/test evidence). */
    public static final String LAST_WHET_TAG = "HearthsteadWhetLast";
    /** Where the last whet happened: "grindstone" or "spot:<reason>". */
    public static final String LAST_WHET_WHERE_TAG = "HearthsteadWhetWhere";
    /** Whet length in ticks = the WHET_AXE clip (3.5 s). */
    public static final int WHET_TICKS = 70;
    /**
     * Blade-on-wheel contact ticks of the WHET_AXE clip (0.60, 1.30, 1.90,
     * 2.50 s): sparks and the grindstone rasp land on these. Deliberately
     * uneven, a man working, not a metronome.
     */
    private static final int[] STROKES = {12, 26, 38, 50};

    private LumberWhetting() {
    }

    /** Counter after one felled tree; counts only while the node is owned, caps at the due value. */
    public static int afterTree(int trees, boolean owned) {
        if (!owned) {
            return Math.max(0, trees);
        }
        return Math.min(TREES_PER_WHET, Math.max(0, trees) + 1);
    }

    public static boolean due(int trees) {
        return trees >= TREES_PER_WHET;
    }

    /** True on the tick a stroke meets the stone. */
    public static boolean strokeAt(int tick) {
        for (int stroke : STROKES) {
            if (stroke == tick) {
                return true;
            }
        }
        return false;
    }

    public static int strokeCount() {
        return STROKES.length;
    }
}
