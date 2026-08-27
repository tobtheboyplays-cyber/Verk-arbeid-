package com.hearthstead.settlement;

import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * The single definition of a ready communal meal.
 *
 * <p>A stack counts here if and only if the hearth's real eating path can
 * remove it as food. Recruitment simulations, the courier and the displayed
 * larder count all delegate to this class; none of them maintain a parallel
 * allow-list or a nutrition-point currency.
 */
public final class ReadyFood {

    /** The same NeoForge-aware query used when a settler actually eats. */
    public static boolean isReadyMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getFoodProperties(null) != null;
    }

    /** One edible item is one ready meal, regardless of nutrition value. */
    public static int count(ItemStackHandler inventory) {
        int meals = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (isReadyMeal(stack)) {
                meals += stack.getCount();
            }
        }
        return meals;
    }

    /**
     * Deep slot-for-slot copy used for payment simulation. Mutating the
     * result can never shrink or replace a live hearth stack.
     */
    public static ItemStackHandler copy(ItemStackHandler inventory) {
        ItemStackHandler copy = new ItemStackHandler(inventory.getSlots());
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            copy.setStackInSlot(slot, inventory.getStackInSlot(slot).copy());
        }
        return copy;
    }

    /**
     * Removes one real meal, preferring the most nourishing edible stack.
     * This remains item-based: nutrition only chooses which meal is eaten.
     */
    public static ItemStack extractBest(ItemStackHandler inventory) {
        int bestSlot = -1;
        int bestNutrition = -1;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(null);
            if (food != null && food.nutrition() > bestNutrition) {
                bestSlot = slot;
                bestNutrition = food.nutrition();
            }
        }
        return bestSlot < 0 ? ItemStack.EMPTY : inventory.extractItem(bestSlot, 1, false);
    }

    private ReadyFood() {
    }
}
