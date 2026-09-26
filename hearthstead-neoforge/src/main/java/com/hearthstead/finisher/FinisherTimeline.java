package com.hearthstead.finisher;

/**
 * The one clock every viewer plays an execution on: real time at 20 Hz, no
 * dilation, no per-player camera. The only liberty is the shared hit-stop:
 * for {@link #HIT_STOP_TICKS} ticks after the impact, actor and victim hold
 * the contact frame, then resume.
 *
 * <p>The hold is BAKED INTO THE AUTHORED CLIPS (Blender export samples the
 * continuous "move" at {@link #clipTicks} for every real frame), so every
 * runtime simply plays the clip linearly from the start packet and all
 * viewers see the identical freeze. A variant's move is authored over
 * {@code lengthTicks}; its exported clip lasts {@code lengthTicks +
 * HIT_STOP_TICKS}; the server kills the victim at {@code start + impactTick}
 * (the first frozen frame).</p>
 */
public final class FinisherTimeline {
    /** Frozen contact pose, in ticks. The owner asked for a 2-3 tick hold. */
    public static final int HIT_STOP_TICKS = 3;
    public static final float TICKS_PER_SECOND = 20.0F;

    private FinisherTimeline() {
    }

    /**
     * Move time (continuous authored motion) shown at {@code realTicks} after
     * the start of an execution whose impact is at move tick {@code impactTick}.
     */
    public static float clipTicks(float realTicks, int impactTick) {
        if (realTicks <= 0.0F) {
            return 0.0F;
        }
        if (realTicks < impactTick) {
            return realTicks;
        }
        if (realTicks < impactTick + HIT_STOP_TICKS) {
            return impactTick;
        }
        return realTicks - HIT_STOP_TICKS;
    }

    public static float clipSeconds(float realTicks, int impactTick) {
        return clipTicks(realTicks, impactTick) / TICKS_PER_SECOND;
    }

    /** True while the shared hit-stop holds the contact frame. */
    public static boolean inHitStop(float realTicks, int impactTick) {
        return realTicks >= impactTick && realTicks < impactTick + HIT_STOP_TICKS;
    }
}
