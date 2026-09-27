package com.hearthstead.settlement.techtree;

import net.minecraft.network.chat.Component;

/**
 * A named number that learned nodes add to, e.g.
 * {@code TechBonus.of("watch.raid_warning_ticks", "+%s ticks raid warning")}.
 * Declare keys as constants in your own branch effect class; read them with
 * {@code TechTree.bonus(level, settlement, KEY)} (the sum over learned nodes,
 * 0 when none).
 *
 * @param id        stable id, "<branch>.<name>"
 * @param fallback  English line for the side panel; %s is the amount
 * @param percent   true when the amount is a percentage (shown as +N%)
 */
public record TechBonus(String id, String fallback, boolean percent) {

    public static TechBonus of(String id, String fallback) {
        return new TechBonus(id, fallback, false);
    }

    public static TechBonus percent(String id, String fallback) {
        return new TechBonus(id, fallback, true);
    }

    public Component describe(double amount) {
        String shown = amount == Math.rint(amount)
            ? String.valueOf((long) amount) : String.valueOf(amount);
        return Component.translatableWithFallback("hearthstead.techtree.bonus." + id,
            fallback.replace("%s", shown), shown);
    }
}
