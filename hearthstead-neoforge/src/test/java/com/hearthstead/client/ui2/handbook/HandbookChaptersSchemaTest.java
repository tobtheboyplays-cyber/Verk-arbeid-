package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonObject;
import com.hearthstead.settlement.journey.JourneyIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handbook data contract: every chapter file is reachable from the
 * index, every lang key, texture, key binding, item and Journey step a page
 * names actually exists, and the text respects the authoring limits that
 * keep pages short (see COORD/to-codex.md, 26 Sep 11:20).
 */
class HandbookChaptersSchemaTest {
    private static final Pattern PAGE_ID = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final Set<String> VANILLA_KEYS = Set.of("key.use", "key.attack", "key.sneak", "key.jump",
        "key.sprint", "key.inventory", "key.drop", "key.swapOffhand", "key.pickItem", "key.forward", "key.back",
        "key.left", "key.right", "key.chat", "key.playerlist", "key.togglePerspective");
    private static final long TEXTURE_BUDGET = 4L * 1024 * 1024;

    @Test
    void theIndexParsesWithoutProblemsAndReachesEveryChapterFile() throws Exception {
        HandbookBook book = HandbookTestData.book();
        assertTrue(book.problems().isEmpty(), "handbook problems: " + book.problems());
        assertFalse(book.chapters().isEmpty());
        assertEquals("start_here", book.chapters().get(0).id(), "the book opens on Start here");

        Set<String> indexed = new HashSet<>();
        for (HandbookBook.Chapter c : book.chapters()) {
            assertTrue(indexed.add(c.id()), "chapter listed twice: " + c.id());
        }
        URL dir = getClass().getClassLoader().getResource(HandbookTestData.ASSETS + "handbook/chapters");
        assertNotNull(dir, "chapters directory");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path f : files.toList()) {
                String id = f.getFileName().toString().replace(".json", "");
                assertTrue(indexed.contains(id), f.getFileName() + " exists but chapters.json never shows it");
            }
        }
    }

    @Test
    void everyPageIsWellFormedAndEveryReferenceResolves() throws Exception {
        HandbookBook book = HandbookTestData.book();
        JsonObject en = HandbookTestData.english();
        Set<String> ids = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (HandbookBook.Chapter c : book.chapters()) {
            text(en, c.titleKey(), 32, "chapter title", failures);
            item(en, c.icon(), "icon of " + c.id(), failures);
            for (HandbookBook.Page p : c.pages()) {
                String where = p.id() + ": ";
                if (!ids.add(p.id())) failures.add(where + "duplicate page id");
                if (!PAGE_ID.matcher(p.id()).matches() || !p.id().startsWith(c.id() + ".")) {
                    failures.add(where + "id must be '" + c.id() + ".<slug>'");
                }
                if (p.bullets().isEmpty() && p.text().isEmpty()) failures.add(where + "no bullets and no text");
                if (p.bullets().size() > 4) failures.add(where + "more than 4 bullets");
                if (p.titleKey() != null) text(en, p.titleKey(), 40, where + "title", failures);
                for (String b : p.bullets()) text(en, b, 140, where + "bullet", failures);
                for (String t : p.text()) text(en, t, Integer.MAX_VALUE, where + "detail", failures);
                if (p.tipKey() != null) text(en, p.tipKey(), 120, where + "tip", failures);
                if (p.items().size() > 6) failures.add(where + "more than 6 items");
                for (String i : p.items()) item(en, i, where + "item", failures);
                for (HandbookBook.KeyChip k : p.keys()) {
                    key(en, k.key(), where, failures);
                    if (k.modifier() != null) key(en, k.modifier(), where, failures);
                    if (k.fallback() != null) key(en, k.fallback(), where, failures);
                    text(en, k.actionKey(), 40, where + "key action", failures);
                }
                if (p.image() != null) image(en, p.image(), where, failures);
                for (String step : p.journey()) {
                    if (!journeySteps().contains(step)) failures.add(where + "unknown Journey step " + step);
                }
                for (String i : p.hintItems()) item(en, i, where + "hint item", failures);
                for (String st : p.steps()) text(en, st, 140, where + "step", failures);
                if (p.steps().size() > 6) failures.add(where + "more than 6 steps");
                if (p.obtainKey() != null) text(en, p.obtainKey(), 160, where + "obtain", failures);
                if (p.link() != null && book.page(p.link()) == null) failures.add(where + "link to unknown page " + p.link());
                for (String r : p.recipes()) {
                    ResourceLocation rl = ResourceLocation.tryParse(r);
                    if (rl == null || (!rl.getNamespace().equals("minecraft") && HandbookTestData.json(
                            "data/" + rl.getNamespace() + "/recipe/" + rl.getPath() + ".json") == null)) {
                        failures.add(where + "recipe " + r + " does not exist");
                    }
                }
            }
        }
        for (var e : book.indexJourney().entrySet()) {
            if (!journeySteps().contains(e.getKey())) failures.add("index journey: unknown step " + e.getKey());
            if (book.page(e.getValue()) == null) failures.add("index journey: unknown page " + e.getValue());
        }
        for (var e : book.indexHints().entrySet()) {
            item(en, e.getKey(), "index hint", failures);
            if (book.page(e.getValue()) == null) failures.add("index hint: unknown page " + e.getValue());
        }
        JsonObject index = HandbookTestData.json(HandbookTestData.ASSETS + HandbookBook.INDEX);
        if (index.has("images")) {
            for (String pageId : index.getAsJsonObject("images").keySet()) {
                if (book.page(pageId) == null) failures.add("index image: unknown page " + pageId);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void everyJourneyStepHasAHandbookPage() throws Exception {
        HandbookBook book = HandbookTestData.book();
        List<String> missing = new ArrayList<>();
        for (String step : journeySteps()) {
            if (book.pageForJourney(step) == null) missing.add(step);
        }
        assertTrue(missing.isEmpty(), "Journey steps with no handbook page: " + missing);
        for (String first : List.of("fj_010_found_hearth", "fj_020_open_journey", "fj_030_appoint_mayor",
                "fj_100_unlock_lumber_camp", "fj_110_link_lumber_camp", "fj_120_staff_lumber_camp",
                "fj_140_set_lumber_zone", "fj_160_give_lumberer_axe")) {
            assertEquals("start_here", book.pageForJourney(first).chapterId(),
                first + " is taught by the Start here chapter");
        }
    }

    @Test
    void keyItemsPointAtAHintPage() {
        HandbookBook book = HandbookTestData.book();
        for (String item : List.of("hearthstead:builders_plan", "hearthstead:survey_rod", "hearthstead:guard_emblem")) {
            assertNotNull(book.hintPages().get(item), item + " shows a one-time handbook hint");
        }
    }

    @Test
    void picturesStayInsideTheTextureBudget() throws Exception {
        URL dir = getClass().getClassLoader().getResource(HandbookTestData.ASSETS + "textures/gui/handbook");
        assertNotNull(dir, "textures/gui/handbook");
        long total = 0;
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path f : files.toList()) total += Files.size(f);
        }
        assertTrue(total < TEXTURE_BUDGET, "handbook textures use " + total + " bytes");
    }

    // ------------------------------------------------------------ helpers

    private static Set<String> steps;

    static Set<String> journeySteps() throws IllegalAccessException {
        if (steps != null) return steps;
        Set<String> out = new HashSet<>();
        for (Field f : JourneyIds.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("FJ_")
                && f.getType() == ResourceLocation.class) {
                String path = ((ResourceLocation) f.get(null)).getPath();
                out.add(path.substring(path.lastIndexOf('/') + 1));
            }
        }
        steps = out;
        return out;
    }

    private static void text(JsonObject en, String key, int max, String what, List<String> failures) {
        if (key == null) {
            failures.add(what + ": no lang key");
            return;
        }
        if (!en.has(key)) {
            failures.add(what + ": missing lang key " + key);
            return;
        }
        String value = en.get(key).getAsString();
        if (value.isBlank()) failures.add(what + ": blank " + key);
        if (value.length() > max) failures.add(what + ": " + key + " is " + value.length() + " chars (max " + max + ")");
    }

    private static void item(JsonObject en, String id, String what, List<String> failures) {
        if (id == null || !ITEM_ID.matcher(id).matches()) {
            failures.add(what + ": bad item id " + id);
            return;
        }
        if (id.startsWith("hearthstead:")) {
            String path = id.substring("hearthstead:".length());
            if (!en.has("item.hearthstead." + path) && !en.has("block.hearthstead." + path)) {
                failures.add(what + ": " + id + " has no item/block name, so it is not a registered item");
            }
        }
    }

    private static void key(JsonObject en, String name, String where, List<String> failures) {
        if (VANILLA_KEYS.contains(name)) return;
        if (name != null && name.startsWith("key.hearthstead.") && en.has(name)) return;
        failures.add(where + "unknown key binding " + name);
    }

    private static void image(JsonObject en, HandbookBook.Image img, String where, List<String> failures)
        throws Exception {
        ResourceLocation loc = ResourceLocation.tryParse(img.texture());
        if (loc == null) {
            failures.add(where + "bad texture " + img.texture());
            return;
        }
        String path = "assets/" + loc.getNamespace() + "/" + loc.getPath();
        try (InputStream in = HandbookChaptersSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                failures.add(where + "missing texture " + path);
                return;
            }
            DataInputStream d = new DataInputStream(in);
            d.skipBytes(16);
            int w = d.readInt();
            int h = d.readInt();
            if (w != img.width() || h != img.height()) {
                failures.add(where + path + " is " + w + "x" + h + ", page says " + img.width() + "x" + img.height());
            }
            if (w > 512 || h > 512) failures.add(where + path + " is larger than 512 px");
        }
        if (img.captionKey() != null) text(en, img.captionKey(), 80, where + "caption", failures);
    }
}
