package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.entity.RaiderEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Short, distance-scaled camera shake for a Brute's ground slam (and a tiny
 * tremor on its close-range footfalls). Pure client presentation: the server
 * only broadcasts the slam's entity event; nothing here touches gameplay.
 *
 * <p>Honours the {@code combat.cameraShake} client toggle and vanilla's
 * Distortion Effects accessibility slider. The shake is a decaying "trauma"
 * value (amplitude = trauma squared) sampled with smooth incommensurate
 * sines, so it reads as a thump rather than jitter, and lasts about 0.35 s.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CombatCameraShake {
    /** Ticks a full-strength slam takes to settle (about 0.35 s). */
    public static final int SETTLE_TICKS = 7;
    private static final float MAX_YAW = 1.6F;
    private static final float MAX_PITCH = 2.2F;
    private static final float MAX_ROLL = 2.8F;

    private static float trauma;
    private static float previousTrauma;
    private static long seed;

    static {
        // Class loading on the client registers the hook; the common entity
        // never references this class.
        RaiderEntity.clientImpactCue = CombatCameraShake::onImpact;
    }

    private CombatCameraShake() {
    }

    /** Distance falloff: full at the impact, zero at {@code radius}. */
    public static float falloff(double distance, double radius) {
        if (radius <= 0.0D || distance >= radius) {
            return 0.0F;
        }
        float t = (float) (1.0D - distance / radius);
        return t * t;
    }

    private static void onImpact(RaiderEntity source, double x, double y, double z,
                                 float magnitude, double radius) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || source.level() != player.level()
            || !HearthsteadClientConfig.combatCameraShake()) {
            return;
        }
        float added = magnitude * falloff(Math.sqrt(player.distanceToSqr(x, y, z)), radius);
        if (added <= 0.001F) {
            return;
        }
        trauma = Math.min(1.0F, trauma + added);
        previousTrauma = Math.max(previousTrauma, trauma);
        seed = source.getId() * 31L + player.tickCount;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        previousTrauma = trauma;
        if (trauma > 0.0F) {
            trauma = Math.max(0.0F, trauma - 1.0F / SETTLE_TICKS);
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (trauma <= 0.0F && previousTrauma <= 0.0F) {
            return;
        }
        float partial = (float) event.getPartialTick();
        float t = Mth.lerp(partial, previousTrauma, trauma);
        Minecraft mc = Minecraft.getInstance();
        float accessibility = mc.options.screenEffectScale().get().floatValue();
        float amp = t * t * accessibility;
        if (amp <= 0.0F) {
            return;
        }
        float time = (mc.player == null ? 0 : mc.player.tickCount) + partial + seed;
        event.setYaw(event.getYaw() + MAX_YAW * amp * Mth.sin(time * 2.9F));
        event.setPitch(event.getPitch() + MAX_PITCH * amp * Mth.sin(time * 3.7F + 1.3F));
        event.setRoll(event.getRoll() + MAX_ROLL * amp * Mth.sin(time * 2.3F + 2.1F));
    }
}
