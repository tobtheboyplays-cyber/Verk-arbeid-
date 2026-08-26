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
 * Sound sync contracts (accent frame -> tick; every value below must agree
 * with tools/anim_check.py's SOUND_CONTRACTS / ENTITY_SOUND_CONTRACTS tables
 * and, for the goal-driven ones, the AI goal's own tick-modulo comment):
 *  WALK: footfalls t=0.25s/0.75s (vanilla step sound, no custom contract)
 *  FARM_TILL: t=0.60s -> tick 12 of 30 (FarmerWorkGoal, WORK_DURATION-tied)
 *  FARM_PLANT: t=0.70s -> tick 14 of 40
 *  FARM_HARVEST: t=0.45s -> tick 9 of 36 (grab); t=0.90s -> tick 18 (stow)
 *  FARM_WATER: t=0.80s -> tick 16 of 48
 *  CHOP: t=0.55s -> tick 11 of 20 (LumbererWorkGoal, live, unchanged)
 *  LIMB_BRANCHES: t=0.30s -> tick 6 of 26; t=0.95s -> tick 19 of 26
 *  HAUL_LOG: t=1.20s -> tick 24 of 48
 *  MELEE: t=0.20s -> tick 4 of 10 (damage tick, SettlerEntity.doHurtTarget)
 *  CLIMB_LADDER: ladder_creak, twice per 20 ticks (SettlerEntity.tickAccents,
 *    gated on real vertical movement so it is silent while merely standing
 *    on a ladder). NOT phase-locked to the clip: the clip is sampled from
 *    climbState's accumulated time, whose phase depends on when the state
 *    started, while the accent runs off the world tick. This is a
 *    FREQUENCY accent -- the right rate, deliberately not a synced frame.
 *  WALK_LIMP: settler_hm pitched 0.8x, once per three 28-tick cycles (an
 *    84-tick super-cycle) -- SettlerEntity.tickAccents. Also FREQUENCY
 *    only, and necessarily so: WALK_LIMP is driven by animateWalk() from
 *    limbSwing, i.e. distance travelled, so the clip has no time-based
 *    phase for a sound to lock to at all.
 *  RUN_PANIC: t=0.15s -> tick 3 of the 12-tick cycle, first cycle only,
 *    then throttled to one vocal per 2s -- SettlerPanicGoal.tick(),
 *    settler_panic
 *  SHIELD_BLOCK: t=0.10s (2 ticks after the block event is registered) --
 *    SettlerEntity.hurt(), shield_thud
 *  EAT: t=0.25s/0.70s -> ticks 5/14 of 24 -- EatFromHearthGoal.tick(),
 *    settler_eat
 *  CELEBRATE: t=0.45s/1.10s -> ticks 9/22 after the one-shot's own trigger
 *    tick (never ageInTicks -- see SettlerModel's own comment on why) --
 *    SettlerEntity.tickAccents, cheer
 *  WAKE_STRETCH: t=1.20s -> tick 24 of 52 (one-shot), triggered by
 *    RestAtNightGoal via SettlerEntity.triggerWakeStretch() -- yawn
 *  WALK_LADEN: haul_step at CourierWorkGoal.HAUL_STEP_PERIOD (18) and
 *    haul_strain at HAUL_STRAIN_PERIOD (96), both gated on real movement.
 *    FREQUENCY-ONLY, for the same structural reason as WALK_LIMP above:
 *    animateWalk() samples this clip from limbSwing (distance travelled),
 *    not real time, so no tick-modulo in the goal can lock phase to it --
 *    the periods are the right cadence, but not a synced frame. (18 also
 *    is not a divisor of this clip's own 24-tick/1.20s loop length, so
 *    even a time-based accent would drift against the footfall poses.)
 *  COURIER_LIFT: crate_grip at CourierWorkGoal.LIFT_GRIP_TICK (8). NOT
 *    phase-locked to THIS clip: CourierWorkGoal's loading phase at the
 *    hearth sets activity=SORTING (sortState/COURIER_SORT plays), not a
 *    dedicated lift state -- there is no liftState/entity-event wiring for
 *    this clip in SettlerEntity (out of this piece's file ownership). The
 *    sound therefore currently lands mid-cycle in an unrelated clip's loop
 *    rather than on this clip's own t=0.60s/0.95s accent frames. This clip
 *    is fully authored to the catalogue and passes every structural check;
 *    it has no in-game trigger this slice (see the piece 3 report).
 *  COURIER_CARRY: no sound accent of its own is actually wired -- see
 *    COURIER_SORT below for why crate_creak (piece 4's registered sound
 *    for this clip) is unused.
 *  COURIER_SET_DOWN: crate_down fires once on arrival at the warehouse in
 *    CourierWorkGoal.tickToWarehouse(). NOT phase-locked:
 *    CourierWorkGoal.SET_DOWN_TICK (6) is declared but unused dead code --
 *    the actual play call fires unconditionally on arrival, gated by
 *    distance, not by any tick. Like COURIER_LIFT there is no dedicated
 *    set-down state/event, so this fully-authored clip has no in-game
 *    trigger this slice either.
 *  COURIER_SORT: chest_stow at CourierWorkGoal's SORT_MOVE_TICK (16) of
 *    SORT_PERIOD (32) IS phase-locked -- workTicks and sortState's own
 *    clock both reset in the same tick when Mode.SORTING starts at the
 *    warehouse, and tick 16 of 32 is exactly this clip's t=0.80s accent.
 *    item_pickup (the catalogue's other COURIER_SORT accent, t=0.25s,
 *    piece 4's registered sound) is never played by CourierWorkGoal --
 *    an unused sound, not a broken contract on this clip's side.
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

    /** Carrying something heavy: short-stepping backward-leaning wedge,
     *  compressed root, cloak pinned still. Legs/torso/root/cloak/head only
     *  -- the carry grammar clip (COURIER_CARRY / HAUL_LOG) owns the arms
     *  on a second, layered AnimationState (§16.2 reuse rule; catalogue
     *  §1.2). 1.2s loop. */
    public static final AnimationDefinition WALK_LADEN = AnimationDefinition.Builder
        .withLength(1.2F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-24, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-24, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-24, 0, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-12, 3, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-12, -3, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-12, 3, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
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

    /** Flat-out flight: arms flailing above the head, out of phase. 0.6s loop
     *  (bumped from the catalogue's 0.55s so the accent's quarter-beats land
     *  on the 0.05s tick grid -- recorded in the quality ledger). */
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
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(16, -7, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(44, 5, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(36, 3, 0), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(32, 2, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(18, -3, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(14, -7, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(24, -5, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-84, 10, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-108, 13, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-155, 8, -7), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-26, -7, 0), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-25, -7, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-24, -8, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-52, -13, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-90, -16, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-64, -16, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-16, -6, 0), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-15, -6, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-14, -7, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-34, -12, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-56, -17, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(5, 2, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(22, -3, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(26, 0, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-14, 0, -6), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 3), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(14, 0, 6), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(8, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(18, 0, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-14, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0, -0.6F, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, -0.4F, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, -2.0F, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0, -1.8F, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.posVec(0, -1.3F, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.posVec(0, -1.1F, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM)))
        .build();

    /** Deep squat, one hand pressing seed into the ground. 2s loop. */
    public static final AnimationDefinition FARM_PLANT = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -6, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -6.5F, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0, -9, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.posVec(0, -6, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-62, 0, -8), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-62, 0, -8), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-34, 0, 10), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-34, 0, 10), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(30, -8, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(46, -12, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(38, -4, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(30, -8, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-40, 14, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(5, 20, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(18, 24, 0), LINEAR),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-18, 17, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-40, 14, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -22, 6), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-66, -30, 6), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-58, -22, 6), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, 6, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(26, 3, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(29, 1, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(24, 5, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(22, 6, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-10, 0, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM)))
        .build();

    /** A twist -- reach and pull, then rise and swing to the shoulder bag. 1.8s loop. */
    public static final AnimationDefinition FARM_HARVEST = AnimationDefinition.Builder
        .withLength(1.8F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(28, 16, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(38, 22, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-1, -32, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(6, -19, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(28, 16, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-36, 30, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(21, 38, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(25, 41, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-104, -61, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-78, -46, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-36, 30, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-46, -16, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-52, -19, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-104, -29, 16), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-97, -26, 16), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-46, -16, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(18, 12, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(24, 18, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-1, -23, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(18, 12, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-18, 0, -7), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(11, 0, 7), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -3, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, -5, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, 1, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.posVec(0, -3, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, -4), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(8, 0, -9), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(7, 0, 14), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(3, 0, -4), CATMULLROM)))
        .build();

    /** Upright, arm extended, watering-can tipping. 2.4s loop. */
    public static final AnimationDefinition FARM_WATER = AnimationDefinition.Builder
        .withLength(2.4F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, -10, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(22, -20, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(17, -5, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(12, -10, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -18, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-18, -28, -65), LINEAR),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-8, -28, -85), CATMULLROM),
            new Keyframe(2.1F, KeyframeAnimations.degreeVec(-43, -23, -35), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-58, -18, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-22, 10, 8), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-38, 18, 12), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-22, 10, 8), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(20, -8, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(24, -11, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(26, -11, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(20, -8, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-4, 0, 8), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(13, 0, 10), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(2, 0, 3), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0, -0.3F, 0.2F), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0, -0.2F, 0.3F), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    // -------------------------------------------------------- lumberer ---

    /** Felling swing, REBUILT 2026-08-25 (owner: "Oksa til siden ser
     *  fortsatt javlig ut"; owner-critic AVVIST verdict on the previous
     *  committed build). The failures this build removes: (1) torso and arm
     *  peaked on the same tick -- now a CASCADE: the torso's peak-velocity
     *  interval (0.40-0.45, 18 deg/tick on y) leads the arm's (0.50-0.55,
     *  92 deg/tick) by 2 ticks, hips -> shoulders -> arm; (2) zero
     *  overshoot -- recovery now passes rest on arm y by 13 deg (~11% of
     *  the 120-deg strike travel) before settling; (3) torso z was flat 0
     *  -- now rolls -4 -> +10 (14 deg) with the strike, weight visibly
     *  transferring rear foot -> front foot; (4) no acceleration ramp --
     *  arm y now builds 3 -> 22 -> 92 deg/tick over the last three
     *  intervals; (5) statue legs -- both legs shift 6-7 deg with a 3-deg
     *  rear-heel pivot at contact while root drives +0.7 lateral / -0.9
     *  drop; (6) impact hold drift tightened to <= 2.5 deg over the 3-tick
     *  bite. The axe rides a CHEST-HIGH line all cycle (arm x -80..-104,
     *  the y channel is the swing) instead of the old thigh-height wave.
     *  Loop seam is velocity-continuous on every channel: the settle flows
     *  into the next wind-up.
     *  Sound contract: strike lands t=0.55s; LumbererWorkGoal plays
     *  hearthstead:chop at tick 11 of the 20-tick loop (K=11, P=20). */
    public static final AnimationDefinition CHOP = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-55, -25, 8), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-63, -48, 10), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-104, -86, 14), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-100, -80, 13), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-92, -58, 8), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-80, 34, 2), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-79.5F, 33, 2), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-78.5F, 31.5F, 2), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-76, 22, 3), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-52, -38, 10), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-53.5F, -17, 7), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-55, -25, 8), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-38, -14, -6), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-43, -33, -7), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-76, -64, -10), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-73, -59, -9), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-68, -42, -6), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-58, 26, -2), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-57.5F, 25.2F, -2), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-56.5F, 24, -2), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-54.5F, 16.5F, -1.5F), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-40, -24, -4), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-36, -6, -5.5F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-38, -14, -6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(7, 14, 2), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(5, 19, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(2, 22, -4), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(4, 18, -2), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(10, 3, 4), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(16, -10, 9), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(15, -14, 10), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(14.5F, -13.5F, 9.5F), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(13.5F, -12.5F, 9), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(9, 4, 4), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(8, 9, 3), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(7, 14, 2), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 4, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(5, 7, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(14, 2, 0), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(12.5F, 2.5F, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(9, 2.5F, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(8, 4, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-14, 0, -8), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-12.5F, 0.5F, -7.5F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-11, 2, -7), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-12, 1, -7.3F), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-14.5F, -1, -7.8F), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-19, -3, -8.6F), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-18.2F, -2.8F, -8.4F), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-15.3F, -0.6F, -8.35F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-14, 0, -8), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, 0, 8), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(13.5F, -0.5F, 8.4F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(16, -2, 9), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(15, -1, 8.7F), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(12, 1, 8.2F), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(7, 3, 7.4F), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(7.8F, 2.8F, 7.55F), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(10.7F, 0.6F, 7.75F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(12, 0, 8), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 1), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-5, 0, 3.5F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-12.5F, 0, 6), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(15, 0, -5), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(6.5F, 0, -1.5F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(3, 0, 1), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.posVec(-0.3F, 0.15F, -0.2F), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.posVec(-0.6F, 0.35F, -0.45F), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(-0.35F, 0.2F, -0.25F), LINEAR),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0.2F, -0.35F, 0.05F), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0.7F, -0.9F, 0.35F), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.posVec(0.6F, -0.75F, 0.3F), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0.2F, -0.1F, 0.15F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Short, fast, sideways axe flicks at knee height. Two strikes per loop. 1.3s loop. */
    public static final AnimationDefinition LIMB_BRANCHES = AnimationDefinition.Builder
        .withLength(1.3F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(46, -4, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(40, 0, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(12, 19, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(50, -2, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(43, 2, 0), CATMULLROM),
            new Keyframe(1.15F, KeyframeAnimations.degreeVec(12, 19, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-74, 24, -10), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-94, 27, -14), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-116, 30, -18), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-38, 4, -2), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-35, 4, -2), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-82, 27, -13), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-119, 32, -21), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-40, 7, -2), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-38, 7, -2), LINEAR),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-88, 27, -11), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-74, 24, -10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-35, -2, 2), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-32, -2, 2), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-35, -2, 2), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-32, -2, 2), LINEAR),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-74, -11, 9), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(24, 10, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(31, 5, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(22, 12, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(31, 3, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(22, 12, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(24, 10, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-16, 0, -6), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-22, 0, -9), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-16, 0, -6), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-22, 0, -9), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-16, 0, -6), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 6), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(10, 0, 9), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(4, 0, 6), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(10, 0, 9), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(4, 0, 6), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0, -3.4F, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.posVec(0, -3.4F, 0), LINEAR),
            new Keyframe(1.3F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-10, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(19, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-10, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(19, 0, 0), CATMULLROM),
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
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(14, 0, 16), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(9, 0, 8), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, -14, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(7, -16, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(4, -14, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-10, 0, -7), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(6, 0, -7), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(22, 0, -7), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(6, 0, -7), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(-10, 0, -7), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 7), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-6, 0, 7), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-22, 0, 7), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(-6, 0, 7), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(10, 0, 7), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, 0, -6), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(13, 0, -14), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.degreeVec(5, 0, -6), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, -1.6F, 0), CATMULLROM),
            new Keyframe(2.4F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM)))
        .build();

    // ---------------------------------------------------------- courier ---

    /** One-shot: a squat that stands up. Body compresses to two-thirds
     *  height, HOLDS at the bottom (the grip beat), then rises with the
     *  shoulders hauling -- torso overshoots past vertical before settling,
     *  the counterweight snapping in. Arrives at the carry handoff pose
     *  (catalogue §5, ~line 862); does not return to its own start pose,
     *  so it is in anim_check's ENDS_IN_POSE_ALLOWLIST. 1.4s one-shot. */
    public static final AnimationDefinition COURIER_LIFT = AnimationDefinition.Builder
        .withLength(1.4F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, -7, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, -7, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-54, 0, -10), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-54, 0, -10), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-14, 0, -4), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-4, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-54, 0, 10), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-54, 0, 10), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-14, 0, 4), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-4, 0, 3), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(34, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(32, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-16, 0, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-11, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, 4, -2), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-34, 20, -18), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-40, 22, -20), LINEAR),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-84, 18, -16), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-78, 16, -14), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, -4, 2), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-34, -20, 18), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-40, -22, 20), LINEAR),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-84, -18, 16), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-78, -16, 14), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(28, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-9, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .build();

    /** The flagship carry clip: a boxy figure leaning back with both
     *  forearms locked out front at chest height -- total arm travel 3
     *  degrees, a clamp, not a swing. Layers over WALK_LADEN's legs; owns
     *  torso/head/cloak/root itself so those must be reset before this
     *  applies, or the two clips' holds sum (SettlerModel). Runs whenever
     *  CARRYING, moving or not -- the catalogue requires it read correctly
     *  standing still at a chest, not just in transit. 2.0s loop (against
     *  WALK_LADEN's 1.2s, so the two drift in and out of phase and the haul
     *  never looks mechanically looped). */
    /**
     * Both hands gripping the sack's shoulder straps, not a crate held in
     * front. The first version posed the arms forward at -78 degrees and
     * leaned the torso BACKWARD to counterbalance -- which is how you carry
     * a load in front of you, and this settler's load is on his back. The
     * lean is forward now, the hands are up at the chest on the straps, and
     * the weight of it is in the spine.
     *
     * <p>Read together with SettlerModel.applySack, which adds more forward
     * lean the fuller the sack gets (vanilla animate() is additive), so a
     * near-empty courier walks nearly upright and a full one is bent into it.
     * 2.0s loop.
     */
    public static final AnimationDefinition COURIER_CARRY = AnimationDefinition.Builder
        .withLength(2.0F).looping()
        // Hands up on the straps: raised high, drawn inward across the chest.
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-104, 27, -25), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-101, 26, -27), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-106, 28, -24), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-104, 27, -25), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-104, -27, 25), CATMULLROM),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-101, -26, 27), CATMULLROM),
            new Keyframe(1.4F, KeyframeAnimations.degreeVec(-106, -28, 24), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-104, -27, 25), CATMULLROM)))
        // Forward into the weight, with the trudge rocking side to side.
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(9, 2, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(11, 0, 2.5F), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(9, -2, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(11, 0, -2.5F), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(9, 2, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(SCALE,
            new Keyframe(0.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.scaleVec(1.012, 0.992, 1.012), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
            new Keyframe(1.65F, KeyframeAnimations.scaleVec(1.010, 0.994, 1.010), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
        // Head up against the lean -- he still has to see where he is going.
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-4, 6, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-7, 0, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-4, -5, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-5, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-7, 0, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.degreeVec(-5, 0, 0), CATMULLROM)))
        // Sinks under the load rather than riding level.
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
            new Keyframe(2.0F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM)))
        .build();

    /** One-shot: the reverse of the lift, faster and looser -- drops, the
     *  crate lands (the release beat), shoulders roll back in relief with a
     *  small backward torso flourish at the very end. Departs from the
     *  carry handoff pose; does not return to it, so it is in anim_check's
     *  ENDS_IN_POSE_ALLOWLIST. 1.2s one-shot. */
    public static final AnimationDefinition COURIER_SET_DOWN = AnimationDefinition.Builder
        .withLength(1.2F)
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -1, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -6.5F, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, -6.5F, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-48, 0, -9), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-48, 0, -9), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-48, 0, 9), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-48, 0, 9), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-12, 0, 4), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-11, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(30, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(28, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-5, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-78, 16, -14), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-36, 22, -20), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-30, 22, -20), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-12, 8, -6), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-4, 2, -2), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-78, -16, 14), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-36, -22, 20), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-30, -22, 20), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-12, -8, 6), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-4, -2, 2), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(26, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(7, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .build();

    /** Filing goods into the chest: a steady two-beat reach -- down to the
     *  crate at the feet, up and forward into the chest -- with the head
     *  bobbing between the two and the eyeline moving with the item. One
     *  busy arm (right, grab/place) and one holding arm (left, propping the
     *  lid) is what reads as sorting rather than waving. 1.6s loop. */
    public static final AnimationDefinition COURIER_SORT = AnimationDefinition.Builder
        .withLength(1.6F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-22, 12, -6), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-8, 16, -10), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-52, 10, -6), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-86, 4, -4), LINEAR),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-64, 8, -5), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-22, 12, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -14, 8), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-66, -18, 10), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-62, -16, 9), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-58, -14, 8), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(18, 8, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(24, 12, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(6, -4, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(12, 2, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(18, 8, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, 10, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(26, 12, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(2, -6, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(22, 10, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.posVec(0, -3.5F, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.posVec(0, -2, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(9, 0, 3), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(2, 0, -1), CATMULLROM),
            new Keyframe(1.6F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM)))
        .build();

    // ------------------------------------------------------------ guard ---

    /** The low guard (Pflug): a trained swordsman at rest -- controlled,
     *  threatening, zero wasted tension (animation-quality §2.2).
     *  Blade 30-45° below horizontal continuing the forearm line
     *  (right_arm x -28..-30, y -10..-11.5 draws the hilt in front of the
     *  hip, z 5-6.5 tucks the upper arm to the ribs); off-hand across the
     *  body at the hip; legs staggered (left foot leads, sword-side foot
     *  back, x -10/+12), knees soft, weight ~60/40 front. Torso +6-7.5°
     *  forward and ready. The clock IS the confidence: one 4.0 s breath
     *  (rise 2.2 s, fall 1.8 s -- down faster), sway 3° total, and ONE
     *  slow deliberate head scan (y ≤ 17°) per loop. Every channel seams
     *  mid-motion (value AND velocity continuous, §3 check 6). 4s loop. */
    public static final AnimationDefinition GUARD_STANCE = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12.15F, -6, -3.15F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(12, -6, -3), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(12.5F, -6, -3.4F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(12.15F, -6, -3.15F), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-9.85F, 5, 3.15F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-10, 5, 3), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(-9.6F, 5, 3.4F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-9.85F, 5, 3.15F), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6.5F, 0.5F, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(6, 1.5F, 0), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(7.5F, -1.5F, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(6.5F, 0.5F, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-28.65F, -10.5F, 5.5F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-28, -10, 5), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(-30, -11.5F, 6.5F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-28.65F, -10.5F, 5.5F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20.65F, 14.5F, 4.35F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-20, 14, 4), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(-22, 15.5F, 5), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-20.65F, 14.5F, 4.35F), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(1, 0.6F, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(1.8F, -1.2F, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(0.5F, 0.5F, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(0, 17, 0), CATMULLROM),
            new Keyframe(3.1F, KeyframeAnimations.degreeVec(0.4F, 15.5F, 0), CATMULLROM),
            new Keyframe(3.6F, KeyframeAnimations.degreeVec(0.8F, 1.8F, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(1, 0.6F, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4.05F, 0, 0.25F), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(3, 0, 1.5F), CATMULLROM),
            new Keyframe(2.95F, KeyframeAnimations.degreeVec(5.5F, 0, -1.5F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(4.05F, 0, 0.25F), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0.12F, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.posVec(0, 0.35F, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.posVec(0, 0.12F, 0), CATMULLROM)))
        .build();

    /** Layers over WALK's legs/torso/cloak: locked pommel-hand + wide scan.
     *  Only touches right_arm/left_arm/head -- SettlerModel resets those two
     *  arm parts before applying this, so it overrides rather than adds to
     *  WALK's swing. 4s loop (co-prime-ish with WALK's 1s so the scan never
     *  syncs to footfalls). */
    public static final AnimationDefinition GUARD_PATROL = AnimationDefinition.Builder
        .withLength(4.0F).looping()
        // Same Pflug carry as GUARD_STANCE (animation-quality §2.2): the
        // blade continues the forearm line 30-45° below horizontal, the
        // hilt sits in front of the hip (y negative), the upper arm tucks
        // to the ribs (z positive) -- never flared out. Arms locked to
        // ≤ 1° of drift: the overriding of WALK's swing is the entire read.
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30.35F, -8.2F, 5.2F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-30, -8, 5), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(-31, -8.6F, 5.6F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-30.35F, -8.2F, 5.2F), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20.35F, 15.2F, 6.2F), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-20, 15, 6), CATMULLROM),
            new Keyframe(2.8F, KeyframeAnimations.degreeVec(-21, 15.6F, 6.6F), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(-20.35F, 15.2F, 6.2F), CATMULLROM)))
        // ONE slow deliberate scan per 4 s loop (confident clock, §2.2) --
        // to the opposite side of GUARD_STANCE's scan, so a guard passing
        // between post and patrol appears to cover both flanks.
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0.8F, -0.5F, 0), CATMULLROM),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(1.5F, 1, 0), CATMULLROM),
            new Keyframe(1.8F, KeyframeAnimations.degreeVec(0.3F, -0.4F, 0), CATMULLROM),
            new Keyframe(2.6F, KeyframeAnimations.degreeVec(-1, -18, 0), CATMULLROM),
            new Keyframe(3.1F, KeyframeAnimations.degreeVec(-0.6F, -16.5F, 0), CATMULLROM),
            new Keyframe(3.6F, KeyframeAnimations.degreeVec(0.6F, -1.6F, 0), CATMULLROM),
            new Keyframe(4.0F, KeyframeAnimations.degreeVec(0.8F, -0.5F, 0), CATMULLROM)))
        .build();

    /** One-shot diagonal slash with hip rotation and lunging legs. 0.5s. */
    public static final AnimationDefinition MELEE = AnimationDefinition.Builder
        .withLength(0.5F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM),
            new Keyframe(0.05F, KeyframeAnimations.degreeVec(-62, 6, 0), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-168, 22, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(24, -26, 0), LINEAR),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(20, -24, 0), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-42, 6, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-20, 0, 0), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-28, 3, 2), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(18, 10, 8), LINEAR),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(15, 9, 7), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-26, 2, 1), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-20, 0, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 20, 0), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(9, -24, 0), LINEAR),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(6, -20, 0), LINEAR),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(5, -18, 0), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(2, 27, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0, 20, 0), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.posVec(0, -0.8F, 1.0F), LINEAR),
            new Keyframe(0.3F, KeyframeAnimations.posVec(0, -0.7F, 0.9F), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.posVec(0, 0.15F, -0.15F), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 14, 0), CATMULLROM),
            new Keyframe(0.05F, KeyframeAnimations.degreeVec(2, 4, 0), LINEAR),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(4, -12, 0), LINEAR),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(3, -10, 0), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(1, 19, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(0, 14, 0), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.1F, KeyframeAnimations.degreeVec(-14, 0, -10), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(16, 0, 13), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(10, 0, 8), CATMULLROM),
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

    // ================================================================== //
    // SLICE CHAINS-1 -- the six crafting motions.                        //
    //                                                                    //
    // Keyed to the ACTION, not the job title (D-015): a butcher and a    //
    // tanner both cleave, a smith and a mason both strike. Eleven trades //
    // read as six real jobs of work, and none of them is a generic loop. //
    //                                                                    //
    // Every clip below follows the craft standard in the                 //
    // `animation-quality` skill: the wind-up ACCELERATES rather than     //
    // drifting, there is a visible BEAT at the moment of contact, the    //
    // recovery OVERSHOOTS past rest before settling, and the TORSO leads //
    // the arm by a few frames. The two clips with no impact (SAW, WEAVE) //
    // earn their weight a different way, noted on each.                  //
    // ================================================================== //

    /**
     * Bakery and kitchen: pressing dough into a bench.
     *
     * <p>No strike, so no beat — what sells this one is <b>continuous
     * pressure</b>. The push bottoms out and stays there while the torso keeps
     * driving down for another three ticks, which reads as leaning body weight
     * onto the heel of the hand rather than tapping a table. 1.2s loop.
     */
    public static final AnimationDefinition KNEAD = AnimationDefinition.Builder
        .withLength(1.2F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-58, -16, 6), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-24, -20, 10), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-88, -12, 4), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-85, -12, 4), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-35, -18, 8), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-58, -16, 6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-52, 18, -6), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-86, 13, -4), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-38, 20, -9), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-42, 20, -9), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-76, 15, -4), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-52, 18, -6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(26, 8, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(34, -6, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(32, -6, 0), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(16, 6, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(10, 3, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(25, -7, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(13, 2, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-10, 0, -5), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-2, 0, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -1.1F, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.posVec(0, -1.0F, 0), LINEAR),
            new Keyframe(1.20F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Butcher and tannery: a short cleaving stroke at bench height.
     *
     * <p>Shorter travel than a woodcutter's chop and much faster through the
     * bottom, because a cleaver is light and the target is close. It still
     * gets its beat — three ticks parked at the board — and the off hand
     * holds the work down, which is what makes the stroke look aimed rather
     * than flailed. 0.85s loop.
     */
    public static final AnimationDefinition CLEAVE = AnimationDefinition.Builder
        .withLength(0.85F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-46, -12, -6), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-71, -12, -7), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-147, -9, -9), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-141, -9, -9), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-54, -13, -5), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-52, -13, -5), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-29, -12, -6), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-46, -12, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-68, 22, -4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-72, 22, -4), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-70, 23, -4), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-68, 22, -4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(12, 6, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-4, 16, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(28, -10, 0), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(25, -8, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(6, 9, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(12, 6, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(18, 2, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(12, 4, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(27, -1, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(18, 2, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-13, 0, -6), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(7, 0, 4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(12, 0, 6), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(7, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-18, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(17, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.posVec(0, 0.4F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -1.0F, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.9F, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Smelter: working the bellows, and flinching off the heat.
     *
     * <p>The push is slow and two-handed — bellows resist — and the
     * distinctive beat is at the <b>end of the stroke</b>, where the arms stay
     * compressed while the fire answers. The recoil is the interesting half:
     * the torso pulls <i>back and away</i> rather than simply returning, which
     * reads as standing in front of something far too hot. 1.4s loop.
     */
    public static final AnimationDefinition STOKE = AnimationDefinition.Builder
        .withLength(1.4F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-38, -14, 4), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-27, -15, 5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-105, -8, 1), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-102, -8, 1), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-18, -17, 7), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-38, -14, 4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-38, 14, -4), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-27, 15, -5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-105, 8, -1), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-102, 8, -1), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-18, 17, -7), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-38, 14, -4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(30, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(37, 0, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(34, 0, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-19, 0, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-16, 10, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-14, 0, -6), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(14, 0, 6), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(23, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-15, 0, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.posVec(0, -0.85F, 0.5F), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -0.75F, 0.5F), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.posVec(0, 0.35F, -0.6F), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Smithy and mason: the anvil strike. The heaviest clip in the set.
     *
     * <p>Everything the craft standard asks for, at full size: the wind-up
     * accelerates into the top, the torso peaks <b>three ticks before</b> the
     * arm reaches the anvil, the hammer parks for a four-tick beat at the
     * bottom, and the recovery overshoots well past rest before settling. The
     * off hand grips the work with tongs and barely moves — a still hand next
     * to a violent one is what makes the violent one read. 1.0s loop.
     */
    public static final AnimationDefinition HAMMER_ANVIL = AnimationDefinition.Builder
        .withLength(1.0F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-40, -10, -5), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-66, -10, -6), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-172, -8, -8), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-165, -8, -8), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-30, -13, -4), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-29, -13, -4), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-27, -14, -4), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-18, -11, -5), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-52, -10, -5), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-40, -10, -5), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-78, 20, -3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-82, 20, -3), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-80, 21, -3), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-78, 20, -3), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 4, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-10, 16, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-20, 19, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(38, -9, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(33, -7, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-2, 11, 0), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(10, 4, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(12, 3, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(32, 0, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(29, 0, 0), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-11, 0, -5), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-18, 0, -6), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-16, 0, -6), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-11, 0, -5), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(13, 0, 7), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-26, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(23, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.posVec(0, 0.75F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -1.3F, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -1.1F, 0), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Sawmill and carpenter: a two-handed push-and-pull saw stroke.
     *
     * <p>No impact, so no beat — instead the weight lives in the
     * <b>reversals</b>. Each end of the stroke holds for two ticks while the
     * blade bites and the body changes direction, and the torso rocks with the
     * arms rather than staying planted. A saw animated as a smooth sine wave
     * looks like waving; the pauses are what make it cut. 1.1s loop, one full
     * push-pull.
     */
    public static final AnimationDefinition SAW = AnimationDefinition.Builder
        .withLength(1.1F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-34, -8, -2), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-140, -4, -4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-135, -4, -4), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-21, -10, -2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-25, -10, -2), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-34, -8, -2), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-52, 10, 2), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-149, 6, 4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-144, 6, 4), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-39, 12, 2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-43, 12, 2), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-52, 10, 2), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(16, -4, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(36, 10, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(34, 10, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(6, -10, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(8, -10, 0), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(16, -4, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(26, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(19, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-22, 0, -4), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(18, 0, 4), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(23, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-11, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.posVec(0, -0.6F, 0.6F), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -0.15F, -0.5F), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Weaver and fletcher: close, quick, fiddly hand work.
     *
     * <p>Deliberately the opposite of everything above — small amplitude, high
     * frequency, head down, torso almost still. It is here to make the heavy
     * clips read heavy: a village where every trade swings from the shoulder
     * has no scale to it. Two passes per loop so the hands look busy rather
     * than metronomic. 0.9s loop.
     */
    public static final AnimationDefinition FINE_WORK = AnimationDefinition.Builder
        .withLength(0.9F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-74, -22, 10), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-82, -18, 13), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-70, -24, 8), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-90, -16, 15), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-72, -23, 9), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-74, -22, 10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-78, 21, -9), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-70, 25, -7), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-88, 17, -12), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-72, 24, -8), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-78, 21, -9), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(21, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(25, 4, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(21, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(27, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(31, -4, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(30, 4, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(27, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-4, 0, -2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-4, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(13, 0, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.35F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.45F, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.posVec(0, -0.35F, 0), CATMULLROM)))
        .build();

    // ================================================================== //
    // Signature motions (D-016). Every trade gets the ONE thing it does  //
    // that nobody else does -- the beat you recognise from across the    //
    // square. Owner, 2026-08-25: "pass på alle har sin distinkte ting.   //
    // Da virker moden gjennomtenkt."                                     //
    // ================================================================== //

    /**
     * Lumberjack: stooping to gather the log they just felled.
     *
     * <p>Felling was only ever half the job. The half that makes it read as
     * work is what happens after the tree comes down — the knees bend, the
     * back takes the weight, and the settler comes up slower than they went
     * down. That asymmetry is the whole clip: 0.35 s to drop, 0.55 s to rise,
     * because a log is heavy and standing up under one is not the reverse of
     * bending over. One-shot, 1.10 s.
     */
    public static final AnimationDefinition GATHER_LOG = AnimationDefinition.Builder
        .withLength(1.10F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, -6, -3), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-30, -10, -4), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-92, -18, -6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-90, -18, -6), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-62, -14, -5), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-70, -15, -5), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-8, -6, -3), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 6, 3), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-30, 10, 4), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-92, 18, 6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-90, 18, 6), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-62, 14, 5), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-70, 15, 5), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-8, 6, 3), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(18, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(52, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(50, 0, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(26, 0, 0), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, -3), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-34, 0, -6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-33, 0, -6), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-4, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(30, 0, 6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(29, 0, 6), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(34, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(30, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, -2.4F, 0.5F), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.posVec(0, -2.35F, 0.5F), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.posVec(0, -0.3F, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Baker: working the oven with a peel.
     *
     * <p>The signature is the <b>flip</b>. Both hands drive the peel forward
     * into the oven mouth, hold there for three ticks while the loaf goes in,
     * and then the wrists snap over — a fast roll on the arms' Z axis that
     * nothing else in the mod does. The torso leans away as the heat comes
     * back, which is the beat you actually recognise a baker by. 1.60 s loop.
     */
    public static final AnimationDefinition OVEN_TEND = AnimationDefinition.Builder
        .withLength(1.60F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-52, -14, 4), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-41, -15, 5), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-111, -7, 1), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-109, -7, 1), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-101, -6, 61), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-98, -6, 58), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-36, -17, -10), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-57, -14, 7), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-52, -14, 4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-52, 14, -4), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-41, 15, -5), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-111, 7, -1), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-109, 7, -1), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-101, 6, -61), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-98, 6, -58), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-36, 17, 10), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-57, 14, -7), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-52, 14, -4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(30, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(32, 0, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(30, 0, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-18, 0, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-11, 7, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-15, 0, -3), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(10, 0, 3), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-17, 0, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.7F, 0.75F), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0, -0.6F, 0.75F), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.posVec(0, 0.3F, -0.7F), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Farmer: broadcasting seed by hand.
     *
     * <p>The oldest sowing motion there is, and it makes a farmer readable at
     * fifty blocks: the off hand cradles the seed bag at the hip, the right
     * hand dips into it, and then sweeps across the body in a wide arc and
     * opens. The <b>release</b> is the beat — the arm parks for two ticks at
     * the end of the arc while the seed leaves the hand — and the torso
     * rotates a full 30 degrees to drive it, because the arc comes from the
     * hips or it comes from nowhere. 1.40 s loop.
     */
    public static final AnimationDefinition SOW_BROADCAST = AnimationDefinition.Builder
        .withLength(1.40F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-16, 28, 22), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-43, 46, 34), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-37, 52, 39), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-79, -65, -38), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-76, -68, -40), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-52, -83, -50), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-7, -2, 4), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-16, 28, 22), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-64, 26, -14), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-72, 30, -16), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-68, 28, -16), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-60, 24, -12), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-64, 26, -14), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 10, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(3, 28, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(2, 31, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(9, -32, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(8, -31, 0), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(4, -14, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(6, 10, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 12, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(13, 25, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(16, -30, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(10, 12, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-5, 4, -3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-12, -14, -5), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-5, 4, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, -4, 3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(10, 14, 5), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(5, -4, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-13, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(26, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(7, 0, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(-0.5F, 0.2F, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.posVec(0.6F, -0.25F, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Miner: a two-handed pick into rock. Catalogue 8.1.
     *
     * <p>Shorter and tighter than {@code CHOP}, because a pick is swung into a
     * face an arm's length away rather than through a trunk. The beat is
     * longer than any other clip in the mod — four ticks — since a pick point
     * genuinely lodges in stone and has to be worked free, and that lodging is
     * the whole character of the motion. The recovery is a pull, not a lift:
     * the torso rocks back to free the point before the arms reset. 0.95 s
     * loop.
     */
    public static final AnimationDefinition MINE_PICK = AnimationDefinition.Builder
        .withLength(0.95F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-38, -12, -6), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-73, -11, -7), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-161, -8, -9), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-156, -8, -9), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-51, -13, -5), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-48, -13, -5), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-13, -12, -6), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-46, -12, -6), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-38, -12, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-46, 16, 6), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-149, 11, 9), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-144, 11, 9), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-56, 17, 5), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-54, 17, 5), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-26, 16, 6), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-46, 16, 6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 3, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-12, 14, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-21, 17, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(40, -10, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(35, -8, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(1, 9, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(14, 3, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(16, 2, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(31, -2, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(28, -2, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(19, -9, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-13, 0, -5), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-24, 0, -8), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-20, 0, -7), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-13, 0, -5), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 5), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(16, 0, 8), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(10, 0, 5), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.posVec(0, 0.7F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -1.3F, 0.4F), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -1.2F, 0.4F), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Sergeant's leap strike: the one everybody will remember.
     *
     * <p>Four beats, and the whole clip lives or dies on the third:
     *
     * <ol>
     *   <li><b>Coil</b> (0-0.20 s) — a deep crouch that gathers, accelerating
     *       into the bottom. The sword goes BACK, not up: this is a lunge.
     *   <li><b>Launch</b> (0.20-0.30 s) — legs snap straight, root rises hard,
     *       the torso opens. Fast, and it is allowed to be: it is bracketed by
     *       the coil's hold and the float.
     *   <li><b>Float</b> (0.30-0.50 s) — the sword comes overhead and
     *       <b>hangs</b> for four ticks at the top. Airborne hang time is what
     *       makes a leap feel like a leap rather than a hop, and it is the
     *       moment the player reads the threat.
     *   <li><b>Slam</b> (0.50-0.60 s) — down, hard, with a five-tick beat at
     *       the bottom while the ground takes it. Then a slow rise, because
     *       nobody springs back up from a landing like that.
     * </ol>
     *
     * <p>One-shot, 1.30 s, and it returns to its start pose so the guard drops
     * straight back into their stance. Catalogue 19.1.
     */
    public static final AnimationDefinition LEAP_STRIKE = AnimationDefinition.Builder
        .withLength(1.30F)
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, -14, -6), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(18, -20, -10), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-72, -16, -8), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-168, -10, -6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-165, -10, -6), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-26, -15, -4), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-24, -15, -4), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-46, -14, -6), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-30, -14, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-26, 16, 6), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(24, 26, 12), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-96, 30, 16), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-92, 30, 16), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-40, 22, 8), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-38, 22, 8), LINEAR),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-26, 16, 6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(42, 10, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-23, 5, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-30, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(50, -5, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(45, -4, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-7, 3, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-18, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(30, 0, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(27, 0, 0), LINEAR),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-6, 0, -4), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-52, 0, -8), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(14, 0, -3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-30, 0, -5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-58, 0, -9), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-54, 0, -9), LINEAR),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-6, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(46, 0, 8), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-16, 0, 3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(26, 0, 5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(50, 0, 9), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(46, 0, 9), LINEAR),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(26, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-38, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(44, 0, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.posVec(0, -3.4F, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, 4.6F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, 5.2F, 0), LINEAR),
            new Keyframe(0.50F, KeyframeAnimations.posVec(0, 4.9F, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -2.9F, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -2.6F, 0), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.posVec(0, 0.4F, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Catalogue §20.1 — the cook's stir. A pot held steady in the left hand
     * while the right traces a slow ellipse, with a faster sweep at the far
     * side of the pot, the way a real stir accelerates through the thick of
     * the stew. Head down in the steam the whole time.
     */
    public static final AnimationDefinition COOK_STIR = AnimationDefinition.Builder
        .withLength(1.50F).looping()
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-64, -24, 8), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-86, 11, 21), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-99, 60, 8), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-82, 86, -14), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-55, 51, -27), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(-51, 7, -14), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(-64, -24, 8), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-46, 18, -8), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-54, 14, -10), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(-46, 18, -8), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, -3, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(17, 10, 3), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(10, 12, -3), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(12, -3, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, -4, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(25, 8, 0), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(22, -4, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-4, 0, -2), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-7, 0, -4), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(-4, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 2), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(6, 0, 4), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(3, 0, 2), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(12, 3, 0), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -0.2F, 0.15F), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.posVec(0, -0.1F, -0.15F), CATMULLROM),
            new Keyframe(1.50F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Catalogue §20.2 — the carpenter's plane. A two-handed push away from
     * the chest with the torso committing first and the whole body weight
     * following through, then the light drag back for the next stroke.
     * Push fast, return slow: planing is asymmetric or it is sanding.
     */
    public static final AnimationDefinition CARPENTER_PLANE = AnimationDefinition.Builder
        .withLength(1.30F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(25, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(53, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(50, 0, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-52, -6, 6), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-94, -6, 6), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-160, 2, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-154, 2, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-40, -9, 9), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-52, -6, 6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-52, 6, -6), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-94, 6, -6), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-160, -2, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-154, -2, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-40, 9, -9), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-52, 6, -6), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(11, 0, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-14, 0, -2), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-24, 0, -2), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-14, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, 0, 2), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(22, 0, 2), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(12, 0, 2), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(28, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.2F, 0.7F), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.2F, 0.65F), LINEAR),
            new Keyframe(1.30F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Catalogue §20.3 — the mason's chisel. Left hand seats the chisel and
     * stays put; the right raises the mallet with an accelerating wind-up,
     * lands one ringing tap, holds a beat on the stone, and recovers past
     * rest before settling. Impact clip: the craft standard applies in full.
     */
    public static final AnimationDefinition MASON_CHISEL = AnimationDefinition.Builder
        .withLength(1.05F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, -8, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(11, -4, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(22, 3, 0), LINEAR),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(17, -1, 0), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(17, -1, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(2, -10, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(6, -8, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-50, 0, 10), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-68, 3, 13), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-98, 5, 16), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-125, 7, 18), LINEAR),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-18, 2, 7), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-18, 2, 7), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-68, 0, 11), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-50, 0, 10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, 24, -6), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-60, 24, -6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-54, 22, -7), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-56, 23, -6), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-58, 24, -6), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(18, -10, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(12, -12, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(25, -6, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(15, -9, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(18, -10, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-8, 0, -2), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-13, 0, -4), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-8, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 2), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(6, 0, 2), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(15, 0, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(11, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-3, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, 0.3F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, 0.15F, 0), LINEAR),
            new Keyframe(0.50F, KeyframeAnimations.posVec(0, -0.7F, 0), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.posVec(0, -0.6F, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    /**
     * Catalogue §20.4 — the fletcher's fletching. An arrow held up to the
     * eye in the left hand while the right makes three small precise pinches
     * seating the feathers, the head tilted in close. The smallest clip in
     * the set on purpose: fine work reads through stillness, not sweep.
     */
    public static final AnimationDefinition FLETCHER_FLETCH = AnimationDefinition.Builder
        .withLength(1.60F).looping()
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-78, 16, -10), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-82, 15, -10), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-78, 16, -10), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-58, -14, 4), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-68, -12, 5), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-73, -11, 5), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-63, -13, 4), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-72, -11, 5), CATMULLROM),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-74, -11, 5), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-62, -13, 4), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-71, -12, 5), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-73, -11, 5), LINEAR),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-55, -14, 4), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-58, -14, 4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(7, 5, 0), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(7, 5, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 8, 6), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(18, 4, 8), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(14, 8, 6), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-5, 0, -3), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-5, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM)))
        .build();

    /**
     * Catalogue §20.5 — the tanner's scrape. A two-handed scraper drawn hard
     * down the hide, the torso rocking into every stroke and the whole frame
     * dipping with it, then the light lift back up the frame. The heaviest
     * loop of the five: tanning is shoulders, not fingers.
     */
    public static final AnimationDefinition TANNER_SCRAPE = AnimationDefinition.Builder
        .withLength(1.20F).looping()
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(50, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(48, 0, 0), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-92, -8, 6), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-78, -8, 6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-9, -12, 10), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-13, -12, 10), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-99, -6, 4), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-92, -8, 6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-92, 8, -6), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-78, 8, -6), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-9, 12, -10), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-13, 12, -10), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-99, 6, -4), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-92, 8, -6), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(27, 0, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-10, 0, -2), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-18, 0, -2), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-10, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 2), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(16, 0, 2), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(8, 0, 2), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(28, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-3, 0, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.posVec(0, -0.5F, 0.5F), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.45F, 0.5F), LINEAR),
            new Keyframe(1.20F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    // ================================================================== //
    // TRADES-1 (SURVIVAL_AUDIT F1 / catalogue 24): three new signature      //
    // motions for the Ring-1 gathering trades that had no worker code at   //
    // all until this piece -- herder, fisher, hunter. Built as their own   //
    // private buildXxx() methods from the start, per this file's own      //
    // <clinit> 64KB note further down: the note says the NEXT new clip     //
    // "can go back to a plain inline field initializer UNTIL the class     //
    // starts failing to compile", but three clips (plus a fourth, the      //
    // idle, further down with its sibling buildIdleXxx() methods) is       //
    // exactly the kind of batch that note warns tips the balance, so all   //
    // three use the pattern pre-emptively rather than waiting for the      //
    // compiler to say so.                                                  //
    // ================================================================== //

    /**
     * HERDER: shears a docile sheep held at the flank. One hand runs the
     * shears through a fast snip (a 40deg/tick snap at contact, matching the
     * "timing IS weight" reading a light tool still needs); the other hand
     * just holds the fleece taut and barely moves at all (animation-quality
     * principle 11 -- the working hand only reads because the steadying one
     * holds still). Torso leads the snap by two ticks, the same "torso
     * leads, arm follows" rule the mod's heavier impacts already use. 1.00s
     * loop; one full loop is one shear pass. Catalogue 24.1.
     */
    private static AnimationDefinition buildHerderShear() {
            AnimationDefinition HERDER_SHEAR = AnimationDefinition.Builder
            .withLength(1.00F).looping()
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-55, 8, 6), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(-48, 5, 4), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(-15, -20, -15), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-9, -26, -19), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-82, 34, 28), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-80, 33, 27), LINEAR),
                new Keyframe(0.75F, KeyframeAnimations.degreeVec(-62, 12, 10), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(-57, 9, 7), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(-55, 8, 6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-20, -10, -6), CATMULLROM),
                new Keyframe(0.50F, KeyframeAnimations.degreeVec(-22, -11, -6), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(-20, -10, -6), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(32, 4, 0), CATMULLROM),
                new Keyframe(0.15F, KeyframeAnimations.degreeVec(28, 2, 0), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(44, 12, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(40, 10, 0), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(38, 9, 0), LINEAR),
                new Keyframe(0.75F, KeyframeAnimations.degreeVec(24, 1, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(32, 4, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(10, 3, 0), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(22, -4, 0), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(20, -4, 0), LINEAR),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(-13, 0, -6), LINEAR),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.degreeVec(10, 0, 5), LINEAR),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(0.25F, KeyframeAnimations.degreeVec(-10, 0, 0), CATMULLROM),
                new Keyframe(0.50F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.posVec(0, 0.3F, 0.2F), CATMULLROM),
                new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.6F, 0.3F), LINEAR),
                new Keyframe(0.55F, KeyframeAnimations.posVec(0, -0.55F, 0.3F), LINEAR),
                new Keyframe(1.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .build();
        return HERDER_SHEAR;
    }

    public static final AnimationDefinition HERDER_SHEAR = buildHerderShear();

    /**
     * FISHER: a patient watch at the water's edge that breaks into a hook-
     * set. Most of the 2.00s loop is a slow, continuous sway (breath-scale
     * motion folded into torso/head so the pose is never dead-still, per
     * animation-quality's "no dead-still frames") -- then at 1.30-1.45s the
     * line goes taut: torso yanks back (peaking two ticks before the arm's
     * own snap, the standard torso-leads-arm lead time), the rod arm snaps
     * a fast 40deg in one tick (LINEAR both keys), holds through the fight,
     * then eases up past rest -- a real fisherman lifts the rod tip higher
     * than its resting angle after a catch, which is exactly the overshoot
     * this recovery uses. One full loop is one catch
     * (FisherWorkGoal.FISH_CADENCE). Catalogue 24.2.
     */
    private static AnimationDefinition buildFisherCast() {
            AnimationDefinition FISHER_CAST = AnimationDefinition.Builder
            .withLength(2.00F).looping()
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-75, -6, 10), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(-72, -5, 9), CATMULLROM),
                new Keyframe(0.75F, KeyframeAnimations.degreeVec(-77, -7, 11), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-74, -6, 10), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-70, -4, 9), CATMULLROM),
                new Keyframe(1.40F, KeyframeAnimations.degreeVec(-72, -5, 9), LINEAR),
                new Keyframe(1.45F, KeyframeAnimations.degreeVec(-112, 14, 24), LINEAR),
                new Keyframe(1.55F, KeyframeAnimations.degreeVec(-108, 13, 23), LINEAR),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(-64, -3, 7), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.degreeVec(-73, -5, 9), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-75, -6, 10), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-55, 8, -6), CATMULLROM),
                new Keyframe(0.35F, KeyframeAnimations.degreeVec(-58, 9, -6), CATMULLROM),
                new Keyframe(0.75F, KeyframeAnimations.degreeVec(-53, 7, -5), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-50, 6, -5), CATMULLROM),
                new Keyframe(1.45F, KeyframeAnimations.degreeVec(-78, 20, -14), LINEAR),
                new Keyframe(1.55F, KeyframeAnimations.degreeVec(-76, 19, -13), LINEAR),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(-52, 8, -6), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-55, 8, -6), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 6, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(8, 4, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(12, 7, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(7, 3, 0), CATMULLROM),
                new Keyframe(1.35F, KeyframeAnimations.degreeVec(-18, -12, 0), CATMULLROM),
                new Keyframe(1.45F, KeyframeAnimations.degreeVec(-16, -11, 0), LINEAR),
                new Keyframe(1.55F, KeyframeAnimations.degreeVec(-14, -10, 0), LINEAR),
                new Keyframe(1.80F, KeyframeAnimations.degreeVec(4, 1, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(10, 6, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(14, 10, 0), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(16, -8, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(13, 6, 0), CATMULLROM),
                new Keyframe(1.35F, KeyframeAnimations.degreeVec(24, -2, 0), CATMULLROM),
                new Keyframe(1.45F, KeyframeAnimations.degreeVec(26, -3, 0), LINEAR),
                new Keyframe(1.55F, KeyframeAnimations.degreeVec(25, -3, 0), LINEAR),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(15, 4, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(14, 10, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -2), CATMULLROM),
                new Keyframe(0.65F, KeyframeAnimations.degreeVec(-9, 0, -3), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-6, 0, -2), CATMULLROM),
                new Keyframe(1.45F, KeyframeAnimations.degreeVec(-13, 0, -5), LINEAR),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-6, 0, -2), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
                new Keyframe(0.65F, KeyframeAnimations.degreeVec(8, 0, 3), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
                new Keyframe(1.45F, KeyframeAnimations.degreeVec(11, 0, 4), LINEAR),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(0.50F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-2, 0, 0), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
                new Keyframe(1.80F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.65F, KeyframeAnimations.posVec(0, 0.25F, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(1.45F, KeyframeAnimations.posVec(0, -0.8F, 0.3F), LINEAR),
                new Keyframe(1.60F, KeyframeAnimations.posVec(0, -0.7F, 0.3F), LINEAR),
                new Keyframe(1.85F, KeyframeAnimations.posVec(0, 0.2F, -0.1F), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .build();
        return FISHER_CAST;
    }

    public static final AnimationDefinition FISHER_CAST = buildFisherCast();

    /**
     * HUNTER: a bow draw and loose. The bow arm ({@code left_arm}) is held
     * almost perfectly still throughout -- the stillness principle again,
     * this time on the OFF-hand, because a bow only reads if the bow arm
     * doesn't waver. The draw hand accelerates back to full extension near
     * the ear, holds the aim for two ticks, then the string looses at
     * 96deg/tick (heavy-impact velocity, LINEAR both keys) -- the hand snaps
     * forward as the tension releases. Torso leads the loose by three ticks
     * (peak twist at 0.55s against the arm's 0.70s release). 1.20s loop; one
     * full loop is one shot (HunterWorkGoal.HUNT_DURATION). Catalogue 24.3.
     */
    private static AnimationDefinition buildHunterLoose() {
            AnimationDefinition HUNTER_LOOSE = AnimationDefinition.Builder
            .withLength(1.20F).looping()
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-70, 20, 10), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-74, 22, 11), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-72, 21, 10), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-70, 20, 10), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-58, -10, -8), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(-62, -14, -10), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-95, -40, -24), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-132, -68, -40), CATMULLROM),
                new Keyframe(0.65F, KeyframeAnimations.degreeVec(-136, -70, -41), LINEAR),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-40, 30, 20), LINEAR),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(-42, 29, 19), LINEAR),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(-63, 8, 3), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-58, -10, -8), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 14, 0), CATMULLROM),
                new Keyframe(0.20F, KeyframeAnimations.degreeVec(4, 16, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(-8, 22, 0), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-16, 28, 0), CATMULLROM),
                new Keyframe(0.65F, KeyframeAnimations.degreeVec(-13, 25, 0), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(14, 2, 0), LINEAR),
                new Keyframe(0.80F, KeyframeAnimations.degreeVec(11, 4, 0), LINEAR),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(8, 10, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(6, 14, 0), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 4, 0), CATMULLROM),
                new Keyframe(0.40F, KeyframeAnimations.degreeVec(6, 2, 0), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(12, -6, 0), CATMULLROM),
                new Keyframe(0.65F, KeyframeAnimations.degreeVec(13, -6, 0), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(4, 8, 0), LINEAR),
                new Keyframe(0.85F, KeyframeAnimations.degreeVec(6, 5, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(8, 4, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-10, 0, -6), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(-14, 0, -8), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(-8, 0, -4), LINEAR),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-10, 0, -6), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(13, 0, 7), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(7, 0, 3), LINEAR),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(0.30F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
                new Keyframe(0.95F, KeyframeAnimations.degreeVec(-3, 0, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.55F, KeyframeAnimations.posVec(0, 0.3F, -0.3F), CATMULLROM),
                new Keyframe(0.70F, KeyframeAnimations.posVec(0, -0.4F, 0.3F), LINEAR),
                new Keyframe(0.85F, KeyframeAnimations.posVec(0, -0.3F, 0.2F), LINEAR),
                new Keyframe(1.20F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .build();
        return HUNTER_LOOSE;
    }

    public static final AnimationDefinition HUNTER_LOOSE = buildHunterLoose();

    // ================================================================== //
    // Universal punctuation (D-020, owner request 2026-08-25): "jeg vil at //
    // ALLE settlere av enhver type skal ha en animasjon for å plukke opp   //
    // ting og legge dem i vesken sin." One clip every settler owns        //
    // regardless of profession -- the village's "I took a thing" beat.    //
    // Wiring it to harvest pickups / chest withdrawals / ground pickups   //
    // is out of this file's scope; this only authors the clip itself.     //
    // Catalogue section 21.1.                                             //
    // ================================================================== //

    /**
     * Universal one-shot: stoop, grab, stand, tuck the item at the hip.
     *
     * <p>REBUILT 2026-08-25 to animation-quality section 2.4 + the
     * owner-critic's numeric targets ("Den ma fikses pa"). 1.40 s
     * (SettlerEntity expires pickupState at 1450 ms -- keep in step).
     * Structure and the numbers that carry it:
     * (1) counter-move 0.00-0.10: head drops to lock the item while the
     *     torso straightens and root rises +0.3 -- opposite the stoop;
     * (2) reach 0.10-0.50 into the deep stoop, hand HOVERING short of the
     *     item at x -62;
     * (3) the SNATCH 0.50-0.55: the hand darts the last stretch at
     *     35 deg/tick, LINEAR on both keys -- the grab accent;
     * (4) grab hold 0.55-0.70, 3 ticks LINEAR, all channels within 2 deg;
     * (5) rise 0.70-0.90, torso overshooting past level;
     * (6) the STOW ROLL 0.90-0.95: the wrist turns into the left-hip bag
     *     at 38 deg/tick on z (the fastest motion in the clip -- the stow
     *     is the story), LINEAR pair, head snapping its glance to the bag;
     * (7) bag-contact hold 0.95-1.10, 3 ticks LINEAR, drift <= 2.5 deg;
     * (8) release 1.10-1.30 DECELERATING (16 -> 9 deg/tick), one past-rest
     *     overshoot key at 1.25-1.30 on every major channel, then a
     *     near-still settle -- the final tick moves <= 4 deg anywhere, and
     *     every channel ends exactly on its start value, so the state
     *     expiry can never cut visible motion (the old build swung the arm
     *     ~46 deg through its last 3 ticks, which is the snap the film
     *     caught). Two beats a viewer can count: snatch... stow.
     */
    public static final AnimationDefinition PICKUP_STOW = AnimationDefinition.Builder
        .withLength(1.40F)
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(1, -2, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(18, 3, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(46, 8, 2), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(58, 10, 3), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(61, 10.5F, 3), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(59.5F, 10, 3), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(20, -1, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(8, -3.5F, -0.6F), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(0, -5, -1), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-2.5F, -5.5F, -1.2F), LINEAR),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(7, 1, 0.3F), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, -4, -2), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(2, -2, 1), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-24, -7, -3), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-48, -10, -4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-56, -11, -4.5F), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-62, -12, -5), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-97, -18, -8), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-95.5F, -17.5F, -8), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-42, 6, 6), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-36, 10, 8), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-30, 40, 46), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-28.5F, 42, 45.5F), LINEAR),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-14, 10, 12), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-3.5F, -8.5F, -6), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-6, -4, -2), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-4, 4, 2), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(2, 5, 3), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(14, 8, 6), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(20, 10, 8), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(19, 10, 8), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(0, 5, 3), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-9, 3.5F, 1.5F), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-3.2F, 4.2F, 2.2F), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-4, 4, 2), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(16, 3, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(34, 6, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(44, 8, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(43, 8, 0), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(10, -2, 0), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(3, 6, 0), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(2, 7, 0), LINEAR),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(6, 1, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-4, 0, -3), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(-1, 0, -2.5F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-28, 0, -4.8F), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-38, 0, -5.6F), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-43, 0, -6), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-42, 0, -6), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-16, 0, -4.2F), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-2.5F, 0, -2.7F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(-4.6F, 0, -3.1F), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-4, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.degreeVec(1, 0, 2.5F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(26, 0, 4.6F), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(36, 0, 5.4F), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(41, 0, 6), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(40, 0, 6), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(15, 0, 4.1F), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(2.2F, 0, 2.7F), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.degreeVec(4.5F, 0, 3.1F), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-3, 0, -1), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(24, 0, 3), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(29, 0, 4), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-4, 0, -1.5F), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(8, 0, 1.5F), CATMULLROM),
            new Keyframe(1.15F, KeyframeAnimations.degreeVec(1, 0, -0.3F), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(2.6F, 0, 0.15F), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.10F, KeyframeAnimations.posVec(0, 0.3F, -0.15F), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, -3.6F, 0.3F), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.posVec(0, -5.1F, 0.45F), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -5.7F, 0.5F), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.posVec(0, -5.5F, 0.5F), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.posVec(0, 0.7F, -0.12F), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.posVec(0, 0.25F, 0), CATMULLROM),
            new Keyframe(1.25F, KeyframeAnimations.posVec(0, -0.12F, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();

    // ================================================================== //
    // Trade idles (owner: "vil ogsa ha idle animations som matcher        //
    // jobben" -- idle animations that match the job). Fourteen self-      //
    // contained full-body loops covering all 21 employed professions      //
    // (NONE keeps the generic IDLE above). Each clip fully replaces IDLE  //
    // rather than layering on it -- SettlerEntity gates idleState off     //
    // whenever a trade idle is active, so these are never summed with     //
    // IDLE's own breath/sway (the additive hazard documented on IDLE      //
    // above). Every clip therefore authors its own breath (torso SCALE,   //
    // 1.01-1.03 on y, a separate channel from the pose lean below it) and //
    // its own weight shift -- nobody is allowed to freeze.                //
    //                                                                     //
    // Variation over time: SettlerModel offsets each clip's sampled       //
    // ageInTicks by a distinct `id % N`, the same scheme IDLE and the     //
    // CHAINS-1 craft loops already use -- valid here because every one of //
    // these fourteen is a LOOPING clip (see the file-level note on why    //
    // that offset must never touch a one-shot). Two settlers of the same  //
    // trade land at different points in the loop, and because each loop   //
    // runs several breath cycles before its one signature gesture comes   //
    // around again, a single settler reads as breathing and shifting      //
    // weight far more often than performing the trade tic -- not a tic on //
    // permanent repeat.                                                   //
    //                                                                     //
    // Every clip follows the same shape: settle into the trade's resting  //
    // pose (leaning on a tool, hands at a bench, arms empty by the belt), //
    // an accelerating anticipation into the signature gesture, a LINEAR   //
    // hold at the beat (2-4 ticks, the accent a viewer can actually see), //
    // and a decelerating release with a small overshoot back to rest.     //
    // Where two or three trades share a clip the sharing is justified in  //
    // that clip's own comment -- the instruction was "genuinely the same  //
    // motion", not "close enough". All fourteen are catalogued in         //
    // docs/ANIMATION_CATALOGUE.md section 22.                             //
    //                                                                     //
    // WHY these are built by private static methods instead of plain     //
    // field initializers (read this before adding clip #15 inline):      //
    // every `public static final AnimationDefinition X = ...` in this     //
    // class is a static field initializer, and javac compiles ALL of      //
    // them, in source order, into ONE method -- the class's <clinit>.     //
    // Adding these fourteen clips as ordinary inline initializers (the    //
    // style every clip above uses) pushed that single method's bytecode   //
    // past the JVM's hard 64KB-per-method ceiling: "error: code too       //
    // large", pointing at the class's FIRST field (IDLE), nowhere near    //
    // the clip that actually tipped it over -- a genuinely confusing      //
    // error if you don't already know <clinit> is one shared method.      //
    //                                                                     //
    // The fix: each clip below is built inside its own private static     //
    // `buildXxx()` method, which gets its OWN 64KB budget, independent of //
    // <clinit>'s. <clinit> then only holds fourteen cheap method-call      //
    // assignments (`IDLE_FARMER = buildIdleFarmer();`), not the keyframe   //
    // bulk itself.                                                        //
    //                                                                     //
    // One deliberate wrinkle: inside each `buildXxx()` method the return   //
    // value is assigned to a LOCAL variable with the SAME name as the      //
    // public field it feeds (legal Java -- the local shadows the field     //
    // only within that method body). This is not decorative:            //
    // tools/anim_check.py's parser is a regex over the raw source text     //
    // for the literal pattern `AnimationDefinition <NAME> = `             //
    // `AnimationDefinition.Builder ... .build();` -- it does not run a     //
    // real Java parser and has no idea what a method or a <clinit> is. A   //
    // bare `return AnimationDefinition.Builder...` (no name before the     //
    // assignment) is invisible to that regex, and an invisible clip is     //
    // worse than a flagged one: it silently skips every structural/craft   //
    // check (bone whitelist, tick grid, loop closure, amplitude budgets,   //
    // legs-present, cloak motion...) while still printing "PASS". Naming   //
    // the local exactly like the field is what makes the checker see the   //
    // clip at all.                                                        //
    //                                                                     //
    // So: the next new clip in this file can go back to a plain inline     //
    // field initializer UNTIL/UNLESS the class starts failing to compile   //
    // with "code too large" again -- at which point wrap that one (or a    //
    // few) in the same `buildXxx()` + same-named-local pattern rather      //
    // than reaching for something more exotic. Do not "fix" this by        //
    // renaming the local to something other than the clip's name; that     //
    // silently reopens the invisible-to-anim_check.py hole above.          //
    // ================================================================== //

    /**
     * FARMER. Both hands on the hoe shaft, weight leaning into the tool;
     * partway through the loop the settler straightens up and squints at
     * the sky (checking the weather), then eases back down onto the haft.
     * 5.5s loop.
     */
    private static AnimationDefinition buildIdleFarmer() {
            AnimationDefinition IDLE_FARMER = AnimationDefinition.Builder
            .withLength(5.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(18, -3, 2), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(20, -2, 2), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(14, 1, 1), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(7, 3, 0), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(7, 3, 0), LINEAR),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(12, 0, 1), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(18, -3, 2), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.40F, KeyframeAnimations.scaleVec(1.015, 1.025, 1.015), CATMULLROM),
                new Keyframe(2.80F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(4.20F, KeyframeAnimations.scaleVec(1.015, 1.025, 1.015), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-64, 6, 5), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-67, 5, 5), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-52, 10, 3), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-46, 13, 2), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(-46, 13, 2), LINEAR),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-56, 9, 4), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(-64, 6, 5), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-44, -8, -5), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-46, -7, -5), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-40, -6, -4), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-38, -5, -3), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(-38, -5, -3), LINEAR),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-41, -7, -4), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(-44, -8, -5), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, -4, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(12, -2, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(2, 3, 0), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-20, 6, 0), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(-22, 5, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-8, 1, 0), CATMULLROM),
                new Keyframe(4.30F, KeyframeAnimations.degreeVec(6, -3, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(10, -4, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(7, 0, 0), CATMULLROM),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(2, 0, -1), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(-4, 0, 1), LINEAR),
                new Keyframe(3.65F, KeyframeAnimations.degreeVec(-3, 0, 1), LINEAR),
                new Keyframe(4.15F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.3F, 0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.posVec(0, -0.45F, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.posVec(0, 0.1F, 0), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.posVec(0, 0.35F, 0), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.posVec(0, 0.35F, 0), LINEAR),
                new Keyframe(4.00F, KeyframeAnimations.posVec(0, 0.0F, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.posVec(0, -0.3F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(-12, 0, -5), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
            .build();
        return IDLE_FARMER;
    }

    public static final AnimationDefinition IDLE_FARMER = buildIdleFarmer();

    /**
     * LUMBERER. Axe shouldered, elbow bent, haft past the head; the left
     * hand reaches up and thumbs the blade's edge, testing it, then drops
     * back to the side. 5.0s loop.
     */
    private static AnimationDefinition buildIdleLumberer() {
            AnimationDefinition IDLE_LUMBERER = AnimationDefinition.Builder
            .withLength(5.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 8, -2), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(6, 9, -2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(3, 4, -1), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(2, 2, -1), LINEAR),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(2, 2, -1), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(4, 6, -2), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(5, 8, -2), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.scaleVec(1.02, 1.03, 1.02), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.80F, KeyframeAnimations.scaleVec(1.02, 1.03, 1.02), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-150, -20, 30), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-153, -19, 30), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-148, -18, 28), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-146, -17, 27), LINEAR),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(-146, -17, 27), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(-149, -19, 29), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-150, -20, 30), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-30, 22, -8), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-34, 24, -9), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-70, 30, -14), CATMULLROM),
                new Keyframe(2.45F, KeyframeAnimations.degreeVec(-96, 34, -18), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-99, 35, -19), LINEAR),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(-97, 35, -19), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(-58, 28, -12), CATMULLROM),
                new Keyframe(4.30F, KeyframeAnimations.degreeVec(-32, 23, -8), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-30, 22, -8), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, -8, 3), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(5, -7, 3), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(10, 4, 6), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(14, 10, 9), LINEAR),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(13, 9, 8), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(6, -8, 3), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, -1), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(5, 0, -1), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-2, 0, 1), CATMULLROM),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(-5, 0, 2), LINEAR),
                new Keyframe(3.70F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(3, 0, -1), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.35F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.15F, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.posVec(0, 0.25F, 0), LINEAR),
                new Keyframe(3.10F, KeyframeAnimations.posVec(0, 0.22F, 0), LINEAR),
                new Keyframe(3.55F, KeyframeAnimations.posVec(0, 0.0F, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-7, 0, -4), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(-10, 0, -5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-7, 0, -4), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(8, 0, 4), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM)))
            .build();
        return IDLE_LUMBERER;
    }

    public static final AnimationDefinition IDLE_LUMBERER = buildIdleLumberer();

    /**
     * GUARD, ARCHER. Weapon lowered at the side, not the readied Pflug of
     * GUARD_STANCE -- this is off-duty alertness, not a held line. One hand
     * drifts to rest at the belt while the head runs one slow deliberate
     * scan, opposite phase to GUARD_PATROL's so a guard never repeats the
     * same beat between post and patrol. The bow and the sword read the
     * same from this rig (only ItemInHandLayer distinguishes them), and
     * "hand at the belt" is the one gesture that suits a lowered blade and
     * a lowered bow equally -- GUARD_STANCE is the readied line, this is
     * the same soldier at ease. 5.5s loop.
     */
    private static AnimationDefinition buildIdleSentry() {
            AnimationDefinition IDLE_SENTRY = AnimationDefinition.Builder
            .withLength(5.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 1, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(6, 2, 0), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(4, -3, 0), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(3, -5, 0), LINEAR),
                new Keyframe(3.80F, KeyframeAnimations.degreeVec(3, -5, 0), LINEAR),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(5, 1, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.40F, KeyframeAnimations.scaleVec(1.012, 1.02, 1.012), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(4.15F, KeyframeAnimations.scaleVec(1.012, 1.02, 1.012), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-22, -6, 4), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-24, -7, 4), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-18, -3, 3), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-16, -2, 2), LINEAR),
                new Keyframe(3.80F, KeyframeAnimations.degreeVec(-16, -2, 2), LINEAR),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-20, -5, 3), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(-22, -6, 4), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-14, 10, 3), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-15, 11, 3), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-20, 16, 5), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-26, 20, 8), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-30, 22, 9), LINEAR),
                new Keyframe(3.80F, KeyframeAnimations.degreeVec(-29, 22, 9), LINEAR),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-18, 13, 4), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(-14, 10, 3), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(0.5F, 6, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(1, 8, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(0.2F, 1, 0), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(-1, -16, 0), CATMULLROM),
                new Keyframe(3.80F, KeyframeAnimations.degreeVec(-0.6F, -14, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(0.3F, 2, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(0.5F, 6, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3.5F, 0, 0.3F), CATMULLROM),
                new Keyframe(1.40F, KeyframeAnimations.degreeVec(5, 0, 1), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(1, 0, -1), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.degreeVec(-2, 0, -1.5F), LINEAR),
                new Keyframe(4.20F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(3.5F, 0, 0.3F), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0.05F, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.posVec(0, 0.25F, 0), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.posVec(0, 0.35F, 0), LINEAR),
                new Keyframe(3.80F, KeyframeAnimations.posVec(0, 0.32F, 0), LINEAR),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, 0.1F, 0), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.posVec(0, 0.05F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(11, -5, -3), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(11.5F, -5, -3.3F), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(11, -5, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 4, 3), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-8.5F, 4, 3.3F), CATMULLROM),
                new Keyframe(5.50F, KeyframeAnimations.degreeVec(-8, 4, 3), CATMULLROM)))
            .build();
        return IDLE_SENTRY;
    }

    public static final AnimationDefinition IDLE_SENTRY = buildIdleSentry();

    /**
     * COURIER. Empty-handed, relaxed; the right hand rises to check the
     * satchel strap on the shoulder, then thumbs a quick two-flick tally
     * against the fingers before dropping back. 4.5s loop.
     */
    private static AnimationDefinition buildIdleCourier() {
            AnimationDefinition IDLE_COURIER = AnimationDefinition.Builder
            .withLength(4.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 2, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(4, 3, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(2, -2, 0), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(1, -4, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(1, -4, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(3, 1, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(3, 2, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.scaleVec(1.015, 1.025, 1.015), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.scaleVec(1.015, 1.025, 1.015), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-10, -8, -4), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(-14, -10, -5), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(-58, -18, -10), CATMULLROM),
                new Keyframe(2.15F, KeyframeAnimations.degreeVec(-82, -22, -13), LINEAR),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(-79, -21, -12), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(-83, -23, -13), LINEAR),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(-79, -21, -12), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(-82, -22, -13), LINEAR),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(-46, -15, -8), CATMULLROM),
                new Keyframe(3.70F, KeyframeAnimations.degreeVec(-16, -9, -5), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-10, -8, -4), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 6, 3), CATMULLROM),
                new Keyframe(1.50F, KeyframeAnimations.degreeVec(-9, 7, 4), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(-7, 6, 3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-6, 6, 3), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 3, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(3, 5, 0), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(14, 10, 0), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(20, 12, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(19, 11, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(8, 6, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(4, 3, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-3, 0, -1), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-6, 0, -2), LINEAR),
                new Keyframe(3.60F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.posVec(0, 0.2F, 0), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.posVec(0, 0.3F, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.posVec(0, 0.28F, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.posVec(0, 0.05F, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM)))
            .build();
        return IDLE_COURIER;
    }

    public static final AnimationDefinition IDLE_COURIER = buildIdleCourier();

    /**
     * SMITH, SMELTER. Shared: both trades stand at a forge all day and the
     * shared read is hands worked raw by the heat, not which tool caused
     * it -- hammer or bellows, the hands cramp the same way. Both hands
     * flex outward, then wipe slowly down the apron front, left trailing
     * the right by roughly a quarter beat so the two wipes never mirror.
     * 5.0s loop.
     */
    private static AnimationDefinition buildIdleForge() {
            AnimationDefinition IDLE_FORGE = AnimationDefinition.Builder
            .withLength(5.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(9, 0, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(14, 3, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(17, 4, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(16, 4, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(10, 1, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.scaleVec(1.018, 1.028, 1.018), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.75F, KeyframeAnimations.scaleVec(1.018, 1.028, 1.018), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-24, -4, 6), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-28, -5, 7), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.degreeVec(-20, -2, 4), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-16, 0, 2), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(-46, -6, 8), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-52, -8, 9), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-30, -5, 6), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-24, -4, 6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-22, 4, -6), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-25, 5, -7), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-18, 3, -5), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-15, 1, -3), LINEAR),
                new Keyframe(3.05F, KeyframeAnimations.degreeVec(-44, 7, -8), LINEAR),
                new Keyframe(3.60F, KeyframeAnimations.degreeVec(-49, 8, -9), LINEAR),
                new Keyframe(4.10F, KeyframeAnimations.degreeVec(-28, 5, -6), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-22, 4, -6), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(5, 1, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(10, 3, 0), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(18, 4, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(17, 4, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(9, 1, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(0, 0, -1), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(-4, 0, -2), LINEAR),
                new Keyframe(3.70F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.1F, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.posVec(0, -0.4F, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.posVec(0, -0.5F, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(-11, 0, -5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
            .build();
        return IDLE_FORGE;
    }

    public static final AnimationDefinition IDLE_FORGE = buildIdleForge();

    /**
     * BAKER, MILLER. Shared: the miller grinds the same flour the baker
     * kneads with, and "flour on the hands" reads identically regardless
     * of which side of the sack the settler stands on. Two sharp claps
     * knock the dust off, then a slower brushing pass down the apron.
     * 4.5s loop.
     */
    private static AnimationDefinition buildIdleBaker() {
            AnimationDefinition IDLE_BAKER = AnimationDefinition.Builder
            .withLength(4.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(0.95F, KeyframeAnimations.degreeVec(7, 0, 0), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(9, 2, 0), CATMULLROM),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(11, 3, 0), LINEAR),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(9, 2, 0), LINEAR),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(11, 3, 0), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(9, 1, 0), CATMULLROM),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(7, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.scaleVec(1.015, 1.024, 1.015), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.scaleVec(1.015, 1.024, 1.015), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-18, -10, -6), CATMULLROM),
                new Keyframe(0.95F, KeyframeAnimations.degreeVec(-21, -11, -6), CATMULLROM),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(-42, -24, -12), CATMULLROM),
                new Keyframe(1.95F, KeyframeAnimations.degreeVec(-52, -30, -16), LINEAR),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(-44, -24, -12), LINEAR),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-52, -30, -16), LINEAR),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-40, -20, -10), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-30, -14, -8), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.degreeVec(-52, -16, -10), CATMULLROM),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-24, -12, -7), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-18, -10, -6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-16, 10, 6), CATMULLROM),
                new Keyframe(0.95F, KeyframeAnimations.degreeVec(-19, 11, 6), CATMULLROM),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(-40, 23, 11), CATMULLROM),
                new Keyframe(1.95F, KeyframeAnimations.degreeVec(-49, 28, 15), LINEAR),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(-42, 23, 11), LINEAR),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-49, 28, 15), LINEAR),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-38, 19, 9), CATMULLROM),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-28, 13, 7), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.degreeVec(-48, 15, 9), CATMULLROM),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-22, 11, 6), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-16, 10, 6), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(0.95F, KeyframeAnimations.degreeVec(5, 1, 0), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(16, 2, 0), CATMULLROM),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(19, 3, 0), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(16, 2, 0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.degreeVec(18, 2, 0), CATMULLROM),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(9, 1, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(-2, 0, -1), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(5, 0, 1), CATMULLROM),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(-3, 0, -1), CATMULLROM),
                new Keyframe(4.10F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(0.95F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(1.95F, KeyframeAnimations.posVec(0, 0.25F, 0), LINEAR),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.3F, 0), LINEAR),
                new Keyframe(2.75F, KeyframeAnimations.posVec(0, 0.05F, 0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM)))
            .build();
        return IDLE_BAKER;
    }

    public static final AnimationDefinition IDLE_BAKER = buildIdleBaker();

    /**
     * COOK, BREWER. Shared: both trades sample their own batch mid-shift --
     * a spoon at the pot, a ladle at the vat -- the same taste-test beat,
     * just a different vessel implied by the same hand. Lifts the tasting
     * hand to the mouth, holds, and gives one small judging nod. 4.5s loop.
     */
    private static AnimationDefinition buildIdleCook() {
            AnimationDefinition IDLE_COOK = AnimationDefinition.Builder
            .withLength(4.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, -2, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(6, -1, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(9, 3, 0), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(11, 5, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(10, 4, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, -2, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.scaleVec(1.014, 1.022, 1.014), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.scaleVec(1.014, 1.022, 1.014), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-16, -14, -6), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(-20, -16, -7), CATMULLROM),
                new Keyframe(1.80F, KeyframeAnimations.degreeVec(-72, -30, -16), CATMULLROM),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(-104, -38, -22), LINEAR),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(-108, -40, -23), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(-100, -38, -22), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-58, -26, -13), CATMULLROM),
                new Keyframe(3.85F, KeyframeAnimations.degreeVec(-22, -16, -8), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-16, -14, -6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-12, 14, 5), CATMULLROM),
                new Keyframe(1.60F, KeyframeAnimations.degreeVec(-15, 16, 6), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-13, 15, 5), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-12, 14, 5), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, -3, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(7, -2, 0), CATMULLROM),
                new Keyframe(1.80F, KeyframeAnimations.degreeVec(16, 3, 0), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(22, 5, 0), LINEAR),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(20, 5, 0), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(14, 3, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(17, 4, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(8, -3, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(1, 0, -1), CATMULLROM),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(7, 0, 1), CATMULLROM),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(2, 0, -1), CATMULLROM),
                new Keyframe(4.10F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.1F, 0.1F), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.posVec(0, -0.25F, 0.15F), CATMULLROM),
                new Keyframe(2.05F, KeyframeAnimations.posVec(0, 0.15F, 0), LINEAR),
                new Keyframe(2.35F, KeyframeAnimations.posVec(0, 0.25F, -0.05F), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.posVec(0, 0.1F, 0), CATMULLROM),
                new Keyframe(3.50F, KeyframeAnimations.posVec(0, -0.15F, 0.1F), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, -0.1F, 0.1F), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-5, 0, -2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-8, 0, -3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-5, 0, -2), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM)))
            .build();
        return IDLE_COOK;
    }

    public static final AnimationDefinition IDLE_COOK = buildIdleCook();

    /**
     * MASON, CARPENTER, SAWYER. Shared: sighting down a straight edge with
     * one eye shut is a single physical check that does not care whether
     * the edge is stone or a plank -- the arm extends flat as the
     * reference line, the head cants hard to sight along it. Three trades
     * on one clip because it is genuinely one motion, not three similar
     * ones. 5.0s loop.
     */
    private static AnimationDefinition buildIdleSightEdge() {
            AnimationDefinition IDLE_SIGHT_EDGE = AnimationDefinition.Builder
            .withLength(5.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(5, 1, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(7, -6, 0), CATMULLROM),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(8, -9, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(8, -9, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(5, -2, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.scaleVec(1.013, 1.02, 1.013), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.75F, KeyframeAnimations.scaleVec(1.013, 1.02, 1.013), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, -10, -4), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-11, -11, -5), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.degreeVec(-40, -40, -18), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-58, -58, -26), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(-62, -62, -28), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-60, -61, -27), LINEAR),
                new Keyframe(3.75F, KeyframeAnimations.degreeVec(-34, -36, -16), CATMULLROM),
                new Keyframe(4.40F, KeyframeAnimations.degreeVec(-12, -12, -5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-8, -10, -4), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-9, 9, 3), CATMULLROM),
                new Keyframe(1.60F, KeyframeAnimations.degreeVec(-12, 11, 4), CATMULLROM),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-10, 10, 3), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-9, 9, 3), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 4, 2), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(2, 5, 2), CATMULLROM),
                new Keyframe(1.90F, KeyframeAnimations.degreeVec(0, 20, 10), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-2, 32, 16), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-2, 31, 16), LINEAR),
                new Keyframe(3.75F, KeyframeAnimations.degreeVec(0, 16, 8), CATMULLROM),
                new Keyframe(4.40F, KeyframeAnimations.degreeVec(2, 6, 3), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(2, 4, 2), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-3, 0, -2), CATMULLROM),
                new Keyframe(2.80F, KeyframeAnimations.degreeVec(-5, 0, -3), LINEAR),
                new Keyframe(3.60F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(4.40F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.2F, 0), CATMULLROM),
                new Keyframe(2.55F, KeyframeAnimations.posVec(0, 0.3F, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.posVec(0, 0.28F, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.posVec(0, 0.05F, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-10, 0, -4), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
            .build();
        return IDLE_SIGHT_EDGE;
    }

    public static final AnimationDefinition IDLE_SIGHT_EDGE = buildIdleSightEdge();

    /**
     * FLETCHER. Hands close at the chest holding an arrow shaft; the right
     * gives it two quick opposite-direction twirls between finger and
     * thumb, then settles. The smallest, fastest gesture in the set --
     * fine work reads through a flick, not a sweep. 4.0s loop.
     */
    private static AnimationDefinition buildIdleFletcher() {
            AnimationDefinition IDLE_FLETCHER = AnimationDefinition.Builder
            .withLength(4.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(7, 4, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(8, 5, 0), CATMULLROM),
                new Keyframe(1.70F, KeyframeAnimations.degreeVec(7, 3, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(6, 2, 0), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(7, 3, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(7, 4, 0), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(7, 4, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.05F, KeyframeAnimations.scaleVec(1.012, 1.02, 1.012), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.10F, KeyframeAnimations.scaleVec(1.012, 1.02, 1.012), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-60, -16, 4), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(-62, -15, 5), CATMULLROM),
                new Keyframe(1.65F, KeyframeAnimations.degreeVec(-58, -13, 3), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(-56, -11, 24), LINEAR),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-58, -13, -14), LINEAR),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-57, -12, 6), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-59, -14, 4), CATMULLROM),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-60, -16, 4), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-60, -16, 4), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-70, 18, -8), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-72, 19, -8), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-71, 18, -8), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-70, 18, -8), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(16, 10, 6), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(17, 11, 6), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.degreeVec(15, 9, 10), LINEAR),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(16, 10, 2), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(16, 10, 7), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(16, 10, 6), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(1.85F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.posVec(0, -0.12F, 0), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-5, 0, -3), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-5, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
            .build();
        return IDLE_FLETCHER;
    }

    public static final AnimationDefinition IDLE_FLETCHER = buildIdleFletcher();

    /**
     * MINER. Pick head rested on the ground, right hand planted on the
     * haft; the left hand finds the small of the back, the torso arches
     * and the head tips back in one long stretch, then eases forward
     * again. 5.0s loop.
     */
    private static AnimationDefinition buildIdleMiner() {
            AnimationDefinition IDLE_MINER = AnimationDefinition.Builder
            .withLength(5.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 2, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(7, 3, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(10, 1, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-4, -2, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-6, -3, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(6, 2, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.scaleVec(1.016, 1.026, 1.016), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.75F, KeyframeAnimations.scaleVec(1.016, 1.026, 1.016), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-30, -6, 6), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-33, -7, 6), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-29, -5, 5), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-27, -4, 5), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-27, -4, 5), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-30, -6, 6), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-30, -6, 6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 10, 4), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-10, 11, 5), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-20, 20, 10), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(-38, 32, 20), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-42, 34, 22), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-40, 33, 21), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-20, 20, 10), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-10, 12, 5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-8, 10, 4), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(7, 1, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(10, -1, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-10, 2, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(-12, 2, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-1, 0, -1), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-6, 0, -2), LINEAR),
                new Keyframe(3.60F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
                new Keyframe(4.30F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.5F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, -0.3F, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.posVec(0, 0.35F, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.posVec(0, 0.4F, 0), LINEAR),
                new Keyframe(3.90F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.posVec(0, -0.4F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(-12, 0, -5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM)))
            .build();
        return IDLE_MINER;
    }

    public static final AnimationDefinition IDLE_MINER = buildIdleMiner();

    /**
     * SCHOLAR. A book cradled in the right hand; the left thumbs a page, a
     * quick flick down and up, and the head dips into a small confirming
     * nod as if the line just read settled something. 4.5s loop.
     */
    private static AnimationDefinition buildIdleScholar() {
            AnimationDefinition IDLE_SCHOLAR = AnimationDefinition.Builder
            .withLength(4.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, -3, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(11, -2, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(13, 1, 0), LINEAR),
                new Keyframe(2.80F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(10, -2, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(10, -3, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.scaleVec(1.011, 1.018, 1.011), CATMULLROM),
                new Keyframe(2.25F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.35F, KeyframeAnimations.scaleVec(1.011, 1.018, 1.011), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-64, -20, 10), CATMULLROM),
                new Keyframe(1.60F, KeyframeAnimations.degreeVec(-66, -21, 10), CATMULLROM),
                new Keyframe(3.10F, KeyframeAnimations.degreeVec(-65, -20, 10), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-64, -20, 10), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-60, 20, -9), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-61, 21, -9), CATMULLROM),
                new Keyframe(1.95F, KeyframeAnimations.degreeVec(-56, 17, -6), CATMULLROM),
                new Keyframe(2.15F, KeyframeAnimations.degreeVec(-70, 26, -13), LINEAR),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-58, 19, -7), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(-63, 21, -9), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-61, 20, -9), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-60, 20, -9), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(20, 4, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(21, 4, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(18, 3, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(24, 3, 0), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(17, 3, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(20, 4, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(20, 4, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.22F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, -0.1F, 0), CATMULLROM),
                new Keyframe(3.30F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(6, 0, 2), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM)))
            .build();
        return IDLE_SCHOLAR;
    }

    public static final AnimationDefinition IDLE_SCHOLAR = buildIdleScholar();

    /**
     * INNKEEPER. A mug held at the waist while the other hand polishes it
     * in a slow circular pass with the apron hem, then lifts it to check
     * the shine before settling back into the polish. 4.5s loop.
     */
    private static AnimationDefinition buildIdleInnkeeper() {
            AnimationDefinition IDLE_INNKEEPER = AnimationDefinition.Builder
            .withLength(4.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 3, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(6, 4, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(7, -1, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(8, -3, 0), LINEAR),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(7, -2, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(6, 2, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(5, 3, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.scaleVec(1.013, 1.021, 1.013), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.40F, KeyframeAnimations.scaleVec(1.013, 1.021, 1.013), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-20, -12, -5), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.degreeVec(-23, -13, -6), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(-38, -16, -8), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-46, -18, -9), LINEAR),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-42, -17, -8), LINEAR),
                new Keyframe(3.40F, KeyframeAnimations.degreeVec(-26, -13, -6), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-20, -12, -5), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-16, 14, 6), CATMULLROM),
                new Keyframe(0.60F, KeyframeAnimations.degreeVec(-24, 18, 10), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.degreeVec(-14, 20, 4), CATMULLROM),
                new Keyframe(1.70F, KeyframeAnimations.degreeVec(-22, 14, 10), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-16, 20, 4), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-18, 16, 6), CATMULLROM),
                new Keyframe(3.55F, KeyframeAnimations.degreeVec(-16, 14, 6), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-16, 14, 6), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 2, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(9, 3, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(13, 4, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(17, 5, 0), LINEAR),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(11, 3, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(14, 4, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(10, 2, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(1.15F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(1, 0, -1), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(5, 0, 1), CATMULLROM),
                new Keyframe(3.70F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.15F, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.posVec(0, 0.2F, 0), LINEAR),
                new Keyframe(2.90F, KeyframeAnimations.posVec(0, 0.1F, 0), CATMULLROM),
                new Keyframe(3.50F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-5, 0, -2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-6, 0, -2), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-5, 0, -2), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(5, 0, 2), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM)))
            .build();
        return IDLE_INNKEEPER;
    }

    public static final AnimationDefinition IDLE_INNKEEPER = buildIdleInnkeeper();

    /**
     * WEAVER. Rolls a length of thread between finger and thumb, testing
     * its twist with two small opposite rolls, then lifts it to sight the
     * spin against the light before returning to the roll. 4.0s loop.
     */
    private static AnimationDefinition buildIdleWeaver() {
            AnimationDefinition IDLE_WEAVER = AnimationDefinition.Builder
            .withLength(4.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 5, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(9, 5, 0), CATMULLROM),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(8, 3, 0), CATMULLROM),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(6, 1, 0), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(7, 2, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(8, 5, 0), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(8, 5, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.scaleVec(1.011, 1.018, 1.011), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.scaleVec(1.011, 1.018, 1.011), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-72, -14, 6), CATMULLROM),
                new Keyframe(0.85F, KeyframeAnimations.degreeVec(-74, -13, 6), CATMULLROM),
                new Keyframe(1.55F, KeyframeAnimations.degreeVec(-71, -15, 16), LINEAR),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(-73, -13, -4), LINEAR),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-64, -10, 10), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(-63, -9, 10), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-72, -14, 6), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-72, -14, 6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-66, 16, -8), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(-68, 17, -8), CATMULLROM),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(-62, 14, -6), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-66, 16, -8), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-66, 16, -8), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(18, 8, 4), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.degreeVec(19, 8, 4), CATMULLROM),
                new Keyframe(1.75F, KeyframeAnimations.degreeVec(17, 7, 4), CATMULLROM),
                new Keyframe(2.05F, KeyframeAnimations.degreeVec(10, 6, 3), LINEAR),
                new Keyframe(2.55F, KeyframeAnimations.degreeVec(9, 6, 3), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(18, 8, 4), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(18, 8, 4), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(1.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(1, 0, 0), CATMULLROM),
                new Keyframe(3.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(0.90F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.posVec(0, -0.15F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-5, 0, -3), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(-5, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM),
                new Keyframe(2.00F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM),
                new Keyframe(4.00F, KeyframeAnimations.degreeVec(4, 0, 3), CATMULLROM)))
            .build();
        return IDLE_WEAVER;
    }

    public static final AnimationDefinition IDLE_WEAVER = buildIdleWeaver();

    /**
     * BUTCHER, TANNER. Shared: both trades work a bench with a blade and
     * empty hands otherwise -- the shared read is testing the edge, not
     * the specific cut. Wipes the knife down the apron in one long stroke,
     * then taps the flat twice against the bench to test it's true. 5.0s
     * loop.
     */
    private static AnimationDefinition buildIdleBladeBench() {
            AnimationDefinition IDLE_BLADE_BENCH = AnimationDefinition.Builder
            .withLength(5.00F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(9, -4, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(10, -3, 0), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(13, 1, 0), CATMULLROM),
                new Keyframe(2.45F, KeyframeAnimations.degreeVec(15, 3, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.degreeVec(12, 2, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(10, -1, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(9, -4, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.scaleVec(1.014, 1.023, 1.014), CATMULLROM),
                new Keyframe(2.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.75F, KeyframeAnimations.scaleVec(1.014, 1.023, 1.014), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-26, -10, -6), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-29, -11, -6), CATMULLROM),
                new Keyframe(1.95F, KeyframeAnimations.degreeVec(-52, -16, -9), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.degreeVec(-70, -20, -12), LINEAR),
                new Keyframe(2.45F, KeyframeAnimations.degreeVec(-74, -21, -13), LINEAR),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-58, -16, -10), LINEAR),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(-62, -17, -11), LINEAR),
                new Keyframe(3.15F, KeyframeAnimations.degreeVec(-40, -13, -8), CATMULLROM),
                new Keyframe(3.90F, KeyframeAnimations.degreeVec(-30, -11, -7), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-26, -10, -6), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-24, 10, 6), CATMULLROM),
                new Keyframe(1.60F, KeyframeAnimations.degreeVec(-27, 11, 7), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-25, 10, 6), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-24, 10, 6), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, -2, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(9, -1, 0), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(16, 1, 0), CATMULLROM),
                new Keyframe(2.45F, KeyframeAnimations.degreeVec(21, 2, 0), LINEAR),
                new Keyframe(2.75F, KeyframeAnimations.degreeVec(19, 2, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(10, -2, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(1.25F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(0, 0, -1), CATMULLROM),
                new Keyframe(2.90F, KeyframeAnimations.degreeVec(-5, 0, -2), LINEAR),
                new Keyframe(3.60F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(4.30F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.1F, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.posVec(0, -0.35F, 0), LINEAR),
                new Keyframe(2.85F, KeyframeAnimations.posVec(0, -0.4F, 0), LINEAR),
                new Keyframe(3.50F, KeyframeAnimations.posVec(0, -0.05F, 0), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(-11, 0, -5), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
                new Keyframe(2.40F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM),
                new Keyframe(5.00F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
            .build();
        return IDLE_BLADE_BENCH;
    }

    public static final AnimationDefinition IDLE_BLADE_BENCH = buildIdleBladeBench();

    /**
     * FISHER. The rod rests loosely across the shoulder in the right hand,
     * held about as still as HUNTER_LOOSE's off-hand is -- a fisher's whole
     * manner is patience. Partway through the loop the free hand rises to
     * shade the eyes and the head tips out and down, scanning the water for
     * a moment, then both ease back to rest. 4.50s loop. Catalogue 24.4.
     */
    private static AnimationDefinition buildIdleFisher() {
            AnimationDefinition IDLE_FISHER = AnimationDefinition.Builder
            .withLength(4.50F).looping()
            .addAnimation("torso", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 6, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(7, 7, 0), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(5, 3, 0), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(3, -2, 0), LINEAR),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(3, -2, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(5, 4, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(6, 6, 0), CATMULLROM)))
            .addAnimation("torso", new AnimationChannel(SCALE,
                new Keyframe(0.00F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(1.20F, KeyframeAnimations.scaleVec(1.02, 1.03, 1.02), CATMULLROM),
                new Keyframe(2.25F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM),
                new Keyframe(3.35F, KeyframeAnimations.scaleVec(1.02, 1.03, 1.02), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.scaleVec(1.0, 1.0, 1.0), CATMULLROM)))
            .addAnimation("right_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-140, -15, 25), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-142, -14, 24), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-138, -16, 26), CATMULLROM),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(-136, -17, 26), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-136, -17, 26), LINEAR),
                new Keyframe(3.85F, KeyframeAnimations.degreeVec(-139, -16, 25), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-140, -15, 25), CATMULLROM)))
            .addAnimation("left_arm", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-15, 10, -5), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(-18, 12, -6), CATMULLROM),
                new Keyframe(2.10F, KeyframeAnimations.degreeVec(-45, 20, -10), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(-70, 26, -14), LINEAR),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(-72, 27, -14), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-50, 22, -11), CATMULLROM),
                new Keyframe(3.85F, KeyframeAnimations.degreeVec(-20, 13, -6), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-15, 10, -5), CATMULLROM)))
            .addAnimation("head", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, -10, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.degreeVec(9, -6, 0), CATMULLROM),
                new Keyframe(1.80F, KeyframeAnimations.degreeVec(11, 8, 0), CATMULLROM),
                new Keyframe(2.35F, KeyframeAnimations.degreeVec(18, 14, 0), LINEAR),
                new Keyframe(2.70F, KeyframeAnimations.degreeVec(17, 13, 0), LINEAR),
                new Keyframe(3.30F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
                new Keyframe(3.85F, KeyframeAnimations.degreeVec(10, -9, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(10, -10, 0), CATMULLROM)))
            .addAnimation("cloak", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM),
                new Keyframe(1.30F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
                new Keyframe(2.60F, KeyframeAnimations.degreeVec(-3, 0, 0), CATMULLROM),
                new Keyframe(3.20F, KeyframeAnimations.degreeVec(-6, 0, 0), LINEAR),
                new Keyframe(3.80F, KeyframeAnimations.degreeVec(0, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(2, 0, 0), CATMULLROM)))
            .addAnimation("root", new AnimationChannel(POSITION,
                new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM),
                new Keyframe(1.10F, KeyframeAnimations.posVec(0, -0.35F, 0), CATMULLROM),
                new Keyframe(2.20F, KeyframeAnimations.posVec(0, 0.15F, 0), CATMULLROM),
                new Keyframe(2.70F, KeyframeAnimations.posVec(0, 0.22F, 0), LINEAR),
                new Keyframe(3.20F, KeyframeAnimations.posVec(0, 0.18F, 0), LINEAR),
                new Keyframe(3.85F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.posVec(0, -0.2F, 0), CATMULLROM)))
            .addAnimation("right_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
            .addAnimation("left_leg", new AnimationChannel(ROTATION,
                new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM),
                new Keyframe(2.30F, KeyframeAnimations.degreeVec(7, 0, 3), CATMULLROM),
                new Keyframe(4.50F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM)))
            .build();
        return IDLE_FISHER;
    }

    public static final AnimationDefinition IDLE_FISHER = buildIdleFisher();

}
