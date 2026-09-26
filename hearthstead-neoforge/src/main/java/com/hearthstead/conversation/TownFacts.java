package com.hearthstead.conversation;

/**
 * Pure facts a speaker could see about a town, so persuasion lines and odds
 * match reality (survival QA: a day-0 town must never threaten "our walls
 * and our guards"). Built from the live settlement by {@link TownFactsLive}.
 *
 * @param guards      martial settlers (guards, archers, spearmen...)
 * @param defenses    standing defensive buildings (barracks, watchtowers)
 * @param food        food in the settlement stores
 * @param raidsHeld   raids repelled so far
 * @param population  settlers
 */
public record TownFacts(int guards, int defenses, int food, int raidsHeld, int population) {
    public static final TownFacts NONE = new TownFacts(0, 0, 0, 0, 0);

    /** Enough muscle to threaten with: two or more fighters, or a defensive building. */
    public boolean defended() {
        return guards >= 2 || defenses >= 1;
    }

    public boolean fed() {
        return food >= 20;
    }

    /**
     * Odds bonus a persuasion attempt gets from the facts, by the skill it
     * uses. Threats ("intimidation") stand on real defenders and victories
     * and are weaker when there is nothing behind them; charm and trade lean
     * on reputation and plenty.
     */
    public int bonus(String skill) {
        return switch (skill == null ? "" : skill) {
            case "intimidation" -> Math.min(12, guards * 3) + Math.min(8, defenses * 4) + Math.min(6, raidsHeld * 2)
                - (defended() ? 0 : 10);
            case "charisma" -> Math.min(4, raidsHeld);
            case "trade" -> Math.min(5, food / 20);
            default -> 0;
        };
    }
}
