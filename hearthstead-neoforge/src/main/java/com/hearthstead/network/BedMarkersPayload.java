package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BedMarkersPayload(ResourceLocation dimension, List<Entry> entries) implements CustomPacketPayload {
    public static final int MAX_BEDS = 128;
    public static final byte INVALID = 0, FREE = 1, ASSIGNED = 2;
    public record Entry(BlockPos head, byte state) {
        public Entry {
            if (head == null || state < INVALID || state > ASSIGNED) throw new IllegalArgumentException("Bed marker");
            head = head.immutable();
        }
    }
    public BedMarkersPayload {
        if (dimension == null || entries == null || entries.size() > MAX_BEDS) throw new IllegalArgumentException("Bed snapshot");
        entries = List.copyOf(entries);
        var positions = new HashSet<BlockPos>();
        for (Entry e : entries) if (!positions.add(e.head())) throw new IllegalArgumentException("Duplicate bed");
    }
    public static final Type<BedMarkersPayload> TYPE = new Type<>(Hearthstead.id("bed_markers"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BedMarkersPayload> CODEC = new StreamCodec<>() {
        public BedMarkersPayload decode(RegistryFriendlyByteBuf buf) {
            ResourceLocation dimension = buf.readResourceLocation();
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_BEDS) throw new IllegalArgumentException("Bed count");
            var entries = new ArrayList<Entry>(size);
            for (int i=0;i<size;i++) entries.add(new Entry(buf.readBlockPos(),buf.readByte()));
            return new BedMarkersPayload(dimension,entries);
        }
        public void encode(RegistryFriendlyByteBuf buf, BedMarkersPayload value) {
            buf.writeResourceLocation(value.dimension()); buf.writeVarInt(value.entries().size());
            for (Entry e : value.entries()) { buf.writeBlockPos(e.head()); buf.writeByte(e.state()); }
        }
    };
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
