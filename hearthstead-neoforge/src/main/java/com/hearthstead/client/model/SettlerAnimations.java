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
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(18, -6, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(38, 4, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(33, 3, 0), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(30, 2, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(20, -2, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(16, -6, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(24, -5, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-80, 10, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-96, 12, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-140, 8, -6), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-32, -6, 0), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-30, -7, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-46, -12, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-76, -15, 0), CATMULLROM),
            new Keyframe(1.5F, KeyframeAnimations.degreeVec(-68, 8, 0), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-42, -10, 0), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-58, -14, 0), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-22, -8, 0), LINEAR),
            new Keyframe(0.7F, KeyframeAnimations.degreeVec(-20, -9, 0), LINEAR),
            new Keyframe(0.9F, KeyframeAnimations.degreeVec(-30, -11, 0), CATMULLROM),
            new Keyframe(1.1F, KeyframeAnimations.degreeVec(-50, -16, 0), CATMULLROM),
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
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
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
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-52, -10, -5), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-152, -8, -6), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-140, -8, -6), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-38, -10, -4), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-36, -10, -4), LINEAR),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-14, -9, -4), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-22, -10, -4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-30, 14, 5), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-58, 14, 6), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-148, 12, 7), CATMULLROM),
            new Keyframe(0.5F, KeyframeAnimations.degreeVec(-136, 12, 7), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-44, 14, 5), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-42, 14, 5), LINEAR),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-20, 15, 5), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(-30, 14, 5), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, -3, 0), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-14, -7, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-16, -9, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(23, 5, 0), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(18, 3, 0), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(16, 2, 0), LINEAR),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(0, -4, 0), CATMULLROM),
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
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-16, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-14, 0, 0), CATMULLROM),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
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
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(38, 2, 0), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(34, 4, 0), CATMULLROM),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(18, 15, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(40, 3, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(36, 5, 0), CATMULLROM),
            new Keyframe(1.15F, KeyframeAnimations.degreeVec(18, 15, 0), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(26, 10, 0), CATMULLROM)))
        .addAnimation("right_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-74, 24, -10), CATMULLROM),
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-88, 26, -13), CATMULLROM),
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-104, 28, -16), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-48, 10, -4), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-46, 10, -4), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(-80, 26, -12), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-106, 30, -18), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-50, 12, -4), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-48, 12, -4), LINEAR),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-84, 26, -11), CATMULLROM),
            new Keyframe(1.3F, KeyframeAnimations.degreeVec(-74, 24, -10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM),
            new Keyframe(0.3F, KeyframeAnimations.degreeVec(-44, -4, 4), LINEAR),
            new Keyframe(0.4F, KeyframeAnimations.degreeVec(-42, -4, 4), LINEAR),
            new Keyframe(0.6F, KeyframeAnimations.degreeVec(-66, -8, 8), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-44, -4, 4), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-42, -4, 4), LINEAR),
            new Keyframe(1.2F, KeyframeAnimations.degreeVec(-72, -10, 9), CATMULLROM),
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
            new Keyframe(0.2F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.8F, KeyframeAnimations.degreeVec(-4, 0, 0), CATMULLROM),
            new Keyframe(1.0F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
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
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-40, -18, 8), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-74, -14, 5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-72, -14, 5), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-46, -17, 7), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-58, -16, 6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-52, 18, -6), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-72, 15, -5), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-44, 19, -8), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-46, 19, -8), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-66, 16, -5), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-52, 18, -6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(20, 4, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(24, -3, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(23, -3, 0), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(15, 3, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(20, -3, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(-6, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(5, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(12, 0, 0), CATMULLROM),
            new Keyframe(1.20F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.posVec(0, -0.6F, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.posVec(0, -0.55F, 0), LINEAR),
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
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-64, -12, -7), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-118, -10, -8), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-114, -10, -8), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-52, -13, -5), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-50, -13, -5), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-34, -12, -6), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-46, -12, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-68, 22, -4), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-72, 22, -4), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-70, 23, -4), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-68, 22, -4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(12, 6, 0), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(2, 12, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(22, -4, 0), LINEAR),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(20, -3, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(8, 8, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(12, 6, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(18, 2, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(14, 3, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(24, 0, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(18, 2, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-8, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(7, 0, 4), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(7, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-8, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(11, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.posVec(0, 0.25F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.5F, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.45F, 0), LINEAR),
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
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-30, -15, 5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-86, -10, 2), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-84, -10, 2), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-24, -16, 6), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-38, -14, 4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-38, 14, -4), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-30, 15, -5), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-86, 10, -2), CATMULLROM),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-84, 10, -2), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-24, 16, -6), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-38, 14, -4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(26, 0, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(24, 0, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-9, 0, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-6, 6, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-9, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(9, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-7, 0, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.posVec(0, -0.5F, 0.3F), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -0.45F, 0.3F), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.posVec(0, 0.2F, -0.35F), CATMULLROM),
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
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-62, -10, -6), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-158, -8, -7), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-152, -8, -7), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-56, -11, -4), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-54, -11, -4), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-22, -10, -5), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-46, -10, -5), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-40, -10, -5), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-78, 20, -3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-82, 20, -3), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-80, 21, -3), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-78, 20, -3), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 4, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-6, 12, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-14, 14, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(28, -6, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(25, -5, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(4, 8, 0), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(10, 4, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(14, 2, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(27, 0, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(25, 0, 0), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-11, 0, -5), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-15, 0, -5), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-13, 0, -5), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-11, 0, -5), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(9, 0, 5), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-19, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(17, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(7, 0, 0), CATMULLROM),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.posVec(0, 0.55F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.9F, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.8F, 0), LINEAR),
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
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-82, -6, -3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-80, -6, -3), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-28, -9, -2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-30, -9, -2), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-34, -8, -2), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-52, 10, 2), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.degreeVec(-96, 8, 3), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-94, 8, 3), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-46, 11, 2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-48, 11, 2), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-52, 10, 2), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(16, -4, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(26, 3, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(25, 3, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(11, -7, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(12, -7, 0), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(16, -4, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-16, 0, -4), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-12, 0, -4), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(13, 0, 4), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(10, 0, 4), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(13, 0, 0), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-2, 0, 0), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.posVec(0, -0.35F, 0.35F), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -0.1F, -0.3F), CATMULLROM),
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
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-84, -17, 14), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-72, -23, 9), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-74, -22, 10), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-78, 21, -9), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-70, 25, -7), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-81, 19, -11), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-72, 24, -8), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-78, 21, -9), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(21, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(23, 2, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(21, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(27, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(29, -3, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(28, 3, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(27, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(-4, 0, -2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-4, 0, -2), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(4, 0, 2), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.00F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(8, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.00F, KeyframeAnimations.posVec(0, -0.35F, 0), CATMULLROM),
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
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-44, -15, 5), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-96, -9, 2), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-94, -9, 2), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-88, -8, 46), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-86, -8, 44), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-40, -16, -6), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-56, -14, 6), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-52, -14, 4), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-52, 14, -4), CATMULLROM),
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-44, 15, -5), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(-96, 9, -2), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-94, 9, -2), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-88, 8, -46), LINEAR),
            new Keyframe(0.90F, KeyframeAnimations.degreeVec(-86, 8, -44), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-40, 16, 6), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(-56, 14, -6), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-52, 14, -4), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(24, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(26, 0, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(24, 0, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-10, 0, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(10, 0, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(20, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-4, 5, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(14, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-11, 0, -3), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(-7, 0, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(6, 0, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(15, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-9, 0, 0), CATMULLROM),
            new Keyframe(1.60F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.4F, 0.45F), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.posVec(0, -0.35F, 0.45F), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.posVec(0, 0.15F, -0.4F), CATMULLROM),
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
            new Keyframe(0.25F, KeyframeAnimations.degreeVec(-34, 40, 30), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-30, 44, 33), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-58, -34, -18), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(-56, -36, -19), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-40, -46, -26), CATMULLROM),
            new Keyframe(1.10F, KeyframeAnimations.degreeVec(-10, 8, 10), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-16, 28, 22), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-64, 26, -14), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-68, 28, -15), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-66, 27, -15), LINEAR),
            new Keyframe(1.00F, KeyframeAnimations.degreeVec(-62, 25, -13), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-64, 26, -14), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(6, 10, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(4, 22, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(3, 24, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(8, -18, 0), LINEAR),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(7, -17, 0), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(5, -6, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(6, 10, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 12, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(12, 20, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(14, -14, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(10, 12, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-5, 4, -3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-9, -6, -4), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(-5, 4, -3), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(5, -4, 3), CATMULLROM),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(8, 6, 4), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(5, -4, 3), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-6, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(16, 0, 0), CATMULLROM),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(5, 0, 0), CATMULLROM),
            new Keyframe(1.40F, KeyframeAnimations.degreeVec(3, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(-0.3F, 0.1F, 0), CATMULLROM),
            new Keyframe(0.65F, KeyframeAnimations.posVec(0.35F, -0.15F, 0), CATMULLROM),
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
            new Keyframe(0.15F, KeyframeAnimations.degreeVec(-66, -11, -7), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-136, -9, -8), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-132, -9, -8), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-48, -13, -5), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-46, -13, -5), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(-18, -12, -6), CATMULLROM),
            new Keyframe(0.85F, KeyframeAnimations.degreeVec(-44, -12, -6), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-38, -12, -6), CATMULLROM)))
        .addAnimation("left_arm", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-46, 16, 6), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-128, 12, 8), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(-124, 12, 8), LINEAR),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-54, 17, 5), LINEAR),
            new Keyframe(0.65F, KeyframeAnimations.degreeVec(-52, 17, 5), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(-30, 16, 6), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-46, 16, 6), CATMULLROM)))
        .addAnimation("torso", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(14, 3, 0), CATMULLROM),
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(-2, 10, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-8, 12, 0), CATMULLROM),
            new Keyframe(0.40F, KeyframeAnimations.degreeVec(30, -5, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(27, -4, 0), LINEAR),
            new Keyframe(0.75F, KeyframeAnimations.degreeVec(6, 7, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(14, 3, 0), CATMULLROM)))
        .addAnimation("head", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(16, 2, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(29, 0, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(27, 0, 0), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(22, 0, 0), CATMULLROM)))
        .addAnimation("right_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(-13, 0, -5), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-17, 0, -5), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.degreeVec(-15, 0, -5), LINEAR),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(-13, 0, -5), CATMULLROM)))
        .addAnimation("left_leg", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(10, 0, 5), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(10, 0, 5), CATMULLROM)))
        .addAnimation("cloak", new AnimationChannel(ROTATION,
            new Keyframe(0.0F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-15, 0, 0), CATMULLROM),
            new Keyframe(0.50F, KeyframeAnimations.degreeVec(15, 0, 0), CATMULLROM),
            new Keyframe(0.70F, KeyframeAnimations.degreeVec(6, 0, 0), CATMULLROM),
            new Keyframe(0.95F, KeyframeAnimations.degreeVec(4, 0, 0), CATMULLROM)))
        .addAnimation("root", new AnimationChannel(POSITION,
            new Keyframe(0.0F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.posVec(0, 0.45F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, -0.75F, 0.25F), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -0.7F, 0.25F), LINEAR),
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
            new Keyframe(0.20F, KeyframeAnimations.degreeVec(34, 8, 0), CATMULLROM),
            new Keyframe(0.30F, KeyframeAnimations.degreeVec(-16, 4, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.degreeVec(-22, 0, 0), CATMULLROM),
            new Keyframe(0.55F, KeyframeAnimations.degreeVec(40, -4, 0), LINEAR),
            new Keyframe(0.80F, KeyframeAnimations.degreeVec(36, -3, 0), LINEAR),
            new Keyframe(1.05F, KeyframeAnimations.degreeVec(-4, 2, 0), CATMULLROM),
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
            new Keyframe(0.20F, KeyframeAnimations.posVec(0, -2.6F, 0), CATMULLROM),
            new Keyframe(0.35F, KeyframeAnimations.posVec(0, 3.4F, 0), CATMULLROM),
            new Keyframe(0.45F, KeyframeAnimations.posVec(0, 3.8F, 0), LINEAR),
            new Keyframe(0.50F, KeyframeAnimations.posVec(0, 3.6F, 0), LINEAR),
            new Keyframe(0.60F, KeyframeAnimations.posVec(0, -2.2F, 0), LINEAR),
            new Keyframe(0.85F, KeyframeAnimations.posVec(0, -2.0F, 0), LINEAR),
            new Keyframe(1.10F, KeyframeAnimations.posVec(0, 0.3F, 0), CATMULLROM),
            new Keyframe(1.30F, KeyframeAnimations.posVec(0, 0, 0), CATMULLROM)))
        .build();
}
