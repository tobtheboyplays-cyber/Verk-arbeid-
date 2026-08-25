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
        boolean already = settler.blockPosition().closerThan(post.where(), Schedule.AT_POST);
        if ("Astrid".equals(settler.getSettlerName())) {
            double dist = Math.sqrt(settler.blockPosition().distSqr(post.where()));
            System.out.println("GTP_DEBUG canUse name=" + settler.getSettlerName()
                + " pos=" + settler.blockPosition() + " post=" + post.where()
                + " reason=" + post.reason() + " dist=" + dist + " already=" + already);
        }
        if (already) {
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
        if ("Astrid".equals(settler.getSettlerName())) {
            System.out.println("GTP_DEBUG start name=" + settler.getSettlerName()
                + " pos=" + settler.blockPosition() + " post=" + posting.where()
                + " reason=" + posting.reason());
        }
        path();
    }

    @Override
    public void stop() {
        if (posting != null && walkedTicks > PATIENCE
            && !settler.blockPosition().closerThan(posting.where(), Schedule.AT_POST)) {
            settler.recordRouteFailure("post:" + posting.reason());
        }
        posting = null;
        settler.getNavigation().stop();
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
        if ("Astrid".equals(settler.getSettlerName())) {
            System.out.println("GTP_DEBUG path name=" + settler.getSettlerName()
                + " pos=" + settler.blockPosition() + " where=" + where
                + " moving=" + moving + " walkedTicks=" + walkedTicks
                + " navDone=" + settler.getNavigation().isDone()
                + " path=" + settler.getNavigation().getPath());
        }
        if (!moving) {
            // No path at all is worth saying immediately; waiting out the
            // patience timer would just delay the same answer.
            settler.recordRouteFailure("post_unreachable:" + posting.reason());
            walkedTicks = PATIENCE + 1;
        }
    }
}
