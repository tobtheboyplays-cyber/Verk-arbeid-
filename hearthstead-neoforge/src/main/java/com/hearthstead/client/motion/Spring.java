package com.hearthstead.client.motion;

/**
 * Damped angular spring for secondary motion (bag sway, brim lag, tool lag).
 * Semi-implicit Euler with fixed sub-steps so the result does not depend on
 * frame rate; the value is clamped so nothing can wind up or explode after a
 * frame hitch. Calm by default: damping ratio ~0.6 gives one soft
 * overshoot, never a cartoon wobble.
 */
public final class Spring {
    private static final float STEP = 1.0F / 120.0F;

    public float value;
    public float velocity;

    /**
     * @param dt        seconds since last update (clamped to 0.1)
     * @param target    rest value the spring settles toward
     * @param drive     extra angular acceleration this frame (the "kick")
     * @param frequency natural frequency in Hz
     * @param damping   damping ratio (1 = critical)
     * @param limit     absolute clamp on the value
     */
    public void update(float dt, float target, float drive, float frequency, float damping, float limit) {
        if (!(dt > 0.0F)) {
            return;
        }
        float remaining = Math.min(dt, 0.1F);
        float omega = (float) (2.0 * Math.PI * frequency);
        while (remaining > 1.0E-6F) {
            float h = Math.min(STEP, remaining);
            float accel = -omega * omega * (value - target) - 2.0F * damping * omega * velocity + drive;
            velocity += accel * h;
            value += velocity * h;
            remaining -= h;
        }
        if (value > limit) {
            value = limit;
            velocity = Math.min(velocity, 0.0F);
        } else if (value < -limit) {
            value = -limit;
            velocity = Math.max(velocity, 0.0F);
        }
        if (!Float.isFinite(value) || !Float.isFinite(velocity)) {
            reset();
        }
    }

    public void reset() {
        value = 0.0F;
        velocity = 0.0F;
    }
}
