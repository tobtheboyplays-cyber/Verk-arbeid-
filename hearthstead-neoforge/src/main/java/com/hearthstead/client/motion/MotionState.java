package com.hearthstead.client.motion;

/**
 * Per-entity, render-side motion memory. Bounded and self-resetting: any
 * frame gap, teleport or reload drops it back to rest, so it can never
 * accumulate across time or leak into another entity.
 */
public final class MotionState {
    // Frame clock.
    float lastAge = Float.NaN;
    float dt;
    boolean newFrame;

    // Real ground distance for stride-matched cadence.
    double lastX = Double.NaN;
    double lastZ = Double.NaN;
    float frameDistance;
    /** Gait phase in cycles; advanced by distance / stride so the planted foot does not slide. */
    float gaitPhase;
    float speedBlocksPerSecond;

    // Turn banking and acceleration lean.
    float lastBodyYaw = Float.NaN;
    float yawRate;          // degrees / second, filtered
    float lastSpeed;
    float accel;            // blocks / s^2, filtered

    // Secondary springs.
    final Spring bagPitch = new Spring();
    final Spring bagRoll = new Spring();
    final Spring brimPitch = new Spring();
    final Spring brimRoll = new Spring();
    final Spring toolPitch = new Spring();
    float lastTorsoPitch = Float.NaN;
    float lastTorsoYaw = Float.NaN;
    float lastHeadPitch = Float.NaN;
    float lastHeadYaw = Float.NaN;
    float lastArmPitch = Float.NaN;
    // Overkill secondary: cloak sway, footfall bounce, impact shake.
    final Spring cloakPitch = new Spring();
    final Spring cloakRoll = new Spring();
    /** Sack drop on each footfall, model px (+ = down). */
    final Spring bagBounce = new Spring();
    /** Short high-frequency tool shake after a contact, radians about the palm. */
    final Spring toolShake = new Spring();
    /** Tiny head nod after a contact (hair/hat jiggle rides on it and the brim springs). */
    final Spring headJolt = new Spring();
    /** Knee give on a heavy landing, radians. */
    final Spring landing = new Spring();
    /** Pending impact (0..2), set by contact cues and consumed by secondary(). */
    float pendingImpact;
    /** Footfalls crossed this frame by the distance-clocked gait (0, 1 or 2). */
    int pendingFootfalls;
    /** Which foot struck last (0 = right, 1 = left) for heavy-step dust. */
    int lastFoot;
    /** 0..1 exertion: rises with running, hauling and blows, fades over ~15 s. */
    float exertion;
    /** Integrated breathing phase (radians), so a rate change never jumps the pose. */
    float breathPhase = Float.NaN;
    // Vertical motion for landings.
    double lastY = Double.NaN;
    float fallSpeed;
    boolean wasOnGround = true;
    // Turn-in-place stepping.
    float turnStepPhase;
    float turnStepWeight;
    float turnStepSign = 1.0F;
    /** Gait layer weight this frame (0 standing .. 1 full stride), for hip sway. */
    float gaitWeight;
    /** Arms swing out of a turn (radians, + = right). */
    final Spring armTurn = new Spring();
    /** Glance at a nearby player: 0..1 blend, and the smoothed look angles. */
    float glance;
    float glanceYaw;
    float glancePitch;

    // Ownership snapshot of the gait layer (limbs untouched after walk()).
    final float[] gaitSnapshot = new float[4 * 3];
    boolean gaitApplied;
    final float[] gaitElbow = new float[2];
    final float[] gaitKnee = new float[2];

    int libraryGeneration = -1;

    /** Variant cursor per played definition (identity keyed; created on first use). */
    static final class Cursor {
        MotionClip clip;
        float start;
        int counter = -1;
        float lastSeconds = Float.NaN;
    }

    java.util.IdentityHashMap<Object, Cursor> cursors;

    /** Last sampled local time per definition, for timeline sound cues. */
    static final class SoundCursor {
        MotionClip clip;
        float local = Float.NaN;
    }

    java.util.IdentityHashMap<Object, SoundCursor> soundCursors;

    SoundCursor soundCursor(Object key) {
        if (soundCursors == null) {
            soundCursors = new java.util.IdentityHashMap<>();
        }
        return soundCursors.computeIfAbsent(key, k -> new SoundCursor());
    }

    /** Display-only hand props requested by the clips sampled this frame. */
    public MotionProp propMain;
    public MotionProp propOff;

    /** Personal carriage (from synced appearance seed, energy and morale). */
    float strideScale = 1.0F;

    Cursor cursor(Object key) {
        if (cursors == null) {
            cursors = new java.util.IdentityHashMap<>();
        }
        Cursor c = cursors.get(key);
        if (c == null) {
            c = new Cursor();
            cursors.put(key, c);
        }
        return c;
    }

    void resetDynamics() {
        if (cursors != null) {
            cursors.clear();
        }
        bagPitch.reset();
        bagRoll.reset();
        brimPitch.reset();
        brimRoll.reset();
        toolPitch.reset();
        cloakPitch.reset();
        cloakRoll.reset();
        bagBounce.reset();
        toolShake.reset();
        headJolt.reset();
        landing.reset();
        pendingImpact = 0.0F;
        pendingFootfalls = 0;
        lastY = Double.NaN;
        fallSpeed = 0.0F;
        wasOnGround = true;
        lastTorsoPitch = lastTorsoYaw = lastHeadPitch = lastHeadYaw = lastArmPitch = Float.NaN;
        yawRate = 0.0F;
        accel = 0.0F;
    }
}
