package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestItemFingerprint;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestState;
import com.hearthstead.settlement.request.RequestType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The warehouse/courier flagship, deepened: a priority ladder that puts a
 * crafter's own shortage ahead of routine consolidation, a reservation
 * ledger that stops two couriers fetching the same scarce stock, a tidy
 * that provably stops, and the live-reported navigation regression pinned
 * so it cannot come back unnoticed.
 *
 * <p>{@link CourierGameTests} already covers the original hearth ->
 * warehouse leg in depth (sealed rooms, sack load, the undeliverable-load
 * fallback); this file is aimed at what changed: the restock leg, the
 * reservation that guards it, and {@code TidyWarehouseGoal}'s convergence.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class LogisticsGameTests {

    // ------------------------------------------------------------ fixtures ---

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

    /**
     * Registered through {@link SettlementManager}, per the task's own
     * instruction, rather than reaching past it into
     * {@code SettlementSavedData} directly -- {@code SettlementManager.data}
     * is that exact call one layer down, but going through the manager keeps
     * this file agnostic of that detail changing later.
     */
    private static Settlement registerSettlement(GameTestHelper helper, BlockPos centerRel,
                                                  int radius) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        var data = SettlementManager.data(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Tingholm",
            helper.absolutePos(centerRel));
        s.radius = radius;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /** Register custom bounds through the one supported synthetic seam. */
    private static Building addBuilding(GameTestHelper helper, Settlement s, BuildingType type,
                                        BlockPos minRel, BlockPos maxRel, BlockPos anchorRel) {
        BoundingBox bounds = BoundingBox.fromCorners(
            helper.absolutePos(minRel), helper.absolutePos(maxRel));
        return GameTestFixtures.registerWithBounds(helper, s, type, anchorRel,
            anchorRel, bounds);
    }

    /** A closed wooden door, both halves -- copied from {@link CourierGameTests}. */
    private static void placeDoor(GameTestHelper helper, BlockPos lowerRel, Direction facing) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(lowerRel, lower);
        helper.setBlock(lowerRel.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static boolean doorOpen(GameTestHelper helper, BlockPos rel) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(rel));
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countIn(Container c, Item item) {
        if (c == null) {
            return 0;
        }
        int n = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack stack = c.getItem(slot);
            if (stack.is(item)) {
                n += stack.getCount();
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

    private static String tidyDiagnostic(GameTestHelper helper, Settlement settlement,
                                         SettlerEntity courier, BlockPos[] chestRel,
                                         int targetIndex) {
        StringBuilder stock = new StringBuilder();
        for (int index = 0; index < chestRel.length; index++) {
            if (index > 0) {
                stock.append(',');
            }
            BlockPos absolute = helper.absolutePos(chestRel[index]);
            stock.append(index == targetIndex ? "target@" : "source@")
                .append(absolute.toShortString()).append('=')
                .append(countIn(containerAt(helper, chestRel[index]), Items.COBBLESTONE));
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(helper.getLevel());
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        String request = ledger == null ? "none" : ledger.active().stream()
            .filter(row -> courier.getUUID().equals(row.courierId()))
            .map(row -> row.type() + ":" + row.state() + ":" + row.blocker()
                + " src=" + row.sourceContainer().toShortString()
                + " dst=" + row.targetContainer().toShortString()
                + " moved=" + row.movedCount() + " delivered=" + row.deliveredCount())
            .reduce((left, right) -> left + "|" + right).orElse("none");
        return " [stock=" + stock + " bag=" + bagCount(courier)
            + " activity=" + courier.getActivity()
            + " lifecycle=" + courier.workerLifecycle().state()
            + "/" + courier.workerLifecycle().task()
            + " route=" + courier.routeFailureNote() + " request=" + request + "]";
    }

    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Bud");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        Building warehouse = s.buildings.stream()
            .filter(b -> b.valid && b.type == BuildingType.WAREHOUSE
                && b.workers.size() < b.type.workerCapacity())
            .findFirst().orElse(null);
        if (warehouse != null) {
            helper.assertTrue(Employment.hire(helper.getLevel(), s, warehouse,
                    settler).ok(),
                "courier fixture must acquire real Warehouse employment authority");
        } else {
            // A small set of negative tests intentionally has no warehouse;
            // direct projection lets those exercise NO_WAREHOUSE behavior.
            settler.assignProfession(Profession.COURIER);
        }
        return settler;
    }

    // ------------------------------------------------------- reservation ---

    /**
     * Two couriers, one scarce stock: without a reservation, both would
     * decide independently -- in the same tick, before either has moved --
     * to fetch the same raw iron for the same smelter, and both would walk
     * the whole route before either found out only one delivery was needed.
     *
     * <p>Item counts alone cannot prove exclusivity here: chest truth means
     * a second courier who was never locked out but simply arrives after
     * the stock is gone looks IDENTICAL from the outside to one who was
     * turned away by the ledger -- neither ever visibly carries anything.
     * {@link CourierWorkGoal#restockJobIsHeld} is asked directly instead,
     * so this proves the lock itself exists, gets held, and gets released
     * -- not just that the physically-obvious outcome (one delivery, not
     * two) happened to occur.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "logistics_day")
    public void reservationLetsOnlyOneCourierFetchTheSameStock(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);
        Container source = containerAt(helper, new BlockPos(5, 1, 3));
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        // TWO, not four. Specification correction (2026-08-26, logistics
        // weight): RAW_IRON is DEAD_WEIGHT, so one courier load is now 2 of
        // it, and a 4-item pile takes two trips. This test is about the
        // RESERVATION -- that two couriers cannot both claim one job -- and
        // with four items the first courier legitimately finished, released
        // the job, and the second one correctly took the remaining pile.
        // That is the ledger working, not failing, but it read as a failure
        // because the setup quietly assumed one trip empties the chest.
        //
        // Sizing the pile to exactly one load restores that assumption and
        // leaves every assertion below untouched and unweakened -- the
        // "only one courier should ever have hauled this stock" check is
        // still the original, still strict, and now tests what it meant to.
        source.setItem(0, new ItemStack(Items.RAW_IRON, 2));

        Building smelter = addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);

        SettlerEntity first = courier(helper, s, new BlockPos(7, 1, 7));
        SettlerEntity second = courier(helper, s, new BlockPos(8, 1, 7));
        final boolean[] firstCarried = {false};
        final boolean[] secondCarried = {false};
        final boolean[] sawHeld = {false};
        final boolean[] sawReleasedAfterHold = {false};

        helper.succeedWhen(() -> {
            if (first.getActivity() == SettlerActivity.CARRYING
                || first.getActivity() == SettlerActivity.SORTING) {
                firstCarried[0] = true;
            }
            if (second.getActivity() == SettlerActivity.CARRYING
                || second.getActivity() == SettlerActivity.SORTING) {
                secondCarried[0] = true;
            }
            boolean held = CourierWorkGoal.restockJobIsHeld(smelter.id, Items.RAW_IRON);
            if (held) {
                sawHeld[0] = true;
            }
            if (sawHeld[0] && !held) {
                sawReleasedAfterHold[0] = true;
            }

            Container smelterChest = containerAt(helper, new BlockPos(3, 1, 6));
            Container warehouseChest = containerAt(helper, new BlockPos(5, 1, 3));
            int atSmelter = countIn(smelterChest, Items.RAW_IRON);
            int atWarehouse = countIn(warehouseChest, Items.RAW_IRON);
            int total = atSmelter + atWarehouse + bagCount(first) + bagCount(second);
            helper.assertTrue(total == 2,
                "raw iron must be conserved across the whole route, saw " + total
                    + " [smelter=" + atSmelter + " warehouse=" + atWarehouse
                    + " firstBag=" + bagCount(first) + " secondBag=" + bagCount(second) + "]");
            helper.assertTrue(atSmelter == 2,
                "all 2 raw iron should reach the smelter, saw " + atSmelter
                    + " [firstAct=" + first.getActivity() + " secondAct=" + second.getActivity()
                    + "]");
            helper.assertTrue(sawHeld[0],
                "the reservation ledger never recorded a claim on this job -- the lock "
                    + "itself never engaged");
            helper.assertTrue(sawReleasedAfterHold[0],
                "the reservation was claimed but never released once the job resolved");
            helper.assertTrue(!(firstCarried[0] && secondCarried[0]),
                "only one courier should ever have hauled this stock -- both did "
                    + "[first=" + firstCarried[0] + " second=" + secondCarried[0] + "]");
        });
    }

    // -------------------------------------------------------------- tidy ---

    /**
     * Three part-stacks of the same ordinary material, scattered across two
     * source chests with a separately assigned home chest, with a courier employed
     * to tidy them. Wood and crops take the
     * physical warehouse route; this keeps the tidy convergence contract
     * scoped to the work it still owns. This must both consolidate --
     * every existing GameTest that checked this class checked a single
     * merge, never whether it keeps going and then genuinely stops -- and
     * stay converged: once the warehouse reads as tidy the goal must go
     * quiet, not keep re-selecting and re-scanning forever.
     */
    // This is a convergence fixture, not a throughput benchmark. Each unit
    // owns a full source-pickup and bag-to-chest presentation cycle, while a
    // real day also contains meals. Three small partial stacks prove the same
    // physical all-to-one contract inside one bounded work window.
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "logistics_day")
    public void tidyConvergesAndGoesQuiet(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(2, 1, 4), new BlockPos(6, 3, 8), new BlockPos(2, 1, 4));
        BlockPos[] chestRel = {
            new BlockPos(3, 1, 5), new BlockPos(5, 1, 5), new BlockPos(3, 1, 7),
        };
        for (int i = 0; i < chestRel.length; i++) {
            helper.setBlock(chestRel[i], Blocks.CHEST);
            Container c = containerAt(helper, chestRel[i]);
            helper.assertTrue(c != null, "arena chest " + i + " should exist");
        }
        // Use the production selector's y/x/z-ordered result rather than
        // assuming fixture declaration order is the chosen Materials home.
        java.util.List<BlockPos> materialTargets =
            com.hearthstead.settlement.warehouse.WarehouseSorting.destinations(
            helper.getLevel(), warehouse, new ItemStack(Items.COBBLESTONE));
        int selectedTargetIndex = -1;
        for (int index = 0; index < chestRel.length; index++) {
            if (materialTargets.contains(helper.absolutePos(chestRel[index]))) {
                selectedTargetIndex = index;
                break;
            }
        }
        final int targetIndex = selectedTargetIndex;
        helper.assertTrue(materialTargets.size() == 1 && targetIndex >= 0,
            "tidy fixture must have one real Materials destination: " + materialTargets);
        int firstSource = targetIndex == 0 ? 1 : 0;
        int secondSource = targetIndex == 2 ? 1 : 2;
        Container first = containerAt(helper, chestRel[firstSource]);
        Container second = containerAt(helper, chestRel[secondSource]);
        first.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        first.setItem(1, new ItemStack(Items.COBBLESTONE, 2));
        second.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        int expectedTotal = 3 + 2 + 1;

        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 6));
        helper.assertTrue(Employment.employerOf(s, bud.getUUID()) == warehouse,
            "tidy fixture must keep the courier's real Warehouse employment");
        bud.setHunger(100.0F);
        bud.setEnergy(100.0F);

        final int[] stableTicks = {0};
        final int[] maxDistinctSeen = {0};

        helper.succeedWhen(() -> {
            int total = 0;
            int distinctChests = 0;
            for (BlockPos rel : chestRel) {
                int here = countIn(containerAt(helper, rel), Items.COBBLESTONE);
                total += here;
                if (here > 0) {
                    distinctChests++;
                }
            }
            maxDistinctSeen[0] = Math.max(maxDistinctSeen[0], distinctChests);
            total += bagCount(bud);
            helper.assertTrue(total == expectedTotal,
                "tidying must conserve every cobblestone, saw " + total + " of " + expectedTotal
                    + tidyDiagnostic(helper, s, bud, chestRel, targetIndex));

            int targetCount = countIn(containerAt(helper, chestRel[targetIndex]),
                Items.COBBLESTONE);
            boolean settled = distinctChests <= 1 && targetCount == expectedTotal
                && bagCount(bud) == 0
                && bud.getActivity() != SettlerActivity.SORTING;
            // A streak, not a one-shot check: the whole point is that once
            // tidy, it STAYS tidy and the goal STAYS quiet -- a courier who
            // reaches one chest and then immediately churns again would
            // pass a single-tick check and fail this one.
            stableTicks[0] = settled ? stableTicks[0] + 1 : 0;
            helper.assertTrue(stableTicks[0] >= 150,
                "tidy should converge to one chest and go quiet, and stay quiet: "
                    + "distinctChests=" + distinctChests + " targetCount=" + targetCount
                    + " act=" + bud.getActivity()
                    + " stableTicks=" + stableTicks[0] + " maxDistinctSeen="
                    + maxDistinctSeen[0] + tidyDiagnostic(helper, s, bud, chestRel, targetIndex));
        });
    }

    // ------------------------------------------------------------ restock ---

    /**
     * Conservation across the NEW leg specifically: warehouse -> crafter.
     * {@link CourierGameTests} already proves hearth -> warehouse in depth;
     * this is the restock route's own version of that same proof, since it
     * is a materially different transfer (a live withdrawal from a chest
     * that is not the hearth, landing in a building that is not a
     * warehouse) that no existing GameTest exercises.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "logistics_day")
    public void restockConservesItemsAcrossTheFullRoute(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);
        Container source = containerAt(helper, new BlockPos(5, 1, 3));
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 12));

        addBuilding(helper, s, BuildingType.SMITHY,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        helper.assertTrue(Employment.employerOf(s, bud.getUUID()) == warehouse
                && bud.getProfession() == Profession.COURIER,
            "courier fixture must retain its exact Warehouse employment");

        helper.succeedWhen(() -> {
            Container smithyChest = containerAt(helper, new BlockPos(3, 1, 6));
            Container warehouseChest = containerAt(helper, new BlockPos(5, 1, 3));
            int atSmithy = countIn(smithyChest, Items.IRON_INGOT);
            int atWarehouse = countIn(warehouseChest, Items.IRON_INGOT);
            int total = atSmithy + atWarehouse + bagCount(bud);
            helper.assertTrue(total == 12,
                "iron ingots must be conserved across the restock route, saw " + total
                    + " [smithy=" + atSmithy + " warehouse=" + atWarehouse
                    + " bag=" + bagCount(bud) + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atSmithy == 12,
                "all 12 iron ingots should reach the smithy, saw " + atSmithy
                    + " [act=" + bud.getActivity() + " pos=" + bud.blockPosition().toShortString()
                    + " energy=" + String.format("%.1f", bud.getEnergy())
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
        });
    }

    /**
     * Hunter ammunition is a durable ledger-owned restock trip: exact physical
     * warehouse source, bounded Lodge target, and an exact-source bag return
     * across goal recreation. The Lodge is mixed storage,
     * so its real wildlife output must remain collectable while bow and arrow
     * inputs stay put.
     */
    @GameTest(template = "empty16", timeoutTicks = 1640,
        batch = "hunter_lodge_ammunition")
    public void hunterLodgeAmmoRestockConservesAcrossInterruptionAndCannotDrain(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 8);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(3, 1, 3), new BlockPos(5, 3, 5),
            new BlockPos(3, 1, 3));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 4);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        Container source = containerAt(helper, warehouseChestRel);
        helper.assertTrue(source != null, "warehouse source must exist");
        source.setItem(0, new ItemStack(Items.ARROW, 40));

        addBuilding(helper, s, BuildingType.HUNTERS_LODGE,
            new BlockPos(7, 1, 3), new BlockPos(9, 3, 5),
            new BlockPos(9, 1, 3));
        BlockPos lodgeChestRel = new BlockPos(7, 1, 4);
        helper.setBlock(lodgeChestRel, Blocks.CHEST);
        Container lodge = containerAt(helper, lodgeChestRel);
        helper.assertTrue(lodge != null, "Hunter Lodge target must exist");
        lodge.setItem(0, new ItemStack(Items.BOW));
        lodge.setItem(1, new ItemStack(Items.ARROW, 3));
        lodge.setItem(2, new ItemStack(Items.BEEF, 2));

        SettlerEntity bud = courier(helper, s, new BlockPos(6, 1, 5));
        int seededArrows = 43;
        // This fixture drives real goal ticks while NoAI excludes a second goal owner.
        // Typed source presentation accepts only one advancement per world tick.
        bud.setNoAi(true);
        BlockPos sourceContact = helper.absolutePos(warehouseChestRel.south());
        BlockPos targetContact = helper.absolutePos(lodgeChestRel.south());
        bud.setPos(sourceContact.getX() + .5, sourceContact.getY(), sourceContact.getZ() + .5);
        CourierWorkGoal route = new CourierWorkGoal(bud);
        CourierWorkGoal collection = new CourierWorkGoal(bud);
        helper.assertTrue(route.canUse(),
            "the existing restock ladder must select the short Lodge");
        route.start();
        int[] phase = {0};
        boolean[] interrupted = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(countIn(source, Items.ARROW) + countIn(lodge, Items.ARROW)
                    + bagCountOf(bud, Items.ARROW) == seededArrows,
                "every actual source/destination contact must conserve all forty-three arrows");
            helper.assertTrue(countIn(source, Items.BEEF) + countIn(lodge, Items.BEEF)
                    + bagCountOf(bud, Items.BEEF) == 2,
                "the output positive control must conserve both real beef items");
            if (phase[0] == 0) {
                route.tick();
                if (bagCountOf(bud, Items.ARROW) == 0) {
                    helper.assertTrue(false, "waiting for the first actual source contact");
                }
                helper.assertTrue(bagCountOf(bud, Items.ARROW) == 1,
                    "the interruption must follow exactly one new source unit");
                route.stop();
                interrupted[0] = true;
                helper.assertTrue(route.canUse(), "the same route must resume its partial source session");
                route.start();
                phase[0] = 1;
            } else if (phase[0] == 1) {
                boolean sourceActive = new com.hearthstead.entity.ai.CourierSourceBagSession(bud).active();
                if (!sourceActive && bagCountOf(bud, Items.ARROW) > 0) {
                    // Controlled contact fixture: move the actor only after the real final source lift.
                    bud.setPos(targetContact.getX() + .5, targetContact.getY(), targetContact.getZ() + .5);
                }
                route.tick();
                if (bagCountOf(bud, Items.ARROW) == 0 && !bud.bagTransferPresentation().active()
                        && !new com.hearthstead.entity.ai.CourierSourceBagSession(bud).active()) {
                    route.stop();
                    if (countIn(lodge, Items.ARROW) < SettlerEntity.ARCHER_QUIVER_CAPACITY) {
                        bud.setPos(sourceContact.getX() + .5, sourceContact.getY(), sourceContact.getZ() + .5);
                        helper.assertTrue(route.canUse(), "remaining real ammunition deficit needs its next bounded load");
                        route.start();
                    } else {
                        helper.assertTrue(countIn(lodge, Items.ARROW) == SettlerEntity.ARCHER_QUIVER_CAPACITY,
                            "resumed delivery must fill exactly one quiver reserve");
                        bud.setPos(targetContact.getX() + .5, targetContact.getY(), targetContact.getZ() + .5);
                        helper.assertTrue(collection.canUse(), "genuine Lodge output must remain collectable");
                        collection.start();
                        phase[0] = 2;
                    }
                }
            } else {
                if (!new com.hearthstead.entity.ai.CourierSourceBagSession(bud).active()
                        && bagCountOf(bud, Items.BEEF) > 0) {
                    bud.setPos(sourceContact.getX() + .5, sourceContact.getY(), sourceContact.getZ() + .5);
                }
                collection.tick();
            }
            helper.assertTrue(phase[0] == 2 && countIn(source, Items.BEEF) == 2
                    && countIn(lodge, Items.BEEF) == 0 && bagCountOf(bud, Items.BEEF) == 0
                    && !bud.bagTransferPresentation().active(),
                "actual source fill, warehouse unload and final lift must finish the output positive control");
            helper.assertTrue(interrupted[0] && countIn(lodge, Items.BOW) == 1
                    && countIn(lodge, Items.ARROW) == SettlerEntity.ARCHER_QUIVER_CAPACITY
                    && countIn(source, Items.ARROW) == seededArrows - SettlerEntity.ARCHER_QUIVER_CAPACITY,
                "interrupted ammunition and output collection must retain the exact Lodge reserve");
            collection.stop();
        });
    }

    // --------------------------------------------------- arrival regression ---

    /**
     * LIVE REGRESSION (coordinator diagnostic, run 20260826T013935Z):
     * {@code hasArrived} used to require {@code building.bounds.isInside(at)}
     * before it looked at distance at all -- but {@link CourierWorkGoal}'s own
     * approach-cell picker only guarantees a standable cell TOUCHING the
     * chest, not one inside the building's own recorded box, and the world's
     * navigator resolves that request to the last walkable node next to an
     * obstruction, which can land one step short of the requested cell. Proof
     * from the field: a courier parked one block outside a 3x3x3 fixture box,
     * two blocks from the chest, navigation reporting DONE -- and 33 repaths
     * against a length-1 path that went nowhere, because a containment gate
     * checked first can never be satisfied by a courier who has gone exactly
     * as far toward the chest as the world lets her.
     *
     * <p>This pins it without relying on that navigator quirk reproducing:
     * the crafter's registered bounds here are drawn to the chest's own
     * single cell, narrower than ANY position a courier could physically
     * stand at (nobody stands inside a chest), so {@code isInside(at)} is
     * false for every tick of this delivery, by construction. Delivery must
     * still complete purely on the reach test -- the exact shape of "a target
     * the navigator cannot stand on", generalised past one specific field
     * report so a regression here cannot silently return.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "logistics_day")
    public void restockDeliversWhenTheOnlyStandableCellIsOutsideTheCraftersBounds(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);
        Container source = containerAt(helper, new BlockPos(5, 1, 3));
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 12));

        // The crafter's bounds are the chest's own single cell -- no
        // standable approach cell can ever be "inside" a box that small.
        BlockPos smithyChestRel = new BlockPos(3, 1, 6);
        addBuilding(helper, s, BuildingType.SMITHY,
            smithyChestRel, smithyChestRel, new BlockPos(2, 1, 5));
        helper.setBlock(smithyChestRel, Blocks.CHEST);

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        helper.assertTrue(Employment.employerOf(s, bud.getUUID()) == warehouse
                && bud.getProfession() == Profession.COURIER,
            "courier fixture must retain its exact Warehouse employment");

        helper.succeedWhen(() -> {
            Container smithyChest = containerAt(helper, smithyChestRel);
            Container warehouseChest = containerAt(helper, new BlockPos(5, 1, 3));
            int atSmithy = countIn(smithyChest, Items.IRON_INGOT);
            int atWarehouse = countIn(warehouseChest, Items.IRON_INGOT);
            int total = atSmithy + atWarehouse + bagCount(bud);
            helper.assertTrue(total == 12,
                "iron ingots must be conserved even when no standable position "
                    + "is ever inside the crafter's registered bounds, saw " + total
                    + " [smithy=" + atSmithy + " warehouse=" + atWarehouse
                    + " bag=" + bagCount(bud) + "]");
            helper.assertTrue(atSmithy == 12,
                "delivery must complete on the reach test alone when the "
                    + "registered bounds can never contain a standing courier, saw "
                    + atSmithy + " [act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
        });
    }

    // ------------------------------------------------- navigation regression ---

    /**
     * LIVE REGRESSION (coordinator report, live session "Heatherbrook",
     * evidence dir qa/reports/artifacts/live/20260825T183505Z): a settler's
     * pathfinder marked closed doors passable (RoadNavigation's node
     * evaluator had {@code setCanPassDoors(true)} but never {@code
     * setCanOpenDoors(true)}), so a path could be planned straight through a
     * closed door but nothing ever told the mob to actually open it on
     * arrival -- every route into a real, one-door room ended standing at
     * the door forever. Fixed at the navigation layer (not in this file's
     * owned goals, which is why this is only a pin, not a fix): this test
     * exists so a regression there is caught here too, on the one path in
     * this repository that most depends on a settler actually getting
     * through a closed door -- a courier's delivery.
     *
     * <p>The room has exactly one opening, so delivery completing at all is
     * already strong evidence; the door is also polled directly and must be
     * seen open at least once, so a future regression that finds some other
     * way to route around a "closed" door still fails this for the right
     * reason.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "logistics_day")
    public void courierOpensAClosedDoorToDeliver(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 6));
        }

        // A sealed 5x5 room: walls y1-3 on the ring, a ceiling, one closed
        // door -- the only way in or out.
        for (int x = 5; x <= 9; x++) {
            for (int z = 5; z <= 9; z++) {
                boolean rim = x == 5 || z == 5 || x == 9 || z == 9;
                helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_PLANKS);
                if (rim) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
                    }
                }
            }
        }
        BlockPos doorRel = new BlockPos(7, 1, 5);
        placeDoor(helper, doorRel, Direction.SOUTH);
        BlockPos chestRel = new BlockPos(8, 1, 8);
        helper.setBlock(chestRel, Blocks.CHEST);
        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(5, 0, 5), new BlockPos(9, 4, 9), new BlockPos(6, 2, 5));

        SettlerEntity bud = courier(helper, s, new BlockPos(3, 1, 3));
        final boolean[] doorEverOpened = {false};

        helper.succeedWhen(() -> {
            if (doorOpen(helper, doorRel)) {
                doorEverOpened[0] = true;
            }
            Container chest = containerAt(helper, chestRel);
            int delivered = countIn(chest, Items.OAK_LOG);
            helper.assertTrue(delivered >= 6,
                "all 6 logs should reach the sealed warehouse through its one door, saw "
                    + delivered + " [act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " bag=" + bagCount(bud)
                    + " doorOpenNow=" + doorOpen(helper, doorRel)
                    + " doorEverOpened=" + doorEverOpened[0]
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(doorEverOpened[0],
                "the closed door must actually be opened by the settler, not routed "
                    + "around some other way -- it was never seen open");
        });
    }

    // ------------------------------------------------ dissolved mid-route ---

    /**
     * The brief's own hunt list: "what happens ... when a building is
     * dissolved mid-route." A crafter's plaque coming down mid-delivery is
     * exactly what {@code BuildingManager}'s real sweep produces --
     * {@code settlement.buildings.remove(building)} -- so this removes the
     * smithy from the settlement's own list the same way, at the moment the
     * courier is genuinely mid-haul with the ingots in her sack, rather than
     * depending on the sweep's cross-test round-robin timing (KF-014/KF-021's
     * documented flake source, and not this file's concern to pin).
     *
     * <p>Both of {@link CourierWorkGoal}'s own dissolution guards are live
     * candidates here depending on exactly which tick the removal lands on --
     * {@code tickToCrafter}'s {@code crafter == null -> beginReturn()} if she
     * is still walking, or {@code canUseCarrying}'s equivalent check if the
     * goal was interrupted and resumed in between -- and the route's own
     * contract does not care which one fires: either way the ingots must
     * come home to the warehouse they left, not evaporate and not strand in
     * the bag.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "logistics_day")
    public void restockLoadReturnsToWarehouseWhenTheCrafterDissolvesMidTrip(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        Container source = containerAt(helper, warehouseChestRel);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        int seeded = 12;
        source.setItem(0, new ItemStack(Items.IRON_INGOT, seeded));

        Building smithy = addBuilding(helper, s, BuildingType.SMITHY,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        helper.assertTrue(Employment.employerOf(s, bud.getUUID()) == warehouse
                && bud.getProfession() == Profession.COURIER,
            "courier fixture must retain its exact Warehouse employment");

        final boolean[] dissolved = {false};

        helper.succeedWhen(() -> {
            if (!dissolved[0]) {
                // Wait for the real pickup the AI itself performs -- not a
                // guessed tick count -- so the dissolve genuinely lands
                // mid-haul, cargo already in the sack.
                if (bagCount(bud) <= 0) {
                    return;
                }
                helper.assertTrue(s.buildings.remove(smithy),
                    "fixture: the smithy must still be registered to remove it");
                dissolved[0] = true;
                return; // let the goal react on its own next tick
            }
            Container smithyChest = containerAt(helper, new BlockPos(3, 1, 6));
            Container warehouseChest = containerAt(helper, warehouseChestRel);
            int atSmithy = countIn(smithyChest, Items.IRON_INGOT);
            int atWarehouse = countIn(warehouseChest, Items.IRON_INGOT);
            int inBag = bagCount(bud);
            int total = atSmithy + atWarehouse + inBag;
            helper.assertTrue(total == seeded,
                "iron ingots must be conserved when the destination dissolves "
                    + "mid-trip, saw " + total + " [smithy=" + atSmithy
                    + " warehouse=" + atWarehouse + " bag=" + inBag
                    + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atWarehouse == seeded,
                "with the smithy gone, every ingot must come home to the "
                    + "warehouse it left rather than stay stranded in the sack, saw "
                    + atWarehouse + " of " + seeded + " [smithy=" + atSmithy
                    + " bag=" + inBag + " act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
        });
    }

    // ------------------------------------------------------- the ladder ---

    /**
     * The other half of the ladder the brief names: "a crafter starving
     * outranks a hungry hearth". {@link CourierFoodRouteGameTests} already
     * proves food outranks collection
     * ({@code starvingHearthOutranksMineCollection}); this is the rung above
     * it, and it is this file's to prove because {@code CRAFTER_RESTOCK} is
     * {@link CourierWorkGoal.JobPriority}'s own top tier, defined in the file
     * this class exists to test.
     *
     * <p>A warehouse holds both cargoes at once -- iron ingots the smithy is
     * completely out of, and bread the hearth is completely out of -- so a
     * single courier genuinely has to choose. The watch is a latch exactly
     * like the mine/food test's: if bread is ever seen at the hearth before
     * the smithy's restock has fully landed, the ladder is inverted and the
     * test can never pass. The smithy must then still finish, and the hearth
     * must still be fed afterwards, so this also proves the ladder continues
     * below restock rather than starving the village of food forever.
     */
    @GameTest(template = "empty16", timeoutTicks = 4800, batch = "logistics_day")
    public void restockOutranksAHungryHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        Container source = containerAt(helper, warehouseChestRel);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        int ingotSeed = 12;
        int breadSeed = 24;
        source.setItem(0, new ItemStack(Items.IRON_INGOT, ingotSeed));
        source.setItem(1, new ItemStack(Items.BREAD, breadSeed));

        addBuilding(helper, s, BuildingType.SMITHY,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        BlockPos smithyChestRel = new BlockPos(3, 1, 6);
        helper.setBlock(smithyChestRel, Blocks.CHEST);

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        helper.assertTrue(Employment.employerOf(s, bud.getUUID()) == warehouse
                && bud.getProfession() == Profession.COURIER,
            "courier fixture must retain its exact Warehouse employment");
        int threshold = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.stageFor(s)).courierReadyFoodTarget();

        final boolean[] breadMovedBeforeRestock = {false};

        helper.succeedWhen(() -> {
            Container smithyChest = containerAt(helper, smithyChestRel);
            Container warehouseChest = containerAt(helper, warehouseChestRel);
            int atSmithy = countIn(smithyChest, Items.IRON_INGOT);
            int atWarehouseIngot = countIn(warehouseChest, Items.IRON_INGOT);
            int ingotInBag = bagCountOf(bud, Items.IRON_INGOT);
            int atWarehouseBread = countIn(warehouseChest, Items.BREAD);
            int breadInBag = bagCountOf(bud, Items.BREAD);
            int atHearth = 0;
            if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
                instanceof HearthBlockEntity h) {
                atHearth = h.countFoodUnits();
            }
            if (atHearth > 0 && atSmithy < ingotSeed) {
                breadMovedBeforeRestock[0] = true;
            }
            int ingotTotal = atSmithy + atWarehouseIngot + ingotInBag;
            int breadTotal = atHearth + atWarehouseBread + breadInBag;
            helper.assertTrue(!breadMovedBeforeRestock[0],
                "bread reached the hearth while the smithy still needed ingots -- "
                    + "restock must outrank food, but food ran first "
                    + "[atSmithy=" + atSmithy + " of " + ingotSeed
                    + " atHearth=" + atHearth + "]");
            helper.assertTrue(ingotTotal == ingotSeed,
                "iron ingots must be conserved, saw " + ingotTotal + " of " + ingotSeed
                    + " [smithy=" + atSmithy + " warehouse=" + atWarehouseIngot
                    + " bag=" + ingotInBag + "]");
            helper.assertTrue(breadTotal == breadSeed,
                "bread must be conserved, saw " + breadTotal + " of " + breadSeed
                    + " [hearth=" + atHearth + " warehouse=" + atWarehouseBread
                    + " bag=" + breadInBag + "]");
            helper.assertTrue(atSmithy == ingotSeed,
                "the smithy must eventually be fully restocked, saw " + atSmithy
                    + " of " + ingotSeed + " [act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(atHearth >= threshold,
                "the ladder must continue below restock: the larder should reach "
                    + "its LOW mark of " + threshold + " afterwards, saw " + atHearth
                    + " [act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote()
                    + " sourceBread=" + atWarehouseBread + " bagBread=" + breadInBag
                    + " pos=" + bud.position() + " grounded=" + bud.onGround()
                    + " navTarget=" + bud.getNavigation().getTargetPos()
                    + " navDone=" + bud.getNavigation().isDone()
                    + " running=" + bud.goalSelector.getAvailableGoals().stream().filter(goal -> goal.isRunning())
                        .map(goal -> goal.getGoal().getClass().getSimpleName()).toList()
                    + " sourceBag=" + bud.getPersistentData().getCompound("HearthsteadCourierSourceBag")
                    + "]");
        });
    }

    // --------------------------------------- durable Hunter ammunition ---

    private record AmmoFixture(Settlement settlement, Building warehouse,
                               Container source, BlockPos sourcePos,
                               Building lodge, Container lodgeChest,
                               BlockPos lodgePos, Container otherWarehouse,
                               SettlerEntity courier) {
    }

    private static AmmoFixture ammoFixture(GameTestHelper helper,
                                           int sourceArrows,
                                           int lodgeArrows,
                                           boolean secondWarehouse) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement settlement = registerSettlement(helper, hearthRel, 9);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
                instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building warehouse = addBuilding(helper, settlement,
            BuildingType.WAREHOUSE, new BlockPos(3, 1, 3),
            new BlockPos(5, 3, 5), new BlockPos(3, 1, 3));
        BlockPos sourceRel = new BlockPos(5, 1, 4);
        helper.setBlock(sourceRel, Blocks.CHEST);
        Container source = containerAt(helper, sourceRel);
        helper.assertTrue(source != null, "ammunition Warehouse must have a chest");
        if (sourceArrows > 0) {
            source.setItem(0, new ItemStack(Items.ARROW, sourceArrows));
        }

        Building lodge = addBuilding(helper, settlement,
            BuildingType.HUNTERS_LODGE, new BlockPos(7, 1, 3),
            new BlockPos(9, 3, 5), new BlockPos(9, 1, 3));
        BlockPos lodgeRel = new BlockPos(7, 1, 4);
        helper.setBlock(lodgeRel, Blocks.CHEST);
        Container lodgeChest = containerAt(helper, lodgeRel);
        helper.assertTrue(lodgeChest != null,
            "ammunition Hunter Lodge must have a chest");
        if (lodgeArrows > 0) {
            lodgeChest.setItem(0, new ItemStack(Items.ARROW, lodgeArrows));
        }

        Container other = null;
        if (secondWarehouse) {
            addBuilding(helper, settlement, BuildingType.WAREHOUSE,
                new BlockPos(3, 1, 8), new BlockPos(5, 3, 10),
                new BlockPos(3, 1, 8));
            BlockPos otherRel = new BlockPos(5, 1, 9);
            helper.setBlock(otherRel, Blocks.CHEST);
            other = containerAt(helper, otherRel);
            helper.assertTrue(other != null,
                "alternate Warehouse must have a physical chest");
        }

        SettlerEntity courier = courier(helper, settlement,
            new BlockPos(6, 1, 5));
        courier.setNoAi(true);
        return new AmmoFixture(settlement, warehouse, source,
            helper.absolutePos(sourceRel), lodge, lodgeChest,
            helper.absolutePos(lodgeRel), other, courier);
    }

    private static RequestRecord openAndReserveAmmo(GameTestHelper helper,
                                                     AmmoFixture fixture,
                                                     SettlerEntity courier,
                                                     int sourceSlot,
                                                     int maximum) {
        RequestLedgerService.Decision opened =
            RequestLedgerService.openAmmunitionDelivery(helper.getLevel(),
                fixture.settlement(), fixture.warehouse(), fixture.sourcePos(),
                sourceSlot, fixture.lodge(), fixture.lodgePos(),
                Math.min(maximum, courier.getCarryCapacity()));
        helper.assertTrue(opened.accepted() && opened.request() != null,
            "fixture must open one exact AMMUNITION row");
        RequestLedgerService.Decision reserved =
            RequestLedgerService.reserve(helper.getLevel(),
                fixture.settlement(), opened.request().id(), courier);
        helper.assertTrue(reserved.accepted() && reserved.request() != null,
            "fixture Courier must own the exact AMMUNITION row");
        return reserved.request();
    }

    private static void pickupAmmo(GameTestHelper helper, AmmoFixture fixture,
                                   SettlerEntity courier,
                                   RequestRecord request) {
        courier.setPos(fixture.sourcePos().getX() + 0.5D,
            fixture.sourcePos().getY() + 1.0D,
            fixture.sourcePos().getZ() + 0.5D);
        RequestLedgerService.Decision picked = RequestLedgerService.pickup(
            helper.getLevel(), fixture.settlement(), request.id(), courier);
        helper.assertTrue(picked.accepted()
                && picked.request().state() == RequestState.IN_TRANSIT,
            "exact source contact must move the AMMUNITION row into transit");
    }

    private static RequestLedger ammoLedger(GameTestHelper helper,
                                             Settlement settlement) {
        return RequestLedgerSavedData.get(helper.getLevel())
            .ledger(settlement.id);
    }

    private static SettlerEntity reloadAmmoCourier(GameTestHelper helper,
                                                    SettlerEntity original) {
        CompoundTag savedEntity = original.saveWithoutId(new CompoundTag());
        UUID id = original.getUUID();
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        SettlerEntity restored = ModEntities.SETTLER.get()
            .create(helper.getLevel());
        helper.assertTrue(restored != null,
            "saved ammunition Courier must be constructible");
        restored.load(savedEntity);
        helper.assertTrue(id.equals(restored.getUUID())
                && helper.getLevel().addFreshEntity(restored),
            "reload must recreate the exact Courier identity");

        RequestLedgerSavedData live = RequestLedgerSavedData.get(
            helper.getLevel());
        RequestLedgerSavedData restoredLedger = RequestLedgerSavedData.load(
            live.save(new CompoundTag(), helper.getLevel().registryAccess()),
            helper.getLevel().registryAccess());
        helper.assertTrue(!restoredLedger.rootQuarantined(),
            "AMMUNITION ledger root must survive its real NBT round-trip");
        helper.getLevel().getDataStorage().set(
            "hearthstead_request_ledger", restoredLedger);
        restored.setNoAi(true);
        return restored;
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "hunter_lodge_ammunition")
    public void reservedAmmoReloadKeepsOneRequestAndUntouchedSource(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 40, 3, true);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        SettlerEntity restored = reloadAmmoCourier(helper, f.courier());

        CourierWorkGoal fresh = new CourierWorkGoal(restored);
        helper.assertTrue(fresh.canUse(),
            "a fresh goal must adopt the persisted pre-pickup route");
        helper.assertTrue(restored.workerLifecycle().task() != null
                && restored.workerLifecycle().task().requestId()
                    .equals(request.id()),
            "runtime lifecycle observation must point at the original row");
        SettlerEntity other = courier(helper, f.settlement(),
            new BlockPos(4, 1, 9));
        other.setNoAi(true);
        helper.assertTrue(!RequestLedgerService.reserve(helper.getLevel(),
                f.settlement(), request.id(), other).accepted(),
            "a second Courier cannot steal the recovered reservation");
        long ammunitionRows = ammoLedger(helper, f.settlement()).active()
            .stream().filter(row -> row.type() == RequestType.AMMUNITION)
            .count();
        helper.assertTrue(ammunitionRows == 1
                && countIn(f.source(), Items.ARROW) == 40
                && bagCountOf(restored, Items.ARROW) == 0,
            "pre-pickup reload must retain one intent and mutate no arrows");
        CompoundTag malformed = request.writeNbt();
        malformed.put("Fingerprint", RequestItemFingerprint.capture(
            helper.getLevel().registryAccess(), new ItemStack(Items.BONE,
                request.fingerprint().count()),
            request.fingerprint().count()).writeNbt());
        helper.assertTrue(RequestRecord.readNbt(malformed,
                helper.getLevel().registryAccess(), f.settlement().id) == null,
            "a serialized AMMUNITION row for a non-Arrow item must quarantine at decode");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 600,
        batch = "hunter_lodge_ammunition")
    public void inTransitAmmoReloadCompletesOriginalLodgeWithoutSecondPickup(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 40, 3, true);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        pickupAmmo(helper, f, f.courier(), request);
        int moved = request.fingerprint().count();
        helper.assertTrue(countIn(f.source(), Items.ARROW) == 40 - moved
                && bagCountOf(f.courier(), Items.ARROW) == moved,
            "fixture must cross the physical source-to-bag boundary once");

        SettlerEntity restored = reloadAmmoCourier(helper, f.courier());
        restored.setPos(f.lodgePos().getX() + 0.5D,
            f.lodgePos().getY(), f.lodgePos().getZ() + 1.5D);
        CourierWorkGoal fresh = new CourierWorkGoal(restored);
        helper.assertTrue(fresh.canUse(),
            "fresh goal must reconstruct the original in-transit Lodge route");
        fresh.start();
        helper.succeedWhen(() -> {
            fresh.tick();
            helper.assertTrue(countIn(f.source(), Items.ARROW) == 40 - moved
                    && countIn(f.otherWarehouse(), Items.ARROW) == 0
                    && countIn(f.source(), Items.ARROW) + countIn(f.lodgeChest(), Items.ARROW)
                        + bagCountOf(restored, Items.ARROW) == 43,
                "every reloaded destination unit must conserve arrows without a second source debit");
            RequestRecord completed = ammoLedger(helper, f.settlement())
            .any(request.id());
            helper.assertTrue(completed != null
                && completed.state() == RequestState.SATISFIED
                && completed.hasFullTransportTrace(),
            "the original AMMUNITION row must own the complete transport trace");
            helper.assertTrue(countIn(f.source(), Items.ARROW) == 40 - moved
                && countIn(f.lodgeChest(), Items.ARROW)
                    == 3 + moved
                && bagCountOf(restored, Items.ARROW) == 0
                && countIn(f.otherWarehouse(), Items.ARROW) == 0,
            "reload must deliver once to the exact Lodge, never anonymous storage");
            helper.assertTrue(countIn(f.source(), Items.ARROW)
                + countIn(f.lodgeChest(), Items.ARROW) == 43,
            "source, bag and Lodge must conserve all seeded shafts");
            fresh.stop();
        });
    }

    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "hunter_lodge_ammunition")
    public void externallyFilledLodgeReturnsTrackedAmmoToExactSource(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 40, 3, true);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        pickupAmmo(helper, f, f.courier(), request);
        int moved = request.fingerprint().count();
        int externalFill = SettlerEntity.ARCHER_QUIVER_CAPACITY - 3;
        f.lodgeChest().getItem(0).grow(externalFill);
        f.lodgeChest().setChanged();

        SettlerEntity restored = reloadAmmoCourier(helper, f.courier());
        restored.setPos(f.lodgePos().getX() + 0.5D,
            f.lodgePos().getY() + 1.0D, f.lodgePos().getZ() + 0.5D);
        RequestLedgerService.Decision refused = RequestLedgerService.deliver(
            helper.getLevel(), f.settlement(), request.id(), restored);
        helper.assertTrue(!refused.accepted()
                && refused.blocker() == RequestBlocker.TARGET_FULL,
            "an externally filled Lodge must refuse every tracked surplus shaft");

        restored.setPos(f.sourcePos().getX() + 0.5D,
            f.sourcePos().getY() + 1.0D, f.sourcePos().getZ() + 0.5D);
        CourierWorkGoal recovered = new CourierWorkGoal(restored);
        helper.assertTrue(recovered.canUse(),
            "fresh blocked route must recover toward its exact source");
        recovered.start();
        for (int tick = 0; tick < 40
                && bagCountOf(restored, Items.ARROW) > 0; tick++) {
            recovered.tick();
        }
        recovered.stop();

        RequestRecord terminal = ammoLedger(helper, f.settlement())
            .any(request.id());
        helper.assertTrue(terminal != null
                && terminal.state() == RequestState.CANCELLED
                && terminal.deliveredCount() == 0,
            "return must terminalise the same request without erasing its history");
        helper.assertTrue(countIn(f.source(), Items.ARROW) == 40
                && countIn(f.lodgeChest(), Items.ARROW)
                    == SettlerEntity.ARCHER_QUIVER_CAPACITY
                && bagCountOf(restored, Items.ARROW) == 0
                && countIn(f.otherWarehouse(), Items.ARROW) == 0,
            "tracked surplus must return only to its recorded Warehouse source");
        helper.assertTrue(countIn(f.source(), Items.ARROW)
                + countIn(f.lodgeChest(), Items.ARROW) == 43 + externalFill,
            "external Lodge fill plus the original forty-three shafts must conserve");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "hunter_lodge_ammunition")
    public void partialAmmoDeliverySurvivesLegitimateConsumption(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 40, 3, false);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        pickupAmmo(helper, f, f.courier(), request);
        f.courier().setPos(f.lodgePos().getX() + 0.5D,
            f.lodgePos().getY() + 1.0D, f.lodgePos().getZ() + 0.5D);

        RequestLedgerService.Decision partial =
            RequestLedgerService.deliver(helper.getLevel(), f.settlement(),
                request.id(), f.courier(), 5);
        helper.assertTrue(partial.outcome()
                == RequestLedgerService.Outcome.COMMITTED
                && partial.request().deliveredCount() == 5
                && bagCountOf(f.courier(), Items.ARROW)
                    == request.fingerprint().count() - 5,
            "a bounded first contact must record exactly five delivered arrows");
        int consumed = f.lodgeChest().removeItem(0, 5).getCount();
        helper.assertTrue(consumed == 5,
            "fixture must model a legitimate Hunter/player withdrawal");

        RequestLedgerService.Decision completed =
            RequestLedgerService.deliver(helper.getLevel(), f.settlement(),
                request.id(), f.courier());
        helper.assertTrue(completed.outcome()
                == RequestLedgerService.Outcome.SATISFIED
                && completed.request().hasFullTransportTrace(),
            "local transaction evidence must survive consumption between deposits");
        helper.assertTrue(countIn(f.source(), Items.ARROW)
                + countIn(f.lodgeChest(), Items.ARROW)
                + bagCountOf(f.courier(), Items.ARROW) + consumed == 43,
            "partial deposit, consumption and completion must conserve all shafts");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "hunter_lodge_ammunition")
    public void reboundLodgeCannotReceiveSavedAmmo(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 40, 3, true);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        pickupAmmo(helper, f, f.courier(), request);
        int moved = request.fingerprint().count();

        f.lodge().valid = false;
        Building replacement = addBuilding(helper, f.settlement(),
            BuildingType.HUNTERS_LODGE, new BlockPos(8, 1, 8),
            new BlockPos(10, 3, 10), new BlockPos(8, 1, 8));
        BlockPos replacementRel = new BlockPos(9, 1, 9);
        helper.setBlock(replacementRel, Blocks.CHEST);
        Container replacementChest = containerAt(helper, replacementRel);
        helper.assertTrue(replacementChest != null,
            "rebound Lodge must expose a different physical endpoint");

        SettlerEntity restored = reloadAmmoCourier(helper, f.courier());
        restored.setPos(f.sourcePos().getX() + 0.5D,
            f.sourcePos().getY() + 1.0D, f.sourcePos().getZ() + 0.5D);
        CourierWorkGoal recovered = new CourierWorkGoal(restored);
        helper.assertTrue(recovered.canUse(),
            "invalid exact target with a valid source must recover the bag");
        recovered.start();
        for (int tick = 0; tick < 40
                && bagCountOf(restored, Items.ARROW) > 0; tick++) {
            recovered.tick();
        }
        recovered.stop();

        helper.assertTrue(countIn(f.source(), Items.ARROW) == 40
                && countIn(replacementChest, Items.ARROW) == 0
                && countIn(f.lodgeChest(), Items.ARROW) == 3
                && countIn(f.otherWarehouse(), Items.ARROW) == 0
                && bagCountOf(restored, Items.ARROW) == 0,
            "rebound identity must not receive tracked cargo or trigger fallback");
        helper.assertTrue(ammoLedger(helper, f.settlement())
                .any(request.id()).state() == RequestState.CANCELLED
                && countIn(f.source(), Items.ARROW)
                    + countIn(f.lodgeChest(), Items.ARROW) == 43,
            "exact-source cancellation must conserve all original arrows");
        helper.assertTrue(!replacement.id.equals(request.targetBuildingId())
                && moved > 0,
            "fixture must prove a real target-identity change after pickup");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "hunter_lodge_ammunition")
    public void partialSourceRoomKeepsWholeTrackedRemainderUntilRetry(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 8, 8, false);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        pickupAmmo(helper, f, f.courier(), request);
        helper.assertTrue(request.fingerprint().count() == 8,
            "fixture must own an exact eight-arrow in-transit remainder");

        f.source().setItem(0, new ItemStack(Items.ARROW, 60));
        for (int slot = 1; slot < f.source().getContainerSize(); slot++) {
            f.source().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        f.courier().setPos(f.sourcePos().getX() + 0.5D,
            f.sourcePos().getY() + 1.0D, f.sourcePos().getZ() + 0.5D);
        RequestLedgerSavedData trusted = RequestLedgerSavedData.get(
            helper.getLevel());
        CompoundTag quarantinedTag = trusted.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        quarantinedTag.putBoolean("RootQuarantined", true);
        quarantinedTag.putString("QuarantineReason", "fixture");
        RequestLedgerSavedData quarantined = RequestLedgerSavedData.load(
            quarantinedTag, helper.getLevel().registryAccess());
        helper.getLevel().getDataStorage().set(
            "hearthstead_request_ledger", quarantined);
        RequestLedgerService.Decision refusedQuarantine =
            RequestLedgerService.returnAmmunitionToSource(helper.getLevel(),
                f.settlement(), request.id(), f.courier());
        helper.assertTrue(refusedQuarantine.outcome()
                == RequestLedgerService.Outcome.QUARANTINED
                && countIn(f.source(), Items.ARROW) == 60
                && bagCountOf(f.courier(), Items.ARROW) == 8,
            "quarantined ledger authority must mutate neither source nor bag");
        helper.getLevel().getDataStorage().set(
            "hearthstead_request_ledger", trusted);

        RequestLedgerService.Decision blocked =
            RequestLedgerService.returnAmmunitionToSource(helper.getLevel(),
                f.settlement(), request.id(), f.courier());
        helper.assertTrue(!blocked.accepted()
                && blocked.blocker() == RequestBlocker.TARGET_FULL
                && countIn(f.source(), Items.ARROW) == 60
                && bagCountOf(f.courier(), Items.ARROW) == 8
                && ammoLedger(helper, f.settlement()).any(request.id()).state()
                    != RequestState.CANCELLED,
            "four free source slots must mutate neither half of an eight-arrow return");

        f.source().setItem(1, ItemStack.EMPTY);
        RequestLedgerService.Decision returned =
            RequestLedgerService.returnAmmunitionToSource(helper.getLevel(),
                f.settlement(), request.id(), f.courier());
        RequestRecord terminal = ammoLedger(helper, f.settlement())
            .any(request.id());
        helper.assertTrue(returned.accepted() && terminal != null
                && terminal.state() == RequestState.CANCELLED
                && terminal.deliveredCount() == 0
                && countIn(f.source(), Items.ARROW) == 68
                && bagCountOf(f.courier(), Items.ARROW) == 0,
            "clearing full remainder room must atomically return and terminalise");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 80,
        batch = "hunter_lodge_ammunition")
    public void playerSourceRemovalStillAllowsLocalDeltaReturn(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 40, 3, false);
        RequestRecord request = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        pickupAmmo(helper, f, f.courier(), request);
        int moved = request.fingerprint().count();
        int externalRemoval = f.source().removeItem(0,
            f.source().getItem(0).getCount()).getCount();
        f.courier().setPos(f.sourcePos().getX() + 0.5D,
            f.sourcePos().getY() + 1.0D, f.sourcePos().getZ() + 0.5D);

        RequestLedgerService.Decision returned =
            RequestLedgerService.returnAmmunitionToSource(helper.getLevel(),
                f.settlement(), request.id(), f.courier());
        RequestRecord terminal = ammoLedger(helper, f.settlement())
            .any(request.id());
        helper.assertTrue(returned.accepted() && terminal != null
                && terminal.state() == RequestState.CANCELLED
                && terminal.deliveredCount() == 0,
            "external source withdrawal must not invalidate a same-tick return delta");
        helper.assertTrue(externalRemoval == 40 - moved
                && countIn(f.source(), Items.ARROW) == moved
                && bagCountOf(f.courier(), Items.ARROW) == 0
                && countIn(f.source(), Items.ARROW)
                    + countIn(f.lodgeChest(), Items.ARROW)
                    + externalRemoval == 43,
            "tracked return plus explicit player removal must conserve all shafts");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "hunter_lodge_ammunition")
    public void twoCouriersAndSourceSlotsCannotOverReserveLodge(
            GameTestHelper helper) {
        AmmoFixture f = ammoFixture(helper, 10, 0, true);
        f.source().setItem(1, new ItemStack(Items.ARROW, 10));
        SettlerEntity other = courier(helper, f.settlement(),
            new BlockPos(4, 1, 9));
        other.setNoAi(true);

        RequestRecord first = openAndReserveAmmo(helper, f, f.courier(),
            0, 64);
        RequestLedgerService.Decision duplicate =
            RequestLedgerService.openAmmunitionDelivery(helper.getLevel(),
                f.settlement(), f.warehouse(), f.sourcePos(), 1, f.lodge(),
                f.lodgePos(), 64);
        helper.assertTrue(duplicate.outcome()
                == RequestLedgerService.Outcome.DUPLICATE
                && duplicate.request() != null
                && duplicate.request().id().equals(first.id())
                && !RequestLedgerService.reserve(helper.getLevel(),
                    f.settlement(), first.id(), other).accepted(),
            "another source slot and Courier must resolve to the same owned deficit");

        pickupAmmo(helper, f, f.courier(), first);
        f.courier().setPos(f.lodgePos().getX() + 0.5D,
            f.lodgePos().getY() + 1.0D, f.lodgePos().getZ() + 0.5D);
        helper.assertTrue(RequestLedgerService.deliver(helper.getLevel(),
                f.settlement(), first.id(), f.courier()).outcome()
                == RequestLedgerService.Outcome.SATISFIED,
            "first exact ten-arrow request must complete");

        RequestRecord second = openAndReserveAmmo(helper, f, other, 1, 64);
        helper.assertTrue(second.fingerprint().count() == 8,
            "second request must be bounded by the Courier's eight-item bag");
        pickupAmmo(helper, f, other, second);
        other.setPos(f.lodgePos().getX() + 0.5D,
            f.lodgePos().getY() + 1.0D, f.lodgePos().getZ() + 0.5D);
        helper.assertTrue(RequestLedgerService.deliver(helper.getLevel(),
                f.settlement(), second.id(), other).outcome()
                == RequestLedgerService.Outcome.SATISFIED,
            "second Courier may claim only after the first row terminalises");
        helper.assertTrue(countIn(f.lodgeChest(), Items.ARROW)
                == SettlerEntity.ARCHER_QUIVER_CAPACITY
                && countIn(f.source(), Items.ARROW) == 4
                && bagCountOf(f.courier(), Items.ARROW) == 0
                && bagCountOf(other, Items.ARROW) == 0
                && countIn(f.otherWarehouse(), Items.ARROW) == 0,
            "two slots and Couriers must conserve twenty arrows without over-cap");
        helper.succeed();
    }

    private static int bagCountOf(SettlerEntity settler, Item item) {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }
}
