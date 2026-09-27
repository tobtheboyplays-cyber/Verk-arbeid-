package com.hearthstead.entity.ai;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.VillageSocial;
import com.hearthstead.event.VillageMomentSavedData;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

/**
 * A sparse, interruptible pause between two already-idle civilians.
 *
 * <p>Priority 7 is intentional: every work, delivery, meal, rest, alert and
 * defense goal is above it. The scene holds MOVE/LOOK only while it is real,
 * and cancellation clears both actors immediately. It never creates a route,
 * seat claim, item movement, Coin transaction or player prompt.</p>
 */
public final class WorkCompanionGoal extends Goal {
    private static final double MIN_RANGE_SQR = 2.25D;
    private static final double MAX_RANGE = 6.0D;
    private static final double MAX_RANGE_SQR = MAX_RANGE * MAX_RANGE;
    private static final int JOIN_GRACE_TICKS = 12;
    private static final Map<ServerLevel, Map<UUID, Session>> SESSIONS = new WeakHashMap<>();

    private final SettlerEntity actor;
    @Nullable
    private Session session;

    public WorkCompanionGoal(SettlerEntity actor) {
        this.actor = actor;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(actor.level() instanceof ServerLevel level)) {
            return false;
        }
        Session existing = sessionFor(level, actor.getSettlementId());
        if (existing != null) {
            // Other idle residents must never consume a running pair's
            // session. Their own selector simply keeps looking for work.
            if (!existing.includes(actor)) {
                return false;
            }
            if (!safeDuringScene(actor, existing, level.getGameTime())) {
                existing.cancel(level);
                return false;
            }
            session = existing;
            return true;
        }
        if (!eligible(actor, level.getGameTime())) {
            return false;
        }
        SettlerEntity partner = findPartner(level);
        Settlement settlement = actor.settlement();
        if (partner == null || settlement == null
            || !VillageMomentSavedData.get(level).claim(settlement.id, level.getGameTime())) {
            return false;
        }
        session = new Session(settlement.id, actor.getUUID(), partner.getUUID(),
            level.getGameTime() + JOIN_GRACE_TICKS);
        sessions(level).put(settlement.id, session);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(actor.level() instanceof ServerLevel level) || session == null) {
            return false;
        }
        if (!safeDuringScene(actor, session, level.getGameTime()) || !session.valid(level)) {
            session.cancel(level);
            return false;
        }
        return session.awaitingPartner(level.getGameTime()) || session.running(level.getGameTime());
    }

    @Override
    public void start() {
        if (!(actor.level() instanceof ServerLevel level) || session == null) {
            return;
        }
        actor.getNavigation().stop();
        session.join(actor, level);
    }

    @Override
    public void tick() {
        if (!(actor.level() instanceof ServerLevel level) || session == null) {
            return;
        }
        actor.getNavigation().stop();
        SettlerEntity partner = session.partner(level, actor);
        if (partner != null) {
            actor.getLookControl().setLookAt(partner, 25.0F, 25.0F);
        }
        session.present(actor, level);
    }

    @Override
    public void stop() {
        if (actor.level() instanceof ServerLevel level && session != null) {
            session.cancel(level);
        }
        session = null;
    }

    private SettlerEntity findPartner(ServerLevel level) {
        return level.getEntitiesOfClass(SettlerEntity.class,
                new AABB(actor.blockPosition()).inflate(MAX_RANGE), candidate -> candidate != actor
                    && eligible(candidate, level.getGameTime())
                    && sameSettlement(candidate, actor)
                    && between(candidate, actor))
            .stream()
            .min(Comparator.comparing((SettlerEntity candidate) -> candidate.getUUID().toString()))
            .orElse(null);
    }

    private static boolean sameSettlement(SettlerEntity first, SettlerEntity second) {
        return first.getSettlementId() != null && first.getSettlementId().equals(second.getSettlementId());
    }

    private static boolean between(SettlerEntity first, SettlerEntity second) {
        double distance = first.distanceToSqr(second);
        return distance >= MIN_RANGE_SQR && distance <= MAX_RANGE_SQR;
    }

    private static boolean eligible(SettlerEntity settler, long now) {
        if (!(settler.level() instanceof ServerLevel level) || !settler.isAlive() || settler.isTraveler()
            || settler.getSettlementId() == null || settler.getActivity() != SettlerActivity.IDLE
            || settler.isPassenger() || settler.isSleeping() || settler.hasMeal()
            || settler.getTarget() != null || settler.hurtTime > 0 || settler.isOnFire()
            || settler.getHunger() < 72.0F || settler.getEnergy() < 72.0F
            || settler.carryFraction() > 0.0F || !settler.getMainHandItem().isEmpty()
            || !settler.getOffhandItem().isEmpty() || !settler.getNavigation().isDone()
            || Summons.active(settler) || defensiveProfession(settler.getProfession())) {
            return false;
        }
        Settlement settlement = settler.settlement();
        return level.isDay() && settlement != null && level.hasChunkAt(settlement.center)
            && settlement.pendingRaid == null && !settlement.raidLifecycle.isAuthoredFirstRaidActive()
            && !settlement.recurringRaidRun.isActive() && settlement.alertUntilGameTime <= now
            && level.players().stream().anyMatch(player -> player.isAlive() && !player.isSpectator()
                && player.distanceToSqr(settlement.center.getX(), settlement.center.getY(),
                    settlement.center.getZ()) <= 48.0D * 48.0D);
    }

    private static boolean safeDuringScene(SettlerEntity settler, Session active, long now) {
        if (!active.includes(settler)) {
            return false;
        }
        if (active.awaitingPartner(now)) {
            return eligible(settler, now);
        }
        Settlement settlement = settler.settlement();
        return settler.isAlive() && !settler.isTraveler() && !settler.isPassenger()
            && !settler.isSleeping() && !settler.hasMeal() && settler.getTarget() == null
            && settler.hurtTime == 0 && settler.carryFraction() <= 0.0F
            && settler.getMainHandItem().isEmpty() && settler.getOffhandItem().isEmpty()
            && !settler.isOnFire() && settler.getHunger() >= 55.0F && settler.getEnergy() >= 45.0F
            && !Summons.active(settler) && !defensiveProfession(settler.getProfession())
            && active.settlementId.equals(settler.getSettlementId()) && settlement != null
            && settlement.pendingRaid == null && !settlement.raidLifecycle.isAuthoredFirstRaidActive()
            && !settlement.recurringRaidRun.isActive() && settlement.alertUntilGameTime <= now;
    }

    private static boolean defensiveProfession(Profession profession) {
        return profession == Profession.GUARD || profession == Profession.ARCHER;
    }

    @Nullable
    private static Session sessionFor(ServerLevel level, @Nullable UUID settlementId) {
        if (settlementId == null) {
            return null;
        }
        Session session = sessions(level).get(settlementId);
        if (session != null && !session.valid(level)) {
            session.cancel(level);
            return null;
        }
        return session;
    }

    private static Map<UUID, Session> sessions(ServerLevel level) {
        return SESSIONS.computeIfAbsent(level, ignored -> new HashMap<>());
    }

    private static final class Session {
        private final UUID settlementId;
        private final UUID initiator;
        private final UUID companion;
        private final long joinDeadline;
        private long startedAt = Long.MIN_VALUE;
        private boolean initiatorJoined;
        private boolean companionJoined;
        private boolean murmurPlayed;
        private boolean cancelled;

        Session(UUID settlementId, UUID initiator, UUID companion, long joinDeadline) {
            this.settlementId = settlementId;
            this.initiator = initiator;
            this.companion = companion;
            this.joinDeadline = joinDeadline;
        }

        boolean includes(SettlerEntity settler) {
            return initiator.equals(settler.getUUID()) || companion.equals(settler.getUUID());
        }

        boolean awaitingPartner(long now) {
            return !cancelled && startedAt == Long.MIN_VALUE && now <= joinDeadline;
        }

        boolean running(long now) {
            return !cancelled && startedAt != Long.MIN_VALUE && now - startedAt < VillageSocial.CHAT_TICKS;
        }

        boolean valid(ServerLevel level) {
            return !cancelled && participant(level, initiator) != null && participant(level, companion) != null;
        }

        @Nullable
        SettlerEntity partner(ServerLevel level, SettlerEntity actor) {
            return initiator.equals(actor.getUUID()) ? participant(level, companion) : participant(level, initiator);
        }

        void join(SettlerEntity settler, ServerLevel level) {
            if (cancelled || !includes(settler)) {
                return;
            }
            if (initiator.equals(settler.getUUID())) {
                initiatorJoined = true;
            } else {
                companionJoined = true;
            }
            if (startedAt == Long.MIN_VALUE && initiatorJoined && companionJoined) {
                startedAt = level.getGameTime();
                apply(level, VillageSocial.WELCOME);
            }
        }

        void present(SettlerEntity actor, ServerLevel level) {
            if (!running(level.getGameTime())) {
                return;
            }
            long elapsed = level.getGameTime() - startedAt;
            int mode = initiator.equals(actor.getUUID()) && elapsed < VillageSocial.WELCOME_TICKS
                ? VillageSocial.WELCOME
                : initiator.equals(actor.getUUID()) ? VillageSocial.CHAT : VillageSocial.LISTEN;
            actor.setActivity(SettlerActivity.SOCIALIZING);
            actor.setVillageSocial(mode, startedAt);
            if (!murmurPlayed && elapsed >= 20L) {
                murmurPlayed = true;
                level.playSound(null, actor.blockPosition(), ModSounds.SETTLER_HM.get(),
                    SoundSource.NEUTRAL, 0.35F, 0.82F + actor.getRandom().nextFloat() * 0.12F);
            }
        }

        void cancel(ServerLevel level) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (UUID id : new UUID[] {initiator, companion}) {
                SettlerEntity participant = participant(level, id);
                if (participant == null) {
                    continue;
                }
                participant.setVillageSocial(VillageSocial.NONE, level.getGameTime());
                if (participant.getActivity() == SettlerActivity.SOCIALIZING) {
                    participant.setActivity(SettlerActivity.IDLE);
                }
            }
            Map<UUID, Session> sessions = sessions(level);
            if (sessions.get(settlementId) == this) {
                sessions.remove(settlementId);
            }
        }

        private void apply(ServerLevel level, int mode) {
            for (UUID id : new UUID[] {initiator, companion}) {
                SettlerEntity participant = participant(level, id);
                if (participant != null) {
                    participant.setActivity(SettlerActivity.SOCIALIZING);
                    participant.setVillageSocial(initiator.equals(id) ? mode : VillageSocial.LISTEN, startedAt);
                }
            }
        }

        @Nullable
        private SettlerEntity participant(ServerLevel level, UUID id) {
            return level.getEntity(id) instanceof SettlerEntity settler
                && settlementId.equals(settler.getSettlementId()) ? settler : null;
        }
    }
}
