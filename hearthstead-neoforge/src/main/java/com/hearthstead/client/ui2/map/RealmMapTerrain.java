package com.hearthstead.client.ui2.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Top-down terrain for the realm map, painted from the chunks the client
 * already has loaded -- no terrain is ever requested from the server.
 *
 * <p>One texel is one block. The texture is chunk aligned and covers the
 * claim plus a margin. It starts as blank parchment and fills in from the
 * centre outward a few chunks per frame under a time budget; afterwards a
 * slow round-robin re-paints one chunk every few frames and newly loaded
 * chunks are queued when noticed. A chunk that unloads keeps its last
 * painting (the map remembers what you have seen). All scratch state is
 * preallocated; a frame's work never allocates.
 */
public final class RealmMapTerrain implements AutoCloseable {
    public static final int MARGIN = 32;
    public static final int MAX_SIZE = 256;
    static final long FRAME_BUDGET_NANOS = 1_500_000L;
    static final int RESCAN_FRAMES = 30;
    static final int ROLLING_FRAMES = 6;

    private static final byte NEVER = 0;
    private static final byte PAINTED = 2;

    private final int originX;
    private final int originZ;
    private final int size;
    private final int chunks;
    private final NativeImage image;
    private final DynamicTexture texture;
    private final ResourceLocation location;
    private final byte[] state;
    private final int[] order;
    private final int[] queue;
    private final boolean[] queued;
    private int head;
    private int tail;
    private int rolling;
    private int frame;
    private boolean dirty;
    private boolean closed;
    private int painted;
    private final int[] heights = new int[18 * 18];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private RealmMapTerrain(int originX, int originZ, int size) {
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.chunks = size / 16;
        this.image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                image.setPixelRGBA(x, z, RealmMapPalette.toAbgr(
                    RealmMapPalette.unknown(RealmMapPalette.grain(originX + x, originZ + z))));
            }
        }
        this.texture = new DynamicTexture(image);
        this.location = Minecraft.getInstance().getTextureManager().register("hearthstead_realm_map", texture);
        int count = chunks * chunks;
        this.state = new byte[count];
        this.queue = new int[count];
        this.queued = new boolean[count];
        this.order = spiralOrder(chunks);
        for (int index : order) enqueue(index);
    }

    /** Terrain for a claim centred at (cx, cz) with the given radius, chunk aligned. */
    public static RealmMapTerrain create(int centerX, int centerZ, int radius) {
        int half = Math.min(MAX_SIZE / 2, Math.max(48, radius + MARGIN));
        int minX = Math.floorDiv(centerX - half, 16) * 16;
        int minZ = Math.floorDiv(centerZ - half, 16) * 16;
        int maxX = (Math.floorDiv(centerX + half - 1, 16) + 1) * 16;
        int maxZ = (Math.floorDiv(centerZ + half - 1, 16) + 1) * 16;
        int size = Math.min(MAX_SIZE, Math.max(maxX - minX, maxZ - minZ));
        return new RealmMapTerrain(minX, minZ, size);
    }

    /** Chunk indices ordered by distance from the centre, so the map fills in from the Banner out. */
    static int[] spiralOrder(int chunks) {
        int count = chunks * chunks;
        int[] out = new int[count];
        long[] keyed = new long[count];
        double c = (chunks - 1) / 2.0D;
        for (int i = 0; i < count; i++) {
            int cx = i % chunks;
            int cz = i / chunks;
            long d = Math.round(((cx - c) * (cx - c) + (cz - c) * (cz - c)) * 16.0D);
            keyed[i] = (d << 20) | i;
        }
        java.util.Arrays.sort(keyed);
        for (int i = 0; i < count; i++) out[i] = (int) (keyed[i] & 0xFFFFF);
        return out;
    }

    public boolean covers(int centerX, int centerZ, int radius) {
        int half = Math.min(MAX_SIZE / 2, Math.max(48, radius + MARGIN));
        return centerX - half >= originX && centerZ - half >= originZ
            && centerX + half <= originX + size && centerZ + half <= originZ + size;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public int size() {
        return size;
    }

    public ResourceLocation location() {
        return location;
    }

    /** Fraction of chunks painted at least once (0..1); used for the quiet "surveying" caption. */
    public float surveyed() {
        return state.length == 0 ? 1.0F : painted / (float) state.length;
    }

    /** Does a frame's worth of painting and uploads if anything changed. */
    public void tick(ClientLevel level) {
        if (closed || level == null) return;
        frame++;
        if (frame % RESCAN_FRAMES == 0) rescan(level);
        if (frame % ROLLING_FRAMES == 0) {
            int index = order[rolling];
            rolling = (rolling + 1) % order.length;
            if (state[index] == PAINTED) enqueue(index);
        }
        long start = System.nanoTime();
        while (head != tail && System.nanoTime() - start < FRAME_BUDGET_NANOS) {
            int index = queue[head];
            head = (head + 1) % queue.length;
            queued[index] = false;
            paintChunk(level, index);
        }
        if (dirty) {
            texture.upload();
            dirty = false;
        }
    }

    private void rescan(ClientLevel level) {
        for (int i = 0; i < state.length; i++) {
            if (state[i] == NEVER && !queued[i] && loaded(level, i)) enqueue(i);
        }
    }

    private boolean loaded(ClientLevel level, int index) {
        int cx = (originX >> 4) + index % chunks;
        int cz = (originZ >> 4) + index / chunks;
        return level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
    }

    private void enqueue(int index) {
        if (queued[index]) return;
        int next = (tail + 1) % queue.length;
        if (next == head && queue.length > 1) return;
        queue[tail] = index;
        tail = next;
        queued[index] = true;
    }

    private void paintChunk(ClientLevel level, int index) {
        int cxLocal = index % chunks;
        int czLocal = index / chunks;
        int cx = (originX >> 4) + cxLocal;
        int cz = (originZ >> 4) + czLocal;
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) return;
        int baseX = cx << 4;
        int baseZ = cz << 4;
        // Heights for this chunk plus a one-block north/west apron (18x18 incl. corner).
        for (int dz = -1; dz < 16; dz++) {
            for (int dx = -1; dx < 16; dx++) {
                heights[(dz + 1) * 18 + (dx + 1)] = surface(level, chunk, baseX + dx, baseZ + dz);
            }
        }
        int minY = level.getMinBuildHeight();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int grain = RealmMapPalette.grain(wx, wz);
                int top = heights[(lz + 1) * 18 + (lx + 1)];
                int argb;
                if (top <= minY) {
                    argb = RealmMapPalette.unknown(grain);
                } else {
                    argb = columnColour(chunk, wx, top, wz, lx, lz, minY, grain);
                }
                image.setPixelRGBA(wx - originX, wz - originZ, RealmMapPalette.toAbgr(argb));
            }
        }
        if (state[index] != PAINTED) painted++;
        state[index] = PAINTED;
        dirty = true;
    }

    private int columnColour(LevelChunk chunk, int wx, int top, int wz, int lx, int lz, int minY, int grain) {
        int y = top;
        BlockState block = chunk.getBlockState(cursor.set(wx, y, wz));
        MapColor colour = block.getMapColor(chunk, cursor);
        int guard = 0;
        while (colour == MapColor.NONE && y > minY && guard++ < 8) {
            y--;
            block = chunk.getBlockState(cursor.set(wx, y, wz));
            colour = block.getMapColor(chunk, cursor);
        }
        if (block.getFluidState().is(FluidTags.WATER)) {
            int depth = 1;
            int floor = y - 1;
            while (floor > minY && depth < 16
                && chunk.getBlockState(cursor.set(wx, floor, wz)).getFluidState().is(FluidTags.WATER)) {
                depth++;
                floor--;
            }
            return RealmMapPalette.water(depth, grain);
        }
        int h = y;
        int north = heights[lz * 18 + (lx + 1)];
        int west = heights[(lz + 1) * 18 + lx];
        if (north <= minY) north = h;
        if (west <= minY) west = h;
        int slope = h * 2 - north - west;
        int band = Math.floorDiv(h, RealmMapPalette.CONTOUR_STEP);
        // The engraved line sits on the upper side of each contour step.
        boolean contour = band > Math.floorDiv(north, RealmMapPalette.CONTOUR_STEP)
            || band > Math.floorDiv(west, RealmMapPalette.CONTOUR_STEP);
        if (block.is(Blocks.DIRT_PATH)) {
            return RealmMapPalette.road(slope, grain);
        }
        if (colour == MapColor.NONE) return RealmMapPalette.unknown(grain);
        return RealmMapPalette.land(colour.col, slope, contour, grain);
    }

    /** Surface height at a column, or min build height when its chunk is not loaded. */
    private static int surface(ClientLevel level, LevelChunk home, int x, int z) {
        LevelChunk chunk = home;
        if ((x >> 4) != home.getPos().x || (z >> 4) != home.getPos().z) {
            chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            if (chunk == null) return level.getMinBuildHeight();
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Minecraft.getInstance().getTextureManager().release(location);
    }
}
