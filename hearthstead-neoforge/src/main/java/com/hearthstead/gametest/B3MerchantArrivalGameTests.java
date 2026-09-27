package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.LinkedHashMap;
import java.util.List;

/** Exercises the event handler, rather than the visit() seam that already ignores player proximity. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class B3MerchantArrivalGameTests {
    private static final String RECEIPTS = "hearthstead_early_merchants";
    private static final String OWNER = "HearthsteadEarlyMerchantSettlement";
    private static final SavedData.Factory<EarlyCoinMerchant.Receipts> FACTORY = new SavedData.Factory<>(
        EarlyCoinMerchant.Receipts::new, EarlyCoinMerchant.Receipts::load, null);

    @GameTest(template = "empty64", skyAccess = true, timeoutTicks = 200, batch = "b3_first_merchant_without_spectator")
    public void eventTickPublishesFirstMerchantWithoutNearbyPlayerAndReloadCannotReplayIt(GameTestHelper h) {
        h.runAfterDelay(100L - Math.floorMod(h.getLevel().getGameTime(), 100L), () -> {
            ServerLevel level = h.getLevel();
            for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            BlockPos center = h.absolutePos(new BlockPos(32, 1, 32));
            level.setBlockAndUpdate(center, ModBlocks.HEARTH.get().defaultBlockState());
            Settlement settlement = null;
            boolean previous = SettlementManager.ignoreFoundingDistance;
            try {
                SettlementManager.ignoreFoundingDistance = true;
                settlement = SettlementManager.tryFound(level, center);
                h.assertTrue(settlement != null && settlement.population() == 4, "real complete founding");
                settlement.radius = 8;
                ((HearthBlockEntity) level.getBlockEntity(center)).bindSettlement(settlement.id);
                h.assertTrue(level.players().stream().noneMatch(p -> p.isAlive() && p.blockPosition().distSqr(center) <= 80 * 80),
                    "fixture has no nearby player: only the first-visit exception can publish");
                EarlyCoinMerchant.Receipts receipts = level.getDataStorage().computeIfAbsent(FACTORY, RECEIPTS);
                h.assertTrue(!receipts.hasVisited(center), "unvisited physical site");
                tickOnlyFixture(level, settlement);
                List<WanderingTrader> merchants = owned(level, settlement);
                h.assertTrue(merchants.size() == 1 && receipts.hasVisited(center),
                    "actual LevelTick handler publishes one merchant without a spectator");
                WanderingTrader first = merchants.getFirst();
                h.assertTrue(first.getNavigation().getPath() != null && first.getNavigation().getPath().canReach(),
                    "merchant publication includes a real walkable approach");
                CompoundTag committed = receipts.save(new CompoundTag(), level.registryAccess());
                var restored = EarlyCoinMerchant.Receipts.load(committed, level.registryAccess());
                level.getDataStorage().set(RECEIPTS, restored);
                tickOnlyFixture(level, settlement);
                List<WanderingTrader> after = owned(level, settlement);
                h.assertTrue(after.size() == 1 && after.getFirst().getUUID().equals(first.getUUID())
                    && committed.equals(restored.save(new CompoundTag(), level.registryAccess())),
                    "receipt reload plus event replay cannot replace the visitor or refresh its stock window");
                CompoundTag merchantNbt = first.saveWithoutId(new CompoundTag());
                WanderingTrader decoded = net.minecraft.world.entity.EntityType.WANDERING_TRADER.create(level);
                h.assertTrue(decoded != null, "merchant decode");
                decoded.load(merchantNbt);
                h.assertTrue(EarlyCoinMerchant.availableForTrade(decoded), "visitor reload preserves remaining valid visit");
                decoded.getPersistentData().putLong("HearthsteadMerchantExpires", level.getGameTime());
                h.assertTrue(!EarlyCoinMerchant.availableForTrade(decoded), "expired visitor cannot resurrect its stock");
            } finally {
                SettlementManager.ignoreFoundingDistance = previous;
                if (settlement != null) {
                    for (WanderingTrader merchant : owned(level, settlement)) merchant.discard();
                    for (var founder : SettlementManager.loadedMembers(level, settlement)) founder.discard();
                    SettlementSavedData.get(level).settlements.remove(settlement.id);
                }
                if (level.getBlockEntity(center) instanceof HearthBlockEntity banner) {
                    for (int slot = 0; slot < banner.getInventory().getSlots(); slot++)
                        banner.getInventory().setStackInSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                }
                level.setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
            }
            h.succeed();
        });
    }

    private static List<WanderingTrader> owned(ServerLevel level, Settlement s) {
        return level.getEntitiesOfClass(WanderingTrader.class, new AABB(s.center).inflate(35),
            e -> e.getPersistentData().hasUUID(OWNER) && e.getPersistentData().getUUID(OWNER).equals(s.id));
    }

    private static void tickOnlyFixture(ServerLevel level, Settlement s) {
        // Synchronous event scope: older test arenas must not spawn unrelated merchants,
        // and the bounded 256-settlement scan must observe this owned fixture.
        var data = SettlementSavedData.get(level);
        var others = new LinkedHashMap<>(data.settlements);
        try {
            data.settlements.clear();
            data.settlements.put(s.id, s);
            EarlyCoinMerchant.onTick(new LevelTickEvent.Post(() -> true, level));
        } finally {
            data.settlements.clear();
            data.settlements.putAll(others);
        }
    }
}
