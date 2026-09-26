package com.hearthstead.entity.combat;

import com.hearthstead.HearthsteadServerConfig;

/**
 * One Guard melee move as pure data. Every tick value is on the 20 Hz server
 * clock and is measured from the tick the move's wind-up event is broadcast.
 *
 * <p>The server is authoritative: damage is applied only on {@link #hitTick()}
 * through the entity's one-use contact ticket, never at move start. The
 * matching client clip is authored so its impact pose lands on exactly the
 * same tick (see {@code docs/ANIMATION_CATALOGUE.md}, guard moveset).
 *
 * <table>
 *   <caption>Guard moveset (defaults)</caption>
 *   <tr><th>move</th><th>clip</th><th>len</th><th>hit</th><th>chain</th>
 *       <th>dmg</th><th>kb</th><th>reach</th><th>cd</th><th>stagger</th><th>arc</th></tr>
 *   <tr><td>LIGHT_A</td><td>MELEE</td><td>10</td><td>4</td><td>6</td>
 *       <td>1.0</td><td>0.0</td><td>1.0</td><td>10</td><td>0</td><td>75</td></tr>
 *   <tr><td>LIGHT_B</td><td>GUARD_LIGHT_SLASH_B</td><td>10</td><td>4</td><td>8</td>
 *       <td>1.0</td><td>0.0</td><td>1.0</td><td>10</td><td>0</td><td>75</td></tr>
 *   <tr><td>COMBO_FINISHER</td><td>GUARD_FINISHER_DRIVE</td><td>14</td><td>4</td><td>-</td>
 *       <td>1.5</td><td>0.60</td><td>1.0</td><td>20</td><td>8</td><td>60</td></tr>
 *   <tr><td>HEAVY</td><td>GUARD_HEAVY_OVERHEAD</td><td>22</td><td>11</td><td>-</td>
 *       <td>1.8</td><td>0.90</td><td>1.0</td><td>14</td><td>10</td><td>50</td></tr>
 *   <tr><td>SHIELD_BASH</td><td>GUARD_SHIELD_BASH</td><td>10</td><td>3</td><td>-</td>
 *       <td>0.35</td><td>0.50</td><td>0.75</td><td>6</td><td>8</td><td>60</td></tr>
 * </table>
 *
 * <p>"kb" is extra knockback on top of vanilla's hurt knockback (0.4), so a
 * light slash keeps the ordinary small push.
 *
 * <p>THRUST is deliberately absent: guards carry swords only (no spear or
 * polearm item exists), so there is no weapon that could justify extra reach.
 */
public enum GuardMove {
    /** Quick forehand slash; plays the existing MELEE clip on meleeState. */
    LIGHT_A(10, 4, 6, 1.0D, 0.0D, 1.0D, 10, 0, 75.0F),
    /** Mirrored backhand return swing; the second combo link. */
    LIGHT_B(10, 4, 8, 1.0D, 0.0D, 1.0D, 10, 0, 75.0F),
    /** Third combo link; reuses GUARD_FINISHER_DRIVE (contact tick 4). */
    COMBO_FINISHER(14, 4, -1, 1.5D, 0.60D, 1.0D, 20, 8, 60.0F),
    /** Telegraphed two-handed overhead chop. */
    HEAVY(22, 11, -1, 1.8D, 0.90D, 1.0D, 14, 10, 50.0F),
    /** Offhand shield punch: short range, interrupts an enemy wind-up. */
    SHIELD_BASH(10, 3, -1, 0.35D, 0.50D, 0.75D, 6, 8, 60.0F);

    /** Sentinel (-1, written literally above) for moves that never chain. */
    public static final int NO_CHAIN_TICK = -1;
    /** Ticks after a link's chain tick in which the next link may still begin. */
    public static final int DEFAULT_COMBO_WINDOW_TICKS = 6;
    /** Separate cooldown on the bash so it stays a reaction, not a spam move. */
    public static final int DEFAULT_SHIELD_BASH_COOLDOWN_TICKS = 80;

    private final int authoredLength;
    private final int authoredHit;
    private final int chainTick;
    private final double defaultDamageMultiplier;
    private final double knockback;
    private final double reachScale;
    private final int defaultCooldown;
    private final int staggerTicks;
    private final float arcHalfDegrees;

    GuardMove(int length, int hit, int chainTick, double damage, double knockback,
              double reachScale, int cooldown, int stagger, float arcHalfDegrees) {
        this.authoredLength = length;
        this.authoredHit = hit;
        this.chainTick = chainTick;
        this.defaultDamageMultiplier = damage;
        this.knockback = knockback;
        this.reachScale = reachScale;
        this.defaultCooldown = cooldown;
        this.staggerTicks = stagger;
        this.arcHalfDegrees = arcHalfDegrees;
    }

    /** Clip length the animator authored, in ticks. */
    public int authoredLengthTicks() {
        return authoredLength;
    }

    /** Contact tick the animator authored, in ticks after wind-up start. */
    public int authoredHitTick() {
        return authoredHit;
    }

    /** Server contact tick. Only HEAVY's wind-up is configurable. */
    public int hitTick() {
        return this == HEAVY ? HearthsteadServerConfig.guardHeavyWindupTicks()
            : authoredHit;
    }

    /** Ticks of wind-up before contact (equal to {@link #hitTick()}). */
    public int windupTicks() {
        return hitTick();
    }

    /** Full move length; a configured wind-up keeps the authored recovery. */
    public int lengthTicks() {
        return hitTick() + recoveryTicks();
    }

    /** Ticks from contact to the end of the move. */
    public int recoveryTicks() {
        return authoredLength - authoredHit;
    }

