package com.hearthstead.event;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First-10-minutes contract (techtree-craft lane, 26 Sep): the early merchant's
 * Basic log row really takes a new player's plain, freshly chopped oak logs
 * and pays one Coin, so a fresh Sunday world can earn its first Coin (Timber
 * Rights costs 1). Guards the offer itself; the live merchant path is
 * scenario_founding_first_coins.
 */
class FirstCoinLogSaleTest {

    @Test
    void basicLogRowTakesPlainLogsAndPaysOneCoin() {
        MerchantOffer offer = GoldCoinTrades.purchase(Items.OAK_LOG);
        ItemStack pack = new ItemStack(Items.OAK_LOG, 64);
        assertFalse(offer.isOutOfStock(), "a fresh Basic log row is open");
        assertTrue(offer.getCostA().getCount() > 0 && offer.getCostA().getCount() <= 16,
            "a sane log quote: " + offer.getCostA().getCount());
        int quote = offer.getCostA().getCount();
        assertTrue(offer.take(pack, ItemStack.EMPTY), "plain chopped oak logs satisfy the Basic row");
        assertEquals(64 - quote, pack.getCount(), "exactly the quote leaves the pack");
        ItemStack paid = offer.assemble();
        assertTrue(paid.is(ModItems.GOLD_COIN.get()) && paid.getCount() == 1, "one Coin per shipment");
    }

    @Test
    void fineRowDoesNotTakeBasicLogs() {
        MerchantOffer fine = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.FINE);
        assertFalse(fine.take(new ItemStack(Items.OAK_LOG, 64), ItemStack.EMPTY),
            "a Fine row needs Fine logs, so the Basic row must be the one a new player uses");
    }
}
