package com.hearthstead.settlement.work;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerStorageAuthorityTest {

    @Test
    void fullBagRejectsWholeHarvestWithoutPartialMutation() {
        SimpleContainer bag = new SimpleContainer(1);
        bag.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        ItemStack before = bag.getItem(0).copy();

        assertFalse(WorkerStorageAuthority.storeAllInBag(bag,
            List.of(new ItemStack(Items.WHEAT),
                new ItemStack(Items.WHEAT_SEEDS, 2))));
        assertTrue(ItemStack.matches(before, bag.getItem(0)));
    }

    @Test
    void completeBatchIsConservedAcrossMultipleOutputRows() {
        SimpleContainer bag = new SimpleContainer(2);
        bag.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 62));

        assertTrue(WorkerStorageAuthority.storeAllInBag(bag,
            List.of(new ItemStack(Items.WHEAT),
                new ItemStack(Items.WHEAT_SEEDS, 2))));
        assertEquals(64, count(bag, Items.WHEAT_SEEDS));
        assertEquals(1, count(bag, Items.WHEAT));
    }

    @Test
    void extremeBoundsFailBeforeOverflowCanAuthoriseAScan() {
        assertFalse(WorkerStorageAuthority.withinScanBudget(new BoundingBox(
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)));
        assertFalse(WorkerStorageAuthority.withinScanBudget(new BoundingBox(
            0, 0, 0, 256, 256, 256)));
        assertTrue(WorkerStorageAuthority.withinScanBudget(new BoundingBox(
            0, 0, 0, 15, 7, 7)));
    }

    private static int count(SimpleContainer bag,
                             net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < bag.getContainerSize(); slot++) {
            if (bag.getItem(slot).is(item)) {
                count += bag.getItem(slot).getCount();
            }
        }
        return count;
    }
}
