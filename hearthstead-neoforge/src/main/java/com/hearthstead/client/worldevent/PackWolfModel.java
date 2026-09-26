package com.hearthstead.client.worldevent;

import com.hearthstead.event.worldevent.PackWolfEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Night-wolf model on the vanilla wolf geometry (baked from the vanilla
 * wolf layer, so no mesh is duplicated). Always the standing, hackles-up
 * pose: head low, tail straight out.
 */
public class PackWolfModel extends HierarchicalModel<PackWolfEntity> {
    private final ModelPart root;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart upperBody;
    private final ModelPart rightHindLeg;
    private final ModelPart leftHindLeg;
    private final ModelPart rightFrontLeg;
    private final ModelPart leftFrontLeg;
    private final ModelPart tail;

    public PackWolfModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.body = root.getChild("body");
        this.upperBody = root.getChild("upper_body");
        this.rightHindLeg = root.getChild("right_hind_leg");
        this.leftHindLeg = root.getChild("left_hind_leg");
        this.rightFrontLeg = root.getChild("right_front_leg");
        this.leftFrontLeg = root.getChild("left_front_leg");
        this.tail = root.getChild("tail");
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(PackWolfEntity wolf, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        body.setPos(0.0F, 14.0F, 2.0F);
        body.xRot = (float) (Math.PI / 2);
        upperBody.setPos(-1.0F, 14.0F, -3.0F);
        upperBody.xRot = body.xRot;
        tail.setPos(-1.0F, 12.0F, 8.0F);
        rightHindLeg.setPos(-2.5F, 16.0F, 7.0F);
        leftHindLeg.setPos(0.5F, 16.0F, 7.0F);
        rightFrontLeg.setPos(-2.5F, 16.0F, -4.0F);
        leftFrontLeg.setPos(0.5F, 16.0F, -4.0F);
        float swing = Mth.cos(limbSwing * 0.6662F) * 1.4F * limbSwingAmount;
        rightHindLeg.xRot = swing;
        leftHindLeg.xRot = -swing;
        rightFrontLeg.xRot = -swing;
        leftFrontLeg.xRot = swing;
        // Stalking: head a little low; the tail stiff behind.
        head.xRot = headPitch * Mth.DEG_TO_RAD + 0.15F;
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        tail.xRot = 1.25F;
        tail.yRot = 0.0F;
    }
}
