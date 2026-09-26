package com.hearthstead.event.worldevent;

import java.util.Locale;

/**
 * The small world events the owner approved (26 Sep): each one has a stable
 * id (saved, typed in {@code /hsevent start <id>}, used as the config key),
 * a base weight, a minimum gap in days before the same event may come back,
 * the day-time window in which it may begin and an eligible-time budget
 * after which it always ends and cleans up.
 *
 * <p>Pure data: nothing here reads the world, so the scheduling rules in
 * {@link WorldEventSchedule} stay unit-testable.
 */
public enum WorldEventType {
    //            id             weight gapDays startFrom startTo  budgetTicks hostile
    PEDDLER      ("peddler",       3.0,  3,      1_000,   4_000,  24_000, false),
    REFUGEES     ("refugees",      2.0,  4,      2_000,   8_000,  12_000, false),
    MINSTRELS    ("minstrels",     2.0,  3,     10_300,  11_200,   4_800, false),
    FIELD_FOX    ("field_fox",     2.0,  2,      1_500,   9_000,   3_600, false),
    WOLF_PACK    ("wolf_pack",     2.0,  3,     14_000,  20_000,   3_600, true),
    WILD_BOAR    ("wild_boar",     1.5,  3,      1_500,   9_000,   4_800, true),
    TAVERN_BRAWL ("tavern_brawl",  1.5,  3,     11_000,  12_300,   1_800, false),
    // Owner-approved 26 Sep: three passive brutes demand food at the Banner.
    BRUTE_TOLL   ("brute_toll",    1.5,  4,      2_000,   9_000,   7_000, true),
    // Owner-approved 26 Sep (second round, from EVENTS-IDEAS.md).
    STRAY_DOG    ("stray_dog",     2.0,  6,      1_500,   9_000,  12_000, false),
    CARAVAN      ("caravan",       2.0,  4,      2_000,   7_000,   7_000, false),
    RIVAL_ENVOY  ("rival_envoy",   1.5,  5,      2_000,   8_000,   8_000, false);

    /** Brute toll: about two in-game hours to answer before the default outcome. */
    public static final int BRUTE_TOLL_ANSWER_TICKS = 2_000;

    private final String id;
    private final double weight;
    private final int gapDays;
    private final int startFrom;
    private final int startTo;
    private final int budgetTicks;
    private final boolean hostile;

    WorldEventType(String id, double weight, int gapDays, int startFrom, int startTo,
                   int budgetTicks, boolean hostile) {
        this.id = id;
        this.weight = weight;
        this.gapDays = gapDays;
        this.startFrom = startFrom;
        this.startTo = startTo;
        this.budgetTicks = budgetTicks;
        this.hostile = hostile;
    }

    public String id() { return id; }
    public double weight() { return weight; }
    /** Whole in-game days before this same event may be planned again. */
    public int gapDays() { return gapDays; }
    /** Earliest time of day (0..23999) the event may begin. */
    public int startFrom() { return startFrom; }
    /** Latest time of day the event may begin; later the day's plan lapses. */
    public int startTo() { return startTo; }
    /** Eligible (someone near) ticks after which the event ends and cleans up. */
    public int budgetTicks() { return budgetTicks; }
    /** Hostile creatures: never planned on Peaceful difficulty. */
    public boolean hostile() { return hostile; }

    public static WorldEventType byId(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (WorldEventType type : values()) if (type.id.equals(key)) return type;
        return null;
    }
}
