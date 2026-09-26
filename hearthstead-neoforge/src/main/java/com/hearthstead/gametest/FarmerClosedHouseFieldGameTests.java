package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Normal-goal reproduction of B2's closed raised Farmhouse / opposite field.
 * Geometry is translated into empty16, with every block and work-zone cell
 * inside the template. After setup this test only observes: no goal ticking,
 * teleport, NoAI changes, door operation, seed injection or crop maturation.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FarmerClosedHouseFieldGameTests {
    private static final BlockPos DOOR = new BlockPos(9, 2, 3);
    private static final BlockPos CHEST = new BlockPos(9, 2, 7);

    /*
     * The nine-crop route deliberately uses vanilla beetroot loot.  A mature
     * beetroot has one beetroot plus up to four seeds: at most five physical
     * output units per original crop.  Farmer collection keeps the two real
     * drop stacks separate (seed stack, then beetroot) but moves each whole
     * stack through PICK(20), RETURN(1), STOW(22).  Chest delivery remains
     * one physical unit per 40 ticks after the 80-tick first/close cycle.
     *
     * The route terms are product bounds, not an extra wait: every submitted
     * farmer route repaths at 40 ticks and refuses after seven windows.  This
     * fixed arena can need one initial tool/field leg, two seed legs per crop,
     * and a field-to-chest plus return for each full eight-unit sack load.
     */
    private static final int NINE_CROP_PLOTS = 9;
    private static final int MAX_BEETROOT_OUTPUT_UNITS = NINE_CROP_PLOTS * 5;
    private static final int FARMER_CARRY_CAPACITY = SettlerEntity.BASE_CARRY_CAPACITY;
    private static final int MAX_OUTPUT_LOADS = (MAX_BEETROOT_OUTPUT_UNITS
        + FARMER_CARRY_CAPACITY - 1) / FARMER_CARRY_CAPACITY;
    private static final int FIELD_STACK_CONTACT_TICKS = 20 + 1 + 22;
    private static final int FIELD_CYCLE_TICKS = 28 + 36 + 28 + 32 + 6
        + 2 * FIELD_STACK_CONTACT_TICKS;
    private static final int CHEST_UNIT_TICKS = 40;
    private static final int CHEST_LOAD_CLOSE_TICKS = 40;
    private static final int ROUTE_REPLAN_TICKS = 40;
    private static final int ROUTE_WINDOWS = 7;
    private static final int BASE_ROUTE_LEGS = 1 /* tool */ + 1 /* first field */
        + 2 * NINE_CROP_PLOTS /* input + replant */;
    private static final int SURVEY_WINDOWS = NINE_CROP_PLOTS;
    private static final int MAX_ACTIVE_NINE_CROP_TICKS = NINE_CROP_PLOTS * FIELD_CYCLE_TICKS
        + MAX_BEETROOT_OUTPUT_UNITS * CHEST_UNIT_TICKS
        + MAX_OUTPUT_LOADS * CHEST_LOAD_CLOSE_TICKS
        + (BASE_ROUTE_LEGS + 2 * MAX_OUTPUT_LOADS - 1)
            * ROUTE_WINDOWS * ROUTE_REPLAN_TICKS
        + SURVEY_WINDOWS * ROUTE_REPLAN_TICKS;
    /* Start at 2000: work 3500, MEAL 1500, work 4500, then the next
       MORNING_WORK is 13,500 wall ticks away. The final 524 active ticks
       therefore cross that next day's 1500-tick MEAL as well. */
    private static final int MAX_SCHEDULED_NONWORK_TICKS = 1500 + 13500 + 1500;
    private static final int NINE_CROP_TIMEOUT_TICKS = MAX_ACTIVE_NINE_CROP_TICKS
        + MAX_SCHEDULED_NONWORK_TICKS + 76;
    /** A stalled job must fail near its own seven-window route contract. */
    private static final int MAX_NO_PROGRESS_TICKS = ROUTE_WINDOWS * ROUTE_REPLAN_TICKS
        + ROUTE_REPLAN_TICKS;

    /** The variable term is only the physical output actually committed so far. */
    private static int activeBudgetFor(int producedOutputUnits) {
        int loads = (producedOutputUnits + FARMER_CARRY_CAPACITY - 1)
            / FARMER_CARRY_CAPACITY;
        int routeLegs = BASE_ROUTE_LEGS + (loads == 0 ? 0 : 2 * loads - 1);
        return NINE_CROP_PLOTS * FIELD_CYCLE_TICKS
            + producedOutputUnits * CHEST_UNIT_TICKS
            + loads * CHEST_LOAD_CLOSE_TICKS
            + routeLegs * ROUTE_WINDOWS * ROUTE_REPLAN_TICKS
            + SURVEY_WINDOWS * ROUTE_REPLAN_TICKS;
    }

    private static void arena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 6; y++) {
                    boolean rim = x == 0 || x == 15 || z == 0 || z == 15;
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 3 ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
            }
        }
        // Live B2 room: 7x7 hollow box, one-block raised floor, north door.
        // Outdoor foot Y=1, indoor foot Y=2, crop soil Y=1 / crop Y=2.
        for (int x = 6; x <= 12; x++) {
            for (int y = 1; y <= 5; y++) {
                for (int z = 3; z <= 9; z++) {
                    boolean shell = x == 6 || x == 12 || y == 1 || y == 5
                        || z == 3 || z == 9;
                    helper.setBlock(new BlockPos(x, y, z),
                        shell ? Blocks.STONE_BRICKS : Blocks.AIR);
                }
            }
        }
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.SOUTH)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        helper.setBlock(DOOR, lower);
        helper.setBlock(DOOR.above(), lower
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(CHEST, Blocks.CHEST);
        helper.setBlock(new BlockPos(8, 2, 7), Blocks.COMPOSTER);
        helper.setBlock(new BlockPos(7, 2, 8), Blocks.TORCH);
        for (int x = 4; x <= 6; x++) {
            for (int z = 10; z <= 12; z++) {
                helper.setBlock(new BlockPos(x, 1, z),
                    Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                helper.setBlock(new BlockPos(x, 2, z),
                    ((CropBlock) Blocks.BEETROOTS).getStateForAge(3));
            }
        }
        // Same corner-height selection fence as B2; neighboring contact is
        // allowed, so the worker need not stand inside this obstructed crop.
        helper.setBlock(new BlockPos(6, 3, 10), Blocks.OAK_FENCE);
    }

    /** Failure-only observation; target changes do not reset the physical-progress watchdog. */
    private static String farmerWorkFailureDiagnostic(SettlerEntity farmer) {
        StringBuilder result = new StringBuilder(", workState={");
        for (var wrapped : farmer.goalSelector.getAvailableGoals()) {
            if (!(wrapped.getGoal() instanceof com.hearthstead.entity.ai.FarmerWorkGoal goal)) continue;
            result.append("running=").append(wrapped.isRunning());
            for (String name : new String[]{"mode", "target", "maintainTarget", "queue",
                    "maintainQueue", "fieldColumnCursor", "fieldSweepComplete", "stuckChecks", "done"}) {
                try {
                    var field = goal.getClass().getDeclaredField(name);
                    field.setAccessible(true);
                    result.append(", ").append(name).append('=').append(field.get(goal));
                } catch (ReflectiveOperationException failure) {
                    result.append(", ").append(name).append("=unavailable");
                }
            }
        }
        var path = farmer.getNavigation().getPath();
        result.append(", path=").append(path == null ? "none"
            : path.getTarget() + ":reachable=" + path.canReach() + ":next=" + path.getNextNodeIndex());
        return result.append('}').toString();
    }

    private static String transferDiagnostic(SettlerEntity farmer) {
        var transfer = farmer.bagTransferPresentation();
        return "{active=" + transfer.active()
            + ", id=" + transfer.transferId()
            + ", clock=" + transfer.clock()
            + ", anchor=" + transfer.bagAnchor()
            + ", container=" + transfer.containerPos()
            + ", committed=" + transfer.committed()
            + ", sourcePickup=" + transfer.sourcePickup()
            + ", item=" + transfer.item() + "}";
    }
    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // The active budget is mechanically derived above: max vanilla loot,
    // exact stack/unit contacts, bounded route legs and nine survey windows.
    // 29,600 is that 13,024 active maximum plus the two scheduled MEAL phases,
    // intervening evening/rest/rise cycle, and a 76-tick GameTest handoff.
    // A separate 320-tick physical-progress watchdog runs only in an actual
    // work window, so scheduled village life cannot masquerade as a stall.
    @GameTest(template = "empty16", timeoutTicks = NINE_CROP_TIMEOUT_TICKS,
        batch = "farmer_closed_house_field_route")
    public void farmerExitsRaisedClosedHouseAndHarvestsEveryOppositeCrop(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        arena(helper);
        SettlementSavedData settlements = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Closedfield",
            helper.absolutePos(new BlockPos(8, 2, 8)));
        settlement.radius = 7;
        settlements.settlements.put(settlement.id, settlement);
        settlements.setDirty();
        Building farmhouse = GameTestFixtures.registerWithBounds(helper,
            settlement, BuildingType.FARMHOUSE, new BlockPos(9, 2, 6),
            new BlockPos(10, 2, 2), BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(6, 1, 3)),
                helper.absolutePos(new BlockPos(12, 5, 9))));
        WorkZone field = WorkZone.between(settlement.id, farmhouse.id,
            WorkZone.Type.FARM, helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(4, 1, 10)),
            helper.absolutePos(new BlockPos(6, 3, 12)), 2);
        helper.assertTrue(farmhouse.commitWorkZone(1, field),
            "fixture: exact 3x3x3 Farm Zone must replace the default arena zone");
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        helper.assertTrue(chest != null, "fixture: linked Farmhouse chest must exist");
        chest.setItem(0, new ItemStack(Items.IRON_HOE));
        chest.setItem(1, new ItemStack(Items.BEETROOT_SEEDS, 32));
        chest.setChanged();
        SettlerEntity farmer = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(9, 2, 6));
        farmer.setSettlerName("Solveig Closedfield");
        farmer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(farmer.getUUID(), farmer.getSettlerName(), Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, farmhouse, farmer).ok(),
            "fixture: the exact Farmhouse must hire the real Farmer");
        farmer.attributes().pinForTest(com.hearthstead.entity.Attribute.DEXTERITY, 10);
        farmer.setHunger(100);
        farmer.setEnergy(100);

        Set<BlockPos> cropPositions = new HashSet<>();
        for (int x = 4; x <= 6; x++) {
            for (int z = 10; z <= 12; z++) {
                cropPositions.add(helper.absolutePos(new BlockPos(x, 2, z)));
            }
        }
        BoundingBox interior = BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(7, 2, 4)),
            helper.absolutePos(new BlockPos(11, 4, 8)));
        BoundingBox outsideDoor = BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(8, 1, 1)),
            helper.absolutePos(new BlockPos(10, 3, 2)));
        boolean[] openedDoor = {false};
        boolean[] exitedDoor = {false};
        Set<UUID> observedHarvests = new HashSet<>();
        Set<UUID> observedPlantActions = new HashSet<>();
        Map<BlockPos, UUID> firstHarvestForCrop = new HashMap<>();
        WorkerProvenanceSavedData provenance = WorkerProvenanceSavedData.get(helper.getLevel());
        var beetroot = BuiltInRegistries.ITEM.getKey(Items.BEETROOT);
        float[] lowestNeeds = {farmer.getHunger(), farmer.getEnergy()};
        int[] needsTicks = {0, 0, 0}; // Meal phase, actual eating, actual resting/sleeping.
        int[] activeWorkTicks = {0};
        int[] scheduledNonWorkTicks = {0};
        int[] activeTransferTicks = {0};
        Map<SettlerActivity, Integer> activityTicks = new EnumMap<>(SettlerActivity.class);
        String[] lastProgress = {""};
        long[] lastProgressTick = {0L};

        helper.onEachTick(() -> {
            activityTicks.merge(farmer.getActivity(), 1, Integer::sum);
            if (farmer.bagTransferPresentation().active()) activeTransferTicks[0]++;
            lowestNeeds[0] = Math.min(lowestNeeds[0], farmer.getHunger());
            lowestNeeds[1] = Math.min(lowestNeeds[1], farmer.getEnergy());
            if (farmer.dayPhase().meal()) needsTicks[0]++;
            if (farmer.dayPhase().work()) activeWorkTicks[0]++;
            else scheduledNonWorkTicks[0]++;
            int producedOutputUnits = provenance.actionsForSettlement(settlement.id).stream()
                .filter(action -> action.kind() == WorkerProvenanceSavedData.Kind.FARM_HARVEST
                    && action.workerId().equals(farmer.getUUID()) && action.workTerminal())
                .mapToInt(action -> action.produced().values().stream()
                    .mapToInt(Integer::intValue).sum())
                .sum();
            int activeBudget = activeBudgetFor(producedOutputUnits);
            helper.assertTrue(activeWorkTicks[0] <= activeBudget,
                "nine-crop active-work budget exhausted: elapsed=" + helper.getTick()
                    + ", activeWorkTicks=" + activeWorkTicks[0]
                    + ", scheduledNonWorkTicks=" + scheduledNonWorkTicks[0]
                    + ", producedOutputUnits=" + producedOutputUnits
                    + ", activeBudget=" + activeBudget);
            if (farmer.getActivity() == SettlerActivity.EATING) needsTicks[1]++;
            if (farmer.getActivity() == SettlerActivity.RESTING || farmer.isSleeping()) needsTicks[2]++;
            BlockState door = helper.getBlockState(DOOR);
            openedDoor[0] |= door.is(Blocks.OAK_DOOR) && door.getValue(DoorBlock.OPEN);
            exitedDoor[0] |= openedDoor[0] && outsideDoor.isInside(farmer.blockPosition());
            for (var action : provenance.actionsForSettlement(settlement.id)) {
                if (!action.workerId().equals(farmer.getUUID()) || !action.workTerminal()) {
                    continue;
                }
                if (action.kind() == WorkerProvenanceSavedData.Kind.FARM_PLANT) {
                    observedPlantActions.add(action.id());
                    continue;
                }
                if (action.kind() != WorkerProvenanceSavedData.Kind.FARM_HARVEST
                    || !observedHarvests.add(action.id())) continue;
                helper.assertTrue(action.planned().size() == 1
                        && cropPositions.contains(action.planned().getFirst()),
                    "every harvested position must belong to the nine exact original crop cells");
                BlockPos crop = action.planned().getFirst();
                // Production currently has no crop-contact witness equivalent
                // to StorageMutationWitness. Observe the terminal action on the
                // next test tick, while the harvest pose/navigation-stop holds.
                boolean clear = helper.getLevel().clip(new ClipContext(
                    farmer.getEyePosition(), Vec3.atBottomCenterOf(crop).add(0, 0.2D, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, farmer))
                    .getType() == HitResult.Type.MISS;
                helper.assertTrue(exitedDoor[0] && !interior.isInside(farmer.blockPosition())
                        && farmer.blockPosition().distSqr(crop) <= 6.5D && clear,
                    "FARM_HARVEST crossed the solid Farmhouse wall before accessible physical contact: "
                        + "worker=" + farmer.position() + ", crop=" + crop
                        + ", openedDoor=" + openedDoor[0] + ", exitedDoor=" + exitedDoor[0]
                        + ", clear=" + clear + ", route=" + farmer.routeFailureNote());
                firstHarvestForCrop.putIfAbsent(crop, action.id());
            }
            boolean activeProductionWindow = farmer.dayPhase().work()
                && farmer.getActivity() != SettlerActivity.EATING
                && farmer.getActivity() != SettlerActivity.RESTING && !farmer.isSleeping();
            if (!activeProductionWindow) {
                // Farmer work is phase-gated. Do not turn a scheduled meal,
                // evening or bed visit into a false path-stall assertion.
                lastProgress[0] = "";
                lastProgressTick[0] = helper.getTick();
            } else {
                String progress = farmer.blockPosition() + "|" + farmer.getActivity()
                    + "|load=" + farmer.getCarryLoad() + "|offhand=" + farmer.getOffhandItem()
                    + "|field=" + farmer.getPersistentData().getCompound("HearthsteadGroundedFieldBag")
                    + "|transfer=" + transferDiagnostic(farmer)
                    + "|harvests=" + observedHarvests.size() + "|plants=" + observedPlantActions.size();
                if (!progress.equals(lastProgress[0])) {
                    lastProgress[0] = progress;
                    lastProgressTick[0] = helper.getTick();
                }
                helper.assertTrue(helper.getTick() - lastProgressTick[0] <= MAX_NO_PROGRESS_TICKS,
                    "nine-crop route made no physical/session progress for "
                        + (helper.getTick() - lastProgressTick[0]) + " ticks; last=" + lastProgress[0]
                        + ", route=" + farmer.routeFailureNote()
                        + (helper.getTick() - lastProgressTick[0] > MAX_NO_PROGRESS_TICKS
                            ? farmerWorkFailureDiagnostic(farmer) : ""));
            }
        });

        helper.succeedWhen(() -> {
            long terminalPlantTicks = provenance.actionsForSettlement(settlement.id).stream()
                .filter(action -> action.kind() == WorkerProvenanceSavedData.Kind.FARM_PLANT
                    && action.workerId().equals(farmer.getUUID()) && action.workTerminal())
                .mapToLong(action -> action.updatedTick() - action.createdTick()).sum();
            String timing = ", timing={activityTicks=" + activityTicks
                + ", activeTransferTicks=" + activeTransferTicks[0]
                + ", terminalHarvestActions=" + observedHarvests.size()
                + ", terminalPlantActions=" + observedPlantActions.size()
                + ", terminalPlantTicks=" + terminalPlantTicks + "}";
            helper.assertTrue(openedDoor[0] && exitedDoor[0],
                "the normal Farmer/door goals must open and cross the sole north doorway" + timing);
            helper.assertTrue(firstHarvestForCrop.keySet().equals(cropPositions),
                "all nine distinct original crops must be harvested after physical exit; harvested="
                    + firstHarvestForCrop.size() + ", pos=" + farmer.position()
                    + ", navDone=" + farmer.getNavigation().isDone()
                    + ", activity=" + farmer.getActivity()
                    + ", dayTime=" + helper.getLevel().getDayTime() + ", phase=" + farmer.dayPhase()
                    + ", hunger=" + farmer.getHunger() + ", energy=" + farmer.getEnergy()
                    + ", lowestHunger=" + lowestNeeds[0] + ", lowestEnergy=" + lowestNeeds[1]
                    + ", mealPhaseTicks=" + needsTicks[0] + ", eatingTicks=" + needsTicks[1]
                    + ", activeWorkTicks=" + activeWorkTicks[0]
                    + ", scheduledNonWorkTicks=" + scheduledNonWorkTicks[0]
                    + ", restingTicks=" + needsTicks[2] + ", hasMeal=" + farmer.hasMeal()
                    + ", sleeping=" + farmer.isSleeping() + ", claimedBed=" + farmer.getClaimedBed()
                    + ", remaining=" + cropPositions.stream().filter(pos -> !firstHarvestForCrop.containsKey(pos)).toList()
                    + ", fieldBag=" + farmer.getPersistentData().getCompound("HearthsteadGroundedFieldBag")
                    + ", chest=" + count(chest, Items.BEETROOT)
                    + ", bag=" + count(farmer.bag, Items.BEETROOT)
                    + ", depositClock=" + farmer.bagTransferPresentation().clock()
                    + ", route=" + farmer.routeFailureNote() + timing);
            for (UUID actionId : firstHarvestForCrop.values()) {
                int deposited = provenance.receiptsFor(actionId).stream()
                    .filter(receipt -> receipt.workerId().equals(farmer.getUUID())
                        && receipt.buildingId().equals(farmhouse.id)
                        && receipt.containerPos().equals(helper.absolutePos(CHEST))
                        && receipt.itemId().equals(beetroot))
                    .mapToInt(WorkerProvenanceSavedData.DepositReceipt::count).sum();
                if (deposited != 1) helper.assertTrue(false,
                    "each original crop must have exactly one physical beetroot deposited in its Farmhouse"
                        + "; missingAction=" + actionId + ", deposited=" + deposited
                        + ", elapsed=" + helper.getTick() + ", worldTick=" + helper.getLevel().getGameTime()
                        + ", action=" + provenance.actionsForSettlement(settlement.id).stream()
                            .filter(action -> action.id().equals(actionId)).findFirst()
                        + ", receipts=" + provenance.receiptsFor(actionId)
                        + ", chestBeets=" + count(chest, Items.BEETROOT)
                        + ", bag=" + java.util.stream.IntStream.range(0, farmer.bag.getContainerSize())
                            .filter(slot -> !farmer.bag.getItem(slot).isEmpty())
                            .mapToObj(slot -> slot + ":" + farmer.bag.getItem(slot)
                                + ":" + farmer.bag.getItem(slot).getComponents()).toList()
                        + ", offhand=" + farmer.getOffhandItem() + ":" + farmer.getOffhandItem().getComponents()
                        + ", looseBeets=" + helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds(),
                            entity -> entity.getItem().is(Items.BEETROOT)).stream()
                            .map(entity -> entity.getUUID() + "@" + entity.position() + ":" + entity.getItem()
                                + ":" + entity.getItem().getComponents()).toList()
                        + ", fieldBag=" + farmer.getPersistentData().getCompound("HearthsteadGroundedFieldBag")
                        + ", activity=" + farmer.getActivity() + ", phase=" + farmer.dayPhase()
                        + ", hunger=" + farmer.getHunger() + ", energy=" + farmer.getEnergy()
                        + ", eatingTicks=" + needsTicks[1] + ", restingTicks=" + needsTicks[2]
                        + ", mealPhaseTicks=" + needsTicks[0] + ", pos=" + farmer.position()
                        + ", navDone=" + farmer.getNavigation().isDone()
                        + ", transfer=" + transferDiagnostic(farmer)
                        + ", held=" + farmer.getMainHandItem()
                        + ", equipmentRequest=" + EquipmentRequests.requestFor(farmhouse, farmer.getUUID())
                        + ", placedWorkContainer=" + farmer.placedWorkContainerPos()
                        + ":" + farmer.placedWorkContainerKind()
                        + ", route=" + farmer.routeFailureNote() + timing);
            }
            int produced = provenance.actionsForSettlement(settlement.id).stream()
                .filter(action -> action.kind() == WorkerProvenanceSavedData.Kind.FARM_HARVEST
                    && action.workerId().equals(farmer.getUUID()) && action.workTerminal())
                .mapToInt(action -> action.produced().getOrDefault(beetroot, 0)).sum();
            boolean loose = !helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                helper.getBounds(), entity -> entity.getItem().is(Items.BEETROOT)).isEmpty();
            // Do not alter global randomTickSpeed. If normal random ticks grow
            // an already replanted cell again, account for its new production
            // while still requiring all nine original cells and their receipts.
            helper.assertTrue(produced >= 9 && count(chest, Items.BEETROOT) == produced
                    && count(farmer.bag, Items.BEETROOT) == 0 && !loose,
                "every physically produced beetroot must reach the exact Farmhouse without loss or duplication"
                    + timing);
            com.hearthstead.Hearthstead.LOGGER.info(
                "HSQA_FARMER_NINE_CROPS_COMPLETE worker={} elapsed={} produced={} chest={}{}",
                farmer.getUUID(), helper.getTick(), produced, count(chest, Items.BEETROOT), timing);
        });
    }

    /**
     * B2 rear-wall cargo return: a useful route around the room must survive
     * the real Farmer's forty-tick replans. The old contact selector repeatedly
     * replaced that doorway route with a shorter incomplete rear-wall path.
     * This isolates the return leg; ordinary carried items are fixture cargo,
     * not claims of authenticated harvest production. No AI or physics changes.
     */
    // Fourteen unit contacts require600 action ticks; preserve the original600 route budget too.
    @GameTest(template = "empty16", timeoutTicks = 1200,
        batch = "farmer_closed_house_field_route")
    public void farmerReturnsRearWallCargoThroughClosedDoorWithoutOscillation(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        arena(helper);
        SettlementSavedData settlements = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Rearwall Return",
            helper.absolutePos(new BlockPos(8, 2, 8)));
        settlement.radius = 7;
        settlements.settlements.put(settlement.id, settlement);
        settlements.setDirty();
        Building farmhouse = GameTestFixtures.registerWithBounds(helper,
            settlement, BuildingType.FARMHOUSE, new BlockPos(9, 2, 6),
            new BlockPos(10, 2, 2), BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(6, 1, 3)),
                helper.absolutePos(new BlockPos(12, 5, 9))));
        Container chest = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(CHEST));
        helper.assertTrue(chest != null, "fixture: exact empty Farmhouse chest exists");
        SettlerEntity farmer = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(11, 1, 10));
        farmer.setSettlerName("Gudrun Rearwall");
        farmer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(farmer.getUUID(), farmer.getSettlerName(), Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, farmhouse, farmer).ok(),
            "fixture: real Farmhouse employment must accept the returning Farmer");
        farmer.setHunger(100);
        farmer.setEnergy(100);
        // B2 carried fourteen units during the stalled return. Four beetroot
        // and ten seed units reproduce that load without manufacturing output
        // receipts. No hoe is supplied: after return, no new crops can be cut.
        farmer.bag.setItem(0, new ItemStack(Items.BEETROOT, 4));
        farmer.bag.setItem(1, new ItemStack(Items.BEETROOT_SEEDS, 10));
        farmer.bag.setChanged();
        BlockPos initial = helper.absolutePos(new BlockPos(11, 1, 10));
        farmer.moveTo(initial.getX() + 0.2D, initial.getY(),
            initial.getZ() + 0.5D, 180.0F, 0.0F);

        boolean[] openedDoor = {false};
        boolean[] reachedFront = {false};
        boolean[] witnessedDeposit = {false};
        BoundingBox outsideDoor = BoundingBox.fromCorners(
            helper.absolutePos(new BlockPos(8, 1, 1)),
            helper.absolutePos(new BlockPos(10, 3, 2)));
        // Observer only: no routes, goals, inventory, clocks or time bounds are changed.
        class RearwallObservation {
            final long initialGameTick = helper.getLevel().getGameTime();
            final Map<SettlerActivity, Integer> activityTicks = new EnumMap<>(SettlerActivity.class);
            long firstFront = -1, firstContact = -1, firstDeposit = -1;
            long cooldownStarted = -1, lastCooldownElapsed;
            int lastCooldown = -1, cooldownTicks, cooldownDecrements, stalledTicks, advancedTicks;
            int lastClock = -1, lastStoredBeetroot = -1, lastStoredSeeds = -1;
            int lastBagBeetroot = -1, lastBagSeeds = -1;
            UUID lastTransfer;
            String lastRoute = "", lastState = "";
            boolean sawDoor;

            int cooldown() {
                for (var wrapped : farmer.goalSelector.getAvailableGoals()) {
                    if (!(wrapped.getGoal() instanceof com.hearthstead.entity.ai.FarmerWorkGoal)) continue;
                    try {
                        var field = wrapped.getGoal().getClass().getDeclaredField("depositRetryCooldown");
                        field.setAccessible(true);
                        return field.getInt(wrapped.getGoal());
                    } catch (ReflectiveOperationException failure) { return -1; }
                }
                return -1;
            }

            String summary() {
                return "{firstFront=" + firstFront + ", firstContact=" + firstContact
                    + ", firstDeposit=" + firstDeposit + ", activityTicks=" + activityTicks
                    + ", advancedTransferTicks=" + advancedTicks + ", currentStallTicks=" + stalledTicks
                    + ", cooldownTicks=" + cooldownTicks + ", observedCooldownDecrements=" + cooldownDecrements
                    + ", currentCooldown=" + lastCooldown + ", lastCooldownElapsedWorldTicks=" + lastCooldownElapsed + "}";
            }

            void observe(int storedBeetroot, int storedSeeds, int looseBeetroot, int looseSeeds) {
                long tick = helper.getTick(), world = helper.getLevel().getGameTime();
                int bagBeetroot = count(farmer.bag, Items.BEETROOT);
                int bagSeeds = count(farmer.bag, Items.BEETROOT_SEEDS);
                activityTicks.merge(farmer.getActivity(), 1, Integer::sum);
                var contact = com.hearthstead.settlement.work.ContainerApproach.inspect(
                    helper.getLevel(), farmer, helper.absolutePos(CHEST));
                String moveOwners = farmer.goalSelector.getAvailableGoals().stream()
                    .filter(wrapped -> wrapped.isRunning() && wrapped.getGoal().getFlags()
                        .contains(net.minecraft.world.entity.ai.goal.Goal.Flag.MOVE))
                    .map(wrapped -> wrapped.getGoal().getClass().getSimpleName()).sorted()
                    .collect(java.util.stream.Collectors.joining("+"));
                String state = farmer.getActivity() + "/" + moveOwners + "/" + contact.state();
                String route = farmer.routeFailureNote();
                StringBuilder events = new StringBuilder();
                if (openedDoor[0] && !sawDoor) { sawDoor = true; events.append("door|"); }
                if (reachedFront[0] && firstFront < 0) { firstFront = tick; events.append("front|"); }
                if (contact.canInteract() && firstContact < 0) { firstContact = tick; events.append("first_contact|"); }
                if (storedBeetroot + storedSeeds > 0 && firstDeposit < 0) firstDeposit = tick;
                if (!state.equals(lastState)) events.append("activity_or_move_owner_or_contact|");
                if (!java.util.Objects.equals(route, lastRoute)) events.append("route_change|");
                if (storedBeetroot != lastStoredBeetroot || storedSeeds != lastStoredSeeds
                    || bagBeetroot != lastBagBeetroot || bagSeeds != lastBagSeeds) events.append("cargo_change|");
                int currentCooldown = cooldown();
                if (currentCooldown > 0) cooldownTicks++;
                if (lastCooldown > currentCooldown && currentCooldown >= 0)
                    cooldownDecrements += lastCooldown - currentCooldown;
                if (currentCooldown > 0 && lastCooldown <= 0) {
                    cooldownStarted = world; events.append("cooldown_start|");
                } else if (currentCooldown == 0 && lastCooldown > 0) {
                    lastCooldownElapsed = world - cooldownStarted; events.append("cooldown_end|");
                }
                var transfer = farmer.bagTransferPresentation();
                boolean sameTransfer = transfer.active() && transfer.transferId().equals(lastTransfer);
                if (sameTransfer && transfer.clock() > lastClock) advancedTicks++;
                if (sameTransfer && transfer.clock() == lastClock) {
                    if (++stalledTicks == 6) events.append("transfer_stall|");
                } else {
                    if (stalledTicks >= 6) events.append("transfer_resumed_or_replaced_after_").append(stalledTicks).append("|");
                    stalledTicks = 0;
                }
                if (transfer.active() && lastTransfer == null) events.append("transfer_start|");
                lastState = state; lastRoute = route; lastCooldown = currentCooldown;
                lastStoredBeetroot = storedBeetroot; lastStoredSeeds = storedSeeds;
                lastBagBeetroot = bagBeetroot; lastBagSeeds = bagSeeds;
                lastTransfer = transfer.active() ? transfer.transferId() : null; lastClock = transfer.clock();
                if (events.length() > 0) Hearthstead.LOGGER.info(
                    "HSQA_FARMER_REARWALL_PHASE worker={} testTick={} worldTick={} elapsedWorld={} events={} state={} stored={}/{} bag={}/{} loose={}/{} conserved={} pos={} chest={} route={} transfer={} observation={}{}",
                    farmer.getUUID(), tick, world, world - initialGameTick, events, state,
                    storedBeetroot, storedSeeds, bagBeetroot, bagSeeds, looseBeetroot, looseSeeds,
                    storedBeetroot + bagBeetroot + looseBeetroot == 4 && storedSeeds + bagSeeds + looseSeeds == 10,
                    farmer.position(), helper.absolutePos(CHEST), route, transferDiagnostic(farmer), summary(),
                    farmerWorkFailureDiagnostic(farmer));
            }
        }
        RearwallObservation observation = new RearwallObservation();
        helper.onEachTick(() -> {
            BlockState door = helper.getBlockState(DOOR);
            openedDoor[0] |= door.is(Blocks.OAK_DOOR) && door.getValue(DoorBlock.OPEN);
            reachedFront[0] |= outsideDoor.isInside(farmer.blockPosition());
            int storedBeetroot = count(chest, Items.BEETROOT);
            int storedSeeds = count(chest, Items.BEETROOT_SEEDS);
            int looseBeetroot = 0;
            int looseSeeds = 0;
            for (ItemEntity loose : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    helper.getBounds())) {
                if (loose.getItem().is(Items.BEETROOT)) looseBeetroot += loose.getItem().getCount();
                if (loose.getItem().is(Items.BEETROOT_SEEDS)) looseSeeds += loose.getItem().getCount();
            }
            observation.observe(storedBeetroot, storedSeeds, looseBeetroot, looseSeeds);
            helper.assertTrue(!farmer.routeFailureNote().startsWith("farmhouse_storage_unreachable:"),
                "the reachable doorway route must not be retired as unreachable during ordinary cargo return; "
                    + "testTick=" + helper.getTick() + ", pos=" + farmer.position()
                    + ", route=" + farmer.routeFailureNote() + ", observation=" + observation.summary()
                    + farmerWorkFailureDiagnostic(farmer));
            helper.assertTrue(storedBeetroot + count(farmer.bag, Items.BEETROOT)
                    + looseBeetroot == 4
                    && storedSeeds + count(farmer.bag, Items.BEETROOT_SEEDS) + looseSeeds == 10,
                "every original cargo unit must remain accounted for throughout real navigation");
            if (!witnessedDeposit[0] && (storedBeetroot > 0 || storedSeeds > 0)) {
                helper.assertTrue(openedDoor[0] && reachedFront[0]
                        && com.hearthstead.settlement.work.ContainerApproach.inspect(
                            helper.getLevel(), farmer, helper.absolutePos(CHEST)).canInteract(),
                    "first real deposit must follow physical front-door passage and visible chest contact");
                witnessedDeposit[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(witnessedDeposit[0] && openedDoor[0] && reachedFront[0],
                "normal Farmer replans must retain the accessible door detour; "
                    + "witnessedDeposit=" + witnessedDeposit[0]
                    + ", openedDoor=" + openedDoor[0] + ", reachedFront=" + reachedFront[0]
                    + ", storedBeetroot=" + count(chest, Items.BEETROOT)
                    + ", storedSeeds=" + count(chest, Items.BEETROOT_SEEDS)
                    + ", bagBeetroot=" + count(farmer.bag, Items.BEETROOT)
                    + ", bagSeeds=" + count(farmer.bag, Items.BEETROOT_SEEDS)
                    + ", pos=" + farmer.position() + ", activity=" + farmer.getActivity()
                    + ", navDone=" + farmer.getNavigation().isDone()
                    + ", navTarget=" + farmer.getNavigation().getTargetPos()
                    + ", origin=" + helper.absolutePos(BlockPos.ZERO)
                    + ", door=" + helper.absolutePos(DOOR)
                    + ", doorState=" + helper.getBlockState(DOOR)
                    + ", chest=" + helper.absolutePos(CHEST)
                    + ", contact=" + com.hearthstead.settlement.work.ContainerApproach.inspect(
                        helper.getLevel(), farmer, helper.absolutePos(CHEST)).state()
                    + ", route=" + farmer.routeFailureNote()
                    + ", observation=" + observation.summary());
            helper.assertTrue(count(chest, Items.BEETROOT) == 4
                    && count(chest, Items.BEETROOT_SEEDS) == 10
                    && count(farmer.bag, Items.BEETROOT) == 0
                    && count(farmer.bag, Items.BEETROOT_SEEDS) == 0,
                "all fourteen original cargo units must reach the exact linked Farmhouse chest; "
                    + "storedBeetroot=" + count(chest, Items.BEETROOT)
                    + ", storedSeeds=" + count(chest, Items.BEETROOT_SEEDS)
                    + ", bagBeetroot=" + count(farmer.bag, Items.BEETROOT)
                    + ", bagSeeds=" + count(farmer.bag, Items.BEETROOT_SEEDS)
                    + ", testTick=" + helper.getTick()
                    + ", pos=" + farmer.position()
                    + ", activity=" + farmer.getActivity()
                    + ", navDone=" + farmer.getNavigation().isDone()
                    + ", navTarget=" + farmer.getNavigation().getTargetPos()
                    + ", contact=" + com.hearthstead.settlement.work.ContainerApproach.inspect(
                        helper.getLevel(), farmer, helper.absolutePos(CHEST)).state()
                    + ", transfer=" + transferDiagnostic(farmer)
                    + ", placedWorkContainer=" + farmer.placedWorkContainerPos()
                    + ":" + farmer.placedWorkContainerKind()
                    + ", route=" + farmer.routeFailureNote()
                    + ", observation=" + observation.summary());
            Hearthstead.LOGGER.info("HSQA_FARMER_REARWALL_COMPLETE worker={} testTick={} observation={}",
                farmer.getUUID(), helper.getTick(), observation.summary());
        });
    }
}
