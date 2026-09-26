package com.hearthstead.entity.combat;

import com.hearthstead.HearthsteadServerConfig;

/**
 * Raider melee moves as pure data (20 Hz ticks from wind-up start). Every move
 * is server-ticketed: damage lands only on {@link #hitTick()}.
 *
 * <table>
 *   <caption>Raider moveset (defaults)</caption>
 *   <tr><th>move</th><th>clip</th><th>len</th><th>hit</th><th>dmg</th>
 *       <th>kb</th><th>cd</th><th>stagger open/blocked</th><th>slam r</th></tr>
 *   <tr><td>LIGHT</td><td>RAIDER_LIGHT</td><td>8</td><td>3</td>
 *       <td>1.0</td><td>0.0</td><td>12</td><td>0 / 0</td><td>-</td></tr>
 *   <tr><td>CLUB</td><td>BRUTE_CLUB_STRIKE</td><td>34</td><td>18</td>
 *       <td>1.4</td><td>0.8</td><td>8</td><td>8 / 4</td><td>2.5</td></tr>
 *   <tr><td>HEAVY</td><td>RAIDER_HEAVY</td><td>44</td><td>24</td>
 *       <td>2.4</td><td>1.4</td><td>12</td><td>16 / 8</td><td>3.0</td></tr>
 * </table>
 *
 * <p>The Brute's two blows (CLUB and HEAVY) are crushing, readable ground
 * slams: long wind-ups guards can read, heavy knockback, a stagger that even a
 * raised shield only halves, and a shockwave at the club's ground point that
 * pushes and briefly staggers nearby defenders (never other raiders).
 */
public enum RaiderMove {
    LIGHT(8, 3, 1.0D, 0.0D, 12, 0, 0, 0.0D, 0.0D, 0.0D, 0, 0.0F),
    /** The Brute's quicker (still crushing) club slam. */
    CLUB(34, 18, 1.4D, 0.8D, 8, 8, 4, 2.5D, 0.7D, 0.0D, 6, 0.6F),
    /** The telegraphed two-handed smash. */
    HEAVY(44, 24, 2.4D, 1.4D, 12, 16, 8, 3.0D, 1.1D, 1.0D, 10, 1.0F);

    /** Default chance a Brute opens with HEAVY instead of its club. */
    public static final double DEFAULT_BRUTE_HEAVY_CHANCE = 0.70D;
    /** Ordinary Skirmishers are hit-and-run jabbers; they never throw the heavy. */
    public static final double SKIRMISHER_HEAVY_CHANCE = 0.0D;
    /** A Captain, whatever its build, leans on the heavy more often. */
    public static final double CAPTAIN_HEAVY_CHANCE = 0.35D;
    /** Slam knockback/stagger multiplier on a guard braced behind a shield. */
    public static final double BRACED_SLAM_SCALE = 0.35D;
    /** Players within this many blocks of a slam feel the camera shake. */
    public static final double SLAM_SHAKE_RADIUS = 12.0D;

    private final int authoredLength;
    private final int authoredHit;
    private final double defaultDamageMultiplier;
    private final double knockback;
    private final int defaultCooldown;
    private final int staggerTicks;
    private final int blockedStaggerTicks;
    private final double slamRadius;
    private final double slamKnockback;
    private final double slamSplashDamage;
    private final int slamStaggerTicks;
    private final float shakeMagnitude;

    RaiderMove(int length, int hit, double damage, double knockback,
               int cooldown, int staggerTicks, int blockedStaggerTicks,
               double slamRadius, double slamKnockback, double slamSplashDamage,
               int slamStaggerTicks, float shakeMagnitude) {
        this.authoredLength = length;
        this.authoredHit = hit;
        this.defaultDamageMultiplier = damage;
        this.knockback = knockback;
        this.defaultCooldown = cooldown;
        this.staggerTicks = staggerTicks;
        this.blockedStaggerTicks = blockedStaggerTicks;
        this.slamRadius = slamRadius;
        this.slamKnockback = slamKnockback;
        this.slamSplashDamage = slamSplashDamage;
        this.slamStaggerTicks = slamStaggerTicks;
        this.shakeMagnitude = shakeMagnitude;
    }

