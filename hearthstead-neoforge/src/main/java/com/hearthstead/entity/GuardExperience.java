package com.hearthstead.entity;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Kill-earned combat experience shared by guards and archers.
 *
 * <p>This is deliberately separate from the existing Strength/Dexterity
 * ladders: those attributes still drive {@link GuardRank} and
 * {@link ArcherRank}, while this counter proves how many hostile kills a
 * defender has actually earned. A valid kill also trains the appropriate
 * existing attribute (see {@code GuardExperienceEvents}), so the two systems
 * reinforce rather than replace each other.
 *
 * <p>The top tier is a hard, saturating cap. That gives persistence a small,
 * bounded integer, makes malformed saves harmless, and prevents arithmetic
 * overflow even if another mod passes an absurd award value.
 */
public final class GuardExperience {

    /** Ordinary hostile mobs, such as zombies and skeletons. */
    public static final int HOSTILE_KILL_XP = 10;
    /** Hearthstead raiders are the settlement's intended military threat. */
    public static final int RAIDER_KILL_XP = 15;
    /** A raid captain is a memorable fight, not merely another body. */
    public static final int RAIDER_CAPTAIN_KILL_XP = 30;

    /** Highest persisted/displayed value; all writes saturate here. */
    public static final int MAX_EXPERIENCE = 480;

    public enum Tier {
        RECRUIT(1, 0),
        TRAINED(2, 40),
        VETERAN(3, 120),
        ELITE(4, 260),
        HERO(5, MAX_EXPERIENCE);

        private final int level;
        private final int threshold;

        Tier(int level, int threshold) {
            this.level = level;
            this.threshold = threshold;
        }

        public int level() {
            return level;
        }

        public int threshold() {
            return threshold;
        }

        public Component displayName() {
            return Component.translatable("hearthstead.combat_tier."
                + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Clamps a persisted or network-projected value to the owned domain. */
    public static int clamp(int experience) {
        return Math.max(0, Math.min(MAX_EXPERIENCE, experience));
    }

    /** Saturating addition, safe for every signed integer input. */
    public static int add(int current, int award) {
        int safeCurrent = clamp(current);
        if (award <= 0 || safeCurrent >= MAX_EXPERIENCE) {
            return safeCurrent;
        }
        long total = (long) safeCurrent + award;
        return (int) Math.min(MAX_EXPERIENCE, total);
    }

    /** Highest tier whose threshold has been reached. */
    public static Tier tierOf(int experience) {
        int safe = clamp(experience);
        Tier best = Tier.RECRUIT;
        for (Tier tier : Tier.values()) {
            if (safe >= tier.threshold) {
                best = tier;
            }
        }
        return best;
    }

    /** Absolute XP threshold of the next tier; the cap at {@link Tier#HERO}. */
    public static int nextThreshold(int experience) {
        Tier current = tierOf(experience);
        Tier[] tiers = Tier.values();
        if (current == Tier.HERO) {
            return MAX_EXPERIENCE;
        }
        return tiers[current.ordinal() + 1].threshold;
    }

    /** Progress from the current tier to the next, in the closed 0..1 range. */
    public static float progress(int experience) {
        int safe = clamp(experience);
        Tier current = tierOf(safe);
        if (current == Tier.HERO) {
            return 1.0F;
        }
        int start = current.threshold;
        int end = nextThreshold(safe);
        return end <= start ? 1.0F : (safe - start) / (float) (end - start);
    }

    private GuardExperience() {
    }
}
