package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Summons;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * "Come here." Walks a summoned settler to wherever a player called them
 * from the plaque, then stands and faces that spot until the schedule or the
 * next thing that matters takes over.
 *
 * <p>Registered at priority 3 (line owned by {@code SettlerEntity}, which is
 * not this file) — the same numeric slot as {@link GuardRespondToAlertGoal},
 * one step below the combat/panic band (0 {@link
 * net.minecraft.world.entity.ai.goal.FloatGoal}, 1 door-opening and {@link
 * SettlerPanicGoal}, 2 traveller-join and the guard's leap/melee) and one
 * step above {@link EatFromHearthGoal} (4) and {@link RestAtNightGoal} (5).
 * A summons is meant to outrank the settler's ordinary day — it is a player
 * asking for them by name — but the numeric slot alone would put it ahead of
 * eating too, which is backwards: nobody should starve because they were
 * fetched. Combat and panic are handled by priority ordering alone (this
 * goal and theirs share {@link Flag#MOVE}, so the lower-numbered goal wins
 * outright whenever both want to run); eating needs an explicit check below
 * because its number is the wrong way round for that to happen for free.
 *
 * <p>Interrupted rather than cancelled: losing the movement flag to
 * something above it (hunger, a target, panic) does not clear the summons —
 * {@link Summons#active} is still true afterwards, so this goal simply picks
 * the walk back up once whatever preempted it is done. Arrival, a route this
 * goal has proved unreachable, or the call's own ~90 s clock ends it for
 * real; see {@link Summons} for the guarantee that the glow always comes off
 * even if this goal never gets another turn to run.
 */
public class RespondToSummonsGoal extends Goal {

    /** Re-path this often while walking; mirrors {@link GoToPostGoal}. */
    private static final int REPATH_INTERVAL = 40;
    /** Give up after this much uninterrupted failed travel and say why. */
    private static final int PATIENCE = 300;
    /** A freshly spawned/reloaded navigator can reject its first path before its ground node settles. */
    private static final int INITIAL_PATH_RETRY_TICKS = 5;
    /** Three bounded attempts distinguish that transient setup from a sealed caller. */
    private static final int MAX_INITIAL_PATH_ATTEMPTS = 3;
    /** Close enough to call it arrived. */
    private static final double ARRIVE_RADIUS = 2.0;
    /**
     * {@link EatFromHearthGoal}'s own "hungry enough to eat right now"
     * threshold (its base one, not the wider mealtime one — a settler who is
     * merely peckish at dinner time still comes when called). Copied rather
     * than referenced: that file does not export it as a constant, and
     * EatFromHearthGoal is not this file's to change. If that threshold ever
     * moves, this one should move with it.
     */
    private static final float STARVING_HUNGER = 40.0F;

    private final SettlerEntity settler;
    private int repathTimer;
    private int walkedTicks;
    private int initialPathAttempts;
    private boolean arrived;
    private boolean unreachable;

    public RespondToSummonsGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!settler.isBound() || settler.getTarget() != null) {
            return false;
        }
        if (settler.getHunger() < STARVING_HUNGER) {
            return false; // starving outranks being called, whatever the slot number says
        }
        return Summons.active(settler);
    }

    @Override
    public boolean canContinueToUse() {
        if (arrived || !settler.isBound() || settler.getTarget() != null) {
            return false;
        }
        if (settler.getHunger() < STARVING_HUNGER) {
            return false; // yield the flag; Summons stays active, so this resumes once fed
        }
        return Summons.active(settler) && walkedTicks <= PATIENCE;
    }

    @Override
    public void start() {
        walkedTicks = 0;
        repathTimer = 0;
        initialPathAttempts = 0;
        arrived = false;
        unreachable = false;
        settler.setActivity(SettlerActivity.TRAVELING);
        path();
    }

    @Override
    public void tick() {
        BlockPos where = Summons.where(settler);
        if (where == null) {
            return; // expired between canContinueToUse's check and this tick; next poll ends it
        }
        if (settler.blockPosition().closerThan(where, ARRIVE_RADIUS)) {
            arrived = true;
            settler.getNavigation().stop();
            settler.getLookControl().setLookAt(where.getX() + 0.5, where.getY() + 1,
                where.getZ() + 0.5);
            Summons.clear(settler); // job done: this is the one place that ends the call outright
            return;
        }
        walkedTicks++;
        if (walkedTicks > PATIENCE) {
            abandonUnreachable();
            return;
        }
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            path();
        }
    }

    private void path() {
        BlockPos where = Summons.where(settler);
        if (where == null) {
            return;
        }
        var path = settler.getNavigation().createPath(where, 0);
        if (path != null && path.canReach()) {
            settler.getNavigation().moveTo(path, 1.0);
            initialPathAttempts = 0;
            return;
        }
        // A freshly spawned/reloaded navigator can reject its first route while
        // its grounded node has not settled. Retry twice on a short clock, then
        // end this one exact call: a sealed settler still clears its glow quickly
        // and cannot burn through the call's full ninety-second lifetime.
        if (++initialPathAttempts >= MAX_INITIAL_PATH_ATTEMPTS) {
            abandonUnreachable();
        } else {
            repathTimer = INITIAL_PATH_RETRY_TICKS;
        }
    }

    /** Ends only a route this goal itself has proved unavailable. */
    private void abandonUnreachable() {
        if (unreachable) {
            return;
        }
        unreachable = true;
        settler.recordRouteFailure("summons_unreachable");
        settler.getNavigation().stop();
        Summons.clear(settler);
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
        arrived = false;
        unreachable = false;
        initialPathAttempts = 0;
        walkedTicks = 0;
    }
}
