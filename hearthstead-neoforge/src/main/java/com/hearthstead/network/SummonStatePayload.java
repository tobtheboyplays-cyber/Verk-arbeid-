package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to client: the settlers summoned by THIS player (en route or waiting
 * at their side), for the private outline, the map "summoned to you" line and
 * the settler sheet's button state. Sent on change only.
 */
public record SummonStatePayload(List<Row> rows) implements CustomPacketPayload {
    public static final int MAX_ROWS = 64;

    public record Row(int entityId, UUID settlerId, boolean arrived) {
    }

    public static final Type<SummonStatePayload> TYPE = new Type<>(Hearthstead.id("summon_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SummonStatePayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            int n = Math.min(p.rows.size(), MAX_ROWS);
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Row row = p.rows.get(i);
                buf.writeVarInt(row.entityId());
                buf.writeUUID(row.settlerId());
                buf.writeBoolean(row.arrived());
            }
        },
        buf -> {
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_ROWS) throw new IllegalArgumentException("Too many summon rows");
            List<Row> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) rows.add(new Row(buf.readVarInt(), buf.readUUID(), buf.readBoolean()));
            return new SummonStatePayload(rows);
        });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
