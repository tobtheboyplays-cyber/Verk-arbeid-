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

    @Test
    void settlementStatusCacheInvalidatesForStateFontLanguageAndLayout() {
        Object font = new Object();

        assertTrue(HearthScreen.statusRenderCacheMatches(0, font, "en_us", 236,
            0, font, "en_us", 236));
        assertFalse(HearthScreen.statusRenderCacheMatches(0, font, "en_us", 236,
            1, font, "en_us", 236));
        assertFalse(HearthScreen.statusRenderCacheMatches(0, font, "en_us", 236,
            0, new Object(), "en_us", 236));
        assertFalse(HearthScreen.statusRenderCacheMatches(0, font, "en_us", 236,
            0, font, "nb_no", 236));
        assertFalse(HearthScreen.statusRenderCacheMatches(0, font, "en_us", 236,
            0, font, "en_us", 192));
    }

    @Test
    void mayorRosterCacheTracksSnapshotLocaleLayoutRowsAndPage() {
        Object snapshot = new Object();
        assertTrue(HearthScreen.mayorRenderCacheMatches(snapshot, "en_us",
            411, 224, 3, 0, snapshot, "en_us", 411, 224, 3, 0));
        assertFalse(HearthScreen.mayorRenderCacheMatches(snapshot, "en_us",
            411, 224, 3, 0, new Object(), "en_us", 411, 224, 3, 0));
        assertFalse(HearthScreen.mayorRenderCacheMatches(snapshot, "en_us",
            411, 224, 3, 0, snapshot, "nb_no", 411, 224, 3, 0));
        assertFalse(HearthScreen.mayorRenderCacheMatches(snapshot, "en_us",
            411, 224, 3, 0, snapshot, "en_us", 304, 224, 3, 0));
        assertFalse(HearthScreen.mayorRenderCacheMatches(snapshot, "en_us",
            411, 224, 3, 0, snapshot, "en_us", 411, 224, 2, 0));
        assertFalse(HearthScreen.mayorRenderCacheMatches(snapshot, "en_us",
            411, 224, 3, 0, snapshot, "en_us", 411, 224, 3, 1));
    }

    @Test
    void mayorInitialIsStableForEmptyAsciiAndUnicodeNames() {
        assertEquals("?", HearthScreen.firstInitial(""));
        assertEquals("R", HearthScreen.firstInitial(" Runa"));
        assertEquals("Å", HearthScreen.firstInitial("Åse"));
    }
}
