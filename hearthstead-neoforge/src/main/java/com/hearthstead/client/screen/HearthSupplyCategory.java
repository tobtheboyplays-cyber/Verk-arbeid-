package com.hearthstead.client.screen;

import com.hearthstead.registry.ModItems;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;

/** Client-only presentation groups for the Hearth's real physical storage slots. */
enum HearthSupplyCategory {
    ALL("All"),
    FOOD("Food"),
    TOOLS("Tools"),
    WOOD("Wood"),
    FARMING("Farming"),
    BUILDING_MATERIALS("Building Materials"),
    COINS("Coins"),
    OTHER("Other");

    private final String displayName;

    HearthSupplyCategory(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    boolean matches(ItemStack stack) {
        return this == ALL || categoryOf(stack) == this;
    }

    static HearthSupplyCategory categoryOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return OTHER;
        if (stack.is(ModItems.GOLD_COIN.get())) return COINS;
        if (stack.getFoodProperties(null) != null) return FOOD;
        if (isFarming(stack)) return FARMING;
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.SAPLINGS) || stack.is(Items.STICK)) return WOOD;
        if (isTool(stack)) return TOOLS;
        if (stack.getItem() instanceof BlockItem) return BUILDING_MATERIALS;
        return OTHER;
    }

    private static boolean isFarming(ItemStack stack) {
        return stack.getItem() instanceof HoeItem
            || stack.is(Items.WHEAT) || stack.is(Items.CARROT) || stack.is(Items.POTATO)
            || stack.is(Items.BEETROOT) || stack.is(Items.MELON_SLICE) || stack.is(Items.PUMPKIN)
            || stack.is(Items.SUGAR_CANE) || stack.is(Items.CACTUS) || stack.is(Items.BONE_MEAL)
            || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)
            || stack.is(Items.MELON_SEEDS) || stack.is(Items.PUMPKIN_SEEDS)
            || stack.is(Items.TORCHFLOWER_SEEDS) || stack.is(Items.PITCHER_POD);
    }

    private static boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof DiggerItem
            || stack.getItem() instanceof SwordItem
            || stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem
            || stack.getItem() instanceof ShieldItem
            || stack.getItem() instanceof ArmorItem
            || stack.getItem() instanceof FishingRodItem
            || stack.getItem() instanceof ShearsItem;
    }
}
