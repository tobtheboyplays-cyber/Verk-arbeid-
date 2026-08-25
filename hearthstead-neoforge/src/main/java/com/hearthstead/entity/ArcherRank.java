package com.hearthstead.entity;

import net.minecraft.network.chat.Component;

/**
 * What an archer has learned to do, earned by loosing arrows.
 *
 * <h2>Rank is not a number you spend, it is a number you reach</h2>
 *
 * <p>Owner's ask, 2026-08-25: "vil også ha en archer, med kule abilities
 * power shot og triple shot osv over tid" — an archer whose abilities arrive
 * over time. So this mirrors {@link GuardRank} exactly: rank reads straight
 * off {@link Attribute#DEXTERITY} — the attribute an archer's own work
 * trains — and there is nothing to allocate. A sharpshooter on the tower is
 * evidence of watches stood and arrows loosed, the only currency this mod
 * has that cannot be farmed quickly.
 *
 * <p>The guard's ladder climbs STRENGTH because a blow is force; this one
 * climbs DEXTERITY because a shot is hands — the same attribute the fletcher
 * who made the arrow trains, which is a nice symmetry and not an accident.
 *
 * <h2>The abilities</h2>
 *
 * <table>
 *   <caption>Ranks</caption>
 *   <tr><th>rank</th><th>at</th><th>what they can do</th></tr>
 *   <tr><td>RECRUIT</td><td>0</td><td>looses an ordinary arrow, generously spread</td></tr>
 *   <tr><td>MARKSMAN</td><td>20</td><td><b>Steady Hand</b> — +25% arrow damage, much tighter spread</td></tr>
 *   <tr><td>SHARPSHOOTER</td><td>35</td><td><b>Power Shot</b> — every 4th shot is drawn long
 *       (a visible ~{@value #POWER_SHOT_DRAW_TICKS}-tick pause), lands at
 *       {@value #POWER_SHOT_DAMAGE_MULT}× damage with strong knockback and
 *       pierces one body, on a deeper twang</td></tr>
 *   <tr><td>MASTER</td><td>55</td><td><b>Triple Shot</b> — every 5th shot fans three arrows
 *       at ±{@value #TRIPLE_SHOT_YAW_DEGREES}° yaw</td></tr>
 * </table>
 *
 * <p><b>Future design only — Arrow Storm.</b> A capstone above MASTER (the
 * ladder's CAPTAIN moment: a brief rain of arrows over an area, once per
 * raid) is <i>documented here as design and deliberately not implemented</i>:
 * it needs its own telegraphed animation, its own sound, and a raid-scale
 * balancing pass before it can be more than a damage number, and a capstone
 * that is only a number is exactly the half-job the job standard forbids.
 *
 * <h2>Why the thresholds sit lower than the guard's</h2>
 *
 * <p>The guard's ladder runs 20/40/60/80 and doubles as an <i>armor</i> ramp;
 * this one runs 20/35/55 and carries no gear at all — an archer's rank is
 * only ever visible in how they shoot. Abilities that nothing renders on the
 * body can afford to arrive a little sooner, and the asymptotic growth curve
 * ({@link SettlerAttributes}) still makes MASTER a long way: on the same
 * arithmetic as the guard's doc, DEX 55 from a fresh 5 is roughly 2,200
 * train units — weeks of active watches.
 *
 * <h2>No equipment ladder</h2>
 *
 * <p>{@link GuardRank#applyEquipment} exists because guard rank is readable
 * as armor across a square. The archer's kit is the bow the profession
 * already puts in their hand ({@link Profession#ARCHER}); rank reads in the
 * cadence — the long-drawn pause before a Power Shot, the fan of a Triple
 * Shot — not in what they wear. If archer armor is ever wanted, it belongs
 * here, gated the same way the guard's is: earned, never bought.
 */
public enum ArcherRank {
    // "recruit" deliberately shares GuardRank.RECRUIT's lang key: it is the
    // same word meaning the same thing, and two keys for one word is drift.
    RECRUIT("recruit", 0),
    MARKSMAN("marksman", 20),
    SHARPSHOOTER("sharpshooter", 35),
    MASTER("master_archer", 55);

    // ----------------------------------------------------------- shooting ---

    /** A recruit's spread, matching a normal-difficulty vanilla skeleton
     *  (14 − difficulty·4). Generous on purpose: a fresh archer misses. */
    public static final float BASE_INACCURACY = 6.0F;
    /** A marksman's spread. The tightening is the visible half of the rank;
     *  the +25% below is the numeric half. */
    public static final float MARKSMAN_INACCURACY = 2.0F;
    /** Steady Hand: a marksman's arrows carry a quarter more base damage. */
    public static final float MARKSMAN_DAMAGE_MULT = 1.25F;

