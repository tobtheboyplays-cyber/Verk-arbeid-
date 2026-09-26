package com.hearthstead.settlement.guard.patrol;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Pure limits and validation for player-authored patrol routes (PATROL
 * ROUTES lane, owner request 2026-09-26: "I want patrolling with the
 * soldiers too"). No level access: the caller judges standability and
 * passes the verdict in, so every rule here is JUnit-testable.
 *
 * <p>Limits: at most {@link #MAX_ROUTES} routes per settlement, at most
 * {@link #MAX_WAYPOINTS} waypoints per route, no two waypoints of one route
 * more than {@link #MAX_SPAN} blocks apart (the "128 blocks per route"),
 * each leg at most {@link #MAX_LEG} blocks so a guard can path it in one
 * bounded search, and every waypoint inside the settlement's claim plus
 * {@link #REACH_BEYOND_CLAIM} (a wall walk just outside the claim is fine).
 */
public final class PatrolRules {
    public static final int MAX_ROUTES = 6;
    public static final int MAX_WAYPOINTS = 12;
    /** A route needs this many waypoints before anyone walks it. */
    public static final int MIN_WAYPOINTS = 2;
    /** A closed loop needs a real ring, not a there-and-back. */
    public static final int MIN_LOOP_WAYPOINTS = 3;
    public static final int MAX_SPAN = 128;
    public static final int MAX_LEG = 48;
    /** Vertical step between neighbouring waypoints (ladders, towers, stairs). */
    public static final int MAX_LEG_RISE = 24;
    public static final int REACH_BEYOND_CLAIM = 24;
    public static final int MAX_SQUAD = 4;
    public static final int MAX_PER_SHIFT = MAX_SQUAD;
    public static final int MAX_NAME_LENGTH = 24;
    /** Clicking within this many blocks of a marker means that marker. */
    public static final double MARKER_PICK_RADIUS = 1.6D;

    public enum Refusal {
        NONE("ok"),
        DISABLED("disabled"),
        NOT_MEMBER("not_member"),
        NO_ROUTE("no_route"),
        TOO_MANY_ROUTES("too_many_routes"),
        TOO_MANY_WAYPOINTS("too_many_waypoints"),
        OUTSIDE_CLAIM("outside_claim"),
        LEG_TOO_LONG("leg_too_long"),
        LOOP_LEG_TOO_LONG("loop_leg_too_long"),
        GAP_TOO_LONG("gap_too_long"),
        SPAN_TOO_LONG("span_too_long"),
        NOT_STANDABLE("not_standable"),
        DUPLICATE("duplicate"),
        LOOP_TOO_SHORT("loop_too_short"),
        BAD_NAME("bad_name"),
        NOT_ELIGIBLE("not_eligible"),
        STALE("stale"),
        WRONG_DIMENSION("wrong_dimension");

        private final String id;

        Refusal(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Short player-facing sentence (action bar). */
        public String sentence() {
            return switch (this) {
                case NONE -> "Done.";
                case DISABLED -> "Patrol routes are switched off on this server.";
                case NOT_MEMBER -> "Only members of this settlement can change its patrols.";
                case NO_ROUTE -> "That route no longer exists.";
                case TOO_MANY_ROUTES -> "A settlement can keep at most " + MAX_ROUTES + " patrol routes.";
                case TOO_MANY_WAYPOINTS -> "A route can have at most " + MAX_WAYPOINTS + " waypoints.";
                case OUTSIDE_CLAIM -> "Too far from the Banner: keep the route inside the claim.";
                case LEG_TOO_LONG -> "Too far from the last waypoint (max " + MAX_LEG + " blocks).";
                case LOOP_LEG_TOO_LONG -> "Too far from waypoint 1 to close the loop (max " + MAX_LEG + " blocks).";
                case GAP_TOO_LONG -> "That would leave a gap too long to walk (max " + MAX_LEG + " blocks).";
                case SPAN_TOO_LONG -> "The route would stretch over " + MAX_SPAN + " blocks.";
                case NOT_STANDABLE -> "A guard can't stand there.";
                case DUPLICATE -> "There is already a waypoint there.";
                case LOOP_TOO_SHORT -> "A loop needs at least " + MIN_LOOP_WAYPOINTS + " waypoints.";
                case BAD_NAME -> "That name can't be used.";
                case NOT_ELIGIBLE -> "Only guards and foot soldiers can walk a route.";
                case STALE -> "The route changed meanwhile; try again.";
                case WRONG_DIMENSION -> "That route is in another dimension.";
            };
        }
    }

    /**
     * Whether {@code candidate} may be appended to {@code existing}.
     *
     * @param standable the caller's verdict that a guard can stand at the candidate
     */
    public static Refusal validateAppend(List<BlockPos> existing, BlockPos candidate,
                                         BlockPos center, int claimRadius, boolean standable) {
        if (candidate == null || center == null) return Refusal.NOT_STANDABLE;
        if (existing.size() >= MAX_WAYPOINTS) return Refusal.TOO_MANY_WAYPOINTS;
        if (!insideReach(candidate, center, claimRadius)) return Refusal.OUTSIDE_CLAIM;
        if (!standable) return Refusal.NOT_STANDABLE;
        for (BlockPos p : existing) {
            if (horizontalSq(p, candidate) < 1.0D && Math.abs(p.getY() - candidate.getY()) <= 1) {
                return Refusal.DUPLICATE;
            }
        }
        if (!existing.isEmpty()) {
            BlockPos last = existing.get(existing.size() - 1);
            if (horizontalSq(last, candidate) > (double) MAX_LEG * MAX_LEG
                || Math.abs(last.getY() - candidate.getY()) > MAX_LEG_RISE) {
                return Refusal.LEG_TOO_LONG;
            }
        }
        for (BlockPos p : existing) {
            if (horizontalSq(p, candidate) > (double) MAX_SPAN * MAX_SPAN) return Refusal.SPAN_TOO_LONG;
        }
        return Refusal.NONE;
    }

    /** Closing the loop adds the leg last -> first, which must be walkable too. */
    public static Refusal validateLoop(List<BlockPos> points) {
        if (points.size() < MIN_LOOP_WAYPOINTS) return Refusal.LOOP_TOO_SHORT;
        BlockPos first = points.get(0);
        BlockPos last = points.get(points.size() - 1);
        if (horizontalSq(first, last) > (double) MAX_LEG * MAX_LEG
            || Math.abs(first.getY() - last.getY()) > MAX_LEG_RISE) {
            return Refusal.LOOP_LEG_TOO_LONG;
        }
        return Refusal.NONE;
    }

    /**
     * Full check of a stored route (load, and every client edit re-validates
     * the whole list). A route that fails is kept but inert.
     */
    public static Refusal validateRoute(List<BlockPos> points, boolean loop, BlockPos center, int claimRadius) {
        if (points.size() > MAX_WAYPOINTS) return Refusal.TOO_MANY_WAYPOINTS;
        for (int i = 0; i < points.size(); i++) {
            BlockPos p = points.get(i);
            if (!insideReach(p, center, claimRadius)) return Refusal.OUTSIDE_CLAIM;
            if (i > 0) {
                BlockPos prev = points.get(i - 1);
                if (horizontalSq(prev, p) > (double) MAX_LEG * MAX_LEG
                    || Math.abs(prev.getY() - p.getY()) > MAX_LEG_RISE) return Refusal.LEG_TOO_LONG;
            }
            for (int j = 0; j < i; j++) {
                if (horizontalSq(points.get(j), p) > (double) MAX_SPAN * MAX_SPAN) return Refusal.SPAN_TOO_LONG;
            }
        }
        return loop ? validateLoop(points) : Refusal.NONE;
    }

    public static boolean insideReach(BlockPos p, BlockPos center, int claimRadius) {
        double reach = Math.max(0, claimRadius) + REACH_BEYOND_CLAIM;
        return horizontalSq(p, center) <= reach * reach;
    }

    /** Index of the waypoint within {@link #MARKER_PICK_RADIUS} of {@code click}, or -1 (nearest wins). */
    public static int pick(List<BlockPos> points, BlockPos click) {
        int best = -1;
        double bestSq = MARKER_PICK_RADIUS * MARKER_PICK_RADIUS;
        for (int i = 0; i < points.size(); i++) {
            BlockPos p = points.get(i);
            if (Math.abs(p.getY() - click.getY()) > 2) continue;
            double d = horizontalSq(p, click);
            if (d <= bestSq) {
                best = i;
                bestSq = d;
            }
        }
        return best;
    }

    /** Trims, strips formatting codes and control characters; null when unusable. */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (Character.isISOControl(c)) continue;
            out.append(c);
        }
        String name = out.toString().trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) return null;
        return name;
    }

    /** Total walked length of the route, including the closing leg of a loop. */
    public static double length(List<BlockPos> points, boolean loop) {
        double total = 0.0D;
        for (int i = 1; i < points.size(); i++) total += Math.sqrt(points.get(i - 1).distSqr(points.get(i)));
        if (loop && points.size() >= MIN_LOOP_WAYPOINTS) {
            total += Math.sqrt(points.get(points.size() - 1).distSqr(points.get(0)));
        }
        return total;
    }

    /**
     * The next waypoint index: a loop wraps, an open route walks there and
     * back (ping-pong). {@code direction} is +1/-1; returns {index, direction}.
     */
    public static int[] advance(int index, int direction, int size, boolean loop) {
        if (size <= 1) return new int[] {0, 1};
        if (loop && size >= MIN_LOOP_WAYPOINTS) return new int[] {(index + 1) % size, 1};
        int dir = direction >= 0 ? 1 : -1;
        int next = index + dir;
        if (next >= size || next < 0) {
            dir = -dir;
            next = index + dir;
        }
        return new int[] {Math.max(0, Math.min(size - 1, next)), dir};
    }

    /**
     * A compass name for a new route from where its waypoints lie around the
     * Banner: "East Wall" near the claim edge, "East Round" further in,
     * "Inner Round" at the centre.
     */
    public static String autoName(List<BlockPos> points, BlockPos center, int claimRadius) {
        if (points.isEmpty() || center == null) return "Patrol";
        double sx = 0.0D;
        double sz = 0.0D;
        double far = 0.0D;
        for (BlockPos p : points) {
            sx += p.getX() - center.getX();
            sz += p.getZ() - center.getZ();
            far = Math.max(far, Math.sqrt(horizontalSq(p, center)));
        }
        sx /= points.size();
        sz /= points.size();
        double off = Math.sqrt(sx * sx + sz * sz);
        if (off < Math.max(4.0D, claimRadius * 0.2D)) {
            return far >= claimRadius * 0.6D ? "Outer Round" : "Inner Round";
        }
        // Minecraft: +X east, +Z south.
        double angle = Math.toDegrees(Math.atan2(sz, sx));
        String[] dirs = {"East", "South-East", "South", "South-West", "West", "North-West", "North", "North-East"};
        int octant = (int) Math.round(((angle % 360.0D) + 360.0D) % 360.0D / 45.0D) & 7;
        return dirs[octant] + (far >= claimRadius * 0.6D ? " Wall" : " Round");
    }

    static double horizontalSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private PatrolRules() {
    }
}
