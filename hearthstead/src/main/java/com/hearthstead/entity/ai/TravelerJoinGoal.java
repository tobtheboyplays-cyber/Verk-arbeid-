package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** A wandering traveler walks to the hearth and asks to join. */
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

    private void path() {
        BlockPos hearth = settler.getHearthPos();
        settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
            hearth.getZ() + 0.5, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        return settler.isTraveler();
    }

    @Override
    public void tick() {
        BlockPos hearth = settler.getHearthPos();
        if (hearth == null) {
            return;
        }
        settler.getLookControl().setLookAt(hearth.getX() + 0.5, hearth.getY() + 1,
            hearth.getZ() + 0.5);
        if (settler.blockPosition().distSqr(hearth) <= 9) {
            if (settler.level() instanceof ServerLevel serverLevel) {
                SettlementManager.convertTraveler(serverLevel, settler);
            }
            return;
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
