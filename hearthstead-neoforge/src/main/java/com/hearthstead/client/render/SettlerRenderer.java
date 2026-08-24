package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public class SettlerRenderer extends MobRenderer<SettlerEntity, SettlerModel> {
    private static final ResourceLocation TEXTURE_NONE =
        Hearthstead.id("textures/entity/settler/settler_none.png");
    private static final ResourceLocation TEXTURE_FARMER =
        Hearthstead.id("textures/entity/settler/settler_farmer.png");
    private static final ResourceLocation TEXTURE_LUMBERER =
        Hearthstead.id("textures/entity/settler/settler_lumberer.png");
    private static final ResourceLocation TEXTURE_GUARD =
        Hearthstead.id("textures/entity/settler/settler_guard.png");

    public SettlerRenderer(EntityRendererProvider.Context context) {
        super(context, new SettlerModel(context.bakeLayer(SettlerModel.LAYER)), 0.5F);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(SettlerEntity entity) {
        ResourceLocation composed = SettlerTextureCache.getOrCreate(entity);
        if (composed != null) {
            return composed;
        }
        return switch (entity.getProfession()) {
            case FARMER -> TEXTURE_FARMER;
            case LUMBERER -> TEXTURE_LUMBERER;
            case GUARD -> TEXTURE_GUARD;
            default -> TEXTURE_NONE;
        };
    }

    @Override
    protected boolean shouldShowName(SettlerEntity entity) {
        return entityRenderDispatcher.distanceToSqr(entity) < 150.0
            && Minecraft.renderNames();
    }

    @Override
    protected void renderNameTag(SettlerEntity entity, Component name, PoseStack pose,
                                 MultiBufferSource buffers, int packedLight,
                                 float partialTick) {
        pose.pushPose();
        pose.translate(0.0F, entity.getBbHeight() + 0.55F, 0.0F);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = pose.last().pose();
        Font font = getFont();
        int background = (int) (Minecraft.getInstance().options
            .getBackgroundOpacity(0.25F) * 255.0F) << 24;

        float x = -font.width(name) / 2.0F;
        font.drawInBatch(name, x, 0, 0x20FFFFFF, false, matrix, buffers,
            Font.DisplayMode.SEE_THROUGH, background, packedLight);
        font.drawInBatch(name, x, 0, 0xFFFFFF, false, matrix, buffers,
            Font.DisplayMode.NORMAL, 0, packedLight);

        // Second line while targeted: profession and current doing.
        if (Minecraft.getInstance().crosshairPickEntity == entity) {
            Profession profession = entity.getProfession();
            Component status = profession.employed()
                ? Component.empty().append(profession.displayName())
                    .append(" · ").append(entity.getActivity().displayName())
                : entity.getActivity().displayName();
            float sx = -font.width(status) / 2.0F;
            int color = 0xFF000000 | profession.color();
            font.drawInBatch(status, sx, 11, color, false, matrix, buffers,
                Font.DisplayMode.SEE_THROUGH, background, packedLight);
            font.drawInBatch(status, sx, 11, color, false, matrix, buffers,
                Font.DisplayMode.NORMAL, 0, packedLight);
        }
        pose.popPose();
    }
}
