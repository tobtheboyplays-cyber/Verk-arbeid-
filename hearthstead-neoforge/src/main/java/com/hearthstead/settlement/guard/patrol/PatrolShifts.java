package com.hearthstead.settlement.guard.patrol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure shift assignment: who walks which route right now.
 *
 * <p>Rules (owner request, PATROL ROUTES lane):
 * <ul>
 *   <li>The watch rota decides: only guards on their own watch patrol;
 *       off-watch guards (picked or not) rest as before.</li>
 *   <li>Picked guards walk their route (pick order), up to {@link PatrolRules#MAX_SQUAD}.</li>
 *   <li>"N guards per shift" tops a route's squad up to N from the free pool:
 *       on-watch guards picked for no route. The pool is shared in route
 *       order; a pool guard already on a route keeps it while still free
 *       (no reshuffling every second).</li>
 *   <li>Unavailable guards (an explicit post order, a field order, a summons)
 *       never count; everyone left over keeps their ordinary behaviour.</li>
 *   <li>The leader stays the leader while in the squad; otherwise the first.</li>
 * </ul>
 */
public final class PatrolShifts {
    /** One route as the planner sees it. */
    public record RouteSpec(int id, boolean walkable, List<UUID> picked, int perShift) {
    }

    /** One guard as the planner sees it. */
    public record Candidate(UUID id, boolean onWatch, boolean available) {
    }

    /**
     * @param previous last plan (route id -> squad, leader first); may be empty
     * @return route id -> squad (leader first); routes with nobody are absent
     */
    public static Map<Integer, List<UUID>> plan(List<RouteSpec> routes, List<Candidate> candidates,
                                                Map<Integer, List<UUID>> previous) {
        Map<UUID, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : candidates) byId.put(c.id(), c);
        Set<UUID> pickedAnywhere = new HashSet<>();
        for (RouteSpec r : routes) pickedAnywhere.addAll(r.picked());

        List<RouteSpec> ordered = new ArrayList<>(routes);
        ordered.sort(Comparator.comparingInt(RouteSpec::id));
        Map<Integer, List<UUID>> squads = new LinkedHashMap<>();
        Set<UUID> used = new HashSet<>();
        for (RouteSpec r : ordered) {
            List<UUID> squad = new ArrayList<>();
            if (r.walkable()) {
                for (UUID id : r.picked()) {
                    if (squad.size() >= PatrolRules.MAX_SQUAD) break;
                    if (ready(byId.get(id)) && used.add(id)) squad.add(id);
                }
            }
            squads.put(r.id(), squad);
        }

        List<UUID> pool = new ArrayList<>();
        for (Candidate c : candidates) {
            if (ready(c) && !pickedAnywhere.contains(c.id()) && !used.contains(c.id())) pool.add(c.id());
        }
        pool.sort(Comparator.naturalOrder());
        // Keep pool guards where they already walk.
        for (RouteSpec r : ordered) {
            List<UUID> squad = squads.get(r.id());
            int want = want(r);
            List<UUID> before = previous.getOrDefault(r.id(), List.of());
            for (UUID id : before) {
                if (squad.size() >= want) break;
                if (pool.contains(id) && used.add(id)) {
                    squad.add(id);
                    pool.remove(id);
                }
            }
        }
        for (RouteSpec r : ordered) {
            List<UUID> squad = squads.get(r.id());
            int want = want(r);
            while (squad.size() < want && !pool.isEmpty()) {
                UUID id = pool.remove(0);
                if (used.add(id)) squad.add(id);
            }
        }

        Map<Integer, List<UUID>> out = new LinkedHashMap<>();
        for (RouteSpec r : ordered) {
            List<UUID> squad = squads.get(r.id());
            if (squad.isEmpty()) continue;
            List<UUID> before = previous.getOrDefault(r.id(), List.of());
            if (!before.isEmpty() && squad.contains(before.get(0))) {
                squad.remove(before.get(0));
                squad.add(0, before.get(0));
            }
            out.put(r.id(), List.copyOf(squad));
        }
        return out;
    }

    private static int want(RouteSpec r) {
        return r.walkable() ? Math.min(PatrolRules.MAX_SQUAD, Math.max(0, r.perShift())) : 0;
    }

    private static boolean ready(Candidate c) {
        return c != null && c.onWatch() && c.available();
    }

    private PatrolShifts() {
    }
}
