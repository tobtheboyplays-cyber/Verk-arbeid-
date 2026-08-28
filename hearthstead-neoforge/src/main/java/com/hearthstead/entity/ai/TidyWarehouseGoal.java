package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The courier tidies the warehouse when there is nothing to fetch.
 *
 * <p>Owner's ask, 2026-08-25: <i>"Vil også at en jobb curior at han rydder i
 * kistene i lageret."</i> And it is the right job for the idle half of a
 * courier's day — a warehouse that has been running for a week is full of
 * half stacks, one chest with nine wheat and another with three, and a player
 * opening it should see the difference somebody made.
 *
 * <h2>It only ever merges</h2>
 *
 * <p>Every move is either a partial stack poured into another partial stack
 * of the <b>same item</b>, or one whole stack relocated into an empty slot
 * beside a sibling stack of the same item in a different chest. Nothing is
 * created, nothing is destroyed, nothing changes kind — so this cannot
 * violate chest truth (INV-3) even if it is interrupted halfway, because the
 * interrupted state is just "some of it moved" or "one stack changed chest".
 *
 * <h2>It is visibly slow</h2>
 *
 * <p>One move per sort cycle, on the beat of {@code COURIER_SORT}. Instant
 * tidying would be a button press disguised as a settler; the point is that
 * you can watch somebody do it.
 *
 * <h2>It converges and stops</h2>
 *
 * <p>Both move kinds are picked by a deterministic scan — the same
 * container/slot order every time the world underneath is unchanged — and
 * both strictly shrink a bounded, non-negative quantity, so repeated tidies
 * cannot churn forever:
 *
 * <ul>
 *   <li>{@link #findMerge} only fires when it can either empty a partial
 *       stack completely or fill one to its max, so the count of
 *       non-empty/non-full slots for that item strictly drops by one.
 *   <li>{@link #findRelocation} only fires when a stack sits in a chest
 *       that is NOT that item's first-seen ("home") chest, and moves it
 *       there — so the count of distinct chests holding that item, summed
 *       over every item, strictly drops by one.
 * </ul>
 *
 * <p>Both quantities are bounded below by zero and bounded above by the
 * warehouse's own (capped) container/slot count, so a warehouse that has
 * gone quiet — {@link #canContinueToUse} returning false because neither
 * finder has anything left — stays quiet: the very next tidy asks the same
 * deterministic questions and gets the same "nothing to do" answer, which is
 * exactly idempotence.
 */
public class TidyWarehouseGoal extends Goal {

    /** Ticks per move — the sort clip's own loop. */
    private static final int SORT_PERIOD = 32;
    /** Re-check for untidiness this often when idle. */
    private static final int LOOK_INTERVAL = 60;
    /**
     * How often {@link #canContinueToUse} re-runs the finders while the goal
     * is already active, rather than every single tick. Both finders are
     * bounded (capped containers x slots), but re-scanning that on every one
     * of the ~30 ticks between moves is unbudgeted work for no benefit: a
     * stale "yes, keep going" between checks is harmless, because
     * {@link #tick} re-validates with its own fresh scan before it ever acts,
     * and the worst case is standing at post for a few extra ticks after the
     * last real move before this goal notices and quietly ends.
     */
    private static final int CONTINUE_CHECK_INTERVAL = 8;

    private final SettlerEntity settler;
    private Building warehouse;
    private int workTicks;
    private int lookCooldown;
    private int continueCheckCooldown;
    private int merges;

    public TidyWarehouseGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() != Profession.COURIER || !settler.isBound()
            || settler.getTarget() != null || settler.getEnergy() <= 15.0F) {
            return false;
        }
        // A load in hand is a delivery in progress; that outranks tidying.
        if (!settler.bag.isEmpty()) {
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
        if (settlement == null
            || !Schedule.shouldWork(settlement, settler, settler.dayPhase())) {
            return false;
        }
        Building home = Employment.employerOf(settlement, settler.getUUID());
        if (home == null || !home.valid || home.anchor == null) {
            return false;
        }
        if (!hasWorkToDo(level, home)) {
            return false;
        }
        warehouse = home;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (warehouse == null || settler.getTarget() != null
            || !settler.bag.isEmpty()) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null
            || !Schedule.shouldWork(settlement, settler, settler.dayPhase())
            || !(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        // Budgeted re-check (see CONTINUE_CHECK_INTERVAL): trust the last
        // answer between checks rather than re-scanning every tick.
        if (continueCheckCooldown > 0) {
            continueCheckCooldown--;
            return true;
        }
        continueCheckCooldown = CONTINUE_CHECK_INTERVAL;
        return hasWorkToDo(level, warehouse);
    }

    @Override
    public void start() {
        workTicks = 0;
        merges = 0;
        continueCheckCooldown = 0;
        settler.setActivity(SettlerActivity.SORTING);
        settler.getNavigation().moveTo(warehouse.anchor.getX() + 0.5,
            warehouse.anchor.getY(), warehouse.anchor.getZ() + 0.5, 0.8);
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        settler.getNavigation().stop();
        warehouse = null;
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        if (!settler.blockPosition().closerThan(warehouse.anchor, Schedule.AT_POST)) {
            if (settler.getNavigation().isDone()) {
                settler.getNavigation().moveTo(warehouse.anchor.getX() + 0.5,
                    warehouse.anchor.getY(), warehouse.anchor.getZ() + 0.5, 0.8);
            }
            return;
        }
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.SORTING);
        if (++workTicks % SORT_PERIOD != 0) {
            return;
        }
        Merge merge = findMerge(level, warehouse);
        if (merge == null) {
            tickRelocation(level);
            return;
        }
        ItemStack from = merge.from().getItem(merge.fromSlot());
        ItemStack into = merge.into().getItem(merge.intoSlot());
        int move = Math.min(from.getCount(), into.getMaxStackSize() - into.getCount());
        if (move <= 0) {
            return;
        }
        into.grow(move);
        from.shrink(move);
        if (from.isEmpty()) {
            merge.from().setItem(merge.fromSlot(), ItemStack.EMPTY);
        }
        merge.from().setChanged();
        merge.into().setChanged();
        level.playSound(null, settler.blockPosition(), ModSounds.CHEST_STOW.get(),
            SoundSource.NEUTRAL, 0.6F,
            0.95F + settler.getRandom().nextFloat() * 0.1F);
        // Tidying is real work, and it is the work of carrying things about.
        settler.train(Attribute.STAMINA, 1.0F);
        merges++;
    }

    /**
     * Once no partial stacks are left to merge, a full stack of an item that
     * already lives in another chest is still not "grouped" — it is only
     * maxed out where it happens to sit. Moves it whole into an empty slot
     * beside its siblings, which is a real move (not a split), so it never
     * needs its own "leftover" bookkeeping the way a merge does.
     */
    private void tickRelocation(ServerLevel level) {
        Relocation relocation = findRelocation(level, warehouse);
        if (relocation == null) {
            return;
        }
        ItemStack moving = relocation.from().getItem(relocation.fromSlot());
        relocation.into().setItem(relocation.intoSlot(), moving.copy());
        relocation.from().setItem(relocation.fromSlot(), ItemStack.EMPTY);
        relocation.from().setChanged();
        relocation.into().setChanged();
        level.playSound(null, settler.blockPosition(), ModSounds.CHEST_STOW.get(),
            SoundSource.NEUTRAL, 0.6F,
            0.95F + settler.getRandom().nextFloat() * 0.1F);
        settler.train(Attribute.STAMINA, 1.0F);
        merges++;
    }

    /** How many merges this settler has made in the current stint. */
    public int mergesThisStint() {
        return merges;
    }

    /** Whether either finder below has anything left to do, without acting. */
    private static boolean hasWorkToDo(ServerLevel level, Building building) {
        return findMerge(level, building) != null || findRelocation(level, building) != null;
    }

    // ------------------------------------------------------------ helpers ---

    /** Two partial stacks of the same item that ought to be one. */
    private record Merge(Container from, int fromSlot, Container into, int intoSlot) {
    }

    /** One whole stack that belongs beside a sibling stack in another chest. */
    private record Relocation(Container from, int fromSlot, Container into, int intoSlot) {
    }

    private static List<Container> containers(ServerLevel level, Building building) {
        List<Container> found = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                found.add(container);
            }
        }
        return found;
    }

    /**
     * The first pair worth merging.
     *
     * <p>Deliberately returns the FIRST rather than the best: a courier is
     * tidying, not solving a packing problem, and finding one pair is O(slots)
     * where finding the optimal set is not.
     */
    private static Merge findMerge(ServerLevel level, Building building) {
        List<Container> containers = containers(level, building);
        List<Object[]> partials = new ArrayList<>();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.getCount() >= stack.getMaxStackSize()) {
                    continue;
                }
                for (Object[] other : partials) {
                    Container oc = (Container) other[0];
                    int os = (int) other[1];
                    ItemStack existing = oc.getItem(os);
                    if (!existing.isEmpty()
                        && ItemStack.isSameItemSameComponents(existing, stack)
                        && existing.getCount() < existing.getMaxStackSize()) {
                        // Pour the smaller into the larger, so a chest empties
                        // out rather than everything ending up half full.
                        return existing.getCount() >= stack.getCount()
                            ? new Merge(container, slot, oc, os)
                            : new Merge(oc, os, container, slot);
                    }
                }
                partials.add(new Object[]{container, slot});
            }
        }
        return null;
    }

    /**
     * A whole stack sitting somewhere other than that item's "home" chest —
     * the first chest, in scan order, this pass finds holding it — with an
     * empty slot open in the home chest to take it.
     *
     * <p>Deterministic for the same reason {@link #findMerge} is: "home" is
     * always the first-in-scan-order chest for a given world state, so two
     * consecutive calls against an unchanged warehouse make the same choice.
     * Only fires once {@link #findMerge} has nothing left, so a warehouse
     * with genuine partial stacks to pour is tidied before whole stacks are
     * shuffled between chests — cheaper progress first.
     */
    private static Relocation findRelocation(ServerLevel level, Building building) {
        List<Container> containers = containers(level, building);
        java.util.Map<Item, Container> home = new java.util.LinkedHashMap<>();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    home.putIfAbsent(stack.getItem(), container);
                }
            }
        }
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                Container target = home.get(stack.getItem());
                if (target == null || target == container) {
                    continue; // this already IS the home chest for this item
                }
                int destSlot = firstEmptySlot(target);
                if (destSlot < 0) {
                    continue; // home chest is full; nothing to do about that here
                }
                return new Relocation(container, slot, target, destSlot);
            }
        }
        return null;
    }

    private static int firstEmptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }
}
