package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.building.BuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumSet;
import java.util.List;

/**
 * The KORN objective: come for the stores, take what can be carried, leave.
 *
 * <p><b>Theft is physical.</b> Goods are pulled out of real containers into
 * the raider's own real inventory, and dropped again if that raider is
 * killed. MineColonies' raids leave nothing behind but a chat line and a day
 * of mourning, and its own feature requests (#113, #129) are asking for
 * consequences that outlive the fight. A stolen stack you can chase down and
 * take back is that consequence, and a counter decrement could never be.
 *
 * <p>Deliberately ordered: take first, then leave. A raider that dies mid-
 * theft has the goods on it, so at every instant the items exist in exactly
 * one place -- the same conservation rule the courier follows.
 */
public class RaiderLootGoal extends Goal {

    /** Reach at which a raider can pull from a container. */
    private static final double REACH_SQR = 6.25;
    /** Ticks between grabs, so looting is visible rather than instant. */
    public static final int GRAB_PERIOD = 20;
    /** Re-path cadence while closing on the stores. */
    private static final int REPATH_INTERVAL = 15;
    /** Re-paths before this raider gives up on the stores and just fights. */
    private static final int STUCK_LIMIT = 40;
    /** How far past the settlement edge counts as having got away. */
    public static final int ESCAPE_MARGIN = 40;

    private enum Mode { TO_STORES, LOOTING, WITHDRAWING }

    private final RaiderEntity raider;
    private Mode mode;
    private BlockPos target;
    private int workTicks;
    private int repathTimer;
    private int stuckChecks;
    private boolean done;

    public RaiderLootGoal(RaiderEntity raider) {
        this.raider = raider;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (raider.objective() != RaidObjective.KORN) {
            return false;
        }
        if (!(raider.level() instanceof ServerLevel level)) {
            return false;
        }
        // Already carrying? Get it out of the settlement.
        if (raider.lootCount() > 0) {
            mode = Mode.WITHDRAWING;
            return true;
        }
        Settlement s = raider.settlement();
        if (s == null) {
            return false;
        }
        target = nearestStore(level, s);
        if (target == null) {
            return false; // nothing worth taking; the melee goals take over
        }
        mode = Mode.TO_STORES;
        return true;
    }

    /** The nearest container in any warehouse this settlement has. */
    private BlockPos nearestStore(ServerLevel level, Settlement settlement) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos from = raider.blockPosition();
        for (Building b : settlement.buildings) {
            if (!b.valid || b.type != BuildingType.WAREHOUSE) {
                continue;
            }
            for (BlockPos pos : WarehouseIndex.containers(level, b)) {
                if (containerAt(level, pos) == null || isEmpty(level, pos)) {
                    continue;
                }
                double d = from.distSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos;
                }
            }
        }
        return best;
    }

    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    private static boolean isEmpty(ServerLevel level, BlockPos pos) {
        Container c = containerAt(level, pos);
        if (c == null) {
            return true;
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (!c.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && raider.isAlive();
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
    }

    @Override
    public void tick() {
        switch (mode) {
            case TO_STORES -> tickToStores();
            case LOOTING -> tickLooting();
            case WITHDRAWING -> tickWithdrawing();
        }
    }

    private void tickToStores() {
        if (target == null || !(raider.level() instanceof ServerLevel)) {
            done = true;
            return;
        }
        if (raider.blockPosition().distSqr(target) <= REACH_SQR) {
            raider.getNavigation().stop();
            mode = Mode.LOOTING;
            workTicks = 0;
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            if (++stuckChecks > STUCK_LIMIT) {
                done = true; // cannot reach the stores; fall back to fighting
            } else {
                raider.getNavigation().moveTo(target.getX() + 0.5,
                    target.getY(), target.getZ() + 0.5, 1.0);
            }
        }
    }

    /** One stack per grab, so a robbery is something you can watch happen. */
    private void tickLooting() {
        workTicks++;
        if (workTicks % GRAB_PERIOD != 0) {
            return;
        }
        if (!(raider.level() instanceof ServerLevel level)) {
            done = true;
            return;
        }
        Container store = containerAt(level, target);
        if (store == null) {
            done = true;
            return;
        }
        for (int slot = 0; slot < store.getContainerSize(); slot++) {
            ItemStack stack = store.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // Destination first: the raider holds it before the chest loses
            // it, so an interruption at any instant conserves the item.
            ItemStack taken = store.removeItem(slot, stack.getCount());
            ItemStack leftover = raider.loot.addItem(taken);
            if (!leftover.isEmpty()) {
                store.setItem(slot, leftover); // full: put back what will not fit
                mode = Mode.WITHDRAWING;
                stuckChecks = 0;
                repathTimer = 0;
            }
            store.setChanged();
            return;
        }
        // Nothing left here. Leave with whatever was taken.
        mode = raider.lootCount() > 0 ? Mode.WITHDRAWING : Mode.TO_STORES;
        stuckChecks = 0;
        repathTimer = 0;
        if (mode == Mode.TO_STORES) {
            Settlement s = raider.settlement();
            target = s == null ? null : nearestStore(level, s);
            if (target == null) {
                done = true;
            }
        }
    }

    /** Out past the settlement edge, where the goods are genuinely gone. */
    private void tickWithdrawing() {
        Settlement s = raider.settlement();
        if (s == null) {
            done = true;
            return;
        }
        double escapeAt = s.radius + ESCAPE_MARGIN;
        if (raider.blockPosition().distSqr(s.center) >= escapeAt * escapeAt) {
            // Got away. The settlement finds out it has lost the goods.
            s.raidLootEscaped = true;
            raider.discard();
            done = true;
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            BlockPos away = fleePoint(s);
            raider.getNavigation().moveTo(away.getX() + 0.5, away.getY(),
                away.getZ() + 0.5, 1.15);
        }
    }

    /** Straight out along the bearing they arrived on, and keep going. */
    private BlockPos fleePoint(Settlement settlement) {
        BlockPos here = raider.blockPosition();
        int dx = here.getX() - settlement.center.getX();
        int dz = here.getZ() - settlement.center.getZ();
        double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        int reach = settlement.radius + ESCAPE_MARGIN + 8;
        return new BlockPos(
            settlement.center.getX() + (int) Math.round(dx / len * reach),
            here.getY(),
            settlement.center.getZ() + (int) Math.round(dz / len * reach));
    }

    @Override
    public void stop() {
        raider.getNavigation().stop();
    }

    /** Test seam: what this raider is currently doing. */
    public String debugMode() {
        return mode == null ? "none" : mode.name();
    }

    private static List<BlockPos> unused() {
        return List.of();
    }
}
