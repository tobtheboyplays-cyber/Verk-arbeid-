package com.hearthstead.settlement.work;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Set;

/** Physical farm contact and bounded movement; never item or work-zone authority. */
public final class FarmWorkApproach {
    public enum Contact { PLANT, SOIL }

    // Keep the existing Farmer block-position envelope exactly. Visibility
    // narrows permission; projected navigation positions never grant contact.
    private static final double REACH_SQR = 6.5D;
    private static final int MAX_HOUSE_SCAN = 4096;

    public static boolean canContact(@Nullable ServerLevel level,
                                     @Nullable SettlerEntity worker,
                                     @Nullable BlockPos target, Contact contact) {
        return level != null && worker != null && target != null
            && WorkZoneService.livePositionAvailable(level, target)
            && worker.blockPosition().distSqr(target) <= REACH_SQR
            && visible(level, worker, worker.getEyePosition(), target, contact);
    }

    /**
     * A Farmer may only do PLANT work from a dry feet cell. Use the same
     * fractional-foot convention as the field bag: a Farmer standing on
     * farmland at y+15/16 has feet in the air cell above it.
     */
    public static boolean canDryPlantContact(@Nullable ServerLevel level,
                                              @Nullable SettlerEntity worker,
                                              @Nullable BlockPos target) {
        if (!canContact(level, worker, target, Contact.PLANT) || worker == null) return false;
        BlockPos feet = BlockPos.containing(worker.getX(),
            Math.ceil(worker.getY() - 1.0E-4D), worker.getZ());
        return dryStand(level, feet) && !worker.isInWater();
    }

