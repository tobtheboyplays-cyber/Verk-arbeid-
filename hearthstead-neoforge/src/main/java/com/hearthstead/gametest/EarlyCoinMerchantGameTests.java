package com.hearthstead.gametest;
import com.hearthstead.Hearthstead;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class EarlyCoinMerchantGameTests {
    @GameTest(template="empty64",skyAccess=true,timeoutTicks=100,batch="early_coin_merchant")
    public void actualFoundedVillagePublishesOneReachableMerchantWithoutReplay(GameTestHelper helper) {
        assertFoundedVillagePublication(helper, false);
    }

    @GameTest(template="empty64",skyAccess=true,timeoutTicks=100,batch="early_coin_merchant")
    public void recessedHearthPublishesMerchantOnActualWalkingSurface(GameTestHelper helper) {
        assertFoundedVillagePublication(helper, true);
    }

    private void assertFoundedVillagePublication(GameTestHelper helper, boolean recessedHearth) {
        for(int x=0;x<64;x++) for(int z=0;z<64;z++) {
            helper.setBlock(new BlockPos(x,0,z),Blocks.STONE);
            if (recessedHearth) helper.setBlock(new BlockPos(x,1,z),Blocks.STONE);
        }
        BlockPos hearth=helper.absolutePos(new BlockPos(32,1,32));
        helper.getLevel().setBlockAndUpdate(hearth,ModBlocks.HEARTH.get().defaultBlockState());
        // GameTest arenas share SavedData and sit closer than independent
        // survival settlements. This test covers atomic founders and the real
        // merchant publication, not separation from unrelated test arenas.
        // Scope the existing fixture override to this one founding call.
        boolean previousDistanceOverride = SettlementManager.ignoreFoundingDistance;
        com.hearthstead.settlement.Settlement settlement;
        try {
            var neighbors = SettlementSavedData.get(helper.getLevel()).settlements.values().stream()
                .filter(other -> other.center.distSqr(hearth)
                    < Math.pow(other.radius + com.hearthstead.settlement.Settlement.DEFAULT_RADIUS, 2))
                .map(other -> other.id + "@" + other.center.toShortString() + "/radius=" + other.radius)
                .toList();
            Hearthstead.LOGGER.info("HSQA_MERCHANT_ARENA_ISOLATION hearth={} neighboringFixtures={}",
                hearth, neighbors);
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(helper.getLevel(), hearth);
        } finally {
            SettlementManager.ignoreFoundingDistance = previousDistanceOverride;
        }
        helper.assertTrue(settlement!=null && settlement.mayorId==null && settlement.population()==4,
            "actual founding must create its four ordinary founders (no Mayor) before merchant access");
        settlement.radius=8; // Owned64 arena tests publication, not default48 normal-terrain feasibility.
        var hearthEntity = (com.hearthstead.block.HearthBlockEntity) helper.getLevel().getBlockEntity(hearth);
        helper.assertTrue(hearthEntity != null && !EarlyCoinMerchant.visit(helper.getLevel(), settlement),
            "a Hearth block without this settlement binding cannot authorize a merchant");
        hearthEntity.bindSettlement(java.util.UUID.randomUUID());
        helper.assertTrue(!EarlyCoinMerchant.visit(helper.getLevel(), settlement),
            "another settlement's Hearth binding cannot authorize this visit");
        hearthEntity.bindSettlement(settlement.id);
        helper.assertTrue(EarlyCoinMerchant.visit(helper.getLevel(),settlement),
            "loaded outside route must publish a real merchant even when the Hearth is recessed");
        var traders=helper.getLevel().getEntitiesOfClass(WanderingTrader.class,new AABB(hearth).inflate(30),
            e->e.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement")
                && e.getPersistentData().getUUID("HearthsteadEarlyMerchantSettlement").equals(settlement.id));
        helper.assertTrue(traders.size()==1 && traders.getFirst().blockPosition().distSqr(hearth)>64,
            "one real merchant begins outside the actual settlement boundary");
        helper.assertTrue(!EarlyCoinMerchant.visit(helper.getLevel(),settlement),
            "same-tick invocation cannot refresh stock or publish a duplicate");
        var merchant=traders.getFirst();
        if (recessedHearth) {
            var path = merchant.getNavigation().getPath();
            helper.assertTrue(path != null && path.canReach()
                && path.getTarget().getY() == hearth.getY() + 1,
                "recessed Hearth must target its actual raised walking surface");
        }
        CompoundTag saved=merchant.saveWithoutId(new CompoundTag());
        var decoded=net.minecraft.world.entity.EntityType.WANDERING_TRADER.create(helper.getLevel());
        helper.assertTrue(decoded!=null,"merchant decode exists"); decoded.load(saved);
        helper.assertTrue(EarlyCoinMerchant.availableForTrade(decoded),"clean reload retains remaining visit window");
        decoded.getPersistentData().putLong("HearthsteadMerchantExpires",helper.getLevel().getGameTime());
        helper.assertTrue(!EarlyCoinMerchant.availableForTrade(decoded),"expired unloaded visitor cannot resume old stock");
        merchant.discard();
        for(var actor:SettlementManager.loadedMembers(helper.getLevel(),settlement)) actor.discard();
        SettlementSavedData.get(helper.getLevel()).settlements.remove(settlement.id);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        helper.getLevel().setBlockAndUpdate(hearth,Blocks.AIR.defaultBlockState());
        helper.succeed();
    }

    @GameTest(template="empty64",skyAccess=true,timeoutTicks=1200,batch="early_coin_merchant")
    public void walkingMerchantRecoversWhenOriginalArrivalAreaBecomesBlocked(GameTestHelper helper) {
        var level = helper.getLevel();
        for (int x=0; x<64; x++) for (int z=0; z<64; z++)
            helper.setBlock(new BlockPos(x,0,z), Blocks.STONE);
        BlockPos hearth = helper.absolutePos(new BlockPos(32,1,32));
        level.setBlockAndUpdate(hearth, ModBlocks.HEARTH.get().defaultBlockState());
        boolean previousOverride = SettlementManager.ignoreFoundingDistance;
        com.hearthstead.settlement.Settlement settlement;
        try {
            SettlementManager.ignoreFoundingDistance = true;
            settlement = SettlementManager.tryFound(level, hearth);
        } finally {
            SettlementManager.ignoreFoundingDistance = previousOverride;
        }
        helper.assertTrue(settlement != null && settlement.mayorId == null && settlement.population() == 4,
            "actual founding supplies the valid owner and four founders for production publication");
        settlement.radius = 8;
        ((com.hearthstead.block.HearthBlockEntity)level.getBlockEntity(hearth)).bindSettlement(settlement.id);
        var founders = java.util.List.copyOf(SettlementManager.loadedMembers(level, settlement));
        // Declared fixture placement BEFORE publication: keep ordinary founders out
        // of the later construction site. Merchant AI and movement are never altered.
        for (int i=0; i<founders.size(); i++) {
            BlockPos safe = helper.absolutePos(new BlockPos(4+i*2,1,4));
            founders.get(i).moveTo(safe.getX()+.5, safe.getY(), safe.getZ()+.5, 0, 0);
        }
        helper.assertTrue(EarlyCoinMerchant.visit(level, settlement),
            "production publishes one genuine visitor before the obstacle exists");
        var owned = level.getEntitiesOfClass(WanderingTrader.class, new AABB(hearth).inflate(30),
            e -> e.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement")
                && e.getPersistentData().getUUID("HearthsteadEarlyMerchantSettlement").equals(settlement.id));
        helper.assertTrue(owned.size() == 1, "exactly one published merchant belongs to this fixture");
        var merchant = owned.getFirst();
        var identity = merchant.getUUID();
        var start = merchant.position();
        float initialHealth = merchant.getHealth();
        long expires = merchant.getPersistentData().getLong("HearthsteadMerchantExpires");
        BlockPos original = BlockPos.of(merchant.getPersistentData().getLong("HearthsteadMerchantArrivalTarget"));
        var initialPath = merchant.getNavigation().getPath();
        helper.assertTrue(initialPath != null && initialPath.canReach()
            && initialPath.getTarget().equals(original) && original.getY() == hearth.getY()
            && original.distSqr(hearth) <= 36 && original.distSqr(hearth) >= 4
            && start.distanceToSqr(original.getX()+.5, original.getY(), original.getZ()+.5) >= 81,
            "original real target is reachable on flat terrain before dynamic obstruction");
        var receipts = level.getDataStorage().get(
            new net.minecraft.world.level.saveddata.SavedData.Factory<>(
                EarlyCoinMerchant.Receipts::new, EarlyCoinMerchant.Receipts::load, null),
            "hearthstead_early_merchants");
        helper.assertTrue(receipts != null, "actual publication persisted its visit receipt");
        CompoundTag receiptBefore = arrivalReceiptRow(receipts.save(new CompoundTag(), level.registryAccess()), hearth);
        helper.assertTrue(receiptBefore.getUUID("Merchant").equals(identity)
            && receiptBefore.getLong("Expires") == expires, "receipt owns this exact merchant and expiry");
        int[] blockedAt = {-1};
        boolean[] alternateObserved = {false};
        double[] walked = {0};
        net.minecraft.world.phys.Vec3[] previous = {start};
        helper.onEachTick(() -> {
            helper.assertTrue(merchant.isAlive() && level.getEntity(identity) == merchant
                && merchant.getPersistentData().getUUID("HearthsteadEarlyMerchantSettlement").equals(settlement.id)
                && merchant.getPersistentData().getLong("HearthsteadMerchantExpires") == expires,
                "route recovery retains the same live actor, owner and absolute visit expiry");
            double stepSquared = merchant.position().distanceToSqr(previous[0]);
            helper.assertTrue(stepSquared <= 4.0, "arrival must use continuous physical movement, never teleport");
            walked[0] += Math.sqrt(stepSquared);
            previous[0] = merchant.position();
            helper.assertTrue(!merchant.isInWater() && merchant.getHealth() >= initialHealth,
                "the visitor stays dry and unharmed through route recovery");
            if (blockedAt[0] < 0 && merchant.position().distanceToSqr(start) >= 1.0) {
                helper.assertTrue(!merchant.getPersistentData().getBoolean("HearthsteadMerchantArrived")
                    && merchant.distanceToSqr(original.getX()+.5, original.getY(), original.getZ()+.5) > 49,
                    "merchant must really walk before obstruction and remain outside construction footprint");
                // The entire old arrival radius is now blocked: outside this solid
                // cube horizontal distance is >=3.5, and its roof is five blocks up.
                // Preserve the actual bound Hearth even when buried by construction.
                for (int dx=-3; dx<=3; dx++) for (int dz=-3; dz<=3; dz++) for (int dy=0; dy<5; dy++) {
                    BlockPos block = original.offset(dx,dy,dz);
                    if (!block.equals(hearth)) level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
                }
                BlockPos clearAlternative = hearth.offset(-6*Integer.signum(original.getX()-hearth.getX()), 0,
                    -6*Integer.signum(original.getZ()-hearth.getZ()));
                helper.assertTrue(arrivalSafeFeet(level, merchant, clearAlternative),
                    "a clear grounded alternative remains within six blocks of the same Hearth");
                blockedAt[0] = (int)helper.getTick();
                Hearthstead.LOGGER.info("HSQA_MERCHANT_DYNAMIC_OBSTACLE merchant={} tick={} pos={} original={} clearAlternative={}",
                    identity, helper.getTick(), merchant.position(), original, clearAlternative);
            }
            if (blockedAt[0] < 0) return;
            BlockPos target = BlockPos.of(merchant.getPersistentData().getLong("HearthsteadMerchantArrivalTarget"));
            if (!target.equals(original)) {
                alternateObserved[0] = true;
                helper.assertTrue(target.distSqr(hearth) <= 36 && arrivalSafeFeet(level, merchant, target),
                    "accepted replacement is a safe physical destination near the same owner Hearth");
                var path = merchant.getNavigation().getPath();
                if (path != null && !merchant.getNavigation().isDone()) {
                    helper.assertTrue(path.canReach() && path.getTarget().equals(target),
                        "accepted alternate path actually reaches the stored replacement destination");
                    for (int node=path.getNextNodeIndex(); node<path.getNodeCount(); node++)
                        helper.assertTrue(arrivalSafeFeet(level, merchant, path.getNodePos(node)),
                            "alternate route never bypasses ground, fluid or body-clearance guards at node " + node);
                }
            }
            if (!merchant.getPersistentData().getBoolean("HearthsteadMerchantArrived")) return;
            helper.assertTrue(alternateObserved[0] && !target.equals(original) && walked[0] >= 5
                && merchant.distanceToSqr(target.getX()+.5,target.getY(),target.getZ()+.5) <= 6.25
                && Math.abs(merchant.getY()-target.getY()) <= 1.5
                && merchant.position().distanceToSqr(hearth.getX()+.5,hearth.getY(),hearth.getZ()+.5) <= 100,
                "normal AI physically arrives at an alternate, never the blocked original region");
            helper.assertTrue(!EarlyCoinMerchant.visit(level, settlement)
                && arrivalReceiptRow(receipts.save(new CompoundTag(),level.registryAccess()),hearth).equals(receiptBefore),
                "recovering arrival cannot republish a visit or change its receipt");
            var after = level.getEntitiesOfClass(WanderingTrader.class, new AABB(hearth).inflate(30),
                e -> e.getPersistentData().hasUUID("HearthsteadEarlyMerchantSettlement")
                    && e.getPersistentData().getUUID("HearthsteadEarlyMerchantSettlement").equals(settlement.id));
            helper.assertTrue(after.size() == 1 && after.getFirst() == merchant,
                "recovery retains exactly the original published entity");
            CompoundTag saved = merchant.saveWithoutId(new CompoundTag());
            var decoded = net.minecraft.world.entity.EntityType.WANDERING_TRADER.create(level);
            helper.assertTrue(decoded != null, "decode-only visitor exists");
            decoded.load(saved);
            helper.assertTrue(decoded.getUUID().equals(identity)
                && decoded.getPersistentData().getUUID("HearthsteadEarlyMerchantSettlement").equals(settlement.id)
                && decoded.getPersistentData().getLong("HearthsteadMerchantExpires") == expires
                && decoded.getPersistentData().getBoolean("HearthsteadMerchantArrived")
                && decoded.getPersistentData().getLong("HearthsteadMerchantArrivalTarget") == target.asLong()
                && EarlyCoinMerchant.availableForTrade(decoded),
                "entity serialization preserves completed arrival, identity, owner and remaining visit window");
            var reloadedReceipts = EarlyCoinMerchant.Receipts.load(
                receipts.save(new CompoundTag(),level.registryAccess()), level.registryAccess());
            long next = receiptBefore.getLong("Next");
            helper.assertTrue(!reloadedReceipts.available(hearth, level.getGameTime())
                && !reloadedReceipts.available(hearth, next-1) && reloadedReceipts.available(hearth, next)
                && arrivalReceiptRow(reloadedReceipts.save(new CompoundTag(),level.registryAccess()),hearth).equals(receiptBefore),
                "receipt serialization retains exact same visit and original next-publication boundary");
            merchant.discard();
            for (var founder:founders) founder.discard();
            SettlementSavedData.get(level).settlements.remove(settlement.id);
            SettlementSavedData.get(level).setDirty();
            level.setBlockAndUpdate(hearth,Blocks.AIR.defaultBlockState());
            helper.succeed();
        });
        helper.runAtTickTime(/* absolute: terminal step at the deadline */ 1199, () -> helper.assertTrue(false,
            "merchant failed real alternate arrival: blockedAt=" + blockedAt[0] + " alternate=" + alternateObserved[0]
                + " pos=" + merchant.position() + " target="
                + BlockPos.of(merchant.getPersistentData().getLong("HearthsteadMerchantArrivalTarget"))
                + " navDone=" + merchant.getNavigation().isDone() + " walked=" + walked[0]));
    }

    private static CompoundTag arrivalReceiptRow(CompoundTag saved, BlockPos hearth) {
        var rows = saved.getList("Visits",net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int index=0; index<rows.size(); index++)
            if (rows.getCompound(index).getLong("Site") == hearth.asLong()) return rows.getCompound(index).copy();
        throw new IllegalStateException("published site receipt missing");
    }

    private static boolean arrivalSafeFeet(net.minecraft.server.level.ServerLevel level,
                                          WanderingTrader merchant, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())
            || !level.getWorldBorder().isWithinBounds(feet)
            || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()
            || !level.getBlockState(feet.below()).isFaceSturdy(level,feet.below(),net.minecraft.core.Direction.UP)) return false;
        double half = merchant.getBbWidth()/2.0;
        return level.noCollision(merchant,new AABB(feet.getX()+.5-half,feet.getY(),feet.getZ()+.5-half,
            feet.getX()+.5+half,feet.getY()+merchant.getBbHeight(),feet.getZ()+.5+half));
    }
}
