package com.hearthstead.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * Draws a dropped item the way it actually behaves once it stops moving: it
 * LIES on the ground rather than hovering and spinning over it.
 *
 * <p>Replaces {@link ItemEntityRenderer} (vanilla's own renderer for
 * {@code EntityType.ITEM}) for every ground item in the world. Vanilla's
 * renderer always applies a sine-wave bob and a continuous yaw spin
 * ({@code ItemEntity#getSpin}), on top of an item model that, for flat
 * (2D-sprite / "generated") items, stands the sprite upright — so a dropped
 * sapling floats a few pixels up and pinwheels forever. None of that reads
 * as an object at rest.
 *
 * <p>Once {@link ItemEntity#onGround()} is true this renderer instead:
 * <ul>
 *   <li>drops the bob and the spin entirely;</li>
 *   <li>for a flat sprite model ({@code BakedModel#isGui3d() == false}),
 *   tips it ~90&deg; around the local X axis so the sprite lies face-up,
 *   parallel to the ground, at a per-entity deterministic compass heading;</li>
 *   <li>for a 3D block model ({@code isGui3d() == true}), keeps it upright
 *   as a small resting block with the same deterministic heading and an
 *   optional hair of settle-tilt;</li>
 *   <li>renders a big stack's extra copies (vanilla's {@code getRenderedAmount}
 *   staircase) as a small flat pile fanned out around the drop point instead
 *   of vanilla's floating column.</li>
 * </ul>
 * While the entity is still falling or drifting in water
 * ({@code !onGround()}), this defers to vanilla's own bob/spin/column-stack
 * math (reusing {@link ItemEntityRenderer}'s public statics) — an item that
 * has not settled yet is allowed to still look like it is in motion.
 *
 * <p>All per-entity variation (heading, ground-clearance epsilon, settle
 * tilt, fan placement) is derived from {@link ItemEntity#getId()} through a
 * fixed integer hash — no {@link RandomSource} is created or reseeded on the
 * resting path, so two saplings dropped in the same spot never scatter
 * identically, and nothing here allocates or churns per frame beyond what
 * {@link ItemRenderer#render} itself already does to draw the model.
 */
public class GroundedItemRenderer extends EntityRenderer<ItemEntity> {

    /** How far a flat (2D sprite) item's face sits above the ground, in blocks — a couple of pixels. */
    private static final float FLAT_GROUND_CLEARANCE = 0.03F;
    /** Extra per-entity lift on top of the base clearance, so coincident flat drops don't share a plane. */
    private static final float GROUND_EPSILON_SCALE = 0.015F;
    /** Extra lift added per stacked copy beyond the first, purely to break coplanarity within one pile. */
    private static final float STACK_Y_STEP = 0.0025F;
    /** Rotation around the (already-yawed) local X axis that lays a flat sprite down on its back. */
    private static final float FLAT_LIE_DEGREES = -90.0F;
    /** Maximum settle tilt applied to a resting block item, split across two axes; small enough to never clip. */
    private static final float BLOCK_TILT_MAX_DEGREES = 3.0F;
    /** Horizontal radius of the fan/pile pattern used for a big stack's extra rendered copies. */
    private static final float FAN_RADIUS = 0.09F;
    /** Extra yaw applied to each successive fanned-out copy, on top of the entity's base heading. */
    private static final float FAN_YAW_JITTER_DEGREES = 50.0F;

    // Distinct salts so the same entity id doesn't produce correlated values
    // for unrelated purposes (heading vs. epsilon vs. tilt vs. fan placement).
    private static final int SALT_YAW = 0x27D4EB2D;
    private static final int SALT_EPSILON = 0x165667B1;
    private static final int SALT_TILT_X = 0x85EBCA77;
    private static final int SALT_TILT_Z = 0xC2B2AE35;
    private static final int SALT_FAN_ANGLE = 0x1B873593;
    private static final int SALT_FAN_RADIUS = 0x27D4EB4F;
    private static final int SALT_FAN_YAW = 0x9E3779B1;
    /** Combined with the copy index so each fanned-out copy gets its own draw from the same entity id. */
    private static final int COPY_MIX = 0x1000193;

    private final ItemRenderer itemRenderer;
    /**
     * Only touched on the airborne (not-on-ground) fallback path, exactly as
     * vanilla's own {@code ItemEntityRenderer} uses its instance field: reseeded
     * from the item stack every call, never advanced tick-to-tick.
     */
    private final RandomSource airborneRandom = RandomSource.create();

    public GroundedItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
        this.shadowRadius = 0.15F;
        this.shadowStrength = 0.75F;
    }

    @Override
    public ResourceLocation getTextureLocation(ItemEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    @Override
    public void render(ItemEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                        MultiBufferSource buffers, int packedLight) {
        ItemStack stack = entity.getItem();
        if (!stack.isEmpty()) {
            poseStack.pushPose();
            BakedModel model = this.itemRenderer.getModel(stack, entity.level(), null, entity.getId());
            boolean gui3d = model.isGui3d();
            if (entity.onGround()) {
                renderResting(entity, stack, model, !gui3d, poseStack, buffers, packedLight);
            } else {
                renderAirborne(entity, partialTick, stack, model, gui3d, poseStack, buffers, packedLight);
            }
            poseStack.popPose();
        }
        // Nameplate / other generic entity decorations, drawn outside the
        // item's own local transform exactly as vanilla's renderer does.
        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    // ------------------------------------------------------------------
    // Resting on the ground: no bob, no spin, deterministic per-entity pose.
    // ------------------------------------------------------------------

    private void renderResting(ItemEntity entity, ItemStack stack, BakedModel model, boolean flat,
                                PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        int id = entity.getId();
        boolean spread = IClientItemExtensions.of(stack).shouldSpreadAsEntity(stack);
        int copies = renderedAmountFor(stack.getCount());
        float baseYaw = unitFloat(id ^ SALT_YAW) * 360.0F;
        float clearance = (flat ? FLAT_GROUND_CLEARANCE : blockClearance(model))
            + unitFloat(id ^ SALT_EPSILON) * GROUND_EPSILON_SCALE;

        float yaw = baseYaw;
        for (int copy = 0; copy < copies; copy++) {
            poseStack.pushPose();
            poseStack.translate(0.0F, clearance + copy * STACK_Y_STEP, 0.0F);
            if (spread && copy > 0) {
                int copySeed = id ^ (copy * COPY_MIX);
                float angle = unitFloat(copySeed ^ SALT_FAN_ANGLE) * (float) (Math.PI * 2.0);
                float radius = FAN_RADIUS * unitFloat(copySeed ^ SALT_FAN_RADIUS);
                poseStack.translate(Mth.cos(angle) * radius, 0.0F, Mth.sin(angle) * radius);
                yaw += (unitFloat(copySeed ^ SALT_FAN_YAW) - 0.5F) * FAN_YAW_JITTER_DEGREES;
            }
            // Heading is always applied last (called first — PoseStack.mulPose
            // right-multiplies, so the first call ends up outermost) so it
            // reads as a true compass rotation regardless of the lie/tilt
            // rotations composed under it.
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            if (flat) {
                poseStack.mulPose(Axis.XP.rotationDegrees(FLAT_LIE_DEGREES));
            } else {
                float tiltX = (unitFloat(id ^ SALT_TILT_X) - 0.5F) * BLOCK_TILT_MAX_DEGREES;
                float tiltZ = (unitFloat(id ^ SALT_TILT_Z) - 0.5F) * BLOCK_TILT_MAX_DEGREES;
                poseStack.mulPose(Axis.XP.rotationDegrees(tiltX));
                poseStack.mulPose(Axis.ZP.rotationDegrees(tiltZ));
            }
            this.itemRenderer.render(stack, ItemDisplayContext.GROUND, false, poseStack, buffers, packedLight,
                OverlayTexture.NO_OVERLAY, model);
            poseStack.popPose();
        }
    }

    /** Mirrors vanilla's own non-bob baseline lift (0.25 * the model's GROUND scale.y) so a block item's own transform still clears the ground. */
    private static float blockClearance(BakedModel model) {
        float groundScaleY = model.getTransforms().getTransform(ItemDisplayContext.GROUND).scale.y();
        return 0.25F * groundScaleY;
    }

    /** Mirrors vanilla's package-private {@code ItemEntityRenderer#getRenderedAmount}, which is not accessible from here. */
    private static int renderedAmountFor(int count) {
        if (count <= 1) {
            return 1;
        } else if (count <= 16) {
            return 2;
        } else if (count <= 32) {
            return 3;
        } else {
            return count <= 48 ? 4 : 5;
        }
    }

    // ------------------------------------------------------------------
    // Falling / swimming: not settled yet, so vanilla's own motion reads
    // as correct rather than jarring. Reuses vanilla's public statics
    // instead of re-deriving the same bob/spin/column-stack math.
    // ------------------------------------------------------------------

    private void renderAirborne(ItemEntity entity, float partialTick, ItemStack stack, BakedModel model,
                                 boolean gui3d, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        this.airborneRandom.setSeed(ItemEntityRenderer.getSeedForItemStack(stack));
        boolean shouldBob = IClientItemExtensions.of(stack).shouldBobAsEntity(stack);
        float bob = shouldBob
            ? Mth.sin(((float) entity.getAge() + partialTick) / 10.0F + entity.bobOffs) * 0.1F + 0.1F
            : 0.0F;
        float groundScaleY = model.getTransforms().getTransform(ItemDisplayContext.GROUND).scale.y();
        poseStack.translate(0.0F, bob + 0.25F * groundScaleY, 0.0F);
        poseStack.mulPose(Axis.YP.rotation(entity.getSpin(partialTick)));
        ItemEntityRenderer.renderMultipleFromCount(
            this.itemRenderer, poseStack, buffers, packedLight, stack, model, gui3d, this.airborneRandom);
    }

    // ------------------------------------------------------------------
    // Deterministic per-entity variation, derived from Entity#getId() —
    // no RandomSource, no per-frame churn beyond int/float arithmetic.
    // ------------------------------------------------------------------

    /** A fixed-output 32-bit integer hash (the public-domain "triple32" mixer); deterministic and allocation-free. */
    private static int hash(int seed) {
        int x = seed;
        x = ((x >>> 16) ^ x) * 0x45D9F3B;
        x = ((x >>> 16) ^ x) * 0x45D9F3B;
        x = (x >>> 16) ^ x;
        return x;
    }

    /** {@code hash(seed)} folded into [0, 1). */
    private static float unitFloat(int seed) {
        return (hash(seed) & 0xFFFFFF) / (float) 0x1000000;
    }
}
