package com.hearthstead.settlement;

import com.hearthstead.entity.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One founded settlement. Lives in {@link SettlementSavedData}; the member
 * list is kept here (not only on the entities) so population and employment
 * stay correct even while settler chunks are unloaded.
 */
public class Settlement {
    public static final int DEFAULT_RADIUS = 48;
    public static final int BASE_CAPACITY = 8;

    public final UUID id;
    public String name;
    public BlockPos center;
    public int radius = DEFAULT_RADIUS;
    public final List<SettlerRecord> settlers = new ArrayList<>();
    /** Automatically detected buildings (homes first; more types later). */
    public final List<Building> buildings = new ArrayList<>();

    public int recruitProgress;
    public int recruitTarget;
    public long alertUntilGameTime;
    public BlockPos alertPos;
    /** A traveler currently walking toward the hearth, if any. */
    public UUID travelerId;
    public long travelerSinceGameTime;

    /** Refreshed every second by the hearth block entity. */
    public int foodCache;
    /**
     * How badly the world wants to raid this place tonight. Persisted, and
     * the only thing that decides whether a raid happens — there is no night
     * counter with a safe floor (see RaidPressure).
     */
    public final com.hearthstead.settlement.raid.RaidPressure raidPressure =
        new com.hearthstead.settlement.raid.RaidPressure();

    /**
     * Enemies this settlement has met, and the raid it is currently
     * expecting. This is the Tingbok's enemy gallery: captains persist so a
     * beaten one can come back harder and by a different road, which is the
     * tail MineColonies' own feature requests (#113, #129) keep asking for.
     */
    public final List<com.hearthstead.settlement.raid.RaidCaptain> raidCaptains =
        new ArrayList<>();
    public com.hearthstead.settlement.raid.RaidPlan pendingRaid;
    /**
     * Set when a laden raider gets clear of the settlement. Decides whether
     * the raid resolves as repelled or lost, which is the difference between
     * pressure rising and easing.
     */
    public boolean raidLootEscaped;

    /** Average morale of currently loaded members, refreshed every second. */
    public int moraleCache = 60;
    /** Who speaks for the settlement. See {@link Mayor}. Null when leaderless. */
    @javax.annotation.Nullable
    public UUID mayorId;
    /** Game time the current mayor took office; a new one settles in slowly. */
    public long mayorSince;
    /** Game time until which the settlement is mourning and cannot appoint. */
    public long mourningUntil;

    public Settlement(UUID id, String name, BlockPos center) {
        this.id = id;
        this.name = name;
        this.center = center;
    }

    /** Three founders shelter at the hearth; growth beyond that needs beds. */
    public int capacity() {
        return 3 + validBedCount();
    }

    public int validBedCount() {
        int beds = 0;
        for (Building b : buildings) {
            if (b.valid && b.type.housesResidents()) {
                beds += b.beds.size();
            }
        }
        return beds;
    }

    public int validHomeCount() {
        int homes = 0;
        for (Building b : buildings) {
            if (b.valid && b.type.housesResidents()) {
                homes++;
            }
        }
        return homes;
    }

    public int population() {
        return settlers.size();
    }

    public int employed() {
        int n = 0;
        for (SettlerRecord r : settlers) {
            if (r.profession.employed()) {
                n++;
            }
        }
        return n;
    }

    public boolean alertActive(long gameTime) {
        return gameTime < alertUntilGameTime;
    }

    public boolean inside(BlockPos pos) {
        return pos.distSqr(center) <= (double) radius * radius;
    }

    public SettlerRecord record(UUID entityId) {
        for (SettlerRecord r : settlers) {
            if (r.entityId.equals(entityId)) {
                return r;
            }
        }
        return null;
    }

    /** Idempotent: updates the existing record instead of duplicating it. */
    public void putRecord(UUID entityId, String settlerName, Profession profession) {
        SettlerRecord r = record(entityId);
        if (r == null) {
            settlers.add(new SettlerRecord(entityId, settlerName, profession));
        } else {
            r.name = settlerName;
            r.profession = profession;
        }
    }

