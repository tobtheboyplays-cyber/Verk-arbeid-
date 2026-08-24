package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

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
 */
public class CourierWorkGoal extends Goal {

    /** Ticks of the SORTING loop; one stack moves per cycle. */
    public static final int SORT_PERIOD = 32;
    /** Tick within the sort cycle at which the stack actually moves. */
    public static final int SORT_MOVE_TICK = 16;
    /** How much the courier carries per trip; the sack's capacity (D-007). */
    private static final int LOAD_TRIGGER = 8;

    private enum Mode { TO_HEARTH, LOADING, TO_WAREHOUSE, SORTING }

    private final SettlerEntity settler;
    private Mode mode;
    private BlockPos warehousePos;
    private java.util.UUID warehouseId;
    private int workTicks;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public CourierWorkGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.COURIER
            || !settler.isBound()
            || settler.dayPhase() != SettlerEntity.DayPhase.WORK
            || settler.getEnergy() <= 15) {
            return false;
        }
        Settlement s = settler.settlement();
        if (s == null || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        Building warehouse = pickWarehouse(level, s);
        if (warehouse == null) {
            return false; // idle visibly rather than thrash (MineColonies #2932)
        }
        warehouseId = warehouse.id;
        warehousePos = warehouse.anchor;

        // Already carrying? Finish the delivery before starting another.
        if (bagCount() > 0) {
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
    private Building pickWarehouse(ServerLevel level, Settlement s) {
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
        return !done && settler.isBound() && warehousePos != null;
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
        if (mode == Mode.TO_WAREHOUSE) {
            settler.setActivity(SettlerActivity.CARRYING);
            pathTo(warehousePos);
        } else {
            settler.setActivity(SettlerActivity.TRAVELING);
            pathTo(settler.getHearthPos());
        }
    }

    private void pathTo(BlockPos pos) {
        if (pos != null) {
            settler.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 1,
                pos.getZ() + 0.5, 0.95);
        }
    }

    @Override
    public void tick() {
        switch (mode) {
            case TO_HEARTH -> tickToHearth();
            case LOADING -> tickLoading();
            case TO_WAREHOUSE -> tickToWarehouse();
            case SORTING -> tickSorting();
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
        if (settler.blockPosition().distSqr(hearthPos) <= 6.25) {
            settler.getNavigation().stop();
            mode = Mode.LOADING;
            workTicks = 0;
            settler.setActivity(SettlerActivity.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 8) {
                done = true;
            } else {
                pathTo(hearthPos);
            }
        }
    }

    /** Lifts one bag-load of non-food goods out of the hearth. */
    private void tickLoading() {
        workTicks++;
        if (workTicks < SORT_PERIOD / 2) {
            return;
        }
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null) {
            done = true;
            return;
        }
        var inv = hearth.getInventory();
        for (int slot = 0; slot < inv.getSlots() && bagCount() < LOAD_TRIGGER; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (!isHaulable(stack)) {
                continue;
            }
            int want = Math.min(stack.getCount(), LOAD_TRIGGER - bagCount());
            // Extract first, then bank it. The extracted stack exists in a
            // local only for the moment between these two lines, and the
            // remainder goes straight back, so no path loses an item.
            ItemStack taken = inv.extractItem(slot, want, false);
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
        pathTo(warehousePos);
    }

    private void tickToWarehouse() {
        if (warehousePos == null) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(warehousePos.getX() + 0.5,
            warehousePos.getY() + 0.6, warehousePos.getZ() + 0.5);
        if (settler.blockPosition().distSqr(warehousePos) <= 9.0) {
            settler.getNavigation().stop();
            mode = Mode.SORTING;
            workTicks = 0;
            settler.setActivity(SettlerActivity.SORTING);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            if (++stuckChecks > 12) {
                // Cannot reach the warehouse. Keep the load rather than
                // dropping it, and end the goal; the bag is persisted and
                // drops on death, so nothing is lost either way.
                done = true;
            } else {
                pathTo(warehousePos);
            }
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
        Building warehouse = s == null ? null : findWarehouseById(s);
        if (warehouse == null) {
            // The warehouse was dissolved mid-delivery. Keep the goods.
            done = true;
            return;
        }
        WarehouseStorage storage = WarehouseStorage.of(level, warehouse);
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            ItemStack stack = settler.bag.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = storage.insert(level, warehouse, stack.copy());
            settler.bag.setItem(i, leftover);
            if (!leftover.isEmpty()) {
                // Warehouse full: stop, keep what is left, go home with it.
                done = true;
            }
            return; // one stack per cycle -- the animation beat
        }
        done = true; // bag empty: delivery complete
    }

    private Building findWarehouseById(Settlement s) {
        for (Building b : s.buildings) {
            if (b.id.equals(warehouseId) && b.valid) {
                return b;
            }
        }
        return null;
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
    }
}
