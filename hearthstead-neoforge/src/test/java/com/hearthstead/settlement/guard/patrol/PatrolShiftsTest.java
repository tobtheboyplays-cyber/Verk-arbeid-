package com.hearthstead.settlement.guard.patrol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shift assignment: the watch rota decides, picks come first, "N per shift" tops up. */
class PatrolShiftsTest {
    private static UUID id(int n) {
        return new UUID(0L, n);
    }

    private static PatrolShifts.Candidate on(int n) {
        return new PatrolShifts.Candidate(id(n), true, true);
    }

    private static PatrolShifts.Candidate off(int n) {
        return new PatrolShifts.Candidate(id(n), false, true);
    }

    private static PatrolShifts.Candidate busy(int n) {
        return new PatrolShifts.Candidate(id(n), true, false);
    }

    @Test
    void onlyOnWatchPickedGuardsWalkAndOffWatchOnesRest() {
        var route = new PatrolShifts.RouteSpec(1, true, List.of(id(1), id(2), id(3)), 0);
        Map<Integer, List<UUID>> plan = PatrolShifts.plan(List.of(route), List.of(on(1), off(2), on(3)), Map.of());
        assertEquals(List.of(id(1), id(3)), plan.get(1));
    }

    @Test
    void perShiftTopsUpFromTheFreePoolOnly() {
        var east = new PatrolShifts.RouteSpec(1, true, List.of(id(1)), 3);
        var west = new PatrolShifts.RouteSpec(2, true, List.of(id(9)), 0);
        List<PatrolShifts.Candidate> guards = List.of(on(1), on(2), on(3), on(4), off(5), busy(6), on(9));
        Map<Integer, List<UUID>> plan = PatrolShifts.plan(List.of(east, west), guards, Map.of());
        assertEquals(3, plan.get(1).size());
        assertEquals(id(1), plan.get(1).get(0), "a picked guard leads");
        assertFalse(plan.get(1).contains(id(5)), "off watch");
        assertFalse(plan.get(1).contains(id(6)), "busy with another order");
        assertFalse(plan.get(1).contains(id(9)), "picked for the west route");
        assertEquals(List.of(id(9)), plan.get(2));
        // Everyone left over keeps their ordinary behaviour: id 4 is on no route.
        assertTrue(plan.values().stream().noneMatch(s -> s.contains(id(4))));
    }

    @Test
    void squadsNeverExceedFour() {
        List<UUID> picked = new ArrayList<>();
        List<PatrolShifts.Candidate> guards = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            picked.add(id(i));
            guards.add(on(i));
        }
        var route = new PatrolShifts.RouteSpec(1, true, picked, 4);
        assertEquals(PatrolRules.MAX_SQUAD, PatrolShifts.plan(List.of(route), guards, Map.of()).get(1).size());
    }

    @Test
    void anUnwalkableRouteGetsNobody() {
        var route = new PatrolShifts.RouteSpec(1, false, List.of(id(1)), 2);
        assertTrue(PatrolShifts.plan(List.of(route), List.of(on(1), on(2)), Map.of()).isEmpty());
    }

    @Test
    void poolGuardsStayOnTheirRouteAndTheLeaderStaysLeader() {
        var east = new PatrolShifts.RouteSpec(1, true, List.of(), 2);
        var west = new PatrolShifts.RouteSpec(2, true, List.of(), 2);
        List<PatrolShifts.Candidate> guards = List.of(on(1), on(2), on(3), on(4));
        Map<Integer, List<UUID>> previous = Map.of(1, List.of(id(4), id(3)), 2, List.of(id(2), id(1)));
        Map<Integer, List<UUID>> plan = PatrolShifts.plan(List.of(east, west), guards, previous);
        assertEquals(List.of(id(4), id(3)), plan.get(1));
        assertEquals(List.of(id(2), id(1)), plan.get(2));
    }

    @Test
    void theWatchChangeSwapsTheSquad() {
        var route = new PatrolShifts.RouteSpec(1, true, List.of(), 2);
        // Day: 1 and 2 on watch; night: 3 and 4.
        Map<Integer, List<UUID>> day = PatrolShifts.plan(List.of(route),
            List.of(on(1), on(2), off(3), off(4)), Map.of());
        Map<Integer, List<UUID>> night = PatrolShifts.plan(List.of(route),
            List.of(off(1), off(2), on(3), on(4)), day);
        assertEquals(List.of(id(1), id(2)), day.get(1));
        assertEquals(List.of(id(3), id(4)), night.get(1));
    }
}
