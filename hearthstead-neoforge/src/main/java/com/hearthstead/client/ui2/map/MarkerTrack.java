package com.hearthstead.client.ui2.map;

import java.util.UUID;

/**
 * One settler's marker between server samples.
 *
 * <p>Samples arrive about every half second. A new sample starts a straight
 * glide from wherever the marker is drawn right now to the new position,
 * lasting one measured sample interval, so motion is continuous (no snap
 * back, no overshoot) and at most one interval behind the server. A jump
 * longer than {@link #TELEPORT_BLOCKS} (teleport, respawn) snaps instead of
 * sliding across the map. Pure: time is passed in, nothing allocates.
 */
public final class MarkerTrack {
    public static final float TELEPORT_BLOCKS = 16.0F;
    static final long MIN_GLIDE_MS = 50L;
    static final long MAX_GLIDE_MS = 1500L;

    public final UUID id;
    public int entityId = -1;
    public int professionId;
    public int activityId;
    public int statusId;
    public float y;

    private boolean initialized;
    private double fromX;
    private double fromZ;
    private double toX;
    private double toZ;
    private long startMs;
    private long glideMs = 500L;
    private float dirX;
    private float dirZ;
    private float speed;
    /** Receipt generation of the last sample that named this settler. */
    public int seenGeneration;

    // Presentation state owned by the map view (walk cycle, facing, crowd fan-out); never sent.
    public float walkPhase;
    public long animMs;
    public int view;
    public boolean facingLeft;
    public float fanX;
    public float fanY;

    public MarkerTrack(UUID id) {
        this.id = id;
    }

    /** Feed one server sample received at {@code nowMs}; {@code intervalMs} is the expected gap. */
    public void push(double x, float sampleY, double z, long nowMs, long intervalMs) {
        y = sampleY;
        if (!initialized) {
            fromX = toX = x;
            fromZ = toZ = z;
            startMs = nowMs;
            initialized = true;
            return;
        }
        double cx = x(nowMs);
        double cz = z(nowMs);
        float dx = (float) (x - cx);
        float dz = (float) (z - cz);
        float distance = (float) Math.sqrt(dx * dx + dz * dz);
        glideMs = Math.max(MIN_GLIDE_MS, Math.min(MAX_GLIDE_MS, intervalMs));
        if (distance > TELEPORT_BLOCKS) {
            fromX = toX = x;
            fromZ = toZ = z;
            speed = 0.0F;
        } else {
            fromX = cx;
            fromZ = cz;
            toX = x;
            toZ = z;
            if (distance > 0.05F) {
                dirX = dx / distance;
                dirZ = dz / distance;
            }
            speed = distance / (glideMs / 1000.0F);
        }
        startMs = nowMs;
    }

    public boolean initialized() {
        return initialized;
    }

    public float progress(long nowMs) {
        if (!initialized) return 1.0F;
        float t = (nowMs - startMs) / (float) glideMs;
        return t <= 0.0F ? 0.0F : Math.min(1.0F, t);
    }

    public double x(long nowMs) {
        return fromX + (toX - fromX) * progress(nowMs);
    }

    public double z(long nowMs) {
        return fromZ + (toZ - fromZ) * progress(nowMs);
    }

    public double targetX() {
        return toX;
    }

    public double targetZ() {
        return toZ;
    }

    /** Unit heading of the latest real movement (0,0 until it has moved). */
    public float dirX() {
        return dirX;
    }

    public float dirZ() {
        return dirZ;
    }

    /** Blocks per second over the current glide; 0 once the glide has ended. */
    public float speed(long nowMs) {
        return progress(nowMs) >= 1.0F ? 0.0F : speed;
    }
}
