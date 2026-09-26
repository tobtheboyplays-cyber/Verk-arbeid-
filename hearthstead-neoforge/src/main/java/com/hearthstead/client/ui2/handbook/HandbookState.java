package com.hearthstead.client.ui2.handbook;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Client-only reading state that survives restarts: the last page read and
 * which one-time item hints were already shown. Stored in
 * {@code config/hearthstead-handbook.json}; a missing or broken file just
 * means "fresh reader".
 */
public final class HandbookState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static boolean loaded;
    private static String lastPage;
    private static final Set<String> SEEN_HINTS = new LinkedHashSet<>();

    private HandbookState() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("hearthstead-handbook.json");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
                if (o.has("lastPage")) lastPage = o.get("lastPage").getAsString();
                if (o.has("seenHints")) {
                    for (var e : o.getAsJsonArray("seenHints")) SEEN_HINTS.add(e.getAsString());
                }
            }
        } catch (Exception e) {
            Hearthstead.LOGGER.warn("Handbook state unreadable, starting fresh: {}", e.toString());
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            if (lastPage != null) o.addProperty("lastPage", lastPage);
            JsonArray seen = new JsonArray();
            SEEN_HINTS.forEach(seen::add);
            o.add("seenHints", seen);
            Path f = file();
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            Hearthstead.LOGGER.warn("Handbook state not saved: {}", e.toString());
        }
    }

    public static String lastPage() {
        ensureLoaded();
        return lastPage;
    }

    public static void setLastPage(String pageId) {
        ensureLoaded();
        if (pageId != null && !pageId.equals(lastPage)) {
            lastPage = pageId;
            save();
        }
    }

    public static boolean hintSeen(String itemId) {
        ensureLoaded();
        return SEEN_HINTS.contains(itemId);
    }

    public static void markHintSeen(String itemId) {
        ensureLoaded();
        if (SEEN_HINTS.add(itemId)) save();
    }
}
