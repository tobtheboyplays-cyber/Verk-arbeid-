package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.ButcheringTableBlockEntity;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.event.HunterShotEvents;
import com.hearthstead.event.HuntingGroundsSpawner;
import com.hearthstead.item.CarcassData;
import com.hearthstead.item.CarcassItem;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hunter rework: the carcass is the one physical authority for a kill's
 * loot from the arrow to the Lodge chest, and the hunting-grounds spawner
 * only ever adds game where the player is not building or looking.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class HunterCarcassGameTests {
    private record Fixture(ServerLevel level, Settlement settlement, Building lodge,
                           Container chest, SettlerEntity hunter, List<Cow> herd) {
    }

    // ------------------------------------------------------- exact once ---

    @GameTest(batch = "hunter_carcass", template = "empty16", timeoutTicks = 100)
    public void carcassReplacesTheVanillaLootExactlyOnce(GameTestHelper helper) {
        Fixture f = fixture(helper, 5, true);
        f.hunter().setNoAi(true);
        f.herd().forEach(cow -> cow.setNoAi(true));
        Cow target = f.herd().get(0);
        Arrow arrow = new Arrow(f.level(), f.hunter(), new ItemStack(Items.ARROW),
            f.hunter().getMainHandItem());
        helper.assertTrue(HunterShotEvents.issue(arrow, f.hunter(), target, f.lodge()),
            "the fifth cow must be a legal Hunter target");
        target.hurt(f.level().damageSources().arrow(arrow, f.hunter()), 100.0F);
        helper.assertTrue(!target.isAlive(), "the provenance arrow must kill the cow");
        AABB around = target.getBoundingBox().inflate(3.0D);
        List<ItemEntity> carcasses = f.level().getEntitiesOfClass(ItemEntity.class, around,
            item -> item.isAlive() && CarcassItem.isCarcass(item.getItem()));
        helper.assertTrue(carcasses.size() == 1,
            "one kill must yield exactly one carcass, saw " + carcasses.size());
        helper.assertTrue(f.level().getEntitiesOfClass(ItemEntity.class, around,
                item -> item.isAlive() && !CarcassItem.isCarcass(item.getItem())).isEmpty(),
            "no vanilla drop may land beside the carcass that replaced it");
        CarcassData data = CarcassItem.data(carcasses.get(0).getItem());
        int beef = count(data.yield(), Items.BEEF);
        int leather = count(data.yield(), Items.LEATHER);
        helper.assertTrue(data.type().orElse(null) == EntityType.COW
                && beef >= 1 && beef <= 3 && leather >= 0 && leather <= 2
                && data.yieldCount() == beef + leather,
            "the carcass records the cow's vanilla loot roll (1-3 beef, 0-2 leather); got " + data.yield());
        helper.assertTrue(f.hunter().getUUID().equals(carcasses.get(0).getTarget()),
            "the carcass is leased to the shooting Hunter");
        helper.assertTrue(carcasses.get(0).lifespan == Integer.MAX_VALUE,
            "a carcass must never despawn: it is the only copy of that loot");

        // Control: a kill that is not a Hunter shot keeps ordinary vanilla loot.
        Cow other = f.herd().get(1);
        other.hurt(f.level().damageSources().generic(), 100.0F);
        AABB otherBox = other.getBoundingBox().inflate(2.0D);
        helper.assertTrue(f.level().getEntitiesOfClass(ItemEntity.class, otherBox,
                item -> item.isAlive() && item.getItem().is(Items.BEEF)).size() >= 1,
            "a non-Hunter kill must keep its vanilla beef drop");
        helper.succeed();
    }

    // --------------------------------------------- haul, butcher, store ---

    @GameTest(batch = "hunter_haul_table", template = "empty16", timeoutTicks = 1600)
    public void huntedCarcassIsHauledButcheredAtTheTableAndStoredConserved(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, true);
        BlockPos tableRel = new BlockPos(7, 1, 7);
        helper.setBlock(tableRel, ModBlocks.BUTCHERING_TABLE.get());
        helper.assertTrue(f.lodge().contains(helper.absolutePos(tableRel)),
            "the Butchering Table must stand inside the Lodge");
        runHaulAndButcher(helper, f, helper.absolutePos(tableRel), true);
    }

    @GameTest(batch = "hunter_haul_floor", template = "empty16", timeoutTicks = 1800)
    public void lodgeWithoutATableDressesTheCarcassOnTheFloorConserved(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, true);
        runHaulAndButcher(helper, f, null, false);
    }

    private static void runHaulAndButcher(GameTestHelper helper, Fixture f, BlockPos table,
                                          boolean expectTable) {
        f.level().setDayTime(3000);
        CarcassData data = CarcassData.of(EntityType.COW,
            List.of(new ItemStack(Items.BEEF, 2), new ItemStack(Items.LEATHER)));
        BlockPos dropRel = new BlockPos(11, 1, 11);
        BlockPos drop = helper.absolutePos(dropRel);
        ItemEntity loose = new ItemEntity(f.level(), drop.getX() + 0.5D, drop.getY() + 0.1D,
            drop.getZ() + 0.5D, CarcassItem.create(data));
        loose.setDeltaMovement(0.0D, 0.0D, 0.0D);
        helper.assertTrue(f.level().addFreshEntity(loose), "fixture carcass must enter the world");
        // Dropped at the kill, as HunterShotEvents does: leased to this Hunter.
        helper.assertTrue(GroundCollectionSession.leaseExisting(f.hunter(), loose),
            "the fixture carcass takes the shooter's ordinary pickup lease");
        boolean[] seen = new boolean[4]; // haul, skin, butcher, table
        helper.onEachTick(() -> {
            SettlerActivity activity = f.hunter().getActivity();
            seen[0] |= activity == SettlerActivity.HAULING_CARCASS
                && CarcassItem.isCarcass(f.hunter().getOffhandItem());
            seen[1] |= activity == SettlerActivity.WORK_SKIN;
            seen[2] |= activity == SettlerActivity.WORK_BUTCHER;
            if (table != null && f.level().getBlockEntity(table) instanceof ButcheringTableBlockEntity bench
                && bench.hasCarcass()) {
                seen[3] = true;
            }
            int carcasses = carcassesEverywhere(helper, f, table);
            int yield = yieldEverywhere(helper, f);
            if (carcasses > 1 || (carcasses == 1 && yield != 0) || (carcasses == 0 && yield != 3)) {
                helper.fail("conservation broken: carcasses=" + carcasses + " yieldUnits=" + yield
                    + " activity=" + activity);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(count(f.chest(), Items.BEEF) == 2 && count(f.chest(), Items.LEATHER) == 1,
                "the Lodge chest must end with exactly the carcass's 2 beef and 1 leather; chest beef="
                    + count(f.chest(), Items.BEEF) + " leather=" + count(f.chest(), Items.LEATHER)
                    + " activity=" + f.hunter().getActivity() + " route=" + f.hunter().routeFailureNote()
                    + " offhand=" + f.hunter().getOffhandItem() + " pos=" + f.hunter().blockPosition());
            helper.assertTrue(bagUnits(f.hunter()) == 0 && carcassesEverywhere(helper, f, table) == 0,
                "nothing may remain in the bag and no carcass may survive its butchering");
            helper.assertTrue(seen[0] && seen[1] && seen[2],
                "the Hunter must be seen hauling, skinning and butchering; saw haul=" + seen[0]
                    + " skin=" + seen[1] + " butcher=" + seen[2]);
            helper.assertTrue(!expectTable || seen[3],
                "with a table the carcass must physically lie on it while worked");
        });
    }

    // ------------------------------------------------- death & interrupt ---

    @GameTest(batch = "hunter_carcass", template = "empty16", timeoutTicks = 100)
    public void carriedCarcassSurvivesTheHuntersDeath(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, true);
        f.hunter().setNoAi(true);
        CarcassData data = CarcassData.of(EntityType.PIG, List.of(new ItemStack(Items.PORKCHOP, 3)));
        GroundCollectionSession session = new GroundCollectionSession(f.hunter(),
            CarcassItem::isCarcass, 4);
        helper.assertTrue(session.acceptHandoff(CarcassItem.create(data)),
            "fixture must put one session-owned carcass on the Hunter's shoulders");
        f.hunter().hurt(f.level().damageSources().genericKill(), 1000.0F);
        helper.assertTrue(!f.hunter().isAlive(), "the Hunter must die");
        helper.succeedWhen(() -> {
            List<ItemEntity> dropped = f.level().getEntitiesOfClass(ItemEntity.class,
                f.hunter().getBoundingBox().inflate(4.0D),
                item -> item.isAlive() && CarcassItem.isCarcass(item.getItem()));
            helper.assertTrue(dropped.size() == 1 && data.equals(CarcassItem.data(dropped.get(0).getItem())),
                "the dead Hunter's carcass must become exactly one real item with its yield intact");
            helper.assertTrue(f.hunter().getOffhandItem().isEmpty(),
                "the corpse may not keep a second copy");
        });
    }

    @GameTest(batch = "hunter_carcass", template = "empty16", timeoutTicks = 100)
    public void interruptedHaulSetsTheCarcassDownAndTheHunterReclaimsIt(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, true);
        f.hunter().setNoAi(true);
        f.level().setDayTime(3000);
        CarcassData data = CarcassData.of(EntityType.CHICKEN,
            List.of(new ItemStack(Items.CHICKEN), new ItemStack(Items.FEATHER, 2)));
        f.hunter().setItemSlot(EquipmentSlot.OFFHAND, CarcassItem.create(data));
        f.hunter().getPersistentData().putBoolean(
            GroundCollectionSession.PERSISTENT_OFFHAND_OWNERSHIP_TAG, true);
        HunterWorkGoal haul = new HunterWorkGoal(f.hunter());
        helper.assertTrue(haul.canUse(), "a carcass on the shoulders must start the haul home");
        haul.start();
        haul.tick();
        helper.assertTrue(f.hunter().getActivity() == SettlerActivity.HAULING_CARCASS,
            "the sheet must read 'Hauling a carcass home'; was " + f.hunter().getActivity());
        // A raid alarm / meal / bedtime preempts the work goal.
        haul.stop();
        List<ItemEntity> set = f.level().getEntitiesOfClass(ItemEntity.class,
            f.hunter().getBoundingBox().inflate(2.0D),
            item -> item.isAlive() && CarcassItem.isCarcass(item.getItem()));
        helper.assertTrue(set.size() == 1 && f.hunter().getOffhandItem().isEmpty(),
            "an interruption sets the carcass down as exactly one real item, never deletes it");
        helper.assertTrue(f.hunter().getUUID().equals(set.get(0).getTarget()),
            "the set-down carcass stays leased to the Hunter");
        HunterWorkGoal resume = new HunterWorkGoal(f.hunter());
        helper.assertTrue(resume.canUse(), "the Hunter must go back for his own carcass");
        helper.succeed();
    }

    @GameTest(batch = "hunter_carcass", template = "empty16", timeoutTicks = 100)
    public void looseUnownedCarcassInTheGroundsIsReclaimedNotDuplicated(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, true);
        f.hunter().setNoAi(true);
        f.level().setDayTime(3000);
        BlockPos at = helper.absolutePos(new BlockPos(12, 1, 12));
        ItemEntity loose = new ItemEntity(f.level(), at.getX() + 0.5D, at.getY() + 0.1D, at.getZ() + 0.5D,
            CarcassItem.create(CarcassData.of(EntityType.RABBIT, List.of(new ItemStack(Items.RABBIT)))));
        helper.assertTrue(f.level().addFreshEntity(loose), "a player-dropped carcass lies in the grounds");
        HunterWorkGoal goal = new HunterWorkGoal(f.hunter());
        helper.assertTrue(goal.canUse(), "the Hunter must go for a loose carcass in his grounds");
        helper.assertTrue(f.hunter().getUUID().equals(loose.getTarget()) && loose.isAlive(),
            "claiming leases the one real item; it is not copied or moved yet");
        helper.assertTrue(f.level().getEntitiesOfClass(ItemEntity.class, helper.getBounds(),
                item -> item.isAlive() && CarcassItem.isCarcass(item.getItem())).size() == 1,
            "still exactly one carcass");
        helper.succeed();
    }

    /**
     * Soak finding (26 Sep): a drop the Hunter could path beside but never
     * pick up was released and re-claimed forever (STUCK 40% of the work
     * phase). Now one drop gets a bounded route; then it is set aside, still
     * a real unowned item, and the same Hunter does not re-claim it.
     */
    @GameTest(batch = "hunter_carcass", template = "empty16", timeoutTicks = 600)
    public void unreachableCarcassIsSetAsideNotChasedForever(GameTestHelper helper) {
        Fixture f = fixture(helper, 0, true);
        f.hunter().setNoAi(true); // cannot move: the drop is unreachable by construction
        f.level().setDayTime(3000);
        BlockPos at = helper.absolutePos(new BlockPos(12, 1, 12));
        ItemEntity loose = new ItemEntity(f.level(), at.getX() + 0.5D, at.getY() + 0.1D, at.getZ() + 0.5D,
            CarcassItem.create(CarcassData.of(EntityType.PIG, List.of(new ItemStack(Items.PORKCHOP)))));
        loose.setDeltaMovement(0.0D, 0.0D, 0.0D);
        helper.assertTrue(f.level().addFreshEntity(loose), "fixture carcass must enter the world");
        HunterWorkGoal goal = new HunterWorkGoal(f.hunter());
        helper.assertTrue(goal.canUse(), "the Hunter first goes for the loose carcass");
        goal.start();
        long started = f.level().getGameTime();
        boolean[] gaveUp = {false};
        long[] gaveUpAt = {0L};
        helper.onEachTick(() -> {
            if (!gaveUp[0]) {
                if (goal.canContinueToUse()) {
                    goal.tick();
                } else {
                    goal.stop();
                    gaveUp[0] = true;
                    gaveUpAt[0] = f.level().getGameTime();
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(gaveUp[0], "the route must end within its bound");
            helper.assertTrue(gaveUpAt[0] - started <= 360,
                "give-up must come within the route bound, took " + (gaveUpAt[0] - started));
            helper.assertTrue(f.level().getGameTime() - gaveUpAt[0] > 60,
                "wait past the carcass-scan throttle before re-checking");
            helper.assertTrue(!goal.canUse() && loose.isAlive() && loose.getTarget() == null,
                "the set-aside carcass stays one real unowned item and is not re-claimed");
        });
    }

    // ------------------------------------------------ courier onward flow ---

    @GameTest(batch = "hunter_courier", template = "empty16", timeoutTicks = 3600)
    public void courierCollectsLodgeMeatButNeverTheCarcass(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        for (int x = 0; x < 14; x++) for (int z = 0; z < 14; z++) {
            boolean rim = x == 0 || z == 0 || x == 13 || z == 13;
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(x, y, z), rim && y <= 2
                    ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        SettlementSavedData data = SettlementManager.data(level);
        Settlement s = new Settlement(UUID.randomUUID(), "Jaktholm", helper.absolutePos(hearthRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        if (level.getBlockEntity(helper.absolutePos(hearthRel)) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        GameTestFixtures.registerWithBounds(helper, s, BuildingType.WAREHOUSE, new BlockPos(4, 1, 2),
            new BlockPos(4, 1, 2), BoundingBox.fromCorners(helper.absolutePos(new BlockPos(4, 1, 2)),
                helper.absolutePos(new BlockPos(6, 3, 4))));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        GameTestFixtures.register(helper, s, BuildingType.HUNTERS_LODGE, 8, 2);
        BlockPos lodgeChestRel = new BlockPos(9, 1, 3);
        helper.setBlock(lodgeChestRel, Blocks.CHEST);
        Container lodgeChest = container(helper, lodgeChestRel);
        lodgeChest.setItem(0, new ItemStack(Items.BEEF, 3));
        ItemStack carcass = CarcassItem.create(CarcassData.of(EntityType.SHEEP,
            List.of(new ItemStack(Items.MUTTON), new ItemStack(Items.WHITE_WOOL))));
        lodgeChest.setItem(1, carcass);
        SettlerEntity courier = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 10));
        courier.setSettlerName("Bud");
        courier.bindTo(s.id, s.center);
        s.putRecord(courier.getUUID(), "Bud", Profession.NONE);
        Building warehouse = s.buildings.stream().filter(b -> b.type == BuildingType.WAREHOUSE)
            .findFirst().orElseThrow();
        helper.assertTrue(Employment.hire(level, s, warehouse, courier).ok(),
            "the fixture courier must be employed at the warehouse");
        helper.succeedWhen(() -> {
            Container warehouseChest = container(helper, warehouseChestRel);
            int beef = count(warehouseChest, Items.BEEF) + count(lodgeChest, Items.BEEF)
                + bagCount(courier, Items.BEEF);
            helper.assertTrue(beef == 3, "beef must be conserved, saw " + beef);
            helper.assertTrue(count(warehouseChest, Items.BEEF) == 3,
                "the Courier must carry the Lodge's butchered beef on to the warehouse; warehouse="
                    + count(warehouseChest, Items.BEEF) + " route=" + courier.routeFailureNote());
            helper.assertTrue(countCarcass(lodgeChest) == 1 && countCarcass(warehouseChest) == 0
                    && !CarcassItem.isCarcass(courier.getOffhandItem()),
                "an unbutchered carcass is Hunter work, never Courier output");
        });
    }

    // ------------------------------------------------- hunting grounds ---

    @GameTest(batch = "hunting_grounds", template = "empty64", skyAccess = true, timeoutTicks = 100)
    public void huntingGroundsSpawnOnlyOnOpenNaturalGroundAwayFromBuildsAndPlayers(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(6000);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        clearStaleSettlements(helper, data);
        Settlement s = new Settlement(UUID.randomUUID(), "Viltmark",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        data.settlements.put(s.id, s);
        data.setDirty();
        Building lodge = GameTestFixtures.register(helper, s, BuildingType.HUNTERS_LODGE, 30, 30);
        RandomSource random = RandomSource.create(42L);
        BlockPos good = helper.absolutePos(new BlockPos(46, 1, 46));
        List<double[]> nobody = List.of();

        helper.assertTrue(HuntingGroundsSpawner.tryLodge(level, data, s, lodge, random, true)
                == HuntingGroundsSpawner.Outcome.NO_HUNTER,
            "no game returns to a Lodge without an employed Hunter");
        helper.assertTrue(HuntingGroundsSpawner.placementAllowed(level, data, good, nobody),
            "open grass in the hunting grounds, far from builds, must qualify");
        // The village core around the Hearth.
        helper.assertTrue(!HuntingGroundsSpawner.placementAllowed(level, data,
                helper.absolutePos(new BlockPos(10, 1, 10)), nobody),
            "the village core (16 blocks around the Hearth) never receives game");
        // Next to a building (4-block margin around the Lodge's bounds).
        helper.assertTrue(!HuntingGroundsSpawner.placementAllowed(level, data,
                helper.absolutePos(new BlockPos(36, 1, 32)), nobody),
            "game never spawns within 4 blocks of a building");
        // Under a roof / inside walls.
        BlockPos roof = good.above(3);
        level.setBlockAndUpdate(roof, Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(!HuntingGroundsSpawner.placementAllowed(level, data, good, nobody),
            "a roofed cell is a player build, not open ground");
        level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(good, Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(!HuntingGroundsSpawner.placementAllowed(level, data, good, nobody),
            "a solid feet cell is a wall, never a spawn");
        level.setBlockAndUpdate(good, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(good.below(), Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(!HuntingGroundsSpawner.placementAllowed(level, data, good, nobody),
            "plank flooring is a player build, not natural ground");
        level.setBlockAndUpdate(good.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        // Out of players' direct view.
        helper.assertTrue(!HuntingGroundsSpawner.placementAllowed(level, data, good,
                List.<double[]>of(new double[] {good.getX() + 10.0D, good.getY(), good.getZ()})),
            "game never appears within 24 blocks of a player");
        helper.assertTrue(HuntingGroundsSpawner.placementAllowed(level, data, good,
                List.<double[]>of(new double[] {good.getX() + 30.0D, good.getY(), good.getZ()})),
            "a player 30 blocks away does not block the spawn");

        // Employ a Hunter: exactly one cow returns on the good cell.
        SettlerEntity hunter = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(31, 1, 31));
        hunter.setNoAi(true);
        hunter.bindTo(s.id, s.center);
        s.putRecord(hunter.getUUID(), "Jeger", Profession.NONE);
        helper.assertTrue(Employment.hire(level, s, lodge, hunter).ok(), "hunter employment");
        HuntingGroundsSpawner.resetInterval(level, lodge.id);
        helper.assertTrue(HuntingGroundsSpawner.attemptAt(level, data, lodge, good, EntityType.COW,
                random, nobody),
            "a cow must spawn on the qualifying cell");
        List<Cow> spawned = level.getEntitiesOfClass(Cow.class, new AABB(good).inflate(1.0D),
            cow -> cow.getPersistentData().getBoolean(HuntingGroundsSpawner.SPAWNED_TAG));
        helper.assertTrue(spawned.size() == 1 && !spawned.get(0).isBaby(),
            "exactly one adult hunting-ground cow must exist");
        helper.assertTrue(HuntingGroundsSpawner.tryLodge(level, data, s, lodge, random, false)
                == HuntingGroundsSpawner.Outcome.NOT_DUE,
            "at most one animal per Lodge per spawn interval");
        helper.succeed();
    }

    @GameTest(batch = "hunting_grounds", template = "empty64", skyAccess = true, timeoutTicks = 100)
    public void huntingGroundsStopAtTheGameCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(6000);
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        clearStaleSettlements(helper, data);
        Settlement s = new Settlement(UUID.randomUUID(), "Kvoteby",
            helper.absolutePos(new BlockPos(2, 1, 2)));
        data.settlements.put(s.id, s);
        data.setDirty();
        Building lodge = GameTestFixtures.register(helper, s, BuildingType.HUNTERS_LODGE, 30, 30);
        SettlerEntity hunter = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(31, 1, 31));
        hunter.setNoAi(true);
        hunter.bindTo(s.id, s.center);
        s.putRecord(hunter.getUUID(), "Jeger", Profession.NONE);
        helper.assertTrue(Employment.hire(level, s, lodge, hunter).ok(), "hunter employment");
        List<Animal> herd = new ArrayList<>();
        for (int i = 0; i < com.hearthstead.HearthsteadServerConfig.huntingGameCap(); i++) {
            Animal animal = helper.spawn(EntityType.SHEEP, new BlockPos(40 + i, 1, 44));
            animal.setNoAi(true);
            herd.add(animal);
        }
        helper.assertTrue(HuntingGroundsSpawner.tryLodge(level, data, s, lodge, RandomSource.create(7L), true)
                == HuntingGroundsSpawner.Outcome.AT_CAP,
            "a full hunting ground adds nothing");
        herd.get(0).discard();
        HuntingGroundsSpawner.Outcome below = HuntingGroundsSpawner.tryLodge(level, data, s, lodge,
            RandomSource.create(7L), true);
        helper.assertTrue(below != HuntingGroundsSpawner.Outcome.AT_CAP,
            "one below the cap the spawner may try again, got " + below);
        helper.succeed();
    }

    // ------------------------------------------------------------ fixture ---

    private static Fixture fixture(GameTestHelper helper, int cows, boolean bow) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Jegerby",
            helper.absolutePos(new BlockPos(4, 1, 4)));
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        Building lodge = GameTestFixtures.register(helper, settlement, BuildingType.HUNTERS_LODGE, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = container(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "Hunter Lodge fixture must have one physical chest");
        SettlerEntity hunter = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 6));
        hunter.bindTo(settlement.id, settlement.center);
        settlement.putRecord(hunter.getUUID(), "Hunter", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, lodge, hunter).ok(),
            "fixture must create exact Lodge employment");
        if (bow) {
            hunter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            EquipmentRequests.refreshFor(level, settlement, lodge, hunter);
        }
        List<Cow> herd = new ArrayList<>();
        int[][] positions = {{10, 10}, {11, 9}, {9, 11}, {12, 11}, {10, 13}};
        for (int i = 0; i < cows; i++) {
            herd.add(helper.spawn(EntityType.COW, new BlockPos(positions[i][0], 1, positions[i][1])));
        }
        return new Fixture(level, settlement, lodge, chest, hunter, herd);
    }

    /** Earlier batches' fixture settlements may sit on this grid slot; they are not ours. */
    private static void clearStaleSettlements(GameTestHelper helper, SettlementSavedData data) {
        AABB arena = helper.getBounds();
        data.settlements.values().removeIf(old -> old.center != null
            && arena.contains(old.center.getX() + 0.5D, old.center.getY() + 0.5D, old.center.getZ() + 0.5D));
    }

    private static Container container(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) total += container.getItem(slot).getCount();
        }
        return total;
    }

    private static int count(List<ItemStack> stacks, Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int countCarcass(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (CarcassItem.isCarcass(container.getItem(slot))) total++;
        }
        return total;
    }

    private static int bagCount(SettlerEntity settler, Item item) {
        return count(settler.bag, item);
    }

    private static int bagUnits(SettlerEntity settler) {
        int total = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            total += settler.bag.getItem(slot).getCount();
        }
        return total;
    }

    /** Every place a carcass can physically be during the loop. */
    private static int carcassesEverywhere(GameTestHelper helper, Fixture f, BlockPos table) {
        int total = CarcassItem.isCarcass(f.hunter().getOffhandItem()) ? 1 : 0;
        total += countCarcass(f.chest());
        total += countCarcass(f.hunter().bag);
        if (table != null && f.level().getBlockEntity(table) instanceof ButcheringTableBlockEntity bench
            && bench.hasCarcass()) {
            total++;
        }
        total += f.level().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(2.0D), item -> item.isAlive() && CarcassItem.isCarcass(item.getItem()))
            .size();
        return total;
    }

    /** Beef + leather units anywhere they may legally be (chest, bag, ground). */
    private static int yieldEverywhere(GameTestHelper helper, Fixture f) {
        int total = count(f.chest(), Items.BEEF) + count(f.chest(), Items.LEATHER)
            + bagCount(f.hunter(), Items.BEEF) + bagCount(f.hunter(), Items.LEATHER);
        for (ItemEntity item : f.level().getEntitiesOfClass(ItemEntity.class,
                helper.getBounds().inflate(2.0D), ItemEntity::isAlive)) {
            if (item.getItem().is(Items.BEEF) || item.getItem().is(Items.LEATHER)) {
                total += item.getItem().getCount();
            }
        }
        if (f.hunter().getOffhandItem().is(Items.BEEF) || f.hunter().getOffhandItem().is(Items.LEATHER)) {
            total += f.hunter().getOffhandItem().getCount();
        }
        return total;
    }
}
