package com.hearthstead.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import com.hearthstead.event.GoldCoinTrades;
import com.hearthstead.settlement.economy.EconomyConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MerchantPursePayloadTest {
    @Test void emptyFirstAndLaterVisitBudgetsRoundTripWithoutChangingMenuIdentity() {
        // [economy] the purse grows with the village: 12 base, the default
        // cap 40, and the hard wire/validation bound 64 must all round-trip.
        for (int coins : new int[] {0, 8, 12, EconomyConfig.DEFAULT_MERCHANT_PURSE_CAP,
                GoldCoinTrades.MAX_PURSE_BOUND}) {
            var payload = new MerchantPursePayload(100, coins);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
            try {
                MerchantPursePayload.CODEC.encode(buffer, payload);
                assertEquals(payload, MerchantPursePayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }

    @Test void invalidMenusAndImpossiblePursesAreRejectedOnTheWire() {
        for (int[] invalid : new int[][] {{-1, 8}, {101, 8}, {1, -1},
                {1, GoldCoinTrades.MAX_PURSE_BOUND + 1}, {1, Integer.MAX_VALUE}}) {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
            try {
                buffer.writeVarInt(invalid[0]);
                buffer.writeVarInt(invalid[1]);
                assertThrows(IllegalArgumentException.class, () -> MerchantPursePayload.CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
    }

    @Test void theWireBoundCoversEveryConfigurablePurseCap() {
        assertEquals(64, GoldCoinTrades.MAX_PURSE_BOUND, "deliberate: raised from 12 when the purse began to grow");
        assertEquals(40, EconomyConfig.DEFAULT_MERCHANT_PURSE_CAP);
        assertTrue(EconomyConfig.DEFAULT_MERCHANT_PURSE_CAP <= GoldCoinTrades.MAX_PURSE_BOUND);
        assertThrows(IllegalArgumentException.class, () -> new MerchantPursePayload(1, 65));
        assertEquals(40, new MerchantPursePayload(1, 40).coins());
    }
}
