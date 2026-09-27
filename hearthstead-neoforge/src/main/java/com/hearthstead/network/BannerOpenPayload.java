package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server (QA U9): "I clicked the pole or cloth of the Banner at
 * {@code pos}". The stand block is only the bottom cell; the pole and flag
 * above it are drawn, not blocks, so a click there never reached the server.
 * The server re-checks the Banner, reach and the view ray before opening
 * exactly what a click on the stand opens.
 */
public record BannerOpenPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<BannerOpenPayload> TYPE = new Type<>(Hearthstead.id("banner_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BannerOpenPayload> CODEC = StreamCodec.of(
        (buf, p) -> buf.writeBlockPos(p.pos),
        buf -> new BannerOpenPayload(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