    /** Earliest tick after start at which the next combo link may begin. */
    public int chainTick() {
        return chainTick;
    }

    public boolean chains() {
        return chainTick != NO_CHAIN_TICK;
    }

    public double defaultDamageMultiplier() {
        return defaultDamageMultiplier;
    }

    /** Configured damage multiplier applied to the guard's normal attack damage. */
    public double damageMultiplier() {
        return switch (this) {
            case LIGHT_A, LIGHT_B -> HearthsteadServerConfig.guardLightDamageMultiplier();
            case COMBO_FINISHER -> HearthsteadServerConfig.guardFinisherDamageMultiplier();
            case HEAVY -> HearthsteadServerConfig.guardHeavyDamageMultiplier();
            case SHIELD_BASH -> HearthsteadServerConfig.guardShieldBashDamageMultiplier();
        };
    }

    /** Extra horizontal knockback strength added on a landed hit. */
    public double knockback() {
        return knockback;
    }

    /** Fraction of the vanilla melee reach this move may land at (never above 1). */
    public double reachScale() {
        return reachScale;
    }

    public int defaultCooldownTicks() {
        return defaultCooldown;
    }

    /** Ticks after the move ends before a new opener may start. */
    public int cooldownTicks() {
        return switch (this) {
            case LIGHT_A, LIGHT_B -> HearthsteadServerConfig.guardLightCooldownTicks();
            case COMBO_FINISHER -> HearthsteadServerConfig.guardFinisherCooldownTicks();
            case HEAVY -> HearthsteadServerConfig.guardHeavyCooldownTicks();
            case SHIELD_BASH -> defaultCooldown;
        };
    }

    /** Start-to-start ticks when this move is used as a single opener. */
    public int cadenceTicks() {
        return lengthTicks() + cooldownTicks();
    }

    /** Ticks a landed hit staggers the target (0 = no stagger). */
    public int staggerTicks() {
        return staggerTicks;
    }

    public boolean staggers() {
        return staggerTicks > 0;
    }

    /** Half-angle, in degrees, of the swing arc locked at wind-up start. */
    public float arcHalfDegrees() {
        return arcHalfDegrees;
    }

    public boolean isLight() {
        return this == LIGHT_A || this == LIGHT_B;
    }

    /**
     * Presentation-only helper: authored contact over configured contact.
     * A client may scale the wind-up phase by this to keep the impact frame on
     * the server contact when a server raises or lowers the heavy wind-up.
     * Exactly 1.0 at the defaults.
     */
    public static float windupPlaybackScale(GuardMove move) {
        return move.authoredHitTick() / (float) Math.max(1, move.hitTick());
    }

    // ------------------------------------------------ weapon-class timing
    //
    // A guard's move timing follows the weapon TYPE it actually holds
    // (WeaponClass, classified by tag so every material tier agrees). Sword,
    // short sword and axe keep the authored sword moveset above exactly.
    // Every other melee class swings on its own clip: the plain swing uses
    // the class's contactTick/swingLength, two-handed weapons never chain a
    // combo or bash, and the heavy never lands before the weapon's own swing.

    /** True when {@code weapon} keeps the authored sword timings. */
    public static boolean swordTimed(WeaponClass weapon) {
        return weapon == null || weapon == WeaponClass.SWORD
            || weapon == WeaponClass.SHORT_SWORD || weapon == WeaponClass.AXE
            || weapon == WeaponClass.NONE || weapon == WeaponClass.BOW;
    }

    /** Server contact tick for this move swung with {@code weapon}. */
    public int hitTick(WeaponClass weapon) {
        if (swordTimed(weapon)) {
            return hitTick();
        }
        return switch (this) {
            case LIGHT_A, LIGHT_B, COMBO_FINISHER -> Math.max(1, weapon.contactTick());
            case HEAVY -> Math.max(hitTick(), weapon.contactTick());
            case SHIELD_BASH -> hitTick();
        };
    }

    /** Full move length with {@code weapon}. */
    public int lengthTicks(WeaponClass weapon) {
        if (swordTimed(weapon)) {
            return lengthTicks();
        }
        return switch (this) {
            case LIGHT_A, LIGHT_B, COMBO_FINISHER ->
                Math.max(weapon.swingLength(), hitTick(weapon) + 1);
            case HEAVY -> hitTick(weapon) + recoveryTicks();
            case SHIELD_BASH -> lengthTicks();
        };
    }

    public int cadenceTicks(WeaponClass weapon) {
        return lengthTicks(weapon) + cooldownTicks();
    }

    /** Two-handed weapons commit to single blows: no combo chain. */
    public boolean chains(WeaponClass weapon) {
        return chains() && (weapon == null || !weapon.twoHanded());
    }

    /** A two-handed weapon leaves no hand for the shield bash. */
    public static boolean allowsShieldBash(WeaponClass weapon) {
        return weapon == null || !weapon.twoHanded();
    }

    /** Reach multiplier of the weapon (never below vanilla). */
    public static double weaponReach(WeaponClass weapon) {
        return weapon == null || swordTimed(weapon) ? 1.0D
            : Math.max(1.0D, weapon.reachScale());
    }

    /** Combo links in order: light, light, heavy finisher. */
    public static GuardMove comboLink(int index) {
        return switch (index) {
            case 0 -> LIGHT_A;
            case 1 -> LIGHT_B;
            case 2 -> COMBO_FINISHER;
            default -> throw new IllegalArgumentException("combo link " + index);
        };
    }

    public static final int COMBO_LENGTH = 3;
}
