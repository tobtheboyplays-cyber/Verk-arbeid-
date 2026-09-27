package com.hearthstead.client.look;

import com.hearthstead.Hearthstead;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Runtime character-texture compositor (client only).
 *
 * <p>Layers are alpha-composited (true src-over, unlike NativeImage#blendPixel,
 * which lowers the alpha of opaque pixels under a translucent layer) into one
 * DynamicTexture per unique look, so a settler costs one texture bind like
 * any other entity. Memory is bounded twice over: an LRU of composed
 * textures (released from the TextureManager on eviction) and an LRU of
 * decoded layer images. New compositions are rate-limited per 50 ms window
 * so 40 settlers walking into view cannot hitch one frame; a look that is
 * not composed yet returns null and the caller draws its fallback for that
 * frame.
 */
public final class LookTextureCache {
    private static final int MAX_TEXTURES = 320;
    private static final int MAX_LAYERS = 384;
    private static final int COMPOSE_BUDGET = 6;
    private static final long WINDOW_NS = 50_000_000L;

    private static final Map<String, ResourceLocation> TEXTURES = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ResourceLocation> eldest) {
            if (size() <= MAX_TEXTURES) {
                return false;
            }
            Minecraft.getInstance().getTextureManager().release(eldest.getValue());
            return true;
        }
    };
    private static final Map<ResourceLocation, NativeImage> LAYERS = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ResourceLocation, NativeImage> eldest) {
            if (size() <= MAX_LAYERS) {
                return false;
            }
            eldest.getValue().close();
            return true;
        }
    };
    private static final int MAX_FAILED = 256;
    /** Bounded negative cache: a broken look warns once, then stops retrying (until reload/logout). */
    private static final Set<String> FAILED = boundedSet(MAX_FAILED);
    private static long windowStart;
    private static int composedInWindow;
    private static int serial;

    private LookTextureCache() {
    }

    /** Composed texture for {@code key}, or null (not yet composed / failed). */
    public static ResourceLocation get(String key, int width, int height, List<ResourceLocation> layers) {
        ResourceLocation hit = TEXTURES.get(key);
        if (hit != null) {
            return hit;
        }
        if (FAILED.contains(key)) {
            return null;
        }
        long now = System.nanoTime();
        if (now - windowStart > WINDOW_NS) {
            windowStart = now;
            composedInWindow = 0;
        }
        if (composedInWindow >= COMPOSE_BUDGET) {
            return null;
        }
        composedInWindow++;
        try {
            ResourceLocation loc = compose(width, height, layers);
            TEXTURES.put(key, loc);
            return loc;
        } catch (Exception e) {
            if (FAILED.add(key)) {
                Hearthstead.LOGGER.warn("character look composition failed for {} ({}), using the fallback skin",
                    key, e.toString());
            }
            return null;
        }
    }

    private static ResourceLocation compose(int width, int height, List<ResourceLocation> layers) throws Exception {
        NativeImage out = new NativeImage(width, height, true);
        boolean registered = false;
        try {
            for (ResourceLocation loc : layers) {
                NativeImage layer = layer(loc);
                int w = Math.min(width, layer.getWidth());
                int h = Math.min(height, layer.getHeight());
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int src = layer.getPixelRGBA(x, y);
                        int sa = (src >>> 24) & 0xFF;
                        if (sa == 0) {
                            continue;
                        }
                        out.setPixelRGBA(x, y, sa == 0xFF ? src : over(src, out.getPixelRGBA(x, y)));
                    }
                }
            }
            ResourceLocation dest = Hearthstead.id("look/composed_" + (serial++));
            Minecraft.getInstance().getTextureManager().register(dest, new DynamicTexture(out));
            registered = true;
            return dest;
        } finally {
            if (!registered) {
                out.close();
            }
        }
    }

    /** Straight-alpha src-over on NativeImage's ABGR ints; channel order is irrelevant. */
    static int over(int src, int dst) {
        int sa = (src >>> 24) & 0xFF;
        int da = (dst >>> 24) & 0xFF;
        int oa = sa + da * (255 - sa) / 255;
        if (oa == 0) {
            return 0;
        }
        int r = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int sc = (src >>> shift) & 0xFF;
            int dc = (dst >>> shift) & 0xFF;
            int c = (sc * sa + dc * da * (255 - sa) / 255) / oa;
            r |= Math.min(255, c) << shift;
        }
        return r | (oa << 24);
    }

    private static NativeImage layer(ResourceLocation loc) throws Exception {
        NativeImage img = LAYERS.get(loc);
        if (img != null) {
            return img;
        }
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        try (InputStream in = resources.open(loc)) {
            img = NativeImage.read(in);
        }
        LAYERS.put(loc, img);
        return img;
    }

    /** Resource reload: every cached image came from the old packs. */
    public static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation loc : TEXTURES.values()) {
            textures.release(loc);
        }
        TEXTURES.clear();
        for (NativeImage img : LAYERS.values()) {
            img.close();
        }
        LAYERS.clear();
        FAILED.clear();
    }

    static Set<String> boundedSet(int max) {
        return java.util.Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(32, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > max;
            }
        });
    }

    /** Diagnostics for QA/tests. */
    public static int cachedTextures() {
        return TEXTURES.size();
    }
}
