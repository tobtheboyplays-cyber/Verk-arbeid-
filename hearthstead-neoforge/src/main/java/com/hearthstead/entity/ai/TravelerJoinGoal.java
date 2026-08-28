package com.hearthstead.entity.ai;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * A wandering traveler walks to the settlement and waits there as a guest.
 *
 * <p>SLICE RECRUIT-1 (DESIGN.md system 8). Arriving used to mean joining on
 * the spot; a traveler is a guest first now. This goal only ever gets them to
 * the right doorstep — the tavern's anchor when a valid one stands, the
 * hearth otherwise, the way {@link Schedule}'s own gathering spots already
 * fall back — and keeps them looking like they are waiting for something once
 * they are there. Whether and when they actually join is entirely
 * {@link SettlementManager#tickRecruitment}'s call, made once a second off
 * the settlement's own state (can it pay? has patience run out?), so this
 * goal never decides that itself and can never double-fire it.
 */
public class TravelerJoinGoal extends Goal {
    private final SettlerEntity settler;
    private int repathTimer;

    public TravelerJoinGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return settler.isTraveler() && settler.getHearthPos() != null;
    }

    @Override
    public void start() {
        settler.setActivity(SettlerActivity.TRAVELING);
        path();
    }

    /** The tavern's anchor if the settlement has a valid one, else the hearth. */
    private BlockPos waitingSpot() {
        BlockPos hearth = settler.getHearthPos();
        if (!(settler.level() instanceof ServerLevel level)) {
            return hearth;
        }
        Settlement s = SettlementManager.byId(level, settler.getTargetSettlementId());
        if (s == null) {
            return hearth;
        }
        BlockPos tavern = Schedule.firstValid(s, BuildingType.TAVERN);
        return tavern != null ? tavern : hearth;
    }

    private void path() {
        BlockPos spot = waitingSpot();
        settler.getNavigation().moveTo(spot.getX() + 0.5, spot.getY() + 1,
            spot.getZ() + 0.5, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        return settler.isTraveler();
    }

    @Override
    public void tick() {
        BlockPos spot = waitingSpot();
        if (spot == null) {
            return;
        }
        settler.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY() + 1,
            spot.getZ() + 0.5);
        if (settler.blockPosition().distSqr(spot) <= 9) {
            // Arrived: stand like a guest instead of pacing. Joining itself is
            // tickRecruitment's call, not this tick's.
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
            repathTimer = 40;
            path();
        }
    }

    @Override
    public void stop() {
        if (!settler.isTraveler()) {
            settler.setActivity(SettlerActivity.IDLE);
        }
    }
}
