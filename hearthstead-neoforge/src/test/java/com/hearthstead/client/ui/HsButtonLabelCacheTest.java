package com.hearthstead.client.ui;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HsButtonLabelCacheTest {

    @Test
    void reusesTheSingleFitWhileEveryVisualInputIsStable() {
        AtomicInteger fits = new AtomicInteger();
        HsUi.FittedLabelCache cache = cache(fits);
        Component message = Component.literal("Gather");

        HsUi.FittedLabel first = cache.fit(null, message, 64, "en_us");
        HsUi.FittedLabel second = cache.fit(null, message, 64, "en_us");

        assertSame(first, second);
        assertEquals(1, fits.get());
    }

    @Test
    void refitsWhenMessageWidthOrLanguageChanges() {
        AtomicInteger fits = new AtomicInteger();
        HsUi.FittedLabelCache cache = cache(fits);
        Component firstMessage = Component.literal("Gather");
        Component secondMessage = Component.literal("Store");

        cache.fit(null, firstMessage, 64, "en_us");
        cache.fit(null, secondMessage, 64, "en_us");
        cache.fit(null, secondMessage, 48, "en_us");
        cache.fit(null, secondMessage, 48, "nb_no");

        assertEquals(4, fits.get());
    }

    @Test
    void fontIdentityIsPartOfTheCacheKey() {
        HsUi.FittedLabel label = new HsUi.FittedLabel(Component.empty(), 0);
        Component message = Component.literal("Gather");
        Object firstFont = new Object();
        Object secondFont = new Object();

        assertTrue(HsUi.FittedLabelCache.isCurrent(label, message, firstFont, 64,
            "en_us", message, firstFont, 64, "en_us"));
        assertFalse(HsUi.FittedLabelCache.isCurrent(label, message, firstFont, 64,
            "en_us", message, secondFont, 64, "en_us"));
    }

    @Test
    void explicitInvalidationDropsTheOnlyCachedEntry() {
        AtomicInteger fits = new AtomicInteger();
        HsUi.FittedLabelCache cache = cache(fits);
        Component message = Component.literal("Gather");

        cache.fit(null, message, 64, "en_us");
        cache.invalidate();
        cache.fit(null, message, 64, "en_us");

        assertEquals(2, fits.get());
    }

    private static HsUi.FittedLabelCache cache(AtomicInteger fits) {
        return new HsUi.FittedLabelCache((font, message, width) ->
            new HsUi.FittedLabel(Component.literal(message.getString() + fits.incrementAndGet()),
                width));
    }
}
