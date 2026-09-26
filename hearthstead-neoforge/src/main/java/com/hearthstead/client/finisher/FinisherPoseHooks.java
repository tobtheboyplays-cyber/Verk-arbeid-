package com.hearthstead.client.finisher;

import com.hearthstead.client.motion.MotionLibrary;
import com.hearthstead.client.motion.MotionOverrides;
import com.hearthstead.client.motion.PlayerClips;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.finisher.FinisherVariant;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges executions onto the motion engine's two shared hooks (agreed with
 * the motion lane, 2026-09-26):
 * <ul>
 *   <li>{@link PlayerClips}: executor / partner / READY clips on the vanilla
 *       player model ({@code animations/player/finisher_*}), flattened from
 *       the settler rig at export time;</li>
 *   <li>{@link MotionOverrides}: the victim's exclusive absolute reaction clip
 *       on the raider or goblin rig ({@code animations/raider|goblin/finisher_victim_*}).</li>
 * </ul>
 * Clips play linearly from the start packet; the hit-stop hold is baked into
 * them, so every viewer sees the same frame.
 */
public final class FinisherPoseHooks {
    public static final String READY_CLIP = "player/finisher_ready";
    /** A victim lies on its last frame this long (the server removes it ~1 s after the kill). */
    public static final int VICTIM_HOLD_TICKS = 40;

    private FinisherPoseHooks() {
    }

    /** Registered once at client setup. */
    static void registerPlayerProvider() {
        PlayerClips.register(FinisherPoseHooks::playerRequest);
    }

    @Nullable
    static PlayerClips.Request playerRequest(Player player) {
        FinisherClient.Exec exec = FinisherClient.forActor(player);
        if (exec == null || exec.isGuard() || !exec.actorsLocked(0.0F)) {
            return null;
        }
        String key = playerClipKey(exec, player);
        if (key == null || MotionLibrary.override(key) == null) {
            return null;
        }
        // Absolute: full-body choreography from rest, not added onto vanilla's item/bob pose.
        return new PlayerClips.Request(key, exec.startGameTime(), exec.isReady(), true);
    }

    @Nullable
    static String playerClipKey(FinisherClient.Exec exec, Entity actor) {
        FinisherVariant variant = exec.variant();
        if (variant == null) {
            return READY_CLIP;
        }
        return actor.getId() == exec.partnerId() ? variant.partnerClipKey() : variant.leadClipKey();
    }

    static String rigOf(Entity victim) {
        return victim instanceof RaiderEntity raider && raider.isGoblinThiefDemo() ? "goblin" : "raider";
    }

    /** Starts (or restarts) the victim's reaction clip on its own tick clock. */
    static void startVictim(FinisherClient.Exec exec, @Nullable Entity victim, int elapsedTicks) {
        if (victim == null || exec.variant() == null || !(victim instanceof RaiderEntity)) {
            return;
        }
        String key = exec.variant().victimClipKey(rigOf(victim));
        if (MotionLibrary.override(key) == null) {
            return;
        }
        MotionOverrides.start(victim.getId(), key, victim.tickCount - elapsedTicks, VICTIM_HOLD_TICKS);
    }

    static void stopVictim(int victimId) {
        MotionOverrides.stop(victimId);
    }

    /**
     * Whether the motion engine is posing this victim with its fall clip right
     * now (so vanilla's sideways death tip may be hidden). A victim with no
     * authored clip keeps vanilla's death animation.
     */
    static boolean hasVictimClip(LivingEntity entity) {
        MotionOverrides.Active active = MotionOverrides.get(entity.getId());
        return active != null && active.key().contains("/finisher_victim_")
            && MotionLibrary.override(active.key()) != null;
    }
}
