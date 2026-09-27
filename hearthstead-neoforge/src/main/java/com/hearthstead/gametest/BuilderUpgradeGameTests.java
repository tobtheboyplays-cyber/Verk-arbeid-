package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.BuilderNetwork;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintLibrary;
import com.hearthstead.settlement.builder.BlueprintMeta;
import com.hearthstead.settlement.builder.BuildJob;
import com.hearthstead.settlement.builder.BuildJobs;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import com.hearthstead.settlement.builder.BuildStatus;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.hearthstead.settlement.builder.UpgradePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owner's fear, 26 Sep: "builder level up". Every test here raises a real
 * building's checklist level THROUGH THE BUILDER -- an Upgrade Order is
 * planned from the plaque's gap, the Builder carries and sets the pieces,
 * the plaque re-surveys and the level rises. Nothing sets a level by hand.
 * The buildings are player-built (their blocks placed directly, the plan
 * fitted by the player), so every test also proves the Builder never
 * touches a block the player placed. Batch {@code builder_upgrade}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class BuilderUpgradeGameTests {

    private static final BlockPos SITE = new BlockPos(8, 1, 8);

    // ---------------------------------------------------------- fixtures ---

    /**
     * A 7x7 player house: cobble floor, three plank courses around a 5x5x3
     * room (volume 75), plank roof, a door, a bed, one lantern and the
     * plaque on the inside of the east wall. Level 1, and room enough to be
     * furnished up to the Manor (level 4) by Upgrade Orders alone.
     */
    static Blueprint manorShell() {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BuilderTestKit.Cells c = new BuilderTestKit.Cells()
            .box(0, 0, 0, 6, 0, 6, Blocks.COBBLESTONE.defaultBlockState())
            .box(0, 1, 0, 6, 3, 6, planks)
            .box(1, 1, 1, 5, 3, 5, Blocks.AIR.defaultBlockState())
            .box(0, 4, 0, 6, 4, 6, planks);
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        c.set(3, 1, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        c.set(3, 2, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        c.set(1, 1, 5, bed.setValue(BedBlock.PART, BedPart.FOOT));
        c.set(2, 1, 5, bed.setValue(BedBlock.PART, BedPart.HEAD));
        c.set(5, 1, 1, Blocks.LANTERN.defaultBlockState());
        c.set(5, 2, 3, ModBlocks.PLAQUE.get().defaultBlockState().setValue(PlaqueBlock.FACING, Direction.WEST));
        return new Blueprint(BuilderTestKit.meta("test_manor_shell", BlueprintMeta.Kind.BUILDING, "house",
            new int[]{5, 2, 3}, "west"), 7, 5, 7, c.list());
    }

    /** Places a building's blocks directly (the player's own work) and fits its plan. */
    static Building standUp(GameTestHelper helper, BuilderTestKit.Arena arena, Blueprint blueprint,
                            BlockPos origin, BuildingType type, Map<BlockPos, BlockState> playerBlocks) {
        BlockPos plaqueRel = null;
        BlockState plaqueState = null;
        for (Blueprint.Cell cell : blueprint.cells()) {
            BlockPos rel = origin.offset(cell.x(), cell.y(), cell.z());
            if (cell.state().is(ModBlocks.PLAQUE.get())) {
                plaqueRel = rel;
                plaqueState = cell.state();
                continue;
            }
            helper.setBlock(rel, cell.state());
        }
        helper.assertTrue(plaqueRel != null, blueprint.id() + " has a plaque");
        helper.setBlock(plaqueRel, plaqueState);
        for (Blueprint.Cell cell : blueprint.cells()) {
            BlockPos abs = helper.absolutePos(origin.offset(cell.x(), cell.y(), cell.z()));
            BlockState now = arena.level().getBlockState(abs);
            if (!now.isAir() && !now.is(ModBlocks.PLAQUE.get())) {
                playerBlocks.put(abs, now);
            }
        }
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) arena.level().getBlockEntity(helper.absolutePos(plaqueRel));
        helper.assertTrue(plaque != null && plaque.insertPlan(arena.level(),
            PlaqueItemData.stamped(new ItemStack(ModItems.BUILD_PLAN.get()), type)),
            "the player fits a " + type.id() + " plan into " + blueprint.id());
        Building building = plaque.building(arena.level());
        helper.assertTrue(building != null && building.valid,
            blueprint.id() + " registers as a valid " + type.id() + " at level 1: "
                + (building == null ? "none" : "valid=" + building.valid + " gap=" + building.nextLevelGap));
        return building;
    }

    /** Drives Upgrade Orders one level at a time, the way a player orders them from the Plan. */
    static final class Climber {
        UUID job;
        int orders;
        int lastLevel;
        final List<String> log = new ArrayList<>();
        final Map<Item, Integer> used = new HashMap<>();
        boolean stockOrders = true;
        BuilderTestKit.Arena arena;

        String report(Building building) {
            return "level " + building.level + " after " + orders + " orders; gap " + building.nextLevelGap + "; " + log
                + " last order: " + lastOrder();
        }

        /** Every step of the last order: where, what, done/skipped and why (W36d barracks lantern). */
        private String lastOrder() {
            BuildJob live = arena == null || job == null ? null : BuilderTestKit.job(arena.level(), arena.settlement(), job);
            if (live == null) {
                return "none";
            }
            StringBuilder out = new StringBuilder(live.state + " " + live.status + " " + live.statusArgs + " [");
            for (int i = 0; i < live.size() && i < 24; i++) {
                BlockPos p = live.pos(i);
                out.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(' ')
                    .append(com.hearthstead.settlement.builder.Blueprint.idOf(live.state(i)))
                    .append(live.isDone(i) ? " done" : live.isSkipped(i) ? " SKIP" : " open")
                    .append(" now=").append(com.hearthstead.settlement.builder.Blueprint.idOf(
                        arena.level().getBlockState(p))).append("; ");
            }
            return out.append("] why=").append(live.skipReport()).toString();
        }
    }

    static void climb(GameTestHelper helper, BuilderTestKit.Arena arena, Building building, int target, Climber c) {
        c.lastLevel = building.level;
        c.arena = arena;
        helper.onEachTick(() -> {
            if (building.level >= target) {
                return;
            }
            BuildJob live = c.job == null ? null : BuilderTestKit.job(arena.level(), arena.settlement(), c.job);
            if (live != null && live.state == BuildJob.State.ACTIVE) {
                return;
            }
            if (live != null && building.level == c.lastLevel && live.state == BuildJob.State.COMPLETE) {
                // The order finished but the level did not rise: report it once.
                if (!c.log.contains("order " + c.orders + " finished without a level")) {
                    c.log.add("order " + c.orders + " finished without a level");
                }
            }
            c.lastLevel = building.level;
            if (c.orders >= target * 2) {
                return; // bounded: never an endless order loop
            }
            // The Plan screen's own gate, exactly as in game.
            String lock = BuilderNetwork.upgradeRefusal(arena.level(), arena.settlement(), building);
            if (lock != null) {
                c.log.add("refused " + lock);
                c.orders = target * 2;
                return;
            }
            UpgradePlanner.Result result = UpgradePlanner.plan(arena.level(), arena.settlement(), building, null);
            if (result == null || result.plan().job() == null) {
                c.log.add("no order at level " + building.level + ": "
                    + (result == null ? "null" : result.plan().validation().reasonKey() + " hand " + result.handOnly()));
                c.orders = target * 2;
                return;
            }
            BuildJob job = result.plan().job();
            helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "order queued");
            Map<Item, Integer> bill = BuilderMaterials.total(job);
            if (c.stockOrders) {
                for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                    BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
                }
            }
            bill.forEach((k, v) -> c.used.merge(k, v, Integer::sum));
            c.job = job.id;
            c.orders++;
            c.log.add("L" + building.level + "->order " + job.size() + " steps, hand " + result.handOnly().size());
        });
    }

    /**
     * The town has researched the Builder's Hut (real tech-tree path) and,
     * unless {@code hutAtLevelOne}, its hut stands at level 2 -- the level
     * Upgrade Orders need. The hut's own climb to level 2 is proven by
     * {@link #buildersHutOrdersItsOwnFirstUpgrade}, which keeps it at 1.
     */
    static void upgradeReady(GameTestHelper helper, BuilderTestKit.Arena arena, boolean hutAtLevelOne) {
        // No hut levels (owner, 26 Sep): every upgrade test runs with the
        // level-1 hut a fresh world has.
        BuilderTestKit.researchBuildersHut(helper, arena, new BlockPos(1, 1, 1));
        helper.assertTrue(arena.hut().level == 1, "a fresh level-1 hut");
    }

    /** No block the player placed was removed or changed (the Builder only ADDS). */
    static void assertUntouched(GameTestHelper helper, BuilderTestKit.Arena arena, Map<BlockPos, BlockState> player) {
        for (Map.Entry<BlockPos, BlockState> e : player.entrySet()) {
            BlockState now = arena.level().getBlockState(e.getKey());
            // Strict (Codex review): no exception for path decay. The shipped
            // blueprints never put a solid block on a path any more
            // (BlueprintGroundDecayTest), so a path turned to dirt here means
            // the Builder set something solid on the player's path.
            boolean same = now.getBlock() == e.getValue().getBlock();
            helper.assertTrue(same, "the player's " + e.getValue().getBlock() + " at " + e.getKey()
                + " is untouched, found " + now);
        }
    }

    static void assertBillUsed(GameTestHelper helper, BuilderTestKit.Arena arena, Climber c) {
        for (Map.Entry<Item, Integer> e : c.used.entrySet()) {
            helper.assertTrue(BuilderTestKit.count(arena.chest(), e.getKey()) == 0,
                "exactly the orders' bill was used: " + e.getKey() + " left " + BuilderTestKit.count(arena.chest(), e.getKey()));
        }
    }

    // ------------------------------------------------------------- tests ---

    /** P1 (native film 26 Sep): no hut level gates anything; only research does. */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "builder_upgrade")
    public void aLevelOneHutTakesUpgradeOrdersAndLockedResearchStillRefuses(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        var level = arena.level();
        var s = arena.settlement();
        Blueprint cottage = BlueprintLibrary.get(level.getServer(), "house_cottage");
        helper.assertTrue(cottage != null, "house_cottage loads");
        // Before the research: refused, both for orders and for blueprints.
        helper.assertTrue("hearthstead.builder.lock.builders_hut".equals(BuilderNetwork.upgradeRefusal(level, s, null)),
            "without the research an Upgrade Order is refused: " + BuilderNetwork.upgradeRefusal(level, s, null));
        helper.assertTrue(com.hearthstead.settlement.builder.BuilderUnlocks.blueprintLock(level, s, cottage) != null,
            "without the research the Cottage is locked");
        upgradeReady(helper, arena, true);
        Map<BlockPos, BlockState> player = new HashMap<>();
        Building house = standUp(helper, arena, manorShell(), SITE, BuildingType.HOUSE, player);
        helper.assertTrue(com.hearthstead.settlement.builder.BuilderUnlocks.hutLevel(s) == 1, "the hut is level 1");
        helper.assertTrue(BuilderNetwork.upgradeRefusal(level, s, house) == null,
            "a level-1 hut takes a House L1->L2 Upgrade Order: " + BuilderNetwork.upgradeRefusal(level, s, house));
        // The open path (research done, L1 hut, the Plan's PLACE) is proven end
        // to end by scenario_blueprint_build_l1plan_house_* (W38a green).
        for (String id : List.of("house_cottage", "house_rustic", "house_timber", "house_stone", "house_two_storey")) {
            Blueprint b = BlueprintLibrary.get(level.getServer(), id);
            helper.assertTrue(b != null && com.hearthstead.settlement.builder.BuilderUnlocks.sizeLock(s, b.sizeX(),
                b.sizeZ(), 4096) == null, id + " is never too big for a level-1 hut");
        }
        helper.succeed();
    }

    /** House L1 -> L2 -> L3 -> L4 (Manor): three real Upgrade Orders, nothing of the player's touched. */
    @GameTest(template = "empty16", timeoutTicks = 16000, batch = "builder_upgrade")
    public void aPlayerHouseClimbsToManorThroughUpgradeOrders(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        upgradeReady(helper, arena, false);
        Map<BlockPos, BlockState> player = new HashMap<>();
        Building house = standUp(helper, arena, manorShell(), SITE, BuildingType.HOUSE, player);
        helper.assertTrue(house.level == 1, "starts at level 1, is " + house.level);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Ragnhild");
        Climber c = new Climber();
        climb(helper, arena, house, 4, c);
        helper.succeedWhen(() -> {
            helper.assertTrue(house.level == 4, "the house reaches the Manor (level 4): " + c.report(house));
            helper.assertTrue(house.valid, "still a valid house");
            assertUntouched(helper, arena, player);
            assertBillUsed(helper, arena, c);
        });
    }

    /** The first Upgrade Order of each levelled building type, on its real Elmfield blueprint. */
    private static void firstUpgrade(GameTestHelper helper, String blueprintId, BuildingType type) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 32, 12);
        upgradeReady(helper, arena, type == BuildingType.BUILDERS_HUT);
        Blueprint blueprint = BlueprintLibrary.get(arena.level().getServer(), blueprintId);
        helper.assertTrue(blueprint != null, blueprintId + " loads");
        Map<BlockPos, BlockState> player = new HashMap<>();
        Building building = standUp(helper, arena, blueprint, SITE, type, player);
        int start = building.level;
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Asgeir");
        Climber c = new Climber();
        climb(helper, arena, building, start + 1, c);
        helper.succeedWhen(() -> {
            helper.assertTrue(building.level == start + 1,
                blueprintId + " rises one level through an Upgrade Order: " + c.report(building));
            if (type == BuildingType.LUMBER_CAMP || type == BuildingType.FARMHOUSE) {
                helper.assertTrue(building.workerCapacity() == type.workerCapacity() + 1,
                    "level 2 takes one more worker: " + building.workerCapacity());
            }
            assertUntouched(helper, arena, player);
            assertBillUsed(helper, arena, c);
        });
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void warehouseSmallFirstUpgrade(GameTestHelper helper) {
        firstUpgrade(helper, "warehouse_small", BuildingType.WAREHOUSE);
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void barracksSmallFirstUpgrade(GameTestHelper helper) {
        firstUpgrade(helper, "barracks_small", BuildingType.BARRACKS);
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void tavernSmallFirstUpgrade(GameTestHelper helper) {
        firstUpgrade(helper, "tavern_small", BuildingType.TAVERN);
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void lumberCampSmallReachesLevelTwoAndTakesAThirdWorker(GameTestHelper helper) {
        firstUpgrade(helper, "lumber_camp_small", BuildingType.LUMBER_CAMP);
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void farmhouseSmallReachesLevelTwoAndTakesAThirdWorker(GameTestHelper helper) {
        firstUpgrade(helper, "farmhouse_small", BuildingType.FARMHOUSE);
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void cottageFirstUpgrade(GameTestHelper helper) {
        firstUpgrade(helper, "house_cottage", BuildingType.HOUSE);
    }

    @GameTest(template = "empty32", timeoutTicks = 12000, batch = "builder_upgrade")
    public void buildersHutOrdersItsOwnFirstUpgrade(GameTestHelper helper) {
        // The hut's own first level is orderable before the hut is level 2.
        firstUpgrade(helper, "builders_hut_small", BuildingType.BUILDERS_HUT);
    }

    /** People live and keep things in it: nobody trapped, nothing lost, the plaque link holds. */
    @GameTest(template = "empty16", timeoutTicks = 9000, batch = "builder_upgrade")
    public void upgradingALivedInHouseLosesNothing(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        upgradeReady(helper, arena, false);
        Map<BlockPos, BlockState> player = new HashMap<>();
        Blueprint shell = manorShell();
        Building house = standUp(helper, arena, shell, SITE, BuildingType.HOUSE, player);
        // The family's own chest, with their things in it.
        BlockPos chestRel = SITE.offset(1, 1, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container family = (Container) arena.level().getBlockEntity(helper.absolutePos(chestRel));
        family.setItem(0, new ItemStack(Items.BREAD, 7));
        family.setItem(1, new ItemStack(Items.IRON_INGOT, 3));
        player.put(helper.absolutePos(chestRel), Blocks.CHEST.defaultBlockState());
        PlaqueBlockEntity plaque = (PlaqueBlockEntity) arena.level().getBlockEntity(helper.absolutePos(SITE.offset(5, 2, 3)));
        plaque.survey(arena.level());
        SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(), SITE.offset(3, 1, 3));
        resident.bindTo(arena.settlement().id, arena.settlement().center);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Tove");
        Climber c = new Climber();
        int start = house.level;
        climb(helper, arena, house, start + 1, c);
        UUID houseId = house.id;
        helper.succeedWhen(() -> {
            helper.assertTrue(house.level == start + 1, "the lived-in house rises: " + c.report(house));
            helper.assertTrue(BuilderTestKit.count(family, Items.BREAD) == 7
                && BuilderTestKit.count(family, Items.IRON_INGOT) == 3, "the family's chest keeps its contents");
            helper.assertTrue(resident.isAlive() && arena.level().noCollision(resident, resident.getBoundingBox()),
                "the resident is not walled in or hurt");
            Building linked = plaque.building(arena.level());
            helper.assertTrue(linked != null && linked.id.equals(houseId) && linked.valid,
                "the plaque still links the same, valid house");
            assertUntouched(helper, arena, player);
        });
    }

    /** Materials run out halfway: the Builder says so and waits, then finishes when they come. */
    @GameTest(template = "empty16", timeoutTicks = 9000, batch = "builder_upgrade")
    public void anUpgradeShortOfMaterialsWaitsThenFinishes(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        upgradeReady(helper, arena, false);
        Map<BlockPos, BlockState> player = new HashMap<>();
        Building house = standUp(helper, arena, manorShell(), SITE, BuildingType.HOUSE, player);
        UpgradePlanner.Result result = UpgradePlanner.plan(arena.level(), arena.settlement(), house, null);
        helper.assertTrue(result != null && result.plan().job() != null, "an order can be planned: "
            + (result == null ? "null" : result.plan().validation().reasonKey()));
        BuildJob job = result.plan().job();
        helper.assertTrue(BuildJobs.commit(arena.level(), arena.settlement(), job) == null, "queued");
        Map<Item, Integer> bill = BuilderMaterials.total(job);
        // Only the first item kind is there; the rest comes later.
        Map.Entry<Item, Integer> first = bill.entrySet().iterator().next();
        BuilderTestKit.stock(arena.chest(), new ItemStack(first.getKey(), first.getValue()));
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Signe");
        int start = house.level;
        boolean[] saidSo = {false};
        boolean[] delivered = {false};
        helper.onEachTick(() -> {
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            if (live == null || delivered[0]) {
                return;
            }
            if (live.status == BuildStatus.NEEDS_PLAYER || live.status == BuildStatus.WAITING_FOR) {
                saidSo[0] = true;
                for (Map.Entry<Item, Integer> e : bill.entrySet()) {
                    if (e.getKey() != first.getKey()) {
                        BuilderTestKit.stock(arena.chest(), new ItemStack(e.getKey(), e.getValue()));
                    }
                }
                delivered[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(saidSo[0], "the Builder said what was missing before it came");
            BuildJob live = BuilderTestKit.job(arena.level(), arena.settlement(), job.id);
            helper.assertTrue(live != null && live.state == BuildJob.State.COMPLETE,
                "the order completes once the rest arrives: " + (live == null ? "gone" : live.status + " " + live.statusArgs));
            helper.assertTrue(house.level == start + 1, "and the level rises, is " + house.level);
            assertUntouched(helper, arena, player);
        });
    }

    /** A raid alarm sends everyone to shelter; the order resumes afterwards and completes. */
    @GameTest(template = "empty16", timeoutTicks = 9000, batch = "builder_upgrade")
    public void anUpgradeInterruptedByAnAlarmResumes(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        upgradeReady(helper, arena, false);
        Map<BlockPos, BlockState> player = new HashMap<>();
        Building house = standUp(helper, arena, manorShell(), SITE, BuildingType.HOUSE, player);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Halvard");
        Climber c = new Climber();
        int start = house.level;
        climb(helper, arena, house, start + 1, c);
        boolean[] alarmed = {false};
        helper.onEachTick(() -> {
            BuildJob live = c.job == null ? null : BuilderTestKit.job(arena.level(), arena.settlement(), c.job);
            if (!alarmed[0] && live != null && live.doneCount() >= 1) {
                arena.settlement().alertUntilGameTime = arena.level().getGameTime() + 400L;
                alarmed[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(alarmed[0], "the alarm went off mid-order");
            helper.assertTrue(!arena.settlement().alertActive(arena.level().getGameTime()), "the alarm is over");
            helper.assertTrue(house.level == start + 1, "the order resumed and the level rose: " + c.report(house));
            assertUntouched(helper, arena, player);
        });
    }

    /** An order half done survives a save and load of the site data exactly. */
    @GameTest(template = "empty16", timeoutTicks = 9000, batch = "builder_upgrade")
    public void anUpgradeInProgressSurvivesSaveAndLoad(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 7);
        upgradeReady(helper, arena, false);
        Map<BlockPos, BlockState> player = new HashMap<>();
        Building house = standUp(helper, arena, manorShell(), SITE, BuildingType.HOUSE, player);
        BuilderTestKit.hireBuilder(helper, arena, new BlockPos(4, 1, 7), "Leiv");
        Climber c = new Climber();
        int start = house.level;
        climb(helper, arena, house, start + 1, c);
        boolean[] checked = {false};
        helper.onEachTick(() -> {
            BuildJob live = c.job == null ? null : BuilderTestKit.job(arena.level(), arena.settlement(), c.job);
            if (checked[0] || live == null || live.doneCount() < 1 || live.state != BuildJob.State.ACTIVE) {
                return;
            }
            var registries = arena.level().registryAccess();
            CompoundTag saved = BuildSiteSavedData.get(arena.level()).save(new CompoundTag(), registries);
            BuildSiteSavedData loaded = BuildSiteSavedData.load(saved, registries);
            BuildJob again = loaded.job(arena.settlement().id, live.id);
            helper.assertTrue(again != null && again.kind == BuildJob.Kind.UPGRADE
                    && again.size() == live.size() && again.doneCount() == live.doneCount()
                    && house.id.equals(again.targetId),
                "the half-done order reloads exactly: " + (again == null ? "missing"
                    : again.kind + " " + again.doneCount() + "/" + again.size()));
            checked[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(checked[0], "the order was saved and loaded mid-way");
            helper.assertTrue(house.level == start + 1, "and still completed: " + c.report(house));
        });
    }
}
