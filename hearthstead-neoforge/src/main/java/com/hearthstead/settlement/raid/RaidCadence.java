package com.hearthstead.settlement.raid;

import net.minecraft.util.RandomSource;

/**
 * Pure arithmetic for the recurring raid cadence (owner decision 25 Sep,
 * revised: "after the first raid, a raid every 3-4 in-game days").
 *
 * <p>Nights are the raid-night indices used by
 * {@code RaidLifecycle.raidNightOf}: the night whose dusk most recently
 * passed. When a raid ends on raid night {@code R}, one interval
 * {@code I} is drawn once from the configured window {@code [min, max]}
 * and persisted. The next attack is then GUARANTEED for night
 * {@code R + I} and its warning is committed one dusk earlier, exactly like
 * before. Nothing here reads the world or the config, so every rule is
 * directly unit-testable.
 */
public final class RaidCadence {
    /** Owner default: never earlier than day 3 after the previous raid. */
    public static final int DEFAULT_MIN_DAYS = 3;
    /** Owner default: never later than day 4 (unless the raid is blocked). */
    public static final int DEFAULT_MAX_DAYS = 4;
    /**
     * Hard bounds for any configured window. At least one full day is
     * required because the warning is committed the dusk before the attack.
     */
    public static final int MIN_ALLOWED_DAYS = 1;
    public static final int MAX_ALLOWED_DAYS = 30;
    /**
     * The 25 Sep cadence this window replaces: attack two nights after a
     * held (or retreated) raid, three after a lost one. Kept only to migrate
     * a next-attack night that was persisted under that rule.
     */
    public static final int LEGACY_GAP_AFTER_HELD = 2;
    public static final int LEGACY_GAP_AFTER_LOSS = 3;

    /** A validated inclusive interval window, in in-game days. */
    public record Window(int minDays, int maxDays) {
        public Window {
            minDays = clampDays(minDays);
            maxDays = Math.max(minDays, clampDays(maxDays));
        }

        public boolean contains(int days) {
            return days >= minDays && days <= maxDays;
        }

        public int span() {
            return maxDays - minDays + 1;
        }
    }

    public static final Window DEFAULT_WINDOW =
        new Window(DEFAULT_MIN_DAYS, DEFAULT_MAX_DAYS);

    private RaidCadence() {
    }

    /** Clamps one configured day count into the supported range. */
    public static int clampDays(int days) {
        return Math.max(MIN_ALLOWED_DAYS, Math.min(MAX_ALLOWED_DAYS, days));
    }

    /**
     * Builds a window from two raw config values. A max below the min is
     * treated as "exactly min" rather than an error, so a half-edited config
     * can never produce an empty window or a raid earlier than the minimum.
     */
    public static Window window(int minDays, int maxDays) {
        return new Window(minDays, maxDays);
    }

    /** The small random pick between the window's days, drawn once per raid. */
    public static int pickIntervalDays(Window window, RandomSource random) {
        Window w = window == null ? DEFAULT_WINDOW : window;
        if (w.span() <= 1 || random == null) {
            return w.minDays();
        }
        return w.minDays() + random.nextInt(w.span());
    }

    /** Attack night for a raid that ended on {@code raidNight}. */
    public static long attackNightAfter(long raidNight, int intervalDays) {
        long interval = clampDays(intervalDays);
        if (raidNight > Long.MAX_VALUE - interval) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, raidNight + interval);
    }

    /** The warning is committed at the dusk before the attack, as before. */
    public static long warningNightFor(long attackNight) {
        return Math.max(0L, attackNight - 1L);
    }

    /** Warning night for a raid that ended on {@code raidNight}. */
    public static long warningNightAfter(long raidNight, int intervalDays) {
        return warningNightFor(attackNightAfter(raidNight, intervalDays));
    }

    /**
     * Recovers the raid night {@code R} a legacy (pre-window) save scheduled
     * from. The old rule stored {@code warning = R + gap - 1}, where the gap
     * was 3 after a loss (the persisted breather flag) and 2 otherwise.
     */
    public static long legacyRaidNight(long legacyWarningNight,
                                       boolean lostLastRaid) {
        int gap = lostLastRaid ? LEGACY_GAP_AFTER_LOSS : LEGACY_GAP_AFTER_HELD;
        return Math.max(0L, legacyWarningNight + 1L - gap);
    }

    /**
     * Save migration: re-anchors a legacy next-warning night on the new
     * window. The earliest in-window day is used, so a migrated raid never
     * comes earlier than the configured minimum after the raid it follows
     * (and, with the default window, never earlier than the old promise).
     */
    public static long migrateLegacyWarningNight(long legacyWarningNight,
                                                 boolean lostLastRaid,
                                                 Window window) {
        Window w = window == null ? DEFAULT_WINDOW : window;
        return warningNightAfter(legacyRaidNight(legacyWarningNight,
            lostLastRaid), w.minDays());
    }

    /**
     * True when {@code attackNight} sits inside the window counted from
     * {@code raidNight}: never earlier than min days and never later than
     * max days after the previous raid.
     */
    public static boolean attackWithinWindow(long raidNight, long attackNight,
                                             Window window) {
        Window w = window == null ? DEFAULT_WINDOW : window;
        long gap = attackNight - raidNight;
        return gap >= w.minDays() && gap <= w.maxDays();
    }
}
