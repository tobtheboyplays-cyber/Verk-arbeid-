package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * Fallback Java clips for the tavern lane (settler rig). The Blender-authored
 * bedrock JSON of the same name (animations/settler/&lt;name&gt;.animation.json,
 * source tools/blender/pipeline/clips/tavern/) replaces each of them on the
 * motion engine; these only fix the length, loop flag and contact beats
 * (AuthoredClipAssetsTest) and give the vanilla path a readable pose.
 *
 * <p>Seated clips (SEATED_DRINK, TABLE_CHEER, SLEEPY_NOD) are upper body only:
 * the TavernSeatEntity owns root and the seated legs.
 */
public final class TavernMotionAnimations {
    private TavernMotionAnimations() {
    }

    private static Keyframe r(float t, float x, float y, float z) {
        return new Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), CATMULLROM);
    }

    private static Keyframe p(float t, float x, float y, float z) {
        return new Keyframe(t, KeyframeAnimations.posVec(x, y, z), CATMULLROM);
    }

    /** Seated patron alone with an ale: lift at 0.86 s, gulps, set down 2.68 s, a look round. 8.0 s loop. */
    public static final AnimationDefinition SEATED_DRINK = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(1.3F, 2, -1, 0), r(2.3F, -1, -2, 0), r(2.7F, 6, -3, 0),
            r(5.0F, 8, 8, 0), r(6.5F, 6, 6, 0), r(8.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(1.4F, -12, 0, 0), r(2.2F, -8, -2, 0), r(2.7F, 7, 0, 0),
            r(5.0F, 2, 18, 0), r(6.5F, 5, 14, 0), r(8.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(0.86F, -60, 8, 6), r(1.3F, -95, 20, 10), r(2.2F, -95, 20, 10),
            r(2.68F, -62, 8, 6), r(8.0F, -62, 8, 6)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(5.0F, -70, -18, -10), r(6.2F, -62, -10, -6), r(8.0F, -62, -10, -6)))
        .build();

    /** Table clock, the teller: drinks, then tells the story; punchline at 6.0 s. 8.0 s loop. */
    public static final AnimationDefinition SEATED_STORY = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(1.3F, 2, -1, 0), r(2.7F, 6, -3, 0), r(5.2F, 11, 2, 3), r(6.0F, 2, 0, -1),
            r(8.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(1.4F, -12, 0, 0), r(2.7F, 7, 0, 0), r(4.45F, 6, -8, 0), r(5.05F, 5, 8, 0),
            r(6.0F, -12, 0, 0), r(8.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(1.3F, -95, 20, 10), r(2.2F, -95, 20, 10), r(2.68F, -62, 8, 6), r(8.0F, -62, 8, 6)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(4.45F, -80, -20, -14), r(5.05F, -70, -16, -10), r(5.95F, -110, -25, -20),
            r(7.1F, -62, -10, -6), r(8.0F, -62, -10, -6)))
        .build();

    /** Table clock, the others: nods on 4.45 / 5.05, laughs and slaps the table on the punchline. 8.0 s loop. */
    public static final AnimationDefinition SEATED_LISTEN = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(3.0F, 11, 0, 0), r(6.0F, 12, 0, 0), r(6.35F, -6, 4, -3), r(6.38F, 12, 4, 0),
            r(6.78F, 13, 0, 2), r(7.5F, 6, -2, 0), r(8.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(1.7F, -12, 2, 0), r(3.0F, 4, 0, 4), r(4.45F, 10, 0, 4), r(4.7F, 3, 0, 4),
            r(5.05F, 9, 0, 4), r(6.3F, -22, 0, -6), r(6.8F, 10, 0, 4), r(8.0F, 4, 6, 0)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(5.9F, -62, -10, -6), r(6.2F, -130, -10, -10), r(6.38F, -60, -10, -6),
            r(6.6F, -120, -10, -10), r(6.78F, -60, -10, -6), r(8.0F, -62, -10, -6)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(1.4F, -95, 20, 10), r(2.0F, -95, 20, 10), r(2.5F, -62, 8, 6), r(8.0F, -62, 8, 6)))
        .build();

    /** Table clock: "to the house!" - mug hoisted at 1.1 s, a long draught, thumped down 4.05 s. 8.0 s loop. */
    public static final AnimationDefinition SEATED_TOAST = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(1.1F, -4, -9, 3), r(2.4F, 0, -2, 0), r(4.05F, 9, -3, 0), r(8.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(1.1F, -16, -10, -4), r(3.3F, -20, 0, 0), r(4.05F, 8, 2, 0), r(8.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(1.1F, -160, 10, 14), r(1.8F, -165, 10, 14), r(2.45F, -95, 20, 10),
            r(3.6F, -95, 20, 10), r(4.05F, -62, 8, 6), r(8.0F, -62, 8, 6)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(1.1F, -150, -10, -20), r(2.3F, -62, -10, -6), r(8.0F, -62, -10, -6)))
        .build();

    /** Every seated patron of one table: mugs meet over the table at t=1.0 s. One-shot 3.0 s. */
    public static final AnimationDefinition TABLE_CHEER = AnimationDefinition.Builder
        .withLength(3.0F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(0.5F, 2, -8, 0), r(1.0F, 16, -2, 0), r(2.0F, -2, -3, 0), r(3.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.5F, -12, -4, 0), r(1.0F, -2, 0, 0), r(1.9F, -16, -2, 0), r(3.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(0.5F, -150, 10, 12), r(1.0F, -120, -10, 0), r(1.9F, -95, 20, 10),
            r(2.55F, -62, 8, 6), r(3.0F, -62, 8, 6)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(0.5F, -160, -10, -20), r(1.6F, -62, -10, -6), r(3.0F, -62, -10, -6)))
        .build();

    /** TABLE_CHEER style: after the clink a short swig, then the back of the free hand wipes the mouth. */
    public static final AnimationDefinition TABLE_CHEER_WIPE = AnimationDefinition.Builder
        .withLength(3.0F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(1.0F, 16, -2, 0), r(1.8F, 0, -3, 0), r(2.4F, 4, 4, 0), r(3.0F, 7, -3, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(0.5F, -150, 10, 12), r(1.0F, -120, -10, 0), r(1.6F, -95, 20, 10),
            r(2.1F, -62, 8, 6), r(3.0F, -62, 8, 6)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(2.2F, -62, -10, -6), r(2.45F, -115, -30, -20), r(2.8F, -62, -10, -6),
            r(3.0F, -62, -10, -6)))
        .build();

    /** TABLE_CHEER style: a quick sip after the clink, the mug set down early, a lean back. */
    public static final AnimationDefinition TABLE_CHEER_QUICK = AnimationDefinition.Builder
        .withLength(3.0F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(1.0F, 16, -2, 0), r(1.6F, 2, -3, 0), r(2.2F, -4, -2, 0), r(3.0F, 7, -3, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(0.5F, -150, 10, 12), r(1.0F, -120, -10, 0), r(1.35F, -95, 20, 10),
            r(1.75F, -62, 8, 6), r(3.0F, -62, 8, 6)))
        .build();

    /** Listener style: a small smile and a head shake on the punchline, no slap. 8.0 s loop. */
    public static final AnimationDefinition SEATED_LISTEN_SMILE = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(3.0F, 9, 0, 0), r(6.2F, 5, 0, 0), r(8.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(4.45F, 8, 0, 3), r(6.1F, 2, -8, 0), r(6.4F, 2, 8, 0), r(6.7F, 2, -6, 0),
            r(7.2F, 4, 0, 0), r(8.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(8.0F, -62, 8, 6)))
        .build();

    /** Listener style: looks away, sips on the story, a chuckle with shaking shoulders. 8.0 s loop. */
    public static final AnimationDefinition SEATED_LISTEN_CHUCKLE = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(6.0F, 7, 0, 0), r(6.3F, 2, 2, 2), r(6.6F, 6, 0, -2), r(7.2F, 4, 0, 0),
            r(8.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(3.2F, 2, 24, 0), r(4.5F, -8, 2, 0), r(6.2F, -6, 0, 4), r(8.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(4.3F, -95, 20, 10), r(5.0F, -95, 20, 10), r(5.5F, -62, 8, 6), r(8.0F, -62, 8, 6)))
        .build();

    /** SEATED_DRINK mirrored for a left-handed drinker (the mug in the offhand). 8.0 s loop. */
    public static final AnimationDefinition SEATED_DRINK_LEFT = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, 3, 0), r(1.3F, 2, 1, 0), r(2.3F, -1, 2, 0), r(2.7F, 6, 3, 0), r(5.0F, 8, -8, 0),
            r(8.0F, 7, 3, 0)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -8, -6), r(0.86F, -60, -8, -6), r(1.3F, -95, -20, -10), r(2.2F, -95, -20, -10),
            r(2.68F, -62, -8, -6), r(8.0F, -62, -8, -6)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 10, 6), r(8.0F, -62, 10, 6)))
        .build();

    /** Calm seated idle: breathing, a posture settle, a slow look round the room. 12.0 s loop. */
    public static final AnimationDefinition SEATED_IDLE = AnimationDefinition.Builder
        .withLength(12.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(4.0F, 6, -2, 1), r(6.0F, 5, -2, 1), r(9.0F, 6, -4, -1), r(12.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(3.0F, 3, 18, 1), r(5.5F, 5, 16, 1), r(7.5F, 2, -14, -1), r(10.0F, 4, -10, 0),
            r(12.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(12.0F, -62, 8, 6)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(12.0F, -62, -10, -6)))
        .build();

    /** One sip from the ale on the table, back to rest. One-shot 3.0 s. */
    public static final AnimationDefinition SEATED_SIP = AnimationDefinition.Builder
        .withLength(3.0F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(0.8F, 2, -1, 0), r(1.6F, 0, -2, 0), r(2.3F, 6, -3, 0), r(3.0F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.8F, -10, 0, 0), r(1.5F, -12, 0, 0), r(2.3F, 5, 2, 0), r(3.0F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(0.7F, -95, 20, 10), r(1.6F, -95, 20, 10), r(2.3F, -62, 8, 6), r(3.0F, -62, 8, 6)))
        .build();

    /** SEATED_SIP for a left-handed drinker (mug in the offhand). One-shot 3.0 s. */
    public static final AnimationDefinition SEATED_SIP_LEFT = AnimationDefinition.Builder
        .withLength(3.0F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, 3, 0), r(0.8F, 2, 1, 0), r(1.6F, 0, 2, 0), r(2.3F, 6, 3, 0), r(3.0F, 7, 3, 0)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -8, -6), r(0.7F, -95, -20, -10), r(1.6F, -95, -20, -10), r(2.3F, -62, -8, -6),
            r(3.0F, -62, -8, -6)))
        .build();

    /** Fidget: the free hand scratches the chin, a thoughtful head tilt. One-shot 3.0 s. */
    public static final AnimationDefinition SEATED_FIDGET_CHIN = AnimationDefinition.Builder
        .withLength(3.0F)
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, -10, -6), r(0.6F, -115, -25, -15), r(2.2F, -115, -25, -15), r(3.0F, -62, -10, -6)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.8F, -2, 4, 6), r(2.2F, -3, 2, 5), r(3.0F, 4, 6, 0)))
        .build();

    /** Fidget: leans back, rolls the shoulders and neck. One-shot 3.4 s. */
    public static final AnimationDefinition SEATED_FIDGET_STRETCH = AnimationDefinition.Builder
        .withLength(3.4F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(1.0F, -6, -3, 0), r(2.0F, -8, 2, 0), r(3.4F, 7, -3, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(1.0F, -10, 0, 8), r(1.8F, -6, 0, -8), r(3.4F, 4, 6, 0)))
        .build();

    /** Fidget: settles in the seat, re-grips the mug. One-shot 2.4 s. */
    public static final AnimationDefinition SEATED_FIDGET_SHIFT = AnimationDefinition.Builder
        .withLength(2.4F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 7, -3, 0), r(0.7F, 3, 4, 2), r(1.4F, 8, -2, -1), r(2.4F, 7, -3, 0)))
        .build();

    /** Innkeeper between bouts: leaning on the bar, watching the room. 8.0 s loop. */
    public static final AnimationDefinition COUNTER_LEAN = AnimationDefinition.Builder
        .withLength(8.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 14, 2, 0), r(4.0F, 13, -4, 1), r(8.0F, 14, 2, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, -6, 12, 0), r(3.0F, -8, 22, 0), r(5.5F, -6, -14, 0), r(8.0F, -6, 12, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -58, 12, 10), r(8.0F, -58, 12, 10)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -58, -12, -10), r(8.0F, -58, -12, -10)))
        .build();

    /** Closing time: the head sinks off the fist, jerks awake at 2.95 s. 6.0 s loop. */
    public static final AnimationDefinition SLEEPY_NOD = AnimationDefinition.Builder
        .withLength(6.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 12, -2, 3), r(2.95F, 21, -1, 7), r(3.1F, 4, 2, -1), r(4.6F, 8, 3, 1), r(6.0F, 12, -2, 3)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 10, 3, 8), r(2.95F, 34, 4, 14), r(3.1F, -10, 0, -2), r(3.8F, -4, -22, 1),
            r(4.4F, 2, 20, 1), r(6.0F, 10, 3, 8)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -110, -15, -10), r(2.8F, -110, -15, -10), r(3.3F, -62, -10, -6),
            r(4.6F, -62, -10, -6), r(6.0F, -110, -15, -10)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -62, 8, 6), r(6.0F, -62, 8, 6)))
        .build();

    /** Innkeeper tending the ale tap: draws a mug, tops it, holds it to the light. 4.0 s loop. */
    public static final AnimationDefinition ALE_POUR = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 6, 0, 0), r(0.6F, 14, -6, 0), r(2.4F, 14, -6, 0), r(3.2F, 0, 4, 0), r(4.0F, 6, 0, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 8, 0, 0), r(0.6F, 22, -8, 0), r(2.4F, 20, -8, 0), r(3.2F, -10, 6, 0), r(4.0F, 8, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -30, 0, 4), r(0.6F, -95, -10, 6), r(2.2F, -95, -10, 6), r(2.6F, -30, 0, 4), r(4.0F, -30, 0, 4)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -60, 10, -4), r(0.6F, -70, 20, -6), r(2.4F, -70, 20, -6), r(3.2F, -130, 0, -10),
            r(4.0F, -60, 10, -4)))
        .build();

    /** Innkeeper wiping the bar top in two circles, then a long stroke. 4.0 s loop. */
    public static final AnimationDefinition COUNTER_WIPE = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 16, 4, 0), r(1.0F, 18, -4, 2), r(2.0F, 16, 4, 0), r(3.0F, 20, -10, 0), r(4.0F, 16, 4, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -70, 10, 10), r(0.5F, -76, -10, 2), r(1.0F, -70, 10, 10), r(1.5F, -76, -10, 2),
            r(2.0F, -70, 10, 10), r(3.0F, -80, -30, 0), r(4.0F, -70, 10, 10)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -60, -8, -6), r(4.0F, -60, -8, -6)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 20, 0, 0), r(2.0F, 22, 6, 0), r(3.0F, 14, -12, 0), r(4.0F, 20, 0, 0)))
        .build();

    /** Host carrying real service cargo: an upright, careful carriage over the walk. 2.0 s loop. */
    public static final AnimationDefinition SERVE_CARRY = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, -3, 0, 1), r(0.5F, -3, 2, 0), r(1.0F, -3, 0, -1), r(1.5F, -3, -2, 0), r(2.0F, -3, 0, 1)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 6, 0, -1), r(1.0F, 6, 0, 1), r(2.0F, 6, 0, -1)))
        .build();

    /** Bard-night jig: hop-steps on the 0.25 s beat, arms up. 2.0 s loop. */
    public static final AnimationDefinition DANCE_JIG = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0), p(0.25F, 0, 1.2F, 0), p(0.5F, 0, 0, 0), p(0.75F, 0, 1.2F, 0), p(1.0F, 0, 0, 0),
            p(1.25F, 0, 1.2F, 0), p(1.5F, 0, 0, 0), p(1.75F, 0, 1.2F, 0), p(2.0F, 0, 0, 0)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.25F, -40, 0, 0), r(0.5F, 0, 0, 0), r(1.0F, 10, 0, 0), r(1.25F, -20, 0, 10),
            r(1.5F, 0, 0, 0), r(2.0F, 0, 0, 0)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.5F, 10, 0, 0), r(0.75F, -40, 0, 0), r(1.0F, 0, 0, 0), r(1.75F, -20, 0, -10),
            r(2.0F, 0, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -150, 0, 20), r(0.5F, -140, 0, 30), r(1.0F, -150, 0, 20), r(1.5F, -140, 0, 30),
            r(2.0F, -150, 0, 20)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -20, 0, -30), r(1.0F, -30, 0, -40), r(2.0F, -20, 0, -30)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 10, 4), r(1.0F, 0, -10, -4), r(2.0F, 0, 10, 4)))
        .build();

    /** Walking home after an ale: a weaving, stumbling gait (WALK slot, two gait cycles). 2.0 s loop. */
    public static final AnimationDefinition TIPSY_WALK = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 5, 6, -5), r(0.5F, 7, -5, 6), r(1.0F, 11, 9, -9), r(1.5F, 6, -8, 8), r(2.0F, 5, 6, -5)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 2, -6, 7), r(0.6F, -4, 6, -8), r(1.1F, 8, -10, 11), r(1.6F, -2, 8, -10), r(2.0F, 2, -6, 7)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, -0.8F, -1, 0), p(0.5F, 0.9F, -1, 0), p(1.0F, -1.6F, -1.1F, 0), p(1.5F, 1.8F, -1.2F, 0),
            p(2.0F, -0.8F, -1, 0)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, -25, 0, 0), r(0.5F, 25, 0, 0), r(1.0F, -22, 0, 0), r(1.5F, 25, 0, 0), r(2.0F, -25, 0, 0)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 25, 0, 0), r(0.5F, -25, 0, 0), r(1.0F, 25, 0, 0), r(1.5F, -28, 0, 0), r(2.0F, 25, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 22, 0, 12), r(0.5F, -18, 0, 8), r(1.1F, -40, 0, 42), r(1.5F, -20, 0, 12), r(2.0F, 22, 0, 12)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -20, 0, -10), r(0.5F, 22, 0, -14), r(1.0F, -12, 0, -12), r(1.5F, 24, 0, -16), r(2.0F, -20, 0, -10)))
        .build();

    /** Drunk (2 ales): unsteady weave, irregular steps (WALK slot, two gait cycles). 2.4 s loop. */
    public static final AnimationDefinition DRUNK_WALK = AnimationDefinition.Builder
        .withLength(2.4F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 6, 8, -7), r(0.6F, 8, -6, 8), r(1.2F, 12, 10, -10), r(1.8F, 7, -9, 9), r(2.4F, 6, 8, -7)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 4, -8, 9), r(0.7F, -2, 8, -10), r(1.3F, 10, -12, 13), r(1.9F, 0, 10, -12), r(2.4F, 4, -8, 9)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, -25, 0, 0), r(0.6F, 25, 0, 0), r(1.2F, -22, 0, 0), r(1.8F, 25, 0, 0), r(2.4F, -25, 0, 0)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 25, 0, 0), r(0.6F, -25, 0, 0), r(1.2F, 25, 0, 0), r(1.8F, -28, 0, 0), r(2.4F, 25, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 22, 0, 16), r(0.6F, -18, 0, 12), r(1.2F, 14, 0, 30), r(1.8F, -20, 0, 14), r(2.4F, 22, 0, 16)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -20, 0, -14), r(0.6F, 22, 0, -18), r(1.2F, -12, 0, -16), r(1.8F, 24, 0, -20), r(2.4F, -20, 0, -14)))
        .build();

    /** Very drunk (3+ ales): big lurching weave, the head lolling. 2.8 s loop (WALK slot). */
    public static final AnimationDefinition VERY_DRUNK_WALK = AnimationDefinition.Builder
        .withLength(2.8F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 10, 12, -12), r(0.7F, 14, -10, 13), r(1.4F, 18, 14, -15), r(2.1F, 12, -12, 14), r(2.8F, 10, 12, -12)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 12, -12, 14), r(0.8F, 4, 12, -16), r(1.5F, 18, -16, 18), r(2.2F, 6, 14, -14), r(2.8F, 12, -12, 14)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, -22, 0, 3), r(0.7F, 22, 0, -3), r(1.4F, -18, 0, 5), r(2.1F, 24, 0, -2), r(2.8F, -22, 0, 3)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 22, 0, -3), r(0.7F, -22, 0, 3), r(1.4F, 22, 0, -5), r(2.1F, -24, 0, 2), r(2.8F, 22, 0, -3)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 18, 0, 28), r(1.4F, -10, 0, 40), r(2.8F, 18, 0, 28)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -12, 0, -26), r(1.4F, 16, 0, -36), r(2.8F, -12, 0, -26)))
        .build();

    /** A drunk stumble layered over the walk: a foot catches, arms flail loose, recover. One-shot 1.6 s. */
    public static final AnimationDefinition STUMBLE = AnimationDefinition.Builder
        .withLength(1.6F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.25F, 24, 6, -8), r(0.6F, 14, -8, 10), r(1.0F, -6, 4, -4), r(1.6F, 0, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.3F, -70, 0, 60), r(0.7F, -120, 0, 30), r(1.1F, -30, 0, 20), r(1.6F, 0, 0, 0)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.35F, -40, 0, -70), r(0.75F, -100, 0, -40), r(1.15F, -20, 0, -20), r(1.6F, 0, 0, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.3F, -18, 6, 0), r(0.7F, 10, -6, 8), r(1.6F, 0, 0, 0)))
        .build();

    /** Very drunk fall, forward flop: collapse, lie, groan, clumsy get-up. One-shot 5.0 s. */
    public static final AnimationDefinition FALL_FORWARD = AnimationDefinition.Builder
        .withLength(5.0F)
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0), p(0.8F, 0, -18, -6), p(0.95F, 0, -20.5F, -7), p(1.1F, 0, -19.5F, -7),
            p(2.6F, 0, -20, -7), p(3.8F, 0, -8, -3), p(5.0F, 0, 0, 0)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.8F, 80, 0, 6), r(0.95F, 88, 0, 4), r(2.6F, 86, 0, 4), r(3.8F, 40, 0, 0), r(5.0F, 0, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.8F, -150, 0, 20), r(2.6F, -160, 0, 10), r(3.8F, -60, 0, 10), r(5.0F, 0, 0, 0)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.8F, -140, 0, -30), r(2.6F, -160, 0, -10), r(3.8F, -60, 0, -10), r(5.0F, 0, 0, 0)))
        .build();

    /** Very drunk fall, sideways spin: knees buckle, twist, land on a hip, get up. One-shot 5.0 s. */
    public static final AnimationDefinition FALL_SIDE = AnimationDefinition.Builder
        .withLength(5.0F)
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0), p(0.9F, 6, -17, 0), p(1.05F, 6.5F, -18.5F, 0), p(2.7F, 6.5F, -18, 0), p(3.9F, 2, -7, 0),
            p(5.0F, 0, 0, 0)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.9F, 0, 40, -80), r(1.05F, 0, 45, -86), r(2.7F, 0, 45, -84), r(3.9F, 0, 20, -30),
            r(5.0F, 0, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.9F, -60, 0, 90), r(2.7F, -40, 0, 100), r(3.9F, -60, 0, 20), r(5.0F, 0, 0, 0)))
        .build();

    /** Very drunk stop: leaning a hand on a wall, swaying. 4.0 s loop. */
    public static final AnimationDefinition DRUNK_LEAN = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 10, -10, -12), r(2.0F, 14, -6, -16), r(4.0F, 10, -10, -12)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -90, 0, 70), r(2.0F, -95, 0, 72), r(4.0F, -90, 0, 70)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 20, 6, -10), r(2.0F, 26, -4, -6), r(4.0F, 20, 6, -10)))
        .build();

    /** Very drunk stop: plops down on the floor, sits a moment, gets up. One-shot 6.0 s. */
    public static final AnimationDefinition DRUNK_SIT = AnimationDefinition.Builder
        .withLength(6.0F)
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0), p(0.9F, 0, -11, 0), p(1.05F, 0, -12, 0), p(4.5F, 0, -12, 0), p(5.4F, 0, -4, 0), p(6.0F, 0, 0, 0)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.9F, -85, 0, 8), r(4.5F, -85, 0, 8), r(5.4F, -40, 0, 0), r(6.0F, 0, 0, 0)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.9F, -85, 0, -8), r(4.5F, -85, 0, -8), r(5.4F, -40, 0, 0), r(6.0F, 0, 0, 0)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.9F, -10, 0, 0), r(3.0F, 6, 0, 4), r(4.5F, 10, 0, 0), r(6.0F, 0, 0, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(1.2F, 20, 0, 0), r(2.4F, 30, 0, 8), r(3.4F, 10, 0, 0), r(6.0F, 0, 0, 0)))
        .build();

    /** Tavern brawl: a wound-up haymaker, contact at 0.45 s. One-shot 1.2 s. */
    public static final AnimationDefinition BRAWL_PUNCH = AnimationDefinition.Builder
        .withLength(1.2F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.3F, 4, 30, 0), r(0.45F, 12, -30, 0), r(0.7F, 6, -20, 0), r(1.2F, 0, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.3F, -40, 40, 20), r(0.45F, -95, -10, 0), r(0.7F, -80, -10, 0), r(1.2F, 0, 0, 0)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.3F, -70, -20, -10), r(0.45F, -40, 0, 0), r(1.2F, 0, 0, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.3F, 6, -10, 0), r(0.45F, 4, 12, 0), r(1.2F, 0, 0, 0)))
        .build();

    /** Waving the crows off the crop: both arms flap overhead, hop forward. 2.4 s loop. */
    public static final AnimationDefinition SHOO_BIRDS = AnimationDefinition.Builder
        .withLength(2.4F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -150, 0, 30), r(0.3F, -170, 0, 10), r(0.6F, -150, 0, 30), r(0.9F, -170, 0, 10),
            r(1.2F, -150, 0, 30), r(1.8F, -40, 0, 10), r(2.4F, -150, 0, 30)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -150, 0, -30), r(0.3F, -170, 0, -10), r(0.6F, -150, 0, -30), r(0.9F, -170, 0, -10),
            r(1.2F, -150, 0, -30), r(1.8F, -40, 0, -10), r(2.4F, -150, 0, -30)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, -6, 0, 0), r(0.6F, -2, 0, 0), r(1.2F, -6, 0, 0), r(1.8F, 8, 0, 0), r(2.4F, -6, 0, 0)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, -14, 0, 0), r(1.2F, -10, 0, 0), r(1.8F, 2, 0, 0), r(2.4F, -14, 0, 0)))
        .build();
}
