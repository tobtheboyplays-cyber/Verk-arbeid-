package com.hearthstead.entity;

import net.minecraft.network.chat.Component;

/**
 * What a guard has learned to do, earned by fighting.
 *
 * <h2>Rank is not a number you spend, it is a number you reach</h2>
 *
 * <p>Owner's ask, 2026-08-25: abilities that unlock as a guard levels, one per
 * twenty points, and TekTopia's leap that hits several enemies at once. So
 * rank reads straight off {@link Attribute#STRENGTH} — the attribute a guard's
 * own work trains — and there is nothing to allocate. A veteran guard is
 * evidence of nights survived, which is the only currency this mod has that
 * cannot be farmed quickly.
 *
 * <p>Every twenty points is deliberately a long way: with growth slowing as it
 * rises, {@link #SERGEANT} is weeks of patrols and fights, and the leap is
 * meant to be a thing you remember the first time you see one of your own
 * guards do it.
 *
 * <h2>The abilities</h2>
 *
 * <table>
 *   <caption>Ranks</caption>
 *   <tr><th>rank</th><th>at</th><th>what they can do</th></tr>
 *   <tr><td>RECRUIT</td><td>0</td><td>swings a sword</td></tr>
 *   <tr><td>SPEARMAN</td><td>20</td><td><b>Shield Bash</b> — hits knock back and stagger</td></tr>
 *   <tr><td>VETERAN</td><td>40</td><td><b>Cleave</b> — the swing also catches a second enemy</td></tr>
 *   <tr><td>SERGEANT</td><td>60</td><td><b>Leap Strike</b> — leaps a gap and lands on everyone at once</td></tr>
 *   <tr><td>CAPTAIN</td><td>80</td><td><b>Rally</b> — a kill lifts every guard nearby</td></tr>
 * </table>
 */
public enum GuardRank {
    RECRUIT("recruit", 0),
    SPEARMAN("spearman", 20),
    VETERAN("veteran", 40),
    SERGEANT("sergeant", 60),
    CAPTAIN("captain", 80);

    /** Secondary targets take this share, the way vanilla's sweep does. */
    public static final float CLEAVE_SHARE = 0.6F;
    /** How far a sergeant will leap. Short enough to read as a lunge. */
    public static final double LEAP_MIN = 3.5;
    public static final double LEAP_MAX = 9.0;
    /** Everything within this of the landing takes the blow. */
    public static final double LEAP_RADIUS = 3.0;
    /** Ticks between leaps, so it stays an event rather than a walk cycle. */
    public static final int LEAP_COOLDOWN = 200;

    private final String key;
    private final int threshold;

    GuardRank(String key, int threshold) {
        this.key = key;
        this.threshold = threshold;
    }

    public String key() {
        return key;
    }

    public int threshold() {
        return threshold;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.rank." + key);
    }

    /** The rank this strength has earned. */
    public static GuardRank of(int strength) {
        GuardRank best = RECRUIT;
        for (GuardRank rank : values()) {
            if (strength >= rank.threshold) {
                best = rank;
            }
        }
        return best;
    }

    public static GuardRank of(SettlerEntity settler) {
        return of(settler.attribute(Attribute.STRENGTH));
    }

    public boolean atLeast(GuardRank other) {
        return ordinal() >= other.ordinal();
    }

    /** Progress towards the next rank, 0..1; 1 at the top. */
    public static float progress(int strength) {
        GuardRank now = of(strength);
        if (now == CAPTAIN) {
            return 1.0F;
        }
        GuardRank next = values()[now.ordinal() + 1];
        int span = next.threshold - now.threshold;
        return span <= 0 ? 1.0F : (strength - now.threshold) / (float) span;
    }
}
