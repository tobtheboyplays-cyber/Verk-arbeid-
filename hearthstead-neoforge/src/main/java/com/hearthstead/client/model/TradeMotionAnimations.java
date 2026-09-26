package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * Trade work clips of the anim lane's job pass (each trade its own work loop; played on the
 * SettlerModel trade clock). The authored JSON (tools/blender/pipeline/clips/craft/author_*.py)
 * replaces these on the motion engine; these vanilla-path fallbacks are GENERATED from that JSON
 * (anim-overkill/gen_tradefallbacks.py) and fix the
 * length/loop contract. All are absolute full-body clips: SettlerModel resets first.
 */
public final class TradeMotionAnimations {
    private TradeMotionAnimations() {
    }

    /** Brewer at the mash tun (WORK_STOKE for a BREWER), 1.60 s loop; POT/mash stir at tick 16. */
    public static final AnimationDefinition BREW_MASH = AnimationDefinition.Builder
        .withLength(1.6F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.6F, 0.6F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.posVec(0.37F, -0.88F, 0.59F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.posVec(0.38F, -1.22F, -0.75F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0F, -1.6F, -1.5F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.posVec(-0.37F, -0.72F, 0.2F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.posVec(-0.37F, -0.55F, 0.78F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0F, -0.6F, 0.6F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(9F, 0F, 0F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(9.69F, 5.25F, 0F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(15.04F, 5.26F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(18F, 0F, 0F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(12.04F, -5.25F, 0F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(7.26F, -5.1F, 0F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(9F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(4.4F, -0.27F, 0F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(5.47F, -2.52F, 0F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(7.74F, -1.38F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(8.52F, 0.22F, 0F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(5.66F, 2.73F, 0F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(5.55F, 1.31F, 0F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(4.4F, -0.27F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-68.21F, -24.59F, 18.14F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-77.75F, -33.63F, 30.31F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-55.98F, -42.18F, 55.03F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(25.02F, -42.62F, 20.99F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(20.2F, -29.62F, 16.36F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-11.09F, -21.42F, 8.55F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-68.21F, -24.59F, 18.14F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-103.39F, 37.08F, -39.99F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-101.83F, 34.05F, -41.21F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-88.77F, 57.46F, -27.3F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-41.49F, 74.89F, 23.21F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-73.06F, 68.98F, -8.7F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-91.53F, 55.92F, -28.62F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-103.39F, 37.08F, -39.99F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-8.07F, 8.51F, 4.21F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-11.76F, 8.48F, 6.18F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-5.46F, 8.46F, 7.35F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-3.11F, 8.48F, 6.34F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-7.06F, 8.52F, 2.71F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-8.83F, 8.52F, 2.24F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-8.07F, 8.51F, 4.21F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-25.75F, -8.53F, -0.2F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-30.94F, -8.53F, 1.65F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-31.08F, -8.53F, 0.56F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-31.48F, -8.53F, -1.96F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-27.02F, -8.52F, -2.41F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-24.21F, -8.53F, -1.92F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-25.75F, -8.53F, -0.2F), CATMULLROM)))
        .build();

    /** Weaver at the loom (WORK_WEAVE for a WEAVER), 1.60 s loop = two 16-tick passes; beats at ticks 12, 28. */
    public static final AnimationDefinition LOOM_WEAVE = AnimationDefinition.Builder
        .withLength(1.6F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0.3F, -0.4F, 0F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.posVec(0.12F, -0.48F, 0F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.posVec(-0.21F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(-0.3F, -0.4F, 0F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.posVec(-0.12F, -0.48F, 0F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.posVec(0.21F, -0.6F, 0F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0.3F, -0.4F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(10F, 4F, 0F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(11.25F, -3.65F, 0F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(10.99F, 0F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(10F, -4F, 0F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(11.25F, 3.65F, 0F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(10.99F, 0F, 0F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(10F, 4F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(22.09F, -26.7F, 0F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(21.1F, 22.3F, 0F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(31.56F, -0.11F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(22.1F, 26.7F, 0F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(21.1F, -22.3F, 0F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(31.56F, 0.11F, 0F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(22.09F, -26.7F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-75.02F, -9.01F, 54.14F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-83.74F, -17.23F, 50.44F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-85.03F, -21.51F, 48.37F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-38.97F, -12.94F, 52.4F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-61.06F, -4.24F, 56.2F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-83.63F, -20.26F, 48.99F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-75.02F, -9.01F, 54.14F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-38.98F, 12.94F, -52.4F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-61.06F, 4.24F, -56.2F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-83.63F, 20.26F, -48.99F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-75.02F, 9.01F, -54.14F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-83.74F, 17.23F, -50.44F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-85.03F, 21.51F, -48.37F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-38.98F, 12.94F, -52.4F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.35F, 8.52F, 2.91F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-12.95F, 8.52F, 2.03F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-14.86F, 8.53F, 0.42F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-11.54F, 8.53F, -0.05F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-13.02F, 8.53F, 0.85F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-14.75F, 8.52F, 2.5F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-11.35F, 8.52F, 2.91F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-16.7F, -8.53F, 0.79F), CATMULLROM),
            new Keyframe(0.27F, KeyframeAnimations.degreeVec(-18.19F, -8.53F, -0.1F), CATMULLROM),
            new Keyframe(0.53F, KeyframeAnimations.degreeVec(-19.96F, -8.53F, -1.74F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-16.5F, -8.53F, -2.17F), CATMULLROM),
            new Keyframe(1.07F, KeyframeAnimations.degreeVec(-18.12F, -8.53F, -1.29F), CATMULLROM),
            new Keyframe(1.33F, KeyframeAnimations.degreeVec(-20.07F, -8.53F, 0.34F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-16.7F, -8.53F, 0.79F), CATMULLROM)))
        .build();

    /** Armourer planishing a plate (WORK_HAMMER for an ARMOURER), 1.00 s loop; blow at tick 9. */
    public static final AnimationDefinition ARMOUR_PLANISH = AnimationDefinition.Builder
        .withLength(1F).looping()
        .addAnimation("root", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(0F, 0.55F, 0F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(0F, 4.59F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0F, -2.1F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0F, 0.09F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(0F, 0.55F, 0F), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.3F, 0F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.posVec(-0.29F, -0.15F, 0.19F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0.27F, -0.95F, -0.19F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0.12F, -0.41F, -0.07F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.posVec(0F, -0.3F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(9F, 0F, 0F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(0.54F, 5.88F, 3.79F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(13.9F, -2.93F, -2.49F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(9.96F, -0.73F, -0.66F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(9F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(14.27F, -0.33F, 0F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(13.73F, -8.35F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(14.27F, 4.46F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(15.43F, 0.07F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(14.27F, -0.33F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-45.37F, -15.1F, 39.73F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-45.87F, 3.15F, 15.13F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-31.83F, -21.26F, 28.17F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-41.15F, -20.16F, 39.13F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-45.37F, -15.1F, 39.74F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-91.93F, 16F, -48.65F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-79.69F, 14.98F, -49.35F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-99.91F, 17.02F, -47.94F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-96.18F, 20.87F, -45.14F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-91.93F, 16F, -48.65F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(0.39F, 7.93F, 6.17F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(1.67F, 3.93F, 4.37F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-8.51F, 10.44F, 8.19F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-1.66F, 8.41F, 6.92F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(0.39F, 7.94F, 6.17F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-18.06F, -9.08F, -0.66F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-8.59F, -13.1F, -2.16F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-30.11F, -6.47F, 0.64F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-21.06F, -8.62F, -0.1F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-18.06F, -9.08F, -0.66F), CATMULLROM)))
        .build();

    /** Miller at a hand quern (WORK_KNEAD for a MILLER), 1.20 s loop = one turn; grind at tick 9. */
    public static final AnimationDefinition MILL_GRIND = AnimationDefinition.Builder
        .withLength(1.2F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.8F, 0.5F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.posVec(-0.5F, -1.18F, -0.43F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.posVec(-0.13F, -1.42F, -1.04F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.posVec(0.35F, -0.86F, 0.09F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.posVec(0.25F, -0.72F, 0.41F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0F, -0.8F, 0.5F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(10F, 0F, 0F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.degreeVec(11.34F, 5.89F, 0F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.degreeVec(14.68F, 1.24F, 0F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.degreeVec(12.22F, -4.52F, 0F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.degreeVec(9.88F, -3.18F, 0F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(10F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(20.82F, -0.26F, 0F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.degreeVec(22.22F, -8.6F, 0F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.degreeVec(25.39F, -8.45F, 0F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.degreeVec(22.11F, -2.7F, 0F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.degreeVec(21.32F, -2.66F, 0F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(20.82F, -0.26F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-48.24F, -50.91F, 19.95F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.degreeVec(-80.57F, -29.35F, 45.11F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.degreeVec(-108.92F, -31.31F, 43.55F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.degreeVec(-102.34F, -39.26F, 36.23F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.degreeVec(-91.89F, -48.37F, 24.4F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-48.24F, -50.91F, 19.95F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-13.06F, 30.93F, -43.86F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.degreeVec(16.41F, 31.98F, -43.01F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.degreeVec(20.25F, 35.91F, -39.55F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.degreeVec(-15.95F, 34.38F, -40.95F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.degreeVec(-72.97F, 40.36F, -35.07F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-13.06F, 30.93F, -43.86F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-13.23F, 8.52F, 3.04F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.degreeVec(-11.23F, 8.53F, 1.21F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.degreeVec(-8.64F, 8.51F, 3.86F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.degreeVec(-10.74F, 8.5F, 5.08F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.degreeVec(-11.17F, 8.51F, 4.42F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-13.23F, 8.52F, 3.04F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-28.75F, -8.53F, -1.46F), CATMULLROM),
            new Keyframe(0.24F, KeyframeAnimations.degreeVec(-30.49F, -8.5F, -4.91F), CATMULLROM),
            new Keyframe(0.48F, KeyframeAnimations.degreeVec(-30.72F, -8.51F, -3.58F), CATMULLROM),
            new Keyframe(0.72F, KeyframeAnimations.degreeVec(-28.68F, -8.53F, -0.04F), CATMULLROM),
            new Keyframe(0.96F, KeyframeAnimations.degreeVec(-27.2F, -8.53F, -0.16F), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-28.75F, -8.53F, -1.46F), CATMULLROM)))
        .build();
}
