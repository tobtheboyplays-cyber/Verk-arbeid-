package com.hearthstead.settlement;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Come here." A player calling one specific worker to one specific spot —
 * the plaque's SUMMON action ({@link com.hearthstead.network.PlaqueNetwork})
 * is the only thing that calls {@link #call}.
 *
 * <p><b>Deliberately not persisted.</b> This is a transient, in-memory
 * registry keyed by settler UUID, alive only for the current server run. A
 * summons that survived a restart would be worse than none: it would either
 * dangle forever pointing at a position nothing explains any more, or (worse)
 * silently reattach itself to whichever settler happens to load first and
 * share that UUID after the world reopens. A call is a live request, not a
 * fact about the settlement, so it belongs in memory next to the goal that
 * consumes it, not in {@link SettlementSavedData}. If the server stops
 * mid-summons, the worker simply forgets it was called — exactly as if the
 * player had never asked.
 *
 * <p>The other half of the contract is {@link
 * com.hearthstead.entity.ai.RespondToSummonsGoal}, which walks the settler to
 * {@link Summon#where()} while this stays active. This class owns the
 * signal (the map entry and the glow); the goal owns the walking.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class Summons {

    /** How long a call stays live before it gives up on its own, in ticks (~90 s). */
    public static final int DURATION_TICKS = 20 * 90;

    /** One live call: where to go, and the game tick it stops mattering. */
    public record Summon(BlockPos where, long until) {
    }

    // Server-thread only: every access happens from a goal's tick, from
    // PlaqueNetwork's packet handler (already hopped to the main thread via
    // context.enqueueWork), or from this class's own tick/unload listeners
    // below — never from the network thread directly. A plain HashMap is
    // therefore safe and there is no need for anything heavier for what is,
    // in practice, a handful of entries at once.
    private static final Map<UUID, Summon> ACTIVE = new HashMap<>();

    private Summons() {
    }

    /**
     * Calls {@code settler} to {@code where}. Overwrites any earlier call to
     * the same settler (a second summons just moves the destination and
     * resets the clock, rather than stacking).
     *
     * <p>Plays a readable cue at both ends — a bell at the calling point, so
     * everyone nearby the plaque hears the village call someone in, and an
     * acknowledging murmur from the settler themselves, wherever they
     * currently stand — and turns on the vanilla glowing outline so the
     * summoned worker reads at a glance and through walls, the whole way
     * there.
     */
    public static void call(SettlerEntity settler, BlockPos where, ServerLevel level) {
        UUID id = settler.getUUID();
        ACTIVE.put(id, new Summon(where.immutable(), level.getGameTime() + DURATION_TICKS));
        settler.setGlowingTag(true);
        level.playSound(null, where, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 0.8F, 1.4F);
        level.playSound(null, settler.blockPosition(), ModSounds.SETTLER_HM.get(),
            SoundSource.NEUTRAL, 0.9F, 0.95F + settler.getRandom().nextFloat() * 0.1F);
    }

    /**
     * Whether {@code settler} is currently summoned. Self-expiring: a call
     * whose clock has run out is cleared (map entry removed, glow turned
     * off) the moment anything asks, so a goal that only polls this rather
     * than checking {@link Summon#until()} itself can never observe a call
     * that should already be over.
     */
    public static boolean active(SettlerEntity settler) {
        Summon summon = ACTIVE.get(settler.getUUID());
        if (summon == null) {
            return false;
        }
        if (settler.level().getGameTime() >= summon.until()) {
            clear(settler);
            return false;
        }
        return true;
    }

    /** Where {@code settler} was called to, or {@code null} if not currently summoned. */
    public static BlockPos where(SettlerEntity settler) {
        Summon summon = ACTIVE.get(settler.getUUID());
        return summon == null ? null : summon.where();
    }

    /** Ends the call, if there is one, and always turns the glow off. */
    public static void clear(SettlerEntity settler) {
        ACTIVE.remove(settler.getUUID());
        settler.setGlowingTag(false);
    }

    // ------------------------------------------------------- leak guard ---

    /**
     * The goal that walks a summoned settler is the normal way a call ends,
     * but it must never be the ONLY way. A settler who never gets another
     * chance to run {@code RespondToSummonsGoal} at all — sealed into a
     * chunk that stops ticking oddly, killed and never reloaded, or simply
     * unlucky with goal-selector scheduling for the whole 90 seconds — must
     * still lose the glow. This sweep is that backstop: it runs off the
     * level tick directly, entirely independent of any settler's own AI, and
     * catches anything {@link #active} never got asked about.
     *
     * <p>Cheap in the overwhelmingly common case of no summons active at
     * all — one map-empty check and nothing else.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (ACTIVE.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        List<UUID> expired = null;
        for (Map.Entry<UUID, Summon> entry : ACTIVE.entrySet()) {
            if (now >= entry.getValue().until()) {
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(entry.getKey());
            }
        }
        if (expired == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        for (UUID id : expired) {
            ACTIVE.remove(id);
            for (ServerLevel candidate : server.getAllLevels()) {
                if (candidate.getEntity(id) instanceof SettlerEntity settler) {
                    settler.setGlowingTag(false);
                    break;
                }
            }
        }
    }

    /**
     * A settlement can be reloaded within the same server process
     * (single-player: quit to title, open a different save, or the same one
     * again) without the JVM restarting, which a static map would otherwise
     * survive. Mirrors {@code WarehouseStorage.clearAll()}'s handling of the
     * exact same hazard for the exact same reason.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel) {
            ACTIVE.clear();
        }
    }
}
