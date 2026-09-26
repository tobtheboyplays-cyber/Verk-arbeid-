package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.event.GoodsQualityTooltip;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.economy.EconomyConfig;
import com.hearthstead.settlement.economy.QualityConfig;
import com.hearthstead.settlement.work.CraftedQuality;
import com.hearthstead.settlement.work.GoodsQuality;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.UUID;

/**
 * Quality lane (plan/QUALITY.md): settler-crafted goods roll a grade, the
 * grade is real (durability, attack), survives courier moves unmerged, the
 * merchant pays the graded price exactly once, and an old component-less
 * item still behaves as a plain Basic good.
 *
 * <p>The crafted roll and the merchant's graded rows are OFF on the GameTest
 * server ({@link QualityConfig#testOverride}); these batches switch them on
 * for themselves, exactly like the economy_tuned_ batches.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class GoodsQualityCraftedGameTests {

    private static final String SMITH = "goods_quality_smith";
    private static final String COURIER = "goods_quality_courier";
    private static final String MERCHANT = "goods_quality_merchant";
    private static final String LEGACY = "goods_quality_legacy";

    @BeforeBatch(batch = SMITH)
    public static void onSmith(ServerLevel level) { QualityConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = SMITH)
    public static void offSmith(ServerLevel level) { QualityConfig.testOverride = null; }
    @BeforeBatch(batch = COURIER)
    public static void onCourier(ServerLevel level) {
        QualityConfig.testOverride = Boolean.TRUE;
        EconomyConfig.testOverride = Boolean.TRUE;
    }
    @AfterBatch(batch = COURIER)
    public static void offCourier(ServerLevel level) {
        QualityConfig.testOverride = null;
        EconomyConfig.testOverride = null;
    }
    @BeforeBatch(batch = MERCHANT)
    public static void onMerchant(ServerLevel level) {
        QualityConfig.testOverride = Boolean.TRUE;
        EconomyConfig.testOverride = Boolean.TRUE;
    }
    @AfterBatch(batch = MERCHANT)
    public static void offMerchant(ServerLevel level) {
        QualityConfig.testOverride = null;
        EconomyConfig.testOverride = null;
    }
    @BeforeBatch(batch = LEGACY)
    public static void onLegacy(ServerLevel level) { QualityConfig.testOverride = Boolean.TRUE; }
    @AfterBatch(batch = LEGACY)
    public static void offLegacy(ServerLevel level) { QualityConfig.testOverride = null; }

    // ------------------------------------------------------------ fixture --

    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel, String name) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var arena = helper.getBounds();
        data.settlements.values().removeIf(old -> arena.contains(old.center.getX() + 0.5,
            old.center.getY() + 0.5, old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

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

    private static boolean hasQualityAttack(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.getAttributeModifiers();
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE)
                    && entry.modifier().id().equals(Hearthstead.id("quality_attack"))
                    && entry.slot() == EquipmentSlotGroup.MAINHAND) return true;
        }
        return false;
    }

    private static double attack(ItemStack stack) {
        double total = 0.0D;
        for (ItemAttributeModifiers.Entry entry : stack.getAttributeModifiers().modifiers()) {
            if (entry.attribute().is(Attributes.ATTACK_DAMAGE) && entry.slot() == EquipmentSlotGroup.MAINHAND) {
                total += entry.modifier().amount();
            }
        }
        return total;
    }

    // ---------------------------------------------------- 1. master smith --

    /**
     * A level-10 smith (primary attribute 99) at a real Smithy forges an iron
     * sword through the same {@code Production.run(..., crafter)} call his
     * work goal makes. At that skill every roll is Superior or better
     * (CraftedQuality: mean 3.67, spread 1.5, Fine needs value &lt; 2). The
     * sword carries the grade, its max durability is the graded value, it
     * hits harder by exactly the grade's attack bonus, the two ingots are
     * gone and nothing else changed. A second batch by a level-1 smith of
     * the same Smithy is never Masterwork+.
     */
    @GameTest(template = "empty16", timeoutTicks = 60, batch = SMITH)
    public void goodsQualityMasterSmithForgesAGradedSword(GameTestHelper helper) {
        QualityConfig.testOverride = Boolean.TRUE;
        Settlement s = settlement(helper, new BlockPos(8, 1, 8), "Forgeholm");
        Building smithy = GameTestFixtures.register(helper, s, BuildingType.SMITHY, 2, 2);
        BlockPos chestRel = new BlockPos(3, 1, 3);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        // The Smithy burns fuel (Fuel.burns): one dense-fuel stack covers both batches.
        chest.setItem(1, new ItemStack(Items.CHARCOAL, 16));
        SettlerEntity smith = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(2, 1, 2));
        smith.setNoAi(true);
        smith.bindTo(s.id, s.center);
        s.putRecord(smith.getUUID(), "Hild", Profession.NONE);
        smith.setProfessionProjection(Profession.SMITH);
        smith.tradeSkills().set(Profession.SMITH, SkillLevels.MAX_XP);
        for (Attribute a : Attribute.values()) smith.attributes().pinForTest(a, 99);
        helper.assertTrue(SkillLevels.levelOf(smith) == SkillLevels.MAX_LEVEL, "the smith is a master");
        Production.Recipe sword = null;
        for (Production.Recipe r : Production.of(BuildingType.SMITHY)) if (r.id().equals("sword")) sword = r;
        helper.assertTrue(sword != null, "the smithy forges swords");
        helper.assertTrue(Production.run(helper.getLevel(), smithy, sword, smith), "the batch must run");
        ItemStack made = ItemStack.EMPTY;
        int ingots = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(Items.IRON_SWORD)) made = chest.getItem(i);
            if (chest.getItem(i).is(Items.IRON_INGOT)) ingots += chest.getItem(i).getCount();
        }
        helper.assertTrue(!made.isEmpty() && ingots == 2, "one sword made from exactly two ingots, ingots left " + ingots);
        int quality = GoodsQuality.of(made);
        helper.assertTrue(quality >= GoodsQuality.SUPERIOR,
            "a master smith forges Superior or better, got " + quality);
        helper.assertTrue(made.getMaxDamage() == CraftedQuality.bonusMaxDamage(250, quality)
                && made.getMaxDamage() > 250,
            "graded sword durability " + made.getMaxDamage() + " for grade " + quality);
        helper.assertTrue(hasQualityAttack(made), "a graded sword carries the quality attack modifier");
        double plain = attack(new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(Math.abs(attack(made) - plain - CraftedQuality.attackBonus(quality)) < 1.0E-6,
            "attack bonus: plain " + plain + ", graded " + attack(made));
        helper.assertTrue(GoodsQualityTooltip.shown(made), "the grade shows in the tooltip");
        // A level-1 smith at the same bench: never above Exceptional (gate).
        SettlerEntity novice = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(3, 1, 2));
        novice.setNoAi(true);
        novice.bindTo(s.id, s.center);
        s.putRecord(novice.getUUID(), "Ebba", Profession.NONE);
        novice.setProfessionProjection(Profession.SMITH);
        helper.assertTrue(Production.run(helper.getLevel(), smithy, sword, novice), "the novice batch runs");
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(Items.IRON_SWORD) && stack != made) {
                helper.assertTrue(GoodsQuality.of(stack) <= GoodsQuality.EXCEPTIONAL,
                    "a level-1 smith can never make Masterwork");
            }
        }
        QualityConfig.testOverride = null;
        helper.succeed();
    }

    // ------------------------------------------------ 2. courier, mixed --

    private static int count(Container c, int quality) {
        int n = 0;
        if (c == null) return 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.is(Items.ARROW) && GoodsQuality.of(s) == quality) n += s.getCount();
        }
        return n;
    }

    private static int count(ItemStackHandler h, int quality) {
        int n = 0;
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack s = h.getStackInSlot(i);
            if (s.is(Items.ARROW) && GoodsQuality.of(s) == quality) n += s.getCount();
        }
        return n;
    }

    private static int onGround(GameTestHelper helper, int quality) {
        int n = 0;
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(24);
        for (ItemEntity e : helper.getLevel().getEntitiesOfClass(ItemEntity.class, box)) {
            if (e.getItem().is(Items.ARROW) && GoodsQuality.of(e.getItem()) == quality) n += e.getItem().getCount();
        }
        return n;
    }

    private static ItemStack arrows(int count, int quality) {
        ItemStack stack = new ItemStack(Items.ARROW, count);
        return CraftedQuality.stamp(stack, quality);
    }

    /**
     * 5 Basic, 4 Fine and 3 Superior arrows at the Hearth. A real courier
     * consolidates them to the Warehouse chest with the tuned 4-item bundle.
     * Every tick, per grade, Hearth + bag + chest + floor holds exactly the
     * starting count (no loss, no grade change), and at the end the chest
     * holds 5 / 4 / 3 in stacks that never merged across grades.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = COURIER)
    public void goodsQualityCourierMovesMixedGradesWithoutMerging(GameTestHelper helper) {
        QualityConfig.testOverride = Boolean.TRUE;
        EconomyConfig.testOverride = Boolean.TRUE;
        helper.getLevel().setDayTime(2000);
        arena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        Settlement s = settlement(helper, hearthRel, "Quiverton");
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel));
        hearth.bindSettlement(s.id);
        hearth.insertGoods(arrows(5, GoodsQuality.BASIC));
        hearth.insertGoods(arrows(4, GoodsQuality.FINE));
        hearth.insertGoods(arrows(3, GoodsQuality.SUPERIOR));
        BlockPos chestRel = new BlockPos(10, 1, 10);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos plaqueRel = new BlockPos(9, 1, 9);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get());
        BoundingBox bounds = BoundingBox.fromCorners(helper.absolutePos(plaqueRel),
            helper.absolutePos(new BlockPos(11, 3, 11)));
        Building warehouse = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(plaqueRel), helper.absolutePos(plaqueRel), bounds);
        warehouse.valid = true;
        s.buildings.add(warehouse);
        SettlerEntity tally = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        tally.setSettlerName("Tally");
        tally.bindTo(s.id, s.center);
        s.putRecord(tally.getUUID(), tally.getSettlerName(), Profession.NONE);
        tally.assignProfession(Profession.COURIER);
        int[] start = {5, 4, 3};
        helper.onEachTick(() -> {
            Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
            for (int q = 0; q < start.length; q++) {
                int total = count(hearth.getInventory(), q) + count(tally.bag, q) + count(chest, q)
                    + onGround(helper, q);
                if (total != start[q]) {
                    helper.fail("grade " + q + " arrows must be conserved every tick: saw " + total);
                }
            }
        });
        helper.succeedWhen(() -> {
            Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(chestRel));
            for (int q = 0; q < start.length; q++) {
                helper.assertTrue(count(chest, q) == start[q],
                    "grade " + q + " should all reach the warehouse unmerged, saw " + count(chest, q));
            }
            int stacks = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(Items.ARROW)) stacks++;
            helper.assertTrue(stacks >= 3, "three grades need three separate stacks, saw " + stacks);
            QualityConfig.testOverride = null;
            EconomyConfig.testOverride = null;
        });
    }

    // ------------------------------------------------- 3. graded merchant --

    /**
     * The village holds Fine leather and a Legendary iron sword. The market
     * publishes a Basic and a Fine leather row and a Legendary sword row.
     * Fine leather pays its graded price (5 leather -> 2 Coins); each sale
     * debits the purse by exactly the Coins paid even when the completion
     * callback is replayed; Basic leather cannot fill the Fine row; and once
     * the purse holds fewer Coins than the Legendary sword pays (5), that row
     * closes -- the purse is a hard cap.
     */
    @GameTest(template = "empty16", timeoutTicks = 40, batch = MERCHANT)
    public void goodsQualityMerchantPaysTheGradedPriceExactlyOnce(GameTestHelper helper) {
        QualityConfig.testOverride = Boolean.TRUE;
        EconomyConfig.testOverride = Boolean.TRUE;
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(4, 2, 4));
        Settlement settlement = new Settlement(UUID.randomUUID(), "Market",
            helper.absolutePos(new BlockPos(8, 2, 8)));
        SettlementSavedData.get(helper.getLevel()).settlements.put(settlement.id, settlement);
        trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
        trader.getPersistentData().putLong("HearthsteadMerchantExpires",
            helper.getLevel().getGameTime() + EarlyCoinMerchant.VISIT_TICKS);
        helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(trader, settlement.id),
            "a fresh owned visitor clears its vanilla rows first");
        ItemStack legendarySword = CraftedQuality.stamp(new ItemStack(Items.IRON_SWORD), GoodsQuality.LEGENDARY);
        List<ItemStack> stock = List.of(CraftedQuality.stamp(new ItemStack(Items.LEATHER, 40), GoodsQuality.FINE),
            legendarySword, new ItemStack(Items.OAK_LOG, 64));
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(helper.getLevel(), settlement, trader, null, stock),
            "the owned market publishes");
        MerchantOffer fine = null;
        MerchantOffer basic = null;
        MerchantOffer legend = null;
        for (MerchantOffer offer : trader.getOffers()) {
            if (offer.getBaseCostA().is(Items.LEATHER) && GoodsQuality.of(offer.getBaseCostA()) == GoodsQuality.FINE) fine = offer;
            if (offer.getBaseCostA().is(Items.LEATHER) && GoodsQuality.of(offer.getBaseCostA()) == GoodsQuality.BASIC) basic = offer;
            if (offer.getBaseCostA().is(Items.IRON_SWORD)
                && GoodsQuality.of(offer.getBaseCostA()) == GoodsQuality.LEGENDARY) legend = offer;
        }
        helper.assertTrue(fine != null && basic != null && legend != null,
            "Basic + Fine leather and Legendary sword rows must be published");
        int[] finePrice = CraftedQuality.salePrice(3, 64, GoodsQuality.FINE);
        helper.assertTrue(fine.getCostA().getCount() == finePrice[0] && fine.getResult().getCount() == finePrice[1]
                && finePrice[1] == 2 && finePrice[0] == 5,
            "Fine leather: 5 for 2 Coins, saw " + fine.getCostA().getCount() + " for " + fine.getResult().getCount());
        helper.assertTrue(legend.getCostA().getCount() == 1 && legend.getResult().getCount() == 5,
            "a Legendary sword is one sword for 5 Coins");
        helper.assertTrue(basic.getResult().getCount() == 1, "a Basic row still pays one Coin");
        helper.assertTrue(!fine.satisfiedBy(new ItemStack(Items.LEATHER, 64), ItemStack.EMPTY),
            "Basic leather can never fill the Fine row");
        int purse = GoldCoinTrades.remainingOwnedPurse(trader);
        helper.assertTrue(purse == GoldCoinTrades.RETURNING_VISIT_PURSE,
            "an empty village's purse is the base 12, saw " + purse);
        var player = helper.makeMockServerPlayerInLevel();
        trader.setTradingPlayer(player);
        ItemStack payment = CraftedQuality.stamp(new ItemStack(Items.LEATHER, 40), GoodsQuality.FINE);
        int index = trader.getOffers().indexOf(fine);
        int coins = 0;
        for (int sale = 0; sale < GoldCoinTrades.MAX_USES; sale++) {
            int before = GoldCoinTrades.remainingOwnedPurse(trader);
            helper.assertTrue(fine.take(payment, ItemStack.EMPTY), "each sale consumes real Fine leather");
            ItemStack result = fine.assemble();
            helper.assertTrue(result.is(ModItems.GOLD_COIN.get()) && result.getCount() == 2, "two Coins per sale");
            coins += result.getCount();
            trader.notifyTrade(fine);
            GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), trader, index, fine);
            GoldCoinTrades.finishOwnedPurchase(helper.getLevel(), trader, index, fine);
            int after = GoldCoinTrades.remainingOwnedPurse(trader);
            helper.assertTrue(after == before - 2, "each graded sale debits exactly its 2 Coins once: "
                + before + " -> " + after);
        }
        trader.setTradingPlayer(null);
        helper.assertTrue(coins == 8 && payment.getCount() == 40 - 5 * GoldCoinTrades.MAX_USES && fine.isOutOfStock(),
            "four graded sales: 8 Coins for 20 Fine leather, row closed");
        helper.assertTrue(GoldCoinTrades.remainingOwnedPurse(trader) == 4, "purse 12 - 8 = 4");
        helper.assertTrue(legend.isOutOfStock(), "a 5-Coin row closes once the purse holds only 4");
        helper.assertTrue(!basic.isOutOfStock(), "a 1-Coin row stays open while the purse has Coins");
        QualityConfig.testOverride = null;
        EconomyConfig.testOverride = null;
        helper.succeed();
    }

    // -------------------------------------------------- 4. old, no grade --

    /**
     * An iron sword and bread made before this lane (no component at all):
     * Basic grade, vanilla durability and attack exactly, the tooltip names
     * it Basic, a Basic crafted loaf still stacks with it, and the merchant's
     * Basic sword row accepts it.
     */
    @GameTest(template = "empty16", timeoutTicks = 40, batch = LEGACY)
    public void goodsQualityOldItemsWithoutAGradeStillWorkAsBasic(GameTestHelper helper) {
        ItemStack oldSword = new ItemStack(Items.IRON_SWORD);
        helper.assertTrue(!oldSword.has(ModComponents.GOODS_QUALITY.get())
                && GoodsQuality.of(oldSword) == GoodsQuality.BASIC, "no component reads as Basic");
        helper.assertTrue(oldSword.getMaxDamage() == 250, "vanilla durability is untouched");
        helper.assertTrue(!hasQualityAttack(oldSword), "no quality modifier on an ungraded sword");
        helper.assertTrue(Math.abs(attack(oldSword) - attack(new ItemStack(Items.IRON_SWORD))) < 1.0E-9,
            "vanilla attack is untouched");
        helper.assertTrue(GoodsQualityTooltip.shown(oldSword), "the tooltip names a workshop sword Basic");
        helper.assertTrue(CraftedQuality.realDurabilityBonusPercent(oldSword) == 0, "no durability line");
        ItemStack oldBread = new ItemStack(Items.BREAD, 10);
        ItemStack basicCrafted = CraftedQuality.stamp(new ItemStack(Items.BREAD, 3), GoodsQuality.BASIC);
        helper.assertTrue(ItemStack.isSameItemSameComponents(oldBread, basicCrafted),
            "Basic crafted bread is byte-identical to old bread and stacks with it");
        ItemStack fineBread = CraftedQuality.stamp(new ItemStack(Items.BREAD, 3), GoodsQuality.FINE);
        helper.assertTrue(!ItemStack.isSameItemSameComponents(oldBread, fineBread),
            "Fine bread never merges into Basic bread");
        helper.assertTrue(fineBread.get(DataComponents.FOOD).saturation()
                > oldBread.get(DataComponents.FOOD).saturation()
                && fineBread.get(DataComponents.FOOD).nutrition() == oldBread.get(DataComponents.FOOD).nutrition(),
            "Fine bread: a little more saturation, same nutrition");
        // A save round trip of a graded, durability-baked sword keeps everything.
        ItemStack graded = CraftedQuality.stamp(new ItemStack(Items.IRON_SWORD), GoodsQuality.EXCEPTIONAL);
        graded.setDamageValue(40);
        var saved = graded.saveOptional(helper.getLevel().registryAccess());
        ItemStack loaded = ItemStack.parseOptional(helper.getLevel().registryAccess(),
            (net.minecraft.nbt.CompoundTag) saved);
        helper.assertTrue(GoodsQuality.of(loaded) == GoodsQuality.EXCEPTIONAL && loaded.getMaxDamage() == 338
                && loaded.getDamageValue() == 40 && ItemStack.isSameItemSameComponents(loaded, graded),
            "grade, max durability and wear survive a save");
        // The Basic sword row (vanilla clamps its cost to one sword) accepts the old sword.
        MerchantOffer basicRow = GoldCoinTrades.purchase(Items.IRON_SWORD, GoodsQuality.BASIC);
        helper.assertTrue(basicRow.satisfiedBy(oldSword, ItemStack.EMPTY) && basicRow.getCostA().getCount() == 1,
            "the old sword sells on the Basic row");
        helper.succeed();
    }
}
