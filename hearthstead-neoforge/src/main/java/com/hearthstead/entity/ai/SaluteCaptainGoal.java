package com.hearthstead.entity.ai;

import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "I want a captain whom people greet." (Owner's ask, 2026-08-25.)
 *
 * <p>Registered for <b>every</b> settler, not only guards: the Vaktkaptein is
 * the settlement's highest-ranked living guard ({@link GuardRank#captainOf}),
 * and this goal is what makes that fact visible to a player who has never
 * opened a settler screen. Walk near the captain and, once in a while,
 * whatever you were doing pauses for a moment and the settler turns to face
 * them.
 *
 * <h2>Modest on purpose</h2>
 *
 * <p>Priority 7 — one step below every trade's work goal and
 * {@link GuardPatrolGoal} (6), one step above the idle-wander band
 * ({@link BoundedStrollGoal} 8, {@code LookAtPlayerGoal} 9,
 * {@code RandomLookAroundGoal} 10). Sharing {@link Flag#MOVE} with those
 * neighbours is the entire mechanism: a settler already at work, on patrol,
 * fighting, fleeing, eating or resting keeps their flags and this goal simply
 * never gets a turn, which is "not in combat" (and not mid-shift, not
 * starving) for free, the same way {@code RespondToSummonsGoal} leans on
 * priority ordering rather than checking every higher goal by name. An idle
 * settler — wandering, looking at a player, looking around — yields
 * immediately.
 *
 * <h2>The cue</h2>
 *
 * <p>A dedicated SALUTE keyframe clip is future work (no {@code AnimationState}
 * here, on purpose — this slice does not own {@code SettlerModel} or
 * {@code SettlerAnimations}). Until it exists, the greeting is the least
 * invasive audible+visible pair available from this goal alone, and the
 * visible half — the turned head — is also what {@code GuardRankGameTests}
 * asserts, since there is no clip's {@code AnimationState} to check instead:
 * <ul>
 *   <li><b>Visible</b> — the settler stops (this goal holds {@link Flag#MOVE})
 *       and turns to face the captain via {@link net.minecraft.world.entity.ai.control.LookControl}
 *       for the whole pause, the same tool {@code RespondToSummonsGoal} uses
 *       to look at what it walked to.
 *   <li><b>Audible</b> — one {@link ModSounds#SETTLER_HM} at a lowered pitch,
 *       played directly (mirroring how {@link GuardPatrolGoal} and
 *       {@link GuardLeapGoal} play their own accents straight off the level
 *       rather than through {@code SettlerEntity#playAccent}, which is
 *       private and not this file's to open up).
 * </ul>
 *
 * <h2>Bounded</h2>
 *
 * <p>The captain is never looked up more than once a second
 * ({@link #CAPTAIN_REFRESH_TICKS}) and cached between refreshes, so the
 * expensive part — walking the settlement's loaded members — happens at most
 * once per settler per second, not once per tick. Everything cheaper than
 * that (distance, the cooldown map) runs every tick the way an ordinary
 * {@code canUse()} does. The cooldown map itself never grows past "one entry
 * per captain this settler has ever saluted" — in practice one entry, since a
 * settlement rarely swaps captains inside a settler's lifetime.
 */
public class SaluteCaptainGoal extends Goal {

    /** How close counts as "near enough to notice". */
    private static final double RANGE = 6.0;
    private static final double RANGE_SQR = RANGE * RANGE;
    /** Don't re-greet the same captain more than once every two minutes. */
    private static final int SALUTE_COOLDOWN_TICKS = 2400;
    /** How long the captain reference is trusted before being recomputed. */
    private static final int CAPTAIN_REFRESH_TICKS = 20;
    private static final int PAUSE_MIN_TICKS = 15;
    private static final int PAUSE_MAX_TICKS = 20;

    private final SettlerEntity settler;
    /** Captain UUID -> game time last saluted. One entry in the ordinary
     *  case; never persisted, never shared between settlers. */
    private final Map<UUID, Long> lastSaluted = new HashMap<>();

    @Nullable
    private SettlerEntity cachedCaptain;
    private long captainCacheTick = Long.MIN_VALUE;

    /** Who this run of the goal is saluting, and how long is left. */
    @Nullable
    private SettlerEntity saluting;
    private int pauseTicksLeft;

    public SaluteCaptainGoal(SettlerEntity settler) {
        this.settler = settler;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (settler.getTarget() != null) {
            return false;
        }
        SettlerEntity captain = captain();
        if (captain == null || captain == settler) {
            return false; // no captain yet, or the captain never salutes themselves
        }
        if (settler.distanceToSqr(captain) > RANGE_SQR) {
            return false;
        }
        Long last = lastSaluted.get(captain.getUUID());
        long now = settler.level().getGameTime();
        return last == null || now - last >= SALUTE_COOLDOWN_TICKS;
    }

    @Override
    public boolean canContinueToUse() {
        return pauseTicksLeft > 0 && settler.getTarget() == null;
    }

    @Override
    public void start() {
        saluting = cachedCaptain;
        pauseTicksLeft = PAUSE_MIN_TICKS
            + settler.getRandom().nextInt(PAUSE_MAX_TICKS - PAUSE_MIN_TICKS + 1);
        settler.getNavigation().stop();
        if (saluting != null) {
            lastSaluted.put(saluting.getUUID(), settler.level().getGameTime());
            face(saluting);
        }
        if (settler.level() instanceof ServerLevel serverLevel) {
            // A quiet, lowered-pitch murmur -- respectful, not a cheer.
            serverLevel.playSound(null, settler.blockPosition(), ModSounds.SETTLER_HM.get(),
                SoundSource.NEUTRAL, 0.6F, 0.72F + settler.getRandom().nextFloat() * 0.08F);
        }
    }

    @Override
    public void tick() {
        if (saluting != null && saluting.isAlive()) {
            face(saluting);
        }
        pauseTicksLeft--;
    }

    @Override
    public void stop() {
        saluting = null;
        pauseTicksLeft = 0;
    }

    private void face(SettlerEntity captain) {
        settler.getLookControl().setLookAt(
            captain.getX(), captain.getEyeY(), captain.getZ());
    }

    /** True while a salute is actually playing out. No accessor exists to
     *  reach a specific settler's goal instance from outside the selector, so
     *  {@code GuardRankGameTests} verifies the visible half (facing the
     *  captain) directly off {@link SettlerEntity#getLookAngle()} instead of
     *  this flag; kept as the honest single source of "is one happening right
     *  now" for anything in-process that can reach the goal itself. */
    public boolean isSaluting() {
        return saluting != null && pauseTicksLeft > 0;
    }

    @Nullable
    private SettlerEntity captain() {
        long now = settler.level().getGameTime();
        if (cachedCaptain != null && !cachedCaptain.isAlive()) {
            cachedCaptain = null;
        }
        if (cachedCaptain == null || now - captainCacheTick >= CAPTAIN_REFRESH_TICKS) {
            cachedCaptain = computeCaptain();
            captainCacheTick = now;
        }
        return cachedCaptain;
    }

    @Nullable
    private SettlerEntity computeCaptain() {
        if (!(settler.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Settlement s = settler.settlement();
        if (s == null) {
            return null;
        }
        return GuardRank.captainOf(SettlementManager.loadedMembers(serverLevel, s));
    }
}
