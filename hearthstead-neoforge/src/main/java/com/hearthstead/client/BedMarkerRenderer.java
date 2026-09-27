package com.hearthstead.client;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BedMarkersPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * Bed status shown on the sheet itself: a tinted blanket laid just above each
 * bed's own blanket (yellow free, green taken, red not in a valid room). Purely
 * client-side; the bed block, its colour and its item are never changed.
 */
@EventBusSubscriber(modid=Hearthstead.MODID,value=Dist.CLIENT)
public final class BedMarkerRenderer {
    private static final ResourceLocation SHEET_TEXTURE = Hearthstead.id("textures/misc/bed_sheet.png");
    private static final int RANGE = 32;
    private static BedMarkersPayload snapshot;
    private static ClientLevel boundLevel;
    private static long receivedAt;
    private static RenderType sheetType;
    // Reused every frame: no per-frame allocation in the draw loop.
    private static final BlockPos.MutableBlockPos FOOT = new BlockPos.MutableBlockPos();
    private static final float[] RECT = new float[4];
    private static final Vector3f POS = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();

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
        // Right after vanilla draws beds: opaque, depth-tested, before translucent terrain.
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        var mc=Minecraft.getInstance();
        if(mc.level!=boundLevel || System.nanoTime()-receivedAt>3_000_000_000L) { clear(); return; }
        if(snapshot==null || mc.options.hideGui || mc.screen!=null || mc.player==null) return;
        if(sheetType==null) sheetType=RenderType.entityCutoutNoCull(SHEET_TEXTURE);
        Vec3 camera=event.getCamera().getPosition();
        PoseStack.Pose pose=event.getPoseStack().last();
        var buffers=mc.renderBuffers().bufferSource();
        VertexConsumer vc=null;
        var entries=snapshot.entries();
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i);
            BlockPos head=entry.head();
            if(head.distSqr(mc.player.blockPosition())>RANGE*RANGE || !mc.level.hasChunkAt(head)) continue;
            BlockState state=mc.level.getBlockState(head);
            if(!(state.getBlock() instanceof BedBlock) || state.getValue(BedBlock.PART)!=BedPart.HEAD) continue;
            Direction facing=state.getValue(BedBlock.FACING);
            int rgb=BedSheetGeometry.tint(entry.state());
            if(vc==null) vc=buffers.getBuffer(sheetType);
            half(vc,pose,camera,head,facing,true,rgb,LevelRenderer.getLightColor(mc.level,head));
            FOOT.setWithOffset(head,facing.getOpposite());
            if(!mc.level.hasChunkAt(FOOT)) continue;
            BlockState foot=mc.level.getBlockState(FOOT);
            if(foot.getBlock()==state.getBlock() && foot.getValue(BedBlock.PART)==BedPart.FOOT
                && foot.getValue(BedBlock.FACING)==facing)
                half(vc,pose,camera,FOOT,facing,false,rgb,LevelRenderer.getLightColor(mc.level,FOOT));
        }
        if(vc!=null) buffers.endBatch(sheetType);
    }

    private static void half(VertexConsumer vc, PoseStack.Pose pose, Vec3 camera, BlockPos pos,
                             Direction facing, boolean head, int rgb, int light) {
        float ox=(float)(pos.getX()-camera.x), oy=(float)(pos.getY()-camera.y), oz=(float)(pos.getZ()-camera.z);
        BedSheetGeometry.sheetRect(facing,head,RECT);
        float x0=RECT[0], z0=RECT[1], x1=RECT[2], z1=RECT[3];
        float y=BedSheetGeometry.SHEET_Y, yb=BedSheetGeometry.FOLD_BOTTOM;
        int r=(rgb>>16)&0xFF, g=(rgb>>8)&0xFF, b=rgb&0xFF;
        // Sheet top, weave mapped 1 texture per block.
        vertex(vc,pose,ox+x0,oy+y,oz+z0,r,g,b,uv(x0),uv(z0),light,0,1,0);
        vertex(vc,pose,ox+x0,oy+y,oz+z1,r,g,b,uv(x0),uv(z1),light,0,1,0);
        vertex(vc,pose,ox+x1,oy+y,oz+z1,r,g,b,uv(x1),uv(z1),light,0,1,0);
        vertex(vc,pose,ox+x1,oy+y,oz+z0,r,g,b,uv(x1),uv(z0),light,0,1,0);
        int fold=BedSheetGeometry.shade(rgb,BedSheetGeometry.FOLD_SHADE);
        int fr=(fold>>16)&0xFF, fg=(fold>>8)&0xFF, fb=fold&0xFF;
        float v0=0F, v1=y-yb;
        for(int i=0;i<BedSheetGeometry.foldCount(head);i++) {
            Direction side=BedSheetGeometry.foldSide(facing,head,i);
            int nx=side.getStepX(), nz=side.getStepZ();
            if(side.getAxis()==Direction.Axis.X) {
                float x=nx>0?x1:x0;
                vertex(vc,pose,ox+x,oy+y ,oz+z0,fr,fg,fb,uv(z0),v0,light,nx,0,0);
                vertex(vc,pose,ox+x,oy+yb,oz+z0,fr,fg,fb,uv(z0),v1,light,nx,0,0);
                vertex(vc,pose,ox+x,oy+yb,oz+z1,fr,fg,fb,uv(z1),v1,light,nx,0,0);
                vertex(vc,pose,ox+x,oy+y ,oz+z1,fr,fg,fb,uv(z1),v0,light,nx,0,0);
            } else {
                float z=nz>0?z1:z0;
                vertex(vc,pose,ox+x0,oy+y ,oz+z,fr,fg,fb,uv(x0),v0,light,0,0,nz);
                vertex(vc,pose,ox+x0,oy+yb,oz+z,fr,fg,fb,uv(x0),v1,light,0,0,nz);
                vertex(vc,pose,ox+x1,oy+yb,oz+z,fr,fg,fb,uv(x1),v1,light,0,0,nz);
                vertex(vc,pose,ox+x1,oy+y ,oz+z,fr,fg,fb,uv(x1),v0,light,0,0,nz);
            }
        }
    }

    private static float uv(float local) { return Math.max(0F,Math.min(1F,local)); }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                               int r, int g, int b, float u, float v, int light, float nx, float ny, float nz) {
        pose.pose().transformPosition(x,y,z,POS);
        pose.normal().transform(nx,ny,nz,NORMAL).normalize();
        vc.addVertex(POS.x(),POS.y(),POS.z()).setColor(r,g,b,255).setUv(u,v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(NORMAL.x(),NORMAL.y(),NORMAL.z());
    }
}
