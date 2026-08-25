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
 * The food route (FLOWS.md route 5, the hearth half): warehouse -> hearth
 * when the larder runs LOW. {@link CourierWorkshopRouteGameTests} proves
 * outputs travel workshop -> warehouse and {@link LogisticsGameTests}
 * proves raw material travels warehouse -> crafter; this file proves the
 * one leg that feeds PEOPLE rather than production -- without it the
 * bakery's bread strands on a warehouse shelf while the village goes
 * hungry beside it, because settlers only ever eat from the hearth
 * ({@code EatFromHearthGoal}).
 *
 * <p>Three food claims and one fuel claim: a starving hearth is fed,
 * chest-true; a stocked hearth is left alone -- the LOW threshold
 * ({@link CourierWorkGoal#hearthFoodThreshold}) is a real gate, not
 * decoration; feeding the hearth outranks tidying a mine's shelves
 * (FOOD_DELIVERY sits above OUTPUT_COLLECTION on the ladder); and a cold
 * burner gets fuel hauled to it through the very same restock machinery.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CourierFoodRouteGameTests {

    // ------------------------------------------------------------ fixtures ---

    private static final BlockPos HEARTH_REL = new BlockPos(2, 1, 2);
    private static final BlockPos WAREHOUSE_CHEST_REL = new BlockPos(5, 1, 3);
    private static final BlockPos WORKSHOP_CHEST_REL = new BlockPos(3, 1, 6);

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

    /** The standard warehouse fixture: rooms at (4..6, 1..3, 2..4), one chest. */
    private static void addWarehouse(GameTestHelper helper, Settlement s) {
        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        helper.setBlock(WAREHOUSE_CHEST_REL, Blocks.CHEST);
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static HearthBlockEntity hearthAt(GameTestHelper helper) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(HEARTH_REL));
        return be instanceof HearthBlockEntity h ? h : null;
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

    /** The hearth is an ItemStackHandler, not a Container -- its own loop. */
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

    /**
     * A courier who will not snack on the evidence: every test here does
     * conservation arithmetic over seeded FOOD, and a peckish settler
     * genuinely eating a loaf ({@code EatFromHearthGoal} destroys the item,
     * as it should) would fail that arithmetic for the wrong reason. Hunger
     * starts pinned at 100; the morning work phase has no communal-meal
     * graze and idle drain is ~0.04/s, so it cannot fall anywhere near the
     * hunger-40 eat line inside any of these windows.
     */
    private static SettlerEntity courier(GameTestHelper helper, Settlement s, BlockPos rel) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName("Bud");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), settler.getSettlerName(), Profession.NONE);
        settler.assignProfession(Profession.COURIER);
        settler.setHunger(100.0F);
        return settler;
    }

    /** Hearth + bound settlement -- the standard courier fixture opening. */
    private static Settlement standardOpening(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        helper.setBlock(HEARTH_REL, ModBlocks.HEARTH.get());
        Settlement s = registerSettlement(helper, HEARTH_REL, 6);
        HearthBlockEntity hearth = hearthAt(helper);
        helper.assertTrue(hearth != null, "arena hearth should exist");
        hearth.bindSettlement(s.id);
        return s;
    }

    // ------------------------------------------------------- the delivery ---

    /**
     * A near-empty larder (one loaf, below the LOW mark of
     * {@link CourierWorkGoal#FOOD_PER_SETTLER} x 1 living settler = 4) and
     * sixteen loaves on a warehouse shelf: bread must reach the hearth
     * until the larder is at least at threshold, chest-true at every poll
     * -- hearth plus warehouse plus bag always accounts for all seventeen
     * loaves, so nothing was floored, voided or minted in transit. The
     * ledger is watched directly, the same way {@link LogisticsGameTests}
     * watches the restock lock: a food trip claims its (settlement, item)
     * key and releases it once the job resolves.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
    public void breadReachesTheStarvingHearth(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        hearthAt(helper).insertGoods(new ItemStack(Items.BREAD, 1));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 16));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        // Computed AFTER the courier's record exists: 1 living settler.
        int threshold = CourierWorkGoal.hearthFoodThreshold(s.population());
        helper.assertTrue(threshold == 4,
            "fixture arithmetic: one living settler should put the LOW mark at 4, got "
                + threshold);
        final boolean[] sawHeld = {false};
        final boolean[] sawReleasedAfterHold = {false};

        helper.succeedWhen(() -> {
            boolean held = CourierWorkGoal.restockJobIsHeld(s.id, Items.BREAD);
            if (held) {
                sawHeld[0] = true;
            }
            if (sawHeld[0] && !held) {
                sawReleasedAfterHold[0] = true;
            }
            int atHearth = countInHearth(hearthAt(helper), Items.BREAD);
            int atWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL), Items.BREAD);
            int inBag = bagCountOf(bud, Items.BREAD);
            int total = atHearth + atWarehouse + inBag;
            helper.assertTrue(total == 17,
                "bread must be conserved across the food route, saw " + total
                    + " [hearth=" + atHearth + " warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atHearth >= threshold,
                "the larder should be fed at least to its LOW mark of " + threshold
                    + ", saw " + atHearth + " [warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(inBag == 0,
                "the delivery should finish with an empty bag, saw " + inBag);
            helper.assertTrue(sawHeld[0],
                "the food trip never claimed its (settlement, item) reservation key -- "
                    + "the shared ledger is not guarding this route");
            helper.assertTrue(sawReleasedAfterHold[0],
                "the food reservation was claimed but never released once the job "
                    + "resolved");
        });
    }

    // ------------------------------------------------------- the threshold ---

    /**
     * The LOW mark is a real gate: a larder already at or above
     * {@link CourierWorkGoal#hearthFoodThreshold} must trigger NO delivery
     * at all, watched over a whole window rather than at one instant -- a
     * courier who fetched bread and put it back would pass an end-state
     * read and still have burned a round trip the threshold exists to
     * prevent. Every poll pins every count; any bread seen out of place at
     * any tick keeps failing to the timeout.
     */
    @GameTest(template = "empty16", timeoutTicks = 2000, batch = "day")
    public void stockedLarderTriggersNoDelivery(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        int seeded = 8;
        hearthAt(helper).insertGoods(new ItemStack(Items.BREAD, seeded));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 16));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        int threshold = CourierWorkGoal.hearthFoodThreshold(s.population());
        helper.assertTrue(seeded >= threshold,
            "fixture arithmetic: the seed of " + seeded
                + " must sit at/above the LOW mark of " + threshold);
        final int[] quietTicks = {0};

        helper.succeedWhen(() -> {
            int atHearth = countInHearth(hearthAt(helper), Items.BREAD);
            int atWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL), Items.BREAD);
            int inBag = bagCountOf(bud, Items.BREAD);
            helper.assertTrue(atWarehouse == 16 && atHearth == seeded && inBag == 0,
                "no bread may move while the larder is at/above its LOW mark of "
                    + threshold + ", saw [hearth=" + atHearth + " warehouse="
                    + atWarehouse + " bag=" + inBag + " act=" + bud.getActivity() + "]");
            helper.assertTrue(!CourierWorkGoal.restockJobIsHeld(s.id, Items.BREAD),
                "no food job should even be CLAIMED for a stocked larder -- the "
                    + "threshold gates the scan, not just the walk");
            quietTicks[0]++;
            helper.assertTrue(quietTicks[0] >= 400,
                "watching the whole window: " + quietTicks[0] + "/400 quiet ticks");
        });
    }

    // -------------------------------------------------------- the priority ---

    /**
     * A starving hearth outranks tidy shelves: with bread waiting in the
     * warehouse AND a mine chest full of pure-yield cobblestone (an
     * OUTPUT_COLLECTION job with keep-back zero,
     * {@link CourierWorkshopRouteGameTests} proves it on its own), the
     * bread must move FIRST. The watch is a latch: if any poll ever sees
     * cobblestone out of the mine while the larder is still below its LOW
     * mark, the test can never pass -- and the cobble must then still be
     * collected afterwards, so the ladder provably continues below the
     * food tier rather than starving it. Sized for two sequential hauls
     * (the bread trip, then 12 cobble at a carry capacity of 8 = two more
     * trips), hence the longer timeout.
     */
    @GameTest(template = "empty16", timeoutTicks = 4800, batch = "day")
    public void starvingHearthOutranksMineCollection(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 8));

        addBuilding(helper, s, BuildingType.MINE,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(WORKSHOP_CHEST_REL, Blocks.CHEST);
        Container mineChest = containerAt(helper, WORKSHOP_CHEST_REL);
        helper.assertTrue(mineChest != null, "arena mine chest should exist");
        mineChest.setItem(0, new ItemStack(Items.COBBLESTONE, 12));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        int threshold = CourierWorkGoal.hearthFoodThreshold(s.population());
        final boolean[] cobbleMovedBeforeBread = {false};

        helper.succeedWhen(() -> {
            int breadAtHearth = countInHearth(hearthAt(helper), Items.BREAD);
            int breadAtWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.BREAD);
            int breadInBag = bagCountOf(bud, Items.BREAD);
            int cobbleAtMine = countIn(containerAt(helper, WORKSHOP_CHEST_REL),
                Items.COBBLESTONE);
            int cobbleAtWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.COBBLESTONE);
            int cobbleInBag = bagCountOf(bud, Items.COBBLESTONE);
            if (cobbleAtMine < 12 && breadAtHearth < threshold) {
                cobbleMovedBeforeBread[0] = true;
            }
            helper.assertTrue(breadAtHearth + breadAtWarehouse + breadInBag == 8,
                "bread must be conserved, saw "
                    + (breadAtHearth + breadAtWarehouse + breadInBag)
                    + " [hearth=" + breadAtHearth + " warehouse=" + breadAtWarehouse
                    + " bag=" + breadInBag + "]");
            helper.assertTrue(cobbleAtMine + cobbleAtWarehouse + cobbleInBag == 12,
                "cobblestone must be conserved, saw "
                    + (cobbleAtMine + cobbleAtWarehouse + cobbleInBag)
                    + " [mine=" + cobbleAtMine + " warehouse=" + cobbleAtWarehouse
                    + " bag=" + cobbleInBag + "]");
            helper.assertTrue(!cobbleMovedBeforeBread[0],
                "cobblestone left the mine while the larder was still below its LOW "
                    + "mark of " + threshold + " -- collection ran ahead of food "
                    + "[breadAtHearth=" + breadAtHearth + "]");
            helper.assertTrue(breadAtHearth >= threshold,
                "the larder should be fed to its LOW mark of " + threshold + ", saw "
                    + breadAtHearth + " [act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(cobbleAtWarehouse == 12,
                "the ladder must continue below the food tier: all 12 cobblestone "
                    + "should still be collected afterwards, saw " + cobbleAtWarehouse
                    + " [mine=" + cobbleAtMine + " bag=" + cobbleInBag
                    + " act=" + bud.getActivity()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
        });
    }

    // ------------------------------------------------------------- the fuel ---

    /**
     * FUEL through the restock tier: a smelter with a bone-dry fuel chest
     * and sixteen charcoal on a warehouse shelf must have charcoal hauled
     * to it -- the contract work against {@code com.hearthstead.building
     * .Fuel} (burns / perBatch / isFuel), whose values belong to that
     * class, not to this test. Assertions are therefore gated on MOVEMENT
     * only: some charcoal reaches the smelter, every charcoal stays
     * accounted for at every poll, and the trip runs under the shared
     * (building, item) ledger key like any other restock. How MUCH moves
     * is {@code FUEL_RESERVE_BATCHES x Fuel.perBatch(SMELTER)} -- pinned
     * where perBatch is, once Fuel's own numbers are testable. Assumes the
     * Fuel contract marks SMELTER as a burner (FLOWS.md gives the smelter
     * the charcoal recipe for exactly this loop).
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "day")
    public void fuelReachesTheColdSmelter(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.CHARCOAL, 16));

        Building smelterB = addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(WORKSHOP_CHEST_REL, Blocks.CHEST);

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] sawHeld = {false};

        helper.succeedWhen(() -> {
            if (CourierWorkGoal.restockJobIsHeld(smelterB.id, Items.CHARCOAL)) {
                sawHeld[0] = true;
            }
            int atSmelter = countIn(containerAt(helper, WORKSHOP_CHEST_REL), Items.CHARCOAL);
            int atWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.CHARCOAL);
            int inBag = bagCountOf(bud, Items.CHARCOAL);
            int total = atSmelter + atWarehouse + inBag;
            helper.assertTrue(total == 16,
                "charcoal must be conserved across the fuel route, saw " + total
                    + " [smelter=" + atSmelter + " warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atSmelter > 0,
                "charcoal should reach the cold smelter's chest, saw " + atSmelter
                    + " [warehouse=" + atWarehouse + " bag=" + inBag
                    + " act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(sawHeld[0],
                "the fuel trip never claimed its (smelter, charcoal) key -- fuel "
                    + "restock is not running under the shared ledger");
        });
    }
}