    public boolean removeRecord(UUID entityId) {
        return settlers.removeIf(r -> r.entityId.equals(entityId));
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (mayorId != null) {
            tag.putUUID("MayorId", mayorId);
        }
        tag.putLong("MayorSince", mayorSince);
        tag.putLong("MourningUntil", mourningUntil);
        tag.putString("Name", name);
        tag.put("Center", NbtUtils.writeBlockPos(center));
        tag.putInt("Radius", radius);
        tag.putInt("RecruitProgress", recruitProgress);
        tag.putInt("RecruitTarget", recruitTarget);
        tag.putLong("AlertUntil", alertUntilGameTime);
        if (alertPos != null) {
            tag.put("AlertPos", NbtUtils.writeBlockPos(alertPos));
        }
        if (travelerId != null) {
            tag.putUUID("TravelerId", travelerId);
            tag.putLong("TravelerSince", travelerSinceGameTime);
        }
        ListTag list = new ListTag();
        for (SettlerRecord r : settlers) {
            CompoundTag rt = new CompoundTag();
            rt.putUUID("EntityId", r.entityId);
            rt.putString("Name", r.name);
            rt.putByte("Profession", r.profession.id());
            list.add(rt);
        }
        tag.put("Settlers", list);
        ListTag buildingList = new ListTag();
        for (Building b : buildings) {
            buildingList.add(b.writeNbt());
        }
        tag.put("Buildings", buildingList);
        tag.put("RaidPressure", raidPressure.writeNbt());
        ListTag captainList = new ListTag();
        for (com.hearthstead.settlement.raid.RaidCaptain c : raidCaptains) {
            captainList.add(c.writeNbt());
        }
        tag.put("RaidCaptains", captainList);
        if (pendingRaid != null) {
            tag.put("PendingRaid", pendingRaid.writeNbt());
        }
        tag.putBoolean("RaidLootEscaped", raidLootEscaped);
        return tag;
    }

    public static Settlement readNbt(CompoundTag tag) {
        Settlement s = new Settlement(tag.getUUID("Id"), tag.getString("Name"),
            NbtUtils.readBlockPos(tag, "Center").orElse(BlockPos.ZERO));
        s.radius = tag.getInt("Radius");
        if (s.radius <= 0) {
            s.radius = DEFAULT_RADIUS;
        }
        s.mayorId = tag.hasUUID("MayorId") ? tag.getUUID("MayorId") : null;
        s.mayorSince = tag.getLong("MayorSince");
        s.mourningUntil = tag.getLong("MourningUntil");
        s.recruitProgress = tag.getInt("RecruitProgress");
        s.recruitTarget = tag.getInt("RecruitTarget");
        s.alertUntilGameTime = tag.getLong("AlertUntil");
        if (tag.contains("AlertPos")) {
            s.alertPos = NbtUtils.readBlockPos(tag, "AlertPos").orElse(null);
        }
        if (tag.hasUUID("TravelerId")) {
            s.travelerId = tag.getUUID("TravelerId");
            s.travelerSinceGameTime = tag.getLong("TravelerSince");
        }
        ListTag list = tag.getList("Settlers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag rt = list.getCompound(i);
            s.settlers.add(new SettlerRecord(rt.getUUID("EntityId"), rt.getString("Name"),
                Profession.byId(rt.getByte("Profession"))));
        }
        ListTag buildingList = tag.getList("Buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingList.size(); i++) {
            s.buildings.add(Building.readNbt(buildingList.getCompound(i)));
        }
        if (tag.contains("RaidPressure")) {
            s.raidPressure.copyFrom(com.hearthstead.settlement.raid.RaidPressure
                .readNbt(tag.getCompound("RaidPressure")));
        }
        ListTag captainList = tag.getList("RaidCaptains", Tag.TAG_COMPOUND);
        for (int i = 0; i < captainList.size(); i++) {
            s.raidCaptains.add(com.hearthstead.settlement.raid.RaidCaptain
                .readNbt(captainList.getCompound(i)));
        }
        s.raidLootEscaped = tag.getBoolean("RaidLootEscaped");
        if (tag.contains("PendingRaid")) {
            s.pendingRaid = com.hearthstead.settlement.raid.RaidPlan
                .readNbt(tag.getCompound("PendingRaid"));
        }
        return s;
    }

    public static class SettlerRecord {
        public final UUID entityId;
        public String name;
        public Profession profession;

        public SettlerRecord(UUID entityId, String name, Profession profession) {
            this.entityId = entityId;
            this.name = name;
            this.profession = profession;
        }
    }
}
