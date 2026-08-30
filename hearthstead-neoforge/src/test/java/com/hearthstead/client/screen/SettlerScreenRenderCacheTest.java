package com.hearthstead.client.screen;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlerScreenRenderCacheTest {

    @Test
    void viewCacheRequiresTheSameSnapshotLayoutCombatFontAndLanguage() {
        Object snapshot = new Object();
        Object font = new Object();

        assertTrue(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18,
            font, "en_us", snapshot, 336, 340, 18, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18,
            font, "en_us", new Object(), 336, 340, 18, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18,
            font, "en_us", snapshot, 320, 340, 18, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18,
            font, "en_us", snapshot, 336, 340, 19, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18,
            font, "en_us", snapshot, 336, 340, 18, new Object(), "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18,
            font, "en_us", snapshot, 336, 340, 18, font, "nb_no"));
    }

    @Test
    void needNumberCacheReusesTheRenderedIntegerOnly() {
        SettlerScreen.NeedValueCache cache = new SettlerScreen.NeedValueCache();

        Component twelve = cache.valueFor(0, 12.9F);
        assertSame(twelve, cache.valueFor(0, 12.1F));
        assertNotSame(twelve, cache.valueFor(1, 12.1F),
            "each need owns a distinct cache slot even when values match");
        Component thirteen = cache.valueFor(0, 13.0F);
        assertNotSame(twelve, thirteen);
        assertEquals("12%", cache.valueFor(3, 12.9F).getString(),
            "Work Pace keeps its existing percent suffix");
    }
}
