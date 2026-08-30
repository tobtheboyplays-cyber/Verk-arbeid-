package com.hearthstead.entity.ai;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Enemy;

import java.util.UUID;

public class GuardMeleeGoal extends MeleeAttackGoal {

    /** MELEE's authored sword contact: t=0.20 s on Minecraft's 20 Hz clock. */
    public static final int MELEE_CONTACT_TICK = 4;

    /** The rank edge's transient modifier id; on the guard only for the one
     *  tick of the one blow, never persisted. */
    private static final ResourceLocation RANK_EDGE_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead", "guard_rank_edge");
    /** Modest role counter: no wrong-role penalty and no captain bonus. */
    private static final ResourceLocation BRUTE_COUNTER_ID =
        ResourceLocation.fromNamespaceAndPath("hearthstead",
            "guard_brute_counter");
    public static final double COUNTER_DAMAGE_MULTIPLIER = 1.25D;

    private final SettlerEntity settler;
    private long pendingContactTicket;
    private long pendingContactTick = Long.MIN_VALUE;
    private UUID pendingTargetId;

    public GuardMeleeGoal(SettlerEntity settler) {
        super(settler, 1.15, true);
        this.settler = settler;
    }

    @Override
    public boolean canUse() {
        return settler.getProfession() == Profession.GUARD
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler,
                Profession.GUARD)
            && isAuthorizedHostile(settler.getTarget())
            && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return settler.getProfession() == Profession.GUARD
            && settler.level() instanceof ServerLevel level
            && EquipmentRequests.readyForProfession(level, settler,
                Profession.GUARD)
            && isAuthorizedHostile(settler.getTarget())
            && super.canContinueToUse();
    }

    @Override
    public void start() {
        super.start();
        settler.setActivity(SettlerActivity.COMBAT);
    }

    /**
     * Two-phase server attack. Vanilla's range/LOS/cooldown predicate starts
     * the visible wind-up and spends the ordinary 20-tick cooldown. Four
     * ticks later the same target, sword, range, line of sight and settlement
     * hostility are revalidated before its one-use contact ticket may deal
     * damage. Losing any condition makes the swing a visible miss.
     */
    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        if (!(settler.level() instanceof ServerLevel level)) {
            cancelPendingContact();
            return;
        }

        long now = level.getGameTime();
        if (pendingContactTicket != 0L) {
            // A target switch never transfers a cocked blade to the newcomer.
            if (pendingTargetId == null
                || !pendingTargetId.equals(target.getUUID())) {
                cancelPendingContact();
                return;
            }
            if (now < pendingContactTick) {
                return;
            }

            long ticket = pendingContactTicket;
            long dueTick = pendingContactTick;
            // Clear this goal's copy before entering vanilla damage hooks.
            // The entity ledger also consumes before hurt(), giving both
            // layers the same retry/re-entrancy guarantee.
            clearPendingFields();
            if (now != dueTick
                || !isAuthorizedContact(target)) {
                settler.cancelMeleeContact(ticket);
                return;
            }

            boolean hit = performRankedContact(ticket, target);
            if (!hit) {
                return;
            }

            // Training and cleave are consequences of an actual accepted
            // damage pass, never of reaching a timer or playing a swing.
            settler.train(Attribute.STRENGTH, GuardRank.TRAIN_COMBAT);
            cleave(target);
            return;
        }

        // canPerformAttack is vanilla's exact cooldown + reach + LOS gate.
        // The extra predicate makes the target a settlement-authorized enemy
        // and reasserts the physical sword before any animation is broadcast.
        if (!canPerformAttack(target) || !isAuthorizedContact(target)) {
            return;
        }

        resetAttackCooldown();
        long ticket = settler.beginMeleeWindup(target);
        if (ticket == 0L) {
            return;
        }
        pendingContactTicket = ticket;
        pendingTargetId = target.getUUID();
        pendingContactTick = now + MELEE_CONTACT_TICK;
        // EV_MELEE is the sole presentation owner for this one-shot. A
        // vanilla swing packet would create a second attack timeline with
        // independent interpolation; even though SettlerModel currently
        // ignores EntityModel.attackTime, emitting it would make that safety
        // accidental and invite a later double-layer/pop regression.
    }

    /** One vanilla damage pass with the rank edge present for that pass only. */
    private boolean performRankedContact(long ticket, LivingEntity target) {
        AttributeInstance attack = settler.getAttribute(Attributes.ATTACK_DAMAGE);
        double edge = GuardRank.MELEE_EDGE_PER_RANK * GuardRank.of(settler).ordinal();
        boolean edged = attack != null && edge > 0.0 && !attack.hasModifier(RANK_EDGE_ID);
        double counter = counterDamageMultiplier(target);
        boolean countered = attack != null && counter > 1.0D
            && !attack.hasModifier(BRUTE_COUNTER_ID);
        if (edged) {
            attack.addTransientModifier(new AttributeModifier(RANK_EDGE_ID, edge,
                AttributeModifier.Operation.ADD_VALUE));
        }
        if (countered) {
            attack.addTransientModifier(new AttributeModifier(BRUTE_COUNTER_ID,
                counter - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        try {
            return settler.commitMeleeContact(ticket, target);
        } finally {
            if (countered) {
                attack.removeModifier(BRUTE_COUNTER_ID);
            }
            if (edged) {
                attack.removeModifier(RANK_EDGE_ID);
            }
        }
    }

    /** Exact counter seam used both by contact damage and deterministic QA. */
    public static double counterDamageMultiplier(LivingEntity target) {
        return target instanceof RaiderEntity raider
            && !raider.isCaptain()
            && raider.variant() == RaiderEntity.Variant.BRUTE
                ? COUNTER_DAMAGE_MULTIPLIER : 1.0D;
    }

    /** Pure overload: no world/entity fixture needed to pin the design rule. */
    public static double counterDamageMultiplier(
            RaiderEntity.Variant variant, boolean captain) {
        return !captain && variant == RaiderEntity.Variant.BRUTE
            ? COUNTER_DAMAGE_MULTIPLIER : 1.0D;
    }

    /** Contact-time validation deliberately excludes the cooldown already
     * spent at wind-up, while repeating every physical/authority condition. */
    private boolean isAuthorizedContact(LivingEntity target) {
        return settler.isAuthorizedMeleeContactTarget(target);
    }

    /**
     * The target must still be a live server-side enemy of this settlement.
     * Ordinary monsters qualify only inside its defended ring; a raid-bound
     * raider must additionally name this exact settlement, so two nearby
     * settlements cannot damage each other's raid actors.
     */
    private boolean isAuthorizedHostile(LivingEntity target) {
        if (!(settler.level() instanceof ServerLevel level)
            || target == null || target.level() != level
            || !target.isAlive() || target.isRemoved()
            || !(target instanceof Enemy)
            || !settler.canAttack(target)) {
            return false;
        }
        Settlement settlement = settler.settlement();
        if (settlement == null) {
            return false;
        }
        double defendedRadius = settlement.radius + 8.0;
        if (target.blockPosition().distSqr(settlement.center)
            > defendedRadius * defendedRadius) {
            return false;
        }
        if (target instanceof RaiderEntity raider
            && raider.settlementId() != null
            && !settlement.id.equals(raider.settlementId())) {
            return false;
        }
        return true;
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
                || !isAuthorizedHostile(other)) {
                continue;
            }
            other.hurt(level.damageSources().mobAttack(settler), 3.0F * share);
            break;  // ONE extra, not a whirlwind
        }
    }

    @Override
    public void stop() {
        cancelPendingContact();
        super.stop();
        settler.setActivity(SettlerActivity.IDLE);
    }

    private void cancelPendingContact() {
        if (pendingContactTicket != 0L) {
            settler.cancelMeleeContact(pendingContactTicket);
        }
        clearPendingFields();
    }

    private void clearPendingFields() {
        pendingContactTicket = 0L;
        pendingContactTick = Long.MIN_VALUE;
        pendingTargetId = null;
    }

}
