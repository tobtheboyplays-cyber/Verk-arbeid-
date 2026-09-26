package com.hearthstead.client.captain;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.captain.CaptainConfig;
import com.hearthstead.entity.combat.captain.CaptainPayloads;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Client view of hero Captains: the synced state (for the panel and for other
 * lanes' cape/plume layers), and the render-only size. The hitbox is never
 * changed, so doors and two-high paths keep working.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class CaptainClient {
    private static final Map<Integer, CaptainPayloads.State> STATES = new HashMap<>();
    /** Drawn size; the server's [captain] renderScale is not synced, so the default is used. */
    public static final float SCALE = (float) CaptainConfig.DEFAULT_RENDER_SCALE;

    private CaptainClient() {
    }

    public static void state(CaptainPayloads.State payload) {
        STATES.put(payload.entityId(), payload);
        if (Minecraft.getInstance().screen instanceof CaptainScreen screen
            && screen.entityId() == payload.entityId()) {
            screen.accept(payload);
        }
    }

    @Nullable
    public static CaptainPayloads.State of(int entityId) {
        return STATES.get(entityId);
    }

    /** For the skins lane: is this settler the hero Captain (cape/plume/tabard)? */
    public static boolean isHero(SettlerEntity settler) {
        CaptainPayloads.State s = STATES.get(settler.getId());
        return s != null && s.hero();
    }

    /** Cape dye id (-1 = outfit default) for the skins lane. */
    public static int capeColour(SettlerEntity settler) {
        CaptainPayloads.State s = STATES.get(settler.getId());
        return s == null ? -1 : s.cape();
    }

    public static boolean plume(SettlerEntity settler) {
        CaptainPayloads.State s = STATES.get(settler.getId());
        return s == null || s.plume();
    }

    public static void open(SettlerEntity settler) {
        Minecraft.getInstance().setScreen(new CaptainScreen(settler));
        send(settler.getId(), CaptainPayloads.Action.REFRESH, 0, "");
    }

    public static void send(int entityId, int kind, int value, String text) {
        PacketDistributor.sendToServer(new CaptainPayloads.Action(entityId, kind, value, text));
    }

    // --------------------------------------------------------------- scale

    // LOWEST and never for a cancelled render: Post always balances this push.
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void pre(RenderLivingEvent.Pre<?, ?> event) {
        if (event.getEntity() instanceof SettlerEntity settler && isHero(settler)) {
            PoseStack pose = event.getPoseStack();
            pose.pushPose();
            pose.scale(SCALE, SCALE, SCALE);
        }
    }

    @SubscribeEvent
    public static void post(RenderLivingEvent.Post<?, ?> event) {
        if (event.getEntity() instanceof SettlerEntity settler && isHero(settler)) {
            event.getPoseStack().popPose();
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
    }
}
