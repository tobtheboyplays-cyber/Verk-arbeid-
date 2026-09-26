package com.hearthstead.entity;

import java.util.function.IntUnaryOperator;

/**
 * Client-only outline for settlers that only THIS player sees (a summoned
 * settler, the soldiers who will hear a command while a command key is
 * held). Unlike the vanilla Glowing effect, nothing is sent to other players.
 *
 * <p>Common code with no client references: the client installs
 * {@link #color} at setup; on a dedicated server it stays "no outline".
 */
public final class ClientOutlineHook {
    /** Entity network id -> outline RGB, or -1 for none. */
    public static volatile IntUnaryOperator color = id -> -1;

    public static int colorFor(int entityId) {
        try {
            return color.applyAsInt(entityId);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private ClientOutlineHook() {
    }
}
