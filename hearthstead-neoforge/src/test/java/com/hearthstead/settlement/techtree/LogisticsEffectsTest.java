package com.hearthstead.settlement.techtree;

import com.hearthstead.event.worldevent.WorldEventSchedule;
import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.settlement.development.HaulGear;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.techtree.effects.LogisticsEffects;
import com.hearthstead.settlement.techtree.effects.LogisticsEffects.Rung;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules behind the Logistics tech nodes (numbers quoted in logistics.json). */
class LogisticsEffectsTest {

    @Test
    void cartTiersAndPortersRaiseTheTripBudget() {
        // Frame Pack (tier 3) with Hand Cart / Coster's Cart / Heavy Carts.
        assertEquals(36, HaulGear.courierCapacity(3, 200, 0));
        assertEquals(44, HaulGear.courierCapacity(3, 300, 0));
        assertEquals(52, HaulGear.courierCapacity(3, 400, 0));
        // Porters' Guild: the whole trip x1.5.
        assertEquals(54, HaulGear.courierCapacity(3, 200, LogisticsEffects.PORTERS_LOAD_PERCENT));
        assertEquals(66, HaulGear.courierCapacity(3, 300, LogisticsEffects.PORTERS_LOAD_PERCENT));
        assertEquals(78, HaulGear.courierCapacity(3, 400, LogisticsEffects.PORTERS_LOAD_PERCENT));
        // No sack: no cart, but Porters still carry half again (8 -> 12).
        assertEquals(12, HaulGear.courierCapacity(0, 400, 50));
        // The old two-argument form is unchanged.
        assertEquals(HaulGear.courierCapacity(3, true), HaulGear.courierCapacity(3, HaulGear.cartPercent(), 0));
    }

    @Test
    void cartTierPacksAndUnpacksWithoutDisturbingTheSack() {
        assertEquals(0, HaulGear.unpackCartTier(HaulGear.pack(3, 0)));
        assertEquals(1, HaulGear.unpackCartTier(HaulGear.pack(3, true)));
        for (int cart = 1; cart <= 3; cart++) {
            for (int sack = 0; sack <= 3; sack++) {
                int packed = HaulGear.pack(sack, cart);
                assertEquals(cart, HaulGear.unpackCartTier(packed));
                assertEquals(sack, HaulGear.unpackTier(packed));
                assertTrue(HaulGear.unpackCart(packed));
            }
        }
        assertEquals(3, HaulGear.unpackCartTier(HaulGear.pack(1, 9)));
    }

    @Test
    void guildAndMuleSpeedsLandOnTheRightSettlers() {
        // Runners +15 for a Courier anywhere; a non-courier gets nothing.
        assertEquals(15, HaulGear.terrainPercent(false, false, true, false, false, 15, 0));
        assertEquals(0, HaulGear.terrainPercent(false, false, false, false, false, 15, 0));
        // Porters -10.
        assertEquals(-10, HaulGear.terrainPercent(false, false, true, false, false, -10, 0));
        // Heavy Carts: +20 only for a hitched cart on a road.
        assertEquals(30, HaulGear.terrainPercent(true, false, true, false, true, 0, 20));
        assertEquals(-15, HaulGear.terrainPercent(false, false, true, false, true, 0, 20));
        assertEquals(0, HaulGear.terrainPercent(true, false, true, false, false, 0, 20));
        // The old five-argument form is unchanged.
        assertEquals(HaulGear.terrainPercent(true, true, true, true, true),
            HaulGear.terrainPercent(true, true, true, true, true, 0, 0));
    }

    @Test
    void ledgerSortsRungsByTheirMostUrgentRequest() {
        List<Rung> fixed = List.of(Rung.values());
        Map<Rung, RequestPriority> urgentCollection = new EnumMap<>(Rung.class);
        urgentCollection.put(Rung.COLLECTION, RequestPriority.URGENT);
        assertEquals(fixed, LogisticsEffects.courierLadder(false, urgentCollection),
            "without the Ledger the ladder is fixed");
        // Nothing open: Hearth food (always High) leads, the rest keep their order.
        assertEquals(List.of(Rung.FOOD, Rung.EQUIPMENT, Rung.RESTOCK, Rung.TAVERN, Rung.COLLECTION),
            LogisticsEffects.courierLadder(true, Map.of()));
        // A missing tool (Urgent) stays first.
        assertEquals(List.of(Rung.EQUIPMENT, Rung.FOOD, Rung.RESTOCK, Rung.TAVERN, Rung.COLLECTION),
            LogisticsEffects.courierLadder(true, Map.of(Rung.EQUIPMENT, RequestPriority.URGENT)));
        // An Urgent pickup jumps the whole ladder; High ties keep the default order.
        Map<Rung, RequestPriority> mixed = new EnumMap<>(Rung.class);
        mixed.put(Rung.COLLECTION, RequestPriority.URGENT);
        mixed.put(Rung.EQUIPMENT, RequestPriority.HIGH);
        assertEquals(List.of(Rung.COLLECTION, Rung.EQUIPMENT, Rung.FOOD, Rung.RESTOCK, Rung.TAVERN),
            LogisticsEffects.courierLadder(true, mixed));
    }

