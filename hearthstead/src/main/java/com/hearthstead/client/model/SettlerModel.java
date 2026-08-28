package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Sturdy medieval settler: broad 10-wide torso, hood, shoulder cape, belt and
 * backpack as separately animated parts. Texture atlas is 128x64; the UV
 * table here is mirrored exactly by tools/gen_settler.py.
 */
public class SettlerModel extends HierarchicalModel<SettlerEntity> implements ArmedModel {
    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(Hearthstead.id("settler"), "main");

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart hood;
    private final ModelPart hatBrim;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public SettlerModel(ModelPart root) {
        this.root = root.getChild("root");
        this.torso = this.root.getChild("torso");
        this.head = torso.getChild("head");
        this.hood = head.getChild("hood");
        this.hatBrim = head.getChild("hat_brim");
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
        this.rightLeg = this.root.getChild("right_leg");
        this.leftLeg = this.root.getChild("left_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition base = mesh.getRoot();
        PartDefinition root = base.addOrReplaceChild("root", CubeListBuilder.create(),
            PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create()
                .texOffs(64, 0).addBox(-5.0F, -12.0F, -2.5F, 10.0F, 12.0F, 5.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));

        PartDefinition head = torso.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        head.addOrReplaceChild("hood", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                    new CubeDeformation(0.6F)),
            PartPose.ZERO);
        head.addOrReplaceChild("hat_brim", CubeListBuilder.create()
                .texOffs(64, 44).addBox(-6.0F, -5.0F, -6.0F, 12.0F, 1.0F, 12.0F),
            PartPose.ZERO);

        torso.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-6.0F, -10.0F, 0.0F));
        torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(6.0F, -10.0F, 0.0F));

        torso.addOrReplaceChild("cloak", CubeListBuilder.create()
                .texOffs(64, 32).addBox(-5.5F, 0.0F, -3.0F, 11.0F, 4.0F, 6.0F,
                    new CubeDeformation(0.2F)),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        torso.addOrReplaceChild("backpack", CubeListBuilder.create()
                .texOffs(96, 0).addBox(-3.0F, -9.0F, 2.5F, 6.0F, 7.0F, 3.0F),
            PartPose.ZERO);
        torso.addOrReplaceChild("belt", CubeListBuilder.create()
                .texOffs(96, 20).addBox(-5.0F, -5.0F, -2.5F, 10.0F, 2.0F, 5.0F,
                    new CubeDeformation(0.3F)),
            PartPose.ZERO);

        root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                .texOffs(32, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-2.6F, -12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                .texOffs(48, 32).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(2.6F, -12.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 64);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(SettlerEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root().getAllParts().forEach(ModelPart::resetPose);

        // Profession silhouette: hood up, straw brim, or bare head.
        Profession profession = entity.getProfession();
        hood.visible = profession == Profession.NONE || profession == Profession.GUARD;
        hatBrim.visible = profession == Profession.FARMER;

        animateWalk(SettlerAnimations.WALK, limbSwing, limbSwingAmount, 2.0F, 2.5F);

        animate(entity.idleState, SettlerAnimations.IDLE, ageInTicks);
        animate(entity.farmState, SettlerAnimations.FARM, ageInTicks);
        animate(entity.chopState, SettlerAnimations.CHOP, ageInTicks);
        animate(entity.eatState, SettlerAnimations.EAT, ageInTicks);
        animate(entity.restState, SettlerAnimations.REST, ageInTicks);
        animate(entity.stanceState, SettlerAnimations.GUARD_STANCE, ageInTicks);
        animate(entity.meleeState, SettlerAnimations.MELEE, ageInTicks);
        animate(entity.celebrateState, SettlerAnimations.CELEBRATE, ageInTicks);

        // Head tracking layers additively over the keyframes, damped while
        // the settler is absorbed in a meal or dozing at the fire.
        SettlerActivity activity = entity.getActivity();
        float damp = activity == SettlerActivity.RESTING
            || activity == SettlerActivity.EATING ? 0.25F : 1.0F;
        head.yRot += Mth.clamp(netHeadYaw, -60.0F, 60.0F) * ((float) Math.PI / 180F) * damp;
        head.xRot += headPitch * ((float) Math.PI / 180F) * damp;

        // Procedural hurt flinch.
        if (entity.hurtTime > 0) {
            float progress = (float) entity.hurtTime / 10.0F;
            torso.xRot += Mth.sin(progress * (float) Math.PI) * 0.15F;
        }
    }

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
    }
}
