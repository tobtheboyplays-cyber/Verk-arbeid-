package com.hearthstead.ambient;

import com.hearthstead.entity.LifeNeed;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Living village: an IDLE settler caught in the rain steps under the nearest
 * roof it can see and waits there until the rain stops (or anything real
 * needs it). Priority 7, MOVE only: every work, meal, rest, alarm and
 * defense goal outranks it, so it only ever spends time that was idle
 * anyway. Bounded: a check every {@link #CHECK_TICKS}, at most
 * {@link #SAMPLES} cheap sky checks and one path request per shelter.
 */
public final class ShelterFromRainGoal extends Goal {
    static final int CHECK_TICKS = 100;
    static final int SAMPLES = 12;
    static final int RANGE = 8;
    static final int WALK_LIMIT_TICKS = 200;
    static final int WAIT_TICKS = 600;

    private final SettlerEntity settler;
    private long nextCheck = Long.MIN_VALUE;
    @Nullable
    private BlockPos shelter;
    @Nullable
    private Path path;
    private long startedAt;
    private boolean arrived;

    public ShelterFromRainGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level) || !level.isRaining()) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextCheck) {
            return false;
        }
        nextCheck = now + CHECK_TICKS + settler.getRandom().nextInt(40);
        if (!LivingVillage.enabled() || !eligible(settler, now)
            || !level.isRainingAt(settler.blockPosition().above())) {
            return false;
        }
        shelter = findShelter(level);
        if (shelter == null) {
            return false;
        }
        path = settler.getNavigation().createPath(shelter, 0);
        return path != null && path.canReach();
    }

    /** IDLE, bound, standing still, nothing urgent: time that was idle anyway. */
    public static boolean eligible(SettlerEntity settler, long now) {
        Settlement settlement = settler.settlement();
        return settlement != null && settler.isAlive() && settler.isBound() && !settler.isTraveler()
            && settler.getActivity() == SettlerActivity.IDLE && settler.getNavigation().isDone()
            && !settler.isPassenger() && !settler.isSleeping() && settler.getTarget() == null
            && settler.carryFraction() <= 0.0F && !Summons.active(settler)
            && !settler.getProfession().martial() && !LifeNeed.threatActive(settlement, now);
    }

    @Nullable
    private BlockPos findShelter(ServerLevel level) {
        BlockPos origin = settler.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            BlockPos candidate = origin.offset(settler.getRandom().nextInt(RANGE * 2 + 1) - RANGE,
                settler.getRandom().nextInt(5) - 2, settler.getRandom().nextInt(RANGE * 2 + 1) - RANGE);
            if (!level.isLoaded(candidate) || level.canSeeSky(candidate)
                || !level.getBlockState(candidate).isAir() || !level.getBlockState(candidate.above()).isAir()
                || !level.getBlockState(candidate.below()).isFaceSturdy(level, candidate.below(),
                    net.minecraft.core.Direction.UP)) {
                continue;
            }
            double dist = candidate.distSqr(origin);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public void start() {
        startedAt = settler.level().getGameTime();
        arrived = false;
        // A brisk step out of the rain; the model plays the hurried gait.
        settler.getNavigation().moveTo(path, 0.8D);
    }

    @Override
    public boolean canContinueToUse() {
        if (!(settler.level() instanceof ServerLevel level) || shelter == null || !level.isRaining()) {
            return false;
        }
        long now = level.getGameTime();
        Settlement settlement = settler.settlement();
        if (settlement == null || settler.getActivity() != SettlerActivity.IDLE
            || settler.getTarget() != null || Summons.active(settler)
            || LifeNeed.threatActive(settlement, now)) {
            return false;
        }
        if (!arrived) {
            if (settler.blockPosition().distSqr(shelter) <= 2.0D) {
                arrived = true;
                settler.getNavigation().stop();
                startedAt = now;
                return true;
            }
            return now - startedAt < WALK_LIMIT_TICKS && !settler.getNavigation().isDone();
        }
        return now - startedAt < WAIT_TICKS;
    }

    @Override
    public void stop() {
        if (!arrived) {
            settler.getNavigation().stop();
        }
        shelter = null;
        path = null;
    }

    /** GameTest seam. */
    public boolean sheltering() {
        return shelter != null && arrived;
    }
}
