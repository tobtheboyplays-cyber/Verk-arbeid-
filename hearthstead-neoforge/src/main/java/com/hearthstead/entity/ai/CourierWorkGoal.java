package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Fuel;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The courier: moves goods between the hearth, the warehouse and the
 * workshops -- restocking crafters short of their own raw material, and
 * collecting their surplus product back.
 *
 * <p><b>Four routes, never a request queue.</b> A courier consolidates
 * hearth goods into a warehouse, restocks a crafter short of its own raw
 * material -- or, for a building that burns, short of fuel -- from a
 * warehouse that has some, carries prepared food from a warehouse to a
 * hearth running low (FLOWS.md route 5 -- without it the bakery's bread
 * strands on a warehouse shelf while the village goes hungry beside it),
 * or collects a workshop's surplus
 * OUTPUT into a warehouse (FLOWS.md route 4, the return leg of the economy
 * loop -- without it the mason's bricks, the smelter's ingots and the
 * mine's entire yield strand forever in their own chests and the smithy can
 * only ever be fed by hand). {@link JobPriority} orders the choice; no
 * route ever carries a building's own raw material AWAY from it, and none
 * touches anything closer to a worksite than a building's own
 * chests. MineColonies shipped a
 * courier/builder circular wait where each side blocked on the other (issue
 * #5333); nothing here can do that, because neither route ever waits ON
 * anybody: a courier decides a destination from the world as it stands right
 * now, delivers, and is done -- no request outlives the trip that satisfies
 * it, and no building's own work goal ({@code CrafterWorkGoal}) ever blocks
 * waiting for a courier (D-007: a building works alone; a courier is an
 * optimisation on top of that, never a precondition for it).
 *
 * <p><b>Food never leaves the hearth</b> (D-A2a-1). {@code EatFromHearthGoal}
 * and {@code Settlement.foodCache} both read hearth contents, so draining
 * food into a warehouse would quietly starve the settlement. This is about
 * the HEARTH specifically: a recipe's raw material can itself be a food item
 * (raw beef, a potato) sitting in a warehouse a player filled by hand, and
 * restocking a butcher or kitchen with it is fine -- that route never
 * touches the hearth. The hearth is a one-way food valve: the FOOD_DELIVERY
 * route carries meals INTO it from a warehouse when the larder runs low,
 * and nothing ever carries them back out.
 *
 * <p><b>Chests are the truth</b> (D-A2a-3): goods are removed from the
 * source into the bag, and inserted into the destination-first with the true
 * leftover carried back. At every instant the items exist in exactly one
 * real container, so an interruption -- including the courier dying, which
 * {@code SettlerEntity#die} drops the bag for (every profession, not just
 * this one, so it is not duplicated here) -- conserves them.
 *
 * <p><b>The delivery target is a container, never the plaque</b>
 * (D-A2a-5). A warehouse has no beds, so {@code Building.anchor} is the
 * plaque block -- mounted in a wall, with no standable cell beside it and
 * none above it. Routing to the anchor produced exactly MineColonies'
 * "deliveryman never delivers" wedge (#2932): the courier loaded, walked
 * to the outside of the wall, never satisfied the arrival radius, gave up
 * and re-triggered forever with the load stranded in her bag. The courier
 * now walks to a standable cell beside a real chest, and only stows once
 * she is genuinely within reach of it -- so goods cannot be posted through
 * a wall either (see {@link #hasArrived}, which keeps that promise with a
 * reach test rather than requiring literal containment in the building's
 * own recorded bounds -- a strict containment gate could be permanently
 * unsatisfiable when the only standable cell beside the chest lands just
 * outside it). The same standard now applies to a crafter's own chests on
 * the restock route.
 */
public class CourierWorkGoal extends Goal {

    /** Ticks of the SORTING loop; one stack moves per cycle. */
    public static final int SORT_PERIOD = 32;
    /** Tick within the sort cycle at which the stack actually moves. */
    public static final int SORT_MOVE_TICK = 16;
    // How much the courier carries per trip is NOT a constant here: it is
    // SettlerEntity.getCarryCapacity(), the same number the sack is drawn
    // from (D-A2b-1). Two sources for one truth is how the plaque/settlement
    // split went wrong before (D-006), so the goal reads the entity's.
    /** Reach to a warehouse or crafter chest, squared. Two blocks and a bit. */
    private static final double CHEST_REACH_SQR = 6.25;
    /** Reach to the hearth, squared. */
    private static final double HEARTH_REACH_SQR = 6.25;
    /**
     * How long the courier stops trying after a route genuinely fails.
     * Without this, giving up and re-triggering on the next tick is an
     * invisible busy-loop -- the wedge shape itself, not a fix for it.
     */
    public static final int RETRY_COOLDOWN_TICKS = 400;
    /**
     * First rest after a failed leg. A flat 400 was too blunt: one transient
     * path hiccup took a courier off shift for twenty seconds even with a
     * full hearth and an empty warehouse two steps away. The rest now
     * doubles per consecutive failure up to {@link #RETRY_COOLDOWN_TICKS},
     * so a hiccup costs five seconds while a genuinely unreachable warehouse
     * still stops the spin.
     */
    public static final int FIRST_REST_TICKS = 100;
    /**
     * How long between re-paths while walking somewhere. Deliberately short:
     * at 40 ticks a courier whose path was cancelled stood still for two
     * full seconds before trying again, several times per trip, which both
     * looks broken and wastes most of a delivery window. The stuck limits
     * below are scaled so total patience before giving up is unchanged.
     */
    private static final int REPATH_INTERVAL = 15;
    /** Re-paths allowed before a leg is declared unreachable (~300 ticks). */
    private static final int HEARTH_STUCK_LIMIT = 20;
    /** Same for a longer haul -- to a warehouse, a source or a crafter (~480 ticks). */
    private static final int HAUL_STUCK_LIMIT = 32;
    /** How often {@link #findRestockJob} (and, one rung below it on the
     *  same budgeted slot, {@link #findCollectionJob}) is even tried, so an
     *  idle courier is not re-scanning every crafter's chests on every
     *  single tick. */
    private static final int RESTOCK_LOOK_INTERVAL = 40;
    /**
     * COLLECTION: how many of an output item stay behind in the producing
     * building's own chests. Stripping a workshop bare would race its own
     * crafter for its own product -- the mason's STONE is also the input of
     * her stone_bricks recipe, and the smithy's bloom-forged IRON_INGOT is
     * the input of every tool she makes -- so a same-building chain always
     * keeps working out of this buffer, and only the genuine surplus
     * travels. Cross-building chains lose nothing either: the smelter's
     * bloom feeds the smithy THROUGH the warehouse (the restock route only
     * ever reads warehouse stock), so hauling the surplus is precisely what
     * gets that chain fed, and the eight left behind cost it nothing.
     * A {@link BuildingType#MINE} has no Production table and consumes
     * nothing, so its keep-back is zero -- see {@link #keepBackFor}.
     * Public so the GameTest asserts against the same number the route
     * uses.
     */
    public static final int OUTPUT_KEEP_BACK = 8;
    /**
     * FOOD_DELIVERY: meals the hearth should hold per living settler before
     * the larder reads as LOW. A settler eats one item per meal
     * ({@code HearthBlockEntity#extractBestFood} removes exactly one) and
     * sits down to roughly two or three meals across a day (hunger drains
     * 0.04-0.10/s and a meal restores nutrition x 8), so four per head is a
     * full day's eating with margin -- the larder starts refilling while
     * everyone can still eat, never after the shelf is bare.
     */
    public static final int FOOD_PER_SETTLER = 4;
    /**
     * FOOD_DELIVERY: ceiling on the LOW threshold, whatever the population.
     * 24 is {@code HearthBlockEntity#INVENTORY_SIZE} -- one meal per hearth
     * slot -- so past six settlers (6 x {@link #FOOD_PER_SETTLER} = 24) the
     * threshold stops growing: a big village's courier tops the larder up to
     * a bound the hearth can always physically hold (food stacks, so this is
     * comfortably conservative), instead of chasing a target that scales
     * past the furniture.
     */
    public static final int FOOD_STOCK_CAP = 24;
    /**
     * FUEL: how many batches' worth of fuel a burning building keeps on
     * hand. Restock triggers when its chests hold fewer than
     * {@code FUEL_RESERVE_BATCHES x Fuel.perBatch(type)} fuel items -- four
     * batches covers a full courier round trip (claim, walk, withdraw, walk,
     * deposit can span several hundred ticks) with the burner never going
     * cold while the next load is on the road, mirroring how
     * {@link #FOOD_PER_SETTLER} buys the larder a day of margin.
     */
    public static final int FUEL_RESERVE_BATCHES = 4;

    /**
     * The hearth larder's LOW mark: {@link #FOOD_PER_SETTLER} meals for each
     * living settler ({@code Settlement#population()} counts records, and
     * {@code SettlementManager#onSettlerDied} removes a record on death, so
     * the dead stop being catered for), capped at {@link #FOOD_STOCK_CAP}.
     * Public and static so the GameTest computes its expectations from the
     * same arithmetic the route runs on.
     */
    public static int hearthFoodThreshold(int livingSettlers) {
        return Math.min(FOOD_STOCK_CAP, FOOD_PER_SETTLER * livingSettlers);
    }

    /**
     * How long a claimed restock job stays claimed without a heartbeat.
     * Renewed every active tick ({@link #renewReservation}), so a courier
     * genuinely working the job never sees it expire, however long her
     * route takes; this only reclaims a job whose courier stopped ticking
     * altogether (death, a permanent unbind) without ever reaching a normal
     * release point. Sized well past a routine interruption (e.g. a fight)
     * so a brief preemption never opens the double-fetch window this exists
     * to close.
     *
     * <p>Known, accepted gap: this goal does not tick while it is not
     * running, so a load held off-shift (see {@link #canUseCarrying}) or a
     * courier who dies mid-trip stops renewing and the lease lapses after
     * this many ticks even though the job is not really abandoned. The
     * consequence is bounded and never touches item conservation: at worst
     * a second courier restocks the same crafter again, which is a harmless
     * surplus delivery, not a lost or duplicated item. A shorter TTL would
     * recover a genuinely dead courier's job faster at the cost of lapsing
     * during ordinary overnight holds more often; this favours surviving
     * the ordinary case.
     */
    private static final int RESERVATION_TTL_TICKS = 1200;
    // Sound-sync contract (catalogue §0.4 / §5.1-5.4). Each value must agree
    // with the clip comment in SettlerAnimations and tools/anim_check.py.
    public static final int LIFT_GRIP_TICK = 8;
    public static final int HAUL_STEP_PERIOD = 18;
    public static final int HAUL_STRAIN_PERIOD = 96;
    public static final int SET_DOWN_TICK = 6;
    public static final int CRATE_CREAK_PERIOD = 54;
    public static final int CRATE_CREAK_OFFSET = 9;

    private enum Mode {
        TO_HEARTH, LOADING, TO_WAREHOUSE, SORTING, RETURNING,
        TO_SOURCE, WITHDRAWING, TO_CRAFTER, DEPOSITING,
        /** FOOD_DELIVERY's set-down: bag -> hearth, one stack per cycle. */
        STOCKING
    }

    /**
     * The order a courier tries jobs in when nothing is already in her
     * hands. Lower ordinal outranks higher: {@link #findRestockJob} is
     * always tried before {@link #findFoodJob}, that before
     * {@link #findCollectionJob}, and all three before
     * {@link #beginConsolidation}, so a crafter running dry on its own raw
     * material is never left waiting behind a larder top-up, a hungry
     * village is never left waiting behind an output pile that is merely
     * getting taller, and none of them waits behind routine tidying-up of
     * the hearth.
     *
     * <p>This is a decision-time ordering only -- a trip already under way
     * is always finished before a new one is picked (see
     * {@link #canUseCarrying}), the same way the class doc's deadlock
     * argument holds: nothing here ever pre-empts a trip mid-haul.
     */
    private enum JobPriority {
        /**
         * A crafter short of its own raw material -- or, for a building
         * that burns ({@code Fuel#burns}), short of fuel -- when a
         * warehouse is holding some. Outranks consolidation: a smithy
         * standing idle for want of iron sitting fifteen blocks away in a
         * warehouse is a worse look than a warehouse chest one merge short
         * of tidy.
         */
        CRAFTER_RESTOCK,
        /**
         * The hearth larder running LOW (below
         * {@link #hearthFoodThreshold}) with a warehouse holding something
         * edible -- FLOWS.md route 5's hearth half, warehouse -> hearth.
         * The tier that used to sit at the very top of this ladder was a
         * placeholder for the farmer's own harvest delivery (FLOWS route 1),
         * which never runs through this goal at all; now the courier-carried
         * food route is real, it lands HERE instead: above collection and
         * consolidation because a hungry village outranks tidy shelves, but
         * below CRAFTER_RESTOCK because the LOW threshold fires while the
         * village still has a full day's eating in hand ({@code
         * EatFromHearthGoal} works down to the last loaf), whereas a crafter
         * with an empty input chest -- the bakery that BAKES this route's
         * cargo among them -- is stopped dead right now.
         */
        FOOD_DELIVERY,
        /**
         * A producing building's own OUTPUT piled up past
         * {@link #OUTPUT_KEEP_BACK} -- or ANYTHING in a
         * {@link BuildingType#MINE}, which has no Production table and
         * whose chests are pure yield -- with a warehouse that has room for
         * it. FLOWS.md route 4, the return leg of the economy loop. Below
         * restocking on purpose: a crafter out of raw material is stopped
         * dead right now, while an output merely accumulates. Above hearth
         * tidying because a stranded output is stock the restock route
         * cannot see until it reaches a warehouse -- the mason's bricks are
         * repair material and the smelter's ingots are the smithy's iron,
         * but only once they get there.
         */
        OUTPUT_COLLECTION,
        /** Plain hearth -> warehouse consolidation: the original, still-needed job. */
        WAREHOUSE_CONSOLIDATION
    }

    private final SettlerEntity settler;
    private Mode mode;
    private JobPriority job;
    /** The warehouse chest being delivered to (consolidation and
     *  collection both land here) -- never the plaque. */
    private BlockPos dropOff;
    private UUID warehouseId;
    /** RESTOCK/COLLECTION: the chest this trip withdraws from -- a
     *  warehouse chest for a restock, the producing building's own chest
     *  for a collection. */
    private BlockPos sourcePos;
    /** RESTOCK/COLLECTION: which building {@link #sourcePos} belongs to --
     *  also the fallback deposit target if the destination cannot take the
     *  load (a restock load goes back to its warehouse, a collected output
     *  back to the workshop it came out of). */
    private UUID sourceWarehouseId;
    /** RESTOCK only: the crafter building being restocked. */
    private UUID craftBuildingId;
    /** RESTOCK only: chest at the crafter to deposit into. */
    private BlockPos craftDropOff;
    /** RESTOCK/COLLECTION: the exact item this trip is reserved for. */
    private Item reservedItem;
    /** RESTOCK/COLLECTION: the ledger key held for this trip, if any. */
    private RestockKey reservationKey;
    private int workTicks;
    private int setDownThudIn = -1;
    private int repathTimer;
    private int stuckChecks;
    private long cooldownUntil = Long.MIN_VALUE;
    /** Consecutive abandoned routes; reset by any completed delivery. */
    private int consecutiveFailures;
    private int restockCooldown;
    private boolean done;

    public CourierWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ---------------------------------------------------------- decision ---

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.COURIER || !settler.isBound()) {
            return false;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null) {
            return false;
        }
        boolean carrying = bagCount() > 0;
        boolean onShift = settler.dayPhase().work()
            && settler.getEnergy() > 15
            && level.getGameTime() >= cooldownUntil;

        if (carrying) {
            return canUseCarrying(level, s, onShift);
        }
        if (!onShift) {
            return false; // nothing in hand and nothing to do: skip the lookup
        }

        // The priority ladder (JobPriority): try each tier in turn and take
        // the first with real work.
        if (restockCooldown > 0) {
            restockCooldown--;
        } else {
            restockCooldown = RESTOCK_LOOK_INTERVAL;
            RestockJob restock = findRestockJob(level, s);
            if (restock != null) {
                beginRestock(restock);
                return true;
            }
            // Same budgeted slot, next rung down (FOOD_DELIVERY): no
            // crafter is starving, so the larder gets looked at before any
            // shelf-tidying does.
            FoodJob food = findFoodJob(level, s);
            if (food != null) {
                beginFoodDelivery(food);
                return true;
            }
            // Same budgeted slot again, next rung down (OUTPUT_COLLECTION):
            // only when no crafter is starving and the village can eat does
            // the courier go around emptying workshop output shelves into
            // the warehouse.
            CollectionJob collection = findCollectionJob(level, s);
            if (collection != null) {
                beginCollection(collection);
                return true;
            }
        }
        return beginConsolidation(level, s);
    }

    /**
     * A load already in the bag must be finished before anything new is
     * decided, whichever tier put it there: which job it was is remembered
     * in {@code job} across the stop()/start() an interruption causes, so
     * this re-validates today's route rather than picking a fresh one.
     */
    private boolean canUseCarrying(ServerLevel level, Settlement s, boolean onShift) {
        if (job == JobPriority.CRAFTER_RESTOCK) {
            Building crafter = craftBuildingId == null ? null
                : findBuildingById(s, craftBuildingId);
            if (crafter == null || craftDropOff == null) {
                // The crafter this was reserved for is gone: nowhere left to
                // deliver to, so the raw material goes back to the warehouse
                // rather than sitting out of circulation in a bag.
                releaseReservation();
                mode = Mode.RETURNING;
                return true;
            }
            if (!onShift) {
                return false; // hold the load until morning, same as consolidation
            }
            mode = Mode.TO_CRAFTER;
            return true;
        }
        if (job == JobPriority.FOOD_DELIVERY) {
            if (settler.hearth() == null || settler.getHearthPos() == null) {
                // The hearth is gone mid-trip (a razed settlement): the
                // meals go back to the warehouse they came from rather
                // than riding out of circulation in a bag.
                releaseReservation();
                mode = Mode.RETURNING;
                return true;
            }
            if (!onShift) {
                return false; // hold the load until morning, same as the others
            }
            mode = Mode.TO_HEARTH;
            return true;
        }
        if (job == JobPriority.OUTPUT_COLLECTION) {
            Building target = findBuildingById(s, warehouseId);
            if (target == null) {
                target = pickWarehouse(s); // original gone: any warehouse will do
            }
            BlockPos chest = target == null ? null : pickDropOff(level, target);
            if (chest == null) {
                // Every warehouse is gone: the collected output goes back
                // to the workshop it came out of -- never to the hearth,
                // which is read as "goods awaiting their first haul", and
                // never held hostage in the bag.
                releaseReservation();
                mode = Mode.RETURNING;
                return true;
            }
            if (!onShift) {
                return false; // hold the load until morning, same as the others
            }
            warehouseId = target.id;
            dropOff = chest;
            mode = Mode.TO_WAREHOUSE;
            return true;
        }
        Building warehouse = pickWarehouse(s);
        BlockPos target = warehouse == null ? null : pickDropOff(level, warehouse);
        if (target == null) {
            warehouseId = null;
            dropOff = null;
            job = JobPriority.WAREHOUSE_CONSOLIDATION;
            mode = Mode.RETURNING;
            return true;
        }
        if (!onShift) {
            return false; // carrying off-shift: hold the load until morning
        }
        warehouseId = warehouse.id;
        dropOff = target;
        job = JobPriority.WAREHOUSE_CONSOLIDATION;
        mode = Mode.TO_WAREHOUSE;
        return true;
    }

    private boolean beginConsolidation(ServerLevel level, Settlement s) {
        Building warehouse = pickWarehouse(s);
        BlockPos target = warehouse == null ? null : pickDropOff(level, warehouse);
        if (target == null) {
            return false; // idle visibly rather than thrash (MineColonies #2932)
        }
        if (!hearthHasHaulableGoods()) {
            return false;
        }
        warehouseId = warehouse.id;
        dropOff = target;
        job = JobPriority.WAREHOUSE_CONSOLIDATION;
        mode = Mode.TO_HEARTH;
        return true;
    }

    private void beginRestock(RestockJob restock) {
        job = JobPriority.CRAFTER_RESTOCK;
        sourceWarehouseId = restock.warehouse().id;
        sourcePos = restock.sourceChest();
        craftBuildingId = restock.crafter().id;
        craftDropOff = restock.craftChest();
        reservedItem = restock.item();
        reservationKey = restock.key();
        mode = Mode.TO_SOURCE;
    }

    private void beginFoodDelivery(FoodJob food) {
        job = JobPriority.FOOD_DELIVERY;
        sourceWarehouseId = food.warehouse().id;
        sourcePos = food.sourceChest();
        craftBuildingId = null;
        craftDropOff = null;
        warehouseId = null;
        dropOff = null;
        reservedItem = food.item();
        reservationKey = food.key();
        mode = Mode.TO_SOURCE;
    }

    private void beginCollection(CollectionJob collection) {
        job = JobPriority.OUTPUT_COLLECTION;
        sourceWarehouseId = collection.source().id;
        sourcePos = collection.sourceChest();
        craftBuildingId = null;
        craftDropOff = null;
        warehouseId = collection.warehouse().id;
        dropOff = collection.dropChest();
        reservedItem = collection.item();
        reservationKey = collection.key();
        mode = Mode.TO_SOURCE;
    }

    /** Prefers a warehouse this settler is actually employed at (D-A2a-4). */
    private Building pickWarehouse(Settlement s) {
        Building fallback = null;
        for (Building b : s.buildings) {
            if (b.type != BuildingType.WAREHOUSE || !b.valid) {
                continue;
            }
            if (b.workers.contains(settler.getUUID())) {
                return b;
            }
            if (fallback == null) {
                fallback = b;
            }
        }
        return fallback;
    }

    /**
     * The nearest chest or barrel in the warehouse, or {@code null} if it
     * has none yet. Read from the cached index rather than a fresh scan so
     * that calling this every tick stays inside the scan budget.
     */
    private BlockPos pickDropOff(ServerLevel level, Building warehouse) {
        List<BlockPos> containers = WarehouseStorage.of(level, warehouse).containers();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos from = settler.blockPosition();
        for (BlockPos pos : containers) {
            double d = from.distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** Food is off limits; anything else in the hearth is haulable. */
    private static boolean isHaulable(ItemStack stack) {
        return !stack.isEmpty() && !stack.has(DataComponents.FOOD);
    }

    private boolean hearthHasHaulableGoods() {
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            return false;
        }
        var inv = hearth.getInventory();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (isHaulable(inv.getStackInSlot(slot))) {
                return true;
            }
        }
        return false;
    }

    private int bagCount() {
        int n = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            n += settler.bag.getItem(i).getCount();
        }
        return n;
    }

    private Building findBuildingById(Settlement s, UUID id) {
        if (id == null) {
            return null;
        }
        for (Building b : s.buildings) {
            if (b.id.equals(id) && b.valid) {
                return b;
            }
        }
        return null;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && settler.isBound();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        done = false;
        workTicks = 0;
        stuckChecks = 0;
        repathTimer = 0;
        switch (mode) {
            case TO_WAREHOUSE -> {
                settler.setActivity(SettlerActivity.CARRYING);
                pathToChest(dropOff);
            }
            case TO_CRAFTER -> {
                settler.setActivity(SettlerActivity.CARRYING);
                pathToChest(craftDropOff);
            }
            case TO_SOURCE -> {
                settler.setActivity(SettlerActivity.TRAVELING);
                pathToChest(sourcePos);
            }
            case RETURNING -> {
                settler.setActivity(SettlerActivity.CARRYING);
                pathAbove(returnsToSource()
                    ? sourcePos : settler.getHearthPos());
            }
            default -> {
                // TO_HEARTH serves two jobs: consolidation walks there
                // empty-handed to load, a food delivery arrives laden.
                settler.setActivity(bagCount() > 0
                    ? SettlerActivity.CARRYING : SettlerActivity.TRAVELING);
                pathAbove(settler.getHearthPos());
            }
        }
    }

    /** Walks to the cell above a block -- used for the hearth. */
    private void pathAbove(BlockPos pos) {
        if (pos != null) {
            settler.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 1,
                pos.getZ() + 0.5, 0.95);
        }
    }

    /** Walks to a cell a settler can actually stand in, feet at {@code pos}. */
    private void pathToStand(BlockPos pos) {
        settler.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(),
            pos.getZ() + 0.5, 0.95);
    }

    /** Walks toward a standable cell beside any chest leg of any route. */
    private void pathToChest(BlockPos pos) {
        if (pos != null && settler.level() instanceof ServerLevel level) {
            pathToStand(approachTo(level, pos, settler.blockPosition()));
        }
    }

    /**
     * A standable cell beside the chest, preferring the side the courier is
     * already on. A chest itself is never walkable, and the cell above it is
     * only walkable for a barrel, so aiming at the container block leaves the
     * navigator to guess -- and outside a sealed room its guess is the wrong
     * side of the wall. Picking the nearest open side also avoids sending her
     * around a chest that is flush against one.
     */
    private static BlockPos approachTo(ServerLevel level, BlockPos container,
                                       BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = container.relative(dir);
            if (!isStandable(level, side)) {
                continue;
            }
            double d = from.distSqr(side);
            if (d < bestDist) {
                bestDist = d;
                best = side;
            }
        }
        if (best != null) {
            return best;
        }
        if (isStandable(level, container.above())) {
            return container.above();
        }
        return container;
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
            && level.getBlockState(pos.above())
                .getCollisionShape(level, pos.above()).isEmpty()
            && !level.getBlockState(pos.below())
                .getCollisionShape(level, pos.below()).isEmpty();
    }

    // --------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        if (setDownThudIn >= 0 && setDownThudIn-- == 0) {
            playAt(ModSounds.CRATE_DOWN.get(), 0.8F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        // A restock lease is a heartbeat, not a one-shot timer: as long as
        // this goal is actively ticking the job, the lock cannot expire out
        // from under her -- only a courier who stops ticking altogether
        // (death, an interruption that never resumes) lets it lapse.
        renewReservation();
        switch (mode) {
            case TO_HEARTH -> tickToHearth();
            case LOADING -> tickLoading();
            case TO_WAREHOUSE -> tickToWarehouse();
            case SORTING -> tickSorting();
            case RETURNING -> tickReturning();
            case TO_SOURCE -> tickToSource();
            case WITHDRAWING -> tickWithdrawing();
            case TO_CRAFTER -> tickToCrafter();
            case DEPOSITING -> tickDepositing();
            case STOCKING -> tickStocking();
        }
    }

    private void tickToHearth() {
        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null) {
            // A laden food trip re-decides through canUseCarrying, which
            // sends the meals back to their warehouse; an empty-handed
            // consolidation walk just stands down.
            done = true;
            return;
        }
        workTicks++;
        if (bagCount() > 0) {
            playHaulSounds(); // the food leg carries real weight like any haul
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5,
            hearthPos.getY() + 0.6, hearthPos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(hearthPos) <= HEARTH_REACH_SQR) {
            settler.getNavigation().stop();
            if (job == JobPriority.FOOD_DELIVERY) {
                // Laden arrival: the crate comes DOWN here, mirroring
                // tickToWarehouse -- the thud is scheduled so it lands with
                // the clip's contact, not before it.
                settler.triggerCourierSetDown();
                setDownThudIn = SET_DOWN_TICK;
                mode = Mode.STOCKING;
            } else {
                mode = Mode.LOADING;
            }
            workTicks = 0;
            settler.setActivity(SettlerActivity.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > HEARTH_STUCK_LIMIT) {
                giveUp(); // unreachable hearth: rest the route, don't spin
            } else {
                pathAbove(hearthPos);
            }
        }
    }

    /** Lifts one bag-load of non-food goods out of the hearth. */
    private void tickLoading() {
        workTicks++;
        if (workTicks == LIFT_GRIP_TICK) {
            // The grip IS the lift: start COURIER_LIFT here so the clip's
            // own grip beat lands with the sound, not a tick either side.
            settler.triggerCourierLift();
            playAt(ModSounds.CRATE_GRIP.get(), 0.7F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks < SORT_PERIOD / 2) {
            return;
        }
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            done = true;
            return;
        }
        var inv = hearth.getInventory();
        int capacity = settler.getCarryCapacity();
        for (int slot = 0; slot < inv.getSlots() && bagCount() < capacity; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (!isHaulable(stack)) {
                continue;
            }
            int want = Math.min(stack.getCount(), capacity - bagCount());
            // Extract first, then bank it. The extracted stack exists in a
            // local only for the moment between these two lines, and the
            // remainder goes straight back, so no path loses an item.
            ItemStack taken = inv.extractItem(slot, want, false);
            playAt(ModSounds.ITEM_PICKUP.get(), 0.5F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            ItemStack leftover = settler.bag.addItem(taken);
            if (!leftover.isEmpty()) {
                inv.insertItem(slot, leftover, false);
                break;
            }
        }
        if (bagCount() <= 0) {
            done = true;
            return;
        }
        job = JobPriority.WAREHOUSE_CONSOLIDATION;
        mode = Mode.TO_WAREHOUSE;
        workTicks = 0;
        stuckChecks = 0;
        settler.setActivity(SettlerActivity.CARRYING);
        pathToChest(dropOff);
    }

    private void tickToWarehouse() {
        if (dropOff == null || !(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement s = settler.settlement();
        Building warehouse = s == null ? null : findBuildingById(s, warehouseId);
        if (warehouse == null) {
            beginReturn(); // dissolved mid-trip: carry the goods home
            return;
        }
        workTicks++;
        playHaulSounds();
        settler.getLookControl().setLookAt(dropOff.getX() + 0.5,
            dropOff.getY() + 0.6, dropOff.getZ() + 0.5);
        if (hasArrived(warehouse, dropOff)) {
            settler.getNavigation().stop();
            // COURIER_SET_DOWN starts now; its contact is SET_DOWN_TICK
            // ticks in, so the thud is scheduled rather than played here
            // (playing it immediately put the sound before the crate had
            // visibly left the settler's hands).
            settler.triggerCourierSetDown();
            setDownThudIn = SET_DOWN_TICK;
            mode = Mode.SORTING;
            workTicks = 0;
            settler.setActivity(SettlerActivity.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > HAUL_STUCK_LIMIT) {
                // The warehouse cannot be reached from here. Carry the load
                // back to the hearth so the goods stay in circulation, and
                // rest the route so this is not an invisible busy-loop.
                giveUp();
            } else {
                pathToChest(dropOff);
            }
        }
    }

    /**
     * Arrival is a REACH test, not a containment test -- being within reach
     * through a real wall is not arriving, but a courier standing exactly as
     * close to the chest as the world lets her stand always is, whether or
     * not that spot is inside the building's own recorded box.
     *
     * <p>LIVE REGRESSION (coordinator diagnostic, run 20260826T013935Z): a
     * strict {@code bounds.isInside(at)} gate used to be checked FIRST and
     * failed the whole test on its own, before distance was ever looked at.
     * {@link #approachTo} already puts the courier on a standable cell
     * touching the container -- but the navigator resolves that request to
     * the last WALKABLE node next to an obstruction (a chest, a wall), which
     * can land one step short of the requested cell. When the container sits
     * flush on the edge of the building's own bounds (routine for a chest
     * against the near wall of a room), landing one step short lands OUTSIDE
     * the box -- two blocks from the chest, navigation reporting DONE, and a
     * predicate that can never be satisfied however long she waits: 33
     * repaths against a length-1 path going nowhere, twice, on two different
     * legs (TO_CRAFTER and TO_SOURCE), before giving up for good with the
     * goods never delivered. Bounds is now only a cheap fast path for the
     * common case of genuinely being inside; outside it, the SAME reach
     * radius that always gated the fast path is what still keeps this from
     * being satisfied through an actual sealed wall -- {@link #approachTo}
     * never puts her somewhere convenient on the far side of one, so a
     * courier reaching this by the fallback has always gone exactly as far
     * toward the container as she physically could.
     */
    private boolean hasArrived(Building building, BlockPos target) {
        BlockPos at = settler.blockPosition();
        if (building.bounds == null || building.bounds.isInside(at)) {
            return at.distSqr(target) <= CHEST_REACH_SQR;
        }
        return at.distSqr(target) <= CHEST_REACH_SQR
            || distSqrToBounds(at, building.bounds) <= CHEST_REACH_SQR;
    }

    /**
     * Squared distance from a point to the nearest point ON a bounding box
     * -- zero when the point is already inside it. Each axis clamps
     * independently, which is the standard point-to-AABB distance: the gap
     * on an axis the point is already within the box's span on is zero, so
     * only the axes it actually protrudes on contribute.
     */
    private static double distSqrToBounds(BlockPos at, BoundingBox bounds) {
        double dx = Math.max(0, Math.max(bounds.minX() - at.getX(), at.getX() - bounds.maxX()));
        double dy = Math.max(0, Math.max(bounds.minY() - at.getY(), at.getY() - bounds.maxY()));
        double dz = Math.max(0, Math.max(bounds.minZ() - at.getZ(), at.getZ() - bounds.maxZ()));
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Laden footfalls and the occasional strained breath while under load:
     * the load is meant to be audible, not just visible (D-007). Shared by
     * every haul leg -- a restock delivery carries real weight exactly like
     * a consolidation one.
     */
    private void playHaulSounds() {
        if (settler.getDeltaMovement().horizontalDistanceSqr() <= 1.0E-4) {
            return;
        }
        if (workTicks % HAUL_STEP_PERIOD == 0) {
            playAt(ModSounds.HAUL_STEP.get(), 0.6F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks % HAUL_STRAIN_PERIOD == 0) {
            playAt(ModSounds.HAUL_STRAIN.get(), 0.55F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        // Loaded wood flexing. Deliberately offset from the footfall period
        // so the creak never lands on the same tick as a step.
        if (workTicks % CRATE_CREAK_PERIOD == CRATE_CREAK_OFFSET) {
            playAt(ModSounds.CRATE_CREAK.get(), 0.45F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
    }

    /** One stack per cycle into the warehouse chests, moving at tick 16. */
    private void tickSorting() {
        workTicks++;
        if (workTicks % SORT_PERIOD != SORT_MOVE_TICK) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement s = settler.settlement();
        Building warehouse = s == null ? null : findBuildingById(s, warehouseId);
        if (warehouse == null) {
            beginReturn(); // dissolved mid-delivery: take them home
            return;
        }
        WarehouseStorage storage = WarehouseStorage.of(level, warehouse);
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = storage.insert(level, warehouse, stack.copy());
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            // Job standard, point 8: a stack actually filed is one unit of a
            // courier's work, and what it builds is the legs to carry the next.
            settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            settler.bag.setItem(i, leftover);
            if (!leftover.isEmpty()) {
                beginReturn(); // warehouse full: stop, and carry what is left back home
            }
            return; // one stack per cycle -- the animation beat
        }
        consecutiveFailures = 0; // the route works; forget the bad streak
        // A collection trip holds a ledger key exactly like a restock does;
        // consolidation never has one, so this is a no-op for it.
        releaseReservation();
        done = true; // bag empty: delivery complete
    }

    // ------------------------------------------------------ restock legs ---

    private void tickToSource() {
        if (sourcePos == null || !(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        Settlement s = settler.settlement();
        Building source = s == null ? null : findBuildingById(s, sourceWarehouseId);
        if (source == null) {
            // The source building (a warehouse for a restock, the workshop
            // for a collection) dissolved mid-trip. Nothing has been taken
            // yet -- the bag is still empty at this point in the route --
            // so there is nothing to lose; just stand down.
            done = true;
            releaseReservation();
            return;
        }
        settler.getLookControl().setLookAt(sourcePos.getX() + 0.5,
            sourcePos.getY() + 0.6, sourcePos.getZ() + 0.5);
        if (hasArrived(source, sourcePos)) {
            settler.getNavigation().stop();
            mode = Mode.WITHDRAWING;
            workTicks = 0;
            settler.setActivity(SettlerActivity.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > HAUL_STUCK_LIMIT) {
                giveUp();
            } else {
                pathToChest(sourcePos);
            }
        }
    }

    /** Lifts one bag-load of the reserved item out of the source chest. */
    private void tickWithdrawing() {
        workTicks++;
        if (workTicks == LIFT_GRIP_TICK) {
            settler.triggerCourierLift();
            playAt(ModSounds.CRATE_GRIP.get(), 0.7F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks < SORT_PERIOD / 2) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        if (!(level.getBlockEntity(sourcePos) instanceof Container container)) {
            // The chest is gone by the time we arrived -- nothing has been
            // taken yet, so nothing is lost. Stand down rather than assume.
            done = true;
            releaseReservation();
            return;
        }
        if (job == JobPriority.OUTPUT_COLLECTION) {
            withdrawCollectedSurplus(level, container);
        } else if (job == JobPriority.FOOD_DELIVERY) {
            withdrawFoodForHearth(container);
        } else {
            int capacity = settler.getCarryCapacity();
            for (int slot = 0; slot < container.getContainerSize() && bagCount() < capacity; slot++) {
                ItemStack stack = container.getItem(slot);
                // Reserved by exact ITEM, not by the recipe's whole ingredient:
                // this exact item was confirmed sitting in this exact chest when
                // the job was claimed, so that is what gets fetched -- not just
                // anything else the recipe's tag would also accept.
                if (stack.isEmpty() || !stack.is(reservedItem)) {
                    continue;
                }
                int want = Math.min(stack.getCount(), capacity - bagCount());
                // Destination-first (D-A2a-3): remove from the real chest, bank
                // into the bag, and give back whatever the bag would not take,
                // so an interruption between these lines still leaves the item
                // somewhere real.
                ItemStack removed = container.removeItem(slot, want);
                if (removed.isEmpty()) {
                    continue;
                }
                ItemStack leftover = settler.bag.addItem(removed);
                giveBackToChest(container, slot, leftover);
                playAt(ModSounds.ITEM_PICKUP.get(), 0.5F,
                    0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        if (bagCount() <= 0) {
            // Reserved, but empty-handed on arrival -- a player took it by
            // hand between the reservation and now, or rearranged the
            // chest (or, for a collection, the surplus fell back under the
            // keep-back). Chest truth is re-read here, never assumed from
            // reservation time; nothing was picked up, so nothing is lost.
            done = true;
            releaseReservation();
            return;
        }
        workTicks = 0;
        stuckChecks = 0;
        settler.setActivity(SettlerActivity.CARRYING);
        if (job == JobPriority.OUTPUT_COLLECTION) {
            mode = Mode.TO_WAREHOUSE;
            pathToChest(dropOff);
        } else if (job == JobPriority.FOOD_DELIVERY) {
            mode = Mode.TO_HEARTH;
            pathAbove(settler.getHearthPos());
        } else {
            mode = Mode.TO_CRAFTER;
            pathToChest(craftDropOff);
        }
    }

    /**
     * COLLECTION's half of the withdrawal: lifts the reserved output item
     * out of the workshop's chest, but only down to the keep-back
     * ({@link #OUTPUT_KEEP_BACK}; zero for a {@link BuildingType#MINE}),
     * counted across the WHOLE building's chests and re-read now -- the
     * count the job was claimed on is however many ticks old, and chest
     * truth means the only number allowed to authorise a removal is one
     * read in the same tick as the removal.
     */
    private void withdrawCollectedSurplus(ServerLevel level, Container container) {
        Settlement s = settler.settlement();
        Building source = s == null ? null : findBuildingById(s, sourceWarehouseId);
        if (source == null) {
            return; // building dissolved: take nothing; the empty-bag path stands down
        }
        int total = countItemIn(liveContainers(level, source), reservedItem);
        int surplus = total - keepBackFor(source.type, reservedItem);
        int want = Math.min(surplus, settler.getCarryCapacity() - bagCount());
        for (int slot = 0; slot < container.getContainerSize() && want > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !stack.is(reservedItem)) {
                continue;
            }
            int take = Math.min(stack.getCount(), want);
            // Destination-first (D-A2a-3), exactly like the restock loop:
            // out of the real chest, into the bag, remainder straight back.
            ItemStack removed = container.removeItem(slot, take);
            if (removed.isEmpty()) {
                continue;
            }
            int got = removed.getCount();
            ItemStack leftover = settler.bag.addItem(removed);
            giveBackToChest(container, slot, leftover);
            want -= got - leftover.getCount();
            playAt(ModSounds.ITEM_PICKUP.get(), 0.5F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
    }

    /**
     * FOOD_DELIVERY's half of the withdrawal: lifts the reserved edible
     * item out of the warehouse chest, but only up to the larder's live
     * deficit -- {@link #hearthFoodThreshold} minus what the hearth holds
     * RIGHT NOW, re-read this tick exactly the way
     * {@link #withdrawCollectedSurplus} re-reads its surplus, because the
     * stock the job was claimed on is however many ticks old. Capping at
     * the deficit rather than a full bag means the route never drains a
     * warehouse of food the village does not yet need -- a warehouse
     * potato is also the kitchen's raw material, and the restock route
     * should not find its cargo pre-emptively carried off to the hearth.
     */
    private void withdrawFoodForHearth(Container container) {
        HearthBlockEntity hearth = settler.hearth();
        Settlement s = settler.settlement();
        if (hearth == null || s == null) {
            return; // razed while walking: take nothing; the empty-bag path stands down
        }
        int deficit = hearthFoodThreshold(s.population()) - hearth.countFoodUnits();
        int want = Math.min(deficit, settler.getCarryCapacity() - bagCount());
        for (int slot = 0; slot < container.getContainerSize() && want > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !stack.is(reservedItem)) {
                continue;
            }
            int take = Math.min(stack.getCount(), want);
            // Destination-first (D-A2a-3), exactly like the other two
            // withdrawal loops: out of the real chest, into the bag,
            // remainder straight back to the very slot it came from.
            ItemStack removed = container.removeItem(slot, take);
            if (removed.isEmpty()) {
                continue;
            }
            int got = removed.getCount();
            ItemStack leftover = settler.bag.addItem(removed);
            giveBackToChest(container, slot, leftover);
            want -= got - leftover.getCount();
            playAt(ModSounds.ITEM_PICKUP.get(), 0.5F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
    }

    /**
     * Puts back what the bag would not take, merging onto whatever this
     * exact slot still holds rather than overwriting it. {@code want} can be
     * less than the whole stack (the bag's own capacity was the limit, not
     * the chest's), which leaves a remainder in the slot that a bare
     * {@code setItem} would silently discard -- FIX: this exists so a
     * courier topping off mid-stack never quietly drops the rest of it.
     */
    private static void giveBackToChest(Container container, int slot, ItemStack leftover) {
        if (leftover.isEmpty()) {
            return;
        }
        ItemStack remaining = container.getItem(slot);
        if (remaining.isEmpty()) {
            container.setItem(slot, leftover);
        } else {
            remaining.grow(leftover.getCount());
        }
        container.setChanged();
    }

    private void tickToCrafter() {
        if (craftDropOff == null || !(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        Settlement s = settler.settlement();
        Building crafter = s == null ? null : findBuildingById(s, craftBuildingId);
        if (crafter == null) {
            // Dissolved mid-trip: the raw material goes back to the
            // warehouse it came from, not into a building that no longer
            // exists.
            beginReturn();
            return;
        }
        workTicks++;
        playHaulSounds();
        settler.getLookControl().setLookAt(craftDropOff.getX() + 0.5,
            craftDropOff.getY() + 0.6, craftDropOff.getZ() + 0.5);
        if (hasArrived(crafter, craftDropOff)) {
            settler.getNavigation().stop();
            settler.triggerCourierSetDown();
            setDownThudIn = SET_DOWN_TICK;
            mode = Mode.DEPOSITING;
            workTicks = 0;
            settler.setActivity(SettlerActivity.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > HAUL_STUCK_LIMIT) {
                giveUp();
            } else {
                pathToChest(craftDropOff);
            }
        }
    }

    private void tickDepositing() {
        workTicks++;
        if (workTicks % SORT_PERIOD != SORT_MOVE_TICK) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        Settlement s = settler.settlement();
        Building crafter = s == null ? null : findBuildingById(s, craftBuildingId);
        if (crafter == null) {
            beginReturn();
            return;
        }
        // Reuses the warehouse's own destination-first insert:
        // WarehouseIndex is not actually warehouse-specific -- a building's
        // containers are just its bounds' chests -- so the same conserving
        // transfer that keeps consolidation honest keeps a restock delivery
        // honest too, rather than a second insert implementation to trust.
        WarehouseStorage storage = WarehouseStorage.of(level, crafter);
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = storage.insert(level, crafter, stack.copy());
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            settler.bag.setItem(i, leftover);
            if (!leftover.isEmpty()) {
                // Crafter's chests filled mid-delivery: the rest goes back
                // to the warehouse, not into the void.
                beginReturn();
            }
            return;
        }
        // Delivered: a later courier's scan sees the crafter's real, now
        // lower need, so holding the lease any further protects nothing.
        releaseReservation();
        done = true;
    }

    /**
     * FOOD_DELIVERY's set-down: one stack per cycle out of the bag into the
     * hearth's communal larder, through the same
     * {@code HearthBlockEntity#insertGoods} every other hearth deposit uses
     * (the true leftover comes back, so nothing is dropped or voided). A
     * leftover means the hearth is genuinely full -- the rest returns to
     * the warehouse it came from, exactly like a full crafter chest on the
     * restock route.
     */
    private void tickStocking() {
        workTicks++;
        if (workTicks % SORT_PERIOD != SORT_MOVE_TICK) {
            return;
        }
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            beginReturn(); // razed mid-delivery: the meals go back to the warehouse
            return;
        }
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = hearth.insertGoods(stack.copy());
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            settler.bag.setItem(i, leftover);
            if (!leftover.isEmpty()) {
                beginReturn(); // hearth full: the rest goes back, never the floor
            }
            return; // one stack per cycle -- the animation beat
        }
        consecutiveFailures = 0; // the route works; forget the bad streak
        // Delivered: EatFromHearthGoal and Settlement.foodCache read the
        // hearth directly, so the village can eat the moment this lands.
        releaseReservation();
        done = true;
    }

    // ---------------------------------------------------------- failure ---

    /**
     * Carries an undeliverable load back to where it came from and puts it
     * down. Where "back" means depends on the job: a consolidation load
     * goes to the hearth it was lifted from; a restock load goes to the
     * warehouse it was lifted from and a collected output back to the
     * workshop it was lifted from, never to the hearth -- putting either
     * there would misplace it, since the hearth is read as "goods awaiting
     * their first haul", not general storage.
     *
     * <p>The failure mode this exists to prevent is the quiet one: a
     * courier wandering off with the settlement's goods locked in her bag.
     */
    private void tickReturning() {
        if (bagCount() <= 0) {
            done = true;
            return;
        }
        boolean backToSource = returnsToSource();
        BlockPos returnPos = backToSource ? sourcePos : settler.getHearthPos();
        boolean nowhereToGo = returnPos == null
            || (!backToSource && settler.hearth() == null);
        if (nowhereToGo) {
            // No hearth (or, for a chest-sourced load, no source position
            // at all) -- a razed settlement, which raids are meant to be
            // able to cause. Keep the load and rest the route: without the
            // cooldown, canUse() re-selects RETURNING on the very next tick
            // and this becomes a one-tick busy loop.
            restRoute();
            done = true;
            return;
        }
        double reachSqr = backToSource ? CHEST_REACH_SQR : HEARTH_REACH_SQR;
        settler.getLookControl().setLookAt(returnPos.getX() + 0.5,
            returnPos.getY() + 0.6, returnPos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(returnPos) <= reachSqr) {
            settler.getNavigation().stop();
            depositReturnedLoad(backToSource);
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            if (bagCount() > 0) {
                // FIX: even the fallback container can be full (or gone) --
                // e.g. the warehouse this restock trip came from is now
                // packed too. Without this, the original consolidation path
                // had no rest here at all, so a full hearth and a full
                // warehouse would ping-pong a courier between them every
                // shift with no cooldown -- the same busy loop
                // RETRY_COOLDOWN exists to prevent on every other leg.
                restRoute();
            }
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > HAUL_STUCK_LIMIT) {
                restRoute();
                done = true;
            } else {
                pathAbove(returnPos);
            }
        }
    }

    private void depositReturnedLoad(boolean backToSource) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (backToSource) {
                Settlement s = settler.settlement();
                // The building sourcePos belongs to: a warehouse for a
                // restock load, the workshop itself for a collected output.
                // WarehouseStorage.insert works on any building's chests
                // (see tickDepositing), so both return legs share it.
                Building source = s == null ? null
                    : findBuildingById(s, sourceWarehouseId);
                if (source == null) {
                    continue; // nothing to insert into: keep it in the bag
                }
                settler.bag.setItem(i,
                    WarehouseStorage.of(level, source).insert(level, source, stack.copy()));
            } else {
                HearthBlockEntity hearth = settler.hearth();
                if (hearth == null) {
                    continue;
                }
                settler.bag.setItem(i, hearth.insertGoods(stack.copy()));
            }
        }
    }

    /**
     * Sends whatever is left in the bag back to where it can safely rest,
     * rather than pressing on toward a destination that just proved
     * unreachable, full, or gone.
     */
    private void beginReturn() {
        if (returnsToSource()) {
            // The trip is decided either way from here -- a second
            // courier's scan will see the chests' real, current state, so
            // holding the lease any further protects nothing.
            releaseReservation();
        }
        mode = Mode.RETURNING;
        stuckChecks = 0;
        repathTimer = 0;
        settler.setActivity(SettlerActivity.CARRYING);
        pathAbove(returnsToSource() ? sourcePos : settler.getHearthPos());
    }

    /** Whether an undeliverable load goes back to {@link #sourcePos} rather
     *  than the hearth: every chest-sourced route returns to its source --
     *  a food load included, since its destination IS the hearth, so "back
     *  to the hearth" would be pressing on toward the very place that just
     *  proved full, unreachable or gone. */
    private boolean returnsToSource() {
        return job == JobPriority.CRAFTER_RESTOCK
            || job == JobPriority.OUTPUT_COLLECTION
            || job == JobPriority.FOOD_DELIVERY;
    }

    /**
     * A route failed for real. Rest it for {@link #RETRY_COOLDOWN_TICKS} so
     * the courier does something else instead of re-entering this goal on
     * the very next tick, and bring any load home first.
     */
    private void giveUp() {
        restRoute();
        if (bagCount() > 0) {
            beginReturn();
        } else {
            done = true;
            releaseReservation();
        }
    }

    /**
     * Stops this goal being re-selectable for {@link #RETRY_COOLDOWN_TICKS}.
     * Every path that abandons a trip goes through here: a goal that ends and
     * immediately qualifies again is the wedge shape from KF-013, not a fix
     * for it.
     */
    private void restRoute() {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 8);
        int rest = Math.min(RETRY_COOLDOWN_TICKS,
            FIRST_REST_TICKS * (1 << (consecutiveFailures - 1)));
        cooldownUntil = settler.level().getGameTime() + rest;
        // A courier that quietly stops working is indistinguishable from one
        // that has nothing to do. Say which leg failed (KF-014).
        settler.recordRouteFailure("courier:" + mode + ":stuck" + stuckChecks
            + ":rest" + rest + ":run" + consecutiveFailures);
    }

    private void playAt(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        settler.level().playSound(null, settler.getX(), settler.getY(), settler.getZ(),
            sound, net.minecraft.sounds.SoundSource.NEUTRAL, volume, pitch);
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        // Deliberately does NOT release a restock reservation here. This
        // goal can be stopped by a higher-priority interruption (e.g.
        // combat) and resumed on the very next opportunity with the SAME
        // job still in these fields (mode/job/sourcePos/... all survive
        // stop()/start()) -- an early release here would open exactly the
        // double-fetch window a second courier's scan is meant to be locked
        // out of. The lease (RESERVATION_TTL_TICKS, renewed every active
        // tick) is what actually reclaims a job whose courier never comes
        // back -- the same reasoning {@code SettlerEntity#die} bag-drop
        // already applies at the entity level: a courier who dies mid-
        // restock leaves her reservation to expire on the lease rather than
        // being released from here, since die() does not run this goal's
        // stop() either.
    }

    // ------------------------------------------------------ restock scan ---

    /**
     * The first crafter building genuinely short of a raw material one of
     * the settlement's warehouses is holding, with nobody else already
     * fetching it for that crafter -- or, on the same tier, the first
     * burning building ({@code Fuel#burns}) short of fuel
     * ({@link #FUEL_RESERVE_BATCHES}).
     *
     * <p>"First", not "best" -- the same tradeoff {@code
     * TidyWarehouseGoal#findMerge} makes and for the same reason: a courier
     * doing her rounds is not solving an assignment problem.
     *
     * <p>Reserves the job it returns before returning it, in the SAME
     * synchronous call. Minecraft's server tick is single-threaded, so
     * nothing else can observe the gap between "found it" and "claimed it"
     * the way two real threads racing on a lock could -- the ledger exists
     * to stop a SECOND courier's {@code canUse()}, evaluated later in the
     * same or a following tick, from picking the identical job, not to
     * guard against real concurrency. FIX: this closes the double-fetch
     * race the task called out -- two couriers independently deciding, in
     * the same tick before either has moved, to fetch the same scarce stock
     * for the same crafter.
     *
     * <p>Bounded like every other world read here: each building's own
     * chests and each warehouse's chests are read through
     * {@link WarehouseIndex}, which caps at
     * {@link WarehouseIndex#MAX_CONTAINERS}, and the outer loops are over
     * the settlement's own building list -- there is no per-tick cost that
     * grows with how much the settlement owns, only with how many buildings
     * it has, and this whole method is itself only tried once every
     * {@link #RESTOCK_LOOK_INTERVAL} ticks per idle courier.
     */
    private RestockJob findRestockJob(ServerLevel level, Settlement s) {
        long now = level.getGameTime();
        for (Building crafter : s.buildings) {
            boolean burns = Fuel.burns(crafter.type);
            if (!crafter.valid || (!Production.produces(crafter.type) && !burns)) {
                continue;
            }
            List<Held> mine = liveContainers(level, crafter);
            if (mine.isEmpty()) {
                continue;
            }
            if (Production.produces(crafter.type)) {
                for (Production.Recipe recipe : Production.of(crafter.type)) {
                    Ingredient want = recipe.input();
                    if (countMatching(mine, want) >= recipe.inputCount()
                        || !roomFor(mine, want)) {
                        continue; // not short, or nowhere to put more even if fetched
                    }
                    RestockJob claimed = claimRestockFrom(level, s, crafter, mine, want, now);
                    if (claimed != null) {
                        return claimed;
                    }
                }
            }
            // FUEL, same tier: a building that burns ({@code Fuel#burns})
            // holding fewer than FUEL_RESERVE_BATCHES x Fuel.perBatch(type)
            // fuel items is as stopped as one out of raw material -- the
            // raw material is merely checked first to keep the existing
            // scan order stable, not because it outranks the firebox. The
            // trip this claims is an ordinary restock in every other way:
            // same ledger key, same legs, same conservation discipline.
            if (burns
                && countMatching(mine, Fuel::isFuel)
                    < FUEL_RESERVE_BATCHES * Fuel.perBatch(crafter.type)
                && roomFor(mine, Fuel::isFuel)) {
                RestockJob claimed = claimRestockFrom(level, s, crafter, mine,
                    Fuel::isFuel, now);
                if (claimed != null) {
                    return claimed;
                }
            }
        }
        return null;
    }

    /**
     * The warehouse half of a restock claim, shared by the raw-material and
     * fuel checks above: the first warehouse holding a stack {@code want}
     * accepts, reserved before it is returned -- by the exact item found,
     * in the same synchronous call, per the reservation reasoning on
     * {@link #findRestockJob}.
     */
    private RestockJob claimRestockFrom(ServerLevel level, Settlement s, Building crafter,
                                        List<Held> mine, Predicate<ItemStack> want,
                                        long now) {
        for (Building warehouse : s.buildings) {
            if (warehouse.type != BuildingType.WAREHOUSE || !warehouse.valid) {
                continue;
            }
            List<Held> theirs = liveContainers(level, warehouse);
            Held stock = findStock(theirs, want);
            if (stock == null) {
                continue;
            }
            Item item = matchingItem(stock.container(), want);
            if (item == null) {
                continue;
            }
            RestockKey key = new RestockKey(crafter.id, item);
            if (isReservedByOther(key, now)) {
                continue; // another courier already has this job
            }
            if (!reserve(key, now)) {
                continue; // lost a same-tick race to another courier's claim
            }
            return new RestockJob(crafter, mine.get(0).pos(),
                warehouse, stock.pos(), item, key);
        }
        return null;
    }

    // --------------------------------------------------------- food scan ---

    /**
     * FOOD_DELIVERY (FLOWS.md route 5): when the hearth's larder is LOW --
     * fewer edible items than {@link #hearthFoodThreshold} for the living
     * population -- the first warehouse holding anything edible supplies a
     * hearth-bound top-up trip. The larder is measured with the hearth's
     * own {@code countFoodUnits()}, the exact number
     * {@code EatFromHearthGoal} decides against and
     * {@code Settlement.foodCache} republishes, so the courier and the
     * eaters can never disagree about what "low" means.
     *
     * <p>"First", not "best", reserved-before-returned in the same
     * synchronous call, and bounded exactly like the other two scans: the
     * same settlement building-list walk, the same
     * {@link WarehouseIndex#MAX_CONTAINERS} cap on every chest read, and
     * only ever tried in the same {@link #RESTOCK_LOOK_INTERVAL} slot,
     * after restock found nothing. The ledger key borrows the settlement's
     * own id for its building half -- the hearth is not a {@link Building},
     * and one settlement has one hearth, so (settlement, item) locks a food
     * item's hearth run exactly the way (building, item) locks every other
     * route. Two couriers can still claim DIFFERENT edible items for the
     * same low larder; both deliver, and the worst case is a larder a few
     * meals above the threshold -- a harmless surplus, the same accepted
     * shape as a double restock after a lapsed lease.
     */
    private FoodJob findFoodJob(ServerLevel level, Settlement s) {
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            return null;
        }
        if (hearth.countFoodUnits() >= hearthFoodThreshold(s.population())) {
            return null; // the larder is stocked; nothing to do
        }
        long now = level.getGameTime();
        for (Building warehouse : s.buildings) {
            if (warehouse.type != BuildingType.WAREHOUSE || !warehouse.valid) {
                continue;
            }
            List<Held> theirs = liveContainers(level, warehouse);
            Held stock = findStock(theirs, CourierWorkGoal::isEdible);
            if (stock == null) {
                continue;
            }
            Item item = matchingItem(stock.container(), CourierWorkGoal::isEdible);
            if (item == null) {
                continue;
            }
            if (!hearthHasRoomFor(hearth, item)) {
                // A hearth crammed full of goods awaiting their first haul:
                // a load lifted now would only bounce straight back to the
                // warehouse -- the ping-pong shape RETRY_COOLDOWN exists to
                // prevent. Consolidation is the route that makes this room.
                return null;
            }
            RestockKey key = new RestockKey(s.id, item);
            if (isReservedByOther(key, now)) {
                continue; // another courier is already feeding the hearth this
            }
            if (!reserve(key, now)) {
                continue; // lost a same-tick race to another courier's claim
            }
            return new FoodJob(warehouse, stock.pos(), item, key);
        }
        return null;
    }

    /**
     * What counts as a meal: the SAME check the hearth larder itself
     * applies. {@code HearthBlockEntity#countFoodUnits()} and
     * {@code HearthBlockEntity#extractBestFood()} -- the pair
     * {@code EatFromHearthGoal} eats through -- both test
     * {@code stack.getFoodProperties(null) != null}, inline in their own
     * loops rather than as a callable predicate, so it is duplicated here
     * with {@link HearthBlockEntity} as the source of truth: if the
     * larder's idea of "edible" ever changes, change this with it.
     * (Deliberately NOT {@code has(DataComponents.FOOD)}, which
     * {@link #isHaulable} uses for its coarser keep-food-home purpose:
     * {@code getFoodProperties} also honours NeoForge's item-extension
     * overrides, and what matters here is delivering exactly what the
     * eater can actually eat.)
     */
    private static boolean isEdible(ItemStack stack) {
        return !stack.isEmpty() && stack.getFoodProperties(null) != null;
    }

    /** Whether the hearth could accept at least one more of this item --
     *  an empty slot, or a part-stack of the same item. The claim-time
     *  twin of {@link #roomFor}, against the hearth's item handler. */
    private static boolean hearthHasRoomFor(HearthBlockEntity hearth, Item item) {
        var inv = hearth.getInventory();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack held = inv.getStackInSlot(slot);
            if (held.isEmpty()
                || (held.is(item) && held.getCount() < held.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------- collection scan ---

    /**
     * COLLECTION (FLOWS.md route 4): the first producing building sitting
     * on more of one of its own OUTPUT items than its keep-back
     * ({@link #OUTPUT_KEEP_BACK}; a {@link BuildingType#MINE} has no
     * Production table at all -- its chests are pure yield, so EVERYTHING
     * there is surplus with a keep-back of zero), paired with the first
     * warehouse with room for that item. No warehouse, or no room in any:
     * no job -- this route exists to un-strand goods, and a load lifted
     * with nowhere to put it down would only re-strand them in a bag
     * (nothing is ever dropped, and nothing is ever voided).
     *
     * <p>Only OUTPUTS move. An item sitting in a workshop's chest because
     * it is that building's raw material (raw iron in the smelter) is
     * exactly what the restock route just delivered; hauling it back out
     * would be a courier carousel. Matching against the building's own
     * recipe outputs -- never "anything not currently needed" -- is what
     * keeps the two routes out of each other's cargo, and the shared
     * reservation key (same building id, same item) locks even the
     * transient tug-of-war out: one building's one item is one courier's
     * business at a time, whichever direction it is moving.
     *
     * <p>"First", not "best", reserved-before-returned in the same
     * synchronous call, and bounded exactly like {@link #findRestockJob}:
     * the same settlement building-list walks, the same
     * {@link WarehouseIndex#MAX_CONTAINERS} cap on every chest read, and
     * only ever tried in the same {@link #RESTOCK_LOOK_INTERVAL} slot,
     * after restock found nothing.
     */
    private CollectionJob findCollectionJob(ServerLevel level, Settlement s) {
        long now = level.getGameTime();
        for (Building source : s.buildings) {
            if (!source.valid || source.type == BuildingType.WAREHOUSE) {
                continue; // a warehouse is this route's destination, never its source
            }
            boolean mine = source.type == BuildingType.MINE;
            if (!mine && !Production.produces(source.type)) {
                continue; // makes nothing: it has no "output" to collect
            }
            List<Held> theirs = liveContainers(level, source);
            if (theirs.isEmpty()) {
                continue;
            }
            Item item = findSurplusOutput(theirs, source.type);
            if (item == null) {
                continue;
            }
            RestockKey key = new RestockKey(source.id, item);
            if (isReservedByOther(key, now)) {
                continue; // another courier is already emptying this shelf
            }
            Held stock = findStock(theirs, Ingredient.of(item));
            if (stock == null) {
                continue;
            }
            for (Building warehouse : s.buildings) {
                if (warehouse.type != BuildingType.WAREHOUSE || !warehouse.valid) {
                    continue;
                }
                List<Held> store = liveContainers(level, warehouse);
                if (store.isEmpty() || !roomFor(store, Ingredient.of(item))) {
                    continue; // this one cannot take it; maybe another can
                }
                if (!reserve(key, now)) {
                    break; // lost a same-tick race; leave this building alone
                }
                BlockPos drop = pickDropOff(level, warehouse);
                if (drop == null) {
                    drop = store.get(0).pos(); // cache momentarily behind the live read
                }
                return new CollectionJob(source, stock.pos(), warehouse, drop, item, key);
            }
        }
        return null;
    }

    /**
     * The first item this building's chests hold beyond its keep-back that
     * the building itself PRODUCES -- or, for a mine, holds at all. Inputs
     * never match: they are the restock route's cargo, not this one's. An
     * item that is both (the mason's STONE, the smithy's IRON_INGOT) does
     * match, and the keep-back is what lets its own chain keep running.
     */
    private static Item findSurplusOutput(List<Held> containers, BuildingType type) {
        if (type == BuildingType.MINE) {
            for (Held h : containers) {
                Container c = h.container();
                for (int slot = 0; slot < c.getContainerSize(); slot++) {
                    ItemStack stack = c.getItem(slot);
                    if (!stack.isEmpty()) {
                        return stack.getItem();
                    }
                }
            }
            return null;
        }
        for (Production.Recipe recipe : Production.of(type)) {
            if (countItemIn(containers, recipe.output()) > keepBackFor(type, recipe.output())) {
                return recipe.output();
            }
        }
        return null;
    }

    /** COLLECTION keep-back per building kind and item: a mine's chests are
     *  pure yield; a workshop keeps a working buffer of its own product
     *  (see {@link #OUTPUT_KEEP_BACK} for why) -- and a BURNING building
     *  keeps at least its whole fuel reserve of any output that doubles as
     *  fuel (the smelter chars logs into charcoal, and charcoal feeds its
     *  own firebox). Without that floor the collection route and the fuel
     *  restock would carousel the same stacks: one hauling firewood in to
     *  reach {@link #FUEL_RESERVE_BATCHES} x perBatch, the other hauling
     *  the "surplus" above {@link #OUTPUT_KEEP_BACK} straight back out. */
    private static int keepBackFor(BuildingType type, Item item) {
        if (type == BuildingType.MINE) {
            return 0;
        }
        int keep = OUTPUT_KEEP_BACK;
        if (Fuel.burns(type) && Fuel.isFuel(new ItemStack(item))) {
            keep = Math.max(keep, FUEL_RESERVE_BATCHES * Fuel.perBatch(type));
        }
        return keep;
    }

    /** Like {@link #countMatching}, by exact item rather than ingredient. */
    private static int countItemIn(List<Held> containers, Item item) {
        int total = 0;
        for (Held h : containers) {
            Container c = h.container();
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack stack = c.getItem(slot);
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private record Held(BlockPos pos, Container container) {
    }

    private record RestockJob(Building crafter, BlockPos craftChest, Building warehouse,
                              BlockPos sourceChest, Item item, RestockKey key) {
    }

    private record CollectionJob(Building source, BlockPos sourceChest, Building warehouse,
                                 BlockPos dropChest, Item item, RestockKey key) {
    }

    /** FOOD_DELIVERY: no destination chest field -- the destination is
     *  always the settlement's own hearth, read live at delivery time. */
    private record FoodJob(Building warehouse, BlockPos sourceChest, Item item,
                           RestockKey key) {
    }

    private static List<Held> liveContainers(ServerLevel level, Building building) {
        List<Held> found = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            if (level.getBlockEntity(pos) instanceof Container c) {
                found.add(new Held(pos, c));
            }
        }
        return found;
    }

    // The matchers below take a bare Predicate rather than an Ingredient:
    // an Ingredient IS one (it implements Predicate<ItemStack>), so every
    // recipe call site passes through unchanged, and the fuel and food
    // checks hand in {@code Fuel::isFuel} / {@link #isEdible} without
    // inventing a fake Ingredient for something that is not a recipe input.

    private static int countMatching(List<Held> containers, Predicate<ItemStack> want) {
        int total = 0;
        for (Held h : containers) {
            Container c = h.container();
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack stack = c.getItem(slot);
                if (!stack.isEmpty() && want.test(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static boolean roomFor(List<Held> containers, Predicate<ItemStack> want) {
        for (Held h : containers) {
            Container c = h.container();
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack stack = c.getItem(slot);
                if (stack.isEmpty()) {
                    return true;
                }
                if (want.test(stack) && stack.getCount() < stack.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Held findStock(List<Held> containers, Predicate<ItemStack> want) {
        for (Held h : containers) {
            Container c = h.container();
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack stack = c.getItem(slot);
                if (!stack.isEmpty() && want.test(stack)) {
                    return h;
                }
            }
        }
        return null;
    }

    private static Item matchingItem(Container container, Predicate<ItemStack> want) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && want.test(stack)) {
                return stack.getItem();
            }
        }
        return null;
    }

    // ------------------------------------------------------- reservation ---

    /** Which building, and which exact item, a trip is claimed for. For a
     *  restock the id is the DESTINATION crafter's; for a collection it is
     *  the SOURCE workshop's; for a food delivery it is the SETTLEMENT's
     *  own id, standing in for the hearth (which is not a Building). One
     *  ledger for all of them means one place's one item is one courier's
     *  business at a time -- two routes cannot even transiently drag the
     *  same item through the same door in both directions. */
    private record RestockKey(UUID crafterId, Item item) {
    }

    private record Reservation(UUID courier, long expiresAtTick) {
    }

    /**
     * Every claimed restock job in the game, across every courier and every
     * settlement. Static like {@code WarehouseStorage#CACHE}: this is intent
     * ("who is handling this"), never a second copy of chest contents, so it
     * carries no per-world lifecycle of its own -- a stale entry for a
     * settlement that no longer exists just sits unreferenced until its
     * lease lapses.
     */
    private static final Map<RestockKey, Reservation> RESERVATIONS = new HashMap<>();

    private boolean isReservedByOther(RestockKey key, long now) {
        Reservation held = RESERVATIONS.get(key);
        return held != null && held.expiresAtTick() > now
            && !held.courier().equals(settler.getUUID());
    }

    private boolean reserve(RestockKey key, long now) {
        Reservation held = RESERVATIONS.get(key);
        if (held != null && held.expiresAtTick() > now
            && !held.courier().equals(settler.getUUID())) {
            return false;
        }
        RESERVATIONS.put(key, new Reservation(settler.getUUID(), now + RESERVATION_TTL_TICKS));
        return true;
    }

    private void renewReservation() {
        if (reservationKey == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        RESERVATIONS.put(reservationKey,
            new Reservation(settler.getUUID(), level.getGameTime() + RESERVATION_TTL_TICKS));
    }

    private void releaseReservation() {
        if (reservationKey == null) {
            return;
        }
        Reservation held = RESERVATIONS.get(reservationKey);
        if (held != null && held.courier().equals(settler.getUUID())) {
            RESERVATIONS.remove(reservationKey);
        }
        reservationKey = null;
    }

    /**
     * Test-only window into the ledger. A GameTest cannot otherwise tell
     * "the second courier was locked out" from "the second courier just
     * found an already-emptied chest" -- with a single stock stack fully
     * consumable by one trip, chest truth means both outcomes look
     * identical from the outside (the loser is never visibly different
     * whether or not she was ever allowed to try). This looks at the lock
     * itself instead.
     */
    public static boolean restockJobIsHeld(UUID crafterId, Item item) {
        return RESERVATIONS.containsKey(new RestockKey(crafterId, item));
    }
}
