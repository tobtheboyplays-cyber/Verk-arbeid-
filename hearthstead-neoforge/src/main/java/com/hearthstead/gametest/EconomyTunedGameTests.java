package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierHearthBagSession;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.economy.EconomyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.UUID;

/**
 * The [economy] TUNED mode -- what players actually run (plan/ECONOMY.md).
 *
 * <p>Every economy lever is neutral on the GameTest server so the rest of the
 * suite keeps measuring the recipe table; these tests switch the tuning on
 * for their own batches ({@link EconomyConfig#testOverride}, set in
 * {@code @BeforeBatch} and cleared in {@code @AfterBatch}) and pin the
 * exact-once invariants that only exist when a courier moves more than one
 * item per motion, or the merchant's purse grows:
 *
 * <ol>
 *   <li>Hearth lift, n = 4: Hearth + bag + Warehouse is constant every tick,
 *       and more than one item really moves per lift cycle.</li>
 *   <li>Hearth lift receipt replayed after a "crash" between extract and ack:
 *       the recovery acknowledges, never extracts a second time.</li>
 *   <li>Stow bundle of 4 into a chest that fills mid-bundle: nothing is lost
 *       or duplicated; the chest ends exactly full.</li>
 *   <li>Crafter self-fetch: a sawyer with an empty bench and no courier
 *       walks to the Warehouse, brings logs back and saws them; logs are
 *       conserved every tick across Warehouse, bag, bench and output.</li>
 *   <li>Merchant: a crafted-goods row pays exactly once per sale (replayed
 *       callbacks debit once), and the purse starts at, and never exceeds,
 *       its cap.</li>
 * </ol>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class EconomyTunedGameTests {

    private static final String LIFT = "economy_tuned_lift";
    private static final String REPLAY = "economy_tuned_replay";
    private static final String STOW = "economy_tuned_stow";
    private static final String MERCHANT = "economy_tuned_merchant";
    private static final String FETCH = "economy_tuned_fetch";

    @BeforeBatch(batch = LIFT)
    public static void tuneLift(ServerLevel level) { EconomyConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = LIFT)
    public static void untuneLift(ServerLevel level) { EconomyConfig.testOverride = null; }
    @BeforeBatch(batch = REPLAY)
    public static void tuneReplay(ServerLevel level) { EconomyConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = REPLAY)
    public static void untuneReplay(ServerLevel level) { EconomyConfig.testOverride = null; }
    @BeforeBatch(batch = STOW)
    public static void tuneStow(ServerLevel level) { EconomyConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = STOW)
    public static void untuneStow(ServerLevel level) { EconomyConfig.testOverride = null; }
    @BeforeBatch(batch = FETCH)
    public static void tuneFetch(ServerLevel level) { EconomyConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = FETCH)
    public static void untuneFetch(ServerLevel level) { EconomyConfig.testOverride = null; }
    @BeforeBatch(batch = MERCHANT)
    public static void tuneMerchant(ServerLevel level) { EconomyConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = MERCHANT)
    public static void untuneMerchant(ServerLevel level) { EconomyConfig.testOverride = null; }

    // ------------------------------------------------------------ fixture --

    private static void arena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), rim && y <= 2
                        ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var arena = helper.getBounds();
        data.settlements.values().removeIf(old -> arena.contains(old.center.getX() + 0.5,
            old.center.getY() + 0.5, old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Tunedholm", helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static HearthBlockEntity hearth(GameTestHelper helper, BlockPos rel, Settlement s) {
        helper.setBlock(rel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        hearth.bindSettlement(s.id);
        return hearth;
    }

    private static Building warehouse(GameTestHelper helper, Settlement s, BlockPos minRel, BlockPos maxRel) {
        helper.setBlock(minRel, ModBlocks.PLAQUE.get());
        BoundingBox bounds = BoundingBox.fromCorners(helper.absolutePos(minRel), helper.absolutePos(maxRel));
        Building b = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(minRel), helper.absolutePos(minRel), bounds);
        b.valid = true;
        s.buildings.add(b);
        return b;
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Tally");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settler.assignProfession(Profession.COURIER);
        return settler;
    }

    private static int count(Container c, Item item) {
        int n = 0;
        if (c == null) return 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    private static int count(ItemStackHandler h, Item item) {
        int n = 0;
        for (int i = 0; i < h.getSlots(); i++) if (h.getStackInSlot(i).is(item)) n += h.getStackInSlot(i).getCount();
        return n;
    }

    private static int onGround(GameTestHelper helper, Item item) {
        int n = 0;
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(24);
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
            if (e.getItem().is(item)) n += e.getItem().getCount();
        }
        return n;
    }

    // --------------------------------------------------------- 1. lift n=4 --

    /**
     * A courier consolidating 16 string from the Hearth to the Warehouse with
     * the tuned bundle: every tick the three places (plus the floor) hold
     * exactly 16, the bag at some tick grows by more than one item in a
     * single tick (the bundle is real), and all 16 end in the chest.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = LIFT)
    public void tunedHearthLiftMovesBundlesAndConservesEveryTick(GameTestHelper helper) {
        EconomyConfig.testOverride = Boolean.TRUE;
        helper.getLevel().setDayTime(2000);
        arena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = hearth(helper, hearthRel, s);
        hearth.insertGoods(new ItemStack(Items.STRING, 16));
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        warehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        SettlerEntity tally = courier(helper, s, new BlockPos(4, 1, 4));
        final int[] lastBag = {0};
        final boolean[] bundled = {false};
        helper.onEachTick(() -> {
            Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(10, 1, 10)));
            int bag = count(tally.bag, Items.STRING);
            int total = count(hearth.getInventory(), Items.STRING) + bag + count(chest, Items.STRING)
                + onGround(helper, Items.STRING);
            if (total != 16) {
                helper.fail("string must be conserved every tick: saw " + total + " (bag=" + bag + ")");
            }
            if (bag - lastBag[0] > 1) bundled[0] = true;
            lastBag[0] = bag;
        });
        helper.succeedWhen(() -> {
            Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(10, 1, 10)));
            helper.assertTrue(count(chest, Items.STRING) == 16,
                "all 16 string should reach the warehouse, saw " + count(chest, Items.STRING));
            helper.assertTrue(bundled[0], "the tuned Hearth lift must move more than one item in one cycle");
        });
    }

    // ------------------------------------------------------- 2. replayed lift --

    /**
     * Drives the Hearth session directly with n = 4. After the first real
     * lift, the persisted receipt is rewritten to the exact state a crash
     * between extract and ack would leave (Pending, Bag = before, AfterBag =
     * after, Clock 47), and a FRESH session object (an entity reload)
     * resumes it: it must acknowledge the lift it already made, not take
     * another four.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = REPLAY)
    public void tunedHearthLiftReceiptReplaysExactlyOnce(GameTestHelper helper) {
        EconomyConfig.testOverride = Boolean.TRUE;
        helper.getLevel().setDayTime(2000);
        arena(helper, 14);
        BlockPos hearthRel = new BlockPos(5, 1, 5);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = hearth(helper, hearthRel, s);
        hearth.insertGoods(new ItemStack(Items.STRING, 12));
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        Building w = warehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        SettlerEntity tally = courier(helper, s, new BlockPos(6, 1, 5));
        tally.setNoAi(true);
        ServerLevel level = helper.getLevel();
        CourierHearthBagSession session = new CourierHearthBagSession(tally);
        session.unitLimit(slot -> 4);
        BlockPos target = helper.absolutePos(new BlockPos(10, 1, 10));
        helper.assertTrue(session.begin(level, w.id, target, slot -> true), "the lift session must begin");
        CompoundTag t = tally.getPersistentData().getCompound("HearthsteadCourierHearthBag");
        BlockPos anchor = BlockPos.of(t.getLong("Anchor"));
        Vec3 stand = Vec3.atBottomCenterOf(anchor);
        tally.moveTo(stand.x, stand.y, stand.z, tally.getYRot(), 0);

        final CompoundTag[] beforeExtract = {null};
        final int[] phase = {0};
        final CourierHearthBagSession[] live = {session};
        helper.onEachTick(() -> {
            int hearthNow = count(hearth.getInventory(), Items.STRING);
            int bagNow = count(tally.bag, Items.STRING);
            if (hearthNow + bagNow != 12) helper.fail("hearth + bag must stay 12, saw " + (hearthNow + bagNow));
            tally.moveTo(stand.x, stand.y, stand.z, tally.getYRot(), 0);
            CompoundTag now = tally.getPersistentData().getCompound("HearthsteadCourierHearthBag");
            if (phase[0] == 0 && now.getInt("Clock") == 47) {
                beforeExtract[0] = now.copy();
            }
            live[0].tick(level, slot -> true);
            CompoundTag after = tally.getPersistentData().getCompound("HearthsteadCourierHearthBag");
            if (phase[0] == 0 && beforeExtract[0] != null && after.getBoolean("Ack")) {
                // The first lift just committed: 4 in the bag.
                helper.assertTrue(count(tally.bag, Items.STRING) == 4,
                    "the first tuned lift moves exactly 4, bag=" + count(tally.bag, Items.STRING));
                // Rewrite to the crash state: extracted, but the ack never saved.
                CompoundTag crash = after.copy();
                crash.putBoolean("Pending", true);
                crash.putBoolean("Ack", false);
                crash.putInt("Clock", 47);
                crash.put("Bag", beforeExtract[0].get("Bag").copy());
                tally.getPersistentData().put("HearthsteadCourierHearthBag", crash);
                CourierHearthBagSession reloaded = new CourierHearthBagSession(tally);
                reloaded.unitLimit(slot -> 4);
                live[0] = reloaded;
                phase[0] = 1;
            } else if (phase[0] == 1) {
                // One reload tick later: acknowledged, nothing taken twice.
                helper.assertTrue(count(tally.bag, Items.STRING) == 4
                        && count(hearth.getInventory(), Items.STRING) == 8,
                    "a replayed receipt must acknowledge, not extract again: bag="
                        + count(tally.bag, Items.STRING) + " hearth=" + count(hearth.getInventory(), Items.STRING));
                phase[0] = 2;
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(phase[0] == 2, "the replay check must run"));
    }

    // ---------------------------------------------------- 3. stow fills mid --

    /**
     * The only warehouse chest has room for exactly 3 more string (a stack
     * of 61 plus every other slot full of cobblestone). The courier arrives
     * with a bag of string and stows with a bundle of 4: the chest must end
     * at exactly 64, and string must be conserved every tick.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = STOW)
    public void tunedStowBundleStopsExactlyAtAFullChest(GameTestHelper helper) {
        EconomyConfig.testOverride = Boolean.TRUE;
        helper.getLevel().setDayTime(2000);
        arena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        Settlement s = settlement(helper, hearthRel);
        HearthBlockEntity hearth = hearth(helper, hearthRel, s);
        hearth.insertGoods(new ItemStack(Items.STRING, 8));
        BlockPos chestRel = new BlockPos(10, 1, 10);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.STRING, 61));
        for (int i = 1; i < chest.getContainerSize(); i++) chest.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        warehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        SettlerEntity tally = courier(helper, s, new BlockPos(4, 1, 4));
        final int total = 69;
        helper.onEachTick(() -> {
            int now = count(hearth.getInventory(), Items.STRING) + count(tally.bag, Items.STRING)
                + count(chest, Items.STRING) + onGround(helper, Items.STRING);
            if (now != total) helper.fail("string must be conserved every tick: saw " + now);
            if (count(chest, Items.STRING) > 64) helper.fail("the chest can never hold more than one full stack");
        });
        helper.succeedWhen(() -> helper.assertTrue(count(chest, Items.STRING) == 64,
            "the chest should end exactly full (64), saw " + count(chest, Items.STRING)));
    }

    // ----------------------------------------------------------- 4. merchant --

    /**
     * 100 settlers: purse = 12 + 2 x 20 = 52, capped at 40. The market shows
     * crafted rows for what the village holds; each bread sale pays one Coin
     * exactly once even when the completion callback is replayed; the purse
     * never rises above 40 and falls by exactly one per paid sale.
     */
    @GameTest(template = "empty16", timeoutTicks = 40, batch = MERCHANT)
    public void tunedMerchantBuysCraftedGoodsOnceAndCapsThePurse(GameTestHelper helper) {
        EconomyConfig.testOverride = Boolean.TRUE;
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Market",
            helper.absolutePos(new BlockPos(8, 2, 8)));
        for (int i = 0; i < 100; i++) settlement.putRecord(UUID.randomUUID(), "S" + i, Profession.NONE);
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
        trader.getPersistentData().putLong("HearthsteadMerchantExpires",
            helper.getLevel().getGameTime() + EarlyCoinMerchant.VISIT_TICKS);
        helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(trader, settlement.id),
            "a fresh owned visitor clears its vanilla rows first");
        List<ItemStack> stock = List.of(new ItemStack(Items.BREAD, 64), new ItemStack(Items.LEATHER, 30),
            new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.WHEAT, 64));
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, stock),
            "the owned market publishes");
        int cap = EconomyConfig.DEFAULT_MERCHANT_PURSE_CAP;
        helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(trader) == cap,
            "100 settlers must hit the purse cap " + cap + ", saw " + GoldCoinTrades.remainingOwnedPurse(trader));
        MerchantOffer bread = null;
        for (MerchantOffer offer : trader.getOffers()) if (offer.getBaseCostA().is(Items.BREAD)) bread = offer;
        helper.assertTrue(bread != null, "the merchant must offer to buy the village's bread");
        helper.assertTrue(bread.getCostA().getCount() == 4 && bread.getResult().is(ModItems.GOLD_COIN.get()),
            "bread sells at 4 per Coin");
        var player = helper.makeMockServerPlayerInLevel();
        trader.setTradingPlayer(player);
        ItemStack payment = new ItemStack(Items.BREAD, 64);
        int index = trader.getOffers().indexOf(bread);
        int coins = 0;
        for (int sale = 0; sale < GoldCoinTrades.MAX_USES; sale++) {
            int purseBefore = GoldCoinTrades.remainingOwnedPurse(trader);
            helper.assertTrue(bread.take(payment, ItemStack.EMPTY), "each sale consumes real bread");
            ItemStack result = bread.assemble();
            helper.assertTrue(result.is(ModItems.GOLD_COIN.get()) && result.getCount() == 1, "one Coin per sale");
            coins += result.getCount();
            trader.notifyTrade(bread);
            // A replayed completion callback must not debit twice.
            GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), trader, index, bread);
            GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), trader, index, bread);
            int purseAfter = GoldCoinTrades.remainingOwnedPurse(trader);
            helper.assertTrue(purseAfter == purseBefore - 1 && purseAfter <= cap,
                "each paid sale debits the purse exactly once: " + purseBefore + " -> " + purseAfter);
        }
        trader.setTradingPlayer(null);
        helper.assertTrue(coins == GoldCoinTrades.MAX_USES && payment.getCount() == 64 - 4 * GoldCoinTrades.MAX_USES
                && bread.isOutOfStock(),
            "four sales: " + coins + " Coins for " + (64 - payment.getCount()) + " bread, row closed");
        helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(trader) == cap - GoldCoinTrades.MAX_USES,
            "purse = cap - sales");
        EconomyConfig.testOverride = null;
        helper.succeed();
    }

    // --------------------------------------------------------- 5. self-fetch --

    @GameTest(template = "empty16", timeoutTicks = 3000, batch = FETCH)
    public void tunedCrafterFetchesItsOwnInputsAndConservesThem(GameTestHelper helper) {
        EconomyConfig.testOverride = Boolean.TRUE;
        arena(helper, 16);
        Settlement s = settlement(helper, new BlockPos(8, 1, 8));
        Building sawmill = GameTestFixtures.register(helper, s, BuildingType.SAWMILL, 2, 2);
        BlockPos benchRel = new BlockPos(3, 1, 2);
        helper.setBlock(benchRel, Blocks.CHEST);
        Container bench = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(benchRel));
        Building store = GameTestFixtures.register(helper, s, BuildingType.WAREHOUSE, 9, 9);
        BlockPos shelfRel = new BlockPos(10, 1, 10);
        helper.setBlock(shelfRel, Blocks.CHEST);
        Container shelf = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(shelfRel));
        shelf.setItem(0, new ItemStack(Items.OAK_LOG, 8));
        SettlerEntity saw = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 2));
        saw.setSettlerName("Sawny");
        saw.bindTo(s.id, s.center);
        s.putRecord(saw.getUUID(), "Sawny", Profession.NONE);
        helper.assertTrue(com.hearthstead.settlement.Employment.hire(helper.getLevel(), s, sawmill, saw).ok(),
            "a sawmill must take a sawyer");
        helper.getLevel().setDayTime(2000);
        final boolean[] carried = {false};
        helper.onEachTick(() -> {
            helper.getLevel().setDayTime(2000); // stay inside the morning work phase
            int planks = count(bench, Items.OAK_PLANKS);
            int beams = count(bench, ModItems.TIMBER_BEAM.get());
            int logs = count(shelf, Items.OAK_LOG) + count(bench, Items.OAK_LOG)
                + count(saw.bag, Items.OAK_LOG) + onGround(helper, Items.OAK_LOG);
            int consumed = planks / 6 + (beams / 2) * 3;
            if (logs + consumed != 8) {
                helper.fail("logs must be conserved: " + logs + " held + " + consumed + " sawn != 8");
            }
            if (saw.getActivity() == com.hearthstead.entity.SettlerActivity.CARRYING
                && count(saw.bag, Items.OAK_LOG) > 0) carried[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(carried[0], "the sawyer must visibly carry the logs home");
            helper.assertTrue(count(bench, Items.OAK_PLANKS) > 0 || count(bench, ModItems.TIMBER_BEAM.get()) > 0,
                "the fetched logs must be sawn at the bench");
        });
    }
}
