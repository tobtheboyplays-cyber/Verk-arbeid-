package com.hearthstead.settlement.guard.patrol;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A settlement's patrol routes, saved with the settlement (SavedData) and
 * shared by every member. All mutation goes through here so the limits in
 * {@link PatrolRules} hold whatever the caller; the network layer adds the
 * membership, dimension and standability checks.
 */
public final class PatrolRouteBook {
    public static final int DATA_VERSION = 1;

    private final List<PatrolRoute> routes = new ArrayList<>();
    private int nextId = 1;
    private int revision;

    public List<PatrolRoute> routes() {
        return List.copyOf(routes);
    }

    public boolean isEmpty() {
        return routes.isEmpty();
    }

    /** Bumped on every change; snapshots and squads key on it. */
    public int revision() {
        return revision;
    }

    @Nullable
    public PatrolRoute route(int id) {
        for (PatrolRoute route : routes) {
            if (route.id == id) return route;
        }
        return null;
    }

    /** The route this guard was picked for, or null. */
    @Nullable
    public PatrolRoute routeOfMember(UUID guard) {
        for (PatrolRoute route : routes) {
            if (route.members().contains(guard)) return route;
        }
        return null;
    }

    /** A new, empty route, or null at the route limit. */
    @Nullable
    public PatrolRoute create() {
        if (routes.size() >= PatrolRules.MAX_ROUTES) return null;
        boolean[] used = new boolean[PatrolPalette.COUNT];
        for (PatrolRoute route : routes) used[route.color()] = true;
        int color = 0;
        while (color < used.length - 1 && used[color]) color++;
        PatrolRoute route = new PatrolRoute(nextId++, "Route " + (routes.size() + 1), color);
        routes.add(route);
        revision++;
        return route;
    }

    public boolean delete(int id) {
        boolean removed = routes.removeIf(route -> route.id == id);
        if (removed) revision++;
        return removed;
    }

    public PatrolRules.Refusal append(PatrolRoute route, BlockPos pos, BlockPos center, int claimRadius,
                                      boolean standable) {
        PatrolRules.Refusal refusal = PatrolRules.validateAppend(route.waypoints(), pos, center, claimRadius,
            standable);
        if (refusal != PatrolRules.Refusal.NONE) return refusal;
        if (route.loopFlag() && route.size() >= PatrolRules.MIN_LOOP_WAYPOINTS - 1) {
            // A closed loop gains a new closing leg (new point -> waypoint 1): it must be walkable too.
            List<BlockPos> next = new ArrayList<>(route.waypoints());
            next.add(pos);
            if (PatrolRules.validateLoop(next) != PatrolRules.Refusal.NONE) return PatrolRules.Refusal.LOOP_LEG_TOO_LONG;
        }
        route.append(pos);
        if (!route.named() && route.size() >= PatrolRules.MIN_WAYPOINTS) {
            route.rename(uniqueName(PatrolRules.autoName(route.waypoints(), center, claimRadius), route), false);
        }
        revision++;
        return PatrolRules.Refusal.NONE;
    }

    public PatrolRules.Refusal remove(PatrolRoute route, int index) {
        if (index < 0 || index >= route.size()) return PatrolRules.Refusal.STALE;
        // Removing a middle waypoint joins its two legs: refuse a gap no guard could walk.
        List<BlockPos> next = new ArrayList<>(route.waypoints());
        next.remove(index);
        boolean loop = route.loopFlag() && next.size() >= PatrolRules.MIN_LOOP_WAYPOINTS;
        for (int i = 1; i < next.size(); i++) {
            if (PatrolRules.horizontalSq(next.get(i - 1), next.get(i)) > (double) PatrolRules.MAX_LEG * PatrolRules.MAX_LEG) {
                return PatrolRules.Refusal.GAP_TOO_LONG;
            }
        }
        if (loop && PatrolRules.validateLoop(next) != PatrolRules.Refusal.NONE) return PatrolRules.Refusal.GAP_TOO_LONG;
        route.remove(index);
        revision++;
        return PatrolRules.Refusal.NONE;
    }

    public PatrolRules.Refusal setLoop(PatrolRoute route, boolean loop) {
        if (loop) {
            PatrolRules.Refusal refusal = PatrolRules.validateLoop(route.waypoints());
            if (refusal != PatrolRules.Refusal.NONE) return refusal;
        }
        route.setLoop(loop);
        revision++;
        return PatrolRules.Refusal.NONE;
    }

    public PatrolRules.Refusal rename(PatrolRoute route, String raw) {
        String name = PatrolRules.cleanName(raw);
        if (name == null) return PatrolRules.Refusal.BAD_NAME;
        route.rename(name, true);
        revision++;
        return PatrolRules.Refusal.NONE;
    }

    public void setFormation(PatrolRoute route, PatrolRoute.Formation formation) {
        route.setFormation(formation);
        revision++;
    }

    public void setPerShift(PatrolRoute route, int n) {
        route.setPerShift(n);
        revision++;
    }

    /**
     * Picks (or un-picks) a guard for {@code route}. A guard walks one route:
     * picking moves them off any other route.
     */
    public boolean toggleMember(PatrolRoute route, UUID guard) {
        if (route.members().contains(guard)) {
            route.removeMember(guard);
            revision++;
            return false;
        }
        for (PatrolRoute other : routes) {
            if (other != route) other.removeMember(guard);
        }
        route.addMember(guard);
        revision++;
        return true;
    }

    /** Forgets a guard everywhere (death, dismissal). */
    public void forget(UUID guard) {
        boolean changed = false;
        for (PatrolRoute route : routes) changed |= route.removeMember(guard);
        if (changed) revision++;
    }

    private String uniqueName(String base, PatrolRoute self) {
        String name = base;
        int n = 2;
        while (taken(name, self)) name = base + " " + n++;
        return name.length() > PatrolRules.MAX_NAME_LENGTH ? name.substring(0, PatrolRules.MAX_NAME_LENGTH) : name;
    }

    private boolean taken(String name, PatrolRoute self) {
        for (PatrolRoute route : routes) {
            if (route != self && route.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------- nbt ---

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Version", DATA_VERSION);
        tag.putInt("NextId", nextId);
        ListTag list = new ListTag();
        for (PatrolRoute route : routes) list.add(route.writeNbt());
        tag.put("Routes", list);
        return tag;
    }

    /** Missing or foreign data reads as an empty book; a bad row drops only itself. */
    public static PatrolRouteBook readNbt(@Nullable CompoundTag tag) {
        PatrolRouteBook book = new PatrolRouteBook();
        if (tag == null || tag.getInt("Version") != DATA_VERSION) return book;
        ListTag list = tag.getList("Routes", Tag.TAG_COMPOUND);
        int maxId = 0;
        for (int i = 0; i < list.size() && book.routes.size() < PatrolRules.MAX_ROUTES; i++) {
            PatrolRoute route = PatrolRoute.readNbt(list.getCompound(i));
            if (route == null || book.route(route.id) != null) continue;
            book.routes.add(route);
            maxId = Math.max(maxId, route.id);
        }
        book.nextId = Math.max(maxId + 1, tag.getInt("NextId"));
        return book;
    }
}
