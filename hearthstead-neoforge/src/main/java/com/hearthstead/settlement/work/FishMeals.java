package com.hearthstead.settlement.work;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.ReadyFood;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Whole catches remain physical until the communal hearth prepares their portions. */
public final class FishMeals {
    public static boolean isWholeCatch(ItemStack stack) {
        return stack.is(ModItems.RIVER_PERCH.get()) || stack.is(ModItems.BROWN_TROUT.get())
            || stack.is(ModItems.SILVER_PIKE.get()) || stack.is(ModItems.GOLDEN_CHAR.get());
    }

    public static int portions(ItemStack stack) {
        if (stack.isEmpty() || GoodsQuality.of(stack) > 0) return 0;
        if (stack.is(ModItems.RIVER_PERCH.get())) return 2;
        if (stack.is(ModItems.BROWN_TROUT.get())) return 3;
        if (stack.is(ModItems.SILVER_PIKE.get())) return 4;
        if (stack.is(ModItems.GOLDEN_CHAR.get())) return 5;
        return 0;
    }

    /** One fish per preparation; full output storage leaves every input unchanged. */
    public static boolean prepareOne(ItemStackHandler inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            int yield = portions(inventory.getStackInSlot(slot));
            if (yield == 0) continue;
            ItemStackHandler trial = ReadyFood.copy(inventory);
            trial.extractItem(slot, 1, false);
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(trial,
                new ItemStack(ModItems.FISH_PORTION.get(), yield), false);
            if (!remainder.isEmpty()) continue;
            for (int i = 0; i < inventory.getSlots(); i++) {
                inventory.setStackInSlot(i, trial.getStackInSlot(i));
            }
            return true;
        }
        return false;
    }

    private FishMeals() {}
}
