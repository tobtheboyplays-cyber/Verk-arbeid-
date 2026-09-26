package com.hearthstead.settlement;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

/**
 * One building inside a settlement, declared by a plaque.
 *
 * <p>A building exists because a player hung a plaque and the room around it
 * satisfied that plaque's type. The plaque position is therefore identity, not
 * decoration: lose the plaque and the building is gone. Everything else here
 * — occupancy, quality, validity — is re-derived from scans, so this record
 * stays the single authority the plaque reads from rather than a cache beside
 * one.
 */
public class Building {

    /** Root settlement schema that first owns every building target ledger. */
    static final int TARGET_BLESSINGS_SCHEMA_VERSION = 3;
    /** Root schema that first owns strict optional Work Zone state. */
    static final int WORK_ZONE_SCHEMA_VERSION = 5;

    public TavernServingClaims tavernServingClaims = new TavernServingClaims();

    public final UUID id;
    public BuildingType type;
    /** Where the declaring plaque hangs. Identity, not decoration. */
    public BlockPos plaquePos;
    /** Bought from the architect; higher levels demand more of the room. */
    public int level = 1;
    /**
     * Unmet checklist items of level + 1, re-derived by every plaque survey
     * (Builder lane, checklist levels). Runtime only, never persisted: the
     * next scan after a load rebuilds it, exactly like {@link #level}.
     */
    public List<com.hearthstead.building.BuildingLevelChecklist.Gap> nextLevelGap = List.of();
    /**
     * Warehouse only: the level an old save is grandfathered to, so an
     * existing big warehouse never shrinks (see WarehouseLevels). New
     * buildings start at 1. {@link #WAREHOUSE_FLOOR_PENDING} marks a save
     * written before warehouse levels existed; the container index resolves
     * it from the first complete count.
     */
    public int warehouseLevelFloor = 1;
    public static final int WAREHOUSE_FLOOR_PENDING = -1;
    /** Most player container marks one building keeps. */
    public static final int MAX_CONTAINER_MARKS = 256;
    /**
     * Player marks on single containers (packed BlockPos to
     * WarehouseLevels.MARK_PRIORITY / MARK_EXCLUDED). Persisted, bounded.
     */
    public final java.util.Map<Long, Byte> containerMarks =
        new java.util.LinkedHashMap<>();
    /**
     * Runtime only: highest warehouse level the settlement's Logistics tree
     * recognises; 0 until WarehouseLevelService first syncs it.
     */
    public int warehouseTechMax;
    /** Settlers employed here (work buildings); homes leave this empty. */
    public final List<UUID> workers = new ArrayList<>();
    /**
     * Persistent equipment intent for this workplace. Items never live in
     * this list; its rows only name what a worker still needs and who is
     * currently carrying it.
     */
    public final List<EquipmentRequest> equipmentRequests = new ArrayList<>();
    /** Anchor: the first bed found; scans re-seed from here. */
    public BlockPos anchor;
    public BoundingBox bounds;
    public int interiorVolume;
    public final List<BlockPos> beds = new ArrayList<>();
    public int doorCount;
    public int lightSources;
    /** Distinct furnishing types found (capped); drives home quality. */
    public int furnishingScore;
    public boolean valid;
    public long lastValidatedGameTime;
    /**
     * Permanent Blessings bound to this building's physical plaque identity.
     * Direct enum lookups only: no world scan, block-entity cache, or tick hook.
     */
    private TargetBlessingState targetBlessings = new TargetBlessingState();
    /** Optional means truthful NO_WORK_ZONE; quarantine means corrupt state. */
    private WorkZone workZone;
    private int workZoneRevision;
    private boolean workZoneQuarantined;

    public Building(UUID id, BuildingType type, BlockPos plaquePos,
                    BlockPos anchor, BoundingBox bounds) {
        this.id = id;
        this.type = type;
        this.plaquePos = plaquePos;
        this.anchor = anchor;
        this.bounds = bounds;
    }

