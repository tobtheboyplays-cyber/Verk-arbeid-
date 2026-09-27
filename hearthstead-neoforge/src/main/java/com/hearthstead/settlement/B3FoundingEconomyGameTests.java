package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;

/** Real founding transaction and physical inventory conservation for B3. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class B3FoundingEconomyGameTests {
    private static final BlockPos LOCAL = new BlockPos(8, 1, 8);
    private static final int GIFT = 5;

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "b3_founding_coins_once")
    public void successfulFoundingGetsCoinsOnlyOnceAcrossRebindAndReload(GameTestHelper h) {
        // Default is 0 since the Guildmaster welcome gift (owner, 27 Sep); prove the gift path with 5.
        StarterKitConfig.overrideStartCoinsForTests(GIFT);
        HearthBlockEntity banner = banner(h);
        Settlement s = null;
        try {
            s = found(h, banner.getBlockPos());
            h.assertTrue(s != null && s.population() == 4, "complete actual founding commits");
            banner.bindSettlement(s.id);
            h.assertTrue(coins(banner) == StarterKitConfig.startCoins() && droppedCoins(h, banner.getBlockPos()) == 0,
                "exact configured gift reaches Banner inventory physically");
            // Spend the gift before replay: checking a still-full stack would miss silent replenishment.
            for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
                if (banner.getInventory().getStackInSlot(slot).is(ModItems.GOLD_COIN.get()))
                    banner.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
            h.assertTrue(SettlementManager.tryFound(h.getLevel(), s.center) == s && coins(banner) == 0,
                "same-site rebind never replenishes spent Coins");
            Settlement loaded = Settlement.readNbt(s.writeNbt());
            SettlementSavedData.get(h.getLevel()).settlements.put(loaded.id, loaded);
            s = loaded;
            h.assertTrue(SettlementManager.tryFound(h.getLevel(), loaded.center) == loaded
                && coins(banner) == 0 && loaded.population() == 4,
                "save reload retains identity and cannot grant another starting gift");
        } finally { StarterKitConfig.overrideStartCoinsForTests(null); cleanup(h, banner, s); }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "b3_founding_coins_rollback")
    public void failedFourthFounderMintsNothingAndRetryGrantsExactlyOnce(GameTestHelper h) {
        // Default is 0 since the Guildmaster welcome gift (owner, 27 Sep); prove the gift path with 5.
        StarterKitConfig.overrideStartCoinsForTests(GIFT);
        HearthBlockEntity banner = banner(h);
        List<SettlerEntity> tentative = new ArrayList<>();
        Settlement success = null;
        boolean previous = SettlementManager.ignoreFoundingDistance;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            int[] calls = {0};
            Settlement failed = SettlementManager.tryFoundWithSpawner(h.getLevel(), banner.getBlockPos(), (level, s) -> {
                if (++calls[0] == 4) return null;
                SettlerEntity founder = SettlementManager.spawnSettler(level, s, false);
                if (founder != null) tentative.add(founder);
                return founder;
            });
            h.assertTrue(failed == null && calls[0] == 4 && tentative.size() == 3
                && tentative.stream().allMatch(SettlerEntity::isRemoved), "three actual provisional founders roll back");
            h.assertTrue(coins(banner) == 0 && droppedCoins(h, banner.getBlockPos()) == 0,
                "failed founding creates no inventory or dropped Coins");
            success = SettlementManager.tryFound(h.getLevel(), banner.getBlockPos());
            h.assertTrue(success != null && success.population() == 4
                && coins(banner) == StarterKitConfig.startCoins(), "retry commits the exact single gift");
            SettlementManager.tryFound(h.getLevel(), banner.getBlockPos());
            h.assertTrue(coins(banner) == StarterKitConfig.startCoins(), "repeated success cannot duplicate gift");
        } finally {
            SettlementManager.ignoreFoundingDistance = previous;
            for (SettlerEntity founder : tentative) if (!founder.isRemoved()) founder.discard();
            cleanup(h, banner, success);
            StarterKitConfig.overrideStartCoinsForTests(null);
        }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "b3_founding_coins_full_banner")
    public void fullBannerPreservesStockAndDropsOnlyTheUninsertedGift(GameTestHelper h) {
        // Default is 0 since the Guildmaster welcome gift (owner, 27 Sep); prove the gift path with 5.
        StarterKitConfig.overrideStartCoinsForTests(GIFT);
        HearthBlockEntity banner = banner(h);
        Settlement s = null;
        for (int slot = 0; slot < banner.getInventory().getSlots(); slot++)
            banner.getInventory().setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
        CompoundTag before = banner.getInventory().serializeNBT(h.getLevel().registryAccess());
        try {
            s = found(h, banner.getBlockPos());
            h.assertTrue(s != null && before.equals(banner.getInventory().serializeNBT(h.getLevel().registryAccess())),
                "founding gift must never overwrite pre-existing Banner contents");
            h.assertTrue(coins(banner) == 0 && droppedCoins(h, banner.getBlockPos()) == StarterKitConfig.startCoins(),
                "full Banner conserves the exact uninserted gift as physical items");
            SettlementManager.tryFound(h.getLevel(), banner.getBlockPos());
            h.assertTrue(droppedCoins(h, banner.getBlockPos()) == StarterKitConfig.startCoins(),
                "rebind cannot duplicate the fallback drop");
        } finally { StarterKitConfig.overrideStartCoinsForTests(null); cleanup(h, banner, s); }
        h.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "b3_live_reserve_quotes")
    public void recruitmentAndReadinessUseTheSmallerPhysicalReserveWithoutConsumingIt(GameTestHelper h) {
        HearthBlockEntity banner = banner(h);
        Settlement s = null;
        try {
            s = found(h, banner.getBlockPos());
            h.assertTrue(s != null, "actual founded village");
            banner.bindSettlement(s.id);
            banner.getInventory().setStackInSlot(1, new ItemStack(Items.BREAD, 16));
            CompoundTag stock = banner.getInventory().serializeNBT(h.getLevel().registryAccess());
            RecruitmentPolicy.Assessment quote = RecruitmentPolicy.assess(h.getLevel(), s, RecruitmentPolicy.Stage.WAITING_ADMISSION);
            var readiness = FirstRaidReadinessService.assessDomain(h.getLevel(), s);
            h.assertTrue(quote.requiredReadyFood() == 16 && quote.missingReadyFood() == 0
                && readiness.metrics().requiredReadyMeals() == 16,
                "live recruitment and readiness projections both use the sixteen-meal founding reserve");
            h.assertTrue(stock.equals(banner.getInventory().serializeNBT(h.getLevel().registryAccess())),
                "reserve assessments only observe real stock");
            s.raidLifecycle.markIntegrityLost();
            h.assertTrue(RecruitmentPolicy.assess(h.getLevel(), s, RecruitmentPolicy.Stage.WAITING_ADMISSION)
                    .requiredReadyFood() == 40
                && FirstRaidReadinessService.assessDomain(h.getLevel(), s).metrics().requiredReadyMeals() == 32,
                "damaged raid authority restores ordinary current and post-recruit reserves");
        } finally { StarterKitConfig.overrideStartCoinsForTests(null); cleanup(h, banner, s); }
        h.succeed();
    }

    private static HearthBlockEntity banner(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
        h.setBlock(LOCAL, ModBlocks.HEARTH.get());
        return (HearthBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(LOCAL));
    }

    private static Settlement found(GameTestHelper h, BlockPos center) {
        boolean previous = SettlementManager.ignoreFoundingDistance;
        try { SettlementManager.ignoreFoundingDistance = true; return SettlementManager.tryFound(h.getLevel(), center); }
        finally { SettlementManager.ignoreFoundingDistance = previous; }
    }

    private static int coins(HearthBlockEntity banner) {
        int total = 0;
        for (int slot = 0; slot < banner.getInventory().getSlots(); slot++) {
            ItemStack stack = banner.getInventory().getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) total += stack.getCount();
        }
        return total;
    }

    private static int droppedCoins(GameTestHelper h, BlockPos pos) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
            e -> e.getItem().is(ModItems.GOLD_COIN.get())).stream().mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static void cleanup(GameTestHelper h, HearthBlockEntity banner, Settlement s) {
        if (s != null) {
            for (SettlerEntity founder : SettlementManager.loadedMembers(h.getLevel(), s)) founder.discard();
            SettlementSavedData.get(h.getLevel()).settlements.remove(s.id);
        }
        for (ItemEntity item : h.getLevel().getEntitiesOfClass(ItemEntity.class,
            new AABB(banner.getBlockPos()).inflate(3), e -> e.getItem().is(ModItems.GOLD_COIN.get()))) item.discard();
        for (int slot = 0; slot < banner.getInventory().getSlots(); slot++)
            banner.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
        h.getLevel().setBlock(banner.getBlockPos(), Blocks.AIR.defaultBlockState(), 2);
    }
}
