package com.hearthstead.settlement.work;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoodsQualityTest {
    @Test void skillToolsAndDevelopmentAreAllRequiredForHigherQuality() {
        assertEquals(1, GoodsQuality.determine(19, true, true, 2, 0));
        assertEquals(1, GoodsQuality.determine(40, false, false, 2, 0));
        assertEquals(2, GoodsQuality.determine(20, true, false, 1, 0));
        assertEquals(3, GoodsQuality.determine(30, true, true, 1, 0));
        assertEquals(2, GoodsQuality.determine(30, true, true, 0, 0));
        assertEquals(2, GoodsQuality.determine(30, true, true, 2, 1));
        assertEquals(3, GoodsQuality.determine(30, true, true, 2, 0));
        assertEquals(4, GoodsQuality.determine(35, true, true, 2, 0));
        assertEquals(5, GoodsQuality.determine(40, true, true, true, 3, 0));
        int rare = 0;
        for (int roll = 0; roll < 100; roll++) {
            int value = GoodsQuality.determine(30, true, true, 2, roll);
            assertEquals(value, GoodsQuality.determine(30, true, true, 2, roll));
            if (value == 3) rare++;
        }
        assertEquals(5, rare);
    }
    @Test void persistedAndNetworkQualityRejectOutOfRangeValues() {
        for (int value : new int[]{-1, 0, 6, Integer.MAX_VALUE}) {
            assertTrue(GoodsQuality.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonPrimitive(value)).error().isPresent());
            var buffer = io.netty.buffer.Unpooled.buffer();
            try {
                net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buffer, value);
                assertThrows(IllegalArgumentException.class, () -> GoodsQuality.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test void priceDivisorsAndNewTiersRemainBounded() {
        assertEquals(1, GoodsQuality.priceDivisor(GoodsQuality.BASIC));
        assertEquals(2, GoodsQuality.priceDivisor(GoodsQuality.FINE));
        assertEquals(3, GoodsQuality.priceDivisor(GoodsQuality.SUPERIOR));
        assertEquals(4, GoodsQuality.priceDivisor(GoodsQuality.EXCEPTIONAL));
        assertEquals(5, GoodsQuality.priceDivisor(GoodsQuality.MASTERWORK));
        assertEquals(6, GoodsQuality.priceDivisor(GoodsQuality.LEGENDARY));
        assertThrows(IllegalArgumentException.class, () -> GoodsQuality.priceDivisor(6));
    }
}
