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
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
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

    /**
     * A building registered the way every other courier fixture registers
     * one: bounds and anchor handed in directly, {@code valid} forced true.
     * A real plaque block still has to exist at the anchor -- the same
     * belt-and-braces {@code addWarehouse} in {@link CourierGameTests}
     * documents, since BuildingManager's sweep dissolves any building whose
     * plaque position holds no plaque.
     */
    private static Building addBuilding(GameTestHelper helper, Settlement s, BuildingType type,
                                        BlockPos minRel, BlockPos maxRel, BlockPos anchorRel) {
        helper.setBlock(anchorRel, ModBlocks.PLAQUE.get());
        BoundingBox bounds = BoundingBox.fromCorners(
            helper.absolutePos(minRel), helper.absolutePos(maxRel));
        Building b = new Building(UUID.randomUUID(), type,
            helper.absolutePos(anchorRel), helper.absolutePos(anchorRel), bounds);
        b.valid = true;
        s.buildings.add(b);
        return b;
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

    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Bud");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settler.assignProfession(Profession.COURIER);
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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
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
        source.setItem(0, new ItemStack(Items.RAW_IRON, 4));

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
            helper.assertTrue(total == 4,
                "raw iron must be conserved across the whole route, saw " + total
                    + " [smelter=" + atSmelter + " warehouse=" + atWarehouse
                    + " firstBag=" + bagCount(first) + " secondBag=" + bagCount(second) + "]");
            helper.assertTrue(atSmelter == 4,
                "all 4 raw iron should reach the smelter, saw " + atSmelter
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
     * Three part-stacks of the same item, scattered across three chests,
     * with a courier employed to tidy them. This must both consolidate --
     * every existing GameTest that checked this class checked a single
     * merge, never whether it keeps going and then genuinely stops -- and
     * stay converged: once the warehouse reads as tidy the goal must go
     * quiet, not keep re-selecting and re-scanning forever.
     */
    @GameTest(template = "empty16", timeoutTicks = 2000, batch = "day")
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
        int[] amounts = {20, 15, 10}; // 45 total: one stack (max 64) fits it all
        for (int i = 0; i < chestRel.length; i++) {
            helper.setBlock(chestRel[i], Blocks.CHEST);
            Container c = containerAt(helper, chestRel[i]);
            helper.assertTrue(c != null, "arena chest " + i + " should exist");
            c.setItem(0, new ItemStack(Items.WHEAT, amounts[i]));
        }
        int expectedTotal = 20 + 15 + 10;

        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 6));
        warehouse.workers.add(bud.getUUID()); // TidyWarehouseGoal requires employment

        final int[] stableTicks = {0};
        final int[] maxDistinctSeen = {0};

        helper.succeedWhen(() -> {
            int total = 0;
            int distinctChests = 0;
            for (BlockPos rel : chestRel) {
                int here = countIn(containerAt(helper, rel), Items.WHEAT);
                total += here;
                if (here > 0) {
                    distinctChests++;
                }
            }
            maxDistinctSeen[0] = Math.max(maxDistinctSeen[0], distinctChests);
            helper.assertTrue(total == expectedTotal,
                "tidying must conserve every wheat, saw " + total + " of " + expectedTotal);

            boolean settled = distinctChests <= 1
                && bud.getActivity() != SettlerActivity.SORTING;
            // A streak, not a one-shot check: the whole point is that once
            // tidy, it STAYS tidy and the goal STAYS quiet -- a courier who
            // reaches one chest and then immediately churns again would
            // pass a single-tick check and fail this one.
            stableTicks[0] = settled ? stableTicks[0] + 1 : 0;
            helper.assertTrue(stableTicks[0] >= 150,
                "tidy should converge to one chest and go quiet, and stay quiet: "
                    + "distinctChests=" + distinctChests + " act=" + bud.getActivity()
                    + " stableTicks=" + stableTicks[0] + " maxDistinctSeen="
                    + maxDistinctSeen[0]);
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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
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

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);
        Container source = containerAt(helper, new BlockPos(5, 1, 3));
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 12));

        addBuilding(helper, s, BuildingType.SMITHY,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));

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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
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
}
