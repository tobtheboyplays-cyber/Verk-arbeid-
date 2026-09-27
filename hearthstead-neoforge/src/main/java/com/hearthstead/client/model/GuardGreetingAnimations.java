package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * The guard's greeting (GuardSaluteGoal, owner spec 2026-09-26). The authored bedrock JSON of the
 * same name (animations/settler/&lt;name&gt;.animation.json, tools/blender/pipeline/clips/combat/greet.py)
 * replaces these on the motion engine. These vanilla-path fallbacks are GENERATED from that JSON
 * (anim-overkill/gen_greetfallbacks.py) and fix the
 * length/loop contract. All are absolute full-body clips: SettlerModel resets first.
 */
public final class GuardGreetingAnimations {
    private GuardGreetingAnimations() {
    }

    /** Greeting: sword into the right-hip scabbard (in the scabbard from 0.70 s), 1.00 s. */
    public static final AnimationDefinition GUARD_SHEATHE_SWORD = AnimationDefinition.Builder
        .withLength(1F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.posVec(0F, -0.24F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0F, -0.18F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0F, -0.18F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0F, -0.34F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0F, -0.46F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0F, -0.17F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0F, -0.05F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.posVec(0F, -0.07F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-1.5F, 0F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-0.89F, 1.73F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0.49F, 0.12F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(2F, -5.17F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(2.44F, -8F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(3.37F, -6.75F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(3.99F, -5.07F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(2.19F, -2.93F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-1.62F, -0.05F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-2.15F, 0.03F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-1.8F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-4F, 0F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-2.69F, 0F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0.26F, 0F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(6.6F, 0F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(10F, -2.76F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(11.29F, -7.24F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(12.96F, -7.3F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(6.78F, -3.3F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-4.56F, -0.11F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-4.05F, 0F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-4F, 0F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-3.32F, -21.44F, 0.31F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(5.25F, -24.06F, 3.46F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-3.46F, -10.46F, -3.67F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-16.32F, -3.62F, 6.19F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-37.51F, -1.24F, 14.76F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-27.6F, -2.52F, 13.67F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-22.68F, -3.17F, 13.16F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-20.4F, 1.98F, 7.85F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-20.39F, 2.02F, 7.81F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-20.4F, 2F, 7.83F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-20.4F, 1.98F, 7.85F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(0.39F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-2.38F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-3F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-1.8F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0.8F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(2F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(1.77F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(1.33F, 0F, -2.5F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-15.73F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-13.74F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-13.82F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-17.62F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-19.93F, 7.97F, -0.37F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-13.51F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-9F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-10.35F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-15.73F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-13.74F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-13.82F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-17.62F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-19.93F, -7.97F, 0.37F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-13.51F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-9F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-10.35F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM)))
        .build();

    /** Greeting: crisp hand salute raised to the right brow, 0.30 s. */
    public static final AnimationDefinition GUARD_SALUTE_RAISE = AnimationDefinition.Builder
        .withLength(0.3F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.posVec(0F, -0.13F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0F, 0.07F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0F, 0F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-1.8F, 0F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-1.8F, 0F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-2.93F, 0F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-2.6F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-4F, 0F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-4.7F, 0F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-5.01F, 0F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-4.5F, 0F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-20.4F, 1.98F, 7.85F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-23.94F, 1.4F, 7.65F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-27.46F, 38.31F, 107.85F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-12.33F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0.82F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-3.01F, 7.97F, -0.35F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-12.33F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0.82F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-3.01F, -7.97F, 0.35F), CATMULLROM)))
        .build();

    /** Greeting: the held hand salute, breathing only, 2.00 s loop. */
    public static final AnimationDefinition GUARD_SALUTE_HOLD_BROW = AnimationDefinition.Builder
        .withLength(2F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-2.6F, 0F, 0F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-2.71F, 0F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-2.87F, 0F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-3.02F, 0F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-3.1F, 0F, 0F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(-3.04F, 0F, 0F), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-2.89F, 0F, 0F), CATMULLROM),
            new Keyframe(1.75F, KeyframeAnimations.degreeVec(-2.72F, 0F, 0F), CATMULLROM),
            new Keyframe(2F, KeyframeAnimations.degreeVec(-2.6F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-4.5F, 0F, 0F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-4.41F, 0F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-4.28F, 0F, 0F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-4.16F, 0F, 0F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-4.1F, 0F, 0F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(-4.14F, 0F, 0F), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-4.27F, 0F, 0F), CATMULLROM),
            new Keyframe(1.75F, KeyframeAnimations.degreeVec(-4.41F, 0F, 0F), CATMULLROM),
            new Keyframe(2F, KeyframeAnimations.degreeVec(-4.5F, 0F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(1.75F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(2F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(1.75F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(2F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-3.01F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-2.13F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0.76F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0.84F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(0.86F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(0.84F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(0.76F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(1.75F, KeyframeAnimations.degreeVec(-2.13F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(2F, KeyframeAnimations.degreeVec(-3.01F, 7.97F, -0.35F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-3.01F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-2.13F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0.76F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0.84F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(1F, KeyframeAnimations.degreeVec(0.86F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(0.84F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(0.76F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(1.75F, KeyframeAnimations.degreeVec(-2.13F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(2F, KeyframeAnimations.degreeVec(-3.01F, -7.97F, 0.35F), CATMULLROM)))
        .build();

    /** Greeting: the cut-away, hand onto the hilt, 0.35 s. */
    public static final AnimationDefinition GUARD_SALUTE_RELEASE = AnimationDefinition.Builder
        .withLength(0.35F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, 0F, 0F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.posVec(0F, -0.15F, 0F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.posVec(0F, -0.16F, 0F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-2.6F, 0F, 0F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.degreeVec(-1.57F, 0F, 0F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.degreeVec(-1.21F, 0F, 0F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-1.8F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-4.5F, 0F, 0F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.degreeVec(-4.37F, 0F, 0F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.degreeVec(-4.13F, 0F, 0F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-4F, 0F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-25.38F, 46.24F, 128.47F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.degreeVec(-24.19F, 30.4F, 127.62F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.degreeVec(-22.86F, 4.34F, 34.32F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-20.4F, 1.98F, 7.85F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-3.01F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.degreeVec(-12.7F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.degreeVec(-13.17F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-3.01F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.12F, KeyframeAnimations.degreeVec(-12.7F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.23F, KeyframeAnimations.degreeVec(-13.17F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM)))
        .build();

    /** Greeting: sword drawn (in hand from 0.05 s), ends on GUARD_ATTENTION, 0.90 s. */
    public static final AnimationDefinition GUARD_DRAW_SWORD = AnimationDefinition.Builder
        .withLength(0.9F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.posVec(0F, -0.18F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0F, -0.32F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0F, -0.32F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0F, -0.14F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0F, 0.02F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0F, 0.01F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0F, -0.16F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0F, -0.15F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.posVec(0F, -0.1F, 0F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-1.8F, 0F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-0.56F, -0.86F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(1.76F, -5.89F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(3F, -9F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(1.72F, -5.54F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-0.91F, -4.07F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-2.55F, -1.87F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-2.14F, 0.76F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-1.67F, 0.59F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-1.5F, 0F, 0F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-4F, 0F, 0F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-1.26F, 0F, 0F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(5.68F, 0F, 0F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(2.89F, 0F, 0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-4.21F, 0F, 0F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-5.24F, 0F, 0F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-4.74F, 0F, 0F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-4.38F, 0F, 0F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-4.09F, 0F, 0F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-4F, 0F, 0F), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-20.4F, 1.98F, 7.85F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-20.6F, 2.03F, 7.87F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-24.97F, 3.15F, 8.28F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-41.55F, 7.43F, 9.83F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-55.97F, 4.33F, 4.29F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-39.95F, -2.77F, 0.55F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-18.83F, -8.47F, -0.93F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-12.03F, -13.93F, -0.44F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-3.32F, -21.44F, 0.31F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-3.32F, -21.44F, 0.31F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(1.79F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(3.27F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(4F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(2.38F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-0.52F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-2.01F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-1.1F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(0.25F, 0F, -2.5F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(1F, 0F, -2.5F), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-13.97F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-17.4F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-17.37F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-12.85F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0.73F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-2.54F, 7.97F, -0.34F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-13.69F, 7.97F, -0.36F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-12.89F, 7.97F, -0.35F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-11.23F, 7.97F, -0.35F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-13.97F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-17.4F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-17.37F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-12.85F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0.73F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-2.54F, -7.97F, 0.34F), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-13.69F, -7.97F, 0.36F), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-12.89F, -7.97F, 0.35F), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-11.23F, -7.97F, 0.35F), CATMULLROM)))
        .build();
}
