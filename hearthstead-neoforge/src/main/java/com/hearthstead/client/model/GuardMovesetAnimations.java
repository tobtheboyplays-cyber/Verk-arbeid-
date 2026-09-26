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
 * Fallback Java clips for the guard combat moveset (settler rig). The
 * authored versions are bedrock JSON clips of the same name under
 * assets/hearthstead/animations/settler/ and replace these on the motion
 * engine; these keep every state visible with the engine off and fix each
 * clip's length and contact beat (the JSON must match them).
 *
 * <p>Strikes are ADDITIVE offsets over GUARD_STANCE / GUARD_PATROL that start
 * and end at zero (the MELEE grammar). GUARD_STAGGER is a full-body hold that
 * SettlerModel applies over a clean reset, like GUARD_HIT_REACT.
 */
public final class GuardMovesetAnimations {
    private GuardMovesetAnimations() {
    }

    private static Keyframe r(float t, float x, float y, float z, AnimationChannel.Interpolation i) {
        return new Keyframe(t, KeyframeAnimations.degreeVec(x, y, z), i);
    }

    private static Keyframe p(float t, float x, float y, float z, AnimationChannel.Interpolation i) {
        return new Keyframe(t, KeyframeAnimations.posVec(x, y, z), i);
    }

    /** Backhand return cut; contact 0.20 s (tick 4), 0.50 s. */
    public static final AnimationDefinition GUARD_LIGHT_SLASH_B = AnimationDefinition.Builder
        .withLength(0.5F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.1F, -32, -28, -12, CATMULLROM),
            r(0.15F, -30, -26, -10, LINEAR), r(0.2F, -18, 28, 10, LINEAR),
            r(0.3F, -8, 14, 5, CATMULLROM), r(0.42F, -1, 2, 1, CATMULLROM), r(0.5F, 0, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.1F, -4, -14, 0, CATMULLROM),
            r(0.2F, -10, 16, 2, LINEAR), r(0.3F, -6, 8, 1, CATMULLROM), r(0.5F, 0, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.1F, 2, 12, 0, CATMULLROM),
            r(0.2F, 5, -14, 0, LINEAR), r(0.5F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Two-step overhead; contact 0.55 s (tick 11), 1.10 s. */
    public static final AnimationDefinition GUARD_HEAVY_OVERHEAD = AnimationDefinition.Builder
        .withLength(1.1F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.35F, -130, 6, 10, CATMULLROM),
            r(0.45F, -136, 6, 10, LINEAR), r(0.55F, -20, -4, 0, LINEAR),
            r(0.7F, -16, -4, 0, CATMULLROM), r(0.95F, -4, 0, 0, CATMULLROM), r(1.1F, 0, 0, 0, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.35F, -110, -10, -8, CATMULLROM),
            r(0.45F, -114, -10, -8, LINEAR), r(0.55F, -24, -6, 0, LINEAR),
            r(0.7F, -20, -4, 0, CATMULLROM), r(1.1F, 0, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.35F, -12, 6, 0, CATMULLROM),
            r(0.45F, -13, 6, 0, LINEAR), r(0.55F, 22, -2, 0, LINEAR),
            r(0.7F, 18, -2, 0, CATMULLROM), r(1.1F, 0, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0, CATMULLROM), p(0.55F, 0, -0.8F, -0.8F, LINEAR),
            p(0.7F, 0, -0.7F, -0.7F, CATMULLROM), p(1.1F, 0, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.35F, -8, 0, 0, CATMULLROM),
            r(0.55F, 10, 0, 0, LINEAR), r(1.1F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Shield-arm punch; contact 0.15 s (tick 3), 0.50 s. */
    public static final AnimationDefinition GUARD_SHIELD_BASH = AnimationDefinition.Builder
        .withLength(0.5F)
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.08F, -18, 12, 0, CATMULLROM),
            r(0.15F, -80, -10, 0, LINEAR), r(0.25F, -70, -8, 0, CATMULLROM),
            r(0.4F, -12, 0, 0, CATMULLROM), r(0.5F, 0, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.08F, -3, 10, 0, CATMULLROM),
            r(0.15F, 8, -12, 0, LINEAR), r(0.3F, 5, -6, 0, CATMULLROM), r(0.5F, 0, 0, 0, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0, CATMULLROM), p(0.15F, 0, -0.3F, -1.2F, LINEAR),
            p(0.3F, 0, -0.2F, -0.8F, CATMULLROM), p(0.5F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Full-body recoil off a heavy hit; 0.60 s. */
    public static final AnimationDefinition GUARD_STAGGER = AnimationDefinition.Builder
        .withLength(0.6F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.12F, -16, 8, 4, CATMULLROM),
            r(0.3F, -10, 5, 2, CATMULLROM), r(0.6F, -6, 0, 0, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.12F, -12, -6, 0, CATMULLROM), r(0.6F, 1, 0, 0, CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -28, -10, 5, CATMULLROM), r(0.12F, -44, 10, 22, CATMULLROM),
            r(0.35F, -34, -4, 10, CATMULLROM), r(0.6F, -28, -10, 5, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -20, 14, 4, CATMULLROM), r(0.12F, -34, -8, -22, CATMULLROM),
            r(0.35F, -26, 8, -8, CATMULLROM), r(0.6F, -20, 14, 4, CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.12F, 9, 0, 2, CATMULLROM), r(0.6F, 0, 0, 0, CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.12F, -6, 0, -2, CATMULLROM), r(0.6F, 0, 0, 0, CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            p(0.0F, 0, 0, 0, CATMULLROM), p(0.12F, 0, -0.5F, 1.0F, CATMULLROM), p(0.6F, 0, 0, 0, CATMULLROM)))
        .build();

    // ---- Ceremony (absolute full-body holds: reset every bone first, no strike mask). ----
    // The authored JSON (animations/settler/guard_attention*.json, guard_salute.json)
    // replaces these through the motion engine; the stubs keep the contract and a
    // plain fallback pose when the engine is off.

    /** GUARD_STANCE to attention; heel click at 0.28 s; 0.40 s one-shot. */
    public static final AnimationDefinition GUARD_ATTENTION_SNAP = AnimationDefinition.Builder
        .withLength(0.4F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -28, -10, 5, CATMULLROM), r(0.28F, -24, -8, 2, CATMULLROM), r(0.4F, -24, -8, 2, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, -20, 14, 4, CATMULLROM), r(0.28F, 0, 0, -2, CATMULLROM), r(0.4F, 0, 0, -2, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.28F, -2, 0, 0, CATMULLROM), r(0.4F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Attention hold with breathing: heels together, sword upright at the right shoulder; 2.00 s loop. */
    public static final AnimationDefinition GUARD_ATTENTION = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -24, -8, 2, CATMULLROM), r(1.0F, -25, -8, 2, CATMULLROM), r(2.0F, -24, -8, 2, CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, -2, CATMULLROM), r(1.0F, 0, 0, -3, CATMULLROM), r(2.0F, 0, 0, -2, CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(1.0F, -1, 0, 0, CATMULLROM), r(2.0F, 0, 0, 0, CATMULLROM)))
        .build();

    /** Sword salute from and back to GUARD_ATTENTION; hilt at the face 0.40-0.72 s; 1.20 s one-shot. */
    public static final AnimationDefinition GUARD_SALUTE = AnimationDefinition.Builder
        .withLength(1.2F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            r(0.0F, -24, -8, 2, CATMULLROM), r(0.4F, -95, -32, 0, CATMULLROM), r(0.72F, -95, -32, 0, CATMULLROM),
            r(0.92F, -22, -10, 22, CATMULLROM), r(1.12F, -24, -8, 2, CATMULLROM), r(1.2F, -24, -8, 2, CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            r(0.0F, 0, 0, 0, CATMULLROM), r(0.4F, 3, 0, 0, CATMULLROM), r(0.72F, 3, 0, 0, CATMULLROM),
            r(1.12F, 0, 0, 0, CATMULLROM), r(1.2F, 0, 0, 0, CATMULLROM)))
        .build();
}
