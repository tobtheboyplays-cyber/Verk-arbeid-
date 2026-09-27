package com.hearthstead.client.heraldry;

import com.hearthstead.heraldry.BannerShape;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a banner cloth in any {@link BannerShape}, layer for layer the way
 * vanilla's {@code BannerRenderer.renderPatterns} draws the flag box: the
 * cloth texture (solid), the base in the field colour, then each pattern
 * layer tinted with its dye. Coordinates and UVs are vanilla's flag cube
 * (texOffs 0,0; 20 x 40 x 1 pixels at z -2..-1; 64 x 64 texture), so every
 * pattern lands on exactly the pixels it would on a plain banner and the
 * shape only trims the silhouette. Call with the pose where the flag part's
 * own transform is already applied.
 */
public final class ShapedCloth {
    private static final float TEX = 64F;
    private static final float Z_FRONT = -2F;
    private static final float Z_BACK = -1F;

    private ShapedCloth() {
    }

    public static void render(PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                              BannerShape shape, DyeColor base, BannerPatternLayers patterns) {
        PoseStack.Pose last = pose.last();
        draw(last, ModelBakery.BANNER_BASE.buffer(buffers, RenderType::entitySolid), light, overlay, shape, -1);
        layer(last, buffers, light, overlay, shape, Sheets.BANNER_BASE, base);
        for (int i = 0; i < 16 && i < patterns.layers().size(); i++) {
            BannerPatternLayers.Layer layer = patterns.layers().get(i);
            layer(last, buffers, light, overlay, shape, Sheets.getBannerMaterial(layer.pattern()), layer.color());
        }
    }

    private static void layer(PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay,
                              BannerShape shape, Material material, DyeColor color) {
        draw(pose, material.buffer(buffers, RenderType::entityNoOutline), light, overlay, shape,
            color.getTextureDiffuseColor());
    }

    private static void draw(PoseStack.Pose pose, VertexConsumer out, int light, int overlay, BannerShape shape,
                             int color) {
        Matrix4f matrix = pose.pose();
        Vector3f scratch = new Vector3f();
        Vector3f front = pose.transformNormal(0F, 0F, -1F, new Vector3f());
        Vector3f back = pose.transformNormal(0F, 0F, 1F, new Vector3f());
        for (float[] q : shape.quads()) {
            // Front (north) face: vanilla order TR, TL, BL, BR; u = 1 + (x + 10).
            for (int i = 0; i < 4; i++) {
                float x = q[i * 2];
                float y = q[i * 2 + 1];
                vertex(out, matrix, scratch, x, y, Z_FRONT, color, (11F + x) / TEX, (1F + y) / TEX,
                    overlay, light, front);
            }
            // Back (south) face: reversed winding; vanilla mirrors it at u = 32 - x.
            for (int i = 3; i >= 0; i--) {
                float x = q[i * 2];
                float y = q[i * 2 + 1];
                vertex(out, matrix, scratch, x, y, Z_BACK, color, (32F - x) / TEX, (1F + y) / TEX,
                    overlay, light, back);
            }
        }
        // The cloth's thin edge along the silhouette, both windings so no
        // cull setting can hide it; sampled from the front's edge pixels.
        float[] o = shape.outline();
        int n = o.length / 2;
        for (int i = 0; i < n; i++) {
            float ax = o[i * 2];
            float ay = o[i * 2 + 1];
            float bx = o[((i + 1) % n) * 2];
            float by = o[((i + 1) % n) * 2 + 1];
            float nx = by - ay;
            float ny = ax - bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny);
            if (len < 1.0E-4F) {
                continue;
            }
            Vector3f normal = pose.transformNormal(nx / len, ny / len, 0F, new Vector3f());
            float au = (11F + inset(ax, bx)) / TEX;
            float av = (1F + inset(ay, by)) / TEX;
            float bu = (11F + inset(bx, ax)) / TEX;
            float bv = (1F + inset(by, ay)) / TEX;
            vertex(out, matrix, scratch, ax, ay, Z_FRONT, color, au, av, overlay, light, normal);
            vertex(out, matrix, scratch, bx, by, Z_FRONT, color, bu, bv, overlay, light, normal);
            vertex(out, matrix, scratch, bx, by, Z_BACK, color, bu, bv, overlay, light, normal);
            vertex(out, matrix, scratch, ax, ay, Z_BACK, color, au, av, overlay, light, normal);
            vertex(out, matrix, scratch, ax, ay, Z_BACK, color, au, av, overlay, light, normal);
            vertex(out, matrix, scratch, bx, by, Z_BACK, color, bu, bv, overlay, light, normal);
            vertex(out, matrix, scratch, bx, by, Z_FRONT, color, bu, bv, overlay, light, normal);
            vertex(out, matrix, scratch, ax, ay, Z_FRONT, color, au, av, overlay, light, normal);
        }
    }

    /** Nudges an edge sample a hair toward the other end so it stays on the cloth's own texel. */
    private static float inset(float a, float b) {
        return a + Math.signum(b - a) * 0.01F;
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, Vector3f scratch, float px, float py, float pz,
                               int color, float u, float v, int overlay, int light, Vector3f normal) {
        Vector3f p = matrix.transformPosition(px / 16F, py / 16F, pz / 16F, scratch);
        out.addVertex(p.x(), p.y(), p.z(), color, u, v, overlay, light, normal.x(), normal.y(), normal.z());
    }
}
