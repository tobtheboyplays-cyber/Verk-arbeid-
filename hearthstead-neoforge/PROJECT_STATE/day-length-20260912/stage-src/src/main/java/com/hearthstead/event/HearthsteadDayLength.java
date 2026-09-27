package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Optional 1.5x Overworld daylight. Vanilla remains authoritative for sleep,
 * commands and the daylight gamerule: only a normal one-tick daylight advance
 * is corrected, and game time is never changed.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class HearthsteadDayLength {
    static final String ENABLE_PROPERTY = "hearthstead.longDays";
    private static final Map<ServerLevel, Long> PRE_DAY_TIME =
        Collections.synchronizedMap(new WeakHashMap<>());

    private HearthsteadDayLength() {
    }

    @SubscribeEvent
    public static void beforeLevelTick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.dimension() != Level.OVERWORLD || !enabled()) {
            return;
        }
        PRE_DAY_TIME.put(level, level.getDayTime());
    }

    @SubscribeEvent
    public static void afterLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Long before = PRE_DAY_TIME.remove(level);
        if (before == null) {
            return;
        }
        long current = level.getDayTime();
        if (shouldCorrect(enabled(), level.dimension() == Level.OVERWORLD,
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT), before,
                current, level.getGameTime())) {
            level.setDayTime(current - 1L);
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    /** Pure policy seam: true only for an ordinary vanilla daylight increment. */
    static boolean shouldCorrect(boolean enabled, boolean overworld,
                                 boolean daylightCycle, long beforeDayTime,
                                 long currentDayTime, long gameTime) {
        return enabled && overworld && daylightCycle
            && currentDayTime == beforeDayTime + 1L
            && Math.floorMod(gameTime, 3L) == 0L;
    }
}
