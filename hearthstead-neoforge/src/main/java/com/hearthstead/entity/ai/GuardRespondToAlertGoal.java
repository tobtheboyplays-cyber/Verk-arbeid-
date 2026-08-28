package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Guards rush toward the position that raised the alarm. */
public class GuardRespondToAlertGoal extends Goal {
    private final SettlerEntity settler;

    public GuardRespondToAlertGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Martial, not just GUARD: an archer who ignores the alarm is a
        // decoration on a tower. They run to the trouble the same way, then
        // their own attack goal decides what to do when they arrive.
        if (!settler.getProfession().martial() || settler.getTarget() != null) {
            return false;
        }
        Settlement s = settler.settlement();
        return s != null && s.alertActive(settler.level().getGameTime())
            && s.alertPos != null
            && settler.blockPosition().distSqr(s.alertPos) > 36;
    }

    @Override
    public void start() {
        Settlement s = settler.settlement();
        if (s != null && s.alertPos != null) {
            settler.getNavigation().moveTo(s.alertPos.getX() + 0.5, s.alertPos.getY(),
                s.alertPos.getZ() + 0.5, 1.15);
        }
        settler.setActivity(SettlerActivity.PATROLLING);
    }

    @Override
    public boolean canContinueToUse() {
        Settlement s = settler.settlement();
        return settler.getTarget() == null && s != null
            && s.alertActive(settler.level().getGameTime())
            && !settler.getNavigation().isDone();
    }

    @Override
    public void stop() {
        settler.setActivity(SettlerActivity.IDLE);
    }
}
