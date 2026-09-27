package com.hearthstead.revive;

/**
 * Downed/revive counts for one settlement's current (or most recent) raid.
 *
 * <p>This is the raid-report hook: {@link ReviveService#tally} returns a
 * copy that the raid report / aftermath writer can read when it builds its
 * entry. Counts reset when that settlement's next raid starts. Transient:
 * a server restart forgets them (the report is written at raid end, long
 * before anyone restarts).
 *
 * @param downs     players who went down instead of dying
 * @param revives   downed players a comrade got back on their feet
 * @param bleedOuts downed players who died anyway (bled out, finished off,
 *                  or logged out while down)
 */
public record ReviveTally(int downs, int revives, int bleedOuts) {
    public static final ReviveTally EMPTY = new ReviveTally(0, 0, 0);

    ReviveTally withDown() {
        return new ReviveTally(downs + 1, revives, bleedOuts);
    }

    ReviveTally withRevive() {
        return new ReviveTally(downs, revives + 1, bleedOuts);
    }

    ReviveTally withBleedOut() {
        return new ReviveTally(downs, revives, bleedOuts + 1);
    }
}
