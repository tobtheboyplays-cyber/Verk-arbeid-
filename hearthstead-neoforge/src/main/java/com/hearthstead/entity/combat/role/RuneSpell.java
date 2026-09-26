package com.hearthstead.entity.combat.role;

/**
 * The Rune Mage's three spells as pure data (plan/BATTLE-ROLES.md §4).
 * "Strong but never a replacement for guards": every number here is small on
 * purpose and every spell spends a scarce charge. Wire ids are save/sync
 * contracts; append, never renumber.
 */
public enum RuneSpell {
    /** Bolt to a point; small AoE, moderate damage, brief burning. Enemies only. */
    FIREBOLT(0, "firebolt", 1, 100, 20, 16.0D),
    /** Rune shield on up to four nearby allies; absorbs a little for a few seconds. */
    WARD(1, "ward", 2, 400, 16, 6.0D),
    /** Ground rune that slows enemies inside it. No damage. */
    FROST_RUNE(2, "frost_rune", 1, 240, 24, 16.0D);

    // ------------------------------------------------------- firebolt ---
    public static final double FIREBOLT_RADIUS = 2.0D;
    public static final float FIREBOLT_CENTRE_DAMAGE = 6.0F;
    /** Damage at the very edge of the blast, as a share of the centre. */
    public static final float FIREBOLT_EDGE_SHARE = 0.6F;
    public static final int FIREBOLT_BURN_TICKS = 40;

    // ----------------------------------------------------------- ward ---
    public static final int WARD_MAX_ALLIES = 4;
    public static final float WARD_ABSORB = 6.0F;
    public static final int WARD_DURATION_TICKS = 100;
    /** high_runes (Castle) lengthens the ward to six seconds. */
    public static final int WARD_DURATION_TICKS_HIGH_RUNES = 120;

    // ----------------------------------------------------------- frost ---
    public static final double FROST_RADIUS = 3.0D;
    public static final int FROST_LIFETIME_TICKS = 80;
    /** Slowness II (amplifier 1), refreshed every second while inside. */
    public static final int FROST_SLOW_AMPLIFIER = 1;
    public static final int FROST_SLOW_TICKS = 30;

    private final int wireId;
    private final String key;
    private final int cost;
    private final int cooldown;
    private final int castTicks;
    private final double range;

    RuneSpell(int wireId, String key, int cost, int cooldown, int castTicks, double range) {
        this.wireId = wireId;
        this.key = key;
        this.cost = cost;
        this.cooldown = cooldown;
        this.castTicks = castTicks;
        this.range = range;
    }

    public int wireId() { return wireId; }
    public String key() { return key; }
    /** Rune charges this spell spends. */
    public int cost() { return cost; }
    /** Ticks from release until this spell may be cast again. */
    public int cooldownTicks() { return cooldown; }
    /** Visible channel before release; damage during it interrupts. */
    public int castTicks() { return castTicks; }
    /** Target range for aimed spells; ward radius for WARD. */
    public double range() { return range; }

    public static RuneSpell byWireId(int id) {
        for (RuneSpell s : values()) {
            if (s.wireId == id) {
                return s;
            }
        }
        return null;
    }

    /**
     * How much of a ward is still standing when it expires, so exactly that
     * much absorption is removed and never anyone else's (a golden apple, a
     * second source). Absorption drains as one pool; the ward is counted as
     * drained FIRST, which is the conservative reading for the player.
     *
     * @param granted      absorption the ward actually added
     * @param totalAtGrant absorption right after the ward was added
     * @param current      absorption now
     */
    public static float wardLeftAtExpiry(float granted, float totalAtGrant, float current) {
        float consumed = Math.max(0.0F, totalAtGrant - current);
        return Math.max(0.0F, Math.min(current, granted - consumed));
    }

    /**
     * Firebolt damage at {@code distance} from the blast centre: the full
     * centre value falling linearly to {@link #FIREBOLT_EDGE_SHARE} at the
     * radius, and nothing beyond it.
     */
    public static float fireboltDamage(double distance) {
        if (!(distance >= 0.0D) || distance > FIREBOLT_RADIUS) {
            return 0.0F;
        }
        double t = distance / FIREBOLT_RADIUS;
        return (float) (FIREBOLT_CENTRE_DAMAGE * (1.0D - t * (1.0D - FIREBOLT_EDGE_SHARE)));
    }
}
