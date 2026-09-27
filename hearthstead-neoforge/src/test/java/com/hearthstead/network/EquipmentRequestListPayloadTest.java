package com.hearthstead.network;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class EquipmentRequestListPayloadTest {

    @Test
    void rowKeepsBoundedServerAuthoredStatusEvidence() {
        ItemStack source = new ItemStack(Items.IRON_AXE);
        EquipmentRequestListPayload.Row row = new EquipmentRequestListPayload.Row(
            UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
            null, -4, 2, 1, source, 0, 0, 3, 7, 1, -22L, null, true);

        assertEquals("", row.requesterName());
        assertEquals("", row.destinationType());
        assertEquals("", row.courierName());
        assertEquals(1, row.queuePosition());
        assertEquals(1, row.count());
        assertEquals(-1L, row.ageTicks());
        assertEquals(3, row.requestStateWireId());
        assertEquals(7, row.blockerWireId());
        assertEquals(1, row.physicalOwnerWireId());
        assertNotSame(source, row.item());
    }
}
