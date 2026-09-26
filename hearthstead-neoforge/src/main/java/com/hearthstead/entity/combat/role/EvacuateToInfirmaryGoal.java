package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * The patient's half of the Healer's evacuation (plan/BATTLE-ROLES.md §3):
 * a badly wounded fighter tagged by a Healer breaks off and walks to the
 * Infirmary, the Healer beside them. The tag carries its own expiry, so a
 * lost Healer or a reload can never strand a soldier out of the fight.
 */
public class EvacuateToInfirmaryGoal extends Goal {
    private static final double ARRIVED = 3.0D;
    private final SettlerEntity settler;
    private BlockPos to;
    private long nextRepath = Long.MIN_VALUE;

    public EvacuateToInfirmaryGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean tagged() {
        return RoleCombat.enabled() && settler.level() instanceof ServerLevel level
            && settler.getPersistentData().getLong(HealerMedicGoal.EVACUATE_UNTIL) > level.getGameTime()
            && settler.getPersistentData().contains(HealerMedicGoal.EVACUATE_TO);
    }

    @Override
    public boolean canUse() {
        if (!tagged() || settler.getHealth() >= settler.getMaxHealth() * 0.65F) {
            return false;
        }
        to = BlockPos.of(settler.getPersistentData().getLong(HealerMedicGoal.EVACUATE_TO));
        return !settler.blockPosition().closerThan(to, ARRIVED);
    }

    @Override
    public boolean canContinueToUse() {
        return tagged() && to != null && !settler.blockPosition().closerThan(to, ARRIVED);
    }

    @Override
    public void start() {
        settler.setTarget(null);
        settler.setActivity(SettlerActivity.IDLE);
    }

    @Override
    public void tick() {
        if (settler.level() instanceof ServerLevel level && level.getGameTime() >= nextRepath) {
            settler.getNavigation().moveTo(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D, 1.1D);
            nextRepath = level.getGameTime() + 20;
        }
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        if (to != null && settler.blockPosition().closerThan(to, ARRIVED)) {
            // Arrived: the escort is over, the Healer bandages on the spot.
            settler.getPersistentData().putLong(HealerMedicGoal.EVACUATE_UNTIL, 0L);
        }
    }
}
