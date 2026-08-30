package com.hearthstead.client.screen;

import com.hearthstead.settlement.RecruitmentPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearthRenderCacheContractTest {

    @Test
    void countdownUsesOneSecondBucketsInsteadOfFrameTime() {
        assertEquals(0L, HearthScreen.countdownSecond(-1L));
        assertEquals(0L, HearthScreen.countdownSecond(0L));
        assertEquals(0L, HearthScreen.countdownSecond(19L));
        assertEquals(1L, HearthScreen.countdownSecond(20L));
        assertEquals(1L, HearthScreen.countdownSecond(39L));
        assertEquals(2L, HearthScreen.countdownSecond(40L));
    }

    @Test
    void countdownCacheInvalidatesOnlyAcrossBucketBoundary() {
        assertFalse(HearthScreen.needsCountdownRefresh(5L, 100L));
        assertFalse(HearthScreen.needsCountdownRefresh(5L, 119L));
        assertTrue(HearthScreen.needsCountdownRefresh(5L, 120L));
        assertTrue(HearthScreen.needsCountdownRefresh(Long.MIN_VALUE, 0L));
    }

    @Test
    void callToArmsQualificationKeepsDeterministicProgressVisible() {
        assertTrue(HearthScreen.recruitmentProgressVisible(
            RecruitmentPolicy.Stage.ATTRACTION,
            RecruitmentPolicy.Blocker.NONE));
        assertTrue(HearthScreen.recruitmentProgressVisible(
            RecruitmentPolicy.Stage.QUALIFYING,
            RecruitmentPolicy.Blocker.NONE));
        assertFalse(HearthScreen.recruitmentProgressVisible(
            RecruitmentPolicy.Stage.TRAVELING,
            RecruitmentPolicy.Blocker.NONE));
        assertFalse(HearthScreen.recruitmentProgressVisible(
            RecruitmentPolicy.Stage.QUALIFYING,
            RecruitmentPolicy.Blocker.NO_BED));
    }
}
