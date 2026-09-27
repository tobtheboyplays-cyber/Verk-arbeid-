package com.hearthstead.entity.ai;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Plays a settler's work-contact sound on the same client frame as the
 * animation beat it belongs to.
 *
 * <p>Why a deferred send is needed at all: every work clip is started on
 * the client from the synced {@code SettlerActivity}, and vanilla ships
 * entity-data changes from {@code ServerEntity.sendChanges()} inside the
 * NEXT server tick's chunk-source pass (ServerLevel ticks its chunk source
 * before its entities). {@code level.playSound} instead writes its packet
 * immediately. So a sound played on server tick {@code S+n} reached the
 * client exactly one tick earlier, relative to the clip, than the frame it
 * was meant for -- the audio led the visible contact by 50 ms (audio that
 * LEADS the picture by more than ~45 ms reads as wrong; trailing it by a
 * few ms reads as natural).
 *
 * <p>Queued sounds are flushed at {@link LevelTickEvent.Pre} of the next
 * tick, i.e. immediately before that tick's chunk-source pass sends the
 * entity data -- the sound and the activity it belongs to now travel in
 * the same network flush, so the goal's own "counter % period == contact"
 * math lands on the clip's own contact frame.
 *
 * <p>It is also the one place work-sound quality rules live: a restrained
 * volume ceiling (work sounds must never outshout the footsteps next to
 * them) and a +/-6 % pitch spread so a repeating strike never becomes a
 * machine gun.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WorkSoundSync {

    /** Hard ceiling for any settler work accent (sounds.json weights stack on top). */
    public static final float MAX_WORK_VOLUME = 0.6F;
    /** Half-width of the random pitch spread: 0.94..1.06 around the base pitch. */
    public static final float PITCH_SPREAD = 0.06F;

    private record Pending(double x, double y, double z, SoundEvent sound,
                           SoundSource source, float volume, float pitch) {}

    private static final Map<ServerLevel, List<Pending>> QUEUE = new WeakHashMap<>();

    private WorkSoundSync() {}

    /** Contact accent at a block, varied pitch, volume capped. */
    public static void play(ServerLevel level, BlockPos pos, SoundEvent sound,
                            float volume, float basePitch) {
        play(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            sound, volume, basePitch);
    }

    public static void play(ServerLevel level, double x, double y, double z,
                            SoundEvent sound, float volume, float basePitch) {
        RandomSource random = level.getRandom();
        float pitch = basePitch * (1.0F - PITCH_SPREAD
            + random.nextFloat() * 2.0F * PITCH_SPREAD);
        QUEUE.computeIfAbsent(level, ignored -> new ArrayList<>())
            .add(new Pending(x, y, z, sound, SoundSource.NEUTRAL,
                Math.min(volume, MAX_WORK_VOLUME), pitch));
    }

    /** Same deferred, capped send, but with the caller's own exact pitch
     *  (for call sites that already vary pitch themselves). */
    public static void playExact(ServerLevel level, double x, double y, double z,
                                 SoundEvent sound, float volume, float pitch) {
        QUEUE.computeIfAbsent(level, ignored -> new ArrayList<>())
            .add(new Pending(x, y, z, sound, SoundSource.NEUTRAL,
                Math.min(volume, MAX_WORK_VOLUME), pitch));
    }

    @SubscribeEvent
    public static void onLevelTickPre(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<Pending> pending = QUEUE.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<Pending> batch = new ArrayList<>(pending);
        pending.clear();
        for (Pending p : batch) {
            level.playSound(null, p.x(), p.y(), p.z(), p.sound(), p.source(),
                p.volume(), p.pitch());
        }
    }
}
