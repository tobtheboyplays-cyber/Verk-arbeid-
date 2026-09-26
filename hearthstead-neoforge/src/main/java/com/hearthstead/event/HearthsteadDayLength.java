package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
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
 * Longer Overworld days so settlers have time to both work and visit the
 * Tavern. The length is the server config value {@code time.dayLengthMultiplier}
 * (default 2.0 = a 40-minute day/night cycle).
 *
 * <p>Vanilla stays authoritative: {@code ServerLevel.tick} runs the sleep skip
 * and then {@code tickTime()}, which adds exactly +1 to day time when
 * {@code doDaylightCycle} is on. We snapshot day time in
 * {@link LevelTickEvent.Pre} and, in {@link LevelTickEvent.Post}, undo that +1
 * on a {@code (1 - 1/multiplier)} fraction of ticks. Any other change (sleep
 * skip, {@code /time set}, gamerule off) is left alone, so time never moves
 * backwards past a skip. Game time is never touched.
 *
 * <p>Clients predict +1 day time per tick between vanilla's 20-tick time
 * syncs. To keep the sun from stepping back by up to 20 ticks each second, a
 * time packet is sent to the level's players on every held-back tick, so a
 * client is never more than one tick ahead (an imperceptible 0.015 degrees).
 *
 * <p>The GameTest server keeps the fixed legacy 1.5x cadence
 * ({@link #holdBackGameTestTick}). Disabled with
 * {@code -Dhearthstead.dayLength.disable} or the legacy
 * {@code -Dhearthstead.longDays=false}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class HearthsteadDayLength {
    static final String LEGACY_ENABLE_PROPERTY = "hearthstead.longDays";
    static final String DISABLE_PROPERTY = "hearthstead.dayLength.disable";
    private static final Map<ServerLevel, Long> PRE_DAY_TIME =
        Collections.synchronizedMap(new WeakHashMap<>());

    private HearthsteadDayLength() {
    }

    @SubscribeEvent
    public static void beforeLevelTick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.dimension() != Level.OVERWORLD
            || !enabled(level.getServer())) {
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
        boolean daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        if (!isOrdinaryAdvance(true, level.dimension() == Level.OVERWORLD,
                daylight, before, current)
            || !(level.getServer() instanceof GameTestServer
                ? holdBackGameTestTick(level.getGameTime())
                : holdBackThisTick(level.getGameTime(),
                    HearthsteadServerConfig.dayLengthMultiplier()))) {
            return;
        }
        level.setDayTime(current - 1L);
        if (!level.players().isEmpty()) {
            level.getServer().getPlayerList().broadcastAll(
                new ClientboundSetTimePacket(level.getGameTime(),
                    level.getDayTime(), daylight),
                level.dimension());
        }
    }

    static boolean enabled(MinecraftServer server) {
        if (System.getProperty(DISABLE_PROPERTY) != null) {
            return false;
        }
        return Boolean.parseBoolean(System.getProperty(LEGACY_ENABLE_PROPERTY, "true"));
    }

    /**
     * The GameTest server keeps the fixed pre-config 1.5x cadence (one held
     * tick in three, by game time) that the suite's time-of-day-sensitive
     * fixtures were authored against. Switching it off, or to the 2x config
     * default, moves unpinned tests into different day phases (for example a
     * day-watch guard asleep at night instead of fetching its sword).
     */
    static boolean holdBackGameTestTick(long gameTime) {
        return Math.floorMod(gameTime, 3L) == 0L;
    }

    /**
     * Pure policy seam: true only for an ordinary vanilla daylight increment
     * (+1 exactly), never for a sleep skip, a command or a frozen clock.
     */
    public static boolean isOrdinaryAdvance(boolean enabled, boolean overworld,
                                            boolean daylightCycle,
                                            long beforeDayTime, long currentDayTime) {
        return enabled && overworld && daylightCycle
            && currentDayTime == beforeDayTime + 1L;
    }

    /**
     * Pure accumulator: each tick adds {@code 1 - 1/multiplier}; a tick holds
     * the clock back when the running total crosses a whole number. Indexed by
     * game time, so it needs no saved state and over any N consecutive ticks
     * holds back {@code floor(N * (1 - 1/multiplier))} (+/- 1) ticks, giving a
     * net day-time rate of {@code 1/multiplier}. The multiplier is clamped to
     * the config range; NaN means vanilla.
     */
    public static boolean holdBackThisTick(long gameTime, double multiplier) {
        double fraction = holdBackFraction(multiplier);
        if (fraction <= 0.0D) {
            return false;
        }
        return Math.floor((gameTime + 1L) * fraction) > Math.floor(gameTime * fraction);
    }

    public static double holdBackFraction(double multiplier) {
        if (Double.isNaN(multiplier)) {
            return 0.0D;
        }
        double clamped = Math.max(HearthsteadServerConfig.MIN_DAY_LENGTH_MULTIPLIER,
            Math.min(HearthsteadServerConfig.MAX_DAY_LENGTH_MULTIPLIER, multiplier));
        return 1.0D - 1.0D / clamped;
    }
}
