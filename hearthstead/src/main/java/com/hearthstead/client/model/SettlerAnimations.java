package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;
import static net.minecraft.client.animation.AnimationChannel.Targets.SCALE;

/**
 * Keyframe library for the settler. All values are hand-tuned around
 * vanilla-range amplitudes; posVec y is up-positive, rotations in degrees.
 *
 * Sound sync contract: CHOP's strike lands at t=0.55s of the 1s loop —
 * LumbererWorkGoal plays the thock at tick 11 of each 20-tick cycle.
 */
public final class SettlerAnimations {

    /** Slow breath, tiny arm sway, an idle glance. 4s loop. */
    public static final AnimationDefinition IDLE = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("torso", new AnimationChannel(SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.scaleVec(1.015, 1.02, 1.015), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 2.5F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, -2.5F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(1.5F, -7, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, -5, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(2, 6, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Marching cycle consumed through animateWalk. 1s loop. */
    public static final AnimationDefinition WALK = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-35, 0, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(35, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-35, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(35, 0, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-35, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(35, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(28, 0, 2), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-28, 0, 2), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(28, 0, 2), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-28, 0, -2), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(28, 0, -2), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-28, 0, -2), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 4, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(3, -4, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(3, 4, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .build();

    /** Two thoughtful bites, head dipped toward the raised hand. 1.2s loop. */
    public static final AnimationDefinition EAT = AnimationDefinition.Builder
        .withLength(1.2F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-108, -28, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-124, -30, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-104, -27, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-124, -30, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-104, -27, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-108, -28, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-38, 12, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-42, 12, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-38, 12, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, -8, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(15, -8, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(9, -8, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(15, -8, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(10, -8, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .build();

    /** Bent over the soil, hoe strokes with a snappy down-pull. 1.5s loop. */
    public static final AnimationDefinition FARM = AnimationDefinition.Builder
        .withLength(1.5F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(24, -5, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(29, 6, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(24, -5, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-96, 12, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-32, -6, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-46, -12, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-52, -14, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .build();

    /** Two-handed axe: wind up, strike at 0.55s, follow through. 1s loop. */
    public static final AnimationDefinition CHOP = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-22, -10, -4), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-152, -8, -6), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-38, -10, -4), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-26, -10, -4), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-22, -10, -4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, 14, 5), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-148, 12, 7), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-44, 14, 5), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-33, 14, 5), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-30, 14, 5), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, -3, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-7, -6, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(15, 2, 0), LINEAR),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(5, -3, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-2, 0, 0), LINEAR),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .build();

    /** Feet planted, low guard, slow scanning gaze. 3s loop. */
    public static final AnimationDefinition GUARD_STANCE = AnimationDefinition.Builder
        .withLength(3.0F).looping()
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, -8, -4), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(0, -8, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 8, 4), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(0, 8, 4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 3, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(2, -3, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(2, 3, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-26, 0, -6), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-23, 0, -6), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(-26, 0, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-34, 18, 6), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-31, 18, 6), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(-34, 18, 6), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(0, 30, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(0, -30, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .build();

    /** One-shot diagonal slash with hip rotation. 0.5s. */
    public static final AnimationDefinition MELEE = AnimationDefinition.Builder
        .withLength(0.5F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM),
            new Keyframe(0.08F, KeyframeAnimations.degreeVec(-168, 22, 0), CATMULLROM),
            new Keyframe(0.22F, KeyframeAnimations.degreeVec(24, -26, 0), LINEAR),
            new Keyframe(0.36F, KeyframeAnimations.degreeVec(8, -12, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-26, 0, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20, 0, 0), CATMULLROM),
            new Keyframe(0.22F, KeyframeAnimations.degreeVec(18, 10, 8), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-34, 18, 6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 20, 0), CATMULLROM),
            new Keyframe(0.22F, KeyframeAnimations.degreeVec(6, -18, 0), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2, 3, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.22F, KeyframeAnimations.posVec(0, -0.8F, 1.0F), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Settled by the fire: sunk down, legs forward, arms on knees. 6s loop. */
    public static final AnimationDefinition REST = AnimationDefinition.Builder
        .withLength(6.0F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -8, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.posVec(0, -8, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-86, -6, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(-86, -6, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-86, 6, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(-86, 6, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(11, 0, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.scaleVec(1.012, 1.018, 1.012), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -8, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(-58, -8, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, 8, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(-58, 8, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(15, 3, 0), CATMULLROM),
            new Keyframe(4.4F, KeyframeAnimations.degreeVec(12, -3, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM)))
        .build();

    /** One-shot: arms thrown up, two small hops. 2s. */
    public static final AnimationDefinition CELEBRATE = AnimationDefinition.Builder
        .withLength(2.0F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-176, 0, -12), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-158, 0, -8), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-176, 0, -12), CATMULLROM),
            new Keyframe(1.35F, KeyframeAnimations.degreeVec(-158, 0, -8), CATMULLROM),
            new Keyframe(1.7F, KeyframeAnimations.degreeVec(-172, 0, -10), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-176, 0, 12), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-158, 0, 8), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-176, 0, 12), CATMULLROM),
            new Keyframe(1.35F, KeyframeAnimations.degreeVec(-158, 0, 8), CATMULLROM),
            new Keyframe(1.7F, KeyframeAnimations.degreeVec(-172, 0, 10), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, 2.5F, 0), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, 0, 0), LINEAR),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.posVec(0, 2.5F, 0), LINEAR),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, 0, 0), LINEAR),
            new Keyframe(2.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-14, 0, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-12, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .build();

    private SettlerAnimations() {
    }
}
