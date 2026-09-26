package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.state.GuardOrder;
import net.minecraft.server.level.ServerLevel;
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
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) return false;
        // Unordered martial settlers answer alarms; authored defensive posts
        // retain their movement authority while combat selects local threats.
        Profession profession = settler.getProfession();
        if (!answersAlarms(profession)
            || settler.getTarget() != null
            || !(settler.level() instanceof ServerLevel level)
            || !EquipmentRequests.readyForProfession(level, settler,
                profession)) {
            return false;
        }
        Settlement s = settler.settlement();
        return s != null && s.alertActive(settler.level().getGameTime())
            && orderAllowsAlarm(level, s)
            && s.alertPos != null
            && settler.blockPosition().distSqr(s.alertPos) > 36;
    }

    @Override
    public void start() {
        Settlement s = settler.settlement();
        if (s != null && s.alertPos != null) {
            settler.getNavigation().moveTo(s.alertPos.getX() + 0.5, s.alertPos.getY(),
                s.alertPos.getZ() + 0.5, 1.2);
        }
        settler.setActivity(SettlerActivity.COMBAT);
    }

    @Override
    public boolean canContinueToUse() {
        if (com.hearthstead.settlement.guard.BannerTeams.active(settler) != null) return false;
        Settlement s = settler.settlement();
        return answersAlarms(settler.getProfession()) && settler.getTarget() == null && s != null
            && settler.level() instanceof ServerLevel level
            && orderAllowsAlarm(level, s)
            && s.alertActive(settler.level().getGameTime())
            && !settler.getNavigation().isDone();
    }

    @Override
    public void stop() {
        settler.getNavigation().stop();
        settler.setActivity(SettlerActivity.IDLE);
    }

    /**
     * The one current-role rule for answering an alarm (J-02/J-04), checked
     * on start AND every tick, so a settler re-trained mid-run stops at once:
     * Guard and Archer always; Spearman, Longswordsman, Rune Mage and Healer
     * while battle roles are on (they take field orders and alarms, never
     * authored Guard Orders); civilians never. The Healer goes to the
     * wounded, not the fight: HealerMedicGoal outranks this goal.
     */
    public static boolean answersAlarms(Profession profession) {
        return switch (profession) {
            case GUARD, ARCHER -> true;
            case SPEARMAN, LONGSWORDSMAN, RUNE_MAGE, HEALER ->
                com.hearthstead.entity.combat.role.RoleCombat.enabled();
            default -> false;
        };
    }

    private boolean orderAllowsAlarm(ServerLevel level, Settlement settlement) {
        Profession profession = settler.getProfession();
        if (profession != Profession.GUARD && profession != Profession.ARCHER) {
            // Battle roles hold no authored Guard Order (Barracks/Watchtower
            // trades only), so no post can keep them from the alarm.
            return answersAlarms(profession);
        }
        GuardAssignmentService.Validation validation =
            GuardAssignmentService.validate(level, settlement, settler, true);
        if (validation.reason() != GuardAssignmentService.InvalidReason.NONE) {
            return false;
        }
        // An absent order is valid with allowEmptyOrder, but valid() still
        // requires an order. Do not mistake that absence for corruption.
        GuardOrder.Mode mode = validation.order().map(order ->
            order.modeAt(level.getGameTime())).orElse(GuardOrder.Mode.NONE);
        return mode != GuardOrder.Mode.STAND_POST
            && mode != GuardOrder.Mode.TOWER_POST;
    }
}
