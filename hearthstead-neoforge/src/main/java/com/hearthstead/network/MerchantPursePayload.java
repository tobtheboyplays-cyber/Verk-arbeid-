package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Display-only snapshot of the current visitor's shared payout budget. */
public record MerchantPursePayload(int containerId, int coins) implements CustomPacketPayload {
    public MerchantPursePayload {
        if (containerId < 0 || containerId > 100 || coins < 0 || coins > com.hearthstead.event.GoldCoinTrades.MAX_PURSE_BOUND) {
            throw new IllegalArgumentException("Invalid merchant purse snapshot");
        }
    }

    public static final Type<MerchantPursePayload> TYPE = new Type<>(Hearthstead.id("merchant_purse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MerchantPursePayload> CODEC = new StreamCodec<>() {
        public MerchantPursePayload decode(RegistryFriendlyByteBuf buffer) {
            return new MerchantPursePayload(buffer.readVarInt(), buffer.readVarInt());
        }
        public void encode(RegistryFriendlyByteBuf buffer, MerchantPursePayload value) {
            buffer.writeVarInt(value.containerId());
            buffer.writeVarInt(value.coins());
        }
    };

    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
