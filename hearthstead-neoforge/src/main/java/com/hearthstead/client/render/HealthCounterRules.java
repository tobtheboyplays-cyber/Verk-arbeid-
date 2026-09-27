package com.hearthstead.client.render;

/**
 * Pure rules for the heart counter (owner's pick, 26 Sep: "en hearth counter
 * helt på toppen av skjermen du ser på"): a small heart and "14 / 20" at the
 * very top centre of the screen for the settler, raider or mob you look at.
 * No box, no panel, no bar, nothing over heads.
 *
 * <ul>
 *   <li>Shown while the target is under your aim within {@link #LOOK_RANGE}
 *       blocks; fades in over {@link #FADE_IN_MS}, and out over
 *       {@link #FADE_OUT_MS} after you look away.</li>
 *   <li>Sits where the boss bar lives; when vanilla boss bars show, just
 *       below the last one.</li>
 *   <li>Turns gold and pulses ONLY while the target can really be finished
 *       (off balance and under 10% health, server-synced); no other effects.</li>
 * </ul>
 * No Minecraft types, so every rule here is unit tested.
 */
public final class HealthCounterRules {
    public enum Mode { TOP, OFF }

    public static final double LOOK_RANGE = 16.0D;
    public static final long FADE_IN_MS = 150L;
    public static final long FADE_OUT_MS = 1000L;
    /** Top edge when no boss bar shows. */
    public static final int TOP_Y = 5;
    /** Vanilla boss bar height below its y; the counter keeps this gap under it. */
    public static final int BOSS_BAR_H = 5;
    public static final int GAP = 4;

    private HealthCounterRules() {
    }

    /**
     * Config value ({@code [hud] healthCounter = top | off}). Anything but
     * "off" means top: old "lookAt" configs land on the new counter, never on
     * the retired box.
     */
    public static Mode modeOf(String value) {
        return "off".equalsIgnoreCase(value == null ? "" : value.trim()) ? Mode.OFF : Mode.TOP;
    }

    /** The entity to show, or -1: the one under your aim, within range, when the counter is on. */
    public static int target(Mode mode, int lookedId, double lookedDistance) {
        if (mode == Mode.OFF || lookedId < 0 || !(lookedDistance <= LOOK_RANGE)) {
            return -1;
        }
        return lookedId;
    }

    /** Eases opacity: quickly in ({@link #FADE_IN_MS}), gently out ({@link #FADE_OUT_MS}). */
    public static float approach(float shown, boolean wanted, long elapsedMs) {
        long ms = Math.max(0L, elapsedMs);
        float next = wanted ? shown + ms / (float) FADE_IN_MS : shown - ms / (float) FADE_OUT_MS;
        return Math.max(0.0F, Math.min(1.0F, next));
    }

    /** Hidden with F1, in spectator, behind any open screen, and while you are downed. */
    public static boolean hudAllowed(boolean hideGui, boolean spectator, boolean screenOpen, boolean downed) {
        return !hideGui && !spectator && !screenOpen && !downed;
    }

    /**
     * Top edge of the counter line: {@link #TOP_Y} with no boss bar, else
     * just under the last boss bar drawn this frame.
     *
     * @param lastBossBarY y of the lowest boss bar this frame, or negative for none
     */
    public static int topY(int lastBossBarY) {
        return lastBossBarY < 0 ? TOP_Y : lastBossBarY + BOSS_BAR_H + GAP;
    }

    /** "14 / 20": whole hit points; one decimal only below 1 so a last half point is not "0". */
    public static String text(float health, float maximum) {
        return number(health) + " / " + number(maximum);
    }

    static String number(float value) {
        float v = Math.max(0.0F, value);
        if (v > 0.0F && v < 1.0F) {
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        }
        return Integer.toString(Math.round(v));
    }

    /**
     * Gold pulse strength while finishable (0.55..1), else 0: the only
     * effect the counter has.
     */
    public static float finisherPulse(boolean finishable, float seconds) {
        if (!finishable) {
            return 0.0F;
        }
        return 0.775F + 0.225F * (float) Math.sin(seconds * Math.PI * 2.0D * 0.9D);
    }

    /**
     * Who the counter may show for: NPCs only (settlers, raiders, hostiles),
     * never a player -- not in co-op, not on a server (owner, 26 Sep).
     */
    public static boolean showsFor(boolean player, boolean invisible, boolean alive, boolean npc) {
        return !player && !invisible && alive && npc;
    }
}
