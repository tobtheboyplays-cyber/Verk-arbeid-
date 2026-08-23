package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

public class GuardMeleeGoal extends MeleeAttackGoal {
    private final SettlerEntity settler;

    public GuardMeleeGoal(SettlerEntity settler) {
        super(settler, 1.15, true);
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        return settler.getProfession() == Profession.GUARD && super.canUse();
    }

    @Override
    public void start() {
        super.start();
        settler.setActivity(SettlerActivity.COMBAT);
    }

    @Override
    public void stop() {
        super.stop();
        settler.setActivity(SettlerActivity.IDLE);
    }
}
