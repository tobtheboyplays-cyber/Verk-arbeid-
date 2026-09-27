package com.hearthstead.client.look;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pure parts of the client compositor: bounded failure cache and src-over maths. */
class LookTextureCacheTest {
    @Test
    void failureCacheIsBounded() {
        Set<String> failed = LookTextureCache.boundedSet(256);
        for (int i = 0; i < 10_000; i++) {
            failed.add("look" + i);
        }
        assertEquals(256, failed.size());
        assertTrue(failed.contains("look9999"));
        assertFalse(failed.contains("look0"));
    }

    @Test
    void translucentLayerNeverThinsAnOpaquePixel() {
        int opaque = 0xFF405060;
        int translucent = 0x46102030;
        int out = LookTextureCache.over(translucent, opaque);
        assertEquals(0xFF, out >>> 24, "an opaque face stays opaque under freckles or scars");
        assertEquals(0, LookTextureCache.over(0, 0));
        assertEquals(opaque, LookTextureCache.over(opaque, 0x80FFFFFF) | 0xFF000000);
    }
}
