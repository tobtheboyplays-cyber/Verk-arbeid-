package com.hearthstead.client.builder;

import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.builder.BuilderMaterials;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The catalog's 3D preview of a blueprint (preset browser): the server's
 * PREVIEW cells drawn as real blocks in a small turning box, so the player
 * sees what a preset looks like -- in the town's style when "Match town
 * style" is on -- before placing the ghost. Only the outer shell is drawn
 * (cells with at least one open side), which keeps big halls cheap.
 */
public final class BlueprintThumbnail {

    private record Cell(int x, int y, int z, BlockState state) {
    }

    private static BuilderPayloads.Preview cachedFor;
    private static List<Cell> shell = List.of();
    private static Map<Item, Integer> bill = Map.of();

    private BlueprintThumbnail() {
    }

    private static void prepare(BuilderPayloads.Preview preview) {
        if (preview == cachedFor) {
            return;
        }
        cachedFor = preview;
        int[] packed = preview.cells();
        Set<Long> solid = new HashSet<>();
        List<Cell> all = new ArrayList<>(packed.length / 2);
        Map<Item, Integer> sums = new LinkedHashMap<>();
        List<net.minecraft.core.BlockPos> water = new ArrayList<>();
        for (int i = 0; i + 1 < packed.length; i += 2) {
            int p = packed[i];
            int idx = packed[i + 1];
            if (idx < 0 || idx >= preview.palette().size()) {
                continue;
            }
            BlockState state = preview.palette().get(idx);
            int x = p & 0xFF;
            int y = (p >> 8) & 0xFF;
            int z = (p >> 16) & 0xFF;
            all.add(new Cell(x, y, z, state));
            if (BuilderMaterials.waterSource(state)) {
                water.add(new net.minecraft.core.BlockPos(x, y, z));
            }
            if (state.canOcclude()) {
                solid.add(key(x, y, z));
            }
            // Builder ground (27 Sep): a depth > 0 means the ground layer
            // (depth - 1) sits in the terrain; its soil is already there.
            if (preview.groundLevel() > 0 && y == preview.groundLevel() - 1
                && com.hearthstead.settlement.builder.MaterialRules.isSoil(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
                continue;
            }
            for (BuilderMaterials.ItemCount cost : BuilderMaterials.costsOf(state)) {
                if (cost.buildable()) {
                    sums.merge(cost.item(), cost.count(), Integer::sum);
                }
            }
        }
        if (!water.isEmpty()) {
            sums.merge(net.minecraft.world.item.Items.WATER_BUCKET, BuilderMaterials.waterBuckets(water), Integer::sum);
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
        shell = outer;
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(sums.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Item, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : sorted) {
            ordered.put(e.getKey(), e.getValue());
        }
        bill = ordered;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFF) | (((long) y & 0xFFFF) << 16) | (((long) z & 0xFFFF) << 32);
    }

    /** The bill of materials of this preview (the styled one when the town style is on). */
    public static Map<Item, Integer> bill(BuilderPayloads.Preview preview) {
        prepare(preview);
        return bill;
    }

    /** Draws the preview into the square at (x, y) of the given size, slowly turning. */
    public static void render(GuiGraphics g, BuilderPayloads.Preview preview, int x, int y, int size) {
        prepare(preview);
        if (shell.isEmpty()) {
            return;
        }
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        float extent = Math.max(preview.sizeX(), Math.max(preview.sizeY(), preview.sizeZ()));
        float scale = size / (extent * 1.75F);
        float spin = (Util.getMillis() % 24000L) / 24000.0F * 360.0F;
        g.enableScissor(x, y, x + size, y + size);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x + size / 2.0F, y + size / 2.0F, 400.0F);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(28.0F));
        pose.mulPose(Axis.YP.rotationDegrees(45.0F + spin));
        pose.translate(-preview.sizeX() / 2.0F, -preview.sizeY() / 2.0F, -preview.sizeZ() / 2.0F);
        Lighting.setupFor3DItems();
        var buffers = g.bufferSource();
        for (Cell c : shell) {
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
