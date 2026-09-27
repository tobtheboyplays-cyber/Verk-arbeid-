package com.hearthstead.ambient;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Playtest 27 Sep #6 guard: every bark key the picker can ever send must
 * resolve to real English words, otherwise the client would show a raw key
 * (or, before the fix, an empty plate) over the settler's head.
 */
class BarkLangKeysTest {
    private static final String AMBIENT_LANG = "assets/hearthstead_ambient/lang/en_us.json";
    private static final String MAIN_LANG = "assets/hearthstead/lang/en_us.json";

    @Test
    void everyPickableBarkKeyHasNonBlankEnglish() throws IOException {
        JsonObject ambient = read(AMBIENT_LANG);
        JsonObject main = read(MAIN_LANG);
        List<String> missing = new ArrayList<>();
        for (BarkContext context : BarkContext.values()) {
            if (context == BarkContext.JOB) {
                continue;
            }
            for (int n = 1; n <= context.variants(); n++) {
                check(ambient, main, BarkPicker.PREFIX + context.id() + "." + n, missing);
            }
        }
        for (int n = 1; n <= BarkContext.JOB.variants(); n++) {
            check(ambient, main, BarkPicker.PREFIX + "job.generic." + n, missing);
        }
        for (String trade : BarkPicker.JOB_TRADES) {
            for (int n = 1; n <= BarkPicker.JOB_VARIANTS; n++) {
                check(ambient, main, BarkPicker.PREFIX + "job." + trade + "." + n, missing);
            }
        }
        // Every key the picker actually produces for a spread of seeds.
        for (long seed = 0; seed < 64; seed++) {
            for (BarkContext context : BarkContext.values()) {
                check(ambient, main, BarkPicker.key(context, seed, (int) seed), missing);
            }
            check(ambient, main, BarkPicker.jobKey("WIZARD", seed, 3), missing);
        }
        assertTrue(missing.isEmpty(), "bark keys without English text: " + missing);
    }

    private static void check(JsonObject ambient, JsonObject main, String key, List<String> missing) {
        JsonObject source = ambient.has(key) ? ambient : main;
        if (!source.has(key) || source.get(key).getAsString().isBlank()) {
            if (!missing.contains(key)) {
                missing.add(key);
            }
        }
    }

    private static JsonObject read(String path) throws IOException {
        try (InputStream in = BarkLangKeysTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }
}
