package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tavern lane: drunkenness levels, wear-off, movement and work penalties, the watch stays sober. */
final class DrunkennessTest {
    @Test
    void oneAleIsATipsyGlowTwoIsDrunkThreeIsVeryDrunk() {
        double p = 0;
        p = Drunkenness.addAle(p);
        assertEquals(Drunkenness.TIPSY, Drunkenness.level(p));
        assertEquals(0.0, Drunkenness.speedPenalty(Drunkenness.level(p)), 1e-9, "a tipsy glow does not slow anyone");
        assertEquals(0.0, Drunkenness.weaveAmplitude(Drunkenness.level(p)), 1e-9, "nor weave");
        p = Drunkenness.addAle(p);
        assertEquals(Drunkenness.DRUNK, Drunkenness.level(p));
        assertEquals(-0.25, Drunkenness.speedPenalty(Drunkenness.level(p)), 1e-9);
        assertEquals(-0.10, Drunkenness.workCut(Drunkenness.level(p)), 1e-9);
        p = Drunkenness.addAle(p);
        assertEquals(Drunkenness.VERY, Drunkenness.level(p));
        assertEquals(-0.40, Drunkenness.speedPenalty(Drunkenness.level(p)), 1e-9);
        assertEquals(-0.20, Drunkenness.workCut(Drunkenness.level(p)), 1e-9);
        for (int i = 0; i < 10; i++) p = Drunkenness.addAle(p);
        assertEquals(Drunkenness.MAX_POINTS, p, 1e-9, "capped");
        assertEquals(Drunkenness.VERY, Drunkenness.level(p));
    }

    @Test
    void itWearsOffAboutTwoMinutesPerLevel() {
        double p = 3.0;
        assertEquals(Drunkenness.VERY, Drunkenness.level(p));
        p = Drunkenness.decay(p, Drunkenness.DECAY_TICKS);
        assertEquals(Drunkenness.DRUNK, Drunkenness.level(p));
        p = Drunkenness.decay(p, Drunkenness.DECAY_TICKS);
        assertEquals(Drunkenness.TIPSY, Drunkenness.level(p));
        p = Drunkenness.decay(p, Drunkenness.DECAY_TICKS);
        assertEquals(Drunkenness.SOBER, Drunkenness.level(p));
        assertEquals(0.0, Drunkenness.decay(p, 100000), 1e-9, "never below sober");
        assertEquals(2400, Drunkenness.DECAY_TICKS, "two in-game minutes per level");
    }

    @Test
    void oneAleStaysTipsyForItsFullTwoMinutes() {
        double one = Drunkenness.addAle(0.0);
        assertEquals(Drunkenness.TIPSY, Drunkenness.level(Drunkenness.decay(one, 20)), "20 ticks later: still tipsy");
        assertEquals(Drunkenness.TIPSY, Drunkenness.level(Drunkenness.decay(one, 2399)), "tick 2399: still tipsy");
        assertEquals(Drunkenness.SOBER, Drunkenness.level(Drunkenness.decay(one, 2400)), "tick 2400: sober");
        // the server decays in 20-tick steps: the same boundaries hold (no floating residue)
        double p = one;
        for (int i = 0; i < 119; i++) p = Drunkenness.decay(p, 20);
        assertEquals(Drunkenness.TIPSY, Drunkenness.level(p), "step 119 x 20 ticks: tipsy");
        p = Drunkenness.decay(p, 20);
        assertEquals(Drunkenness.SOBER, Drunkenness.level(p), "step 120: sober");
        assertEquals(0.0, p, 0.0, "fully worn off");
    }

    @Test
    void twoAlesSpacedApartStillMakeYouDrunk() {
        for (long gap : new long[] {20, 600, 1200, 2000, 2399}) {
            double p = Drunkenness.addAle(Drunkenness.decay(Drunkenness.addAle(0.0), gap));
            assertEquals(Drunkenness.DRUNK, Drunkenness.level(p), "second ale " + gap + " ticks after the first");
            assertEquals(-0.25, Drunkenness.speedPenalty(Drunkenness.level(p)), 1e-9);
        }
        double late = Drunkenness.addAle(Drunkenness.decay(Drunkenness.addAle(0.0), 2400));
        assertEquals(Drunkenness.TIPSY, Drunkenness.level(late), "the first ale had fully worn off");
        double three = Drunkenness.addAle(Drunkenness.decay(Drunkenness.addAle(Drunkenness.decay(Drunkenness.addAle(0.0), 900)), 900));
        assertEquals(Drunkenness.VERY, Drunkenness.level(three), "three ales over a minute and a half: very drunk");
    }

    @Test
    void savedDrunkennessLoadsWithTheElapsedWearOff() {
        assertEquals(Drunkenness.DRUNK, Drunkenness.level(Drunkenness.loadPoints(3.0, 1000, 1000 + 2400)));
        assertEquals(3.0, Drunkenness.loadPoints(3.0, 5000, 5000), 1e-9, "no time passed");
        assertEquals(3.0, Drunkenness.loadPoints(3.0, 9000, 5000), 1e-9, "a stamp in the future never adds points");
        assertEquals(Drunkenness.MAX_POINTS, Drunkenness.loadPoints(99.0, 0, 0), 1e-9, "bounded");
        assertEquals(0.0, Drunkenness.loadPoints(Double.NaN, 0, 0), 0.0);
        assertEquals(0.0, Drunkenness.loadPoints(-2.0, 0, 0), 0.0);
        assertEquals(0.0, Drunkenness.loadPoints(2.0, 0, 100000), 0.0, "long gone");
    }

    @Test
    void theWeaveIsBoundedAndPerPerson() {
        for (int level = 0; level <= 3; level++) {
            double a = Drunkenness.weaveAmplitude(level), max = 0;
            for (double t = 0; t < 60; t += 0.05) max = Math.max(max, Math.abs(Drunkenness.weave(42L, t, level)));
            assertTrue(max <= a + 1e-9, "weave within its amplitude");
            if (level >= Drunkenness.DRUNK) assertTrue(max > a * 0.5, "a drunk really weaves");
        }
        double a = Drunkenness.weave(1L, 3.3, Drunkenness.VERY), b = Drunkenness.weave(2L, 3.3, Drunkenness.VERY);
        assertTrue(Math.abs(a - b) > 1e-6, "two drunks never weave in step");
    }

    @Test
    void guardsAndArchersNeverGetDrunk() {
        for (Profession p : Profession.values())
            assertEquals(!p.martial(), Drunkenness.mayGetDrunk(p), p + ": only the watch stays sober");
        assertFalse(Drunkenness.mayGetDrunk(Profession.GUARD));
        assertFalse(Drunkenness.mayGetDrunk(Profession.ARCHER));
    }

    @Test
    void hiccupsAreOccasionalAndGrowWithTheLevel() {
        assertEquals(0.0, DrunkSounds.hiccupChance(0), 1e-9);
        assertTrue(DrunkSounds.hiccupChance(1) < DrunkSounds.hiccupChance(2)
            && DrunkSounds.hiccupChance(2) < DrunkSounds.hiccupChance(3));
        assertTrue(1.0 / DrunkSounds.hiccupChance(3) >= 8.0, "at most one hiccup every 8 s or so");
        assertEquals(0.0, DrunkSounds.burpChance(1), 1e-9, "no burps on a tipsy glow");
    }
}
