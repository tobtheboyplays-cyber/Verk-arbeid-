package com.hearthstead.conversation;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * The goods a barter partner carries. The owner (event content) keeps and
 * saves it; the conversation service only mutates it on an accepted deal,
 * exactly once, on the server thread.
 */
public interface BarterStock {
    /** Current goods, as copies. */
    List<ItemStack> items();

    /**
     * Removes exactly these stacks (matched by item and components, summed by
     * count) atomically. Returns false and changes nothing if any is missing.
     */
    boolean remove(List<ItemStack> taken);

    /** Adds what the player paid. */
    void add(List<ItemStack> given);

    /** Copper value of one of {@code stack}; -1 uses the shared table. */
    default int unitValue(ItemStack stack) {
        return -1;
    }
}
