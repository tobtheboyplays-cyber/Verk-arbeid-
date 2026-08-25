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
        if (!Minecraft.renderNames()) {
            return false;
        }
        // An explicitly flagged name (lineups, debugging, a player naming a
        // settler) carries vanilla's full 64-block range; the ambient
        // everyone-nearby-is-named behaviour keeps its short, intimate one.
        double range = entity.isCustomNameVisible() ? 4096.0 : 150.0;
        return entityRenderDispatcher.distanceToSqr(entity) < range;
    }

    @Override
    protected void renderNameTag(SettlerEntity entity, Component name, PoseStack pose,
                                 MultiBufferSource buffers, int packedLight,
                                 float partialTick) {
        // 1.21 convention: POSITIVE x scale and the NAME_TAG attachment
        // point. The old 1.20-era scale(-0.025F, ...) mirror makes every
        // glyph quad back-facing on 1.21, and the text is culled invisibly
        // -- proven live (20260825T183505Z): a vanilla pig's tag rendered
        // while a settler's, drawn by this method, did not.
        net.minecraft.world.phys.Vec3 attach = entity.getAttachments().getNullable(
            net.minecraft.world.entity.EntityAttachment.NAME_TAG, 0,
            entity.getViewYRot(partialTick));
        if (attach == null) {
            return;
        }
        pose.pushPose();
        pose.translate(attach.x, attach.y + 0.5, attach.z);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(0.025F, -0.025F, 0.025F);
        Matrix4f matrix = pose.last().pose();
        Font font = getFont();
        int background = (int) (Minecraft.getInstance().options
            .getBackgroundOpacity(0.25F) * 255.0F) << 24;

        float x = -font.width(name) / 2.0F;
        font.drawInBatch(name, x, 0, 0x20FFFFFF, false, matrix, buffers,
            Font.DisplayMode.SEE_THROUGH, background, packedLight);
        font.drawInBatch(name, x, 0, 0xFFFFFFFF, false, matrix, buffers,
            Font.DisplayMode.NORMAL, 0, packedLight);

        // Second line: the PROFESSION, always ("navn over med yrke ...
        // tydeligere for jobben", 20260825) — a villager's job is readable
        // at a glance, not only under the crosshair. The current doing is
        // the noisy part, so it still only joins in while targeted.
        Profession profession = entity.getProfession();
        boolean targeted = Minecraft.getInstance().crosshairPickEntity == entity;
        Component status = null;
        if (profession.employed()) {
            status = targeted
                ? Component.empty().append(profession.displayName())
                    .append(" · ").append(entity.getActivity().displayName())
                : profession.displayName();
        } else if (targeted) {
            status = entity.getActivity().displayName();
        }
        if (status != null) {
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
