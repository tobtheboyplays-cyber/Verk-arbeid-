package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModItems;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Physical manual sales, retaining the merchant's ordinary stock and trade UI. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GoldCoinTrades {
    private static final String INSTALLED = "HearthsteadCoinPurchasesV2";
    private static final String OWNER = "HearthsteadEarlyMerchantSettlement";
    private static final String MARKET = "HearthsteadMerchantMarket";
    private static final String MARKET_VERSION = "HearthsteadMerchantMarketVersion";
    private static final String MARKET_PURSE = "HearthsteadMerchantPurse";
    private static final String MARKET_REVISION = "HearthsteadMerchantMarketRevision";
    private static final String MARKET_LAST_SALE = "HearthsteadMerchantLastSale";
    private static final String MARKET_FAMILY_SALES = "HearthsteadMerchantFamilySales";
    private static final String MARKET_PENDING = "HearthsteadMerchantMarketPending";
    private static final String MARKET_LEGACY_EMPTY = "HearthsteadMerchantMarketLegacyEmpty";
    private static final int LEGACY_MARKET_SCHEMA = 1;
    private static final int MARKET_SCHEMA = 3;
    private static final int FAMILY_SALES_BEFORE_SURCHARGE = 4;
    private static final int LEGACY_BASIC_MAX_USES = 8;
    public static final int MAX_USES = 4;
    public static final int BASIC_MAX_USES = 4;
    public static final int MASTERWORK_MAX_USES = 2;
    public static final int LEGENDARY_MAX_USES = 1;
    /**
     * Early-Coin balance (26 Sep): the first visitor's purse equals later
     * ones. Its 4-use Basic rows (logs, wheat, iron) already cap what a new
     * player can physically sell at 12, so the old 8-Coin purse only ever cut
     * off players who brought a real mix of goods in the first 20 minutes.
     */
    public static final int FIRST_VISIT_PURSE = 12;
    public static final int RETURNING_VISIT_PURSE = 12;
    private static final int MAX_MARKET_ROWS = 18;
    /**
     * [economy] The purse grows with the village (EconomyConfig
     * merchantPurse*): 12 + 2 per 5 settlers, capped (default 40). This is
     * the hard validation ceiling for any stored purse, above every
     * configurable cap, so a grown purse is never mistaken for corruption.
     */
    public static final int MAX_PURSE_BOUND = 64;
    /** Crafted rows shown per visit, chosen from what the village holds. */
    private static final int CRAFTED_ROWS_PER_VISIT = 3;
    /**
     * [economy] Crafted goods the merchant buys (plan/ECONOMY.md): items per
     * Coin at Basic quality. Each is priced about 1.3-2x above the raw goods
     * that went into it, so a workshop's labour is worth something at the
     * market -- and every crafted good has a buyer. Basic rows only; four
     * sales per row and the shared purse cap them like every other row.
     */
    private static final java.util.Map<Item, Integer> CRAFTED = craftedQuotes();

    private static java.util.Map<Item, Integer> craftedQuotes() {
        java.util.LinkedHashMap<Item, Integer> m = new java.util.LinkedHashMap<>();
        m.put(Items.BREAD, 4);
        m.put(Items.BAKED_POTATO, 8);
        m.put(Items.COOKED_BEEF, 4);
        m.put(Items.COOKED_PORKCHOP, 4);
        m.put(Items.COOKED_MUTTON, 6);
        m.put(Items.COOKED_CHICKEN, 6);
        m.put(Items.LEATHER, 3);
        m.put(Items.STONE_BRICKS, 16);
        m.put(Items.CHARCOAL, 6);
        m.put(Items.WHITE_WOOL, 6);
        m.put(Items.ARROW, 32);
        m.put(Items.BARREL, 2);
        m.put(Items.IRON_AXE, 2);
        m.put(Items.IRON_PICKAXE, 2);
        m.put(Items.IRON_HOE, 3);
        m.put(Items.IRON_SWORD, 3);
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The mod's own crafted goods, registered after class init. */
    private static int craftedQuote(Item item) {
        Integer fixed = CRAFTED.get(item);
        if (fixed != null) return fixed;
        if (item == ModItems.TIMBER_BEAM.get()) return 3;
        if (item == ModItems.WOOL_BOLT.get()) return 2;
        if (item == ModItems.ALE.get()) return 4;
        if (item == ModItems.CURED_HIDE.get()) return 4;
        return 0;
    }

    /** Items one Basic sale really takes: the quote, clamped to the stack size like vanilla's cost. */
    private static int effectiveQuote(Item item) {
        return Math.max(1, Math.min(craftedQuote(item), new ItemStack(item).getMaxStackSize()));
    }

    public static boolean isCraftedGood(Item item) {
        return craftedQuote(item) > 0;
    }
    private static final Item[] GOODS = {Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
        Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG,
        Items.CHERRY_LOG, Items.OAK_PLANKS, Items.WHEAT, Items.CARROT, Items.POTATO,
        Items.BEETROOT, Items.IRON_INGOT, Items.HONEY_BOTTLE, Items.PUMPKIN, Items.COD, Items.SALMON};

    private GoldCoinTrades() {}

    public static MerchantOffer purchase(Item goods) { return purchase(goods, GoodsQuality.BASIC); }

    /** Higher quality earns a modest, explicit physical quote; no origin gate. */
    public static MerchantOffer purchase(Item goods, int quality) {
        if (quality < GoodsQuality.BASIC || quality > GoodsQuality.HIGHEST)
            throw new IllegalArgumentException("Unknown sale quality");
        if (isCraftedGood(goods)) {
            if (quality == GoodsQuality.BASIC)
                return new MerchantOffer(new ItemCost(goods, craftedQuote(goods)),
                    new ItemStack(ModItems.GOLD_COIN.get()), MAX_USES, 0, 0.10F);
            // [quality] a graded crafted good: x1.25 .. x5 of the Basic rate,
            // 1-5 Coins per sale (CraftedQuality#salePrice). Each Coin still
            // comes out of the shared purse (finishOwnedPurchase).
            int[] price = com.hearthstead.settlement.work.CraftedQuality.salePrice(
                craftedQuote(goods), new ItemStack(goods).getMaxStackSize(), quality);
            ItemCost graded = new ItemCost(goods, price[0]).withComponents(builder ->
                builder.expect(ModComponents.GOODS_QUALITY.get(), quality));
            return new MerchantOffer(graded, new ItemStack(ModItems.GOLD_COIN.get(), price[1]),
                maxUsesForQuality(quality), 0, 0.10F);
        }
        int[] quote = isFish(goods) ? new int[] {8, 6, 5, 4, 3, 2} : isFieldGood(goods)
            ? new int[] {16, 14, 12, 10, 9, 8}
            : new int[] {8, 7, 6, 5, 4, 4};
        int count = quote[quality];
        // Vanilla crafting yields four planks per log with no added material.
        // Round the log quote first so no quality tier gains a conversion profit.
        if (goods == Items.OAK_PLANKS) count *= 4;
        ItemCost cost = new ItemCost(goods, count);
        if (quality > GoodsQuality.BASIC) cost = cost.withComponents(builder ->
            builder.expect(ModComponents.GOODS_QUALITY.get(), quality));
        return new MerchantOffer(cost, new ItemStack(ModItems.GOLD_COIN.get()),
            maxUsesForQuality(quality), 0, 0.10F);
    }

    private static int maxUsesForQuality(int quality) {
        return switch (quality) {
            case GoodsQuality.BASIC, GoodsQuality.FINE, GoodsQuality.SUPERIOR -> MAX_USES;
            case GoodsQuality.EXCEPTIONAL, GoodsQuality.MASTERWORK -> MASTERWORK_MAX_USES;
            case GoodsQuality.LEGENDARY -> LEGENDARY_MAX_USES;
            default -> throw new IllegalArgumentException("Unknown sale quality");
        };
    }

    /** Removes vanilla currency rows before a tagged visitor can be published. */
    public static boolean clearOwnedMerchantForPublication(WanderingTrader trader, UUID settlementId) {
        if (trader == null || settlementId == null || !settlementId.equals(trader.getPersistentData().getUUID(OWNER))
            || trader.getTradingPlayer() != null) return false;
        trader.getOffers().clear();
        CompoundTag tag = trader.getPersistentData();
        tag.remove(MARKET); tag.remove(MARKET_VERSION); tag.remove(MARKET_PURSE);
        tag.remove(MARKET_REVISION); tag.remove(MARKET_LAST_SALE);
        tag.remove(MARKET_FAMILY_SALES);
        tag.remove(MARKET_LEGACY_EMPTY);
        tag.putBoolean(MARKET_PENDING, true);
        return true;
    }

    /**
     * Installs a finite, fixed demand list before its first consumer opens a
     * menu. Existing tagged visitors migrate only while no player trades.
     */
    public static boolean ensureOwnedMarket(ServerLevel level, Settlement settlement,
            WanderingTrader trader, @Nullable ServerPlayer player, List<ItemStack> stock) {
        if (level == null || settlement == null || trader == null || trader.level() != level
            || trader.getTradingPlayer() != null || !EarlyCoinMerchant.availableForTrade(trader)
            || !settlement.id.equals(trader.getPersistentData().getUUID(OWNER))) return false;
        CompoundTag tag = trader.getPersistentData();
        if (validOwnedMarket(trader)) {
            prepareOwnedSaleReceipts(trader);
            // A completed visitor should never expose a stale live Coin row
            // after a reload or external data repair. Close it before any
            // player can receive a payout from the empty purse.
            if (tag.getInt(MARKET_PURSE) == 0) retireOwnedCoinRows(trader);
            else retireUnaffordableRows(trader, tag.getInt(MARKET_PURSE));
            return true;
        }
        if (!tag.getBoolean(MARKET_PENDING)) return retirePrePolicyMarket(trader);
        Item wood = preferredWood(trader, player, stock);
        MerchantDemandSavedData demands = MerchantDemandSavedData.get(level);
        List<ResourceLocation> candidates = demands.hasVisit(settlement.id)
            ? productiveCandidates(settlement, stock, wood) : List.of(id(wood), id(Items.WHEAT), id(Items.IRON_INGOT));
        if (candidates.isEmpty()) candidates = List.of(id(wood));
        int wanted = demands.hasVisit(settlement.id) ? Math.min(3, candidates.size()) : 3;
        MerchantDemandSavedData.VisitSnapshot snapshot = demands.beginVisit(settlement.id, candidates,
            level.getGameTime(), wanted);
        if (snapshot == null) return false;
        trader.getOffers().clear();
        List<MarketRow> rows = new ArrayList<>();
        boolean bootstrap = snapshot.sequence() == 0;
        for (ResourceLocation key : snapshot.wanted()) {
            Item item = BuiltInRegistries.ITEM.get(key);
            if (item == Items.AIR) continue;
            if (!isFish(item)) addOwnedRow(trader, rows, item, GoodsQuality.BASIC);
            // Preserve current first-visit income lanes: iron is Basic only.
            if (!bootstrap || item != Items.IRON_INGOT)
                addOwnedRow(trader, rows, item, GoodsQuality.FINE);
            addPublishedPremiumRows(trader, rows, item, player, stock);
        }
        // [economy] crafted goods: up to three rows for what the village (or
        // the player) actually holds, rotating by visit so every workshop's
        // output finds a buyer over a few visits.
        if (com.hearthstead.settlement.economy.EconomyConfig.merchantBuysCrafted(level.getServer())) {
            boolean premiums = com.hearthstead.settlement.economy.QualityConfig.merchantPremiums(level.getServer());
            for (Item crafted : craftedCandidates(player, stock, snapshot.sequence())) {
                if (rows.size() >= MAX_MARKET_ROWS) break;
                addOwnedRow(trader, rows, crafted, GoodsQuality.BASIC);
                // [quality] graded rows only for grades really on hand.
                if (premiums) addCraftedQualityRows(trader, rows, crafted, player, stock);
            }
        }
        if (rows.isEmpty()) return false;
        writeMarket(trader, rows, com.hearthstead.settlement.economy.EconomyConfig.merchantPurse(
            level.getServer(), settlement.population(),
            bootstrap ? FIRST_VISIT_PURSE : RETURNING_VISIT_PURSE)
            // Tech tree v3: Town/Castle/Kingdom charters bring fuller purses.
            + (int) com.hearthstead.settlement.development.TechTree.bonus(level, settlement,
                com.hearthstead.settlement.techtree.effects.CrownEffects.MERCHANT_PURSE)
            // A valid Market (Tavern & Trade) draws richer merchants.
            + com.hearthstead.settlement.techtree.effects.CommonsEffects.marketPurse(level, settlement));
        retireUnaffordableRows(trader, tag.getInt(MARKET_PURSE));
        tag.remove(MARKET_PENDING);
        tag.remove(MARKET_LEGACY_EMPTY);
        return true;
    }

    /** Loaded pre-policy visitors lose Emerald rows once, without choosing new stock. */
    public static void migrateLegacyOwnedMerchant(ServerLevel level, WanderingTrader trader) {
        if (trader == null || trader.getTradingPlayer() != null || !trader.getPersistentData().hasUUID(OWNER)
            || trader.getPersistentData().getBoolean(MARKET_PENDING)) return;
        Settlement settlement = SettlementManager.byId(level, trader.getPersistentData().getUUID(OWNER));
        if (settlement != null) ensureOwnedMarket(level, settlement, trader, null, List.of());
    }

    /** Exact owned-menu validation; generic purchases remain strict for ordinary traders. */
    public static boolean isPurchase(WanderingTrader trader, int offerIndex, MerchantOffer offer) {
        if (trader == null || offer == null) return false;
        return trader.getPersistentData().hasUUID(OWNER)
            ? ownedRow(trader, offerIndex, offer) != null : isPurchase(offer);
    }

    /** Applies one shared purse debit, local offer pressure and future-visit demand. */
    public static void finishOwnedPurchase(ServerLevel level, WanderingTrader trader,
                                           int offerIndex, MerchantOffer offer) {
        MarketRow row = ownedRow(trader, offerIndex, offer);
        if (row == null) return;
        CompoundTag tag = trader.getPersistentData();
        int purse = tag.getInt(MARKET_PURSE);
        if (purse <= 0) {
            retireOwnedCoinRows(trader);
            return;
        }
        if (isDuplicateOwnedSale(tag, row, offer)) return;
        // [quality] a graded crafted row pays 1-5 Coins per sale; every Coin
        // paid comes out of the shared purse (Basic rows pay exactly 1).
        int paid = Math.max(1, offer.getResult().getCount());
        int remaining = Math.max(0, purse - paid);
        tag.putInt(MARKET_PURSE, remaining);
        if (recordOwnedFamilySale(tag, offer.getBaseCostA().getItem()))
            raiseRemainingFamilyQuotes(trader, offer.getBaseCostA().getItem());
        if (remaining == 0) retireOwnedCoinRows(trader);
        else retireUnaffordableRows(trader, remaining);
        refreshOwnedMarketView(trader);
    }

    /**
     * Attributes lane (plan/ATTRIBUTES.md): takes up to {@code coins} extra
     * Coins out of an owned merchant's purse (a trader's Presence bonus) and
     * returns how many were taken, 0 when the purse is empty. Same retire /
     * refresh bookkeeping as {@link #finishOwnedPurchase}; the caller pays the
     * taken coins inside its own receipted commit, so it stays exact-once.
     */
    public static int takeFromOwnedPurse(@Nullable WanderingTrader trader, int coins) {
        if (trader == null || coins <= 0 || !validOwnedMarket(trader)) return 0;
        CompoundTag tag = trader.getPersistentData();
        int purse = tag.getInt(MARKET_PURSE);
        int taken = Math.min(Math.max(0, purse), coins);
        if (taken <= 0) return 0;
        int remaining = purse - taken;
        tag.putInt(MARKET_PURSE, remaining);
        if (remaining == 0) retireOwnedCoinRows(trader);
        else retireUnaffordableRows(trader, remaining);
        refreshOwnedMarketView(trader);
        return taken;
    }

    /** Open events run after the vanilla menu is installed and before its offers packet. */
    @SubscribeEvent
    public static void onMarketOpen(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getContainer() instanceof net.minecraft.world.inventory.MerchantMenu menu)) return;
        for (WanderingTrader trader : player.serverLevel().getEntitiesOfClass(WanderingTrader.class,
                player.getBoundingBox().inflate(8))) {
            if (trader.getTradingPlayer() == player && menu.getOffers() == trader.getOffers()) {
                refreshOwnedMarketView(trader);
                return;
            }
        }
    }

    /** Also covers a worker sale while a player is viewing this exact visitor. */
    private static void refreshOwnedMarketView(WanderingTrader trader) {
        int coins = remainingOwnedPurse(trader);
        if (coins < 0 || !(trader.getTradingPlayer() instanceof ServerPlayer player)
                || !(player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu)
                || menu.getOffers() != trader.getOffers()) return;
        player.sendMerchantOffers(menu.containerId, trader.getOffers(), 1, trader.getVillagerXp(), false, false);
        com.hearthstead.network.PayloadSend.toPlayer(player,
            new com.hearthstead.network.MerchantPursePayload(menu.containerId, coins));
    }

    /** Remaining shared payout for a valid settlement-owned visitor; -1 means no readable purse. */
    public static int remainingOwnedPurse(@Nullable WanderingTrader trader) {
        return trader == null || !trader.getPersistentData().hasUUID(OWNER)
            || !validOwnedMarket(trader) ? -1 : trader.getPersistentData().getInt(MARKET_PURSE);
    }

    private record MarketRow(int index, Item item, int quality, int baseline) { }

    private static void addOwnedRow(WanderingTrader trader, List<MarketRow> rows,
                                    Item item, int quality) {
        if (isFish(item) && !supportsFishQuality(item, quality)) return;
        MerchantOffer offer = purchase(item, quality);
        int baseline = 0;
        int index = trader.getOffers().size();
        trader.getOffers().add(offer);
        rows.add(new MarketRow(index, item, quality, baseline));
    }

    /** Higher tiers remain earned from real local/player goods, but are frozen with this visitor. */
    private static void addPublishedPremiumRows(WanderingTrader trader, List<MarketRow> rows,
            Item item, @Nullable ServerPlayer player, List<ItemStack> stock) {
        // Vanilla log crafting strips the quality component, and the Sawmill
        // does not yet preserve it through a verified conversion. Do not show
        // a premium plank export that ordinary gameplay cannot produce.
        if (item == Items.OAK_PLANKS) return;
        for (int quality = GoodsQuality.SUPERIOR; quality <= GoodsQuality.HIGHEST; quality++) {
            boolean present = false;
            if (player != null) for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item) && GoodsQuality.of(stack) == quality) { present = true; break; }
            }
            if (!present) for (ItemStack stack : stock) {
                if (stack.is(item) && GoodsQuality.of(stack) == quality) { present = true; break; }
            }
            if (present) addOwnedRow(trader, rows, item, quality);
        }
    }

    /**
     * [quality] Fine..Legendary rows for one crafted good, each only when a
     * unit of exactly that grade is in the village stock or the player's
     * inventory -- the merchant never advertises a grade nobody has made.
     */
    private static void addCraftedQualityRows(WanderingTrader trader, List<MarketRow> rows,
            Item item, @Nullable ServerPlayer player, List<ItemStack> stock) {
        for (int quality = GoodsQuality.FINE; quality <= GoodsQuality.HIGHEST; quality++) {
            if (rows.size() >= MAX_MARKET_ROWS) return;
            boolean present = false;
            if (player != null) for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item) && GoodsQuality.of(stack) == quality) { present = true; break; }
            }
            if (!present) for (ItemStack stack : stock) {
                if (stack.is(item) && GoodsQuality.of(stack) == quality) { present = true; break; }
            }
            if (present) addOwnedRow(trader, rows, item, quality);
        }
    }

    private static List<MarketRow> legacyRows(WanderingTrader trader) {
        List<MarketRow> rows = new ArrayList<>();
        for (int i = 0; i < trader.getOffers().size(); i++) {
            MerchantOffer offer = trader.getOffers().get(i);
            if (isPurchase(offer)) rows.add(new MarketRow(i, offer.getBaseCostA().getItem(),
                GoodsQuality.of(offer.getBaseCostA()), offer.getSpecialPriceDiff()));
        }
        return rows;
    }

    private static void writeMarket(WanderingTrader trader, List<MarketRow> rows, int purse) {
        ListTag saved = new ListTag();
        for (MarketRow row : rows) {
            CompoundTag value = new CompoundTag();
            value.putInt("Index", row.index); value.putString("Item", id(row.item).toString());
            value.putInt("Quality", row.quality); value.putInt("Baseline", row.baseline);
            saved.add(value);
        }
        CompoundTag market = new CompoundTag(); market.put("Rows", saved);
        CompoundTag tag = trader.getPersistentData();
        int previousRevision = tag.contains(MARKET_REVISION, Tag.TAG_INT)
            ? tag.getInt(MARKET_REVISION) : 0;
        int nextRevision = previousRevision == Integer.MAX_VALUE ? 1 : Math.max(1, previousRevision + 1);
        tag.put(MARKET, market);
        tag.putInt(MARKET_VERSION, MARKET_SCHEMA);
        tag.putInt(MARKET_PURSE, Math.max(0,
            Math.min(MAX_PURSE_BOUND, purse)));
        tag.putInt(MARKET_REVISION, nextRevision);
        tag.remove(MARKET_LAST_SALE);
        tag.remove(MARKET_FAMILY_SALES);
        prepareOwnedSaleReceipts(trader);
    }

    /** A new quote/cap policy never refills an already-published visitor. */
    private static boolean retirePrePolicyMarket(WanderingTrader trader) {
        trader.getOffers().clear();
        CompoundTag tag = trader.getPersistentData();
        tag.putBoolean(MARKET_LEGACY_EMPTY, true);
        writeMarket(trader, List.of(), 0);
        return validOwnedMarket(trader);
    }

    /**
     * Version 1 had no aggregate visitor budget and may contain an old cheap
     * plank quote. Keep only rows that satisfy the current physical quote;
     * stale rows are retired rather than allowing a save to mint discounted
     * Coins or leaving an offer without a saved market row.
     */
    private static boolean migrateVersionOneMarket(WanderingTrader trader) {
        CompoundTag tag = trader.getPersistentData();
        if (tag.getInt(MARKET_VERSION) != LEGACY_MARKET_SCHEMA
            || !tag.contains(MARKET, Tag.TAG_COMPOUND)) return false;
        trader.getOffers().removeIf(offer -> !isPurchase(offer));
        List<MarketRow> rows = legacyRows(trader);
        if (rows.isEmpty()) tag.putBoolean(MARKET_LEGACY_EMPTY, true);
        else tag.remove(MARKET_LEGACY_EMPTY);
        writeMarket(trader, rows, boundedRemainingPurse(trader));
        return validOwnedMarket(trader);
    }

    private static int boundedRemainingPurse(WanderingTrader trader) {
        int remaining = 0;
        for (MerchantOffer offer : trader.getOffers()) {
            if (!isPurchase(offer)) continue;
            remaining = Math.min(MAX_PURSE_BOUND,
                remaining + Math.max(0, offer.getMaxUses() - offer.getUses()));
        }
        return remaining;
    }

    private static void retireOwnedCoinRows(WanderingTrader trader) {
        for (MerchantOffer candidate : trader.getOffers()) {
            if (candidate.getResult().is(ModItems.GOLD_COIN.get())) candidate.setToOutOfStock();
        }
    }

    /** [quality] A row paying more Coins than the purse still holds closes, so the purse is a hard cap. */
    private static void retireUnaffordableRows(WanderingTrader trader, int purse) {
        for (MerchantOffer candidate : trader.getOffers()) {
            if (candidate.getResult().is(ModItems.GOLD_COIN.get())
                    && candidate.getResult().getCount() > purse) candidate.setToOutOfStock();
        }
    }

    /** Returns true exactly once when this visitor reaches its visible family surcharge. */
    private static boolean recordOwnedFamilySale(CompoundTag tag, Item item) {
        String family = demandFamily(item);
        CompoundTag sales = tag.getCompound(MARKET_FAMILY_SALES);
        int before = Math.max(0, Math.min(FAMILY_SALES_BEFORE_SURCHARGE, sales.getInt(family)));
        int after = Math.min(FAMILY_SALES_BEFORE_SURCHARGE, before + 1);
        if (after == before) return false;
        sales.putInt(family, after);
        tag.put(MARKET_FAMILY_SALES, sales);
        return after == FAMILY_SALES_BEFORE_SURCHARGE;
    }

    /** One +25% input step for all live rows in a family; it cannot stack. */
    private static void raiseRemainingFamilyQuotes(WanderingTrader trader, Item soldItem) {
        String family = demandFamily(soldItem);
        for (MerchantOffer candidate : trader.getOffers()) {
            if (candidate.isOutOfStock() || !isPurchase(candidate)
                    || !family.equals(demandFamily(candidate.getBaseCostA().getItem()))) continue;
            int current = candidate.getCostA().getCount();
            int raised = (current * 5 + 3) / 4;
            candidate.setSpecialPriceDiff(candidate.getSpecialPriceDiff() + raised - current);
        }
    }

    /** Upgrade old single-sale receipts before a menu can accept another payment. */
    private static void prepareOwnedSaleReceipts(WanderingTrader trader) {
        CompoundTag tag = trader.getPersistentData();
        int revision = tag.getInt(MARKET_REVISION);
        CompoundTag receipt = tag.getCompound(MARKET_LAST_SALE);
        if (receipt.getInt("Revision") == revision
                && receipt.contains("UsesByOffer", Tag.TAG_COMPOUND)) return;
        CompoundTag usesByOffer = new CompoundTag();
        for (int index = 0; index < trader.getOffers().size(); index++)
            usesByOffer.putInt(Integer.toString(index), trader.getOffers().get(index).getUses());
        receipt = new CompoundTag();
        receipt.putInt("Revision", revision);
        receipt.put("UsesByOffer", usesByOffer);
        tag.put(MARKET_LAST_SALE, receipt);
    }

    /** Each row keeps its own high-water mark; interleaved callbacks cannot spend twice. */
    private static boolean isDuplicateOwnedSale(CompoundTag tag, MarketRow row, MerchantOffer offer) {
        int usesAfter = offer.getUses();
        if (usesAfter <= 0) return true;
        int revision = tag.getInt(MARKET_REVISION);
        CompoundTag receipt = tag.getCompound(MARKET_LAST_SALE);
        CompoundTag usesByOffer = receipt.getCompound("UsesByOffer");
        String key = Integer.toString(row.index);
        if (receipt.getInt("Revision") != revision || !usesByOffer.contains(key, Tag.TAG_INT)
                || usesAfter <= usesByOffer.getInt(key)) return true;
        usesByOffer.putInt(key, usesAfter);
        receipt.put("UsesByOffer", usesByOffer);
        tag.put(MARKET_LAST_SALE, receipt);
        return false;
    }

    @Nullable private static MarketRow ownedRow(WanderingTrader trader, int index, MerchantOffer offer) {
        if (!validOwnedMarket(trader) || index < 0 || index >= trader.getOffers().size()
            || trader.getOffers().get(index) != offer) return null;
        for (Tag raw : trader.getPersistentData().getCompound(MARKET).getList("Rows", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            if (row.getInt("Index") != index) continue;
            ResourceLocation key = ResourceLocation.tryParse(row.getString("Item"));
            Item item = key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
            int quality = row.getInt("Quality"), baseline = row.getInt("Baseline");
            if (item == Items.AIR || baseline < 0 || quality < GoodsQuality.BASIC
                || quality > GoodsQuality.HIGHEST || !isPurchase(offer)
                || !offer.getBaseCostA().is(item) || GoodsQuality.of(offer.getBaseCostA()) != quality
                || offer.getSpecialPriceDiff() < baseline) return null;
            return new MarketRow(index, item, quality, baseline);
        }
        return null;
    }

    private static boolean validOwnedMarket(WanderingTrader trader) {
        CompoundTag tag = trader.getPersistentData();
        if (tag.getInt(MARKET_VERSION) != MARKET_SCHEMA || !tag.contains(MARKET, Tag.TAG_COMPOUND)
            || !tag.contains(MARKET_PURSE, Tag.TAG_INT)) return false;
        int purse = tag.getInt(MARKET_PURSE);
        if (purse < 0 || purse > MAX_PURSE_BOUND) return false;
        ListTag rows = tag.getCompound(MARKET).getList("Rows", Tag.TAG_COMPOUND);
        // A persisted empty legacy marker means that migration deliberately
        // removed every non-Coin row. It is valid only when the live offer
        // list is empty too. Otherwise an old trader with only Emerald rows
        // would look migrated forever and never reach the one safe removal.
        if (rows.isEmpty()) {
            return tag.getBoolean(MARKET_LEGACY_EMPTY) && purse == 0 && trader.getOffers().isEmpty();
        }
        if (tag.getBoolean(MARKET_LEGACY_EMPTY) || rows.size() > MAX_MARKET_ROWS
            || rows.size() != trader.getOffers().size()) return false;
        boolean[] covered = new boolean[trader.getOffers().size()];
        for (Tag raw : rows) {
            if (!(raw instanceof CompoundTag row) || !row.contains("Index", Tag.TAG_INT)
                || !row.contains("Item", Tag.TAG_STRING) || !row.contains("Quality", Tag.TAG_INT)
                || !row.contains("Baseline", Tag.TAG_INT)) return false;
            int index = row.getInt("Index");
            if (index < 0 || index >= trader.getOffers().size() || covered[index]) return false;
            ResourceLocation key = ResourceLocation.tryParse(row.getString("Item"));
            Item item = key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
            int quality = row.getInt("Quality"), baseline = row.getInt("Baseline");
            MerchantOffer offer = trader.getOffers().get(index);
            if (item == Items.AIR || baseline < 0 || quality < GoodsQuality.BASIC
                || quality > GoodsQuality.HIGHEST || !isPurchase(offer)
                || !offer.getBaseCostA().is(item)
                || GoodsQuality.of(offer.getBaseCostA()) != quality
                || offer.getSpecialPriceDiff() < baseline) return false;
            covered[index] = true;
        }
        for (boolean rowPresent : covered) if (!rowPresent) return false;
        return true;
    }

    private static Item preferredWood(WanderingTrader trader, @Nullable ServerPlayer player,
                                      List<ItemStack> stock) {
        Item selected = selectedWood(trader);
        if (selected != null) return selected;
        if (player != null) {
            if (isWood(player.getMainHandItem().getItem())) return player.getMainHandItem().getItem();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                Item item = player.getInventory().getItem(slot).getItem();
                if (isWood(item)) return item;
            }
        }
        for (ItemStack stack : stock) if (isWood(stack.getItem())) return stack.getItem();
        return Items.OAK_LOG;
    }

    private static List<ResourceLocation> productiveCandidates(Settlement settlement, List<ItemStack> stock,
                                                                 Item preferredWood) {
        java.util.LinkedHashSet<ResourceLocation> result = new java.util.LinkedHashSet<>();
        addFamilyCandidate(result, id(preferredWood));
        addFamilyCandidate(result, id(Items.WHEAT));
        for (var building : settlement.buildings) {
            if (building == null || !building.valid) continue;
            switch (building.type) {
                case LUMBER_CAMP -> addFamilyCandidate(result, id(Items.OAK_LOG));
                case FARMHOUSE -> addFamilyCandidate(result, id(Items.WHEAT));
                case FISHERY -> {
                    Item fish = stock.stream().filter(stack -> isFish(stack.getItem())
                        && supportsFishQuality(stack.getItem(), GoodsQuality.of(stack)))
                        .map(ItemStack::getItem).findFirst().orElse(ModItems.BROWN_TROUT.get());
                    addFamilyCandidate(result, id(fish));
                }
                default -> { }
            }
        }
        for (ItemStack stack : stock) if (!stack.isEmpty() && stack.getItem() != Items.OAK_PLANKS
            && MerchantDemandSavedData.supports(id(stack.getItem()))) addFamilyCandidate(result, id(stack.getItem()));
        if (result.isEmpty()) addFamilyCandidate(result, id(Items.OAK_LOG));
        return List.copyOf(result);
    }

    private static void addFamilyCandidate(java.util.LinkedHashSet<ResourceLocation> result,
                                           ResourceLocation candidate) {
        if (!MerchantDemandSavedData.supports(candidate)) return;
        Item item = BuiltInRegistries.ITEM.get(candidate);
        String family = demandFamily(item);
        for (ResourceLocation existing : result)
            if (family.equals(demandFamily(BuiltInRegistries.ITEM.get(existing)))) return;
        result.add(candidate);
    }

    /** Crafted goods on hand first (most units first), then a rotation. */
    private static List<Item> craftedCandidates(@Nullable ServerPlayer player, List<ItemStack> stock,
                                                long sequence) {
        java.util.Map<Item, Integer> held = new java.util.LinkedHashMap<>();
        for (ItemStack stack : stock) {
            if (!stack.isEmpty() && isCraftedGood(stack.getItem()))
                held.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        if (player != null) for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && isCraftedGood(stack.getItem()))
                held.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        List<Item> out = new ArrayList<>();
        // [quality] one sale of an unstackable good (a sword) is ONE item --
        // vanilla clamps the cost to the stack size -- so a single sword on
        // hand is enough to be worth a row.
        held.entrySet().stream()
            .filter(e -> e.getValue() >= effectiveQuote(e.getKey()))
            .sorted((a, b) -> Integer.compare(b.getValue() / effectiveQuote(b.getKey()),
                a.getValue() / effectiveQuote(a.getKey())))
            .limit(CRAFTED_ROWS_PER_VISIT).forEach(e -> out.add(e.getKey()));
        List<Item> rotation = new ArrayList<>(CRAFTED.keySet());
        int start = (int) Math.floorMod(sequence * CRAFTED_ROWS_PER_VISIT, (long) rotation.size());
        for (int i = 0; i < rotation.size() && out.size() < CRAFTED_ROWS_PER_VISIT; i++) {
            Item next = rotation.get((start + i) % rotation.size());
            if (!out.contains(next)) out.add(next);
        }
        return out;
    }

    private static boolean isFish(Item item) {
        return item == Items.COD || item == Items.SALMON
            || item == ModItems.BROWN_TROUT.get() || item == ModItems.SILVER_PIKE.get()
            || item == ModItems.GOLDEN_CHAR.get();
    }

    private static boolean supportsFishQuality(Item item, int quality) {
        if (item == ModItems.BROWN_TROUT.get()) return quality == GoodsQuality.FINE;
        if (item == ModItems.SILVER_PIKE.get()) return quality == GoodsQuality.SUPERIOR;
        if (item == ModItems.GOLDEN_CHAR.get())
            return quality >= GoodsQuality.EXCEPTIONAL && quality <= GoodsQuality.HIGHEST;
        return (item == Items.COD || item == Items.SALMON)
            && quality >= GoodsQuality.FINE && quality <= GoodsQuality.HIGHEST;
    }

    private static boolean isFieldGood(Item item) {
        return item == Items.WHEAT || item == Items.CARROT || item == Items.POTATO
            || item == Items.BEETROOT || item == Items.PUMPKIN;
    }

    private static String demandFamily(Item item) {
        if (isWood(item) || item == Items.OAK_PLANKS) return "Timber";
        if (isFish(item)) return "Fish";
        if (isCraftedGood(item)) return "Crafted";
        return isFieldGood(item) ? "Field" : "Specialty";
    }

    private static ResourceLocation id(Item item) { return BuiltInRegistries.ITEM.getKey(item); }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!event.getLevel().isClientSide && event.getTarget() instanceof WanderingTrader trader
            && event.getEntity() instanceof ServerPlayer player) {
            if (!EarlyCoinMerchant.availableForTrade(trader)) {
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
                event.setCanceled(true);
                return;
            }
            if (trader.getPersistentData().hasUUID(OWNER)) {
                // A second player must not shift indices in an already open menu.
                if (trader.getTradingPlayer() != null) return;
                ServerLevel level = (ServerLevel) trader.level();
                Settlement settlement = SettlementManager.byId(level,
                    trader.getPersistentData().getUUID(OWNER));
                if (settlement != null) ensureOwnedMarket(level, settlement, trader, player, List.of());
                return;
            }
            // Same rule as the settlement merchant: a second player must not
            // add/remove rows under a menu another player already has open.
            if (trader.getTradingPlayer() != null) return;
            if (!ensureBasics(trader)) return;
            Item selected = selectedWood(trader);
            Item wood = selected;
            ItemStack held = player.getMainHandItem();
            // Existing merchants only retarget from an explicit held wood; arbitrary inventory never changes a selected offer.
            if (selected != null && isWood(held.getItem()) && !held.isEmpty()) wood = held.getItem();
            else if (selected == null) for (int slot=0;slot<player.getInventory().getContainerSize();slot++) {
                ItemStack stack=player.getInventory().getItem(slot);
                if (isWood(stack.getItem()) && !stack.isEmpty()) { wood=stack.getItem(); break; }
            }
            if (wood == null) player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "Bring timber to offer a wood shipment."), false);
            else ensurePurchases(trader, wood);
            Item activeWood = selectedWood(trader);
            for (int slot=0;slot<player.getInventory().getContainerSize();slot++) {
                ItemStack stack=player.getInventory().getItem(slot);
                int quality=GoodsQuality.of(stack);
                if (((activeWood != null && stack.is(activeWood)) || stack.is(Items.WHEAT)) && quality>=GoodsQuality.SUPERIOR)
                    addEarnedTier(trader,stack.getItem(),quality,activeWood);
            }
        }
    }
    private static boolean isWood(Item item) {
        return item==Items.OAK_LOG || item==Items.SPRUCE_LOG || item==Items.BIRCH_LOG
            || item==Items.JUNGLE_LOG || item==Items.ACACIA_LOG || item==Items.DARK_OAK_LOG
            || item==Items.MANGROVE_LOG || item==Items.CHERRY_LOG;
    }

    private static Item selectedWood(WanderingTrader trader) {
        var id=net.minecraft.resources.ResourceLocation.tryParse(
            trader.getPersistentData().getString("HearthsteadCoinWood"));
        Item item=id==null ? Items.AIR : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        return isWood(item) ? item : null;
    }

    /** Explicit fixture/default adapter; actual player interaction selects carried local wood. */
    public static void ensurePurchases(WanderingTrader trader) { ensurePurchases(trader,Items.OAK_LOG); }

    private static boolean ensureBasics(WanderingTrader trader) {
        if (trader.level().isClientSide) return false;
        // Retire only the recognised old eight-use Basic shape in place. A
        // current Basic row has four real sales and remains valid until its
        // vanilla stock is exhausted.
        for (MerchantOffer offer : trader.getOffers()) {
            if (isCompletedBasicPurchase(offer) && !offer.isOutOfStock()) {
                offer.setToOutOfStock();
            } else if (isBasicCoinOffer(offer) && !isPurchase(offer)
                    && !isCurrentLegacyBasicOffer(offer)) {
                // Do not expose a stale Basic quote from a pre-policy save.
                // The one recognised legacy shape below retains its first
                // honest sale and then follows the normal one-use rule.
                offer.setToOutOfStock();
            }
        }
        var tag=trader.getPersistentData();
        if (tag.contains(INSTALLED)) {
            return tag.contains(INSTALLED,net.minecraft.nbt.Tag.TAG_BYTE)
                && tag.getByte(INSTALLED)==1
                && hasOffer(trader,Items.WHEAT,GoodsQuality.BASIC)
                && hasOffer(trader,Items.WHEAT,GoodsQuality.FINE)
                && hasOffer(trader,Items.IRON_INGOT,GoodsQuality.BASIC);
        }
        // Unexplained existing Coin offers are not fresh stock to adopt/refill.
        if (trader.getOffers().stream().anyMatch(o -> o.getResult().is(ModItems.GOLD_COIN.get()))) return false;
        addOnce(trader,Items.WHEAT,GoodsQuality.BASIC);
        addOnce(trader,Items.WHEAT,GoodsQuality.FINE);
        addOnce(trader,Items.IRON_INGOT,GoodsQuality.BASIC);
        tag.putBoolean(INSTALLED,true);
        return true;
    }

    public static void ensurePurchases(WanderingTrader trader, Item wood) {
        if (!isWood(wood) || !ensureBasics(trader)) return;
        var tag=trader.getPersistentData();
        Item selected = selectedWood(trader);
        if (selected != null && selected != wood) {
            if (!retargetUnspentWoodRows(trader, selected)) return;
            tag.remove("HearthsteadCoinWood");
        }
        if (tag.contains("HearthsteadCoinWood")) return;
        if (trader.getOffers().stream().anyMatch(o -> o.getResult().is(ModItems.GOLD_COIN.get())
            && isWood(o.getBaseCostA().getItem()))) return;
        addOnce(trader,wood,GoodsQuality.BASIC);
        addOnce(trader,wood,GoodsQuality.FINE);
        tag.putString("HearthsteadCoinWood",
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(wood).toString());
    }

    private static boolean retargetUnspentWoodRows(WanderingTrader trader, Item selected) {
        var rows = trader.getOffers();
        boolean hasSelected = false;
        for (MerchantOffer offer : rows) {
            if (!offer.getResult().is(ModItems.GOLD_COIN.get()) || !offer.getBaseCostA().is(selected)) continue;
            hasSelected = true;
            if (offer.getUses() != 0 || offer.getSpecialPriceDiff() != 0) return false;
        }
        if (!hasSelected) return false;
        rows.removeIf(offer -> offer.getResult().is(ModItems.GOLD_COIN.get())
            && offer.getBaseCostA().is(selected) && offer.getUses() == 0 && offer.getSpecialPriceDiff() == 0);
        var issued = trader.getPersistentData().getCompound("HearthsteadCoinTiers");
        for (int tier = GoodsQuality.FINE; tier <= GoodsQuality.HIGHEST; tier++) issued.remove("wood" + tier);
        if (issued.isEmpty()) trader.getPersistentData().remove("HearthsteadCoinTiers");
        else trader.getPersistentData().put("HearthsteadCoinTiers", issued);
        return true;
    }
    private static void addEarnedTier(WanderingTrader trader, Item item, int tier, Item wood) {
        if (tier<GoodsQuality.SUPERIOR || tier>GoodsQuality.HIGHEST) return;
        if (item!=Items.WHEAT && (wood==null || item!=wood
            || !hasOffer(trader,wood,GoodsQuality.BASIC) || !hasOffer(trader,wood,GoodsQuality.FINE))) return;
        var tag=trader.getPersistentData();
        if (tag.contains("HearthsteadCoinTiers") && !tag.contains("HearthsteadCoinTiers",net.minecraft.nbt.Tag.TAG_COMPOUND)) return;
        var issued=tag.getCompound("HearthsteadCoinTiers");
        String key=(item==Items.WHEAT ? "wheat" : "wood")+tier;
        if (issued.contains(key)) return; // No missing-row repair, including malformed issued flags.
        addOnce(trader,item,tier);
        issued.putBoolean(key,true); tag.put("HearthsteadCoinTiers",issued);
    }

    private static boolean hasOffer(WanderingTrader trader, Item item, int quality) {
        return trader.getOffers().stream().anyMatch(offer -> offer.getResult().is(ModItems.GOLD_COIN.get())
            && offer.getBaseCostA().is(item) && GoodsQuality.of(offer.getBaseCostA())==quality);
    }

    private static void addOnce(WanderingTrader trader, Item item, int quality) {
        var offers=trader.getOffers();
        boolean exists=offers.stream().anyMatch(offer -> offer.getResult().is(ModItems.GOLD_COIN.get())
            && offer.getBaseCostA().is(item) && GoodsQuality.of(offer.getBaseCostA())==quality);
        if (exists) return;
        MerchantOffer added = purchase(item, quality);
        if (!trader.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement")) {
            offers.add(added);
            return;
        }
        // Insert only the newly issued row. Existing offer objects, relative
        // order, uses and price pressure survive reopening and saved reloads.
        int insertion = 0;
        int order = coinDisplayOrder(item, quality);
        for (int index = 0; index < offers.size(); index++) {
            MerchantOffer current = offers.get(index);
            if (!current.getResult().is(ModItems.GOLD_COIN.get())) continue;
            if (coinDisplayOrder(current.getBaseCostA().getItem(),
                    GoodsQuality.of(current.getBaseCostA())) > order) break;
            insertion = index + 1;
        }
        offers.add(insertion, added);
    }

    private static int coinDisplayOrder(Item item, int quality) {
        return (isWood(item) ? 0 : item == Items.WHEAT ? 4 : 8) + quality;
    }

    @SubscribeEvent
    public static void onSale(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager() instanceof WanderingTrader trader)
                || !(event.getEntity() instanceof ServerPlayer player)) return;
        MerchantOffer offer = event.getMerchantOffer();
        int index = trader.getOffers().indexOf(offer);
        if (trader.getPersistentData().hasUUID(OWNER)) {
            if (!isPurchase(trader, index, offer)) return;
            finishOwnedPurchase((ServerLevel) trader.level(), trader, index, offer);
            com.hearthstead.fx.FxHooks.coinSale(trader);
            return;
        } else {
            // A recognised pre-policy Basic quote gets one honest sale, then
            // the normal retirement rule closes its old eight-use budget.
            if (!isPurchase(offer) && !isCurrentLegacyBasicOffer(offer)) return;
            finishPurchase(offer);
            com.hearthstead.fx.FxHooks.coinSale(trader);
        }
        if (trader.getTradingPlayer() == player
                && player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu) {
            player.sendMerchantOffers(player.containerMenu.containerId, trader.getOffers(),
                1, trader.getVillagerXp(), false, false);
        }
    }

    /** Shared post-sale policy. Caller already consumed exactly one offer use. */
    public static void finishPurchase(MerchantOffer offer) {
        boolean legacyBasic = isCurrentLegacyBasicOffer(offer);
        if (!isPurchase(offer) && !legacyBasic) return;
        // Wandering traders never restock. Use vanilla's persisted price adjustment
        // for pressure within this merchant's finite budget: +1 good per two sales.
        if (legacyBasic) {
            // Retire an unused legacy eight-use offer on its first honest sale.
            offer.setToOutOfStock();
        } else {
            offer.setSpecialPriceDiff(offer.getUses() / 2);
        }
    }

    /** Explicit local stock adapter; never resets uses or chooses unrelated wood. */
    public static void ensurePurchasesForStock(WanderingTrader trader, java.util.List<ItemStack> stock) {
        if (!ensureBasics(trader)) return;
        Item wood = selectedWood(trader);
        if (wood == null) {
            for (ItemStack stack : stock) if (!stack.isEmpty() && isWood(stack.getItem())) {
                wood = stack.getItem(); break;
            }
            if (wood != null) ensurePurchases(trader, wood);
        }
        for (ItemStack stack : stock) if (!stack.isEmpty())
            addEarnedTier(trader, stack.getItem(), GoodsQuality.of(stack), wood);
    }

    /** Client visibility only; server offer indices and spent receipts stay stable. */
    public static boolean isCompletedBasicPurchase(MerchantOffer offer) {
        return isCurrentLegacyBasicOffer(offer) && offer.getUses() > 0;
    }

    /**
     * The former Basic quote had eight uses. It is recognised only while its
     * physical cost is still today's Basic quote, so an old cheap plank row
     * cannot survive a price migration as a Coin source.
     */
    private static boolean isCurrentLegacyBasicOffer(MerchantOffer offer) {
        if (!isBasicCoinOffer(offer) || offer.getMaxUses() != LEGACY_BASIC_MAX_USES) return false;
        return offer.getBaseCostA().getCount()
            == purchase(offer.getBaseCostA().getItem(), GoodsQuality.BASIC).getBaseCostA().getCount();
    }

    private static boolean isBasicCoinOffer(MerchantOffer offer) {
        if (offer == null || !offer.getResult().is(ModItems.GOLD_COIN.get())
                || offer.getResult().getCount() != 1 || !offer.getCostB().isEmpty()
                || GoodsQuality.of(offer.getBaseCostA()) != GoodsQuality.BASIC) return false;
        for (Item item : GOODS) if (offer.getBaseCostA().is(item)) return true;
        return false;
    }

    public static boolean isPurchase(MerchantOffer offer) {
        if (!offer.getResult().is(ModItems.GOLD_COIN.get()) || !offer.getCostB().isEmpty()) return false;
        Item offeredItem = offer.getBaseCostA().getItem();
        if (isCraftedGood(offeredItem) && GoodsQuality.of(offer.getBaseCostA()) > GoodsQuality.BASIC) {
            // [quality] graded crafted row: exactly today's quote, payout and budget.
            MerchantOffer expected = purchase(offeredItem, GoodsQuality.of(offer.getBaseCostA()));
            return offer.getMaxUses() == expected.getMaxUses()
                && offer.getBaseCostA().getCount() == expected.getBaseCostA().getCount()
                && offer.getResult().getCount() == expected.getResult().getCount();
        }
        if (offer.getResult().getCount() != 1) return false;
        if (isFish(offeredItem)) {
            int tier = GoodsQuality.of(offer.getBaseCostA());
            return supportsFishQuality(offeredItem, tier)
                && offer.getMaxUses() == maxUsesForQuality(tier)
                && offer.getBaseCostA().getCount() == purchase(offeredItem, tier).getBaseCostA().getCount();
        }
        if (isCraftedGood(offeredItem)) {
            return GoodsQuality.of(offer.getBaseCostA()) == GoodsQuality.BASIC
                && offer.getMaxUses() == MAX_USES
                && offer.getBaseCostA().getCount() == craftedQuote(offeredItem);
        }
        for (Item item : GOODS) if (offer.getBaseCostA().is(item)) {
            int tier = GoodsQuality.of(offer.getBaseCostA());
            boolean supportedBudget = offer.getMaxUses() == maxUsesForQuality(tier);
            return supportedBudget
                && offer.getBaseCostA().getCount() == purchase(item, tier).getBaseCostA().getCount();
        }
        return false;
    }
}
