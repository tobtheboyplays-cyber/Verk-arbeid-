package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.SettlementBannerInteraction;
import com.hearthstead.block.SettlementHeraldry;
import com.hearthstead.network.BannerOpenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * QA U9: the whole Banner opens the Banner screen. The stand is the only
 * real block; the pole and flag are drawn above it, so a right-click there
 * used to hit air (or whatever stood behind). When the use key is pressed and
 * the Banner's cloth is the nearest thing under the crosshair, ask the server
 * to open it (it re-validates). A vanilla banner in hand keeps its own
 * "hang new colours" path.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class BannerClothClick {
    private BannerClothClick() {
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || mc.player == null || mc.level == null || mc.screen != null
            || mc.player.isSpectator()
            || SettlementHeraldry.isBanner(mc.player.getItemInHand(event.getHand()))) {
            return;
        }
        var hit = SettlementBannerInteraction.clothUnderCrosshair(mc.level, mc.player);
        if (hit == null) {
            return;
        }
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS) {
            double vanillaDistance = vanilla.getLocation().distanceTo(mc.player.getEyePosition());
            if (vanillaDistance < hit.distance()) {
                return; // something nearer (the stand itself, a settler, a wall) is the real target
            }
        }
        PacketDistributor.sendToServer(new BannerOpenPayload(hit.banner().getBlockPos()));
        event.setSwingHand(true);
        event.setCanceled(true);
    }
}
