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
 * Transcribed from docs/ANIMATION_CATALOGUE.md phase A1 (23 clips) --
 * that document is the source of truth for every value below.
 *
 * Sound sync contracts (accent frame -> tick, of a `length*20`-tick cycle):
 *  WALK: footfalls t=0.25s/0.75s (vanilla step sound, no custom contract)
 *  FARM_TILL: t=0.60s -> tick 12 of 30 (FarmerWorkGoal, WORK_DURATION-tied)
 *  FARM_PLANT: t=0.70s -> tick 14 of 40
 *  FARM_HARVEST: t=0.45s -> tick 9 of 36 (grab); t=0.90s -> tick 18 (stow)
 *  FARM_WATER: t=0.80s -> tick 16 of 48
 *  CHOP: t=0.55s -> tick 11 of 20 (LumbererWorkGoal, live, unchanged)
 *  LIMB_BRANCHES: t=0.30s -> tick 6 of 26; t=0.95s -> tick 19 of 26
 *  HAUL_LOG: t=1.20s -> tick 24 of 48
 *  MELEE: t=0.22s -> tick 4 of 10 (damage tick)
 *  CLIMB_LADDER: t=0.25s/0.75s -> ticks 5/15 of 20
 *  WAKE_STRETCH: t=1.20s -> tick 24 of 52 (one-shot)
 */
public final class SettlerAnimations {

    // -------------------------------------------------------------- life ---

