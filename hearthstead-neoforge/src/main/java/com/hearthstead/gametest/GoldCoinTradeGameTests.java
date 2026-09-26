package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GoldCoinTradeGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void basicMenuPaysOnceAndAnotherPlayerCannotRepeatAfterReload(GameTestHelper helper) {
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        GoldCoinTrades.ensurePurchases(trader);
        var player = helper.makeMockServerPlayerInLevel();
        trader.setTradingPlayer(player);
        var menu = new net.minecraft.world.inventory.MerchantMenu(1, player.getInventory(), trader);
        var basic = trader.getOffers().stream().filter(o -> o.getBaseCostA().is(Items.OAK_LOG)
            && o.getResult().is(ModItems.GOLD_COIN.get())
            && GoodsQuality.of(o.getBaseCostA()) == GoodsQuality.BASIC).findFirst().orElseThrow();
        int index = trader.getOffers().indexOf(basic);
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        helper.assertTrue(!menu.quickMoveStack(player, 2).isEmpty(), "real output slot pays the first shipment");
        helper.assertTrue(menu.quickMoveStack(player, 2).isEmpty()
                && player.getInventory().countItem(ModItems.GOLD_COIN.get()) == 1
                && player.getInventory().countItem(Items.OAK_LOG) + menu.getSlot(0).getItem().getCount() == 0
                && !basic.isOutOfStock() && basic.getUses() == 1 && !GoldCoinTrades.isCompletedBasicPurchase(basic),
            "shift-click pays one valid Basic shipment and consumes exactly eight logs");
        var saved = trader.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var decoded = EntityType.WANDERING_TRADER.create(helper.getLevel());
        helper.assertTrue(decoded != null, "merchant can reload");
        decoded.load(saved);
        GoldCoinTrades.ensurePurchases(decoded);
        var second = helper.makeMockServerPlayerInLevel();
        decoded.setTradingPlayer(second);
        var secondMenu = new net.minecraft.world.inventory.MerchantMenu(2, second.getInventory(), decoded);
        second.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
        secondMenu.setSelectionHint(index);
        secondMenu.tryMoveItems(index);
        helper.assertTrue(!secondMenu.quickMoveStack(second, 2).isEmpty()
                && second.getInventory().countItem(ModItems.GOLD_COIN.get()) == 1
                && second.getInventory().countItem(Items.OAK_LOG) + secondMenu.getSlot(0).getItem().getCount() == 0
                && decoded.getOffers().get(index).getUses() == 2,
            "another player can complete one separate persisted Basic shipment without duplicating payment");
        trader.setTradingPlayer(null);
        decoded.setTradingPlayer(null);
        trader.discard();
        decoded.discard();
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40)
    public void legacyBasicStockMigratesWithoutRefillAndFineRetainsRepeatSales(GameTestHelper helper) {
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        trader.setTradingPlayer(helper.makeMockServerPlayerInLevel());
        GoldCoinTrades.ensurePurchases(trader);
        var offers = trader.getOffers();
        int index = -1;
        for (int i = 0; i < offers.size(); i++) if (offers.get(i).getBaseCostA().is(Items.OAK_LOG)
            && offers.get(i).getResult().is(ModItems.GOLD_COIN.get())
            && GoodsQuality.of(offers.get(i).getBaseCostA()) == GoodsQuality.BASIC) index = i;
        helper.assertTrue(index >= 0, "Basic row exists");
        var legacy = new net.minecraft.world.item.trading.MerchantOffer(
            new net.minecraft.world.item.trading.ItemCost(Items.OAK_LOG, 8),
            new ItemStack(ModItems.GOLD_COIN.get()), 8, 0, 0.10F);
        legacy.increaseUses();
        legacy.setSpecialPriceDiff(2);
        offers.set(index, legacy);
        var originalRows = java.util.List.copyOf(offers);
        GoldCoinTrades.ensurePurchases(trader);
        helper.assertTrue(offers.equals(originalRows) && offers.get(index) == legacy
                && legacy.isOutOfStock() && legacy.getCostA().getCount() == 10,
            "spent old eight-use Basic offer becomes exhausted in place without changing its price or indices");
        var fine = offers.stream().filter(o -> o.getBaseCostA().is(Items.OAK_LOG)
            && o.getResult().is(ModItems.GOLD_COIN.get())
            && GoodsQuality.of(o.getBaseCostA()) == GoodsQuality.FINE).findFirst().orElseThrow();
        for (int sale = 0; sale < 2; sale++) {
            ItemStack payment = new ItemStack(Items.OAK_LOG, fine.getCostA().getCount());
            payment.set(ModComponents.GOODS_QUALITY.get(), GoodsQuality.FINE);
            helper.assertTrue(fine.take(payment, ItemStack.EMPTY) && payment.isEmpty(), "Fine payment is physical");
            trader.notifyTrade(fine);
        }
        helper.assertTrue(fine.getMaxUses() == GoldCoinTrades.MAX_USES && fine.getUses() == 2
                && !fine.isOutOfStock() && fine.getCostA().getCount() == 8
                && !GoldCoinTrades.isCompletedBasicPurchase(fine),
            "Fine retains repeated sales and real price pressure after two shipments");
        var unusedLegacy = new net.minecraft.world.item.trading.MerchantOffer(
            new net.minecraft.world.item.trading.ItemCost(Items.OAK_LOG, 8),
            new ItemStack(ModItems.GOLD_COIN.get()), 8, 0, 0.10F);
        offers.set(index, unusedLegacy);
        GoldCoinTrades.ensurePurchases(trader);
        helper.assertTrue(!unusedLegacy.isOutOfStock(), "an untouched legacy shipment remains available");
        ItemStack legacyPayment = new ItemStack(Items.OAK_LOG, 8);
        helper.assertTrue(unusedLegacy.take(legacyPayment, ItemStack.EMPTY) && legacyPayment.isEmpty(),
            "unused legacy shipment takes its real first payment");
        trader.notifyTrade(unusedLegacy);
        helper.assertTrue(unusedLegacy.isOutOfStock() && GoldCoinTrades.isCompletedBasicPurchase(unusedLegacy),
            "first sale exhausts the old eight-use budget immediately");
        trader.setTradingPlayer(null);
        trader.discard();
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40)
    public void physicalPurchaseRetainsVanillaOffersAndExhaustedStock(GameTestHelper helper) {
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        trader.setTradingPlayer(helper.makeMockServerPlayerInLevel());
        var original = java.util.List.copyOf(trader.getOffers());
        GoldCoinTrades.ensurePurchases(trader);
        helper.assertTrue(trader.getOffers().containsAll(original), "ordinary offers must remain intact");
        int firstVisitBasicCoins = trader.getOffers().stream()
            .filter(o -> o.getResult().is(ModItems.GOLD_COIN.get())
                && GoodsQuality.of(o.getBaseCostA()) == GoodsQuality.BASIC)
            .mapToInt(o -> o.getResult().getCount() * o.getMaxUses()).sum();
        int firstWorkerCost = com.hearthstead.settlement.development.DevelopmentNode.TIMBER_RIGHTS.costs().getFirst().count()
            + com.hearthstead.settlement.development.JobEmblemCatalog.forProfession(
                com.hearthstead.entity.Profession.LUMBERER).costs().getFirst().count();
        helper.assertTrue(firstVisitBasicCoins >= firstWorkerCost,
            "finite player-quality income must still fund Timber Rights and the first Lumberer in one visit");
        var offer = trader.getOffers().stream().filter(o -> o.getResult().is(ModItems.GOLD_COIN.get())
            && o.getBaseCostA().is(Items.OAK_LOG)
            && GoodsQuality.of(o.getBaseCostA()) == GoodsQuality.BASIC).findFirst().orElseThrow();
        ItemStack tooFew = new ItemStack(Items.OAK_LOG, 7);
        helper.assertTrue(!offer.take(tooFew, ItemStack.EMPTY) && tooFew.getCount() == 7,
            "insufficient goods must remain untouched");
        for (int sale = 0; sale < GoldCoinTrades.BASIC_MAX_USES; sale++) {
            int cost = offer.getCostA().getCount();
            ItemStack goods = new ItemStack(Items.OAK_LOG, cost + 1);
            helper.assertTrue(!offer.isOutOfStock() && offer.take(goods, ItemStack.EMPTY)
                    && goods.getCount() == 1,
                "each sale must deduct exactly its current physical input");
            ItemStack coin = offer.assemble();
            helper.assertTrue(coin.is(ModItems.GOLD_COIN.get()) && coin.getCount() == 1,
                "each paid offer yields exactly one physical coin");
            trader.notifyTrade(offer);
            helper.assertTrue(offer.getCostA().getCount() == 8 + (sale + 1) / 2,
                "an ordinary merchant applies only its visible per-row pressure");
        }
        int size = trader.getOffers().size();
        GoldCoinTrades.ensurePurchases(trader);
        helper.assertTrue(offer.isOutOfStock() && offer.getUses() == GoldCoinTrades.BASIC_MAX_USES
                && trader.getOffers().size() == size,
            "reopening must never reset uses or append fresh copies");
        var saved = trader.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var decoded = EntityType.WANDERING_TRADER.create(helper.getLevel());
        helper.assertTrue(decoded != null, "trader must decode");
        decoded.load(saved);
        GoldCoinTrades.ensurePurchases(decoded);
        var retained = decoded.getOffers().stream().filter(o -> o.getResult().is(ModItems.GOLD_COIN.get())
            && o.getBaseCostA().is(Items.OAK_LOG)
            && GoodsQuality.of(o.getBaseCostA()) == GoodsQuality.BASIC).findFirst().orElseThrow();
        helper.assertTrue(retained.isOutOfStock() && decoded.getOffers().size() == size,
            "reload must retain the exact exhausted merchant stock");
        trader.discard();
        helper.succeed();
    }
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void qualitySalesRequireActualComponentsAndConserveTheirPhysicalInputs(GameTestHelper helper) {
        for (int quality = GoodsQuality.BASIC; quality <= GoodsQuality.HIGHEST; quality++) {
            var logs = GoldCoinTrades.purchase(Items.OAK_LOG, quality);
            var planks = GoldCoinTrades.purchase(Items.OAK_PLANKS, quality);
            helper.assertTrue(planks.getCostA().getCount() == 4 * logs.getCostA().getCount()
                    && planks.getResult().getCount() == logs.getResult().getCount(),
                "one log becoming four planks must not increase Coin value at quality " + quality);
        }
        var basic = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.BASIC);
        var fine = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.FINE);
        var superior = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.SUPERIOR);
        var exceptional = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.EXCEPTIONAL);
        var masterwork = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.MASTERWORK);
        var legendary = GoldCoinTrades.purchase(Items.OAK_LOG, GoodsQuality.LEGENDARY);
        helper.assertTrue(basic.getCostA().getCount()==8 && fine.getCostA().getCount()==7
            && superior.getCostA().getCount()==6 && exceptional.getCostA().getCount()==5
            && masterwork.getCostA().getCount()==4 && legendary.getCostA().getCount()==4
            && masterwork.getMaxUses() == GoldCoinTrades.MASTERWORK_MAX_USES
            && legendary.getMaxUses() == GoldCoinTrades.LEGENDARY_MAX_USES,
            "actual quality buys better physical rates, not an origin label");
        ItemStack playerLogs = new ItemStack(Items.OAK_LOG,8);
        helper.assertTrue(!fine.take(playerLogs,ItemStack.EMPTY) && playerLogs.getCount()==8,
            "player Basic goods cannot impersonate Fine output");
        helper.assertTrue(basic.take(playerLogs,ItemStack.EMPTY) && playerLogs.isEmpty(),
            "ordinary player gathering can earn the first Coin before any paid worker");
        ItemStack shortFine = new ItemStack(Items.OAK_LOG,6);
        shortFine.set(ModComponents.GOODS_QUALITY.get(),GoodsQuality.FINE);
        helper.assertTrue(!fine.take(shortFine,ItemStack.EMPTY) && shortFine.getCount()==6,
            "insufficient quality goods remain owned and unchanged");
        shortFine.grow(2);
        helper.assertTrue(fine.take(shortFine,ItemStack.EMPTY) && shortFine.getCount()==1
            && GoodsQuality.of(shortFine)==GoodsQuality.FINE,
            "exactly seven actual Fine goods are consumed and the remainder retains its component");
        helper.assertTrue(basic.assemble().is(ModItems.GOLD_COIN.get())
            && basic.assemble().getCount()==1 && fine.assemble().getCount()==1,
            "each accepted vanilla offer result is one physical Coin");
        helper.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=40)
    public void compactCatalogueDefersOnlyLocalWoodAndNeverRemintsMissingIssuedTier(GameTestHelper helper) {
        var trader=helper.spawn(EntityType.WANDERING_TRADER,new BlockPos(4,2,4));
        var player=helper.makeMockServerPlayerInLevel();
        var interaction=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
            player,net.minecraft.world.InteractionHand.MAIN_HAND,trader);
        GoldCoinTrades.onInteract(interaction);
        helper.assertTrue(trader.getOffers().stream().filter(o->o.getResult().is(ModItems.GOLD_COIN.get())).count()==3,
            "no timber still exposes Basic/Fine wheat and one stretch order");
        player.getInventory().add(new ItemStack(Items.SPRUCE_LOG,8));
        GoldCoinTrades.onInteract(interaction);
        helper.assertTrue(trader.getOffers().stream().filter(o->o.getResult().is(ModItems.GOLD_COIN.get())).count()==5
            && trader.getPersistentData().getString("HearthsteadCoinWood").equals("minecraft:spruce_log"),
            "actual carried local timber installs only its Basic/Fine pair");
        ItemStack rare=new ItemStack(Items.SPRUCE_LOG);
        rare.set(ModComponents.GOODS_QUALITY.get(),GoodsQuality.EXCEPTIONAL);
        player.getInventory().add(rare);
        GoldCoinTrades.onInteract(interaction);
        var tier=trader.getOffers().stream().filter(o->o.getResult().is(ModItems.GOLD_COIN.get())
            && o.getBaseCostA().is(Items.SPRUCE_LOG)
            && GoodsQuality.of(o.getBaseCostA())==GoodsQuality.EXCEPTIONAL).findFirst().orElseThrow();
        helper.assertTrue(trader.getOffers().stream().filter(o->o.getResult().is(ModItems.GOLD_COIN.get())).count()==6,
            "only the demonstrated higher tier appears, not every inaccessible tier");
        trader.getOffers().remove(tier); // Malformed missing persisted row must not become fresh stock.
        GoldCoinTrades.onInteract(interaction);
        helper.assertTrue(trader.getOffers().stream().noneMatch(o->o.getResult().is(ModItems.GOLD_COIN.get())
            && o.getBaseCostA().is(Items.SPRUCE_LOG)
            && GoodsQuality.of(o.getBaseCostA())==GoodsQuality.EXCEPTIONAL),
            "issued tier marker prevents missing/exhausted offer reconstruction");
        trader.discard();
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40, batch = "gold_coin_first_purse")
    public void ownedMerchantPrioritizesRealWoodAndPreservesPaidRowsAcrossBusyReload(GameTestHelper helper) {
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        var decoded = EntityType.WANDERING_TRADER.create(helper.getLevel());
        helper.assertTrue(decoded != null, "saved merchant must be constructible");
        try {
            var settlement = new com.hearthstead.settlement.Settlement(java.util.UUID.randomUUID(), "Market",
                helper.absolutePos(new BlockPos(8, 2, 8)));
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
            trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
            trader.getPersistentData().putLong("HearthsteadMerchantExpires",
                helper.getLevel().getGameTime() + com.hearthstead.event.EarlyCoinMerchant.VISIT_TICKS);
            helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(trader, settlement.id),
                "fresh owned visitor clears vanilla rows before its first market is published");
            var player = helper.makeMockServerPlayerInLevel();
            player.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 8));
            var interaction = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                player, net.minecraft.world.InteractionHand.MAIN_HAND, trader);
            GoldCoinTrades.onInteract(interaction);
            var offers = trader.getOffers();
            helper.assertTrue(offers.size() == 5 && offers.stream().allMatch(o -> o.getResult().is(ModItems.GOLD_COIN.get())
                    && !o.getBaseCostA().is(Items.EMERALD) && !o.getResult().is(Items.EMERALD)),
                "fresh owned visitor replaces every vanilla/Emerald row with five finite Coin rows");
            helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(trader) == GoldCoinTrades.FIRST_VISIT_PURSE,
                "the first owned visitor starts with one shared, persisted payout purse");
            var basic = offers.get(0);
            helper.assertTrue(basic.getResult().is(ModItems.GOLD_COIN.get())
                    && basic.getBaseCostA().is(Items.SPRUCE_LOG)
                    && GoodsQuality.of(basic.getBaseCostA()) == GoodsQuality.BASIC
                    && basic.getCostA().getCount() == 8,
                "first visible offer buys actual carried Basic spruce for one Coin");
            helper.assertTrue(offers.get(1).getBaseCostA().is(Items.SPRUCE_LOG)
                    && GoodsQuality.of(offers.get(1).getBaseCostA()) == GoodsQuality.FINE,
                "Fine spruce is immediately beside the accessible Basic shipment");
            trader.setTradingPlayer(player);
            int paidCoins = 0;
            for (int sale = 0; sale < 1; sale++) {
                helper.assertTrue(basic.take(player.getInventory().getItem(0), ItemStack.EMPTY),
                    "each actual shipment consumes held physical spruce");
                ItemStack result = basic.assemble();
                helper.assertTrue(result.is(ModItems.GOLD_COIN.get()) && result.getCount() == 1,
                    "one accepted shipment yields one physical Coin");
                player.getInventory().add(result);
                paidCoins++;
                trader.notifyTrade(basic);
                // A replayed integration callback must not debit the shared
                // purse or record demand twice for the same completed use.
                GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), trader, offers.indexOf(basic), basic);
            }
            helper.assertTrue(player.getInventory().countItem(Items.SPRUCE_LOG) == 0
                    && player.getInventory().countItem(ModItems.GOLD_COIN.get()) == paidCoins
                    && paidCoins == 1 && basic.getUses() == 1 && basic.getCostA().getCount() == 8
                    && !basic.isOutOfStock() && GoldCoinTrades.remainingOwnedPurse(trader) == GoldCoinTrades.FIRST_VISIT_PURSE - 1,
                "one Basic shipment spends eight logs and debits only one shared Coin");
            var fine = offers.get(1);
            ItemStack finePayment = new ItemStack(Items.SPRUCE_LOG, 64);
            finePayment.set(ModComponents.GOODS_QUALITY.get(), GoodsQuality.FINE);
            for (int sale = 0; sale < GoldCoinTrades.MAX_USES; sale++) {
                helper.assertTrue(fine.take(finePayment, ItemStack.EMPTY),
                    "each remaining purse unit consumes real Fine spruce");
                player.getInventory().add(fine.assemble());
                paidCoins++;
                trader.notifyTrade(fine);
                if (sale == 0) {
                    var receiptBeforeReplay = trader.getPersistentData().copy();
                    GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), trader, offers.indexOf(basic), basic);
                    helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(trader) == GoldCoinTrades.FIRST_VISIT_PURSE - paidCoins
                            && trader.getPersistentData().equals(receiptBeforeReplay),
                        "a replayed Basic sale after a Fine sale cannot debit the shared purse or family demand again");
                    decoded.load(trader.saveWithoutId(new net.minecraft.nbt.CompoundTag()));
                    var restoredBeforeReplay = decoded.getPersistentData().copy();
                    GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), decoded, 0, decoded.getOffers().get(0));
                    GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), decoded, 1, decoded.getOffers().get(1));
                    helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(decoded) == GoldCoinTrades.FIRST_VISIT_PURSE - paidCoins
                            && decoded.getPersistentData().equals(restoredBeforeReplay),
                        "non-adjacent completed sale receipts survive reload without consuming a second Coin");
                    var legacyReceipt = new net.minecraft.nbt.CompoundTag();
                    legacyReceipt.putInt("Revision", decoded.getPersistentData()
                        .getCompound("HearthsteadMerchantLastSale").getInt("Revision"));
                    legacyReceipt.putInt("OfferIndex", 1);
                    legacyReceipt.putInt("UsesAfter", 1);
                    decoded.getPersistentData().put("HearthsteadMerchantLastSale", legacyReceipt);
                    helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement,
                            decoded, player, java.util.List.of()),
                        "a saved single-sale receipt upgrades before opening the next menu");
                    var migratedBeforeReplay = decoded.getPersistentData().copy();
                    GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), decoded, 0, decoded.getOffers().get(0));
                    GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), decoded, 1, decoded.getOffers().get(1));
                    helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(decoded) == GoldCoinTrades.FIRST_VISIT_PURSE - paidCoins
                            && decoded.getPersistentData().equals(migratedBeforeReplay),
                        "legacy receipt migration preserves spent purse and rejects older completed callbacks");
                    var nextFine = decoded.getOffers().get(1);
                    helper.assertTrue(nextFine.take(finePayment.copy(), ItemStack.EMPTY)
                            && nextFine.assemble().is(ModItems.GOLD_COIN.get()),
                        "a migrated merchant still accepts a new physical Fine shipment");
                    decoded.setTradingPlayer(player);
                    decoded.notifyTrade(nextFine);
                    decoded.setTradingPlayer(null);
                    GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), decoded, 1, nextFine);
                    helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(decoded) == GoldCoinTrades.FIRST_VISIT_PURSE - 1 - paidCoins,
                        "the first genuine post-migration use debits exactly one Coin");
                }
            }
            var wheat = offers.get(2);
            ItemStack wheatPayment = new ItemStack(Items.WHEAT, 96);
            for (int sale = 0; sale < GoldCoinTrades.BASIC_MAX_USES; sale++) {
                helper.assertTrue(wheat.take(wheatPayment, ItemStack.EMPTY),
                    "field goods keep a separate physical demand family");
                player.getInventory().add(wheat.assemble());
                paidCoins++;
                trader.notifyTrade(wheat);
            }
            // The 12-Coin first purse (26 Sep) equals what the first visit's
            // rows can buy here: 1 Basic + 4 Fine spruce and 4 wheat leave
            // exactly 3 Coins for Basic iron, the fifth row.
            var iron = offers.get(4);
            helper.assertTrue(iron.getBaseCostA().is(Items.IRON_INGOT)
                    && GoodsQuality.of(iron.getBaseCostA()) == GoodsQuality.BASIC,
                "the first visitor's fifth row buys Basic iron");
            ItemStack ironPayment = new ItemStack(Items.IRON_INGOT, 64);
            while (paidCoins < GoldCoinTrades.FIRST_VISIT_PURSE) {
                helper.assertTrue(!iron.isOutOfStock() && iron.take(ironPayment, ItemStack.EMPTY),
                    "Basic iron keeps paying until the shared purse is empty");
                player.getInventory().add(iron.assemble());
                paidCoins++;
                trader.notifyTrade(iron);
            }
            helper.assertTrue(finePayment.getCount() == 34 && wheatPayment.getCount() == 32
                    && ironPayment.getCount() == 64 - 3 * 8
                    && paidCoins == GoldCoinTrades.FIRST_VISIT_PURSE
                    && GoldCoinTrades.remainingOwnedPurse(trader) == 0
                    && offers.stream().filter(o -> o.getResult().is(ModItems.GOLD_COIN.get()))
                        .allMatch(net.minecraft.world.item.trading.MerchantOffer::isOutOfStock),
                "one shared purse caps Timber and Field rows together, then retires every Coin row");
            ItemStack rare = new ItemStack(Items.SPRUCE_LOG);
            rare.set(ModComponents.GOODS_QUALITY.get(), GoodsQuality.EXCEPTIONAL);
            player.getInventory().setItem(2, rare);
            var beforeBusyRows = java.util.List.copyOf(offers);
            var beforeBusyTags = trader.getPersistentData().copy();
            GoldCoinTrades.onInteract(interaction);
            helper.assertTrue(offers.equals(beforeBusyRows)
                    && trader.getPersistentData().equals(beforeBusyTags),
                "interaction while owned merchant is trading changes neither row indices nor issued tiers");
            trader.setTradingPlayer(null);
            GoldCoinTrades.onInteract(interaction);
            helper.assertTrue(offers.equals(beforeBusyRows) && offers.get(0) == basic,
                "after closing, the fixed visitor market does not retarget or append new rows");
            var saved = trader.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            decoded.load(saved);
            GoldCoinTrades.onInteract(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(
                player, net.minecraft.world.InteractionHand.MAIN_HAND, decoded));
            var savedAgain = decoded.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            helper.assertTrue(savedAgain.getCompound("Offers").equals(saved.getCompound("Offers"))
                    && decoded.getPersistentData().equals(trader.getPersistentData())
                    && decoded.getOffers().get(0).getUses() == GoldCoinTrades.BASIC_MAX_USES
                    && decoded.getOffers().get(1).isOutOfStock()
                    && decoded.getOffers().get(0).getCostA().getCount() == 10
                    && GoldCoinTrades.remainingOwnedPurse(decoded) == 0
                    && player.getInventory().countItem(ModItems.GOLD_COIN.get()) == GoldCoinTrades.FIRST_VISIT_PURSE
                    && player.getInventory().getItem(2) == rare && rare.getCount() == 1,
                "save/reload/reopen preserves the exhausted shared purse, offer order, uses, demand and issuance marker");
        } finally {
            trader.setTradingPlayer(null);
            trader.discard();
            if (decoded != null) decoded.discard();
        }
        helper.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=40)
    public void legacyOwnedMerchantRemovesEmeraldOnlyAfterMenuCloses(GameTestHelper helper) {
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        var settlement = new com.hearthstead.settlement.Settlement(java.util.UUID.randomUUID(), "Legacy",
            helper.absolutePos(new BlockPos(8, 2, 8)));
        com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
        trader.getPersistentData().putLong("HearthsteadMerchantExpires", helper.getLevel().getGameTime() + 2000);
        var retained = GoldCoinTrades.purchase(Items.OAK_LOG);
        retained.increaseUses(); retained.setSpecialPriceDiff(2);
        trader.getOffers().add(retained);
        var player = helper.makeMockServerPlayerInLevel();
        trader.setTradingPlayer(player);
        var busyRows = java.util.List.copyOf(trader.getOffers());
        var busyTags = trader.getPersistentData().copy();
        helper.assertTrue(!GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, java.util.List.of())
                && trader.getOffers().equals(busyRows) && trader.getPersistentData().equals(busyTags),
            "an active legacy menu cannot lose or shift any offer row");
        trader.setTradingPlayer(null);
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, java.util.List.of())
                && trader.getOffers().isEmpty()
                && GoldCoinTrades.remainingOwnedPurse(trader) == 0,
            "a new quote policy retires a pre-policy visitor without a replacement purse");
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, java.util.List.of())
                && trader.getOffers().isEmpty(),
            "a retired pre-policy visitor never restocks or appends replacement Coin rows");
        var stale = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(6, 2, 4));
        stale.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
        stale.getPersistentData().putLong("HearthsteadMerchantExpires", helper.getLevel().getGameTime() + 2000);
        stale.getOffers().add(new net.minecraft.world.item.trading.MerchantOffer(
            new net.minecraft.world.item.trading.ItemCost(Items.OAK_PLANKS, 24),
            new ItemStack(ModItems.GOLD_COIN.get()), GoldCoinTrades.BASIC_MAX_USES, 0, 0.10F));
        var oldMarket = new net.minecraft.nbt.CompoundTag();
        var oldRows = new net.minecraft.nbt.ListTag();
        var oldRow = new net.minecraft.nbt.CompoundTag();
        oldRow.putInt("Index", 0); oldRow.putString("Item", "minecraft:oak_planks");
        oldRow.putInt("Quality", GoodsQuality.BASIC); oldRow.putInt("Baseline", 0);
        oldRows.add(oldRow); oldMarket.put("Rows", oldRows);
        stale.getPersistentData().put("HearthsteadMerchantMarket", oldMarket);
        stale.getPersistentData().putInt("HearthsteadMerchantMarketVersion", 1);
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, stale, null, java.util.List.of())
                && stale.getOffers().isEmpty() && GoldCoinTrades.remainingOwnedPurse(stale) == 0,
            "old cheap plank rows retire during schema migration instead of surviving outside the saved market");
        stale.discard();
        trader.discard();
        com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
        helper.succeed();
    }

    /** A persisted empty legacy market must not permanently hide live Emerald rows. */
    @GameTest(template="empty16",timeoutTicks=40)
    public void persistedEmptyLegacyMarketClearsEmeraldRowsOnceWithoutPublishingCoinStock(GameTestHelper helper) {
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        var settlement = new com.hearthstead.settlement.Settlement(java.util.UUID.randomUUID(), "EmptyLegacy",
            helper.absolutePos(new BlockPos(8, 2, 8)));
        com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        try {
            trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
            trader.getPersistentData().putLong("HearthsteadMerchantExpires", helper.getLevel().getGameTime() + 2000);
            trader.getOffers().clear();
            trader.getOffers().add(new net.minecraft.world.item.trading.MerchantOffer(
                new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, 1),
                new ItemStack(Items.BREAD), 8, 0, 0.05F));
            var market = new net.minecraft.nbt.CompoundTag();
            market.put("Rows", new net.minecraft.nbt.ListTag());
            trader.getPersistentData().put("HearthsteadMerchantMarket", market);
            trader.getPersistentData().putInt("HearthsteadMerchantMarketVersion", 1);
            trader.getPersistentData().putBoolean("HearthsteadMerchantMarketLegacyEmpty", true);

            var player = helper.makeMockServerPlayerInLevel();
            trader.setTradingPlayer(player);
            var busyRows = java.util.List.copyOf(trader.getOffers());
            var busyTags = trader.getPersistentData().copy();
            helper.assertTrue(!GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, java.util.List.of())
                    && trader.getOffers().equals(busyRows) && trader.getPersistentData().equals(busyTags),
                "a busy persisted legacy menu must leave its Emerald row and exact marker untouched");

            trader.setTradingPlayer(null);
            helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, java.util.List.of())
                    && trader.getOffers().isEmpty()
                    && trader.getPersistentData().getCompound("HearthsteadMerchantMarket")
                        .getList("Rows", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty()
                    && trader.getPersistentData().getBoolean("HearthsteadMerchantMarketLegacyEmpty"),
                "an empty persisted legacy market removes only Emerald stock after close and publishes no replacement Coins");
            var afterRows = java.util.List.copyOf(trader.getOffers());
            var afterTags = trader.getPersistentData().copy();
            helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, java.util.List.of())
                    && trader.getOffers().equals(afterRows) && trader.getPersistentData().equals(afterTags),
                "the completed empty migration stays empty and cannot restock on a later reopen");
        } finally {
            trader.setTradingPlayer(null);
            trader.discard();
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
        }
        helper.succeed();
    }

    @GameTest(template="empty16",timeoutTicks=40)
    public void unopenedWoodChoiceMayFollowFineOutputButPaidRowsNeverRetarget(GameTestHelper helper) {
        var trader=helper.spawn(EntityType.WANDERING_TRADER,new BlockPos(4,2,4));
        var player=helper.makeMockServerPlayerInLevel();
        var event=new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(player,net.minecraft.world.InteractionHand.MAIN_HAND,trader);
        player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG)); GoldCoinTrades.onInteract(event);
        var vanilla=trader.getOffers().stream().filter(o->!o.getResult().is(ModItems.GOLD_COIN.get())).toList(); int coinsBefore=player.getInventory().countItem(ModItems.GOLD_COIN.get());
        ItemStack fine=new ItemStack(Items.SPRUCE_LOG,4); fine.set(ModComponents.GOODS_QUALITY.get(),GoodsQuality.FINE); player.getInventory().setItem(0,fine); GoldCoinTrades.onInteract(event);
        helper.assertTrue(trader.getPersistentData().getString("HearthsteadCoinWood").equals("minecraft:spruce_log") && trader.getOffers().containsAll(vanilla) && trader.getOffers().stream().noneMatch(o->o.getResult().is(ModItems.GOLD_COIN.get())&&o.getBaseCostA().is(Items.OAK_LOG)) && trader.getOffers().stream().anyMatch(o->o.getResult().is(ModItems.GOLD_COIN.get())&&o.getBaseCostA().is(Items.SPRUCE_LOG)&&GoodsQuality.of(o.getBaseCostA())==GoodsQuality.FINE)&&player.getInventory().countItem(ModItems.GOLD_COIN.get())==coinsBefore,"unused accidental wood rows yield to real Fine output without modifying vanilla stock or minting Coins");
        var basic=trader.getOffers().stream().filter(o->o.getResult().is(ModItems.GOLD_COIN.get())&&o.getBaseCostA().is(Items.SPRUCE_LOG)&&GoodsQuality.of(o.getBaseCostA())==GoodsQuality.BASIC).findFirst().orElseThrow(); ItemStack payment=new ItemStack(Items.SPRUCE_LOG,8); helper.assertTrue(basic.take(payment,ItemStack.EMPTY),"physical spruce sale commits"); trader.notifyTrade(basic);
        player.getInventory().setItem(0,new ItemStack(Items.OAK_LOG)); GoldCoinTrades.onInteract(event);
        helper.assertTrue(basic.getUses()==1&&basic.getCostA().getCount()==8&&trader.getPersistentData().getString("HearthsteadCoinWood").equals("minecraft:spruce_log")&&trader.getOffers().stream().noneMatch(o->o.getResult().is(ModItems.GOLD_COIN.get())&&o.getBaseCostA().is(Items.OAK_LOG)),"committed sale freezes finite wood rows and demand history"); trader.discard(); helper.succeed();
    }
}
