package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
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

/**
 * The raider rig: lean, hooded and hunched, deliberately unlike the
 * settler's broad, cloaked build.
 *
 * <p>Silhouette is the whole point. Both reference mods field raiders that
 * players cannot tell apart from each other or from their own guards —
 * MineColonies' own design intent is that raiders be "similar to guards",
 * and the resulting complaint is that "chief raiders don't even stand out".
 * A captain here is a different shape at fifty blocks: pauldron, helm, and a
 * taller stance.
 *
 * <p>Texture atlas is 64x64; the UV table is mirrored by tools/gen_raider.py.
 */
public class RaiderModel extends HierarchicalModel<RaiderEntity> {
    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(Hearthstead.id("raider"), "main");

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart hood;
    private final ModelPart helm;
    private final ModelPart pauldron;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    /** How far a raider leans into their advance. They do not stroll. */
    private static final float PROWL_LEAN = 0.14F;

    public RaiderModel(ModelPart root) {
        this.root = root.getChild("root");
        this.torso = this.root.getChild("torso");
        this.head = torso.getChild("head");
        this.hood = head.getChild("hood");
        this.helm = head.getChild("helm");
        this.pauldron = torso.getChild("pauldron");
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
        this.rightLeg = this.root.getChild("right_leg");
        this.leftLeg = this.root.getChild("left_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition base = mesh.getRoot();
        PartDefinition rootPart = base.addOrReplaceChild("root",
            CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition torso = rootPart.addOrReplaceChild("torso",
            CubeListBuilder.create()
                .texOffs(0, 16).addBox(-4.0F, -12.0F, -2.0F, 8.0F, 12.0F, 4.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));

        PartDefinition head = torso.addOrReplaceChild("head",
            CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        head.addOrReplaceChild("hood", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                    new CubeDeformation(0.45F)),
            PartPose.ZERO);
        head.addOrReplaceChild("helm", CubeListBuilder.create()
                .texOffs(0, 48).addBox(-4.5F, -10.5F, -4.5F, 9.0F, 3.0F, 9.0F),
            PartPose.ZERO);

        torso.addOrReplaceChild("pauldron", CubeListBuilder.create()
                .texOffs(32, 32).addBox(-5.0F, -12.5F, -2.5F, 10.0F, 3.0F, 5.0F),
            PartPose.ZERO);

        torso.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(32, 16).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 12.0F, 3.0F),
            PartPose.offset(-5.0F, -10.0F, 0.0F));
        torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(48, 16).mirror().addBox(-1.5F, -1.5F, -1.5F, 3.0F, 12.0F, 3.0F),
            PartPose.offset(5.0F, -10.0F, 0.0F));

        rootPart.addOrReplaceChild("right_leg", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-2.2F, -12.0F, 0.0F));
        rootPart.addOrReplaceChild("left_leg", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(2.2F, -12.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(RaiderEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root().getAllParts().forEach(ModelPart::resetPose);

        boolean captain = entity.isCaptain();
        helm.visible = captain;
        pauldron.visible = captain;
        hood.visible = !captain; // the helm replaces the hood, never stacks

        // A longer, lower stride than a settler's: they are covering ground.
        float stride = limbSwing * 0.75F;
        float swing = limbSwingAmount * 1.1F;
        rightLeg.xRot = Mth.cos(stride) * 1.3F * swing;
        leftLeg.xRot = Mth.cos(stride + (float) Math.PI) * 1.3F * swing;
        rightArm.xRot = Mth.cos(stride + (float) Math.PI) * 1.1F * swing;
        leftArm.xRot = Mth.cos(stride) * 1.1F * swing;
        // Arms held slightly out from the body -- weapon-ready, not relaxed.
        rightArm.zRot = 0.10F;
        leftArm.zRot = -0.10F;

        torso.xRot = PROWL_LEAN + swing * 0.06F;
        head.xRot = -PROWL_LEAN * 0.7F;

        // The swing itself: vanilla's attack arc, on the lead arm only.
        float attack = getAttackAnim(ageInTicks);
        if (attack > 0.0F) {
            float arc = Mth.sin(attack * (float) Math.PI);
            rightArm.xRot = -1.9F * arc;
            rightArm.yRot = -0.35F * arc;
            torso.yRot = 0.25F * arc;
        }

        head.yRot += Mth.clamp(netHeadYaw, -55.0F, 55.0F) * ((float) Math.PI / 180F);
        head.xRot += headPitch * ((float) Math.PI / 180F) * 0.8F;

        if (entity.hurtTime > 0) {
            float progress = (float) entity.hurtTime / 10.0F;
            torso.xRot += Mth.sin(progress * (float) Math.PI) * 0.18F;
        }
    }

    private float getAttackAnim(float ageInTicks) {
        return this.attackTime;
    }

    /** Set by the renderer each frame from the entity's swing progress. */
    public float attackTime;

    @Override
    public ModelPart root() {
        return root;
    }
}
