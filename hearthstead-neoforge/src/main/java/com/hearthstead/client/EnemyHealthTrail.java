package com.hearthstead.client;

/** Presentation only: actual health updates immediately; elapsed monotonic time owns the damage tail. */
public final class EnemyHealthTrail {
    private static final long HOLD = 150_000_000L;
    private static final long CATCH_UP = 300_000_000L;
    private static final long FLASH = 120_000_000L;
    private float health, maximum, actual, trailing, from;
    private long hitTime;
    private boolean initialized, damaged;

    public void observe(float health, float maximum, long now) {
        advance(now);
        float ratio = Math.max(0F, Math.min(1F, health / maximum));
        if (!initialized || maximum != this.maximum || health > this.health) {
            actual = trailing = from = ratio;
            damaged = false;
        } else if (health < this.health) {
            from = Math.max(trailing, actual);
            actual = ratio;
            trailing = Math.max(from, actual);
            hitTime = now;
            damaged = true;
        } else actual = ratio;
        this.health = health; this.maximum = maximum; initialized = true;
    }
    private void advance(long now) {
        if (!damaged) return;
        long elapsed = Math.max(0L, now - hitTime);
        if (elapsed >= HOLD + CATCH_UP) { trailing = actual; damaged = false; }
        else if (elapsed > HOLD) {
            float t = (float)(elapsed - HOLD) / CATCH_UP;
            float smooth = t * t * (3F - 2F * t);
            trailing = from + (actual - from) * smooth;
        }
    }
    public float actual() { return actual; }
    public float trailing() { return Math.max(actual, trailing); }
    public float flash(long now) {
        return damaged ? Math.max(0F, 1F - (float)Math.max(0L, now - hitTime) / FLASH) : 0F;
    }
}
