package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A traveler walks to the exact locked Tavern's physical doorstep, then waits
 * as a guest and later retraces a saved outside origin. Recruitment owns both
 * transitions and immutable identity; this goal owns ordinary Vanilla movement.
 */
public class TravelerJoinGoal extends Goal {
    private static final int REPATH_TICKS = 40;
    private static final int REJECTED_PATH_RETRY_TICKS = 10;
    private static final int MAX_LOCAL_LEG = 24;
    private static final int LOCAL_VERTICAL_RANGE = 7;
    private static final double DIRECT_RANGE_MARGIN = 2.0D;

    private final SettlerEntity settler;
    private int repathTimer;

    public TravelerJoinGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return settler.isTraveler() && waitingSpot() != null;
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.TRAVELING);
        schedulePath();
    }

    private BlockPos waitingSpot() {
        return settler.level() instanceof ServerLevel level
            ? SettlementManager.travelerRouteDestination(level, settler) : null;
    }

    private void schedulePath() {
        // A rejected spawn-time path commonly means the new entity has not
        // grounded yet. Retry that failed request sooner, while successful
        // physical routes retain the established forty-game-tick cadence.
        repathTimer = adjustedTickDelay(path()
            ? REPATH_TICKS : REJECTED_PATH_RETRY_TICKS);
    }

    private boolean path() {
        BlockPos spot = waitingSpot();
        if (spot == null) {
            settler.getNavigation().stop();
            return false;
        }
        // The resolver returns a feet cell, not the plaque/bed block above
        // it. Vanilla navigation and SettlerDoorGoal own every physical step.
        //
        // Edge travelers may lawfully begin farther from the Tavern than this
        // mob's FOLLOW_RANGE. Asking PathNavigation for that remote endpoint
        // may return a partial path even when a continuous road exists. Walk
        // the useful physical prefix of a bounded leg when its real end node
        // strictly closes distance, then
        // recompute toward the SAME immutable Tavern approach.
        Vec3 exact = Vec3.atBottomCenterOf(spot);
        double directRange = Math.max(4.0D,
            settler.getAttributeValue(Attributes.FOLLOW_RANGE) - DIRECT_RANGE_MARGIN);
        double before = settler.position().distanceToSqr(exact);
        if (before > directRange * directRange) {
            int localRange = Math.max(4,
                Math.min(MAX_LOCAL_LEG, (int) Math.floor(directRange)));
            // Prefer the deterministic point on the destination vector. It
            // gives a straight road a stable first leg while PathNavigation
            // still owns the actual physical route and end node.
            Vec3 offset = exact.subtract(settler.position());
            Vec3 projected = settler.position().add(offset.normalize()
                .scale(Math.min(localRange, offset.length())));
            if (installClosingPath(projected, exact, before)) {
                return true;
            }
            // Rough terrain may make the projected block invalid. Vanilla's
            // bounded land sampler may choose another closing leg; null and
            // non-closing results fail for a short retry.
            Vec3 leg = LandRandomPos.getPosTowards(settler, localRange,
                LOCAL_VERTICAL_RANGE, exact);
            if (leg != null && installClosingPath(leg, exact, before)) {
                return true;
            }
            settler.getNavigation().stop();
            return false;
        }
        if (installClosingPath(exact, exact, before)) {
            return true;
        }
        settler.getNavigation().stop();
        return false;
    }

    /**
     * Installs only a real path whose actual end node closes on the Tavern.
     *
     * <p>{@link Path#canReach()} means "reached this temporary requested
     * waypoint within the pathfinder accuracy", not "this physical prefix is
     * unusable". Requiring it discarded every useful prefix at doors and at
     * the visited-node boundary, leaving the navigator stopped forever. The
     * end node is the movement authority: a partial prefix is accepted only
     * while it makes strict geometric progress. Once the mob reaches that end,
     * the next bounded request is evaluated from its new physical position.</p>
     */
    private boolean installClosingPath(Vec3 requested, Vec3 exact,
                                       double distanceBefore) {
        Path path = settler.getNavigation().createPath(
            BlockPos.containing(requested), 0);
        if (path == null || path.isDone() || path.getEndNode() == null
            || Vec3.atBottomCenterOf(path.getEndNode().asBlockPos())
                .distanceToSqr(exact) >= distanceBefore) {
            return false;
        }
        return settler.getNavigation().moveTo(path, 1.0D);
    }
    @Override
    public boolean canContinueToUse() {
        return settler.isTraveler() && waitingSpot() != null;
    }

    @Override
    public void tick() {
        BlockPos spot = waitingSpot();
        if (spot == null) return;
        settler.getLookControl().setLookAt(spot.getX() + 0.5D,
            spot.getY() + 1.0D, spot.getZ() + 0.5D);
        if (settler.blockPosition().distSqr(spot) <= 9.0D) {
            if (settler.getActivity() != SettlerActivity.IDLE) {
                settler.getNavigation().stop();
                settler.setActivity(SettlerActivity.IDLE);
            }
            return;
        }
        if (settler.getActivity() != SettlerActivity.TRAVELING) {
            settler.setActivity(SettlerActivity.TRAVELING);
        }
        if (--repathTimer <= 0) {
            schedulePath();
        }
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.clearDoorPassageRequest();
        if (!settler.isTraveler() || com.hearthstead.settlement.TavernSeating.mayVisit(settler))
            settler.setActivity(SettlerActivity.IDLE);
    }
}
