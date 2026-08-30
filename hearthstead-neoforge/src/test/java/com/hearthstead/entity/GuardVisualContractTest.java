package com.hearthstead.entity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardVisualContractTest {

    @Test
    void shieldImpactAccentRequiresThePhysicalOffhandItem() {
        assertEquals(SettlerEntity.SHIELD_THUD_DELAY,
            SettlerEntity.shieldThudDelayFor(new ItemStack(Items.SHIELD)));
        assertEquals(-1,
            SettlerEntity.shieldThudDelayFor(ItemStack.EMPTY));
        assertEquals(-1,
            SettlerEntity.shieldThudDelayFor(new ItemStack(Items.IRON_SWORD)));
        assertEquals(-1,
            SettlerEntity.shieldThudDelayFor(new ItemStack(Items.BOW)));
    }

}
