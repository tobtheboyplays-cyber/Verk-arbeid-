package com.hearthstead.entity.combat;

/**
 * Pure Guard move choice. No entity, level or random source: the caller
 * passes a {@link Situation} and two uniform rolls in [0, 1), so every branch
 * is unit-testable and GameTests can force outcomes.
 *
 * <p>Order of preference:
 * <ol>
 *   <li><b>Read an enemy heavy.</b> If the target is winding up a heavy and
 *       a bash would land first, bash it (needs a physical shield, bash off
 *       cooldown, and bash reach). Otherwise, if even a light would land too
 *       late, step back out of the swing.</li>
 *   <li><b>Punish.</b> A staggered or blocking target eats a HEAVY.</li>
 *   <li><b>Strong target</b> (Brute, Captain): mostly HEAVY.</li>
 *   <li><b>Ordinary target:</b> LIGHT; experienced guards more often plan
 *       the full light, light, finisher combo.</li>
 * </ol>
 */
public final class GuardMoveSelector {

    /** Probability a guard of each rank ordinal plans a full combo. */
    static final double[] COMBO_CHANCE_BY_RANK = {0.0D, 0.30D, 0.55D, 0.70D, 0.80D};
    /** Base chance to open with HEAVY against a strong target. */
    public static final double STRONG_TARGET_HEAVY_CHANCE = 0.65D;
    /** Guards step back only while the enemy blow is at most this close. */
    public static final int EVADE_LOOKAHEAD_TICKS = 8;

    private GuardMoveSelector() {
    }

    public enum Action {
        /** Begin {@link Choice#move()} now. */
        ATTACK,
        /** Back-step out of an incoming heavy for {@link Choice#evadeTicks()}. */
        EVADE
    }

    /**
     * @param strongTarget            Brute or Captain (armoured/strong)
     * @param enemyHeavyContactIn     ticks until the target's pending HEAVY
     *                                contact, or negative when none is pending
     * @param targetStaggered         the target is currently staggered
     * @param targetBlocking          the target is raising a shield
     * @param rankOrdinal             {@code GuardRank.ordinal()} (0 = Recruit)
     * @param hasShield               physical offhand shield
     * @param bashReady               bash cooldown has elapsed
     * @param inBashReach             target within the bash's shorter reach
     */
    public record Situation(boolean strongTarget, int enemyHeavyContactIn,
                            boolean targetStaggered, boolean targetBlocking,
                            int rankOrdinal, boolean hasShield,
                            boolean bashReady, boolean inBashReach) {

        public boolean enemyHeavyIncoming() {
            return enemyHeavyContactIn >= 0;
        }
    }

    public record Choice(Action action, GuardMove move, boolean planCombo,
                         int evadeTicks) {
        static Choice attack(GuardMove move, boolean combo) {
            return new Choice(Action.ATTACK, move, combo, 0);
        }

        static Choice evade(int ticks) {
            return new Choice(Action.EVADE, null, false, ticks);
        }
    }

    public static double comboChance(int rankOrdinal) {
        int i = Math.max(0, Math.min(COMBO_CHANCE_BY_RANK.length - 1, rankOrdinal));
        return COMBO_CHANCE_BY_RANK[i];
    }

    /**
     * Reaction to an enemy heavy, or {@code null} when there is none to make.
     * Shared by the opener and by the between-link check of a running combo.
     */
    public static Choice react(Situation s) {
        if (!s.enemyHeavyIncoming()) {
            return null;
        }
        int in = s.enemyHeavyContactIn();
        // A bash that connects strictly before the enemy's contact interrupts it.
        if (s.hasShield() && s.bashReady() && s.inBashReach()
            && GuardMove.SHIELD_BASH.hitTick() < in) {
            return Choice.attack(GuardMove.SHIELD_BASH, false);
        }
        // Trading is fine while our light lands first; otherwise get clear.
        if (GuardMove.LIGHT_A.hitTick() < in) {
            return null;
        }
        if (in <= EVADE_LOOKAHEAD_TICKS) {
            return Choice.evade(in + 2);
        }
        return null;
    }

    /**
     * Chooses the opening move of an exchange.
     *
     * @param heavyRoll uniform [0, 1) draw for the strong-target heavy chance
     * @param comboRoll uniform [0, 1) draw for planning a combo
     */
    public static Choice chooseOpener(Situation s, double heavyRoll, double comboRoll) {
        Choice reaction = react(s);
        if (reaction != null) {
            return reaction;
        }
        if (s.targetStaggered() || s.targetBlocking()) {
            return Choice.attack(GuardMove.HEAVY, false);
        }
        if (s.strongTarget() && heavyRoll < STRONG_TARGET_HEAVY_CHANCE) {
            return Choice.attack(GuardMove.HEAVY, false);
        }
        boolean combo = comboRoll < comboChance(s.rankOrdinal());
        return Choice.attack(GuardMove.LIGHT_A, combo);
    }

    /**
     * A running combo continues only if the previous link landed, the target
     * is still in reach and the chain window is still open.
     */
    public static boolean continueCombo(int nextLinkIndex, boolean previousLanded,
                                        boolean targetInReach, long now,
                                        long windowOpens, long windowCloses) {
        return nextLinkIndex > 0 && nextLinkIndex < GuardMove.COMBO_LENGTH
            && previousLanded && targetInReach
            && now >= windowOpens && now <= windowCloses;
    }
}
