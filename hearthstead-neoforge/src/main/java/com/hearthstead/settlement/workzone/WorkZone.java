package com.hearthstead.settlement.workzone;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One exact, player-confirmed three-dimensional workplace volume.
 *
 * <p>The record carries every identity needed to reject a stale or displaced
 * copy after a restart. Bounds are inclusive block coordinates; callers must
 * use {@link #contains(BlockPos)} before reading or mutating a work target.
 */
public record WorkZone(UUID settlementId, UUID buildingId, Type type,
                       ResourceLocation dimension, BlockPos min,
                       BlockPos max, int revision) {
    /**
     * v2 makes a Farm Zone a real three-dimensional growing volume. Legacy
     * v1 farm selections were always saved as one flat Y layer even though
     * FarmerWorkGoal correctly requires both the soil and the crop/air above
     * it. Those old zones are migrated, never silently discarded.
     */
    public static final int DATA_VERSION = 2;
    private static final int LEGACY_FLAT_DATA_VERSION = 1;
    private static final int LEGACY_FARM_HEADROOM = 3;
    /**
     * Frozen persistence limits. Runtime validation applies the same limits
     * before commit; decoding repeats them so forged/current NBT can never
     * become an enormous AI scan after restart.
     */
    public static final int MAX_PERSISTED_SIZE_X = 48;
    public static final int MAX_PERSISTED_SIZE_Y = 64;
    public static final int MAX_PERSISTED_SIZE_Z = 48;
    public static final long MAX_PERSISTED_VOLUME = 16_384L;

    public enum Type {
        LUMBER(0, "lumber", BuildingType.LUMBER_CAMP, Profession.LUMBERER),
        FARM(1, "farm", BuildingType.FARMHOUSE, Profession.FARMER);

        private final int wireId;
        private final String id;
        private final BuildingType buildingType;
        private final Profession profession;

        Type(int wireId, String id, BuildingType buildingType,
             Profession profession) {
            this.wireId = wireId;
            this.id = id;
            this.buildingType = buildingType;
            this.profession = profession;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        public BuildingType buildingType() {
            return buildingType;
        }

        public Profession profession() {
            return profession;
        }

        @Nullable
        public static Type fromWireId(int wireId) {
            return wireId == 0 ? LUMBER : wireId == 1 ? FARM : null;
        }

        @Nullable
        public static Type fromBuilding(BuildingType buildingType) {
            for (Type value : values()) {
                if (value.buildingType == buildingType) {
                    return value;
                }
            }
            return null;
        }
    }

    public WorkZone {
        if (settlementId == null || buildingId == null || type == null
            || dimension == null || min == null || max == null
            || revision <= 0
            || min.getX() > max.getX() || min.getY() > max.getY()
            || min.getZ() > max.getZ()) {
            throw new IllegalArgumentException("Malformed Work Zone identity or bounds");
        }
        min = min.immutable();
        max = max.immutable();
    }

    /** Creates normalized inclusive bounds for a successful server commit. */
    public static WorkZone between(UUID settlementId, UUID buildingId,
                                   Type type, ResourceLocation dimension,
                                   BlockPos first, BlockPos second,
                                   int revision) {
        BlockPos min = new BlockPos(Math.min(first.getX(), second.getX()),
            Math.min(first.getY(), second.getY()),
            Math.min(first.getZ(), second.getZ()));
        BlockPos max = new BlockPos(Math.max(first.getX(), second.getX()),
            Math.max(first.getY(), second.getY()),
            Math.max(first.getZ(), second.getZ()));
        return new WorkZone(settlementId, buildingId, type, dimension,
            min, max, revision);
    }

    public boolean contains(BlockPos pos) {
        return pos != null
            && pos.getX() >= min.getX() && pos.getX() <= max.getX()
            && pos.getY() >= min.getY() && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int sizeY() {
        return max.getY() - min.getY() + 1;
    }

    public int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Overflow-safe structural gate shared by strict NBT and server commit. */
    public boolean withinPersistentLimits() {
        long sx = (long) max.getX() - min.getX() + 1L;
        long sy = (long) max.getY() - min.getY() + 1L;
        long sz = (long) max.getZ() - min.getZ() + 1L;
        return sx > 0L && sy > 0L && sz > 0L
            && sx <= MAX_PERSISTED_SIZE_X
            && sy <= MAX_PERSISTED_SIZE_Y
            && sz <= MAX_PERSISTED_SIZE_Z
            && sx * sy * sz <= MAX_PERSISTED_VOLUME;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putUUID("Settlement", settlementId);
        tag.putUUID("Building", buildingId);
        tag.putInt("TypeWireId", type.wireId());
        tag.putString("Type", type.id());
        tag.putString("Dimension", dimension.toString());
        tag.put("Min", NbtUtils.writeBlockPos(min));
        tag.put("Max", NbtUtils.writeBlockPos(max));
        tag.putInt("Revision", revision);
        return tag;
    }

    /** Strict decoder. A null result means the present state is corrupt. */
    @Nullable
    public static WorkZone readNbt(@Nullable CompoundTag tag) {
        if (tag == null
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || !tag.hasUUID("Settlement") || !tag.hasUUID("Building")
            || !tag.contains("TypeWireId", Tag.TAG_INT)
            || !tag.contains("Type", Tag.TAG_STRING)
            || !tag.contains("Dimension", Tag.TAG_STRING)
            || !tag.contains("Min", Tag.TAG_INT_ARRAY)
            || !tag.contains("Max", Tag.TAG_INT_ARRAY)
            || !tag.contains("Revision", Tag.TAG_INT)) {
            return null;
        }
        int sourceVersion = tag.getInt("DataVersion");
        if (sourceVersion != LEGACY_FLAT_DATA_VERSION
            && sourceVersion != DATA_VERSION) {
            return null;
        }
        Type type = Type.fromWireId(tag.getInt("TypeWireId"));
        ResourceLocation dimension = ResourceLocation.tryParse(
            tag.getString("Dimension"));
        BlockPos min = NbtUtils.readBlockPos(tag, "Min").orElse(null);
        BlockPos max = NbtUtils.readBlockPos(tag, "Max").orElse(null);
        int revision = tag.getInt("Revision");
        if (type == null || !type.id().equals(tag.getString("Type"))
            || dimension == null || min == null || max == null) {
            return null;
        }
        if (sourceVersion == LEGACY_FLAT_DATA_VERSION
            && type == Type.FARM && min.getY() == max.getY()) {
            if (max.getY() > Integer.MAX_VALUE - LEGACY_FARM_HEADROOM) {
                return null;
            }
            max = max.above(LEGACY_FARM_HEADROOM);
        } else if (sourceVersion == DATA_VERSION
            && type == Type.FARM && min.getY() == max.getY()) {
            // v2 writers know Farm Zones need a soil layer and headroom.
            // A flat current-schema value can only be forged/corrupt state.
            return null;
        }
        try {
            WorkZone decoded = new WorkZone(tag.getUUID("Settlement"),
                tag.getUUID("Building"), type, dimension, min, max, revision);
            return decoded.withinPersistentLimits() ? decoded : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
