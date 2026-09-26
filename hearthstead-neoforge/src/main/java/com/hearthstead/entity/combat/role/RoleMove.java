package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.SettlerEntity;

/**
 * Spearman and Longswordsman moves as pure data (plan/BATTLE-ROLES.md §1-2).
 * Same contract as {@link com.hearthstead.entity.combat.GuardMove}: every
 * tick is on the 20 Hz server clock, measured from the tick the wind-up is
 * broadcast, and damage lands only on {@link #hitTick()} through a one-use
 * contact ticket.
 *
 * <p>{@link #reachScale()} is a multiple of vanilla's melee reach
 * ({@code w/2 + 0.828 + w/2} between two bodies); the spear's 1.5 is the
 * owner's "about 1.5x melee reach".
 */
public enum RoleMove {
    // ------------------------------------------------------------ spear
    /** Default poke: narrow cone, long reach. */
    SPEAR_THRUST(Weapon.SPEAR, 12, 5, 1.0D, 0.30D, 1.5D, 8, 0, 30.0F, 1, 1.0D,
        SettlerEntity.EV_MELEE),
    /** Two quick pokes; the second has its own ticket (Veteran+). */
    SPEAR_DOUBLE_THRUST(Weapon.SPEAR, 16, 5, 0.7D, 0.20D, 1.5D, 12, 0, 30.0F, 1, 1.0D,
        SettlerEntity.EV_GUARD_LIGHT_B),
    /** From brace: a charger entering reach is stopped and rocked. */
    SPEAR_BRACE_STRIKE(Weapon.SPEAR, 10, 3, 1.75D, 0.80D, 1.5D, 30, 24, 40.0F, 1, 1.0D,
        SettlerEntity.EV_GUARD_SHIELD_BASH),

    // --------------------------------------------------------- longsword
    /** Wide two-handed arc: up to three enemies, each at most once. */
    LONGSWORD_CLEAVE(Weapon.LONGSWORD, 16, 8, 1.1D, 0.30D, 1.25D, 10, 0, 70.0F, 3, 0.75D,
        SettlerEntity.EV_GUARD_FINISHER),
    /** Telegraphed overhead; punishes staggered and broken guards. */
    LONGSWORD_HEAVY(Weapon.LONGSWORD, 26, 14, 2.2D, 0.90D, 1.25D, 16, 12, 40.0F, 1, 1.0D,
        SettlerEntity.EV_GUARD_HEAVY),
    /** Half-sword / pommel strike: breaks a raised guard. */
    LONGSWORD_HALF_SWORD(Weapon.LONGSWORD, 14, 6, 0.8D, 0.50D, 1.0D, 40, 16, 40.0F, 1, 1.0D,
        SettlerEntity.EV_GUARD_SHIELD_BASH);

    public enum Weapon { SPEAR, LONGSWORD }

    /** Tick of the second contact of {@link #SPEAR_DOUBLE_THRUST}. */
    public static final int DOUBLE_THRUST_SECOND_HIT = 11;
    /** Guard-break window a half-sword strike opens on a shielded target. */
    public static final int GUARD_BREAK_TICKS = 60;
    /** A player's shield is disabled this long, like an axe hit. */
    public static final int PLAYER_SHIELD_DISABLE_TICKS = 100;

    private final Weapon weapon;
    private final int length;
    private final int hit;
    private final double damage;
    private final double knockback;
    private final double reachScale;
    private final int cooldown;
    private final int stagger;
    private final float arcHalfDegrees;
    private final int maxTargets;
    private final double secondaryShare;
    /** Existing entity event whose clip stands in until the role clip lands. */
    private final byte fallbackEvent;

    RoleMove(Weapon weapon, int length, int hit, double damage, double knockback,
             double reachScale, int cooldown, int stagger, float arcHalfDegrees,
             int maxTargets, double secondaryShare, byte fallbackEvent) {
        this.weapon = weapon;
        this.length = length;
        this.hit = hit;
        this.damage = damage;
        this.knockback = knockback;
        this.reachScale = reachScale;
        this.cooldown = cooldown;
        this.stagger = stagger;
        this.arcHalfDegrees = arcHalfDegrees;
        this.maxTargets = maxTargets;
        this.secondaryShare = secondaryShare;
        this.fallbackEvent = fallbackEvent;
    }

    public Weapon weapon() { return weapon; }
    public int lengthTicks() { return length; }
    public int hitTick() { return hit; }
    public int recoveryTicks() { return length - hit; }
    public double damageMultiplier() { return damage; }
    public double knockback() { return knockback; }
    public double reachScale() { return reachScale; }
    public int cooldownTicks() { return cooldown; }
    public int staggerTicks() { return stagger; }
    public boolean staggers() { return stagger > 0; }
    public float arcHalfDegrees() { return arcHalfDegrees; }
    public int maxTargets() { return maxTargets; }
    public double secondaryShare() { return secondaryShare; }
    public byte fallbackEvent() { return fallbackEvent; }
    /** The move's own authored clip (RoleMotionAnimations), SettlerEntity.EV_ROLE_MOVE_BASE + ordinal. */
    public byte clipEvent() { return (byte) (SettlerEntity.EV_ROLE_MOVE_BASE + ordinal()); }

    /** Start-to-start ticks when used as a single opener. */
    public int cadenceTicks() {
        return length + cooldown;
    }

    /** Contact ticks after wind-up start (two for the double thrust). */
    public int[] contactTicks() {
        return this == SPEAR_DOUBLE_THRUST
            ? new int[] {hit, DOUBLE_THRUST_SECOND_HIT} : new int[] {hit};
    }

    public boolean cleaves() {
        return maxTargets > 1;
    }

    public boolean breaksGuard() {
        return this == LONGSWORD_HALF_SWORD;
    }
}
