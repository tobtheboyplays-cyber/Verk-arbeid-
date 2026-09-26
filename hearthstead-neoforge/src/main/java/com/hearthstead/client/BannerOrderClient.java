package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.BannerOrderActionPayload;
import com.hearthstead.network.BannerOrderMenuPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.UUID;

@EventBusSubscriber(modid=Hearthstead.MODID,value=Dist.CLIENT)
public final class BannerOrderClient {
    private static boolean armed=true;
    private static UUID pending;
    private static long requestedAt;
    private static BannerOrderMenuPayload visible;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(!mc.options.keyUse.isDown()) armed=true;
        if(pending!=null && System.nanoTime()-requestedAt>5_000_000_000L) pending=null;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {pending=null;visible=null;armed=true;}
    @SubscribeEvent public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        var mc=Minecraft.getInstance();
        if(!event.isUseItem() || mc.player==null || mc.screen!=null
            || !(mc.player.getMainHandItem().getItem() instanceof BannerItem)) return;
        // A banner aimed at the settlement Banner hangs new colours instead.
        if(targetsSettlementBanner(mc)) return;
        // Retain vanilla banner placement without adding another command key.
        if(mc.player.isShiftKeyDown()&&!(mc.hitResult instanceof EntityHitResult hit
            &&hit.getEntity() instanceof SettlerEntity))return;
        event.setCanceled(true);event.setSwingHand(false);
        if(!armed) return;
        armed=false;
        if(mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof SettlerEntity settler) {
            PacketDistributor.sendToServer(new BannerOrderActionPayload(BannerOrderActionPayload.ASSIGN,
                BannerOrderActionPayload.NONE,settler.getUUID()));
        } else {
            pending=UUID.randomUUID();requestedAt=System.nanoTime();
            PacketDistributor.sendToServer(BannerOrderActionPayload.open(pending));
        }
    }
    public static void accept(BannerOrderMenuPayload menu) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || !(mc.player.getMainHandItem().getItem() instanceof BannerItem item)
            || item.getColor().getId()!=menu.color()) return;
        boolean refresh=mc.screen instanceof com.hearthstead.client.screen.BannerOrderScreen screen
            && screen.token().equals(menu.token());
        if(!refresh && (pending==null || !pending.equals(menu.token()) || mc.screen!=null)) return;
        pending=null;
        visible=menu;
        mc.setScreen(new com.hearthstead.client.screen.BannerOrderScreen(menu));
    }
    @SubscribeEvent public static void marker(net.neoforged.neoforge.client.event.RenderLevelStageEvent event){
        if(event.getStage()!=net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES)return;
        var mc=Minecraft.getInstance();
        if(visible==null||!visible.ground()||!(mc.screen instanceof com.hearthstead.client.screen.BannerOrderScreen screen)
            ||!screen.token().equals(visible.token())||mc.level==null||!mc.level.hasChunkAt(visible.point()))return;
        var p=visible.point();var camera=event.getCamera().getPosition();
        int rgb=net.minecraft.world.item.DyeColor.byId(visible.color()).getTextColor();
        var box=new net.minecraft.world.phys.AABB(p.getX(),p.getY()+.03,p.getZ(),p.getX()+1,p.getY()+.08,p.getZ()+1)
            .move(-camera.x,-camera.y,-camera.z);
        var buffers=mc.renderBuffers().bufferSource();
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(event.getPoseStack(),
            buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines()),box,
            ((rgb>>16)&255)/255F,((rgb>>8)&255)/255F,(rgb&255)/255F,1);
        buffers.endBatch(net.minecraft.client.renderer.RenderType.lines());
    }
    private static boolean targetsSettlementBanner(Minecraft mc) {
        if(mc.level==null) return false;
        if(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
            && hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK
            && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof com.hearthstead.block.HearthBlock) return true;
        if(mc.hitResult instanceof EntityHitResult) return false;
        var cloth=com.hearthstead.block.SettlementBannerInteraction.clothUnderCrosshair(mc.level,mc.player);
        if(cloth==null) return false;
        return mc.hitResult==null || mc.hitResult.getType()==net.minecraft.world.phys.HitResult.Type.MISS
            || cloth.distance()<=mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition());
    }
    private BannerOrderClient(){}
}
