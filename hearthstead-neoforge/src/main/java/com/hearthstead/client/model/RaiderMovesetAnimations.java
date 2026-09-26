package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * Fallback Java clip for the raider heavy attack. The authored version is
 * the bedrock JSON clip animation.raider.raider_heavy and replaces this on
 * the motion engine; this one fixes the length (1.20 s) and contact
 * (0.60 s / tick 12) and keeps the state visible with the engine off.
 * Applied after clearMotion() on arms, torso, head and legs, like
 * RAIDER_STRIKE.
 */
public final class RaiderMovesetAnimations {
    private RaiderMovesetAnimations() {
    }

    private static Keyframe r(float t, float x, float y, float z, AnimationChannel.Interpolation i) {
        return new Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), i);
    }

    /** Ticketed skirmisher jab: 0.40 s, contact 0.15 s (tick 3). */
    public static final AnimationDefinition RAIDER_LIGHT = AnimationDefinition.Builder
        .withLength(0.4F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -20, 0, 0, CATMULLROM), r(0.08F, -95, 10, 18, CATMULLROM),
            r(0.15F, -30, -20, 0, LINEAR), r(0.25F, -28, -18, 0, CATMULLROM), r(0.4F, -20, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.08F, -6, 14, 0, CATMULLROM),
            r(0.15F, 14, -12, 0, LINEAR), r(0.4F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Crushing two-hand overhead: 2.20 s, contact 1.20 s (tick 24). */
    public static final AnimationDefinition RAIDER_HEAVY = AnimationDefinition.Builder
        .withLength(2.20F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.00F, -20, 0, 0, CATMULLROM), r(0.80F, -160, 4, 12, CATMULLROM),
            r(1.00F, -164, 4, 12, LINEAR), r(1.20F, -34, 0, 0, LINEAR),
            r(1.55F, -40, 0, 2, CATMULLROM), r(2.20F, -20, 0, 0, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.00F, -10, 0, 0, CATMULLROM), r(0.80F, -40, 0, -14, CATMULLROM),
            r(1.20F, 10, 0, -6, LINEAR), r(2.20F, -10, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.00F, 0, 0, 0, CATMULLROM), r(0.80F, -16, 10, 0, CATMULLROM),
            r(1.00F, -17, 10, 0, LINEAR), r(1.20F, 26, -8, 0, LINEAR),
            r(1.55F, 22, -6, 0, CATMULLROM), r(2.20F, 0, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.00F, 0, 0, 0, CATMULLROM), r(0.80F, -8, -6, 0, CATMULLROM),
            r(1.20F, 12, 4, 0, LINEAR), r(2.20F, 0, 0, 0, CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.00F, 0, 0, 0, CATMULLROM), r(1.20F, -18, 0, 0, LINEAR), r(2.20F, 0, 0, 0, CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.00F, 0, 0, 0, CATMULLROM), r(1.20F, 14, 0, 0, LINEAR), r(2.20F, 0, 0, 0, CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.posVec(0, -1.0F, -0.6F), LINEAR),
            new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Skirmisher hop back out of reach: 0.40 s. */
    public static final AnimationDefinition RAIDER_HOP_BACK = AnimationDefinition.Builder
        .withLength(0.4F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0, 1.5F, 1.0F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.2F, -10, 0, 0, CATMULLROM), r(0.4F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Duck to the raider's left: 0.45 s (mirrored by DODGE_RIGHT). */
    public static final AnimationDefinition RAIDER_DODGE_LEFT = dodge(1.0F);
    /** Duck to the raider's right: 0.45 s. */
    public static final AnimationDefinition RAIDER_DODGE_RIGHT = dodge(-1.0F);

    private static AnimationDefinition dodge(float side) {
        return AnimationDefinition.Builder.withLength(0.45F)
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.2F, KeyframeAnimations.posVec(-1.5F * side, -1.0F, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                r(0.0F, 0, 0, 0, CATMULLROM), r(0.2F, 8, 0, 12 * side, CATMULLROM),
                r(0.45F, 0, 0, 0, CATMULLROM)))
            .build();
    }

    /** Beckoning taunt: 2.00 s, fades in by 0.25 s and out after 1.70 s. */
    public static final AnimationDefinition RAIDER_TAUNT = AnimationDefinition.Builder
        .withLength(2.0F)
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.25F, -70, 0, -10, CATMULLROM),
            r(0.6F, -80, 0, -10, CATMULLROM), r(0.95F, -70, 0, -10, CATMULLROM),
            r(1.3F, -80, 0, -10, CATMULLROM), r(1.7F, -70, 0, -10, CATMULLROM), r(2.0F, 0, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.5F, -6, 0, 6, CATMULLROM), r(1.0F, -2, 0, -6, CATMULLROM),
            r(1.5F, -6, 0, 6, CATMULLROM), r(2.0F, 0, 0, 0, CATMULLROM)))
        .build();
}
