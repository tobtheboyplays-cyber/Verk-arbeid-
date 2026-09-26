package com.hearthstead.client.ambient;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.ambient.AmbientCue;
import com.hearthstead.client.motion.MotionLibrary;
import com.hearthstead.client.motion.MotionOverrides;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.AmbientCuePayload;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * Client side of the living village (display only).
 *
 * <ul>
 *   <li>Cues from the server ({@link AmbientCuePayload}): the authored
 *       overlay clip {@code settler/<cue>} when the animation lane has shipped
 *       it, otherwise a procedural fallback ({@link AmbientPose}); cheer falls
 *       back to the existing CELEBRATE clip first.</li>
 *   <li>Barks: one short line per settler, drawn by
 *       {@code client.render.SettlerBarkLabel} for {@link #BARK_MILLIS}.</li>
 *   <li>Weather, derived locally: a settler walking in rain hunches (and the
 *       model picks the hurried gait via {@link #hurryInRain}).</li>
 *   <li>World life: chimney smoke above working forges, ovens and kitchens;
 *       butterflies and birdsong by farmers at work. Vanilla particles and
 *       sounds only, a handful per second at most.</li>
 * </ul>
 * Client toggles: {@code [ambient] barks / gestures / worldLife}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class AmbientClient {
    public static final long BARK_MILLIS = 3800L;
    private static final double LIFE_RANGE = 40.0D;
    private static final int SMOKE_CAP = 8;
    private static final int FIELD_CAP = 3;
    private static final Vector3f[] BUTTERFLY = {
        new Vector3f(1.0F, 0.95F, 0.55F), new Vector3f(1.0F, 0.65F, 0.2F),
        new Vector3f(0.97F, 0.97F, 1.0F), new Vector3f(0.75F, 0.8F, 1.0F)};

    /** A line over a settler's head. */
    public record Bark(Component text, long startMillis) {
    }

    /** A running cue; {@code procedural} = no authored clip, AmbientPose draws it. */
    public record CueState(AmbientCue cue, float startAge, boolean procedural) {
    }

    private static final Int2ObjectOpenHashMap<Bark> BARKS = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectOpenHashMap<CueState> CUES = new Int2ObjectOpenHashMap<>();
    private static final Int2FloatOpenHashMap OVERLAY_UNTIL = new Int2FloatOpenHashMap();
    private static final IntOpenHashSet RAIN_OVERLAY = new IntOpenHashSet();
    /** Per-entity rain answer cached for one game tick: id -> (gameTime << 1 | answer). */
    private static final Int2LongOpenHashMap RAIN_CACHE = new Int2LongOpenHashMap();
    @Nullable
    private static ClientLevel lastLevel;
    private static int ticks;

    private AmbientClient() {
    }

    public static void accept(AmbientCuePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !(level.getEntity(payload.entityId()) instanceof SettlerEntity settler)) {
            return;
        }
        AmbientCue cue = payload.cueType();
        if (cue != AmbientCue.NONE && HearthsteadClientConfig.ambientMotion()) {
            startCue(settler, cue);
        }
        if (!payload.barkKey().isEmpty() && HearthsteadClientConfig.ambientBarks()) {
            Component text = payload.arg().isEmpty()
                ? Component.translatable(payload.barkKey())
                : Component.translatable(payload.barkKey(), payload.arg());
            BARKS.put(settler.getId(), new Bark(text, Util.getMillis()));
        }
    }

    private static void startCue(SettlerEntity settler, AmbientCue cue) {
        int id = settler.getId();
        String key = cue.clipKey();
        boolean authored = MotionLibrary.override(key) != null;
        if (!authored && cue == AmbientCue.CHEER && MotionLibrary.override("settler/celebrate") != null) {
            key = "settler/celebrate";
            authored = true;
        }
        float now = settler.tickCount;
        if (authored) {
            RAIN_OVERLAY.remove(id);
            MotionOverrides.overlay(id, key, now);
            OVERLAY_UNTIL.put(id, now + cue.durationTicks());
        }
        CUES.put(id, new CueState(cue, now, !authored));
    }

    /** The line currently shown over this settler, or null. */
    @Nullable
    public static Bark bark(int entityId) {
        Bark bark = BARKS.isEmpty() ? null : BARKS.get(entityId);
        return bark != null && Util.getMillis() - bark.startMillis() <= BARK_MILLIS ? bark : null;
    }

    /** The cue currently playing on this settler, or null. */
    @Nullable
    public static CueState cue(int entityId) {
        return CUES.isEmpty() ? null : CUES.get(entityId);
    }

    /** Standing in open rain (cached per game tick). The model uses it for the gait. */
    public static boolean hurryInRain(SettlerEntity settler) {
        if (!HearthsteadClientConfig.ambientMotion() || !settler.level().isRaining()) {
            return false;
        }
        long time = settler.level().getGameTime();
        long cached = RAIN_CACHE.getOrDefault(settler.getId(), Long.MIN_VALUE);
        if (cached != Long.MIN_VALUE && (cached >> 1) == time) {
            return (cached & 1L) != 0L;
        }
        boolean wet = settler.level().isRainingAt(settler.blockPosition().above());
        if (RAIN_CACHE.size() > 512) {
            RAIN_CACHE.clear();
        }
        RAIN_CACHE.put(settler.getId(), time << 1 | (wet ? 1L : 0L));
        return wet;
    }

    /** Walking through open rain: the hunch applies. */
    public static boolean rainWalking(SettlerEntity settler) {
        return settler.walkAnimation.speed() > 0.15F && hurryInRain(settler);
    }

    /** True while an authored rain_hunch clip plays this settler's hunch (the fallback then stays off). */
    public static boolean rainClipActive(int entityId) {
        return RAIN_OVERLAY.contains(entityId);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }
        if (level == null || minecraft.player == null || minecraft.isPaused()) {
            return;
        }
        expire(level);
        ticks++;
        if (ticks % 5 != 0) {
            return;
        }
        boolean motion = HearthsteadClientConfig.ambientMotion();
        boolean life = HearthsteadClientConfig.ambientParticles() && ticks % 10 == 0;
        if (!motion && !life) {
            return;
        }
        List<SettlerEntity> near = level.getEntitiesOfClass(SettlerEntity.class,
            minecraft.player.getBoundingBox().inflate(LIFE_RANGE, 16.0D, LIFE_RANGE), SettlerEntity::isAlive);
        if (motion) {
            rainOverlays(near);
        }
        if (life) {
            worldLife(level, near);
        }
    }

    private static void expire(ClientLevel level) {
        if (!OVERLAY_UNTIL.isEmpty()) {
            var it = OVERLAY_UNTIL.int2FloatEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                Entity entity = level.getEntity(entry.getIntKey());
                if (entity == null) {
                    it.remove();
                } else if (entity.tickCount >= entry.getFloatValue()) {
                    MotionOverrides.stopOverlay(entry.getIntKey(), entity.tickCount);
                    it.remove();
                }
            }
        }
        if (!CUES.isEmpty()) {
            var it = CUES.int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                var entry = it.next();
                Entity entity = level.getEntity(entry.getIntKey());
                CueState state = entry.getValue();
                if (entity == null || entity.tickCount - state.startAge() > state.cue().durationTicks()
                    || entity.tickCount < state.startAge()) {
                    it.remove();
                }
            }
        }
        if (!BARKS.isEmpty()) {
            long now = Util.getMillis();
            BARKS.values().removeIf(bark -> now - bark.startMillis() > BARK_MILLIS);
        }
    }

    /** Authored rain_hunch only; without the clip AmbientPose hunches procedurally. */
    private static void rainOverlays(List<SettlerEntity> near) {
        String key = AmbientCue.RAIN_HUNCH.clipKey();
        boolean clip = MotionLibrary.override(key) != null;
        for (SettlerEntity settler : near) {
            int id = settler.getId();
            boolean want = clip && !OVERLAY_UNTIL.containsKey(id) && rainWalking(settler);
            if (want && RAIN_OVERLAY.add(id)) {
                MotionOverrides.overlay(id, key, settler.tickCount);
            } else if (!want && RAIN_OVERLAY.remove(id) && !OVERLAY_UNTIL.containsKey(id)) {
                MotionOverrides.stopOverlay(id, settler.tickCount);
            }
        }
    }

    private static boolean smokyWork(SettlerActivity activity) {
        return activity == SettlerActivity.WORK_HAMMER || activity == SettlerActivity.WORK_STOKE
            || activity == SettlerActivity.WORK_OVEN || activity == SettlerActivity.WORK_KNEAD
            || activity == SettlerActivity.WORK_STIR;
    }

    private static void worldLife(ClientLevel level, List<SettlerEntity> near) {
        RandomSource random = level.random;
        int smoke = 0;
        int fields = 0;
        boolean fair = level.isDay() && !level.isRaining();
        for (SettlerEntity settler : near) {
            SettlerActivity activity = settler.getActivity();
            if (smoke < SMOKE_CAP && smokyWork(activity) && random.nextBoolean()) {
                smoke += chimney(level, settler, random) ? 1 : 0;
            } else if (fair && fields < FIELD_CAP && outdoorsTrade(settler.getProfession())
                && activity != SettlerActivity.IDLE && random.nextInt(4) == 0
                && level.canSeeSky(settler.blockPosition().above())) {
                fields++;
                fieldLife(level, settler, random);
            }
        }
    }

    /**
     * Smoke from the highest roof column around a worker indoors: a chimney
     * if the building has one, otherwise the ridge. Nothing when the worker
     * stands under open sky (no roof, no chimney).
     */
    private static boolean chimney(ClientLevel level, SettlerEntity settler, RandomSource random) {
        BlockPos base = settler.blockPosition();
        int bestTop = Integer.MIN_VALUE;
        int bestX = base.getX();
        int bestZ = base.getZ();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, base.getX() + dx, base.getZ() + dz);
                if (top > bestTop) {
                    bestTop = top;
                    bestX = base.getX() + dx;
                    bestZ = base.getZ() + dz;
                }
            }
        }
        if (bestTop < base.getY() + 3 || bestTop > base.getY() + 24) {
            return false;
        }
        level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
            bestX + 0.5D + (random.nextDouble() - 0.5D) * 0.3D, bestTop + 0.2D,
            bestZ + 0.5D + (random.nextDouble() - 0.5D) * 0.3D,
            0.0D, 0.03D + random.nextDouble() * 0.02D, 0.0D);
        return true;
    }

    private static boolean outdoorsTrade(Profession profession) {
        return profession == Profession.FARMER || profession == Profession.HERDER;
    }

    /** A couple of butterfly motes over the crop, and now and then a bird. */
    private static void fieldLife(ClientLevel level, SettlerEntity settler, RandomSource random) {
        double x = settler.getX() + (random.nextDouble() - 0.5D) * 7.0D;
        double y = settler.getY() + 0.6D + random.nextDouble() * 1.2D;
        double z = settler.getZ() + (random.nextDouble() - 0.5D) * 7.0D;
        DustParticleOptions wing = new DustParticleOptions(BUTTERFLY[random.nextInt(BUTTERFLY.length)], 0.7F);
        for (int i = 0; i < 2; i++) {
            level.addParticle(wing, x + (random.nextDouble() - 0.5D) * 0.4D, y + random.nextDouble() * 0.3D,
                z + (random.nextDouble() - 0.5D) * 0.4D,
                (random.nextDouble() - 0.5D) * 0.4D, 0.1D, (random.nextDouble() - 0.5D) * 0.4D);
        }
        if (random.nextInt(10) == 0) {
            level.playLocalSound(settler.getX() + (random.nextDouble() - 0.5D) * 16.0D, settler.getY() + 5.0D,
                settler.getZ() + (random.nextDouble() - 0.5D) * 16.0D, SoundEvents.PARROT_AMBIENT,
                SoundSource.AMBIENT, 0.12F, 1.7F + random.nextFloat() * 0.3F, false);
        }
    }

    private static void clear() {
        BARKS.clear();
        CUES.clear();
        OVERLAY_UNTIL.clear();
        RAIN_OVERLAY.clear();
        RAIN_CACHE.clear();
    }
}
