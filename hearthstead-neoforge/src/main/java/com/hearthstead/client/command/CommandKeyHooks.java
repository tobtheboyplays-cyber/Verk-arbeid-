package com.hearthstead.client.command;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-only seam that lets other features share the Knights command key (R)
 * without a second KeyMapping. On the press edge {@link CommandKeys} asks every
 * interceptor first; if one returns {@code true} the press (and the hold and
 * release that follow it) is consumed, so a single press can never both run a
 * finisher and issue a knights order.
 */
public final class CommandKeyHooks {
    @FunctionalInterface
    public interface KnightsKeyInterceptor {
        /** Main client thread. Return true only if this press was handled. */
        boolean tryConsume(Minecraft mc);
    }

    private static final List<KnightsKeyInterceptor> KNIGHTS = new CopyOnWriteArrayList<>();

    public static void registerKnightsInterceptor(KnightsKeyInterceptor interceptor) {
        if (interceptor != null && !KNIGHTS.contains(interceptor)) {
            KNIGHTS.add(interceptor);
        }
    }

    /** True when some interceptor consumed this Knights-key press. */
    static boolean knightsPressConsumed(Minecraft mc) {
        for (KnightsKeyInterceptor interceptor : KNIGHTS) {
            try {
                if (interceptor.tryConsume(mc)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A faulty hook must never break commanding.
            }
        }
        return false;
    }

    private CommandKeyHooks() {
    }
}
