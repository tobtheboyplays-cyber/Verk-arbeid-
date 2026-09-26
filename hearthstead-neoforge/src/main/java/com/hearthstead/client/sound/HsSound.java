package com.hearthstead.client.sound;

import com.hearthstead.Hearthstead;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Client-only playback of hearthstead sound ids that exist only in sounds.json
 * (UI, conversation, ambience beds). If a resource pack removes the id, the
 * given vanilla fallback plays instead, so a missing asset never goes silent.
 */
public final class HsSound {
    private HsSound() {
    }

    public static ResourceLocation id(String path) {
        return Hearthstead.id(path);
    }

    /** Resolve an id against the loaded sounds.json, falling back to a vanilla event. */
    public static ResourceLocation resolve(ResourceLocation id, Object fallback) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager().getSoundEvent(id) != null || fallback == null) {
            return id;
        }
        if (fallback instanceof SoundEvent event) {
            return event.getLocation();
        }
        if (fallback instanceof Holder<?> holder && holder.value() instanceof SoundEvent event) {
            return event.getLocation();
        }
        return id;
    }

    /** Non-positional interface sound (screens, handbook, map). */
    public static void ui(String path, Object fallback, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(new SimpleSoundInstance(resolve(id(path), fallback), SoundSource.MASTER,
            volume, pitch, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE,
            0.0D, 0.0D, 0.0D, true));
    }

    /** Positional one-shot in the world. */
    public static void at(String path, Object fallback, SoundSource source, double x, double y, double z,
                          float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(new SimpleSoundInstance(resolve(id(path), fallback), source,
            volume, pitch, RandomSource.create(), false, 0, SoundInstance.Attenuation.LINEAR,
            x, y, z, false));
    }
}
