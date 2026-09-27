package com.hearthstead.settlement.guard.patrol;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Route limits and validation, the book's edits and its save/load round trip. */
class PatrolRulesTest {
    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final int RADIUS = 48;

    @Test
    void appendAcceptsAStandableNearbyWaypoint() {
        assertEquals(PatrolRules.Refusal.NONE,
            PatrolRules.validateAppend(List.of(), new BlockPos(10, 64, 10), CENTER, RADIUS, true));
    }

    @Test
    void appendRefusesWhatAGuardCannotStandOn() {
        assertEquals(PatrolRules.Refusal.NOT_STANDABLE,
            PatrolRules.validateAppend(List.of(), new BlockPos(10, 64, 10), CENTER, RADIUS, false));
    }

    @Test
    void appendRefusesOutsideTheClaimPlusReach() {
        int edge = RADIUS + PatrolRules.REACH_BEYOND_CLAIM;
        assertEquals(PatrolRules.Refusal.NONE,
            PatrolRules.validateAppend(List.of(), new BlockPos(edge, 64, 0), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.OUTSIDE_CLAIM,
            PatrolRules.validateAppend(List.of(), new BlockPos(edge + 1, 64, 0), CENTER, RADIUS, true));
    }

    @Test
    void appendRefusesALegLongerThanTheLimit() {
        List<BlockPos> one = List.of(new BlockPos(-30, 64, 0));
        assertEquals(PatrolRules.Refusal.NONE, PatrolRules.validateAppend(one,
            new BlockPos(-30 + PatrolRules.MAX_LEG, 64, 0), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.LEG_TOO_LONG, PatrolRules.validateAppend(one,
            new BlockPos(-30 + PatrolRules.MAX_LEG + 1, 64, 0), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.LEG_TOO_LONG, PatrolRules.validateAppend(one,
            new BlockPos(-30, 64 + PatrolRules.MAX_LEG_RISE + 1, 0), CENTER, RADIUS, true));
    }

    @Test
    void appendRefusesARouteSpanningMoreThan128Blocks() {
        // Legs of 40 from x=-64 eastward: the fourth point would sit 136 from the first.
        List<BlockPos> pts = new ArrayList<>();
        pts.add(new BlockPos(-64, 64, 0));
        pts.add(new BlockPos(-24, 64, 0));
        pts.add(new BlockPos(16, 64, 0));
        pts.add(new BlockPos(56, 64, 0));
        assertEquals(PatrolRules.Refusal.SPAN_TOO_LONG, PatrolRules.validateAppend(pts,
            new BlockPos(66, 64, 0), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.NONE, PatrolRules.validateAppend(pts.subList(0, 3),
            new BlockPos(56, 64, 0), CENTER, RADIUS, true));
    }

    @Test
    void appendRefusesTheThirteenthWaypointAndDuplicates() {
        List<BlockPos> pts = new ArrayList<>();
        for (int i = 0; i < PatrolRules.MAX_WAYPOINTS; i++) pts.add(new BlockPos(i * 3, 64, 0));
        assertEquals(PatrolRules.Refusal.TOO_MANY_WAYPOINTS, PatrolRules.validateAppend(pts,
            new BlockPos(0, 64, 5), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.DUPLICATE, PatrolRules.validateAppend(pts.subList(0, 3),
            new BlockPos(3, 65, 0), CENTER, RADIUS, true));
    }

    @Test
    void loopNeedsThreeWaypointsAndAWalkableClosingLeg() {
        assertEquals(PatrolRules.Refusal.LOOP_TOO_SHORT,
            PatrolRules.validateLoop(List.of(new BlockPos(0, 64, 0), new BlockPos(5, 64, 0))));
        assertEquals(PatrolRules.Refusal.NONE, PatrolRules.validateLoop(List.of(new BlockPos(0, 64, 0),
            new BlockPos(10, 64, 0), new BlockPos(10, 64, 10))));
        assertEquals(PatrolRules.Refusal.LOOP_LEG_TOO_LONG, PatrolRules.validateLoop(List.of(new BlockPos(-40, 64, 0),
            new BlockPos(0, 64, 0), new BlockPos(40, 64, 0))));
    }

    @Test
    void wholeRouteValidationCatchesAnEditedSave() {
        List<BlockPos> ok = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), new BlockPos(10, 64, 10));
        assertEquals(PatrolRules.Refusal.NONE, PatrolRules.validateRoute(ok, true, CENTER, RADIUS));
        List<BlockPos> far = List.of(new BlockPos(0, 64, 0), new BlockPos(500, 64, 0));
        assertEquals(PatrolRules.Refusal.OUTSIDE_CLAIM, PatrolRules.validateRoute(far, false, CENTER, RADIUS));
    }

    @Test
    void loopWrapsAndAnOpenRouteWalksThereAndBack() {
        assertArrayEquals(new int[] {0, 1}, PatrolRules.advance(3, 1, 4, true));
        assertArrayEquals(new int[] {2, 1}, PatrolRules.advance(1, 1, 4, false));
        assertArrayEquals(new int[] {2, -1}, PatrolRules.advance(3, 1, 4, false));
        assertArrayEquals(new int[] {1, 1}, PatrolRules.advance(0, -1, 4, false));
        // A "loop" of two is walked there and back.
        assertArrayEquals(new int[] {0, -1}, PatrolRules.advance(1, 1, 2, true));
    }

    @Test
    void pickFindsTheNearestMarkerWithinReach() {
        List<BlockPos> pts = List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0));
        assertEquals(1, PatrolRules.pick(pts, new BlockPos(3, 64, 1)));
        assertEquals(-1, PatrolRules.pick(pts, new BlockPos(8, 64, 8)));
        assertEquals(-1, PatrolRules.pick(pts, new BlockPos(0, 70, 0)));
    }

    @Test
    void namesAreCleanedAndBounded() {
        assertEquals("East Wall", PatrolRules.cleanName("  East   §cWall "));
        assertNull(PatrolRules.cleanName("   "));
        assertNull(PatrolRules.cleanName("x".repeat(PatrolRules.MAX_NAME_LENGTH + 1)));
    }

    @Test
    void autoNameReadsTheCompass() {
        List<BlockPos> eastWall = List.of(new BlockPos(40, 64, -10), new BlockPos(40, 64, 10));
        assertEquals("East Wall", PatrolRules.autoName(eastWall, CENTER, RADIUS));
        List<BlockPos> northRound = List.of(new BlockPos(-4, 64, -12), new BlockPos(4, 64, -12));
        assertEquals("North Round", PatrolRules.autoName(northRound, CENTER, RADIUS));
    }

    @Test
    void theBookHoldsAtMostSixRoutesAndSurvivesSaveAndLoad() {
        PatrolRouteBook book = new PatrolRouteBook();
        for (int i = 0; i < PatrolRules.MAX_ROUTES; i++) assertNotNull(book.create());
        assertNull(book.create(), "the seventh route is refused");
        PatrolRoute route = book.routes().get(0);
        assertEquals(PatrolRules.Refusal.NONE, book.append(route, new BlockPos(40, 64, -10), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.NONE, book.append(route, new BlockPos(40, 64, 10), CENTER, RADIUS, true));
        assertEquals(PatrolRules.Refusal.NONE, book.append(route, new BlockPos(30, 64, 10), CENTER, RADIUS, true));
        assertEquals("East Wall", route.name(), "auto-named once it has two waypoints");
        assertEquals(PatrolRules.Refusal.NONE, book.setLoop(route, true));
        book.setPerShift(route, 3);
        book.setFormation(route, PatrolRoute.Formation.PAIRS);
        UUID guard = UUID.randomUUID();
        assertTrue(book.toggleMember(route, guard));
        assertTrue(book.toggleMember(book.routes().get(1), guard), "picking moves a guard to the new route");
        assertFalse(route.members().contains(guard));

        CompoundTag saved = book.writeNbt();
        PatrolRouteBook loaded = PatrolRouteBook.readNbt(saved);
        assertEquals(PatrolRules.MAX_ROUTES, loaded.routes().size());
        PatrolRoute back = loaded.route(route.id);
        assertNotNull(back);
        assertEquals(route.waypoints(), back.waypoints());
        assertTrue(back.loop());
        assertEquals(3, back.perShift());
        assertEquals(PatrolRoute.Formation.PAIRS, back.formation());
        assertEquals("East Wall", back.name());
        assertTrue(loaded.route(book.routes().get(1).id).members().contains(guard));
        loaded.delete(route.id);
        assertNotNull(loaded.create(), "ids keep counting after a reload");
        assertTrue(loaded.routes().stream().noneMatch(r -> r.id == route.id && r.size() > 0));
    }

    @Test
    void missingOrForeignDataReadsAsAnEmptyBook() {
        assertTrue(PatrolRouteBook.readNbt(null).isEmpty());
        CompoundTag foreign = new CompoundTag();
        foreign.putInt("Version", 99);
        assertTrue(PatrolRouteBook.readNbt(foreign).isEmpty());
    }

    @Test
    void removingAWaypointBelowThreeOpensTheLoop() {
        PatrolRouteBook book = new PatrolRouteBook();
        PatrolRoute route = book.create();
        book.append(route, new BlockPos(0, 64, 0), CENTER, RADIUS, true);
        book.append(route, new BlockPos(8, 64, 0), CENTER, RADIUS, true);
        book.append(route, new BlockPos(8, 64, 8), CENTER, RADIUS, true);
        book.setLoop(route, true);
        assertTrue(route.loop());
        book.remove(route, 2);
        assertFalse(route.loop());
        assertEquals(PatrolRules.Refusal.STALE, book.remove(route, 5));
    }

    @Test
    void aClosedLoopRefusesAPointTooFarFromWaypointOneAndRemovalNeverLeavesAnUnwalkableGap() {
        PatrolRouteBook book = new PatrolRouteBook();
        PatrolRoute route = book.create();
        book.append(route, new BlockPos(-20, 64, 0), CENTER, RADIUS, true);
        book.append(route, new BlockPos(0, 64, 0), CENTER, RADIUS, true);
        book.append(route, new BlockPos(0, 64, 20), CENTER, RADIUS, true);
        assertEquals(PatrolRules.Refusal.NONE, book.setLoop(route, true));
        // 40 from the last point is a fine leg, but 60 back to waypoint 1 is not.
        assertEquals(PatrolRules.Refusal.LOOP_LEG_TOO_LONG,
            book.append(route, new BlockPos(40, 64, 20), CENTER, RADIUS, true));
        PatrolRouteBook open = new PatrolRouteBook();
        PatrolRoute line = open.create();
        open.append(line, new BlockPos(-40, 64, 0), CENTER, RADIUS, true);
        open.append(line, new BlockPos(0, 64, 0), CENTER, RADIUS, true);
        open.append(line, new BlockPos(40, 64, 0), CENTER, RADIUS, true);
        assertEquals(PatrolRules.Refusal.GAP_TOO_LONG, open.remove(line, 1), "80 blocks would be left between 1 and 3");
        assertEquals(PatrolRules.Refusal.NONE, open.remove(line, 2));
    }
}
