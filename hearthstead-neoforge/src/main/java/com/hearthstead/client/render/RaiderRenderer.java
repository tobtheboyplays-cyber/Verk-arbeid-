package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.RaiderModel;
import com.hearthstead.entity.RaiderEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

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
public class RaiderRenderer extends MobRenderer<RaiderEntity, RaiderModel> {

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
    }

    @Override
    protected void scale(RaiderEntity entity, PoseStack pose, float partialTick) {
        float s = entity.isCaptain() ? 1.12F : 1.0F;
        pose.scale(s, s, s);
    }

    @Override
    public void render(RaiderEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        this.model.attackTime = entity.getAttackAnim(partialTick);
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(RaiderEntity entity) {
        boolean captain = entity.isCaptain();
        boolean marked = captain && entity.isSagaMarked();
        return switch (entity.variant()) {
            case SKIRMISHER -> marked ? SKIRMISHER_CAPTAIN_MARKED_TEXTURE
                : captain ? SKIRMISHER_CAPTAIN_TEXTURE
                : SKIRMISHER_TEXTURE;
            case BRUTE -> marked ? BRUTE_CAPTAIN_MARKED_TEXTURE
                : captain ? BRUTE_CAPTAIN_TEXTURE
                : BRUTE_TEXTURE;
        };
    }
}
