package com.hearthstead.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerScreenLanguageContractTest {

    private static final List<String> COMPACT_KEYS = List.of(
        "hearthstead.settler.compact.right_now",
        "hearthstead.settler.compact.current_task",
        "hearthstead.settler.compact.blocker",
        "hearthstead.settler.compact.pace",
        "hearthstead.settler.compact.attributes",
        "hearthstead.settler.compact.job_focus",
        "hearthstead.settler.compact.job_effects",
        "hearthstead.settler.compact.attribute_toggle.tip",
        "hearthstead.settler.compact.role_workplace",
        "hearthstead.settler.compact.request",
        "hearthstead.settler.compact.traits",
        "hearthstead.settler.compact.traits.none",
        "hearthstead.settler.compact.inventory",
        "hearthstead.settler.compact.workplace",
        "hearthstead.settler.compact.actions",
        "hearthstead.settler.compact.job.none",
        "hearthstead.settler.compact.job.value",
        "hearthstead.settler.compact.job.core",
        "hearthstead.settler.compact.job.support",
        "hearthstead.settler.compact.job.lumber.live",
        "hearthstead.settler.compact.job.lumber.band",
        "hearthstead.settler.compact.job.lumber.live.detail",
        "hearthstead.settler.compact.job.stamina.live",
        "hearthstead.settler.compact.job.stamina.band",
        "hearthstead.settler.compact.job.stamina.live.detail",
        "hearthstead.settler.compact.job.values",
        "hearthstead.settler.compact.job.priority_only",
        "hearthstead.settler.compact.job.effect.physical_output",
        "hearthstead.settler.compact.job.effect.physical_output.band",
        "hearthstead.settler.compact.job.effect.fatigue_pace",
        "hearthstead.settler.compact.job.effect.fatigue_pace.band",
        "hearthstead.settler.compact.job.effect.learning_rate",
        "hearthstead.settler.compact.job.effect.learning_rate.band",
        "hearthstead.settler.compact.job.effect.precision_execution",
        "hearthstead.settler.compact.job.effect.precision_execution.band",
        "hearthstead.settler.compact.job.effect.morale_resilience",
        "hearthstead.settler.compact.job.effect.morale_resilience.band",
        "hearthstead.settler.compact.job.effect.target_discovery",
        "hearthstead.settler.compact.job.effect.target_discovery.band",
        "hearthstead.settler.compact.job.effect.task_continuity",
        "hearthstead.settler.compact.job.effect.task_continuity.band",
        "hearthstead.settler.compact.job.effect.social_influence",
        "hearthstead.settler.compact.job.effect.social_influence.band",
        "hearthstead.settler.compact.job.effect.carry_capacity",
        "hearthstead.settler.compact.job.effect.carry_capacity.band",
        "hearthstead.settler.compact.job.effect.lumber_contacts",
        "hearthstead.settler.compact.job.effect.lumber_contacts.band",
        "hearthstead.attribute.strength.compact",
        "hearthstead.attribute.strength.abbr",
        "hearthstead.attribute.stamina.compact",
        "hearthstead.attribute.stamina.abbr",
        "hearthstead.attribute.wits.compact",
        "hearthstead.attribute.wits.abbr",
        "hearthstead.attribute.dexterity.compact",
        "hearthstead.attribute.dexterity.abbr",
        "hearthstead.attribute.spirit.compact",
        "hearthstead.attribute.spirit.abbr",
        "hearthstead.attribute.perception.compact",
        "hearthstead.attribute.perception.abbr",
        "hearthstead.attribute.focus.compact",
        "hearthstead.attribute.focus.abbr",
        "hearthstead.attribute.presence.compact",
        "hearthstead.attribute.presence.abbr"
    );

    @Test
    void compactCitizenDossierHasEnglishCopy() throws Exception {
        JsonObject english = language("en_us");
        for (String key : COMPACT_KEYS) {
            assertText(english, key);
        }
    }

    private static JsonObject language(String locale) throws Exception {
        String path = "assets/hearthstead/lang/" + locale + ".json";
        var stream = SettlerScreenLanguageContractTest.class.getClassLoader()
            .getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream,
            StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void assertText(JsonObject language, String key) {
        assertTrue(language.has(key), "missing language key " + key);
        assertTrue(!language.get(key).getAsString().isBlank(),
            "blank language key " + key);
    }
}
