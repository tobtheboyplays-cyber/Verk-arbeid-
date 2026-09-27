package com.hearthstead.settlement.request;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingOrderBookTransitionsTest {
    private static final ResourceLocation PLANKS = ResourceLocation.parse("minecraft:oak_planks");

    @Test
    void playerOrderCanMoveToWorkshopAndCloseOnlyOnce() {
        CraftingOrderBook book = new CraftingOrderBook();
        UUID workshop = UUID.randomUUID();
        var order = book.open(UUID.randomUUID(), PLANKS, 2,
            CraftingOrderBook.Source.DIRECT, null, 10).order();
        assertEquals(CraftingOrderBook.Status.NEEDS_PLAYER, order.status());
        assertNull(book.noteMade(workshop, PLANKS, 1, 11));
        assertTrue(book.reassign(order, workshop, 12));
        assertEquals(CraftingOrderBook.Status.OPEN, order.status());
        assertSame(order, book.noteMade(workshop, PLANKS, 2, 13));
        assertEquals(2, order.made());
        assertEquals(CraftingOrderBook.Status.CRAFTED, order.status());
        assertNull(book.noteMade(workshop, PLANKS, 1, 14),
            "a crafted order cannot receive the same batch twice");
        assertTrue(book.close(order, true, 15));
        assertFalse(book.close(order, true, 16));
        assertFalse(book.reassign(order, UUID.randomUUID(), 17));
        assertEquals(2, order.made());
        assertEquals(1, book.history().size());
        assertTrue(book.active().isEmpty());
    }

    @Test
    void foreignBookCannotReassignAnOrderItDoesNotOwn() {
        CraftingOrderBook owner = new CraftingOrderBook();
        CraftingOrderBook foreign = new CraftingOrderBook();
        UUID originalWorkshop = UUID.randomUUID();
        var order = owner.open(UUID.randomUUID(), PLANKS, 2,
            CraftingOrderBook.Source.DIRECT, originalWorkshop, 10).order();
        assertFalse(foreign.reassign(order, UUID.randomUUID(), 11));
        assertEquals(originalWorkshop, order.workshopId());
    }
}
