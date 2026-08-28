package com.hearthstead.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import com.hearthstead.building.BuildingType;

import java.util.UUID;

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

    public final UUID id;
    public BuildingType type;
    /** Where the declaring plaque hangs. Identity, not decoration. */
    public BlockPos plaquePos;
    /** Bought from the architect; higher levels demand more of the room. */
    public int level = 1;
    /** Settlers employed here (work buildings); homes leave this empty. */
    public final List<UUID> workers = new ArrayList<>();
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

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Type", type.id());
        tag.put("Plaque", NbtUtils.writeBlockPos(plaquePos));
        tag.putInt("Level", level);
        ListTag workerList = new ListTag();
        for (UUID worker : workers) {
            CompoundTag w = new CompoundTag();
            w.putUUID("Id", worker);
            workerList.add(w);
        }
        tag.put("Workers", workerList);
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
        return tag;
    }

    public static Building readNbt(CompoundTag tag) {
        int[] b = tag.getIntArray("Bounds");
        BoundingBox bounds = b.length == 6
            ? new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5])
            : new BoundingBox(BlockPos.ZERO);
        Building building = new Building(tag.getUUID("Id"),
            BuildingType.byId(tag.getString("Type")),
            NbtUtils.readBlockPos(tag, "Plaque").orElse(BlockPos.ZERO),
            NbtUtils.readBlockPos(tag, "Anchor").orElse(BlockPos.ZERO), bounds);
        building.level = Math.max(1, tag.getInt("Level"));
        ListTag workerList = tag.getList("Workers", Tag.TAG_COMPOUND);
        for (int i = 0; i < workerList.size(); i++) {
            building.workers.add(workerList.getCompound(i).getUUID("Id"));
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
        return building;
    }
}
