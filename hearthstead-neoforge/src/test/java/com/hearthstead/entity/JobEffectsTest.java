package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobEffectsTest {

    private static final double EPSILON = 1.0E-9D;

    @Test
    void fatigueAndPaceAreBoundedAndMonotonic() {
        assertEquals(1.0D, JobEffects.fatigue(-20.0D), EPSILON);
        assertEquals(1.0D, JobEffects.fatigue(0.0D), EPSILON);
        assertEquals(0.0D, JobEffects.fatigue(70.0D), EPSILON);
        assertEquals(0.0D, JobEffects.fatigue(1000.0D), EPSILON);
        assertEquals(1.0D, JobEffects.fatigue(Double.NaN), EPSILON);

        assertEquals(0.65D, JobEffects.minimumPace(-1), EPSILON);
        assertEquals(0.65D + 0.0015D * 99.0D,
            JobEffects.minimumPace(1000), EPSILON);
        assertEquals(0.65D, JobEffects.workPace(0.0D, 0), EPSILON);
        assertEquals(1.0D, JobEffects.workPace(70.0D, 0), EPSILON);
        assertTrue(JobEffects.workPace(20.0D, 80)
            > JobEffects.workPace(20.0D, 20));
        assertTrue(JobEffects.workPace(50.0D, 20)
            > JobEffects.workPace(10.0D, 20));
    }

    @Test
    void staminaDrainAndRecoveryUseTheLockedFormulas() {
        assertEquals(0.09D, JobEffects.workingDrain(0, 1.0D, 1.0D), EPSILON);
        assertEquals(0.09D * (1.0D - 0.0025D * 99.0D),
            JobEffects.workingDrain(99, 1.0D, 1.0D), EPSILON);
        assertTrue(JobEffects.workingDrain(80, 1.0D, 1.0D)
            < JobEffects.workingDrain(20, 1.0D, 1.0D));
        assertEquals(0.0D,
            JobEffects.workingDrain(50, Double.NaN, 1.0D), EPSILON);
        assertEquals(1.5D, JobEffects.sleepRecovery(0), EPSILON);
        assertEquals(1.5D * (1.0D + 0.002D * 99.0D),
            JobEffects.sleepRecovery(99), EPSILON);
        assertEquals(1.2D, JobEffects.restRecovery(0), EPSILON);
        assertEquals(1.2D * (1.0D + 0.002D * 99.0D),
            JobEffects.restRecovery(99), EPSILON);
    }

    @Test
    void carryBudgetsFollowStrengthTraitAndHardCaps() {
        assertEquals(8, JobEffects.carryItems(0, 1.0D));
        assertEquals(16, JobEffects.carryMass(0, 1.0D));
        assertEquals(10, JobEffects.carryItems(25, 1.0D));
        assertEquals(18, JobEffects.carryMass(25, 1.0D));
        assertEquals(19, JobEffects.carryItems(99, 1.25D));
        assertEquals(29, JobEffects.carryMass(99, 1.25D));
        assertEquals(20, JobEffects.carryItems(999, 4.0D));
        assertEquals(30, JobEffects.carryMass(999, 4.0D));
        assertTrue(JobEffects.carryItems(-1, -1.0D) >= 0);
        assertTrue(JobEffects.carryMass(-1, -1.0D) >= 0);
    }

    @Test
    void lumberContactsChangeAtExactStrengthThresholds() {
        assertEquals(4, JobEffects.lumberContacts(-1));
        assertEquals(4, JobEffects.lumberContacts(0));
        assertEquals(4, JobEffects.lumberContacts(24));
        assertEquals(3, JobEffects.lumberContacts(25));
        assertEquals(3, JobEffects.lumberContacts(69));
        assertEquals(2, JobEffects.lumberContacts(70));
        assertEquals(2, JobEffects.lumberContacts(99));
        assertEquals(2, JobEffects.lumberContacts(Integer.MAX_VALUE));
    }
}
