package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Server to client: "{@code count} of {@code stack} just entered your
 * inventory". Display-only; the client never trusts it for anything but the
 * pickup notice HUD. {@code stack} is a single-item exemplar (its own count is
 * ignored) so components such as custom names and enchantments survive.
 */
public record PickupNoticePayload(ItemStack stack, int count) implements CustomPacketPayload {
    public static final int MAX_COUNT = 1_000_000;

    public PickupNoticePayload {
        if (stack == null || stack.isEmpty() || count <= 0 || count > MAX_COUNT) {
            throw new IllegalArgumentException("Invalid pickup notice");
        }
    }

    public static final Type<PickupNoticePayload> TYPE = new Type<>(Hearthstead.id("pickup_notice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PickupNoticePayload> CODEC = new StreamCodec<>() {
        public PickupNoticePayload decode(RegistryFriendlyByteBuf buffer) {
            ItemStack stack = ItemStack.STREAM_CODEC.decode(buffer);
            return new PickupNoticePayload(stack, buffer.readVarInt());
        }

        public void encode(RegistryFriendlyByteBuf buffer, PickupNoticePayload value) {
            ItemStack.STREAM_CODEC.encode(buffer, value.stack());
            buffer.writeVarInt(value.count());
        }
    };

    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
