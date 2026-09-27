package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An on-demand, bounded snapshot of one Builder's actual site. */
public record BuilderNeedsPayload(int entityId, UUID builderId, UUID sessionId, boolean opening,
                                  String builderName, String siteName, boolean queued, int phase,
                                  int done, int total, int status, List<String> statusArgs,
                                  List<BuilderPayloads.Stock> materials, int materialCount,
                                  List<Help> help, int helpCount) implements CustomPacketPayload {
    public static final int MAX_MATERIALS = 512;
    public static final int MAX_HELP = 64;
    public static final Type<BuilderNeedsPayload> TYPE = new Type<>(Hearthstead.id("builder_needs"));
    public record Help(BlockPos pos, String blockKey) { }

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderNeedsPayload> CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(p.entityId);
            UUIDUtil.STREAM_CODEC.encode(buf, p.builderId);
            UUIDUtil.STREAM_CODEC.encode(buf, p.sessionId);
            buf.writeBoolean(p.opening);
            buf.writeUtf(p.builderName, 128);
            buf.writeUtf(p.siteName, 128);
            buf.writeBoolean(p.queued);
            buf.writeVarInt(p.phase);
            buf.writeVarInt(p.done);
            buf.writeVarInt(p.total);
            buf.writeVarInt(p.status);
            writeList(buf, p.statusArgs, 8, (b, s) -> b.writeUtf(s, 128));
            writeList(buf, p.materials, MAX_MATERIALS, BuilderPayloads.Stock::write);
            buf.writeVarInt(p.materialCount);
            writeList(buf, p.help, MAX_HELP, (b, h) -> {
                BlockPos.STREAM_CODEC.encode(b, h.pos);
                b.writeUtf(h.blockKey, 256);
            });
            buf.writeVarInt(p.helpCount);
        }, buf -> new BuilderNeedsPayload(buf.readVarInt(), UUIDUtil.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readUtf(128), buf.readUtf(128),
            buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            readList(buf, 8, b -> b.readUtf(128)), readList(buf, MAX_MATERIALS, BuilderPayloads.Stock::read),
            buf.readVarInt(), readList(buf, MAX_HELP, b -> new Help(BlockPos.STREAM_CODEC.decode(b),
                b.readUtf(256))), buf.readVarInt()));

    private static <T> void writeList(RegistryFriendlyByteBuf buf, List<T> list, int max,
                                     java.util.function.BiConsumer<RegistryFriendlyByteBuf, T> writer) {
        int size = Math.min(max, list.size());
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) writer.accept(buf, list.get(i));
    }

    private static <T> List<T> readList(RegistryFriendlyByteBuf buf, int max,
                                       java.util.function.Function<RegistryFriendlyByteBuf, T> reader) {
        int size = buf.readVarInt();
        if (size < 0 || size > max) throw new IllegalArgumentException("Builder needs list size");
        List<T> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(reader.apply(buf));
        return List.copyOf(out);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
