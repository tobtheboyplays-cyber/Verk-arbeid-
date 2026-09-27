package com.hearthstead.util;

import com.hearthstead.registry.ModItems;
import net.minecraft.world.item.ItemStack;

/**
 * "1 Coin", "4 Coins": the Gold Coin item is named "Coins", so any
 * "count + item name" text read "1 Coins" (QA U4). Everything that prints a
 * Coin amount goes through here.
 */
public final class CoinText {
    private CoinText() {
    }

    public static String coins(long amount) {
        return amount + (amount == 1 ? " Coin" : " Coins");
    }

    public static boolean isCoin(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.GOLD_COIN.get());
    }

    /** "8 Oak Log", or "1 Coin" / "12 Coins" for gold coins. */
    public static String stack(ItemStack stack) {
        return isCoin(stack) ? coins(stack.getCount())
            : stack.getCount() + " " + stack.getHoverName().getString();
    }

    /** Singular noun for one coin, the item name otherwise. */
    public static String unitName(ItemStack stack, long count) {
        return isCoin(stack) ? (count == 1 ? "Coin" : "Coins") : stack.getHoverName().getString();
    }
}
