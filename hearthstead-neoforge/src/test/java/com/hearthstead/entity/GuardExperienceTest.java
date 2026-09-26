package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuardExperienceTest {

    @Test
    void everyBoundarySelectsTheExpectedTier() {
        assertEquals(GuardExperience.Tier.RECRUIT, GuardExperience.tierOf(-1));
        assertEquals(GuardExperience.Tier.RECRUIT, GuardExperience.tierOf(39));
        assertEquals(GuardExperience.Tier.TRAINED, GuardExperience.tierOf(40));
        assertEquals(GuardExperience.Tier.TRAINED, GuardExperience.tierOf(119));
        assertEquals(GuardExperience.Tier.VETERAN, GuardExperience.tierOf(120));
        assertEquals(GuardExperience.Tier.VETERAN, GuardExperience.tierOf(259));
        assertEquals(GuardExperience.Tier.ELITE, GuardExperience.tierOf(260));
        assertEquals(GuardExperience.Tier.ELITE, GuardExperience.tierOf(479));
        assertEquals(GuardExperience.Tier.HERO, GuardExperience.tierOf(480));
        assertEquals(GuardExperience.Tier.HERO,
            GuardExperience.tierOf(Integer.MAX_VALUE));
    }

    @Test
    void additionSaturatesAndCannotOverflowOrSubtract() {
        assertEquals(15, GuardExperience.add(0, 15));
        assertEquals(GuardExperience.MAX_EXPERIENCE,
            GuardExperience.add(470, Integer.MAX_VALUE));
        assertEquals(GuardExperience.MAX_EXPERIENCE,
            GuardExperience.add(Integer.MAX_VALUE, 15));
        assertEquals(GuardExperience.MAX_EXPERIENCE,
            GuardExperience.add(GuardExperience.MAX_EXPERIENCE, 15));
        assertEquals(20, GuardExperience.add(20, -10));
        assertEquals(10, GuardExperience.add(-100, 10));
    }

    @Test
    void progressIsBoundedAndUsesTheCurrentTierSpan() {
        assertEquals(0.0F, GuardExperience.progress(0));
        assertEquals(0.5F, GuardExperience.progress(20));
        assertEquals(0.0F, GuardExperience.progress(40));
        assertEquals(0.5F, GuardExperience.progress(80));
        assertEquals(1.0F, GuardExperience.progress(480));
        assertEquals(1.0F, GuardExperience.progress(Integer.MAX_VALUE));
    }
}
