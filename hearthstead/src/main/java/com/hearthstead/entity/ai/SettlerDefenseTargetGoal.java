package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;

/** Guards target hostiles that intrude on the settlement. */
public class SettlerDefenseTargetGoal extends NearestAttackableTargetGoal<Monster> {
    private final SettlerEntity settler;

    public SettlerDefenseTargetGoal(SettlerEntity settler) {
        super(settler, Monster.class, 10, true, false, target -> {
            Settlement s = settler.settlement();
            if (s == null) {
                return false;
            }
            double range = s.radius + 8;
            return target.blockPosition().distSqr(s.center) <= range * range;
        });
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        return settler.getProfession() == Profession.GUARD && super.canUse();
    }
}
