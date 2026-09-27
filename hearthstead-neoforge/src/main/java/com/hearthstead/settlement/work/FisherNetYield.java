package com.hearthstead.settlement.work;

/**
 * Fisher's Nets (tech tree, ring 1): a set net holds one fish per in-game
 * hour, up to {@link #CAP}. Pure lazy accrual on game time, so a net fills
 * while its chunk is unloaded too, and a full net never banks spare time.
 */
public final class FisherNetYield {
    /** One in-game hour. */
    public static final int TICKS_PER_FISH = 1000;
    public static final int CAP = 4;

    private FisherNetYield() {
    }

    /** Accrued net state: fish held and the game time the next fish counts from. */
    public record State(int stored, long since) {
    }

    public static State accrue(int stored, long since, long now) {
        int held = Math.max(0, Math.min(CAP, stored));
        if (now <= since) {
            // Same tick, or a clock that went backwards (/time set on a
            // copied world): restart the hour from now, never mint fish.
            return new State(held, Math.min(since, now));
        }
        if (held >= CAP) {
            return new State(CAP, now);
        }
        long gained = (now - since) / TICKS_PER_FISH;
        if (held + gained >= CAP) {
            return new State(CAP, now);
        }
        return new State(held + (int) gained, since + gained * TICKS_PER_FISH);
    }

    /**
     * The Fisher hauls a full net on any round, and on his first round of a
     * work day whatever the net holds (node card: "hauls in at the start of work").
     */
    public static boolean shouldHaul(int stored, boolean firstRoundToday) {
        return stored >= CAP || (firstRoundToday && stored >= 1);
    }
}
