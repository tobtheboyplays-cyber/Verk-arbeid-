package com.hearthstead.client.render;

import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.CarcassItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Hunter's carcass, drawn from the settler's real synced OFFHAND stack:
 * slung across the shoulders behind the neck while he carries it, and lying
 * on the floor in front of him while he dresses it there (no table). While
 * skinning/jointing he visibly holds his hunter's knife; this is a
 * presentation prop only (his bow stays the server-side MAINHAND item and is
 * hidden by SettlerRenderer for these activities).
 */
public final class CarcassCarryLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    /** The hunter's knife (reference sheet): an iron blade drawn at 70% size. */
    private static final ItemStack KNIFE = new ItemStack(Items.IRON_SWORD);
    /**
     * Hunter v2 (anim lane): the kill rides the TORSO (it used to float fixed in entity space while the walk
     * bobbed under it). HUNTER_TAKE_KILL lifts it along this path -- the same keys the clip's hands follow --
     * {seconds since EV_PICKUP, torso-local px x, y, z}; the last key is the shoulder rest HUNTER_HAUL holds.
     */
    private static final float[][] HUNTER_LIFT = {
        {0.55F, 0.0F, -4.09F, -10.89F},
        {0.78F, 0.0F, -8.08F, -6.87F},
        {0.95F, 0.0F, -22.71F, 0.71F},
        {1.1F, 0.0F, -13.3F, 3.2F}};
    private final ItemInHandRenderer items;

    public CarcassCarryLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent,
                             ItemInHandRenderer items) {
        super(parent);
        this.items = items;
    }

    /** True for the three Hunter activities whose hands belong to the carcass. */
    public static boolean ownsHands(SettlerActivity activity) {
        return activity == SettlerActivity.HAULING_CARCASS
            || activity == SettlerActivity.WORK_BUTCHER
            || activity == SettlerActivity.WORK_SKIN;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, SettlerEntity actor,
                       float limbSwing, float limbSwingAmount, float partialTick, float age,
                       float yaw, float pitch) {
        SettlerActivity activity = actor.getActivity();
        boolean working = activity == SettlerActivity.WORK_BUTCHER
            || activity == SettlerActivity.WORK_SKIN;
        boolean hunterV2 = actor.getProfession() == com.hearthstead.entity.Profession.HUNTER
            && com.hearthstead.client.motion.MotionSettings.engineEnabled();
        // One blade per hand: HUNTER_BUTCHER_KNEEL's cleaver prop stows the knife.
        if (working && !(hunterV2 && MotionPropLayer.hidesReal(getParentModel(), actor, HumanoidArm.RIGHT))) {
            drawTool(actor, KNIFE, pose, buffers, light);
        }
        ItemStack carcass = actor.getOffhandItem();
        if (!CarcassItem.isCarcass(carcass)) {
            return;
        }
        pose.pushPose();
        if (working) {
            // Field dressing: the body lies on the floor in front of his feet (v2: in the kneeling reach).
            pose.translate(0.0D, 1.42D, hunterV2 ? -0.65D : -0.75D);
        } else if (hunterV2) {
            getParentModel().translateToTorso(pose);
            float[] p = hunterLift(actor);
            pose.translate(p[0] / 16.0F, p[1] / 16.0F, p[2] / 16.0F);
        } else {
            // Across the shoulders, behind the neck (layer space: +y down, +z back).
            pose.translate(0.0D, -0.08D, 0.2D);
        }
        // Back to world orientation (Y up) for the entity model.
        pose.scale(-1.0F, -1.0F, 1.0F);
        CarcassDisplay.render(carcass, pose, buffers, light);
        pose.popPose();
    }

    /** Torso-local px of the kill: along HUNTER_LIFT while EV_PICKUP runs, else the shoulder rest. */
    private static float[] hunterLift(SettlerEntity actor) {
        float[] rest = HUNTER_LIFT[HUNTER_LIFT.length - 1];
        if (!actor.pickupState.isStarted()) {
            return new float[] {rest[1], rest[2], rest[3]};
        }
        float t = actor.pickupState.getAccumulatedTime() / 1000.0F;
        for (int i = 1; i < HUNTER_LIFT.length; i++) {
            float[] a = HUNTER_LIFT[i - 1];
            float[] b = HUNTER_LIFT[i];
            if (t <= b[0]) {
                float u = Math.max(0.0F, Math.min(1.0F, (t - a[0]) / (b[0] - a[0])));
                u = u * u * (3.0F - 2.0F * u);
                return new float[] {a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u, a[3] + (b[3] - a[3]) * u};
            }
        }
        return new float[] {rest[1], rest[2], rest[3]};
    }

    private void drawTool(SettlerEntity actor, ItemStack tool, PoseStack pose,
                          MultiBufferSource buffers, int light) {
        pose.pushPose();
        getParentModel().translateToHand(HumanoidArm.RIGHT, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(1.0F / 16.0F, 0.125F, -0.625F);
        pose.scale(0.7F, 0.7F, 0.7F);
        items.renderItem(actor, tool, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false,
            pose, buffers, light);
        pose.popPose();
    }
}
