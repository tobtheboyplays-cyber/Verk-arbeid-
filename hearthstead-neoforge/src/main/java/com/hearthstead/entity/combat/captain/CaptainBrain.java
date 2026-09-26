package com.hearthstead.entity.combat.captain;

import javax.annotation.Nullable;

/**
 * Pure special-attack choice for the hero Captain (owner rules): Second Wind
 * when badly hurt, Hold the Line when civilians are threatened, Rally at the
 * start of a fight, the executioner on a finished enemy, crowd attacks when
 * three or more enemies are close, the anti-brute move on a brute, then the
 * loadout's single-target and gap-closing tools. The first special whose
 * condition holds AND is ready (cooldown, loadout) wins.
 */
public final class CaptainBrain {
    /** Crowd specials need this many enemies inside their range. */
    public static final int CROWD = 3;
    public static final int SMALL_CROWD = 2;
    /** "Fight start": Rally is preferred in the first seconds of an engagement. */
    public static final int FIGHT_START_TICKS = 100;

    private CaptainBrain() {
    }

    /**
     * Everything the choice needs, measured by the goal.
     *
     * @param enemyDistances    horizontal distance of every authorized enemy within 24
     * @param targetDistance    distance to the current target, or -1 when none
     * @param targetStrong      brute, raid captain or an armoured/tough mob
     * @param targetGuarded     a raised shield
     * @param targetHealth      target health fraction (1 when none)
     * @param targetCharging    target is closing fast (brace)
     * @param heavyIncomingIn   ticks until an enemy heavy lands on the Captain, -1 if none
     * @param civiliansThreatened an enemy is hunting a civilian or player near the Captain
     */
    public record Situation(float healthFraction, boolean secondWindUsed, int fightTicks,
                            double[] enemyDistances, double targetDistance, boolean targetStrong,
                            boolean targetGuarded, float targetHealth, boolean targetCharging,
                            int heavyIncomingIn, boolean civiliansThreatened) {

        public int within(double r) {
            int n = 0;
            for (double d : enemyDistances) {
                if (d <= r) {
                    n++;
                }
            }
            return n;
        }

        public boolean hasTarget() {
            return targetDistance >= 0.0D;
        }

        public boolean targetWithin(double r) {
            return hasTarget() && targetDistance <= r;
        }
    }

    /** Readiness is supplied by the caller (cooldowns + loadout). */
    @FunctionalInterface
    public interface Ready {
        boolean test(CaptainSpecial special);
    }

    @Nullable
    public static CaptainSpecial choose(CaptainLoadout loadout, Situation s, Ready ready) {
        for (CaptainSpecial sp : order(loadout)) {
            if (ready.test(sp) && wants(sp, s)) {
                return sp;
            }
        }
        return null;
    }

    /** Preference order: survival, protection, morale, finish, crowd, anti-brute, single, reposition. */
    static CaptainSpecial[] order(CaptainLoadout l) {
        return switch (l) {
            case SWORD_SHIELD -> new CaptainSpecial[] {CaptainSpecial.SECOND_WIND,
                CaptainSpecial.HOLD_THE_LINE, CaptainSpecial.RALLY_CRY, CaptainSpecial.EXECUTION,
                CaptainSpecial.POMMEL_STUN, CaptainSpecial.SHIELD_CHARGE};
            case DUAL_SWORDS -> new CaptainSpecial[] {CaptainSpecial.SECOND_WIND,
                CaptainSpecial.DODGE_STEP, CaptainSpecial.RALLY_CRY, CaptainSpecial.EXECUTION,
                CaptainSpecial.BLADE_WHIRL, CaptainSpecial.DISARM, CaptainSpecial.TWIN_THRUST};
            case GREAT_AXE -> new CaptainSpecial[] {CaptainSpecial.SECOND_WIND,
                CaptainSpecial.RALLY_CRY, CaptainSpecial.EXECUTION, CaptainSpecial.SPINNING_CHOP,
                CaptainSpecial.AXE_CLEAVE, CaptainSpecial.ARMOUR_BREAKER};
            case BOW -> new CaptainSpecial[] {CaptainSpecial.SECOND_WIND, CaptainSpecial.RALLY_CRY,
                CaptainSpecial.ARROW_VOLLEY, CaptainSpecial.MARK_TARGET, CaptainSpecial.PIERCING_SHOT};
            case HALBERD -> new CaptainSpecial[] {CaptainSpecial.SECOND_WIND,
                CaptainSpecial.BRACE_CHARGE, CaptainSpecial.RALLY_CRY, CaptainSpecial.EXECUTION,
                CaptainSpecial.HALBERD_SWEEP, CaptainSpecial.HOOK_PULL};
            case WARHAMMER -> new CaptainSpecial[] {CaptainSpecial.SECOND_WIND,
                CaptainSpecial.RALLY_CRY, CaptainSpecial.EXECUTION, CaptainSpecial.GROUND_SLAM,
                CaptainSpecial.SHIELD_BREAKER, CaptainSpecial.CRUSHING_BLOW};
        };
    }

    /** The situational condition of one special (cooldown/loadout are {@link Ready}'s job). */
    public static boolean wants(CaptainSpecial sp, Situation s) {
        return switch (sp) {
            case SECOND_WIND -> !s.secondWindUsed() && s.healthFraction() > 0.0F
                && s.healthFraction() < CaptainSpecial.SECOND_WIND_BELOW && s.within(12.0D) > 0;
            case HOLD_THE_LINE -> s.civiliansThreatened();
            case RALLY_CRY -> s.fightTicks() <= FIGHT_START_TICKS && s.within(16.0D) >= SMALL_CROWD;
            case EXECUTION -> s.targetWithin(sp.range())
                && s.targetHealth() > 0.0F && s.targetHealth() <= CaptainSpecial.EXECUTE_BELOW;
            case POMMEL_STUN, ARMOUR_BREAKER -> s.targetWithin(sp.range()) && s.targetStrong();
            case SHIELD_CHARGE -> s.hasTarget() && s.targetDistance() >= 3.0D
                && s.targetDistance() <= sp.range();
            case BLADE_WHIRL -> s.within(sp.range()) >= SMALL_CROWD;
            case TWIN_THRUST -> s.targetWithin(sp.range());
            case DISARM -> s.targetWithin(sp.range()) && (s.targetStrong() || s.heavyIncomingIn() >= 0);
            case DODGE_STEP -> s.heavyIncomingIn() >= 1 && s.heavyIncomingIn() <= 6;
            case AXE_CLEAVE, HALBERD_SWEEP -> s.within(sp.range()) >= SMALL_CROWD;
            case SPINNING_CHOP, GROUND_SLAM -> s.within(sp.range()) >= CROWD
                || sp == CaptainSpecial.GROUND_SLAM && s.targetWithin(sp.range()) && s.targetStrong();
            case ARROW_VOLLEY -> s.within(sp.range()) >= CROWD;
            case PIERCING_SHOT -> s.targetWithin(sp.range()) && s.within(sp.range()) >= SMALL_CROWD;
            case MARK_TARGET -> s.targetWithin(sp.range()) && s.targetStrong();
            case BRACE_CHARGE -> s.targetCharging() && s.targetWithin(6.0D);
            case HOOK_PULL -> s.hasTarget() && s.targetDistance() >= 3.0D
                && s.targetDistance() <= sp.range();
            case SHIELD_BREAKER -> s.targetWithin(sp.range()) && s.targetGuarded();
            case CRUSHING_BLOW -> s.targetWithin(sp.range());
        };
    }
}
