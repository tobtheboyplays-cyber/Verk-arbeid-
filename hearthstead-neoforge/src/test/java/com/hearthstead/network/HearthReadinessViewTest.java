package com.hearthstead.network;

import io.netty.buffer.Unpooled;
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

class HearthReadinessViewTest {
    @Test
    void boundedBlockedViewRoundTripsEveryField() {
        HearthMayorSnapshot.ReadinessView view = blocked(400L,
            List.of(0, 30, 38, 44));
        RegistryFriendlyByteBuf buffer = buffer();
        HearthMayorSnapshot.ReadinessView.CODEC.encode(buffer, view);

        assertEquals(view,
            HearthMayorSnapshot.ReadinessView.CODEC.decode(buffer));
        assertEquals(1, HearthMayorSnapshot.ReadinessView.WIRE_VERSION);
        assertEquals(48, HearthMayorSnapshot.ReadinessView.MAX_BLOCKERS);
        assertEquals(HearthMayorAction.Kind.OPEN_RAID_READINESS,
            HearthMayorAction.Kind.fromWireId(8));
        assertEquals(HearthMayorAction.Kind.CONFIRM_RAID_READINESS,
            HearthMayorAction.Kind.fromWireId(9));
    }

    @Test
    void readyAndCommittedReceiptsHaveDifferentAuthorityShapes() {
        UUID session = UUID.randomUUID();
        HearthMayorSnapshot.ReadinessView ready =
            new HearthMayorSnapshot.ReadinessView(true, 4L, session, 17,
                99L, true, false, 4, 4, 2, 32, 32, 0, 0,
                List.of());
        HearthMayorSnapshot.ReadinessView committed =
            new HearthMayorSnapshot.ReadinessView(true, 5L,
                HearthMayorAction.NO_ID, 0, 100L, true, true, 4, 4, 2,
                32, 32, 0, 0, List.of());

        assertTrue(ready.ready());
        assertFalse(ready.committed());
        assertTrue(committed.committed());
        assertTrue(committed.acceptsAfter(ready));
        assertFalse(ready.acceptsAfter(committed),
            "a delayed pre-commit packet may not roll back the receipt");
    }

    @Test
    void malformedCardinalityIdentityAndTruthFailClosed() {
        List<Integer> tooMany = new ArrayList<>();
        for (int i = 0; i <=
                HearthMayorSnapshot.ReadinessView.MAX_BLOCKERS; i++) {
            tooMany.add(i);
        }
        assertThrows(IllegalArgumentException.class,
            () -> blocked(1L, tooMany));
        assertThrows(IllegalArgumentException.class,
            () -> blocked(1L, List.of(0, 0)));
        assertThrows(IllegalArgumentException.class,
            () -> blocked(1L, List.of(46)));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.ReadinessView(true, 1L,
                UUID.randomUUID(), 1, 1L, true, false, 0, 0, 0, 0,
                0, 0, 0, List.of(30)));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.ReadinessView(true, 1L,
                UUID.randomUUID(), 1, 1L, true, true, 0, 0, 0, 0,
                0, 0, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new HearthMayorSnapshot.ReadinessView(false, 1L,
                HearthMayorAction.NO_ID, 0, 0L, false, false, 0, 0,
                0, 0, 0, 0, 0, List.of()));
    }

    @Test
    void orderingRejectsOlderAndPostCommitPackets() {
        HearthMayorSnapshot.ReadinessView current = blocked(100L,
            List.of(30));
        HearthMayorSnapshot.ReadinessView older = blocked(99L,
            List.of(31));
        HearthMayorSnapshot.ReadinessView refresh = blocked(101L,
            List.of(31));

        assertTrue(current.acceptsAfter(
            HearthMayorSnapshot.ReadinessView.closed()));
        assertFalse(older.acceptsAfter(current));
        assertTrue(refresh.acceptsAfter(current));
        assertFalse(HearthMayorSnapshot.ReadinessView.closed()
            .acceptsAfter(current));
    }

    private static HearthMayorSnapshot.ReadinessView blocked(
            long tick, List<Integer> blockers) {
        return new HearthMayorSnapshot.ReadinessView(true, tick,
            UUID.randomUUID(), 7, 55L, false, false, 4, 4, 2, 28, 32,
            3, 1, blockers);
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
            RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