    public int quality() {
        return Math.min(10, furnishingScore + Math.min(2, lightSources));
    }

    public boolean contains(BlockPos pos) {
        return bounds != null && bounds.isInside(pos);
    }

    /** Server-authoritative endpoint used by a physical Blessing Seal. */
    public TargetBlessingState.ApplyResult applyBlessing(BlessingId blessing) {
        return applyBlessing(blessing, 1);
    }

    public TargetBlessingState.ApplyResult applyBlessing(BlessingId blessing, int rankUnits) {
        return targetBlessings.apply(blessing, rankUnits);
    }

    /** Constant-time permanent rank lookup for gameplay and presentation hooks. */
    public int blessingRank(BlessingId blessing) {
        return targetBlessings.rank(blessing);
    }

    /** Whether strict NBT decoding quarantined this building's ledger. */
    public boolean blessingStateQuarantined() {
        return targetBlessings.quarantined();
    }

    public Optional<WorkZone> workZone() {
        return workZoneQuarantined ? Optional.empty()
            : Optional.ofNullable(workZone);
    }

    public int workZoneRevision() {
        return workZoneRevision;
    }

    public boolean workZoneQuarantined() {
        return workZoneQuarantined;
    }

    /** Parent settlement identity/bounds are available only after decode. */
    void validateWorkZoneOwner(Settlement settlement) {
        if (!workZoneQuarantined && workZone != null
            && (settlement == null
                || !workZone.settlementId().equals(settlement.id))) {
            workZone = null;
            workZoneQuarantined = true;
        }
    }

    /**
     * Level-aware load gate. A dimension-mismatched zone is corrupt state,
     * never a portable claim; quarantine is sticky and keeps the last
     * monotonic revision so a restart cannot silently mint revision zero.
     */
    boolean quarantineWorkZoneDimension(ResourceLocation expectedDimension) {
        if (!workZoneQuarantined && workZone != null
            && (expectedDimension == null
                || !expectedDimension.equals(workZone.dimension()))) {
            workZone = null;
            workZoneQuarantined = true;
            return true;
        }
        return false;
    }

    /** Compare-and-commit endpoint. Preview/cancel never call this method. */
    public boolean commitWorkZone(int expectedRevision, WorkZone next) {
        if (workZoneQuarantined || next == null
            || expectedRevision != workZoneRevision
            || next.revision() != expectedRevision + 1
            || !id.equals(next.buildingId())
            || next.type().buildingType() != type) {
            return false;
        }
        workZone = next;
        workZoneRevision = next.revision();
        return true;
    }

