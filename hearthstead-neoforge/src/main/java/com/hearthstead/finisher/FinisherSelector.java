package com.hearthstead.finisher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Picks the solo execution for weapon x enemy. Weighted random among the moves
 * authored for the pair, never repeating the executor's previous move when an
 * alternative exists (a friend watching three executions in a row should see
 * three different ones). Pure: randomness is injected so JUnit can pin it.
 */
public final class FinisherSelector {
    private FinisherSelector() {
    }

    /** Every solo move authored for the pair (never empty: see {@link #fallback}). */
    public static List<FinisherVariant> candidates(WeaponClass weapon, EnemyClass enemy) {
        List<FinisherVariant> out = new ArrayList<>();
        for (FinisherVariant v : FinisherVariant.values()) {
            if (!v.isDouble() && v.fits(weapon, enemy)) {
                out.add(v);
            }
        }
        if (out.isEmpty()) {
            // An odd weapon on an enemy class it has no dedicated move for:
            // any solo move built for that enemy still reads correctly.
            for (FinisherVariant v : FinisherVariant.values()) {
                if (!v.isDouble() && v.enemies().contains(enemy)) {
                    out.add(v);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(fallback());
        }
        return out;
    }

    /**
     * @param random  bound -> uniform int in [0, bound)
     * @param previous the executor's last move, or null
     */
    public static FinisherVariant pick(WeaponClass weapon, EnemyClass enemy,
                                       FinisherVariant previous, IntUnaryOperator random) {
        List<FinisherVariant> pool = candidates(weapon, enemy);
        if (pool.size() > 1 && previous != null) {
            pool.remove(previous);
        }
        int total = 0;
        for (FinisherVariant v : pool) {
            total += Math.max(1, v.weight());
        }
        int roll = Math.floorMod(random.applyAsInt(total), total);
        for (FinisherVariant v : pool) {
            roll -= Math.max(1, v.weight());
            if (roll < 0) {
                return v;
            }
        }
        return pool.get(pool.size() - 1);
    }

    public static FinisherVariant fallback() {
        return FinisherVariant.SWORD_PARRY_THRUST;
    }
}
