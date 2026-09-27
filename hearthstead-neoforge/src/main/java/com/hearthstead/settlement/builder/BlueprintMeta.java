package com.hearthstead.settlement.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The metadata JSON beside one blueprint structure
 * ({@code data/hearthstead/blueprints/<id>.json}, BUILDER.md section 3).
 *
 * <p>Only what the Builder needs is parsed; unknown fields are ignored so
 * the content lane can add presentation data without breaking the loader.
 * The {@code materials} list in the file is informational (the catalog can
 * show it without the template); jobs always recompute from the template.
 */
public record BlueprintMeta(String id,
                            @Nullable String name,
                            String category,
                            Kind kind,
                            @Nullable String buildingType,
                            String style,
                            String requires,
                            int groundLevel,
                            @Nullable int[] plaquePos,
                            @Nullable String plaqueFacing,
                            @Nullable String segment,
                            String structure,
                            List<int[]> work,
                            List<FurnitureSwap> furniture,
                            @Nullable Integer eaveY) {

    /**
     * Optional furniture substitution (owner, 26 Sep): the NBT holds vanilla
     * blocks, and when the named mod is loaded the cell at {@code pos} (or
     * every cell holding {@code from}, when no pos is given) becomes
     * {@code to}, a block-state string such as
     * {@code another_furniture:oak_chair[facing=north]}. No hard dependency:
     * a missing mod or an unknown block simply keeps the vanilla block, and
     * material costs follow whichever block is actually placed.
     */
    public record FurnitureSwap(String mod, @Nullable int[] pos, @Nullable String from, String to) {
    }

    public enum Kind {
        BUILDING("building"),
        DEFENSE("defense"),
        BARRICADE("barricade"),
        /** Small pieces with no plaque: a well, a stall, a lamp post, a plaza. */
        DECORATION("decoration");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Kind byId(String id) {
            for (Kind kind : values()) {
                if (kind.id.equalsIgnoreCase(id)) {
                    return kind;
                }
            }
            return BUILDING;
        }
    }

    /** Parses one metadata file. The id falls back to the file name. */
    public static BlueprintMeta parse(String fileId, JsonObject json) {
        String id = string(json, "id", fileId);
        String structure = string(json, "structure", "hearthstead:blueprints/" + id);
        int[] plaque = null;
        String facing = null;
        if (json.has("plaque") && json.get("plaque").isJsonObject()) {
            JsonObject p = json.getAsJsonObject("plaque");
            plaque = intArray(p.get("pos"));
            facing = string(p, "facing", "south");
        }
        List<int[]> work = new ArrayList<>();
        if (json.has("work") && json.get("work").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("work")) {
                int[] pos = intArray(e);
                if (pos != null) {
                    work.add(pos);
                }
            }
        }
        List<FurnitureSwap> furniture = new ArrayList<>();
        if (json.has("furniture") && json.get("furniture").isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray("furniture")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject f = e.getAsJsonObject();
                String to = string(f, "to", null);
                if (to == null) {
                    to = string(f, "replacement", null);
                }
                if (to == null) {
                    continue;
                }
                String mod = string(f, "mod", to.contains(":") ? to.substring(0, to.indexOf(':')) : "another_furniture");
                furniture.add(new FurnitureSwap(mod, intArray(f.get("pos")),
                    string(f, "from", string(f, "vanilla", null)), to));
            }
        }
        return new BlueprintMeta(id,
            json.has("name") ? json.get("name").getAsString() : null,
            string(json, "category", "homes"),
            Kind.byId(string(json, "kind", "building")),
            json.has("building_type") && !json.get("building_type").isJsonNull()
                ? json.get("building_type").getAsString() : null,
            string(json, "style", "timber"),
            string(json, "requires", "builders_hut"),
            json.has("ground_level") ? json.get("ground_level").getAsInt() : 0,
            plaque, facing,
            json.has("segment") && !json.get("segment").isJsonNull()
                ? json.get("segment").getAsString() : null,
            structure, List.copyOf(work), List.copyOf(furniture),
            json.has("eave_y") && !json.get("eave_y").isJsonNull() ? json.get("eave_y").getAsInt() : null);
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    @Nullable
    private static int[] intArray(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            return null;
        }
        return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
    }

    /** Lang key used when the file carries no display name. */
    public String nameKey() {
        return "hearthstead.blueprint." + id;
    }

    /** Horizontal facing index (0 north, 1 east, 2 south, 3 west) of the plaque. */
    public int plaqueFacingIndex() {
        if (plaqueFacing == null) {
            return 2;
        }
        return switch (plaqueFacing.toLowerCase(java.util.Locale.ROOT)) {
            case "north" -> 0;
            case "east" -> 1;
            case "west" -> 3;
            default -> 2;
        };
    }
}
