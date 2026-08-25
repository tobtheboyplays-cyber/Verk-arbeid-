package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The collection route (FLOWS.md route 4): workshop OUTPUTS -> warehouse,
 * the return leg of the economy loop. {@link LogisticsGameTests} proves the
 * warehouse -> crafter restock leg and its reservation ledger; this file
 * proves the leg pointing the other way -- without it the mason's bricks,
 * the smelter's ingots and the mine's entire chest contents strand forever
 * in their own buildings, and the smithy can only ever be fed by hand.
 *
 * <p>Three claims, one test each: a mine's yield is collected completely
 * (keep-back zero, conservation exact); a workshop's output is collected
 * only down to {@link CourierWorkGoal#OUTPUT_KEEP_BACK}; and a workshop's
 * INPUT item is never touched by this route at all -- collecting it back
 * out would be the exact carousel the restock route just paid a trip to
 * prevent.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CourierWorkshopRouteGameTests {

    // ------------------------------------------------------------ fixtures ---

    /** Copied from {@link LogisticsGameTests}: flat floor, low rim wall. */
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

    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Bud");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settler.assignProfession(Profession.COURIER);
        return settler;
    }

    /** Hearth + bound settlement -- the standard courier fixture opening. */
    private static Settlement standardOpening(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, hearthRel, 6);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        return s;
    }

    // ----------------------------------------------------------- the mine ---

    /**
     * A mine has no Production table: its chests are pure yield, so the
     * whole contents are surplus (keep-back zero) and every last block must
     * reach the warehouse -- exactly 12 of 12, chest-true at every tick in
     * between (a transient dip or bump in the total is a conservation bug
     * even if the end state looks right, which is why the total is asserted
     * on every poll, bag included). The ledger is also watched directly,
     * the same way {@link LogisticsGameTests} watches the restock lock: the
     * collection trip must claim its (building, item) key and release it
     * once the job resolves.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
    public void mineYieldIsCollectedCompletely(GameTestHelper helper) {
        Settlement s = standardOpening(helper);

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);

        Building mineB = addBuilding(helper, s, BuildingType.MINE,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);
        Container mineChest = containerAt(helper, new BlockPos(3, 1, 6));
        helper.assertTrue(mineChest != null, "arena mine chest should exist");
        mineChest.setItem(0, new ItemStack(Items.COBBLESTONE, 12));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] sawHeld = {false};
        final boolean[] sawReleasedAfterHold = {false};

        helper.succeedWhen(() -> {
            boolean held = CourierWorkGoal.restockJobIsHeld(mineB.id, Items.COBBLESTONE);
            if (held) {
                sawHeld[0] = true;
            }
            if (sawHeld[0] && !held) {
                sawReleasedAfterHold[0] = true;
            }
            int atMine = countIn(containerAt(helper, new BlockPos(3, 1, 6)),
                Items.COBBLESTONE);
            int atWarehouse = countIn(containerAt(helper, new BlockPos(5, 1, 3)),
                Items.COBBLESTONE);
            int inBag = bagCountOf(bud, Items.COBBLESTONE);
            int total = atMine + atWarehouse + inBag;
            helper.assertTrue(total == 12,
                "cobblestone must be conserved across the collection route, saw " + total
                    + " [mine=" + atMine + " warehouse=" + atWarehouse + " bag=" + inBag
                    + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atWarehouse == 12 && atMine == 0,
                "all 12 cobblestone should move mine -> warehouse (keep-back is zero "
                    + "for a mine), saw warehouse=" + atWarehouse + " mine=" + atMine
                    + " [bag=" + inBag + " act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(sawHeld[0],
                "the collection trip never claimed its reservation key -- the shared "
                    + "ledger is not guarding this route");
            helper.assertTrue(sawReleasedAfterHold[0],
                "the collection reservation was claimed but never released once the "
                    + "job resolved");
        });
    }

    // ------------------------------------------------------- the keep-back ---

    /**
     * A producing building keeps {@link CourierWorkGoal#OUTPUT_KEEP_BACK}
     * of each output item: 20 iron ingots in the smelter means exactly 12
     * travel and exactly 8 stay, and the smelter's chest must never be seen
     * below 8 even for one tick -- a courier who lifts the whole pile and
     * puts 8 back later would pass an end-state check and still have
     * broken the buffer the keep-back exists to guarantee.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
    public void workshopOutputKeepsItsKeepBack(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        int stocked = 20;
        int surplus = stocked - CourierWorkGoal.OUTPUT_KEEP_BACK; // 12

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);

        addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);
        Container smelterChest = containerAt(helper, new BlockPos(3, 1, 6));
        helper.assertTrue(smelterChest != null, "arena smelter chest should exist");
        smelterChest.setItem(0, new ItemStack(Items.IRON_INGOT, stocked));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] keepBackDipped = {false};

        helper.succeedWhen(() -> {
            int atSmelter = countIn(containerAt(helper, new BlockPos(3, 1, 6)),
                Items.IRON_INGOT);
            int atWarehouse = countIn(containerAt(helper, new BlockPos(5, 1, 3)),
                Items.IRON_INGOT);
            int inBag = bagCountOf(bud, Items.IRON_INGOT);
            if (atSmelter < CourierWorkGoal.OUTPUT_KEEP_BACK) {
                keepBackDipped[0] = true;
            }
            int total = atSmelter + atWarehouse + inBag;
            helper.assertTrue(total == stocked,
                "iron ingots must be conserved across the collection route, saw " + total
                    + " [smelter=" + atSmelter + " warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity() + "]");
            helper.assertTrue(!keepBackDipped[0],
                "the smelter's chest dipped below the keep-back of "
                    + CourierWorkGoal.OUTPUT_KEEP_BACK + " -- the buffer must never "
                    + "be lifted, not even transiently");
            helper.assertTrue(
                atWarehouse == surplus && atSmelter == CourierWorkGoal.OUTPUT_KEEP_BACK,
                "exactly " + surplus + " ingots should travel and exactly "
                    + CourierWorkGoal.OUTPUT_KEEP_BACK + " stay behind, saw warehouse="
                    + atWarehouse + " smelter=" + atSmelter + " [bag=" + inBag
                    + " act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
        });
    }

    // ------------------------------------------------- inputs are off-limits ---

    /**
     * Raw iron in the smelter is the smelter's raw material -- the exact
     * cargo the restock route delivers TO it -- and the collection route
     * must not haul it back out, however much of it is sitting there. The
     * ingot surplus beside it is the positive control: the courier provably
     * works this building and this chest (the ingots travel), so the raw
     * iron staying put is a decision, not an idle courier. The raw-iron
     * watch is a latch, not an end-state read: if a single poll ever sees
     * it outside the smelter's chest, the test cannot pass.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
    public void workshopInputsAreNeverCollected(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        int rawStocked = 10;
        int ingotStocked = 20;
        int ingotSurplus = ingotStocked - CourierWorkGoal.OUTPUT_KEEP_BACK; // 12

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(5, 1, 3), Blocks.CHEST);

        addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(new BlockPos(3, 1, 6), Blocks.CHEST);
        Container smelterChest = containerAt(helper, new BlockPos(3, 1, 6));
        helper.assertTrue(smelterChest != null, "arena smelter chest should exist");
        smelterChest.setItem(0, new ItemStack(Items.RAW_IRON, rawStocked));
        smelterChest.setItem(1, new ItemStack(Items.IRON_INGOT, ingotStocked));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] rawEverLeft = {false};

        helper.succeedWhen(() -> {
            Container atSmelterC = containerAt(helper, new BlockPos(3, 1, 6));
            Container atWarehouseC = containerAt(helper, new BlockPos(5, 1, 3));
            int rawAtSmelter = countIn(atSmelterC, Items.RAW_IRON);
            int rawAtWarehouse = countIn(atWarehouseC, Items.RAW_IRON);
            int rawInBag = bagCountOf(bud, Items.RAW_IRON);
            if (rawAtSmelter != rawStocked || rawAtWarehouse != 0 || rawInBag != 0) {
                rawEverLeft[0] = true;
            }
            int ingotAtSmelter = countIn(atSmelterC, Items.IRON_INGOT);
            int ingotAtWarehouse = countIn(atWarehouseC, Items.IRON_INGOT);
            int ingotInBag = bagCountOf(bud, Items.IRON_INGOT);
            int ingotTotal = ingotAtSmelter + ingotAtWarehouse + ingotInBag;
            helper.assertTrue(ingotTotal == ingotStocked,
                "iron ingots must be conserved, saw " + ingotTotal
                    + " [smelter=" + ingotAtSmelter + " warehouse=" + ingotAtWarehouse
                    + " bag=" + ingotInBag + "]");
            helper.assertTrue(
                ingotAtWarehouse == ingotSurplus
                    && ingotAtSmelter == CourierWorkGoal.OUTPUT_KEEP_BACK,
                "positive control: the ingot surplus of " + ingotSurplus
                    + " should reach the warehouse, saw warehouse=" + ingotAtWarehouse
                    + " smelter=" + ingotAtSmelter + " [bag=" + ingotInBag
                    + " act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(!rawEverLeft[0],
                "raw iron is the smelter's INPUT and must never be collected away -- "
                    + "it was seen outside the smelter's chest [smelterNow=" + rawAtSmelter
                    + " warehouseNow=" + rawAtWarehouse + " bagNow=" + rawInBag + "]");
        });
    }
}
