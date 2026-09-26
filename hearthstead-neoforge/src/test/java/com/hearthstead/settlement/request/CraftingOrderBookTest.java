package com.hearthstead.settlement.request;

import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CraftingOrderBookTest {
    private static final ResourceLocation CHARCOAL = ResourceLocation.parse("minecraft:charcoal");

    @Test
    void duplicateNeedReturnsLiveOrder() {
        CraftingOrderBook book = new CraftingOrderBook();
        UUID requester = UUID.randomUUID();
        UUID workshop = UUID.randomUUID();
        var first = book.open(requester, CHARCOAL, 4, CraftingOrderBook.Source.FUEL, workshop, 10);
        var second = book.open(requester, CHARCOAL, 8, CraftingOrderBook.Source.FUEL, workshop, 20);
        assertEquals(CraftingOrderBook.OpenResult.CREATED, first.result());
        assertEquals(CraftingOrderBook.OpenResult.DUPLICATE, second.result());
        assertSame(first.order(), second.order());
        assertEquals(1, book.active().size());
    }

    @Test
    void capIsThirtyTwoActive() {
        CraftingOrderBook book = new CraftingOrderBook();
        for (int i = 0; i < CraftingOrderBook.MAX_ACTIVE; i++) {
            assertEquals(CraftingOrderBook.OpenResult.CREATED, book.open(UUID.randomUUID(), CHARCOAL, 1,
                CraftingOrderBook.Source.DIRECT, UUID.randomUUID(), 0).result());
        }
        assertEquals(CraftingOrderBook.OpenResult.CAPPED, book.open(UUID.randomUUID(), CHARCOAL, 1,
            CraftingOrderBook.Source.DIRECT, UUID.randomUUID(), 0).result());
    }

    @Test
    void closeHappensExactlyOnceAndMadeCountsToCrafted() {
        CraftingOrderBook book = new CraftingOrderBook();
        UUID workshop = UUID.randomUUID();
        var order = book.open(UUID.randomUUID(), CHARCOAL, 2, CraftingOrderBook.Source.FUEL, workshop, 0).order();
        assertEquals(CHARCOAL, book.preferredAt(workshop));
        book.noteMade(workshop, CHARCOAL, 1, 1);
        assertEquals(CraftingOrderBook.Status.OPEN, order.status());
        book.noteMade(workshop, CHARCOAL, 1, 2);
        assertEquals(CraftingOrderBook.Status.CRAFTED, order.status());
        assertTrue(book.close(order, true, 3));
        assertFalse(book.close(order, true, 4));
        assertEquals(CraftingOrderBook.Status.FULFILLED, order.status());
        assertNull(book.preferredAt(workshop));
    }

    @Test
    void nbtRoundTripKeepsOrderAndOldSavesLoadEmpty() {
        CraftingOrderBook book = new CraftingOrderBook();
        UUID workshop = UUID.randomUUID();
        var order = book.open(UUID.randomUUID(), CHARCOAL, 5, CraftingOrderBook.Source.MATERIAL, workshop, 7).order();
        book.noteMade(workshop, CHARCOAL, 2, 8);
        book.open(UUID.randomUUID(), CHARCOAL, 3, CraftingOrderBook.Source.MATERIAL, null, 9);
        ListTag saved = book.save();
        CraftingOrderBook loaded = CraftingOrderBook.load(saved);
        var copy = loaded.byId(order.id());
        assertNotNull(copy);
        assertEquals(2, copy.made());
        assertEquals(5, copy.count());
        assertEquals(workshop, copy.workshopId());
        assertEquals(CraftingOrderBook.Status.OPEN, copy.status());
        assertEquals(2, loaded.active().size());
        assertEquals(CraftingOrderBook.Status.NEEDS_PLAYER, loaded.active().get(1).status());
        assertTrue(CraftingOrderBook.load(null).all().isEmpty());
    }
}
