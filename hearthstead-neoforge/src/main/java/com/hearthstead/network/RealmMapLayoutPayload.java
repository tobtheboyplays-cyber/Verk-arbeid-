package com.hearthstead.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to client: the slow-changing half of the realm map for one
 * settlement -- claim, building footprints and the recorded roster.
 *
 * <p>Sent when a map subscription starts and again only when its content
 * hash changes (a plaque hung, a worker hired, a settler renamed, the
 * alarm bell found or lost). Every list
 * and string is bounded on both encode and decode, so a client can neither
 * request nor be sent an unbounded projection. It carries exactly one
 * settlement: the one whose Banner menu the receiving player has open.
 */
public record RealmMapLayoutPayload(UUID settlementId, int revision,
                                    int centerX, int centerY, int centerZ, int radius,
                                    UUID mayorId, boolean hasBell, int bellX, int bellZ,
                                    int nextRaidInDays, boolean raidWarned, int raidHoldReason,
                                    List<BuildingEntry> buildings, List<RosterEntry> roster)
    implements CustomPacketPayload {

    public static final int MAX_BUILDINGS = 256;
    public static final int MAX_ROSTER = 256;
    public static final int MAX_TEXT = 48;
    public static final int NO_BUILDING = -1;
    public static final UUID NO_ID = new UUID(0L, 0L);
    /** {@link #nextRaidInDays} when no recurring raid night is scheduled. */
    public static final int NO_RAID = -1;
    /** {@link #raidHoldReason} when no raid is being held tonight. */
    public static final int NO_HOLD = -1;

    public static final Type<RealmMapLayoutPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "realm_map_layout"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RealmMapLayoutPayload> CODEC =
        StreamCodec.of(RealmMapLayoutPayload::write, RealmMapLayoutPayload::read);

    /**
     * One building footprint in block coordinates (inclusive min/max). The
     * worker list is not repeated here: roster entries name their workplace
     * by index, which keeps the two lists consistent by construction.
     */
    public record BuildingEntry(UUID id, String typeId, int level, int minX, int minZ,
                                int maxX, int maxZ, int plaqueX, int plaqueZ,
                                boolean valid, int workerCapacity) {
        public BuildingEntry {
            id = id == null ? NO_ID : id;
            typeId = bounded(typeId);
            level = Math.max(0, Math.min(99, level));
            if (maxX < minX) { int t = minX; minX = maxX; maxX = t; }
            if (maxZ < minZ) { int t = minZ; minZ = maxZ; maxZ = t; }
            workerCapacity = Math.max(0, Math.min(99, workerCapacity));
        }
    }

    /** One recorded settlement member; {@code appearanceSeed} is -1 when unknown (unloaded). */
    public record RosterEntry(UUID id, String name, int professionId, int workBuilding,
                              int appearanceSeed, boolean loaded) {
        public RosterEntry {
            id = id == null ? NO_ID : id;
            name = bounded(name);
            professionId = Math.max(0, Math.min(255, professionId));
            workBuilding = Math.max(NO_BUILDING, Math.min(MAX_BUILDINGS - 1, workBuilding));
        }
    }

    public RealmMapLayoutPayload {
        settlementId = settlementId == null ? NO_ID : settlementId;
        mayorId = mayorId == null ? NO_ID : mayorId;
        nextRaidInDays = Math.max(NO_RAID, Math.min(99, nextRaidInDays));
        raidHoldReason = Math.max(NO_HOLD, Math.min(15, raidHoldReason));
        radius = Math.max(0, Math.min(1024, radius));
        buildings = buildings == null ? List.of()
            : List.copyOf(buildings.subList(0, Math.min(MAX_BUILDINGS, buildings.size())));
        roster = roster == null ? List.of()
            : List.copyOf(roster.subList(0, Math.min(MAX_ROSTER, roster.size())));
    }

    static String bounded(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    private static void write(RegistryFriendlyByteBuf buf, RealmMapLayoutPayload p) {
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeVarInt(p.revision);
        buf.writeVarInt(p.centerX);
        buf.writeVarInt(p.centerY);
        buf.writeVarInt(p.centerZ);
        buf.writeVarInt(p.radius);
        UUIDUtil.STREAM_CODEC.encode(buf, p.mayorId);
        buf.writeBoolean(p.hasBell);
        buf.writeVarInt(p.bellX);
        buf.writeVarInt(p.bellZ);
        buf.writeVarInt(p.nextRaidInDays + 1);
        buf.writeBoolean(p.raidWarned);
        buf.writeVarInt(p.raidHoldReason + 1);
        buf.writeVarInt(p.buildings.size());
        for (BuildingEntry b : p.buildings) {
            UUIDUtil.STREAM_CODEC.encode(buf, b.id());
            buf.writeUtf(b.typeId(), MAX_TEXT * 4);
            buf.writeVarInt(b.level());
            buf.writeVarInt(b.minX());
            buf.writeVarInt(b.minZ());
            buf.writeVarInt(b.maxX());
            buf.writeVarInt(b.maxZ());
            buf.writeVarInt(b.plaqueX());
            buf.writeVarInt(b.plaqueZ());
            buf.writeBoolean(b.valid());
            buf.writeVarInt(b.workerCapacity());
        }
        buf.writeVarInt(p.roster.size());
        for (RosterEntry r : p.roster) {
            UUIDUtil.STREAM_CODEC.encode(buf, r.id());
            buf.writeUtf(r.name(), MAX_TEXT * 4);
            buf.writeVarInt(r.professionId());
            buf.writeVarInt(r.workBuilding());
            buf.writeInt(r.appearanceSeed());
            buf.writeBoolean(r.loaded());
        }
    }

    private static RealmMapLayoutPayload read(RegistryFriendlyByteBuf buf) {
        UUID settlement = UUIDUtil.STREAM_CODEC.decode(buf);
        int revision = buf.readVarInt();
        int cx = buf.readVarInt();
        int cy = buf.readVarInt();
        int cz = buf.readVarInt();
        int radius = buf.readVarInt();
        UUID mayor = UUIDUtil.STREAM_CODEC.decode(buf);
        boolean hasBell = buf.readBoolean();
        int bellX = buf.readVarInt();
        int bellZ = buf.readVarInt();
        int nextRaid = buf.readVarInt() - 1;
        boolean warned = buf.readBoolean();
        int hold = buf.readVarInt() - 1;
        int buildingCount = boundedCount(buf.readVarInt(), MAX_BUILDINGS, "buildings");
        List<BuildingEntry> buildings = new ArrayList<>(buildingCount);
        for (int i = 0; i < buildingCount; i++) {
            buildings.add(new BuildingEntry(UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readUtf(MAX_TEXT * 4), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readVarInt()));
        }
        int rosterCount = boundedCount(buf.readVarInt(), MAX_ROSTER, "roster");
        List<RosterEntry> roster = new ArrayList<>(rosterCount);
        for (int i = 0; i < rosterCount; i++) {
            roster.add(new RosterEntry(UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readUtf(MAX_TEXT * 4), buf.readVarInt(), buf.readVarInt(),
                buf.readInt(), buf.readBoolean()));
        }
        return new RealmMapLayoutPayload(settlement, revision, cx, cy, cz, radius, mayor,
            hasBell, bellX, bellZ, nextRaid, warned, hold, buildings, roster);
    }

    static int boundedCount(int count, int max, String what) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("realm map " + what + " count out of range: " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
