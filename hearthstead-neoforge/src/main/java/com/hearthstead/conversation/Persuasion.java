package com.hearthstead.conversation;

/**
 * Pure persuasion formula (JUnit-covered).
 *
 * <pre>chance = base + relation / 4 + reputation / 5 + skill, clamped 5..95 %</pre>
 *
 * <p>Nothing is ever certain: a Loyal friend can still say no (95 %), a
 * sworn enemy can still be talked round (5 %). The roll is made by the
 * server only ({@link #succeeds}); the client just shows the number.
 */
public final class Persuasion {
    public static final int MIN_CHANCE = 5;
    public static final int MAX_CHANCE = 95;
    /** Highest bonus any single player skill can add. */
    public static final int MAX_SKILL = 20;

    private Persuasion() {
    }

    public static int chance(int base, int relation, int reputation, int skill) {
        long raw = (long) base
            + Relations.clamp(relation) / 4
            + Relations.clamp(reputation) / 5
            + Math.max(-MAX_SKILL, Math.min(MAX_SKILL, skill));
        return (int) Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, raw));
    }

    /** {@code roll} is a uniform 0..99 draw; success when it lands under the chance. */
    public static boolean succeeds(int chance, int roll) {
        int c = Math.max(MIN_CHANCE, Math.min(MAX_CHANCE, chance));
        return roll >= 0 && roll < c;
    }

    /**
     * Skill bonus from what the player has shown. "charisma" and "trade" grow
     * with experience level (half a level each, at most 15) plus the
     * settlement's renown; "intimidation" grows with the armour worn.
     */
    public static int skill(String skill, int experienceLevel, int armor, int renown) {
        int xp = Math.max(0, Math.min(15, experienceLevel / 2));
        int fame = Math.max(0, Math.min(5, renown));
        int value = switch (skill == null ? "" : skill) {
            case "intimidation" -> Math.max(0, Math.min(15, armor / 2)) + fame;
            case "charisma", "trade" -> xp + fame;
            default -> 0;
        };
        return Math.min(MAX_SKILL, value);
    }
}
