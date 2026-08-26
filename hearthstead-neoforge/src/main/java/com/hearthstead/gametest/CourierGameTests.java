package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.logistics.Weight;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE A2a — the courier actually delivers, and never loses an item.
 *
 * <p>These are aimed squarely at MineColonies' shipped delivery failures
 * (see docs/project/REFERENCE_ANALYSIS.md): deliveries that silently never
 * happen, and items that vanish when a destination cannot take them.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class CourierGameTests {

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

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel,
                                             int radius) {
        var level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Courierholm",
            helper.absolutePos(centerRel));
        s.radius = radius;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    /** Registers a warehouse Building over a corner of the arena. */
    private static Building addWarehouse(GameTestHelper helper, Settlement s,
                                         BlockPos minRel, BlockPos maxRel) {
        return addWarehouse(helper, s, minRel, maxRel, minRel);
    }

    /**
     * Registers a warehouse with an explicit anchor. A real warehouse has no
     * beds, so its anchor is the plaque block itself -- in a wall.
     */
    private static Building addWarehouse(GameTestHelper helper, Settlement s,
                                         BlockPos minRel, BlockPos maxRel,
                                         BlockPos anchorRel) {
        // A plaque block MUST exist at the anchor. BuildingManager's sweep
        // dissolves any building whose plaquePos holds no plaque -- correctly,
        // since "no plaque, no building" is the permanent invariant (D-005).
        // A fixture that skips this registers a building the game then deletes
        // out from under the test, on a round-robin sweep shared with every
        // other concurrently running test: the root cause of KF-014.
        helper.setBlock(anchorRel, ModBlocks.PLAQUE.get());
        BoundingBox bounds = BoundingBox.fromCorners(
            helper.absolutePos(minRel), helper.absolutePos(maxRel));
        Building b = new Building(UUID.randomUUID(), BuildingType.WAREHOUSE,
            helper.absolutePos(anchorRel), helper.absolutePos(anchorRel), bounds);
        b.valid = true;
        s.buildings.add(b);
        return b;
    }

    /** A closed wooden door, both halves, as a player would have hung it. */
    private static void placeDoor(GameTestHelper helper, BlockPos lowerRel,
                                  Direction facing) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(lowerRel, lower);
        helper.setBlock(lowerRel.above(),
            lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countIn(Container c, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack stack = c.getItem(slot);
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

    /**
     * The whole point: goods left at the hearth end up in warehouse chests,
     * carried there by a settler, with the total item count unchanged.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "courier_day")
    public void courierHaulsGoodsFromHearthToWarehouse(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 6));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));

        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4));
        final boolean[] sawCarrying = {false};

        helper.succeedWhen(() -> {
            if (bud.getActivity() == SettlerActivity.CARRYING) {
                sawCarrying[0] = true;
            }
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            helper.assertTrue(chest != null, "warehouse chest should exist");
            int delivered = countIn(chest, Items.OAK_LOG);
            helper.assertTrue(delivered >= 6,
                "all 6 logs should reach the warehouse, saw " + delivered
                    + " (act=" + bud.getActivity() + ")");
            helper.assertTrue(sawCarrying[0],
                "the courier should visibly carry (CARRYING), not teleport goods");
        });
    }

    /**
     * Food is the settlement's life support and must never be hauled away
     * (D-A2a-1) -- draining the hearth would quietly starve everyone.
     */
    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "courier_day")
    public void courierNeverTakesFoodFromTheHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.BREAD, 8));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));
        courier(helper, s, new BlockPos(4, 1, 4));

        helper.runAtTickTime(600, () -> {
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            helper.assertTrue(chest != null, "warehouse chest should exist");
            helper.assertTrue(countIn(chest, Items.BREAD) == 0,
                "bread must never be hauled out of the hearth, found "
                    + countIn(chest, Items.BREAD) + " in the warehouse");
            BlockEntity be = helper.getLevel()
                .getBlockEntity(helper.absolutePos(hearthRel));
            helper.assertTrue(be instanceof HearthBlockEntity h
                    && h.countFoodUnits() > 0,
                "the hearth should still hold its food");
            helper.succeed();
        });
    }

    /**
     * With no warehouse the courier must idle quietly, not thrash between
     * states -- MineColonies' delivery loop failures (#5333/#3892) are
     * exactly this shape.
     */
    @GameTest(template = "empty16", timeoutTicks = 800, batch = "courier_day")
    public void courierIdlesWithoutAWarehouse(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 12);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 10);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 4));
        }
        SettlerEntity bud = courier(helper, s, new BlockPos(5, 1, 5));

        helper.runAtTickTime(400, () -> {
            helper.assertTrue(bud.getActivity() != SettlerActivity.CARRYING
                    && bud.getActivity() != SettlerActivity.SORTING,
                "with no warehouse the courier must not enter a haul state, got "
                    + bud.getActivity());
            int bagged = 0;
            for (int i = 0; i < bud.bag.getContainerSize(); i++) {
                bagged += bud.bag.getItem(i).getCount();
            }
            helper.assertTrue(bagged == 0,
                "the courier should not have picked anything up, bag=" + bagged);
            BlockEntity be = helper.getLevel()
                .getBlockEntity(helper.absolutePos(hearthRel));
            helper.assertTrue(be instanceof HearthBlockEntity h
                    && h.getInventory().getStackInSlot(0).getCount() == 4,
                "the logs should still be in the hearth, untouched");
            helper.succeed();
        });
    }

    /**
     * The live-world failure this slice actually shipped with: a warehouse
     * is a real sealed room, so its plaque -- and therefore its anchor -- is
     * a wall block with nothing standable beside it. Routing the courier to
     * the anchor left her outside the wall, one block short of the arrival
     * radius, giving up and re-triggering forever with the load in her bag.
     *
     * <p>Two things are asserted, and the second is the one the open-arena
     * tests could never catch: the goods arrive, AND the courier was inside
     * the room when they did. Reaching through a wall is not delivering.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_day")
    public void courierEntersASealedWarehouseAndDelivers(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 14);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 6));
        }

        // A closed 7x7 room: walls y1-3 on the ring, a ceiling, one door.
        for (int x = 6; x <= 12; x++) {
            for (int z = 6; z <= 12; z++) {
                boolean rim = x == 6 || z == 6 || x == 12 || z == 12;
                helper.setBlock(new BlockPos(x, 4, z), Blocks.OAK_PLANKS);
                if (rim) {
                    for (int y = 1; y <= 3; y++) {
                        helper.setBlock(new BlockPos(x, y, z), Blocks.OAK_PLANKS);
                    }
                }
            }
        }
        placeDoor(helper, new BlockPos(9, 1, 6), Direction.SOUTH);
        // The chest sits in the far corner, diagonally across from the door,
        // so nothing outside the room is ever within reach of it.
        BlockPos chestRel = new BlockPos(11, 1, 11);
        helper.setBlock(chestRel, Blocks.CHEST);
        // Anchor = the plaque beside the door, in the wall, exactly as
        // PlaqueBlockEntity assigns it for a building with no beds.
        addWarehouse(helper, s, new BlockPos(6, 0, 6), new BlockPos(12, 4, 12),
            new BlockPos(10, 2, 6));

        SettlerEntity bud = courier(helper, s, new BlockPos(3, 1, 3));
        BoundingBox interior = BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(7, 1, 7)),
            helper.absolutePos(new BlockPos(11, 3, 11)));
        final int[] seen = {0};
        final String[] postedFrom = {null};
        final boolean[] everInside = {false};

        helper.succeedWhen(() -> {
            Container chest = containerAt(helper, chestRel);
            helper.assertTrue(chest != null, "warehouse chest should exist");
            int delivered = countIn(chest, Items.OAK_LOG);
            if (interior.isInside(bud.blockPosition())) {
                everInside[0] = true;
            }
            // Where the courier stood at the moment the count rose is the
            // whole question. Checking "was she ever inside" is not enough:
            // she can post through the wall and wander in afterwards.
            if (delivered > seen[0]) {
                seen[0] = delivered;
                if (postedFrom[0] == null && !interior.isInside(bud.blockPosition())) {
                    postedFrom[0] = bud.blockPosition().toShortString();
                }
            }
            helper.assertTrue(postedFrom[0] == null,
                "the courier must walk into the warehouse, not post goods "
                    + "through the wall -- stowed from " + postedFrom[0]);
            // A bare "saw 0" says nothing about WHY. Everything needed to
            // tell "never got in" from "ran out of energy" from "never
            // started" goes in the message, because a timeout is the only
            // place this state is ever visible.
            helper.assertTrue(delivered >= 6,
                "all 6 logs should reach the sealed warehouse, saw " + delivered
                    + " [act=" + bud.getActivity()
                    + " pos=" + bud.blockPosition().toShortString()
                    + " energy=" + String.format("%.1f", bud.getEnergy())
                    + " bag=" + bagCount(bud)
                    + " everInside=" + everInside[0]
                    + " doorOpen=" + doorOpen(helper, new BlockPos(9, 1, 6))
                    + "]");
        });
    }

    /**
     * A load has to cost something in the world, not only in a number. A
     * full sack slows the carrier, the penalty scales with how full it is,
     * and it clears completely the moment the sack is emptied -- a settler
     * who stayed slow after delivering would be a permanent silent debuff.
     */
    @GameTest(template = "empty16", timeoutTicks = 400, batch = "courier_day")
    public void aFullSackSlowsTheCarrier(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 12);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 10);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        SettlerEntity empty = courier(helper, s, new BlockPos(5, 1, 5));
        SettlerEntity laden = courier(helper, s, new BlockPos(7, 1, 5));
        SettlerEntity half = courier(helper, s, new BlockPos(9, 1, 5));
        laden.bag.addItem(new ItemStack(Items.OAK_LOG, laden.getCarryCapacity()));
        half.bag.addItem(new ItemStack(Items.OAK_LOG,
            Math.max(1, half.getCarryCapacity() / 2)));

        final boolean[] checked = {false};
        helper.runAtTickTime(40, () -> {
            double emptySpeed = empty.getAttributeValue(Attributes.MOVEMENT_SPEED);
            double halfSpeed = half.getAttributeValue(Attributes.MOVEMENT_SPEED);
            double ladenSpeed = laden.getAttributeValue(Attributes.MOVEMENT_SPEED);
            helper.assertTrue(ladenSpeed < emptySpeed,
                "a full sack must slow the carrier: " + ladenSpeed
                    + " vs " + emptySpeed
                    + " [laden load=" + laden.getCarryLoad()
                    + "/" + laden.getCarryCapacity()
                    + " frac=" + laden.carryFraction()
                    + " bagSlot0=" + laden.bag.getItem(0)
                    + " act=" + laden.getActivity()
                    + " | empty load=" + empty.getCarryLoad()
                    + " frac=" + empty.carryFraction() + "]");
            helper.assertTrue(halfSpeed < emptySpeed && halfSpeed > ladenSpeed,
                "and the penalty must scale with the load: half=" + halfSpeed
                    + " full=" + ladenSpeed + " empty=" + emptySpeed);
            checked[0] = true;
            // Emptying the sack must give the speed straight back.
            laden.bag.clearContent();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(checked[0], "the laden checks must run first");
            helper.assertTrue(laden.getCarryLoad() == 0, "the sack is empty now");
            helper.assertTrue(
                laden.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    == empty.getAttributeValue(Attributes.MOVEMENT_SPEED),
                "an emptied sack must return the speed in full, got "
                    + laden.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    + " vs " + empty.getAttributeValue(Attributes.MOVEMENT_SPEED));
        });
    }

    /**
     * How many containers the warehouse index is reporting. KF-014 comes
     * down to exactly two remaining causes, and this separates them: if the
     * courier is idle with work available and this reads 0, the index is the
     * culprit; if it reads 1 and hearthNull is false, neither is, and the
     * gate that closed is something not yet enumerated.
     */
    private static int warehouseContainerCount(GameTestHelper helper, Settlement s) {
        if (!(helper.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return -1;
        }
        for (Building b : s.buildings) {
            if (b.valid && b.type == BuildingType.WAREHOUSE) {
                return com.hearthstead.settlement.warehouse.WarehouseStorage
                    .of(level, b).containers().size();
            }
        }
        return -2; // no valid warehouse at all
    }

    private static int bagCount(SettlerEntity settler) {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    /**
     * The sack is only worth drawing if its number is the real one. This
     * pins the synced carry load to the physical bag AND to the AI: with
     * more in the hearth than one trip can take, the load must peak at
     * exactly the capacity the goal loads to, because they are the same
     * number (D-A2b-1). If someone reintroduces a private LOAD_TRIGGER
     * constant, the peak stops matching and this fails.
     */
    @GameTest(template = "empty16", timeoutTicks = 2400, batch = "courier_day")
    public void courierSackShowsTheRealLoad(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            // Deliberately more than one sack holds: three trips, so the
            // peak load is the capacity and not just "whatever was there".
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, 20));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));

        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4));
        final int[] peak = {0};
        final String[] fault = {null};

        helper.succeedWhen(() -> {
            int synced = bud.getCarryLoad();
            int capacity = bud.getCarryCapacity();
            peak[0] = Math.max(peak[0], synced);
            if (synced > capacity && fault[0] == null) {
                fault[0] = "carried " + synced + " with capacity " + capacity;
            }
            float fraction = bud.carryFraction();
            if ((fraction < 0.0F || fraction > 1.0F) && fault[0] == null) {
                fault[0] = "carryFraction out of range: " + fraction;
            }
            helper.assertTrue(fault[0] == null, "sack state is wrong -- " + fault[0]);

            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            helper.assertTrue(chest != null, "warehouse chest should exist");
            int delivered = countIn(chest, Items.OAK_LOG);
            helper.assertTrue(delivered >= 20,
                "all 20 logs should be delivered, saw " + delivered
                    + " [load=" + synced + " peak=" + peak[0]
                    + " act=" + bud.getActivity()
                    + " energy=" + String.format("%.1f", bud.getEnergy())
                    + " hunger=" + String.format("%.1f", bud.getHunger())
                    + " phase=" + bud.dayPhase()
                    + " hearth=" + hearthCount(helper, hearthRel)
                    + " pos=" + bud.blockPosition().toShortString()
                    + " lastRouteFailure=" + bud.routeFailureNote()
                    + " hearthNull=" + (bud.hearth() == null)
                    + " containers=" + warehouseContainerCount(helper, s) + "]");
            // Specification correction (2026-08-26, logistics weight): a load is
            // bounded by what a courier can LIFT as well as by how many items
            // fit, so "fills to capacity" is only the right expectation for
            // cargo light enough that the count binds first. This hauls
            // OAK_LOG, which is HEAVY (4 units against a 16-unit bag), so the
            // real ceiling is 4, not 8.
            //
            // This is deliberately NOT loosened to "peak <= capacity", which
            // would pass a courier that hauled one log at a time forever.
            // Asserting the exact binding limit is STRICTER than the old
            // check: it still catches under-filling, and it now also catches
            // the weight table itself being wrong or silently bypassed.
            int expectedPeak = Weight.perLoad(new ItemStack(Items.OAK_LOG), capacity);
            helper.assertTrue(peak[0] == expectedPeak,
                "the sack should fill to whichever limit binds first -- for OAK_LOG that is "
                    + "weight, not the slot count: peak=" + peak[0] + " expected=" + expectedPeak
                    + " capacity=" + capacity);
            helper.assertTrue(synced == 0 && bagCount(bud) == 0,
                "an emptied sack must report empty, load=" + synced
                    + " bag=" + bagCount(bud));
        });
    }

    private static int hearthCount(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        if (!(be instanceof HearthBlockEntity hearth)) {
            return -1;
        }
        int n = 0;
        var inv = hearth.getInventory();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            n += inv.getStackInSlot(slot).getCount();
        }
        return n;
    }

    private static boolean doorOpen(GameTestHelper helper, BlockPos rel) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(rel));
        return state.getBlock() instanceof DoorBlock
            && state.getValue(DoorBlock.OPEN);
    }

    /**
     * Goods must never end up stranded in a courier's bag. With a warehouse
     * that has no container to receive them, the load comes home to the
     * hearth where the settlement can see and use it again.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "courier_day")
    public void courierWithNowhereToDeliverBringsGoodsBackToTheHearth(
        GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        // A warehouse with bounds but no chest: a real state, because the
        // plaque validates the room before anyone furnishes it.
        addWarehouse(helper, s, new BlockPos(8, 1, 8), new BlockPos(11, 4, 11));

        SettlerEntity bud = courier(helper, s, new BlockPos(9, 1, 9));
        bud.bag.addItem(new ItemStack(Items.OAK_LOG, 5));

        helper.succeedWhen(() -> {
            int bagged = 0;
            for (int i = 0; i < bud.bag.getContainerSize(); i++) {
                bagged += bud.bag.getItem(i).getCount();
            }
            BlockEntity be = helper.getLevel()
                .getBlockEntity(helper.absolutePos(hearthRel));
            helper.assertTrue(be instanceof HearthBlockEntity, "hearth should exist");
            int atHearth = 0;
            var inv = ((HearthBlockEntity) be).getInventory();
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (stack.is(Items.OAK_LOG)) {
                    atHearth += stack.getCount();
                }
            }
            helper.assertTrue(bagged + atHearth == 5,
                "logs must be conserved, bag=" + bagged + " hearth=" + atHearth);
            helper.assertTrue(atHearth == 5,
                "the undeliverable load should come back to the hearth, "
                    + "still carrying " + bagged);
        });
    }

    /**
     * The scenario the brief names directly: a courier is killed with real
     * goods physically in her sack. {@code SettlerEntity#die} documents that
     * a carried bag is "physically real" and must be dropped rather than
     * voided (the KF-027 lesson: a courier who loses her load to a place a
     * test does not count reads as destroyed items) -- this proves it against
     * a load the AI itself picked up mid-route, not a bag stuffed by hand,
     * so the whole chain (real pickup -> real death -> real drop) is
     * covered in one place, not assumed from the entity-level doc comment
     * alone.
     *
     * <p>The kill is timed off the courier's own state (first tick she is
     * genuinely CARRYING with cargo in hand), not a guessed tick count, so
     * this cannot start passing for the wrong reason if a timing constant
     * changes later. Conservation is counted across hearth + warehouse +
     * bag + dropped {@link ItemEntity} near her corpse -- the exact shape
     * KF-027 exposed, where a courier's lost load was invisible to a test
     * that only counted two chests.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "courier_day")
    public void courierKilledMidHaulDropsGoodsRatherThanDestroyingThem(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(3, 1, 3);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel, 12);
        int seeded = 6;
        if (helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
            hearth.insertGoods(new ItemStack(Items.OAK_LOG, seeded));
        }
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.CHEST);
        addWarehouse(helper, s, new BlockPos(9, 1, 9), new BlockPos(11, 3, 11));

        SettlerEntity bud = courier(helper, s, new BlockPos(4, 1, 4));
        final boolean[] killed = {false};
        final int[] carriedAtDeath = {0};
        final BlockPos[] deathPos = {null};

        helper.succeedWhen(() -> {
            int inBag = bagCount(bud);
            if (!killed[0]) {
                if (inBag <= 0 || bud.getActivity() != SettlerActivity.CARRYING) {
                    return; // keep polling until she is genuinely mid-haul
                }
                carriedAtDeath[0] = inBag;
                deathPos[0] = bud.blockPosition();
                bud.kill();
                killed[0] = true;
                helper.assertTrue(!bud.isAlive(), "the killing blow must land");
                return; // let the drop settle onto the ground before counting it
            }
            Container chest = containerAt(helper, new BlockPos(10, 1, 10));
            int atWarehouse = chest == null ? 0 : countIn(chest, Items.OAK_LOG);
            int atHearth = hearthCountOf(helper, hearthRel, Items.OAK_LOG);
            int dropped = 0;
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(deathPos[0]).inflate(4.0), e -> e.getItem().is(Items.OAK_LOG))) {
                dropped += item.getItem().getCount();
            }
            int total = atWarehouse + atHearth + dropped;
            helper.assertTrue(total == seeded,
                "logs must be conserved through a courier's death, saw " + total
                    + " of " + seeded + " [hearth=" + atHearth + " warehouse=" + atWarehouse
                    + " dropped=" + dropped + " carriedAtDeath=" + carriedAtDeath[0] + "]");
            helper.assertTrue(dropped == carriedAtDeath[0],
                "every log she was carrying at the moment of death should land on the "
                    + "ground near her, saw dropped=" + dropped + " carriedAtDeath="
                    + carriedAtDeath[0]);
            helper.assertTrue(bagCount(bud) == 0,
                "a dead courier's bag must not still report the load, saw "
                    + bagCount(bud));
        });
    }

    private static int hearthCountOf(GameTestHelper helper, BlockPos hearthRel,
                                     Item item) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel));
        if (!(be instanceof HearthBlockEntity hearth)) {
            return 0;
        }
        int n = 0;
        var inv = hearth.getInventory();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }
}
