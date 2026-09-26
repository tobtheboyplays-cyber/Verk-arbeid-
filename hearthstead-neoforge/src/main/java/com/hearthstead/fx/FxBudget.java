package com.hearthstead.fx;

/**
 * The client particle budget, as pure logic (no Minecraft types, so it is
 * unit tested directly). One instance lives on the client ({@code FxClient}).
 *
 * <h2>Live count without leaks</h2>
 * The particle engine drops particles silently on a level change or when its
 * own queue overflows, so counting create/remove would drift. Instead every
 * live Bannerhold particle reports {@link #markAlive()} from its own tick;
 * at the end of each client tick {@link #endTick()} sets
 * {@code live = aliveMarks + spawnedSinceLastEndTick} (particles created
 * this tick have not ticked yet) and resets both. Between ticks the estimate
 * is {@code live + spawned}: conservative, never below the truth.
 *
 * <h2>Scaling</h2>
 * {@link #scale} folds the {@code [particles] intensity} config (0..2) and
 * the vanilla Particles option (All / Decreased / Minimal) into one factor;
 * {@link #count} turns an authored count into a stochastic whole number so
 * a 0.5 factor on "3" gives 1 or 2, not always 1.
 */
public final class FxBudget {
    /** Distance culling for any effect without its own shorter range. */
    public static final double DEFAULT_CULL_RANGE = 48.0D;

    public static final int STATUS_ALL = 0;
    public static final int STATUS_DECREASED = 1;
    public static final int STATUS_MINIMAL = 2;

    private static final int[] CAP_BY_INTENSITY = {220, 450, 750};
    private static final float[] SCALE_BY_INTENSITY = {0.45F, 1.0F, 1.6F};

    private int live;
    private int spawned;
    private int aliveMarks;
    private long refused;

    /** Hard cap on live Bannerhold particles for an intensity (clamped 0..2). */
    public static int capFor(int intensity) {
        return CAP_BY_INTENSITY[clampIntensity(intensity)];
    }

    public static int clampIntensity(int intensity) {
        return Math.max(0, Math.min(2, intensity));
    }

    /**
     * Count factor for one spawn. Minimal drops ambience entirely and keeps
     * a trace of feedback ({@code essential}: order dots, summon arrival, a
     * level-up) so the game still answers the player.
     */
    public static float scale(int intensity, int particleStatus, boolean essential) {
        float base = SCALE_BY_INTENSITY[clampIntensity(intensity)];
        return switch (particleStatus) {
            case STATUS_ALL -> base;
            case STATUS_DECREASED -> base * 0.5F;
            default -> essential ? base * 0.3F : 0.0F;
        };
    }

    /**
     * Stochastic rounding of {@code base * scale}; {@code roll} is a uniform
     * random in [0, 1). Never negative; 0 when the scale is 0.
     */
    public static int count(int base, float scale, double roll) {
        if (base <= 0 || scale <= 0.0F) {
            return 0;
        }
        double exact = base * (double) scale;
        int whole = (int) Math.floor(exact);
        return whole + (roll < exact - whole ? 1 : 0);
    }

    /** Distance culling: inside a sphere of {@code range} around the camera. */
    public static boolean inRange(double dx, double dy, double dz, double range) {
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    /** Reserves one particle under {@code cap}; false = over budget, do not spawn. */
    public boolean tryReserve(int cap) {
        if (live + spawned >= cap) {
            refused++;
            return false;
        }
        spawned++;
        return true;
    }

    /** Called by each live Bannerhold particle once per tick it survives. */
    public void markAlive() {
        aliveMarks++;
    }

    /** End of a client tick: re-derive the live count (see class doc). */
    public void endTick() {
        live = aliveMarks + spawned;
        aliveMarks = 0;
        spawned = 0;
    }

    /** Current conservative estimate of live particles. */
    public int estimate() {
        return live + spawned;
    }

    /** Spawns refused over budget since start (debug/perf readout). */
    public long refused() {
        return refused;
    }

    public void reset() {
        live = 0;
        spawned = 0;
        aliveMarks = 0;
    }
}
