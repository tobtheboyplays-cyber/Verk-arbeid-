package com.hearthstead.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR;
import static net.minecraft.client.animation.AnimationChannel.Targets.ROTATION;

/**
 * Clip contract for the goblin thief (rig "goblin"): name, length and loop of
 * each stage clip. GoblinThiefModel stays fully procedural unless the motion
 * engine is on AND the authored JSON (animations/goblin/*.json,
 * {@code animation.goblin.<name>}) exists, in which case that clip plays on
 * the server-synced stage clock. These definitions are only the contract.
 */
public final class GoblinAnimations {
    private GoblinAnimations() {
    }

    private static AnimationDefinition stub(float length, boolean loop) {
        AnimationDefinition.Builder builder = AnimationDefinition.Builder.withLength(length);
        if (loop) {
            builder.looping();
        }
        return builder.addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), LINEAR))).build();
    }

    /** Stage 0, stationary lurk. */
    public static final AnimationDefinition GOBLIN_LURK = stub(3.0F, true);
    /** Stage 0 crouched walk (distance clocked; blocks_per_cycle in meta). */
    public static final AnimationDefinition GOBLIN_SNEAK = stub(1.0F, true);
    /** Stage 1, 60-tick theft window from the synced stage start. */
    public static final AnimationDefinition GOBLIN_STEAL = stub(3.0F, false);
    /** Stage 2 run, hand on the pouch (distance clocked). */
    public static final AnimationDefinition GOBLIN_FLEE = stub(0.5F, true);
    /** Stage 3, 40-tick lock pick from the synced stage start. */
    public static final AnimationDefinition GOBLIN_PICK_LOCK = stub(2.0F, false);
    /** Defensive poke: six-tick swing, contact at 0.15 s. */
    public static final AnimationDefinition GOBLIN_POKE = stub(0.3F, false);
}
