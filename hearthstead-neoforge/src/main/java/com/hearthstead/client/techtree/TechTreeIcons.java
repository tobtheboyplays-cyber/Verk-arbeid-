package com.hearthstead.client.techtree;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom tech tree node icons by convention (icon lane):
 * {@code assets/hearthstead/textures/gui/techtree/node/<node_id>.png},
 * 32x32 RGBA, drawn inside the screen's medallion. Existence is looked up
 * once per id and cached; the cache is cleared on resource reload.
 */
public final class TechTreeIcons {
    private static final Map<String, Optional<ResourceLocation>> CACHE = new ConcurrentHashMap<>();

    private TechTreeIcons() {
    }

    @Nullable
    public static ResourceLocation custom(String nodeId) {
        return CACHE.computeIfAbsent(nodeId, id -> {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("hearthstead",
                "textures/gui/techtree/node/" + id + ".png");
            try {
                return Minecraft.getInstance().getResourceManager().getResource(rl).isPresent()
                    ? Optional.of(rl) : Optional.empty();
            } catch (RuntimeException notReady) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Draws the 32x32 icon scaled to {@code size} at a (float) top-left corner. */
    public static void draw(GuiGraphics g, ResourceLocation rl, float x, float y, float size) {
        draw(g, rl, x, y, size, 1.0F);
    }

    /**
     * Draws the icon with a brightness tint (1 = as painted; below 1 darkens,
     * e.g. 0.55 for locked or planned nodes). Style C icons are the
     * medallion face: the screen draws only the state ring around them.
     */
    public static void draw(GuiGraphics g, ResourceLocation rl, float x, float y, float size, float tint) {
        if (tint < 0.999F) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(tint, tint, tint * 0.95F, 1.0F);
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(size / 32.0F, size / 32.0F, 1.0F);
        g.blit(rl, 0, 0, 0.0F, 0.0F, 32, 32, 32, 32);
        g.pose().popPose();
        if (tint < 0.999F) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    public static void clear() {
        CACHE.clear();
    }
}
