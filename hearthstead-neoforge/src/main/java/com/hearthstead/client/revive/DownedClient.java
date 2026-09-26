package com.hearthstead.client.revive;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.ReviveEventPayload;
import com.hearthstead.network.ReviveStatePayload;
import com.hearthstead.revive.ReviveRules;
import com.hearthstead.revive.ReviveService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client mirror of the server's downed players (display only; the server
 * decides everything). Drives: the crawl / kneel poses, the downed player's
 * desaturated screen and heartbeat, input suppression, drag steering, the
 * down-alert and revive sounds, and {@link #playerClip} for the motion
 * engine's player clips.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class DownedClient {
    public static final ResourceLocation DESATURATE = Hearthstead.id("shaders/post/downed.json");
    public static final ResourceLocation HEARTBEAT = Hearthstead.id("downed_heartbeat");
    public static final ResourceLocation REVIVED = Hearthstead.id("downed_revived");
    public static final ResourceLocation ALERT = Hearthstead.id("downed_alert");
    private static final long STALE_TICKS = 100L;
    private static final int GET_UP_TICKS = 24;

    /** One downed player as the client sees it, with local extrapolation. */
    public static final class View {
        public final ReviveStatePayload.Entry entry;
        /** Bleed ticks left, counted down locally between packets. */
        public int bleedLeft;
        /** Revive ticks done, counted up locally while a reviver holds. */
        public int reviveProgress;
        public final long firstSeen;

        View(ReviveStatePayload.Entry entry, long firstSeen) {
            this.entry = entry;
            this.bleedLeft = entry.bleedLeft();
            this.reviveProgress = entry.reviveProgress();
            this.firstSeen = firstSeen;
        }

        public boolean beingRevived() {
            return entry.reviverId() != ReviveStatePayload.NONE;
        }

        public float reviveFraction() {
            return Mth.clamp(reviveProgress / (float) entry.reviveRequired(), 0.0F, 1.0F);
        }

        public float bleedFraction() {
            return Mth.clamp(bleedLeft / (float) entry.bleedTotal(), 0.0F, 1.0F);
        }
    }

    /** A clip request for the motion engine's player driver. */
    public record PlayerClip(String constant, long startGameTime, boolean loop) {
    }

    private static final Map<Integer, View> VIEWS = new LinkedHashMap<>();
    private static final Map<Integer, Long> GET_UP = new HashMap<>();
    private static final Set<Integer> POSED_DOWNED = new HashSet<>();
    private static final Set<Integer> POSED_REVIVERS = new HashSet<>();
    @Nullable
    private static ClientLevel boundLevel;
    private static long lastPacketTick;
    private static int heartbeatIn;
    private static boolean effectOn;
    private static boolean effectFailed;

    private DownedClient() {
    }

    // ------------------------------------------------------------------ queries

    public static Collection<View> views() {
        return VIEWS.values();
    }

    @Nullable
    public static View viewOf(int entityId) {
        return VIEWS.get(entityId);
    }

    @Nullable
    public static View localDowned() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : VIEWS.get(player.getId());
    }

    /** The downed player the local player is currently reviving, if any. */
    @Nullable
    public static View localReviving() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        for (View v : VIEWS.values()) {
            if (v.entry.reviverId() == player.getId()) {
                return v;
            }
        }
        return null;
    }

    /** The downed player the local player is dragging, if any. */
    @Nullable
    public static View localDragging() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }
        for (View v : VIEWS.values()) {
            if (v.entry.draggerId() == player.getId()) {
                return v;
            }
        }
        return null;
    }

    /**
     * Motion-engine hook: which revive clip this player should show right
     * now, or null (vanilla / other clips). DOWNED_CRAWL while moving or
     * dragged, DOWNED_IDLE while still, REVIVE_KNEEL for a reviver, GET_UP
     * for {@value #GET_UP_TICKS} ticks after a revive.
     */
    @Nullable
    public static PlayerClip playerClip(Player player) {
        if (player == null || boundLevel == null || player.level() != boundLevel) {
            return null;
        }
        long now = boundLevel.getGameTime();
        View view = VIEWS.get(player.getId());
        if (view != null) {
            double speed = player.getDeltaMovement().horizontalDistanceSqr();
            boolean moving = speed > 1.0E-4D || view.entry.draggerId() != ReviveStatePayload.NONE;
            return new PlayerClip(moving ? "DOWNED_CRAWL" : "DOWNED_IDLE", view.firstSeen, true);
        }
        for (View v : VIEWS.values()) {
            if (v.entry.reviverId() == player.getId()) {
                return new PlayerClip("REVIVE_KNEEL", now, true);
            }
        }
        Long up = GET_UP.get(player.getId());
        if (up != null && now - up < GET_UP_TICKS) {
            return new PlayerClip("GET_UP", up, false);
        }
        return null;
    }

    /** Hooks the revive clips into the motion engine's shared player-pose hook. */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> com.hearthstead.client.motion.PlayerClips.register(p -> {
            PlayerClip clip = playerClip(p);
            return clip == null ? null
                : new com.hearthstead.client.motion.PlayerClips.Request(clip.constant(),
                    clip.startGameTime(), clip.loop());
        }));
    }

    // ------------------------------------------------------------------ packets

    public static void acceptState(ReviveStatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (boundLevel != mc.level) {
            clear();
            boundLevel = mc.level;
        }
        long now = mc.level.getGameTime();
        Map<Integer, View> next = new LinkedHashMap<>();
        for (ReviveStatePayload.Entry e : payload.entries()) {
            View old = VIEWS.get(e.entityId());
            next.put(e.entityId(), new View(e, old == null ? now : old.firstSeen));
        }
        VIEWS.clear();
        VIEWS.putAll(next);
        synchronized (ReviveService.CLIENT_DOWNED_IDS) {
            ReviveService.CLIENT_DOWNED_IDS.clear();
            ReviveService.CLIENT_DOWNED_IDS.addAll(VIEWS.keySet());
        }
        lastPacketTick = now;
        applyPoses(mc.level);
    }

    public static void acceptEvent(ReviveEventPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        boolean self = payload.entityId() == mc.player.getId();
        switch (payload.kind()) {
            case ReviveEventPayload.DOWNED -> {
                if (!self) {
                    play(ALERT, SoundEvents.BELL_BLOCK, 1.0F, 1.0F, null);
                }
                heartbeatIn = 0;
            }
            case ReviveEventPayload.REVIVED -> {
                GET_UP.put(payload.entityId(), mc.level.getGameTime());
                play(REVIVED, SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F,
                    new double[] {payload.x(), payload.y(), payload.z()});
            }
            default -> {
                // DIED: vanilla death screen / message covers it.
            }
        }
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (boundLevel != null) {
                clear();
            }
            return;
        }
        if (boundLevel != null && boundLevel != mc.level) {
            clear();
        }
        long now = mc.level.getGameTime();
        if (!VIEWS.isEmpty() && now - lastPacketTick > STALE_TICKS) {
            clear();
        }
        for (View v : VIEWS.values()) {
            if (v.beingRevived()) {
                v.reviveProgress = Math.min(v.entry.reviveRequired(), v.reviveProgress + 1);
            } else if (v.bleedLeft > 0) {
                v.bleedLeft--;
            }
        }
        GET_UP.values().removeIf(t -> now - t > GET_UP_TICKS * 2L);
        if (boundLevel != null) {
            applyPoses(boundLevel);
        }

        View self = VIEWS.get(mc.player.getId());
        if (self != null) {
            mc.player.setSprinting(false);
            if (--heartbeatIn <= 0) {
                heartbeatIn = ReviveRules.heartbeatInterval(self.bleedLeft, self.entry.bleedTotal());
                play(HEARTBEAT, SoundEvents.WARDEN_HEARTBEAT, 1.0F, 1.0F, null);
            }
        }
        if (localDragging() != null) {
            mc.player.setSprinting(false);
        }
        updateEffect(mc, self != null);
    }

    /** Forced poses: crawl for the downed, a crouch-kneel for revivers (vanilla fallback). */
    private static void applyPoses(ClientLevel level) {
        Set<Integer> downedNow = new HashSet<>(VIEWS.keySet());
        Set<Integer> reviversNow = new HashSet<>();
        for (View v : VIEWS.values()) {
            if (v.beingRevived()) {
                reviversNow.add(v.entry.reviverId());
            }
        }
        for (Integer id : POSED_DOWNED) {
            if (!downedNow.contains(id) && level.getEntity(id) instanceof Player p
                && p.getForcedPose() == Pose.SWIMMING) {
                p.setForcedPose(null);
            }
        }
        for (Integer id : POSED_REVIVERS) {
            if (!reviversNow.contains(id) && level.getEntity(id) instanceof Player p
                && p.getForcedPose() == Pose.CROUCHING) {
                p.setForcedPose(null);
            }
        }
        for (Integer id : downedNow) {
            if (level.getEntity(id) instanceof Player p && p.getForcedPose() != Pose.SWIMMING) {
                p.setForcedPose(Pose.SWIMMING);
            }
        }
        for (Integer id : reviversNow) {
            if (level.getEntity(id) instanceof Player p && p.getForcedPose() == null) {
                p.setForcedPose(Pose.CROUCHING);
            }
        }
        POSED_DOWNED.clear();
        POSED_DOWNED.addAll(downedNow);
        POSED_REVIVERS.clear();
        POSED_REVIVERS.addAll(reviversNow);
    }

    private static void updateEffect(Minecraft mc, boolean downed) {
        PostChain current = mc.gameRenderer.currentEffect();
        boolean ours = current != null && DESATURATE.toString().equals(current.getName());
        if (downed) {
            if (!ours && current == null && !effectFailed) {
                mc.gameRenderer.loadEffect(DESATURATE);
                PostChain loaded = mc.gameRenderer.currentEffect();
                effectFailed = loaded == null || !DESATURATE.toString().equals(loaded.getName());
            }
            effectOn = true;
        } else if (effectOn) {
            if (ours) {
                mc.gameRenderer.shutdownEffect();
            }
            effectOn = false;
            effectFailed = false;
        }
    }

    // ------------------------------------------------------------------ input

    /** A downed player can't attack, use or pick. */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && VIEWS.containsKey(mc.player.getId())) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /** No jumping or sneaking while down; being dragged steers toward the hauler. */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Player player = event.getEntity();
        View self = VIEWS.get(player.getId());
        if (self == null) {
            return;
        }
        Input input = event.getInput();
        input.jumping = false;
        input.shiftKeyDown = false;
        int draggerId = self.entry.draggerId();
        if (draggerId == ReviveStatePayload.NONE) {
            return;
        }
        Entity dragger = player.level().getEntity(draggerId);
        if (dragger == null) {
            return;
        }
        double dx = dragger.getX() - player.getX();
        double dz = dragger.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.4D) {
            return;
        }
        dx /= dist;
        dz /= dist;
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        float sin = Mth.sin(yaw);
        float cos = Mth.cos(yaw);
        float forward = (float) (dz * cos - dx * sin);
        float left = (float) (dx * cos + dz * sin);
        input.forwardImpulse = Mth.clamp(forward, -1.0F, 1.0F);
        input.leftImpulse = Mth.clamp(left, -1.0F, 1.0F);
    }

    // ------------------------------------------------------------------ sound

    private static void play(ResourceLocation id, Object fallback, float volume, float pitch,
                             @Nullable double[] at) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation location = id;
        if (mc.getSoundManager().getSoundEvent(id) == null) {
            if (fallback instanceof SoundEvent event) {
                location = event.getLocation();
            } else if (fallback instanceof net.minecraft.core.Holder<?> holder
                && holder.value() instanceof SoundEvent event) {
                location = event.getLocation();
            }
        }
        SoundInstance sound = at == null
            ? new SimpleSoundInstance(location, SoundSource.PLAYERS, volume, pitch,
                RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true)
            : new SimpleSoundInstance(location, SoundSource.PLAYERS, volume, pitch,
                RandomSource.create(), false, 0, SoundInstance.Attenuation.LINEAR, at[0], at[1], at[2], false);
        mc.getSoundManager().play(sound);
    }

    // ------------------------------------------------------------------ lifecycle

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        ClientLevel level = boundLevel;
        if (level != null) {
            VIEWS.clear();
            applyPoses(level);
        }
        VIEWS.clear();
        GET_UP.clear();
        POSED_DOWNED.clear();
        POSED_REVIVERS.clear();
        synchronized (ReviveService.CLIENT_DOWNED_IDS) {
            ReviveService.CLIENT_DOWNED_IDS.clear();
        }
        Minecraft mc = Minecraft.getInstance();
        if (effectOn && mc.gameRenderer != null) {
            PostChain current = mc.gameRenderer.currentEffect();
            if (current != null && DESATURATE.toString().equals(current.getName())) {
                mc.gameRenderer.shutdownEffect();
            }
        }
        effectOn = false;
        effectFailed = false;
        boundLevel = null;
    }
}
