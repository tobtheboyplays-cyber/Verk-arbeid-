package com.hearthstead.client.render;

import java.util.Locale;

/**
 * Pure rules for the health counter plate (owner, 26 Sep): no bars and
 * nothing above heads, but a small plate just above the hotbar naming the
 * entity you look at, with a heart and "18/24" (and the finisher dagger when
 * it can be finished).
 *
 * <ul>
 *   <li>Looked at: crosshair on the entity within {@link #LOOK_RANGE} blocks.</li>
 *   <li>Lingers {@link #LOOK_LINGER_MS} after you look away.</li>
 *   <li>Just hit by you: {@link #HIT_SHOW_MS} after your hit.</li>
 *   <li>Fades in and out over {@link #FADE_MS}.</li>
 * </ul>
 * No Minecraft types, so the rules and the layout are unit tested.
 */
public final class HealthCounterRules {
    public enum Mode { LOOK_AT, OFF }

    public static final double LOOK_RANGE = 16.0D;
    public static final long LOOK_LINGER_MS = 1500L;
    public static final long HIT_SHOW_MS = 3000L;
    public static final long FADE_MS = 200L;
    public static final float LOW_HEALTH = 0.35F;
    /** Vanilla GUI rows, measured up from the bottom edge. */
    public static final int HOTBAR_H = 22;
    public static final int HEALTH_ROW_Y = 39;
    public static final int ITEM_NAME_Y = 59;
    public static final int NAME_LINE_H = 9;
    public static final int OVERLAY_Y = 68;
    /** Below the crosshair the finisher prompt uses h/2+11 .. h/2+25. */
    public static final int CROSSHAIR_CLEAR = 28;
    public static final int GAP = 3;

    private HealthCounterRules() {
    }

    /** Config value ({@code [hud] healthCounter = lookAt | off}); anything else means lookAt. */
    public static Mode modeOf(String value) {
        return "off".equalsIgnoreCase(value == null ? "" : value.trim()) ? Mode.OFF : Mode.LOOK_AT;
    }

    /**
     * Which entity the plate is about, or -1 for none. The one under the
     * crosshair (within range) always wins; otherwise the most recent of a
     * lingering look or a fresh hit.
     *
     * @param lookedId   entity under the crosshair now, or -1
     * @param sinceLookMs ms since {@code lastLookId} was last under the crosshair
     * @param sinceHitMs  ms since the local player last hit {@code lastHitId}
     */
    public static int target(Mode mode, int lookedId, double lookedDistance,
                             int lastLookId, long sinceLookMs, int lastHitId, long sinceHitMs) {
        if (mode == Mode.OFF) {
            return -1;
        }
        if (lookedId >= 0 && lookedDistance <= LOOK_RANGE) {
            return lookedId;
        }
        boolean look = lastLookId >= 0 && sinceLookMs >= 0L && sinceLookMs <= LOOK_LINGER_MS;
        boolean hit = lastHitId >= 0 && sinceHitMs >= 0L && sinceHitMs <= HIT_SHOW_MS;
        if (look && hit) {
            return sinceHitMs <= sinceLookMs ? lastHitId : lastLookId;
        }
        return look ? lastLookId : hit ? lastHitId : -1;
    }

    /** Eases the plate's opacity toward shown/hidden: full swing in {@link #FADE_MS}. */
    public static float approach(float shown, boolean wanted, long elapsedMs) {
        float step = Math.max(0L, elapsedMs) / (float) FADE_MS;
        float next = wanted ? shown + step : shown - step;
        return Math.max(0.0F, Math.min(1.0F, next));
    }

    /** Hidden with F1, in spectator, behind any open screen, and while you are downed. */
    public static boolean hudAllowed(boolean hideGui, boolean spectator, boolean screenOpen, boolean downed) {
        return !hideGui && !spectator && !screenOpen && !downed;
    }

    /** Top of NeoForge's held-item name line (above the taller stat stack). */
    public static int itemNameTop(int guiHeight, int statStackHeight) {
        return guiHeight - Math.max(statStackHeight, ITEM_NAME_Y);
    }

    /** Top of the action-bar message, drawn just above the item name. */
    public static int overlayMessageTop(int guiHeight, int statStackHeight) {
        return guiHeight - Math.max(statStackHeight + (OVERLAY_Y - ITEM_NAME_Y), OVERLAY_Y) - 4;
    }

    /**
     * The plate rectangle {x, y, w, h}, centred. Its bottom sits a gap above
     * the action-bar line, which clears the hotbar, XP, hearts, armour, food,
     * air, mount health and the item name (NeoForge's {@code Gui.leftHeight/
     * rightHeight} stack, which mods like Detail Armor Bar extend). On short
     * screens where that would reach the finisher prompt under the
     * crosshair, it drops to just above the item-name line instead.
     */
    public static int[] plate(int guiWidth, int guiHeight, int plateWidth, int plateHeight,
                              int statStackHeight) {
        int bottom = overlayMessageTop(guiHeight, statStackHeight) - GAP;
        if (bottom - plateHeight < guiHeight / 2 + CROSSHAIR_CLEAR) {
            bottom = itemNameTop(guiHeight, statStackHeight) - GAP;
        }
        int x = (guiWidth - plateWidth) / 2;
        return new int[]{x, bottom - plateHeight, plateWidth, plateHeight};
    }

    public static final int PLATE_H_FULL = 23;
    public static final int PLATE_H_COMPACT = 14;

    /**
     * 23 px (name line + heart line), or 14 px (heart line only) on short
     * screens where the full plate would reach the finisher prompt.
     */
    public static int plateHeight(int guiHeight, int statStackHeight) {
        int bottom = itemNameTop(guiHeight, statStackHeight) - GAP;
        return bottom - PLATE_H_FULL >= guiHeight / 2 + CROSSHAIR_CLEAR ? PLATE_H_FULL : PLATE_H_COMPACT;
    }

    /** "18/24": whole hit points; one decimal only below 1 so a last half point is not "0". */
    public static String text(float health, float maximum) {
        return number(health) + "/" + number(maximum);
    }

    static String number(float value) {
        float v = Math.max(0.0F, value);
        if (v > 0.0F && v < 1.0F) {
            return String.format(Locale.ROOT, "%.1f", v);
        }
        return Integer.toString(Math.round(v));
    }

    /** Heart tint: a vanilla-like red when healthy, darker as health falls. */
    public static int heartColour(float ratio) {
        final int full = 0xC8403A;
        final int low = 0x7A2622;
        return lerp(low, full, Math.max(0.0F, Math.min(1.0F, ratio)));
    }

    /** Gentle pulse below the warning line: 1.0 when healthy, 0.8..1.0 when low. */
    public static float pulse(float ratio, float seconds) {
        if (ratio >= LOW_HEALTH) {
            return 1.0F;
        }
        return 0.9F + 0.1F * (float) Math.sin(seconds * Math.PI * 2.0D * 1.2D);
    }

    static int lerp(int from, int to, float t) {
        float k = Math.max(0.0F, Math.min(1.0F, t));
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (r << 16) | (g << 8) | b;
    }
}
