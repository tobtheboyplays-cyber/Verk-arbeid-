package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.CraftPresentation;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    private static final int STOP_COLOR = 0xFFF0D7B0;
    private static final int STOP_WAITING = 0xFFD6A447;
    private static final int STOP_BLOCKED = 0xFFD35F52;
    private static final int DIM_TEXT_ALPHA = 0x26; // the through-wall tone, same feel the old 0x20 had
    private static final float PAD_X = 3.0F;
    private static final float PAD_TOP = 2.0F;
    private static final float PAD_BOTTOM = 2.0F;
    private static final float DOT_SIZE = 4.0F;
    private static final float DOT_GAP = 3.0F;
    private static final float LINE2_Y = HsUiTokens.LINE_GAP; // 11
    private static final float LINE3_Y = LINE2_Y + HsUiTokens.LINE_GAP;
    private static final double REQUEST_BUBBLE_RANGE_SQ = 24.0D * 24.0D;
    private static final int REQUEST_BUBBLE_RIM = 0xFFD6A447;
    private static final int REQUEST_BUBBLE_FILL = 0xEE1A1A1A;

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

        // World-first diagnostics stay quiet at village scale: only the one
        // courier the player deliberately targets while sneaking gets the
        // actionable third line. Fifty settlers therefore cost no fifty-line
        // overlay and no per-frame world scan.
        StopReason stop = entity.logisticsStopReason();
        boolean workZoneStop = (profession == Profession.LUMBERER
                || profession == Profession.FARMER)
            && (stop == StopReason.NO_WORK_ZONE
                || stop == StopReason.NO_VALID_TARGET);
        boolean inspectStop = targeted && stop != StopReason.NONE
            && Minecraft.getInstance().player != null
            && (workZoneStop || profession == Profession.COURIER
                && Minecraft.getInstance().player.isShiftKeyDown());
        Component stopLine = null;
        if (inspectStop) {
            Component targetName = logisticsTargetName(entity);
            int seconds = entity.logisticsRetrySeconds();
            stopLine = seconds > 0
                ? Component.translatable("hearthstead.logistics.line.retry",
                    stop.displayName(), targetName, seconds)
                : Component.translatable("hearthstead.logistics.line",
                    stop.displayName(), targetName);
        }

        int nameWidth = font.width(name);
        int statusWidth = status != null ? font.width(status) : 0;
        float statusClusterWidth = status != null
            ? statusWidth + (badge ? DOT_SIZE + DOT_GAP : 0.0F)
            : 0.0F;
        int stopWidth = stopLine == null ? 0 : font.width(stopLine);
        float stopClusterWidth = stopLine == null
            ? 0.0F : stopWidth + DOT_SIZE + DOT_GAP;
        float plateHalfWidth = Math.max(nameWidth,
            Math.max(statusClusterWidth, stopClusterWidth)) / 2.0F + PAD_X;
        float plateTop = -PAD_TOP;
        float plateBottom = (stopLine != null ? LINE3_Y + HsUiTokens.TEXT_H
            : status != null ? LINE2_Y + HsUiTokens.TEXT_H : HsUiTokens.TEXT_H)
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
        if (stopLine != null) {
            float clusterHalf = stopClusterWidth / 2.0F;
            float dotX = -clusterHalf + DOT_SIZE / 2.0F;
            float dotY = LINE3_Y + HsUiTokens.TEXT_H / 2.0F;
            drawDot(pose, buffers, packedLight, dotX, dotY,
                stop.isWaiting() ? STOP_WAITING : STOP_BLOCKED, fade);
            float textX = -clusterHalf + DOT_SIZE + DOT_GAP;
            font.drawInBatch(stopLine, textX, LINE3_Y, withAlpha(STOP_COLOR, dimAlpha), false,
                matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0, packedLight);
            font.drawInBatch(stopLine, textX, LINE3_Y, withAlpha(STOP_COLOR, fullAlpha), false,
                matrix, buffers, Font.DisplayMode.NORMAL, 0, packedLight);
        }
        pose.popPose();
    }

    @Override
    public void render(SettlerEntity entity, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffers,
                       int packedLight) {
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
        renderCraftPresentation(entity, partialTick, pose, buffers,
            packedLight);
        renderEquipmentRequestBubble(entity, partialTick, pose, buffers,
            packedLight);
    }

    /**
     * Renders only server-authored crafting truth. Table props are fixed to
     * the exact crafting-table block and storage props to the exact selected
     * container; neither inherits settler sway, root compression, yaw, or
     * locomotion. The projection is action-scoped and disappears as soon as
     * the server invalidates that reservation/escrow.
     */
    private void renderCraftPresentation(SettlerEntity entity,
                                         float partialTick,
                                         PoseStack pose,
                                         MultiBufferSource buffers,
                                         int packedLight) {
        CraftPresentation craft = entity.craftPresentation();
        BlockPos anchor = craft.anchorPos();
        if (!craft.active() || anchor == null) {
            return;
        }
        Vec3 entityPosition = entity.getPosition(partialTick);
        Direction forward = craft.facing();
        Direction right = forward.getClockWise();
        float facingYaw = -forward.toYRot();

        if (craft.phase() == CraftPresentation.Phase.LAY_OUT
            || craft.phase() == CraftPresentation.Phase.WIND_UP) {
            for (int slot = 0; slot < CraftPresentation.GRID_SIZE; slot++) {
                if (!craft.slotVisible(slot)) {
                    continue;
                }
                int row = slot / 3;
                int column = slot % 3;
                double lateral = (column - 1) * 0.225D;
                double depth = (1 - row) * 0.225D;
                double x = anchor.getX() + 0.5D
                    + right.getStepX() * lateral
                    + forward.getStepX() * depth;
                double z = anchor.getZ() + 0.5D
                    + right.getStepZ() * lateral
                    + forward.getStepZ() * depth;
                renderFixedCraftItem(entity, craft.recipeSlot(slot), pose,
                    buffers, packedLight, entityPosition, x,
                    anchor.getY() + 1.035D, z, facingYaw, 0.38F,
                    31 * slot);
            }
            return;
        }

        if (craft.phase() == CraftPresentation.Phase.RESULT_READ
            || craft.phase() == CraftPresentation.Phase.PICK_UP) {
            renderFixedCraftItem(entity, craft.output(), pose, buffers,
                packedLight, entityPosition, anchor.getX() + 0.5D,
                anchor.getY() + 1.045D, anchor.getZ() + 0.5D,
                facingYaw, 0.58F, 313);
            return;
        }

        if (craft.phase() == CraftPresentation.Phase.DEPOSIT) {
            // The chest-side copy remains fixed just outside the selected
            // container until the exact deposit contact clears the escrow.
            // CARRIED intentionally renders nothing: escrow is protected in
            // the worker's bag, not masquerading as a MAINHAND item.
            double x = anchor.getX() + 0.5D
                - forward.getStepX() * 0.34D;
            double z = anchor.getZ() + 0.5D
                - forward.getStepZ() * 0.34D;
            renderFixedCraftItem(entity, craft.output(), pose, buffers,
                packedLight, entityPosition, x, anchor.getY() + 0.94D, z,
                facingYaw, 0.55F, 719);
        }
    }

    private static void renderFixedCraftItem(SettlerEntity entity,
                                             ItemStack stack,
                                             PoseStack pose,
                                             MultiBufferSource buffers,
                                             int packedLight,
                                             Vec3 entityPosition,
                                             double worldX,
                                             double worldY,
                                             double worldZ,
                                             float yaw,
                                             float scale,
                                             int salt) {
        if (stack.isEmpty()) {
            return;
        }
        pose.pushPose();
        pose.translate(worldX - entityPosition.x, worldY - entityPosition.y,
            worldZ - entityPosition.z);
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.scale(scale, scale, scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack,
            ItemDisplayContext.GROUND, packedLight, OverlayTexture.NO_OVERLAY,
            pose, buffers, entity.level(), entity.getId() * 37 + salt);
        pose.popPose();
    }

    /**
     * One quiet, billboarded item bubble for an active need. It is independent
     * of name-tag visibility: hiding names must not hide gameplay information.
     * The icon is the real preferred request item projected by the server,
     * never a renderer-side guess from profession.
     */
    private void renderEquipmentRequestBubble(SettlerEntity entity,
                                              float partialTick,
                                              PoseStack pose,
                                              MultiBufferSource buffers,
                                              int packedLight) {
        ItemStack requested = entity.requestedEquipmentIcon();
        double distanceSqr = entityRenderDispatcher.distanceToSqr(entity);
        if (requested.isEmpty() || distanceSqr > REQUEST_BUBBLE_RANGE_SQ) {
            return;
        }
        float fade = Mth.clamp((float) ((24.0D - Math.sqrt(distanceSqr)) / 4.0D),
            0.0F, 1.0F);
        float bob = Mth.sin((entity.tickCount + partialTick) * 0.10F) * 0.035F;

        pose.pushPose();
        pose.translate(0.0D, entity.getBbHeight() + 1.02D + bob, 0.0D);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(0.42F, 0.42F, 0.42F);

        VertexConsumer plate = buffers.getBuffer(
            RenderType.textBackgroundSeeThrough());
        fillQuad(plate, pose, -0.74F, 0.74F, -0.74F, 0.74F,
            -0.06F, withAlpha(REQUEST_BUBBLE_RIM,
                fadeAlpha(0xFF, fade)), packedLight);
        fillQuad(plate, pose, -0.64F, 0.64F, -0.64F, 0.64F,
            -0.05F, withAlpha(REQUEST_BUBBLE_FILL,
                fadeAlpha(0xEE, fade)), packedLight);

        pose.pushPose();
        pose.translate(0.0F, 0.0F, 0.04F);
        Minecraft.getInstance().getItemRenderer().renderStatic(requested,
            ItemDisplayContext.GUI, packedLight, OverlayTexture.NO_OVERLAY,
            pose, buffers, entity.level(), entity.getId());
        pose.popPose();
        pose.popPose();
    }

    @Override
    public boolean shouldRender(SettlerEntity entity, Frustum frustum,
                                double cameraX, double cameraY, double cameraZ) {
        if (super.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)) {
            return true;
        }
        CraftPresentation craft = entity.craftPresentation();
        if (craft.active() && craft.anchorPos() != null
            && anchorVisible(entity, frustum, craft.anchorPos(), cameraX,
                cameraY, cameraZ)) {
            return true;
        }
        var placed = entity.placedWorkContainerPos();
        if (placed == null) {
            return false;
        }
        return anchorVisible(entity, frustum, placed, cameraX, cameraY,
            cameraZ);
    }

    private static boolean anchorVisible(SettlerEntity entity,
                                         Frustum frustum,
                                         BlockPos anchor,
                                         double cameraX,
                                         double cameraY,
                                         double cameraZ) {
        double dx = anchor.getX() + 0.5 - cameraX;
        double dy = anchor.getY() + 0.5 - cameraY;
        double dz = anchor.getZ() + 0.5 - cameraZ;
        return entity.shouldRenderAtSqrDistance(dx * dx + dy * dy + dz * dz)
            && frustum.isVisible(new AABB(anchor).inflate(0.5));
    }

    private static Component logisticsTargetName(SettlerEntity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || entity.logisticsStopTarget().isEmpty()) {
            return Component.translatable("hearthstead.logistics.target.unknown");
        }
        net.minecraft.core.BlockPos target = entity.logisticsStopTarget().get();
        if (minecraft.level.getBlockEntity(target) instanceof PlaqueBlockEntity plaque) {
            return plaque.type().displayName();
        }
        if (minecraft.level.getBlockEntity(target) instanceof HearthBlockEntity) {
            return Component.translatable("hearthstead.logistics.target.hearth");
        }
        return Component.translatable("hearthstead.logistics.target.destination");
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
        fillQuad(buffer, pose, x0, x1, y0, y1, 0.0F, argb, light);
    }

    private static void fillQuad(VertexConsumer buffer, PoseStack pose, float x0, float x1,
                                 float y0, float y1, float z, int argb, int light) {
        int a = argb >>> 24;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        PoseStack.Pose last = pose.last();
        buffer.addVertex(last, x0, y0, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y0, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y1, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x0, y1, z).setColor(r, g, b, a).setLight(light);
    }

    private static int fadeAlpha(int base0to255, float fade) {
        return Mth.clamp(Math.round(base0to255 * fade), 0, 255);
    }

    private static int withAlpha(int rgb, int alpha0to255) {
        return (alpha0to255 << 24) | (rgb & 0xFFFFFF);
    }
}
