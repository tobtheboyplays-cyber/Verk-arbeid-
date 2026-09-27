package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * The archer's shot cycle (owner request 2026-09-26; tools/blender/pipeline/clips/roles/archer.py). The
 * authored JSON replaces these on the motion engine; these vanilla-path fallbacks are GENERATED from it
 * (anim-overkill/gen_archerfallbacks.py) and fix the
 * length/loop contract. All are absolute full-body clips: SettlerModel resets first.
 */
public final class ArcherMotionAnimations {
    private ArcherMotionAnimations() {
    }

    /** Archer shot cycle: low ready -> bow up -> draw to the jaw anchor (pull frames on the server draw clock), 1.00 s. */
    public static final AnimationDefinition ARCHER_DRAW = AnimationDefinition.Builder
        .withLength(1F)
        .addAnimation("root", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(0F, 5F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(0F, 5.21F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0F, 10.29F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(0F, 31.64F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -1.2F, 0.6F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.posVec(0F, -0.79F, 0.59F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0F, -0.78F, 0.53F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0F, -0.69F, 0.26F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0F, -0.6F, -0.01F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0F, -0.6F, -0.01F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(12F, 13F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(11.95F, 13.07F, 0.1F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(10.88F, 14.68F, 0.35F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(6.33F, 21.5F, 0.75F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(1.99F, 28.17F, 1.18F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2.34F, 28.08F, 1.73F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(2.63F, 28F, 2.2F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(2.83F, 28F, 2.51F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(2.98F, 28F, 2.8F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(3F, 28F, 3F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(2F, 28F, 3F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-12F, -16F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-11.92F, -16.35F, 0.02F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-10.43F, -23.2F, 0.45F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-4.06F, -52.28F, 2.27F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-141.43F, -40.23F, 121.89F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-173.28F, -20.6F, 99.3F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-151.14F, 20.49F, 84.48F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-91.53F, 63.27F, 146.14F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-72.9F, 57.69F, 169.44F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-74.3F, 57.03F, 167.43F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-75.52F, 56.33F, 165.61F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-62.12F, 54.41F, 161.23F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-51.34F, 52.5F, 157.54F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-51.05F, 52.43F, 157.41F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-49.78F, 52.24F, 157.07F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-30.11F, 7.41F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-34.7F, 0.9F, -5.61F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-38.87F, -7F, -11.91F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-55.42F, -60.76F, -50.44F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-59.52F, -74.1F, -60F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-59.52F, -74.15F, -60.04F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-59.49F, -74.14F, -60.03F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-59.47F, -74.1F, -60.01F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-59.46F, -74.06F, -59.97F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-59.5F, -74.04F, -59.96F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-60.3F, -74.04F, -59.96F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-30.45F, 2.97F, 1.85F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-37.95F, 2.84F, 2.03F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-30.13F, -2.56F, 1.3F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-24.85F, -23.66F, 2.13F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-35.52F, -12.97F, -0.81F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-48.44F, -13.09F, -0.45F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-50.29F, -18.18F, 5.5F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-47.95F, -39.38F, 7.28F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM)))
        .build();

    /** Archer shot cycle: release follow-through, quiver, arrow nocked back in low ready (15 recovery ticks), 0.75 s. */
    public static final AnimationDefinition ARCHER_RELOAD = AnimationDefinition.Builder
        .withLength(0.75F)
        .addAnimation("root", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(0F, 52F, 0F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(0F, 52.3F, 0F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(0F, 31.15F, 0F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(0F, 13.79F, 0F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(0F, 5.31F, 0F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(0F, 4.99F, 0F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(0F, 5F, 0F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(0F, 5F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0F, 5F, 0F), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.posVec(0F, -0.59F, -0.01F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.posVec(0F, -0.86F, 0.26F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.posVec(0F, -1.09F, 0.49F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.posVec(0F, -1.2F, 0.6F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.posVec(0F, -1.2F, 0.6F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.posVec(0F, -1.2F, 0.6F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.posVec(0F, -1.2F, 0.6F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0F, -1.2F, 0.6F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(2F, 28F, 3F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(4.88F, 28.08F, 0.94F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(8.09F, 21.38F, -2.76F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(10.69F, 15.82F, -3.98F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(11.89F, 13.23F, -3.63F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(12F, 13F, -2.12F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(12F, 13F, -0.03F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(12F, 13F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(12F, 13F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(2F, -80F, 4F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-3.97F, -51.81F, 0.68F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-13.96F, 4.32F, -4.87F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-15.86F, 12.45F, -5.86F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-15.04F, 10.04F, -5.03F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-12.04F, -1.14F, -2.99F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-6.26F, -9.23F, -0.02F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-4.29F, -13.85F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-12F, -16F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-65.96F, 52.05F, 156.74F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-6.76F, 36.7F, 131.64F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(4.31F, 137.33F, 135.32F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-40.11F, 145.47F, 139.71F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-38.9F, 145.71F, 139.82F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-49.68F, 119F, 118.38F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(186.76F, 153.75F, 255.62F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(52.54F, 208.82F, 287.04F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(49.14F, 210.75F, 289.16F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-59.53F, -73.81F, -60.06F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-53.56F, -73.81F, -63.04F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-58.5F, -73.85F, -60.61F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-55.68F, -63.18F, -52.2F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-40.26F, -20.61F, -20.72F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-30.24F, 7.03F, -0.28F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-30.11F, 7.41F, 0F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-32.31F, 7.41F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-30.11F, 7.41F, 0F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-21.88F, -43.85F, 6.43F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-21.95F, -44.15F, 6.52F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-18.27F, -23.17F, 1.93F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-20.41F, -5.82F, 0.56F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-30.55F, 2.52F, 1.19F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-36.33F, 2.97F, 1.99F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-30.45F, 2.97F, 1.85F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-30.45F, 2.97F, 1.85F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-30.45F, 2.97F, 1.85F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-28.4F, -59.95F, 2.38F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-27.68F, -60.25F, 2.27F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-53.89F, -38.89F, 7.34F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-49.89F, -21.72F, 3.67F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-44.66F, -13.59F, -0.51F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-35.52F, -12.97F, -0.81F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-35.52F, -12.97F, -0.81F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-35.52F, -12.97F, -0.81F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-35.52F, -12.97F, -0.81F), CATMULLROM)))
        .build();
}
