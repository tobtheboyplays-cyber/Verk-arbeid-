package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Fuel;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.BagTransferPresentation;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.logistics.Weight;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.WorkContainerKind;
import com.hearthstead.entity.animation.BagToChestAnimationContract;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.entity.work.WorkerLifecycle;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.TavernHostService;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * a wall either (see {@link #hasContainerContact}, which keeps that promise with a
 * reach test rather than requiring literal containment in the building's
 * own recorded bounds -- a strict containment gate could be permanently
 * unsatisfiable when the only standable cell beside the chest lands just
 * outside it). The same standard now applies to a crafter's own chests on
 * the restock route.
 */
public class CourierWorkGoal extends Goal {

    /** Last workshop whose real output reservation succeeded.  It is advanced
     * only after the ledger grants this Courier the row, so an unavailable
     * workshop never steals the next turn from another productive building. */
    private static final String COLLECTION_SOURCE_CURSOR = "HearthsteadCourierCollectionSourceCursor";

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
    /** Absolute circuit breakers; moving detours reset stuck patience, not these. */
    private static final int HEARTH_ROUTE_CHECK_LIMIT = 80;  // ~1,200 ticks
    private static final int HAUL_ROUTE_CHECK_LIMIT = 160;   // ~2,400 ticks
    /** At least 0.2 blocks of body travel in one sample window is real progress. */
    private static final double PATH_PROGRESS_DISTANCE_SQR = 0.04D;
    /**
     * How often an idle Courier scans the bounded logistics ladder. Five
     * ticks keeps newly committed workplace output visibly responsive while
     * still avoiding a full settlement chest scan every server tick.
     */
    private static final int RESTOCK_LOOK_INTERVAL = 5;
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
     * A {@link #GATHERING_BUILDINGS} member ({@link BuildingType#MINE} and
     * its world-gathering siblings) has no Production table and consumes
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
    public static final int FOOD_PER_SETTLER = RecruitmentPolicy.MEALS_PER_PERSON_PER_DAY;
    /**
     * FUEL: how many batches' worth of fuel a burning building keeps on
     * hand. Restock triggers when its chests hold fewer than
     * {@code FUEL_RESERVE_BATCHES x Fuel.unitsPerBatch(type)} fuel units --
     * four batches covers a full courier round trip (claim, walk, withdraw, walk,
     * deposit can span several hundred ticks) with the burner never going
     * cold while the next load is on the road, mirroring how
     * {@link #FOOD_PER_SETTLER} buys the larder a day of margin.
     */
    public static final int FUEL_RESERVE_BATCHES = 4;
    /**
     * RESTOCK (raw material): how many batches' worth of a recipe's own
     * input a crafter is topped up to before the courier stops fetching
     * more. Field evidence (coordinator diagnostic, run 20260826): checking
     * a crafter against a single batch's {@code inputCount()} means the
     * FIRST delivery -- a full bag, since withdrawal is not itself
     * recipe-capped -- already clears every recipe's minimum, so the
     * courier never comes back and any remaining warehouse stock strands
     * there for good. A workshop that only ever holds enough for one
     * attempt stalls the moment a courier is busy elsewhere (another
     * crafter's turn, a raid, a farther delivery) even with the warehouse
     * still full. Four batches is the same margin {@link #FUEL_RESERVE_BATCHES}
     * already buys a firebox, for the identical reason -- a courier's round
     * trip can span several hundred ticks, and production should never idle
     * while the next load is on the road. (Not coincidentally: four batches
     * of the smithy's 3-ingot recipes is 12, exactly what
     * {@code LogisticsGameTests#restockConservesItemsAcrossTheFullRoute}
     * seeds -- that test was always proving this number, before this
     * constant existed to match it.)
     */
    public static final int MATERIAL_RESERVE_BATCHES = 4;

    /**
     * The hearth larder's LOW mark: {@link #FOOD_PER_SETTLER} meals for each
     * future post-recruit resident ({@code Settlement#population()} counts records, and
     * {@code SettlementManager#onSettlerDied} removes a record on death, so
     * the dead stop being catered for). This base excludes price exposure;
     * live routes use
     * {@link RecruitmentPolicy.Assessment#courierReadyFoodTarget()} so bread
     * that the discounted recruit price may consume is added too.
     */
    public static int hearthFoodThreshold(int livingSettlers) {
        return RecruitmentPolicy.baseCourierTarget(livingSettlers);
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
    /** Relative tick where COURIER_LIFT reaches its authored hand/load contact. */
    public static final int LIFT_GRIP_TICK = 12;
    /** Full 1.40 s one-shot; travel cannot resume while the lift still owns the feet. */
    public static final int LIFT_DURATION_TICKS = 28;
    public static final int HAUL_STEP_PERIOD = 18;
    public static final int HAUL_STRAIN_PERIOD = 96;
    /** Relative tick where COURIER_SET_DOWN reaches floor contact. */
    public static final int SET_DOWN_TICK = 12;
    /** Full 1.20 s one-shot; sorting begins only after this handoff. */
    public static final int SET_DOWN_DURATION_TICKS = 24;
    public static final int CRATE_CREAK_PERIOD = 54;
    public static final int CRATE_CREAK_OFFSET = 9;

    private enum Mode {
        TO_HEARTH, LOADING, TO_WAREHOUSE, SORTING, RETURNING,
        TO_SOURCE, WITHDRAWING, TO_CRAFTER, DEPOSITING,
        /** FOOD_DELIVERY's set-down: bag -> hearth, one stack per cycle. */
        STOCKING,
        /**
         * A successful trip has committed, but the courier still owns the
         * movement turn while she checks for the next logistics route.
         *
         * <p>Without this one-tick handoff an empty bag made the ordinary
         * schedule goal eligible between two capacity-split trips. Both goals
         * have the same priority, so an unassigned courier inside a sealed
         * warehouse could spend the rest of the shift repeatedly trying to
         * return to the settlement centre while two real items remained at
         * the hearth. Keeping ownership only through this bounded re-selection
         * preserves the complete priority ladder and releases immediately when
         * there is no more logistics work.
         */
        RESELECTING
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
        /** A named worker cannot begin work until one real tool arrives. */
        EQUIPMENT_REQUEST,
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
         * {@link #OUTPUT_KEEP_BACK} -- or a validated gathered output in a
         * {@link #GATHERING_BUILDINGS} member ({@link BuildingType#MINE}
         * and its world-gathering siblings) -- with a warehouse that has room
         * for it. FLOWS.md route 4, the return leg of the economy loop. Below
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
    /** RESTOCK only: exact item components proven to fit at claim time. */
    private ItemStack reservedStack = ItemStack.EMPTY;
    /** Persistent request row served by the current equipment trip. */
    private UUID equipmentRequestId;
    /** Persistent server-authoritative OUTPUT_PICKUP or FOOD row for this trip. */
    private UUID transportRequestId;
    /** Exact physical destination insertions accumulated for this one route. */
    private int routeInsertedItems;
    /** RESTOCK only: this reservation fills a fuel-unit deficit, not items. */
    private boolean reservedFuel;
    /** RESTOCK/COLLECTION: the ledger key held for this trip, if any. */
    private RestockKey reservationKey;
    private int workTicks;
    /** Prevents a contact transaction from replaying during its recovery frames. */
    private boolean liftContactCommitted;
    /** True only while COURIER_SET_DOWN owns the full body before SORTING. */
    private boolean setDownInProgress;
    /** One authoritative insert at most for each reviewed 80-tick cycle. */
    private boolean bagToChestCommitClaimed;
    /** Holds the fixed bag through closing/reach-back after the final insert. */
    private boolean bagToChestCompletionPending;
    /** Sole presentation/commit owner for the current exact stack-bundle cycle. */
    private UUID bagTransferId;
    private final CourierFoodBagSession foodBag;
    private final CourierSourceBagSession sourceBag;
    private final CourierHearthBagSession hearthBag;
    private ItemStack bagTransferUnit = ItemStack.EMPTY;
    private int repathTimer;
    private int stuckChecks;
    /** Route identity for a progress-based failure budget. */
    private Mode pathBudgetMode;
    private BlockPos pathBudgetTarget;
    private double pathSampleX;
    private double pathSampleY;
    private double pathSampleZ;
    private int routeChecks;
    private long cooldownUntil = Long.MIN_VALUE;
    /** Consecutive abandoned routes; reset by any completed delivery. */
    private int consecutiveFailures;
    private int restockCooldown;
    private boolean done;
    /** First blocked tier seen during the bounded 40-tick logistics scan. */
    private StopReason observedStop = StopReason.NONE;
    private BlockPos observedStopTarget;
    private Building observedStopBuilding;
    /** Plaque this courier last coloured, so clearing cannot leave stale light. */
    private BlockPos publishedStopPlaque;
    private StopReason publishedStopReason = StopReason.NONE;
    private long lastPlaqueHeartbeatTick = Long.MIN_VALUE;

    public CourierWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        this.foodBag = new CourierFoodBagSession(settler);
        this.sourceBag = new CourierSourceBagSession(settler);
        this.hearthBag = new CourierHearthBagSession(settler);
        this.hearthBag.unitLimit(this::hearthLiftBundle);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ---------------------------------------------------------- decision ---

    @Override
    public boolean canUse() {
        if (!settler.isBound()) {
            return false;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement initialSettlement = settler.settlement();
        if (initialSettlement == null || (settler.getProfession() != Profession.COURIER
            && (settler.getProfession() != Profession.MAYOR
                || Employment.ensureMayorCourierWorkplace(level, initialSettlement, settler) == null))) {
            return false;
        }
        heartbeatPlaqueStop();
        Settlement s = settler.settlement();
        if (s == null) {
            clearPublishedStop();
            return false;
        }
        // Paid post-raid Courier Satchel: raise the entity-owned trip budget
        // once; every capacity read below keeps using that single number.
        com.hearthstead.settlement.development.CourierSatchel.apply(level, s, settler);
        if (hearthBag.hasDeferredDelivery()) {
            if (level.getGameTime() < cooldownUntil) return false;
            warehouseId = hearthBag.deferredWarehouse(); dropOff = hearthBag.deferredTarget();
            if (warehouseId == null || dropOff == null) return false;
            job = JobPriority.WAREHOUSE_CONSOLIDATION;
            transportRequestId = null; equipmentRequestId = null; reservationKey = null;
            sourceWarehouseId = null; sourcePos = null; craftDropOff = null;
            reservedItem = null; reservedStack = ItemStack.EMPTY; reservedFuel = false;
            mode = Mode.TO_WAREHOUSE; done = false;
            return true;
        }
        // Saved untyped Hearth custody also precedes anonymous carrying recovery.
        if (hearthBag.active()) {
            if (!hearthBag.ready(level.getGameTime()) || level.getGameTime() < cooldownUntil) return false;
            warehouseId = hearthBag.warehouse(); dropOff = hearthBag.target();
            if (warehouseId == null || dropOff == null) return false;
            job = JobPriority.WAREHOUSE_CONSOLIDATION;
            transportRequestId = null; equipmentRequestId = null; reservationKey = null;
            sourceWarehouseId = null; sourcePos = null; craftDropOff = null;
            reservedItem = null; reservedStack = ItemStack.EMPTY; reservedFuel = false;
            mode = Mode.LOADING; done = false;
            return true;
        }
        // A stale, zero-cargo OUTPUT preview can only release itself after the
        // ledger has expired its exact matching row. Then fall through to a
        // fresh authority scan in this same selection pass.
        if (sourceBag.active()) {
            sourceBag.retireExpiredZeroCargoOutput(level);
        }
        // A partial source fill or its final lift owns selection before anonymous cargo.
        if (sourceBag.active()) {
            if (!sourceBag.ready(level.getGameTime()) || level.getGameTime() < cooldownUntil) return false;
            RequestRecord saved = sourceBag.request(level);
            if (saved == null) return false;
            ItemStack exact = saved.fingerprint().prototype(level.registryAccess());
            if (exact.isEmpty()) return false;
            transportRequestId = saved.id(); sourceWarehouseId = saved.sourceBuildingId();
            sourcePos = saved.sourceContainer(); reservedItem = exact.getItem();
            reservedStack = exact.copyWithCount(1); reservedFuel = false;
            reservationKey = null; equipmentRequestId = null;
            routeInsertedItems = saved.deliveredCount();
            switch (saved.type()) {
                case FOOD -> { job = JobPriority.FOOD_DELIVERY; dropOff = null; craftDropOff = null; }
                case OUTPUT_PICKUP -> { job = JobPriority.OUTPUT_COLLECTION;
                    warehouseId = saved.targetBuildingId(); dropOff = saved.targetContainer(); }
                case AMMUNITION, MATERIAL_INPUT -> { job = JobPriority.CRAFTER_RESTOCK;
                    craftBuildingId = saved.targetBuildingId(); craftDropOff = saved.targetContainer(); }
                default -> { return false; }
            }
            settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(saved.settlementId(), saved.id()));
            mode = Mode.WITHDRAWING;
            return true;
        }
        // Recover even the empty final lift before choosing another route.
        if (foodBag.active()) {
            if (foodBag.coolingDown(level)) return false;
            RequestRecord saved = foodBag.request(level);
            if (saved == null) return false; // Retain malformed/foreign intent and its real cargo.
            transportRequestId = saved.id();
            sourceWarehouseId = saved.sourceBuildingId();
            sourcePos = saved.sourceContainer();
            ItemStack exact = saved.fingerprint().prototype(level.registryAccess());
            if (exact.isEmpty()) return false;
            reservedItem = exact.getItem();
            reservedStack = exact.copyWithCount(1);
            reservedFuel = false;
            reservationKey = null;
            equipmentRequestId = null;
            routeInsertedItems = saved.deliveredCount();
            settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(saved.settlementId(), saved.id()));
            job = JobPriority.FOOD_DELIVERY;
            mode = Mode.STOCKING;
            return true;
        }
        boolean carrying = bagCount() > 0;
        // Low energy may reduce pace, but never hides a valid logistics route.
        // Finish cargo through the meal break. Otherwise a phase-change
        // interruption strands the village's lunch in the Courier's bag.
        boolean onShift = (settler.dayPhase().work()
            || carrying && settler.dayPhase().meal())
            && level.getGameTime() >= cooldownUntil;

        // A typed route survives goal recreation and a full server restart.
        // Rebuild it from persisted authority before interpreting an existing
        // bag as an anonymous legacy load or choosing any new work.
        RequestLedgerService.Route persisted = RequestLedgerService
            .routeForCourier(level, s, settler);
        if (persisted.outcome() == RequestLedgerService.Outcome.QUARANTINED) {
            reportStop(StopReason.RESTING_AFTER_FAIL, settler.getHearthPos(), null);
            return false; // Unknown typed ownership must never become anonymous warehouse cargo.
        }
        if (persisted.request() != null) {
            if (persisted.phase() == RequestLedgerService.RoutePhase.COMPLETE) {
                transportRequestId = null;
                settler.workerLifecycle().clear();
            } else {
            boolean adopted = switch (persisted.request().type()) {
                case FOOD -> adoptFoodRoute(level, persisted);
                case OUTPUT_PICKUP -> adoptOutputRoute(level, persisted);
                case AMMUNITION -> adoptAmmunitionRoute(level, persisted);
                case MATERIAL_INPUT -> adoptTavernRestockRoute(level, persisted);
                default -> false;
            };
            if (!adopted) {
                reportTypedBlock(persisted);
                return false;
            }
            if (!onShift) {
                settler.workerLifecycle().interrupt();
                return false;
            }
            return true;
            }
        }

        if (settler.workerLifecycle().task() != null) settler.workerLifecycle().clear();

        // Equipment routes now carry the same persisted ownership spine as
        // typed output routes. Rebuild volatile goal fields before treating a
        // real tool in the bag as anonymous consolidation cargo.
        EquipmentRequests.CourierRoute equipmentRoute =
            EquipmentRequests.routeForCourier(level, s, settler);
        if (equipmentRoute != null) {
            if (equipmentRoute.resumeBlocker() != RequestBlocker.NONE) {
                EquipmentRequests.block(level, s,
                    equipmentRoute.request().id(), settler.getUUID(),
                    equipmentRoute.resumeBlocker());
            }
            if (!adoptEquipmentRoute(equipmentRoute)) {
                return false;
            }
            if (!onShift) {
                return false;
            }
            return true;
        }
        if (EquipmentRequests.parkUnadoptableInTransit(level, s, settler)) {
            // A persisted request still owns this physical bag load. Parking
            // it is the only safe choice when its exact route is malformed;
            // generic consolidation would sever the request/item spine.
            reportStop(StopReason.RESTING_AFTER_FAIL,
                settler.getHearthPos(), null);
            return false;
        }

        if (carrying) {
            return canUseCarrying(level, s, onShift);
        }
        if (!onShift) {
            // Feeding the Hearth is meal service, even when no ordinary
            // workshop or shelf-sorting work should start during the break.
            if (settler.dayPhase().meal() && level.getGameTime() >= cooldownUntil) {
                FoodJob meal = findFoodJob(level, s);
                if (meal != null) {
                    beginFoodDelivery(meal);
                    return true;
                }
            }
            return false; // nothing in hand and nothing to do: skip the lookup
        }

        // The priority ladder (JobPriority): try each tier in turn and take
        // the first with real work.
        if (restockCooldown > 0) {
            restockCooldown--;
        } else {
            restockCooldown = RESTOCK_LOOK_INTERVAL;
            return selectPrioritizedRoute(level, s);
        }
        return beginConsolidation(level, s);
    }

    /**
     * Runs the complete bounded logistics ladder at a decision point.
     *
     * <p>Idle couriers call this on the existing forty-tick budget. A courier
     * that has just completed a physical delivery calls it once more from
     * {@link Mode#RESELECTING}; that is not a per-tick scan, and it prevents a
     * capacity-split source from being abandoned between its first and second
     * trip without allowing routine consolidation to jump ahead of a newly
     * opened equipment, restock, food or output request.
     */
    private boolean selectPrioritizedRoute(ServerLevel level, Settlement s) {
        resetStopObservation();
        // Default ladder: equipment, crafter restock, Hearth food (no crafter
        // is starving, so the larder before shelf-tidying), Tavern service,
        // output collection. The tech node Courier's Ledger re-sorts the rungs
        // by their most urgent open request (LogisticsEffects.courierLadder).
        for (com.hearthstead.settlement.techtree.effects.LogisticsEffects.Rung rung
                : com.hearthstead.settlement.techtree.effects.LogisticsEffects.courierLadder(level, s)) {
            if (tryRung(level, s, rung)) {
                return true;
            }
        }
        // Consolidation remains last and gets the chance to publish a useful
        // missing/full-warehouse blocker before the observation is committed.
        if (beginConsolidation(level, s)) {
            return true;
        }
        CollectionJob tidy = findCollectionJob(level, s, true);
        if (tidy == null) tidy = findWarehouseSortJob(level, s);
        if (tidy != null) {
            settler.getPersistentData().putUUID("HearthsteadCourierTidyRequest", tidy.requestId());
            beginCollection(tidy);
            return true;
        }
        publishStopObservation();
        return false;
    }

    /** One rung of the logistics ladder; true when a job was begun. */
    private boolean tryRung(ServerLevel level, Settlement s,
                            com.hearthstead.settlement.techtree.effects.LogisticsEffects.Rung rung) {
        switch (rung) {
            case EQUIPMENT -> {
                EquipmentJob equipment = findEquipmentJob(level, s);
                if (equipment != null) {
                    beginEquipment(equipment);
                    return true;
                }
            }
            case RESTOCK -> {
                RestockJob restock = findRestockJob(level, s);
                if (restock != null) {
                    beginRestock(restock);
                    return true;
                }
            }
            case FOOD -> {
                FoodJob food = findFoodJob(level, s);
                if (food != null) {
                    beginFoodDelivery(food);
                    return true;
                }
            }
            case TAVERN -> {
                RestockJob tavernRestock = findTavernRestockJob(level, s);
                if (tavernRestock != null) {
                    beginRestock(tavernRestock);
                    return true;
                }
            }
            case COLLECTION -> {
                CollectionJob collection = findCollectionJob(level, s);
                if (collection != null) {
                    beginCollection(collection);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A load already in the bag must be finished before anything new is
     * decided, whichever tier put it there: which job it was is remembered
     * in {@code job} across the stop()/start() an interruption causes, so
     * this re-validates today's route rather than picking a fresh one.
     */
    private boolean canUseCarrying(ServerLevel level, Settlement s, boolean onShift) {
        if (job == JobPriority.EQUIPMENT_REQUEST
            || job == JobPriority.CRAFTER_RESTOCK) {
            Building crafter = craftBuildingId == null ? null
                : findBuildingById(s, craftBuildingId);
            if (crafter == null || craftDropOff == null) {
                // The crafter this was reserved for is gone: nowhere left to
                // deliver to, so the raw material goes back to the warehouse
                // rather than sitting out of circulation in a bag.
                releaseReservation();
                reportStop(StopReason.RESTING_AFTER_FAIL, craftDropOff, null);
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
                reportStop(StopReason.RESTING_AFTER_FAIL,
                    settler.getHearthPos(), null);
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
                Building source = findBuildingById(s, sourceWarehouseId);
                reportStop(StopReason.NO_WAREHOUSE_SPACE,
                    source == null ? null : source.plaquePos, source);
                mode = Mode.RETURNING;
                return true;
            }
            if (!onShift) {
                return false; // hold the load until morning, same as the others
            }
            warehouseId = target.id;
            dropOff = chest;
            if (com.hearthstead.settlement.warehouse.WarehouseSorting
                    .foodOverflowActive(target.id)) {
                // Advisory: still delivering, but the player should add chests.
                reportStop(StopReason.FOOD_OVERFLOW, target.plaquePos, target);
            }
            mode = Mode.TO_WAREHOUSE;
            return true;
        }
        Building warehouse = pickWarehouse(s);
        BlockPos target = warehouse == null ? null : pickDropOff(level, warehouse);
        if (target == null) {
            warehouseId = null;
            dropOff = null;
            job = JobPriority.WAREHOUSE_CONSOLIDATION;
            reportStop(StopReason.NO_WAREHOUSE_SPACE, settler.getHearthPos(), null);
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
        // Do not diagnose an empty warehouse network when there is no cargo
        // waiting at the hearth in the first place.
        if (!hearthHasHaulableGoods()) {
            return false;
        }
        Building warehouse = pickWarehouse(s);
        BlockPos target = warehouse == null ? null : pickDropOff(level, warehouse);
        if (target == null) {
            observeStop(StopReason.NO_WAREHOUSE_SPACE,
                warehouse == null ? settler.getHearthPos() : warehouse.plaquePos,
                warehouse);
            return false; // idle visibly rather than thrash (MineColonies #2932)
        }
        warehouseId = warehouse.id;
        dropOff = target;
        job = JobPriority.WAREHOUSE_CONSOLIDATION;
        routeInsertedItems = 0;
        mode = Mode.TO_HEARTH;
        emitRouteClaim("building:" + warehouse.id, "mixed");
        return true;
    }

    private void beginRestock(RestockJob restock) {
        routeInsertedItems = 0;
        job = JobPriority.CRAFTER_RESTOCK;
        sourceWarehouseId = restock.warehouse().id;
        sourcePos = restock.sourceChest();
        craftBuildingId = restock.crafter().id;
        craftDropOff = restock.craftChest();
        reservedItem = restock.item();
        reservedStack = restock.stack().copyWithCount(1);
        reservedFuel = restock.fuel();
        reservationKey = restock.key();
        transportRequestId = restock.requestId();
        if (transportRequestId != null) {
            settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(
                settler.getSettlementId(), transportRequestId));
        }
        mode = Mode.TO_SOURCE;
        emitRouteClaim(transportRequestId == null
                ? "building:" + restock.crafter().id
                : "request:" + transportRequestId,
            itemId(reservedItem));
    }

    private void beginEquipment(EquipmentJob equipment) {
        routeInsertedItems = 0;
        job = JobPriority.EQUIPMENT_REQUEST;
        sourceWarehouseId = equipment.warehouse().id;
        sourcePos = equipment.sourceChest();
        craftBuildingId = equipment.workplace().id;
        craftDropOff = equipment.workplaceChest();
        reservedItem = equipment.stack().getItem();
        reservedStack = equipment.stack().copyWithCount(1);
        reservedFuel = false;
        reservationKey = equipment.key();
        equipmentRequestId = equipment.request().id();
        mode = Mode.TO_SOURCE;
        emitRouteClaim("request:" + equipmentRequestId,
            itemId(reservedItem));
    }

    private void beginFoodDelivery(FoodJob food) {
        transportRequestId = food.request().id();
        settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(
            food.request().settlementId(), food.request().id()));
        routeInsertedItems = 0;
        job = JobPriority.FOOD_DELIVERY;
        sourceWarehouseId = food.warehouse().id;
        sourcePos = food.sourceChest();
        craftBuildingId = null;
        craftDropOff = null;
        warehouseId = null;
        dropOff = null;
        reservedItem = food.item();
        reservedStack = food.stack().copyWithCount(1);
        reservedFuel = false;
        reservationKey = null; // FOOD ownership lives only in the durable ledger.
        mode = Mode.TO_SOURCE;
        settler.workerLifecycle().activate();
        emitRouteClaim("request:" + transportRequestId, itemId(reservedItem));
    }

    private void beginCollection(CollectionJob collection) {
        routeInsertedItems = 0;
        job = JobPriority.OUTPUT_COLLECTION;
        sourceWarehouseId = collection.source().id;
        sourcePos = collection.sourceChest();
        craftBuildingId = null;
        craftDropOff = null;
        warehouseId = collection.warehouse().id;
        dropOff = collection.dropChest();
        reservedItem = collection.item();
        reservedStack = collection.stack().copyWithCount(1);
        reservedFuel = false;
        reservationKey = null;
        transportRequestId = collection.requestId();
        mode = Mode.TO_SOURCE;
        emitRouteClaim("building:" + collection.source().id,
            itemId(reservedItem));
    }

    private boolean adoptFoodRoute(ServerLevel level, RequestLedgerService.Route route) {
        RequestRecord request = route.request();
        transportRequestId = request.id();
        settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(request.settlementId(), request.id()));
        if (route.phase() == RequestLedgerService.RoutePhase.BLOCKED || route.source() == null) {
            settler.workerLifecycle().interrupt();
            return false;
        }
        ItemStack exact = request.fingerprint().prototype(level.registryAccess());
        if (exact.isEmpty()) return false;
        job = JobPriority.FOOD_DELIVERY;
        sourceWarehouseId = request.sourceBuildingId();
        sourcePos = request.sourceContainer();
        craftBuildingId = null;
        craftDropOff = null;
        warehouseId = null;
        dropOff = null;
        reservedItem = exact.getItem();
        reservedStack = exact.copyWithCount(1);
        reservedFuel = false;
        reservationKey = null;
        routeInsertedItems = request.deliveredCount();
        mode = route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE ? Mode.TO_SOURCE : Mode.TO_HEARTH;
        return true;
    }

    /** Restores all volatile AI route fields from one persisted typed row. */
    private boolean adoptOutputRoute(ServerLevel level,
                                     RequestLedgerService.Route route) {
        RequestRecord request = route.request();
        if (request == null || route.source() == null || route.target() == null
            || route.phase() == RequestLedgerService.RoutePhase.BLOCKED
            || route.phase() == RequestLedgerService.RoutePhase.COMPLETE) {
            return false;
        }
        ItemStack exact = request.fingerprint().prototype(level.registryAccess());
        if (exact.isEmpty()) {
            return false;
        }
        transportRequestId = request.id();
        job = JobPriority.OUTPUT_COLLECTION;
        sourceWarehouseId = route.source().id;
        sourcePos = request.sourceContainer();
        warehouseId = route.target().id;
        dropOff = request.targetContainer();
        craftBuildingId = null;
        craftDropOff = null;
        reservedItem = exact.getItem();
        reservedStack = exact.copyWithCount(1);
        reservedFuel = false;
        reservationKey = null;
        routeInsertedItems = request.deliveredCount();
        mode = route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE
            ? Mode.TO_SOURCE : Mode.TO_WAREHOUSE;
        return true;
    }

    /** Restores one durable Warehouse-to-Hunter-Lodge Arrow route. */
    private boolean adoptAmmunitionRoute(
            ServerLevel level, RequestLedgerService.Route route) {
        RequestRecord request = route.request();
        if (request == null || request.type() != RequestType.AMMUNITION
            || route.source() == null) {
            return false;
        }
        ItemStack exact = request.fingerprint().prototype(level.registryAccess());
        if (exact.isEmpty() || !exact.is(Items.ARROW)) {
            return false;
        }

        Mode recoveredMode;
        if (route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE) {
            if (route.target() == null) return false;
            recoveredMode = Mode.TO_SOURCE;
        } else if (route.phase() == RequestLedgerService.RoutePhase.TO_TARGET) {
            if (route.target() == null) return false;
            recoveredMode = Mode.TO_CRAFTER;
        } else if (route.phase() == RequestLedgerService.RoutePhase.BLOCKED
            && request.effectiveState()
                == com.hearthstead.settlement.request.RequestState.IN_TRANSIT
            && ownsExactTransportBag(request, exact)) {
            recoveredMode = Mode.RETURNING;
        } else {
            return false;
        }

        transportRequestId = request.id();
        settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(
            request.settlementId(), request.id()));
        job = JobPriority.CRAFTER_RESTOCK;
        sourceWarehouseId = request.sourceBuildingId();
        sourcePos = request.sourceContainer();
        craftBuildingId = request.targetBuildingId();
        craftDropOff = request.targetContainer();
        warehouseId = null;
        dropOff = null;
        reservedItem = exact.getItem();
        reservedStack = exact.copyWithCount(1);
        reservedFuel = false;
        reservationKey = null;
        routeInsertedItems = request.deliveredCount();
        mode = recoveredMode;
        return true;
    }

    private boolean ownsExactTransportBag(RequestRecord request,
                                           ItemStack exact) {
        int matching = 0;
        int total = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (stack.isEmpty()) continue;
            total += stack.getCount();
            if (ItemStack.isSameItemSameComponents(stack, exact)) {
                matching += stack.getCount();
            }
        }
        return matching == request.remainingCount()
            && total == request.remainingCount();
    }

    /** Restores one exact persisted equipment route after goal/world reload. */
    private boolean adoptEquipmentRoute(
            EquipmentRequests.CourierRoute route) {
        if (route == null || route.request() == null
            || route.exactStack().isEmpty()) {
            return false;
        }
        equipmentRequestId = route.request().id();
        transportRequestId = null;
        job = JobPriority.EQUIPMENT_REQUEST;
        sourceWarehouseId = route.source().id;
        sourcePos = route.sourceContainer();
        craftBuildingId = route.target().id;
        craftDropOff = route.targetContainer();
        warehouseId = null;
        dropOff = null;
        reservedItem = route.exactStack().getItem();
        reservedStack = route.exactStack().copyWithCount(1);
        reservedFuel = false;
        reservationKey = null;
        routeInsertedItems = route.request().deliveredCount();
        mode = route.stage() == EquipmentRequest.TraceStage.SOURCE
            ? Mode.TO_SOURCE
            : shouldReturnPersistedEquipment(route.stage(),
                route.resumeBlocker()) ? Mode.RETURNING : Mode.TO_CRAFTER;
        return true;
    }

    static boolean shouldReturnPersistedEquipment(
            EquipmentRequest.TraceStage stage, RequestBlocker blocker) {
        return stage == EquipmentRequest.TraceStage.COURIER_BAG
            && blocker != RequestBlocker.NONE;
    }

    private void reportTypedBlock(RequestLedgerService.Route route) {
        if (route.request() != null && route.request().type() == RequestType.FOOD) {
            reportStop(route.blocker() == RequestBlocker.TARGET_FULL ? StopReason.HEARTH_FULL
                : route.blocker() == RequestBlocker.NO_PATH ? StopReason.NO_PATH
                : StopReason.RESTING_AFTER_FAIL, route.request().targetContainer(), route.source());
            return;
        }
        Building building = route.target() != null ? route.target() : route.source();
        StopReason reason = switch (route.blocker()) {
            case TARGET_FULL, TARGET_INVALID, TARGET_UNLOADED ->
                StopReason.NO_WAREHOUSE_SPACE;
            case NO_STOCK, SOURCE_INVALID, SOURCE_UNLOADED,
                 FINGERPRINT_MISMATCH -> StopReason.WAITING_INPUT;
            case NO_PATH -> StopReason.NO_PATH;
            case RESERVED_BY_OTHER -> StopReason.RESERVED_BY_OTHER;
            default -> StopReason.RESTING_AFTER_FAIL;
        };
        reportStop(reason, building == null ? null : building.plaquePos, building);
    }

    private void emitRouteClaim(String target, String item) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Settlement settlement = settler.settlement();
        AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.COURIER_ROUTE_CLAIMED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.items(
                settlement == null ? null : settlement.id, target,
                0, 0, 0, 0, item, 0, 0, 0,
                job.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * The route ladder is also the diagnosis ladder. Only the first blocked
     * tier is retained, so adding visibility cannot accidentally change the
     * job order or turn a lower-priority full shelf into the explanation for
     * a stopped top-priority crafter.
     */
    private void resetStopObservation() {
        observedStop = StopReason.NONE;
        observedStopTarget = null;
        observedStopBuilding = null;
    }

    private void observeStop(StopReason reason, BlockPos target, Building building) {
        if (observedStop != StopReason.NONE || reason == StopReason.NONE) {
            return;
        }
        observedStop = reason;
        observedStopTarget = target == null ? null : target.immutable();
        observedStopBuilding = building;
    }

    private void publishStopObservation() {
        if (observedStop == StopReason.NONE) {
            clearPublishedStop();
            return;
        }
        settler.setLogisticsStop(observedStop, observedStopTarget, 0);
        setPlaqueStop(observedStopBuilding, observedStop);
    }

    private void reportStop(StopReason reason, BlockPos target, Building building) {
        reportStop(reason, target, building, 0);
    }

    private void reportStop(StopReason reason, BlockPos target, Building building,
                            int retryTicks) {
        settler.setLogisticsStop(reason, target, retryTicks);
        setPlaqueStop(building, reason);
    }

    /** Clears both channels this courier owns: overhead line and lamp. */
    private void clearPublishedStop() {
        settler.clearLogisticsStop();
        clearPublishedPlaqueStop();
    }

    /**
     * Removes only this courier's contribution to the previous plaque.
     * A hearth-only or otherwise building-less stop must keep its overhead
     * explanation while no longer colouring an unrelated old destination.
     */
    private void clearPublishedPlaqueStop() {
        if (publishedStopPlaque != null
            && settler.level() instanceof ServerLevel level
            && level.getBlockEntity(publishedStopPlaque) instanceof PlaqueBlockEntity plaque) {
            plaque.setLogisticsStopReason(level, settler.getUUID(), StopReason.NONE);
        }
        publishedStopPlaque = null;
        publishedStopReason = StopReason.NONE;
        lastPlaqueHeartbeatTick = Long.MIN_VALUE;
    }

    private void setPlaqueStop(Building building, StopReason reason) {
        if (building == null) {
            clearPublishedPlaqueStop();
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        if (publishedStopPlaque != null && !publishedStopPlaque.equals(building.plaquePos)) {
            clearPublishedPlaqueStop();
        }
        if (level.getBlockEntity(building.plaquePos) instanceof PlaqueBlockEntity plaque) {
            plaque.setLogisticsStopReason(level, settler.getUUID(), reason);
            publishedStopPlaque = reason == StopReason.NONE ? null : building.plaquePos;
            publishedStopReason = reason;
            lastPlaqueHeartbeatTick = reason == StopReason.NONE
                ? Long.MIN_VALUE : level.getGameTime();
        } else if (reason == StopReason.NONE) {
            publishedStopPlaque = null;
            publishedStopReason = StopReason.NONE;
            lastPlaqueHeartbeatTick = Long.MIN_VALUE;
        }
    }

    /** One direct block-entity heartbeat per second; no world scan. */
    private void heartbeatPlaqueStop() {
        if (publishedStopPlaque == null || publishedStopReason == StopReason.NONE
            || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (now % 20 != 0 || now == lastPlaqueHeartbeatTick) {
            return;
        }
        if (level.getBlockEntity(publishedStopPlaque) instanceof PlaqueBlockEntity plaque) {
            plaque.setLogisticsStopReason(level, settler.getUUID(), publishedStopReason);
            lastPlaqueHeartbeatTick = now;
        } else {
            publishedStopPlaque = null;
            publishedStopReason = StopReason.NONE;
            lastPlaqueHeartbeatTick = Long.MIN_VALUE;
        }
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
        ItemStack cargo = ItemStack.EMPTY;
        boolean mixedCargo = false;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack held = settler.bag.getItem(slot);
            if (held.isEmpty()) continue;
            if (cargo.isEmpty()) cargo = held;
            else if (com.hearthstead.settlement.warehouse.WarehouseSorting.groupOf(held)
                    != com.hearthstead.settlement.warehouse.WarehouseSorting.groupOf(cargo)) mixedCargo = true;
        }
        if (cargo.isEmpty() && settler.hearth() != null) {
            var inventory = settler.hearth().getInventory();
            int[] surplus = hearthSurplus(inventory);
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                if (surplus[slot] > 0) { cargo = inventory.getStackInSlot(slot); break; }
            }
        }
        if (!mixedCargo && com.hearthstead.settlement.warehouse.WarehouseSorting.isCourierSortable(cargo)) {
            var grouped = com.hearthstead.settlement.warehouse.WarehouseSorting.destinations(level, warehouse, cargo.copyWithCount(1));
            return grouped.isEmpty() ? null : grouped.getFirst();
        }
        List<BlockPos> containers = WarehouseStorage.of(level, warehouse).containers();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos from = settler.blockPosition();
        for (BlockPos pos : containers) {
            // The storage cache can be up to 99 ticks old: a chest whose chunk
            // unloaded since must not be read (getBlockEntity would load it). BH-15
            if (!level.hasChunkAt(pos)) continue;
            if (com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(level.getBlockEntity(pos)) != null) continue;
            double d = from.distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /** Food is always off limits; recruitment materials also need a reserve. */
    private static boolean isHaulable(ItemStack stack) {
        return !stack.isEmpty() && !stack.has(DataComponents.FOOD)
            && com.hearthstead.settlement.warehouse.WarehouseSorting.isCourierSortable(stack);
    }

    private boolean hearthHasHaulableGoods() {
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            return false;
        }
        var inv = hearth.getInventory();
        int[] surplus = hearthSurplus(inv);
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            if (surplus[slot] > 0) {
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

    /** What the bag currently masses, in {@link Weight}'s units. */
    private int bagWeight() {
        int w = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            w += Weight.of(stack, stack.getCount());
        }
        return w;
    }

    /**
     * How many more of {@code stack}'s item this courier can take, under BOTH
     * limits at once: the item count her bag holds, and what she can lift.
     *
     * <p>This is where {@link Weight} finally bites. The table existed and was
     * documented for a day before anything called it, so until this method
     * every load was bounded by item COUNT alone -- eight iron ingots and
     * eight feathers cost exactly the same walk, no arrangement of buildings
     * could beat any other, and "put the mason near the warehouse" was
     * flavour text rather than advice. With mass in the loop a stone chain
     * moves a fraction of what a grain chain moves per trip, so distance
     * stops being a flat cost and starts multiplying: trips x weight x walk.
     *
     * <p>Conservation is untouched, which is the invariant that matters
     * (chest truth): this only ever makes a courier take FEWER items in one
     * trip, never fewer in total and never none. An empty bag always has room
     * for at least one of anything -- BAG_BUDGET is 16 and the heaviest class
     * is 6 -- so nothing can be stranded in a chest for want of a lift.
     */
    private int roomFor(ItemStack stack) {
        int byCount = settler.getCarryCapacity() - bagCount();
        int unit = Weight.of(stack);
        if (unit <= 0) {
            return Math.max(0, byCount);
        }
        int byWeight = (Weight.budgetFor(settler.getCarryCapacity()) - bagWeight()) / unit;
        return Math.max(0, Math.min(byCount, byWeight));
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
        settler.workerLifecycle().activate();
        applyTradePace();
        done = false;
        if (hearthBag.active()) { mode = Mode.LOADING; return; }
        if (sourceBag.active()) {
            mode = Mode.WITHDRAWING;
            return;
        }
        if (foodBag.active()) {
            mode = Mode.STOCKING;
            return; // Existing saved clock and grounded anchor remain authoritative.
        }
        workTicks = 0;
        liftContactCommitted = false;
        setDownInProgress = false;
        bagToChestCommitClaimed = false;
        bagToChestCompletionPending = false;
        if (settler.bagTransferPresentation().active()) {
            settler.clearBagTransferPresentation(
                settler.bagTransferPresentation().transferId());
        }
        bagTransferId = null;
        bagTransferUnit = ItemStack.EMPTY;
        settler.clearWorkContainer();
        resetPathBudget();
        launchCurrentLeg();
    }

    /** Starts navigation/presentation for a mode selected by either entry path. */
    private void launchCurrentLeg() {
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
                if (returnsToSource()) {
                    pathToChest(sourcePos);
                } else {
                    pathAbove(settler.getHearthPos());
                }
            }
            default -> {
                // TO_HEARTH serves two jobs: consolidation walks there
                // empty-handed to load, a food delivery arrives laden.
                settler.setActivity(bagCount() > 0
                    ? SettlerActivity.CARRYING : SettlerActivity.TRAVELING);
                if (job == JobPriority.FOOD_DELIVERY
                    && settler.level() instanceof ServerLevel level) {
                    pathToFoodHearth(level, settler.getHearthPos());
                } else {
                    pathAbove(settler.getHearthPos());
                }
            }
            case RESELECTING -> settler.setActivity(SettlerActivity.IDLE);
        }
    }

    /** Walks to the cell above a block -- used for the hearth. */
    private void pathAbove(BlockPos pos) {
        if (pos != null) {
            settler.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 1,
                pos.getZ() + 0.5, 0.95);
        }
    }

    /** FOOD delivery needs a route endpoint safely inside the ledger contact. */
    private void pathToFoodHearth(ServerLevel level, BlockPos hearthPos) {
        if (hearthPos == null) return;
        preparePathBudget(mode, hearthPos);
        var path = HearthApproach.findCourierFoodContactPath(settler, level, hearthPos);
        if (path != null) {
            settler.getNavigation().moveTo(path, 0.95D);
        }
        repathTimer = REPATH_INTERVAL;
    }
    /** Walks toward a standable cell beside any chest leg of any route. */
    private void pathToChest(BlockPos pos) {
        if (pos != null && settler.level() instanceof ServerLevel level) {
            preparePathBudget(mode, pos);
            ContainerApproach.moveToContact(level, settler, pos, 0.95D);
            repathTimer = REPATH_INTERVAL;
        }
    }

    /** Clears the patience budget when a new route or a fresh retry starts. */
    private void resetPathBudget() {
        repathTimer = 0;
        stuckChecks = 0;
        pathBudgetMode = null;
        pathBudgetTarget = null;
        pathSampleX = settler.getX();
        pathSampleY = settler.getY();
        pathSampleZ = settler.getZ();
        routeChecks = 0;
    }

    private void preparePathBudget(Mode budgetMode, BlockPos target) {
        if (pathBudgetMode != budgetMode
            || !Objects.equals(pathBudgetTarget, target)) {
            pathBudgetMode = budgetMode;
            pathBudgetTarget = target.immutable();
            pathSampleX = settler.getX();
            pathSampleY = settler.getY();
            pathSampleZ = settler.getZ();
            stuckChecks = 0;
            routeChecks = 0;
        }
    }

    /**
     * Counts consecutive intervals without measurable physical progress, not
     * elapsed travel time or straight-line distance to the target. Measuring
     * the body lets a healthy path move temporarily away around a wall without
     * looking stuck. A separate generous absolute circuit breaker still ends
     * an endlessly oscillating route. Returning true also lets the caller keep
     * a healthy active path instead of rebuilding it every fifteen ticks.
     */
    private boolean notePathProgress(BlockPos target) {
        preparePathBudget(mode, target);
        double dx = settler.getX() - pathSampleX;
        double dy = settler.getY() - pathSampleY;
        double dz = settler.getZ() - pathSampleZ;
        pathSampleX = settler.getX();
        pathSampleY = settler.getY();
        pathSampleZ = settler.getZ();
        routeChecks++;
        if (madePhysicalPathProgress(dx * dx + dy * dy + dz * dz)) {
            stuckChecks = 0;
            return true;
        }
        stuckChecks++;
        return false;
    }

    /**
     * Allocate one current recruitment payment in payment/slot order. Protect
     * partial stock too: missing bread must neither release reserved planks nor
     * prevent unrelated logs from being hauled. Each physical item is reserved
     * at most once, including prices with overlapping exact/tag lines.
     */
    private int[] hearthSurplus(ItemStackHandler inventory) {
        int[] surplus = new int[inventory.getSlots()];
        Settlement settlement = settler.settlement();
        if (!(settler.level() instanceof ServerLevel level) || settlement == null) {
            return surplus; // Missing authority/context never means drain everything.
        }
        for (int slot = 0; slot < surplus.length; slot++) {
            surplus[slot] = inventory.getStackInSlot(slot).getCount();
        }
        for (Costs.Line line : SettlementManager.recruitPrice(level, settlement).lines()) {
            int needed = line.count();
            for (int slot = 0; slot < surplus.length && needed > 0; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.isEmpty() || !(line.tag() != null
                        ? stack.is(line.tag()) : stack.is(line.exact()))) {
                    continue;
                }
                int reserved = Math.min(needed, surplus[slot]);
                surplus[slot] -= reserved;
                needed -= reserved;
            }
        }
        for (int slot = 0; slot < surplus.length; slot++) {
            if (!isHaulable(inventory.getStackInSlot(slot))) {
                surplus[slot] = 0;
            }
        }
        return surplus;
    }

    /** Package-visible deterministic seam for the physical progress tolerance. */
    static boolean madePhysicalPathProgress(double movementDistanceSqr) {
        return movementDistanceSqr > PATH_PROGRESS_DISTANCE_SQR;
    }

    // --------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        // GoalSelector may tick an every-tick goal once more before its next
        // continuation check. A finished leg must not repeat its failure or
        // extend the retry backoff while waiting for stop().
        if (done) return;
        heartbeatPlaqueStop();
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
            case RESELECTING -> tickReselecting();
        }
    }

    /**
     * Hands a completed trip directly to the next real logistics decision.
     * This owns at most one scan tick; no work means the goal ends now and the
     * ordinary settlement schedule gets the movement flag back.
     */
    private void tickReselecting() {
        if (!(settler.level() instanceof ServerLevel level)
            || !settler.dayPhase().work()
            || level.getGameTime() < cooldownUntil) {
            done = true;
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            done = true;
            return;
        }
        restockCooldown = RESTOCK_LOOK_INTERVAL;
        if (!selectPrioritizedRoute(level, settlement)) {
            done = true;
            return;
        }
        workTicks = 0;
        liftContactCommitted = false;
        setDownInProgress = false;
        resetPathBudget();
        launchCurrentLeg();
    }

    private void tickToHearth() {
        if (job == JobPriority.FOOD_DELIVERY && transportRequestId != null
            && settler.level() instanceof ServerLevel level) {
            Settlement settlement = settler.settlement();
            if (settlement == null) { done = true; return; }
            if (!(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
                || !settlement.id.equals(hearth.getSettlementId())) {
                RequestLedgerService.Route route = RequestLedgerService.routeForCourier(level, settlement, settler);
                reportTypedBlock(route);
                settler.workerLifecycle().interrupt();
                done = true;
                return;
            }
        }

        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null) {
            // A laden food trip re-decides through canUseCarrying, which
            // sends the meals back to their warehouse; an empty-handed
            // consolidation walk just stands down.
            reportStop(StopReason.RESTING_AFTER_FAIL, null, null);
            done = true;
            return;
        }
        workTicks++;
        if (bagCount() > 0) {
            playHaulSounds(); // the food leg carries real weight like any haul
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5,
            hearthPos.getY() + 0.6, hearthPos.getZ() + 0.5);
        boolean foodDelivery = job == JobPriority.FOOD_DELIVERY;
        boolean withinHearthReach = settler.blockPosition().distSqr(hearthPos) <= HEARTH_REACH_SQR;
        if (foodDelivery && settler.level() instanceof ServerLevel foodLevel) {
            withinHearthReach = RequestLedgerService.hasFoodHearthContact(foodLevel,
                settler, hearthPos);
        }
        // BagTransferPresentation retains this feet block as its recovery
        // anchor. Vanilla collision marks grounded slabs, stairs and plates
        // onGround, but keeps a falling air node false. Until it lands, leave
        // navigation active so the existing 15-tick hearth repath can finish.
        boolean groundedFoodArrival = foodDelivery && withinHearthReach
            && settler.onGround();
        if ((!foodDelivery && withinHearthReach) || groundedFoodArrival) {
            settler.getNavigation().stop();
            if (foodDelivery) {
                // Laden arrival: the crate comes DOWN here, mirroring
                // tickToWarehouse -- the thud is scheduled so it lands with
                // the clip's contact, not before it.
                beginSetDown(Mode.STOCKING);
            } else if (settler.level() instanceof ServerLevel level
                    && hearthBag.begin(level, warehouseId, dropOff, this::hearthSlotHaulable)) {
                mode = Mode.LOADING;
            } else {
                restRoute(StopReason.WAITING_INPUT, hearthPos, null);
                done = true;
            }
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            boolean progressing = notePathProgress(hearthPos);
            if (stuckChecks > HEARTH_STUCK_LIMIT
                || routeChecks > HEARTH_ROUTE_CHECK_LIMIT) {
                giveUp(hearthPos, null); // unreachable hearth: rest the route, don't spin
            } else if (!progressing || settler.getNavigation().isDone()) {
                if (foodDelivery && settler.level() instanceof ServerLevel foodLevel) {
                    pathToFoodHearth(foodLevel, hearthPos);
                } else {
                    pathAbove(hearthPos);
                }
            }
        }
    }

    /** Re-read protected recruitment stock and actual weight before every source contact. */
    /**
     * [economy] courierDepositBundle for the Hearth lift: at most the bundle,
     * the slot's surplus above what the Hearth keeps, and the bag's room.
     * 1 on the GameTest server (the original one-item cycle).
     */
    private int hearthLiftBundle(int slot) {
        HearthBlockEntity h = settler.hearth();
        if (h == null || slot < 0 || slot >= h.getInventory().getSlots()
            || !(settler.level() instanceof ServerLevel level)) return 1;
        int bundle = com.hearthstead.settlement.economy.EconomyConfig
            .courierDepositBundle(level.getServer());
        if (bundle <= 1) return 1;
        ItemStack stack = h.getInventory().getStackInSlot(slot);
        return Math.max(1, Math.min(bundle, Math.min(hearthSurplus(h.getInventory())[slot],
            roomFor(stack))));
    }

    private boolean hearthSlotHaulable(int slot) {
        HearthBlockEntity h = settler.hearth();
        if (h == null || slot < 0 || slot >= h.getInventory().getSlots()) return false;
        ItemStack stack = h.getInventory().getStackInSlot(slot);
        // The drop-off may be a far warehouse chest: never load its chunk from the
        // Hearth (BH-15); insertAt re-checks the chest's group at the real contact.
        if (dropOff != null && settler.level() instanceof ServerLevel level && level.hasChunkAt(dropOff)) {
            var group = com.hearthstead.settlement.warehouse.WarehouseSorting.assignedGroup(level.getBlockEntity(dropOff));
            if (group != null && group != com.hearthstead.settlement.warehouse.WarehouseSorting.groupOf(stack)) return false;
        }
        return com.hearthstead.settlement.warehouse.WarehouseSorting.isCourierSortable(stack)
            && hearthSurplus(h.getInventory())[slot] > 0 && roomFor(stack) > 0;
    }

    private void tickLoading() {
        if (!(settler.level() instanceof ServerLevel level)) { done = true; return; }
        var result = hearthBag.tick(level, this::hearthSlotHaulable);
        if (result == CourierHearthBagSession.Result.BLOCKED) {
            restRoute(hearthBag.pathResting(level.getGameTime()) ? StopReason.NO_PATH : StopReason.WAITING_INPUT,
                settler.getHearthPos(), null);
            done = true;
            return;
        }
        if (result != CourierHearthBagSession.Result.COMPLETE) return;
        if (bagCount() == 0) { done = true; return; }
        mode = Mode.TO_WAREHOUSE;
        workTicks = 0;
        resetPathBudget();
        settler.setActivity(SettlerActivity.CARRYING);
        pathToChest(dropOff);
    }

    private void tickToWarehouse() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement s = settler.settlement();
        Building warehouse = s == null ? null : findBuildingById(s, warehouseId);
        if (dropOff == null) {
            reportStop(StopReason.NO_WAREHOUSE_SPACE,
                warehouse == null ? null : warehouse.plaquePos, warehouse);
            if (transportRequestId != null && s != null) {
                RequestLedgerService.block(level, s, transportRequestId, settler,
                    RequestBlocker.TARGET_INVALID);
            }
            done = true;
            return;
        }
        if (warehouse == null) {
            reportStop(StopReason.NO_WAREHOUSE_SPACE, dropOff, null);
            if (transportRequestId != null && s != null) {
                RequestLedgerService.block(level, s, transportRequestId, settler,
                    RequestBlocker.TARGET_INVALID);
                done = true;
                return;
            }
            beginReturn(); // dissolved mid-trip: carry the goods home
            return;
        }
        workTicks++;
        playHaulSounds();
        settler.getLookControl().setLookAt(dropOff.getX() + 0.5,
            dropOff.getY() + 0.6, dropOff.getZ() + 0.5);
        if (hasContainerContact(level, dropOff)) {
            settler.getNavigation().stop();
            // COURIER_SET_DOWN starts now; its contact is SET_DOWN_TICK
            // ticks in, so the thud is scheduled rather than played here
            // (playing it immediately put the sound before the crate had
            // visibly left the settler's hands).
            beginSetDown(Mode.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            boolean progressing = notePathProgress(dropOff);
            if (stuckChecks > HAUL_STUCK_LIMIT
                || routeChecks > HAUL_ROUTE_CHECK_LIMIT) {
                // The warehouse cannot be reached from here. Carry the load
                // back to the hearth so the goods stay in circulation, and
                // rest the route so this is not an invisible busy-loop.
                giveUp(warehouse.plaquePos, warehouse);
            } else if (!progressing || settler.getNavigation().isDone()) {
                pathToChest(dropOff);
            }
        }
    }

    /** Server-authoritative physical contact gate for every chest mutation. */
    private boolean hasContainerContact(ServerLevel level, BlockPos target) {
        return ContainerApproach.inspect(level, settler, target).canInteract();
    }

    /**
     * A lost chest contact is a transient travel condition, not evidence of
     * a full container or a completed request. Preserve the bag, reservation,
     * route target, animation progress and bounded re-path counters; only
     * return the visual sack to the carrying pose and retry the same leg.
     */
    private void resumeChestTravel(Mode travelMode, BlockPos target) {
        settler.clearWorkContainer();
        mode = travelMode;
        settler.setActivity(SettlerActivity.CARRYING);
        pathToChest(target);
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

    }

    /** One explicitly labelled stack bundle per cycle, committing at tick 48. */
    private void tickSorting() {
        if (tickSetDownPhase()) {
            return;
        }
        if (!tickBagToChestCommitGate()) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement s = settler.settlement();
        if (job == JobPriority.OUTPUT_COLLECTION && transportRequestId != null) {
            if (!hasContainerContact(level, dropOff)) {
                resumeChestTravel(Mode.TO_WAREHOUSE, dropOff);
                return;
            }
            tickTypedOutputDelivery(level, s);
            return;
        }
        Building warehouse = s == null ? null : findBuildingById(s, warehouseId);
        if (warehouse == null) {
            reportStop(StopReason.NO_WAREHOUSE_SPACE, dropOff, null);
            beginReturn(); // dissolved mid-delivery: take them home
            return;
        }
        if (!hasContainerContact(level, dropOff)) {
            resumeChestTravel(Mode.TO_WAREHOUSE, dropOff);
            return;
        }
        WarehouseStorage storage = WarehouseStorage.of(level, warehouse);
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty() || stack.getCount() < bagTransferUnit.getCount()
                || !ItemStack.isSameItemSameComponents(stack, bagTransferUnit)) {
                continue;
            }
            int visualCount = bagTransferUnit.getCount();
            ItemStack offered = stack.copyWithCount(visualCount);
            ItemStack leftover = storage.insertAt(level, warehouse, dropOff,
                offered);
            int inserted = visualCount - leftover.getCount();
            routeInsertedItems += Math.max(0, inserted);
            if (inserted > 0) com.hearthstead.event.WarehouseLabels.ensure(level, dropOff);
            if (inserted > 0) playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            if (inserted > 0) markBagTransferCommitted();
            // Job standard, point 8: a stack actually filed is one unit of a
            // courier's work, and what it builds is the legs to carry the next.
            if (inserted > 0) settler.train(
                com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            if (inserted > 0) com.hearthstead.entity.SkillLevels.completeUnit(settler, 1,
                com.hearthstead.entity.Attribute.STAMINA);
            ItemStack kept = stack.copy();
            kept.shrink(inserted);
            settler.bag.setItem(i, kept.isEmpty() ? ItemStack.EMPTY : kept);
            if (inserted == 0) {
                reportStop(StopReason.NO_WAREHOUSE_SPACE,
                    warehouse.plaquePos, warehouse);
                beginReturn(); // warehouse full: stop, and carry what is left back home
            } else if (bagCount() == 0) {
                bagToChestCompletionPending = true;
            }
            return; // one stack per cycle -- the animation beat
        }
        bagToChestCompletionPending = true;
    }

    private void tickTypedOutputDelivery(ServerLevel level,
                                         Settlement settlement) {
        if (settlement == null) {
            done = true;
            return;
        }
        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            level, settlement, settler);
        RequestRecord request = route.request();
        if (request == null || !request.id().equals(transportRequestId)) {
            done = true;
            return;
        }
        int deliveredBefore = request.deliveredCount();
        RequestLedgerService.Decision result = RequestLedgerService.deliver(
            level, settlement, transportRequestId, settler,
            bagTransferUnit.getCount());
        RequestRecord committed = result.request();
        if (committed != null) {
            int inserted = Math.max(0,
                committed.deliveredCount() - deliveredBefore);
            routeInsertedItems += inserted;
            if (inserted > 0) {
                com.hearthstead.event.WarehouseLabels.ensure(level, dropOff);
                markBagTransferCommitted();
            }
        }
        if (result.outcome() == RequestLedgerService.Outcome.SATISFIED) {
            Building target = findBuildingById(settlement, warehouseId);
            consecutiveFailures = 0;
            clearPublishedStop();
            if (target != null) {
                setPlaqueStop(target, StopReason.NONE);
            }
            transportRequestId = null;
            bagToChestCompletionPending = true;
            return;
        }
        if (!result.accepted()) {
            Building target = findBuildingById(settlement, warehouseId);
            StopReason reason = result.blocker() == RequestBlocker.NO_PATH
                ? StopReason.NO_PATH : StopReason.NO_WAREHOUSE_SPACE;
            restRoute(reason, target == null ? dropOff : target.plaquePos,
                target);
            done = true;
        }
    }

    /** Starts the whole lift at local tick zero with its empty sack on the ground. */
    private void beginLift(Mode liftMode) {
        mode = liftMode;
        workTicks = 0;
        liftContactCommitted = false;
        settler.setActivity(SettlerActivity.SORTING);
        settler.placeWorkContainer(WorkContainerKind.SACK,
            settler.blockPosition());
        settler.triggerCourierLift();
    }

    /** Starts set-down while the physical load is still attached to the carrier. */
    private void beginSetDown(Mode deliveryMode) {
        if (deliveryMode == Mode.STOCKING && transportRequestId != null
                && settler.level() instanceof ServerLevel level) {
            foodBag.begin(level, transportRequestId);
            if (foodBag.active()) {
                mode = Mode.STOCKING;
                return;
            }
            done = true;
            return;
        }
        mode = deliveryMode;
        workTicks = 0;
        setDownInProgress = true;
        bagToChestCommitClaimed = false;
        bagToChestCompletionPending = false;
        bagTransferId = null;
        bagTransferUnit = ItemStack.EMPTY;
        // CARRYING keeps the loaded sack and carry handoff pose until floor
        // contact; the ground projection is published only on that contact.
        settler.setActivity(SettlerActivity.CARRYING);
        if (isBagToChestMode()) {
            beginBagTransferPresentation(deliveryMode == Mode.SORTING
                ? dropOff : craftDropOff);
            settler.triggerBagToChestUnload();
        } else {
            settler.triggerCourierSetDown();
        }
    }

    /** Owns the full set-down one-shot before the first sorting tick. */
    private boolean tickSetDownPhase() {
        if (!setDownInProgress) {
            return false;
        }
        workTicks++;
        if (isBagToChestMode()) advanceBagTransferPresentation(workTicks, bagToChestCommitClaimed);
        if (workTicks == SET_DOWN_TICK) {
            settler.placeWorkContainer(WorkContainerKind.SACK,
                settler.blockPosition());
            playAt(ModSounds.CRATE_DOWN.get(), 0.8F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        if (workTicks >= SET_DOWN_DURATION_TICKS) {
            setDownInProgress = false;
            // The reviewed chest candidate began at arrival tick zero, so its
            // authority clock must retain the first 24 set-down ticks. Hearth
            // stocking still uses the original independent sort loop.
            if (!isBagToChestMode()) {
                workTicks = 0;
            }
            settler.setActivity(SettlerActivity.SORTING);
        }
        return true;
    }

    private boolean isBagToChestMode() {
        return mode == Mode.SORTING || mode == Mode.DEPOSITING;
    }

    /**
     * Advances the exact 80-tick candidate clock and grants one insert ticket
     * at tick 48. Recovery owns the actor and grounded bag until tick 80;
     * only then may completion clear the projection or a remaining load begin
     * another visible cycle.
     */
    private boolean tickBagToChestCommitGate() {
        workTicks++;
        advanceBagTransferPresentation(workTicks, bagToChestCommitClaimed);
        BlockPos target = mode == Mode.SORTING ? dropOff : craftDropOff;
        if (target != null && settler.level() instanceof ServerLevel level) {
            if (workTicks == BagToChestAnimationContract.LID_CONTACT_TICK) {
                var state = level.getBlockState(target);
                level.blockEvent(target, state.getBlock(), 1, 1);
            } else if (workTicks == BagToChestAnimationContract.LID_CLOSED_TICK) {
                var state = level.getBlockState(target);
                level.blockEvent(target, state.getBlock(), 1, 0);
            }
        }
        if (BagToChestAnimationContract.continuesGroundedSession(workTicks,
                bagToChestCommitClaimed, bagToChestCompletionPending)) {
            // Keep the same planted sack; only the next bundle receives a new
            // one-use ticket. Never replay shoulder pickup between stacks.
            workTicks = BagToChestAnimationContract.GROUNDED_REPEAT_TICK;
            bagToChestCommitClaimed = false;
            beginBagTransferPresentation(target);
            return false;
        }
        if (BagToChestAnimationContract.beginsCycle(workTicks)) {
            closeAndClearBagTransfer(target);
            if (bagToChestCompletionPending) {
                finishBagToChestDeliveryAfterRecovery();
                return false;
            }
            bagToChestCommitClaimed = false;
            beginBagTransferPresentation(target);
            settler.triggerBagToChestUnload();
        }
        if (!BagToChestAnimationContract.mayCommit(workTicks,
                bagToChestCommitClaimed)) {
            return false;
        }
        if (!bagStillOwnsPresentedUnit()) {
            // Inventory changed during the reach (player intervention,
            // recovery or another authority). The picture may not authorize
            // a different item; wait for the next cycle to publish fresh truth.
            bagToChestCompletionPending = bagCount() == 0;
            return false;
        }
        bagToChestCommitClaimed = true;
        return true;
    }

    private boolean bagStillOwnsPresentedUnit() {
        if (bagTransferId == null || bagTransferUnit.isEmpty()) return false;
        BagTransferPresentation current = settler.bagTransferPresentation();
        if (!current.active() || !bagTransferId.equals(current.transferId())
            || current.committed()
            || !ItemStack.isSameItemSameComponents(current.item(),
                bagTransferUnit)) return false;
        if (current.containerPos() == null
            || plannedTransferCount(current.containerPos(), bagTransferUnit)
                < bagTransferUnit.getCount()) return false;
        int matching = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack live = settler.bag.getItem(slot);
            if (!live.isEmpty() && ItemStack.isSameItemSameComponents(
                    live, bagTransferUnit)) matching += live.getCount();
        }
        return matching >= bagTransferUnit.getCount();
    }

    private void beginBagTransferPresentation(BlockPos target) {
        BagTransferPresentation previous = settler.bagTransferPresentation();
        BlockPos anchor = settler.placedWorkContainerPos();
        if (anchor == null) anchor = settler.blockPosition();
        float yaw = previous.active() && anchor.equals(previous.bagAnchor())
            ? previous.bagYaw() : settler.getYRot();
        if (previous.active()) settler.clearBagTransferPresentation(previous.transferId());
        bagTransferId = null;
        bagTransferUnit = ItemStack.EMPTY;
        if (target == null) return;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (!stack.isEmpty()) {
                int planned = plannedTransferCount(target, stack);
                if (planned <= 0) return;
                bagTransferId = UUID.randomUUID();
                bagTransferUnit = stack.copyWithCount(planned);
                settler.publishBagTransferPresentation(new BagTransferPresentation(
                    bagTransferId, anchor, yaw,
                    target, workTicks, false, bagTransferUnit));
                return;
            }
        }
    }

    private void advanceBagTransferPresentation(int clock, boolean committed) {
        if (bagTransferId == null) return;
        BagTransferPresentation current = settler.bagTransferPresentation();
        if (current.active() && bagTransferId.equals(current.transferId())) {
            settler.publishBagTransferPresentation(current.advance(clock, committed));
        }
    }

    private void markBagTransferCommitted() {
        advanceBagTransferPresentation(workTicks, true);
    }

    /** Exact room in the named physical container, re-read at contact. */
    private int plannedTransferCount(BlockPos target, ItemStack stack) {
        if (target == null || stack.isEmpty()
            || !(settler.level() instanceof ServerLevel level)
            || !(level.getBlockEntity(target) instanceof Container container)) return 0;
        // [economy] courierDepositBundle (plan/ECONOMY.md): one item per
        // 80-tick stow motion made unloading a full bag take ~18 s and capped
        // three couriers at ~400 moved items a day. The motion and its
        // labelled bundle are unchanged; the bundle is just bigger. 1 on the
        // GameTest server.
        int bundle = com.hearthstead.settlement.economy.EconomyConfig
            .courierDepositBundle(level.getServer());
        return Math.min(bundle, transferRoom(container, stack));
    }

    static int transferRoom(Container container, ItemStack stack) {
        if (container == null || stack == null || stack.isEmpty()) return 0;
        int room = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, stack)) continue;
            ItemStack held = container.getItem(slot);
            if (held.isEmpty()) {
                room += Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
            } else if (ItemStack.isSameItemSameComponents(held, stack)) {
                room += Math.max(0, Math.min(container.getMaxStackSize(),
                    held.getMaxStackSize()) - held.getCount());
            }
            if (room >= stack.getCount()) return stack.getCount();
        }
        return Math.min(room, stack.getCount());
    }

    private void closeAndClearBagTransfer(BlockPos target) {
        if (target != null && settler.level() instanceof ServerLevel level) {
            var state = level.getBlockState(target);
            level.blockEvent(target, state.getBlock(), 1, 0);
        }
        if (bagTransferId != null) settler.clearBagTransferPresentation(bagTransferId);
        bagTransferId = null;
        bagTransferUnit = ItemStack.EMPTY;
    }

    private void finishBagToChestDeliveryAfterRecovery() {
        bagToChestCompletionPending = false;
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement settlement = settler.settlement();
        if (mode == Mode.SORTING) {
            Building warehouse = settlement == null ? null
                : findBuildingById(settlement, warehouseId);
            if (warehouse == null) {
                done = true;
                return;
            }
            finishWarehouseDelivery(level, settlement, warehouse);
        } else if (mode == Mode.DEPOSITING) {
            Building crafter = settlement == null ? null
                : findBuildingById(settlement, craftBuildingId);
            if (crafter == null) {
                done = true;
                return;
            }
            finishCrafterDelivery(level, settlement, crafter);
        }
    }

    // ------------------------------------------------------ restock legs ---

    private void tickToSource() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        Settlement s = settler.settlement();
        Building source = s == null ? null : findBuildingById(s, sourceWarehouseId);
        if (sourcePos == null) {
            reportStop(StopReason.RESTING_AFTER_FAIL,
                source == null ? null : source.plaquePos, source);
            done = true;
            if (transportRequestId != null && s != null) {
                RequestLedgerService.block(level, s, transportRequestId, settler,
                    RequestBlocker.SOURCE_INVALID);
                return;
            }
            releaseReservation();
            return;
        }
        if (source == null) {
            // The source building (a warehouse for a restock, the workshop
            // for a collection) dissolved mid-trip. Nothing has been taken
            // yet -- the bag is still empty at this point in the route --
            // so there is nothing to lose; just stand down.
            reportStop(StopReason.WAITING_INPUT, sourcePos, null);
            done = true;
            if (transportRequestId != null && s != null) {
                RequestLedgerService.block(level, s, transportRequestId, settler,
                    RequestBlocker.SOURCE_INVALID);
                return;
            }
            releaseReservation();
            return;
        }
        settler.getLookControl().setLookAt(sourcePos.getX() + 0.5,
            sourcePos.getY() + 0.6, sourcePos.getZ() + 0.5);
        if (hasContainerContact(level, sourcePos)) {
            settler.getNavigation().stop();
            if (transportRequestId != null) {
                var ledger = RequestLedgerSavedData.existing(level);
                var requests = ledger == null || s == null ? null : ledger.existing(s.id);
                var request = requests == null ? null : requests.active(transportRequestId);
                sourceBag.begin(level, request);
                if (sourceBag.active()) { mode = Mode.WITHDRAWING; return; }
                done = true;
                return;
            }
            beginLift(Mode.WITHDRAWING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            boolean progressing = notePathProgress(sourcePos);
            if (stuckChecks > HAUL_STUCK_LIMIT
                || routeChecks > HAUL_ROUTE_CHECK_LIMIT) {
                giveUp(source.plaquePos, source);
            } else if (!progressing || settler.getNavigation().isDone()) {
                pathToChest(sourcePos);
            }
        }
    }

    /** Lifts one bag-load of the reserved item out of the source chest. */
    private void tickWithdrawing() {
        if (sourceBag.active() && settler.level() instanceof ServerLevel sourceLevel) {
            var marker = settler.getPersistentData();
            var request = sourceBag.request(sourceLevel);
            if (request != null && marker.hasUUID("HearthsteadCourierTidyRequest")
                    && request.id().equals(marker.getUUID("HearthsteadCourierTidyRequest"))) {
                Settlement settlement = settler.settlement();
                Building source = settlement == null ? null
                    : findBuildingById(settlement, request.sourceBuildingId());
                // Re-check before every physical unit, including after reload.
                // A room reassigned while walking may now need this stock.
                boolean internalSort = source != null && source.type == BuildingType.WAREHOUSE
                    && source.id.equals(request.targetBuildingId())
                    && com.hearthstead.settlement.warehouse.WarehouseSorting.isCourierSortable(reservedStack);
                if (source == null || !internalSort && !mayTidy(source.type, reservedStack)) {
                    RequestLedgerService.block(sourceLevel, settlement, request.id(),
                        settler, RequestBlocker.NO_STOCK);
                    sourceBag.interrupt();
                    done = true;
                    return;
                }
            }
            var result = sourceBag.tick(sourceLevel);
            if (result == CourierSourceBagSession.Result.COMPLETE) finishWithdrawalTravel();
            else if (result == CourierSourceBagSession.Result.BLOCKED) {
                if (sourceBag.pathResting(sourceLevel.getGameTime())) {
                    Settlement s = settler.settlement();
                    Building source = s == null ? null : findBuildingById(s, sourceWarehouseId);
                    giveUp(source == null ? sourcePos : source.plaquePos, source);
                } else {
                    reportStop(StopReason.WAITING_INPUT, sourcePos, null);
                    done = true;
                }
            }
            return;
        }
        workTicks++;
        if (workTicks < LIFT_GRIP_TICK) {
            return;
        }
        if (liftContactCommitted) {
            if (workTicks >= LIFT_DURATION_TICKS) {
                finishWithdrawalTravel();
            }
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        if (!hasContainerContact(level, sourcePos)) {
            // Repeated bumps during the grip must spend the same source-leg
            // budget as walking. WITHDRAWING is only its presentation phase:
            // charging that mode would reset the budget on every retry.
            mode = Mode.TO_SOURCE;
            notePathProgress(sourcePos);
            if (stuckChecks > HAUL_STUCK_LIMIT
                || routeChecks > HAUL_ROUTE_CHECK_LIMIT) {
                Settlement settlement = settler.settlement();
                Building source = settlement == null ? null
                    : findBuildingById(settlement, sourceWarehouseId);
                giveUp(source == null ? sourcePos : source.plaquePos, source);
                return;
            }
            resumeChestTravel(Mode.TO_SOURCE, sourcePos);
            return;
        }
        if (transportRequestId != null
            && (job == JobPriority.OUTPUT_COLLECTION
                || job == JobPriority.FOOD_DELIVERY
                || job == JobPriority.CRAFTER_RESTOCK)) {
            tickTypedTransportPickup(level);
            return;
        }
        if (!(level.getBlockEntity(sourcePos) instanceof Container container)) {
            // The chest is gone by the time we arrived -- nothing has been
            // taken yet, so nothing is lost. Stand down rather than assume.
            Settlement s = settler.settlement();
            Building source = s == null ? null : findBuildingById(s, sourceWarehouseId);
            reportStop(StopReason.WAITING_INPUT,
                source == null ? sourcePos : source.plaquePos, source);
            done = true;
            releaseReservation();
            return;
        }
        int bagBefore = bagCount();
        EquipmentRequest equipmentRequest = null;
        int equipmentSourceSlot = -1;
        if (job == JobPriority.EQUIPMENT_REQUEST) {
            Settlement active = settler.settlement();
            equipmentRequest = active == null || equipmentRequestId == null
                ? null : EquipmentRequests.byId(active, equipmentRequestId);
            if (equipmentRequest == null
                || equipmentRequest.traceStage()
                    != EquipmentRequest.TraceStage.SOURCE) {
                reportStop(StopReason.WAITING_INPUT, sourcePos, null);
                done = true;
                releaseReservation();
                return;
            }
            equipmentSourceSlot = equipmentRequest.sourceSlot();
        }
        if (job == JobPriority.OUTPUT_COLLECTION) {
            withdrawCollectedSurplus(level, container);
        } else if (job == JobPriority.FOOD_DELIVERY) {
            withdrawFoodForHearth(container);
        } else {
            int capacity = settler.getCarryCapacity();
            int remainingItems = capacity - bagCount();
            if (job == JobPriority.EQUIPMENT_REQUEST) {
                // One request is one physical main-hand tool. Never empty a
                // warehouse stack merely because the bag has room.
                remainingItems = Math.min(1, remainingItems);
            }
            if (reservedFuel) {
                remainingItems = Math.min(remainingItems,
                    liveFuelItemDeficit(level));
            } else if (isHunterAmmunitionRoute(level)) {
                remainingItems = Math.min(remainingItems,
                    liveHunterArrowDeficit(level));
            }
            for (int slot = 0; slot < container.getContainerSize()
                    && remainingItems > 0; slot++) {
                if (job == JobPriority.EQUIPMENT_REQUEST
                    && slot != equipmentSourceSlot) {
                    continue;
                }
                ItemStack stack = container.getItem(slot);
                // Reserved by exact ITEM, not by the recipe's whole ingredient:
                // this exact item was confirmed sitting in this exact chest when
                // the job was claimed, so that is what gets fetched -- not just
                // anything else the recipe's tag would also accept.
                if (stack.isEmpty() || !stack.is(reservedItem)
                    || !ItemStack.isSameItemSameComponents(stack, reservedStack)) {
                    continue;
                }
                int room = Math.min(roomFor(stack), remainingItems);
                if (room <= 0) {
                    break;
                }
                int want = Math.min(stack.getCount(), room);
                // Destination-first (D-A2a-3): remove from the real chest, bank
                // into the bag, and give back whatever the bag would not take,
                // so an interruption between these lines still leaves the item
                // somewhere real.
                ItemStack removed = container.removeItem(slot, want);
                if (removed.isEmpty()) {
                    continue;
                }
                int got = removed.getCount();
                ItemStack leftover = settler.bag.addItem(removed);
                giveBackToChest(container, slot, leftover);
                remainingItems -= got - leftover.getCount();
                playAt(ModSounds.ITEM_PICKUP.get(), 0.5F,
                    0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        int bagAfter = bagCount();
        if (bagAfter > bagBefore) {
            Settlement active = settler.settlement();
            int moved = bagAfter - bagBefore;
            if (job == JobPriority.EQUIPMENT_REQUEST
                && (active == null || equipmentRequestId == null
                    || !EquipmentRequests.markPickedUp(level, active,
                        equipmentRequestId, settler))) {
                // The inventory move happened, but the proof edge did not.
                // Put the exact item straight back while this exact source is
                // still loaded; never continue with an untracked bag load.
                if (rollbackEquipmentPickup(container, equipmentSourceSlot,
                        moved)) {
                    reportStop(StopReason.WAITING_INPUT, sourcePos, null);
                    done = true;
                    releaseReservation();
                } else if (active != null && equipmentRequestId != null) {
                    EquipmentRequests.block(level, active, equipmentRequestId,
                        settler.getUUID(), RequestBlocker.FINGERPRINT_MISMATCH);
                    done = true;
                }
                return;
            }
            AuthorityTelemetry.Event event = job == JobPriority.EQUIPMENT_REQUEST
                ? AuthorityTelemetry.Event.EQUIPMENT_ITEM_PICKED_UP
                : AuthorityTelemetry.Event.COURIER_ITEM_PICKED_UP;
            AuthorityTelemetry.emit(level, event,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(
                    active == null ? null : active.id,
                    "source:" + sourceWarehouseId, 0, 0, bagBefore,
                    bagAfter, itemId(reservedItem), moved, moved, 0,
                    job.name().toLowerCase(java.util.Locale.ROOT)));
        }
        if (bagCount() <= 0) {
            // Reserved, but empty-handed on arrival -- a player took it by
            // hand between the reservation and now, or rearranged the
            // chest (or, for a collection, the surplus fell back under the
            // keep-back). Chest truth is re-read here, never assumed from
            // reservation time; nothing was picked up, so nothing is lost.
            Settlement s = settler.settlement();
            Building source = s == null ? null : findBuildingById(s, sourceWarehouseId);
            reportStop(StopReason.WAITING_INPUT,
                source == null ? sourcePos : source.plaquePos, source);
            done = true;
            releaseReservation();
            return;
        }
        // The source->bag mutation above is the physical grip. Publish both
        // cues on this exact tick, then let the remaining lift frames recover
        // before navigation can take ownership of the feet.
        liftContactCommitted = true;
        playAt(ModSounds.CRATE_GRIP.get(), 0.7F,
            0.95F + settler.getRandom().nextFloat() * 0.1F);
    }

    private void finishWithdrawalTravel() {
        settler.clearWorkContainer();
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
     * ({@link #keepBackFor}; zero for a {@link #GATHERING_BUILDINGS}
     * member), counted across the WHOLE building's chests and re-read now -- the
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
     * item out of the warehouse chest, but only up to the larger of the
     * larder's live meal deficit and the live deficit for this exact edible
     * recruitment-price item. Both are re-read RIGHT NOW, exactly the way
     * {@link #withdrawCollectedSurplus} re-reads its surplus, because the
     * stock the job was claimed on is however many ticks old. Capping at
     * real deficit rather than a full bag means the route never drains a
     * warehouse of food the village does not yet need. The exact-price
     * branch matters when the scalar meal target is already full of the
     * wrong food: potatoes keep people fed, but cannot pay a bread line.
     */
    private void withdrawFoodForHearth(Container container) {
        HearthBlockEntity hearth = settler.hearth();
        Settlement s = settler.settlement();
        if (hearth == null || s == null
            || !(settler.level() instanceof ServerLevel level)) {
            return; // razed while walking: take nothing; the empty-bag path stands down
        }
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level, s,
            RecruitmentPolicy.stageFor(s));
        int mealDeficit = Math.max(0,
            assessment.courierReadyFoodTarget() - hearth.countFoodUnits());
        int exactPriceDeficit = RecruitmentPolicy.missingReadyFoodPrices(
                hearth.getInventory(), assessment.price()).stream()
            .filter(need -> need.matches(reservedStack))
            .findFirst()
            .map(RecruitmentPolicy.ReadyPriceNeed::missing)
            .orElse(0);
        int deficit = Math.max(mealDeficit, exactPriceDeficit);
        int want = Math.min(deficit, settler.getCarryCapacity() - bagCount());
        for (int slot = 0; slot < container.getContainerSize() && want > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !stack.is(reservedItem)
                || !ItemStack.isSameItemSameComponents(stack, reservedStack)) {
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

    /** Emergency rollback for a refused equipment provenance edge. */
    private boolean rollbackEquipmentPickup(Container source, int sourceSlot,
                                            int count) {
        if (sourceSlot < 0 || sourceSlot >= source.getContainerSize()
            || count != 1 || reservedStack.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack inBag = settler.bag.getItem(slot);
            if (inBag.isEmpty()
                || !ItemStack.isSameItemSameComponents(inBag, reservedStack)) {
                continue;
            }
            ItemStack removed = settler.bag.removeItem(slot, 1);
            if (removed.isEmpty()) {
                return false;
            }
            ItemStack atSource = source.getItem(sourceSlot);
            if (atSource.isEmpty()) {
                source.setItem(sourceSlot, removed);
            } else if (ItemStack.isSameItemSameComponents(atSource, removed)
                && atSource.getCount() < Math.min(source.getMaxStackSize(),
                    atSource.getMaxStackSize())) {
                atSource.grow(1);
                source.setItem(sourceSlot, atSource);
            } else {
                ItemStack leftover = settler.bag.addItem(removed);
                if (!leftover.isEmpty()) {
                    // The item still remains real in the returned value; no
                    // destructive fallback is allowed here.
                    settler.bag.setItem(slot, leftover);
                }
                return false;
            }
            source.setChanged();
            return true;
        }
        return false;
    }

    private void tickToCrafter() {
        if (!(settler.level() instanceof ServerLevel level)) {
            done = true;
            releaseReservation();
            return;
        }
        Settlement s = settler.settlement();
        Building crafter = s == null ? null : findBuildingById(s, craftBuildingId);
        if (craftDropOff == null) {
            reportStop(StopReason.RESTING_AFTER_FAIL,
                crafter == null ? null : crafter.plaquePos, crafter);
            done = true;
            releaseReservation();
            return;
        }
        if (crafter == null) {
            // Dissolved mid-trip: the raw material goes back to the
            // warehouse it came from, not into a building that no longer
            // exists.
            reportStop(StopReason.RESTING_AFTER_FAIL, craftDropOff, null);
            beginReturn();
            return;
        }
        workTicks++;
        playHaulSounds();
        settler.getLookControl().setLookAt(craftDropOff.getX() + 0.5,
            craftDropOff.getY() + 0.6, craftDropOff.getZ() + 0.5);
        if (hasContainerContact(level, craftDropOff)) {
            settler.getNavigation().stop();
            beginSetDown(Mode.DEPOSITING);
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            boolean progressing = notePathProgress(craftDropOff);
            if (stuckChecks > HAUL_STUCK_LIMIT
                || routeChecks > HAUL_ROUTE_CHECK_LIMIT) {
                giveUp(crafter.plaquePos, crafter);
            } else if (!progressing || settler.getNavigation().isDone()) {
                pathToChest(craftDropOff);
            }
        }
    }

    private void tickDepositing() {
        if (tickSetDownPhase()) {
            return;
        }
        if (!tickBagToChestCommitGate()) {
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
            reportStop(StopReason.RESTING_AFTER_FAIL, craftDropOff, null);
            beginReturn();
            return;
        }
        if (job == JobPriority.EQUIPMENT_REQUEST
            && (equipmentRequestId == null
                || EquipmentRequests.byId(s, equipmentRequestId) == null)) {
            // Dismissed/reassigned while the courier was walking. Return the
            // still-real tool to its source rather than stocking the old post.
            beginReturn();
            return;
        }
        if (!hasContainerContact(level, craftDropOff)) {
            resumeChestTravel(Mode.TO_CRAFTER, craftDropOff);
            return;
        }
        if (job == JobPriority.CRAFTER_RESTOCK
            && transportRequestId != null) {
            tickTypedAmmunitionDelivery(level, s, crafter);
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
            if (stack.isEmpty() || stack.getCount() < bagTransferUnit.getCount()
                || !ItemStack.isSameItemSameComponents(stack, bagTransferUnit)) {
                continue;
            }
            // The renderer labels this exact bundle count. Capacity/fuel truth
            // may reduce a later cycle, never enlarge the current transaction.
            int allowed = bagTransferUnit.getCount();
            if (reservedFuel) {
                // Claim-time truth is not delivery-time truth: another
                // courier or the player may have filled the firebox while
                // this load was on the road. Offer only the live deficit and
                // carry every surplus item back to its source warehouse.
                allowed = Math.min(allowed, liveFuelItemDeficit(level));
            } else if (isHunterAmmunitionRoute(level)) {
                // Another delivery or the player may have filled the Lodge
                // after claim. Return every surplus shaft to the source.
                allowed = Math.min(allowed, liveHunterArrowDeficit(level));
            }
            if (allowed <= 0) {
                releaseReservation();
                clearPublishedStop();
                beginReturn();
                return;
            }
            ItemStack offered = stack.copyWithCount(allowed);
            ItemStack leftover = storage.insertAt(level, crafter, craftDropOff,
                offered);
            int inserted = allowed - leftover.getCount();
            routeInsertedItems += Math.max(0, inserted);
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            if (inserted > 0) {
                markBagTransferCommitted();
                settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            }
            ItemStack kept = stack.copy();
            kept.shrink(inserted);
            settler.bag.setItem(i, kept.isEmpty() ? ItemStack.EMPTY : kept);
            if (inserted > 0 && job == JobPriority.EQUIPMENT_REQUEST
                && equipmentRequestId != null) {
                // Commit TARGET only after the bag was physically shrunk and
                // the exact loaded destination proves its +1 delta.
                if (EquipmentRequests.markDelivered(level, s,
                        equipmentRequestId, settler, craftDropOff)) {
                    equipmentRequestId = null;
                } else {
                    EquipmentRequests.block(level, s, equipmentRequestId,
                        settler.getUUID(), RequestBlocker.FINGERPRINT_MISMATCH);
                    reportStop(StopReason.RESTING_AFTER_FAIL,
                        crafter.plaquePos, crafter);
                    done = true;
                    return;
                }
            }
            if (inserted < allowed) {
                // Crafter's chests filled mid-delivery: the rest goes back
                // to the warehouse, not into the void.
                reportStop(StopReason.CHEST_FULL, crafter.plaquePos, crafter);
                beginReturn();
            } else if (reservedFuel && !kept.isEmpty()
                && liveFuelItemDeficit(level) <= 0) {
                // The live unit cap intentionally left part of the claimed
                // load in the bag. It belongs back on the warehouse shelf,
                // not above the firebox target.
                releaseReservation();
                clearPublishedStop();
                beginReturn();
            } else if (bagCount() == 0) {
                bagToChestCompletionPending = true;
            }
            return;
        }
        bagToChestCompletionPending = true;
    }

    private void tickTypedAmmunitionDelivery(ServerLevel level,
                                               Settlement settlement,
                                               Building lodge) {
        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            level, settlement, settler);
        RequestRecord request = route.request();
        if (request == null || request.type() != RequestType.AMMUNITION
            && request.type() != RequestType.MATERIAL_INPUT
            || !request.id().equals(transportRequestId)) {
            settler.workerLifecycle().interrupt();
            done = true;
            return;
        }
        int deliveredBefore = request.deliveredCount();
        RequestLedgerService.Decision result = RequestLedgerService.deliver(
            level, settlement, transportRequestId, settler,
            bagTransferUnit.getCount());
        RequestRecord committed = result.request();
        int inserted = committed == null ? 0 : Math.max(0,
            committed.deliveredCount() - deliveredBefore);
        routeInsertedItems += inserted;
        if (inserted > 0) {
            markBagTransferCommitted();
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
        }
        if (result.outcome() == RequestLedgerService.Outcome.SATISFIED) {
            consecutiveFailures = 0;
            clearPublishedStop();
            setPlaqueStop(lodge, StopReason.NONE);
            transportRequestId = null;
            settler.workerLifecycle().clear();
            bagToChestCompletionPending = true;
            return;
        }
        if (result.outcome() == RequestLedgerService.Outcome.COMMITTED) {
            return;
        }
        if (result.blocker() == RequestBlocker.TARGET_FULL) {
            reportStop(StopReason.CHEST_FULL, lodge.plaquePos, lodge);
            settler.workerLifecycle().interrupt();
            beginReturn();
            return;
        }
        settler.workerLifecycle().interrupt();
        reportStop(result.blocker() == RequestBlocker.NO_PATH
                ? StopReason.NO_PATH : StopReason.RESTING_AFTER_FAIL,
            lodge.plaquePos, lodge);
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
        if (foodBag.active() && settler.level() instanceof ServerLevel level) {
            RequestRecord saved = foodBag.request(level);
            int deliveredBefore = saved == null ? -1 : saved.deliveredCount();
            CourierFoodBagSession.Result result = foodBag.tick(level);
            RequestRecord current = foodBag.request(level);
            // A full target deliberately keeps the grounded session running.
            // Publish its real ledger blocker during the retry hold as well.
            if (current != null && current.state() == com.hearthstead.settlement.request.RequestState.BLOCKED
                    && current.blocker() == RequestBlocker.TARGET_FULL) {
                reportStop(StopReason.HEARTH_FULL, current.targetContainer(), null);
            } else if (current != null && current.deliveredCount() > deliveredBefore
                    && settler.logisticsStopReason() == StopReason.HEARTH_FULL) {
                clearPublishedStop(); // Capacity returned and a real unit was accepted.
            }
            if (result == CourierFoodBagSession.Result.COMPLETE) {
                routeInsertedItems = saved == null ? 0 : saved.deliveredCount();
                transportRequestId = null;
                settler.workerLifecycle().clear();
                finishHearthDelivery(level);
            } else if (result == CourierFoodBagSession.Result.BLOCKED) {
                settler.workerLifecycle().interrupt();
                reportStop(StopReason.RESTING_AFTER_FAIL, settler.getHearthPos(), null);
                done = true;
            }
            return;
        }
        if (tickSetDownPhase()) {
            return;
        }
        workTicks++;
        if (workTicks % SORT_PERIOD != SORT_MOVE_TICK) {
            return;
        }
        if (transportRequestId != null && settler.level() instanceof ServerLevel level) {
            tickTypedFoodDelivery(level);
            return;
        }
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            reportStop(StopReason.RESTING_AFTER_FAIL, settler.getHearthPos(), null);
            beginReturn(); // razed mid-delivery: the meals go back to the warehouse
            return;
        }
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = hearth.insertGoods(stack.copy());
            int inserted = stack.getCount() - leftover.getCount();
            routeInsertedItems += Math.max(0, inserted);
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            settler.bag.setItem(i, leftover);
            if (!leftover.isEmpty()) {
                reportStop(StopReason.HEARTH_FULL, settler.getHearthPos(), null);
                beginReturn(); // hearth full: the rest goes back, never the floor
            } else if (bagCount() == 0
                && settler.level() instanceof ServerLevel level) {
                finishHearthDelivery(level);
            }
            return; // one stack per cycle -- the animation beat
        }
        if (settler.level() instanceof ServerLevel level) {
            finishHearthDelivery(level);
        }
    }

    private void tickTypedFoodDelivery(ServerLevel level) {
        Settlement settlement = settler.settlement();
        if (settlement == null) { done = true; return; }
        RequestLedgerService.Decision result = RequestLedgerService.deliver(level, settlement,
            transportRequestId, settler);
        if (result.outcome() == RequestLedgerService.Outcome.SATISFIED) {
            routeInsertedItems = result.request().deliveredCount();
            transportRequestId = null;
            settler.workerLifecycle().clear();
            playAt(ModSounds.CHEST_STOW.get(), 0.65F, 1.0F);
            settler.train(com.hearthstead.entity.Attribute.STAMINA, 1.0F);
            finishHearthDelivery(level);
        } else {
            // Exact intent owns this bag, including a partially delivered load.
            // Leave it visibly blocked rather than guessing another destination.
            settler.workerLifecycle().interrupt();
            reportStop(result.blocker() == RequestBlocker.TARGET_FULL ? StopReason.HEARTH_FULL
                : result.blocker() == RequestBlocker.NO_PATH ? StopReason.NO_PATH
                : StopReason.RESTING_AFTER_FAIL, settlement.center, null);
            done = true;
        }
    }

    private void tickTypedTransportPickup(ServerLevel level) {
        // Legacy lift recovery may enter here before the new persisted session exists.
        var data = RequestLedgerSavedData.existing(level);
        var ledger = data == null || settler.settlement() == null ? null
            : data.existing(settler.settlement().id);
        sourceBag.begin(level, ledger == null ? null : ledger.active(transportRequestId));
        if (!sourceBag.active()) done = true;
    }

    private void finishWarehouseDelivery(ServerLevel level, Settlement settlement,
                                         Building warehouse) {
        settler.clearWorkContainer();
        consecutiveFailures = 0;
        // A collection trip holds a ledger key exactly like a restock does;
        // consolidation never has one, so this is a no-op for it.
        releaseReservation();
        Building source = job == JobPriority.OUTPUT_COLLECTION
            && settlement != null
            ? findBuildingById(settlement, sourceWarehouseId) : null;
        noteCompletedDelivery(level, settlement, source, warehouse);
        clearPublishedStop();
        setPlaqueStop(warehouse, StopReason.NONE);
        // A weight-bounded load can be only the first physical leg of one
        // source drain (six logs are 4 + 2). Re-run the full priority ladder
        // before surrendering MOVE so the schedule cannot wedge that remainder.
        mode = Mode.RESELECTING;
    }

    private void finishCrafterDelivery(ServerLevel level, Settlement settlement,
                                       Building crafter) {
        settler.clearWorkContainer();
        // A later scan sees the crafter's real lower need; the lease no
        // longer protects anything after the final physical insertion.
        releaseReservation();
        Building source = settlement == null ? null
            : findBuildingById(settlement, sourceWarehouseId);
        noteCompletedDelivery(level, settlement, source, crafter);
        clearPublishedStop();
        setPlaqueStop(crafter, StopReason.NONE);
        mode = Mode.RESELECTING;
    }

    private void finishHearthDelivery(ServerLevel level) {
        settler.clearWorkContainer();
        consecutiveFailures = 0;
        // EatFromHearthGoal reads this real inventory immediately.
        releaseReservation();
        Settlement settlement = settler.settlement();
        Building source = settlement == null ? null
            : findBuildingById(settlement, sourceWarehouseId);
        noteCompletedDelivery(level, settlement, source, null);
        clearPublishedStop();
        mode = Mode.RESELECTING;
    }

    /**
     * One route, one quest event. Partial/full insert failures do not report a
     * completed delivery; a successful terminal pass reports the exact count
     * already removed from the real Courier bag and accepted by destination.
     */
    private void noteCompletedDelivery(ServerLevel level,
                                       Settlement settlement,
                                       Building source,
                                       Building destination) {
        int inserted = routeInsertedItems;
        routeInsertedItems = 0;
        if (inserted > 0 && settlement != null) {
            boolean internalWarehouseSort = source != null && destination != null
                && source.type == BuildingType.WAREHOUSE && source.id.equals(destination.id);
            if (!internalWarehouseSort) {
                DevelopmentQuests.noteCourierDelivery(level, settlement, settler,
                    source, destination, inserted);
            }
            AuthorityTelemetry.Event event = job == JobPriority.EQUIPMENT_REQUEST
                ? AuthorityTelemetry.Event.EQUIPMENT_ITEM_DELIVERED
                : AuthorityTelemetry.Event.COURIER_ITEM_DELIVERED;
            String target = destination == null ? "hearth"
                : "building:" + destination.id;
            String deliveredItem = job == JobPriority.WAREHOUSE_CONSOLIDATION
                ? "mixed" : itemId(reservedItem);
            AuthorityTelemetry.emit(level, event,
                AuthorityTelemetry.Result.COMMITTED,
                AuthorityTelemetry.Fields.items(settlement.id, target,
                    0, 0, inserted, 0, deliveredItem, inserted, inserted,
                    0, job.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static String itemId(Item item) {
        return item == null ? "none"
            : BuiltInRegistries.ITEM.getKey(item).toString();
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
            clearPublishedStop();
            done = true;
            return;
        }
        boolean backToSource = returnsToSource();
        BlockPos returnPos = backToSource ? sourcePos : settler.getHearthPos();
        Building source = backToSource && settler.settlement() != null
            ? findBuildingById(settler.settlement(), sourceWarehouseId) : null;
        boolean nowhereToGo = returnPos == null
            || (backToSource && source == null)
            || (!backToSource && settler.hearth() == null);
        if (nowhereToGo) {
            // No hearth (or, for a chest-sourced load, no source position
            // at all) -- a razed settlement, which raids are meant to be
            // able to cause. Keep the load and rest the route: without the
            // cooldown, canUse() re-selects RETURNING on the very next tick
            // and this becomes a one-tick busy loop.
            restRoute(StopReason.RESTING_AFTER_FAIL, null, null);
            done = true;
            return;
        }
        double reachSqr = backToSource ? CHEST_REACH_SQR : HEARTH_REACH_SQR;
        settler.getLookControl().setLookAt(returnPos.getX() + 0.5,
            returnPos.getY() + 0.6, returnPos.getZ() + 0.5);
        boolean arrived = settler.level() instanceof ServerLevel level
            && (backToSource
                ? hasContainerContact(level, returnPos)
                : settler.blockPosition().distSqr(returnPos) <= HEARTH_REACH_SQR);
        if (arrived) {
            settler.getNavigation().stop();
            boolean typedAmmunitionReturn = transportRequestId != null
                && job == JobPriority.CRAFTER_RESTOCK;
            int returnBagBefore = bagCount();
            if (!depositReturnedLoad(backToSource)) {
                return;
            }
            if (!typedAmmunitionReturn || bagCount() < returnBagBefore) {
                playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                    0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
            if (bagCount() > 0) {
                // FIX: even the fallback container can be full (or gone) --
                // e.g. the warehouse this restock trip came from is now
                // packed too. Without this, the original consolidation path
                // had no rest here at all, so a full hearth and a full
                // warehouse would ping-pong a courier between them every
                // shift with no cooldown -- the same busy loop
                // RETRY_COOLDOWN exists to prevent on every other leg.
                StopReason full = backToSource
                    ? (source != null && source.type == BuildingType.WAREHOUSE
                        ? StopReason.NO_WAREHOUSE_SPACE : StopReason.CHEST_FULL)
                    : StopReason.HEARTH_FULL;
                restRoute(full, backToSource
                    ? (source == null ? returnPos : source.plaquePos) : returnPos,
                    source);
            }
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            boolean progressing = notePathProgress(returnPos);
            if (stuckChecks > HAUL_STUCK_LIMIT
                || routeChecks > HAUL_ROUTE_CHECK_LIMIT) {
                restRoute(StopReason.NO_PATH,
                    source == null ? returnPos : source.plaquePos, source);
                done = true;
            } else if (!progressing || settler.getNavigation().isDone()) {
                if (backToSource) {
                    pathToChest(returnPos);
                } else {
                    pathAbove(returnPos);
                }
            }
        }
    }

    private boolean depositReturnedLoad(boolean backToSource) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        if (backToSource && !hasContainerContact(level, sourcePos)) {
            resumeChestTravel(Mode.RETURNING, sourcePos);
            return false;
        }
        if (backToSource && transportRequestId != null
            && job == JobPriority.CRAFTER_RESTOCK) {
            Settlement settlement = settler.settlement();
            RequestLedgerService.Decision returned = settlement == null
                ? null : RequestLedgerService.returnAmmunitionToSource(level,
                    settlement, transportRequestId, settler);
            if (returned != null && returned.accepted()) {
                transportRequestId = null;
                settler.workerLifecycle().clear();
                return true;
            }
            // Ledger-owned Arrow cargo is one all-or-nothing source return.
            // A full/changed source retains the complete remainder in the
            // named Courier bag for the next persisted recovery attempt.
            return returned != null
                && returned.blocker() != RequestBlocker.NO_PATH;
        }
        int equipmentReturned = 0;
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
                ItemStack remaining = WarehouseStorage.of(level, source)
                    .insertAt(level, source, sourcePos, stack.copy());
                if (job == JobPriority.EQUIPMENT_REQUEST
                    && ItemStack.isSameItemSameComponents(stack, reservedStack)) {
                    equipmentReturned += stack.getCount() - remaining.getCount();
                }
                settler.bag.setItem(i, remaining);
            } else {
                HearthBlockEntity hearth = settler.hearth();
                if (hearth == null) {
                    continue;
                }
                settler.bag.setItem(i, hearth.insertGoods(stack.copy()));
            }
        }
        if (backToSource && equipmentRequestId != null && bagCount() == 0) {
            Settlement settlement = settler.settlement();
            if (settlement != null && EquipmentRequests.markReturned(level,
                    settlement, equipmentRequestId, settler,
                    equipmentReturned)) {
                equipmentRequestId = null;
            }
        }
        if (backToSource && transportRequestId != null && bagCount() == 0) {
            Settlement settlement = settler.settlement();
            if (settlement != null && RequestLedgerService.cancelAfterReturn(
                    level, settlement, transportRequestId, settler)) {
                transportRequestId = null;
            }
        }
        return true;
    }

    /**
     * Sends whatever is left in the bag back to where it can safely rest,
     * rather than pressing on toward a destination that just proved
     * unreachable, full, or gone.
     */
    private void beginReturn() {
        settler.clearWorkContainer();
        setDownInProgress = false;
        if (returnsToSource()) {
            // Pre-pickup claims may reopen immediately. A traced item already
            // in this bag keeps its claim until depositReturnedLoad proves an
            // exact physical return; releaseReservation refuses that unsafe
            // mid-transit edge.
            releaseReservation();
        }
        mode = Mode.RETURNING;
        resetPathBudget();
        settler.setActivity(SettlerActivity.CARRYING);
        if (returnsToSource()) {
            pathToChest(sourcePos);
        } else {
            pathAbove(settler.getHearthPos());
        }
    }

    /** Whether an undeliverable load goes back to {@link #sourcePos} rather
     *  than the hearth: every chest-sourced route returns to its source --
     *  a food load included, since its destination IS the hearth, so "back
     *  to the hearth" would be pressing on toward the very place that just
     *  proved full, unreachable or gone. */
    private boolean returnsToSource() {
        return job == JobPriority.EQUIPMENT_REQUEST
            || job == JobPriority.CRAFTER_RESTOCK
            || job == JobPriority.OUTPUT_COLLECTION
            || job == JobPriority.FOOD_DELIVERY;
    }

    /**
     * A route failed for real. Rest it for {@link #RETRY_COOLDOWN_TICKS} so
     * the courier does something else instead of re-entering this goal on
     * the very next tick, and bring any load home first.
     */
    private void giveUp(BlockPos target, Building building) {
        restRoute(StopReason.NO_PATH, target, building);
        if (job == JobPriority.WAREHOUSE_CONSOLIDATION && mode == Mode.TO_WAREHOUSE
                && transportRequestId == null && hearthBag.deferCompletedDelivery()) {
            done = true;
            return;
        }
        if (transportRequestId != null
            && settler.level() instanceof ServerLevel level) {
            Settlement settlement = settler.settlement();
            if (settlement != null) {
                RequestLedgerService.block(level, settlement, transportRequestId,
                    settler, RequestBlocker.NO_PATH);
            }
            // A typed IN_TRANSIT load stays in the proved Courier bag. It is
            // retried against its exact target; silently retargeting or
            // returning it would sever the persisted ownership trace.
            done = true;
            return;
        }
        if (equipmentRequestId != null
            && settler.level() instanceof ServerLevel level) {
            Settlement settlement = settler.settlement();
            if (settlement != null) {
                EquipmentRequests.block(level, settlement, equipmentRequestId,
                    settler.getUUID(), RequestBlocker.NO_PATH);
            }
        }
        if (bagCount() > 0) {
            beginReturn();
        } else {
            done = true;
            releaseReservation();
        }
    }

    /** Transient walking-pace modifier from the Courier trade skill. */
    public static final net.minecraft.resources.ResourceLocation TRADE_PACE_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("hearthstead", "courier_trade_pace");

    /**
     * Trade skill (secondary, Stamina, level 5+): a small walking-pace bonus
     * only while this goal runs (transient: never saved, removed in stop()).
     * Level 1-4 adds nothing, so their movement is exactly as before.
     */
    private void applyTradePace() {
        if (settler.level().isClientSide() || settler.getProfession() != Profession.COURIER) {
            return;
        }
        double pace = com.hearthstead.entity.SkillLevels.paceBonus(settler);
        var speed = settler.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(TRADE_PACE_ID);
        if (pace > 0.0D) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                TRADE_PACE_ID, pace,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private void clearTradePace() {
        var speed = settler.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(TRADE_PACE_ID);
        }
    }

    /**
     * Stops this goal being re-selectable for {@link #RETRY_COOLDOWN_TICKS}.
     * Every path that abandons a trip goes through here: a goal that ends and
     * immediately qualifies again is the wedge shape from KF-013, not a fix
     * for it.
     */
    private void restRoute(StopReason reason, BlockPos target, Building building) {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 8);
        int rest = Math.min(RETRY_COOLDOWN_TICKS,
            FIRST_REST_TICKS * (1 << (consecutiveFailures - 1)));
        // Runners' Guild (tech tree): half the rest after a blocked route.
        rest = com.hearthstead.settlement.techtree.effects.LogisticsEffects.courierRestTicks(
            settler.level() instanceof ServerLevel serverLevel ? serverLevel : null,
            settler.settlement(), rest);
        cooldownUntil = settler.level().getGameTime() + rest;
        // A courier that quietly stops working is indistinguishable from one
        // that has nothing to do. Say which leg failed (KF-014).
        settler.recordRouteFailure("courier:" + mode + ":stuck" + stuckChecks
            + ":rest" + rest + ":run" + consecutiveFailures);
        reportStop(reason, target, building, rest);
    }

    /**
     * Converts the crafter's live fuel-unit shortfall into the smallest
     * number of this trip's reserved physical item. Dense charcoal therefore
     * tops an eight-unit reserve up with four items, while logs need eight.
     * This is re-read at withdrawal so another delivery cannot make this
     * courier overstock the firebox from stale claim-time data.
     */
    private int liveFuelItemDeficit(ServerLevel level) {
        Settlement s = settler.settlement();
        Building crafter = s == null ? null : findBuildingById(s, craftBuildingId);
        int unitsPerItem = reservedItem == null ? 0
            : Fuel.unitsPerItem(new ItemStack(reservedItem));
        if (crafter == null || unitsPerItem <= 0 || !Fuel.burns(crafter.type)) {
            return 0;
        }
        int targetUnits = FUEL_RESERVE_BATCHES * Fuel.unitsPerBatch(crafter.type);
        int missingUnits = Math.max(0,
            targetUnits - countFuelUnits(liveContainers(level, crafter)));
        return itemsForUnits(missingUnits, unitsPerItem);
    }

    private boolean isHunterAmmunitionRoute(ServerLevel level) {
        Settlement settlement = settler.settlement();
        Building target = settlement == null ? null
            : findBuildingById(settlement, craftBuildingId);
        return job == JobPriority.CRAFTER_RESTOCK
            && target != null && target.valid
            && RequestLedgerService.ammunitionTarget(target)
            && reservedItem == Items.ARROW;
    }

    private int liveHunterArrowDeficit(ServerLevel level) {
        Settlement settlement = settler.settlement();
        Building target = settlement == null ? null
            : findBuildingById(settlement, craftBuildingId);
        if (target == null || !target.valid
            || !RequestLedgerService.ammunitionTarget(target)
            || reservedItem != Items.ARROW) {
            return 0;
        }
        return Math.max(0, SettlerEntity.ARCHER_QUIVER_CAPACITY
            - countItemIn(liveContainers(level, target), Items.ARROW));
    }

    private void playAt(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        // Deferred one tick so the accent rides the same flush as the synced
        // activity/prop state its clip starts from (WorkSoundSync); 0.8x keeps
        // the courier's handling sounds under the footsteps beside them.
        if (settler.level() instanceof ServerLevel serverLevel) {
            WorkSoundSync.playExact(serverLevel, settler.getX(), settler.getY() + 0.6,
                settler.getZ(), sound, volume * 0.8F,
                pitch * (0.96F + settler.getRandom().nextFloat() * 0.08F));
        }
    }

    @Override
    public void stop() {
        settler.workerLifecycle().interrupt();
        clearTradePace();
        if (sourceBag.active()) sourceBag.interrupt();
        if (hearthBag.active()) hearthBag.interrupt();
        if (!foodBag.active() && !sourceBag.active() && !hearthBag.active()) {
            closeAndClearBagTransfer(mode == Mode.SORTING ? dropOff : craftDropOff);
            settler.clearWorkContainer();
        }
        setDownInProgress = false;
        liftContactCommitted = false;
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

    /** Restores one durable Warehouse-to-staffed-Tavern food or bottle route. */
    private boolean adoptTavernRestockRoute(ServerLevel level,
                                             RequestLedgerService.Route route) {
        RequestRecord request = route.request();
        if (request == null || request.type() != RequestType.MATERIAL_INPUT
            || route.source() == null || route.target() == null) return false;
        ItemStack exact = request.fingerprint().prototype(level.registryAccess());
        if (exact.isEmpty() || (route.target().type != BuildingType.TRADING_POST
            && route.target().type != BuildingType.BUILDERS_HUT // BUILDER lane
            && TavernHostService.restockKind(exact) == null)) return false;
        Mode recovered;
        if (route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE) recovered = Mode.TO_SOURCE;
        else if (route.phase() == RequestLedgerService.RoutePhase.TO_TARGET) recovered = Mode.TO_CRAFTER;
        else if (route.phase() == RequestLedgerService.RoutePhase.BLOCKED
            && request.effectiveState() == com.hearthstead.settlement.request.RequestState.IN_TRANSIT
            && ownsExactTransportBag(request, exact)) recovered = Mode.RETURNING;
        else return false;
        transportRequestId = request.id();
        settler.workerLifecycle().recovering(new WorkerLifecycle.TaskRef(request.settlementId(), request.id()));
        job = JobPriority.CRAFTER_RESTOCK;
        sourceWarehouseId = request.sourceBuildingId(); sourcePos = request.sourceContainer();
        craftBuildingId = request.targetBuildingId(); craftDropOff = request.targetContainer();
        warehouseId = null; dropOff = null; reservedItem = exact.getItem();
        reservedStack = exact.copyWithCount(1); reservedFuel = false; reservationKey = null;
        routeInsertedItems = request.deliveredCount(); mode = recovered;
        return true;
    }
    // ------------------------------------------------------ restock scan ---

    /**
     * Highest-priority request lane: one serviceable physical tool from a
     * warehouse to the named worker's own workplace chest. The worker takes
     * it from there through {@link EquipmentRequests#equipFromWorkplace}; the
     * courier never equips at a distance and this board never owns an item.
     */
    private EquipmentJob findEquipmentJob(ServerLevel level, Settlement s) {
        long now = level.getGameTime();
        for (EquipmentRequest request : EquipmentRequests.list(level, s)) {
            Building workplace = findBuildingById(s,
                request.destinationBuildingId());
            if (workplace == null) {
                continue;
            }
            EquipmentRequests.reconcile(level, s, workplace, request);
            if (EquipmentRequests.byId(s, request.id()) != request
                || request.status() != EquipmentRequest.Status.OPEN) {
                continue;
            }
            List<Held> destination = liveContainers(level, workplace);
            if (destination.isEmpty()) {
                observeStop(StopReason.CHEST_FULL, workplace.plaquePos, workplace);
                continue;
            }
            boolean sawWarehouse = false;
            boolean sawMatchingTool = false;
            for (Building warehouse : s.buildings) {
                if (!warehouse.valid || warehouse.type != BuildingType.WAREHOUSE) {
                    continue;
                }
                sawWarehouse = true;
                for (Held stock : liveContainers(level, warehouse)) {
                    Container source = stock.container();
                    for (int slot = 0; slot < source.getContainerSize(); slot++) {
                        ItemStack candidate = source.getItem(slot);
                        if (!request.requirement().serviceable(candidate)) {
                            continue;
                        }
                        sawMatchingTool = true;
                        Held destinationChest = firstRoomForExact(destination,
                            candidate);
                        if (destinationChest == null) {
                            observeStop(StopReason.CHEST_FULL,
                                workplace.plaquePos, workplace);
                            continue;
                        }
                        RestockKey key = RestockKey.forItem(workplace.id,
                            candidate.getItem());
                        if (isReservedByOther(key, now)) {
                            observeStop(StopReason.RESERVED_BY_OTHER,
                                workplace.plaquePos, workplace);
                            continue;
                        }
                        if (!EquipmentRequests.claim(level, s, request.id(),
                                settler.getUUID())) {
                            continue;
                        }
                        if (!EquipmentRequests.bindRoute(level, s, request.id(),
                                settler, warehouse, stock.pos(), slot,
                                workplace, destinationChest.pos(),
                                candidate.copyWithCount(1))) {
                            EquipmentRequests.release(level, s, request.id(),
                                settler.getUUID());
                            continue;
                        }
                        if (!reserve(key, now)) {
                            EquipmentRequests.release(level, s, request.id(),
                                settler.getUUID());
                            observeStop(StopReason.RESERVED_BY_OTHER,
                                workplace.plaquePos, workplace);
                            continue;
                        }
                        return new EquipmentJob(request, workplace,
                            destinationChest.pos(), warehouse, stock.pos(),
                            slot, candidate.copyWithCount(1), key);
                    }
                }
            }
            if (!sawWarehouse) {
                observeStop(StopReason.NO_WAREHOUSE_SPACE,
                    workplace.plaquePos, workplace);
            } else if (!sawMatchingTool) {
                observeStop(StopReason.WAITING_INPUT,
                    workplace.plaquePos, workplace);
            }
        }
        return null;
    }

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
        if (RequestLedgerService.validCourier(level, s, settler)) {
            RequestLedgerService.Decision queued = RequestLedgerService
                .claimNextAmmunition(level, s, settler);
            RestockJob claimed = ammunitionRestockJob(level, s,
                queued.request());
            if (queued.accepted() && claimed != null) {
                return claimed;
            }
        }
        for (Building crafter : restockOrder(level, s, now)) {
            if (!crafter.valid) {
                continue;
            }
            boolean hunterAmmunition = RequestLedgerService.ammunitionTarget(crafter);
            boolean burns = Fuel.burns(crafter.type);
            if (!hunterAmmunition
                && !Production.produces(crafter.type) && !burns) {
                continue;
            }
            if (hunterAmmunition
                && !RequestLedgerService.ammunitionBoundsLoaded(level,
                    crafter)) {
                observeStop(StopReason.NO_PATH, crafter.plaquePos, crafter);
                continue;
            }
            List<Held> mine = liveContainers(level, crafter);
            if (mine.isEmpty()) {
                observeStop(StopReason.CHEST_FULL, crafter.plaquePos, crafter);
                continue;
            }
            if (hunterAmmunition) {
                int arrows = countItemIn(mine, Items.ARROW);
                if (arrows < SettlerEntity.ARCHER_QUIVER_CAPACITY) {
                    if (!roomFor(mine, stack -> stack.is(Items.ARROW))) {
                        observeStop(StopReason.CHEST_FULL, crafter.plaquePos,
                            crafter);
                    } else {
                        RestockJob claimed = claimAmmunitionFrom(level, s,
                            crafter, mine);
                        if (claimed != null) {
                            return claimed;
                        }
                    }
                }
                continue;
            }
            if (Production.produces(crafter.type)) {
                for (Production.Recipe recipe : Production.of(crafter.type)) {
                    Ingredient want = recipe.input();
                    // Short means "below MATERIAL_RESERVE_BATCHES batches",
                    // not "below one" -- see that constant's own reasoning.
                    int keep = MATERIAL_RESERVE_BATCHES * recipe.inputCount();
                    if (countMatching(mine, want) >= keep) {
                        continue; // this recipe has its working reserve
                    }
                    if (!roomFor(mine, want)) {
                        observeStop(StopReason.CHEST_FULL, crafter.plaquePos, crafter);
                        continue; // short, but nowhere to put more even if fetched
                    }
                    RestockJob claimed = claimRestockFrom(level, s, crafter, mine,
                        want, false, now);
                    if (claimed != null) {
                        return claimed;
                    }
                }
            }
            // FUEL, same tier: a building that burns ({@code Fuel#burns})
            // holding fewer than FUEL_RESERVE_BATCHES x
            // Fuel.unitsPerBatch(type) fuel UNITS is as stopped as one out
            // of raw material -- the
            // raw material is merely checked first to keep the existing
            // scan order stable, not because it outranks the firebox. The
            // trip this claims is an ordinary restock in every other way:
            // same ledger key, same legs, same conservation discipline.
            if (burns && countFuelUnits(mine)
                    < FUEL_RESERVE_BATCHES * Fuel.unitsPerBatch(crafter.type)) {
                if (!roomFor(mine, Fuel::isFuel)) {
                    observeStop(StopReason.CHEST_FULL, crafter.plaquePos, crafter);
                    continue;
                }
                RestockJob claimed = claimRestockFrom(level, s, crafter, mine,
                    Fuel::isFuel, true, now);
                if (claimed != null) {
                    return claimed;
                }
            }
        }
        return null;
    }

    /**
     * [economy] starvingWorkshopsFirst (plan/ECONOMY.md): the restock scan
     * used to walk {@code s.buildings} in list order, so the first workshops
     * ever placed were always topped up to their four-batch reserve while a
     * later bench sat with nothing to run at all. This keeps the same scan
     * but visits the STARVED benches first -- a producing building that
     * cannot run a single batch right now ({@link Production#ready} is null)
     * -- then everyone else, each group in the old order. Recomputed at most
     * every {@link #RESTOCK_ORDER_TTL} ticks per settlement (a pure read of
     * the benches' chests), shared by every courier.
     */
    private static List<Building> restockOrder(ServerLevel level, Settlement s, long now) {
        if (!com.hearthstead.settlement.economy.EconomyConfig
                .starvingWorkshopsFirst(level.getServer())) {
            return s.buildings;
        }
        RestockOrder cached = RESTOCK_ORDERS.get(s.id);
        if (cached != null && cached.size() == s.buildings.size()
            && now - cached.at() >= 0 && now - cached.at() < RESTOCK_ORDER_TTL) {
            return cached.order();
        }
        List<Building> starved = new ArrayList<>();
        List<Building> rest = new ArrayList<>();
        for (Building building : s.buildings) {
            if (building.valid && Production.produces(building.type)
                && building.bounds != null
                && Production.ready(level, building) == null) {
                starved.add(building);
            } else {
                rest.add(building);
            }
        }
        starved.addAll(rest);
        if (RESTOCK_ORDERS.size() > 64) {
            RESTOCK_ORDERS.clear();
        }
        RESTOCK_ORDERS.put(s.id, new RestockOrder(List.copyOf(starved), s.buildings.size(), now));
        return RESTOCK_ORDERS.get(s.id).order();
    }

    private record RestockOrder(List<Building> order, int size, long at) {
    }

    private static final int RESTOCK_ORDER_TTL = 40;
    private static final Map<UUID, RestockOrder> RESTOCK_ORDERS = new HashMap<>();

    /** Opens and reserves one durable Arrow route inside the restock tier. */
    private RestockJob claimAmmunitionFrom(ServerLevel level,
                                            Settlement settlement,
                                            Building lodge,
                                            List<Held> lodgeContainers) {
        for (Building warehouse : settlement.buildings) {
            if (!warehouse.valid || warehouse.type != BuildingType.WAREHOUSE) {
                continue;
            }
            if (!RequestLedgerService.ammunitionBoundsLoaded(level,
                    warehouse)) {
                continue;
            }
            for (Held stock : liveContainers(level, warehouse)) {
                Container source = stock.container();
                for (int slot = 0; slot < source.getContainerSize(); slot++) {
                    ItemStack arrows = source.getItem(slot);
                    if (arrows.isEmpty() || !arrows.is(Items.ARROW)) {
                        continue;
                    }
                    int requested = Math.min(arrows.getCount(),
                        Math.min(roomFor(arrows),
                            RequestLedgerService.ammunitionDeficit(level,
                                settlement, lodge)));
                    if (requested <= 0) {
                        continue;
                    }
                    for (Held destination : lodgeContainers) {
                        RequestLedgerService.Decision opened =
                            RequestLedgerService.openAmmunitionDelivery(
                                level, settlement, warehouse, stock.pos(), slot,
                                lodge, destination.pos(), requested);
                        if (opened.request() == null) {
                            continue;
                        }
                        RequestLedgerService.Decision reserved =
                            RequestLedgerService.reserve(level, settlement,
                                opened.request().id(), settler);
                        RestockJob job = ammunitionRestockJob(level,
                            settlement, reserved.request());
                        if (reserved.accepted() && job != null) {
                            return job;
                        }
                        if (reserved.blocker()
                                == RequestBlocker.RESERVED_BY_OTHER) {
                            observeStop(StopReason.RESERVED_BY_OTHER,
                                lodge.plaquePos, lodge);
                            return null;
                        }
                    }
                }
            }
        }
        observeStop(StopReason.WAITING_INPUT, lodge.plaquePos, lodge);
        return null;
    }

    private RestockJob ammunitionRestockJob(ServerLevel level,
                                             Settlement settlement,
                                             RequestRecord request) {
        if (request == null || request.type() != RequestType.AMMUNITION
            || request.courierId() == null
            || !request.courierId().equals(settler.getUUID())) {
            return null;
        }
        Building source = findBuildingById(settlement,
            request.sourceBuildingId());
        Building target = findBuildingById(settlement,
            request.targetBuildingId());
        ItemStack exact = request.fingerprint().prototype(
            level.registryAccess());
        if (source == null || source.type != BuildingType.WAREHOUSE
            || !RequestLedgerService.ammunitionTarget(target)
            || exact.isEmpty() || !exact.is(Items.ARROW)) {
            return null;
        }
        return new RestockJob(target, request.targetContainer(), source,
            request.sourceContainer(), exact.getItem(),
            exact.copyWithCount(1), false, null, request.id());
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
                                        boolean fuel, long now) {
        Building firstWarehouse = null;
        boolean hasWarehouseStorage = false;
        boolean foundMatchingStock = false;
        for (Building warehouse : s.buildings) {
            if (warehouse.type != BuildingType.WAREHOUSE || !warehouse.valid) {
                continue;
            }
            if (firstWarehouse == null) {
                firstWarehouse = warehouse;
            }
            List<Held> theirs = liveContainers(level, warehouse);
            hasWarehouseStorage |= !theirs.isEmpty();
            for (Held stock : theirs) {
                Container source = stock.container();
                for (int slot = 0; slot < source.getContainerSize(); slot++) {
                    ItemStack candidate = source.getItem(slot);
                    if (candidate.isEmpty() || !want.test(candidate)) {
                        continue;
                    }
                    foundMatchingStock = true;
                    // A broad tag/predicate match is not physical room. A
                    // partial log stack cannot accept charcoal, and two
                    // stacks of the same item with different components do
                    // not merge. Prove this exact source stack fits before
                    // sending a courier on a top-priority round trip.
                    Held destinationChest = firstRoomForExact(mine, candidate);
                    if (destinationChest == null) {
                        continue;
                    }
                    Item item = candidate.getItem();
                    RestockKey key = fuel
                        ? RestockKey.forFuel(crafter.id)
                        : RestockKey.forItem(crafter.id, item);
                    if (isReservedByOther(key, now)) {
                        observeStop(StopReason.RESERVED_BY_OTHER,
                            crafter.plaquePos, crafter);
                        continue;
                    }
                    if (!reserve(key, now)) {
                        observeStop(StopReason.RESERVED_BY_OTHER,
                            crafter.plaquePos, crafter);
                        continue;
                    }
                    return new RestockJob(crafter, destinationChest.pos(),
                        warehouse, stock.pos(), item,
                        candidate.copyWithCount(1), fuel, key, null);
                }
            }
        }
        if (foundMatchingStock) {
            observeStop(StopReason.CHEST_FULL, crafter.plaquePos, crafter);
        } else if (hasWarehouseStorage) {
            observeStop(StopReason.WAITING_INPUT, crafter.plaquePos, crafter);
        } else {
            Building target = firstWarehouse == null ? crafter : firstWarehouse;
            observeStop(StopReason.NO_WAREHOUSE_SPACE, target.plaquePos, target);
        }
        return null;
    }

    // --------------------------------------------------------- food scan ---

    /**
     * FOOD_DELIVERY (FLOWS.md route 5): an exact edible item named by the
     * recruitment price is supplied first, even when the hearth already has
     * enough other meals. Otherwise, when the larder is below the shared
     * policy target, the first warehouse holding anything edible supplies a
     * hearth-bound top-up trip. The larder is measured with the hearth's own
     * {@code countFoodUnits()}, the exact number
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
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level, s,
            RecruitmentPolicy.stageFor(s));
        for (RecruitmentPolicy.ReadyPriceNeed need
                : RecruitmentPolicy.missingReadyFoodPrices(
                    hearth.getInventory(), assessment.price())) {
            FoodJob priceJob = claimFoodJob(level, s, hearth, need::matches);
            if (priceJob != null) {
                return priceJob;
            }
        }
        int target = assessment.courierReadyFoodTarget();
        if (hearth.countFoodUnits() >= target) {
            return null; // stocked overall; any exact-price miss was reported above
        }
        return claimFoodJob(level, s, hearth, CourierWorkGoal::isEdible);
    }

    /** Finds and claims one matching food stack through the durable ledger. */
    private FoodJob claimFoodJob(ServerLevel level, Settlement s,
                                 HearthBlockEntity hearth,
                                 Predicate<ItemStack> want) {
        Building firstWarehouse = null;
        boolean hasWarehouseStorage = false;
        for (Building warehouse : s.buildings) {
            if (warehouse.type != BuildingType.WAREHOUSE || !warehouse.valid) {
                continue;
            }
            if (firstWarehouse == null) {
                firstWarehouse = warehouse;
            }
            List<Held> theirs = liveContainers(level, warehouse);
            hasWarehouseStorage |= !theirs.isEmpty();
            Held stock = findStock(theirs, want);
            if (stock == null) {
                continue;
            }
            ItemStack candidate = matchingStack(stock.container(), want);
            if (candidate.isEmpty()) {
                continue;
            }
            if (!hearthHasRoomFor(hearth, candidate)) {
                // A hearth crammed full of goods awaiting their first haul:
                // a load lifted now would only bounce straight back to the
                // warehouse -- the ping-pong shape RETRY_COOLDOWN exists to
                // prevent. Consolidation is the route that makes this room.
                observeStop(StopReason.HEARTH_FULL, settler.getHearthPos(), null);
                return null;
            }
            if (!RequestLedgerService.validCourier(level, s, settler)) {
                return null;
            }
            for (int slot = 0; slot < stock.container().getContainerSize(); slot++) {
                ItemStack exact = stock.container().getItem(slot);
                if (!ItemStack.isSameItemSameComponents(exact, candidate)) continue;
                int amount = Math.min(exact.getCount(), Math.min(roomFor(exact),
                    RequestLedgerService.foodDeficit(level, s, exact)));
                if (amount <= 0) continue;
                var opened = RequestLedgerService.openFoodDelivery(level, s, warehouse, stock.pos(), slot, amount);
                if (opened.request() == null) continue;
                var claimed = RequestLedgerService.reserve(level, s, opened.request().id(), settler);
                if (claimed.accepted() && claimed.request() != null) {
                    RequestRecord request = claimed.request();
                    Building actualSource = findBuildingById(s, request.sourceBuildingId());
                    ItemStack claimedStack = request.fingerprint().prototype(level.registryAccess());
                    if (actualSource != null && !claimedStack.isEmpty()) {
                        return new FoodJob(request, actualSource, request.sourceContainer(),
                            claimedStack.getItem(), claimedStack.copyWithCount(1));
                    }
                }
            }
        }
        if (hasWarehouseStorage) {
            observeStop(StopReason.WAITING_INPUT, settler.getHearthPos(), null);
        } else {
            observeStop(StopReason.NO_WAREHOUSE_SPACE,
                firstWarehouse == null ? settler.getHearthPos() : firstWarehouse.plaquePos,
                firstWarehouse);
        }
        return null;
    }

    /**
     * What counts as a meal: the SAME shared predicate the hearth larder and
     * {@code EatFromHearthGoal} consume through. {@link ReadyFood} owns that
     * definition, so recruitment simulation, couriers and actual eating
     * cannot drift onto separate food lists.
     * (Deliberately NOT {@code has(DataComponents.FOOD)}, which
     * {@link #isHaulable} uses for its coarser keep-food-home purpose:
     * {@code getFoodProperties} also honours NeoForge's item-extension
     * overrides, and what matters here is delivering exactly what the
     * eater can actually eat.)
     */
    private static boolean isEdible(ItemStack stack) {
        return ReadyFood.isReadyMeal(stack)
            || com.hearthstead.settlement.work.FishMeals.portions(stack) > 0;
    }

    /** Whether the hearth could accept at least one more of this item --
     *  an empty slot, or a part-stack of the same item. The claim-time
     *  twin of {@link #roomFor}, against the hearth's item handler. */
    private static boolean hearthHasRoomFor(HearthBlockEntity hearth,
                                             ItemStack incoming) {
        var inv = hearth.getInventory();
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack held = inv.getStackInSlot(slot);
            if (held.isEmpty()
                || (ItemStack.isSameItemSameComponents(held, incoming)
                    && held.getCount() < held.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /**
     * One bounded Warehouse -> Tavern service delivery. It runs only after
     * {@link #findFoodJob}: the live Hearth reserve is the hard survival floor,
     * while the Tavern is a convenience store with one food and one bottle row.
     */
    private RestockJob findTavernRestockJob(ServerLevel level, Settlement settlement) {
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) return null;
        RecruitmentPolicy.Assessment assessment = RecruitmentPolicy.assess(level,
            settlement, RecruitmentPolicy.stageFor(settlement));
        boolean hearthNeedsFood = hearth.countFoodUnits() < assessment.courierReadyFoodTarget();
        if (RequestLedgerService.validCourier(level, settlement, settler)) {
            RequestLedgerService.Decision queued = RequestLedgerService
                .claimNextMaterialInput(level, settlement, settler);
            RestockJob claimed = tavernRestockJob(level, settlement, queued.request());
            if (queued.accepted() && claimed != null) return claimed;
        }
        for (Building tavern : settlement.buildings) {
            if (!tavern.valid || tavern.type != BuildingType.TAVERN) continue;
            for (TavernHostService.RestockKind kind : TavernHostService.RestockKind.values()) {
                // A ready meal belongs below the Hearth survival reserve, but
                // a reusable service glass cannot feed the Hearth and must not
                // deadlock a hungry Tavern after Courier collected its return.
                if (hearthNeedsFood && kind == TavernHostService.RestockKind.READY_MEAL) continue;
                for (Building warehouse : settlement.buildings) {
                    if (!warehouse.valid || warehouse.type != BuildingType.WAREHOUSE) continue;
                    for (Held stock : liveContainers(level, warehouse)) {
                        Container source = stock.container();
                        for (int slot = 0; slot < source.getContainerSize(); slot++) {
                            ItemStack candidate = source.getItem(slot);
                            if (TavernHostService.restockKind(candidate) != kind) continue;
                            TavernHostService.RestockNeed need = TavernHostService.restockNeed(
                                level, settlement, tavern, candidate);
                            if (need == null || need.deficit() <= 0) continue;
                            BlockPos targetPos = need.container();
                            int requested = Math.min(candidate.getCount(),
                                Math.min(roomFor(candidate), need.deficit()));
                            RequestLedgerService.Decision opened = RequestLedgerService.openTavernRestock(
                                level, settlement, warehouse, stock.pos(), slot, tavern,
                                targetPos, requested);
                            if (opened.request() == null) continue;
                            RequestLedgerService.Decision reserved = RequestLedgerService.reserve(
                                level, settlement, opened.request().id(), settler);
                            RestockJob job = tavernRestockJob(level, settlement, reserved.request());
                            if (reserved.accepted() && job != null) return job;
                            if (reserved.blocker() == RequestBlocker.RESERVED_BY_OTHER) return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    private RestockJob tavernRestockJob(ServerLevel level, Settlement settlement,
                                        RequestRecord request) {
        if (request == null || request.type() != RequestType.MATERIAL_INPUT
            || request.courierId() == null || !request.courierId().equals(settler.getUUID())) return null;
        Building source = findBuildingById(settlement, request.sourceBuildingId());
        Building target = findBuildingById(settlement, request.targetBuildingId());
        ItemStack exact = request.fingerprint().prototype(level.registryAccess());
        if (source == null || source.type != BuildingType.WAREHOUSE
            || target == null || (target.type != BuildingType.TAVERN && target.type != BuildingType.TRADING_POST
                && target.type != BuildingType.BUILDERS_HUT) // BUILDER lane: hut material parcels
            || exact.isEmpty() || (target.type == BuildingType.TAVERN
                && TavernHostService.restockKind(exact) == null)) return null;
        return new RestockJob(target, request.targetContainer(), source,
            request.sourceContainer(), exact.getItem(), exact.copyWithCount(1),
            false, null, request.id());
    }
    // --------------------------------------------------- collection scan ---

    /**
     * Building types whose own chests receive world-gathered yield rather
     * than {@code Production} recipe output. {@link #isGatheredOutput}
     * defines the exact output set for mixed-use chests. {@link BuildingType#MINE}
     * was always this shape
     * (see the historic notes on {@link #OUTPUT_KEEP_BACK} and
     * {@link #keepBackFor}); {@code PASTURE}, {@code FISHERY} and
     * {@code HUNTERS_LODGE} (worked by {@code HerderWorkGoal},
     * {@code FisherWorkGoal} and {@code HunterWorkGoal}) are the identical
     * shape and hit the identical bug for the want of this generalisation
     * (adversarial review FINDING 1, 2026-08-26): {@link #findCollectionJob}
     * recognised only {@code MINE} by name, so the herder's wool and eggs,
     * the fisher's cod and the hunter's meat and hides sat in their own
     * chests forever -- no courier ever collected them, so no warehouse and
     * no hungry settler ever saw them either.
     *
     * <p>Deliberately NOT "any {@code BuildingType} with no Production
     * table". A residential or hub building (a house, a watchtower, a
     * library) holds no self-produced surplus at all -- a watchtower's
     * arrows are RESTOCKED into it for the archer to draw down, never
     * "collected" back out -- so a blanket no-recipe-table predicate would
     * have a courier haul a garrison's own arrows, or a player's own
     * storage, out from under it. The positive filters keep a gathering
     * building's seeds, tools and ammunition at their physical endpoint
     * while its real field output remains collectable.
     */
    private static final EnumSet<BuildingType> GATHERING_BUILDINGS = EnumSet.of(
        BuildingType.MINE, BuildingType.FARMHOUSE, BuildingType.LUMBER_CAMP,
        BuildingType.PASTURE, BuildingType.FISHERY,
        BuildingType.HUNTERS_LODGE);

    private static boolean isGathering(BuildingType type) {
        return GATHERING_BUILDINGS.contains(type);
    }

    /**
     * COLLECTION (FLOWS.md route 4 / SOURCE-OUT): the first producing OR
     * gathering building sitting on more of one of its validated OUTPUT items
     * than its keep-back ({@link #OUTPUT_KEEP_BACK}; a
     * {@link #GATHERING_BUILDINGS} member has no Production table at all and
     * therefore uses {@link #isGatheredOutput} to separate gathered yield
     * from seeds, tools and ammunition), paired with the first warehouse with room for
     * that item. No warehouse, or no room in any: no job -- this route
     * exists to un-strand goods, and a load lifted with nowhere to put it
     * down would only re-strand them in a bag (nothing is ever dropped, and
     * nothing is ever voided).
     *
     * <p>Only OUTPUTS move. FARMHOUSE and LUMBER_CAMP use explicit output
     * filters because those starter workplaces also hold seeds and requested
     * tools. An item sitting in a workshop's chest because
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
        return findCollectionJob(level, s, false);
    }

    private CollectionJob findCollectionJob(ServerLevel level, Settlement s, boolean tidy) {
        // Never mint an OUTPUT_PICKUP before the worker can truthfully own it.
        // Otherwise a malformed/direct profession assignment leaves an OPEN
        // row counted as reserved stock and the real output becomes invisible
        // to every later scan.
        if (!RequestLedgerService.validCourier(level, s, settler)) {
            return null;
        }
        // Persisted intents win before a fresh world scan. The reservation
        // call is synchronous on the server thread, so two Couriers looking
        // at one OPEN row in the same tick still produce exactly one owner.
        if (!tidy) {
            RequestLedgerService.Decision queued = RequestLedgerService
                .claimNextOutput(level, s, settler);
            CollectionJob claimed = collectionJob(level, s, queued.request());
            if (queued.accepted() && claimed != null) {
                rememberCollectionSource(claimed);
                return claimed;
            }
        }
        List<Building> sources = new ArrayList<>();
        for (Building source : s.buildings) {
            if (!source.valid || source.type == BuildingType.WAREHOUSE || source.type == BuildingType.TRADING_POST
                || source.type == BuildingType.BUILDERS_HUT) { // BUILDER lane: construction stock stays put
                continue; // a warehouse is this route's destination, never its source
            }
            if (!tidy && !isGathering(source.type) && !Production.produces(source.type)) {
                continue; // makes nothing: it has no "output" to collect
            }
            sources.add(source);
        }
        int firstSource = tidy ? 0 : sourceIndexAfterCursor(sources);
        for (int offset = 0; offset < sources.size(); offset++) {
            Building source = sources.get((firstSource + offset) % sources.size());
            List<Held> theirs = liveContainers(level, source);
            if (theirs.isEmpty()) {
                continue;
            }
            Item item = tidy ? findTidyItem(theirs, source.type) : findSurplusOutput(theirs, source.type);
            if (item == null) {
                continue;
            }
            int surplus = countItemIn(theirs, item)
                - (tidy ? 0 : keepBackFor(source.type, item))
                - RequestLedgerService.reservedOutputCount(level, s, source,
                    item);
            if (surplus <= 0) {
                continue;
            }
            Building firstWarehouse = null;
            for (Building warehouse : s.buildings) {
                if (warehouse.type != BuildingType.WAREHOUSE || !warehouse.valid) {
                    continue;
                }
                if (firstWarehouse == null) {
                    firstWarehouse = warehouse;
                }
                List<Held> store = liveContainers(level, warehouse);
                for (Held stock : theirs) {
                    Container sourceContainer = stock.container();
                    for (int slot = 0; slot < sourceContainer.getContainerSize(); slot++) {
                        ItemStack stack = sourceContainer.getItem(slot);
                        if (stack.isEmpty() || !stack.is(item)
                                || tidy && !mayTidy(source.type, stack)) {
                            continue;
                        }
                        int requested = Math.min(stack.getCount(),
                            Math.min(surplus, roomFor(stack)));
                        if (requested <= 0) {
                            continue;
                        }
                        var sorted = com.hearthstead.settlement.warehouse.WarehouseSorting.groupOf(stack) == null
                            ? null : com.hearthstead.settlement.warehouse.WarehouseSorting.destinations(level, warehouse, stack);
                        for (Held destination : store) {
                            if (sorted != null && !sorted.contains(destination.pos())) continue;
                            if (destination.pos().equals(stock.pos())) continue; // shared chest: never its own destination
                            RequestLedgerService.Decision opened =
                                RequestLedgerService.openOutputPickup(level, s,
                                    source, stock.pos(), slot, warehouse,
                                    destination.pos(), requested,
                                    RequestPriority.NORMAL);
                            if (opened.request() == null) {
                                continue;
                            }
                            RequestLedgerService.Decision reserved =
                                RequestLedgerService.reserve(level, s,
                                    opened.request().id(), settler);
                            CollectionJob job = collectionJob(level, s,
                                reserved.request());
                            if (reserved.accepted() && job != null) {
                                if (!tidy) rememberCollectionSource(job);
                                return job;
                            }
                            if (reserved.blocker()
                                    == RequestBlocker.RESERVED_BY_OTHER) {
                                observeStop(StopReason.RESERVED_BY_OTHER,
                                    source.plaquePos, source);
                                break;
                            }
                        }
                    }
                }
            }
            Building target = firstWarehouse == null ? source : firstWarehouse;
            observeStop(StopReason.NO_WAREHOUSE_SPACE, target.plaquePos,
                target);
        }
        return null;
    }

    private int sourceIndexAfterCursor(List<Building> sources) {
        if (sources.isEmpty()) return 0;
        var data = settler.getPersistentData();
        if (!data.hasUUID(COLLECTION_SOURCE_CURSOR)) return 0;
        return collectionStartAfter(sources.stream().map(building -> building.id).toList(),
            data.getUUID(COLLECTION_SOURCE_CURSOR));
    }

    /** Stable pure seam for the successful-reservation round robin. */
    public static int collectionStartAfter(List<UUID> sources, UUID cursor) {
        if (sources == null || sources.isEmpty() || cursor == null) return 0;
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).equals(cursor)) return (index + 1) % sources.size();
        }
        return 0;
    }

    private void rememberCollectionSource(CollectionJob job) {
        if (job.source() != null && job.source().type != BuildingType.WAREHOUSE) {
            settler.getPersistentData().putUUID(COLLECTION_SOURCE_CURSOR, job.source().id);
        }
    }

    /** Lowest-priority storage moves use the same saved bag route as deliveries. */
    private CollectionJob findWarehouseSortJob(ServerLevel level, Settlement settlement) {
        if (!RequestLedgerService.validCourier(level, settlement, settler)) return null;
        for (Building warehouse : settlement.buildings) {
            if (!warehouse.valid || warehouse.type != BuildingType.WAREHOUSE) continue;
            for (Held source : liveContainers(level, warehouse)) {
                for (int slot = 0; slot < source.container().getContainerSize(); slot++) {
                    ItemStack stack = source.container().getItem(slot);
                    if (!com.hearthstead.settlement.warehouse.WarehouseSorting.isCourierSortable(stack)) continue;
                    var targets = com.hearthstead.settlement.warehouse.WarehouseSorting.destinations(level, warehouse, stack);
                    if (targets.contains(source.pos())) continue;
                    int quantity = Math.min(stack.getCount(), roomFor(stack));
                    if (quantity <= 0) continue;
                    for (BlockPos target : targets) {
                        var opened = RequestLedgerService.openOutputPickup(level, settlement, warehouse,
                            source.pos(), slot, warehouse, target, quantity, RequestPriority.NORMAL);
                        if (opened.request() == null) continue;
                        var reserved = RequestLedgerService.reserve(level, settlement, opened.request().id(), settler);
                        if (reserved.accepted()) return collectionJob(level, settlement, reserved.request());
                    }
                }
            }
        }
        return null;
    }

    /** Routine cleanup never competes with a building's operating stock.
     * Keep the entire protected category; normal output collection owns its
     * existing numerical buffers. This also prevents restock/cleanup loops. */
    private static boolean mayTidy(BuildingType type, ItemStack stack) {
        if (type == BuildingType.TRADING_POST) return false; // approved commercial staging is not surplus
        if (type == BuildingType.BUILDERS_HUT) return false; // BUILDER lane: delivered construction stock is not surplus
        if (stack.isEmpty() || stack.isDamageableItem()
                || stack.has(DataComponents.FOOD)
                || stack.getItem() instanceof net.minecraft.world.item.ArmorItem
                || stack.getItem() instanceof net.minecraft.world.item.ArrowItem
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)
                || stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.MELON_SEEDS)
                || stack.is(Items.TORCHFLOWER_SEEDS) || stack.is(Items.PITCHER_POD)
                || stack.is(Items.BONE_MEAL) || stack.is(Items.EGG)
                || stack.is(Items.WHEAT) || stack.is(Items.SUGAR)
                || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET)
                || stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) return false;
        if (Fuel.burns(type) && Fuel.isFuel(stack)) return false;
        if (isGathering(type) && isGatheredOutput(type, stack)) return false;
        for (Production.Recipe recipe : Production.of(type)) {
            if (recipe.input().test(stack) || stack.is(recipe.output())) return false;
        }
        return true;
    }

    private static Item findTidyItem(List<Held> containers, BuildingType type) {
        for (Held held : containers) {
            for (int slot = 0; slot < held.container().getContainerSize(); slot++) {
                ItemStack stack = held.container().getItem(slot);
                if (mayTidy(type, stack)) return stack.getItem();
            }
        }
        return null;
    }

    /**
     * The first item this building's chests hold beyond its keep-back that
     * the building itself PRODUCES -- or, for a {@link #GATHERING_BUILDINGS}
     * member, holds at all. Inputs never match: they are the restock
     * route's cargo, not this one's. An item that is both (the mason's
     * STONE, the smithy's IRON_INGOT) does match, and the keep-back is what
     * lets its own chain keep running.
     */
    private static Item findSurplusOutput(List<Held> containers, BuildingType type) {
        if (isGathering(type)) {
            // World-gathering buildings have no recipe output table. Filter
            // the two mixed-input starter workplaces explicitly so the
            // courier takes logs/crops, never the axe, hoe or seed reserve.
            for (Held h : containers) {
                Container c = h.container();
                for (int slot = 0; slot < c.getContainerSize(); slot++) {
                    ItemStack stack = c.getItem(slot);
                    if (!stack.isEmpty() && isGatheredOutput(type, stack)
                        && countItemIn(containers, stack.getItem())
                            > keepBackFor(type, stack.getItem())) {
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

    /** COLLECTION keep-back per building kind and item: most
     *  {@link #GATHERING_BUILDINGS} members' chests are pure yield; a
     *  farmhouse retains a planting buffer; a
     *  workshop keeps a working buffer of its own product (see
     *  {@link #OUTPUT_KEEP_BACK} for why) -- and a BURNING building keeps
     *  at least its whole fuel reserve of any output that doubles as fuel
     *  (the smelter chars logs into charcoal, and charcoal feeds its own
     *  firebox). Without that floor the collection route and the fuel
     *  restock would carousel the same stacks: one hauling firewood in to
     *  reach {@link #FUEL_RESERVE_BATCHES} x unitsPerBatch, the other hauling
     *  the "surplus" above {@link #OUTPUT_KEEP_BACK} straight back out.
     *
     *  <p>The same carousel exists for any MATERIAL an output doubles as --
     *  not just fuel. Adversarial review FINDING 2 (2026-08-26): raising
     *  {@link #MATERIAL_RESERVE_BATCHES} from 1 to 4 (so a crafter's
     *  restock trip is worth a round-trip, see that constant's own note)
     *  pushed several dual-role items' RESTOCK target above this method's
     *  flat {@link #OUTPUT_KEEP_BACK} floor -- the mason's STONE restocks
     *  to 16 (stone_bricks needs 4 x 4) but collected back down to 8, the
     *  smithy's IRON_INGOT restocks to 12 (the priciest tool recipe needs
     *  3 x 4) but collected back down to 8. Collection empties the crafter
     *  to the (too-low) keep-back; restock's very next look sees the
     *  crafter short of the (too-high) target and hauls the identical
     *  warehouse stack straight back in -- forever, and restock is
     *  {@code JobPriority}'s top tier, so a courier wedged in that shuttle
     *  never even reaches the food route. Fixed the exact way the fuel case
     *  already was: for every recipe at this building that consumes `item`
     *  as its OWN input, raise the keep-back to at least that recipe's own
     *  restock target (the MAX across every such recipe -- WEAVER's
     *  WHITE_WOOL is consumed by both wool_bolt at 3/batch and banner at
     *  6/batch, and the floor must clear whichever one findRestockJob could
     *  actually trigger on). The collection floor is then always >= the
     *  restock ceiling, so the two bands can never straddle a live stock
     *  level -- the same guarantee the fuel branch already gave one item
     *  kind, generalised to every dual-role item in the table. */
    private static int keepBackFor(BuildingType type, Item item) {
        if (isGathering(type)) {
            // Carrots and potatoes are both harvest and seed. Leaving one
            // working buffer prevents collection from stripping a fresh
            // farmhouse of the stock needed for its next planting cycle.
            return type == BuildingType.FARMHOUSE ? OUTPUT_KEEP_BACK : 0;
        }
        ItemStack asTool = new ItemStack(item);
        if (asTool.isDamageableItem()) {
            // A finished tool, weapon or armour piece is never a workshop's
            // working buffer. Keeping eight back left every axe the smithy made
            // on its own shelf while Lumberers waited for one: Equipment
            // requests are served from the Warehouse only (reliability soak
            // 2026-09-26: 5 iron axes at the smithy, NO_TOOL lumberers). Only
            // a tool the same workshop consumes as an input keeps a reserve.
            boolean input = false;
            for (Production.Recipe recipe : Production.of(type)) {
                input |= recipe.input().test(asTool);
            }
            if (!input) {
                return 0;
            }
        }
        int keep = OUTPUT_KEEP_BACK;
        if (Fuel.burns(type) && Fuel.isFuel(new ItemStack(item))) {
            int targetUnits = FUEL_RESERVE_BATCHES * Fuel.unitsPerBatch(type);
            int unitsPerItem = Fuel.unitsPerItem(new ItemStack(item));
            keep = Math.max(keep, itemsForUnits(targetUnits, unitsPerItem));
        }
        ItemStack asStack = new ItemStack(item);
        for (Production.Recipe recipe : Production.of(type)) {
            if (recipe.input().test(asStack)) {
                keep = Math.max(keep, MATERIAL_RESERVE_BATCHES * recipe.inputCount());
            }
        }
        return keep;
    }

    private static boolean isGatheredOutput(BuildingType type, ItemStack stack) {
        if (type == BuildingType.FISHERY) return stack.is(ItemTags.FISHES);
        if (type == BuildingType.LUMBER_CAMP) {
            return stack.is(ItemTags.LOGS);
        }
        if (type == BuildingType.FARMHOUSE) {
            return stack.is(Items.WHEAT) || stack.is(Items.CARROT)
                || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
                || stack.is(Items.SUGAR_CANE);
        }
        if (type == BuildingType.HUNTERS_LODGE) {
            // Positive-list only what HunterWorkGoal can physically obtain.
            // Arrows and delivered bows are inputs/equipment and therefore
            // can never become generic gathering output.
            return stack.is(Items.BEEF) || stack.is(Items.COOKED_BEEF)
                || stack.is(Items.PORKCHOP) || stack.is(Items.COOKED_PORKCHOP)
                || stack.is(Items.MUTTON) || stack.is(Items.COOKED_MUTTON)
                || stack.is(Items.CHICKEN) || stack.is(Items.COOKED_CHICKEN)
                || stack.is(Items.RABBIT) || stack.is(Items.COOKED_RABBIT)
                || stack.is(Items.LEATHER)
                || stack.is(Items.RABBIT_HIDE) || stack.is(Items.RABBIT_FOOT)
                || stack.is(Items.FEATHER) || stack.is(ItemTags.WOOL)
                || stack.is(Items.BROWN_MUSHROOM);
        }
        return true;
    }

    private static int itemsForUnits(int units, int unitsPerItem) {
        if (units <= 0 || unitsPerItem <= 0) {
            return 0;
        }
        return (units + unitsPerItem - 1) / unitsPerItem;
    }

    /** Total heat represented by physical fuel stacks, saturating safely. */
    private static int countFuelUnits(List<Held> containers) {
        long total = 0;
        for (Held held : containers) {
            Container container = held.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                total += Fuel.units(container.getItem(slot));
                if (total >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return (int) total;
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

    private record EquipmentJob(EquipmentRequest request, Building workplace,
                                BlockPos workplaceChest, Building warehouse,
                                BlockPos sourceChest, int sourceSlot,
                                ItemStack stack,
                                RestockKey key) {
    }

    private record RestockJob(Building crafter, BlockPos craftChest, Building warehouse,
                              BlockPos sourceChest, Item item, ItemStack stack,
                              boolean fuel, RestockKey key, UUID requestId) {
    }

    private record CollectionJob(UUID requestId, Building source,
                                 BlockPos sourceChest, Building warehouse,
                                 BlockPos dropChest, Item item,
                                 ItemStack stack) {
    }

    /** FOOD_DELIVERY: no destination chest field -- the destination is
     *  always the settlement's own hearth, read live at delivery time. */
    private record FoodJob(RequestRecord request, Building warehouse, BlockPos sourceChest, Item item,
                           ItemStack stack) {
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
        return firstRoomFor(containers, want) != null;
    }

    private static Held firstRoomFor(List<Held> containers,
                                     Predicate<ItemStack> want) {
        for (Held h : containers) {
            Container c = h.container();
            if (c instanceof com.hearthstead.block.FishRackBlockEntity) continue;
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack stack = c.getItem(slot);
                if (stack.isEmpty()) {
                    return h;
                }
                if (want.test(stack) && stack.getCount() < Math.min(c.getMaxStackSize(), stack.getMaxStackSize())) {
                    return h;
                }
            }
        }
        return null;
    }

    private CollectionJob collectionJob(ServerLevel level,
                                        Settlement settlement,
                                        RequestRecord request) {
        if (request == null || request.courierId() == null
            || !request.courierId().equals(settler.getUUID())) {
            return null;
        }
        Building source = findBuildingById(settlement,
            request.sourceBuildingId());
        Building target = findBuildingById(settlement,
            request.targetBuildingId());
        ItemStack exact = request.fingerprint().prototype(level.registryAccess());
        if (source == null || target == null || exact.isEmpty()) {
            return null;
        }
        return new CollectionJob(request.id(), source,
            request.sourceContainer(), target, request.targetContainer(),
            exact.getItem(), exact.copyWithCount(1));
    }

    /** Whether this concrete item/component stack can physically merge or land. */
    private static boolean roomForExact(List<Held> containers, ItemStack incoming) {
        return firstRoomForExact(containers, incoming) != null;
    }

    private static Held firstRoomForExact(List<Held> containers,
                                          ItemStack incoming) {
        for (Held held : containers) {
            Container container = held.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.canPlaceItem(slot, incoming)) continue;
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()) {
                    return held;
                }
                if (ItemStack.isSameItemSameComponents(existing, incoming)
                    && existing.getCount() < Math.min(container.getMaxStackSize(),
                        existing.getMaxStackSize())) {
                    return held;
                }
            }
        }
        return null;
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

    /** First concrete matching stack, including components, without mutation. */
    private static ItemStack matchingStack(Container container,
                                           Predicate<ItemStack> want) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && want.test(stack)) {
                return stack.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------- reservation ---

    /** Which building, and which exact item, a trip is claimed for. For a
     *  restock the id is the DESTINATION crafter's; for a collection it is
     *  the SOURCE workshop's; for a food delivery it is the SETTLEMENT's
     *  own id, standing in for the hearth (which is not a Building). One
     *  ledger for all of them means one place's one item is one courier's
     *  business at a time -- two routes cannot even transiently drag the
     *  same item through the same door in both directions. */
    private record RestockKey(UUID crafterId, Item item, boolean fuelLane) {
        private static RestockKey forItem(UUID crafterId, Item item) {
            return new RestockKey(crafterId, item, false);
        }

        /** One shared demand lane per burner, independent of fuel species. */
        private static RestockKey forFuel(UUID crafterId) {
            return new RestockKey(crafterId, null, true);
        }
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

    /**
     * [economy] crafter self-fetch (CrafterWorkGoal): the bench's own crafter
     * takes the SAME restock key a courier would, so a courier never sets
     * out for a load the crafter is already carrying (and a crafter never
     * sets out while a courier holds the job). Item conservation never
     * depended on this map -- both sides move real stacks chest-to-bag-to-
     * chest -- it only prevents a double trip. {@code item == null} is the
     * bench's fuel lane.
     *
     * @return whether {@code holder} now holds the key (false: someone else
     *         holds a live reservation, e.g. a courier on her way)
     */
    public static boolean claimRestockKey(UUID crafterId, @javax.annotation.Nullable Item item,
                                          UUID holder, long now) {
        RestockKey key = item == null ? RestockKey.forFuel(crafterId)
            : RestockKey.forItem(crafterId, item);
        Reservation held = RESERVATIONS.get(key);
        if (held != null && held.expiresAtTick() > now && !held.courier().equals(holder)) {
            return false;
        }
        RESERVATIONS.put(key, new Reservation(holder, now + RESERVATION_TTL_TICKS));
        return true;
    }

    /** Releases a key taken with {@link #claimRestockKey}, only if still ours. */
    public static void releaseRestockKey(UUID crafterId, @javax.annotation.Nullable Item item,
                                         UUID holder) {
        RestockKey key = item == null ? RestockKey.forFuel(crafterId)
            : RestockKey.forItem(crafterId, item);
        Reservation held = RESERVATIONS.get(key);
        if (held != null && held.courier().equals(holder)) {
            RESERVATIONS.remove(key);
        }
    }

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
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        if (equipmentRequestId != null) {
            Settlement settlement = settler.settlement();
            if (settlement != null) {
                EquipmentRequests.renew(level, settlement, equipmentRequestId,
                    settler.getUUID());
            }
        }
        if (transportRequestId != null) {
            Settlement settlement = settler.settlement();
            if (settlement != null) {
                RequestLedgerService.renew(level, settlement, transportRequestId,
                    settler);
            }
        }
        if (reservationKey == null) {
            return;
        }
        RESERVATIONS.put(reservationKey,
            new Reservation(settler.getUUID(), level.getGameTime() + RESERVATION_TTL_TICKS));
    }

    private void releaseReservation() {
        if (transportRequestId != null
            && settler.level() instanceof ServerLevel outputLevel) {
            Settlement settlement = settler.settlement();
            if (settlement != null && bagCount() == 0
                && RequestLedgerService.releasePrePickup(outputLevel,
                    settlement, transportRequestId, settler)) {
                transportRequestId = null;
            }
        }
        if (equipmentRequestId != null
            && settler.level() instanceof ServerLevel level) {
            Settlement settlement = settler.settlement();
            if (settlement != null && EquipmentRequests.release(level,
                    settlement, equipmentRequestId, settler.getUUID())) {
                equipmentRequestId = null;
            }
        }
        if (reservationKey != null) {
            Reservation held = RESERVATIONS.get(reservationKey);
            if (held != null && held.courier().equals(settler.getUUID())) {
                RESERVATIONS.remove(reservationKey);
            }
            reservationKey = null;
        }
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
        return RESERVATIONS.containsKey(RestockKey.forItem(crafterId, item));
    }

    /** Test-only view of the one shared fuel-demand lane for a burner. */
    public static boolean fuelJobIsHeld(UUID crafterId) {
        return RESERVATIONS.containsKey(RestockKey.forFuel(crafterId));
    }
}
