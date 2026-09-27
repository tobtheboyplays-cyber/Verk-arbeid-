package com.hearthstead.client.builder;

import com.hearthstead.network.BuilderPayloads;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 3D thumbnails for the style picker: the same outer-shell drawing as
 * {@link BlueprintThumbnail}, but with one cached shell per preview so five
 * cards can be drawn every frame without re-deriving their shells.
 */
final class PlanThumbs {

    private record Cell(int x, int y, int z, BlockState state) {
    }

    private static final Map<BuilderPayloads.Preview, List<Cell>> SHELLS = new IdentityHashMap<>();

    private PlanThumbs() {
    }

    static void clear() {
        SHELLS.clear();
        ANGLES.clear();
    }

    private static List<Cell> shell(BuilderPayloads.Preview preview) {
        List<Cell> cached = SHELLS.get(preview);
        if (cached != null) {
            return cached;
        }
        if (SHELLS.size() > 16) {
            SHELLS.clear();
        }
        int[] packed = preview.cells();
        Set<Long> solid = new HashSet<>();
        List<Cell> all = new ArrayList<>(packed.length / 2);
        for (int i = 0; i + 1 < packed.length; i += 2) {
            int p = packed[i];
            int idx = packed[i + 1];
            if (idx < 0 || idx >= preview.palette().size()) {
                continue;
            }
            BlockState state = preview.palette().get(idx);
            Cell c = new Cell(p & 0xFF, (p >> 8) & 0xFF, (p >> 16) & 0xFF, state);
            all.add(c);
            if (state.canOcclude()) {
                solid.add(key(c.x, c.y, c.z));
            }
        }
        List<Cell> outer = new ArrayList<>();
        for (Cell c : all) {
            if (c.state.getRenderShape() == RenderShape.INVISIBLE) {
                continue;
            }
            if (!solid.contains(key(c.x + 1, c.y, c.z)) || !solid.contains(key(c.x - 1, c.y, c.z))
                || !solid.contains(key(c.x, c.y + 1, c.z)) || !solid.contains(key(c.x, c.y - 1, c.z))
                || !solid.contains(key(c.x, c.y, c.z + 1)) || !solid.contains(key(c.x, c.y, c.z - 1))) {
                outer.add(c);
            }
        }
        SHELLS.put(preview, outer);
        return outer;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFF) | (((long) y & 0xFFFF) << 16) | (((long) z & 0xFFFF) << 32);
    }

    /** One full turn every 12 s; the hovered card turns a little faster (one turn per 8 s). */
    static final long TURN_MS = 12000L;
    static final long HOVER_TURN_MS = 8000L;
    private static final Map<BuilderPayloads.Preview, Spin> ANGLES = new IdentityHashMap<>();

    private static final class Spin {
        float angle;
        long last;
    }

    /**
     * The preview's current turn in degrees. Every card turns all the time; the angle is
     * accumulated per preview so a card that becomes hovered speeds up smoothly instead of jumping.
     */
    static float advance(BuilderPayloads.Preview preview, long now, boolean fast) {
        Spin st = ANGLES.get(preview);
        if (st == null) {
            if (ANGLES.size() > 16) {
                ANGLES.clear();
            }
            st = new Spin();
            st.angle = (now % TURN_MS) / (float) TURN_MS * 360.0F;
            st.last = now;
            ANGLES.put(preview, st);
        }
        long dt = Math.max(0L, Math.min(250L, now - st.last));
        st.angle = (st.angle + dt * 360.0F / (fast ? HOVER_TURN_MS : TURN_MS)) % 360.0F;
        st.last = now;
        return st.angle;
    }

    /** Draws the preview into the w x h box, turning; {@code hovered} turns it a bit faster. */
    static void render(GuiGraphics g, BuilderPayloads.Preview preview, int x, int y, int w, int h, boolean hovered) {
        List<Cell> cells = shell(preview);
        if (cells.isEmpty()) {
            return;
        }
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        float extent = Math.max(preview.sizeX(), Math.max(preview.sizeY(), preview.sizeZ()));
        float scale = Math.min(w, h) / (extent * 1.6F);
        float turn = advance(preview, Util.getMillis(), hovered);
        g.enableScissor(x, y, x + w, y + h);
        PoseStack pose = g.pose();
        pose.pushPose();
        // z 150: the turning model stays below tooltips (drawn at z 400).
        pose.translate(x + w / 2.0F, y + h / 2.0F, 150.0F);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(28.0F));
        pose.mulPose(Axis.YP.rotationDegrees(45.0F + turn));
        pose.translate(-preview.sizeX() / 2.0F, -preview.sizeY() / 2.0F, -preview.sizeZ() / 2.0F);
        Lighting.setupFor3DItems();
        var buffers = g.bufferSource();
        for (Cell c : cells) {
            pose.pushPose();
            pose.translate(c.x, c.y, c.z);
            dispatcher.renderSingleBlock(c.state, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        g.flush();
        pose.popPose();
        Lighting.setupForFlatItems();
        g.disableScissor();
    }
}
