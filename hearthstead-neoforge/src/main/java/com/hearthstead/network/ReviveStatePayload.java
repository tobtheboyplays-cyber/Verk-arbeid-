package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to client, wire id {@code hearthstead:revive_state}: every downed
 * player in the receiver's dimension. Sent every 10 ticks while anyone is
 * down (every 2 while a revive is in progress) and once, empty, when the
 * last one is back up or dead. Display-only: the client uses it for the HUD,
 * the see-through markers, poses, input suppression and drag steering, never
 * for authority.
 */
public record ReviveStatePayload(List<Entry> entries) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_NAME = 32;
    public static final int NONE = -1;

    /**
     * @param entityId       downed player's entity id ({@link #NONE} never)
     * @param name           display name (plain, bounded)
     * @param bleedLeft      bleed-out ticks left (paused while being revived)
     * @param bleedTotal     bleed-out ticks at the start
     * @param reviverId      entity id of whoever is holding the revive, or {@link #NONE}
     * @param reviveProgress revive ticks done
     * @param reviveRequired revive ticks needed
     * @param draggerId      entity id of whoever is dragging them, or {@link #NONE}
     */
    public record Entry(int entityId, UUID playerId, String name, double x, double y, double z,
                       int bleedLeft, int bleedTotal, int reviverId, int reviveProgress,
                       int reviveRequired, int draggerId) {
        public Entry {
            name = name == null ? "" : name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
            bleedTotal = Math.max(1, bleedTotal);
            bleedLeft = Math.max(0, Math.min(bleedTotal, bleedLeft));
            reviveRequired = Math.max(1, reviveRequired);
            reviveProgress = Math.max(0, Math.min(reviveRequired, reviveProgress));
        }
    }

    public ReviveStatePayload {
        entries = entries == null ? List.of()
            : List.copyOf(entries.size() > MAX_ENTRIES ? entries.subList(0, MAX_ENTRIES) : entries);
    }

    public static final Type<ReviveStatePayload> TYPE = new Type<>(Hearthstead.id("revive_state"));
    public static final StreamCodec<FriendlyByteBuf, ReviveStatePayload> CODEC = new StreamCodec<>() {
        @Override
        public ReviveStatePayload decode(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IllegalArgumentException("revive_state: bad entry count " + count);
            }
            List<Entry> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(new Entry(buf.readVarInt(), buf.readUUID(), buf.readUtf(MAX_NAME),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt()));
            }
            return new ReviveStatePayload(list);
        }

        @Override
        public void encode(FriendlyByteBuf buf, ReviveStatePayload value) {
            buf.writeVarInt(value.entries().size());
            for (Entry e : value.entries()) {
                buf.writeVarInt(e.entityId());
                buf.writeUUID(e.playerId());
                buf.writeUtf(e.name(), MAX_NAME);
                buf.writeDouble(e.x());
                buf.writeDouble(e.y());
                buf.writeDouble(e.z());
                buf.writeVarInt(e.bleedLeft());
                buf.writeVarInt(e.bleedTotal());
                buf.writeVarInt(e.reviverId());
                buf.writeVarInt(e.reviveProgress());
                buf.writeVarInt(e.reviveRequired());
                buf.writeVarInt(e.draggerId());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
