package com.hearthstead.ambient;

import java.util.Locale;
import java.util.Set;

/**
 * Picks which bark line a settler says. Pure (no world, no registry) so it is
 * unit tested.
 *
 * <p>Seeded per settler: each settler walks its own permutation of a
 * context's variants (a seed-derived start and a step coprime with the
 * variant count), so two settlers rarely open with the same line and one
 * settler never repeats a line until it has said every other one.
 */
public final class BarkPicker {
    public static final String PREFIX = "hearthstead.bark.";
    /** Lines per trade in {@code hearthstead.bark.job.<trade>.<n>}. */
    public static final int JOB_VARIANTS = 4;
    /** Trades with their own flavour lines; any other falls back to job.generic. */
    public static final Set<String> JOB_TRADES = Set.of(
        "farmer", "lumberer", "courier", "baker", "cook", "butcher", "smelter",
        "smith", "sawyer", "carpenter", "mason", "fletcher", "weaver", "tanner",
        "miner", "innkeeper", "scholar", "miller", "brewer", "armourer", "herder",
        "fisher", "hunter", "trader", "builder", "healer");

    private BarkPicker() {
    }

    /**
     * Variant index 0..variants-1 for the {@code counter}-th line this
     * settler says in one context. Deterministic for (seed, salt, counter).
     */
    public static int variant(int variants, long seed, int salt, int counter) {
        if (variants <= 1) {
            return 0;
        }
        long mixed = mix(seed * 31L + salt);
        int start = (int) Math.floorMod(mixed, (long) variants);
        int step = coprimeStep(variants, (int) Math.floorMod(mixed >>> 17, (long) (variants - 1)) + 1);
        return (int) Math.floorMod(start + (long) step * counter, (long) variants);
    }

    /** Full lang key for a non-job context, 1-based suffix. */
    public static String key(BarkContext context, long seed, int counter) {
        if (context == BarkContext.JOB) {
            return jobKey(null, seed, counter);
        }
        return PREFIX + context.id() + "." + (1 + variant(context.variants(), seed, context.ordinal(), counter));
    }

    /**
     * Job flavour key for a trade id (lower-case profession name). Trades
     * without their own lines use the generic set.
     */
    public static String jobKey(String trade, long seed, int counter) {
        String id = trade == null ? "" : trade.toLowerCase(Locale.ROOT);
        if (JOB_TRADES.contains(id)) {
            return PREFIX + "job." + id + "." + (1 + variant(JOB_VARIANTS, seed, 97 + id.hashCode(), counter));
        }
        return PREFIX + "job.generic." + (1 + variant(BarkContext.JOB.variants(), seed, 97, counter));
    }

    /** Smallest step >= wanted (wrapping) that is coprime with n, so the walk visits every variant. */
    static int coprimeStep(int n, int wanted) {
        for (int i = 0; i < n; i++) {
            int step = 1 + Math.floorMod(wanted - 1 + i, n - 1);
            if (gcd(step, n) == 1) {
                return step;
            }
        }
        return 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    /** SplitMix64 finaliser: well spread from sequential seeds. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
