package com.hearthstead.conversation.net;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the conversation ended; ease the camera back. {@code notice} may be empty. */
public record ConvClosePayload(int session, Component notice) implements CustomPacketPayload {
    public static final Type<ConvClosePayload> TYPE = new Type<>(Hearthstead.id("conv_close"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConvClosePayload> CODEC = new StreamCodec<>() {
        @Override
        public ConvClosePayload decode(RegistryFriendlyByteBuf buf) {
            return new ConvClosePayload(buf.readVarInt(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ConvClosePayload value) {
            buf.writeVarInt(value.session());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, value.notice());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
