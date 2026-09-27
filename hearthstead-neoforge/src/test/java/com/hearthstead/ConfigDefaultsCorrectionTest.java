package com.hearthstead;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA-CLIENT-01: a brand-new game directory has no config files, so NeoForge
 * builds each default by correcting an EMPTY config -- every value is tested
 * with null first. A validator that throws there (List.of(...).contains(null))
 * stops the client at "Error loading mods". Both registered specs must correct
 * an empty config, and a bad value, without throwing.
 */
class ConfigDefaultsCorrectionTest {

    private static int correct(ModConfigSpec spec, CommentedConfig config) {
        return spec.correct(config, (action, path, incorrect, corrected) -> { });
    }

    @Test
    void clientSpecBuildsItsDefaultsFromAnEmptyConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        assertDoesNotThrow(() -> correct(HearthsteadClientConfig.SPEC, config));
        assertEquals("top", config.get("hud.healthCounter"), "the health counter gets its default");
        assertTrue(HearthsteadClientConfig.SPEC.isCorrect(config), "the corrected client config is complete");
    }

    @Test
    void serverSpecBuildsItsDefaultsFromAnEmptyConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        assertDoesNotThrow(() -> correct(HearthsteadServerConfig.SPEC, config));
        assertTrue(HearthsteadServerConfig.SPEC.isCorrect(config), "the corrected server config is complete");
    }

    @Test
    void anUnknownHealthCounterValueFallsBackToTheDefault() {
        CommentedConfig config = CommentedConfig.inMemory();
        correct(HearthsteadClientConfig.SPEC, config);
        config.set("hud.healthCounter", "sometimes");
        assertFalse(HearthsteadClientConfig.SPEC.isCorrect(config));
        assertDoesNotThrow(() -> correct(HearthsteadClientConfig.SPEC, config));
        assertEquals("top", config.get("hud.healthCounter"));
    }

    @Test
    void aValidOffValueIsKept() {
        CommentedConfig config = CommentedConfig.inMemory();
        correct(HearthsteadClientConfig.SPEC, config);
        config.set("hud.healthCounter", "off");
        assertTrue(HearthsteadClientConfig.SPEC.isCorrect(config));
        assertEquals(0, correct(HearthsteadClientConfig.SPEC, config), "nothing to correct");
        assertEquals("off", config.get("hud.healthCounter"));
    }
}
