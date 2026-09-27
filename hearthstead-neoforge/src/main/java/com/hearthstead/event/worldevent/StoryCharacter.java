package com.hearthstead.event.worldevent;

import java.util.Locale;

/**
 * The named, recurring visitors of the story lane (owner, 26 Sep: "custom
 * characters with names; the world remembers who has come by"). Pure data:
 * a stable id (saved in {@link VisitorMemory}, typed in {@code /hsstory}),
 * the fixed name shown everywhere, the costume key and face seed that make
 * them look the same on every visit, their cooldown in days and how many
 * times they come at most (0 = no cap; the trigger itself limits them).
 *
 * <p>{@link #THANKS} is not a visitor: one of the settlement's own settlers
 * thanks the players, so it has no costume or seed.
 */
public enum StoryCharacter {
    //         id            name                  costume            seed       cool max threat
    HOLLINS   ("hollins",    "Marta Hollin",       "neighbour_wife",  205     ,   6,  3, false),
    PELL_ROOK ("pell_rook",  "Pell Rook",          "herald",          150     ,   2,  0, false),
    ODO       ("odo",        "Odo Farthing",       "merchant",        8000    ,   6,  4, false),
    THANKS    ("thanks",     "",                   "",                0,          3,  0, false),
    WENNA     ("wenna",      "Wenna Lark",         "bard",            2261    ,   2,  0, false),
    BRISKS    ("brisks",     "Aldo Brisk",         "newcomer_man",    3311    ,   5,  2, false),
    ANSELM    ("anselm",     "Brother Anselm",     "pilgrim",         2063    ,  10,  3, false),
    GERD      ("gerd",       "Mother Gerd Wyllow", "healer",          484     ,   4,  0, false),
    BRANNOC   ("brannoc",    "Sergeant Brannoc",   "veteran",         1111    ,   8,  3, false),
    HILDE     ("hilde",      "Old Hilde",          "storyteller",     323     ,   9,  3, false),
    SIGRUN    ("sigrun",     "Sigrun Crowvoice",   "crow_herald",     204     ,   3,  0, true),
    HAMON     ("hamon",      "Reeve Hamon Stark",  "bailiff",         119     ,   3,  0, true);

    /** The bandit warlord behind Sigrun's ladder (a raid captain, not a visitor). */
    public static final String VARG = "Varg Ironjaw";
    /** The brute toll's chief (T3). */
    public static final String GORM = "Gorm Stonebelly";

    private final String id;
    private final String displayName;
    private final String costume;
    private final int seed;
    private final int cooldownDays;
    private final int maxVisits;
    private final boolean threat;

    StoryCharacter(String id, String displayName, String costume, int seed, int cooldownDays, int maxVisits,
                   boolean threat) {
        this.id = id;
        this.displayName = displayName;
        this.costume = costume;
        this.seed = seed;
        this.cooldownDays = cooldownDays;
        this.maxVisits = maxVisits;
        this.threat = threat;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String costume() { return costume; }
    /** Appearance seed: the same face and hair on every visit. */
    public int seed() { return seed; }
    public int cooldownDays() { return cooldownDays; }
    public int maxVisits() { return maxVisits; }
    public boolean threat() { return threat; }

    public static StoryCharacter byId(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (StoryCharacter c : values()) if (c.id.equals(key)) return c;
        return null;
    }
}
