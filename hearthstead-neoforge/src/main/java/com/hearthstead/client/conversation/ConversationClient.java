package com.hearthstead.client.conversation;

import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.conversation.net.ConvBarterPayload;
import com.hearthstead.conversation.net.ConvClosePayload;
import com.hearthstead.conversation.net.ConvMarkersPayload;
import com.hearthstead.conversation.net.ConvStatePayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client side of conversations: keeps the latest server state, opens the
 * talk bar / barter table and drives the camera. Never decides anything;
 * every click goes back to the server as a {@link ConvActionPayload}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.hearthstead.Hearthstead.MODID,
    value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class ConversationClient {
    @Nullable private static ConvStatePayload state;
    private static int session = -1;
    private static int npcId = -1;
    private static boolean cinematic;
    /** NPCs met this session (by UUID): their name card is not shown again. */
    private static final java.util.Set<java.util.UUID> MET = new java.util.HashSet<>();
    private static final Int2ObjectOpenHashMap<ConvMarkersPayload.Marker> MARKERS = new Int2ObjectOpenHashMap<>();
    private static long markersAt;

    private ConversationClient() {
    }

    /** A dropped connection cannot rely on receiving the server's close packet. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        state = null;
        session = -1;
        npcId = -1;
        cinematic = false;
        MET.clear();
        MARKERS.clear();
        markersAt = 0L;
        ConversationCamera.reset();
    }

    public static void state(ConvStatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        boolean fresh = payload.session() != session;
        boolean welcome = fresh && foundingWelcome(payload);
        state = payload;
        session = payload.session();
        npcId = payload.npcId();
        if (fresh) {
            // Owner: the big card only on the first meeting this session; repeats get a quick 0.4 s ease.
            net.minecraft.world.entity.Entity npc = mc.level == null ? null : mc.level.getEntity(payload.npcId());
            boolean firstMeeting = npc == null || MET.add(npc.getUUID());
            // The founding welcome always gets its intro: the camera turns to the Guildmaster first.
            cinematic = (payload.mode() == 1 && firstMeeting) || welcome;
            ConversationCamera.begin(payload.npcId(), cinematic, payload.style() == 1);
            com.hearthstead.client.sound.HsSound.ui("ui.conversation_open", net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN,
                0.5F, 1.0F);
        }
        if (mc.screen instanceof ConversationScreen screen && screen.session() == payload.session()) {
            screen.update(payload);
        } else {
            mc.setScreen(new ConversationScreen(payload, fresh && cinematic, welcome));
        }
    }

    /**
     * The Guildmaster's welcome after Raise banner (settlement/guildmaster/GuildmasterWelcome):
     * recognised by its speaker title, so no payload changes are needed.
     */
    static boolean foundingWelcome(ConvStatePayload payload) {
        return payload.title().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
            && WELCOME_TITLE.equals(t.getKey());
    }

    static final String WELCOME_TITLE = "conversation.hearthstead.gm_welcome.title";

    public static void barter(ConvBarterPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (payload.session() != session) return;
        if (mc.screen instanceof BarterScreen screen && screen.session() == payload.session()) {
            screen.update(payload);
        } else {
            mc.setScreen(new BarterScreen(payload));
        }
    }

    public static void close(ConvClosePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (payload.session() != session && session != -1) return;
        endLocal();
        if (mc.screen instanceof ConversationScreen || mc.screen instanceof BarterScreen) mc.setScreen(null);
        if (!payload.notice().getString().isEmpty() && mc.player != null) {
            mc.player.displayClientMessage(payload.notice(), true);
        }
    }

    /** The screen was closed locally (Esc): tell the server and ease the camera home. */
    public static void leave(int closingSession) {
        if (closingSession != session) return;
        PacketDistributor.sendToServer(ConvActionPayload.simple(closingSession, ConvActionPayload.LEAVE));
        endLocal();
    }

    private static void endLocal() {
        ConversationCamera.end();
        state = null;
        session = -1;
        npcId = -1;
    }

    public static void choose(int sessionId, int revision, int index, String optionId) {
        PacketDistributor.sendToServer(ConvActionPayload.choose(sessionId, revision, index, optionId));
    }

    public static void send(ConvActionPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** Back from the barter table to the talk. */
    public static void barterBack(int sessionId) {
        // The server answers with a fresh talk state, which reopens the bar (no stale intro card).
        PacketDistributor.sendToServer(ConvActionPayload.simple(sessionId, ConvActionPayload.BARTER_BACK));
    }

    public static void markers(ConvMarkersPayload payload) {
        MARKERS.clear();
        for (ConvMarkersPayload.Marker marker : payload.markers()) MARKERS.put(marker.entityId(), marker);
        Minecraft mc = Minecraft.getInstance();
        markersAt = mc.level == null ? 0 : mc.level.getGameTime();
    }

    @Nullable
    static ConvMarkersPayload.Marker marker(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getGameTime() - markersAt > 60) return null;
        return MARKERS.get(entityId);
    }

    static Int2ObjectOpenHashMap<ConvMarkersPayload.Marker> markers() {
        return MARKERS;
    }

    public static boolean active() {
        return session != -1;
    }

    public static int npcId() {
        return npcId;
    }

    @Nullable
    public static ConvStatePayload current() {
        return state;
    }

    static Component empty() {
        return Component.empty();
    }
}
