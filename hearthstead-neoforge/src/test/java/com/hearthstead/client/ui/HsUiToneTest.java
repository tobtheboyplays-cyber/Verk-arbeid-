package com.hearthstead.client.ui;

import com.hearthstead.Hearthstead;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HsUiToneTest {

    private static final Map<HsUi.Tone, ExpectedPaths> EXPECTED_PATHS = Map.of(
        HsUi.Tone.ACCENT, new ExpectedPaths("widget/pip_accent", "bar/fill_good"),
        HsUi.Tone.GOOD, new ExpectedPaths("widget/pip_good", "bar/fill_good"),
        HsUi.Tone.WARN, new ExpectedPaths("widget/pip_warn", "bar/fill_warn"),
        HsUi.Tone.BAD, new ExpectedPaths("widget/pip_bad", "bar/fill_bad")
    );

    @Test
    void everyToneCachesItsExpectedSpriteLocations() {
        for (HsUi.Tone tone : HsUi.Tone.values()) {
            ExpectedPaths expected = EXPECTED_PATHS.get(tone);
            ResourceLocation pip = tone.pip();
            ResourceLocation barFill = tone.barFill();

            assertSame(pip, tone.pip(), tone + " must cache its pip location");
            assertSame(barFill, tone.barFill(), tone + " must cache its bar-fill location");
            assertLocation(pip, expected.pip(), tone + " pip");
            assertLocation(barFill, expected.barFill(), tone + " bar fill");
        }
    }

    private static void assertLocation(ResourceLocation location, String path, String message) {
        assertEquals(Hearthstead.MODID, location.getNamespace(), message + " namespace");
        assertEquals(path, location.getPath(), message + " path");
    }

    private record ExpectedPaths(String pip, String barFill) {
    }
}
