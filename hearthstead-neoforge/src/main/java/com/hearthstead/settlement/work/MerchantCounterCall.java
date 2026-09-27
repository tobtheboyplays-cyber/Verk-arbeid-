package com.hearthstead.settlement.work;

import com.hearthstead.event.EarlyCoinMerchant;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.WanderingTrader;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * TRADER lane: the visiting merchant is asked to step up to the Trading Post's
 * counter (the Trader's shop) and stand there while the deal is struck.
 *
 * <p>It is a LEASE, never a takeover: the Trader's goal refreshes it every tick
 * it wants the merchant at the counter, and it lapses by itself
 * {@link #LEASE_TICKS} after the last refresh (a stopped goal, an unloaded
 * Trader, a restart). While it runs the merchant walks to the merchant cell
 * and waits there facing the Trader. Rules (owner/coordinator, 26 Sep):
 * <ul>
 *   <li>a player trading with or talking to the merchant always wins: the goal
 *   will not start, stops at once, and vanilla's TradeWithPlayerGoal (priority
 *   1) outranks it anyway;</li>
 *   <li>it never touches the visit timer, despawn delay, restriction, wander
 *   target or arrival bookkeeping of {@link EarlyCoinMerchant};</li>
 *   <li>a merchant still walking in to the Banner (arrival goal, stuck
 *   recovery) is never called: {@link #mayCall} requires the arrival to be
 *   finished, so the two goals are never live together.</li>
 * </ul>
 * If the cell cannot be reached the call is marked unreachable and the Trader
 * walks out to the merchant instead.
 */
public final class MerchantCounterCall {
    public static final String TAG = "HearthsteadCounterCall";
    /** A call lapses this long after its last refresh. */
    public static final int LEASE_TICKS = 40;
    /** The merchant counts as standing at the counter within this horizontal distance of the cell centre. */
    static final double AT_CELL = 0.7;
    static final double WALK_SPEED = 0.6;
    private static final String ARRIVAL_TARGET = "HearthsteadMerchantArrivalTarget";
    private static final String ARRIVED = "HearthsteadMerchantArrived";
    private static final Map<WanderingTrader, Boolean> INSTALLED = new WeakHashMap<>();

    private MerchantCounterCall() {
    }

    /** A player is trading with or talking to him. */
    public static boolean busy(WanderingTrader merchant) {
        if (merchant.getTradingPlayer() != null) {
            return true;
        }
        try {
            return com.hearthstead.conversation.ConversationService.isTalking(merchant);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** He may be asked to the counter now: alive, visiting, not busy, done walking in. */
    public static boolean mayCall(WanderingTrader merchant) {
        if (merchant == null || !merchant.isAlive() || merchant.isPassenger() || merchant.isLeashed()
            || !EarlyCoinMerchant.availableForTrade(merchant) || busy(merchant)) {
            return false;
        }
        CompoundTag data = merchant.getPersistentData();
        return data.getBoolean(ARRIVED) || !data.contains(ARRIVAL_TARGET, Tag.TAG_LONG);
    }

    /** Places or refreshes the lease. Returns false when he may not be called right now. */
    public static boolean call(ServerLevel level, WanderingTrader merchant, TraderCounter.Spot spot, Entity trader) {
        if (!mayCall(merchant)) {
            return false;
        }
        ensureGoal(merchant);
        CompoundTag data = merchant.getPersistentData();
        CompoundTag call = data.getCompound(TAG);
        long cell = spot.merchantCell().asLong();
        if (!call.contains("Cell", Tag.TAG_LONG) || call.getLong("Cell") != cell) {
            call = new CompoundTag();
            call.putLong("Cell", cell);
        }
        call.putLong("Counter", spot.counter().asLong());
        call.putUUID("Trader", trader.getUUID());
        call.putLong("Until", level.getGameTime() + LEASE_TICKS);
        data.put(TAG, call);
        return true;
    }

    public static void release(@Nullable WanderingTrader merchant) {
        if (merchant != null) {
            merchant.getPersistentData().remove(TAG);
        }
    }

    public static boolean unreachable(WanderingTrader merchant) {
        return merchant.getPersistentData().getCompound(TAG).getBoolean("Unreachable");
    }

    /** He is standing at the merchant cell of this spot. */
    public static boolean arrived(WanderingTrader merchant, TraderCounter.Spot spot) {
        return atCell(merchant.getX(), merchant.getY(), merchant.getZ(), spot.merchantCell());
    }

    static boolean atCell(double x, double y, double z, BlockPos cell) {
        double dx = x - (cell.getX() + 0.5);
        double dz = z - (cell.getZ() + 0.5);
        return dx * dx + dz * dz <= AT_CELL * AT_CELL && Math.abs(y - cell.getY()) < 0.75;
    }

    private static void ensureGoal(WanderingTrader merchant) {
        if (INSTALLED.containsKey(merchant)) {
            return;
        }
        for (var wrapped : merchant.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof CounterGoal) {
                INSTALLED.put(merchant, Boolean.TRUE);
                return;
            }
        }
        // Same priority as vanilla WanderToPositionGoal and the arrival goal:
        // it never interrupts either, and panic / avoid / player trade (1) win.
        merchant.goalSelector.addGoal(2, new CounterGoal(merchant));
        INSTALLED.put(merchant, Boolean.TRUE);
    }

    private static final class CounterGoal extends Goal {
        private final WanderingTrader merchant;
        private long nextPath;

        CounterGoal(WanderingTrader merchant) {
            this.merchant = merchant;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private CompoundTag live() {
            CompoundTag call = merchant.getPersistentData().getCompound(TAG);
            if (!call.contains("Cell", Tag.TAG_LONG) || call.getBoolean("Unreachable")) {
                return null;
            }
            if (call.getLong("Until") < merchant.level().getGameTime()) {
                return null;
            }
            return call;
        }

        @Override
        public boolean canUse() {
            return live() != null && mayCall(merchant) && !merchant.isInWater();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            nextPath = 0L;
        }

        @Override
        public void stop() {
            merchant.getNavigation().stop();
        }

        @Override
        public void tick() {
            CompoundTag call = live();
            if (call == null || !(merchant.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos cell = BlockPos.of(call.getLong("Cell"));
            BlockPos counter = BlockPos.of(call.getLong("Counter"));
            if (!atCell(merchant.getX(), merchant.getY(), merchant.getZ(), cell)) {
                if (level.getGameTime() >= nextPath || merchant.getNavigation().isDone()) {
                    nextPath = level.getGameTime() + 20L;
                    var path = merchant.getNavigation().createPath(cell, 0);
                    if (path == null || !path.canReach()) {
                        call.putBoolean("Unreachable", true);
                        merchant.getPersistentData().put(TAG, call);
                        merchant.getNavigation().stop();
                        return;
                    }
                    merchant.getNavigation().moveTo(path, WALK_SPEED);
                }
                merchant.getLookControl().setLookAt(counter.getX() + 0.5, counter.getY() + 1.2,
                    counter.getZ() + 0.5);
                return;
            }
            merchant.getNavigation().stop();
            Entity trader = call.hasUUID("Trader") ? level.getEntity(call.getUUID("Trader")) : null;
            if (trader != null) {
                merchant.getLookControl().setLookAt(trader, 30.0F, 30.0F);
            } else {
                merchant.getLookControl().setLookAt(counter.getX() + 0.5, counter.getY() + 1.2,
                    counter.getZ() + 0.5);
            }
        }
    }

    /** Test/diagnostic view of the live lease, or null. */
    @Nullable
    public static UUID callerOf(WanderingTrader merchant) {
        CompoundTag call = merchant.getPersistentData().getCompound(TAG);
        return call.hasUUID("Trader") && call.getLong("Until") >= merchant.level().getGameTime()
            ? call.getUUID("Trader") : null;
    }
}
