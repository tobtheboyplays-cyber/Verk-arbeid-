package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, wire id {@code hearthstead:revive_event}: a one-off
 * downed/revive moment so the client can play its cue (alert drum, revive
 * chime, the GET_UP clip). Sent to every player in the dimension.
 */
public record ReviveEventPayload(int kind, int entityId, double x, double y, double z)
    implements CustomPacketPayload {
    /** A player went down. */
    public static final int DOWNED = 0;
    /** A downed player was revived (GET_UP starts now). */
    public static final int REVIVED = 1;
    /** A downed player died (bled out, finished off, logged out). */
    public static final int DIED = 2;

    public ReviveEventPayload {
        if (kind < DOWNED || kind > DIED) {
            throw new IllegalArgumentException("revive_event: bad kind " + kind);
        }
    }

    public static final Type<ReviveEventPayload> TYPE = new Type<>(Hearthstead.id("revive_event"));
    public static final StreamCodec<FriendlyByteBuf, ReviveEventPayload> CODEC = new StreamCodec<>() {
        @Override
        public ReviveEventPayload decode(FriendlyByteBuf buf) {
            return new ReviveEventPayload(buf.readByte(), buf.readVarInt(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        @Override
        public void encode(FriendlyByteBuf buf, ReviveEventPayload value) {
            buf.writeByte(value.kind());
            buf.writeVarInt(value.entityId());
            buf.writeDouble(value.x());
            buf.writeDouble(value.y());
            buf.writeDouble(value.z());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
