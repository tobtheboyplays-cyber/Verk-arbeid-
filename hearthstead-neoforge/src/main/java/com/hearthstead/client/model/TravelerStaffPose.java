package com.hearthstead.client.model;

import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.WeakHashMap;

/** Guest-only cosmetic contact. No inventory, movement, transaction or server action. */
final class TravelerStaffPose {
    private static final Map<SettlerEntity, TravelerStaffMotion> MOTION = new WeakHashMap<>();

    static boolean apply(SettlerEntity actor, float age, ModelPart root, ModelPart torso,
                         ModelPart arm, ModelPart upper, ModelPart fore, ModelPart staff) {
        SettlerActivity activity = actor.getActivity();
        if (!(actor.hasTravelerAppearance() || actor.getProfession() == com.hearthstead.entity.Profession.NONE
                && com.hearthstead.entity.look.CharacterLooks.costumeStaff(actor.getLookCostume()))
            || !actor.isAlive() || actor.isPassenger()
            || actor.isSleeping() || !actor.onGround() || actor.onClimbable()
            || actor.isAutoSpinAttack() || actor.isFullyFrozen() || actor.isFallFlying()
            || LivingEntityRenderer.isEntityUpsideDown(actor)
            || !actor.getMainHandItem().isEmpty() || !actor.getOffhandItem().isEmpty()
            || actor.blessingReceiveState.isStarted() || actor.bagToChestUnloadState.isStarted()
            || (activity != SettlerActivity.TRAVELING && activity != SettlerActivity.IDLE)) {
            MOTION.remove(actor);
            return false;
        }
        float partial = Mth.clamp(age - actor.tickCount, 0, 1);
        Vec3 position = new Vec3(Mth.lerp(partial, actor.xo, actor.getX()),
            Mth.lerp(partial, actor.yo, actor.getY()), Mth.lerp(partial, actor.zo, actor.getZ()));
        double yaw = Math.toRadians(Mth.rotLerp(partial, actor.yBodyRotO, actor.yBodyRot));
        double scale = actor.getScale();
        Vec3 desired = position.add((-Math.cos(yaw) * .43 - Math.sin(yaw) * .12) * scale,
            0, (-Math.sin(yaw) * .43 + Math.cos(yaw) * .12) * scale);
        Vec3 ground = ground(actor, desired, position.y);
        if (ground == null) {
            MOTION.remove(actor);
            return false; // no fake contact across a cliff or unsupported surface
        }
        TravelerStaffMotion motion = MOTION.computeIfAbsent(actor, ignored -> new TravelerStaffMotion());
        Vec3 tip = motion.sample(position, ground, age);
        if (motion.planted()) {
            Vec3 actualSupport = ground(actor, tip, tip.y);
            if (actualSupport == null || Math.abs(actualSupport.y - tip.y) > 1e-5) {
                MOTION.remove(actor);
                return false; // the planted surface changed; never keep a floating anchor
            }
        }
        Vec3 grip = tip.add(0, scale, 0);
        Vec3 localGrip = inversePart(inversePart(
            TavernServingHandPose.worldToModel(actor, grip, partial), root), torso);
        Vec3 shoulder = new Vec3(arm.x, arm.y, arm.z);
        var solution = TavernServingHandPose.solve(shoulder, localGrip, -1);
        if (solution == null) {
            MOTION.remove(actor);
            return false; // preserve truthful anatomy rather than stretching the arm
        }
        upper.copyFrom(arm);
        fore.resetPose();
        aim(upper, solution.elbow().subtract(shoulder));
        aim(fore, inverseRotation(localGrip.subtract(solution.elbow()), upper));
        upper.visible = true;
        arm.visible = false;
        Vec3 localTip = inversePart(TavernServingHandPose.worldToModel(actor, tip, partial), root);
        Vec3 localUp = inversePart(TavernServingHandPose.worldToModel(actor,
            tip.add(0, scale, 0), partial), root);
        staff.setPos((float) localTip.x, (float) localTip.y, (float) localTip.z);
        // The shaft's negative Y points up. Inverting the complete root pose
        // keeps both the tip and palm fixed despite idle breathing/root sway.
        Vec3 downward = localTip.subtract(localUp);
        aim(staff, downward);
        staff.yScale = (float) (downward.length() / 16.0D);
        staff.visible = true;
        return true;
    }

    private static Vec3 ground(SettlerEntity actor, Vec3 point, double feetY) {
        BlockPos base = BlockPos.containing(point.x, feetY, point.z);
        Vec3 closest = null;
        double error = .61D;
        for (int dy = 0; dy >= -2; dy--) {
            BlockPos floor = base.offset(0, dy, 0);
            if (!actor.level().hasChunkAt(floor)) continue;
            var state = actor.level().getBlockState(floor);
            var shape = state.getCollisionShape(actor.level(), floor);
            if (shape.isEmpty() || !state.getFluidState().isEmpty()) continue;
            double localTop = surfaceTop(shape, point.x - floor.getX(), point.z - floor.getZ());
            if (!Double.isFinite(localTop)) continue;
            double height = floor.getY() + localTop;
            double currentError = Math.abs(height - feetY);
            if (currentError < error) {
                error = currentError;
                closest = new Vec3(point.x, height, point.z);
            }
        }
        return closest;
    }

    /** Highest actual collision box under this tip, not the entire block's maximum. */
    static double surfaceTop(VoxelShape shape, double x, double z) {
        double top = Double.NEGATIVE_INFINITY;
        for (var box : shape.toAabbs()) {
            if (x >= box.minX && x <= box.maxX && z >= box.minZ && z <= box.maxZ) {
                top = Math.max(top, box.maxY);
            }
        }
        return top;
    }

    private static void aim(ModelPart part, Vec3 direction) {
        part.xRot = (float) Math.atan2(direction.z, Math.hypot(direction.x, direction.y));
        part.yRot = 0;
        part.zRot = (float) Math.atan2(-direction.x, direction.y);
    }

    private static Vec3 inversePart(Vec3 point, ModelPart part) {
        Vec3 p = inverseRotation(point.subtract(part.x, part.y, part.z), part);
        return new Vec3(p.x / part.xScale, p.y / part.yScale, p.z / part.zScale);
    }

    private static Vec3 inverseRotation(Vec3 p, ModelPart part) {
        double c = Math.cos(-part.zRot), s = Math.sin(-part.zRot);
        p = new Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z);
        c = Math.cos(-part.yRot); s = Math.sin(-part.yRot);
        p = new Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
        c = Math.cos(-part.xRot); s = Math.sin(-part.xRot);
        return new Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c);
    }

    static boolean qaPlanted(SettlerEntity actor) {
        var motion = MOTION.get(actor);
        return motion != null && motion.planted();
    }

    private TravelerStaffPose() {}
}
