package com.hearthstead.registry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Bannerhold's own particle types (particles &amp; juice lane). Plain
 * {@link SimpleParticleType}s: the look lives in the client provider
 * ({@code client.fx.FxParticle}) and the sprites in
 * {@code assets/hearthstead/particles/*.json} (drawn by
 * {@code tools/gen_fx_particles.py}). Usable from clip sidecars too
 * ({@code motion/particles.json}, e.g. {@code "hearthstead:anvil_spark"}).
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(Registries.PARTICLE_TYPE, Hearthstead.MODID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARKLE = simple("sparkle");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MOTE = simple("mote");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> EMBER = simple("ember");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FIREFLY = simple("firefly");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ANVIL_SPARK = simple("anvil_spark");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FLOUR_PUFF = simple("flour_puff");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> STEAM = simple("steam");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WOOD_CHIP = simple("wood_chip");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> COIN = simple("coin");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> CONFETTI = simple("confetti");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DUST_MOTE = simple("dust_mote");

    private ModParticles() {
    }

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> simple(String name) {
        return PARTICLE_TYPES.register(name, () -> new SimpleParticleType(false));
    }

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
