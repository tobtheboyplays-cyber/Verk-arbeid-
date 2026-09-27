package com.hearthstead.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BagTransferPresentationTest {
    @Test
    void oneVisibleUnitKeepsStableOwnershipAndWorldAnchor() {
        UUID owner = UUID.randomUUID();
        BlockPos bag = new BlockPos(4, 65, 7);
        BlockPos chest = new BlockPos(5, 65, 7);
        BagTransferPresentation start = new BagTransferPresentation(owner,
            bag, 90.0F, chest, 0, false, new ItemStack(Items.OAK_LOG));
        BagTransferPresentation contact = start.advance(48, true);

        assertEquals(owner, contact.transferId());
        assertEquals(bag, contact.bagAnchor());
        assertEquals(chest, contact.containerPos());
        assertEquals(90.0F, contact.bagYaw());
        assertEquals(48, contact.clock());
        assertTrue(contact.committed());
        assertEquals(Items.OAK_LOG, contact.item().getItem());
        assertEquals(1, contact.item().getCount());
    }

    @Test
    void preservesAnExplicitTruthfulStackBundle() {
        BagTransferPresentation bundle = new BagTransferPresentation(
            UUID.randomUUID(), BlockPos.ZERO, 0.0F, BlockPos.ZERO.above(),
            0, false, new ItemStack(Items.OAK_LOG, 12));
        assertEquals(12, bundle.item().getCount());
    }

    @Test
    void rejectsAnEmptyVisualBundle() {
        assertThrows(IllegalArgumentException.class,
            () -> new BagTransferPresentation(UUID.randomUUID(), BlockPos.ZERO,
                0.0F, BlockPos.ZERO.above(), 0, false,
                ItemStack.EMPTY));
    }

    @Test
    void returnedStackIsDefensiveCopy() {
        BagTransferPresentation presentation = new BagTransferPresentation(
            UUID.randomUUID(), BlockPos.ZERO, 0.0F, BlockPos.ZERO.above(),
            0, false, new ItemStack(Items.WHEAT));
        ItemStack external = presentation.item();
        external.setCount(0);
        assertEquals(1, presentation.item().getCount());
    }
}
