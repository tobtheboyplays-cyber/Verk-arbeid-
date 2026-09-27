package com.hearthstead.client.sound;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.EnumMap;
import java.util.Map;

/**
 * Quiet looping ambience layers around a living settlement (ElevenLabs sound
 * pass): a day murmur, night crickets, rain on roofs, a market bustle while a
 * merchant is in town, and workshop room tones near a working forge or bench.
 *
 * <p>Client only and presentation only. Each bed is one looping sound instance
 * that fades toward a target volume, so layers cross-fade instead of popping.
 * The scan runs once a second over already-rendered entities; nothing is sent
 * to or read from the server. Beds use {@link SoundSource#AMBIENT} so the
 * player's ambient slider governs them.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class AmbienceBedsClient {
    private enum Bed {
        VILLAGE("ambient.village_murmur", 0.55F),
        NIGHT("ambient.night_crickets", 0.5F),
        RAIN("ambient.rain_roof", 0.5F),
        MARKET("ambient.market_bustle", 0.6F),
        SMITHY("ambient.workshop_smithy", 0.5F),
        WOOD("ambient.workshop_wood", 0.5F);

        final String path;
        final float max;

        Bed(String path, float max) {
            this.path = path;
            this.max = max;
        }
    }

    private static final Map<Bed, Loop> PLAYING = new EnumMap<>(Bed.class);
    private static final Map<Bed, Float> TARGET = new EnumMap<>(Bed.class);
    private static Object world;

    private AmbienceBedsClient() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level) {
            world = mc.level;
            PLAYING.values().forEach(Loop::kill);
            PLAYING.clear();
            TARGET.clear();
        }
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        if (mc.level.getGameTime() % 20 != 0) return;
        if (!com.hearthstead.HearthsteadClientConfig.ambienceBeds()) {
            PLAYING.values().forEach(l -> l.target = 0.0F);
            return;
        }

        int settlers = 0;
        boolean smithy = false, wood = false, market = false;
        double nearWork = 12 * 12;
        for (Entity e : mc.level.entitiesForRendering()) {
            double d = mc.player.distanceToSqr(e);
            if (e instanceof WanderingTrader && d < 32 * 32) market = true;
            if (!(e instanceof SettlerEntity s) || !s.isAlive() || d > 48 * 48) continue;
            settlers++;
            if (d < nearWork) {
                SettlerActivity a = s.getActivity();
                if (a == SettlerActivity.WORK_HAMMER || a == SettlerActivity.WORK_STOKE) smithy = true;
                if (a == SettlerActivity.WORK_SAW || a == SettlerActivity.WORK_PLANE) wood = true;
                if ((a == SettlerActivity.TRADING || a == SettlerActivity.SORTING)
                    && s.getProfession() == com.hearthstead.entity.Profession.TRADER) market = true;
            }
        }
        BlockPos head = BlockPos.containing(mc.player.getEyePosition());
        boolean outdoors = mc.level.canSeeSky(head);
        boolean raining = mc.level.isRaining();
        boolean day = mc.level.isDay();
        boolean town = settlers >= 3;

        TARGET.put(Bed.VILLAGE, town && day && !raining ? (outdoors ? 1.0F : 0.45F) : 0.0F);
        TARGET.put(Bed.NIGHT, settlers >= 1 && !day && !raining && outdoors ? 1.0F : 0.0F);
        TARGET.put(Bed.RAIN, settlers >= 1 && raining ? (outdoors ? 0.7F : 1.0F) : 0.0F);
        TARGET.put(Bed.MARKET, market && day ? 1.0F : 0.0F);
        TARGET.put(Bed.SMITHY, smithy ? 1.0F : 0.0F);
        TARGET.put(Bed.WOOD, wood ? 1.0F : 0.0F);

        for (Bed bed : Bed.values()) {
            float want = TARGET.getOrDefault(bed, 0.0F) * bed.max;
            Loop loop = PLAYING.get(bed);
            if (want > 0.0F && (loop == null || loop.isStopped())) {
                ResourceLocation id = Hearthstead.id(bed.path);
                if (mc.getSoundManager().getSoundEvent(id) == null) continue;
                loop = new Loop(SoundEvent.createVariableRangeEvent(id));
                PLAYING.put(bed, loop);
                mc.getSoundManager().play(loop);
            }
            if (loop != null) loop.target = want;
        }
    }

    /** A non-positional looping bed that eases toward its target volume and stops at silence. */
    private static final class Loop extends AbstractTickableSoundInstance {
        float target;
        private float level;

        Loop(SoundEvent sound) {
            super(sound, SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.001F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            level = Mth.approach(level, target, 0.01F);   // ~2.5 s full fade
            volume = Math.max(0.001F, level);
            if (level <= 0.0F && target <= 0.0F) stop();
        }

        void kill() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
