package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

public class GuardMeleeGoal extends MeleeAttackGoal {

    /** The rank edge's transient modifier id; on the guard only for the one
     *  tick of the one blow, never persisted. */
    private static final ResourceLocation RANK_EDGE_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_rank_edge");

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
     * One landed blow, three consequences: the rank's edge rides on it, it
     * trains Strength (the number {@link GuardRank#of} reads — a guard earns
     * armor by fighting), and a veteran's swing cleaves into a second raider.
     */
    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        // canPerformAttack is the exact predicate super gates the blow on
        // (cooldown AND reach AND line of sight). isTimeToAttack alone can be
        // true a whole chase away from the enemy — training on it would pay
        // Strength for swings at empty air.
        if (!canPerformAttack(target)) {
            super.checkAndPerformAttack(target);
            return;
        }

        // Rank damage: experience swings harder — the attribute stays flat,
        // the goal adds the edge. +0.5 per rank ordinal (RECRUIT +0.0,
        // CAPTAIN +2.0), applied as a transient ATTACK_DAMAGE modifier that
        // exists only around this one doHurtTarget, so the bonus rides the
        // ordinary damage pipeline — one hurt, one knockback, armor applied —
        // instead of a second hurt() call the target's invulnerability
        // frames would swallow.
        AttributeInstance attack = settler.getAttribute(Attributes.ATTACK_DAMAGE);
        double edge = GuardRank.MELEE_EDGE_PER_RANK * GuardRank.of(settler).ordinal();
        boolean edged = attack != null && edge > 0.0 && !attack.hasModifier(RANK_EDGE_ID);
        if (edged) {
            attack.addTransientModifier(new AttributeModifier(RANK_EDGE_ID, edge,
                AttributeModifier.Operation.ADD_VALUE));
        }
        try {
            super.checkAndPerformAttack(target);
        } finally {
            if (edged) {
                attack.removeModifier(RANK_EDGE_ID);
            }
        }

        // A blow landed is combat training — the fast lane up the rank
        // ladder (5x the patrol drill per event, GuardRank.TRAIN_COMBAT).
        // Same idiom as GuardPatrolGoal's STAMINA-per-waypoint: train at the
        // moment the work completes, never on a timer.
        settler.train(Attribute.STRENGTH, GuardRank.TRAIN_COMBAT);

        cleave(target);
    }

    /**
     * A veteran's swing catches a second enemy.
     *
     * <p>Secondary targets take {@link GuardRank#CLEAVE_SHARE}, matching
     * vanilla's sweep: an area attack that hits everything for full damage
     * stops being a special move and becomes the only move.
     */
    private void cleave(LivingEntity target) {
        if (!GuardRank.of(settler).atLeast(GuardRank.VETERAN)) {
            return;
        }
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        float share = GuardRank.CLEAVE_SHARE;
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class,
                settler.getBoundingBox().inflate(2.2))) {
            // Splash is HOSTILE-ONLY: the swing follows through into the
            // raid, never into a bystander — a passing cow, somebody's pet,
            // or a player leaning in to watch must not catch the edge of it.
            // (RaiderEntity is never a SettlerEntity, so this also keeps the
            // old never-your-own-people rule, plus the canAttack filter.)
            if (other == settler || other == target
                || !(other instanceof RaiderEntity)
                || !settler.canAttack(other)) {
                continue;
            }
            other.hurt(level.damageSources().mobAttack(settler), 3.0F * share);
            break;  // ONE extra, not a whirlwind
        }
    }

    @Override
    public void stop() {
        super.stop();
        settler.setActivity(SettlerActivity.IDLE);
    }
}
