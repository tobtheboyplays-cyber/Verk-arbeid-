package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Fuel;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.CourierWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.RecruitmentPolicy;
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
 * <p>Six food claims and four fuel claims: a starving hearth is fed,
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
    private static final BlockPos SECOND_WAREHOUSE_CHEST_REL = new BlockPos(9, 1, 3);
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

    private static int fuelUnitsIn(Container container) {
        if (container == null) {
            return 0;
        }
        int units = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            units += Fuel.units(container.getItem(slot));
        }
        return units;
    }

    private static int fuelUnitsInBag(SettlerEntity settler) {
        return fuelUnitsIn(settler.bag);
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
        Building warehouse = s.buildings.stream()
            .filter(b -> b.valid && b.type == BuildingType.WAREHOUSE
                && b.workers.size() < b.type.workerCapacity())
            .findFirst().orElse(null);
        helper.assertTrue(warehouse != null
                && Employment.hire(helper.getLevel(), s, warehouse, settler).ok(),
            "courier route fixture needs one real Warehouse employment authority");
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
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_food_route_day")
    public void breadReachesTheStarvingHearth(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        hearthAt(helper).insertGoods(new ItemStack(Items.BREAD, 1));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 32));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        // Computed AFTER the courier's record exists: 1 living settler.
        int threshold = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.stageFor(s)).courierReadyFoodTarget();
        helper.assertTrue(threshold == 20,
            "one resident plus the next recruit and bread price should target 20, got "
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
            helper.assertTrue(total == 33,
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
    @GameTest(template = "empty16", timeoutTicks = 2000, batch = "courier_food_route_day")
    public void stockedLarderTriggersNoDelivery(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        int seeded = 20;
        hearthAt(helper).insertGoods(new ItemStack(Items.BREAD, seeded));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 16));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        int threshold = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.stageFor(s)).courierReadyFoodTarget();
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

    /**
     * A scalar meal target cannot prove that an exact price is payable:
     * twenty baked potatoes already satisfy the one-resident courier target,
     * but recruiting still names four loaves. The courier must claim bread
     * anyway, carry exactly the four-loaf shortfall, conserve every loaf and
     * stop once the exact price line is covered. Old generic-only routing
     * never claimed this job because the hearth already held twenty meals.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400,
        batch = "courier_food_route_day")
    public void exactRecruitmentBreadOutranksSatisfiedMealTotal(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        HearthBlockEntity hearth = hearthAt(helper);
        hearth.insertGoods(new ItemStack(Items.BAKED_POTATO, 20));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 8));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        RecruitmentPolicy.Assessment opening = RecruitmentPolicy.assess(
            helper.getLevel(), s, RecruitmentPolicy.stageFor(s));
        helper.assertTrue(opening.courierReadyFoodTarget() == 20,
            "fixture target should be 20 meals, got "
                + opening.courierReadyFoodTarget());
        helper.assertTrue(hearth.countFoodUnits() == 20,
            "potatoes should already satisfy the scalar meal target");
        var openingNeed = RecruitmentPolicy.missingReadyFoodPrices(
                hearth.getInventory(), opening.price()).stream()
            .filter(need -> need.matches(new ItemStack(Items.BREAD)))
            .findFirst();
        helper.assertTrue(openingNeed.isPresent()
                && openingNeed.get().missing() == 4,
            "fixture should expose an exact four-bread price deficit");
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
            int atWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.BREAD);
            int inBag = bagCountOf(bud, Items.BREAD);
            helper.assertTrue(atHearth + atWarehouse + inBag == 8,
                "bread must be conserved across exact-price delivery, saw "
                    + (atHearth + atWarehouse + inBag));
            helper.assertTrue(countInHearth(hearthAt(helper), Items.BAKED_POTATO) == 20,
                "price routing must not consume or relocate the twenty reserve meals");
            helper.assertTrue(atHearth == 4 && atWarehouse == 4 && inBag == 0,
                "courier should deliver exactly the four missing loaves, saw "
                    + "[hearth=" + atHearth + " warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity() + "]");
            boolean breadStillNeeded = RecruitmentPolicy.missingReadyFoodPrices(
                    hearthAt(helper).getInventory(),
                    RecruitmentPolicy.assess(helper.getLevel(), s,
                        RecruitmentPolicy.stageFor(s)).price()).stream()
                .anyMatch(need -> need.matches(new ItemStack(Items.BREAD)));
            helper.assertTrue(!breadStillNeeded,
                "the exact edible recruitment-price deficit should now be closed");
            helper.assertTrue(sawHeld[0] && sawReleasedAfterHold[0],
                "exact-price food job should claim and release its reservation");
        });
    }

    /**
     * The price-specific route consumes the live discounted policy price,
     * not the four-loaf base table. A staffed tavern lowers bread to three;
     * nineteen wrong meals satisfy the scalar target, yet exactly three
     * loaves still move and the fourth remains on the warehouse shelf.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400,
        batch = "courier_food_route_day")
    public void courierHonoursLiveInnkeeperBreadDiscount(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        Building tavern = addBuilding(helper, s, BuildingType.TAVERN,
            new BlockPos(8, 1, 5), new BlockPos(10, 3, 7), new BlockPos(8, 1, 5));
        tavern.workers.add(UUID.randomUUID());
        HearthBlockEntity hearth = hearthAt(helper);
        hearth.insertGoods(new ItemStack(Items.BAKED_POTATO, 19));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 6));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 8));
        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));

        RecruitmentPolicy.Assessment opening = RecruitmentPolicy.assess(
            helper.getLevel(), s, RecruitmentPolicy.stageFor(s));
        helper.assertTrue(opening.price().lines().get(0).count() == 3
                && opening.courierReadyFoodTarget() == 19
                && hearth.countFoodUnits() == 19,
            "staffed tavern fixture must expose a three-bread discounted target");

        helper.succeedWhen(() -> {
            int atHearth = countInHearth(hearthAt(helper), Items.BREAD);
            int atWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.BREAD);
            int inBag = bagCountOf(bud, Items.BREAD);
            helper.assertTrue(atHearth + atWarehouse + inBag == 8,
                "discounted bread route must conserve all eight loaves");
            helper.assertTrue(atHearth == 3 && atWarehouse == 5 && inBag == 0,
                "courier should deliver the discounted three loaves exactly, saw "
                    + "[hearth=" + atHearth + " warehouse=" + atWarehouse
                    + " bag=" + inBag + "]");
        });
    }

    /**
     * A food claim is intent, never permission to withdraw stale stock. Once
     * bread is reserved but before the courier reaches the warehouse, the
     * player fills the four-loaf price line directly at the hearth. The trip
     * must then take nothing, release its lease and leave all warehouse bread
     * untouched.
     */
    @GameTest(template = "empty16", timeoutTicks = 2600,
        batch = "courier_food_route_day")
    public void priceFilledAfterClaimCancelsFoodWithdrawal(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        HearthBlockEntity hearth = hearthAt(helper);
        hearth.insertGoods(new ItemStack(Items.BAKED_POTATO, 20));
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 8));
        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] filledAfterClaim = {false};
        final boolean[] sawHeld = {false};
        final boolean[] sawReleased = {false};

        helper.succeedWhen(() -> {
            boolean held = CourierWorkGoal.restockJobIsHeld(s.id, Items.BREAD);
            sawHeld[0] |= held;
            if (!filledAfterClaim[0] && held) {
                ItemStack left = hearth.insertGoods(new ItemStack(Items.BREAD, 4));
                helper.assertTrue(left.isEmpty(),
                    "hearth should accept the player's four-loaf intervention");
                filledAfterClaim[0] = true;
            }
            if (sawHeld[0] && !held) {
                sawReleased[0] = true;
            }
            int atHearth = countInHearth(hearthAt(helper), Items.BREAD);
            int atWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.BREAD);
            int inBag = bagCountOf(bud, Items.BREAD);
            helper.assertTrue(!filledAfterClaim[0]
                    || atHearth + atWarehouse + inBag == 12,
                "player fill plus warehouse bread must remain conserved");
            helper.assertTrue(filledAfterClaim[0] && atHearth == 4
                    && atWarehouse == 8 && inBag == 0,
                "stale food claim should withdraw nothing, saw [hearth="
                    + atHearth + " warehouse=" + atWarehouse + " bag="
                    + inBag + "]");
            helper.assertTrue(sawHeld[0] && sawReleased[0],
                "stale food job should claim once, then release without cargo");
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
    @GameTest(template = "empty16", timeoutTicks = 4800, batch = "courier_food_route_day")
    public void starvingHearthOutranksMineCollection(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addWarehouse(helper, s);
        Container source = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(source != null, "arena warehouse chest should exist");
        source.setItem(0, new ItemStack(Items.BREAD, 24));

        addBuilding(helper, s, BuildingType.MINE,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(WORKSHOP_CHEST_REL, Blocks.CHEST);
        Container mineChest = containerAt(helper, WORKSHOP_CHEST_REL);
        helper.assertTrue(mineChest != null, "arena mine chest should exist");
        mineChest.setItem(0, new ItemStack(Items.COBBLESTONE, 12));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        int threshold = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.stageFor(s)).courierReadyFoodTarget();
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
            helper.assertTrue(breadAtHearth + breadAtWarehouse + breadInBag == 24,
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
     * A broad "room for some fuel" check is not enough. This smelter chest
     * is full except for room in an oak-log stack; warehouse charcoal cannot
     * merge there. The courier must reject that exact load before claiming
     * it and continue down the priority ladder to feed the empty hearth.
     * The old predicate-only check looped charcoal warehouse -> bag -> full
     * smelter -> warehouse forever and starved this bread route.
     */
    @GameTest(template = "empty16", timeoutTicks = 3000,
        batch = "courier_food_route_day")
    public void incompatibleFuelStackCannotStarveFoodDelivery(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addWarehouse(helper, s);
        Container warehouse = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(warehouse != null, "arena warehouse chest should exist");
        warehouse.setItem(0, new ItemStack(Items.CHARCOAL, 8));
        warehouse.setItem(1, new ItemStack(Items.BREAD, 32));

        Building smelter = addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(WORKSHOP_CHEST_REL, Blocks.CHEST);
        Container firebox = containerAt(helper, WORKSHOP_CHEST_REL);
        helper.assertTrue(firebox != null, "arena smelter chest should exist");
        firebox.setItem(0, new ItemStack(Items.OAK_LOG, 7));
        for (int slot = 1; slot < firebox.getContainerSize(); slot++) {
            firebox.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        int foodTarget = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.stageFor(s)).courierReadyFoodTarget();
        final boolean[] fuelWasEverClaimed = {false};

        helper.succeedWhen(() -> {
            fuelWasEverClaimed[0] |= CourierWorkGoal.fuelJobIsHeld(smelter.id);
            int breadAtHearth = countInHearth(hearthAt(helper), Items.BREAD);
            int breadAtWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.BREAD);
            int breadInBag = bagCountOf(bud, Items.BREAD);
            helper.assertTrue(breadAtHearth + breadAtWarehouse + breadInBag == 32,
                "bread must stay conserved while bypassing incompatible fuel");
            helper.assertTrue(countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                    Items.CHARCOAL) == 8
                    && countIn(containerAt(helper, WORKSHOP_CHEST_REL),
                        Items.CHARCOAL) == 0,
                "charcoal that cannot fit must never leave its warehouse");
            helper.assertTrue(!fuelWasEverClaimed[0],
                "an exact fuel stack with no physical destination room was claimed");
            helper.assertTrue(breadAtHearth >= foodTarget && breadInBag == 0,
                "food delivery must continue below the rejected fuel job, saw "
                    + breadAtHearth + "/" + foodTarget + " at hearth [act="
                    + bud.getActivity() + "]");
        });
    }

    /**
     * Fuel is one demand lane, not one job per species. With charcoal in the
     * first warehouse and coal in the second, two couriers must not reserve
     * both against the same empty eight-unit firebox. Exactly one courier
     * carries fuel and the destination stops at the shared unit target.
     */
    @GameTest(template = "empty16", timeoutTicks = 3200,
        batch = "courier_food_route_day")
    public void mixedFuelTypesShareOneCourierReservation(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addWarehouse(helper, s);
        Container denseWarehouse = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(denseWarehouse != null, "first warehouse chest should exist");
        denseWarehouse.setItem(0, new ItemStack(Items.CHARCOAL, 4));
        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(8, 1, 2), new BlockPos(10, 3, 4), new BlockPos(8, 1, 2));
        helper.setBlock(SECOND_WAREHOUSE_CHEST_REL, Blocks.CHEST);
        Container coalWarehouse = containerAt(helper, SECOND_WAREHOUSE_CHEST_REL);
        helper.assertTrue(coalWarehouse != null, "second warehouse chest should exist");
        coalWarehouse.setItem(0, new ItemStack(Items.COAL, 4));

        Building smelter = addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(WORKSHOP_CHEST_REL, Blocks.CHEST);
        SettlerEntity first = courier(helper, s, new BlockPos(6, 1, 8));
        SettlerEntity second = courier(helper, s, new BlockPos(8, 1, 8));
        final boolean[] firstCarriedFuel = {false};
        final boolean[] secondCarriedFuel = {false};
        final boolean[] sawHeld = {false};
        final boolean[] sawReleased = {false};

        helper.succeedWhen(() -> {
            firstCarriedFuel[0] |= fuelUnitsInBag(first) > 0;
            secondCarriedFuel[0] |= fuelUnitsInBag(second) > 0;
            boolean held = CourierWorkGoal.fuelJobIsHeld(smelter.id);
            sawHeld[0] |= held;
            if (sawHeld[0] && !held) {
                sawReleased[0] = true;
            }
            int atFirebox = fuelUnitsIn(containerAt(helper, WORKSHOP_CHEST_REL));
            int inWarehouses = fuelUnitsIn(containerAt(helper, WAREHOUSE_CHEST_REL))
                + fuelUnitsIn(containerAt(helper, SECOND_WAREHOUSE_CHEST_REL));
            int inBags = fuelUnitsInBag(first) + fuelUnitsInBag(second);
            helper.assertTrue(atFirebox + inWarehouses + inBags == 16,
                "mixed fuel units must be conserved across both routes");
            helper.assertTrue(!(firstCarriedFuel[0] && secondCarriedFuel[0]),
                "two couriers carried different fuels for one shared deficit");
            helper.assertTrue(atFirebox == 8 && inBags == 0,
                "firebox should stop at exactly eight units, saw " + atFirebox);
            helper.assertTrue(sawHeld[0] && sawReleased[0],
                "shared fuel lane must be claimed once and released after delivery");
        });
    }

    /**
     * Delivery revalidates the firebox too. Once the courier has physically
     * lifted four charcoal, the player fills the target with four coal. The
     * stale load must turn around untouched and return to the warehouse;
     * otherwise a valid eight-unit reserve becomes sixteen.
     */
    @GameTest(template = "empty16", timeoutTicks = 3600,
        batch = "courier_food_route_day")
    public void fuelFilledInTransitReturnsTheStaleLoad(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addWarehouse(helper, s);
        Container warehouse = containerAt(helper, WAREHOUSE_CHEST_REL);
        helper.assertTrue(warehouse != null, "arena warehouse chest should exist");
        warehouse.setItem(0, new ItemStack(Items.CHARCOAL, 8));
        Building smelter = addBuilding(helper, s, BuildingType.SMELTER,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        helper.setBlock(WORKSHOP_CHEST_REL, Blocks.CHEST);
        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] filledInTransit = {false};
        final boolean[] sawHeld = {false};
        final boolean[] sawReleased = {false};

        helper.succeedWhen(() -> {
            boolean held = CourierWorkGoal.fuelJobIsHeld(smelter.id);
            sawHeld[0] |= held;
            if (!filledInTransit[0] && bagCountOf(bud, Items.CHARCOAL) == 4) {
                Container firebox = containerAt(helper, WORKSHOP_CHEST_REL);
                firebox.setItem(0, new ItemStack(Items.COAL, 4));
                filledInTransit[0] = true;
            }
            if (sawHeld[0] && !held) {
                sawReleased[0] = true;
            }
            int charcoalWarehouse = countIn(containerAt(helper, WAREHOUSE_CHEST_REL),
                Items.CHARCOAL);
            int charcoalFirebox = countIn(containerAt(helper, WORKSHOP_CHEST_REL),
                Items.CHARCOAL);
            int coalFirebox = countIn(containerAt(helper, WORKSHOP_CHEST_REL), Items.COAL);
            int charcoalBag = bagCountOf(bud, Items.CHARCOAL);
            helper.assertTrue(charcoalWarehouse + charcoalFirebox + charcoalBag == 8,
                "stale charcoal load must remain conserved");
            helper.assertTrue(!filledInTransit[0] || fuelUnitsIn(
                    containerAt(helper, WORKSHOP_CHEST_REL)) == 8,
                "delivery must never raise the player-filled firebox above eight units");
            helper.assertTrue(filledInTransit[0] && charcoalWarehouse == 8
                    && charcoalFirebox == 0 && charcoalBag == 0 && coalFirebox == 4,
                "stale load should return whole after the live deficit disappears");
            helper.assertTrue(sawHeld[0] && sawReleased[0],
                "stale fuel reservation should release before the return leg ends");
        });
    }

    /**
     * FUEL through the restock tier: a smelter with a bone-dry fuel chest
     * and sixteen charcoal on a warehouse shelf must have charcoal hauled
     * to it under {@link Fuel}'s unit contract. Four reserve batches times
     * two units, divided by charcoal's exact two units per item, means four
     * physical charcoal must move -- not eight. Every item stays accounted
     * for at every poll, and the trip runs under the shared (building, item)
     * ledger key like any other restock.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_food_route_day")
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
        int targetUnits = CourierWorkGoal.FUEL_RESERVE_BATCHES
            * Fuel.unitsPerBatch(BuildingType.SMELTER);
        int expectedCharcoal = targetUnits
            / Fuel.unitsPerItem(new ItemStack(Items.CHARCOAL));
        helper.assertTrue(targetUnits == 8 && expectedCharcoal == 4,
            "fixture should request exactly four dense charcoal items");
        final boolean[] sawHeld = {false};

        helper.succeedWhen(() -> {
            if (CourierWorkGoal.fuelJobIsHeld(smelterB.id)) {
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
            helper.assertTrue(atSmelter == expectedCharcoal
                    && atWarehouse == 16 - expectedCharcoal && inBag == 0,
                "courier should deliver exactly the cold smelter's unit deficit, saw "
                    + atSmelter
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
