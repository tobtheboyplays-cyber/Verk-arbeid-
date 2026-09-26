package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.economy.EconomyConfig;
import com.hearthstead.settlement.economy.QualityConfig;
import com.hearthstead.settlement.work.CraftedQuality;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * SCENARIO lane (26 Sep, Codex gap): a graded sale survives a save and
 * reload of the merchant exactly (batch {@code scenario_merchant_reload}).
 * Two Fine-leather sales pay 2 Coins each; the merchant is saved and loaded
 * as after a restart; a replay of the last sale's receipt on the reopened
 * merchant debits nothing; the next real sale debits exactly its 2 Coins;
 * uses and purse are the saved ones, never reset.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ScenarioMerchantGameTests {
    private static final String BATCH = "scenario_merchant_reload";

    @BeforeBatch(batch = BATCH)
    public static void tune(ServerLevel level) {
        QualityConfig.testOverride = Boolean.TRUE;
        EconomyConfig.testOverride = Boolean.TRUE;
    }

    @AfterBatch(batch = BATCH)
    public static void untune(ServerLevel level) {
        QualityConfig.testOverride = null;
        EconomyConfig.testOverride = null;
    }

    private static MerchantOffer fineLeather(WanderingTrader trader) {
        for (MerchantOffer offer : trader.getOffers()) {
            if (offer.getBaseCostA().is(Items.LEATHER) && GoodsQuality.of(offer.getBaseCostA()) == GoodsQuality.FINE) {
                return offer;
            }
        }
        return null;
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = BATCH)
    public void aGradedSaleSurvivesAMerchantReloadAndItsReceiptNeverPaysTwice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WanderingTrader trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Reloadmarket", helper.absolutePos(new BlockPos(8, 2, 8)));
        SettlementSavedData.get(level).settlements.put(settlement.id, settlement);
        trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
        trader.getPersistentData().putLong("HearthsteadMerchantExpires", level.getGameTime() + EarlyCoinMerchant.VISIT_TICKS);
        helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(trader, settlement.id), "fresh owned visitor");
        List<ItemStack> stock = List.of(CraftedQuality.stamp(new ItemStack(Items.LEATHER, 40), GoodsQuality.FINE),
            new ItemStack(Items.OAK_LOG, 64));
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(level, settlement, trader, null, stock), "the market publishes");
        MerchantOffer fine = fineLeather(trader);
        helper.assertTrue(fine != null && fine.getResult().getCount() == 2, "a Fine leather row paying 2 Coins");
        int index = trader.getOffers().indexOf(fine);
        int purse0 = GoldCoinTrades.remainingOwnedPurse(trader);
        var player = helper.makeMockServerPlayerInLevel();
        ItemStack payment = CraftedQuality.stamp(new ItemStack(Items.LEATHER, 40), GoodsQuality.FINE);
        trader.setTradingPlayer(player);
        for (int sale = 0; sale < 2; sale++) {
            helper.assertTrue(fine.take(payment, ItemStack.EMPTY), "a Fine sale takes real Fine leather");
            player.getInventory().add(fine.assemble());
            trader.notifyTrade(fine);
            GoldCoinTrades.finishOwnedPurchase(level, trader, index, fine);
        }
        trader.setTradingPlayer(null);
        int purseSaved = GoldCoinTrades.remainingOwnedPurse(trader);
        helper.assertTrue(purseSaved == purse0 - 4, "two graded sales: exactly 4 Coins out of the purse ("
            + purse0 + " -> " + purseSaved + ")");
        int usesSaved = fine.getUses();

        // Restart: the merchant is written to NBT and loaded back as a new entity.
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(trader.save(saved), "the merchant saves");
        trader.discard();
        WanderingTrader reopened = EntityType.WANDERING_TRADER.create(level);
        helper.assertTrue(reopened != null, "the merchant can be rebuilt");
        reopened.load(saved);
        helper.assertTrue(level.addFreshEntity(reopened), "and rejoins the world");
        MerchantOffer fineAgain = reopened.getOffers().size() > index ? reopened.getOffers().get(index) : null;
        helper.assertTrue(fineAgain != null && fineAgain.getBaseCostA().is(Items.LEATHER)
                && GoodsQuality.of(fineAgain.getBaseCostA()) == GoodsQuality.FINE,
            "the Fine row is at the same place after the reload");
        helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(reopened) == purseSaved && fineAgain.getUses() == usesSaved,
            "purse and uses are the saved ones (purse " + GoldCoinTrades.remainingOwnedPurse(reopened) + ", uses "
                + fineAgain.getUses() + ")");

        // A late receipt for the last sale arrives after reopening: it must pay nothing.
        GoldCoinTrades.finishOwnedPurchase(level, reopened, index, fineAgain);
        helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(reopened) == purseSaved,
            "a replayed receipt after the reload debits nothing (" + GoldCoinTrades.remainingOwnedPurse(reopened) + ")");

        // The next real sale pays exactly once.
        reopened.setTradingPlayer(player);
        helper.assertTrue(fineAgain.take(payment, ItemStack.EMPTY), "a third sale after the reload");
        player.getInventory().add(fineAgain.assemble());
        reopened.notifyTrade(fineAgain);
        GoldCoinTrades.finishOwnedPurchase(level, reopened, index, fineAgain);
        GoldCoinTrades.finishOwnedPurchase(level, reopened, index, fineAgain);
        reopened.setTradingPlayer(null);
        helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(reopened) == purseSaved - 2,
            "the next graded sale debits exactly its 2 Coins once (" + GoldCoinTrades.remainingOwnedPurse(reopened) + ")");
        helper.assertTrue(player.getInventory().countItem(ModItems.GOLD_COIN.get()) == 6
                && payment.getCount() == 40 - 3 * fine.getCostA().getCount(),
            "three sales: 6 Coins for exactly three shipments of Fine leather");
        reopened.discard();
        SettlementSavedData.get(level).settlements.remove(settlement.id);
        helper.succeed();
    }
}
