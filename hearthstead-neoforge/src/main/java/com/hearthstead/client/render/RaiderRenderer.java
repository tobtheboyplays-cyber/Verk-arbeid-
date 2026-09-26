package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.RaiderModel;
import com.hearthstead.client.model.GoblinThiefModel;
import net.minecraft.client.model.HierarchicalModel;
import com.hearthstead.entity.RaiderEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a raider. Captains are visibly larger as well as differently
 * equipped: the point is that you can read who is leading a raid from
 * across the field rather than by hitting them and watching the health bar.
 *
 * <p>Texture selection is the full cross product of
 * {@link RaiderEntity.Variant} x {@code isCaptain()} x
 * {@code isSagaMarked()} (tools/gen_raider.py owns the paint job for every
 * cell of that matrix; this class only picks one). See
 * {@link RaiderEntity.Variant}'s own javadoc for why SKIRMISHER and BRUTE
 * are the only two builds.
 */
public class RaiderRenderer extends MobRenderer<RaiderEntity, HierarchicalModel<RaiderEntity>> {
    public static final float BRUTE_RENDER_SCALE = 1.32F;
    public static final float CAPTAIN_RENDER_SCALE = 1.12F;
    private final RaiderModel ordinaryModel;
    private final GoblinThiefModel goblinModel;
    private static final ResourceLocation GOBLIN_TEXTURE = Hearthstead.id("textures/entity/raider/goblin_thief.png");

    private static final ResourceLocation SKIRMISHER_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider.png");
    private static final ResourceLocation SKIRMISHER_CAPTAIN_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider_captain.png");
    private static final ResourceLocation BRUTE_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider_brute.png");
    private static final ResourceLocation BRUTE_CAPTAIN_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider_brute_captain.png");
    /**
     * SAGA v1: a captain the settlement's named roster has actually seen
     * earn an epithet -- see {@code RaiderEntity#isSagaMarked}. Same rig,
     * same silhouette, a brass mark and face war-paint in place of the
     * plain captain's crimson (tools/gen_raider.py), so growth is readable
     * at a glance the same way the plain grunt/captain split already is.
     * One marked texture per build -- the epithet is earned by the captain
     * wearing it, not by the build.
     */
    private static final ResourceLocation SKIRMISHER_CAPTAIN_MARKED_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider_captain_marked.png");
    private static final ResourceLocation BRUTE_CAPTAIN_MARKED_TEXTURE =
        Hearthstead.id("textures/entity/raider/raider_brute_captain_marked.png");

    public RaiderRenderer(EntityRendererProvider.Context context) {
        super(context, new RaiderModel(context.bakeLayer(RaiderModel.LAYER)), 0.4F);
        ordinaryModel = (RaiderModel)this.model;
        goblinModel = new GoblinThiefModel(context.bakeLayer(GoblinThiefModel.LAYER));
        addLayer(new RaiderHeldItemLayer(this, context.getItemInHandRenderer()));
    }

    @Override
    protected boolean shouldShowName(RaiderEntity entity) {
        // A sneaking thief must be discovered in the world, not by a label
        // floating above cover. Ordinary raider nameplates remain unchanged.
        if (entity.isGoblinThiefDemo() && entity.isShiftKeyDown()) return false;
        return super.shouldShowName(entity);
    }

    @Override
    protected void renderNameTag(RaiderEntity entity, net.minecraft.network.chat.Component name,
                                 PoseStack pose, MultiBufferSource buffers, int packedLight,
                                 float partialTick) {
        super.renderNameTag(entity, name.copy().withStyle(net.minecraft.ChatFormatting.RED),
            pose, buffers, packedLight, partialTick);
    }

    @Override
    protected void scale(RaiderEntity entity, PoseStack pose, float partialTick) {
        float s = visualScale(entity);
        pose.scale(s, s, s);
    }

