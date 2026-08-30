package com.hearthstead.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthRequestViewTest {
    private static final UUID SETTLEMENT_ID = new UUID(7L, 11L);
    private static final int CONTAINER_ID = 23;

    @Test
    void boundedViewRoundTripsEveryPlayerFacingField() {
        HearthMayorSnapshot.RequestView view = new HearthMayorSnapshot.RequestView(
            true, SETTLEMENT_ID, CONTAINER_ID, 400L, 17L, 9L, false, "none", false,
            List.of(row(1)));
        RegistryFriendlyByteBuf buffer = buffer();
        HearthMayorSnapshot.RequestView.CODEC.encode(buffer, view);

        assertEquals(view, HearthMayorSnapshot.RequestView.CODEC.decode(buffer));
        assertEquals(2, HearthMayorSnapshot.RequestView.WIRE_VERSION);
        assertEquals(64, HearthMayorSnapshot.RequestView.MAX_ROWS);
        assertEquals(HearthMayorAction.Kind.OPEN_REQUEST_LEDGER,
            HearthMayorAction.Kind.fromWireId(6),
            "the append-only Hearth action id must keep opening Requests");
    }

    @Test
    void cardinalityAndHostileTextFailClosed() {
        List<HearthMayorSnapshot.RequestRow> rows = new ArrayList<>();
        for (int i = 0; i <= HearthMayorSnapshot.RequestView.MAX_ROWS; i++) {
            rows.add(row(i + 1));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.RequestView(true, SETTLEMENT_ID,
                CONTAINER_ID, 1L, 1L, 1L,
                false, "none", true, rows));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.RequestView(true, SETTLEMENT_ID,
                CONTAINER_ID, 1L, 1L, 1L,
                true, "forged\nsecond-row", false, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.RequestView(true,
                HearthMayorAction.NO_ID, -1, 1L, 1L, 1L,
                false, "none", false, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.RequestRow(UUID.randomUUID(),
                4, 0, 0, "x".repeat(65), "none",
                "hearthstead.building.lumber_camp", BlockPos.ZERO,
                "hearthstead.building.warehouse", BlockPos.ZERO,
                HearthMayorAction.NO_ID, "", "minecraft:oak_log",
                1, 0, 0, 0L, 0, 0, true, true, false, false));
    }

    @Test
    void closeReconnectAndRevisionRegressionCannotReuseStaleRows() {
        HearthMayorSnapshot.RequestView closed =
            HearthMayorSnapshot.RequestView.closed();
        HearthMayorSnapshot.RequestView current = new HearthMayorSnapshot.RequestView(
            true, SETTLEMENT_ID, CONTAINER_ID, 100L, 8L, 5L,
            false, "none", false, List.of(row(1)));
        HearthMayorSnapshot.RequestView olderTick = new HearthMayorSnapshot.RequestView(
            true, SETTLEMENT_ID, CONTAINER_ID, 99L, 8L, 5L,
            false, "none", false, List.of(row(2)));
        HearthMayorSnapshot.RequestView typedRegression =
            new HearthMayorSnapshot.RequestView(true, SETTLEMENT_ID,
                CONTAINER_ID, 101L, 7L, 5L, false, "none", false,
                List.of(row(3)));
        HearthMayorSnapshot.RequestView equipmentRegression =
            new HearthMayorSnapshot.RequestView(true, SETTLEMENT_ID,
                CONTAINER_ID, 101L, 8L, 4L, false, "none", false,
                List.of(row(4)));
        HearthMayorSnapshot.RequestView refresh =
            new HearthMayorSnapshot.RequestView(true, SETTLEMENT_ID,
                CONTAINER_ID, 101L, 9L, 6L, false, "none", false,
                List.of(row(5)));
        HearthMayorSnapshot.RequestView wrongSettlement =
            new HearthMayorSnapshot.RequestView(true, UUID.randomUUID(),
                CONTAINER_ID, 102L, 9L, 6L, false, "none", false,
                List.of(row(6)));
        HearthMayorSnapshot.RequestView wrongContainer =
            new HearthMayorSnapshot.RequestView(true, SETTLEMENT_ID,
                CONTAINER_ID + 1, 102L, 9L, 6L, false, "none", false,
                List.of(row(7)));

        assertTrue(current.acceptsAfter(closed),
            "a new exact Hearth session may open a fresh bounded view");
        assertFalse(closed.acceptsAfter(current),
            "reconnect/default closed state cannot masquerade as an open view");
        assertFalse(olderTick.acceptsAfter(current));
        assertFalse(typedRegression.acceptsAfter(current));
        assertFalse(equipmentRegression.acceptsAfter(current));
        assertTrue(refresh.acceptsAfter(current));
        assertTrue(current.matches(SETTLEMENT_ID, CONTAINER_ID));
        assertFalse(current.matches(UUID.randomUUID(), CONTAINER_ID));
        assertFalse(current.matches(SETTLEMENT_ID, CONTAINER_ID + 1));
        assertFalse(wrongSettlement.acceptsAfter(current));
        assertFalse(wrongContainer.acceptsAfter(current));
    }

    private static HearthMayorSnapshot.RequestRow row(int seed) {
        return new HearthMayorSnapshot.RequestRow(
            new UUID(1L, seed), 4, 3, 2, "lumber_camp", "none",
            "hearthstead.building.lumber_camp", new BlockPos(2, 3, 4),
            "hearthstead.building.warehouse", new BlockPos(8, 3, 4),
            new UUID(2L, seed), "Alda", "minecraft:oak_log",
            4, 4, 0, 80L, 0, 1, true, true, false, false);
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
