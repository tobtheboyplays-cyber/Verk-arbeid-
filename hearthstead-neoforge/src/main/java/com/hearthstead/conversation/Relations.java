package com.hearthstead.conversation;

/**
 * Pure relation arithmetic (no Minecraft types, JUnit-covered).
 *
 * <p>A relation is what one named person (the returning peddler, a Saga
 * raid captain) thinks of one settlement: -100..+100. Reputation is what a
 * whole kind of visitor ("peddler", "brute", "raider") has heard of it, on
 * the same scale. Both only ever move through {@link #apply}.
 */
public final class Relations {
    public static final int MIN = -100;
    public static final int MAX = 100;

    public enum Tier {
        HOSTILE("hostile"), WARY("wary"), NEUTRAL("neutral"), FRIENDLY("friendly"), LOYAL("loyal");

        private final String id;

        Tier(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String langKey() {
            return "conversation.hearthstead.relation." + id;
        }
    }

    private Relations() {
    }

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    /** Adds {@code delta} without overflow and clamps into -100..+100. */
    public static int apply(int current, int delta) {
        long sum = (long) clamp(current) + delta;
        return clamp((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum)));
    }

    public static Tier tier(int relation) {
        int r = clamp(relation);
        if (r <= -50) return Tier.HOSTILE;
        if (r <= -10) return Tier.WARY;
        if (r < 25) return Tier.NEUTRAL;
        if (r < 70) return Tier.FRIENDLY;
        return Tier.LOYAL;
    }

    /** 0..1 position of a relation on the -100..+100 bar. */
    public static float barFraction(int relation) {
        return (clamp(relation) - MIN) / (float) (MAX - MIN);
    }

    /**
     * How much more (or less) a partner asks for what the player takes, in
     * percent: 115 at a neutral 0, 90 when Loyal (+100), 140 when Hostile (-100).
     */
    public static int askPercent(int relation) {
        return 115 - Math.round(clamp(relation) * 0.25F);
    }
}
