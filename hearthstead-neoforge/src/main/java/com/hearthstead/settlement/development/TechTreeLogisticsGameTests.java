package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.worldevent.WorldEventDirector;
import com.hearthstead.event.worldevent.WorldEventSavedData;
import com.hearthstead.event.worldevent.WorldEventSchedule;
import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.techtree.effects.LogisticsEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Tech tree v3, Logistics branch (batch {@code techtree_logistics} plus three
 * own batches for the long delivery, the world-event calendar and the global
 * kill switch). Every test asserts the hook BEFORE the node is learned and
 * AFTER, so a node that silently does nothing fails; a second settlement
 * without the node proves the bonus is per settlement, not per player.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechTreeLogisticsGameTests {
    private static final String BATCH = "techtree_logistics";

    public TechTreeLogisticsGameTests() {
    }

    /** Coster's Cart: cart +300% (36 -> 44), Mule/Heavy Carts +400% (-> 52), tier drawn. */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void costersAndHeavyCartsGrowTheCart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(3, 1, 3), "Costerby");
        Settlement other = settlement(helper, new BlockPos(12, 1, 12), "Plainby");
        grantGear(helper, s);
        grantGear(helper, other);
        SettlerEntity courier = settler(helper, s, new BlockPos(6, 1, 6), Profession.COURIER);
        DevelopmentState state = Development.of(level, s);

        helper.assertTrue(CourierSatchel.targetCapacity(level, s) == 36
                && HaulGear.cartTier(level, s) == 1 && HaulGear.cartPercent(level, s) == 200,
            "before: Frame Pack + Hand Cart is 36 per trip on a tier 1 cart, got "
                + CourierSatchel.targetCapacity(level, s));
        // This settler's own Strength/skill bonus rides on top of every tier.
        int own = CourierSatchel.apply(level, s, courier) - 36;
        state.learnTech("costers_cart");
        int raised = CourierSatchel.apply(level, s, courier) - own;
        helper.assertTrue(raised == 44 && CourierSatchel.targetCapacity(level, s) == 44
                && HaulGear.cartTier(level, s) == 2
                && courier.cartTier() == 2 && courier.hasHandCart(),
            "Coster's Cart: 44 per trip and the tier 2 cart is drawn, got " + raised
                + " tier " + courier.cartTier());
        state.learnTech("mule_cart");
        raised = CourierSatchel.apply(level, s, courier) - own;
        helper.assertTrue(raised == 52 && HaulGear.cartTier(level, s) == 3 && courier.cartTier() == 3
                && HaulGear.cartPercent(level, s) == 400,
            "Heavy Carts: 52 per trip on a tier 3 cart, got " + raised);
        helper.assertTrue(CourierSatchel.targetCapacity(level, other) == 36
                && HaulGear.cartTier(level, other) == 1,
            "another settlement without the nodes keeps 36 (per settlement)");
        helper.succeed();
    }

    /** Heavy Carts: a hitched cart on a road rolls +10% (cart) +20% (Heavy Carts). */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void heavyCartsRollFasterOnRoads(GameTestHelper helper) {
        paveFloor(helper, Blocks.DIRT_PATH);
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(6, 1, 6), "Mulebury");
        grantGear(helper, s);
        SettlerEntity carter = settler(helper, s, new BlockPos(2, 1, 2), Profession.COURIER);
        carter.setActivity(SettlerActivity.CARRYING);
        HaulGear.onFootfall(carter);
        helper.assertTrue(HaulGear.appliedPercent(carter) == 10,
            "before: a hitched Hand Cart rolls +10% on a road, got " + HaulGear.appliedPercent(carter));
        DevelopmentState state = Development.of(level, s);
        state.learnTech("costers_cart");
        state.learnTech("mule_cart");
        HaulGear.onFootfall(carter);
        helper.assertTrue(HaulGear.appliedPercent(carter) == 30,
            "Heavy Carts: +10% cart +20% road, got " + HaulGear.appliedPercent(carter));
        carter.setActivity(SettlerActivity.IDLE);
        HaulGear.onFootfall(carter);
        helper.assertTrue(HaulGear.appliedPercent(carter) == 0,
            "a parked cart gives no road bonus, got " + HaulGear.appliedPercent(carter));
        helper.succeed();
    }

    /** Porters' Guild: the whole trip x1.5 (36 -> 54), Couriers walk 10% slower. */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void portersCarryHalfAgainAndWalkSlower(GameTestHelper helper) {
        paveFloor(helper, Blocks.GRASS_BLOCK);
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(6, 1, 6), "Portersby");
        grantGear(helper, s);
        SettlerEntity courier = settler(helper, s, new BlockPos(2, 1, 2), Profession.COURIER);
        SettlerEntity farmer = settler(helper, s, new BlockPos(4, 1, 2), Profession.FARMER);
        HaulGear.onFootfall(courier);
        int own = CourierSatchel.apply(level, s, courier) - 36; // Strength/skill extra
        helper.assertTrue(CourierSatchel.targetCapacity(level, s) == 36
                && HaulGear.appliedPercent(courier) == 0,
            "before: 36 per trip at normal pace");
        DevelopmentState state = Development.of(level, s);
        state.learnTech("porters_guild");
        int raised = CourierSatchel.apply(level, s, courier) - own;
        HaulGear.onFootfall(courier);
        HaulGear.onFootfall(farmer);
        helper.assertTrue(raised == 54 && HaulGear.appliedPercent(courier) == -10,
            "Porters: 54 per trip and -10% pace, got " + raised + " / "
                + HaulGear.appliedPercent(courier));
        helper.assertTrue(HaulGear.appliedPercent(farmer) == 0,
            "only Couriers are Porters: a Farmer keeps his pace");
        state.learnTech("costers_cart");
        helper.assertTrue(CourierSatchel.apply(level, s, courier) - own == 66
                && CourierSatchel.targetCapacity(level, s) == 66,
            "Porters with the Coster's Cart: 44 -> 66 per trip");
        helper.succeed();
    }

    /** Runners' Guild: +15% Courier pace, rest after a blocked route halved. */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void runnersWalkFasterAndRetrySooner(GameTestHelper helper) {
        paveFloor(helper, Blocks.GRASS_BLOCK);
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(6, 1, 6), "Runnersby");
        Settlement other = settlement(helper, new BlockPos(12, 1, 12), "Slowby");
        SettlerEntity courier = settler(helper, s, new BlockPos(2, 1, 2), Profession.COURIER);
        HaulGear.onFootfall(courier);
        helper.assertTrue(HaulGear.appliedPercent(courier) == 0
                && LogisticsEffects.courierRestTicks(level, s, 100) == 100
                && LogisticsEffects.courierRestTicks(level, s, 400) == 400,
            "before: normal pace and the full 5 s / 20 s rest");
        Development.of(level, s).learnTech("runners_guild");
        HaulGear.onFootfall(courier);
        helper.assertTrue(HaulGear.appliedPercent(courier) == 15,
            "Runners: +15% pace, got " + HaulGear.appliedPercent(courier));
        helper.assertTrue(LogisticsEffects.courierRestTicks(level, s, 100) == 50
                && LogisticsEffects.courierRestTicks(level, s, 400) == 200,
            "Runners: rest after a blocked route halves");
        helper.assertTrue(LogisticsEffects.courierRestTicks(level, other, 100) == 100,
            "another settlement keeps the full rest (per settlement)");
        helper.succeed();
    }

    /** Courier's Ledger: Hearth food (High) moves ahead of workshop inputs (Normal). */
    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 60)
    public void ledgerServesHighPriorityFirst(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(3, 1, 3), "Ledgerby");
        List<LogisticsEffects.Rung> before = LogisticsEffects.courierLadder(level, s);
        helper.assertTrue(before.equals(List.of(LogisticsEffects.Rung.values())),
            "before: the fixed ladder, got " + before);
        Development.of(level, s).learnTech("courier_ledger");
        List<LogisticsEffects.Rung> after = LogisticsEffects.courierLadder(level, s);
        helper.assertTrue(after.indexOf(LogisticsEffects.Rung.FOOD)
                < after.indexOf(LogisticsEffects.Rung.RESTOCK)
                && after.size() == LogisticsEffects.Rung.values().length,
            "Ledger: Hearth food before workshop inputs, got " + after);
        helper.succeed();
    }

    /**
     * A Porter with the Coster's Cart hauls all 66 planks in ONE trip and
     * every one arrives exactly once: hearth + warehouse + bag + anything
     * spilled on the ground is exactly 66 on every tick. (One trip on
     * purpose: a second trip would start after the noon meal break, which is
     * a schedule wait, not a logistics result; W19 showed 66/70 stored with
     * the other 4 conserved in hearth and bag when the timeout hit.)
     */
    @GameTest(template = "empty16", batch = "techtree_logistics_haul", timeoutTicks = 7600)
    public void portersCartDeliversABigLoadExactlyOnce(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = settlement(helper, hearthRel, "Heapby");
        grantGear(helper, s);
        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.learnTech("costers_cart");
        state.learnTech("porters_guild");
        int seeded = 66;
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, seeded));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4), "Heap Bud");
        final int[] peak = {0};
        final boolean[] created = {false};
        helper.succeedWhen(() -> {
            int load = bagCount(bud);
            peak[0] = Math.max(peak[0], load);
            helper.assertTrue(load <= bud.getCarryCapacity(),
                "carried " + load + " over capacity " + bud.getCarryCapacity());
            Container chest = helper.getLevel().getBlockEntity(helper.absolutePos(
                new BlockPos(10, 1, 10))) instanceof Container c ? c : null;
            int stored = chest == null ? 0 : countIn(chest, Items.OAK_PLANKS);
            int home = hearthCount(helper, hearthRel, Items.OAK_PLANKS);
            int spilled = 0;
            for (net.minecraft.world.entity.item.ItemEntity item : helper.getLevel().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(BlockPos.ZERO))
                        .inflate(20.0D), e -> e.getItem().is(Items.OAK_PLANKS))) {
                spilled += item.getItem().getCount();
            }
            if (stored + home + load + spilled > seeded) {
                created[0] = true; // latched: a duplicate can never "heal" into a pass
            }
            helper.assertTrue(!created[0], "items were created: stored=" + stored + " home="
                + home + " bag=" + load + " spilled=" + spilled + " seeded=" + seeded);
            helper.assertTrue(stored == seeded && home == 0 && load == 0 && spilled == 0,
                "all " + seeded + " must arrive exactly once: stored=" + stored
                    + " home=" + home + " bag=" + load + " spilled=" + spilled
                    + " (sum " + (stored + home + load + spilled) + ") peak=" + peak[0]
                    + " cap=" + bud.getCarryCapacity());
            // 66 from the tree, plus this courier's own Strength/skill extra.
            helper.assertTrue(CourierSatchel.targetCapacity(helper.getLevel(), s) == 66
                    && bud.getCarryCapacity() >= 66
                    && peak[0] == Math.min(seeded, bud.getCarryCapacity())
                    && bud.cartTier() == 2,
                "a Porter's Coster's Cart trip must fill the whole budget: cap=" + bud.getCarryCapacity()
                    + " peak=" + peak[0] + " cart=" + bud.cartTier());
        });
    }

    /**
     * Caravan Routes: a caravan is due after 3 quiet days. Settlement B (with
     * the node) gets a caravan planned; settlement A (without) cannot plan one
     * that day (the caravan's own 4-day gap), so the difference is the node.
     */
    @GameTest(template = "empty64", batch = "techtree_logistics_events", timeoutTicks = 100)
    public void caravanRoutesPlanACaravanWhenDue(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long base = Math.floorDiv(level.getDayTime(), 24000L) * 24000L;
        level.setDayTime(base + 500L); // before the caravan's start window: plan only
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        Settlement without = eventVillage(helper, "Nocaravan", new BlockPos(16, 1, 16));
        Settlement with = eventVillage(helper, "Caravanby", new BlockPos(48, 1, 48));
        WorldEventDirector.resetTransientForTests();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        BlockPos mid = helper.absolutePos(new BlockPos(32, 1, 32));
        player.teleportTo(mid.getX() + .5, mid.getY(), mid.getZ() + .5);
        WorldEventSavedData data = WorldEventSavedData.get(level);
        try {
            helper.assertTrue(LogisticsEffects.eventWeight(level, with, WorldEventType.CARAVAN) == 1.0D
                    && !LogisticsEffects.caravanRoutes(level, with),
                "before: caravans at their base weight");
            Development.of(level, with).learnTech("caravan_routes");
            helper.assertTrue(LogisticsEffects.eventWeight(level, with, WorldEventType.CARAVAN) == 3.0D
                    && LogisticsEffects.eventWeight(level, with, WorldEventType.PEDDLER) == 1.0D,
                "Caravan Routes: caravans x3, other events unchanged");
            for (Settlement s : List.of(without, with)) {
                WorldEventSavedData.Row row = data.rowOrCreate(s.id);
                row.lastDayByType.put(WorldEventType.CARAVAN, day - 3L);
                row.lastEventDay = day - 1L;
                row.plannedDay = WorldEventSchedule.NO_DAY;
                WorldEventDirector.observe(level, s, data, true);
            }
            WorldEventSavedData.Row a = data.row(without.id);
            WorldEventSavedData.Row b = data.row(with.id);
            helper.assertTrue(b.plannedDay == day && b.plannedType == WorldEventType.CARAVAN,
                "with Caravan Routes a caravan is planned after 3 days, got " + b.plannedType);
            helper.assertTrue(a.plannedDay == day && a.plannedType != WorldEventType.CARAVAN,
                "without the node no caravan inside its 4-day gap, got " + a.plannedType);
        } finally {
            for (Settlement s : List.of(without, with)) {
                WorldEventDirector.finish(level, s, "test_teardown", null);
                WorldEventSavedData.Row row = data.row(s.id);
                if (row != null) {
                    row.plannedType = null;
                }
                SettlementSavedData.get(level).settlements.remove(s.id);
            }
            player.discard();
        }
        helper.succeed();
    }

    /** [features] logisticsUpgrades=false: every node here stays learned and changes nothing. */
    @GameTest(template = "empty16", batch = "techtree_logistics_killswitch", timeoutTicks = 60)
    public void killSwitchTurnsTheNodesOff(GameTestHelper helper) {
        paveFloor(helper, Blocks.DIRT_PATH);
        ServerLevel level = helper.getLevel();
        Settlement s = settlement(helper, new BlockPos(6, 1, 6), "Offby");
        grantGear(helper, s);
        DevelopmentState state = Development.of(level, s);
        for (String id : List.of("courier_ledger", "costers_cart", "mule_cart", "porters_guild",
                "caravan_routes")) {
            state.learnTech(id);
        }
        SettlerEntity courier = settler(helper, s, new BlockPos(2, 1, 2), Profession.COURIER);
        boolean before = HearthsteadServerConfig.logisticsUpgradesEnabled();
        try {
            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(false);
            courier.setActivity(SettlerActivity.CARRYING);
            HaulGear.onFootfall(courier);
            helper.assertTrue(CourierSatchel.targetCapacity(level, s) == SettlerEntity.BASE_CARRY_CAPACITY
                    && HaulGear.cartTier(level, s) == 0 && HaulGear.appliedPercent(courier) == 0
                    && LogisticsEffects.courierLadder(level, s).equals(List.of(LogisticsEffects.Rung.values()))
                    && LogisticsEffects.eventWeight(level, s, WorldEventType.CARAVAN) == 1.0D,
                "switched off: base load, no cart, no speed, fixed ladder, base caravan weight");
            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(true);
            helper.assertTrue(CourierSatchel.targetCapacity(level, s) == 78
                    && HaulGear.cartTier(level, s) == 3,
                "switched back on: Frame Pack + Heavy Cart + Porters = 78, got "
                    + CourierSatchel.targetCapacity(level, s));
        } finally {
            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(before);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------ fixtures

    /** Frame Pack (tier 3) + Hand Cart, the gear every cart node builds on. */
    private static void grantGear(GameTestHelper helper, Settlement s) {
        DevelopmentState state = Development.of(helper.getLevel(), s);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlockUpgrade(PostRaidUpgrade.COURIER_SATCHEL);
        state.unlockUpgrade(PostRaidUpgrade.LEATHER_PACK);
        state.unlockUpgrade(PostRaidUpgrade.FRAME_PACK);
        state.unlockUpgrade(PostRaidUpgrade.HAND_CART);
    }

    private static Settlement settlement(GameTestHelper helper, BlockPos centerRel, String name) {
        ServerLevel level = helper.getLevel();
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        Development.revisionOf(level, s);
        return s;
    }

    private static Settlement eventVillage(GameTestHelper helper, String name, BlockPos centerRel) {
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(centerRel));
        s.radius = 10;
        for (int i = 0; i < 4; i++) {
            s.putRecord(UUID.randomUUID(), "Resident" + i, Profession.NONE);
        }
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        Development.revisionOf(helper.getLevel(), s);
        return s;
    }

    private static void paveFloor(GameTestHelper helper, Block floor) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 0, z), floor);
                for (int y = 1; y < 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
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
}
