package com.hearthstead.entity.ai;

import com.hearthstead.building.Production;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.request.CraftingOrderService;
import com.hearthstead.settlement.research.Research;
import com.hearthstead.settlement.research.ResearchKey;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Fuel;
import com.hearthstead.entity.path.StandCells;
import com.hearthstead.settlement.builder.BuilderStock;
import com.hearthstead.settlement.economy.EconomyConfig;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * The work every crafting trade does: stand at your bench and make the thing.
 *
 * <p>One goal, eleven trades. The shape is identical — take from your own
 * chests, spend time, put something back — so it is written once here and
 * differs only in the recipe table ({@link Production}) and the motion
 * ({@link Employment#motionOf}). Eleven bespoke work goals would be eleven
 * places for the same bug.
 *
 * <h2>What it is careful about</h2>
 *
 * <p><b>It does not scan every tick.</b> Asking whether there is work means
 * reading the building's containers, so it is asked on a cooldown and not
 * otherwise. All world scanning here is budgeted, like everything else.
 *
 * <p><b>The work takes the time the recipe says.</b> The output appears when
 * the clip finishes, not when the goal starts — a profession that teleported
 * its result would make the animation decoration rather than the work.
 *
 * <p><b>Doing the job makes you better at it.</b> One completed action trains
 * the attribute that trade leans on ({@link Employment#trainedBy}), which is
 * what "learning by doing" has to mean if it is to mean anything: counted on
 * completion, never on a timer.
 *
 * <p><b>Research changes the price, not just the pace.</b> Four completed
 * projects (Bedre Gjær, Tørrsett Tømmer, Blestring, Garvesyre) cut both
 * {@link #researchedTicks} AND the effort a batch costs
 * ({@link #researchEffortMultiplier}) — see
 * {@code docs/project/BALANCE_AUDIT.md} finding 2's follow-up for why the
 * second one had to exist: effort, not ticks, was always the number that
 * actually capped a day's batches, so a project that only touched ticks
 * bought nothing a player could ever see.
 */
public class CrafterWorkGoal extends Goal {

    /** How often to ask the chests whether there is anything to do. */
    private static final int LOOK_INTERVAL = 20;

    private final SettlerEntity settler;
    private Production.Recipe recipe;
    private Building bench;
    private int lookCooldown;
    private int ticksLeft;
    private int workedTicks;

    /**
     * The recipe's cost after this settlement's research (RESEARCH-1's
     * handoff, finally wired): Bedre Gjær really does make the bakery
     * faster. A settlement that never opened a lectern pays exactly the
     * table price -- the multiplier is neutral, not absent.
     */
    private int researchedTicks(Production.Recipe recipe) {
        Settlement settlement = settler.settlement();
        if (!(settler.level() instanceof ServerLevel level)
            || settlement == null || bench == null) {
            return recipe.ticks();
        }
        return Production.ticksFor(level, settlement.id, bench.type, recipe);
    }

    /**
     * The same completed-research multiplier {@link #researchedTicks}
     * already applies to time, read again here for effort — see
     * {@code docs/project/BALANCE_AUDIT.md} finding 2's follow-up.
     *
     * <p><b>Both, not one instead of the other.</b> The tick cut stays real
     * where it always was real: a settler pulled off the bench mid-batch
     * still loses less half-finished work, and the clip visibly runs
     * quicker, which is worth keeping on its own terms. What it never did
     * is buy a single extra loaf, because effort — not the clock — was
     * always the ceiling (Q4 of the audit). Cutting effort is what makes
     * "better at this craft" show up the one place a player actually
     * counts it: more finished batches before the pool runs dry. One
     * project, one multiplier, two places it was always meant to matter.
     *
     * <p>Duplicates {@code Production}'s own (private)
     * {@code BuildingType -> ResearchKey} table rather than widening that
     * class's surface for one caller — {@code Production.java} belongs to
     * another worker while this slice lands, and four case labels are
     * cheaper to keep in sync than a cross-owner API change.
     */
    private float researchEffortMultiplier() {
        Settlement settlement = settler.settlement();
        if (!(settler.level() instanceof ServerLevel level)
            || settlement == null || bench == null) {
            return 1.0F;
        }
        ResearchKey key = switch (bench.type) {
            case BAKERY -> ResearchKey.BAKERY_TICKS;
            case SAWMILL -> ResearchKey.SAWMILL_TICKS;
            case SMELTER -> ResearchKey.SMELTER_TICKS;
            case TANNERY -> ResearchKey.TANNERY_TICKS;
            default -> null;
        };
        if (key == null) {
            return 1.0F;
        }
        // Tech tree Charcoal Kilns (techtree-craft): Smelter effort x0.75 too.
        return Research.bonus(level, settlement.id, key) * (float)
            com.hearthstead.settlement.techtree.effects.CraftEffects.smelterScale(level, settlement.id, bench.type);
    }

    public CrafterWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------------
    // [economy] What a crafter does while its bench has nothing to make
    // (plan/ECONOMY.md). Two REAL fallbacks, tried in this order, never a
    // busy loop:
    //
    //  FETCH  -- the bench has stood empty for selfFetchAfterSeconds and no
    //            courier holds its restock job: walk to a nearby Warehouse,
    //            take a load of one missing input (or fuel) out of a real
    //            chest into the bag, carry it back, put it in the bench's
    //            chest. It takes the courier's own restock key while it does
    //            (CourierWorkGoal#claimRestockKey), so no courier makes the
    //            same trip. Items only ever move chest -> bag -> chest.
    //  UPKEEP -- nothing to fetch either: tidy the workshop's chests (merge
    //            split stacks) and study the trade at the bench. One finished
    //            session is 1 trade XP (a crafted batch is 2); the bench is
    //            re-checked every second, and the first input that arrives
    //            ends the session and the crafter goes straight back to work.
    //
    // Both are off (and this goal behaves exactly as before) on the GameTest
    // server unless a test opts in -- see EconomyConfig.
    // ------------------------------------------------------------------

    private enum Mode { CRAFT, FETCH, UPKEEP }

    private enum FetchLeg { TO_SOURCE, TO_BENCH }

    private static final int REPLAN_INTERVAL = 20;
    private static final int UPKEEP_RECHECK = 20;
    private static final int FETCH_GIVE_UP_TICKS = 1200;
    private static final double MOVE_SPEED = 0.7D;

    private Mode mode = Mode.CRAFT;
    /** Game time the bench was first seen unable to run anything; -1 = fed. */
    private long starvedSince = -1L;
    private FetchLeg fetchLeg;
    private BlockPos fetchFrom;
    private BlockPos fetchTo;
    private Item fetchItem;
    private boolean fetchFuel;
    private int fetchWant;
    private boolean fetchKeyHeld;
    private long lastReplan;
    private long legStarted;
    private int upkeepLeft;
    /** Last time a self-fetch was looked for and nothing was fetchable. */
    private long lastFetchCheck = Long.MIN_VALUE / 2;
    /**
     * BH-29: the bench had no room for the carried load. Until this tick the
     * carry-back does not pre-empt crafting (crafting is what frees room), so
     * a full bench can never livelock the crafter into walking back and forth.
     */
    private long carryBackBlockedUntil = Long.MIN_VALUE / 2;
    static final int CARRY_BACK_RETRY_TICKS = 400;
    /**
     * Which bench chest the carry-back aims at. A return leg that cannot reach
     * its chest in FETCH_GIVE_UP_TICKS tries the next one, then backs off
     * (Codex P2, 2026-09-26: the leg had no timeout and re-pathed every tick
     * once navigation was done, stalling the crafter for its whole shift).
     */
    private int carryBackChest;
    /** Minimum ticks between two route requests even when navigation is done. */
    private static final int MIN_REPLAN_TICKS = 5;

    @Override
    public boolean canUse() {
        if (!settler.isBound() || settler.getTarget() != null) {
            return false;
        }
        if (lookCooldown > 0) {
            lookCooldown--;
            return false;
        }
        lookCooldown = LOOK_INTERVAL;
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        Building building = Employment.employerOf(settlement, settler.getUUID());
        if (building == null || !building.valid || building.anchor == null
            || !Production.produces(building.type)) {
            return false;
        }
        // A load this crafter fetched earlier and could not finish carrying
        // (a meal, a raid, the end of the shift): take it to the bench first,
        // whatever the hour -- inputs never ride around overnight.
        if (economyFallbacks(level) && carriesBenchGoods(building)
            && level.getGameTime() >= carryBackBlockedUntil) {
            bench = building;
            beginCarryBack(level, building);
            if (fetchTo == null) {
                bench = null;
                return false;
            }
            return true;
        }
        if (!Schedule.shouldWork(settlement, settler, settler.dayPhase())) {
            return false;
        }
        // Work happens AT the bench. Getting there is GoToPostGoal's job; if
        // the settler is not there yet, this simply is not their turn.
        if (!settler.blockPosition().closerThan(building.anchor, Schedule.AT_POST)) {
            return false;
        }
        // Logistics M1: an open crafting order at this bench is preferred
        // over the default least-stocked choice; it never gates work.
        Production.Recipe ready = Production.ready(level, building,
            CraftingOrderService.preferredOutput(level, settlement, building));
        if (ready != null) {
            WorkStopReasons.clear(settler);
            starvedSince = -1L;
            bench = building;
            recipe = ready;
            mode = Mode.CRAFT;
            return true;
        }
        // J-01: say why the bench is idle instead of "Nothing needed" -- still
        // true while the crafter fetches or tidies below.
        WorkStopReasons.report(settler,
            Production.idleReason(level, building) == Production.Idle.OUTPUT_FULL
                ? com.hearthstead.logistics.StopReason.CHEST_FULL
                : com.hearthstead.logistics.StopReason.WAITING_INPUT,
            building.anchor);
        if (!economyFallbacks(level)) {
            return false;
        }
        long now = level.getGameTime();
        if (starvedSince < 0L || now < starvedSince) {
            starvedSince = now;
        }
        bench = building;
        if (fetchDue(level, now)) {
            if (planFetch(level, settlement, building, now)) {
                mode = Mode.FETCH;
                return true;
            }
            lastFetchCheck = now; // nothing fetchable: look again after a while
        }
        if (EconomyConfig.workshopUpkeep(level.getServer())) {
            mode = Mode.UPKEEP;
            upkeepLeft = EconomyConfig.upkeepTicks();
            return true;
        }
        bench = null;
        return false;
    }

    /** The bench has waited long enough, and the last look is not too recent. */
    private boolean fetchDue(ServerLevel level, long now) {
        int after = EconomyConfig.selfFetchAfterTicks();
        return EconomyConfig.selfFetch(level.getServer()) && starvedSince >= 0L
            && now - starvedSince >= after && now - lastFetchCheck >= after;
    }

    private static boolean economyFallbacks(ServerLevel level) {
        return EconomyConfig.selfFetch(level.getServer())
            || EconomyConfig.workshopUpkeep(level.getServer());
    }

    @Override
    public boolean canContinueToUse() {
        Settlement settlement = settler.settlement();
        if (bench == null || settler.getTarget() != null || settlement == null) {
            return false;
        }
        return switch (mode) {
            case CRAFT -> recipe != null
                && Schedule.shouldWork(settlement, settler, settler.dayPhase());
            // Carrying the bench's goods home is allowed past the shift's
            // end; higher-priority goals (meals, rest, danger) still win.
            case FETCH -> fetchLeg == FetchLeg.TO_BENCH
                || Schedule.shouldWork(settlement, settler, settler.dayPhase());
            case UPKEEP -> upkeepLeft > 0
                && Schedule.shouldWork(settlement, settler, settler.dayPhase());
        };
    }

    /**
     * Without this, {@code Mob.serverAiStep} only ticks a running goal on
     * every OTHER real tick (the vanilla half-rate optimisation for goals
     * that do not ask for more), which silently doubles how long every
     * recipe takes to cook down {@code ticksLeft} -- a batch calibrated to
     * finish in {@code recipe.ticks()} real ticks was actually taking about
     * twice that, which is exactly the "crafters work but stall" shape
     * (KF-020): the settler visibly swings, the sound plays, but two
     * batches never land inside their bounded budget. Every sibling work
     * goal (Farmer, Lumberer, Courier) already overrides this for the same
     * reason; this one was the one still running on vanilla's half tick.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        if (mode == Mode.FETCH) {
            lastReplan = Long.MIN_VALUE / 2;
            legStarted = settler.level().getGameTime();
            settler.setActivity(fetchLeg == FetchLeg.TO_BENCH
                ? SettlerActivity.CARRYING : SettlerActivity.TRAVELING);
            return;
        }
        settler.getNavigation().stop();
        if (mode == Mode.UPKEEP) {
            settler.setActivity(SettlerActivity.SORTING);
            workedTicks = 0;
            return;
        }
        settler.setActivity(Employment.motionOf(bench.type));
        // Trade skill speed trims whole clip loops only (level 1: identical).
        ticksLeft = com.hearthstead.entity.SkillLevels.shortenLooped(settler,
            researchedTicks(recipe), Employment.soundPeriodOf(bench.type));
        workedTicks = 0;
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        if (mode == Mode.FETCH) {
            settler.getNavigation().stop();
        }
        releaseFetchKey();
        recipe = null;
        bench = null;
        ticksLeft = 0;
        fetchLeg = null;
        fetchFrom = null;
        fetchTo = null;
        fetchItem = null;
        fetchFuel = false;
        upkeepLeft = 0;
        mode = Mode.CRAFT;
    }

    @Override
    public void tick() {
        // requiresUpdateEveryTick() makes vanilla tick this goal on the
        // half-rate tick too, WITHOUT canContinueToUse(). When the previous
        // tick finished the last ready batch (recipe = null below), that
        // extra tick used to reach Production.run(null) and crash the
        // server (soak 2026-09-26). The next full tick stops the goal.
        if (bench == null || !(settler.level() instanceof ServerLevel level)) {
            return;
        }
        if (mode == Mode.FETCH) {
            tickFetch(level);
            return;
        }
        if (mode == Mode.UPKEEP) {
            tickUpkeep(level);
            return;
        }
        if (recipe == null) {
            return;
        }
        // The sound rides the clip, not a timer of its own: one per loop of
        // whatever motion this trade performs, ON the clip's contact beat
        // (job standard, point 6). % period == 0 -- the old form -- fired at
        // the loop seam, the rest pose, half a cycle away from the visible
        // strike (audit F8); soundContactOf carries each clip's real beat.
        // start() sets the activity and this tick() runs in the SAME server
        // tick, so workedTicks is already 1 on the clip's frame 0: clipTick
        // is the clip's own frame index. WorkSoundSync then ships the sound
        // in the same flush as the synced activity, so it lands on the beat.
        int period = Employment.soundPeriodOf(bench.type);
        int clipTick = ++workedTicks - 1;
        if (clipTick % period == Employment.soundContactOf(bench.type)) {
            WorkSoundSync.play(level, settler.getX(), settler.getY() + 0.9, settler.getZ(),
                Employment.soundOf(bench.type), 0.55F, 1.0F);
        }
        if (--ticksLeft > 0) {
            return;
        }
        // [quality] the finished good rolls its grade from this crafter.
        boolean made = Production.run(level, bench, recipe, settler);
        if (made) {
            // Logistics M1: a physically completed batch counts toward the
            // oldest open crafting order for this output at this bench.
            CraftingOrderService.noteCrafted(level, settler.settlement(), bench, recipe);
            settler.train(Employment.trainedBy(bench.type), 1.0F);
            com.hearthstead.entity.SkillLevels.completeUnit(settler, 2,
                Employment.trainedBy(bench.type));
            settler.addMorale(0.5F);
            // One completed batch, whatever the trade, is the same 2 units
            // of the daily pool (PLAN_EFFORT.md §2) -- charged on the same
            // tick the recipe completes, the moment Production.run says the
            // output actually exists. A completed research project on this
            // building's key shaves that 2 down (researchEffortMultiplier,
            // BALANCE_AUDIT.md finding 2's follow-up); an untouched
            // settlement's multiplier is neutral and this call behaves
            // exactly like the flat settler.spendEffort(2) it replaced.
            settler.effort().spendResearched(2, researchEffortMultiplier(),
                settler.attribute(Attribute.STAMINA));
        }
        // Look for the next piece of work straight away. Fatigue changes the
        // worker's pace centrally; it does not invalidate a ready recipe.
        Production.Recipe next = Production.ready(level, bench,
            CraftingOrderService.preferredOutput(level, settler.settlement(), bench));
        if (next == null) {
            recipe = null;
            return;
        }
        recipe = next;
        ticksLeft = com.hearthstead.entity.SkillLevels.shortenLooped(settler,
            researchedTicks(next), Employment.soundPeriodOf(bench.type));
    }

    // ------------------------------------------------------------ fetch ---

    /**
     * Picks one input (or the fuel lane) this bench is short of that a
     * Warehouse within {@code selfFetchRadius} actually holds, and takes the
     * courier's restock key for it. False when there is nothing to fetch,
     * or a courier already has the job.
     */
    private boolean planFetch(ServerLevel level, Settlement settlement, Building building, long now) {
        List<Container> mine = BuilderStock.hutContainers(level, building);
        if (mine.isEmpty()) {
            return false;
        }
        int radius = EconomyConfig.selfFetchRadius();
        // Fuel first when fuel is the ONLY thing stopping the bench.
        if (Production.starvedForFuel(level, building)
            && tryPlan(level, settlement, building, mine, Fuel::isFuel, true, 8, radius, now)) {
            return true;
        }
        net.minecraft.world.item.Item ordered = CraftingOrderService.preferredOutput(level, settlement, building);
        for (Production.Recipe candidate : Production.of(building.type)) {
            if (Production.orderOnly(candidate) && candidate.output() != ordered) {
                continue; // stone pickaxe: fetch cobblestone only for its order
            }
            Ingredient want = candidate.input();
            int target = CourierWorkGoal.MATERIAL_RESERVE_BATCHES * candidate.inputCount();
            int have = countMatching(mine, want);
            if (have >= candidate.inputCount()) {
                continue; // this recipe is fed; something else stops the bench
            }
            if (tryPlan(level, settlement, building, mine, want, false,
                    target - have, radius, now)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryPlan(ServerLevel level, Settlement settlement, Building building,
                            List<Container> mine, Predicate<ItemStack> want, boolean fuel,
                            int wanted, int radius, long now) {
        for (Building warehouse : settlement.buildings) {
            if (!warehouse.valid || warehouse.type != BuildingType.WAREHOUSE
                || warehouse.bounds == null || warehouse.anchor == null
                || !warehouse.anchor.closerThan(building.anchor, radius)) {
                continue;
            }
            for (BlockPos pos : WarehouseIndex.containers(level, warehouse)) {
                if (!(level.getBlockEntity(pos) instanceof Container chest)) {
                    continue;
                }
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    ItemStack stack = chest.getItem(slot);
                    if (stack.isEmpty() || !want.test(stack)
                        || !stack.getComponentsPatch().isEmpty()) {
                        continue;
                    }
                    int room = 0;
                    for (Container c : mine) {
                        room += CourierWorkGoal.transferRoom(c, stack.copyWithCount(64));
                    }
                    int carry = com.hearthstead.logistics.Weight.perLoad(stack,
                        settler.getCarryCapacity());
                    int amount = Math.min(Math.min(wanted, room),
                        Math.min(carry, EconomyConfig.selfFetchMaxItems()));
                    if (amount <= 0) {
                        continue;
                    }
                    Item item = stack.getItem();
                    if (!CourierWorkGoal.claimRestockKey(building.id, fuel ? null : item,
                            settler.getUUID(), now)) {
                        return false; // a courier is already on it: let her
                    }
                    fetchKeyHeld = true;
                    fetchFuel = fuel;
                    fetchItem = item;
                    fetchWant = amount;
                    fetchFrom = pos;
                    fetchTo = null;
                    fetchLeg = FetchLeg.TO_SOURCE;
                    return true;
                }
            }
        }
        return false;
    }

    private void tickFetch(ServerLevel level) {
        long now = level.getGameTime();
        if (fetchKeyHeld) {
            // Keep the courier's key alive while the trip is genuinely on.
            CourierWorkGoal.claimRestockKey(bench.id, fetchFuel ? null : fetchItem,
                settler.getUUID(), now);
        }
        BlockPos target = fetchLeg == FetchLeg.TO_SOURCE ? fetchFrom : fetchTo;
        if (target == null) {
            endFetch(now);
            return;
        }
        if (!StandCells.inReach(level, settler, target)) {
            if (now - legStarted > FETCH_GIVE_UP_TICKS && fetchLeg == FetchLeg.TO_SOURCE) {
                // No way to the shelf: give the job back to the couriers.
                endFetch(now);
                return;
            }
            if (now - legStarted > FETCH_GIVE_UP_TICKS && fetchLeg == FetchLeg.TO_BENCH) {
                // No way to this bench chest: try the next one, else keep the
                // load in the bag, release the key and back off. Crafting and
                // other goals get the settler back; nothing is dropped.
                List<BlockPos> chests = WarehouseIndex.containers(level, bench);
                if (++carryBackChest < chests.size()) {
                    fetchTo = chests.get(carryBackChest);
                    legStarted = now;
                    lastReplan = Long.MIN_VALUE / 2;
                    return;
                }
                settler.recordRouteFailure("crafter_carry_back_unreachable");
                carryBackBlockedUntil = now + CARRY_BACK_RETRY_TICKS;
                endFetch(now);
                return;
            }
            if (now - lastReplan >= REPLAN_INTERVAL
                || settler.getNavigation().isDone() && now - lastReplan >= MIN_REPLAN_TICKS) {
                lastReplan = now;
                StandCells.moveNextTo(level, settler, target, MOVE_SPEED);
            }
            return;
        }
        settler.getNavigation().stop();
        if (fetchLeg == FetchLeg.TO_SOURCE) {
            int moved = 0;
            if (level.getBlockEntity(fetchFrom) instanceof Container chest) {
                moved = BuilderStock.moveToBag(List.of(chest), fetchItem, fetchWant, settler.bag);
            }
            if (moved <= 0) {
                endFetch(now); // someone emptied the shelf first
                return;
            }
            playStow(level);
            beginCarryBack(level, bench);
            if (fetchTo == null) {
                endFetch(now);
            }
            return;
        }
        // TO_BENCH: set every bench good the bag holds into the bench chests.
        List<Container> mine = BuilderStock.hutContainers(level, bench);
        int stowed = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (stack.isEmpty() || !isBenchGood(bench, stack)) {
                continue;
            }
            ItemStack left = BuilderStock.insertAll(mine, stack.copy());
            stowed += stack.getCount() - left.getCount();
            settler.bag.setItem(slot, left);
        }
        if (stowed > 0) {
            playStow(level);
            settler.train(Attribute.STAMINA, 1.0F);
        }
        if (bench != null && carriesBenchGoods(bench)) {
            carryBackBlockedUntil = now + CARRY_BACK_RETRY_TICKS; // bench full: craft first
        }
        endFetch(now);
    }

    /** Aims the carry-back leg at the bench's first chest. */
    private void beginCarryBack(ServerLevel level, Building building) {
        List<BlockPos> chests = WarehouseIndex.containers(level, building);
        carryBackChest = 0;
        fetchTo = chests.isEmpty() ? null : chests.get(0);
        fetchLeg = FetchLeg.TO_BENCH;
        mode = Mode.FETCH;
        lastReplan = Long.MIN_VALUE / 2;
        legStarted = level.getGameTime();
        settler.setActivity(SettlerActivity.CARRYING);
    }

    private void endFetch(long now) {
        releaseFetchKey();
        // Restart the starve clock: the next fetch waits a full
        // selfFetchAfterSeconds, so a courier always gets first claim.
        starvedSince = now;
        fetchLeg = null;
        fetchFrom = null;
        fetchTo = null;
        bench = null; // ends the goal on the next canContinueToUse
    }

    private void releaseFetchKey() {
        if (fetchKeyHeld && bench != null) {
            CourierWorkGoal.releaseRestockKey(bench.id, fetchFuel ? null : fetchItem,
                settler.getUUID());
        }
        fetchKeyHeld = false;
    }

    private boolean carriesBenchGoods(Building building) {
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            ItemStack stack = settler.bag.getItem(slot);
            if (!stack.isEmpty() && isBenchGood(building, stack)) {
                return true;
            }
        }
        return false;
    }

    /** A recipe input of this bench, or fuel for a bench that burns. */
    private static boolean isBenchGood(Building building, ItemStack stack) {
        if (Fuel.burns(building.type) && Fuel.isFuel(stack)) {
            return true;
        }
        for (Production.Recipe candidate : Production.of(building.type)) {
            if (candidate.input().test(stack)) {
                return true;
            }
        }
        return false;
    }

    private static int countMatching(List<Container> containers, Ingredient want) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && want.test(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private void playStow(ServerLevel level) {
        level.playSound(null, settler.getX(), settler.getY(), settler.getZ(),
            com.hearthstead.registry.ModSounds.CHEST_STOW.get(),
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, 1.0F);
    }

    // ----------------------------------------------------------- upkeep ---

    /**
     * One upkeep session in three visible parts, each on an existing clip:
     * TIDY (the sorting clip) merges split stacks in the workshop chests,
     * SHARPEN (the trade's own bench motion) mends a little wear on any
     * damaged tool at the bench, STUDY (the scholar's reading clip) ends the
     * session with 1 trade XP. The bench is re-checked every second; the
     * first input that arrives ends upkeep and crafting resumes.
     */
    private void tickUpkeep(ServerLevel level) {
        workedTicks++;
        if (workedTicks % UPKEEP_RECHECK == 0) {
            if (Production.ready(level, bench,
                    CraftingOrderService.preferredOutput(level, settler.settlement(), bench)) != null) {
                // The moment anything can be made, making it wins.
                upkeepLeft = 0;
                starvedSince = -1L;
                bench = null;
                return;
            }
            // A due self-fetch does NOT cut the session short: the tuned
            // soak (captain1, 26 Sep) showed that cutting at every due check
            // looped tidy -> sharpen -> tidy and never reached the study
            // part when the Warehouse had nothing to fetch. A fetch waits at
            // most one session (30 s) and is planned the moment it ends.
        }
        int total = Math.max(3, EconomyConfig.upkeepTicks());
        int part = total / 3;
        if (workedTicks == 1) {
            settler.setActivity(SettlerActivity.SORTING);
        } else if (workedTicks == part) {
            // Tidy done: merge split stacks inside each workshop chest
            // (conserving -- items only move between slots of one container).
            for (Container container : BuilderStock.hutContainers(level, bench)) {
                mergeStacks(container);
            }
            playStow(level);
            settler.setActivity(Employment.motionOf(bench.type));
        } else if (workedTicks == 2 * part) {
            sharpenTools(level);
            settler.setActivity(Employment.motionOf(BuildingType.ARCHITECTS_STUDY));
        } else if (workedTicks > part && workedTicks < 2 * part) {
            int period = Employment.soundPeriodOf(bench.type);
            if ((workedTicks - part) % period == Employment.soundContactOf(bench.type)) {
                WorkSoundSync.play(level, settler.getX(), settler.getY() + 0.9, settler.getZ(),
                    Employment.soundOf(bench.type), 0.35F, 1.1F);
            }
        }
        if (--upkeepLeft > 0) {
            return;
        }
        // Study: one unit of trade XP for a finished session (a batch is 2).
        com.hearthstead.entity.SkillLevels.completeUnit(settler, 1);
        bench = null; // re-evaluate: a delivery or a fetch may be possible now
    }

    /** Mends a little wear on damaged tools held or kept at the bench. */
    private void sharpenTools(ServerLevel level) {
        mend(settler.getMainHandItem());
        for (Container container : BuilderStock.hutContainers(level, bench)) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (mend(container.getItem(slot))) {
                    container.setChanged();
                }
            }
        }
    }

    private static final int SHARPEN_POINTS = 4;

    private static boolean mend(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageableItem() || !stack.isDamaged()) {
            return false;
        }
        stack.setDamageValue(Math.max(0, stack.getDamageValue() - SHARPEN_POINTS));
        return true;
    }

    private static void mergeStacks(Container container) {
        boolean changed = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack into = container.getItem(i);
            if (into.isEmpty() || into.getCount() >= into.getMaxStackSize()) {
                continue;
            }
            for (int j = i + 1; j < container.getContainerSize()
                    && into.getCount() < into.getMaxStackSize(); j++) {
                ItemStack from = container.getItem(j);
                if (from.isEmpty() || !ItemStack.isSameItemSameComponents(into, from)) {
                    continue;
                }
                int move = Math.min(from.getCount(), into.getMaxStackSize() - into.getCount());
                into.grow(move);
                from.shrink(move);
                if (from.isEmpty()) {
                    container.setItem(j, ItemStack.EMPTY);
                }
                changed = true;
            }
        }
        if (changed) {
            container.setChanged();
        }
    }
}
