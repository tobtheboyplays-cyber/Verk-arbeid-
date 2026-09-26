package com.hearthstead.event.worldevent;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * World events under a server restart: a running event (its type, id,
 * eligible budget, actors and handler state), the day plan and the per-type
 * cooldowns must reload exactly, so actors that load after the restart are
 * recognised as members (not culled as leftovers) and the budget continues
 * instead of restarting.
 */
class WorldEventSavedDataRestartTest {

    private static WorldEventSavedData reload(WorldEventSavedData data) {
        CompoundTag saved = data.save(new CompoundTag(), null);
        return WorldEventSavedData.load(saved, null);
    }

    @Test
    void aRunningEventSurvivesSaveAndLoadExactly() {
        WorldEventSavedData data = new WorldEventSavedData();
        UUID settlement = UUID.randomUUID();
        WorldEventSavedData.Row row = data.rowOrCreate(settlement);
        assertNotNull(row);
        row.plannedDay = 12L;
        row.plannedType = WorldEventType.WOLF_PACK;
        row.plannedStart = 14000;
        row.lastEventDay = 11L;
        row.lastDayByType.put(WorldEventType.PEDDLER, 9L);
        row.bruteFavour = true;
        UUID eventId = UUID.randomUUID();
        WorldEventSavedData.Active active = new WorldEventSavedData.Active(
            WorldEventType.BRUTE_TOLL, eventId, 123456L, 11L);
        active.eligibleTicks = 777;
        active.forced = true;
        UUID chief = UUID.randomUUID();
        UUID brute = UUID.randomUUID();
        assertTrue(active.addActor(chief));
        assertTrue(active.addActor(brute));
        active.state.putBoolean("Hostile", true);
        active.state.putInt("Toll", 14);
        row.active = active;

        WorldEventSavedData loaded = reload(reload(data));
        assertFalse(loaded.quarantined());
        WorldEventSavedData.Row back = loaded.row(settlement);
        assertNotNull(back);
        assertEquals(12L, back.plannedDay);
        assertEquals(WorldEventType.WOLF_PACK, back.plannedType);
        assertEquals(14000, back.plannedStart);
        assertEquals(11L, back.lastEventDay);
        assertEquals(9L, back.lastDayByType.get(WorldEventType.PEDDLER));
        assertTrue(back.bruteFavour);
        WorldEventSavedData.Active a = back.active;
        assertNotNull(a, "the running event reloads");
        assertEquals(WorldEventType.BRUTE_TOLL, a.type);
        assertEquals(eventId, a.id);
        assertEquals(123456L, a.startedGameTime);
        assertEquals(11L, a.startedDay);
        assertEquals(777, a.eligibleTicks, "the budget continues, it does not restart");
        assertTrue(a.forced);
        assertEquals(java.util.List.of(chief, brute), a.actors, "actors stay members after a restart");
        assertTrue(a.state.getBoolean("Hostile"));
        assertEquals(14, a.state.getInt("Toll"));
        assertEquals(a, loaded.activeById(eventId));
        assertEquals(settlement, loaded.settlementOfEvent(eventId));
    }

    @Test
    void aQuietRowReloadsWithoutAnActiveEvent() {
        WorldEventSavedData data = new WorldEventSavedData();
        UUID settlement = UUID.randomUUID();
        data.rowOrCreate(settlement).lastOutcome = "peddler:left";
        WorldEventSavedData.Row back = reload(data).row(settlement);
        assertNotNull(back);
        assertNull(back.active);
        assertNull(back.plannedType);
        assertEquals("peddler:left", back.lastOutcome);
    }

    @Test
    void aMalformedFileQuarantinesAndIsWrittenBackUnchanged() {
        CompoundTag broken = new CompoundTag();
        broken.putInt("Version", 1);
        broken.putString("Rows", "not a list");
        WorldEventSavedData data = WorldEventSavedData.load(broken, null);
        assertTrue(data.quarantined());
        assertNull(data.rowOrCreate(UUID.randomUUID()), "no new events while quarantined");
        CompoundTag written = data.save(new CompoundTag(), null);
        assertEquals("not a list", written.getString("Rows"), "the original is kept for a human");
        assertTrue(written.getBoolean("Quarantined"));
    }
}
