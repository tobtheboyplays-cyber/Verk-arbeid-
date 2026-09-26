package com.hearthstead.client.model;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.ResidentMeal;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.TavernSeatEntity;
import com.hearthstead.entity.TavernServingEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Client-only projection of real serving cargo. Never advances a service or owns an item. */
public final class TavernServingHandPose {
    private static final double UPPER = 4, FOREARM = 6;
    private TavernServingHandPose() {}

    /** Two-bit mask: right=1, left=2. Called after the seated root/leg pose. */
    static int apply(SettlerEntity actor, float age, ModelPart root, ModelPart torso,
                     ModelPart right, ModelPart left, ModelPart rightUpper, ModelPart rightFore,
                     ModelPart leftUpper, ModelPart leftFore) {
        // These renderer rotation modes have a different inverse transform.
        if (!actor.isAlive() || actor.isSleeping() || actor.isAutoSpinAttack()
            || actor.isFullyFrozen() || actor.isFallFlying() || LivingEntityRenderer.isEntityUpsideDown(actor)
            || actor.blessingReceiveState.isStarted() || actor.bagToChestUnloadState.isStarted()) return 0;
        if (actor.getProfession() != Profession.INNKEEPER && !(actor.getVehicle() instanceof TavernSeatEntity)) return 0;
        TavernServingEntity serving = findServing(actor);
        if (serving == null) return 0;
        float partial = Mth.clamp(age - actor.tickCount, 0, 1);
        boolean host = actor.getUUID().equals(serving.hostId());
        float weight = 0, lean = 0;
        Vec3 food = null, glass = null;
        if (host && actor.getProfession() == Profession.INNKEEPER
            && actor.getMainHandItem().isEmpty() && actor.getOffhandItem().isEmpty()) {
            switch (serving.phase()) {
                case CARRYING, RETURNING -> weight = 1;
                case PLACING -> {
                    weight = 1;
                    lean = smooth(serving.presentationTransitionTicks(partial) / 12F);
                }
                case SLIDING -> {
                    float elapsed = Math.min(20, serving.presentationSlideTicks(partial));
                    weight = 1 - smooth((elapsed - 4) / 6F);
                    lean = weight;
                }
                case PICKING_UP -> {
                    weight = 1;
                    lean = 1 - smooth(serving.presentationTransitionTicks(partial) / 12F);
                }
                default -> { }
            }
            if (weight <= 0) return 0;
            if (TavernServingMotion.hasTableware(serving)) food = TavernServingMotion.plateSupport(serving, partial);
            if (!serving.displayGlass().isEmpty()) glass = TavernServingMotion.cupGrip(serving, actor, partial, 1);
            if (serving.phase() == TavernServingEntity.Phase.SLIDING) {
                // After four pushed ticks the palms recover from the RELEASE point;
                // they never chase or pretend to hold the coasting tabletop items.
                float beyond = Math.max(0, Math.min(20, serving.presentationSlideTicks(partial)) - 4);
                Vec3 rewind = new Vec3(serving.facing().getStepX(), 0,
                    serving.facing().getStepZ()).scale(beyond * .60 / 20);
                if (food != null) food = food.add(rewind);
                if (glass != null) glass = glass.add(rewind);
            }
            // Service hinge only. Root, feet, seated legs and other roles are untouched.
            // A table-facing body can still inherit the idle torso's independent
            // yaw/roll/breath. Settle those only over the service hinge envelope;
            // otherwise a valid stand can put one real palm beyond the 4+6 arm.
            torso.xRot = Mth.lerp(lean, torso.xRot, 30 * Mth.DEG_TO_RAD);
            torso.yRot = Mth.lerp(lean, torso.yRot, 0);
            torso.zRot = Mth.lerp(lean, torso.zRot, 0);
            torso.xScale = Mth.lerp(lean, torso.xScale, 1);
            torso.yScale = Mth.lerp(lean, torso.yScale, 1);
            torso.zScale = Mth.lerp(lean, torso.zScale, 1);
            torso.z -= .96F * lean;
        } else if (!host && actor.getVehicle() instanceof TavernSeatEntity
            && actor.getUUID().equals(serving.guestId())) {
            if (serving.phase() == TavernServingEntity.Phase.READY
                && !serving.displayFood().isEmpty()) {
                weight = smooth(serving.presentationReceivingTicks(partial) / 8F);
            } else if (serving.phase() == TavernServingEntity.Phase.DINING && actor.hasMeal()) {
                // Preserve the receiving endpoint through the actual ownership boundary,
                // then blend to the existing EAT palm. Meal renderer owns the visual bridge.
                weight = 1 - smooth((ResidentMeal.DURATION - actor.mealRemainingTicks() + partial) / 6F);
            } else if (serving.phase() == TavernServingEntity.Phase.DRINKING
                && !actor.hasMeal() && !serving.displayGlass().isEmpty()) {
                // The synchronized serving point moves the same real glass to
                // the saved mouth target; the seated left palm follows it.
                weight = 1;
                glass = TavernServingMotion.cupGrip(serving, actor, partial, 1);
            }
            Vec3 receive = serving.foodPosition(partial);
            if (serving.phase() == TavernServingEntity.Phase.DRINKING) {
                // Left hand alone owns the sip; no parallel food/reach pose.
                receive = null;
            } else if (serving.phase() == TavernServingEntity.Phase.DINING && !actor.hasMeal()
                && !serving.displayGlass().isEmpty()) {
                weight = smooth(serving.presentationReturnPreparationTicks(partial) / 8F);
                receive = TavernServingMotion.plateSupport(serving, partial);
                glass = TavernServingMotion.cupGrip(serving, actor, partial, 1);
            } else if (serving.phase() == TavernServingEntity.Phase.RETURN_SLIDING
                && !serving.displayGlass().isEmpty()) {
                float elapsed = Math.min(20, 20 - serving.presentationSlideTicks(partial));
                weight = 1 - smooth((elapsed - 4) / 6F);
                // Guest pushes back along dinerFacing; recover from the four-tick
                // release endpoint while the same cup and service plate coast toward the host.
                Vec3 rewind = new Vec3(serving.facing().getStepX(), 0,
                    serving.facing().getStepZ()).scale(Math.max(0, elapsed - 4) * .60 / 20);
                receive = TavernServingMotion.plateSupport(serving, partial).subtract(rewind);
                glass = TavernServingMotion.cupGrip(serving, actor, partial, 1).subtract(rewind);
            }
            if (weight <= 0) return 0;
            food = receive; // Right receives food or pushes the plate; left pushes the same real cup.
            torso.xRot = Mth.lerp(weight, torso.xRot, 18 * Mth.DEG_TO_RAD);
        } else return 0;
        int mask = 0;
        if (food != null && pose(actor, partial, root, torso, right, rightUpper, rightFore, food, weight, -1)) mask |= 1;
        if (glass != null && pose(actor, partial, root, torso, left, leftUpper, leftFore, glass, weight, 1)) mask |= 2;
        return mask;
    }

