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

    /**
     * WHET_AXE (RING-1 lane, Sharpened Axes; 3.5 s loop = LumberWhetting.WHET_TICKS).
     * Leans over the grindstone and draws the axe edge across the wheel in four
     * uneven strokes; blade meets the stone at 0.60 / 1.30 / 1.90 / 2.50 s (ticks
     * 12 / 26 / 38 / 50, the sparks), then lifts the axe to eye height to sight
     * the edge before settling. Authored twin: tools/blender/pipeline/clips/craft/author_whet_axe.py.
     */
    public static final AnimationDefinition WHET_AXE = AnimationDefinition.Builder
        .withLength(3.5F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -30, -4, 0, CATMULLROM), r(0.34F, -48, -6, 0, CATMULLROM),
            r(0.47F, -56, -6, 0, CATMULLROM), r(0.6F, -41, -6, 0, LINEAR), r(0.82F, -44, -6, 0, CATMULLROM),
            r(1.17F, -56, -6, 0, CATMULLROM), r(1.3F, -40, -6, 0, LINEAR), r(1.52F, -44, -6, 0, CATMULLROM),
            r(1.77F, -55, -6, 0, CATMULLROM), r(1.9F, -39, -6, 0, LINEAR), r(2.12F, -44, -6, 0, CATMULLROM),
            r(2.37F, -56, -6, 0, CATMULLROM), r(2.5F, -40, -6, 0, LINEAR), r(2.72F, -46, -6, 0, CATMULLROM),
            r(3.05F, -86, -12, 6, CATMULLROM), r(3.22F, -80, -10, 4, CATMULLROM),
            r(3.5F, -30, -4, 0, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -26, 10, 0, CATMULLROM), r(0.34F, -46, 16, 0, CATMULLROM),
            r(0.47F, -52, 16, 0, CATMULLROM), r(0.6F, -42, 18, 0, LINEAR), r(0.82F, -45, 16, 0, CATMULLROM),
            r(1.17F, -52, 16, 0, CATMULLROM), r(1.3F, -41, 18, 0, LINEAR), r(1.52F, -45, 16, 0, CATMULLROM),
            r(1.77F, -51, 16, 0, CATMULLROM), r(1.9F, -40, 18, 0, LINEAR), r(2.12F, -45, 16, 0, CATMULLROM),
            r(2.37F, -52, 16, 0, CATMULLROM), r(2.5F, -41, 18, 0, LINEAR), r(2.72F, -42, 14, 0, CATMULLROM),
            r(3.05F, -70, 8, 0, CATMULLROM), r(3.5F, -26, 10, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 0, 0, CATMULLROM), r(0.45F, 18, 0, 0, CATMULLROM),
            r(0.6F, 20, 0, 0, CATMULLROM), r(1.3F, 20, 2, 0, CATMULLROM), r(1.9F, 21, -1, 0, CATMULLROM),
            r(2.5F, 20, 0, 0, CATMULLROM), r(3.05F, 6, -4, 0, CATMULLROM), r(3.5F, 4, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 10, 0, 0, CATMULLROM), r(0.45F, 24, 0, 0, CATMULLROM), r(2.8F, 26, 0, 0, CATMULLROM),
            r(3.05F, -6, 10, 0, CATMULLROM), r(3.22F, -4, 8, 0, CATMULLROM), r(3.5F, 10, 0, 0, CATMULLROM)))
        .build();

    /**
     * FISHER_NET (RING-1 lane, Fisher's Nets; 4.0 s loop = FisherNetGoal.NET_TICKS).
     * Hand-over-hand on the head rope (pulls at 0.35 / 0.8 / 1.3 / 1.85 s), the
     * net lifted and shaken out (catch in the bag at 3.0 s, tick 60), a wind-up
     * and the toss that sets it again (float lands at 3.5 s, tick 70), then a
     * settle watching the float. Authored twin: tools/blender/pipeline/clips/craft/author_fisher_net.py.
     */
    public static final AnimationDefinition FISHER_NET = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -40, -6, 0, CATMULLROM), r(0.2F, -78, -6, 0, CATMULLROM), r(0.35F, -74, -6, 0, LINEAR),
            r(0.6F, -32, -8, 0, CATMULLROM), r(0.95F, -80, -6, 0, CATMULLROM), r(1.3F, -76, -6, 0, LINEAR),
            r(1.55F, -30, -8, 0, CATMULLROM), r(2.0F, -78, -6, 0, CATMULLROM), r(2.4F, -62, -10, 0, CATMULLROM),
            r(2.8F, -112, -12, 0, CATMULLROM), r(3.0F, -106, -12, 0, CATMULLROM),
            r(3.1F, -100, -12, 0, CATMULLROM), r(3.2F, -106, -12, 0, CATMULLROM),
            r(3.3F, -20, -14, 0, CATMULLROM), r(3.5F, -122, -8, 0, LINEAR),
            r(3.7F, -70, -6, 0, CATMULLROM), r(4.0F, -40, -6, 0, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -44, 6, 0, CATMULLROM), r(0.35F, -34, 8, 0, CATMULLROM), r(0.55F, -80, 6, 0, CATMULLROM),
            r(0.8F, -76, 6, 0, LINEAR), r(1.05F, -32, 8, 0, CATMULLROM), r(1.3F, -34, 8, 0, CATMULLROM),
            r(1.6F, -80, 6, 0, CATMULLROM), r(1.85F, -76, 6, 0, LINEAR), r(2.15F, -30, 8, 0, CATMULLROM),
            r(2.4F, -60, 10, 0, CATMULLROM), r(2.8F, -110, 12, 0, CATMULLROM), r(3.0F, -104, 12, 0, CATMULLROM),
            r(3.1F, -98, 12, 0, CATMULLROM), r(3.2F, -104, 12, 0, CATMULLROM),
            r(3.3F, -22, 14, 0, CATMULLROM), r(3.5F, -118, 8, 0, LINEAR),
            r(3.7F, -66, 6, 0, CATMULLROM), r(4.0F, -44, 6, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 8, 0, 0, CATMULLROM), r(0.35F, 4, -4, 0, CATMULLROM), r(0.8F, 4, 4, 0, CATMULLROM),
            r(1.3F, 3, -4, 0, CATMULLROM), r(1.85F, 2, 5, 0, CATMULLROM), r(2.4F, 0, 0, 0, CATMULLROM),
            r(2.8F, -6, 0, 0, CATMULLROM), r(3.2F, -4, 0, 0, CATMULLROM), r(3.3F, -2, 14, 0, CATMULLROM),
            r(3.5F, 12, -6, 0, LINEAR), r(3.7F, 10, -2, 0, CATMULLROM), r(4.0F, 8, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 18, 0, 0, CATMULLROM), r(1.85F, 20, 0, 0, CATMULLROM), r(2.8F, 4, 0, 0, CATMULLROM),
            r(3.2F, 8, 0, 0, CATMULLROM), r(3.5F, 6, -4, 0, CATMULLROM), r(3.8F, 14, 0, 0, CATMULLROM),
            r(4.0F, 18, 0, 0, CATMULLROM)))
        .build();

    // ------------------------------------------------------------------
    // FISHER v3 (fisher lane, 27 Sep): seated rod fishing on the shore chair,
    // one clip per FisherWorkGoal phase window (SettlerModel.applyFishingPose,
    // client.render.FisherCastClock): cast 1.70 s (release 0.80, bobber lands
    // 1.25), wait 4.00 s loop (+ fisher_wait__v2 JSON variant), strike + reel
    // 2.20 s, land 2.50 s (fish dangles and flaps 0.45-1.75, grab 2.05). Authored JSON wins on the motion engine;
    // these twins are generated from it by tools/gen_fisher_v3_twins.py.
    // ------------------------------------------------------------------
    public static final AnimationDefinition FISHER_CAST_V3 = AnimationDefinition.Builder
        .withLength(1.70F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.000F, 7.0F, 0.0F, 0.0F, CATMULLROM), r(0.100F, 5.7F, -1.2F, 0.0F, CATMULLROM),
            r(0.233F, 2.9F, -4.4F, -0.1F, CATMULLROM), r(0.350F, 0.2F, -7.9F, -1.1F, CATMULLROM),
            r(0.533F, -3.5F, -12.6F, -2.0F, CATMULLROM), r(0.633F, -3.8F, -12.8F, -2.0F, CATMULLROM),
            r(0.700F, 2.8F, -6.6F, -0.7F, CATMULLROM), r(0.800F, 15.0F, 5.0F, 1.0F, CATMULLROM),
            r(0.883F, 17.3F, 7.2F, 1.0F, CATMULLROM), r(1.033F, 16.5F, 6.6F, 0.9F, CATMULLROM),
            r(1.117F, 14.3F, 5.0F, 0.6F, CATMULLROM), r(1.250F, 10.0F, 1.7F, 0.1F, CATMULLROM),
            r(1.450F, 7.6F, 0.1F, 0.0F, CATMULLROM), r(1.700F, 7.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.000F, 9.0F, 0.0F, 0.0F, CATMULLROM), r(0.067F, 8.4F, 1.5F, 0.0F, CATMULLROM),
            r(0.200F, 4.9F, 6.8F, 0.0F, CATMULLROM), r(0.333F, 0.2F, 9.2F, 0.7F, CATMULLROM),
            r(0.483F, -4.5F, 10.7F, 1.8F, CATMULLROM), r(0.583F, -5.3F, 9.7F, 1.9F, CATMULLROM),
            r(0.700F, -4.5F, -1.6F, 0.7F, CATMULLROM), r(0.800F, -0.4F, -7.4F, -0.0F, CATMULLROM),
            r(0.867F, 5.4F, -4.7F, -0.0F, CATMULLROM), r(1.017F, 12.1F, -2.1F, 0.0F, CATMULLROM),
            r(1.117F, 12.7F, -0.9F, 0.0F, CATMULLROM), r(1.250F, 12.3F, 0.2F, 0.0F, CATMULLROM),
            r(1.317F, 11.3F, 0.1F, 0.0F, CATMULLROM), r(1.483F, 9.9F, 0.1F, 0.0F, CATMULLROM),
            r(1.700F, 9.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.000F, -36.0F, -8.0F, 6.0F, CATMULLROM), r(0.117F, -56.5F, -9.4F, 8.9F, CATMULLROM),
            r(0.217F, -75.2F, -9.6F, 9.9F, CATMULLROM), r(0.283F, -84.0F, -7.5F, 9.0F, CATMULLROM),
            r(0.400F, -97.9F, -2.3F, 6.9F, CATMULLROM), r(0.500F, -108.0F, 0.0F, 6.0F, CATMULLROM),
            r(0.633F, -111.0F, -0.3F, 7.1F, CATMULLROM), r(0.700F, -109.5F, -6.1F, 7.7F, CATMULLROM),
            r(0.800F, -87.2F, -9.3F, 7.3F, CATMULLROM), r(0.900F, -54.5F, -8.0F, 6.0F, CATMULLROM),
            r(1.000F, -46.0F, -8.0F, 6.0F, CATMULLROM), r(1.100F, -42.2F, -8.0F, 6.0F, CATMULLROM),
            r(1.217F, -39.8F, -8.0F, 6.0F, CATMULLROM), r(1.250F, -39.3F, -8.0F, 6.0F, CATMULLROM),
            r(1.500F, -36.5F, -8.0F, 6.0F, CATMULLROM), r(1.583F, -36.1F, -8.0F, 6.0F, CATMULLROM),
            r(1.700F, -36.0F, -8.0F, 6.0F, CATMULLROM)))
        .addAnimation("right_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -34.0F, 0.0F, 0.0F, CATMULLROM), r(0.150F, -39.6F, 0.0F, 0.0F, CATMULLROM),
            r(0.267F, -39.3F, 0.0F, 0.0F, CATMULLROM), r(0.467F, -36.1F, 0.0F, 0.0F, CATMULLROM),
            r(0.517F, -36.2F, 0.0F, 0.0F, CATMULLROM), r(0.600F, -39.7F, 0.0F, 0.0F, CATMULLROM),
            r(0.683F, -30.8F, 0.0F, 0.0F, CATMULLROM), r(0.800F, -10.8F, 0.0F, 0.0F, CATMULLROM),
            r(0.917F, -13.7F, 0.0F, 0.0F, CATMULLROM), r(1.017F, -22.7F, 0.0F, 0.0F, CATMULLROM),
            r(1.083F, -25.3F, 0.0F, 0.0F, CATMULLROM), r(1.233F, -29.7F, 0.0F, 0.0F, CATMULLROM),
            r(1.250F, -30.1F, 0.0F, 0.0F, CATMULLROM), r(1.400F, -32.7F, 0.0F, 0.0F, CATMULLROM),
            r(1.533F, -33.8F, 0.0F, 0.0F, CATMULLROM), r(1.700F, -34.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_item", new AnimationChannel(ROTATION,
            r(0.000F, 0.0F, 0.0F, 0.0F, CATMULLROM), r(0.200F, -0.0F, 0.1F, 0.0F, CATMULLROM),
            r(0.267F, -0.5F, 1.6F, 0.0F, CATMULLROM), r(0.350F, -1.6F, 5.1F, 0.0F, CATMULLROM),
            r(0.500F, -4.0F, 12.0F, 0.0F, CATMULLROM), r(0.600F, -5.9F, 14.9F, 0.0F, CATMULLROM),
            r(0.700F, 2.3F, 7.5F, 0.0F, CATMULLROM), r(0.800F, 15.5F, 0.0F, 0.0F, CATMULLROM),
            r(0.867F, 17.8F, 0.0F, 0.0F, CATMULLROM), r(0.983F, 15.7F, 0.0F, 0.0F, CATMULLROM),
            r(1.150F, 5.8F, 0.0F, 0.0F, CATMULLROM), r(1.250F, 3.0F, 0.0F, 0.0F, CATMULLROM),
            r(1.400F, 1.0F, 0.0F, 0.0F, CATMULLROM), r(1.700F, 0.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.000F, -20.0F, 14.0F, -4.0F, CATMULLROM), r(0.100F, -29.2F, 16.7F, -5.0F, CATMULLROM),
            r(0.200F, -42.1F, 20.7F, -6.2F, CATMULLROM), r(0.300F, -54.2F, 24.7F, -7.0F, CATMULLROM),
            r(0.383F, -63.6F, 27.8F, -7.6F, CATMULLROM), r(0.500F, -70.0F, 30.0F, -8.0F, CATMULLROM),
            r(0.617F, -65.8F, 28.0F, -7.5F, CATMULLROM), r(0.667F, -62.2F, 26.3F, -7.1F, CATMULLROM),
            r(0.800F, -52.0F, 22.0F, -6.0F, CATMULLROM), r(0.917F, -42.7F, 19.2F, -5.0F, CATMULLROM),
            r(1.000F, -35.8F, 17.4F, -4.4F, CATMULLROM), r(1.067F, -31.5F, 16.4F, -4.0F, CATMULLROM),
            r(1.150F, -28.3F, 15.6F, -4.0F, CATMULLROM), r(1.250F, -25.4F, 14.9F, -4.0F, CATMULLROM),
            r(1.450F, -21.6F, 14.2F, -4.0F, CATMULLROM), r(1.583F, -20.3F, 14.0F, -4.0F, CATMULLROM),
            r(1.700F, -20.0F, 14.0F, -4.0F, CATMULLROM)))
        .addAnimation("left_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -52.0F, 0.0F, 0.0F, CATMULLROM), r(0.117F, -56.2F, 0.0F, 0.0F, CATMULLROM),
            r(0.217F, -62.3F, 0.0F, 0.0F, CATMULLROM), r(0.283F, -67.6F, 0.0F, 0.0F, CATMULLROM),
            r(0.367F, -74.3F, 0.0F, 0.0F, CATMULLROM), r(0.500F, -80.0F, 0.0F, 0.0F, CATMULLROM),
            r(0.600F, -76.3F, 0.0F, 0.0F, CATMULLROM), r(0.733F, -66.0F, 0.0F, 0.0F, CATMULLROM),
            r(0.800F, -62.0F, 0.0F, 0.0F, CATMULLROM), r(0.867F, -59.6F, 0.0F, 0.0F, CATMULLROM),
            r(1.017F, -56.1F, 0.0F, 0.0F, CATMULLROM), r(1.067F, -55.4F, 0.0F, 0.0F, CATMULLROM),
            r(1.250F, -53.6F, 0.0F, 0.0F, CATMULLROM), r(1.633F, -52.0F, 0.0F, 0.0F, CATMULLROM),
            r(1.700F, -52.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            r(0.000F, 6.2F, 0.0F, -0.0F, CATMULLROM), r(0.183F, 6.1F, 0.0F, 0.4F, CATMULLROM),
            r(0.517F, 2.7F, 0.0F, 0.5F, CATMULLROM), r(0.567F, 2.3F, 0.0F, 0.4F, CATMULLROM),
            r(0.700F, 0.9F, 0.0F, -0.9F, CATMULLROM), r(0.800F, 1.4F, 0.0F, -2.4F, CATMULLROM),
            r(0.867F, 7.7F, 0.0F, -0.9F, CATMULLROM), r(0.950F, 10.6F, 0.0F, -0.0F, CATMULLROM),
            r(1.150F, 10.7F, 0.0F, 0.4F, CATMULLROM), r(1.250F, 9.5F, 0.0F, 0.5F, CATMULLROM),
            r(1.417F, 7.1F, 0.0F, 0.1F, CATMULLROM), r(1.483F, 6.7F, 0.0F, 0.1F, CATMULLROM),
            r(1.700F, 6.2F, 0.0F, -0.0F, CATMULLROM)))
        .build();

    public static final AnimationDefinition FISHER_WAIT = AnimationDefinition.Builder
        .withLength(4.00F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.000F, 7.0F, 0.0F, 0.0F, CATMULLROM), r(1.183F, 7.7F, 0.8F, 0.5F, CATMULLROM),
            r(2.067F, 6.4F, 0.3F, -0.0F, CATMULLROM), r(3.100F, 7.6F, -0.6F, -0.4F, CATMULLROM),
            r(4.000F, 7.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.000F, 9.0F, 0.0F, 0.0F, CATMULLROM), r(0.400F, 9.3F, 1.3F, 0.0F, CATMULLROM),
            r(0.900F, 10.0F, 3.0F, 0.0F, CATMULLROM), r(1.383F, 10.9F, 0.0F, -0.0F, CATMULLROM),
            r(2.117F, 8.1F, -3.9F, -1.0F, CATMULLROM), r(2.150F, 8.0F, -4.0F, -1.0F, CATMULLROM),
            r(2.333F, 8.1F, -3.9F, -0.9F, CATMULLROM), r(3.183F, 9.5F, -1.3F, 0.0F, CATMULLROM),
            r(4.000F, 9.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.000F, -36.0F, -8.0F, 6.0F, CATMULLROM), r(0.967F, -36.3F, -8.0F, 6.0F, CATMULLROM),
            r(1.233F, -37.3F, -8.0F, 6.0F, CATMULLROM), r(1.450F, -37.0F, -8.0F, 6.0F, CATMULLROM),
            r(1.833F, -35.4F, -7.6F, 6.0F, CATMULLROM), r(2.567F, -34.5F, -7.0F, 6.0F, CATMULLROM),
            r(4.000F, -36.0F, -8.0F, 6.0F, CATMULLROM)))
        .addAnimation("right_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -34.0F, 0.0F, 0.0F, CATMULLROM), r(1.333F, -35.9F, 0.0F, 0.0F, CATMULLROM),
            r(1.650F, -33.6F, 0.0F, 0.0F, CATMULLROM), r(2.567F, -32.5F, 0.0F, 0.0F, CATMULLROM),
            r(4.000F, -34.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_item", new AnimationChannel(ROTATION,
            r(0.000F, 0.0F, 0.0F, 0.0F, CATMULLROM), r(1.267F, -0.2F, 0.0F, 0.0F, CATMULLROM),
            r(1.417F, -3.0F, 0.0F, 0.0F, CATMULLROM), r(1.567F, -0.5F, 0.0F, 0.0F, CATMULLROM),
            r(1.917F, 0.0F, 0.0F, 0.0F, CATMULLROM), r(4.000F, 0.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.000F, -20.0F, 14.0F, -4.0F, CATMULLROM), r(1.600F, -23.0F, 14.0F, -4.0F, CATMULLROM),
            r(2.200F, -21.4F, 14.0F, -4.0F, CATMULLROM), r(2.950F, -19.0F, 14.0F, -4.0F, CATMULLROM),
            r(4.000F, -20.0F, 14.0F, -4.0F, CATMULLROM)))
        .addAnimation("left_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -52.0F, 0.0F, 0.0F, CATMULLROM), r(0.567F, -50.9F, 0.0F, 0.0F, CATMULLROM),
            r(1.500F, -48.0F, 0.0F, 0.0F, CATMULLROM), r(2.533F, -49.3F, 0.0F, 0.0F, CATMULLROM),
            r(3.883F, -52.0F, 0.0F, 0.0F, CATMULLROM), r(4.000F, -52.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            r(0.000F, 6.2F, 0.0F, -0.0F, CATMULLROM), r(1.517F, 6.3F, 0.0F, 0.0F, CATMULLROM),
            r(2.450F, 6.0F, 0.0F, 0.0F, CATMULLROM), r(3.533F, 6.3F, 0.0F, -0.0F, CATMULLROM),
            r(4.000F, 6.2F, 0.0F, -0.0F, CATMULLROM)))
        .build();

    public static final AnimationDefinition FISHER_STRIKE_REEL = AnimationDefinition.Builder
        .withLength(2.20F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.000F, 7.0F, 0.0F, 0.0F, CATMULLROM), r(0.117F, -3.7F, -2.5F, 0.0F, CATMULLROM),
            r(0.167F, -6.0F, -3.0F, 0.0F, CATMULLROM), r(0.533F, -3.1F, -1.2F, 0.0F, CATMULLROM),
            r(0.600F, -2.7F, -0.8F, 0.0F, CATMULLROM), r(0.967F, -1.0F, 1.0F, 0.0F, CATMULLROM),
            r(1.633F, -3.0F, -1.0F, 0.5F, CATMULLROM), r(1.950F, -2.4F, -0.4F, 0.2F, CATMULLROM),
            r(2.200F, -2.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.000F, 9.0F, 0.0F, 0.0F, CATMULLROM), r(0.100F, 18.3F, 0.8F, 0.0F, CATMULLROM),
            r(0.200F, 14.1F, 1.1F, 0.0F, CATMULLROM), r(0.300F, 12.7F, 1.8F, 0.0F, CATMULLROM),
            r(1.167F, 15.1F, -1.9F, 0.0F, CATMULLROM), r(1.950F, 15.8F, -0.4F, 0.0F, CATMULLROM),
            r(2.200F, 16.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.000F, -36.0F, -8.0F, 6.0F, CATMULLROM), r(0.117F, -54.8F, -18.0F, -2.4F, CATMULLROM),
            r(0.167F, -59.8F, -21.5F, -5.0F, CATMULLROM), r(0.283F, -58.0F, -25.3F, -6.8F, CATMULLROM),
            r(0.400F, -41.9F, -28.7F, -9.1F, CATMULLROM), r(0.500F, -33.0F, -30.0F, -10.0F, CATMULLROM),
            r(0.617F, -34.6F, -30.0F, -10.0F, CATMULLROM), r(0.833F, -40.4F, -30.0F, -10.0F, CATMULLROM),
            r(0.933F, -40.8F, -30.0F, -10.0F, CATMULLROM), r(1.017F, -38.7F, -30.0F, -10.0F, CATMULLROM),
            r(1.183F, -32.9F, -30.0F, -10.0F, CATMULLROM), r(1.267F, -32.1F, -30.0F, -10.0F, CATMULLROM),
            r(1.350F, -34.0F, -30.0F, -10.0F, CATMULLROM), r(1.533F, -41.0F, -30.0F, -10.0F, CATMULLROM),
            r(1.617F, -41.9F, -30.0F, -10.0F, CATMULLROM), r(1.700F, -40.4F, -30.0F, -10.0F, CATMULLROM),
            r(1.883F, -34.8F, -30.0F, -10.0F, CATMULLROM), r(1.900F, -34.4F, -30.0F, -10.0F, CATMULLROM),
            r(2.017F, -35.1F, -30.0F, -10.0F, CATMULLROM), r(2.167F, -39.7F, -30.0F, -10.0F, CATMULLROM),
            r(2.200F, -40.0F, -30.0F, -10.0F, CATMULLROM)))
        .addAnimation("right_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -34.0F, 0.0F, 0.0F, CATMULLROM), r(0.133F, -39.8F, 0.0F, 0.0F, CATMULLROM),
            r(0.217F, -42.5F, 0.0F, 0.0F, CATMULLROM), r(0.400F, -48.1F, 0.0F, 0.0F, CATMULLROM),
            r(0.533F, -50.4F, 0.0F, 0.0F, CATMULLROM), r(0.617F, -51.2F, 0.0F, 0.0F, CATMULLROM),
            r(0.950F, -52.8F, 0.0F, 0.0F, CATMULLROM), r(1.433F, -52.7F, 0.0F, 0.0F, CATMULLROM),
            r(1.950F, -50.5F, 0.0F, 0.0F, CATMULLROM), r(2.200F, -50.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_item", new AnimationChannel(ROTATION,
            r(0.000F, 0.0F, 0.0F, 0.0F, CATMULLROM), r(0.117F, -5.4F, 0.0F, -13.2F, CATMULLROM),
            r(0.183F, -12.8F, 0.0F, -17.2F, CATMULLROM), r(0.283F, -21.7F, 0.0F, -21.4F, CATMULLROM),
            r(0.400F, -21.2F, 0.0F, -24.3F, CATMULLROM), r(0.567F, -19.4F, 0.0F, -25.0F, CATMULLROM),
            r(0.817F, -17.2F, 0.0F, -25.0F, CATMULLROM), r(1.033F, -17.6F, 0.0F, -25.0F, CATMULLROM),
            r(1.550F, -22.9F, 0.0F, -25.0F, CATMULLROM), r(1.950F, -21.1F, 0.0F, -25.0F, CATMULLROM),
            r(2.200F, -20.0F, 0.0F, -25.0F, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.000F, -20.0F, 14.0F, -4.0F, CATMULLROM), r(0.100F, -27.0F, 14.0F, -3.8F, CATMULLROM),
            r(0.200F, -40.5F, 14.3F, -1.8F, CATMULLROM), r(0.283F, -61.1F, 26.5F, 6.0F, CATMULLROM),
            r(0.383F, -71.9F, 36.3F, 10.0F, CATMULLROM), r(0.483F, -67.1F, 33.3F, 7.9F, CATMULLROM),
            r(0.600F, -66.1F, 20.3F, 10.1F, CATMULLROM), r(0.700F, -64.1F, 33.7F, 6.7F, CATMULLROM),
            r(0.800F, -76.2F, 29.7F, 13.4F, CATMULLROM), r(0.900F, -68.1F, 25.8F, 10.1F, CATMULLROM),
            r(1.000F, -70.7F, 36.5F, 9.1F, CATMULLROM), r(1.100F, -73.2F, 26.1F, 12.6F, CATMULLROM),
            r(1.200F, -60.8F, 22.4F, 7.5F, CATMULLROM), r(1.300F, -61.4F, 34.7F, 5.2F, CATMULLROM),
            r(1.400F, -73.7F, 31.5F, 11.7F, CATMULLROM), r(1.500F, -69.6F, 26.7F, 10.6F, CATMULLROM),
            r(1.600F, -71.0F, 39.7F, 8.8F, CATMULLROM), r(1.700F, -78.2F, 33.7F, 14.0F, CATMULLROM),
            r(1.800F, -66.9F, 24.1F, 9.9F, CATMULLROM), r(1.900F, -61.0F, 33.2F, 5.2F, CATMULLROM),
            r(2.033F, -69.9F, 33.5F, 9.3F, CATMULLROM), r(2.117F, -74.2F, 32.9F, 11.6F, CATMULLROM),
            r(2.200F, -76.2F, 33.3F, 12.5F, CATMULLROM)))
        .addAnimation("left_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -52.0F, 0.0F, 0.0F, CATMULLROM), r(0.133F, -57.9F, 0.0F, 0.0F, CATMULLROM),
            r(0.200F, -60.0F, 0.0F, 0.0F, CATMULLROM), r(0.300F, -52.6F, 0.0F, 0.0F, CATMULLROM),
            r(0.400F, -51.3F, 0.0F, 0.0F, CATMULLROM), r(0.517F, -55.8F, 0.0F, 0.0F, CATMULLROM),
            r(0.600F, -53.7F, 0.0F, 0.0F, CATMULLROM), r(0.700F, -58.5F, 0.0F, 0.0F, CATMULLROM),
            r(0.783F, -55.1F, 0.0F, 0.0F, CATMULLROM), r(0.883F, -52.8F, 0.0F, 0.0F, CATMULLROM),
            r(1.000F, -57.3F, 0.0F, 0.0F, CATMULLROM), r(1.100F, -53.3F, 0.0F, 0.0F, CATMULLROM),
            r(1.217F, -55.7F, 0.0F, 0.0F, CATMULLROM), r(1.283F, -59.6F, 0.0F, 0.0F, CATMULLROM),
            r(1.433F, -53.8F, 0.0F, 0.0F, CATMULLROM), r(1.483F, -53.5F, 0.0F, 0.0F, CATMULLROM),
            r(1.617F, -56.2F, 0.0F, 0.0F, CATMULLROM), r(1.700F, -51.9F, 0.0F, 0.0F, CATMULLROM),
            r(1.817F, -53.9F, 0.0F, 0.0F, CATMULLROM), r(1.883F, -57.9F, 0.0F, 0.0F, CATMULLROM),
            r(1.950F, -58.8F, 0.0F, 0.0F, CATMULLROM), r(2.150F, -53.1F, 0.0F, 0.0F, CATMULLROM),
            r(2.200F, -52.4F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            r(0.000F, 6.2F, 0.0F, -0.0F, CATMULLROM), r(0.117F, 8.1F, 0.0F, 0.5F, CATMULLROM),
            r(0.200F, 3.3F, 0.0F, 0.2F, CATMULLROM), r(0.267F, 0.2F, 0.0F, -0.0F, CATMULLROM),
            r(0.800F, 2.0F, 0.0F, -0.1F, CATMULLROM), r(0.850F, 2.1F, 0.0F, -0.1F, CATMULLROM),
            r(1.267F, 2.4F, 0.0F, 0.1F, CATMULLROM), r(1.833F, 1.7F, 0.0F, -0.0F, CATMULLROM),
            r(1.950F, 1.8F, 0.0F, -0.0F, CATMULLROM), r(2.200F, 2.0F, 0.0F, -0.0F, CATMULLROM)))
        .build();

    public static final AnimationDefinition FISHER_LAND_FISH = AnimationDefinition.Builder
        .withLength(2.50F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.000F, -2.0F, 0.0F, 0.0F, CATMULLROM), r(0.117F, -3.0F, 0.3F, 0.0F, CATMULLROM),
            r(0.350F, -6.8F, 1.8F, 0.0F, CATMULLROM), r(0.450F, -7.0F, 2.1F, 0.0F, CATMULLROM),
            r(1.033F, -5.0F, 3.0F, 0.0F, CATMULLROM), r(1.617F, -6.0F, 2.0F, 0.0F, CATMULLROM),
            r(1.750F, -3.1F, 3.0F, 0.3F, CATMULLROM), r(1.933F, 3.2F, 5.3F, 0.8F, CATMULLROM),
            r(2.050F, 5.0F, 6.0F, 1.0F, CATMULLROM), r(2.367F, 1.5F, 3.5F, 0.0F, CATMULLROM),
            r(2.500F, 1.0F, 3.0F, 0.0F, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.000F, 16.0F, 0.0F, 0.0F, CATMULLROM), r(0.067F, 15.3F, 0.1F, 0.0F, CATMULLROM),
            r(0.233F, 9.1F, 0.7F, 0.0F, CATMULLROM), r(0.367F, 3.3F, 1.6F, 0.0F, CATMULLROM),
            r(0.450F, 1.0F, 2.3F, 0.0F, CATMULLROM), r(0.633F, -0.2F, 4.1F, 0.0F, CATMULLROM),
            r(0.833F, -0.1F, 4.9F, 0.0F, CATMULLROM), r(1.183F, 1.1F, 1.1F, 1.0F, CATMULLROM),
            r(1.300F, 1.0F, 1.4F, 0.8F, CATMULLROM), r(1.633F, 0.0F, 4.4F, -0.0F, CATMULLROM),
            r(1.750F, 1.4F, 5.7F, 0.0F, CATMULLROM), r(1.933F, 6.4F, 7.6F, 0.0F, CATMULLROM),
            r(2.050F, 9.9F, 7.7F, 0.0F, CATMULLROM), r(2.250F, 14.3F, 6.7F, 0.0F, CATMULLROM),
            r(2.383F, 13.6F, 5.3F, 0.0F, CATMULLROM), r(2.500F, 12.0F, 4.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.000F, -40.0F, -30.0F, -10.0F, CATMULLROM), r(0.117F, -46.2F, -26.9F, -10.0F, CATMULLROM),
            r(0.200F, -55.0F, -22.5F, -10.0F, CATMULLROM), r(0.283F, -63.8F, -18.1F, -10.0F, CATMULLROM),
            r(0.400F, -70.0F, -15.0F, -10.0F, CATMULLROM), r(0.450F, -69.8F, -15.0F, -10.0F, CATMULLROM),
            r(0.750F, -67.0F, -15.0F, -10.0F, CATMULLROM), r(1.083F, -71.0F, -15.0F, -10.0F, CATMULLROM),
            r(1.450F, -68.0F, -15.0F, -10.0F, CATMULLROM), r(1.683F, -68.9F, -15.3F, -10.0F, CATMULLROM),
            r(1.783F, -68.4F, -16.6F, -9.9F, CATMULLROM), r(1.917F, -57.5F, -20.6F, -7.7F, CATMULLROM),
            r(2.000F, -49.8F, -23.0F, -6.3F, CATMULLROM), r(2.050F, -47.0F, -24.0F, -6.0F, CATMULLROM),
            r(2.167F, -44.4F, -25.2F, -6.3F, CATMULLROM), r(2.333F, -43.6F, -25.9F, -7.4F, CATMULLROM),
            r(2.500F, -44.0F, -26.0F, -8.0F, CATMULLROM)))
        .addAnimation("right_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -50.0F, 0.0F, 0.0F, CATMULLROM), r(0.100F, -46.9F, 0.0F, 0.0F, CATMULLROM),
            r(0.217F, -38.8F, 0.0F, 0.0F, CATMULLROM), r(0.317F, -32.2F, 0.0F, 0.0F, CATMULLROM),
            r(0.383F, -30.1F, 0.0F, 0.0F, CATMULLROM), r(0.450F, -30.0F, 0.0F, 0.0F, CATMULLROM),
            r(1.300F, -28.1F, 0.0F, 0.0F, CATMULLROM), r(1.600F, -29.4F, 0.0F, 0.0F, CATMULLROM),
            r(1.750F, -31.0F, 0.0F, 0.0F, CATMULLROM), r(1.783F, -32.1F, 0.0F, 0.0F, CATMULLROM),
            r(1.917F, -43.7F, 0.0F, 0.0F, CATMULLROM), r(1.983F, -49.5F, 0.0F, 0.0F, CATMULLROM),
            r(2.050F, -52.0F, 0.0F, 0.0F, CATMULLROM), r(2.500F, -50.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("right_item", new AnimationChannel(ROTATION,
            r(0.000F, -20.0F, 0.0F, -25.0F, CATMULLROM), r(0.117F, -13.8F, 0.0F, -21.9F, CATMULLROM),
            r(0.200F, -5.0F, 0.0F, -17.5F, CATMULLROM), r(0.283F, 3.8F, 0.0F, -13.1F, CATMULLROM),
            r(0.400F, 10.0F, 0.0F, -10.0F, CATMULLROM), r(0.450F, 10.0F, 0.0F, -10.0F, CATMULLROM),
            r(1.283F, 12.9F, 0.0F, -10.0F, CATMULLROM), r(1.567F, 11.5F, 0.0F, -10.0F, CATMULLROM),
            r(1.750F, 9.0F, 0.0F, -10.0F, CATMULLROM), r(1.783F, 7.7F, 0.0F, -10.3F, CATMULLROM),
            r(1.883F, -2.5F, 0.0F, -14.3F, CATMULLROM), r(2.000F, -16.5F, 0.0F, -20.4F, CATMULLROM),
            r(2.133F, -22.5F, 0.0F, -23.4F, CATMULLROM), r(2.267F, -23.4F, 0.0F, -24.6F, CATMULLROM),
            r(2.500F, -22.0F, 0.0F, -25.0F, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.000F, -77.3F, 33.0F, 13.3F, CATMULLROM), r(0.117F, -79.3F, 29.9F, 14.1F, CATMULLROM),
            r(0.200F, -84.5F, 27.1F, 15.2F, CATMULLROM), r(0.300F, -88.9F, 26.3F, 15.0F, CATMULLROM),
            r(0.400F, -83.3F, 26.0F, 11.4F, CATMULLROM), r(0.517F, -63.0F, 23.4F, 3.6F, CATMULLROM),
            r(0.617F, -52.5F, 22.1F, 0.1F, CATMULLROM), r(0.733F, -52.0F, 22.0F, 0.0F, CATMULLROM),
            r(1.300F, -52.3F, 22.0F, 0.0F, CATMULLROM), r(1.600F, -54.0F, 22.0F, 0.0F, CATMULLROM),
            r(1.667F, -55.1F, 21.7F, 0.1F, CATMULLROM), r(1.750F, -58.0F, 20.4F, 0.3F, CATMULLROM),
            r(1.867F, -61.7F, 18.4F, 0.6F, CATMULLROM), r(2.000F, -62.0F, 17.6F, -0.5F, CATMULLROM),
            r(2.133F, -61.4F, 17.1F, -2.1F, CATMULLROM), r(2.183F, -63.0F, 17.1F, -2.3F, CATMULLROM),
            r(2.333F, -65.7F, 15.4F, -2.0F, CATMULLROM), r(2.500F, -62.0F, 12.0F, -2.0F, CATMULLROM)))
        .addAnimation("left_forearm", new AnimationChannel(ROTATION,
            r(0.000F, -47.9F, 0.0F, 0.0F, CATMULLROM), r(0.117F, -44.8F, 0.0F, 0.0F, CATMULLROM),
            r(0.200F, -33.0F, 0.0F, 0.0F, CATMULLROM), r(0.300F, -22.5F, 0.0F, 0.0F, CATMULLROM),
            r(0.400F, -26.8F, 0.0F, 0.0F, CATMULLROM), r(0.483F, -44.5F, 0.0F, 0.0F, CATMULLROM),
            r(0.583F, -66.4F, 0.0F, 0.0F, CATMULLROM), r(0.667F, -70.0F, 0.0F, 0.0F, CATMULLROM),
            r(1.550F, -66.0F, 0.0F, 0.0F, CATMULLROM), r(1.633F, -65.9F, 0.0F, 0.0F, CATMULLROM),
            r(1.700F, -64.0F, 0.0F, 0.0F, CATMULLROM), r(1.817F, -59.1F, 0.0F, 0.0F, CATMULLROM),
            r(1.883F, -59.3F, 0.0F, 0.0F, CATMULLROM), r(2.033F, -66.4F, 0.0F, 0.0F, CATMULLROM),
            r(2.117F, -66.9F, 0.0F, 0.0F, CATMULLROM), r(2.200F, -71.5F, 0.0F, 0.0F, CATMULLROM),
            r(2.300F, -78.0F, 0.0F, 0.0F, CATMULLROM), r(2.367F, -80.1F, 0.0F, 0.0F, CATMULLROM),
            r(2.500F, -84.0F, 0.0F, 0.0F, CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            r(0.000F, 2.1F, 0.0F, -0.0F, CATMULLROM), r(0.217F, 1.9F, 0.0F, -0.1F, CATMULLROM),
            r(0.450F, -0.0F, 0.0F, -0.1F, CATMULLROM), r(1.267F, 0.7F, 0.0F, 0.0F, CATMULLROM),
            r(1.700F, 0.2F, 0.0F, -0.1F, CATMULLROM), r(1.750F, 0.2F, 0.0F, -0.2F, CATMULLROM),
            r(1.900F, 1.8F, 0.0F, -0.2F, CATMULLROM), r(2.050F, 4.3F, 0.0F, -0.1F, CATMULLROM),
            r(2.183F, 5.4F, 0.0F, 0.1F, CATMULLROM), r(2.500F, 3.6F, 0.0F, 0.1F, CATMULLROM)))
        .build();


}
