package com.hearthstead.client.ui2.handbook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Reads the handbook from the client resource manager (so resource packs can re-skin it). */
public final class HandbookLoader {
    private static final Map<String, Boolean> TEXTURE_EXISTS = new HashMap<>();

    private HandbookLoader() {
    }

    public static HandbookBook load() {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        TEXTURE_EXISTS.clear();
        try {
            JsonObject index = read(rm, Hearthstead.id(HandbookBook.INDEX));
            HandbookBook book = HandbookBook.parse(index, id -> read(rm, Hearthstead.id(HandbookBook.chapterPath(id))));
            for (String problem : book.problems()) {
                Hearthstead.LOGGER.warn("Handbook: {}", problem);
            }
            return book;
        } catch (Exception e) {
            Hearthstead.LOGGER.error("Handbook could not be loaded", e);
            return HandbookBook.empty();
        }
    }

    private static HandbookItems items;

    /** The item catalog, cached; {@link #invalidate()} drops it (resource reload). */
    public static HandbookItems items() {
        if (items == null) {
            try {
                items = HandbookItems.parse(read(Minecraft.getInstance().getResourceManager(),
                    Hearthstead.id(HandbookItems.PATH)));
            } catch (Exception e) {
                Hearthstead.LOGGER.error("Handbook item catalog could not be loaded", e);
                items = HandbookItems.empty();
            }
        }
        return items;
    }

    public static void invalidate() {
        items = null;
        TEXTURE_EXISTS.clear();
    }

    private static JsonObject read(ResourceManager rm, ResourceLocation loc) {
        Optional<Resource> res = rm.getResource(loc);
        if (res.isEmpty()) return null;
        try (Reader r = res.get().openAsReader()) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(loc + ": " + e.getMessage(), e);
        }
    }

    /** True when a page picture's PNG actually ships; pages without one simply show no picture. */
    public static boolean textureExists(String texture) {
        if (texture == null) return false;
        return TEXTURE_EXISTS.computeIfAbsent(texture, t -> {
            ResourceLocation loc = ResourceLocation.tryParse(t);
            return loc != null && Minecraft.getInstance().getResourceManager().getResource(loc).isPresent();
        });
    }
}
