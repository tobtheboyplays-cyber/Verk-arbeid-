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
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.work.ContainerApproach;
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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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

    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_workshop_route_day")
    public void idleCourierTidiesRegisteredHomeButKeepsSuppliesAndPrivateChest(GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4,1,2), new BlockPos(6,3,4), new BlockPos(4,1,2));
        helper.setBlock(new BlockPos(5,1,3), Blocks.CHEST);
        addBuilding(helper, s, BuildingType.HOUSE,
            new BlockPos(2,1,5), new BlockPos(4,3,7), new BlockPos(2,1,5));
        BlockPos home = new BlockPos(3,1,6);
        helper.setBlock(home, Blocks.CHEST);
        Container source = containerAt(helper, home);
        source.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        source.setItem(1, new ItemStack(Items.BREAD, 6));
        source.setItem(2, new ItemStack(Items.WHEAT_SEEDS, 8));
        source.setItem(3, new ItemStack(Items.STONE_AXE));
        source.setItem(4, new ItemStack(Items.ARROW, 12));
        source.setItem(5, new ItemStack(Items.COAL, 5));
        BlockPos privatePos = new BlockPos(10,1,10);
        helper.setBlock(privatePos, Blocks.CHEST);
        Container privateChest = containerAt(helper, privatePos);
        privateChest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        SettlerEntity worker = courier(helper, s, new BlockPos(7,1,7));
        helper.onEachTick(() -> {
            helper.assertTrue(countIn(source, Items.BREAD)==6
                && countIn(source, Items.WHEAT_SEEDS)==8
                && countIn(source, Items.STONE_AXE)==1
                && countIn(source, Items.ARROW)==12
                && countIn(source, Items.COAL)==5,
                "cleanup must preserve operating and household supplies");
            helper.assertTrue(countIn(privateChest, Items.DIAMOND)==3,
                "unregistered private chest must never be scanned for cleanup");
            int total = countIn(source, Items.COBBLESTONE)
                + countIn(containerAt(helper,new BlockPos(5,1,3)), Items.COBBLESTONE)
                + bagCountOf(worker, Items.COBBLESTONE);
            helper.assertTrue(total==4, "cleanup must conserve real source/bag/destination stock");
        });
        helper.succeedWhen(() -> helper.assertTrue(
            countIn(containerAt(helper,new BlockPos(5,1,3)), Items.COBBLESTONE)==4,
            "idle Courier must physically deliver all misplaced Home building materials"));
    }

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
        BoundingBox bounds = BoundingBox.fromCorners(
            helper.absolutePos(minRel), helper.absolutePos(maxRel));
        return GameTestFixtures.registerWithBounds(helper, s, type, anchorRel,
            anchorRel, bounds);
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
        Building warehouse = s.buildings.stream()
            .filter(b -> b.valid && b.type == BuildingType.WAREHOUSE)
            .findFirst().orElse(null);
        helper.assertTrue(warehouse != null
                && Employment.hire(helper.getLevel(), s, warehouse, settler).ok(),
            "courier route fixture needs one real Warehouse employment authority");
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
            RequestLedgerService.Route route = RequestLedgerService
                .routeForCourier(helper.getLevel(), s, bud);
            boolean held = route.request() != null && route.source() != null
                && route.source().id.equals(mineB.id)
                && bud.getUUID().equals(route.request().courierId());
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

    /**
     * The shared container-contact contract must hold across the complete
     * request-owned collection route, not merely at a helper unit boundary.
     * The mine's exact output request is first lifted from a real source
     * chest; its warehouse target is a corner chest inside a closed room.
     * A courier must not post into that target through its wall, but must be
     * able to open the only oak door, enter, and commit there exactly once.
     */
    @GameTest(template = "empty16", timeoutTicks = 1200,
        batch = "courier_container_contact")
    public void collectionRequestCrossesClosedDoorBeforeCornerChestMutation(
            GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        s.radius = 20;

        Building mine = addBuilding(helper, s, BuildingType.MINE,
            new BlockPos(3, 1, 8), new BlockPos(5, 3, 10),
            new BlockPos(3, 1, 8));
        BlockPos sourceRel = new BlockPos(4, 1, 9);
        helper.setBlock(sourceRel, Blocks.CHEST);
        Container source = containerAt(helper, sourceRel);
        helper.assertTrue(source != null, "setup: mine source chest should exist");
        int seeded = 4;
        source.setItem(0, new ItemStack(Items.COBBLESTONE, seeded));

        // A 4x4 closed warehouse room. The west door is the only route to
        // the far corner chest; nothing outside can honestly contact it.
        for (int x = 10; x <= 13; x++) {
            for (int z = 10; z <= 13; z++) {
                if (x != 10 && x != 13 && z != 10 && z != 13) {
                    continue;
                }
                for (int y = 1; y <= 2; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
                }
            }
        }
        BlockState lowerDoor = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.WEST)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        BlockPos doorRel = new BlockPos(10, 1, 12);
        helper.setBlock(doorRel, lowerDoor);
        helper.setBlock(doorRel.above(), lowerDoor
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(10, 0, 10), new BlockPos(13, 3, 13),
            new BlockPos(10, 2, 10));
        BlockPos targetRel = new BlockPos(12, 1, 12);
        helper.setBlock(targetRel, Blocks.CHEST);
        Container target = containerAt(helper, targetRel);
        helper.assertTrue(target != null, "setup: corner warehouse chest should exist");

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 9));
        BlockPos sourcePos = helper.absolutePos(sourceRel);
        BlockPos targetPos = helper.absolutePos(targetRel);
        BlockPos doorPos = helper.absolutePos(doorRel);
        final boolean[] sawOwnedRequest = {false};
        final boolean[] sawDoorOpen = {false};
        final boolean[] sawPickup = {false};
        final boolean[] pickupAtContact = {false};
        final boolean[] sawDeposit = {false};
        final boolean[] depositAtContact = {false};
        final int[] sourcePrevious = {seeded};
        final int[] targetPrevious = {0};

        helper.onEachTick(() -> {
            RequestLedgerService.Route route = RequestLedgerService
                .routeForCourier(helper.getLevel(), s, bud);
            if (route.request() != null && route.source() != null
                && route.source().id.equals(mine.id)
                && bud.getUUID().equals(route.request().courierId())) {
                sawOwnedRequest[0] = true;
            }
            BlockState doorState = helper.getLevel().getBlockState(doorPos);
            if (doorState.is(Blocks.OAK_DOOR)
                && doorState.getValue(DoorBlock.OPEN)) {
                sawDoorOpen[0] = true;
            }

            int atSource = countIn(source, Items.COBBLESTONE);
            int atTarget = countIn(target, Items.COBBLESTONE);
            int inBag = bagCountOf(bud, Items.COBBLESTONE);
            helper.assertTrue(atSource + atTarget + inBag == seeded,
                "request cargo must be conserved on every tick, saw "
                    + (atSource + atTarget + inBag) + " of " + seeded
                    + " [source=" + atSource + " target=" + atTarget
                    + " bag=" + inBag + " request=" + sawOwnedRequest[0]
                    + " doorOpen=" + sawDoorOpen[0]
                    + " pos=" + bud.blockPosition().toShortString() + "]");
            if (!sawPickup[0] && atSource < sourcePrevious[0]) {
                sawPickup[0] = true;
                pickupAtContact[0] = ContainerApproach.inspect(
                    helper.getLevel(), bud, sourcePos).state()
                    == ContainerApproach.State.CONTACT;
            }
            if (!sawDeposit[0] && atTarget > targetPrevious[0]) {
                sawDeposit[0] = true;
                depositAtContact[0] = ContainerApproach.inspect(
                    helper.getLevel(), bud, targetPos).state()
                    == ContainerApproach.State.CONTACT;
            }
            helper.assertTrue(sawDoorOpen[0] || atTarget == 0,
                "the closed/occluded warehouse must not receive a request item "
                    + "before its only oak door is observed open [target=" + atTarget
                    + " source=" + atSource + " bag=" + inBag
                    + " pos=" + bud.blockPosition().toShortString() + "]");
            sourcePrevious[0] = atSource;
            targetPrevious[0] = atTarget;
        });

        helper.succeedWhen(() -> {
            int atSource = countIn(source, Items.COBBLESTONE);
            int atTarget = countIn(target, Items.COBBLESTONE);
            int inBag = bagCountOf(bud, Items.COBBLESTONE);
            helper.assertTrue(sawOwnedRequest[0],
                "fixture must exercise a real request owned by the courier "
                    + "[source=" + atSource + " target=" + atTarget
                    + " bag=" + inBag + " activity=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
            helper.assertTrue(sawDoorOpen[0],
                "the courier must visibly open the only oak warehouse door before "
                    + "reaching the corner chest [source=" + atSource
                    + " target=" + atTarget + " bag=" + inBag
                    + " pos=" + bud.blockPosition().toShortString() + "]");
            helper.assertTrue(sawPickup[0] && pickupAtContact[0],
                "the first source inventory mutation must occur at "
                    + "ContainerApproach.CONTACT [sawPickup=" + sawPickup[0]
                    + " contact=" + pickupAtContact[0] + " source=" + atSource
                    + " bag=" + inBag + " pos="
                    + bud.blockPosition().toShortString() + "]");
            helper.assertTrue(sawDeposit[0] && depositAtContact[0],
                "the first corner-chest inventory mutation must occur at "
                    + "ContainerApproach.CONTACT [sawDeposit=" + sawDeposit[0]
                    + " contact=" + depositAtContact[0] + " target=" + atTarget
                    + " bag=" + inBag + " pos="
                    + bud.blockPosition().toShortString() + "]");
            helper.assertTrue(atSource == 0 && atTarget == seeded && inBag == 0,
                "the closed-door request route must finish with exactly one conserved "
                    + "transfer [source=" + atSource + " target=" + atTarget
                    + " bag=" + inBag + " total=" + (atSource + atTarget + inBag)
                    + " activity=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
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
        // Fishery storage and the Courier's authored start both sit beyond
        // the legacy radius-six opening; make them genuine settlement space.
        s.radius = 12;

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
                    + " hearth=" + atHearth + " eaterAct=" + eater.getActivity()
                    + " courier=" + bud.getUUID() + " bag=" + inBudBag
                    + " pos=" + bud.blockPosition() + " act=" + bud.getActivity()
                    + " phase=" + bud.dayPhase() + " route=" + bud.routeFailureNote()
                    + " task=" + bud.workerLifecycle().task()
                    + " lifecycle=" + bud.workerLifecycle().state()
                    + " transfer=" + bud.bagTransferPresentation().clock()
                    + ":" + bud.bagTransferPresentation().committed()
                    + " navDone=" + bud.getNavigation().isDone()
                    + " navTarget=" + bud.getNavigation().getTargetPos()
                    + " routeDiag=" + GameTestFixtures.courierRouteWitness(bud) + "]");
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

    // ------------------------------------------------ dissolved mid-route ---

    /**
     * The brief's own hunt list, collection's half: the destination
     * warehouse dissolves while a load it was promised is already in the
     * courier's sack. Removed from {@code s.buildings} the same way
     * {@code BuildingManager}'s real sweep does it
     * ({@code settlement.buildings.remove(building)}), timed off the
     * courier's own state (real pickup already happened) rather than a
     * guessed tick or the sweep's shared cross-test cursor.
     *
     * <p>With the only warehouse gone, {@link CourierWorkGoal}'s own
     * contract for an OUTPUT_COLLECTION trip is explicit:
     * {@code canUseCarrying} re-resolves the destination, finds no warehouse
     * anywhere (not even a fallback), and sends the load back to
     * {@code sourcePos} -- the mine chest it was lifted from -- never to the
     * hearth, which the class doc reserves for "goods awaiting their first
     * haul". The cobblestone must therefore end up back in the mine, not
     * stranded in the bag and not duplicated anywhere.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_workshop_route_day")
    public void collectedLoadReturnsToTheMineWhenTheWarehouseDissolvesMidTrip(
            GameTestHelper helper) {
        Settlement s = standardOpening(helper);

        Building warehouse = addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(4, 1, 2), new BlockPos(6, 3, 4), new BlockPos(4, 1, 2));
        BlockPos warehouseChestRel = new BlockPos(5, 1, 3);
        helper.setBlock(warehouseChestRel, Blocks.CHEST);

        addBuilding(helper, s, BuildingType.MINE,
            new BlockPos(2, 1, 5), new BlockPos(4, 3, 7), new BlockPos(2, 1, 5));
        BlockPos mineChestRel = new BlockPos(3, 1, 6);
        helper.setBlock(mineChestRel, Blocks.CHEST);
        Container mineChest = containerAt(helper, mineChestRel);
        helper.assertTrue(mineChest != null, "arena mine chest should exist");
        int seeded = 12;
        mineChest.setItem(0, new ItemStack(Items.COBBLESTONE, seeded));

        SettlerEntity bud = courier(helper, s, new BlockPos(7, 1, 7));
        final boolean[] dissolved = {false};

        helper.succeedWhen(() -> {
            if (!dissolved[0]) {
                if (bagCountOf(bud, Items.COBBLESTONE) <= 0) {
                    return; // wait for the AI's own real pickup
                }
                helper.assertTrue(s.buildings.remove(warehouse),
                    "fixture: the warehouse must still be registered to remove it");
                dissolved[0] = true;
                return; // let the goal react on its own next tick
            }
            int atMine = countIn(containerAt(helper, mineChestRel), Items.COBBLESTONE);
            int atWarehouse = countIn(containerAt(helper, warehouseChestRel),
                Items.COBBLESTONE);
            int inBag = bagCountOf(bud, Items.COBBLESTONE);
            int total = atMine + atWarehouse + inBag;
            helper.assertTrue(total == seeded,
                "cobblestone must be conserved when the destination warehouse "
                    + "dissolves mid-trip, saw " + total + " [mine=" + atMine
                    + " warehouse=" + atWarehouse + " bag=" + inBag
                    + " act=" + bud.getActivity() + "]");
            helper.assertTrue(atMine == seeded,
                "with every warehouse gone, the collected load must come home to "
                    + "the mine it was lifted from rather than strand in the sack, "
                    + "saw " + atMine + " of " + seeded + " [warehouse=" + atWarehouse
                    + " bag=" + inBag + " act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote() + "]");
        });
    }
    /** Both workplaces are closed and raised; the courier starts inside its
     * warehouse, collects at the mine, then physically returns to that same
     * warehouse. A reduced vanilla visit budget deterministically pressures
     * partial-path handling without changing collision or contact authority. */
    @GameTest(template = "empty16", timeoutTicks = 1800,
        batch = "courier_container_contact")
    public void collectionCrossesTwoRaisedOffsetDoorsWithPartialPaths(
            GameTestHelper helper) {
        Settlement s = standardOpening(helper);
        s.radius = 20;
        BlockPos sourceDoor = new BlockPos(3, 2, 7);
        BlockPos targetDoor = new BlockPos(12, 2, 7);
        raisedClosedRoom(helper, 2, 6, sourceDoor);
        raisedClosedRoom(helper, 9, 13, targetDoor);
        Building mine = addBuilding(helper, s, BuildingType.MINE,
            new BlockPos(2, 1, 7), new BlockPos(6, 5, 12),
            new BlockPos(2, 2, 7));
        addBuilding(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(9, 1, 7), new BlockPos(13, 5, 12),
            new BlockPos(9, 2, 7));
        BlockPos sourceRel = new BlockPos(3, 2, 10);
        BlockPos targetRel = new BlockPos(10, 2, 10);
        helper.setBlock(sourceRel, Blocks.CHEST);
        helper.setBlock(targetRel, Blocks.CHEST);
        Container source = containerAt(helper, sourceRel);
        Container target = containerAt(helper, targetRel);
        helper.assertTrue(source != null && target != null,
            "setup: both closed workplaces need physical chests");
        int seeded = 4;
        source.setItem(0, new ItemStack(Items.COBBLESTONE, seeded));
        SettlerEntity bud = courier(helper, s, new BlockPos(11, 2, 10));
        // Limit only this fixture's search to 25 visited nodes so a short
        // complete route cannot bypass the incomplete-path recovery check.
        // The assertions still require an installed partial prefix and delivery.
        bud.getNavigation().setMaxVisitedNodesMultiplier(0.05F);
        BlockPos sourcePos = helper.absolutePos(sourceRel);
        BlockPos targetPos = helper.absolutePos(targetRel);
        boolean[] sourceOpened = {false};
        boolean[] targetOpened = {false};
        boolean[] ownedRequest = {false};
        boolean[] partialTravel = {false};
        boolean[] pickedUp = {false};
        boolean[] returned = {false};
        int[] previousSource = {seeded};
        int[] previousTarget = {0};
        helper.onEachTick(() -> {
            sourceOpened[0] |= helper.getBlockState(sourceDoor).getValue(DoorBlock.OPEN);
            targetOpened[0] |= helper.getBlockState(targetDoor).getValue(DoorBlock.OPEN);
            var route = RequestLedgerService.routeForCourier(helper.getLevel(), s, bud);
            if (route.request() != null && route.source() != null
                && mine.id.equals(route.source().id)
                && bud.getUUID().equals(route.request().courierId())) {
                ownedRequest[0] = true;
            }
            var path = bud.getNavigation().getPath();
            partialTravel[0] |= path != null && !path.canReach()
                && !path.isDone() && bud.getActivity() !=
                    com.hearthstead.entity.SettlerActivity.IDLE;
            int atSource = countIn(source, Items.COBBLESTONE);
            int atTarget = countIn(target, Items.COBBLESTONE);
            int inBag = bagCountOf(bud, Items.COBBLESTONE);
            helper.assertTrue(atSource + atTarget + inBag == seeded,
                "every tick must conserve the exact four request items");
            if (atSource < previousSource[0]) {
                helper.assertTrue(ownedRequest[0] && sourceOpened[0] && targetOpened[0],
                    "pickup must follow owned outbound travel through both doors");
                helper.assertTrue(ContainerApproach.inspect(helper.getLevel(),
                        bud, sourcePos).canInteract(),
                    "source mutation must occur at real contact, never through a wall");
                pickedUp[0] = true;
            }
            if (atTarget > previousTarget[0]) {
                helper.assertTrue(pickedUp[0]
                    && ContainerApproach.inspect(helper.getLevel(), bud, targetPos)
                        .canInteract(),
                    "return deposit must occur inside Warehouse at physical contact");
                returned[0] = true;
            }
            previousSource[0] = atSource;
            previousTarget[0] = atTarget;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(partialTravel[0],
                "fixture must actually exercise an installed incomplete work path"
                    + " [source=" + countIn(source, Items.COBBLESTONE)
                    + " target=" + countIn(target, Items.COBBLESTONE)
                    + " bag=" + bagCountOf(bud, Items.COBBLESTONE)
                    + " sourceDoor=" + sourceOpened[0] + " targetDoor=" + targetOpened[0]
                    + " owned=" + ownedRequest[0] + " picked=" + pickedUp[0] + " returned=" + returned[0]
                    + " pos=" + bud.position() + " activity=" + bud.getActivity()
                    + " path=" + (bud.getNavigation().getPath() == null ? "none"
                        : "reach=" + bud.getNavigation().getPath().canReach()
                            + ",done=" + bud.getNavigation().getPath().isDone())
                    + " route=" + bud.routeFailureNote() + "]");
            helper.assertTrue(pickedUp[0] && returned[0]
                    && countIn(source, Items.COBBLESTONE) == 0
                    && countIn(target, Items.COBBLESTONE) == seeded
                    && bagCountOf(bud, Items.COBBLESTONE) == 0,
                "courier must leave Warehouse, collect inside Mine and return physically"
                    + " [pos=" + bud.blockPosition().toShortString()
                    + " activity=" + bud.getActivity()
                    + " route=" + bud.routeFailureNote() + "]");
        });
    }

    private static void raisedClosedRoom(GameTestHelper helper, int minX,
                                          int maxX, BlockPos door) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = 7; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE_BRICKS);
                for (int y = 2; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        x == minX || x == maxX || z == 7 || z == 12
                            ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
            }
        }
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.NORTH)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        helper.setBlock(door, lower);
        helper.setBlock(door.above(), lower
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }



    /** Actual goal contact ownership across source changes and a clean actor save/load. */
    @GameTest(template = "empty16", timeoutTicks = 800, batch = "courier_workshop_route_day")
    public void hearthConsolidationFillsOneGroundedSackAcrossReload(GameTestHelper helper) {
        Settlement settlement = standardOpening(helper);
        Building warehouse = addBuilding(helper, settlement, BuildingType.WAREHOUSE,
            new BlockPos(5, 1, 2), new BlockPos(7, 3, 4), new BlockPos(5, 1, 2));
        helper.setBlock(new BlockPos(6, 1, 3), Blocks.CHEST);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(settlement.center);
        ItemStack original = new ItemStack(Items.OAK_LOG, 4);
        original.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("Actual Hearth timber"));
        hearth.getInventory().setStackInSlot(0, original.copy());
        hearth.getInventory().setStackInSlot(1, new ItemStack(Items.BREAD, 8));
        SettlerEntity first = courier(helper, settlement, new BlockPos(2, 1, 3));
        first.setNoAi(true);
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 1, 3));
        first.moveTo(anchor.getX() + .5, anchor.getY(), anchor.getZ() + .5, 0, 0);
        SettlerEntity[] actor = {first};
        CourierWorkGoal[] goal = {new CourierWorkGoal(first)};
        helper.assertTrue(goal[0].canUse(), "real employed Courier must select actual Hearth timber");
        goal[0].start();
        int[] prior = {0}, contacts = {0};
        boolean[] changed = {false}, restored = {false}, reloaded = {false}, pendingRestore = {false};
        helper.onEachTick(() -> {
            if (pendingRestore[0]) {
                hearth.getInventory().setStackInSlot(0, original.copyWithCount(4 - prior[0]));
                pendingRestore[0] = false; restored[0] = true;
                helper.assertTrue(goal[0].canUse(), "source change must allow a lawful fresh anticipation");
                goal[0].start();
            }
            var before = actor[0].bagTransferPresentation();
            if (!changed[0] && before.active() && before.clock() == 47) {
                ItemStack replacement = original.copyWithCount(4);
                replacement.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("Changed source"));
                hearth.getInventory().setStackInSlot(0, replacement);
                changed[0] = true; pendingRestore[0] = true;
            }
            goal[0].tick();
            int count = bagCountOf(actor[0], Items.OAK_LOG);
            helper.assertTrue(count >= prior[0] && count - prior[0] <= 1,
                "one actual source unit at most; never bulk-debit or replay");
            int sourceCount = hearth.getInventory().getStackInSlot(0).getCount();
            helper.assertTrue(sourceCount + count == 4 && hearth.getInventory().getStackInSlot(1).getCount() == 8,
                "all real timber conserved and ready food untouched");
            var view = actor[0].bagTransferPresentation();
            if (count > prior[0]) {
                helper.assertTrue(view.active() && view.sourcePickup() && view.committed() && view.clock() == 48
                        && anchor.equals(view.bagAnchor()), "each debit belongs to same grounded source sack at contact48");
                for (int slot = 0; slot < actor[0].bag.getContainerSize(); slot++) {
                    ItemStack held = actor[0].bag.getItem(slot);
                    if (!held.isEmpty()) helper.assertTrue(ItemStack.isSameItemSameComponents(original, held),
                        "changed source components must never enter the bag on an obsolete preview");
                }
                contacts[0]++;
            }
            if (pendingRestore[0]) helper.assertTrue(count == prior[0], "changed source must cancel this contact before debit");
            prior[0] = count;
            if (count == 2 && !reloaded[0]) {
                reloaded[0] = true;
                goal[0].stop();
                net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
                actor[0].saveWithoutId(saved);
                actor[0].remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
                SettlerEntity loaded = ModEntities.SETTLER.get().create(helper.getLevel());
                helper.assertTrue(loaded != null, "decode actual saved Courier");
                loaded.load(saved);
                helper.assertTrue(helper.getLevel().addFreshEntity(loaded), "republish same UUID after actual unload");
                actor[0] = loaded; goal[0] = new CourierWorkGoal(loaded);
                helper.assertTrue(goal[0].canUse(), "saved two-unit sack must resume before anonymous carrying");
                goal[0].start();
            }
            if (contacts[0] == 4 && !actor[0].getPersistentData().contains("HearthsteadCourierHearthBag")) {
                helper.assertTrue(changed[0] && restored[0] && reloaded[0]
                        && !actor[0].bagTransferPresentation().active() && actor[0].placedWorkContainerPos() == null,
                    "final lift follows all four receipts and clears only the grounded presentation");
                goal[0].stop(); actor[0].discard();
                SettlementManager.data(helper.getLevel()).settlements.remove(settlement.id);
                SettlementManager.data(helper.getLevel()).setDirty();
                helper.succeed();
            }
        });
    }
}