    /** Saved warehouse floor: absent or pending stays pending, a real level is clamped to 1..5. */
    static int readWarehouseLevelFloor(CompoundTag tag) {
        if (!tag.contains("WarehouseLevelFloor", Tag.TAG_INT)) {
            return WAREHOUSE_FLOOR_PENDING;
        }
        int saved = tag.getInt("WarehouseLevelFloor");
        return saved == WAREHOUSE_FLOOR_PENDING ? WAREHOUSE_FLOOR_PENDING : Math.max(1, Math.min(5, saved));
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.put("TavernServingClaims", tavernServingClaims.save());
        tag.putString("Type", type.id());
        tag.put("Plaque", NbtUtils.writeBlockPos(plaquePos));
        tag.putInt("Level", level);
        tag.putInt("WarehouseLevelFloor", warehouseLevelFloor);
        if (!containerMarks.isEmpty()) {
            long[] markPos = new long[containerMarks.size()];
            byte[] markKind = new byte[containerMarks.size()];
            int m = 0;
            for (var mark : containerMarks.entrySet()) {
                markPos[m] = mark.getKey();
                markKind[m] = mark.getValue();
                m++;
            }
            tag.putLongArray("ContainerMarkPos", markPos);
            tag.putByteArray("ContainerMarkKind", markKind);
        }
        ListTag workerList = new ListTag();
        for (UUID worker : workers) {
            CompoundTag w = new CompoundTag();
            w.putUUID("Id", worker);
            workerList.add(w);
        }
        tag.put("Workers", workerList);
        ListTag requestList = new ListTag();
        for (EquipmentRequest request : equipmentRequests) {
            requestList.add(request.writeNbt());
        }
        tag.put("EquipmentRequests", requestList);
        tag.put("Anchor", NbtUtils.writeBlockPos(anchor));
        tag.putIntArray("Bounds", new int[]{
            bounds.minX(), bounds.minY(), bounds.minZ(),
            bounds.maxX(), bounds.maxY(), bounds.maxZ()});
        tag.putInt("Volume", interiorVolume);
        ListTag bedList = new ListTag();
        for (BlockPos bed : beds) {
            bedList.add(new net.minecraft.nbt.IntArrayTag(
                new int[]{bed.getX(), bed.getY(), bed.getZ()}));
        }
        tag.put("Beds", bedList);
        tag.putInt("Doors", doorCount);
        tag.putInt("Lights", lightSources);
        tag.putInt("Furnishing", furnishingScore);
        tag.putBoolean("Valid", valid);
        tag.put("TargetBlessings", targetBlessings.writeNbt());
        tag.putInt("WorkZoneSchema", WorkZone.DATA_VERSION);
        tag.putInt("WorkZoneRevision", workZoneRevision);
        tag.putBoolean("WorkZoneQuarantined", workZoneQuarantined);
        if (workZone != null && !workZoneQuarantined) {
            tag.put("WorkZone", workZone.writeNbt());
        }
        return tag;
    }

    /**
     * Reads a standalone current-schema building. Real world loads must use
     * {@link #readNbt(CompoundTag, int)} so the root settlement data version
     * remains the authority for whether an absent target ledger is legacy or
     * corruption.
     */
    public static Building readNbt(CompoundTag tag) {
        return readNbt(tag, SettlementSavedData.CURRENT_DATA_VERSION);
    }

