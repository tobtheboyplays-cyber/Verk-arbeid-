package com.hearthstead.entity.ai;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.combat.RaiderMove;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

import java.util.UUID;

/**
 * The line raider's melee route (Brutes and Captains): a server-ticketed
 * anticipation/contact/recovery timeline per {@link RaiderMove}. Ordinary
 * Skirmishers never use it; they run {@link RaiderSkirmishGoal}.
 *
 * <p>BRUTEs mostly throw the telegraphed HEAVY, otherwise their club; both
 * are crushing ground slams. Once a crushing blow is committed it always
 * comes down on its contact tick (the main target may have stepped out of
 * reach, but the ground is still there), unless a guard staggers the Brute
 * first. Damage still lands only through the entity's one-use ticket and
 * only on an authorized, in-reach main target.
 */
public final class RaiderMeleeGoal extends MeleeAttackGoal {

    /** t=0.90s on the 20Hz server clock (RaiderMove.CLUB). */
    public static final int BRUTE_CLUB_CONTACT_TICK = 18;

    private final RaiderEntity raider;
    private long pendingTicket;
    private long pendingContactTick = Long.MIN_VALUE;
    private UUID pendingTargetId;
    private RaiderMove pendingMove;
    /** Own start-to-start cadence; vanilla's cooldown is never spent. */
    private long nextSwingTick = Long.MIN_VALUE;
    private RaiderMove forcedNextMove;

    public RaiderMeleeGoal(RaiderEntity raider, double speed) {
        super(raider, speed, false);
        this.raider = raider;
    }

    @Override
    public boolean canUse() {
        return !raider.isSkirmisherPest() && super.canUse();
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        if (!(raider.level() instanceof ServerLevel level)) {
            cancelPendingContact();
            return;
        }
        if (raider.isStaggered() || pendingTicket != 0L) {
            // Pending blows are resolved in tick(), independent of chasing.
            return;
        }
        long now = level.getGameTime();
        if (now < nextSwingTick) {
            return;
        }
        // canPerformAttack is NeoForge 1.21.1's normal reach and line-of-sight
        // starter gate (its cooldown half is never spent here). The entity
        // repeats those facts before issuing its ticket and again at contact.
        if (!canPerformAttack(target)
            || !raider.isAuthorizedMeleeMoveTarget(target)) {
            return;
        }
        // Bandits (early raids) are plain outlaws: light swings only, no
        // specials, whatever their rank.
        RaiderMove move = forcedNextMove != null ? forcedNextMove
            : raider.variant() == RaiderEntity.Variant.BANDIT ? RaiderMove.LIGHT
            : RaiderMove.choose(raider.variant() == RaiderEntity.Variant.BRUTE,
                raider.isCaptain(), raider.getRandom().nextDouble());
        long ticket = raider.beginMeleeMove(target, move);
        if (ticket == 0L) {
            return;
        }
        forcedNextMove = null;
        pendingTicket = ticket;
        pendingTargetId = target.getUUID();
        pendingContactTick = now + move.hitTick();
        pendingMove = move;
        nextSwingTick = now + move.cadenceTicks();
    }

    /** A committed ground slam comes down whatever the main target does. */
    private boolean committedSlam() {
        return pendingTicket != 0L && pendingMove != null
            && pendingMove.slamsGround(raider.variant() == RaiderEntity.Variant.BRUTE);
    }

    @Override
    public boolean canContinueToUse() {
        if (raider.isSkirmisherPest()) {
            return false;
        }
        // Vanilla's false-following mode stops once navigation reaches a
        // stationary target. A pending ticket must survive that short idle
        // window so its already-authorized contact can resolve. A committed
        // slam holds the goal until it lands; any other pending blow needs
        // its target still valid, and stop() cancels it otherwise.
        if (committedSlam()) {
            return raider.isAlive() && !raider.isStaggered();
        }
        if (pendingTicket != 0L) {
            LivingEntity target = raider.getTarget();
            return target != null && pendingTargetId != null
                && pendingTargetId.equals(target.getUUID())
                && raider.isAuthorizedMeleeMoveTarget(target);
        }
        return super.canContinueToUse();
    }

    @Override
    public void tick() {
        if (!(raider.level() instanceof ServerLevel level)) {
            cancelPendingContact();
            return;
        }
        if (raider.isStaggered()) {
            cancelPendingContact();
            raider.getNavigation().stop();
            return;
        }
        if (committedSlam()) {
            // Planted for the blow: no chasing during a crushing wind-up.
            raider.getNavigation().stop();
            LivingEntity target = raider.getTarget();
            if (target != null) {
                raider.getLookControl().setLookAt(target, 30.0F, 30.0F);
            }
        } else {
            super.tick();
        }
        if (pendingTicket == 0L) {
            return;
        }
        long now = level.getGameTime();
        LivingEntity target = raider.getTarget();
        boolean sameTarget = target != null && pendingTargetId != null
            && pendingTargetId.equals(target.getUUID());
        if (now > pendingContactTick) {
            cancelPendingContact();
            return;
        }
        if (!committedSlam() && (!sameTarget
                || !raider.isAuthorizedMeleeMoveTarget(target))) {
            // Some MeleeAttackGoal paths never call checkAndPerformAttack
            // while an opponent is gone; cancel here so death, target switch,
            // range and LOS never wait for a later retry.
            cancelPendingContact();
            return;
        }
        if (now < pendingContactTick) {
            if (now == pendingContactTick - 1L && pendingMove.crushing()) {
                level.playSound(null, raider.blockPosition(),
                    com.hearthstead.registry.ModSounds.COMBAT_SWING_HEAVY.get(),
                    SoundSource.HOSTILE, 0.9F,
                    0.55F + raider.getRandom().nextFloat() * 0.1F);
            }
            return;
        }
        long ticket = pendingTicket;
        // Clear goal state before the entity enters hurt callbacks. The
        // entity ledger consumes before damage too, making this robust to a
        // same-tick retry or a re-entrant hook.
        clearPendingFields();
        // The entity validates the main target itself: an invalid or absent
        // target takes nothing, but a Brute's slam still lands.
        raider.commitMeleeMove(ticket, sameTarget ? target : null);
    }

    @Override
    public void stop() {
        cancelPendingContact();
        super.stop();
    }

    /** Deterministic QA seam: the next swing is exactly this move. */
    public void forceNextMove(RaiderMove move) {
        this.forcedNextMove = move;
    }

    /** The move whose wind-up is live, or null. */
    public RaiderMove pendingMove() {
        return pendingTicket != 0L ? pendingMove : null;
    }

    private void cancelPendingContact() {
        if (pendingTicket != 0L) {
            raider.cancelMeleeMove(pendingTicket);
        }
        clearPendingFields();
    }

    private void clearPendingFields() {
        pendingTicket = 0L;
        pendingContactTick = Long.MIN_VALUE;
        pendingTargetId = null;
        pendingMove = null;
    }
}
