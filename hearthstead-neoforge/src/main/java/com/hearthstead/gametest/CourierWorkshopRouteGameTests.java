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

    private static final BlockPos HEARTH_REL = new BlockPos(2, 1, 2);

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static HearthBlockEntity hearthAt(GameTestHelper helper) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(HEARTH_REL));
        return be instanceof HearthBlockEntity h ? h : null;
    }

    /** The hearth is an ItemStackHandler, not a Container -- its own loop
     *  (copied from {@link CourierFoodRouteGameTests}). */
    private static int countInHearth(HearthBlockEntity hearth, Item item) {
        if (hearth == null) {
            return 0;
        }
        var inv = hearth.getInventory();
        int n = 0;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_workshop_route_day")
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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_workshop_route_day")
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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_workshop_route_day")
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

    // --------------------------------------------------- gathering buildings ---

    /**
     * SEAM FINDING 1 (adversarial review, 2026-08-26). Before this fix,
     * {@code CourierWorkGoal#findCollectionJob} recognised only
     * {@link BuildingType#MINE} as a pure-yield source: PASTURE, FISHERY
     * and HUNTERS_LODGE have no {@code Production} table either, but the
     * gate special-cased MINE by name and skipped every other
     * no-Production building outright. The fisher's cod, the herder's wool
     * and eggs and the hunter's meat and hides sat in their own chests
     * forever -- no courier ever collected them, no warehouse ever saw
     * them, and no settler could ever eat any of it.
     *
     * <p>This proves the whole seam end to end for the one gathering
     * building whose yield is also FOOD, so a reverted fix fails for the
     * reason that actually mattered (a starving settlement), not merely a
     * chest count: seeded cod must physically travel fishery -> warehouse
     * -> hearth, and a genuinely hungry settler (hunger pinned below
     * {@code EatFromHearthGoal}'s eat line of 40) must actually eat some of
     * it through the real goal -- proven by the settler's own hunger
     * rising, not inferred from item movement alone. If the collection gate
     * is reverted to MINE-only, the cod never leaves the fishery, the
     * hearth never receives any, and the eater's hunger never rises above
     * its starting point -- this fails loudly instead of going quiet.
     *
     * <p>Registered through {@link GameTestFixtures#register}, the one
     * sanctioned path for a synthetic {@link Building} (FLAKE-2) -- a
     * hand-rolled fixture that forgets the plaque loses its building to
     * {@code BuildingManager}'s sweep mid-test for a reason that has
     * nothing to do with what this test claims to prove.
     */
    @GameTest(template = "empty16", timeoutTicks = 4800, batch = "courier_workshop_route_day")
    public void gatheredCodReachesAWarehouseAndFeedsAHungrySettler(GameTestHelper helper) {
        Settlement s = standardOpening(helper);

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);

        GameTestFixtures.register(helper, s, BuildingType.FISHERY, 8, 2);
        BlockPos fisheryChestRel = new BlockPos(9, 1, 3);
        helper.setBlock(fisheryChestRel, Blocks.CHEST);
        Container fisheryChest = containerAt(helper, fisheryChestRel);
        helper.assertTrue(fisheryChest != null, "arena fishery chest should exist");
        int seeded = 10;
        fisheryChest.setItem(0, new ItemStack(Items.COD, seeded));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 10));

        SettlerEntity eater = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(1, 1, 1));
        eater.setSettlerName("Sulten");
        eater.bindTo(s.id, s.center);
        s.putRecord(eater.getUUID(), eater.getSettlerName(), Profession.NONE);
        float startingHunger = 15.0F; // below EatFromHearthGoal's hunger < 40 eat line
        eater.setHunger(startingHunger);

        final int[] maxSeenAtWarehouse = {0};

        helper.succeedWhen(() -> {
            int atFishery = countIn(containerAt(helper, fisheryChestRel), Items.COD);
            int atWarehouse = countIn(containerAt(helper, warehouseChestRel), Items.COD);
            int atHearth = countInHearth(hearthAt(helper), Items.COD);
            int inBudBag = bagCountOf(bud, Items.COD);
            maxSeenAtWarehouse[0] = Math.max(maxSeenAtWarehouse[0], atWarehouse);
            int accounted = atFishery + atWarehouse + atHearth + inBudBag;
            helper.assertTrue(accounted <= seeded,
                "cod must never exceed the seeded total (nothing is ever minted), saw "
                    + accounted + " of " + seeded + " [fishery=" + atFishery
                    + " warehouse=" + atWarehouse + " hearth=" + atHearth
                    + " bag=" + inBudBag + "]");
            helper.assertTrue(maxSeenAtWarehouse[0] > 0,
                "the fishery's cod was never seen reaching the warehouse -- the "
                    + "collection gate is still skipping FISHERY [fishery=" + atFishery
                    + " budAct=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(eater.getHunger() > startingHunger,
                "a genuinely hungry settler (hunger pinned at " + startingHunger
                    + ", below EatFromHearthGoal's eat line of 40) should have actually "
                    + "eaten some of the delivered cod by now, saw hunger="
                    + eater.getHunger() + " [accounted=" + accounted + " of " + seeded
                    + " fishery=" + atFishery + " warehouse=" + atWarehouse
                    + " hearth=" + atHearth + " eaterAct=" + eater.getActivity() + "]");
        });
    }

    // ----------------------------------------------------- dual-role stability ---

    /**
     * SEAM FINDING 2 (adversarial review, 2026-08-26). Raising {@code
     * CourierWorkGoal#MATERIAL_RESERVE_BATCHES} from 1 to 4 pushed the
     * mason's own STONE restock target (stone_bricks needs 4 stone/batch,
     * so 4 x 4 = 16) above the fixed collection floor of {@code
     * OUTPUT_KEEP_BACK} (8) -- collection trimmed the mason back to 8,
     * restock's very next look saw her short of 16 and hauled the identical
     * stack straight back in, forever, with restock as {@code
     * JobPriority}'s TOP tier so the courier never even reached the food
     * route. The fix raises {@code CourierWorkGoal#keepBackFor} so the
     * collection floor for a dual-role item is always >= its own restock
     * target.
     *
     * <p>Proven not by asserting the mason's stone equals some number
     * after the fact, but by seeding EXACTLY the stable point (16 stone,
     * with zero cobblestone so nobody is hired to actually run the "stone"
     * recipe and confound the count with real production) and watching it
     * for a window long enough that the old bug's shuttle -- collect
     * 16 -> 8, then restock 8 -> 16 -- would have completed at least twice
     * over. If the fix is reverted, {@code keepBackFor} goes back to a flat
     * 8 for STONE, {@code findSurplusOutput} sees 16 > 8 on its very first
     * look, and the very first poll below already fails.
     *
     * <p>Registered through {@link GameTestFixtures#register}, the one
     * sanctioned path for a synthetic {@link Building} (FLAKE-2).
     */
    @GameTest(template = "empty16", timeoutTicks = 3600, batch = "courier_workshop_route_day")
    public void masonsDualRoleStoneReachesAStableRestNotAShuttle(GameTestHelper helper) {
        Settlement s = standardOpening(helper);

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);
        Container warehouseChest = containerAt(helper, warehouseChestRel);
        helper.assertTrue(warehouseChest != null, "arena warehouse chest should exist");
        int warehouseSeed = 32; // plenty spare -- a bugged shuttle is never
        // starved for supply; if it moves at all, that is the bug, not a
        // starved trip.
        warehouseChest.setItem(0, new ItemStack(Items.STONE, warehouseSeed));

        GameTestFixtures.register(helper, s, BuildingType.MASON, 8, 2);
        BlockPos masonChestRel = new BlockPos(9, 1, 3);
        helper.setBlock(masonChestRel, Blocks.CHEST);
        Container masonChest = containerAt(helper, masonChestRel);
        helper.assertTrue(masonChest != null, "arena mason chest should exist");
        // The stable point itself: MATERIAL_RESERVE_BATCHES(4) x
        // stone_bricks' own inputCount(4) = 16 -- exactly what keepBackFor
        // must now also return for STONE at a MASON. No cobblestone seeded,
        // so Production never has a reason to touch this count either.
        int stable = CourierWorkGoal.MATERIAL_RESERVE_BATCHES * 4;
        helper.assertTrue(stable == 16,
            "fixture arithmetic: expected the mason's stone_bricks recipe to need "
                + "4 stone/batch -- a constant changed under this test");
        masonChest.setItem(0, new ItemStack(Items.STONE, stable));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 10));

        final int[] stableTicks = {0};
        final int[] minSeen = {Integer.MAX_VALUE};
        final int[] maxSeen = {0};

        helper.succeedWhen(() -> {
            int atMason = countIn(containerAt(helper, masonChestRel), Items.STONE);
            int atWarehouse = countIn(containerAt(helper, warehouseChestRel), Items.STONE);
            int inBag = bagCountOf(bud, Items.STONE);
            minSeen[0] = Math.min(minSeen[0], atMason);
            maxSeen[0] = Math.max(maxSeen[0], atMason);
            int total = atMason + atWarehouse + inBag;
            helper.assertTrue(total == stable + warehouseSeed,
                "stone must be conserved across the whole watch, saw " + total
                    + " [mason=" + atMason + " warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atMason == stable,
                "the mason's own stone must never move from the stable point of "
                    + stable + " -- a courier touched it (min seen " + minSeen[0]
                    + ", max seen " + maxSeen[0] + "), saw mason=" + atMason
                    + " [warehouse=" + atWarehouse + " bag=" + inBag
                    + " act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            // A window long enough that the OLD shuttle (collect 16 -> 8,
            // then restock 8 -> 16) would have completed at least twice:
            // each leg is a full courier round trip, so two full cycles is
            // a real, generous margin, not a hair-trigger race on timing.
            stableTicks[0]++;
            helper.assertTrue(stableTicks[0] >= 3000,
                "watching the whole window for a shuttle: " + stableTicks[0] + "/3000");
        });
    }
}
