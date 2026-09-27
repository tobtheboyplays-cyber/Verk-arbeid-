package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.Weight;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Sack tiers, the Hand Cart and road speed, against real couriers and real
 * chests. Every delivery test counts hearth + warehouse + bag + ground so a
 * bigger load can never create or lose an item (exact-once), and asserts the
 * peak load equals the tier's own binding limit (count or weight), so an
 * upgrade that silently does nothing fails too. Own batches; daytime only.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HaulGearGameTests {
    private static final String BATCH = "haul_gear";

    public HaulGearGameTests() {
    }

    // Budget (BH-22): the GameTest server runs the neutral economy, so a
    // courier lifts one item per 40-tick Hearth cycle and stows one per
    // 40-tick chest cycle: ~80 ticks per item plus the walks. 50 items need
    // ~4500 ticks; the old flat 3000 was never run green (W3a 06:33).
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 4000)
    public void satchelCourierDeliversExactlyOnce(GameTestHelper helper) {
        deliverWithGear(helper, 1, false, Items.OAK_PLANKS, 30, "Satchelby");
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 5000)
    public void leatherPackCourierDeliversExactlyOnce(GameTestHelper helper) {
        deliverWithGear(helper, 2, false, Items.OAK_PLANKS, 40, "Packby");
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 6000)
    public void framePackCourierDeliversExactlyOnce(GameTestHelper helper) {
        deliverWithGear(helper, 3, false, Items.OAK_PLANKS, 50, "Frameby");
    }

    /** Heavy cargo: the cart's larger weight budget must carry 14 logs a trip, not 4. */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 4800)
    public void handCartCourierHaulsHeavyLogsExactlyOnce(GameTestHelper helper) {
        deliverWithGear(helper, 1, true, Items.OAK_LOG, 34, "Cartby");
    }

    /**
     * A Courier killed with a full cart load drops every item of it next to
     * the body: the cart is the bag, so there is no second inventory that
     * could vanish with the entity.
     */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 2000)
    public void handCartLoadDropsOnCourierDeath(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14, Blocks.GRASS_BLOCK);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, "Cartfall");
        grantGear(helper, s, 3, true);
        int seeded = 40;
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, seeded));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4), "Cart Bud");
        final boolean[] killed = {false};
        final int[] carried = {0};
        final BlockPos[] at = {null};
        helper.succeedWhen(() -> {
            int inBag = bagCount(bud);
            if (!killed[0]) {
                // Only a load a plain sack could never hold proves the cart.
                helper.assertTrue(inBag > SettlerEntity.BASE_CARRY_CAPACITY
                        && bud.getActivity() == SettlerActivity.CARRYING,
                    "waiting for a cart-sized load: bag=" + inBag + " cap="
                        + bud.getCarryCapacity() + " act=" + bud.getActivity());
                carried[0] = inBag;
                at[0] = bud.blockPosition();
                bud.kill();
                killed[0] = true;
                helper.assertTrue(false, "let the drop settle");
            }
            int dropped = 0;
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(at[0]).inflate(4.0D), e -> e.getItem().is(Items.OAK_PLANKS))) {
                dropped += item.getItem().getCount();
            }
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            int stored = chest == null ? 0 : countIn(chest, Items.OAK_PLANKS);
            int home = hearthCount(helper, hearthRel, Items.OAK_PLANKS);
            helper.assertTrue(dropped == carried[0]
                    && stored + home + dropped == seeded && bagCount(bud) == 0,
                "a cart load must drop whole on death: carried=" + carried[0]
                    + " dropped=" + dropped + " stored=" + stored + " home=" + home);
        });
    }

    /**
     * Paved Roads: +15% only on a road block; nothing without the upgrade.
     * The cart rolls +10% on a road and -15% off it while hitched.
     */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void roadSpeedFollowsTheGroundAndTheUpgrades(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), x < 4 ? Blocks.DIRT_PATH : Blocks.GRASS_BLOCK);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, "Roadby");
        SettlerEntity walker = settler(helper, s, new BlockPos(1, 1, 1), Profession.FARMER);
        SettlerEntity carter = settler(helper, s, new BlockPos(5, 1, 1), Profession.COURIER);

        HaulGear.onFootfall(walker);
        helper.assertTrue(HaulGear.appliedPercent(walker) == 0,
            "a road without Paved Roads must not change speed");

        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlockUpgrade(PostRaidUpgrade.PAVED_ROADS);
        HaulGear.onFootfall(walker);
        var modifier = walker.getAttribute(Attributes.MOVEMENT_SPEED)
            .getModifier(HaulGear.TERRAIN_SPEED_ID);
        helper.assertTrue(HaulGear.appliedPercent(walker) == 15 && modifier != null
                && modifier.operation() == net.minecraft.world.entity.ai.attributes
                    .AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
            "Paved Roads must give +15% on a dirt path, got "
                + HaulGear.appliedPercent(walker));
        HaulGear.onFootfall(carter);
        helper.assertTrue(HaulGear.appliedPercent(carter) == 0,
            "grass is not a road: no Paved Roads bonus");

        grantGear(helper, s, 1, true);
        carter.setActivity(SettlerActivity.CARRYING);
        HaulGear.onFootfall(carter);
        helper.assertTrue(carter.hasHandCart()
                && HaulGear.appliedPercent(carter) == -15,
            "a hitched cart on grass must roll 15% slower, got "
                + HaulGear.appliedPercent(carter));
        carter.teleportTo(helper.absolutePos(new BlockPos(2, 1, 2)).getX() + 0.5D,
            helper.absolutePos(new BlockPos(2, 1, 2)).getY(),
            helper.absolutePos(new BlockPos(2, 1, 2)).getZ() + 0.5D);
        HaulGear.onFootfall(carter);
        helper.assertTrue(HaulGear.appliedPercent(carter) == 25,
            "a hitched cart on a paved road gets +15% road +10% cart, got "
                + HaulGear.appliedPercent(carter));
        carter.setActivity(SettlerActivity.IDLE);
        HaulGear.onFootfall(carter);
        helper.assertTrue(HaulGear.appliedPercent(carter) == 15,
            "a parked cart never slows or speeds anyone");
        helper.succeed();
    }

    /** Leather and Frame Packs chain, pay their goods exactly once and grow every sack. */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void sackTiersChainAndPayExactlyOnce(GameTestHelper helper) {
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, "Tierby");
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.bindSettlement(s.id);
        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        SettlerEntity courier = settler(helper, s, new BlockPos(6, 1, 6), Profession.COURIER);
        SettlerEntity farmer = settler(helper, s, new BlockPos(8, 1, 6), Profession.FARMER);
        state.unlockUpgrade(PostRaidUpgrade.WORKER_PACKS);

        fund(hearth, PostRaidUpgrade.FRAME_PACK);
        helper.assertTrue(Development.purchaseUpgrade(helper.getLevel(), s, hearth,
                PostRaidUpgrade.FRAME_PACK, state.revision(), null)
                == Development.Result.PREREQUISITE,
            "the Frame Pack must wait for the Leather Pack");
        clear(hearth);

        int[] expected = {12, 16, 20};
        PostRaidUpgrade[] chain = {PostRaidUpgrade.COURIER_SATCHEL,
            PostRaidUpgrade.LEATHER_PACK, PostRaidUpgrade.FRAME_PACK};
        for (int i = 0; i < chain.length; i++) {
            PostRaidUpgrade tier = chain[i];
            fund(hearth, tier);
            int revision = state.revision();
            helper.assertTrue(Development.purchaseUpgrade(helper.getLevel(), s, hearth,
                    tier, revision, null) == Development.Result.APPLIED
                    && total(hearth) == 0,
                tier.id() + " must consume exactly its price");
            fund(hearth, tier);
            helper.assertTrue(Development.purchaseUpgrade(helper.getLevel(), s, hearth,
                    tier, revision + 1, null) == Development.Result.ALREADY_UNLOCKED
                    && total(hearth) == totalCost(tier),
                "a replayed " + tier.id() + " must pay nothing");
            clear(hearth);
            int raised = CourierSatchel.apply(helper.getLevel(), s, courier);
            int packed = WorkerPacks.apply(helper.getLevel(), s, farmer);
            helper.assertTrue(raised == expected[i] && packed == expected[i]
                    && courier.sackTier() == i + 1 && farmer.sackTier() == i + 1,
                tier.id() + " must give courier and packed farmer " + expected[i]
                    + " (tier " + (i + 1) + "), got " + raised + "/" + packed
                    + " tiers " + courier.sackTier() + "/" + farmer.sackTier());
        }
        helper.succeed();
    }

    /**
     * Sunday kill-switch: with {@code [features] logisticsUpgrades=false} a settlement
     * that owns every sack tier, the cart, Worker Packs and Paved Roads keeps its
     * carriers at the base budget, shows no sack/cart and gets no road speed; the
     * owned upgrades stay saved, so switching back on restores them. Own batch:
     * the switch is global, so nothing else may run while it is flipped.
     */
    @GameTest(template = "empty16", batch = "haul_gear_killswitch", timeoutTicks = 100)
    public void logisticsKillSwitchKeepsBaseCapacityAndNoCart(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.DIRT_PATH);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos hearthRel = new BlockPos(6, 1, 6);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, "Switchby");
        grantGear(helper, s, 3, true);
        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlockUpgrade(PostRaidUpgrade.WORKER_PACKS);
        state.unlockUpgrade(PostRaidUpgrade.PAVED_ROADS);
        SettlerEntity courier = settler(helper, s, new BlockPos(1, 1, 1), Profession.COURIER);
        SettlerEntity farmer = settler(helper, s, new BlockPos(3, 1, 1), Profession.FARMER);
        var level = helper.getLevel();
        boolean before = HearthsteadServerConfig.logisticsUpgradesEnabled();
        try {
            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(false);
            helper.assertTrue(!HaulGear.enabled(), "the switch must read false once set");
            int raised = CourierSatchel.apply(level, s, courier);
            int packed = WorkerPacks.apply(level, s, farmer);
            courier.setActivity(SettlerActivity.CARRYING);
            HaulGear.onFootfall(courier);
            HaulGear.onFootfall(farmer);
            helper.assertTrue(HaulGear.sackTier(level, s) == 0
                    && !HaulGear.cartOwned(level, s)
                    && HaulGear.gearFor(level, s, courier) == 0
                    && !courier.hasHandCart() && courier.sackTier() == 0,
                "disabled logistics must show no sack tier or cart: tier="
                    + HaulGear.sackTier(level, s) + " gear=" + HaulGear.gearFor(level, s, courier));
            helper.assertTrue(raised == SettlerEntity.BASE_CARRY_CAPACITY
                    && packed == SettlerEntity.BASE_CARRY_CAPACITY,
                "disabled logistics must keep base capacity, got courier=" + raised
                    + " farmer=" + packed);
            helper.assertTrue(HaulGear.appliedPercent(courier) == 0
                    && HaulGear.appliedPercent(farmer) == 0,
                "disabled logistics must apply no road/cart speed, got "
                    + HaulGear.appliedPercent(courier) + "/" + HaulGear.appliedPercent(farmer));

            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(true);
            int restored = CourierSatchel.apply(level, s, courier);
            helper.assertTrue(restored > SettlerEntity.BASE_CARRY_CAPACITY
                    && HaulGear.sackTier(level, s) == 3 && HaulGear.cartOwned(level, s),
                "re-enabling must restore the owned upgrades, got capacity=" + restored);
        } finally {
            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(before);
        }
        helper.succeed();
    }

    // ---- shared delivery scenario ----

    private static void deliverWithGear(GameTestHelper helper, int tier, boolean cart,
                                        Item cargo, int seeded, String town) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14, Blocks.STONE_BRICKS);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, town);
        grantGear(helper, s, tier, cart);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(cargo, seeded));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4), town + " Bud");
        int capacity = HaulGear.courierCapacity(tier, cart);
        int expectedPeak = Weight.perLoad(new ItemStack(cargo), capacity);
        final int[] peak = {0};
        helper.succeedWhen(() -> {
            int load = bagCount(bud);
            peak[0] = Math.max(peak[0], load);
            helper.assertTrue(load <= bud.getCarryCapacity(),
                "carried " + load + " over capacity " + bud.getCarryCapacity());
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            int stored = chest == null ? 0 : countIn(chest, cargo);
            int home = hearthCount(helper, hearthRel, cargo);
            helper.assertTrue(stored + home + load <= seeded,
                "items were created: stored=" + stored + " home=" + home + " bag=" + load);
            helper.assertTrue(stored == seeded && home == 0 && load == 0,
                "all " + seeded + " must arrive exactly once: stored=" + stored
                    + " home=" + home + " bag=" + load + " peak=" + peak[0]
                    + " cap=" + bud.getCarryCapacity());
            helper.assertTrue(bud.getCarryCapacity() == capacity
                    && bud.sackTier() == tier && bud.hasHandCart() == cart,
                "gear projection wrong: cap=" + bud.getCarryCapacity() + " tier="
                    + bud.sackTier() + " cart=" + bud.hasHandCart());
            helper.assertTrue(peak[0] == expectedPeak,
                "a trip must fill to the tier's binding limit: peak=" + peak[0]
                    + " expected=" + expectedPeak + " capacity=" + capacity);
        });
    }

    private static void grantGear(GameTestHelper helper, Settlement s, int tier, boolean cart) {
        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        if (tier >= 1) {
            state.unlockUpgrade(PostRaidUpgrade.COURIER_SATCHEL);
        }
        if (tier >= 2) {
            state.unlockUpgrade(PostRaidUpgrade.LEATHER_PACK);
        }
        if (tier >= 3) {
            state.unlockUpgrade(PostRaidUpgrade.FRAME_PACK);
        }
        if (cart) {
            state.unlockUpgrade(PostRaidUpgrade.HAND_CART);
        }
    }

    // ---- fixtures (mirrors CourierGameTests' arena) ----

    private static void buildArena(GameTestHelper helper, int size, Block floor) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), floor);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel,
                                             String name) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        Development.revisionOf(level, s);
        return s;
    }

    private static void addWarehouse(GameTestHelper helper, Settlement s,
                                     BlockPos minRel, BlockPos maxRel) {
        helper.setBlock(minRel, ModBlocks.PLAQUE.get());
        BoundingBox bounds = BoundingBox.fromCorners(
            helper.absolutePos(minRel), helper.absolutePos(maxRel));
        Building b = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(minRel), helper.absolutePos(minRel), bounds);
        b.valid = true;
        s.buildings.add(b);
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel,
                                         String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settler.assignProfession(Profession.COURIER);
        return settler;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s, BlockPos rel,
                                         Profession profession) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setNoAi(true);
        settler.bindTo(s.id, s.center);
        settler.setProfessionProjection(profession);
        s.putRecord(settler.getUUID(), profession.name(), profession);
        return settler;
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        return helper.getLevel().getBlockEntity(helper.absolutePos(rel)) instanceof Container c
            ? c : null;
    }

    private static int countIn(Container c, Item item) {
        int n = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (c.getItem(slot).is(item)) {
                n += c.getItem(slot).getCount();
            }
        }
        return n;
    }

    private static int bagCount(SettlerEntity settler) {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    private static int hearthCount(GameTestHelper helper, BlockPos rel, Item item) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel))
            instanceof HearthBlockEntity hearth)) {
            return 0;
        }
        int n = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static void fund(HearthBlockEntity hearth, PostRaidUpgrade upgrade) {
        for (DevelopmentNode.Cost cost : upgrade.costs()) {
            for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
                if (hearth.getInventory().getStackInSlot(slot).isEmpty()) {
                    hearth.getInventory().setStackInSlot(slot,
                        new ItemStack(cost.item(), cost.count()));
                    break;
                }
            }
        }
    }

    private static int totalCost(PostRaidUpgrade upgrade) {
        int n = 0;
        for (DevelopmentNode.Cost cost : upgrade.costs()) {
            n += cost.count();
        }
        return n;
    }

    private static int total(HearthBlockEntity hearth) {
        int n = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            n += hearth.getInventory().getStackInSlot(slot).getCount();
        }
        return n;
    }

    private static void clear(HearthBlockEntity hearth) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            hearth.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }
}
