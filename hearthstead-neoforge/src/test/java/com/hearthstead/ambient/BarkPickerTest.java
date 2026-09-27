package com.hearthstead.ambient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BarkPickerTest {
    private static final String LANG = "/assets/hearthstead_ambient/lang/en_us.json";

    @Test
    void oneSettlerSaysEveryVariantBeforeRepeating() {
        for (int variants = 1; variants <= 12; variants++) {
            for (long seed = -5; seed < 40; seed++) {
                Set<Integer> seen = new HashSet<>();
                for (int counter = 0; counter < variants; counter++) {
                    int v = BarkPicker.variant(variants, seed, 3, counter);
                    assertTrue(v >= 0 && v < variants, "in range");
                    assertTrue(seen.add(v), "no repeat within one cycle: n=" + variants + " seed=" + seed);
                }
                assertEquals(variants, seen.size());
            }
        }
    }

    @Test
    void neverTheSameLineTwiceInARow() {
        for (long seed = 0; seed < 200; seed++) {
            int previous = -1;
            for (int counter = 0; counter < 40; counter++) {
                int v = BarkPicker.variant(10, seed, 1, counter);
                assertNotEquals(previous, v, "seed " + seed + " counter " + counter);
                previous = v;
            }
        }
    }

    @Test
    void seededPerSettlerSoACrowdDoesNotOpenInUnison() {
        Set<Integer> openers = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) {
            openers.add(BarkPicker.variant(12, seed * 7919L, BarkContext.GREET.ordinal(), 0));
        }
        assertTrue(openers.size() >= 8, "50 settlers should open with many different greetings, got " + openers.size());
        assertEquals(BarkPicker.variant(12, 42L, 0, 5), BarkPicker.variant(12, 42L, 0, 5), "deterministic");
    }

    @Test
    void keysUseTheContextAndOneBasedSuffix() {
        String key = BarkPicker.key(BarkContext.RAID_WON, 7L, 0);
        assertTrue(key.startsWith("hearthstead.bark.raid_won."), key);
        int n = Integer.parseInt(key.substring(key.lastIndexOf('.') + 1));
        assertTrue(n >= 1 && n <= BarkContext.RAID_WON.variants());
        assertTrue(BarkPicker.jobKey("SMITH", 1L, 0).startsWith("hearthstead.bark.job.smith."));
        assertTrue(BarkPicker.jobKey("MAYOR", 1L, 0).startsWith("hearthstead.bark.job.generic."));
        assertTrue(BarkPicker.jobKey(null, 1L, 0).startsWith("hearthstead.bark.job.generic."));
    }

    @Test
    void everyPickableKeyHasAnEnglishLine() throws IOException {
        // The lane's own lang file (merged by Minecraft with every namespace's
        // en_us.json), read from the classpath: no shared file, no edit races.
        JsonObject json;
        try (InputStream in = BarkPickerTest.class.getResourceAsStream(LANG)) {
            assertNotNull(in, LANG + " on the classpath");
            json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (BarkContext context : BarkContext.values()) {
            if (context == BarkContext.JOB) {
                for (int i = 1; i <= context.variants(); i++) {
                    assertTrue(json.has(BarkPicker.PREFIX + "job.generic." + i), "job.generic." + i);
                }
                continue;
            }
            for (int i = 1; i <= context.variants(); i++) {
                assertTrue(json.has(BarkPicker.PREFIX + context.id() + "." + i), context.id() + "." + i);
            }
        }
        for (String trade : BarkPicker.JOB_TRADES) {
            for (int i = 1; i <= BarkPicker.JOB_VARIANTS; i++) {
                assertTrue(json.has(BarkPicker.PREFIX + "job." + trade + "." + i), trade + "." + i);
            }
        }
    }
}
