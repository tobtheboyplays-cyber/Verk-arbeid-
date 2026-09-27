package com.hearthstead.item.weapon;

/**
 * The four captain weapon types (the fifth, the longsword, is the battle-roles lane's
 * {@link com.hearthstead.item.role.RoleWeaponItem}). Numbers follow vanilla's tool maths: the
 * attack-damage modifier is {@code baseDamage + tier bonus} on top of the wielder's 1.0 base, the
 * speed modifier is added to the 4.0 base. Full table per tier: plan/WEAPONS.md.
 *
 * <pre>
 * type          dmg (iron)  speed  identity
 * short sword   5           2.0    fast, light; carried as a pair (one per hand)
 * double axe    10          0.8    two-handed; armour shred, bonus vs Brutes, breaks shields
 * halberd       8           0.9    two-handed; reach +1.5, x1.35 vs a charging foe
 * warhammer     9           0.8    two-handed; knockback +1, breaks shields, stun chance
 * </pre>
 */
public enum WeaponType {
    SHORT_SWORD("short_sword", 2.0F, -2.0F, false, true, false),
    DOUBLE_AXE("double_axe", 7.0F, -3.2F, true, true, true),
    HALBERD("halberd", 5.0F, -3.1F, true, true, false),
    WARHAMMER("warhammer", 6.0F, -3.2F, true, false, true);

    /** Player reach bonus of the halberd (entity interaction range, blocks). */
    public static final double HALBERD_REACH = 1.5D;
    /** Warhammer's attack knockback bonus (vanilla Knockback I adds 0.5 per level... this is +1.0). */
    public static final double WARHAMMER_KNOCKBACK = 1.0D;

    private final String id;
    private final float baseDamage;
    private final float speed;
    private final boolean twoHanded;
    private final boolean sweeps;
    private final boolean breaksShields;

    WeaponType(String id, float baseDamage, float speed, boolean twoHanded, boolean sweeps, boolean breaksShields) {
        this.id = id;
        this.baseDamage = baseDamage;
        this.speed = speed;
        this.twoHanded = twoHanded;
        this.sweeps = sweeps;
        this.breaksShields = breaksShields;
    }

    /** Registry-name suffix: {@code <tier>_<id>}, e.g. {@code iron_double_axe}. */
    public String id() {
        return id;
    }

    /** Attack-damage modifier before the tier bonus. */
    public float baseDamage() {
        return baseDamage;
    }

    /** Attack-speed modifier (added to the 4.0 base). */
    public float speed() {
        return speed;
    }

    public boolean twoHanded() {
        return twoHanded;
    }

    public boolean sweeps() {
        return sweeps;
    }

    public boolean breaksShields() {
        return breaksShields;
    }

    /** Total melee damage for a player (1.0 base + modifier) at the given tier bonus. */
    public float totalDamage(float tierBonus) {
        return 1.0F + baseDamage + tierBonus;
    }

    /** Attacks per second for a player. */
    public float attacksPerSecond() {
        return 4.0F + speed;
    }
}
