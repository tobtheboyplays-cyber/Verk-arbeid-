package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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

    // Owner's finding, 20260826 (filmed session, "Jeg liker ikke hvordan
    // UI'en her er pa de"): the overhead tag read as raw vanilla debug
    // text -- a plain floating name and, once employed, a second line
    // that clipped through walls at any range. He likes the settlers
    // themselves; this is presentation only.
    //
    // The ambient (non-explicitly-named) tag now caps at AMBIENT_RANGE and
    // tapers out over FADE_BAND blocks before that cap instead of popping,
    // so "capped" doesn't also read as "abrupt". An explicitly named
    // settler (a player naming one, lineups, debugging) keeps vanilla's
    // full 64-block range -- that is a deliberate, different contract, not
    // the ambient everyone-nearby-is-named behaviour this fixes.
    private static final double AMBIENT_RANGE = 24.0;
    private static final double AMBIENT_RANGE_SQ = AMBIENT_RANGE * AMBIENT_RANGE;
    private static final double CUSTOM_RANGE_SQ = 4096.0;
    private static final double FADE_BAND = 6.0;

    // The tag's own palette, pulled from the same token set every screen in
    // the mod draws from (client/ui/HsUiTokens) -- so the settler standing
    // next to a plaque or a card in the Hearth ledger reads as the same
    // object language, not a second, uncoordinated one.
    private static final int PLATE_RIM = 0xFF1C1C20; // iron_forged[0]
    private static final int PLATE_FILL = HsUiTokens.FIELD; // charcoal, #1A1A1A
    private static final int NAME_COLOR = HsUiTokens.TEXT_STRONG;
    private static final int STATUS_COLOR = HsUiTokens.TEXT_MUTED;
    private static final int DIM_TEXT_ALPHA = 0x26; // the through-wall tone, same feel the old 0x20 had
    private static final float PAD_X = 3.0F;
    private static final float PAD_TOP = 2.0F;
    private static final float PAD_BOTTOM = 2.0F;
    private static final float DOT_SIZE = 4.0F;
    private static final float DOT_GAP = 3.0F;
    private static final float LINE2_Y = HsUiTokens.LINE_GAP; // 11

    public SettlerRenderer(EntityRendererProvider.Context context) {
        super(context, new SettlerModel(context.bakeLayer(SettlerModel.LAYER)), 0.5F);
        addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        addLayer(new SettlerArmorLayer(this));
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
        // everyone-nearby-is-named behaviour keeps its capped, short one --
        // see AMBIENT_RANGE above.
        double range = entity.isCustomNameVisible() ? CUSTOM_RANGE_SQ : AMBIENT_RANGE_SQ;
        return entityRenderDispatcher.distanceToSqr(entity) < range;
    }

    @Override
    protected void renderNameTag(SettlerEntity entity, Component name, PoseStack pose,
                                 MultiBufferSource buffers, int packedLight,
                                 float partialTick) {
        // Taper the ambient tag out over the last FADE_BAND blocks before
        // its cap rather than letting shouldShowName's hard boolean pop it
        // in and out of existence.
        float fade = 1.0F;
        if (!entity.isCustomNameVisible()) {
            double dist = Math.sqrt(entityRenderDispatcher.distanceToSqr(entity));
            fade = Mth.clamp((float) ((AMBIENT_RANGE - dist) / FADE_BAND), 0.0F, 1.0F);
            if (fade <= 0.0F) {
                return;
            }
        }

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

        // The status line: the PROFESSION, always while employed ("navn
        // over med yrke ... tydeligere for jobben", 20260825) -- a
        // villager's job is readable at a glance, not only under the
        // crosshair. The current doing is the noisy part, so it still only
        // joins in while targeted.
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
        boolean badge = profession.employed();

        int nameWidth = font.width(name);
        int statusWidth = status != null ? font.width(status) : 0;
        float statusClusterWidth = status != null
            ? statusWidth + (badge ? DOT_SIZE + DOT_GAP : 0.0F)
            : 0.0F;
        float plateHalfWidth = Math.max(nameWidth, statusClusterWidth) / 2.0F + PAD_X;
        float plateTop = -PAD_TOP;
        float plateBottom = (status != null ? LINE2_Y + HsUiTokens.TEXT_H : HsUiTokens.TEXT_H)
            + PAD_BOTTOM;

        // One shared backplate under both lines -- a single designed tag,
        // not two independent floating strings each carrying vanilla's own
        // per-glyph background box.
        drawPlate(pose, buffers, packedLight, plateHalfWidth, plateTop, plateBottom, fade);

        int dimAlpha = fadeAlpha(DIM_TEXT_ALPHA, fade);
        int fullAlpha = fadeAlpha(0xFF, fade);
        float nx = -nameWidth / 2.0F;
        font.drawInBatch(name, nx, 0, withAlpha(NAME_COLOR, dimAlpha), false, matrix, buffers,
            Font.DisplayMode.SEE_THROUGH, 0, packedLight);
        font.drawInBatch(name, nx, 0, withAlpha(NAME_COLOR, fullAlpha), false, matrix, buffers,
            Font.DisplayMode.NORMAL, 0, packedLight);

        if (status != null) {
            float clusterHalf = statusClusterWidth / 2.0F;
            float textX = badge ? -clusterHalf + DOT_SIZE + DOT_GAP : -statusWidth / 2.0F;
            if (badge) {
                float dotX = -clusterHalf + DOT_SIZE / 2.0F;
                float dotY = LINE2_Y + HsUiTokens.TEXT_H / 2.0F;
                drawDot(pose, buffers, packedLight, dotX, dotY, profession.color(), fade);
            }
            font.drawInBatch(status, textX, LINE2_Y, withAlpha(STATUS_COLOR, dimAlpha), false,
                matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0, packedLight);
            font.drawInBatch(status, textX, LINE2_Y, withAlpha(STATUS_COLOR, fullAlpha), false,
                matrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        }
        pose.popPose();
    }

    /**
     * The tag's backplate: a 1px iron rim under a charcoal fill, the same
     * two-step construction {@code panel/inset} uses at GUI scale, just
     * drawn as flat quads instead of a nine-slice sprite -- there is no
     * sprite path available to billboarded world-space text. Respects the
     * player's own background-opacity accessibility setting the way
     * vanilla's chat and the old per-glyph box both did, just against a
     * higher, more legible baseline than chat's 0.25 default.
     */
    private void drawPlate(PoseStack pose, MultiBufferSource buffers, int light,
                           float halfWidth, float top, float bottom, float fade) {
        float userOpacity = Minecraft.getInstance().options.getBackgroundOpacity(0.45F);
        int fillAlpha = fadeAlpha(Math.round(userOpacity * 255.0F), fade);
        if (fillAlpha <= 0) {
            return;
        }
        int rimAlpha = fadeAlpha(Math.min(255, Math.round(userOpacity * 255.0F * 1.4F)), fade);
        VertexConsumer plate = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        fillQuad(plate, pose, -halfWidth - 1.0F, halfWidth + 1.0F, top - 1.0F, bottom + 1.0F,
            withAlpha(PLATE_RIM, rimAlpha), light);
        fillQuad(plate, pose, -halfWidth, halfWidth, top, bottom,
            withAlpha(PLATE_FILL, fillAlpha), light);
    }

    /**
     * The profession badge: a small diamond bead in the trade's own colour,
     * the same "stamped mark" shape the plaque's requirement rows use --
     * supplementary to the muted text next to it, never the only channel
     * carrying which trade this is.
     */
    private void drawDot(PoseStack pose, MultiBufferSource buffers, int light,
                         float cx, float cy, int rgb, float fade) {
        int alpha = fadeAlpha(0xFF, fade);
        if (alpha <= 0) {
            return;
        }
        VertexConsumer dot = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        float r = DOT_SIZE / 2.0F;
        int argb = withAlpha(rgb, alpha);
        int a = argb >>> 24;
        int red = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        PoseStack.Pose last = pose.last();
        dot.addVertex(last, cx, cy - r, 0.0F).setColor(red, g, b, a).setLight(light);
        dot.addVertex(last, cx - r, cy, 0.0F).setColor(red, g, b, a).setLight(light);
        dot.addVertex(last, cx, cy + r, 0.0F).setColor(red, g, b, a).setLight(light);
        dot.addVertex(last, cx + r, cy, 0.0F).setColor(red, g, b, a).setLight(light);
    }

    private static void fillQuad(VertexConsumer buffer, PoseStack pose, float x0, float x1,
                                 float y0, float y1, int argb, int light) {
        int a = argb >>> 24;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        PoseStack.Pose last = pose.last();
        buffer.addVertex(last, x0, y0, 0.0F).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y0, 0.0F).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y1, 0.0F).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x0, y1, 0.0F).setColor(r, g, b, a).setLight(light);
    }

    private static int fadeAlpha(int base0to255, float fade) {
        return Mth.clamp(Math.round(base0to255 * fade), 0, 255);
    }

    private static int withAlpha(int rgb, int alpha0to255) {
        return (alpha0to255 << 24) | (rgb & 0xFFFFFF);
    }
}
