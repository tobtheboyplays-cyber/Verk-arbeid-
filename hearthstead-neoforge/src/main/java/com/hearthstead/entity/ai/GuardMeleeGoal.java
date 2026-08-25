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

    /**
     * A veteran's swing catches a second enemy.
     *
     * <p>Secondary targets take {@link GuardRank#CLEAVE_SHARE}, matching
     * vanilla's sweep: an area attack that hits everything for full damage
     * stops being a special move and becomes the only move.
     */
    @Override
    protected void checkAndPerformAttack(net.minecraft.world.entity.LivingEntity target) {
        boolean landing = isTimeToAttack();
        super.checkAndPerformAttack(target);
        if (!landing || !(mob instanceof com.hearthstead.entity.SettlerEntity guard)) {
            return;
        }
        if (!com.hearthstead.entity.GuardRank.of(guard)
                .atLeast(com.hearthstead.entity.GuardRank.VETERAN)) {
            return;
        }
        if (!(guard.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        float share = com.hearthstead.entity.GuardRank.CLEAVE_SHARE;
        for (net.minecraft.world.entity.LivingEntity other
                : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    guard.getBoundingBox().inflate(2.2))) {
            if (other == guard || other == target
                || other instanceof com.hearthstead.entity.SettlerEntity
                || !guard.canAttack(other)) {
                continue;
            }
            other.hurt(level.damageSources().mobAttack(guard), 3.0F * share);
            break;  // ONE extra, not a whirlwind
        }
    }

    @Override
    public void stop() {
        super.stop();
        settler.setActivity(SettlerActivity.IDLE);
    }
}
