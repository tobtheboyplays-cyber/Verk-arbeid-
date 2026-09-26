package com.hearthstead.conversation.net;

import com.hearthstead.Hearthstead;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, every second near talkable people: who shows a "!"
 * (someone has something to say), who is mid-conversation (and with whom,
 * for the "Tobias is talking to Grukk" label), so every nearby client plays
 * the same talk gesture.
 */
public record ConvMarkersPayload(List<Marker> markers) implements CustomPacketPayload {
    public static final int MAX = 32;
    public static final int AVAILABLE = 1;
    public static final int TALKING = 2;
    public static final int TRADE = 3;
    public static final int PARLEY = 4;
    public static final int QUEST = 5;

    public record Marker(int entityId, int state, String talker) {
    }

    public static final Type<ConvMarkersPayload> TYPE = new Type<>(Hearthstead.id("conv_markers"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConvMarkersPayload> CODEC = new StreamCodec<>() {
        @Override
        public ConvMarkersPayload decode(RegistryFriendlyByteBuf buf) {
            int count = Math.min(MAX, buf.readVarInt());
            List<Marker> markers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) markers.add(new Marker(buf.readVarInt(), buf.readVarInt(), buf.readUtf(48)));
            return new ConvMarkersPayload(markers);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ConvMarkersPayload value) {
            int count = Math.min(MAX, value.markers().size());
            buf.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                Marker marker = value.markers().get(i);
                buf.writeVarInt(marker.entityId());
                buf.writeVarInt(marker.state());
                String talker = marker.talker() == null ? "" : marker.talker();
                buf.writeUtf(talker.length() > 48 ? talker.substring(0, 48) : talker, 48);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
