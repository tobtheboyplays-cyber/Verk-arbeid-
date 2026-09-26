package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.event.MerchantDemandSavedData;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class MerchantDemandGameTests {
    @GameTest(template = "empty16", timeoutTicks = 40)
    public void ownedVisitorIsCoinOnlyAndFreezesChosenWood(GameTestHelper helper) {
        var level = helper.getLevel();
        var trader = helper.spawn(EntityType.WANDERING_TRADER, new BlockPos(5, 2, 5));
        var settlement = new Settlement(UUID.randomUUID(), "Market", helper.absolutePos(new BlockPos(8, 2, 8)));
        SettlementSavedData.get(level).settlements.put(settlement.id, settlement);
        trader.getPersistentData().putUUID("HearthsteadEarlyMerchantSettlement", settlement.id);
        trader.getPersistentData().putLong("HearthsteadMerchantExpires", level.getGameTime() + 2000);
        helper.assertTrue(GoldCoinTrades.clearOwnedMerchantForPublication(trader, settlement.id)
                && trader.getOffers().isEmpty(), "publication clears every vanilla/Emerald row before a menu exists");
        var player = helper.makeMockServerPlayerInLevel();
        player.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 16));
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(level, settlement, trader, player, List.of()),
            "first safe interaction creates a finite owned market");
        var before = List.copyOf(trader.getOffers());
        helper.assertTrue(before.size() == 5 && before.stream().allMatch(offer ->
                offer.getResult().is(ModItems.GOLD_COIN.get()) && !offer.getBaseCostA().is(Items.EMERALD)
                    && !offer.getResult().is(Items.EMERALD))
                && before.getFirst().getBaseCostA().is(Items.SPRUCE_LOG)
                && before.getFirst().getCostA().getCount() == 8,
            "chosen local spruce has the protected first Basic rate and every owned row pays Coins");
        player.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 16));
        helper.assertTrue(GoldCoinTrades.ensureOwnedMarket(level, settlement, trader, player, List.of())
                && trader.getOffers().equals(before) && trader.getOffers().getFirst() == before.getFirst(),
            "a later interaction cannot retarget or reorder the frozen visitor offer list");
        trader.discard();
        SettlementSavedData.get(level).settlements.remove(settlement.id);
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 40)
    public void demandUsesNovelGoodsThenPersistsAndRecovers(GameTestHelper helper) {
        MerchantDemandSavedData demand = new MerchantDemandSavedData();
        UUID settlement = UUID.randomUUID();
        List<ResourceLocation> goods = List.of(key(Items.OAK_LOG), key(Items.WHEAT), key(Items.IRON_INGOT), key(Items.CARROT));
        var first = demand.beginVisit(settlement, goods, 100L, 3);
        helper.assertTrue(first != null && first.wanted().size() == 3, "first market has bounded requested goods");
        helper.assertTrue(demand.recordCompletedSale(settlement, key(Items.OAK_LOG), 200L)
                && demand.recordCompletedSale(settlement, key(Items.OAK_LOG), 201L)
                && demand.pressure(settlement, key(Items.OAK_LOG), 201L) == 2,
            "manual and Trader commit seam can accumulate one shared item pressure");
        var next = demand.beginVisit(settlement, goods, 300L, 3);
        helper.assertTrue(next != null && next.wanted().contains(key(Items.CARROT)),
            "a later market takes the sole novel candidate before repeating old wants");
        var saved = demand.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        var reloaded = MerchantDemandSavedData.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.pressure(settlement, key(Items.OAK_LOG), 201L + MerchantDemandSavedData.RECOVERY_TICKS) == 1,
            "saved pressure survives reload and recovers exactly one tier per Minecraft day");
        helper.succeed();
    }

    private static ResourceLocation key(net.minecraft.world.item.Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }
}