    /** Slow breath, tiny arm sway, an idle glance, weight shift. 4s loop. */
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
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(1.5F, -7, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(0, -5, 0), CATMULLROM),
            new Keyframe(3.1F, KeyframeAnimations.degreeVec(2, 3, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0, -0.7F, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(3.6F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, -2), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM)))
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
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM)))
        .build();

    /** Settled by the fire: sunk down, legs forward, arms on knees. 6s loop.
     *  Defines the SEATED base pose (root/legs) -- do not change without
     *  checking future reuse (EAT_AT_TABLE, SCRIBE_WRITE, CAPTIVE, A2+). */
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
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(11, 0, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.scaleVec(1.012, 1.018, 1.012), CATMULLROM),
            new Keyframe(4.8F, KeyframeAnimations.scaleVec(1.006, 1.009, 1.006), CATMULLROM),
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
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-5, 0, 0), CATMULLROM),
            new Keyframe(6.0F, KeyframeAnimations.degreeVec(-5, 0, 0), CATMULLROM)))
        .build();

    /** One-shot: arms thrown up, two small hops, legs tuck, cape flies. 2s. */
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
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-8, 6, 0), CATMULLROM),
            new Keyframe(1.35F, KeyframeAnimations.degreeVec(-8, -6, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-14, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-12, 0, 0), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-26, 0, -6), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-26, 0, -6), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-26, 0, 6), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-26, 0, 6), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM)))
        .build();

    /** Horizontal body in bed; renderer supplies the lie-down rotation, this
     *  clip only supplies breath and a slow head roll. 8s loop. */
    public static final AnimationDefinition SLEEP_IN_BED = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.scaleVec(1.02, 1.03, 1.02), CATMULLROM),
            new Keyframe(3.6F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(5.6F, KeyframeAnimations.scaleVec(1.018, 1.026, 1.018), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 8, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(0, 8, 0), CATMULLROM),
            new Keyframe(3.6F, KeyframeAnimations.degreeVec(2, -6, 0), CATMULLROM),
            new Keyframe(6.4F, KeyframeAnimations.degreeVec(2, -6, 0), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(0, 8, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, 12), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(-4, 0, 12), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, -12), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(-4, 0, -12), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-2, 0, 0), CATMULLROM),
            new Keyframe(8.0F, KeyframeAnimations.degreeVec(-2, 0, 0), CATMULLROM)))
        .build();

    /** One-shot: wide arm stretch, arch, hold, slump. 2.6s. */
    public static final AnimationDefinition WAKE_STRETCH = AnimationDefinition.Builder
        .withLength(2.6F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-96, 0, -26), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-152, 0, -34), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-148, 0, -33), CATMULLROM),
            new Keyframe(2.25F, KeyframeAnimations.degreeVec(-52, 0, -14), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, 4), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-96, 0, 26), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-152, 0, 34), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-148, 0, 33), CATMULLROM),
            new Keyframe(2.25F, KeyframeAnimations.degreeVec(-52, 0, 14), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(-8, 0, 4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-16, 0, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-15, 0, 0), CATMULLROM),
            new Keyframe(2.3F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.scaleVec(1.025, 1.03, 1.025), CATMULLROM),
            new Keyframe(2.1F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-20, 0, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-18, 0, 0), CATMULLROM),
            new Keyframe(2.3F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-6, 0, -4), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(0, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(0, 0, 3), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, 1.2F, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.posVec(0, 1.1F, 0), CATMULLROM),
            new Keyframe(2.35F, KeyframeAnimations.posVec(0, -0.6F, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-10, 0, 0), CATMULLROM),
            new Keyframe(2.35F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .build();

    // -------------------------------------------------------- locomotion ---

    /** The everyday gait, consumed through animateWalk. 1s loop. */
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
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 4, 1.5F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(3, -4, -1.5F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(3, 4, 1.5F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(9, 0, -3), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(9, 0, -3), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM)))
        .build();

    /** Errand jog: forward pitch, tight pumping elbows, level head. 0.7s loop. */
    public static final AnimationDefinition WALK_HURRIED = AnimationDefinition.Builder
        .withLength(0.7F).looping()
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-40, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-40, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-40, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(42, 0, 6), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-34, 0, 6), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(42, 0, 6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-42, 0, -6), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(34, 0, -6), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-42, 0, -6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(11, 6, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(11, -6, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(11, 6, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .build();

    /** Flat-out flight: arms flailing above the head, out of phase. 0.55s loop. */
    public static final AnimationDefinition RUN_PANIC = AnimationDefinition.Builder
        .withLength(0.6F).looping()
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-52, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(52, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-52, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(52, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-52, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(52, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-96, 0, -22), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-128, 0, -30), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-96, 0, -22), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-128, 0, 30), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-96, 0, 22), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-128, 0, 30), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(16, 9, 3), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(16, -9, -3), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(16, 9, 3), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.posVec(0, -0.7F, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.7F, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-12, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-12, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0, 0.4F, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Injured, asymmetric hitch -- one bad step, one good step. 1.4s loop. */
    public static final AnimationDefinition WALK_LIMP = AnimationDefinition.Builder
        .withLength(1.4F).looping()
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-16, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-16, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(30, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(30, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0, -2.2F, 0), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0, -0.6F, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 7), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(12, 0, 14), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(8, 0, 2), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(8, 0, 7), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 14), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-4, 0, 18), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(10, 0, 14), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-26, 0, -4), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(26, 0, -4), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-26, 0, -4), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(10, 0, 9), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 5), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(10, 0, 9), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(3, 0, 5), CATMULLROM)))
        .build();

    /** Hunched, narrow, sweeping head -- moving quietly after dark. 1.6s loop. */
    public static final AnimationDefinition CREEP_NIGHT = AnimationDefinition.Builder
        .withLength(1.6F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -3, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0, -3, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20, 0, -5), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(0, 0, -5), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(20, 0, -5), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, -5), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-20, 0, -5), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(20, 0, 5), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(0, 0, 5), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-20, 0, 5), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, 5), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(20, 0, 5), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, 3, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(22, -3, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(22, 3, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-28, 0, 10), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-22, 0, 10), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-28, 0, 10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-28, 0, -10), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-22, 0, -10), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-28, 0, -10), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 24, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-8, -24, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-8, 24, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM)))
        .build();

    /** Hand-over-hand ladder climb, two rungs per cycle. 1s loop. */
    public static final AnimationDefinition CLIMB_LADDER = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-168, 0, -6), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-168, 0, -6), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-96, 0, -10), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-168, 0, -6), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-168, 0, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-96, 0, 10), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-168, 0, 6), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-168, 0, 6), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-96, 0, 10), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, 0, -6), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-20, 0, -6), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-58, 0, -6), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20, 0, 6), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-58, 0, 6), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-20, 0, 6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 4, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-8, -4, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-8, 4, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-14, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-14, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.posVec(0, 0.6F, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0, 0.6F, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    // ---------------------------------------------------------- farmer ---

    /** Bent over the soil, hoe strokes with a snappy down-pull. 1.5s loop. */
    public static final AnimationDefinition FARM_TILL = AnimationDefinition.Builder
        .withLength(1.5F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(24, -5, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(18, -6, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(31, 2, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(27, 4, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(24, -5, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-96, 12, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-140, 8, -6), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-32, -6, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-46, -12, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-58, -14, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-22, -8, 0), LINEAR),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(18, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 3), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(8, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM)))
        .build();

    /** Deep squat, one hand pressing seed into the ground. 2s loop. */
    public static final AnimationDefinition FARM_PLANT = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -6, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0, -7, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.posVec(0, -6, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-62, 0, -8), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-62, 0, -8), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-34, 0, 10), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-34, 0, 10), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(30, -8, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(38, -10, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(34, -6, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(30, -8, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-40, 14, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-12, 18, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-4, 20, 0), LINEAR),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-26, 16, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-40, 14, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -22, 6), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-62, -26, 6), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-58, -22, 6), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, 6, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(22, 6, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM)))
        .build();

    /** A twist -- reach and pull, then rise and swing to the shoulder bag. 1.8s loop. */
    public static final AnimationDefinition FARM_HARVEST = AnimationDefinition.Builder
        .withLength(1.8F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(28, 16, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(34, 20, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(10, -14, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(14, -6, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(28, 16, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-36, 30, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-6, 34, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-4, 36, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-72, -18, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-58, -10, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-36, 30, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-46, -16, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-50, -18, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-82, -24, 6), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-78, -22, 6), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-46, -16, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(18, 12, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(22, 16, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(6, -10, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(18, 12, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -3, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, -4, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.posVec(0, -3, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, -4), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(6, 0, -7), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(5, 0, 6), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(3, 0, -4), CATMULLROM)))
        .build();

    /** Upright, arm extended, watering-can tipping. 2.4s loop. */
    public static final AnimationDefinition FARM_WATER = AnimationDefinition.Builder
        .withLength(2.4F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, -10, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(16, -14, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(14, -8, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(12, -10, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -18, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-74, -22, -26), LINEAR),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-78, -22, -34), CATMULLROM),
            new Keyframe(2.1F, KeyframeAnimations.degreeVec(-64, -20, -14), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-58, -18, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-22, 10, 8), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-30, 14, 10), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-22, 10, 8), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(20, -8, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(20, -8, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(5, 0, 5), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    // -------------------------------------------------------- lumberer ---

    /** Two-handed axe: wind up, strike at 0.55s, follow through. 1s loop. */
    public static final AnimationDefinition CHOP = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-22, -10, -4), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-152, -8, -6), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-140, -8, -6), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-38, -10, -4), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-26, -10, -4), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-22, -10, -4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, 14, 5), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-148, 12, 7), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-136, 12, 7), LINEAR),
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
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-10, 0, -4), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-10, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 4), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(8, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-12, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, 0.5F, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -0.8F, 0), LINEAR),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Short, fast, sideways axe flicks at knee height. Two strikes per loop. 1.3s loop. */
    public static final AnimationDefinition LIMB_BRANCHES = AnimationDefinition.Builder
        .withLength(1.3F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(30, 4, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(26, 14, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(30, 6, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-74, 24, -10), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-104, 28, -16), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-48, 10, -4), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-80, 26, -12), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-106, 30, -18), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-50, 12, -4), LINEAR),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-74, 24, -10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-44, -4, 4), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-44, -4, 4), LINEAR),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(24, 10, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(24, 10, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-16, 0, -6), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-16, 0, -6), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 6), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(4, 0, 6), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0, -2.6F, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.posVec(0, -2.6F, 0), LINEAR),
            new Keyframe(1.3F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .build();

    /** Log on the shoulder: a horizontal bar, tilted torso, one-shoulder lean.
     *  In the full catalogue this is an arms+torso layer riding over
     *  WALK_LADEN's legs (A2); WALK_LADEN doesn't exist yet, so for A1 this
     *  clip is self-contained -- it supplies its own short-stepping legs on
     *  a fixed 2.4s timer via animate(), not animateWalk(), so the gait
     *  doesn't scale with actual movement speed. A1-only simplification;
     *  A2 should split legs into WALK_LADEN and slim this back down to an
     *  arm/torso-only layer per §16.2. 2.4s loop. */
    public static final AnimationDefinition HAUL_LOG = AnimationDefinition.Builder
        .withLength(2.4F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-142, -6, -20), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-139, -6, -22), LINEAR),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-142, -6, -20), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-28, 4, -12), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-34, 6, -16), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-28, 4, -12), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(9, 0, 8), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(11, 0, 11), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(9, 0, 8), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, -14, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(4, -14, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-10, 0, -4), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0, 0, -4), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(10, 0, -4), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(0, 0, -4), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-10, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(0, 0, 4), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-10, 0, 4), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(0, 0, 4), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, 0, -6), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(8, 0, -9), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(5, 0, -6), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM)))
        .build();

    // ------------------------------------------------------------ guard ---

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
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 2), CATMULLROM),
            new Keyframe(1.65F, KeyframeAnimations.degreeVec(3, 0, -2), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(3, 0, 2), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Layers over WALK's legs/torso/cloak: locked pommel-hand + wide scan.
     *  Only touches right_arm/left_arm/head -- SettlerModel resets those two
     *  arm parts before applying this, so it overrides rather than adds to
     *  WALK's swing. 4s loop (co-prime-ish with WALK's 1s so the scan never
     *  syncs to footfalls). */
    public static final AnimationDefinition GUARD_PATROL = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-34, -6, -12), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-31, -6, -13), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-34, -6, -12), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-40, 16, 10), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-37, 16, 11), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-40, 16, 10), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-4, 34, 0), CATMULLROM),
            new Keyframe(1.9F, KeyframeAnimations.degreeVec(0, 6, 0), CATMULLROM),
            new Keyframe(3.0F, KeyframeAnimations.degreeVec(-4, -34, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .build();

    /** One-shot diagonal slash with hip rotation and lunging legs. 0.5s. */
    public static final AnimationDefinition MELEE = AnimationDefinition.Builder
        .withLength(0.5F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-168, 22, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(24, -26, 0), LINEAR),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(8, -12, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(18, 10, 8), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-20, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 20, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(6, -18, 0), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0, 20, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0, -0.8F, 1.0F), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 14, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(4, -12, 0), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0, 14, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-10, 0, -8), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(12, 0, 10), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, -4), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-22, 0, -6), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-4, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(18, 0, 6), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM)))
        .build();

    /** Bracing behind the shield: head tucked, shield high, sword drawn back.
     *  Looping brace; also usable as a 0.3s impact hit-react one-shot by
     *  playing only its opening portion. 1.6s loop. */
    public static final AnimationDefinition SHIELD_BLOCK = AnimationDefinition.Builder
        .withLength(1.6F).looping()
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-96, 26, 22), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-100, 28, 24), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-96, 26, 22), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-48, -22, -16), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-44, -22, -16), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-48, -22, -16), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 22, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(16, 24, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(14, 22, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(16, 18, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(16, 18, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(18, 0, -8), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(18, 0, -8), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-14, 0, 8), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-14, 0, 8), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, -5), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(6, 0, -5), CATMULLROM)))
        .build();

    private SettlerAnimations() {
    }
}
