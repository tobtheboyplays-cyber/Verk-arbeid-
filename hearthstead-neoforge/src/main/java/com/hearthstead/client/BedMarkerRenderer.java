package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BedMarkersPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** A small depth-tested outline above each bed pillow, independent of sleeping or textures. */
@EventBusSubscriber(modid=Hearthstead.MODID,value=Dist.CLIENT)
public final class BedMarkerRenderer {
    private static BedMarkersPayload snapshot;
    private static ClientLevel boundLevel;
    private static long receivedAt;
    public static void accept(BedMarkersPayload value) {
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().location().equals(value.dimension())) return;
        boundLevel=level; snapshot=value; receivedAt=System.nanoTime();
    }
    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
    private static void clear() { snapshot=null; boundLevel=null; }
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc=Minecraft.getInstance();
        if(mc.level!=boundLevel || System.nanoTime()-receivedAt>3_000_000_000L) { clear(); return; }
        if(snapshot==null || mc.options.hideGui || mc.screen!=null || mc.player==null) return;
        var camera=event.getCamera().getPosition();
        var buffers=mc.renderBuffers().bufferSource();
        for(var entry:snapshot.entries()) {
            var p=entry.head();
            if(p.distSqr(mc.player.blockPosition())>32*32 || !mc.level.hasChunkAt(p)) continue;
            var state=mc.level.getBlockState(p);
            if(!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART)!=BedPart.HEAD) continue;
            float r=entry.state()==BedMarkersPayload.ASSIGNED?.22F:.95F;
            float g=entry.state()==BedMarkersPayload.INVALID?.18F:.82F;
            float b=entry.state()==BedMarkersPayload.ASSIGNED?.35F:.12F;
            var box=new AABB(p.getX()+.32,p.getY()+.64,p.getZ()+.32,
                p.getX()+.68,p.getY()+.67,p.getZ()+.68).move(-camera.x,-camera.y,-camera.z);
            LevelRenderer.renderLineBox(event.getPoseStack(),buffers.getBuffer(RenderType.lines()),box,r,g,b,1);
        }
        buffers.endBatch(RenderType.lines());
    }
}
