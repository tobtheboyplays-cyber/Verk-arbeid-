package com.hearthstead;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner rule (26 Sep): every player-visible text is English. Guards both
 * en_us.json files: no Norwegian letters (outside a small whitelist of
 * personal names) and none of the common Norwegian words that used to leak
 * into research names and messages. Keys and internal ids (e.g.
 * {@code research.project.bedre_gjaer}) are not checked; only values are.
 */
class EnglishOnlyTextTest {
    private static final List<String> LANG_FILES = List.of(
        "/assets/hearthstead/lang/en_us.json",
        "/assets/hearthstead_ambient/lang/en_us.json");

    /** Personal names from the Nordic name pool may keep their letters. */
    private static final Set<String> NAME_WHITELIST = Set.of(
        "Bjørn", "Søren", "Åse", "Ørjan", "Håkon", "Sigbjørn", "Torbjørn", "Ståle", "Kåre", "Mårten");

    private static final Pattern NORWEGIAN_LETTERS = Pattern.compile("[æøåÆØÅ]");

    /** Common Norwegian words (no English homographs) that must never be shown. */
    private static final Pattern NORWEGIAN_WORDS = Pattern.compile("\\b(ikke|jeg|bygning|tømmer|tommer|gjær|gjaer"
        + "|garvesyre|blestring|åkerskifte|akerskifte|prøvebenken|provebenken|tørrsett|torrsett|velkommen|takk"
        + "|landsby|kjøpmann|snekker|bryggeri|sagbruk|bonde|ferdig|mangler|venter|bedre|vaktdrill|arbeider"
        + "|hjem|nybygg|kvern|låve|gård|smie|garveri|vevstue|fiskebu|bakeri|slakter|kornmølle)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Test
    void everyLangValueIsEnglish() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String path : LANG_FILES) {
            JsonObject lang = read(path);
            for (Map.Entry<String, JsonElement> entry : lang.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                String value = entry.getValue().getAsString();
                String stripped = value;
                for (String name : NAME_WHITELIST) stripped = stripped.replace(name, "");
                if (NORWEGIAN_LETTERS.matcher(stripped).find()) {
                    problems.add(path + " " + entry.getKey() + " has Norwegian letters: " + value);
                }
                var word = NORWEGIAN_WORDS.matcher(value.toLowerCase(Locale.ROOT));
                if (word.find()) {
                    problems.add(path + " " + entry.getKey() + " has the Norwegian word '" + word.group() + "': " + value);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theGuardCatchesWhatItShould() {
        assertTrue(NORWEGIAN_WORDS.matcher("bedre gjær").find());
        assertTrue(NORWEGIAN_LETTERS.matcher("Åkerskifte").find());
        assertTrue(!NORWEGIAN_WORDS.matcher("Better Yeast, Crop Rotation, Seasoned Timber").find());
    }

    private static JsonObject read(String path) throws Exception {
        try (InputStream in = EnglishOnlyTextTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
