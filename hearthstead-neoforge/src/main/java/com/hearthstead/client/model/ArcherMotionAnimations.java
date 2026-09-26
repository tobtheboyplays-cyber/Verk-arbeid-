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
            new Keyframe(0F, KeyframeAnimations.degreeVec(0F, -10F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(0F, -10.19F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0F, -14.72F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(0F, -33.81F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -1.1F, 0.8F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.posVec(0F, -0.69F, 0.79F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0F, -0.69F, 0.71F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0F, -0.64F, 0.35F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0F, -0.6F, -0.01F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0F, -0.6F, -0.01F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0F, -0.6F, -0.01F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(16F, -4F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(15.94F, -4.11F, -0.1F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(14.35F, -6.83F, -0.36F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(8.06F, -17.6F, -0.75F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(2.11F, -28F, -1.19F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2.39F, -28F, -1.73F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(2.63F, -28F, -2.19F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(2.82F, -28F, -2.51F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(2.98F, -28F, -2.8F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(3F, -28F, -3F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(2F, -28F, -3F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-14F, 14F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-13.91F, 14.37F, -0.02F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-12.2F, 21.43F, -0.45F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-4.93F, 51.41F, -2.27F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-33.25F, 3.19F, 1.26F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-45.51F, 3.38F, 6.19F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-52F, 5.96F, 12.31F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-31.77F, 43.83F, 58.44F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-26.75F, 53.22F, 69.88F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-26.71F, 53.26F, 69.93F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-26.69F, 53.26F, 69.92F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-26.69F, 53.23F, 69.89F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-26.72F, 53.2F, 69.85F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-26.77F, 53.18F, 69.83F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-27.57F, 53.18F, 69.83F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-13.75F, -41.66F, 90.17F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-4.72F, -50.85F, 123.27F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-47.09F, -48.75F, 159.63F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-48.11F, -49.24F, 157.39F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-47.68F, -49.15F, 157.79F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-48.46F, -48.81F, 159.34F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-49.21F, -48.48F, 160.79F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-37.82F, -47.53F, 164.43F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-24.88F, -46.55F, 167.69F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-24.54F, -46.52F, 167.81F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-22.49F, -46.48F, 167.91F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-34.08F, 17.97F, 1.01F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-41.55F, 18.08F, 0.94F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-45.39F, 22.87F, -3.43F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-44.84F, 41.82F, -6.36F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-30.54F, 2.03F, -1.81F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-41.91F, 2.14F, -2.08F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-33.3F, 6.75F, -1.46F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-26.7F, 25.81F, -2.63F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM)))
        .build();

    /** Archer shot cycle: release follow-through, quiver, arrow nocked back in low ready (15 recovery ticks), 0.75 s. */
    public static final AnimationDefinition ARCHER_RELOAD = AnimationDefinition.Builder
        .withLength(0.75F)
        .addAnimation("root", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(0F, -52F, 0F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(0F, -52.27F, 0F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(0F, -33.37F, 0F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(0F, -18.11F, 0F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(0F, -10.33F, 0F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(0F, -9.99F, 0F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(0F, -10F, 0F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(0F, -10F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0F, -10F, 0F), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.6F, 0F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.posVec(0F, -0.59F, -0.02F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.posVec(0F, -0.82F, 0.35F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.posVec(0F, -1.01F, 0.65F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.posVec(0F, -1.09F, 0.79F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.posVec(0F, -1.1F, 0.8F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.posVec(0F, -1.1F, 0.8F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.posVec(0F, -1.1F, 0.8F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0F, -1.1F, 0.8F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(2F, -28F, -3F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(4.95F, -28.15F, -2.15F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(9.85F, -17.43F, -0.22F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(13.94F, -8.49F, 1.54F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(15.8F, -4.43F, 1.86F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(16.02F, -3.95F, 0.71F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(16F, -4F, 0.04F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(16F, -4F, -0.01F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(16F, -4F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(2F, 80F, -4F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(2.1F, 80.42F, -4.03F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-5.1F, 50.72F, -2.23F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-10.96F, 26.53F, -0.76F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-13.86F, 14.58F, -0.03F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-14F, 13.98F, 0F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-14F, 14F, 0F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-14F, 14F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-14F, 14F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-26.54F, 52.94F, 69.83F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-20.5F, 52.94F, 66.81F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-25.58F, 52.94F, 69.35F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-27.42F, 46.43F, 60.85F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-30.9F, 20.59F, 25.25F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-33.21F, 3.42F, 1.59F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-33.25F, 3.18F, 1.26F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-33.25F, 3.19F, 1.26F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-33.25F, 3.19F, 1.27F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-62.89F, -56.98F, -144.1F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-42.33F, -44.75F, -113.68F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(44.43F, -59.1F, -162.84F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(120.14F, -5.1F, -254.42F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(110.17F, -1.06F, -256.85F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(99.72F, -30.21F, -237.15F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(83.77F, 146.3F, -234.03F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(163.72F, 127.17F, -128.41F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(166.44F, 139.22F, -108.44F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-28.4F, 59.95F, -2.38F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-28.25F, 60.22F, -2.3F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-36.26F, 41.36F, -6.47F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-38.85F, 25.59F, -8F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-52.14F, 18.3F, -6.83F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-42.3F, 17.97F, -0.25F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-33.99F, 17.97F, 1.01F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-34.08F, 17.97F, 1.01F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-34.08F, 17.97F, 1.01F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-20.83F, 43.84F, -6.64F), CATMULLROM),
            new Keyframe(0.09F, KeyframeAnimations.degreeVec(-20.89F, 44.1F, -6.72F), CATMULLROM),
            new Keyframe(0.19F, KeyframeAnimations.degreeVec(-17.88F, 25.55F, -2.3F), CATMULLROM),
            new Keyframe(0.28F, KeyframeAnimations.degreeVec(-21.77F, 9.89F, -0.75F), CATMULLROM),
            new Keyframe(0.38F, KeyframeAnimations.degreeVec(-30.89F, 2.48F, -0.9F), CATMULLROM),
            new Keyframe(0.47F, KeyframeAnimations.degreeVec(-37.16F, 2.03F, -1.8F), CATMULLROM),
            new Keyframe(0.56F, KeyframeAnimations.degreeVec(-30.43F, 2.03F, -1.81F), CATMULLROM),
            new Keyframe(0.66F, KeyframeAnimations.degreeVec(-30.54F, 2.03F, -1.81F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-30.54F, 2.03F, -1.82F), CATMULLROM)))
        .build();
}
