package com.hearthstead.entity.combat.role;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.revive.DownedRescuer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The Healer's side of the revive lane's {@link DownedRescuer} hook
 * (plan/BATTLE-ROLES.md §3). Once a second the revive service offers each
 * downed player; the nearest free Healer of that settlement within
 * {@link HealerMedicGoal#REVIVE_RADIUS} takes the job and then pings
 * {@code ReviveService.pingRevive} every tick in reach, exactly like a player
 * holding use (same timer, same damage interruption). Registered once by
 * {@link RoleWorld}.
 */
public final class HealerRescuer implements DownedRescuer {
    @Override
    public boolean offer(ServerLevel level, ServerPlayer downed, UUID settlementId,
                         int bleedTicksLeft) {
        if (downed.level() != level || settlementId == null || !RoleCombat.enabled()) {
            return false;
        }
        double r = HealerMedicGoal.REVIVE_RADIUS;
        List<SettlerEntity> healers = level.getEntitiesOfClass(SettlerEntity.class,
            downed.getBoundingBox().inflate(r, 8.0D, r),
            s -> s.isAlive() && s.getProfession() == Profession.HEALER
                && settlementId.equals(s.getSettlementId()));
        healers.sort(Comparator.comparingDouble(s -> s.distanceToSqr(downed)));
        for (SettlerEntity healer : healers) {
            HealerMedicGoal goal = HealerMedicGoal.of(healer);
            if (goal != null && goal.offerRevive(level, downed)) {
                return true;
            }
        }
        return false;
    }
}
