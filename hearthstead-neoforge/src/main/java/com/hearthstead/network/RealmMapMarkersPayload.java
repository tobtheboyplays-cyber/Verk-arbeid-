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
 * Server to client, about twice a second while a Banner map is open: where
 * each loaded settler of the one subscribed settlement is and what it is
 * doing. x and z are offsets from the settlement's centre block (floats
 * stay exact near the claim at any world coordinate); y is absolute.
 *
 * <p>Positions come from the server's own loaded entities, so settlers far
 * outside the client's entity tracking range still move on the map. A
 * settler whose chunk is not loaded is simply absent (the roster still names
 * them); the server never loads a chunk to find one.
 *
 * <p>{@code coins} is the settlement's physical coin count (Banner plus
 * loaded warehouses), refreshed at a slower cadence; -1 means "not counted".
 * {@code focus} is present only for the settler the client asked to focus,
 * and only when that settler belongs to this settlement and is loaded.
 */
public record RealmMapMarkersPayload(UUID settlementId, int layoutRevision, long gameTime,
                                     int coins, List<Marker> markers, Focus focus, List<Raider> raiders,
                                     List<Talker> talkers)
    implements CustomPacketPayload {

    public static final int MAX_MARKERS = 256;
    public static final int MAX_BAG = 8;
    public static final int MAX_RAIDERS = 64;
    public static final int MAX_TALKERS = 32;
    public static final int MAX_TALKER_NAME = 48;
    public static final int MAX_TALKER_TITLE = 96;
    public static final UUID NO_ID = new UUID(0L, 0L);

    public static final Type<RealmMapMarkersPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "realm_map_markers"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RealmMapMarkersPayload> CODEC =
        StreamCodec.of(RealmMapMarkersPayload::write, RealmMapMarkersPayload::read);

    public record Marker(UUID id, int entityId, float x, float y, float z,
                         int professionId, int activityId, int status) {
        public Marker {
            id = id == null ? NO_ID : id;
            professionId = Math.max(0, Math.min(255, professionId));
            activityId = Math.max(0, Math.min(255, activityId));
            status = Math.max(0, Math.min(255, status));
        }
    }

    /** A loaded raider near the claim, centre-relative like markers. */
    public record Raider(int entityId, float x, float z, boolean captain) {
    }

    /**
     * Someone near the claim with something to say (the conversation
     * system's "!"): an event visitor, a parleying raid captain, a traveler.
     * Only unanswered, available talks are sent; centre-relative like markers.
     */
    public record Talker(int entityId, float x, float z, String name, String titleKey, int kind) {
        /** Badge kinds, the same numbers as the conversation markers: talk, trade, parley, quest. */
        public static final int TALK = 1;
        public static final int TRADE = 3;
        public static final int PARLEY = 4;
        public static final int QUEST = 5;

        public Talker {
            name = name == null ? "" : name.length() > MAX_TALKER_NAME ? name.substring(0, MAX_TALKER_NAME) : name;
            titleKey = titleKey == null || titleKey.length() > MAX_TALKER_TITLE ? "" : titleKey;
            kind = kind == TRADE || kind == PARLEY || kind == QUEST ? kind : TALK;
        }

        public Talker(int entityId, float x, float z, String name, String titleKey) {
            this(entityId, x, z, name, titleKey, TALK);
        }

        public Talker(int entityId, float x, float z, String name) {
            this(entityId, x, z, name, "", TALK);
        }
    }

    /** Source-compatible constructor: no talkers. */
    public RealmMapMarkersPayload(UUID settlementId, int layoutRevision, long gameTime, int coins,
                                  List<Marker> markers, Focus focus, List<Raider> raiders) {
        this(settlementId, layoutRevision, gameTime, coins, markers, focus, raiders, List.of());
    }

    /** Source-compatible constructor: no raiders. */
    public RealmMapMarkersPayload(UUID settlementId, int layoutRevision, long gameTime, int coins,
                                  List<Marker> markers, Focus focus) {
        this(settlementId, layoutRevision, gameTime, coins, markers, focus, List.of(), List.of());
    }

    /** One carried bag slot as a registry id and count (chest truth, not a display fiction). */
    public record BagSlot(int itemId, int count) {
        public BagSlot {
            itemId = Math.max(0, itemId);
            count = Math.max(0, Math.min(9999, count));
        }
    }

    public record Focus(UUID id, int level, int hunger, int energy, List<BagSlot> bag) {
        public static final Focus NONE = new Focus(NO_ID, 0, 0, 0, List.of());

        public Focus {
            id = id == null ? NO_ID : id;
            level = Math.max(0, Math.min(99, level));
            hunger = Math.max(0, Math.min(100, hunger));
            energy = Math.max(0, Math.min(100, energy));
            bag = bag == null ? List.of() : List.copyOf(bag.subList(0, Math.min(MAX_BAG, bag.size())));
        }

        public boolean present() {
            return !NO_ID.equals(id);
        }
    }

    public RealmMapMarkersPayload {
        settlementId = settlementId == null ? NO_ID : settlementId;
        coins = Math.max(-1, coins);
        markers = markers == null ? List.of()
            : List.copyOf(markers.subList(0, Math.min(MAX_MARKERS, markers.size())));
        focus = focus == null ? Focus.NONE : focus;
        raiders = raiders == null ? List.of()
            : List.copyOf(raiders.subList(0, Math.min(MAX_RAIDERS, raiders.size())));
        talkers = talkers == null ? List.of()
            : List.copyOf(talkers.subList(0, Math.min(MAX_TALKERS, talkers.size())));
    }

    private static void write(RegistryFriendlyByteBuf buf, RealmMapMarkersPayload p) {
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeVarInt(p.layoutRevision);
        buf.writeVarLong(p.gameTime);
        buf.writeVarInt(p.coins + 1);
        buf.writeVarInt(p.markers.size());
        for (Marker m : p.markers) {
            UUIDUtil.STREAM_CODEC.encode(buf, m.id());
            buf.writeVarInt(m.entityId());
            buf.writeFloat(m.x());
            buf.writeFloat(m.y());
            buf.writeFloat(m.z());
            buf.writeByte(m.professionId());
            buf.writeByte(m.activityId());
            buf.writeByte(m.status());
        }
        Focus f = p.focus;
        buf.writeBoolean(f.present());
        if (f.present()) {
            UUIDUtil.STREAM_CODEC.encode(buf, f.id());
            buf.writeByte(f.level());
            buf.writeByte(f.hunger());
            buf.writeByte(f.energy());
            buf.writeByte(f.bag().size());
            for (BagSlot slot : f.bag()) {
                buf.writeVarInt(slot.itemId());
                buf.writeVarInt(slot.count());
            }
        }
        buf.writeVarInt(p.raiders.size());
        for (Raider r : p.raiders) {
            buf.writeVarInt(r.entityId());
            buf.writeFloat(r.x());
            buf.writeFloat(r.z());
            buf.writeBoolean(r.captain());
        }
        buf.writeVarInt(p.talkers.size());
        for (Talker t : p.talkers) {
            buf.writeVarInt(t.entityId());
            buf.writeFloat(t.x());
            buf.writeFloat(t.z());
            buf.writeUtf(t.name(), MAX_TALKER_NAME);
            buf.writeUtf(t.titleKey(), MAX_TALKER_TITLE);
            buf.writeByte(t.kind());
        }
    }

    private static RealmMapMarkersPayload read(RegistryFriendlyByteBuf buf) {
        UUID settlement = UUIDUtil.STREAM_CODEC.decode(buf);
        int revision = buf.readVarInt();
        long gameTime = buf.readVarLong();
        int coins = buf.readVarInt() - 1;
        int count = RealmMapLayoutPayload.boundedCount(buf.readVarInt(), MAX_MARKERS, "markers");
        List<Marker> markers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            markers.add(new Marker(UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readUnsignedByte()));
        }
        Focus focus = Focus.NONE;
        if (buf.readBoolean()) {
            UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
            int level = buf.readUnsignedByte();
            int hunger = buf.readUnsignedByte();
            int energy = buf.readUnsignedByte();
            int bagSize = RealmMapLayoutPayload.boundedCount(buf.readUnsignedByte(), MAX_BAG, "bag");
            List<BagSlot> bag = new ArrayList<>(bagSize);
            for (int i = 0; i < bagSize; i++) {
                bag.add(new BagSlot(buf.readVarInt(), buf.readVarInt()));
            }
            focus = new Focus(id, level, hunger, energy, bag);
        }
        int raiderCount = RealmMapLayoutPayload.boundedCount(buf.readVarInt(), MAX_RAIDERS, "raiders");
        List<Raider> raiders = new ArrayList<>(raiderCount);
        for (int i = 0; i < raiderCount; i++) {
            raiders.add(new Raider(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));
        }
        int talkerCount = RealmMapLayoutPayload.boundedCount(buf.readVarInt(), MAX_TALKERS, "talkers");
        List<Talker> talkers = new ArrayList<>(talkerCount);
        for (int i = 0; i < talkerCount; i++) {
            talkers.add(new Talker(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readUtf(MAX_TALKER_NAME),
                buf.readUtf(MAX_TALKER_TITLE), buf.readUnsignedByte()));
        }
        return new RealmMapMarkersPayload(settlement, revision, gameTime, coins, markers, focus, raiders, talkers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
