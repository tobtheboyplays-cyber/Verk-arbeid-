package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernSeatMotion;
import com.hearthstead.settlement.HomeSeating;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A brief, physical evening sit in the resident's own assigned house.
 *
 * <p>Registered after TavernVisitGoal at the same priority: a serviceable
 * Tavern remains the social destination; this is the no-host/no-table
 * fallback. Hunger, sleep, panic, combat, and work retain their existing
 * higher-priority owners.
 */
public final class HomeRestGoal extends Goal {
    private final SettlerEntity actor;
    private TavernSeatEntity seat;
    private Path path;
    private int walking;
    private int nextApproachRepath;

    public HomeRestGoal(SettlerEntity actor) {
        this.actor = actor;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (HomeSeating.hasHomeSeat(actor)
            && actor.getVehicle() instanceof TavernSeatEntity current
            && !current.isClosing() && HomeSeating.mayRest(actor)) {
            seat = current;
            path = null;
            return true;
        }
        HomeSeating.Search found = HomeSeating.reserveReachable(actor);
        seat = found.seat();
        path = found.path();
        return seat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return seat != null && !seat.isRemoved() && !seat.isClosing()
            && HomeSeating.mayRest(actor)
            && (actor.getVehicle() == seat || walking < 200);
    }

    @Override
    public void start() {
        walking = 0;
        nextApproachRepath = 20;
        if (actor.getVehicle() != seat && path != null) {
            actor.setActivity(SettlerActivity.TRAVELING);
            actor.getNavigation().moveTo(path, 0.9D);
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (actor.getVehicle() == seat) {
            actor.getNavigation().stop();
            actor.setActivity(SettlerActivity.IDLE);
            return;
        }
        walking++;
        if (seat.beginEntry(actor)) {
            return;
        }
        Vec3 target = TavernSeatMotion.staging(seat.site());
        if (actor.position().distanceToSqr(target) < 2.25D
            && actor.level().noCollision(actor,
                actor.getBoundingBox().expandTowards(target.subtract(actor.position())))) {
            actor.getNavigation().stop();
            if (actor.position().distanceToSqr(target) > .0009D) {
                double speed = net.minecraft.util.Mth.clamp(
                    actor.position().distanceTo(target) * 2.0D, .16D, .5D);
                actor.getMoveControl().setWantedPosition(target.x, target.y,
                    target.z, speed);
            } else {
                float yaw = seat.site().dinerFacing().toYRot();
                actor.setYRot(net.minecraft.util.Mth.approachDegrees(actor.getYRot(), yaw, 12));
                actor.setYBodyRot(net.minecraft.util.Mth.approachDegrees(actor.yBodyRot, yaw, 12));
                actor.setYHeadRot(net.minecraft.util.Mth.approachDegrees(actor.getYHeadRot(), yaw, 12));
            }
        } else if (actor.getNavigation().isDone() && walking >= nextApproachRepath) {
            nextApproachRepath = walking + 20;
            Path retry = actor.getNavigation().createPath(
                BlockPos.containing(target), 0);
            if (retry != null && retry.canReach()) {
                path = retry;
                actor.getNavigation().moveTo(retry, .9D);
            }
        }
    }

    @Override
    public void stop() {
        actor.getNavigation().stop();
        if (seat != null) {
            seat.release();
        }
        seat = null;
        path = null;
        actor.setActivity(SettlerActivity.IDLE);
    }
}
