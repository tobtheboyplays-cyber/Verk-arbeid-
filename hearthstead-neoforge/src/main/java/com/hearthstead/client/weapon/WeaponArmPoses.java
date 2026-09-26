package com.hearthstead.client.weapon;

import com.hearthstead.HearthsteadServerConfig;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/**
 * The two-handed guard for players holding a double axe, halberd or warhammer (weapons lane,
 * 26 Sep). A NeoForge enum extension of the client-only {@link HumanoidModel.ArmPose}
 * (META-INF/enumextensions.json): the haft runs diagonally across the body, head out beside the
 * main-hand shoulder and forward at chest height, the OFF hand low on the haft and the MAIN hand
 * higher; the face stays clear from the front and 3/4 views.
 *
 * <p>The angles are solved together with each weapon's third-person display transform by
 * tools/weapons/tune_twohand.py (settler rig == player arm maths): both fists sit on the haft
 * (residual 0.75 px) and nothing enters the body (0.0 px). Re-run that script and copy the
 * printed angles here if the weapons' grips move.
 *
 * <p>Attack: vanilla's setupAttackAnimation swings the main arm after this pose; the off arm
 * copies the same swing here so both hands chop together.
 */
public final class WeaponArmPoses {
    // twohand_pose.json -> right_arm_deg / left_arm_deg (main = right hand)
    static final float MAIN_X = -73.025F * Mth.DEG_TO_RAD;
    static final float MAIN_Y = -27.584F * Mth.DEG_TO_RAD;
    static final float OFF_X = -55.522F * Mth.DEG_TO_RAD;
    static final float OFF_Y = 57.38F * Mth.DEG_TO_RAD;
    /** How much the guard follows the head's pitch (the crossbow hold follows it 1:1). */
    static final float LOOK_FOLLOW = 0.35F;

    public static final EnumProxy<HumanoidModel.ArmPose> TWO_HANDED_GUARD = new EnumProxy<>(
        HumanoidModel.ArmPose.class, true, (IArmPoseTransformer) WeaponArmPoses::applyGuard);

    private WeaponArmPoses() {
    }

    static void applyGuard(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
        boolean right = arm == HumanoidArm.RIGHT;
        float side = right ? 1.0F : -1.0F;
        ModelPart main = right ? model.rightArm : model.leftArm;
        ModelPart off = right ? model.leftArm : model.rightArm;
        float look = model.head.xRot * LOOK_FOLLOW;
        main.xRot = MAIN_X + look;
        main.yRot = side * MAIN_Y;
        main.zRot = 0.0F;
        off.xRot = OFF_X + look;
        off.yRot = side * OFF_Y;
        off.zRot = 0.0F;
        float a = model.attackTime;
        if (a > 0.0F) {
            // the same curve HumanoidModel.setupAttackAnimation applies to the main arm afterwards
            float f = 1.0F - a;
            f *= f;
            f *= f;
            f = 1.0F - f;
            float f1 = Mth.sin(f * Mth.PI);
            float f2 = Mth.sin(a * Mth.PI) * -(model.head.xRot - 0.7F) * 0.75F;
            off.xRot -= f1 * 1.2F + f2;
            off.zRot += Mth.sin(a * Mth.PI) * -0.4F;
        }
    }

    /** Client-side read of the synced server kill switch (defaults on). */
    static boolean enabled() {
        return HearthsteadServerConfig.captainWeaponsEnabled();
    }
}
