package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Civilians sprint to the hearth while danger is near. */
public class SettlerPanicGoal extends Goal {
    private final SettlerEntity settler;

    public SettlerPanicGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean danger() {
        if (settler.hurtTime > 0) {
            return true;
        }
        Settlement s = settler.settlement();
        return s != null && s.alertActive(settler.level().getGameTime());
    }

    @Override
    public boolean canUse() {
        if (settler.getProfession() == Profession.GUARD || !settler.isBound()) {
            return false;
        }
        BlockPos hearth = settler.getHearthPos();
        return hearth != null && danger() && settler.blockPosition().distSqr(hearth) > 16;
    }

    @Override
    public void start() {
        if (settler.isSleeping()) {
            settler.stopSleeping();
        }
        BlockPos hearth = settler.getHearthPos();
        settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
            hearth.getZ() + 0.5, 1.25);
        settler.setActivity(SettlerActivity.FLEEING);
    }

    @Override
    public boolean canContinueToUse() {
        return danger() && !settler.getNavigation().isDone();
    }

    @Override
    public void tick() {
        if (settler.getNavigation().isDone()) {
            BlockPos hearth = settler.getHearthPos();
            if (hearth != null && settler.blockPosition().distSqr(hearth) > 16) {
                settler.getNavigation().moveTo(hearth.getX() + 0.5, hearth.getY() + 1,
                    hearth.getZ() + 0.5, 1.25);
            }
        }
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
    }
}