    /** Shared by renderer-attached presentation such as the overhead health bar. */
    public static float visualScale(RaiderEntity entity) {
        if (entity.isGoblinThiefDemo()) return 1.0F;
        float scale = entity.variant() == RaiderEntity.Variant.BRUTE
            ? BRUTE_RENDER_SCALE : 1.0F;
        return entity.isCaptain() ? scale * CAPTAIN_RENDER_SCALE : scale;
    }

    @Override
    public void render(RaiderEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        this.model = entity.isGoblinThiefDemo() ? goblinModel : ordinaryModel;
        this.model.attackTime = entity.getAttackAnim(partialTick);
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(RaiderEntity entity) {
        // Skins lane: per-UUID variants, saga-captain looks ([features] characterSkins).
        ResourceLocation look = com.hearthstead.client.look.RaiderLook.texture(entity);
        if (look != null) return look;
        if (entity.isGoblinThiefDemo()) return GOBLIN_TEXTURE;
        boolean captain = entity.isCaptain();
        boolean marked = captain && entity.isSagaMarked();
        return switch (entity.variant()) {
            // Bandits normally wear the skins lane's road-bandit looks (RaiderLook);
            // with characterSkins off they fall back to the human skirmisher skin.
            case SKIRMISHER, BANDIT -> marked ? SKIRMISHER_CAPTAIN_MARKED_TEXTURE
                : captain ? SKIRMISHER_CAPTAIN_TEXTURE
                : SKIRMISHER_TEXTURE;
            case BRUTE -> marked ? BRUTE_CAPTAIN_MARKED_TEXTURE
                : captain ? BRUTE_CAPTAIN_TEXTURE
                : BRUTE_TEXTURE;
        };
    }

    /** Actual equipped items take precedence over ordinary model role props. */
    private static final class RaiderHeldItemLayer
            extends RenderLayer<RaiderEntity, HierarchicalModel<RaiderEntity>> {
        private final ItemInHandRenderer items;

        private RaiderHeldItemLayer(
                RenderLayerParent<RaiderEntity, HierarchicalModel<RaiderEntity>> parent,
                ItemInHandRenderer items) {
            super(parent);
            this.items = items;
        }

        @Override public void render(PoseStack pose, MultiBufferSource buffers, int packedLight,
                                     RaiderEntity entity, float limbSwing, float limbAmount,
                                     float partialTick, float ageInTicks, float netHeadYaw,
                                     float headPitch) {
            if (!entity.isGoblinThiefDemo()
                    && getParentModel() instanceof RaiderModel raider) {
                boolean rightMain = entity.getMainArm() == HumanoidArm.RIGHT;
                renderOrdinaryHand(raider, entity, HumanoidArm.RIGHT,
                    rightMain ? entity.getMainHandItem() : entity.getOffhandItem(),
                    pose, buffers, packedLight);
                renderOrdinaryHand(raider, entity, HumanoidArm.LEFT,
                    rightMain ? entity.getOffhandItem() : entity.getMainHandItem(),
                    pose, buffers, packedLight);
                return;
            }
            if (!entity.isGoblinThiefDemo()
                    || !(getParentModel() instanceof GoblinThiefModel goblin)) return;
            ItemStack held = entity.getMainHandItem();
            if (held.isEmpty()) return;
            pose.pushPose();
            goblin.translateToHand(HumanoidArm.RIGHT, pose);
            pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            pose.translate(1.0F / 16.0F, .125F, -.625F);
            items.renderItem(entity, held, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false, pose, buffers, packedLight);
            pose.popPose();
        }

        private void renderOrdinaryHand(RaiderModel raider, RaiderEntity entity,
                                        HumanoidArm side, ItemStack held,
                                        PoseStack pose, MultiBufferSource buffers,
                                        int packedLight) {
            if (held.isEmpty()) return;
            boolean left = side == HumanoidArm.LEFT;
            pose.pushPose();
            raider.translateToHand(side, pose);
            pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            pose.translate((left ? -1.0F : 1.0F) / 16.0F, .125F, -.625F);
            items.renderItem(entity, held, left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                    : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                left, pose, buffers, packedLight);
            pose.popPose();
        }
    }
}
