package com.hearthstead.client.model;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernServingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Read-only geometry contract shared by the real serving renderer and hand IK. */
public final class TavernServingMotion {
    public static final double CUP_WIDTH = .24;
    public static final double CUP_HEIGHT = .31;
    public static final double PLATE_DIAMETER = .38;
    public static final double CUP_GRIP_HEIGHT = .10;
    private TavernServingMotion() {}

    /** Reusable service decoration lasts with the serving cargo; never an inventory item. */
    public static boolean hasTableware(TavernServingEntity serving) {
        return !serving.displayFood().isEmpty() || !serving.displayGlass().isEmpty();
    }

    /** Cup mesh coordinates are bottom-centred, with Y=0 at base and Y=.31 at rim. */
    public record CupPose(Vec3 base, Vec3 tiltAxis, float tiltDegrees, float liftFraction) {
        public Vec3 transformWorldOffset(Vec3 uprightOffset) {
            double radians = Math.toRadians(tiltDegrees);
            double c = Math.cos(radians), s = Math.sin(radians);
            return uprightOffset.scale(c).add(tiltAxis.cross(uprightOffset).scale(s))
                .add(tiltAxis.scale(tiltAxis.dot(uprightOffset) * (1 - c)));
        }
        public Vec3 point(Vec3 uprightOffset) { return base.add(transformWorldOffset(uprightOffset)); }
    }

    public static CupPose cupPose(TavernServingEntity serving, float partial) {
        float t = serving.presentationDrinkingTicks(partial);
        float lift = serving.phase() == TavernServingEntity.Phase.DRINKING ? drinkLift(t) : 0;
        // Facing is diner -> table. Negative rotation tips the rim toward the diner.
        Vec3 forward = new Vec3(serving.facing().getStepX(), 0, serving.facing().getStepZ());
        Vec3 axis = new Vec3(forward.z, 0, -forward.x);
        float tilt = -28F * lift;
        CupPose rotation = new CupPose(Vec3.ZERO, axis, tilt, lift);
        Vec3 nearRim = forward.scale(-CUP_WIDTH / 2).add(0, CUP_HEIGHT, 0);
        // Server glassPosition is a base anchor at the table but a mouth-contact
        // target at full lift. Gradually change the mesh origin, not item ownership.
        // At tick8..16 the near rim, rather than the cup base, equals that target.
        Vec3 base = serving.glassPosition(partial).subtract(rotation.transformWorldOffset(nearRim).scale(lift));
        return new CupPose(base, axis, tilt, lift);
    }

    /** The existing server owns the exact 8 lift + 8 sip + 8 lower clock. */
    public static float drinkLift(float tick) {
        if (tick <= TavernServingEntity.DRINK_LIFT_TICKS)
            return smooth(tick / TavernServingEntity.DRINK_LIFT_TICKS);
        if (tick <= TavernServingEntity.DRINK_LIFT_TICKS + TavernServingEntity.DRINK_SIP_TICKS) return 1;
        return 1 - smooth((tick - TavernServingEntity.DRINK_LIFT_TICKS - TavernServingEntity.DRINK_SIP_TICKS)
            / TavernServingEntity.DRINK_LOWER_TICKS);
    }

    /** side=+1 left palm, -1 right palm; contact is on the cup wall, never its hollow centre. */
    public static Vec3 cupGrip(TavernServingEntity serving, SettlerEntity actor, float partial, int side) {
        double yaw = Math.toRadians(Mth.rotLerp(partial, actor.yBodyRotO, actor.yBodyRot));
        Vec3 sideWall = new Vec3(Math.cos(yaw) * side * CUP_WIDTH / 2, CUP_GRIP_HEIGHT,
            Math.sin(yaw) * side * CUP_WIDTH / 2);
        return cupPose(serving, partial).point(sideWall);
    }

    /** Host supports the underside; food receive/meal bridge keeps its own existing contact. */
    public static Vec3 plateSupport(TavernServingEntity serving, float partial) {
        return serving.foodPosition(partial).add(0, .004, 0);
    }

    private static float smooth(float value) {
        float t = Mth.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
