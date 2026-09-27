package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The item catalog ({@code assets/hearthstead/handbook/items.json}): every
 * item the mod registers belongs to one family, and each family says how to
 * GET it (a recipe the guard test finds, or an {@code obtain} line), how to
 * USE it (a one-line {@code use} key plus 2-4 {@code steps} shown under
 * Shift in the tooltip) and which handbook page explains it.
 *
 * <pre>{"schema":1,
 *  "families": {"work_scepter": {"page":"items.work_scepter","use":"...","steps":["..."],
 *                                "obtain":"...","hint":true}},
 *  "items": {"hearthstead:work_scepter": "work_scepter"}}</pre>
 *
 * A family marked {@code "internal": "<reason>"} (display-only props) is
 * exempt: no tooltip, page or recipe, but it must say why.
 * {@code ItemDocumentationGuardTest} fails when a registered item is missing.
 */
public record HandbookItems(Map<String, Family> families, Map<String, String> items) {
    public static final String PATH = HandbookBook.ROOT + "items.json";

    public record Family(String id, String page, String useKey, List<String> steps, String obtainKey,
                         boolean hint, String internal) {
        public boolean isInternal() {
            return internal != null && !internal.isBlank();
        }
    }

    public static HandbookItems empty() {
        return new HandbookItems(Map.of(), Map.of());
    }

    public static HandbookItems parse(JsonObject root) {
        if (root == null) return empty();
        Map<String, Family> families = new LinkedHashMap<>();
        if (root.has("families")) {
            for (var e : root.getAsJsonObject("families").entrySet()) {
                JsonObject f = e.getValue().getAsJsonObject();
                List<String> steps = new ArrayList<>();
                if (f.has("steps")) for (JsonElement s : f.getAsJsonArray("steps")) steps.add(s.getAsString());
                families.put(e.getKey(), new Family(e.getKey(), str(f, "page"), str(f, "use"), List.copyOf(steps),
                    str(f, "obtain"), f.has("hint") && f.get("hint").getAsBoolean(), str(f, "internal")));
            }
        }
        Map<String, String> items = new LinkedHashMap<>();
        if (root.has("items")) {
            for (var e : root.getAsJsonObject("items").entrySet()) items.put(e.getKey(), e.getValue().getAsString());
        }
        return new HandbookItems(Map.copyOf(families), Map.copyOf(items));
    }

    private static String str(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull() ? o.get(name).getAsString() : null;
    }

    /** The family of an item id ({@code ns:path}), or null when it is not catalogued. */
    public Family familyOf(String itemId) {
        String family = items.get(itemId);
        return family == null ? null : families.get(family);
    }

    /** Every lang key the catalog reads (for the lang contract). */
    public List<String> langKeys() {
        List<String> keys = new ArrayList<>();
        for (Family f : families.values()) {
            if (f.useKey() != null) keys.add(f.useKey());
            keys.addAll(f.steps());
            if (f.obtainKey() != null) keys.add(f.obtainKey());
        }
        return List.copyOf(keys);
    }
}
