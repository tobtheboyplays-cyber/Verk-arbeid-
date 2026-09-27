package com.hearthstead.entity.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.client.render.FisherCastClock;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fisher v3: the synced phase keeps its windows and the fixed beats match the authored clips. */
class FisherV3TimingTest {
    private static final int[] CYCLES = {300, 260, 220, 198, 180, 162, 160, 144};

    @Test
    void phaseIsMonotonicAndHitsEveryWindowBoundary() {
        for (int length : CYCLES) {
            int last = -1;
            for (int t = 0; t <= length; t++) {
                int phase = FisherWorkGoal.phaseOf(t, length);
                assertTrue(phase >= last, "phase never runs backwards (cycle " + length + ")");
                assertTrue(phase >= 0 && phase <= 299);
                last = phase;
            }
            assertEquals(0, FisherWorkGoal.phaseOf(0, length));
            assertEquals(30, FisherWorkGoal.phaseOf(FisherWorkGoal.CAST_TICKS, length), "cast ends on its fixed tick");
            int bite = length - FisherWorkGoal.REEL_TICKS - FisherWorkGoal.LAND_TICKS;
            assertEquals(240, FisherWorkGoal.phaseOf(bite, length), "the bite (240, splash sound) comes on its tick");
            assertEquals(282, FisherWorkGoal.phaseOf(length - FisherWorkGoal.LAND_TICKS, length));
            assertEquals(299, FisherWorkGoal.phaseOf(length, length), "the catch commits at the cycle end");
        }
    }

    @Test
    void veryShortCyclesFallBackToTheProportionalClock() {
        assertEquals(150, FisherWorkGoal.phaseOf(50, 100));
        assertEquals(299, FisherWorkGoal.phaseOf(100, 100));
    }

    @Test
    void fixedWindowsMatchTheAuthoredClipLengths() throws Exception {
        assertEquals(FisherWorkGoal.CAST_TICKS / 20.0F, FisherCastClock.CAST_SECONDS, 0.05F);
        assertEquals(FisherWorkGoal.REEL_TICKS / 20.0F, FisherCastClock.REEL_SECONDS, 0.01F);
        assertEquals(FisherWorkGoal.LAND_TICKS / 20.0F, FisherCastClock.LAND_SECONDS, 0.01F);
        assertEquals(FisherCastClock.CAST_SECONDS, length("fisher_cast_v3"), 0.001F);
        assertEquals(FisherCastClock.REEL_SECONDS, length("fisher_strike_reel"), 0.001F);
        assertEquals(FisherCastClock.LAND_SECONDS, length("fisher_land_fish"), 0.001F);
        assertEquals(4.0F, length("fisher_wait"), 0.001F);
        assertEquals(8.0F, length("fisher_wait__v2"), 0.001F, "the wait variant is two whole base cycles");
        assertTrue(FisherCastClock.GRAB < FisherCastClock.LAND_SECONDS);
        assertTrue(FisherCastClock.LAND_SWING - FisherCastClock.LAND_UP >= 1.0F, "the hooked fish dangles at least 1 s");
    }

    @Test
    void windowsFollowThePhase() {
        assertEquals(FisherCastClock.Window.CAST, FisherCastClock.windowOf(0));
        assertEquals(FisherCastClock.Window.WAIT, FisherCastClock.windowOf(30));
        assertEquals(FisherCastClock.Window.REEL, FisherCastClock.windowOf(240));
        assertEquals(FisherCastClock.Window.LAND, FisherCastClock.windowOf(282));
        assertEquals(FisherCastClock.Window.LAND, FisherCastClock.windowOf(299));
    }

    private static float length(String stem) throws Exception {
        String path = "/assets/hearthstead/animations/settler/" + stem + ".animation.json";
        try (Reader in = new InputStreamReader(FisherV3TimingTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
            JsonObject anim = root.getAsJsonObject("animations")
                .getAsJsonObject("animation.settler." + stem);
            return anim.get("animation_length").getAsFloat();
        }
    }
}
