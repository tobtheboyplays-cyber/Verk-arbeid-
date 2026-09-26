package com.hearthstead.entity.ai;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CourierBagTransferTest {
    @Test
    void partialChestPublishesOnlyTheAtomicAmountThatFits() {
        SimpleContainer chest = new SimpleContainer(1);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 60));
        assertEquals(4, CourierWorkGoal.transferRoom(chest,
            new ItemStack(Items.OAK_LOG, 12)));
    }

    @Test
    void fullChestPublishesNoTransfer() {
        SimpleContainer chest = new SimpleContainer(1);
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 64));
        assertEquals(0, CourierWorkGoal.transferRoom(chest,
            new ItemStack(Items.OAK_LOG, 12)));
    }

    @Test
    void emptyChestMayPublishTheWholeRealBagStack() {
        assertEquals(12, CourierWorkGoal.transferRoom(new SimpleContainer(1),
            new ItemStack(Items.OAK_LOG, 12)));
    }
}