    @Test
    void caravanRoutesMakeACaravanDueAfterThreeDays() {
        assertFalse(LogisticsEffects.caravanDue(false, 10L, null));
        assertTrue(LogisticsEffects.caravanDue(true, 10L, null), "never had one: due");
        assertTrue(LogisticsEffects.caravanDue(true, 10L, 7L));
        assertFalse(LogisticsEffects.caravanDue(true, 10L, 8L));
        assertFalse(LogisticsEffects.caravanDue(true, 10L, 10L));
    }

    @Test
    void aDueCaravanIsPlannedButRaidsAndOneEventADayStillHold() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
        EnumSet<WorldEventType> all = EnumSet.allOf(WorldEventType.class);
        Map<WorldEventType, Long> last = new EnumMap<>(WorldEventType.class);
        last.put(WorldEventType.CARAVAN, 7L); // inside its own 4-day gap on day 10
        for (long seed = 1; seed < 40; seed++) {
            WorldEventSchedule.Plan plan = WorldEventSchedule.plan(seed, id, 10L, all, last, 9L,
                1.0D, true, null, WorldEventType.CARAVAN);
            assertEquals(WorldEventType.CARAVAN, plan.type(), "a due caravan skips the daily roll");
            assertTrue(plan.startTimeOfDay() >= WorldEventType.CARAVAN.startFrom()
                && plan.startTimeOfDay() <= WorldEventType.CARAVAN.startTo());
            assertTrue(WorldEventSchedule.plan(seed, id, 10L, all, last, 9L, 1.0D, false, null,
                WorldEventType.CARAVAN).quiet(), "never on the eve of a raid");
            assertTrue(WorldEventSchedule.plan(seed, id, 10L, all, last, 10L, 1.0D, true, null,
                WorldEventType.CARAVAN).quiet(), "one event a day");
            assertTrue(WorldEventSchedule.plan(seed, id, 10L, all, last, 9L, 0.0D, true, null,
                WorldEventType.CARAVAN).quiet(), "frequency 0 still disables events");
            EnumSet<WorldEventType> noCaravan = EnumSet.complementOf(EnumSet.of(WorldEventType.CARAVAN));
            assertNotEquals(WorldEventType.CARAVAN, WorldEventSchedule.plan(seed, id, 10L, noCaravan,
                last, 9L, 1.0D, true, null, WorldEventType.CARAVAN).type(),
                "a switched-off or unavailable caravan is never forced");
        }
    }

    @Test
    void caravanWeightTriplesAndNoScaleKeepsTheOldCalendar() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000ca1a0");
        EnumSet<WorldEventType> all = EnumSet.allOf(WorldEventType.class);
        int plain = 0;
        int routes = 0;
        for (long day = 0; day < 4000; day++) {
            WorldEventSchedule.Plan old = WorldEventSchedule.plan(99L, id, day, all, Map.of(), -1L, 1.0D, true);
            WorldEventSchedule.Plan same = WorldEventSchedule.plan(99L, id, day, all, Map.of(), -1L, 1.0D,
                true, null, null);
            assertEquals(old, same, "no tech: the calendar is exactly the old one");
            if (old.type() == WorldEventType.CARAVAN) plain++;
            WorldEventSchedule.Plan tripled = WorldEventSchedule.plan(99L, id, day, all, Map.of(), -1L,
                1.0D, true, type -> type == WorldEventType.CARAVAN
                    ? LogisticsEffects.CARAVAN_WEIGHT_MULTIPLIER : 1.0D, null);
            if (tripled.type() == WorldEventType.CARAVAN) routes++;
        }
        assertTrue(routes > plain * 2, "x3 weight: " + plain + " -> " + routes + " caravans");
        assertEquals(3.0D * WorldEventType.CARAVAN.weight(),
            WorldEventSchedule.weightOf(WorldEventType.CARAVAN, t -> 3.0D));
    }
}
