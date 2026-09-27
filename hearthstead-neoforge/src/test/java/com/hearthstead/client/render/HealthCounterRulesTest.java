package com.hearthstead.client.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.hearthstead.client.render.HealthCounterRules.Mode.OFF;
import static com.hearthstead.client.render.HealthCounterRules.Mode.TOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Visibility, fade, config, text, gold rule and top-of-screen placement of the heart counter. */
class HealthCounterRulesTest {

    @Test
    void showsOnlyTheAimedTargetWithinSixteenBlocks() {
        assertEquals(7, HealthCounterRules.target(TOP, 7, 10.0));
        assertEquals(7, HealthCounterRules.target(TOP, 7, 16.0));
        assertEquals(-1, HealthCounterRules.target(TOP, 7, 16.5), "beyond 16 blocks: nothing");
        assertEquals(-1, HealthCounterRules.target(TOP, -1, 0.0), "looking at nobody: nothing");
        assertEquals(-1, HealthCounterRules.target(OFF, 7, 2.0), "off means off");
    }

    @Test
    void fadesInQuicklyAndOutOverAboutASecond() {
        assertEquals(1.0F, HealthCounterRules.approach(0.0F, true, 150), 1e-6);
        assertEquals(0.5F, HealthCounterRules.approach(1.0F, false, 500), 1e-6);
        assertEquals(0.0F, HealthCounterRules.approach(1.0F, false, 1000), 1e-6);
        assertTrue(HealthCounterRules.approach(1.0F, false, 900) > 0.0F, "still visible just before 1 s");
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
    void topIsTheDefaultAndOldLookAtConfigsMapToTop() {
        assertEquals(TOP, HealthCounterRules.modeOf("top"));
        assertEquals(TOP, HealthCounterRules.modeOf("lookAt"), "no one keeps the retired box");
        assertEquals(TOP, HealthCounterRules.modeOf(null));
        assertEquals(TOP, HealthCounterRules.modeOf("garbage"));
        assertEquals(OFF, HealthCounterRules.modeOf("off"));
        assertEquals(OFF, HealthCounterRules.modeOf(" OFF "));
    }

    @Test
    void sitsAtTheTopOrJustUnderTheLastBossBar() {
        assertEquals(HealthCounterRules.TOP_Y, HealthCounterRules.topY(-1));
        // vanilla draws boss bars at y = 12, 31, 50 ... (5 px tall, name 9 px above the next one)
        assertEquals(12 + 5 + 4, HealthCounterRules.topY(12));
        assertEquals(31 + 5 + 4, HealthCounterRules.topY(31));
        assertTrue(HealthCounterRules.topY(31) + 9 < 50 - 9 + 9, "never on top of a third bar's name line");
    }

    @Test
    void wholeHitPointsAndAHalfPointNeverReadsZero() {
        assertEquals("14 / 20", HealthCounterRules.text(14.0F, 20.0F));
        assertEquals("14 / 20", HealthCounterRules.text(13.6F, 20.0F));
        assertEquals("0.5 / 20", HealthCounterRules.text(0.5F, 20.0F));
        assertEquals("0 / 24", HealthCounterRules.text(0.0F, 24.0F));
    }

    @Test
    void goldOnlyWhileFinishable() {
        assertEquals(0.0F, HealthCounterRules.finisherPulse(false, 1.7F), 1e-6);
        for (float t = 0; t < 3; t += 0.05F) {
            float p = HealthCounterRules.finisherPulse(true, t);
            assertTrue(p >= 0.55F && p <= 1.0F, "pulse in range at " + t + ": " + p);
        }
    }

    @Test
    void neverShowsForPlayers() throws Exception {
        for (int bits = 0; bits < 8; bits++) {
            boolean invisible = (bits & 1) != 0;
            boolean alive = (bits & 2) != 0;
            boolean npc = (bits & 4) != 0;
            assertFalse(HealthCounterRules.showsFor(true, invisible, alive, npc),
                "a player never gets a counter (invisible=" + invisible + " alive=" + alive + ")");
        }
        assertTrue(HealthCounterRules.showsFor(false, false, true, true), "a living NPC does");
        assertFalse(HealthCounterRules.showsFor(false, true, true, true), "an invisible one does not");
        assertFalse(HealthCounterRules.showsFor(false, false, false, true), "a dead one does not");
        assertFalse(HealthCounterRules.showsFor(false, false, true, false), "an animal does not");

        // The live HUD must route every target through that rule with the real player test.
        String java = Files.readString(findSource("src/main/java/com/hearthstead/client/render/HealthCounter.java"));
        assertTrue(java.contains("HealthCounterRules.showsFor(entity instanceof Player,"),
            "HealthCounter.eligible must pass 'entity instanceof Player' to showsFor");
        assertTrue(java.contains("eligible(mc.crosshairPickEntity)"),
            "the crosshair target must go through eligible");
    }

    private static Path findSource(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("could not locate " + relative);
    }
}
