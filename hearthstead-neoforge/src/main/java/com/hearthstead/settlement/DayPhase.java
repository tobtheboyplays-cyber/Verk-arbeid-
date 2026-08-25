package com.hearthstead.settlement;

import net.minecraft.network.chat.Component;

/**
 * The village day. One rhythm the whole settlement shares.
 *
 * <p>Before this, "when" was decided independently inside each goal — the rest
 * goal knew about night, the work goals knew about nothing at all and would
 * have a farmer tilling at three in the morning. A settlement reads as alive
 * because everyone moves at the same time: the square fills at the meal and
 * empties at dusk. That only happens if there is one clock.
 *
 * <p>The phases are cut at Minecraft's own daytime marks so they line up with
 * the light the player sees. Dawn is 23000–1000 (the sky greying), noon is
 * 6000, dusk 12000, midnight 18000.
 *
 * <table>
 *   <caption>The day</caption>
 *   <tr><th>phase</th><th>ticks</th><th>where everyone is</th></tr>
 *   <tr><td>RISE</td><td>23000–1000</td><td>waking, leaving home</td></tr>
 *   <tr><td>MORNING_WORK</td><td>1000–5500</td><td>at their building</td></tr>
 *   <tr><td>MEAL</td><td>5500–7000</td><td>the dining hall, together</td></tr>
 *   <tr><td>AFTERNOON_WORK</td><td>7000–11500</td><td>back at work</td></tr>
 *   <tr><td>EVENING</td><td>11500–12700</td><td>tavern, hearth, the square</td></tr>
 *   <tr><td>REST</td><td>12700–23000</td><td>in their own beds</td></tr>
 * </table>
 *
 * <p>A phase is a <b>default, never a law</b>. A raid alarm overrides it, and so
 * does a need that has become urgent — an exhausted settler sleeps through the
 * morning. That ordering lives in the goal priorities, not here.
 */
public enum DayPhase {
    RISE("rise"),
    MORNING_WORK("morning_work"),
    MEAL("meal"),
    AFTERNOON_WORK("afternoon_work"),
    EVENING("evening"),
    REST("rest");

    private final String key;

    DayPhase(String key) {
        this.key = key;
    }

    public static DayPhase of(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (t >= 12700L || t < 1000L) {
            return t >= 23000L || t < 1000L ? RISE : REST;
        }
        if (t < 5500L) {
            return MORNING_WORK;
        }
        if (t < 7000L) {
            return MEAL;
        }
        if (t < 11500L) {
            return AFTERNOON_WORK;
        }
        return EVENING;
    }

    /** Hours of the working day — the only phases a trade is practised in. */
    public boolean work() {
        return this == MORNING_WORK || this == AFTERNOON_WORK;
    }

    public boolean meal() {
        return this == MEAL;
    }

    /** When the idle gather: the tavern, the hearth, the square. */
    public boolean social() {
        return this == EVENING;
    }

    public boolean rest() {
        return this == REST;
    }

    public String key() {
        return key;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.dayphase." + key);
    }
}
