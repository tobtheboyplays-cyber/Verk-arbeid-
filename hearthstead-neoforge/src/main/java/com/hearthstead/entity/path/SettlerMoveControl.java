package com.hearthstead.entity.path;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Vanilla steering with bounded, collision-checked ladder exits and open-door alignment. */
public final class SettlerMoveControl extends MoveControl {
    public SettlerMoveControl(SettlerEntity settler) {
        super(settler);
    }

    @Override
    public void tick() {
        if (!supportedLadderExit()) {
            Vec3 alignment = openDoorAlignment();
            if (alignment == null) {
                super.tick();
            } else {
                // Forward pressure on an open leaf can leave a lateral
                // correction smaller than vanilla's applied-movement epsilon.
                // Face the clear lateral segment first, using ordinary speed
                // and collision. Navigation retains its original destination.
                operation = Operation.WAIT;
                float yaw = (float) (Mth.atan2(alignment.z - mob.getZ(),
                    alignment.x - mob.getX()) * 180.0D / Math.PI) - 90.0F;
                mob.setYRot(rotlerp(mob.getYRot(), yaw, MAX_TURN));
                mob.setSpeed((float) (speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            }
            return;
        }
        // Vanilla's current-block collision shortcut treats the ladder's thin
        // full-height shape as an obstacle to jump. JUMPING then keeps the old
        // heading until onGround, which a sideways exit must reach first.
        // Only this verified dismount resumes normal turning without that jump.
        operation = Operation.WAIT;
        double dx = wantedX - mob.getX();
        double dz = wantedZ - mob.getZ();
        double dy = wantedY - mob.getY();
        if (dx * dx + dy * dy + dz * dz < MIN_SPEED_SQR) {
            mob.setZza(0.0F);
            return;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        mob.setYRot(rotlerp(mob.getYRot(), yaw, MAX_TURN));
        mob.setSpeed((float) (speedModifier * mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
    }

    private Vec3 openDoorAlignment() {
        if (operation != Operation.MOVE_TO || !mob.onGround() || !mob.horizontalCollision
            || mob.onClimbable() || mob.isPassenger() || mob.isInWater()) return null;
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone() || !path.canReach()) return null;
        BlockPos next = path.getNextNodePos();
        if (!mob.level().hasChunkAt(next) || !mob.level().hasChunkAt(next.above())
            || !mob.level().hasChunkAt(next.below())) return null;
        var door = mob.level().getBlockState(next);
        if (!(door.getBlock() instanceof DoorBlock) || !door.getValue(DoorBlock.OPEN)
            || door.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return null;
        var upper = mob.level().getBlockState(next.above());
        if (!upper.is(door.getBlock()) || !upper.getValue(DoorBlock.OPEN)
            || upper.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
            || upper.getValue(DoorBlock.FACING) != door.getValue(DoorBlock.FACING)
            || upper.getValue(DoorBlock.HINGE) != door.getValue(DoorBlock.HINGE)) return null;
        double cx = next.getX() + .5D;
        double cz = next.getZ() + .5D;
        double floor = WalkNodeEvaluator.getFloorLevel(mob.level(), next);
        if (Math.abs(wantedX - cx) > .01D || Math.abs(wantedZ - cz) > .01D
            || Math.abs(wantedY - floor) > .01D) return null;
        boolean alongZ = door.getValue(DoorBlock.FACING).getAxis() == Direction.Axis.Z;
        double normal = alongZ ? mob.getZ() - cz : mob.getX() - cx;
        double lateral = alongZ ? cx - mob.getX() : cz - mob.getZ();
        // Only a near-threshold body still outside the doorway, correcting a
        // small offset inside the same floor cell. No corner-cutting or turn
        // into a different door; the live requested node is authoritative.
        if (Math.abs(normal) < .5D + mob.getBbWidth() / 2.0D - .01D
            || Math.abs(normal) > 1.25D || Math.abs(lateral) > .25D
            || Math.abs(lateral) < 1.0E-4D) return null;
        double rise = floor - mob.getY();
        if (Math.abs(rise) > mob.maxUpStep()) return null;
        AABB body = mob.getBoundingBox();
        double forwardX = alongZ ? 0.0D : cx - mob.getX();
        double forwardZ = alongZ ? cz - mob.getZ() : 0.0D;
        AABB unalignedEntry = body.move(forwardX, rise, forwardZ);
        boolean leafBlocks = false;
        for (AABB leaf : door.getCollisionShape(mob.level(), next).toAabbs()) {
            if (leaf.move(next).intersects(unalignedEntry)) leafBlocks = true;
        }
        if (!leafBlocks) return null;
        double sideX = alongZ ? lateral : 0.0D;
        double sideZ = alongZ ? 0.0D : lateral;
        AABB sideSweep = body.expandTowards(sideX, 0.0D, sideZ);
        if (!mob.level().noCollision(mob, sideSweep)) return null;
        BlockPos supportPos = BlockPos.containing(mob.getX() + sideX, mob.getY() - .01D, mob.getZ() + sideZ);
        if (!mob.level().hasChunkAt(supportPos)) return null;
        boolean supported = false;
        for (AABB box : mob.level().getBlockState(supportPos).getCollisionShape(mob.level(), supportPos).toAabbs()) {
            AABB top = box.move(supportPos);
            if (Math.abs(top.maxY - body.minY) < .001D && top.minX <= sideSweep.minX
                && top.maxX >= sideSweep.maxX && top.minZ <= sideSweep.minZ
                && top.maxZ >= sideSweep.maxZ) supported = true;
        }
        if (!supported) return null;
        AABB aligned = body.move(sideX, 0.0D, sideZ);
        double lift = Math.max(0.0D, rise);
        if (!mob.level().noCollision(mob, aligned.expandTowards(0.0D, lift, 0.0D))
            || !mob.level().noCollision(mob, aligned.move(0.0D, lift, 0.0D)
                .expandTowards(forwardX, Math.min(0.0D, rise), forwardZ))) return null;
        return new Vec3(mob.getX() + sideX, mob.getY(), mob.getZ() + sideZ);
    }

    private boolean supportedLadderExit() {
        if ((operation != Operation.MOVE_TO && operation != Operation.JUMPING)
            || !mob.onClimbable() || mob.isPassenger() || mob.isInWater()) return false;
        BlockPos feet = mob.blockPosition();
        if (!mob.level().getBlockState(feet).is(Blocks.LADDER)) return false;
        Path path = mob.getNavigation().getPath();
        if (path == null || path.isDone() || !path.canReach()) return false;
        BlockPos next = path.getNextNodePos();
        if (Math.abs(next.getX() - feet.getX()) + Math.abs(next.getZ() - feet.getZ()) != 1
            || !mob.level().hasChunkAt(next) || !mob.level().hasChunkAt(next.below())
            || IndoorBlocks.climbable(mob.level(), next)
            || !mob.level().getFluidState(next).isEmpty()) return false;
        // The live navigation request must actually be for this node, not a
        // different controller's destination or the vertical-column override.
        if (Math.abs(wantedX - (next.getX() + .5D)) > .01D
            || Math.abs(wantedZ - (next.getZ() + .5D)) > .01D) return false;
        var support = mob.level().getBlockState(next.below()).getCollisionShape(mob.level(), next.below());
        if (support.isEmpty()) return false;
        double floor = WalkNodeEvaluator.getFloorLevel(mob.level(), next);
        double rise = floor - mob.getY();
        if (Math.abs(wantedY - floor) > .01D || rise > mob.maxUpStep() || rise < -1.0D) return false;
        // Keep the ordinary step-height limit and require the whole body route
        // to be clear, including the actual half-slab floor height. No teleport,
        // synthetic support, altered velocity or climb-through-wall permission.
        AABB body = mob.getBoundingBox();
        double lift = Math.max(0.0D, rise);
        if (!mob.level().noCollision(mob, body.expandTowards(0.0D, lift, 0.0D))) return false;
        AABB sweep = body.move(0.0D, lift, 0.0D).expandTowards(
            wantedX - mob.getX(), Math.min(0.0D, rise), wantedZ - mob.getZ());
        return mob.level().noCollision(mob, sweep);
    }
}