    private static boolean visible(ServerLevel level, SettlerEntity worker,
                                   Vec3 eyes, BlockPos target, Contact contact) {
        // Farmland has a 15/16-height top; a soil hit is legitimate. Crop/air
        // cells often have no collider, so MISS is equally legitimate.
        Vec3 point = Vec3.atBottomCenterOf(target).add(0,
            contact == Contact.SOIL ? 0.95D : 0.2D, 0);
        // A short diagonal ray can cross a third chunk at a corner even when
        // both endpoint chunks are loaded. Check its bounded cell enclosure
        // before clip, which must never force a chunk for work inspection.
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(eyes),
                BlockPos.containing(point))) {
            if (!WorkZoneService.livePositionAvailable(level, cell)) {
                return false;
            }
        }
        var hit = level.clip(new ClipContext(eyes, point,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, worker));
        return hit.getType() == HitResult.Type.MISS
            || target.equals(hit.getBlockPos());
    }

    public static boolean moveToContact(ServerLevel level, SettlerEntity worker,
                                        @Nullable Building farmhouse,
                                        @Nullable BlockPos target,
                                        Contact contact, double speed) {
        if (target == null || !WorkZoneService.livePositionAvailable(level, target)) {
            return false;
        }
        if (canContact(level, worker, target, contact)) {
            worker.getNavigation().stop();
            return true;
        }
        // A field-side partial route may end at the opposite interior wall.
        // Stage the known employer's real boundary door first, including an
        // already-open door while crossing its raised threshold.
        if (stageExitFromOwnFarmhouse(level, worker, farmhouse, target, speed)) {
            return true;
        }
        Set<BlockPos> stands = new LinkedHashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    // Actual contact was rejected above. Vanilla can mark
                    // this feet cell reached while stopping short of its exact
                    // center; resubmitting it cannot fix a corner-blocked ray.
                    // Choose another legal stand, never loosen contact.
                    if (feet.equals(worker.blockPosition())
                        || feet.distSqr(target) > REACH_SQR
                        || !ContainerApproach.isStandable(level, feet)) {
                        continue;
                    }
                    Vec3 position = Vec3.atBottomCenterOf(feet);
                    if (level.noCollision(worker, worker.getBoundingBox().move(
                            position.subtract(worker.position())))
                        && visible(level, worker,
                            position.add(0, worker.getEyeHeight(), 0), target, contact)) {
                        stands.add(feet.immutable());
                    }
                }
            }
        }
        return !stands.isEmpty() && ContainerApproach.startPathToStandTargets(
            level, worker, stands, speed) != null;
    }

    /**
     * Farmer-only PLANT approach. It uses the ordinary bounded visible-contact
     * envelope, but plans a dry detour when the shortest generic path crosses
     * water. The temporary path malus is restored before movement begins.
     */
    public static boolean moveToDryPlantContact(ServerLevel level, SettlerEntity worker,
                                                 @Nullable Building farmhouse,
                                                 @Nullable BlockPos target, double speed) {
        if (target == null || !WorkZoneService.livePositionAvailable(level, target)) return false;
        if (canDryPlantContact(level, worker, target)) {
            worker.getNavigation().stop();
            return true;
        }
        if (stageExitFromOwnFarmhouse(level, worker, farmhouse, target, speed)) return true;
        Set<BlockPos> stands = new LinkedHashSet<>();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = target.offset(dx, dy, dz);
                if (feet.equals(worker.blockPosition()) || feet.distSqr(target) > REACH_SQR
                    || !dryStand(level, feet)) continue;
                Vec3 position = Vec3.atBottomCenterOf(feet);
                if (level.noCollision(worker, worker.getBoundingBox().move(
                        position.subtract(worker.position())))
                    && visible(level, worker, position.add(0, worker.getEyeHeight(), 0),
                        target, Contact.PLANT)) {
                    stands.add(feet.immutable());
                }
            }
        }
        if (stands.isEmpty()) return false;
        float waterMalus = worker.getPathfindingMalus(PathType.WATER);
        try {
            worker.setPathfindingMalus(PathType.WATER, -1.0F);
            Path route = worker.getNavigation().createPath(stands, 0);
            if (route == null || !route.canReach() || !stands.contains(route.getTarget())
                || !dryRoute(level, route)) {
                route = new FieldReturnNavigation(worker, level).findFieldRoute(stands);
            }
            if (route == null || !route.canReach() || !stands.contains(route.getTarget())
                || !dryRoute(level, route)) return false;
            return worker.getNavigation().moveTo(route, speed);
        } finally {
            worker.setPathfindingMalus(PathType.WATER, waterMalus);
        }
    }

    /** A home can be farther from the field than the ordinary mob search range. */
    private static final class FieldReturnNavigation
            extends com.hearthstead.entity.path.RoadNavigation {
        private FieldReturnNavigation(SettlerEntity worker, ServerLevel level) {
            super(worker, level);
        }

        private Path findFieldRoute(Set<BlockPos> stands) {
            setMaxVisitedNodesMultiplier(3.0F);
            return createPath(stands, 0, false, 0, 96.0F);
        }
    }

    private static boolean dryStand(@Nullable ServerLevel level, BlockPos feet) {
        return level != null && ContainerApproach.isStandable(level, feet)
            && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty();
    }

    private static boolean dryRoute(ServerLevel level, Path route) {
        for (int index = 0; index < route.getNodeCount(); index++) {
            BlockPos feet = route.getNode(index).asBlockPos();
            if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Starts only the owning Farmer's physical exit from its registered Farmhouse.
     * Callers keep authority for their subsequent field contact or sack anchor.
     */
    public static boolean stageExitFromOwnFarmhouse(ServerLevel level,
                                                     SettlerEntity worker,
                                                     @Nullable Building house,
                                                     BlockPos target, double speed) {
        if (house == null || !house.valid || house.type != BuildingType.FARMHOUSE
            || !house.workers.contains(worker.getUUID()) || house.bounds == null
            || !house.contains(worker.blockPosition()) || house.contains(target)) {
            return false;
        }
        var box = house.bounds;
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > MAX_HOUSE_SCAN) {
            return false;
        }
        // Only the registered employer's boundary is considered. No chunk is
        // loaded, unrelated doors are never opened, and a live stand/path is
        // required on the interior before requesting normal door-goal passage.
        for (BlockPos cursor : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ())) {
            if (cursor.getX() != box.minX() && cursor.getX() != box.maxX()
                && cursor.getZ() != box.minZ() && cursor.getZ() != box.maxZ()) {
                continue;
            }
            if (!WorkZoneService.livePositionAvailable(level, cursor)
                || !WorkZoneService.livePositionAvailable(level, cursor.above())) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            if (!(state.getBlock() instanceof DoorBlock)
                || !DoorBlock.isWoodenDoor(level, cursor)
                || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (direction.getAxis() != state.getValue(DoorBlock.FACING).getAxis()) {
                    continue;
                }
                BlockPos inside = ContainerApproach.standableDoorSide(level,
                    cursor.relative(direction));
                BlockPos outside = ContainerApproach.standableDoorSide(level,
                    cursor.relative(direction.getOpposite()));
                if (inside == null || outside == null || !house.contains(inside)
                    || house.contains(outside)) {
                    continue;
                }
                if (worker.distanceToSqr(Vec3.atBottomCenterOf(inside)) <= 2.25D) {
                    // A normal path across the exact threshold lets the door
                    // goal own opening, closing and collision recomputation.
                    BlockPos started = ContainerApproach.startPathToStandTargets(
                        level, worker, Set.of(outside), speed);
                    if (!state.getValue(DoorBlock.OPEN)) {
                        worker.requestDoorPassage(cursor.immutable());
                        return true;
                    }
                    if (started != null) {
                        return true;
                    }
                } else if (ContainerApproach.startPathToStandTargets(level,
                        worker, Set.of(inside), speed) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private FarmWorkApproach() {}
}
