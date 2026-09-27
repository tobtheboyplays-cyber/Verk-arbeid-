package com.hearthstead.client.guildmaster;

import com.hearthstead.entity.GuildmasterEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Original cuboid Guildmaster, always seated on his own stool (the stool is
 * part of the model, so seat contact can never drift from the body).
 *
 * <p>Model space: y grows downward, the ground is y = 24. The stool seat top
 * is at y = 15; hips rest on it, thighs run forward, shins drop to the
 * ground, forearms rest on the knees. Texture 128x64, painted for this mod
 * (burgundy cap, green vest over a linen shirt, belt with pouch, dark
 * trousers and boots) -- no vanilla or third-party texture is used.
 *
 * <p>Animation is a code pose: a slow breath, a bounded head follow and, when
 * a player opens trade, a short nod with a raised right hand (about 1.7 s).
 * Idle life comes from natural gaps: an occasional glance down at the ledger
 * every half minute or so, offset per entity so two Guildmasters never move
 * in sync.
 */
public final class GuildmasterModel extends HierarchicalModel<GuildmasterEntity> {
    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart rightArm;
    private final ModelPart leftArm;

    /** Resting pose of the arms: forearms laid over the knees. */
    private static final float ARM_REST_X = -1.02F;
    private static final float ARM_REST_Z = 0.22F;

    public GuildmasterModel(ModelPart root) {
        this.root = root;
        this.body = root.getChild("body");
        this.head = body.getChild("head");
        this.rightArm = body.getChild("right_arm");
        this.leftArm = body.getChild("left_arm");
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        CubeDeformation none = CubeDeformation.NONE;

        // Stool: seat 10x2x8 on four 2x7x2 legs; seat top at y 15.
        PartDefinition stool = root.addOrReplaceChild("stool", CubeListBuilder.create()
                .texOffs(72, 0).addBox(-5.0F, 0.0F, -3.0F, 10.0F, 2.0F, 8.0F, none),
            PartPose.offset(0.0F, 15.0F, 0.0F));
        stool.addOrReplaceChild("leg_fr", CubeListBuilder.create()
            .texOffs(72, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 7.0F, 2.0F, none),
            PartPose.offset(-3.8F, 2.0F, -1.8F));
        stool.addOrReplaceChild("leg_fl", CubeListBuilder.create()
            .texOffs(72, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 7.0F, 2.0F, none),
            PartPose.offset(3.8F, 2.0F, -1.8F));
        stool.addOrReplaceChild("leg_br", CubeListBuilder.create()
            .texOffs(72, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 7.0F, 2.0F, none),
            PartPose.offset(-3.8F, 2.0F, 3.8F));
        stool.addOrReplaceChild("leg_bl", CubeListBuilder.create()
            .texOffs(72, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 7.0F, 2.0F, none),
            PartPose.offset(3.8F, 2.0F, 3.8F));

        // Body pivot at the hips (y 15, on the seat), torso 8x12x4 upwards.
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                .texOffs(0, 16).addBox(-4.0F, -12.0F, -2.0F, 8.0F, 12.0F, 4.0F, none),
            PartPose.offset(0.0F, 15.0F, 1.0F));
        // Belt pouch on the right hip.
        body.addOrReplaceChild("pouch", CubeListBuilder.create()
                .texOffs(56, 16).addBox(-1.5F, 0.0F, -1.0F, 3.0F, 3.0F, 2.0F, none),
            PartPose.offset(-3.2F, -3.6F, -2.6F));
        // Head on the neck (top of the torso).
        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, none),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        // Burgundy cap: a soft, slightly wider crown sitting on the head.
        head.addOrReplaceChild("cap", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.5F, -9.5F, -4.5F, 9.0F, 3.0F, 9.0F, none),
            PartPose.rotation(-0.06F, 0.0F, 0.05F));
        body.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(24, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 11.0F, 4.0F, none),
            PartPose.offset(-5.0F, -10.0F, 0.0F));
        body.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(40, 16).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 11.0F, 4.0F, none),
            PartPose.offset(5.0F, -10.0F, 0.0F));

        // Seated legs: thighs forward along -z on the seat, shins down to the ground.
        root.addOrReplaceChild("right_thigh", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, -4.0F, -7.0F, 4.0F, 4.0F, 6.0F, none),
            PartPose.offset(-2.0F, 15.0F, 0.5F));
        root.addOrReplaceChild("left_thigh", CubeListBuilder.create()
                .texOffs(20, 32).addBox(-2.0F, -4.0F, -7.0F, 4.0F, 4.0F, 6.0F, none),
            PartPose.offset(2.0F, 15.0F, 0.5F));
        root.addOrReplaceChild("right_shin", CubeListBuilder.create()
                .texOffs(40, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 13.0F, 4.0F, none),
            PartPose.offset(-2.1F, 11.0F, -6.5F));
        root.addOrReplaceChild("left_shin", CubeListBuilder.create()
                .texOffs(56, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 13.0F, 4.0F, none),
            PartPose.offset(2.1F, 11.0F, -6.5F));
        return LayerDefinition.create(mesh, 128, 64);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(GuildmasterEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root().getAllParts().forEach(ModelPart::resetPose);
        float phase = ageInTicks + (entity.getId() % 97) * 7.0F;

        // Breathing: a slow, small rise of the torso.
        float breath = Mth.sin(phase * 0.075F);
        body.y += breath * 0.18F;
        body.xRot = 0.04F + breath * 0.012F;

        // Bounded head follow; the look goal already limits yaw to 55 degrees.
        head.yRot = Mth.clamp(netHeadYaw, -55.0F, 55.0F) * Mth.DEG_TO_RAD;
        head.xRot = Mth.clamp(headPitch, -25.0F, 30.0F) * Mth.DEG_TO_RAD;

        // A glance down at the ledger roughly every 30 s, 3 s long, only when
        // he is not following anyone (head roughly forward).
        float cycle = phase % 600.0F;
        if (cycle < 60.0F && Math.abs(netHeadYaw) < 8.0F) {
            float w = Mth.sin(cycle / 60.0F * Mth.PI);
            head.xRot += 0.32F * w;
            head.yRot += 0.12F * w;
        }

        rightArm.xRot = ARM_REST_X;
        rightArm.zRot = -ARM_REST_Z; // hands drawn in over the knees
        leftArm.xRot = ARM_REST_X;
        leftArm.zRot = ARM_REST_Z;
        // Tiny independent drift so the hands never look frozen.
        rightArm.xRot += Mth.sin(phase * 0.043F) * 0.015F;
        leftArm.xRot += Mth.sin(phase * 0.037F + 1.3F) * 0.015F;

        int since = entity.tickCount - entity.greetStartTick;
        float t = since + (ageInTicks - (int) ageInTicks);
        if (since >= 0 && t < GuildmasterEntity.GREET_TICKS) {
            // Greeting: a nod, then the right hand lifts to shoulder height
            // (palm out) and settles back. One short motion, never a wave loop.
            float p = t / GuildmasterEntity.GREET_TICKS;
            float raise = Mth.sin(Mth.clamp(p * 1.25F, 0.0F, 1.0F) * Mth.PI);
            rightArm.xRot = Mth.lerp(raise, ARM_REST_X, -2.1F);
            rightArm.zRot = Mth.lerp(raise, -ARM_REST_Z, 0.12F);
            float nod = Mth.sin(Mth.clamp(p * 2.2F, 0.0F, 1.0F) * Mth.PI);
            head.xRot += 0.22F * nod;
            body.xRot += 0.05F * raise;
        }
    }
}
