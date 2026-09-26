package com.hearthstead.settlement.raid;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure order and bounds of the raid form-up search (real-terrain fix, 26 Sep). */
class RaidFootingSearchTest {

    private static final int CLAIM = 48;

    @Test
    void everyCandidateIsOutsideTheClaim() {
        for (float bearing = 0.0F; bearing < 360.0F; bearing += 7.5F) {
            for (RaidFootingSearch.Candidate c
                    : RaidFootingSearch.captain(bearing, CLAIM, 60)) {
                assertTrue(c.distance() > CLAIM, "captain candidate inside claim " + c);
            }
            for (RaidFootingSearch.Candidate c
                    : RaidFootingSearch.follower(bearing, CLAIM, 60)) {
                assertTrue(c.distance() > CLAIM, "follower candidate inside claim " + c);
            }
        }
    }

    @Test
    void theWarnedColumnComesFirstAndItsBearingBeforeAnyOther() {
        List<RaidFootingSearch.Candidate> captain = RaidFootingSearch.captain(95.0F, CLAIM, 61);
        assertEquals(new RaidFootingSearch.Candidate(95.0F, 61), captain.get(0));
        int band = RaidFootingSearch.bandMin(CLAIM);
        int extended = RaidFootingSearch.bandMax(CLAIM) + RaidDirector.CAPTAIN_EXTRA_REACH;
        int ownBearing = 0;
        for (RaidFootingSearch.Candidate c : captain) {
            if (c.bearing() != 95.0F) {
                break;
            }
            ownBearing++;
            assertTrue(c.distance() >= band && c.distance() <= extended, "own ray " + c);
        }
        assertTrue(ownBearing >= 10, "the warned ray is walked across band and reach first");
    }

    @Test
    void theCaptainSweepsTheFullCircleInTwelveDegreeSteps() {
        List<Float> bearings = RaidFootingSearch.sweepBearings(20.0F);
        assertEquals(30, bearings.size(), "360 / 12 distinct bearings");
        assertEquals(30, new HashSet<>(bearings).size(), "no bearing twice");
        assertEquals(20.0F, bearings.get(0));
        assertEquals(32.0F, bearings.get(1));
        assertEquals(8.0F, bearings.get(2));
        Set<Float> seen = new HashSet<>();
        for (RaidFootingSearch.Candidate c : RaidFootingSearch.captain(20.0F, CLAIM, 60)) {
            seen.add(c.bearing());
        }
        assertEquals(new HashSet<>(bearings), seen, "the captain tries every sweep bearing");
    }

    @Test
    void edgeAndFarRingsComeAfterEveryBandColumn() {
        List<RaidFootingSearch.Candidate> captain = RaidFootingSearch.captain(0.0F, CLAIM, 60);
        int min = RaidFootingSearch.bandMin(CLAIM);
        int extended = RaidFootingSearch.bandMax(CLAIM) + RaidDirector.CAPTAIN_EXTRA_REACH;
        boolean leftBand = false;
        int maxDistance = 0;
        for (RaidFootingSearch.Candidate c : captain) {
            boolean inBand = c.distance() >= min && c.distance() <= extended;
            if (!inBand) {
                leftBand = true;
            } else {
                assertFalse(leftBand, "band column " + c + " after an edge/far ring column");
            }
            maxDistance = Math.max(maxDistance, c.distance());
        }
        assertTrue(leftBand, "edge and far rings exist");
        assertTrue(captain.stream().anyMatch(c -> c.distance()
                == CLAIM + RaidFootingSearch.EDGE_MARGIN),
            "the ring just past the claim edge is tried");
        assertTrue(maxDistance <= RaidFootingSearch.bandMax(CLAIM) + RaidFootingSearch.FAR_REACH,
            "bounded far ring, got " + maxDistance);
        assertTrue(captain.size() < 1_200, "bounded work per attempt, got " + captain.size());
    }

    @Test
    void aFollowerKeepsItsOwnBearing() {
        List<RaidFootingSearch.Candidate> follower = RaidFootingSearch.follower(-30.0F, CLAIM, 59);
        assertEquals(new RaidFootingSearch.Candidate(330.0F, 59), follower.get(0));
        for (RaidFootingSearch.Candidate c : follower) {
            assertEquals(330.0F, c.bearing(), "follower left its bearing: " + c);
            assertTrue(c.distance() <= RaidFootingSearch.bandMax(CLAIM)
                + RaidDirector.CAPTAIN_EXTRA_REACH);
        }
    }

    @Test
    void gatherOffsetsSpreadFollowersAroundTheirCaptain() {
        List<int[]> first = RaidFootingSearch.gatherOffsets(1);
        List<int[]> second = RaidFootingSearch.gatherOffsets(2);
        assertEquals(first.size(), second.size());
        assertFalse(first.get(0)[0] == second.get(0)[0] && first.get(0)[1] == second.get(0)[1],
            "two gathering followers must not start on the same block");
        for (int[] o : first) {
            int sq = o[0] * o[0] + o[1] * o[1];
            assertTrue(sq > 0 && sq <= RaidFootingSearch.GATHER_RADIUS * RaidFootingSearch.GATHER_RADIUS,
                "offset out of the gather circle");
        }
    }

    @Test
    void surfaceReachIsSymmetricAndBounded() {
        assertTrue(RaidFootingSearch.withinReach(98, 72, RaidFootingSearch.SURFACE_REACH),
            "Elmfield's western hill, 26 above the Banner, is an approach");
        assertTrue(RaidFootingSearch.withinReach(63, 72, RaidFootingSearch.SURFACE_REACH));
        assertFalse(RaidFootingSearch.withinReach(-61, 40, RaidFootingSearch.SURFACE_REACH),
            "flat ground a hundred blocks below a sky platform is not");
        assertFalse(RaidFootingSearch.withinReach(76, 72, RaidFootingSearch.GATHER_STEP_HEIGHT));
    }
}
