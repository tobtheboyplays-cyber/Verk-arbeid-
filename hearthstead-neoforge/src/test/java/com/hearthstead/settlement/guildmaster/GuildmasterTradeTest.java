package com.hearthstead.settlement.guildmaster;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Price and request validation for the Guildmaster's Professions & Emblems trade. */
class GuildmasterTradeTest {

    @Test
    void quantityRoundTripsForEveryCatalogueEmblem() {
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            int id = entry.profession().id();
            assertTrue(id > 0 && id < 256, "profession id fits the low byte: " + entry.profession());
            for (int q = 1; q <= GuildmasterTrade.MAX_QUANTITY; q++) {
                int encoded = GuildmasterTrade.encode(id, q);
                assertEquals(id, GuildmasterTrade.professionIdOf(encoded));
                assertEquals(q, GuildmasterTrade.quantityOf(encoded));
            }
        }
    }

    @Test
    void aPlainProfessionIdStillMeansOneEmblem() {
        // Old single-emblem actions carry just the profession id.
        assertEquals(1, GuildmasterTrade.quantityOf(7));
        assertEquals(7, GuildmasterTrade.professionIdOf(7));
    }

    @Test
    void forgedQuantitiesAreRefusedNotClamped() {
        assertEquals(0, GuildmasterTrade.quantityOf(7 | (GuildmasterTrade.MAX_QUANTITY << 8)),
            "one above the maximum");
        assertEquals(0, GuildmasterTrade.quantityOf(7 | (255 << 8)));
        assertEquals(0, GuildmasterTrade.quantityOf(-1));
        assertEquals(0, GuildmasterTrade.quantityOf(1 << 16), "stray high bits");
        assertEquals(1, GuildmasterTrade.clampQuantity(0));
        assertEquals(1, GuildmasterTrade.clampQuantity(-5));
        assertEquals(GuildmasterTrade.MAX_QUANTITY, GuildmasterTrade.clampQuantity(99));
        assertEquals(GuildmasterTrade.MAX_QUANTITY,
            GuildmasterTrade.quantityOf(GuildmasterTrade.encode(3, 1000)),
            "encode never produces an out-of-range quantity");
    }

    @Test
    void totalIsTheCatalogueUnitPriceTimesQuantity() {
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            List<DevelopmentNode.Cost> unit = entry.costs();
            for (int q : new int[]{1, 2, 5, GuildmasterTrade.MAX_QUANTITY}) {
                List<DevelopmentNode.Cost> total = GuildmasterTrade.total(entry, q);
                assertEquals(unit.size(), total.size());
                for (int i = 0; i < unit.size(); i++) {
                    assertEquals(unit.get(i).item(), total.get(i).item());
                    assertEquals(unit.get(i).count() * q, total.get(i).count(),
                        entry.profession() + " line " + i + " x" + q);
                }
                assertEquals(ModItems.GOLD_COIN.get(), total.get(0).item(), "Coins come first");
                assertEquals(entry.coinPrice() * q, GuildmasterTrade.totalCoins(entry, q));
            }
        }
        assertTrue(GuildmasterTrade.total(null, 3).isEmpty());
        assertTrue(GuildmasterTrade.total(JobEmblemCatalog.RELEASE_CATALOG.get(0), 0).isEmpty());
    }

    @Test
    void everyEmblemNeedsItsOwnEmptySlot() {
        Inventory inventory = new Inventory(null);
        assertEquals(36, GuildmasterTrade.emptySlots(inventory));
        inventory.items.set(0, new ItemStack(Items.OAK_LOG, 5));
        inventory.items.set(9, new ItemStack(ModItems.GOLD_COIN.get(), 3));
        assertEquals(34, GuildmasterTrade.emptySlots(inventory));
        for (int i = 0; i < inventory.items.size(); i++) {
            inventory.items.set(i, new ItemStack(Items.DIRT));
        }
        assertEquals(0, GuildmasterTrade.emptySlots(inventory), "full inventory: nothing may be bought");
        assertEquals(0, GuildmasterTrade.emptySlots(null));
    }
}
