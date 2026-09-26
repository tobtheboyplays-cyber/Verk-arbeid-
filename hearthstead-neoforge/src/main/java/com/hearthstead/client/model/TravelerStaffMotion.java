package com.hearthstead.client.model;

import net.minecraft.world.phys.Vec3;

/** Small deterministic contact state; movement advances plants, time only finishes a lift. */
final class TravelerStaffMotion {
    static final double PLANT_DISTANCE = 0.30D;
    static final double RECOVERY_DISTANCE = 0.22D;
    static final float RECOVERY_TICKS = 8.0F;
    static final double LIFT_HEIGHT = 0.10D;
    private Vec3 previous;
    private Vec3 anchor;
    private Vec3 recoveryStart;
    private float previousAge;
    private float recoveryAge;
    private double distance;
    private boolean recovering;

    Vec3 sample(Vec3 position, Vec3 desiredGround, float age) {
        if (previous == null || age < previousAge
            || position.distanceToSqr(previous) > 4.0D) {
            previous = position;
            previousAge = age;
            anchor = desiredGround;
            distance = 0;
            recovering = false;
            return anchor;
        }
        Vec3 delta = position.subtract(previous);
        distance += Math.hypot(delta.x, delta.z);
        previous = position;
        previousAge = age;
        if (!recovering && (distance >= PLANT_DISTANCE
            || desiredGround.distanceToSqr(anchor) > 0.16D)) {
            recovering = true;
            recoveryAge = age;
            recoveryStart = anchor;
            distance = 0;
        }
        if (!recovering) {
            return anchor; // exactly fixed world tip, including stopped breathing
        }
        double t = Math.min(1, Math.max(distance / RECOVERY_DISTANCE,
            (age - recoveryAge) / RECOVERY_TICKS));
        if (t >= 1 - 1e-9) {
            anchor = desiredGround;
            recovering = false;
            distance = 0;
            return anchor; // exact contact, not the floating-point residue of sin(pi)
        }
        double eased = t * t * (3 - 2 * t);
        Vec3 tip = recoveryStart.lerp(desiredGround, eased)
            .add(0, Math.sin(Math.PI * t) * LIFT_HEIGHT, 0);
        return tip;
    }

    boolean planted() { return !recovering; }
}
