package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.CATMULLROM;
import static net.minecraft.client.animation.AnimationChannel.Targets.POSITION;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * Fallback Java clips for the Guard Drill morning sparring (settler rig). The authored versions
 * are bedrock JSON clips of the same name under assets/hearthstead/animations/settler/
 * (tools/blender/pipeline/clips/combat/guard_drill.py) and replace these on the motion engine;
 * these keep the drill readable with the engine off and fix each clip's length and contact beat
 * (GuardDrillScript.LENGTH_S / CONTACT_S; the JSON must match them).
 *
 * <p>All are ABSOLUTE full-body clips played on reset bones (SettlerModel resets every part
 * first, like the village socials). Every one-shot starts and ends on the relaxed drill stance
 * so the cross-fade (GuardDrillScript.BLEND_S) only has to hide the stance's own breathing.
 */
public final class GuardDrillAnimations {
    private GuardDrillAnimations() {
    }

    private static Keyframe r(float t, float x, float y, float z) {
        return new Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), CATMULLROM);
    }

    private static Keyframe p(float t, float x, float y, float z) {
        return new Keyframe(t, KeyframeAnimations.posVec(x, y, z), CATMULLROM);
    }

    // Relaxed drill stance values the one-shots start and end on.
    private static final float RA_X = -30, RA_Y = -8, RA_Z = 6;
    private static final float LA_X = -12, LA_Y = 10, LA_Z = -6;

    /** Relaxed sparring stance: loose knees, weight rocking, sword low-ready; 3.20 s loop. */
    public static final AnimationDefinition GUARD_DRILL_STANCE = AnimationDefinition.Builder
        .withLength(3.2F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(1.6F, RA_X - 3, RA_Y, RA_Z + 1), r(3.2F, RA_X, RA_Y, RA_Z)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, LA_X, LA_Y, LA_Z), r(1.6F, LA_X - 2, LA_Y, LA_Z - 1), r(3.2F, LA_X, LA_Y, LA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(1.6F, 5, 4, 0), r(3.2F, 4, 6, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.8F, 0.3F, -0.6F, 0), p(1.6F, 0, -0.4F, 0),
            p(2.4F, -0.3F, -0.6F, 0), p(3.2F, 0, -0.4F, 0)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, 8, 0, 3), r(3.2F, 8, 0, 3)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, -10, 0, -3), r(3.2F, -10, 0, -3)))
        .build();

    /** High practice cut, pulled short; contact 0.55 s, 1.20 s. */
    public static final AnimationDefinition GUARD_DRILL_CUT_HIGH = AnimationDefinition.Builder
        .withLength(1.2F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.35F, -150, 10, 12), r(0.55F, -80, -14, 4),
            r(0.75F, -62, -12, 4), r(1.2F, RA_X, RA_Y, RA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.35F, -6, 14, 0), r(0.55F, 14, -10, 0), r(1.2F, 4, 6, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.55F, 0, -1.0F, -3.0F), p(1.2F, 0, -0.4F, 0)))
        .build();

    /** Low/flank practice cut; contact 0.50 s, 1.10 s. */
    public static final AnimationDefinition GUARD_DRILL_CUT_LOW = AnimationDefinition.Builder
        .withLength(1.1F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.3F, -50, 40, 30), r(0.5F, -70, -30, -8),
            r(0.7F, -60, -26, -6), r(1.1F, RA_X, RA_Y, RA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.3F, 2, 22, 0), r(0.5F, 10, -16, 0), r(1.1F, 4, 6, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.5F, 0, -1.2F, -2.5F), p(1.1F, 0, -0.4F, 0)))
        .build();

    /** High parry: blade raised across the head; meets the cut at 0.45 s, 1.00 s. */
    public static final AnimationDefinition GUARD_DRILL_PARRY_HIGH = AnimationDefinition.Builder
        .withLength(1.0F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.35F, -125, -30, -20), r(0.45F, -122, -28, -18),
            r(0.65F, -110, -24, -14), r(1.0F, RA_X, RA_Y, RA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.45F, -4, 2, 0), r(1.0F, 4, 6, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.45F, 0, -0.9F, 1.0F), p(1.0F, 0, -0.4F, 0)))
        .build();

    /** Low parry: blade swept down to the flank; meets the cut at 0.42 s, 1.00 s. */
    public static final AnimationDefinition GUARD_DRILL_PARRY_LOW = AnimationDefinition.Builder
        .withLength(1.0F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.32F, -40, -40, -20), r(0.42F, -42, -42, -22),
            r(0.62F, -38, -30, -14), r(1.0F, RA_X, RA_Y, RA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.42F, 8, -8, 0), r(1.0F, 4, 6, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.42F, 0, -1.3F, 0.8F), p(1.0F, 0, -0.4F, 0)))
        .build();

    /** Evade: a quick step back out of range, not answering the blow; furthest back 0.40 s, 0.90 s. */
    public static final AnimationDefinition GUARD_DRILL_EVADE = AnimationDefinition.Builder
        .withLength(0.9F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.4F, -8, 10, 0), r(0.9F, 4, 6, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.4F, 0, -0.6F, 5.0F), p(0.9F, 0, -0.4F, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.4F, -20, -4, 10), r(0.9F, RA_X, RA_Y, RA_Z)))
        .build();

    /** Breather (short): step back, sword down, wipe the brow, step back in; 2.20 s. */
    public static final AnimationDefinition GUARD_DRILL_BREATHER = AnimationDefinition.Builder
        .withLength(2.2F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.4F, -6, 0, 6), r(1.75F, -8, 0, 6), r(2.2F, RA_X, RA_Y, RA_Z)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, LA_X, LA_Y, LA_Z), r(0.6F, -140, -30, 10), r(0.9F, -140, 10, 10),
            r(1.2F, -20, 6, -6), r(2.2F, LA_X, LA_Y, LA_Z)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.6F, -10, 0, 0), r(0.9F, -6, 8, 0), r(1.3F, 6, 0, 0), r(2.2F, 0, 0, 0)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, -0.4F, 0), p(0.4F, 0, 0, 3.0F), p(1.75F, 0, 0, 3.0F), p(2.2F, 0, -0.4F, 0)))
        .build();

    private static AnimationDefinition step(float len, float dx, float dz) {
        return AnimationDefinition.Builder.withLength(len)
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                r(0.0F, RA_X, RA_Y, RA_Z), r(len / 2, RA_X - 4, RA_Y, RA_Z), r(len, RA_X, RA_Y, RA_Z)))
            .addAnimation("root", new AnimationChannel(POSITION,
                p(0.0F, 0, -0.4F, 0), p(len / 2, dx * 0.15F, -0.9F, dz * 0.15F), p(len, 0, -0.4F, 0)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                r(0.0F, -10, 0, -3), r(len * 0.3F, -18, 0, dx > 0 ? -12 : -3), r(len * 0.6F, -10, 0, -3),
                r(len, -10, 0, -3)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                r(0.0F, 8, 0, 3), r(len * 0.6F, 14, 0, dx < 0 ? 12 : 3), r(len, 8, 0, 3)))
            .build();
    }

    /** Side-step to the guard's left (circling); the body travels 0.5 block; 0.80 s. */
    public static final AnimationDefinition GUARD_DRILL_STEP_LEFT = step(0.8F, 1, 0);
    /** Side-step to the guard's right (circling); 0.80 s. */
    public static final AnimationDefinition GUARD_DRILL_STEP_RIGHT = step(0.8F, -1, 0);
    /** Advance: front foot then rear foot, 6 px forward; 0.70 s. */
    public static final AnimationDefinition GUARD_DRILL_ADVANCE = step(0.7F, 0, -1);
    /** Retreat: rear foot then front foot, 6 px back; 0.70 s. */
    public static final AnimationDefinition GUARD_DRILL_RETREAT = step(0.7F, 0, 1);

    /** Feint: starts a high cut, checks it at 0.32 s and snaps back to guard; 0.80 s. */
    public static final AnimationDefinition GUARD_DRILL_FEINT = AnimationDefinition.Builder
        .withLength(0.8F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.22F, -120, 6, 10), r(0.32F, -100, -6, 6), r(0.8F, RA_X, RA_Y, RA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.22F, -2, 14, 0), r(0.32F, 8, 0, 0), r(0.8F, 4, 6, 0)))
        .build();

    /** Stumble: knocked off balance, a step back, arms out, recovers; 1.40 s. */
    public static final AnimationDefinition GUARD_DRILL_STUMBLE = AnimationDefinition.Builder
        .withLength(1.4F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.35F, -16, -8, 6), r(0.8F, -6, 0, -3), r(1.4F, 4, 6, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.35F, -60, 20, 40), r(0.9F, -20, 0, 10), r(1.4F, RA_X, RA_Y, RA_Z)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, LA_X, LA_Y, LA_Z), r(0.35F, -50, -10, -40), r(0.9F, -10, 0, -8), r(1.4F, LA_X, LA_Y, LA_Z)))
        .build();

    /** Nod: sword point down, a nod and a chuckle, guard back up; 1.30 s. */
    public static final AnimationDefinition GUARD_DRILL_NOD = AnimationDefinition.Builder
        .withLength(1.3F)
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0), r(0.35F, 14, 0, 0), r(0.55F, 2, 0, 0), r(0.75F, 10, 0, 0), r(1.3F, 0, 0, 0)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, RA_X, RA_Y, RA_Z), r(0.3F, -8, 0, 6), r(0.95F, -8, 0, 6), r(1.3F, RA_X, RA_Y, RA_Z)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 4, 6, 0), r(0.45F, 6, 0, 0), r(0.6F, 2, 0, 0), r(0.75F, 6, 0, 0), r(1.3F, 4, 6, 0)))
        .build();
}
