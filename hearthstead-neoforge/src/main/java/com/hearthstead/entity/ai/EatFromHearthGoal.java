package com.hearthstead.entity.ai;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/** Hungry settlers fetch a meal from the communal hearth stores. */
public class EatFromHearthGoal extends Goal {
    /** Meal length in ticks; EAT's chew cycle runs inside it. */
    public static final int EAT_DURATION = 40;
    // EAT's bite-accent contract (catalogue §12.3): must agree with the
    // clip comment in SettlerAnimations and tools/anim_check.py.
    public static final int EAT_BITE_PERIOD = 24;
    public static final int EAT_BITE_TICK_A = 5;
    public static final int EAT_BITE_TICK_B = 14;

    private final SettlerEntity settler;
    private int cooldown;
    private int repathTimer;
    private int fetchTicks;
    private boolean done;
    private Vec3 lastPartialPosition;
    private BlockPos lastPartialEnd;

    public EatFromHearthGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // The mounted visit owns MOVE and advances the same durable meal.
        if (settler.hasTavernSeat()) return false;
        if (settler.hasMeal() && settler.isAlive()) {
            return true;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (!settler.isBound()) {
            return false;
        }
        float hunger = settler.getHunger();
        // Hunger drives eating at any hour, but the village also eats TOGETHER
        // at midday and again in the evening -- a settler who is merely
        // peckish will still come to the table. That shared sitting is what
        // makes a dining hall worth building and what fills the square at
        // noon; without it everyone grazes alone whenever their own bar dips.
        boolean mealtime = settler.dayPhase().meal() || settler.dayPhase().social();
        boolean hungry = hunger < 40 || (mealtime && hunger < 75);
        if (!hungry) {
            return false;
        }
        HearthBlockEntity hearth = settler.hearth();
        if (hearth == null || hearth.countFoodUnits() <= 0) {
            cooldown = 100;
            return false;
        }
        // A concrete reachable meal offer may win the existing visit goal.
        // Its shared cooldown/attempt owns the wait; this predicate owns no seat or item.
        if (hunger >= 40 && mealtime && settler.prefersTavernMeal()) return false;
        return true;
    }

    @Override
    public void start() {
        done = false;
        fetchTicks = 0;
        repathTimer = 40;
        lastPartialPosition = null;
        lastPartialEnd = null;
        if (settler.hasMeal()) {
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.EATING);
        } else {
            path();
        }
    }

    private void path() {
        if (!(settler.level() instanceof ServerLevel level)) return;
        BlockPos hearth = settler.getHearthPos();
        if (hearth == null) return;
        // Food/ray authority remains canContactHearth at the actual
        // withdrawal tick. Civilian hunger may move along one strictly nearer
        // Road prefix while the full contact route is outside follow range.
        // Guard and other essential-order callers use the same bounded
        // progressing probe when a safe essential meal must temporarily
        // yield a persisted post.
        Path route = HearthApproach.findCivilianMealContactPath(settler, level, hearth);
        if (route == null) return;
        if (route.canReach()) {
            lastPartialPosition = null;
            lastPartialEnd = null;
            settler.getNavigation().moveTo(route, 1.0);
            return;
        }
        if (route.getNodeCount() <= 0) return;
        Vec3 start = settler.position();
        BlockPos end = route.getNodePos(route.getNodeCount() - 1);
        // Do not continually reinstall a stale prefix. A physical move, even
        // within the same block, permits the next normal repath to reuse it.
        if (lastPartialPosition != null && start.distanceToSqr(lastPartialPosition) < 0.0001D
            && end.equals(lastPartialEnd)) return;
        lastPartialPosition = start;
        lastPartialEnd = end.immutable();
        settler.getNavigation().moveTo(route, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        return !done && settler.isAlive() && !settler.hasTavernSeat()
            && (settler.hasMeal() || settler.isBound() && settler.getHearthPos() != null);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // Vanilla ticks every-tick goals once more after tick() finished them,
        // without canContinueToUse() (see CrafterWorkGoal.tick): never act again.
        if (done) return;
        if (settler.hasMeal()) {
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.EATING);
            done = settler.tickMeal();
            return;
        }
        BlockPos hearthPos = settler.getHearthPos();
        if (hearthPos == null || ++fetchTicks > 360) {
            done = true;
            return;
        }
        settler.getLookControl().setLookAt(hearthPos.getX() + 0.5, hearthPos.getY() + 0.6,
            hearthPos.getZ() + 0.5);
        if (canContactHearth(hearthPos)) {
            HearthBlockEntity hearth = settler.hearth();
            if (hearth == null) {
                done = true;
                return;
            }
            // Transfer from the real slot, not an extracted temporary whose
            // failure could disappear. Same best-nutrition ordering as ReadyFood.
            var inventory = hearth.getInventory();
            int best = -1;
            int nutrition = -1;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack live = inventory.getStackInSlot(slot);
                FoodProperties food = com.hearthstead.settlement.ReadyFood.isReadyMeal(live) ? live.getFoodProperties(null) : null;
                if (food != null && food.nutrition() > nutrition) {
                    best = slot;
                    nutrition = food.nutrition();
                }
            }
            if (best < 0) {
                done = true;
                return;
            }
            ItemStack live = inventory.getStackInSlot(best);
            if (!settler.beginMeal(live)) {
                done = true;
                return;
            }
            inventory.setStackInSlot(best, live); // Dirty and invalidate food cache.
            settler.getNavigation().stop();
            settler.setActivity(SettlerActivity.EATING);
        } else if (--repathTimer <= 0) {
            repathTimer = 40;
            path();
        }
    }

    private boolean canContactHearth(BlockPos target) {
        if (!(settler.level() instanceof ServerLevel level)
            || !level.hasChunkAt(target)
            || settler.blockPosition().distSqr(target) > 6.25) {
            return false;
        }
        Vec3 eye = settler.getEyePosition();
        Vec3 sample = Vec3.atCenterOf(target);
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(eye), target)) {
            if (!level.hasChunkAt(cell)) return false;
        }
        var hit = level.clip(new ClipContext(eye, sample,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, settler));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
        cooldown = 100;
        lastPartialPosition = null;
        lastPartialEnd = null;
        // The resident owns the exact food and remaining ticks across stops.
        // Danger, job changes and reload pause eating; they never discard it.
    }
}
