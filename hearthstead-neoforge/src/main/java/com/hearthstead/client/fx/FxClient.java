package com.hearthstead.client.fx;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.fx.FxBudget;
import com.hearthstead.fx.FxEffect;
import com.hearthstead.network.FxPayload;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client side of the FX pipeline (particles &amp; juice lane).
 *
 * <ul>
 *   <li>{@link #accept}: one {@link FxPayload} from the server becomes a
 *       whole particle moment ({@link FxRecipes}), culled by distance.</li>
 *   <li>Budget: every Bannerhold particle is admitted through {@link #admit}
 *       (off switch + hard live cap, see {@link FxBudget}); counts are
 *       scaled by {@code [particles] intensity} and the vanilla Particles
 *       option through {@link #count}.</li>
 *   <li>Sound: {@link #playSound} is the one place a moment's sound is
 *       chosen (premium {@code hearthstead:fx.<key>} if the pack defines
 *       it, else the vanilla stand-in in {@link FxEffect}).</li>
 *   <li>A tiny timeline for moments that unfold over a second or two.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FxClient {
    public static final FxBudget BUDGET = new FxBudget();
    private static final int TIMELINE_CAP = 96;

    private record Pending(long due, Runnable action) {
    }

    private static final List<Pending> TIMELINE = new ArrayList<>();
    private static long clientTicks;
    @Nullable
    private static ClientLevel lastLevel;

    private FxClient() {
    }

    // ------------------------------------------------------------ budget ---

    /** Off switch + live cap; called by the particle provider for every particle. */
    public static boolean admit() {
        if (!HearthsteadClientConfig.particlesEnabled()) {
            return false;
        }
        return BUDGET.tryReserve(FxBudget.capFor(HearthsteadClientConfig.particlesIntensity()));
    }

    /** Scaled whole count for an authored {@code base}. */
    public static int count(int base, boolean essential) {
        Minecraft mc = Minecraft.getInstance();
        int status = mc.options == null ? FxBudget.STATUS_ALL : mc.options.particles().get().getId();
        float scale = FxBudget.scale(HearthsteadClientConfig.particlesIntensity(), status, essential);
        ClientLevel level = mc.level;
        double roll = level == null ? 0.5D : level.random.nextDouble();
        return FxBudget.count(base, scale, roll);
    }

    /** Camera distance culling for a moment at (x, y, z). */
    public static boolean inRange(double x, double y, double z, double range) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer == null ? null : mc.gameRenderer.getMainCamera();
        Vec3 eye = camera != null && camera.isInitialized() ? camera.getPosition()
            : mc.player != null ? mc.player.getEyePosition() : null;
        return eye != null && FxBudget.inRange(x - eye.x, y - eye.y, z - eye.z,
            Math.min(range, FxBudget.DEFAULT_CULL_RANGE + 16.0D));
    }

    // ----------------------------------------------------------- spawning ---

    /** One Bannerhold particle, or null when refused (budget, off). */
    @Nullable
    public static FxParticle spawn(SimpleParticleType type, double x, double y, double z,
                                   double vx, double vy, double vz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.particleEngine == null) {
            return null;
        }
        Particle particle = mc.particleEngine.createParticle(type, x, y, z, vx, vy, vz);
        return particle instanceof FxParticle fx ? fx : null;
    }

    /** A vanilla particle under the same off switch and budget. */
    public static void vanilla(ParticleOptions options, double x, double y, double z,
                               double vx, double vy, double vz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && admit()) {
            mc.level.addParticle(options, true, x, y, z, vx, vy, vz);
        }
    }

    /** Runs {@code action} {@code delayTicks} client ticks from now (dropped if the queue is full). */
    public static void later(int delayTicks, Runnable action) {
        if (TIMELINE.size() < TIMELINE_CAP) {
            TIMELINE.add(new Pending(clientTicks + Math.max(1, delayTicks), action));
        }
    }

    // -------------------------------------------------------------- sound ---

    /** The single place an FX moment's sound is chosen and played. */
    public static void playSound(FxEffect effect, double x, double y, double z, float pitchScale) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        ResourceLocation premium = Hearthstead.id(effect.soundId());
        SoundEvent sound;
        float volume;
        if (mc.getSoundManager().getSoundEvent(premium) != null) {
            sound = SoundEvent.createVariableRangeEvent(premium);
            volume = 1.0F;
        } else if (!effect.fallbackSound().isEmpty()) {
            sound = SoundEvent.createVariableRangeEvent(ResourceLocation.withDefaultNamespace(effect.fallbackSound()));
            volume = effect.volume();
        } else {
            return;
        }
        level.playLocalSound(x, y, z, sound, SoundSource.NEUTRAL, volume, effect.pitch() * pitchScale, false);
    }

    // ------------------------------------------------------------ network ---

    /** Server moment: follow the entity when loaded, cull, then draw. */
    public static void accept(FxPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        FxEffect effect = payload.effectType();
        if (level == null || effect == null || !effect.networked()) {
            return;
        }
        Entity entity = payload.entityId() >= 0 ? level.getEntity(payload.entityId()) : null;
        // An impact lands where the blow lands, not at the Captain's feet.
        boolean atPoint = effect == FxEffect.CAPTAIN_IMPACT;
        double x = entity != null && !atPoint ? entity.getX() : payload.x();
        double y = entity != null && !atPoint ? entity.getY() : payload.y();
        double z = entity != null && !atPoint ? entity.getZ() : payload.z();
        if (!inRange(x, y, z, effect.range())) {
            return;
        }
        try {
            FxRecipes.play(effect, level, x, y, z, entity, payload.arg(), payload.hasBox() ? payload.box() : null);
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.debug("FX recipe {} failed", effect, failure);
        }
    }

    // --------------------------------------------------------------- tick ---

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            BUDGET.reset();
            TIMELINE.clear();
        }
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        BUDGET.endTick();
        clientTicks++;
        if (!TIMELINE.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            TIMELINE.removeIf(p -> {
                if (p.due() <= clientTicks) {
                    due.add(p);
                    return true;
                }
                return false;
            });
            for (Pending p : due) {
                try {
                    p.action().run();
                } catch (RuntimeException failure) {
                    Hearthstead.LOGGER.debug("FX timeline step failed", failure);
                }
            }
        }
    }

    public static long ticks() {
        return clientTicks;
    }
}
