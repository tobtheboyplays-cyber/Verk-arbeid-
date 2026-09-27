package com.hearthstead.network;

import com.hearthstead.entity.SettlerActivity;
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

/** Wire contract of the three realm map payloads: exact round trips and hard bounds. */
class RealmMapPayloadTest {
    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }

    private static UUID id(int n) {
        return new UUID(0x1234L, n);
    }

    @Test
    void requestRoundTripsEveryKindAndUnknownIsInert() {
        for (RealmMapRequestPayload.Kind kind : RealmMapRequestPayload.Kind.values()) {
            var input = new RealmMapRequestPayload(new BlockPos(110, 72, -116), id(1), 7, kind, id(2));
            var buf = buffer();
            try {
                RealmMapRequestPayload.CODEC.encode(buf, input);
                assertEquals(input, RealmMapRequestPayload.CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
            } finally {
                buf.release();
            }
        }
        assertEquals(RealmMapRequestPayload.Kind.UNKNOWN, RealmMapRequestPayload.Kind.fromWireId(99));
        var nulls = new RealmMapRequestPayload(null, null, 0, null, null);
        assertEquals(RealmMapRequestPayload.Kind.UNKNOWN, nulls.kind());
        assertEquals(RealmMapRequestPayload.NO_FOCUS, nulls.focus());
    }

    @Test
    void layoutRoundTripsBuildingsRosterAndBell() {
        var buildings = List.of(
            new RealmMapLayoutPayload.BuildingEntry(id(10), "lumber_camp", 2, 100, -120, 108, -112, 104, -121, true, 2),
            new RealmMapLayoutPayload.BuildingEntry(id(11), "house", 1, 90, -100, 95, -95, 92, -101, false, 0));
        var roster = List.of(
            new RealmMapLayoutPayload.RosterEntry(id(20), "Ansgar", 1, 0, 12345, true),
            new RealmMapLayoutPayload.RosterEntry(id(21), "Eira", 26, RealmMapLayoutPayload.NO_BUILDING, -1, false));
        var input = new RealmMapLayoutPayload(id(1), 3, 110, 72, -116, 48, id(21), true, 112, -110,
            3, true, 1, buildings, roster);
        var buf = buffer();
        try {
            RealmMapLayoutPayload.CODEC.encode(buf, input);
            var decoded = RealmMapLayoutPayload.CODEC.decode(buf);
            assertEquals(input, decoded);
            assertEquals(0, buf.readableBytes());
            assertTrue(decoded.hasBell());
            assertEquals("Ansgar", decoded.roster().get(0).name());
        } finally {
            buf.release();
        }
    }

    @Test
    void layoutClampsTextAndRejectsOversizedCounts() {
        String longName = "x".repeat(200);
        var entry = new RealmMapLayoutPayload.RosterEntry(id(1), longName, 999, 9999, 0, true);
        assertEquals(RealmMapLayoutPayload.MAX_TEXT, entry.name().length());
        assertEquals(255, entry.professionId());
        assertEquals(RealmMapLayoutPayload.MAX_BUILDINGS - 1, entry.workBuilding());
        var box = new RealmMapLayoutPayload.BuildingEntry(id(2), "house", 1, 10, 10, 0, 0, 0, 0, true, 1);
        assertTrue(box.minX() <= box.maxX() && box.minZ() <= box.maxZ(), "inverted bounds are normalised");

        var buf = buffer();
        try {
            net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, id(1));
            buf.writeVarInt(1);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(48);
            net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, id(0));
            buf.writeBoolean(false);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeBoolean(false);
            buf.writeVarInt(0);
            buf.writeVarInt(RealmMapLayoutPayload.MAX_BUILDINGS + 1);
            assertThrows(IllegalArgumentException.class, () -> RealmMapLayoutPayload.CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void fortyMarkersWithFocusRoundTripCompactly() {
        List<RealmMapMarkersPayload.Marker> markers = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            markers.add(new RealmMapMarkersPayload.Marker(id(100 + i), 5000 + i, 100.25F + i, 71.0F, -116.5F - i,
                i % 28, SettlerActivity.byId(i % 20).id(), RealmMapStatus.byWireId(i % 7).wireId()));
        }
        var focus = new RealmMapMarkersPayload.Focus(id(100), 4, 72, 55, List.of(
            new RealmMapMarkersPayload.BagSlot(17, 12), new RealmMapMarkersPayload.BagSlot(40, 1)));
        var input = new RealmMapMarkersPayload(id(1), 3, 123_456L, 57, markers, focus, List.of(
            new RealmMapMarkersPayload.Raider(900, -60.5F, 12.25F, true),
            new RealmMapMarkersPayload.Raider(901, -58.0F, 14.0F, false)));
        var buf = buffer();
        try {
            RealmMapMarkersPayload.CODEC.encode(buf, input);
            int bytes = buf.readableBytes();
            assertTrue(bytes < 40 * 40 + 160, "about 36 bytes per marker, got " + bytes);
            assertEquals(input, RealmMapMarkersPayload.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void talkMarkersRoundTripBoundedAndNamed() {
        List<RealmMapMarkersPayload.Talker> talkers = new ArrayList<>();
        talkers.add(new RealmMapMarkersPayload.Talker(700, 12.5F, -30.25F, "Aldric the Peddler"));
        talkers.add(new RealmMapMarkersPayload.Talker(701, -4.0F, 8.0F, "Grukk, Raid Captain", "",
            RealmMapMarkersPayload.Talker.PARLEY));
        talkers.add(new RealmMapMarkersPayload.Talker(702, 1.0F, 2.0F, "Odd Peddler", "t", 99));
        var input = new RealmMapMarkersPayload(id(1), 2, 99L, 3, List.of(), null, List.of(), talkers);
        var buf = buffer();
        try {
            RealmMapMarkersPayload.CODEC.encode(buf, input);
            var decoded = RealmMapMarkersPayload.CODEC.decode(buf);
            assertEquals(input, decoded);
            assertEquals("Aldric the Peddler", decoded.talkers().get(0).name());
            assertEquals(RealmMapMarkersPayload.Talker.PARLEY, decoded.talkers().get(1).kind());
            assertEquals(RealmMapMarkersPayload.Talker.TALK, decoded.talkers().get(2).kind(), "unknown kinds fall back to talk");
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
        // Long names are cut to the wire limit; the list is capped.
        var longName = new RealmMapMarkersPayload.Talker(1, 0, 0, "x".repeat(200));
        assertEquals(RealmMapMarkersPayload.MAX_TALKER_NAME, longName.name().length());
        List<RealmMapMarkersPayload.Talker> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(new RealmMapMarkersPayload.Talker(i, i, i, "n" + i));
        assertEquals(RealmMapMarkersPayload.MAX_TALKERS,
            new RealmMapMarkersPayload(id(1), 0, 0L, 0, List.of(), null, List.of(), many).talkers().size());
        // Older constructors carry no talkers.
        assertTrue(new RealmMapMarkersPayload(id(1), 0, 0L, 0, List.of(), null, List.of()).talkers().isEmpty());
    }

    @Test
    void oversizedTalkerCountIsRejected() {
        var buf = buffer();
        try {
            RealmMapMarkersPayload.CODEC.encode(buf, new RealmMapMarkersPayload(id(1), 0, 0L, 0, List.of(), null));
            // Rewrite the trailing talker count (last byte, 0) as an oversized one.
            buf.writerIndex(buf.writerIndex() - 1);
            buf.writeVarInt(RealmMapMarkersPayload.MAX_TALKERS + 1);
            assertThrows(IllegalArgumentException.class, () -> RealmMapMarkersPayload.CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void markersWithoutFocusAndUnknownCoinsRoundTrip() {
        var input = new RealmMapMarkersPayload(id(1), 0, 0L, -1, List.of(), null);
        assertFalse(input.focus().present());
        var buf = buffer();
        try {
            RealmMapMarkersPayload.CODEC.encode(buf, input);
            var decoded = RealmMapMarkersPayload.CODEC.decode(buf);
            assertEquals(input, decoded);
            assertEquals(-1, decoded.coins());
        } finally {
            buf.release();
        }
    }

    @Test
    void oversizedMarkerAndBagCountsAreRejected() {
        var buf = buffer();
        try {
            net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, id(1));
            buf.writeVarInt(0);
            buf.writeVarLong(0L);
            buf.writeVarInt(0);
            buf.writeVarInt(RealmMapMarkersPayload.MAX_MARKERS + 1);
            assertThrows(IllegalArgumentException.class, () -> RealmMapMarkersPayload.CODEC.decode(buf));
        } finally {
            buf.release();
        }
        List<RealmMapMarkersPayload.BagSlot> bag = new ArrayList<>();
        for (int i = 0; i < 20; i++) bag.add(new RealmMapMarkersPayload.BagSlot(i, 1));
        assertEquals(RealmMapMarkersPayload.MAX_BAG,
            new RealmMapMarkersPayload.Focus(id(1), 1, 1, 1, bag).bag().size());
    }

    @Test
    void statusClassificationReadsActivityNavigationAndStall() {
        assertEquals(RealmMapStatus.WORKING, RealmMapStatus.classify(SettlerActivity.WORK_CHOP, false, false));
        assertEquals(RealmMapStatus.WORKING, RealmMapStatus.classify(SettlerActivity.WORK_CHOP, true, false));
        assertEquals(RealmMapStatus.STUCK, RealmMapStatus.classify(SettlerActivity.WORK_CHOP, true, true));
        assertEquals(RealmMapStatus.WORKING, RealmMapStatus.classify(SettlerActivity.WORK_CHOP, false, true),
            "a stall timer without a live path is someone working in place");
        assertEquals(RealmMapStatus.WALKING, RealmMapStatus.classify(SettlerActivity.IDLE, true, false));
        assertEquals(RealmMapStatus.IDLE, RealmMapStatus.classify(SettlerActivity.IDLE, false, false));
        assertEquals(RealmMapStatus.WALKING, RealmMapStatus.classify(SettlerActivity.CARRYING, false, false));
        assertEquals(RealmMapStatus.SLEEPING, RealmMapStatus.classify(SettlerActivity.SLEEPING, false, false));
        assertEquals(RealmMapStatus.FLEEING, RealmMapStatus.classify(SettlerActivity.FLEEING, true, true));
        assertEquals(RealmMapStatus.FIGHTING, RealmMapStatus.classify(SettlerActivity.COMBAT, true, false));
        assertEquals(RealmMapStatus.STUCK, RealmMapStatus.classify(SettlerActivity.OUT_OF_AMMO, false, false));
        assertEquals(RealmMapStatus.IDLE, RealmMapStatus.byWireId(99));
        assertTrue(RealmMapStatus.STUCK.needsAttention() && !RealmMapStatus.WORKING.needsAttention());
        assertTrue(RealmMapNetwork.stalledFor(RealmMapNetwork.STALL_TICKS));
        assertFalse(RealmMapNetwork.stalledFor(RealmMapNetwork.STALL_TICKS - 1));
    }
}
