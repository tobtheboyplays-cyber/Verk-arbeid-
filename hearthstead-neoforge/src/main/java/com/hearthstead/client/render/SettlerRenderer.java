package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.CraftPresentation;
import com.hearthstead.entity.BagTransferPresentation;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
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
    private boolean portraitLabelsHidden;

    /** Hide this renderer's world name/activity plate only for the synchronous portrait draw. */
    public static void withoutPortraitLabels(SettlerEntity entity, Runnable drawPortrait) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof SettlerRenderer settlerRenderer)) {
            drawPortrait.run();
            return;
        }
        boolean previous = settlerRenderer.portraitLabelsHidden;
        settlerRenderer.portraitLabelsHidden = true;
        try {
            drawPortrait.run();
        } finally {
            settlerRenderer.portraitLabelsHidden = previous;
        }
    }
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
    /** Vanilla world size from two blocks out; capped apparent size below it. */
    static final double NEAR_FULL_SIZE = 2.0;
    /** Below this distance the apparent size also shrinks toward zero. */
    static final double NEAR_SHRINK_DISTANCE = 1.5;

    /**
     * World-label multiplier shared with thought bubbles. Proportional scaling
     * cancels perspective growth nearby; an extra close-range factor makes the
     * label recede below 1.5 blocks. No positive floor: that would let labels
     * grow without bound as the camera approaches.
     */
    static float nearLabelScale(double distance) {
        if (!(distance > 0.0)) {
            return 0.0F;
        }
        double cap = Math.min(1.0, distance / NEAR_FULL_SIZE);
        double shrink = Math.min(1.0, distance / NEAR_SHRINK_DISTANCE);
        return (float) (cap * shrink);
    }
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
    // 1.21 text quads need a positive billboard x scale (the same rule as
    // renderNameTag above). The previous negative x scale back-faced and
    // culled the count at normal player-camera angles even though the item
    // itself rendered. This compact high-contrast plate is deliberately
    // smaller than the 0.62-block item prop while remaining readable at the
    // native QA distance (about five blocks at 1024x768).
    static final float TRANSFER_COUNT_SCALE = 0.025F;
    static final int TRANSFER_COUNT_TEXT = 0xFFFDF3DB;
    static final int TRANSFER_COUNT_RIM = 0xFFD6A447;
    static final int TRANSFER_COUNT_FILL = 0xE61A1A1A;
    private static final float TRANSFER_COUNT_PAD_X = 2.5F;
    private static final float TRANSFER_COUNT_PAD_Y = 1.5F;
    static final long CONTAINER_DOWN_TOOL_RESTORE_MS = 1200L;
    static final long CONTAINER_UP_TOOL_SUPPRESS_MS = 600L;

    public SettlerRenderer(EntityRendererProvider.Context context) {
        super(context, new SettlerModel(context.bakeLayer(SettlerModel.LAYER)), 0.5F);
        addLayer(new LumberCarryItemInHandLayer(this,
            context.getItemInHandRenderer()));
        addLayer(new FisherOutfitLayer(this));
        addLayer(new SettlerArmorLayer(this));
        addLayer(new ResidentMealLayer(this, context.getItemInHandRenderer()));
        addLayer(new CarcassCarryLayer(this, context.getItemInHandRenderer()));
        addLayer(new MotionPropLayer(this, context.getItemInHandRenderer()));
        addLayer(new GuardScabbardLayer(this, context.getItemInHandRenderer()));
        addLayer(new CarryPackLayer(this, context.getItemInHandRenderer()));
    }

    /**
     * The server-owned MAINHAND stack never changes here. Heavy timber carry
     * reserves both visible hands for the frame grips. The two container
     * one-shots run under GATHERING_LOG server activity, so their exact
     * AnimationState clocks also participate: set-down begins in the
     * two-hand haul silhouette and restores the axe during recovery; pickup
     * begins with the axe visible and suppresses it as both hands settle onto
     * the frame. Every other state uses vanilla's ItemInHandLayer unchanged.
     */
    static boolean rendersHeldItemsFor(SettlerActivity activity,
                                       boolean containerDownActive,
                                       long containerDownElapsedMs,
                                       boolean containerUpActive,
                                       long containerUpElapsedMs) {
        if (activity == SettlerActivity.HAULING_LOG
            || CarcassCarryLayer.ownsHands(activity)) {
            return false;
        }
        if (containerDownActive) {
            return containerDownElapsedMs >= CONTAINER_DOWN_TOOL_RESTORE_MS;
        }
        if (containerUpActive) {
            return containerUpElapsedMs < CONTAINER_UP_TOOL_SUPPRESS_MS;
        }
        return true;
    }

    private static final class LumberCarryItemInHandLayer
            extends ItemInHandLayer<SettlerEntity, SettlerModel> {
        private LumberCarryItemInHandLayer(
                net.minecraft.client.renderer.entity.RenderLayerParent<SettlerEntity,
                    SettlerModel> parent,
                ItemInHandRenderer itemRenderer) {
            super(parent, itemRenderer);
            this.bowRenderer = itemRenderer;
        }

        /** Weapons lane: vanilla's ItemInHandLayer field is private; SettlerBowHold needs it. */
        private final ItemInHandRenderer bowRenderer;

        @Override
        protected void renderArmWithItem(net.minecraft.world.entity.LivingEntity entity,
                                         net.minecraft.world.item.ItemStack stack,
                                         net.minecraft.world.item.ItemDisplayContext context,
                                         net.minecraft.world.entity.HumanoidArm arm,
                                         PoseStack pose, MultiBufferSource buffers, int light) {
            // A carried carcass is drawn whole by CarcassCarryLayer, never as a hand sprite.
            if (com.hearthstead.item.CarcassItem.isCarcass(stack)) return;
            // A motion-clip prop window may stow the real item of one hand (display only).
            if (entity instanceof SettlerEntity settler
                && MotionPropLayer.hidesReal(getParentModel(), settler, arm)) return;
            // Greeting: the sword is in the scabbard (GuardScabbardLayer draws it at the hip).
            if (entity instanceof SettlerEntity sheathing && arm == sheathing.getMainArm()
                && SettlerModel.swordSheathed(sheathing)) return;
            // Weapons lane (owner 26 Sep): settlers carry the vanilla bow 1.22x, at the side when
            // idle, across the hips at low ready, upright while drawing (players keep vanilla's).
            if (com.hearthstead.client.weapon.SettlerBowHold.applies(entity, stack)) {
                com.hearthstead.client.weapon.SettlerBowHold.render(getParentModel(), entity, stack, arm,
                    pose, buffers, light, this.bowRenderer);
                return;
            }
            super.renderArmWithItem(entity, stack, context, arm, pose, buffers, light);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers,
                           int packedLight, SettlerEntity entity,
                           float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            if (entity.bagTransferPresentation().ownsBodyPose(entity)) return;
            if (entity.getActivity() == SettlerActivity.EATING && entity.hasMeal()) return;
            if (getParentModel().hasServiceHandPose(net.minecraft.world.entity.HumanoidArm.RIGHT)
                || getParentModel().hasServiceHandPose(net.minecraft.world.entity.HumanoidArm.LEFT)) return;
            boolean lowering = entity.workContainerDownState.isStarted();
            boolean lifting = entity.workContainerUpState.isStarted();
            if (rendersHeldItemsFor(entity.getActivity(), lowering,
                    lowering
                        ? entity.workContainerDownState.getAccumulatedTime()
                        : -1L,
                    lifting,
                    lifting
                        ? entity.workContainerUpState.getAccumulatedTime()
                        : -1L)) {
                super.render(pose, buffers, packedLight, entity, limbSwing,
                    limbSwingAmount, partialTick, ageInTicks, netHeadYaw,
                    headPitch);
            }
        }
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
        // The short happiness cue replaces the name plate rather than drawing
        // over its text or pushing the cue into a low Tavern ceiling.
        if (portraitLabelsHidden || entity.moraleJoyTicksRemaining() > 0
            || tavernCueVisible(entity)) return;
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
        // QA gate (owner, 26 Sep): vanilla's fixed 0.025 world scale makes the
        // tag fill the screen up close. Keep its apparent size no larger than
        // at NEAR_FULL_SIZE blocks; see nearLabelScale.
        float tagScale = 0.025F * nearLabelScale(
            entity.getPosition(partialTick).add(attach).add(0.0, 0.5, 0.0)
                .distanceTo(entityRenderDispatcher.camera.getPosition()));
        pose.scale(tagScale, -tagScale, tagScale);
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
        boolean roleLabel = profession.employed() || profession == Profession.MAYOR;
        if (roleLabel) {
            Component role = Component.literal(profession.displayName().getString()
                .toUpperCase(java.util.Locale.ROOT));
            status = targeted
                ? Component.empty().append(role)
                    .append(" · ").append(entity.getActivity().displayName())
                : role;
        } else if (targeted) {
            status = entity.getActivity().displayName();
        }
        boolean badge = roleLabel;

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
        renderTavernCue(entity, pose, buffers, packedLight);
        renderMoraleJoy(entity, partialTick, pose, buffers, packedLight);
        renderCraftPresentation(entity, partialTick, pose, buffers,
            packedLight);
        renderBagTransferPresentation(entity, partialTick, pose, buffers,
            packedLight);
        // The near-range thought bubble owns the space above the head while
        // shown (it carries the tool need too); the far equipment bubble
        // keeps covering everything beyond the thought bubble's range.
        // Living village: a short spoken line owns the space for a few seconds.
        boolean barkShown = SettlerBarkLabel.render(entity, pose, buffers, packedLight,
            getFont(), entityRenderDispatcher, portraitLabelsHidden || tavernCueVisible(entity));
        boolean thoughtShown = barkShown || SettlerThoughtBubble.render(entity, partialTick,
            pose, buffers, packedLight, getFont(), entityRenderDispatcher,
            portraitLabelsHidden || tavernCueVisible(entity));
        if (!thoughtShown) {
            renderEquipmentRequestBubble(entity, partialTick, pose, buffers,
                packedLight);
        }
    }

    /** One short depth-tested line replaces the name plate during a restaurant step. */
    private boolean tavernCueVisible(SettlerEntity entity) {
        double distance = entityRenderDispatcher.distanceToSqr(entity);
        return entity.tavernCue() != com.hearthstead.entity.TavernCue.NONE
            && !portraitLabelsHidden && !entity.isInvisible() && distance <= 12 * 12
            && (distance <= 6 * 6 || Minecraft.getInstance().crosshairPickEntity == entity);
    }
    private void renderTavernCue(SettlerEntity entity, PoseStack pose,
                                MultiBufferSource buffers, int light) {
        var cue = entity.tavernCue();
        double distance = entityRenderDispatcher.distanceToSqr(entity);
        if (!tavernCueVisible(entity)) return;
        Component text = cue == com.hearthstead.entity.TavernCue.QUOTED
            ? entity.tavernQuotedCoins() == 0 ? Component.translatable("hearthstead.tavern.cue.included")
                : Component.translatable(cue.translationKey(), com.hearthstead.util.CoinText.coins(entity.tavernQuotedCoins()))
            : Component.translatable(cue.translationKey());
        Font font = getFont();
        float width = font.width(text);
        float scale = .025F * Math.min(1F, 116F / Math.max(1F, width));
        float fade = Mth.clamp((float) ((12 - Math.sqrt(distance)) / 2), 0, 1);
        pose.pushPose();
        pose.translate(0, entity.getBbHeight() + .48, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(scale, -scale, scale);
        VertexConsumer plate = buffers.getBuffer(RenderType.textBackground());
        fillQuad(plate, pose, -width / 2 - 4, width / 2 + 4, -3, 11,
            withAlpha(0xFF89704B, fadeAlpha(0xEE, fade)), light);
        fillQuad(plate, pose, -width / 2 - 3, width / 2 + 3, -2, 10, -.001F,
            withAlpha(0xFF211E19, fadeAlpha(0xEE, fade)), light);
        pose.translate(0, 0, -.002);
        font.drawInBatch(text, -width / 2, 0, withAlpha(0xFFF8EDD8, fadeAlpha(0xFF, fade)), false,
            pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
    }

    /** Brief depth-tested pixel smile: a server-confirmed positive morale change. */
    private void renderMoraleJoy(SettlerEntity entity, float partial, PoseStack pose,
                                 MultiBufferSource buffers, int light) {
        int remaining = entity.moraleJoyTicksRemaining();
        if (remaining == 0 || portraitLabelsHidden || entity.isInvisible()
            || tavernCueVisible(entity)
            || !entity.isAlive() || entityRenderDispatcher.distanceToSqr(entity) > 24 * 24) return;
        float age = 40 - remaining + partial;
        float opacity = Math.min(1F, Math.min(age / 4F, (40 - age) / 10F));
        int alpha = Mth.clamp((int) (255 * opacity), 0, 255) << 24;
        if (alpha == 0) return;
        pose.pushPose();
        pose.translate(0, entity.getBbHeight() + .55 + age * .004, 0);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(.025F, -.025F, .025F);
        VertexConsumer pixels = buffers.getBuffer(RenderType.textBackground());
        // Original 12-pixel face geometry, with a clipped round silhouette.
        fillQuad(pixels, pose, -4, 4, -6, 6, alpha | 0x426B28, light);
        fillQuad(pixels, pose, -6, 6, -4, 4, alpha | 0x426B28, light);
        fillQuad(pixels, pose, -4, 4, -5, 5, -.001F, alpha | 0xA3D85D, light);
        fillQuad(pixels, pose, -5, 5, -3, 3, -.001F, alpha | 0xA3D85D, light);
        fillQuad(pixels, pose, -3, -1, -3, -1, -.002F, alpha | 0x243520, light);
        fillQuad(pixels, pose, 1, 3, -3, -1, -.002F, alpha | 0x243520, light);
        fillQuad(pixels, pose, -3, -2, 1, 3, -.002F, alpha | 0x243520, light);
        fillQuad(pixels, pose, 2, 3, 1, 3, -.002F, alpha | 0x243520, light);
        fillQuad(pixels, pose, -2, 2, 3, 4, -.002F, alpha | 0x243520, light);
        pose.popPose();
    }

    /**
     * Draws the exact server-named one-count stack from hand contact until
     * chest contact. It is world-positioned, so neither locomotion nor body
     * yaw can drag the item or its grounded sack truth around.
     */
    private void renderBagTransferPresentation(SettlerEntity entity,
                                               float partialTick,
                                               PoseStack pose,
                                               MultiBufferSource buffers,
                                               int packedLight) {
        BagTransferPresentation transfer = entity.bagTransferPresentation();
        if (!transfer.active() || transfer.bagAnchor() == null
            || transfer.containerPos() == null || transfer.committed()
            || transfer.clock() < 30 || transfer.clock() > 48) return;
        if (transfer.sourcePickup()) {
            var point = transfer.sourceUnitPosition(transfer.clock() + partialTick);
            renderFixedCraftItem(entity, transfer.item(), pose, buffers, packedLight,
                entity.getPosition(partialTick), point.x, point.y, point.z,
                transfer.bagYaw(), .62F, 911);
            return;
        }
        // Start at the drawn sack mouth (forward-left of the planted worker),
        // not the anchor block centre under the worker's feet.
        Vec3 bag = transfer.visualSackPoint();
        BlockPos chest = transfer.containerPos();
        float clock = transfer.clock() + partialTick;
        float progress = Mth.clamp((clock - 30.0F) / 18.0F, 0.0F, 1.0F);
        progress = progress * progress * (3.0F - 2.0F * progress);
        double x = Mth.lerp(progress, bag.x, chest.getX() + 0.5D);
        double y = Mth.lerp(progress, bag.y + 0.72D, chest.getY() + 1.05D);
        double z = Mth.lerp(progress, bag.z, chest.getZ() + 0.5D);
        float spin = transfer.bagYaw();
        float itemScale = 0.62F;
        Vec3 hand = getModel().transferHandWorld(entity, partialTick);
        if (hand != null && com.hearthstead.client.motion.MotionSettings.engineEnabled()) {
            // One beat per unit: the carrying palm holds the exact server-named
            // item from the grab (tick 30) to over the open chest (tick 45),
            // then lets it drop in a short spinning arc into the chest mouth
            // just as the server commits it (tick 48). Display only.
            itemScale = 0.42F;
            if (clock < 45.0F) {
                x = hand.x;
                y = hand.y - 0.06D;
                z = hand.z;
            } else {
                float drop = Mth.clamp((clock - 45.0F) / 3.0F, 0.0F, 1.0F);
                double ex = chest.getX() + 0.5D, ey = chest.getY() + 0.62D, ez = chest.getZ() + 0.5D;
                x = Mth.lerp(drop, hand.x, ex);
                z = Mth.lerp(drop, hand.z, ez);
                y = Mth.lerp(drop * drop, hand.y - 0.06D, ey) + 0.12D * Math.sin(Math.PI * drop);
                spin += 220.0F * drop;
            }
        }
        renderFixedCraftItem(entity, transfer.item(), pose, buffers,
            packedLight, entity.getPosition(partialTick), x, y, z,
            spin, itemScale, 911);
        if (transfer.item().getCount() > 1) {
            renderTransferCount(entity, transfer.item().getCount(), pose,
                buffers, packedLight, partialTick, x, y + 0.34D, z);
        }
    }

    /** Exact bundle count; without this label one rendered item would lie. */
    private void renderTransferCount(SettlerEntity entity, int count,
                                     PoseStack pose, MultiBufferSource buffers,
                                     int packedLight, float partialTick,
                                     double worldX, double worldY,
                                     double worldZ) {
        Vec3 entityPosition = entity.getPosition(partialTick);
        String label = "×" + count;
        Font font = Minecraft.getInstance().font;
        pose.pushPose();
        pose.translate(worldX - entityPosition.x, worldY - entityPosition.y,
            worldZ - entityPosition.z);
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        pose.scale(TRANSFER_COUNT_SCALE, -TRANSFER_COUNT_SCALE,
            TRANSFER_COUNT_SCALE);
        float x = -font.width(label) / 2.0F;
        float halfWidth = font.width(label) / 2.0F + TRANSFER_COUNT_PAD_X;
        VertexConsumer plate = buffers.getBuffer(
            RenderType.textBackgroundSeeThrough());
        fillQuad(plate, pose, -halfWidth - 1.0F, halfWidth + 1.0F,
            -TRANSFER_COUNT_PAD_Y - 1.0F,
            font.lineHeight + TRANSFER_COUNT_PAD_Y + 1.0F,
            TRANSFER_COUNT_RIM, packedLight);
        fillQuad(plate, pose, -halfWidth, halfWidth,
            -TRANSFER_COUNT_PAD_Y,
            font.lineHeight + TRANSFER_COUNT_PAD_Y,
            TRANSFER_COUNT_FILL, packedLight);
        font.drawInBatch(label, x, 0.0F, TRANSFER_COUNT_TEXT, true,
            pose.last().pose(), buffers, Font.DisplayMode.NORMAL,
            0, packedLight);
        pose.popPose();
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
        if (requested.isEmpty() || distanceSqr > REQUEST_BUBBLE_RANGE_SQ
            || tavernCueVisible(entity)) {
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