    /** Bounded loaded-client lookup; no server-only site/source/settlement fields. */
    public static TavernServingEntity findServing(SettlerEntity actor) {
        TavernServingEntity found = null;
        for (TavernServingEntity item : actor.level().getEntitiesOfClass(TavernServingEntity.class,
                actor.getBoundingBox().inflate(3), e -> !e.isRemoved()
                    && (actor.getUUID().equals(e.hostId()) || actor.getUUID().equals(e.guestId())))) {
            if (found != null) return null; // Ambiguous projection is not permission to pick an arbitrary meal.
            found = item;
        }
        return found;
    }

    private static boolean pose(SettlerEntity actor, float partial, ModelPart root, ModelPart torso,
                                ModelPart rigid, ModelPart upper, ModelPart fore, Vec3 world,
                                float weight, int side) {
        Vec3 local = inversePart(inversePart(worldToModel(actor, world, partial), root), torso);
        Vec3 shoulder = new Vec3(rigid.x, rigid.y, rigid.z);
        Vec3 ordinary = forwardPart(new Vec3(0, UPPER + FOREARM, 0), rigid);
        Vec3 desired = ordinary.lerp(local, weight);
        ArmSolution solution = solve(shoulder, desired, side);
        // No scale/stretch, detached shoulder or hidden target clamp. An unsupported
        // current guest-edge pickup remains visibly unsupported until server routing changes.
        if (solution == null) return false;
        upper.copyFrom(rigid); fore.resetPose();
        aim(upper, solution.elbow().subtract(shoulder));
        Vec3 lower = inverseRotation(desired.subtract(solution.elbow()), upper);
        aim(fore, lower);
        upper.visible = true;
        rigid.visible = false;
        return true;
    }