    public int authoredLengthTicks() {
        return authoredLength;
    }

    public int authoredHitTick() {
        return authoredHit;
    }

    public int hitTick() {
        return this == HEAVY ? HearthsteadServerConfig.raiderHeavyWindupTicks()
            : authoredHit;
    }

    public int recoveryTicks() {
        return authoredLength - authoredHit;
    }

    public int lengthTicks() {
        return hitTick() + recoveryTicks();
    }

    public double defaultDamageMultiplier() {
        return defaultDamageMultiplier;
    }

    public double damageMultiplier() {
        return switch (this) {
            case LIGHT -> HearthsteadServerConfig.raiderLightDamageMultiplier();
            case CLUB -> HearthsteadServerConfig.bruteClubDamageMultiplier();
            case HEAVY -> HearthsteadServerConfig.raiderHeavyDamageMultiplier();
        };
    }

    /** Extra knockback on the main target of a landed blow. */
    public double knockback() {
        return knockback;
    }

    public int defaultCooldownTicks() {
        return defaultCooldown;
    }

    public int cooldownTicks() {
        return this == HEAVY ? HearthsteadServerConfig.raiderHeavyCooldownTicks()
            : defaultCooldown;
    }

    public int cadenceTicks() {
        return lengthTicks() + cooldownTicks();
    }

    /** Ticks a landed blow staggers a Guard (0 = none). */
    public int staggerTicks() {
        return staggerTicks;
    }

    /** Ticks the blow still staggers a Guard who blocked it with a shield. */
    public int blockedStaggerTicks() {
        return blockedStaggerTicks;
    }

    /** A crushing blow: guards read its wind-up and may bash or step back. */
    public boolean crushing() {
        return this == CLUB || this == HEAVY;
    }

    /** Only the Brute's crushing blows slam the ground. */
    public boolean slamsGround(boolean brute) {
        return brute && crushing();
    }

    /** Radius of the ground-slam shockwave, scaled by server config. */
    public double slamRadius() {
        return slamRadius * HearthsteadServerConfig.bruteSlamRadiusScale();
    }

    public double slamKnockback() {
        return slamKnockback;
    }

    public double slamSplashDamage() {
        return slamSplashDamage;
    }

    public int slamStaggerTicks() {
        return slamStaggerTicks;
    }

    /** Camera-shake magnitude at the impact point (client scales by distance). */
    public float shakeMagnitude() {
        return shakeMagnitude;
    }

    /**
     * Pure shockwave falloff: 1 at the impact point, {@code 0.3} at the rim,
     * 0 outside. Keeps the rim push noticeable but never equal to the centre.
     */
    public static double slamFalloff(double distance, double radius) {
        if (radius <= 0.0D || distance > radius) {
            return 0.0D;
        }
        return 1.0D - 0.7D * (distance / radius);
    }

    public static float windupPlaybackScale(RaiderMove move) {
        return move.authoredHitTick() / (float) Math.max(1, move.hitTick());
    }

    /**
     * Pure move choice. {@code roll} is a uniform draw in [0, 1).
     *
     * @param brute   the Brute build (otherwise Skirmisher)
     * @param captain the raid captain
     */
    public static RaiderMove choose(boolean brute, boolean captain, double roll) {
        if (brute) {
            double heavy = Math.max(HearthsteadServerConfig.bruteHeavyChance(),
                captain ? CAPTAIN_HEAVY_CHANCE : 0.0D);
            return roll < heavy ? HEAVY : CLUB;
        }
        double heavy = captain ? CAPTAIN_HEAVY_CHANCE : SKIRMISHER_HEAVY_CHANCE;
        return roll < heavy ? HEAVY : LIGHT;
    }
}
