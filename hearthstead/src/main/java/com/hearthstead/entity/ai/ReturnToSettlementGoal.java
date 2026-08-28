package com.hearthstead.entity.ai;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Strayed settlers head back inside their settlement's bounds. */
public class ReturnToSettlementGoal extends Goal {
    private final SettlerEntity settler;
    private int checkCooldown;

    public ReturnToSettlementGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (checkCooldown > 0) {
            checkCooldown--;
            return false;
        }
        checkCooldown = 40;
        Settlement s = settler.settlement();
        if (s == null) {
            return false;
        }
        double limit = s.radius + 8;
        return settler.blockPosition().distSqr(s.center) > limit * limit;
    }

    @Override
    public void start() {
        Settlement s = settler.settlement();
        if (s != null) {
            settler.getNavigation().moveTo(s.center.getX() + 0.5, s.center.getY() + 1,
                s.center.getZ() + 0.5, 1.0);
        }
    }

    @Override
    public boolean canContinueToUse() {
        Settlement s = settler.settlement();
        if (s == null || settler.getNavigation().isDone()) {
            return false;
        }
        return settler.blockPosition().distSqr(s.center)
            > (double) s.radius * s.radius * 0.25;
    }
}
