package com.hearthstead.finisher;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;

/**
 * The two call sites the guard combat lane agreed to (2026-09-26): kept in
 * their own tiny class so SettlerEntity only ever depends on these stable
 * signatures, never on the finisher service internals.
 */
public final class FinisherHooks {
    private FinisherHooks() {
    }

    /**
     * Called in {@code SettlerEntity#beginGuardMove} before a light move
     * claims a raider's cinematic opportunity. False while a player execution
     * or double latch holds the raider, or when this window's guard roll
     * failed (guard executions are rarer than players').
     */
    public static boolean guardMayClaim(SettlerEntity guard, RaiderEntity raider) {
        return FinisherService.guardMayClaim(guard, raider);
    }

    /**
     * Knocks an enemy off balance (condition 1 of the owner's finisher rule) for
     * {@code ticks}. For lanes that add new off-balance sources (parry, new
     * weapons); staggers through RaiderEntity#stagger and heavy slowness stuns
     * are already picked up automatically.
     */
    public static void markOffBalance(net.minecraft.world.entity.LivingEntity enemy, int ticks) {
        FinisherService.markOffBalance(enemy, ticks);
    }

    /**
     * Owner rule for ANY execution (player, guard, knight Captain): the target
     * is off balance AND below 10% health right now, and no other execution
     * holds it.
     */
    public static boolean executionAllowed(net.minecraft.world.entity.LivingEntity target) {
        return FinisherService.executionAllowed(target);
    }

    /**
     * Called when a claimed finishing drive connects and the raider is still
     * alive. Returns true when it became a full execution (the raider is dead;
     * skip {@code triggerCinematicStagger}).
     */
    public static boolean onGuardFinisherContact(SettlerEntity guard, RaiderEntity raider) {
        return FinisherService.onGuardFinisherContact(guard, raider);
    }
}
