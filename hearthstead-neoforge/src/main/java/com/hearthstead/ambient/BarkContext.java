package com.hearthstead.ambient;

/**
 * Why a settler says a short line. Each context owns {@code variants}
 * English lang keys {@code hearthstead.bark.<id>.<n>} (n = 1..variants);
 * JOB lines are per trade instead (see {@link BarkPicker#jobVariants}).
 *
 * <p>{@code eventLine} contexts answer a village moment (a won raid, a death,
 * a newcomer, a finished building): they may pass the per-area gap sooner
 * than everyday chatter, but still respect the per-settler cooldown.
 */
public enum BarkContext {
    /** First sight of a player today. Arg: the player's name. */
    GREET("greet", 12, false),
    HUNGRY("hungry", 8, false),
    TIRED("tired", 8, false),
    RAID_WON("raid_won", 10, true),
    RAIN("rain", 10, false),
    COLD("cold", 8, false),
    NEWCOMER("newcomer", 8, true),
    BUILDING_DONE("building_done", 8, true),
    /** Arg: the name of the settler who died. */
    MOURN("mourn", 6, true),
    WAKE("wake", 8, false),
    EVENING("evening", 10, false),
    /** Per trade; {@code variants} here is the generic fallback count. */
    JOB("job", 6, false);

    private final String id;
    private final int variants;
    private final boolean eventLine;

    BarkContext(String id, int variants, boolean eventLine) {
        this.id = id;
        this.variants = variants;
        this.eventLine = eventLine;
    }

    public String id() {
        return id;
    }

    public int variants() {
        return variants;
    }

    public boolean eventLine() {
        return eventLine;
    }
}
