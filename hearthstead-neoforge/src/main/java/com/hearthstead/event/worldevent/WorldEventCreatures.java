package com.hearthstead.event.worldevent;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.workzone.WorkZone;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Shared world queries for the event creatures: fields, livestock and safe spawn spots. */
public final class WorldEventCreatures {
    /** Bounded scan: never more blocks than this per query. */
    private static final int SCAN_BUDGET = 9_000;

    private WorldEventCreatures() {
    }

    public static boolean isLivestock(LivingEntity entity) {
        return entity.isAlive() && isLivestockType(entity);
    }

    /** Type check only (also true for a livestock animal that is dying right now). */
    public static boolean isLivestockType(LivingEntity entity) {
        EntityType<?> type = entity.getType();
        return type == EntityType.COW || type == EntityType.SHEEP || type == EntityType.PIG
            || type == EntityType.CHICKEN || type == EntityType.GOAT;
    }

    public static List<LivingEntity> livestock(ServerLevel level, Settlement settlement) {
        int r = Math.max(16, settlement.radius);
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(settlement.center).inflate(r, 16, r),
            WorldEventCreatures::isLivestock);
    }

    /** The settlement an event actor belongs to, from its event tag. */
    @Nullable
    public static Settlement settlementOf(ServerLevel level, Entity entity) {
        UUID id = WorldEventDirector.tagSettlement(entity);
        return id == null ? null : SettlementManager.byId(level, id);
    }

    /** Crop blocks inside the settlement's farm work zones (bounded scan). */
    public static List<BlockPos> crops(ServerLevel level, Settlement settlement, boolean matureOnly, int limit) {
        List<BlockPos> out = new ArrayList<>();
        int budget = SCAN_BUDGET;
        for (Building building : settlement.buildings) {
            if (!building.valid || building.type != BuildingType.FARMHOUSE) continue;
            WorkZone zone = building.workZone().orElse(null);
            if (zone == null || !zone.dimension().equals(level.dimension().location())) continue;
            BlockPos min = zone.min(), max = zone.max();
            int top = Math.min(max.getY(), min.getY() + 6);
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    for (int y = min.getY(); y <= top; y++) {
                        if (--budget < 0) return out;
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        BlockState state = level.getBlockState(pos);
                        if (state.getBlock() instanceof CropBlock crop
                            && (matureOnly ? crop.isMaxAge(state) : crop.getAge(state) > 0)) {
                            out.add(pos);
                            if (out.size() >= limit) return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    public static boolean hasFields(ServerLevel level, Settlement settlement) {
        return crops(level, settlement, false, 3).size() >= 3;
    }

    /** Nearest suitable crop to {@code mob} in its settlement's farm zones. */
    @Nullable
    public static BlockPos nearestCrop(ServerLevel level, Entity mob, boolean matureOnly) {
        Settlement settlement = settlementOf(level, mob);
        if (settlement == null) return null;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : crops(level, settlement, matureOnly, 64)) {
            double d = pos.distSqr(mob.blockPosition());
            if (d < bestDistance) { bestDistance = d; best = pos; }
        }
        return best;
    }

    /** Average position of a list of positions; null when empty. */
    @Nullable
    public static BlockPos centroid(List<BlockPos> positions) {
        if (positions.isEmpty()) return null;
        long x = 0, y = 0, z = 0;
        for (BlockPos pos : positions) { x += pos.getX(); y += pos.getY(); z += pos.getZ(); }
        int n = positions.size();
        return new BlockPos((int) (x / n), (int) (y / n), (int) (z / n));
    }

    /**
     * A safe open standing spot on a ring around {@code origin}: loaded,
     * dry, sturdy floor, room for {@code width x height}, not inside any
     * registered building. Null when none of the tried columns fit.
     */
    @Nullable
    public static BlockPos ringSpot(ServerLevel level, Settlement settlement, BlockPos origin,
                                    int minRadius, int maxRadius, float width, float height, RandomSource random) {
        int rotation = random.nextInt(24);
        for (int i = 0; i < 24; i++) {
            double angle = (rotation + i) * Math.PI / 12.0D;
            int radius = minRadius + (maxRadius > minRadius ? random.nextInt(maxRadius - minRadius + 1) : 0);
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos column = new BlockPos(x, origin.getY(), z);
            if (!level.hasChunkAt(column)) continue;
            BlockPos feet = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (Math.abs(feet.getY() - origin.getY()) > 12) continue;
            if (!safeFeet(level, feet, width, height)) continue;
            // Open footing only (survival QA #5): headroom, not on leaves, and a
            // flat neighbour on every side so the spot is no 1-wide notch or pit.
            if (!level.getBlockState(feet.above(2)).getCollisionShape(level, feet.above(2)).isEmpty()
                || level.getBlockState(feet.below()).is(net.minecraft.tags.BlockTags.LEAVES)
                || !openAround(level, feet, width, height)) continue;
            if (settlement != null && insideBuilding(settlement, feet)) continue;
            return feet;
        }
        return null;
    }

    /** Nearest safe standing cell to {@code column}, scanning a few blocks up and down. */
    @Nullable
    public static BlockPos nearFeet(ServerLevel level, BlockPos column, float width, float height) {
        for (int offset = 0; offset <= 5; offset++) {
            BlockPos up = column.above(offset);
            if (safeFeet(level, up, width, height)) return up;
            if (offset > 0) {
                BlockPos down = column.below(offset);
                if (safeFeet(level, down, width, height)) return down;
            }
        }
        return null;
    }

    public static boolean safeFeet(ServerLevel level, BlockPos feet, float width, float height) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())
            || !level.getWorldBorder().isWithinBounds(feet)
            || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()
            || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
            return false;
        }
        double half = width / 2.0D;
        return level.noCollision(new AABB(feet.getX() + .5 - half, feet.getY(), feet.getZ() + .5 - half,
            feet.getX() + .5 + half, feet.getY() + height, feet.getZ() + .5 + half));
    }

    /** Every horizontal neighbour is standable at the same height: no notch, no pit. */
    static boolean openAround(ServerLevel level, BlockPos feet, float width, float height) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!safeFeet(level, feet.relative(side), Math.min(width, 0.9F), height)) return false;
        }
        return true;
    }

    public static boolean insideBuilding(Settlement settlement, BlockPos pos) {
        for (Building building : settlement.buildings) {
            if (building.valid && building.contains(pos)) return true;
        }
        return false;
    }
}
