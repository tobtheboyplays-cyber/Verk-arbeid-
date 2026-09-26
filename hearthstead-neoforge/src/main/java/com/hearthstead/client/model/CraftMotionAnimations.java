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
 * Fallback Java clips for newer craft activities (settler rig). The authored
 * bedrock JSON of the same name (animations/settler/nail_hammer.animation.json)
 * replaces them on the motion engine; these fix length and contact beats.
 */
public final class CraftMotionAnimations {
    private CraftMotionAnimations() {
    }

    private static Keyframe r(float t, float x, float y, float z, AnimationChannel.Interpolation i) {
        return new Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), i);
    }

    /**
     * WORK_NAIL wooden repair: three short hammer taps at 0.40 / 0.80 / 1.20 s
     * (ticks 8 / 16 / 24 of a 40-tick loop), then a look over the mend.
     */
    public static final AnimationDefinition NAIL_HAMMER = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -58, -10, 4, CATMULLROM),
            r(0.3F, -86, -10, 4, CATMULLROM), r(0.4F, -52, -10, 4, LINEAR),
            r(0.7F, -86, -10, 4, CATMULLROM), r(0.8F, -52, -10, 4, LINEAR),
            r(1.1F, -86, -10, 4, CATMULLROM), r(1.2F, -52, -10, 4, LINEAR),
            r(1.5F, -48, -12, 6, CATMULLROM), r(2.0F, -58, -10, 4, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 18, -6, CATMULLROM), r(1.2F, -64, 18, -6, CATMULLROM),
            r(1.6F, -54, 14, -4, CATMULLROM), r(2.0F, -62, 18, -6, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 18, 4, 0, CATMULLROM), r(0.4F, 21, 3, 0, LINEAR), r(0.8F, 21, 3, 0, LINEAR),
            r(1.2F, 21, 3, 0, LINEAR), r(1.6F, 12, -4, 0, CATMULLROM), r(2.0F, 18, 4, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 22, 0, 0, CATMULLROM), r(1.2F, 24, 0, 0, CATMULLROM),
            r(1.6F, 14, -8, 0, CATMULLROM), r(2.0F, 22, 0, 0, CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, -8, 0, -3, CATMULLROM), r(2.0F, -8, 0, -3, CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 7, 0, 3, CATMULLROM), r(2.0F, 7, 0, 3, CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, -0.7F, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM)))
        .build();

    // ------------------------------------------------ BUILDER lane (fallbacks) ---
    // The authored bedrock JSON of the same name replaces these on the motion
    // engine; they fix length and contact beats (see BuilderWorkGoal#beat).

    /**
     * BUILD_PLACE (1.6 s loop): reach forward and down, set the block, two
     * seating taps at 0.90 s and 1.20 s (ticks 18 and 24), recover.
     */
    public static final AnimationDefinition BUILD_PLACE = AnimationDefinition.Builder
        .withLength(1.6F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -40, -8, 6, CATMULLROM), r(0.4F, -82, -4, 2, CATMULLROM),
            r(0.6F, -74, -4, 2, CATMULLROM), r(0.8F, -96, -8, 4, CATMULLROM),
            r(0.9F, -66, -8, 4, LINEAR), r(1.1F, -96, -8, 4, CATMULLROM),
            r(1.2F, -66, -8, 4, LINEAR), r(1.6F, -40, -8, 6, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -36, 10, -4, CATMULLROM), r(0.4F, -78, 8, -2, CATMULLROM),
            r(0.6F, -70, 8, -2, CATMULLROM), r(1.2F, -58, 12, -4, CATMULLROM),
            r(1.6F, -36, 10, -4, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 10, 0, 0, CATMULLROM), r(0.5F, 26, 2, 0, CATMULLROM),
            r(0.9F, 22, 2, 0, LINEAR), r(1.2F, 22, 2, 0, LINEAR), r(1.6F, 10, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 12, 0, 0, CATMULLROM), r(0.5F, 28, 0, 0, CATMULLROM),
            r(1.3F, 24, -6, 0, CATMULLROM), r(1.6F, 12, 0, 0, CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, -6, 0, -3, CATMULLROM), r(0.5F, -14, 0, -3, CATMULLROM), r(1.6F, -6, 0, -3, CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 6, 0, 3, CATMULLROM), r(0.5F, 10, 0, 3, CATMULLROM), r(1.6F, 6, 0, 3, CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -0.3F, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, -1.0F, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0, -0.3F, 0), CATMULLROM)))
        .build();

    /** BUILD_HAMMER (1.0 s loop): overhead hammer beat, contact at 0.45 s (tick 9). */
    public static final AnimationDefinition BUILD_HAMMER = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -150, -6, 10, CATMULLROM), r(0.3F, -170, -6, 12, CATMULLROM),
            r(0.45F, -92, -6, 6, LINEAR), r(0.6F, -96, -6, 6, CATMULLROM),
            r(1.0F, -150, -6, 10, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -80, 14, -6, CATMULLROM), r(0.45F, -84, 14, -6, CATMULLROM),
            r(1.0F, -80, 14, -6, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, -4, 0, 0, CATMULLROM), r(0.3F, -8, 0, 0, CATMULLROM),
            r(0.45F, 8, 0, 0, LINEAR), r(1.0F, -4, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, -18, 0, 0, CATMULLROM), r(0.45F, -10, 0, 0, LINEAR), r(1.0F, -18, 0, 0, CATMULLROM)))
        .build();

    /**
     * CARRY_PLANKS (1.2 s loop, walk overlay): both forearms under a bundle at
     * chest height; the elbows dip a little on every step for the weight.
     */
    public static final AnimationDefinition CARRY_PLANKS = AnimationDefinition.Builder
        .withLength(1.2F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -58, -14, 6, CATMULLROM), r(0.3F, -54, -14, 6, CATMULLROM),
            r(0.6F, -58, -14, 6, CATMULLROM), r(0.9F, -54, -14, 6, CATMULLROM),
            r(1.2F, -58, -14, 6, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -58, 14, -6, CATMULLROM), r(0.3F, -54, 14, -6, CATMULLROM),
            r(0.6F, -58, 14, -6, CATMULLROM), r(0.9F, -54, 14, -6, CATMULLROM),
            r(1.2F, -58, 14, -6, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, -6, 0, 0, CATMULLROM), r(0.6F, -5, 0, 0, CATMULLROM), r(1.2F, -6, 0, 0, CATMULLROM)))
        .build();
}
