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
 * <p>Every move is a partial stack poured into another partial stack of the
 * <b>same item</b>. Nothing is created, nothing is destroyed, nothing changes
 * kind — so this cannot violate chest truth (INV-3) even if it is interrupted
 * halfway, because the interrupted state is just "some of it moved".
 *
 * <h2>It is visibly slow</h2>
 *
 * <p>One merge per sort cycle, on the beat of {@code COURIER_SORT}. Instant
 * tidying would be a button press disguised as a settler; the point is that
 * you can watch somebody do it.
 */
public class TidyWarehouseGoal extends Goal {

    /** Ticks per merge — the sort clip's own loop. */
    private static final int SORT_PERIOD = 32;
    /** Re-check for untidiness this often when idle. */
    private static final int LOOK_INTERVAL = 60;

    private final SettlerEntity settler;
    private Building warehouse;
    private int workTicks;
    private int lookCooldown;
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
        if (findMerge(level, home) == null) {
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
        return settlement != null
            && Schedule.shouldWork(settlement, settler, settler.dayPhase())
            && settler.level() instanceof ServerLevel level
            && findMerge(level, warehouse) != null;
    }

    @Override
    public void start() {
        workTicks = 0;
        merges = 0;
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

    /** How many merges this settler has made in the current stint. */
    public int mergesThisStint() {
        return merges;
    }

    // ------------------------------------------------------------ helpers ---

    /** Two partial stacks of the same item that ought to be one. */
    private record Merge(Container from, int fromSlot, Container into, int intoSlot) {
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
}
