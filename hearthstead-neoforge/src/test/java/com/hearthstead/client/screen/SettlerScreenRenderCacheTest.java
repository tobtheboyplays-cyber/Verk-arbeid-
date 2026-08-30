package com.hearthstead.client.screen;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.JobAttributeProfile;
import com.hearthstead.entity.Profession;
import com.hearthstead.client.ui.HsUi;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

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

        assertTrue(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", snapshot, 336, 340, 18, 16, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", new Object(), 336, 340, 18, 16, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", snapshot, 320, 340, 18, 16, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", snapshot, 336, 340, 19, 16, font, "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", snapshot, 336, 340, 18, 20, font, "en_us"),
            "live carry capacity invalidates the cached Lumberer job band");
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", snapshot, 336, 340, 18, 16, new Object(), "en_us"));
        assertFalse(SettlerScreen.viewCacheMatches(snapshot, 336, 340, 18, 16,
            font, "en_us", snapshot, 336, 340, 18, 16, font, "nb_no"));
    }

    @Test
    void needNumberCacheReusesTheRenderedIntegerOnly() {
        AtomicInteger fits = new AtomicInteger();
        SettlerScreen.NeedValueCache cache = new SettlerScreen.NeedValueCache(
            (font, component, width) -> {
                fits.incrementAndGet();
                return new HsUi.FittedLabel(component, component.getString().length());
            });

        HsUi.FittedLabel twelve = cache.valueFor(0, 12.9F, null);
        assertSame(twelve, cache.valueFor(0, 12.1F, null));
        assertNotSame(twelve, cache.valueFor(1, 12.1F, null),
            "each need owns a distinct cache slot even when values match");
        HsUi.FittedLabel thirteen = cache.valueFor(0, 13.0F, null);
        assertNotSame(twelve, thirteen);
        assertEquals("12%", cache.valueFor(3, 12.9F, null).text().getString(),
            "Work Pace keeps its existing percent suffix");
        assertEquals(4, fits.get(),
            "unchanged integers must reuse both their Component and measured width");
    }

    @Test
    void compactDrawPathNeverMeasuresOrBuildsTextPerFrame() throws Exception {
        Path source = findSource();
        String java = Files.readString(source);
        int start = java.indexOf("private void drawCompactHeader");
        int end = java.indexOf("private void requestChild", start);
        assertTrue(start >= 0 && end > start, "compact draw-method range moved");
        String hotPath = java.substring(start, end);

        assertFalse(hotPath.contains("HsUi.labelIn"));
        assertFalse(hotPath.contains("HsUi.right"));
        assertFalse(hotPath.contains("font.width"));
        assertFalse(hotPath.contains("Component.literal"));
        assertFalse(hotPath.contains("Component.translatable"));
        assertFalse(hotPath.contains(".split("));
    }

    private static Path findSource() {
        Path cursor = Path.of("").toAbsolutePath();
        String relative = "src/main/java/com/hearthstead/client/screen/SettlerScreen.java";
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("could not locate " + relative);
    }

    @Test
    void compactJobBandDistinguishesLiveEffectsFromRolePriorities() {
        JobAttributeProfile lumberer = JobAttributeProfile.find(
            Profession.LUMBERER).orElseThrow();
        JobAttributeProfile courier = JobAttributeProfile.find(
            Profession.COURIER).orElseThrow();

        assertEquals(SettlerScreen.JobImpactEvidence.LIVE,
            SettlerScreen.jobImpactEvidence(Profession.LUMBERER,
                slot(lumberer, Attribute.STRENGTH)));
        assertEquals(SettlerScreen.JobImpactEvidence.LIVE,
            SettlerScreen.jobImpactEvidence(Profession.LUMBERER,
                slot(lumberer, Attribute.STAMINA)));
        assertEquals(SettlerScreen.JobImpactEvidence.ROLE_PRIORITY,
            SettlerScreen.jobImpactEvidence(Profession.COURIER,
                slot(courier, Attribute.STRENGTH)),
            "a calculator without a Courier runtime call site must not be sold as live");
    }

    private static JobAttributeProfile.Slot slot(JobAttributeProfile profile,
                                                  Attribute attribute) {
        return profile.slots().stream()
            .filter(candidate -> candidate.attribute() == attribute)
            .findFirst().orElseThrow();
    }
}
