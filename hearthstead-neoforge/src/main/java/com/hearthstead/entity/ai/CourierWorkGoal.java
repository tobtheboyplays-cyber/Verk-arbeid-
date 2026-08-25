package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;

/**
 * The courier: moves non-food goods from the hearth to a warehouse.
 *
 * <p><b>Deliberately one-directional this slice</b> (hearth → warehouse,
 * never the reverse and never fetch-to-worksite). MineColonies shipped a
 * courier/builder circular wait where each side blocked on the other
 * (issue #5333); a one-way courier cannot deadlock because nothing is
 * waiting on it. Two-way routing lands only once this is proven.
 *
 * <p><b>Food never leaves the hearth</b> (D-A2a-1). {@code EatFromHearthGoal}
 * and {@code Settlement.foodCache} both read hearth contents, so draining
 * food into a warehouse would quietly starve the settlement.
 *
 * <p><b>Chests are the truth</b> (D-A2a-3): goods are removed from the
 * hearth into the bag, and inserted into the warehouse destination-first
 * with the true leftover carried back. At every instant the items exist
 * in exactly one real container, so an interruption -- including the
 * courier dying, which drops the bag -- conserves them.
 *
 * <p><b>The delivery target is a container, never the plaque</b>
 * (D-A2a-5). A warehouse has no beds, so {@code Building.anchor} is the
 * plaque block -- mounted in a wall, with no standable cell beside it and
 * none above it. Routing to the anchor produced exactly MineColonies'
 * "deliveryman never delivers" wedge (#2932): the courier loaded, walked
 * to the outside of the wall, never satisfied the arrival radius, gave up
 * and re-triggered forever with the load stranded in her bag. The courier
 * now walks to a standable cell beside a real chest, and only stows once
 * she is <em>inside</em> the building's own bounds -- so goods cannot be
 * posted through a wall either.
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
    /** Reach to the drop-off chest, squared. Two blocks and a bit. */
    private static final double DROP_OFF_REACH_SQR = 6.25;
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
    /** Same for the longer haul to the warehouse and home (~480 ticks). */
    private static final int HAUL_STUCK_LIMIT = 32;
    // Sound-sync contract (catalogue §0.4 / §5.1-5.4). Each value must agree
    // with the clip comment in SettlerAnimations and tools/anim_check.py.
    public static final int LIFT_GRIP_TICK = 8;
    public static final int HAUL_STEP_PERIOD = 18;
    public static final int HAUL_STRAIN_PERIOD = 96;
    public static final int SET_DOWN_TICK = 6;
    public static final int CRATE_CREAK_PERIOD = 54;
    public static final int CRATE_CREAK_OFFSET = 9;

    private enum Mode { TO_HEARTH, LOADING, TO_WAREHOUSE, SORTING, RETURNING }

    private final SettlerEntity settler;
    private Mode mode;
    /** The chest being delivered to -- the real destination, not the plaque. */
    private BlockPos dropOff;
    private java.util.UUID warehouseId;
    private int workTicks;
    private int setDownThudIn = -1;
    private int repathTimer;
    private int stuckChecks;
    private long cooldownUntil = Long.MIN_VALUE;
    /** Consecutive abandoned routes; reset by any completed delivery. */
    private int consecutiveFailures;
    private boolean done;

    public CourierWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

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
        if (!carrying && !onShift) {
            return false; // nothing in hand and nothing to do: skip the lookup
        }
        Building warehouse = pickWarehouse(s);
        BlockPos target = warehouse == null ? null : pickDropOff(level, warehouse);

        // Carrying with nowhere to put it down: take the goods back to the
        // hearth rather than sitting on them. Items in a bag are real, but
        // they are out of circulation, and the settlement cannot see them.
        if (carrying && target == null) {
            warehouseId = null;
            dropOff = null;
            mode = Mode.RETURNING;
            return true;
        }
        if (!onShift) {
            return false; // carrying off-shift: hold the load until morning
        }
        if (target == null) {
            return false; // idle visibly rather than thrash (MineColonies #2932)
        }
        warehouseId = warehouse.id;
        dropOff = target;

        // Already carrying? Finish the delivery before starting another.
        if (carrying) {
            mode = Mode.TO_WAREHOUSE;
            return true;
        }
        if (!hearthHasHaulableGoods()) {
            return false;
        }
        mode = Mode.TO_HEARTH;
        return true;
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
                pathToDropOff();
            }
            case RETURNING -> {
                settler.setActivity(SettlerActivity.CARRYING);
                pathAbove(settler.getHearthPos());
            }
            default -> {
                settler.setActivity(SettlerActivity.TRAVELING);
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

    private void pathToDropOff() {
        if (dropOff != null && settler.level() instanceof ServerLevel level) {
            pathToStand(approachTo(level, dropOff, settler.blockPosition()));
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

    @Override
    public void tick() {
        if (setDownThudIn >= 0 && setDownThudIn-- == 0) {
            playAt(ModSounds.CRATE_DOWN.get(), 0.8F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
        }
        switch (mode) {
            case TO_HEARTH -> tickToHearth();
            case LOADING -> tickLoading();
            case TO_WAREHOUSE -> tickToWarehouse();
            case SORTING -> tickSorting();
            case RETURNING -> tickReturning();
        }
    }

    private void tickToHearth() {
        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5,
            hearthPos.getY() + 0.6, hearthPos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(hearthPos) <= HEARTH_REACH_SQR) {
            settler.getNavigation().stop();
            mode = Mode.LOADING;
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
        mode = Mode.TO_WAREHOUSE;
        workTicks = 0;
        stuckChecks = 0;
        settler.setActivity(SettlerActivity.CARRYING);
        pathToDropOff();
    }

    private void tickToWarehouse() {
        if (dropOff == null || !(settler.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Settlement s = settler.settlement();
        Building warehouse = s == null ? null : findWarehouseById(s);
        if (warehouse == null) {
            mode = Mode.RETURNING; // dissolved mid-trip: carry the goods home
            stuckChecks = 0;
            repathTimer = 0;
            pathAbove(settler.getHearthPos());
            return;
        }
        // Laden footfalls and the occasional strained breath: the load is
        // meant to be audible, not just visible (D-007).
        workTicks++;
        if (settler.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
            if (workTicks % HAUL_STEP_PERIOD == 0) {
                playAt(ModSounds.HAUL_STEP.get(), 0.6F,
                    0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
            if (workTicks % HAUL_STRAIN_PERIOD == 0) {
                playAt(ModSounds.HAUL_STRAIN.get(), 0.55F,
                    0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
            // Loaded wood flexing. Deliberately offset from the footfall
            // period so the creak never lands on the same tick as a step.
            if (workTicks % CRATE_CREAK_PERIOD == CRATE_CREAK_OFFSET) {
                playAt(ModSounds.CRATE_CREAK.get(), 0.45F,
                    0.95F + settler.getRandom().nextFloat() * 0.1F);
            }
        }
        settler.getLookControl().setLookAt(dropOff.getX() + 0.5,
            dropOff.getY() + 0.6, dropOff.getZ() + 0.5);
        if (hasArrivedAt(warehouse)) {
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
                pathToDropOff();
            }
        }
    }

    /**
     * Arrival means <em>in the warehouse, at a chest</em> -- being within
     * reach through a wall is not arriving. The building's bounds come from
     * the plaque's room scan and include its shell, so a settler in the
     * doorway counts as inside while one outside the wall does not.
     */
    private boolean hasArrivedAt(Building warehouse) {
        BlockPos at = settler.blockPosition();
        if (warehouse.bounds != null && !warehouse.bounds.isInside(at)) {
            return false;
        }
        return at.distSqr(dropOff) <= DROP_OFF_REACH_SQR;
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
        Building warehouse = s == null ? null : findWarehouseById(s);
        if (warehouse == null) {
            mode = Mode.RETURNING; // dissolved mid-delivery: take them home
            stuckChecks = 0;
            repathTimer = 0;
            settler.setActivity(SettlerActivity.CARRYING);
            pathAbove(settler.getHearthPos());
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
                // Warehouse full: stop, and carry what is left back home.
                mode = Mode.RETURNING;
                stuckChecks = 0;
                repathTimer = 0;
                settler.setActivity(SettlerActivity.CARRYING);
                pathAbove(settler.getHearthPos());
            }
            return; // one stack per cycle -- the animation beat
        }
        consecutiveFailures = 0; // the route works; forget the bad streak
        done = true; // bag empty: delivery complete
    }

    /**
     * Carries an undeliverable load back to the hearth and puts it down.
     * The failure mode this exists to prevent is the quiet one: a courier
     * wandering off with the settlement's goods locked in her bag.
     */
    private void tickReturning() {
        if (bagCount() <= 0) {
            done = true;
            return;
        }
        BlockPos hearthPos = settler.getHearthPos();
        HearthBlockEntity hearth = settler.hearth();
        if (hearthPos == null || hearth == null) {
            // No hearth to return to -- a razed settlement, which raids are
            // meant to be able to cause. Keep the load and rest the route:
            // without the cooldown, canUse() re-selects RETURNING on the very
            // next tick and this becomes a one-tick busy loop.
            restRoute();
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5,
            hearthPos.getY() + 0.6, hearthPos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(hearthPos) <= HEARTH_REACH_SQR) {
            settler.getNavigation().stop();
            // Destination-first again: the hearth takes the stack before the
            // bag slot is overwritten with whatever it could not hold.
            for (int i = 0; i < settler.bag.getContainerSize(); i++) {
                ItemStack stack = settler.bag.getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                settler.bag.setItem(i, hearth.insertGoods(stack.copy()));
            }
            playAt(ModSounds.CHEST_STOW.get(), 0.65F,
                0.95F + settler.getRandom().nextFloat() * 0.1F);
            done = true;
        } else if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > HAUL_STUCK_LIMIT) {
                restRoute(); // cannot even get home; the bag keeps the goods
                done = true;
            } else {
                pathAbove(hearthPos);
            }
        }
    }

    /**
     * A route failed for real. Rest it for {@link #RETRY_COOLDOWN_TICKS} so
     * the courier does something else instead of re-entering this goal on
     * the very next tick, and bring any load home first.
     */
    private void giveUp() {
        restRoute();
        if (bagCount() > 0) {
            mode = Mode.RETURNING;
            stuckChecks = 0;
            repathTimer = 0;
            settler.setActivity(SettlerActivity.CARRYING);
            pathAbove(settler.getHearthPos());
        } else {
            done = true;
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

    private Building findWarehouseById(Settlement s) {
        if (warehouseId == null) {
            return null;
        }
        for (Building b : s.buildings) {
            if (b.id.equals(warehouseId) && b.valid) {
                return b;
            }
        }
        return null;
    }

    private void playAt(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        settler.level().playSound(null, settler.getX(), settler.getY(), settler.getZ(),
            sound, net.minecraft.sounds.SoundSource.NEUTRAL, volume, pitch);
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
    }
}