    public static Building readNbt(CompoundTag tag, int sourceVersion) {
        if (sourceVersion < 0
            || sourceVersion > SettlementSavedData.CURRENT_DATA_VERSION) {
            throw new SettlementSavedData.DataVersionException(
                "Unsupported building source version " + sourceVersion);
        }
        int[] b = tag.getIntArray("Bounds");
        BoundingBox bounds = b.length == 6
            ? new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5])
            : new BoundingBox(BlockPos.ZERO);
        Building building = new Building(tag.getUUID("Id"),
            BuildingType.byId(tag.getString("Type")),
            NbtUtils.readBlockPos(tag, "Plaque").orElse(BlockPos.ZERO),
            NbtUtils.readBlockPos(tag, "Anchor").orElse(BlockPos.ZERO), bounds);
        building.level = Math.max(1, tag.getInt("Level"));
        // Absent = written before warehouse levels: grandfather on first
        // complete container count (WarehouseIndex), never shrink.
        // A still-pending grandfather (-1) must survive a save/load before the
        // first complete scan (BH-14); only a real level is clamped.
        building.warehouseLevelFloor = readWarehouseLevelFloor(tag);
        long[] markPos = tag.getLongArray("ContainerMarkPos");
        byte[] markKind = tag.getByteArray("ContainerMarkKind");
        for (int m = 0; m < Math.min(markPos.length, markKind.length)
                && building.containerMarks.size() < MAX_CONTAINER_MARKS; m++) {
            if (markKind[m] == 1 || markKind[m] == 2) {
                building.containerMarks.put(markPos[m], markKind[m]);
            }
        }
        building.tavernServingClaims = TavernServingClaims.load(tag.getCompound("TavernServingClaims"));
        ListTag workerList = tag.getList("Workers", Tag.TAG_COMPOUND);
        for (int i = 0; i < workerList.size(); i++) {
            building.workers.add(workerList.getCompound(i).getUUID("Id"));
        }
        ListTag requestList = tag.getList("EquipmentRequests", Tag.TAG_COMPOUND);
        for (int i = 0; i < requestList.size(); i++) {
            EquipmentRequest request = EquipmentRequest.readNbt(
                requestList.getCompound(i));
            if (request != null
                && request.destinationBuildingId().equals(building.id)
                && building.equipmentRequests.stream().noneMatch(existing ->
                    existing.id().equals(request.id())
                        || existing.requesterId().equals(request.requesterId()))) {
                building.equipmentRequests.add(request);
            }
        }
        building.interiorVolume = tag.getInt("Volume");
        ListTag bedList = tag.getList("Beds", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < bedList.size(); i++) {
            int[] p = bedList.getIntArray(i);
            if (p.length == 3) {
                building.beds.add(new BlockPos(p[0], p[1], p[2]));
            }
        }
        building.doorCount = tag.getInt("Doors");
        building.lightSources = tag.getInt("Lights");
        building.furnishingScore = tag.getInt("Furnishing");
        building.valid = tag.getBoolean("Valid");
        Tag rawBlessings = tag.get("TargetBlessings");
        if (rawBlessings == null
            && sourceVersion < TARGET_BLESSINGS_SCHEMA_VERSION) {
            // Root schemas v0-v2 predate per-target Blessing ownership, so an
            // absent ledger is the one legitimate empty migration. Once v3
            // owns this field, absence cannot silently reset permanent ranks.
            building.targetBlessings = new TargetBlessingState();
        } else if (rawBlessings instanceof CompoundTag blessingTag) {
            building.targetBlessings = TargetBlessingState.readNbt(blessingTag);
        } else {
            // Current-schema absence and every present wrong tag type are
            // corruption, never a fresh ledger that could accept replacement
            // ranks for free. TargetBlessingState persists this quarantine on
            // the next write, making the fail-closed decision sticky.
            building.targetBlessings = TargetBlessingState.quarantinedEmpty();
        }
        if (sourceVersion < WORK_ZONE_SCHEMA_VERSION) {
            // v0-v4 have no Work Zone field. This is the sole legitimate
            // absent-state migration and becomes an explicit revision-zero
            // no-zone record on the next save.
            building.workZone = null;
            building.workZoneRevision = 0;
            building.workZoneQuarantined = false;
        } else {
            int workZoneSchema = tag.contains("WorkZoneSchema", Tag.TAG_INT)
                ? tag.getInt("WorkZoneSchema") : -1;
            boolean headerValid = (workZoneSchema == 1
                    || workZoneSchema == WorkZone.DATA_VERSION)
                && tag.contains("WorkZoneRevision", Tag.TAG_INT)
                && tag.contains("WorkZoneQuarantined", Tag.TAG_BYTE);
            int revision = headerValid ? tag.getInt("WorkZoneRevision") : -1;
            boolean quarantined = !headerValid || revision < 0
                || revision >= Integer.MAX_VALUE
                || tag.getBoolean("WorkZoneQuarantined");
            WorkZone decoded = null;
            Tag rawZone = tag.get("WorkZone");
            if (!quarantined && rawZone != null) {
                if (rawZone instanceof CompoundTag zoneTag) {
                    // Header and nested payload advance together. Accepting a
                    // mixed pair would turn a torn/corrupt write into a
                    // legitimate migration.
                    decoded = zoneTag.contains("DataVersion", Tag.TAG_INT)
                            && zoneTag.getInt("DataVersion") == workZoneSchema
                        ? WorkZone.readNbt(zoneTag) : null;
                }
                quarantined = decoded == null
                    || decoded.revision() != revision
                    || !decoded.buildingId().equals(building.id)
                    || decoded.type().buildingType() != building.type;
            }
            // A nonzero revision with no current zone is unreachable because
            // this slice has no delete operation. It cannot silently reset.
            if (!quarantined && rawZone == null && revision != 0) {
                quarantined = true;
            }
            building.workZone = quarantined ? null : decoded;
            building.workZoneRevision = Math.max(0, revision);
            building.workZoneQuarantined = quarantined;
        }
        return building;
    }
}
