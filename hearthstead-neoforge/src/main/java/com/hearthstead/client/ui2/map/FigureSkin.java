package com.hearthstead.client.ui2.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Optional;

/**
 * The outfit colours of one settler skin, boiled down to a few cells per
 * body part so the tiny map figures wear the settler's real clothes.
 *
 * <p>Each part is a region of the 128x64 settler texture (the same UV
 * layout as {@code SettlerModel}) averaged into a small grid of cells:
 * the torso front into 3x4, a leg or arm into 1x3 (thigh or upper arm,
 * middle, boot or hand). Sampling happens once per texture and is cached;
 * drawing only reads the cached ints. A transparent cell is 0 and the
 * figure falls back to its trade colour there.
 */
public final class FigureSkin {
    public static final int TORSO_FRONT = 0;
    public static final int TORSO_BACK = 1;
    public static final int TORSO_SIDE = 2;
    public static final int LEG_R_FRONT = 3;
    public static final int LEG_L_FRONT = 4;
    public static final int LEG_R_BACK = 5;
    public static final int LEG_L_BACK = 6;
    public static final int LEG_SIDE = 7;
    public static final int ARM_R_FRONT = 8;
    public static final int ARM_L_FRONT = 9;
    public static final int ARM_R_BACK = 10;
    public static final int ARM_L_BACK = 11;
    public static final int ARM_SIDE = 12;

    /** u, v, width, height, columns, rows -- regions from SettlerModel's texOffs. */
    static final int[][] REGIONS = {
        {69, 5, 10, 12, 3, 4},   // torso front (body texOffs 64,0; box 10x12x5)
        {84, 5, 10, 12, 3, 4},   // torso back
        {64, 5, 5, 12, 2, 4},    // torso right side
        {36, 36, 4, 12, 1, 3},   // right leg front (texOffs 32,32)
        {52, 36, 4, 12, 1, 3},   // left leg front (texOffs 48,32)
        {44, 36, 4, 12, 1, 3},   // right leg back
        {60, 36, 4, 12, 1, 3},   // left leg back
        {32, 36, 4, 12, 1, 3},   // right leg outer side
        {4, 36, 4, 12, 1, 3},    // right arm front (texOffs 0,32)
        {20, 36, 4, 12, 1, 3},   // left arm front (texOffs 16,32)
        {12, 36, 4, 12, 1, 3},   // right arm back
        {28, 36, 4, 12, 1, 3},   // left arm back
        {0, 36, 4, 12, 1, 3},    // right arm outer side
    };

    /** Reads one texel as ARGB. */
    @FunctionalInterface
    public interface Pixels {
        int argb(int x, int y);
    }

    private static final FigureSkin NONE = new FigureSkin(new int[0][]);
    private static final HashMap<ResourceLocation, FigureSkin> CACHE = new HashMap<>();

    private final int[][] cells;

    FigureSkin(int[][] cells) {
        this.cells = cells;
    }

    /** Colour of a cell (ARGB, opaque) or 0 when the region is empty there. */
    public int cell(int part, int col, int row) {
        if (part < 0 || part >= cells.length) return 0;
        int[] grid = cells[part];
        int cols = REGIONS[part][4];
        int index = row * cols + col;
        return index >= 0 && index < grid.length ? grid[index] : 0;
    }

    /** Samples every part from a texture read through {@code pixels}. */
    public static FigureSkin sample(Pixels pixels) {
        int[][] out = new int[REGIONS.length][];
        for (int i = 0; i < REGIONS.length; i++) {
            int[] r = REGIONS[i];
            out[i] = sampleRegion(pixels, r[0], r[1], r[2], r[3], r[4], r[5]);
        }
        return new FigureSkin(out);
    }

    /**
     * Averages a region into {@code cols x rows} cells (row-major). Only
     * mostly-opaque texels count; a cell with none is 0.
     */
    public static int[] sampleRegion(Pixels pixels, int u, int v, int w, int h, int cols, int rows) {
        int[] out = new int[cols * rows];
        for (int row = 0; row < rows; row++) {
            int y0 = v + row * h / rows;
            int y1 = Math.max(y0 + 1, v + (row + 1) * h / rows);
            for (int col = 0; col < cols; col++) {
                int x0 = u + col * w / cols;
                int x1 = Math.max(x0 + 1, u + (col + 1) * w / cols);
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = pixels.argb(x, y);
                        if ((c >>> 24) < 128) continue;
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                out[row * cols + col] = n == 0 ? 0
                    : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        return out;
    }

    /**
     * The cached outfit for a settler texture, sampling it on first use:
     * a composed skin is read from its DynamicTexture, a packed skin from
     * the resource manager (once). Null when the texture cannot be read.
     */
    public static FigureSkin of(ResourceLocation texture) {
        if (texture == null) return null;
        FigureSkin cached = CACHE.get(texture);
        if (cached != null) return cached == NONE ? null : cached;
        if (CACHE.size() > 512) CACHE.clear();
        FigureSkin skin = null;
        try {
            skin = read(texture);
        } catch (Exception | LinkageError ignored) {
            skin = null;
        }
        CACHE.put(texture, skin == null ? NONE : skin);
        return skin;
    }

    private static FigureSkin read(ResourceLocation texture) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        AbstractTexture loaded = mc.getTextureManager().getTexture(texture);
        if (loaded instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
            NativeImage image = dynamic.getPixels();
            if (image.getWidth() < 128 || image.getHeight() < 64) return null;
            return sample((x, y) -> abgrToArgb(image.getPixelRGBA(x, y)));
        }
        Optional<net.minecraft.server.packs.resources.Resource> resource = mc.getResourceManager().getResource(texture);
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            if (image.getWidth() < 128 || image.getHeight() < 64) return null;
            return sample((x, y) -> abgrToArgb(image.getPixelRGBA(x, y)));
        }
    }

    static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
    }
}
