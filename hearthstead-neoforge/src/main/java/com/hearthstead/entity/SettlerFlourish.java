package com.hearthstead.entity;

import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The small, restrained "grew more skilled" moment: a soft level-up chime,
 * a few happy-villager motes above the head and, optionally, one action-bar
 * line to players standing nearby.
 *
 * <p>Reusable by any growth event (attribute point, trade skill level):
 * call {@link #play(ServerLevel, SettlerEntity, Component)} once per growth.
 * Server only. Own throttles: several calls for one settler on the same game
 * tick collapse into one flourish (e.g. two attributes from one action), and
 * the action-bar line is shown at most once per {@link #LINE_THROTTLE_TICKS}
 * per settler so a fast learner never spams the HUD.</p>
 */
public final class SettlerFlourish {
    /** Nearby = close enough to have seen it happen. */
    public static final double NEARBY = 16.0D;
    /** At most one action-bar line per settler per minute. */
    public static final long LINE_THROTTLE_TICKS = 1200L;

    /** Weak keys: counters and throttles vanish with the settler. */
    private static final Map<SettlerEntity, Integer> FIRED = new WeakHashMap<>();
    private static final Map<SettlerEntity, Long> LAST_FLOURISH = new WeakHashMap<>();
    private static final Map<SettlerEntity, Long> LAST_LINE = new WeakHashMap<>();

    private SettlerFlourish() {
    }

    /**
     * Plays the flourish for one growth event.
     *
     * @param message action-bar line for nearby players, or null for none
     * @return true when a flourish was shown (false when collapsed into one
     *         already shown this tick)
     */
    public static synchronized boolean play(ServerLevel level, SettlerEntity settler,
                                            @Nullable Component message) {
        long now = level.getGameTime();
        Long previous = LAST_FLOURISH.get(settler);
        if (previous != null && previous == now) {
            return false;
        }
        LAST_FLOURISH.put(settler, now);
        FIRED.merge(settler, 1, Integer::sum);

        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(),
            SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.45F, 1.35F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            settler.getX(), settler.getY() + settler.getBbHeight() + 0.15D, settler.getZ(),
            5, 0.3D, 0.2D, 0.3D, 0.0D);

        if (message == null) {
            return true;
        }
        Long lastLine = LAST_LINE.get(settler);
        if (lastLine != null && now >= lastLine && now - lastLine < LINE_THROTTLE_TICKS) {
            return true;
        }
        LAST_LINE.put(settler, now);
        double nearbySq = NEARBY * NEARBY;
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(settler) <= nearbySq) {
                player.displayClientMessage(message, true);
            }
        }
        return true;
    }

    /** "Wilmot the Courier grew more skilled". */
    public static Component skillGrowthLine(SettlerEntity settler) {
        return Component.translatableWithFallback(
            "hearthstead.message.skill_growth",
            "%s the %s grew more skilled",
            settler.getSettlerName(), settler.getProfession().displayName());
    }

    /** How many flourishes this settler has shown (GameTest hook). */
    public static synchronized int firedCount(SettlerEntity settler) {
        return FIRED.getOrDefault(settler, 0);
    }
}
