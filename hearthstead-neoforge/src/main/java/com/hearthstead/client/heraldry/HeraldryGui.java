package com.hearthstead.client.heraldry;

import com.hearthstead.heraldry.BannerShape;
import com.hearthstead.heraldry.VillageDesign;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Village heraldry on screens: the real banner textures and patterns, drawn
 * flat like the loom's preview, in the design's own cloth shape.
 */
public final class HeraldryGui {
    private HeraldryGui() {
    }

    @Nullable
    public static HolderGetter<BannerPattern> patterns() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? null : mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
    }

    /** The whole design, cloth top-left at (x, y), {@code width} wide and twice as tall. */
    public static void drawBanner(GuiGraphics g, VillageDesign design, int x, int y, int width) {
        drawCloth(g, design.shape(), design.base(), design.toPatternLayers(patterns()), x, y, width);
    }

    /** One pattern alone on {@code field} (the picker's swatches), plain cloth. */
    public static void drawPattern(GuiGraphics g, DyeColor field, @Nullable VillageDesign.Layer layer,
                                   BannerShape shape, int x, int y, int width) {
        BannerPatternLayers layers = BannerPatternLayers.EMPTY;
        HolderGetter<BannerPattern> lookup = patterns();
        if (layer != null && lookup != null) {
            BannerPatternLayers.Layer resolved = VillageDesign.resolve(lookup, layer);
            if (resolved != null) {
                layers = new BannerPatternLayers(List.of(resolved));
            }
        }
        drawCloth(g, shape, field, layers, x, y, width);
    }

    public static void drawCloth(GuiGraphics g, BannerShape shape, DyeColor base, BannerPatternLayers layers,
                                 int x, int y, int width) {
        float scale = width / 1.25F;
        PoseStack pose = g.pose();
        g.flush();
        Lighting.setupForFlatItems();
        pose.pushPose();
        pose.translate(x + width / 2F, y, 60F);
        pose.scale(scale, scale, -scale);
        // The flag faces south when drawn this way round; turn it to the viewer.
        ShapedCloth.render(pose, g.bufferSource(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
            shape, base, layers);
        pose.popPose();
        g.flush();
        Lighting.setupFor3DItems();
    }
}
