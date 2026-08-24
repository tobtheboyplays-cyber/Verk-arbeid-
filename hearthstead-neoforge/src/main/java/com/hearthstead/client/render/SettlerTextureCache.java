package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerAppearance;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composites a settler's five appearance layers (base/hair/face/clothing/
 * outfit) into one texture at runtime and registers it with the texture
 * manager, so each unique (appearance, profession) pair costs one draw call
 * like any other entity, not one per layer. Client-only (INV-6): never
 * referenced from server code.
 */
public final class SettlerTextureCache {
    private static final int MAX_CACHE = 256;
    private static final String[] SKIN_KEYS = {"skin", "skin_tan", "skin_deep", "skin_pale"};
    private static final String[] HAIR_COLOR_KEYS =
        {"hair_brn", "hair_blnd", "hair_blk", "hair_red"};

    private record CacheKey(SettlerAppearance appearance, Profession profession) {
    }

    /** Every failing key gets exactly one WARN so a real bug is visible in
     *  the log instead of silently and permanently falling back -- but a
     *  broken pack (missing layer file) can't spam once per settler. */
    private static final java.util.Set<CacheKey> WARNED_KEYS = new java.util.HashSet<>();
    /** Logged once per game session so "the composited path never actually
     *  ran" is as visible as "it ran and failed" -- silence alone can't
     *  tell the two apart. */
    private static boolean loggedFirstSuccess = false;

    private static final Map<CacheKey, ResourceLocation> CACHE =
        new LinkedHashMap<>(32, 0.75F, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, ResourceLocation> eldest) {
                if (size() <= MAX_CACHE) {
                    return false;
                }
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                return true;
            }
        };

    /** Returns a composed texture location, or null on any failure -- the
     *  caller must fall back to a static per-profession texture; this must
     *  never throw into the render loop. */
    public static ResourceLocation getOrCreate(SettlerEntity entity) {
        SettlerAppearance appearance = entity.getAppearance();
        Profession profession = entity.getProfession();
        CacheKey key = new CacheKey(appearance, profession);
        ResourceLocation cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            ResourceLocation composed = compose(appearance, profession);
            CACHE.put(key, composed);
            if (!loggedFirstSuccess) {
                loggedFirstSuccess = true;
                Hearthstead.LOGGER.info("first composed settler texture registered: {}", composed);
            }
            return composed;
        } catch (Exception e) {
            if (WARNED_KEYS.add(key)) {
                Hearthstead.LOGGER.warn(
                    "settler texture composition failed for {} + {}, falling back to the "
                        + "legacy per-profession texture: {}", appearance, profession, e.toString());
            }
            return null;
        }
    }

    /** Called on resource-pack reload: the cached DynamicTextures reference
     *  layer bytes read from the old pack, so they must all be dropped. */
    public static void clear() {
        var textureManager = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation loc : CACHE.values()) {
            textureManager.release(loc);
        }
        CACHE.clear();
    }

    private static ResourceLocation compose(SettlerAppearance a, Profession profession)
        throws IOException {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        List<ResourceLocation> layers = List.of(
            layerLoc("base_" + SKIN_KEYS[a.skinTone()] + ".png"),
            layerLoc("hair_" + a.hairStyle() + "_" + HAIR_COLOR_KEYS[a.hairColor()] + ".png"),
            layerLoc("face_" + a.faceVariant() + ".png"),
            layerLoc("clothing_" + a.clothingVariant() + ".png"),
            layerLoc("outfit_" + profession.key() + ".png"));

        NativeImage composed = new NativeImage(128, 64, true);
        boolean registered = false;
        try {
            for (ResourceLocation loc : layers) {
                try (InputStream in = resources.open(loc)) {
                    NativeImage layer = NativeImage.read(in);
                    try {
                        for (int y = 0; y < layer.getHeight(); y++) {
                            for (int x = 0; x < layer.getWidth(); x++) {
                                composed.blendPixel(x, y, layer.getPixelRGBA(x, y));
                            }
                        }
                    } finally {
                        layer.close();
                    }
                }
            }
            // Built from the appearance/profession fields directly (not a
            // hashCode) so two different combinations can never collide and
            // silently overwrite each other's registered texture.
            ResourceLocation dest = Hearthstead.id("settler/composed_"
                + a.skinTone() + "_" + a.hairStyle() + "_" + a.hairColor() + "_"
                + a.faceVariant() + "_" + a.clothingVariant() + "_" + profession.key());
            Minecraft.getInstance().getTextureManager().register(dest, new DynamicTexture(composed));
            registered = true;
            return dest;
        } finally {
            if (!registered) {
                composed.close();
            }
        }
    }

    private static ResourceLocation layerLoc(String fileName) {
        return Hearthstead.id("textures/entity/settler/layers/" + fileName);
    }

    private SettlerTextureCache() {
    }
}
