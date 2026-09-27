package com.hearthstead.client.patrol;

import com.hearthstead.Hearthstead;
import com.hearthstead.item.PatrolMapItem;
import com.hearthstead.network.PatrolSnapshotPayload;
import com.hearthstead.settlement.guard.patrol.PatrolPalette;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Client holder of the last patrol-route projection, the in-world route
 * markers while the Patrol Map is held (numbered posts joined by a line,
 * the selected route bright, the others faint), and the route screen's
 * opener. Display only: every value came from the server.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class PatrolClient {
    private static final double VIEW_DISTANCE_SQ = 160.0D * 160.0D;
    @Nullable private static PatrolSnapshotPayload current;
    private static int version;

    private PatrolClient() {
    }

    public static void accept(PatrolSnapshotPayload payload) {
        if (payload == null) return;
        current = payload;
        version++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PatrolRouteScreen screen && screen.settlementId().equals(payload.settlementId())) {
            screen.update(payload);
        } else if (payload.open() && mc.player != null) {
            mc.setScreen(new PatrolRouteScreen(payload));
        }
    }

    @Nullable
    public static PatrolSnapshotPayload snapshot() {
        return current;
    }

    /** Bumped on every receipt; UI caches key on it. */
    public static int version() {
        return version;
    }

    /** The last projection if it belongs to this settlement, else null. */
    @Nullable
    public static PatrolSnapshotPayload forSettlement(UUID settlementId) {
        PatrolSnapshotPayload s = current;
        return s != null && settlementId != null && settlementId.equals(s.settlementId()) ? s : null;
    }

    /** The route this settler walks right now, per the last projection, or null. */
    @Nullable
    public static PatrolSnapshotPayload.Route walking(UUID settlementId, UUID settler) {
        PatrolSnapshotPayload s = forSettlement(settlementId);
        if (s == null || settler == null) return null;
        for (PatrolSnapshotPayload.Route route : s.routes()) {
            if (route.squad().contains(settler)) return route;
        }
        return null;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        current = null;
        version++;
    }

    private static boolean holdingMap(Minecraft mc) {
        return mc.player != null && (mc.player.getMainHandItem().getItem() instanceof PatrolMapItem
            || mc.player.getOffhandItem().getItem() instanceof PatrolMapItem);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        PatrolSnapshotPayload s = current;
        if (s == null || mc.level == null || !holdingMap(mc)
            || !mc.level.dimension().location().toString().equals(s.dimension())) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Vec3 cam = event.getCamera().getPosition();
        // Lines first (one batch), then marker posts, then the numbers.
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (PatrolSnapshotPayload.Route route : s.routes()) {
            boolean selected = route.id() == s.selectedRoute();
            float alpha = selected ? 1.0F : 0.45F;
            List<BlockPos> pts = route.waypoints();
            int legs = route.loop() ? pts.size() : pts.size() - 1;
            for (int i = 0; i < legs; i++) {
                BlockPos a = pts.get(i);
                BlockPos b = pts.get((i + 1) % pts.size());
                segment(pose, lines, cam, a, b, route.color(), alpha);
            }
        }
        buffers.endBatch(RenderType.lines());
        for (PatrolSnapshotPayload.Route route : s.routes()) {
            boolean selected = route.id() == s.selectedRoute();
            List<BlockPos> pts = route.waypoints();
            for (BlockPos p : pts) {
                if (p.distToCenterSqr(cam) > VIEW_DISTANCE_SQ) continue;
                AABB post = new AABB(p.getX() + 0.42D, p.getY(), p.getZ() + 0.42D,
                    p.getX() + 0.58D, p.getY() + (selected ? 1.6D : 1.1D), p.getZ() + 0.58D)
                    .move(-cam.x, -cam.y, -cam.z);
                DebugRenderer.renderFilledBox(pose, buffers, post, PatrolPalette.red(route.color()),
                    PatrolPalette.green(route.color()), PatrolPalette.blue(route.color()), selected ? 0.85F : 0.4F);
            }
        }
        buffers.endBatch(RenderType.debugFilledBox());
        for (PatrolSnapshotPayload.Route route : s.routes()) {
            boolean selected = route.id() == s.selectedRoute();
            List<BlockPos> pts = route.waypoints();
            for (int i = 0; i < pts.size(); i++) {
                BlockPos p = pts.get(i);
                if (p.distToCenterSqr(cam) > VIEW_DISTANCE_SQ) continue;
                String label = selected && i == 0 && pts.size() >= 3 && !route.loop()
                    ? "1 (click to close loop)" : String.valueOf(i + 1);
                DebugRenderer.renderFloatingText(pose, buffers, label, p.getX() + 0.5D,
                    p.getY() + (selected ? 2.0D : 1.4D), p.getZ() + 0.5D,
                    selected ? 0xFFFFFFFF : 0xFFBFBFBF, selected ? 0.03F : 0.022F);
            }
            if (selected && !pts.isEmpty()) {
                BlockPos p = pts.get(0);
                if (p.distToCenterSqr(cam) <= VIEW_DISTANCE_SQ) {
                    DebugRenderer.renderFloatingText(pose, buffers, route.name(), p.getX() + 0.5D, p.getY() + 2.45D,
                        p.getZ() + 0.5D, PatrolPalette.ink(route.color()) | 0xFF000000, 0.028F);
                }
            }
        }
        buffers.endBatch();
    }

    private static void segment(PoseStack pose, VertexConsumer lines, Vec3 cam, BlockPos a, BlockPos b, int color,
                                float alpha) {
        float ax = (float) (a.getX() + 0.5D - cam.x);
        float ay = (float) (a.getY() + 0.15D - cam.y);
        float az = (float) (a.getZ() + 0.5D - cam.z);
        float bx = (float) (b.getX() + 0.5D - cam.x);
        float by = (float) (b.getY() + 0.15D - cam.y);
        float bz = (float) (b.getZ() + 0.5D - cam.z);
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-3F) return;
        nx /= len;
        ny /= len;
        nz /= len;
        float r = PatrolPalette.red(color);
        float g = PatrolPalette.green(color);
        float bl = PatrolPalette.blue(color);
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, ax, ay, az).setColor(r, g, bl, alpha).setNormal(last, nx, ny, nz);
        lines.addVertex(last, bx, by, bz).setColor(r, g, bl, alpha).setNormal(last, nx, ny, nz);
    }
}