    /**
     * Power Shot cadence: every 4th volley, counted on the archer's own
     * volley counter. See {@code ArcherAttackGoal}'s cadence comment for how
     * this interleaves with {@link #TRIPLE_SHOT_EVERY} — the two never stack
     * on the same shot; where they would collide, the Power Shot wins.
     */
    public static final int POWER_SHOT_EVERY = 4;
    /** Extra draw ticks on a Power Shot — the visible held-long pause that
     *  telegraphs it, on top of the ordinary draw. */
    public static final int POWER_SHOT_DRAW_TICKS = 25;
    public static final float POWER_SHOT_DAMAGE_MULT = 2.5F;
    /** Punch level on the transient weapon a Power Shot is fired from — the
     *  vanilla enchantment pipeline is what turns it into knockback. */
    public static final int POWER_SHOT_PUNCH = 2;
    /** Piercing level on the same transient weapon: the shot carries through
     *  one body. */
    public static final int POWER_SHOT_PIERCE = 1;
    /** A Power Shot's spread: drawn that long, it goes where it is aimed. */
    public static final float POWER_SHOT_INACCURACY = 1.0F;

    /** Triple Shot cadence: every 5th volley (see {@link #POWER_SHOT_EVERY}
     *  for the collision rule). */
    public static final int TRIPLE_SHOT_EVERY = 5;
    /** The fan: one arrow at the mark, one at each of ±this yaw. */
    public static final float TRIPLE_SHOT_YAW_DEGREES = 7.0F;

    // ------------------------------------------------------------ training ---
    //
    // Rank reads Attribute.DEXTERITY, so the archer's OWN trade must climb
    // the ladder — the same lesson GuardRank's training constants encode
    // (before those existed, a career guard could never leave RECRUIT).

    /**
     * DEXTERITY per volley loosed ({@code ArcherAttackGoal}), paid at the
     * moment of release — never on a timer.
     *
     * <p>The arithmetic, from {@link SettlerAttributes} (RATE 0.05/unit at
     * value 0, growth ∝ (1 − v/100)²): climbing a fresh archer's ~5 DEX to
     * {@link #MARKSMAN}'s 20 integrates to ~395 train units — the same
     * integral GuardRank.TRAIN_DRILL documents for the same span. A volley
     * pays 2.5 here plus, for roughly every second arrow a recruit actually
     * lands at watch ranges, {@link #TRAIN_HIT} — about 5.0 units per volley
     * in practice. That lands the first stripe after ~79 volleys; an active
     * watch (a raid wave of half a dozen plus the night's strays, one volley
     * per ~35-tick aim-and-loose cycle) runs ~20–25 volleys, so MARKSMAN
     * arrives after ≈ <b>3–4 active-watch days</b> — the intended pace, and
     * the same first-stripe timing as the guard's drill.
     *
     * <p>On the same curve {@link #SHARPSHOOTER} (DEX 35, ~790 units total)
     * is ≈ +4–5 more active days and {@link #MASTER} (DEX 55, ~2,200 units)
     * is weeks — past the first stripe, only a settlement that is genuinely
     * fought over makes a Master.
     */
    public static final float TRAIN_SHOT = 2.5F;

    /**
     * DEXTERITY per arrow that actually <i>struck</i> a living target
     * (trained from the arrow's own post-hurt hook, so it counts the hit
     * and never the loose). Twice {@link #TRAIN_SHOT}, not the guard's 5× —
     * a guard's drill is peacetime walking and combat is rare, but every
     * archer volley is already combat and hits are the common case, so a
     * bigger multiple would silently halve the 3–4 day arithmetic above.
     */
    public static final float TRAIN_HIT = 5.0F;

    private final String key;
    private final int threshold;

    ArcherRank(String key, int threshold) {
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

    /** The rank this dexterity has earned. */
    public static ArcherRank of(int dexterity) {
        ArcherRank best = RECRUIT;
        for (ArcherRank rank : values()) {
            if (dexterity >= rank.threshold) {
                best = rank;
            }
        }
        return best;
    }

    public static ArcherRank of(SettlerEntity settler) {
        return of(settler.attribute(Attribute.DEXTERITY));
    }

    public boolean atLeast(ArcherRank other) {
        return ordinal() >= other.ordinal();
    }

    /** Progress towards the next rank, 0..1; 1 at the top. */
    public static float progress(int dexterity) {
        ArcherRank now = of(dexterity);
        if (now == MASTER) {
            return 1.0F;
        }
        ArcherRank next = values()[now.ordinal() + 1];
        int span = next.threshold - now.threshold;
        return span <= 0 ? 1.0F : (dexterity - now.threshold) / (float) span;
    }
}
