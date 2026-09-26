package com.hearthstead.entity.combat.role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure combat rules for the Spearman and the Longswordsman. No entity, level
 * or random source: callers pass measurements and uniform rolls, so every
 * branch is unit-testable (plan/BATTLE-ROLES.md §1-2).
 */
public final class RoleCombatRules {

    /** Vanilla's horizontal melee inflation, sqrt(2.04) - 0.6. */
    public static final double VANILLA_REACH_INFLATE = 0.828D;

    // ----------------------------------------------------------- brace ---
    /** Closing speed (blocks/tick) that reads as a charge for anyone. */
    public static final double CHARGE_SPEED = 0.15D;
    /** Closing speed that reads as a charge for known chargers (brute, wolf). */
    public static final double KNOWN_CHARGER_SPEED = 0.08D;
    /** A spearman braces on its own when a charger is this close. */
    public static final double AUTO_BRACE_RADIUS = 8.0D;
    /** Brace lapses this long after the last charger was seen. */
    public static final int BRACE_HOLD_TICKS = 40;

    // ---------------------------------------------------------- flanks ---
    /** A hit from further than this off the victim's facing is a flank. */
    public static final float FLANK_ANGLE_DEGREES = 100.0F;
    public static final float SPEAR_FLANK_DAMAGE_MULTIPLIER = 1.35F;
    /** A flank hit knocks a spearman out of brace for this long. */
    public static final int FLANK_BRACE_BREAK_TICKS = 40;

    // ------------------------------------------------------- longsword ---
    public static final float LONGSWORD_PROJECTILE_DAMAGE_MULTIPLIER = 1.25F;
    public static final double LONGSWORD_SPEED_PENALTY = -0.08D;
    /** Chance to open with the heavy against a strong target. */
    public static final double LONGSWORD_STRONG_HEAVY_CHANCE = 0.6D;

    /** Rank ordinal (GuardRank) from which the spear's double thrust appears. */
    public static final int DOUBLE_THRUST_RANK = 2;
    public static final double DOUBLE_THRUST_CHANCE = 0.4D;

    private RoleCombatRules() {
    }

    // ------------------------------------------------------------ reach ---

    /** Centre-to-centre horizontal reach of a move between two bodies. */
    public static double reach(double selfWidth, double targetWidth, double scale) {
        return (selfWidth * 0.5D + VANILLA_REACH_INFLATE + targetWidth * 0.5D) * scale;
    }

    public static boolean inReach(double horizontalDistSqr, double selfWidth,
                                  double targetWidth, double scale) {
        double r = reach(selfWidth, targetWidth, scale);
        return horizontalDistSqr <= r * r;
    }

    /** Smallest absolute difference between two yaws, in [0, 180]. */
    public static float yawDifference(float a, float b) {
        float d = ((a - b) % 360.0F + 540.0F) % 360.0F - 180.0F;
        return Math.abs(d);
    }

    /** Minecraft yaw (degrees) that looks from (fromX, fromZ) toward (toX, toZ). */
    public static float yawToward(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    public static boolean inArc(float swingYaw, float bearingYaw, float halfArcDegrees) {
        return yawDifference(swingYaw, bearingYaw) <= halfArcDegrees;
    }

    // ----------------------------------------------------------- charge ---

    /**
     * Speed at which {@code mover} is closing on {@code anchor}, blocks per
     * tick (positive = approaching). Pure vector maths on the horizontal plane.
     */
    public static double closingSpeed(double anchorX, double anchorZ,
                                      double moverX, double moverZ,
                                      double moverVx, double moverVz) {
        double dx = anchorX - moverX;
        double dz = anchorZ - moverZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6D) {
            return 0.0D;
        }
        return (moverVx * dx + moverVz * dz) / len;
    }

    /** Whether an approach reads as a charge (the brace trigger). */
    public static boolean isCharging(double closingSpeed, boolean knownCharger) {
        return closingSpeed >= CHARGE_SPEED
            || knownCharger && closingSpeed >= KNOWN_CHARGER_SPEED;
    }

    // ------------------------------------------------------------ flank ---

    /** A hit is a flank when the attacker stands well off the victim's facing. */
    public static boolean isFlankHit(float victimFacingYaw, float bearingToAttackerYaw) {
        return yawDifference(victimFacingYaw, bearingToAttackerYaw) > FLANK_ANGLE_DEGREES;
    }

    // ------------------------------------------------------------ cleave ---

    /** One enemy in front of a longswordsman at contact time. */
    public record Candidate<T>(T id, double distSqr, float bearingYaw, boolean primary) {
    }

    /**
     * The fixed hit set of one cleave: the primary first (if it still
     * qualifies), then the nearest others inside the arc and reach, up to
     * {@code maxTargets}. Each candidate appears at most once, so each enemy
     * is hit at most once per swing.
     */
    public static <T> List<T> cleaveTargets(List<Candidate<T>> candidates, float swingYaw,
                                            float halfArc, double maxReachSqr,
                                            int maxTargets) {
        List<Candidate<T>> ok = new ArrayList<>();
        for (Candidate<T> c : candidates) {
            if (c == null || c.id() == null || c.distSqr() > maxReachSqr
                || !inArc(swingYaw, c.bearingYaw(), halfArc)) {
                continue;
            }
            boolean duplicate = false;
            for (Candidate<T> seen : ok) {
                if (seen.id().equals(c.id())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                ok.add(c);
            }
        }
        ok.sort(Comparator.<Candidate<T>, Boolean>comparing(c -> !c.primary())
            .thenComparingDouble(Candidate::distSqr));
        List<T> out = new ArrayList<>();
        for (Candidate<T> c : ok) {
            if (out.size() >= Math.max(1, maxTargets)) {
                break;
            }
            out.add(c.id());
        }
        return out;
    }

    // ------------------------------------------------------- move choice ---

    /** Everything the spear's move choice needs, measured by the goal. */
    public record SpearSituation(boolean braced, boolean targetCharging,
                                 boolean braceStrikeReady, int rankOrdinal) {
    }

    public static RoleMove chooseSpearMove(SpearSituation s, double roll) {
        if (s.braced() && s.targetCharging() && s.braceStrikeReady()) {
            return RoleMove.SPEAR_BRACE_STRIKE;
        }
        if (s.rankOrdinal() >= DOUBLE_THRUST_RANK && roll < DOUBLE_THRUST_CHANCE) {
            return RoleMove.SPEAR_DOUBLE_THRUST;
        }
        return RoleMove.SPEAR_THRUST;
    }

    public record SwordSituation(boolean targetGuarded, boolean halfSwordReady,
                                 boolean targetOpen, boolean strongTarget,
                                 int enemiesInArc) {
    }

    /**
     * Guarded target: break it. Open (staggered or guard-broken) target:
     * punish with the heavy. A crowd: cleave. A strong lone target: mostly
     * heavy. Otherwise cleave (it is the longsword's bread and butter).
     */
    public static RoleMove chooseLongswordMove(SwordSituation s, double roll) {
        if (s.targetGuarded() && s.halfSwordReady()) {
            return RoleMove.LONGSWORD_HALF_SWORD;
        }
        if (s.targetOpen()) {
            return RoleMove.LONGSWORD_HEAVY;
        }
        if (s.enemiesInArc() >= 2) {
            return RoleMove.LONGSWORD_CLEAVE;
        }
        if (s.strongTarget() && roll < LONGSWORD_STRONG_HEAVY_CHANCE) {
            return RoleMove.LONGSWORD_HEAVY;
        }
        return RoleMove.LONGSWORD_CLEAVE;
    }
}
