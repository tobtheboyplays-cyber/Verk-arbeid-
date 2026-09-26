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
        if (working) {
            drawTool(actor, KNIFE, pose, buffers, light);
        }
        ItemStack carcass = actor.getOffhandItem();
        if (!CarcassItem.isCarcass(carcass)) {
            return;
        }
        pose.pushPose();
        if (working) {
            // Field dressing: the body lies on the floor in front of his feet.
            pose.translate(0.0D, 1.42D, -0.75D);
        } else {
            // Across the shoulders, behind the neck (layer space: +y down, +z back).
            pose.translate(0.0D, -0.08D, 0.2D);
        }
        // Back to world orientation (Y up) for the entity model.
        pose.scale(-1.0F, -1.0F, 1.0F);
        CarcassDisplay.render(carcass, pose, buffers, light);
        pose.popPose();
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
