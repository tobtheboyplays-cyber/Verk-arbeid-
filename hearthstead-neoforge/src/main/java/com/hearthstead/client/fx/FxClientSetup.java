package com.hearthstead.client.fx;

import com.hearthstead.Hearthstead;
import com.hearthstead.registry.ModParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/** Registers the one provider behind every Bannerhold particle type. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FxClientSetup {
    private FxClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.SPARKLE.get(), s -> new FxParticle.Provider(s, FxParticle.Style.SPARKLE));
        event.registerSpriteSet(ModParticles.MOTE.get(), s -> new FxParticle.Provider(s, FxParticle.Style.MOTE));
        event.registerSpriteSet(ModParticles.EMBER.get(), s -> new FxParticle.Provider(s, FxParticle.Style.EMBER));
        event.registerSpriteSet(ModParticles.FIREFLY.get(), s -> new FxParticle.Provider(s, FxParticle.Style.FIREFLY));
        event.registerSpriteSet(ModParticles.ANVIL_SPARK.get(), s -> new FxParticle.Provider(s, FxParticle.Style.SPARK));
        event.registerSpriteSet(ModParticles.FLOUR_PUFF.get(), s -> new FxParticle.Provider(s, FxParticle.Style.PUFF));
        event.registerSpriteSet(ModParticles.STEAM.get(), s -> new FxParticle.Provider(s, FxParticle.Style.STEAM));
        event.registerSpriteSet(ModParticles.WOOD_CHIP.get(), s -> new FxParticle.Provider(s, FxParticle.Style.CHIP));
        event.registerSpriteSet(ModParticles.COIN.get(), s -> new FxParticle.Provider(s, FxParticle.Style.COIN));
        event.registerSpriteSet(ModParticles.CONFETTI.get(), s -> new FxParticle.Provider(s, FxParticle.Style.CONFETTI));
        event.registerSpriteSet(ModParticles.DUST_MOTE.get(), s -> new FxParticle.Provider(s, FxParticle.Style.DUST));
    }
}
