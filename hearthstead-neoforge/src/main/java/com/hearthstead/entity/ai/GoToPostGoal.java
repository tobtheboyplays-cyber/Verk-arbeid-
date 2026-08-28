package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Walk to where the day says you should be.
 *
 * <p>This is the goal that turns a schedule into something you can watch. At
 * dawn the village leaves its houses; at the work bell it splits up and each
 * settler goes to <b>their own building</b>; at midday they converge on the
 * dining hall; at dusk they drift to the tavern. Before this, a settler's
 * position was wherever their work happened to take them, which is why a
 * settlement could look busy without ever looking alive.
 *
 * <p>It deliberately does one thing: <b>getting there</b>. Once inside the room
 * it stops wanting to run, and the trade goals below it take over — the farmer
 * starts farming because he is standing in his farm, not because a farm exists
 * somewhere. That split is what keeps this goal from fighting the work goals
 * for the movement flag every tick.
 *
 * <p>Route failures are recorded rather than endured. A settler who cannot
 * reach their post says so, with a reason, instead of jittering against a wall
 * — which is the whole of TekTopia's worst review and the reason KF-013 was
 * diagnosable at all.
 */
public class GoToPostGoal extends Goal {

    /** Re-path this often while walking; a post can move when a room changes. */
    private static final int REPATH_INTERVAL = 40;
    /** Give up after this long and say why, rather than shuffling forever. */
    private static final int PATIENCE = 300;

    private final SettlerEntity settler;
    private Schedule.Posting posting;
    private int repathTimer;
    private int walkedTicks;

    public GoToPostGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!settler.isBound() || settler.getTarget() != null) {
            return false;
        }
        if (carryingSomething()) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        Schedule.Posting post = Schedule.postFor(settlement, settler,
            DayPhase.of(settler.level().getDayTime()));
        if (post == null || post.where() == null) {
            return false;
        }
        // Already there: say no, so the trade goals can have the movement flag.
        if (settler.blockPosition().closerThan(post.where(), Schedule.AT_POST)) {
            return false;
        }
        posting = post;
        return true;
    }

    /**
     * A load in your hands outranks the clock.
     *
     * <p>The schedule says where to be when you have nothing else to do; it
     * must never drag a courier who is mid-delivery off to stand in the
     * square, because the goods would be set down wherever the day happened to
     * send them. {@code aFullSackSlowsTheCarrier} caught exactly that.
     *
     * <p>It reads the bag rather than {@code getCarryLoad()} on purpose: the
     * synced load is published from {@code aiStep} <i>after</i> the goals have
     * already run, so on a settler's very first tick the field still says zero
     * while the sack is full. Asking the container is the only answer that is
     * true on every tick.
     */
    private boolean carryingSomething() {
        return !settler.bag.isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        if (posting == null || settler.getTarget() != null || carryingSomething()) {
            return false;
        }
        if (walkedTicks > PATIENCE) {
            return false;
        }
        return !settler.blockPosition().closerThan(posting.where(), Schedule.AT_POST);
    }

    @Override
    public void start() {
        walkedTicks = 0;
        repathTimer = 0;
        settler.setActivity(posting.activity());
        path();
    }

    /**
     * Hand the flag back honestly.
     *
     * <p>Whether this goal ends because the settler arrived, gave up, picked
     * something up, or got a target, the settler is left standing still with
     * nothing telling it what it is doing. Every other goal that owns
     * Flag.MOVE resets the activity in its own {@code stop()} (see
     * {@link RestAtNightGoal#stop()}); this one used not to, which meant a
     * settler who legitimately arrived — or who was cut off mid-walk by a
     * combat target or a courier's pickup — kept reading
     * {@code SettlerActivity.TRAVELING} until some other goal happened to
     * overwrite it. When the goal that was supposed to take the flag next
     * (a trade goal on a look-cooldown, a fully-fed settler with nothing
     * else to do) was slow to claim it, that stale TRAVELING label was the
     * only thing an observer — a test assertion or a player — ever saw,
     * which reads as "never arrived" even on ticks where the settler had
     * been standing at its post the whole time.
     */
    @Override
    public void stop() {
        if (posting != null && walkedTicks > PATIENCE
            && !settler.blockPosition().closerThan(posting.where(), Schedule.AT_POST)) {
            settler.recordRouteFailure("post:" + posting.reason());
        }
        posting = null;
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
    }

    @Override
    public void tick() {
        walkedTicks++;
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            path();
        }
    }

    private void path() {
        BlockPos where = posting.where();
        boolean moving = settler.getNavigation().moveTo(
            where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0.85);
        if (!moving) {
            // No path at all is worth saying immediately; waiting out the
            // patience timer would just delay the same answer.
            settler.recordRouteFailure("post_unreachable:" + posting.reason());
            walkedTicks = PATIENCE + 1;
        }
    }
}
