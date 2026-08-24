package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * S2C: a snapshot of what the settlement's warehouses hold, for the
 * read-only Storage view.
 *
 * <p>A snapshot, deliberately: the client never asks "is item X available"
 * and never acts on this. Chests remain the truth (D-A2a-2) — this exists
 * so the player can see the settlement's stores at a glance, which is the
 * thing MineColonies makes you dig through building UIs to find.
 *
 * <p>{@code top} is capped server-side so the payload stays small
 * regardless of how much a settlement is hoarding.
 */
public record StorageIndexPayload(String warehouseName, int distinctTypes,
                                  int totalItems, List<ItemStack> top)
    implements CustomPacketPayload {

    /** Maximum stacks listed; the rest is summarised by the two counts. */
    public static final int MAX_LISTED = 12;

    public static final Type<StorageIndexPayload> TYPE =
        new Type<>(Hearthstead.id("storage_index"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageIndexPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageIndexPayload::warehouseName,
            ByteBufCodecs.VAR_INT, StorageIndexPayload::distinctTypes,
            ByteBufCodecs.VAR_INT, StorageIndexPayload::totalItems,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, StorageIndexPayload::top,
            StorageIndexPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
