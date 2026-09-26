package com.hearthstead.client.render;

import org.junit.jupiter.api.Test;

import static com.hearthstead.client.render.HealthCounterRules.Mode.LOOK_AT;
import static com.hearthstead.client.render.HealthCounterRules.Mode.OFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Visibility, fade, text and HUD layout of the look-at health counter plate. */
class HealthCounterRulesTest {

    @Test
    void lookedAtWithinRangeWins() {
        assertEquals(7, HealthCounterRules.target(LOOK_AT, 7, 10.0, 3, 100, 4, 100));
        assertEquals(-1, HealthCounterRules.target(LOOK_AT, 7, 16.5, -1, -1, -1, -1),
            "beyond 16 blocks a look shows nothing");
        assertEquals(-1, HealthCounterRules.target(OFF, 7, 2.0, 7, 0, 7, 0), "off means off");
        assertEquals(-1, HealthCounterRules.target(LOOK_AT, -1, 0, -1, -1, -1, -1), "calm: nothing");
    }

    @Test
    void lookLingersForOneAndAHalfSecondsAndHitsForThree() {
        assertEquals(3, HealthCounterRules.target(LOOK_AT, -1, 0, 3, 1500, -1, -1));
        assertEquals(-1, HealthCounterRules.target(LOOK_AT, -1, 0, 3, 1501, -1, -1));
        assertEquals(4, HealthCounterRules.target(LOOK_AT, -1, 0, -1, -1, 4, 3000));
        assertEquals(-1, HealthCounterRules.target(LOOK_AT, -1, 0, -1, -1, 4, 3001));
        assertEquals(4, HealthCounterRules.target(LOOK_AT, -1, 0, 3, 900, 4, 200), "the fresher one wins");
        assertEquals(3, HealthCounterRules.target(LOOK_AT, -1, 0, 3, 100, 4, 2500));
    }

    @Test
    void fadeTakesTwoHundredMilliseconds() {
        assertEquals(0.5F, HealthCounterRules.approach(0.0F, true, 100), 1e-6);
        assertEquals(1.0F, HealthCounterRules.approach(0.0F, true, 200), 1e-6);
        assertEquals(0.0F, HealthCounterRules.approach(1.0F, false, 250), 1e-6);
        assertEquals(1.0F, HealthCounterRules.approach(1.0F, true, 16), 1e-6);
    }

    @Test
    void hiddenWithF1SpectatorScreensAndWhileDowned() {
        assertTrue(HealthCounterRules.hudAllowed(false, false, false, false));
        assertFalse(HealthCounterRules.hudAllowed(true, false, false, false));
        assertFalse(HealthCounterRules.hudAllowed(false, true, false, false));
        assertFalse(HealthCounterRules.hudAllowed(false, false, true, false));
        assertFalse(HealthCounterRules.hudAllowed(false, false, false, true));
    }

    @Test
    void configValues() {
        assertEquals(OFF, HealthCounterRules.modeOf("off"));
        assertEquals(LOOK_AT, HealthCounterRules.modeOf("lookAt"));
        assertEquals(LOOK_AT, HealthCounterRules.modeOf("garbage"));
        assertEquals(LOOK_AT, HealthCounterRules.modeOf(null));
    }

    @Test
    void wholeHitPointsAndAHalfPointNeverReadsZero() {
        assertEquals("18/24", HealthCounterRules.text(18.0F, 24.0F));
        assertEquals("18/24", HealthCounterRules.text(17.6F, 24.0F));
        assertEquals("0.5/20", HealthCounterRules.text(0.5F, 20.0F));
        assertEquals("0/20", HealthCounterRules.text(0.0F, 20.0F));
    }

    @Test
    void heartDarkensAndPulsesOnlyWhenLow() {
        assertEquals(0xC8403A, HealthCounterRules.heartColour(1.0F));
        assertEquals(0x7A2622, HealthCounterRules.heartColour(0.0F));
        assertEquals(1.0F, HealthCounterRules.pulse(0.5F, 1.3F), 1e-6);
        for (float t = 0; t < 2; t += 0.05F) {
            float p = HealthCounterRules.pulse(0.2F, t);
            assertTrue(p >= 0.8F && p <= 1.0F);
        }
    }

    /** GUI scales 2-4 on common windows (only sizes Minecraft allows: height >= 240). */
    @Test
    void fullPlateWhereItFitsCompactOnlyOnShortCrowdedScreens() {
        assertEquals(HealthCounterRules.PLATE_H_FULL, HealthCounterRules.plateHeight(360, 39));
        assertEquals(HealthCounterRules.PLATE_H_FULL, HealthCounterRules.plateHeight(240, 39));
        assertEquals(HealthCounterRules.PLATE_H_COMPACT, HealthCounterRules.plateHeight(240, 69));
    }

    private static final int[][] VIEWPORTS = {
        {960, 540}, {640, 360}, {480, 270}, {683, 384}, {455, 256}, {640, 360}, {426, 240}, {1280, 720}
    };

    @Test
    void plateClearsHotbarStatsItemNameActionBarAndFinisherPrompt() {
        int plateW = 70;
        for (int[] v : VIEWPORTS) {
            int w = v[0];
            int h = v[1];
            // 39 = one heart row; 49 = armour (or Detail Armor Bar) row; 69 = extra absorption rows.
            for (int stack : new int[]{39, 49, 59, 69}) {
                int[] r = HealthCounterRules.plate(w, h, plateW,
                    HealthCounterRules.plateHeight(h, stack), stack);
                String at = w + "x" + h + " stack " + stack;
                int bottom = r[1] + r[3];
                assertTrue(r[0] >= 0 && r[0] + r[2] <= w, "inside the screen " + at);
                assertTrue(r[1] >= 0, "not off the top " + at);
                assertTrue(bottom <= h - Math.max(stack, 59) - 1, "above the item name and every stat row " + at);
                assertTrue(bottom <= h - HealthCounterRules.HOTBAR_H - 7, "above hotbar and XP bar " + at);
                assertTrue(r[1] >= h / 2 + 26 || bottom <= h / 2 + 11,
                    "never on the finisher prompt under the crosshair " + at);
                boolean aboveActionBar = bottom <= HealthCounterRules.overlayMessageTop(h, stack) - 1;
                if (h >= 300 && stack <= 49) {
                    assertTrue(aboveActionBar, "roomy screens keep the action-bar line free " + at);
                }
            }
        }
    }
}
