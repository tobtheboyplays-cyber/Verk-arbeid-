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
 * Keyframe library for the raider (both {@link com.hearthstead.entity.RaiderEntity.Variant}
 * builds). Owner directive: *"Enemies skal se unike ut og vaere skumle med
 * syke animasjonene"* -- enemies must look unique and be scary, with sick
 * animations; standing bar, *"bare premium er standaren"*. Before this file
 * {@code RaiderModel} had no {@code AnimationDefinition} at all -- a raider
 * was a settler-shaped procedural swing with a different texture. Every clip
 * below is transcribed from {@code docs/ANIMATION_CATALOGUE.md} section 23,
 * the raider's counterpart to the settler's catalogue.
 *
 * <p>Bones: {@code root, torso, head, right_arm, left_arm, right_leg,
 * left_leg} -- seven, not the settler's eight. The raider rig has no
 * {@code cloak} bone ({@code RaiderModel.createBodyLayer()}); {@code hood},
 * {@code helm} and {@code pauldron} are visibility-toggled parts (captain
 * vs. grunt), never animation targets, the same rule the settler's
 * {@code hood}/{@code hat_brim} already follow.
 *
 * <p><b>Build geometry lives in {@code RaiderModel.setupAnim}, not here.</b>
 * The BRUTE's mass-forward, arms-too-long, head-low-between-the-shoulders
 * silhouette is a persistent {@code ModelPart} {@code SCALE} shape, applied
 * once per frame regardless of which clip below is playing -- these clips
 * carry motion and posture (lean, crouch, stance), never scale, so the two
 * layers can never fight each other.
 *
 * <p>Sound sync contracts (see {@code tools/anim_check.py}'s
 * {@code ENTITY_SOUND_CONTRACTS} table, extended in this piece to check this
 * file too):
 * <ul>
 *   <li>BREACH_SLAM: no accent-second keyframe check (the trigger fires the
 *       instant {@code RaiderBreachGoal} actually breaks the block, so the
 *       scar and the sound land together -- exactly {@code MELEE}'s own
 *       precedent, where the damage tick and the clip's own visual impact
 *       keyframe are not literally the same tick either). {@code
 *       RaiderBreachGoal}'s {@code SWING_CONTACT}/{@code SWING_PERIOD}
 *       constants are still cross-checked so they cannot silently drift.</li>
 *   <li>LOOT_SNATCH: same shape, tied to {@code RaiderLootGoal}'s {@code
 *       GRAB_PERIOD} -- the trigger fires the instant a stack actually
 *       leaves the chest.</li>
 *   <li>RAIDER_STRIKE has no contract row, matching {@code MELEE}'s own
 *       precedent: {@code RaiderEntity#doHurtTarget} broadcasts the event
 *       and (if it exists) plays any hit sound synchronously in the same
 *       tick, so there is no delay constant to verify.</li>
 * </ul>
 *
 * <p>WHY these seven are all built by private static {@code buildXxx()}
 * methods rather than plain field initializers, from the very first clip:
 * every {@code public static final AnimationDefinition X = ...} in a class
 * like this compiles into ONE shared method, the class's {@code <clinit>},
 * and {@code SettlerAnimations.java} already hit the JVM's 64KB-per-method
 * ceiling once with only fourteen clips inlined alongside its others -- see
 * that file's own header for the full story. Each {@code buildXxx()} here
 * gets its own independent 64KB budget instead. The local variable inside
 * each method is deliberately named IDENTICALLY to the public field it
 * feeds (legal Java -- the local only shadows the field within that method
 * body): {@code tools/anim_check.py} finds clips with a plain regex over
 * {@code AnimationDefinition <NAME> = AnimationDefinition.Builder ...
 * .build();} in the raw source text, not a real parser, so a bare
 * {@code return AnimationDefinition.Builder...} with no name in front of it
 * would be invisible to every structural and craft check while still
 * printing PASS. Do not rename the local to anything else.
 */
public final class RaiderAnimations {

    private RaiderAnimations() {
    }

    // ---------------------------------------------------------- STALK ---

    public static final AnimationDefinition STALK = buildStalk();

    /**
     * SKIRMISHER's walk: predatory, low, hands held tight near the belt with
     * a knuckle-flex twitch on every step -- nothing like the settler's
     * upright, loose-armed {@code WALK}. 0.80 s loop.
     */
    private static AnimationDefinition buildStalk() {
        AnimationDefinition STALK = AnimationDefinition.Builder
            .withLength(0.80F).looping()
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-38, 0, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(38, 0, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(-38, 0, 0), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(38, 0, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-38, 0, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(38, 0, 0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(14, -6, 10), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(-4, -6, -8), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-16, -6, 10), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-4, -6, -8), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(14, -6, 10), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-14, 6, -10), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(4, 6, 8), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(16, 6, -10), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(4, 6, 8), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(-14, 6, -10), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(26, 4, 3), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(24, -2, -2), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(28, -4, 3), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(24, 2, -2), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(26, 4, 3), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.55F, 0), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-10, -14, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-8, 10, 0), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(-10, -14, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -1.2F, 0), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.posVec(0, -1.2F, 0), CATMULLROM)))
            .build();
        return STALK;
    }

    // ------------------------------------------------------ BRUTE_MARCH ---

    public static final AnimationDefinition BRUTE_MARCH = buildBruteMarch();

    /**
     * BRUTE's walk: slow, ground-eating, shoulder-led -- the walk itself is
     * the threat. Long heavy strides, a big shoulder-twist torso and a
     * pronounced dip on every footfall; nowhere near STALK's tight,
     * close-held read. 1.20 s loop.
     */
    private static AnimationDefinition buildBruteMarch() {
        AnimationDefinition BRUTE_MARCH = AnimationDefinition.Builder
            .withLength(1.20F).looping()
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-40, 0, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-40, 0, 0), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-40, 0, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(30, 4, 8), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(-2, 0, 4), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-34, -4, -8), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(-2, 0, 4), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(30, 4, 8), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-30, -4, -8), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(2, 0, -4), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(34, 4, 8), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(2, 0, -4), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-30, -4, -8), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(32, 12, 4), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(30, -2, -1), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(28, -12, -4), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(30, 2, 1), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(32, 12, 4), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.posVec(0, -1.0F, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.posVec(0, -1.1F, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, -8, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-4, 8, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-6, -8, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -2.0F, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.posVec(0, -2.0F, 0), CATMULLROM)))
            .build();
        return BRUTE_MARCH;
    }

    // ------------------------------------------------------------ SPRINT ---

    public static final AnimationDefinition SPRINT = buildSprint();

    /**
     * SKIRMISHER's charge: feral, all-out, arms trailing back rather than
     * pumping front-to-back -- only plays while actively charging a live
     * target ({@code RaiderModel}), never for ordinary travel. 0.60 s loop.
     */
    private static AnimationDefinition buildSprint() {
        AnimationDefinition SPRINT = AnimationDefinition.Builder
            .withLength(0.60F).looping()
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-42, 0, 0), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(42, 0, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-42, 0, 0), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(42, 0, 0), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(-42, 0, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(42, 0, 0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(46, -8, 16), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(10, -4, 10), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(-40, 0, 4), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(10, -4, 10), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(46, -8, 16), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-44, 8, -14), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(-8, 4, -8), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(42, 0, -4), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-8, 4, -8), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-44, 8, -14), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(34, 14, 5), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(32, -14, -5), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(34, 14, 5), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.posVec(0, -0.9F, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.95F, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-18, -4, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(-16, 4, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-18, -4, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -1.5F, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.posVec(0, -1.5F, 0), CATMULLROM)))
            .build();
        return SPRINT;
    }

    // -------------------------------------------------------- BREACH_SLAM ---

    public static final AnimationDefinition BREACH_SLAM = buildBreachSlam();

    /**
     * BRUTE's door-breaking blow: a huge two-tick wind-up snap (the arm
     * travels 215 degrees door-to-door across the strike), a LINEAR hold,
     * a big controlled overshoot follow-through, then an off-balance
     * stagger before settling -- the "ugly recovery" the brief asks for.
     * Triggered from {@code RaiderBreachGoal} the instant the target
     * actually gives way, so the scar and this clip start the same tick
     * (the clip's own internal impact keyframe lands a few ticks into its
     * own playback, same as {@code MELEE}'s precedent -- see this file's
     * header). One-shot, 1.50 s.
     */
    private static AnimationDefinition buildBreachSlam() {
        AnimationDefinition BREACH_SLAM = AnimationDefinition.Builder
            .withLength(1.50F)
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(15, 0, -6), LINEAR),
                new Keyframe(0.10F, KeyframeAnimations.degreeVec(-20, -4, 4), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(-165, -25, 18), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-160, -24, 17), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(55, 15, -25), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(53, 14, -24), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.degreeVec(44, 20, -30), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(2, -6, 10), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(15, 0, -6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 0, 6), LINEAR),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(-20, -6, 10), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-35, -10, 14), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-33, -9, 13), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.degreeVec(-15, -4, 8), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(10, 0, 6), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(20, 0, 2), LINEAR),
                new Keyframe(0.10F, KeyframeAnimations.degreeVec(10, 8, 3), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-15, -20, -8), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-12, -18, -7), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(48, 22, 10), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(46, 20, 9), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.degreeVec(58, 26, 13), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(14, -6, -3), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(20, 0, 2), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-10, -8, 0), LINEAR),
                new Keyframe(0.10F, KeyframeAnimations.degreeVec(-14, -4, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-20, 10, 0), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-4, -6, 0), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-6, -5, 0), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.degreeVec(-16, 4, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(-8, -10, 0), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(-10, -8, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -2.0F, 0), LINEAR),
                new Keyframe(0.10F, KeyframeAnimations.posVec(0, -1.6F, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.posVec(0, -1.0F, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.posVec(0, -1.1F, 0), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.posVec(0, -3.2F, 0), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.posVec(0, -3.0F, 0), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.posVec(0, -3.6F, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.posVec(0, -1.6F, 0), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.posVec(0, -2.0F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-4, 0, -6), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-14, 0, -12), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(18, 0, 10), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(16, 0, 9), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.degreeVec(22, 0, 14), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(-8, 0, -8), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(-4, 0, -6), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 6), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(14, 0, 12), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-16, 0, -9), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-14, 0, -8), LINEAR),
                new Keyframe(1.05F, KeyframeAnimations.degreeVec(-20, 0, -13), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(8, 0, 8), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(4, 0, 6), CATMULLROM)))
            .build();
        return BREACH_SLAM;
    }

    // ------------------------------------------------------ RAIDER_STRIKE ---

    public static final AnimationDefinition RAIDER_STRIKE = buildRaiderStrike();

    /**
     * The ordinary melee swing, both builds. Deliberately wilder and less
     * disciplined than the guard's {@code MELEE}: bigger, off-axis wind-up,
     * a sloppier overshoot on recovery instead of a clean return -- the
     * contrast between a trained defender and an attacker who does not care
     * if they overbalance is the point. Also plays when a SKIRMISHER
     * breaches a door or wall ({@code RaiderBreachGoal}); the BRUTE's own
     * breach gets {@code BREACH_SLAM} instead. One-shot, 0.55 s.
     */
    private static AnimationDefinition buildRaiderStrike() {
        AnimationDefinition RAIDER_STRIKE = AnimationDefinition.Builder
            .withLength(0.55F)
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-25, 5, -10), CATMULLROM),
                new Keyframe(0.05F, KeyframeAnimations.degreeVec(-70, 15, -25), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(-175, 38, -48), LINEAR),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(42, -42, 32), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(38, -38, 29), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-58, 20, -15), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-25, 5, -10), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-15, -4, 8), CATMULLROM),
                new Keyframe(0.05F, KeyframeAnimations.degreeVec(-32, -10, 18), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(24, 25, -20), LINEAR),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(30, 30, -24), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(26, 26, -21), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-36, -14, 20), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-15, -4, 8), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 24, -4), CATMULLROM),
                new Keyframe(0.05F, KeyframeAnimations.degreeVec(10, -10, 8), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(14, -34, 14), LINEAR),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(8, -30, 10), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(7, -27, 9), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-4, 36, -18), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(2, 24, -4), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.25F, KeyframeAnimations.posVec(0, -0.9F, 1.3F), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.posVec(0, -0.8F, 1.1F), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.posVec(0, 0.3F, -0.3F), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(0, 16, 0), CATMULLROM),
                new Keyframe(0.05F, KeyframeAnimations.degreeVec(3, 4, 0), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(6, -16, 0), LINEAR),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(8, -14, 0), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-2, 22, 0), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(0, 16, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -6), CATMULLROM),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(-28, 0, -10), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(10, 0, 6), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-6, 0, -6), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 6), CATMULLROM),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(24, 0, 10), LINEAR),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-12, 0, -8), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(8, 0, 6), CATMULLROM)))
            .build();
        return RAIDER_STRIKE;
    }

    // -------------------------------------------------------- LOOT_SNATCH ---

    public static final AnimationDefinition LOOT_SNATCH = buildLootSnatch();

    /**
     * Stealing from a chest: a fast reach and grip, then the torso and head
     * whip around into a held look-over-shoulder check before snapping back
     * to work. Triggered from {@code RaiderLootGoal} the instant a stack
     * actually leaves the chest. One-shot, 0.70 s.
     */
    private static AnimationDefinition buildLootSnatch() {
        AnimationDefinition LOOT_SNATCH = AnimationDefinition.Builder
            .withLength(0.70F)
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, -5, 0), CATMULLROM),
                new Keyframe(0.05F, KeyframeAnimations.degreeVec(25, -10, 5), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(-95, 20, -15), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(-100, 22, -16), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-60, 10, -8), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(10, -5, 0), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 4, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(-20, 8, 4), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-14, 5, 2), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-8, 4, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(0.05F, KeyframeAnimations.degreeVec(10, -6, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(18, 10, 4), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(16, 9, 4), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(4, -28, -10), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(4, -30, -11), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(18, 4, 0), LINEAR),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(16, 4, 0), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(2, -48, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(2, -50, 0), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.posVec(0, -0.6F, 0), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-3, 0, -4), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(-9, 0, -6), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(3, 0, 4), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-3, 0, -4), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 4), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(9, 0, 6), LINEAR),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-3, 0, -4), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(3, 0, 4), CATMULLROM)))
            .build();
        return LOOT_SNATCH;
    }

    // ------------------------------------------------------- MENACE_IDLE ---

    public static final AnimationDefinition MENACE_IDLE = buildMenaceIdle();

    /**
     * The stationary read: rolling shoulders (arms drift out of phase with
     * each other, never mirrored) and the head hunting side to side on an
     * irregular clock, with a slow weight shift foot to foot. Every raider
     * plays this while stopped -- pack, brute, captain, and the telegraph
     * scout at the treeline ({@code RaiderModel}, gated on {@code
     * !moving} alone) -- since this is what the player actually watches
     * during the dusk telegraph; it has to carry the dread on its own.
     * 4.20 s loop.
     */
    private static AnimationDefinition buildMenaceIdle() {
        AnimationDefinition MENACE_IDLE = AnimationDefinition.Builder
            .withLength(4.20F).looping()
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, -4, 4), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(10, -2, 10), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(4, -6, 2), CATMULLROM),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(9, -3, 9), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(6, -4, 4), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-5, 3, -3), CATMULLROM),
                new Keyframe(1.55F, KeyframeAnimations.degreeVec(-11, 6, -9), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-4, 2, -2), CATMULLROM),
                new Keyframe(3.70F, KeyframeAnimations.degreeVec(-9, 5, -7), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(-5, 3, -3), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(18, 0, 3), CATMULLROM),
                new Keyframe(1.40F, KeyframeAnimations.degreeVec(16, -6, -2), CATMULLROM),
                new Keyframe(2.80F, KeyframeAnimations.degreeVec(20, 5, 4), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(18, 0, 3), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.posVec(0, -0.7F, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.posVec(0, -0.35F, 0), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, -22, 0), CATMULLROM),
                new Keyframe(0.75F, KeyframeAnimations.degreeVec(-10, -4, 0), CATMULLROM),
                new Keyframe(1.60F, KeyframeAnimations.degreeVec(-6, 18, 0), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(-9, 20, 0), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(-7, -6, 0), CATMULLROM),
                new Keyframe(3.70F, KeyframeAnimations.degreeVec(-9, -20, 0), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(-8, -22, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -1.2F, 0), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.posVec(0, -1.2F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-3, 0, -5), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.degreeVec(-8, 0, -9), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-2, 0, -4), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(-3, 0, -5), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 5), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.degreeVec(1, 0, 2), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(9, 0, 10), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(3, 0, 5), CATMULLROM)))
            .build();
        return MENACE_IDLE;
    }
}
