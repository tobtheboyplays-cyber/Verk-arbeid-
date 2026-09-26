package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.InnkeeperAtmosphere;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Spatial, cancellable original humming. Late tracking never restarts half a phrase. */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class InnkeeperAmbienceClient {
    private static final Map<UUID, Hum> active = new HashMap<>();
    private static final Map<UUID, Long> observed = new HashMap<>();
    private static Object world;
    private InnkeeperAmbienceClient() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world) {
            active.values().forEach(Hum::cancel);
            active.clear(); observed.clear(); world = mc.level;
        }
        if (mc.level == null || mc.isPaused()) return;
        active.entrySet().removeIf(e -> e.getValue().isStopped());
        java.util.Set<UUID> loaded = new java.util.HashSet<>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof SettlerEntity host)) continue;
            loaded.add(host.getUUID());
            if (host.innkeeperSocialMode() != InnkeeperAtmosphere.HUM) continue;
            long started = host.innkeeperSocialStart();
            if (observed.getOrDefault(host.getUUID(), Long.MIN_VALUE) == started) continue;
            observed.put(host.getUUID(), started);
            long elapsed = mc.level.getGameTime() - started;
            if (elapsed < 0 || elapsed > 8 || mc.player == null
                || mc.player.distanceToSqr(host) > 24 * 24) continue;
            Hum previous = active.remove(host.getUUID());
            if (previous != null) previous.cancel();
            Hum sound = new Hum(host, started);
            active.put(host.getUUID(), sound);
            mc.getSoundManager().play(sound);
        }
        if (mc.level.getGameTime() % 100 == 0) observed.keySet().retainAll(loaded);
    }

    private static final class Hum extends AbstractTickableSoundInstance {
        private final SettlerEntity host;
        private final long started;
        private int age;
        Hum(SettlerEntity host, long started) {
            super(ModSounds.INNKEEPER_HUM.get(), SoundSource.NEUTRAL, RandomSource.create());
            this.host = host; this.started = started;
            volume = .45F; pitch = 1F; looping = false; delay = 0;
            x = host.getX(); y = host.getEyeY(); z = host.getZ();
        }
        void cancel() { stop(); }
        @Override public void tick() {
            if (!host.isAlive() || host.isRemoved() || ++age > InnkeeperAtmosphere.HUM_TICKS
                || host.innkeeperSocialMode() != InnkeeperAtmosphere.HUM
                || host.innkeeperSocialStart() != started
                || Minecraft.getInstance().level != host.level()) {
                stop(); return;
            }
            x = host.getX(); y = host.getEyeY(); z = host.getZ();
            int remaining = InnkeeperAtmosphere.HUM_TICKS - age;
            volume = .45F * Math.min(1F, Math.min(age / 5F, remaining / 6F));
        }
    }
}
