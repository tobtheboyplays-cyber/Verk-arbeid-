package com.hearthstead.network;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BedMarkersPayloadTest {
    private static final ResourceLocation DIM=ResourceLocation.withDefaultNamespace("overworld");
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY,ConnectionType.NEOFORGE);
    }
    @Test void exactIndependentBedStatesRoundTrip() {
        var input=new BedMarkersPayload(DIM,List.of(
            new BedMarkersPayload.Entry(new BlockPos(1,64,1),BedMarkersPayload.ASSIGNED),
            new BedMarkersPayload.Entry(new BlockPos(3,64,1),BedMarkersPayload.FREE),
            new BedMarkersPayload.Entry(new BlockPos(5,64,1),BedMarkersPayload.INVALID)));
        var buf=buffer();
        try { BedMarkersPayload.CODEC.encode(buf,input); assertEquals(input,BedMarkersPayload.CODEC.decode(buf)); assertEquals(0,buf.readableBytes()); }
        finally { buf.release(); }
    }
    @Test void duplicatePositionsUnknownStatesAndOversizedWireCountsAreRejected() {
        var entry=new BedMarkersPayload.Entry(BlockPos.ZERO,BedMarkersPayload.FREE);
        assertThrows(IllegalArgumentException.class,()->new BedMarkersPayload(DIM,List.of(entry,entry)));
        assertThrows(IllegalArgumentException.class,()->new BedMarkersPayload.Entry(BlockPos.ZERO,(byte)3));
        var buf=buffer();
        try { buf.writeResourceLocation(DIM); buf.writeVarInt(BedMarkersPayload.MAX_BEDS+1);
            assertThrows(IllegalArgumentException.class,()->BedMarkersPayload.CODEC.decode(buf)); }
        finally { buf.release(); }
    }
}
