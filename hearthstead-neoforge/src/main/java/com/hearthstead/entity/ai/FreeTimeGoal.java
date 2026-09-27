package com.hearthstead.entity.ai;

import com.hearthstead.entity.LifeNeed;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.VillageSocial;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.Summons;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Free-time life: in the midday meal and evening phases an idle resident
 * now and then either greets a nearby player (turn, then the existing
 * WELCOME wave) or wanders to the Hearth and stands warming at it.
 *
 * <p>Presentation only. Priority 7 (with {@link WorkCompanionGoal}): every
 * work, delivery, meal, rest, Tavern, alert and defense goal is above it,
 * and {@link #canContinueToUse()} re-checks the same gate every tick, so
 * anything important simply takes over. It owns no seat, item, Coin or
 * claim. Bounded: one attempt per 30-60 s per resident, one path request
 * per scene (a single capped repath), the Hearth must already be within
 * {@link #HEARTH_RANGE} blocks, and a player is greeted at most once per
 * {@link #GREET_COOLDOWN_TICKS} by the same resident.</p>
 *
 * <p>Sitting on benches/stairs was deliberately left out: neither existing
 * seat entity is safe to reuse outside its own job (the Fisher seat
 * discards non-fishers; the Tavern seat is owned by TavernSeating).</p>
 */
public final class FreeTimeGoal extends Goal {
    static final int ATTEMPT_BASE_TICKS = 600;
    static final int ATTEMPT_JITTER_TICKS = 600;
    static final int INELIGIBLE_RECHECK_TICKS = 40;
    static final double GREET_RANGE = 5.0D;
    static final double HEARTH_RANGE = 16.0D;
    static final double WARM_NEAR = 3.2D;
    static final int GREET_TICKS = 44;
    static final int WARM_TICKS = 200;
    static final int WALK_LIMIT_TICKS = 200;
    static final long GREET_COOLDOWN_TICKS = 6000L;
    static final float WARM_CHANCE = 0.4F;
    /** Living village: evenings draw more of the idle to the Banner fire, for longer. */
    static final float EVENING_GATHER_CHANCE = 0.7F;
    static final int EVENING_LINGER_TICKS = 600;
    static final double CHAT_RANGE = 4.0D;
    static final int CHAT_ODDS = 40;

    /** Residents standing at the fire right now (server thread only; weak keys). */
    private static final java.util.Set<SettlerEntity> WARMING =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Turns per fire chat: speaker, answer, speaker (the shared SocialPair clock). */
    static final int CHAT_TURNS = 3;

    enum Mode { GREET, WARM }

    private final SettlerEntity settler;
    private final Map<UUID, Long> greeted = new HashMap<>();
    private long nextAttempt = Long.MIN_VALUE;
    @Nullable
    private Mode mode;
    @Nullable
    private UUID playerId;
    @Nullable
    private BlockPos hearth;
    private long startedAt;
    private long lingerUntil;
    private boolean arrived;
    private int repaths;

    public FreeTimeGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * The whole gate, pure over its inputs so GameTests can pin it without
     * moving the clock: meal/evening only, never while the resident should
     * work or sleep, never during a raid or alarm, never while hungry,
     * tired, carrying, holding tools, eating, seated or summoned.
     */
    public static boolean allowed(SettlerEntity settler, @Nullable Settlement settlement,
                                  DayPhase phase, long now) {
        if (settlement == null || !(phase.meal() || phase.social())
            || Schedule.shouldWork(settlement, settler, phase)
            || Schedule.shouldSleep(settlement, settler, phase)
            || LifeNeed.threatActive(settlement, now)) {
            return false;
        }
        return settler.isAlive() && settler.isBound() && !settler.isTraveler()
            && settler.getActivity() == SettlerActivity.IDLE
            && !settler.isPassenger() && !settler.isSleeping() && !settler.hasMeal()
            && !settler.hasTavernSeat() && settler.getTarget() == null
            && settler.hurtTime == 0 && !settler.isOnFire()
            && settler.getHunger() >= 50.0F && settler.getEnergy() >= 35.0F
            && settler.carryFraction() <= 0.0F && settler.getMainHandItem().isEmpty()
            && settler.getOffhandItem().isEmpty() && !Summons.active(settler)
            && settler.getProfession() != Profession.GUARD
            && settler.getProfession() != Profession.ARCHER;
    }

    /** Looser in-scene check: a started scene may keep its own SOCIALIZING. */
    private boolean stillAllowed(long now) {
        Settlement settlement = settler.settlement();
        DayPhase phase = settler.dayPhase();
        if (settlement == null || !(phase.meal() || phase.social())
            || Schedule.shouldWork(settlement, settler, phase)
            || Schedule.shouldSleep(settlement, settler, phase)
            || LifeNeed.threatActive(settlement, now)) {
            return false;
        }
        SettlerActivity activity = settler.getActivity();
        boolean ownActivity = activity == SettlerActivity.IDLE
            || mode == Mode.GREET && activity == SettlerActivity.SOCIALIZING;
        return ownActivity && settler.isAlive() && !settler.isPassenger()
            && !settler.isSleeping() && !settler.hasMeal() && !settler.hasTavernSeat()
            && settler.getTarget() == null && settler.hurtTime == 0 && !settler.isOnFire()
            && settler.getHunger() >= 40.0F && settler.getEnergy() >= 25.0F
            && settler.carryFraction() <= 0.0F && !Summons.active(settler);
    }

    @Override
    public boolean canUse() {
        if (!(settler.level() instanceof ServerLevel level)) {
            return false;
        }
        long now = level.getGameTime();
        if (now < nextAttempt) {
            return false;
        }
        if (!allowed(settler, settler.settlement(), settler.dayPhase(), now)
            || !settler.getNavigation().isDone()) {
            nextAttempt = now + INELIGIBLE_RECHECK_TICKS;
            return false;
        }
        nextAttempt = now + ATTEMPT_BASE_TICKS + settler.getRandom().nextInt(ATTEMPT_JITTER_TICKS);
        greeted.values().removeIf(at -> now - at >= GREET_COOLDOWN_TICKS || at > now);

        Player player = level.getNearestPlayer(settler, GREET_RANGE);
        if (player != null && player.isAlive() && !player.isSpectator()
            && !greeted.containsKey(player.getUUID()) && settler.hasLineOfSight(player)) {
            mode = Mode.GREET;
            playerId = player.getUUID();
            return true;
        }
        BlockPos hearthPos = settler.getHearthPos();
        float warmChance = eveningGathering() ? EVENING_GATHER_CHANCE : WARM_CHANCE;
        // Tech tree (Warm Hearth): the fire draws settlers more often.
        warmChance = com.hearthstead.settlement.techtree.effects.CommonsEffects.warmChance(settler, warmChance);
        if (hearthPos != null && settler.getRandom().nextFloat() < warmChance
            && settler.distanceToSqr(Vec3.atCenterOf(hearthPos)) <= HEARTH_RANGE * HEARTH_RANGE
            && level.isLoaded(hearthPos)) {
            mode = Mode.WARM;
            hearth = hearthPos.immutable();
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(settler.level() instanceof ServerLevel level) || mode == null) {
            return false;
        }
        long now = level.getGameTime();
        if (!stillAllowed(now)) {
            return false;
        }
        if (mode == Mode.GREET) {
            return now - startedAt < GREET_TICKS && greetTarget(level) != null;
        }
        if (!arrived) {
            return now - startedAt < WALK_LIMIT_TICKS;
        }
        return now < lingerUntil;
    }

    @Override
    public void start() {
        if (!(settler.level() instanceof ServerLevel level) || mode == null) {
            return;
        }
        startedAt = level.getGameTime();
        arrived = false;
        repaths = 0;
        if (mode == Mode.GREET) {
            settler.getNavigation().stop();
            greeted.put(playerId, startedAt);
            settler.setActivity(SettlerActivity.SOCIALIZING);
            settler.setVillageSocial(VillageSocial.WELCOME, startedAt);
        } else if (hearth != null) {
            Vec3 spot = warmSpot(settler, hearth);
            settler.getNavigation().moveTo(spot.x, hearth.getY(), spot.z, 0.7D);
        }
    }

    @Override
    public void tick() {
        if (!(settler.level() instanceof ServerLevel level) || mode == null) {
            return;
        }
        long now = level.getGameTime();
        if (mode == Mode.GREET) {
            Player player = greetTarget(level);
            if (player != null) {
                settler.getLookControl().setLookAt(player, 30.0F, 30.0F);
            }
            return;
        }
        if (hearth == null) {
            return;
        }
        Vec3 centre = Vec3.atCenterOf(hearth);
        if (!arrived) {
            double fromCentre = settler.distanceToSqr(centre);
            if (fromCentre <= WARM_NEAR * WARM_NEAR && fromCentre >= KEEP_CLEAR * KEEP_CLEAR) {
                arrived = true;
                settler.getNavigation().stop();
                lingerUntil = now + (eveningGathering() ? EVENING_LINGER_TICKS : WARM_TICKS)
                    + settler.getRandom().nextInt(80);
                WARMING.add(settler);
                // Tech tree (Warm Hearth): evening warmth, +3 morale once a day.
                com.hearthstead.settlement.techtree.effects.CommonsEffects.onWarmAtHearth(settler);
            } else if (settler.getNavigation().isDone() && repaths < 1) {
                // One capped retry, never a per-tick path request.
                repaths++;
                Vec3 spot = warmSpot(settler, hearth);
                settler.getNavigation().moveTo(spot.x, hearth.getY(), spot.z, 0.7D);
            } else if (settler.getNavigation().isDone()) {
                lingerUntil = now; // Unreachable: give up quietly.
                startedAt = now - WALK_LIMIT_TICKS;
            }
            return;
        }
        if (eveningGathering() && chatAtTheFire()) {
            return; // SocialPair.tick owns facing and look-at while the pair runs.
        }
        // Standing at the fire: the ordinary idle body, facing the flames.
        settler.getLookControl().setLookAt(centre.x, centre.y, centre.z, 20.0F, 20.0F);
    }

    /**
     * QA U9: never linger on the Banner's front cells, where the player
     * clicks. Each resident has its own spot on a ring about 3 blocks out
     * (angle from its UUID), so a gathering is a loose circle, not a crowd.
     */
    static final double KEEP_CLEAR = 2.0D;
    static final double WARM_RING = 3.0D;

    static Vec3 warmSpot(SettlerEntity settler, BlockPos hearth) {
        double angle = (settler.getUUID().getLeastSignificantBits() & 0xFFFF) / 65536.0D * Math.PI * 2.0D;
        return new Vec3(hearth.getX() + 0.5D + Math.cos(angle) * WARM_RING, hearth.getY(),
            hearth.getZ() + 0.5D + Math.sin(angle) * WARM_RING);
    }

    /** Living village: evening chat at the Banner fire (reuses VillageSocial CHAT/LISTEN). */
    private boolean eveningGathering() {
        return settler.dayPhase().social() && com.hearthstead.ambient.LivingVillage.enabled();
    }

    /**
     * Two residents at the fire now and then trade a few words through the
     * shared {@link com.hearthstead.entity.SocialPair} clock (tavern lane):
     * facing each other, looking at each other, taking turns. Each side's own
     * goal ticks its half. Presentation only.
     *
     * @return true while this resident is in a running pair
     */
    private boolean chatAtTheFire() {
        if (com.hearthstead.entity.SocialPair.partner(settler) != null) {
            if (com.hearthstead.entity.SocialPair.tick(settler)) {
                return true;
            }
            com.hearthstead.entity.SocialPair.stop(settler);
            return false;
        }
        if (settler.villageSocialMode() != VillageSocial.NONE
            || settler.getRandom().nextInt(CHAT_ODDS) != 0) {
            return false;
        }
        for (SettlerEntity other : WARMING) {
            if (other != settler && other.isAlive() && other.level() == settler.level()
                && other.villageSocialMode() == VillageSocial.NONE
                && com.hearthstead.entity.SocialPair.partner(other) == null
                && settler.distanceToSqr(other) <= CHAT_RANGE * CHAT_RANGE) {
                return com.hearthstead.entity.SocialPair.start(settler, other, CHAT_TURNS)
                    && com.hearthstead.entity.SocialPair.tick(settler);
            }
        }
        return false;
    }

    @Override
    public void stop() {
        if (mode == Mode.GREET) {
            settler.setVillageSocial(VillageSocial.NONE, settler.level().getGameTime());
            if (settler.getActivity() == SettlerActivity.SOCIALIZING) {
                settler.setActivity(SettlerActivity.IDLE);
            }
        } else if (mode == Mode.WARM && !arrived) {
            settler.getNavigation().stop();
        } else if (mode == Mode.WARM) {
            WARMING.remove(settler);
            if (com.hearthstead.entity.SocialPair.partner(settler) != null) {
                com.hearthstead.entity.SocialPair.stop(settler);
            }
        }
        mode = null;
        playerId = null;
        hearth = null;
    }

    @Nullable
    private Player greetTarget(ServerLevel level) {
        if (playerId == null) {
            return null;
        }
        Player player = level.getPlayerByUUID(playerId);
        return player != null && player.isAlive() && !player.isSpectator()
            && settler.distanceToSqr(player) <= (GREET_RANGE + 2.0D) * (GREET_RANGE + 2.0D)
            ? player : null;
    }

    /** GameTest seam: the scene currently running, or null. */
    @Nullable
    public String activeMode() {
        return mode == null ? null : mode.name();
    }
}
