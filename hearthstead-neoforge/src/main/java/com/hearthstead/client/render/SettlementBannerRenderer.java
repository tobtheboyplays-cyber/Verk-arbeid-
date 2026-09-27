package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.SettlementHeraldry;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the settlement Banner above its ledger counter: a gallows pole with an
 * arm, brace and iron straps (a standalone block model), and a vanilla banner
 * cloth with the settlement's colours swaying gently in the wind, harder in
 * rain and storms. The stand itself is the ordinary block model.
 */
public class SettlementBannerRenderer implements BlockEntityRenderer<HearthBlockEntity> {
    public static final ResourceLocation POLE_MODEL_ID = Hearthstead.id("block/settlement_banner_pole");
    public static final ModelResourceLocation POLE_MODEL = ModelResourceLocation.standalone(POLE_MODEL_ID);

    /** Vanilla's standing banner is 2/3; this one hangs from the gallows arm at 1/2. */
    private static final float CLOTH_SCALE = 0.5F;
    /** Top of the cloth, just under the arm's iron straps, in blocks above the base. */
    private static final float PIVOT_Y = 36.6F / 16F;
    /** Sideways offset toward the open side of the arm (away from the pole). */
    private static final float CLOTH_SIDE = 4.25F / 16F;
    /** How far behind the block centre the cloth hangs, under the arm. */
    private static final float CLOTH_BACK = 4.25F / 16F;

    private final ModelPart flag;
    private final BlockRenderDispatcher blocks;
    private BannerPatternLayers defaultPatterns;
    private HolderLookup.Provider defaultPatternsFor;

    public SettlementBannerRenderer(BlockEntityRendererProvider.Context context) {
        this.flag = context.bakeLayer(ModelLayers.BANNER).getChild("flag");
        this.blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(HearthBlockEntity banner, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = banner.getBlockState();
        Direction facing = state.hasProperty(HearthBlock.FACING)
            ? state.getValue(HearthBlock.FACING) : Direction.NORTH;
        Level level = banner.getLevel();

        // Pole, crossbar and finial: authored facing north, one block up.
        BakedModel pole = Minecraft.getInstance().getModelManager().getModel(POLE_MODEL);
        pose.pushPose();
        pose.translate(0.5F, 0.0F, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-(facing.toYRot() + 180.0F)));
        pose.translate(-0.5F, 1.0F, -0.5F);
        blocks.getModelRenderer().renderModel(pose.last(),
            buffers.getBuffer(Sheets.cutoutBlockSheet()), state, pole,
            1.0F, 1.0F, 1.0F, light, overlay, ModelData.EMPTY, null);
        pose.popPose();

        // Cloth: vanilla banner flag and patterns, hung from the crossbar.
        pose.pushPose();
        pose.translate(0.5F, PIVOT_Y, 0.5F);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(CLOTH_SIDE, 0.0F, -CLOTH_BACK);
        pose.scale(CLOTH_SCALE, -CLOTH_SCALE, -CLOTH_SCALE);
        applySway(banner.getBlockPos(), level, partialTick);
        com.hearthstead.heraldry.VillageDesign design = banner.effectiveDesign();
        DyeColor base = design.base();
        BannerPatternLayers patterns = level == null ? BannerPatternLayers.EMPTY
            : design.toPatternLayers(level.registryAccess().lookupOrThrow(
                net.minecraft.core.registries.Registries.BANNER_PATTERN));
        if (design.shape() == com.hearthstead.heraldry.BannerShape.STRAIGHT) {
            BannerRenderer.renderPatterns(pose, buffers, light, overlay, flag,
                ModelBakery.BANNER_BASE, true, base, patterns);
        } else {
            pose.pushPose();
            flag.translateAndRotate(pose);
            com.hearthstead.client.heraldry.ShapedCloth.render(pose, buffers, light, overlay,
                design.shape(), base, patterns);
            pose.popPose();
        }
        pose.popPose();
    }

    /**
     * A slow billow like vanilla's, plus a gentle sideways drift and gusts.
     * Rain and thunder stiffen the wind. Deterministic per position so
     * neighbouring banners do not move in lockstep.
     */
    private void applySway(BlockPos pos, Level level, float partialTick) {
        long time = level == null ? 0L : level.getGameTime();
        long seed = pos.getX() * 7L + pos.getY() * 9L + pos.getZ() * 13L;
        float t = (float) Math.floorMod(seed + time, 2400L) + partialTick;
        float wind = 1.0F;
        if (level != null) {
            wind += level.getRainLevel(partialTick) * 0.7F + level.getThunderLevel(partialTick) * 0.8F;
        }
        float gust = 0.75F + 0.25F * Mth.sin(t * 0.021F + seed * 0.37F);
        float billow = Mth.cos(t * Mth.TWO_PI / 100.0F);
        float drift = Mth.sin(t * Mth.TWO_PI / 170.0F + 1.3F);
        flag.x = 0.0F;
        flag.y = 0.0F;
        flag.z = 0.0F;
        flag.xRot = (-0.0125F + 0.012F * wind * gust * billow) * Mth.PI;
        flag.yRot = 0.0F;
        flag.zRot = 0.006F * wind * gust * drift * Mth.PI;
    }

    private BannerPatternLayers defaultPatterns(Level level) {
        HolderLookup.Provider registries = level == null ? null : level.registryAccess();
        if (defaultPatterns == null || defaultPatternsFor != registries) {
            defaultPatterns = SettlementHeraldry.defaultPatterns(registries);
            defaultPatternsFor = registries;
        }
        return defaultPatterns;
    }

    @Override
    public AABB getRenderBoundingBox(HearthBlockEntity banner) {
        BlockPos pos = banner.getBlockPos();
        return new AABB(pos.getX() - 0.25, pos.getY(), pos.getZ() - 0.25,
            pos.getX() + 1.25, pos.getY() + 3.0, pos.getZ() + 1.25);
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
