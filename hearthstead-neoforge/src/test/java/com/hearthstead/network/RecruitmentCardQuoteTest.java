package com.hearthstead.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecruitmentCardQuoteTest {
    @Test void exactAptitudeAndFrozenPriceRoundTripWithEmptyAndLegacyCards() {
        for (var card : List.of(HearthMayorSnapshot.RecruitmentCard.empty(), card(1, 0, 15, 2, 13, 2),
                card(0, -1, 0, -1, 0, 0), card(0, 0, 40, 2, 20, 0))) {
            RegistryFriendlyByteBuf buffer = buffer();
            try {
                HearthMayorSnapshot.RecruitmentCard.CODEC.encode(buffer, card);
                assertEquals(card, HearthMayorSnapshot.RecruitmentCard.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void malformedAptitudeAndUnknownWireVersionReject() {
        assertThrows(IllegalArgumentException.class, () -> card(1, 8, 15, 2, 13, 2));
        assertThrows(IllegalArgumentException.class, () -> card(1, 0, 16, 2, 13, 2));
        assertThrows(IllegalArgumentException.class, () -> card(1, 0, 15, 0, 13, 2));
        assertThrows(IllegalArgumentException.class, () -> card(1, 0, 15, 2, 13, 0));
        assertThrows(IllegalArgumentException.class, () -> card(0, 0, 100, 2, 13, 0));
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            buffer.writeVarInt(2);
            assertThrows(IllegalArgumentException.class, () -> HearthMayorSnapshot.RecruitmentCard.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    private static HearthMayorSnapshot.RecruitmentCard card(int version, int a, int av, int b, int bv, int premium) {
        return new HearthMayorSnapshot.RecruitmentCard(true, new UUID(1, 2), "Guest", 40,
            4, 0, 1, 32, 32, 900L,
            List.of(new HearthMayorSnapshot.CostLine("item.minecraft.bread", 5),
                new HearthMayorSnapshot.CostLine("hearthstead.cost.planks", 9)),
            true, true, version, a, av, b, bv, premium, 25);
    }
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
