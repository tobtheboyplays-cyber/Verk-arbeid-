package com.hearthstead.event.worldevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class WorldEventScheduleTest {
    private static final UUID VILLAGE = UUID.fromString("00000000-0000-0000-0000-00000000beef");
    private static final List<WorldEventType> ALL = List.of(WorldEventType.values());

    private static WorldEventSchedule.Plan plan(long day) {
        return WorldEventSchedule.plan(42L, VILLAGE, day, ALL, Map.of(), -1L, 1.0D, true);
    }

    @Test
    void sameSeedSettlementAndDayGiveTheSamePlan() {
        for (long day = 0; day < 50; day++) assertEquals(plan(day), plan(day));
        assertNotEquals(WorldEventSchedule.seed(42L, VILLAGE, 3), WorldEventSchedule.seed(43L, VILLAGE, 3));
    }

    @Test
    void aboutHalfTheDaysHaveAnEventAndSomeDaysAreQuiet() {
        int events = 0;
        for (long day = 0; day < 2000; day++) if (!plan(day).quiet()) events++;
        assertTrue(events > 2000 * 0.45 && events < 2000 * 0.65, "event days " + events);
    }

    @Test
    void frequencyZeroDisablesAndAHighMultiplierStillLeavesQuietDays() {
        int high = 0;
        for (long day = 0; day < 1000; day++) {
            assertTrue(WorldEventSchedule.plan(42L, VILLAGE, day, ALL, Map.of(), -1L, 0.0D, true).quiet());
            if (!WorldEventSchedule.plan(42L, VILLAGE, day, ALL, Map.of(), -1L, 3.0D, true).quiet()) high++;
        }
        assertTrue(high < 1000 && high > 800, "high-frequency event days " + high);
        assertEquals(WorldEventSchedule.MAX_DAILY_CHANCE, WorldEventSchedule.dailyChance(10.0D));
    }

    @Test
    void neverTwoEventsOnOneDay() {
        for (long day = 0; day < 200; day++) {
            assertTrue(WorldEventSchedule.plan(42L, VILLAGE, day, ALL, Map.of(), day, 3.0D, true).quiet());
        }
    }

    @Test
    void noEventWhenTheRaidGateIsClosed() {
        for (long day = 0; day < 200; day++) {
            assertTrue(WorldEventSchedule.plan(42L, VILLAGE, day, ALL, Map.of(), -1L, 3.0D, false).quiet());
        }
    }

    @Test
    void raidGateBlocksRaidsWarningsTheEveAndTheAttackItself() {
        assertFalse(WorldEventSchedule.raidQuiet(true, false, 10, -1));
        assertFalse(WorldEventSchedule.raidQuiet(false, true, 10, -1));
        assertFalse(WorldEventSchedule.raidQuiet(false, false, 10, 11), "the eve of an attack");
        assertFalse(WorldEventSchedule.raidQuiet(false, false, 10, 10), "the attack night");
        assertTrue(WorldEventSchedule.raidQuiet(false, false, 10, 12), "two nights out is still open");
        assertTrue(WorldEventSchedule.raidQuiet(false, false, 10, -1), "no raid scheduled");
        assertTrue(WorldEventSchedule.raidQuiet(false, false, 10, 7), "a stale past attack night");
    }

    @Test
    void onlyAvailableEventsAreChosenAndStartInsideTheirWindow() {
        List<WorldEventType> onlyFarm = List.of(WorldEventType.FIELD_FOX, WorldEventType.WILD_BOAR);
        for (long day = 0; day < 500; day++) {
            WorldEventSchedule.Plan plan = WorldEventSchedule.plan(7L, VILLAGE, day, onlyFarm, Map.of(), -1L, 2.0D, true);
            if (plan.quiet()) continue;
            assertTrue(onlyFarm.contains(plan.type()));
            assertTrue(plan.startTimeOfDay() >= plan.type().startFrom() && plan.startTimeOfDay() <= plan.type().startTo());
            assertTrue(WorldEventSchedule.inStartWindow(plan, plan.startTimeOfDay()));
            assertFalse(WorldEventSchedule.inStartWindow(plan, plan.type().startTo() + 1));
        }
        assertTrue(WorldEventSchedule.plan(7L, VILLAGE, 5, List.of(), Map.of(), -1L, 3.0D, true).quiet());
    }

    @Test
    void anEventWaitsForItsOwnGapBeforeComingBack() {
        Map<WorldEventType, Long> last = new EnumMap<>(WorldEventType.class);
        last.put(WorldEventType.PEDDLER, 100L);
        List<WorldEventType> justPeddler = List.of(WorldEventType.PEDDLER);
        for (long day = 101; day < 100 + WorldEventType.PEDDLER.gapDays(); day++) {
            assertTrue(WorldEventSchedule.plan(1L, VILLAGE, day, justPeddler, last, 100L, 3.0D, true).quiet());
        }
        boolean cameBack = false;
        for (long day = 100 + WorldEventType.PEDDLER.gapDays(); day < 140; day++) {
            if (!WorldEventSchedule.plan(1L, VILLAGE, day, justPeddler, last, 100L, 3.0D, true).quiet()) cameBack = true;
        }
        assertTrue(cameBack);
    }

    @Test
    void everyEventHasAStartWindowInsideOneDayAndABudget() {
        for (WorldEventType type : WorldEventType.values()) {
            assertTrue(type.startFrom() >= 0 && type.startTo() < 24_000 && type.startFrom() <= type.startTo(), type.id());
            assertTrue(type.budgetTicks() > 0 && type.gapDays() >= 1, type.id());
            assertEquals(type, WorldEventType.byId(type.id()));
        }
        assertTrue(EnumSet.of(WorldEventType.WOLF_PACK, WorldEventType.WILD_BOAR, WorldEventType.BRUTE_TOLL)
            .stream().allMatch(WorldEventType::hostile));
    }

    @Test
    void savedStateRoundTripsIncludingTheActiveEvent() {
        WorldEventSavedData data = new WorldEventSavedData();
        WorldEventSavedData.Row row = data.rowOrCreate(VILLAGE);
        assertNotNull(row);
        row.plannedDay = 9;
        row.plannedType = WorldEventType.MINSTRELS;
        row.plannedStart = 10_500;
        row.lastDayByType.put(WorldEventType.PEDDLER, 7L);
        row.bruteFavour = true;
        WorldEventSavedData.Active active = new WorldEventSavedData.Active(WorldEventType.WOLF_PACK, UUID.randomUUID(), 5L, 9L);
        active.eligibleTicks = 120;
        active.addActor(UUID.randomUUID());
        active.state.putInt("Kills", 1);
        row.active = active;

        WorldEventSavedData restored = WorldEventSavedData.load(data.save(new CompoundTag(), null), null);
        WorldEventSavedData.Row back = restored.row(VILLAGE);
        assertFalse(restored.quarantined());
        assertEquals(WorldEventType.MINSTRELS, back.plannedType);
        assertEquals(7L, back.lastDayByType.get(WorldEventType.PEDDLER));
        assertTrue(back.bruteFavour);
        assertEquals(active.id, back.active.id);
        assertEquals(120, back.active.eligibleTicks);
        assertEquals(1, back.active.actors.size());
        assertEquals(1, back.active.state.getInt("Kills"));
    }

    @Test
    void aMalformedFileQuarantinesAndIsWrittenBackUnchanged() {
        CompoundTag broken = new CompoundTag();
        broken.putInt("Version", 99);
        broken.putString("Keep", "me");
        WorldEventSavedData data = WorldEventSavedData.load(broken, null);
        assertTrue(data.quarantined());
        assertEquals(null, data.rowOrCreate(VILLAGE));
        CompoundTag written = data.save(new CompoundTag(), null);
        assertEquals("me", written.getString("Keep"));
    }
}
