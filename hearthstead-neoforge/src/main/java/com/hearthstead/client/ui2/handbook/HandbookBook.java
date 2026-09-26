package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The handbook's content, parsed from data rather than hard-coded.
 *
 * <p>Two kinds of file under {@code assets/hearthstead/handbook/}:
 * <ul>
 *   <li>{@code chapters.json}, the index: reading order as groups of
 *       chapter ids ({@code {"schema":1,"groups":[{"id","title","chapters":[...]}]}}),
 *       plus optional page links owned by the screen lane: {@code "journey"}
 *       (step -> page), {@code "hints"} (item -> page), {@code "links"} (page ->
 *       page its tip links to) and {@code "images"}
 *       (page -> picture, used when the chapter file has none);</li>
 *   <li>{@code chapters/<id>.json}, one per chapter: {@code id}, {@code title}
 *       (lang key), {@code icon} (item id) and {@code pages}.</li>
 * </ul>
 * Each page carries lang keys only (title, 0-4 bullets, numbered "how to
 * use" {@code steps}, an {@code obtain} line, a tip with an optional
 * {@code link} to another page, optional
 * "more detail" paragraphs), crafting {@code recipes} (recipe ids, drawn
 * from the client's RecipeManager), an optional image, key-hint chips naming real
 * KeyMappings, item icons, the Journey steps it explains and the items whose
 * first pickup should point at it. See {@code COORD/to-codex.md} (26 Sep
 * 11:20) for the authoring rules; {@code HandbookChaptersSchemaTest}
 * enforces them.
 *
 * <p>Pure Java plus Gson: no Minecraft state, so JUnit parses the exact
 * shipped files.
 */
public record HandbookBook(List<Group> groups, List<Chapter> chapters, List<Page> pages,
                           Map<String, String> indexJourney, Map<String, String> indexHints,
                           List<String> problems) {
    public static final int SCHEMA = 1;
    public static final String ROOT = "handbook/";
    public static final String INDEX = ROOT + "chapters.json";

    public enum Placement { AUTO, TOP, SIDE }

    public record Group(String id, String titleKey, List<String> chapterIds) {
    }

    public record Chapter(String id, String titleKey, String icon, String groupId, int index,
                          List<Page> pages) {
        public Page first() {
            return pages.get(0);
        }
    }

    public record Image(String texture, int width, int height, String captionKey, Placement placement) {
    }

    /**
     * One reference row (icon, name, one line). {@code ref} names what it documents,
     * e.g. {@code profession:LUMBERER}, {@code building:WAREHOUSE}, {@code event:caravan},
     * {@code node:timber_rights}, {@code option:features.extendedTrades}; the reference
     * guards use it to prove every live job, building, event, node and switch is covered.
     */
    public record Entry(String ref, String icon, String nameKey, String textKey) {
    }

    /** A key hint: {@code [modifier]+[key] action}, rendered with the player's real binding. */
    public record KeyChip(String key, String modifier, String fallback, boolean hold, String actionKey) {
    }

    public record Page(String id, String chapterId, int chapterIndex, int indexInChapter, int globalIndex,
                       String titleKey, Image image, List<String> bullets, List<String> text,
                       List<KeyChip> keys, List<String> items, String tipKey, List<String> journey,
                       List<String> hintItems, List<String> recipes, List<String> steps, String obtainKey,
                       String link, List<Entry> entries) {
    }

    public static HandbookBook empty() {
        return new HandbookBook(List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of("empty"));
    }

    /** Chapter-file path for a chapter id, relative to the namespace root. */
    public static String chapterPath(String chapterId) {
        return ROOT + "chapters/" + chapterId + ".json";
    }

    /**
     * Parses the index and every chapter it names. A chapter file that is
     * missing or broken is skipped and reported in {@link #problems()}
     * rather than taking the whole book down.
     */
    public static HandbookBook parse(JsonObject index, Function<String, JsonObject> chapterLoader) {
        List<String> problems = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        List<Chapter> chapters = new ArrayList<>();
        List<Page> pages = new ArrayList<>();
        if (index == null) {
            return empty();
        }
        if (index.has("schema") && index.get("schema").getAsInt() != SCHEMA) {
            problems.add("index schema " + index.get("schema") + " != " + SCHEMA);
        }
        Map<String, String> linkOverrides = links(index, "links");
        Map<String, JsonObject> imageOverrides = new LinkedHashMap<>();
        if (index.has("images") && index.get("images").isJsonObject()) {
            for (var e : index.getAsJsonObject("images").entrySet()) {
                imageOverrides.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        for (JsonElement ge : array(index, "groups")) {
            JsonObject g = ge.getAsJsonObject();
            String groupId = str(g, "id");
            List<String> ids = strings(g, "chapters");
            groups.add(new Group(groupId, str(g, "title"), ids));
            for (String chapterId : ids) {
                JsonObject c;
                try {
                    c = chapterLoader.apply(chapterId);
                } catch (RuntimeException e) {
                    problems.add(chapterId + ": " + e.getMessage());
                    continue;
                }
                if (c == null) {
                    problems.add(chapterId + ": missing " + chapterPath(chapterId));
                    continue;
                }
                try {
                    Chapter chapter = chapter(c, chapterId, groupId, chapters.size(), pages.size(), imageOverrides,
                        linkOverrides, problems);
                    if (chapter.pages().isEmpty()) {
                        problems.add(chapterId + ": no pages");
                        continue;
                    }
                    chapters.add(chapter);
                    pages.addAll(chapter.pages());
                } catch (RuntimeException e) {
                    problems.add(chapterId + ": " + e);
                }
            }
        }
        return new HandbookBook(List.copyOf(groups), List.copyOf(chapters), List.copyOf(pages),
            links(index, "journey"), links(index, "hints"), List.copyOf(problems));
    }

    private static Chapter chapter(JsonObject c, String expectedId, String groupId, int chapterIndex,
                                   int firstGlobal, Map<String, JsonObject> imageOverrides,
                                   Map<String, String> linkOverrides, List<String> problems) {
        String id = str(c, "id");
        if (!expectedId.equals(id)) {
            problems.add(expectedId + ": file id is '" + id + "'");
        }
        List<Page> pages = new ArrayList<>();
        int n = 0;
        for (JsonElement pe : array(c, "pages")) {
            JsonObject p = pe.getAsJsonObject();
            Image image = null;
            JsonObject i = p.has("image") ? p.getAsJsonObject("image") : imageOverrides.get(str(p, "id"));
            if (i != null) {
                image = new Image(str(i, "texture"), num(i, "width", 256), num(i, "height", 144),
                    str(i, "caption"), placement(str(i, "placement")));
            }
            List<KeyChip> keys = new ArrayList<>();
            for (JsonElement ke : array(p, "keys")) {
                JsonObject k = ke.getAsJsonObject();
                keys.add(new KeyChip(str(k, "key"), str(k, "modifier"), str(k, "fallback"),
                    k.has("hold") && k.get("hold").getAsBoolean(), str(k, "action")));
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonElement ee : array(p, "entries")) {
                JsonObject e = ee.getAsJsonObject();
                entries.add(new Entry(str(e, "ref"), str(e, "icon"), str(e, "name"), str(e, "text")));
            }
            pages.add(new Page(str(p, "id"), expectedId, chapterIndex, n, firstGlobal + n,
                str(p, "title"), image, strings(p, "bullets"), strings(p, "text"), List.copyOf(keys),
                strings(p, "items"), str(p, "tip"), strings(p, "journey"), strings(p, "hint_items"),
                strings(p, "recipes"), strings(p, "steps"), str(p, "obtain"),
                p.has("link") ? str(p, "link") : linkOverrides.get(str(p, "id")), List.copyOf(entries)));
            n++;
        }
        return new Chapter(expectedId, str(c, "title"), str(c, "icon"), groupId, chapterIndex,
            List.copyOf(pages));
    }

    /** Index-level links ({@code "journey": {"fj_..": "page.id"}}, {@code "hints": {"item": "page.id"}}). */
    private static Map<String, String> links(JsonObject index, String name) {
        Map<String, String> out = new LinkedHashMap<>();
        if (index.has(name) && index.get(name).isJsonObject()) {
            for (var e : index.getAsJsonObject(name).entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        }
        return Map.copyOf(out);
    }

    private static Placement placement(String s) {
        if (s == null) return Placement.AUTO;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "top" -> Placement.TOP;
            case "side" -> Placement.SIDE;
            default -> Placement.AUTO;
        };
    }

    private static JsonArray array(JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonArray() ? o.getAsJsonArray(name) : new JsonArray();
    }

    private static List<String> strings(JsonObject o, String name) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(o, name)) out.add(e.getAsString());
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull() ? o.get(name).getAsString() : null;
    }

    private static int num(JsonObject o, String name, int fallback) {
        return o.has(name) ? o.get(name).getAsInt() : fallback;
    }

    // ------------------------------------------------------------ lookups

    /** Every {@code ref} any page documents (reference-guard seam). */
    public java.util.Set<String> refs() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Page p : pages) for (Entry e : p.entries()) if (e.ref() != null) out.add(e.ref());
        return out;
    }

    /** Every KeyMapping name used by a key chip on any page. */
    public java.util.Set<String> keyNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Page p : pages) {
            for (KeyChip k : p.keys()) {
                out.add(k.key());
                if (k.modifier() != null) out.add(k.modifier());
            }
        }
        return out;
    }

    public Page page(String pageId) {
        if (pageId == null) return null;
        for (Page p : pages) if (p.id().equals(pageId)) return p;
        return null;
    }

    public Chapter chapter(int index) {
        return chapters.get(index);
    }

    public Chapter chapterOf(Page page) {
        return chapters.get(page.chapterIndex());
    }

    /** The page that explains a Journey step ({@code fj_...}, with or without namespace). */
    public Page pageForJourney(String step) {
        if (step == null) return null;
        // Accepts "fj_010_found_hearth", "journey/fj_010_found_hearth" or the full id.
        String path = step.substring(Math.max(step.lastIndexOf(':'), step.lastIndexOf('/')) + 1);
        for (Page p : pages) if (p.journey().contains(path)) return p;
        return page(indexJourney.get(path));
    }

    /** Item id -> page, for the one-time "you just got X" hints. */
    public Map<String, Page> hintPages() {
        Map<String, Page> out = new LinkedHashMap<>();
        for (Page p : pages) for (String item : p.hintItems()) out.putIfAbsent(item, p);
        for (var e : indexHints.entrySet()) {
            Page p = page(e.getValue());
            if (p != null) out.putIfAbsent(e.getKey(), p);
        }
        return out;
    }

    /** Every lang key the book reads, in reading order (lang contract seam). */
    public List<String> langKeys() {
        List<String> keys = new ArrayList<>();
        for (Group g : groups) if (g.titleKey() != null) keys.add(g.titleKey());
        for (Chapter c : chapters) {
            keys.add(c.titleKey());
            for (Page p : c.pages()) {
                if (p.titleKey() != null) keys.add(p.titleKey());
                keys.addAll(p.bullets());
                keys.addAll(p.steps());
                for (Entry e : p.entries()) {
                    if (e.nameKey() != null) keys.add(e.nameKey());
                    if (e.textKey() != null) keys.add(e.textKey());
                }
                if (p.obtainKey() != null) keys.add(p.obtainKey());
                keys.addAll(p.text());
                if (p.tipKey() != null) keys.add(p.tipKey());
                if (p.image() != null && p.image().captionKey() != null) keys.add(p.image().captionKey());
                for (KeyChip k : p.keys()) if (k.actionKey() != null) keys.add(k.actionKey());
            }
        }
        return List.copyOf(keys);
    }
}
