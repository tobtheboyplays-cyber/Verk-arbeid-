package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.settlement.work.GoodsQuality;
import com.hearthstead.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FisherTradeGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void fishQuotesRequireExactRareQualityAndKeepFinitePayouts(GameTestHelper helper) {
        for (var fish : new net.minecraft.world.item.Item[] {Items.COD, Items.SALMON}) {
            helper.assertTrue(!GoldCoinTrades.isPurchase(GoldCoinTrades.purchase(fish)),
                "Basic fish are food, not an accepted Coin row");
            int previous = Integer.MAX_VALUE;
            for (int quality = GoodsQuality.FINE; quality <= GoodsQuality.HIGHEST; quality++) {
                var offer = GoldCoinTrades.purchase(fish, quality);
                helper.assertTrue(GoldCoinTrades.isPurchase(offer)
                    && GoodsQuality.of(offer.getBaseCostA()) == quality,
                    "Rare fish retain the exact physical quality component");
                helper.assertTrue(offer.getResult().is(ModItems.GOLD_COIN.get())
                    && offer.getResult().getCount() == 1
                    && offer.getMaxUses() >= 1 && offer.getMaxUses() <= 4,
                    "Every rare quote retains the bounded one-Coin payout policy");
                helper.assertTrue(offer.getBaseCostA().getCount() <= previous,
                    "Rarer fish never require a larger shipment");
                previous = offer.getBaseCostA().getCount();
            }
        }
        var species = new net.minecraft.world.item.Item[] {ModItems.RIVER_PERCH.get(),
            ModItems.BROWN_TROUT.get(), ModItems.SILVER_PIKE.get(), ModItems.GOLDEN_CHAR.get()};
        for (int speciesIndex = 0; speciesIndex < species.length; speciesIndex++) {
            for (int quality = GoodsQuality.BASIC; quality <= GoodsQuality.HIGHEST; quality++) {
                var offer = GoldCoinTrades.purchase(species[speciesIndex], quality);
                boolean expected = speciesIndex == 1 && quality == GoodsQuality.FINE
                    || speciesIndex == 2 && quality == GoodsQuality.SUPERIOR
                    || speciesIndex == 3 && quality >= GoodsQuality.EXCEPTIONAL;
                helper.assertTrue(GoldCoinTrades.isPurchase(offer) == expected,
                    "The species catalogue accepts only Fine trout, Superior pike and Exceptional-or-better char");
                if (expected) {
                    helper.assertTrue(GoodsQuality.of(offer.getBaseCostA()) == quality
                        && offer.getResult().is(ModItems.GOLD_COIN.get())
                        && offer.getResult().getCount() == 1 && offer.getMaxUses() <= 4,
                        "Species quotes preserve exact quality and finite one-Coin payouts");
                }
            }
        }
        helper.succeed();
    }
}