    /** Exact shoulder->elbow=4px and elbow->palm=6px; no extending beyond annulus2..10. */
    static ArmSolution solve(Vec3 shoulder, Vec3 palm, int side) {
        Vec3 delta = palm.subtract(shoulder);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance < 2 - 1e-6 || distance > 10 + 1e-6) return null;
        Vec3 axis = delta.scale(1 / distance);
        Vec3 pole = new Vec3(side, .65, -.35);
        Vec3 normal = pole.subtract(axis.scale(pole.dot(axis)));
        if (normal.lengthSqr() < 1e-8) {
            pole = new Vec3(0, 0, 1);
            normal = pole.subtract(axis.scale(pole.dot(axis)));
        }
        double along = (UPPER * UPPER - FOREARM * FOREARM + distance * distance) / (2 * distance);
        double height = Math.sqrt(Math.max(0, UPPER * UPPER - along * along));
        Vec3 elbow = shoulder.add(axis.scale(along)).add(normal.normalize().scale(height));
        return new ArmSolution(elbow, palm);
    }

    record ArmSolution(Vec3 elbow, Vec3 palm) {}

    private static void aim(ModelPart part, Vec3 direction) {
        // Rz(z) Rx(x) * (0,length,0) = direction. Preserve translations and unit scale.
        part.xRot = (float) Math.atan2(direction.z, Math.hypot(direction.x, direction.y));
        part.yRot = 0;
        part.zRot = (float) Math.atan2(-direction.x, direction.y);
    }

    /** Inverts vanilla translation(-1.501Y), mirror(-X,-Y,+Z), yaw(180-bodyYaw), and actor scale. */
    static Vec3 worldToModel(SettlerEntity actor, Vec3 target, float partial) {
        Vec3 position = new Vec3(Mth.lerp(partial, actor.xo, actor.getX()),
            Mth.lerp(partial, actor.yo, actor.getY()), Mth.lerp(partial, actor.zo, actor.getZ()));
        Vec3 d = target.subtract(position).scale(1 / actor.getScale());
        double yaw = Math.toRadians(180 - Mth.rotLerp(partial, actor.yBodyRotO, actor.yBodyRot));
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new Vec3((-c * d.x + s * d.z) * 16, (1.501 - d.y) * 16,
            (s * d.x + c * d.z) * 16);
    }

    private static Vec3 inversePart(Vec3 point, ModelPart part) {
        Vec3 p = inverseRotation(point.subtract(part.x, part.y, part.z), part);
        return new Vec3(p.x / part.xScale, p.y / part.yScale, p.z / part.zScale);
    }

    private static Vec3 inverseRotation(Vec3 p, ModelPart part) {
        return rotateX(rotateY(rotateZ(p, -part.zRot), -part.yRot), -part.xRot);
    }

    private static Vec3 forwardPart(Vec3 p, ModelPart part) {
        p = new Vec3(p.x * part.xScale, p.y * part.yScale, p.z * part.zScale);
        return rotateZ(rotateY(rotateX(p, part.xRot), part.yRot), part.zRot).add(part.x, part.y, part.z);
    }

    private static Vec3 rotateX(Vec3 p, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c);
    }
    private static Vec3 rotateY(Vec3 p, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
    }
    private static Vec3 rotateZ(Vec3 p, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z);
    }
    private static float smooth(float x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
